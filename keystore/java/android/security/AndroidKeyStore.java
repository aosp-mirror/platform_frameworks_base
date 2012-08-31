/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.security;

import org.apache.harmony.xnet.provider.jsse.OpenSSLDSAPrivateKey;
import org.apache.harmony.xnet.provider.jsse.OpenSSLEngine;
import org.apache.harmony.xnet.provider.jsse.OpenSSLRSAPrivateKey;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * A java.security.KeyStore interface for the Android KeyStore. An instance of
 * it can be created via the {@link java.security.KeyStore#getInstance(String)
 * KeyStore.getInstance("AndroidKeyStore")} interface. This returns a
 * java.security.KeyStore backed by this "AndroidKeyStore" implementation.
 * <p>
 * This is built on top of Android's keystore daemon. The convention of alias
 * use is:
 * <p>
 * PrivateKeyEntry will have a Credentials.USER_PRIVATE_KEY as the private key,
 * Credentials.USER_CERTIFICATE as the first certificate in the chain (the one
 * that corresponds to the private key), and then a Credentials.CA_CERTIFICATE
 * entry which will have the rest of the chain concatenated in BER format.
 * <p>
 * TrustedCertificateEntry will just have a Credentials.CA_CERTIFICATE entry
 * with a single certificate.
 *
 * @hide
 */
public class AndroidKeyStore extends KeyStoreSpi {
    public static final String NAME = "AndroidKeyStore";

    private android.security.KeyStore mKeyStore;

    @Override
    public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException,
            UnrecoverableKeyException {
        if (!isKeyEntry(alias)) {
            return null;
        }

        final OpenSSLEngine engine = OpenSSLEngine.getInstance("keystore");
        try {
            return engine.getPrivateKeyById(Credentials.USER_PRIVATE_KEY + alias);
        } catch (InvalidKeyException e) {
            UnrecoverableKeyException t = new UnrecoverableKeyException("Can't get key");
            t.initCause(e);
            throw t;
        }
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        final X509Certificate leaf = (X509Certificate) engineGetCertificate(alias);
        if (leaf == null) {
            return null;
        }

        final Certificate[] caList;

        final byte[] caBytes = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        if (caBytes != null) {
            final Collection<X509Certificate> caChain = toCertificates(caBytes);

            caList = new Certificate[caChain.size() + 1];

            final Iterator<X509Certificate> it = caChain.iterator();
            int i = 1;
            while (it.hasNext()) {
                caList[i++] = it.next();
            }
        } else {
            caList = new Certificate[1];
        }

        caList[0] = leaf;

        return caList;
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        byte[] certificate = mKeyStore.get(Credentials.USER_CERTIFICATE + alias);
        if (certificate != null) {
            return toCertificate(certificate);
        }

        certificate = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        if (certificate != null) {
            return toCertificate(certificate);
        }

        return null;
    }

    private static X509Certificate toCertificate(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) certFactory
                    .generateCertificate(new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificate in keystore", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<X509Certificate> toCertificates(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (Collection<X509Certificate>) certFactory
                    .generateCertificates(new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificates in keystore", e);
            return new ArrayList<X509Certificate>();
        }
    }

