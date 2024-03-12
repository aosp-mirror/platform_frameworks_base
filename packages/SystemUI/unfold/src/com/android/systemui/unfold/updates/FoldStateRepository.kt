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
package com.android.systemui.unfold.updates

import com.android.systemui.unfold.updates.FoldStateRepository.FoldUpdate
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Allows to subscribe to main events related to fold/unfold process such as hinge angle update,
 * start folding/unfolding, screen availability
 */
interface FoldStateRepository {
    /** Latest fold update, as described by [FoldStateProvider.FoldUpdate]. */
    val foldUpdate: Flow<FoldUpdate>

    /** Provides the hinge angle while the fold/unfold is in progress. */
    val hingeAngle: Flow<Float>

    enum class FoldUpdate {
        START_OPENING,
        START_CLOSING,
        FINISH_HALF_OPEN,
        FINISH_FULL_OPEN,
        FINISH_CLOSED;

        companion object {
            /** Maps the old [FoldStateProvider.FoldUpdate] to [FoldStateRepository.FoldUpdate]. */
            fun fromFoldUpdateId(@FoldStateProvider.FoldUpdate oldId: Int): FoldUpdate {
                return when (oldId) {
                    FOLD_UPDATE_START_OPENING -> START_OPENING
                    FOLD_UPDATE_START_CLOSING -> START_CLOSING
                    FOLD_UPDATE_FINISH_HALF_OPEN -> FINISH_HALF_OPEN
                    FOLD_UPDATE_FINISH_FULL_OPEN -> FINISH_FULL_OPEN
                    FOLD_UPDATE_FINISH_CLOSED -> FINISH_CLOSED
                    else -> error("FoldUpdateNotFound")
                }
            }
        }
    }
}

class FoldStateRepositoryImpl
@Inject
constructor(
    private val foldStateProvider: FoldStateProvider,
) : FoldStateRepository {

    override val hingeAngle: Flow<Float>
        get() =
            callbackFlow {
                    val callback =
                        object : FoldStateProvider.FoldUpdatesListener {
                            override fun onHingeAngleUpdate(angle: Float) {
                                trySend(angle)
                            }
                        }
                    foldStateProvider.addCallback(callback)
                    awaitClose { foldStateProvider.removeCallback(callback) }
                }
                .buffer(capacity = Channel.CONFLATED)

    override val foldUpdate: Flow<FoldUpdate>
        get() =
            callbackFlow {
                    val callback =
                        object : FoldStateProvider.FoldUpdatesListener {
                            override fun onFoldUpdate(update: Int) {
                                trySend(FoldUpdate.fromFoldUpdateId(update))
                            }
                        }
                    foldStateProvider.addCallback(callback)
                    awaitClose { foldStateProvider.removeCallback(callback) }
                }
                .buffer(capacity = Channel.CONFLATED)
}
