package com.jorge.ventanaadaptativa;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivity extends Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 1001;
    private static final double MIN_SPEED_KMH = 5.0;
    private static final double MIN_AUTOMATIC_GAP_MM = 25.0;
    private static final double MANUAL_REFERENCE_GAP_MM = 30.0;
    private static final double MAX_GAP_MM = 450.0;
    private static final double FULL_OPEN_TOLERANCE_MM = 5.0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private Switch enabledSwitch;
    private Switch testModeSwitch;
    private SeekBar gapSeek;
    private SeekBar speedSeek;
    private TextView speedText;
    private TextView gapText;
    private TextView stateText;
    private TextView referenceText;
    private TextView sourceText;
    private WindowView windowView;

    private boolean enabled;
    private boolean testMode;
    private boolean manualMoving;
    private boolean active;
    private boolean pending;
    private boolean gpsRunning;
    private boolean permissionRequested;
    private boolean programmaticGapChange;

    private double speedKmh;
    private double filteredGpsKmh;
    private double gapMm;
    private double referenceSpeedKmh;
    private double referenceGapMm;
    private double targetGapMm;

    private final Runnable controlLoop = new Runnable() {
        @Override
        public void run() {
            regulate();
            handler.postDelayed(this, 300L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        buildInterface();
        handler.post(controlLoop);
        startGps();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(244, 247, 250));
        scroll.addView(root);

        TextView title = text("Ventana Adaptativa GPS", 27, Color.rgb(20, 38, 58));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = text("Simulador con velocidad GPS y modo de prueba", 15, Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Activar control adaptativo");
        enabledSwitch.setTextSize(18);
        enabledSwitch.setChecked(false);
        root.addView(enabledSwitch);

        testModeSwitch = new Switch(this);
        testModeSwitch.setText("Modo de prueba: velocidad simulada");
        testModeSwitch.setTextSize(16);
        root.addView(testModeSwitch);

        sourceText = text("Fuente: GPS", 14, Color.DKGRAY);
        root.addView(sourceText);

        speedText = text("0.0 km/h", 30, Color.rgb(17, 87, 138));
        speedText.setGravity(Gravity.CENTER);
        root.addView(speedText);

        speedSeek = new SeekBar(this);
        speedSeek.setMax(160);
        speedSeek.setVisibility(View.GONE);
        root.addView(speedSeek);

        windowView = new WindowView(this);
        LinearLayout.LayoutParams windowParams = new LinearLayout.LayoutParams(-1, dp(260));
        windowParams.setMargins(0, dp(10), 0, dp(8));
        root.addView(windowView, windowParams);

        gapText = text("Apertura: 0 mm", 26, Color.rgb(17, 87, 138));
        gapText.setGravity(Gravity.CENTER);
        root.addView(gapText);
        root.addView(text("Control manual de apertura", 14, Color.DKGRAY));

        gapSeek = new SeekBar(this);
        gapSeek.setMax((int) MAX_GAP_MM);
        root.addView(gapSeek);

        stateText = text("Función apagada por defecto.", 16, Color.rgb(25, 45, 66));
        stateText.setPadding(dp(14), dp(14), dp(14), dp(14));
        stateText.setBackgroundColor(Color.rgb(225, 234, 242));
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
        stateParams.setMargins(0, dp(14), 0, dp(8));
        root.addView(stateText, stateParams);

        referenceText = text("Referencia: ninguna", 14, Color.DKGRAY);
        root.addView(referenceText);

        TextView rules = text(
                "Al activar, la apertura actual se toma como referencia. " +
                "Si el vehículo está detenido, la velocidad se fija al superar 5 km/h. " +
                "Mover manualmente la ventana solo suspende el control durante el movimiento. " +
                "El mínimo automático es 25 mm y una apertura total en parado no crea referencia.",
                14,
                Color.DKGRAY);
        rules.setPadding(0, dp(10), 0, dp(20));
        root.addView(rules);

        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                enabled = checked;
                if (enabled) {
                    activateFromCurrentOpening();
                    if (!testMode) {
                        startGps();
                    }
                } else {
                    clearReference();
                    setState("Función apagada. Control completamente manual.");
                }
                refresh();
            }
        });

        testModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                testMode = checked;
                speedSeek.setVisibility(testMode ? View.VISIBLE : View.GONE);
                filteredGpsKmh = 0.0;
                if (testMode) {
                    stopGps();
                    speedKmh = speedSeek.getProgress();
                    sourceText.setText("Fuente: velocidad de prueba");
                } else {
                    speedKmh = 0.0;
                    sourceText.setText("Fuente: GPS");
                    startGps();
                }
                refresh();
            }
        });

        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (testMode) {
                    speedKmh = value;
                    refresh();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        gapSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                gapMm = value;
                refresh();
                if (fromUser && manualMoving) {
                    setState("Movimiento manual: automatización suspendida temporalmente.");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                if (programmaticGapChange) {
                    return;
                }
                manualMoving = true;
                setState("Movimiento manual: automatización suspendida temporalmente.");
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (programmaticGapChange) {
                    return;
                }
                manualMoving = false;
                finishManualMovement();
            }
        });

        setContentView(scroll);
        refresh();
    }

    private void activateFromCurrentOpening() {
        if (isFullyOpenWhileStopped()) {
            clearReference();
            setState("Función habilitada, pero una apertura total en parado no define referencia.");
            return;
        }

        if (gapMm < MIN_AUTOMATIC_GAP_MM) {
            clearReference();
            setState("Función habilitada. La apertura actual es menor al mínimo automático de 25 mm.");
            return;
        }

        if (speedKmh > MIN_SPEED_KMH) {
            captureReference();
            setState("Control activado con la apertura y velocidad actuales como referencia.");
        } else {
            setPendingReference(gapMm);
            setState("Control activado con la apertura actual. La velocidad se fijará al superar 5 km/h.");
        }
    }

    private void finishManualMovement() {
        if (!enabled) {
            clearReference();
            setState("Ajuste manual terminado. La función está apagada.");
        } else if (isFullyOpenWhileStopped()) {
            clearReference();
            setState("Ventana totalmente abierta en parado: referencia eliminada. La función sigue habilitada.");
        } else if (speedKmh <= MIN_SPEED_KMH) {
            if (gapMm >= MANUAL_REFERENCE_GAP_MM) {
                setPendingReference(gapMm);
                setState("Ajuste manual terminado. El control se reactivará al superar 5 km/h.");
            } else {
                clearReference();
                setState("Función habilitada, pero abre al menos 30 mm después del ajuste manual.");
            }
        } else if (gapMm >= MANUAL_REFERENCE_GAP_MM) {
            captureReference();
            setState("Control reactivado con la nueva posición manual como referencia.");
        } else {
            clearReference();
            setState("Función habilitada y en espera: abre al menos 30 mm después del ajuste manual.");
        }
        refresh();
    }

    private boolean isFullyOpenWhileStopped() {
        return gapMm >= MAX_GAP_MM - FULL_OPEN_TOLERANCE_MM && speedKmh <= MIN_SPEED_KMH;
    }

    private void setPendingReference(double openingMm) {
        active = false;
        pending = true;
        referenceGapMm = openingMm;
        referenceSpeedKmh = 0.0;
        targetGapMm = openingMm;
    }

    private void captureReference() {
        referenceSpeedKmh = Math.max(speedKmh, MIN_SPEED_KMH);
        referenceGapMm = gapMm;
        targetGapMm = gapMm;
        active = true;
        pending = false;
    }

    private void clearReference() {
        active = false;
        pending = false;
        referenceSpeedKmh = 0.0;
        referenceGapMm = 0.0;
        targetGapMm = gapMm;
    }

    private void regulate() {
        if (!enabled || manualMoving) {
            refresh();
            return;
        }

        if (pending && speedKmh > MIN_SPEED_KMH) {
            referenceSpeedKmh = speedKmh;
            targetGapMm = referenceGapMm;
            active = true;
            pending = false;
            setState("Control iniciado con la apertura previamente seleccionada.");
        }

        if (!active) {
            refresh();
            return;
        }

        if (speedKmh <= MIN_SPEED_KMH) {
            setState("Control suspendido por baja velocidad. La referencia se conserva.");
            refresh();
            return;
        }

        targetGapMm = clamp(
                referenceGapMm * referenceSpeedKmh / speedKmh,
                MIN_AUTOMATIC_GAP_MM,
                MAX_GAP_MM);

        double difference = targetGapMm - gapMm;
        if (Math.abs(difference) >= 1.0) {
            gapMm = clamp(
                    gapMm + clamp(difference, -8.0, 8.0),
                    MIN_AUTOMATIC_GAP_MM,
                    MAX_GAP_MM);
            programmaticGapChange = true;
            gapSeek.setProgress((int) Math.round(gapMm));
            programmaticGapChange = false;
        }

        setState(String.format(
                Locale.getDefault(),
                "Regulando automáticamente. Objetivo: %.0f mm.",
                targetGapMm));
        refresh();
    }

    private void refresh() {
        speedText.setText(String.format(Locale.getDefault(), "%.1f km/h", speedKmh));
        gapText.setText(String.format(Locale.getDefault(), "Apertura: %.0f mm", gapMm));
        windowView.setGap(gapMm);

        if (pending) {
            referenceText.setText(String.format(
                    Locale.getDefault(),
                    "Referencia pendiente: %.0f mm; velocidad al superar 5 km/h",
                    referenceGapMm));
        } else if (active) {
            referenceText.setText(String.format(
                    Locale.getDefault(),
                    "Referencia: %.0f mm a %.1f km/h",
                    referenceGapMm,
                    referenceSpeedKmh));
        } else {
            referenceText.setText("Referencia: ninguna");
        }
    }

    private void startGps() {
        if (testMode || locationManager == null || gpsRunning) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true;
                requestPermissions(
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        LOCATION_REQUEST);
            }
            return;
        }

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.0f,
                    this);
            gpsRunning = true;
        } catch (RuntimeException error) {
            setState("No se pudo iniciar el GPS. Usa el modo de prueba.");
        }
    }

    private void stopGps() {
        if (locationManager != null && gpsRunning) {
            locationManager.removeUpdates(this);
        }
        gpsRunning = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startGps();
            } else {
                setState("Permiso GPS denegado. Usa el modo de prueba.");
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (testMode) {
            return;
        }
        double rawSpeed = location.hasSpeed() ? Math.max(0.0, location.getSpeed() * 3.6) : 0.0;
        filteredGpsKmh = filteredGpsKmh == 0.0
                ? rawSpeed
                : filteredGpsKmh * 0.75 + rawSpeed * 0.25;
        speedKmh = filteredGpsKmh;
        refresh();
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        speedKmh = 0.0;
        setState("GPS desactivado. Control suspendido.");
        refresh();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!testMode) {
            startGps();
        }
    }

    @Override
    protected void onPause() {
        stopGps();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(controlLoop);
        stopGps();
        super.onDestroy();
    }

    private void setState(String value) {
        stateText.setText(value);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class WindowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double gapMm;

        WindowView(Context context) {
            super(context);
        }

        void setGap(double value) {
            gapMm = clamp(value, 0.0, MAX_GAP_MM);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float margin = 20.0f;

            RectF frame = new RectF(margin, margin, width - margin, height - margin);
            paint.setColor(Color.rgb(34, 45, 57));
            canvas.drawRoundRect(frame, 24.0f, 24.0f, paint);

            RectF inside = new RectF(
                    frame.left + 12.0f,
                    frame.top + 12.0f,
                    frame.right - 12.0f,
                    frame.bottom - 12.0f);
            paint.setColor(Color.rgb(220, 232, 240));
            canvas.drawRoundRect(inside, 16.0f, 16.0f, paint);

            float openPixels = (float) (gapMm / MAX_GAP_MM * inside.height());
            float glassTop = inside.top + openPixels;
            if (glassTop < inside.bottom) {
                paint.setColor(Color.rgb(105, 174, 208));
                canvas.drawRect(inside.left, glassTop, inside.right, inside.bottom, paint);
            }

            paint.setColor(Color.rgb(20, 38, 58));
            paint.setTextSize(34.0f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(
                    String.format(Locale.getDefault(), "%.0f mm", gapMm),
                    width / 2.0f,
                    inside.top + Math.max(45.0f, openPixels / 2.0f),
                    paint);
        }
    }
}
