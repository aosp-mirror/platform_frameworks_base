/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.mediaprojection.permission

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.CallSuper
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.DialogDelegate

/** Base permission dialog for screen share and recording */
abstract class BaseMediaProjectionPermissionDialogDelegate<T : AlertDialog>(
    private val screenShareOptions: List<ScreenShareOption>,
    private val appName: String?,
    private val hostUid: Int,
    private val mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    @DrawableRes private val dialogIconDrawable: Int? = null,
    @ColorRes private val dialogIconTint: Int? = null,
    @ScreenShareMode val defaultSelectedMode: Int = screenShareOptions.first().mode,
) : DialogDelegate<T> {
    private lateinit var dialogTitle: TextView
    private lateinit var cancelButton: TextView
    private lateinit var screenShareModeSpinner: Spinner
    protected lateinit var dialog: AlertDialog
    protected lateinit var viewBinder: BaseMediaProjectionPermissionViewBinder

    /**
     * Create the view binder for the permission dialog, this can be override by child classes to
     * support a different type of view binder
     */
    open fun createViewBinder(): BaseMediaProjectionPermissionViewBinder {
        return BaseMediaProjectionPermissionViewBinder(
            screenShareOptions,
            appName,
            hostUid,
            mediaProjectionMetricsLogger,
            defaultSelectedMode,
        )
    }

    @CallSuper
    override fun onStop(dialog: T) {
        viewBinder.unbind()
    }

    @CallSuper
    override fun onCreate(dialog: T, savedInstanceState: Bundle?) {
        this.dialog = dialog
        dialog.window?.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.setContentView(R.layout.screen_share_dialog)
        dialogTitle = dialog.requireViewById(R.id.screen_share_dialog_title)
        cancelButton = dialog.requireViewById(android.R.id.button2)
        updateIcon()
        if (!::viewBinder.isInitialized) {
            viewBinder = createViewBinder()
        }
        viewBinder.bind(dialog.requireViewById(R.id.screen_share_permission_dialog))
    }

    private fun updateIcon() {
        val icon = dialog.requireViewById<ImageView>(R.id.screen_share_dialog_icon)
        if (dialogIconTint != null) {
            icon.setColorFilter(dialog.context.getColor(dialogIconTint))
        }
        if (dialogIconDrawable != null) {
            icon.setImageDrawable(dialog.context.getDrawable(dialogIconDrawable))
        }
    }

    fun getSelectedScreenShareOption(): ScreenShareOption {
        return viewBinder.selectedScreenShareOption
    }

    /** Protected methods for the text updates & functionality */
    protected fun setDialogTitle(@StringRes stringId: Int) {
        val title = dialog.context.getString(stringId, appName)
        dialogTitle.text = title
    }

    protected fun setStartButtonOnClickListener(listener: View.OnClickListener?) {
        viewBinder.setStartButtonOnClickListener(listener)
    }

    protected fun setCancelButtonOnClickListener(listener: View.OnClickListener?) {
        cancelButton.setOnClickListener(listener)
    }
}
