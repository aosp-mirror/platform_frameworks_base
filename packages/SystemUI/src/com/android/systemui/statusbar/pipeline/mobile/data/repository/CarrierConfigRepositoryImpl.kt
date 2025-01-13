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

import android.content.IntentFilter
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.SparseArray
import androidx.annotation.VisibleForTesting
import androidx.core.util.getOrElse
import androidx.core.util.isEmpty
import androidx.core.util.keyIterator
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

@SysUISingleton
class CarrierConfigRepositoryImpl
@Inject
constructor(
    broadcastDispatcher: BroadcastDispatcher,
    private val carrierConfigManager: CarrierConfigManager?,
    dumpManager: DumpManager,
    logger: MobileInputLogger,
) : CarrierConfigRepository, Dumpable {
    private var isListening = false
    private val defaultConfig: PersistableBundle by lazy { CarrierConfigManager.getDefaultConfig() }
    // Used for logging the default config in the dumpsys
    private val defaultConfigForLogs: SystemUiCarrierConfig by lazy {
        SystemUiCarrierConfig(-1, defaultConfig)
    }

    private val configs = SparseArray<SystemUiCarrierConfig>()

    init {
        dumpManager.registerNormalDumpable(this)
    }

    @VisibleForTesting
    val carrierConfigStream: Flow<Pair<Int, PersistableBundle>> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                intent,
                _ ->
                intent.getIntExtra(
                    CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                )
            }
            .onEach { logger.logCarrierConfigChanged(it) }
            .filter { SubscriptionManager.isValidSubscriptionId(it) }
            .mapNotNull { subId ->
                val config = carrierConfigManager?.getConfigForSubId(subId)
                config?.let { subId to it }
            }

    override suspend fun startObservingCarrierConfigUpdates() {
        isListening = true
        carrierConfigStream.collect { updateCarrierConfig(it.first, it.second) }
    }

    /** Update or create the [SystemUiCarrierConfig] for subId with the override */
    private fun updateCarrierConfig(subId: Int, config: PersistableBundle) {
        val configToUpdate = getOrCreateConfigForSubId(subId)
        configToUpdate.processNewCarrierConfig(config)
    }

    override fun getOrCreateConfigForSubId(subId: Int): SystemUiCarrierConfig {
        return configs.getOrElse(subId) {
            val config = SystemUiCarrierConfig(subId, defaultConfig)
            val carrierConfig = carrierConfigManager?.getConfigForSubId(subId)
            if (carrierConfig != null) config.processNewCarrierConfig(carrierConfig)
            configs.put(subId, config)
            config
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("isListening: $isListening")
        if (configs.isEmpty()) {
            pw.println("no carrier configs loaded")
        } else {
            pw.println("Carrier configs by subId")
            configs.keyIterator().forEach {
                pw.println("  subId=$it")
                val config = configs.get(it)
                if (config == null) {
                    pw.println("    config=null (config was removed during dump)")
                } else {
                    pw.println("    config=${config.toStringConsideringDefaults()}")
                }
            }
            // Finally, print the default config
            pw.println("Default config:")
            pw.println("  $defaultConfigForLogs")
        }
    }
}
