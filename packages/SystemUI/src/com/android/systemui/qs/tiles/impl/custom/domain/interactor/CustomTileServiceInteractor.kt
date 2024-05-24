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

package com.android.systemui.qs.tiles.impl.custom.domain.interactor

import android.app.PendingIntent
import android.content.ComponentName
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.service.quicksettings.IQSTileService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.external.CustomTileInterface
import com.android.systemui.qs.external.TileServiceManager
import com.android.systemui.qs.external.TileServices
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import com.android.systemui.user.data.repository.UserRepository
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Communicates with [TileService] via [TileServiceManager] and [IQSTileService]. This interactor is
 * also responsible for the binding to the [TileService].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@QSTileScope
class CustomTileServiceInteractor
@Inject
constructor(
    private val tileSpec: TileSpec.CustomTileSpec,
    private val activityStarter: ActivityStarter,
    private val userActionInteractor: Lazy<CustomTileUserActionInteractor>,
    private val customTileInteractor: CustomTileInteractor,
    userRepository: UserRepository,
    private val qsTileLogger: QSTileLogger,
    private val tileServices: TileServices,
    @QSTileScope private val tileScope: CoroutineScope,
) {

    private val tileReceivingInterface = ReceivingInterface()
    private var tileServiceManager: TileServiceManager? = null
    private val tileServiceInterface: IQSTileService
        get() = getTileServiceManager().tileService

    private var currentUser: UserHandle = userRepository.getSelectedUserInfo().userHandle
    private var destructionJob: Job? = null

    val callingAppIds: Flow<Int>
        get() = tileReceivingInterface.mutableCallingAppIds
    val refreshEvents: Flow<Unit>
        get() = tileReceivingInterface.mutableRefreshEvents

    /** Clears all pending binding for an active tile and binds not active one. */
    suspend fun bindOnStart() {
        try {
            with(getTileServiceManager()) {
                if (customTileInteractor.isTileActive()) {
                    clearPendingBind()
                } else {
                    setBindRequested(true)
                    tileServiceInterface.onStartListening()
                }
            }
        } catch (e: RemoteException) {
            qsTileLogger.logError(tileSpec, "Binding to the service failed", e)
        }
    }

    /** Binds active tile WITHOUT CLEARING pending binds. */
    suspend fun bindOnClick() {
        try {
            with(getTileServiceManager()) {
                if (customTileInteractor.isTileActive()) {
                    setBindRequested(true)
                    tileServiceInterface.onStartListening()
                }
            }
        } catch (e: RemoteException) {
            qsTileLogger.logError(tileSpec, "Binding to the service on click failed", e)
        }
    }

    /** Releases resources held by the binding and prepares the interactor to be collected */
    fun unbind() {
        try {
            with(userActionInteractor.get()) {
                clearLastClickedView()
                tileServiceInterface.onStopListening()
                revokeToken(false)
                setShowingDialog(false)
            }
            getTileServiceManager().setBindRequested(false)
        } catch (e: RemoteException) {
            qsTileLogger.logError(tileSpec, "Unbinding failed", e)
        }
    }

    /**
     * Checks if [TileServiceManager] has a pending [android.service.quicksettings.TileService]
     * bind.
     */
    fun hasPendingBind(): Boolean = getTileServiceManager().hasPendingBind()

    /** Sets a [user] for the custom tile to use. User change triggers service rebinding. */
    fun setUser(user: UserHandle) {
        if (user == currentUser) {
            return
        }
        currentUser = user
        destructionJob?.cancel()

        tileServiceManager = null
    }

    /** Sends click event to [TileService] using [IQSTileService.onClick]. */
    fun onClick(token: IBinder) {
        tileServiceInterface.onClick(token)
    }

    private fun getTileServiceManager(): TileServiceManager =
        synchronized(tileServices) {
            if (tileServiceManager == null) {
                tileServices
                    .getTileWrapper(tileReceivingInterface)
                    .also { destructionJob = createDestructionJob() }
                    .also { tileServiceManager = it }
            } else {
                tileServiceManager!!
            }
        }

    /**
     * This job used to free the resources when the [QSTileScope] coroutine scope gets cancelled by
     * the View Model.
     */
    private fun createDestructionJob(): Job =
        tileScope.launch {
            produce<Unit> {
                awaitClose {
                    userActionInteractor.get().revokeToken(true)
                    tileServices.freeService(tileReceivingInterface, getTileServiceManager())
                    destructionJob = null
                }
            }
        }

    private inner class ReceivingInterface : CustomTileInterface {

        override val user: Int
            get() = currentUser.identifier
        override val qsTile: Tile
            get() = customTileInteractor.getTile(currentUser)
        override val component: ComponentName = tileSpec.componentName

        val mutableCallingAppIds = MutableStateFlow(Process.INVALID_UID)
        val mutableRefreshEvents = MutableSharedFlow<Unit>()

        override fun getTileSpec(): String = tileSpec.spec

        override fun refreshState() {
            tileScope.launch { mutableRefreshEvents.emit(Unit) }
        }

        override fun updateTileState(tile: Tile, uid: Int) {
            customTileInteractor.updateTile(tile)
            mutableCallingAppIds.tryEmit(uid)
        }

        override fun onDialogShown() {
            userActionInteractor.get().setShowingDialog(true)
        }

        override fun onDialogHidden() =
            with(userActionInteractor.get()) {
                setShowingDialog(false)
                revokeToken(true)
            }

        override fun startActivityAndCollapse(pendingIntent: PendingIntent) {
            userActionInteractor.get().startActivityAndCollapse(pendingIntent)
        }

        override fun startUnlockAndRun() {
            activityStarter.postQSRunnableDismissingKeyguard {
                tileServiceInterface.onUnlockComplete()
            }
        }
    }
}
