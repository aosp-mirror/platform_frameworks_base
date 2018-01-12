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

package android.security.keystore;

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
public final class EntryRecoveryData implements Parcelable {
    private String mAlias;
    // The only supported format is AES-256 symmetric key.
    private byte[] mEncryptedKeyMaterial;

    /**
     * Builder for creating {@link EntryRecoveryData}.
     */
    public static class Builder {
        private EntryRecoveryData mInstance = new EntryRecoveryData();

        /**
         * Sets Application-specific alias of the key.
         *
         * @param alias The alias.
         * @return This builder.
         */
        public Builder setAlias(@NonNull String alias) {
            mInstance.mAlias = alias;
            return this;
        }

        /**
         * Sets key material encrypted by recovery key.
         *
         * @param encryptedKeyMaterial The key material
         * @return This builder
         */

        public Builder setEncryptedKeyMaterial(@NonNull byte[] encryptedKeyMaterial) {
            mInstance.mEncryptedKeyMaterial = encryptedKeyMaterial;
            return this;
        }

        /**
         * Creates a new {@link EntryRecoveryData} instance.
         *
         * @return new instance
         * @throws NullPointerException if some required fields were not set.
         */
        public @NonNull EntryRecoveryData build() {
            Preconditions.checkNotNull(mInstance.mAlias);
            Preconditions.checkNotNull(mInstance.mEncryptedKeyMaterial);
            return mInstance;
        }
    }

    private EntryRecoveryData() {

    }

    /**
     * Deprecated - consider using Builder.
     * @hide
     */
    public EntryRecoveryData(@NonNull String alias, @NonNull byte[] encryptedKeyMaterial) {
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

    /** Key material encrypted by recovery key. */
    public @NonNull byte[] getEncryptedKeyMaterial() {
        return mEncryptedKeyMaterial;
    }

    public static final Parcelable.Creator<EntryRecoveryData> CREATOR =
            new Parcelable.Creator<EntryRecoveryData>() {
                public EntryRecoveryData createFromParcel(Parcel in) {
                    return new EntryRecoveryData(in);
                }

                public EntryRecoveryData[] newArray(int length) {
                    return new EntryRecoveryData[length];
                }
            };

    /**
     * @hide
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mAlias);
        out.writeByteArray(mEncryptedKeyMaterial);
    }

    /**
     * @hide
     */
    protected EntryRecoveryData(Parcel in) {
        mAlias = in.readString();
        mEncryptedKeyMaterial = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
