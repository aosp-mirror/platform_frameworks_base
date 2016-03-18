package com.android.hotspot2.osu;

import android.net.Network;
import android.util.Base64;
import android.util.Log;

import com.android.hotspot2.Utils;
import com.android.hotspot2.flow.PlatformAdapter;
import com.android.hotspot2.pps.HomeSP;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class OSUSocketFactory {
    private static final long ConnectionTimeout = 10000L;
    private static final long ReconnectWait = 2000L;

    private static final String SecureHTTP = "https";
    private static final String UnsecureHTTP = "http";
    private static final String EKU_ID = "2.5.29.37";
    private static final Set<String> EKU_ID_SET = new HashSet<>(Arrays.asList(EKU_ID));
    private static final EKUChecker sEKUChecker = new EKUChecker();

    private final Network mNetwork;
    private final SocketFactory mSocketFactory;
    private final KeyManager mKeyManager;
    private final WFATrustManager mTrustManager;
    private final List<InetSocketAddress> mRemotes;

    public static Set<X509Certificate> buildCertSet() {
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            Set<X509Certificate> set = new HashSet<>();
            for (String b64 : WFACerts) {
                ByteArrayInputStream bis = new ByteArrayInputStream(
                        Base64.decode(b64, Base64.DEFAULT));
                X509Certificate cert = (X509Certificate) certFactory.generateCertificate(bis);
                set.add(cert);
            }
            return set;
        } catch (CertificateException ce) {
            Log.e(OSUManager.TAG, "Cannot build CA cert set");
            return null;
        }
    }

    public static OSUSocketFactory getSocketFactory(KeyStore ks, HomeSP homeSP,
                                                    OSUFlowManager.FlowType flowType,
                                                    Network network, URL url, KeyManager km,
                                                    boolean enforceSecurity)
            throws GeneralSecurityException, IOException {

        if (enforceSecurity && !url.getProtocol().equalsIgnoreCase(SecureHTTP)) {
            throw new IOException("Protocol '" + url.getProtocol() + "' is not secure");
        }
        return new OSUSocketFactory(ks, homeSP, flowType, network, url, km);
    }

    private OSUSocketFactory(KeyStore ks, HomeSP homeSP, OSUFlowManager.FlowType flowType,
                             Network network,
                             URL url, KeyManager km) throws GeneralSecurityException, IOException {
        mNetwork = network;
        mKeyManager = km;
        mTrustManager = new WFATrustManager(ks, homeSP, flowType);
        int port;
        switch (url.getProtocol()) {
            case UnsecureHTTP:
                mSocketFactory = new DefaultSocketFactory();
                port = url.getPort() > 0 ? url.getPort() : 80;
                break;
            case SecureHTTP:
                SSLContext tlsContext = SSLContext.getInstance("TLSv1");
                tlsContext.init(km != null ? new KeyManager[]{km} : null,
                        new TrustManager[]{mTrustManager}, null);
                mSocketFactory = tlsContext.getSocketFactory();
                port = url.getPort() > 0 ? url.getPort() : 443;
                break;
            default:
                throw new IOException("Bad URL: " + url);
        }
        if (OSUManager.R2_MOCK && url.getHost().endsWith(".wi-fi.org")) {
            // !!! Warning: Ruckus hack!
            mRemotes = new ArrayList<>(1);
            mRemotes.add(new InetSocketAddress(InetAddress.getByName("10.123.107.107"), port));
        } else {
            InetAddress[] remotes = mNetwork.getAllByName(url.getHost());
            android.util.Log.d(OSUManager.TAG, "'" + url.getHost() + "' resolves to " +
                    Arrays.toString(remotes));
            if (remotes == null || remotes.length == 0) {
                throw new IOException("Failed to look up host from " + url);
            }
            mRemotes = new ArrayList<>(remotes.length);
            for (InetAddress remote : remotes) {
                mRemotes.add(new InetSocketAddress(remote, port));
            }
        }
        Collections.shuffle(mRemotes);
    }

    public void reloadKeys(Map<OSUCertType, List<X509Certificate>> certs, PrivateKey key)
            throws IOException {
        if (mKeyManager instanceof ClientKeyManager) {
            ((ClientKeyManager) mKeyManager).reloadKeys(certs, key);
        }
    }

    public Socket createSocket() throws IOException {
        Socket socket = mSocketFactory.createSocket();
        mNetwork.bindSocket(socket);

        long bail = System.currentTimeMillis() + ConnectionTimeout;
        boolean success = false;

        while (System.currentTimeMillis() < bail) {
            for (InetSocketAddress remote : mRemotes) {
                try {
                    socket.connect(remote);
                    Log.d(OSUManager.TAG, "Connection " + socket.getLocalSocketAddress() +
                            " to " + socket.getRemoteSocketAddress());
                    success = true;
                    break;
                } catch (IOException ioe) {
                    Log.d(OSUManager.TAG, "Failed to connect to " + remote + ": " + ioe);
                    socket = mSocketFactory.createSocket();
                    mNetwork.bindSocket(socket);
                }
            }
            if (success) {
                break;
            }
            Utils.delay(ReconnectWait);
        }
        if (!success) {
            throw new IOException("No available network");
        }
        return socket;
    }

    public X509Certificate getOSUCertificate(URL url) throws GeneralSecurityException {
        String fqdn = url.getHost();
        for (X509Certificate certificate : mTrustManager.getTrustChain()) {
            for (List<?> name : certificate.getSubjectAlternativeNames()) {
                if (name.size() >= SPVerifier.DNSName &&
                        name.get(0).getClass() == Integer.class &&
                        name.get(1).toString().equals(fqdn)) {
                    return certificate;
                }
            }
        }
        return null;
    }

    final class DefaultSocketFactory extends SocketFactory {

        DefaultSocketFactory() {
        }

        @Override
        public Socket createSocket() throws IOException {
            return new Socket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return new Socket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return new Socket(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return new Socket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                                   int localPort) throws IOException {
            return new Socket(address, port, localAddress, localPort);
        }
    }

    private static class WFATrustManager implements X509TrustManager {
        private final KeyStore mKeyStore;
        private final HomeSP mHomeSP;
        private final OSUFlowManager.FlowType mFlowType;
        private X509Certificate[] mTrustChain;

        private WFATrustManager(KeyStore ks, HomeSP homeSP, OSUFlowManager.FlowType flowType)
                throws CertificateException {
            mKeyStore = ks;
            mHomeSP = homeSP;
            mFlowType = flowType;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            // N/A
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            Log.d("TLSOSU", "Checking " + chain.length + " certs.");

            try {
                CertPathValidator validator =
                        CertPathValidator.getInstance(CertPathValidator.getDefaultType());
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                CertPath path = certFactory.generateCertPath(
                        Arrays.asList(chain));
                Set<TrustAnchor> trustAnchors = new HashSet<>();
                if (mHomeSP == null) {
                    for (X509Certificate cert : getRootCerts(mKeyStore)) {
                        trustAnchors.add(new TrustAnchor(cert, null));
                    }
                } else {
                    String prefix = mFlowType == OSUFlowManager.FlowType.Remediation ?
                            PlatformAdapter.CERT_REM_ALIAS : PlatformAdapter.CERT_POLICY_ALIAS;

                    X509Certificate cert = getCert(mKeyStore, prefix + mHomeSP.getFQDN());
                    if (cert == null) {
                        cert = getCert(mKeyStore,
                                PlatformAdapter.CERT_SHARED_ALIAS + mHomeSP.getFQDN());
                    }
                    if (cert == null) {
                        for (X509Certificate root : getRootCerts(mKeyStore)) {
                            trustAnchors.add(new TrustAnchor(root, null));
                        }
                    } else {
                        trustAnchors.add(new TrustAnchor(cert, null));
                    }
                }
                PKIXParameters params = new PKIXParameters(trustAnchors);
                params.setRevocationEnabled(false);
                params.addCertPathChecker(sEKUChecker);
                validator.validate(path, params);
                mTrustChain = chain;
            } catch (GeneralSecurityException gse) {
                throw new SecurityException(gse);
            }
            mTrustChain = chain;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        public X509Certificate[] getTrustChain() {
            return mTrustChain != null ? mTrustChain : new X509Certificate[0];
        }
    }

    private static X509Certificate getCert(KeyStore keyStore, String alias)
            throws KeyStoreException {
        Certificate cert = keyStore.getCertificate(alias);
        if (cert != null && cert instanceof X509Certificate) {
            return (X509Certificate) cert;
        }
        return null;
    }

    public static Set<X509Certificate> getRootCerts(KeyStore keyStore) throws KeyStoreException {
        Set<X509Certificate> certSet = new HashSet<>();
        int index = 0;
        for (int n = 0; n < 1000; n++) {
            Certificate cert = keyStore.getCertificate(
                    String.format("%s%d", PlatformAdapter.CERT_WFA_ALIAS, index));
            if (cert == null) {
                break;
            } else if (cert instanceof X509Certificate) {
                certSet.add((X509Certificate) cert);
            }
            index++;
        }
        return certSet;
    }

    private static class EKUChecker extends PKIXCertPathChecker {
        @Override
        public void init(boolean forward) throws CertPathValidatorException {

        }

        @Override
        public boolean isForwardCheckingSupported() {
            return true;
        }

        @Override
        public Set<String> getSupportedExtensions() {
            return EKU_ID_SET;
        }

        @Override
        public void check(Certificate cert, Collection<String> unresolvedCritExts)
                throws CertPathValidatorException {
            Log.d(OSUManager.TAG, "Checking EKU " + unresolvedCritExts);
            unresolvedCritExts.remove(EKU_ID);
        }
    }

    /*
     *
      Subject: CN=osu-server.r2-testbed-rks.wi-fi.org, O=Intel Corporation CCG DRD, C=US
      Signature Algorithm: SHA256withRSA, OID = 1.2.840.113549.1.1.11
      Validity: [From: Wed Jan 28 16:00:00 PST 2015,
                   To: Sat Jan 28 15:59:59 PST 2017]
      Issuer: CN="NetworkFX, Inc. Hotspot 2.0 Intermediate CA", OU=OSU CA - 01, O="NetworkFX, Inc.", C=US
      SerialNumber: [    312af3db 138eae19 1defbce2 e2b88b55]
    *
    *
      Subject: CN="NetworkFX, Inc. Hotspot 2.0 Intermediate CA", OU=OSU CA - 01, O="NetworkFX, Inc.", C=US
      Signature Algorithm: SHA256withRSA, OID = 1.2.840.113549.1.1.11
      Validity: [From: Tue Nov 19 16:00:00 PST 2013,
                   To: Sun Nov 19 15:59:59 PST 2023]
      Issuer: CN=Hotspot 2.0 Trust Root CA - 01, O=WFA Hotspot 2.0, C=US
      SerialNumber: [    4152b1b0 301495f3 8fa76428 2ef41046]
     */

    public static final String[] WFACerts = {
            "MIIFbDCCA1SgAwIBAgIQDLMPcPKGpDPguQmJ3gHttzANBgkqhkiG9w0BAQsFADBQ" +
                    "MQswCQYDVQQGEwJVUzEYMBYGA1UEChMPV0ZBIEhvdHNwb3QgMi4wMScwJQYDVQQD" +
                    "Ex5Ib3RzcG90IDIuMCBUcnVzdCBSb290IENBIC0gMDMwHhcNMTMxMjA4MTIwMDAw" +
                    "WhcNNDMxMjA4MTIwMDAwWjBQMQswCQYDVQQGEwJVUzEYMBYGA1UEChMPV0ZBIEhv" +
                    "dHNwb3QgMi4wMScwJQYDVQQDEx5Ib3RzcG90IDIuMCBUcnVzdCBSb290IENBIC0g" +
                    "MDMwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCsdEtReIUbMlO+hR6b" +
                    "yQk4nGVITv3meYTaDeVwZnQVal8EjHuu4Kd89g8yRYVTv3J1kq9ukE7CDrDehrXK" +
                    "ym+8VlR7ro0lB/lwRyNk3W7yNccg3AknQ0x5fKVwcFznwD/FYg37owGmhGFtpMTB" +
                    "cxzreQaLXvLta8YNlJU10ZkfputBpzi9bLPWsLOkIrQw7KH1Wc+Oiy4hUMUbTlSi" +
                    "cjqacKPR188mVIoxxUoICHyVV1KvMmYZrVdc/b5dbmd0haMHxC0VSqbydXxxS7vv" +
                    "/lCrC2d5qbKE66PiuBPkhzyU7SI9C8GU/S7akYm1MMSTn5W7lSp2AWRDnf9LQg51" +
                    "dLvDxJ7t2fruXtSkkqG/cwY1yQI8O+WZYPDThKPcDmNbaxVE9lOizAHXFVsfYrXA" +
                    "PbbMOkzKehYwaIikmNgcpxtQNw+wikJiZb9N8VwwtwHK71XEFi+n5DGlPa9VDYgB" +
                    "YkBcxvVo2rbE3i3teQgHm+pWZNP08aFNWwMk9yQkm/SOGdLq1jLbQA9yd7fyR1Ct" +
                    "W1GLzKi1Ojr/6XiB9/noL3oxP/+gb8OSgcqVfkZp4QLvrGdlKiOI2fE7Bslmzn6l" +
                    "B3UTpApjab7BQ99rCXzDwt3Xd7IrCtAJNkxi302J7k6hnGlW8S4oPQBElkOtoH9y" +
                    "XEhp9rNS0lZiuwtFmWW2q50fkQIDAQABo0IwQDAPBgNVHRMBAf8EBTADAQH/MA4G" +
                    "A1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUZw5JLGEXnuvt4FTnhNmbrWRgc2UwDQYJ" +
                    "KoZIhvcNAQELBQADggIBAFPoGFDyzFg9B9+jJUPGW32omftBhChVcgjllI07RCie" +
                    "KTMBi47+auuLgiMox3xRyP7/dX7YaUeMXEQ1BMv6nlrsXWv1lH4yu+RNuehPlqRs" +
                    "fY351mAfPtQ654SBUi0Wg++9iyTOfgF5a9IWEDt4lnSZMvA4vlw8pUCz6zpKXHnA" +
                    "RXKrpY3bU+2dnrFDKR0XQhmAQdo7UvdsT1elVoFIxHhLpwfzx+kpEhtrXw3nGgt+" +
                    "M4jNp684XoWpxVGaQ4Vvv00Sm2DQ8jq2sf9F+kRWszZpQOTiMGKZr0lX2CI5cww1" +
                    "dfmd1BkAjI9cIWLkD8YSeaggZzvYe1o9d7e7lKfdJmjDlSQ0uBiG77keUK4tF2fi" +
                    "xFTxibtPux56p3GYQ2GdRsBaKjH3A3HMJSKXwIGR+wb1sgz/bBdlyJSylG8hYD//" +
                    "0Hyo+UrMUszAdszoPhMY+4Ol3QE3QRWzXi+W/NtKeYD2K8xUzjZM10wMdxCfoFOa" +
                    "8bzzWnxZQlnu880ULUSHIxDPeE+DDZYYOaN1hV2Rh/hrFKvvV+gJj2eXHF5G7y9u" +
                    "Yg7nHYCCf7Hy8UTIXDtAAeDCQNon1ReN8G+XOqhLQ9TalmnJ5U5ARtC0MdQDht7T" +
                    "DZpWeEVv+pQHARX9GDV/T85MV2RPJWKqfZ6kK0gvQDkunADdg8IhZAjwMMx3k6B/",

            "MIIFbDCCA1SgAwIBAgIQaAV8NQv/Xdusi4IU+tpUfjANBgkqhkiG9w0BAQsFADBQ" +
                    "MQswCQYDVQQGEwJVUzEYMBYGA1UEChMPV0ZBIEhvdHNwb3QgMi4wMScwJQYDVQQD" +
                    "Ex5Ib3RzcG90IDIuMCBUcnVzdCBSb290IENBIC0gMDEwHhcNMTMxMTIwMDAwMDAw" +
                    "WhcNNDMxMTE5MjM1OTU5WjBQMQswCQYDVQQGEwJVUzEYMBYGA1UEChMPV0ZBIEhv" +
                    "dHNwb3QgMi4wMScwJQYDVQQDEx5Ib3RzcG90IDIuMCBUcnVzdCBSb290IENBIC0g" +
                    "MDEwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQC/gf4CHxWjr2EcktAZ" +
                    "pHT4z1yFYZILD3ZVqvzzXBK+YKjWhjsgZ28Z1VwXqu51JvVzwTGDalPf5m7zMcJW" +
                    "CpPtPBdxxwQ/cBDPK4w+/sCuYYSddlMLzwZ/IgwFike12tKTR7Kk7Nk6ghrYaxCG" +
                    "R+QEZDVrxITj79vGpgk2otVnMI4d3H9mWt1o6Lx+hVioyBgOvmo2OWHR2uKkbg5h" +
                    "tktXqmBEtzK+qDqIIUY4WRRZHxlOaF2/EdIIGhXlf+Vlr13aPqOPiDiE08o+GARz" +
                    "TIp8BrW2boo0+2kpEFUKiqc427vOYEkUdSMfwu4aGOcuOewc8sk6ztquL/JcPROL" +
                    "VSFSSFR3HKhUto8EJcHEEG9wzcOi1OO/OOSVxjNwiaV/hB9Ed1wvoBhiJ+C+Q8/K" +
                    "HXmoH/ankXDaB06yjt2Ojemt0nO45qlarRj8tO7zbpghJuJxztur47U7PJta7Zcg" +
                    "z7kOPJPTAbzmOU2TXt1pXO1hVnSlV+M1rRwe7qivnSMMrTnkX15YWmyK27/tgJeu" +
                    "muR2YzvPwPtF/m1N0bRKI7FW05NYg3smItFq0E/eyf/orgolcXTZ7zNRyRGnjWNs" +
                    "/w9SDbdby0uVUfdN4V/5uC4HBmA1rikoBbGZ+nzCtesY4yW8eEwMfguVpNT3ueaU" +
                    "q30nufeY2VnA3Rv1WH8TaeZU+wIDAQABo0IwQDAPBgNVHRMBAf8EBTADAQH/MA4G" +
                    "A1UdDwEB/wQEAwIBBjAdBgNVHQ4EFgQU+RjGVZbebjpzEPfthaTLqbvXMiEwDQYJ" +
                    "KoZIhvcNAQELBQADggIBABj3LP1UXVa16HYeXC1+GU1dX/cla1n1bwpIlxRnCZ5/" +
                    "3I3zGw/nRnsLUTkGf8q3XCgin+jX22kyzzQNrgepn0zqBsmAj+pjUUwWzYQUzphc" +
                    "Uzmg4PJRWaEaGG3kvD+wJEC0pWvIhe48qcq8FZCCmjbvecEVn5mM0smPzPyUjf/o" +
                    "fjUMQvVWqug/Ff5HT6kbyDWhC3nD+8IZ5PjyO85OnoBnQkr8WYwr24XJgO2HS2rs" +
                    "W40CzQe3Kdg7HHyef+/iyLYTBJH7EUJPCHGVQtZ3q0aNqURkutXJ/CxKJYMcNTEB" +
                    "x+a09EhZ6DOHQDqsdTuAqGh3VyrxhFk+3suNsxoh6XaRK10VslvdNB/1YKfU8DWe" +
                    "V6XfDH/TR0NIL04exUp3rER8sERulpJGBOnaG6OQKh4bFYDB406+QfusQnvO0aYR" +
                    "UXJzf01B15HRJgpZsggpIuex0UDcJhTTpkRfTj8L4ayUce2ZRsGn3dBaT9ZMx4o9" +
                    "E/YsQyOpfw28gM5u+zZt4BJz4gAaRGbp4r4sk5Vm/P1/0EXJ70Du6K9d0HAHtpEv" +
                    "Y94Ww5W6fpMDdyAKYTXZBgTX3cqtikNkLX/kHH8l4o/XW2sXqU3X7vOYqgeVYoD9" +
                    "NnhZXYCerH4Se5Lgj8/KhXxRWtcn3XduMdkC6UTApMooA64Vs508173Z3lJn2SeQ",

            "MIIFXTCCA0WgAwIBAgIBATANBgkqhkiG9w0BAQsFADBQMQswCQYDVQQGEwJVUzEY" +
                    "MBYGA1UECgwPV0ZBIEhvdHNwb3QgMi4wMScwJQYDVQQDDB5Ib3RzcG90IDIuMCBU" +
                    "cnVzdCBSb290IENBIC0gMDIwHhcNMTMxMjAyMjA1NzU3WhcNNDMxMjAyMjA1NTAz" +
                    "WjBQMQswCQYDVQQGEwJVUzEYMBYGA1UECgwPV0ZBIEhvdHNwb3QgMi4wMScwJQYD" +
                    "VQQDDB5Ib3RzcG90IDIuMCBUcnVzdCBSb290IENBIC0gMDIwggIiMA0GCSqGSIb3" +
                    "DQEBAQUAA4ICDwAwggIKAoICAQDCSoMqNhtTwbnIsINp6nUhx5UFuq9ZQoTv+KDk" +
                    "vAajT0di6+cQG3sAVvZLySmJoiBAv3PizYYLOD4eGMrFQRqi7PmSJ83WqNv23ZYF" +
                    "ryFFJiy/URXc/ALDuB3dgElPt24Mx7n2xDPAh9t82HTmuskpQRrsyg9QPoi5rRRS" +
                    "Djm5mjFJjKChq99RWcweNV/KGH1sTwcmlDmNMScK16A+BBNiSvmZlsGJgAlP369k" +
                    "lnNqt6UiDhepcktuKpHmSvNel+c/xqzR0gURfUnXcZhzjzS94Rx5O+CNWL4EGiJq" +
                    "qKAfk99j/lbD0MWYo7Rh0UKQlXSdohWDiV93hxvvfugej8KUOIb+1wmd1Fi+lwDZ" +
                    "bR2yg2f0qyxbC/tAV4JJNnuDLFb19leD78x+68eAnlbMi+xMH5lINs15+26s2H5d" +
                    "lx9kwRDBJq02LuHnen6FLafWjejnnBQ/PuGD0ACvBegSsDKDaCuTAnTNS6MDmQr4" +
                    "wza08iX360ZN+BbSAnCK1YGa/7J7fhyydwxLJ7s5Eo0b6SUMY87FMc5XmkAk4xxL" +
                    "MLqS2HMtqsGBI5JQT0SgH0ghE6DjMWArBTZcD+swuzTi1/Cz5+Z9Es8xJ3MPvSZW" +
                    "pJi6VVB2eVMAqfHOj4ozHoVpvJypIVGRwWBzVRWom76R47utuRK6uKzoLiB1jwE5" +
                    "vwHpUQIDAQABo0IwQDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBxjAd" +
                    "BgNVHQ4EFgQU5C9c1OMsB+/MOwl9OKG2D/XSwrUwDQYJKoZIhvcNAQELBQADggIB" +
                    "AGULYE/VrnA3K0ptgHrWlQoPfp5wGvScgsmy0wp9qE3b6n/4bLehBKb5w4Y3JVA9" +
                    "gjxoQ5xE2ssDtULZ3nKnGWmMN3qOBoRZCA6KjKs1860p09tm1ScUsajDJ15Tp1nI" +
                    "zfR0oP63+2bJx+JXM8fPKOJe245hj2rs1c3JXsGCe+UVrlGsotG+wR0PdrejaXJ8" +
                    "HbhBQHcbhgjsD1Gb6Egm4YxRKAtcVY3q9EKKWAGhbC1qvCh1iLNKo3FeGgm2r3EG" +
                    "L4cYJBb2fhSKltjISqCDhYq4tplOIeQSJJyJC8gfW/BnMU39lTjNgnSjjGPLQXGV" +
                    "+Ulb/CgNMJ3RhRJdBoLcpIm/EeLx6JLq/2Erxy7CxjaSOcD0UKa14+dzLSHVsXft" +
                    "HZuOy548X8m18KruSZsf5uAT3c7NqlXtr9YgOVUqSJykNAHTGi/BHB1dC2clKvxN" +
                    "ElfLWWrG9yaAd5TFW0+3wsaDIwRZL584AsFwwAD3KMo1oU/2zRvtm0E+VghsuD/Z" +
                    "IE1xaVGTPaL7ph/YgC9+0rGHieauT8SXz6Ryp3h0RtYMLFZOMTKM7xjmcbMZDwrO" +
                    "c+J/XjK9dbiCqlx5/B8P0xWaYYHzvE5/fafiPYzoGyFVUXquu0dFCCQrvjF/y0tC" +
                    "TPm4hQim3k1F+5NChcbeNggN+kq+VdlSqPhQEuOY+kNv"
    };

    //private static final Set<TrustAnchor> sTrustAnchors = buildCertSet();
}
