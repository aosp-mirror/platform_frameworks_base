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

package com.android.server.policy;

import static android.os.PowerManager.WAKE_REASON_CAMERA_LAUNCH;
import static android.os.PowerManager.WAKE_REASON_LID;
import static android.os.PowerManager.WAKE_REASON_GESTURE;
import static android.os.PowerManager.WAKE_REASON_POWER_BUTTON;
import static android.os.PowerManager.WAKE_REASON_WAKE_KEY;
import static android.os.PowerManager.WAKE_REASON_WAKE_MOTION;
import static android.view.InputDevice.SOURCE_ROTARY_ENCODER;
import static android.view.InputDevice.SOURCE_TOUCHSCREEN;
import static android.view.KeyEvent.KEYCODE_HOME;
import static android.view.KeyEvent.KEYCODE_POWER;
import static android.view.KeyEvent.KEYCODE_STEM_PRIMARY;

import static com.android.internal.R.bool.config_allowTheaterModeWakeFromKey;
import static com.android.internal.R.bool.config_allowTheaterModeWakeFromPowerKey;
import static com.android.internal.R.bool.config_allowTheaterModeWakeFromMotion;
import static com.android.internal.R.bool.config_allowTheaterModeWakeFromCameraLens;
import static com.android.internal.R.bool.config_allowTheaterModeWakeFromLidSwitch;
import static com.android.internal.R.bool.config_allowTheaterModeWakeFromGesture;
import static com.android.server.policy.Flags.FLAG_SUPPORT_INPUT_WAKEUP_DELEGATE;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.PowerManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.os.Clock;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.function.BooleanSupplier;
/**
 * Test class for {@link WindowWakeUpPolicy}.
 *
 * <p>Build/Install/Run: atest WmTests:WindowWakeUpPolicyTests
 */
public final class WindowWakeUpPolicyTests {
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock PowerManager mPowerManager;
    @Mock Clock mClock;
    @Mock WindowWakeUpPolicyInternal.InputWakeUpDelegate mInputWakeUpDelegate;

    private Context mContextSpy;
    private Resources mResourcesSpy;

    private WindowWakeUpPolicy mPolicy;

    @Before
    public void setUp() {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mContextSpy.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        LocalServices.removeServiceForTest(WindowWakeUpPolicyInternal.class);
    }

    @Test
    public void testSupportsInputWakeDelegatse_publishesLocalService() {
        mSetFlagsRule.enableFlags(FLAG_SUPPORT_INPUT_WAKEUP_DELEGATE);

        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);

