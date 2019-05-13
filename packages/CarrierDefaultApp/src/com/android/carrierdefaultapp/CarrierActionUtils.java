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
package com.android.carrierdefaultapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.telephony.PhoneConstants;
import com.android.carrierdefaultapp.R;
/**
 * This util class provides common logic for carrier actions
 */
public class CarrierActionUtils {
    private static final String TAG = CarrierActionUtils.class.getSimpleName();

    private static final String PORTAL_NOTIFICATION_TAG = "CarrierDefault.Portal.Notification";
    private static final String NO_DATA_NOTIFICATION_TAG = "CarrierDefault.NoData.Notification";
    private static final String NOTIFICATION_CHANNEL_ID_MOBILE_DATA_STATUS = "mobile_data_status";
    private static final int PORTAL_NOTIFICATION_ID = 0;
    private static final int NO_DATA_NOTIFICATION_ID = 1;
    private static boolean ENABLE = true;

    // A list of supported carrier action idx
    public static final int CARRIER_ACTION_ENABLE_METERED_APNS               = 0;
    public static final int CARRIER_ACTION_DISABLE_METERED_APNS              = 1;
    public static final int CARRIER_ACTION_DISABLE_RADIO                     = 2;
    public static final int CARRIER_ACTION_ENABLE_RADIO                      = 3;
    public static final int CARRIER_ACTION_SHOW_PORTAL_NOTIFICATION          = 4;
    public static final int CARRIER_ACTION_SHOW_NO_DATA_SERVICE_NOTIFICATION = 5;
    public static final int CARRIER_ACTION_CANCEL_ALL_NOTIFICATIONS          = 6;
    public static final int CARRIER_ACTION_ENABLE_DEFAULT_URL_HANDLER        = 7;
    public static final int CARRIER_ACTION_DISABLE_DEFAULT_URL_HANDLER       = 8;
    public static final int CARRIER_ACTION_REGISTER_DEFAULT_NETWORK_AVAIL    = 9;
    public static final int CARRIER_ACTION_DEREGISTER_DEFAULT_NETWORK_AVAIL  = 10;
    public static final int CARRIER_ACTION_RESET_ALL                         = 11;

    public static void applyCarrierAction(int actionIdx, Intent intent, Context context) {
        switch (actionIdx) {
            case CARRIER_ACTION_ENABLE_METERED_APNS:
                onEnableAllMeteredApns(intent, context);
                break;
            case CARRIER_ACTION_DISABLE_METERED_APNS:
                onDisableAllMeteredApns(intent, context);
                break;
            case CARRIER_ACTION_DISABLE_RADIO:
                onDisableRadio(intent, context);
                break;
            case CARRIER_ACTION_ENABLE_RADIO:
                onEnableRadio(intent, context);
                break;
            case CARRIER_ACTION_SHOW_PORTAL_NOTIFICATION:
                onShowCaptivePortalNotification(intent, context);
                break;
            case CARRIER_ACTION_SHOW_NO_DATA_SERVICE_NOTIFICATION:
                onShowNoDataServiceNotification(context);
                break;
            case CARRIER_ACTION_CANCEL_ALL_NOTIFICATIONS:
                onCancelAllNotifications(context);
                break;
            case CARRIER_ACTION_ENABLE_DEFAULT_URL_HANDLER:
                onEnableDefaultURLHandler(context);
                break;
            case CARRIER_ACTION_DISABLE_DEFAULT_URL_HANDLER:
                onDisableDefaultURLHandler(context);
                break;
            case CARRIER_ACTION_REGISTER_DEFAULT_NETWORK_AVAIL:
                onRegisterDefaultNetworkAvail(intent, context);
                break;
            case CARRIER_ACTION_DEREGISTER_DEFAULT_NETWORK_AVAIL:
                onDeregisterDefaultNetworkAvail(intent, context);
                break;
            case CARRIER_ACTION_RESET_ALL:
                onResetAllCarrierActions(intent, context);
                break;
            default:
                loge("unsupported carrier action index: " + actionIdx);
        }
    }

    private static void onDisableAllMeteredApns(Intent intent, Context context) {
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        logd("onDisableAllMeteredApns subId: " + subId);
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        telephonyMgr.carrierActionSetMeteredApnsEnabled(subId, !ENABLE);
    }

