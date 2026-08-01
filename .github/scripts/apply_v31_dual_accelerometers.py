from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global java
    count = java.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    java = java.replace(old, new, 1)


replace_once(
    "    private static final long GPS_STALE_MS = 2500L;\n",
    """    private static final long GPS_STALE_MS = 2500L;
    private static final float GPS_ACCELERATION_TAU_S = 0.70f;
    private static final float GPS_ACCELERATION_MAX_ABS = 12.0f;
    private static final float GPS_MIN_INTERVAL_S = 0.12f;
    private static final float GPS_MAX_INTERVAL_S = 3.0f;
    private static final long GPS_ACCELERATION_STALE_MS = 3000L;
""",
    "GPS acceleration constants",
)

replace_once(
    """    private float gpsSpeed;
    private float gpsSpeedAccuracy = Float.NaN;
    private float gpsHorizontalAccuracy = Float.NaN;
    private long lastGpsMs;
    private int gnssSatellitesVisible;
""",
    """    private float gpsSpeed;
    private float gpsSpeedAccuracy = Float.NaN;
    private float gpsHorizontalAccuracy = Float.NaN;
    private long lastGpsMs;

    private float gpsAccelerationRaw;
    private float gpsAccelerationFiltered;
    private float gpsAccelerationUncertainty = Float.NaN;
    private float gpsAccelerationQuality;
    private float gpsUpdateHz;
    private float gpsLastIntervalS;
    private long lastGpsAccelerationMs;
    private long previousGpsTimeMs;
    private float previousGpsSpeed;
    private float previousGpsSpeedAccuracy = Float.NaN;
    private float previousGpsHorizontalAccuracy = Float.NaN;
    private Location previousGpsLocation;
    private boolean gpsAccelerationValid;

    private int gnssSatellitesVisible;
""",
    "GPS acceleration fields",
)

