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

import android.annotation.NonNull;

import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * Class used to personalize a new identity credential.
 *
 * <p>Credentials cannot be updated or modified after creation; any changes require deletion and
 * re-creation.
 *
 * Use {@link IdentityCredentialStore#createCredential(String, String)} to create a new credential.
 */
public abstract class WritableIdentityCredential {
    /**
     * @hide
     */
    protected WritableIdentityCredential() {}

    /**
     * Generates and returns an X.509 certificate chain for the CredentialKey which identifies this
     * credential to the issuing authority. The certificate contains an
     * <a href="https://source.android.com/security/keystore/attestation">Android Keystore</a>
     * attestation extension which describes the key and the security hardware in which it lives.
     *
     * <p>Additionally, the attestation extension will contain the tag Tag::IDENTITY_CREDENTIAL_KEY
     * which indicates it is an Identity Credential key (which can only sign/MAC very specific
     * messages) and not an Android Keystore key (which can be used to sign/MAC anything).
     *
     * <p>The issuer <b>MUST</b> carefully examine this certificate chain including (but not
     * limited to) checking that the root certificate is well-known, the tag
     * Tag::IDENTITY_CREDENTIAL_KEY present, the passed in challenge is present, the tag
     * Tag::ATTESTATION_APPLICATION_ID is set to the expected Android application, the device
     * has verified boot enabled, each certificate in the chain is signed by its successor,
     * none of the certificates have been revoked, and so on.
     *
     * <p>It is not strictly necessary to use this method to provision a credential if the issuing
     * authority doesn't care about the nature of the security hardware. If called, however, this
     * method must be called before {@link #personalize(PersonalizationData)}.
     *
     * @param challenge is a non-empty byte array whose contents should be unique, fresh and
     *                  provided by the issuing authority. The value provided is embedded in the
     *                  attestation extension and enables the issuing authority to verify that the
     *                  attestation certificate is fresh. Implementations are required to support
     *                  challenges at least 32 bytes of length.
     * @return the X.509 certificate for this credential's CredentialKey.
     */
    public abstract @NonNull Collection<X509Certificate> getCredentialKeyCertificateChain(
            @NonNull byte[] challenge);

    /**
     * Stores all of the data in the credential, with the specified access control profiles.
     *
     * <p>This method returns a COSE_Sign1 data structure signed by the CredentialKey with payload
     * set to {@code ProofOfProvisioning} as defined below.
     *
     * <pre>
     *     ProofOfProvisioning = [
     *          "ProofOfProvisioning",        ; tstr
     *          tstr,                         ; DocType
     *          [ * AccessControlProfile ],
     *          ProvisionedData,
     *          bool                          ; true if this is a test credential, should
     *                                        ; always be false.
     *      ]
     *
     *      AccessControlProfile = {
     *          "id": uint,
     *          ? "readerCertificate" : bstr,
     *          ? (
     *               "userAuthenticationRequired" : bool,
     *               "timeoutMillis" : uint,
     *          )
     *      }
     *
     *      ProvisionedData = {
     *          * Namespace =&gt; [ + Entry ]
     *      },
     *
     *      Namespace = tstr
     *
     *      Entry = {
     *          "name" : tstr,
     *          "value" : any,
     *          "accessControlProfiles" : [ * uint ],
     *      }
     * </pre>
     *
     * <p>This data structure provides a guarantee to the issuer about the data which may be
     * returned in the CBOR returned by
     * {@link ResultData#getAuthenticatedData()} during a credential
     * presentation.
     *
     * @param personalizationData   The data to provision, including access control profiles
     *                              and data elements and their values, grouped into namespaces.
     * @return A COSE_Sign1 data structure, see above.
     */
    public abstract @NonNull byte[] personalize(
            @NonNull PersonalizationData personalizationData);
}