    private Date getModificationDate(String alias) {
        final long epochMillis = mKeyStore.getmtime(alias);
        if (epochMillis == -1L) {
            return null;
        }

        return new Date(epochMillis);
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        Date d = getModificationDate(Credentials.USER_PRIVATE_KEY + alias);
        if (d != null) {
            return d;
        }

        d = getModificationDate(Credentials.USER_CERTIFICATE + alias);
        if (d != null) {
            return d;
        }

        return getModificationDate(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
            throws KeyStoreException {
        if ((password != null) && (password.length > 0)) {
            throw new KeyStoreException("entries cannot be protected with passwords");
        }

        if (key instanceof PrivateKey) {
            setPrivateKeyEntry(alias, (PrivateKey) key, chain);
        } else {
            throw new KeyStoreException("Only PrivateKeys are supported");
        }
    }

    private void setPrivateKeyEntry(String alias, PrivateKey key, Certificate[] chain)
            throws KeyStoreException {
        byte[] keyBytes = null;

        final String pkeyAlias;
        if (key instanceof OpenSSLRSAPrivateKey) {
            pkeyAlias = ((OpenSSLRSAPrivateKey) key).getPkeyAlias();
        } else if (key instanceof OpenSSLDSAPrivateKey) {
            pkeyAlias = ((OpenSSLDSAPrivateKey) key).getPkeyAlias();
        } else {
            pkeyAlias = null;
        }

        final boolean shouldReplacePrivateKey;
        if (pkeyAlias != null && pkeyAlias.startsWith(Credentials.USER_PRIVATE_KEY)) {
            final String keySubalias = pkeyAlias.substring(Credentials.USER_PRIVATE_KEY.length());
            if (!alias.equals(keySubalias)) {
                throw new KeyStoreException("Can only replace keys with same alias: " + alias
                        + " != " + keySubalias);
            }

            shouldReplacePrivateKey = false;
        } else {
            // Make sure the PrivateKey format is the one we support.
            final String keyFormat = key.getFormat();
            if ((keyFormat == null) || (!"PKCS#8".equals(keyFormat))) {
                throw new KeyStoreException(
                        "Only PrivateKeys that can be encoded into PKCS#8 are supported");
            }

            // Make sure we can actually encode the key.
            keyBytes = key.getEncoded();
            if (keyBytes == null) {
                throw new KeyStoreException("PrivateKey has no encoding");
            }

            shouldReplacePrivateKey = true;
        }

        // Make sure the chain exists since this is a PrivateKey
        if ((chain == null) || (chain.length == 0)) {
            throw new KeyStoreException("Must supply at least one Certificate with PrivateKey");
        }

        // Do chain type checking.
        X509Certificate[] x509chain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            if (!"X.509".equals(chain[i].getType())) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            if (!(chain[i] instanceof X509Certificate)) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            x509chain[i] = (X509Certificate) chain[i];
        }

        final byte[] userCertBytes;
        try {
            userCertBytes = x509chain[0].getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException("Couldn't encode certificate #1", e);
        }

        /*
         * If we have a chain, store it in the CA certificate slot for this
         * alias as concatenated DER-encoded certificates. These can be
         * deserialized by {@link CertificateFactory#generateCertificates}.
         */
        final byte[] chainBytes;
        if (chain.length > 1) {
            /*
             * The chain is passed in as {user_cert, ca_cert_1, ca_cert_2, ...}
             * so we only need the certificates starting at index 1.
             */
            final byte[][] certsBytes = new byte[x509chain.length - 1][];
            int totalCertLength = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                try {
                    certsBytes[i] = x509chain[i + 1].getEncoded();
                    totalCertLength += certsBytes[i].length;
                } catch (CertificateEncodingException e) {
                    throw new KeyStoreException("Can't encode Certificate #" + i, e);
                }
            }

            /*
             * Serialize this into one byte array so we can later call
             * CertificateFactory#generateCertificates to recover them.
             */
            chainBytes = new byte[totalCertLength];
            int outputOffset = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                final int certLength = certsBytes[i].length;
                System.arraycopy(certsBytes[i], 0, chainBytes, outputOffset, certLength);
                outputOffset += certLength;
                certsBytes[i] = null;
            }
        } else {
            chainBytes = null;
        }

