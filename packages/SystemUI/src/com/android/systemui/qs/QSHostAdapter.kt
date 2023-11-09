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
    private val qsTileHost: QSTileHost,
    private val interactor: CurrentTilesInteractor,
    private val context: Context,
    private val tileServiceRequestControllerBuilder: TileServiceRequestController.Builder,
    @Application private val scope: CoroutineScope,
    flags: QSPipelineFlagsRepository,
    dumpManager: DumpManager,
) : QSHost {

    companion object {
        private const val TAG = "QSTileHost"
    }

    private val useNewHost = flags.pipelineEnabled

    @GuardedBy("callbacksMap") private val callbacksMap = mutableMapOf<QSHost.Callback, Job>()

    init {
        scope.launch { tileServiceRequestControllerBuilder.create(this@QSHostAdapter).init() }
        // Redirect dump to the correct host (needed for CTS tests)
        dumpManager.registerCriticalDumpable(TAG, if (useNewHost) interactor else qsTileHost)
    }

    override fun getTiles(): Collection<QSTile> {
        return if (useNewHost) {
            interactor.currentQSTiles
        } else {
            qsTileHost.getTiles()
        }
    }

    override fun getSpecs(): List<String> {
        return if (useNewHost) {
            interactor.currentTilesSpecs.map { it.spec }
        } else {
            qsTileHost.getSpecs()
        }
    }

    override fun removeTile(spec: String) {
        if (useNewHost) {
            interactor.removeTiles(listOf(TileSpec.create(spec)))
        } else {
            qsTileHost.removeTile(spec)
        }
    }

    override fun addCallback(callback: QSHost.Callback) {
        if (useNewHost) {
            val job = scope.launch { interactor.currentTiles.collect { callback.onTilesChanged() } }
            synchronized(callbacksMap) { callbacksMap.put(callback, job) }
        } else {
            qsTileHost.addCallback(callback)
        }
    }

    override fun removeCallback(callback: QSHost.Callback) {
        if (useNewHost) {
            synchronized(callbacksMap) { callbacksMap.remove(callback)?.cancel() }
        } else {
            qsTileHost.removeCallback(callback)
        }
    }

    override fun removeTiles(specs: Collection<String>) {
        if (useNewHost) {
            interactor.removeTiles(specs.map(TileSpec::create))
        } else {
            qsTileHost.removeTiles(specs)
        }
    }

    override fun removeTileByUser(component: ComponentName) {
        if (useNewHost) {
            interactor.removeTiles(listOf(TileSpec.create(component)))
        } else {
            qsTileHost.removeTileByUser(component)
        }
    }

    override fun addTile(spec: String, position: Int) {
        if (useNewHost) {
            interactor.addTile(TileSpec.create(spec), position)
        } else {
            qsTileHost.addTile(spec, position)
        }
    }

    override fun addTile(component: ComponentName, end: Boolean) {
        if (useNewHost) {
            interactor.addTile(TileSpec.create(component), if (end) POSITION_AT_END else 0)
        } else {
            qsTileHost.addTile(component, end)
        }
    }

    override fun changeTilesByUser(previousTiles: List<String>, newTiles: List<String>) {
        if (useNewHost) {
            interactor.setTiles(newTiles.map(TileSpec::create))
        } else {
            qsTileHost.changeTilesByUser(previousTiles, newTiles)
        }
    }

    override fun getContext(): Context {
        return if (useNewHost) {
            context
        } else {
            qsTileHost.context
        }
    }

    override fun getUserContext(): Context {
        return if (useNewHost) {
            interactor.userContext.value
        } else {
            qsTileHost.userContext
        }
    }

    override fun getUserId(): Int {
        return if (useNewHost) {
            interactor.userId.value
        } else {
            qsTileHost.userId
        }
    }

    override fun createTile(tileSpec: String): QSTile? {
        return qsTileHost.createTile(tileSpec)
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
