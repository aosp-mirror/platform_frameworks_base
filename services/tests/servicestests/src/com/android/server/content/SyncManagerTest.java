/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.content;

import static android.content.pm.UserProperties.INHERIT_DEVICE_POLICY_FROM_PARENT;
import static android.content.pm.UserProperties.SHOW_IN_LAUNCHER_WITH_PARENT;
import static android.content.pm.UserProperties.SHOW_IN_SETTINGS_WITH_PARENT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.accounts.AccountManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.job.JobSchedulerInternal;

import junit.framework.TestCase;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Tests for SyncManager.
 *
 * bit FrameworksServicesTests:com.android.server.content.SyncManagerTest
 */
@SmallTest
public class SyncManagerTest extends TestCase {

    final String KEY_1 = "key_1";
    final String KEY_2 = "key_2";

    private SyncManager mSyncManager;
    private Context mContext;

    @Mock
    private UserManager mUserManager;
    @Mock
    private AccountManagerInternal mAccountManagerInternal;
    @Mock
    private JobSchedulerInternal mJobSchedulerInternal;

    private class SyncManagerWithMockedServices extends SyncManager {

        @Override
        protected AccountManagerInternal getAccountManagerInternal() {
            return mAccountManagerInternal;
        }

        @Override
        protected JobSchedulerInternal getJobSchedulerInternal() {
            return mJobSchedulerInternal;
        }

        private SyncManagerWithMockedServices(Context context, boolean factoryTest) {
            super(context, factoryTest);
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        doNothing().when(mAccountManagerInternal).addOnAppPermissionChangeListener(any());
        when(mJobSchedulerInternal.getSystemScheduledPendingJobs()).thenReturn(new ArrayList<>());
        mSyncManager = spy(new SyncManagerWithMockedServices(mContext, true));
    }

    public void testSyncExtrasEquals_WithNull() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, null);

        assertTrue("Null extra not properly compared between bundles.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testSyncExtrasEqualsBigger_WithNull() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, null);

        b1.putString(KEY_2, "bla");
        b2.putString(KEY_2, "bla");

        assertTrue("Extras not properly compared between bundles.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testSyncExtrasEqualsFails_WithNull() throws Exception {
        Bundle b1 = new Bundle();
        b1.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        b1.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

        Bundle b2 = new Bundle();
        b2.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        b2.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        b2.putString(null, "Hello NPE!");
        b2.putString("a", "b");
        b2.putString("c", "d");
        b2.putString("e", "f");

        assertFalse("Extras not properly compared between bundles.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testSyncExtrasEqualsFails_differentValues() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, null);

        b1.putString(KEY_2, "bla");
        b2.putString(KEY_2, "ble");  // different key

        assertFalse("Extras considered equal when they are different.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testSyncExtrasEqualsFails_differentNulls() throws Exception {
        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();

        b1.putString(KEY_1, null);
        b2.putString(KEY_1, "bla");  // different key

        b1.putString(KEY_2, "ble");
        b2.putString(KEY_2, "ble");

        assertFalse("Extras considered equal when they are different.",
                SyncManager.syncExtrasEquals(b1, b2, false /* don't care about system extras */));
    }

    public void testFormatDurationHMS() {
        checkFormatDurationHMS("0s", 0, 0, 0, 0);
        checkFormatDurationHMS("1s", 0, 0, 0, 1);
        checkFormatDurationHMS("9s", 0, 0, 0, 9);
        checkFormatDurationHMS("10s", 0, 0, 0, 10);
        checkFormatDurationHMS("59s", 0, 0, 0, 59);
        checkFormatDurationHMS("1m00s", 0, 0, 1, 0);
        checkFormatDurationHMS("1m01s", 0, 0, 1, 1);
        checkFormatDurationHMS("1m09s", 0, 0, 1, 9);
        checkFormatDurationHMS("1m10s", 0, 0, 1, 10);
        checkFormatDurationHMS("1m59s", 0, 0, 1, 59);
        checkFormatDurationHMS("1h00m00s", 0, 1, 0, 0);
        checkFormatDurationHMS("1h00m01s", 0, 1, 0, 1);
        checkFormatDurationHMS("1h01m01s", 0, 1, 1, 1);
        checkFormatDurationHMS("1h09m10s", 0, 1, 9, 10);
        checkFormatDurationHMS("1h10m59s", 0, 1, 10, 59);
        checkFormatDurationHMS("1h59m00s", 0, 1, 59, 0);

        checkFormatDurationHMS("1d00h00m00s", 1, 0, 0, 0);
        checkFormatDurationHMS("1d00h00m00s", 1, 0, 0, 0);
        checkFormatDurationHMS("1d01h00m00s", 1, 1, 0, 0);
        checkFormatDurationHMS("1d09h00m00s", 1, 9, 0, 0);
        checkFormatDurationHMS("1d10h00m00s", 1, 10, 0, 0);
        checkFormatDurationHMS("1d23h00m00s", 1, 23, 0, 0);
        checkFormatDurationHMS("123d01h00m00s", 123, 1, 0, 0);

        final StringBuilder sb = new StringBuilder();
        assertEquals("-1m01s", SyncManager.formatDurationHMS(sb, -61000L).toString());
    }

    private void checkFormatDurationHMS(String expected,
            int d, int h, int m, int s) {
        final long time = (d * 24 * 3600) + (h * 3600) + (m * 60) + s;

        final StringBuilder sb = new StringBuilder();
        assertEquals(expected, SyncManager.formatDurationHMS(sb, time * 1000).toString());
    }

    private UserInfo createUserInfo(String name, int id, int groupId, int flags) {
        final UserInfo ui = new UserInfo(id, name, flags | UserInfo.FLAG_INITIALIZED);
        ui.profileGroupId = groupId;
        return ui;
    }

    @NotNull
    private UserProperties getCloneUserProperties() {
        return new UserProperties.Builder()
                .setStartWithParent(true)
                .setShowInLauncher(SHOW_IN_LAUNCHER_WITH_PARENT)
                .setShowInSettings(SHOW_IN_SETTINGS_WITH_PARENT)
                .setUseParentsContacts(true)
                .setInheritDevicePolicy(INHERIT_DEVICE_POLICY_FROM_PARENT)
                .build();
    }

    private void mockUserProperties(UserInfo primaryUserInfo, UserInfo cloneUserInfo) {
        UserProperties cloneUserProperties = getCloneUserProperties();
        when(mUserManager.getUserProperties(cloneUserInfo.getUserHandle()))
                .thenReturn(cloneUserProperties);
        // Set default user properties for primary user
        when(mUserManager.getUserProperties(primaryUserInfo.getUserHandle()))
                .thenReturn(new UserProperties.Builder().build());
    }

    public void testShouldDisableSync() {
        doReturn(true).when(mSyncManager).isContactSharingAllowedForCloneProfile();
        UserInfo primaryUserInfo = createUserInfo("primary", 0 /* id */, 0 /* groupId */,
                UserInfo.FLAG_PRIMARY | UserInfo.FLAG_ADMIN);
        UserInfo cloneUserInfo = createUserInfo("clone", 10 /* id */, 0 /* groupId */,
                UserInfo.FLAG_PROFILE);

        mockUserProperties(primaryUserInfo, cloneUserInfo);

        // Clone user accounts must have contact syncs disabled
        assertThat(mSyncManager.shouldDisableSyncForUser(cloneUserInfo,
                ContactsContract.AUTHORITY)).isTrue();
        assertThat(mSyncManager.shouldDisableSyncForUser(primaryUserInfo,
                ContactsContract.AUTHORITY)).isFalse();
    }
}
