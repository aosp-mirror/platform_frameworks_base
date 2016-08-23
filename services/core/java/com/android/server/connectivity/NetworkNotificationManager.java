/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.R;

import static android.net.ConnectivityManager.getNetworkTypeName;


public class NetworkNotificationManager {

    public static enum NotificationType { SIGN_IN, NO_INTERNET; };

    private static final String NOTIFICATION_ID = "CaptivePortal.Notification";

    private static final String TAG = NetworkNotificationManager.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;

    public NetworkNotificationManager(Context context, TelephonyManager telephonyManager) {
        mContext = context;
        mTelephonyManager = telephonyManager;
    }

    /**
     * Show or hide network provisioning notifications.
     *
     * We use notifications for two purposes: to notify that a network requires sign in
     * (NotificationType.SIGN_IN), or to notify that a network does not have Internet access
     * (NotificationType.NO_INTERNET). We display at most one notification per ID, so on a
     * particular network we can display the notification type that was most recently requested.
     * So for example if a captive portal fails to reply within a few seconds of connecting, we
     * might first display NO_INTERNET, and then when the captive portal check completes, display
     * SIGN_IN.
     *
     * @param id an identifier that uniquely identifies this notification.  This must match
     *         between show and hide calls.  We use the NetID value but for legacy callers
     *         we concatenate the range of types with the range of NetIDs.
     */
    public void setProvNotificationVisibleIntent(boolean visible, int id,
            NotificationType notifyType, int networkType, String extraInfo, PendingIntent intent,
            boolean highPriority) {
        if (VDBG || (DBG && visible)) {
            Slog.d(TAG, "setProvNotificationVisibleIntent " + notifyType + " visible=" + visible
                    + " networkType=" + getNetworkTypeName(networkType)
                    + " extraInfo=" + extraInfo + " highPriority=" + highPriority);
        }

        Resources r = Resources.getSystem();
        NotificationManager notificationManager = (NotificationManager) mContext
            .getSystemService(Context.NOTIFICATION_SERVICE);

        if (visible) {
            CharSequence title;
            CharSequence details;
            int icon;
            if (notifyType == NotificationType.NO_INTERNET &&
                    networkType == ConnectivityManager.TYPE_WIFI) {
                title = r.getString(R.string.wifi_no_internet, 0);
                details = r.getString(R.string.wifi_no_internet_detailed);
                icon = R.drawable.stat_notify_wifi_in_range;  // TODO: Need new icon.
            } else if (notifyType == NotificationType.SIGN_IN) {
                switch (networkType) {
                    case ConnectivityManager.TYPE_WIFI:
                        title = r.getString(R.string.wifi_available_sign_in, 0);
                        details = r.getString(R.string.network_available_sign_in_detailed,
                                extraInfo);
                        icon = R.drawable.stat_notify_wifi_in_range;
                        break;
                    case ConnectivityManager.TYPE_MOBILE:
                    case ConnectivityManager.TYPE_MOBILE_HIPRI:
                        title = r.getString(R.string.network_available_sign_in, 0);
                        // TODO: Change this to pull from NetworkInfo once a printable
                        // name has been added to it
                        details = mTelephonyManager.getNetworkOperatorName();
                        icon = R.drawable.stat_notify_rssi_in_range;
                        break;
                    default:
                        title = r.getString(R.string.network_available_sign_in, 0);
                        details = r.getString(R.string.network_available_sign_in_detailed,
                                extraInfo);
                        icon = R.drawable.stat_notify_rssi_in_range;
                        break;
                }
            } else {
                Slog.wtf(TAG, "Unknown notification type " + notifyType + "on network type "
                        + getNetworkTypeName(networkType));
                return;
            }

            Notification notification = new Notification.Builder(mContext)
                    .setWhen(0)
                    .setSmallIcon(icon)
                    .setAutoCancel(true)
                    .setTicker(title)
                    .setColor(mContext.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setContentTitle(title)
                    .setContentText(details)
                    .setContentIntent(intent)
                    .setLocalOnly(true)
                    .setPriority(highPriority ?
                            Notification.PRIORITY_HIGH :
                            Notification.PRIORITY_DEFAULT)
                    .setDefaults(highPriority ? Notification.DEFAULT_ALL : 0)
                    .setOnlyAlertOnce(true)
                    .build();

            try {
                notificationManager.notifyAsUser(NOTIFICATION_ID, id, notification, UserHandle.ALL);
            } catch (NullPointerException npe) {
                Slog.d(TAG, "setNotificationVisible: visible notificationManager npe=" + npe);
            }
        } else {
            try {
                notificationManager.cancelAsUser(NOTIFICATION_ID, id, UserHandle.ALL);
            } catch (NullPointerException npe) {
                Slog.d(TAG, "setNotificationVisible: cancel notificationManager npe=" + npe);
            }
        }
    }

    /**
     * Legacy provisioning notifications coming directly from DcTracker.
     */
    public void setProvNotificationVisible(boolean visible, int networkType, String action) {
        Intent intent = new Intent(action);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
        // Concatenate the range of types onto the range of NetIDs.
        int id = MAX_NET_ID + 1 + (networkType - ConnectivityManager.TYPE_NONE);
        mNotifier.setProvNotificationVisibleIntent(visible, id, NotificationType.SIGN_IN,
                networkType, null, pendingIntent, false);
    }
}
