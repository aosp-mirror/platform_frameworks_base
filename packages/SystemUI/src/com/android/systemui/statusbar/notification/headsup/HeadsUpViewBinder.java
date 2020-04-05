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

package com.android.systemui.statusbar.notification.headsup;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;

import android.util.ArrayMap;

import androidx.annotation.Nullable;
import androidx.core.os.CancellationSignal;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Wrapper around heads up view binding logic. {@link HeadsUpViewBinder} is responsible for
 * figuring out the right heads up inflation parameters and inflating/freeing the heads up
 * content view.
 *
 * TODO: This should be moved into {@link HeadsUpCoordinator} when the old pipeline is deprecated
 * (i.e. when {@link HeadsUpBindController} is removed).
 */
@Singleton
public class HeadsUpViewBinder {
    private final RowContentBindStage mStage;
    private final NotificationMessagingUtil mNotificationMessagingUtil;
    private final Map<NotificationEntry, CancellationSignal> mOngoingBindCallbacks =
            new ArrayMap<>();

    private NotificationPresenter mNotificationPresenter;

    @Inject
    HeadsUpViewBinder(
            NotificationMessagingUtil notificationMessagingUtil,
            RowContentBindStage bindStage) {
        mNotificationMessagingUtil = notificationMessagingUtil;
        mStage = bindStage;
    }

    /**
     * Set notification presenter to determine parameters for heads up view inflation.
     */
    public void setPresenter(NotificationPresenter presenter) {
        mNotificationPresenter = presenter;
    }

    /**
     * Bind heads up view to the notification row.
     * @param callback callback after heads up view is bound
     */
    public void bindHeadsUpView(NotificationEntry entry, @Nullable BindCallback callback) {
        RowContentBindParams params = mStage.getStageParams(entry);
        final boolean isImportantMessage = mNotificationMessagingUtil.isImportantMessaging(
                entry.getSbn(), entry.getImportance());
        final boolean useIncreasedHeadsUp = isImportantMessage
                && !mNotificationPresenter.isPresenterFullyCollapsed();
        params.setUseIncreasedHeadsUpHeight(useIncreasedHeadsUp);
        params.requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);
        CancellationSignal signal = mStage.requestRebind(entry, en -> {
            en.getRow().setUsesIncreasedHeadsUpHeight(params.useIncreasedHeadsUpHeight());
            if (callback != null) {
                callback.onBindFinished(en);
            }
        });
        abortBindCallback(entry);
        mOngoingBindCallbacks.put(entry, signal);
    }

    /**
     * Abort any callbacks waiting for heads up view binding to finish for a given notification.
     * @param entry notification with bind in progress
     */
    public void abortBindCallback(NotificationEntry entry) {
        CancellationSignal ongoingBindCallback = mOngoingBindCallbacks.remove(entry);
        if (ongoingBindCallback != null) {
            ongoingBindCallback.cancel();
        }
    }

    /**
     * Unbind the heads up view from the notification row.
     */
    public void unbindHeadsUpView(NotificationEntry entry) {
        abortBindCallback(entry);
        mStage.getStageParams(entry).markContentViewsFreeable(FLAG_CONTENT_VIEW_HEADS_UP);
        mStage.requestRebind(entry, null);
    }
}
