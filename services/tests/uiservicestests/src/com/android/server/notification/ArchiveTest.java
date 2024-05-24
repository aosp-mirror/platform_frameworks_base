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
package com.android.server.notification;

import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;
import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.StatusBarNotification;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ArchiveTest extends UiServiceTestCase {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int SIZE = 5;

    private NotificationManagerService.Archive mArchive;
    @Mock
    private UserManager mUm;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mArchive = new NotificationManagerService.Archive(SIZE);
        mArchive.updateHistoryEnabled(USER_SYSTEM, true);
        mArchive.updateHistoryEnabled(USER_CURRENT, true);

        when(mUm.getProfileIds(anyInt(), anyBoolean())).thenReturn(
                new int[] {USER_CURRENT, USER_SYSTEM});
    }

    private StatusBarNotification getNotification(String pkg, int id, UserHandle user) {
        Notification n = new Notification.Builder(getContext(), "test" + id)
                .setContentTitle("A")
                .setWhen(1205)
                .build();
        return  new StatusBarNotification(
                pkg, pkg, id, null, 0, 0, n, user, null, System.currentTimeMillis());
    }

    @Test
    public void testRecordAndRead() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            StatusBarNotification sbn = getNotification("pkg" + i, i,
                    UserHandle.of(i % 2 ==0 ? USER_SYSTEM : USER_CURRENT));
            expected.add(sbn.getKey());
            mArchive.record(sbn, REASON_CANCEL);
        }

        List<StatusBarNotification> actual = Arrays.asList(mArchive.getArray(mUm, SIZE, true));
        assertThat(actual).hasSize(expected.size());
        for (StatusBarNotification sbn : actual) {
            assertThat(expected).contains(sbn.getKey());
        }
    }

    @Test
    public void testCrossUser() {
        mArchive.record(getNotification("pkg", 1, UserHandle.of(USER_SYSTEM)), REASON_CANCEL);
        mArchive.record(getNotification("pkg", 2, UserHandle.of(USER_CURRENT)), REASON_CANCEL);
        mArchive.record(getNotification("pkg", 3, UserHandle.of(USER_ALL)), REASON_CANCEL);
        mArchive.record(getNotification("pkg", 4, UserHandle.of(USER_NULL)), REASON_CANCEL);

        List<StatusBarNotification> actual = Arrays.asList(mArchive.getArray(mUm, SIZE, true));
        assertThat(actual).hasSize(3);
        for (StatusBarNotification sbn : actual) {
            if (sbn.getUserId() == USER_NULL) {
                fail("leaked notification from wrong user");
            }
        }
    }

    @Test
    public void testRecordAndRead_overLimit() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < (SIZE * 2); i++) {
            StatusBarNotification sbn = getNotification("pkg" + i, i, UserHandle.of(USER_SYSTEM));
            mArchive.record(sbn, REASON_CANCEL);
            if (i >= SIZE) {
                expected.add(sbn.getKey());
            }
        }

        List<StatusBarNotification> actual = Arrays.asList(
                mArchive.getArray(mUm, (SIZE * 2), true));
        assertThat(actual).hasSize(expected.size());
        for (StatusBarNotification sbn : actual) {
            assertThat(expected).contains(sbn.getKey());
        }
    }

    @Test
    public void testDoesNotRecordIfHistoryDisabled() {
        mArchive.updateHistoryEnabled(USER_CURRENT, false);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            StatusBarNotification sbn = getNotification("pkg" + i, i,
                    UserHandle.of(i % 2 ==0 ? USER_SYSTEM : USER_CURRENT));
            mArchive.record(sbn, REASON_CANCEL);
            if (i % 2 ==0) {
                expected.add(sbn.getKey());
            }
        }

        List<StatusBarNotification> actual = Arrays.asList(mArchive.getArray(mUm, SIZE, true));
        assertThat(actual).hasSize(expected.size());
        for (StatusBarNotification sbn : actual) {
            assertThat(expected).contains(sbn.getKey());
        }
    }

    @Test
    public void testRemovesEntriesWhenHistoryDisabled() {
        mArchive.updateHistoryEnabled(USER_CURRENT, true);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            StatusBarNotification sbn = getNotification("pkg" + i, i,
                    UserHandle.of(i % 2 ==0 ? USER_SYSTEM : USER_CURRENT));
            mArchive.record(sbn, REASON_CANCEL);
            if (i % 2 ==0) {
                expected.add(sbn.getKey());
            }
        }
        mArchive.updateHistoryEnabled(USER_CURRENT, false);

        List<StatusBarNotification> actual = Arrays.asList(mArchive.getArray(mUm, SIZE, true));
        assertThat(actual).hasSize(expected.size());
        for (StatusBarNotification sbn : actual) {
            assertThat(expected).contains(sbn.getKey());
        }
    }

    @Test
    public void testRemoveChannelNotifications() {
        List<String> expected = new ArrayList<>();
        // Add one extra notification to the beginning to test when 2 adjacent notifications will be
        // removed in the same pass.
        StatusBarNotification sbn0 = getNotification("pkg", 0, UserHandle.of(USER_CURRENT));
        mArchive.record(sbn0, REASON_CANCEL);
        for (int i = 0; i < SIZE - 1; i++) {
            StatusBarNotification sbn = getNotification("pkg", i, UserHandle.of(USER_CURRENT));
            mArchive.record(sbn, REASON_CANCEL);
            if (i != 0 && i != SIZE - 2) {
                // Will delete notification for this user in channel "test0", and also the last
                // element in the list.
                expected.add(sbn.getKey());
            }
        }
        mArchive.removeChannelNotifications("pkg", USER_CURRENT, "test0");
        mArchive.removeChannelNotifications("pkg", USER_CURRENT, "test" + (SIZE - 2));
        List<StatusBarNotification> actual = Arrays.asList(mArchive.getArray(mUm, SIZE, true));
        assertThat(actual).hasSize(expected.size());
        for (StatusBarNotification sbn : actual) {
            assertThat(expected).contains(sbn.getKey());
        }
    }

    @Test
    public void testRemoveChannelNotifications_concurrently() throws InterruptedException {
        List<String> expected = new ArrayList<>();
        // Add one extra notification to the beginning to test when 2 adjacent notifications will be
        // removed in the same pass.
        StatusBarNotification sbn0 = getNotification("pkg", 0, UserHandle.of(USER_CURRENT));
        mArchive.record(sbn0, REASON_CANCEL);
        for (int i = 0; i < SIZE; i++) {
            StatusBarNotification sbn = getNotification("pkg", i, UserHandle.of(USER_CURRENT));
            mArchive.record(sbn, REASON_CANCEL);
            if (i >= SIZE - 2) {
                // Remove everything < SIZE - 2
                expected.add(sbn.getKey());
            }
        }

        // Remove these in multiple threads to try to get them to happen at the same time
        int numThreads = SIZE - 2;
        AtomicBoolean error = new AtomicBoolean(false);
        CountDownLatch startThreadsLatch = new CountDownLatch(1);
        CountDownLatch threadsDone = new CountDownLatch(numThreads);
        for (int i = 0; i < numThreads; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startThreadsLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                try {
                    mArchive.removeChannelNotifications("pkg", USER_CURRENT, "test" + idx);
                } catch (ConcurrentModificationException e) {
                    error.compareAndSet(false, true);
                }
            }).start();
        }

        startThreadsLatch.countDown();
        threadsDone.await(10, TimeUnit.SECONDS);
        if (error.get()) {
            fail("Concurrent modification exception");
        }

        List<StatusBarNotification> actual = Arrays.asList(mArchive.getArray(mUm, SIZE, true));
        assertThat(actual).hasSize(expected.size());
        for (StatusBarNotification sbn : actual) {
            assertThat(expected).contains(sbn.getKey());
        }
    }

    @Test
    public void testRemoveNotificationsByPackage() {
        List<String> expected = new ArrayList<>();

        StatusBarNotification sbn_remove = getNotification("pkg_remove", 0,
                UserHandle.of(USER_CURRENT));
        mArchive.record(sbn_remove, REASON_CANCEL);

        StatusBarNotification sbn_keep = getNotification("pkg_keep", 1,
                UserHandle.of(USER_CURRENT));
        mArchive.record(sbn_keep, REASON_CANCEL);
        expected.add(sbn_keep.getKey());

        StatusBarNotification sbn_remove2 = getNotification("pkg_remove", 2,
                UserHandle.of(USER_CURRENT));
        mArchive.record(sbn_remove2, REASON_CANCEL);

        mArchive.removePackageNotifications("pkg_remove", USER_CURRENT);
        List<StatusBarNotification> actual = Arrays.asList(mArchive.getArray(mUm, SIZE, true));
        assertThat(actual).hasSize(expected.size());
        for (StatusBarNotification sbn : actual) {
            assertThat(expected).contains(sbn.getKey());
        }
    }
}
