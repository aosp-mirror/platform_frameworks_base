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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.BindParams;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationCallback;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;

import javax.inject.Inject;

/**
 * A stage that binds all content views for an already inflated {@link ExpandableNotificationRow}.
 *
 * In the farther future, the binder logic and consequently this stage should be broken into
 * smaller stages.
 */
@SysUISingleton
public class RowContentBindStage extends BindStage<RowContentBindParams> {
    private final NotificationRowContentBinder mBinder;
    private final NotifInflationErrorManager mNotifInflationErrorManager;
    private final RowContentBindStageLogger mLogger;

    @Inject
    RowContentBindStage(
            NotificationRowContentBinder binder,
            NotifInflationErrorManager errorManager,
            RowContentBindStageLogger logger) {
        mBinder = binder;
        mNotifInflationErrorManager = errorManager;
        mLogger = logger;
    }

    @Override
    protected void executeStage(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row,
            @NonNull StageCallback callback) {
        RowContentBindParams params = getStageParams(entry);

        mLogger.logStageParams(entry.getKey(), params.toString());

        // Resolve content to bind/unbind.
        @InflationFlag int inflationFlags = params.getContentViews();
        @InflationFlag int invalidatedFlags = params.getDirtyContentViews();

        @InflationFlag int contentToBind = invalidatedFlags & inflationFlags;
        @InflationFlag int contentToUnbind = inflationFlags ^ FLAG_CONTENT_VIEW_ALL;

        // Bind/unbind with parameters
        mBinder.unbindContent(entry, row, contentToUnbind);

        BindParams bindParams = new BindParams();
        bindParams.isLowPriority = params.useLowPriority();
        bindParams.usesIncreasedHeight = params.useIncreasedHeight();
        bindParams.usesIncreasedHeadsUpHeight = params.useIncreasedHeadsUpHeight();
        boolean forceInflate = params.needsReinflation();

        InflationCallback inflationCallback = new InflationCallback() {
            @Override
            public void handleInflationException(NotificationEntry entry, Exception e) {
                mNotifInflationErrorManager.setInflationError(entry, e);
            }

            @Override
            public void onAsyncInflationFinished(NotificationEntry entry) {
                mNotifInflationErrorManager.clearInflationError(entry);
                getStageParams(entry).clearDirtyContentViews();
                callback.onStageFinished(entry);
            }
        };
        mBinder.cancelBind(entry, row);
        mBinder.bindContent(entry, row, contentToBind, bindParams, forceInflate, inflationCallback);
    }

    @Override
    protected void abortStage(
            @NonNull NotificationEntry entry,
            @NonNull ExpandableNotificationRow row) {
        mBinder.cancelBind(entry, row);
    }

    @Override
    protected RowContentBindParams newStageParams() {
        return new RowContentBindParams();
    }
}
