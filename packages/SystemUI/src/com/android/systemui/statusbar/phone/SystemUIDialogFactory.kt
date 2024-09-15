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

package com.android.systemui.statusbar.phone

import android.content.Context
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.model.SysUiState
import com.android.systemui.util.Assert
import javax.inject.Inject

/** A factory to easily instantiate a [ComponentSystemUIDialog]. */
class SystemUIDialogFactory
@Inject
constructor(
    @Application val applicationContext: Context,
    private val dialogManager: SystemUIDialogManager,
    private val sysUiState: SysUiState,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
) {
    /**
     * Create a new [ComponentSystemUIDialog].
     *
     * Important: This should be called on the main thread and the returned dialog should be shown
     * on the main thread.
     *
     * @param context the [Context] in which the dialog will be constructed.
     * @param dismissOnDeviceLock whether the dialog should be automatically dismissed when the
     *   device is locked (true by default).
     */
    fun create(
        context: Context = this.applicationContext,
        theme: Int = SystemUIDialog.DEFAULT_THEME,
        dismissOnDeviceLock: Boolean = SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK,
        dialogDelegate: DialogDelegate<SystemUIDialog> = object : DialogDelegate<SystemUIDialog> {},
    ): ComponentSystemUIDialog {
        Assert.isMainThread()

        return ComponentSystemUIDialog(
            context,
            theme,
            dismissOnDeviceLock,
            dialogManager,
            sysUiState,
            broadcastDispatcher,
            dialogTransitionAnimator,
            dialogDelegate,
        )
    }
}
