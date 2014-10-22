/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mediadrm.signer;

import android.media.MediaDrm;
import android.media.DeniedByServerException;

/**
 * Provides certificate request generation, response handling and
 * signing APIs
 */
public final class MediaDrmSigner {
    private MediaDrmSigner() {}

    /**
     * Specify X.509 certificate type
     */
    public static final int CERTIFICATE_TYPE_X509 = MediaDrm.CERTIFICATE_TYPE_X509;

    /**
     * Contains the opaque data an app uses to request a certificate from a provisioning
     * server
     */
    public final static class CertificateRequest {
        private final MediaDrm.CertificateRequest mCertRequest;

        CertificateRequest(MediaDrm.CertificateRequest certRequest) {
            mCertRequest = certRequest;
        }

        /**
         * Get the opaque message data
         */
        public byte[] getData() {
            return mCertRequest.getData();
        }

        /**
         * Get the default URL to use when sending the certificate request
         * message to a server, if known. The app may prefer to use a different
         * certificate server URL obtained from other sources.
         */
        public String getDefaultUrl() {
            return mCertRequest.getDefaultUrl();
        }
    }

    /**
     * Contains the wrapped private key and public certificate data associated
     * with a certificate.
     */
    public final static class Certificate {
        private final MediaDrm.Certificate mCertificate;

        Certificate(MediaDrm.Certificate certificate) {
            mCertificate = certificate;
        }

        /**
         * Get the wrapped private key data
         */
        public byte[] getWrappedPrivateKey() {
            return mCertificate.getWrappedPrivateKey();
        }

        /**
         * Get the PEM-encoded public certificate chain
         */
        public byte[] getContent() {
            return mCertificate.getContent();
        }
    }

    /**
     * Generate a certificate request, specifying the certificate type
     * and authority. The response received should be passed to
     * provideCertificateResponse.
     *
     * @param drm the MediaDrm object
     * @param certType Specifies the certificate type.
     * @param certAuthority is passed to the certificate server to specify
     * the chain of authority.
     */
    public static CertificateRequest getCertificateRequest(MediaDrm drm, int certType,
            String certAuthority) {
        return new CertificateRequest(drm.getCertificateRequest(certType, certAuthority));
    }

    /**
     * Process a response from the provisioning server.  The response
     * is obtained from an HTTP Post to the url provided by getCertificateRequest.
     *
     * The public X509 certificate chain and wrapped private key are returned
     * in the returned Certificate objec.  The certificate chain is in BIO serialized
     * PEM format.  The wrapped private key should be stored in application private
     * storage, and used when invoking the signRSA method.
     *
     * @param drm the MediaDrm object
     * @param response the opaque certificate response byte array to provide to the
     * DRM engine plugin.
     * @throws android.media.DeniedByServerException if the response indicates that the
     * server rejected the request
     */
    public static Certificate provideCertificateResponse(MediaDrm drm, byte[] response)
            throws DeniedByServerException {
        return new Certificate(drm.provideCertificateResponse(response));
    }

    /**
     * Sign data using an RSA key
     *
     * @param drm the MediaDrm object
     * @param sessionId a sessionId obtained from openSession on the MediaDrm object
     * @param algorithm the signing algorithm to use, e.g. "PKCS1-BlockType1"
     * @param wrappedKey - the wrapped (encrypted) RSA private key obtained
     * from provideCertificateResponse
     * @param message the data for which a signature is to be computed
     */
    public static byte[] signRSA(MediaDrm drm, byte[] sessionId,
            String algorithm, byte[] wrappedKey, byte[] message) {
        return drm.signRSA(sessionId, algorithm, wrappedKey, message);
    }
}
