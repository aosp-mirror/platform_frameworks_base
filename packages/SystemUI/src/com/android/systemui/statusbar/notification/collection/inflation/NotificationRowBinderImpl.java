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

package com.android.systemui.statusbar.notification.collection.inflation;

import static com.android.server.notification.Flags.screenshareNotificationHiding;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_PUBLIC;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_SINGLE_LINE;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_GROUP_SUMMARY_HEADER;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.view.ViewGroup;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.icon.IconManager;
import com.android.systemui.statusbar.notification.row.BigPictureIconManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation;
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;

import javax.inject.Inject;
import javax.inject.Provider;

/** Handles inflating and updating views for notifications. */
@SysUISingleton
public class NotificationRowBinderImpl implements NotificationRowBinder {

    private static final String TAG = "NotificationViewManager";

    private final Context mContext;
    private final NotificationMessagingUtil mMessagingUtil;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    private final NotifBindPipeline mNotifBindPipeline;
    private final RowContentBindStage mRowContentBindStage;
    private final Provider<RowInflaterTask> mRowInflaterTaskProvider;
    private final ExpandableNotificationRowComponent.Builder
            mExpandableNotificationRowComponentBuilder;
    private final IconManager mIconManager;
    private final NotificationRowBinderLogger mLogger;

    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;
    private NotificationClicker mNotificationClicker;
    private FeatureFlags mFeatureFlags;

    @Inject
    public NotificationRowBinderImpl(
            Context context,
            NotificationMessagingUtil notificationMessagingUtil,
            NotificationRemoteInputManager notificationRemoteInputManager,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotifBindPipeline notifBindPipeline,
            RowContentBindStage rowContentBindStage,
            Provider<RowInflaterTask> rowInflaterTaskProvider,
            ExpandableNotificationRowComponent.Builder expandableNotificationRowComponentBuilder,
            IconManager iconManager,
            NotificationRowBinderLogger logger,
            FeatureFlags featureFlags) {
        mContext = context;
        mNotifBindPipeline = notifBindPipeline;
        mRowContentBindStage = rowContentBindStage;
        mMessagingUtil = notificationMessagingUtil;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mNotificationLockscreenUserManager = notificationLockscreenUserManager;
        mRowInflaterTaskProvider = rowInflaterTaskProvider;
        mExpandableNotificationRowComponentBuilder = expandableNotificationRowComponentBuilder;
        mIconManager = iconManager;
        mLogger = logger;
        mFeatureFlags = featureFlags;
    }

