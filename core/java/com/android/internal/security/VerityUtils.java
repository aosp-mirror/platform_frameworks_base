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

package com.android.internal.security;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.os.SystemProperties;
import android.system.Os;
import android.system.OsConstants;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import com.android.internal.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.internal.org.bouncycastle.cms.CMSException;
import com.android.internal.org.bouncycastle.cms.CMSProcessableByteArray;
import com.android.internal.org.bouncycastle.cms.CMSSignedData;
import com.android.internal.org.bouncycastle.cms.SignerInformation;
import com.android.internal.org.bouncycastle.cms.SignerInformationVerifier;
import com.android.internal.org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import com.android.internal.org.bouncycastle.operator.OperatorCreationException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** Provides fsverity related operations. */
public abstract class VerityUtils {
    private static final String TAG = "VerityUtils";

    /**
     * File extension of the signature file. For example, foo.apk.fsv_sig is the signature file of
     * foo.apk.
     */
    public static final String FSVERITY_SIGNATURE_FILE_EXTENSION = ".fsv_sig";

    /** The maximum size of signature file.  This is just to avoid potential abuse. */
    private static final int MAX_SIGNATURE_FILE_SIZE_BYTES = 8192;

    /** SHA256 hash size. */
    private static final int HASH_SIZE_BYTES = 32;

    public static boolean isFsVeritySupported() {
        return Build.VERSION.DEVICE_INITIAL_SDK_INT >= Build.VERSION_CODES.R
                || SystemProperties.getInt("ro.apk_verity.mode", 0) == 2;
    }

    /** Returns true if the given file looks like containing an fs-verity signature. */
    public static boolean isFsveritySignatureFile(File file) {
        return file.getName().endsWith(FSVERITY_SIGNATURE_FILE_EXTENSION);
    }

    /** Returns the fs-verity signature file path of the given file. */
    public static String getFsveritySignatureFilePath(String filePath) {
        return filePath + FSVERITY_SIGNATURE_FILE_EXTENSION;
    }

    /** Enables fs-verity for the file with an optional PKCS#7 detached signature file. */
    public static void setUpFsverity(@NonNull String filePath, @Nullable String signaturePath)
            throws IOException {
        byte[] rawSignature = null;
        if (signaturePath != null) {
            Path path = Paths.get(signaturePath);
            if (Files.size(path) > MAX_SIGNATURE_FILE_SIZE_BYTES) {
                throw new SecurityException("Signature file is unexpectedly large: "
                        + signaturePath);
            }
            rawSignature = Files.readAllBytes(path);
        }
        setUpFsverity(filePath, rawSignature);
    }

    /** Enables fs-verity for the file with an optional PKCS#7 detached signature bytes. */
    public static void setUpFsverity(@NonNull String filePath, @Nullable byte[] pkcs7Signature)
            throws IOException {
        // This will fail if the public key is not already in .fs-verity kernel keyring.
        int errno = enableFsverityNative(filePath, pkcs7Signature);
        if (errno != 0) {
            throw new IOException("Failed to enable fs-verity on " + filePath + ": "
                    + Os.strerror(errno));
        }
    }

    /** Returns whether the file has fs-verity enabled. */
    public static boolean hasFsverity(@NonNull String filePath) {
        int retval = statxForFsverityNative(filePath);
        if (retval < 0) {
            Slog.e(TAG, "Failed to check whether fs-verity is enabled, errno " + -retval + ": "
                    + filePath);
            return false;
        }
        return (retval == 1);
    }

