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

package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable;

import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;

import java.util.Comparator;
import java.util.List;

/**
 * Pluggable for participating in notif sorting. See {@link NotifPipeline#setComparators(List)}.
 */
public abstract class NotifComparator
        extends Pluggable<NotifComparator>
        implements Comparator<ListEntry> {

    protected NotifComparator(String name) {
        super(name);
    }

    /**
     * Compare two ListEntries. Note that these might be either NotificationEntries or GroupEntries.
     *
     * @return a negative integer, zero, or a positive integer as the first argument is less than
     *      equal to, or greater than the second (same as standard Comparator<> interface).
     */
    public abstract int compare(ListEntry o1, ListEntry o2);
}
