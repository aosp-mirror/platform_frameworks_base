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

import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_IGNORED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_LOCKED;
import static android.provider.Settings.Secure.DEVICE_STATE_ROTATION_LOCK_UNLOCKED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableContentResolver;
import android.testing.TestableResources;

import androidx.test.ext.junit.runners.AndroidJUnit4;
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

import java.util.Collections;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DeviceStateRotationLockSettingControllerTest extends SysuiTestCase {

    private static final DeviceState DEFAULT_FOLDED_STATE = createDeviceState(0 /* identifier */,
            "folded", Set.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY),
            Set.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED));
    private static final DeviceState DEFAULT_HALF_FOLDED_STATE = createDeviceState(
            2 /* identifier */, "half_folded",
            Set.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY),
            Set.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN));
    private static final DeviceState DEFAULT_UNFOLDED_STATE = createDeviceState(1 /* identifier */,
            "unfolded",
            Set.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY),
            Set.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN));
    private static final DeviceState UNKNOWN_DEVICE_STATE = createDeviceState(8 /* identifier */,
            "unknown", Collections.emptySet(), Collections.emptySet());
    private static final List<DeviceState> DEVICE_STATE_LIST = List.of(DEFAULT_FOLDED_STATE,
            DEFAULT_HALF_FOLDED_STATE, DEFAULT_UNFOLDED_STATE);

    private static final String[] DEFAULT_SETTINGS = new String[]{"0:1", "2:0:1", "1:2"};
    private static final int[] DEFAULT_FOLDED_STATE_IDENTIFIERS =
            new int[]{DEFAULT_FOLDED_STATE.getIdentifier()};
    private static final int[] DEFAULT_HALF_FOLDED_STATE_IDENTIFIERS =
            new int[]{DEFAULT_HALF_FOLDED_STATE.getIdentifier()};
    private static final int[] DEFAULT_UNFOLDED_STATE_IDENTIFIERS =
            new int[]{DEFAULT_UNFOLDED_STATE.getIdentifier()};

    @Mock private DeviceStateManager mDeviceStateManager;
    @Mock private DeviceStateRotationLockSettingControllerLogger mLogger;
    @Mock private DumpManager mDumpManager;

    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeExecutor mFakeExecutor = new FakeExecutor(mFakeSystemClock);
    private final FakeRotationPolicy mFakeRotationPolicy = new FakeRotationPolicy();
    private DeviceStateRotationLockSettingController mDeviceStateRotationLockSettingController;
    private DeviceStateManager.DeviceStateCallback mDeviceStateCallback;
    private DeviceStateRotationLockSettingsManager mSettingsManager;
    private TestableContentResolver mContentResolver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */ this);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.array.config_perDeviceStateRotationLockDefaults, DEFAULT_SETTINGS);
        resources.addOverride(R.array.config_foldedDeviceStates, DEFAULT_FOLDED_STATE_IDENTIFIERS);
        resources.addOverride(R.array.config_halfFoldedDeviceStates,
                DEFAULT_HALF_FOLDED_STATE_IDENTIFIERS);
        resources.addOverride(R.array.config_openDeviceStates, DEFAULT_UNFOLDED_STATE_IDENTIFIERS);
        when(mDeviceStateManager.getSupportedDeviceStates()).thenReturn(DEVICE_STATE_LIST);
        mContext.addMockSystemService(DeviceStateManager.class, mDeviceStateManager);
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

        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_UNFOLDED_STATE);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        // Settings only exist for state 0 and 1
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_HALF_FOLDED_STATE);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();
    }

    @Test
    public void whenDeviceStateSwitched_loadCorrectSetting() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_UNLOCKED, 1, DEVICE_STATE_ROTATION_LOCK_LOCKED);
        mFakeRotationPolicy.setRotationLock(true);

        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_FOLDED_STATE);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_UNFOLDED_STATE);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();
    }

    @Test
    public void whenDeviceStateSwitched_settingIsIgnored_loadsDefaultFallbackSetting() {
        initializeSettingsWith();
        mFakeRotationPolicy.setRotationLock(true);

        // State 2 -> Ignored -> Fall back to state 1 which is unlocked
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_HALF_FOLDED_STATE);

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
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_HALF_FOLDED_STATE);

        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();
    }

    @Test
    public void whenUserChangesSetting_saveSettingForCurrentState() {
        initializeSettingsWith(
                0, DEVICE_STATE_ROTATION_LOCK_LOCKED, 1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        mSettingsManager.onPersistedSettingsChanged();
        mFakeRotationPolicy.setRotationLock(true);

        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_FOLDED_STATE);
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
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_FOLDED_STATE);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isTrue();

        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_UNFOLDED_STATE);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();
    }

    @Test
    public void whenDeviceStateSwitchedToIgnoredState_noFallback_newSettingsSaveForPreviousState() {
        initializeSettingsWith(
                8, DEVICE_STATE_ROTATION_LOCK_IGNORED, 1, DEVICE_STATE_ROTATION_LOCK_UNLOCKED);
        mFakeRotationPolicy.setRotationLock(true);

        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_UNFOLDED_STATE);
        assertThat(mFakeRotationPolicy.isRotationLocked()).isFalse();

        mDeviceStateCallback.onDeviceStateChanged(UNKNOWN_DEVICE_STATE);
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
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_FOLDED_STATE);

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
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_FOLDED_STATE);

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
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_HALF_FOLDED_STATE);

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
        mDeviceStateCallback.onDeviceStateChanged(DEFAULT_UNFOLDED_STATE);
        mDeviceStateCallback.onDeviceStateChanged(UNKNOWN_DEVICE_STATE);

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

    private static DeviceState createDeviceState(int identifier, @NonNull String name,
            @NonNull Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties,
            @NonNull Set<@DeviceState.PhysicalDeviceStateProperties Integer> physicalProperties) {
        DeviceState.Configuration deviceStateConfiguration = new DeviceState.Configuration.Builder(
                identifier, name).setSystemProperties(systemProperties).setPhysicalProperties(
                physicalProperties).build();
        return new DeviceState(deviceStateConfiguration);
    }

    private static class FakeRotationPolicy implements RotationPolicyWrapper {

        private boolean mRotationLock;

        public void setRotationLock(boolean enabled) {
            setRotationLock(enabled, /* caller= */ "FakeRotationPolicy");
        }

        @Override
        public void setRotationLock(boolean enabled, String caller) {
            mRotationLock = enabled;
        }

        public void setRotationLockAtAngle(boolean enabled, int rotation) {
            setRotationLockAtAngle(enabled, rotation, /* caller= */ "FakeRotationPolicy");
        }

        @Override
        public void setRotationLockAtAngle(boolean enabled, int rotation, String caller) {
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
