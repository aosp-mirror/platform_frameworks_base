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

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_PUBLIC;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.view.ViewGroup;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.LowPriorityInflationHelper;
import com.android.systemui.statusbar.notification.icon.IconManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
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
    private final LowPriorityInflationHelper mLowPriorityInflationHelper;
    private final NotifPipelineFlags mNotifPipelineFlags;

    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;
    private BindRowCallback mBindRowCallback;
    private NotificationClicker mNotificationClicker;

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
            LowPriorityInflationHelper lowPriorityInflationHelper,
            NotifPipelineFlags notifPipelineFlags) {
        mContext = context;
        mNotifBindPipeline = notifBindPipeline;
        mRowContentBindStage = rowContentBindStage;
        mMessagingUtil = notificationMessagingUtil;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mNotificationLockscreenUserManager = notificationLockscreenUserManager;
        mRowInflaterTaskProvider = rowInflaterTaskProvider;
        mExpandableNotificationRowComponentBuilder = expandableNotificationRowComponentBuilder;
        mIconManager = iconManager;
        mLowPriorityInflationHelper = lowPriorityInflationHelper;
        mNotifPipelineFlags = notifPipelineFlags;
    }

    /**
     * Sets up late-bound dependencies for this component.
     */
    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            BindRowCallback bindRowCallback) {
        mPresenter = presenter;
        mListContainer = listContainer;
        mBindRowCallback = bindRowCallback;

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
            NotifInflater.Params params,
            NotificationRowContentBinder.InflationCallback callback)
            throws InflationException {
        if (params == null) {
            // weak assert that the params should always be passed in the new pipeline
            mNotifPipelineFlags.checkLegacyPipelineEnabled();
        }
        ViewGroup parent = mListContainer.getViewParentForNotification(entry);

        if (entry.rowExists()) {
            mIconManager.updateIcons(entry);
            ExpandableNotificationRow row = entry.getRow();
            row.reset();
            updateRow(entry, row);
            inflateContentViews(entry, params, row, callback);
        } else {
            mIconManager.createIcons(entry);
            mRowInflaterTaskProvider.get().inflate(mContext, parent, entry,
                    row -> {
                        // Setup the controller for the view.
                        ExpandableNotificationRowComponent component =
                                mExpandableNotificationRowComponentBuilder
                                        .expandableNotificationRow(row)
                                        .notificationEntry(entry)
                                        .onExpandClickListener(mPresenter)
                                        .listContainer(mListContainer)
                                        .build();
                        ExpandableNotificationRowController rowController =
                                component.getExpandableNotificationRowController();
                        rowController.init(entry);
                        entry.setRowController(rowController);
                        bindRow(entry, row);
                        updateRow(entry, row);
                        inflateContentViews(entry, params, row, callback);
                    });
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
        row.setOnActivatedListener(mPresenter);
        entry.setRow(row);
        mNotifBindPipeline.manageRow(entry, row);
        mBindRowCallback.onBindRow(row);
    }

    /**
     * Updates the views bound to an entry when the entry's ranking changes, either in-place or by
     * reinflating them.
     *
     * TODO: Should this method be in this class?
     */
    @Override
    public void onNotificationRankingUpdated(
            NotificationEntry entry,
            @Nullable Integer oldImportance,
            NotificationUiAdjustment oldAdjustment,
            NotificationUiAdjustment newAdjustment,
            NotificationRowContentBinder.InflationCallback callback) {
        mNotifPipelineFlags.checkLegacyPipelineEnabled();
        if (NotificationUiAdjustment.needReinflate(oldAdjustment, newAdjustment)) {
            if (entry.rowExists()) {
                ExpandableNotificationRow row = entry.getRow();
                row.reset();
                updateRow(entry, row);
                inflateContentViews(entry, null, row, callback);
            } else {
                // Once the RowInflaterTask is done, it will pick up the updated entry, so
                // no-op here.
            }
        } else {
            if (oldImportance != null && entry.getImportance() != oldImportance) {
                if (entry.rowExists()) {
                    entry.getRow().onNotificationRankingUpdated();
                }
            }
        }
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
            NotifInflater.Params inflaterParams,
            ExpandableNotificationRow row,
            @Nullable NotificationRowContentBinder.InflationCallback inflationCallback) {
        final boolean useIncreasedCollapsedHeight =
                mMessagingUtil.isImportantMessaging(entry.getSbn(), entry.getImportance());
        final boolean isLowPriority;
        if (inflaterParams != null) {
            // NEW pipeline
            isLowPriority = inflaterParams.isLowPriority();
        } else {
            // LEGACY pipeline
            mNotifPipelineFlags.checkLegacyPipelineEnabled();
            // If this is our first time inflating, we don't actually know the groupings for real
            // yet, so we might actually inflate a low priority content view incorrectly here and
            // have to correct it later in the pipeline. On subsequent inflations (i.e. updates),
            // this should inflate the correct view.
            isLowPriority = mLowPriorityInflationHelper.shouldUseLowPriorityView(entry);
        }

        RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        params.setUseIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
        params.setUseLowPriority(isLowPriority);

        if (mNotificationLockscreenUserManager.needsRedaction(entry)) {
            params.requireContentViews(FLAG_CONTENT_VIEW_PUBLIC);
        } else {
            params.markContentViewsFreeable(FLAG_CONTENT_VIEW_PUBLIC);
        }

        params.rebindAllContentViews();
        mRowContentBindStage.requestRebind(entry, en -> {
            row.setUsesIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
            row.setIsLowPriority(isLowPriority);
            if (inflationCallback != null) {
                inflationCallback.onAsyncInflationFinished(en);
            }
        });
    }

    /** Callback for when a row is bound to an entry. */
    public interface BindRowCallback {
        /**
         * Called when a new row is created and bound to a notification.
         */
        void onBindRow(ExpandableNotificationRow row);
    }
}
