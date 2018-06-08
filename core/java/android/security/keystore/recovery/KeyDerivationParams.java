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

package android.security.keystore.recovery;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Collection of parameters which define a key derivation function.
 * Currently only supports salted SHA-256.
 *
 * @hide
 */
@SystemApi
public final class KeyDerivationParams implements Parcelable {

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

    private final int mAlgorithm;
    private final byte[] mSalt;
    private final int mMemoryDifficulty;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ALGORITHM_"}, value = {ALGORITHM_SHA256, ALGORITHM_SCRYPT})
    public @interface KeyDerivationAlgorithm {
    }

    /**
     * Salted SHA256.
     */
    public static final int ALGORITHM_SHA256 = 1;

    /**
     * SCRYPT.
     */
    public static final int ALGORITHM_SCRYPT = 2;

    /**
     * Creates instance of the class to to derive keys using salted SHA256 hash.
     *
     * <p>The salted SHA256 hash is computed over the concatenation of four byte strings, salt_len +
     * salt + key_material_len + key_material, where salt_len and key_material_len are 4-byte, and
     * denote the number of bytes for salt and key_material, respectively.
     */
    public static @NonNull KeyDerivationParams createSha256Params(@NonNull byte[] salt) {
        return new KeyDerivationParams(ALGORITHM_SHA256, salt);
    }

    /**
     * Creates instance of the class to to derive keys using the password hashing algorithm SCRYPT.
     *
     * <p>We expose only one tuning parameter of SCRYPT, which is the memory cost parameter (i.e. N
     * in <a href="https://www.tarsnap.com/scrypt/scrypt.pdf">the SCRYPT paper</a>). Regular/default
     * values are used for the other parameters, to keep the overall running time low. Specifically,
     * the parallelization parameter p is 1, the block size parameter r is 8, and the hashing output
     * length is 32-byte.
     */
    public static @NonNull KeyDerivationParams createScryptParams(
            @NonNull byte[] salt, int memoryDifficulty) {
        return new KeyDerivationParams(ALGORITHM_SCRYPT, salt, memoryDifficulty);
    }

    /**
     * @hide
     */
    private KeyDerivationParams(@KeyDerivationAlgorithm int algorithm, @NonNull byte[] salt) {
        this(algorithm, salt, /*memoryDifficulty=*/ -1);
    }

    /**
     * @hide
     */
    private KeyDerivationParams(@KeyDerivationAlgorithm int algorithm, @NonNull byte[] salt,
            int memoryDifficulty) {
        mAlgorithm = algorithm;
        mSalt = Preconditions.checkNotNull(salt);
        mMemoryDifficulty = memoryDifficulty;
    }

    /**
     * Gets algorithm.
     */
    public @KeyDerivationAlgorithm int getAlgorithm() {
        return mAlgorithm;
    }

    /**
     * Gets salt.
     */
    public @NonNull byte[] getSalt() {
        return mSalt;
    }

    /**
     * Gets the memory difficulty parameter for the hashing algorithm.
     *
     * <p>The effect of this parameter depends on the algorithm in use. For example, please see
     * {@link #createScryptParams(byte[], int)} for choosing the parameter for SCRYPT.
     *
     * <p>If the specific algorithm does not support such a memory difficulty parameter, its value
     * should be -1.
     */
    public int getMemoryDifficulty() {
        return mMemoryDifficulty;
    }

    public static final Parcelable.Creator<KeyDerivationParams> CREATOR =
            new Parcelable.Creator<KeyDerivationParams>() {
        public KeyDerivationParams createFromParcel(Parcel in) {
                return new KeyDerivationParams(in);
        }

        public KeyDerivationParams[] newArray(int length) {
            return new KeyDerivationParams[length];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mAlgorithm);
        out.writeByteArray(mSalt);
        out.writeInt(mMemoryDifficulty);
    }

    /**
     * @hide
     */
    protected KeyDerivationParams(Parcel in) {
        mAlgorithm = in.readInt();
        mSalt = in.createByteArray();
        mMemoryDifficulty = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
