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

package com.android.systemui.statusbar.phone.ongoingcall

import android.app.Notification
import android.app.Notification.CallStyle.CALL_TYPE_ONGOING
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import javax.inject.Inject

/**
 * A controller to handle the ongoing call chip in the collapsed status bar.
 */
@SysUISingleton
class OngoingCallController @Inject constructor(
    private val notifCollection: CommonNotifCollection,
    private val featureFlags: FeatureFlags
) {

    private val notifListener = object : NotifCollectionListener {
        override fun onEntryUpdated(entry: NotificationEntry) {
            if (isOngoingCallNotification(entry) && DEBUG) {
                Log.d(TAG, "Ongoing call notification updated")
            }
        }

        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            if (isOngoingCallNotification(entry) && DEBUG) {
                Log.d(TAG, "Ongoing call notification removed")
            }
        }
    }

    fun init() {
        if (featureFlags.isOngoingCallStatusBarChipEnabled) {
            notifCollection.addCollectionListener(notifListener)
        }
    }
}

private fun isOngoingCallNotification(entry: NotificationEntry): Boolean {
    val extras = entry.sbn.notification.extras
    val callStyleTemplateName = Notification.CallStyle::class.java.name
    return extras.getString(Notification.EXTRA_TEMPLATE) == callStyleTemplateName &&
            extras.getInt(Notification.EXTRA_CALL_TYPE, -1) == CALL_TYPE_ONGOING
}

private const val TAG = "OngoingCallController"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
