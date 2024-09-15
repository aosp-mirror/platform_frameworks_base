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

import static android.app.Notification.COLOR_DEFAULT;
import static android.app.Notification.FLAG_AUTOGROUP_SUMMARY;
import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_GROUP_SUMMARY;
import static android.app.Notification.FLAG_LOCAL_ONLY;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.Notification.VISIBILITY_PRIVATE;
import static android.app.Notification.VISIBILITY_PUBLIC;

import android.annotation.NonNull;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * NotificationManagerService helper for auto-grouping notifications.
 */
public class GroupHelper {
    private static final String TAG = "GroupHelper";

    protected static final String AUTOGROUP_KEY = "ranker_group";

    protected static final int FLAG_INVALID = -1;

    // Flags that all autogroup summaries have
    protected static final int BASE_FLAGS =
            FLAG_AUTOGROUP_SUMMARY | FLAG_GROUP_SUMMARY | FLAG_LOCAL_ONLY;
    // Flag that autogroup summaries inherits if all children have the flag
    private static final int ALL_CHILDREN_FLAG = FLAG_AUTO_CANCEL;
    // Flags that autogroup summaries inherits if any child has them
    private static final int ANY_CHILDREN_FLAGS = FLAG_ONGOING_EVENT | FLAG_NO_CLEAR;

    private final Callback mCallback;
    private final int mAutoGroupAtCount;
    private final Context mContext;
    private final PackageManager mPackageManager;

    // Only contains notifications that are not explicitly grouped by the app (aka no group or
    // sort key).
    // userId|packageName -> (keys of notifications that aren't in an explicit app group -> flags)
    @GuardedBy("mUngroupedNotifications")
    private final ArrayMap<String, ArrayMap<String, NotificationAttributes>> mUngroupedNotifications
            = new ArrayMap<>();

    public GroupHelper(Context context, PackageManager packageManager, int autoGroupAtCount,
            Callback callback) {
        mAutoGroupAtCount = autoGroupAtCount;
        mCallback =  callback;
        mContext = context;
        mPackageManager = packageManager;
    }

    private String generatePackageKey(int userId, String pkg) {
        return userId + "|" + pkg;
    }

    @VisibleForTesting
    @GuardedBy("mUngroupedNotifications")
    protected int getAutogroupSummaryFlags(
            @NonNull final ArrayMap<String, NotificationAttributes> children) {
        boolean allChildrenHasFlag = children.size() > 0;
        int anyChildFlagSet = 0;
        for (int i = 0; i < children.size(); i++) {
            if (!hasAnyFlag(children.valueAt(i).flags, ALL_CHILDREN_FLAG)) {
                allChildrenHasFlag = false;
            }
            if (hasAnyFlag(children.valueAt(i).flags, ANY_CHILDREN_FLAGS)) {
                anyChildFlagSet |= (children.valueAt(i).flags & ANY_CHILDREN_FLAGS);
            }
        }
        return BASE_FLAGS | (allChildrenHasFlag ? ALL_CHILDREN_FLAG : 0) | anyChildFlagSet;
    }

    private boolean hasAnyFlag(int flags, int mask) {
        return (flags & mask) != 0;
    }

