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

import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE_IDENTIFIER;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link android.hardware.devicestate.DeviceState}.
 * <p/>
 * Run with <code>atest DeviceStateTest</code>.
 */
@Presubmit
@RunWith(JUnit4.class)
public final class DeviceStateTest {
    @Test
    public void testConstruct() {
        DeviceState.Configuration config = new DeviceState.Configuration.Builder(
                MINIMUM_DEVICE_STATE_IDENTIFIER, "TEST_CLOSED")
                .setSystemProperties(
                        new HashSet<>(List.of(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS)))
                .build();
        final DeviceState state = new DeviceState(config);
        assertEquals(state.getIdentifier(), MINIMUM_DEVICE_STATE_IDENTIFIER);
        assertEquals(state.getName(), "TEST_CLOSED");
        assertTrue(state.hasProperty(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS));
    }

    @Test
    public void testHasProperties() {
        DeviceState.Configuration config = new DeviceState.Configuration.Builder(
                MINIMUM_DEVICE_STATE_IDENTIFIER, "TEST")
                .setSystemProperties(new HashSet<>(List.of(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS,
                        PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST)))
                .build();

        final DeviceState state = new DeviceState(config);

        assertTrue(state.hasProperty(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS));
        assertTrue(state.hasProperty(PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST));
        assertTrue(state.hasProperties(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS,
                PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST));
    }

    @Test
    public void writeToParcel() {
        final DeviceState originalState = new DeviceState(
                new DeviceState.Configuration.Builder(0, "TEST_STATE")
                        .setSystemProperties(Set.of(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS,
                                PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST))
                        .setPhysicalProperties(
                                Set.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED))
                        .build());

        final Parcel parcel = Parcel.obtain();
        originalState.getConfiguration().writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        final DeviceState.Configuration stateConfiguration =
                DeviceState.Configuration.CREATOR.createFromParcel(parcel);

        Assert.assertEquals(originalState, new DeviceState(stateConfiguration));
    }
}
