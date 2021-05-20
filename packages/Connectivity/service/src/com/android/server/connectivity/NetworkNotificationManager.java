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

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.ConnectivityResources;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.wifi.WifiInfo;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.widget.Toast;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;

public class NetworkNotificationManager {


    public static enum NotificationType {
        LOST_INTERNET(SystemMessage.NOTE_NETWORK_LOST_INTERNET),
        NETWORK_SWITCH(SystemMessage.NOTE_NETWORK_SWITCH),
        NO_INTERNET(SystemMessage.NOTE_NETWORK_NO_INTERNET),
        PARTIAL_CONNECTIVITY(SystemMessage.NOTE_NETWORK_PARTIAL_CONNECTIVITY),
        SIGN_IN(SystemMessage.NOTE_NETWORK_SIGN_IN),
        PRIVATE_DNS_BROKEN(SystemMessage.NOTE_NETWORK_PRIVATE_DNS_BROKEN);

        public final int eventId;

        NotificationType(int eventId) {
            this.eventId = eventId;
            Holder.sIdToTypeMap.put(eventId, this);
        }

        private static class Holder {
            private static SparseArray<NotificationType> sIdToTypeMap = new SparseArray<>();
        }

        public static NotificationType getFromId(int id) {
            return Holder.sIdToTypeMap.get(id);
        }
    };

    private static final String TAG = NetworkNotificationManager.class.getSimpleName();
    private static final boolean DBG = true;

    // Notification channels used by ConnectivityService mainline module, it should be aligned with
    // SystemNotificationChannels so the channels are the same as the ones used as the system
    // server.
    public static final String NOTIFICATION_CHANNEL_NETWORK_STATUS = "NETWORK_STATUS";
    public static final String NOTIFICATION_CHANNEL_NETWORK_ALERTS = "NETWORK_ALERTS";

    // The context is for the current user (system server)
    private final Context mContext;
    private final Resources mResources;
    private final TelephonyManager mTelephonyManager;
    // The notification manager is created from a context for User.ALL, so notifications
    // will be sent to all users.
    private final NotificationManager mNotificationManager;
    // Tracks the types of notifications managed by this instance, from creation to cancellation.
    private final SparseIntArray mNotificationTypeMap;

    public NetworkNotificationManager(@NonNull final Context c, @NonNull final TelephonyManager t) {
        mContext = c;
        mTelephonyManager = t;
        mNotificationManager =
                (NotificationManager) c.createContextAsUser(UserHandle.ALL, 0 /* flags */)
                        .getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationTypeMap = new SparseIntArray();
        mResources = new ConnectivityResources(mContext).get();
    }

    @VisibleForTesting
    protected static int approximateTransportType(NetworkAgentInfo nai) {
        return nai.isVPN() ? TRANSPORT_VPN : getFirstTransportType(nai);
    }

    // TODO: deal more gracefully with multi-transport networks.
    private static int getFirstTransportType(NetworkAgentInfo nai) {
        // TODO: The range is wrong, the safer and correct way is to change the range from
        // MIN_TRANSPORT to MAX_TRANSPORT.
        for (int i = 0; i < 64; i++) {
            if (nai.networkCapabilities.hasTransport(i)) return i;
        }
        return -1;
    }

    private String getTransportName(final int transportType) {
        String[] networkTypes = mResources.getStringArray(R.array.network_switch_type_name);
        try {
            return networkTypes[transportType];
        } catch (IndexOutOfBoundsException e) {
            return mResources.getString(R.string.network_switch_type_name_unknown);
        }
    }

