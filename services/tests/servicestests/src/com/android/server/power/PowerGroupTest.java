/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.power;


import static android.os.PowerManager.GO_TO_SLEEP_REASON_APPLICATION;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
import static android.os.PowerManager.WAKE_REASON_GESTURE;
import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.power.PowerGroup}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:PowerManagerServiceTest
 */
public class PowerGroupTest {

    private static final int GROUP_ID = 0;
    private static final long TIMESTAMP_CREATE = 1;
    private static final long TIMESTAMP1 = 999;
    private static final long TIMESTAMP2 = TIMESTAMP1 + 10;
    private static final long TIMESTAMP3 = TIMESTAMP2 + 10;
    private static final int UID = 11;

    private PowerGroup mPowerGroup;
    @Mock
    private PowerGroup.PowerGroupListener mWakefulnessCallbackMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mPowerGroup = new PowerGroup(GROUP_ID, mWakefulnessCallbackMock, new DisplayPowerRequest(),
                WAKEFULNESS_AWAKE, /* ready= */ true, /* supportsSandman= */true, TIMESTAMP_CREATE);
    }

    @Test
    public void testDreamPowerGroupTriggersOnWakefulnessChangedCallback() {
        mPowerGroup.dreamLocked(TIMESTAMP1, UID);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_DREAMING), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_APPLICATION),
                eq(UID), /* opUid= */anyInt(), /* opPackageName= */ isNull(), /* details= */
                isNull());
    }

    @Test
    public void testLastWakeAndSleepTimeIsUpdated() {
        assertThat(mPowerGroup.getLastWakeTimeLocked()).isEqualTo(TIMESTAMP_CREATE);
        assertThat(mPowerGroup.getLastSleepTimeLocked()).isEqualTo(TIMESTAMP_CREATE);

        // Verify that the transition to WAKEFULNESS_DOZING updates the last sleep time
        String details = "PowerGroup1 Timeout";
        mPowerGroup.setWakefulnessLocked(WAKEFULNESS_DOZING, TIMESTAMP1, UID,
                GO_TO_SLEEP_REASON_TIMEOUT, /* opUid= */ 0, /* opPackageName= */ null, details);
        assertThat(mPowerGroup.getLastSleepTimeLocked()).isEqualTo(TIMESTAMP1);
        assertThat(mPowerGroup.getLastWakeTimeLocked()).isEqualTo(TIMESTAMP_CREATE);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_DOZING), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_TIMEOUT),
                eq(UID), /* opUid= */anyInt(), /* opPackageName= */ isNull(), eq(details));

        // Verify that the transition to WAKEFULNESS_ASLEEP after dozing does not update the last
        // wake or sleep time
        mPowerGroup.setWakefulnessLocked(WAKEFULNESS_ASLEEP, TIMESTAMP2, UID,
                GO_TO_SLEEP_REASON_DEVICE_ADMIN, /* opUid= */ 0, /* opPackageName= */ null,
                details);
        assertThat(mPowerGroup.getLastSleepTimeLocked()).isEqualTo(TIMESTAMP1);
        assertThat(mPowerGroup.getLastWakeTimeLocked()).isEqualTo(TIMESTAMP_CREATE);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_ASLEEP), eq(TIMESTAMP2), eq(GO_TO_SLEEP_REASON_DEVICE_ADMIN),
                eq(UID), /* opUid= */anyInt(), /* opPackageName= */ isNull(), eq(details));

        // Verify that waking up the power group only updates the last wake time
        details = "PowerGroup1 Gesture";
        mPowerGroup.setWakefulnessLocked(WAKEFULNESS_AWAKE, TIMESTAMP2, UID,
                WAKE_REASON_GESTURE, /* opUid= */ 0, /* opPackageName= */ null, details);
        assertThat(mPowerGroup.getLastWakeTimeLocked()).isEqualTo(TIMESTAMP2);
        assertThat(mPowerGroup.getLastSleepTimeLocked()).isEqualTo(TIMESTAMP1);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_AWAKE), eq(TIMESTAMP2), eq(WAKE_REASON_GESTURE),
                eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ isNull(), eq(details));

        // Verify that a transition to WAKEFULNESS_ASLEEP from an interactive state updates the last
        // sleep time
        mPowerGroup.setWakefulnessLocked(WAKEFULNESS_ASLEEP, TIMESTAMP3, UID,
                GO_TO_SLEEP_REASON_DEVICE_ADMIN, /* opUid= */ 0, /* opPackageName= */ null,
                details);
        assertThat(mPowerGroup.getLastSleepTimeLocked()).isEqualTo(TIMESTAMP3);
        assertThat(mPowerGroup.getLastWakeTimeLocked()).isEqualTo(TIMESTAMP2);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_ASLEEP), eq(TIMESTAMP3), eq(GO_TO_SLEEP_REASON_DEVICE_ADMIN),
                eq(UID), /* opUid= */anyInt(), /* opPackageName= */ isNull(), eq(details));
    }
}
