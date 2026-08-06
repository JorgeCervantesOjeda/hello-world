# Temperature Decrease Sound Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Android APK version 4.9 that emits one grave alarm-channel tone for every whole Celsius degree crossed downward, without changing any upward-temperature behavior.

**Architecture:** Extract whole-degree crossing and hysteresis logic into a small pure-Java `TemperatureDropTracker` so it can be unit-tested independently. `MainActivity` will feed valid battery temperatures into the tracker, enqueue returned drop counts, and use one `ToneGenerator` plus one main-thread `Handler` to play grave tones sequentially.

**Tech Stack:** Android Java, `BatteryManager`, `ToneGenerator`, `AudioManager.STREAM_ALARM`, JUnit 4, Gradle 8.7, Java 17, GitHub Actions.

## Global Constraints

- Preserve all GPS, acceleration, logarithmic-scale, range, direction, and drawing behavior from version 4.8.
- Do not add, remove, or modify any upward-temperature sound behavior.
- First valid temperature reading produces zero tones.
- Use `0.2 °C` hysteresis.
- A direct change `42.3 → 40.8 °C` produces exactly two grave tones.
- Use the alarm stream and maximum generator volume; do not change device volume settings.
- Version name: `4.9-gps-sonido-descenso-temperatura`.
- Application ID: `com.example.accelledsinertial.gpsonly.v49.temperaturedropsound`.

---

### Task 1: Add failing tracker tests

**Files:**
- Create: `accel-leds-inertial/app/src/test/java/com/example/accelledsinertial/TemperatureDropTrackerTest.java`
- Modify: `accel-leds-inertial/app/build.gradle`
- Modify: `.github/workflows/build-accel-leds-inertial.yml`

**Interfaces:**
- Consumes: future class `TemperatureDropTracker(float hysteresisC)`.
- Produces: executable tests for `int update(float temperatureC)`.

- [ ] **Step 1: Add JUnit dependency**

Add:

```gradle
dependencies {
    testImplementation 'junit:junit:4.13.2'
}
```

- [ ] **Step 2: Write failing tests**

Create tests asserting:

```java
assertEquals(0, tracker.update(42.3f));
assertEquals(1, tracker.update(41.8f));
```

```java
assertEquals(0, tracker.update(42.3f));
assertEquals(2, tracker.update(40.8f));
```

```java
assertEquals(0, tracker.update(42.3f));
assertEquals(0, tracker.update(42.0f));
assertEquals(0, tracker.update(42.1f));
```

```java
assertEquals(0, tracker.update(42.3f));
assertEquals(1, tracker.update(41.8f));
assertEquals(0, tracker.update(42.1f));
assertEquals(0, tracker.update(42.2f));
assertEquals(1, tracker.update(41.8f));
```

```java
assertEquals(0, tracker.update(Float.NaN));
assertEquals(0, tracker.update(42.3f));
```

- [ ] **Step 3: Run tests and verify RED**

Run:

```bash
gradle -p accel-leds-inertial testDebugUnitTest --stacktrace
```

Expected: compilation failure because `TemperatureDropTracker` does not exist.

### Task 2: Implement tracker and grave-tone queue

**Files:**
- Create: `accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/TemperatureDropTracker.java`
- Modify: `accel-leds-inertial/app/src/main/java/com/example/accelledsinertial/MainActivity.java`

**Interfaces:**
- Produces: `TemperatureDropTracker(float hysteresisC)` and `int update(float temperatureC)`.
- `MainActivity` consumes the returned whole-degree drop count.

- [ ] **Step 1: Implement minimal tracker**

Use a stable integer level initialized with `floor(firstValidReading)`. For every update:

```java
while (temperatureC <= stableDegree - hysteresisC) {
    stableDegree--;
    drops++;
}
while (temperatureC >= stableDegree + 1f + hysteresisC) {
    stableDegree++;
}
```

Invalid readings return zero and do not alter state.

- [ ] **Step 2: Run unit tests and verify GREEN**

Run:

```bash
gradle -p accel-leds-inertial testDebugUnitTest --stacktrace
```

Expected: all tracker tests pass.

- [ ] **Step 3: Wire temperature updates**

In `updateBatteryTemperature`, after parsing a valid reading, call the tracker and enqueue the returned count. Do not add any branch for upward changes.

- [ ] **Step 4: Add sequential grave-tone playback**

Use:

```java
new ToneGenerator(AudioManager.STREAM_ALARM, 100)
```

Play `ToneGenerator.TONE_DTMF_0` for `180 ms`, with `120 ms` gap. Maintain an integer pending count and a single main-thread `Runnable`; never overlap tones.

- [ ] **Step 5: Release resources**

In `onDestroy`, remove callbacks, stop and release the generator, and clear pending counts.

### Task 3: Version, build, and verify APK

**Files:**
- Modify: `accel-leds-inertial/app/build.gradle`
- Modify: `.github/workflows/build-accel-leds-inertial.yml`

**Interfaces:**
- Produces: GitHub Actions artifact `GPS-Sonido-Descenso-Temperatura-v4.9`.

- [ ] **Step 1: Update version metadata**

Set the exact application ID and version name from Global Constraints.

- [ ] **Step 2: Run complete verification**

Run:

```bash
gradle -p accel-leds-inertial testDebugUnitTest assembleDebug --stacktrace
```

Expected: tests and APK build succeed.

- [ ] **Step 3: Inspect source invariants**

Confirm the source contains `STREAM_ALARM`, `TONE_DTMF_0`, `TemperatureDropTracker`, and no new upward-tone branch.

- [ ] **Step 4: Verify artifact**

Check ZIP/APK integrity, Android signing block, version strings, and SHA-256. Deliver the APK and checksum file.
