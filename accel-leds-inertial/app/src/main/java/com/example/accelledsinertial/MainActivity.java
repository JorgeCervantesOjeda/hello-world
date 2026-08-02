package com.example.accelledsinertial;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Locale;

public final class MainActivity extends Activity implements LocationListener {

    private static final int REQ_LOCATION = 7;

    private static final float GREEN_FULL_SCALE = 3.0f;
    private static final float RED_FULL_SCALE = 9.0f;
    private static final float VISUAL_DEAD_ZONE = 0.015f;
    private static final float ACCELERATION_DISPLAY_FACTOR = 3.6f;

    private static final long GPS_SPEED_STALE_MS = 2500L;
    private static final long GPS_ACCELERATION_STALE_MS = 3000L;
    private static final float GPS_ACCELERATION_TAU_S = 0.70f;
    private static final float GPS_ACCELERATION_MAX_ABS = 12.0f;
    private static final float GPS_MIN_INTERVAL_S = 0.12f;
    private static final float GPS_MAX_INTERVAL_S = 3.0f;

    private LocationManager locationManager;
    private GpsView view;

    private float batteryTemperatureC = Float.NaN;
    private boolean batteryReceiverRegistered;

    private float gpsSpeed;
    private float gpsSpeedAccuracy = Float.NaN;
    private float gpsHorizontalAccuracy = Float.NaN;
    private long lastGpsMs;

    private float gpsAccelerationRaw = Float.NaN;
    private float gpsAccelerationFiltered;
    private float gpsAccelerationUncertainty = Float.NaN;
    private float gpsAccelerationQuality;
    private float gpsUpdateHz;
    private float gpsLastIntervalS;
    private float gpsPositionConsistencyError = Float.NaN;
    private long lastGpsAccelerationMs;
    private boolean gpsAccelerationValid;

    private long previousGpsTimeMs;
    private float previousGpsSpeed;
    private float previousGpsSpeedAccuracy = Float.NaN;
    private float previousGpsHorizontalAccuracy = Float.NaN;
    private Location previousGpsLocation;

    private int gnssSatellitesVisible;
    private int gnssSatellitesUsed;
    private boolean gnssStarted;
    private boolean gnssCallbackRegistered;
    private boolean showInfo = true;

