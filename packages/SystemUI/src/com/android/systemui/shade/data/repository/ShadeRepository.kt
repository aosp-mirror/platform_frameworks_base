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
 * limitations under the License
 */
package com.android.systemui.shade.data.repository

import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionListener
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.domain.model.ShadeModel
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

interface ShadeRepository {
    /** ShadeModel information regarding shade expansion events */
    val shadeModel: Flow<ShadeModel>
}

/** Business logic for shade interactions */
@SysUISingleton
class ShadeRepositoryImpl
@Inject
constructor(shadeExpansionStateManager: ShadeExpansionStateManager) : ShadeRepository {
    override val shadeModel: Flow<ShadeModel> =
        conflatedCallbackFlow {
                val callback =
                    object : ShadeExpansionListener {
                        override fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) {
                            // Don't propagate ShadeExpansionChangeEvent.dragDownPxAmount field.
                            // It is too noisy and produces extra events that consumers won't care
                            // about
                            val info =
                                ShadeModel(
                                    expansionAmount = event.fraction,
                                    isExpanded = event.expanded,
                                    isUserDragging = event.tracking
                                )
                            trySendWithFailureLogging(info, TAG, "updated shade expansion info")
                        }
                    }

                shadeExpansionStateManager.addExpansionListener(callback)
                trySendWithFailureLogging(ShadeModel(), TAG, "initial shade expansion info")

                awaitClose { shadeExpansionStateManager.removeExpansionListener(callback) }
            }
            .distinctUntilChanged()

    companion object {
        private const val TAG = "ShadeRepository"
    }
}
