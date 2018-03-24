/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.security.keystore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Helpers for converting classes between old and new API, so we can preserve backwards
 * compatibility while teamfooding. This will be removed soon.
 *
 * @hide
 */
class BackwardsCompat {


    static KeychainProtectionParams toLegacyKeychainProtectionParams(
            android.security.keystore.recovery.KeyChainProtectionParams keychainProtectionParams
    ) {
        return new KeychainProtectionParams.Builder()
                .setUserSecretType(keychainProtectionParams.getUserSecretType())
                .setSecret(keychainProtectionParams.getSecret())
                .setLockScreenUiFormat(keychainProtectionParams.getLockScreenUiFormat())
                .setKeyDerivationParams(
                        toLegacyKeyDerivationParams(
                                keychainProtectionParams.getKeyDerivationParams()))
                .build();
    }

    static KeyDerivationParams toLegacyKeyDerivationParams(
            android.security.keystore.recovery.KeyDerivationParams keyDerivationParams
    ) {
        return new KeyDerivationParams(
                keyDerivationParams.getAlgorithm(), keyDerivationParams.getSalt());
    }

    static WrappedApplicationKey toLegacyWrappedApplicationKey(
            android.security.keystore.recovery.WrappedApplicationKey wrappedApplicationKey
    ) {
        return new WrappedApplicationKey.Builder()
                .setAlias(wrappedApplicationKey.getAlias())
                .setEncryptedKeyMaterial(wrappedApplicationKey.getEncryptedKeyMaterial())
                .build();
    }

    static android.security.keystore.recovery.KeyDerivationParams fromLegacyKeyDerivationParams(
            KeyDerivationParams keyDerivationParams
    ) {
        return android.security.keystore.recovery.KeyDerivationParams.createSha256Params(
                keyDerivationParams.getSalt());
    }

    static android.security.keystore.recovery.WrappedApplicationKey fromLegacyWrappedApplicationKey(
            WrappedApplicationKey wrappedApplicationKey
    ) {
        return new android.security.keystore.recovery.WrappedApplicationKey.Builder()
                .setAlias(wrappedApplicationKey.getAlias())
                .setEncryptedKeyMaterial(wrappedApplicationKey.getEncryptedKeyMaterial())
                .build();
    }

    static List<android.security.keystore.recovery.WrappedApplicationKey>
            fromLegacyWrappedApplicationKeys(List<WrappedApplicationKey> wrappedApplicationKeys
    ) {
        return map(wrappedApplicationKeys, BackwardsCompat::fromLegacyWrappedApplicationKey);
    }

    static List<android.security.keystore.recovery.KeyChainProtectionParams>
            fromLegacyKeychainProtectionParams(
                    List<KeychainProtectionParams> keychainProtectionParams) {
        return map(keychainProtectionParams, BackwardsCompat::fromLegacyKeychainProtectionParam);
    }

    static android.security.keystore.recovery.KeyChainProtectionParams
            fromLegacyKeychainProtectionParam(KeychainProtectionParams keychainProtectionParams) {
        return new android.security.keystore.recovery.KeyChainProtectionParams.Builder()
                .setUserSecretType(keychainProtectionParams.getUserSecretType())
                .setSecret(keychainProtectionParams.getSecret())
                .setLockScreenUiFormat(keychainProtectionParams.getLockScreenUiFormat())
                .setKeyDerivationParams(
                        fromLegacyKeyDerivationParams(
                                keychainProtectionParams.getKeyDerivationParams()))
                .build();
    }

    static KeychainSnapshot toLegacyKeychainSnapshot(
            android.security.keystore.recovery.KeyChainSnapshot keychainSnapshot
    ) {
        return new KeychainSnapshot.Builder()
                .setCounterId(keychainSnapshot.getCounterId())
                .setEncryptedRecoveryKeyBlob(keychainSnapshot.getEncryptedRecoveryKeyBlob())
                .setTrustedHardwarePublicKey(keychainSnapshot.getTrustedHardwarePublicKey())
                .setSnapshotVersion(keychainSnapshot.getSnapshotVersion())
                .setMaxAttempts(keychainSnapshot.getMaxAttempts())
                .setServerParams(keychainSnapshot.getServerParams())
                .setKeychainProtectionParams(
                        map(keychainSnapshot.getKeyChainProtectionParams(),
                                BackwardsCompat::toLegacyKeychainProtectionParams))
                .setWrappedApplicationKeys(
                        map(keychainSnapshot.getWrappedApplicationKeys(),
                                BackwardsCompat::toLegacyWrappedApplicationKey))
                .build();
    }

    static <A, B> List<B> map(List<A> as, Function<A, B> f) {
        ArrayList<B> bs = new ArrayList<>(as.size());
        for (A a : as) {
            bs.add(f.apply(a));
        }
        return bs;
    }
}
