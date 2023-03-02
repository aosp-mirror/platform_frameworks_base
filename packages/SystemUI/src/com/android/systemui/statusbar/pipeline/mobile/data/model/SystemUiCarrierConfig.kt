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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import android.os.PersistableBundle
import android.telephony.CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL
import android.telephony.CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents, for a given subscription ID, the set of keys about which SystemUI cares.
 *
 * Upon first creation, this config represents only the default configuration (see
 * [android.telephony.CarrierConfigManager.getDefaultConfig]).
 *
 * Upon request (see
 * [com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository]), an
 * instance of this class may be created for a given subscription Id, and will default to
 * representing the default carrier configuration. However, once a carrier config is received for
 * this [subId], all fields will reflect those in the received config, using [PersistableBundle]'s
 * default of false for any config that is not present in the override.
 *
 * To keep things relatively simple, this class defines a wrapper around each config key which
 * exposes a StateFlow<Boolean> for each config we care about. It also tracks whether or not it is
 * using the default config for logging purposes.
 *
 * NOTE to add new keys to be tracked:
 * 1. Define a new `private val` wrapping the key using [BooleanCarrierConfig]
 * 2. Define a public `val` exposing the wrapped flow using [BooleanCarrierConfig.config]
 * 3. Add the new [BooleanCarrierConfig] to the list of tracked configs, so they are properly
 *    updated when a new carrier config comes down
 */
class SystemUiCarrierConfig
internal constructor(
    val subId: Int,
    defaultConfig: PersistableBundle,
) {
    @VisibleForTesting
    var isUsingDefault = true
        private set

    private val inflateSignalStrength =
        BooleanCarrierConfig(KEY_INFLATE_SIGNAL_STRENGTH_BOOL, defaultConfig)
    /** Flow tracking the [KEY_INFLATE_SIGNAL_STRENGTH_BOOL] carrier config */
    val shouldInflateSignalStrength: StateFlow<Boolean> = inflateSignalStrength.config

    private val showOperatorName =
        BooleanCarrierConfig(KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL, defaultConfig)
    /** Flow tracking the [KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL] config */
    val showOperatorNameInStatusBar: StateFlow<Boolean> = showOperatorName.config

    private val trackedConfigs =
        listOf(
            inflateSignalStrength,
            showOperatorName,
        )

    /** Ingest a new carrier config, and switch all of the tracked keys over to the new values */
    fun processNewCarrierConfig(config: PersistableBundle) {
        isUsingDefault = false
        trackedConfigs.forEach { it.update(config) }
    }

    /** For dumpsys, shortcut if we haven't overridden any keys */
    fun toStringConsideringDefaults(): String {
        return if (isUsingDefault) {
            "using defaults"
        } else {
            trackedConfigs.joinToString { it.toString() }
        }
    }

    override fun toString(): String = trackedConfigs.joinToString { it.toString() }
}

/** Extracts [key] from the carrier config, and stores it in a flow */
private class BooleanCarrierConfig(
    val key: String,
    defaultConfig: PersistableBundle,
) {
    private val _configValue = MutableStateFlow(defaultConfig.getBoolean(key))
    val config = _configValue.asStateFlow()

    fun update(config: PersistableBundle) {
        _configValue.value = config.getBoolean(key)
    }

    override fun toString(): String {
        return "$key=${config.value}"
    }
}
