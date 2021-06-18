/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.display;

import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HIGH_ZONE;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_LOW_ZONE;

import static com.android.server.display.DisplayModeDirector.Vote.PRIORITY_FLICKER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.Preconditions;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.display.DisplayModeDirector.BrightnessObserver;
import com.android.server.display.DisplayModeDirector.DesiredDisplayModeSpecs;
import com.android.server.display.DisplayModeDirector.Vote;
import com.android.server.testutils.FakeDeviceConfigInterface;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayModeDirectorTest {
    // The tolerance within which we consider something approximately equals.
    private static final String TAG = "DisplayModeDirectorTest";
    private static final boolean DEBUG = false;
    private static final float FLOAT_TOLERANCE = 0.01f;

    private Context mContext;
    private FakesInjector mInjector;
    private Handler mHandler;
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        final MockContentResolver resolver = mSettingsProviderRule.mockContentResolver(mContext);
        when(mContext.getContentResolver()).thenReturn(resolver);
        mInjector = new FakesInjector();
        mHandler = new Handler(Looper.getMainLooper());
    }

    private DisplayModeDirector createDirectorFromRefreshRateArray(
            float[] refreshRates, int baseModeId) {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, mHandler, mInjector);
        int displayId = 0;
        Display.Mode[] modes = new Display.Mode[refreshRates.length];
        for (int i = 0; i < refreshRates.length; i++) {
            modes[i] = new Display.Mode(
                    /*modeId=*/baseModeId + i, /*width=*/1000, /*height=*/1000, refreshRates[i]);
        }
        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(displayId, modes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(displayId, modes[0]);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);
        return director;
    }

    private DisplayModeDirector createDirectorFromFpsRange(int minFps, int maxFps) {
        int numRefreshRates = maxFps - minFps + 1;
        float[] refreshRates = new float[numRefreshRates];
        for (int i = 0; i < numRefreshRates; i++) {
            refreshRates[i] = minFps + i;
        }
        return createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/minFps);
    }

    @Test
    public void testDisplayModeVoting() {
        int displayId = 0;

        // With no votes present, DisplayModeDirector should allow any refresh rate.
        DesiredDisplayModeSpecs modeSpecs =
                createDirectorFromFpsRange(60, 90).getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(modeSpecs.baseModeId).isEqualTo(60);
        Truth.assertThat(modeSpecs.primaryRefreshRateRange.min).isEqualTo(0f);
        Truth.assertThat(modeSpecs.primaryRefreshRateRange.max).isEqualTo(Float.POSITIVE_INFINITY);

        int numPriorities =
                DisplayModeDirector.Vote.MAX_PRIORITY - DisplayModeDirector.Vote.MIN_PRIORITY + 1;

        // Ensure vote priority works as expected. As we add new votes with higher priority, they
        // should take precedence over lower priority votes.
        {
            int minFps = 60;
            int maxFps = 90;
            DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
            assertTrue(2 * numPriorities < maxFps - minFps + 1);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(displayId, votes);
            for (int i = 0; i < numPriorities; i++) {
                int priority = Vote.MIN_PRIORITY + i;
                votes.put(priority, Vote.forRefreshRates(minFps + i, maxFps - i));
                director.injectVotesByDisplay(votesByDisplay);
                modeSpecs = director.getDesiredDisplayModeSpecs(displayId);
                Truth.assertThat(modeSpecs.baseModeId).isEqualTo(minFps + i);
                Truth.assertThat(modeSpecs.primaryRefreshRateRange.min)
                        .isEqualTo((float) (minFps + i));
                Truth.assertThat(modeSpecs.primaryRefreshRateRange.max)
                        .isEqualTo((float) (maxFps - i));
            }
        }

        // Ensure lower priority votes are able to influence the final decision, even in the
        // presence of higher priority votes.
        {
            assertTrue(numPriorities >= 2);
            DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(displayId, votes);
            votes.put(Vote.MAX_PRIORITY, Vote.forRefreshRates(65, 85));
            votes.put(Vote.MIN_PRIORITY, Vote.forRefreshRates(70, 80));
            director.injectVotesByDisplay(votesByDisplay);
            modeSpecs = director.getDesiredDisplayModeSpecs(displayId);
            Truth.assertThat(modeSpecs.baseModeId).isEqualTo(70);
            Truth.assertThat(modeSpecs.primaryRefreshRateRange.min).isEqualTo(70f);
            Truth.assertThat(modeSpecs.primaryRefreshRateRange.max).isEqualTo(80f);
        }
    }

    @Test
    public void testVotingWithFloatingPointErrors() {
        int displayId = 0;
        DisplayModeDirector director = createDirectorFromFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(displayId, votes);
        float error = FLOAT_TOLERANCE / 4;
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE, Vote.forRefreshRates(0, 60));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forRefreshRates(60 + error, 60 + error));
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE,
                Vote.forRefreshRates(60 - error, 60 - error));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);

        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.baseModeId).isEqualTo(60);
    }

    @Test
    public void testFlickerHasLowerPriorityThanUser() {
        assertTrue(PRIORITY_FLICKER < Vote.PRIORITY_APP_REQUEST_REFRESH_RATE);
        assertTrue(PRIORITY_FLICKER < Vote.PRIORITY_APP_REQUEST_SIZE);

        int displayId = 0;
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(displayId, votes);
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(60, 90));
        votes.put(PRIORITY_FLICKER, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(60, 90));
        votes.put(PRIORITY_FLICKER, Vote.forRefreshRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(90, 90));
        votes.put(PRIORITY_FLICKER, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(60, 60));
        votes.put(PRIORITY_FLICKER, Vote.forRefreshRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
    }

    @Test
    public void testAppRequestRefreshRateRange() {
        // Confirm that the app request range doesn't include flicker or min refresh rate settings,
        // but does include everything else.
        assertTrue(
                PRIORITY_FLICKER < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE
                < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE
                >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);

        int displayId = 0;
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(displayId, votes);
        votes.put(PRIORITY_FLICKER, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.appRequestRefreshRateRange.min).isAtMost(60f);
        Truth.assertThat(desiredSpecs.appRequestRefreshRateRange.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                Vote.forRefreshRates(90, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isAtLeast(90f);
        Truth.assertThat(desiredSpecs.appRequestRefreshRateRange.min).isAtMost(60f);
        Truth.assertThat(desiredSpecs.appRequestRefreshRateRange.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(75, 75));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(75);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(75);
        Truth.assertThat(desiredSpecs.appRequestRefreshRateRange.min)
                .isWithin(FLOAT_TOLERANCE)
                .of(75);
        Truth.assertThat(desiredSpecs.appRequestRefreshRateRange.max)
                .isWithin(FLOAT_TOLERANCE)
                .of(75);
    }

    void verifySpecsWithRefreshRateSettings(DisplayModeDirector director, float minFps,
            float peakFps, float defaultFps, float primaryMin, float primaryMax,
            float appRequestMin, float appRequestMax) {
        DesiredDisplayModeSpecs specs = director.getDesiredDisplayModeSpecsWithInjectedFpsSettings(
                minFps, peakFps, defaultFps);
        Truth.assertThat(specs.primaryRefreshRateRange.min).isEqualTo(primaryMin);
        Truth.assertThat(specs.primaryRefreshRateRange.max).isEqualTo(primaryMax);
        Truth.assertThat(specs.appRequestRefreshRateRange.min).isEqualTo(appRequestMin);
        Truth.assertThat(specs.appRequestRefreshRateRange.max).isEqualTo(appRequestMax);
    }

    @Test
    public void testSpecsFromRefreshRateSettings() {
        // Confirm that, with varying settings for min, peak, and default refresh rate,
        // DesiredDisplayModeSpecs is calculated correctly.
        float[] refreshRates = {30.f, 60.f, 90.f, 120.f, 150.f};
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/0);

        float inf = Float.POSITIVE_INFINITY;
        verifySpecsWithRefreshRateSettings(director, 0, 0, 0, 0, inf, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 0, 0, 90, 0, 90, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 0, 0, 90, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 60, 0, 60, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 120, 0, 90, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 0, 90, inf, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 120, 90, 120, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 60, 90, inf, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 90, 120, 0, 90, 120, 0, 120);
        verifySpecsWithRefreshRateSettings(director, 90, 60, 0, 90, 90, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 60, 120, 90, 60, 90, 0, 120);
    }

    void verifyBrightnessObserverCall(DisplayModeDirector director, float minFps, float peakFps,
            float defaultFps, float brightnessObserverMin, float brightnessObserverMax) {
        BrightnessObserver brightnessObserver = Mockito.mock(BrightnessObserver.class);
        director.injectBrightnessObserver(brightnessObserver);
        director.getDesiredDisplayModeSpecsWithInjectedFpsSettings(minFps, peakFps, defaultFps);
        verify(brightnessObserver)
                .onRefreshRateSettingChangedLocked(brightnessObserverMin, brightnessObserverMax);
    }

    @Test
    public void testBrightnessObserverCallWithRefreshRateSettings() {
        // Confirm that, with varying settings for min, peak, and default refresh rate, we make the
        // correct call to the brightness observer.
        float[] refreshRates = {60.f, 90.f, 120.f};
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/0);
        verifyBrightnessObserverCall(director, 0, 0, 0, 0, 0);
        verifyBrightnessObserverCall(director, 0, 0, 90, 0, 90);
        verifyBrightnessObserverCall(director, 0, 90, 0, 0, 90);
        verifyBrightnessObserverCall(director, 0, 90, 60, 0, 60);
        verifyBrightnessObserverCall(director, 90, 90, 0, 90, 90);
        verifyBrightnessObserverCall(director, 120, 90, 0, 120, 90);
    }

    @Test
    public void testBrightnessObserverGetsUpdatedRefreshRatesForZone() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, /* baseModeId= */ 0);
        SensorManager sensorManager = createMockSensorManager(createLightSensor());

        final int initialRefreshRate = 60;
        mInjector.getDeviceConfig().setRefreshRateInLowZone(initialRefreshRate);
        director.start(sensorManager);
        assertThat(director.getBrightnessObserver().getRefreshRateInLowZone())
                .isEqualTo(initialRefreshRate);

        final int updatedRefreshRate = 90;
        mInjector.getDeviceConfig().setRefreshRateInLowZone(updatedRefreshRate);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getBrightnessObserver().getRefreshRateInLowZone())
                .isEqualTo(updatedRefreshRate);
    }

    @Test
    public void testBrightnessObserverThresholdsInZone() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, /* baseModeId= */ 0);
        SensorManager sensorManager = createMockSensorManager(createLightSensor());

        final int[] initialDisplayThresholds = { 10 };
        final int[] initialAmbientThresholds = { 20 };

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setLowDisplayBrightnessThresholds(initialDisplayThresholds);
        config.setLowAmbientBrightnessThresholds(initialAmbientThresholds);
        director.start(sensorManager);

        assertThat(director.getBrightnessObserver().getLowDisplayBrightnessThresholds())
                .isEqualTo(initialDisplayThresholds);
        assertThat(director.getBrightnessObserver().getLowAmbientBrightnessThresholds())
                .isEqualTo(initialAmbientThresholds);

        final int[] updatedDisplayThresholds = { 9, 14 };
        final int[] updatedAmbientThresholds = { -1, 19 };
        config.setLowDisplayBrightnessThresholds(updatedDisplayThresholds);
        config.setLowAmbientBrightnessThresholds(updatedAmbientThresholds);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getBrightnessObserver().getLowDisplayBrightnessThresholds())
                .isEqualTo(updatedDisplayThresholds);
        assertThat(director.getBrightnessObserver().getLowAmbientBrightnessThresholds())
                .isEqualTo(updatedAmbientThresholds);
    }

    @Test
    public void testLockFpsForLowZone() throws Exception {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90);
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInLowZone(90);
        config.setLowDisplayBrightnessThresholds(new int[] { 10 });
        config.setLowAmbientBrightnessThresholds(new int[] { 20 });

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);

        director.start(sensorManager);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        Mockito.verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        setBrightness(10);
        // Sensor reads 20 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 20 /*lux*/));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, PRIORITY_FLICKER);
        assertVoteForRefreshRateLocked(vote, 90 /*fps*/);

        setBrightness(125);
        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 1000 /*lux*/));

        vote = director.getVote(Display.DEFAULT_DISPLAY, PRIORITY_FLICKER);
        assertThat(vote).isNull();
    }

    @Test
    public void testLockFpsForHighZone() throws Exception {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90 /*fps*/);
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInHighZone(60);
        config.setHighDisplayBrightnessThresholds(new int[] { 255 });
        config.setHighAmbientBrightnessThresholds(new int[] { 8000 });

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);

        director.start(sensorManager);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        Mockito.verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        setBrightness(100);
        // Sensor reads 2000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 2000));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, PRIORITY_FLICKER);
        assertThat(vote).isNull();

        setBrightness(255);
        // Sensor reads 9000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 9000));

        vote = director.getVote(Display.DEFAULT_DISPLAY, PRIORITY_FLICKER);
        assertVoteForRefreshRateLocked(vote, 60 /*fps*/);
    }

    @Test
    public void testSensorRegistration() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90 /*fps*/);
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);

        director.start(sensorManager);
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        Mockito.verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));

        // Dispaly state changed from On to Doze
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_DOZE);
        Mockito.verify(sensorManager)
                .unregisterListener(listenerCaptor.capture());

        // Dispaly state changed from Doze to On
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);
        Mockito.verify(sensorManager, times(2))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));

    }

    private void assertVoteForRefreshRateLocked(Vote vote, float refreshRate) {
        assertThat(vote).isNotNull();
        final DisplayModeDirector.RefreshRateRange expectedRange =
                new DisplayModeDirector.RefreshRateRange(refreshRate, refreshRate);
        assertThat(vote.refreshRateRange).isEqualTo(expectedRange);
    }

    private static class FakeDeviceConfig extends FakeDeviceConfigInterface {
        @Override
        public String getProperty(String namespace, String name) {
            Preconditions.checkArgument(DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace));
            return super.getProperty(namespace, name);
        }

        @Override
        public void addOnPropertiesChangedListener(
                String namespace,
                Executor executor,
                DeviceConfig.OnPropertiesChangedListener listener) {
            Preconditions.checkArgument(DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace));
            super.addOnPropertiesChangedListener(namespace, executor, listener);
        }

        void setRefreshRateInLowZone(int fps) {
            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_REFRESH_RATE_IN_LOW_ZONE,
                    String.valueOf(fps));
        }

        void setLowDisplayBrightnessThresholds(int[] brightnessThresholds) {
            String thresholds = toPropertyValue(brightnessThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Brightness Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        void setLowAmbientBrightnessThresholds(int[] ambientThresholds) {
            String thresholds = toPropertyValue(ambientThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Ambient Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        void setRefreshRateInHighZone(int fps) {
            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_REFRESH_RATE_IN_HIGH_ZONE,
                    String.valueOf(fps));
        }

        void setHighDisplayBrightnessThresholds(int[] brightnessThresholds) {
            String thresholds = toPropertyValue(brightnessThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Brightness Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        void setHighAmbientBrightnessThresholds(int[] ambientThresholds) {
            String thresholds = toPropertyValue(ambientThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Ambient Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        @NonNull
        private static String toPropertyValue(@NonNull int[] intArray) {
            return Arrays.stream(intArray)
                    .mapToObj(Integer::toString)
                    .collect(Collectors.joining(","));
        }
    }

    private void setBrightness(int brightness) {
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                brightness);
        mInjector.notifyBrightnessChanged();
        waitForIdleSync();
    }

    private void setPeakRefreshRate(float fps) {
        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.PEAK_REFRESH_RATE,
                 fps);
        mInjector.notifyPeakRefreshRateChanged();
        waitForIdleSync();
    }

    private static SensorManager createMockSensorManager(Sensor... sensors) {
        SensorManager sensorManager = Mockito.mock(SensorManager.class);
        when(sensorManager.getSensorList(anyInt())).then((invocation) -> {
            List<Sensor> requestedSensors = new ArrayList<>();
            int type = invocation.getArgument(0);
            for (Sensor sensor : sensors) {
                if (sensor.getType() == type || type == Sensor.TYPE_ALL) {
                    requestedSensors.add(sensor);
                }
            }
            return requestedSensors;
        });

        when(sensorManager.getDefaultSensor(anyInt())).then((invocation) -> {
            int type = invocation.getArgument(0);
            for (Sensor sensor : sensors) {
                if (sensor.getType() == type) {
                    return sensor;
                }
            }
            return null;
        });
        return sensorManager;
    }

    private static Sensor createLightSensor() {
        try {
            return TestUtils.createSensor(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        } catch (Exception e) {
            // There's nothing we can do if this fails, just throw a RuntimeException so that we
            // don't have to mark every function that might call this as throwing Exception
            throw new RuntimeException("Failed to create a light sensor", e);
        }
    }

    private void waitForIdleSync() {
        mHandler.runWithScissors(() -> { }, 500 /*timeout*/);
    }

    static class FakesInjector implements DisplayModeDirector.Injector {
        private final FakeDeviceConfig mDeviceConfig;
        private ContentObserver mBrightnessObserver;
        private ContentObserver mPeakRefreshRateObserver;

        FakesInjector() {
            mDeviceConfig = new FakeDeviceConfig();
        }

        @NonNull
        public FakeDeviceConfig getDeviceConfig() {
            return mDeviceConfig;
        }

        @Override
        public void registerBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            if (mBrightnessObserver != null) {
                throw new IllegalStateException("Tried to register a second brightness observer");
            }
            mBrightnessObserver = observer;
        }

        @Override
        public void unregisterBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mBrightnessObserver = null;
        }

        void notifyBrightnessChanged() {
            if (mBrightnessObserver != null) {
                mBrightnessObserver.dispatchChange(false /*selfChange*/, DISPLAY_BRIGHTNESS_URI);
            }
        }

        @Override
        public void registerPeakRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mPeakRefreshRateObserver = observer;
        }

        void notifyPeakRefreshRateChanged() {
            if (mPeakRefreshRateObserver != null) {
                mPeakRefreshRateObserver.dispatchChange(false /*selfChange*/,
                        PEAK_REFRESH_RATE_URI);
            }
        }
    }
}
