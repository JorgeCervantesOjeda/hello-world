from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")

replacements = [
    (
        '                            ? String.format(Locale.US, "%.1f", gpsSpeed * 3.6f)',
        '                            ? String.format(Locale.US, "%.4f", gpsSpeed * 3.6f)',
        "formato principal del velocímetro",
    ),
    (
        '                                "GPS %.1f km/h · edad %s",',
        '                                "GPS %.4f km/h · edad %s",',
        "formato GPS del diagnóstico",
    ),
    (
        '''            paint.setTextSize(height * 0.125f);
            paint.setColor(fresh ? Color.WHITE : Color.rgb(120, 120, 120));''',
        '''            paint.setTextSize(height * 0.125f);
            float maximumSpeedTextWidth = (right - left) * 0.90f;
            float measuredSpeedTextWidth = paint.measureText(speedText);
            if (measuredSpeedTextWidth > maximumSpeedTextWidth
                    && measuredSpeedTextWidth > 0f) {
                paint.setTextSize(
                        paint.getTextSize()
                                * maximumSpeedTextWidth
                                / measuredSpeedTextWidth);
            }
            paint.setColor(fresh ? Color.WHITE : Color.rgb(120, 120, 120));''',
        "ajuste automático de tipografía",
    ),
]

for old, new, label in replacements:
    count = java.count(old)
    if count != 1:
        raise SystemExit(f"{label}: se esperaba 1 coincidencia y se encontraron {count}")
    java = java.replace(old, new, 1)

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
gradle_replacements = [
    (
        "applicationId 'com.example.accelledsinertial.continuous.gpsspeed.v29'",
        "applicationId 'com.example.accelledsinertial.continuous.gpsspeed.v30.decimals4'",
    ),
    (
        "versionName '2.9-velocimetro-gps'",
        "versionName '3.0-velocimetro-gps-4-decimales'",
    ),
]

for old, new in gradle_replacements:
    count = gradle.count(old)
    if count != 1:
        raise SystemExit(f"build.gradle: se esperaba 1 coincidencia para {old!r}, encontradas {count}")
    gradle = gradle.replace(old, new, 1)

GRADLE_PATH.write_text(gradle, encoding="utf-8")
