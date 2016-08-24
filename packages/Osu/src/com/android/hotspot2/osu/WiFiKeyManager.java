package com.android.hotspot2.osu;

import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

public class WiFiKeyManager implements X509KeyManager {
    private final KeyStore mKeyStore;
    private final Map<X500Principal, String[]> mAliases = new HashMap<>();

    public WiFiKeyManager(KeyStore keyStore) throws IOException {
        mKeyStore = keyStore;
    }

    public void enableClientAuth(List<String> issuerNames) throws GeneralSecurityException,
            IOException {

        Set<X500Principal> acceptedIssuers = new HashSet<>();
        for (String issuerName : issuerNames) {
            acceptedIssuers.add(new X500Principal(issuerName));
        }

        Enumeration<String> aliases = mKeyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate cert = mKeyStore.getCertificate(alias);
            if ((cert instanceof X509Certificate) && mKeyStore.getKey(alias, null) != null) {
                X509Certificate x509Certificate = (X509Certificate) cert;
                X500Principal issuer = x509Certificate.getIssuerX500Principal();
                if (acceptedIssuers.contains(issuer)) {
                    mAliases.put(issuer, new String[]{alias, cert.getPublicKey().getAlgorithm()});
                }
            }
        }

        if (mAliases.isEmpty()) {
            throw new IOException("No aliases match requested issuers: " + issuerNames);
        }
    }

    private static class AliasEntry implements Comparable<AliasEntry> {
        private final int mPreference;
        private final String mAlias;

        private AliasEntry(int preference, String alias) {
            mPreference = preference;
            mAlias = alias;
        }

        public int getPreference() {
            return mPreference;
        }

        public String getAlias() {
            return mAlias;
        }

        @Override
        public int compareTo(AliasEntry other) {
            return Integer.compare(getPreference(), other.getPreference());
        }
    }

    @Override
    public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {

        Map<String, Integer> keyPrefs = new HashMap<>(keyTypes.length);
        int pref = 0;
        for (String keyType : keyTypes) {
            keyPrefs.put(keyType, pref++);
        }

        List<AliasEntry> aliases = new ArrayList<>();
        if (issuers != null) {
            for (Principal issuer : issuers) {
                if (issuer instanceof X500Principal) {
                    String[] aliasAndKey = mAliases.get((X500Principal) issuer);
                    if (aliasAndKey != null) {
                        Integer preference = keyPrefs.get(aliasAndKey[1]);
                        if (preference != null) {
                            aliases.add(new AliasEntry(preference, aliasAndKey[0]));
                        }
                    }
                }
            }
        } else {
            for (String[] aliasAndKey : mAliases.values()) {
                Integer preference = keyPrefs.get(aliasAndKey[1]);
                if (preference != null) {
                    aliases.add(new AliasEntry(preference, aliasAndKey[0]));
                }
            }
        }
        Collections.sort(aliases);
        return aliases.isEmpty() ? null : aliases.get(0).getAlias();
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        List<String> aliases = new ArrayList<>();
        if (issuers != null) {
            for (Principal issuer : issuers) {
                if (issuer instanceof X500Principal) {
                    String[] aliasAndKey = mAliases.get((X500Principal) issuer);
                    if (aliasAndKey != null) {
                        aliases.add(aliasAndKey[0]);
                    }
                }
            }
        } else {
            for (String[] aliasAndKey : mAliases.values()) {
                aliases.add(aliasAndKey[0]);
            }
        }
        return aliases.isEmpty() ? null : aliases.toArray(new String[aliases.size()]);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        try {
            List<X509Certificate> certs = new ArrayList<>();
            for (Certificate certificate : mKeyStore.getCertificateChain(alias)) {
                if (certificate instanceof X509Certificate) {
                    certs.add((X509Certificate) certificate);
                }
            }
            return certs.toArray(new X509Certificate[certs.size()]);
        } catch (KeyStoreException kse) {
            Log.w(OSUManager.TAG, "Failed to retrieve certificates: " + kse);
            return null;
        }
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        try {
            return (PrivateKey) mKeyStore.getKey(alias, null);
        } catch (GeneralSecurityException gse) {
            Log.w(OSUManager.TAG, "Failed to retrieve private key: " + gse);
            return null;
        }
    }
}
