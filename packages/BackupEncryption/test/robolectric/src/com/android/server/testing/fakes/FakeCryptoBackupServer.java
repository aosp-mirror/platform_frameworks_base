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

package com.android.server.testing.fakes;

import android.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.client.UnexpectedActiveSecondaryOnServerException;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Fake {@link CryptoBackupServer}, for tests. Stores tertiary keys in memory. */
public class FakeCryptoBackupServer implements CryptoBackupServer {
    @GuardedBy("this")
    @Nullable
    private String mActiveSecondaryKeyAlias;

    // Secondary key alias -> (package name -> tertiary key)
    @GuardedBy("this")
    private Map<String, Map<String, WrappedKeyProto.WrappedKey>> mWrappedKeyStore = new HashMap<>();

    @Override
    public String uploadIncrementalBackup(
            String packageName,
            String oldDocId,
            byte[] diffScript,
            WrappedKeyProto.WrappedKey tertiaryKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String uploadNonIncrementalBackup(
            String packageName, byte[] data, WrappedKeyProto.WrappedKey tertiaryKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized void setActiveSecondaryKeyAlias(
            String keyAlias, Map<String, WrappedKeyProto.WrappedKey> tertiaryKeys) {
        mActiveSecondaryKeyAlias = keyAlias;

        mWrappedKeyStore.putIfAbsent(keyAlias, new HashMap<>());
        Map<String, WrappedKeyProto.WrappedKey> keyStore = mWrappedKeyStore.get(keyAlias);

        for (String packageName : tertiaryKeys.keySet()) {
            keyStore.put(packageName, tertiaryKeys.get(packageName));
        }
    }

    public synchronized Optional<String> getActiveSecondaryKeyAlias() {
        return Optional.ofNullable(mActiveSecondaryKeyAlias);
    }

    public synchronized Map<String, WrappedKeyProto.WrappedKey> getAllTertiaryKeys(
            String secondaryKeyAlias) throws UnexpectedActiveSecondaryOnServerException {
        if (!secondaryKeyAlias.equals(mActiveSecondaryKeyAlias)) {
            throw new UnexpectedActiveSecondaryOnServerException(
                    String.format(
                            Locale.US,
                            "Requested tertiary keys wrapped with %s but %s was active secondary.",
                            secondaryKeyAlias,
                            mActiveSecondaryKeyAlias));
        }

        if (!mWrappedKeyStore.containsKey(secondaryKeyAlias)) {
            return Collections.emptyMap();
        }
        return new HashMap<>(mWrappedKeyStore.get(secondaryKeyAlias));
    }
}