    private final BroadcastReceiver batteryReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateBatteryTemperature(intent);
                }
            };

    private final GnssStatus.Callback gnssStatusCallback =
            new GnssStatus.Callback() {
                @Override
                public void onStarted() {
                    gnssStarted = true;
                    invalidateView();
                }

                @Override
                public void onStopped() {
                    gnssStarted = false;
                    gnssSatellitesVisible = 0;
                    gnssSatellitesUsed = 0;
                    gpsAccelerationValid = false;
                    invalidateView();
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
                    invalidateView();
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

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        view = new GpsView(this);
        setContentView(view);
        startLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideUi();
        startBatteryTemperature();
        startLocation();
    }

    @Override
    protected void onPause() {
        stopLocation();
        stopBatteryTemperature();
        super.onPause();
    }

    private void startBatteryTemperature() {
        if (batteryReceiverRegistered) return;

        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent currentBattery;
            if (Build.VERSION.SDK_INT >= 33) {
                currentBattery =
                        registerReceiver(
                                batteryReceiver,
                                filter,
                                Context.RECEIVER_NOT_EXPORTED);
            } else {
                currentBattery = registerReceiver(batteryReceiver, filter);
            }
            batteryReceiverRegistered = true;
            updateBatteryTemperature(currentBattery);
        } catch (Exception ignored) {
            batteryReceiverRegistered = false;
            batteryTemperatureC = Float.NaN;
        }
    }

    private void stopBatteryTemperature() {
        if (!batteryReceiverRegistered) return;

        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        batteryReceiverRegistered = false;
    }

    private void updateBatteryTemperature(Intent intent) {
        if (intent == null || !intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            batteryTemperatureC = Float.NaN;
        } else {
            int tenthsCelsius =
                    intent.getIntExtra(
                            BatteryManager.EXTRA_TEMPERATURE,
                            Integer.MIN_VALUE);
            batteryTemperatureC =
                    tenthsCelsius == Integer.MIN_VALUE
                            ? Float.NaN
                            : tenthsCelsius / 10f;
        }
        invalidateView();
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
    public void onLocationChanged(Location location) {
        if (location == null) return;

        long currentTimeMs = location.getElapsedRealtimeNanos() / 1_000_000L;
        float currentHorizontalAccuracy =
                location.hasAccuracy() ? location.getAccuracy() : Float.NaN;

        if (!location.hasSpeed()) {
            gpsAccelerationValid = false;
            previousGpsLocation = new Location(location);
            previousGpsTimeMs = currentTimeMs;
            previousGpsSpeedAccuracy = Float.NaN;
            previousGpsHorizontalAccuracy = currentHorizontalAccuracy;
            invalidateView();
            return;
        }

        float currentSpeed = Math.max(0f, location.getSpeed());
        float currentSpeedAccuracy =
                location.hasSpeedAccuracy()
                        ? location.getSpeedAccuracyMetersPerSecond()
                        : Float.NaN;

        updateGpsAcceleration(
                location,
                currentTimeMs,
                currentSpeed,
                currentSpeedAccuracy,
                currentHorizontalAccuracy);

        gpsSpeed = currentSpeed;
        gpsSpeedAccuracy = currentSpeedAccuracy;
        gpsHorizontalAccuracy = currentHorizontalAccuracy;
        lastGpsMs = currentTimeMs;
        invalidateView();
    }

    private void updateGpsAcceleration(
            Location location,
            long currentTimeMs,
            float currentSpeed,
            float currentSpeedAccuracy,
            float currentHorizontalAccuracy) {

        gpsAccelerationValid = false;
        gpsAccelerationRaw = Float.NaN;
        gpsAccelerationUncertainty = Float.NaN;
        gpsPositionConsistencyError = Float.NaN;

        if (previousGpsLocation != null && previousGpsTimeMs > 0L) {
            float dt = (currentTimeMs - previousGpsTimeMs) / 1000f;
            gpsLastIntervalS = dt;
            gpsUpdateHz = dt > 0f ? 1f / dt : 0f;

            if (dt >= GPS_MIN_INTERVAL_S && dt <= GPS_MAX_INTERVAL_S) {
                float rawAcceleration = (currentSpeed - previousGpsSpeed) / dt;
                gpsAccelerationRaw = rawAcceleration;

                float displacement = previousGpsLocation.distanceTo(location);
                float expectedDisplacement =
                        0.5f * (previousGpsSpeed + currentSpeed) * dt;
                float positionError = Math.abs(displacement - expectedDisplacement);
                gpsPositionConsistencyError = positionError;

                float currentPositionAllowance =
                        Float.isNaN(currentHorizontalAccuracy)
                                ? 10f
                                : currentHorizontalAccuracy;
                float previousPositionAllowance =
                        Float.isNaN(previousGpsHorizontalAccuracy)
                                ? 10f
                                : previousGpsHorizontalAccuracy;
                float positionTolerance =
                        Math.max(
                                5f,
                                currentPositionAllowance
                                        + previousPositionAllowance
                                        + Math.max(2f, expectedDisplacement * 0.60f));

                boolean positionCoherent = positionError <= positionTolerance;
                boolean physicallyPlausible =
                        Math.abs(rawAcceleration) <= GPS_ACCELERATION_MAX_ABS;

                if (!Float.isNaN(currentSpeedAccuracy)
                        && !Float.isNaN(previousGpsSpeedAccuracy)) {
                    gpsAccelerationUncertainty =
                            (float)
                                    (Math.sqrt(
                                                    currentSpeedAccuracy * currentSpeedAccuracy
                                                            + previousGpsSpeedAccuracy
                                                                    * previousGpsSpeedAccuracy)
                                            / dt);
                }

                boolean uncertaintyAcceptable =
                        Float.isNaN(gpsAccelerationUncertainty)
                                || gpsAccelerationUncertainty <= 6.0f;

                float speedQuality =
                        Float.isNaN(gpsAccelerationUncertainty)
                                ? 0.45f
                                : 1f - clamp(gpsAccelerationUncertainty / 4.0f, 0f, 1f);
                float positionQuality =
                        1f - clamp(positionError / positionTolerance, 0f, 1f);
                gpsAccelerationQuality =
                        clamp(0.65f * speedQuality + 0.35f * positionQuality, 0f, 1f);

                if (positionCoherent && physicallyPlausible && uncertaintyAcceptable) {
                    float alpha = dt / (GPS_ACCELERATION_TAU_S + dt);
                    boolean previousEstimateStale =
                            lastGpsAccelerationMs == 0L
                                    || currentTimeMs - lastGpsAccelerationMs
                                            > GPS_ACCELERATION_STALE_MS;

                    if (previousEstimateStale) {
                        gpsAccelerationFiltered = rawAcceleration;
                    } else {
                        gpsAccelerationFiltered +=
                                alpha * (rawAcceleration - gpsAccelerationFiltered);
                    }

                    lastGpsAccelerationMs = currentTimeMs;
                    gpsAccelerationValid = true;
                }
            }
        }

        previousGpsLocation = new Location(location);
        previousGpsTimeMs = currentTimeMs;
        previousGpsSpeed = currentSpeed;
        previousGpsSpeedAccuracy = currentSpeedAccuracy;
        previousGpsHorizontalAccuracy = currentHorizontalAccuracy;
    }

    @Override
    public void onProviderEnabled(String provider) {
        invalidateView();
    }

    @Override
    public void onProviderDisabled(String provider) {
        gpsAccelerationValid = false;
        invalidateView();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private void invalidateView() {
        if (view != null) view.invalidate();
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float signedFillFraction(float value) {
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

    private final class GpsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectangle = new RectF();

        GpsView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();

            drawHeader(canvas, width, height);
            drawSpeedPanel(canvas, width, height);
            drawAccelerationPanel(canvas, width, height);

            if (showInfo) drawDiagnostics(canvas, width, height);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.020f);
            paint.setColor(Color.GRAY);
            canvas.drawText(
                    "Solo GPS · uso experimental · no manipular al conducir",
                    width / 2f,
                    height * 0.974f,
                    paint);
        }

        private void drawHeader(Canvas canvas, int width, int height) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.039f);
            paint.setColor(Color.WHITE);
            canvas.drawText(
                    "GPS · ACELERACIÓN DOMINANTE",
                    width * 0.035f,
                    height * 0.078f,
                    paint);

            float buttonLeft = width * 0.545f;
            float buttonTop = height * 0.020f;
            float buttonRight = width * 0.735f;
            float buttonBottom = height * 0.105f;
            rectangle.set(buttonLeft, buttonTop, buttonRight, buttonBottom);
            paint.setColor(Color.rgb(32, 32, 32));
            canvas.drawRoundRect(rectangle, height * 0.018f, height * 0.018f, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.024f);
            paint.setColor(Color.WHITE);
            canvas.drawText(
                    showInfo ? "OCULTAR INFO" : "MOSTRAR INFO",
                    (buttonLeft + buttonRight) / 2f,
                    buttonTop + (buttonBottom - buttonTop) * 0.64f,
                    paint);

            drawBatteryThermometer(canvas, width, height);
        }

        private void drawBatteryThermometer(Canvas canvas, int width, int height) {
            float left = width * 0.765f;
            float top = height * 0.015f;
            float right = width * 0.965f;
            float bottom = height * 0.110f;

            rectangle.set(left, top, right, bottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(16, 20, 24));
            canvas.drawRoundRect(rectangle, height * 0.018f, height * 0.018f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, height * 0.0025f));
            paint.setColor(Color.rgb(95, 110, 125));
            canvas.drawRoundRect(rectangle, height * 0.018f, height * 0.018f, paint);
            paint.setStyle(Paint.Style.FILL);

            int temperatureColor;
            if (Float.isNaN(batteryTemperatureC)) {
                temperatureColor = Color.rgb(145, 145, 145);
            } else if (batteryTemperatureC >= 45f) {
                temperatureColor = Color.rgb(255, 65, 45);
            } else if (batteryTemperatureC >= 40f) {
                temperatureColor = Color.rgb(255, 205, 70);
            } else {
                temperatureColor = Color.WHITE;
            }

            float iconX = left + (right - left) * 0.135f;
            float stemTop = top + (bottom - top) * 0.20f;
            float stemBottom = top + (bottom - top) * 0.68f;
            float stemHalfWidth = Math.max(2f, width * 0.0040f);
            float bulbRadius = Math.max(4f, height * 0.013f);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, height * 0.0028f));
            paint.setColor(Color.rgb(195, 205, 215));
            rectangle.set(
                    iconX - stemHalfWidth,
                    stemTop,
                    iconX + stemHalfWidth,
                    stemBottom);
            canvas.drawRoundRect(rectangle, stemHalfWidth, stemHalfWidth, paint);
            canvas.drawCircle(iconX, stemBottom + bulbRadius * 0.45f, bulbRadius, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(temperatureColor);
            float levelFraction =
                    Float.isNaN(batteryTemperatureC)
                            ? 0f
                            : clamp((batteryTemperatureC - 20f) / 30f, 0f, 1f);
            float fillTop = stemBottom - (stemBottom - stemTop) * levelFraction;
            canvas.drawRect(
                    iconX - stemHalfWidth * 0.45f,
                    fillTop,
                    iconX + stemHalfWidth * 0.45f,
                    stemBottom,
                    paint);
            canvas.drawCircle(
                    iconX,
                    stemBottom + bulbRadius * 0.45f,
                    bulbRadius * 0.68f,
                    paint);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.0155f);
            paint.setColor(Color.rgb(175, 195, 215));
            canvas.drawText(
                    "TEMP. BATERÍA",
                    left + (right - left) * 0.255f,
                    top + (bottom - top) * 0.36f,
                    paint);

            String temperatureText =
                    Float.isNaN(batteryTemperatureC)
                            ? "--.- °C"
                            : String.format(Locale.US, "%.1f °C", batteryTemperatureC);
            paint.setTextSize(height * 0.030f);
            paint.setColor(temperatureColor);
            canvas.drawText(
                    temperatureText,
                    left + (right - left) * 0.255f,
                    top + (bottom - top) * 0.78f,
                    paint);
        }

        private void drawSpeedPanel(Canvas canvas, int width, int height) {
            float left = width * 0.035f;
            float right = width * 0.470f;
            float top = height * 0.120f;
            float bottom = height * 0.290f;
            drawPanelBackground(canvas, left, top, right, bottom);

            long ageMs =
                    lastGpsMs == 0L
                            ? Long.MAX_VALUE
                            : Math.max(0L, SystemClock.elapsedRealtime() - lastGpsMs);
            boolean fresh = lastGpsMs != 0L && ageMs <= GPS_SPEED_STALE_MS;

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.023f);
            paint.setColor(Color.rgb(170, 195, 220));
            canvas.drawText("VELOCIDAD GPS", left + width * 0.018f, top + height * 0.038f, paint);

            String speedText =
                    fresh
                            ? String.format(Locale.US, "%.4f", gpsSpeed * 3.6f)
                            : "---";
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.075f);
            fitText(speedText, (right - left) * 0.70f);
            paint.setColor(fresh ? Color.WHITE : Color.rgb(115, 115, 115));
            canvas.drawText(speedText, (left + right) / 2f, top + height * 0.112f, paint);

            paint.setTextSize(height * 0.025f);
            paint.setColor(Color.rgb(195, 210, 225));
            canvas.drawText("km/h", (left + right) / 2f, top + height * 0.148f, paint);

            String footer;
            if (!fresh) {
                footer = gnssStarted ? "buscando fijación" : "GPS inactivo";
            } else if (Float.isNaN(gpsSpeedAccuracy)) {
                footer = String.format(Locale.US, "edad %d ms · precisión n/d", ageMs);
            } else {
                footer = String.format(
                        Locale.US,
                        "edad %d ms · ±%.2f km/h",
                        ageMs,
                        gpsSpeedAccuracy * 3.6f);
            }
            paint.setTextSize(height * 0.018f);
            paint.setColor(fresh ? Color.rgb(190, 205, 220) : Color.rgb(255, 190, 70));
            canvas.drawText(footer, (left + right) / 2f, bottom - height * 0.014f, paint);
        }

        private void drawAccelerationPanel(Canvas canvas, int width, int height) {
            float left = width * 0.035f;
            float right = width * 0.965f;
            float top = height * 0.315f;
            float bottom = showInfo ? height * 0.735f : height * 0.900f;
            float panelHeight = bottom - top;
            drawPanelBackground(canvas, left, top, right, bottom);

            long ageMs =
                    lastGpsAccelerationMs == 0L
                            ? Long.MAX_VALUE
                            : Math.max(
                                    0L,
                                    SystemClock.elapsedRealtime()
                                            - lastGpsAccelerationMs);
            boolean fresh =
                    gpsAccelerationValid
                            && lastGpsAccelerationMs != 0L
                            && ageMs <= GPS_ACCELERATION_STALE_MS;

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.030f);
            paint.setColor(Color.rgb(185, 205, 225));
            canvas.drawText(
                    "ACELERACIÓN GPS",
                    left + width * 0.020f,
                    top + panelHeight * 0.105f,
                    paint);

            String accelerationText =
                    fresh
                            ? String.format(
                                    Locale.US,
                                    "%+.4f",
                                    gpsAccelerationFiltered * ACCELERATION_DISPLAY_FACTOR)
                            : "---";
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.105f);
            fitText(accelerationText, (right - left) * 0.50f);
            paint.setColor(fresh ? Color.WHITE : Color.rgb(115, 115, 115));
            canvas.drawText(
                    accelerationText,
                    (left + right) / 2f,
                    top + panelHeight * 0.305f,
                    paint);

            paint.setTextSize(height * 0.030f);
            paint.setColor(Color.rgb(195, 210, 225));
            canvas.drawText(
                    "km/(h·s)",
                    (left + right) / 2f,
                    top + panelHeight * 0.395f,
                    paint);

            float barLeft = left + (right - left) * 0.025f;
            float barRight = right - (right - left) * 0.025f;
            float barTop = top + panelHeight * 0.475f;
            float barBottom = top + panelHeight * 0.805f;
            float axisLabelY = top + panelHeight * 0.885f;
            drawAccelerationBar(
                    canvas,
                    barLeft,
                    barRight,
                    barTop,
                    barBottom,
                    axisLabelY,
                    fresh ? gpsAccelerationFiltered : 0f);

            String status = fresh ? accelerationQualityText() : "SIN ESTIMACIÓN VÁLIDA";
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.022f);
            paint.setColor(fresh ? accelerationQualityColor() : Color.rgb(255, 190, 70));
            canvas.drawText(
                    status + " · zona neutra ±0.054 km/(h·s)",
                    (left + right) / 2f,
                    top + panelHeight * 0.955f,
                    paint);
        }

        private void drawDiagnostics(Canvas canvas, int width, int height) {
            float top = height * 0.785f;
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.021f);
            paint.setColor(Color.rgb(205, 210, 215));

            long speedAge =
                    lastGpsMs == 0L
                            ? -1L
                            : Math.max(0L, SystemClock.elapsedRealtime() - lastGpsMs);
            long accelerationAge =
                    lastGpsAccelerationMs == 0L
                            ? -1L
                            : Math.max(
                                    0L,
                                    SystemClock.elapsedRealtime()
                                            - lastGpsAccelerationMs);

            String speedAccuracyText =
                    Float.isNaN(gpsSpeedAccuracy)
                            ? "n/d"
                            : String.format(Locale.US, "%.3f m/s", gpsSpeedAccuracy);
            String positionAccuracyText =
                    Float.isNaN(gpsHorizontalAccuracy)
                            ? "n/d"
                            : String.format(Locale.US, "%.1f m", gpsHorizontalAccuracy);
            String accelerationUncertaintyText =
                    Float.isNaN(gpsAccelerationUncertainty)
                            ? "n/d"
                            : String.format(
                                    Locale.US,
                                    "%.3f km/(h·s)",
                                    gpsAccelerationUncertainty
                                            * ACCELERATION_DISPLAY_FACTOR);
            String rawAccelerationText =
                    Float.isNaN(gpsAccelerationRaw)
                            ? "n/d"
                            : String.format(
                                    Locale.US,
                                    "%+.4f km/(h·s)",
                                    gpsAccelerationRaw * ACCELERATION_DISPLAY_FACTOR);
            String consistencyText =
                    Float.isNaN(gpsPositionConsistencyError)
                            ? "n/d"
                            : String.format(
                                    Locale.US,
                                    "%.2f m",
                                    gpsPositionConsistencyError);

            String[] lines =
                    new String[]{
                        String.format(
                                Locale.US,
                                "GNSS: %.2f Hz · intervalo %.0f ms · satélites %d/%d",
                                gpsUpdateHz,
                                gpsLastIntervalS * 1000f,
                                gnssSatellitesUsed,
                                gnssSatellitesVisible),
                        String.format(
                                Locale.US,
                                "Velocidad: precisión %s · posición %s · edad %s",
                                speedAccuracyText,
                                positionAccuracyText,
                                speedAge < 0L ? "n/d" : speedAge + " ms"),
                        String.format(
                                Locale.US,
                                "Aceleración: cruda %s · filtrada %+.4f km/(h·s) · incertidumbre %s",
                                rawAccelerationText,
                                gpsAccelerationFiltered * ACCELERATION_DISPLAY_FACTOR,
                                accelerationUncertaintyText),
                        String.format(
                                Locale.US,
                                "Coherencia de posición: error %s · calidad %.0f%% · edad %s",
                                consistencyText,
                                gpsAccelerationQuality * 100f,
                                accelerationAge < 0L ? "n/d" : accelerationAge + " ms")
                    };

            float y = top;
            for (String line : lines) {
                canvas.drawText(line, width * 0.040f, y, paint);
                y += height * 0.040f;
            }
        }

        private void drawPanelBackground(
                Canvas canvas,
                float left,
                float top,
                float right,
                float bottom) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(12, 16, 20));
            rectangle.set(left, top, right, bottom);
            canvas.drawRoundRect(rectangle, getHeight() * 0.025f, getHeight() * 0.025f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, getHeight() * 0.003f));
            paint.setColor(Color.rgb(90, 105, 120));
            canvas.drawRoundRect(rectangle, getHeight() * 0.025f, getHeight() * 0.025f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawAccelerationBar(
                Canvas canvas,
                float left,
                float right,
                float top,
                float bottom,
                float axisLabelY,
                float value) {
            paint.setAntiAlias(false);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(24, 24, 24));
            canvas.drawRect(left, top, right, bottom, paint);

            float fraction = signedFillFraction(value);
            if (fraction <= 0f) {
                paint.setAntiAlias(true);
                return;
            }

            float pixels = (right - left) * fraction;
            boolean negative = value < -VISUAL_DEAD_ZONE;
            float activeLeft = negative ? right - pixels : left;
            float activeRight = negative ? right : left + pixels;
            int activeColor =
                    negative
                            ? Color.rgb(255, 35, 25)
                            : Color.rgb(0, 255, 70);

            paint.setColor(activeColor);
            canvas.drawRect(activeLeft, top, activeRight, bottom, paint);

            String[] tickLabels =
                    negative
                            ? new String[]{"−32.4", "−24.3", "−16.2", "−8.1", "0"}
                            : new String[]{"0", "+2.7", "+5.4", "+8.1", "+10.8"};
            float[] tickValues =
                    negative
                            ? new float[]{-9.0f, -6.75f, -4.50f, -2.25f, 0f}
                            : new float[]{0f, 0.75f, 1.50f, 2.25f, 3.0f};

            paint.setStrokeWidth(Math.max(1f, getHeight() * 0.0022f));
            paint.setColor(Color.WHITE);
            paint.setAlpha(95);
            for (int i = 0; i < tickLabels.length; i++) {
                float tickFraction = signedFillFraction(tickValues[i]);
                float x =
                        negative
                                ? right - (right - left) * tickFraction
                                : left + (right - left) * tickFraction;
                canvas.drawLine(x, top, x, bottom, paint);
            }
            paint.setAlpha(255);

            float edgeX = negative ? activeLeft : activeRight;
            float markerWidth = Math.max(2f, getWidth() * 0.0022f);
            paint.setColor(Color.WHITE);
            canvas.drawRect(
                    edgeX - markerWidth / 2f,
                    top,
                    edgeX + markerWidth / 2f,
                    bottom,
                    paint);

            paint.setAntiAlias(true);
            paint.setTextSize(getHeight() * 0.019f);
            paint.setColor(
                    negative
                            ? Color.rgb(255, 175, 170)
                            : Color.rgb(170, 255, 195));

            for (int i = 0; i < tickLabels.length; i++) {
                float tickFraction = signedFillFraction(tickValues[i]);
                float x =
                        negative
                                ? right - (right - left) * tickFraction
                                : left + (right - left) * tickFraction;
                if (i == 0) {
                    paint.setTextAlign(Paint.Align.LEFT);
                } else if (i == tickLabels.length - 1) {
                    paint.setTextAlign(Paint.Align.RIGHT);
                } else {
                    paint.setTextAlign(Paint.Align.CENTER);
                }
                canvas.drawText(tickLabels[i], x, axisLabelY, paint);
            }

            String currentText =
                    String.format(
                            Locale.US,
                            "%+.2f",
                            value * ACCELERATION_DISPLAY_FACTOR);
            paint.setTextSize(getHeight() * 0.024f);
            paint.setTextAlign(Paint.Align.CENTER);
            float textPadding = getHeight() * 0.010f;
            float textWidth = paint.measureText(currentText);
            float labelHalfWidth = textWidth / 2f + textPadding;
            float labelCenterX =
                    clamp(
                            edgeX,
                            left + labelHalfWidth + textPadding,
                            right - labelHalfWidth - textPadding);
            float labelTop = top + (bottom - top) * 0.075f;
            float labelBottom = top + (bottom - top) * 0.365f;

            rectangle.set(
                    labelCenterX - labelHalfWidth,
                    labelTop,
                    labelCenterX + labelHalfWidth,
                    labelBottom);
            paint.setColor(Color.argb(205, 0, 0, 0));
            canvas.drawRoundRect(
                    rectangle,
                    getHeight() * 0.012f,
                    getHeight() * 0.012f,
                    paint);

            paint.setColor(Color.WHITE);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float textY =
                    (labelTop + labelBottom) / 2f
                            - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(currentText, labelCenterX, textY, paint);
        }

        private String accelerationQualityText() {
            if (gpsAccelerationQuality >= 0.75f) return "CALIDAD ALTA";
            if (gpsAccelerationQuality >= 0.45f) return "CALIDAD MEDIA";
            return "CALIDAD BAJA";
        }

        private int accelerationQualityColor() {
            if (gpsAccelerationQuality >= 0.75f) return Color.rgb(80, 255, 130);
            if (gpsAccelerationQuality >= 0.45f) return Color.rgb(255, 215, 80);
            return Color.rgb(255, 110, 90);
        }

        private void fitText(String text, float maximumWidth) {
            float measuredWidth = paint.measureText(text);
            if (measuredWidth > maximumWidth && measuredWidth > 0f) {
                paint.setTextSize(paint.getTextSize() * maximumWidth / measuredWidth);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;

            if (event.getX() >= getWidth() * 0.52f
                    && event.getX() <= getWidth() * 0.75f
                    && event.getY() <= getHeight() * 0.14f) {
                showInfo = !showInfo;
                invalidate();
            }
            return true;
        }
    }
}
