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

package android.util.apk;

import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningDetails.SignatureSchemeVersion;
import android.content.pm.parsing.ParsingPackageUtils;
import android.os.Build;
import android.os.Trace;
import android.util.jar.StrictJarFile;

import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;

/**
 * Facade class that takes care of the details of APK verification on
 * behalf of PackageParser.
 *
 * @hide for internal use only.
 */
public class ApkSignatureVerifier {

    private static final AtomicReference<byte[]> sBuffer = new AtomicReference<>();

    /**
     * Verifies the provided APK and returns the certificates associated with each signer.
     *
     * @throws PackageParserException if the APK's signature failed to verify.
     */
    public static SigningDetails verify(String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion)
            throws PackageParserException {
        return verifySignatures(apkPath, minSignatureSchemeVersion, true);
    }

    /**
     * Returns the certificates associated with each signer for the given APK without verification.
     * This method is dangerous and should not be used, unless the caller is absolutely certain the
     * APK is trusted.
     *
     * @throws PackageParserException if there was a problem collecting certificates.
     */
    public static SigningDetails unsafeGetCertsWithoutVerification(
            String apkPath, int minSignatureSchemeVersion)
            throws PackageParserException {
        return verifySignatures(apkPath, minSignatureSchemeVersion, false);
    }

    /**
     * Verifies the provided APK using all allowed signing schemas.
     * @return the certificates associated with each signer.
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     * @throws PackageParserException if there was a problem collecting certificates
     */
    private static SigningDetails verifySignatures(String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion, boolean verifyFull)
            throws PackageParserException {
        return verifySignaturesInternal(apkPath, minSignatureSchemeVersion,
                verifyFull).signingDetails;
    }

