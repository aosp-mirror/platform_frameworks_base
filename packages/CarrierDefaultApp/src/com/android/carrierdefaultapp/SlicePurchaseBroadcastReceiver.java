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
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.LocaleList;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.AnomalyReporter;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;
import android.webkit.WebView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.phone.slice.SlicePurchaseController;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The SlicePurchaseBroadcastReceiver listens for
 * {@link SlicePurchaseController#ACTION_START_SLICE_PURCHASE_APP} from the SlicePurchaseController
 * in the phone process to start the slice purchase application. It displays the performance boost
 * notification to the user and will start the {@link SlicePurchaseActivity} to display the
 * {@link WebView} to purchase performance boosts from the user's carrier.
 */
public class SlicePurchaseBroadcastReceiver extends BroadcastReceiver{
    private static final String TAG = "SlicePurchaseBroadcastReceiver";

    /**
     * UUID to report an anomaly when receiving a PendingIntent from an application or process
     * other than the Phone process.
     */
    private static final String UUID_BAD_PENDING_INTENT = "c360246e-95dc-4abf-9dc1-929a76cd7e53";

    /** Channel ID for the performance boost notification. */
    private static final String PERFORMANCE_BOOST_NOTIFICATION_CHANNEL_ID = "performance_boost";
    /** Tag for the performance boost notification. */
    public static final String PERFORMANCE_BOOST_NOTIFICATION_TAG = "SlicePurchaseApp.Notification";
    /**
     * Action for when the user clicks the "Not now" button on the performance boost notification.
     */
    private static final String ACTION_NOTIFICATION_CANCELED =
            "com.android.phone.slice.action.NOTIFICATION_CANCELED";

    /**
     * A map of Intents sent by {@link SlicePurchaseController} for each capability.
     * If this map contains an Intent for a given capability, the performance boost notification to
     * purchase the capability is visible to the user.
     * If this map does not contain an Intent for a given capability, either the capability was
     * never requested or the {@link SlicePurchaseActivity} is visible to the user.
     * An Intent is added to this map when the performance boost notification is displayed to the
     * user and removed from the map when the notification is canceled.
     */
    private static final Map<Integer, Intent> sIntents = new HashMap<>();

    /**
     * Cancel the performance boost notification for the given capability and
     * remove the corresponding notification intent from the map.
     *
     * @param context The context to cancel the notification in.
     * @param capability The premium capability to cancel the notification for.
     */
    public static void cancelNotification(@NonNull Context context,
            @TelephonyManager.PremiumCapability int capability) {
        context.getSystemService(NotificationManager.class).cancelAsUser(
                PERFORMANCE_BOOST_NOTIFICATION_TAG, capability, UserHandle.ALL);
        sIntents.remove(capability);
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
     * @param intent The intent to check.
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

        String purchaseUrl = intent.getStringExtra(SlicePurchaseController.EXTRA_PURCHASE_URL);
        String userData = intent.getStringExtra(SlicePurchaseController.EXTRA_USER_DATA);
        String contentsType = intent.getStringExtra(SlicePurchaseController.EXTRA_CONTENTS_TYPE);
        if (getPurchaseUrl(purchaseUrl, userData, TextUtils.isEmpty(contentsType)) == null) {
            loge("isIntentValid: invalid purchase URL: " + purchaseUrl);
            return false;
        }

        String carrier = intent.getStringExtra(SlicePurchaseController.EXTRA_CARRIER);
        if (TextUtils.isEmpty(carrier)) {
            loge("isIntentValid: empty carrier: " + carrier);
            return false;
        }

        return isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_CANCELED)
                && isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_CARRIER_ERROR)
                && isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_REQUEST_FAILED)
                && isPendingIntentValid(intent,
                        SlicePurchaseController.EXTRA_INTENT_NOT_DEFAULT_DATA_SUBSCRIPTION)
                && isPendingIntentValid(intent,
                        SlicePurchaseController.EXTRA_INTENT_NOTIFICATIONS_DISABLED)
                && isPendingIntentValid(intent, SlicePurchaseController.EXTRA_INTENT_SUCCESS)
                && isPendingIntentValid(intent,
                        SlicePurchaseController.EXTRA_INTENT_NOTIFICATION_SHOWN);
    }

    /**
     * Get the {@link URL} from the given purchase URL String and user data, if it is valid.
     *
     * @param purchaseUrl The purchase URL String to use to create the URL.
     * @param userData The user data parameter from the entitlement server.
     * @param shouldAppendUserData If this is {@code true} and the {@code userData} exists,
     *        the {@code userData} should be appended to the {@code purchaseUrl} to create the URL.
     *        If this is false, only the {@code purchaseUrl} should be used and the {@code userData}
     *        will be sent as data to the POST request instead.
     * @return The URL from the given purchase URL and user data or {@code null} if it is invalid.
     */
    @Nullable public static URL getPurchaseUrl(@Nullable String purchaseUrl,
            @Nullable String userData, boolean shouldAppendUserData) {
        if (purchaseUrl == null) {
            return null;
        }
        // Only append user data if it exists, otherwise just return the purchase URL
        if (!shouldAppendUserData || TextUtils.isEmpty(userData)) {
            return getPurchaseUrl(purchaseUrl);
        }
        URL url = getPurchaseUrl(purchaseUrl + "?" + userData);
        if (url == null) {
            url = getPurchaseUrl(purchaseUrl);
        }
        return url;
    }

    /**
     * Get the {@link URL} from the given purchase URL String, if it is valid.
     *
     * @param purchaseUrl The purchase URL String to use to create the URL.
     * @return The purchase URL from the given String or {@code null} if it is invalid.
     */
    @Nullable private static URL getPurchaseUrl(@Nullable String purchaseUrl) {
        if (!URLUtil.isValidUrl(purchaseUrl)) {
            return null;
        }
        if (URLUtil.isAssetUrl(purchaseUrl)
                && !purchaseUrl.equals(SlicePurchaseController.SLICE_PURCHASE_TEST_FILE)) {
            return null;
        }
        URL url = null;
        try {
            url = new URL(purchaseUrl);
            url.toURI();
        } catch (MalformedURLException | URISyntaxException e) {
            loge("Invalid purchase URL: " + purchaseUrl + ", " + e);
        }
        return url;
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
            case SlicePurchaseController.EXTRA_INTENT_NOTIFICATIONS_DISABLED:
                return "notifications disabled";
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
            case Intent.ACTION_LOCALE_CHANGED:
                onLocaleChanged(context);
                break;
            case SlicePurchaseController.ACTION_START_SLICE_PURCHASE_APP:
                onDisplayPerformanceBoostNotification(context, intent, false);
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

    private void onLocaleChanged(@NonNull Context context) {
        if (sIntents.isEmpty()) return;

        for (int capability : new int[]{TelephonyManager.PREMIUM_CAPABILITY_PRIORITIZE_LATENCY}) {
            if (sIntents.get(capability) != null) {
                // Notification is active -- update notification for new locale
                context.getSystemService(NotificationManager.class).cancelAsUser(
                        PERFORMANCE_BOOST_NOTIFICATION_TAG, capability, UserHandle.ALL);
                onDisplayPerformanceBoostNotification(context, sIntents.get(capability), true);
            }
        }
    }

    private void onDisplayPerformanceBoostNotification(@NonNull Context context,
            @NonNull Intent intent, boolean localeChanged) {
        if (!localeChanged && !isIntentValid(intent)) {
            sendSlicePurchaseAppResponse(intent,
                    SlicePurchaseController.EXTRA_INTENT_REQUEST_FAILED);
            return;
        }

        Resources res = getResources(context);
        NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        NotificationChannel channel = notificationManager.getNotificationChannel(
                PERFORMANCE_BOOST_NOTIFICATION_CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(
                    PERFORMANCE_BOOST_NOTIFICATION_CHANNEL_ID,
                    res.getString(R.string.performance_boost_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            // CarrierDefaultApp notifications are unblockable by default.
            // Make this channel blockable to allow users to disable notifications posted to this
            // channel without affecting other notifications in this application.
            channel.setBlockable(true);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        } else if (localeChanged) {
            // If the channel already exists but the locale has changed, update the channel name.
            channel.setName(res.getString(R.string.performance_boost_notification_channel));
        }

        boolean channelNotificationsDisabled =
                channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
        if (channelNotificationsDisabled || !notificationManager.areNotificationsEnabled()) {
            // If notifications are disabled for the app or channel, fail the purchase request.
            logd("Purchase request failed because notifications are disabled for the "
                    + (channelNotificationsDisabled ? "channel." : "application."));
            sendSlicePurchaseAppResponse(intent,
                    SlicePurchaseController.EXTRA_INTENT_NOTIFICATIONS_DISABLED);
            return;
        }

        String carrier = intent.getStringExtra(SlicePurchaseController.EXTRA_CARRIER);
        Notification notification =
                new Notification.Builder(context, PERFORMANCE_BOOST_NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(res.getString(
                                R.string.performance_boost_notification_title))
                        .setContentText(String.format(res.getString(
                                R.string.performance_boost_notification_detail), carrier))
                        .setSmallIcon(R.drawable.ic_performance_boost)
                        .setContentIntent(createContentIntent(context, intent, 1))
                        .setDeleteIntent(intent.getParcelableExtra(
                                SlicePurchaseController.EXTRA_INTENT_CANCELED, PendingIntent.class))
                        // Add an action for the "Not now" button, which has the same behavior as
                        // the user canceling or closing the notification.
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(context, R.drawable.ic_performance_boost),
                                res.getString(
                                        R.string.performance_boost_notification_button_not_now),
                                createCanceledIntent(context, intent)).build())
                        // Add an action for the "Manage" button, which has the same behavior as
                        // the user clicking on the notification.
                        .addAction(new Notification.Action.Builder(
                                Icon.createWithResource(context, R.drawable.ic_performance_boost),
                                res.getString(
                                        R.string.performance_boost_notification_button_manage),
                                createContentIntent(context, intent, 2)).build())
                        .build();

        int capability = intent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        logd((localeChanged ? "Update" : "Display")
                + " the performance boost notification for capability "
                + TelephonyManager.convertPremiumCapabilityToString(capability));
        context.getSystemService(NotificationManager.class).notifyAsUser(
                PERFORMANCE_BOOST_NOTIFICATION_TAG, capability, notification, UserHandle.ALL);
        if (!localeChanged) {
            sIntents.put(capability, intent);
            sendSlicePurchaseAppResponse(intent,
                    SlicePurchaseController.EXTRA_INTENT_NOTIFICATION_SHOWN);
        }
    }

    /**
     * Get the {@link Resources} for the current locale.
     *
     * @param context The context to get the resources in.
     *
     * @return The resources in the current locale.
     */
    @VisibleForTesting
    @NonNull public Resources getResources(@NonNull Context context) {
        Resources resources = context.getResources();
        Configuration config = resources.getConfiguration();
        config.setLocale(getCurrentLocale());
        return new Resources(resources.getAssets(), resources.getDisplayMetrics(), config);
    }

    /**
     * Get the current {@link Locale} from the system property {@code persist.sys.locale}.
     *
     * @return The user's default/preferred language.
     */
    @VisibleForTesting
    @NonNull public Locale getCurrentLocale() {
        String languageTag = SystemProperties.get("persist.sys.locale");
        if (TextUtils.isEmpty(languageTag)) {
            return LocaleList.getAdjustedDefault().get(0);
        }
        return Locale.forLanguageTag(languageTag);
    }

    /**
     * Create the intent for when the user clicks on the "Manage" button on the performance boost
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
     * Create the canceled intent for when the user clicks the "Not now" button on the performance
     * boost notification. This will send {@link #ACTION_NOTIFICATION_CANCELED} and has the same
     * behavior as if the user had canceled or removed the notification.
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
        if (sIntents.get(capability) != null) {
            // Notification is still active -- cancel pending notification
            logd("Closing performance boost notification since the user did not respond in time.");
            cancelNotification(context, capability);
        } else {
            // SlicePurchaseActivity is still active -- ignore timer
            logd("Ignoring timeout since the SlicePurchaseActivity is still active.");
        }
    }

    private void onUserCanceled(@NonNull Context context, @NonNull Intent intent) {
        if (!isIntentValid(intent)) {
            loge("Ignoring onUserCanceled called with invalid intent.");
            return;
        }
        int capability = intent.getIntExtra(SlicePurchaseController.EXTRA_PREMIUM_CAPABILITY,
                SlicePurchaseController.PREMIUM_CAPABILITY_INVALID);
        logd("onUserCanceled: " + TelephonyManager.convertPremiumCapabilityToString(capability));
        cancelNotification(context, capability);
        sendSlicePurchaseAppResponse(intent, SlicePurchaseController.EXTRA_INTENT_CANCELED);
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
