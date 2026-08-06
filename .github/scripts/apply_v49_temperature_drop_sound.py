from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")

replacements = [
    (
        "import android.location.LocationManager;\nimport android.os.BatteryManager;",
        "import android.location.LocationManager;\nimport android.media.AudioManager;\nimport android.media.ToneGenerator;\nimport android.os.BatteryManager;",
        "imports de audio",
    ),
    (
        "    private static final float GPS_MIN_INTERVAL_S = 0.12f;\n    private static final float GPS_MAX_INTERVAL_S = 3.0f;\n",
        "    private static final float GPS_MIN_INTERVAL_S = 0.12f;\n    private static final float GPS_MAX_INTERVAL_S = 3.0f;\n\n    private static final float TEMPERATURE_HYSTERESIS_C = 0.2f;\n    private static final int TEMPERATURE_DROP_TONE_MS = 180;\n    private static final long TEMPERATURE_DROP_TONE_GAP_MS = 120L;\n",
        "constantes de temperatura",
    ),
    (
        "    private float batteryTemperatureC = Float.NaN;\n    private boolean batteryReceiverRegistered;\n\n    private float gpsSpeed;\n",
        '''    private float batteryTemperatureC = Float.NaN;
    private boolean batteryReceiverRegistered;

    private final TemperatureDropTracker temperatureDropTracker =
            new TemperatureDropTracker(TEMPERATURE_HYSTERESIS_C);
    private final Handler temperatureToneHandler =
            new Handler(Looper.getMainLooper());
    private ToneGenerator temperatureToneGenerator;
    private int pendingTemperatureDropTones;
    private boolean temperatureDropToneRunning;

    private final Runnable temperatureDropToneRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (pendingTemperatureDropTones <= 0) {
                        temperatureDropToneRunning = false;
                        return;
                    }

                    ToneGenerator generator = ensureTemperatureToneGenerator();
                    if (generator == null) {
                        pendingTemperatureDropTones = 0;
                        temperatureDropToneRunning = false;
                        return;
                    }

                    pendingTemperatureDropTones--;
                    try {
                        generator.startTone(
                                ToneGenerator.TONE_DTMF_0,
                                TEMPERATURE_DROP_TONE_MS);
                    } catch (RuntimeException ignored) {
                    }

                    temperatureToneHandler.postDelayed(
                            this,
                            TEMPERATURE_DROP_TONE_MS
                                    + TEMPERATURE_DROP_TONE_GAP_MS);
                }
            };

    private float gpsSpeed;
''',
        "estado y cola de tonos",
    ),
    (
        '''    @Override
    protected void onPause() {
        stopLocation();
        stopBatteryTemperature();
        super.onPause();
    }

    private void startBatteryTemperature() {
''',
        '''    @Override
    protected void onPause() {
        stopLocation();
        stopBatteryTemperature();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseTemperatureToneGenerator();
        super.onDestroy();
    }

    private void startBatteryTemperature() {
''',
        "liberación en onDestroy",
    ),
    (
        '''    private void updateBatteryTemperature(Intent intent) {
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
''',
        '''    private void updateBatteryTemperature(Intent intent) {
        float newTemperatureC = Float.NaN;
        if (intent != null && intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            int tenthsCelsius =
                    intent.getIntExtra(
                            BatteryManager.EXTRA_TEMPERATURE,
                            Integer.MIN_VALUE);
            if (tenthsCelsius != Integer.MIN_VALUE) {
                newTemperatureC = tenthsCelsius / 10f;
            }
        }

        batteryTemperatureC = newTemperatureC;
        if (!Float.isNaN(batteryTemperatureC)) {
            enqueueTemperatureDropTones(
                    temperatureDropTracker.update(batteryTemperatureC));
        }
        invalidateView();
    }

    private void enqueueTemperatureDropTones(int count) {
        if (count <= 0) return;

        pendingTemperatureDropTones += count;
        if (temperatureDropToneRunning) return;

        temperatureDropToneRunning = true;
        temperatureToneHandler.post(temperatureDropToneRunnable);
    }

    private ToneGenerator ensureTemperatureToneGenerator() {
        if (temperatureToneGenerator != null) return temperatureToneGenerator;

        try {
            temperatureToneGenerator =
                    new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException ignored) {
            temperatureToneGenerator = null;
        }
        return temperatureToneGenerator;
    }

    private void releaseTemperatureToneGenerator() {
        temperatureToneHandler.removeCallbacks(temperatureDropToneRunnable);
        pendingTemperatureDropTones = 0;
        temperatureDropToneRunning = false;

        if (temperatureToneGenerator == null) return;
        try {
            temperatureToneGenerator.stopTone();
            temperatureToneGenerator.release();
        } catch (RuntimeException ignored) {
        }
        temperatureToneGenerator = null;
    }

    private void hideUi() {
''',
        "detección y reproducción de descensos",
    ),
]

for old, new, label in replacements:
    count = java.count(old)
    if count != 1:
        raise SystemExit(f"{label}: se esperaba 1 coincidencia, encontradas {count}")
    java = java.replace(old, new, 1)

required_java = [
    "import android.media.AudioManager;",
    "import android.media.ToneGenerator;",
    "new TemperatureDropTracker(TEMPERATURE_HYSTERESIS_C)",
    "temperatureDropTracker.update(batteryTemperatureC)",
    "new ToneGenerator(AudioManager.STREAM_ALARM, 100)",
    "ToneGenerator.TONE_DTMF_0",
    "pendingTemperatureDropTones += count;",
    "releaseTemperatureToneGenerator();",
    "Math.log(magnitude / VISUAL_DEAD_ZONE)",
    "private static final float ACCELERATION_FULL_SCALE = 9.0f;",
]
for fragment in required_java:
    if fragment not in java:
        raise SystemExit(f"Falta validación Java v4.9: {fragment}")

if java.count("generator.startTone(") != 1:
    raise SystemExit("Debe existir exactamente una reproducción nueva, solo para descenso")
if "Math.log1p" in java:
    raise SystemExit("La escala logarítmica real fue alterada")

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
old_application_id = "applicationId 'com.example.accelledsinertial.gpsonly.v48.sharedleftright'"
old_version = "versionName '4.8-gps-rango-comun-izquierda-derecha'"
if gradle.count(old_application_id) != 1 or gradle.count(old_version) != 1:
    raise SystemExit("No se encontró exactamente la configuración de versión 4.8")
gradle = gradle.replace(
    old_application_id,
    "applicationId 'com.example.accelledsinertial.gpsonly.v49.temperaturedropsound'",
    1,
)
gradle = gradle.replace(
    old_version,
    "versionName '4.9-gps-sonido-descenso-temperatura'",
    1,
)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

print("Sonido grave por descenso de temperatura v4.9 aplicado y validado")
