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

import static android.os.UserHandle.MIN_SECONDARY_USER_ID;
import static android.os.UserHandle.USER_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.content.pm.UserInfo;
import android.graphics.drawable.Icon;
import android.os.UserManager;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


@RunWith(AndroidJUnit4.class)
public class NotificationHistoryManagerTest extends UiServiceTestCase {

    @Mock
    UserManager mUserManager;
    @Mock
    NotificationHistoryDatabase mDb;
    List<UserInfo> mUsers;
    int[] mProfiles;
    int mProfileId = 11;

    NotificationHistoryManager mHistoryManager;

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

        getContext().addMockSystemService(UserManager.class, mUserManager);

        mUsers = new ArrayList<>();
        UserInfo userSystem = new UserInfo();
        userSystem.id = USER_SYSTEM;
        mUsers.add(userSystem);
        UserInfo userFullSecondary = new UserInfo();
        userFullSecondary.id = MIN_SECONDARY_USER_ID;
        mUsers.add(userFullSecondary);
        UserInfo userProfile = new UserInfo();
        userProfile.id = mProfileId;
        mUsers.add(userProfile);
        when(mUserManager.getUsers()).thenReturn(mUsers);

        mProfiles = new int[] {userSystem.id, userProfile.id};
        when(mUserManager.getProfileIds(userSystem.id, true)).thenReturn(mProfiles);
        when(mUserManager.getProfileIds(userFullSecondary.id, true))
                .thenReturn(new int[] {userFullSecondary.id});
        when(mUserManager.getProfileIds(userProfile.id, true))
                .thenReturn(new int[] {userProfile.id});

        when(mUserManager.getProfileParent(userProfile.id)).thenReturn(userSystem);

        NotificationHistoryDatabaseFactory.setTestingNotificationHistoryDatabase(mDb);

        mHistoryManager = new NotificationHistoryManager(getContext(), null);

        for (UserInfo info : mUsers) {
            Settings.Secure.putIntForUser(getContext().getContentResolver(),
                    Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 1, info.id);
            mHistoryManager.mSettingsObserver.update(null, info.id);
        }
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
    public void testOnUserUnlocked_historyDisabled() {
        // create a history
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();
        // lock user
        mHistoryManager.onUserStopped(USER_SYSTEM);

        // turn off history
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        // unlock user, verify that history is disabled
        mHistoryManager.onUserUnlocked(USER_SYSTEM);

        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();
        verify(mDb, times(1)).disableHistory();
    }

