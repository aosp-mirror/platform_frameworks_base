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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.Person;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutQueryWrapper;
import android.content.pm.ShortcutServiceInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class ShortcutHelperTest extends UiServiceTestCase {

    private static final String SHORTCUT_ID = "shortcut";
    private static final String PKG = "pkg";
    private static final String KEY = "key";
    private static final Person PERSON = mock(Person.class);

    @Mock
    LauncherApps mLauncherApps;
    @Mock
    ShortcutHelper.ShortcutListener mShortcutListener;
    @Mock
    UserManager mUserManager;
    @Mock
    ShortcutServiceInternal mShortcutServiceInternal;
    @Mock
    NotificationRecord mNr;
    @Mock
    Notification mNotif;
    @Mock
    StatusBarNotification mSbn;
    @Mock
    Notification.BubbleMetadata mBubbleMetadata;
    @Mock
    ShortcutInfo mShortcutInfo;

    @Captor private ArgumentCaptor<ShortcutQuery> mShortcutQueryCaptor;

    ShortcutHelper mShortcutHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mShortcutHelper = new ShortcutHelper(
                mLauncherApps, mShortcutListener, mShortcutServiceInternal, mUserManager);
        when(mSbn.getPackageName()).thenReturn(PKG);
        when(mShortcutInfo.getId()).thenReturn(SHORTCUT_ID);
        when(mNotif.getBubbleMetadata()).thenReturn(mBubbleMetadata);
        when(mBubbleMetadata.getShortcutId()).thenReturn(SHORTCUT_ID);
        when(mUserManager.isUserUnlocked(any(UserHandle.class))).thenReturn(true);

        setUpMockNotificationRecord(mNr, KEY);
    }

    private void setUpMockNotificationRecord(NotificationRecord mockRecord, String key) {
        when(mockRecord.getKey()).thenReturn(key);
        when(mockRecord.getSbn()).thenReturn(mSbn);
        when(mockRecord.getNotification()).thenReturn(mNotif);
        when(mockRecord.getShortcutInfo()).thenReturn(mShortcutInfo);
    }

    private LauncherApps.Callback addShortcutBubbleAndVerifyListener() {
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(mNr,
                false /* removed */,
                null /* handler */);

        ArgumentCaptor<LauncherApps.Callback> launcherAppsCallback =
                ArgumentCaptor.forClass(LauncherApps.Callback.class);

        verify(mLauncherApps, times(1)).registerCallback(
                launcherAppsCallback.capture(), any());
        return launcherAppsCallback.getValue();
    }

    @Test
    public void testBubbleAdded_listenedAdded() {
        addShortcutBubbleAndVerifyListener();
    }

    @Test
    public void testBubbleRemoved_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener();

        // Then remove the notif
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(mNr,
                true /* removed */,
                null /* handler */);

        verify(mLauncherApps, times(1)).unregisterCallback(any());
    }

    @Test
    public void testBubbleNoLongerHasBubbleMetadata_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener();

        // Then make it not a bubble
        when(mNotif.getBubbleMetadata()).thenReturn(null);
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(mNr,
                false /* removed */,
                null /* handler */);

        verify(mLauncherApps, times(1)).unregisterCallback(any());
    }

    @Test
    public void testBubbleNoLongerHasShortcutId_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener();

        // Clear out shortcutId
        when(mBubbleMetadata.getShortcutId()).thenReturn(null);
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(mNr,
                false /* removed */,
                null /* handler */);

        verify(mLauncherApps, times(1)).unregisterCallback(any());
    }

    @Test
    public void testNotifNoLongerHasShortcut_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener();

        NotificationRecord validMock1 = Mockito.mock(NotificationRecord.class);
        setUpMockNotificationRecord(validMock1, "KEY1");

        NotificationRecord validMock2 = Mockito.mock(NotificationRecord.class);
        setUpMockNotificationRecord(validMock2, "KEY2");

        NotificationRecord validMock3 = Mockito.mock(NotificationRecord.class);
        setUpMockNotificationRecord(validMock3, "KEY3");

        mShortcutHelper.maybeListenForShortcutChangesForBubbles(validMock1,
                false /* removed */,
                null /* handler */);

        mShortcutHelper.maybeListenForShortcutChangesForBubbles(validMock2,
                false /* removed */,
                null /* handler */);

        mShortcutHelper.maybeListenForShortcutChangesForBubbles(validMock3,
                false /* removed */,
                null /* handler */);

        // Clear out shortcutId of the bubble in the middle, to double check that we don't hit a
        // concurrent modification exception (removing the last bubble would sidestep that check).
        when(validMock2.getShortcutInfo()).thenReturn(null);
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(validMock2,
                false /* removed */,
                null /* handler */);

        verify(mLauncherApps, times(1)).unregisterCallback(any());
    }

    @Test
    public void testOnShortcutsChanged_listenerRemoved() {
        // First set it up to listen
        LauncherApps.Callback callback = addShortcutBubbleAndVerifyListener();

        // App shortcuts are removed:
        callback.onShortcutsChanged(PKG, Collections.emptyList(),  mock(UserHandle.class));

        verify(mLauncherApps, times(1)).unregisterCallback(any());
    }

    @Test
    public void testListenerNotifiedOnShortcutRemoved() {
        LauncherApps.Callback callback = addShortcutBubbleAndVerifyListener();

        List<ShortcutInfo> shortcutInfos = new ArrayList<>();
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcutInfos);

        callback.onShortcutsChanged(PKG, shortcutInfos, mock(UserHandle.class));
        verify(mShortcutListener).onShortcutRemoved(mNr.getKey());
    }

    @Test
    public void testGetValidShortcutInfo_noMatchingShortcut() {
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(null);
        when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any())).thenReturn(true);

        assertThat(mShortcutHelper.getValidShortcutInfo("a", "p", UserHandle.SYSTEM)).isNull();
    }

    @Test
    public void testGetValidShortcutInfo_nullShortcut() {
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(null);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any())).thenReturn(true);

        assertThat(mShortcutHelper.getValidShortcutInfo("a", "p", UserHandle.SYSTEM)).isNull();
    }

    @Test
    public void testGetValidShortcutInfo_notLongLived() {
        ShortcutInfo si = mock(ShortcutInfo.class);
        when(si.getPackage()).thenReturn(PKG);
        when(si.getId()).thenReturn(SHORTCUT_ID);
        when(si.getUserId()).thenReturn(UserHandle.USER_SYSTEM);
        when(si.isLongLived()).thenReturn(false);
        when(si.isEnabled()).thenReturn(true);
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(si);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any())).thenReturn(true);

        assertThat(mShortcutHelper.getValidShortcutInfo("a", "p", UserHandle.SYSTEM)).isNull();
    }

    @Ignore("b/155016294")
    @Test
    public void testGetValidShortcutInfo_notSharingShortcut() {
        ShortcutInfo si = mock(ShortcutInfo.class);
        when(si.getPackage()).thenReturn(PKG);
        when(si.getId()).thenReturn(SHORTCUT_ID);
        when(si.getUserId()).thenReturn(UserHandle.USER_SYSTEM);
        when(si.isLongLived()).thenReturn(true);
        when(si.isEnabled()).thenReturn(true);
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(si);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any())).thenReturn(false);

        assertThat(mShortcutHelper.getValidShortcutInfo("a", "p", UserHandle.SYSTEM)).isNull();
    }

    @Test
    public void testGetValidShortcutInfo_notEnabled() {
        ShortcutInfo si = mock(ShortcutInfo.class);
        when(si.getPackage()).thenReturn(PKG);
        when(si.getId()).thenReturn(SHORTCUT_ID);
        when(si.getUserId()).thenReturn(UserHandle.USER_SYSTEM);
        when(si.isLongLived()).thenReturn(true);
        when(si.isEnabled()).thenReturn(false);
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(si);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
                anyString(), anyInt(), any())).thenReturn(true);

        assertThat(mShortcutHelper.getValidShortcutInfo("a", "p", UserHandle.SYSTEM)).isNull();
    }

    @Test
    public void testGetValidShortcutInfo_isValid() {
        ShortcutInfo si = mock(ShortcutInfo.class);
        when(si.getPackage()).thenReturn(PKG);
        when(si.getId()).thenReturn(SHORTCUT_ID);
        when(si.getUserId()).thenReturn(UserHandle.USER_SYSTEM);
        when(si.isLongLived()).thenReturn(true);
        when(si.isEnabled()).thenReturn(true);
        when(si.getPersons()).thenReturn(new Person[]{PERSON});
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(si);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        // TODO: b/155016294
        //when(mShortcutServiceInternal.isSharingShortcut(anyInt(), anyString(), anyString(),
         //       anyString(), anyInt(), any())).thenReturn(true);

        assertThat(mShortcutHelper.getValidShortcutInfo("a", "p", UserHandle.SYSTEM))
                .isSameInstanceAs(si);
    }


    @Test
    public void testGetValidShortcutInfo_isValidButUserLocked() {
        ShortcutInfo si = mock(ShortcutInfo.class);
        when(si.getPackage()).thenReturn(PKG);
        when(si.getId()).thenReturn(SHORTCUT_ID);
        when(si.getUserId()).thenReturn(UserHandle.USER_SYSTEM);
        when(si.isLongLived()).thenReturn(true);
        when(si.isEnabled()).thenReturn(true);
        when(si.getPersons()).thenReturn(new Person[]{PERSON});
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(si);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        when(mUserManager.isUserUnlocked(any(UserHandle.class))).thenReturn(false);

        assertThat(mShortcutHelper.getValidShortcutInfo("a", "p", UserHandle.SYSTEM))
                .isNull();
    }

    @Test
    public void testGetValidShortcutInfo_hasGetPersonsDataFlag() {

        ShortcutInfo info = mShortcutHelper.getValidShortcutInfo(
                "a", "p", UserHandle.SYSTEM);
        verify(mLauncherApps).getShortcuts(mShortcutQueryCaptor.capture(), any());
        ShortcutQueryWrapper shortcutQuery =
                new ShortcutQueryWrapper(mShortcutQueryCaptor.getValue());
        assertThat(hasFlag(shortcutQuery.getQueryFlags(), ShortcutQuery.FLAG_GET_PERSONS_DATA))
                .isTrue();
    }

    /**
     * Returns {@code true} iff {@link ShortcutQuery}'s {@code queryFlags} has {@code flag} set.
    */
    private static boolean hasFlag(int queryFlags, int flag) {
        return (queryFlags & flag) != 0;
    }
}
