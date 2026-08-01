from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global java
    count = java.count(old)
    if count != 1:
        raise SystemExit(f"{label}: se esperaba 1 coincidencia y se encontraron {count}")
    java = java.replace(old, new, 1)


def replace_section(start_marker: str, end_marker: str, replacement: str, label: str) -> None:
    global java
    start = java.find(start_marker)
    end = java.find(end_marker, start)
    if start < 0 or end < 0 or end <= start:
        raise SystemExit(f"No se encontró la sección: {label}")
    java = java[:start] + replacement.rstrip() + "\n\n" + java[end:]


replace_once(
    "import android.app.Activity;\nimport android.content.Context;\n",
    """import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
""",
    "imports de batería",
)

replace_once(
    "import android.os.Build;\nimport android.os.Bundle;\n",
    """import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
""",
    "BatteryManager",
)

replace_once(
    """    private LocationManager locationManager;
    private GpsView view;
""",
    """    private LocationManager locationManager;
    private GpsView view;

    private float batteryTemperatureC = Float.NaN;
    private boolean batteryReceiverRegistered;
""",
    "campos de temperatura",
)

replace_once(
    """    private boolean gnssCallbackRegistered;
    private boolean showInfo = true;

    private final GnssStatus.Callback gnssStatusCallback =
""",
    """    private boolean gnssCallbackRegistered;
    private boolean showInfo = true;

    private final BroadcastReceiver batteryReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateBatteryTemperature(intent);
                }
            };

    private final GnssStatus.Callback gnssStatusCallback =
""",
    "receptor de batería",
)

replace_once(
    """    protected void onResume() {
        super.onResume();
        hideUi();
        startLocation();
    }

    @Override
    protected void onPause() {
        stopLocation();
        super.onPause();
    }

    private void hideUi() {
""",
    """    protected void onResume() {
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
""",
    "ciclo de vida del termómetro",
)

replace_section(
    "        private void drawHeader(Canvas canvas, int width, int height) {",
    "        private void drawSpeedPanel(Canvas canvas, int width, int height) {",
    """        private void drawHeader(Canvas canvas, int width, int height) {
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
""",
    "encabezado y termómetro",
)

replace_once(
    """            if (event.getX() >= getWidth() * 0.73f
                    && event.getY() <= getHeight() * 0.14f) {
""",
    """            if (event.getX() >= getWidth() * 0.52f
                    && event.getX() <= getWidth() * 0.75f
                    && event.getY() <= getHeight() * 0.14f) {
""",
    "zona táctil del botón",
)

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
old_gradle = """        applicationId 'com.example.accelledsinertial.gpsonly.v41.dominantbar'
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName '4.1-gps-barra-aceleracion-dominante'
"""
new_gradle = """        applicationId 'com.example.accelledsinertial.gpsonly.v42.batterythermometer'
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName '4.2-gps-termometro-bateria'
"""
if gradle.count(old_gradle) != 1:
    raise SystemExit("No se encontró la versión 4.1 esperada en build.gradle")
gradle = gradle.replace(old_gradle, new_gradle, 1)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

checks = {
    "termómetro gráfico": "drawBatteryThermometer",
    "temperatura de batería": "BatteryManager.EXTRA_TEMPERATURE",
    "receptor dinámico": "batteryReceiver",
    "umbral amarillo": "batteryTemperatureC >= 40f",
    "umbral rojo": "batteryTemperatureC >= 45f",
    "versión 4.2": "4.2-gps-termometro-bateria",
}
combined = java + "\n" + gradle
for label, marker in checks.items():
    if marker not in combined:
        raise SystemExit(f"Validación ausente: {label}")

print("Termómetro de batería v4.2 aplicado y validado")
