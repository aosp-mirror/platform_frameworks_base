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
     * Container for biometric data
     * @hide
     */
    abstract class BiometricIdentifier implements Parcelable {}

    /**
     * Container for callback data from {@link BiometricAuthenticator#authenticate(
     * CancellationSignal, Executor, AuthenticationCallback)} and
     * {@link BiometricAuthenticator#authenticate(CryptoObject, CancellationSignal, Executor,
     * AuthenticationCallback)}
     */
    class AuthenticationResult {
        private BiometricIdentifier mIdentifier;
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
        public AuthenticationResult(CryptoObject crypto, BiometricIdentifier identifier,
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
        public BiometricIdentifier getId() {
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
         * Called when a biometric is recognized.
         * @param result An object containing authentication-related data
         */
        public void onAuthenticationSucceeded(AuthenticationResult result) {}

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
    void authenticate(@NonNull CryptoObject crypto,
            @NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback);

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
    void authenticate(@NonNull CancellationSignal cancel,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AuthenticationCallback callback);
}
