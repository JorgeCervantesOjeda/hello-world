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

public class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final double MIN_VALID_SPEED_KMH = 5.0;
    private static final double MIN_AUTOMATIC_GAP_MM = 25.0;
    private static final double ACTIVATION_GAP_MM = 30.0;
    private static final double MAX_GAP_MM = 450.0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Switch enabledSwitch;
    private Switch testModeSwitch;
    private SeekBar manualGapSeek;
    private SeekBar testSpeedSeek;
    private TextView speedText;
    private TextView gapText;
    private TextView stateText;
    private TextView referenceText;
    private TextView sourceText;
    private WindowView windowView;

    private LocationManager locationManager;
    private boolean locationUpdatesActive;
    private boolean featureEnabled;
    private boolean testMode;
    private boolean manualMoving;
    private boolean controlActive;
    private boolean pendingReference;

    private double currentSpeedKmh;
    private double filteredGpsSpeedKmh;
    private double currentGapMm;
    private double referenceSpeedKmh;
    private double referenceGapMm;
    private double currentTargetMm;

    private final Runnable controlLoop = new Runnable() {
        @Override
        public void run() {
            updateAutomaticControl();
            handler.postDelayed(this, 300L);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (testMode) {
                return;
            }
            double rawSpeed = location.hasSpeed() ? Math.max(0.0, location.getSpeed() * 3.6) : 0.0;
            if (filteredGpsSpeedKmh == 0.0) {
                filteredGpsSpeedKmh = rawSpeed;
            } else {
                filteredGpsSpeedKmh = filteredGpsSpeedKmh * 0.75 + rawSpeed * 0.25;
            }
            currentSpeedKmh = filteredGpsSpeedKmh;
            updateReadouts();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            setState("GPS disponible. La función permanece en espera hasta un ajuste manual válido.");
        }

        @Override
        public void onProviderDisabled(String provider) {
            currentSpeedKmh = 0.0;
            setState("GPS desactivado. El control automático está suspendido.");
            updateReadouts();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        buildInterface();
        handler.post(controlLoop);
        requestLocationAndStart();
    }

    private void buildInterface() {
        int padding = dp(18);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(244, 247, 250));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Ventana Adaptativa GPS");
        title.setTextSize(27f);
        title.setTextColor(Color.rgb(20, 38, 58));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Simulación de apertura automática según la velocidad del GPS");
        subtitle.setTextSize(15f);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 0, 0, dp(14));
        root.addView(subtitle, matchWrap());

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Activar control adaptativo");
        enabledSwitch.setTextSize(18f);
        enabledSwitch.setChecked(false);
        root.addView(enabledSwitch, matchWrap());

        testModeSwitch = new Switch(this);
        testModeSwitch.setText("Modo de prueba: velocidad simulada");
        testModeSwitch.setTextSize(16f);
        testModeSwitch.setChecked(false);
        root.addView(testModeSwitch, matchWrap());

        sourceText = makeInfoText("Fuente: GPS");
        root.addView(sourceText, matchWrap());

        speedText = makeValueText("0.0 km/h");
        root.addView(speedText, matchWrap());

        testSpeedSeek = new SeekBar(this);
        testSpeedSeek.setMax(160);
        testSpeedSeek.setProgress(0);
        testSpeedSeek.setVisibility(View.GONE);
        root.addView(testSpeedSeek, matchWrap());

        windowView = new WindowView(this);
        LinearLayout.LayoutParams windowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(270));
        windowParams.setMargins(0, dp(12), 0, dp(8));
        root.addView(windowView, windowParams);

        gapText = makeValueText("Apertura: 0 mm");
        root.addView(gapText, matchWrap());

        TextView manualLabel = makeInfoText("Control manual de la ventana");
        root.addView(manualLabel, matchWrap());

        manualGapSeek = new SeekBar(this);
        manualGapSeek.setMax((int) MAX_GAP_MM);
        manualGapSeek.setProgress(0);
        root.addView(manualGapSeek, matchWrap());

        stateText = new TextView(this);
        stateText.setTextSize(16f);
        stateText.setTextColor(Color.rgb(25, 45, 66));
        stateText.setPadding(dp(14), dp(14), dp(14), dp(14));
        stateText.setBackgroundColor(Color.rgb(225, 234, 242));
        stateText.setText("Función apagada por defecto.");
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.setMargins(0, dp(14), 0, dp(8));
        root.addView(stateText, stateParams);

        referenceText = makeInfoText("Referencia: ninguna");
        root.addView(referenceText, matchWrap());

        TextView rules = makeInfoText(
                "Reglas de esta versión:\n" +
                "• Mover manualmente la ventana solo pausa la automatización.\n" +
                "• Al soltar el control, la posición final se convierte en la nueva referencia si es válida.\n" +
                "• El cierre automático nunca baja de 25 mm.\n" +
                "• Abrir totalmente a velocidad cero elimina la referencia.\n" +
                "• La función continúa habilitada hasta que el usuario la apague.");
        rules.setPadding(0, dp(10), 0, dp(24));
        root.addView(rules, matchWrap());

        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                featureEnabled = isChecked;
                if (!featureEnabled) {
                    clearReference();
                    setState("Función apagada. La ventana queda completamente manual.");
                } else {
                    setState("Función habilitada y en espera de un ajuste manual válido.");
                    if (!testMode) {
                        requestLocationAndStart();
                    }
                }
                updateReadouts();
            }
        });

        testModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                testMode = isChecked;
                testSpeedSeek.setVisibility(testMode ? View.VISIBLE : View.GONE);
                filteredGpsSpeedKmh = 0.0;
                if (testMode) {
                    stopLocationUpdates();
                    currentSpeedKmh = testSpeedSeek.getProgress();
                    sourceText.setText("Fuente: velocidad de prueba");
                } else {
                    currentSpeedKmh = 0.0;
                    sourceText.setText("Fuente: GPS");
                    requestLocationAndStart();
                }
                updateReadouts();
            }
        });

        testSpeedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (testMode) {
                    currentSpeedKmh = progress;
                    updateReadouts();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        manualGapSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentGapMm = progress;
                windowView.setOpeningMm(currentGapMm);
                gapText.setText(String.format(Locale.getDefault(), "Apertura: %.0f mm", currentGapMm));
                if (fromUser && manualMoving) {
                    setState("Control manual en curso: automatización suspendida temporalmente.");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                manualMoving = true;
                setState("Control manual en curso: automatización suspendida temporalmente.");
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                manualMoving = false;
                handleManualMovementFinished();
            }
        });

        setContentView(scrollView);
        updateReadouts();
    }

    private void handleManualMovementFinished() {
        if (!featureEnabled) {
            clearReference();
            setState("Ajuste manual terminado. La función está apagada.");
            return;
        }

        if (currentSpeedKmh <= MIN_VALID_SPEED_KMH) {
            if (currentGapMm >= MAX_GAP_MM - 5.0) {
                clearReference();
                setState("Ventana totalmente abierta en parado: referencia eliminada. La función sigue habilitada.");
            } else if (currentGapMm >= ACTIVATION_GAP_MM) {
                controlActive = false;
                pendingReference = true;
                referenceGapMm = currentGapMm;
                referenceSpeedKmh = 0.0;
                setState("Ajuste manual terminado. Se reactivará al superar 5 km/h.");
            } else {
                clearReference();
                setState("Función habilitada, pero la apertura debe ser de al menos 30 mm.");
            }
        } else if (currentGapMm >= ACTIVATION_GAP_MM) {
            captureReference();
            setState("Control reactivado con la nueva posición manual como referencia.");
        } else {
            clearReference();
            setState("Función habilitada y en espera: abre al menos 30 mm para regular.");
        }
        updateReadouts();
    }

    private void captureReference() {
        referenceSpeedKmh = Math.max(currentSpeedKmh, MIN_VALID_SPEED_KMH);
        referenceGapMm = currentGapMm;
        currentTargetMm = currentGapMm;
        controlActive = true;
        pendingReference = false;
    }

    private void clearReference() {
        controlActive = false;
        pendingReference = false;
        referenceSpeedKmh = 0.0;
        referenceGapMm = 0.0;
        currentTargetMm = currentGapMm;
    }

    private void updateAutomaticControl() {
        if (!featureEnabled || manualMoving) {
            updateReadouts();
            return;
        }

        if (pendingReference && currentSpeedKmh > MIN_VALID_SPEED_KMH) {
            referenceSpeedKmh = currentSpeedKmh;
            currentTargetMm = referenceGapMm;
            controlActive = true;
            pendingReference = false;
            setState("Control reactivado al iniciar la marcha.");
        }

        if (!controlActive) {
            updateReadouts();
            return;
        }

        if (currentSpeedKmh <= MIN_VALID_SPEED_KMH) {
            setState("Control suspendido por baja velocidad. La referencia se conserva.");
            updateReadouts();
            return;
        }

        double calculated = referenceGapMm * referenceSpeedKmh / currentSpeedKmh;
        currentTargetMm = clamp(calculated, MIN_AUTOMATIC_GAP_MM, MAX_GAP_MM);
        double difference = currentTargetMm - currentGapMm;

        if (Math.abs(difference) >= 1.0) {
            double step = clamp(difference, -8.0, 8.0);
            currentGapMm = clamp(currentGapMm + step, MIN_AUTOMATIC_GAP_MM, MAX_GAP_MM);
            manualGapSeek.setProgress((int) Math.round(currentGapMm));
        }

        setState(String.format(Locale.getDefault(),
                "Regulando automáticamente. Objetivo actual: %.0f mm.", currentTargetMm));
        updateReadouts();
    }

    private void updateReadouts() {
        speedText.setText(String.format(Locale.getDefault(), "%.1f km/h", currentSpeedKmh));
        gapText.setText(String.format(Locale.getDefault(), "Apertura: %.0f mm", currentGapMm));
        windowView.setOpeningMm(currentGapMm);

        if (pendingReference) {
            referenceText.setText(String.format(Locale.getDefault(),
                    "Referencia pendiente: %.0f mm; se fijará al superar 5 km/h", referenceGapMm));
        } else if (controlActive) {
            referenceText.setText(String.format(Locale.getDefault(),
                    "Referencia: %.0f mm a %.1f km/h", referenceGapMm, referenceSpeedKmh));
        } else {
            referenceText.setText("Referencia: ninguna");
        }
    }

    private void requestLocationAndStart() {
        if (testMode || locationManager == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            return;
        }
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (testMode || locationUpdatesActive || locationManager == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.0f,
                    locationListener);
            locationUpdatesActive = true;
            sourceText.setText("Fuente: GPS");
        } catch (RuntimeException exception) {
            setState("No se pudo iniciar el GPS. Activa el modo de prueba para simular velocidad.");
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationUpdatesActive) {
            locationManager.removeUpdates(locationListener);
        }
        locationUpdatesActive = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                setState("Permiso de ubicación denegado. Usa el modo de prueba o concede el permiso GPS.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!testMode) {
            requestLocationAndStart();
        }
    }

    @Override
    protected void onPause() {
        stopLocationUpdates();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(controlLoop);
        stopLocationUpdates();
        super.onDestroy();
    }

    private void setState(String text) {
        stateText.setText(text);
    }

    private TextView makeValueText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(28f);
        view.setTextColor(Color.rgb(17, 87, 138));
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private TextView makeInfoText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        view.setTextColor(Color.DKGRAY);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class WindowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double openingMm;

        public WindowView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
        }

        public void setOpeningMm(double openingMm) {
            this.openingMm = clamp(openingMm, 0.0, MAX_GAP_MM);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float margin = 20f;
            RectF frame = new RectF(margin, margin, width - margin, height - margin);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(34, 45, 57));
            canvas.drawRoundRect(frame, 24f, 24f, paint);

            RectF inside = new RectF(frame.left + 12f, frame.top + 12f,
                    frame.right - 12f, frame.bottom - 12f);
            paint.setColor(Color.rgb(220, 232, 240));
            canvas.drawRoundRect(inside, 16f, 16f, paint);

            float usableHeight = inside.height();
            float openPixels = (float) (openingMm / MAX_GAP_MM * usableHeight);
            float glassTop = inside.top + openPixels;
            if (glassTop < inside.bottom) {
                RectF glass = new RectF(inside.left, glassTop, inside.right, inside.bottom);
                paint.setColor(Color.rgb(105, 174, 208));
                canvas.drawRect(glass, paint);
            }

            paint.setColor(Color.rgb(20, 38, 58));
            paint.setTextSize(34f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format(Locale.getDefault(), "%.0f mm", openingMm),
                    width / 2f, inside.top + Math.max(45f, openPixels / 2f), paint);
        }
    }
}
