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

import android.os.ServiceSpecificException;

/**
 * Exception thrown by {@link RecoveryManager} methods.
 *
 * @hide
 */
public class RecoveryManagerException extends Exception {
    /**
     * Failed because the loader has not been initialized with a recovery public key yet.
     */
    public static final int ERROR_UNINITIALIZED_RECOVERY_PUBLIC_KEY = 20;

    /**
     * Failed because no snapshot is yet pending to be synced for the user.
     */
    public static final int ERROR_NO_SNAPSHOT_PENDING = 21;

    /**
     * Failed due to an error internal to AndroidKeyStore.
     */
    public static final int ERROR_KEYSTORE_INTERNAL_ERROR = 22;

    /**
     * Failed because the user does not have a lock screen set.
     */
    public static final int ERROR_INSECURE_USER = 24;

    /**
     * Failed because of an internal database error.
     */
    public static final int ERROR_DATABASE_ERROR = 25;

    /**
     * Failed because the provided certificate was not a valid X509 certificate.
     */
    public static final int ERROR_BAD_X509_CERTIFICATE = 26;

    /**
     * Should never be thrown - some algorithm that all AOSP implementations must support is
     * not available.
     */
    public static final int ERROR_UNEXPECTED_MISSING_ALGORITHM = 27;

    /**
     * Error thrown if decryption failed. This might be because the tag is wrong, the key is wrong,
     * the data has become corrupted, the data has been tampered with, etc.
     */
    public static final int ERROR_DECRYPTION_FAILED = 28;

    /**
     * Rate limit is enforced to prevent using too many trusted remote devices, since each device
     * can have its own number of user secret guesses allowed.
     *
     * @hide
     */
    public static final int ERROR_RATE_LIMIT_EXCEEDED = 29;

    private int mErrorCode;

    /**
     * Creates new {@link #RecoveryManagerException} instance from the error code.
     *
     * @param errorCode An error code, as listed at the top of this file.
     * @param message The associated error message.
     * @hide
     */
    public static RecoveryManagerException fromErrorCode(
            int errorCode, String message) {
        return new RecoveryManagerException(errorCode, message);
    }
    /**
     * Creates new {@link #RecoveryManagerException} from {@link
     * ServiceSpecificException}.
     *
     * @param e exception thrown on service side.
     * @hide
     */
    static RecoveryManagerException fromServiceSpecificException(
            ServiceSpecificException e) throws RecoveryManagerException {
        throw RecoveryManagerException.fromErrorCode(e.errorCode, e.getMessage());
    }

    private RecoveryManagerException(int errorCode, String message) {
        super(message);
        mErrorCode = errorCode;
    }

    /** Returns errorCode. */
    public int getErrorCode() {
        return mErrorCode;
    }
}
