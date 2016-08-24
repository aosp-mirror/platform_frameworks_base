package com.android.configparse;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.util.Base64;
import android.util.Log;

import com.android.anqp.eap.AuthParam;
import com.android.anqp.eap.EAP;
import com.android.anqp.eap.EAPMethod;
import com.android.anqp.eap.NonEAPInnerAuth;
import com.android.hotspot2.IMSIParameter;
import com.android.hotspot2.pps.Credential;
import com.android.hotspot2.pps.HomeSP;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class ConfigBuilder {
    private static final String TAG = "WCFG";

    private static void dropFile(Uri uri, Context context) {
        context.getContentResolver().delete(uri, null, null);
    }

    public static WifiConfiguration buildConfig(HomeSP homeSP, X509Certificate caCert,
                                                 List<X509Certificate> clientChain, PrivateKey key)
            throws IOException, GeneralSecurityException {

        Credential credential = homeSP.getCredential();

        WifiConfiguration config;

        EAP.EAPMethodID eapMethodID = credential.getEAPMethod().getEAPMethodID();
        switch (eapMethodID) {
            case EAP_TTLS:
                if (key != null || clientChain != null) {
                    Log.w(TAG, "Client cert and/or key included with EAP-TTLS profile");
                }
                config = buildTTLSConfig(homeSP);
                break;
            case EAP_TLS:
                config = buildTLSConfig(homeSP, clientChain, key);
                break;
            case EAP_AKA:
            case EAP_AKAPrim:
            case EAP_SIM:
                if (key != null || clientChain != null || caCert != null) {
                    Log.i(TAG, "Client/CA cert and/or key included with " +
                            eapMethodID + " profile");
                }
                config = buildSIMConfig(homeSP);
                break;
            default:
                throw new IOException("Unsupported EAP Method: " + eapMethodID);
        }

        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;

        enterpriseConfig.setCaCertificate(caCert);
        enterpriseConfig.setAnonymousIdentity("anonymous@" + credential.getRealm());

        return config;
    }

    // Retain for debugging purposes
    /*
    private static void xIterateCerts(KeyStore ks, X509Certificate caCert)
            throws GeneralSecurityException {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate cert = ks.getCertificate(alias);
            Log.d("HS2J", "Checking " + alias);
            if (cert instanceof X509Certificate) {
                X509Certificate x509Certificate = (X509Certificate) cert;
                boolean sm = x509Certificate.getSubjectX500Principal().equals(
                        caCert.getSubjectX500Principal());
                boolean eq = false;
                if (sm) {
                    eq = Arrays.equals(x509Certificate.getEncoded(), caCert.getEncoded());
                }
                Log.d("HS2J", "Subject: " + x509Certificate.getSubjectX500Principal() +
                        ": " + sm + "/" + eq);
            }
        }
    }
    */

    private static WifiConfiguration buildTTLSConfig(HomeSP homeSP)
            throws IOException {
        Credential credential = homeSP.getCredential();

        if (credential.getUserName() == null || credential.getPassword() == null) {
            throw new IOException("EAP-TTLS provisioned without user name or password");
        }

        EAPMethod eapMethod = credential.getEAPMethod();

        AuthParam authParam = eapMethod.getAuthParam();
        if (authParam == null ||
                authParam.getAuthInfoID() != EAP.AuthInfoID.NonEAPInnerAuthType) {
            throw new IOException("Bad auth parameter for EAP-TTLS: " + authParam);
        }

        WifiConfiguration config = buildBaseConfiguration(homeSP);
        NonEAPInnerAuth ttlsParam = (NonEAPInnerAuth) authParam;
        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;
        enterpriseConfig.setPhase2Method(remapInnerMethod(ttlsParam.getType()));
        enterpriseConfig.setIdentity(credential.getUserName());
        enterpriseConfig.setPassword(credential.getPassword());

        return config;
    }

    private static WifiConfiguration buildTLSConfig(HomeSP homeSP,
                                                    List<X509Certificate> clientChain,
                                                    PrivateKey clientKey)
            throws IOException, GeneralSecurityException {

        Credential credential = homeSP.getCredential();

        X509Certificate clientCertificate = null;

        if (clientKey == null || clientChain == null) {
            throw new IOException("No key and/or cert passed for EAP-TLS");
        }
        if (credential.getCertType() != Credential.CertType.x509v3) {
            throw new IOException("Invalid certificate type for TLS: " +
                    credential.getCertType());
        }

        byte[] reference = credential.getFingerPrint();
        MessageDigest digester = MessageDigest.getInstance("SHA-256");
        for (X509Certificate certificate : clientChain) {
            digester.reset();
            byte[] fingerprint = digester.digest(certificate.getEncoded());
            if (Arrays.equals(reference, fingerprint)) {
                clientCertificate = certificate;
                break;
            }
        }
        if (clientCertificate == null) {
            throw new IOException("No certificate in chain matches supplied fingerprint");
        }

        String alias = Base64.encodeToString(reference, Base64.DEFAULT);

        WifiConfiguration config = buildBaseConfiguration(homeSP);
        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;
        enterpriseConfig.setClientCertificateAlias(alias);
        enterpriseConfig.setClientKeyEntry(clientKey, clientCertificate);

        return config;
    }

    private static WifiConfiguration buildSIMConfig(HomeSP homeSP)
            throws IOException {

        Credential credential = homeSP.getCredential();
        IMSIParameter credImsi = credential.getImsi();

        /*
         * Uncomment to enforce strict IMSI matching with currently installed SIM cards.
         *
        TelephonyManager tm = TelephonyManager.from(context);
        SubscriptionManager sub = SubscriptionManager.from(context);
        boolean match = false;

        for (int subId : sub.getActiveSubscriptionIdList()) {
            String imsi = tm.getSubscriberId(subId);
            if (credImsi.matches(imsi)) {
                match = true;
                break;
            }
        }
        if (!match) {
            throw new IOException("Supplied IMSI does not match any SIM card");
        }
        */

        WifiConfiguration config = buildBaseConfiguration(homeSP);
        config.enterpriseConfig.setPlmn(credImsi.toString());
        return config;
    }

    private static WifiConfiguration buildBaseConfiguration(HomeSP homeSP) throws IOException {
        EAP.EAPMethodID eapMethodID = homeSP.getCredential().getEAPMethod().getEAPMethodID();

        WifiConfiguration config = new WifiConfiguration();

        config.FQDN = homeSP.getFQDN();

        HashSet<Long> roamingConsortiumIds = homeSP.getRoamingConsortiums();
        config.roamingConsortiumIds = new long[roamingConsortiumIds.size()];
        int i = 0;
        for (long id : roamingConsortiumIds) {
            config.roamingConsortiumIds[i] = id;
            i++;
        }
        config.providerFriendlyName = homeSP.getFriendlyName();

        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);

        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setEapMethod(remapEAPMethod(eapMethodID));
        enterpriseConfig.setRealm(homeSP.getCredential().getRealm());
        if (homeSP.getUpdateIdentifier() >= 0) {
            config.updateIdentifier = Integer.toString(homeSP.getUpdateIdentifier());
        }
        config.enterpriseConfig = enterpriseConfig;
        if (homeSP.getUpdateIdentifier() >= 0) {
            config.updateIdentifier = Integer.toString(homeSP.getUpdateIdentifier());
        }

        return config;
    }

    private static int remapEAPMethod(EAP.EAPMethodID eapMethodID) throws IOException {
        switch (eapMethodID) {
            case EAP_TTLS:
                return WifiEnterpriseConfig.Eap.TTLS;
            case EAP_TLS:
                return WifiEnterpriseConfig.Eap.TLS;
            case EAP_SIM:
                return WifiEnterpriseConfig.Eap.SIM;
            case EAP_AKA:
                return WifiEnterpriseConfig.Eap.AKA;
            case EAP_AKAPrim:
                return WifiEnterpriseConfig.Eap.AKA_PRIME;
            default:
                throw new IOException("Bad EAP method: " + eapMethodID);
        }
    }

    private static int remapInnerMethod(NonEAPInnerAuth.NonEAPType type) throws IOException {
        switch (type) {
            case PAP:
                return WifiEnterpriseConfig.Phase2.PAP;
            case MSCHAP:
                return WifiEnterpriseConfig.Phase2.MSCHAP;
            case MSCHAPv2:
                return WifiEnterpriseConfig.Phase2.MSCHAPV2;
            case CHAP:
            default:
                throw new IOException("Inner method " + type + " not supported");
        }
    }
}
