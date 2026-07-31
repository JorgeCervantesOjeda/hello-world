package com.jorge.ventanaadaptativagps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final float MAX_OPENING_MM = 450f;
    private static final float MIN_AUTO_GAP_MM = 25f;
    private static final float ACTIVATION_OPENING_MM = 30f;
    private static final float MIN_VALID_SPEED_KMH = 5f;
    private static final float FULL_OPEN_THRESHOLD_MM = 445f;
    private static final long GPS_STALE_MS = 6000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private Switch enabledSwitch;
    private Switch testModeSwitch;
    private SeekBar openingSeek;
    private SeekBar testSpeedSeek;
    private LinearLayout testSpeedPanel;
    private TextView speedText;
    private TextView openingText;
    private TextView stateText;
    private TextView referenceText;
    private TextView sourceText;
    private TextView formulaText;
    private WindowView windowView;

    private float openingMm = 0f;
    private float filteredGpsSpeedKmh = 0f;
    private float referenceSpeedKmh = 0f;
    private float referenceOpeningMm = 0f;
    private float manualStartOpeningMm = 0f;
    private boolean referenceActive = false;
    private boolean programmaticOpeningChange = false;
    private long lastGpsFixMs = 0L;

    private final Runnable controlLoop = new Runnable() {
        @Override
        public void run() {
            updateAutomaticControl();
            refreshUi();
            handler.postDelayed(this, 200L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        setContentView(buildUi());
        enabledSwitch.setChecked(false);
        setState("Apagada por defecto");
        handler.post(controlLoop);
        requestLocationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startGpsUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGpsUpdates();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(controlLoop);
        stopGpsUpdates();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(241, 245, 249));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Ventana Adaptativa GPS", 26, true);
        title.setTextColor(Color.rgb(15, 23, 42));
        root.addView(title);

        TextView subtitle = text("Simulación de la función de software para automóviles", 14, false);
        subtitle.setTextColor(Color.rgb(71, 85, 105));
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        LinearLayout controlsCard = card();
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Activar regulación adaptativa");
        enabledSwitch.setTextSize(17f);
        enabledSwitch.setPadding(0, dp(4), 0, dp(8));
        controlsCard.addView(enabledSwitch);

        testModeSwitch = new Switch(this);
        testModeSwitch.setText("Modo de prueba sin conducir");
        testModeSwitch.setTextSize(16f);
        controlsCard.addView(testModeSwitch);

        testSpeedPanel = new LinearLayout(this);
        testSpeedPanel.setOrientation(LinearLayout.VERTICAL);
        testSpeedPanel.setPadding(0, dp(12), 0, 0);
        TextView testLabel = text("Velocidad simulada", 14, true);
        testSpeedPanel.addView(testLabel);
        testSpeedSeek = new SeekBar(this);
        testSpeedSeek.setMax(160);
        testSpeedSeek.setProgress(0);
        testSpeedPanel.addView(testSpeedSeek);
        testSpeedPanel.setVisibility(View.GONE);
        controlsCard.addView(testSpeedPanel);
        root.addView(controlsCard);

        LinearLayout metricsCard = card();
        speedText = metric("0.0 km/h", "Velocidad");
        openingText = metric("0 mm", "Apertura simulada");
        stateText = text("Estado: apagada", 16, true);
        stateText.setTextColor(Color.rgb(30, 64, 175));
        stateText.setPadding(0, dp(10), 0, dp(4));
        referenceText = text("Referencia: ninguna", 14, false);
        sourceText = text("Fuente: esperando GPS", 13, false);
        sourceText.setTextColor(Color.rgb(71, 85, 105));
        metricsCard.addView(speedText);
        metricsCard.addView(openingText);
        metricsCard.addView(stateText);
        metricsCard.addView(referenceText);
        metricsCard.addView(sourceText);
        root.addView(metricsCard);

        windowView = new WindowView(this);
        root.addView(windowView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));

        LinearLayout manualCard = card();
        TextView manualTitle = text("Control manual de la ventana", 17, true);
        manualCard.addView(manualTitle);
        TextView manualHelp = text(
                "Arrastra para abrir o subir. Al subir manualmente, la automatización se cancela de inmediato.",
                13, false);
        manualHelp.setTextColor(Color.rgb(71, 85, 105));
        manualHelp.setPadding(0, dp(3), 0, dp(8));
        manualCard.addView(manualHelp);

        openingSeek = new SeekBar(this);
        openingSeek.setMax((int) MAX_OPENING_MM);
        openingSeek.setProgress(0);
        manualCard.addView(openingSeek);

        LinearLayout buttonsRow1 = horizontalRow();
        Button openButton = actionButton("Abrir +25 mm");
        Button upButton = actionButton("Subir −25 mm");
        buttonsRow1.addView(openButton, weightedParams());
        buttonsRow1.addView(upButton, weightedParams());
        manualCard.addView(buttonsRow1);

        LinearLayout buttonsRow2 = horizontalRow();
        Button fullOpenButton = actionButton("Abrir totalmente");
        Button closeButton = actionButton("Cerrar totalmente");
        buttonsRow2.addView(fullOpenButton, weightedParams());
        buttonsRow2.addView(closeButton, weightedParams());
        manualCard.addView(buttonsRow2);
        root.addView(manualCard);

        LinearLayout logicCard = card();
        TextView logicTitle = text("Reglas simuladas", 17, true);
        logicCard.addView(logicTitle);
        formulaText = text(
                "• Se activa al abrir ≥ 30 mm circulando a más de 5 km/h.\n" +
                "• Objetivo = apertura de referencia × velocidad de referencia / velocidad actual.\n" +
                "• Cierre automático limitado a una ranura mínima de 25 mm.\n" +
                "• La subida manual siempre manda y puede cerrar por completo.\n" +
                "• Abrir totalmente a velocidad cero elimina la referencia.\n" +
                "• Al detenerse, la regulación se suspende sin mover la ventana.",
                14, false);
        formulaText.setLineSpacing(0f, 1.2f);
        formulaText.setPadding(0, dp(6), 0, 0);
        logicCard.addView(formulaText);
        root.addView(logicCard);

        TextView disclaimer = text(
                "Prototipo de simulación. No controla ningún sistema real del automóvil.",
                12, false);
        disclaimer.setTextColor(Color.rgb(100, 116, 139));
        disclaimer.setGravity(Gravity.CENTER);
        disclaimer.setPadding(dp(8), dp(8), dp(8), 0);
        root.addView(disclaimer);

        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                referenceActive = false;
                if (isChecked) {
                    setState("Habilitada · esperando apertura manual válida");
                    startGpsUpdates();
                } else {
                    setState("Apagada");
                }
                refreshUi();
            }
        });

        testModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                testSpeedPanel.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                referenceActive = false;
                setState(isChecked
                        ? "Modo de prueba · esperando apertura manual"
                        : "GPS real · esperando apertura manual");
                if (isChecked) {
                    stopGpsUpdates();
                } else {
                    startGpsUpdates();
                }
                refreshUi();
            }
        });

        testSpeedSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                refreshUi();
            }
        });

        openingSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                openingMm = progress;
                windowView.setOpeningMm(openingMm);
                refreshUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (!programmaticOpeningChange) {
                    manualStartOpeningMm = openingMm;
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!programmaticOpeningChange) {
                    handleManualMove(manualStartOpeningMm, openingMm);
                }
            }
        });

        openButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyManualOpening(Math.min(MAX_OPENING_MM, openingMm + 25f));
            }
        });

        upButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyManualOpening(Math.max(0f, openingMm - 25f));
            }
        });

        fullOpenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyManualOpening(MAX_OPENING_MM);
            }
        });

        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyManualOpening(0f);
            }
        });

        return scrollView;
    }

    private void applyManualOpening(float newOpeningMm) {
        float oldOpening = openingMm;
        setOpeningProgrammatically(newOpeningMm);
        handleManualMove(oldOpening, newOpeningMm);
    }

    private void handleManualMove(float oldOpeningMm, float newOpeningMm) {
        float delta = newOpeningMm - oldOpeningMm;
        float speed = currentSpeedKmh();

        if (delta < -0.5f) {
            referenceActive = false;
            referenceSpeedKmh = 0f;
            referenceOpeningMm = 0f;
            setState("Cancelada por subida manual · control total del conductor");
            return;
        }

        if (delta <= 0.5f) {
            return;
        }

        if (!enabledSwitch.isChecked()) {
            setState("Apagada · apertura manual normal");
            return;
        }

        if (speed <= MIN_VALID_SPEED_KMH) {
            referenceActive = false;
            if (newOpeningMm >= FULL_OPEN_THRESHOLD_MM) {
                setState("Sin regulación · apertura total a velocidad cero");
            } else {
                setState("Sin referencia · ajusta la apertura durante la marcha");
            }
            return;
        }

        if (newOpeningMm < ACTIVATION_OPENING_MM) {
            referenceActive = false;
            setState("En espera · abre al menos 30 mm");
            return;
        }

        referenceSpeedKmh = speed;
        referenceOpeningMm = newOpeningMm;
        referenceActive = true;
        setState("Referencia capturada · regulación activa");
    }

    private void updateAutomaticControl() {
        if (!enabledSwitch.isChecked() || !referenceActive) {
            return;
        }

        float speed = currentSpeedKmh();
        if (speed <= MIN_VALID_SPEED_KMH) {
            setState("Suspendida a baja velocidad · posición conservada");
            return;
        }

        float target = referenceOpeningMm * referenceSpeedKmh / speed;
        target = Math.max(MIN_AUTO_GAP_MM, Math.min(MAX_OPENING_MM, target));

        float error = target - openingMm;
        if (Math.abs(error) < 2f) {
            setState("Regulando · objetivo alcanzado");
            return;
        }

        float step = Math.max(-8f, Math.min(8f, error));
        setOpeningProgrammatically(openingMm + step);
        setState(target <= MIN_AUTO_GAP_MM + 0.5f
                ? "Regulando · límite automático de 25 mm"
                : "Regulando automáticamente");
    }

    private void setOpeningProgrammatically(float valueMm) {
        openingMm = Math.max(0f, Math.min(MAX_OPENING_MM, valueMm));
        programmaticOpeningChange = true;
        openingSeek.setProgress(Math.round(openingMm));
        programmaticOpeningChange = false;
        windowView.setOpeningMm(openingMm);
    }

    private float currentSpeedKmh() {
        if (testModeSwitch != null && testModeSwitch.isChecked()) {
            return testSpeedSeek == null ? 0f : testSpeedSeek.getProgress();
        }
        if (System.currentTimeMillis() - lastGpsFixMs > GPS_STALE_MS) {
            return 0f;
        }
        return filteredGpsSpeedKmh;
    }

    private void refreshUi() {
        if (speedText == null) {
            return;
        }
        float speed = currentSpeedKmh();
        speedText.setText(String.format(Locale.getDefault(), "%.1f km/h\nVelocidad", speed));
        openingText.setText(String.format(Locale.getDefault(), "%.0f mm\nApertura simulada", openingMm));
        referenceText.setText(referenceActive
                ? String.format(Locale.getDefault(),
                    "Referencia: %.0f mm a %.1f km/h",
                    referenceOpeningMm, referenceSpeedKmh)
                : "Referencia: ninguna");

        if (testModeSwitch.isChecked()) {
            sourceText.setText("Fuente: control de velocidad de prueba");
        } else if (lastGpsFixMs == 0L) {
            sourceText.setText("Fuente: esperando una lectura GPS");
        } else if (System.currentTimeMillis() - lastGpsFixMs > GPS_STALE_MS) {
            sourceText.setText("Fuente: señal GPS temporalmente no disponible");
        }
        windowView.setOpeningMm(openingMm);
    }

    private void setState(String state) {
        if (stateText != null) {
            stateText.setText("Estado: " + state);
        }
    }

    private void requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        }
    }

    private void startGpsUpdates() {
        if (testModeSwitch != null && testModeSwitch.isChecked()) {
            return;
        }
        if (locationManager == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500L, 0f, this);
        } catch (Exception error) {
            if (sourceText != null) {
                sourceText.setText("Fuente: no se pudo iniciar el GPS");
            }
        }
    }

    private void stopGpsUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        float rawKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        if (lastGpsFixMs == 0L) {
            filteredGpsSpeedKmh = rawKmh;
        } else {
            filteredGpsSpeedKmh = filteredGpsSpeedKmh * 0.72f + rawKmh * 0.28f;
        }
        lastGpsFixMs = System.currentTimeMillis();
        if (sourceText != null) {
            sourceText.setText(String.format(Locale.getDefault(),
                    "Fuente: GPS · precisión ±%.0f m",
                    location.hasAccuracy() ? location.getAccuracy() : 0f));
        }
        refreshUi();
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (sourceText != null) {
            sourceText.setText("Fuente: GPS activado · esperando lectura");
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        lastGpsFixMs = 0L;
        filteredGpsSpeedKmh = 0f;
        if (sourceText != null) {
            sourceText.setText("Fuente: GPS desactivado en el teléfono");
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Conservado por compatibilidad con Android 8.
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startGpsUpdates();
            } else {
                Toast.makeText(this,
                        "Sin permiso de ubicación puedes usar el modo de prueba.",
                        Toast.LENGTH_LONG).show();
                if (sourceText != null) {
                    sourceText.setText("Fuente: permiso GPS denegado · usa modo de prueba");
                }
            }
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(226, 232, 240));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(params);
        card.setElevation(dp(2));
        return card;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams weightedParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private Button actionButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13f);
        return button;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(30, 41, 59));
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private TextView metric(String value, String label) {
        TextView view = text(value + "\n" + label, 21, true);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    private static final class WindowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float openingMm = 0f;

        WindowView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setOpeningMm(float valueMm) {
            openingMm = Math.max(0f, Math.min(MAX_OPENING_MM, valueMm));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float left = width * 0.14f;
            float right = width * 0.86f;
            float top = height * 0.12f;
            float bottom = height * 0.86f;
            RectF frame = new RectF(left, top, right, bottom);

            paint.setColor(Color.rgb(15, 23, 42));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(8f, width * 0.025f));
            canvas.drawRoundRect(frame, 24f, 24f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(186, 230, 253));
            canvas.drawRoundRect(new RectF(left + 7f, top + 7f, right - 7f, bottom - 7f),
                    18f, 18f, paint);

            float openFraction = openingMm / MAX_OPENING_MM;
            float glassTop = top + 7f + openFraction * (bottom - top - 14f);
            if (glassTop < bottom - 7f) {
                paint.setColor(Color.argb(205, 37, 99, 235));
                canvas.drawRect(left + 7f, glassTop, right - 7f, bottom - 7f, paint);
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f);
                canvas.drawLine(left + 10f, glassTop, right - 10f, glassTop, paint);
            }

            float minGapY = top + 7f + (MIN_AUTO_GAP_MM / MAX_OPENING_MM)
                    * (bottom - top - 14f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(Color.rgb(234, 88, 12));
            canvas.drawLine(left + 12f, minGapY, right - 12f, minGapY, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(15, 23, 42));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(26f, width * 0.06f));
            canvas.drawText(String.format(Locale.getDefault(), "%.0f mm", openingMm),
                    width / 2f, height * 0.96f, paint);

            paint.setTextSize(Math.max(15f, width * 0.035f));
            paint.setColor(Color.rgb(154, 52, 18));
            canvas.drawText("línea mínima automática: 25 mm",
                    width / 2f, Math.max(dpStatic(getContext(), 18), minGapY - 8f), paint);
        }

        private static int dpStatic(Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
