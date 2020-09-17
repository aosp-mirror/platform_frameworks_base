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

import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class that provide methods to help check when we need to inflate a low priority version
 * ot notification content.
 */
@Singleton
public class LowPriorityInflationHelper {
    private final FeatureFlags mFeatureFlags;
    private final NotificationGroupManager mGroupManager;
    private final RowContentBindStage mRowContentBindStage;

    @Inject
    LowPriorityInflationHelper(
            FeatureFlags featureFlags,
            NotificationGroupManager groupManager,
            RowContentBindStage rowContentBindStage) {
        mFeatureFlags = featureFlags;
        mGroupManager = groupManager;
        mRowContentBindStage = rowContentBindStage;
    }

    /**
     * Check if we inflated the wrong version of the view and if we need to reinflate the
     * content views to be their low priority version or not.
     *
     * Whether we inflate the low priority view or not depends on the notification being visually
     * part of a group. Since group membership is determined AFTER inflation, we're forced to check
     * again at a later point in the pipeline to see if we inflated the wrong view and reinflate
     * the correct one here.
     *
     * TODO: The group manager should run before inflation so that we don't deal with this
     */
    public void recheckLowPriorityViewAndInflate(
            NotificationEntry entry,
            ExpandableNotificationRow row) {
        RowContentBindParams params = mRowContentBindStage.getStageParams(entry);
        final boolean shouldBeLowPriority = shouldUseLowPriorityView(entry);
        if (!row.isRemoved() && row.isLowPriority() != shouldBeLowPriority) {
            params.setUseLowPriority(shouldBeLowPriority);
            mRowContentBindStage.requestRebind(entry,
                    en -> row.setIsLowPriority(shouldBeLowPriority));
        }
    }

    /**
     * Whether the notification should inflate a low priority version of its content views.
     */
    public boolean shouldUseLowPriorityView(NotificationEntry entry) {
        boolean isGroupChild;
        if (mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            isGroupChild = (entry.getParent() != GroupEntry.ROOT_ENTRY);
        } else {
            isGroupChild = mGroupManager.isChildInGroupWithSummary(entry.getSbn());
        }
        return entry.isAmbient() && !isGroupChild;
    }
}
