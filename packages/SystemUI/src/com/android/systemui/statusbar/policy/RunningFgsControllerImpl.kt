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
package com.android.systemui.statusbar.policy

import android.app.IActivityManager
import android.app.IForegroundServiceObserver
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.RunningFgsController.Callback
import com.android.systemui.statusbar.policy.RunningFgsController.UserPackageTime
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Implementation for [RunningFgsController]
 */
@SysUISingleton
class RunningFgsControllerImpl @Inject constructor(
    @Background private val executor: Executor,
    private val systemClock: SystemClock,
    private val activityManager: IActivityManager
) : RunningFgsController, IForegroundServiceObserver.Stub() {

    companion object {
        private val LOG_TAG = RunningFgsControllerImpl::class.java.simpleName
    }

    private val lock = Any()

    @GuardedBy("lock")
    var initialized = false

    @GuardedBy("lock")
    private val runningServiceTokens = mutableMapOf<UserPackageKey, StartTimeAndTokensValue>()

    @GuardedBy("lock")
    private val callbacks = mutableSetOf<Callback>()

    fun init() {
        synchronized(lock) {
            if (initialized) {
                return
            }
            try {
                activityManager.registerForegroundServiceObserver(this)
            } catch (e: RemoteException) {
                e.rethrowFromSystemServer()
            }

            initialized = true
        }
    }

    override fun addCallback(listener: Callback) {
        init()
        synchronized(lock) { callbacks.add(listener) }
    }

    override fun removeCallback(listener: Callback) {
        init()
        synchronized(lock) {
            if (!callbacks.remove(listener)) {
                Log.e(LOG_TAG, "Callback was not registered.", RuntimeException())
            }
        }
    }

    override fun observe(lifecycle: Lifecycle?, listener: Callback?): Callback {
        init()
        return super.observe(lifecycle, listener)
    }

    override fun observe(owner: LifecycleOwner?, listener: Callback?): Callback {
        init()
        return super.observe(owner, listener)
    }

    override fun getPackagesWithFgs(): List<UserPackageTime> {
        init()
        return synchronized(lock) { getPackagesWithFgsLocked() }
    }

    private fun getPackagesWithFgsLocked(): List<UserPackageTime> =
            runningServiceTokens.map {
                UserPackageTime(it.key.userId, it.key.packageName, it.value.fgsStartTime)
            }

    override fun stopFgs(userId: Int, packageName: String) {
        init()
        try {
            activityManager.makeServicesNonForeground(packageName, userId)
        } catch (e: RemoteException) {
            e.rethrowFromSystemServer()
        }
    }

    private data class UserPackageKey(
        val userId: Int,
        val packageName: String
    )

    private class StartTimeAndTokensValue(systemClock: SystemClock) {
        val fgsStartTime = systemClock.elapsedRealtime()
        var tokens = mutableSetOf<IBinder>()
        fun addToken(token: IBinder): Boolean {
            return tokens.add(token)
        }

        fun removeToken(token: IBinder): Boolean {
            return tokens.remove(token)
        }

        val isEmpty: Boolean
            get() = tokens.isEmpty()
    }

    override fun onForegroundStateChanged(
        token: IBinder,
        packageName: String,
        userId: Int,
        isForeground: Boolean
    ) {
        val result = synchronized(lock) {
            val userPackageKey = UserPackageKey(userId, packageName)
            if (isForeground) {
                var addedNew = false
                runningServiceTokens.getOrPut(userPackageKey) {
                    addedNew = true
                    StartTimeAndTokensValue(systemClock)
                }.addToken(token)
                if (!addedNew) {
                    return
                }
            } else {
                val startTimeAndTokensValue = runningServiceTokens[userPackageKey]
                if (startTimeAndTokensValue?.removeToken(token) == false) {
                    Log.e(LOG_TAG,
                            "Stopped foreground service was not known to be running.")
                    return
                }
                if (!startTimeAndTokensValue!!.isEmpty) {
                    return
                }
                runningServiceTokens.remove(userPackageKey)
            }
            getPackagesWithFgsLocked().toList()
        }

        callbacks.forEach { executor.execute { it.onFgsPackagesChanged(result) } }
    }
}