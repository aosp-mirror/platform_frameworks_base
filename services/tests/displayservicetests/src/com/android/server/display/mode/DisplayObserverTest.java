/*
 * Copyright (C) 2023 The Android Open Source Project
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


import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.Mode.INVALID_MODE_ID;

import static com.android.server.display.mode.DisplayModeDirector.SYNCHRONIZED_REFRESH_RATE_TOLERANCE;
import static com.android.server.display.mode.Vote.PRIORITY_LIMIT_MODE;
import static com.android.server.display.mode.Vote.PRIORITY_SYNCHRONIZED_REFRESH_RATE;
import static com.android.server.display.mode.Vote.PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE;
import static com.android.server.display.mode.VotesStorage.GLOBAL_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfigInterface;
import android.test.mock.MockContentResolver;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.sensors.SensorManagerInternal;

import junitparams.JUnitParamsRunner;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(JUnitParamsRunner.class)
public class DisplayObserverTest {
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    private static final int EXTERNAL_DISPLAY = 1;
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;
    private static final int MAX_REFRESH_RATE = 60;

    private final Display.Mode[] mInternalDisplayModes = new Display.Mode[] {
            new Display.Mode(/*modeId=*/ 0, MAX_WIDTH / 2, MAX_HEIGHT / 2,
                    (float) MAX_REFRESH_RATE / 2),
            new Display.Mode(/*modeId=*/ 1, MAX_WIDTH / 2, MAX_HEIGHT / 2,
                    MAX_REFRESH_RATE),
            new Display.Mode(/*modeId=*/ 2, MAX_WIDTH / 2, MAX_HEIGHT / 2,
                    MAX_REFRESH_RATE * 2),
            new Display.Mode(/*modeId=*/ 3, MAX_WIDTH, MAX_HEIGHT, MAX_REFRESH_RATE * 2),
            new Display.Mode(/*modeId=*/ 4, MAX_WIDTH, MAX_HEIGHT, MAX_REFRESH_RATE),
            new Display.Mode(/*modeId=*/ 5, MAX_WIDTH * 2, MAX_HEIGHT * 2,
                    MAX_REFRESH_RATE),
            new Display.Mode(/*modeId=*/ 6, MAX_WIDTH / 2, MAX_HEIGHT / 2,
                    MAX_REFRESH_RATE * 3),
    };

    private final Display.Mode[] mExternalDisplayModes = new Display.Mode[] {
            new Display.Mode(/*modeId=*/ 0, MAX_WIDTH / 2, MAX_HEIGHT / 2,
                    (float) MAX_REFRESH_RATE / 2),
            new Display.Mode(/*modeId=*/ 1, MAX_WIDTH / 2, MAX_HEIGHT / 2,
                    MAX_REFRESH_RATE),
            new Display.Mode(/*modeId=*/ 2, MAX_WIDTH / 2, MAX_HEIGHT / 2,
                    MAX_REFRESH_RATE * 2),
            new Display.Mode(/*modeId=*/ 3, MAX_WIDTH, MAX_HEIGHT, MAX_REFRESH_RATE * 2),
            new Display.Mode(/*modeId=*/ 4, MAX_WIDTH, MAX_HEIGHT, MAX_REFRESH_RATE),
            new Display.Mode(/*modeId=*/ 5, MAX_WIDTH * 2, MAX_HEIGHT * 2,
                    MAX_REFRESH_RATE),
    };

    private DisplayModeDirector mDmd;
    private Context mContext;
    private DisplayModeDirector.Injector mInjector;
    private Handler mHandler;
    private DisplayManager.DisplayListener mObserver;
    private Resources mResources;
    @Mock
    private DisplayManagerFlags mDisplayManagerFlags;
    @Mock
    private DisplayModeDirector.DisplayDeviceConfigProvider mDisplayDeviceConfigProvider;
    private int mExternalDisplayUserPreferredModeId = INVALID_MODE_ID;
    private int mInternalDisplayUserPreferredModeId = INVALID_MODE_ID;
    private Display mDefaultDisplay;
    private Display mExternalDisplay;

    /** Setup tests. */
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mHandler = new Handler(Looper.getMainLooper());
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        mResources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(mResources);
        MockContentResolver resolver = mSettingsProviderRule.mockContentResolver(mContext);
        when(mContext.getContentResolver()).thenReturn(resolver);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakRefreshRate))
                .thenReturn(0);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakWidth))
                .thenReturn(0);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakHeight))
                .thenReturn(0);
        when(mResources.getBoolean(R.bool.config_refreshRateSynchronizationEnabled))
                .thenReturn(false);

        // Necessary configs to initialize DisplayModeDirector
        when(mResources.getIntArray(R.array.config_brightnessThresholdsOfPeakRefreshRate))
                .thenReturn(new int[]{5});
        when(mResources.getIntArray(R.array.config_ambientThresholdsOfPeakRefreshRate))
                .thenReturn(new int[]{10});
        when(mResources.getIntArray(
                R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate))
                .thenReturn(new int[]{250});
        when(mResources.getIntArray(
                R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate))
                .thenReturn(new int[]{7000});
    }

    /** No vote for user preferred mode */
    @Test
    public void testExternalDisplay_notVotedUserPreferredMode() {
        var preferredMode = mExternalDisplayModes[5];
        mExternalDisplayUserPreferredModeId = preferredMode.getModeId();

        init();
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);

        // Testing that the vote is not added when display is added because feature is disabled
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);

        // Testing that the vote is not present after display is removed
        mObserver.onDisplayRemoved(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);

        // Testing that the vote is not added when display is changed because feature is disabled
        mObserver.onDisplayChanged(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
    }

    /** Vote for user preferred mode */
    @Test
    public void testExternalDisplay_voteUserPreferredMode() {
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        var preferredMode = mExternalDisplayModes[5];
        mExternalDisplayUserPreferredModeId = preferredMode.getModeId();
        var expectedVote = Vote.forSize(
                        preferredMode.getPhysicalWidth(),
                        preferredMode.getPhysicalHeight());
        init();
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(expectedVote);

        mExternalDisplayUserPreferredModeId = INVALID_MODE_ID;
        mObserver.onDisplayChanged(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);

        preferredMode = mExternalDisplayModes[4];
        mExternalDisplayUserPreferredModeId = preferredMode.getModeId();
        expectedVote = Vote.forSize(
                        preferredMode.getPhysicalWidth(),
                        preferredMode.getPhysicalHeight());
        mObserver.onDisplayChanged(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(expectedVote);

        // Testing that the vote is removed.
        mObserver.onDisplayRemoved(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
    }

    /** External display: Do not apply limit to user preferred mode */
    @Test
    public void testExternalDisplay_doNotApplyLimitToUserPreferredMode() {
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakRefreshRate))
                .thenReturn(MAX_REFRESH_RATE);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakWidth))
                .thenReturn(MAX_WIDTH);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakHeight))
                .thenReturn(MAX_HEIGHT);

        var preferredMode = mExternalDisplayModes[5];
        mExternalDisplayUserPreferredModeId = preferredMode.getModeId();
        var expectedResolutionVote = Vote.forSize(preferredMode.getPhysicalWidth(),
                preferredMode.getPhysicalHeight());
        init();
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(expectedResolutionVote);

        // Testing that the vote is removed.
        mObserver.onDisplayRemoved(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
    }

    /** Default display: Do not apply limit to user preferred mode */
    @Test
    public void testDefaultDisplayAdded_notAppliedLimitToUserPreferredMode() {
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakRefreshRate))
                .thenReturn(MAX_REFRESH_RATE);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakWidth))
                .thenReturn(MAX_WIDTH);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakHeight))
                .thenReturn(MAX_HEIGHT);
        var preferredMode = mInternalDisplayModes[5];
        mInternalDisplayUserPreferredModeId = preferredMode.getModeId();
        var expectedResolutionVote = Vote.forSize(preferredMode.getPhysicalWidth(),
                        preferredMode.getPhysicalHeight());
        init();
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
        mObserver.onDisplayAdded(DEFAULT_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(expectedResolutionVote);
        mObserver.onDisplayRemoved(DEFAULT_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE))
                .isEqualTo(null);
    }

    /** Default display added, no mode limit set */
    @Test
    public void testDefaultDisplayAdded() {
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakRefreshRate))
                .thenReturn(MAX_REFRESH_RATE);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakWidth))
                .thenReturn(MAX_WIDTH);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakHeight))
                .thenReturn(MAX_HEIGHT);
        init();
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        mObserver.onDisplayAdded(DEFAULT_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
    }

    /** External display added, apply resolution refresh rate limit */
    @Test
    public void testExternalDisplayAdded_applyResolutionRefreshRateLimit() {
        when(mDisplayManagerFlags.isDisplayResolutionRangeVotingEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakRefreshRate))
                .thenReturn(MAX_REFRESH_RATE);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakWidth))
                .thenReturn(MAX_WIDTH);
        when(mResources.getInteger(R.integer.config_externalDisplayPeakHeight))
                .thenReturn(MAX_HEIGHT);
        init();
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(
                Vote.forSizeAndPhysicalRefreshRatesRange(0, 0,
                        MAX_WIDTH, MAX_HEIGHT,
                        /*minPhysicalRefreshRate=*/ 0, MAX_REFRESH_RATE));
        mObserver.onDisplayRemoved(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
    }

    /** External display added, disabled resolution refresh rate limit. */
    @Test
    public void testExternalDisplayAdded_disabledResolutionRefreshRateLimit() {
        when(mDisplayManagerFlags.isDisplayResolutionRangeVotingEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        init();
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        mObserver.onDisplayChanged(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        mObserver.onDisplayRemoved(EXTERNAL_DISPLAY);
        assertThat(getVote(DEFAULT_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
        assertThat(getVote(EXTERNAL_DISPLAY, PRIORITY_LIMIT_MODE)).isEqualTo(null);
    }

    /** External display added, applied refresh rates synchronization */
    @Test
    public void testExternalDisplayAdded_appliedRefreshRatesSynchronization() {
        when(mDisplayManagerFlags.isDisplayResolutionRangeVotingEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isDisplaysRefreshRatesSynchronizationEnabled()).thenReturn(true);
        when(mResources.getBoolean(R.bool.config_refreshRateSynchronizationEnabled))
                .thenReturn(true);
        init();
        assertThat(getVote(GLOBAL_ID, PRIORITY_SYNCHRONIZED_REFRESH_RATE)).isEqualTo(null);
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(GLOBAL_ID, PRIORITY_SYNCHRONIZED_REFRESH_RATE)).isEqualTo(
                Vote.forPhysicalRefreshRates(
                        MAX_REFRESH_RATE - SYNCHRONIZED_REFRESH_RATE_TOLERANCE,
                        MAX_REFRESH_RATE + SYNCHRONIZED_REFRESH_RATE_TOLERANCE));

        // Remove external display and check that sync vote is no longer present.
        mObserver.onDisplayRemoved(EXTERNAL_DISPLAY);

        assertThat(getVote(GLOBAL_ID, PRIORITY_SYNCHRONIZED_REFRESH_RATE)).isEqualTo(null);
    }

    /** External display added, disabled feature refresh rates synchronization */
    @Test
    public void testExternalDisplayAdded_disabledFeatureRefreshRatesSynchronization() {
        when(mDisplayManagerFlags.isDisplayResolutionRangeVotingEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isDisplaysRefreshRatesSynchronizationEnabled()).thenReturn(false);
        when(mResources.getBoolean(R.bool.config_refreshRateSynchronizationEnabled))
                .thenReturn(true);
        init();
        assertThat(getVote(GLOBAL_ID, PRIORITY_SYNCHRONIZED_REFRESH_RATE)).isEqualTo(null);
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(GLOBAL_ID, PRIORITY_SYNCHRONIZED_REFRESH_RATE)).isEqualTo(null);
    }

    /** External display not applied refresh rates synchronization, because
     * config_refreshRateSynchronizationEnabled is false. */
    @Test
    public void testExternalDisplay_notAppliedRefreshRatesSynchronization() {
        when(mDisplayManagerFlags.isDisplayResolutionRangeVotingEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isUserPreferredModeVoteEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isExternalDisplayLimitModeEnabled()).thenReturn(true);
        when(mDisplayManagerFlags.isDisplaysRefreshRatesSynchronizationEnabled()).thenReturn(true);
        init();
        assertThat(getVote(GLOBAL_ID, PRIORITY_SYNCHRONIZED_REFRESH_RATE)).isEqualTo(null);
        mObserver.onDisplayAdded(EXTERNAL_DISPLAY);
        assertThat(getVote(GLOBAL_ID, PRIORITY_SYNCHRONIZED_REFRESH_RATE)).isEqualTo(null);
    }

    private void init() {
        mInjector = mock(DisplayModeDirector.Injector.class);
        doAnswer(invocation -> {
            assertThat(mObserver).isNull();
            mObserver = invocation.getArgument(0);
            return null;
        }).when(mInjector).registerDisplayListener(
                any(DisplayModeDirector.DisplayObserver.class), any());

        doAnswer(c -> {
            DisplayInfo info = c.getArgument(1);
            info.type = Display.TYPE_INTERNAL;
            info.displayId = DEFAULT_DISPLAY;
            info.defaultModeId = 0;
            info.supportedModes = mInternalDisplayModes;
            info.userPreferredModeId = mInternalDisplayUserPreferredModeId;
            return true;
        }).when(mInjector).getDisplayInfo(eq(DEFAULT_DISPLAY), /*displayInfo=*/ any());

        doAnswer(c -> {
            DisplayInfo info = c.getArgument(1);
            info.type = Display.TYPE_EXTERNAL;
            info.displayId = EXTERNAL_DISPLAY;
            info.defaultModeId = 0;
            info.supportedModes = mExternalDisplayModes;
            info.userPreferredModeId = mExternalDisplayUserPreferredModeId;
            return true;
        }).when(mInjector).getDisplayInfo(eq(EXTERNAL_DISPLAY), /*displayInfo=*/ any());

        doAnswer(c -> mock(SensorManagerInternal.class))
                .when(mInjector).getSensorManagerInternal();
        doAnswer(c -> mock(DeviceConfigInterface.class)).when(mInjector).getDeviceConfig();
        doAnswer(c -> mock(DisplayManagerInternal.class))
                .when(mInjector).getDisplayManagerInternal();


        mDefaultDisplay = mock(Display.class);
        when(mDefaultDisplay.getDisplayId()).thenReturn(DEFAULT_DISPLAY);
        doAnswer(c -> mInjector.getDisplayInfo(DEFAULT_DISPLAY, c.getArgument(0)))
                .when(mDefaultDisplay).getDisplayInfo(/*displayInfo=*/ any());

        mExternalDisplay = mock(Display.class);
        when(mExternalDisplay.getDisplayId()).thenReturn(EXTERNAL_DISPLAY);
        doAnswer(c -> mInjector.getDisplayInfo(EXTERNAL_DISPLAY, c.getArgument(0)))
                .when(mExternalDisplay).getDisplayInfo(/*displayInfo=*/ any());

        when(mInjector.getDisplays()).thenReturn(new Display[] {mDefaultDisplay, mExternalDisplay});

        mDmd = new DisplayModeDirector(mContext, mHandler, mInjector,
                mDisplayManagerFlags, mDisplayDeviceConfigProvider);
        mDmd.start(null);
        assertThat(mObserver).isNotNull();
    }

    @Nullable
    private Vote getVote(final int displayId, final int priority) {
        return mDmd.getVote(displayId, priority);
    }
}
