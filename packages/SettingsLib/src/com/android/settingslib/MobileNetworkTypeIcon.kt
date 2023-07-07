/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * A specification for the icon displaying the mobile network type -- 4G, 5G, LTE, etc. (aka "RAT
 * icon" or "data type icon"). This is *not* the signal strength triangle.
 *
 * This is intended to eventually replace [SignalIcon.MobileIconGroup]. But for now,
 * [MobileNetworkTypeIcons] just reads from the existing set of [SignalIcon.MobileIconGroup]
 * instances to not duplicate data.
 *
 * TODO(b/238425913): Remove [SignalIcon.MobileIconGroup] and replace it with this class so that we
 *   don't need to fill in the superfluous fields from its parent [SignalIcon.IconGroup] class. Then
 *   this class can become either a sealed class or an enum with parameters.
 */
data class MobileNetworkTypeIcon(
    /** A human-readable name for this network type, used for logging. */
    val name: String,

    /** The resource ID of the icon drawable to use. */
    @DrawableRes val iconResId: Int,

    /** The resource ID of the content description to use. */
    @StringRes val contentDescriptionResId: Int,
)
