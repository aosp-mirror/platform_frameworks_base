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
    private final int mAlgorithm;
    private final byte[] mSalt;
    private final int mDifficulty;

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
     *
     * @hide
     */
    public static final int ALGORITHM_SCRYPT = 2;

    /**
     * Creates instance of the class to to derive key using salted SHA256 hash.
     */
    public static KeyDerivationParams createSha256Params(@NonNull byte[] salt) {
        return new KeyDerivationParams(ALGORITHM_SHA256, salt);
    }

    /**
     * Creates instance of the class to to derive key using the password hashing algorithm SCRYPT.
     *
     * @hide
     */
    public static KeyDerivationParams createScryptParams(@NonNull byte[] salt, int difficulty) {
        return new KeyDerivationParams(ALGORITHM_SCRYPT, salt, difficulty);
    }

    /**
     * @hide
     */
    // TODO: Make private once legacy API is removed
    public KeyDerivationParams(@KeyDerivationAlgorithm int algorithm, @NonNull byte[] salt) {
        this(algorithm, salt, /*difficulty=*/ 0);
    }

    /**
     * @hide
     */
    KeyDerivationParams(@KeyDerivationAlgorithm int algorithm, @NonNull byte[] salt,
            int difficulty) {
        mAlgorithm = algorithm;
        mSalt = Preconditions.checkNotNull(salt);
        mDifficulty = difficulty;
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
     * Gets hashing difficulty.
     *
     * @hide
     */
    public int getDifficulty() {
        return mDifficulty;
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
        out.writeInt(mDifficulty);
    }

    /**
     * @hide
     */
    protected KeyDerivationParams(Parcel in) {
        mAlgorithm = in.readInt();
        mSalt = in.createByteArray();
        mDifficulty = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
