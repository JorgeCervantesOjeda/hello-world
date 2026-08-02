from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")

old_function = '''    private static float signedFillFraction(float value) {
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
'''

new_function = '''    private static float signedFillFraction(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= VISUAL_DEAD_ZONE) return 0f;

        float maximum = value >= 0f ? GREEN_FULL_SCALE : RED_FULL_SCALE;
        float normalized =
                (float)
                        (Math.log(magnitude / VISUAL_DEAD_ZONE)
                                / Math.log(maximum / VISUAL_DEAD_ZONE));
        return clamp(normalized, 0f, 1f);
    }
'''

if java.count(old_function) != 1:
    raise SystemExit(
        f"función log1p: se esperaba 1 coincidencia, encontradas {java.count(old_function)}"
    )
java = java.replace(old_function, new_function, 1)

if "Math.log1p" in java:
    raise SystemExit("Quedó una transformación log1p en el código")
if java.count("Math.log(magnitude / VISUAL_DEAD_ZONE)") != 1:
    raise SystemExit("No se instaló la escala logarítmica real")
if java.count("Math.log(maximum / VISUAL_DEAD_ZONE)") != 1:
    raise SystemExit("No se instaló la normalización logarítmica real")

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
old_application_id = "applicationId 'com.example.accelledsinertial.gpsonly.v46.uncertaintyreadability'"
old_version = "versionName '4.6-gps-incertidumbre-legibilidad'"
if gradle.count(old_application_id) != 1 or gradle.count(old_version) != 1:
    raise SystemExit("No se encontró exactamente la configuración de versión 4.6")
gradle = gradle.replace(
    old_application_id,
    "applicationId 'com.example.accelledsinertial.gpsonly.v47.truelogscale'",
    1,
)
gradle = gradle.replace(
    old_version,
    "versionName '4.7-gps-escala-logaritmica-real'",
    1,
)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

print("Escala logarítmica real v4.7 aplicada y validada")
