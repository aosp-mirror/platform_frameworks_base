/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings

import android.app.IActivityManager
import android.app.UserSwitchObserver
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.os.Handler
import android.os.IRemoteCallback
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import com.android.systemui.Dumpable
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.util.Assert
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import javax.inject.Provider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * SystemUI cache for keeping track of the current user and associated values.
 *
 * The values provided asynchronously are NOT copies, but shared among all requesters. Do not modify
 * them.
 *
 * This class purposefully doesn't use [BroadcastDispatcher] in order to receive the broadcast as
 * soon as possible (and reduce its dependency graph). Other classes that want to listen to the
 * broadcasts listened here SHOULD subscribe to this class instead.
 *
 * @see UserTracker
 *
 * Class constructed and initialized in [SettingsModule].
 */
open class UserTrackerImpl
internal constructor(
    private val context: Context,
    private val featureFlagsProvider: Provider<FeatureFlagsClassic>,
    private val userManager: UserManager,
    private val iActivityManager: IActivityManager,
    private val dumpManager: DumpManager,
    private val appScope: CoroutineScope,
    private val backgroundContext: CoroutineDispatcher,
    private val backgroundHandler: Handler,
) : UserTracker, Dumpable, BroadcastReceiver() {

    companion object {
        private const val TAG = "UserTrackerImpl"
        private const val USER_CHANGE_THRESHOLD = 5L * 1000 // 5 sec
    }

    var initialized = false
        private set

    private val mutex = Any()
    private val isBackgroundUserSwitchEnabled: Boolean
        get() = featureFlagsProvider.get().isEnabled(Flags.USER_TRACKER_BACKGROUND_CALLBACKS)

    @Deprecated("Use UserInteractor.getSelectedUserId()")
    override var userId: Int by SynchronizedDelegate(context.userId)
        protected set

    override var userHandle: UserHandle by SynchronizedDelegate(context.user)
        protected set

    override var userContext: Context by SynchronizedDelegate(context)
        protected set

    override val userContentResolver: ContentResolver
        get() = userContext.contentResolver

    override var userInfo: UserInfo by SynchronizedDelegate(UserInfo(context.userId, "", 0))
        protected set

    override var isUserSwitching = false
        protected set

    /**
     * Returns a [List<UserInfo>] of all profiles associated with the current user.
     *
     * The list returned is not a copy, so a copy should be made if its elements need to be
     * modified.
     */
    override var userProfiles: List<UserInfo> by SynchronizedDelegate(emptyList())
        protected set

    @GuardedBy("callbacks") private val callbacks: MutableList<DataItem> = ArrayList()

    private var userSwitchingJob: Job? = null
    private var afterUserSwitchingJob: Job? = null

    open fun initialize(startingUser: Int) {
        if (initialized) {
            return
        }
        Log.i(TAG, "Starting user: $startingUser")
        initialized = true
        setUserIdInternal(startingUser)

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_USER_INFO_CHANGED)
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                // These get called when a managed profile goes in or out of quiet mode.
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            }
        context.registerReceiverForAllUsers(this, filter, null, backgroundHandler)

        registerUserSwitchObserver()

        dumpManager.registerDumpable(TAG, this)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_USER_INFO_CHANGED,
            Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
            Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
            Intent.ACTION_MANAGED_PROFILE_ADDED,
            Intent.ACTION_MANAGED_PROFILE_REMOVED,
            Intent.ACTION_MANAGED_PROFILE_UNLOCKED,
            Intent.ACTION_PROFILE_ADDED,
            Intent.ACTION_PROFILE_REMOVED,
            Intent.ACTION_PROFILE_AVAILABLE,
            Intent.ACTION_PROFILE_UNAVAILABLE -> {
                handleProfilesChanged()
            }
        }
    }

    override fun createCurrentUserContext(context: Context): Context {
        synchronized(mutex) {
            return context.createContextAsUser(userHandle, 0)
        }
    }

    private fun setUserIdInternal(user: Int): Pair<Context, List<UserInfo>> {
        val profiles = userManager.getProfiles(user)
        val handle = UserHandle(user)
        val ctx = context.createContextAsUser(handle, 0)

        synchronized(mutex) {
            userId = user
            userHandle = handle
            userContext = ctx
            userProfiles = profiles.map { UserInfo(it) }
            userInfo = profiles.first { it.id == user }
        }
        return ctx to profiles
    }

    private fun registerUserSwitchObserver() {
        iActivityManager.registerUserSwitchObserver(
            object : UserSwitchObserver() {
                override fun onBeforeUserSwitching(newUserId: Int) {
                    handleBeforeUserSwitching(newUserId)
                }

                override fun onUserSwitching(newUserId: Int, reply: IRemoteCallback?) {
                    isUserSwitching = true
                    if (isBackgroundUserSwitchEnabled) {
                        userSwitchingJob?.cancel()
                        userSwitchingJob =
                            appScope.launch(backgroundContext) {
                                handleUserSwitchingCoroutines(newUserId) { reply?.sendResult(null) }
                            }
                    } else {
                        handleUserSwitching(newUserId)
                        reply?.sendResult(null)
                    }
                }

                override fun onUserSwitchComplete(newUserId: Int) {
                    isUserSwitching = false
                    if (isBackgroundUserSwitchEnabled) {
                        afterUserSwitchingJob?.cancel()
                        afterUserSwitchingJob =
                            appScope.launch(backgroundContext) {
                                handleUserSwitchComplete(newUserId)
                            }
                    } else {
                        handleUserSwitchComplete(newUserId)
                    }
                }
            },
            TAG,
        )
    }

    @WorkerThread
    protected open fun handleBeforeUserSwitching(newUserId: Int) {
        setUserIdInternal(newUserId)

        notifySubscribers { callback, resultCallback ->
                callback.onBeforeUserSwitching(newUserId)
                resultCallback.run()
            }
            .await()
    }

    @WorkerThread
    protected open fun handleUserSwitching(newUserId: Int) {
        Assert.isNotMainThread()
        Log.i(TAG, "Switching to user $newUserId")

        notifySubscribers { callback, resultCallback ->
                callback.onUserChanging(newUserId, userContext, resultCallback)
            }
            .await()
    }

    @WorkerThread
    protected open suspend fun handleUserSwitchingCoroutines(newUserId: Int, onDone: () -> Unit) =
        coroutineScope {
            Assert.isNotMainThread()
            Log.i(TAG, "Switching to user $newUserId")

            for (callbackDataItem in synchronized(callbacks) { callbacks.toList() }) {
                val callback: UserTracker.Callback = callbackDataItem.callback.get() ?: continue
                launch(callbackDataItem.executor.asCoroutineDispatcher()) {
                        val mutex = Mutex(true)
                        val thresholdLogJob =
                            launch(backgroundContext) {
                                delay(USER_CHANGE_THRESHOLD)
                                Log.e(TAG, "Failed to finish $callback in time")
                            }
                        callback.onUserChanging(userId, userContext) { mutex.unlock() }
                        mutex.lock()
                        thresholdLogJob.cancel()
                    }
                    .join()
            }
            onDone()
        }

    @WorkerThread
    protected open fun handleUserSwitchComplete(newUserId: Int) {
        Assert.isNotMainThread()
        Log.i(TAG, "Switched to user $newUserId")

        notifySubscribers { callback, _ ->
            callback.onUserChanged(newUserId, userContext)
            callback.onProfilesChanged(userProfiles)
        }
    }

    @WorkerThread
    protected open fun handleProfilesChanged() {
        Assert.isNotMainThread()

        val profiles = userManager.getProfiles(userId)
        synchronized(mutex) {
            userProfiles = profiles.map { UserInfo(it) } // save a "deep" copy
        }
        notifySubscribers { callback, _ -> callback.onProfilesChanged(profiles) }
    }

    override fun addCallback(callback: UserTracker.Callback, executor: Executor) {
        synchronized(callbacks) { callbacks.add(DataItem(WeakReference(callback), executor)) }
    }

    override fun removeCallback(callback: UserTracker.Callback) {
        synchronized(callbacks) { callbacks.removeIf { it.sameOrEmpty(callback) } }
    }

    private inline fun notifySubscribers(
        crossinline action: (UserTracker.Callback, resultCallback: Runnable) -> Unit
    ): CountDownLatch {
        val list = synchronized(callbacks) { callbacks.toList() }
        val latch = CountDownLatch(list.size)
        list.forEach {
            val callback = it.callback.get()
            if (callback != null) {
                it.executor.execute { action(callback) { latch.countDown() } }
            } else {
                latch.countDown()
            }
        }
        return latch
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Initialized: $initialized")
        if (initialized) {
            pw.println("userId: $userId")
            val ids = userProfiles.map { it.toFullString() }
            pw.println("userProfiles: $ids")
        }
        val list = synchronized(callbacks) { callbacks.toList() }
        pw.println("Callbacks:")
        list.forEach { it.callback.get()?.let { pw.println("  $it") } }
    }

    private class SynchronizedDelegate<T : Any>(private var value: T) :
        ReadWriteProperty<UserTrackerImpl, T> {

        @GuardedBy("mutex")
        override fun getValue(thisRef: UserTrackerImpl, property: KProperty<*>): T {
            if (!thisRef.initialized) {
                throw IllegalStateException("Must initialize before getting ${property.name}")
            }
            return synchronized(thisRef.mutex) { value }
        }

        @GuardedBy("mutex")
        override fun setValue(thisRef: UserTrackerImpl, property: KProperty<*>, value: T) {
            synchronized(thisRef.mutex) { this.value = value }
        }
    }
}

private data class DataItem(
    val callback: WeakReference<UserTracker.Callback>,
    val executor: Executor,
) {
    fun sameOrEmpty(other: UserTracker.Callback): Boolean {
        return callback.get()?.equals(other) ?: true
    }
}