replace_once(
    """    @Override
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
""",
    """    @Override
    public void onLocationChanged(Location location) {
        if (location == null || !location.hasSpeed()) return;

        float currentSpeed = Math.max(0f, location.getSpeed());
        float currentSpeedAccuracy =
                location.hasSpeedAccuracy()
                        ? location.getSpeedAccuracyMetersPerSecond()
                        : Float.NaN;
        float currentHorizontalAccuracy =
                location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        long currentTimeMs = location.getElapsedRealtimeNanos() / 1_000_000L;

        if (previousGpsTimeMs > 0L && currentTimeMs > previousGpsTimeMs) {
            float dt = (currentTimeMs - previousGpsTimeMs) / 1000f;
            gpsLastIntervalS = dt;
            gpsUpdateHz = dt > 0f ? 1f / dt : 0f;

            if (dt >= GPS_MIN_INTERVAL_S && dt <= GPS_MAX_INTERVAL_S) {
                float rawAcceleration = (currentSpeed - previousGpsSpeed) / dt;

                float displacement =
                        previousGpsLocation == null
                                ? Float.NaN
                                : previousGpsLocation.distanceTo(location);
                float expectedDisplacement =
                        0.5f * (previousGpsSpeed + currentSpeed) * dt;

                float positionAccuracySum = 0f;
                if (!Float.isNaN(previousGpsHorizontalAccuracy)) {
                    positionAccuracySum += previousGpsHorizontalAccuracy;
                }
                if (!Float.isNaN(currentHorizontalAccuracy)) {
                    positionAccuracySum += currentHorizontalAccuracy;
                }
                float positionTolerance =
                        Math.max(
                                8f,
                                positionAccuracySum
                                        + Math.max(2f, expectedDisplacement * 0.45f));
                float positionError =
                        Float.isNaN(displacement)
                                ? 0f
                                : Math.abs(displacement - expectedDisplacement);
                boolean positionCoherent =
                        Float.isNaN(displacement)
                                || expectedDisplacement < 3f
                                || positionError <= positionTolerance;

                if (!Float.isNaN(currentSpeedAccuracy)
                        && !Float.isNaN(previousGpsSpeedAccuracy)) {
                    gpsAccelerationUncertainty =
                            (float)
                                    (Math.sqrt(
                                                    currentSpeedAccuracy
                                                                    * currentSpeedAccuracy
                                                            + previousGpsSpeedAccuracy
                                                                    * previousGpsSpeedAccuracy)
                                            / dt);
                } else {
                    gpsAccelerationUncertainty = Float.NaN;
                }

                boolean speedAccuracyAcceptable =
                        (Float.isNaN(currentSpeedAccuracy)
                                        || currentSpeedAccuracy <= 2.5f)
                                && (Float.isNaN(previousGpsSpeedAccuracy)
                                        || previousGpsSpeedAccuracy <= 2.5f);
                boolean uncertaintyAcceptable =
                        Float.isNaN(gpsAccelerationUncertainty)
                                || gpsAccelerationUncertainty <= 3.0f;
                boolean accelerationPlausible =
                        Math.abs(rawAcceleration) <= GPS_ACCELERATION_MAX_ABS;

                if (positionCoherent
                        && speedAccuracyAcceptable
                        && uncertaintyAcceptable
                        && accelerationPlausible) {
                    boolean previousEstimateFresh =
                            gpsAccelerationValid
                                    && currentTimeMs - lastGpsAccelerationMs
                                            <= GPS_ACCELERATION_STALE_MS;
                    gpsAccelerationRaw = rawAcceleration;
                    if (!previousEstimateFresh) {
                        gpsAccelerationFiltered = rawAcceleration;
                    } else {
                        float alpha = dt / (GPS_ACCELERATION_TAU_S + dt);
                        gpsAccelerationFiltered +=
                                alpha
                                        * (rawAcceleration
                                                - gpsAccelerationFiltered);
                    }

                    float uncertaintyScore =
                            Float.isNaN(gpsAccelerationUncertainty)
                                    ? 0.45f
                                    : clamp(
                                            1f
                                                    - gpsAccelerationUncertainty
                                                            / 3.0f,
                                            0f,
                                            1f);
                    float positionScore =
                            expectedDisplacement < 3f
                                    ? 1f
                                    : clamp(
                                            1f - positionError / positionTolerance,
                                            0f,
                                            1f);
                    gpsAccelerationQuality =
                            100f
                                    * (0.70f * uncertaintyScore
                                            + 0.30f * positionScore);
                    gpsAccelerationValid = true;
                    lastGpsAccelerationMs = currentTimeMs;
                } else {
                    gpsAccelerationValid = false;
                }
            } else {
                gpsAccelerationValid = false;
            }
        }

        previousGpsTimeMs = currentTimeMs;
        previousGpsSpeed = currentSpeed;
        previousGpsSpeedAccuracy = currentSpeedAccuracy;
        previousGpsHorizontalAccuracy = currentHorizontalAccuracy;
        previousGpsLocation = new Location(location);

        gpsSpeed = currentSpeed;
        gpsSpeedAccuracy = currentSpeedAccuracy;
        gpsHorizontalAccuracy = currentHorizontalAccuracy;
        lastGpsMs = currentTimeMs;
        if (view != null) view.invalidate();
    }
""",
    "GPS acceleration calculation",
)

replace_once(
    """            drawAverageBars(canvas, width, height);
            drawGpsSpeedometer(canvas, width, height);
""",
    """            drawAverageBars(canvas, width, height);
            drawGpsAcceleration(canvas, width, height);
            drawGpsSpeedometer(canvas, width, height);
""",
    "GPS acceleration draw call",
)

replace_once(
    "            float areaBottom = height * 0.675f;",
    "            float areaBottom = height * 0.615f;",
    "inertial panel height",
)

replace_once(
    '                    "PROMEDIOS · zona neutra ±0.015 m/s²",',
    '                    "ACELERÓMETRO INERCIAL · promedios 2/5/10/20/50",',
    "inertial title",
)

