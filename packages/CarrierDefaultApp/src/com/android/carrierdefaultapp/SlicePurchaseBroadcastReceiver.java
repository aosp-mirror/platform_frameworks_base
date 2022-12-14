/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.telephony.AnomalyReporter;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.slice.SlicePurchaseController;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The SlicePurchaseBroadcastReceiver listens for
 * {@link SlicePurchaseController#ACTION_START_SLICE_PURCHASE_APP} from the SlicePurchaseController
 * in the phone process to start the slice purchase application. It displays the network boost
 * notification to the user and will start the {@link SlicePurchaseActivity} to display the
 * {@link WebView} to purchase network boosts from the user's carrier.
 */
public class SlicePurchaseBroadcastReceiver extends BroadcastReceiver{
    private static final String TAG = "SlicePurchaseBroadcastReceiver";

    /**
     * UUID to report an anomaly when receiving a PendingIntent from an application or process
     * other than the Phone process.
     */
    private static final String UUID_BAD_PENDING_INTENT = "c360246e-95dc-4abf-9dc1-929a76cd7e53";

    /** Weak references to {@link SlicePurchaseActivity} for each capability, if it exists. */
    private static final Map<Integer, WeakReference<SlicePurchaseActivity>>
            sSlicePurchaseActivities = new HashMap<>();

    /** Channel ID for the network boost notification. */
    private static final String NETWORK_BOOST_NOTIFICATION_CHANNEL_ID = "network_boost";
    /** Tag for the network boost notification. */
    public static final String NETWORK_BOOST_NOTIFICATION_TAG = "SlicePurchaseApp.Notification";
    /** Action for when the user clicks the "Not now" button on the network boost notification. */
    private static final String ACTION_NOTIFICATION_CANCELED =
            "com.android.phone.slice.action.NOTIFICATION_CANCELED";

    /**
     * Create a weak reference to {@link SlicePurchaseActivity}. The reference will be removed when
     * {@link SlicePurchaseActivity#onDestroy()} is called.
     *
     * @param capability The premium capability requested.
     * @param slicePurchaseActivity The instance of SlicePurchaseActivity.
     */
    public static void updateSlicePurchaseActivity(
            @TelephonyManager.PremiumCapability int capability,
            @NonNull SlicePurchaseActivity slicePurchaseActivity) {
        sSlicePurchaseActivities.put(capability, new WeakReference<>(slicePurchaseActivity));
    }

    /**
     * Remove the weak reference to {@link SlicePurchaseActivity} when
     * {@link SlicePurchaseActivity#onDestroy()} is called.
     *
     * @param capability The premium capability requested.
     */
    public static void removeSlicePurchaseActivity(
            @TelephonyManager.PremiumCapability int capability) {
        sSlicePurchaseActivities.remove(capability);
    }

    /**
     * Send the PendingIntent containing the corresponding slice purchase application response.
     *
     * @param intent The Intent containing the PendingIntent extra.
     * @param extra The extra to get the PendingIntent to send.
     */
    public static void sendSlicePurchaseAppResponse(@NonNull Intent intent, @NonNull String extra) {
        PendingIntent pendingIntent = intent.getParcelableExtra(extra, PendingIntent.class);
        if (pendingIntent == null) {
            loge("PendingIntent does not exist for extra: " + extra);
            return;
        }
        try {
            pendingIntent.send();
        } catch (PendingIntent.CanceledException e) {
            loge("Unable to send " + getPendingIntentType(extra) + " intent: " + e);
        }
    }

    /**
     * Send the PendingIntent containing the corresponding slice purchase application response
     * with additional data.
     *
     * @param context The Context to use to send the PendingIntent.
     * @param intent The Intent containing the PendingIntent extra.
     * @param extra The extra to get the PendingIntent to send.
     * @param data The Intent containing additional data to send with the PendingIntent.
     */
    public static void sendSlicePurchaseAppResponseWithData(@NonNull Context context,
            @NonNull Intent intent, @NonNull String extra, @NonNull Intent data) {
        PendingIntent pendingIntent = intent.getParcelableExtra(extra, PendingIntent.class);
        if (pendingIntent == null) {
            loge("PendingIntent does not exist for extra: " + extra);
            return;
        }
        try {
            pendingIntent.send(context, 0 /* unused */, data);
        } catch (PendingIntent.CanceledException e) {
            loge("Unable to send " + getPendingIntentType(extra) + " intent: " + e);
        }
    }

    /**
     * Check whether the Intent is valid and can be used to complete purchases in the slice purchase
     * application. This checks that all necessary extras exist and that the values are valid.
     *
     * @param intent The intent to check
     * @return {@code true} if the intent is valid and {@code false} otherwise.
     */
    public static boolean isIntentValid(@NonNull Intent intent) {
        int phoneId = intent.getIntExtra(SlicePurchaseController.EXTRA_PHONE_ID,
                SubscriptionManager.INVALID_PHONE_INDEX);
        if (phoneId == SubscriptionManager.INVALID_PHONE_INDEX) {
            loge("isIntentValid: invalid phone index: " + phoneId);
            return false;
        }

        int subId = intent.getIntExtra(SlicePurchaseController.EXTRA_SUB_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            loge("isIntentValid: invalid subscription ID: " + subId);
            return false;
        }

        int capability = intent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        if (capability == SlicePurchaseController.PREMIUM_CAPABILITY_INVALID) {
            loge("isIntentValid: invalid premium capability: " + capability);
            return false;
        }

        String appName = intent.getStringExtra(SlicePurchaseController.EXTRA_REQUESTING_APP_NAME);
        if (TextUtils.isEmpty(appName)) {
            loge("isIntentValid: empty requesting application name: " + appName);
            return false;
        }

        return isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_CANCELED)
                && isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_CARRIER_ERROR)
                && isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_REQUEST_FAILED)
                && isPendingIntentValid(intent,
                        SlicePurchaseController.EXTRA_INTENT_NOT_DEFAULT_DATA_SUBSCRIPTION)
                && isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_SUCCESS)
                && isPendingIntentValid(intent,
                        SlicePurchaseController.EXTRA_INTENT_NOTIFICATION_SHOWN);
    }

    private static boolean isPendingIntentValid(@NonNull Intent intent, @NonNull String extra) {
        String intentType = getPendingIntentType(extra);
        PendingIntent pendingIntent = intent.getParcelableExtra(extra, PendingIntent.class);
        if (pendingIntent == null) {
            loge("isPendingIntentValid: " + intentType + " intent not found.");
            return false;
        }
        String creatorPackage = pendingIntent.getCreatorPackage();
        if (!creatorPackage.equals(TelephonyManager.PHONE_PROCESS_NAME)) {
            String logStr = "isPendingIntentValid: " + intentType + " intent was created by "
                    + creatorPackage + " instead of the phone process.";
            loge(logStr);
            AnomalyReporter.reportAnomaly(UUID.fromString(UUID_BAD_PENDING_INTENT), logStr);
            return false;
        }
        if (!pendingIntent.isBroadcast()) {
            loge("isPendingIntentValid: " + intentType + " intent is not a broadcast.");
            return false;
        }
        return true;
    }

    @NonNull private static String getPendingIntentType(@NonNull String extra) {
        switch (extra) {
            case SlicePurchaseController.EXTRA_INTENT_CANCELED: return "canceled";
            case SlicePurchaseController.EXTRA_INTENT_CARRIER_ERROR: return "carrier error";
            case SlicePurchaseController.EXTRA_INTENT_REQUEST_FAILED: return "request failed";
            case SlicePurchaseController.EXTRA_INTENT_NOT_DEFAULT_DATA_SUBSCRIPTION:
                return "not default data subscription";
            case SlicePurchaseController.EXTRA_INTENT_SUCCESS: return "success";
            case SlicePurchaseController.EXTRA_INTENT_NOTIFICATION_SHOWN:
                return "notification shown";
            default: {
                loge("Unknown pending intent extra: " + extra);
                return "unknown(" + extra + ")";
            }
        }
    }

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        logd("onReceive intent: " + intent.getAction());
        switch (intent.getAction()) {
            case SlicePurchaseController.ACTION_START_SLICE_PURCHASE_APP:
                onDisplayNetworkBoostNotification(context, intent);
                break;
            case SlicePurchaseController.ACTION_SLICE_PURCHASE_APP_RESPONSE_TIMEOUT:
                onTimeout(context, intent);
                break;
            case ACTION_NOTIFICATION_CANCELED:
                onUserCanceled(context, intent);
                break;
            default:
                loge("Received unknown action: " + intent.getAction());
        }
    }

    private void onDisplayNetworkBoostNotification(@NonNull Context context,
            @NonNull Intent intent) {
        if (!isIntentValid(intent)) {
            sendSlicePurchaseAppResponse(intent,
                    SlicePurchaseController.EXTRA_INTENT_REQUEST_FAILED);
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                NETWORK_BOOST_NOTIFICATION_CHANNEL_ID,
                context.getResources().getString(R.string.network_boost_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        // CarrierDefaultApp notifications are unblockable by default. Make this channel blockable
        //  to allow users to disable notifications posted to this channel without affecting other
        //  notifications in this application.
        channel.setBlockable(true);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification =
                new Notification.Builder(context, NETWORK_BOOST_NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(String.format(context.getResources().getString(
                                R.string.network_boost_notification_title),
                                intent.getStringExtra(
                                        SlicePurchaseController.EXTRA_REQUESTING_APP_NAME)))
                        .setContentText(context.getResources().getString(
                                R.string.network_boost_notification_detail))
                        .setSmallIcon(R.drawable.ic_network_boost)
                        .setContentIntent(createContentIntent(context, intent, 1))
                        .setDeleteIntent(intent.getParcelableExtra(
                                SlicePurchaseController.EXTRA_INTENT_CANCELED, PendingIntent.class))
                        // Add an action for the "Not now" button, which has the same behavior as
                        // the user canceling or closing the notification.
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(context, R.drawable.ic_network_boost),
                                context.getResources().getString(
                                        R.string.network_boost_notification_button_not_now),
                                createCanceledIntent(context, intent)).build())
                        // Add an action for the "Manage" button, which has the same behavior as
                        // the user clicking on the notification.
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(context, R.drawable.ic_network_boost),
                                context.getResources().getString(
                                        R.string.network_boost_notification_button_manage),
                                createContentIntent(context, intent, 2)).build())
                        .build();

        int capability = intent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        logd("Display the network boost notification for capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability));
        context.getSystemService(NotificationManager.class).notifyAsUser(
                NETWORK_BOOST_NOTIFICATION_TAG, capability, notification, UserHandle.ALL);
        sendSlicePurchaseAppResponse(intent,
                SlicePurchaseController.EXTRA_INTENT_NOTIFICATION_SHOWN);
    }

    /**
     * Create the intent for when the user clicks on the "Manage" button on the network boost
     * notification or the notification itself. This will open {@link SlicePurchaseActivity}.
     *
     * @param context The Context to create the intent for.
     * @param intent The source Intent used to launch the slice purchase application.
     * @param requestCode The request code for the PendingIntent.
     *
     * @return The intent to start {@link SlicePurchaseActivity}.
     */
    @VisibleForTesting
    @NonNull public PendingIntent createContentIntent(@NonNull Context context,
            @NonNull Intent intent, int requestCode) {
        Intent i = new Intent(context, SlicePurchaseActivity.class);
        i.setComponent(ComponentName.unflattenFromString(
                "com.android.carrierdefaultapp/.SlicePurchaseActivity"));
        i.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.putExtras(intent);
        return PendingIntent.getActivityAsUser(context, requestCode, i,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE, null /* options */,
                UserHandle.CURRENT);
    }

    /**
     * Create the canceled intent for when the user clicks the "Not now" button on the network boost
     * notification. This will send {@link #ACTION_NOTIFICATION_CANCELED} and has the same function
     * as if the user had canceled or removed the notification.
     *
     * @param context The Context to create the intent for.
     * @param intent The source Intent used to launch the slice purchase application.
     *
     * @return The canceled intent.
     */
    @VisibleForTesting
    @NonNull public PendingIntent createCanceledIntent(@NonNull Context context,
            @NonNull Intent intent) {
        Intent i = new Intent(ACTION_NOTIFICATION_CANCELED);
        i.setComponent(ComponentName.unflattenFromString(
                "com.android.carrierdefaultapp/.SlicePurchaseBroadcastReceiver"));
        i.putExtras(intent);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    private void onTimeout(@NonNull Context context, @NonNull Intent intent) {
        int capability = intent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        logd("Purchase capability " + TelephonyManager.convertPremiumCapabilityToString(capability)
                + " timed out.");
        if (sSlicePurchaseActivities.get(capability) == null) {
            // Notification is still active
            logd("Closing network boost notification since the user did not respond in time.");
            context.getSystemService(NotificationManager.class).cancelAsUser(
                    NETWORK_BOOST_NOTIFICATION_TAG, capability, UserHandle.ALL);
        } else {
            // Notification was dismissed but SlicePurchaseActivity is still active
            logd("Closing slice purchase application WebView since the user did not complete the "
                    + "purchase in time.");
            sSlicePurchaseActivities.get(capability).get().finishAndRemoveTask();
        }
    }

    private void onUserCanceled(@NonNull Context context, @NonNull Intent intent) {
        int capability = intent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        logd("onUserCanceled: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        context.getSystemService(NotificationManager.class)
                .cancelAsUser(NETWORK_BOOST_NOTIFICATION_TAG, capability, UserHandle.ALL);
        sendSlicePurchaseAppResponse(intent, SlicePurchaseController.EXTRA_INTENT_CANCELED);
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
