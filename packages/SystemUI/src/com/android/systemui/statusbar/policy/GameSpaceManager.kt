/*
 * Copyright (C) 2021 Chaldeaprjkt
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy

import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners

import java.util.Arrays
import javax.inject.Inject

@SysUISingleton
class GameSpaceManager @Inject constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
) {
    private val handler by lazy { GameSpaceHandler(Looper.getMainLooper()) }
    private val taskManager by lazy { ActivityTaskManager.getService() }

    private var activeGame: String? = null
    private var isRegistered = false

    private val taskStackChangeListener = object : TaskStackChangeListener {
        override fun onTaskStackChanged() {
            handler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP)
        }
    }

    private val interactivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    activeGame = null
                    handler.sendEmptyMessage(MSG_DISPATCH_FOREGROUND_APP)
                }
            }
        }
    }

    private val keyguardStateCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            if (keyguardStateController.isShowing) return
            handler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP)
        }
    }

    private inner class GameSpaceHandler(looper: Looper?) : Handler(looper, null, true) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_UPDATE_FOREGROUND_APP -> checkForegroundApp()
                MSG_DISPATCH_FOREGROUND_APP -> dispatchForegroundApp()
            }
        }
    }

    private fun checkForegroundApp() {
        try {
            val info = taskManager.focusedRootTaskInfo
            info?.topActivity ?: return
            val packageName = info.topActivity?.packageName
            activeGame = checkGameList(packageName)
            handler.sendEmptyMessage(MSG_DISPATCH_FOREGROUND_APP)
        } catch (e: RemoteException) {
        }
    }

    private fun dispatchForegroundApp() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive && activeGame != null) return
        val action = if (activeGame != null) ACTION_GAME_START else ACTION_GAME_STOP
        Intent(action).apply {
            setPackage(GAMESPACE_PACKAGE)
            component = ComponentName.unflattenFromString(RECEIVER_CLASS)
            putExtra(EXTRA_CALLER_NAME, context.packageName)
            if (activeGame != null) putExtra(EXTRA_ACTIVE_GAME, activeGame)
            addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                or Intent.FLAG_RECEIVER_FOREGROUND
                or Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND)
            context.sendBroadcastAsUser(this, UserHandle.CURRENT,
                android.Manifest.permission.MANAGE_GAME_MODE)
        }
    }

    fun observe() {
        val taskStackChangeListeners = TaskStackChangeListeners.getInstance();
        if (isRegistered) {
            taskStackChangeListeners.unregisterTaskStackListener(taskStackChangeListener)
        }
        taskStackChangeListeners.registerTaskStackListener(taskStackChangeListener)
        isRegistered = true;
        handler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP)
        context.registerReceiver(interactivityReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        keyguardStateController.addCallback(keyguardStateCallback)
    }

    fun isGameActive() = activeGame != null

    fun shouldSuppressFullScreenIntent() =
        Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.GAMESPACE_SUPPRESS_FULLSCREEN_INTENT, 0,
            UserHandle.USER_CURRENT) == 1 && isGameActive()

    private fun checkGameList(packageName: String?): String? {
        packageName ?: return null
        val games = Settings.System.getStringForUser(
            context.contentResolver,
            Settings.System.GAMESPACE_GAME_LIST,
            UserHandle.USER_CURRENT)

        if (games.isNullOrEmpty())
            return null

        return games.split(";")
            .map { it.split("=").first() }
            .firstOrNull { it == packageName }
    }

    companion object {
        private const val ACTION_GAME_START = "io.chaldeaprjkt.gamespace.action.GAME_START"
        private const val ACTION_GAME_STOP = "io.chaldeaprjkt.gamespace.action.GAME_STOP"
        private const val GAMESPACE_PACKAGE = "io.chaldeaprjkt.gamespace"
        private const val RECEIVER_CLASS = "io.chaldeaprjkt.gamespace/.gamebar.GameBroadcastReceiver"
        private const val EXTRA_CALLER_NAME = "source"
        private const val EXTRA_ACTIVE_GAME = "package_name"
        private const val MSG_UPDATE_FOREGROUND_APP = 0
        private const val MSG_DISPATCH_FOREGROUND_APP = 1
    }
}
