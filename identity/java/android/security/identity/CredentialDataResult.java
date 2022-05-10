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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.util.Collection;


/**
 * An object that contains the result of retrieving data from a credential. This is used to return
 * data requested in a {@link PresentationSession}.
 */
public abstract class CredentialDataResult {
    /**
     * @hide
     */
    protected CredentialDataResult() {}

    /**
     * Returns a CBOR structure containing the retrieved device-signed data.
     *
     * <p>This structure - along with the session transcript - may be cryptographically
     * authenticated to prove to the reader that the data is from a trusted credential and
     * {@link #getDeviceMac()} can be used to get a MAC.
     *
     * <p>The CBOR structure which is cryptographically authenticated is the
     * {@code DeviceAuthenticationBytes} structure according to the following
     * <a href="https://tools.ietf.org/html/rfc8610">CDDL</a> schema:
     *
     * <pre>
     *   DeviceAuthentication = [
     *     "DeviceAuthentication",
     *     SessionTranscript,
     *     DocType,
     *     DeviceNameSpacesBytes
     *   ]
     *
     *   DocType = tstr
     *   SessionTranscript = any
     *   DeviceNameSpacesBytes = #6.24(bstr .cbor DeviceNameSpaces)
     *   DeviceAuthenticationBytes = #6.24(bstr .cbor DeviceAuthentication)
     * </pre>
     *
     * <p>where
     *
     * <pre>
     *   DeviceNameSpaces = {
     *     * NameSpace => DeviceSignedItems
     *   }
     *
     *   DeviceSignedItems = {
     *     + DataItemName => DataItemValue
     *   }
     *
     *   NameSpace = tstr
     *   DataItemName = tstr
     *   DataItemValue = any
     * </pre>
     *
     * <p>The returned data is the binary encoding of the {@code DeviceNameSpaces} structure
     * as defined above.
     *
     * @return The bytes of the {@code DeviceNameSpaces} CBOR structure.
     */
    public abstract @NonNull byte[] getDeviceNameSpaces();

    /**
     * Returns a message authentication code over the {@code DeviceAuthenticationBytes} CBOR
     * specified in {@link #getDeviceNameSpaces()}, to prove to the reader that the data
     * is from a trusted credential.
     *
     * <p>The MAC proves to the reader that the data is from a trusted credential. This code is
     * produced by using the key agreement and key derivation function from the ciphersuite
     * with the authentication private key and the reader ephemeral public key to compute a
     * shared message authentication code (MAC) key, then using the MAC function from the
     * ciphersuite to compute a MAC of the authenticated data. See section 9.2.3.5 of
     * ISO/IEC 18013-5 for details of this operation.
     *
     * <p>If the session transcript or reader ephemeral key wasn't set on the {@link
     * PresentationSession} used to obtain this data no message authencation code will be produced
     * and this method will return {@code null}.
     *
     * @return A COSE_Mac0 structure with the message authentication code as described above
     *         or {@code null} if the conditions specified above are not met.
     */
    public abstract @Nullable byte[] getDeviceMac();

    /**
     * Returns the static authentication data associated with the dynamic authentication
     * key used to MAC the data returned by {@link #getDeviceNameSpaces()}.
     *
     * @return The static authentication data associated with dynamic authentication key used to
     * MAC the data.
     */
    public abstract @NonNull byte[] getStaticAuthenticationData();

    /**
     * Gets the device-signed entries that was returned.
     *
     * @return an object to examine the entries returned.
     */
    public abstract @NonNull Entries getDeviceSignedEntries();

    /**
     * Gets the issuer-signed entries that was returned.
     *
     * @return an object to examine the entries returned.
     */
    public abstract @NonNull Entries getIssuerSignedEntries();

    /**
     * A class for representing data elements returned.
     */
    public interface Entries {
        /** Value was successfully retrieved. */
        int STATUS_OK = 0;

