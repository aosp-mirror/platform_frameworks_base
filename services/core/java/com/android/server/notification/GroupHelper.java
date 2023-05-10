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

import static android.app.Notification.FLAG_AUTOGROUP_SUMMARY;
import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_GROUP_SUMMARY;
import static android.app.Notification.FLAG_LOCAL_ONLY;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_ONGOING_EVENT;

import android.annotation.NonNull;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * NotificationManagerService helper for auto-grouping notifications.
 */
public class GroupHelper {
    private static final String TAG = "GroupHelper";

    protected static final String AUTOGROUP_KEY = "ranker_group";

    // Flags that all autogroup summaries have
    protected static final int BASE_FLAGS =
            FLAG_AUTOGROUP_SUMMARY | FLAG_GROUP_SUMMARY | FLAG_LOCAL_ONLY;
    // Flag that autogroup summaries inherits if all children have the flag
    private static final int ALL_CHILDREN_FLAG = FLAG_AUTO_CANCEL;
    // Flags that autogroup summaries inherits if any child has them
    private static final int ANY_CHILDREN_FLAGS = FLAG_ONGOING_EVENT | FLAG_NO_CLEAR;

    private final Callback mCallback;
    private final int mAutoGroupAtCount;

    // Only contains notifications that are not explicitly grouped by the app (aka no group or
    // sort key).
    // userId|packageName -> (keys of notifications that aren't in an explicit app group -> flags)
    @GuardedBy("mUngroupedNotifications")
    private final ArrayMap<String, ArrayMap<String, Integer>> mUngroupedNotifications
            = new ArrayMap<>();

    public GroupHelper(int autoGroupAtCount, Callback callback) {
        mAutoGroupAtCount = autoGroupAtCount;
        mCallback =  callback;
    }

    private String generatePackageKey(int userId, String pkg) {
        return userId + "|" + pkg;
    }

    @VisibleForTesting
    @GuardedBy("mUngroupedNotifications")
    protected int getAutogroupSummaryFlags(@NonNull final ArrayMap<String, Integer> children) {
        boolean allChildrenHasFlag = children.size() > 0;
        int anyChildFlagSet = 0;
        for (int i = 0; i < children.size(); i++) {
            if (!hasAnyFlag(children.valueAt(i), ALL_CHILDREN_FLAG)) {
                allChildrenHasFlag = false;
            }
            if (hasAnyFlag(children.valueAt(i), ANY_CHILDREN_FLAGS)) {
                anyChildFlagSet |= (children.valueAt(i) & ANY_CHILDREN_FLAGS);
            }
        }
        return BASE_FLAGS | (allChildrenHasFlag ? ALL_CHILDREN_FLAG : 0) | anyChildFlagSet;
    }

    private boolean hasAnyFlag(int flags, int mask) {
        return (flags & mask) != 0;
    }

