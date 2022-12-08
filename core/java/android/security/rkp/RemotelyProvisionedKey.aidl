/*
 * Copyright 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.rkp;

/**
 * A {@link RemotelyProvisionedKey} holds an attestation key and the
 * corresponding remotely provisioned certificate chain.
 *
 * @hide
 */
@RustDerive(Eq=true, PartialEq=true)
parcelable RemotelyProvisionedKey {
    /**
     * The remotely-provisioned key that may be used to sign attestations. The
     * format of this key is opaque, and need only be understood by the
     * IRemotelyProvisionedComponent that generated it.
     *
     * Any private key material contained within this blob must be encrypted.
     *
     * @see IRemotelyProvisionedComponent
     */
    byte[] keyBlob;

    /**
     * Sequence of DER-encoded X.509 certificates that make up the attestation
     * key's certificate chain. This is the binary encoding for a chain that is
     * supported by Java's CertificateFactory.generateCertificates API.
     */
    byte[] encodedCertChain;
}
