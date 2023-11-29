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

package com.android.server.display.mode;

import static android.hardware.display.DisplayManager.DeviceConfig.KEY_BRIGHTNESS_THROTTLING_DATA;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HBM_HDR;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HBM_SUNLIGHT;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HIGH_ZONE;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_LOW_ZONE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.RefreshRateLimitation;
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Temperature;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl.RefreshRateRange;
import android.view.SurfaceControl.RefreshRateRanges;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.display.RefreshRateSettingsUtils;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.TestUtils;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.mode.DisplayModeDirector.BrightnessObserver;
import com.android.server.display.mode.DisplayModeDirector.DesiredDisplayModeSpecs;
import com.android.server.sensors.SensorManagerInternal;
import com.android.server.sensors.SensorManagerInternal.ProximityActiveListener;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.testutils.FakeDeviceConfigInterface;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SmallTest
@RunWith(JUnitParamsRunner.class)
public class DisplayModeDirectorTest {
    public static Collection<Object[]> getAppRequestedSizeTestCases() {
        var appRequestedSizeTestCases = Arrays.asList(new Object[][] {
                {/*expectedBaseModeId*/ DEFAULT_MODE_75.getModeId(),
                        /*expectedPhysicalRefreshRate*/ Float.POSITIVE_INFINITY,
                        /*expectedAppRequestedRefreshRate*/ DEFAULT_MODE_75.getRefreshRate(),
                        /*votesWithPriorities*/ Map.of()},
                {/*expectedBaseModeId*/ APP_MODE_HIGH_90.getModeId(),
                        /*expectedPhysicalRefreshRate*/ Float.POSITIVE_INFINITY,
                        /*expectedAppRequestedRefreshRate*/ APP_MODE_HIGH_90.getRefreshRate(),
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_HIGH_90.getPhysicalWidth(),
                                        APP_MODE_HIGH_90.getPhysicalHeight()),
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_HIGH_90.getRefreshRate()))},
                {/*expectedBaseModeId*/ LIMIT_MODE_70.getModeId(),
                        /*expectedPhysicalRefreshRate*/ Float.POSITIVE_INFINITY,
                        /*expectedAppRequestedRefreshRate*/ Float.POSITIVE_INFINITY,
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_HIGH_90.getPhysicalWidth(),
                                        APP_MODE_HIGH_90.getPhysicalHeight()),
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_HIGH_90.getRefreshRate()),
                                Vote.PRIORITY_LOW_POWER_MODE,
                                Vote.forSize(LIMIT_MODE_70.getPhysicalWidth(),
                                        LIMIT_MODE_70.getPhysicalHeight()))},
                {/*expectedBaseModeId*/ LIMIT_MODE_70.getModeId(),
                        /*expectedPhysicalRefreshRate*/ LIMIT_MODE_70.getRefreshRate(),
                        /*expectedAppRequestedRefreshRate*/ LIMIT_MODE_70.getRefreshRate(),
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_65.getPhysicalWidth(),
                                        APP_MODE_65.getPhysicalHeight()),
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_65.getRefreshRate()),
                                Vote.PRIORITY_LOW_POWER_MODE,
                                Vote.forSize(LIMIT_MODE_70.getPhysicalWidth(),
                                        LIMIT_MODE_70.getPhysicalHeight()))},
                {/*expectedBaseModeId*/ LIMIT_MODE_70.getModeId(),
                        /*expectedPhysicalRefreshRate*/ LIMIT_MODE_70.getRefreshRate(),
                        /*expectedAppRequestedRefreshRate*/ LIMIT_MODE_70.getRefreshRate(),
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_65.getPhysicalWidth(),
                                        APP_MODE_65.getPhysicalHeight()),
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_65.getRefreshRate()),
                                Vote.PRIORITY_LOW_POWER_MODE,
                                Vote.forSizeAndPhysicalRefreshRatesRange(
                                    0, 0,
                                    LIMIT_MODE_70.getPhysicalWidth(),
                                    LIMIT_MODE_70.getPhysicalHeight(),
                                    0, Float.POSITIVE_INFINITY)),
                        /*displayResolutionRangeVotingEnabled*/ false},
                {/*expectedBaseModeId*/ APP_MODE_65.getModeId(),
                        /*expectedPhysicalRefreshRate*/ APP_MODE_65.getRefreshRate(),
                        /*expectedAppRequestedRefreshRate*/ APP_MODE_65.getRefreshRate(),
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_65.getPhysicalWidth(),
                                        APP_MODE_65.getPhysicalHeight()),
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_65.getRefreshRate()),
                                Vote.PRIORITY_LOW_POWER_MODE,
                                Vote.forSizeAndPhysicalRefreshRatesRange(
                                    0, 0,
                                    LIMIT_MODE_70.getPhysicalWidth(),
                                    LIMIT_MODE_70.getPhysicalHeight(),
                                    0, Float.POSITIVE_INFINITY)),
                        /*displayResolutionRangeVotingEnabled*/ true},
                {/*expectedBaseModeId*/ DEFAULT_MODE_75.getModeId(),
                        /*expectedPhysicalRefreshRate*/ APP_MODE_65.getRefreshRate(),
                        /*expectedAppRequestedRefreshRate*/ APP_MODE_HIGH_90.getRefreshRate(),
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_HIGH_90.getPhysicalWidth(),
                                        APP_MODE_HIGH_90.getPhysicalHeight()),
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_HIGH_90.getRefreshRate()),
                                Vote.PRIORITY_LOW_POWER_MODE,
                                Vote.forSizeAndPhysicalRefreshRatesRange(
                                    0, 0,
                                    LIMIT_MODE_70.getPhysicalWidth(),
                                    LIMIT_MODE_70.getPhysicalHeight(),
                                    0, APP_MODE_65.getRefreshRate())),
                        /*displayResolutionRangeVotingEnabled*/ false},
                {/*expectedBaseModeId*/ DEFAULT_MODE_60.getModeId(), // Resolution == APP_MODE_65
                        /*expectedPhysicalRefreshRate*/ APP_MODE_65.getRefreshRate(),
                        /*expectedAppRequestedRefreshRate*/ APP_MODE_65.getRefreshRate(),
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_HIGH_90.getPhysicalWidth(),
                                        APP_MODE_HIGH_90.getPhysicalHeight()),
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_HIGH_90.getRefreshRate()),
                                Vote.PRIORITY_LOW_POWER_MODE,
                                Vote.forSizeAndPhysicalRefreshRatesRange(
                                    0, 0,
                                    LIMIT_MODE_70.getPhysicalWidth(),
                                    LIMIT_MODE_70.getPhysicalHeight(),
                                    0, APP_MODE_65.getRefreshRate())),
                        /*displayResolutionRangeVotingEnabled*/ true},
                {/*expectedBaseModeId*/ APP_MODE_65.getModeId(),
                        /*expectedPhysicalRefreshRate*/ 64.99f,
                        /*expectedAppRequestedRefreshRate*/ 64.99f,
                        /*votesWithPriorities*/ Map.of(
                                Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                                Vote.forBaseModeRefreshRate(APP_MODE_65.getRefreshRate()),
                                Vote.PRIORITY_APP_REQUEST_SIZE,
                                Vote.forSize(APP_MODE_65.getPhysicalWidth(),
                                        APP_MODE_65.getPhysicalHeight()),
                                Vote.PRIORITY_LOW_POWER_MODE,
                                Vote.forPhysicalRefreshRates(
                                        0, 64.99f))}});

        final var res = new ArrayList<Object[]>(appRequestedSizeTestCases.size() * 2);

        // Add additional argument for displayResolutionRangeVotingEnabled=false if not present.
        for (var testCaseArrayArgs : appRequestedSizeTestCases) {
            if (testCaseArrayArgs.length == 4) {
                var testCaseListArgs = new ArrayList<>(Arrays.asList(testCaseArrayArgs));
                testCaseListArgs.add(/* displayResolutionRangeVotingEnabled */ false);
                res.add(testCaseListArgs.toArray());
            } else {
                res.add(testCaseArrayArgs);
            }
        }

        // Add additional argument for displayResolutionRangeVotingEnabled=true if not present.
        for (var testCaseArrayArgs : appRequestedSizeTestCases) {
            if (testCaseArrayArgs.length == 4) {
                var testCaseListArgs = new ArrayList<>(Arrays.asList(testCaseArrayArgs));
                testCaseListArgs.add(/* displayResolutionRangeVotingEnabled */ true);
                res.add(testCaseListArgs.toArray());
            }
        }

        return res;
    }

    private static final String TAG = "DisplayModeDirectorTest";
    private static final boolean DEBUG = false;
    private static final float FLOAT_TOLERANCE = 0.01f;

    private static final Display.Mode DEFAULT_MODE_60 = new Display.Mode(
            /*modeId=*/60, /*width=*/1900, /*height=*/1900, 60);
    private static final Display.Mode APP_MODE_65 = new Display.Mode(
            /*modeId=*/65, /*width=*/1900, /*height=*/1900, 65);
    private static final Display.Mode LIMIT_MODE_70 = new Display.Mode(
            /*modeId=*/70, /*width=*/2000, /*height=*/2000, 70);
    private static final Display.Mode DEFAULT_MODE_75 = new Display.Mode(
            /*modeId=*/75, /*width=*/2500, /*height=*/2500, 75);
    private static final Display.Mode APP_MODE_HIGH_90 = new Display.Mode(
            /*modeId=*/90, /*width=*/3000, /*height=*/3000, 90);
    private static final Display.Mode[] TEST_MODES = new Display.Mode[] {
        DEFAULT_MODE_60,
        APP_MODE_65,
        LIMIT_MODE_70,
        DEFAULT_MODE_75,
        APP_MODE_HIGH_90
    };

    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;
    private static final int MODE_ID = 1;
    private static final float TRANSITION_POINT = 0.763f;

    private static final float HBM_TRANSITION_POINT_INVALID = Float.POSITIVE_INFINITY;

    private Context mContext;
    private FakesInjector mInjector;
    private Handler mHandler;
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();
    @Mock
    public StatusBarManagerInternal mStatusBarMock;
    @Mock
    public SensorManagerInternal mSensorManagerInternalMock;
    @Mock
    public DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock
    private DisplayManagerFlags mDisplayManagerFlags;

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder(this)
                    .setStrictness(Strictness.LENIENT)
                    .spyStatic(RefreshRateSettingsUtils.class)
                    .build();

    @Before
    public void setUp() throws Exception {
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        final MockContentResolver resolver = mSettingsProviderRule.mockContentResolver(mContext);
        when(mContext.getContentResolver()).thenReturn(resolver);
        mInjector = spy(new FakesInjector(mDisplayManagerInternalMock, mStatusBarMock,
                mSensorManagerInternalMock));
        mHandler = new Handler(Looper.getMainLooper());
    }

    private DisplayModeDirector createDirectorFromRefreshRateArray(
            float[] refreshRates, int baseModeId) {
        return createDirectorFromRefreshRateArray(refreshRates, baseModeId, refreshRates[0]);
    }

    private DisplayModeDirector createDirectorFromRefreshRateArray(
            float[] refreshRates, int baseModeId, float defaultRefreshRate) {
        Display.Mode[] modes = new Display.Mode[refreshRates.length];
        Display.Mode defaultMode = null;
        for (int i = 0; i < refreshRates.length; i++) {
            modes[i] = new Display.Mode(
                    /*modeId=*/baseModeId + i, /*width=*/1000, /*height=*/1000, refreshRates[i]);
            if (refreshRates[i] == defaultRefreshRate) {
                defaultMode = modes[i];
            }
        }
        assertThat(defaultMode).isNotNull();
        return createDirectorFromModeArray(modes, defaultMode);
    }

    private DisplayModeDirector createDirectorFromModeArray(Display.Mode[] modes,
            Display.Mode defaultMode) {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, mHandler, mInjector, mDisplayManagerFlags);
        director.setLoggingEnabled(true);
        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(DISPLAY_ID, modes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(DISPLAY_ID, defaultMode);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);
        return director;
    }

    private DisplayModeDirector createDirectorFromFpsRange(int minFps, int maxFps) {
        int numRefreshRates = maxFps - minFps + 1;
        float[] refreshRates = new float[numRefreshRates];
        for (int i = 0; i < numRefreshRates; i++) {
            refreshRates[i] = minFps + i;
        }
        return createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/minFps,
                /*defaultRefreshRate=*/minFps);
    }

    @Test
    public void testDisplayModeVoting() {
        // With no votes present, DisplayModeDirector should allow any refresh rate.
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        DesiredDisplayModeSpecs modeSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(modeSpecs.baseModeId).isEqualTo(60);
        assertThat(modeSpecs.primary.physical.min).isEqualTo(0f);
        assertThat(modeSpecs.primary.physical.max).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(modeSpecs.primary.render.min).isEqualTo(0f);
        assertThat(modeSpecs.primary.render.max).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(modeSpecs.appRequest.physical.min).isEqualTo(0f);
        assertThat(modeSpecs.appRequest.physical.max).isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(modeSpecs.appRequest.render.min).isEqualTo(0f);
        assertThat(modeSpecs.appRequest.render.max).isEqualTo(Float.POSITIVE_INFINITY);

        int numPriorities =  Vote.MAX_PRIORITY - Vote.MIN_PRIORITY + 1;

        // Ensure vote priority works as expected. As we add new votes with higher priority, they
        // should take precedence over lower priority votes.
        {
            int minFps = 60;
            int maxFps = minFps + 2 * numPriorities;
            director = createDirectorFromFpsRange(minFps, maxFps);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(DISPLAY_ID, votes);
            for (int i = 0; i < numPriorities; i++) {
                int priority = Vote.MIN_PRIORITY + i;
                votes.put(priority, Vote.forPhysicalRefreshRates(minFps + i, maxFps - i));
                director.injectVotesByDisplay(votesByDisplay);
                modeSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
                assertThat(modeSpecs.baseModeId).isEqualTo(minFps + i);
                assertThat(modeSpecs.primary.physical.min)
                        .isEqualTo((float) (minFps + i));
                assertThat(modeSpecs.primary.physical.max)
                        .isEqualTo((float) (maxFps - i));
                assertThat(modeSpecs.primary.render.min).isZero();
                assertThat(modeSpecs.primary.render.max)
                        .isEqualTo((float) (maxFps - i));
                if (priority >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF) {
                    assertThat(modeSpecs.appRequest.physical.min)
                            .isEqualTo((float) (minFps + i));
                    assertThat(modeSpecs.appRequest.physical.max)
                            .isEqualTo((float) (maxFps - i));
                    assertThat(modeSpecs.appRequest.render.min).isZero();
                    assertThat(modeSpecs.appRequest.render.max).isEqualTo(
                            (float) (maxFps - i));
                } else {
                    assertThat(modeSpecs.appRequest.physical.min).isZero();
                    assertThat(modeSpecs.appRequest.physical.max).isPositiveInfinity();
                    assertThat(modeSpecs.appRequest.render.min).isZero();
                    assertThat(modeSpecs.appRequest.render.max).isPositiveInfinity();
                }

            }
        }

        // Ensure lower priority votes are able to influence the final decision, even in the
        // presence of higher priority votes.
        {
            assertTrue(numPriorities >= 2);
            director = createDirectorFromFpsRange(60, 90);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(DISPLAY_ID, votes);
            votes.put(Vote.MAX_PRIORITY, Vote.forPhysicalRefreshRates(65, 85));
            votes.put(Vote.MIN_PRIORITY, Vote.forPhysicalRefreshRates(70, 80));
            director.injectVotesByDisplay(votesByDisplay);
            modeSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
            assertThat(modeSpecs.baseModeId).isEqualTo(70);
            assertThat(modeSpecs.primary.physical.min).isEqualTo(70f);
            assertThat(modeSpecs.primary.physical.max).isEqualTo(80f);
        }
    }

    @Test
    public void testVotingWithFloatingPointErrors() {
        DisplayModeDirector director = createDirectorFromFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        float error = FLOAT_TOLERANCE / 4;
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(0, 60));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE,
                Vote.forPhysicalRefreshRates(60 + error, 60 + error));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forPhysicalRefreshRates(60 - error, 60 - error));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);

        votes.clear();
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(0, 60 - error));
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(60 + error, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);
    }

    @Test
    public void testFlickerHasLowerPriorityThanUserAndRangeIsSingle() {
        assertTrue(Vote.PRIORITY_FLICKER_REFRESH_RATE
                < Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertTrue(Vote.PRIORITY_FLICKER_REFRESH_RATE
                < Vote.PRIORITY_APP_REQUEST_SIZE);

        assertTrue(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH
                > Vote.PRIORITY_LOW_POWER_MODE);

        Display.Mode[] modes = new Display.Mode[4];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/2000, /*height=*/2000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/3, /*width=*/1000, /*height=*/1000, 90);
        modes[3] = new Display.Mode(
                /*modeId=*/4, /*width=*/2000, /*height=*/2000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primary.physical.min);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(90, 90));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primary.physical.min);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primary.physical.min);

        votes.clear();
        appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(90, 90));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primary.physical.min);
    }

    @Test
    public void testLPMHasHigherPriorityThanUser() {
        assertTrue(Vote.PRIORITY_LOW_POWER_MODE
                > Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertTrue(Vote.PRIORITY_LOW_POWER_MODE
                > Vote.PRIORITY_APP_REQUEST_SIZE);

        Display.Mode[] modes = new Display.Mode[4];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/2000, /*height=*/2000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/3, /*width=*/1000, /*height=*/1000, 90);
        modes[3] = new Display.Mode(
                /*modeId=*/4, /*width=*/2000, /*height=*/2000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(90);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);

        votes.clear();
        appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(90);
    }

    /** Resolution range voting disabled */
    @Test
    public void testAppRequestRefreshRateRange() {
        // Confirm that the app request range doesn't include flicker or min refresh rate settings,
        // but does include everything else.
        assertTrue(
                Vote.PRIORITY_FLICKER_REFRESH_RATE
                        < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE
                < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE
                >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);

        Display.Mode[] modes = new Display.Mode[3];
        modes[0] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/75, /*width=*/2000, /*height=*/2000, 75);
        modes[2] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(90, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);

        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(75);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);
    }

    /** Tests for app requested size */
    @Parameters(method = "getAppRequestedSizeTestCases")
    @Test
    public void testAppRequestedSize(final int expectedBaseModeId,
                final float expectedPhysicalRefreshRate,
                final float expectedAppRequestedRefreshRate,
                final Map<Integer, Vote> votesWithPriorities,
                final boolean displayResolutionRangeVotingEnabled) {
        when(mDisplayManagerFlags.isDisplayResolutionRangeVotingEnabled())
                .thenReturn(displayResolutionRangeVotingEnabled);
        DisplayModeDirector director = createDirectorFromModeArray(TEST_MODES, DEFAULT_MODE_75);

        SparseArray<Vote> votes = new SparseArray<>();
        votesWithPriorities.forEach(votes::put);

        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        director.injectVotesByDisplay(votesByDisplay);

        var desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(expectedBaseModeId);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.physical.max).isAtLeast(expectedPhysicalRefreshRate);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(0);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(expectedAppRequestedRefreshRate);
    }

    void verifySpecsWithRefreshRateSettings(DisplayModeDirector director, float minFps,
            float peakFps, float defaultFps, RefreshRateRanges primary,
            RefreshRateRanges appRequest) {
        DesiredDisplayModeSpecs specs = director.getDesiredDisplayModeSpecsWithInjectedFpsSettings(
                minFps, peakFps, defaultFps);
        assertThat(specs.primary).isEqualTo(primary);
        assertThat(specs.appRequest).isEqualTo(appRequest);
    }

    @Test
    public void testSpecsFromRefreshRateSettings() {
        // Confirm that, with varying settings for min, peak, and default refresh rate,
        // DesiredDisplayModeSpecs is calculated correctly.
        float[] refreshRates = {30.f, 60.f, 90.f, 120.f, 150.f};
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/0);

        float inf = Float.POSITIVE_INFINITY;
        RefreshRateRange rangeAll = new RefreshRateRange(0, inf);
        RefreshRateRange range0to60 = new RefreshRateRange(0, 60);
        RefreshRateRange range0to90 = new RefreshRateRange(0, 90);
        RefreshRateRange range0to120 = new RefreshRateRange(0, 120);
        RefreshRateRange range60to90 = new RefreshRateRange(60, 90);
        RefreshRateRange range90to90 = new RefreshRateRange(90, 90);
        RefreshRateRange range90to120 = new RefreshRateRange(90, 120);
        RefreshRateRange range60toInf = new RefreshRateRange(60, inf);
        RefreshRateRange range90toInf = new RefreshRateRange(90, inf);

        RefreshRateRanges frameRateAll = new RefreshRateRanges(rangeAll, rangeAll);
        RefreshRateRanges frameRate90toInf = new RefreshRateRanges(range90toInf, range90toInf);
        RefreshRateRanges frameRate0to60 = new RefreshRateRanges(rangeAll, range0to60);
        RefreshRateRanges frameRate0to90 = new RefreshRateRanges(rangeAll, range0to90);
        RefreshRateRanges frameRate0to120 = new RefreshRateRanges(rangeAll, range0to120);
        RefreshRateRanges frameRate60to90 = new RefreshRateRanges(range60toInf, range60to90);
        RefreshRateRanges frameRate90to90 = new RefreshRateRanges(range90toInf, range90to90);
        RefreshRateRanges frameRate90to120 = new RefreshRateRanges(range90toInf, range90to120);

        verifySpecsWithRefreshRateSettings(director, 0, 0, 0, frameRateAll, frameRateAll);
        verifySpecsWithRefreshRateSettings(director, 0, 0, 90, frameRate0to90, frameRateAll);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 0, frameRate0to90, frameRate0to90);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 60, frameRate0to60, frameRate0to90);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 120, frameRate0to90,
                frameRate0to90);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 0, frameRate90toInf, frameRateAll);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 120, frameRate90to120,
                frameRateAll);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 60, frameRate90toInf, frameRateAll);
        verifySpecsWithRefreshRateSettings(director, 90, 120, 0, frameRate90to120,
                frameRate0to120);
        verifySpecsWithRefreshRateSettings(director, 90, 60, 0, frameRate90to90,
                frameRate0to90);
        verifySpecsWithRefreshRateSettings(director, 60, 120, 90, frameRate60to90,
                frameRate0to120);
    }

    void verifyBrightnessObserverCall(DisplayModeDirector director, float minFps, float peakFps,
            float defaultFps, float brightnessObserverMin, float brightnessObserverMax) {
        BrightnessObserver brightnessObserver = mock(BrightnessObserver.class);
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
    public void testBrightnessObserver_LowPowerModeRemovesFlickerVotes() {
        float[] refreshRates = {60.f, 90.f, 120.f};
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/0);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(-1, votes); // Global Vote
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(0, 60));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forRenderFrameRates(60, 90));
        director.injectVotesByDisplay(votesByDisplay);

        director.getBrightnessObserver().onLowPowerModeEnabledLocked(true);

        assertNull(director.getVote(-1, Vote.PRIORITY_FLICKER_REFRESH_RATE));
        assertNull(director.getVote(-1, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH));
    }

    @Test
    public void testVotingWithAlwaysRespectAppRequest() {
        Display.Mode[] modes = new Display.Mode[3];
        modes[0] = new Display.Mode(
                /*modeId=*/50, /*width=*/1000, /*height=*/1000, 50);
        modes[1] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);

        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(0, 60));
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(60, 90));
        Display.Mode appRequestedMode = modes[2];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(60, 60));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);

        assertThat(director.shouldAlwaysRespectAppRequestedMode()).isFalse();
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);

        director.setShouldAlwaysRespectAppRequestedMode(true);
        assertThat(director.shouldAlwaysRespectAppRequestedMode()).isTrue();
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isAtMost(50);
        assertThat(desiredSpecs.primary.physical.max).isAtLeast(90);
        assertThat(desiredSpecs.primary.render.min).isAtMost(50);
        assertThat(desiredSpecs.primary.render.max).isAtLeast(90);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);

        director.setShouldAlwaysRespectAppRequestedMode(false);
        assertThat(director.shouldAlwaysRespectAppRequestedMode()).isFalse();

        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);
    }

    @Test
    public void testVotingWithSwitchingTypeNone() {
        DisplayModeDirector director = createDirectorFromFpsRange(0, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(30, 90));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));

        director.injectVotesByDisplay(votesByDisplay);
        assertThat(director.getModeSwitchingType())
                .isNotEqualTo(DisplayManager.SWITCHING_TYPE_NONE);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.appRequest.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(30);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_NONE);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_NONE);

        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequest.render.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.baseModeId).isEqualTo(30);
    }

    @Test
    public void testVotingWithSwitchingTypeRenderFrameRateOnly() {
        DisplayModeDirector director = createDirectorFromFpsRange(0, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(30, 90));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));

        director.injectVotesByDisplay(votesByDisplay);
        assertThat(director.getModeSwitchingType())
                .isNotEqualTo(DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.appRequest.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(30);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY);

        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequest.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(30);

        assertThat(desiredSpecs.baseModeId).isEqualTo(30);
    }

    @Test
    public void testVotingWithSwitchingTypeRenderFrameRateOnlyRenderRateIsNotPhysicalRefreshRate() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 120);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(30, 90));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));

        director.injectVotesByDisplay(votesByDisplay);
        assertThat(director.getModeSwitchingType())
                .isNotEqualTo(DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.appRequest.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_RENDER_FRAME_RATE_ONLY);

        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(60);

        assertThat(desiredSpecs.baseModeId).isEqualTo(60);
    }

    @Test
    public void testVotingWithSwitchingTypeWithinGroups() {
        DisplayModeDirector director = createDirectorFromFpsRange(0, 90);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.allowGroupSwitching).isFalse();
    }

    @Test
    public void testVotingWithSwitchingTypeWithinAndAcrossGroups() {
        DisplayModeDirector director = createDirectorFromFpsRange(0, 90);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.allowGroupSwitching).isTrue();
    }

    @Test
    public void testDefaultDisplayModeIsSelectedIfAvailable() {
        final float[] refreshRates = new float[]{24f, 25f, 30f, 60f, 90f};
        final int defaultModeId = 3;
        DisplayModeDirector director = createDirectorFromRefreshRateArray(
                refreshRates, /*baseModeId=*/0, refreshRates[defaultModeId]);

        DesiredDisplayModeSpecs specs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(specs.baseModeId).isEqualTo(defaultModeId);
    }

    @Test
    public void testStaleAppRequestSize() {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, mHandler, mInjector, mDisplayManagerFlags);
        Display.Mode[] modes = new Display.Mode[] {
                new Display.Mode(1, 1280, 720, 60),
        };
        Display.Mode defaultMode = modes[0];

        // Inject supported modes
        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(DISPLAY_ID, modes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);

        // Inject default mode
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(DISPLAY_ID, defaultMode);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);

        // Inject votes
        SparseArray<Vote> votes = new SparseArray<>();
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(1920, 1080));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(60));
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        director.injectVotesByDisplay(votesByDisplay);

        director.setShouldAlwaysRespectAppRequestedMode(true);

        // We should return the only available mode
        DesiredDisplayModeSpecs specs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(specs.baseModeId).isEqualTo(defaultMode.getModeId());
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

        float[] expectedDisplayThresholds = { BrightnessSynchronizer.brightnessIntToFloat(10) };
        float[] expectedAmbientThresholds = { 20 };
        assertThat(director.getBrightnessObserver().getLowDisplayBrightnessThresholds())
                .isEqualTo(expectedDisplayThresholds);
        assertThat(director.getBrightnessObserver().getLowAmbientBrightnessThresholds())
                .isEqualTo(expectedAmbientThresholds);

        final int[] updatedDisplayThresholds = { 9, 14 };
        final int[] updatedAmbientThresholds = { -1, 19 };
        config.setLowDisplayBrightnessThresholds(updatedDisplayThresholds);
        config.setLowAmbientBrightnessThresholds(updatedAmbientThresholds);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();

        expectedDisplayThresholds = new float[]{ BrightnessSynchronizer.brightnessIntToFloat(9),
                BrightnessSynchronizer.brightnessIntToFloat(14) };
        expectedAmbientThresholds = new float[]{ -1, 19 };
        assertThat(director.getBrightnessObserver().getLowDisplayBrightnessThresholds())
                .isEqualTo(expectedDisplayThresholds);
        assertThat(director.getBrightnessObserver().getLowAmbientBrightnessThresholds())
                .isEqualTo(expectedAmbientThresholds);
    }

    @Test
    public void setBrightness_doesNotLockFpsIfSmoothDisplayIsOff() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(60); // set smooth display OFF
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInLowZone(90);
        config.setLowDisplayBrightnessThresholds(new int[] { 10 });
        config.setLowAmbientBrightnessThresholds(new int[] { 20 });

        director.start(createMockSensorManager(createLightSensor()));

        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                        | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        setBrightness(10, 10, displayListener);

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNull();
    }

    @Test
    public void setBrightness_locksFpsIfSmoothDisplayIsOn() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90); // set smooth display ON
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInLowZone(90);
        config.setLowDisplayBrightnessThresholds(new int[] { 10 });
        config.setLowAmbientBrightnessThresholds(new int[] { 20 });

        director.start(createMockSensorManager(createLightSensor()));

        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                        | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        setBrightness(10, 10, displayListener);

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForPhysicalRefreshRate(vote, 90 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(DisableRefreshRateSwitchingVote.class);
        DisableRefreshRateSwitchingVote disableVote = (DisableRefreshRateSwitchingVote) vote;
        assertThat(disableVote.mDisableRefreshRateSwitching).isTrue();
    }

    @Test
    public void setBrightness_doesNotLockFpsIfSmoothDisplayIsOnAndLowPowerMode() {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.LOW_POWER_MODE, 1);
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90); // set smooth display ON
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInLowZone(90);
        config.setLowDisplayBrightnessThresholds(new int[] { 10 });
        config.setLowAmbientBrightnessThresholds(new int[] { 20 });

        director.start(createMockSensorManager(createLightSensor()));

        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                        | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        setBrightness(10, 10, displayListener);

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNull();
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

        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        ArgumentCaptor<SensorEventListener> sensorListenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        Mockito.verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        sensorListenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener sensorListener = sensorListenerCaptor.getValue();

        setBrightness(10, 10, displayListener);
        // Sensor reads 20 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 20 /*lux*/));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForPhysicalRefreshRate(vote, 90 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(DisableRefreshRateSwitchingVote.class);
        DisableRefreshRateSwitchingVote disableVote = (DisableRefreshRateSwitchingVote) vote;
        assertThat(disableVote.mDisableRefreshRateSwitching).isTrue();

        // We expect DisplayModeDirector to act on BrightnessInfo.adjustedBrightness; set only this
        // parameter to the necessary threshold
        setBrightness(10, 125, displayListener);
        // Sensor reads 1000 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 1000 /*lux*/));

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
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

        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener sensorListener = listenerCaptor.getValue();

        setBrightness(100, 100, displayListener);
        // Sensor reads 2000 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 2000));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNull();

        // We expect DisplayModeDirector to act on BrightnessInfo.adjustedBrightness; set only this
        // parameter to the necessary threshold
        setBrightness(100, 255, displayListener);
        // Sensor reads 9000 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 9000));

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForPhysicalRefreshRate(vote, 60 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(DisableRefreshRateSwitchingVote.class);
        DisableRefreshRateSwitchingVote disableVote = (DisableRefreshRateSwitchingVote) vote;
        assertThat(disableVote.mDisableRefreshRateSwitching).isTrue();
    }

    @Test
    public void testLockFpsForHighZoneWithThermalCondition() throws Exception {
        // First, configure brightness zones or DMD won't register for sensor data.
        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setHighDisplayBrightnessThresholds(new int[] { 200 });
        config.setHighAmbientBrightnessThresholds(new int[] { 8000 });

        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f, 120.f}, 0);
        setPeakRefreshRate(120 /*fps*/);
        director.getSettingsObserver().setDefaultRefreshRate(120);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        // Set the thresholds for High Zone
        DisplayDeviceConfig ddcMock = mock(DisplayDeviceConfig.class);
        when(ddcMock.getDefaultHighBlockingZoneRefreshRate()).thenReturn(90);
        when(ddcMock.getHighDisplayBrightnessThresholds()).thenReturn(new float[] { 200 });
        when(ddcMock.getHighAmbientBrightnessThresholds()).thenReturn(new float[] { 8000 });
        when(ddcMock.getDefaultLowBlockingZoneRefreshRate()).thenReturn(90);
        when(ddcMock.getLowDisplayBrightnessThresholds()).thenReturn(new float[] {});
        when(ddcMock.getLowAmbientBrightnessThresholds()).thenReturn(new float[] {});

        // Set the thermal condition for refresh rate range
        when(ddcMock.getHighBlockingZoneThermalMap()).thenReturn(
                new SparseArray<RefreshRateRange>() {{
                    put(Temperature.THROTTLING_CRITICAL, new RefreshRateRange(60, 60));
                }}
        );
        director.defaultDisplayDeviceUpdated(ddcMock); // set the ddc

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);
        director.start(sensorManager);

        // Get the display listener so that we can send it new brightness events
        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        // Get the sensor listener so that we can give it new light sensor events
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener sensorListener = listenerCaptor.getValue();

        // Get the thermal listener so that we can give it new thermal conditions
        ArgumentCaptor<IThermalEventListener> thermalListenerCaptor =
                ArgumentCaptor.forClass(IThermalEventListener.class);
        verify(mInjector, atLeastOnce()).registerThermalServiceListener(
                thermalListenerCaptor.capture());
        List<IThermalEventListener> thermalListeners = thermalListenerCaptor.getAllValues();

        setBrightness(100, 100, displayListener);
        // Sensor reads 2000 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 2000));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNull();

        // We expect DisplayModeDirector to act on BrightnessInfo.adjustedBrightness; set only this
        // parameter to the necessary threshold
        setBrightness(255, 255, displayListener);
        // Sensor reads 9000 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 9000));

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForPhysicalRefreshRate(vote, 90 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(DisableRefreshRateSwitchingVote.class);
        DisableRefreshRateSwitchingVote disableVote = (DisableRefreshRateSwitchingVote) vote;
        assertThat(disableVote.mDisableRefreshRateSwitching).isTrue();

        // Set critical and check new refresh rate
        Temperature temp = getSkinTemp(Temperature.THROTTLING_CRITICAL);
        for (var listener : thermalListeners) {
            listener.notifyThrottling(temp);
        }

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForPhysicalRefreshRate(vote, 60 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(DisableRefreshRateSwitchingVote.class);
        disableVote = (DisableRefreshRateSwitchingVote) vote;
        assertThat(disableVote.mDisableRefreshRateSwitching).isTrue();
    }

    @Test
    public void testLockFpsForLowZoneWithThermalCondition() throws Exception {
        // First, configure brightness zones or DMD won't register for sensor data.
        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setHighDisplayBrightnessThresholds(new int[] { 200 });
        config.setHighAmbientBrightnessThresholds(new int[] { 8000 });

        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f, 120.f}, 0);
        setPeakRefreshRate(120 /*fps*/);
        director.getSettingsObserver().setDefaultRefreshRate(120);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        // Set the thresholds for Low Zone
        DisplayDeviceConfig ddcMock = mock(DisplayDeviceConfig.class);
        when(ddcMock.getDefaultLowBlockingZoneRefreshRate()).thenReturn(90);
        when(ddcMock.getHighDisplayBrightnessThresholds()).thenReturn(new float[] { 200 });
        when(ddcMock.getHighAmbientBrightnessThresholds()).thenReturn(new float[] { 8000 });
        when(ddcMock.getDefaultLowBlockingZoneRefreshRate()).thenReturn(90);
        when(ddcMock.getLowDisplayBrightnessThresholds()).thenReturn(new float[] { 10 });
        when(ddcMock.getLowAmbientBrightnessThresholds()).thenReturn(new float[] { 10 });

        // Set the thermal condition for refresh rate range
        when(ddcMock.getLowBlockingZoneThermalMap()).thenReturn(
                new SparseArray<RefreshRateRange>() {{
                    put(Temperature.THROTTLING_CRITICAL, new RefreshRateRange(60, 60));
                }}
        );
        director.defaultDisplayDeviceUpdated(ddcMock); // set the ddc

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);
        director.start(sensorManager);

        // Get the display listener so that we can send it new brightness events
        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        // Get the sensor listener so that we can give it new light sensor events
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener sensorListener = listenerCaptor.getValue();

        // Get the thermal listener so that we can give it new thermal conditions
        ArgumentCaptor<IThermalEventListener> thermalListenerCaptor =
                ArgumentCaptor.forClass(IThermalEventListener.class);
        verify(mInjector, atLeastOnce()).registerThermalServiceListener(
                thermalListenerCaptor.capture());
        List<IThermalEventListener> thermalListeners = thermalListenerCaptor.getAllValues();

        setBrightness(100, 100, displayListener);
        // Sensor reads 2000 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 2000));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNull();

        // We expect DisplayModeDirector to act on BrightnessInfo.adjustedBrightness; set only this
        // parameter to the necessary threshold
        setBrightness(5, 5, displayListener);
        // Sensor reads 9 lux,
        sensorListener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 9));

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForPhysicalRefreshRate(vote, 90 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(DisableRefreshRateSwitchingVote.class);
        DisableRefreshRateSwitchingVote disableVote = (DisableRefreshRateSwitchingVote) vote;
        assertThat(disableVote.mDisableRefreshRateSwitching).isTrue();

        // Set critical and check new refresh rate
        Temperature temp = getSkinTemp(Temperature.THROTTLING_CRITICAL);
        for (var listener : thermalListeners) {
            listener.notifyThrottling(temp);
        }

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForPhysicalRefreshRate(vote, 60 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(DisableRefreshRateSwitchingVote.class);
        disableVote = (DisableRefreshRateSwitchingVote) vote;
        assertThat(disableVote.mDisableRefreshRateSwitching).isTrue();
    }

    @Test
    public void testPeakRefreshRate_FlagEnabled() {
        when(mDisplayManagerFlags.isBackUpSmoothDisplayAndForcePeakRefreshRateEnabled())
                .thenReturn(true);
        float highestRefreshRate = 130;
        doReturn(highestRefreshRate).when(() ->
                RefreshRateSettingsUtils.findHighestRefreshRateForDefaultDisplay(mContext));
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);
        director.start(sensorManager);

        setPeakRefreshRate(Float.POSITIVE_INFINITY);

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY,
                Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE);
        assertVoteForRenderFrameRateRange(vote, /* frameRateLow= */ 0, /* frameRateHigh= */
                highestRefreshRate);
    }

    @Test
    public void testPeakRefreshRate_FlagDisabled() {
        when(mDisplayManagerFlags.isBackUpSmoothDisplayAndForcePeakRefreshRateEnabled())
                .thenReturn(false);
        float peakRefreshRate = 130;
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);
        director.start(sensorManager);

        setPeakRefreshRate(peakRefreshRate);

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY,
                Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE);
        assertVoteForRenderFrameRateRange(vote, /* frameRateLow= */ 0, /* frameRateHigh= */
                peakRefreshRate);
    }

    @Test
    public void testMinRefreshRate_FlagEnabled() {
        when(mDisplayManagerFlags.isBackUpSmoothDisplayAndForcePeakRefreshRateEnabled())
                .thenReturn(true);
        float highestRefreshRate = 130;
        doReturn(highestRefreshRate).when(() ->
                RefreshRateSettingsUtils.findHighestRefreshRateForDefaultDisplay(mContext));
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);
        director.start(sensorManager);

        setMinRefreshRate(Float.POSITIVE_INFINITY);

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY,
                Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE);
        assertVoteForRenderFrameRateRange(vote, /* frameRateLow= */ highestRefreshRate,
                /* frameRateHigh= */ Float.POSITIVE_INFINITY);
    }

    @Test
    public void testMinRefreshRate_FlagDisabled() {
        when(mDisplayManagerFlags.isBackUpSmoothDisplayAndForcePeakRefreshRateEnabled())
                .thenReturn(false);
        float minRefreshRate = 130;
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);
        director.start(sensorManager);

        setMinRefreshRate(minRefreshRate);

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY,
                Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE);
        assertVoteForRenderFrameRateRange(vote, /* frameRateLow= */ minRefreshRate,
                /* frameRateHigh= */ Float.POSITIVE_INFINITY);
    }

    @Test
    public void testSensorRegistration() {
        // First, configure brightness zones or DMD won't register for sensor data.
        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInHighZone(60);
        config.setHighDisplayBrightnessThresholds(new int[] { 255 });
        config.setHighAmbientBrightnessThresholds(new int[] { 8000 });

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
        verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));

        // Display state changed from On to Doze
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_DOZE);
        verify(sensorManager)
                .unregisterListener(listenerCaptor.capture());

        // Display state changed from Doze to On
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);
        verify(sensorManager, times(2))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));

    }

    @Test
    public void testUdfpsListenerGetsRegistered() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f, 110.f}, 0);
        verify(mStatusBarMock, never()).setUdfpsRefreshRateCallback(any());

        director.onBootCompleted();
        verify(mStatusBarMock).setUdfpsRefreshRateCallback(eq(director.getUdpfsObserver()));
    }

    @Test
    public void testGbhmVotesFor60hz() throws Exception {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f, 110.f}, 0);
        director.start(createMockSensorManager());
        director.onBootCompleted();
        ArgumentCaptor<IUdfpsRefreshRateRequestCallback> captor =
                ArgumentCaptor.forClass(IUdfpsRefreshRateRequestCallback.class);
        verify(mStatusBarMock).setUdfpsRefreshRateCallback(captor.capture());

        // Should be no vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_UDFPS);
        assertNull(vote);
    }

    @Test
    public void testAppRequestMinRefreshRate() {
        // Confirm that the app min request range doesn't include flicker or min refresh rate
        // settings but does include everything else.
        assertTrue(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE
                >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);

        Display.Mode[] modes = new Display.Mode[3];
        modes[0] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/75, /*width=*/1000, /*height=*/1000, 75);
        modes[2] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[1]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(90, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE,
                Vote.forRenderFrameRates(75, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);
    }

    @Test
    public void testAppRequestMaxRefreshRate() {
        // Confirm that the app max request range doesn't include flicker or min refresh rate
        // settings but does include everything else.
        assertTrue(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE
                >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);

        Display.Mode[] modes = new Display.Mode[3];
        modes[0] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/75, /*width=*/1000, /*height=*/1000, 75);
        modes[2] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[1]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forPhysicalRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.render.min).isZero();
        assertThat(desiredSpecs.primary.render.max).isAtMost(60);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequest.render.min).isZero();
        assertThat(desiredSpecs.appRequest.render.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(90, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.render.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequest.physical.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequest.render.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequest.render.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE,
                Vote.forRenderFrameRates(0, 75));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.primary.render.min).isZero();
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.appRequest.physical.min).isZero();
        assertThat(desiredSpecs.appRequest.physical.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequest.render.min).isZero();
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(75);
    }

    @Test
    public void testAppRequestObserver_modeId() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, 60, 0, 0);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(BaseModeRefreshRateVote.class);
        BaseModeRefreshRateVote baseModeVote = (BaseModeRefreshRateVote) vote;
        assertThat(baseModeVote.mAppRequestBaseModeRefreshRate).isWithin(FLOAT_TOLERANCE).of(60);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(SizeVote.class);
        SizeVote sizeVote = (SizeVote) vote;
        assertThat(sizeVote.mHeight).isEqualTo(1000);
        assertThat(sizeVote.mWidth).isEqualTo(1000);
        assertThat(sizeVote.mMinHeight).isEqualTo(1000);
        assertThat(sizeVote.mMinWidth).isEqualTo(1000);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNull(vote);

        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, 90, 0, 0);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(BaseModeRefreshRateVote.class);
        baseModeVote = (BaseModeRefreshRateVote) vote;
        assertThat(baseModeVote.mAppRequestBaseModeRefreshRate).isWithin(FLOAT_TOLERANCE).of(90);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(SizeVote.class);
        sizeVote = (SizeVote) vote;
        assertThat(sizeVote.mHeight).isEqualTo(1000);
        assertThat(sizeVote.mWidth).isEqualTo(1000);
        assertThat(sizeVote.mMinHeight).isEqualTo(1000);
        assertThat(sizeVote.mMinWidth).isEqualTo(1000);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNull(vote);
    }

    @Test
    public void testAppRequestObserver_minRefreshRate() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, -1, 60, 0);
        Vote appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNull(appRequestRefreshRate);

        Vote appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNull(appRequestSize);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        RefreshRateVote.RenderVote renderVote = (RefreshRateVote.RenderVote) vote;
        assertThat(renderVote.mMinRefreshRate).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(renderVote.mMaxRefreshRate).isAtLeast(90);

        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, -1, 90, 0);
        appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNull(appRequestRefreshRate);

        appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNull(appRequestSize);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        renderVote = (RefreshRateVote.RenderVote) vote;
        assertThat(renderVote.mMinRefreshRate).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(renderVote.mMaxRefreshRate).isAtLeast(90);
    }

    @Test
    public void testAppRequestObserver_maxRefreshRate() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, -1, 0, 90);
        Vote appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNull(appRequestRefreshRate);

        Vote appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNull(appRequestSize);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        RefreshRateVote.RenderVote renderVote = (RefreshRateVote.RenderVote) vote;
        assertThat(renderVote.mMinRefreshRate).isZero();
        assertThat(renderVote.mMaxRefreshRate).isWithin(FLOAT_TOLERANCE).of(90);

        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, -1, 0, 60);
        appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNull(appRequestRefreshRate);

        appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNull(appRequestSize);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        renderVote = (RefreshRateVote.RenderVote) vote;
        assertThat(renderVote.mMinRefreshRate).isZero();
        assertThat(renderVote.mMaxRefreshRate).isWithin(FLOAT_TOLERANCE).of(60);
    }

    @Test
    public void testAppRequestObserver_invalidRefreshRateRange() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, -1, 90, 60);
        Vote appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNull(appRequestRefreshRate);

        Vote appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNull(appRequestSize);

        Vote appRequestRefreshRateRange =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNull(appRequestRefreshRateRange);
    }

    @Test
    public void testAppRequestObserver_modeIdAndRefreshRateRange() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, 60, 90, 90);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(BaseModeRefreshRateVote.class);
        BaseModeRefreshRateVote baseModeVote = (BaseModeRefreshRateVote) vote;
        assertThat(baseModeVote.mAppRequestBaseModeRefreshRate).isWithin(FLOAT_TOLERANCE).of(60);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(SizeVote.class);
        SizeVote sizeVote = (SizeVote) vote;
        assertThat(sizeVote.mHeight).isEqualTo(1000);
        assertThat(sizeVote.mWidth).isEqualTo(1000);
        assertThat(sizeVote.mMinHeight).isEqualTo(1000);
        assertThat(sizeVote.mMinWidth).isEqualTo(1000);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE);
        assertNotNull(vote);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        RefreshRateVote.RenderVote renderVote = (RefreshRateVote.RenderVote) vote;
        assertThat(renderVote.mMinRefreshRate).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(renderVote.mMaxRefreshRate).isWithin(FLOAT_TOLERANCE).of(90);
    }

    @Test
    public void testAppRequestsIsTheDefaultMode() {
        Display.Mode[] modes = new Display.Mode[2];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(1);
        assertThat(desiredSpecs.primary.physical.min).isAtMost(60);
        assertThat(desiredSpecs.primary.physical.max).isAtLeast(90);

        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primary.physical.min).isAtMost(60);
        assertThat(desiredSpecs.primary.physical.max).isAtLeast(90);
    }

    @Test
    public void testDisableRefreshRateSwitchingVote() {
        DisplayModeDirector director = createDirectorFromFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(90, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(50);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(50);
        assertThat(desiredSpecs.baseModeId).isEqualTo(50);

        votes.clear();
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE,
                Vote.forPhysicalRefreshRates(70, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(80, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 90));
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(80);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(80);
        assertThat(desiredSpecs.baseModeId).isEqualTo(80);

        votes.clear();
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE,
                Vote.forPhysicalRefreshRates(90, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(80, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 90));
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);
    }

    @Test
    @Parameters({
            "true",
            "false"
    })
    public void testBaseModeIdInPrimaryRange(boolean supportsFrameRateOverride) {
        when(mInjector.supportsFrameRateOverride()).thenReturn(supportsFrameRateOverride);
        DisplayModeDirector director = createDirectorFromFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(70));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        if (supportsFrameRateOverride) {
            assertThat(desiredSpecs.baseModeId).isEqualTo(70);
        } else {
            assertThat(desiredSpecs.baseModeId).isEqualTo(50);

        }
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(55));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(55);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE,
                Vote.forRenderFrameRates(0, 52));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(55));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        if (supportsFrameRateOverride) {
            assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(52);
        } else {
            assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        }
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.baseModeId).isEqualTo(55);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE,
                Vote.forRenderFrameRates(0, 58));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(55));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(58);
        assertThat(desiredSpecs.baseModeId).isEqualTo(55);
    }

    @Test
    public void testStaleAppVote() {
        Display.Mode[] modes = new Display.Mode[4];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/2000, /*height=*/2000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/3, /*width=*/1000, /*height=*/1000, 90);
        modes[3] = new Display.Mode(
                /*modeId=*/4, /*width=*/2000, /*height=*/2000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);

        // Change mode Id's to simulate that a hotplug has occurred.
        Display.Mode[] newModes = new Display.Mode[4];
        newModes[0] = new Display.Mode(
                /*modeId=*/5, /*width=*/1000, /*height=*/1000, 60);
        newModes[1] = new Display.Mode(
                /*modeId=*/6, /*width=*/2000, /*height=*/2000, 60);
        newModes[2] = new Display.Mode(
                /*modeId=*/7, /*width=*/1000, /*height=*/1000, 90);
        newModes[3] = new Display.Mode(
                /*modeId=*/8, /*width=*/2000, /*height=*/2000, 90);

        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(DISPLAY_ID, newModes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(DISPLAY_ID, newModes[0]);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(8);
    }

    @Test
    @Parameters({
            "true",
            "false"
    })
    public void testRefreshRateIsSubsetOfFrameRate(boolean supportsFrameRateOverride) {
        when(mInjector.supportsFrameRateOverride()).thenReturn(supportsFrameRateOverride);
        DisplayModeDirector director = createDirectorFromFpsRange(60, 120);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);


        votes.put(Vote.PRIORITY_UDFPS, Vote.forPhysicalRefreshRates(90, 120));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(120);
        assertThat(desiredSpecs.appRequest.render.min).isZero();
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(120);

        votes.clear();
        votes.put(Vote.PRIORITY_UDFPS, Vote.forPhysicalRefreshRates(90, 120));
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(120);
        assertThat(desiredSpecs.appRequest.render.min).isZero();
        if (supportsFrameRateOverride) {
            assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        } else {
            assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(120);
        }

        votes.clear();
        votes.put(Vote.PRIORITY_UDFPS, Vote.forPhysicalRefreshRates(90, 120));
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(120);
        if (supportsFrameRateOverride) {
            assertThat(desiredSpecs.appRequest.render.min).isWithin(FLOAT_TOLERANCE).of(60);
            assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        } else {
            assertThat(desiredSpecs.appRequest.render.min).isZero();
            assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(120);
        }

        votes.clear();
        votes.put(Vote.PRIORITY_UDFPS, Vote.forPhysicalRefreshRates(90, 120));
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(140, 140));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(120);
        assertThat(desiredSpecs.appRequest.render.min).isZero();
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(120);
    }

    @Test
    public void testRenderFrameRateIsAchievableByPhysicalRefreshRate() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 120);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);


        votes.put(Vote.PRIORITY_UDFPS, Vote.forPhysicalRefreshRates(120, 120));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.appRequest.physical.min).isWithin(FLOAT_TOLERANCE).of(120);
        assertThat(desiredSpecs.appRequest.physical.max).isWithin(FLOAT_TOLERANCE).of(120);
        assertThat(desiredSpecs.appRequest.render.min).isZero();
        assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(120);
    }

    @Test
    @Parameters({
            "true",
            "false"
    })
    public void testRenderFrameRateIncludesPhysicalRefreshRate(boolean supportsFrameRateOverride) {
        when(mInjector.supportsFrameRateOverride()).thenReturn(supportsFrameRateOverride);
        DisplayModeDirector director = createDirectorFromFpsRange(60, 120);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);

        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRenderFrameRates(0, 60));
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE,
                Vote.forRenderFrameRates(0, 30));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.appRequest.physical.min).isZero();
        assertThat(desiredSpecs.appRequest.physical.max).isPositiveInfinity();
        assertThat(desiredSpecs.appRequest.render.min).isZero();
        if (supportsFrameRateOverride) {
            assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(30);
        } else {
            assertThat(desiredSpecs.appRequest.render.max).isWithin(FLOAT_TOLERANCE).of(60);
        }
    }

    @Test
    public void testRenderFrameRateIsDroppedIfLowerPriorityThenBaseModeRefreshRate() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 120);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE,
                Vote.forRenderFrameRates(120, 120));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(90));
        votes.put(Vote.PRIORITY_PROXIMITY, Vote.forPhysicalRefreshRates(60, 120));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(120);
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(120);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);
    }

    @Test
    public void testRenderFrameRateIsAchievableWhenSwitchingTypeNone() {
        Display.Mode[] modes = new Display.Mode[2];
        modes[0] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[1]);
        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_NONE);

        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE,
                Vote.forRenderFrameRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primary.physical.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.physical.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.render.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primary.render.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);
    }

    @Test
    public void testProximitySensorVoting() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.f, 90.f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<ProximityActiveListener> ProximityCaptor =
                ArgumentCaptor.forClass(ProximityActiveListener.class);
        verify(mSensorManagerInternalMock).addProximityActiveListener(any(Executor.class),
                ProximityCaptor.capture());
        ProximityActiveListener proximityListener = ProximityCaptor.getValue();

        ArgumentCaptor<DisplayListener> DisplayCaptor =
                ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector, times(2)).registerDisplayListener(DisplayCaptor.capture(),
                any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                        | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                        | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED));
        DisplayListener displayListener = DisplayCaptor.getAllValues().get(0);

        // Verify that there is no proximity vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_PROXIMITY);
        assertNull(vote);

        when(mDisplayManagerInternalMock.getRefreshRateForDisplayAndSensor(eq(DISPLAY_ID), eq(null),
                  eq(Sensor.STRING_TYPE_PROXIMITY))).thenReturn(new RefreshRateRange(60, 60));

        when(mInjector.isDozeState(any(Display.class))).thenReturn(false);

        // Set the proximity to active and verify that we added a vote.
        proximityListener.onProximityActive(true);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_PROXIMITY);
        assertVoteForPhysicalRefreshRate(vote, 60.f);

        // Set the display state to doze and verify that the vote is gone
        when(mInjector.isDozeState(any(Display.class))).thenReturn(true);
        displayListener.onDisplayAdded(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_PROXIMITY);
        assertNull(vote);

        // Set the display state to on and verify that we added the vote back.
        when(mInjector.isDozeState(any(Display.class))).thenReturn(false);
        displayListener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_PROXIMITY);
        assertVoteForPhysicalRefreshRate(vote, 60.f);

        // Set the display state to doze and verify that the vote is gone
        when(mInjector.isDozeState(any(Display.class))).thenReturn(true);
        displayListener.onDisplayAdded(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_PROXIMITY);
        assertNull(vote);

        // Remove the display to cause the doze state to be removed
        displayListener.onDisplayRemoved(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_PROXIMITY);
        assertVoteForPhysicalRefreshRate(vote, 60.f);

        // Turn prox off and verify vote is gone.
        proximityListener.onProximityActive(false);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_PROXIMITY);
        assertNull(vote);
    }

    @Test
    public void testHbmVoting_forHdr() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.0f, 90.0f}, 0);
        final int hbmRefreshRate = 72;

        // Specify limitation before starting DisplayModeDirector to avoid waiting on property
        // propagation
        mInjector.getDeviceConfig().setRefreshRateInHbmHdr(hbmRefreshRate);

        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> captor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(captor.capture(), any(Handler.class),
                  eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                  | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED));
        DisplayListener listener = captor.getValue();

        // Specify Limitation
        when(mDisplayManagerInternalMock.getRefreshRateLimitations(DISPLAY_ID)).thenReturn(
                List.of(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE,
                        60.f, 60.f)));

        // Verify that there is no HBM vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HBM, with brightness in the HBM range
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(TRANSITION_POINT + FLOAT_TOLERANCE, 0.0f, 1.0f,
                    BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR, TRANSITION_POINT,
                    BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, hbmRefreshRate);

        // Turn on HBM, with brightness below the HBM range
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(TRANSITION_POINT - FLOAT_TOLERANCE, 0.0f, 1.0f,
                    BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR, TRANSITION_POINT,
                    BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(0.45f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT,
                    BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HBM, with brightness in the HBM range
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(TRANSITION_POINT + FLOAT_TOLERANCE, 0.0f, 1.0f,
                    BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR, TRANSITION_POINT,
                    BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, hbmRefreshRate);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(0.45f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HBM, with brightness below the HBM range
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(TRANSITION_POINT - FLOAT_TOLERANCE, 0.0f, 1.0f,
                    BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR, TRANSITION_POINT,
                    BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(0.45f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);
    }

    @Test
    public void testHbmObserverGetsUpdatedRefreshRateInHbmSunlight() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, /* baseModeId= */ 0);

        final int initialRefreshRate = 60;
        mInjector.getDeviceConfig().setRefreshRateInHbmSunlight(initialRefreshRate);
        director.start(createMockSensorManager());
        assertThat(director.getHbmObserver().getRefreshRateInHbmSunlight())
                .isEqualTo(initialRefreshRate);

        final int updatedRefreshRate = 90;
        mInjector.getDeviceConfig().setRefreshRateInHbmSunlight(updatedRefreshRate);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getHbmObserver().getRefreshRateInHbmSunlight())
                .isEqualTo(updatedRefreshRate);
    }

    @Test
    public void testHbmObserverGetsUpdatedRefreshRateInHbmHdr() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, /* baseModeId= */ 0);

        final int initialRefreshRate = 60;
        mInjector.getDeviceConfig().setRefreshRateInHbmHdr(initialRefreshRate);
        director.start(createMockSensorManager());
        assertThat(director.getHbmObserver().getRefreshRateInHbmHdr())
                .isEqualTo(initialRefreshRate);

        final int updatedRefreshRate = 90;
        mInjector.getDeviceConfig().setRefreshRateInHbmHdr(updatedRefreshRate);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getHbmObserver().getRefreshRateInHbmHdr())
                .isEqualTo(updatedRefreshRate);
    }

    @Test
    public void testHbmVoting_forSunlight() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> captor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(captor.capture(), any(Handler.class),
                  eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                  | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED));
        DisplayListener listener = captor.getValue();

        final int initialRefreshRate = 60;
        // Specify Limitation
        when(mDisplayManagerInternalMock.getRefreshRateLimitations(DISPLAY_ID)).thenReturn(
                List.of(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE,
                        initialRefreshRate, initialRefreshRate)));

        // Verify that there is no HBM vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, initialRefreshRate);

        // Change refresh rate vote value through DeviceConfig, ensure it takes precedence
        final int updatedRefreshRate = 90;
        mInjector.getDeviceConfig().setRefreshRateInHbmSunlight(updatedRefreshRate);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getHbmObserver().getRefreshRateInHbmSunlight())
                .isEqualTo(updatedRefreshRate);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, updatedRefreshRate);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(0.43f, 0.1f, 0.8f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn HBM on again and ensure the updated vote value stuck
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, updatedRefreshRate);

        // Reset DeviceConfig refresh rate, ensure vote falls back to the initial value
        mInjector.getDeviceConfig().setRefreshRateInHbmSunlight(0);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getHbmObserver().getRefreshRateInHbmSunlight()).isEqualTo(0);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, initialRefreshRate);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(0.43f, 0.1f, 0.8f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);
    }

    @Test
    public void testHbmVoting_forSunlight_NoLimitation() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> captor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(captor.capture(), any(Handler.class),
                  eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                  | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED));
        DisplayListener listener = captor.getValue();

        // Specify Limitation for different display
        when(mDisplayManagerInternalMock.getRefreshRateLimitations(DISPLAY_ID + 1)).thenReturn(
                List.of(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE,
                        60.f, 60.f)));

        // Verify that there is no HBM vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(0.43f, 0.1f, 0.8f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);
    }

    @Test
    public void testHbmVoting_HbmUnsupported() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> captor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(captor.capture(), any(Handler.class),
                  eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                  | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED));
        DisplayListener listener = captor.getValue();

        // Specify Limitation
        when(mDisplayManagerInternalMock.getRefreshRateLimitations(DISPLAY_ID)).thenReturn(
                List.of(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE,
                        60.0f, 60.0f)));

        // Verify that there is no HBM vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HBM when HBM is supported; expect a valid transition point and a vote.
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, 60.0f);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on Sunlight HBM when HBM is unsupported; expect an invalid transition point and
        // no vote.
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT,
                    HBM_TRANSITION_POINT_INVALID, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HDR HBM when HBM is unsupported; expect an invalid transition point and
        // no vote.
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR,
                    HBM_TRANSITION_POINT_INVALID, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn off HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);
    }

    private void setHbmAndAssertRefreshRate(
            DisplayModeDirector director, DisplayListener listener, int mode, float rr) {
        when(mInjector.getBrightnessInfo(DISPLAY_ID))
                .thenReturn(new BrightnessInfo(1.0f, 0.0f, 1.0f, mode, TRANSITION_POINT,
                    BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);

        final Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        if (Float.isNaN(rr)) {
            assertNull(vote);
        } else {
            assertVoteForPhysicalRefreshRate(vote, rr);
        }
    }

    @Test
    public void testHbmVoting_forSunlightAndHdr() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.0f, 90.0f}, 0);

        // Specify HDR limitation before starting DisplayModeDirector to avoid waiting on property
        // propagation
        final int hdrRr = 60;
        mInjector.getDeviceConfig().setRefreshRateInHbmHdr(hdrRr);
        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> captor = ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(captor.capture(), any(Handler.class),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                        | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED));
        DisplayListener listener = captor.getValue();

        // Specify Sunlight limitations
        final float sunlightRr = 90.0f;
        when(mDisplayManagerInternalMock.getRefreshRateLimitations(DISPLAY_ID))
                .thenReturn(List.of(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE, sunlightRr,
                        sunlightRr)));

        // Verify that there is no HBM vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Verify all state transitions
        setHbmAndAssertRefreshRate(
                director, listener, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT, sunlightRr);
        setHbmAndAssertRefreshRate(
                director, listener, BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR, hdrRr);
        setHbmAndAssertRefreshRate(
                director, listener, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF, Float.NaN);
        setHbmAndAssertRefreshRate(
                director, listener, BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR, hdrRr);
        setHbmAndAssertRefreshRate(
                director, listener, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT, sunlightRr);
        setHbmAndAssertRefreshRate(
                director, listener, BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF, Float.NaN);
    }

    @Test
    public void testHbmVoting_RemovedDisplay() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> captor =
                  ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(captor.capture(), any(Handler.class),
                  eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                  | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED));
        DisplayListener listener = captor.getValue();

        // Specify Limitation for different display
        when(mDisplayManagerInternalMock.getRefreshRateLimitations(DISPLAY_ID)).thenReturn(
                List.of(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE,
                        60.f, 60.f)));

        // Verify that there is no HBM vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);

        // Turn on HBM
        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(1.0f, 0.0f, 1.0f, BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT,
                    TRANSITION_POINT, BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertVoteForPhysicalRefreshRate(vote, 60.f);

        // Turn off HBM
        listener.onDisplayRemoved(DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE);
        assertNull(vote);
    }

    @Test
    public void testSkinTemperature() throws RemoteException {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<IThermalEventListener> thermalEventListener =
                ArgumentCaptor.forClass(IThermalEventListener.class);

        verify(mInjector).registerThermalServiceListener(thermalEventListener.capture());
        final IThermalEventListener listener = thermalEventListener.getValue();

        // Verify that there is no skin temperature vote initially.
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_SKIN_TEMPERATURE);
        assertNull(vote);

        // Set the skin temperature to critical and verify that we added a vote.
        listener.notifyThrottling(getSkinTemp(Temperature.THROTTLING_CRITICAL));
        BackgroundThread.getHandler().runWithScissors(() -> { }, 500 /*timeout*/);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_SKIN_TEMPERATURE);
        assertVoteForRenderFrameRateRange(vote, 0f, 60.f);

        // Set the skin temperature to severe and verify that the vote is gone.
        listener.notifyThrottling(getSkinTemp(Temperature.THROTTLING_SEVERE));
        BackgroundThread.getHandler().runWithScissors(() -> { }, 500 /*timeout*/);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_SKIN_TEMPERATURE);
        assertNull(vote);
    }

    @Test
    public void testNotifyDefaultDisplayDeviceUpdated() {
        Resources resources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(resources);
        when(resources.getInteger(com.android.internal.R.integer.config_defaultPeakRefreshRate))
            .thenReturn(75);
        when(resources.getInteger(R.integer.config_defaultRefreshRate))
            .thenReturn(45);
        when(resources.getInteger(R.integer.config_fixedRefreshRateInHighZone))
            .thenReturn(65);
        when(resources.getInteger(R.integer.config_defaultRefreshRateInZone))
            .thenReturn(85);
        when(resources.getInteger(R.integer.config_defaultRefreshRateInHbmHdr))
            .thenReturn(95);
        when(resources.getInteger(R.integer.config_defaultRefreshRateInHbmSunlight))
            .thenReturn(100);
        when(resources.getIntArray(R.array.config_brightnessThresholdsOfPeakRefreshRate))
            .thenReturn(new int[]{5});
        when(resources.getIntArray(R.array.config_ambientThresholdsOfPeakRefreshRate))
            .thenReturn(new int[]{10});
        when(
            resources.getIntArray(R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate))
            .thenReturn(new int[]{250});
        when(
            resources.getIntArray(R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate))
            .thenReturn(new int[]{7000});
        when(resources.getInteger(
            com.android.internal.R.integer.config_displayWhiteBalanceBrightnessFilterHorizon))
            .thenReturn(3);
        ArgumentCaptor<TypedValue> valueArgumentCaptor = ArgumentCaptor.forClass(TypedValue.class);
        doAnswer((Answer<Void>) invocation -> {
            valueArgumentCaptor.getValue().type = 4;
            valueArgumentCaptor.getValue().data = 13;
            return null;
        }).when(resources).getValue(eq(com.android.internal.R.dimen
                .config_displayWhiteBalanceBrightnessFilterIntercept),
                valueArgumentCaptor.capture(), eq(true));
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.0f, 90.0f}, 0);
        SensorManager sensorManager = createMockSensorManager(createLightSensor());
        director.start(sensorManager);
        // We don't expect any interaction with DeviceConfig when the director is initialized
        // because we explicitly avoid doing this as this can lead to a latency spike in the
        // startup of DisplayManagerService
        // Verify all the loaded values are from config.xml
        assertEquals(director.getSettingsObserver().getDefaultRefreshRate(), 45, 0.0);
        assertEquals(director.getSettingsObserver().getDefaultPeakRefreshRate(), 75,
                0.0);
        assertEquals(director.getBrightnessObserver().getRefreshRateInHighZone(), 65);
        assertEquals(director.getBrightnessObserver().getRefreshRateInLowZone(), 85);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmHdr(), 95);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmSunlight(), 100);
        assertArrayEquals(director.getBrightnessObserver().getHighDisplayBrightnessThresholds(),
                new float[]{ BrightnessSynchronizer.brightnessIntToFloat(250) }, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getHighAmbientBrightnessThresholds(),
                new float[]{7000}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowDisplayBrightnessThresholds(),
                new float[]{ BrightnessSynchronizer.brightnessIntToFloat(5) }, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowAmbientBrightnessThresholds(),
                new float[]{10}, /* delta= */ 0);


        // Notify that the default display is updated, such that DisplayDeviceConfig has new values
        DisplayDeviceConfig displayDeviceConfig = mock(DisplayDeviceConfig.class);
        when(displayDeviceConfig.getDefaultLowBlockingZoneRefreshRate()).thenReturn(50);
        when(displayDeviceConfig.getDefaultHighBlockingZoneRefreshRate()).thenReturn(55);
        when(displayDeviceConfig.getDefaultRefreshRate()).thenReturn(60);
        when(displayDeviceConfig.getDefaultPeakRefreshRate()).thenReturn(65);
        when(displayDeviceConfig.getLowDisplayBrightnessThresholds())
                .thenReturn(new float[]{0.025f});
        when(displayDeviceConfig.getLowAmbientBrightnessThresholds())
                .thenReturn(new float[]{30});
        when(displayDeviceConfig.getHighDisplayBrightnessThresholds())
                .thenReturn(new float[]{0.21f});
        when(displayDeviceConfig.getHighAmbientBrightnessThresholds())
                .thenReturn(new float[]{2100});
        when(displayDeviceConfig.getDefaultRefreshRateInHbmHdr()).thenReturn(65);
        when(displayDeviceConfig.getDefaultRefreshRateInHbmSunlight()).thenReturn(75);
        director.defaultDisplayDeviceUpdated(displayDeviceConfig);

        // Verify the new values are from the freshly loaded DisplayDeviceConfig.
        assertEquals(director.getSettingsObserver().getDefaultRefreshRate(), 60, 0.0);
        assertEquals(director.getSettingsObserver().getDefaultPeakRefreshRate(), 65,
                0.0);
        assertEquals(director.getBrightnessObserver().getRefreshRateInHighZone(), 55);
        assertEquals(director.getBrightnessObserver().getRefreshRateInLowZone(), 50);
        assertArrayEquals(director.getBrightnessObserver().getHighDisplayBrightnessThresholds(),
                new float[]{0.21f}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getHighAmbientBrightnessThresholds(),
                new float[]{2100}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowDisplayBrightnessThresholds(),
                new float[]{0.025f}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowAmbientBrightnessThresholds(),
                new float[]{30}, /* delta= */ 0);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmHdr(), 65);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmSunlight(), 75);

        // Notify that the default display is updated, such that DeviceConfig has new values
        FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setDefaultPeakRefreshRate(60);
        config.setRefreshRateInHighZone(65);
        config.setRefreshRateInLowZone(70);
        config.setLowAmbientBrightnessThresholds(new int[]{20});
        config.setLowDisplayBrightnessThresholds(new int[]{10});
        config.setHighDisplayBrightnessThresholds(new int[]{255});
        config.setHighAmbientBrightnessThresholds(new int[]{8000});
        config.setRefreshRateInHbmHdr(70);
        config.setRefreshRateInHbmSunlight(80);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();

        // Verify the values are loaded from the DeviceConfig.
        assertEquals(director.getSettingsObserver().getDefaultRefreshRate(), 60, 0.0);
        assertEquals(director.getSettingsObserver().getDefaultPeakRefreshRate(), 60,
                0.0);
        assertEquals(director.getBrightnessObserver().getRefreshRateInHighZone(), 65);
        assertEquals(director.getBrightnessObserver().getRefreshRateInLowZone(), 70);
        assertArrayEquals(director.getBrightnessObserver().getHighDisplayBrightnessThresholds(),
                new float[]{ BrightnessSynchronizer.brightnessIntToFloat(255) }, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getHighAmbientBrightnessThresholds(),
                new float[]{8000}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowDisplayBrightnessThresholds(),
                new float[]{ BrightnessSynchronizer.brightnessIntToFloat(10) }, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowAmbientBrightnessThresholds(),
                new float[]{20}, /* delta= */ 0);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmHdr(), 70);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmSunlight(), 80);

        // Reset the DeviceConfig
        config.setDefaultPeakRefreshRate(null);
        config.setRefreshRateInHighZone(null);
        config.setRefreshRateInLowZone(null);
        config.setLowAmbientBrightnessThresholds(new int[]{});
        config.setLowDisplayBrightnessThresholds(new int[]{});
        config.setHighDisplayBrightnessThresholds(new int[]{});
        config.setHighAmbientBrightnessThresholds(new int[]{});
        config.setRefreshRateInHbmHdr(null);
        config.setRefreshRateInHbmSunlight(null);
        waitForIdleSync();

        // verify the new values now fallback to DisplayDeviceConfig
        assertEquals(director.getSettingsObserver().getDefaultRefreshRate(), 60, 0.0);
        assertEquals(director.getSettingsObserver().getDefaultPeakRefreshRate(), 65,
                0.0);
        assertEquals(director.getBrightnessObserver().getRefreshRateInHighZone(), 55);
        assertEquals(director.getBrightnessObserver().getRefreshRateInLowZone(), 50);
        assertArrayEquals(director.getBrightnessObserver().getHighDisplayBrightnessThresholds(),
                new float[]{0.21f}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getHighAmbientBrightnessThresholds(),
                new float[]{2100}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowDisplayBrightnessThresholds(),
                new float[]{0.025f}, /* delta= */ 0);
        assertArrayEquals(director.getBrightnessObserver().getLowAmbientBrightnessThresholds(),
                new float[]{30}, /* delta= */ 0);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmHdr(), 65);
        assertEquals(director.getHbmObserver().getRefreshRateInHbmSunlight(), 75);
    }

    @Test
    public void testSensorReloadOnDeviceSwitch() throws Exception {
        // First, configure brightness zones or DMD won't register for sensor data.
        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInHighZone(60);
        config.setHighDisplayBrightnessThresholds(new int[] { 255 });
        config.setHighAmbientBrightnessThresholds(new int[] { 8000 });

        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90 /*fps*/);
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        Sensor lightSensorOne = TestUtils.createSensor(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        Sensor lightSensorTwo = TestUtils.createSensor(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        SensorManager sensorManager = createMockSensorManager(lightSensorOne, lightSensorTwo);
        when(sensorManager.getDefaultSensor(5)).thenReturn(lightSensorOne, lightSensorTwo);
        director.start(sensorManager);
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensorOne),
                        anyInt(),
                        any(Handler.class));

        DisplayDeviceConfig ddcMock = mock(DisplayDeviceConfig.class);
        when(ddcMock.getDefaultLowBlockingZoneRefreshRate()).thenReturn(50);
        when(ddcMock.getDefaultHighBlockingZoneRefreshRate()).thenReturn(55);
        when(ddcMock.getLowDisplayBrightnessThresholds()).thenReturn(new float[]{0.025f});
        when(ddcMock.getLowAmbientBrightnessThresholds()).thenReturn(new float[]{30});
        when(ddcMock.getHighDisplayBrightnessThresholds()).thenReturn(new float[]{0.21f});
        when(ddcMock.getHighAmbientBrightnessThresholds()).thenReturn(new float[]{2100});

        Resources resMock = mock(Resources.class);
        when(resMock.getInteger(
                com.android.internal.R.integer.config_displayWhiteBalanceBrightnessFilterHorizon))
                .thenReturn(3);
        ArgumentCaptor<TypedValue> valueArgumentCaptor = ArgumentCaptor.forClass(TypedValue.class);
        doAnswer((Answer<Void>) invocation -> {
            valueArgumentCaptor.getValue().type = 4;
            valueArgumentCaptor.getValue().data = 13;
            return null;
        }).when(resMock).getValue(anyInt(), valueArgumentCaptor.capture(), eq(true));
        when(mContext.getResources()).thenReturn(resMock);

        director.defaultDisplayDeviceUpdated(ddcMock);

        verify(sensorManager).unregisterListener(any(SensorEventListener.class));
        verify(sensorManager).registerListener(any(SensorEventListener.class),
                eq(lightSensorTwo), anyInt(), any(Handler.class));
    }

    @Test
    public void testAuthenticationPossibleSetsPhysicalRateRangesToMax() throws RemoteException {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.0f, 90.0f}, 0);
        // don't call director.start(createMockSensorManager());
        // DisplayObserver will reset mSupportedModesByDisplay
        director.onBootCompleted();
        ArgumentCaptor<IUdfpsRefreshRateRequestCallback> captor =
                ArgumentCaptor.forClass(IUdfpsRefreshRateRequestCallback.class);
        verify(mStatusBarMock).setUdfpsRefreshRateCallback(captor.capture());

        captor.getValue().onAuthenticationPossible(DISPLAY_ID, true);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE);
        assertVoteForPhysicalRefreshRate(vote, 90);
    }

    @Test
    public void testAuthenticationPossibleUnsetsVote() throws RemoteException {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());
        director.onBootCompleted();
        ArgumentCaptor<IUdfpsRefreshRateRequestCallback> captor =
                ArgumentCaptor.forClass(IUdfpsRefreshRateRequestCallback.class);
        verify(mStatusBarMock).setUdfpsRefreshRateCallback(captor.capture());
        captor.getValue().onAuthenticationPossible(DISPLAY_ID, true);
        captor.getValue().onAuthenticationPossible(DISPLAY_ID, false);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE);
        assertNull(vote);
    }

    @Test
    public void testUdfpsRequestSetsPhysicalRateRangesToMax() throws RemoteException {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.0f, 90.0f}, 0);
        // don't call director.start(createMockSensorManager());
        // DisplayObserver will reset mSupportedModesByDisplay
        director.onBootCompleted();
        ArgumentCaptor<IUdfpsRefreshRateRequestCallback> captor =
                ArgumentCaptor.forClass(IUdfpsRefreshRateRequestCallback.class);
        verify(mStatusBarMock).setUdfpsRefreshRateCallback(captor.capture());

        captor.getValue().onRequestEnabled(DISPLAY_ID);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_UDFPS);
        assertVoteForPhysicalRefreshRate(vote, 90);
    }

    @Test
    public void testUdfpsRequestUnsetsUnsetsVote() throws RemoteException {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());
        director.onBootCompleted();
        ArgumentCaptor<IUdfpsRefreshRateRequestCallback> captor =
                ArgumentCaptor.forClass(IUdfpsRefreshRateRequestCallback.class);
        verify(mStatusBarMock).setUdfpsRefreshRateCallback(captor.capture());
        captor.getValue().onRequestEnabled(DISPLAY_ID);
        captor.getValue().onRequestDisabled(DISPLAY_ID);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_UDFPS);
        assertNull(vote);
    }

    @Test
    public void testUpdateLayoutLimitedRefreshRate_validDisplayInfo() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        float refreshRate = 60;
        mInjector.mDisplayInfo.layoutLimitedRefreshRate =
                new RefreshRateRange(refreshRate, refreshRate);
        displayListener.onDisplayChanged(DISPLAY_ID);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_LAYOUT_LIMITED_FRAME_RATE);
        assertVoteForPhysicalRefreshRate(vote, /* refreshRate= */ refreshRate);

        mInjector.mDisplayInfo.layoutLimitedRefreshRate = null;
        displayListener.onDisplayChanged(DISPLAY_ID);

        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_LAYOUT_LIMITED_FRAME_RATE);
        assertNull(vote);
    }

    @Test
    public void testUpdateLayoutLimitedRefreshRate_invalidDisplayInfo() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[]{60.0f, 90.0f}, 0);
        director.start(createMockSensorManager());

        ArgumentCaptor<DisplayListener> displayListenerCaptor =
                ArgumentCaptor.forClass(DisplayListener.class);
        verify(mInjector).registerDisplayListener(displayListenerCaptor.capture(),
                any(Handler.class));
        DisplayListener displayListener = displayListenerCaptor.getValue();

        mInjector.mDisplayInfo.layoutLimitedRefreshRate = new RefreshRateRange(10, 10);
        mInjector.mDisplayInfoValid = false;
        displayListener.onDisplayChanged(DISPLAY_ID);

        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_LAYOUT_LIMITED_FRAME_RATE);
        assertNull(vote);
    }

    private Temperature getSkinTemp(@Temperature.ThrottlingStatus int status) {
        return new Temperature(30.0f, Temperature.TYPE_SKIN, "test_skin_temp", status);
    }

    private void assertVoteForPhysicalRefreshRate(Vote vote, float refreshRate) {
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(CombinedVote.class);
        CombinedVote combinedVote = (CombinedVote) vote;
        RefreshRateVote.PhysicalVote physicalVote =
                (RefreshRateVote.PhysicalVote) combinedVote.mVotes.get(0);
        assertThat(physicalVote.mMinRefreshRate).isWithin(FLOAT_TOLERANCE).of(refreshRate);
        assertThat(physicalVote.mMaxRefreshRate).isWithin(FLOAT_TOLERANCE).of(refreshRate);
    }

    private void assertVoteForRenderFrameRateRange(
            Vote vote, float frameRateLow, float frameRateHigh) {
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        RefreshRateVote.RenderVote renderVote = (RefreshRateVote.RenderVote) vote;
        assertThat(renderVote.mMinRefreshRate).isEqualTo(frameRateLow);
        assertThat(renderVote.mMaxRefreshRate).isEqualTo(frameRateHigh);
    }

    public static class FakeDeviceConfig extends FakeDeviceConfigInterface {
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

        void setRefreshRateInLowZone(Integer fps) {
            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_REFRESH_RATE_IN_LOW_ZONE,
                    String.valueOf(fps));
        }

        void setRefreshRateInHbmSunlight(Integer fps) {
            putPropertyAndNotify(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_REFRESH_RATE_IN_HBM_SUNLIGHT, String.valueOf(fps));
        }

        void setRefreshRateInHbmHdr(Integer fps) {
            putPropertyAndNotify(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_REFRESH_RATE_IN_HBM_HDR, String.valueOf(fps));
        }

        public void setThermalBrightnessThrottlingData(String brightnessThrottlingData) {
            putPropertyAndNotify(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_BRIGHTNESS_THROTTLING_DATA, brightnessThrottlingData);
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

        void setRefreshRateInHighZone(Integer fps) {
            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_REFRESH_RATE_IN_HIGH_ZONE,
                    String.valueOf(fps));
        }

        void setDefaultPeakRefreshRate(Integer fps) {
            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_PEAK_REFRESH_RATE_DEFAULT,
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

    private void setBrightness(int brightness, int adjustedBrightness, DisplayListener listener) {
        float floatBri = BrightnessSynchronizer.brightnessIntToFloat(brightness);
        float floatAdjBri = BrightnessSynchronizer.brightnessIntToFloat(adjustedBrightness);

        when(mInjector.getBrightnessInfo(DISPLAY_ID)).thenReturn(
                new BrightnessInfo(floatBri, floatAdjBri, 0.0f, 1.0f,
                    BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF, TRANSITION_POINT,
                    BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE));
        listener.onDisplayChanged(DISPLAY_ID);
    }

    private void setPeakRefreshRate(float fps) {
        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.PEAK_REFRESH_RATE,
                 fps);
        mInjector.notifyPeakRefreshRateChanged();
        waitForIdleSync();
    }

    private void setMinRefreshRate(float fps) {
        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.MIN_REFRESH_RATE,
                fps);
        mInjector.notifyMinRefreshRateChanged();
        waitForIdleSync();
    }

    private static SensorManager createMockSensorManager(Sensor... sensors) {
        SensorManager sensorManager = mock(SensorManager.class);
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

    public static class FakesInjector implements DisplayModeDirector.Injector {
        private final FakeDeviceConfig mDeviceConfig;
        private final DisplayInfo mDisplayInfo;
        private final Display mDisplay;
        private boolean mDisplayInfoValid = true;
        private final DisplayManagerInternal mDisplayManagerInternal;
        private final StatusBarManagerInternal mStatusBarManagerInternal;
        private final SensorManagerInternal mSensorManagerInternal;

        private ContentObserver mPeakRefreshRateObserver;
        private ContentObserver mMinRefreshRateObserver;

        FakesInjector() {
            this(null, null, null);
        }

        FakesInjector(DisplayManagerInternal displayManagerInternal,
                StatusBarManagerInternal statusBarManagerInternal,
                SensorManagerInternal sensorManagerInternal) {
            mDeviceConfig = new FakeDeviceConfig();
            mDisplayInfo = new DisplayInfo();
            mDisplayInfo.defaultModeId = MODE_ID;
            mDisplayInfo.supportedModes = new Display.Mode[] {new Display.Mode(MODE_ID,
                    800, 600, /* refreshRate= */ 60)};
            mDisplay = createDisplay(DISPLAY_ID);
            mDisplayManagerInternal = displayManagerInternal;
            mStatusBarManagerInternal = statusBarManagerInternal;
            mSensorManagerInternal = sensorManagerInternal;
        }

        @NonNull
        public FakeDeviceConfig getDeviceConfig() {
            return mDeviceConfig;
        }

        @Override
        public void registerPeakRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mPeakRefreshRateObserver = observer;
        }

        @Override
        public void registerMinRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mMinRefreshRateObserver = observer;
        }

        @Override
        public void registerDisplayListener(DisplayListener listener, Handler handler) {}

        @Override
        public void registerDisplayListener(DisplayListener listener, Handler handler, long flag) {}

        @Override
        public Display getDisplay(int displayId) {
            return mDisplay;
        }

        @Override
        public Display[] getDisplays() {
            return new Display[] { mDisplay };
        }

        @Override
        public boolean getDisplayInfo(int displayId, DisplayInfo displayInfo) {
            displayInfo.copyFrom(mDisplayInfo);
            return mDisplayInfoValid;
        }

        @Override
        public BrightnessInfo getBrightnessInfo(int displayId) {
            return null;
        }

        @Override
        public boolean isDozeState(Display d) {
            return false;
        }

        @Override
        public boolean registerThermalServiceListener(IThermalEventListener listener) {
            return true;
        }

        @Override
        public void unregisterThermalServiceListener(IThermalEventListener listener) {
        }

        @Override
        public boolean supportsFrameRateOverride() {
            return true;
        }

        @Override
        public DisplayManagerInternal getDisplayManagerInternal() {
            return mDisplayManagerInternal;
        }

        @Override
        public StatusBarManagerInternal getStatusBarManagerInternal() {
            return mStatusBarManagerInternal;
        }

        @Override
        public SensorManagerInternal getSensorManagerInternal() {
            return mSensorManagerInternal;
        }

        protected Display createDisplay(int id) {
            return new Display(DisplayManagerGlobal.getInstance(), id, mDisplayInfo,
                    ApplicationProvider.getApplicationContext().getResources());
        }

        void notifyPeakRefreshRateChanged() {
            if (mPeakRefreshRateObserver != null) {
                mPeakRefreshRateObserver.dispatchChange(false /*selfChange*/,
                        PEAK_REFRESH_RATE_URI);
            }
        }

        void notifyMinRefreshRateChanged() {
            if (mMinRefreshRateObserver != null) {
                mMinRefreshRateObserver.dispatchChange(false /*selfChange*/,
                        MIN_REFRESH_RATE_URI);
            }
        }
    }
}
