from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")
MANIFEST_PATH = Path("accel-leds-inertial/app/src/main/AndroidManifest.xml")

text = JAVA_PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.location.Location;\nimport android.location.LocationListener;\nimport android.location.LocationManager;",
    "import android.location.GnssStatus;\nimport android.location.Location;\nimport android.location.LocationListener;\nimport android.location.LocationManager;",
    "GNSS import",
)

replace_once(
    "import android.os.Build;\nimport android.os.Bundle;\nimport android.os.SystemClock;",
    "import android.os.Build;\nimport android.os.Bundle;\nimport android.os.Handler;\nimport android.os.Looper;\nimport android.os.SystemClock;",
    "handler imports",
)

replace_once(
    "    private static final float GREEN_FULL_SCALE = 3.0f;\n    private static final float RED_FULL_SCALE = 9.0f;\n",
    "    private static final float GREEN_FULL_SCALE = 3.0f;\n    private static final float RED_FULL_SCALE = 9.0f;\n    private static final float VISUAL_DEAD_ZONE = 0.015f;\n    private static final long GPS_STALE_MS = 2500L;\n",
    "GPS constants",
)

replace_once(
    "    private float gpsSpeed;\n    private long lastGpsMs;\n    private boolean showInfo = true;",
    "    private float gpsSpeed;\n    private float gpsSpeedAccuracy = Float.NaN;\n    private float gpsHorizontalAccuracy = Float.NaN;\n    private long lastGpsMs;\n    private int gnssSatellitesVisible;\n    private int gnssSatellitesUsed;\n    private boolean gnssStarted;\n    private boolean gnssCallbackRegistered;\n    private boolean showInfo = true;",
    "GPS fields",
)

replace_once(
    "    private String message = \"Fija el teléfono y pulsa CALIBRAR\";\n\n    @Override\n    protected void onCreate(Bundle state) {",
    """    private String message = \"Fija el teléfono y pulsa CALIBRAR\";

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
    protected void onCreate(Bundle state) {""",
    "GNSS callback",
)

replace_once(
    """        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f, this);
        } catch (Exception ignored) {
        }
""",
    """        try {
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
""",
    "location request",
)

replace_once(
    """    private void stopLocation() {
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
    }
""",
    """    private void stopLocation() {
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
""",
    "stop location",
)

replace_once(
    """    public void onLocationChanged(Location location) {
        if (location != null && location.hasSpeed()) {
            gpsSpeed = Math.max(0f, location.getSpeed());
            lastGpsMs = SystemClock.elapsedRealtime();
            view.invalidate();
        }
    }
""",
    """    public void onLocationChanged(Location location) {
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
    "location callback",
)

replace_once(
    """    private static float signedAverageFillFraction(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= 0f) return 0f;

        float maximum = value >= 0f ? GREEN_FULL_SCALE : RED_FULL_SCALE;
        float normalized =
                (float)
                        (Math.log1p(magnitude / 0.15f)
                                / Math.log1p(maximum / 0.15f));

        return clamp(normalized, 0f, 1f);
    }
