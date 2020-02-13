/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.incremental.IncrementalManager;

import java.io.File;
import java.security.cert.Certificate;

import sun.security.pkcs.PKCS7;
import sun.security.pkcs.ParsingException;

/**
 * APK Signature Scheme v4 verifier.
 *
 * @hide for internal use only.
 */
public class ApkSignatureSchemeV4Verifier {

    /**
     * Extracts APK Signature Scheme v4 signatures of the provided APK and returns the certificates
     * associated with each signer.
     */
    public static Certificate[] extractCertificates(String apkFile)
            throws SignatureNotFoundException, SecurityException {
        final byte[] rawSignature = IncrementalManager.unsafeGetFileSignature(
                new File(apkFile).getAbsolutePath());
        if (rawSignature == null || rawSignature.length == 0) {
            throw new SignatureNotFoundException("Failed to obtain raw signature from IncFS.");
        }

        try {
            PKCS7 pkcs7 = new PKCS7(rawSignature);
            return pkcs7.getCertificates();
        } catch (ParsingException e) {
            throw new SecurityException("Failed to parse signature and extract certificates", e);
        }
    }
}
