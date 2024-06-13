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

import android.annotation.StringRes
import android.content.Context
import android.content.pm.PackageManager
import android.text.Html
import android.text.Html.FROM_HTML_MODE_LEGACY
import android.text.TextUtils
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
    private val context: Context
) {
    /** Creates a new [SystemUIDialog] using the given delegate. */
    fun createDialog(delegate: SystemUIDialog.Delegate): SystemUIDialog {
        return dialogFactory.create(delegate)
    }

    /**
     * Returns the message to show in the dialog based on the specific media projection state.
     *
     * @param genericMessageResId a res ID for a more generic "end projection" message
     * @param specificAppMessageResId a res ID for an "end projection" message that also lets us
     *   specify which app is currently being projected.
     */
    fun getDialogMessage(
        state: MediaProjectionState.Projecting,
        @StringRes genericMessageResId: Int,
        @StringRes specificAppMessageResId: Int,
    ): CharSequence {
        when (state) {
            is MediaProjectionState.Projecting.EntireScreen ->
                return context.getString(genericMessageResId)
            is MediaProjectionState.Projecting.SingleTask -> {
                val packageName =
                    state.task.baseIntent.component?.packageName
                        ?: return context.getString(genericMessageResId)
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val appName = appInfo.loadLabel(packageManager)
                    return getSpecificAppMessageText(specificAppMessageResId, appName)
                } catch (e: PackageManager.NameNotFoundException) {
                    // TODO(b/332662551): Log this error.
                    return context.getString(genericMessageResId)
                }
            }
        }
    }

    private fun getSpecificAppMessageText(
        @StringRes specificAppMessageResId: Int,
        appName: CharSequence,
    ): CharSequence {
        // https://developer.android.com/guide/topics/resources/string-resource#StylingWithHTML
        val escapedAppName = TextUtils.htmlEncode(appName.toString())
        val text = context.getString(specificAppMessageResId, escapedAppName)
        return Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
    }
}
