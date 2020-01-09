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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayModeDirectorTest {
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private DisplayModeDirector createDisplayModeDirectorWithDisplayFpsRange(
            int minFps, int maxFps) {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, new Handler(Looper.getMainLooper()));
        int displayId = 0;
        int numModes = maxFps - minFps + 1;
        Display.Mode[] modes = new Display.Mode[numModes];
        for (int i = minFps; i <= maxFps; i++) {
            modes[i - minFps] = new Display.Mode(
                    /*modeId=*/i, /*width=*/1000, /*height=*/1000, /*refreshRate=*/i);
        }
        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<Display.Mode[]>();
        supportedModesByDisplay.put(displayId, modes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<Display.Mode>();
        defaultModesByDisplay.put(displayId, modes[0]);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);
        return director;
    }

    private int[] intRange(int min, int max) {
        int[] range = new int[max - min + 1];
        for (int i = min; i <= max; i++) {
            range[i - min] = i;
        }
        return range;
    }

    @Test
    public void testDisplayModeVoting() {
        int displayId = 0;

        // With no votes present, DisplayModeDirector should allow any refresh rate.
        assertEquals(new DisplayModeDirector.DesiredDisplayModeSpecs(/*baseModeId=*/60,
                             new DisplayModeDirector.RefreshRateRange(0f, Float.POSITIVE_INFINITY)),
                createDisplayModeDirectorWithDisplayFpsRange(60, 90).getDesiredDisplayModeSpecs(
                        displayId));

        int numPriorities =
                DisplayModeDirector.Vote.MAX_PRIORITY - DisplayModeDirector.Vote.MIN_PRIORITY + 1;

        // Ensure vote priority works as expected. As we add new votes with higher priority, they
        // should take precedence over lower priority votes.
        {
            int minFps = 60;
            int maxFps = 90;
            DisplayModeDirector director = createDisplayModeDirectorWithDisplayFpsRange(60, 90);
            assertTrue(2 * numPriorities < maxFps - minFps + 1);
            SparseArray<DisplayModeDirector.Vote> votes =
                    new SparseArray<DisplayModeDirector.Vote>();
            SparseArray<SparseArray<DisplayModeDirector.Vote>> votesByDisplay =
                    new SparseArray<SparseArray<DisplayModeDirector.Vote>>();
            votesByDisplay.put(displayId, votes);
            for (int i = 0; i < numPriorities; i++) {
                int priority = DisplayModeDirector.Vote.MIN_PRIORITY + i;
                votes.put(
                        priority, DisplayModeDirector.Vote.forRefreshRates(minFps + i, maxFps - i));
                director.injectVotesByDisplay(votesByDisplay);
                assertEquals(
                        new DisplayModeDirector.DesiredDisplayModeSpecs(
                                /*baseModeId=*/minFps + i,
                                new DisplayModeDirector.RefreshRateRange(minFps + i, maxFps - i)),
                        director.getDesiredDisplayModeSpecs(displayId));
            }
        }

        // Ensure lower priority votes are able to influence the final decision, even in the
        // presence of higher priority votes.
        {
            assertTrue(numPriorities >= 2);
            DisplayModeDirector director = createDisplayModeDirectorWithDisplayFpsRange(60, 90);
            SparseArray<DisplayModeDirector.Vote> votes =
                    new SparseArray<DisplayModeDirector.Vote>();
            SparseArray<SparseArray<DisplayModeDirector.Vote>> votesByDisplay =
                    new SparseArray<SparseArray<DisplayModeDirector.Vote>>();
            votesByDisplay.put(displayId, votes);
            votes.put(DisplayModeDirector.Vote.MAX_PRIORITY,
                    DisplayModeDirector.Vote.forRefreshRates(65, 85));
            votes.put(DisplayModeDirector.Vote.MIN_PRIORITY,
                    DisplayModeDirector.Vote.forRefreshRates(70, 80));
            director.injectVotesByDisplay(votesByDisplay);
            assertEquals(new DisplayModeDirector.DesiredDisplayModeSpecs(/*baseModeId=*/70,
                                 new DisplayModeDirector.RefreshRateRange(70, 80)),
                    director.getDesiredDisplayModeSpecs(displayId));
        }
    }
}
