/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.external

import android.app.Dialog
import android.app.IUriGrantsManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.DialogInterface
import android.graphics.drawable.Icon
import android.os.RemoteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.internal.statusbar.IAddTileResultCallback
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.QSHost
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.phone.SystemUIDialog
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import javax.inject.Inject

private const val TAG = "TileServiceRequestController"

/**
 * Controller to interface between [TileRequestDialog] and [QSHost].
 */
class TileServiceRequestController(
        private val qsHost: QSHost,
        private val commandQueue: CommandQueue,
        private val commandRegistry: CommandRegistry,
        private val eventLogger: TileRequestDialogEventLogger,
        private val iUriGrantsManager: IUriGrantsManager,
        private val dialogCreator: () -> TileRequestDialog = { TileRequestDialog(qsHost.context) }
) {

    companion object {
        internal const val ADD_TILE = StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
        internal const val DONT_ADD_TILE = StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED
        internal const val TILE_ALREADY_ADDED =
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        internal const val DISMISSED = StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED
    }

    private var dialogCanceller: ((String) -> Unit)? = null

    private val commandQueueCallback = object : CommandQueue.Callbacks {
        override fun requestAddTile(
            callingUid: Int,
            componentName: ComponentName,
            appName: CharSequence,
            label: CharSequence,
            icon: Icon,
            callback: IAddTileResultCallback
        ) {
            requestTileAdd(callingUid, componentName, appName, label, icon) {
                try {
                    callback.onTileRequest(it)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Couldn't respond to request", e)
                }
            }
        }

        override fun cancelRequestAddTile(packageName: String) {
            dialogCanceller?.invoke(packageName)
        }
    }

    fun init() {
        commandRegistry.registerCommand("tile-service-add") { TileServiceRequestCommand() }
        commandQueue.addCallback(commandQueueCallback)
    }

    fun destroy() {
        commandRegistry.unregisterCommand("tile-service-add")
        commandQueue.removeCallback(commandQueueCallback)
    }

    private fun addTile(componentName: ComponentName) {
        qsHost.addTile(componentName, true)
    }

    @VisibleForTesting
    internal fun requestTileAdd(
        callingUid: Int,
        componentName: ComponentName,
        appName: CharSequence,
        label: CharSequence,
        icon: Icon?,
        callback: Consumer<Int>
    ) {
        val instanceId = eventLogger.newInstanceId()
        val packageName = componentName.packageName
        if (isTileAlreadyAdded(componentName)) {
            callback.accept(TILE_ALREADY_ADDED)
            eventLogger.logTileAlreadyAdded(packageName, instanceId)
            return
        }
        val dialogResponse = SingleShotConsumer<Int> { response ->
            if (response == ADD_TILE) {
                addTile(componentName)
            }
            dialogCanceller = null
            eventLogger.logUserResponse(response, packageName, instanceId)
            callback.accept(response)
        }
        val tileData = TileRequestDialog.TileData(
                callingUid,
                appName,
                label,
                icon,
                componentName.packageName,
        )
        createDialog(tileData, dialogResponse).also { dialog ->
            dialogCanceller = {
                if (packageName == it) {
                    dialog.cancel()
                }
                dialogCanceller = null
            }
        }.show()
        eventLogger.logDialogShown(packageName, instanceId)
    }

    private fun createDialog(
        tileData: TileRequestDialog.TileData,
        responseHandler: SingleShotConsumer<Int>
    ): SystemUIDialog {
        val dialogClickListener = DialogInterface.OnClickListener { _, which ->
            if (which == Dialog.BUTTON_POSITIVE) {
                responseHandler.accept(ADD_TILE)
            } else {
                responseHandler.accept(DONT_ADD_TILE)
            }
        }
        return dialogCreator().apply {
            setTileData(tileData, iUriGrantsManager)
            setShowForAllUsers(true)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { responseHandler.accept(DISMISSED) }
            // We want this in case the dialog is dismissed without it being cancelled (for example
            // by going home or locking the device). We use a SingleShotConsumer so the response
            // is only sent once, with the first value.
            setOnDismissListener { responseHandler.accept(DISMISSED) }
            setPositiveButton(R.string.qs_tile_request_dialog_add, dialogClickListener)
            setNegativeButton(R.string.qs_tile_request_dialog_not_add, dialogClickListener)
        }
    }

    private fun isTileAlreadyAdded(componentName: ComponentName): Boolean {
        val spec = CustomTile.toSpec(componentName)
        return qsHost.indexOf(spec) != -1
    }

    inner class TileServiceRequestCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val componentName: ComponentName = ComponentName.unflattenFromString(args[0])
                    ?: run {
                        Log.w(TAG, "Malformed componentName ${args[0]}")
                        return
                    }
            requestTileAdd(0, componentName, args[1], args[2], null) {
                Log.d(TAG, "Response: $it")
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar tile-service-add " +
                    "<componentName> <appName> <label>")
        }
    }

    private class SingleShotConsumer<T>(private val consumer: Consumer<T>) : Consumer<T> {
        private val dispatched = AtomicBoolean(false)

        override fun accept(t: T) {
            if (dispatched.compareAndSet(false, true)) {
                consumer.accept(t)
            }
        }
    }

    @SysUISingleton
    class Builder @Inject constructor(
        private val commandQueue: CommandQueue,
        private val commandRegistry: CommandRegistry,
        private val iUriGrantsManager: IUriGrantsManager,
    ) {
        fun create(qsHost: QSHost): TileServiceRequestController {
            return TileServiceRequestController(
                    qsHost,
                    commandQueue,
                    commandRegistry,
                    TileRequestDialogEventLogger(),
                    iUriGrantsManager,
            )
        }
    }
}
