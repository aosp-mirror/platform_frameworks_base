/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.app.tracing.coroutines.createCoroutineTracingContext
import android.annotation.UserIdInt
import android.annotation.WorkerThread
import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings.SettingNotFoundException
import com.android.app.tracing.TraceUtils.trace
import com.android.systemui.util.settings.SettingsProxy.Companion.parseFloat
import com.android.systemui.util.settings.SettingsProxy.Companion.parseFloatOrThrow
import com.android.systemui.util.settings.SettingsProxy.Companion.parseLongOrThrow
import com.android.systemui.util.settings.SettingsProxy.Companion.parseLongOrUseDefault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Used to interact with per-user Settings.Secure and Settings.System settings (but not
 * Settings.Global, since those do not vary per-user)
 *
 * This interface can be implemented to give instance method (instead of static method) versions of
 * Settings.Secure and Settings.System. It can be injected into class constructors and then faked or
 * mocked as needed in tests.
 *
 * You can ask for [SecureSettings] or [SystemSettings] to be injected as needed.
 *
 * This class also provides [.registerContentObserver] methods, normally found on [ContentResolver]
 * instances, unifying setting related actions in one place.
 */
interface UserSettingsProxy : SettingsProxy {
    val currentUserProvider: SettingsProxy.CurrentUserIdProvider

    /** Returns the user id for the associated [ContentResolver]. */
    var userId: Int
        get() = getContentResolver().userId
        set(_) {
            throw UnsupportedOperationException(
                "userId cannot be set in interface, use setter from an implementation instead."
            )
        }

    /**
     * Returns the actual current user handle when querying with the current user. Otherwise,
     * returns the passed in user id.
     */
    fun getRealUserHandle(userHandle: Int): Int {
        return if (userHandle != UserHandle.USER_CURRENT) {
            userHandle
        } else currentUserProvider.getUserId()
    }

    @WorkerThread
    override fun registerContentObserverSync(uri: Uri, settingsObserver: ContentObserver) {
        registerContentObserverForUserSync(uri, settingsObserver, userId)
    }

    override suspend fun registerContentObserver(uri: Uri, settingsObserver: ContentObserver) {
        withContext(backgroundDispatcher) {
            registerContentObserverForUserSync(uri, settingsObserver, userId)
        }
    }

    override fun registerContentObserverAsync(uri: Uri, settingsObserver: ContentObserver) =
        CoroutineScope(backgroundDispatcher + createCoroutineTracingContext("UserSettingsProxy-A")).launch {
            registerContentObserverForUserSync(uri, settingsObserver, userId)
        }

