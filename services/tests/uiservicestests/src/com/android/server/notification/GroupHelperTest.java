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

import static android.app.Notification.COLOR_DEFAULT;
import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_CAN_COLORIZE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.Notification.VISIBILITY_PRIVATE;
import static android.app.Notification.VISIBILITY_PUBLIC;
import static android.app.Notification.VISIBILITY_SECRET;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;

import static com.android.server.notification.GroupHelper.BASE_FLAGS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.UiServiceTestCase;
import com.android.server.notification.GroupHelper.NotificationAttributes;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

@SmallTest
@SuppressLint("GuardedBy") // It's ok for this test to access guarded methods from the class.
@RunWith(ParameterizedAndroidJunit4.class)
public class GroupHelperTest extends UiServiceTestCase {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    private final int DEFAULT_VISIBILITY = VISIBILITY_PRIVATE;

    private @Mock GroupHelper.Callback mCallback;
    private @Mock PackageManager mPackageManager;

    private final static int AUTOGROUP_AT_COUNT = 7;
    private GroupHelper mGroupHelper;
    private @Mock Icon mSmallIcon;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(
                android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST);
    }

    public GroupHelperTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mGroupHelper = new GroupHelper(getContext(), mPackageManager, AUTOGROUP_AT_COUNT,
                mCallback);

        NotificationRecord r = mock(NotificationRecord.class);
        StatusBarNotification sbn = getSbn("package", 0, "0", UserHandle.SYSTEM);
        when(r.getNotification()).thenReturn(sbn.getNotification());
        when(r.getSbn()).thenReturn(sbn);
        when(mSmallIcon.sameAs(mSmallIcon)).thenReturn(true);
    }

    private StatusBarNotification getSbn(String pkg, int id, String tag,
            UserHandle user, String groupKey, Icon smallIcon, int iconColor) {
        Notification.Builder nb = new Notification.Builder(getContext(), "test_channel_id")
                .setContentTitle("A")
                .setWhen(1205)
                .setSmallIcon(smallIcon)
                .setColor(iconColor);
        if (groupKey != null) {
            nb.setGroup(groupKey);
        }
        return new StatusBarNotification(pkg, pkg, id, tag, 0, 0, nb.build(), user, null,
                System.currentTimeMillis());
    }

    private StatusBarNotification getSbn(String pkg, int id, String tag,
            UserHandle user, String groupKey) {
        return getSbn(pkg, id, tag, user, groupKey, mSmallIcon, Notification.COLOR_DEFAULT);
    }

    private StatusBarNotification getSbn(String pkg, int id, String tag,
            UserHandle user) {
        return getSbn(pkg, id, tag, user, null);
    }

    private NotificationAttributes getNotificationAttributes(int flags) {
        return new NotificationAttributes(flags, mSmallIcon, COLOR_DEFAULT, DEFAULT_VISIBILITY);
    }

    @Test
    public void testGetAutogroupSummaryFlags_noChildren() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();

        assertEquals(BASE_FLAGS, mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoing() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoingNoClear() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT | FLAG_NO_CLEAR));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(FLAG_NO_CLEAR | FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoingBubble() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT | FLAG_BUBBLE));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_multipleOngoing() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));
        children.put("d", getNotificationAttributes(FLAG_ONGOING_EVENT));

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneAutoCancel() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_allAutoCancel() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("b", getNotificationAttributes(FLAG_AUTO_CANCEL | FLAG_CAN_COLORIZE));
        children.put("c", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("d", getNotificationAttributes(FLAG_AUTO_CANCEL | FLAG_FOREGROUND_SERVICE));

        assertEquals(FLAG_AUTO_CANCEL | BASE_FLAGS,
                mGroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_allAutoCancelOneOngoing() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("b", getNotificationAttributes(FLAG_AUTO_CANCEL | FLAG_CAN_COLORIZE));
        children.put("c", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("d", getNotificationAttributes(
                FLAG_AUTO_CANCEL | FLAG_FOREGROUND_SERVICE | FLAG_ONGOING_EVENT));

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
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_alwaysAutogroup() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            mGroupHelper.onNotificationPosted(
                    getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM), false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            assertThat(mGroupHelper.onNotificationPosted(
                    getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM), false)).isFalse();
        }
        assertThat(mGroupHelper.onNotificationPosted(
                getSbn(pkg, AUTOGROUP_AT_COUNT - 1, String.valueOf(AUTOGROUP_AT_COUNT - 1),
                        UserHandle.SYSTEM), false)).isTrue();
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_oneChildOngoing_summaryOngoing_alwaysAutogroup() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
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
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_oneChildAutoCancel_summaryNotAutoCancel_alwaysAutogroup() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            if (i == 0) {
                sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            }
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
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
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_allChildrenAutoCancel_summaryAutoCancel_alwaysAutogroup() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_allChildrenAutoCancel_summaryAutoCancel() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            sbn.getNotification().flags |= FLAG_AUTO_CANCEL;
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_summaryAutoCancelNoClear_alwaysAutogroup() {
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
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL | FLAG_NO_CLEAR)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
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
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL | FLAG_NO_CLEAR)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
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
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
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
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
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
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
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
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
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
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
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
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
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
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
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
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL)));
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
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
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
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testDropToZeroRemoveGroup_disableFlag() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
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
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testDropToZeroRemoveGroup() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
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
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAppStartsGrouping_disableFlag() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
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
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAppStartsGrouping() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
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
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testNewNotificationsAddedToAutogroup_ifOriginalNotificationsCanceled_alwaysGroup() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
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
        assertThat(mGroupHelper.onNotificationPosted(sbn, true)).isFalse();
        verify(mCallback, times(1)).addAutoGroup(sbn.getKey(), true);
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(), any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testNewNotificationsAddedToAutogroup_ifOriginalNotificationsCanceled() {
        final String pkg = "package";
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM);
            posted.add(sbn);
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
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
        assertThat(mGroupHelper.onNotificationPosted(sbn, true)).isTrue();
        // addAutoGroup not called on sbn, because the autogrouping is expected to be done
        // synchronously.
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(), any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAddSummary_sameIcon_sameColor_alwaysAutogroup() {
        final String pkg = "package";
        final Icon icon = mock(Icon.class);
        when(icon.sameAs(icon)).thenReturn(true);
        final int iconColor = Color.BLUE;
        final NotificationAttributes attr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                DEFAULT_VISIBILITY);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    icon, iconColor);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(attr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());

        // After auto-grouping, add new notification with the same color
        StatusBarNotification sbn = getSbn(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, icon, iconColor);
        mGroupHelper.onNotificationPosted(sbn, true);

        // Check that the summary was updated
        //NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), eq(attr));
    }

    @Test
    @EnableFlags({Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE,
            android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST})
    public void testAddSummary_sameIcon_sameColor() {
        final String pkg = "package";
        final Icon icon = mock(Icon.class);
        when(icon.sameAs(icon)).thenReturn(true);
        final int iconColor = Color.BLUE;
        final NotificationAttributes attr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                DEFAULT_VISIBILITY);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    icon, iconColor);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(attr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());

        // After auto-grouping, add new notification with the same color
        StatusBarNotification sbn = getSbn(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, icon, iconColor);
        mGroupHelper.onNotificationPosted(sbn, true);

        // Check that the summary was updated
        //NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), eq(attr));
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAddSummary_diffIcon_diffColor_disableFlag() {
        final String pkg = "package";
        final Icon initialIcon = mock(Icon.class);
        when(initialIcon.sameAs(initialIcon)).thenReturn(true);
        final int initialIconColor = Color.BLUE;

        // Spy GroupHelper for getMonochromeAppIcon
        final Icon monochromeIcon = mock(Icon.class);
        when(monochromeIcon.sameAs(monochromeIcon)).thenReturn(true);
        GroupHelper groupHelper = spy(mGroupHelper);
        doReturn(monochromeIcon).when(groupHelper).getMonochromeAppIcon(eq(pkg));

        final NotificationAttributes initialAttr = new NotificationAttributes(BASE_FLAGS,
                initialIcon, initialIconColor, DEFAULT_VISIBILITY);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    initialIcon, initialIconColor);
            groupHelper.onNotificationPosted(sbn, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(initialAttr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());

        // After auto-grouping, add new notification with a different color
        final Icon newIcon = mock(Icon.class);
        final int newIconColor = Color.YELLOW;
        StatusBarNotification sbn = getSbn(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, newIcon,
                newIconColor);
        groupHelper.onNotificationPosted(sbn, true);

        // Summary should be updated to the default color and the icon to the monochrome icon
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, monochromeIcon,
                COLOR_DEFAULT, DEFAULT_VISIBILITY);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), eq(newAttr));
    }

    @Test
    @EnableFlags({Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE,
            android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST})
    public void testAddSummary_diffIcon_diffColor() {
        final String pkg = "package";
        final Icon initialIcon = mock(Icon.class);
        when(initialIcon.sameAs(initialIcon)).thenReturn(true);
        final int initialIconColor = Color.BLUE;

        // Spy GroupHelper for getMonochromeAppIcon
        final Icon monochromeIcon = mock(Icon.class);
        when(monochromeIcon.sameAs(monochromeIcon)).thenReturn(true);
        GroupHelper groupHelper = spy(mGroupHelper);
        doReturn(monochromeIcon).when(groupHelper).getMonochromeAppIcon(eq(pkg));

        final NotificationAttributes initialAttr = new NotificationAttributes(BASE_FLAGS,
                initialIcon, initialIconColor, DEFAULT_VISIBILITY);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    initialIcon, initialIconColor);
            groupHelper.onNotificationPosted(sbn, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(initialAttr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());

        // After auto-grouping, add new notification with a different color
        final Icon newIcon = mock(Icon.class);
        final int newIconColor = Color.YELLOW;
        StatusBarNotification sbn = getSbn(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, newIcon,
                newIconColor);
        groupHelper.onNotificationPosted(sbn, true);

        // Summary should be updated to the default color and the icon to the monochrome icon
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, monochromeIcon,
                COLOR_DEFAULT, DEFAULT_VISIBILITY);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), eq(newAttr));
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAddSummary_diffVisibility_alwaysAutogroup() {
        final String pkg = "package";
        final Icon icon = mock(Icon.class);
        when(icon.sameAs(icon)).thenReturn(true);
        final int iconColor = Color.BLUE;
        final NotificationAttributes attr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                VISIBILITY_PRIVATE);

        // Add notifications with same icon and color and default visibility (private)
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    icon, iconColor);
            mGroupHelper.onNotificationPosted(sbn, false);
        }
        // Check that the summary has private visibility
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(attr));

        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());

        // After auto-grouping, add new notification with public visibility
        StatusBarNotification sbn = getSbn(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, icon, iconColor);
        sbn.getNotification().visibility = VISIBILITY_PUBLIC;
        mGroupHelper.onNotificationPosted(sbn, true);

        // Check that the summary visibility was updated
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                VISIBILITY_PUBLIC);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), eq(newAttr));
    }

    @Test
    @EnableFlags({Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE,
            android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST})
    public void testAddSummary_diffVisibility() {
        final String pkg = "package";
        final Icon icon = mock(Icon.class);
        when(icon.sameAs(icon)).thenReturn(true);
        final int iconColor = Color.BLUE;
        final NotificationAttributes attr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                VISIBILITY_PRIVATE);

        // Add notifications with same icon and color and default visibility (private)
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    icon, iconColor);
            assertThat(mGroupHelper.onNotificationPosted(sbn, false)).isFalse();
        }
        // The last notification added will reach the autogroup threshold.
        StatusBarNotification sbn = getSbn(pkg, AUTOGROUP_AT_COUNT - 1,
                String.valueOf(AUTOGROUP_AT_COUNT - 1), UserHandle.SYSTEM, null, icon, iconColor);
        assertThat(mGroupHelper.onNotificationPosted(sbn, false)).isTrue();

        // Check that the summary has private visibility
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(attr));
        // The last sbn is expected to be added to autogroup synchronously.
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString());

        // After auto-grouping, add new notification with public visibility
        sbn = getSbn(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, icon, iconColor);
        sbn.getNotification().visibility = VISIBILITY_PUBLIC;
        assertThat(mGroupHelper.onNotificationPosted(sbn, true)).isTrue();

        // Check that the summary visibility was updated
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                VISIBILITY_PUBLIC);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), eq(newAttr));
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutoGrouped_diffIcon_diffColor_removeChild_updateTo_sameIcon_sameColor() {
        final String pkg = "package";
        final Icon initialIcon = mock(Icon.class);
        when(initialIcon.sameAs(initialIcon)).thenReturn(true);
        final int initialIconColor = Color.BLUE;
        final NotificationAttributes initialAttr = new NotificationAttributes(
                GroupHelper.FLAG_INVALID, initialIcon, initialIconColor, DEFAULT_VISIBILITY);

        // Add AUTOGROUP_AT_COUNT-1 notifications with same icon and color
        ArrayList<StatusBarNotification> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            StatusBarNotification sbn = getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    initialIcon, initialIconColor);
            notifications.add(sbn);
        }
        // And an additional notification with different icon and color
        final int lastIdx = AUTOGROUP_AT_COUNT - 1;
        StatusBarNotification newSbn = getSbn(pkg, lastIdx,
                String.valueOf(lastIdx), UserHandle.SYSTEM, null, mock(Icon.class),
                Color.YELLOW);
        notifications.add(newSbn);
        for (StatusBarNotification sbn: notifications) {
            mGroupHelper.onNotificationPosted(sbn, false);
        }

        // Remove last notification (the only one with different icon and color)
        mGroupHelper.onNotificationRemoved(notifications.get(lastIdx));

        // Summary should be updated to the common icon and color
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), eq(initialAttr));
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryIcon_sameIcon() {
        final String pkg = "package";
        final Icon icon = mock(Icon.class);
        when(icon.sameAs(icon)).thenReturn(true);

        // Create notifications with the same icon
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            childrenAttr.add(new NotificationAttributes(0, icon, COLOR_DEFAULT,
                    DEFAULT_VISIBILITY));
        }

        //Check that the generated summary icon is the same as the child notifications'
        Icon summaryIcon = mGroupHelper.getAutobundledSummaryAttributes(pkg, childrenAttr).icon;
        assertThat(summaryIcon).isEqualTo(icon);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryIcon_diffIcon() {
        final String pkg = "package";
        // Spy GroupHelper for getMonochromeAppIcon
        final Icon monochromeIcon = mock(Icon.class);
        GroupHelper groupHelper = spy(mGroupHelper);
        doReturn(monochromeIcon).when(groupHelper).getMonochromeAppIcon(eq(pkg));

        // Create notifications with different icons
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), COLOR_DEFAULT,
                    DEFAULT_VISIBILITY));
        }

        // Check that the generated summary icon is the monochrome icon
        Icon summaryIcon = groupHelper.getAutobundledSummaryAttributes(pkg, childrenAttr).icon;
        assertThat(summaryIcon).isEqualTo(monochromeIcon);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryIconColor_sameColor() {
        final String pkg = "package";
        final int iconColor = Color.BLUE;
        // Create notifications with the same icon color
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                    DEFAULT_VISIBILITY));
        }

        // Check that the generated summary icon color is the same as the child notifications'
        int summaryIconColor = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).iconColor;
        assertThat(summaryIconColor).isEqualTo(iconColor);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryIconColor_diffColor() {
        final String pkg = "package";
        // Create notifications with different icon colors
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), i,
                    DEFAULT_VISIBILITY));
        }

        // Check that the generated summary icon color is the default color
        int summaryIconColor = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).iconColor;
        assertThat(summaryIconColor).isEqualTo(Notification.COLOR_DEFAULT);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryVisibility_hasPublicChildren() {
        final String pkg = "package";
        final int iconColor = Color.BLUE;
        // Create notifications with private and public visibility
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                VISIBILITY_PUBLIC));
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                    VISIBILITY_PRIVATE));
        }

        // Check that the generated summary visibility is public
        int summaryVisibility = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).visibility;
        assertThat(summaryVisibility).isEqualTo(VISIBILITY_PUBLIC);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryVisibility_noPublicChildren() {
        final String pkg = "package";
        final int iconColor = Color.BLUE;
        int visibility = VISIBILITY_PRIVATE;
        // Create notifications with either private or secret visibility
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            if (i % 2 == 0) {
                visibility = VISIBILITY_PRIVATE;
            } else {
                visibility = VISIBILITY_SECRET;
            }
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                    visibility));
        }

        // Check that the generated summary visibility is private
        int summaryVisibility = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).visibility;
        assertThat(summaryVisibility).isEqualTo(VISIBILITY_PRIVATE);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testMonochromeAppIcon_adaptiveIconExists() throws Exception {
        final String pkg = "testPackage";
        final int monochromeIconResId = 1234;
        AdaptiveIconDrawable adaptiveIcon = mock(AdaptiveIconDrawable.class);
        Drawable monochromeIcon = mock(Drawable.class);
        when(mPackageManager.getApplicationIcon(pkg)).thenReturn(adaptiveIcon);
        when(adaptiveIcon.getMonochrome()).thenReturn(monochromeIcon);
        when(adaptiveIcon.getSourceDrawableResId()).thenReturn(monochromeIconResId);
        assertThat(mGroupHelper.getMonochromeAppIcon(pkg).getResId())
                .isEqualTo(monochromeIconResId);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testMonochromeAppIcon_adaptiveIconMissing_fallback() throws Exception {
        final String pkg = "testPackage";
        final int fallbackIconResId = R.drawable.ic_notification_summary_auto;
        when(mPackageManager.getApplicationIcon(pkg)).thenReturn(mock(Drawable.class));
        assertThat(mGroupHelper.getMonochromeAppIcon(pkg).getResId())
                .isEqualTo(fallbackIconResId);
    }
}
