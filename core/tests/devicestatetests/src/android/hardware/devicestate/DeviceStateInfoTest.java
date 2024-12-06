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

package android.hardware.devicestate;

import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link DeviceStateInfo}.
 *
 * <p> Build/Install/Run:
 * atest FrameworksCoreDeviceStateManagerTests:DeviceStateInfoTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DeviceStateInfoTest {
    private static final DeviceState DEVICE_STATE_0 = new DeviceState(
            new DeviceState.Configuration.Builder(0, "STATE_0")
                    .setSystemProperties(
                            Set.of(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS,
                                    PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY))
                    .setPhysicalProperties(
                            Set.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED))
                    .build());
    private static final DeviceState DEVICE_STATE_1 = new DeviceState(
            new DeviceState.Configuration.Builder(1, "STATE_1")
                    .setSystemProperties(
                            Set.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY))
                    .setPhysicalProperties(
                            Set.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN))
                    .build());
    private static final DeviceState DEVICE_STATE_2 = new DeviceState(
            new DeviceState.Configuration.Builder(2, "STATE_2")
                    .setSystemProperties(
                            Set.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY))
                    .build());

    @Test
    public void create() {
        final ArrayList<DeviceState> supportedStates =
                new ArrayList<>(List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState baseState = DEVICE_STATE_0;
        final DeviceState currentState = DEVICE_STATE_2;

        final DeviceStateInfo info =
                new DeviceStateInfo(supportedStates, baseState, currentState);

        assertThat(info.supportedStates).containsExactlyElementsIn(supportedStates).inOrder();
        assertThat(info.baseState).isEqualTo(baseState);
        assertThat(info.currentState).isEqualTo(currentState);
    }

    @Test
    public void equals() {
        final ArrayList<DeviceState> supportedStates =
                new ArrayList<>(List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState baseState = DEVICE_STATE_0;
        final DeviceState currentState = DEVICE_STATE_2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);
        final DeviceStateInfo sameInstance = info;
        assertThat(info).isEqualTo(sameInstance);

        final DeviceStateInfo sameInfo =
                new DeviceStateInfo(supportedStates, baseState, currentState);
        assertThat(info).isEqualTo(sameInfo);

        final DeviceStateInfo copiedInfo = new DeviceStateInfo(info);
        assertThat(info).isEqualTo(copiedInfo);

        final DeviceStateInfo differentInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_0, DEVICE_STATE_2)), baseState, currentState);
        assertThat(differentInfo).isNotEqualTo(info);
    }

    @Test
    public void hashCode_sameObject() {
        final ArrayList<DeviceState> supportedStates =
                new ArrayList<>(List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState baseState = DEVICE_STATE_0;
        final DeviceState currentState = DEVICE_STATE_2;
        final DeviceStateInfo info =
                new DeviceStateInfo(supportedStates, baseState, currentState);
        final DeviceStateInfo copiedInfo = new DeviceStateInfo(info);

        assertThat(info.hashCode()).isEqualTo(copiedInfo.hashCode());
    }

    @Test
    public void diff_sameObject() {
        final ArrayList<DeviceState> supportedStates =
                new ArrayList<>(List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState baseState = DEVICE_STATE_0;
        final DeviceState currentState = DEVICE_STATE_2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);

        assertThat(info.diff(info)).isEqualTo(0);
    }

    @Test
    public void diff_differentSupportedStates() {
        final DeviceStateInfo info = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_1)), DEVICE_STATE_0, DEVICE_STATE_0);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_2)), DEVICE_STATE_0, DEVICE_STATE_0);

        final int diff = info.diff(otherInfo);

        assertThat(diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES).isGreaterThan(0);
        assertThat(diff & DeviceStateInfo.CHANGED_BASE_STATE).isEqualTo(0);
        assertThat(diff & DeviceStateInfo.CHANGED_CURRENT_STATE).isEqualTo(0);
    }

    @Test
    public void diff_differentNonOverrideState() {
        final DeviceStateInfo info = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_1)), DEVICE_STATE_1, DEVICE_STATE_0);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_1)), DEVICE_STATE_2, DEVICE_STATE_0);

        final int diff = info.diff(otherInfo);

        assertThat(diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES).isEqualTo(0);
        assertThat(diff & DeviceStateInfo.CHANGED_BASE_STATE).isGreaterThan(0);
        assertThat(diff & DeviceStateInfo.CHANGED_CURRENT_STATE).isEqualTo(0);
    }

    @Test
    public void diff_differentState() {
        final DeviceStateInfo info = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_1)), DEVICE_STATE_0, DEVICE_STATE_1);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_1)), DEVICE_STATE_0, DEVICE_STATE_2);

        final int diff = info.diff(otherInfo);

        assertThat(diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES).isEqualTo(0);
        assertThat(diff & DeviceStateInfo.CHANGED_BASE_STATE).isEqualTo(0);
        assertThat(diff & DeviceStateInfo.CHANGED_CURRENT_STATE).isGreaterThan(0);
    }

    @Test
    public void writeToParcel() {
        final ArrayList<DeviceState> supportedStates =
                new ArrayList<>(List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState nonOverrideState = DEVICE_STATE_0;
        final DeviceState state = DEVICE_STATE_2;
        final DeviceStateInfo originalInfo =
                new DeviceStateInfo(supportedStates, nonOverrideState, state);

        final Parcel parcel = Parcel.obtain();
        originalInfo.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        final DeviceStateInfo info = DeviceStateInfo.CREATOR.createFromParcel(parcel);
        assertThat(info).isEqualTo(originalInfo);
    }
}
