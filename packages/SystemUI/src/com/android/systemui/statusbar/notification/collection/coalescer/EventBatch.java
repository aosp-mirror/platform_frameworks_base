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

package com.android.systemui.statusbar.notification.collection.coalescer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a set of notification post events for a particular notification group.
 */
public class EventBatch {
    /** SystemClock.uptimeMillis() */
    final long mCreatedTimestamp;

    /** SBN.getGroupKey -- same for all members */
    final String mGroupKey;

    /**
     * All members of the batch. Must share the same group key. Includes both children and
     * summaries.
     */
    final List<CoalescedEvent> mMembers = new ArrayList<>();

    @Nullable Runnable mCancelShortTimeout;

    EventBatch(long createdTimestamp, String groupKey) {
        mCreatedTimestamp = createdTimestamp;
        this.mGroupKey = groupKey;
    }
}
