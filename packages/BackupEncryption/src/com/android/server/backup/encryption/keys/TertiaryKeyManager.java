/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.keys;

import android.annotation.Nullable;
import android.content.Context;
import android.util.Slog;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Optional;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * Gets the correct tertiary key to use during a backup, rotating it if required.
 *
 * <p>Calling any method on this class will count a incremental backup against the app, and the key
 * will be rotated if required.
 */
public class TertiaryKeyManager {

    private static final String TAG = "TertiaryKeyMgr";

    private final TertiaryKeyStore mKeyStore;
    private final TertiaryKeyGenerator mKeyGenerator;
    private final TertiaryKeyRotationScheduler mTertiaryKeyRotationScheduler;
    private final RecoverableKeyStoreSecondaryKey mSecondaryKey;
    private final String mPackageName;

    private boolean mKeyRotated;
    @Nullable private SecretKey mTertiaryKey;

    public TertiaryKeyManager(
            Context context,
            SecureRandom secureRandom,
            TertiaryKeyRotationScheduler tertiaryKeyRotationScheduler,
            RecoverableKeyStoreSecondaryKey secondaryKey,
            String packageName) {
        mSecondaryKey = secondaryKey;
        mPackageName = packageName;
        mKeyGenerator = new TertiaryKeyGenerator(secureRandom);
        mKeyStore = TertiaryKeyStore.newInstance(context, secondaryKey);
        mTertiaryKeyRotationScheduler = tertiaryKeyRotationScheduler;
    }

    /**
     * Returns either the previously used tertiary key, or a new tertiary key if there was no
     * previous key or it needed to be rotated.
     */
    public SecretKey getKey()
            throws InvalidKeyException, IOException, IllegalBlockSizeException,
                    NoSuchPaddingException, NoSuchAlgorithmException,
                    InvalidAlgorithmParameterException {
        init();
        return mTertiaryKey;
    }

    /** Returns the key given by {@link #getKey()} wrapped by the secondary key. */
    public WrappedKeyProto.WrappedKey getWrappedKey()
            throws InvalidKeyException, IOException, IllegalBlockSizeException,
                    NoSuchPaddingException, NoSuchAlgorithmException,
                    InvalidAlgorithmParameterException {
        init();
        return KeyWrapUtils.wrap(mSecondaryKey.getSecretKey(), mTertiaryKey);
    }

    /**
     * Returns {@code true} if a new tertiary key was generated at the start of this session,
     * otherwise {@code false}.
     */
    public boolean wasKeyRotated()
            throws InvalidKeyException, IllegalBlockSizeException, IOException,
                    NoSuchPaddingException, NoSuchAlgorithmException,
                    InvalidAlgorithmParameterException {
        init();
        return mKeyRotated;
    }

    private void init()
            throws IllegalBlockSizeException, InvalidKeyException, IOException,
                    NoSuchAlgorithmException, NoSuchPaddingException,
                    InvalidAlgorithmParameterException {
        if (mTertiaryKey != null) {
            return;
        }

        Optional<SecretKey> key = getExistingKeyIfNotRotated();

        if (!key.isPresent()) {
            Slog.d(TAG, "Generating new tertiary key for " + mPackageName);

            key = Optional.of(mKeyGenerator.generate());
            mKeyRotated = true;
            mTertiaryKeyRotationScheduler.recordKeyRotation(mPackageName);
            mKeyStore.save(mPackageName, key.get());
        }

        mTertiaryKey = key.get();

        mTertiaryKeyRotationScheduler.recordBackup(mPackageName);
    }

    private Optional<SecretKey> getExistingKeyIfNotRotated()
            throws InvalidKeyException, IOException, InvalidAlgorithmParameterException,
                    NoSuchAlgorithmException, NoSuchPaddingException {
        if (mTertiaryKeyRotationScheduler.isKeyRotationDue(mPackageName)) {
            Slog.i(TAG, "Tertiary key rotation was required for " + mPackageName);
            return Optional.empty();
        } else {
            Slog.i(TAG, "Tertiary key rotation was not required");
            return mKeyStore.load(mPackageName);
        }
    }
}
