/*
 * Copyright (C) 2017 The Android Open Source Project
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


package android.security.keystore;

import java.security.KeyStore.Entry;
import java.security.spec.AlgorithmParameterSpec;

/**
 * An {@link Entry} that holds a wrapped key. Wrapped keys contain encrypted key data and
 * description information that can be used to securely import key material into a hardware-backed
 * Keystore.
 *
 * <p>
 *   The wrapped key is in DER-encoded ASN.1 format, specified by the following schema:
 * </p>
 *
 * <pre>
 *     KeyDescription ::= SEQUENCE(
 *         keyFormat INTEGER,                   # Values from KeyFormat enum.
 *         keyParams AuthorizationList,
 *     )
 *
 *     SecureKeyWrapper ::= SEQUENCE(
 *         version INTEGER,                     # Contains value 0
 *         encryptedTransportKey OCTET_STRING,
 *         initializationVector OCTET_STRING,
 *         keyDescription KeyDescription,
 *         encryptedKey OCTET_STRING,
 *         tag OCTET_STRING
 *     )
 * </pre>
 * <ul>
 *     <li>keyFormat is an integer from the KeyFormat enum, defining the format of the plaintext
 *       key material.
 *     </li>
 *     <li>keyParams is the characteristics of the key to be imported (as with generateKey or
 *       importKey).  If the secure import is successful, these characteristics must be
 *       associated with the key exactly as if the key material had been insecurely imported
 *       with importKey. See <a href="https://developer.android.com/training/articles/security-key-attestation.html#certificate_schema">Key Attestation</a> for the AuthorizationList format.
 *     </li>
 *     <li>encryptedTransportKey is a 256-bit AES key, XORed with a masking key and then encrypted
 *       in RSA-OAEP mode (SHA-256 digest, SHA-1 MGF1 digest) with the wrapping key specified by
 *       wrappingKeyBlob.
 *     </li>
 *     <li>keyDescription is a KeyDescription, above.
 *     </li>
 *     <li>encryptedKey is the key material of the key to be imported, in format keyFormat, and
 *       encrypted with encryptedEphemeralKey in AES-GCM mode, with the DER-encoded
 *       representation of keyDescription provided as additional authenticated data.
 *     </li>
 *     <li>tag is the tag produced by the AES-GCM encryption of encryptedKey.
 *     </li>
 *</ul>
 *
 * <p>
 *     Imported wrapped keys will have KeymasterDefs.KM_ORIGIN_SECURELY_IMPORTED
 * </p>
 */
public class WrappedKeyEntry implements Entry {

    private final byte[] mWrappedKeyBytes;
    private final String mWrappingKeyAlias;
    private final String mTransformation;
    private final AlgorithmParameterSpec mAlgorithmParameterSpec;

    /**
     * Constructs a {@link WrappedKeyEntry} with a binary wrapped key.
     *
     * @param wrappedKeyBytes ASN.1 DER encoded wrapped key
     * @param wrappingKeyAlias identifies the private key that can unwrap the wrapped key
     * @param transformation used to unwrap the key. ex: "RSA/ECB/OAEPPadding"
     * @param algorithmParameterSpec spec for the private key used to unwrap the wrapped key
     */
    public WrappedKeyEntry(byte[] wrappedKeyBytes, String wrappingKeyAlias, String transformation,
            AlgorithmParameterSpec algorithmParameterSpec) {
        mWrappedKeyBytes = wrappedKeyBytes;
        mWrappingKeyAlias = wrappingKeyAlias;
        mTransformation = transformation;
        mAlgorithmParameterSpec = algorithmParameterSpec;
    }

    public byte[] getWrappedKeyBytes() {
        return mWrappedKeyBytes;
    }

    public String getWrappingKeyAlias() {
        return mWrappingKeyAlias;
    }

    public String getTransformation() {
        return mTransformation;
    }

    public AlgorithmParameterSpec getAlgorithmParameterSpec() {
        return mAlgorithmParameterSpec;
    }
}