    /**
     * Sets up late-bound dependencies for this component.
     */
    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer) {
        mPresenter = presenter;
        mListContainer = listContainer;

        mIconManager.attach();
    }

    public void setNotificationClicker(NotificationClicker clicker) {
        mNotificationClicker = clicker;
    }

    /**
     * Inflates the views for the given entry (possibly asynchronously).
     */
    @Override
    public void inflateViews(
            NotificationEntry entry,
            @NonNull NotifInflater.Params params,
            NotificationRowContentBinder.InflationCallback callback)
            throws InflationException {
        //TODO(b/217799515): Remove the entry parameter from getViewParentForNotification(), this
        // function returns the NotificationStackScrollLayout regardless of the entry.
        ViewGroup parent = mListContainer.getViewParentForNotification(entry);

        if (entry.rowExists()) {
            mLogger.logUpdatingRow(entry, params);
            mIconManager.updateIcons(entry);
            ExpandableNotificationRow row = entry.getRow();
            row.reset();
            updateRow(entry, row);
            inflateContentViews(entry, params, row, callback);
        } else {
            mLogger.logCreatingRow(entry, params);
            mIconManager.createIcons(entry);
            mLogger.logInflatingRow(entry);
            mRowInflaterTaskProvider.get().inflate(mContext, parent, entry,
                    row -> {
                        mLogger.logInflatedRow(entry);
                        // Setup the controller for the view.
                        ExpandableNotificationRowComponent component =
                                mExpandableNotificationRowComponentBuilder
                                        .expandableNotificationRow(row)
                                        .notificationEntry(entry)
                                        .onExpandClickListener(mPresenter)
                                        .build();
                        ExpandableNotificationRowController rowController =
                                component.getExpandableNotificationRowController();
                        rowController.init(entry);
                        entry.setRowController(rowController);
                        maybeSetBigPictureIconManager(row, component);
                        bindRow(entry, row);
                        updateRow(entry, row);
                        inflateContentViews(entry, params, row, callback);
                    });
        }
    }

    @Override
    public void releaseViews(NotificationEntry entry) {
        if (!entry.rowExists()) {
            mLogger.logNotReleasingViewsRowDoesntExist(entry);
            return;
        }
        mLogger.logReleasingViews(entry);
        cancelRunningJobs(entry.getRow());
        final RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_CONTRACTED);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_EXPANDED);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_PUBLIC);
        if (AsyncHybridViewInflation.isEnabled()) {
            params.markContentViewsFreeable(FLAG_CONTENT_VIEW_SINGLE_LINE);
        }
        mRowContentBindStage.requestRebind(entry, null);
    }

    private void maybeSetBigPictureIconManager(ExpandableNotificationRow row,
            ExpandableNotificationRowComponent component) {
        if (mFeatureFlags.isEnabled(Flags.BIGPICTURE_NOTIFICATION_LAZY_LOADING)) {
            row.setBigPictureIconManager(component.getBigPictureIconManager());
        }
    }

    private void cancelRunningJobs(ExpandableNotificationRow row) {
        if (row == null) {
            return;
        }
        BigPictureIconManager iconManager = row.getBigPictureIconManager();
        if (iconManager != null) {
            iconManager.cancelJobs();
        }
    }

    /**
     * Bind row to various controllers and managers. This is only called when the row is first
     * created.
     *
     * TODO: This method associates a row with an entry, but eventually needs to not do that
     */
    private void bindRow(NotificationEntry entry, ExpandableNotificationRow row) {
        mListContainer.bindRow(row);
        mNotificationRemoteInputManager.bindRow(row);
        entry.setRow(row);
        mNotifBindPipeline.manageRow(entry, row);
        mPresenter.onBindRow(row);
    }

    /**
     * Update row after the notification has updated.
     *
     * @param entry notification that has updated
     */
    private void updateRow(
            NotificationEntry entry,
            ExpandableNotificationRow row) {
        row.setLegacy(entry.targetSdk >= Build.VERSION_CODES.GINGERBREAD
                && entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);

        // bind the click event to the content area
        requireNonNull(mNotificationClicker).register(row, entry.getSbn());
    }

    /**
     * Inflate the row's basic content views.
     */
    private void inflateContentViews(
            NotificationEntry entry,
            @NonNull NotifInflater.Params inflaterParams,
            ExpandableNotificationRow row,
            @Nullable NotificationRowContentBinder.InflationCallback inflationCallback) {
        final boolean useIncreasedCollapsedHeight =
                mMessagingUtil.isImportantMessaging(entry.getSbn(), entry.getImportance());
        final boolean isLowPriority = inflaterParams.isLowPriority();

        // Set show snooze action
        row.setShowSnooze(inflaterParams.getShowSnooze());

        RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        params.requireContentViews(FLAG_CONTENT_VIEW_CONTRACTED);
        params.requireContentViews(FLAG_CONTENT_VIEW_EXPANDED);
        params.setUseIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
        params.setUseLowPriority(isLowPriority);

        // If screenshareNotificationHiding is enabled, both public and private views should be
        // inflated to avoid any latency associated with reinflating all notification views when
        // screen share starts and stops
        if (screenshareNotificationHiding()
                || mNotificationLockscreenUserManager.needsRedaction(entry)) {
            params.requireContentViews(FLAG_CONTENT_VIEW_PUBLIC);
        } else {
            params.markContentViewsFreeable(FLAG_CONTENT_VIEW_PUBLIC);
        }

        if (AsyncHybridViewInflation.isEnabled()) {
            if (inflaterParams.isChildInGroup()) {
                params.requireContentViews(FLAG_CONTENT_VIEW_SINGLE_LINE);
            } else {
                // TODO(b/217799515): here we decide whether to free the single-line view
                //  when the group status changes
                params.markContentViewsFreeable(FLAG_CONTENT_VIEW_SINGLE_LINE);
            }
        }

        if (AsyncGroupHeaderViewInflation.isEnabled()) {
            if (inflaterParams.isGroupSummary()) {
                params.requireContentViews(FLAG_GROUP_SUMMARY_HEADER);
                if (isLowPriority) {
                    params.requireContentViews(FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER);
                }
            } else {
                params.markContentViewsFreeable(FLAG_GROUP_SUMMARY_HEADER);
                params.markContentViewsFreeable(FLAG_LOW_PRIORITY_GROUP_SUMMARY_HEADER);
            }
        }
        params.rebindAllContentViews();
        mLogger.logRequestingRebind(entry, inflaterParams);
        mRowContentBindStage.requestRebind(entry, en -> {
            mLogger.logRebindComplete(entry);
            row.setUsesIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
            row.setIsLowPriority(isLowPriority);
            if (inflationCallback != null) {
                inflationCallback.onAsyncInflationFinished(en);
            }
        });
    }
}
