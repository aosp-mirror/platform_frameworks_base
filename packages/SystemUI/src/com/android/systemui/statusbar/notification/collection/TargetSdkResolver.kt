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

package com.android.systemui.statusbar.notification.collection

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.phone.CentralSurfaces
import javax.inject.Inject

@SysUISingleton
class TargetSdkResolver @Inject constructor(@Application private val context: Context) {
    fun initialize(collection: CommonNotifCollection) {
        collection.addCollectionListener(
            object : NotifCollectionListener {
                override fun onEntryBind(entry: NotificationEntry, sbn: StatusBarNotification) {
                    entry.targetSdk = resolveNotificationSdk(sbn)
                }
            }
        )
    }

    private fun resolveNotificationSdk(sbn: StatusBarNotification): Int {
        val applicationInfo =
            getApplicationInfoFromExtras(sbn.notification)
                ?: getApplicationInfoFromPackageManager(sbn)

        return applicationInfo?.targetSdkVersion ?: 0
    }

    private fun getApplicationInfoFromExtras(notification: Notification): ApplicationInfo? =
        notification.extras.getParcelable(
            Notification.EXTRA_BUILDER_APPLICATION_INFO,
            ApplicationInfo::class.java,
        )

    private fun getApplicationInfoFromPackageManager(sbn: StatusBarNotification): ApplicationInfo? {
        val pmUser = CentralSurfaces.getPackageManagerForUser(context, sbn.user.identifier)

        return try {
            pmUser.getApplicationInfo(sbn.packageName, 0)
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.packageName, ex)
            null
        }
    }

    private val TAG = "TargetSdkResolver"
}
