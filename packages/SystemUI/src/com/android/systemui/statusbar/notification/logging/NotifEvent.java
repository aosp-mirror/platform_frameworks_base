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

package com.android.systemui.statusbar.notification.logging;

import android.annotation.IntDef;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.android.systemui.log.RichEvent;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifListBuilder;
import com.android.systemui.statusbar.notification.collection.notifcollection.GroupCoalescer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event related to notifications. {@link NotifLog} stores and prints these events for debugging
 * and triaging purposes. We do not store a copy of the status bar notification nor ranking
 * here to mitigate memory usage.
 */
public class NotifEvent extends RichEvent {
    /**
     * Initializes a rich event that includes an event type that matches with an index in the array
     * getEventLabels().
     */
    public NotifEvent init(@EventType int type, StatusBarNotification sbn,
            NotificationListenerService.Ranking ranking, String reason) {
        StringBuilder extraInfo = new StringBuilder(reason);
        if (sbn != null) {
            extraInfo.append(" " + sbn.getKey());
        }

        if (ranking != null) {
            extraInfo.append(" Ranking=");
            extraInfo.append(ranking.getRank());
        }
        super.init(INFO, type, extraInfo.toString());
        return this;
    }

    /**
     * Event labels for ListBuilderEvents
     * Index corresponds to an # in {@link EventType}
     */
    @Override
    public String[] getEventLabels() {
        assert (TOTAL_EVENT_LABELS
                == (TOTAL_NEM_EVENT_TYPES
                        + TOTAL_LIST_BUILDER_EVENT_TYPES
                        + TOTAL_COALESCER_EVENT_TYPES));
        return EVENT_LABELS;
    }

    /**
     * @return if this event occurred in {@link NotifListBuilder}
     */
    static boolean isListBuilderEvent(@EventType int type) {
        return isBetweenInclusive(type, 0, TOTAL_LIST_BUILDER_EVENT_TYPES);
    }

    /**
     * @return if this event occurred in {@link NotificationEntryManager}
     */
    static boolean isNemEvent(@EventType int type) {
        return isBetweenInclusive(type, TOTAL_LIST_BUILDER_EVENT_TYPES,
                TOTAL_LIST_BUILDER_EVENT_TYPES + TOTAL_NEM_EVENT_TYPES);
    }

    private static boolean isBetweenInclusive(int x, int a, int b) {
        return x >= a && x <= b;
    }

    @IntDef({
            // NotifListBuilder events:
            WARN,
            ON_BUILD_LIST,
            START_BUILD_LIST,
            DISPATCH_FINAL_LIST,
            LIST_BUILD_COMPLETE,
            PRE_GROUP_FILTER_INVALIDATED,
            PROMOTER_INVALIDATED,
            SECTIONS_PROVIDER_INVALIDATED,
            COMPARATOR_INVALIDATED,
            PARENT_CHANGED,
            FILTER_CHANGED,
            PROMOTER_CHANGED,
            PRE_RENDER_FILTER_INVALIDATED,

            // NotificationEntryManager events:
            NOTIF_ADDED,
            NOTIF_REMOVED,
            NOTIF_UPDATED,
            FILTER,
            SORT,
            FILTER_AND_SORT,
            NOTIF_VISIBILITY_CHANGED,
            LIFETIME_EXTENDED,
            REMOVE_INTERCEPTED,
            INFLATION_ABORTED,
            INFLATED,

            // GroupCoalescer
            COALESCED_EVENT,
            EARLY_BATCH_EMIT,
            EMIT_EVENT_BATCH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}

    private static final String[] EVENT_LABELS =
            new String[]{
                    // NotifListBuilder labels:
                    "Warning",
                    "OnBuildList",
                    "StartBuildList",
                    "DispatchFinalList",
                    "ListBuildComplete",
                    "FilterInvalidated",
                    "PromoterInvalidated",
                    "SectionsProviderInvalidated",
                    "ComparatorInvalidated",
                    "ParentChanged",
                    "FilterChanged",
                    "PromoterChanged",
                    "FinalFilterInvalidated",

                    // NEM event labels:
                    "NotifAdded",
                    "NotifRemoved",
                    "NotifUpdated",
                    "Filter",
                    "Sort",
                    "FilterAndSort",
                    "NotifVisibilityChanged",
                    "LifetimeExtended",
                    "RemoveIntercepted",
                    "InflationAborted",
                    "Inflated",

                    // GroupCoalescer labels:
                    "CoalescedEvent",
                    "EarlyBatchEmit",
                    "EmitEventBatch"
            };

    private static final int TOTAL_EVENT_LABELS = EVENT_LABELS.length;

    /**
     * Events related to {@link NotifListBuilder}
     */
    public static final int WARN = 0;
    public static final int ON_BUILD_LIST = 1;
    public static final int START_BUILD_LIST = 2;
    public static final int DISPATCH_FINAL_LIST = 3;
    public static final int LIST_BUILD_COMPLETE = 4;
    public static final int PRE_GROUP_FILTER_INVALIDATED = 5;
    public static final int PROMOTER_INVALIDATED = 6;
    public static final int SECTIONS_PROVIDER_INVALIDATED = 7;
    public static final int COMPARATOR_INVALIDATED = 8;
    public static final int PARENT_CHANGED = 9;
    public static final int FILTER_CHANGED = 10;
    public static final int PROMOTER_CHANGED = 11;
    public static final int PRE_RENDER_FILTER_INVALIDATED = 12;
    private static final int TOTAL_LIST_BUILDER_EVENT_TYPES = 13;

    /**
     * Events related to {@link NotificationEntryManager}
     */
    private static final int NEM_EVENT_START_INDEX = TOTAL_LIST_BUILDER_EVENT_TYPES;
    public static final int NOTIF_ADDED = NEM_EVENT_START_INDEX;
    public static final int NOTIF_REMOVED = NEM_EVENT_START_INDEX + 1;
    public static final int NOTIF_UPDATED = NEM_EVENT_START_INDEX + 2;
    public static final int FILTER = NEM_EVENT_START_INDEX + 3;
    public static final int SORT = NEM_EVENT_START_INDEX + 4;
    public static final int FILTER_AND_SORT = NEM_EVENT_START_INDEX + 5;
    public static final int NOTIF_VISIBILITY_CHANGED = NEM_EVENT_START_INDEX + 6;
    public static final int LIFETIME_EXTENDED = NEM_EVENT_START_INDEX + 7;
    // unable to remove notif - removal intercepted by {@link NotificationRemoveInterceptor}
    public static final int REMOVE_INTERCEPTED = NEM_EVENT_START_INDEX + 8;
    public static final int INFLATION_ABORTED = NEM_EVENT_START_INDEX + 9;
    public static final int INFLATED = NEM_EVENT_START_INDEX + 10;
    private static final int TOTAL_NEM_EVENT_TYPES = 11;

    /**
     * Events related to {@link GroupCoalescer}
     */
    private static final int COALESCER_EVENT_START_INDEX = NEM_EVENT_START_INDEX
            + TOTAL_NEM_EVENT_TYPES;
    public static final int COALESCED_EVENT = COALESCER_EVENT_START_INDEX;
    public static final int EARLY_BATCH_EMIT = COALESCER_EVENT_START_INDEX + 1;
    public static final int EMIT_EVENT_BATCH = COALESCER_EVENT_START_INDEX + 2;
    private static final int TOTAL_COALESCER_EVENT_TYPES = 3;
}
