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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.display.DisplayModeDirector.BrightnessObserver;
import com.android.server.display.DisplayModeDirector.DesiredDisplayModeSpecs;
import com.android.server.display.DisplayModeDirector.Vote;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayModeDirectorTest {
    // The tolerance within which we consider something approximately equals.
    private static final float FLOAT_TOLERANCE = 0.01f;

    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private DisplayModeDirector createDirectorFromRefreshRateArray(
            float[] refreshRates, int baseModeId) {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, new Handler(Looper.getMainLooper()));
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
    public void testBrightnessHasLowerPriorityThanUser() {
        assertTrue(Vote.PRIORITY_LOW_BRIGHTNESS < Vote.PRIORITY_APP_REQUEST_REFRESH_RATE);
        assertTrue(Vote.PRIORITY_LOW_BRIGHTNESS < Vote.PRIORITY_APP_REQUEST_SIZE);

        int displayId = 0;
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(displayId, votes);
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(60, 90));
        votes.put(Vote.PRIORITY_LOW_BRIGHTNESS, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(60, 90));
        votes.put(Vote.PRIORITY_LOW_BRIGHTNESS, Vote.forRefreshRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(90, 90));
        votes.put(Vote.PRIORITY_LOW_BRIGHTNESS, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, Vote.forRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_LOW_BRIGHTNESS, Vote.forRefreshRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(displayId);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        Truth.assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
    }

    @Test
    public void testAppRequestRefreshRateRange() {
        // Confirm that the app request range doesn't include low brightness or min refresh rate
        // settings, but does include everything else.
        assertTrue(
                Vote.PRIORITY_LOW_BRIGHTNESS < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE
                < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_APP_REQUEST_REFRESH_RATE
                >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);

        int displayId = 0;
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(displayId, votes);
        votes.put(Vote.PRIORITY_LOW_BRIGHTNESS, Vote.forRefreshRates(60, 60));
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
}
