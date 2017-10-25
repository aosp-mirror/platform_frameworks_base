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
import android.database.ContentObserver;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
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
    public void testStartStopTracker() {
        startTracker(mTracker);
        assertNotNull(mInjector.mSensorListener);
        assertNotNull(mInjector.mSettingsObserver);
        assertNotNull(mInjector.mBroadcastReceiver);
        mTracker.stop();
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mSettingsObserver);
        assertNull(mInjector.mBroadcastReceiver);
    }

    @Test
    public void testBrightnessEvent() {
        final int brightness = 20;

        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS, brightness);
        startTracker(mTracker);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));
        mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS));
        List<BrightnessChangeEvent> events = mTracker.getEvents(0).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(mInjector.currentTimeMillis(), event.timeStamp);
        assertEquals(1, event.luxValues.length);
        assertEquals(1.0f, event.luxValues[0], 0.1f);
        assertEquals(mInjector.currentTimeMillis() - TimeUnit.SECONDS.toMillis(2),
                event.luxTimestamps[0]);
        assertEquals(brightness, event.brightness);

        // System had no data so these should all be at defaults.
        assertEquals(Float.NaN, event.batteryLevel, 0.0);
        assertFalse(event.nightMode);
        assertEquals(0, event.colorTemperature);
    }

    @Test
    public void testBrightnessFullPopulatedEvent() {
        final int lastBrightness = 230;
        final int brightness = 130;

        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS, lastBrightness);
        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 3333);

        startTracker(mTracker);
        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS, brightness);
        mInjector.mBroadcastReceiver.onReceive(InstrumentationRegistry.getContext(),
                batteryChangeEvent(30, 60));
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1000.0f));
        final long sensorTime = mInjector.currentTimeMillis();
        mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS));
        List<BrightnessChangeEvent> events = mTracker.getEvents(0).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(event.timeStamp, mInjector.currentTimeMillis());
        assertArrayEquals(new float[] {1000.0f}, event.luxValues, 0.01f);
        assertArrayEquals(new long[] {sensorTime}, event.luxTimestamps);
        assertEquals(brightness, event.brightness);
        assertEquals(lastBrightness, event.lastBrightness);
        assertEquals(0.5, event.batteryLevel, 0.01);
        assertTrue(event.nightMode);
        assertEquals(3333, event.colorTemperature);
        assertEquals("a.package", event.packageName);
        assertEquals(0, event.userId);
    }

    @Test
    public void testIgnoreSelfChange() {
        final int initialBrightness = 30;
        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS, initialBrightness);
        startTracker(mTracker);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(1));

        final int systemUpdatedBrightness = 20;
        mTracker.setBrightness(systemUpdatedBrightness, 0);
        assertEquals(systemUpdatedBrightness,
                (int) mInjector.mSystemIntSettings.get(Settings.System.SCREEN_BRIGHTNESS));
        mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS));
        List<BrightnessChangeEvent> events = mTracker.getEvents(0).getList();
        // No events because we filtered out our change.
        assertEquals(0, events.size());

        final int firstUserUpdateBrightness = 20;
        // Then change comes from somewhere else so we shouldn't filter.
        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS,
                firstUserUpdateBrightness);
        mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS));

        // and with a different brightness value.
        final int secondUserUpdateBrightness = 34;
        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS,
                secondUserUpdateBrightness);
        mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS));
        events = mTracker.getEvents(0).getList();

        assertEquals(2, events.size());
        // First event is change from system update (20) to first user update (20)
        assertEquals(systemUpdatedBrightness, events.get(0).lastBrightness);
        assertEquals(firstUserUpdateBrightness, events.get(0).brightness);
        // Second event is from first to second user update.
        assertEquals(firstUserUpdateBrightness, events.get(1).lastBrightness);
        assertEquals(secondUserUpdateBrightness, events.get(1).brightness);

        mTracker.stop();
    }

    @Test
    public void testLimitedBufferSize() {
        startTracker(mTracker);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));

        for (int brightness = 0; brightness <= 255; ++brightness) {
            mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));
            mInjector.incrementTime(TimeUnit.SECONDS.toNanos(1));
            mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS, brightness);
            mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS));
        }
        List<BrightnessChangeEvent> events = mTracker.getEvents(0).getList();
        mTracker.stop();

        // Should be capped at 100 events, and they should be the most recent 100.
        assertEquals(100, events.size());
        for (int i = 0; i < events.size(); i++) {
            BrightnessChangeEvent event = events.get(i);
            assertEquals(156 + i, event.brightness);
        }
    }

    @Test
    public void testLimitedSensorEvents() {
        final int brightness = 20;
        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS, brightness);

        startTracker(mTracker);
        // 20 Sensor events 1 second apart.
        for (int i = 0; i < 20; ++i) {
            mInjector.incrementTime(TimeUnit.SECONDS.toMillis(1));
            mInjector.mSensorListener.onSensorChanged(createSensorEvent(i + 1.0f));
        }
        mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS));
        List<BrightnessChangeEvent> events = mTracker.getEvents(0).getList();
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
        assertEquals(brightness, event.brightness);
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
                + "<event brightness=\"194\" timestamp=\""
                + Long.toString(someTimeAgo) + "\" packageName=\""
                + "com.example.app\" user=\"10\" "
                + "lastBrightness=\"32\" "
                + "batteryLevel=\"1.0\" nightMode=\"false\" colorTemperature=\"0\"\n"
                + "lux=\"32.2,31.1\" luxTimestamps=\""
                + Long.toString(someTimeAgo) + "," + Long.toString(someTimeAgo) + "\"/>"
                + "<event brightness=\"71\" timestamp=\""
                + Long.toString(someTimeAgo) + "\" packageName=\""
                + "com.android.anapp\" user=\"11\" "
                + "lastBrightness=\"32\" "
                + "batteryLevel=\"0.5\" nightMode=\"true\" colorTemperature=\"3235\"\n"
                + "lux=\"132.2,131.1\" luxTimestamps=\""
                + Long.toString(someTimeAgo) + "," + Long.toString(someTimeAgo) + "\"/>"
                // Event that is too old so shouldn't show up.
                + "<event brightness=\"142\" timestamp=\""
                + Long.toString(twoMonthsAgo) + "\" packageName=\""
                + "com.example.app\" user=\"10\" "
                + "lastBrightness=\"32\" "
                + "batteryLevel=\"1.0\" nightMode=\"false\" colorTemperature=\"0\"\n"
                + "lux=\"32.2,31.1\" luxTimestamps=\""
                + Long.toString(twoMonthsAgo) + "," + Long.toString(twoMonthsAgo) + "\"/>"
                + "</events>";
        tracker.readEventsLocked(getInputStream(eventFile));
        List<BrightnessChangeEvent> events = tracker.getEvents(0).getList();
        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(someTimeAgo, event.timeStamp);
        assertEquals(194, event.brightness);
        assertArrayEquals(new float[] {32.2f, 31.1f}, event.luxValues, 0.01f);
        assertArrayEquals(new long[] {someTimeAgo, someTimeAgo}, event.luxTimestamps);
        assertEquals(32, event.lastBrightness);
        assertEquals(0, event.userId);
        assertFalse(event.nightMode);
        assertEquals(1.0f, event.batteryLevel, 0.01);
        assertEquals("com.example.app", event.packageName);

        events = tracker.getEvents(1).getList();
        assertEquals(1, events.size());
        event = events.get(0);
        assertEquals(someTimeAgo, event.timeStamp);
        assertEquals(71, event.brightness);
        assertArrayEquals(new float[] {132.2f, 131.1f}, event.luxValues, 0.01f);
        assertArrayEquals(new long[] {someTimeAgo, someTimeAgo}, event.luxTimestamps);
        assertEquals(32, event.lastBrightness);
        assertEquals(1, event.userId);
        assertTrue(event.nightMode);
        assertEquals(3235, event.colorTemperature);
        assertEquals(0.5f, event.batteryLevel, 0.01);
        assertEquals("com.android.anapp", event.packageName);
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
        assertEquals(0, tracker.getEvents(0).getList().size());

        // Missing lux value.
        eventFile =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<events>\n"
                        + "<event brightness=\"194\" timestamp=\"" + someTimeAgo + "\" packageName=\""
                        + "com.example.app\" user=\"10\" "
                        + "batteryLevel=\"0.7\" nightMode=\"false\" colorTemperature=\"0\" />\n"
                        + "</events>";
        try {
            tracker.readEventsLocked(getInputStream(eventFile));
        } catch (IOException e) {
            // Expected;
        }
        assertEquals(0, tracker.getEvents(0).getList().size());
    }

    @Test
    public void testWriteThenRead() throws Exception {
        final int brightness = 20;

        mInjector.mSystemIntSettings.put(Settings.System.SCREEN_BRIGHTNESS, brightness);
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
        mInjector.mSettingsObserver.onChange(false, Settings.System.getUriFor(
                Settings.System.SCREEN_BRIGHTNESS));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mTracker.writeEventsLocked(baos);
        mTracker.stop();

        baos.flush();
        ByteArrayInputStream input = new ByteArrayInputStream(baos.toByteArray());
        BrightnessTracker tracker = new BrightnessTracker(InstrumentationRegistry.getContext(),
                mInjector);
        tracker.readEventsLocked(input);
        List<BrightnessChangeEvent> events = tracker.getEvents(0).getList();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertArrayEquals(new float[] {2000.0f, 3000.0f}, event.luxValues, 0.01f);
        assertArrayEquals(new long[] {firstSensorTime, secondSensorTime}, event.luxTimestamps);
        assertEquals(brightness, event.brightness);
        assertEquals(0.3, event.batteryLevel, 0.01f);
        assertTrue(event.nightMode);
        assertEquals(3339, event.colorTemperature);
    }

    @Test
    public void testParcelUnParcel() {
        Parcel parcel = Parcel.obtain();
        BrightnessChangeEvent event = new BrightnessChangeEvent();
        event.brightness = 23;
        event.timeStamp = 345L;
        event.packageName = "com.example";
        event.userId = 12;
        event.luxValues = new float[2];
        event.luxValues[0] = 3000.0f;
        event.luxValues[1] = 4000.0f;
        event.luxTimestamps = new long[2];
        event.luxTimestamps[0] = 325L;
        event.luxTimestamps[1] = 315L;
        event.batteryLevel = 0.7f;
        event.nightMode = false;
        event.colorTemperature = 345;
        event.lastBrightness = 50;

        event.writeToParcel(parcel, 0);
        byte[] parceled = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(parceled, 0, parceled.length);
        parcel.setDataPosition(0);

        BrightnessChangeEvent event2 = BrightnessChangeEvent.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        assertEquals(event.brightness, event2.brightness);
        assertEquals(event.timeStamp, event2.timeStamp);
        assertEquals(event.packageName, event2.packageName);
        assertEquals(event.userId, event2.userId);
        assertArrayEquals(event.luxValues, event2.luxValues, 0.01f);
        assertArrayEquals(event.luxTimestamps, event2.luxTimestamps);
        assertEquals(event.batteryLevel, event2.batteryLevel, 0.01f);
        assertEquals(event.nightMode, event2.nightMode);
        assertEquals(event.colorTemperature, event2.colorTemperature);
        assertEquals(event.lastBrightness, event2.lastBrightness);

        parcel = Parcel.obtain();
        event.batteryLevel = Float.NaN;
        event.writeToParcel(parcel, 0);
        parceled = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(parceled, 0, parceled.length);
        parcel.setDataPosition(0);
        event2 = BrightnessChangeEvent.CREATOR.createFromParcel(parcel);
        assertEquals(event.batteryLevel, event2.batteryLevel, 0.01f);
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
        tracker.start();
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
        ContentObserver mSettingsObserver;
        BroadcastReceiver mBroadcastReceiver;
        Map<String, Integer> mSystemIntSettings = new HashMap<>();
        Map<String, Integer> mSecureIntSettings = new HashMap<>();
        long mCurrentTimeMillis = System.currentTimeMillis();
        long mElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        Handler mHandler;

        public TestInjector(Handler handler) {
            mHandler = handler;
        }

        public void incrementTime(long timeMillis) {
            mCurrentTimeMillis += timeMillis;
            mElapsedRealtimeNanos += TimeUnit.MILLISECONDS.toNanos(timeMillis);
        }

        @Override
        public void registerSensorListener(Context context,
                SensorEventListener sensorListener) {
            mSensorListener = sensorListener;
        }

        @Override
        public void unregisterSensorListener(Context context,
                SensorEventListener sensorListener) {
            mSensorListener = null;
        }

        @Override
        public void registerBrightnessObserver(ContentResolver resolver,
                ContentObserver settingsObserver) {
            mSettingsObserver = settingsObserver;
        }

        @Override
        public void unregisterBrightnessObserver(Context context,
                ContentObserver settingsObserver) {
            mSettingsObserver = null;
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

        public void waitForHandler() {
            Idle idle = new Idle();
            mHandler.getLooper().getQueue().addIdleHandler(idle);
            mHandler.post(() -> {});
            idle.waitForIdle();
        }

        @Override
        public int getSystemIntForUser(ContentResolver resolver, String setting, int defaultValue,
                int userId) {
            Integer value = mSystemIntSettings.get(setting);
            if (value == null) {
                return defaultValue;
            } else {
                return value;
            }
        }

        @Override
        public void putSystemIntForUser(ContentResolver resolver, String setting, int value,
                int userId) {
            mSystemIntSettings.put(setting, value);
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
        public AtomicFile getFile() {
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
        public ActivityManager.StackInfo getFocusedStack() throws RemoteException {
            ActivityManager.StackInfo focusedStack = new ActivityManager.StackInfo();
            focusedStack.userId = 0;
            focusedStack.topActivity = new ComponentName("a.package", "a.class");
            return focusedStack;
        }
    }
}