        /*
         * Make sure we clear out all the appropriate types before trying to
         * write.
         */
        if (shouldReplacePrivateKey) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
        } else {
            Credentials.deleteCertificateTypesForAlias(mKeyStore, alias);
        }

        if (shouldReplacePrivateKey
                && !mKeyStore.importKey(Credentials.USER_PRIVATE_KEY + alias, keyBytes)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new KeyStoreException("Couldn't put private key in keystore");
        } else if (!mKeyStore.put(Credentials.USER_CERTIFICATE + alias, userCertBytes)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new KeyStoreException("Couldn't put certificate #1 in keystore");
        } else if (chainBytes != null
                && !mKeyStore.put(Credentials.CA_CERTIFICATE + alias, chainBytes)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new KeyStoreException("Couldn't put certificate chain in keystore");
        }
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] userKey, Certificate[] chain)
            throws KeyStoreException {
        throw new KeyStoreException("Operation not supported because key encoding is unknown");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
        if (isKeyEntry(alias)) {
            throw new KeyStoreException("Entry exists and is not a trusted certificate");
        }

        // We can't set something to null.
        if (cert == null) {
            throw new NullPointerException("cert == null");
        }

        final byte[] encoded;
        try {
            encoded = cert.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException(e);
        }

        if (!mKeyStore.put(Credentials.CA_CERTIFICATE + alias, encoded)) {
            throw new KeyStoreException("Couldn't insert certificate; is KeyStore initialized?");
        }
    }

    @Override
    public void engineDeleteEntry(String alias) throws KeyStoreException {
        if (!isKeyEntry(alias) && !isCertificateEntry(alias)) {
            return;
        }

        if (!Credentials.deleteAllTypesForAlias(mKeyStore, alias)) {
            throw new KeyStoreException("No such entry " + alias);
        }
    }

    private Set<String> getUniqueAliases() {
        final String[] rawAliases = mKeyStore.saw("");
        if (rawAliases == null) {
            return new HashSet<String>();
        }

        final Set<String> aliases = new HashSet<String>(rawAliases.length);
        for (String alias : rawAliases) {
            final int idx = alias.indexOf('_');
            if ((idx == -1) || (alias.length() <= idx)) {
                Log.e(NAME, "invalid alias: " + alias);
                continue;
            }

            aliases.add(new String(alias.substring(idx + 1)));
        }

        return aliases;
    }

    @Override
    public Enumeration<String> engineAliases() {
        return Collections.enumeration(getUniqueAliases());
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias)
                || mKeyStore.contains(Credentials.USER_CERTIFICATE + alias)
                || mKeyStore.contains(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public int engineSize() {
        return getUniqueAliases().size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        return isKeyEntry(alias);
    }

    private boolean isKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.USER_PRIVATE_KEY + alias);
    }

    private boolean isCertificateEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return mKeyStore.contains(Credentials.CA_CERTIFICATE + alias);
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
        return !isKeyEntry(alias) && isCertificateEntry(alias);
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
        if (cert == null) {
            return null;
        }

        final Set<String> nonCaEntries = new HashSet<String>();

        /*
         * First scan the PrivateKeyEntry types. The KeyStoreSpi documentation
         * says to only compare the first certificate in the chain which is
         * equivalent to the USER_CERTIFICATE prefix for the Android keystore
         * convention.
         */
        final String[] certAliases = mKeyStore.saw(Credentials.USER_CERTIFICATE);
        for (String alias : certAliases) {
            final byte[] certBytes = mKeyStore.get(Credentials.USER_CERTIFICATE + alias);
            if (certBytes == null) {
                continue;
            }

            final Certificate c = toCertificate(certBytes);
            nonCaEntries.add(alias);

            if (cert.equals(c)) {
                return alias;
            }
        }

        /*
         * Look at all the TrustedCertificateEntry types. Skip all the
         * PrivateKeyEntry we looked at above.
         */
        final String[] caAliases = mKeyStore.saw(Credentials.CA_CERTIFICATE);
        for (String alias : caAliases) {
            if (nonCaEntries.contains(alias)) {
                continue;
            }

            final byte[] certBytes = mKeyStore.get(Credentials.CA_CERTIFICATE + alias);
            if (certBytes == null) {
                continue;
            }

            final Certificate c = toCertificate(mKeyStore.get(Credentials.CA_CERTIFICATE + alias));
            if (cert.equals(c)) {
                return alias;
            }
        }

        return null;
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        throw new UnsupportedOperationException("Can not serialize AndroidKeyStore to OutputStream");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        if (stream != null) {
            throw new IllegalArgumentException("InputStream not supported");
        }

        if (password != null) {
            throw new IllegalArgumentException("password not supported");
        }

        // Unfortunate name collision.
        mKeyStore = android.security.KeyStore.getInstance();
    }

}
