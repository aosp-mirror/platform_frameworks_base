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

package com.android.server.soundtrigger;

import static android.os.PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED;
import static android.os.PowerManager.SOUND_TRIGGER_MODE_ALL_ENABLED;
import static android.os.PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY;

import static com.android.server.soundtrigger.DeviceStateHandler.SoundTriggerDeviceState;
import static com.android.server.soundtrigger.DeviceStateHandler.SoundTriggerDeviceState.*;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemClock;

import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.annotations.GuardedBy;
import com.android.server.utils.EventLogger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class DeviceStateHandlerTest {
    private final long CONFIRM_NO_EVENT_WAIT_MS = 1000;
    // A wait substantially less than the duration we delay phone notifications by
    private final long PHONE_DELAY_BRIEF_WAIT_MS =
            DeviceStateHandler.CALL_INACTIVE_MSG_DELAY_MS / 4;

    private DeviceStateHandler mHandler;
    private DeviceStateHandler.DeviceStateListener mDeviceStateCallback;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private SoundTriggerDeviceState mState;

    @GuardedBy("mLock")
    private CountDownLatch mLatch;

    private EventLogger mEventLogger;

    @Before
    public void setup() {
        // Reset the state prior to each test
        mEventLogger = new EventLogger(256, "test logger");
        synchronized (mLock) {
            mLatch = new CountDownLatch(1);
        }
        mDeviceStateCallback =
                (SoundTriggerDeviceState state) -> {
                    synchronized (mLock) {
                        mState = state;
                        mLatch.countDown();
                    }
                };
        mHandler = new DeviceStateHandler(Runnable::run, mEventLogger);
        mHandler.onPhoneCallStateChanged(false);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_ENABLED);
        mHandler.registerListener(mDeviceStateCallback);
        try {
            waitAndAssertState(ENABLE);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void waitAndAssertState(SoundTriggerDeviceState state) throws InterruptedException {
        CountDownLatch latch;
        synchronized (mLock) {
            latch = mLatch;
        }
        latch.await();
        synchronized (mLock) {
            assertThat(mState).isEqualTo(state);
            mLatch = new CountDownLatch(1);
        }
    }

    private void waitToConfirmNoEventReceived() throws InterruptedException {
        CountDownLatch latch;
        synchronized (mLock) {
            latch = mLatch;
        }
        // Check that we time out
        assertThat(latch.await(CONFIRM_NO_EVENT_WAIT_MS, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    public void onPowerModeChangedCritical_receiveStateChange() throws Exception {
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitAndAssertState(CRITICAL);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_ENABLED);
        waitAndAssertState(ENABLE);
    }

    @Test
    public void onPowerModeChangedDisabled_receiveStateChange() throws Exception {
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_DISABLED);
        waitAndAssertState(DISABLE);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_ENABLED);
        waitAndAssertState(ENABLE);
    }

    @Test
    public void onPowerModeChangedMultiple_receiveStateChange() throws Exception {
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_DISABLED);
        waitAndAssertState(DISABLE);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitAndAssertState(CRITICAL);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_DISABLED);
        waitAndAssertState(DISABLE);
    }

    @Test
    public void onPowerModeSameState_noStateChange() throws Exception {
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_DISABLED);
        waitAndAssertState(DISABLE);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_DISABLED);
        waitToConfirmNoEventReceived();
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitAndAssertState(CRITICAL);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitToConfirmNoEventReceived();
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_ENABLED);
        waitAndAssertState(ENABLE);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_ENABLED);
        waitToConfirmNoEventReceived();
    }

    @Test
    public void onPhoneCall_receiveStateChange() throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        mHandler.onPhoneCallStateChanged(false);
        waitAndAssertState(ENABLE);
    }

    @Test
    public void onPhoneCall_receiveStateChangeIsDelayed() throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        long beforeTime = SystemClock.uptimeMillis();
        mHandler.onPhoneCallStateChanged(false);
        waitAndAssertState(ENABLE);
        long afterTime = SystemClock.uptimeMillis();
        assertThat(afterTime - beforeTime).isAtLeast(DeviceStateHandler.CALL_INACTIVE_MSG_DELAY_MS);
    }

    @Test
    public void onPhoneCallEnterExitEnter_receiveNoStateChange() throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        mHandler.onPhoneCallStateChanged(false);
        SystemClock.sleep(PHONE_DELAY_BRIEF_WAIT_MS);
        mHandler.onPhoneCallStateChanged(true);
        waitToConfirmNoEventReceived();
    }

    @Test
    public void onBatteryCallbackDuringPhoneWait_receiveStateChangeDelayed() throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        mHandler.onPhoneCallStateChanged(false);
        SystemClock.sleep(PHONE_DELAY_BRIEF_WAIT_MS);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitAndAssertState(CRITICAL);
        // Ensure we don't get an ENABLE event after
        waitToConfirmNoEventReceived();
    }

    @Test
    public void onBatteryChangeWhenInPhoneCall_receiveNoStateChange() throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_ENABLED);
        waitToConfirmNoEventReceived();
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitToConfirmNoEventReceived();
    }

    @Test
    public void whenBatteryCriticalChangeDuringCallAfterPhoneCall_receiveCriticalStateChange()
            throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitToConfirmNoEventReceived();
        mHandler.onPhoneCallStateChanged(false);
        waitAndAssertState(CRITICAL);
    }

    @Test
    public void whenBatteryDisableDuringCallAfterPhoneCallBatteryEnable_receiveStateChange()
            throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_DISABLED);
        waitToConfirmNoEventReceived();
        mHandler.onPhoneCallStateChanged(false);
        waitToConfirmNoEventReceived();
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitAndAssertState(CRITICAL);
    }

    @Test
    public void whenPhoneCallDuringBatteryDisable_receiveNoStateChange() throws Exception {
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_ALL_DISABLED);
        waitAndAssertState(DISABLE);
        mHandler.onPhoneCallStateChanged(true);
        waitToConfirmNoEventReceived();
        mHandler.onPhoneCallStateChanged(false);
        waitToConfirmNoEventReceived();
    }

    @Test
    public void whenPhoneCallDuringBatteryCritical_receiveStateChange() throws Exception {
        mHandler.onPowerModeChanged(SOUND_TRIGGER_MODE_CRITICAL_ONLY);
        waitAndAssertState(CRITICAL);
        mHandler.onPhoneCallStateChanged(true);
        waitAndAssertState(DISABLE);
        mHandler.onPhoneCallStateChanged(false);
        waitAndAssertState(CRITICAL);
    }

    // This test could be flaky, but we want to verify that we only delay notification if
    // we are exiting a call, NOT if we are entering a call.
    @FlakyTest
    @Test
    public void whenPhoneCallReceived_receiveStateChangeFast() throws Exception {
        mHandler.onPhoneCallStateChanged(true);
        CountDownLatch latch;
        synchronized (mLock) {
            latch = mLatch;
        }
        assertThat(latch.await(PHONE_DELAY_BRIEF_WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        synchronized (mLock) {
            assertThat(mState).isEqualTo(DISABLE);
        }
    }
}