    private static int getIcon(int transportType) {
        return (transportType == TRANSPORT_WIFI)
                ? R.drawable.stat_notify_wifi_in_range  // TODO: Distinguish ! from ?.
                : R.drawable.stat_notify_rssi_in_range;
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
     * @param notifyType the type of the notification.
     * @param nai the network with which the notification is associated. For a SIGN_IN, NO_INTERNET,
     *         or LOST_INTERNET notification, this is the network we're connecting to. For a
     *         NETWORK_SWITCH notification it's the network that we switched from. When this network
     *         disconnects the notification is removed.
     * @param switchToNai for a NETWORK_SWITCH notification, the network we are switching to. Null
     *         in all other cases. Only used to determine the text of the notification.
     */
    public void showNotification(int id, NotificationType notifyType, NetworkAgentInfo nai,
            NetworkAgentInfo switchToNai, PendingIntent intent, boolean highPriority) {
        final String tag = tagFor(id);
        final int eventId = notifyType.eventId;
        final int transportType;
        final CharSequence name;
        if (nai != null) {
            transportType = approximateTransportType(nai);
            final String extraInfo = nai.networkInfo.getExtraInfo();
            if (nai.linkProperties != null && nai.linkProperties.getCaptivePortalData() != null
                    && !TextUtils.isEmpty(nai.linkProperties.getCaptivePortalData()
                    .getVenueFriendlyName())) {
                name = nai.linkProperties.getCaptivePortalData().getVenueFriendlyName();
            } else {
                name = TextUtils.isEmpty(extraInfo)
                        ? WifiInfo.sanitizeSsid(nai.networkCapabilities.getSsid()) : extraInfo;
            }
            // Only notify for Internet-capable networks.
            if (!nai.networkCapabilities.hasCapability(NET_CAPABILITY_INTERNET)) return;
        } else {
            // Legacy notifications.
            transportType = TRANSPORT_CELLULAR;
            name = "";
        }

        // Clear any previous notification with lower priority, otherwise return. http://b/63676954.
        // A new SIGN_IN notification with a new intent should override any existing one.
        final int previousEventId = mNotificationTypeMap.get(id);
        final NotificationType previousNotifyType = NotificationType.getFromId(previousEventId);
        if (priority(previousNotifyType) > priority(notifyType)) {
            Log.d(TAG, String.format(
                    "ignoring notification %s for network %s with existing notification %s",
                    notifyType, id, previousNotifyType));
            return;
        }
        clearNotification(id);

        if (DBG) {
            Log.d(TAG, String.format(
                    "showNotification tag=%s event=%s transport=%s name=%s highPriority=%s",
                    tag, nameOf(eventId), getTransportName(transportType), name, highPriority));
        }

        final Resources r = mResources;
        final CharSequence title;
        final CharSequence details;
        Icon icon = Icon.createWithResource(r, getIcon(transportType));
        if (notifyType == NotificationType.NO_INTERNET && transportType == TRANSPORT_WIFI) {
            title = r.getString(R.string.wifi_no_internet, name);
            details = r.getString(R.string.wifi_no_internet_detailed);
        } else if (notifyType == NotificationType.PRIVATE_DNS_BROKEN) {
            if (transportType == TRANSPORT_CELLULAR) {
                title = r.getString(R.string.mobile_no_internet);
            } else if (transportType == TRANSPORT_WIFI) {
                title = r.getString(R.string.wifi_no_internet, name);
            } else {
                title = r.getString(R.string.other_networks_no_internet);
            }
            details = r.getString(R.string.private_dns_broken_detailed);
        } else if (notifyType == NotificationType.PARTIAL_CONNECTIVITY
                && transportType == TRANSPORT_WIFI) {
            title = r.getString(R.string.network_partial_connectivity, name);
            details = r.getString(R.string.network_partial_connectivity_detailed);
        } else if (notifyType == NotificationType.LOST_INTERNET &&
                transportType == TRANSPORT_WIFI) {
            title = r.getString(R.string.wifi_no_internet, name);
            details = r.getString(R.string.wifi_no_internet_detailed);
        } else if (notifyType == NotificationType.SIGN_IN) {
            switch (transportType) {
                case TRANSPORT_WIFI:
                    title = r.getString(R.string.wifi_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed, name);
                    break;
                case TRANSPORT_CELLULAR:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    // TODO: Change this to pull from NetworkInfo once a printable
                    // name has been added to it
                    NetworkSpecifier specifier = nai.networkCapabilities.getNetworkSpecifier();
                    int subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
                    if (specifier instanceof TelephonyNetworkSpecifier) {
                        subId = ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
                    }

                    details = mTelephonyManager.createForSubscriptionId(subId)
                            .getNetworkOperatorName();
                    break;
                default:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed, name);
                    break;
            }
        } else if (notifyType == NotificationType.NETWORK_SWITCH) {
            String fromTransport = getTransportName(transportType);
            String toTransport = getTransportName(approximateTransportType(switchToNai));
            title = r.getString(R.string.network_switch_metered, toTransport);
            details = r.getString(R.string.network_switch_metered_detail, toTransport,
                    fromTransport);
        } else if (notifyType == NotificationType.NO_INTERNET
                    || notifyType == NotificationType.PARTIAL_CONNECTIVITY) {
            // NO_INTERNET and PARTIAL_CONNECTIVITY notification for non-WiFi networks
            // are sent, but they are not implemented yet.
            return;
        } else {
            Log.wtf(TAG, "Unknown notification type " + notifyType + " on network transport "
                    + getTransportName(transportType));
            return;
        }
        // When replacing an existing notification for a given network, don't alert, just silently
        // update the existing notification. Note that setOnlyAlertOnce() will only work for the
        // same id, and the id used here is the NotificationType which is different in every type of
        // notification. This is required because the notification metrics only track the ID but not
        // the tag.
        final boolean hasPreviousNotification = previousNotifyType != null;
        final String channelId = (highPriority && !hasPreviousNotification)
                ? NOTIFICATION_CHANNEL_NETWORK_ALERTS : NOTIFICATION_CHANNEL_NETWORK_STATUS;
        Notification.Builder builder = new Notification.Builder(mContext, channelId)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(notifyType == NotificationType.NETWORK_SWITCH)
                .setSmallIcon(icon)
                .setAutoCancel(true)
                .setTicker(title)
                .setColor(mContext.getColor(android.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentIntent(intent)
                .setLocalOnly(true)
                .setOnlyAlertOnce(true);

        if (notifyType == NotificationType.NETWORK_SWITCH) {
            builder.setStyle(new Notification.BigTextStyle().bigText(details));
        } else {
            builder.setContentText(details);
        }

        if (notifyType == NotificationType.SIGN_IN) {
            builder.extend(new Notification.TvExtender().setChannelId(channelId));
        }

        Notification notification = builder.build();

        mNotificationTypeMap.put(id, eventId);
        try {
            mNotificationManager.notify(tag, eventId, notification);
        } catch (NullPointerException npe) {
            Log.d(TAG, "setNotificationVisible: visible notificationManager error", npe);
        }
    }

    /**
     * Clear the notification with the given id, only if it matches the given type.
     */
    public void clearNotification(int id, NotificationType notifyType) {
        final int previousEventId = mNotificationTypeMap.get(id);
        final NotificationType previousNotifyType = NotificationType.getFromId(previousEventId);
        if (notifyType != previousNotifyType) {
            return;
        }
        clearNotification(id);
    }

    public void clearNotification(int id) {
        if (mNotificationTypeMap.indexOfKey(id) < 0) {
            return;
        }
        final String tag = tagFor(id);
        final int eventId = mNotificationTypeMap.get(id);
        if (DBG) {
            Log.d(TAG, String.format("clearing notification tag=%s event=%s", tag,
                   nameOf(eventId)));
        }
        try {
            mNotificationManager.cancel(tag, eventId);
        } catch (NullPointerException npe) {
            Log.d(TAG, String.format(
                    "failed to clear notification tag=%s event=%s", tag, nameOf(eventId)), npe);
        }
        mNotificationTypeMap.delete(id);
    }

    /**
     * Legacy provisioning notifications coming directly from DcTracker.
     */
    public void setProvNotificationVisible(boolean visible, int id, String action) {
        if (visible) {
            // For legacy purposes, action is sent as the action + the phone ID from DcTracker.
            // Split the string here and send the phone ID as an extra instead.
            String[] splitAction = action.split(":");
            Intent intent = new Intent(splitAction[0]);
            try {
                intent.putExtra("provision.phone.id", Integer.parseInt(splitAction[1]));
            } catch (NumberFormatException ignored) { }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    mContext, 0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE);
            showNotification(id, NotificationType.SIGN_IN, null, null, pendingIntent, false);
        } else {
            clearNotification(id);
        }
    }

    public void showToast(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        String fromTransport = getTransportName(approximateTransportType(fromNai));
        String toTransport = getTransportName(approximateTransportType(toNai));
        String text = mResources.getString(
                R.string.network_switch_metered_toast, fromTransport, toTransport);
        Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
    }

    @VisibleForTesting
    static String tagFor(int id) {
        return String.format("ConnectivityNotification:%d", id);
    }

    @VisibleForTesting
    static String nameOf(int eventId) {
        NotificationType t = NotificationType.getFromId(eventId);
        return (t != null) ? t.name() : "UNKNOWN";
    }

    /**
     * A notification with a higher number will take priority over a notification with a lower
     * number.
     */
    private static int priority(NotificationType t) {
        if (t == null) {
            return 0;
        }
        switch (t) {
            case SIGN_IN:
                return 6;
            case PARTIAL_CONNECTIVITY:
                return 5;
            case PRIVATE_DNS_BROKEN:
                return 4;
            case NO_INTERNET:
                return 3;
            case NETWORK_SWITCH:
                return 2;
            case LOST_INTERNET:
                return 1;
            default:
                return 0;
        }
    }
}
