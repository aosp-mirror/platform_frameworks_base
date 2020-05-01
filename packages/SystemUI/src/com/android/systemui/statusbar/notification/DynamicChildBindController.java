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

package com.android.systemui.statusbar.notification;

import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_EXPANDED;
import static com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * Controller that binds/unbinds views content views on notification group children.
 *
 * We currently only show a limited number of notification children even if more exist, so we
 * can save memory by freeing content views when they're not visible and binding them again when
 * they get close to being visible.
 *
 * Eventually, when {@link NotifPipeline} takes over as the new notification pipeline, we'll have
 * more control over which notifications even make it to inflation in the first place and be able
 * to enforce this at an earlier stage at the level of the {@link ExpandableNotificationRow}, but
 * for now, we're just doing it at the level of content views.
 */
public class DynamicChildBindController {
    private final RowContentBindStage mStage;
    private final int mChildBindCutoff;

    @Inject
    public DynamicChildBindController(RowContentBindStage stage) {
        this(stage, CHILD_BIND_CUTOFF);
    }

    /**
     * @param childBindCutoff the cutoff where we no longer bother having content views bound
     */
    DynamicChildBindController(
            RowContentBindStage stage,
            int childBindCutoff) {
        mStage = stage;
        mChildBindCutoff = childBindCutoff;
    }

    /**
     * Update the content views, unbinding content views on children that won't be visible
     * and binding content views on children that will be visible eventually and previously unbound
     * children that are no longer children.
     *
     * @param groupNotifs map of top-level notifs to their children, if any
     */
    public void updateContentViews(
            Map<NotificationEntry, List<NotificationEntry>> groupNotifs) {
        for (NotificationEntry entry : groupNotifs.keySet()) {
            List<NotificationEntry> children = groupNotifs.get(entry);
            if (children == null) {
                if (!hasContent(entry)) {
                    // Case where child is updated to be top level
                    bindContent(entry);
                }
                continue;
            }
            for (int j = 0; j < children.size(); j++) {
                NotificationEntry childEntry = children.get(j);
                if (j >= mChildBindCutoff) {
                    if (hasContent(childEntry)) {
                        freeContent(childEntry);
                    }
                } else {
                    if (!hasContent(childEntry)) {
                        bindContent(childEntry);
                    }
                }
            }
        }
    }

    private boolean hasContent(NotificationEntry entry) {
        ExpandableNotificationRow row = entry.getRow();
        return row.getPrivateLayout().getContractedChild() != null
                || row.getPrivateLayout().getExpandedChild() != null;
    }

    private void freeContent(NotificationEntry entry) {
        RowContentBindParams params = mStage.getStageParams(entry);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_CONTRACTED);
        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_EXPANDED);
        mStage.requestRebind(entry, null);
    }

    private void bindContent(NotificationEntry entry) {
        RowContentBindParams params = mStage.getStageParams(entry);
        params.requireContentViews(FLAG_CONTENT_VIEW_CONTRACTED);
        params.requireContentViews(FLAG_CONTENT_VIEW_EXPANDED);
        mStage.requestRebind(entry, null);
    }

    /**
     * How big the buffer of extra views we keep around to be ready to show when we do need to
     * dynamically inflate.
     */
    private static final int EXTRA_VIEW_BUFFER_COUNT = 1;

    private static final int CHILD_BIND_CUTOFF =
            NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED + EXTRA_VIEW_BUFFER_COUNT;
}
