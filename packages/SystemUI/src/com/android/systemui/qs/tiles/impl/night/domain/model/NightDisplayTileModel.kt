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

package com.android.systemui.qs.tiles.impl.night.domain.model

import java.time.LocalTime

/** Data model for night display tile */
sealed interface NightDisplayTileModel {
    val isActivated: Boolean
    val isEnrolledInForcedNightDisplayAutoMode: Boolean
    data class AutoModeTwilight(
        override val isActivated: Boolean,
        override val isEnrolledInForcedNightDisplayAutoMode: Boolean,
        val isLocationEnabled: Boolean
    ) : NightDisplayTileModel
    data class AutoModeCustom(
        override val isActivated: Boolean,
        override val isEnrolledInForcedNightDisplayAutoMode: Boolean,
        val startTime: LocalTime?,
        val endTime: LocalTime?,
        val is24HourFormat: Boolean
    ) : NightDisplayTileModel
    data class AutoModeOff(
        override val isActivated: Boolean,
        override val isEnrolledInForcedNightDisplayAutoMode: Boolean
    ) : NightDisplayTileModel
}
