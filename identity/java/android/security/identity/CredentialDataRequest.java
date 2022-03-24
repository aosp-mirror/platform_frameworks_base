/*
 * Copyright 2021 The Android Open Source Project
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
import android.annotation.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An object representing a request for credential data.
 */
public class CredentialDataRequest {
    CredentialDataRequest() {}

    /**
     * Gets the device-signed entries to request.
     *
     * @return the device-signed entries to request.
     */
    public @NonNull Map<String, Collection<String>> getDeviceSignedEntriesToRequest() {
        return mDeviceSignedEntriesToRequest;
    }

    /**
     * Gets the issuer-signed entries to request.
     *
     * @return the issuer-signed entries to request.
     */
    public @NonNull Map<String, Collection<String>> getIssuerSignedEntriesToRequest() {
        return mIssuerSignedEntriesToRequest;
    }

    /**
     * Gets whether to allow using an authentication key which use count has been exceeded.
     *
     * <p>By default this is set to true.
     *
     * @return whether to allow using an authentication key which use
     *         count has been exceeded if no other key is available.
     */
    public boolean isAllowUsingExhaustedKeys() {
        return mAllowUsingExhaustedKeys;
    }

    /**
     * Gets whether to allow using an authentication key which is expired.
     *
     * <p>By default this is set to false.
     *
     * @return whether to allow using an authentication key which is
     *         expired if no other key is available.
     */
    public boolean isAllowUsingExpiredKeys() {
        return mAllowUsingExpiredKeys;
    }

    /**
     * Gets whether to increment the use-count for the authentication key used.
     *
     * <p>By default this is set to true.
     *
     * @return whether to increment the use count of the authentication key used.
     */
    public boolean isIncrementUseCount() {
        return mIncrementUseCount;
    }

    /**
     * Gets the request message CBOR.
     *
     * <p>This data structure is described in the documentation for the
     * {@link PresentationSession#getCredentialData(String, CredentialDataRequest)} method.
     *
     * @return the request message CBOR as described above.
     */
    public @Nullable byte[] getRequestMessage() {
        return mRequestMessage;
    }

    /**
     * Gets the reader signature.
     *
     * <p>This data structure is described in the documentation for the
     * {@link PresentationSession#getCredentialData(String, CredentialDataRequest)} method.
     *
     * @return a {@code COSE_Sign1} structure as described above.
     */
    public @Nullable byte[] getReaderSignature() {
        return mReaderSignature;
    }

    Map<String, Collection<String>> mDeviceSignedEntriesToRequest = new LinkedHashMap<>();
    Map<String, Collection<String>> mIssuerSignedEntriesToRequest = new LinkedHashMap<>();
    boolean mAllowUsingExhaustedKeys = true;
    boolean mAllowUsingExpiredKeys = false;
    boolean mIncrementUseCount = true;
    byte[] mRequestMessage = null;
    byte[] mReaderSignature = null;

    /**
     * A builder for {@link CredentialDataRequest}.
     */
    public static final class Builder {
        private CredentialDataRequest mData;

        /**
         * Creates a new builder.
         */
        public Builder() {
            mData = new CredentialDataRequest();
        }

        /**
         * Sets the device-signed entries to request.
         *
         * @param entriesToRequest the device-signed entries to request.
         */
        public @NonNull Builder setDeviceSignedEntriesToRequest(
                @NonNull Map<String, Collection<String>> entriesToRequest) {
            mData.mDeviceSignedEntriesToRequest = entriesToRequest;
            return this;
        }

        /**
         * Sets the issuer-signed entries to request.
         *
         * @param entriesToRequest the issuer-signed entries to request.
         * @return the builder.
         */
        public @NonNull Builder setIssuerSignedEntriesToRequest(
                @NonNull Map<String, Collection<String>> entriesToRequest) {
            mData.mIssuerSignedEntriesToRequest = entriesToRequest;
            return this;
        }

        /**
         * Sets whether to allow using an authentication key which use count has been exceeded.
         *
         * <p>This is useful in situations where the application hasn't had a chance to renew
         * authentication keys, for example if the device hasn't been connected to the Internet or
         * if the issuing authority server has been down.
         *
         * <p>The reason this could be useful is that the privacy risk of reusing an authentication
         * key for a credential presentation could be significantly smaller compared to the
         * inconvenience of not being able to present the credential at all.
         *
         * <p>By default this is set to true.
         *
         * @param allowUsingExhaustedKeys whether to allow using an authentication key which use
         *                                count has been exceeded if no other key is available.
         * @return the builder.
         */
        public @NonNull Builder setAllowUsingExhaustedKeys(boolean allowUsingExhaustedKeys) {
            mData.mAllowUsingExhaustedKeys = allowUsingExhaustedKeys;
            return this;
        }

        /**
         * Sets whether to allow using an authentication key which is expired.
         *
         * <p>This is useful in situations where the application hasn't had a chance to renew
         * authentication keys, for example if the device hasn't been connected to the Internet or
         * if the issuing authority server has been down.
         *
         * <p>The reason this could be useful is that many verifiers are likely to accept a
         * credential presentation using an expired authentication key (the credential itself
         * wouldn't be expired) and it's likely better for the holder to be able to do this than
         * not present their credential at all.
         *
         * <p>By default this is set to false.
         *
         * @param allowUsingExpiredKeys whether to allow using an authentication key which is
         *                              expired if no other key is available.
         * @return the builder.
         */
        public @NonNull Builder setAllowUsingExpiredKeys(boolean allowUsingExpiredKeys) {
            mData.mAllowUsingExpiredKeys = allowUsingExpiredKeys;
            return this;
        }

        /**
         * Sets whether to increment the use-count for the authentication key used.
         *
         * <p>Not incrementing the use-count for an authentication key is useful in situations
         * where the authentication key is known with certainty to not be leaked. For example,
         * consider an application doing a credential presentation for the sole purpose of
         * displaying the credential data to the user (not for verification).
         *
         * <p>By default this is set to true.
         *
         * @param incrementUseCount whether to increment the use count of the authentication
         *                          key used.
         * @return the builder.
         */
        public @NonNull Builder setIncrementUseCount(boolean incrementUseCount) {
            mData.mIncrementUseCount = incrementUseCount;
            return this;
        }

        /**
         * Sets the request message CBOR.
         *
         * <p>This data structure is described in the documentation for the
         * {@link PresentationSession#getCredentialData(String, CredentialDataRequest)} method.
         *
         * @param requestMessage the request message CBOR as described above.
         * @return the builder.
         */
        public @NonNull Builder setRequestMessage(@NonNull byte[] requestMessage) {
            mData.mRequestMessage = requestMessage;
            return this;
        }

        /**
         * Sets the reader signature.
         *
         * <p>This data structure is described in the documentation for the
         * {@link PresentationSession#getCredentialData(String, CredentialDataRequest)} method.
         *
         * @param readerSignature a {@code COSE_Sign1} structure as described above.
         * @return the builder.
         */
        public @NonNull Builder setReaderSignature(@NonNull byte[] readerSignature) {
            mData.mReaderSignature = readerSignature;
            return this;
        }

        /**
         * Finishes building a {@link CredentialDataRequest}.
         *
         * @return the {@link CredentialDataRequest} object.
         */
        public @NonNull CredentialDataRequest build() {
            return mData;
        }
    }
}
