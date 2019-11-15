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
package com.android.server.notification;

import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class NotificationHistoryManagerTest extends UiServiceTestCase {

    @Mock
    Context mContext;
    @Mock
    UserManager mUserManager;
    @Mock
    NotificationHistoryDatabase mDb;

    NotificationHistoryManager mHistoryManager;

    private HistoricalNotification getHistoricalNotification(int index) {
        return getHistoricalNotification("package" + index, index);
    }

    private HistoricalNotification getHistoricalNotification(String packageName, int index) {
        String expectedChannelName = "channelName" + index;
        String expectedChannelId = "channelId" + index;
        int expectedUid = 1123456 + index;
        int expectedUserId = index;
        long expectedPostTime = 987654321 + index;
        String expectedTitle = "title" + index;
        String expectedText = "text" + index;
        Icon expectedIcon = Icon.createWithResource(InstrumentationRegistry.getContext(),
                index);

        return new HistoricalNotification.Builder()
                .setPackage(packageName)
                .setChannelName(expectedChannelName)
                .setChannelId(expectedChannelId)
                .setUid(expectedUid)
                .setUserId(expectedUserId)
                .setPostedTimeMs(expectedPostTime)
                .setTitle(expectedTitle)
                .setText(expectedText)
                .setIcon(expectedIcon)
                .build();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mContext.getUser()).thenReturn(getContext().getUser());
        when(mContext.getPackageName()).thenReturn(getContext().getPackageName());

        NotificationHistoryDatabaseFactory.setTestingNotificationHistoryDatabase(mDb);

        mHistoryManager = new NotificationHistoryManager(mContext);
    }

    @Test
    public void testOnUserUnlocked() {
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();
        assertThat(mHistoryManager.isUserUnlocked(USER_SYSTEM)).isFalse();
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();
        assertThat(mHistoryManager.isUserUnlocked(USER_SYSTEM)).isTrue();
        verify(mDb, times(1)).init();
    }

    @Test
    public void testOnUserUnlocked_cleansUpRemovedPackages() {
        String pkg = "pkg";
        mHistoryManager.onPackageRemoved(USER_SYSTEM, pkg);
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();
        assertThat(mHistoryManager.isUserUnlocked(USER_SYSTEM)).isTrue();

        verify(mDb, times(1)).onPackageRemoved(pkg);
    }

    @Test
    public void testOnUserStopped_userExists() {
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.onUserStopped(USER_SYSTEM);

        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();
        assertThat(mHistoryManager.isUserUnlocked(USER_SYSTEM)).isFalse();
    }

    @Test
    public void testOnUserStopped_userDoesNotExist() {
        mHistoryManager.onUserStopped(USER_SYSTEM);
        // no crash
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();
        assertThat(mHistoryManager.isUserUnlocked(USER_SYSTEM)).isFalse();
    }

    @Test
    public void testOnUserRemoved_userExists() {
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.onUserRemoved(USER_SYSTEM);

        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();
        assertThat(mHistoryManager.isUserUnlocked(USER_SYSTEM)).isFalse();
    }

    @Test
    public void testOnUserRemoved_userDoesNotExist() {
        mHistoryManager.onUserRemoved(USER_SYSTEM);
        // no crash
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();
        assertThat(mHistoryManager.isUserUnlocked(USER_SYSTEM)).isFalse();
    }

    @Test
    public void testOnUserRemoved_cleanupPendingPackages() {
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.onUserStopped(USER_SYSTEM);
        String pkg = "pkg";
        mHistoryManager.onPackageRemoved(USER_SYSTEM, pkg);
        mHistoryManager.onUserRemoved(USER_SYSTEM);

        assertThat(mHistoryManager.getPendingPackageRemovalsForUser(USER_SYSTEM)).isNull();
    }

    @Test
    public void testOnPackageRemoved_userUnlocked() {
        String pkg = "pkg";
        NotificationHistoryDatabase userHistory = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistory);

        mHistoryManager.onPackageRemoved(USER_SYSTEM, pkg);

        verify(userHistory, times(1)).onPackageRemoved(pkg);
    }

    @Test
    public void testOnPackageRemoved_userLocked() {
        String pkg = "pkg";
        mHistoryManager.onPackageRemoved(USER_SYSTEM, pkg);

        assertThat(mHistoryManager.getPendingPackageRemovalsForUser(USER_SYSTEM)).contains(pkg);
    }

    @Test
    public void testOnPackageRemoved_multiUser() {
        String pkg = "pkg";
        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.onUserUnlocked(USER_ALL);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_ALL, userHistoryAll);

        mHistoryManager.onPackageRemoved(USER_SYSTEM, pkg);

        verify(userHistorySystem, times(1)).onPackageRemoved(pkg);
        verify(userHistoryAll, never()).onPackageRemoved(pkg);
    }

    @Test
    public void testTriggerWriteToDisk() {
        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.onUserUnlocked(USER_ALL);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_ALL, userHistoryAll);

        mHistoryManager.triggerWriteToDisk();

        verify(userHistorySystem, times(1)).forceWriteToDisk();
        verify(userHistoryAll, times(1)).forceWriteToDisk();
    }

    @Test
    public void testTriggerWriteToDisk_onlyUnlockedUsers() {
        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.onUserUnlocked(USER_ALL);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_ALL, userHistoryAll);
        mHistoryManager.onUserStopped(USER_ALL);

        mHistoryManager.triggerWriteToDisk();

        verify(userHistorySystem, times(1)).forceWriteToDisk();
        verify(userHistoryAll, never()).forceWriteToDisk();
    }

    @Test
    public void testAddNotification_userLocked_noCrash() {
        HistoricalNotification hn = getHistoricalNotification("pkg", 1);

        mHistoryManager.addNotification(hn);
    }

    @Test
    public void testAddNotification() {
        HistoricalNotification hnSystem = getHistoricalNotification("pkg", USER_SYSTEM);
        HistoricalNotification hnAll = getHistoricalNotification("pkg", USER_ALL);

        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.onUserUnlocked(USER_ALL);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_ALL, userHistoryAll);

        mHistoryManager.addNotification(hnSystem);
        mHistoryManager.addNotification(hnAll);

        verify(userHistorySystem, times(1)).addNotification(hnSystem);
        verify(userHistoryAll, times(1)).addNotification(hnAll);
    }

    @Test
    public void testReadNotificationHistory() {
        HistoricalNotification hnSystem = getHistoricalNotification("pkg", USER_SYSTEM);
        HistoricalNotification hnAll = getHistoricalNotification("pkg", USER_ALL);

        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);
        NotificationHistory nhSystem = mock(NotificationHistory.class);
        ArrayList<HistoricalNotification> nhSystemList = new ArrayList<>();
        nhSystemList.add(hnSystem);
        when(nhSystem.getNotificationsToWrite()).thenReturn(nhSystemList);
        when(userHistorySystem.readNotificationHistory()).thenReturn(nhSystem);

        mHistoryManager.onUserUnlocked(USER_ALL);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_ALL, userHistoryAll);
        NotificationHistory nhAll = mock(NotificationHistory.class);
        ArrayList<HistoricalNotification> nhAllList = new ArrayList<>();
        nhAllList.add(hnAll);
        when(nhAll.getNotificationsToWrite()).thenReturn(nhAllList);
        when(userHistoryAll.readNotificationHistory()).thenReturn(nhAll);

        // ensure read history returns both historical notifs
        NotificationHistory nh = mHistoryManager.readNotificationHistory(
                new int[] {USER_SYSTEM, USER_ALL});
        assertThat(nh.getNotificationsToWrite()).contains(hnSystem);
        assertThat(nh.getNotificationsToWrite()).contains(hnAll);
    }

    @Test
    public void readFilteredNotificationHistory_userUnlocked() {
        NotificationHistory nh =
                mHistoryManager.readFilteredNotificationHistory(USER_SYSTEM, "", "", 1000);
        assertThat(nh.getNotificationsToWrite()).isEmpty();
    }

    @Test
    public void readFilteredNotificationHistory() {
        mHistoryManager.onUserUnlocked(USER_SYSTEM);

        mHistoryManager.readFilteredNotificationHistory(USER_SYSTEM, "pkg", "chn", 1000);
        verify(mDb, times(1)).readNotificationHistory("pkg", "chn", 1000);
    }
}
