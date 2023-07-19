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
import static android.util.apk.ApkSignatureSchemeV4Verifier.APK_SIGNATURE_SCHEME_DEFAULT;

import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningDetails.SignatureSchemeVersion;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.os.Build;
import android.os.Trace;
import android.os.incremental.V4Signature;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;
import android.util.jar.StrictJarFile;
import android.util.BoostFramework;

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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Facade class that takes care of the details of APK verification on
 * behalf of ParsingPackageUtils.
 *
 * @hide for internal use only.
 */
public class ApkSignatureVerifier {

    private static final AtomicReference<byte[]> sBuffer = new AtomicReference<>();

    private static final String TAG = "ApkSignatureVerifier";
    // multithread verification
    private static final int NUMBER_OF_CORES =
            Runtime.getRuntime().availableProcessors() >= 4 ? 4 : Runtime.getRuntime().availableProcessors() ;
    private static BoostFramework sPerfBoost = null;
    private static boolean sIsPerfLockAcquired = false;
    /**
     * Verifies the provided APK and returns the certificates associated with each signer.
     */
    public static ParseResult<SigningDetails> verify(ParseInput input, String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion) {
        return verifySignatures(input, apkPath, minSignatureSchemeVersion, true /* verifyFull */);
    }

    /**
     * Returns the certificates associated with each signer for the given APK without verification.
     * This method is dangerous and should not be used, unless the caller is absolutely certain the
     * APK is trusted.
     */
    public static ParseResult<SigningDetails> unsafeGetCertsWithoutVerification(
            ParseInput input, String apkPath, int minSignatureSchemeVersion) {
        return verifySignatures(input, apkPath, minSignatureSchemeVersion, false /* verifyFull */);
    }

    /**
     * Verifies the provided APK using all allowed signing schemas.
     * @return the certificates associated with each signer.
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     */
    private static ParseResult<SigningDetails> verifySignatures(ParseInput input, String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion, boolean verifyFull) {
        final ParseResult<SigningDetailsWithDigests> result =
                verifySignaturesInternal(input, apkPath, minSignatureSchemeVersion, verifyFull);
        if (result.isError()) {
            return input.error(result);
        }
        return input.success(result.getResult().signingDetails);
    }

