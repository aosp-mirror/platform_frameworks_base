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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Helper class with data necessary to recover Keystore on a new device.
 * It defines UI shown to the user and a way to derive a cryptographic key from user output.
 *
 * @hide
 */
public final class KeyStoreRecoveryMetadata implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_LOCKSCREEN, TYPE_CUSTOM_PASSWORD})
    public @interface UserSecretType {
    }

    /**
     * Lockscreen secret is required to recover KeyStore.
     */
    public static final int TYPE_LOCKSCREEN = 1;

    /**
     * Custom passphrase, unrelated to lock screen, is required to recover KeyStore.
     */
    public static final int TYPE_CUSTOM_PASSWORD = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_PIN, TYPE_PASSWORD, TYPE_PATTERN})
    public @interface LockScreenUiFormat {
    }

    /**
     * Pin with digits only.
     */
    public static final int TYPE_PIN = 1;

    /**
     * Password. String with latin-1 characters only.
     */
    public static final int TYPE_PASSWORD = 2;

    /**
     * Pattern with 3 by 3 grid.
     */
    public static final int TYPE_PATTERN = 3;

    @UserSecretType
    private final int mUserSecretType;

    @LockScreenUiFormat
    private final int mLockScreenUiFormat;

    /**
     * Parameters of key derivation function, including algorithm, difficulty, salt.
     */
    private KeyDerivationParameters mKeyDerivationParameters;
    private byte[] mSecret; // Derived from user secret. The field must have limited visibility.

    /**
     * @param secret Constructor creates a reference to the secret. Caller must use
     * @link {#clearSecret} to overwrite its value in memory.
     */
    public KeyStoreRecoveryMetadata(@UserSecretType int userSecretType,
            @LockScreenUiFormat int lockScreenUiFormat,
            @NonNull KeyDerivationParameters keyDerivationParameters, @NonNull byte[] secret) {
        mUserSecretType = userSecretType;
        mLockScreenUiFormat = lockScreenUiFormat;
        mKeyDerivationParameters = Preconditions.checkNotNull(keyDerivationParameters);
        mSecret = Preconditions.checkNotNull(secret);
    }

    /**
     * Specifies UX shown to user during recovery.
     *
     * @see KeyStore.TYPE_PIN
     * @see KeyStore.TYPE_PASSWORD
     * @see KeyStore.TYPE_PATTERN
     */
    public @LockScreenUiFormat int getLockScreenUiFormat() {
        return mLockScreenUiFormat;
    }

    /**
     * Specifies function used to derive symmetric key from user input
     * Format is defined in separate util class.
     */
    public @NonNull KeyDerivationParameters getKeyDerivationParameters() {
        return mKeyDerivationParameters;
    }

    /**
     * Secret string derived from user input.
     */
    public @NonNull byte[] getSecret() {
        return mSecret;
    }

    /**
     * @see KeyStore.TYPE_LOCKSCREEN
     * @see KeyStore.TYPE_CUSTOM_PASSWORD
     */
    public @UserSecretType int getUserSecretType() {
        return mUserSecretType;
    }

    /**
     * Removes secret from memory than object is no longer used.
     * Since finalizer call is not reliable, please use @link {#clearSecret} directly.
     */
    @Override
    protected void finalize() throws Throwable {
        clearSecret();
        super.finalize();
    }

    /**
     * Fills mSecret with zeroes.
     */
    public void clearSecret() {
        Arrays.fill(mSecret, (byte) 0);
    }

    public static final Parcelable.Creator<KeyStoreRecoveryMetadata> CREATOR =
            new Parcelable.Creator<KeyStoreRecoveryMetadata>() {
        public KeyStoreRecoveryMetadata createFromParcel(Parcel in) {
            return new KeyStoreRecoveryMetadata(in);
        }

        public KeyStoreRecoveryMetadata[] newArray(int length) {
            return new KeyStoreRecoveryMetadata[length];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mUserSecretType);
        out.writeInt(mLockScreenUiFormat);
        out.writeTypedObject(mKeyDerivationParameters, flags);
        out.writeByteArray(mSecret);
    }

    protected KeyStoreRecoveryMetadata(Parcel in) {
        mUserSecretType = in.readInt();
        mLockScreenUiFormat = in.readInt();
        mKeyDerivationParameters = in.readTypedObject(KeyDerivationParameters.CREATOR);
        mSecret = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