""",
    """    private static float signedAverageFillFraction(float value) {
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
""",
    "visual dead zone",
)

replace_once(
    "            drawAverageBars(canvas, width, height);\n\n            if (showInfo) {",
    "            drawAverageBars(canvas, width, height);\n            drawGpsSpeedometer(canvas, width, height);\n\n            if (showInfo) {",
    "speedometer call",
)

replace_once(
    "            int right = width - Math.round(width * 0.035f);",
    "            int right = Math.round(width * 0.655f);",
    "bar panel width",
)

replace_once(
    "                    \"PROMEDIOS — VERDE +3.0 / ROJO −9.0 m/s²\",",
    "                    \"PROMEDIOS · zona neutra ±0.015 m/s²\",",
    "bar title",
)

marker = "        private void drawButton(Canvas canvas, int index, String label) {"
if text.count(marker) != 1:
    raise SystemExit("drawButton marker not unique")

speedometer_method = """        private void drawGpsSpeedometer(Canvas canvas, int width, int height) {
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
                    \"VELOCIDAD GPS\",
                    (left + right) / 2f,
                    top + height * 0.050f,
                    paint);

            String speedText =
                    fresh
                            ? String.format(Locale.US, \"%.1f\", gpsSpeed * 3.6f)
                            : \"---\";
            paint.setTextSize(height * 0.125f);
            paint.setColor(fresh ? Color.WHITE : Color.rgb(120, 120, 120));
            canvas.drawText(
                    speedText,
                    (left + right) / 2f,
                    top + height * 0.205f,
                    paint);

            paint.setTextSize(height * 0.032f);
            paint.setColor(Color.rgb(190, 205, 220));
            canvas.drawText(
                    \"km/h\",
                    (left + right) / 2f,
                    top + height * 0.255f,
                    paint);

            String accuracyText =
                    Float.isNaN(gpsSpeedAccuracy)
                            ? \"precisión de velocidad: n/d\"
                            : String.format(
                                    Locale.US,
                                    \"±%.1f km/h (68%%)\",
                                    gpsSpeedAccuracy * 3.6f);

            String quality;
            int qualityColor;
            if (!fresh) {
                quality = gnssStarted ? \"BUSCANDO FIJACIÓN\" : \"GPS INACTIVO\";
                qualityColor = Color.rgb(255, 190, 70);
            } else if (Float.isNaN(gpsSpeedAccuracy)) {
                quality = \"FIJACIÓN SIN INCERTIDUMBRE\";
                qualityColor = Color.rgb(255, 210, 80);
            } else if (gpsSpeedAccuracy <= 0.35f) {
                quality = \"PRECISIÓN ALTA\";
                qualityColor = Color.rgb(80, 255, 130);
            } else if (gpsSpeedAccuracy <= 0.80f) {
                quality = \"PRECISIÓN MEDIA\";
                qualityColor = Color.rgb(255, 215, 80);
            } else {
                quality = \"PRECISIÓN BAJA\";
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
                            \"satélites usados %d / visibles %d\",
                            gnssSatellitesUsed,
                            gnssSatellitesVisible),
                    (left + right) / 2f,
                    top + height * 0.390f,
                    paint);

            String positionAccuracy =
                    Float.isNaN(gpsHorizontalAccuracy)
                            ? \"precisión de posición: n/d\"
                            : String.format(
                                    Locale.US,
                                    \"posición ±%.1f m\",
                                    gpsHorizontalAccuracy);
            canvas.drawText(
                    positionAccuracy,
                    (left + right) / 2f,
                    top + height * 0.425f,
                    paint);

            String ageText =
                    lastGpsMs == 0L
                            ? \"sin lectura de velocidad\"
                            : String.format(Locale.US, \"edad %d ms\", ageMs);
            canvas.drawText(
                    ageText,
                    (left + right) / 2f,
                    top + height * 0.460f,
                    paint);
        }

"""
text = text.replace(marker, speedometer_method + marker, 1)

replace_once(
    "                        \"Cada fila: positivo verde / negativo rojo\",",
    "                        \"GPS directo con precisión, edad y satélites\",",
    "info GPS line",
)

JAVA_PATH.write_text(text, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
for old, new in [
    (
        "applicationId 'com.example.accelledsinertial.continuous.signedaverages50'",
        "applicationId 'com.example.accelledsinertial.continuous.gpsspeed.v29'",
    ),
    (
        "versionName '2.7-promedios-con-signo-50'",
        "versionName '2.9-velocimetro-gps'",
    ),
]:
    if gradle.count(old) != 1:
        raise SystemExit(f"build.gradle replacement missing: {old}")
    gradle = gradle.replace(old, new, 1)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

manifest = MANIFEST_PATH.read_text(encoding="utf-8")
old_label = 'android:label="Accel LEDs Continuo"'
if manifest.count(old_label) != 1:
    raise SystemExit("manifest label replacement missing")
MANIFEST_PATH.write_text(
    manifest.replace(old_label, 'android:label="Accel LEDs GPS"', 1),
    encoding="utf-8",
)
