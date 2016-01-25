/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_HIGH;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_LOW;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_MAX;
import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_NONE;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import android.app.Notification;
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.ArrayList;

public class RankingHelperTest extends AndroidTestCase {
    @Mock NotificationUsageStats mUsageStats;
    @Mock RankingHandler handler;

    private Notification mNotiGroupGSortA;
    private Notification mNotiGroupGSortB;
    private Notification mNotiNoGroup;
    private Notification mNotiNoGroup2;
    private Notification mNotiNoGroupSortA;
    private NotificationRecord mRecordGroupGSortA;
    private NotificationRecord mRecordGroupGSortB;
    private NotificationRecord mRecordNoGroup;
    private NotificationRecord mRecordNoGroup2;
    private NotificationRecord mRecordNoGroupSortA;
    private RankingHelper mHelper;

    @Override
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        UserHandle user = UserHandle.ALL;

        mHelper = new RankingHelper(getContext(), handler, mUsageStats,
                new String[] {TopicImportanceExtractor.class.getName()});

        mNotiGroupGSortA = new Notification.Builder(getContext())
                .setContentTitle("A")
                .setGroup("G")
                .setSortKey("A")
                .setWhen(1205)
                .setTopic(new Notification.Topic("A", "a"))
                .build();
        mRecordGroupGSortA = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiGroupGSortA, user));

        mNotiGroupGSortB = new Notification.Builder(getContext())
                .setContentTitle("B")
                .setGroup("G")
                .setSortKey("B")
                .setWhen(1200)
                .setTopic(new Notification.Topic("A", "a"))
                .build();
        mRecordGroupGSortB = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiGroupGSortB, user));

        mNotiNoGroup = new Notification.Builder(getContext())
                .setContentTitle("C")
                .setWhen(1201)
                .setTopic(new Notification.Topic("C", "c"))
                .build();
        mRecordNoGroup = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiNoGroup, user));

        mNotiNoGroup2 = new Notification.Builder(getContext())
                .setContentTitle("D")
                .setWhen(1202)
                .setTopic(new Notification.Topic("D", "d"))
                .build();
        mRecordNoGroup2 = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiNoGroup2, user));

        mNotiNoGroupSortA = new Notification.Builder(getContext())
                .setContentTitle("E")
                .setWhen(1201)
                .setSortKey("A")
                .setTopic(new Notification.Topic("E", "e"))
                .build();
        mRecordNoGroupSortA = new NotificationRecord(getContext(), new StatusBarNotification(
                "package", "package", 1, null, 0, 0, 0, mNotiNoGroupSortA, user));
    }

    @SmallTest
    public void testFindAfterRankingWithASplitGroup() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(3);
        notificationList.add(mRecordGroupGSortA);
        notificationList.add(mRecordGroupGSortB);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroupSortA);
        mHelper.sort(notificationList);
        assertTrue(mHelper.indexOf(notificationList, mRecordGroupGSortA) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordGroupGSortB) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordNoGroup) >= 0);
        assertTrue(mHelper.indexOf(notificationList, mRecordNoGroupSortA) >= 0);
    }

    @SmallTest
    public void testSortShouldNotThrowWithPlainNotifications() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(2);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroup2);
        mHelper.sort(notificationList);
    }

    @SmallTest
    public void testSortShouldNotThrowOneSorted() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(2);
        notificationList.add(mRecordNoGroup);
        notificationList.add(mRecordNoGroupSortA);
        mHelper.sort(notificationList);
    }

    @SmallTest
    public void testSortShouldNotThrowOneNotification() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(1);
        notificationList.add(mRecordNoGroup);
        mHelper.sort(notificationList);
    }

    @SmallTest
    public void testSortShouldNotThrowOneSortKey() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>(1);
        notificationList.add(mRecordGroupGSortB);
        mHelper.sort(notificationList);
    }

    @SmallTest
    public void testSortShouldNotThrowOnEmptyList() throws Exception {
        ArrayList<NotificationRecord> notificationList = new ArrayList<NotificationRecord>();
        mHelper.sort(notificationList);
    }

    @SmallTest
    public void testTopicImportanceExtractor() throws Exception {
        mHelper.setImportance("package", 0, new Notification.Topic("A", "a"), IMPORTANCE_MAX);
        // There is no B. There never was a b. Moving on...
        mHelper.setImportance("package", 0, new Notification.Topic("C", "c"), IMPORTANCE_HIGH);
        mHelper.setImportance("package", 0, new Notification.Topic("D", "d"), IMPORTANCE_LOW);
        // watch out: different package.
        mHelper.setImportance("package2", 0, new Notification.Topic("E", "e"), IMPORTANCE_NONE);

        TopicImportanceExtractor validator = mHelper.findExtractor(TopicImportanceExtractor.class);
        validator.process(mRecordGroupGSortA);
        validator.process(mRecordGroupGSortB);
        validator.process(mRecordNoGroup);
        validator.process(mRecordNoGroup2);
        validator.process(mRecordNoGroupSortA);
        assertTrue(mRecordGroupGSortA.getTopicImportance() == IMPORTANCE_MAX);
        assertTrue(mRecordGroupGSortB.getTopicImportance() == IMPORTANCE_MAX);
        assertTrue(mRecordNoGroup.getTopicImportance() == IMPORTANCE_HIGH);
        assertTrue(mRecordNoGroup2.getTopicImportance() == IMPORTANCE_LOW);
        assertTrue(mRecordNoGroupSortA.getTopicImportance() == IMPORTANCE_UNSPECIFIED);
    }
}
