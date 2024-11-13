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
package com.android.systemui.util.settings

import android.annotation.UserIdInt
import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.UserHandle
import android.util.Pair
import androidx.annotation.VisibleForTesting
import com.android.systemui.util.settings.SettingsProxy.CurrentUserIdProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

class FakeSettings : SecureSettings, SystemSettings, UserSettingsProxy {
    private val values = mutableMapOf<SettingsKey, String?>()
    private val contentObservers = mutableMapOf<SettingsKey, MutableList<ContentObserver>>()
    private val contentObserversAllUsers = mutableMapOf<String, MutableList<ContentObserver>>()

    override val backgroundDispatcher: CoroutineDispatcher

    @UserIdInt override var userId = UserHandle.USER_CURRENT
    override val currentUserProvider: CurrentUserIdProvider

    @Deprecated(
        """Please use FakeSettings(testDispatcher) to provide the same dispatcher used
      by main test scope."""
    )
    constructor() {
        backgroundDispatcher = StandardTestDispatcher(scheduler = null, name = null)
        currentUserProvider = CurrentUserIdProvider { userId }
    }

    constructor(dispatcher: CoroutineDispatcher) {
        backgroundDispatcher = dispatcher
        currentUserProvider = CurrentUserIdProvider { userId }
    }

    constructor(dispatcher: CoroutineDispatcher, currentUserProvider: CurrentUserIdProvider) {
        backgroundDispatcher = dispatcher
        this.currentUserProvider = currentUserProvider
    }

    @VisibleForTesting
    internal constructor(initialKey: String, initialValue: String) : this() {
        putString(initialKey, initialValue)
    }

    @VisibleForTesting
    internal constructor(initialValues: Map<String, String>) : this() {
        for ((key, value) in initialValues) {
            putString(key, value)
        }
    }

    override fun getContentResolver(): ContentResolver {
        throw UnsupportedOperationException("FakeSettings.getContentResolver is not implemented")
    }

    override fun registerContentObserverForUserSync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        if (userHandle == UserHandle.USER_ALL) {
            contentObserversAllUsers
                .getOrPut(uri.toString()) { mutableListOf() }
                .add(settingsObserver)
        } else {
            val key = SettingsKey(userHandle, uri.toString())
            contentObservers.getOrPut(key) { mutableListOf() }.add(settingsObserver)
        }
    }

    override fun unregisterContentObserverSync(settingsObserver: ContentObserver) {
        contentObservers.values.onEach { it.remove(settingsObserver) }
        contentObserversAllUsers.values.onEach { it.remove(settingsObserver) }
    }

    override suspend fun registerContentObserver(uri: Uri, settingsObserver: ContentObserver) =
        suspendAdvanceDispatcher {
            super<UserSettingsProxy>.registerContentObserver(uri, settingsObserver)
        }

    override fun registerContentObserverAsync(uri: Uri, settingsObserver: ContentObserver): Job =
        advanceDispatcher {
            super<UserSettingsProxy>.registerContentObserverAsync(uri, settingsObserver)
        }

    override suspend fun registerContentObserver(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver
    ) = suspendAdvanceDispatcher {
        super<UserSettingsProxy>.registerContentObserver(
            uri,
            notifyForDescendants,
            settingsObserver
        )
    }

    override fun registerContentObserverAsync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver
    ): Job = advanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverAsync(
            uri,
            notifyForDescendants,
            settingsObserver
        )
    }

    override suspend fun registerContentObserverForUser(
        name: String,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) = suspendAdvanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUser(name, settingsObserver, userHandle)
    }

    override fun registerContentObserverForUserAsync(
        name: String,
        settingsObserver: ContentObserver,
        userHandle: Int
    ): Job = advanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUserAsync(
            name,
            settingsObserver,
            userHandle
        )
    }

    override fun unregisterContentObserverAsync(settingsObserver: ContentObserver): Job =
        advanceDispatcher {
            super<UserSettingsProxy>.unregisterContentObserverAsync(settingsObserver)
        }

    override suspend fun registerContentObserverForUser(
        uri: Uri,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) = suspendAdvanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUser(uri, settingsObserver, userHandle)
    }

    override fun registerContentObserverForUserAsync(
        uri: Uri,
        settingsObserver: ContentObserver,
        userHandle: Int
    ): Job = advanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUserAsync(
            uri,
            settingsObserver,
            userHandle
        )
    }

    override fun registerContentObserverForUserAsync(
        uri: Uri,
        settingsObserver: ContentObserver,
        userHandle: Int,
        registered: Runnable
    ): Job = advanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUserAsync(
            uri,
            settingsObserver,
            userHandle,
            registered
        )
    }

    override suspend fun registerContentObserverForUser(
        name: String,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) = suspendAdvanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUser(
            name,
            notifyForDescendants,
            settingsObserver,
            userHandle
        )
    }

    override fun registerContentObserverForUserAsync(
        name: String,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) = advanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUserAsync(
            name,
            notifyForDescendants,
            settingsObserver,
            userHandle
        )
    }

    override fun registerContentObserverForUserAsync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ): Job = advanceDispatcher {
        super<UserSettingsProxy>.registerContentObserverForUserAsync(
            uri,
            notifyForDescendants,
            settingsObserver,
            userHandle
        )
    }

    override fun getUriFor(name: String): Uri {
        return Uri.withAppendedPath(CONTENT_URI, name)
    }

    override fun getString(name: String): String? {
        return getStringForUser(name, userId)
    }

    override fun getStringForUser(name: String, userHandle: Int): String? {
        return values[SettingsKey(userHandle, getUriFor(name).toString())]
    }

    override fun putString(name: String, value: String?, overrideableByRestore: Boolean): Boolean {
        return putStringForUser(name, value, null, false, userId, overrideableByRestore)
    }

    override fun putString(name: String, value: String?): Boolean {
        return putString(name, value, false)
    }

    override fun putStringForUser(name: String, value: String?, userHandle: Int): Boolean {
        return putStringForUser(name, value, null, false, userHandle, false)
    }

    override fun putStringForUser(
        name: String,
        value: String?,
        tag: String?,
        makeDefault: Boolean,
        userHandle: Int,
        overrideableByRestore: Boolean
    ): Boolean {
        val key = SettingsKey(userHandle, getUriFor(name).toString())
        values[key] = value
        val uri = getUriFor(name)
        contentObservers[key]?.onEach { it.dispatchChange(false, listOf(uri), 0, userHandle) }
        contentObserversAllUsers[uri.toString()]?.onEach {
            it.dispatchChange(false, listOf(uri), 0, userHandle)
        }
        return true
    }

    override fun putString(
        name: String,
        value: String?,
        tag: String?,
        makeDefault: Boolean
    ): Boolean {
        return putString(name, value)
    }

    /** Runs current jobs on dispatcher after calling the method. */
    private fun <T> advanceDispatcher(f: () -> T): T {
        val result = f()
        testDispatcherRunCurrent()
        return result
    }

    private suspend fun <T> suspendAdvanceDispatcher(f: suspend () -> T): T {
        val result = f()
        testDispatcherRunCurrent()
        return result
    }

    private fun testDispatcherRunCurrent() {
        val testDispatcher = backgroundDispatcher as? TestDispatcher
        testDispatcher?.scheduler?.runCurrent()
    }

    private data class SettingsKey(val first: Int, val second: String) :
        Pair<Int, String>(first, second)

    companion object {
        val CONTENT_URI = Uri.parse("content://settings/fake")
    }
}
