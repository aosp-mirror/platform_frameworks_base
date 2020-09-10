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

package android.security.keystore.recovery;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

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
@SystemApi
public final class WrappedApplicationKey implements Parcelable {
    private String mAlias;
    // The only supported format is AES-256 symmetric key.
    private byte[] mEncryptedKeyMaterial;
    // The optional metadata that's authenticated (but unencrypted) with the key material.
    private byte[] mMetadata;

    // IMPORTANT! PLEASE READ!
    // -----------------------
    // If you edit this file (e.g., to add new fields), please MAKE SURE to also do the following:
    // - Update the #writeToParcel(Parcel) method below
    // - Update the #(Parcel) constructor below
    // - Update android.security.keystore.recovery.KeyChainSnapshotTest to make sure nobody
    //     accidentally breaks your fields in the Parcel in the future.
    // - Update com.android.server.locksettings.recoverablekeystore.serialization
    //     .KeyChainSnapshotSerializer to correctly serialize your new field
    // - Update com.android.server.locksettings.recoverablekeystore.serialization
    //     .KeyChainSnapshotSerializer to correctly deserialize your new field
    // - Update com.android.server.locksettings.recoverablekeystore.serialization
    //     .KeychainSnapshotSerializerTest to make sure nobody breaks serialization of your field
    //     in the future.

    /**
     * Builder for creating {@link WrappedApplicationKey}.
     */
    public static class Builder {
        private WrappedApplicationKey mInstance = new WrappedApplicationKey();

        /**
         * Sets Application-specific alias of the key.
         *
         * @param alias The alias.
         * @return This builder.
         */
        public @NonNull Builder setAlias(@NonNull String alias) {
            mInstance.mAlias = alias;
            return this;
        }

        /**
         * Sets key material encrypted by recovery key.
         *
         * @param encryptedKeyMaterial The key material
         * @return This builder
         */
        public @NonNull Builder setEncryptedKeyMaterial(@NonNull byte[] encryptedKeyMaterial) {
            mInstance.mEncryptedKeyMaterial = encryptedKeyMaterial;
            return this;
        }

        /**
         * Sets the metadata that is authenticated (but unecrypted) with the key material.
         *
         * @param metadata The metadata
         * @return This builder
         */
        public @NonNull Builder setMetadata(@Nullable byte[] metadata) {
            mInstance.mMetadata = metadata;
            return this;
        }

        /**
         * Creates a new {@link WrappedApplicationKey} instance.
         *
         * @return new instance
         * @throws NullPointerException if some required fields were not set.
         */
        public @NonNull WrappedApplicationKey build() {
            Objects.requireNonNull(mInstance.mAlias);
            Objects.requireNonNull(mInstance.mEncryptedKeyMaterial);
            return mInstance;
        }
    }

    private WrappedApplicationKey() { }

    /**
     * @deprecated Use the builder instead.
     * @hide
     */
    @Deprecated
    public WrappedApplicationKey(@NonNull String alias, @NonNull byte[] encryptedKeyMaterial) {
        mAlias = Objects.requireNonNull(alias);
        mEncryptedKeyMaterial = Objects.requireNonNull(encryptedKeyMaterial);
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

    /** The metadata with the key. */
    public @Nullable byte[] getMetadata() {
        return mMetadata;
    }

    public static final @NonNull Parcelable.Creator<WrappedApplicationKey> CREATOR =
            new Parcelable.Creator<WrappedApplicationKey>() {
                public WrappedApplicationKey createFromParcel(Parcel in) {
                    return new WrappedApplicationKey(in);
                }

                public WrappedApplicationKey[] newArray(int length) {
                    return new WrappedApplicationKey[length];
                }
            };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mAlias);
        out.writeByteArray(mEncryptedKeyMaterial);
        out.writeByteArray(mMetadata);
    }

    /**
     * @hide
     */
    protected WrappedApplicationKey(Parcel in) {
        mAlias = in.readString();
        mEncryptedKeyMaterial = in.createByteArray();
        // Check if there is still data to be read.
        if (in.dataAvail() > 0) {
            mMetadata = in.createByteArray();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
