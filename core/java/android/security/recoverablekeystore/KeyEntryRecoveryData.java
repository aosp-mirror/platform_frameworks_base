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
 * limitations under the License.
 */

package android.security.recoverablekeystore;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Helper class with data necessary recover a single application key, given a recovery key.
 *
 * <ul>
 *   <li>Alias - Keystore alias of the key.
 *   <li>Encrypted key material.
 * </ul>
 *
 * Note that Application info is not included. Recovery Agent can only make its own keys
 * recoverable.
 *
 * @hide
 */
public final class KeyEntryRecoveryData implements Parcelable {
    private final String mAlias;
    // The only supported format is AES-256 symmetric key.
    private final byte[] mEncryptedKeyMaterial;

    public KeyEntryRecoveryData(@NonNull String alias, @NonNull byte[] encryptedKeyMaterial) {
        mAlias = Preconditions.checkNotNull(alias);
        mEncryptedKeyMaterial = Preconditions.checkNotNull(encryptedKeyMaterial);
    }

    /**
     * Application-specific alias of the key.
     *
     * @see java.security.KeyStore.aliases
     */
    public @NonNull String getAlias() {
        return mAlias;
    }

    /** Encrypted key material encrypted by recovery key. */
    public @NonNull byte[] getEncryptedKeyMaterial() {
        return mEncryptedKeyMaterial;
    }

    public static final Parcelable.Creator<KeyEntryRecoveryData> CREATOR =
            new Parcelable.Creator<KeyEntryRecoveryData>() {
                public KeyEntryRecoveryData createFromParcel(Parcel in) {
                    return new KeyEntryRecoveryData(in);
                }

                public KeyEntryRecoveryData[] newArray(int length) {
                    return new KeyEntryRecoveryData[length];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mAlias);
        out.writeByteArray(mEncryptedKeyMaterial);
    }

    protected KeyEntryRecoveryData(Parcel in) {
        mAlias = in.readString();
        mEncryptedKeyMaterial = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
