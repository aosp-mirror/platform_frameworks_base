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

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.res.Resources
import android.util.Log
import com.android.systemui.res.R
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec

object AutoAddableSettingList {

    /** Parses [R.array.config_quickSettingsAutoAdd] into a collection of [AutoAddableSetting]. */
    fun parseSettingsResource(
        resources: Resources,
        autoAddableSettingFactory: AutoAddableSetting.Factory,
    ): Iterable<AutoAddable> {
        val autoAddList = resources.getStringArray(R.array.config_quickSettingsAutoAdd)
        return autoAddList.mapNotNull {
            val elements = it.split(SETTING_SEPARATOR, limit = 2)
            if (elements.size == 2) {
                val setting = elements[0]
                val spec = elements[1]
                val tileSpec = TileSpec.create(spec)
                if (tileSpec == TileSpec.Invalid) {
                    Log.w(TAG, "Malformed item in array: $it")
                    null
                } else {
                    autoAddableSettingFactory.create(setting, TileSpec.create(spec))
                }
            } else {
                Log.w(TAG, "Malformed item in array: $it")
                null
            }
        }
    }

    private const val SETTING_SEPARATOR = ":"
    private const val TAG = "AutoAddableSettingList"
}
