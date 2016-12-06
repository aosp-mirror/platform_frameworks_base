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
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.NetworkCapabilities;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.R;

import static android.net.NetworkCapabilities.*;


public class NetworkNotificationManager {

    public static enum NotificationType { SIGN_IN, NO_INTERNET, LOST_INTERNET, NETWORK_SWITCH };

    private static final String NOTIFICATION_ID = "Connectivity.Notification";

    private static final String TAG = NetworkNotificationManager.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final NotificationManager mNotificationManager;

    public NetworkNotificationManager(Context c, TelephonyManager t, NotificationManager n) {
        mContext = c;
        mTelephonyManager = t;
        mNotificationManager = n;
    }

    // TODO: deal more gracefully with multi-transport networks.
    private static int getFirstTransportType(NetworkAgentInfo nai) {
        for (int i = 0; i < 64; i++) {
            if (nai.networkCapabilities.hasTransport(i)) return i;
        }
        return -1;
    }

    private static String getTransportName(int transportType) {
        Resources r = Resources.getSystem();
        String[] networkTypes = r.getStringArray(R.array.network_switch_type_name);
        try {
            return networkTypes[transportType];
        } catch (IndexOutOfBoundsException e) {
            return r.getString(R.string.network_switch_type_name_unknown);
        }
    }

    private static int getIcon(int transportType) {
        return (transportType == TRANSPORT_WIFI) ?
                R.drawable.stat_notify_wifi_in_range :  // TODO: Distinguish ! from ?.
                R.drawable.stat_notify_rssi_in_range;
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
     * @param nai the network with which the notification is associated. For a SIGN_IN, NO_INTERNET,
     *         or LOST_INTERNET notification, this is the network we're connecting to. For a
     *         NETWORK_SWITCH notification it's the network that we switched from. When this network
     *         disconnects the notification is removed.
     * @param switchToNai for a NETWORK_SWITCH notification, the network we are switching to. Null
     *         in all other cases. Only used to determine the text of the notification.
     */
    public void showNotification(int id, NotificationType notifyType, NetworkAgentInfo nai,
            NetworkAgentInfo switchToNai, PendingIntent intent, boolean highPriority) {
        int transportType;
        String extraInfo;
        if (nai != null) {
            transportType = getFirstTransportType(nai);
            extraInfo = nai.networkInfo.getExtraInfo();
            // Only notify for Internet-capable networks.
            if (!nai.networkCapabilities.hasCapability(NET_CAPABILITY_INTERNET)) return;
        } else {
            // Legacy notifications.
            transportType = TRANSPORT_CELLULAR;
            extraInfo = null;
        }

        if (DBG) {
            Slog.d(TAG, "showNotification " + notifyType
                    + " transportType=" + getTransportName(transportType)
                    + " extraInfo=" + extraInfo + " highPriority=" + highPriority);
        }

        Resources r = Resources.getSystem();
        CharSequence title;
        CharSequence details;
        int icon = getIcon(transportType);
        if (notifyType == NotificationType.NO_INTERNET && transportType == TRANSPORT_WIFI) {
            title = r.getString(R.string.wifi_no_internet, 0);
            details = r.getString(R.string.wifi_no_internet_detailed);
        } else if (notifyType == NotificationType.LOST_INTERNET &&
                transportType == TRANSPORT_WIFI) {
            title = r.getString(R.string.wifi_no_internet, 0);
            details = r.getString(R.string.wifi_no_internet_detailed);
        } else if (notifyType == NotificationType.SIGN_IN) {
            switch (transportType) {
                case TRANSPORT_WIFI:
                    title = r.getString(R.string.wifi_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed, extraInfo);
                    break;
                case TRANSPORT_CELLULAR:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    // TODO: Change this to pull from NetworkInfo once a printable
                    // name has been added to it
                    details = mTelephonyManager.getNetworkOperatorName();
                    break;
                default:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed, extraInfo);
                    break;
            }
        } else if (notifyType == NotificationType.NETWORK_SWITCH) {
            String fromTransport = getTransportName(transportType);
            String toTransport = getTransportName(getFirstTransportType(switchToNai));
            title = r.getString(R.string.network_switch_metered, toTransport);
            details = r.getString(R.string.network_switch_metered_detail, toTransport,
                    fromTransport);
        } else {
            Slog.wtf(TAG, "Unknown notification type " + notifyType + "on network transport "
                    + getTransportName(transportType));
            return;
        }

        Notification.Builder builder = new Notification.Builder(mContext)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(notifyType == NotificationType.NETWORK_SWITCH)
                .setSmallIcon(icon)
                .setAutoCancel(true)
                .setTicker(title)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentIntent(intent)
                .setLocalOnly(true)
                .setPriority(highPriority ?
                        Notification.PRIORITY_HIGH :
                        Notification.PRIORITY_DEFAULT)
                .setDefaults(highPriority ? Notification.DEFAULT_ALL : 0)
                .setOnlyAlertOnce(true);

        if (notifyType == NotificationType.NETWORK_SWITCH) {
            builder.setStyle(new Notification.BigTextStyle().bigText(details));
        } else {
            builder.setContentText(details);
        }

        Notification notification = builder.build();

        try {
            mNotificationManager.notifyAsUser(NOTIFICATION_ID, id, notification, UserHandle.ALL);
        } catch (NullPointerException npe) {
            Slog.d(TAG, "setNotificationVisible: visible notificationManager npe=" + npe);
        }
    }

    public void clearNotification(int id) {
        if (DBG) {
            Slog.d(TAG, "clearNotification id=" + id);
        }
        try {
            mNotificationManager.cancelAsUser(NOTIFICATION_ID, id, UserHandle.ALL);
        } catch (NullPointerException npe) {
            Slog.d(TAG, "setNotificationVisible: cancel notificationManager npe=" + npe);
        }
    }

    /**
     * Legacy provisioning notifications coming directly from DcTracker.
     */
    public void setProvNotificationVisible(boolean visible, int id, String action) {
        if (visible) {
            Intent intent = new Intent(action);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
            showNotification(id, NotificationType.SIGN_IN, null, null, pendingIntent, false);
        } else {
            clearNotification(id);
        }
    }

    public void showToast(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        String fromTransport = getTransportName(getFirstTransportType(fromNai));
        String toTransport = getTransportName(getFirstTransportType(toNai));
        String text = mContext.getResources().getString(
                R.string.network_switch_metered_toast, fromTransport, toTransport);
        Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
    }
}
