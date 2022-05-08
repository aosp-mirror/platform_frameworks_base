/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.hardware.devicestate.DeviceStateManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableResources;

import androidx.test.filters.SmallTest;

import com.android.internal.view.RotationPolicy;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.wrapper.RotationPolicyWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class DeviceStateRotationLockSettingControllerTest extends SysuiTestCase {

    private static final String[] DEFAULT_SETTINGS = new String[]{
            "0:0",
            "1:2"
    };

    private final FakeSettings mFakeSettings = new FakeSettings();
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);
    @Mock DeviceStateManager mDeviceStateManager;
    RotationPolicyWrapper mFakeRotationPolicy = new FakeRotationPolicy();
    DeviceStateRotationLockSettingController mDeviceStateRotationLockSettingController;
    private DeviceStateManager.DeviceStateCallback mDeviceStateCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */ this);
        TestableResources resources = mContext.getOrCreateTestableResources();

        ArgumentCaptor<DeviceStateManager.DeviceStateCallback> deviceStateCallbackArgumentCaptor =
                ArgumentCaptor.forClass(
                        DeviceStateManager.DeviceStateCallback.class);

        mDeviceStateRotationLockSettingController = new DeviceStateRotationLockSettingController(
                mFakeSettings,
                mFakeRotationPolicy,
                mDeviceStateManager,
                mFakeExecutor,
                DEFAULT_SETTINGS
        );

        mDeviceStateRotationLockSettingController.setListening(true);
        verify(mDeviceStateManager).registerCallback(any(),
                deviceStateCallbackArgumentCaptor.capture());
        mDeviceStateCallback = deviceStateCallbackArgumentCaptor.getValue();
    }

    @Test
    public void whenSavedSettingsEmpty_defaultsLoadedAndSaved() {
        mFakeSettings.putStringForUser(Settings.Secure.DEVICE_STATE_ROTATION_LOCK, "",
                UserHandle.USER_CURRENT);

        mDeviceStateRotationLockSettingController.initialize();

        assertThat(mFakeSettings
                .getStringForUser(Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        UserHandle.USER_CURRENT))
                .isEqualTo("0:0:1:2");
    }

    @Test
    public void whenNoSavedValueForDeviceState_assumeIgnored() {
        mFakeSettings.putStringForUser(
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                /* value= */"0:2:1:2",
                UserHandle.USER_CURRENT);
        mFakeRotationPolicy.setRotationLock(true);
        mDeviceStateRotationLockSettingController.initialize();

        mDeviceStateCallback.onStateChanged(1);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        // Settings only exist for state 0 and 1
        mDeviceStateCallback.onStateChanged(2);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();
    }

    @Test
    public void whenDeviceStateSwitched_loadCorrectSetting() {
        mFakeSettings.putStringForUser(
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                /* value= */"0:2:1:1",
                UserHandle.USER_CURRENT);
        mFakeRotationPolicy.setRotationLock(true);
        mDeviceStateRotationLockSettingController.initialize();

        mDeviceStateCallback.onStateChanged(0);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateCallback.onStateChanged(1);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();

    }

    @Test
    public void whenUserChangesSetting_saveSettingForCurrentState() {
        mFakeSettings.putStringForUser(
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                /* value= */"0:1:1:2",
                UserHandle.USER_CURRENT);
        mFakeRotationPolicy.setRotationLock(true);
        mDeviceStateRotationLockSettingController.initialize();

        mDeviceStateCallback.onStateChanged(0);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();

        mDeviceStateRotationLockSettingController
                .onRotationLockStateChanged(/* rotationLocked= */false,
                        /* affordanceVisible= */ true);

        assertThat(mFakeSettings
                .getStringForUser(Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        UserHandle.USER_CURRENT))
                .isEqualTo("0:2:1:2");
    }


    @Test
    public void whenDeviceStateSwitchedToIgnoredState_usePreviousSetting() {
        mFakeSettings.putStringForUser(
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                /* value= */"0:0:1:2",
                UserHandle.USER_CURRENT);
        mFakeRotationPolicy.setRotationLock(true);
        mDeviceStateRotationLockSettingController.initialize();

        mDeviceStateCallback.onStateChanged(1);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateCallback.onStateChanged(0);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();
    }

    @Test
    public void whenDeviceStateSwitchedToIgnoredState_newSettingsSaveForPreviousState() {
        mFakeSettings.putStringForUser(
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                /* value= */"0:0:1:2",
                UserHandle.USER_CURRENT);
        mFakeRotationPolicy.setRotationLock(true);
        mDeviceStateRotationLockSettingController.initialize();

        mDeviceStateCallback.onStateChanged(1);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateCallback.onStateChanged(0);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateRotationLockSettingController
                .onRotationLockStateChanged(/* rotationLocked= */true,
                        /* affordanceVisible= */ true);

        assertThat(mFakeSettings
                .getStringForUser(Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        UserHandle.USER_CURRENT))
                .isEqualTo("0:0:1:1");
    }

    private static class FakeRotationPolicy implements RotationPolicyWrapper {

        private boolean mRotationLock;

        @Override
        public void setRotationLock(boolean enabled) {
            mRotationLock = enabled;
        }

        @Override
        public void setRotationLockAtAngle(boolean enabled, int rotation) {
            mRotationLock = enabled;
        }

        @Override
        public int getRotationLockOrientation() {
            throw new AssertionError("Not implemented");
        }

        @Override
        public boolean isRotationLockToggleVisible() {
            throw new AssertionError("Not implemented");
        }

        @Override
        public boolean isRotationLocked() {
            return mRotationLock;
        }

        @Override
        public void registerRotationPolicyListener(RotationPolicy.RotationPolicyListener listener,
                int userHandle) {
            throw new AssertionError("Not implemented");
        }

        @Override
        public void unregisterRotationPolicyListener(
                RotationPolicy.RotationPolicyListener listener) {
            throw new AssertionError("Not implemented");
        }
    }
}
