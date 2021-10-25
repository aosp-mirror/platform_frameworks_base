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

import android.annotation.IntDef;
import android.hardware.biometrics.BiometricPrompt.AuthenticationResultType;
import android.os.CancellationSignal;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * This is the common interface that all biometric authentication classes should implement.
 * @hide
 */
public interface BiometricAuthenticator {

    /**
     * No biometric methods or nothing has been enrolled.
     * Move/expose these in BiometricPrompt if we ever want to allow applications to "denylist"
     * modalities when calling authenticate().
     * @hide
     */
    int TYPE_NONE = 0;

    /**
     * Constant representing credential (PIN, pattern, or password).
     * @hide
     */
    int TYPE_CREDENTIAL = 1 << 0;

    /**
     * Constant representing fingerprint.
     * @hide
     */
    int TYPE_FINGERPRINT = 1 << 1;

    /**
     * Constant representing iris.
     * @hide
     */
    int TYPE_IRIS = 1 << 2;

    /**
     * Constant representing face.
     * @hide
     */
    int TYPE_FACE = 1 << 3;

    /**
     * @hide
     */
    int TYPE_ANY_BIOMETRIC = TYPE_FINGERPRINT | TYPE_IRIS | TYPE_FACE;

    @IntDef(flag = true, value = {
            TYPE_NONE,
            TYPE_CREDENTIAL,
            TYPE_FINGERPRINT,
            TYPE_IRIS,
            TYPE_FACE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Modality {}

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
        private @AuthenticationResultType int mAuthenticationType;
        private int mUserId;

        /**
         * @hide
         */
        public AuthenticationResult() { }

        /**
         * Authentication result
         * @param crypto
         * @param authenticationType
         * @param identifier
         * @param userId
         * @hide
         */
        public AuthenticationResult(CryptoObject crypto,
                @AuthenticationResultType int authenticationType, Identifier identifier,
                int userId) {
            mCryptoObject = crypto;
            mAuthenticationType = authenticationType;
            mIdentifier = identifier;
            mUserId = userId;
        }

        /**
         * Provides the crypto object associated with this transaction.
         * @return The crypto object provided to {@link BiometricPrompt#authenticate(
         * BiometricPrompt.CryptoObject, CancellationSignal, Executor,
         * BiometricPrompt.AuthenticationCallback)}
         */
        public CryptoObject getCryptoObject() {
            return mCryptoObject;
        }

        /**
         * Provides the type of authentication (e.g. device credential or biometric) that was
         * requested from and successfully provided by the user.
         *
         * @return An integer value representing the authentication method used.
         */
        public @AuthenticationResultType int getAuthenticationType() {
            return mAuthenticationType;
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
    }
}
