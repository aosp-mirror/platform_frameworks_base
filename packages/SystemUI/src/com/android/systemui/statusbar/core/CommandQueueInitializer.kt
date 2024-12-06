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

package com.android.systemui.statusbar.core

import android.app.StatusBarManager
import android.content.Context
import android.os.Binder
import android.os.RemoteException
import android.view.Display
import android.view.WindowInsets
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.statusbar.RegisterStatusBarResult
import com.android.systemui.CoreStartable
import com.android.systemui.InitController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.navigationbar.NavigationBarController
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import dagger.Lazy
import javax.inject.Inject

@SysUISingleton
class CommandQueueInitializer
@Inject
constructor(
    private val context: Context,
    private val commandQueue: CommandQueue,
    private val commandQueueCallbacksLazy: Lazy<CommandQueue.Callbacks>,
    private val statusBarModeRepository: StatusBarModeRepositoryStore,
    private val initController: InitController,
    private val barService: IStatusBarService,
    private val navigationBarController: NavigationBarController,
) : CoreStartable {

    override fun start() {
        StatusBarConnectedDisplays.assertInNewMode()
        val resultPerDisplay: Map<String, RegisterStatusBarResult> =
            try {
                barService.registerStatusBarForAllDisplays(commandQueue)
            } catch (ex: RemoteException) {
                ex.rethrowFromSystemServer()
                return
            }

        resultPerDisplay[Display.DEFAULT_DISPLAY.toString()]?.let {
            createNavigationBar(it)
            // Set up the initial icon state
            val numIcons: Int = it.mIcons.size
            for (i in 0 until numIcons) {
                commandQueue.setIcon(it.mIcons.keyAt(i), it.mIcons.valueAt(i))
            }
        }

        for ((displayId, result) in resultPerDisplay.entries) {
            initializeStatusBarForDisplay(displayId.toInt(), result)
        }
    }

    private fun initializeStatusBarForDisplay(displayId: Int, result: RegisterStatusBarResult) {
        if ((result.mTransientBarTypes and WindowInsets.Type.statusBars()) != 0) {
            statusBarModeRepository.forDisplay(displayId).showTransient()
        }
        val commandQueueCallbacks = commandQueueCallbacksLazy.get()
        commandQueueCallbacks.onSystemBarAttributesChanged(
            displayId,
            result.mAppearance,
            result.mAppearanceRegions,
            result.mNavbarColorManagedByIme,
            result.mBehavior,
            result.mRequestedVisibleTypes,
            result.mPackageName,
            result.mLetterboxDetails,
        )

        // StatusBarManagerService has a back up of IME token and it's restored here.
        commandQueueCallbacks.setImeWindowStatus(
            displayId,
            result.mImeWindowVis,
            result.mImeBackDisposition,
            result.mShowImeSwitcher,
        )

        // set the initial view visibility
        val disabledFlags1 = result.mDisabledFlags1
        val disabledFlags2 = result.mDisabledFlags2
        initController.addPostInitTask {
            commandQueue.disable(displayId, disabledFlags1, disabledFlags2, /* animate= */ false)
            try {
                // NOTE(b/262059863): Force-update the disable flags after applying the flags
                // returned from registerStatusBar(). The result's disabled flags may be stale
                // if StatusBarManager's disabled flags are updated between registering the bar
                // and this handling this post-init task. We force an update in this case, and use a
                // new token to not conflict with any other disabled flags already requested by
                // SysUI
                val token = Binder()
                barService.disable(StatusBarManager.DISABLE_HOME, token, context.packageName)
                barService.disable(0, token, context.packageName)
            } catch (ex: RemoteException) {
                ex.rethrowFromSystemServer()
            }
        }
    }

    private fun createNavigationBar(result: RegisterStatusBarResult) {
        navigationBarController.createNavigationBars(/* includeDefaultDisplay= */ true, result)
    }
}
