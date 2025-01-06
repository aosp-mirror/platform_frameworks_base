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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.os.PersistableBundle
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig

class FakeCarrierConfigRepository : CarrierConfigRepository {
    override suspend fun startObservingCarrierConfigUpdates() {}

    val configsById = mutableMapOf<Int, SystemUiCarrierConfig>()

    override fun getOrCreateConfigForSubId(subId: Int): SystemUiCarrierConfig =
        configsById.getOrPut(subId) { SystemUiCarrierConfig(subId, createDefaultTestConfig()) }
}

val CarrierConfigRepository.fake
    get() = this as FakeCarrierConfigRepository

fun createDefaultTestConfig() =
    PersistableBundle().also {
        it.putBoolean(
            android.telephony.CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL,
            false,
        )
        it.putBoolean(
            android.telephony.CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL,
            false,
        )
        it.putBoolean(android.telephony.CarrierConfigManager.KEY_SHOW_5G_SLICE_ICON_BOOL, true)
    }

/** Override the default config with the given (key, value) pair */
fun configWithOverride(key: String, override: Boolean): PersistableBundle =
    createDefaultTestConfig().also { it.putBoolean(key, override) }

/** Override any number of configs from the default */
fun configWithOverrides(vararg overrides: Pair<String, Boolean>) =
    createDefaultTestConfig().also { config ->
        overrides.forEach { (key, value) -> config.putBoolean(key, value) }
    }
