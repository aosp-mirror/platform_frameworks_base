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
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.hardware.authsecret.IAuthSecret;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.FileUtils;
import android.os.IProgressListener;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.security.KeyStore;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.LockscreenCredential;
import com.android.server.LocalServices;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
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
    protected static final int TERTIARY_USER_ID = 21;

    protected UserInfo mPrimaryUserInfo;
    protected UserInfo mSecondaryUserInfo;
    protected UserInfo mTertiaryUserInfo;

    private ArrayList<UserInfo> mPrimaryUserProfiles = new ArrayList<>();

    LockSettingsServiceTestable mService;
    LockSettingsInternal mLocalService;

    MockLockSettingsContext mContext;
    LockSettingsStorageTestable mStorage;

    Resources mResources;
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
    LockSettingsServiceTestable.MockInjector mInjector;
    @Rule
    public FakeSettingsProviderRule mSettingsRule = FakeSettingsProvider.rule();

    @Before
    public void setUp_baseServices() throws Exception {
        mResources = createMockResources();
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

        LocalServices.removeServiceForTest(LockSettingsInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(DevicePolicyManagerInternal.class, mDevicePolicyManagerInternal);
        LocalServices.addService(WindowManagerInternal.class, mMockWindowManager);

        final Context origContext = InstrumentationRegistry.getContext();
        mContext = new MockLockSettingsContext(origContext, mResources,
                mSettingsRule.mockContentResolver(origContext), mUserManager, mNotificationManager,
                mDevicePolicyManager, mock(StorageManager.class), mock(TrustManager.class),
                mock(KeyguardManager.class), mFingerprintManager, mFaceManager, mPackageManager);
        mStorage = new LockSettingsStorageTestable(mContext,
                new File(origContext.getFilesDir(), "locksettings"));
        File storageDir = mStorage.mStorageDir;
        if (storageDir.exists()) {
            FileUtils.deleteContents(storageDir);
        } else {
            storageDir.mkdirs();
        }

        mSpManager = new MockSyntheticPasswordManager(mContext, mStorage, mGateKeeperService,
                mUserManager, mPasswordSlotManager);
        mAuthSecretService = mock(IAuthSecret.class);
        mInjector =
                new LockSettingsServiceTestable.MockInjector(
                        mContext,
                        mStorage,
                        mKeyStore,
                        mActivityManager,
                        setUpStorageManagerMock(),
                        mSpManager,
                        mGsiService,
                        mRecoverableKeyStoreManager,
                        mUserManagerInternal,
                        mDeviceStateCache);
        mService =
                new LockSettingsServiceTestable(mInjector, mGateKeeperService, mAuthSecretService);
        mService.mHasSecureLockScreen = true;
        mPrimaryUserInfo =
                new UserInfo(
                        PRIMARY_USER_ID,
                        null,
                        null,
                        UserInfo.FLAG_INITIALIZED
                                | UserInfo.FLAG_ADMIN
                                | UserInfo.FLAG_PRIMARY
                                | UserInfo.FLAG_MAIN
                                | UserInfo.FLAG_FULL);
        mSecondaryUserInfo =
                new UserInfo(
                        SECONDARY_USER_ID,
                        null,
                        null,
                        UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_FULL);
        mTertiaryUserInfo =
                new UserInfo(
                        TERTIARY_USER_ID,
                        null,
                        null,
                        UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_FULL);

        when(mUserManager.getUserInfo(eq(PRIMARY_USER_ID))).thenReturn(mPrimaryUserInfo);
        when(mUserManagerInternal.getUserInfo(eq(PRIMARY_USER_ID))).thenReturn(mPrimaryUserInfo);
        mPrimaryUserProfiles.add(mPrimaryUserInfo);
        installChildProfile(MANAGED_PROFILE_USER_ID);
        installAndTurnOffChildProfile(TURNED_OFF_PROFILE_USER_ID);
        for (UserInfo profile : mPrimaryUserProfiles) {
            when(mUserManager.getProfiles(eq(profile.id))).thenReturn(mPrimaryUserProfiles);
        }
        when(mUserManager.getUserInfo(eq(SECONDARY_USER_ID))).thenReturn(mSecondaryUserInfo);
        when(mUserManagerInternal.getUserInfo(eq(SECONDARY_USER_ID)))
                .thenReturn(mSecondaryUserInfo);
        when(mUserManager.getUserInfo(eq(TERTIARY_USER_ID))).thenReturn(mTertiaryUserInfo);
        when(mUserManagerInternal.getUserInfo(eq(TERTIARY_USER_ID))).thenReturn(mTertiaryUserInfo);

        final ArrayList<UserInfo> allUsers = new ArrayList<>(mPrimaryUserProfiles);
        allUsers.add(mSecondaryUserInfo);
        allUsers.add(mTertiaryUserInfo);
        when(mUserManager.getUsers()).thenReturn(allUsers);

        when(mActivityManager.unlockUser2(anyInt(), any())).thenAnswer(
            invocation -> {
                Object[] args = invocation.getArguments();
                int userId = (int) args[0];
                IProgressListener listener = (IProgressListener) args[1];
                listener.onStarted(userId, null);
                listener.onFinished(userId, null);
                return true;
            });

        // Adding a fake Device Owner app which will enable escrow token support in LSS.
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser()).thenReturn(
                new ComponentName("com.dummy.package", ".FakeDeviceOwner"));
        when(mUserManagerInternal.isDeviceManaged()).thenReturn(true);
        when(mDeviceStateCache.isUserOrganizationManaged(anyInt())).thenReturn(true);
        when(mDeviceStateCache.isDeviceProvisioned()).thenReturn(true);
        mockBiometricsHardwareFingerprintsAndTemplates(PRIMARY_USER_ID);
        mockBiometricsHardwareFingerprintsAndTemplates(MANAGED_PROFILE_USER_ID);

        setDeviceProvisioned(true);
        mLocalService = LocalServices.getService(LockSettingsInternal.class);
    }

    private Resources createMockResources() {
        Resources res = mock(Resources.class);

        // Set up some default configs, copied from core/res/res/values/config.xml
        when(res.getBoolean(eq(com.android.internal.R.bool.config_disableLockscreenByDefault)))
                .thenReturn(false);
        when(res.getBoolean(
                eq(com.android.internal.R.bool.config_enableCredentialFactoryResetProtection)))
                .thenReturn(true);
        when(res.getBoolean(eq(com.android.internal.R.bool.config_isMainUserPermanentAdmin)))
                .thenReturn(true);
        when(res.getBoolean(eq(com.android.internal.R.bool.config_strongAuthRequiredOnBoot)))
                .thenReturn(true);
        when(res.getBoolean(eq(com.android.internal.R.bool.config_repairModeSupported)))
                .thenReturn(true);
        return res;
    }

    protected void setDeviceProvisioned(boolean provisioned) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, provisioned ? 1 : 0);
    }

    protected void setUserSetupComplete(boolean complete) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, complete ? 1 : 0, UserHandle.USER_SYSTEM);
    }

    protected void setSecureFrpMode(boolean secure) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.SECURE_FRP_MODE, secure ? 1 : 0, UserHandle.USER_SYSTEM);
    }

    private UserInfo installChildProfile(int profileId) {
        final UserInfo userInfo = new UserInfo(
            profileId, null, null, UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_MANAGED_PROFILE);
        userInfo.profileGroupId = PRIMARY_USER_ID;
        mPrimaryUserProfiles.add(userInfo);
        when(mUserManager.getUserInfo(eq(profileId))).thenReturn(userInfo);
        when(mUserManager.getProfileParent(eq(profileId))).thenReturn(mPrimaryUserInfo);
        when(mUserManager.isUserRunning(eq(profileId))).thenReturn(true);
        when(mUserManager.isUserUnlocked(eq(profileId))).thenReturn(true);
        when(mUserManagerInternal.getUserInfo(eq(profileId))).thenReturn(userInfo);
        // TODO(b/258213147): Remove
        when(mUserManagerInternal.isUserManaged(eq(profileId))).thenReturn(true);
        when(mDeviceStateCache.isUserOrganizationManaged(eq(profileId)))
                .thenReturn(true);
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

        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            mStorageManager.unlockCeStorage(/* userId= */ (int) args[0],
                    /* secret= */ (byte[]) args[1]);
            return null;
        }).when(sm).unlockCeStorage(anyInt(), any());

        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            mStorageManager.setCeStorageProtection(/* userId= */ (int) args[0],
                    /* secret= */ (byte[]) args[1]);
            return null;
        }).when(sm).setCeStorageProtection(anyInt(), any());

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
