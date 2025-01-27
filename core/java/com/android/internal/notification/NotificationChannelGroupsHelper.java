/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.notification;

import static android.app.NotificationChannel.SYSTEM_RESERVED_IDS;
import static android.app.NotificationManager.IMPORTANCE_NONE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.service.notification.Flags;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NotificationChannelGroupHelper contains helper methods for associating channels with the groups
 * they belong to, matching by ID.
 */
public class NotificationChannelGroupsHelper {
    /**
     * Set of parameters passed into
     * {@link NotificationChannelGroupsHelper#getGroupsWithChannels(Collection, Map, Params)}.
     *
     * @param includeDeleted Whether to include deleted channels.
     * @param includeNonGrouped Whether to include channels that are not associated with a group.
     * @param includeEmpty Whether to include groups containing no channels.
     * @param includeAllBlockedWithFilter Whether to include channels that are blocked from
     *                                    sending notifications along with channels specified by
     *                                    the filter. This setting only takes effect when
     *                                    channelFilter is not {@code null}, and if true will
     *                                    include all blocked channels in the output (regardless
     *                                    of whether they are included in the filter).
     * @param channelFilter If non-null, a specific set of channels to include. If a channel
     *                      matching this filter is blocked, it will still be included even
     *                      if includeAllBlockedWithFilter=false.
     */
    public record Params(
            boolean includeDeleted,
            boolean includeNonGrouped,
            boolean includeEmpty,
            boolean includeAllBlockedWithFilter,
            Set<String> channelFilter
    ) {
        /**
         * Default set of parameters used to specify the behavior of
         * {@link INotificationManager#getNotificationChannelGroups(String)}. This will include
         * output for all groups, including those without channels, but not any ungrouped channels.
         */
        public static Params forAllGroups() {
            return new Params(
                    /* includeDeleted= */ false,
                    /* includeNonGrouped= */ false,
                    /* includeEmpty= */ true,
                    /* includeAllBlockedWithFilter= */ true,
                    /* channelFilter= */ null);
        }

        /**
         * Parameters to get groups for all channels, including those not associated with any groups
         * and optionally including deleted channels as well. Channels not associated with a group
         * are returned inside a group with id {@code null}.
         *
         * @param includeDeleted Whether to include deleted channels.
         */
        public static Params forAllChannels(boolean includeDeleted) {
            return new Params(
                    includeDeleted,
                    /* includeNonGrouped= */ true,
                    /* includeEmpty= */ false,
                    /* includeAllBlockedWithFilter= */ true,
                    /* channelFilter= */ null);
        }

        /**
         * Parameters to collect groups only for channels specified by the channel filter, as well
         * as any blocked channels (independent of whether they exist in the filter).
         * @param channelFilter Specific set of channels to return.
         */
        public static Params onlySpecifiedOrBlockedChannels(Set<String> channelFilter) {
            return new Params(
                    /* includeDeleted= */ false,
                    /* includeNonGrouped= */ true,
                    /* includeEmpty= */ false,
                    /* includeAllBlockedWithFilter= */ true,
                    channelFilter);
        }
    }

    /**
     * Retrieve the {@link NotificationChannelGroup} object specified by the given groupId, if it
     * exists, with the list of channels filled in from the provided available channels.
     *
     * @param groupId The ID of the group to return.
     * @param allChannels A list of all channels associated with the package.
     * @param allGroups A map of group ID -> NotificationChannelGroup objects.
     */
    public static @Nullable NotificationChannelGroup getGroupWithChannels(@NonNull String groupId,
            @NonNull Collection<NotificationChannel> allChannels,
            @NonNull Map<String, NotificationChannelGroup> allGroups,
            boolean includeDeleted) {
        NotificationChannelGroup group = null;
        if (allGroups.containsKey(groupId)) {
            group = allGroups.get(groupId).clone();
            group.setChannels(new ArrayList<>());
            for (NotificationChannel nc : allChannels) {
                if (includeDeleted || !nc.isDeleted()) {
                    if (groupId.equals(nc.getGroup())) {
                        group.addChannel(nc);
                    }
                }
            }
        }
        return group;
    }

    /**
     * Returns a list of groups with their associated channels filled in.
     *
     * @param allChannels All available channels that may be associated with these groups.
     * @param allGroups Map of group ID -> {@link NotificationChannelGroup} objects.
     * @param params Params indicating which channels and which groups to include.
     */
    public static @NonNull List<NotificationChannelGroup> getGroupsWithChannels(
            @NonNull Collection<NotificationChannel> allChannels,
            @NonNull Map<String, NotificationChannelGroup> allGroups,
            Params params) {
        Map<String, NotificationChannelGroup> outputGroups = new ArrayMap<>();
        NotificationChannelGroup nonGrouped = new NotificationChannelGroup(null, null);
        for (NotificationChannel nc : allChannels) {
            boolean includeChannel = (params.includeDeleted || !nc.isDeleted())
                    && (params.channelFilter == null
                            || (params.includeAllBlockedWithFilter
                                    && nc.getImportance() == IMPORTANCE_NONE)
                            || params.channelFilter.contains(nc.getId()))
                    && (!Flags.notificationClassification()
                            || !SYSTEM_RESERVED_IDS.contains(nc.getId()));
            if (includeChannel) {
                if (nc.getGroup() != null) {
                    if (allGroups.get(nc.getGroup()) != null) {
                        NotificationChannelGroup ncg = outputGroups.get(nc.getGroup());
                        if (ncg == null) {
                            ncg = allGroups.get(nc.getGroup()).clone();
                            ncg.setChannels(new ArrayList<>());
                            outputGroups.put(nc.getGroup(), ncg);
                        }
                        ncg.addChannel(nc);
                    }
                } else {
                    nonGrouped.addChannel(nc);
                }
            }
        }
        if (params.includeNonGrouped && nonGrouped.getChannels().size() > 0) {
            outputGroups.put(null, nonGrouped);
        }
        if (params.includeEmpty) {
            for (NotificationChannelGroup group : allGroups.values()) {
                if (!outputGroups.containsKey(group.getId())) {
                    outputGroups.put(group.getId(), group);
                }
            }
        }
        return new ArrayList<>(outputGroups.values());
    }
}
