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

@file:JvmName("VpnStatusObserver")

package com.android.systemui.statusbar.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.android.internal.messages.nano.SystemMessageProto
import com.android.internal.net.VpnConfig
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.SecurityController
import javax.inject.Inject

/**
 * Observes if a vpn connection is active and displays a notification to the user
 */
@SysUISingleton
class VpnStatusObserver @Inject constructor(
    context: Context,
    private val securityController: SecurityController
) : CoreStartable(context),
        SecurityController.SecurityControllerCallback {

    private var vpnConnected = false
    private val notificationManager = NotificationManager.from(context)
    private val notificationChannel = createNotificationChannel()
    private val vpnConnectedNotificationBuilder = createVpnConnectedNotificationBuilder()
    private val vpnDisconnectedNotification = createVpnDisconnectedNotification()

    private val vpnIconId: Int
        get() = if (securityController.isVpnBranded) {
            R.drawable.stat_sys_branded_vpn
        } else {
            R.drawable.stat_sys_vpn_ic
        }

    private val vpnName: String?
        get() = securityController.primaryVpnName ?: securityController.workProfileVpnName

    override fun start() {
        // register callback to vpn state changes
        securityController.addCallback(this)
    }

    override fun onStateChanged() {
        securityController.isVpnEnabled.let { newVpnConnected ->
            if (vpnConnected != newVpnConnected) {
                if (newVpnConnected) {
                    notifyVpnConnected()
                } else {
                    notifyVpnDisconnected()
                }
                vpnConnected = newVpnConnected
            }
        }
    }

    private fun notifyVpnConnected() = notificationManager.notify(
            NOTIFICATION_TAG,
            SystemMessageProto.SystemMessage.NOTE_VPN_STATUS,
            createVpnConnectedNotification()
    )

    private fun notifyVpnDisconnected() = notificationManager.run {
        // remove existing connected notification
        cancel(NOTIFICATION_TAG, SystemMessageProto.SystemMessage.NOTE_VPN_STATUS)
        // show the disconnected notification only for a short while
        notify(NOTIFICATION_TAG, SystemMessageProto.SystemMessage.NOTE_VPN_DISCONNECTED,
                vpnDisconnectedNotification)
    }

    private fun createNotificationChannel() =
            NotificationChannel(
                    NOTIFICATION_CHANNEL_TV_VPN,
                    NOTIFICATION_CHANNEL_TV_VPN,
                    NotificationManager.IMPORTANCE_HIGH
            ).also {
                notificationManager.createNotificationChannel(it)
            }

    private fun createVpnConnectedNotification() =
            vpnConnectedNotificationBuilder
                    .apply {
                        vpnName?.let {
                            setContentText(
                                    mContext.getString(
                                            R.string.notification_disclosure_vpn_text, it
                                    )
                            )
                        }
                    }
                    .build()

    private fun createVpnConnectedNotificationBuilder() =
            Notification.Builder(mContext, NOTIFICATION_CHANNEL_TV_VPN)
                    .setSmallIcon(vpnIconId)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .extend(Notification.TvExtender())
                    .setOngoing(true)
                    .setContentTitle(mContext.getString(R.string.notification_vpn_connected))
                    .setContentIntent(VpnConfig.getIntentForStatusPanel(mContext))

    private fun createVpnDisconnectedNotification() =
            Notification.Builder(mContext, NOTIFICATION_CHANNEL_TV_VPN)
                    .setSmallIcon(vpnIconId)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .extend(Notification.TvExtender())
                    .setTimeoutAfter(VPN_DISCONNECTED_NOTIFICATION_TIMEOUT_MS)
                    .setContentTitle(mContext.getString(R.string.notification_vpn_disconnected))
                    .build()

    companion object {
        const val NOTIFICATION_CHANNEL_TV_VPN = "VPN Status"
        val NOTIFICATION_TAG: String = VpnStatusObserver::class.java.simpleName

        private const val TAG = "TvVpnNotification"
        private const val VPN_DISCONNECTED_NOTIFICATION_TIMEOUT_MS = 5_000L
    }
}