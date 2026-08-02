from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")
gradle = GRADLE_PATH.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: se esperaba 1 coincidencia, encontradas {count}")
    return text.replace(old, new, 1)


java = replace_once(
    java,
    '''    private static final float VISUAL_DEAD_ZONE = 0.015f;

    private static final long GPS_SPEED_STALE_MS = 2500L;
''',
    '''    private static final float VISUAL_DEAD_ZONE = 0.015f;
    private static final float ACCELERATION_DISPLAY_FACTOR = 3.6f;

    private static final long GPS_SPEED_STALE_MS = 2500L;
''',
    "factor de conversión",
)

java = replace_once(
    java,
    '''            String accelerationText =
                    fresh
                            ? String.format(Locale.US, "%+.4f", gpsAccelerationFiltered)
                            : "---";
''',
    '''            String accelerationText =
                    fresh
                            ? String.format(
                                    Locale.US,
                                    "%+.4f",
                                    gpsAccelerationFiltered * ACCELERATION_DISPLAY_FACTOR)
                            : "---";
''',
    "lectura grande de aceleración",
)

java = replace_once(
    java,
    '''            canvas.drawText(
                    "m/s²",
                    (left + right) / 2f,
                    top + panelHeight * 0.395f,
                    paint);
''',
    '''            canvas.drawText(
                    "km/(h·s)",
                    (left + right) / 2f,
                    top + panelHeight * 0.395f,
                    paint);
''',
    "unidad principal",
)

java = replace_once(
    java,
    '''            float barLeft = left + (right - left) * 0.025f;
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
''',
    '''            float barLeft = left + (right - left) * 0.025f;
            float barRight = right - (right - left) * 0.025f;
            float barTop = top + panelHeight * 0.475f;
            float barBottom = top + panelHeight * 0.805f;
            float axisLabelY = top + panelHeight * 0.885f;
            drawAccelerationBar(
                    canvas,
                    barLeft,
                    barRight,
                    barTop,
                    barBottom,
                    axisLabelY,
                    fresh ? gpsAccelerationFiltered : 0f);
''',
    "barra y escala dinámica",
)

java = replace_once(
    java,
    '''                    status + " · zona neutra ±0.015",
''',
    '''                    status + " · zona neutra ±0.054 km/(h·s)",
''',
    "zona neutra convertida",
)

java = replace_once(
    java,
    '''                                    "%.3f m/s²",
                                    gpsAccelerationUncertainty);
''',
    '''                                    "%.3f km/(h·s)",
                                    gpsAccelerationUncertainty
                                            * ACCELERATION_DISPLAY_FACTOR);
''',
    "incertidumbre convertida",
)

java = replace_once(
    java,
    '''                            : String.format(Locale.US, "%+.4f m/s²", gpsAccelerationRaw);
''',
    '''                            : String.format(
                                    Locale.US,
                                    "%+.4f km/(h·s)",
                                    gpsAccelerationRaw * ACCELERATION_DISPLAY_FACTOR);
''',
    "aceleración cruda convertida",
)

java = replace_once(
    java,
    '''                                "Aceleración: cruda %s · filtrada %+.4f m/s² · incertidumbre %s",
                                rawAccelerationText,
                                gpsAccelerationFiltered,
                                accelerationUncertaintyText),
''',
    '''                                "Aceleración: cruda %s · filtrada %+.4f km/(h·s) · incertidumbre %s",
                                rawAccelerationText,
                                gpsAccelerationFiltered * ACCELERATION_DISPLAY_FACTOR,
                                accelerationUncertaintyText),
''',
    "diagnóstico filtrado convertido",
)

old_bar_start = '''        private void drawAccelerationBar(
                Canvas canvas,
                float left,
                float right,
                float top,
                float bottom,
                float value) {
'''
bar_start = java.find(old_bar_start)
bar_end_marker = '''        private String accelerationQualityText() {
'''
bar_end = java.find(bar_end_marker, bar_start)
if bar_start < 0 or bar_end < 0:
    raise SystemExit("No se encontró el método drawAccelerationBar")

new_bar = r'''        private void drawAccelerationBar(
                Canvas canvas,
                float left,
                float right,
                float top,
                float bottom,
                float axisLabelY,
                float value) {
            paint.setAntiAlias(false);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(24, 24, 24));
            canvas.drawRect(left, top, right, bottom, paint);

            float fraction = signedFillFraction(value);
            if (fraction <= 0f) {
                paint.setAntiAlias(true);
                return;
            }

            float pixels = (right - left) * fraction;
            boolean negative = value < -VISUAL_DEAD_ZONE;
            float activeLeft = negative ? right - pixels : left;
            float activeRight = negative ? right : left + pixels;
            int activeColor =
                    negative
                            ? Color.rgb(255, 35, 25)
                            : Color.rgb(0, 255, 70);

            paint.setColor(activeColor);
            canvas.drawRect(activeLeft, top, activeRight, bottom, paint);

            String[] tickLabels =
                    negative
                            ? new String[]{"−32.4", "−24.3", "−16.2", "−8.1", "0"}
                            : new String[]{"0", "+2.7", "+5.4", "+8.1", "+10.8"};

            paint.setStrokeWidth(Math.max(1f, getHeight() * 0.0022f));
            paint.setColor(Color.WHITE);
            paint.setAlpha(95);
            for (int i = 0; i < tickLabels.length; i++) {
                float x = left + (right - left) * i / (tickLabels.length - 1f);
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
            paint.setTextSize(getHeight() * 0.019f);
            paint.setColor(
                    negative
                            ? Color.rgb(255, 175, 170)
                            : Color.rgb(170, 255, 195));

            for (int i = 0; i < tickLabels.length; i++) {
                float x = left + (right - left) * i / (tickLabels.length - 1f);
                if (i == 0) {
                    paint.setTextAlign(Paint.Align.LEFT);
                } else if (i == tickLabels.length - 1) {
                    paint.setTextAlign(Paint.Align.RIGHT);
                } else {
                    paint.setTextAlign(Paint.Align.CENTER);
                }
                canvas.drawText(tickLabels[i], x, axisLabelY, paint);
            }

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

java = java[:bar_start] + new_bar + java[bar_end:]

gradle = replace_once(
    gradle,
    '''        applicationId 'com.example.accelledsinertial.gpsonly.v42.batterythermometer'
''',
    '''        applicationId 'com.example.accelledsinertial.gpsonly.v43.axisunits'
''',
    "applicationId v4.3",
)
gradle = replace_once(
    gradle,
    '''        versionName '4.2-gps-termometro-bateria'
''',
    '''        versionName '4.3-gps-eje-km-h-seg'
''',
    "versionName v4.3",
)

required_java_tokens = [
    'ACCELERATION_DISPLAY_FACTOR = 3.6f',
    '"km/(h·s)"',
    '"−32.4", "−24.3", "−16.2", "−8.1", "0"',
    '"0", "+2.7", "+5.4", "+8.1", "+10.8"',
    'axisLabelY',
    'value * ACCELERATION_DISPLAY_FACTOR',
    'zona neutra ±0.054 km/(h·s)',
]
for token in required_java_tokens:
    if token not in java:
        raise SystemExit(f"Validación fallida: falta {token}")

if "m/s²" in java:
    raise SystemExit("Quedaron unidades m/s² visibles en el código")

JAVA_PATH.write_text(java, encoding="utf-8")
GRADLE_PATH.write_text(gradle, encoding="utf-8")

print("Unidades y eje graduado v4.3 aplicados y validados")
