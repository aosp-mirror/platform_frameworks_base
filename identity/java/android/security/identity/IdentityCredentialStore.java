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
 * <li>A set of access control profiles (up to 32), each with a profile ID and a specification
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
 *
 * <p>The Identity Credential API is designed to be able to evolve and change over time
 * but still provide 100% backwards compatibility. This is complicated by the fact that
 * there may be a version skew between the API used by the application and the version
 * implemented in secure hardware. To solve this problem, the API provides for a way
 * for the application to query which feature version the hardware implements (if any
 * at all) using
 * {@link android.content.pm#FEATURE_IDENTITY_CREDENTIAL_HARDWARE} and
 * {@link android.content.pm#FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS}.
 * Methods which only work on certain feature versions are clearly documented as
 * such.
 */
public abstract class IdentityCredentialStore {
    IdentityCredentialStore() {}

    /**
     * Specifies that the cipher suite that will be used to secure communications between the reader
     * and the prover is using the following primitives
     *
     * <ul>
     * <li>ECKA-DH (Elliptic Curve Key Agreement Algorithm - Diffie-Hellman, see BSI TR-03111).</li>
     *
     * <li>HKDF-SHA-256 (see RFC 5869).</li>
     *
     * <li>AES-256-GCM (see NIST SP 800-38D).</li>
     *
     * <li>HMAC-SHA-256 (see RFC 2104).</li>
     * </ul>
     *
     * <p>The exact way these primitives are combined to derive the session key is specified in
     * section 9.2.1.4 of ISO/IEC 18013-5 (see description of cipher suite '1').<p>
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
     * <p>When a credential is created, a cryptographic key-pair - CredentialKey - is created which
     * is used to authenticate the store to the Issuing Authority.  The private part of this
     * key-pair never leaves secure hardware and the public part can be obtained using
     * {@link WritableIdentityCredential#getCredentialKeyCertificateChain(byte[])} on the
     * returned object.
     *
     * <p>In addition, all of the Credential data content is imported and a certificate for the
     * CredentialKey and a signature produced with the CredentialKey are created.  These latter
     * values may be checked by an issuing authority to verify that the data was imported into
     * secure hardware and that it was imported unmodified.
     *
     * @param credentialName The name used to identify the credential.
     * @param docType        The document type for the credential.
     * @return A {@link WritableIdentityCredential} that can be used to create a new credential.
     * @throws AlreadyPersonalizedException if a credential with the given name already exists.
     * @throws DocTypeNotSupportedException if the given document type isn't supported by the store.
     */
    public abstract @NonNull WritableIdentityCredential createCredential(
            @NonNull String credentialName, @NonNull String docType)
            throws AlreadyPersonalizedException, DocTypeNotSupportedException;

    /**
     * Retrieve a named credential.
     *
     * <p>The cipher suite used to communicate with the remote verifier must also be specified.
     * Currently only a single cipher-suite is supported. Support for other cipher suites may be
     * added in a future version of this API.
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
     * @deprecated Use {@link IdentityCredential#delete(byte[])} instead.
     */
    @Deprecated
    public abstract @Nullable byte[] deleteCredentialByName(@NonNull String credentialName);

    /**
     * Creates a new presentation session.
     *
     * <p>This method gets an object to be used for interaction with a remote verifier for
     * presentation of one or more credentials.
     *
     * <p>This is only implemented in feature version 202201 or later. If not implemented, the call
     * fails with {@link UnsupportedOperationException}. See
     * {@link android.content.pm.PackageManager#FEATURE_IDENTITY_CREDENTIAL_HARDWARE} for known
     * feature versions.
     *
     * @param cipherSuite    the cipher suite to use for communicating with the verifier.
     * @return The presentation session.
     */
    public @NonNull PresentationSession createPresentationSession(@Ciphersuite int cipherSuite)
            throws CipherSuiteNotSupportedException {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    @IntDef(value = {CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Ciphersuite {
    }
}
