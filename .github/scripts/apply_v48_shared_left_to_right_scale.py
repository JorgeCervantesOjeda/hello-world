from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")

replacements = [
    (
        '''    private static final float GREEN_FULL_SCALE = 3.0f;
    private static final float RED_FULL_SCALE = 9.0f;
''',
        '''    private static final float ACCELERATION_FULL_SCALE = 9.0f;
''',
        "límites separados",
    ),
    (
        '''        float maximum = value >= 0f ? GREEN_FULL_SCALE : RED_FULL_SCALE;
''',
        '''        float maximum = ACCELERATION_FULL_SCALE;
''',
        "máximo por signo",
    ),
    (
        '''            String[] greenTickLabels =
                    new String[]{"0.25", "0.5", "1", "2", "4", "8", "10.8"};
            float[] greenTickDisplayValues =
                    new float[]{0.25f, 0.5f, 1f, 2f, 4f, 8f, 10.8f};
            String[] redTickLabels =
                    new String[]{"−32.4", "−16", "−8", "−4", "−2", "−1", "−0.5", "−0.25"};
            float[] redTickDisplayValues =
                    new float[]{-32.4f, -16f, -8f, -4f, -2f, -1f, -0.5f, -0.25f};
''',
        '''            String[] greenTickLabels =
                    new String[]{"0.25", "0.5", "1", "2", "4", "8", "16", "32.4"};
            float[] greenTickDisplayValues =
                    new float[]{0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 32.4f};
            String[] redTickLabels =
                    new String[]{"−0.25", "−0.5", "−1", "−2", "−4", "−8", "−16", "−32.4"};
            float[] redTickDisplayValues =
                    new float[]{-0.25f, -0.5f, -1f, -2f, -4f, -8f, -16f, -32.4f};
''',
        "marcas de escala",
    ),
    (
        '''                redXs[i] = right - barWidth * signedFillFraction(tickValue);
''',
        '''                redXs[i] = left + barWidth * signedFillFraction(tickValue);
''',
        "posición de marcas rojas",
    ),
    (
        '''            float activeLeft = negative ? right - pixels : left;
            float activeRight = negative ? right : left + pixels;
''',
        '''            float activeLeft = left;
            float activeRight = left + pixels;
''',
        "dirección de barra roja",
    ),
    (
        '''                float maximum = negative ? RED_FULL_SCALE : GREEN_FULL_SCALE;
''',
        '''                float maximum = ACCELERATION_FULL_SCALE;
''',
        "máximo de incertidumbre",
    ),
    (
        '''                float bandLeft =
                        negative ? right - barWidth * highFraction : left + barWidth * lowFraction;
                float bandRight =
                        negative ? right - barWidth * lowFraction : left + barWidth * highFraction;
''',
        '''                float bandLeft = left + barWidth * lowFraction;
                float bandRight = left + barWidth * highFraction;
''',
        "dirección de banda de incertidumbre",
    ),
    (
        '''                float x = negative ? right - barWidth * tickFraction : left + barWidth * tickFraction;
''',
        '''                float x = left + barWidth * tickFraction;
''',
        "dirección de líneas activas",
    ),
    (
        '''            float edgeX = negative ? activeLeft : activeRight;
''',
        '''            float edgeX = activeRight;
''',
        "posición del marcador",
    ),
]

for old, new, label in replacements:
    count = java.count(old)
    if count != 1:
        raise SystemExit(f"{label}: se esperaba 1 coincidencia, encontradas {count}")
    java = java.replace(old, new, 1)

required = [
    "private static final float ACCELERATION_FULL_SCALE = 9.0f;",
    'new String[]{"0.25", "0.5", "1", "2", "4", "8", "16", "32.4"}',
    'new String[]{"−0.25", "−0.5", "−1", "−2", "−4", "−8", "−16", "−32.4"}',
    "redXs[i] = left + barWidth * signedFillFraction(tickValue);",
    "float activeLeft = left;",
    "float activeRight = left + pixels;",
    "float edgeX = activeRight;",
]
for fragment in required:
    if fragment not in java:
        raise SystemExit(f"Falta validación v4.8: {fragment}")

for forbidden in [
    "GREEN_FULL_SCALE",
    "RED_FULL_SCALE",
    "right - barWidth * signedFillFraction(tickValue)",
    "negative ? right - barWidth * tickFraction",
    "negative ? activeLeft : activeRight",
]:
    if forbidden in java:
        raise SystemExit(f"Quedó lógica anterior: {forbidden}")

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
old_application_id = "applicationId 'com.example.accelledsinertial.gpsonly.v47.truelogscale'"
old_version = "versionName '4.7-gps-escala-logaritmica-real'"
if gradle.count(old_application_id) != 1 or gradle.count(old_version) != 1:
    raise SystemExit("No se encontró exactamente la configuración de versión 4.7")
gradle = gradle.replace(
    old_application_id,
    "applicationId 'com.example.accelledsinertial.gpsonly.v48.sharedleftright'",
    1,
)
gradle = gradle.replace(
    old_version,
    "versionName '4.8-gps-rango-comun-izquierda-derecha'",
    1,
)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

print("Escala común y barras izquierda-derecha v4.8 aplicadas y validadas")
