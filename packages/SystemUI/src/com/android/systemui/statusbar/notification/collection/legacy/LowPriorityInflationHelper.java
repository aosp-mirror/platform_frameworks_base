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

import javax.inject.Inject;

/**
 * Helper class that provide methods to help check when we need to inflate a low priority version
 * ot notification content.
 */
@SysUISingleton
public class LowPriorityInflationHelper {
    private final NotificationGroupManagerLegacy mGroupManager;
    private final NotifPipelineFlags mNotifPipelineFlags;

    @Inject
    LowPriorityInflationHelper(
            NotificationGroupManagerLegacy groupManager,
            NotifPipelineFlags notifPipelineFlags) {
        mGroupManager = groupManager;
        mNotifPipelineFlags = notifPipelineFlags;
    }

    /**
     * Whether the notification should inflate a low priority version of its content views.
     */
    public boolean shouldUseLowPriorityView(NotificationEntry entry) {
        mNotifPipelineFlags.checkLegacyPipelineEnabled();
        return entry.isAmbient() && !mGroupManager.isChildInGroup(entry);
    }
}
