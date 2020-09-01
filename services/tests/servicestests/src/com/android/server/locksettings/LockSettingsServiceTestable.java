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
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.authsecret.V1_0.IAuthSecret;
import android.os.Handler;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManagerInternal;
import android.os.storage.IStorageManager;
import android.security.KeyStore;
import android.security.keystore.KeyPermanentlyInvalidatedException;

import com.android.internal.widget.LockscreenCredential;
import com.android.server.ServiceThread;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;

import java.io.FileNotFoundException;

public class LockSettingsServiceTestable extends LockSettingsService {

    private static class MockInjector extends LockSettingsService.Injector {

        private LockSettingsStorage mLockSettingsStorage;
        private KeyStore mKeyStore;
        private IActivityManager mActivityManager;
        private IStorageManager mStorageManager;
        private SyntheticPasswordManager mSpManager;
        private FakeGsiService mGsiService;
        private RecoverableKeyStoreManager mRecoverableKeyStoreManager;
        private UserManagerInternal mUserManagerInternal;
        private DeviceStateCache mDeviceStateCache;
        private FakeSettings mSettings;

        public MockInjector(Context context, LockSettingsStorage storage, KeyStore keyStore,
                IActivityManager activityManager,
                IStorageManager storageManager, SyntheticPasswordManager spManager,
                FakeGsiService gsiService, RecoverableKeyStoreManager recoverableKeyStoreManager,
                UserManagerInternal userManagerInternal, DeviceStateCache deviceStateCache,
                FakeSettings settings) {
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
            mSettings = settings;
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
        public int settingsGlobalGetInt(ContentResolver contentResolver, String keyName,
                int defaultValue) {
            return mSettings.globalGetInt(keyName);
        }

        @Override
        public int settingsSecureGetInt(ContentResolver contentResolver, String keyName,
                int defaultValue, int userId) {
            return mSettings.secureGetInt(contentResolver, keyName, defaultValue, userId);
        }

        @Override
        public UserManagerInternal getUserManagerInternal() {
            return mUserManagerInternal;
        }

        @Override
        public boolean hasEnrolledBiometrics(int userId) {
            return false;
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
        public RecoverableKeyStoreManager getRecoverableKeyStoreManager(KeyStore keyStore) {
            return mRecoverableKeyStoreManager;
        }

        @Override
        public ManagedProfilePasswordCache getManagedProfilePasswordCache() {
            return mock(ManagedProfilePasswordCache.class);
        }

    }

    public MockInjector mInjector;

    protected LockSettingsServiceTestable(Context context,
            LockSettingsStorage storage, FakeGateKeeperService gatekeeper, KeyStore keystore,
            IStorageManager storageManager, IActivityManager mActivityManager,
            SyntheticPasswordManager spManager, IAuthSecret authSecretService,
            FakeGsiService gsiService, RecoverableKeyStoreManager recoverableKeyStoreManager,
            UserManagerInternal userManagerInternal, DeviceStateCache deviceStateCache,
            FakeSettings settings) {
        super(new MockInjector(context, storage, keystore, mActivityManager,
                storageManager, spManager, gsiService,
                recoverableKeyStoreManager, userManagerInternal, deviceStateCache, settings));
        mGateKeeperService = gatekeeper;
        mAuthSecretService = authSecretService;
    }

    @Override
    protected void tieProfileLockToParent(int userId, LockscreenCredential password) {
        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(password, 0);
        mStorage.writeChildProfileLock(userId, parcel.marshall());
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
}
