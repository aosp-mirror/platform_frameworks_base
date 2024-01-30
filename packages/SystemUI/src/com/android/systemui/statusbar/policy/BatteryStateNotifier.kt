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

package com.android.systemui.statusbar.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.systemui.res.R
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject

/**
 * Listens for important battery states and sends non-dismissible system notifications if there is a
 * problem
 */
class BatteryStateNotifier @Inject constructor(
    val controller: BatteryController,
    val noMan: NotificationManager,
    val delayableExecutor: DelayableExecutor,
    val context: Context
) : BatteryController.BatteryStateChangeCallback {
    var stateUnknown = false

    fun startListening() {
        controller.addCallback(this)
    }

    fun stopListening() {
        controller.removeCallback(this)
    }

    override fun onBatteryUnknownStateChanged(isUnknown: Boolean) {
        stateUnknown = isUnknown
        if (stateUnknown) {
            val channel = NotificationChannel("battery_status", "Battery status",
                    NotificationManager.IMPORTANCE_DEFAULT)
            noMan.createNotificationChannel(channel)

            val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse(context.getString(R.string.config_batteryStateUnknownUrl)))
            val pi = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE)

            val builder = Notification.Builder(context, channel.id)
                    .setAutoCancel(false)
                    .setContentTitle(
                            context.getString(R.string.battery_state_unknown_notification_title))
                    .setContentText(
                            context.getString(R.string.battery_state_unknown_notification_text))
                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOngoing(true)

            noMan.notify(TAG, ID, builder.build())
        } else {
            scheduleNotificationCancel()
        }
    }

    private fun scheduleNotificationCancel() {
        val r = {
            if (!stateUnknown) {
                noMan.cancel(ID)
            }
        }
        delayableExecutor.executeDelayed(r, DELAY_MILLIS)
    }
}

private const val TAG = "BatteryStateNotifier"
private const val ID = 666
private const val DELAY_MILLIS: Long = 4 * 60 * 60 * 1000
