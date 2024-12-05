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

package com.android.systemui.statusbar.notification.row.icon

import android.annotation.WorkerThread
import android.app.Flags
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.UserManager
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.collection.NotifCollectionCache
import com.android.systemui.util.asIndenting
import com.android.systemui.util.withIncreasedIndent
import dagger.Module
import dagger.Provides
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

/**
 * A provider used to cache and fetch information about which icon should be displayed by
 * notifications.
 */
interface NotificationIconStyleProvider {
    /**
     * Determines whether the [notification] should display the app icon instead of the small icon.
     * This can result in a binder call, and therefore should only be called from the background.
     */
    @WorkerThread
    fun shouldShowAppIcon(notification: StatusBarNotification, context: Context): Boolean

    /**
     * Whether the [notification] is coming from a work profile app, and therefore should display
     * the briefcase badge.
     */
    fun shouldShowWorkProfileBadge(notification: StatusBarNotification, context: Context): Boolean

    /**
     * Mark all the entries in the cache that are NOT in [wantedPackages] to be cleared. If they're
     * still not needed on the next call of this method (made after a timeout of 1s, in case they
     * happen more frequently than that), they will be purged. This can be done from any thread.
     */
    fun purgeCache(wantedPackages: Collection<String>)
}

@SysUISingleton
class NotificationIconStyleProviderImpl
@Inject
constructor(private val userManager: UserManager, dumpManager: DumpManager) :
    NotificationIconStyleProvider, Dumpable {
    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    private val cache = NotifCollectionCache<Boolean>()

    override fun shouldShowAppIcon(notification: StatusBarNotification, context: Context): Boolean {
        return cache.getOrFetch(notification.packageName) {
            val packageContext = notification.getPackageContext(context)
            !belongsToHeadlessSystemApp(packageContext)
        }
    }

    @WorkerThread
    private fun belongsToHeadlessSystemApp(context: Context): Boolean {
        val info = context.applicationInfo
        if (info != null) {
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                // It's not a system app at all.
                return false
            } else {
                // If there's no launch intent, it's probably a headless app.
                val pm = context.packageManager
                return (pm.getLaunchIntentForPackage(info.packageName) == null)
            }
        } else {
            // If for some reason we don't have the app info, we don't know; best assume it's
            // not a system app.
            return false
        }
    }

    override fun shouldShowWorkProfileBadge(
        notification: StatusBarNotification,
        context: Context,
    ): Boolean {
        val packageContext = notification.getPackageContext(context)
        // UserManager already caches this, so we don't need to.
        return userManager.isManagedProfile(packageContext.userId)
    }

    override fun purgeCache(wantedPackages: Collection<String>) {
        cache.purge(wantedPackages)
    }

    override fun dump(pwOrig: PrintWriter, args: Array<out String>) {
        val pw = pwOrig.asIndenting()
        pw.println("cache information:")
        pw.withIncreasedIndent { cache.dump(pw, args) }
    }

    companion object {
        const val TAG = "NotificationIconStyleProviderImpl"
    }
}

class NoOpIconStyleProvider : NotificationIconStyleProvider {
    companion object {
        const val TAG = "NoOpIconStyleProvider"
    }

    override fun shouldShowAppIcon(notification: StatusBarNotification, context: Context): Boolean {
        Log.wtf(TAG, "NoOpIconStyleProvider should not be used anywhere.")
        return true
    }

    override fun shouldShowWorkProfileBadge(
        notification: StatusBarNotification,
        context: Context,
    ): Boolean {
        Log.wtf(TAG, "NoOpIconStyleProvider should not be used anywhere.")
        return false
    }

    override fun purgeCache(wantedPackages: Collection<String>) {
        Log.wtf(TAG, "NoOpIconStyleProvider should not be used anywhere.")
    }
}

@Module
class NotificationIconStyleProviderModule {
    @Provides
    @SysUISingleton
    fun provideImpl(
        realImpl: Provider<NotificationIconStyleProviderImpl>
    ): NotificationIconStyleProvider =
        if (Flags.notificationsRedesignAppIcons()) {
            realImpl.get()
        } else {
            NoOpIconStyleProvider()
        }
}