    /**
     * Verifies the signature over the fs-verity digest using the provided certificate.
     *
     * This method should only be used by any existing fs-verity use cases that require
     * PKCS#7 signature verification, if backward compatibility is necessary.
     *
     * Since PKCS#7 is too flexible, for the current specific need, only specific configuration
     * will be accepted:
     * <ul>
     *   <li>Must use SHA256 as the digest algorithm
     *   <li>Must use rsaEncryption as signature algorithm
     *   <li>Must be detached / without content
     *   <li>Must not include any signed or unsigned attributes
     * </ul>
     *
     * It is up to the caller to provide an appropriate/trusted certificate.
     *
     * @param signatureBlock byte array of a PKCS#7 detached signature
     * @param digest fs-verity digest with the common configuration using sha256
     * @param derCertInputStream an input stream of a X.509 certificate in DER
     * @return whether the verification succeeds
     */
    public static boolean verifyPkcs7DetachedSignature(@NonNull byte[] signatureBlock,
            @NonNull byte[] digest, @NonNull InputStream derCertInputStream) {
        if (digest.length != 32) {
            Slog.w(TAG, "Only sha256 is currently supported");
            return false;
        }

        try {
            CMSSignedData signedData = new CMSSignedData(
                    new CMSProcessableByteArray(toFormattedDigest(digest)),
                    signatureBlock);

            if (!signedData.isDetachedSignature()) {
                Slog.w(TAG, "Expect only detached siganture");
                return false;
            }
            if (!signedData.getCertificates().getMatches(null).isEmpty()) {
                Slog.w(TAG, "Expect no certificate in signature");
                return false;
            }
            if (!signedData.getCRLs().getMatches(null).isEmpty()) {
                Slog.w(TAG, "Expect no CRL in signature");
                return false;
            }

            X509Certificate trustedCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(derCertInputStream);
            SignerInformationVerifier verifier = new JcaSimpleSignerInfoVerifierBuilder()
                    .build(trustedCert);

            // Verify any signature with the trusted certificate.
            for (SignerInformation si : signedData.getSignerInfos().getSigners()) {
                // To be the most strict while dealing with the complicated PKCS#7 signature, reject
                // everything we don't need.
                if (si.getSignedAttributes() != null && si.getSignedAttributes().size() > 0) {
                    Slog.w(TAG, "Unexpected signed attributes");
                    return false;
                }
                if (si.getUnsignedAttributes() != null && si.getUnsignedAttributes().size() > 0) {
                    Slog.w(TAG, "Unexpected unsigned attributes");
                    return false;
                }
                if (!NISTObjectIdentifiers.id_sha256.getId().equals(si.getDigestAlgOID())) {
                    Slog.w(TAG, "Unsupported digest algorithm OID: " + si.getDigestAlgOID());
                    return false;
                }
                if (!PKCSObjectIdentifiers.rsaEncryption.getId().equals(si.getEncryptionAlgOID())) {
                    Slog.w(TAG, "Unsupported encryption algorithm OID: "
                            + si.getEncryptionAlgOID());
                    return false;
                }

                if (si.verify(verifier)) {
                    return true;
                }
            }
            return false;
        } catch (CertificateException | CMSException | OperatorCreationException e) {
            Slog.w(TAG, "Error occurred during the PKCS#7 signature verification", e);
        }
        return false;
    }

    /**
     * Returns fs-verity digest for the file if enabled, otherwise returns null. The digest is a
     * hash of root hash of fs-verity's Merkle tree with extra metadata.
     *
     * @see <a href="https://www.kernel.org/doc/html/latest/filesystems/fsverity.html#file-digest-computation">
     *      File digest computation in Linux kernel documentation</a>
     * @return Bytes of fs-verity digest
     */
    public static byte[] getFsverityDigest(@NonNull String filePath) {
        byte[] result = new byte[HASH_SIZE_BYTES];
        int retval = measureFsverityNative(filePath, result);
        if (retval < 0) {
            if (retval != -OsConstants.ENODATA) {
                Slog.e(TAG, "Failed to measure fs-verity, errno " + -retval + ": " + filePath);
            }
            return null;
        }
        return result;
    }

    /** @hide */
    @VisibleForTesting
    public static byte[] toFormattedDigest(byte[] digest) {
        // Construct fsverity_formatted_digest used in fs-verity's built-in signature verification.
        ByteBuffer buffer = ByteBuffer.allocate(12 + digest.length); // struct size + sha256 size
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("FSVerity".getBytes(StandardCharsets.US_ASCII));
        buffer.putShort((short) 1); // FS_VERITY_HASH_ALG_SHA256
        buffer.putShort((short) digest.length);
        buffer.put(digest);
        return buffer.array();
    }

    private static native int enableFsverityNative(@NonNull String filePath,
            @Nullable byte[] pkcs7Signature);
    private static native int measureFsverityNative(@NonNull String filePath,
            @NonNull byte[] digest);
    private static native int statxForFsverityNative(@NonNull String filePath);
}
