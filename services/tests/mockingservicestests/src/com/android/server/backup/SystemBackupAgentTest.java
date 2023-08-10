/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;
import android.app.backup.BackupHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SystemBackupAgentTest {
    private static final int NON_SYSTEM_USER_ID = 10;

    private TestableSystemBackupAgent mSystemBackupAgent;

    @Mock
    private Context mContextMock;
    @Mock
    private UserManager mUserManagerMock;
    @Mock
    private PackageManager mPackageManagerMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSystemBackupAgent = new TestableSystemBackupAgent();
        when(mContextMock.getSystemService(UserManager.class)).thenReturn(mUserManagerMock);
        when(mPackageManagerMock.hasSystemFeature(
                PackageManager.FEATURE_SLICES_DISABLED)).thenReturn(false);
    }

    @Test
    public void onCreate_systemUser_addsAllHelpers() {
        UserHandle userHandle = new UserHandle(UserHandle.USER_SYSTEM);
        when(mUserManagerMock.isProfile()).thenReturn(false);

        mSystemBackupAgent.onCreate(userHandle, /* backupDestination= */ 0);

        assertThat(mSystemBackupAgent.mAddedHelpers)
                .containsExactly(
                        "account_sync_settings",
                        "preferred_activities",
                        "notifications",
                        "permissions",
                        "usage_stats",
                        "shortcut_manager",
                        "account_manager",
                        "slices",
                        "people",
                        "app_locales",
                        "app_gender",
                        "companion");
    }

    @Test
    public void onCreate_systemUser_slicesDisabled_addsAllNonSlicesHelpers() {
        UserHandle userHandle = new UserHandle(UserHandle.USER_SYSTEM);
        when(mUserManagerMock.isProfile()).thenReturn(false);
        when(mPackageManagerMock.hasSystemFeature(
                PackageManager.FEATURE_SLICES_DISABLED)).thenReturn(true);

        mSystemBackupAgent.onCreate(userHandle, /* backupDestination= */ 0);

        assertThat(mSystemBackupAgent.mAddedHelpers)
                .containsExactly(
                        "account_sync_settings",
                        "preferred_activities",
                        "notifications",
                        "permissions",
                        "usage_stats",
                        "shortcut_manager",
                        "account_manager",
                        "people",
                        "app_locales",
                        "app_gender",
                        "companion");
    }

    @Test
    public void onCreate_profileUser_addsProfileEligibleHelpers() {
        UserHandle userHandle = new UserHandle(NON_SYSTEM_USER_ID);
        when(mUserManagerMock.isProfile()).thenReturn(true);

        mSystemBackupAgent.onCreate(userHandle, /* backupDestination= */ 0);

        assertThat(mSystemBackupAgent.mAddedHelpers)
                .containsExactly(
                        "account_sync_settings",
                        "notifications",
                        "permissions",
                        "app_locales",
                        "companion");
    }

    @Test
    public void onCreate_nonSystemUser_addsNonSystemEligibleHelpers() {
        UserHandle userHandle = new UserHandle(NON_SYSTEM_USER_ID);
        when(mUserManagerMock.isProfile()).thenReturn(false);

        mSystemBackupAgent.onCreate(userHandle, /* backupDestination= */ 0);

        assertThat(mSystemBackupAgent.mAddedHelpers)
                .containsExactly(
                        "account_sync_settings",
                        "preferred_activities",
                        "notifications",
                        "permissions",
                        "app_locales",
                        "account_manager",
                        "usage_stats",
                        "shortcut_manager",
                        "companion");
    }

    private class TestableSystemBackupAgent extends SystemBackupAgent {
        final Set<String> mAddedHelpers = new ArraySet<>();

        @Override
        public void addHelper(String keyPrefix, BackupHelper helper) {
            mAddedHelpers.add(keyPrefix);
        }

        @Override
        public Context createContextAsUser(UserHandle user, @CreatePackageOptions int flags) {
            return mContextMock;
        }

        @Override
        public Object getSystemService(@ServiceName @NonNull String name) {
            return null;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManagerMock;
        }
    }
}
