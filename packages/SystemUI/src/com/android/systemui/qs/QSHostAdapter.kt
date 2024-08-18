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

package com.android.systemui.qs

import android.content.ComponentName
import android.content.Context
import androidx.annotation.GuardedBy
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.external.TileServiceRequestController
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Adapter to determine what real class to use for classes that depend on [QSHost].
 * * When [QSPipelineFlagsRepository.pipelineEnabled] is false, all calls will be routed to
 *   [QSTileHost].
 * * When [QSPipelineFlagsRepository.pipelineEnabled] is true, calls regarding the current set of
 *   tiles will be routed to [CurrentTilesInteractor]. Other calls (like [createTileView]) will
 *   still be routed to [QSTileHost].
 *
 * This routing also includes dumps.
 */
@SysUISingleton
class QSHostAdapter
@Inject
constructor(
    private val interactor: CurrentTilesInteractor,
    private val context: Context,
    private val tileServiceRequestControllerBuilder: TileServiceRequestController.Builder,
    @Application private val scope: CoroutineScope,
    dumpManager: DumpManager,
) : QSHost {

    companion object {
        private const val TAG = "QSTileHost"
    }

    @GuardedBy("callbacksMap") private val callbacksMap = mutableMapOf<QSHost.Callback, Job>()

    init {
        scope.launch { tileServiceRequestControllerBuilder.create(this@QSHostAdapter).init() }
        // Redirect dump to the correct host (needed for CTS tests)
        dumpManager.registerCriticalDumpable(TAG, interactor)
    }

    override fun getTiles(): Collection<QSTile> {
        return interactor.currentQSTiles
    }

    override fun getSpecs(): List<String> {
        return interactor.currentTilesSpecs.map { it.spec }
    }

    override fun removeTile(spec: String) {
        interactor.removeTiles(listOf(TileSpec.create(spec)))
    }

    override fun addCallback(callback: QSHost.Callback) {
        val job = scope.launch { interactor.currentTiles.collect { callback.onTilesChanged() } }
        synchronized(callbacksMap) { callbacksMap.put(callback, job) }
    }

    override fun removeCallback(callback: QSHost.Callback) {
        synchronized(callbacksMap) { callbacksMap.remove(callback)?.cancel() }
    }

    override fun removeTiles(specs: Collection<String>) {
        interactor.removeTiles(specs.map(TileSpec::create))
    }

    override fun removeTileByUser(component: ComponentName) {
        interactor.removeTiles(listOf(TileSpec.create(component)))
    }

    override fun addTile(spec: String, position: Int) {
        interactor.addTile(TileSpec.create(spec), position)
    }

    override fun addTile(component: ComponentName, end: Boolean) {
        interactor.addTile(TileSpec.create(component), if (end) POSITION_AT_END else 0)
    }

    override fun changeTilesByUser(previousTiles: List<String>, newTiles: List<String>) {
        interactor.setTiles(newTiles.map(TileSpec::create))
    }

    override fun getContext(): Context {
        return context
    }

    override fun getUserContext(): Context {
        return interactor.userContext.value
    }

    override fun getUserId(): Int {
        return interactor.userId.value
    }

    override fun createTile(tileSpec: String): QSTile? {
        return interactor.createTileSync(TileSpec.create(tileSpec))
    }

    override fun addTile(spec: String) {
        return addTile(spec, QSHost.POSITION_AT_END)
    }

    override fun addTile(tile: ComponentName) {
        return addTile(tile, false)
    }

    override fun indexOf(tileSpec: String): Int {
        return specs.indexOf(tileSpec)
    }
}
