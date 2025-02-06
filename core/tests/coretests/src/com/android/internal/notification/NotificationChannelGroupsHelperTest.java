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

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_NONE;

import static com.android.internal.notification.NotificationChannelGroupsHelper.getGroupWithChannels;
import static com.android.internal.notification.NotificationChannelGroupsHelper.getGroupsWithChannels;

import static com.google.common.truth.Truth.assertThat;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.notification.NotificationChannelGroupsHelper.Params;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class NotificationChannelGroupsHelperTest {
    private Collection<NotificationChannel> mChannels;
    private Map<String, NotificationChannelGroup> mGroups;

    @Before
    public void setUp() {
        // Test data setup.
        // Channels and their corresponding groups:
        //   * "regular": a channel that is not deleted or blocked. In group A.
        //   * "blocked": blocked channel. In group A.
        //   * "deleted": deleted channel. In group A.
        //   * "adrift": regular channel. No group.
        //   * "gone": deleted channel. No group.
        //   * "alternate": regular channel. In group B.
        //   * "another blocked": blocked channel. In group B.
        //   * "another deleted": deleted channel. In group C.
        //   * Additionally, there is an empty group D.
        mChannels = List.of(makeChannel("regular", "a", false, false),
                makeChannel("blocked", "a", true, false),
                makeChannel("deleted", "a", false, true),
                makeChannel("adrift", null, false, false),
                makeChannel("gone", null, false, true),
                makeChannel("alternate", "b", false, false),
                makeChannel("anotherBlocked", "b", true, false),
                makeChannel("anotherDeleted", "c", false, true));

        mGroups = Map.of("a", new NotificationChannelGroup("a", "a"),
                "b", new NotificationChannelGroup("b", "b"),
                "c", new NotificationChannelGroup("c", "c"),
                "d", new NotificationChannelGroup("d", "d"));
    }

    @Test
    public void testGetGroup_noDeleted() {
        NotificationChannelGroup res = getGroupWithChannels("a", mChannels, mGroups, false);
        assertThat(res).isNotNull();
        assertThat(res.getChannels()).hasSize(2);  // "regular" & "blocked"
        assertThat(res.getChannels()).containsExactlyElementsIn(List.of(
                makeChannel("regular", "a", false, false),
                makeChannel("blocked", "a", true, false)));
    }

    @Test
    public void testGetGroup_includeDeleted() {
        NotificationChannelGroup res = getGroupWithChannels("c", mChannels, mGroups, true);
        assertThat(res).isNotNull();
        assertThat(res.getChannels()).hasSize(1);
        assertThat(res.getChannels().getFirst()).isEqualTo(
                makeChannel("anotherDeleted", "c", false, true));
    }

    @Test
    public void testGetGroup_empty() {
        NotificationChannelGroup res = getGroupWithChannels("d", mChannels, mGroups, true);
        assertThat(res).isNotNull();
        assertThat(res.getChannels()).isEmpty();
    }

    @Test
    public void testGetGroup_emptyBecauseNoChannelMatch() {
        NotificationChannelGroup res = getGroupWithChannels("c", mChannels, mGroups, false);
        assertThat(res).isNotNull();
        assertThat(res.getChannels()).isEmpty();
    }

    @Test
    public void testGetGroup_nonexistent() {
        NotificationChannelGroup res = getGroupWithChannels("e", mChannels, mGroups, true);
        assertThat(res).isNull();
    }

    @Test
    public void testGetGroups_paramsForAllGroups() {
        // deleted=false, nongrouped=false, empty=true, blocked=true, no channel filter
        List<NotificationChannelGroup> res = getGroupsWithChannels(mChannels, mGroups,
                Params.forAllGroups());

        NotificationChannelGroup expectedA = new NotificationChannelGroup("a", "a");
        expectedA.setChannels(List.of(
                makeChannel("regular", "a", false, false),
                makeChannel("blocked", "a", true, false)));

        NotificationChannelGroup expectedB = new NotificationChannelGroup("b", "b");
        expectedB.setChannels(List.of(
                makeChannel("alternate", "b", false, false),
                makeChannel("anotherBlocked", "b", true, false)));

        NotificationChannelGroup expectedC = new NotificationChannelGroup("c", "c");
        expectedC.setChannels(new ArrayList<>());  // empty, no deleted

        NotificationChannelGroup expectedD = new NotificationChannelGroup("d", "d");
        expectedD.setChannels(new ArrayList<>());  // empty

        assertThat(res).containsExactly(expectedA, expectedB, expectedC, expectedD);
    }

    @Test
    public void testGetGroups_paramsForAllChannels_noDeleted() {
        // Excluding deleted channels to means group C is not included because it's "empty"
        List<NotificationChannelGroup> res = getGroupsWithChannels(mChannels, mGroups,
                Params.forAllChannels(false));

        NotificationChannelGroup expectedA = new NotificationChannelGroup("a", "a");
        expectedA.setChannels(List.of(
                makeChannel("regular", "a", false, false),
                makeChannel("blocked", "a", true, false)));

        NotificationChannelGroup expectedB = new NotificationChannelGroup("b", "b");
        expectedB.setChannels(List.of(
                makeChannel("alternate", "b", false, false),
                makeChannel("anotherBlocked", "b", true, false)));

        NotificationChannelGroup expectedUngrouped = new NotificationChannelGroup(null, null);
        expectedUngrouped.setChannels(List.of(
                makeChannel("adrift", null, false, false),
                makeChannel("gone", null, false, true)));

        assertThat(res).containsExactly(expectedA, expectedB, expectedUngrouped);
    }

    @Test
    public void testGetGroups_paramsForAllChannels_withDeleted() {
        // This will get everything!
        List<NotificationChannelGroup> res = getGroupsWithChannels(mChannels, mGroups,
                Params.forAllChannels(true));

        NotificationChannelGroup expectedA = new NotificationChannelGroup("a", "a");
        expectedA.setChannels(List.of(
                makeChannel("regular", "a", false, false),
                makeChannel("blocked", "a", true, false),
                makeChannel("deleted", "a", false, true)));

        NotificationChannelGroup expectedB = new NotificationChannelGroup("b", "b");
        expectedB.setChannels(List.of(
                makeChannel("alternate", "b", false, false),
                makeChannel("anotherBlocked", "b", true, false)));

        NotificationChannelGroup expectedC = new NotificationChannelGroup("c", "c");
        expectedC.setChannels(List.of(makeChannel("anotherDeleted", "c", false, true)));

        // no D, because D is empty

        NotificationChannelGroup expectedUngrouped = new NotificationChannelGroup(null, null);
        expectedUngrouped.setChannels(List.of(makeChannel("adrift", null, false, false)));

        assertThat(res).containsExactly(expectedA, expectedB, expectedC, expectedUngrouped);
    }

    @Test
    public void testGetGroups_onlySpecifiedOrBlocked() {
        Set<String> filter = Set.of("regular", "blocked", "adrift", "anotherDeleted");

        // also not including deleted channels to check intersection of those params
        List<NotificationChannelGroup> res = getGroupsWithChannels(mChannels, mGroups,
                Params.onlySpecifiedOrBlockedChannels(filter));

        NotificationChannelGroup expectedA = new NotificationChannelGroup("a", "a");
        expectedA.setChannels(List.of(
                makeChannel("regular", "a", false, false),
                makeChannel("blocked", "a", true, false)));

        // While nothing matches the filter from group B, includeBlocked=true means all blocked
        // channels are included even if they are not in the filter.
        NotificationChannelGroup expectedB = new NotificationChannelGroup("b", "b");
        expectedB.setChannels(List.of(makeChannel("anotherBlocked", "b", true, false)));

        NotificationChannelGroup expectedC = new NotificationChannelGroup("c", "c");
        expectedC.setChannels(new ArrayList<>());  // deleted channel not included

        NotificationChannelGroup expectedD = new NotificationChannelGroup("d", "d");
        expectedD.setChannels(new ArrayList<>());  // empty

        NotificationChannelGroup expectedUngrouped = new NotificationChannelGroup(null, null);
        expectedUngrouped.setChannels(List.of(makeChannel("adrift", null, false, false)));

        assertThat(res).containsExactly(expectedA, expectedB, expectedC, expectedD,
                expectedUngrouped);
    }


    @Test
    public void testGetGroups_noBlockedWithFilter() {
        Set<String> filter = Set.of("regular", "blocked", "adrift");

        // The includeBlocked setting only takes effect if there is a channel filter.
        List<NotificationChannelGroup> res = getGroupsWithChannels(mChannels, mGroups,
                new Params(true, true, true, false, filter));

        // Even though includeBlocked=false, "blocked" is included because it's explicitly specified
        // by the channel filter.
        NotificationChannelGroup expectedA = new NotificationChannelGroup("a", "a");
        expectedA.setChannels(List.of(
                makeChannel("regular", "a", false, false),
                makeChannel("blocked", "a", true, false)));

        NotificationChannelGroup expectedB = new NotificationChannelGroup("b", "b");
        expectedB.setChannels(new ArrayList<>());  // no matches; blocked channel not in filter

        NotificationChannelGroup expectedC = new NotificationChannelGroup("c", "c");
        expectedC.setChannels(new ArrayList<>());  // no matches

        NotificationChannelGroup expectedD = new NotificationChannelGroup("d", "d");
        expectedD.setChannels(new ArrayList<>());  // empty

        NotificationChannelGroup expectedUngrouped = new NotificationChannelGroup(null, null);
        expectedUngrouped.setChannels(List.of(makeChannel("adrift", null, false, false)));

        assertThat(res).containsExactly(expectedA, expectedB, expectedC, expectedD,
                expectedUngrouped);
    }

    private NotificationChannel makeChannel(String id, String groupId, boolean blocked,
            boolean deleted) {
        NotificationChannel c = new NotificationChannel(id, id,
                blocked ? IMPORTANCE_NONE : IMPORTANCE_DEFAULT);
        if (deleted) {
            c.setDeleted(true);
        }
        if (groupId != null) {
            c.setGroup(groupId);
        }
        return c;
    }
}
