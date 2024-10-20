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
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import dagger.Module
import dagger.Provides
import javax.inject.Inject
import javax.inject.Provider

/**
 * A provider used to cache and fetch information about which icon should be displayed by
 * notifications.
 */
interface NotificationIconStyleProvider {
    @WorkerThread
    fun shouldShowAppIcon(notification: StatusBarNotification, context: Context): Boolean
}

@SysUISingleton
class NotificationIconStyleProviderImpl @Inject constructor() : NotificationIconStyleProvider {
    override fun shouldShowAppIcon(notification: StatusBarNotification, context: Context): Boolean {
        val packageContext = notification.getPackageContext(context)
        return !belongsToHeadlessSystemApp(packageContext)
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
}

class NoOpIconStyleProvider : NotificationIconStyleProvider {
    companion object {
        const val TAG = "NoOpIconStyleProvider"
    }

    override fun shouldShowAppIcon(notification: StatusBarNotification, context: Context): Boolean {
        Log.wtf(TAG, "NoOpIconStyleProvider should not be used anywhere.")
        return true
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
