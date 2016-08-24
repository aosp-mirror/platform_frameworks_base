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

package android.ext.services.notification;

import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED;

import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.NotificationRankerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import android.ext.services.R;

/**
 * Class that provides an updatable ranker module for the notification manager..
 */
public final class Ranker extends NotificationRankerService {
    private static final String TAG = "RocketRanker";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int AUTOBUNDLE_AT_COUNT = 4;
    private static final String AUTOBUNDLE_KEY = "ranker_bundle";

    // Map of user : <Map of package : notification keys>. Only contains notifications that are not
    // bundled by the app (aka no group or sort key).
    Map<Integer, Map<String, LinkedHashSet<String>>> mUnbundledNotifications;

    @Override
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn, int importance,
            boolean user) {
        if (DEBUG) Log.i(TAG, "ENQUEUED " + sbn.getKey());
        return null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (DEBUG) Log.i(TAG, "POSTED " + sbn.getKey());
        try {
            List<String> notificationsToBundle = new ArrayList<>();
            if (!sbn.isAppGroup()) {
                // Not grouped by the app, add to the list of notifications for the app;
                // send bundling update if app exceeds the autobundling limit.
                synchronized (mUnbundledNotifications) {
                    Map<String, LinkedHashSet<String>> unbundledNotificationsByUser
                            = mUnbundledNotifications.get(sbn.getUserId());
                    if (unbundledNotificationsByUser == null) {
                        unbundledNotificationsByUser = new HashMap<>();
                    }
                    mUnbundledNotifications.put(sbn.getUserId(), unbundledNotificationsByUser);
                    LinkedHashSet<String> notificationsForPackage
                            = unbundledNotificationsByUser.get(sbn.getPackageName());
                    if (notificationsForPackage == null) {
                        notificationsForPackage = new LinkedHashSet<>();
                    }

                    notificationsForPackage.add(sbn.getKey());
                    unbundledNotificationsByUser.put(sbn.getPackageName(), notificationsForPackage);

                    if (notificationsForPackage.size() >= AUTOBUNDLE_AT_COUNT) {
                        for (String key : notificationsForPackage) {
                            notificationsToBundle.add(key);
                        }
                    }
                }
                if (notificationsToBundle.size() > 0) {
                    adjustAutobundlingSummary(sbn.getPackageName(), notificationsToBundle.get(0),
                            true, sbn.getUserId());
                    adjustNotificationBundling(sbn.getPackageName(), notificationsToBundle, true,
                            sbn.getUserId());
                }
            } else {
                // Grouped, but not by us. Send updates to unautobundle, if we bundled it.
                maybeUnbundle(sbn, false, sbn.getUserId());
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failure processing new notification", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            maybeUnbundle(sbn, true, sbn.getUserId());
        } catch (Exception e) {
            Slog.e(TAG, "Error processing canceled notification", e);
        }
    }

    /**
     * Un-autobundles notifications that are now grouped by the app. Additionally cancels
     * autobundling if the status change of this notification resulted in the loose notification
     * count being under the limit.
     */
    private void maybeUnbundle(StatusBarNotification sbn, boolean notificationGone, int user) {
        List<String> notificationsToUnAutobundle = new ArrayList<>();
        boolean removeSummary = false;
        synchronized (mUnbundledNotifications) {
            Map<String, LinkedHashSet<String>> unbundledNotificationsByUser
                    = mUnbundledNotifications.get(sbn.getUserId());
            if (unbundledNotificationsByUser == null || unbundledNotificationsByUser.size() == 0) {
                return;
            }
            LinkedHashSet<String> notificationsForPackage
                    = unbundledNotificationsByUser.get(sbn.getPackageName());
            if (notificationsForPackage == null || notificationsForPackage.size() == 0) {
                return;
            }
            if (notificationsForPackage.remove(sbn.getKey())) {
                if (!notificationGone) {
                    // Add the current notification to the unbundling list if it still exists.
                    notificationsToUnAutobundle.add(sbn.getKey());
                }
                // If the status change of this notification has brought the number of loose
                // notifications back below the limit, remove the summary and un-autobundle.
                if (notificationsForPackage.size() == AUTOBUNDLE_AT_COUNT - 1) {
                    removeSummary = true;
                    for (String key : notificationsForPackage) {
                        notificationsToUnAutobundle.add(key);
                    }
                }
            }
        }
        if (notificationsToUnAutobundle.size() > 0) {
            if (removeSummary) {
                adjustAutobundlingSummary(sbn.getPackageName(), null, false, user);
            }
            adjustNotificationBundling(sbn.getPackageName(), notificationsToUnAutobundle, false,
                    user);
        }
    }

    @Override
    public void onListenerConnected() {
        if (DEBUG) Log.i(TAG, "CONNECTED");
        mUnbundledNotifications = new HashMap<>();
        for (StatusBarNotification sbn : getActiveNotifications()) {
            onNotificationPosted(sbn);
        }
    }

    private void adjustAutobundlingSummary(String packageName, String key, boolean summaryNeeded,
            int user) {
        Bundle signals = new Bundle();
        if (summaryNeeded) {
            signals.putBoolean(Adjustment.NEEDS_AUTOGROUPING_KEY, true);
            signals.putString(Adjustment.GROUP_KEY_OVERRIDE_KEY, AUTOBUNDLE_KEY);
        } else {
            signals.putBoolean(Adjustment.NEEDS_AUTOGROUPING_KEY, false);
        }
        Adjustment adjustment = new Adjustment(packageName, key, IMPORTANCE_UNSPECIFIED, signals,
                getContext().getString(R.string.notification_ranker_autobundle_explanation), null,
                user);
        if (DEBUG) {
            Log.i(TAG, "Summary update for: " + packageName + " "
                    + (summaryNeeded ? "adding" : "removing"));
        }
        try {
            adjustNotification(adjustment);
        } catch (Exception e) {
            Slog.e(TAG, "Adjustment failed", e);
        }

    }
    private void adjustNotificationBundling(String packageName, List<String> keys, boolean bundle,
            int user) {
        List<Adjustment> adjustments = new ArrayList<>();
        for (String key : keys) {
            adjustments.add(createBundlingAdjustment(packageName, key, bundle, user));
            if (DEBUG) Log.i(TAG, "Sending bundling adjustment for: " + key);
        }
        try {
            adjustNotifications(adjustments);
        } catch (Exception e) {
            Slog.e(TAG, "Adjustments failed", e);
        }
    }

    private Adjustment createBundlingAdjustment(String packageName, String key, boolean bundle,
            int user) {
        Bundle signals = new Bundle();
        if (bundle) {
            signals.putString(Adjustment.GROUP_KEY_OVERRIDE_KEY, AUTOBUNDLE_KEY);
        } else {
            signals.putString(Adjustment.GROUP_KEY_OVERRIDE_KEY, null);
        }
        return new Adjustment(packageName, key, IMPORTANCE_UNSPECIFIED, signals,
                getContext().getString(R.string.notification_ranker_autobundle_explanation),
                null, user);
    }

}