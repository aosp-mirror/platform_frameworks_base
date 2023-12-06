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

package com.android.systemui.qs.tiles.impl.uimodenight

import android.content.res.Configuration
import com.android.systemui.qs.tiles.impl.uimodenight.domain.model.UiModeNightTileModel
import java.time.LocalTime

object UiModeNightTileModelHelper {

    const val DEFAULT_NIGHT_MODE_CUSTOM_TYPE = 0
    val defaultCustomNightEnd: LocalTime = LocalTime.MAX
    val defaultCustomNightStart: LocalTime = LocalTime.MIN

    fun createModel(
        nightMode: Boolean = false,
        powerSave: Boolean = false,
        uiMode: Int = Configuration.UI_MODE_NIGHT_NO,
        isLocationEnabled: Boolean = true,
        nighModeCustomType: Int = DEFAULT_NIGHT_MODE_CUSTOM_TYPE,
        is24HourFormat: Boolean = false,
        customNightModeEnd: LocalTime = defaultCustomNightEnd,
        customNightModeStart: LocalTime = defaultCustomNightStart
    ): UiModeNightTileModel {
        return UiModeNightTileModel(
            uiMode,
            nightMode,
            powerSave,
            isLocationEnabled,
            nighModeCustomType,
            is24HourFormat,
            customNightModeEnd,
            customNightModeStart
        )
    }
}
