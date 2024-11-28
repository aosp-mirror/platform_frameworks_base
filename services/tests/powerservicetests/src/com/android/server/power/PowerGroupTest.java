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


import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_APPLICATION;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
import static android.os.PowerManager.WAKE_REASON_GESTURE;
import static android.os.PowerManager.WAKE_REASON_PLUGGED_IN;
import static android.os.PowerManager.WAKE_REASON_WAKE_MOTION;
import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;
import static android.os.PowerManagerInternal.WAKEFULNESS_DOZING;
import static android.os.PowerManagerInternal.WAKEFULNESS_DREAMING;
import static android.view.Display.STATE_REASON_DEFAULT_POLICY;
import static android.view.Display.STATE_REASON_MOTION;

import static com.android.server.power.PowerManagerService.USER_ACTIVITY_SCREEN_BRIGHT;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_DOZE;
import static com.android.server.power.PowerManagerService.WAKE_LOCK_SCREEN_BRIGHT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.Display;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.LatencyTracker;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.power.feature.PowerManagerFlags;

import org.junit.Before;
import org.junit.Rule;
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
    private static final int NON_DEFAULT_GROUP_ID = 1;
    private static final int NON_DEFAULT_DISPLAY_ID = 2;
    private static final int VIRTUAL_DEVICE_ID = 3;
    private static final int UID = 11;
    private static final long TIMESTAMP_CREATE = 1;
    private static final long TIMESTAMP1 = 999;
    private static final long TIMESTAMP2 = TIMESTAMP1 + 10;
    private static final long TIMESTAMP3 = TIMESTAMP2 + 10;

    private static final float PRECISION = 0.001f;

    private static final float BRIGHTNESS = 0.99f;
    private static final float BRIGHTNESS_DOZE = 0.5f;

    private static final LatencyTracker LATENCY_TRACKER = LatencyTracker.getInstance(
            InstrumentationRegistry.getInstrumentation().getContext());

    private static final long DEFAULT_TIMEOUT = 1234L;

    @Rule
    public SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private PowerGroup mPowerGroup;
    @Mock private PowerGroup.PowerGroupListener mWakefulnessCallbackMock;
    @Mock private Notifier mNotifier;
    @Mock private DisplayManagerInternal mDisplayManagerInternal;
    @Mock private VirtualDeviceManagerInternal mVirtualDeviceManagerInternal;
    @Mock private PowerManagerFlags mFeatureFlags;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mFeatureFlags.isPolicyReasonInDisplayPowerRequestEnabled()).thenReturn(true);
        mPowerGroup = new PowerGroup(GROUP_ID, mWakefulnessCallbackMock, mNotifier,
                mDisplayManagerInternal, WAKEFULNESS_AWAKE, /* ready= */ true,
                /* supportsSandman= */ true, TIMESTAMP_CREATE, mFeatureFlags);
    }

    @Test
    public void testWakePowerGroup() {
        mPowerGroup.sleepLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_APPLICATION);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_ASLEEP), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_APPLICATION),
                eq(UID), /* opUid= */anyInt(), /* opPackageName= */ isNull(), /* details= */
                isNull());
        String details = "wake PowerGroup1";
        mPowerGroup.wakeUpLocked(TIMESTAMP2, WAKE_REASON_PLUGGED_IN, details, UID,
                /* opPackageName= */ null, /* opUid= */ 0, LATENCY_TRACKER);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_AWAKE), eq(TIMESTAMP2), eq(WAKE_REASON_PLUGGED_IN), eq(UID),
                /* opUid= */ anyInt(), /* opPackageName= */ isNull(), eq(details));
    }

    @Test
    public void testDreamPowerGroup() {
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mPowerGroup.dreamLocked(TIMESTAMP1, UID, /* allowWake= */ false);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        assertThat(mPowerGroup.isSandmanSummonedLocked()).isTrue();
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_DREAMING), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_APPLICATION),
                eq(UID), /* opUid= */anyInt(), /* opPackageName= */ isNull(), /* details= */
                isNull());
    }

    @Test
    public void testDozePowerGroup() {
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_TIMEOUT);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        assertThat(mPowerGroup.isSandmanSummonedLocked()).isTrue();
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_DOZING), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_TIMEOUT),
                eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ isNull(),
                /* details= */ isNull());
    }

    @Test
    public void testDozePowerGroupWhenNonInteractiveHasNoEffect() {
        mPowerGroup.sleepLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_TIMEOUT);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_ASLEEP), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_TIMEOUT),
                eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ isNull(),
                /* details= */ isNull());
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        assertThat(mPowerGroup.dozeLocked(TIMESTAMP2, UID, GO_TO_SLEEP_REASON_TIMEOUT)).isFalse();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        verify(mWakefulnessCallbackMock, never()).onWakefulnessChangedLocked(
                eq(GROUP_ID), eq(WAKEFULNESS_DOZING), eq(TIMESTAMP2), /* reason= */ anyInt(),
                eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ any(), /* details= */ any());
    }

    @Test
    public void testSleepPowerGroup() {
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mPowerGroup.sleepLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_DEVICE_FOLD);
        assertThat(mPowerGroup.isSandmanSummonedLocked()).isTrue();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_ASLEEP), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_DEVICE_FOLD),
                eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ isNull(),
                /* details= */ isNull());
    }

    @Test
    public void testDreamPowerGroupWhenNotAwakeHasNoEffect() {
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_TIMEOUT);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_DOZING), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_TIMEOUT),
                eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ isNull(),
                /* details= */ isNull());
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        assertThat(mPowerGroup.dreamLocked(TIMESTAMP2, UID, /* allowWake= */ false)).isFalse();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        verify(mWakefulnessCallbackMock, never()).onWakefulnessChangedLocked(
                eq(GROUP_ID), /* wakefulness= */ eq(WAKEFULNESS_DREAMING), eq(TIMESTAMP2),
                /* reason= */ anyInt(), eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ any(),
                /* details= */ any());
    }

    @Test
    public void testDreamPowerGroupWhenNotAwakeShouldWake() {
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_TIMEOUT);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(eq(GROUP_ID),
                eq(WAKEFULNESS_DOZING), eq(TIMESTAMP1), eq(GO_TO_SLEEP_REASON_TIMEOUT),
                eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ isNull(),
                /* details= */ isNull());
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        assertThat(mPowerGroup.dreamLocked(TIMESTAMP2, UID, /* allowWake= */ true)).isTrue();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        verify(mWakefulnessCallbackMock).onWakefulnessChangedLocked(
                eq(GROUP_ID), /* wakefulness= */ eq(WAKEFULNESS_DREAMING), eq(TIMESTAMP2),
                /* reason= */ anyInt(), eq(UID), /* opUid= */ anyInt(), /* opPackageName= */ any(),
                /* details= */ any());
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

    @Test
    public void testUpdateWhileAwake_UpdatesDisplayPowerRequest() {
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_APPLICATION);
        mPowerGroup.wakeUpLocked(TIMESTAMP2, WAKE_REASON_WAKE_MOTION, "details", UID,
                /* opPackageName= */ null, /* opUid= */ 0, LATENCY_TRACKER);

        final boolean batterySaverEnabled = true;
        float brightnessFactor = 0.7f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();

        CharSequence tag = "my/tag";
        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ tag,
                /* useProximitySensor= */ false,
                /* boostScreenBrightness= */ false,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_DIM);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_MOTION);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.screenBrightnessOverrideTag.toString()).isEqualTo(tag);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(false);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(false);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testWakefulnessReasonInDisplayPowerRequestDisabled_wakefulnessReasonNotPopulated() {
        when(mFeatureFlags.isPolicyReasonInDisplayPowerRequestEnabled()).thenReturn(false);
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_APPLICATION);
        mPowerGroup.wakeUpLocked(TIMESTAMP2, WAKE_REASON_WAKE_MOTION, "details", UID,
                /* opPackageName= */ null, /* opUid= */ 0, LATENCY_TRACKER);

        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ "my/tag",
                /* useProximitySensor= */ false,
                /* boostScreenBrightness= */ false,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                new PowerSaveState.Builder().build(),
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);

        mPowerGroup.wakeUpLocked(TIMESTAMP2, WAKE_REASON_PLUGGED_IN, "details", UID,
                /* opPackageName= */ null, /* opUid= */ 0, LATENCY_TRACKER);
        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ "my/tag",
                /* useProximitySensor= */ false,
                /* boostScreenBrightness= */ false,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                new PowerSaveState.Builder().build(),
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        displayPowerRequest = mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
    }

    @Test
    public void testUpdateWhileDozing_UpdatesDisplayPowerRequest() {
        final boolean useNormalBrightnessForDoze = false;
        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_APPLICATION);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        mPowerGroup.setWakeLockSummaryLocked(WAKE_LOCK_DOZE);

        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                useNormalBrightnessForDoze,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_DOZE);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_ON);
        assertThat(displayPowerRequest.dozeScreenBrightness).isWithin(PRECISION).of(
                BRIGHTNESS_DOZE);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateWhileDozing_useNormalBrightness() {
        final boolean batterySaverEnabled = false;
        final boolean useNormalBrightnessForDoze = true;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_APPLICATION);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);
        mPowerGroup.setWakeLockSummaryLocked(WAKE_LOCK_DOZE);

        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                useNormalBrightnessForDoze,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_DOZE);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_ON);
        assertThat(displayPowerRequest.dozeScreenBrightness).isWithin(PRECISION).of(
                BRIGHTNESS_DOZE);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isTrue();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateWhileDozing_DozeAfterScreenOff() {
        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_APPLICATION);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DOZING);

        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ true,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_OFF);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateQuiescent() {
        mPowerGroup.dozeLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_APPLICATION);
        mPowerGroup.wakeUpLocked(TIMESTAMP2, WAKE_REASON_WAKE_MOTION, "details", UID,
                /* opPackageName= */ null, /* opUid= */ 0, LATENCY_TRACKER);

        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);

        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ true,
                /* dozeAfterScreenOff= */ true,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_OFF);
        // Note how the reason is STATE_REASON_DEFAULT_POLICY, instead of STATE_REASON_MOTION.
        // This is because - although there was a wake up request from a motion, the quiescent state
        // preceded and forced the policy to be OFF, so we ignore the reason associated with the
        // wake up request.
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateWhileAsleep_UpdatesDisplayPowerRequest() {
        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        mPowerGroup.sleepLocked(TIMESTAMP1, UID, GO_TO_SLEEP_REASON_TIMEOUT);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_ASLEEP);
        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_OFF);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateWhileDreamingWithScreenBrightWakelock_UpdatesDisplayPowerRequest() {
        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        mPowerGroup.dreamLocked(TIMESTAMP1, UID, /* allowWake= */ false);
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_DREAMING);
        mPowerGroup.setWakeLockSummaryLocked(WAKE_LOCK_SCREEN_BRIGHT);
        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_BRIGHT);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateWhileAwakeBootNotComplete_UpdatesDisplayPowerRequest() {
        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ false,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_BRIGHT);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateWhileAwakeUserActivityScreenBright_UpdatesDisplayPowerRequest() {
        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mPowerGroup.setUserActivitySummaryLocked(USER_ACTIVITY_SCREEN_BRIGHT);
        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ false,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_BRIGHT);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testUpdateWhileAwakeScreenBrightnessBoostInProgress_UpdatesDisplayPowerRequest() {
        final boolean batterySaverEnabled = false;
        float brightnessFactor = 0.3f;
        PowerSaveState powerSaveState = new PowerSaveState.Builder()
                .setBatterySaverEnabled(batterySaverEnabled)
                .setBrightnessFactor(brightnessFactor)
                .build();
        assertThat(mPowerGroup.getWakefulnessLocked()).isEqualTo(WAKEFULNESS_AWAKE);
        mPowerGroup.updateLocked(/* screenBrightnessOverride= */ BRIGHTNESS,
                /* overrideTag= */ null,
                /* useProximitySensor= */ true,
                /* boostScreenBrightness= */ true,
                /* dozeScreenStateOverride= */ Display.STATE_ON,
                /* dozeScreenStateReason= */ Display.STATE_REASON_DEFAULT_POLICY,
                /* dozeScreenBrightness= */ BRIGHTNESS_DOZE,
                /* useNormalBrightnessForDoze= */ false,
                /* overrideDrawWakeLock= */ false,
                powerSaveState,
                /* quiescent= */ false,
                /* dozeAfterScreenOff= */ false,
                /* bootCompleted= */ true,
                /* screenBrightnessBoostInProgress= */ true,
                /* waitForNegativeProximity= */ false,
                /* brightWhenDozing= */ false);
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest =
                mPowerGroup.mDisplayPowerRequest;
        assertThat(displayPowerRequest.policy).isEqualTo(POLICY_BRIGHT);
        assertThat(displayPowerRequest.policyReason).isEqualTo(STATE_REASON_DEFAULT_POLICY);
        assertThat(displayPowerRequest.screenBrightnessOverride).isWithin(PRECISION).of(BRIGHTNESS);
        assertThat(displayPowerRequest.useProximitySensor).isEqualTo(true);
        assertThat(displayPowerRequest.boostScreenBrightness).isEqualTo(true);
        assertThat(displayPowerRequest.dozeScreenState).isEqualTo(Display.STATE_UNKNOWN);
        assertThat(displayPowerRequest.dozeScreenBrightness).isEqualTo(
                PowerManager.BRIGHTNESS_INVALID_FLOAT);
        assertThat(displayPowerRequest.useNormalBrightnessForDoze).isFalse();
        assertThat(displayPowerRequest.lowPowerMode).isEqualTo(batterySaverEnabled);
        assertThat(displayPowerRequest.screenLowPowerBrightnessFactor).isWithin(PRECISION).of(
                brightnessFactor);
    }

    @Test
    public void testTimeoutsOverride_defaultGroup_noOverride() {
        assertThat(mPowerGroup.getScreenDimDurationOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(DEFAULT_TIMEOUT);
        assertThat(mPowerGroup.getScreenOffTimeoutOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(DEFAULT_TIMEOUT);
    }

    @EnableFlags(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
    @Test
    public void testTimeoutsOverride_noVdm_noOverride() {
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(VirtualDeviceManagerInternal.class, null);

        mPowerGroup = new PowerGroup(NON_DEFAULT_GROUP_ID, mWakefulnessCallbackMock, mNotifier,
                mDisplayManagerInternal, WAKEFULNESS_AWAKE, /* ready= */ true,
                /* supportsSandman= */ true, TIMESTAMP_CREATE, mFeatureFlags);

        assertThat(mPowerGroup.getScreenDimDurationOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(DEFAULT_TIMEOUT);
        assertThat(mPowerGroup.getScreenOffTimeoutOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(DEFAULT_TIMEOUT);
    }

    @EnableFlags(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
    @Test
    public void testTimeoutsOverride_notValidVirtualDeviceId_noOverride() {
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(VirtualDeviceManagerInternal.class, mVirtualDeviceManagerInternal);

        when(mDisplayManagerInternal.getDisplayIdsForGroup(NON_DEFAULT_GROUP_ID))
                .thenReturn(new int[] {NON_DEFAULT_DISPLAY_ID});
        when(mVirtualDeviceManagerInternal.getDeviceIdForDisplayId(NON_DEFAULT_DISPLAY_ID))
                .thenReturn(Context.DEVICE_ID_DEFAULT);
        when(mVirtualDeviceManagerInternal.isValidVirtualDeviceId(Context.DEVICE_ID_DEFAULT))
                .thenReturn(false);

        mPowerGroup = new PowerGroup(NON_DEFAULT_GROUP_ID, mWakefulnessCallbackMock, mNotifier,
                mDisplayManagerInternal, WAKEFULNESS_AWAKE, /* ready= */ true,
                /* supportsSandman= */ true, TIMESTAMP_CREATE, mFeatureFlags);

        assertThat(mPowerGroup.getScreenDimDurationOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(DEFAULT_TIMEOUT);
        assertThat(mPowerGroup.getScreenOffTimeoutOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(DEFAULT_TIMEOUT);
    }

    @EnableFlags(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
    @Test
    public void testTimeoutsOverride_validVirtualDeviceId_timeoutsAreOverridden() {
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(VirtualDeviceManagerInternal.class, mVirtualDeviceManagerInternal);

        final long dimDurationOverride = DEFAULT_TIMEOUT * 3;
        final long screenOffTimeoutOverride = DEFAULT_TIMEOUT * 5;

        when(mDisplayManagerInternal.getDisplayIdsForGroup(NON_DEFAULT_GROUP_ID))
                .thenReturn(new int[] {NON_DEFAULT_DISPLAY_ID});
        when(mVirtualDeviceManagerInternal.getDeviceIdForDisplayId(NON_DEFAULT_DISPLAY_ID))
                .thenReturn(VIRTUAL_DEVICE_ID);
        when(mVirtualDeviceManagerInternal.isValidVirtualDeviceId(VIRTUAL_DEVICE_ID))
                .thenReturn(true);
        when(mVirtualDeviceManagerInternal.getDimDurationMillisForDeviceId(VIRTUAL_DEVICE_ID))
                .thenReturn(dimDurationOverride);
        when(mVirtualDeviceManagerInternal.getScreenOffTimeoutMillisForDeviceId(VIRTUAL_DEVICE_ID))
                .thenReturn(screenOffTimeoutOverride);

        mPowerGroup = new PowerGroup(NON_DEFAULT_GROUP_ID, mWakefulnessCallbackMock, mNotifier,
                mDisplayManagerInternal, WAKEFULNESS_AWAKE, /* ready= */ true,
                /* supportsSandman= */ true, TIMESTAMP_CREATE, mFeatureFlags);

        assertThat(mPowerGroup.getScreenDimDurationOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(dimDurationOverride);
        assertThat(mPowerGroup.getScreenOffTimeoutOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(screenOffTimeoutOverride);
    }

    @EnableFlags(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
    @Test
    public void testTimeoutsOverrides_dimDurationIsCapped() {
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(VirtualDeviceManagerInternal.class, mVirtualDeviceManagerInternal);

        final long dimDurationOverride = DEFAULT_TIMEOUT * 5;
        final long screenOffTimeoutOverride = DEFAULT_TIMEOUT * 3;

        when(mDisplayManagerInternal.getDisplayIdsForGroup(NON_DEFAULT_GROUP_ID))
                .thenReturn(new int[] {NON_DEFAULT_DISPLAY_ID});
        when(mVirtualDeviceManagerInternal.getDeviceIdForDisplayId(NON_DEFAULT_DISPLAY_ID))
                .thenReturn(VIRTUAL_DEVICE_ID);
        when(mVirtualDeviceManagerInternal.isValidVirtualDeviceId(VIRTUAL_DEVICE_ID))
                .thenReturn(true);
        when(mVirtualDeviceManagerInternal.getDimDurationMillisForDeviceId(VIRTUAL_DEVICE_ID))
                .thenReturn(dimDurationOverride);
        when(mVirtualDeviceManagerInternal.getScreenOffTimeoutMillisForDeviceId(VIRTUAL_DEVICE_ID))
                .thenReturn(screenOffTimeoutOverride);

        mPowerGroup = new PowerGroup(NON_DEFAULT_GROUP_ID, mWakefulnessCallbackMock, mNotifier,
                mDisplayManagerInternal, WAKEFULNESS_AWAKE, /* ready= */ true,
                /* supportsSandman= */ true, TIMESTAMP_CREATE, mFeatureFlags);

        assertThat(mPowerGroup.getScreenDimDurationOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(screenOffTimeoutOverride);
        assertThat(mPowerGroup.getScreenOffTimeoutOverrideLocked(DEFAULT_TIMEOUT))
                .isEqualTo(screenOffTimeoutOverride);
    }
}
