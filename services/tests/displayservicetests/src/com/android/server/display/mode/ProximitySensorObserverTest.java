/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManagerInternal;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.sensors.SensorManagerInternal;

import junitparams.JUnitParamsRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(JUnitParamsRunner.class)
public class ProximitySensorObserverTest {

    private static final float FLOAT_TOLERANCE = 0.01f;
    private static final int DISPLAY_ID = 1;
    private static final SurfaceControl.RefreshRateRange REFRESH_RATE_RANGE =
            new SurfaceControl.RefreshRateRange(60, 90);

    private final VotesStorage mStorage = new VotesStorage(() -> { }, null);
    private final FakesInjector mInjector = new FakesInjector();
    private ProximitySensorObserver mSensorObserver;

    @Mock
    DisplayManagerInternal mMockDisplayManagerInternal;
    @Mock
    SensorManagerInternal mMockSensorManagerInternal;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockDisplayManagerInternal.getRefreshRateForDisplayAndSensor(eq(DISPLAY_ID),
                any(), any())).thenReturn(REFRESH_RATE_RANGE);
        mSensorObserver = new ProximitySensorObserver(mStorage, mInjector);
        mSensorObserver.observe();
    }

    @Test
    public void testAddsProximityVoteIfSensorManagerProximityActive() {
        mSensorObserver.onProximityActive(true);

        SparseArray<Vote> displayVotes = mStorage.getVotes(DISPLAY_ID);
        assertThat(displayVotes.size()).isEqualTo(1);
        Vote vote = displayVotes.get(Vote.PRIORITY_PROXIMITY);
        assertThat(vote).isNotNull();
        assertThat(vote).isInstanceOf(CombinedVote.class);
        CombinedVote combinedVote = (CombinedVote) vote;
        RefreshRateVote.PhysicalVote physicalVote =
                (RefreshRateVote.PhysicalVote) combinedVote.mVotes.get(0);
        assertThat(physicalVote.mMinRefreshRate).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(physicalVote.mMaxRefreshRate).isWithin(FLOAT_TOLERANCE).of(90);
    }

    @Test
    public void testDoesNotAddProximityVoteIfSensorManagerProximityNotActive() {
        mSensorObserver.onProximityActive(false);

        SparseArray<Vote> displayVotes = mStorage.getVotes(DISPLAY_ID);
        assertThat(displayVotes.size()).isEqualTo(0);
    }

    @Test
    public void testDoesNotAddProximityVoteIfDoze() {
        mInjector.mDozeState = true;
        mSensorObserver.onDisplayChanged(DISPLAY_ID);
        mSensorObserver.onProximityActive(true);

        SparseArray<Vote> displayVotes = mStorage.getVotes(DISPLAY_ID);
        assertThat(displayVotes.size()).isEqualTo(0);
    }

    private class FakesInjector extends DisplayModeDirectorTest.FakesInjector {

        private boolean mDozeState = false;

        @Override
        public Display[] getDisplays() {
            return new Display[] { createDisplay(DISPLAY_ID) };
        }

        @Override
        public DisplayManagerInternal getDisplayManagerInternal() {
            return mMockDisplayManagerInternal;
        }

        @Override
        public SensorManagerInternal getSensorManagerInternal() {
            return mMockSensorManagerInternal;
        }

        @Override
        public boolean isDozeState(Display d) {
            return mDozeState;
        }
    }
}
