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

package com.android.systemui.recordissue

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.systemui.settings.UserContextProvider

private const val TAG = "UserAwareConnection"
private const val BIND_FLAGS =
    Context.BIND_AUTO_CREATE or
        Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE or
        Context.BIND_WAIVE_PRIORITY

/** ServiceConnection class that can be used to keep an IntentService alive. */
open class UserAwareConnection(
    protected val userContextProvider: UserContextProvider,
    private val intent: Intent,
) : ServiceConnection {
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) var binder: Messenger? = null
    private var shouldUnBind = false

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        binder = Messenger(service)
    }

    override fun onServiceDisconnected(className: ComponentName) {
        binder = null
    }

    @SuppressLint("WrongConstant")
    @WorkerThread
    fun doBind() {
        if (shouldUnBind) {
            // Binding needs to happen after the phone has been unlocked. The RecordIssueTile is
            // initialized before this happens though, so binding is placed at a later time, during
            // normal operations that can be repeated. This check avoids calling "bindService" 2x+
            return
        }
        try {
            shouldUnBind = userContextProvider.userContext.bindService(intent, this, BIND_FLAGS)
        } catch (e: Exception) {
            Log.e(TAG, "failed to bind to the service", e)
        }
    }

    @WorkerThread
    fun doUnBind() {
        if (shouldUnBind) {
            try {
                userContextProvider.userContext.unbindService(this)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Can't disconnect because service wasn't connected anyways.", e)
            }
            shouldUnBind = false
        }
    }
}
