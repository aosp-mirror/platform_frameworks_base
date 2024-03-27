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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link DeviceStateInfo}.
 * <p/>
 * Run with <code>atest DeviceStateInfoTest</code>.
 */
@RunWith(JUnit4.class)
@SmallTest
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
        final ArrayList<DeviceState> supportedStates = new ArrayList<>(
                List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState baseState = DEVICE_STATE_0;
        final DeviceState currentState = DEVICE_STATE_2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);
        assertNotNull(info.supportedStates);
        assertEquals(supportedStates, info.supportedStates);
        assertEquals(baseState, info.baseState);
        assertEquals(currentState, info.currentState);
    }

    @Test
    public void equals() {
        final ArrayList<DeviceState> supportedStates = new ArrayList<>(
                List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState baseState = DEVICE_STATE_0;
        final DeviceState currentState = DEVICE_STATE_2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);
        Assert.assertEquals(info, info);

        final DeviceStateInfo sameInfo = new DeviceStateInfo(supportedStates, baseState,
                currentState);
        Assert.assertEquals(info, sameInfo);

        final DeviceStateInfo differentInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_0, DEVICE_STATE_2)), baseState, currentState);
        assertNotEquals(info, differentInfo);
    }

    @Test
    public void diff_sameObject() {
        final ArrayList<DeviceState> supportedStates = new ArrayList<>(
                List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState baseState = DEVICE_STATE_0;
        final DeviceState currentState = DEVICE_STATE_2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);
        assertEquals(0, info.diff(info));
    }

    @Test
    public void diff_differentSupportedStates() {
        final DeviceStateInfo info = new DeviceStateInfo(new ArrayList<>(List.of(DEVICE_STATE_1)),
                DEVICE_STATE_0, DEVICE_STATE_0);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_2)), DEVICE_STATE_0, DEVICE_STATE_0);
        final int diff = info.diff(otherInfo);
        assertTrue((diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_BASE_STATE) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_CURRENT_STATE) > 0);
    }

    @Test
    public void diff_differentNonOverrideState() {
        final DeviceStateInfo info = new DeviceStateInfo(new ArrayList<>(List.of(DEVICE_STATE_1)),
                DEVICE_STATE_1, DEVICE_STATE_0);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_1)), DEVICE_STATE_2, DEVICE_STATE_0);
        final int diff = info.diff(otherInfo);
        assertFalse((diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES) > 0);
        assertTrue((diff & DeviceStateInfo.CHANGED_BASE_STATE) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_CURRENT_STATE) > 0);
    }

    @Test
    public void diff_differentState() {
        final DeviceStateInfo info = new DeviceStateInfo(new ArrayList<>(List.of(DEVICE_STATE_1)),
                DEVICE_STATE_0, DEVICE_STATE_1);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(
                new ArrayList<>(List.of(DEVICE_STATE_1)), DEVICE_STATE_0, DEVICE_STATE_2);
        final int diff = info.diff(otherInfo);
        assertFalse((diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_BASE_STATE) > 0);
        assertTrue((diff & DeviceStateInfo.CHANGED_CURRENT_STATE) > 0);
    }

    @Test
    public void writeToParcel() {
        final ArrayList<DeviceState> supportedStates = new ArrayList<>(
                List.of(DEVICE_STATE_0, DEVICE_STATE_1, DEVICE_STATE_2));
        final DeviceState nonOverrideState = DEVICE_STATE_0;
        final DeviceState state = DEVICE_STATE_2;
        final DeviceStateInfo originalInfo =
                new DeviceStateInfo(supportedStates, nonOverrideState, state);

        final Parcel parcel = Parcel.obtain();
        originalInfo.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        final DeviceStateInfo info = DeviceStateInfo.CREATOR.createFromParcel(parcel);
        assertEquals(originalInfo, info);
    }
}
