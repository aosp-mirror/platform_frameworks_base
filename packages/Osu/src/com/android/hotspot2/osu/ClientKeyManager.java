package com.android.hotspot2.osu;

import android.util.Log;

import com.android.hotspot2.flow.PlatformAdapter;
import com.android.hotspot2.pps.HomeSP;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.X509KeyManager;

public class ClientKeyManager implements X509KeyManager {
    private final KeyStore mKeyStore;
    private final Map<OSUCertType, String> mAliasMap;
    private final Map<OSUCertType, Object> mTempKeys;

    private static final String sTempAlias = "client-alias";

    public ClientKeyManager(HomeSP homeSP, KeyStore keyStore) throws IOException {
        mKeyStore = keyStore;
        mAliasMap = new HashMap<>();
        mAliasMap.put(OSUCertType.AAA, PlatformAdapter.CERT_CLT_CA_ALIAS + homeSP.getFQDN());
        mAliasMap.put(OSUCertType.Client, PlatformAdapter.CERT_CLT_CERT_ALIAS + homeSP.getFQDN());
        mAliasMap.put(OSUCertType.PrivateKey, PlatformAdapter.CERT_CLT_KEY_ALIAS + homeSP.getFQDN());
        mTempKeys = new HashMap<>();
    }

    public void reloadKeys(Map<OSUCertType, List<X509Certificate>> certs, PrivateKey key)
            throws IOException {
        List<X509Certificate> clientCerts = certs.get(OSUCertType.Client);
        X509Certificate[] certArray = new X509Certificate[clientCerts.size()];
        int n = 0;
        for (X509Certificate cert : clientCerts) {
            certArray[n++] = cert;
        }
        mTempKeys.put(OSUCertType.Client, certArray);
        mTempKeys.put(OSUCertType.PrivateKey, key);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        if (mTempKeys.isEmpty()) {
            return mAliasMap.get(OSUCertType.Client);
        } else {
            return sTempAlias;
        }
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        if (mTempKeys.isEmpty()) {
            String alias = mAliasMap.get(OSUCertType.Client);
            return alias != null ? new String[]{alias} : null;
        } else {
            return new String[]{sTempAlias};
        }
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
        if (mTempKeys.isEmpty()) {
            if (!mAliasMap.get(OSUCertType.Client).equals(alias)) {
                Log.w(OSUManager.TAG, "Bad cert alias requested: '" + alias + "'");
                return null;
            }
            try {
                Certificate cert = mKeyStore.getCertificate(alias);
                return new X509Certificate[] {(X509Certificate) cert};
            } catch (KeyStoreException kse) {
                Log.w(OSUManager.TAG, "Failed to retrieve certificates: " + kse);
                return null;
            }
        } else if (sTempAlias.equals(alias)) {
            return (X509Certificate[]) mTempKeys.get(OSUCertType.Client);
        } else {
            Log.w(OSUManager.TAG, "Bad cert alias requested: '" + alias + "'");
            return null;
        }
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        if (mTempKeys.isEmpty()) {
            if (!mAliasMap.get(OSUCertType.Client).equals(alias)) {
                Log.w(OSUManager.TAG, "Bad key alias requested: '" + alias + "'");
            }
            try {
                return (PrivateKey) mKeyStore.getKey(mAliasMap.get(OSUCertType.PrivateKey), null);
            } catch (GeneralSecurityException gse) {
                Log.w(OSUManager.TAG, "Failed to retrieve private key: " + gse);
                return null;
            }
        } else if (sTempAlias.equals(alias)) {
            return (PrivateKey) mTempKeys.get(OSUCertType.PrivateKey);
        } else {
            Log.w(OSUManager.TAG, "Bad cert alias requested: '" + alias + "'");
            return null;
        }
    }
}
