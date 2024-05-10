/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.interactor

import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.qs.pipeline.data.repository.AutoAddRepository
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.util.asIndenting
import com.android.systemui.util.indentIfPossible
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * Collects the signals coming from all registered [AutoAddable] and adds/removes tiles accordingly.
 */
@SysUISingleton
class AutoAddInteractor
@Inject
constructor(
    private val autoAddables: Set<@JvmSuppressWildcards AutoAddable>,
    private val repository: AutoAddRepository,
    private val dumpManager: DumpManager,
    private val qsPipelineLogger: QSPipelineLogger,
    @Application private val scope: CoroutineScope,
) : Dumpable {

    private val initialized = AtomicBoolean(false)

    /** Start collection of signals following the user from [currentTilesInteractor]. */
    fun init(currentTilesInteractor: CurrentTilesInteractor) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        dumpManager.registerNormalDumpable(TAG, this)

        scope.launch {
            currentTilesInteractor.userId.collectLatest { userId ->
                coroutineScope {
                    val previouslyAdded = repository.autoAddedTiles(userId).stateIn(this)

                    autoAddables
                        .map { addable ->
                            val autoAddSignal = addable.autoAddSignal(userId)
                            when (val lifecycle = addable.autoAddTracking) {
                                is AutoAddTracking.Always -> autoAddSignal
                                is AutoAddTracking.Disabled -> emptyFlow()
                                is AutoAddTracking.IfNotAdded -> {
                                    if (lifecycle.spec !in previouslyAdded.value) {
                                        autoAddSignal.filterIsInstance<AutoAddSignal.Add>().take(1)
                                    } else {
                                        emptyFlow()
                                    }
                                }
                            }
                        }
                        .merge()
                        .collect { signal ->
                            when (signal) {
                                is AutoAddSignal.Add -> {
                                    if (signal.spec !in previouslyAdded.value) {
                                        currentTilesInteractor.addTile(signal.spec, signal.position)
                                        qsPipelineLogger.logTileAutoAdded(
                                            userId,
                                            signal.spec,
                                            signal.position
                                        )
                                        repository.markTileAdded(userId, signal.spec)
                                    }
                                }
                                is AutoAddSignal.Remove -> {
                                    currentTilesInteractor.removeTiles(setOf(signal.spec))
                                    qsPipelineLogger.logTileAutoRemoved(userId, signal.spec)
                                    repository.unmarkTileAdded(userId, signal.spec)
                                }
                                is AutoAddSignal.RemoveTracking -> {
                                    qsPipelineLogger.logTileUnmarked(userId, signal.spec)
                                    repository.unmarkTileAdded(userId, signal.spec)
                                }
                            }
                        }
                }
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        with(pw.asIndenting()) {
            println("AutoAddables:")
            indentIfPossible { autoAddables.forEach { println(it.description) } }
        }
    }

    companion object {
        private const val TAG = "AutoAddInteractor"
    }
}
