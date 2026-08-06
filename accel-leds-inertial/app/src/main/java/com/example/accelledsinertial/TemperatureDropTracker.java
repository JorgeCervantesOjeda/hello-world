package com.example.accelledsinertial;

final class TemperatureDropTracker {
    private final float hysteresisC;

    private boolean initialized;
    private int stableDegree;

    TemperatureDropTracker(float hysteresisC) {
        if (Float.isNaN(hysteresisC)
                || Float.isInfinite(hysteresisC)
                || hysteresisC <= 0f
                || hysteresisC >= 1f) {
            throw new IllegalArgumentException("hysteresisC must be between 0 and 1");
        }
        this.hysteresisC = hysteresisC;
    }

    int update(float temperatureC) {
        if (Float.isNaN(temperatureC) || Float.isInfinite(temperatureC)) {
            return 0;
        }

        if (!initialized) {
            stableDegree = (int) Math.floor(temperatureC);
            initialized = true;
            return 0;
        }

        int drops = 0;
        while (temperatureC <= stableDegree - hysteresisC) {
            stableDegree--;
            drops++;
        }

        while (temperatureC >= stableDegree + 1f + hysteresisC) {
            stableDegree++;
        }

        return drops;
    }
}
