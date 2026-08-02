from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")

old_tick_block = '''            String[] tickLabels =
                    negative
                            ? new String[]{"−32.4", "−24.3", "−16.2", "−8.1", "0"}
                            : new String[]{"0", "+2.7", "+5.4", "+8.1", "+10.8"};
'''
new_tick_block = '''            String[] tickLabels =
                    negative
                            ? new String[]{"−32.4", "−24.3", "−16.2", "−8.1", "0"}
                            : new String[]{"0", "+2.7", "+5.4", "+8.1", "+10.8"};
            float[] tickValues =
                    negative
                            ? new float[]{-9.0f, -6.75f, -4.50f, -2.25f, 0f}
                            : new float[]{0f, 0.75f, 1.50f, 2.25f, 3.0f};
'''
if java.count(old_tick_block) != 1:
    raise SystemExit(f"bloque de marcas: se esperaba 1 coincidencia, encontradas {java.count(old_tick_block)}")
java = java.replace(old_tick_block, new_tick_block, 1)

old_x = '''                float x = left + (right - left) * i / (tickLabels.length - 1f);
'''
new_x = '''                float tickFraction = signedFillFraction(tickValues[i]);
                float x =
                        negative
                                ? right - (right - left) * tickFraction
                                : left + (right - left) * tickFraction;
'''
if java.count(old_x) != 2:
    raise SystemExit(f"posición lineal: se esperaban 2 coincidencias, encontradas {java.count(old_x)}")
java = java.replace(old_x, new_x)

if java.count("signedFillFraction(tickValues[i])") != 2:
    raise SystemExit("Las marcas no usan la función logarítmica en ambos recorridos")
if "i / (tickLabels.length - 1f)" in java:
    raise SystemExit("Quedó una posición lineal de marcas")

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
gradle = gradle.replace(
    "applicationId 'com.example.accelledsinertial.gpsonly.v43.axisunits'",
    "applicationId 'com.example.accelledsinertial.gpsonly.v44.logaxis'",
)
gradle = gradle.replace(
    "versionName '4.3-gps-eje-km-h-seg'",
    "versionName '4.4-gps-eje-logaritmico-corregido'",
)
if "v44.logaxis" not in gradle or "4.4-gps-eje-logaritmico-corregido" not in gradle:
    raise SystemExit("No se pudo actualizar la versión 4.4")
GRADLE_PATH.write_text(gradle, encoding="utf-8")

print("Eje logarítmico v4.4 aplicado y validado")
