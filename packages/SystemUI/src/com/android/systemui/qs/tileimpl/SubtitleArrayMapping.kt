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
package com.android.systemui.qs.tileimpl

import com.android.systemui.res.R

/** Return the subtitle resource Id of the given tile. */
object SubtitleArrayMapping {
    private val subtitleIdsMap: HashMap<String, Int> = HashMap()

    init {
        subtitleIdsMap["internet"] = R.array.tile_states_internet
        subtitleIdsMap["wifi"] = R.array.tile_states_wifi
        subtitleIdsMap["cell"] = R.array.tile_states_cell
        subtitleIdsMap["battery"] = R.array.tile_states_battery
        subtitleIdsMap["dnd"] = R.array.tile_states_dnd
        subtitleIdsMap["flashlight"] = R.array.tile_states_flashlight
        subtitleIdsMap["rotation"] = R.array.tile_states_rotation
        subtitleIdsMap["bt"] = R.array.tile_states_bt
        subtitleIdsMap["airplane"] = R.array.tile_states_airplane
        subtitleIdsMap["location"] = R.array.tile_states_location
        subtitleIdsMap["hotspot"] = R.array.tile_states_hotspot
        subtitleIdsMap["inversion"] = R.array.tile_states_inversion
        subtitleIdsMap["saver"] = R.array.tile_states_saver
        subtitleIdsMap["dark"] = R.array.tile_states_dark
        subtitleIdsMap["work"] = R.array.tile_states_work
        subtitleIdsMap["cast"] = R.array.tile_states_cast
        subtitleIdsMap["night"] = R.array.tile_states_night
        subtitleIdsMap["screenrecord"] = R.array.tile_states_screenrecord
        subtitleIdsMap["record_issue"] = R.array.tile_states_record_issue
        subtitleIdsMap["reverse"] = R.array.tile_states_reverse
        subtitleIdsMap["reduce_brightness"] = R.array.tile_states_reduce_brightness
        subtitleIdsMap["cameratoggle"] = R.array.tile_states_cameratoggle
        subtitleIdsMap["mictoggle"] = R.array.tile_states_mictoggle
        subtitleIdsMap["controls"] = R.array.tile_states_controls
        subtitleIdsMap["wallet"] = R.array.tile_states_wallet
        subtitleIdsMap["qr_code_scanner"] = R.array.tile_states_qr_code_scanner
        subtitleIdsMap["alarm"] = R.array.tile_states_alarm
        subtitleIdsMap["onehanded"] = R.array.tile_states_onehanded
        subtitleIdsMap["color_correction"] = R.array.tile_states_color_correction
        subtitleIdsMap["dream"] = R.array.tile_states_dream
        subtitleIdsMap["font_scaling"] = R.array.tile_states_font_scaling
        subtitleIdsMap["hearing_devices"] = R.array.tile_states_hearing_devices
    }

    /** Get the subtitle resource id of the given tile */
    fun getSubtitleId(spec: String?): Int {
        return if (spec == null) {
            R.array.tile_states_default
        } else subtitleIdsMap[spec] ?: R.array.tile_states_default
    }
}
