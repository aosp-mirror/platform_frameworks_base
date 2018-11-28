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

package android.hardware.biometrics;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.os.CancellationSignal;
import android.os.Parcelable;

import java.util.concurrent.Executor;

/**
 * This is the common interface that all biometric authentication classes should implement.
 * @hide
 */
public interface BiometricAuthenticator {

    /**
     * No biometric methods or nothing has been enrolled.
     * Move/expose these in BiometricPrompt if we ever want to allow applications to "blacklist"
     * modalities when calling authenticate().
     * @hide
     */
    int TYPE_NONE = 0;
    /**
     * Constant representing fingerprint.
     * @hide
     */
    int TYPE_FINGERPRINT = 1 << 0;

    /**
     * Constant representing iris.
     * @hide
     */
    int TYPE_IRIS = 1 << 1;

    /**
     * Constant representing face.
     * @hide
     */
    int TYPE_FACE = 1 << 2;

    /**
     * Container for biometric data
     * @hide
     */
    abstract class Identifier implements Parcelable {
        private CharSequence mName;
        private int mBiometricId;
        private long mDeviceId; // physical device this is associated with

        public Identifier() {}

        public Identifier(CharSequence name, int biometricId, long deviceId) {
            mName = name;
            mBiometricId = biometricId;
            mDeviceId = deviceId;
        }

        /**
         * Gets the human-readable name for the given biometric.
         * @return name given to the biometric
         */
        public CharSequence getName() {
            return mName;
        }

        /**
         * Gets the device-specific biometric id.  Used by Settings to map a name to a specific
         * biometric template.
         */
        public int getBiometricId() {
            return mBiometricId;
        }

        /**
         * Device this biometric belongs to.
         */
        public long getDeviceId() {
            return mDeviceId;
        }

        public void setName(CharSequence name) {
            mName = name;
        }

        public void setDeviceId(long deviceId) {
            mDeviceId = deviceId;
        }
    }

    /**
     * Container for callback data from {@link BiometricAuthenticator#authenticate(
     * CancellationSignal, Executor, AuthenticationCallback)} and
     * {@link BiometricAuthenticator#authenticate(CryptoObject, CancellationSignal, Executor,
     * AuthenticationCallback)}
     */
    class AuthenticationResult {
        private Identifier mIdentifier;
        private CryptoObject mCryptoObject;
        private int mUserId;

        /**
         * @hide
         */
        public AuthenticationResult() { }

        /**
         * Authentication result
         * @param crypto
         * @param identifier
         * @param userId
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto, Identifier identifier,
                int userId) {
            mCryptoObject = crypto;
            mIdentifier = identifier;
            mUserId = userId;
        }

        /**
         * Obtain the crypto object associated with this transaction
         * @return crypto object provided to {@link BiometricAuthenticator#authenticate(
         * CryptoObject, CancellationSignal, Executor, AuthenticationCallback)}
         */
        public CryptoObject getCryptoObject() {
            return mCryptoObject;
        }

        /**
         * Obtain the biometric identifier associated with this operation. Applications are strongly
         * discouraged from associating specific identifiers with specific applications or
         * operations.
         * @hide
         */
        public Identifier getId() {
            return mIdentifier;
        }

        /**
         * Obtain the userId for which this biometric was authenticated.
         * @hide
         */
        public int getUserId() {
            return mUserId;
        }
    };

    /**
     * Callback structure provided to {@link BiometricAuthenticator#authenticate(CancellationSignal,
     * Executor, AuthenticationCallback)} or {@link BiometricAuthenticator#authenticate(
     * CryptoObject, CancellationSignal, Executor, AuthenticationCallback)}. Users must provide
     * an implementation of this for listening to biometric events.
     */
    abstract class AuthenticationCallback {
        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further actions will be made on this object.
         * @param errorCode An integer identifying the error message
         * @param errString A human-readable error string that can be shown on an UI
         */
        public void onAuthenticationError(int errorCode, CharSequence errString) {}

        /**
         * Called when a recoverable error has been encountered during authentication. The help
         * string is provided to give the user guidance for what went wrong, such as "Sensor dirty,
         * please clean it."
         * @param helpCode An integer identifying the error message
         * @param helpString A human-readable string that can be shown on an UI
         */
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {}

        /**
         * Called when a biometric is valid but not recognized.
         */
        public void onAuthenticationFailed() {}

        /**
         * Called when a biometric has been acquired, but hasn't been processed yet.
         * @hide
         */
        public void onAuthenticationAcquired(int acquireInfo) {}
    };

    /**
     * @return true if the biometric hardware is detected.
     */
    default boolean isHardwareDetected() {
        throw new UnsupportedOperationException("Stub!");
    }

    /**
     * @return true if the user has enrolled templates for this biometric.
     */
    default boolean hasEnrolledTemplates() {
        throw new UnsupportedOperationException("Stub!");
    }

    /**
     * @return true if the user has enrolled templates for this biometric.
     */
    default boolean hasEnrolledTemplates(int userId) {
        throw new UnsupportedOperationException("Stub!");
    }

    /**
     * Sets the active user. This is meant to be used to select the current profile
     * to allow separate templates for work profile.
     */
    default void setActiveUser(int userId) {
        throw new UnsupportedOperationException("Stub!");
    }

    /**
     * This call warms up the hardware and starts scanning for valid biometrics. It terminates
     * when {@link AuthenticationCallback#onAuthenticationError(int,
     * CharSequence)} is called or when {@link AuthenticationCallback#onAuthenticationSucceeded(
     * AuthenticationResult)} is called, at which point the crypto object becomes invalid. This
     * operation can be canceled by using the provided cancel object. The application wil receive
     * authentication errors through {@link AuthenticationCallback}. Calling
     * {@link BiometricAuthenticator#authenticate(CryptoObject, CancellationSignal, Executor,
     * AuthenticationCallback)} while an existing authentication attempt is occurring will stop
     * the previous client and start a new authentication. The interrupted client will receive a
     * cancelled notification through {@link AuthenticationCallback#onAuthenticationError(int,
     * CharSequence)}.
     *
     * @throws IllegalArgumentException If any of the arguments are null
     *
     * @param crypto Object associated with the call
     * @param cancel An object that can be used to cancel authentication
     * @param executor An executor to handle callback events
     * @param callback An object to receive authentication events
     */
    default void authenticate(@NonNull CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback) {
        throw new UnsupportedOperationException("Stub!");
    }

    /**
     * This call warms up the hardware and starts scanning for valid biometrics. It terminates
     * when {@link AuthenticationCallback#onAuthenticationError(int,
     * CharSequence)} is called or when {@link AuthenticationCallback#onAuthenticationSucceeded(
     * AuthenticationResult)} is called. This operation can be canceled by using the provided cancel
     * object. The application wil receive authentication errors through
     * {@link AuthenticationCallback}. Calling {@link BiometricAuthenticator#authenticate(
     * CryptoObject, CancellationSignal, Executor, AuthenticationCallback)} while an existing
     * authentication attempt is occurring will stop the previous client and start a new
     * authentication. The interrupted client will receive a cancelled notification through
     * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)}.
     *
     * @throws IllegalArgumentException If any of the arguments are null
     *
     * @param cancel An object that can be used to cancel authentication
     * @param executor An executor to handle callback events
     * @param callback An object to receive authentication events
     */
    default void authenticate(@NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback) {
        throw new UnsupportedOperationException("Stub!");
    }
}
