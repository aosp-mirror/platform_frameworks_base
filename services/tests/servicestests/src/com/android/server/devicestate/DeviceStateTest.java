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

package com.android.server.devicestate;

import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link DeviceState}.
 * <p/>
 * Run with <code>atest DeviceStateTest</code>.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class DeviceStateTest {
    @Test
    public void testConstruct() {
        final DeviceState state = new DeviceState(MINIMUM_DEVICE_STATE /* identifier */,
                "TEST_CLOSED" /* name */, DeviceState.FLAG_CANCEL_OVERRIDE_REQUESTS /* flags */);
        assertEquals(state.getIdentifier(), MINIMUM_DEVICE_STATE);
        assertEquals(state.getName(), "TEST_CLOSED");
        assertEquals(state.getFlags(), DeviceState.FLAG_CANCEL_OVERRIDE_REQUESTS);
    }

    @Test
    public void testConstruct_nullName() {
        final DeviceState state = new DeviceState(MAXIMUM_DEVICE_STATE /* identifier */,
                null /* name */, 0/* flags */);
        assertEquals(state.getIdentifier(), MAXIMUM_DEVICE_STATE);
        assertNull(state.getName());
        assertEquals(state.getFlags(), 0);
    }

    @Test
    public void testConstruct_tooLargeIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> {
            final DeviceState state = new DeviceState(MAXIMUM_DEVICE_STATE + 1 /* identifier */,
                    null /* name */, 0 /* flags */);
        });
    }

    @Test
    public void testConstruct_tooSmallIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> {
            final DeviceState state = new DeviceState(MINIMUM_DEVICE_STATE - 1 /* identifier */,
                    null /* name */, 0 /* flags */);
        });
    }
}
