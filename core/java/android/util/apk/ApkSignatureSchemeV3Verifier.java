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

package android.util.apk;

import static android.util.apk.ApkSigningBlockUtils.CONTENT_DIGEST_VERITY_CHUNKED_SHA256;
import static android.util.apk.ApkSigningBlockUtils.compareSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getContentDigestAlgorithmJcaDigestAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getLengthPrefixedSlice;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmContentDigestAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaKeyAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.isSupportedSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.readLengthPrefixedByteArray;
import static android.util.apk.ApkSigningBlockUtils.verifyProofOfRotationStruct;

import android.os.Build;
import android.util.ArrayMap;
import android.util.Pair;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.DigestException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * APK Signature Scheme v3 verifier.
 *
 * @hide for internal use only.
 */
public class ApkSignatureSchemeV3Verifier {

    /**
     * ID of this signature scheme as used in X-Android-APK-Signed header used in JAR signing.
     */
    public static final int SF_ATTRIBUTE_ANDROID_APK_SIGNED_ID = 3;

    static final int APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xf05368c0;
    static final int APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;

    /**
     * Returns {@code true} if the provided APK contains an APK Signature Scheme V3 signature.
     *
     * <p><b>NOTE: This method does not verify the signature.</b>
     */
    public static boolean hasSignature(String apkFile) throws IOException {
        try (RandomAccessFile apk = new RandomAccessFile(apkFile, "r")) {
            findSignature(apk);
            return true;
        } catch (SignatureNotFoundException e) {
            return false;
        }
    }

    /**
     * Verifies APK Signature Scheme v3 signatures of the provided APK and returns the certificates
     * associated with each signer.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v3.
     * @throws SecurityException          if the APK Signature Scheme v3 signature of this APK does
     *                                    not
     *                                    verify.
     * @throws IOException                if an I/O error occurs while reading the APK file.
     */
    public static VerifiedSigner verify(String apkFile)
            throws SignatureNotFoundException, SecurityException, IOException {
        return verify(apkFile, true);
    }

    /**
     * Returns the certificates associated with each signer for the given APK without verification.
     * This method is dangerous and should not be used, unless the caller is absolutely certain the
     * APK is trusted.  Specifically, verification is only done for the APK Signature Scheme v3
     * Block while gathering signer information.  The APK contents are not verified.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v3.
     * @throws IOException                if an I/O error occurs while reading the APK file.
     */
    public static VerifiedSigner unsafeGetCertsWithoutVerification(String apkFile)
            throws SignatureNotFoundException, SecurityException, IOException {
        return verify(apkFile, false);
    }

    private static VerifiedSigner verify(String apkFile, boolean verifyIntegrity)
            throws SignatureNotFoundException, SecurityException, IOException {
        try (RandomAccessFile apk = new RandomAccessFile(apkFile, "r")) {
            return verify(apk, verifyIntegrity);
        }
    }

