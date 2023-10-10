/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.notification;

import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_CAN_COLORIZE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_ONGOING_EVENT;

import static com.android.server.notification.GroupHelper.BASE_FLAGS;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArrayMap;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@SuppressLint("GuardedBy") // It's ok for this test to access guarded methods from the class.
@RunWith(AndroidJUnit4.class)
public class GroupHelperTest extends UiServiceTestCase {
    private @Mock GroupHelper.Callback mCallback;

    private final static int AUTOGROUP_AT_COUNT = 7;
    private GroupHelper mGroupHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mGroupHelper = new GroupHelper(AUTOGROUP_AT_COUNT, mCallback);
    }

    private StatusBarNotification getSbn(String pkg, int id, String tag,
            UserHandle user, String groupKey) {
        Notification.Builder nb = new Notification.Builder(getContext(), "test_channel_id")
                .setContentTitle("A")
                .setWhen(1205);
        if (groupKey != null) {
            nb.setGroup(groupKey);
        }
        return new StatusBarNotification(pkg, pkg, id, tag, 0, 0, nb.build(), user, null,
                System.currentTimeMillis());
    }

    private StatusBarNotification getSbn(String pkg, int id, String tag,
            UserHandle user) {
        return getSbn(pkg, id, tag, user, null);
    }

    @Test
    public void testGetAutogroupSummaryFlags_noChildren() {
        ArrayMap<String, Integer> children = new ArrayMap<>();

        assertEquals(BASE_FLAGS, mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoing() {
        ArrayMap<String, Integer> children = new ArrayMap<>();
        children.put("a", 0);
        children.put("b", FLAG_ONGOING_EVENT);
        children.put("c", FLAG_BUBBLE);

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoingNoClear() {
        ArrayMap<String, Integer> children = new ArrayMap<>();
        children.put("a", 0);
        children.put("b", FLAG_ONGOING_EVENT|FLAG_NO_CLEAR);
        children.put("c", FLAG_BUBBLE);

        assertEquals(FLAG_NO_CLEAR | FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoingBubble() {
        ArrayMap<String, Integer> children = new ArrayMap<>();
        children.put("a", 0);
        children.put("b", FLAG_ONGOING_EVENT | FLAG_BUBBLE);
        children.put("c", FLAG_BUBBLE);

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_multipleOngoing() {
        ArrayMap<String, Integer> children = new ArrayMap<>();
        children.put("a", 0);
        children.put("b", FLAG_ONGOING_EVENT);
        children.put("c", FLAG_BUBBLE);
        children.put("d", FLAG_ONGOING_EVENT);

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneAutoCancel() {
        ArrayMap<String, Integer> children = new ArrayMap<>();
        children.put("a", 0);
        children.put("b", FLAG_AUTO_CANCEL);
        children.put("c", FLAG_BUBBLE);

        assertEquals(BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_allAutoCancel() {
        ArrayMap<String, Integer> children = new ArrayMap<>();
        children.put("a", FLAG_AUTO_CANCEL);
        children.put("b", FLAG_AUTO_CANCEL | FLAG_CAN_COLORIZE);
        children.put("c", FLAG_AUTO_CANCEL);
        children.put("d", FLAG_AUTO_CANCEL | FLAG_FOREGROUND_SERVICE);

        assertEquals(FLAG_AUTO_CANCEL | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_allAutoCancelOneOngoing() {
        ArrayMap<String, Integer> children = new ArrayMap<>();
        children.put("a", FLAG_AUTO_CANCEL);
        children.put("b", FLAG_AUTO_CANCEL | FLAG_CAN_COLORIZE);
        children.put("c", FLAG_AUTO_CANCEL);
        children.put("d", FLAG_AUTO_CANCEL | FLAG_FOREGROUND_SERVICE | FLAG_ONGOING_EVENT);

        assertEquals(FLAG_AUTO_CANCEL| FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testNoGroup_postingUnderLimit() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM),
                    false);
        }
        verifyZeroInteractions(mCallback);
    }

    @Test
    public void testNoGroup_multiPackage() {
        final String pkg = "package";
        final String pkg2 = "package2";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM),
                    false);
        }
        mGroupHelper.onNotificationPosted(
                getSbn(pkg2, AUTOGROUP_AT_COUNT, "four", UserHandle.SYSTEM), false);
        verifyZeroInteractions(mCallback);
    }

    @Test
    public void testNoGroup_multiUser() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM),
                    false);
        }
        mGroupHelper.onNotificationPosted(
                getSbn(pkg, AUTOGROUP_AT_COUNT, "four", UserHandle.of(7)), false);
        verifyZeroInteractions(mCallback);
    }

    @Test
    public void testNoGroup_someAreGrouped() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(
                    getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM), false);
        }
        mGroupHelper.onNotificationPosted(
                getSbn(pkg, AUTOGROUP_AT_COUNT, "four", UserHandle.SYSTEM, "a"), false);
        verifyZeroInteractions(mCallback);
    }

    @Test
    public void testAddSummary() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            mGroupHelper.onNotificationPosted(
                    getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM), false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(BASE_FLAGS));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testAddSummary_oneChildOngoing_summaryOngoing() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(BASE_FLAGS | FLAG_ONGOING_EVENT));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testAddSummary_oneChildAutoCancel_summaryNotAutoCancel() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            }
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(BASE_FLAGS));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testAddSummary_allChildrenAutoCancel_summaryAutoCancel() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(BASE_FLAGS | FLAG_AUTO_CANCEL));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testAddSummary_summaryAutoCancelNoClear() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_NO_CLEAR;
            }
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(BASE_FLAGS | FLAG_AUTO_CANCEL | FLAG_NO_CLEAR));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testAutoGrouped_allOngoing_updateChildNotOngoing() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_ONGOING_EVENT;
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // One notification is no longer ongoing
        notifications.get(0).getNotification().flags &= ~FLAG_ONGOING_EVENT;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary should keep FLAG_ONGOING_EVENT if any child has it
        verify(mCallback).updateAutogroupSummary(
                anyInt(), anyString(), eq(BASE_FLAGS | FLAG_ONGOING_EVENT));
    }

    @Test
    public void testAutoGrouped_singleOngoing_removeOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // remove ongoing
        mGroupHelper.onNotificationRemoved(notifications.get(0));

        // Summary is no longer ongoing
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), eq(BASE_FLAGS));
    }

    @Test
    public void testAutoGrouped_noOngoing_updateOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // update to ongoing
        notifications.get(0).getNotification().flags |= FLAG_ONGOING_EVENT;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary is now ongoing
        verify(mCallback).updateAutogroupSummary(
                anyInt(), anyString(), eq(BASE_FLAGS | FLAG_ONGOING_EVENT));
    }

    @Test
    public void testAutoGrouped_noOngoing_addOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // add ongoing
        StatusBarNotification sbn = getSbn(pkg, AUTOGROUP_AT_COUNT + 1, null, UserHandle.SYSTEM);
        sbn.getNotification().flags |= FLAG_ONGOING_EVENT;
        mGroupHelper.onNotificationPosted(sbn, true);

        // Summary is now ongoing
        verify(mCallback).updateAutogroupSummary(
                anyInt(), anyString(), eq(BASE_FLAGS | FLAG_ONGOING_EVENT));
    }

    @Test
    public void testAutoGrouped_singleOngoing_appGroupOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // app group the ongoing child
        StatusBarNotification sbn = getSbn(pkg, 0, "0", UserHandle.SYSTEM, "app group now");
        mGroupHelper.onNotificationPosted(sbn, true);

        // Summary is no longer ongoing
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), eq(BASE_FLAGS));
    }

    @Test
    public void testAutoGrouped_singleOngoing_removeNonOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // remove ongoing
        mGroupHelper.onNotificationRemoved(notifications.get(1));

        // Summary is still ongoing
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testAutoGrouped_allAutoCancel_updateChildNotAutoCancel() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // One notification is no longer autocancelable
        notifications.get(0).getNotification().flags &= ~FLAG_AUTO_CANCEL;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary should no longer be autocancelable
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), eq(BASE_FLAGS));
    }

    @Test
    public void testAutoGrouped_almostAllAutoCancel_updateChildAutoCancel() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i != 0) {
                sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            }
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // Missing notification is now autocancelable
        notifications.get(0).getNotification().flags |= FLAG_AUTO_CANCEL;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary should now autocancelable
        verify(mCallback).updateAutogroupSummary(
                anyInt(), anyString(), eq(BASE_FLAGS | FLAG_AUTO_CANCEL));
    }

    @Test
    public void testAutoGrouped_allAutoCancel_updateChildAppGrouped() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // One notification is now grouped by app
        StatusBarNotification sbn = getSbn(pkg, 0, "0", UserHandle.SYSTEM, "app group now");
        mGroupHelper.onNotificationPosted(sbn, true);

        // Summary should be still be autocancelable
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testAutoGrouped_allAutoCancel_removeChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<StatusBarNotification>  notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(sbn);
        }

        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        mGroupHelper.onNotificationRemoved(notifications.get(0));

        // Summary should still be autocancelable
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyInt());
    }

    @Test
    public void testDropToZeroRemoveGroup() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(BASE_FLAGS));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        Mockito.reset(mCallback);

        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationRemoved(posted.remove(0));
        }
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        Mockito.reset(mCallback);

        mGroupHelper.onNotificationRemoved(posted.remove(0));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), anyString());
    }

    @Test
    public void testAppStartsGrouping() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(BASE_FLAGS));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        Mockito.reset(mCallback);

        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn =
                    getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, "app group");
            sbn.setOverrideGroupKey("autogrouped");
            mGroupHelper.onNotificationPosted(sbn, true);
            verify(mCallback, times(1)).removeAutoGroup(sbn.getKey());
            if (i < AUTOGROUP_AT_COUNT - 1) {
                verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
            }
        }
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), anyString());
    }

    @Test
    public void testNewNotificationsAddedToAutogroup_ifOriginalNotificationsCanceled() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(BASE_FLAGS));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        Mockito.reset(mCallback);

        for (int i = posted.size() - 2; i >= 0; i--) {
            mGroupHelper.onNotificationRemoved(posted.remove(i));
        }
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        Mockito.reset(mCallback);

        // only one child remains
        assertEquals(1, mGroupHelper.getNotGroupedByAppCount(UserHandle.USER_SYSTEM, pkg));

        // Add new notification; it should be autogrouped even though the total count is
        // < AUTOGROUP_AT_COUNT
        final StatusBarNotification sbn = getSbn(pkg, 5, String.valueOf(5), UserHandle.SYSTEM);
        posted.add(sbn);
        mGroupHelper.onNotificationPosted(sbn, true);
        verify(mCallback, times(1)).addAutoGroup(sbn.getKey());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), eq(BASE_FLAGS));
        verify(mCallback, never()).addAutoGroupSummary(
                anyInt(), anyString(), anyString(), anyInt());
    }
}
