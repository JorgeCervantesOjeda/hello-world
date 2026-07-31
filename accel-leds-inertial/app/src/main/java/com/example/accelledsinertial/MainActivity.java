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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Locale;

public final class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int REQ_LOCATION = 7;

    // Dos polos de filtrado: atenúan mucho más las vibraciones que un solo pasa bajas.
    private static final float LPF_STAGE_1_TAU_S = 0.075f;
    private static final float LPF_STAGE_2_TAU_S = 0.135f;
    private static final float GRAVITY_TAU_S = 0.90f;

    // Histéresis y persistencia: el mismo signo debe mantenerse varias décimas.
    private static final float DETECT_THRESHOLD = 0.12f;
    private static final float RELEASE_THRESHOLD = 0.065f;
    private static final float REQUIRED_PERSISTENCE_S = 0.28f;
    private static final float RELEASE_HOLD_S = 0.12f;

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

    private float zeroBias;
    private long lastSensorNs;
    private float rawLongitudinal;
    private float longitudinal;
    private float gpsSpeed;
    private long lastGpsMs;
    private boolean showInfo = true;
    private boolean calibrated;

    private int candidateSign;
    private int confirmedSign;
    private float candidateTimeS;
    private float releaseTimeS;

    private int calibrationState; // 0: idle, 1: detenido, 2: acelerando
    private long calibrationStartMs;
    private int calibrationSamples;
    private int zeroSamples;
    private final double[][] covariance = new double[3][3];
    private final double[] calibrationMean = new double[3];
    private final double[] zeroMean = new double[3];
    private String message = "Fija el teléfono y pulsa CALIBRAR";

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
            sensorManager.registerListener(this, motionSensor, SensorManager.SENSOR_DELAY_GAME);
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
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this);
        } catch (Exception ignored) {
        }
    }

    private void stopLocation() {
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
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
            updatePersistenceGate(rawLongitudinal, dt);
        } else {
            rawLongitudinal = 0f;
            resetMotionGate();
        }
        view.invalidate();
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

            candidateTimeS = Math.min(REQUIRED_PERSISTENCE_S, candidateTimeS + dt);
            if (candidateTimeS >= REQUIRED_PERSISTENCE_S) {
                confirmedSign = sign;
                longitudinal = value;
            } else {
                longitudinal = 0f;
            }
            return;
        }

        // Entre ambos umbrales se conserva el estado confirmado: evita parpadeo.
        if (magnitude > RELEASE_THRESHOLD) {
            releaseTimeS = 0f;
            if (confirmedSign == sign) longitudinal = value;
            else longitudinal = 0f;
            candidateTimeS = Math.max(0f, candidateTimeS - dt);
            return;
        }

        // Una pausa corta no apaga inmediatamente; una vibración aislada sí pierde confianza.
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
                for (int i = 0; i < 3; i++) zeroMean[i] += filteredStage2[i];
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
            message = "Calibración insuficiente. Repite con una aceleración más clara";
            return;
        }

        double[] vector = new double[]{1, 1, 1};
        normalize(vector);
        for (int iteration = 0; iteration < 20; iteration++) {
            double[] next = new double[3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) next[i] += covariance[i][j] * vector[j];
            }
            normalize(next);
            vector = next;
        }

        double sign = vector[0] * calibrationMean[0]
                + vector[1] * calibrationMean[1]
                + vector[2] * calibrationMean[2];
        if (sign < 0) for (int i = 0; i < 3; i++) vector[i] = -vector[i];

        axis[0] = (float) vector[0];
        axis[1] = (float) vector[1];
        axis[2] = (float) vector[2];

        if (zeroSamples > 0) {
            float[] averageZero = new float[]{
                    (float) (zeroMean[0] / zeroSamples),
                    (float) (zeroMean[1] / zeroSamples),
                    (float) (zeroMean[2] / zeroSamples)};
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
        message = "Calibrado. Filtro antivibración activo";
    }

    private void setZero() {
        if (!calibrated) return;
        zeroBias = dot(filteredStage2, axis);
        prefs.edit().putFloat("bias", zeroBias).apply();
        resetMotionGate();
        message = "Cero ajustado";
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onLocationChanged(Location location) {
        if (location != null && location.hasSpeed()) {
            gpsSpeed = Math.max(0f, location.getSpeed());
            lastGpsMs = SystemClock.elapsedRealtime();
            view.invalidate();
        }
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @SuppressWarnings("deprecation")
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float magnitude(float[] vector) {
        return (float) Math.sqrt(dot(vector, vector));
    }

    private static void normalize(double[] vector) {
        double m = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
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

    private static float level(float acceleration, float maximum) {
        if (acceleration <= DETECT_THRESHOLD) return 0f;
        float normalized = (float) (Math.log1p((acceleration - DETECT_THRESHOLD) / 0.15f)
                / Math.log1p((maximum - DETECT_THRESHOLD) / 0.15f));
        return clamp(normalized * 10f, 0f, 10f);
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

            float redLevel = level(Math.max(0f, -longitudinal), 7f);
            float greenLevel = level(Math.max(0f, longitudinal), 3.5f);
            float side = width * 0.035f;
            float slot = (width - 2f * side) / 10f;
            float segmentWidth = slot * 0.72f;
            float redTop = height * 0.23f;
            float redBottom = height * 0.40f;
            float greenTop = height * 0.46f;
            float greenBottom = height * 0.63f;

            for (int visualIndex = 0; visualIndex < 10; visualIndex++) {
                float x = side + visualIndex * slot + (slot - segmentWidth) / 2f;
                float redAmount = redLevel - (9 - visualIndex);
                float greenAmount = greenLevel - visualIndex;
                segment(canvas, x, redTop, segmentWidth, redBottom - redTop,
                        Color.rgb(255, 35, 25), redAmount);
                segment(canvas, x, greenTop, segmentWidth, greenBottom - greenTop,
                        Color.rgb(0, 255, 70), greenAmount);
            }

            drawButton(canvas, 0, "CALIBRAR");
            drawButton(canvas, 1, showInfo ? "OCULTAR INFO" : "MOSTRAR INFO");
            drawButton(canvas, 2, "AJUSTAR CERO");
            if (showInfo) drawInfo(canvas, width, height);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.033f);
            paint.setColor(Color.LTGRAY);
            canvas.drawText(message, width / 2f, height * 0.91f, paint);
            paint.setTextSize(height * 0.024f);
            paint.setColor(Color.GRAY);
            canvas.drawText("Uso experimental · teléfono fijado · no manipular al conducir",
                    width / 2f, height * 0.98f, paint);
        }

        private void segment(Canvas canvas, float x, float y, float width, float height,
                             int activeColor, float amount) {
            paint.setColor(amount > 0f ? activeColor : Color.rgb(18, 18, 18));
            paint.setAlpha(amount >= 1f ? 255 : amount > 0f ? (int) (60 + 195 * amount) : 255);
            rectangle.set(x, y, x + width, y + height);
            canvas.drawRoundRect(rectangle, width * 0.18f, width * 0.18f, paint);
            paint.setAlpha(255);
        }

        private void drawButton(Canvas canvas, int index, String label) {
            float margin = getWidth() * 0.02f;
            float gap = getWidth() * 0.012f;
            float buttonWidth = (getWidth() - 2f * margin - 2f * gap) / 3f;
            float x = margin + index * (buttonWidth + gap);
            float y = getHeight() * 0.035f;
            float buttonHeight = getHeight() * 0.13f;
            paint.setColor(Color.rgb(32, 32, 32));
            rectangle.set(x, y, x + buttonWidth, y + buttonHeight);
            canvas.drawRoundRect(rectangle, buttonHeight * 0.2f, buttonHeight * 0.2f, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(getHeight() * 0.043f);
            paint.setColor(Color.WHITE);
            canvas.drawText(label, x + buttonWidth / 2f, y + buttonHeight * 0.68f, paint);
        }

        private void drawInfo(Canvas canvas, int width, int height) {
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.026f);
            paint.setColor(Color.rgb(190, 190, 190));
            String gpsAge = lastGpsMs == 0 ? "sin datos"
                    : (SystemClock.elapsedRealtime() - lastGpsMs) + " ms";
            float confidence = clamp(candidateTimeS / REQUIRED_PERSISTENCE_S, 0f, 1f) * 100f;
            String[] lines = new String[]{
                    String.format(Locale.US, "Salida: %+.3f m/s²", longitudinal),
                    String.format(Locale.US, "Filtrada: %+.3f m/s² · confianza %.0f%%", rawLongitudinal, confidence),
                    String.format(Locale.US, "GPS: %.1f km/h · edad %s", gpsSpeed * 3.6f, gpsAge),
                    "Filtro: dos etapas + persistencia 0.28 s",
                    "Estado: " + (calibrated ? "calibrado" : "sin calibrar")};
            float y = height * 0.68f;
            for (String line : lines) {
                canvas.drawText(line, width * 0.035f, y, paint);
                y += height * 0.034f;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            float third = getWidth() / 3f;
            if (event.getY() < getHeight() * 0.2f) {
                if (event.getX() < third) beginCalibration();
                else if (event.getX() < 2f * third) showInfo = !showInfo;
                else setZero();
                invalidate();
            }
            return true;
        }
    }
}