    /**
     * Called when a notification is newly posted. Checks whether that notification, and all other
     * active notifications should be grouped or ungrouped atuomatically, and returns whether.
     * @param sbn The posted notification.
     * @param autogroupSummaryExists Whether a summary for this notification already exists.
     * @return Whether the provided notification should be autogrouped synchronously.
     */
    public boolean onNotificationPosted(StatusBarNotification sbn, boolean autogroupSummaryExists) {
        boolean sbnToBeAutogrouped = false;
        try {
            if (!sbn.isAppGroup()) {
                sbnToBeAutogrouped = maybeGroup(sbn, autogroupSummaryExists);
            } else {
                maybeUngroup(sbn, false, sbn.getUserId());
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failure processing new notification", e);
        }
        return sbnToBeAutogrouped;
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
    private boolean maybeGroup(StatusBarNotification sbn, boolean autogroupSummaryExists) {
        int flags = 0;
        List<String> notificationsToGroup = new ArrayList<>();
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        // Indicates whether the provided sbn should be autogrouped by the caller.
        boolean sbnToBeAutogrouped = false;
        synchronized (mUngroupedNotifications) {
            String packageKey = generatePackageKey(sbn.getUserId(), sbn.getPackageName());
            final ArrayMap<String, NotificationAttributes> children =
                    mUngroupedNotifications.getOrDefault(packageKey, new ArrayMap<>());

            NotificationAttributes attr = new NotificationAttributes(sbn.getNotification().flags,
                    sbn.getNotification().getSmallIcon(), sbn.getNotification().color,
                    sbn.getNotification().visibility);
            children.put(sbn.getKey(), attr);
            mUngroupedNotifications.put(packageKey, children);

            if (children.size() >= mAutoGroupAtCount || autogroupSummaryExists) {
                flags = getAutogroupSummaryFlags(children);
                notificationsToGroup.addAll(children.keySet());
                childrenAttr.addAll(children.values());
            }
        }
        if (notificationsToGroup.size() > 0) {
            if (autogroupSummaryExists) {
                NotificationAttributes attr = new NotificationAttributes(flags,
                        sbn.getNotification().getSmallIcon(), sbn.getNotification().color,
                        VISIBILITY_PRIVATE);
                if (Flags.autogroupSummaryIconUpdate()) {
                    attr = updateAutobundledSummaryAttributes(sbn.getPackageName(), childrenAttr,
                            attr);
                }

                mCallback.updateAutogroupSummary(sbn.getUserId(), sbn.getPackageName(), attr);
            } else {
                Icon summaryIcon = sbn.getNotification().getSmallIcon();
                int summaryIconColor = sbn.getNotification().color;
                int summaryVisibility = VISIBILITY_PRIVATE;
                if (Flags.autogroupSummaryIconUpdate()) {
                    // Calculate the initial summary icon, icon color and visibility
                    NotificationAttributes iconAttr = getAutobundledSummaryAttributes(
                            sbn.getPackageName(), childrenAttr);
                    summaryIcon = iconAttr.icon;
                    summaryIconColor = iconAttr.iconColor;
                    summaryVisibility = iconAttr.visibility;
                }

                NotificationAttributes attr = new NotificationAttributes(flags, summaryIcon,
                        summaryIconColor, summaryVisibility);
                mCallback.addAutoGroupSummary(sbn.getUserId(), sbn.getPackageName(), sbn.getKey(),
                        attr);
            }
            for (String keyToGroup : notificationsToGroup) {
                if (android.app.Flags.checkAutogroupBeforePost()) {
                    if (keyToGroup.equals(sbn.getKey())) {
                        // Autogrouping for the provided notification is to be done synchronously.
                        sbnToBeAutogrouped = true;
                    } else {
                        mCallback.addAutoGroup(keyToGroup, /*requestSort=*/true);
                    }
                } else {
                    mCallback.addAutoGroup(keyToGroup, /*requestSort=*/true);
                }
            }
        }
        return sbnToBeAutogrouped;
    }

    /**
     * A notification was added that's app grouped, or a notification was removed.
     * Evaluate whether:
     * (a) an existing autogroup summary needs updated flags
     * (b) if we need to remove our autogroup overlay for this notification
     * (c) we need to remove the autogroup summary
     *
     * And updates the internal state of un-app-grouped notifications and their flags.
     */
    private void maybeUngroup(StatusBarNotification sbn, boolean notificationGone, int userId) {
        boolean removeSummary = false;
        int summaryFlags = FLAG_INVALID;
        boolean updateSummaryFlags = false;
        boolean removeAutogroupOverlay = false;
        List<NotificationAttributes> childrenAttrs = new ArrayList<>();
        synchronized (mUngroupedNotifications) {
            String key = generatePackageKey(sbn.getUserId(), sbn.getPackageName());
            final ArrayMap<String, NotificationAttributes> children =
                    mUngroupedNotifications.getOrDefault(key, new ArrayMap<>());
            if (children.size() == 0) {
                return;
            }

            // if this notif was autogrouped and now isn't
            if (children.containsKey(sbn.getKey())) {
                // if this notification was contributing flags that aren't covered by other
                // children to the summary, reevaluate flags for the summary
                int flags = children.remove(sbn.getKey()).flags;
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
                } else {
                    childrenAttrs.addAll(children.values());
                }
            }
        }

        if (removeSummary) {
            mCallback.removeAutoGroupSummary(userId, sbn.getPackageName());
        } else {
            NotificationAttributes attr = new NotificationAttributes(summaryFlags,
                    sbn.getNotification().getSmallIcon(), sbn.getNotification().color,
                    VISIBILITY_PRIVATE);
            boolean attributesUpdated = false;
            if (Flags.autogroupSummaryIconUpdate()) {
                NotificationAttributes newAttr = updateAutobundledSummaryAttributes(
                        sbn.getPackageName(), childrenAttrs, attr);
                if (!newAttr.equals(attr)) {
                    attributesUpdated = true;
                    attr = newAttr;
                }
            }

            if (updateSummaryFlags || attributesUpdated) {
                mCallback.updateAutogroupSummary(userId, sbn.getPackageName(), attr);
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
            final ArrayMap<String, NotificationAttributes> children =
                    mUngroupedNotifications.getOrDefault(key, new ArrayMap<>());
            return children.size();
        }
    }

    NotificationAttributes getAutobundledSummaryAttributes(@NonNull String packageName,
            @NonNull List<NotificationAttributes> childrenAttr) {
        Icon newIcon = null;
        boolean childrenHaveSameIcon = true;
        int newColor = Notification.COLOR_INVALID;
        boolean childrenHaveSameColor = true;
        int newVisibility = VISIBILITY_PRIVATE;

        // Both the icon drawable and the icon background color are updated according to this rule:
        // - if all child icons are identical => use the common icon
        // - if child icons are different: use the monochromatic app icon, if exists.
        // Otherwise fall back to a generic icon representing a stack.
        for (NotificationAttributes state: childrenAttr) {
            // Check for icon
            if (newIcon == null) {
                newIcon = state.icon;
            } else {
                if (!newIcon.sameAs(state.icon)) {
                    childrenHaveSameIcon = false;
                }
            }
            // Check for color
            if (newColor == Notification.COLOR_INVALID) {
                newColor = state.iconColor;
            } else {
                if (newColor != state.iconColor) {
                    childrenHaveSameColor = false;
                }
            }
            // Check for visibility. If at least one child is public, then set to public
            if (state.visibility == VISIBILITY_PUBLIC) {
                newVisibility = VISIBILITY_PUBLIC;
            }
        }
        if (!childrenHaveSameIcon) {
            newIcon = getMonochromeAppIcon(packageName);
        }
        if (!childrenHaveSameColor) {
            newColor = COLOR_DEFAULT;
        }

        return new NotificationAttributes(0, newIcon, newColor, newVisibility);
    }

    NotificationAttributes updateAutobundledSummaryAttributes(@NonNull String packageName,
            @NonNull List<NotificationAttributes> childrenAttr,
            @NonNull NotificationAttributes oldAttr) {
        NotificationAttributes newAttr = getAutobundledSummaryAttributes(packageName,
                childrenAttr);
        Icon newIcon = newAttr.icon;
        int newColor = newAttr.iconColor;
        if (newAttr.icon == null) {
            newIcon = oldAttr.icon;
        }
        if (newAttr.iconColor == Notification.COLOR_INVALID) {
            newColor = oldAttr.iconColor;
        }

        return new NotificationAttributes(oldAttr.flags, newIcon, newColor, newAttr.visibility);
    }

    /**
     * Get the monochrome app icon for an app from the adaptive launcher icon
     *  or a fallback generic icon for autogroup summaries.
     *
     * @param pkg packageName of the app
     * @return a monochrome app icon or a fallback generic icon
     */
    @NonNull
    Icon getMonochromeAppIcon(@NonNull final String pkg) {
        Icon monochromeIcon = null;
        final int fallbackIconResId = R.drawable.ic_notification_summary_auto;
        try {
            final Drawable appIcon = mPackageManager.getApplicationIcon(pkg);
            if (appIcon instanceof AdaptiveIconDrawable) {
                if (((AdaptiveIconDrawable) appIcon).getMonochrome() != null) {
                    monochromeIcon = Icon.createWithResourceAdaptiveDrawable(pkg,
                            ((AdaptiveIconDrawable) appIcon).getSourceDrawableResId(), true,
                            -2.0f * AdaptiveIconDrawable.getExtraInsetFraction());
                }
            }
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Failed to getApplicationIcon() in getMonochromeAppIcon()", e);
        }
        if (monochromeIcon != null) {
            return monochromeIcon;
        } else {
            return Icon.createWithResource(mContext, fallbackIconResId);
        }
    }

    protected static class NotificationAttributes {
        public final int flags;
        public final int iconColor;
        public final Icon icon;
        public final int visibility;

        public NotificationAttributes(int flags, Icon icon, int iconColor, int visibility) {
            this.flags = flags;
            this.icon = icon;
            this.iconColor = iconColor;
            this.visibility = visibility;
        }

        public NotificationAttributes(@NonNull NotificationAttributes attr) {
            this.flags = attr.flags;
            this.icon = attr.icon;
            this.iconColor = attr.iconColor;
            this.visibility = attr.visibility;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NotificationAttributes that)) {
                return false;
            }
            return flags == that.flags && iconColor == that.iconColor && icon.sameAs(that.icon)
                    && visibility == that.visibility;
        }

        @Override
        public int hashCode() {
            return Objects.hash(flags, iconColor, icon, visibility);
        }
    }

    protected interface Callback {
        void addAutoGroup(String key, boolean requestSort);
        void removeAutoGroup(String key);

        void addAutoGroupSummary(int userId, String pkg, String triggeringKey,
                NotificationAttributes summaryAttr);
        void removeAutoGroupSummary(int user, String pkg);
        void updateAutogroupSummary(int userId, String pkg, NotificationAttributes summaryAttr);
    }
}
