/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.common;

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
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import junit.framework.TestCase;

public class DomainNameValidatorTest extends TestCase {
    private static final int ALT_UNKNOWN = 0;
    private static final int ALT_DNS_NAME = 2;
    private static final int ALT_IPA_NAME = 7;

    /**
     * Tests {@link DomainNameValidator#match}
     */
    public void testMatch() {
        // TODO Use actual X509Certificate objects, instead of StubX509Certificate.
        // Comment in DomainNameValidator suggests X509Certificate fails to parse a certificate
        // if subject alternative names contain a domain name that begins with '*'.
        // This test won't cover this kind of errors.

        checkMatch("11", new StubX509Certificate("cn=imap.g.com"), "imap.g.com", true);
        checkMatch("12", new StubX509Certificate("cn=imap2.g.com"), "imap.g.com", false);
        checkMatch("13", new StubX509Certificate("cn=sub.imap.g.com"), "imap.g.com", false);

        // If a subjectAltName extension of type dNSName is present, that MUST
        // be used as the identity
        checkMatch("21", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_DNS_NAME, "a.y.com")
                , "imap.g.com", false);
        checkMatch("22", new StubX509Certificate("cn=imap.g.com") // This cn should be ignored
                .addSubjectAlternativeName(ALT_DNS_NAME, "a.y.com")
                , "imap.g.com", false);
        checkMatch("23", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_DNS_NAME, "imap.g.com")
                , "imap.g.com", true);

        // With wildcards
        checkMatch("24", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_DNS_NAME, "*.g.com")
                , "imap.g.com", true);


        // host name is ip address
        checkMatch("31", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_IPA_NAME, "1.2.3.4")
                , "1.2.3.4", true);
        checkMatch("32", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_IPA_NAME, "1.2.3.4")
                , "1.2.3.5", false);
        checkMatch("32", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_IPA_NAME, "1.2.3.4")
                .addSubjectAlternativeName(ALT_IPA_NAME, "192.168.100.1")
                , "192.168.100.1", true);

        // Has unknown subject alternative names
        checkMatch("41", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 1")
                .addSubjectAlternativeName(ALT_UNKNOWN,  "random string 2")
                .addSubjectAlternativeName(ALT_DNS_NAME, "a.b.c.d")
                .addSubjectAlternativeName(ALT_DNS_NAME, "*.google.com")
                .addSubjectAlternativeName(ALT_DNS_NAME, "imap.g.com")
                .addSubjectAlternativeName(ALT_IPA_NAME, "2.33.44.55")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 3")
                , "imap.g.com", true);

        checkMatch("42", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 1")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 2")
                .addSubjectAlternativeName(ALT_DNS_NAME, "a.b.c.d")
                .addSubjectAlternativeName(ALT_DNS_NAME, "*.google.com")
                .addSubjectAlternativeName(ALT_DNS_NAME, "imap.g.com")
                .addSubjectAlternativeName(ALT_IPA_NAME, "2.33.44.55")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 3")
                , "2.33.44.55", true);

        checkMatch("43", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 1")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 2")
                .addSubjectAlternativeName(ALT_DNS_NAME, "a.b.c.d")
                .addSubjectAlternativeName(ALT_DNS_NAME, "*.google.com")
                .addSubjectAlternativeName(ALT_DNS_NAME, "imap.g.com")
                .addSubjectAlternativeName(ALT_IPA_NAME, "2.33.44.55")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 3")
                , "g.com", false);

        checkMatch("44", new StubX509Certificate("")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 1")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 2")
                .addSubjectAlternativeName(ALT_DNS_NAME, "a.b.c.d")
                .addSubjectAlternativeName(ALT_DNS_NAME, "*.google.com")
                .addSubjectAlternativeName(ALT_DNS_NAME, "imap.g.com")
                .addSubjectAlternativeName(ALT_IPA_NAME, "2.33.44.55")
                .addSubjectAlternativeName(ALT_UNKNOWN, "random string 3")
                , "2.33.44.1", false);
    }

    private void checkMatch(String message, X509Certificate certificate, String thisDomain,
            boolean expected) {
        Boolean actual = DomainNameValidator.match(certificate, thisDomain);
        assertEquals(message, (Object) expected, (Object) actual);
    }

    /**
     * Tests {@link DomainNameValidator#matchDns}
     */
    public void testMatchDns() {
        checkMatchDns("11", "a.b.c.d", "a.b.c.d", true);
        checkMatchDns("12", "a.b.c.d", "*.b.c.d", true);
        checkMatchDns("13", "b.c.d", "*.b.c.d", true);
        checkMatchDns("14", "b.c.d", "b*.c.d", true);

        checkMatchDns("15", "a.b.c.d", "*.*.c.d", false);
        checkMatchDns("16", "a.b.c.d", "*.c.d", false);

        checkMatchDns("21", "imap.google.com", "imap.google.com", true);
        checkMatchDns("22", "imap2.google.com", "imap.google.com", false);
        checkMatchDns("23", "imap.google.com", "*.google.com", true);
        checkMatchDns("24", "imap2.google.com", "*.google.com", true);
        checkMatchDns("25", "imap.google.com", "*.googl.com", false);
        checkMatchDns("26", "imap2.google2.com", "*.google3.com", false);
        checkMatchDns("27", "imap.google.com", "ima*.google.com", true);
        checkMatchDns("28", "imap.google.com", "imap*.google.com", true);
        checkMatchDns("29", "imap.google.com", "*.imap.google.com", true);

        checkMatchDns("41", "imap.google.com", "a*.google.com", false);
        checkMatchDns("42", "imap.google.com", "ix*.google.com", false);

        checkMatchDns("51", "imap.google.com", "iMap.Google.Com", true);
    }

    private void checkMatchDns(String message, String thisDomain, String thatDomain,
            boolean expected) {
        boolean actual = DomainNameValidator.matchDns(thisDomain, thatDomain);
        assertEquals(message, expected, actual);
    }

    /**
     * Minimal {@link X509Certificate} implementation for {@link DomainNameValidator}.
     */
    private static class StubX509Certificate extends X509Certificate {
        private final X500Principal subjectX500Principal;
        private Collection<List<?>> subjectAlternativeNames;

        public StubX509Certificate(String subjectDn) {
            subjectX500Principal = new X500Principal(subjectDn);
            subjectAlternativeNames = null;
        }

        public StubX509Certificate addSubjectAlternativeName(int type, String name) {
            if (subjectAlternativeNames == null) {
                subjectAlternativeNames = new ArrayList<List<?>>();
            }
            LinkedList<Object> entry = new LinkedList<Object>();
            entry.add(type);
            entry.add(name);
            subjectAlternativeNames.add(entry);
            return this;
        }

        @Override
        public Collection<List<?>> getSubjectAlternativeNames() throws CertificateParsingException {
            return subjectAlternativeNames;
        }

        @Override
        public X500Principal getSubjectX500Principal() {
            return subjectX500Principal;
        }

        @Override
        public void checkValidity() throws CertificateExpiredException,
                CertificateNotYetValidException {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public void checkValidity(Date date) throws CertificateExpiredException,
                CertificateNotYetValidException {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public int getBasicConstraints() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public Principal getIssuerDN() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public boolean[] getIssuerUniqueID() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public boolean[] getKeyUsage() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public Date getNotAfter() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public Date getNotBefore() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public BigInteger getSerialNumber() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public String getSigAlgName() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public String getSigAlgOID() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public byte[] getSigAlgParams() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public byte[] getSignature() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public Principal getSubjectDN() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public boolean[] getSubjectUniqueID() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public byte[] getTBSCertificate() throws CertificateEncodingException {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public int getVersion() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public PublicKey getPublicKey() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public String toString() {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public void verify(PublicKey key) throws CertificateException, NoSuchAlgorithmException,
                InvalidKeyException, NoSuchProviderException, SignatureException {
            throw new RuntimeException("Method not implemented");
        }

        @Override
        public void verify(PublicKey key, String sigProvider) throws CertificateException,
                NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException,
                SignatureException {
            throw new RuntimeException("Method not implemented");
        }

        public Set<String> getCriticalExtensionOIDs() {
            throw new RuntimeException("Method not implemented");
        }

        public byte[] getExtensionValue(String oid) {
            throw new RuntimeException("Method not implemented");
        }

        public Set<String> getNonCriticalExtensionOIDs() {
            throw new RuntimeException("Method not implemented");
        }

        public boolean hasUnsupportedCriticalExtension() {
            throw new RuntimeException("Method not implemented");
        }
    }
}
