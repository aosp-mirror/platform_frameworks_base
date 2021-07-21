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

import android.annotation.Nullable;

import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;

/**
 * A class encapsulating the result from the source stamp verifier
 *
 * <p>It indicates whether the source stamp is verified or not, and the source stamp certificate.
 *
 * @hide
 */
public final class SourceStampVerificationResult {

    private final boolean mPresent;
    private final boolean mVerified;
    private final Certificate mCertificate;
    private final List<? extends Certificate> mCertificateLineage;

    private SourceStampVerificationResult(
            boolean present, boolean verified, @Nullable Certificate certificate,
            List<? extends Certificate> certificateLineage) {
        this.mPresent = present;
        this.mVerified = verified;
        this.mCertificate = certificate;
        this.mCertificateLineage = certificateLineage;
    }

    public boolean isPresent() {
        return mPresent;
    }

    public boolean isVerified() {
        return mVerified;
    }

    public Certificate getCertificate() {
        return mCertificate;
    }

    public List<? extends Certificate> getCertificateLineage() {
        return mCertificateLineage;
    }

    /**
     * Create a non-present source stamp outcome.
     *
     * @return A non-present source stamp result.
     */
    public static SourceStampVerificationResult notPresent() {
        return new SourceStampVerificationResult(
                /* present= */ false, /* verified= */ false, /* certificate= */
                null, /* certificateLineage= */ Collections.emptyList());
    }

    /**
     * Create a verified source stamp outcome.
     *
     * @param certificate        The source stamp certificate.
     * @param certificateLineage The proof-of-rotation lineage for the source stamp.
     * @return A verified source stamp result, and the source stamp certificate.
     */
    public static SourceStampVerificationResult verified(Certificate certificate,
            List<? extends Certificate> certificateLineage) {
        return new SourceStampVerificationResult(
                /* present= */ true, /* verified= */ true, certificate, certificateLineage);
    }

    /**
     * Create a non-verified source stamp outcome.
     *
     * @return A non-verified source stamp result.
     */
    public static SourceStampVerificationResult notVerified() {
        return new SourceStampVerificationResult(
                /* present= */ true, /* verified= */ false, /* certificate= */
                null, /* certificateLineage= */ Collections.emptyList());
    }
}
