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

package com.android.wm.shell.apptoweb

import android.app.ActivityManager.RunningTaskInfo
import android.app.TaskInfo
import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
import android.view.WindowlessWindowManager
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.window.TaskConstants
import com.android.wm.shell.R
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewHostViewContainer
import java.util.function.Supplier


/**
 * Window manager for the open by default settings dialog
 */
internal class OpenByDefaultDialog(
    private val context: Context,
    private val taskInfo: TaskInfo,
    private val taskSurface: SurfaceControl,
    private val displayController: DisplayController,
    private val surfaceControlTransactionSupplier: Supplier<SurfaceControl.Transaction>,
    private val listener: DialogLifecycleListener,
    appIconBitmap: Bitmap?,
    appName: CharSequence?
) {
    private lateinit var dialog: OpenByDefaultDialogView
    private lateinit var viewHost: SurfaceControlViewHost
    private lateinit var dialogSurfaceControl: SurfaceControl
    private var dialogContainer: AdditionalViewHostViewContainer? = null
    private lateinit var appIconView: ImageView
    private lateinit var appNameView: TextView

    private lateinit var openInAppButton: RadioButton
    private lateinit var openInBrowserButton: RadioButton

    private val domainVerificationManager =
        context.getSystemService(DomainVerificationManager::class.java)!!
    private val packageName = taskInfo.baseActivity?.packageName!!


    init {
        createDialog()
        initializeRadioButtons()
        bindAppInfo(appIconBitmap, appName)
    }

    /** Creates an open by default settings dialog. */
    fun createDialog() {
        val t = SurfaceControl.Transaction()
        val taskBounds = taskInfo.configuration.windowConfiguration.bounds

        dialog = LayoutInflater.from(context)
            .inflate(
                R.layout.open_by_default_settings_dialog,
                null /* root */
            ) as OpenByDefaultDialogView
        appIconView = dialog.requireViewById(R.id.application_icon)
        appNameView = dialog.requireViewById(R.id.application_name)

        val display = displayController.getDisplay(taskInfo.displayId)
        val builder: SurfaceControl.Builder = SurfaceControl.Builder()
        dialogSurfaceControl = builder
            .setName("Open by Default Dialog of Task=" + taskInfo.taskId)
            .setContainerLayer()
            .setParent(taskSurface)
            .setCallsite("OpenByDefaultDialog#createDialog")
            .build()
        t.setPosition(dialogSurfaceControl, 0f, 0f)
            .setWindowCrop(dialogSurfaceControl, taskBounds.width(), taskBounds.height())
            .setLayer(dialogSurfaceControl, TaskConstants.TASK_CHILD_LAYER_SETTINGS_DIALOG)
            .show(dialogSurfaceControl)
        val lp = WindowManager.LayoutParams(
            taskBounds.width(),
            taskBounds.height(),
            TYPE_APPLICATION_PANEL,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT)
        lp.title = "Open by default settings dialog of task=" + taskInfo.taskId
        lp.setTrustedOverlay()
        val windowManager = WindowlessWindowManager(
            taskInfo.configuration,
            dialogSurfaceControl, null /* hostInputToken */
        )
        viewHost = SurfaceControlViewHost(context, display, windowManager, "Dialog").apply {
            setView(dialog, lp)
            rootSurfaceControl.applyTransactionOnDraw(t)
        }
        dialogContainer = AdditionalViewHostViewContainer(
            dialogSurfaceControl, viewHost, surfaceControlTransactionSupplier)

        dialog.setDismissOnClickListener{
            closeMenu()
        }

        dialog.setConfirmButtonClickListener {
            setDefaultLinkHandlingSetting()
            closeMenu()
        }

        listener.onDialogCreated()
    }

    private fun initializeRadioButtons() {
        openInAppButton = dialog.requireViewById(R.id.open_in_app_button)
        openInBrowserButton = dialog.requireViewById(R.id.open_in_browser_button)

        val userState =
            getDomainVerificationUserState(domainVerificationManager, packageName) ?: return
        val openInApp = userState.isLinkHandlingAllowed
        openInAppButton.isChecked = openInApp
        openInBrowserButton.isChecked = !openInApp
    }

    private fun setDefaultLinkHandlingSetting() {
        domainVerificationManager.setDomainVerificationLinkHandlingAllowed(
            packageName, openInAppButton.isChecked)
    }

    private fun closeMenu() {
        dialogContainer?.releaseView()
        dialogContainer = null
        listener.onDialogDismissed()
    }

     private fun bindAppInfo(
        appIconBitmap: Bitmap?,
        appName: CharSequence?
    ) {
        appIconView.setImageBitmap(appIconBitmap)
        appNameView.text = appName
    }

    /**
     * Relayout the dialog to the new task bounds.
     */
    fun relayout(
        taskInfo: RunningTaskInfo,
    ) {
        val t = surfaceControlTransactionSupplier.get()
        val taskBounds = taskInfo.configuration.windowConfiguration.bounds
        t.setWindowCrop(dialogSurfaceControl, taskBounds.width(), taskBounds.height())
        viewHost.rootSurfaceControl.applyTransactionOnDraw(t)
        viewHost.relayout(taskBounds.width(), taskBounds.height())
    }

    /**
     * Defines interface for classes that can listen to lifecycle events of open by default settings
     * dialog.
     */
    interface DialogLifecycleListener {
        /** Called when open by default dialog view has been created. */
        fun onDialogCreated()

        /** Called when open by default dialog view has been released. */
        fun onDialogDismissed()
    }
}
