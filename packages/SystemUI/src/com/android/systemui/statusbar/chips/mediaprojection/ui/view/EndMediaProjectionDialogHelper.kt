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

package com.android.systemui.statusbar.chips.mediaprojection.ui.view

import android.app.ActivityManager
import android.content.pm.PackageManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject

/** Helper class for showing dialogs that let users end different types of media projections. */
@SysUISingleton
class EndMediaProjectionDialogHelper
@Inject
constructor(
    private val dialogFactory: SystemUIDialog.Factory,
    private val packageManager: PackageManager,
) {
    /** Creates a new [SystemUIDialog] using the given delegate. */
    fun createDialog(delegate: SystemUIDialog.Delegate): SystemUIDialog {
        return dialogFactory.create(delegate)
    }

    fun getAppName(state: MediaProjectionState.Projecting): CharSequence? {
        val specificTaskInfo =
            if (state is MediaProjectionState.Projecting.SingleTask) {
                state.task
            } else {
                null
            }
        return getAppName(specificTaskInfo)
    }

    fun getAppName(specificTaskInfo: ActivityManager.RunningTaskInfo?): CharSequence? {
        val packageName = specificTaskInfo?.baseIntent?.component?.packageName ?: return null
        return getAppName(packageName)
    }

    /**
     * Returns the human-readable application name for the given package, or null if it couldn't be
     * found for any reason.
     */
    fun getAppName(packageName: String): CharSequence? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.loadLabel(packageManager)
        } catch (e: PackageManager.NameNotFoundException) {
            // TODO(b/332662551): Log this error.
            null
        }
    }
}
