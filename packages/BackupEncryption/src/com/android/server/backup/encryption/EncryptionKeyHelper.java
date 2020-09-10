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

package com.android.server.backup.encryption;

import android.content.Context;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.RecoveryController;

import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyRotationScheduler;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

class EncryptionKeyHelper {
    private static SecureRandom sSecureRandom = new  SecureRandom();

    private final Context mContext;
    private final RecoverableKeyStoreSecondaryKeyManager
            .RecoverableKeyStoreSecondaryKeyManagerProvider
            mSecondaryKeyManagerProvider;

    EncryptionKeyHelper(Context context) {
        mContext = context;
        mSecondaryKeyManagerProvider =
                () ->
                        new RecoverableKeyStoreSecondaryKeyManager(
                                RecoveryController.getInstance(mContext), sSecureRandom);
    }

    RecoverableKeyStoreSecondaryKeyManager
            .RecoverableKeyStoreSecondaryKeyManagerProvider getKeyManagerProvider() {
        return mSecondaryKeyManagerProvider;
    }

    RecoverableKeyStoreSecondaryKey getActiveSecondaryKey()
            throws UnrecoverableKeyException, InternalRecoveryServiceException {
        String keyAlias = CryptoSettings.getInstance(mContext).getActiveSecondaryKeyAlias().get();
        return mSecondaryKeyManagerProvider.get().get(keyAlias).get();
    }

    SecretKey getTertiaryKey(
            String packageName,
            RecoverableKeyStoreSecondaryKey secondaryKey)
            throws IllegalBlockSizeException, InvalidAlgorithmParameterException,
            NoSuchAlgorithmException, IOException, NoSuchPaddingException,
            InvalidKeyException {
        TertiaryKeyManager tertiaryKeyManager =
                new TertiaryKeyManager(
                        mContext,
                        sSecureRandom,
                        TertiaryKeyRotationScheduler.getInstance(mContext),
                        secondaryKey,
                        packageName);
        return tertiaryKeyManager.getKey();
    }
}