    /**
     * Verifies the provided APK using all allowed signing schemas.
     * @return the certificates associated with each signer and content digests.
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     * @throws PackageParserException if there was a problem collecting certificates
     * @hide
     */
    public static ParseResult<SigningDetailsWithDigests> verifySignaturesInternal(ParseInput input,
            String apkPath, @SignatureSchemeVersion int minSignatureSchemeVersion,
            boolean verifyFull) {

        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V4) {
            // V4 and before are older than the requested minimum signing version
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // first try v4
        try {
            return verifyV4Signature(input, apkPath, minSignatureSchemeVersion, verifyFull);
        } catch (SignatureNotFoundException e) {
            // not signed with v4, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V4) {
                return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v4 signature in package " + apkPath, e);
            }
        }

        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V3) {
            // V3 and before are older than the requested minimum signing version
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        return verifyV3AndBelowSignatures(input, apkPath, minSignatureSchemeVersion, verifyFull);
    }

    private static ParseResult<SigningDetailsWithDigests> verifyV3AndBelowSignatures(
            ParseInput input, String apkPath, @SignatureSchemeVersion int minSignatureSchemeVersion,
            boolean verifyFull) {
        // try v3
        try {
            return verifyV3Signature(input, apkPath, verifyFull);
        } catch (SignatureNotFoundException e) {
            // not signed with v3, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V3) {
                return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v3 signature in package " + apkPath, e);
            }
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V2) {
            // V2 and before are older than the requested minimum signing version
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // try v2
        try {
            return verifyV2Signature(input, apkPath, verifyFull);
        } catch (SignatureNotFoundException e) {
            // not signed with v2, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V2) {
                return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v2 signature in package " + apkPath, e);
            }
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.JAR) {
            // V1 and is older than the requested minimum signing version
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // v2 didn't work, try jarsigner
        return verifyV1Signature(input, apkPath, verifyFull);
    }

    /**
     * Verifies the provided APK using V4 schema.
     *
     * @param verifyFull whether to verify (V4 vs V3) or just collect certificates.
     * @return the certificates associated with each signer.
     * @throws SignatureNotFoundException if there are no V4 signatures in the APK
     */
    private static ParseResult<SigningDetailsWithDigests> verifyV4Signature(ParseInput input,
            String apkPath, @SignatureSchemeVersion int minSignatureSchemeVersion,
            boolean verifyFull) throws SignatureNotFoundException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, verifyFull ? "verifyV4" : "certsOnlyV4");
        try {
            final Pair<V4Signature.HashingInfo, V4Signature.SigningInfos> v4Pair =
                    ApkSignatureSchemeV4Verifier.extractSignature(apkPath);
            final V4Signature.HashingInfo hashingInfo = v4Pair.first;
            final V4Signature.SigningInfos signingInfos = v4Pair.second;

            Signature[] pastSignerSigs = null;
            Map<Integer, byte[]> nonstreamingDigests = null;
            Certificate[][] nonstreamingCerts = null;

            int v3BlockId = APK_SIGNATURE_SCHEME_DEFAULT;
            // If V4 contains additional signing blocks then we need to always run v2/v3 verifier
            // to figure out which block they use.
            if (verifyFull || signingInfos.signingInfoBlocks.length > 0) {
                try {
                    // v4 is an add-on and requires v2 or v3 signature to validate against its
                    // certificate and digest
                    ApkSignatureSchemeV3Verifier.VerifiedSigner v3Signer =
                            ApkSignatureSchemeV3Verifier.unsafeGetCertsWithoutVerification(apkPath);
                    nonstreamingDigests = v3Signer.contentDigests;
                    nonstreamingCerts = new Certificate[][]{v3Signer.certs};
                    if (v3Signer.por != null) {
                        // populate proof-of-rotation information
                        pastSignerSigs = new Signature[v3Signer.por.certs.size()];
                        for (int i = 0; i < pastSignerSigs.length; i++) {
                            pastSignerSigs[i] = new Signature(
                                    v3Signer.por.certs.get(i).getEncoded());
                            pastSignerSigs[i].setFlags(v3Signer.por.flagsList.get(i));
                        }
                    }
                    v3BlockId = v3Signer.blockId;
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
            }

            ApkSignatureSchemeV4Verifier.VerifiedSigner vSigner =
                    ApkSignatureSchemeV4Verifier.verify(apkPath, hashingInfo, signingInfos,
                            v3BlockId);
            Certificate[][] signerCerts = new Certificate[][]{vSigner.certs};
            Signature[] signerSigs = convertToSignatures(signerCerts);

            if (verifyFull) {
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

            return input.success(new SigningDetailsWithDigests(new SigningDetails(signerSigs,
                    SignatureSchemeVersion.SIGNING_BLOCK_V4, pastSignerSigs),
                    vSigner.contentDigests));
        } catch (SignatureNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // APK Signature Scheme v4 signature found but did not verify.
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
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
     */
    private static ParseResult<SigningDetailsWithDigests> verifyV3Signature(ParseInput input,
            String apkPath, boolean verifyFull) throws SignatureNotFoundException {
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
            return input.success(new SigningDetailsWithDigests(new SigningDetails(signerSigs,
                    SignatureSchemeVersion.SIGNING_BLOCK_V3, pastSignerSigs),
                    vSigner.contentDigests));
        } catch (SignatureNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // APK Signature Scheme v3 signature found but did not verify
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
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
     */
    private static ParseResult<SigningDetailsWithDigests> verifyV2Signature(ParseInput input,
            String apkPath, boolean verifyFull) throws SignatureNotFoundException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, verifyFull ? "verifyV2" : "certsOnlyV2");
        try {
            ApkSignatureSchemeV2Verifier.VerifiedSigner vSigner =
                    ApkSignatureSchemeV2Verifier.verify(apkPath, verifyFull);
            Certificate[][] signerCerts = vSigner.certs;
            Signature[] signerSigs = convertToSignatures(signerCerts);
            return input.success(new SigningDetailsWithDigests(new SigningDetails(signerSigs,
                    SignatureSchemeVersion.SIGNING_BLOCK_V2), vSigner.contentDigests));
        } catch (SignatureNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // APK Signature Scheme v2 signature found but did not verify
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
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
     */
    private static ParseResult<SigningDetailsWithDigests> verifyV1Signature(ParseInput input,
            String apkPath, boolean verifyFull) {
        int objectNumber = verifyFull ? NUMBER_OF_CORES : 1;
        StrictJarFile[] jarFile = new StrictJarFile[objectNumber];
        final ArrayMap<String, StrictJarFile> strictJarFiles = new ArrayMap<String, StrictJarFile>();
        try {
            final Certificate[][] lastCerts;
            final Signature[] lastSigs;

            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "strictJarFileCtor");

            if (sPerfBoost == null) {
                sPerfBoost = new BoostFramework();
            }
            if (sPerfBoost != null && !sIsPerfLockAcquired && verifyFull) {
                //Use big enough number here to hold the perflock for entire PackageInstall session
                sPerfBoost.perfHint(BoostFramework.VENDOR_HINT_PACKAGE_INSTALL_BOOST,
                        null, Integer.MAX_VALUE, -1);
                Slog.d(TAG, "Perflock acquired for PackageInstall ");
                sIsPerfLockAcquired = true;
            }
            // we still pass verify = true to ctor to collect certs, even though we're not checking
            // the whole jar.
            for (int i = 0; i < objectNumber; i++) {
                jarFile[i] = new StrictJarFile(
                        apkPath,
                        true, // collect certs
                        verifyFull); // whether to reject APK with stripped v2 signatures (b/27887819)
            }
            final List<ZipEntry> toVerify = new ArrayList<>();

            // Gather certs from AndroidManifest.xml, which every APK must have, as an optimization
            // to not need to verify the whole APK when verifyFUll == false.
            final ZipEntry manifestEntry = jarFile[0].findEntry(
                    ApkLiteParseUtils.ANDROID_MANIFEST_FILENAME);
            if (manifestEntry == null) {
                return input.error(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Package " + apkPath + " has no manifest");
            }
            final ParseResult<Certificate[][]> result =
                    loadCertificates(input, jarFile[0], manifestEntry);
            if (result.isError()) {
                return input.error(result);
            }
            lastCerts = result.getResult();
            if (ArrayUtils.isEmpty(lastCerts)) {
                return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES, "Package "
                        + apkPath + " has no certificates at entry "
                        + ApkLiteParseUtils.ANDROID_MANIFEST_FILENAME);
            }
            lastSigs = convertToSignatures(lastCerts);

            // fully verify all contents, except for AndroidManifest.xml  and the META-INF/ files.
            if (verifyFull) {
                final Iterator<ZipEntry> i = jarFile[0].iterator();
                while (i.hasNext()) {
                    final ZipEntry entry = i.next();
                    if (entry.isDirectory()) continue;

                    final String entryName = entry.getName();
                    if (entryName.startsWith("META-INF/")) continue;
                    if (entryName.equals(ApkLiteParseUtils.ANDROID_MANIFEST_FILENAME)) continue;

                    toVerify.add(entry);
                }
                class VerificationData {
                    public Exception exception;
                    public int exceptionFlag;
                    public boolean wait;
                    public int index;
                    public Object objWaitAll;
                    public boolean shutDown;
                }
                VerificationData vData = new VerificationData();
                vData.objWaitAll = new Object();
                final ThreadPoolExecutor verificationExecutor = new ThreadPoolExecutor(
                        NUMBER_OF_CORES,
                        NUMBER_OF_CORES,
                        1,/*keep alive time*/
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>());
                for (ZipEntry entry : toVerify) {
                    Runnable verifyTask = new Runnable(){
                        public void run() {
                            try {
                                if (vData.exceptionFlag != 0 ) {
                                    Slog.w(TAG, "VerifyV1 exit with exception " + vData.exceptionFlag);
                                    return;
                                }
                                String tid = Long.toString(Thread.currentThread().getId());
                                StrictJarFile tempJarFile;
                                synchronized (strictJarFiles) {
                                    tempJarFile = strictJarFiles.get(tid);
                                    if (tempJarFile == null) {
                                        if (vData.index >= NUMBER_OF_CORES) {
                                            vData.index = 0;
                                        }
                                        tempJarFile = jarFile[vData.index++];
                                        strictJarFiles.put(tid, tempJarFile);
                                    }
                                }
                                final Certificate[][] entryCerts;
                                final ParseResult<Certificate[][]> ret =
                                        loadCertificates(input, tempJarFile, entry);
                                if (ret.isError()) {
                                    throw new SecurityException(ret.getException());
                                }
                                entryCerts = ret.getResult();
                                if (ArrayUtils.isEmpty(entryCerts)) {
                                     throw new SignatureNotFoundException("Package " + apkPath + " has no certificates at entry "
                                            + entry.getName());
                                }

                                // make sure all entries use the same signing certs
                                final Signature[] entrySigs = convertToSignatures(entryCerts);
                                if (!Signature.areExactMatch(lastSigs, entrySigs)) {
                                     throw new Exception("Package " + apkPath + " has mismatched certificates at entry "
                                            + entry.getName());
                                }
                            } catch (SignatureNotFoundException | SecurityException e) {
                                synchronized (vData.objWaitAll) {
                                    vData.exceptionFlag = INSTALL_PARSE_FAILED_NO_CERTIFICATES;
                                    vData.exception = e;
                                }
                            } catch (GeneralSecurityException e) {
                                synchronized (vData.objWaitAll) {
                                    vData.exceptionFlag = INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
                                    vData.exception = e;
                                }
                            } catch (Exception e) {
                                synchronized (vData.objWaitAll) {
                                    vData.exceptionFlag = INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
                                    vData.exception = e;
                                }
                            }
                        }};
                    synchronized (vData.objWaitAll) {
                        if (vData.exceptionFlag == 0) {
                            verificationExecutor.execute(verifyTask);
                        }
                    }
                }
                vData.wait = true;
                verificationExecutor.shutdown();
                while (vData.wait) {
                    try {
                        if (vData.exceptionFlag != 0 && !vData.shutDown) {
                            Slog.w(TAG, "verifyV1 Exception " + vData.exceptionFlag);
                            verificationExecutor.shutdownNow();
                            vData.shutDown = true;
                        }
                        vData.wait = !verificationExecutor.awaitTermination(50,
                                TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Slog.w(TAG,"VerifyV1 interrupted while awaiting all threads done...");
                    }
                }
                if (vData.exceptionFlag != 0)
                    return input.error(vData.exceptionFlag,
                            "Failed to collect certificates from " + apkPath, vData.exception);
            }
            return input.success(new SigningDetailsWithDigests(
                    new SigningDetails(lastSigs, SignatureSchemeVersion.JAR), null));
        } catch (GeneralSecurityException e) {
            return input.error(INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING,
                    "Failed to collect certificates from " + apkPath, e);
        } catch (IOException | RuntimeException e) {
            return input.error(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath, e);
        } finally {
            if (sIsPerfLockAcquired && sPerfBoost != null) {
                sPerfBoost.perfLockRelease();
                sIsPerfLockAcquired = false;
                Slog.d(TAG, "Perflock released for PackageInstall ");
            }
            strictJarFiles.clear();
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            for (int i = 0; i < objectNumber ; i++) {
                closeQuietly(jarFile[i]);
            }
        }
    }

    private static ParseResult<Certificate[][]> loadCertificates(ParseInput input,
            StrictJarFile jarFile, ZipEntry entry) {
        InputStream is = null;
        try {
            // We must read the stream for the JarEntry to retrieve
            // its certificates.
            is = jarFile.getInputStream(entry);
            readFullyIgnoringContents(is);
            return input.success(jarFile.getCertificateChains(entry));
        } catch (IOException | RuntimeException e) {
            return input.error(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
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
