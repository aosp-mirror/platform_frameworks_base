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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @deprecated Use {@link android.security.keystore.recovery.KeyDerivationParams}.
 * @hide
 */
public final class KeyDerivationParams implements Parcelable {
    private final int mAlgorithm;
    private byte[] mSalt;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ALGORITHM_"}, value = {ALGORITHM_SHA256, ALGORITHM_ARGON2ID})
    public @interface KeyDerivationAlgorithm {
    }

    /**
     * Salted SHA256
     */
    public static final int ALGORITHM_SHA256 = 1;

    /**
     * Argon2ID
     * @hide
     */
    // TODO: add Argon2ID support.
    public static final int ALGORITHM_ARGON2ID = 2;

    /**
     * Creates instance of the class to to derive key using salted SHA256 hash.
     */
    public static KeyDerivationParams createSha256Params(@NonNull byte[] salt) {
        return new KeyDerivationParams(ALGORITHM_SHA256, salt);
    }

    KeyDerivationParams(@KeyDerivationAlgorithm int algorithm, @NonNull byte[] salt) {
        mAlgorithm = algorithm;
        mSalt = Preconditions.checkNotNull(salt);
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

    public static final Parcelable.Creator<KeyDerivationParams> CREATOR =
            new Parcelable.Creator<KeyDerivationParams>() {
        public KeyDerivationParams createFromParcel(Parcel in) {
                return new KeyDerivationParams(in);
        }

        public KeyDerivationParams[] newArray(int length) {
            return new KeyDerivationParams[length];
        }
    };

    /**
     * @hide
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mAlgorithm);
        out.writeByteArray(mSalt);
    }

    /**
     * @hide
     */
    protected KeyDerivationParams(Parcel in) {
        mAlgorithm = in.readInt();
        mSalt = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
