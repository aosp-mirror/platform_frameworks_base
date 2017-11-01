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
 * limitations under the License
 */

package com.android.server.locksettings;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.pm.UserInfo;
import android.os.FileUtils;
import android.os.IProgressListener;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.security.KeyStore;
import android.test.AndroidTestCase;

import com.android.internal.widget.LockPatternUtils;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;


public class BaseLockSettingsServiceTests extends AndroidTestCase {
    protected static final int PRIMARY_USER_ID = 0;
    protected static final int MANAGED_PROFILE_USER_ID = 12;
    protected static final int TURNED_OFF_PROFILE_USER_ID = 17;
    protected static final int SECONDARY_USER_ID = 20;

    private static final UserInfo PRIMARY_USER_INFO = new UserInfo(PRIMARY_USER_ID, null, null,
            UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY);
    private static final UserInfo SECONDARY_USER_INFO = new UserInfo(SECONDARY_USER_ID, null, null,
            UserInfo.FLAG_INITIALIZED);

    private ArrayList<UserInfo> mPrimaryUserProfiles = new ArrayList<>();

    LockSettingsService mService;

    MockLockSettingsContext mContext;
    LockSettingsStorageTestable mStorage;

    LockPatternUtils mLockPatternUtils;
    MockGateKeeperService mGateKeeperService;
    NotificationManager mNotificationManager;
    UserManager mUserManager;
    MockStorageManager mStorageManager;
    IActivityManager mActivityManager;
    DevicePolicyManager mDevicePolicyManager;
    KeyStore mKeyStore;
    MockSyntheticPasswordManager mSpManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mLockPatternUtils = mock(LockPatternUtils.class);
        mGateKeeperService = new MockGateKeeperService();
        mNotificationManager = mock(NotificationManager.class);
        mUserManager = mock(UserManager.class);
        mStorageManager = new MockStorageManager();
        mActivityManager = mock(IActivityManager.class);
        mDevicePolicyManager = mock(DevicePolicyManager.class);

        mContext = new MockLockSettingsContext(getContext(), mUserManager, mNotificationManager,
                mDevicePolicyManager, mock(StorageManager.class));
        mStorage = new LockSettingsStorageTestable(mContext,
                new File(getContext().getFilesDir(), "locksettings"));
        File storageDir = mStorage.mStorageDir;
        if (storageDir.exists()) {
            FileUtils.deleteContents(storageDir);
        } else {
            storageDir.mkdirs();
        }

        mSpManager = new MockSyntheticPasswordManager(mStorage, mGateKeeperService, mUserManager);
        mService = new LockSettingsServiceTestable(mContext, mLockPatternUtils,
                mStorage, mGateKeeperService, mKeyStore, mStorageManager, mActivityManager,
                mSpManager);
        when(mUserManager.getUserInfo(eq(PRIMARY_USER_ID))).thenReturn(PRIMARY_USER_INFO);
        mPrimaryUserProfiles.add(PRIMARY_USER_INFO);
        installChildProfile(MANAGED_PROFILE_USER_ID);
        installAndTurnOffChildProfile(TURNED_OFF_PROFILE_USER_ID);
        when(mUserManager.getUsers(anyBoolean())).thenReturn(mPrimaryUserProfiles);
        when(mUserManager.getProfiles(eq(PRIMARY_USER_ID))).thenReturn(mPrimaryUserProfiles);
        when(mUserManager.getUserInfo(eq(SECONDARY_USER_ID))).thenReturn(SECONDARY_USER_INFO);

        when(mActivityManager.unlockUser(anyInt(), any(), any(), any())).thenAnswer(
                new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                mStorageManager.unlockUser((int)args[0], (byte[])args[2],
                        (IProgressListener) args[3]);
                return true;
            }
        });

        when(mLockPatternUtils.getLockSettings()).thenReturn(mService);

        // Adding a fake Device Owner app which will enable escrow token support in LSS.
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser()).thenReturn(
                new ComponentName("com.dummy.package", ".FakeDeviceOwner"));
    }

    private UserInfo installChildProfile(int profileId) {
        final UserInfo userInfo = new UserInfo(
            profileId, null, null, UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_MANAGED_PROFILE);
        mPrimaryUserProfiles.add(userInfo);
        when(mUserManager.getUserInfo(eq(profileId))).thenReturn(userInfo);
        when(mUserManager.getProfileParent(eq(profileId))).thenReturn(PRIMARY_USER_INFO);
        when(mUserManager.isUserRunning(eq(profileId))).thenReturn(true);
        when(mUserManager.isUserUnlocked(eq(profileId))).thenReturn(true);
        return userInfo;
    }

    private UserInfo installAndTurnOffChildProfile(int profileId) {
        final UserInfo userInfo = installChildProfile(profileId);
        userInfo.flags |= UserInfo.FLAG_QUIET_MODE;
        when(mUserManager.isUserRunning(eq(profileId))).thenReturn(false);
        when(mUserManager.isUserUnlocked(eq(profileId))).thenReturn(false);
        return userInfo;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mStorage.closeDatabase();
        File db = getContext().getDatabasePath("locksettings.db");
        assertTrue(!db.exists() || db.delete());

        File storageDir = mStorage.mStorageDir;
        assertTrue(FileUtils.deleteContents(storageDir));
    }

    protected static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual));
    }

    protected static void assertArrayNotSame(byte[] expected, byte[] actual) {
        assertFalse(Arrays.equals(expected, actual));
    }
}
