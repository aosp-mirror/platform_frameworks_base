/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Binder that takes a notifications {@link ExpandableNotificationRow} and binds the appropriate
 * content to it based off the bind parameters passed to it.
 */
public interface NotificationRowContentBinder {

    /**
     * Inflate notification content views and bind to the row.
     *
     * @param entry notification
     * @param row notification row to bind views to
     * @param contentToBind content views that should be inflated and bound
     * @param bindParams parameters for binding content views
     * @param forceInflate true to force reinflation even if views are cached
     * @param callback callback after inflation is finished
     */
    void bindContent(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row,
            @InflationFlag int contentToBind,
            BindParams bindParams,
            boolean forceInflate,
            @Nullable InflationCallback callback);

    /**
     * Cancel any on-going bind operation.
     *
     * @param entry notification
     * @param row notification row to cancel bind on
     * @return true if an on-going bind operation was cancelled
     */
    boolean cancelBind(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row);

    /**
     * Unbind content views from the row.
     *
     * @param entry notification
     * @param row notification row to unbind content views from
     * @param contentToUnbind content views that should be unbound
     */
    void unbindContent(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row,
            @InflationFlag int contentToUnbind);

    /** For testing, ensure all inflation is synchronous. */
    @VisibleForTesting
    void setInflateSynchronously(boolean inflateSynchronously);

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            prefix = {"FLAG_CONTENT_VIEW_"},
            value = {
                    FLAG_CONTENT_VIEW_CONTRACTED,
                    FLAG_CONTENT_VIEW_EXPANDED,
                    FLAG_CONTENT_VIEW_HEADS_UP,
                    FLAG_CONTENT_VIEW_PUBLIC,
                    FLAG_CONTENT_VIEW_SINGLE_LINE,
                    FLAG_GROUP_SUMMARY_HEADER,
                    FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER,
                    FLAG_CONTENT_VIEW_ALL})
    @interface InflationFlag {}
    /**
     * The default, contracted view.  Seen when the shade is pulled down and in the lock screen
     * if there is no worry about content sensitivity.
     */
    int FLAG_CONTENT_VIEW_CONTRACTED = 1;
    /**
     * The expanded view.  Seen when the user expands a notification.
     */
    int FLAG_CONTENT_VIEW_EXPANDED = 1 << 1;
    /**
     * The heads up view.  Seen when a high priority notification peeks in from the top.
     */
    int FLAG_CONTENT_VIEW_HEADS_UP = 1 << 2;
    /**
     * The public view.  This is a version of the contracted view that hides sensitive
     * information and is used on the lock screen if we determine that the notification's
     * content should be hidden.
     */
    int FLAG_CONTENT_VIEW_PUBLIC = 1 << 3;

    /**
     * The single line notification view. Show when the notification is shown as a child in group.
     */
    int FLAG_CONTENT_VIEW_SINGLE_LINE = 1 << 4;

    /**
     * The notification group summary header view
     */
    int FLAG_GROUP_SUMMARY_HEADER = 1 << 5;

    /**
     * The notification low-priority group summary header view
     */
    int FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER = 1 << 6;

    int FLAG_CONTENT_VIEW_ALL = (1 << 7) - 1;

    /**
     * Parameters for content view binding
     */
    class BindParams {

        /**
         * Bind a minimized version of the content views.
         */
        public boolean isMinimized;

        /**
         * Use increased height when binding contracted view.
         */
        public boolean usesIncreasedHeight;

        /**
         * Use increased height when binding heads up views.
         */
        public boolean usesIncreasedHeadsUpHeight;

        /**
         * Is group summary notification
         */
        public boolean mIsGroupSummary;
    }

    /**
     * Callback for inflation finishing
     */
    interface InflationCallback {

        /**
         * Callback for when there is an inflation exception
         *
         * @param entry notification which failed to inflate content
         * @param e exception
         */
        void handleInflationException(NotificationEntry entry, Exception e);

        /**
         * Callback for after the content views finish inflating.
         *
         * @param entry the entry with the content views set
         */
        void onAsyncInflationFinished(NotificationEntry entry);
    }
}
