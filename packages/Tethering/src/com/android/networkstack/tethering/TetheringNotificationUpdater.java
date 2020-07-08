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

package com.android.networkstack.tethering;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.text.TextUtils.isEmpty;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.SparseArray;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class to display tethering-related notifications.
 *
 * <p>This class is not thread safe, it is intended to be used only from the tethering handler
 * thread. However the constructor is an exception, as it is called on another thread ;
 * therefore for thread safety all members of this class MUST either be final or initialized
 * to their default value (0, false or null).
 *
 * @hide
 */
public class TetheringNotificationUpdater {
    private static final String TAG = TetheringNotificationUpdater.class.getSimpleName();
    private static final String CHANNEL_ID = "TETHERING_STATUS";
    private static final String WIFI_DOWNSTREAM = "WIFI";
    private static final String USB_DOWNSTREAM = "USB";
    private static final String BLUETOOTH_DOWNSTREAM = "BT";
    @VisibleForTesting
    static final String ACTION_DISABLE_TETHERING =
            "com.android.server.connectivity.tethering.DISABLE_TETHERING";
    private static final boolean NOTIFY_DONE = true;
    private static final boolean NO_NOTIFY = false;
    @VisibleForTesting
    static final int EVENT_SHOW_NO_UPSTREAM = 1;
    // Id to update and cancel restricted notification. Must be unique within the tethering app.
    @VisibleForTesting
    static final int RESTRICTED_NOTIFICATION_ID = 1001;
    // Id to update and cancel no upstream notification. Must be unique within the tethering app.
    @VisibleForTesting
    static final int NO_UPSTREAM_NOTIFICATION_ID = 1002;
    // Id to update and cancel roaming notification. Must be unique within the tethering app.
    @VisibleForTesting
    static final int ROAMING_NOTIFICATION_ID = 1003;
    @VisibleForTesting
    static final int NO_ICON_ID = 0;
    @VisibleForTesting
    static final int DOWNSTREAM_NONE = 0;
    // Refer to TelephonyManager#getSimCarrierId for more details about carrier id.
    @VisibleForTesting
    static final int VERIZON_CARRIER_ID = 1839;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final NotificationChannel mChannel;
    private final Handler mHandler;

    // WARNING : the constructor is called on a different thread. Thread safety therefore
    // relies on these values being initialized to 0, false or null, and not any other value. If you
    // need to change this, you will need to change the thread where the constructor is invoked, or
    // to introduce synchronization.
    // Downstream type is one of ConnectivityManager.TETHERING_* constants, 0 1 or 2.
    // This value has to be made 1 2 and 4, and OR'd with the others.
    private int mDownstreamTypesMask = DOWNSTREAM_NONE;
    private boolean mNoUpstream = false;
    private boolean mRoaming = false;