    private static void onEnableAllMeteredApns(Intent intent, Context context) {
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        logd("onEnableAllMeteredApns subId: " + subId);
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        telephonyMgr.carrierActionSetMeteredApnsEnabled(subId, ENABLE);
    }

    private static void onEnableDefaultURLHandler(Context context) {
        logd("onEnableDefaultURLHandler");
        final PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(
                new ComponentName(context, CaptivePortalLoginActivity.getAlias(context)),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }

    private static void onDisableDefaultURLHandler(Context context) {
        logd("onDisableDefaultURLHandler");
        final PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(
                new ComponentName(context, CaptivePortalLoginActivity.getAlias(context)),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    private static void onRegisterDefaultNetworkAvail(Intent intent, Context context) {
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        logd("onRegisterDefaultNetworkAvail subId: " + subId);
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        telephonyMgr.carrierActionReportDefaultNetworkStatus(subId, true);
    }

    private static void onDeregisterDefaultNetworkAvail(Intent intent, Context context) {
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        logd("onDeregisterDefaultNetworkAvail subId: " + subId);
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        telephonyMgr.carrierActionReportDefaultNetworkStatus(subId, false);
    }

    private static void onDisableRadio(Intent intent, Context context) {
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        logd("onDisableRadio subId: " + subId);
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        telephonyMgr.carrierActionSetRadioEnabled(subId, !ENABLE);
    }

    private static void onEnableRadio(Intent intent, Context context) {
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        logd("onEnableRadio subId: " + subId);
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        telephonyMgr.carrierActionSetRadioEnabled(subId, ENABLE);
    }

    private static void onShowCaptivePortalNotification(Intent intent, Context context) {
        logd("onShowCaptivePortalNotification");
        Intent portalIntent = new Intent(context, CaptivePortalLoginActivity.class);
        portalIntent.putExtras(intent);
        portalIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, portalIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = getNotification(context, R.string.portal_notification_id,
                R.string.portal_notification_detail, pendingIntent);
        try {
            context.getSystemService(NotificationManager.class)
                    .notify(PORTAL_NOTIFICATION_TAG, PORTAL_NOTIFICATION_ID, notification);
        } catch (NullPointerException npe) {
            loge("setNotificationVisible: " + npe);
        }
    }

    private static void onShowNoDataServiceNotification(Context context) {
        logd("onShowNoDataServiceNotification");
        Notification notification = getNotification(context, R.string.no_data_notification_id,
                R.string.no_data_notification_detail, null);
        try {
            context.getSystemService(NotificationManager.class)
                    .notify(NO_DATA_NOTIFICATION_TAG, NO_DATA_NOTIFICATION_ID, notification);
        } catch (NullPointerException npe) {
            loge("setNotificationVisible: " + npe);
        }
    }

    private static void onCancelAllNotifications(Context context) {
        logd("onCancelAllNotifications");
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    private static void onResetAllCarrierActions(Intent intent, Context context) {
        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        logd("onResetAllCarrierActions subId: " + subId);
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        telephonyMgr.carrierActionResetAll(subId);
    }

    private static Notification getNotification(Context context, int titleId, int textId,
                                         PendingIntent pendingIntent) {
        final TelephonyManager telephonyMgr = context.getSystemService(TelephonyManager.class);
        final Resources resources = context.getResources();
        String spn = telephonyMgr.getSimOperatorName();
        if (TextUtils.isEmpty(spn)) {
            // There is no consistent way to get the current carrier name as MNOs didn't
            // bother to set EF_SPN. in the long term, we should display a generic wording if
            // spn from subscription is not set.
            spn = telephonyMgr.getNetworkOperatorName();
        }
        final Bundle extras = Bundle.forPair(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                resources.getString(R.string.android_system_label));
        createNotificationChannels(context);
        Notification.Builder builder = new Notification.Builder(context)
                .setContentTitle(resources.getString(titleId))
                .setContentText(String.format(resources.getString(textId), spn))
                .setSmallIcon(R.drawable.ic_sim_card)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(false)
                .setExtras(extras)
                .setChannel(NOTIFICATION_CHANNEL_ID_MOBILE_DATA_STATUS);

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }
        return builder.build();
    }

    /**
     * Creates the notification channel and registers it with NotificationManager. Also used to
     * update an existing channel's name.
     */
    static void createNotificationChannels(Context context) {
        context.getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL_ID_MOBILE_DATA_STATUS,
                context.getResources().getString(
                        R.string.mobile_data_status_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
