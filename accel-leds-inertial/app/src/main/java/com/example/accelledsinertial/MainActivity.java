package com.example.accelledsinertial;

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
    private static final int REQ_LOCATION = 1001;
    private static final double MIN_SPEED = 5.0;
    private static final double MIN_AUTO_GAP = 25.0;
    private static final double ACTIVATION_GAP = 30.0;
    private static final double MAX_GAP = 450.0;
    private static final double FULL_OPEN_TOLERANCE = 5.0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private Switch enabledSwitch;
    private Switch testSwitch;
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
    private double referenceSpeed;
    private double referenceGap;
    private double targetGap;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            regulate();
            handler.postDelayed(this, 300L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        buildUi();
        handler.post(loop);
        startGps();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(244, 247, 250));
        scroll.addView(root);

        TextView title = text("Ventana Adaptativa GPS", 27, Color.rgb(20, 38, 58));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = text("GPS real y modo de prueba", 15, Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(12));
        root.addView(subtitle);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Activar control adaptativo");
        enabledSwitch.setTextSize(18);
        enabledSwitch.setChecked(false);
        root.addView(enabledSwitch);

        testSwitch = new Switch(this);
        testSwitch.setText("Modo de prueba: velocidad simulada");
        testSwitch.setTextSize(16);
        root.addView(testSwitch);

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
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, dp(260));
        vp.setMargins(0, dp(10), 0, dp(8));
        root.addView(windowView, vp);

        gapText = text("Apertura: 0 mm", 26, Color.rgb(17, 87, 138));
        gapText.setGravity(Gravity.CENTER);
        root.addView(gapText);
        root.addView(text("Control manual de apertura", 14, Color.DKGRAY));

        gapSeek = new SeekBar(this);
        gapSeek.setMax((int) MAX_GAP);
        root.addView(gapSeek);

        stateText = text("Función apagada por defecto.", 16, Color.rgb(25, 45, 66));
        stateText.setPadding(dp(14), dp(14), dp(14), dp(14));
        stateText.setBackgroundColor(Color.rgb(225, 234, 242));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(14), 0, dp(8));
        root.addView(stateText, sp);

        referenceText = text("Referencia: ninguna", 14, Color.DKGRAY);
        root.addView(referenceText);

        TextView rules = text(
                "Al activar la función, la apertura actual se toma inmediatamente como referencia válida. " +
                "Si el vehículo está detenido, esa apertura queda pendiente hasta superar 5 km/h. " +
                "Mover la ventana manualmente solo pausa el control durante el movimiento y al soltarla crea una nueva referencia. " +
                "El mínimo automático es 25 mm; una apertura total en parado no define referencia.",
                14, Color.DKGRAY);
        rules.setPadding(0, dp(10), 0, dp(20));
        root.addView(rules);

        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                enabled = checked;
                if (enabled) {
                    activateFromCurrentOpening();
                    if (!testMode) startGps();
                } else {
                    clearReference();
                    setState("Función apagada. Control completamente manual.");
                }
                refresh();
            }
        });

        testSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                testMode = checked;
                speedSeek.setVisibility(testMode ? View.VISIBLE : View.GONE);
                filteredGpsKmh = 0;
                if (testMode) {
                    stopGps();
                    speedKmh = speedSeek.getProgress();
                    sourceText.setText("Fuente: velocidad de prueba");
                } else {
                    speedKmh = 0;
                    sourceText.setText("Fuente: GPS");
                    startGps();
                }
                refresh();
            }
        });

        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (testMode) {
                    speedKmh = value;
                    refresh();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        gapSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                gapMm = value;
                refresh();
                if (fromUser && manualMoving) {
                    setState("Movimiento manual: automatización suspendida temporalmente.");
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                if (programmaticGapChange) return;
                manualMoving = true;
                setState("Movimiento manual: automatización suspendida temporalmente.");
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (programmaticGapChange) return;
                manualMoving = false;
                finishManualMovement();
            }
        });

        setContentView(scroll);
        refresh();
    }

    private void activateFromCurrentOpening() {
        if (gapMm >= MAX_GAP - FULL_OPEN_TOLERANCE && speedKmh <= MIN_SPEED) {
            clearReference();
            setState("Función habilitada, pero una apertura total en parado no define referencia.");
            return;
        }

        if (gapMm < MIN_AUTO_GAP) {
            clearReference();
            setState("Función habilitada. La apertura actual es menor al mínimo automático de 25 mm.");
            return;
        }

        if (speedKmh > MIN_SPEED) {
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
        } else if (speedKmh <= MIN_SPEED) {
            if (gapMm >= MAX_GAP - FULL_OPEN_TOLERANCE) {
                clearReference();
                setState("Ventana totalmente abierta en parado: referencia eliminada. La función sigue habilitada.");
            } else if (gapMm >= ACTIVATION_GAP) {
                setPendingReference(gapMm);
                setState("Ajuste manual terminado. El control se reactivará al superar 5 km/h.");
            } else {
                clearReference();
                setState("Función habilitada, pero la apertura debe ser de al menos 30 mm después de un ajuste manual.");
            }
        } else if (gapMm >= ACTIVATION_GAP) {
            captureReference();
            setState("Control reactivado con la nueva posición manual como referencia.");
        } else {
            clearReference();
            setState("Función habilitada y en espera: abre al menos 30 mm después del ajuste manual.");
        }
        refresh();
    }

    private void setPendingReference(double openingMm) {
        active = false;
        pending = true;
        referenceGap = openingMm;
        referenceSpeed = 0;
        targetGap = openingMm;
    }

    private void captureReference() {
        referenceSpeed = Math.max(speedKmh, MIN_SPEED);
        referenceGap = gapMm;
        targetGap = gapMm;
        active = true;
        pending = false;
    }

    private void clearReference() {
        active = false;
        pending = false;
        referenceSpeed = 0;
        referenceGap = 0;
        targetGap = gapMm;
    }

    private void regulate() {
        if (!enabled || manualMoving) {
            refresh();
            return;
        }

        if (pending && speedKmh > MIN_SPEED) {
            referenceSpeed = speedKmh;
            targetGap = referenceGap;
            active = true;
            pending = false;
            setState("Control iniciado con la apertura previamente seleccionada.");
        }

        if (!active) {
            refresh();
            return;
        }

        if (speedKmh <= MIN_SPEED) {
            setState("Control suspendido por baja velocidad. La referencia se conserva.");
            refresh();
            return;
        }

        targetGap = clamp(referenceGap * referenceSpeed / speedKmh, MIN_AUTO_GAP, MAX_GAP);
        double delta = targetGap - gapMm;
        if (Math.abs(delta) >= 1) {
            gapMm = clamp(gapMm + clamp(delta, -8, 8), MIN_AUTO_GAP, MAX_GAP);
            programmaticGapChange = true;
            gapSeek.setProgress((int) Math.round(gapMm));
            programmaticGapChange = false;
        }

        setState(String.format(Locale.getDefault(),
                "Regulando automáticamente. Objetivo: %.0f mm.", targetGap));
        refresh();
    }

    private void refresh() {
        speedText.setText(String.format(Locale.getDefault(), "%.1f km/h", speedKmh));
        gapText.setText(String.format(Locale.getDefault(), "Apertura: %.0f mm", gapMm));
        windowView.setGap(gapMm);

        if (pending) {
            referenceText.setText(String.format(Locale.getDefault(),
                    "Referencia pendiente: %.0f mm; velocidad al superar 5 km/h", referenceGap));
        } else if (active) {
            referenceText.setText(String.format(Locale.getDefault(),
                    "Referencia: %.0f mm a %.1f km/h", referenceGap, referenceSpeed));
        } else {
            referenceText.setText("Referencia: ninguna");
        }
    }

    private void startGps() {
        if (testMode || locationManager == null || gpsRunning) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            }
            return;
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this);
            gpsRunning = true;
        } catch (RuntimeException e) {
            setState("No se pudo iniciar el GPS. Usa el modo de prueba.");
        }
    }

    private void stopGps() {
        if (locationManager != null && gpsRunning) locationManager.removeUpdates(this);
        gpsRunning = false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startGps();
            } else {
                setState("Permiso GPS denegado. Usa el modo de prueba.");
            }
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (testMode) return;
        double raw = location.hasSpeed() ? Math.max(0, location.getSpeed() * 3.6) : 0;
        filteredGpsKmh = filteredGpsKmh == 0 ? raw : filteredGpsKmh * 0.75 + raw * 0.25;
        speedKmh = filteredGpsKmh;
        refresh();
    }

    @Override public void onProviderEnabled(String provider) { }

    @Override public void onProviderDisabled(String provider) {
        speedKmh = 0;
        setState("GPS desactivado. Control suspendido.");
        refresh();
    }

    @SuppressWarnings("deprecation")
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override protected void onResume() {
        super.onResume();
        if (!testMode) startGps();
    }

    @Override protected void onPause() {
        stopGps();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(loop);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class WindowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double gap;

        WindowView(Context context) {
            super(context);
        }

        void setGap(double value) {
            gap = clamp(value, 0, MAX_GAP);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float margin = 20;

            RectF frame = new RectF(margin, margin, width - margin, height - margin);
            paint.setColor(Color.rgb(34, 45, 57));
            canvas.drawRoundRect(frame, 24, 24, paint);

            RectF inside = new RectF(
                    frame.left + 12,
                    frame.top + 12,
                    frame.right - 12,
                    frame.bottom - 12);
            paint.setColor(Color.rgb(220, 232, 240));
            canvas.drawRoundRect(inside, 16, 16, paint);

            float openPixels = (float) (gap / MAX_GAP * inside.height());
            float glassTop = inside.top + openPixels;
            if (glassTop < inside.bottom) {
                paint.setColor(Color.rgb(105, 174, 208));
                canvas.drawRect(inside.left, glassTop, inside.right, inside.bottom, paint);
            }

            paint.setColor(Color.rgb(20, 38, 58));
            paint.setTextSize(34);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(
                    String.format(Locale.getDefault(), "%.0f mm", gap),
                    width / 2,
                    inside.top + Math.max(45, openPixels / 2),
                    paint);
        }
    }
}
