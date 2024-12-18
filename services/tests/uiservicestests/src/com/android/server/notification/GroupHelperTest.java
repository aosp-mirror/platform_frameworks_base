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

import static android.app.Flags.FLAG_SORT_SECTION_BY_TIME;
import static android.app.Notification.COLOR_DEFAULT;
import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_CAN_COLORIZE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.FLAG_GROUP_SUMMARY;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.Notification.GROUP_ALERT_ALL;
import static android.app.Notification.GROUP_ALERT_CHILDREN;
import static android.app.Notification.GROUP_ALERT_SUMMARY;
import static android.app.Notification.VISIBILITY_PRIVATE;
import static android.app.Notification.VISIBILITY_PUBLIC;
import static android.app.Notification.VISIBILITY_SECRET;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION;
import static android.service.notification.Flags.FLAG_NOTIFICATION_FORCE_GROUPING;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;

import static com.android.server.notification.Flags.FLAG_NOTIFICATION_FORCE_GROUP_CONVERSATIONS;
import static com.android.server.notification.GroupHelper.AGGREGATE_GROUP_KEY;
import static com.android.server.notification.GroupHelper.AUTOGROUP_KEY;
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
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
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
import com.android.server.notification.GroupHelper.CachedSummary;
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
    private final int DEFAULT_GROUP_ALERT = GROUP_ALERT_CHILDREN;

    private final String TEST_CHANNEL_ID = "TEST_CHANNEL_ID";

    private @Mock GroupHelper.Callback mCallback;
    private @Mock PackageManager mPackageManager;

    private final static int AUTOGROUP_AT_COUNT = 7;
    private final static int AUTOGROUP_SINGLETONS_AT_COUNT = 2;
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
                AUTOGROUP_SINGLETONS_AT_COUNT, mCallback);

        NotificationRecord r = mock(NotificationRecord.class);
        StatusBarNotification sbn = getSbn("package", 0, "0", UserHandle.SYSTEM);
        when(r.getNotification()).thenReturn(sbn.getNotification());
        when(r.getSbn()).thenReturn(sbn);
        when(mSmallIcon.sameAs(mSmallIcon)).thenReturn(true);
    }

    private StatusBarNotification getSbn(String pkg, int id, String tag,
            UserHandle user, String groupKey, Icon smallIcon, int iconColor) {
        Notification.Builder nb = new Notification.Builder(getContext(), TEST_CHANNEL_ID)
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

    private NotificationRecord getNotificationRecord(String pkg, int id, String tag,
            UserHandle user) {
        return getNotificationRecord(pkg, id, tag, user, null, false);
    }

    private NotificationRecord getNotificationRecord(String pkg, int id, String tag,
            UserHandle user, String groupKey, boolean isSummary) {
        return getNotificationRecord(pkg, id, tag, user, groupKey, isSummary, IMPORTANCE_DEFAULT);
    }

    private NotificationRecord getNotificationRecord(String pkg, int id, String tag,
            UserHandle user, String groupKey, boolean isSummary, int importance) {
        return getNotificationRecord(pkg, id, tag, user, groupKey, isSummary,
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_ID, importance));
    }

    private NotificationRecord getNotificationRecord(String pkg, int id, String tag,
            UserHandle user, String groupKey, boolean isSummary, NotificationChannel channel) {
        StatusBarNotification sbn = getSbn(pkg, id, tag, user, groupKey);
        if (isSummary) {
            sbn.getNotification().flags |= FLAG_GROUP_SUMMARY;
        }
        return new NotificationRecord(getContext(), sbn, channel);
    }

    private NotificationRecord getNotificationRecord(StatusBarNotification sbn) {
        return new NotificationRecord(getContext(), sbn,
            new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_ID, IMPORTANCE_DEFAULT));
    }

    private NotificationAttributes getNotificationAttributes(int flags) {
        return new NotificationAttributes(flags, mSmallIcon, COLOR_DEFAULT, DEFAULT_VISIBILITY,
                DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);
    }

    private String getExpectedAutogroupKey(final NotificationRecord record) {
        if (android.service.notification.Flags.notificationForceGrouping()) {
            return GroupHelper.getFullAggregateGroupKey(record);
        } else {
            return AUTOGROUP_KEY;
        }
    }

    @Test
    public void testGetAutogroupSummaryFlags_noChildren() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();

        assertEquals(BASE_FLAGS, GroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoing() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                GroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoingNoClear() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT | FLAG_NO_CLEAR));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(FLAG_NO_CLEAR | FLAG_ONGOING_EVENT | BASE_FLAGS,
                GroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneOngoingBubble() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT | FLAG_BUBBLE));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                GroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_multipleOngoing() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_ONGOING_EVENT));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));
        children.put("d", getNotificationAttributes(FLAG_ONGOING_EVENT));

        assertEquals(FLAG_ONGOING_EVENT | BASE_FLAGS,
                GroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_oneAutoCancel() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(0));
        children.put("b", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("c", getNotificationAttributes(FLAG_BUBBLE));

        assertEquals(BASE_FLAGS,
                GroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testGetAutogroupSummaryFlags_allAutoCancel() {
        ArrayMap<String, NotificationAttributes> children = new ArrayMap<>();
        children.put("a", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("b", getNotificationAttributes(FLAG_AUTO_CANCEL | FLAG_CAN_COLORIZE));
        children.put("c", getNotificationAttributes(FLAG_AUTO_CANCEL));
        children.put("d", getNotificationAttributes(FLAG_AUTO_CANCEL | FLAG_FOREGROUND_SERVICE));

        assertEquals(FLAG_AUTO_CANCEL | BASE_FLAGS,
                GroupHelper.getAutogroupSummaryFlags(children));
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
                GroupHelper.getAutogroupSummaryFlags(children));
    }

    @Test
    public void testNoGroup_postingUnderLimit() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(
                getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM),
                false);
        }
        verifyZeroInteractions(mCallback);
    }

    @Test
    public void testNoGroup_multiPackage() {
        final String pkg = "package";
        final String pkg2 = "package2";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(
                getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM),
                false);
        }
        mGroupHelper.onNotificationPosted(
            getNotificationRecord(pkg2, AUTOGROUP_AT_COUNT, "four", UserHandle.SYSTEM), false);
        verifyZeroInteractions(mCallback);
    }

    @Test
    public void testNoGroup_multiUser() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(
                getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM),
                false);
        }
        mGroupHelper.onNotificationPosted(
            getNotificationRecord(pkg, AUTOGROUP_AT_COUNT, "four", UserHandle.of(7)), false);
        verifyZeroInteractions(mCallback);
    }

    @Test
    public void testNoGroup_someAreGrouped() {
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationPosted(
                getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM), false);
        }
        mGroupHelper.onNotificationPosted(
            getNotificationRecord(pkg, AUTOGROUP_AT_COUNT, "four", UserHandle.SYSTEM, "a", false),
            false);
        verifyZeroInteractions(mCallback);
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_alwaysAutogroup() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            mGroupHelper.onNotificationPosted(
                getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM), false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(autogroupKey),
                anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), eq(autogroupKey),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            assertThat(mGroupHelper.onNotificationPosted(
                    getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM),
                    false)).isFalse();
        }
        assertThat(mGroupHelper.onNotificationPosted(
            getNotificationRecord(pkg, AUTOGROUP_AT_COUNT - 1, String.valueOf(AUTOGROUP_AT_COUNT - 1),
                        UserHandle.SYSTEM), false)).isTrue();
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_oneChildOngoing_summaryOngoing_alwaysAutogroup() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_oneChildOngoing_summaryOngoing() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_oneChildAutoCancel_summaryNotAutoCancel_alwaysAutogroup() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
            getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_AUTO_CANCEL;
            }
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_oneChildAutoCancel_summaryNotAutoCancel() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_AUTO_CANCEL;
            }
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(),
                eq(autogroupKey), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_allChildrenAutoCancel_summaryAutoCancel_alwaysAutogroup() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), eq(autogroupKey),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_allChildrenAutoCancel_summaryAutoCancel() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(),
                eq(autogroupKey), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_summaryAutoCancelNoClear_alwaysAutogroup() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            if (i == 0) {
                r.getNotification().flags |= FLAG_NO_CLEAR;
            }
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL | FLAG_NO_CLEAR)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), eq(autogroupKey),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddSummary_summaryAutoCancelNoClear() {
        final String pkg = "package";
        final String autogroupKey = getExpectedAutogroupKey(
                getNotificationRecord(pkg, 0, String.valueOf(0), UserHandle.SYSTEM));
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            if (i == 0) {
                r.getNotification().flags |= FLAG_NO_CLEAR;
            }
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(autogroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL | FLAG_NO_CLEAR)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(),
                eq(autogroupKey), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    public void testAutoGrouped_allOngoing_updateChildNotOngoing() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_ONGOING_EVENT;
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // One notification is no longer ongoing
        notifications.get(0).getNotification().flags &= ~FLAG_ONGOING_EVENT;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary should keep FLAG_ONGOING_EVENT if any child has it
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
    }

    @Test
    public void testAutoGrouped_singleOngoing_removeOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // remove ongoing
        mGroupHelper.onNotificationRemoved(notifications.get(0));

        // Summary is no longer ongoing
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
    }

    @Test
    public void testAutoGrouped_noOngoing_updateOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // update to ongoing
        notifications.get(0).getNotification().flags |= FLAG_ONGOING_EVENT;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary is now ongoing
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
    }

    @Test
    public void testAutoGrouped_noOngoing_addOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM);
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // add ongoing
        NotificationRecord r = getNotificationRecord(pkg, AUTOGROUP_AT_COUNT + 1, null,
                UserHandle.SYSTEM);
        r.getNotification().flags |= FLAG_ONGOING_EVENT;
        mGroupHelper.onNotificationPosted(r, true);

        // Summary is now ongoing
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_ONGOING_EVENT)));
    }

    @Test
    public void testAutoGrouped_singleOngoing_appGroupOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // app group the ongoing child
        NotificationRecord r = getNotificationRecord(pkg, 0, "0", UserHandle.SYSTEM,
                "app group now", false);
        mGroupHelper.onNotificationPosted(r, true);

        // Summary is no longer ongoing
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
    }

    @Test
    @DisableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutoGrouped_singleOngoing_removeNonOngoingChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // remove ongoing
        mGroupHelper.onNotificationRemoved(notifications.get(1));

        // Summary is still ongoing
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutoGrouped_singleOngoing_removeNonOngoingChild_forceGrouping() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM);
            if (i == 0) {
                r.getNotification().flags |= FLAG_ONGOING_EVENT;
            }
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // remove ongoing
        mGroupHelper.onNotificationRemoved(notifications.get(1));

        // Summary is still ongoing
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    public void testAutoGrouped_allAutoCancel_updateChildNotAutoCancel() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // One notification is no longer autocancelable
        notifications.get(0).getNotification().flags &= ~FLAG_AUTO_CANCEL;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary should no longer be autocancelable
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
    }

    @Test
    public void testAutoGrouped_almostAllAutoCancel_updateChildAutoCancel() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            if (i != 0) {
                r.getNotification().flags |= FLAG_AUTO_CANCEL;
            }
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // Missing notification is now autocancelable
        notifications.get(0).getNotification().flags |= FLAG_AUTO_CANCEL;
        mGroupHelper.onNotificationPosted(notifications.get(0), true);

        // Summary should now autocancelable
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS | FLAG_AUTO_CANCEL)));
    }

    @Test
    @DisableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutoGrouped_allAutoCancel_updateChildAppGrouped() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // One notification is now grouped by app
        NotificationRecord r = getNotificationRecord(pkg, 0, "0", UserHandle.SYSTEM,
                "app group now", false);
        mGroupHelper.onNotificationPosted(r, true);

        // Summary should be still be autocancelable
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutoGrouped_allAutoCancel_updateChildAppGrouped_forceGrouping() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // One notification is now grouped by app
        NotificationRecord r = getNotificationRecord(pkg, 0, "0", UserHandle.SYSTEM,
            "app group now", false);
        mGroupHelper.onNotificationPosted(r, true);

        // Summary should be still be autocancelable
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
            any());
    }

    @Test
    @DisableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutoGrouped_allAutoCancel_removeChild() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        mGroupHelper.onNotificationRemoved(notifications.get(0));

        // Summary should still be autocancelable
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutoGrouped_allAutoCancel_removeChild_forceGrouping() {
        final String pkg = "package";

        // Post AUTOGROUP_AT_COUNT ongoing notifications
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM);
            r.getNotification().flags |= FLAG_AUTO_CANCEL;
            notifications.add(r);
        }

        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        mGroupHelper.onNotificationRemoved(notifications.get(0));

        // Summary should still be autocancelable
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
            any());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testDropToZeroRemoveGroup_disableFlag() {
        final String pkg = "package";
        ArrayList<NotificationRecord> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            posted.add(r);
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(), anyString(),
                anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationRemoved(posted.remove(0));
        }
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        mGroupHelper.onNotificationRemoved(posted.remove(0));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testDropToZeroRemoveGroup() {
        final String pkg = "package";
        ArrayList<NotificationRecord> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            posted.add(r);
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(), anyString(),
                anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            mGroupHelper.onNotificationRemoved(posted.remove(0));
        }
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        mGroupHelper.onNotificationRemoved(posted.remove(0));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAppStartsGrouping_disableFlag() {
        final String pkg = "package";
        ArrayList<NotificationRecord> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            posted.add(r);
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "app group", false);
            r.getSbn().setOverrideGroupKey("autogrouped");
            mGroupHelper.onNotificationPosted(r, true);
            verify(mCallback, times(1)).removeAutoGroup(r.getKey());
            if (i < AUTOGROUP_AT_COUNT - 1) {
                verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(),
                        anyString());
            }
        }
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAppStartsGrouping() {
        final String pkg = "package";
        ArrayList<NotificationRecord> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM);
            posted.add(r);
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            final NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "app group", false);
            r.getSbn().setOverrideGroupKey("autogrouped");
            mGroupHelper.onNotificationPosted(r, true);
            verify(mCallback, times(1)).removeAutoGroup(r.getKey());
            if (i < AUTOGROUP_AT_COUNT - 1) {
                verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(),
                        anyString());
            }
        }
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testNewNotificationsAddedToAutogroup_ifOriginalNotificationsCanceled_alwaysGroup() {
        final String pkg = "package";
        ArrayList<NotificationRecord> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM);
            posted.add(r);
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        for (int i = posted.size() - 2; i >= 0; i--) {
            mGroupHelper.onNotificationRemoved(posted.remove(i));
        }
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        // Add new notification; it should be autogrouped even though the total count is
        // < AUTOGROUP_AT_COUNT
        final NotificationRecord r = getNotificationRecord(pkg, 5, String.valueOf(5),
                UserHandle.SYSTEM);
        final String autogroupKey = getExpectedAutogroupKey(r);
        posted.add(r);
        assertThat(mGroupHelper.onNotificationPosted(r, true)).isFalse();
        verify(mCallback, times(1)).addAutoGroup(r.getKey(), autogroupKey, true);
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any());
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testNewNotificationsAddedToAutogroup_ifOriginalNotificationsCanceled() {
        final String pkg = "package";
        ArrayList<NotificationRecord> posted = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM);
            posted.add(r);
            mGroupHelper.onNotificationPosted(r, false);
        }

        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        for (int i = posted.size() - 2; i >= 0; i--) {
            mGroupHelper.onNotificationRemoved(posted.remove(i));
        }
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        Mockito.reset(mCallback);

        // Add new notification; it should be autogrouped even though the total count is
        // < AUTOGROUP_AT_COUNT
        final NotificationRecord r = getNotificationRecord(pkg, 5, String.valueOf(5),
                UserHandle.SYSTEM);
        posted.add(r);
        assertThat(mGroupHelper.onNotificationPosted(r, true)).isTrue();
        // addAutoGroup not called on sbn, because the autogrouping is expected to be done
        // synchronously.
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any());
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
                DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(
                    getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null, icon, iconColor));
            mGroupHelper.onNotificationPosted(r, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), anyString(), anyInt(), eq(attr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());

        // After auto-grouping, add new notification with the same color
        NotificationRecord r = getNotificationRecord(
                getSbn(pkg, AUTOGROUP_AT_COUNT, String.valueOf(AUTOGROUP_AT_COUNT),
                    UserHandle.SYSTEM,null, icon, iconColor));
        mGroupHelper.onNotificationPosted(r, true);

        // Check that the summary was updated
        //NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(attr));
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
                DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(
                    getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null, icon, iconColor));
            mGroupHelper.onNotificationPosted(r, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), eq(attr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());

        // After auto-grouping, add new notification with the same color
        NotificationRecord r = getNotificationRecord(getSbn(pkg, AUTOGROUP_AT_COUNT,
            String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, icon, iconColor));
        mGroupHelper.onNotificationPosted(r, true);

        // Check that the summary was updated
        //NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(attr));
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
                initialIcon, initialIconColor, DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT,
                TEST_CHANNEL_ID);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(
                getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    initialIcon, initialIconColor));
            groupHelper.onNotificationPosted(r, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), eq(initialAttr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());

        // After auto-grouping, add new notification with a different color
        final Icon newIcon = mock(Icon.class);
        final int newIconColor = Color.YELLOW;
        NotificationRecord r = getNotificationRecord(getSbn(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, newIcon,
                newIconColor));
        groupHelper.onNotificationPosted(r, true);

        // Summary should be updated to the default color and the icon to the monochrome icon
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, monochromeIcon,
                COLOR_DEFAULT, DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(newAttr));
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
                initialIcon, initialIconColor, DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT,
                TEST_CHANNEL_ID);

        // Add notifications with same icon and color
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(
                getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    initialIcon, initialIconColor));
            groupHelper.onNotificationPosted(r, false);
        }
        // Check that the summary would have the same icon and color
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), eq(initialAttr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());

        // After auto-grouping, add new notification with a different color
        final Icon newIcon = mock(Icon.class);
        final int newIconColor = Color.YELLOW;
        NotificationRecord r = getNotificationRecord(getSbn(pkg, AUTOGROUP_AT_COUNT,
            String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, newIcon,
            newIconColor));
        groupHelper.onNotificationPosted(r, true);

        // Summary should be updated to the default color and the icon to the monochrome icon
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, monochromeIcon,
                COLOR_DEFAULT, DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(newAttr));
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
                VISIBILITY_PRIVATE, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);

        // Add notifications with same icon and color and default visibility (private)
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(
                getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    icon, iconColor));
            mGroupHelper.onNotificationPosted(r, false);
        }
        // Check that the summary has private visibility
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), anyString(), anyInt(), eq(attr));

        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());

        // After auto-grouping, add new notification with public visibility
        NotificationRecord r = getNotificationRecord(getSbn(pkg, AUTOGROUP_AT_COUNT,
            String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, icon, iconColor));
        r.getNotification().visibility = VISIBILITY_PUBLIC;
        mGroupHelper.onNotificationPosted(r, true);

        // Check that the summary visibility was updated
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                VISIBILITY_PUBLIC, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(newAttr));
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
                VISIBILITY_PRIVATE, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);

        // Add notifications with same icon and color and default visibility (private)
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(
                getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    icon, iconColor));
            assertThat(mGroupHelper.onNotificationPosted(r, false)).isFalse();
        }
        // The last notification added will reach the autogroup threshold.
        NotificationRecord r = getNotificationRecord(getSbn(pkg, AUTOGROUP_AT_COUNT - 1,
            String.valueOf(AUTOGROUP_AT_COUNT - 1), UserHandle.SYSTEM, null, icon, iconColor));
        assertThat(mGroupHelper.onNotificationPosted(r, false)).isTrue();

        // Check that the summary has private visibility
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(), anyString(),
                anyInt(), eq(attr));
        // The last sbn is expected to be added to autogroup synchronously.
        verify(mCallback, times(AUTOGROUP_AT_COUNT - 1)).addAutoGroup(anyString(), anyString(),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());

        // After auto-grouping, add new notification with public visibility
        r = getNotificationRecord(getSbn(pkg, AUTOGROUP_AT_COUNT,
            String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, null, icon, iconColor));
        r.getNotification().visibility = VISIBILITY_PUBLIC;
        assertThat(mGroupHelper.onNotificationPosted(r, true)).isTrue();

        // Check that the summary visibility was updated
        NotificationAttributes newAttr = new NotificationAttributes(BASE_FLAGS, icon, iconColor,
                VISIBILITY_PUBLIC, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(newAttr));
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    @DisableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutoGrouped_diffIcon_diffColor_removeChild_updateTo_sameIcon_sameColor() {
        final String pkg = "package";
        final Icon initialIcon = mock(Icon.class);
        when(initialIcon.sameAs(initialIcon)).thenReturn(true);
        final int initialIconColor = Color.BLUE;
        final NotificationAttributes initialAttr = new NotificationAttributes(
                GroupHelper.FLAG_INVALID, initialIcon, initialIconColor, DEFAULT_VISIBILITY,
                DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);

        // Add AUTOGROUP_AT_COUNT-1 notifications with same icon and color
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(
                getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    initialIcon, initialIconColor));
            notifications.add(r);
        }
        // And an additional notification with different icon and color
        final int lastIdx = AUTOGROUP_AT_COUNT - 1;
        NotificationRecord newRec = getNotificationRecord(getSbn(pkg, lastIdx,
                String.valueOf(lastIdx), UserHandle.SYSTEM, null, mock(Icon.class),
                Color.YELLOW));
        notifications.add(newRec);
        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // Remove last notification (the only one with different icon and color)
        mGroupHelper.onNotificationRemoved(notifications.get(lastIdx));

        // Summary should be updated to the common icon and color
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                eq(initialAttr));
    }

    @Test
    @EnableFlags({Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE,
            FLAG_NOTIFICATION_FORCE_GROUPING})
    public void testAutoGrouped_diffIcon_diffColor_removeChild_updateTo_sameIcon_sameColor_forceGrouping() {
        final String pkg = "package";
        final Icon initialIcon = mock(Icon.class);
        when(initialIcon.sameAs(initialIcon)).thenReturn(true);
        final int initialIconColor = Color.BLUE;
        final NotificationAttributes initialAttr = new NotificationAttributes(
            BASE_FLAGS, initialIcon, initialIconColor, DEFAULT_VISIBILITY,
            DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID);

        // Add AUTOGROUP_AT_COUNT-1 notifications with same icon and color
        ArrayList<NotificationRecord> notifications = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(
                getSbn(pkg, i, String.valueOf(i), UserHandle.SYSTEM, null,
                    initialIcon, initialIconColor));
            notifications.add(r);
        }
        // And an additional notification with different icon and color
        final int lastIdx = AUTOGROUP_AT_COUNT - 1;
        NotificationRecord newRec = getNotificationRecord(getSbn(pkg, lastIdx,
            String.valueOf(lastIdx), UserHandle.SYSTEM, null, mock(Icon.class),
            Color.YELLOW));
        notifications.add(newRec);
        for (NotificationRecord r: notifications) {
            mGroupHelper.onNotificationPosted(r, false);
        }

        // Remove last notification (the only one with different icon and color)
        mGroupHelper.onNotificationRemoved(notifications.get(lastIdx));

        // Summary should be updated to the common icon and color
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), anyString(), anyString(),
            eq(initialAttr));
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
                    DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID));
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
                    DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID));
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
                    DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID));
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
                    DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID));
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
                VISIBILITY_PUBLIC, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID));
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                    VISIBILITY_PRIVATE, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID));
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
                    visibility, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID));
        }

        // Check that the generated summary visibility is private
        int summaryVisibility = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).visibility;
        assertThat(summaryVisibility).isEqualTo(VISIBILITY_PRIVATE);
    }

    @Test
    public void testAutobundledSummaryAlertBehavior_oneChildAlertChildren() {
        final String pkg = "package";
        final int iconColor = Color.BLUE;
        // Create notifications with GROUP_ALERT_SUMMARY + one with GROUP_ALERT_CHILDREN
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                VISIBILITY_PUBLIC, GROUP_ALERT_CHILDREN, TEST_CHANNEL_ID));
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                VISIBILITY_PRIVATE, GROUP_ALERT_SUMMARY, TEST_CHANNEL_ID));
        }
        // Check that the generated summary alert behavior is GROUP_ALERT_CHILDREN
        int groupAlertBehavior = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).groupAlertBehavior;
        assertThat(groupAlertBehavior).isEqualTo(GROUP_ALERT_CHILDREN);
    }

    @Test
    public void testAutobundledSummaryAlertBehavior_oneChildAlertAll() {
        final String pkg = "package";
        final int iconColor = Color.BLUE;
        // Create notifications with GROUP_ALERT_SUMMARY + one with GROUP_ALERT_ALL
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                VISIBILITY_PUBLIC, GROUP_ALERT_ALL, TEST_CHANNEL_ID));
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                VISIBILITY_PRIVATE, GROUP_ALERT_SUMMARY, TEST_CHANNEL_ID));
        }
        // Check that the generated summary alert behavior is GROUP_ALERT_CHILDREN
        int groupAlertBehavior = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).groupAlertBehavior;
        assertThat(groupAlertBehavior).isEqualTo(GROUP_ALERT_CHILDREN);
    }

    @Test
    public void testAutobundledSummaryAlertBehavior_allChildAlertSummary() {
        final String pkg = "package";
        final int iconColor = Color.BLUE;
        // Create notifications with GROUP_ALERT_SUMMARY
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                VISIBILITY_PRIVATE, GROUP_ALERT_SUMMARY, TEST_CHANNEL_ID));
        }

        // Check that the generated summary alert behavior is GROUP_ALERT_SUMMARY
        int groupAlertBehavior = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).groupAlertBehavior;
        assertThat(groupAlertBehavior).isEqualTo(GROUP_ALERT_SUMMARY);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryChannelId() {
        final String pkg = "package";
        final int iconColor = Color.BLUE;
        final String expectedChannelId = TEST_CHANNEL_ID + "0";
        // Create notifications with different channelIds
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            childrenAttr.add(new NotificationAttributes(0, mock(Icon.class), iconColor,
                    DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT, TEST_CHANNEL_ID+i));
        }

        // Check that the generated summary channelId is the first child in the list
        String summaryChannelId = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).channelId;
        assertThat(summaryChannelId).isEqualTo(expectedChannelId);
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTOGROUP_SUMMARY_ICON_UPDATE)
    public void testAutobundledSummaryChannelId_noChildren() {
        final String pkg = "package";
        // No child notifications
        List<NotificationAttributes> childrenAttr = new ArrayList<>();
        // Check that the generated summary channelId is null
        String summaryChannelId = mGroupHelper.getAutobundledSummaryAttributes(pkg,
                childrenAttr).channelId;
        assertThat(summaryChannelId).isNull();
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

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testGetAggregateGroupKey() {
        final String fullAggregateGroupKey = GroupHelper.getFullAggregateGroupKey("pkg",
                "groupKey", 1234);
        assertThat(fullAggregateGroupKey).isEqualTo("1234|pkg|g:groupKey");
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_postingUnderLimit_forcedGrouping() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp " + i, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_AutobundledAlready_forcedGrouping() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, null, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_isCanceled_forcedGrouping() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp" + i, true);
            r.isCanceled = true;
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_isAggregated_forcedGrouping() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            String aggregateGroupKey = AGGREGATE_GROUP_KEY + "AlertingSection";
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, aggregateGroupKey, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_multiPackage_forcedGrouping() {
        final String pkg = "package";
        final String pkg2 = "package2";
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp " + i, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        NotificationRecord r = getNotificationRecord(pkg2, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, "testGrp", true);
        notificationList.add(r);
        mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_multiUser_forcedGrouping() {
        final String pkg = "package";
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp " + i, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        NotificationRecord r = getNotificationRecord(pkg, AUTOGROUP_AT_COUNT,
                String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.of(7), "testGrp", true);
        notificationList.add(r);
        mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_summaryWithChildren_forcedGrouping() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM, "testGrp " + i, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        // Next posted summary has 1 child => no forced grouping
        NotificationRecord summary = getNotificationRecord(pkg, AUTOGROUP_AT_COUNT,
            String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, "testGrp", true);
        notificationList.add(summary);
        NotificationRecord child = getNotificationRecord(pkg, AUTOGROUP_AT_COUNT + 1,
            String.valueOf(AUTOGROUP_AT_COUNT + 1), UserHandle.SYSTEM, "testGrp", false);
        notificationList.add(child);
        mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testNoGroup_groupWithSummary_forcedGrouping() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        for (int i = 0; i < AUTOGROUP_AT_COUNT - 1; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM, "testGrp " + i, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        // Next posted notification has summary => no forced grouping
        NotificationRecord summary = getNotificationRecord(pkg, AUTOGROUP_AT_COUNT,
            String.valueOf(AUTOGROUP_AT_COUNT), UserHandle.SYSTEM, "testGrp", true);
        notificationList.add(summary);
        NotificationRecord child = getNotificationRecord(pkg, AUTOGROUP_AT_COUNT + 1,
            String.valueOf(AUTOGROUP_AT_COUNT + 1), UserHandle.SYSTEM, "testGrp", false);
        notificationList.add(child);
        summaryByGroup.put(summary.getGroupKey(), summary);
        mGroupHelper.onNotificationPostedWithDelay(child, notificationList, summaryByGroup);
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAddAggregateSummary_summaryNoChildren() {
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
                AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post group summaries without children => force autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM, "testGrp " + i, true);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
            eq(expectedGroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
            eq(expectedGroupKey), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
            any());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAddAggregateSummary_childrenNoSummary() {
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
                AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post group notifications without summaries => force autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM, "testGrp " + i, false);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAddAggregateSummary_multipleSections() {
        final String pkg = "package";
        final String expectedGroupKey_alerting = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final String expectedGroupKey_silent = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "SilentSection", UserHandle.SYSTEM.getIdentifier());

        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post notifications with different importance values => force group into separate sections
        NotificationRecord r;
        for (int i = 0; i < 2 * AUTOGROUP_AT_COUNT; i++) {
            if (i % 2 == 0) {
                r = getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM,
                    "testGrp " + i, true, IMPORTANCE_DEFAULT);
            } else {
                r = getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM,
                    "testGrp " + i, false, IMPORTANCE_LOW);
            }
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
            eq(expectedGroupKey_alerting), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
            eq(expectedGroupKey_silent), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));

        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey_alerting), eq(true));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
            eq(expectedGroupKey_silent), eq(true));

        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }


    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testAddAggregateSummary_mixUngroupedAndAbusive_alwaysAutogroup() {
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        // Post ungrouped notifications => create autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            mGroupHelper.onNotificationPosted(
                getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM), false);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(
                anyInt(), eq(pkg), anyString(), eq(expectedGroupKey),
                anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(), eq(expectedGroupKey),
                anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());

        reset(mCallback);

        // Post group notifications without summaries => add to autogroup
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final int id = AUTOGROUP_AT_COUNT;
        NotificationRecord r = getNotificationRecord(pkg, id, String.valueOf(id),
                UserHandle.SYSTEM, "testGrp " + id, false);
        notificationList.add(r);
        mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);

        // Check that the new notification was added
        verify(mCallback, times(1)).addAutoGroup(eq(r.getKey()),
                eq(expectedGroupKey), eq(true));
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), eq(pkg),
                eq(expectedGroupKey), any());
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    @DisableFlags(android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST)
    public void testUpdateAggregateSummary_postUngroupedAfterForcedGrouping_alwaysAutogroup() {
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post group notifications without summaries => force autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM, "testGrp " + i, false);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
            eq(expectedGroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
            eq(expectedGroupKey), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
            any());

        reset(mCallback);

        // Post ungrouped notification => update autogroup
        final int id = AUTOGROUP_AT_COUNT;
        NotificationRecord r = getNotificationRecord(pkg, id, String.valueOf(id),
                UserHandle.SYSTEM);
        mGroupHelper.onNotificationPosted(r, true);

        verify(mCallback, times(1)).addAutoGroup(eq(r.getKey()),
                eq(expectedGroupKey), eq(true));
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), eq(pkg),
                eq(expectedGroupKey), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING,
            android.app.Flags.FLAG_CHECK_AUTOGROUP_BEFORE_POST})
    public void testUpdateAggregateSummary_postUngroupedAfterForcedGrouping() {
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post group notifications without summaries => force autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM, "testGrp " + i, false);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
            eq(expectedGroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
            eq(expectedGroupKey), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
            any());

        reset(mCallback);

        // Post ungrouped notification => update autogroup
        final int id = AUTOGROUP_AT_COUNT;
        NotificationRecord r = getNotificationRecord(pkg, id, String.valueOf(id),
            UserHandle.SYSTEM);
        mGroupHelper.onNotificationPosted(r, true);

        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), eq(pkg),
            eq(expectedGroupKey), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, never()).addAutoGroup(anyString(), anyString(), anyBoolean());
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(),
            anyString(), anyInt(), any());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testUpdateAggregateSummary_postAfterForcedGrouping() {
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post group notifications w/o summaries and summaries w/o children => force autogrouping
        NotificationRecord r;
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            if (i % 2 == 0) {
                r = getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM,
                    "testGrp " + i, true);
            } else {
                r = getNotificationRecord(pkg, i, String.valueOf(i), UserHandle.SYSTEM,
                    "testGrp " + i, false);
            }
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }

        // Post another notification after forced grouping
        final Icon icon = mock(Icon.class);
        when(icon.sameAs(icon)).thenReturn(true);
        final int iconColor = Color.BLUE;
        r = getNotificationRecord(
                getSbn(pkg, AUTOGROUP_AT_COUNT, String.valueOf(AUTOGROUP_AT_COUNT),
                    UserHandle.SYSTEM, "testGrp " + AUTOGROUP_AT_COUNT, icon, iconColor));

        notificationList.add(r);
        mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);

        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
            eq(expectedGroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT + 1)).addAutoGroup(anyString(),
            eq(expectedGroupKey), eq(true));
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), eq(pkg),
            eq(expectedGroupKey), any());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testRemoveAggregateSummary_removeAllNotifications() {
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post group notifications without summaries => force autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                UserHandle.SYSTEM, "testGrp " + i, false);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
        Mockito.reset(mCallback);

        // Remove all posted notifications
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp " + i, false);
            r.setOverrideGroupKey(expectedGroupKey);
            mGroupHelper.onNotificationRemoved(r, notificationList);
        }
        // Check that the autogroup summary is removed
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), eq(pkg),
                eq(expectedGroupKey));
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testMoveAggregateGroups_updateChannel() {
        final String pkg = "package";
        final String expectedGroupKey_alerting = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final NotificationChannel channel = new NotificationChannel(TEST_CHANNEL_ID,
                TEST_CHANNEL_ID, IMPORTANCE_DEFAULT);
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post group notifications without summaries => force autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp " + i, false, channel);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey_alerting), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey_alerting), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
            any());
        Mockito.reset(mCallback);

        // Update the channel importance for all posted notifications
        final String expectedGroupKey_silent = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "SilentSection", UserHandle.SYSTEM.getIdentifier());
        channel.setImportance(IMPORTANCE_LOW);
        for (NotificationRecord r: notificationList) {
            r.updateNotificationChannel(channel);
        }
        mGroupHelper.onChannelUpdated(UserHandle.SYSTEM.getIdentifier(), pkg, channel,
                notificationList);

        // Check that all notifications are moved to the silent section group
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey_silent), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey_silent), eq(true));

        // Check that the alerting section group is removed
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), eq(pkg),
                eq(expectedGroupKey_alerting));
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testMoveAggregateGroups_updateChannel_multipleChannels() {
        final String pkg = "package";
        final String expectedGroupKey_alerting = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final NotificationChannel channel1 = new NotificationChannel("TEST_CHANNEL_ID1",
            "TEST_CHANNEL_ID1", IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 = new NotificationChannel("TEST_CHANNEL_ID2",
            "TEST_CHANNEL_ID2", IMPORTANCE_DEFAULT);
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        // Post notifications with different channels that autogroup within the same section
        NotificationRecord r;
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            if (i % 2 == 0) {
                r = getNotificationRecord(pkg, i, String.valueOf(i),
                        UserHandle.SYSTEM, "testGrp " + i, false, channel1);
            } else {
                r = getNotificationRecord(pkg, i, String.valueOf(i),
                        UserHandle.SYSTEM, "testGrp " + i, false, channel2);
            }
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        NotificationAttributes expectedSummaryAttr = new NotificationAttributes(BASE_FLAGS,
                mSmallIcon, COLOR_DEFAULT, DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT,
                "TEST_CHANNEL_ID1");
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey_alerting), anyInt(), eq(expectedSummaryAttr));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey_alerting), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
        Mockito.reset(mCallback);

        // Update channel1's importance
        final String expectedGroupKey_silent = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "SilentSection", UserHandle.SYSTEM.getIdentifier());
        channel1.setImportance(IMPORTANCE_LOW);
        for (NotificationRecord record: notificationList) {
            if (record.getChannel().getId().equals(channel1.getId())) {
                record.updateNotificationChannel(channel1);
            }
        }
        mGroupHelper.onChannelUpdated(UserHandle.SYSTEM.getIdentifier(), pkg, channel1,
                notificationList);

        // Check that channel1's notifications are moved to the silent section group
        // But not enough to auto-group => remove override group key
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                anyString(), anyInt(), any());
        verify(mCallback, never()).addAutoGroup(anyString(), anyString(), anyBoolean());
        for (NotificationRecord record: notificationList) {
            if (record.getChannel().getId().equals(channel1.getId())) {
                assertThat(record.getSbn().getOverrideGroupKey()).isNull();
            }
        }

        // Check that the alerting section group is not removed, only updated
        expectedSummaryAttr = new NotificationAttributes(BASE_FLAGS,
            mSmallIcon, COLOR_DEFAULT, DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT,
            "TEST_CHANNEL_ID2");
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), eq(pkg),
                eq(expectedGroupKey_alerting));
        verify(mCallback, times(1)).updateAutogroupSummary(anyInt(), eq(pkg),
                eq(expectedGroupKey_alerting), eq(expectedSummaryAttr));
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testMoveAggregateGroups_updateChannel_groupsUngrouped() {
        final String pkg = "package";
        final String expectedGroupKey_silent = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "SilentSection", UserHandle.SYSTEM.getIdentifier());
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();

        // Post too few group notifications without summaries => do not autogroup
        final NotificationChannel lowPrioChannel = new NotificationChannel("TEST_CHANNEL_LOW_ID",
                "TEST_CHANNEL_LOW_ID", IMPORTANCE_LOW);
        final int numUngrouped = AUTOGROUP_AT_COUNT - 1;
        int startIdx = 42;
        for (int i = startIdx; i < startIdx + numUngrouped; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp " + i, false, lowPrioChannel);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, never()).addAutoGroup(anyString(), anyString(), anyBoolean());
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any());

        reset(mCallback);

        final String expectedGroupKey_alerting = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final NotificationChannel channel = new NotificationChannel(TEST_CHANNEL_ID,
                TEST_CHANNEL_ID, IMPORTANCE_DEFAULT);

        // Post group notifications without summaries => force autogroup
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            NotificationRecord r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, "testGrp " + i, false, channel);
            notificationList.add(r);
            mGroupHelper.onNotificationPostedWithDelay(r, notificationList, summaryByGroup);
        }
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey_alerting), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey_alerting), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
        Mockito.reset(mCallback);

        // Update the channel importance for all posted notifications
        final int numSilentGroupNotifications = AUTOGROUP_AT_COUNT + numUngrouped;
        channel.setImportance(IMPORTANCE_LOW);
        for (NotificationRecord r: notificationList) {
            r.updateNotificationChannel(channel);
        }
        mGroupHelper.onChannelUpdated(UserHandle.SYSTEM.getIdentifier(), pkg, channel,
                notificationList);

        // Check that all notifications are moved to the silent section group
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey_silent), anyInt(), eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(numSilentGroupNotifications)).addAutoGroup(anyString(),
                eq(expectedGroupKey_silent), eq(true));

        // Check that the alerting section group is removed
        verify(mCallback, times(1)).removeAutoGroupSummary(anyInt(), eq(pkg),
                eq(expectedGroupKey_alerting));
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    public void testAutogroup_updateChannel_reachedMinAutogroupCount() {
        final String pkg = "package";
        final NotificationChannel channel1 = new NotificationChannel("TEST_CHANNEL_ID1",
                "TEST_CHANNEL_ID1", IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 = new NotificationChannel("TEST_CHANNEL_ID2",
                "TEST_CHANNEL_ID2", IMPORTANCE_LOW);
        final List<NotificationRecord> notificationList = new ArrayList<>();
        // Post notifications with different channels that would autogroup in different sections
        NotificationRecord r;
        // Not enough notifications to autogroup initially
        for (int i = 0; i < AUTOGROUP_AT_COUNT; i++) {
            if (i % 2 == 0) {
                r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, null, false, channel1);
            } else {
                r = getNotificationRecord(pkg, i, String.valueOf(i),
                    UserHandle.SYSTEM, null, false, channel2);
            }
            notificationList.add(r);
            mGroupHelper.onNotificationPosted(r, false);
        }
        verify(mCallback, never()).addAutoGroupSummary(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any());
        verify(mCallback, never()).addAutoGroup(anyString(), anyString(), anyBoolean());
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());
        Mockito.reset(mCallback);

        // Update channel1's importance
        final String expectedGroupKey_silent = GroupHelper.getFullAggregateGroupKey(pkg,
                AGGREGATE_GROUP_KEY + "SilentSection", UserHandle.SYSTEM.getIdentifier());
        channel1.setImportance(IMPORTANCE_LOW);
        for (NotificationRecord record: notificationList) {
            if (record.getChannel().getId().equals(channel1.getId())) {
                record.updateNotificationChannel(channel1);
            }
        }
        mGroupHelper.onChannelUpdated(UserHandle.SYSTEM.getIdentifier(), pkg, channel1,
                notificationList);

        // Check that channel1's notifications are moved to the silent section & autogroup all
        NotificationAttributes expectedSummaryAttr = new NotificationAttributes(BASE_FLAGS,
                mSmallIcon, COLOR_DEFAULT, DEFAULT_VISIBILITY, DEFAULT_GROUP_ALERT,
                "TEST_CHANNEL_ID1");
        verify(mCallback, times(AUTOGROUP_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey_silent), eq(true));
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg), anyString(),
                eq(expectedGroupKey_silent), anyInt(), eq(expectedSummaryAttr));
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING,
            Flags.FLAG_NOTIFICATION_FORCE_GROUP_SINGLETONS})
    public void testNoGroup_singletonGroup_underLimit() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        // Post singleton groups, under forced group limit
        for (int i = 0; i < AUTOGROUP_SINGLETONS_AT_COUNT - 1; i++) {
            NotificationRecord summary = getNotificationRecord(pkg, i,
                    String.valueOf(i), UserHandle.SYSTEM, "testGrp "+i, true);
            notificationList.add(summary);
            NotificationRecord child = getNotificationRecord(pkg, i + 42,
                    String.valueOf(i + 42), UserHandle.SYSTEM, "testGrp "+i, false);
            notificationList.add(child);
            summaryByGroup.put(summary.getGroupKey(), summary);
            mGroupHelper.onNotificationPostedWithDelay(child, notificationList, summaryByGroup);
            mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);
        }
        verifyZeroInteractions(mCallback);
    }


    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    @DisableFlags(Flags.FLAG_NOTIFICATION_FORCE_GROUP_SINGLETONS)
    public void testAddAggregateSummary_singletonGroup_disableFlag() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        // Post singleton groups, above forced group limit
        for (int i = 0; i < AUTOGROUP_SINGLETONS_AT_COUNT; i++) {
            NotificationRecord summary = getNotificationRecord(pkg, i,
                    String.valueOf(i), UserHandle.SYSTEM, "testGrp "+i, true);
            notificationList.add(summary);
            NotificationRecord child = getNotificationRecord(pkg, i + 42,
                    String.valueOf(i + 42), UserHandle.SYSTEM, "testGrp "+i, false);
            notificationList.add(child);
            summaryByGroup.put(summary.getGroupKey(), summary);
            mGroupHelper.onNotificationPostedWithDelay(child, notificationList, summaryByGroup);
            mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);
        }
        // FLAG_NOTIFICATION_FORCE_GROUP_SINGLETONS is disabled => don't force group
        verifyZeroInteractions(mCallback);
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING,
            Flags.FLAG_NOTIFICATION_FORCE_GROUP_SINGLETONS})
    public void testAddAggregateSummary_singletonGroups() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        String expectedTriggeringKey = null;
        // Post singleton groups, above forced group limit
        for (int i = 0; i < AUTOGROUP_SINGLETONS_AT_COUNT; i++) {
            NotificationRecord summary = getNotificationRecord(pkg, i,
                String.valueOf(i), UserHandle.SYSTEM, "testGrp "+i, true);
            notificationList.add(summary);
            NotificationRecord child = getNotificationRecord(pkg, i + 42,
                String.valueOf(i + 42), UserHandle.SYSTEM, "testGrp "+i, false);
            notificationList.add(child);
            expectedTriggeringKey = child.getKey();
            summaryByGroup.put(summary.getGroupKey(), summary);
            mGroupHelper.onNotificationPostedWithDelay(child, notificationList, summaryByGroup);
            summary.isCanceled = true;  // simulate removing the app summary
            mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);
        }
        // Check that notifications are forced grouped
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg),
                eq(expectedTriggeringKey), eq(expectedGroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_SINGLETONS_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());

        // Check that summaries are canceled
        verify(mCallback, times(AUTOGROUP_SINGLETONS_AT_COUNT)).removeAppProvidedSummary(
                anyString());
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING,
            Flags.FLAG_NOTIFICATION_FORCE_GROUP_SINGLETONS})
    public void testAddAggregateSummary_summaryTriggers_singletonGroups() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
                AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final int firstChildIdx = 1;
        // Post singleton groups, below forced group limit
        for (int i = 0; i < AUTOGROUP_SINGLETONS_AT_COUNT - 1; i++) {
            NotificationRecord summary = getNotificationRecord(pkg, i,
                    String.valueOf(i), UserHandle.SYSTEM, "testGrp " + i, true);
            notificationList.add(summary);
            NotificationRecord child = getNotificationRecord(pkg, i + 42,
                    String.valueOf(i + 42), UserHandle.SYSTEM, "testGrp " + i, false);
            notificationList.add(child);
            summaryByGroup.put(summary.getGroupKey(), summary);
            mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);
            mGroupHelper.onNotificationPostedWithDelay(child, notificationList, summaryByGroup);
        }

        // Post triggering group summary
        final String expectedTriggeringKey = notificationList.get(firstChildIdx).getKey();
        final int triggerIdx = AUTOGROUP_SINGLETONS_AT_COUNT - 1;
        NotificationRecord summary = getNotificationRecord(pkg, triggerIdx,
                String.valueOf(triggerIdx), UserHandle.SYSTEM, "testGrp " + triggerIdx, true);
        notificationList.add(summary);
        NotificationRecord child = getNotificationRecord(pkg, triggerIdx + 42,
                String.valueOf(triggerIdx + 42), UserHandle.SYSTEM, "testGrp " + triggerIdx, false);
        notificationList.add(child);
        summaryByGroup.put(summary.getGroupKey(), summary);
        mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);

        // Check that notifications are forced grouped
        verify(mCallback, times(1)).addAutoGroupSummary(anyInt(), eq(pkg),
                eq(expectedTriggeringKey), eq(expectedGroupKey), anyInt(),
                eq(getNotificationAttributes(BASE_FLAGS)));
        verify(mCallback, times(AUTOGROUP_SINGLETONS_AT_COUNT)).addAutoGroup(anyString(),
                eq(expectedGroupKey), eq(true));
        verify(mCallback, never()).removeAutoGroup(anyString());
        verify(mCallback, never()).removeAutoGroupSummary(anyInt(), anyString(), anyString());
        verify(mCallback, never()).updateAutogroupSummary(anyInt(), anyString(), anyString(),
                any());

        // Check that summaries are canceled
        verify(mCallback, times(AUTOGROUP_SINGLETONS_AT_COUNT)).removeAppProvidedSummary(anyString());
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING,
            Flags.FLAG_NOTIFICATION_FORCE_GROUP_SINGLETONS})
    public void testCancelCachedSummary_singletonGroups() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        final int id = 0;
        // Post singleton groups, above forced group limit
        for (int i = 0; i < AUTOGROUP_SINGLETONS_AT_COUNT; i++) {
            NotificationRecord summary = getNotificationRecord(pkg, i,
                    String.valueOf(i), UserHandle.SYSTEM, "testGrp "+i, true);
            notificationList.add(summary);
            NotificationRecord child = getNotificationRecord(pkg, i + 42,
                    String.valueOf(i + 42), UserHandle.SYSTEM, "testGrp "+i, false);
            notificationList.add(child);
            summaryByGroup.put(summary.getGroupKey(), summary);
            mGroupHelper.onNotificationPostedWithDelay(child, notificationList, summaryByGroup);
            summary.isCanceled = true;  // simulate removing the app summary
            mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);
        }
        Mockito.reset(mCallback);

        // App cancels the summary of an aggregated group
        mGroupHelper.maybeCancelGroupChildrenForCanceledSummary(pkg, String.valueOf(id), id,
                UserHandle.SYSTEM.getIdentifier(), REASON_APP_CANCEL);

        verify(mCallback, times(1)).removeNotificationFromCanceledGroup(
                eq(UserHandle.SYSTEM.getIdentifier()), eq(pkg), eq("testGrp " + id),
                eq(REASON_APP_CANCEL));
        CachedSummary cachedSummary = mGroupHelper.findCanceledSummary(pkg, String.valueOf(id), id,
                UserHandle.SYSTEM.getIdentifier());
        assertThat(cachedSummary).isNull();
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING,
            Flags.FLAG_NOTIFICATION_FORCE_GROUP_SINGLETONS})
    public void testRemoveCachedSummary_singletonGroups_removeChildren() {
        final List<NotificationRecord> notificationList = new ArrayList<>();
        final ArrayMap<String, NotificationRecord> summaryByGroup = new ArrayMap<>();
        final String pkg = "package";
        final String expectedGroupKey = GroupHelper.getFullAggregateGroupKey(pkg,
            AGGREGATE_GROUP_KEY + "AlertingSection", UserHandle.SYSTEM.getIdentifier());
        final int id = 0;
        NotificationRecord childToRemove = null;
        // Post singleton groups, above forced group limit
        for (int i = 0; i < AUTOGROUP_SINGLETONS_AT_COUNT; i++) {
            NotificationRecord summary = getNotificationRecord(pkg, i,
                    String.valueOf(i), UserHandle.SYSTEM, "testGrp "+i, true);
            notificationList.add(summary);
            NotificationRecord child = getNotificationRecord(pkg, i + 42, String.valueOf(i + 42),
                    UserHandle.SYSTEM, "testGrp " + i, false);
            if (i == id) {
                childToRemove = child;
            }
            notificationList.add(child);
            summaryByGroup.put(summary.getGroupKey(), summary);
            mGroupHelper.onNotificationPostedWithDelay(child, notificationList, summaryByGroup);
            summary.isCanceled = true;  // simulate removing the app summary
            mGroupHelper.onNotificationPostedWithDelay(summary, notificationList, summaryByGroup);
        }
        // override group key for child notifications
        List<NotificationRecord> notificationListAfterGrouping = new ArrayList<>(
            notificationList.stream().filter(r -> {
                if (r.getSbn().getNotification().isGroupChild()) {
                    r.setOverrideGroupKey(expectedGroupKey);
                    return true;
                } else {
                    return false;
                }
            }).toList());
        summaryByGroup.clear();
        Mockito.reset(mCallback);

        //Cancel child 0 => remove cached summary
        childToRemove.isCanceled = true;
        notificationListAfterGrouping.remove(childToRemove);
        mGroupHelper.onNotificationRemoved(childToRemove, notificationListAfterGrouping);
        CachedSummary cachedSummary = mGroupHelper.findCanceledSummary(pkg, String.valueOf(id), id,
                UserHandle.SYSTEM.getIdentifier());
        assertThat(cachedSummary).isNull();
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    @DisableFlags(FLAG_NOTIFICATION_FORCE_GROUP_CONVERSATIONS)
    public void testNonGroupableNotifications() {
        // Check that there is no valid section for: conversations, calls, foreground services
        NotificationRecord notification_conversation = mock(NotificationRecord.class);
        when(notification_conversation.isConversation()).thenReturn(true);
        assertThat(GroupHelper.getSection(notification_conversation)).isNull();

        NotificationRecord notification_call = spy(getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_LOW));
        Notification n = mock(Notification.class);
        StatusBarNotification sbn = spy(getSbn("package", 0, "0", UserHandle.SYSTEM));
        when(notification_call.isConversation()).thenReturn(false);
        when(notification_call.getNotification()).thenReturn(n);
        when(notification_call.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(n);
        when(n.isStyle(Notification.CallStyle.class)).thenReturn(true);
        assertThat(GroupHelper.getSection(notification_call)).isNull();

        NotificationRecord notification_colorFg = spy(getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_LOW));
        sbn = spy(getSbn("package", 0, "0", UserHandle.SYSTEM));
        n = mock(Notification.class);
        when(notification_colorFg.isConversation()).thenReturn(false);
        when(notification_colorFg.getNotification()).thenReturn(n);
        when(notification_colorFg.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(n);
        when(n.isForegroundService()).thenReturn(true);
        when(n.isColorized()).thenReturn(true);
        when(n.isStyle(Notification.CallStyle.class)).thenReturn(false);
        assertThat(GroupHelper.getSection(notification_colorFg)).isNull();
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_FORCE_GROUPING)
    @DisableFlags(FLAG_NOTIFICATION_CLASSIFICATION)
    public void testGroupSectioners() {
        final NotificationRecord notification_alerting = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_DEFAULT);
        assertThat(GroupHelper.getSection(notification_alerting).mName).isEqualTo(
                "AlertingSection");

        final NotificationRecord notification_silent = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_LOW);
        assertThat(GroupHelper.getSection(notification_silent).mName).isEqualTo("SilentSection");

        // Check that special categories are grouped by their importance
        final NotificationChannel promoChannel = new NotificationChannel(
                NotificationChannel.PROMOTIONS_ID, NotificationChannel.PROMOTIONS_ID,
                IMPORTANCE_DEFAULT);
        final NotificationRecord notification_promotion = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, promoChannel);
        assertThat(GroupHelper.getSection(notification_promotion).mName).isEqualTo(
                "AlertingSection");

        final NotificationChannel newsChannel = new NotificationChannel(NotificationChannel.NEWS_ID,
                NotificationChannel.NEWS_ID, IMPORTANCE_DEFAULT);
        final NotificationRecord notification_news = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, newsChannel);
        assertThat(GroupHelper.getSection(notification_news).mName).isEqualTo(
                "AlertingSection");

        final NotificationChannel socialChannel = new NotificationChannel(
                NotificationChannel.SOCIAL_MEDIA_ID, NotificationChannel.SOCIAL_MEDIA_ID,
                IMPORTANCE_DEFAULT);
        final NotificationRecord notification_social = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, socialChannel);
        assertThat(GroupHelper.getSection(notification_social).mName).isEqualTo(
                "AlertingSection");

        final NotificationChannel recsChannel = new NotificationChannel(NotificationChannel.RECS_ID,
                NotificationChannel.RECS_ID, IMPORTANCE_DEFAULT);
        final NotificationRecord notification_recs = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, recsChannel);
        assertThat(GroupHelper.getSection(notification_recs).mName).isEqualTo(
                "AlertingSection");
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING, FLAG_NOTIFICATION_CLASSIFICATION})
    public void testGroupSectioners_withClassificationSections() {
        final NotificationRecord notification_alerting = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_DEFAULT);
        assertThat(GroupHelper.getSection(notification_alerting).mName).isEqualTo(
                "AlertingSection");

        final NotificationRecord notification_silent = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_LOW);
        assertThat(GroupHelper.getSection(notification_silent).mName).isEqualTo("SilentSection");

        // Check that special categories are grouped in their own sections
        final NotificationChannel promoChannel = new NotificationChannel(
                NotificationChannel.PROMOTIONS_ID, NotificationChannel.PROMOTIONS_ID,
                IMPORTANCE_DEFAULT);
        final NotificationRecord notification_promotion = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, promoChannel);
        assertThat(GroupHelper.getSection(notification_promotion).mName).isEqualTo(
                "PromotionsSection");

        final NotificationChannel newsChannel = new NotificationChannel(NotificationChannel.NEWS_ID,
                NotificationChannel.NEWS_ID, IMPORTANCE_DEFAULT);
        final NotificationRecord notification_news = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, newsChannel);
        assertThat(GroupHelper.getSection(notification_news).mName).isEqualTo(
                "NewsSection");

        final NotificationChannel socialChannel = new NotificationChannel(
                NotificationChannel.SOCIAL_MEDIA_ID, NotificationChannel.SOCIAL_MEDIA_ID,
                IMPORTANCE_DEFAULT);
        final NotificationRecord notification_social = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, socialChannel);
        assertThat(GroupHelper.getSection(notification_social).mName).isEqualTo(
                "SocialSection");

        final NotificationChannel recsChannel = new NotificationChannel(NotificationChannel.RECS_ID,
                NotificationChannel.RECS_ID, IMPORTANCE_DEFAULT);
        final NotificationRecord notification_recs = getNotificationRecord(mPkg, 0, "", mUser,
                "", false, recsChannel);
        assertThat(GroupHelper.getSection(notification_recs).mName).isEqualTo(
                "RecsSection");
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING, FLAG_NOTIFICATION_FORCE_GROUP_CONVERSATIONS})
    public void testNonGroupableNotifications_forceGroupConversations() {
        // Check that there is no valid section for: calls, foreground services
        NotificationRecord notification_call = spy(getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_LOW));
        Notification n = mock(Notification.class);
        StatusBarNotification sbn = spy(getSbn("package", 0, "0", UserHandle.SYSTEM));
        when(notification_call.isConversation()).thenReturn(false);
        when(notification_call.getNotification()).thenReturn(n);
        when(notification_call.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(n);
        when(n.isStyle(Notification.CallStyle.class)).thenReturn(true);
        assertThat(GroupHelper.getSection(notification_call)).isNull();

        NotificationRecord notification_colorFg = spy(getNotificationRecord(mPkg, 0, "", mUser,
                "", false, IMPORTANCE_LOW));
        sbn = spy(getSbn("package", 0, "0", UserHandle.SYSTEM));
        n = mock(Notification.class);
        when(notification_colorFg.isConversation()).thenReturn(false);
        when(notification_colorFg.getNotification()).thenReturn(n);
        when(notification_colorFg.getSbn()).thenReturn(sbn);
        when(sbn.getNotification()).thenReturn(n);
        when(n.isForegroundService()).thenReturn(true);
        when(n.isColorized()).thenReturn(true);
        when(n.isStyle(Notification.CallStyle.class)).thenReturn(false);
        assertThat(GroupHelper.getSection(notification_colorFg)).isNull();
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING, FLAG_NOTIFICATION_FORCE_GROUP_CONVERSATIONS})
    @DisableFlags(FLAG_SORT_SECTION_BY_TIME)
    public void testConversationGroupSections_disableSortSectionByTime() {
        // Check that there are separate sections for conversations: alerting and silent
        NotificationRecord notification_conversation_silent = getNotificationRecord(mPkg, 0, "",
                mUser, "", false, IMPORTANCE_LOW);
        notification_conversation_silent = spy(notification_conversation_silent);
        when(notification_conversation_silent.isConversation()).thenReturn(true);
        assertThat(GroupHelper.getSection(notification_conversation_silent).mName).isEqualTo(
                "PeopleSection(silent)");

        // Check that there is a correct section for conversations
        NotificationRecord notification_conversation_alerting = getNotificationRecord(mPkg, 0, "",
                mUser, "", false, IMPORTANCE_DEFAULT);
        notification_conversation_alerting = spy(notification_conversation_alerting);
        when(notification_conversation_alerting.isConversation()).thenReturn(true);
        assertThat(GroupHelper.getSection(notification_conversation_alerting).mName).isEqualTo(
                "PeopleSection(alerting)");
    }

    @Test
    @EnableFlags({FLAG_NOTIFICATION_FORCE_GROUPING,
            FLAG_NOTIFICATION_FORCE_GROUP_CONVERSATIONS,
            FLAG_SORT_SECTION_BY_TIME})
    public void testConversationGroupSections() {
        // Check that there is a single section for silent/alerting conversations
        NotificationRecord notification_conversation_silent = getNotificationRecord(mPkg, 0, "",
                mUser, "", false, IMPORTANCE_LOW);
        notification_conversation_silent = spy(notification_conversation_silent);
        when(notification_conversation_silent.isConversation()).thenReturn(true);
        assertThat(GroupHelper.getSection(notification_conversation_silent).mName).isEqualTo(
                "PeopleSection");

        NotificationRecord notification_conversation_alerting = getNotificationRecord(mPkg, 0, "",
                mUser, "", false, IMPORTANCE_DEFAULT);
        notification_conversation_alerting = spy(notification_conversation_alerting);
        when(notification_conversation_alerting.isConversation()).thenReturn(true);
        assertThat(GroupHelper.getSection(notification_conversation_alerting).mName).isEqualTo(
                "PeopleSection");

        // Check that there is a section for priority conversations
        NotificationRecord notification_conversation_prio = getNotificationRecord(mPkg, 0, "",
                mUser, "", false, IMPORTANCE_DEFAULT);
        notification_conversation_prio = spy(notification_conversation_prio);
        when(notification_conversation_prio.isConversation()).thenReturn(true);
        notification_conversation_prio.getChannel().setImportantConversation(true);
        assertThat(GroupHelper.getSection(notification_conversation_prio).mName).isEqualTo(
                "PeopleSection(priority)");
    }

}
