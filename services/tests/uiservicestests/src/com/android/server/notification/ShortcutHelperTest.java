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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Person;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutQueryWrapper;
import android.content.pm.ShortcutServiceInternal;
import android.graphics.drawable.Icon;
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
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class ShortcutHelperTest extends UiServiceTestCase {

    private static final String SHORTCUT_ID = "shortcut";
    private static final String PKG = "pkg";
    private static final Person PERSON = mock(Person.class);

    @Mock
    LauncherApps mLauncherApps;
    @Mock
    ShortcutHelper.ShortcutListener mShortcutListener;
    @Mock
    UserManager mUserManager;
    @Mock
    ShortcutServiceInternal mShortcutServiceInternal;

    @Captor private ArgumentCaptor<ShortcutQuery> mShortcutQueryCaptor;

    NotificationRecord mNr;

    ShortcutHelper mShortcutHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mShortcutHelper = new ShortcutHelper(
                mLauncherApps, mShortcutListener, mShortcutServiceInternal, mUserManager);
        when(mUserManager.isUserUnlocked(any(UserHandle.class))).thenReturn(true);

        mNr = setUpNotificationRecord(SHORTCUT_ID, PKG, UserHandle.of(UserHandle.USER_SYSTEM));
    }

    private NotificationRecord setUpNotificationRecord(String shortcutId,
            String pkg,
            UserHandle user) {
        ShortcutInfo shortcutInfo = mock(ShortcutInfo.class);
        when(shortcutInfo.getId()).thenReturn(shortcutId);
        when(shortcutInfo.getUserHandle()).thenReturn(user);
        when(shortcutInfo.isLongLived()).thenReturn(true);

        Notification notification = new Notification.Builder(getContext())
                .setContentTitle("title")
                .setShortcutId(shortcutId)
                .setBubbleMetadata(new Notification.BubbleMetadata.Builder(shortcutId).build())
                .build();

        StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, 0, null,
                1000, 2000, notification, user, null, System.currentTimeMillis());
        NotificationRecord record = new NotificationRecord(mContext, sbn,
                mock(NotificationChannel.class));
        record.setShortcutInfo(shortcutInfo);
        return record;
    }

    private LauncherApps.ShortcutChangeCallback addShortcutBubbleAndVerifyListener(
            NotificationRecord record) {
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(record, false /* removed */);

        ArgumentCaptor<LauncherApps.ShortcutChangeCallback> launcherAppsCallback =
                ArgumentCaptor.forClass(LauncherApps.ShortcutChangeCallback.class);

        verify(mShortcutServiceInternal, times(1)).addShortcutChangeCallback(
                launcherAppsCallback.capture());
        return launcherAppsCallback.getValue();
    }

    @Test
    public void testBubbleAdded_listenedAdded() {
        addShortcutBubbleAndVerifyListener(mNr);
    }

    @Test
    public void testListenerNotifiedOnShortcutRemoved() {
        LauncherApps.ShortcutChangeCallback callback = addShortcutBubbleAndVerifyListener(mNr);

        List<ShortcutInfo> removedShortcuts = new ArrayList<>();
        removedShortcuts.add(mNr.getShortcutInfo());

        callback.onShortcutsRemoved(PKG, removedShortcuts, mNr.getUser());
        verify(mShortcutListener).onShortcutRemoved(mNr.getKey());
    }

    @Test
    public void testListenerNotNotified_notMatchingPackage() {
        LauncherApps.ShortcutChangeCallback callback = addShortcutBubbleAndVerifyListener(mNr);

        List<ShortcutInfo> removedShortcuts = new ArrayList<>();
        removedShortcuts.add(mNr.getShortcutInfo());

        callback.onShortcutsRemoved("differentPackage", removedShortcuts, mNr.getUser());
        verify(mShortcutListener, never()).onShortcutRemoved(anyString());
    }

    @Test
    public void testListenerNotNotified_notMatchingUser() {
        LauncherApps.ShortcutChangeCallback callback = addShortcutBubbleAndVerifyListener(mNr);

        List<ShortcutInfo> removedShortcuts = new ArrayList<>();
        removedShortcuts.add(mNr.getShortcutInfo());

        callback.onShortcutsRemoved(PKG, removedShortcuts, UserHandle.of(10));
        verify(mShortcutListener, never()).onShortcutRemoved(anyString());
    }

    @Test
    public void testListenerNotifiedDifferentUser() {
        LauncherApps.ShortcutChangeCallback callback = addShortcutBubbleAndVerifyListener(mNr);
        NotificationRecord diffUserRecord = setUpNotificationRecord(SHORTCUT_ID, PKG,
                UserHandle.of(10));
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(diffUserRecord,
                false /* removed */);

        List<ShortcutInfo> removedShortcuts = new ArrayList<>();
        removedShortcuts.add(mNr.getShortcutInfo());

        callback.onShortcutsRemoved(PKG, removedShortcuts, mNr.getUser());
        verify(mShortcutListener).onShortcutRemoved(mNr.getKey());

        reset(mShortcutListener);
        removedShortcuts.clear();
        removedShortcuts.add(diffUserRecord.getShortcutInfo());

        callback.onShortcutsRemoved(PKG, removedShortcuts, diffUserRecord.getUser());
        verify(mShortcutListener).onShortcutRemoved(diffUserRecord.getKey());
    }


    @Test
    public void testBubbleRemoved_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener(mNr);

        // Then remove the notif
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(mNr,
                true /* removed */);

        verify(mShortcutServiceInternal, times(1)).removeShortcutChangeCallback(any());
    }

    @Test
    public void testBubbleNoLongerHasBubbleMetadata_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener(mNr);

        // Then make it not a bubble
        mNr.getNotification().setBubbleMetadata(null);
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(mNr,
                false /* removed */);

        verify(mShortcutServiceInternal, times(1)).removeShortcutChangeCallback(any());
    }

    @Test
    public void testBubbleNoLongerHasShortcutId_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener(mNr);

        // Clear out shortcutId
        mNr.getNotification().setBubbleMetadata(new Notification.BubbleMetadata.Builder(
                mock(PendingIntent.class), mock(Icon.class)).build());
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(mNr,
                false /* removed */);

        verify(mShortcutServiceInternal, times(1)).removeShortcutChangeCallback(any());
    }

    @Test
    public void testNotifNoLongerHasShortcut_listenerRemoved() {
        // First set it up to listen
        addShortcutBubbleAndVerifyListener(mNr);

        NotificationRecord record1 = setUpNotificationRecord(SHORTCUT_ID, PKG,
                UserHandle.of(UserHandle.USER_SYSTEM));
        NotificationRecord record2 = setUpNotificationRecord(SHORTCUT_ID, PKG,
                UserHandle.of(UserHandle.USER_SYSTEM));
        NotificationRecord record3 = setUpNotificationRecord(SHORTCUT_ID, PKG,
                UserHandle.of(UserHandle.USER_SYSTEM));

        mShortcutHelper.maybeListenForShortcutChangesForBubbles(record1,
                false /* removed */);

        mShortcutHelper.maybeListenForShortcutChangesForBubbles(record2,
                false /* removed */);

        mShortcutHelper.maybeListenForShortcutChangesForBubbles(record3,
                false /* removed */);

        // Clear out shortcutId of the bubble in the middle, to double-check that we don't hit a
        // concurrent modification exception (removing the last bubble would sidestep that check).
        record2.setShortcutInfo(null);
        mShortcutHelper.maybeListenForShortcutChangesForBubbles(record2,
                false /* removed */);

        verify(mShortcutServiceInternal, times(1)).removeShortcutChangeCallback(any());
    }

    @Test
    public void testOnShortcutsChanged_listenerRemoved() {
        // First set it up to listen
        LauncherApps.ShortcutChangeCallback callback = addShortcutBubbleAndVerifyListener(mNr);

        // App shortcuts are removed:
        List<ShortcutInfo> removedShortcuts = new ArrayList<>();
        removedShortcuts.add(mNr.getShortcutInfo());
        callback.onShortcutsRemoved(PKG, removedShortcuts,  mNr.getUser());

        verify(mShortcutServiceInternal, times(1)).removeShortcutChangeCallback(any());
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
