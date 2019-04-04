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
import android.content.Context;
import android.hardware.authsecret.V1_0.IAuthSecret;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.storage.IStorageManager;
import android.security.KeyStore;
import android.security.keystore.KeyPermanentlyInvalidatedException;

import com.android.internal.widget.LockPatternUtils;

import java.io.FileNotFoundException;

public class LockSettingsServiceTestable extends LockSettingsService {

    private static class MockInjector extends LockSettingsService.Injector {

        private LockSettingsStorage mLockSettingsStorage;
        private KeyStore mKeyStore;
        private IActivityManager mActivityManager;
        private LockPatternUtils mLockPatternUtils;
        private IStorageManager mStorageManager;
        private SyntheticPasswordManager mSpManager;
        private IAuthSecret mAuthSecretService;

        public MockInjector(Context context, LockSettingsStorage storage, KeyStore keyStore,
                IActivityManager activityManager, LockPatternUtils lockPatternUtils,
                IStorageManager storageManager, SyntheticPasswordManager spManager,
                IAuthSecret authSecretService) {
            super(context);
            mLockSettingsStorage = storage;
            mKeyStore = keyStore;
            mActivityManager = activityManager;
            mLockPatternUtils = lockPatternUtils;
            mStorageManager = storageManager;
            mSpManager = spManager;
        }

        @Override
        public Handler getHandler() {
            return new Handler(Looper.getMainLooper());
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
        public LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
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
        public int binderGetCallingUid() {
            return Process.SYSTEM_UID;
        }
    }

    protected LockSettingsServiceTestable(Context context, LockPatternUtils lockPatternUtils,
            LockSettingsStorage storage, FakeGateKeeperService gatekeeper, KeyStore keystore,
            IStorageManager storageManager, IActivityManager mActivityManager,
            SyntheticPasswordManager spManager, IAuthSecret authSecretService) {
        super(new MockInjector(context, storage, keystore, mActivityManager, lockPatternUtils,
                storageManager, spManager, authSecretService));
        mGateKeeperService = gatekeeper;
        mAuthSecretService = authSecretService;
    }

    @Override
    protected void tieProfileLockToParent(int userId, byte[] password) {
        mStorage.writeChildProfileLock(userId, password);
    }

    @Override
    protected byte[] getDecryptedPasswordForTiedProfile(int userId) throws FileNotFoundException,
            KeyPermanentlyInvalidatedException {
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
        return storedData;
    }

}
