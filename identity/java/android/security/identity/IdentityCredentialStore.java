/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An interface to a secure store for user identity documents.
 *
 * <p>This interface is deliberately fairly general and abstract.  To the extent possible,
 * specification of the message formats and semantics of communication with credential
 * verification devices and issuing authorities (IAs) is out of scope. It provides the
 * interface with secure storage but a credential-specific Android application will be
 * required to implement the presentation and verification protocols and processes
 * appropriate for the specific credential type.
 *
 * <p>Multiple credentials can be created.  Each credential comprises:</p>
 * <ul>
 * <li>A document type, which is a string.</li>
 *
 * <li>A set of namespaces, which serve to disambiguate value names. It is recommended
 * that namespaces be structured as reverse domain names so that IANA effectively serves
 * as the namespace registrar.</li>
 *
 * <li>For each namespace, a set of name/value pairs, each with an associated set of
 * access control profile IDs.  Names are strings and values are typed and can be any
 * value supported by <a href="http://cbor.io/">CBOR</a>.</li>
 *
 * <li>A set of access control profiles, each with a profile ID and a specification
 * of the conditions which satisfy the profile's requirements.</li>
 *
 * <li>An asymmetric key pair which is used to authenticate the credential to the Issuing
 * Authority, called the <em>CredentialKey</em>.</li>
 *
 * <li>A set of zero or more named reader authentication public keys, which are used to
 * authenticate an authorized reader to the credential.</li>
 *
 * <li>A set of named signing keys, which are used to sign collections of values and session
 * transcripts.</li>
 * </ul>
 *
 * <p>Implementing support for user identity documents in secure storage requires dedicated
 * hardware-backed support and may not always be available.
 *
 * <p>Two different credential stores exist - the <em>default</em> store and the
 * <em>direct access</em> store. Most often credentials will be accessed through the default
 * store but that requires that the Android device be powered up and fully functional.
 * It is desirable to allow identity credential usage when the Android device's battery is too
 * low to boot the Android operating system, so direct access to the secure hardware via NFC
 * may allow data retrieval, if the secure hardware chooses to implement it.
 *
 * <p>Credentials provisioned to the direct access store should <strong>always</strong> use reader
 * authentication to protect data elements. The reason for this is user authentication or user
 * approval of data release is not possible when the device is off.
 */
public abstract class IdentityCredentialStore {
    IdentityCredentialStore() {}

    /**
     * Specifies that the cipher suite that will be used to secure communications between the reader
     * is:
     *
     * <ul>
     * <li>ECDHE with HKDF-SHA-256 for key agreement.</li>
     * <li>AES-256 with GCM block mode for authenticated encryption (nonces are incremented by one
     * for every message).</li>
     * <li>ECDSA with SHA-256 for signing (used for signing session transcripts to defeat
     * man-in-the-middle attacks), signing keys are not ephemeral. See {@link IdentityCredential}
     * for details on reader and prover signing keys.</li>
     * </ul>
     *
     * <p>
     * At present this is the only supported cipher suite.
     */
    public static final int CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256 = 1;

    /**
     * Gets the default {@link IdentityCredentialStore}.
     *
     * @param context the application context.
     * @return the {@link IdentityCredentialStore} or {@code null} if the device doesn't
     *     have hardware-backed support for secure storage of user identity documents.
     */
    public static @Nullable IdentityCredentialStore getInstance(@NonNull Context context) {
        return CredstoreIdentityCredentialStore.getInstance(context);
    }

    /**
     * Gets the {@link IdentityCredentialStore} for direct access.
     *
     * <p>Direct access requires specialized NFC hardware and may not be supported on all
     * devices even if default store is available. Credentials provisioned to the direct
     * access store should <strong>always</strong> use reader authentication to protect
     * data elements.
     *
     * @param context the application context.
     * @return the {@link IdentityCredentialStore} or {@code null} if direct access is not
     *     supported on this device.
     */
    public static @Nullable IdentityCredentialStore getDirectAccessInstance(@NonNull
            Context context) {
        return CredstoreIdentityCredentialStore.getDirectAccessInstance(context);
    }

    /**
     * Gets a list of supported document types.
     *
     * <p>Only the direct-access store may restrict the kind of document types that can be used for
     * credentials. The default store always supports any document type.
     *
     * @return The supported document types or the empty array if any document type is supported.
     */
    public abstract @NonNull String[] getSupportedDocTypes();

    /**
     * Creates a new credential.
     *
     * @param credentialName The name used to identify the credential.
     * @param docType        The document type for the credential.
     * @return A @{link WritableIdentityCredential} that can be used to create a new credential.
     * @throws AlreadyPersonalizedException if a credential with the given name already exists.
     * @throws DocTypeNotSupportedException if the given document type isn't supported by the store.
     */
    public abstract @NonNull WritableIdentityCredential createCredential(
            @NonNull String credentialName, @NonNull String docType)
            throws AlreadyPersonalizedException, DocTypeNotSupportedException;

    /**
     * Retrieve a named credential.
     *
     * @param credentialName the name of the credential to retrieve.
     * @param cipherSuite    the cipher suite to use for communicating with the verifier.
     * @return The named credential, or null if not found.
     */
    public abstract @Nullable IdentityCredential getCredentialByName(@NonNull String credentialName,
            @Ciphersuite int cipherSuite)
            throws CipherSuiteNotSupportedException;

    /**
     * Delete a named credential.
     *
     * <p>This method returns a COSE_Sign1 data structure signed by the CredentialKey
     * with payload set to {@code ProofOfDeletion} as defined below:
     *
     * <pre>
     *     ProofOfDeletion = [
     *          "ProofOfDeletion",            ; tstr
     *          tstr,                         ; DocType
     *          bool                          ; true if this is a test credential, should
     *                                        ; always be false.
     *      ]
     * </pre>
     *
     * @param credentialName the name of the credential to delete.
     * @return {@code null} if the credential was not found, the COSE_Sign1 data structure above
     *     if the credential was found and deleted.
     */
    public abstract @Nullable byte[] deleteCredentialByName(@NonNull String credentialName);

    /** @hide */
    @IntDef(value = {CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Ciphersuite {
    }

}
