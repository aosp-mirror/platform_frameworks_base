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

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Set;

class WrappedX509Certificate extends X509Certificate {
    private final X509Certificate mWrapped;

    WrappedX509Certificate(X509Certificate wrapped) {
        this.mWrapped = wrapped;
    }

    @Override
    public Set<String> getCriticalExtensionOIDs() {
        return mWrapped.getCriticalExtensionOIDs();
    }

    @Override
    public byte[] getExtensionValue(String oid) {
        return mWrapped.getExtensionValue(oid);
    }

    @Override
    public Set<String> getNonCriticalExtensionOIDs() {
        return mWrapped.getNonCriticalExtensionOIDs();
    }

    @Override
    public boolean hasUnsupportedCriticalExtension() {
        return mWrapped.hasUnsupportedCriticalExtension();
    }

    @Override
    public void checkValidity()
            throws CertificateExpiredException, CertificateNotYetValidException {
        mWrapped.checkValidity();
    }

    @Override
    public void checkValidity(Date date)
            throws CertificateExpiredException, CertificateNotYetValidException {
        mWrapped.checkValidity(date);
    }

    @Override
    public int getVersion() {
        return mWrapped.getVersion();
    }

    @Override
    public BigInteger getSerialNumber() {
        return mWrapped.getSerialNumber();
    }

    @Override
    public Principal getIssuerDN() {
        return mWrapped.getIssuerDN();
    }

    @Override
    public Principal getSubjectDN() {
        return mWrapped.getSubjectDN();
    }

    @Override
    public Date getNotBefore() {
        return mWrapped.getNotBefore();
    }

    @Override
    public Date getNotAfter() {
        return mWrapped.getNotAfter();
    }

    @Override
    public byte[] getTBSCertificate() throws CertificateEncodingException {
        return mWrapped.getTBSCertificate();
    }

    @Override
    public byte[] getSignature() {
        return mWrapped.getSignature();
    }

    @Override
    public String getSigAlgName() {
        return mWrapped.getSigAlgName();
    }

    @Override
    public String getSigAlgOID() {
        return mWrapped.getSigAlgOID();
    }

    @Override
    public byte[] getSigAlgParams() {
        return mWrapped.getSigAlgParams();
    }

    @Override
    public boolean[] getIssuerUniqueID() {
        return mWrapped.getIssuerUniqueID();
    }

    @Override
    public boolean[] getSubjectUniqueID() {
        return mWrapped.getSubjectUniqueID();
    }

    @Override
    public boolean[] getKeyUsage() {
        return mWrapped.getKeyUsage();
    }

    @Override
    public int getBasicConstraints() {
        return mWrapped.getBasicConstraints();
    }

    @Override
    public byte[] getEncoded() throws CertificateEncodingException {
        return mWrapped.getEncoded();
    }

    @Override
    public void verify(PublicKey key) throws CertificateException, NoSuchAlgorithmException,
            InvalidKeyException, NoSuchProviderException, SignatureException {
        mWrapped.verify(key);
    }

    @Override
    public void verify(PublicKey key, String sigProvider)
            throws CertificateException, NoSuchAlgorithmException, InvalidKeyException,
            NoSuchProviderException, SignatureException {
        mWrapped.verify(key, sigProvider);
    }

    @Override
    public String toString() {
        return mWrapped.toString();
    }

    @Override
    public PublicKey getPublicKey() {
        return mWrapped.getPublicKey();
    }
}
