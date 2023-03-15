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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityTaskManager.RootTaskInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.hardware.input.InputSensorInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.MessageQueue;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessTrackerTest {
    private static final float DEFAULT_INITIAL_BRIGHTNESS = 2.5f;
    private static final boolean DEFAULT_COLOR_SAMPLING_ENABLED = true;
    private static final String DEFAULT_DISPLAY_ID = "123";
    private static final float FLOAT_DELTA = 0.01f;

    @Mock private InputSensorInfo mInputSensorInfoMock;

    private BrightnessTracker mTracker;
    private TestInjector mInjector;
    private Sensor mLightSensorFake;

    private static Object sHandlerLock = new Object();
    private static Handler sHandler;
    private static HandlerThread sThread =
            new HandlerThread("brightness.test", android.os.Process.THREAD_PRIORITY_BACKGROUND);

    private int mDefaultNightModeColorTemperature;
    private float mRbcOffsetFactor;

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
        MockitoAnnotations.initMocks(this);
        mInjector = new TestInjector(ensureHandler());
        mLightSensorFake = new Sensor(mInputSensorInfoMock);

        mTracker = new BrightnessTracker(InstrumentationRegistry.getContext(), mInjector);
        mTracker.setLightSensor(mLightSensorFake);
        mDefaultNightModeColorTemperature =
                InstrumentationRegistry.getContext().getResources().getInteger(
                R.integer.config_nightDisplayColorTemperatureDefault);
        mRbcOffsetFactor = InstrumentationRegistry.getContext()
                .getSystemService(ColorDisplayManager.class).getReduceBrightColorsOffsetFactor();
    }

    @Test
    public void testStartStopTrackerScreenOnOff() {
        mInjector.mInteractive = false;
        startTracker(mTracker);
        assertNull(mInjector.mSensorListener);
        assertNotNull(mInjector.mBroadcastReceiver);
        assertTrue(mInjector.mIdleScheduled);
        mInjector.sendScreenChange(/* screenOn= */ true);
        assertNotNull(mInjector.mSensorListener);
        assertTrue(mInjector.mColorSamplingEnabled);

        mInjector.sendScreenChange(/* screenOn= */ false);
        assertNull(mInjector.mSensorListener);
        assertFalse(mInjector.mColorSamplingEnabled);

        // Turn screen on while brightness mode is manual
        mInjector.setBrightnessMode(/* isBrightnessModeAutomatic= */ false);
        mInjector.sendScreenChange(/* screenOn= */ true);
        assertNull(mInjector.mSensorListener);
        assertFalse(mInjector.mColorSamplingEnabled);

        // Set brightness mode to automatic while screen is off.
        mInjector.sendScreenChange(/* screenOn= */ false);
        mInjector.setBrightnessMode(/* isBrightnessModeAutomatic= */ true);
        assertNull(mInjector.mSensorListener);
        assertFalse(mInjector.mColorSamplingEnabled);

        // Turn on screen while brightness mode is automatic.
        mInjector.sendScreenChange(/* screenOn= */ true);
        assertNotNull(mInjector.mSensorListener);
        assertTrue(mInjector.mColorSamplingEnabled);

        mTracker.stop();
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mBroadcastReceiver);
        assertFalse(mInjector.mIdleScheduled);
        assertFalse(mInjector.mColorSamplingEnabled);
    }

    @Test
    public void testModifyBrightnessConfiguration() {
        mInjector.mInteractive = true;
        // Start with tracker not listening for color samples.
        startTracker(mTracker, DEFAULT_INITIAL_BRIGHTNESS, /* collectColorSamples= */ false);
        assertFalse(mInjector.mColorSamplingEnabled);

        // Update brightness config to enabled color sampling.
        mTracker.setBrightnessConfiguration(buildBrightnessConfiguration(
                /* collectColorSamples= */ true));
        mInjector.waitForHandler();
        assertTrue(mInjector.mColorSamplingEnabled);

        // Update brightness config to disable color sampling.
        mTracker.setBrightnessConfiguration(buildBrightnessConfiguration(
                /* collectColorSamples= */ false));
        mInjector.waitForHandler();
        assertFalse(mInjector.mColorSamplingEnabled);

        // Pretend screen is off, update config to turn on color sampling.
        mInjector.sendScreenChange(/* screenOn= */ false);
        mTracker.setBrightnessConfiguration(buildBrightnessConfiguration(
                /* collectColorSamples= */ true));
        mInjector.waitForHandler();
        assertFalse(mInjector.mColorSamplingEnabled);

        // Pretend screen is on.
        mInjector.sendScreenChange(/* screenOn= */ true);
        assertTrue(mInjector.mColorSamplingEnabled);

        mTracker.stop();
        assertFalse(mInjector.mColorSamplingEnabled);
    }

    @Test
    public void testNoColorSampling_WrongPixelFormat() {
        mInjector.mDefaultSamplingAttributes =
                new DisplayedContentSamplingAttributes(
                        0x23,
                        mInjector.mDefaultSamplingAttributes.getDataspace(),
                        mInjector.mDefaultSamplingAttributes.getComponentMask());
        startTracker(mTracker);
        assertFalse(mInjector.mColorSamplingEnabled);
        assertNull(mInjector.mDisplayListener);
    }

    @Test
    public void testNoColorSampling_MissingComponent() {
        mInjector.mDefaultSamplingAttributes =
                new DisplayedContentSamplingAttributes(
                        mInjector.mDefaultSamplingAttributes.getPixelFormat(),
                        mInjector.mDefaultSamplingAttributes.getDataspace(),
                        0x2);
        startTracker(mTracker);
        assertFalse(mInjector.mColorSamplingEnabled);
        assertNull(mInjector.mDisplayListener);
    }

    @Test
    public void testNoColorSampling_NoSupport() {
        mInjector.mDefaultSamplingAttributes = null;
        startTracker(mTracker);
        assertFalse(mInjector.mColorSamplingEnabled);
        assertNull(mInjector.mDisplayListener);
    }

    @Test
    public void testColorSampling_FrameRateChange() {
        startTracker(mTracker);
        assertTrue(mInjector.mColorSamplingEnabled);
        assertNotNull(mInjector.mDisplayListener);
        int noFramesSampled = mInjector.mNoColorSamplingFrames;
        mInjector.mFrameRate = 120.0f;
        // Wrong display
        mInjector.mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY + 10);
        assertEquals(noFramesSampled, mInjector.mNoColorSamplingFrames);
        // Correct display
        mInjector.mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        assertEquals(noFramesSampled * 2, mInjector.mNoColorSamplingFrames);
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
        assertFalse(mInjector.mColorSamplingEnabled);
        assertNull(mInjector.mDisplayListener);

        mInjector.setBrightnessMode(/* isBrightnessModeAutomatic= */ true);
        assertNotNull(mInjector.mSensorListener);
        assertTrue(mInjector.mColorSamplingEnabled);
        assertNotNull(mInjector.mDisplayListener);

        SensorEventListener listener = mInjector.mSensorListener;
        DisplayManager.DisplayListener displayListener = mInjector.mDisplayListener;
        mInjector.mSensorListener = null;
        mInjector.mColorSamplingEnabled = false;
        mInjector.mDisplayListener = null;
        // Duplicate notification
        mInjector.setBrightnessMode(/* isBrightnessModeAutomatic= */ true);
        // Sensor shouldn't have been registered as it was already registered.
        assertNull(mInjector.mSensorListener);
        assertFalse(mInjector.mColorSamplingEnabled);
        assertNull(mInjector.mDisplayListener);
        mInjector.mDisplayListener = displayListener;
        mInjector.mColorSamplingEnabled = true;

        mInjector.setBrightnessMode(/* isBrightnessModeAutomatic= */ false);
        assertNull(mInjector.mSensorListener);
        assertFalse(mInjector.mColorSamplingEnabled);
        assertNull(mInjector.mDisplayListener);

        mTracker.stop();
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mBroadcastReceiver);
        assertNull(mInjector.mContentObserver);
        assertFalse(mInjector.mIdleScheduled);
        assertFalse(mInjector.mColorSamplingEnabled);
        assertNull(mInjector.mDisplayListener);
    }

    @Test
    public void testBrightnessEvent() {
        final float brightness = 0.5f;
        final String displayId = "1234";

        startTracker(mTracker);
        final long sensorTime = TimeUnit.NANOSECONDS.toMillis(mInjector.elapsedRealtimeNanos());
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));
        final long currentTime = mInjector.currentTimeMillis();
        notifyBrightnessChanged(mTracker, brightness, displayId, new float[] {1.0f},
                new long[] {sensorTime});
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(currentTime, event.timeStamp);
        assertEquals(displayId, event.uniqueDisplayId);
        assertEquals(1, event.luxValues.length);
        assertEquals(1.0f, event.luxValues[0], FLOAT_DELTA);
        assertEquals(currentTime - TimeUnit.SECONDS.toMillis(2),
                event.luxTimestamps[0]);
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
        assertEquals(DEFAULT_INITIAL_BRIGHTNESS, event.lastBrightness, FLOAT_DELTA);

        // System had no data so these should all be at defaults.
        assertEquals(Float.NaN, event.batteryLevel, 0.0);
        assertFalse(event.nightMode);
        assertEquals(mDefaultNightModeColorTemperature, event.colorTemperature);
    }

    @Test
    public void testBrightnessFullPopulatedEvent() {
        final int initialBrightness = 230;
        final int brightness = 130;
        final String displayId = "1234";

        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 3333);

        mInjector.mSecureIntSettings.put(Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL, 40);

        startTracker(mTracker, initialBrightness, DEFAULT_COLOR_SAMPLING_ENABLED);
        mInjector.mBroadcastReceiver.onReceive(InstrumentationRegistry.getContext(),
                batteryChangeEvent(30, 60));
        final long currentTime = mInjector.currentTimeMillis();
        notifyBrightnessChanged(mTracker, brightness, displayId, new float[] {1000.0f},
                new long[] {TimeUnit.NANOSECONDS.toMillis(mInjector.elapsedRealtimeNanos())});
        List<BrightnessChangeEvent> eventsNoPackage
                = mTracker.getEvents(0, false).getList();
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(event.timeStamp, currentTime);
        assertEquals(displayId, event.uniqueDisplayId);
        assertArrayEquals(new float[] {1000.0f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {currentTime}, event.luxTimestamps);
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
        assertEquals(initialBrightness, event.lastBrightness, FLOAT_DELTA);
        assertEquals(0.5, event.batteryLevel, FLOAT_DELTA);
        assertTrue(event.nightMode);
        assertEquals(3333, event.colorTemperature);
        assertTrue(event.reduceBrightColors);
        assertEquals(40, event.reduceBrightColorsStrength);
        assertEquals(brightness * mRbcOffsetFactor, event.reduceBrightColorsOffset, FLOAT_DELTA);
        assertEquals("a.package", event.packageName);
        assertEquals(0, event.userId);
        assertArrayEquals(new long[] {1, 10, 100, 1000, 300, 30, 10, 1}, event.colorValueBuckets);
        assertEquals(10000, event.colorSampleDuration);

        assertEquals(1, eventsNoPackage.size());
        assertNull(eventsNoPackage.get(0).packageName);
    }

    @Test
    public void testIgnoreAutomaticBrightnessChange() {
        final int initialBrightness = 30;
        startTracker(mTracker, initialBrightness, DEFAULT_COLOR_SAMPLING_ENABLED);
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(1));

        final int systemUpdatedBrightness = 20;
        notifyBrightnessChanged(mTracker, systemUpdatedBrightness, /* userInitiated= */ false,
                /* powerBrightnessFactor= */ 0.5f, /* isUserSetBrightness= */ false,
                /* isDefaultBrightnessConfig= */ false, DEFAULT_DISPLAY_ID);
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

        for (int brightness = 0; brightness <= 255; ++brightness) {
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
                + "batteryLevel=\"1.0\" nightMode=\"false\" colorTemperature=\"0\" "
                + "reduceBrightColors=\"false\" reduceBrightColorsStrength=\"40\" "
                + "reduceBrightColorsOffset=\"0\"\n"
                + "uniqueDisplayId=\"123\""
                + "lux=\"32.2,31.1\" luxTimestamps=\""
                + Long.toString(someTimeAgo) + "," + Long.toString(someTimeAgo) + "\""
                + "defaultConfig=\"true\" powerSaveFactor=\"0.5\" userPoint=\"true\" />"
                + "<event nits=\"71\" timestamp=\""
                + Long.toString(someTimeAgo) + "\" packageName=\""
                + "com.android.anapp\" user=\"11\" "
                + "lastNits=\"32\" "
                + "batteryLevel=\"0.5\" nightMode=\"true\" colorTemperature=\"3235\" "
                + "reduceBrightColors=\"true\" reduceBrightColorsStrength=\"40\" "
                + "reduceBrightColorsOffset=\"0\"\n"
                + "uniqueDisplayId=\"456\""
                + "lux=\"132.2,131.1\" luxTimestamps=\""
                + Long.toString(someTimeAgo) + "," + Long.toString(someTimeAgo) + "\""
                + "colorSampleDuration=\"3456\" colorValueBuckets=\"123,598,23,19\"/>"
                // Event that is too old so shouldn't show up.
                + "<event nits=\"142\" timestamp=\""
                + Long.toString(twoMonthsAgo) + "\" packageName=\""
                + "com.example.app\" user=\"10\" "
                + "lastNits=\"32\" "
                + "batteryLevel=\"1.0\" nightMode=\"false\" colorTemperature=\"0\" "
                + "reduceBrightColors=\"false\" reduceBrightColorsStrength=\"40\" "
                + "reduceBrightColorsOffset=\"0\"\n"
                + "uniqueDisplayId=\"789\""
                + "lux=\"32.2,31.1\" luxTimestamps=\""
                + Long.toString(twoMonthsAgo) + "," + Long.toString(twoMonthsAgo) + "\"/>"
                + "</events>";
        tracker.readEventsLocked(getInputStream(eventFile));
        List<BrightnessChangeEvent> events = tracker.getEvents(0, true).getList();
        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(someTimeAgo, event.timeStamp);
        assertEquals(194.2, event.brightness, FLOAT_DELTA);
        assertEquals("123", event.uniqueDisplayId);
        assertArrayEquals(new float[] {32.2f, 31.1f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {someTimeAgo, someTimeAgo}, event.luxTimestamps);
        assertEquals(32.333, event.lastBrightness, FLOAT_DELTA);
        assertEquals(0, event.userId);
        assertFalse(event.nightMode);
        assertFalse(event.reduceBrightColors);
        assertEquals(1.0f, event.batteryLevel, FLOAT_DELTA);
        assertEquals("com.example.app", event.packageName);
        assertTrue(event.isDefaultBrightnessConfig);
        assertEquals(0.5f, event.powerBrightnessFactor, FLOAT_DELTA);
        assertTrue(event.isUserSetBrightness);
        assertNull(event.colorValueBuckets);

        events = tracker.getEvents(1, true).getList();
        assertEquals(1, events.size());
        event = events.get(0);
        assertEquals(someTimeAgo, event.timeStamp);
        assertEquals(71, event.brightness, FLOAT_DELTA);
        assertEquals("456", event.uniqueDisplayId);
        assertArrayEquals(new float[] {132.2f, 131.1f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {someTimeAgo, someTimeAgo}, event.luxTimestamps);
        assertEquals(32, event.lastBrightness, FLOAT_DELTA);
        assertEquals(1, event.userId);
        assertTrue(event.nightMode);
        assertEquals(3235, event.colorTemperature);
        assertTrue(event.reduceBrightColors);
        assertEquals(0.5f, event.batteryLevel, FLOAT_DELTA);
        assertEquals("com.android.anapp", event.packageName);
        // Not present in the event so default to false.
        assertFalse(event.isDefaultBrightnessConfig);
        assertEquals(1.0, event.powerBrightnessFactor, FLOAT_DELTA);
        assertFalse(event.isUserSetBrightness);
        assertEquals(3456L, event.colorSampleDuration);
        assertArrayEquals(new long[] {123L, 598L, 23L, 19L}, event.colorValueBuckets);

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
        final String displayId = "1234";

        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 3339);

        mInjector.mSecureIntSettings.put(Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 1);
        mInjector.mSecureIntSettings.put(Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL, 40);

        startTracker(mTracker);
        mInjector.mBroadcastReceiver.onReceive(InstrumentationRegistry.getContext(),
                batteryChangeEvent(30, 100));
        final long elapsedTime1 = TimeUnit.NANOSECONDS.toMillis(mInjector.elapsedRealtimeNanos());
        final long currentTime1 = mInjector.currentTimeMillis();
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));
        final long elapsedTime2 = TimeUnit.NANOSECONDS.toMillis(mInjector.elapsedRealtimeNanos());
        final long currentTime2 = mInjector.currentTimeMillis();
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(3));
        notifyBrightnessChanged(mTracker, brightness, /* userInitiated= */ true,
                /* powerBrightnessFactor= */ 0.5f, /* isUserSetBrightness= */ true,
                /* isDefaultBrightnessConfig= */ false, displayId, new float[] {2000.0f, 3000.0f},
                new long[] {elapsedTime1, elapsedTime2});
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
        assertEquals(displayId, event.uniqueDisplayId);
        assertArrayEquals(new float[] {2000.0f, 3000.0f}, event.luxValues, FLOAT_DELTA);
        assertArrayEquals(new long[] {currentTime1, currentTime2}, event.luxTimestamps);
        assertEquals(brightness, event.brightness, FLOAT_DELTA);
        assertEquals(0.3, event.batteryLevel, FLOAT_DELTA);
        assertTrue(event.nightMode);
        assertEquals(3339, event.colorTemperature);
        assertTrue(event.reduceBrightColors);
        assertEquals(40, event.reduceBrightColorsStrength);
        assertEquals(brightness * mRbcOffsetFactor, event.reduceBrightColorsOffset, FLOAT_DELTA);
        assertEquals(0.5f, event.powerBrightnessFactor, FLOAT_DELTA);
        assertTrue(event.isUserSetBrightness);
        assertFalse(event.isDefaultBrightnessConfig);
        assertArrayEquals(new long[] {1, 10, 100, 1000, 300, 30, 10, 1}, event.colorValueBuckets);
        assertEquals(10000, event.colorSampleDuration);
    }

    @Test
    public void testParcelUnParcel() {
        Parcel parcel = Parcel.obtain();
        BrightnessChangeEvent.Builder builder = new BrightnessChangeEvent.Builder();
        builder.setBrightness(23f);
        builder.setTimeStamp(345L);
        builder.setPackageName("com.example");
        builder.setUserId(12);
        builder.setUniqueDisplayId("9876");
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
        builder.setReduceBrightColors(false);
        builder.setReduceBrightColorsStrength(40);
        builder.setReduceBrightColorsOffset(20f);
        builder.setLastBrightness(50f);
        builder.setColorValues(new long[] {23, 34, 45}, 1000L);
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
        assertEquals(event.uniqueDisplayId, event2.uniqueDisplayId);
        assertArrayEquals(event.luxValues, event2.luxValues, FLOAT_DELTA);
        assertArrayEquals(event.luxTimestamps, event2.luxTimestamps);
        assertEquals(event.batteryLevel, event2.batteryLevel, FLOAT_DELTA);
        assertEquals(event.nightMode, event2.nightMode);
        assertEquals(event.colorTemperature, event2.colorTemperature);
        assertEquals(event.reduceBrightColors, event2.reduceBrightColors);
        assertEquals(event.reduceBrightColorsStrength, event2.reduceBrightColorsStrength);
        assertEquals(event.reduceBrightColorsOffset, event2.reduceBrightColorsOffset, FLOAT_DELTA);
        assertEquals(event.lastBrightness, event2.lastBrightness, FLOAT_DELTA);
        assertArrayEquals(event.colorValueBuckets, event2.colorValueBuckets);
        assertEquals(event.colorSampleDuration, event2.colorSampleDuration);

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

    @Test
    public void testBackgroundHandlerDelay() {
        final int brightness = 20;

        // Setup tracker.
        startTracker(mTracker);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1.0f));
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));

        // Block handler from running.
        final CountDownLatch latch = new CountDownLatch(1);
        mInjector.mHandler.post(
                () -> {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        fail(e.getMessage());
                    }
                });

        // Send an event.
        long eventTime = mInjector.currentTimeMillis();
        mTracker.notifyBrightnessChanged(brightness, /* userInitiated= */ true,
                /* powerBrightnessFactor= */ 1.0f, /* isUserSetBrightness= */ false,
                /* isDefaultBrightnessConfig= */ false, DEFAULT_DISPLAY_ID, new float[10],
                new long[10]);

        // Time passes before handler can run.
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));

        // Let the handler run.
        latch.countDown();
        mInjector.waitForHandler();

        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        mTracker.stop();

        // Check event was recorded with time it was sent rather than handler ran.
        assertEquals(1, events.size());
        BrightnessChangeEvent event = events.get(0);
        assertEquals(eventTime, event.timeStamp);
    }

    @Test
    public void testDisplayIdChange() {
        float firstBrightness = 0.5f;
        float secondBrightness = 0.75f;
        String firstDisplayId = "123";
        String secondDisplayId = "456";

        startTracker(mTracker);
        mInjector.mSensorListener.onSensorChanged(createSensorEvent(1000.0f));

        notifyBrightnessChanged(mTracker, firstBrightness, firstDisplayId);
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));
        List<BrightnessChangeEvent> events = mTracker.getEvents(0, true).getList();
        assertEquals(1, events.size());
        BrightnessChangeEvent firstEvent = events.get(0);
        assertEquals(firstDisplayId, firstEvent.uniqueDisplayId);
        assertEquals(firstBrightness, firstEvent.brightness, 0.001f);

        notifyBrightnessChanged(mTracker, secondBrightness, secondDisplayId);
        mInjector.incrementTime(TimeUnit.SECONDS.toMillis(2));
        events = mTracker.getEvents(0, true).getList();
        assertEquals(2, events.size());
        BrightnessChangeEvent secondEvent = events.get(1);
        assertEquals(secondDisplayId, secondEvent.uniqueDisplayId);
        assertEquals(secondBrightness, secondEvent.brightness, 0.001f);

        mTracker.stop();
    }

    @Test
    public void testLightSensorChange() {
        // verify the tracker started correctly and a listener registered
        startTracker(mTracker);
        assertNotNull(mInjector.mSensorListener);
        assertEquals(mInjector.mLightSensor, mLightSensorFake);

        // Setting the sensor to null should stop the registered listener.
        mTracker.setLightSensor(null);
        mInjector.waitForHandler();
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mLightSensor);

        // Resetting sensor should start listener again
        mTracker.setLightSensor(mLightSensorFake);
        mInjector.waitForHandler();
        assertNotNull(mInjector.mSensorListener);
        assertEquals(mInjector.mLightSensor, mLightSensorFake);

        Sensor secondSensor = new Sensor(mInputSensorInfoMock);
        // Setting a different listener should keep things working
        mTracker.setLightSensor(secondSensor);
        mInjector.waitForHandler();
        assertNotNull(mInjector.mSensorListener);
        assertEquals(mInjector.mLightSensor, secondSensor);
    }

    @Test
    public void testSetLightSensorDoesntStartListener() {
        mTracker.setLightSensor(mLightSensorFake);
        assertNull(mInjector.mSensorListener);
    }

    @Test
    public void testNullLightSensorWontRegister() {
        mTracker.setLightSensor(null);
        startTracker(mTracker);
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mLightSensor);
    }

    @Test
    public void testOnlyOneReceiverRegistered() {
        assertNull(mInjector.mLightSensor);
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mContentObserver);
        assertNull(mInjector.mBroadcastReceiver);
        assertFalse(mInjector.mIdleScheduled);
        startTracker(mTracker, 0.3f, false);

        assertNotNull(mInjector.mLightSensor);
        assertNotNull(mInjector.mSensorListener);
        assertNotNull(mInjector.mContentObserver);
        assertNotNull(mInjector.mBroadcastReceiver);
        assertTrue(mInjector.mIdleScheduled);
        Sensor registeredLightSensor = mInjector.mLightSensor;
        SensorEventListener registeredSensorListener = mInjector.mSensorListener;
        ContentObserver registeredContentObserver = mInjector.mContentObserver;
        BroadcastReceiver registeredBroadcastReceiver = mInjector.mBroadcastReceiver;

        mTracker.start(0.3f);
        assertSame(registeredLightSensor, mInjector.mLightSensor);
        assertSame(registeredSensorListener, mInjector.mSensorListener);
        assertSame(registeredContentObserver, mInjector.mContentObserver);
        assertSame(registeredBroadcastReceiver, mInjector.mBroadcastReceiver);

        mTracker.stop();
        assertNull(mInjector.mLightSensor);
        assertNull(mInjector.mSensorListener);
        assertNull(mInjector.mContentObserver);
        assertNull(mInjector.mBroadcastReceiver);
        assertFalse(mInjector.mIdleScheduled);

        // mInjector asserts that we aren't removing a null receiver
        mTracker.stop();
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
        startTracker(tracker, DEFAULT_INITIAL_BRIGHTNESS,  DEFAULT_COLOR_SAMPLING_ENABLED);
    }

    private void startTracker(BrightnessTracker tracker, float initialBrightness,
            boolean collectColorSamples) {
        tracker.start(initialBrightness);
        tracker.setBrightnessConfiguration(buildBrightnessConfiguration(collectColorSamples));
        mInjector.waitForHandler();
    }

    private void notifyBrightnessChanged(BrightnessTracker tracker, float brightness) {
        notifyBrightnessChanged(tracker, brightness, DEFAULT_DISPLAY_ID);
    }

    private void notifyBrightnessChanged(BrightnessTracker tracker, float brightness,
            String displayId) {
        notifyBrightnessChanged(tracker, brightness, /* userInitiated= */ true,
                /* powerBrightnessFactor= */ 1.0f, /* isUserSetBrightness= */ false,
                /* isDefaultBrightnessConfig= */ false, displayId, new float[10], new long[10]);
    }

    private void notifyBrightnessChanged(BrightnessTracker tracker, float brightness,
            String displayId, float[] luxValues, long[] luxTimestamps) {
        notifyBrightnessChanged(tracker, brightness, /* userInitiated= */ true,
                /* powerBrightnessFactor= */ 1.0f, /* isUserSetBrightness= */ false,
                /* isDefaultBrightnessConfig= */ false, displayId, luxValues, luxTimestamps);
    }

    private void notifyBrightnessChanged(BrightnessTracker tracker, float brightness,
            boolean userInitiated, float powerBrightnessFactor, boolean isUserSetBrightness,
            boolean isDefaultBrightnessConfig, String displayId) {
        tracker.notifyBrightnessChanged(brightness, userInitiated, powerBrightnessFactor,
                isUserSetBrightness, isDefaultBrightnessConfig, displayId, new float[10],
                new long[10]);
        mInjector.waitForHandler();
    }

    private void notifyBrightnessChanged(BrightnessTracker tracker, float brightness,
            boolean userInitiated, float powerBrightnessFactor, boolean isUserSetBrightness,
            boolean isDefaultBrightnessConfig, String displayId, float[] luxValues,
            long[] luxTimestamps) {
        tracker.notifyBrightnessChanged(brightness, userInitiated, powerBrightnessFactor,
                isUserSetBrightness, isDefaultBrightnessConfig, displayId, luxValues,
                luxTimestamps);
        mInjector.waitForHandler();
    }

    private BrightnessConfiguration buildBrightnessConfiguration(boolean collectColorSamples) {
        BrightnessConfiguration.Builder builder = new BrightnessConfiguration.Builder(
                /* lux= */ new float[] {0f, 10f, 100f},
                /* nits= */ new float[] {1f, 90f, 100f});
        builder.setShouldCollectColorSamples(collectColorSamples);
        return builder.build();
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
        Sensor mLightSensor;
        BroadcastReceiver mBroadcastReceiver;
        DisplayManager.DisplayListener mDisplayListener;
        Map<String, Integer> mSecureIntSettings = new HashMap<>();
        long mCurrentTimeMillis = System.currentTimeMillis();
        long mElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        Handler mHandler;
        boolean mIdleScheduled;
        boolean mInteractive = true;
        int[] mProfiles;
        ContentObserver mContentObserver;
        boolean mIsBrightnessModeAutomatic = true;
        boolean mColorSamplingEnabled = false;
        DisplayedContentSamplingAttributes mDefaultSamplingAttributes =
                new DisplayedContentSamplingAttributes(0x37, 0, 0x4);
        float mFrameRate = 60.0f;
        int mNoColorSamplingFrames;


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
                SensorEventListener sensorListener, Sensor lightSensor, Handler handler) {
            mSensorListener = sensorListener;
            mLightSensor = lightSensor;
        }

        @Override
        public void unregisterSensorListener(Context context,
                SensorEventListener sensorListener) {
            mSensorListener = null;
            mLightSensor = null;
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
        public AtomicFile getLegacyFile(String filename) {
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
        public RootTaskInfo getFocusedStack() throws RemoteException {
            RootTaskInfo focusedStack = new RootTaskInfo();
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

        @Override
        public int getNightDisplayColorTemperature(Context context) {
          return mSecureIntSettings.getOrDefault(Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                  mDefaultNightModeColorTemperature);
        }

        @Override
        public boolean isNightDisplayActivated(Context context) {
            return mSecureIntSettings.getOrDefault(Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                    0) == 1;
        }

        @Override
        public int getReduceBrightColorsStrength(Context context) {
            return mSecureIntSettings.getOrDefault(Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                    0);
        }

        @Override
        public boolean isReduceBrightColorsActivated(Context context) {
            return mSecureIntSettings.getOrDefault(Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                    0) == 1;
        }

        @Override
        public DisplayedContentSample sampleColor(int noFramesToSample) {
            return new DisplayedContentSample(600L,
                    null,
                    null,
                     new long[] {1, 10, 100, 1000, 300, 30, 10, 1},
                    null);
        }

        @Override
        public float getFrameRate(Context context) {
            return mFrameRate;
        }

        @Override
        public DisplayedContentSamplingAttributes getSamplingAttributes() {
            return mDefaultSamplingAttributes;
        }

        @Override
        public boolean enableColorSampling(boolean enable, int noFrames) {
            mColorSamplingEnabled = enable;
            mNoColorSamplingFrames = noFrames;
            return true;
        }

        @Override
        public void registerDisplayListener(Context context,
                DisplayManager.DisplayListener listener, Handler handler) {
            mDisplayListener = listener;
        }

        @Override
        public void unRegisterDisplayListener(Context context,
                DisplayManager.DisplayListener listener) {
            mDisplayListener = null;
        }
    }
}
