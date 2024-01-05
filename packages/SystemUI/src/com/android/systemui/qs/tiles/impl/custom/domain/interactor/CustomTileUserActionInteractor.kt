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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.IWindowManager
import android.view.View
import android.view.WindowManager
import androidx.annotation.GuardedBy
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.settings.DisplayTracker
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

@QSTileScope
class CustomTileUserActionInteractor
@Inject
constructor(
    private val context: Context,
    private val tileSpec: TileSpec,
    private val qsTileLogger: QSTileLogger,
    private val windowManager: IWindowManager,
    private val displayTracker: DisplayTracker,
    private val qsTileIntentUserInputHandler: QSTileIntentUserInputHandler,
    @Background private val backgroundContext: CoroutineContext,
    private val serviceInteractor: CustomTileServiceInteractor,
) : QSTileUserActionInteractor<CustomTileDataModel> {

    private val token: IBinder = Binder()

    @GuardedBy("token") private var isTokenGranted: Boolean = false
    @GuardedBy("token") private var isShowingDialog: Boolean = false
    private val lastClickedView: AtomicReference<View> = AtomicReference<View>()

    override suspend fun handleInput(input: QSTileInput<CustomTileDataModel>) =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> click(action.view, data.tile.activityLaunchForClick)
                is QSTileUserAction.LongClick ->
                    longClick(user, action.view, data.componentName, data.tile.state)
            }
            qsTileLogger.logCustomTileUserActionDelivered(tileSpec)
        }

    private fun click(
        view: View?,
        activityLaunchForClick: PendingIntent?,
    ) {
        grantToken()
        try {
            // Bind active tile to deliver user action
            serviceInteractor.bindOnClick()
            if (activityLaunchForClick == null) {
                lastClickedView.set(view)
                serviceInteractor.onClick(token)
            } else {
                qsTileIntentUserInputHandler.handle(view, activityLaunchForClick)
            }
        } catch (e: RemoteException) {
            qsTileLogger.logError(tileSpec, "Failed to deliver click", e)
        }
    }

    fun revokeToken(ignoreShownDialog: Boolean) {
        synchronized(token) {
            if (isTokenGranted && (ignoreShownDialog || !isShowingDialog)) {
                try {
                    windowManager.removeWindowToken(token, displayTracker.defaultDisplayId)
                } catch (e: RemoteException) {
                    qsTileLogger.logError(tileSpec, "Failed to remove a window token", e)
                }
                isTokenGranted = false
            }
        }
    }

    fun setShowingDialog(isShowingDialog: Boolean) {
        synchronized(token) { this.isShowingDialog = isShowingDialog }
    }

    fun startActivityAndCollapse(pendingIntent: PendingIntent) {
        if (!pendingIntent.isActivity) {
            return
        }
        if (!isTokenGranted) {
            return
        }
        qsTileIntentUserInputHandler.handle(lastClickedView.getAndSet(null), pendingIntent)
    }

    fun clearLastClickedView() = lastClickedView.set(null)

    private fun grantToken() {
        synchronized(token) {
            if (!isTokenGranted) {
                try {
                    windowManager.addWindowToken(
                        token,
                        WindowManager.LayoutParams.TYPE_QS_DIALOG,
                        displayTracker.defaultDisplayId,
                        null /* options */
                    )
                } catch (e: RemoteException) {
                    qsTileLogger.logError(tileSpec, "Failed to grant a window token", e)
                }
                isTokenGranted = true
            }
        }
    }

    private suspend fun longClick(
        user: UserHandle,
        view: View?,
        componentName: ComponentName,
        state: Int
    ) {
        val resolvedIntent: Intent? =
            resolveIntent(
                    Intent(TileService.ACTION_QS_TILE_PREFERENCES).apply {
                        setPackage(componentName.packageName)
                    },
                    user,
                )
                ?.apply {
                    putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                    putExtra(TileService.EXTRA_STATE, state)
                }
        if (resolvedIntent == null) {
            qsTileIntentUserInputHandler.handle(
                view,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(
                        Uri.fromParts(IntentFilter.SCHEME_PACKAGE, componentName.packageName, null)
                    )
            )
        } else {
            qsTileIntentUserInputHandler.handle(view, resolvedIntent)
        }
    }

    /**
     * Returns an intent resolved by [android.content.pm.PackageManager.resolveActivityAsUser] or
     * null.
     */
    private suspend fun resolveIntent(intent: Intent, user: UserHandle): Intent? =
        withContext(backgroundContext) {
            val activityInfo =
                context.packageManager
                    .resolveActivityAsUser(intent, 0, user.identifier)
                    ?.activityInfo
            activityInfo ?: return@withContext null
            with(activityInfo) {
                Intent(TileService.ACTION_QS_TILE_PREFERENCES).apply {
                    setClassName(packageName, name)
                }
            }
        }
}
