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
package com.android.server.notification;

import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * NotificationManagerService helper for auto-grouping notifications.
 */
public class GroupHelper {
    private static final String TAG = "GroupHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    protected static final String AUTOGROUP_KEY = "ranker_group";

    private final Callback mCallback;
    private final int mAutoGroupAtCount;

    // count the number of ongoing notifications per group
    // userId -> (package name -> (group Id -> (set of notification keys)))
    final ArrayMap<String, ArraySet<String>>
            mOngoingGroupCount = new ArrayMap<>();

    // Map of user : <Map of package : notification keys>. Only contains notifications that are not
    // grouped by the app (aka no group or sort key).
    Map<Integer, Map<String, LinkedHashSet<String>>> mUngroupedNotifications = new HashMap<>();

    public GroupHelper(int autoGroupAtCount, Callback callback) {
        mAutoGroupAtCount = autoGroupAtCount;
        mCallback = callback;
    }

    private String generatePackageGroupKey(int userId, String pkg, String group) {
        return userId + "|" + pkg + "|" + group;
    }

    @VisibleForTesting
    protected int getOngoingGroupCount(int userId, String pkg, String group) {
        String key = generatePackageGroupKey(userId, pkg, group);
        return mOngoingGroupCount.getOrDefault(key, new ArraySet<>(0)).size();
    }

    private void addToOngoingGroupCount(StatusBarNotification sbn, boolean add) {
        if (sbn.getNotification().isGroupSummary()) return;
        if (!sbn.isOngoing() && add) return;
        String group = sbn.getGroup();
        if (group == null) return;
        int userId = sbn.getUser().getIdentifier();
        String key = generatePackageGroupKey(userId, sbn.getPackageName(), group);
        ArraySet<String> notifications = mOngoingGroupCount.getOrDefault(key, new ArraySet<>(0));
        if (add) {
            notifications.add(sbn.getKey());
            mOngoingGroupCount.put(key, notifications);
        } else {
            notifications.remove(sbn.getKey());
            // we dont need to put it back if it is default
        }
        String combinedKey = generatePackageGroupKey(userId, sbn.getPackageName(), group);
        boolean needsOngoingFlag = notifications.size() > 0;
        mCallback.updateAutogroupSummary(sbn.getKey(), needsOngoingFlag);
    }

    public void onNotificationUpdated(StatusBarNotification childSbn,
            boolean autogroupSummaryExists) {
        if (childSbn.getGroup() != AUTOGROUP_KEY
                || childSbn.getNotification().isGroupSummary()) return;
        if (childSbn.isOngoing()) {
            addToOngoingGroupCount(childSbn, true);
        } else {
            addToOngoingGroupCount(childSbn, false);
        }
    }

    public void onNotificationPosted(StatusBarNotification sbn, boolean autogroupSummaryExists) {
        if (DEBUG) Log.i(TAG, "POSTED " + sbn.getKey());
        try {
            List<String> notificationsToGroup = new ArrayList<>();
            if (autogroupSummaryExists) addToOngoingGroupCount(sbn, true);
            if (!sbn.isAppGroup()) {
                // Not grouped by the app, add to the list of notifications for the app;
                // send grouping update if app exceeds the autogrouping limit.
                synchronized (mUngroupedNotifications) {
                    Map<String, LinkedHashSet<String>> ungroupedNotificationsByUser
                            = mUngroupedNotifications.get(sbn.getUserId());
                    if (ungroupedNotificationsByUser == null) {
                        ungroupedNotificationsByUser = new HashMap<>();
                    }
                    mUngroupedNotifications.put(sbn.getUserId(), ungroupedNotificationsByUser);
                    LinkedHashSet<String> notificationsForPackage
                            = ungroupedNotificationsByUser.get(sbn.getPackageName());
                    if (notificationsForPackage == null) {
                        notificationsForPackage = new LinkedHashSet<>();
                    }

                    notificationsForPackage.add(sbn.getKey());
                    ungroupedNotificationsByUser.put(sbn.getPackageName(), notificationsForPackage);

                    if (notificationsForPackage.size() >= mAutoGroupAtCount
                            || autogroupSummaryExists) {
                        notificationsToGroup.addAll(notificationsForPackage);
                    }
                }
                if (notificationsToGroup.size() > 0) {
                    adjustAutogroupingSummary(sbn.getUserId(), sbn.getPackageName(),
                            notificationsToGroup.get(0), true);
                    adjustNotificationBundling(notificationsToGroup, true);
                }
            } else {
                // Grouped, but not by us. Send updates to un-autogroup, if we grouped it.
                maybeUngroup(sbn, false, sbn.getUserId());
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failure processing new notification", e);
        }
    }

    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            addToOngoingGroupCount(sbn, false);
            maybeUngroup(sbn, true, sbn.getUserId());
        } catch (Exception e) {
            Slog.e(TAG, "Error processing canceled notification", e);
        }
    }

    /**
     * Un-autogroups notifications that are now grouped by the app.
     */
    private void maybeUngroup(StatusBarNotification sbn, boolean notificationGone, int userId) {
        List<String> notificationsToUnAutogroup = new ArrayList<>();
        boolean removeSummary = false;
        synchronized (mUngroupedNotifications) {
            Map<String, LinkedHashSet<String>> ungroupedNotificationsByUser
                    = mUngroupedNotifications.get(sbn.getUserId());
            if (ungroupedNotificationsByUser == null || ungroupedNotificationsByUser.size() == 0) {
                return;
            }
            LinkedHashSet<String> notificationsForPackage
                    = ungroupedNotificationsByUser.get(sbn.getPackageName());
            if (notificationsForPackage == null || notificationsForPackage.size() == 0) {
                return;
            }
            if (notificationsForPackage.remove(sbn.getKey())) {
                if (!notificationGone) {
                    // Add the current notification to the ungrouping list if it still exists.
                    notificationsToUnAutogroup.add(sbn.getKey());
                }
            }
            // If the status change of this notification has brought the number of loose
            // notifications to zero, remove the summary and un-autogroup.
            if (notificationsForPackage.size() == 0) {
                ungroupedNotificationsByUser.remove(sbn.getPackageName());
                removeSummary = true;
            }
        }
        if (removeSummary) {
            adjustAutogroupingSummary(userId, sbn.getPackageName(), null, false);
        }
        if (notificationsToUnAutogroup.size() > 0) {
            adjustNotificationBundling(notificationsToUnAutogroup, false);
        }
    }

    private void adjustAutogroupingSummary(int userId, String packageName, String triggeringKey,
            boolean summaryNeeded) {
        if (summaryNeeded) {
            mCallback.addAutoGroupSummary(userId, packageName, triggeringKey);
        } else {
            mCallback.removeAutoGroupSummary(userId, packageName);
        }
    }

    private void adjustNotificationBundling(List<String> keys, boolean group) {
        for (String key : keys) {
            if (DEBUG) Log.i(TAG, "Sending grouping adjustment for: " + key + " group? " + group);
            if (group) {
                mCallback.addAutoGroup(key);
            } else {
                mCallback.removeAutoGroup(key);
            }
        }
    }

    protected interface Callback {
        void addAutoGroup(String key);
        void removeAutoGroup(String key);
        void addAutoGroupSummary(int userId, String pkg, String triggeringKey);
        void removeAutoGroupSummary(int user, String pkg);
        void updateAutogroupSummary(String key, boolean needsOngoingFlag);
    }
}
