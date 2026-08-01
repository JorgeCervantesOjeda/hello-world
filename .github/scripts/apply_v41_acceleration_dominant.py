from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")


def replace_section(start_marker: str, end_marker: str, replacement: str, label: str) -> None:
    global java
    start = java.find(start_marker)
    end = java.find(end_marker, start)
    if start < 0 or end < 0 or end <= start:
        raise SystemExit(f"No se encontró la sección: {label}")
    java = java[:start] + replacement.rstrip() + "\n\n" + java[end:]


replace_section(
    "        private void drawHeader(Canvas canvas, int width, int height) {",
    "        private void drawSpeedPanel(Canvas canvas, int width, int height) {",
    '''        private void drawHeader(Canvas canvas, int width, int height) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.039f);
            paint.setColor(Color.WHITE);
            canvas.drawText(
                    "GPS · ACELERACIÓN DOMINANTE",
                    width * 0.035f,
                    height * 0.078f,
                    paint);

            float left = width * 0.765f;
            float top = height * 0.020f;
            float right = width * 0.965f;
            float bottom = height * 0.105f;
            rectangle.set(left, top, right, bottom);
            paint.setColor(Color.rgb(32, 32, 32));
            canvas.drawRoundRect(rectangle, height * 0.018f, height * 0.018f, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.026f);
            paint.setColor(Color.WHITE);
            canvas.drawText(
                    showInfo ? "OCULTAR INFO" : "MOSTRAR INFO",
                    (left + right) / 2f,
                    top + (bottom - top) * 0.64f,
                    paint);
        }''',
    "encabezado",
)

replace_section(
    "        private void drawSpeedPanel(Canvas canvas, int width, int height) {",
    "        private void drawAccelerationPanel(Canvas canvas, int width, int height) {",
    '''        private void drawSpeedPanel(Canvas canvas, int width, int height) {
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
        }''',
    "panel de velocidad",
)

replace_section(
    "        private void drawAccelerationPanel(Canvas canvas, int width, int height) {",
    "        private void drawDiagnostics(Canvas canvas, int width, int height) {",
    '''        private void drawAccelerationPanel(Canvas canvas, int width, int height) {
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
                            ? String.format(Locale.US, "%+.4f", gpsAccelerationFiltered)
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
                    "m/s²",
                    (left + right) / 2f,
                    top + panelHeight * 0.395f,
                    paint);

            float barLeft = left + (right - left) * 0.025f;
            float barRight = right - (right - left) * 0.025f;
            float barTop = top + panelHeight * 0.475f;
            float barBottom = top + panelHeight * 0.805f;
            drawAccelerationBar(
                    canvas,
                    barLeft,
                    barRight,
                    barTop,
                    barBottom,
                    fresh ? gpsAccelerationFiltered : 0f);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(height * 0.019f);
            paint.setColor(Color.rgb(170, 255, 195));
            canvas.drawText("+3.0 m/s²", barLeft, top + panelHeight * 0.885f, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(Color.rgb(255, 175, 170));
            canvas.drawText("−9.0 m/s²", barRight, top + panelHeight * 0.885f, paint);

            String status = fresh ? accelerationQualityText() : "SIN ESTIMACIÓN VÁLIDA";
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.022f);
            paint.setColor(fresh ? accelerationQualityColor() : Color.rgb(255, 190, 70));
            canvas.drawText(
                    status + " · zona neutra ±0.015",
                    (left + right) / 2f,
                    top + panelHeight * 0.955f,
                    paint);
        }''',
    "panel de aceleración dominante",
)

for old, new, label in [
    ("            float top = height * 0.690f;", "            float top = height * 0.785f;", "posición del diagnóstico"),
    ("                y += height * 0.047f;", "                y += height * 0.040f;", "espaciado del diagnóstico"),
]:
    count = java.count(old)
    if count != 1:
        raise SystemExit(f"{label}: se esperaba 1 coincidencia y se encontraron {count}")
    java = java.replace(old, new, 1)

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
for old, new in [
    (
        "applicationId 'com.example.accelledsinertial.gpsonly.v40'",
        "applicationId 'com.example.accelledsinertial.gpsonly.v41.dominantbar'",
    ),
    (
        "versionName '4.0-solo-gps-velocidad-aceleracion'",
        "versionName '4.1-gps-barra-aceleracion-dominante'",
    ),
]:
    count = gradle.count(old)
    if count != 1:
        raise SystemExit(f"build.gradle: se esperaba 1 coincidencia para {old!r}, encontradas {count}")
    gradle = gradle.replace(old, new, 1)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

checks = [
    "GPS · ACELERACIÓN DOMINANTE",
    "barTop = top + panelHeight * 0.475f",
    "barBottom = top + panelHeight * 0.805f",
    "versionName '4.1-gps-barra-aceleracion-dominante'",
]
combined = java + "\n" + gradle
for marker in checks:
    if marker not in combined:
        raise SystemExit(f"Falta marcador validado: {marker}")
