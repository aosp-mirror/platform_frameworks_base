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

import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_IGNORED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_LOCKED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.hardware.devicestate.DeviceStateManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContentResolver;
import android.testing.TestableResources;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.view.RotationPolicy;
import com.android.settingslib.devicestate.DeviceStateRotationLockSettingsManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.concurrency.FakeExecutor;
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

    private static final String[] DEFAULT_SETTINGS = new String[]{"0:1", "2:0:1", "1:2"};
    private static final int[] DEFAULT_FOLDED_STATES = new int[]{0};
    private static final int[] DEFAULT_HALF_FOLDED_STATES = new int[]{2};
    private static final int[] DEFAULT_UNFOLDED_STATES = new int[]{1};

    @Mock private DeviceStateManager mDeviceStateManager;
    @Mock private DeviceStateRotationLockSettingControllerLogger mLogger;
    @Mock private DumpManager mDumpManager;

    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);
    private final RotationPolicyWrapper mFakeRotationPolicy = new FakeRotationPolicy();
    private DeviceStateRotationLockSettingController mDeviceStateRotationLockSettingController;
    private DeviceStateManager.DeviceStateCallback mDeviceStateCallback;
    private DeviceStateRotationLockSettingsManager mSettingsManager;
    private TestableContentResolver mContentResolver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */ this);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.array.config_perDeviceStateRotationLockDefaults, DEFAULT_SETTINGS);
        resources.addOverride(R.array.config_foldedDeviceStates, DEFAULT_FOLDED_STATES);
        resources.addOverride(R.array.config_halfFoldedDeviceStates, DEFAULT_HALF_FOLDED_STATES);
        resources.addOverride(R.array.config_openDeviceStates, DEFAULT_UNFOLDED_STATES);

        ArgumentCaptor<DeviceStateManager.DeviceStateCallback> deviceStateCallbackArgumentCaptor =
                ArgumentCaptor.forClass(DeviceStateManager.DeviceStateCallback.class);

        mContentResolver = mContext.getContentResolver();
        mSettingsManager = DeviceStateRotationLockSettingsManager.getInstance(mContext);
        mDeviceStateRotationLockSettingController =
                new DeviceStateRotationLockSettingController(
                        mFakeRotationPolicy,
                        mDeviceStateManager,
                        mFakeExecutor,
                        mSettingsManager,
                        mLogger,
                        mDumpManager
                );

        mDeviceStateRotationLockSettingController.setListening(true);
        verify(mDeviceStateManager)
                .registerCallback(any(), deviceStateCallbackArgumentCaptor.capture());
        mDeviceStateCallback = deviceStateCallbackArgumentCaptor.getValue();
    }

    @Test
    public void whenSavedSettingsEmpty_defaultsLoadedAndSaved() {
        initializeSettingsWith();

        assertThat(
                        Settings.Secure.getStringForUser(
                                mContentResolver,
                                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                                UserHandle.USER_CURRENT))
                .isEqualTo("0:1:1:2:2:0");
    }

    @Test
    public void whenNoSavedValueForDeviceState_assumeIgnored() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_UNLOCKED, 1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        mFakeRotationPolicy.setRotationLock(true);

        mDeviceStateCallback.onStateChanged(1);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        // Settings only exist for state 0 and 1
        mDeviceStateCallback.onStateChanged(2);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();
    }

    @Test
    public void whenDeviceStateSwitched_loadCorrectSetting() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_UNLOCKED, 1, DEVICE_STATE_ROTATION_LOCK_LOCKED);
        mFakeRotationPolicy.setRotationLock(true);

        mDeviceStateCallback.onStateChanged(0);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateCallback.onStateChanged(1);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();
    }

    @Test
    public void whenDeviceStateSwitched_settingIsIgnored_loadsDefaultFallbackSetting() {
        initializeSettingsWith();
        mFakeRotationPolicy.setRotationLock(true);

        // State 2 -> Ignored -> Fall back to state 1 which is unlocked
        mDeviceStateCallback.onStateChanged(2);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();
    }

    @Test
    public void whenDeviceStateSwitched_ignoredSetting_fallbackValueChanges_usesFallbackValue() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_UNLOCKED,
                1, DEVICE_STATE_ROTATION_LOCK_LOCKED,
                2, DEVICE_STATE_ROTATION_LOCK_IGNORED);
        mFakeRotationPolicy.setRotationLock(false);

        // State 2 -> Ignored -> Fall back to state 1 which is locked
        mDeviceStateCallback.onStateChanged(2);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();
    }

    @Test
    public void whenUserChangesSetting_saveSettingForCurrentState() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_LOCKED, 1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        mSettingsManager.onPersistedSettingsChanged();
        mFakeRotationPolicy.setRotationLock(true);

        mDeviceStateCallback.onStateChanged(0);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();

        mDeviceStateRotationLockSettingController.onRotationLockStateChanged(
                /* rotationLocked= */ false, /* affordanceVisible= */ true);

        assertThat(
                        Settings.Secure.getStringForUser(
                                mContentResolver,
                                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                                UserHandle.USER_CURRENT))
                .isEqualTo("0:2:1:2");
    }

    @Test
    public void whenDeviceStateSwitchedToIgnoredState_useFallbackSetting() {
        mDeviceStateCallback.onStateChanged(0);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();

        mDeviceStateCallback.onStateChanged(2);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();
    }

    @Test
    public void whenDeviceStateSwitchedToIgnoredState_noFallback_newSettingsSaveForPreviousState() {
        initializeSettingsWith(
                8, DEVICE_STATE_ROTATION_LOCK_IGNORED, 1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        mFakeRotationPolicy.setRotationLock(true);

        mDeviceStateCallback.onStateChanged(1);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateCallback.onStateChanged(8);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateRotationLockSettingController.onRotationLockStateChanged(
                /* rotationLocked= */ true, /* affordanceVisible= */ true);

        assertThat(
                        Settings.Secure.getStringForUser(
                                mContentResolver,
                                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                                UserHandle.USER_CURRENT))
                .isEqualTo("1:1:8:0");
    }

    @Test
    public void whenSettingsChangedExternally_updateRotationPolicy() throws InterruptedException {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_UNLOCKED,
                1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        mFakeRotationPolicy.setRotationLock(false);
        mDeviceStateCallback.onStateChanged(0);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        // Changing device state 0 to LOCKED
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_LOCKED, 1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();
    }

    @Test
    public void onRotationLockStateChanged_newSettingIsPersisted() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_LOCKED,
                1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        mDeviceStateCallback.onStateChanged(0);

        mDeviceStateRotationLockSettingController.onRotationLockStateChanged(
                /* rotationLocked= */ false,
                /* affordanceVisible= */ true
        );

        assertThat(
                Settings.Secure.getStringForUser(
                        mContentResolver,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        UserHandle.USER_CURRENT))
                .isEqualTo("0:2:1:2");
    }

    @Test
    public void onRotationLockStateChanged_deviceStateIsIgnored_newSettingIsPersistedToFallback() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_LOCKED,
                1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED,
                2, DEVICE_STATE_ROTATION_LOCK_IGNORED);
        mDeviceStateCallback.onStateChanged(2);

        mDeviceStateRotationLockSettingController.onRotationLockStateChanged(
                /* rotationLocked= */ true,
                /* affordanceVisible= */ true
        );

        assertThat(
                Settings.Secure.getStringForUser(
                        mContentResolver,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        UserHandle.USER_CURRENT))
                .isEqualTo("0:1:1:1:2:0");
    }

    @Test
    public void onRotationLockStateChange_stateIgnored_noFallback_settingIsPersistedToPrevious() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_LOCKED,
                1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED,
                8, DEVICE_STATE_ROTATION_LOCK_IGNORED);
        mDeviceStateCallback.onStateChanged(1);
        mDeviceStateCallback.onStateChanged(8);

        mDeviceStateRotationLockSettingController.onRotationLockStateChanged(
                /* rotationLocked= */ true,
                /* affordanceVisible= */ true
        );

        assertThat(
                Settings.Secure.getStringForUser(
                        mContentResolver,
                        Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                        UserHandle.USER_CURRENT))
                .isEqualTo("0:1:1:1:8:0");
    }

    private void initializeSettingsWith(int... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Expecting key-value pairs");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; ) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(values[i++]).append(":").append(values[i++]);
        }

        Settings.Secure.putStringForUser(
                mContentResolver,
                Settings.Secure.DEVICE_STATE_ROTATION_LOCK,
                sb.toString(),
                UserHandle.USER_CURRENT);

        mSettingsManager.onPersistedSettingsChanged();
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
        public boolean isCameraRotationEnabled() {
            throw new AssertionError("Not implemented");
        }

        @Override
        public void registerRotationPolicyListener(
                RotationPolicy.RotationPolicyListener listener, int userHandle) {
            throw new AssertionError("Not implemented");
        }

        @Override
        public void unregisterRotationPolicyListener(
                RotationPolicy.RotationPolicyListener listener) {
            throw new AssertionError("Not implemented");
        }
    }
}
