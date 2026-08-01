package com.example.accelledsinertial;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends Activity
        implements SensorEventListener, LocationListener {

    private static final int REQ_LOCATION = 7;

    private static final float LPF_STAGE_1_TAU_S = 0.075f;
    private static final float LPF_STAGE_2_TAU_S = 0.135f;
    private static final float GRAVITY_TAU_S = 0.90f;

    private static final float DETECT_THRESHOLD = 0.12f;
    private static final float RELEASE_THRESHOLD = 0.065f;
    private static final float REQUIRED_PERSISTENCE_S = 0.28f;
    private static final float RELEASE_HOLD_S = 0.12f;

    // Las escalas siguen siendo diferentes según el signo.
    private static final float GREEN_FULL_SCALE = 3.0f;
    private static final float RED_FULL_SCALE = 9.0f;
    private static final float VISUAL_DEAD_ZONE = 0.015f;
    private static final long GPS_STALE_MS = 2500L;

    private static final int HISTORY_SIZE = 50;
    private static final int[] AVERAGE_WINDOWS = new int[]{2, 5, 10, 20, 50};

    private SensorManager sensorManager;
    private Sensor motionSensor;
    private boolean nativeLinearSensor;
    private LocationManager locationManager;
    private LedView view;
    private SharedPreferences prefs;

    private final float[] gravity = new float[3];
    private final float[] linear = new float[3];
    private final float[] filteredStage1 = new float[3];
    private final float[] filteredStage2 = new float[3];
    private final float[] axis = new float[]{1f, 0f, 0f};

    private final float[] accelerationHistory = new float[HISTORY_SIZE];
    private final float[] averages = new float[AVERAGE_WINDOWS.length];
    private int historyNext;
    private int historyCount;

    private float zeroBias;
    private long lastSensorNs;
    private float rawLongitudinal;
    private float longitudinal;
    private float gpsSpeed;
    private float gpsSpeedAccuracy = Float.NaN;
    private float gpsHorizontalAccuracy = Float.NaN;
    private long lastGpsMs;
    private int gnssSatellitesVisible;
    private int gnssSatellitesUsed;
    private boolean gnssStarted;
    private boolean gnssCallbackRegistered;
    private boolean showInfo = true;
    private boolean calibrated;

    private int candidateSign;
    private int confirmedSign;
    private float candidateTimeS;
    private float releaseTimeS;

    private int calibrationState;
    private long calibrationStartMs;
    private int calibrationSamples;
    private int zeroSamples;
    private final double[][] covariance = new double[3][3];
    private final double[] calibrationMean = new double[3];
    private final double[] zeroMean = new double[3];
    private String message = "Fija el teléfono y pulsa CALIBRAR";

    private final GnssStatus.Callback gnssStatusCallback =
            new GnssStatus.Callback() {
                @Override
                public void onStarted() {
                    gnssStarted = true;
                    if (view != null) view.invalidate();
                }

                @Override
                public void onStopped() {
                    gnssStarted = false;
                    gnssSatellitesVisible = 0;
                    gnssSatellitesUsed = 0;
                    if (view != null) view.invalidate();
                }

                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    gnssStarted = true;
                    gnssSatellitesVisible = status.getSatelliteCount();
                    int used = 0;
                    for (int i = 0; i < gnssSatellitesVisible; i++) {
                        if (status.usedInFix(i)) used++;
                    }
                    gnssSatellitesUsed = used;
                    if (view != null) view.invalidate();
                }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = 1f;
        getWindow().setAttributes(params);
        hideUi();

        prefs = getSharedPreferences("calibration", MODE_PRIVATE);
        calibrated = prefs.getBoolean("valid", false);
        if (calibrated) {
            axis[0] = prefs.getFloat("x", 1f);
            axis[1] = prefs.getFloat("y", 0f);
            axis[2] = prefs.getFloat("z", 0f);
            zeroBias = prefs.getFloat("bias", 0f);
            message = "Calibración cargada";
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        nativeLinearSensor = motionSensor != null;
        if (motionSensor == null) {
            motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        view = new LedView(this);
        setContentView(view);
        startLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideUi();
        if (motionSensor != null) {
            sensorManager.registerListener(
                    this, motionSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        startLocation();
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        stopLocation();
        super.onPause();
    }

    private void hideUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void startLocation() {
        if (locationManager == null) return;

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION);
            return;
        }

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    this,
                    Looper.getMainLooper());
            if (!gnssCallbackRegistered) {
                gnssCallbackRegistered =
                        locationManager.registerGnssStatusCallback(
                                gnssStatusCallback,
                                new Handler(Looper.getMainLooper()));
            }
        } catch (Exception ignored) {
        }
    }

    private void stopLocation() {
        try {
            if (locationManager != null) {
                locationManager.removeUpdates(this);
                if (gnssCallbackRegistered) {
                    locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
                    gnssCallbackRegistered = false;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION
                && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long ns = event.timestamp;
        float dt = lastSensorNs == 0L
                ? 0.02f
                : clamp((ns - lastSensorNs) / 1_000_000_000f, 0.002f, 0.10f);
        lastSensorNs = ns;

        if (nativeLinearSensor) {
            linear[0] = event.values[0];
            linear[1] = event.values[1];
            linear[2] = event.values[2];
        } else {
            float gravityAlpha = dt / (GRAVITY_TAU_S + dt);
            for (int i = 0; i < 3; i++) {
                gravity[i] += gravityAlpha * (event.values[i] - gravity[i]);
                linear[i] = event.values[i] - gravity[i];
            }
        }

        float alpha1 = dt / (LPF_STAGE_1_TAU_S + dt);
        float alpha2 = dt / (LPF_STAGE_2_TAU_S + dt);
        for (int i = 0; i < 3; i++) {
            filteredStage1[i] += alpha1 * (linear[i] - filteredStage1[i]);
            filteredStage2[i] += alpha2 * (filteredStage1[i] - filteredStage2[i]);
        }

        updateCalibration();

        if (calibrated && calibrationState == 0) {
            rawLongitudinal = dot(filteredStage2, axis) - zeroBias;
            addAccelerationMeasurement(rawLongitudinal);
            updatePersistenceGate(rawLongitudinal, dt);
        } else {
            rawLongitudinal = 0f;
            resetMotionGate();
        }

        view.invalidate();
    }

    private void addAccelerationMeasurement(float value) {
        accelerationHistory[historyNext] = value;
        historyNext = (historyNext + 1) % HISTORY_SIZE;
        if (historyCount < HISTORY_SIZE) historyCount++;

        for (int i = 0; i < AVERAGE_WINDOWS.length; i++) {
            averages[i] = averageRecent(AVERAGE_WINDOWS[i]);
        }
    }

    private float averageRecent(int requestedCount) {
        int count = Math.min(requestedCount, historyCount);
        if (count == 0) return 0f;

        float sum = 0f;
        for (int i = 0; i < count; i++) {
            int index = historyNext - 1 - i;
            if (index < 0) index += HISTORY_SIZE;
            sum += accelerationHistory[index];
        }
        return sum / count;
    }

    private void clearAccelerationHistory() {
        Arrays.fill(accelerationHistory, 0f);
        Arrays.fill(averages, 0f);
        historyNext = 0;
        historyCount = 0;
    }

    private void updatePersistenceGate(float value, float dt) {
        float magnitude = Math.abs(value);
        int sign = value >= 0f ? 1 : -1;

        if (magnitude >= DETECT_THRESHOLD) {
            releaseTimeS = 0f;

            if (confirmedSign == sign) {
                candidateSign = sign;
                candidateTimeS = REQUIRED_PERSISTENCE_S;
                longitudinal = value;
                return;
            }

            if (candidateSign != sign) {
                candidateSign = sign;
                candidateTimeS = 0f;
            }

            candidateTimeS =
                    Math.min(REQUIRED_PERSISTENCE_S, candidateTimeS + dt);
            if (candidateTimeS >= REQUIRED_PERSISTENCE_S) {
                confirmedSign = sign;
                longitudinal = value;
            } else {
                longitudinal = 0f;
            }
            return;
        }

        if (magnitude > RELEASE_THRESHOLD) {
            releaseTimeS = 0f;
            longitudinal = confirmedSign == sign ? value : 0f;
            candidateTimeS = Math.max(0f, candidateTimeS - dt);
            return;
        }

        candidateTimeS = Math.max(0f, candidateTimeS - 3f * dt);
        if (candidateTimeS == 0f) candidateSign = 0;

        if (confirmedSign != 0) {
            releaseTimeS += dt;
            if (releaseTimeS >= RELEASE_HOLD_S) {
                confirmedSign = 0;
                releaseTimeS = 0f;
                longitudinal = 0f;
            }
        } else {
            longitudinal = 0f;
        }
    }

    private void resetMotionGate() {
        candidateSign = 0;
        confirmedSign = 0;
        candidateTimeS = 0f;
        releaseTimeS = 0f;
        longitudinal = 0f;
    }

    private void beginCalibration() {
        calibrated = false;
        calibrationState = 1;
        calibrationStartMs = SystemClock.elapsedRealtime();
        calibrationSamples = 0;
        zeroSamples = 0;
        resetMotionGate();
        clearAccelerationHistory();

        for (int i = 0; i < 3; i++) {
            calibrationMean[i] = 0;
            zeroMean[i] = 0;
            for (int j = 0; j < 3; j++) covariance[i][j] = 0;
        }

        message = "CALIBRACIÓN: permanece detenido durante 3 s";
    }

    private void updateCalibration() {
        if (calibrationState == 0) return;

        long elapsed = SystemClock.elapsedRealtime() - calibrationStartMs;

        if (calibrationState == 1) {
            if (elapsed > 500L) {
                zeroSamples++;
                for (int i = 0; i < 3; i++) {
                    zeroMean[i] += filteredStage2[i];
                }
            }

            if (elapsed >= 3000L) {
                calibrationState = 2;
                message = "ACELERA SUAVEMENTE HACIA DELANTE durante 5 s";
            }
            return;
        }

        float vectorMagnitude = magnitude(filteredStage2);
        if (vectorMagnitude > 0.10f && vectorMagnitude < 4.5f) {
            calibrationSamples++;
            for (int i = 0; i < 3; i++) {
                calibrationMean[i] += filteredStage2[i];
                for (int j = 0; j < 3; j++) {
                    covariance[i][j] += filteredStage2[i] * filteredStage2[j];
                }
            }
        }

        if (elapsed >= 8000L) finishCalibration();
    }

    private void finishCalibration() {
        calibrationState = 0;

        if (calibrationSamples < 15) {
            message =
                    "Calibración insuficiente. Repite con una aceleración más clara";
            return;
        }

        double[] vector = new double[]{1, 1, 1};
        normalize(vector);

        for (int iteration = 0; iteration < 20; iteration++) {
            double[] next = new double[3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    next[i] += covariance[i][j] * vector[j];
                }
            }
            normalize(next);
            vector = next;
        }

        double sign =
                vector[0] * calibrationMean[0]
                        + vector[1] * calibrationMean[1]
                        + vector[2] * calibrationMean[2];

        if (sign < 0) {
            for (int i = 0; i < 3; i++) vector[i] = -vector[i];
        }

        axis[0] = (float) vector[0];
        axis[1] = (float) vector[1];
        axis[2] = (float) vector[2];

        if (zeroSamples > 0) {
            float[] averageZero =
                    new float[]{
                        (float) (zeroMean[0] / zeroSamples),
                        (float) (zeroMean[1] / zeroSamples),
                        (float) (zeroMean[2] / zeroSamples)
                    };
            zeroBias = dot(averageZero, axis);
        } else {
            zeroBias = 0f;
        }

        calibrated = true;
        prefs.edit()
                .putBoolean("valid", true)
                .putFloat("x", axis[0])
                .putFloat("y", axis[1])
                .putFloat("z", axis[2])
                .putFloat("bias", zeroBias)
                .apply();

        resetMotionGate();
        clearAccelerationHistory();
        message = "Calibrado. Promedios 2/5/10/20/50 activos";
    }

    private void setZero() {
        if (!calibrated) return;

        zeroBias = dot(filteredStage2, axis);
        prefs.edit().putFloat("bias", zeroBias).apply();
        resetMotionGate();
        clearAccelerationHistory();
        message = "Cero ajustado; historial reiniciado";
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null && location.hasSpeed()) {
            gpsSpeed = Math.max(0f, location.getSpeed());
            gpsSpeedAccuracy =
                    location.hasSpeedAccuracy()
                            ? location.getSpeedAccuracyMetersPerSecond()
                            : Float.NaN;
            gpsHorizontalAccuracy =
                    location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
            lastGpsMs = location.getElapsedRealtimeNanos() / 1_000_000L;
            if (view != null) view.invalidate();
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float magnitude(float[] vector) {
        return (float) Math.sqrt(dot(vector, vector));
    }

    private static void normalize(double[] vector) {
        double m =
                Math.sqrt(
                        vector[0] * vector[0]
                                + vector[1] * vector[1]
                                + vector[2] * vector[2]);

        if (m < 1e-9) {
            vector[0] = 1;
            vector[1] = 0;
            vector[2] = 0;
            return;
        }

        for (int i = 0; i < 3; i++) vector[i] /= m;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float signedAverageFillFraction(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= VISUAL_DEAD_ZONE) return 0f;

        float maximum = value >= 0f ? GREEN_FULL_SCALE : RED_FULL_SCALE;
        float adjustedMagnitude = magnitude - VISUAL_DEAD_ZONE;
        float adjustedMaximum = maximum - VISUAL_DEAD_ZONE;
        float normalized =
                (float)
                        (Math.log1p(adjustedMagnitude / 0.15f)
                                / Math.log1p(adjustedMaximum / 0.15f));

        return clamp(normalized, 0f, 1f);
    }

    private final class LedView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectangle = new RectF();

        LedView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();

            drawButton(canvas, 0, "CALIBRAR");
            drawButton(
                    canvas,
                    1,
                    showInfo ? "OCULTAR INFO" : "MOSTRAR INFO");
            drawButton(canvas, 2, "AJUSTAR CERO");

            drawAverageBars(canvas, width, height);
            drawGpsSpeedometer(canvas, width, height);

            if (showInfo) {
                drawInfo(canvas, width, height);
            }

            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.027f);
            paint.setColor(Color.LTGRAY);
            canvas.drawText(message, width / 2f, height * 0.925f, paint);

            paint.setTextSize(height * 0.020f);
            paint.setColor(Color.GRAY);
            canvas.drawText(
                    "Uso experimental · teléfono fijado · no manipular al conducir",
                    width / 2f,
                    height * 0.982f,
                    paint);
        }

        private void drawAverageBars(Canvas canvas, int width, int height) {
            int labelRight = Math.round(width * 0.185f);
            int left = Math.round(width * 0.205f);
            int right = Math.round(width * 0.655f);
            int availablePixels = Math.max(1, right - left);

            float areaTop = height * 0.205f;
            float areaBottom = height * 0.675f;
            float rowGap = height * 0.012f;
            float rowHeight =
                    (areaBottom - areaTop - rowGap * (AVERAGE_WINDOWS.length - 1))
                            / AVERAGE_WINDOWS.length;

            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.022f);
            paint.setColor(Color.LTGRAY);
            canvas.drawText(
                    "PROMEDIOS · zona neutra ±0.015 m/s²",
                    width * 0.02f,
                    height * 0.182f,
                    paint);

            for (int i = 0; i < AVERAGE_WINDOWS.length; i++) {
                int top = Math.round(areaTop + i * (rowHeight + rowGap));
                int bottom = Math.round(top + rowHeight);
                float value = averages[i];
                boolean positive = value > 0f;
                boolean negative = value < 0f;
                int pixels = Math.round(
                        availablePixels * signedAverageFillFraction(value));

                int activeColor = positive
                        ? Color.rgb(0, 255, 70)
                        : negative
                                ? Color.rgb(255, 35, 25)
                                : Color.rgb(70, 70, 70);

                drawSignedBar(
                        canvas,
                        left,
                        right,
                        top,
                        bottom,
                        pixels,
                        negative,
                        activeColor);

                drawAverageLabel(
                        canvas,
                        labelRight,
                        top,
                        bottom,
                        AVERAGE_WINDOWS[i],
                        value,
                        positive,
                        negative);
            }
        }

        private void drawSignedBar(
                Canvas canvas,
                int left,
                int right,
                int top,
                int bottom,
                int activePixels,
                boolean fromRight,
                int activeColor) {

            paint.setAntiAlias(false);
            paint.setAlpha(255);
            paint.setColor(Color.rgb(24, 24, 24));
            canvas.drawRect(left, top, right, bottom, paint);

            int pixels = Math.max(0, Math.min(right - left, activePixels));
            if (pixels == 0) return;

            int activeLeft = fromRight ? right - pixels : left;
            int activeRight = fromRight ? right : left + pixels;

            paint.setColor(activeColor);
            canvas.drawRect(activeLeft, top, activeRight, bottom, paint);

            int edgeX = fromRight ? activeLeft : activeRight - 1;
            paint.setColor(Color.WHITE);
            paint.setAlpha(155);
            canvas.drawRect(edgeX, top, edgeX + 1, bottom, paint);
            paint.setAlpha(255);
        }

        private void drawAverageLabel(
                Canvas canvas,
                int labelRight,
                int top,
                int bottom,
                int window,
                float value,
                boolean positive,
                boolean negative) {

            float centerY = (top + bottom) / 2f;

            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize((bottom - top) * 0.38f);
            paint.setColor(Color.WHITE);

            float baseline = centerY - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText(
                    Integer.toString(window),
                    labelRight * 0.32f,
                    baseline,
                    paint);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize((bottom - top) * 0.30f);
            paint.setColor(
                    positive
                            ? Color.rgb(155, 255, 180)
                            : negative
                                    ? Color.rgb(255, 175, 170)
                                    : Color.LTGRAY);

            canvas.drawText(
                    String.format(Locale.US, "%+.4f", value),
                    labelRight * 0.40f,
                    baseline,
                    paint);
        }

        private void drawGpsSpeedometer(Canvas canvas, int width, int height) {
            float left = width * 0.680f;
            float right = width * 0.975f;
            float top = height * 0.205f;
            float bottom = height * 0.675f;

            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(12, 16, 20));
            rectangle.set(left, top, right, bottom);
            canvas.drawRoundRect(rectangle, height * 0.025f, height * 0.025f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, height * 0.003f));
            paint.setColor(Color.rgb(90, 105, 120));
            canvas.drawRoundRect(rectangle, height * 0.025f, height * 0.025f, paint);
            paint.setStyle(Paint.Style.FILL);

            long ageMs =
                    lastGpsMs == 0L
                            ? Long.MAX_VALUE
                            : Math.max(0L, SystemClock.elapsedRealtime() - lastGpsMs);
            boolean fresh = lastGpsMs != 0L && ageMs <= GPS_STALE_MS;

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.028f);
            paint.setColor(Color.rgb(170, 190, 210));
            canvas.drawText(
                    "VELOCIDAD GPS",
                    (left + right) / 2f,
                    top + height * 0.050f,
                    paint);

            String speedText =
                    fresh
                            ? String.format(Locale.US, "%.4f", gpsSpeed * 3.6f)
                            : "---";
            paint.setTextSize(height * 0.125f);
            float maximumSpeedTextWidth = (right - left) * 0.90f;
            float measuredSpeedTextWidth = paint.measureText(speedText);
            if (measuredSpeedTextWidth > maximumSpeedTextWidth
                    && measuredSpeedTextWidth > 0f) {
                paint.setTextSize(
                        paint.getTextSize()
                                * maximumSpeedTextWidth
                                / measuredSpeedTextWidth);
            }
            paint.setColor(fresh ? Color.WHITE : Color.rgb(120, 120, 120));
            canvas.drawText(
                    speedText,
                    (left + right) / 2f,
                    top + height * 0.205f,
                    paint);

            paint.setTextSize(height * 0.032f);
            paint.setColor(Color.rgb(190, 205, 220));
            canvas.drawText(
                    "km/h",
                    (left + right) / 2f,
                    top + height * 0.255f,
                    paint);

            String accuracyText =
                    Float.isNaN(gpsSpeedAccuracy)
                            ? "precisión de velocidad: n/d"
                            : String.format(
                                    Locale.US,
                                    "±%.1f km/h (68%%)",
                                    gpsSpeedAccuracy * 3.6f);

            String quality;
            int qualityColor;
            if (!fresh) {
                quality = gnssStarted ? "BUSCANDO FIJACIÓN" : "GPS INACTIVO";
                qualityColor = Color.rgb(255, 190, 70);
            } else if (Float.isNaN(gpsSpeedAccuracy)) {
                quality = "FIJACIÓN SIN INCERTIDUMBRE";
                qualityColor = Color.rgb(255, 210, 80);
            } else if (gpsSpeedAccuracy <= 0.35f) {
                quality = "PRECISIÓN ALTA";
                qualityColor = Color.rgb(80, 255, 130);
            } else if (gpsSpeedAccuracy <= 0.80f) {
                quality = "PRECISIÓN MEDIA";
                qualityColor = Color.rgb(255, 215, 80);
            } else {
                quality = "PRECISIÓN BAJA";
                qualityColor = Color.rgb(255, 110, 90);
            }

            paint.setTextSize(height * 0.022f);
            paint.setColor(qualityColor);
            canvas.drawText(
                    quality,
                    (left + right) / 2f,
                    top + height * 0.315f,
                    paint);

            paint.setTextSize(height * 0.019f);
            paint.setColor(Color.rgb(195, 205, 215));
            canvas.drawText(
                    accuracyText,
                    (left + right) / 2f,
                    top + height * 0.355f,
                    paint);

            canvas.drawText(
                    String.format(
                            Locale.US,
                            "satélites usados %d / visibles %d",
                            gnssSatellitesUsed,
                            gnssSatellitesVisible),
                    (left + right) / 2f,
                    top + height * 0.390f,
                    paint);

            String positionAccuracy =
                    Float.isNaN(gpsHorizontalAccuracy)
                            ? "precisión de posición: n/d"
                            : String.format(
                                    Locale.US,
                                    "posición ±%.1f m",
                                    gpsHorizontalAccuracy);
            canvas.drawText(
                    positionAccuracy,
                    (left + right) / 2f,
                    top + height * 0.425f,
                    paint);

            String ageText =
                    lastGpsMs == 0L
                            ? "sin lectura de velocidad"
                            : String.format(Locale.US, "edad %d ms", ageMs);
            canvas.drawText(
                    ageText,
                    (left + right) / 2f,
                    top + height * 0.460f,
                    paint);
        }

        private void drawButton(Canvas canvas, int index, String label) {
            paint.setAntiAlias(true);

            float margin = getWidth() * 0.02f;
            float gap = getWidth() * 0.012f;
            float buttonWidth =
                    (getWidth() - 2f * margin - 2f * gap) / 3f;
            float x = margin + index * (buttonWidth + gap);
            float y = getHeight() * 0.025f;
            float buttonHeight = getHeight() * 0.12f;

            paint.setColor(Color.rgb(32, 32, 32));
            rectangle.set(x, y, x + buttonWidth, y + buttonHeight);
            canvas.drawRoundRect(
                    rectangle,
                    buttonHeight * 0.2f,
                    buttonHeight * 0.2f,
                    paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(getHeight() * 0.039f);
            paint.setColor(Color.WHITE);
            canvas.drawText(
                    label,
                    x + buttonWidth / 2f,
                    y + buttonHeight * 0.68f,
                    paint);
        }

        private void drawInfo(Canvas canvas, int width, int height) {
            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.0185f);
            paint.setColor(Color.rgb(205, 205, 205));

            String gpsAge =
                    lastGpsMs == 0
                            ? "sin datos"
                            : (SystemClock.elapsedRealtime() - lastGpsMs) + " ms";

            float confidence =
                    clamp(
                                    candidateTimeS / REQUIRED_PERSISTENCE_S,
                                    0f,
                                    1f)
                            * 100f;

            String[] lines =
                    new String[]{
                        String.format(
                                Locale.US,
                                "Filtrada %+.4f · salida aceptada %+.4f m/s²",
                                rawLongitudinal,
                                longitudinal),
                        String.format(
                                Locale.US,
                                "Confianza %.0f%% · historial %d/50",
                                confidence,
                                historyCount),
                        "GPS directo con precisión, edad y satélites",
                        String.format(
                                Locale.US,
                                "GPS %.4f km/h · edad %s",
                                gpsSpeed * 3.6f,
                                gpsAge)
                    };

            float y = height * 0.720f;
            for (String line : lines) {
                canvas.drawText(line, width * 0.035f, y, paint);
                y += height * 0.025f;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;

            float third = getWidth() / 3f;
            if (event.getY() < getHeight() * 0.17f) {
                if (event.getX() < third) {
                    beginCalibration();
                } else if (event.getX() < 2f * third) {
                    showInfo = !showInfo;
                } else {
                    setZero();
                }
                invalidate();
            }
            return true;
        }
    }
}