        assertThat(LocalServices.getService(WindowWakeUpPolicyInternal.class)).isNotNull();
    }

    @Test
    public void testDoesNotSupportInputWakeDelegatse_doesNotPublishLocalService() {
        mSetFlagsRule.disableFlags(FLAG_SUPPORT_INPUT_WAKEUP_DELEGATE);

        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);

        assertThat(LocalServices.getService(WindowWakeUpPolicyInternal.class)).isNull();
    }

    @Test
    public void testMotionWakeUpDelegation_wakePowerManagerIfDelegateDoesNotHandleWake() {
        setTheaterModeEnabled(false);
        mSetFlagsRule.enableFlags(FLAG_SUPPORT_INPUT_WAKEUP_DELEGATE);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        LocalServices.getService(WindowWakeUpPolicyInternal.class)
                .setInputWakeUpDelegate(mInputWakeUpDelegate);

        setDelegatedMotionWakeUpResult(true);

        // Verify the policy wake up call succeeds because of the call on the delegate, and not
        // because of a PowerManager wake up.
        assertThat(mPolicy.wakeUpFromMotion(200, SOURCE_TOUCHSCREEN, true)).isTrue();
        verify(mInputWakeUpDelegate).wakeUpFromMotion(200, SOURCE_TOUCHSCREEN, true);
        verifyNoPowerManagerWakeUp();

        setDelegatedMotionWakeUpResult(false);

        // Verify the policy wake up call succeeds because of the PowerManager wake up, since the
        // delegate would not handle the wake up request.
        assertThat(mPolicy.wakeUpFromMotion(300, SOURCE_ROTARY_ENCODER, false)).isTrue();
        verify(mInputWakeUpDelegate).wakeUpFromMotion(300, SOURCE_ROTARY_ENCODER, false);
        verify(mPowerManager).wakeUp(300, WAKE_REASON_WAKE_MOTION, "android.policy:MOTION");
    }

    @Test
    public void testKeyWakeUpDelegation_wakePowerManagerIfDelegateDoesNotHandleWake() {
        setTheaterModeEnabled(false);
        mSetFlagsRule.enableFlags(FLAG_SUPPORT_INPUT_WAKEUP_DELEGATE);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        LocalServices.getService(WindowWakeUpPolicyInternal.class)
                .setInputWakeUpDelegate(mInputWakeUpDelegate);

        setDelegatedKeyWakeUpResult(true);

        // Verify the policy wake up call succeeds because of the call on the delegate, and not
        // because of a PowerManager wake up.
        assertThat(mPolicy.wakeUpFromKey(200, KEYCODE_POWER, true)).isTrue();
        verify(mInputWakeUpDelegate).wakeUpFromKey(200, KEYCODE_POWER, true);
        verifyNoPowerManagerWakeUp();

        setDelegatedKeyWakeUpResult(false);

        // Verify the policy wake up call succeeds because of the PowerManager wake up, since the
        // delegate would not handle the wake up request.
        assertThat(mPolicy.wakeUpFromKey(300, KEYCODE_STEM_PRIMARY, false)).isTrue();
        verify(mInputWakeUpDelegate).wakeUpFromKey(300, KEYCODE_STEM_PRIMARY, false);
        verify(mPowerManager).wakeUp(300, WAKE_REASON_WAKE_KEY, "android.policy:KEY");
    }

    @Test
    public void testDelegatedKeyWakeIsSubjectToPolicyChecks() {
        mSetFlagsRule.enableFlags(FLAG_SUPPORT_INPUT_WAKEUP_DELEGATE);
        setDelegatedKeyWakeUpResult(true);
        setTheaterModeEnabled(true);
        setBooleanRes(config_allowTheaterModeWakeFromKey, false);
        setBooleanRes(config_allowTheaterModeWakeFromPowerKey, false);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        LocalServices.getService(WindowWakeUpPolicyInternal.class)
                .setInputWakeUpDelegate(mInputWakeUpDelegate);

        // Check that the wake up does not happen because the theater mode policy check fails.
        assertThat(mPolicy.wakeUpFromKey(200, KEYCODE_POWER, true)).isFalse();
        verify(mInputWakeUpDelegate, never()).wakeUpFromKey(anyLong(), anyInt(), anyBoolean());
    }

    @Test
    public void testDelegatedMotionWakeIsSubjectToPolicyChecks() {
        mSetFlagsRule.enableFlags(FLAG_SUPPORT_INPUT_WAKEUP_DELEGATE);
        setDelegatedMotionWakeUpResult(true);
        setTheaterModeEnabled(true);
        setBooleanRes(config_allowTheaterModeWakeFromMotion, false);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        LocalServices.getService(WindowWakeUpPolicyInternal.class)
                .setInputWakeUpDelegate(mInputWakeUpDelegate);

        // Check that the wake up does not happen because the theater mode policy check fails.
        assertThat(mPolicy.wakeUpFromMotion(200, SOURCE_TOUCHSCREEN, true)).isFalse();
        verify(mInputWakeUpDelegate, never()).wakeUpFromMotion(anyLong(), anyInt(), anyBoolean());
    }

    @Test
    public void testWakeUpFromMotion() {
        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromMotion(mClock.uptimeMillis(), SOURCE_TOUCHSCREEN, true),
                config_allowTheaterModeWakeFromMotion,
                WAKE_REASON_WAKE_MOTION,
                "android.policy:MOTION");
    }

    @Test
    public void testWakeUpFromKey_nonPowerKey() {
        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromKey(mClock.uptimeMillis(), KEYCODE_HOME, true),
                config_allowTheaterModeWakeFromKey,
                WAKE_REASON_WAKE_KEY,
                "android.policy:KEY");
    }

    @Test
    public void testWakeUpFromKey_powerKey() {
        // Disable the resource affecting all wake keys because it affects power key as well.
        // That way, power key wake during theater mode will solely be controlled by
        // `config_allowTheaterModeWakeFromPowerKey` in the checks.
        setBooleanRes(config_allowTheaterModeWakeFromKey, false);

        // Test with power key
        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromKey(mClock.uptimeMillis(), KEYCODE_POWER, true),
                config_allowTheaterModeWakeFromPowerKey,
                WAKE_REASON_POWER_BUTTON,
                "android.policy:POWER");

        // Test that power key wake ups happen during theater mode as long as wake-keys are allowed
        // even if the power-key specific theater mode config is disabled.
        setBooleanRes(config_allowTheaterModeWakeFromPowerKey, false);
        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromKey(mClock.uptimeMillis(), KEYCODE_POWER, false),
                config_allowTheaterModeWakeFromKey,
                WAKE_REASON_POWER_BUTTON,
                "android.policy:POWER");
    }

    @Test
    public void testWakeUpFromLid() {
        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromLid(),
                config_allowTheaterModeWakeFromLidSwitch,
                WAKE_REASON_LID,
                "android.policy:LID");
    }

    @Test
    public void testWakeUpFromWakeGesture() {
        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromWakeGesture(),
                config_allowTheaterModeWakeFromGesture,
                WAKE_REASON_GESTURE,
                "android.policy:GESTURE");
    }

    @Test
    public void testwakeUpFromCameraCover() {
        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromCameraCover(mClock.uptimeMillis()),
                config_allowTheaterModeWakeFromCameraLens,
                WAKE_REASON_CAMERA_LAUNCH,
                "android.policy:CAMERA_COVER");
    }

    @Test
    public void testWakeUpFromPowerKeyCameraGesture() {
        // Disable the resource affecting all wake keys because it affects power key as well.
        // That way, power key wake during theater mode will solely be controlled by
        // `config_allowTheaterModeWakeFromPowerKey` in the checks.
        setBooleanRes(config_allowTheaterModeWakeFromKey, false);

        runPowerManagerUpChecks(
                () -> mPolicy.wakeUpFromPowerKeyCameraGesture(),
                config_allowTheaterModeWakeFromPowerKey,
                WAKE_REASON_CAMERA_LAUNCH,
                "android.policy:CAMERA_GESTURE_PREVENT_LOCK");
    }

    private void runPowerManagerUpChecks(
            BooleanSupplier wakeUpCall,
            int theatherModeWakeResId,
            int expectedWakeReason,
            String expectedWakeDetails) {
        // Test under theater mode enabled.
        setTheaterModeEnabled(true);

        Mockito.reset(mPowerManager);
        setBooleanRes(theatherModeWakeResId, true);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        setUptimeMillis(200);
        assertWithMessage("Wake should happen in theater mode when config allows it.")
                .that(wakeUpCall.getAsBoolean()).isTrue();
        verify(mPowerManager).wakeUp(200L, expectedWakeReason, expectedWakeDetails);

        Mockito.reset(mPowerManager);
        setBooleanRes(theatherModeWakeResId, false);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        setUptimeMillis(250);
        assertWithMessage("Wake should not happen in theater mode when config disallows it.")
                .that(wakeUpCall.getAsBoolean()).isFalse();
        verifyNoPowerManagerWakeUp();

        // Cases when theater mode is disabled.
        setTheaterModeEnabled(false);

        Mockito.reset(mPowerManager);
        setBooleanRes(theatherModeWakeResId, true);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        setUptimeMillis(300);
        assertWithMessage("Wake should happen when not in theater mode.")
                .that(wakeUpCall.getAsBoolean()).isTrue();
        verify(mPowerManager).wakeUp(300L, expectedWakeReason, expectedWakeDetails);

        Mockito.reset(mPowerManager);
        setBooleanRes(theatherModeWakeResId, false);
        mPolicy = new WindowWakeUpPolicy(mContextSpy, mClock);
        setUptimeMillis(350);
        assertWithMessage("Wake should happen when not in theater mode.")
                .that(wakeUpCall.getAsBoolean()).isTrue();
        verify(mPowerManager).wakeUp(350L, expectedWakeReason, expectedWakeDetails);
    }

    private void verifyNoPowerManagerWakeUp() {
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    private void setBooleanRes(int resId, boolean val) {
        when(mResourcesSpy.getBoolean(resId)).thenReturn(val);
    }

    private void setUptimeMillis(long uptimeMillis) {
        when(mClock.uptimeMillis()).thenReturn(uptimeMillis);
    }

    private void setTheaterModeEnabled(boolean enabled) {
        Settings.Global.putInt(
                mContextSpy.getContentResolver(), Settings.Global.THEATER_MODE_ON, enabled ? 1 : 0);
    }

    private void setDelegatedMotionWakeUpResult(boolean result) {
        when(mInputWakeUpDelegate.wakeUpFromMotion(anyLong(), anyInt(), anyBoolean()))
                .thenReturn(result);
    }

    private void setDelegatedKeyWakeUpResult(boolean result) {
        when(mInputWakeUpDelegate.wakeUpFromKey(anyLong(), anyInt(), anyBoolean()))
                .thenReturn(result);
    }
}