    // WARNING : this value is not able to being initialized to 0 and must have volatile because
    // telephony service is not guaranteed that is up before tethering service starts. If telephony
    // is up later than tethering, TetheringNotificationUpdater will use incorrect and valid
    // subscription id(0) to query resources. Therefore, initialized subscription id must be
    // INVALID_SUBSCRIPTION_ID.
    private volatile int mActiveDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            RESTRICTED_NOTIFICATION_ID,
            NO_UPSTREAM_NOTIFICATION_ID,
            ROAMING_NOTIFICATION_ID
    })
    @interface NotificationId {}

    private static final class MccMncOverrideInfo {
        public final String visitedMccMnc;
        public final int homeMcc;
        public final int homeMnc;
        MccMncOverrideInfo(String visitedMccMnc, int mcc, int mnc) {
            this.visitedMccMnc = visitedMccMnc;
            this.homeMcc = mcc;
            this.homeMnc = mnc;
        }
    }

    private static final SparseArray<MccMncOverrideInfo> sCarrierIdToMccMnc = new SparseArray<>();

    static {
        sCarrierIdToMccMnc.put(VERIZON_CARRIER_ID, new MccMncOverrideInfo("20404", 311, 480));
    }

    public TetheringNotificationUpdater(@NonNull final Context context,
            @NonNull final Looper looper) {
        mContext = context;
        mNotificationManager = (NotificationManager) context.createContextAsUser(UserHandle.ALL, 0)
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mChannel = new NotificationChannel(
                CHANNEL_ID,
                context.getResources().getString(R.string.notification_channel_tethering_status),
                NotificationManager.IMPORTANCE_LOW);
        mNotificationManager.createNotificationChannel(mChannel);
        mHandler = new NotificationHandler(looper);
    }

    private class NotificationHandler extends Handler {
        NotificationHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case EVENT_SHOW_NO_UPSTREAM:
                    notifyTetheringNoUpstream();
                    break;
            }
        }
    }

    /** Called when downstream has changed */
    public void onDownstreamChanged(@IntRange(from = 0, to = 7) final int downstreamTypesMask) {
        updateActiveNotifications(
                mActiveDataSubId, downstreamTypesMask, mNoUpstream, mRoaming);
    }

    /** Called when active data subscription id changed */
    public void onActiveDataSubscriptionIdChanged(final int subId) {
        updateActiveNotifications(subId, mDownstreamTypesMask, mNoUpstream, mRoaming);
    }

    /** Called when upstream network capabilities changed */
    public void onUpstreamCapabilitiesChanged(@Nullable final NetworkCapabilities capabilities) {
        final boolean isNoUpstream = (capabilities == null);
        final boolean isRoaming = capabilities != null
                && !capabilities.hasCapability(NET_CAPABILITY_NOT_ROAMING);
        updateActiveNotifications(
                mActiveDataSubId, mDownstreamTypesMask, isNoUpstream, isRoaming);
    }

    @NonNull
    @VisibleForTesting
    final Handler getHandler() {
        return mHandler;
    }

    @NonNull
    @VisibleForTesting
    Resources getResourcesForSubId(@NonNull final Context context, final int subId) {
        final Resources res = SubscriptionManager.getResourcesForSubId(context, subId);
        final TelephonyManager tm =
                ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE))
                        .createForSubscriptionId(mActiveDataSubId);
        final int carrierId = tm.getSimCarrierId();
        final String mccmnc = tm.getSimOperator();
        final MccMncOverrideInfo overrideInfo = sCarrierIdToMccMnc.get(carrierId);
        if (overrideInfo != null && overrideInfo.visitedMccMnc.equals(mccmnc)) {
            // Re-configure MCC/MNC value to specific carrier to get right resources.
            final Configuration config = res.getConfiguration();
            config.mcc = overrideInfo.homeMcc;
            config.mnc = overrideInfo.homeMnc;
            return context.createConfigurationContext(config).getResources();
        }
        return res;
    }

    private void updateActiveNotifications(final int subId, final int downstreamTypes,
            final boolean noUpstream, final boolean isRoaming) {
        final boolean tetheringActiveChanged =
                (downstreamTypes == DOWNSTREAM_NONE) != (mDownstreamTypesMask == DOWNSTREAM_NONE);
        final boolean subIdChanged = subId != mActiveDataSubId;
        final boolean upstreamChanged = noUpstream != mNoUpstream;
        final boolean roamingChanged = isRoaming != mRoaming;
        final boolean updateAll = tetheringActiveChanged || subIdChanged;
        mActiveDataSubId = subId;
        mDownstreamTypesMask = downstreamTypes;
        mNoUpstream = noUpstream;
        mRoaming = isRoaming;

        if (updateAll || upstreamChanged) updateNoUpstreamNotification();
        if (updateAll || roamingChanged) updateRoamingNotification();
    }

    private void updateNoUpstreamNotification() {
        final boolean tetheringInactive = mDownstreamTypesMask == DOWNSTREAM_NONE;

        if (tetheringInactive || !mNoUpstream || setupNoUpstreamNotification() == NO_NOTIFY) {
            clearNotification(NO_UPSTREAM_NOTIFICATION_ID);
            mHandler.removeMessages(EVENT_SHOW_NO_UPSTREAM);
        }
    }

    private void updateRoamingNotification() {
        final boolean tetheringInactive = mDownstreamTypesMask == DOWNSTREAM_NONE;

        if (tetheringInactive || !mRoaming || setupRoamingNotification() == NO_NOTIFY) {
            clearNotification(ROAMING_NOTIFICATION_ID);
        }
    }

    @VisibleForTesting
    void tetheringRestrictionLifted() {
        clearNotification(RESTRICTED_NOTIFICATION_ID);
    }

    private void clearNotification(@NotificationId final int id) {
        mNotificationManager.cancel(null /* tag */, id);
    }

    @VisibleForTesting
    static String getSettingsPackageName(@NonNull final PackageManager pm) {
        final Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
        final ComponentName settingsComponent = settingsIntent.resolveActivity(pm);
        return settingsComponent != null
                ? settingsComponent.getPackageName() : "com.android.settings";
    }

    @VisibleForTesting
    void notifyTetheringDisabledByRestriction() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final String title = res.getString(R.string.disable_tether_notification_title);
        final String message = res.getString(R.string.disable_tether_notification_message);
        if (isEmpty(title) || isEmpty(message)) return;

        final PendingIntent pi = PendingIntent.getActivity(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                new Intent(Settings.ACTION_TETHER_SETTINGS)
                        .setPackage(getSettingsPackageName(mContext.getPackageManager()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
                null /* options */);

        showNotification(R.drawable.stat_sys_tether_general, title, message,
                RESTRICTED_NOTIFICATION_ID, false /* ongoing */, pi, new Action[0]);
    }

    private void notifyTetheringNoUpstream() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final String title = res.getString(R.string.no_upstream_notification_title);
        final String message = res.getString(R.string.no_upstream_notification_message);
        final String disableButton =
                res.getString(R.string.no_upstream_notification_disable_button);
        if (isEmpty(title) || isEmpty(message) || isEmpty(disableButton)) return;

        final Intent intent = new Intent(ACTION_DISABLE_TETHERING);
        intent.setPackage(mContext.getPackageName());
        final PendingIntent pi = PendingIntent.getBroadcast(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                intent,
                PendingIntent.FLAG_IMMUTABLE);
        final Action action = new Action.Builder(NO_ICON_ID, disableButton, pi).build();

        showNotification(R.drawable.stat_sys_tether_general, title, message,
                NO_UPSTREAM_NOTIFICATION_ID, true /* ongoing */, null /* pendingIntent */, action);
    }

    private boolean setupRoamingNotification() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final boolean upstreamRoamingNotification =
                res.getBoolean(R.bool.config_upstream_roaming_notification);

        if (!upstreamRoamingNotification) return NO_NOTIFY;

        final String title = res.getString(R.string.upstream_roaming_notification_title);
        final String message = res.getString(R.string.upstream_roaming_notification_message);
        if (isEmpty(title) || isEmpty(message)) return NO_NOTIFY;

        final PendingIntent pi = PendingIntent.getActivity(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                new Intent(Settings.ACTION_TETHER_SETTINGS)
                        .setPackage(getSettingsPackageName(mContext.getPackageManager()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
                null /* options */);

        showNotification(R.drawable.stat_sys_tether_general, title, message,
                ROAMING_NOTIFICATION_ID, true /* ongoing */, pi, new Action[0]);
        return NOTIFY_DONE;
    }

    private boolean setupNoUpstreamNotification() {
        final Resources res = getResourcesForSubId(mContext, mActiveDataSubId);
        final int delayToShowUpstreamNotification =
                res.getInteger(R.integer.delay_to_show_no_upstream_after_no_backhaul);

        if (delayToShowUpstreamNotification < 0) return NO_NOTIFY;

        mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_SHOW_NO_UPSTREAM),
                delayToShowUpstreamNotification);
        return NOTIFY_DONE;
    }

    private void showNotification(@DrawableRes final int iconId, @NonNull final String title,
            @NonNull final String message, @NotificationId final int id, final boolean ongoing,
            @Nullable PendingIntent pi, @NonNull final Action... actions) {
        final Notification notification =
                new Notification.Builder(mContext, mChannel.getId())
                        .setSmallIcon(iconId)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setOngoing(ongoing)
                        .setColor(mContext.getColor(
                                android.R.color.system_notification_accent_color))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setContentIntent(pi)
                        .setActions(actions)
                        .build();

        mNotificationManager.notify(null /* tag */, id, notification);
    }
}