    /**
     * Verifies APK Signature Scheme v3 signatures of the provided APK and returns the certificates
     * associated with each signer.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v3.
     * @throws SecurityException          if an APK Signature Scheme v3 signature of this APK does
     *                                    not
     *                                    verify.
     * @throws IOException                if an I/O error occurs while reading the APK file.
     */
    private static VerifiedSigner verify(RandomAccessFile apk, boolean verifyIntegrity)
            throws SignatureNotFoundException, SecurityException, IOException {
        ApkSignatureSchemeV3Verifier verifier = new ApkSignatureSchemeV3Verifier(apk,
                verifyIntegrity);
        try {
            SignatureInfo signatureInfo = findSignature(apk, APK_SIGNATURE_SCHEME_V31_BLOCK_ID);
            return verifier.verify(signatureInfo, APK_SIGNATURE_SCHEME_V31_BLOCK_ID);
        } catch (SignatureNotFoundException ignored) {
            // This is expected if the APK is not using v3.1 to target rotation.
        } catch (PlatformNotSupportedException ignored) {
            // This is expected if the APK is targeting a platform version later than that of the
            // device for rotation.
        }
        try {
            SignatureInfo signatureInfo = findSignature(apk, APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
            return verifier.verify(signatureInfo, APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
        } catch (PlatformNotSupportedException e) {
            throw new SecurityException(e);
        }
    }

    /**
     * Returns the APK Signature Scheme v3 block contained in the provided APK file and the
     * additional information relevant for verifying the block against the file.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v3.
     * @throws IOException                if an I/O error occurs while reading the APK file.
     */
    public static SignatureInfo findSignature(RandomAccessFile apk)
            throws IOException, SignatureNotFoundException {
        return findSignature(apk, APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
    }

    /*
     * Returns the APK Signature Scheme v3 block in the provided {@code apk} file with the specified
     * {@code blockId} and the additional information relevant for verifying the block against the
     * file.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v3
     * @throws IOException                if an I/O error occurs while reading the APK file
     **/
    private static SignatureInfo findSignature(RandomAccessFile apk, int blockId)
            throws IOException, SignatureNotFoundException {
        return ApkSigningBlockUtils.findSignature(apk, blockId);
    }

    private final RandomAccessFile mApk;
    private final boolean mVerifyIntegrity;
    private OptionalInt mOptionalRotationMinSdkVersion = OptionalInt.empty();
    private int mSignerMinSdkVersion;
    private int mBlockId;

    private ApkSignatureSchemeV3Verifier(RandomAccessFile apk, boolean verifyIntegrity) {
        mApk = apk;
        mVerifyIntegrity = verifyIntegrity;
    }

    /**
     * Verifies the contents of the provided APK file against the provided APK Signature Scheme v3
     * Block.
     *
     * @param signatureInfo APK Signature Scheme v3 Block and information relevant for verifying it
     *                      against the APK file.
     */
    private VerifiedSigner verify(SignatureInfo signatureInfo, int blockId)
            throws SecurityException, IOException, PlatformNotSupportedException {
        mBlockId = blockId;
        int signerCount = 0;
        Map<Integer, byte[]> contentDigests = new ArrayMap<>();
        Pair<X509Certificate[], ApkSigningBlockUtils.VerifiedProofOfRotation> result = null;
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
        }
        ByteBuffer signers;
        try {
            signers = getLengthPrefixedSlice(signatureInfo.signatureBlock);
        } catch (IOException e) {
            throw new SecurityException("Failed to read list of signers", e);
        }
        while (signers.hasRemaining()) {
            try {
                ByteBuffer signer = getLengthPrefixedSlice(signers);
                result = verifySigner(signer, contentDigests, certFactory);
                signerCount++;
            } catch (PlatformNotSupportedException e) {
                // this signer is for a different platform, ignore it.
                continue;
            } catch (IOException | BufferUnderflowException | SecurityException e) {
                throw new SecurityException(
                        "Failed to parse/verify signer #" + signerCount + " block",
                        e);
            }
        }

        if (signerCount < 1 || result == null) {
            // There must always be a valid signer targeting the device SDK version for a v3.0
            // signature.
            if (blockId == APK_SIGNATURE_SCHEME_V3_BLOCK_ID) {
                throw new SecurityException("No signers found");
            }
            throw new PlatformNotSupportedException(
                    "None of the signers support the current platform version");
        }

        if (signerCount != 1) {
            throw new SecurityException("APK Signature Scheme V3 only supports one signer: "
                    + "multiple signers found.");
        }

        if (contentDigests.isEmpty()) {
            throw new SecurityException("No content digests found");
        }

        if (mVerifyIntegrity) {
            ApkSigningBlockUtils.verifyIntegrity(contentDigests, mApk, signatureInfo);
        }

        byte[] verityRootHash = null;
        if (contentDigests.containsKey(CONTENT_DIGEST_VERITY_CHUNKED_SHA256)) {
            byte[] verityDigest = contentDigests.get(CONTENT_DIGEST_VERITY_CHUNKED_SHA256);
            verityRootHash = ApkSigningBlockUtils.parseVerityDigestAndVerifySourceLength(
                    verityDigest, mApk.getChannel().size(), signatureInfo);
        }

        return new VerifiedSigner(result.first, result.second, verityRootHash, contentDigests,
                blockId);
    }

    private Pair<X509Certificate[], ApkSigningBlockUtils.VerifiedProofOfRotation>
            verifySigner(
                ByteBuffer signerBlock,
                Map<Integer, byte[]> contentDigests,
                CertificateFactory certFactory)
            throws SecurityException, IOException, PlatformNotSupportedException {
        ByteBuffer signedData = getLengthPrefixedSlice(signerBlock);
        int minSdkVersion = signerBlock.getInt();
        int maxSdkVersion = signerBlock.getInt();

        if (Build.VERSION.SDK_INT < minSdkVersion || Build.VERSION.SDK_INT > maxSdkVersion) {
            // if this is a v3.1 block then save the minimum SDK version for rotation for comparison
            // against the v3.0 additional attribute.
            if (mBlockId == APK_SIGNATURE_SCHEME_V31_BLOCK_ID) {
                if (!mOptionalRotationMinSdkVersion.isPresent()
                        || mOptionalRotationMinSdkVersion.getAsInt() > minSdkVersion) {
                    mOptionalRotationMinSdkVersion = OptionalInt.of(minSdkVersion);
                }
            }
            // this signature isn't meant to be used with this platform, skip it.
            throw new PlatformNotSupportedException(
                    "Signer not supported by this platform "
                            + "version. This platform: " + Build.VERSION.SDK_INT
                            + ", signer minSdkVersion: " + minSdkVersion
                            + ", maxSdkVersion: " + maxSdkVersion);
        }

        ByteBuffer signatures = getLengthPrefixedSlice(signerBlock);
        byte[] publicKeyBytes = readLengthPrefixedByteArray(signerBlock);

        int signatureCount = 0;
        int bestSigAlgorithm = -1;
        byte[] bestSigAlgorithmSignatureBytes = null;
        List<Integer> signaturesSigAlgorithms = new ArrayList<>();
        while (signatures.hasRemaining()) {
            signatureCount++;
            try {
                ByteBuffer signature = getLengthPrefixedSlice(signatures);
                if (signature.remaining() < 8) {
                    throw new SecurityException("Signature record too short");
                }
                int sigAlgorithm = signature.getInt();
                signaturesSigAlgorithms.add(sigAlgorithm);
                if (!isSupportedSignatureAlgorithm(sigAlgorithm)) {
                    continue;
                }
                if ((bestSigAlgorithm == -1)
                        || (compareSignatureAlgorithm(sigAlgorithm, bestSigAlgorithm) > 0)) {
                    bestSigAlgorithm = sigAlgorithm;
                    bestSigAlgorithmSignatureBytes = readLengthPrefixedByteArray(signature);
                }
            } catch (IOException | BufferUnderflowException e) {
                throw new SecurityException(
                        "Failed to parse signature record #" + signatureCount,
                        e);
            }
        }
        if (bestSigAlgorithm == -1) {
            if (signatureCount == 0) {
                throw new SecurityException("No signatures found");
            } else {
                throw new SecurityException("No supported signatures found");
            }
        }

        String keyAlgorithm = getSignatureAlgorithmJcaKeyAlgorithm(bestSigAlgorithm);
        Pair<String, ? extends AlgorithmParameterSpec> signatureAlgorithmParams =
                getSignatureAlgorithmJcaSignatureAlgorithm(bestSigAlgorithm);
        String jcaSignatureAlgorithm = signatureAlgorithmParams.first;
        AlgorithmParameterSpec jcaSignatureAlgorithmParams = signatureAlgorithmParams.second;
        boolean sigVerified;
        try {
            PublicKey publicKey =
                    KeyFactory.getInstance(keyAlgorithm)
                            .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature sig = Signature.getInstance(jcaSignatureAlgorithm);
            sig.initVerify(publicKey);
            if (jcaSignatureAlgorithmParams != null) {
                sig.setParameter(jcaSignatureAlgorithmParams);
            }
            sig.update(signedData);
            sigVerified = sig.verify(bestSigAlgorithmSignatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException
                | InvalidAlgorithmParameterException | SignatureException e) {
            throw new SecurityException(
                    "Failed to verify " + jcaSignatureAlgorithm + " signature", e);
        }
        if (!sigVerified) {
            throw new SecurityException(jcaSignatureAlgorithm + " signature did not verify");
        }

        // Signature over signedData has verified.

        byte[] contentDigest = null;
        signedData.clear();
        ByteBuffer digests = getLengthPrefixedSlice(signedData);
        List<Integer> digestsSigAlgorithms = new ArrayList<>();
        int digestCount = 0;
        while (digests.hasRemaining()) {
            digestCount++;
            try {
                ByteBuffer digest = getLengthPrefixedSlice(digests);
                if (digest.remaining() < 8) {
                    throw new IOException("Record too short");
                }
                int sigAlgorithm = digest.getInt();
                digestsSigAlgorithms.add(sigAlgorithm);
                if (sigAlgorithm == bestSigAlgorithm) {
                    contentDigest = readLengthPrefixedByteArray(digest);
                }
            } catch (IOException | BufferUnderflowException e) {
                throw new IOException("Failed to parse digest record #" + digestCount, e);
            }
        }

        if (!signaturesSigAlgorithms.equals(digestsSigAlgorithms)) {
            throw new SecurityException(
                    "Signature algorithms don't match between digests and signatures records");
        }
        int digestAlgorithm = getSignatureAlgorithmContentDigestAlgorithm(bestSigAlgorithm);
        byte[] previousSignerDigest = contentDigests.put(digestAlgorithm, contentDigest);
        if ((previousSignerDigest != null)
                && (!MessageDigest.isEqual(previousSignerDigest, contentDigest))) {
            throw new SecurityException(
                    getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm)
                            + " contents digest does not match the digest specified by a "
                            + "preceding signer");
        }

        ByteBuffer certificates = getLengthPrefixedSlice(signedData);
        List<X509Certificate> certs = new ArrayList<>();
        int certificateCount = 0;
        while (certificates.hasRemaining()) {
            certificateCount++;
            byte[] encodedCert = readLengthPrefixedByteArray(certificates);
            X509Certificate certificate;
            try {
                certificate = (X509Certificate)
                        certFactory.generateCertificate(new ByteArrayInputStream(encodedCert));
            } catch (CertificateException e) {
                throw new SecurityException("Failed to decode certificate #" + certificateCount, e);
            }
            certificate = new VerbatimX509Certificate(certificate, encodedCert);
            certs.add(certificate);
        }

        if (certs.isEmpty()) {
            throw new SecurityException("No certificates listed");
        }
        X509Certificate mainCertificate = certs.get(0);
        byte[] certificatePublicKeyBytes = mainCertificate.getPublicKey().getEncoded();
        if (!Arrays.equals(publicKeyBytes, certificatePublicKeyBytes)) {
            throw new SecurityException(
                    "Public key mismatch between certificate and signature record");
        }

        int signedMinSDK = signedData.getInt();
        if (signedMinSDK != minSdkVersion) {
            throw new SecurityException(
                    "minSdkVersion mismatch between signed and unsigned in v3 signer block.");
        }
        mSignerMinSdkVersion = signedMinSDK;

        int signedMaxSDK = signedData.getInt();
        if (signedMaxSDK != maxSdkVersion) {
            throw new SecurityException(
                    "maxSdkVersion mismatch between signed and unsigned in v3 signer block.");
        }

        ByteBuffer additionalAttrs = getLengthPrefixedSlice(signedData);
        return verifyAdditionalAttributes(additionalAttrs, certs, certFactory);
    }

    private static final int PROOF_OF_ROTATION_ATTR_ID = 0x3ba06f8c;
    private static final int ROTATION_MIN_SDK_VERSION_ATTR_ID = 0x559f8b02;
    private static final int ROTATION_ON_DEV_RELEASE_ATTR_ID = 0xc2a6b3ba;

    private Pair<X509Certificate[], ApkSigningBlockUtils.VerifiedProofOfRotation>
            verifyAdditionalAttributes(ByteBuffer attrs, List<X509Certificate> certs,
                CertificateFactory certFactory) throws IOException, PlatformNotSupportedException {
        X509Certificate[] certChain = certs.toArray(new X509Certificate[certs.size()]);
        ApkSigningBlockUtils.VerifiedProofOfRotation por = null;

        while (attrs.hasRemaining()) {
            ByteBuffer attr = getLengthPrefixedSlice(attrs);
            if (attr.remaining() < 4) {
                throw new IOException("Remaining buffer too short to contain additional attribute "
                        + "ID. Remaining: " + attr.remaining());
            }
            int id = attr.getInt();
            switch (id) {
                case PROOF_OF_ROTATION_ATTR_ID:
                    if (por != null) {
                        throw new SecurityException("Encountered multiple Proof-of-rotation records"
                                + " when verifying APK Signature Scheme v3 signature");
                    }
                    por = verifyProofOfRotationStruct(attr, certFactory);
                    // make sure that the last certificate in the Proof-of-rotation record matches
                    // the one used to sign this APK.
                    try {
                        if (por.certs.size() > 0
                                && !Arrays.equals(por.certs.get(por.certs.size() - 1).getEncoded(),
                                certChain[0].getEncoded())) {
                            throw new SecurityException("Terminal certificate in Proof-of-rotation"
                                    + " record does not match APK signing certificate");
                        }
                    } catch (CertificateEncodingException e) {
                        throw new SecurityException("Failed to encode certificate when comparing"
                                + " Proof-of-rotation record and signing certificate", e);
                    }

                    break;
                case ROTATION_MIN_SDK_VERSION_ATTR_ID:
                    if (attr.remaining() < 4) {
                        throw new IOException(
                                "Remaining buffer too short to contain rotation minSdkVersion "
                                        + "value. Remaining: "
                                        + attr.remaining());
                    }
                    int attrRotationMinSdkVersion = attr.getInt();
                    if (!mOptionalRotationMinSdkVersion.isPresent()) {
                        throw new SecurityException(
                                "Expected a v3.1 signing block targeting SDK version "
                                        + attrRotationMinSdkVersion
                                        + ", but a v3.1 block was not found");
                    }
                    int rotationMinSdkVersion = mOptionalRotationMinSdkVersion.getAsInt();
                    if (rotationMinSdkVersion != attrRotationMinSdkVersion) {
                        throw new SecurityException(
                                "Expected a v3.1 signing block targeting SDK version "
                                        + attrRotationMinSdkVersion
                                        + ", but the v3.1 block was targeting "
                                        + rotationMinSdkVersion);
                    }
                    break;
                case ROTATION_ON_DEV_RELEASE_ATTR_ID:
                    // This attribute should only be used in a v3.1 signer as it allows the v3.1
                    // signature scheme to target a platform under development.
                    if (mBlockId == APK_SIGNATURE_SCHEME_V31_BLOCK_ID) {
                        // A platform under development uses the same SDK version as the most
                        // recently released platform; if this platform's SDK version is the same as
                        // the minimum of the signer, then only allow this signer to be used if this
                        // is not a "REL" platform.
                        if (Build.VERSION.SDK_INT == mSignerMinSdkVersion
                                && "REL".equals(Build.VERSION.CODENAME)) {
                            // Set the rotation-min-sdk-version to be verified against the stripping
                            // attribute in the v3.0 block.
                            mOptionalRotationMinSdkVersion = OptionalInt.of(mSignerMinSdkVersion);
                            throw new PlatformNotSupportedException(
                                    "The device is running a release version of "
                                            + mSignerMinSdkVersion
                                            + ", but the signer is targeting a dev release");
                        }
                    }
                    break;
                default:
                    // not the droid we're looking for, move along, move along.
                    break;
            }
        }
        return Pair.create(certChain, por);
    }

    static byte[] getVerityRootHash(String apkPath)
            throws IOException, SignatureNotFoundException, SecurityException {
        try (RandomAccessFile apk = new RandomAccessFile(apkPath, "r")) {
            SignatureInfo signatureInfo = findSignature(apk);
            VerifiedSigner vSigner = verify(apk, false);
            return vSigner.verityRootHash;
        }
    }

    static byte[] generateApkVerity(String apkPath, ByteBufferFactory bufferFactory)
            throws IOException, SignatureNotFoundException, SecurityException, DigestException,
            NoSuchAlgorithmException {
        try (RandomAccessFile apk = new RandomAccessFile(apkPath, "r")) {
            SignatureInfo signatureInfo = findSignature(apk);
            return VerityBuilder.generateApkVerity(apkPath, bufferFactory, signatureInfo);
        }
    }

    /**
     * Verified APK Signature Scheme v3 signer, including the proof of rotation structure.
     *
     * @hide for internal use only.
     */
    public static class VerifiedSigner {
        public final X509Certificate[] certs;
        public final ApkSigningBlockUtils.VerifiedProofOfRotation por;

        public final byte[] verityRootHash;
        // Algorithm -> digest map of signed digests in the signature.
        // All these are verified if requested.
        public final Map<Integer, byte[]> contentDigests;

        // ID of the signature block used to verify.
        public final int blockId;

        public VerifiedSigner(X509Certificate[] certs,
                ApkSigningBlockUtils.VerifiedProofOfRotation por,
                byte[] verityRootHash, Map<Integer, byte[]> contentDigests,
                int blockId) {
            this.certs = certs;
            this.por = por;
            this.verityRootHash = verityRootHash;
            this.contentDigests = contentDigests;
            this.blockId = blockId;
        }

    }

    private static class PlatformNotSupportedException extends Exception {

        PlatformNotSupportedException(String s) {
            super(s);
        }
    }
}