    /**
     * Verifies the provided APK using all allowed signing schemas.
     * @return the certificates associated with each signer and content digests.
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     * @throws PackageParserException if there was a problem collecting certificates
     * @hide
     */
    public static SigningDetailsWithDigests verifySignaturesInternal(String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion, boolean verifyFull)
            throws PackageParserException {

        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V4) {
            // V3 and before are older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // first try v4
        try {
            return verifyV4Signature(apkPath, minSignatureSchemeVersion, verifyFull);
        } catch (SignatureNotFoundException e) {
            // not signed with v4, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V4) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v4 signature in package " + apkPath, e);
            }
        }

        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V3) {
            // V3 and before are older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        return verifyV3AndBelowSignatures(apkPath, minSignatureSchemeVersion, verifyFull);
    }

    private static SigningDetailsWithDigests verifyV3AndBelowSignatures(String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion, boolean verifyFull)
            throws PackageParserException {
        // try v3
        try {
            return verifyV3Signature(apkPath, verifyFull);
        } catch (SignatureNotFoundException e) {
            // not signed with v3, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V3) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v3 signature in package " + apkPath, e);
            }
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V2) {
            // V2 and before are older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // try v2
        try {
            return verifyV2Signature(apkPath, verifyFull);
        } catch (SignatureNotFoundException e) {
            // not signed with v2, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V2) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v2 signature in package " + apkPath, e);
            }
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.JAR) {
            // V1 and is older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // v2 didn't work, try jarsigner
        return verifyV1Signature(apkPath, verifyFull);
    }

    /**
     * Verifies the provided APK using V4 schema.
     *
     * @param verifyFull whether to verify (V4 vs V3) or just collect certificates.
     * @return the certificates associated with each signer.
     * @throws SignatureNotFoundException if there are no V4 signatures in the APK
     * @throws PackageParserException     if there was a problem collecting certificates
     */
    private static SigningDetailsWithDigests verifyV4Signature(String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion, boolean verifyFull)
            throws SignatureNotFoundException, PackageParserException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, verifyFull ? "verifyV4" : "certsOnlyV4");
        try {
            ApkSignatureSchemeV4Verifier.VerifiedSigner vSigner =
                    ApkSignatureSchemeV4Verifier.extractCertificates(apkPath);
            Certificate[][] signerCerts = new Certificate[][]{vSigner.certs};
            Signature[] signerSigs = convertToSignatures(signerCerts);

            if (verifyFull) {
                Map<Integer, byte[]> nonstreamingDigests;
                Certificate[][] nonstreamingCerts;

                try {
                    // v4 is an add-on and requires v2 or v3 signature to validate against its
                    // certificate and digest
                    ApkSignatureSchemeV3Verifier.VerifiedSigner v3Signer =
                            ApkSignatureSchemeV3Verifier.unsafeGetCertsWithoutVerification(apkPath);
                    nonstreamingDigests = v3Signer.contentDigests;
                    nonstreamingCerts = new Certificate[][]{v3Signer.certs};
                } catch (SignatureNotFoundException e) {
                    try {
                        ApkSignatureSchemeV2Verifier.VerifiedSigner v2Signer =
                                ApkSignatureSchemeV2Verifier.verify(apkPath, false);
                        nonstreamingDigests = v2Signer.contentDigests;
                        nonstreamingCerts = v2Signer.certs;
                    } catch (SignatureNotFoundException ee) {
                        throw new SecurityException(
                                "V4 verification failed to collect V2/V3 certificates from : "
                                        + apkPath, ee);
                    }
                }

                Signature[] nonstreamingSigs = convertToSignatures(nonstreamingCerts);
                if (nonstreamingSigs.length != signerSigs.length) {
                    throw new SecurityException(
                            "Invalid number of certificates: " + nonstreamingSigs.length);
                }

                for (int i = 0, size = signerSigs.length; i < size; ++i) {
                    if (!nonstreamingSigs[i].equals(signerSigs[i])) {
                        throw new SecurityException(
                                "V4 signature certificate does not match V2/V3");
                    }
                }

                boolean found = false;
                for (byte[] nonstreamingDigest : nonstreamingDigests.values()) {
                    if (ArrayUtils.equals(vSigner.apkDigest, nonstreamingDigest,
                            vSigner.apkDigest.length)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new SecurityException("APK digest in V4 signature does not match V2/V3");
                }
            }

            return new SigningDetailsWithDigests(new SigningDetails(signerSigs,
                    SignatureSchemeVersion.SIGNING_BLOCK_V4), vSigner.contentDigests);
        } catch (SignatureNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // APK Signature Scheme v4 signature found but did not verify
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v4", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Verifies the provided APK using V3 schema.
     *
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     * @return the certificates associated with each signer.
     * @throws SignatureNotFoundException if there are no V3 signatures in the APK
     * @throws PackageParserException     if there was a problem collecting certificates
     */
    private static SigningDetailsWithDigests verifyV3Signature(String apkPath, boolean verifyFull)
            throws SignatureNotFoundException, PackageParserException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, verifyFull ? "verifyV3" : "certsOnlyV3");
        try {
            ApkSignatureSchemeV3Verifier.VerifiedSigner vSigner =
                    verifyFull ? ApkSignatureSchemeV3Verifier.verify(apkPath)
                            : ApkSignatureSchemeV3Verifier.unsafeGetCertsWithoutVerification(
                                    apkPath);
            Certificate[][] signerCerts = new Certificate[][]{vSigner.certs};
            Signature[] signerSigs = convertToSignatures(signerCerts);
            Signature[] pastSignerSigs = null;
            if (vSigner.por != null) {
                // populate proof-of-rotation information
                pastSignerSigs = new Signature[vSigner.por.certs.size()];
                for (int i = 0; i < pastSignerSigs.length; i++) {
                    pastSignerSigs[i] = new Signature(vSigner.por.certs.get(i).getEncoded());
                    pastSignerSigs[i].setFlags(vSigner.por.flagsList.get(i));
                }
            }
            return new SigningDetailsWithDigests(new SigningDetails(signerSigs,
                    SignatureSchemeVersion.SIGNING_BLOCK_V3, pastSignerSigs),
                    vSigner.contentDigests);
        } catch (SignatureNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // APK Signature Scheme v3 signature found but did not verify
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v3", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Verifies the provided APK using V2 schema.
     *
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     * @return the certificates associated with each signer.
     * @throws SignatureNotFoundException if there are no V2 signatures in the APK
     * @throws PackageParserException     if there was a problem collecting certificates
     */
    private static SigningDetailsWithDigests verifyV2Signature(String apkPath, boolean verifyFull)
            throws SignatureNotFoundException, PackageParserException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, verifyFull ? "verifyV2" : "certsOnlyV2");
        try {
            ApkSignatureSchemeV2Verifier.VerifiedSigner vSigner =
                    ApkSignatureSchemeV2Verifier.verify(apkPath, verifyFull);
            Certificate[][] signerCerts = vSigner.certs;
            Signature[] signerSigs = convertToSignatures(signerCerts);
            return new SigningDetailsWithDigests(new SigningDetails(signerSigs,
                    SignatureSchemeVersion.SIGNING_BLOCK_V2), vSigner.contentDigests);
        } catch (SignatureNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // APK Signature Scheme v2 signature found but did not verify
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v2", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Verifies the provided APK using JAR schema.
     * @return the certificates associated with each signer.
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     * @throws PackageParserException if there was a problem collecting certificates
     */
    private static SigningDetailsWithDigests verifyV1Signature(String apkPath, boolean verifyFull)
            throws PackageParserException {
        StrictJarFile jarFile = null;

        try {
            final Certificate[][] lastCerts;
            final Signature[] lastSigs;

            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "strictJarFileCtor");

            // we still pass verify = true to ctor to collect certs, even though we're not checking
            // the whole jar.
            jarFile = new StrictJarFile(
                    apkPath,
                    true, // collect certs
                    verifyFull); // whether to reject APK with stripped v2 signatures (b/27887819)
            final List<ZipEntry> toVerify = new ArrayList<>();

            // Gather certs from AndroidManifest.xml, which every APK must have, as an optimization
            // to not need to verify the whole APK when verifyFUll == false.
            final ZipEntry manifestEntry = jarFile.findEntry(
                    ParsingPackageUtils.ANDROID_MANIFEST_FILENAME);
            if (manifestEntry == null) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Package " + apkPath + " has no manifest");
            }
            lastCerts = loadCertificates(jarFile, manifestEntry);
            if (ArrayUtils.isEmpty(lastCerts)) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES, "Package "
                        + apkPath + " has no certificates at entry "
                        + ParsingPackageUtils.ANDROID_MANIFEST_FILENAME);
            }
            lastSigs = convertToSignatures(lastCerts);

            // fully verify all contents, except for AndroidManifest.xml  and the META-INF/ files.
            if (verifyFull) {
                final Iterator<ZipEntry> i = jarFile.iterator();
                while (i.hasNext()) {
                    final ZipEntry entry = i.next();
                    if (entry.isDirectory()) continue;

                    final String entryName = entry.getName();
                    if (entryName.startsWith("META-INF/")) continue;
                    if (entryName.equals(ParsingPackageUtils.ANDROID_MANIFEST_FILENAME)) continue;

                    toVerify.add(entry);
                }

                for (ZipEntry entry : toVerify) {
                    final Certificate[][] entryCerts = loadCertificates(jarFile, entry);
                    if (ArrayUtils.isEmpty(entryCerts)) {
                        throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                                "Package " + apkPath + " has no certificates at entry "
                                        + entry.getName());
                    }

                    // make sure all entries use the same signing certs
                    final Signature[] entrySigs = convertToSignatures(entryCerts);
                    if (!Signature.areExactMatch(lastSigs, entrySigs)) {
                        throw new PackageParserException(
                                INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                "Package " + apkPath + " has mismatched certificates at entry "
                                        + entry.getName());
                    }
                }
            }
            return new SigningDetailsWithDigests(
                    new SigningDetails(lastSigs, SignatureSchemeVersion.JAR), null);
        } catch (GeneralSecurityException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING,
                    "Failed to collect certificates from " + apkPath, e);
        } catch (IOException | RuntimeException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath, e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            closeQuietly(jarFile);
        }
    }

    private static Certificate[][] loadCertificates(StrictJarFile jarFile, ZipEntry entry)
            throws PackageParserException {
        InputStream is = null;
        try {
            // We must read the stream for the JarEntry to retrieve
            // its certificates.
            is = jarFile.getInputStream(entry);
            readFullyIgnoringContents(is);
            return jarFile.getCertificateChains(entry);
        } catch (IOException | RuntimeException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed reading " + entry.getName() + " in " + jarFile, e);
        } finally {
            IoUtils.closeQuietly(is);
        }
    }

    private static void readFullyIgnoringContents(InputStream in) throws IOException {
        byte[] buffer = sBuffer.getAndSet(null);
        if (buffer == null) {
            buffer = new byte[4096];
        }

        int n = 0;
        int count = 0;
        while ((n = in.read(buffer, 0, buffer.length)) != -1) {
            count += n;
        }

        sBuffer.set(buffer);
        return;
    }

    /**
     * Converts an array of certificate chains into the {@code Signature} equivalent used by the
     * PackageManager.
     *
     * @throws CertificateEncodingException if it is unable to create a Signature object.
     */
    private static Signature[] convertToSignatures(Certificate[][] certs)
            throws CertificateEncodingException {
        final Signature[] res = new Signature[certs.length];
        for (int i = 0; i < certs.length; i++) {
            res[i] = new Signature(certs[i]);
        }
        return res;
    }

    private static void closeQuietly(StrictJarFile jarFile) {
        if (jarFile != null) {
            try {
                jarFile.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Returns the minimum signature scheme version required for an app targeting the specified
     * {@code targetSdk}.
     */
    public static int getMinimumSignatureSchemeVersionForTargetSdk(int targetSdk) {
        if (targetSdk >= Build.VERSION_CODES.R) {
            return SignatureSchemeVersion.SIGNING_BLOCK_V2;
        }
        return SignatureSchemeVersion.JAR;
    }

    /**
     * Result of a successful APK verification operation.
     */
    public static class Result {
        public final Certificate[][] certs;
        public final Signature[] sigs;
        public final int signatureSchemeVersion;

        public Result(Certificate[][] certs, Signature[] sigs, int signingVersion) {
            this.certs = certs;
            this.sigs = sigs;
            this.signatureSchemeVersion = signingVersion;
        }
    }

    /**
     * @return the verity root hash in the Signing Block.
     */
    public static byte[] getVerityRootHash(String apkPath) throws IOException, SecurityException {
        // first try v3
        try {
            return ApkSignatureSchemeV3Verifier.getVerityRootHash(apkPath);
        } catch (SignatureNotFoundException e) {
            // try older version
        }
        try {
            return ApkSignatureSchemeV2Verifier.getVerityRootHash(apkPath);
        } catch (SignatureNotFoundException e) {
            return null;
        }
    }

    /**
     * Generates the Merkle tree and verity metadata to the buffer allocated by the {@code
     * ByteBufferFactory}.
     *
     * @return the verity root hash of the generated Merkle tree.
     */
    public static byte[] generateApkVerity(String apkPath, ByteBufferFactory bufferFactory)
            throws IOException, SignatureNotFoundException, SecurityException, DigestException,
            NoSuchAlgorithmException {
        // first try v3
        try {
            return ApkSignatureSchemeV3Verifier.generateApkVerity(apkPath, bufferFactory);
        } catch (SignatureNotFoundException e) {
            // try older version
        }
        return ApkSignatureSchemeV2Verifier.generateApkVerity(apkPath, bufferFactory);
    }

    /**
     * Generates the FSVerity root hash from FSVerity header, extensions and Merkle tree root hash
     * in Signing Block.
     *
     * @return FSverity root hash
     */
    public static byte[] generateApkVerityRootHash(String apkPath)
            throws NoSuchAlgorithmException, DigestException, IOException {
        // first try v3
        try {
            return ApkSignatureSchemeV3Verifier.generateApkVerityRootHash(apkPath);
        } catch (SignatureNotFoundException e) {
            // try older version
        }
        try {
            return ApkSignatureSchemeV2Verifier.generateApkVerityRootHash(apkPath);
        } catch (SignatureNotFoundException e) {
            return null;
        }
    }

    /**
     * Extended signing details.
     * @hide for internal use only.
     */
    public static class SigningDetailsWithDigests {
        public final SigningDetails signingDetails;

        /**
         * APK Signature Schemes v2/v3/v4 might contain multiple content digests.
         * SignatureVerifier usually chooses one of them to verify.
         * For certain signature schemes, e.g. v4, this digest is verified continuously.
         * For others, e.g. v2, the caller has to specify if they want to verify.
         * Please refer to documentation for more details.
         */
        public final Map<Integer, byte[]> contentDigests;

        SigningDetailsWithDigests(SigningDetails signingDetails,
                Map<Integer, byte[]> contentDigests) {
            this.signingDetails = signingDetails;
            this.contentDigests = contentDigests;
        }
    }
}
