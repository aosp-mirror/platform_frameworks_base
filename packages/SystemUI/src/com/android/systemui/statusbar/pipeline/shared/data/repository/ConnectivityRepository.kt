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

package com.android.systemui.statusbar.pipeline.shared.data.repository

import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.pipeline.shared.ConnectivityInputLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlots
import com.android.systemui.tuner.TunerService
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Provides data related to the connectivity state that needs to be shared across multiple different
 * types of connectivity (wifi, mobile, ethernet, etc.)
 */
interface ConnectivityRepository {
    /** Observable for the current set of connectivity icons that should be force-hidden. */
    val forceHiddenSlots: StateFlow<Set<ConnectivitySlot>>
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ConnectivityRepositoryImpl
@Inject
constructor(
    private val connectivitySlots: ConnectivitySlots,
    context: Context,
    dumpManager: DumpManager,
    logger: ConnectivityInputLogger,
    @Application scope: CoroutineScope,
    tunerService: TunerService,
) : ConnectivityRepository, Dumpable {
    init {
        dumpManager.registerDumpable("ConnectivityRepository", this)
    }

    // The default set of hidden icons to use if we don't get any from [TunerService].
    private val defaultHiddenIcons: Set<ConnectivitySlot> =
        context.resources
            .getStringArray(DEFAULT_HIDDEN_ICONS_RESOURCE)
            .asList()
            .toSlotSet(connectivitySlots)

    override val forceHiddenSlots: StateFlow<Set<ConnectivitySlot>> =
        conflatedCallbackFlow {
                val callback =
                    object : TunerService.Tunable {
                        override fun onTuningChanged(key: String, newHideList: String?) {
                            if (key != HIDDEN_ICONS_TUNABLE_KEY) {
                                return
                            }
                            logger.logTuningChanged(newHideList)

                            val outputList =
                                newHideList?.split(",")?.toSlotSet(connectivitySlots)
                                    ?: defaultHiddenIcons
                            trySend(outputList)
                        }
                    }
                tunerService.addTunable(callback, HIDDEN_ICONS_TUNABLE_KEY)

                awaitClose { tunerService.removeTunable(callback) }
            }
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = defaultHiddenIcons
            )

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply { println("defaultHiddenIcons=$defaultHiddenIcons") }
    }

    companion object {
        @VisibleForTesting
        internal const val HIDDEN_ICONS_TUNABLE_KEY = StatusBarIconController.ICON_HIDE_LIST
        @VisibleForTesting
        @ArrayRes
        internal val DEFAULT_HIDDEN_ICONS_RESOURCE = R.array.config_statusBarIconsToExclude

        /** Converts a list of string slot names to a set of [ConnectivitySlot] instances. */
        private fun List<String>.toSlotSet(
            connectivitySlots: ConnectivitySlots
        ): Set<ConnectivitySlot> {
            return this.filter { it.isNotBlank() }
                .mapNotNull { connectivitySlots.getSlotFromName(it) }
                .toSet()
        }
    }
}
