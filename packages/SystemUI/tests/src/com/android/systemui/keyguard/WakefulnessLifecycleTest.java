/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.IWallpaperManager;
import android.os.PowerManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WakefulnessLifecycleTest extends SysuiTestCase {

    private WakefulnessLifecycle mWakefulness;
    private WakefulnessLifecycle.Observer mWakefulnessObserver;

    private IWallpaperManager mWallpaperManager;

    @Before
    public void setUp() throws Exception {
        mWallpaperManager = mock(IWallpaperManager.class);
        mWakefulness =
                new WakefulnessLifecycle(
                        mContext,
                        mWallpaperManager,
                        new FakeSystemClock(),
                        mock(DumpManager.class)
                );
        mWakefulnessObserver = mock(WakefulnessLifecycle.Observer.class);
        mWakefulness.addObserver(mWakefulnessObserver);
    }

    @Test
    public void baseState() throws Exception {
        assertEquals(WakefulnessLifecycle.WAKEFULNESS_AWAKE, mWakefulness.getWakefulness());

        verifyNoMoreInteractions(mWakefulnessObserver);
    }

    @Test
    public void dispatchStartedWakingUp() throws Exception {
        mWakefulness.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);

        assertEquals(WakefulnessLifecycle.WAKEFULNESS_WAKING, mWakefulness.getWakefulness());

        verify(mWakefulnessObserver).onStartedWakingUp();
    }

    @Test
    public void dispatchFinishedWakingUp() throws Exception {
        mWakefulness.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        mWakefulness.dispatchFinishedWakingUp();

        assertEquals(WakefulnessLifecycle.WAKEFULNESS_AWAKE, mWakefulness.getWakefulness());

        verify(mWakefulnessObserver).onFinishedWakingUp();
        verify(mWakefulnessObserver).onPostFinishedWakingUp();
    }

    @Test
    public void dispatchStartedGoingToSleep() throws Exception {
        mWakefulness.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        mWakefulness.dispatchFinishedWakingUp();
        mWakefulness.dispatchStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN);

        assertEquals(WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP,
                mWakefulness.getWakefulness());

        verify(mWakefulnessObserver).onStartedGoingToSleep();
    }

    @Test
    public void dispatchFinishedGoingToSleep() throws Exception {
        mWakefulness.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        mWakefulness.dispatchFinishedWakingUp();
        mWakefulness.dispatchStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN);
        mWakefulness.dispatchFinishedGoingToSleep();

        assertEquals(WakefulnessLifecycle.WAKEFULNESS_ASLEEP,
                mWakefulness.getWakefulness());

        verify(mWakefulnessObserver).onFinishedGoingToSleep();
    }

    @Test
    public void doesNotDispatchTwice() throws Exception {
        mWakefulness.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        mWakefulness.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        mWakefulness.dispatchFinishedWakingUp();
        mWakefulness.dispatchFinishedWakingUp();
        mWakefulness.dispatchStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN);
        mWakefulness.dispatchStartedGoingToSleep(PowerManager.GO_TO_SLEEP_REASON_MIN);
        mWakefulness.dispatchFinishedGoingToSleep();
        mWakefulness.dispatchFinishedGoingToSleep();

        verify(mWakefulnessObserver, times(1)).onStartedGoingToSleep();
        verify(mWakefulnessObserver, times(1)).onFinishedGoingToSleep();
        verify(mWakefulnessObserver, times(1)).onStartedWakingUp();
        verify(mWakefulnessObserver, times(1)).onFinishedWakingUp();
    }

    @Test
    public void dump() throws Exception {
        mWakefulness.dump(new PrintWriter(new ByteArrayOutputStream()), new String[0]);
    }

    @Test(expected = NullPointerException.class)
    public void throwNPEOnNullObserver() {
        mWakefulness.addObserver(null);
    }
}
