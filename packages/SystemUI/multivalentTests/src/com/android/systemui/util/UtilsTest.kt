/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util

import android.hardware.devicestate.DeviceState
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN
import android.hardware.devicestate.feature.flags.Flags
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.testing.TestableResources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.deviceStateManager
import com.android.systemui.kosmos.Kosmos
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class UtilsTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private val deviceStateManager = kosmos.deviceStateManager
    private lateinit var testableResources: TestableResources

    @Before
    fun setUp() {
        testableResources = context.orCreateTestableResources
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun isFoldableReturnsFalse_overlayConfigurationValues() {
        testableResources.addOverride(
            com.android.internal.R.array.config_foldedDeviceStates,
            intArrayOf() // empty array <=> device is not foldable
        )
        whenever(deviceStateManager.supportedDeviceStates).thenReturn(listOf(DEFAULT_DEVICE_STATE))
        assertFalse(Utils.isDeviceFoldable(testableResources.resources, deviceStateManager))
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun isFoldableReturnsFalse_deviceStateManager() {
        testableResources.addOverride(
            com.android.internal.R.array.config_foldedDeviceStates,
            intArrayOf() // empty array <=> device is not foldable
        )
        whenever(deviceStateManager.supportedDeviceStates).thenReturn(listOf(DEFAULT_DEVICE_STATE))
        assertFalse(Utils.isDeviceFoldable(testableResources.resources, deviceStateManager))
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun isFoldableReturnsTrue_overlayConfigurationValues() {
        testableResources.addOverride(
            com.android.internal.R.array.config_foldedDeviceStates,
            intArrayOf(FOLDED_DEVICE_STATE.identifier)
        )
        whenever(deviceStateManager.supportedDeviceStates)
            .thenReturn(listOf(FOLDED_DEVICE_STATE, UNFOLDED_DEVICE_STATE))
        assertTrue(Utils.isDeviceFoldable(testableResources.resources, deviceStateManager))
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun isFoldableReturnsTrue_deviceStateManager() {
        testableResources.addOverride(
            com.android.internal.R.array.config_foldedDeviceStates,
            intArrayOf(FOLDED_DEVICE_STATE.identifier)
        )
        whenever(deviceStateManager.supportedDeviceStates)
            .thenReturn(listOf(FOLDED_DEVICE_STATE, UNFOLDED_DEVICE_STATE))
        assertTrue(Utils.isDeviceFoldable(testableResources.resources, deviceStateManager))
    }

    companion object {
        private val DEFAULT_DEVICE_STATE =
            DeviceState(DeviceState.Configuration.Builder(0 /* identifier */, "DEFAULT").build())
        private val FOLDED_DEVICE_STATE =
            DeviceState(
                DeviceState.Configuration.Builder(1 /* identifier */, "FOLDED")
                    .setSystemProperties(
                        setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY)
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)
                    )
                    .build()
            )
        private val UNFOLDED_DEVICE_STATE =
            DeviceState(
                DeviceState.Configuration.Builder(2 /* identifier */, "UNFOLDED")
                    .setSystemProperties(
                        setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)
                    )
                    .build()
            )
    }
}
