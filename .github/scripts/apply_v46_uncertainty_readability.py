from pathlib import Path

JAVA_PATH = Path("accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java")
GRADLE_PATH = Path("accel-leds-inertial/app/build.gradle")

java = JAVA_PATH.read_text(encoding="utf-8")

old_call = '''            float greenAxisLabelY = barTop - height * 0.010f;
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

            String status = fresh ? accelerationQualityText() : "SIN ESTIMACIÓN VÁLIDA";
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.022f);
            paint.setColor(fresh ? accelerationQualityColor() : Color.rgb(255, 190, 70));
'''
new_call = '''            float greenAxisLabelY = barTop - height * 0.018f;
            float redAxisLabelY = top + panelHeight * 0.900f;
            drawAccelerationBar(
                    canvas,
                    barLeft,
                    barRight,
                    barTop,
                    barBottom,
                    greenAxisLabelY,
                    redAxisLabelY,
                    fresh ? gpsAccelerationFiltered : 0f,
                    fresh ? gpsAccelerationUncertainty : Float.NaN);

            boolean uncertainSignal =
                    fresh
                            && !Float.isNaN(gpsAccelerationUncertainty)
                            && Math.abs(gpsAccelerationFiltered)
                                    <= gpsAccelerationUncertainty;
            String status =
                    !fresh
                            ? "SIN ESTIMACIÓN VÁLIDA"
                            : uncertainSignal
                                    ? "SEÑAL INCIERTA"
                                    : accelerationQualityText();
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(height * 0.022f);
            paint.setColor(
                    !fresh
                            ? Color.rgb(255, 190, 70)
                            : uncertainSignal
                                    ? Color.rgb(255, 190, 70)
                                    : accelerationQualityColor());
'''
if java.count(old_call) != 1:
    raise SystemExit(f"panel de aceleración: esperada 1 coincidencia, encontradas {java.count(old_call)}")
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
                float value,
                float uncertainty) {
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

            float[] greenXs = new float[greenTickLabels.length];
            for (int i = 0; i < greenTickLabels.length; i++) {
                float tickValue = greenTickDisplayValues[i] / ACCELERATION_DISPLAY_FACTOR;
                greenXs[i] = left + barWidth * signedFillFraction(tickValue);
            }
            drawNonOverlappingAxisLabels(
                    canvas,
                    greenTickLabels,
                    greenXs,
                    greenAxisLabelY,
                    left,
                    right,
                    Color.rgb(170, 255, 195));

            float[] redXs = new float[redTickLabels.length];
            for (int i = 0; i < redTickLabels.length; i++) {
                float tickValue = redTickDisplayValues[i] / ACCELERATION_DISPLAY_FACTOR;
                redXs[i] = right - barWidth * signedFillFraction(tickValue);
            }
            drawNonOverlappingAxisLabels(
                    canvas,
                    redTickLabels,
                    redXs,
                    redAxisLabelY,
                    left,
                    right,
                    Color.rgb(255, 175, 170));

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

            if (!Float.isNaN(uncertainty) && uncertainty > 0f) {
                float magnitude = Math.abs(value);
                float lowMagnitude = Math.max(VISUAL_DEAD_ZONE, magnitude - uncertainty);
                float maximum = negative ? RED_FULL_SCALE : GREEN_FULL_SCALE;
                float highMagnitude = Math.min(maximum, magnitude + uncertainty);
                float lowFraction =
                        lowMagnitude <= VISUAL_DEAD_ZONE
                                ? 0f
                                : signedFillFraction(negative ? -lowMagnitude : lowMagnitude);
                float highFraction =
                        signedFillFraction(negative ? -highMagnitude : highMagnitude);
                float bandLeft =
                        negative ? right - barWidth * highFraction : left + barWidth * lowFraction;
                float bandRight =
                        negative ? right - barWidth * lowFraction : left + barWidth * highFraction;
                paint.setColor(Color.argb(105, 255, 215, 60));
                canvas.drawRect(bandLeft, top, bandRight, bottom, paint);
            }

            float[] activeTickDisplayValues =
                    negative ? redTickDisplayValues : greenTickDisplayValues;
            float currentDisplayMagnitude =
                    Math.abs(value * ACCELERATION_DISPLAY_FACTOR);
            paint.setStrokeWidth(Math.max(1f, getHeight() * 0.0022f));
            for (float displayValue : activeTickDisplayValues) {
                float tickValue = displayValue / ACCELERATION_DISPLAY_FACTOR;
                float tickFraction = signedFillFraction(tickValue);
                float x = negative ? right - barWidth * tickFraction : left + barWidth * tickFraction;
                boolean reached = Math.abs(displayValue) <= currentDisplayMagnitude + 0.0001f;
                paint.setColor(reached ? Color.WHITE : Color.rgb(95, 100, 105));
                paint.setAlpha(reached ? 180 : 105);
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

        private void drawNonOverlappingAxisLabels(
                Canvas canvas,
                String[] labels,
                float[] positions,
                float baseline,
                float left,
                float right,
                int color) {
            if (labels.length == 0) return;

            float gap = Math.max(3f, getWidth() * 0.006f);
            float lastLabelLeft =
                    right - paint.measureText(labels[labels.length - 1]);
            float previousRight = left - gap;
            paint.setColor(color);

            for (int i = 0; i < labels.length; i++) {
                float textWidth = paint.measureText(labels[i]);
                float x = clamp(positions[i], left, right);
                float textLeft;
                float textRight;

                if (i == 0 || x <= left + (right - left) * 0.025f) {
                    paint.setTextAlign(Paint.Align.LEFT);
                    textLeft = x;
                    textRight = x + textWidth;
                } else if (i == labels.length - 1 || x >= right - (right - left) * 0.025f) {
                    paint.setTextAlign(Paint.Align.RIGHT);
                    textLeft = x - textWidth;
                    textRight = x;
                } else {
                    paint.setTextAlign(Paint.Align.CENTER);
                    textLeft = x - textWidth / 2f;
                    textRight = x + textWidth / 2f;
                }

                boolean endpoint = i == 0 || i == labels.length - 1;
                boolean fitsPrevious = textLeft >= previousRight + gap;
                boolean leavesLast =
                        i >= labels.length - 2 || textRight <= lastLabelLeft - gap;
                if (endpoint || (fitsPrevious && leavesLast)) {
                    canvas.drawText(labels[i], x, baseline, paint);
                    previousRight = textRight;
                }
            }
        }

'''
java = java[:method_start] + new_method + java[method_end:]

required = [
    "SEÑAL INCIERTA",
    "drawNonOverlappingAxisLabels",
    "Color.argb(105, 255, 215, 60)",
    "reached ? Color.WHITE : Color.rgb(95, 100, 105)",
    "barTop - height * 0.018f",
]
for fragment in required:
    if fragment not in java:
        raise SystemExit(f"Falta fragmento v4.6: {fragment}")
if "ESCALA LOGARÍTMICA VISUAL" in java:
    raise SystemExit("Se añadió por error la sugerencia 4")

JAVA_PATH.write_text(java, encoding="utf-8")

gradle = GRADLE_PATH.read_text(encoding="utf-8")
old_id = "applicationId 'com.example.accelledsinertial.gpsonly.v45.permanentlogmarks'"
old_version = "versionName '4.5-gps-marcas-log-permanentes'"
if gradle.count(old_id) != 1 or gradle.count(old_version) != 1:
    raise SystemExit("No se encontró la versión 4.5")
gradle = gradle.replace(
    old_id,
    "applicationId 'com.example.accelledsinertial.gpsonly.v46.uncertaintyreadability'",
    1,
)
gradle = gradle.replace(
    old_version,
    "versionName '4.6-gps-incertidumbre-legibilidad'",
    1,
)
GRADLE_PATH.write_text(gradle, encoding="utf-8")

print("Mejoras v4.6 aplicadas y validadas")
