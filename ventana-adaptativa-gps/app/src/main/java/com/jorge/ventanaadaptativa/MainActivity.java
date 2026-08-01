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
import android.view.MotionEvent;
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

    private static final double MIN_REFERENCE_SPEED_KMH = 5.0;
    private static final double ZERO_SPEED_EPSILON_KMH = 0.01;
    private static final double MAX_GAP_MM = 450.0;
    private static final double AUTOMATIC_POSITIVE_FLOOR_MM = Double.MIN_VALUE;
    private static final double CONTROL_EPSILON_MM = 1.0e-9;
    private static final double AUTOMATIC_MOVEMENT_STEP_MM = 15.0;

    private static final double MIN_LEARNING_DISTANCE_MM = 20.0;
    private static final double DEFAULT_FULL_TRAVEL_MS = 9000.0;
    private static final double TIME_MARGIN_FACTOR = 1.4;
    private static final long FIXED_TIME_MARGIN_MS = 300L;
    private static final long MINIMUM_MOVE_TIMEOUT_MS = 1200L;
    private static final double LEARNING_WEIGHT = 0.10;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private SharedPreferences preferences;

    private Switch enabledSwitch;
    private Switch testModeSwitch;
    private SeekBar speedSeek;
    private TextView speedText;
    private TextView gapText;
    private TextView requestedFlowText;
    private TextView calculatedFlowText;
    private TextView flowDifferenceText;
    private TextView stateText;
    private TextView referenceText;
    private TextView timingText;
    private TextView sourceText;
    private WindowView windowView;

    private boolean enabled;
    private boolean testMode;
    private boolean manualMoving;
    private boolean active;
    private boolean gpsRunning;
    private boolean permissionRequested;
    private boolean automaticMoveInProgress;
    private boolean movementFault;
    private boolean openingTimeLearned;
    private boolean closingTimeLearned;

    private double speedKmh = 0.0;
    private double lastGpsSpeedKmh = 0.0;

    private double gapMm;
    private double referenceMeasuredSpeedKmh;
    private double referenceCalculationSpeedKmh;
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

        TextView subtitle = text(
                "Control continuo de apertura y flujo relativo",
                15,
                Color.DKGRAY);
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

        sourceText = text("Fuente: GPS; velocidad inicial 0.0 km/h", 14, Color.DKGRAY);
        root.addView(sourceText);

        speedText = text("0.0 km/h", 30, Color.rgb(17, 87, 138));
        speedText.setGravity(Gravity.CENTER);
        root.addView(speedText);

        speedSeek = new SeekBar(this);
        speedSeek.setMax(160);
        speedSeek.setVisibility(View.GONE);
        root.addView(speedSeek);

        windowView = new WindowView(this);
        windowView.setManualGapListener(new WindowView.ManualGapListener() {
            @Override
            public void onManualStart() {
                manualMoving = true;
                movementFault = false;
                cancelAutomaticMovement();
                setState("Movimiento manual: automatización suspendida temporalmente.");
            }

            @Override
            public void onManualChange(double openingMm) {
                gapMm = openingMm;
                setState("Movimiento manual: automatización suspendida temporalmente.");
                refresh();
            }

            @Override
            public void onManualEnd() {
                manualMoving = false;
                finishManualMovement();
            }
        });
        LinearLayout.LayoutParams windowParams = new LinearLayout.LayoutParams(-1, dp(300));
        windowParams.setMargins(0, dp(10), 0, dp(8));
        root.addView(windowView, windowParams);

        gapText = text("Apertura: 0 mm", 26, Color.rgb(17, 87, 138));
        gapText.setGravity(Gravity.CENTER);
        root.addView(gapText);

        requestedFlowText = text("Flujo solicitado: sin referencia", 18, Color.rgb(25, 45, 66));
        requestedFlowText.setGravity(Gravity.CENTER);
        root.addView(requestedFlowText);

        calculatedFlowText = text("Flujo real calculado: 0 mm·km/h", 18, Color.rgb(17, 87, 138));
        calculatedFlowText.setGravity(Gravity.CENTER);
        root.addView(calculatedFlowText);

        flowDifferenceText = text("Diferencia: sin referencia", 14, Color.DKGRAY);
        flowDifferenceText.setGravity(Gravity.CENTER);
        root.addView(flowDifferenceText);

        TextView flowHint = text(
                "Flujo relativo = apertura × velocidad. Para obtener m³/s se requiere calibrar el área real y la aerodinámica de la ventana.",
                13,
                Color.DKGRAY);
        flowHint.setGravity(Gravity.CENTER);
        root.addView(flowHint);

        TextView manualHint = text(
                "Arrastra verticalmente el borde del cristal. El control conserva valores double sin convertirlos a enteros.",
                14,
                Color.DKGRAY);
        root.addView(manualHint);

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
                "El flujo solicitado se fija con la referencia manual: apertura de referencia por velocidad de referencia de cálculo. "
                        + "El flujo real calculado usa la apertura y velocidad actuales. "
                        + "A 0 km/h el flujo calculado es cero aunque la ventana se abra a 450 mm. "
                        + "Para cualquier velocidad positiva, el objetivo automático permanece mayor que cero. "
                        + "Solo un cierre manual completo puede llevar la apertura a 0 mm y borrar la referencia.",
                14,
                Color.DKGRAY);
        rules.setPadding(0, dp(10), 0, dp(20));
        root.addView(rules);

        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                enabled = checked;
                movementFault = false;
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
                cancelAutomaticMovement();
                speedSeek.setVisibility(testMode ? View.VISIBLE : View.GONE);

                if (testMode) {
                    stopGps();
                    speedKmh = speedSeek.getProgress();
                    sourceText.setText("Fuente: velocidad de prueba");
                } else {
                    speedKmh = lastGpsSpeedKmh;
                    sourceText.setText(String.format(
                            Locale.getDefault(),
                            "Fuente: GPS; conservando %.1f km/h hasta una lectura nueva",
                            speedKmh));
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

        setContentView(scroll);
        refresh();
    }

    private void activateFromCurrentOpening() {
        movementFault = false;
        if (gapMm <= 0.0) {
            clearReference();
            setState("Función habilitada. Abre manualmente la ventana para definir una referencia.");
            return;
        }
        captureReference(gapMm);
        setState(referenceMeasuredSpeedKmh < MIN_REFERENCE_SPEED_KMH
                ? "Control activado. Referencia fijada con 5 km/h; operación con velocidad real."
                : "Control activado con la apertura y velocidad actuales como referencia.");
    }

    private void finishManualMovement() {
        movementFault = false;
        cancelAutomaticMovement();
        if (!enabled) {
            clearReference();
            setState("Ajuste manual terminado. La función está apagada.");
        } else if (gapMm <= 0.0) {
            gapMm = 0.0;
            clearReference();
            setState("Cierre manual completo: referencia eliminada. La ventana permanecerá cerrada.");
        } else {
            captureReference(gapMm);
            setState(referenceMeasuredSpeedKmh < MIN_REFERENCE_SPEED_KMH
                    ? "Nueva referencia positiva: se usaron 5 km/h para fijarla."
                    : "Control reactivado con la nueva apertura manual como referencia.");
        }
        refresh();
    }

    private boolean isZeroSpeed() {
        return speedKmh <= ZERO_SPEED_EPSILON_KMH;
    }

    private void captureReference(double openingMm) {
        cancelAutomaticMovement();
        referenceGapMm = openingMm;
        referenceMeasuredSpeedKmh = speedKmh;
        referenceCalculationSpeedKmh = Math.max(speedKmh, MIN_REFERENCE_SPEED_KMH);
        targetGapMm = openingMm;
        active = true;
    }

    private void clearReference() {
        cancelAutomaticMovement();
        active = false;
        referenceMeasuredSpeedKmh = 0.0;
        referenceCalculationSpeedKmh = 0.0;
        referenceGapMm = 0.0;
        targetGapMm = gapMm;
    }

    private void regulate() {
        if (!enabled || manualMoving || movementFault) {
            refresh();
            return;
        }
        if (!active) {
            refresh();
            return;
        }

        double newTargetMm = calculateTargetForRealSpeed(speedKmh);
        targetGapMm = newTargetMm;

        if (nearlyEqual(newTargetMm, gapMm)) {
            if (automaticMoveInProgress) {
                finishAutomaticMovement(true);
            }
            setGapProgrammatically(newTargetMm);
            setState(isZeroSpeed()
                    ? "Objetivo de 0 km/h alcanzado: apertura máxima; flujo calculado igual a cero."
                    : String.format(Locale.getDefault(),
                            "Regulación continua a %.1f km/h. Objetivo: %s mm.",
                            speedKmh, formatMillimeters(newTargetMm)));
            refresh();
            return;
        }

        if (!automaticMoveInProgress || !nearlyEqual(newTargetMm, automaticMoveTargetGapMm)) {
            beginAutomaticMovement(newTargetMm);
        }
        advanceAutomaticMovement();
    }

    private double calculateTargetForRealSpeed(double realSpeedKmh) {
        if (realSpeedKmh <= ZERO_SPEED_EPSILON_KMH) {
            return MAX_GAP_MM;
        }
        double calculatedMm = referenceGapMm * referenceCalculationSpeedKmh / realSpeedKmh;
        if (Double.isNaN(calculatedMm)) {
            return AUTOMATIC_POSITIVE_FLOOR_MM;
        }
        if (calculatedMm == Double.POSITIVE_INFINITY || calculatedMm >= MAX_GAP_MM) {
            return MAX_GAP_MM;
        }
        return Math.max(AUTOMATIC_POSITIVE_FLOOR_MM, calculatedMm);
    }

    private void beginAutomaticMovement(double targetMm) {
        cancelAutomaticMovement();
        automaticMoveInProgress = true;
        automaticMoveStartGapMm = gapMm;
        automaticMoveTargetGapMm = Math.max(AUTOMATIC_POSITIVE_FLOOR_MM, targetMm);
        automaticMoveStartMs = SystemClock.elapsedRealtime();

        double distanceMm = Math.abs(automaticMoveTargetGapMm - gapMm);
        double fullTravelMs = automaticMoveTargetGapMm > gapMm
                ? learnedOpeningFullTravelMs : learnedClosingFullTravelMs;
        double expectedPartialMs = fullTravelMs * distanceMm / MAX_GAP_MM;
        long allowedMs = Math.max(MINIMUM_MOVE_TIMEOUT_MS,
                Math.round(expectedPartialMs * TIME_MARGIN_FACTOR + FIXED_TIME_MARGIN_MS));
        automaticMoveDeadlineMs = automaticMoveStartMs + allowedMs;
    }

    private void advanceAutomaticMovement() {
        if (!automaticMoveInProgress) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        double differenceMm = automaticMoveTargetGapMm - gapMm;
        if (nearlyEqual(automaticMoveTargetGapMm, gapMm)) {
            completeAutomaticTarget();
            return;
        }
        if (nowMs > automaticMoveDeadlineMs) {
            stopForMovementTimeout();
            return;
        }

        double stepMm = clamp(differenceMm,
                -AUTOMATIC_MOVEMENT_STEP_MM, AUTOMATIC_MOVEMENT_STEP_MM);
        double nextGapMm = gapMm + stepMm;
        if (automaticMoveTargetGapMm < gapMm) {
            nextGapMm = Math.max(automaticMoveTargetGapMm, nextGapMm);
        } else {
            nextGapMm = Math.min(automaticMoveTargetGapMm, nextGapMm);
        }
        setGapProgrammatically(clamp(nextGapMm,
                AUTOMATIC_POSITIVE_FLOOR_MM, MAX_GAP_MM));

        if (nearlyEqual(automaticMoveTargetGapMm, gapMm)) {
            completeAutomaticTarget();
        } else {
            setState(isZeroSpeed()
                    ? "Velocidad real 0 km/h: abriendo hacia 450 mm; flujo calculado igual a cero."
                    : String.format(Locale.getDefault(),
                            "Regulando a %.1f km/h. Objetivo continuo: %s mm.",
                            speedKmh, formatMillimeters(automaticMoveTargetGapMm)));
            refresh();
        }
    }

    private void completeAutomaticTarget() {
        setGapProgrammatically(automaticMoveTargetGapMm);
        finishAutomaticMovement(true);
        setState(isZeroSpeed()
                ? "Objetivo de 0 km/h alcanzado: apertura máxima; flujo calculado igual a cero."
                : String.format(Locale.getDefault(),
                        "Objetivo automático alcanzado a %.1f km/h: %s mm.",
                        speedKmh, formatMillimeters(targetGapMm)));
        refresh();
    }

    private void setGapProgrammatically(double newGapMm) {
        gapMm = clamp(newGapMm, AUTOMATIC_POSITIVE_FLOOR_MM, MAX_GAP_MM);
        windowView.setGap(gapMm);
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
        setState("Movimiento detenido: no alcanzó el objetivo dentro del tiempo calculado.");
        refresh();
    }

    private void cancelAutomaticMovement() {
        automaticMoveInProgress = false;
        automaticMoveStartGapMm = gapMm;
        automaticMoveTargetGapMm = gapMm;
        automaticMoveStartMs = 0L;
        automaticMoveDeadlineMs = 0L;
    }

    private double requestedFlow() {
        return active ? referenceGapMm * referenceCalculationSpeedKmh : 0.0;
    }

    private double calculatedFlow() {
        return gapMm * speedKmh;
    }

    private void refresh() {
        speedText.setText(String.format(Locale.getDefault(), "%.1f km/h", speedKmh));
        gapText.setText(String.format(Locale.getDefault(),
                "Apertura: %s mm", formatMillimeters(gapMm)));
        windowView.setGap(gapMm);

        double calculated = calculatedFlow();
        calculatedFlowText.setText(String.format(Locale.getDefault(),
                "Flujo real calculado: %s mm·km/h", formatFlow(calculated)));

        if (active) {
            double requested = requestedFlow();
            double difference = calculated - requested;
            double differencePercent = requested > 0.0
                    ? difference * 100.0 / requested : 0.0;
            requestedFlowText.setText(String.format(Locale.getDefault(),
                    "Flujo solicitado: %s mm·km/h", formatFlow(requested)));
            flowDifferenceText.setText(String.format(Locale.getDefault(),
                    "Diferencia: %s mm·km/h (%+.3f%%)",
                    formatSignedFlow(difference), differencePercent));
            referenceText.setText(String.format(Locale.getDefault(),
                    "Referencia: %s mm; velocidad medida %.1f km/h; cálculo %.1f km/h; objetivo %s mm",
                    formatMillimeters(referenceGapMm), referenceMeasuredSpeedKmh,
                    referenceCalculationSpeedKmh, formatMillimeters(targetGapMm)));
        } else {
            requestedFlowText.setText("Flujo solicitado: sin referencia");
            flowDifferenceText.setText("Diferencia: sin referencia");
            referenceText.setText("Referencia: ninguna");
        }

        timingText.setText(String.format(Locale.getDefault(),
                "Recorrido equivalente — abrir: %.1f s (%s), cerrar: %.1f s (%s)",
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
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        LOCATION_REQUEST);
            }
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    500L, 0.0f, this);
            gpsRunning = true;
        } catch (RuntimeException error) {
            sourceText.setText(String.format(Locale.getDefault(),
                    "GPS no disponible; conservando %.1f km/h", speedKmh));
            setState("GPS no disponible. El control continúa con la última velocidad conocida.");
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
                sourceText.setText(String.format(Locale.getDefault(),
                        "Permiso GPS denegado; conservando %.1f km/h", speedKmh));
                setState("Sin permiso GPS. El control continúa con la última velocidad conocida.");
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (testMode) {
            return;
        }
        if (location.hasSpeed() && Float.isFinite(location.getSpeed())
                && location.getSpeed() >= 0.0f) {
            lastGpsSpeedKmh = location.getSpeed() * 3.6;
            speedKmh = lastGpsSpeedKmh;
            sourceText.setText("Fuente: GPS");
        } else {
            sourceText.setText(String.format(Locale.getDefault(),
                    "Fuente: GPS; sin velocidad nueva, conservando %.1f km/h", speedKmh));
        }
        refresh();
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (!LocationManager.GPS_PROVIDER.equals(provider) || testMode) {
            return;
        }
        sourceText.setText(String.format(Locale.getDefault(),
                "GPS reactivado; conservando %.1f km/h hasta una lectura nueva", speedKmh));
        setState("GPS reactivado. Se conserva la última velocidad conocida.");
        refresh();
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (!LocationManager.GPS_PROVIDER.equals(provider)) {
            return;
        }
        sourceText.setText(String.format(Locale.getDefault(),
                "GPS desactivado; conservando %.1f km/h", speedKmh));
        setState("GPS desactivado. El control continúa con la última velocidad conocida.");
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

    private static boolean nearlyEqual(double first, double second) {
        return Math.abs(first - second) <= CONTROL_EPSILON_MM;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String formatMillimeters(double value) {
        if (value == 0.0) return "0";
        if (value > 0.0 && value < 1.0e-6)
            return String.format(Locale.getDefault(), "%.6e", value);
        if (value < 1.0)
            return String.format(Locale.getDefault(), "%.9f", value);
        return String.format(Locale.getDefault(), "%.6f", value);
    }

    private static String formatFlow(double value) {
        if (!Double.isFinite(value)) return "no finito";
        if (value == 0.0) return "0";
        double magnitude = Math.abs(value);
        if (magnitude < 1.0e-6 || magnitude >= 1.0e9)
            return String.format(Locale.getDefault(), "%.6e", value);
        if (magnitude < 1.0)
            return String.format(Locale.getDefault(), "%.9f", value);
        return String.format(Locale.getDefault(), "%.6f", value);
    }

    private static String formatSignedFlow(double value) {
        String formatted = formatFlow(Math.abs(value));
        if (value > 0.0) return "+" + formatted;
        if (value < 0.0) return "−" + formatted;
        return "0";
    }

    private static final class WindowView extends View {
        interface ManualGapListener {
            void onManualStart();
            void onManualChange(double openingMm);
            void onManualEnd();
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double gapMm;
        private ManualGapListener manualGapListener;
        private boolean manualGestureActive;

        WindowView(Context context) {
            super(context);
            setClickable(true);
        }

        void setGap(double value) {
            gapMm = clamp(value, 0.0, MAX_GAP_MM);
            invalidate();
        }

        void setManualGapListener(ManualGapListener listener) {
            manualGapListener = listener;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    manualGestureActive = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if (manualGapListener != null) {
                        manualGapListener.onManualStart();
                        manualGapListener.onManualChange(openingFromTouch(event.getY()));
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (manualGestureActive && manualGapListener != null)
                        manualGapListener.onManualChange(openingFromTouch(event.getY()));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (manualGestureActive && manualGapListener != null) {
                        manualGapListener.onManualChange(openingFromTouch(event.getY()));
                        manualGapListener.onManualEnd();
                    }
                    manualGestureActive = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (manualGestureActive && manualGapListener != null)
                        manualGapListener.onManualEnd();
                    manualGestureActive = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private double openingFromTouch(float touchY) {
            double insideTop = 32.0;
            double insideBottom = Math.max(insideTop + 1.0, getHeight() - 32.0);
            double normalized = (touchY - insideTop) / (insideBottom - insideTop);
            return clamp(normalized * MAX_GAP_MM, 0.0, MAX_GAP_MM);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float margin = 20.0f;
            RectF frame = new RectF(margin, margin, width - margin, height - margin);
            paint.setColor(Color.rgb(34, 45, 57));
            canvas.drawRoundRect(frame, 24.0f, 24.0f, paint);
            RectF inside = new RectF(frame.left + 12.0f, frame.top + 12.0f,
                    frame.right - 12.0f, frame.bottom - 12.0f);
            paint.setColor(Color.rgb(220, 232, 240));
            canvas.drawRoundRect(inside, 16.0f, 16.0f, paint);
            float openPixels = (float) (gapMm / MAX_GAP_MM * inside.height());
            float glassTop = inside.top + openPixels;
            if (glassTop < inside.bottom) {
                paint.setColor(Color.rgb(105, 174, 208));
                canvas.drawRect(inside.left, glassTop, inside.right, inside.bottom, paint);
            }
            paint.setColor(Color.rgb(20, 38, 58));
            paint.setTextSize(30.0f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(formatMillimeters(gapMm) + " mm", width / 2.0f,
                    inside.top + Math.max(45.0f, openPixels / 2.0f), paint);
        }
    }
}
