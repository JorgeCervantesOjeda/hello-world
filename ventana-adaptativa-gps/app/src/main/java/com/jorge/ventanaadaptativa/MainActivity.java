package com.jorge.ventanaadaptativa;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.os.SystemClock;
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
    private static final double ZERO_SPEED_MAX_KMH = 0.5;
    private static final double MIN_AUTOMATIC_GAP_MM = 25.0;
    private static final double MANUAL_REFERENCE_GAP_MM = 30.0;
    private static final double MAX_GAP_MM = 450.0;
    private static final double FULL_OPEN_TOLERANCE_MM = 5.0;
    private static final double ARRIVAL_TOLERANCE_MM = 3.0;
    private static final double TARGET_RESTART_DIFFERENCE_MM = 5.0;
    private static final double MIN_LEARNING_DISTANCE_MM = 20.0;
    private static final double DEFAULT_FULL_TRAVEL_MS = 17000.0;
    private static final double TIME_MARGIN_FACTOR = 1.4;
    private static final long FIXED_TIME_MARGIN_MS = 300L;
    private static final long MINIMUM_MOVE_TIMEOUT_MS = 1200L;
    private static final double LEARNING_WEIGHT = 0.10;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private SharedPreferences preferences;
    private Switch enabledSwitch;
    private Switch testModeSwitch;
    private SeekBar gapSeek;
    private SeekBar speedSeek;
    private TextView speedText;
    private TextView gapText;
    private TextView stateText;
    private TextView referenceText;
    private TextView timingText;
    private TextView sourceText;
    private WindowView windowView;

    private boolean enabled;
    private boolean testMode;
    private boolean speedValid;
    private boolean manualMoving;
    private boolean active;
    private boolean pending;
    private boolean gpsRunning;
    private boolean permissionRequested;
    private boolean programmaticGapChange;
    private boolean automaticMoveInProgress;
    private boolean movementFault;
    private boolean suspendedAtLowSpeedTarget;
    private boolean openingTimeLearned;
    private boolean closingTimeLearned;

    private double speedKmh;
    private double filteredGpsKmh;
    private double gapMm;
    private double referenceSpeedKmh;
    private double referenceGapMm;
    private double targetGapMm;
    private double automaticMoveStartGapMm;
    private double automaticMoveTargetGapMm;
    private double learnedOpeningFullTravelMs = DEFAULT_FULL_TRAVEL_MS;
    private double learnedClosingFullTravelMs = DEFAULT_FULL_TRAVEL_MS;

    private long automaticMoveStartMs;
    private long automaticMoveDeadlineMs;

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
        preferences = getSharedPreferences("window_timing", MODE_PRIVATE);
        loadLearnedTravelTimes();
        buildInterface();
        handler.post(controlLoop);
        startGps();
    }

    private void loadLearnedTravelTimes() {
        openingTimeLearned = preferences.getBoolean("opening_time_learned", false);
        closingTimeLearned = preferences.getBoolean("closing_time_learned", false);
        learnedOpeningFullTravelMs = preferences.getFloat(
                "opening_full_travel_ms",
                (float) DEFAULT_FULL_TRAVEL_MS);
        learnedClosingFullTravelMs = preferences.getFloat(
                "closing_full_travel_ms",
                (float) DEFAULT_FULL_TRAVEL_MS);
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

        sourceText = text("Fuente: GPS; esperando velocidad válida", 14, Color.DKGRAY);
        root.addView(sourceText);

        speedText = text("— km/h", 30, Color.rgb(17, 87, 138));
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

        timingText = text("Tiempo de recorrido: calculando", 14, Color.DKGRAY);
        root.addView(timingText);

        TextView rules = text(
                "Cero km/h es un estado válido: su objetivo es la apertura máxima. "
                        + "Entre cero y 5 km/h se usa el objetivo correspondiente a 5 km/h. "
                        + "Al alcanzar el objetivo de baja velocidad, el control queda suspendido "
                        + "hasta que cambie la velocidad. Una pérdida de GPS no se interpreta como cero.",
                14,
                Color.DKGRAY);
        rules.setPadding(0, dp(10), 0, dp(20));
        root.addView(rules);

        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                enabled = checked;
                movementFault = false;
                suspendedAtLowSpeedTarget = false;
                cancelAutomaticMovement();
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
                cancelAutomaticMovement();
                suspendedAtLowSpeedTarget = false;
                if (testMode) {
                    stopGps();
                    speedKmh = speedSeek.getProgress();
                    speedValid = true;
                    sourceText.setText("Fuente: velocidad de prueba");
                } else {
                    speedKmh = 0.0;
                    speedValid = false;
                    sourceText.setText("Fuente: GPS; esperando velocidad válida");
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
                    speedValid = true;
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
                movementFault = false;
                suspendedAtLowSpeedTarget = false;
                cancelAutomaticMovement();
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
        movementFault = false;
        suspendedAtLowSpeedTarget = false;

        if (!speedValid) {
            if (gapMm >= MIN_AUTOMATIC_GAP_MM) {
                setPendingReference(gapMm);
                setState("Función habilitada con la apertura actual. Esperando una velocidad válida.");
            } else {
                clearReference();
                setState("Función habilitada. La apertura actual es menor al mínimo automático de 25 mm.");
            }
            return;
        }

        if (isFullyOpenAtZero()) {
            clearReference();
            setState("Función habilitada, pero una apertura total a cero no define una referencia.");
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
            setState("Control activado con la apertura actual. La referencia se fijará al superar 5 km/h.");
        }
    }

    private void finishManualMovement() {
        movementFault = false;
        suspendedAtLowSpeedTarget = false;
        cancelAutomaticMovement();

        if (!enabled) {
            clearReference();
            setState("Ajuste manual terminado. La función está apagada.");
        } else if (speedValid && isFullyOpenAtZero()) {
            clearReference();
            setState("Ventana totalmente abierta a cero: referencia eliminada. La función sigue habilitada.");
        } else if (!speedValid || speedKmh <= MIN_SPEED_KMH) {
            if (gapMm >= MANUAL_REFERENCE_GAP_MM) {
                setPendingReference(gapMm);
                setState("Ajuste manual terminado. La referencia se fijará al superar 5 km/h.");
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

    private boolean isFullyOpenAtZero() {
        return gapMm >= MAX_GAP_MM - FULL_OPEN_TOLERANCE_MM && isZeroSpeed();
    }

    private boolean isZeroSpeed() {
        return speedValid && speedKmh <= ZERO_SPEED_MAX_KMH;
    }

    private void setPendingReference(double openingMm) {
        cancelAutomaticMovement();
        active = false;
        pending = true;
        referenceGapMm = openingMm;
        referenceSpeedKmh = 0.0;
        targetGapMm = openingMm;
    }

    private void captureReference() {
        cancelAutomaticMovement();
        referenceSpeedKmh = speedKmh;
        referenceGapMm = gapMm;
        targetGapMm = gapMm;
        active = true;
        pending = false;
    }

    private void clearReference() {
        cancelAutomaticMovement();
        active = false;
        pending = false;
        referenceSpeedKmh = 0.0;
        referenceGapMm = 0.0;
        targetGapMm = gapMm;
    }

    private void regulate() {
        if (!enabled || manualMoving || movementFault) {
            refresh();
            return;
        }

        if (!speedValid) {
            cancelAutomaticMovement();
            setState("Velocidad no válida. Control automático suspendido sin generar un objetivo nuevo.");
            refresh();
            return;
        }

        if (pending) {
            if (speedKmh > MIN_SPEED_KMH) {
                referenceSpeedKmh = speedKmh;
                targetGapMm = referenceGapMm;
                active = true;
                pending = false;
                setState("Control iniciado con la apertura previamente seleccionada.");
            } else {
                setState("Referencia pendiente. Se fijará al superar 5 km/h.");
                refresh();
                return;
            }
        }

        if (!active) {
            refresh();
            return;
        }

        boolean zeroSpeed = isZeroSpeed();
        boolean lowSpeed = speedKmh <= MIN_SPEED_KMH;
        double newTargetMm;

        if (zeroSpeed) {
            newTargetMm = MAX_GAP_MM;
        } else if (lowSpeed) {
            newTargetMm = clamp(
                    referenceGapMm * referenceSpeedKmh / MIN_SPEED_KMH,
                    MIN_AUTOMATIC_GAP_MM,
                    MAX_GAP_MM);
        } else {
            newTargetMm = clamp(
                    referenceGapMm * referenceSpeedKmh / speedKmh,
                    MIN_AUTOMATIC_GAP_MM,
                    MAX_GAP_MM);
            suspendedAtLowSpeedTarget = false;
        }

        targetGapMm = newTargetMm;

        if (Math.abs(newTargetMm - gapMm) <= ARRIVAL_TOLERANCE_MM) {
            if (automaticMoveInProgress) {
                finishAutomaticMovement(true);
            }
            if (lowSpeed) {
                suspendedAtLowSpeedTarget = true;
                setState(zeroSpeed
                        ? "Objetivo de cero alcanzado. Ventana abierta y control suspendido a 0 km/h."
                        : String.format(
                                Locale.getDefault(),
                                "Objetivo del límite mínimo alcanzado: %.0f mm. Control suspendido.",
                                newTargetMm));
            } else {
                setState(String.format(
                        Locale.getDefault(),
                        "Regulación activa. Objetivo alcanzado: %.0f mm.",
                        newTargetMm));
            }
            refresh();
            return;
        }

        if (lowSpeed && suspendedAtLowSpeedTarget
                && Math.abs(newTargetMm - automaticMoveTargetGapMm) <= ARRIVAL_TOLERANCE_MM) {
            refresh();
            return;
        }

        if (!automaticMoveInProgress
                || Math.abs(newTargetMm - automaticMoveTargetGapMm)
                > TARGET_RESTART_DIFFERENCE_MM) {
            beginAutomaticMovement(newTargetMm);
        }

        advanceAutomaticMovement(lowSpeed, zeroSpeed);
    }

    private void beginAutomaticMovement(double targetMm) {
        cancelAutomaticMovement();
        automaticMoveInProgress = true;
        automaticMoveStartGapMm = gapMm;
        automaticMoveTargetGapMm = targetMm;
        automaticMoveStartMs = SystemClock.elapsedRealtime();

        double distanceMm = Math.abs(targetMm - gapMm);
        double fullTravelMs = targetMm > gapMm
                ? learnedOpeningFullTravelMs
                : learnedClosingFullTravelMs;
        double expectedPartialMs = fullTravelMs * distanceMm / MAX_GAP_MM;
        long allowedMs = Math.max(
                MINIMUM_MOVE_TIMEOUT_MS,
                Math.round(expectedPartialMs * TIME_MARGIN_FACTOR + FIXED_TIME_MARGIN_MS));
        automaticMoveDeadlineMs = automaticMoveStartMs + allowedMs;
    }

    private void advanceAutomaticMovement(boolean lowSpeed, boolean zeroSpeed) {
        if (!automaticMoveInProgress) {
            return;
        }

        long nowMs = SystemClock.elapsedRealtime();
        double differenceMm = automaticMoveTargetGapMm - gapMm;

        if (Math.abs(differenceMm) <= ARRIVAL_TOLERANCE_MM) {
            completeAutomaticTarget(lowSpeed, zeroSpeed);
            return;
        }

        if (nowMs > automaticMoveDeadlineMs) {
            stopForMovementTimeout();
            return;
        }

        setGapProgrammatically(clamp(
                gapMm + clamp(differenceMm, -8.0, 8.0),
                MIN_AUTOMATIC_GAP_MM,
                MAX_GAP_MM));

        if (Math.abs(automaticMoveTargetGapMm - gapMm) <= ARRIVAL_TOLERANCE_MM) {
            completeAutomaticTarget(lowSpeed, zeroSpeed);
        } else if (zeroSpeed) {
            setState("Velocidad cero: abriendo hacia el objetivo válido de 450 mm.");
        } else if (lowSpeed) {
            setState(String.format(
                    Locale.getDefault(),
                    "Velocidad baja: buscando el objetivo del límite mínimo de %.0f mm.",
                    automaticMoveTargetGapMm));
        } else {
            setState(String.format(
                    Locale.getDefault(),
                    "Regulando automáticamente. Objetivo: %.0f mm.",
                    automaticMoveTargetGapMm));
        }
        refresh();
    }

    private void completeAutomaticTarget(boolean lowSpeed, boolean zeroSpeed) {
        setGapProgrammatically(automaticMoveTargetGapMm);
        finishAutomaticMovement(true);
        if (lowSpeed) {
            suspendedAtLowSpeedTarget = true;
            setState(zeroSpeed
                    ? "Objetivo de cero alcanzado. Ventana abierta y control suspendido a 0 km/h."
                    : String.format(
                            Locale.getDefault(),
                            "Objetivo del límite mínimo alcanzado: %.0f mm. Control suspendido.",
                            targetGapMm));
        } else {
            setState(String.format(
                    Locale.getDefault(),
                    "Objetivo automático alcanzado: %.0f mm.",
                    targetGapMm));
        }
        refresh();
    }

    private void setGapProgrammatically(double newGapMm) {
        gapMm = newGapMm;
        programmaticGapChange = true;
        gapSeek.setProgress((int) Math.round(gapMm));
        programmaticGapChange = false;
    }

    private void finishAutomaticMovement(boolean learnTiming) {
        if (!automaticMoveInProgress) {
            return;
        }

        long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - automaticMoveStartMs);
        double distanceMm = Math.abs(gapMm - automaticMoveStartGapMm);
        boolean opening = automaticMoveTargetGapMm > automaticMoveStartGapMm;

        if (learnTiming && distanceMm >= MIN_LEARNING_DISTANCE_MM) {
            double equivalentFullTravelMs = elapsedMs * MAX_GAP_MM / distanceMm;
            if (equivalentFullTravelMs >= 1000.0 && equivalentFullTravelMs <= 60000.0) {
                updateLearnedTravelTime(opening, equivalentFullTravelMs);
            }
        }

        cancelAutomaticMovement();
    }

    private void updateLearnedTravelTime(boolean opening, double measuredFullTravelMs) {
        if (opening) {
            learnedOpeningFullTravelMs = openingTimeLearned
                    ? learnedOpeningFullTravelMs * (1.0 - LEARNING_WEIGHT)
                    + measuredFullTravelMs * LEARNING_WEIGHT
                    : measuredFullTravelMs;
            openingTimeLearned = true;
        } else {
            learnedClosingFullTravelMs = closingTimeLearned
                    ? learnedClosingFullTravelMs * (1.0 - LEARNING_WEIGHT)
                    + measuredFullTravelMs * LEARNING_WEIGHT
                    : measuredFullTravelMs;
            closingTimeLearned = true;
        }

        preferences.edit()
                .putBoolean("opening_time_learned", openingTimeLearned)
                .putBoolean("closing_time_learned", closingTimeLearned)
                .putFloat("opening_full_travel_ms", (float) learnedOpeningFullTravelMs)
                .putFloat("closing_full_travel_ms", (float) learnedClosingFullTravelMs)
                .apply();
    }

    private void stopForMovementTimeout() {
        cancelAutomaticMovement();
        movementFault = true;
        active = false;
        pending = false;
        setState("Movimiento detenido: no alcanzó el objetivo dentro del tiempo calculado para el recorrido.");
        refresh();
    }

    private void cancelAutomaticMovement() {
        automaticMoveInProgress = false;
        automaticMoveStartGapMm = gapMm;
        automaticMoveTargetGapMm = gapMm;
        automaticMoveStartMs = 0L;
        automaticMoveDeadlineMs = 0L;
    }

    private void refresh() {
        speedText.setText(speedValid
                ? String.format(Locale.getDefault(), "%.1f km/h", speedKmh)
                : "— km/h");
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

        timingText.setText(String.format(
                Locale.getDefault(),
                "Recorrido completo equivalente — abrir: %.1f s (%s), cerrar: %.1f s (%s)",
                learnedOpeningFullTravelMs / 1000.0,
                openingTimeLearned ? "aprendido" : "inicial",
                learnedClosingFullTravelMs / 1000.0,
                closingTimeLearned ? "aprendido" : "inicial"));
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
            speedValid = false;
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
                speedValid = false;
                setState("Permiso GPS denegado. Usa el modo de prueba.");
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (testMode) {
            return;
        }

        if (!location.hasSpeed()) {
            speedValid = false;
            sourceText.setText("Fuente: GPS; velocidad no válida");
            refresh();
            return;
        }

        double rawSpeedKmh = Math.max(0.0, location.getSpeed() * 3.6);
        if (rawSpeedKmh <= ZERO_SPEED_MAX_KMH) {
            filteredGpsKmh = 0.0;
        } else {
            filteredGpsKmh = filteredGpsKmh == 0.0
                    ? rawSpeedKmh
                    : filteredGpsKmh * 0.75 + rawSpeedKmh * 0.25;
            if (filteredGpsKmh <= ZERO_SPEED_MAX_KMH) {
                filteredGpsKmh = 0.0;
            }
        }

        speedKmh = filteredGpsKmh;
        speedValid = true;
        sourceText.setText("Fuente: GPS");
        refresh();
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        speedValid = false;
        cancelAutomaticMovement();
        sourceText.setText("Fuente: GPS desactivado");
        setState("GPS desactivado. Control suspendido sin interpretar la condición como 0 km/h.");
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