    @Test
    public void testOnUserUnlocked_historyDisabled_withProfile() {
        // create a history
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();
        mHistoryManager.onUserUnlocked(mProfileId);
        assertThat(mHistoryManager.doesHistoryExistForUser(mProfileId)).isTrue();
        // lock user
        mHistoryManager.onUserStopped(USER_SYSTEM);
        mHistoryManager.onUserStopped(mProfileId);

        // turn off history
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);
        // fake cloned settings to profile
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, mProfileId);
        mHistoryManager.mSettingsObserver.update(null, mProfileId);

        // unlock user, verify that history is disabled for self and profile
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.onUserUnlocked(mProfileId);

        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isFalse();
        assertThat(mHistoryManager.doesHistoryExistForUser(mProfileId)).isFalse();
        verify(mDb, times(2)).disableHistory();
    }

    @Test
    public void testAddProfile_historyEnabledInPrimary() {
        // create a history
        mHistoryManager.onUserUnlocked(MIN_SECONDARY_USER_ID);
        assertThat(mHistoryManager.doesHistoryExistForUser(MIN_SECONDARY_USER_ID)).isTrue();

        // fake Settings#CLONE_TO_MANAGED_PROFILE
        int newProfileId = 99;
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 1, newProfileId);
        mUsers = new ArrayList<>();
        UserInfo userFullSecondary = new UserInfo();
        userFullSecondary.id = MIN_SECONDARY_USER_ID;
        mUsers.add(userFullSecondary);
        UserInfo userProfile = new UserInfo();
        userProfile.id = newProfileId;
        mUsers.add(userProfile);
        when(mUserManager.getUsers()).thenReturn(mUsers);

        mProfiles = new int[] {MIN_SECONDARY_USER_ID, userProfile.id};
        when(mUserManager.getProfileIds(MIN_SECONDARY_USER_ID, true)).thenReturn(mProfiles);
        when(mUserManager.getProfileIds(userProfile.id, true))
                .thenReturn(new int[] {userProfile.id});
        when(mUserManager.getProfileParent(userProfile.id)).thenReturn(userFullSecondary);

        // add profile
        mHistoryManager.onUserAdded(newProfileId);
        mHistoryManager.onUserUnlocked(newProfileId);
        assertThat(mHistoryManager.doesHistoryExistForUser(newProfileId)).isTrue();
    }

    @Test
    public void testOnUserUnlocked_historyDisabledThenEnabled() {
        // create a history
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();

        // lock user
        mHistoryManager.onUserStopped(USER_SYSTEM);

        // turn off history
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        // turn on history
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 1, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        // unlock user, verify that history is NOT disabled
        mHistoryManager.onUserUnlocked(USER_SYSTEM);

        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();
        verify(mDb, never()).disableHistory();
    }

    @Test
    public void testOnUserUnlocked_historyDisabledThenEnabled_multiProfile() {
        // create a history
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();
        mHistoryManager.onUserUnlocked(mProfileId);
        assertThat(mHistoryManager.doesHistoryExistForUser(mProfileId)).isTrue();

        // lock user
        mHistoryManager.onUserStopped(USER_SYSTEM);
        mHistoryManager.onUserStopped(mProfileId);

        // turn off history
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        // turn on history
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 1, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        // unlock user, verify that history is NOT disabled
        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.onUserUnlocked(mProfileId);

        assertThat(mHistoryManager.doesHistoryExistForUser(USER_SYSTEM)).isTrue();
        assertThat(mHistoryManager.doesHistoryExistForUser(mProfileId)).isTrue();
        verify(mDb, never()).disableHistory();
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
        assertThat(mHistoryManager.isHistoryEnabled(USER_SYSTEM)).isFalse();
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
    public void testOnPackageRemoved_historyDisabled() {
        mHistoryManager.onUserUnlocked(USER_SYSTEM);

        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);
        String pkg = "pkg";
        mHistoryManager.onPackageRemoved(USER_SYSTEM, pkg);

        assertThat(mHistoryManager.getPendingPackageRemovalsForUser(USER_SYSTEM))
                .isNull();
    }

    @Test
    public void testOnPackageRemoved_multiUser() {
        String pkg = "pkg";
        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.onUserUnlocked(MIN_SECONDARY_USER_ID);
        mHistoryManager.replaceNotificationHistoryDatabase(MIN_SECONDARY_USER_ID, userHistoryAll);

        mHistoryManager.onPackageRemoved(USER_SYSTEM, pkg);

        verify(userHistorySystem, times(1)).onPackageRemoved(pkg);
        verify(userHistoryAll, never()).onPackageRemoved(pkg);
    }

    @Test
    public void testDeleteNotificationHistoryItem_userUnlocked() {
        String pkg = "pkg";
        long time = 235;
        NotificationHistoryDatabase userHistory = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistory);

        mHistoryManager.deleteNotificationHistoryItem(pkg, 1, time);

        verify(userHistory, times(1)).deleteNotificationHistoryItem(pkg, time);
    }

    @Test
    public void testDeleteConversation_userUnlocked() {
        String pkg = "pkg";
        Set<String> convos = Set.of("convo", "another");
        NotificationHistoryDatabase userHistory = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistory);

        mHistoryManager.deleteConversations(pkg, 1, convos);

        verify(userHistory, times(1)).deleteConversations(pkg, convos);
    }

    @Test
    public void testDeleteNotificationChannel_userUnlocked() {
        String pkg = "pkg";
        String channelId = "channelId";
        NotificationHistoryDatabase userHistory = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistory);

        mHistoryManager.deleteNotificationChannel(pkg, 1, channelId);

        verify(userHistory, times(1)).deleteNotificationChannel(pkg, channelId);
    }

    @Test
    public void testTriggerWriteToDisk() {
        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.onUserUnlocked(MIN_SECONDARY_USER_ID);
        mHistoryManager.replaceNotificationHistoryDatabase(MIN_SECONDARY_USER_ID, userHistoryAll);

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

        mHistoryManager.onUserUnlocked(MIN_SECONDARY_USER_ID);
        mHistoryManager.replaceNotificationHistoryDatabase(MIN_SECONDARY_USER_ID, userHistoryAll);
        mHistoryManager.onUserStopped(MIN_SECONDARY_USER_ID);

        mHistoryManager.triggerWriteToDisk();

        verify(userHistorySystem, times(1)).forceWriteToDisk();
        verify(userHistoryAll, never()).forceWriteToDisk();
    }

    @Test
    public void testTriggerWriteToDisk_historyDisabled() {
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);
        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.triggerWriteToDisk();

        verify(userHistorySystem, never()).forceWriteToDisk();
    }

    @Test
    public void testAddNotification_userLocked_noCrash() {
        HistoricalNotification hn = getHistoricalNotification("pkg", 1);

        mHistoryManager.addNotification(hn);
    }

    @Test
    public void testAddNotification_historyDisabled() {
        HistoricalNotification hn = getHistoricalNotification("pkg", 1);

        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0, hn.getUserId());
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        mHistoryManager.onUserUnlocked(hn.getUserId());
        mHistoryManager.addNotification(hn);

        verify(mDb, never()).addNotification(any());
    }

    @Test
    public void testAddNotification() {
        HistoricalNotification hnSystem = getHistoricalNotification("pkg", USER_SYSTEM);
        HistoricalNotification hnAll = getHistoricalNotification("pkg", MIN_SECONDARY_USER_ID);

        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);

        mHistoryManager.onUserUnlocked(MIN_SECONDARY_USER_ID);
        mHistoryManager.replaceNotificationHistoryDatabase(MIN_SECONDARY_USER_ID, userHistoryAll);

        mHistoryManager.addNotification(hnSystem);
        mHistoryManager.addNotification(hnAll);

        verify(userHistorySystem, times(1)).addNotification(hnSystem);
        verify(userHistoryAll, times(1)).addNotification(hnAll);
    }

    @Test
    public void testReadNotificationHistory() {
        HistoricalNotification hnSystem = getHistoricalNotification("pkg", USER_SYSTEM);
        HistoricalNotification hnAll = getHistoricalNotification("pkg", MIN_SECONDARY_USER_ID);

        NotificationHistoryDatabase userHistorySystem = mock(NotificationHistoryDatabase.class);
        NotificationHistoryDatabase userHistoryAll = mock(NotificationHistoryDatabase.class);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        mHistoryManager.replaceNotificationHistoryDatabase(USER_SYSTEM, userHistorySystem);
        NotificationHistory nhSystem = mock(NotificationHistory.class);
        ArrayList<HistoricalNotification> nhSystemList = new ArrayList<>();
        nhSystemList.add(hnSystem);
        when(nhSystem.getNotificationsToWrite()).thenReturn(nhSystemList);
        when(userHistorySystem.readNotificationHistory()).thenReturn(nhSystem);

        mHistoryManager.onUserUnlocked(MIN_SECONDARY_USER_ID);
        mHistoryManager.replaceNotificationHistoryDatabase(MIN_SECONDARY_USER_ID, userHistoryAll);
        NotificationHistory nhAll = mock(NotificationHistory.class);
        ArrayList<HistoricalNotification> nhAllList = new ArrayList<>();
        nhAllList.add(hnAll);
        when(nhAll.getNotificationsToWrite()).thenReturn(nhAllList);
        when(userHistoryAll.readNotificationHistory()).thenReturn(nhAll);

        // ensure read history returns both historical notifs
        NotificationHistory nh = mHistoryManager.readNotificationHistory(
                new int[] {USER_SYSTEM, MIN_SECONDARY_USER_ID});
        assertThat(nh.getNotificationsToWrite()).contains(hnSystem);
        assertThat(nh.getNotificationsToWrite()).contains(hnAll);
    }

    @Test
    public void testReadNotificationHistory_historyDisabled() {
        HistoricalNotification hnSystem = getHistoricalNotification("pkg", USER_SYSTEM);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
        NotificationHistory nhSystem = mock(NotificationHistory.class);
        ArrayList<HistoricalNotification> nhSystemList = new ArrayList<>();
        nhSystemList.add(hnSystem);
        when(nhSystem.getNotificationsToWrite()).thenReturn(nhSystemList);
        when(mDb.readNotificationHistory()).thenReturn(nhSystem);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);

        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0,  USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        NotificationHistory nh =
                mHistoryManager.readNotificationHistory(new int[] {USER_SYSTEM,});
        assertThat(nh.getNotificationsToWrite()).isEmpty();
    }

    @Test
    public void testReadFilteredNotificationHistory_userLocked() {
        NotificationHistory nh =
                mHistoryManager.readFilteredNotificationHistory(USER_SYSTEM, "", "", 1000);
        assertThat(nh.getNotificationsToWrite()).isEmpty();
    }

    @Test
    public void testReadFilteredNotificationHistory_historyDisabled() {
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0,  USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        mHistoryManager.onUserUnlocked(USER_SYSTEM);
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

    @Test
    public void testIsHistoryEnabled() {
        assertThat(mHistoryManager.isHistoryEnabled(USER_SYSTEM)).isTrue();

        mHistoryManager.onUserUnlocked(USER_SYSTEM);

        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0,  USER_SYSTEM);
        mHistoryManager.mSettingsObserver.update(null, USER_SYSTEM);

        assertThat(mHistoryManager.isHistoryEnabled(USER_SYSTEM)).isFalse();
    }
}
