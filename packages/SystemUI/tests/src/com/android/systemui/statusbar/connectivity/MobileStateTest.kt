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
package com.android.systemui.statusbar.connectivity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MobileStateTest : SysuiTestCase() {

    private val state = MobileState()
    @Before fun setUp() {}

    @Test
    fun testIsDataDisabledOrNotDefault_dataDisabled() {
        state.iconGroup = TelephonyIcons.DATA_DISABLED
        state.userSetup = true
        assertTrue(state.isDataDisabledOrNotDefault)
    }

    @Test
    fun testIsDataDisabledOrNotDefault_notDefaultData() {
        state.iconGroup = TelephonyIcons.NOT_DEFAULT_DATA
        state.userSetup = true
        assertTrue(state.isDataDisabledOrNotDefault)
    }

    @Test
    fun testIsDataDisabledOrNotDefault_notDisabled() {
        state.iconGroup = TelephonyIcons.G
        state.userSetup = true
        assertFalse(state.isDataDisabledOrNotDefault)
    }

    @Test
    fun testHasActivityIn_noData_noActivity() {
        state.dataConnected = false
        state.carrierNetworkChangeMode = false
        state.activityIn = false
        assertFalse(state.hasActivityIn())
    }

    @Test
    fun testHasActivityIn_noData_activityIn() {
        state.dataConnected = false
        state.carrierNetworkChangeMode = false
        state.activityIn = true
        assertFalse(state.hasActivityIn())
    }

    @Test
    fun testHasActivityIn_dataConnected_activityIn() {
        state.dataConnected = true
        state.carrierNetworkChangeMode = false
        state.activityIn = true
        assertTrue(state.hasActivityIn())
    }

    @Test
    fun testHasActivityIn_carrierNetworkChange() {
        state.dataConnected = true
        state.carrierNetworkChangeMode = true
        state.activityIn = true
        assertFalse(state.hasActivityIn())
    }

    @Test
    fun testHasActivityOut_noData_noActivity() {
        state.dataConnected = false
        state.carrierNetworkChangeMode = false
        state.activityOut = false
        assertFalse(state.hasActivityOut())
    }

    @Test
    fun testHasActivityOut_noData_activityOut() {
        state.dataConnected = false
        state.carrierNetworkChangeMode = false
        state.activityOut = true
        assertFalse(state.hasActivityOut())
    }

    @Test
    fun testHasActivityOut_dataConnected_activityOut() {
        state.dataConnected = true
        state.carrierNetworkChangeMode = false
        state.activityOut = true
        assertTrue(state.hasActivityOut())
    }

    @Test
    fun testHasActivityOut_carrierNetworkChange() {
        state.dataConnected = true
        state.carrierNetworkChangeMode = true
        state.activityOut = true
        assertFalse(state.hasActivityOut())
    }
}
