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

package com.android.systemui.scene.data.repository

import android.os.RemoteException
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.UiBackground
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Source of truth for the visibility of various parts of the window root view. */
@SysUISingleton
class WindowRootViewVisibilityRepository
@Inject
constructor(
    private val statusBarService: IStatusBarService,
    @UiBackground private val uiBgExecutor: Executor,
) {
    private val _isLockscreenOrShadeVisible = MutableStateFlow(false)
    val isLockscreenOrShadeVisible: StateFlow<Boolean> = _isLockscreenOrShadeVisible.asStateFlow()

    fun setIsLockscreenOrShadeVisible(visible: Boolean) {
        _isLockscreenOrShadeVisible.value = visible
    }

    /**
     * Called when the lockscreen or shade has been shown and can be interacted with so that SysUI
     * can notify external services.
     */
    fun onLockscreenOrShadeInteractive(
        shouldClearNotificationEffects: Boolean,
        notificationCount: Int,
    ) {
        executeServiceCallOnUiBg {
            statusBarService.onPanelRevealed(shouldClearNotificationEffects, notificationCount)
        }
    }

    /**
     * Called when the lockscreen or shade no longer can be interactecd with so that SysUI can
     * notify external services.
     */
    fun onLockscreenOrShadeNotInteractive() {
        executeServiceCallOnUiBg { statusBarService.onPanelHidden() }
    }

    private fun executeServiceCallOnUiBg(runnable: () -> Unit) {
        uiBgExecutor.execute {
            try {
                runnable.invoke()
            } catch (ex: RemoteException) {
                // Won't fail unless the world has ended
            }
        }
    }
}