        /** The entry does not exist. */
        int STATUS_NO_SUCH_ENTRY = 1;

        /** The entry was not requested. */
        int STATUS_NOT_REQUESTED = 2;

        /** The entry wasn't in the request message. */
        int STATUS_NOT_IN_REQUEST_MESSAGE = 3;

        /** The entry was not retrieved because user authentication failed. */
        int STATUS_USER_AUTHENTICATION_FAILED = 4;

        /** The entry was not retrieved because reader authentication failed. */
        int STATUS_READER_AUTHENTICATION_FAILED = 5;

        /**
         * The entry was not retrieved because it was configured without any access
         * control profile.
         */
        int STATUS_NO_ACCESS_CONTROL_PROFILES = 6;

        /**
         * Gets the names of namespaces with retrieved entries.
         *
         * @return collection of name of namespaces containing retrieved entries. May be empty if no
         *     data was retrieved.
         */
        @NonNull Collection<String> getNamespaces();

        /**
         * Get the names of all requested entries in a name space.
         *
         * <p>This includes the name of entries that wasn't successfully retrieved.
         *
         * @param namespaceName the namespace name to get entries for.
         * @return A collection of names for the given namespace or the empty collection if no
         *   entries was returned for the given name space.
         */
        @NonNull Collection<String> getEntryNames(@NonNull String namespaceName);

        /**
         * Get the names of all entries that was successfully retrieved from a name space.
         *
         * <p>This only return entries for which {@link #getStatus(String, String)} will return
         * {@link #STATUS_OK}.
         *
         * @param namespaceName the namespace name to get entries for.
         * @return The entries in the given namespace that were successfully rerieved or the
         *   empty collection if no entries was returned for the given name space.
         */
        @NonNull Collection<String> getRetrievedEntryNames(@NonNull String namespaceName);

        /**
         * Gets the status of an entry.
         *
         * <p>This returns {@link #STATUS_OK} if the value was retrieved, {@link
         * #STATUS_NO_SUCH_ENTRY} if the given entry wasn't retrieved, {@link
         * #STATUS_NOT_REQUESTED} if it wasn't requested, {@link #STATUS_NOT_IN_REQUEST_MESSAGE} if
         * the request message was set but the entry wasn't present in the request message, {@link
         * #STATUS_USER_AUTHENTICATION_FAILED} if the value wasn't retrieved because the necessary
         * user authentication wasn't performed, {@link #STATUS_READER_AUTHENTICATION_FAILED} if
         * the supplied reader certificate chain didn't match the set of certificates the entry was
         * provisioned with, or {@link #STATUS_NO_ACCESS_CONTROL_PROFILES} if the entry was
         * configured without any access control profiles.
         *
         * @param namespaceName the namespace name of the entry.
         * @param name the name of the entry to get the value for.
         * @return the status indicating whether the value was retrieved and if not, why.
         */
        @Status int getStatus(@NonNull String namespaceName, @NonNull String name);

        /**
         * Gets the raw CBOR data for the value of an entry.
         *
         * <p>This should only be called on an entry for which the {@link #getStatus(String,
         * String)} method returns {@link #STATUS_OK}.
         *
         * @param namespaceName the namespace name of the entry.
         * @param name the name of the entry to get the value for.
         * @return the raw CBOR data or {@code null} if no entry with the given name exists.
         */
        @Nullable byte[] getEntry(@NonNull String namespaceName, @NonNull String name);

        /**
         * The type of the entry status.
         * @hide
         */
        @Retention(SOURCE)
        @IntDef({STATUS_OK, STATUS_NO_SUCH_ENTRY, STATUS_NOT_REQUESTED,
                    STATUS_NOT_IN_REQUEST_MESSAGE, STATUS_USER_AUTHENTICATION_FAILED,
                    STATUS_READER_AUTHENTICATION_FAILED, STATUS_NO_ACCESS_CONTROL_PROFILES})
                    @interface Status {}
    }

}
