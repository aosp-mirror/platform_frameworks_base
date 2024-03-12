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


import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.Temperature;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


/**
 * Tests for DisplayModeDirector.SkinThermalStatusObserver. Comply with changes described in
 * b/266789924
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SkinThermalStatusObserverTest {
    private static final float FLOAT_TOLERANCE = 0.01f;
    private static final int DISPLAY_ID = 1;
    private static final int DISPLAY_ID_OTHER = 2;
    private static final int DISPLAY_ID_ADDED = 3;

    SkinThermalStatusObserver mObserver;

    private RegisteringFakesInjector mInjector = new RegisteringFakesInjector();

    private final TestHandler mHandler = new TestHandler(null);
    private final VotesStorage mStorage = new VotesStorage(() -> {});

    @Before
    public void setUp() {
        mObserver = new SkinThermalStatusObserver(mInjector, mStorage, mHandler);
    }

    @Test
    public void testRegisterListenersOnObserve() {
        // GIVEN thermal sensor is available
        assertNull(mInjector.mThermalEventListener);
        assertNull(mInjector.mDisplayListener);
        // WHEN observe is called
        mObserver.observe();
        // THEN thermal and display listeners are registered
        assertEquals(mObserver, mInjector.mThermalEventListener);
        assertEquals(mObserver, mInjector.mDisplayListener);
    }

    @Test
    public void testFailToRegisterThermalListenerOnObserve() {
        // GIVEN thermal sensor is not available
        mInjector = new RegisteringFakesInjector(false);
        mObserver = new SkinThermalStatusObserver(mInjector, mStorage, mHandler);
        // WHEN observe is called
        mObserver.observe();
        // THEN nothing is registered
        assertNull(mInjector.mThermalEventListener);
        assertNull(mInjector.mDisplayListener);
    }

    @Test
    public void testNotifyWithDefaultVotesForCritical() {
        // GIVEN 2 displays with no thermalThrottling config
        mObserver.observe();
        assertEquals(0, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());

        // WHEN thermal sensor notifies CRITICAL
        mObserver.notifyThrottling(createTemperature(Temperature.THROTTLING_CRITICAL));
        mHandler.flush();

        // THEN 2 votes are added to storage with (0,60) render refresh rate(default behaviour)
        SparseArray<Vote> displayVotes = mStorage.getVotes(DISPLAY_ID);
        assertEquals(1, displayVotes.size());

        Vote vote = displayVotes.get(Vote.PRIORITY_SKIN_TEMPERATURE);

        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        RefreshRateVote.RenderVote renderVote = (RefreshRateVote.RenderVote) vote;
        assertEquals(0, renderVote.mMinRefreshRate, FLOAT_TOLERANCE);
        assertEquals(60, renderVote.mMaxRefreshRate, FLOAT_TOLERANCE);

        SparseArray<Vote> otherDisplayVotes = mStorage.getVotes(DISPLAY_ID_OTHER);
        assertEquals(1, otherDisplayVotes.size());

        vote = otherDisplayVotes.get(Vote.PRIORITY_SKIN_TEMPERATURE);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        renderVote = (RefreshRateVote.RenderVote) vote;
        assertEquals(0, renderVote.mMinRefreshRate, FLOAT_TOLERANCE);
        assertEquals(60, renderVote.mMaxRefreshRate, FLOAT_TOLERANCE);
    }

    @Test
    public void testNotifyWithDefaultVotesChangeFromCriticalToSevere() {
        // GIVEN 2 displays with no thermalThrottling config AND temperature level CRITICAL
        mObserver.observe();
        assertEquals(0, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());
        mObserver.notifyThrottling(createTemperature(Temperature.THROTTLING_CRITICAL));
        // WHEN thermal sensor notifies SEVERE
        mObserver.notifyThrottling(createTemperature(Temperature.THROTTLING_SEVERE));
        mHandler.flush();
        // THEN all votes with PRIORITY_SKIN_TEMPERATURE are removed from the storage
        assertEquals(0, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());
    }

    @Test
    public void testNotifyWithDefaultVotesForSevere() {
        // GIVEN 2 displays with no thermalThrottling config
        mObserver.observe();
        assertEquals(0, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());
        // WHEN thermal sensor notifies CRITICAL
        mObserver.notifyThrottling(createTemperature(Temperature.THROTTLING_SEVERE));
        mHandler.flush();
        // THEN nothing is added to the storage
        assertEquals(0, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());
    }

    @Test
    public void testNotifiesWithConfigVotes() {
        // GIVEN 2 displays AND one has thermalThrottling config defined
        SparseArray<SurfaceControl.RefreshRateRange> displayConfig = new SparseArray<>();
        displayConfig.put(Temperature.THROTTLING_MODERATE,
                new SurfaceControl.RefreshRateRange(90.0f, 120.0f));
        SparseArray<SparseArray<SurfaceControl.RefreshRateRange>> config = new SparseArray<>();
        config.put(DISPLAY_ID, displayConfig);
        mInjector = new RegisteringFakesInjector(true, config);
        mObserver = new SkinThermalStatusObserver(mInjector, mStorage, mHandler);
        mObserver.observe();
        mObserver.onDisplayChanged(DISPLAY_ID);
        assertEquals(0, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());
        // WHEN thermal sensor notifies temperature above configured
        mObserver.notifyThrottling(createTemperature(Temperature.THROTTLING_SEVERE));
        mHandler.flush();
        // THEN vote with refreshRate from config is added to the storage
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());

        SparseArray<Vote> displayVotes = mStorage.getVotes(DISPLAY_ID);
        assertEquals(1, displayVotes.size());
        Vote vote = displayVotes.get(Vote.PRIORITY_SKIN_TEMPERATURE);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        RefreshRateVote.RenderVote renderVote = (RefreshRateVote.RenderVote) vote;
        assertEquals(90, renderVote.mMinRefreshRate, FLOAT_TOLERANCE);
        assertEquals(120, renderVote.mMaxRefreshRate, FLOAT_TOLERANCE);
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_OTHER).size());
    }

    @Test
    public void testDisplayAdded() {
        // GIVEN 2 displays with no thermalThrottling config AND temperature level CRITICAL
        mObserver.observe();
        mObserver.notifyThrottling(createTemperature(Temperature.THROTTLING_CRITICAL));
        // WHEN new display is added
        mObserver.onDisplayAdded(DISPLAY_ID_ADDED);
        mHandler.flush();
        // THEN 3rd vote is added to storage with (0,60) render refresh rate(default behaviour)
        assertEquals(1, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(1, mStorage.getVotes(DISPLAY_ID_OTHER).size());
        assertEquals(1, mStorage.getVotes(DISPLAY_ID_ADDED).size());

        SparseArray<Vote> displayVotes = mStorage.getVotes(DISPLAY_ID_ADDED);

        Vote vote = displayVotes.get(Vote.PRIORITY_SKIN_TEMPERATURE);
        assertThat(vote).isInstanceOf(RefreshRateVote.RenderVote.class);
        RefreshRateVote.RenderVote renderVote = (RefreshRateVote.RenderVote) vote;
        assertEquals(0, renderVote.mMinRefreshRate, FLOAT_TOLERANCE);
        assertEquals(60, renderVote.mMaxRefreshRate, FLOAT_TOLERANCE);
    }

    @Test
    public void testDisplayAddedAndThenImmediatelyRemoved() {
        // GIVEN 2 displays with no thermalThrottling config AND temperature level CRITICAL
        mObserver.observe();
        mObserver.notifyThrottling(createTemperature(Temperature.THROTTLING_CRITICAL));
        // WHEN new display is added and immediately removed
        mObserver.onDisplayAdded(DISPLAY_ID_ADDED);
        mObserver.onDisplayRemoved(DISPLAY_ID_ADDED);
        mHandler.flush();
        // THEN there are 2 votes in registry
        assertEquals(1, mStorage.getVotes(DISPLAY_ID).size());
        assertEquals(1, mStorage.getVotes(DISPLAY_ID_OTHER).size());
        assertEquals(0, mStorage.getVotes(DISPLAY_ID_ADDED).size());
    }

    private static Temperature createTemperature(@Temperature.ThrottlingStatus int status) {
        return new Temperature(40.0f, Temperature.TYPE_SKIN, "test_temp", status);
    }


    private static class RegisteringFakesInjector extends DisplayModeDirectorTest.FakesInjector {
        private IThermalEventListener mThermalEventListener;
        private DisplayManager.DisplayListener mDisplayListener;

        private final boolean mRegisterThermalListener;
        private final SparseArray<SparseArray<SurfaceControl.RefreshRateRange>> mOverriddenConfig;


        private RegisteringFakesInjector() {
            this(true);
        }

        private RegisteringFakesInjector(boolean registerThermalListener) {
            this(registerThermalListener, new SparseArray<>());
        }

        private RegisteringFakesInjector(boolean registerThermalListener,
                SparseArray<SparseArray<SurfaceControl.RefreshRateRange>> overriddenConfig) {
            mRegisterThermalListener = registerThermalListener;
            mOverriddenConfig = overriddenConfig;
        }

        @Override
        public boolean registerThermalServiceListener(IThermalEventListener listener) {
            mThermalEventListener = (mRegisterThermalListener ? listener : null);
            return mRegisterThermalListener;
        }

        @Override
        public void registerDisplayListener(DisplayManager.DisplayListener listener,
                Handler handler, long flag) {
            mDisplayListener = listener;
        }

        @Override
        public Display[] getDisplays() {
            return new Display[] {createDisplay(DISPLAY_ID), createDisplay(DISPLAY_ID_OTHER)};
        }

        @Override
        public boolean getDisplayInfo(int displayId, DisplayInfo displayInfo) {
            SparseArray<SurfaceControl.RefreshRateRange> config = mOverriddenConfig.get(displayId);
            if (config != null) {
                displayInfo.thermalRefreshRateThrottling = config;
                return true;
            }
            return false;
        }
    }
}