    public void onNotificationPosted(StatusBarNotification sbn, boolean autogroupSummaryExists) {
        try {
            if (!sbn.isAppGroup()) {
                maybeGroup(sbn, autogroupSummaryExists);
            } else {
                maybeUngroup(sbn, false, sbn.getUserId());
            }

        } catch (Exception e) {
            Slog.e(TAG, "Failure processing new notification", e);
        }
    }

    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            maybeUngroup(sbn, true, sbn.getUserId());
        } catch (Exception e) {
            Slog.e(TAG, "Error processing canceled notification", e);
        }
    }

    /**
     * A non-app grouped notification has been added or updated
     * Evaluate if:
     * (a) an existing autogroup summary needs updated flags
     * (b) a new autogroup summary needs to be added with correct flags
     * (c) other non-app grouped children need to be moved to the autogroup
     *
     * And stores the list of upgrouped notifications & their flags
     */
    private void maybeGroup(StatusBarNotification sbn, boolean autogroupSummaryExists) {
        int flags = 0;
        List<String> notificationsToGroup = new ArrayList<>();
        synchronized (mUngroupedNotifications) {
            String key = generatePackageKey(sbn.getUserId(), sbn.getPackageName());
            final ArrayMap<String, Integer> children =
                    mUngroupedNotifications.getOrDefault(key, new ArrayMap<>());

            children.put(sbn.getKey(), sbn.getNotification().flags);
            mUngroupedNotifications.put(key, children);

            if (children.size() >= mAutoGroupAtCount || autogroupSummaryExists) {
                flags = getAutogroupSummaryFlags(children);
                notificationsToGroup.addAll(children.keySet());
            }
        }
        if (notificationsToGroup.size() > 0) {
            if (autogroupSummaryExists) {
                mCallback.updateAutogroupSummary(sbn.getUserId(), sbn.getPackageName(), flags);
            } else {
                mCallback.addAutoGroupSummary(
                        sbn.getUserId(), sbn.getPackageName(), sbn.getKey(), flags);
            }
            for (String key : notificationsToGroup) {
                mCallback.addAutoGroup(key);
            }
        }
    }

    /**
     * A notification was added that's app grouped, or a notification was removed.
     * Evaluate whether:
     * (a) an existing autogroup summary needs updated flags
     * (b) if we need to remove our autogroup overlay for this notification
     * (c) we need to remove the autogroup summary
     *
     * And updates the internal state of un-app-grouped notifications and their flags
     */
    private void maybeUngroup(StatusBarNotification sbn, boolean notificationGone, int userId) {
        boolean removeSummary = false;
        int summaryFlags = 0;
        boolean updateSummaryFlags = false;
        boolean removeAutogroupOverlay = false;
        synchronized (mUngroupedNotifications) {
            String key = generatePackageKey(sbn.getUserId(), sbn.getPackageName());
            final ArrayMap<String, Integer> children =
                    mUngroupedNotifications.getOrDefault(key, new ArrayMap<>());
            if (children.size() == 0) {
                return;
            }

            // if this notif was autogrouped and now isn't
            if (children.containsKey(sbn.getKey())) {
                // if this notification was contributing flags that aren't covered by other
                // children to the summary, reevaluate flags for the summary
                int flags = children.remove(sbn.getKey());
                // this
                if (hasAnyFlag(flags, ANY_CHILDREN_FLAGS)) {
                    updateSummaryFlags = true;
                    summaryFlags = getAutogroupSummaryFlags(children);
                }
                // if this notification still exists and has an autogroup overlay, but is now
                // grouped by the app, clear the overlay
                if (!notificationGone && sbn.getOverrideGroupKey() != null) {
                    removeAutogroupOverlay = true;
                }

                // If there are no more children left to autogroup, remove the summary
                if (children.size() == 0) {
                    removeSummary = true;
                }
            }
        }
        if (removeSummary) {
            mCallback.removeAutoGroupSummary(userId, sbn.getPackageName());
        } else {
            if (updateSummaryFlags) {
                mCallback.updateAutogroupSummary(userId, sbn.getPackageName(), summaryFlags);
            }
        }
        if (removeAutogroupOverlay) {
            mCallback.removeAutoGroup(sbn.getKey());
        }
    }

    @VisibleForTesting
    int getNotGroupedByAppCount(int userId, String pkg) {
        synchronized (mUngroupedNotifications) {
            String key = generatePackageKey(userId, pkg);
            final ArrayMap<String, Integer> children =
                    mUngroupedNotifications.getOrDefault(key, new ArrayMap<>());
            return children.size();
        }
    }

    protected interface Callback {
        void addAutoGroup(String key);
        void removeAutoGroup(String key);
        void addAutoGroupSummary(int userId, String pkg, String triggeringKey, int flags);
        void removeAutoGroupSummary(int user, String pkg);
        void updateAutogroupSummary(int userId, String pkg, int flags);
    }
}
