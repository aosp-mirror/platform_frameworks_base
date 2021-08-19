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
import android.content.ComponentName
import android.content.DialogInterface
import android.graphics.drawable.Icon
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.QSTileHost
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.R
import java.io.PrintWriter
import java.util.function.Consumer
import javax.inject.Inject

private const val TAG = "TileServiceRequestController"

/**
 * Controller to interface between [TileRequestDialog] and [QSTileHost].
 */
class TileServiceRequestController constructor(
    private val qsTileHost: QSTileHost,
    private val commandRegistry: CommandRegistry,
    private val dialogCreator: () -> TileRequestDialog = { TileRequestDialog(qsTileHost.context) }
) {

    companion object {
        // Temporary return values while there's no API
        internal const val ADD_TILE = 0
        internal const val DONT_ADD_TILE = 1
        internal const val TILE_ALREADY_ADDED = 2
        internal const val DISMISSED = 3
    }

    fun init() {
        commandRegistry.registerCommand("tile-service-add") { TileServiceRequestCommand() }
    }

    fun destroy() {
        commandRegistry.unregisterCommand("tile-service-add")
    }

    private fun addTile(componentName: ComponentName) {
        qsTileHost.addTile(componentName, true)
    }

    @VisibleForTesting
    internal fun requestTileAdd(
        componentName: ComponentName,
        appName: CharSequence,
        label: CharSequence,
        icon: Icon?,
        callback: Consumer<Int>
    ) {
        if (isTileAlreadyAdded(componentName)) {
            callback.accept(TILE_ALREADY_ADDED)
            return
        }
        val dialogResponse = object : Consumer<Int> {
            override fun accept(response: Int) {
                if (response == ADD_TILE) {
                    addTile(componentName)
                }
                callback.accept(response)
            }
        }
        val tileData = TileRequestDialog.TileData(appName, label, icon)
        createDialog(tileData, dialogResponse).show()
    }

    private fun createDialog(
        tileData: TileRequestDialog.TileData,
        responseHandler: Consumer<Int>
    ): SystemUIDialog {
        val dialogClickListener = DialogInterface.OnClickListener { _, which ->
            if (which == Dialog.BUTTON_POSITIVE) {
                responseHandler.accept(ADD_TILE)
            } else {
                responseHandler.accept(DONT_ADD_TILE)
            }
        }
        return dialogCreator().apply {
            setTileData(tileData)
            setShowForAllUsers(true)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { responseHandler.accept(DISMISSED) }
            setPositiveButton(R.string.qs_tile_request_dialog_add, dialogClickListener)
            setNegativeButton(R.string.qs_tile_request_dialog_not_add, dialogClickListener)
        }
    }

    private fun isTileAlreadyAdded(componentName: ComponentName): Boolean {
        val spec = CustomTile.toSpec(componentName)
        return qsTileHost.indexOf(spec) != -1
    }

    inner class TileServiceRequestCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val componentName: ComponentName = ComponentName.unflattenFromString(args[0])
                    ?: run {
                        Log.w(TAG, "Malformed componentName ${args[0]}")
                        return
                    }
            requestTileAdd(componentName, args[1], args[2], null) {
                Log.d(TAG, "Response: $it")
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar tile-service-add " +
                    "<componentName> <appName> <label>")
        }
    }

    @SysUISingleton
    class Builder @Inject constructor(
        private val commandRegistry: CommandRegistry
    ) {
        fun create(qsTileHost: QSTileHost): TileServiceRequestController {
            return TileServiceRequestController(qsTileHost, commandRegistry)
        }
    }
}