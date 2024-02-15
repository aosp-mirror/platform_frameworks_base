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
 * limitations under the License
 */

package com.android.server.locksettings;

import static org.mockito.Mockito.mock;

import android.app.IActivityManager;
import android.app.admin.DeviceStateCache;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.authsecret.IAuthSecret;
import android.os.Handler;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.storage.IStorageManager;
import android.security.KeyStore;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.service.gatekeeper.IGateKeeperService;

import com.android.internal.widget.LockscreenCredential;
import com.android.server.ServiceThread;
import com.android.server.locksettings.SyntheticPasswordManager.SyntheticPassword;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.pm.UserManagerInternal;

import java.io.FileNotFoundException;

public class LockSettingsServiceTestable extends LockSettingsService {

    public static class MockInjector extends LockSettingsService.Injector {

        private LockSettingsStorage mLockSettingsStorage;
        private KeyStore mKeyStore;
        private IActivityManager mActivityManager;
        private IStorageManager mStorageManager;
        private SyntheticPasswordManager mSpManager;
        private FakeGsiService mGsiService;
        private RecoverableKeyStoreManager mRecoverableKeyStoreManager;
        private UserManagerInternal mUserManagerInternal;
        private DeviceStateCache mDeviceStateCache;

        public boolean mIsHeadlessSystemUserMode = false;
        public boolean mIsMainUserPermanentAdmin = false;

        public MockInjector(Context context, LockSettingsStorage storage, KeyStore keyStore,
                IActivityManager activityManager,
                IStorageManager storageManager, SyntheticPasswordManager spManager,
                FakeGsiService gsiService, RecoverableKeyStoreManager recoverableKeyStoreManager,
                UserManagerInternal userManagerInternal, DeviceStateCache deviceStateCache) {
            super(context);
            mLockSettingsStorage = storage;
            mKeyStore = keyStore;
            mActivityManager = activityManager;
            mStorageManager = storageManager;
            mSpManager = spManager;
            mGsiService = gsiService;
            mRecoverableKeyStoreManager = recoverableKeyStoreManager;
            mUserManagerInternal = userManagerInternal;
            mDeviceStateCache = deviceStateCache;
        }

        @Override
        public Handler getHandler(ServiceThread handlerThread) {
            return new Handler(handlerThread.getLooper());
        }

        @Override
        public LockSettingsStorage getStorage() {
            return mLockSettingsStorage;
        }

        @Override
        public LockSettingsStrongAuth getStrongAuth() {
            return mock(LockSettingsStrongAuth.class);
        }

        @Override
        public SynchronizedStrongAuthTracker getStrongAuthTracker() {
            return mock(SynchronizedStrongAuthTracker.class);
        }

        @Override
        public IActivityManager getActivityManager() {
            return mActivityManager;
        }

        @Override
        public DeviceStateCache getDeviceStateCache() {
            return mDeviceStateCache;
        }

        @Override
        public KeyStore getKeyStore() {
            return mKeyStore;
        }

        @Override
        public IStorageManager getStorageManager() {
            return mStorageManager;
        }

        @Override
        public SyntheticPasswordManager getSyntheticPasswordManager(LockSettingsStorage storage) {
            return mSpManager;
        }

        @Override
        public UserManagerInternal getUserManagerInternal() {
            return mUserManagerInternal;
        }

        @Override
        public int binderGetCallingUid() {
            return Process.SYSTEM_UID;
        }

        @Override
        public boolean isGsiRunning() {
            return mGsiService.isGsiRunning();
        }

        @Override
        public RecoverableKeyStoreManager getRecoverableKeyStoreManager() {
            return mRecoverableKeyStoreManager;
        }

        @Override
        public UnifiedProfilePasswordCache getUnifiedProfilePasswordCache(
                java.security.KeyStore ks) {
            return mock(UnifiedProfilePasswordCache.class);
        }

        @Override
        public boolean isHeadlessSystemUserMode() {
            return mIsHeadlessSystemUserMode;
        }

        @Override
        public boolean isMainUserPermanentAdmin() {
            return mIsMainUserPermanentAdmin;
        }
    }

    protected LockSettingsServiceTestable(
            LockSettingsService.Injector injector,
            IGateKeeperService gatekeeper,
            IAuthSecret authSecretService) {
        super(injector);
        mGateKeeperService = gatekeeper;
        mAuthSecretService = authSecretService;
    }

    @Override
    protected void tieProfileLockToParent(int profileUserId, int parentUserId,
            LockscreenCredential password) {
        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(password, 0);
        mStorage.writeChildProfileLock(profileUserId, parcel.marshall());
        parcel.recycle();
    }

    @Override
    protected LockscreenCredential getDecryptedPasswordForTiedProfile(int userId)
            throws FileNotFoundException, KeyPermanentlyInvalidatedException {
        byte[] storedData = mStorage.readChildProfileLock(userId);
        if (storedData == null) {
            throw new FileNotFoundException("Child profile lock file not found");
        }
        try {
            if (mGateKeeperService.getSecureUserId(userId) == 0) {
                throw new KeyPermanentlyInvalidatedException();
            }
        } catch (RemoteException e) {
            // shouldn't happen.
        }
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(storedData, 0, storedData.length);
            parcel.setDataPosition(0);
            return (LockscreenCredential) parcel.readParcelable(null);
        } finally {
            parcel.recycle();
        }
    }

    @Override
    void setKeystorePassword(byte[] password, int userHandle) {

    }

    @Override
    void initKeystoreSuperKeys(int userId, SyntheticPassword sp, boolean allowExisting) {
    }

    @Override
    protected boolean isCredentialSharableWithParent(int userId) {
        UserInfo userInfo = mUserManager.getUserInfo(userId);
        return userInfo.isCloneProfile() || userInfo.isManagedProfile();
    }

    void clearAuthSecret() {
        synchronized (mHeadlessAuthSecretLock) {
            mAuthSecret = null;
        }
    }
}
