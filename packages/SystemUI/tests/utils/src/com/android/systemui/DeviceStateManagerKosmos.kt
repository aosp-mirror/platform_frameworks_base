/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui

import android.hardware.devicestate.DeviceState
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN
import android.hardware.devicestate.DeviceState.PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP
import android.hardware.devicestate.DeviceState.PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE
import android.hardware.devicestate.DeviceStateManager
import com.android.systemui.kosmos.Kosmos
import org.mockito.kotlin.mock

val Kosmos.deviceStateManager by Kosmos.Fixture { mock<DeviceStateManager>() }

val Kosmos.defaultDeviceState by
    Kosmos.Fixture {
        DeviceState(DeviceState.Configuration.Builder(0 /* identifier */, "DEFAULT").build())
    }

val Kosmos.foldedDeviceStateList by
    Kosmos.Fixture {
        listOf(
            DeviceState(
                DeviceState.Configuration.Builder(0, "FOLDED_0")
                    .setSystemProperties(
                        setOf(
                            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                            PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP,
                        )
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)
                    )
                    .build()
            ),
            DeviceState(
                DeviceState.Configuration.Builder(1, "FOLDED_1")
                    .setSystemProperties(
                        setOf(
                            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                            PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP,
                        )
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)
                    )
                    .build()
            ),
            DeviceState(
                DeviceState.Configuration.Builder(2, "FOLDED_2")
                    .setSystemProperties(
                        setOf(
                            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                            PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP,
                        )
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)
                    )
                    .build()
            ),
        )
    }

val Kosmos.halfFoldedDeviceState by
    Kosmos.Fixture {
        DeviceState(
            DeviceState.Configuration.Builder(3 /* identifier */, "HALF_FOLDED")
                .setSystemProperties(
                    setOf(
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                        PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE,
                    )
                )
                .setPhysicalProperties(
                    setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN)
                )
                .build()
        )
    }

val Kosmos.unfoldedDeviceState by
    Kosmos.Fixture {
        DeviceState(
            DeviceState.Configuration.Builder(4 /* identifier */, "UNFOLDED")
                .setSystemProperties(
                    setOf(
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                        PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE,
                    )
                )
                .setPhysicalProperties(setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN))
                .build()
        )
    }

val Kosmos.rearDisplayDeviceState by
    Kosmos.Fixture {
        DeviceState(
            DeviceState.Configuration.Builder(5 /* identifier */, "REAR_DISPLAY")
                .setSystemProperties(
                    setOf(
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                        PROPERTY_FEATURE_REAR_DISPLAY,
                    )
                )
                .build()
        )
    }

val Kosmos.rearDisplayOuterDefaultDeviceState by
    Kosmos.Fixture {
        DeviceState(
            DeviceState.Configuration.Builder(5 /* identifier */, "REAR_DISPLAY")
                .setSystemProperties(
                    setOf(
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                        PROPERTY_FEATURE_REAR_DISPLAY,
                        PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT,
                    )
                )
                .build()
        )
    }

val Kosmos.unknownDeviceState by
    Kosmos.Fixture {
        DeviceState(DeviceState.Configuration.Builder(8 /* identifier */, "UNKNOWN").build())
    }
