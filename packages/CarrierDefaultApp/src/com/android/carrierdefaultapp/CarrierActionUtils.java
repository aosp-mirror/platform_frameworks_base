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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
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
        final NotificationManager notificationMgr = context.getSystemService(
                NotificationManager.class);
        Intent portalIntent = new Intent(context, CaptivePortalLaunchActivity.class);
        portalIntent.putExtras(intent);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, portalIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = getNotification(context, R.string.portal_notification_id,
                R.string.portal_notification_detail, pendingIntent);
        try {
            notificationMgr.notify(PORTAL_NOTIFICATION_TAG, PORTAL_NOTIFICATION_ID, notification);
        } catch (NullPointerException npe) {
            loge("setNotificationVisible: " + npe);
        }
    }

    private static void onShowNoDataServiceNotification(Context context) {
        logd("onShowNoDataServiceNotification");
        final NotificationManager notificationMgr = context.getSystemService(
                NotificationManager.class);
        Notification notification = getNotification(context, R.string.no_data_notification_id,
                R.string.no_data_notification_detail, null);
        try {
            notificationMgr.notify(NO_DATA_NOTIFICATION_TAG, NO_DATA_NOTIFICATION_ID, notification);
        } catch (NullPointerException npe) {
            loge("setNotificationVisible: " + npe);
        }
    }

    private static void onCancelAllNotifications(Context context) {
        logd("onCancelAllNotifications");
        final NotificationManager notificationMgr = context.getSystemService(
                NotificationManager.class);
        notificationMgr.cancelAll();
    }

    private static Notification getNotification(Context context, int titleId, int textId,
                                         PendingIntent pendingIntent) {
        Resources resources = context.getResources();
        Notification.Builder builder = new Notification.Builder(context)
                .setContentTitle(resources.getString(titleId))
                .setContentText(resources.getString(textId))
                .setSmallIcon(R.drawable.ic_sim_card)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(false);

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }
        return builder.build();
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
