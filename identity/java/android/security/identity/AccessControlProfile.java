/*
 * Copyright 2020 The Android Open Source Project
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

package android.security.identity;

import android.annotation.NonNull;

import java.security.cert.X509Certificate;

/**
 * A class used to specify access controls.
 */
public class AccessControlProfile {
    private AccessControlProfileId mAccessControlProfileId = new AccessControlProfileId(0);
    private X509Certificate mReaderCertificate = null;
    private boolean mUserAuthenticationRequired = true;
    private long mUserAuthenticationTimeout = 0;

    private AccessControlProfile() {
    }

    AccessControlProfileId getAccessControlProfileId() {
        return mAccessControlProfileId;
    }

    long getUserAuthenticationTimeout() {
        return mUserAuthenticationTimeout;
    }

    boolean isUserAuthenticationRequired() {
        return mUserAuthenticationRequired;
    }

    X509Certificate getReaderCertificate() {
        return mReaderCertificate;
    }

    /**
     * A builder for {@link AccessControlProfile}.
     */
    public static final class Builder {
        private AccessControlProfile mProfile;

        /**
         * Each access control profile has numeric identifier that must be unique within the
         * context of a Credential and may be used to reference the profile.
         *
         * <p>By default, the resulting {@link AccessControlProfile} will require user
         * authentication with a timeout of zero, thus requiring the holder to authenticate for
         * every presentation where data elements using this access control profile is used.</p>
         *
         * @param accessControlProfileId the access control profile identifier.
         */
        public Builder(@NonNull AccessControlProfileId accessControlProfileId) {
            mProfile = new AccessControlProfile();
            mProfile.mAccessControlProfileId = accessControlProfileId;
        }

        /**
         * Set whether user authentication is required.
         *
         * <p>This should be used sparingly since disabling user authentication on just a single
         * data element can easily create a
         * <a href="https://en.wikipedia.org/wiki/Relay_attack">Relay Attack</a> if the device
         * on which the credential is stored is compromised.</p>
         *
         * @param userAuthenticationRequired Set to true if user authentication is required,
         *                                   false otherwise.
         * @return The builder.
         */
        public @NonNull Builder setUserAuthenticationRequired(boolean userAuthenticationRequired) {
            mProfile.mUserAuthenticationRequired = userAuthenticationRequired;
            return this;
        }

        /**
         * Sets the authentication timeout to use.
         *
         * <p>The authentication timeout specifies the amount of time, in milliseconds, for which a
         * user authentication is valid, if user authentication is required (see
         * {@link #setUserAuthenticationRequired(boolean)}).</p>
         *
         * <p>If the timeout is zero, then authentication is always required for each reader
         * session.</p>
         *
         * @param userAuthenticationTimeoutMillis the authentication timeout, in milliseconds.
         * @return The builder.
         */
        public @NonNull Builder setUserAuthenticationTimeout(long userAuthenticationTimeoutMillis) {
            mProfile.mUserAuthenticationTimeout = userAuthenticationTimeoutMillis;
            return this;
        }

        /**
         * Sets the reader certificate to use when checking access control.
         *
         * <p>If set, this is checked against the certificate chain presented by
         * reader. The access check is fulfilled only if one of the certificates
         * in the chain, matches the certificate set by this method.</p>
         *
         * @param readerCertificate the certificate to use for the access control check.
         * @return The builder.
         */
        public @NonNull Builder setReaderCertificate(@NonNull X509Certificate readerCertificate) {
            mProfile.mReaderCertificate = readerCertificate;
            return this;
        }

        /**
         * Creates a new {@link AccessControlProfile} from the data supplied to the builder.
         *
         * @return The created {@link AccessControlProfile} object.
         */
        public @NonNull AccessControlProfile build() {
            return mProfile;
        }
    }
}
