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
import android.view.ViewGroup
import android.widget.Chronometer
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

/**
 * A controller to handle the ongoing call chip in the collapsed status bar.
 */
@SysUISingleton
class OngoingCallController @Inject constructor(
    private val notifCollection: CommonNotifCollection,
    private val featureFlags: FeatureFlags,
    private val systemClock: SystemClock
) : CallbackController<OngoingCallListener> {

    var hasOngoingCall = false
        private set
    private var chipView: ViewGroup? = null

    private val mListeners: MutableList<OngoingCallListener> = mutableListOf()

    private val notifListener = object : NotifCollectionListener {
        override fun onEntryUpdated(entry: NotificationEntry) {
            if (isOngoingCallNotification(entry)) {
                val timeView = chipView?.findViewById<Chronometer>(R.id.ongoing_call_chip_time)
                if (timeView != null) {
                    hasOngoingCall = true
                    val callStartTime = entry.sbn.notification.`when`
                    timeView.base = callStartTime -
                            System.currentTimeMillis() +
                            systemClock.elapsedRealtime()
                    timeView.start()
                    mListeners.forEach { l -> l.onOngoingCallStarted(animate = true) }
                } else if (DEBUG) {
                    Log.w(TAG, "Ongoing call chip view could not be found; " +
                            "Not displaying chip in status bar")
                }
            }
        }

        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            if (isOngoingCallNotification(entry)) {
                hasOngoingCall = false
                mListeners.forEach { l -> l.onOngoingCallEnded(animate = true) }
            }
        }
    }

    fun init() {
        if (featureFlags.isOngoingCallStatusBarChipEnabled) {
            notifCollection.addCollectionListener(notifListener)
        }
    }

    fun setChipView(chipView: ViewGroup?) {
        this.chipView = chipView
    }

    override fun addCallback(listener: OngoingCallListener) {
        synchronized(mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener)
            }
        }
    }

    override fun removeCallback(listener: OngoingCallListener) {
        synchronized(mListeners) {
            mListeners.remove(listener)
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