replace_once(
    """        private void drawGpsSpeedometer(Canvas canvas, int width, int height) {
""",
    """        private void drawGpsAcceleration(Canvas canvas, int width, int height) {
            int labelRight = Math.round(width * 0.185f);
            int left = Math.round(width * 0.205f);
            int right = Math.round(width * 0.655f);
            int top = Math.round(height * 0.665f);
            int bottom = Math.round(height * 0.735f);
            int availablePixels = Math.max(1, right - left);

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
            float value = fresh ? gpsAccelerationFiltered : 0f;
            boolean positive = value > VISUAL_DEAD_ZONE;
            boolean negative = value < -VISUAL_DEAD_ZONE;
            int pixels =
                    Math.round(
                            availablePixels
                                    * signedAverageFillFraction(value));

            paint.setAntiAlias(true);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.021f);
            paint.setColor(Color.LTGRAY);
            canvas.drawText(
                    "ACELERÓMETRO GPS · Δv/Δt · filtro 0.7 s",
                    width * 0.02f,
                    height * 0.650f,
                    paint);

            int activeColor =
                    positive
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

            float centerY = (top + bottom) / 2f;
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize((bottom - top) * 0.36f);
            paint.setColor(Color.WHITE);
            float baseline = centerY - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText("GPS", labelRight * 0.32f, baseline, paint);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize((bottom - top) * 0.29f);
            paint.setColor(
                    positive
                            ? Color.rgb(155, 255, 180)
                            : negative
                                    ? Color.rgb(255, 175, 170)
                                    : Color.LTGRAY);
            canvas.drawText(
                    fresh
                            ? String.format(
                                    Locale.US,
                                    "%+.4f",
                                    gpsAccelerationFiltered)
                            : "---",
                    labelRight * 0.40f,
                    baseline,
                    paint);
        }

        private void drawGpsSpeedometer(Canvas canvas, int width, int height) {
""",
    "GPS acceleration drawing method",
)

replace_once(
    "            float bottom = height * 0.675f;",
    "            float bottom = height * 0.735f;",
    "speedometer panel height",
)

replace_once(
    """            String[] lines =
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
""",
    """            String gpsAccelerationAge =
                    lastGpsAccelerationMs == 0L
                            ? "sin estimación"
                            : (SystemClock.elapsedRealtime()
                                            - lastGpsAccelerationMs)
                                    + " ms";
            String gpsAccelerationError =
                    Float.isNaN(gpsAccelerationUncertainty)
                            ? "n/d"
                            : String.format(
                                    Locale.US,
                                    "±%.3f",
                                    gpsAccelerationUncertainty);

            String[] lines =
                    new String[]{
                        String.format(
                                Locale.US,
                                "Inercial filtrada %+.4f · aceptada %+.4f m/s²",
                                rawLongitudinal,
                                longitudinal),
                        String.format(
                                Locale.US,
                                "Inercial confianza %.0f%% · historial %d/50",
                                confidence,
                                historyCount),
                        String.format(
                                Locale.US,
                                "GPS acel. cruda %+.4f · filtrada %+.4f · error %s m/s²",
                                gpsAccelerationRaw,
                                gpsAccelerationFiltered,
                                gpsAccelerationError),
                        String.format(
                                Locale.US,
                                "GPS %.2f Hz · Δt %.3f s · calidad %.0f%% · edad %s",
                                gpsUpdateHz,
                                gpsLastIntervalS,
                                gpsAccelerationQuality,
                                gpsAccelerationAge),
                        String.format(
                                Locale.US,
                                "Velocidad GPS %.4f km/h · lectura %s",
                                gpsSpeed * 3.6f,
                                gpsAge)
                    };

            paint.setTextSize(height * 0.0170f);
            float y = height * 0.770f;
            for (String line : lines) {
                canvas.drawText(line, width * 0.035f, y, paint);
                y += height * 0.0215f;
            }
""",
    "diagnostic information",
)

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
for old, new in [
    (
        "applicationId 'com.example.accelledsinertial.continuous.gpsspeed.v30.decimals4'",
        "applicationId 'com.example.accelledsinertial.continuous.gpsspeed.v31.dualaccel'",
    ),
    (
        "versionName '3.0-velocimetro-gps-4-decimales'",
        "versionName '3.1-doble-acelerometro-inercial-gps'",
    ),
]:
    count = gradle.count(old)
    if count != 1:
        raise SystemExit(f"build.gradle: expected 1 match for {old!r}, found {count}")
    gradle = gradle.replace(old, new, 1)

GRADLE_PATH.write_text(gradle, encoding="utf-8")
