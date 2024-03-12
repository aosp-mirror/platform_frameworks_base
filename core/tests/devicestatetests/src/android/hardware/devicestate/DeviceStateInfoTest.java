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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link DeviceStateInfo}.
 * <p/>
 * Run with <code>atest DeviceStateInfoTest</code>.
 */
@RunWith(JUnit4.class)
@SmallTest
public final class DeviceStateInfoTest {
    @Test
    public void create() {
        final int[] supportedStates = new int[] { 0, 1, 2 };
        final int baseState = 0;
        final int currentState = 2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);
        assertNotNull(info.supportedStates);
        assertEquals(supportedStates, info.supportedStates);
        assertEquals(baseState, info.baseState);
        assertEquals(currentState, info.currentState);
    }

    @Test
    public void equals() {
        final int[] supportedStates = new int[] { 0, 1, 2 };
        final int baseState = 0;
        final int currentState = 2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);
        assertTrue(info.equals(info));

        final DeviceStateInfo sameInfo = new DeviceStateInfo(supportedStates, baseState,
                currentState);
        assertTrue(info.equals(sameInfo));

        final DeviceStateInfo differentInfo = new DeviceStateInfo(new int[]{ 0, 2}, baseState,
                currentState);
        assertFalse(info.equals(differentInfo));
    }

    @Test
    public void diff_sameObject() {
        final int[] supportedStates = new int[] { 0, 1, 2 };
        final int baseState = 0;
        final int currentState = 2;

        final DeviceStateInfo info = new DeviceStateInfo(supportedStates, baseState, currentState);
        assertEquals(0, info.diff(info));
    }

    @Test
    public void diff_differentSupportedStates() {
        final DeviceStateInfo info = new DeviceStateInfo(new int[] { 1 }, 0, 0);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(new int[] { 2 }, 0, 0);
        final int diff = info.diff(otherInfo);
        assertTrue((diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_BASE_STATE) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_CURRENT_STATE) > 0);
    }

    @Test
    public void diff_differentNonOverrideState() {
        final DeviceStateInfo info = new DeviceStateInfo(new int[] { 1 }, 1, 0);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(new int[] { 1 }, 2, 0);
        final int diff = info.diff(otherInfo);
        assertFalse((diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES) > 0);
        assertTrue((diff & DeviceStateInfo.CHANGED_BASE_STATE) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_CURRENT_STATE) > 0);
    }

    @Test
    public void diff_differentState() {
        final DeviceStateInfo info = new DeviceStateInfo(new int[] { 1 }, 0, 1);
        final DeviceStateInfo otherInfo = new DeviceStateInfo(new int[] { 1 }, 0, 2);
        final int diff = info.diff(otherInfo);
        assertFalse((diff & DeviceStateInfo.CHANGED_SUPPORTED_STATES) > 0);
        assertFalse((diff & DeviceStateInfo.CHANGED_BASE_STATE) > 0);
        assertTrue((diff & DeviceStateInfo.CHANGED_CURRENT_STATE) > 0);
    }

    @Test
    public void writeToParcel() {
        final int[] supportedStates = new int[] { 0, 1, 2 };
        final int nonOverrideState = 0;
        final int state = 2;
        final DeviceStateInfo originalInfo =
                new DeviceStateInfo(supportedStates, nonOverrideState, state);

        final Parcel parcel = Parcel.obtain();
        originalInfo.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);

        final DeviceStateInfo info = DeviceStateInfo.CREATOR.createFromParcel(parcel);
        assertEquals(originalInfo, info);
    }
}
