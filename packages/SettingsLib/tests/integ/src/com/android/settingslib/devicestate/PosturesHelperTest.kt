/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.devicestate

import android.content.Context
import android.content.res.Resources
import android.hardware.devicestate.DeviceState
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN
import android.hardware.devicestate.DeviceStateManager
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_HALF_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNFOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNKNOWN
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.internal.R
import com.google.common.truth.Expect
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import android.hardware.devicestate.feature.flags.Flags as DeviceStateManagerFlags
import org.mockito.Mockito.`when` as whenever

private const val DEVICE_STATE_UNKNOWN = 0
private val DEVICE_STATE_CLOSED = DeviceState(
    DeviceState.Configuration.Builder(/* identifier= */ 1, "CLOSED")
        .setSystemProperties(setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY))
        .setPhysicalProperties(setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED))
        .build()
)
private val DEVICE_STATE_HALF_FOLDED = DeviceState(
    DeviceState.Configuration.Builder(/* identifier= */ 2, "HALF_FOLDED")
        .setSystemProperties(setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY))
        .setPhysicalProperties(setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN))
        .build()
)
private val DEVICE_STATE_OPEN = DeviceState(
    DeviceState.Configuration.Builder(/* identifier= */ 3, "OPEN")
        .setSystemProperties(setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY))
        .setPhysicalProperties(setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN))
        .build()
)
private val DEVICE_STATE_REAR_DISPLAY = DeviceState(
    DeviceState.Configuration.Builder(/* identifier= */ 4, "REAR_DISPLAY")
        .setSystemProperties(
            setOf(
                PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                PROPERTY_FEATURE_REAR_DISPLAY
            )
        )
        .setPhysicalProperties(setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED))
        .build()
)

@SmallTest
@RunWith(AndroidJUnit4::class)
class PosturesHelperTest {

    @get:Rule val expect: Expect = Expect.create()

    @Mock private lateinit var context: Context

    @Mock private lateinit var resources: Resources

    @Mock private lateinit var deviceStateManager: DeviceStateManager

    private lateinit var posturesHelper: PosturesHelper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(context.resources).thenReturn(resources)
        whenever(resources.getIntArray(R.array.config_foldedDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_CLOSED.identifier))
        whenever(resources.getIntArray(R.array.config_halfFoldedDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_HALF_FOLDED.identifier))
        whenever(resources.getIntArray(R.array.config_openDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_OPEN.identifier))
        whenever(resources.getIntArray(R.array.config_rearDisplayDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_REAR_DISPLAY.identifier))
        whenever(deviceStateManager.supportedDeviceStates).thenReturn(
            listOf(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_FOLDED,
                DEVICE_STATE_OPEN,
                DEVICE_STATE_REAR_DISPLAY
            )
        )

        posturesHelper = PosturesHelper(context, deviceStateManager)
    }

    @Test
    @RequiresFlagsDisabled(DeviceStateManagerFlags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun deviceStateToPosture_mapsCorrectly_overlayConfigurationValues() {
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_CLOSED.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_FOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_HALF_FOLDED.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_OPEN.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_UNFOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_REAR_DISPLAY.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_UNKNOWN))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_UNKNOWN)
    }

    @Test
    @RequiresFlagsEnabled(DeviceStateManagerFlags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun deviceStateToPosture_mapsCorrectly_deviceStateManager() {
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_CLOSED.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_FOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_HALF_FOLDED.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_OPEN.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_UNFOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_REAR_DISPLAY.identifier))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_UNKNOWN))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_UNKNOWN)
    }

    @Test
    @RequiresFlagsDisabled(DeviceStateManagerFlags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun postureToDeviceState_mapsCorrectly_overlayConfigurationValues() {
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_FOLDED))
            .isEqualTo(DEVICE_STATE_CLOSED.identifier)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED))
            .isEqualTo(DEVICE_STATE_HALF_FOLDED.identifier)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_UNFOLDED))
            .isEqualTo(DEVICE_STATE_OPEN.identifier)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY))
            .isEqualTo(DEVICE_STATE_REAR_DISPLAY.identifier)
        expect.that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_UNKNOWN)).isNull()
    }

    @Test
    @RequiresFlagsEnabled(DeviceStateManagerFlags.FLAG_DEVICE_STATE_PROPERTY_MIGRATION)
    fun postureToDeviceState_mapsCorrectly_deviceStateManager() {
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_FOLDED))
            .isEqualTo(DEVICE_STATE_CLOSED.identifier)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED))
            .isEqualTo(DEVICE_STATE_HALF_FOLDED.identifier)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_UNFOLDED))
            .isEqualTo(DEVICE_STATE_OPEN.identifier)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY))
            .isEqualTo(DEVICE_STATE_REAR_DISPLAY.identifier)
        expect.that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_UNKNOWN)).isNull()
    }
}
