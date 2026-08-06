package com.example.accelledsinertial;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TemperatureDropTrackerTest {

    @Test
    public void firstReadingInitializesWithoutTone() {
        TemperatureDropTracker tracker = new TemperatureDropTracker(0.2f);

        assertEquals(0, tracker.update(42.3f));
    }

    @Test
    public void oneWholeDegreeDropProducesOneTone() {
        TemperatureDropTracker tracker = new TemperatureDropTracker(0.2f);

        assertEquals(0, tracker.update(42.3f));
        assertEquals(1, tracker.update(41.8f));
    }

    @Test
    public void multipleWholeDegreeDropProducesOneTonePerDegree() {
        TemperatureDropTracker tracker = new TemperatureDropTracker(0.2f);

        assertEquals(0, tracker.update(42.3f));
        assertEquals(2, tracker.update(40.8f));
    }

    @Test
    public void hysteresisSuppressesNearBoundaryFluctuations() {
        TemperatureDropTracker tracker = new TemperatureDropTracker(0.2f);

        assertEquals(0, tracker.update(42.3f));
        assertEquals(0, tracker.update(42.0f));
        assertEquals(0, tracker.update(42.1f));
        assertEquals(0, tracker.update(41.9f));
    }

    @Test
    public void upwardRearmIsSilentAndAllowsASecondDrop() {
        TemperatureDropTracker tracker = new TemperatureDropTracker(0.2f);

        assertEquals(0, tracker.update(42.3f));
        assertEquals(1, tracker.update(41.8f));
        assertEquals(0, tracker.update(42.1f));
        assertEquals(0, tracker.update(42.2f));
        assertEquals(1, tracker.update(41.8f));
    }

    @Test
    public void invalidReadingDoesNotInitializeOrChangeState() {
        TemperatureDropTracker tracker = new TemperatureDropTracker(0.2f);

        assertEquals(0, tracker.update(Float.NaN));
        assertEquals(0, tracker.update(Float.POSITIVE_INFINITY));
        assertEquals(0, tracker.update(42.3f));
        assertEquals(1, tracker.update(41.8f));
    }
}