    /** Convenience wrapper around [ContentResolver.registerContentObserver].' */
    @WorkerThread
    override fun registerContentObserverSync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver
    ) {
        registerContentObserverForUserSync(uri, notifyForDescendants, settingsObserver, userId)
    }

    override suspend fun registerContentObserver(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver
    ) {
        withContext(backgroundDispatcher) {
            registerContentObserverForUserSync(uri, notifyForDescendants, settingsObserver, userId)
        }
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * API corresponding to [registerContentObserverForUser] for Java usage.
     */
    override fun registerContentObserverAsync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver
    ) =
        CoroutineScope(backgroundDispatcher + createCoroutineTracingContext("UserSettingsProxy-B")).launch {
            registerContentObserverForUserSync(uri, notifyForDescendants, settingsObserver, userId)
        }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver]
     *
     * Implicitly calls [getUriFor] on the passed in name.
     */
    @WorkerThread
    fun registerContentObserverForUserSync(
        name: String,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        registerContentObserverForUserSync(getUriFor(name), settingsObserver, userHandle)
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * suspend API corresponding to [registerContentObserverForUser] to ensure that
     * [ContentObserver] registration happens on a worker thread. Caller may wrap the API in an
     * async block if they wish to synchronize execution.
     */
    suspend fun registerContentObserverForUser(
        name: String,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        withContext(backgroundDispatcher) {
            registerContentObserverForUserSync(name, settingsObserver, userHandle)
        }
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * API corresponding to [registerContentObserverForUser] for Java usage.
     */
    fun registerContentObserverForUserAsync(
        name: String,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) =
        CoroutineScope(backgroundDispatcher + createCoroutineTracingContext("UserSettingsProxy-C")).launch {
            registerContentObserverForUserSync(getUriFor(name), settingsObserver, userHandle)
        }

    /** Convenience wrapper around [ContentResolver.registerContentObserver] */
    @WorkerThread
    fun registerContentObserverForUserSync(
        uri: Uri,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        registerContentObserverForUserSync(uri, false, settingsObserver, userHandle)
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * suspend API corresponding to [registerContentObserverForUser] to ensure that
     * [ContentObserver] registration happens on a worker thread. Caller may wrap the API in an
     * async block if they wish to synchronize execution.
     */
    suspend fun registerContentObserverForUser(
        uri: Uri,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        withContext(backgroundDispatcher) {
            registerContentObserverForUserSync(uri, settingsObserver, userHandle)
        }
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * API corresponding to [registerContentObserverForUser] for Java usage.
     */
    fun registerContentObserverForUserAsync(
        uri: Uri,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) =
        CoroutineScope(backgroundDispatcher + createCoroutineTracingContext("UserSettingsProxy-D")).launch {
            registerContentObserverForUserSync(uri, settingsObserver, userHandle)
        }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * API corresponding to [registerContentObserverForUser] for Java usage. After registration is
     * complete, the callback block is called on the <b>background thread</b> to allow for update of
     * value.
     */
    fun registerContentObserverForUserAsync(
        uri: Uri,
        settingsObserver: ContentObserver,
        userHandle: Int,
        @WorkerThread registered: Runnable
    ) =
        CoroutineScope(backgroundDispatcher + createCoroutineTracingContext("UserSettingsProxy-E")).launch {
            registerContentObserverForUserSync(uri, settingsObserver, userHandle)
            registered.run()
        }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver]
     *
     * Implicitly calls [getUriFor] on the passed in name.
     */
    @WorkerThread
    fun registerContentObserverForUserSync(
        name: String,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        registerContentObserverForUserSync(
            getUriFor(name),
            notifyForDescendants,
            settingsObserver,
            userHandle
        )
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * suspend API corresponding to [registerContentObserverForUser] to ensure that
     * [ContentObserver] registration happens on a worker thread. Caller may wrap the API in an
     * async block if they wish to synchronize execution.
     */
    suspend fun registerContentObserverForUser(
        name: String,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        withContext(backgroundDispatcher) {
            registerContentObserverForUserSync(
                name,
                notifyForDescendants,
                settingsObserver,
                userHandle
            )
        }
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * API corresponding to [registerContentObserverForUser] for Java usage.
     */
    fun registerContentObserverForUserAsync(
        name: String,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        CoroutineScope(backgroundDispatcher + createCoroutineTracingContext("UserSettingsProxy-F")).launch {
            registerContentObserverForUserSync(
                getUriFor(name),
                notifyForDescendants,
                settingsObserver,
                userHandle
            )
        }
    }

    /** Convenience wrapper around [ContentResolver.registerContentObserver] */
    @WorkerThread
    fun registerContentObserverForUserSync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        trace({ "USP#registerObserver#[$uri]" }) {
            getContentResolver()
                .registerContentObserver(
                    uri,
                    notifyForDescendants,
                    settingsObserver,
                    getRealUserHandle(userHandle)
                )
            Unit
        }
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * suspend API corresponding to [registerContentObserverForUser] to ensure that
     * [ContentObserver] registration happens on a worker thread. Caller may wrap the API in an
     * async block if they wish to synchronize execution.
     */
    suspend fun registerContentObserverForUser(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) {
        withContext(backgroundDispatcher) {
            registerContentObserverForUserSync(
                uri,
                notifyForDescendants,
                settingsObserver,
                getRealUserHandle(userHandle)
            )
        }
    }

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * API corresponding to [registerContentObserverForUser] for Java usage.
     */
    fun registerContentObserverForUserAsync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver,
        userHandle: Int
    ) =
        CoroutineScope(backgroundDispatcher + createCoroutineTracingContext("UserSettingsProxy-G")).launch {
            registerContentObserverForUserSync(
                uri,
                notifyForDescendants,
                settingsObserver,
                userHandle
            )
        }

    /**
     * Look up a name in the database.
     *
     * @param name to look up in the table
     * @return the corresponding value, or null if not present
     */
    override fun getString(name: String): String? {
        return getStringForUser(name, userId)
    }

    /** See [getString]. */
    fun getStringForUser(name: String, userHandle: Int): String?

    /**
     * Store a name/value pair into the database. Values written by this method will be overridden
     * if a restore happens in the future.
     *
     * @param name to store
     * @param value to associate with the name
     * @return true if the value was set, false on database errors
     */
    fun putString(name: String, value: String?, overrideableByRestore: Boolean): Boolean

    override fun putString(name: String, value: String?): Boolean {
        return putStringForUser(name, value, userId)
    }

    /** Similar implementation to [putString] for the specified [userHandle]. */
    fun putStringForUser(name: String, value: String?, userHandle: Int): Boolean

    /** Similar implementation to [putString] for the specified [userHandle]. */
    fun putStringForUser(
        name: String,
        value: String?,
        tag: String?,
        makeDefault: Boolean,
        @UserIdInt userHandle: Int,
        overrideableByRestore: Boolean
    ): Boolean

    override fun getInt(name: String, default: Int): Int {
        return getIntForUser(name, default, userId)
    }

    /** Similar implementation to [getInt] for the specified [userHandle]. */
    fun getIntForUser(name: String, default: Int, userHandle: Int): Int {
        val v = getStringForUser(name, userHandle)
        return try {
            v?.toInt() ?: default
        } catch (e: NumberFormatException) {
            default
        }
    }

    @Throws(SettingNotFoundException::class)
    override fun getInt(name: String) = getIntForUser(name, userId)

    /** Similar implementation to [getInt] for the specified [userHandle]. */
    @Throws(SettingNotFoundException::class)
    fun getIntForUser(name: String, userHandle: Int): Int {
        val v = getStringForUser(name, userHandle) ?: throw SettingNotFoundException(name)
        return try {
            v.toInt()
        } catch (e: NumberFormatException) {
            throw SettingNotFoundException(name)
        }
    }

    override fun putInt(name: String, value: Int) = putIntForUser(name, value, userId)

    /** Similar implementation to [getInt] for the specified [userHandle]. */
    fun putIntForUser(name: String, value: Int, userHandle: Int) =
        putStringForUser(name, value.toString(), userHandle)

    override fun getBool(name: String, def: Boolean) = getBoolForUser(name, def, userId)

    /** Similar implementation to [getBool] for the specified [userHandle]. */
    fun getBoolForUser(name: String, def: Boolean, userHandle: Int) =
        getIntForUser(name, if (def) 1 else 0, userHandle) != 0

    @Throws(SettingNotFoundException::class)
    override fun getBool(name: String) = getBoolForUser(name, userId)

    /** Similar implementation to [getBool] for the specified [userHandle]. */
    @Throws(SettingNotFoundException::class)
    fun getBoolForUser(name: String, userHandle: Int): Boolean {
        return getIntForUser(name, userHandle) != 0
    }

    override fun putBool(name: String, value: Boolean): Boolean {
        return putBoolForUser(name, value, userId)
    }

    /** Similar implementation to [putBool] for the specified [userHandle]. */
    fun putBoolForUser(name: String, value: Boolean, userHandle: Int) =
        putIntForUser(name, if (value) 1 else 0, userHandle)

    /** Similar implementation to [getLong] for the specified [userHandle]. */
    fun getLongForUser(name: String, def: Long, userHandle: Int): Long {
        val valString = getStringForUser(name, userHandle)
        return parseLongOrUseDefault(valString, def)
    }

    /** Similar implementation to [getLong] for the specified [userHandle]. */
    @Throws(SettingNotFoundException::class)
    fun getLongForUser(name: String, userHandle: Int): Long {
        val valString = getStringForUser(name, userHandle)
        return parseLongOrThrow(name, valString)
    }

    /** Similar implementation to [putLong] for the specified [userHandle]. */
    fun putLongForUser(name: String, value: Long, userHandle: Int) =
        putStringForUser(name, value.toString(), userHandle)

    /** Similar implementation to [getFloat] for the specified [userHandle]. */
    fun getFloatForUser(name: String, def: Float, userHandle: Int): Float {
        val v = getStringForUser(name, userHandle)
        return parseFloat(v, def)
    }

    /** Similar implementation to [getFloat] for the specified [userHandle]. */
    @Throws(SettingNotFoundException::class)
    fun getFloatForUser(name: String, userHandle: Int): Float {
        val v = getStringForUser(name, userHandle)
        return parseFloatOrThrow(name, v)
    }

    /** Similar implementation to [putFloat] for the specified [userHandle]. */
    fun putFloatForUser(name: String, value: Float, userHandle: Int) =
        putStringForUser(name, value.toString(), userHandle)
}
