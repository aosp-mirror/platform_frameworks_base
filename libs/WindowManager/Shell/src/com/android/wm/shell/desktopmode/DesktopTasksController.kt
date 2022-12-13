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

package com.android.wm.shell.desktopmode

import android.content.Context
import androidx.annotation.BinderThread
import com.android.internal.protolog.common.ProtoLog
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.ExternalInterfaceBinder
import com.android.wm.shell.common.RemoteCallable
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.annotations.ExternalThread
import com.android.wm.shell.common.annotations.ShellMainThread
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository.VisibleTasksListener
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.ShellSharedConstants
import com.android.wm.shell.transition.Transitions
import java.util.concurrent.Executor

/** Handles moving tasks in and out of desktop */
class DesktopTasksController(
    private val context: Context,
    shellInit: ShellInit,
    private val shellController: ShellController,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val transitions: Transitions,
    private val desktopModeTaskRepository: DesktopModeTaskRepository,
    @ShellMainThread private val mainExecutor: ShellExecutor
) : RemoteCallable<DesktopTasksController> {

    private val desktopMode: DesktopModeImpl

    init {
        desktopMode = DesktopModeImpl()
        if (DesktopModeStatus.isProto2Enabled()) {
            shellInit.addInitCallback({ onInit() }, this)
        }
    }

    private fun onInit() {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "Initialize DesktopTasksController")
        shellController.addExternalInterface(
            ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE,
            { createExternalInterface() },
            this
        )
    }

    override fun getContext(): Context {
        return context
    }

    override fun getRemoteCallExecutor(): ShellExecutor {
        return mainExecutor
    }

    /** Creates a new instance of the external interface to pass to another process. */
    private fun createExternalInterface(): ExternalInterfaceBinder {
        return IDesktopModeImpl(this)
    }

    /** Get connection interface between sysui and shell */
    fun asDesktopMode(): DesktopMode {
        return desktopMode
    }

    /**
     * Adds a listener to find out about changes in the visibility of freeform tasks.
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun addListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
        desktopModeTaskRepository.addVisibleTasksListener(listener, callbackExecutor)
    }

    /** The interface for calls from outside the shell, within the host process. */
    @ExternalThread
    private inner class DesktopModeImpl : DesktopMode {
        override fun addListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
            mainExecutor.execute {
                this@DesktopTasksController.addListener(listener, callbackExecutor)
            }
        }
    }

    /** The interface for calls from outside the host process. */
    @BinderThread
    private class IDesktopModeImpl(private var controller: DesktopTasksController?) :
        IDesktopMode.Stub(), ExternalInterfaceBinder {
        /** Invalidates this instance, preventing future calls from updating the controller. */
        override fun invalidate() {
            controller = null
        }

        override fun showDesktopApps() {
            // TODO
        }
    }
}
