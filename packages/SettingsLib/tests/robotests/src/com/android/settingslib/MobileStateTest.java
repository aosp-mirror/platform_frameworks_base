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

package com.android.settingslib;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import com.android.settingslib.mobile.TelephonyIcons;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MobileStateTest {

    private SignalIcon.MobileState mState = new SignalIcon.MobileState();

    @Before
    public void setUp() {
    }

    @Test
    public void testIsDataDisabledOrNotDefault_dataDisabled() {
        mState.iconGroup = TelephonyIcons.DATA_DISABLED;
        mState.userSetup = true;

        assertTrue(mState.isDataDisabledOrNotDefault());
    }

    @Test
    public void testIsDataDisabledOrNotDefault_notDefaultData() {
        mState.iconGroup = TelephonyIcons.NOT_DEFAULT_DATA;
        mState.userSetup = true;

        assertTrue(mState.isDataDisabledOrNotDefault());
    }

    @Test
    public void testIsDataDisabledOrNotDefault_notDisabled() {
        mState.iconGroup = TelephonyIcons.G;
        mState.userSetup = true;

        assertFalse(mState.isDataDisabledOrNotDefault());
    }

    @Test
    public void testHasActivityIn_noData_noActivity() {
        mState.dataConnected = false;
        mState.carrierNetworkChangeMode = false;
        mState.activityIn = false;

        assertFalse(mState.hasActivityIn());
    }

    @Test
    public void testHasActivityIn_noData_activityIn() {
        mState.dataConnected = false;
        mState.carrierNetworkChangeMode = false;
        mState.activityIn = true;

        assertFalse(mState.hasActivityIn());
    }

    @Test
    public void testHasActivityIn_dataConnected_activityIn() {
        mState.dataConnected = true;
        mState.carrierNetworkChangeMode = false;
        mState.activityIn = true;

        assertTrue(mState.hasActivityIn());
    }

    @Test
    public void testHasActivityIn_carrierNetworkChange() {
        mState.dataConnected = true;
        mState.carrierNetworkChangeMode = true;
        mState.activityIn = true;

        assertFalse(mState.hasActivityIn());
    }

    @Test
    public void testHasActivityOut_noData_noActivity() {
        mState.dataConnected = false;
        mState.carrierNetworkChangeMode = false;
        mState.activityOut = false;

        assertFalse(mState.hasActivityOut());
    }

    @Test
    public void testHasActivityOut_noData_activityOut() {
        mState.dataConnected = false;
        mState.carrierNetworkChangeMode = false;
        mState.activityOut = true;

        assertFalse(mState.hasActivityOut());
    }

    @Test
    public void testHasActivityOut_dataConnected_activityOut() {
        mState.dataConnected = true;
        mState.carrierNetworkChangeMode = false;
        mState.activityOut = true;

        assertTrue(mState.hasActivityOut());
    }

    @Test
    public void testHasActivityOut_carrierNetworkChange() {
        mState.dataConnected = true;
        mState.carrierNetworkChangeMode = true;
        mState.activityOut = true;

        assertFalse(mState.hasActivityOut());
    }
}
