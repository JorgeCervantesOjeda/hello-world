package com.example.accelledsinertial;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Locale;

public final class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int REQ_LOCATION = 7;
    private static final float LPF_TAU_S = 0.12f;
    private static final float DEAD_ZONE = 0.09f;

    private SensorManager sensorManager;
    private Sensor motionSensor;
    private boolean nativeLinearSensor;
    private LocationManager locationManager;
    private LedView view;
    private SharedPreferences prefs;

    private final float[] gravity = new float[3];
    private final float[] filtered = new float[3];
    private final float[] axis = new float[]{1f, 0f, 0f};
    private float zeroBias;
    private long lastSensorNs;
    private float longitudinal;
    private float gpsSpeed;
    private float gpsAccuracy = Float.NaN;
    private long lastGpsMs;
    private boolean showInfo = true;
    private boolean calibrated;

    private int calibrationState; // 0 idle, 1 countdown, 2 collecting
    private long calibrationStartMs;
    private int calibrationSamples;
    private final double[][] covariance = new double[3][3];
    private final double[] calibrationMean = new double[3];
    private String message = "Fija el teléfono y pulsa CALIBRAR";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.screenBrightness = 1f;
        getWindow().setAttributes(p);
        hideUi();

        prefs = getSharedPreferences("calibration", MODE_PRIVATE);
        calibrated = prefs.getBoolean("valid", false);
        if (calibrated) {
            axis[0] = prefs.getFloat("x", 1f);
            axis[1] = prefs.getFloat("y", 0f);
            axis[2] = prefs.getFloat("z", 0f);
            zeroBias = prefs.getFloat("bias", 0f);
            message = "Calibración cargada";
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        nativeLinearSensor = motionSensor != null;
        if (motionSensor == null) motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        view = new LedView(this);
        setContentView(view);
        startLocation();
    }

    @Override protected void onResume() {
        super.onResume();
        hideUi();
        if (motionSensor != null) sensorManager.registerListener(this, motionSensor, SensorManager.SENSOR_DELAY_GAME);
        startLocation();
    }

    @Override protected void onPause() {
        sensorManager.unregisterListener(this);
        stopLocation();
        super.onPause();
    }

    private void hideUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void startLocation() {
        if (locationManager == null) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this);
        } catch (Exception ignored) { }
    }

    private void stopLocation() {
        try { if (locationManager != null) locationManager.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startLocation();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        final long ns = event.timestamp;
        float dt = lastSensorNs == 0L ? 0.02f : Math.max(0.002f, Math.min(0.1f, (ns - lastSensorNs) / 1_000_000_000f));
        lastSensorNs = ns;

        float[] linear = new float[3];
        if (nativeLinearSensor) {
            linear[0] = event.values[0]; linear[1] = event.values[1]; linear[2] = event.values[2];
        } else {
            float gAlpha = LPF_TAU_S / (LPF_TAU_S + dt);
            for (int i = 0; i < 3; i++) {
                gravity[i] = gAlpha * gravity[i] + (1f - gAlpha) * event.values[i];
                linear[i] = event.values[i] - gravity[i];
            }
        }

        float alpha = dt / (LPF_TAU_S + dt);
        for (int i = 0; i < 3; i++) filtered[i] += alpha * (linear[i] - filtered[i]);

        updateCalibration();
        if (calibrated && calibrationState == 0) {
            float raw = dot(filtered, axis) - zeroBias;
            longitudinal = Math.abs(raw) < DEAD_ZONE ? 0f : raw;
        } else {
            longitudinal = 0f;
        }
        view.invalidate();
    }

    private void beginCalibration() {
        calibrated = false;
        calibrationState = 1;
        calibrationStartMs = SystemClock.elapsedRealtime();
        calibrationSamples = 0;
        for (int i = 0; i < 3; i++) {
            calibrationMean[i] = 0;
            for (int j = 0; j < 3; j++) covariance[i][j] = 0;
        }
        message = "CALIBRACIÓN: espera 3 s; después acelera suavemente y recto";
    }

    private void updateCalibration() {
        if (calibrationState == 0) return;
        long elapsed = SystemClock.elapsedRealtime() - calibrationStartMs;
        if (calibrationState == 1 && elapsed >= 3000L) {
            calibrationState = 2;
            message = "ACELERA SUAVEMENTE HACIA DELANTE durante 5 s";
        }
        if (calibrationState == 2) {
            float mag = magnitude(filtered);
            if (mag > 0.10f && mag < 4.5f) {
                calibrationSamples++;
                for (int i = 0; i < 3; i++) {
                    calibrationMean[i] += filtered[i];
                    for (int j = 0; j < 3; j++) covariance[i][j] += filtered[i] * filtered[j];
                }
            }
            if (elapsed >= 8000L) finishCalibration();
        }
    }

    private void finishCalibration() {
        calibrationState = 0;
        if (calibrationSamples < 15) {
            message = "Calibración insuficiente. Repite con una aceleración más clara";
            return;
        }
        double[] v = new double[]{1, 1, 1};
        normalize(v);
        for (int n = 0; n < 20; n++) {
            double[] next = new double[3];
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) next[i] += covariance[i][j] * v[j];
            normalize(next);
            v = next;
        }
        double sign = v[0] * calibrationMean[0] + v[1] * calibrationMean[1] + v[2] * calibrationMean[2];
        if (sign < 0) for (int i = 0; i < 3; i++) v[i] = -v[i];
        axis[0] = (float) v[0]; axis[1] = (float) v[1]; axis[2] = (float) v[2];
        zeroBias = dot(filtered, axis);
        calibrated = true;
        prefs.edit().putBoolean("valid", true).putFloat("x", axis[0]).putFloat("y", axis[1]).putFloat("z", axis[2]).putFloat("bias", zeroBias).apply();
        message = "Calibrado. No muevas el teléfono en su soporte";
    }

    private void setZero() {
        if (!calibrated) return;
        zeroBias = dot(filtered, axis);
        prefs.edit().putFloat("bias", zeroBias).apply();
        message = "Cero ajustado";
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onLocationChanged(Location location) {
        if (location != null && location.hasSpeed()) {
            gpsSpeed = Math.max(0f, location.getSpeed());
            if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) gpsAccuracy = location.getSpeedAccuracyMetersPerSecond();
            lastGpsMs = SystemClock.elapsedRealtime();
            view.invalidate();
        }
    }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @SuppressWarnings("deprecation") @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private static float dot(float[] a, float[] b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
    private static float magnitude(float[] a) { return (float)Math.sqrt(dot(a, a)); }
    private static void normalize(double[] v) {
        double m = Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (m < 1e-9) { v[0]=1; v[1]=0; v[2]=0; return; }
        for (int i=0;i<3;i++) v[i] /= m;
    }
    private static float clamp(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }
    private static float level(float a, float max) {
        if (a <= DEAD_ZONE) return 0f;
        float u = (float)(Math.log1p((a-DEAD_ZONE)/0.15f) / Math.log1p((max-DEAD_ZONE)/0.15f));
        return clamp(u * 10f, 0f, 10f);
    }

    private final class LedView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        LedView(Context c) { super(c); setBackgroundColor(Color.BLACK); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w=getWidth(), h=getHeight();
            float top=h*0.26f, bottom=h*0.73f, side=w*0.035f, gap=w*0.045f;
            float slot=(w-side*2-gap)/20f, sw=slot*0.72f, cx=w/2f;
            float green=level(Math.max(0,longitudinal),3.5f);
            float red=level(Math.max(0,-longitudinal),7f);
            for(int i=0;i<10;i++) {
                float lx=cx-gap/2-slot*(i+1)+(slot-sw)/2;
                float rx=cx+gap/2+slot*i+(slot-sw)/2;
                segment(c,lx,top,sw,bottom-top,Color.rgb(0,255,70),green-i);
                segment(c,rx,top,sw,bottom-top,Color.rgb(255,35,25),red-i);
            }
            drawButton(c,0,"CALIBRAR"); drawButton(c,1,showInfo?"OCULTAR INFO":"MOSTRAR INFO"); drawButton(c,2,"AJUSTAR CERO");
            if(showInfo) drawInfo(c,w,h);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(h*0.038f); paint.setColor(Color.LTGRAY);
            c.drawText(message,w/2f,h*0.88f,paint);
            paint.setTextSize(h*0.027f); paint.setColor(Color.GRAY);
            c.drawText("Uso experimental · teléfono fijado · no manipular al conducir",w/2f,h*0.97f,paint);
        }

        private void segment(Canvas c,float x,float y,float width,float height,int active,float amount) {
            paint.setColor(amount>0 ? active : Color.rgb(18,18,18));
            paint.setAlpha(amount>=1?255:amount>0?(int)(60+195*amount):255);
            rect.set(x,y,x+width,y+height); c.drawRoundRect(rect,width*0.18f,width*0.18f,paint); paint.setAlpha(255);
        }

        private void drawButton(Canvas c,int index,String label) {
            float margin=getWidth()*0.02f,gap=getWidth()*0.012f,bw=(getWidth()-2*margin-2*gap)/3f;
            float x=margin+index*(bw+gap), y=getHeight()*0.035f, bh=getHeight()*0.13f;
            paint.setColor(Color.rgb(32,32,32)); rect.set(x,y,x+bw,y+bh); c.drawRoundRect(rect,bh*0.2f,bh*0.2f,paint);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(getHeight()*0.045f); paint.setColor(Color.WHITE);
            c.drawText(label,x+bw/2,y+bh*0.68f,paint);
        }

        private void drawInfo(Canvas c,int w,int h) {
            paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(h*0.034f); paint.setColor(Color.rgb(190,190,190));
            String gpsAge=lastGpsMs==0?"sin datos":(SystemClock.elapsedRealtime()-lastGpsMs)+" ms";
            String[] lines={
                String.format(Locale.US,"Aceleración: %+.3f m/s²",longitudinal),
                String.format(Locale.US,"GPS: %.1f km/h · edad %s",gpsSpeed*3.6f,gpsAge),
                "Sensor: "+(nativeLinearSensor?"aceleración lineal":"acelerómetro con gravedad estimada"),
                String.format(Locale.US,"Eje aprendido: [%.2f, %.2f, %.2f]",axis[0],axis[1],axis[2]),
                "Estado: "+(calibrated?"calibrado":"sin calibrar")
            };
            float y=h*0.78f; for(String s:lines){ c.drawText(s,w*0.035f,y,paint); y+=h*0.042f; }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()!=MotionEvent.ACTION_UP) return true;
            float third=getWidth()/3f;
            if(e.getY()<getHeight()*0.2f) {
                if(e.getX()<third) beginCalibration();
                else if(e.getX()<2*third) showInfo=!showInfo;
                else setZero();
                invalidate();
            }
            return true;
        }
    }
}
