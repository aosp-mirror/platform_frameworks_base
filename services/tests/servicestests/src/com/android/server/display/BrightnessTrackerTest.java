/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.display;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.MessageQueue;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.AtomicFile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessTrackerTest {
    private static final float DEFAULT_INITIAL_BRIGHTNESS = 2.5f;
    private static final float FLOAT_DELTA = 0.01f;

    private BrightnessTracker mTracker;
    private TestInjector mInjector;

    private static Object sHandlerLock = new Object();
    private static Handler sHandler;
    private static HandlerThread sThread =
            new HandlerThread("brightness.test", android.os.Process.THREAD_PRIORITY_BACKGROUND);

    private static Handler ensureHandler() {
        synchronized (sHandlerLock) {
            if (sHandler == null) {
                sThread.start();
                sHandler = new Handler(sThread.getLooper());
            }
            return sHandler;
        }
    }


    @Before
    public void setUp() throws Exception {
        mInjector = new TestInjector(ensureHandler());

        mTracker = new BrightnessTracker(InstrumentationRegistry.getContext(), mInjector);
    }

    @Test
    public void testStartStopTrackerScreenOnOff() {
        mInjector.mInteractive = false;
        startTracker(mTracker);
        assertNull(mInjector.mSensorListener);
        assertNotNull(mInjector.mBroadcastReceiver);
        assertTrue(mInjector.mIdleScheduled);
        mInjector.sendScreenChange(/*screen on */ true);
        assertNotNull(mInjector.mSensorListener);

        mInjector.sendScreenChange(/*screen on */ false);
        assertNull(mInjector.mSensorListener);

        // Turn screen on while brightness mode is manual
        mInjector.setBrightnessMode(/* isBrightnessModeAutomatic */ false);
        mInjector.sendScreenChange(/*screen on */ true);
        assertNull(mInjector.mSensorListener);

        // Set brightness mode to automatic while screen is off.
        mInjector.sendScreenChange(/*screen on */ false);
        mInjector.setBrightnessMode(/* isBrightnessModeAutomatic */ true);
        assertNull(mInjector.mSensorListener);

        // Turn on screen while brightness mode is automatic.
        mInjector.sendScreenChange(/*screen on */ true);
        assertNotNull(mInjector.mSensorListener);

        mTracker.stop();
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mBroadcastReceiver);
        assertFalse(mInjector.mIdleScheduled);
    }

    @Test
    public void testAdaptiveOnOff() {
        mInjector.mInteractive = true;
        mInjector.mIsBrightnessModeAutomatic = false;
        startTracker(mTracker);
        assertNull(mInjector.mSensorListener);
        assertNotNull(mInjector.mBroadcastReceiver);
        assertNotNull(mInjector.mContentObserver);
        assertTrue(mInjector.mIdleScheduled);

        mInjector.setBrightnessMode(/*isBrightnessModeAutomatic*/ true);
        assertNotNull(mInjector.mSensorListener);

        SensorEventListener listener = mInjector.mSensorListener;
        mInjector.mSensorListener = null;
        // Duplicate notification
        mInjector.setBrightnessMode(/*isBrightnessModeAutomatic*/ true);
        // Sensor shouldn't have been registered as it was already registered.
        assertNull(mInjector.mSensorListener);
        mInjector.mSensorListener = listener;

        mInjector.setBrightnessMode(/*isBrightnessModeAutomatic*/ false);
        assertNull(mInjector.mSensorListener);

        mTracker.stop();
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mBroadcastReceiver);
        assertNull(mInjector.mContentObserver);
        assertFalse(mInjector.mIdleScheduled);
    }

    @Test
    public void testBrightnessEvent() {
        final int brightness = 20;

        startTracker(mTracker);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));
        notifyBrightnessChanged(mTracker, brightness);
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(mInjector.currentTimeMillis(), event.timeStamp);
        assertEquals(1, event.luxValues.length);
        assertEquals(1.0f, event.luxValues[0], FLOAT_DELTA);
        assertEquals(mInjector.currentTimeMillis() - TimeUnit.SECONDS.toMillis(2),
                event.luxTimestamps[0]);
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
        assertEquals(DEFAULT_INITIAL_BRIGHTNESS, event.lastBrightness, FLOAT_DELTA);

        // System had no data so these should all be at defaults.
        assertEquals(Float.NaN, event.batteryLevel, 0.0);
        assertFalse(event.nightMode);
        assertEquals(0, event.colorTemperature);
    }

    @Test
    public void testBrightnessFullPopulatedEvent() {
        final int initialBrightness = 230;
        final int brightness = 130;

        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 3333);

        startTracker(mTracker, initialBrightness);
        mInjector.mBroadcastReceiver.onReceive(InstrumentationRegistry.getContext(),
                batteryChangeEvent(30, 60));
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1000.0f));
        final long sensorTime = mInjector.currentTimeMillis();
        notifyBrightnessChanged(mTracker, brightness);
        List<BrightnessChangeEvent> eventsNoPackage
                = mTracker.getEvents(0, false).getList();
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(event.timeStamp, mInjector.currentTimeMillis());
        assertArrayEquals(new float[] {1000.0f}, event.luxValues, 0.01f);
        assertArrayEquals(new long[] {sensorTime}, event.luxTimestamps);
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
        assertEquals(initialBrightness, event.lastBrightness, FLOAT_DELTA);
        assertEquals(0.5, event.batteryLevel, FLOAT_DELTA);
        assertTrue(event.nightMode);
        assertEquals(3333, event.colorTemperature);
        assertEquals("a.package", event.packageName);
        assertEquals(0, event.userId);

        assertEquals(1, eventsNoPackage.size());
        assertNull(eventsNoPackage.get(0).packageName);
    }

    @Test
    public void testIgnoreAutomaticBrightnessChange() {
        final int initialBrightness = 30;
        startTracker(mTracker, initialBrightness);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(1));

        final int systemUpdatedBrightness = 20;
        notifyBrightnessChanged(mTracker, systemUpdatedBrightness, false /*userInitiated*/,
                0.5f /*powerBrightnessFactor(*/, false /*isUserSetBrightness*/,
                false /*isDefaultBrightnessConfig*/);
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        // No events because we filtered out our change.
        assertEquals(0, events.size());

        final int firstUserUpdateBrightness = 20;
        // Then change comes from somewhere else so we shouldn't filter.
        notifyBrightnessChanged(mTracker, firstUserUpdateBrightness);

        // and with a different brightness value.
        final int secondUserUpdateBrightness = 34;
        notifyBrightnessChanged(mTracker, secondUserUpdateBrightness);
        events = mTracker.getEvents(0, true).getList();

        assertEquals(2, events.size());
        // First event is change from system update (20) to first user update (20)
        assertEquals(systemUpdatedBrightness, events.get(0).lastBrightness, FLOAT_DELTA);
        assertEquals(firstUserUpdateBrightness, events.get(0).brightness, FLOAT_DELTA);
        // Second event is from first to second user update.
        assertEquals(firstUserUpdateBrightness, events.get(1).lastBrightness, FLOAT_DELTA);
        assertEquals(secondUserUpdateBrightness, events.get(1).brightness, FLOAT_DELTA);

        mTracker.stop();
    }

    @Test
    public void testLimitedBufferSize() {
        startTracker(mTracker);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));

        for (int brightness = 0; brightness <= 255; ++brightness) {
            mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));
            mInjector.incrementTime(TimeUnit.SECONDS.toNanos(1));
            notifyBrightnessChanged(mTracker, brightness);
        }
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        // Should be capped at 100 events, and they should be the most recent 100.
        assertEquals(100, events.size());
        for (int i = 0; i < events.size(); i++) {
            BrightnessChangeEvent event = events.get(i);
            assertEquals(156 + i, event.brightness, FLOAT_DELTA);
        }
    }

    @Test
    public void testLimitedSensorEvents() {
        final int brightness = 20;

        startTracker(mTracker);
        // 20 Sensor events 1 second apart.
        for (int i = 0; i < 20; ++i) {
            mInjector.incrementTime(TimeUnit.SECONDS.toMillis(1));
            mInjector.mSensorListener.onSensorChanged(createSensorEvent(i + 1.0f));
        }
        notifyBrightnessChanged(mTracker, 20);
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(mInjector.currentTimeMillis(), event.timeStamp);

        // 12 sensor events, 11 for 0->10 seconds + 1 previous event.
        assertEquals(12, event.luxValues.length);
        for (int i = 0; i < 12; ++i) {
            assertEquals(event.luxTimestamps[11 - i],
                    mInjector.currentTimeMillis() - i * TimeUnit.SECONDS.toMillis(1));
        }
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
    }

    @Test
    public void testReadEvents() throws Exception {
        BrightnessTracker tracker = new BrightnessTracker(InstrumentationRegistry.getContext(),
                mInjector);
        mInjector.mCurrentTimeMillis = System.currentTimeMillis();
        long someTimeAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12);
        long twoMonthsAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60);
        // 3 Events in the file but one too old to read.
        String eventFile =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<events>\n"
                + "<event nits=\"194.2\" timestamp=\""
                + Long.toString(someTimeAgo) + "\" packageName=\""
                + "com.example.app\" user=\"10\" "
                + "lastNits=\"32.333\" "
                + "batteryLevel=\"1.0\" nightMode=\"false\" colorTemperature=\"0\"\n"
                + "lux=\"32.2,31.1\" luxTimestamps=\""
                + Long.toString(someTimeAgo) + "," + Long.toString(someTimeAgo) + "\""
                + "defaultConfig=\"true\" powerSaveFactor=\"0.5\" userPoint=\"true\" />"
                + "<event nits=\"71\" timestamp=\""
                + Long.toString(someTimeAgo) + "\" packageName=\""
                + "com.android.anapp\" user=\"11\" "
                + "lastNits=\"32\" "
                + "batteryLevel=\"0.5\" nightMode=\"true\" colorTemperature=\"3235\"\n"
                + "lux=\"132.2,131.1\" luxTimestamps=\""
                + Long.toString(someTimeAgo) + "," + Long.toString(someTimeAgo) + "\"/>"
                // Event that is too old so shouldn't show up.
                + "<event nits=\"142\" timestamp=\""
                + Long.toString(twoMonthsAgo) + "\" packageName=\""
                + "com.example.app\" user=\"10\" "
                + "lastNits=\"32\" "
                + "batteryLevel=\"1.0\" nightMode=\"false\" colorTemperature=\"0\"\n"
                + "lux=\"32.2,31.1\" luxTimestamps=\""
                + Long.toString(twoMonthsAgo) + "," + Long.toString(twoMonthsAgo) + "\"/>"
                + "</events>";
        tracker.readEventsLocked(getInputStream(eventFile));
        List<BrightnessChangeEvent> events = tracker.getEvents(0, true).getList();
        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(someTimeAgo, event.timeStamp);
        assertEquals(194.2, event.brightness, FLOAT_DELTA);
        assertArrayEquals(new float[] {32.2f, 31.1f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {someTimeAgo, someTimeAgo}, event.luxTimestamps);
        assertEquals(32.333, event.lastBrightness, FLOAT_DELTA);
        assertEquals(0, event.userId);
        assertFalse(event.nightMode);
        assertEquals(1.0f, event.batteryLevel, FLOAT_DELTA);
        assertEquals("com.example.app", event.packageName);
        assertTrue(event.isDefaultBrightnessConfig);
        assertEquals(0.5f, event.powerBrightnessFactor, FLOAT_DELTA);
        assertTrue(event.isUserSetBrightness);

        events = tracker.getEvents(1, true).getList();
        assertEquals(1, events.size());
        event = events.get(0);
        assertEquals(someTimeAgo, event.timeStamp);
        assertEquals(71, event.brightness, FLOAT_DELTA);
        assertArrayEquals(new float[] {132.2f, 131.1f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {someTimeAgo, someTimeAgo}, event.luxTimestamps);
        assertEquals(32, event.lastBrightness, FLOAT_DELTA);
        assertEquals(1, event.userId);
        assertTrue(event.nightMode);
        assertEquals(3235, event.colorTemperature);
        assertEquals(0.5f, event.batteryLevel, FLOAT_DELTA);
        assertEquals("com.android.anapp", event.packageName);
        // Not present in the event so default to false.
        assertFalse(event.isDefaultBrightnessConfig);
        assertEquals(1.0, event.powerBrightnessFactor, FLOAT_DELTA);
        assertFalse(event.isUserSetBrightness);

        // Pretend user 1 is a profile of user 0.
        mInjector.mProfiles = new int[]{0, 1};
        events = tracker.getEvents(0, true).getList();
        // Both events should now be returned.
        assertEquals(2, events.size());
        BrightnessChangeEvent userZeroEvent;
        BrightnessChangeEvent userOneEvent;
        if (events.get(0).userId == 0) {
            userZeroEvent = events.get(0);
            userOneEvent = events.get(1);
        } else {
            userZeroEvent = events.get(1);
            userOneEvent = events.get(0);
        }
        assertEquals(0, userZeroEvent.userId);
        assertEquals("com.example.app", userZeroEvent.packageName);
        assertEquals(1, userOneEvent.userId);
        // Events from user 1 should have the package name redacted
        assertNull(userOneEvent.packageName);
    }

    @Test
    public void testFailedRead() {
        String someTimeAgo =
                Long.toString(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12));
        mInjector.mCurrentTimeMillis = System.currentTimeMillis();

        BrightnessTracker tracker = new BrightnessTracker(InstrumentationRegistry.getContext(),
                mInjector);
        String eventFile = "junk in the file";
        try {
            tracker.readEventsLocked(getInputStream(eventFile));
        } catch (IOException e) {
            // Expected;
        }
        assertEquals(0, tracker.getEvents(0, true).getList().size());

        // Missing lux value.
        eventFile =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<events>\n"
                        + "<event nits=\"194\" timestamp=\"" + someTimeAgo + "\" packageName=\""
                        + "com.example.app\" user=\"10\" "
                        + "batteryLevel=\"0.7\" nightMode=\"false\" colorTemperature=\"0\" />\n"
                        + "</events>";
        try {
            tracker.readEventsLocked(getInputStream(eventFile));
        } catch (IOException e) {
            // Expected;
        }
        assertEquals(0, tracker.getEvents(0, true).getList().size());
    }

    @Test
    public void testWriteThenRead() throws Exception {
        final int brightness = 20;

        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 3339);

        startTracker(mTracker);
        mInjector.mBroadcastReceiver.onReceive(InstrumentationRegistry.getContext(),
                batteryChangeEvent(30, 100));
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(2000.0f));
        final long firstSensorTime = mInjector.currentTimeMillis();
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(3000.0f));
        final long secondSensorTime = mInjector.currentTimeMillis();
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(3));
        notifyBrightnessChanged(mTracker, brightness, true /*userInitiated*/,
                0.5f /*powerPolicyDim(*/, true /*hasUserBrightnessPoints*/,
                false /*isDefaultBrightnessConfig*/);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mTracker.writeEventsLocked(baos);
        mTracker.stop();

        baos.flush();
        ByteArrayInputStream input = new ByteArrayInputStream(baos.toByteArray());
        BrightnessTracker tracker = new BrightnessTracker(InstrumentationRegistry.getContext(),
                mInjector);
        tracker.readEventsLocked(input);
        List<BrightnessChangeEvent> events = tracker.getEvents(0, true).getList();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertArrayEquals(new float[] {2000.0f, 3000.0f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {firstSensorTime, secondSensorTime}, event.luxTimestamps);
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
        assertEquals(0.3, event.batteryLevel, FLOAT_DELTA);
        assertTrue(event.nightMode);
        assertEquals(3339, event.colorTemperature);
        assertEquals(0.5f, event.powerBrightnessFactor, FLOAT_DELTA);
        assertTrue(event.isUserSetBrightness);
        assertFalse(event.isDefaultBrightnessConfig);
    }

    @Test
    public void testWritePrunesOldEvents() throws Exception {
        final int brightness = 20;

        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 3339);

        startTracker(mTracker);
        mInjector.mBroadcastReceiver.onReceive(InstrumentationRegistry.getContext(),
                batteryChangeEvent(30, 100));
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1000.0f));
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(1));
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(2000.0f));
        final long sensorTime = mInjector.currentTimeMillis();
        notifyBrightnessChanged(mTracker, brightness);

        // 31 days later
        mInjector.incrementTime(TimeUnit.DAYS.toMillis(31));
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(3000.0f));
        notifyBrightnessChanged(mTracker, brightness);
        final long eventTime = mInjector.currentTimeMillis();

        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        assertEquals(2, events.size());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mTracker.writeEventsLocked(baos);
        events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(eventTime, event.timeStamp);

        // We will keep one of the old sensor events because we keep 1 event outside the window.
        assertArrayEquals(new float[] {2000.0f, 3000.0f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {sensorTime, eventTime}, event.luxTimestamps);
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
        assertEquals(0.3, event.batteryLevel, FLOAT_DELTA);
        assertTrue(event.nightMode);
        assertEquals(3339, event.colorTemperature);
    }

    @Test
    public void testParcelUnParcel() {
        Parcel parcel = Parcel.obtain();
        BrightnessChangeEvent.Builder builder = new BrightnessChangeEvent.Builder();
        builder.setBrightness(23f);
        builder.setTimeStamp(345L);
        builder.setPackageName("com.example");
        builder.setUserId(12);
        float[] luxValues = new float[2];
        luxValues[0] = 3000.0f;
        luxValues[1] = 4000.0f;
        builder.setLuxValues(luxValues);
        long[] luxTimestamps = new long[2];
        luxTimestamps[0] = 325L;
        luxTimestamps[1] = 315L;
        builder.setLuxTimestamps(luxTimestamps);
        builder.setBatteryLevel(0.7f);
        builder.setNightMode(false);
        builder.setColorTemperature(345);
        builder.setLastBrightness(50f);
        BrightnessChangeEvent event = builder.build();

        event.writeToParcel(parcel, 0);
        byte[] parceled = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(parceled, 0, parceled.length);
        parcel.setDataPosition(0);

        BrightnessChangeEvent event2 = BrightnessChangeEvent.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        assertEquals(event.brightness, event2.brightness, FLOAT_DELTA);
        assertEquals(event.timeStamp, event2.timeStamp);
        assertEquals(event.packageName, event2.packageName);
        assertEquals(event.userId, event2.userId);
        assertArrayEquals(event.luxValues, event2.luxValues, FLOAT_DELTA);
        assertArrayEquals(event.luxTimestamps, event2.luxTimestamps);
        assertEquals(event.batteryLevel, event2.batteryLevel, FLOAT_DELTA);
        assertEquals(event.nightMode, event2.nightMode);
        assertEquals(event.colorTemperature, event2.colorTemperature);
        assertEquals(event.lastBrightness, event2.lastBrightness, FLOAT_DELTA);

        parcel = Parcel.obtain();
        builder.setBatteryLevel(Float.NaN);
        event = builder.build();
        event.writeToParcel(parcel, 0);
        parceled = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(parceled, 0, parceled.length);
        parcel.setDataPosition(0);
        event2 = BrightnessChangeEvent.CREATOR.createFromParcel(parcel);
        assertEquals(event.batteryLevel, event2.batteryLevel, FLOAT_DELTA);
    }

    @Test
    public void testNonNullAmbientStats() {
        // getAmbientBrightnessStats should return an empty list rather than null when
        // tracker isn't started or hasn't collected any data.
        ParceledListSlice<AmbientBrightnessDayStats> slice = mTracker.getAmbientBrightnessStats(0);
        assertNotNull(slice);
        assertTrue(slice.getList().isEmpty());
        startTracker(mTracker);
        slice = mTracker.getAmbientBrightnessStats(0);
        assertNotNull(slice);
        assertTrue(slice.getList().isEmpty());
    }

    private InputStream getInputStream(String data) {
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }

    private Intent batteryChangeEvent(int level, int scale) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_BATTERY_CHANGED);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, level);
        intent.putExtra(BatteryManager.EXTRA_SCALE, scale);
        return intent;
    }

    private SensorEvent createSensorEvent(float lux) {
        SensorEvent event;
        try {
            Constructor<SensorEvent> constr =
                    SensorEvent.class.getDeclaredConstructor(Integer.TYPE);
            constr.setAccessible(true);
            event = constr.newInstance(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        event.values[0] = lux;
        event.timestamp = mInjector.mElapsedRealtimeNanos;

        return event;
    }

    private void startTracker(BrightnessTracker tracker) {
        startTracker(tracker, DEFAULT_INITIAL_BRIGHTNESS);
    }

    private void startTracker(BrightnessTracker tracker, float initialBrightness) {
        tracker.start(initialBrightness);
        mInjector.waitForHandler();
    }

    private void notifyBrightnessChanged(BrightnessTracker tracker, float brightness) {
        notifyBrightnessChanged(tracker, brightness, true /*userInitiated*/,
                1.0f /*powerBrightnessFactor*/, false /*isUserSetBrightness*/,
                false /*isDefaultBrightnessConfig*/);
    }

    private void notifyBrightnessChanged(BrightnessTracker tracker, float brightness,
            boolean userInitiated, float powerBrightnessFactor, boolean isUserSetBrightness,
            boolean isDefaultBrightnessConfig) {
        tracker.notifyBrightnessChanged(brightness, userInitiated, powerBrightnessFactor,
                isUserSetBrightness, isDefaultBrightnessConfig);
        mInjector.waitForHandler();
    }

    private static final class Idle implements MessageQueue.IdleHandler {
        private boolean mIdle;

        @Override
        public boolean queueIdle() {
            synchronized (this) {
                mIdle = true;
                notifyAll();
            }
            return false;
        }

        public synchronized void waitForIdle() {
            while (!mIdle) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private class TestInjector extends BrightnessTracker.Injector {
        SensorEventListener mSensorListener;
        BroadcastReceiver mBroadcastReceiver;
        Map<String, Integer> mSecureIntSettings = new HashMap<>();
        long mCurrentTimeMillis = System.currentTimeMillis();
        long mElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        Handler mHandler;
        boolean mIdleScheduled;
        boolean mInteractive = true;
        int[] mProfiles;
        ContentObserver mContentObserver;
        boolean mIsBrightnessModeAutomatic = true;

        public TestInjector(Handler handler) {
            mHandler = handler;
        }

        void incrementTime(long timeMillis) {
            mCurrentTimeMillis += timeMillis;
            mElapsedRealtimeNanos += TimeUnit.MILLISECONDS.toNanos(timeMillis);
        }

        void setBrightnessMode(boolean isBrightnessModeAutomatic) {
          mIsBrightnessModeAutomatic = isBrightnessModeAutomatic;
          mContentObserver.dispatchChange(false, null);
          waitForHandler();
        }

        void sendScreenChange(boolean screenOn) {
            mInteractive = screenOn;
            Intent intent = new Intent();
            intent.setAction(screenOn ? Intent.ACTION_SCREEN_ON : Intent.ACTION_SCREEN_OFF);
            mBroadcastReceiver.onReceive(InstrumentationRegistry.getContext(), intent);
            waitForHandler();
        }

        void waitForHandler() {
            Idle idle = new Idle();
            mHandler.getLooper().getQueue().addIdleHandler(idle);
            mHandler.post(() -> {});
            idle.waitForIdle();
        }

        @Override
        public void registerSensorListener(Context context,
                SensorEventListener sensorListener, Handler handler) {
            mSensorListener = sensorListener;
        }

        @Override
        public void unregisterSensorListener(Context context,
                SensorEventListener sensorListener) {
            mSensorListener = null;
        }

        @Override
        public void registerBrightnessModeObserver(ContentResolver resolver,
                ContentObserver settingsObserver) {
            mContentObserver = settingsObserver;
        }

        @Override
        public void unregisterBrightnessModeObserver(Context context,
                ContentObserver settingsObserver) {
            mContentObserver = null;
        }

        @Override
        public void registerReceiver(Context context,
                BroadcastReceiver shutdownReceiver, IntentFilter shutdownFilter) {
            mBroadcastReceiver = shutdownReceiver;
        }

        @Override
        public void unregisterReceiver(Context context,
                BroadcastReceiver broadcastReceiver) {
            assertEquals(mBroadcastReceiver, broadcastReceiver);
            mBroadcastReceiver = null;
        }

        @Override
        public Handler getBackgroundHandler() {
            return mHandler;
        }

        @Override
        public boolean isBrightnessModeAutomatic(ContentResolver resolver) {
            return mIsBrightnessModeAutomatic;
        }

        @Override
        public int getSecureIntForUser(ContentResolver resolver, String setting, int defaultValue,
                int userId) {
            Integer value = mSecureIntSettings.get(setting);
            if (value == null) {
                return defaultValue;
            } else {
                return value;
            }
        }

        @Override
        public AtomicFile getFile(String filename) {
            // Don't have the test write / read from anywhere.
            return null;
        }

        @Override
        public long currentTimeMillis() {
            return mCurrentTimeMillis;
        }

        @Override
        public long elapsedRealtimeNanos() {
            return mElapsedRealtimeNanos;
        }

        @Override
        public int getUserSerialNumber(UserManager userManager, int userId) {
            return userId + 10;
        }

        @Override
        public int getUserId(UserManager userManager, int userSerialNumber) {
            return userSerialNumber - 10;
        }

        @Override
        public int[] getProfileIds(UserManager userManager, int userId) {
            if (mProfiles != null) {
                return mProfiles;
            } else {
                return new int[]{userId};
            }
        }

        @Override
        public ActivityManager.StackInfo getFocusedStack() throws RemoteException {
            ActivityManager.StackInfo focusedStack = new ActivityManager.StackInfo();
            focusedStack.userId = 0;
            focusedStack.topActivity = new ComponentName("a.package", "a.class");
            return focusedStack;
        }

        @Override
        public void scheduleIdleJob(Context context) {
            // Don't actually schedule jobs during unit tests.
            mIdleScheduled = true;
        }

        @Override
        public void cancelIdleJob(Context context) {
            mIdleScheduled = false;
        }

        @Override
        public boolean isInteractive(Context context) {
            return mInteractive;
        }
    }
}
