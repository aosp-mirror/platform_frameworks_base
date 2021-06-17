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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.DeviceStateCache;
import android.app.trust.TrustManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.authsecret.V1_0.IAuthSecret;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.FileUtils;
import android.os.IProgressListener;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.security.KeyStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.LockscreenCredential;
import com.android.server.LocalServices;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public abstract class BaseLockSettingsServiceTests {
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
    LockSettingsInternal mLocalService;

    MockLockSettingsContext mContext;
    LockSettingsStorageTestable mStorage;

    FakeGateKeeperService mGateKeeperService;
    NotificationManager mNotificationManager;
    UserManager mUserManager;
    FakeStorageManager mStorageManager;
    IActivityManager mActivityManager;
    DevicePolicyManager mDevicePolicyManager;
    DevicePolicyManagerInternal mDevicePolicyManagerInternal;
    KeyStore mKeyStore;
    MockSyntheticPasswordManager mSpManager;
    IAuthSecret mAuthSecretService;
    WindowManagerInternal mMockWindowManager;
    FakeGsiService mGsiService;
    PasswordSlotManagerTestable mPasswordSlotManager;
    RecoverableKeyStoreManager mRecoverableKeyStoreManager;
    UserManagerInternal mUserManagerInternal;
    DeviceStateCache mDeviceStateCache;
    FingerprintManager mFingerprintManager;
    FaceManager mFaceManager;
    PackageManager mPackageManager;
    FakeSettings mSettings;

    @Before
    public void setUp_baseServices() throws Exception {
        mGateKeeperService = new FakeGateKeeperService();
        mNotificationManager = mock(NotificationManager.class);
        mUserManager = mock(UserManager.class);
        mStorageManager = new FakeStorageManager();
        mActivityManager = mock(IActivityManager.class);
        mDevicePolicyManager = mock(DevicePolicyManager.class);
        mDevicePolicyManagerInternal = mock(DevicePolicyManagerInternal.class);
        mMockWindowManager = mock(WindowManagerInternal.class);
        mGsiService = new FakeGsiService();
        mPasswordSlotManager = new PasswordSlotManagerTestable();
        mRecoverableKeyStoreManager = mock(RecoverableKeyStoreManager.class);
        mUserManagerInternal = mock(UserManagerInternal.class);
        mDeviceStateCache = mock(DeviceStateCache.class);
        mFingerprintManager = mock(FingerprintManager.class);
        mFaceManager = mock(FaceManager.class);
        mPackageManager = mock(PackageManager.class);
        mSettings = new FakeSettings();

        LocalServices.removeServiceForTest(LockSettingsInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class, mDevicePolicyManagerInternal);
        LocalServices.addService(WindowManagerInternal.class, mMockWindowManager);

        mContext = new MockLockSettingsContext(InstrumentationRegistry.getContext(), mUserManager,
                mNotificationManager, mDevicePolicyManager, mock(StorageManager.class),
                mock(TrustManager.class), mock(KeyguardManager.class), mFingerprintManager,
                mFaceManager, mPackageManager);
        mStorage = new LockSettingsStorageTestable(mContext,
                new File(InstrumentationRegistry.getContext().getFilesDir(), "locksettings"));
        File storageDir = mStorage.mStorageDir;
        if (storageDir.exists()) {
            FileUtils.deleteContents(storageDir);
        } else {
            storageDir.mkdirs();
        }

        mSpManager = new MockSyntheticPasswordManager(mContext, mStorage, mGateKeeperService,
                mUserManager, mPasswordSlotManager);
        mAuthSecretService = mock(IAuthSecret.class);
        mService = new LockSettingsServiceTestable(mContext, mStorage,
                mGateKeeperService, mKeyStore, setUpStorageManagerMock(), mActivityManager,
                mSpManager, mAuthSecretService, mGsiService, mRecoverableKeyStoreManager,
                mUserManagerInternal, mDeviceStateCache, mSettings);
        mService.mHasSecureLockScreen = true;
        when(mUserManager.getUserInfo(eq(PRIMARY_USER_ID))).thenReturn(PRIMARY_USER_INFO);
        mPrimaryUserProfiles.add(PRIMARY_USER_INFO);
        installChildProfile(MANAGED_PROFILE_USER_ID);
        installAndTurnOffChildProfile(TURNED_OFF_PROFILE_USER_ID);
        for (UserInfo profile : mPrimaryUserProfiles) {
            when(mUserManager.getProfiles(eq(profile.id))).thenReturn(mPrimaryUserProfiles);
        }
        when(mUserManager.getUserInfo(eq(SECONDARY_USER_ID))).thenReturn(SECONDARY_USER_INFO);

        final ArrayList<UserInfo> allUsers = new ArrayList<>(mPrimaryUserProfiles);
        allUsers.add(SECONDARY_USER_INFO);
        when(mUserManager.getUsers()).thenReturn(allUsers);

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

        // Adding a fake Device Owner app which will enable escrow token support in LSS.
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser()).thenReturn(
                new ComponentName("com.dummy.package", ".FakeDeviceOwner"));
        when(mUserManagerInternal.isDeviceManaged()).thenReturn(true);
        when(mDeviceStateCache.isDeviceProvisioned()).thenReturn(true);
        mockBiometricsHardwareFingerprintsAndTemplates(PRIMARY_USER_ID);
        mockBiometricsHardwareFingerprintsAndTemplates(MANAGED_PROFILE_USER_ID);

        mSettings.setDeviceProvisioned(true);
        mLocalService = LocalServices.getService(LockSettingsInternal.class);
    }

    private UserInfo installChildProfile(int profileId) {
        final UserInfo userInfo = new UserInfo(
            profileId, null, null, UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_MANAGED_PROFILE);
        userInfo.profileGroupId = PRIMARY_USER_ID;
        mPrimaryUserProfiles.add(userInfo);
        when(mUserManager.getUserInfo(eq(profileId))).thenReturn(userInfo);
        when(mUserManager.getProfileParent(eq(profileId))).thenReturn(PRIMARY_USER_INFO);
        when(mUserManager.isUserRunning(eq(profileId))).thenReturn(true);
        when(mUserManager.isUserUnlocked(eq(profileId))).thenReturn(true);
        when(mUserManagerInternal.isUserManaged(eq(profileId))).thenReturn(true);
        return userInfo;
    }

    private UserInfo installAndTurnOffChildProfile(int profileId) {
        final UserInfo userInfo = installChildProfile(profileId);
        userInfo.flags |= UserInfo.FLAG_QUIET_MODE;
        when(mUserManager.isUserRunning(eq(profileId))).thenReturn(false);
        when(mUserManager.isUserUnlocked(eq(profileId))).thenReturn(false);
        return userInfo;
    }

    private IStorageManager setUpStorageManagerMock() throws RemoteException {
        final IStorageManager sm = mock(IStorageManager.class);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                mStorageManager.addUserKeyAuth((int) args[0] /* userId */,
                        (int) args[1] /* serialNumber */,
                        (byte[]) args[2] /* token */,
                        (byte[]) args[3] /* secret */);
                return null;
            }
        }).when(sm).addUserKeyAuth(anyInt(), anyInt(), any(), any());

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                mStorageManager.clearUserKeyAuth((int) args[0] /* userId */,
                        (int) args[1] /* serialNumber */,
                        (byte[]) args[2] /* token */,
                        (byte[]) args[3] /* secret */);
                return null;
            }
        }).when(sm).clearUserKeyAuth(anyInt(), anyInt(), any(), any());

        doAnswer(
                new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                mStorageManager.fixateNewestUserKeyAuth((int) args[0] /* userId */);
                return null;
            }
        }).when(sm).fixateNewestUserKeyAuth(anyInt());
        return sm;
    }

    private void mockBiometricsHardwareFingerprintsAndTemplates(int userId) {
        // Hardware must be detected and fingerprints must be enrolled
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)).thenReturn(true);
        when(mFingerprintManager.isHardwareDetected()).thenReturn(true);
        when(mFingerprintManager.hasEnrolledFingerprints(userId)).thenReturn(true);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                FingerprintManager.RemovalCallback callback =
                        (FingerprintManager.RemovalCallback) invocation.getArguments()[1];
                callback.onRemovalSucceeded(null, 0);
                return null;
            }
        }).when(mFingerprintManager).removeAll(eq(userId), any());


        // Hardware must be detected and templates must be enrolled
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE)).thenReturn(true);
        when(mFaceManager.isHardwareDetected()).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(userId)).thenReturn(true);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                FaceManager.RemovalCallback callback =
                        (FaceManager.RemovalCallback) invocation.getArguments()[1];
                callback.onRemovalSucceeded(null, 0);
                return null;
            }
        }).when(mFaceManager).removeAll(eq(userId), any());
    }

    @After
    public void tearDown_baseServices() throws Exception {
        mStorage.closeDatabase();
        File db = InstrumentationRegistry.getContext().getDatabasePath("locksettings.db");
        assertTrue(!db.exists() || db.delete());

        File storageDir = mStorage.mStorageDir;
        assertTrue(FileUtils.deleteContents(storageDir));

        mPasswordSlotManager.cleanup();
    }

    protected void flushHandlerTasks() {
        mService.mHandler.runWithScissors(() -> { }, 0 /*now*/); // Flush runnables on handler
    }

    protected void assertNotEquals(long expected, long actual) {
        assertTrue(expected != actual);
    }

    protected static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual));
    }

    protected static void assertArrayNotEquals(byte[] expected, byte[] actual) {
        assertFalse(Arrays.equals(expected, actual));
    }

    protected LockscreenCredential newPassword(String password) {
        return LockscreenCredential.createPasswordOrNone(password);
    }

    protected LockscreenCredential newPin(String pin) {
        return LockscreenCredential.createPinOrNone(pin);
    }

    protected LockscreenCredential newPattern(String pattern) {
        return LockscreenCredential.createPattern(LockPatternUtils.byteArrayToPattern(
                pattern.getBytes()));
    }

    protected LockscreenCredential nonePassword() {
        return LockscreenCredential.createNone();
    }

}
