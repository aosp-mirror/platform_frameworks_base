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

package android.security.keystore.recovery;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.io.ByteArrayInputStream;
import java.security.cert.CertPath;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

/**
 * The certificate path of the recovery service.
 *
 * @hide
 */
public final class RecoveryCertPath implements Parcelable {

    private static final String CERT_PATH_ENCODING = "PkiPath";

    private final byte[] mEncodedCertPath;

    /**
     * Wraps a {@code CertPath} to create a {@code Parcelable} for Binder calls.
     *
     * @param certPath The certificate path to be wrapped.
     * @throws CertificateException if the given certificate path cannot be encoded properly.
     */
    public static @NonNull RecoveryCertPath createRecoveryCertPath(@NonNull CertPath certPath)
            throws CertificateException {
        // Perform the encoding here to avoid throwing exceptions in writeToParcel
        try {
            return new RecoveryCertPath(encodeCertPath(certPath));
        } catch (CertificateEncodingException e) {
            throw new CertificateException("Failed to encode the given CertPath", e);
        }
    }

    /**
     * Obtains the {@code CertPath} wrapped in the Parcelable.
     *
     * @return the wrapped certificate path.
     * @throws CertificateException if the wrapped certificate path cannot be decoded properly.
     */
    public @NonNull CertPath getCertPath() throws CertificateException {
        // Perform the decoding here to avoid throwing exceptions in createFromParcel
        return decodeCertPath(mEncodedCertPath);
    }

    private RecoveryCertPath(@NonNull byte[] encodedCertPath) {
        mEncodedCertPath = Preconditions.checkNotNull(encodedCertPath);
    }

    private RecoveryCertPath(Parcel in) {
        mEncodedCertPath = in.createByteArray();
    }

    public static final @NonNull Parcelable.Creator<RecoveryCertPath> CREATOR =
            new Parcelable.Creator<RecoveryCertPath>() {
        public RecoveryCertPath createFromParcel(Parcel in) {
            return new RecoveryCertPath(in);
        }

        public RecoveryCertPath[] newArray(int length) {
            return new RecoveryCertPath[length];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByteArray(mEncodedCertPath);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    private static byte[] encodeCertPath(@NonNull CertPath certPath)
            throws CertificateEncodingException {
        Preconditions.checkNotNull(certPath);
        return certPath.getEncoded(CERT_PATH_ENCODING);
    }

    @NonNull
    private static CertPath decodeCertPath(@NonNull byte[] bytes) throws CertificateException {
        Preconditions.checkNotNull(bytes);
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            // Should not happen, as X.509 is mandatory for all providers.
            throw new RuntimeException(e);
        }
        return certFactory.generateCertPath(new ByteArrayInputStream(bytes), CERT_PATH_ENCODING);
    }
}
