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

package com.android.systemui.statusbar.notification.collection.legacy;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;

import javax.inject.Inject;

/**
 * Helper class that provide methods to help check when we need to inflate a low priority version
 * ot notification content.
 */
@SysUISingleton
public class LowPriorityInflationHelper {
    private final NotificationGroupManagerLegacy mGroupManager;
    private final RowContentBindStage mRowContentBindStage;
    private final NotifPipelineFlags mNotifPipelineFlags;

    @Inject
    LowPriorityInflationHelper(
            NotificationGroupManagerLegacy groupManager,
            RowContentBindStage rowContentBindStage,
            NotifPipelineFlags notifPipelineFlags) {
        mGroupManager = groupManager;
        mRowContentBindStage = rowContentBindStage;
        mNotifPipelineFlags = notifPipelineFlags;
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
        mNotifPipelineFlags.checkLegacyPipelineEnabled();
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
        mNotifPipelineFlags.checkLegacyPipelineEnabled();
        return entry.isAmbient() && !mGroupManager.isChildInGroup(entry);
    }
}
