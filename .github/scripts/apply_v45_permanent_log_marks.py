from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")

old_call = '''            float axisLabelY = top + panelHeight * 0.885f;
            drawAccelerationBar(
                    canvas,
                    barLeft,
                    barRight,
                    barTop,
                    barBottom,
                    axisLabelY,
                    fresh ? gpsAccelerationFiltered : 0f);
'''
new_call = '''            float greenAxisLabelY = barTop - height * 0.010f;
            float redAxisLabelY = top + panelHeight * 0.885f;
            drawAccelerationBar(
                    canvas,
                    barLeft,
                    barRight,
                    barTop,
                    barBottom,
                    greenAxisLabelY,
                    redAxisLabelY,
                    fresh ? gpsAccelerationFiltered : 0f);
'''
if java.count(old_call) != 1:
    raise SystemExit(
        f"llamada de barra: se esperaba 1 coincidencia, encontradas {java.count(old_call)}"
    )
java = java.replace(old_call, new_call, 1)

method_start = java.index("        private void drawAccelerationBar(")
method_end = java.index("        private String accelerationQualityText()", method_start)

new_method = '''        private void drawAccelerationBar(
                Canvas canvas,
                float left,
                float right,
                float top,
                float bottom,
                float greenAxisLabelY,
                float redAxisLabelY,
                float value) {
            paint.setStyle(Paint.Style.FILL);
            paint.setAntiAlias(false);
            paint.setColor(Color.rgb(24, 24, 24));
            canvas.drawRect(left, top, right, bottom, paint);

            String[] greenTickLabels =
                    new String[]{"0.25", "0.5", "1", "2", "4", "8", "10.8"};
            float[] greenTickDisplayValues =
                    new float[]{0.25f, 0.5f, 1f, 2f, 4f, 8f, 10.8f};
            String[] redTickLabels =
                    new String[]{"−32.4", "−16", "−8", "−4", "−2", "−1", "−0.5", "−0.25"};
            float[] redTickDisplayValues =
                    new float[]{-32.4f, -16f, -8f, -4f, -2f, -1f, -0.5f, -0.25f};

            float barWidth = right - left;
            paint.setAntiAlias(true);
            paint.setTextSize(getHeight() * 0.0175f);

            paint.setColor(Color.rgb(170, 255, 195));
            for (int i = 0; i < greenTickLabels.length; i++) {
                float tickValue = greenTickDisplayValues[i] / ACCELERATION_DISPLAY_FACTOR;
                float x = left + barWidth * signedFillFraction(tickValue);
                if (x <= left + barWidth * 0.025f) {
                    paint.setTextAlign(Paint.Align.LEFT);
                } else if (x >= right - barWidth * 0.025f) {
                    paint.setTextAlign(Paint.Align.RIGHT);
                } else {
                    paint.setTextAlign(Paint.Align.CENTER);
                }
                canvas.drawText(greenTickLabels[i], x, greenAxisLabelY, paint);
            }

            paint.setColor(Color.rgb(255, 175, 170));
            for (int i = 0; i < redTickLabels.length; i++) {
                float tickValue = redTickDisplayValues[i] / ACCELERATION_DISPLAY_FACTOR;
                float x = right - barWidth * signedFillFraction(tickValue);
                if (x <= left + barWidth * 0.025f) {
                    paint.setTextAlign(Paint.Align.LEFT);
                } else if (x >= right - barWidth * 0.025f) {
                    paint.setTextAlign(Paint.Align.RIGHT);
                } else {
                    paint.setTextAlign(Paint.Align.CENTER);
                }
                canvas.drawText(redTickLabels[i], x, redAxisLabelY, paint);
            }

            float fraction = signedFillFraction(value);
            if (fraction <= 0f) return;

            float pixels = barWidth * fraction;
            boolean negative = value < -VISUAL_DEAD_ZONE;
            float activeLeft = negative ? right - pixels : left;
            float activeRight = negative ? right : left + pixels;
            int activeColor =
                    negative
                            ? Color.rgb(255, 35, 25)
                            : Color.rgb(0, 255, 70);

            paint.setAntiAlias(false);
            paint.setColor(activeColor);
            canvas.drawRect(activeLeft, top, activeRight, bottom, paint);

            float[] activeTickDisplayValues =
                    negative ? redTickDisplayValues : greenTickDisplayValues;
            paint.setStrokeWidth(Math.max(1f, getHeight() * 0.0022f));
            paint.setColor(Color.WHITE);
            paint.setAlpha(95);
            for (float displayValue : activeTickDisplayValues) {
                float tickValue = displayValue / ACCELERATION_DISPLAY_FACTOR;
                float tickFraction = signedFillFraction(tickValue);
                float x = negative ? right - barWidth * tickFraction : left + barWidth * tickFraction;
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

'''
java = java[:method_start] + new_method + java[method_end:]

required_fragments = [
    'new String[]{"0.25", "0.5", "1", "2", "4", "8", "10.8"}',
    'new String[]{"−32.4", "−16", "−8", "−4", "−2", "−1", "−0.5", "−0.25"}',
    'negative ? redTickDisplayValues : greenTickDisplayValues',
    'float greenAxisLabelY',
    'float redAxisLabelY',
]
for fragment in required_fragments:
    if fragment not in java:
        raise SystemExit(f"Falta validación v4.5: {fragment}")
if 'new String[]{"0",' in java or ', "0"}' in java:
    raise SystemExit("Quedó una marca cero en el eje logarítmico")

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
old_application_id = "applicationId 'com.example.accelledsinertial.gpsonly.v44.logaxis'"
old_version = "versionName '4.4-gps-eje-logaritmico-corregido'"
if gradle.count(old_application_id) != 1 or gradle.count(old_version) != 1:
    raise SystemExit("No se encontró exactamente la configuración de versión 4.4")
gradle = gradle.replace(
    old_application_id,
    "applicationId 'com.example.accelledsinertial.gpsonly.v45.permanentlogmarks'",
    1,
)
gradle = gradle.replace(
    old_version,
    "versionName '4.5-gps-marcas-log-permanentes'",
    1,
)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

print("Marcas logarítmicas permanentes v4.5 aplicadas y validadas")
