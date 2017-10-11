package com.android.hotspot2.flow;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.wifi.PasspointManagementObjectDefinition;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.android.configparse.ConfigBuilder;
import com.android.hotspot2.AppBridge;
import com.android.hotspot2.Utils;
import com.android.hotspot2.app.OSUService;
import com.android.hotspot2.omadm.MOManager;
import com.android.hotspot2.omadm.MOTree;
import com.android.hotspot2.omadm.OMAConstants;
import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.OMAParser;
import com.android.hotspot2.osu.ClientKeyManager;
import com.android.hotspot2.osu.OSUCertType;
import com.android.hotspot2.osu.OSUManager;
import com.android.hotspot2.osu.OSUOperationStatus;
import com.android.hotspot2.osu.OSUSocketFactory;
import com.android.hotspot2.osu.WiFiKeyManager;
import com.android.hotspot2.osu.commands.MOData;
import com.android.hotspot2.pps.HomeSP;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.KeyManager;

public class PlatformAdapter {
    private static final String TAG = "OSUFLOW";

    public static final Locale LOCALE = Locale.getDefault();

    public static final String CERT_WFA_ALIAS = "wfa-root-";
    public static final String CERT_REM_ALIAS = "rem-";
    public static final String CERT_POLICY_ALIAS = "pol-";
    public static final String CERT_SHARED_ALIAS = "shr-";
    public static final String CERT_CLT_CERT_ALIAS = "clt-";
    public static final String CERT_CLT_KEY_ALIAS = "prv-";
    public static final String CERT_CLT_CA_ALIAS = "aaa-";

    private static final String KEYSTORE_FILE = "passpoint.ks";

    private final Context mContext;
    private final File mKeyStoreFile;
    private final KeyStore mKeyStore;
    private final AppBridge mAppBridge;
    private final Map<String, PasspointConfig> mPasspointConfigs;

    public PlatformAdapter(Context context) {
        mContext = context;
        mAppBridge = new AppBridge(context);

        File appFolder = context.getFilesDir();
        mKeyStoreFile = new File(appFolder, KEYSTORE_FILE);
        Log.d(TAG, "KS file: " + mKeyStoreFile.getPath());
        KeyStore ks = null;
        try {
            //ks = loadKeyStore(KEYSTORE_FILE, readCertsFromDisk(WFA_CA_LOC));
            ks = loadKeyStore(mKeyStoreFile, OSUSocketFactory.buildCertSet());
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize Passpoint keystore, OSU disabled", e);
        }
        mKeyStore = ks;

        mPasspointConfigs = loadAllSps(context);
    }

    private static KeyStore loadKeyStore(File ksFile, Set<X509Certificate> diskCerts)
            throws IOException {
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            if (ksFile.exists()) {
                try (FileInputStream in = new FileInputStream(ksFile)) {
                    keyStore.load(in, null);
                }

                // Note: comparing two sets of certs does not work.
                boolean mismatch = false;
                int loadCount = 0;
                for (int n = 0; n < 1000; n++) {
                    String alias = String.format("%s%d", CERT_WFA_ALIAS, n);
                    Certificate cert = keyStore.getCertificate(alias);
                    if (cert == null) {
                        break;
                    }

                    loadCount++;
                    boolean matched = false;
                    Iterator<X509Certificate> iter = diskCerts.iterator();
                    while (iter.hasNext()) {
                        X509Certificate diskCert = iter.next();
                        if (cert.equals(diskCert)) {
                            iter.remove();
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        mismatch = true;
                        break;
                    }
                }
                if (mismatch || !diskCerts.isEmpty()) {
                    Log.d(TAG, "Re-seeding Passpoint key store with " +
                            diskCerts.size() + " WFA certs");
                    for (int n = 0; n < 1000; n++) {
                        String alias = String.format("%s%d", CERT_WFA_ALIAS, n);
                        Certificate cert = keyStore.getCertificate(alias);
                        if (cert == null) {
                            break;
                        } else {
                            keyStore.deleteEntry(alias);
                        }
                    }
                    int index = 0;
                    for (X509Certificate caCert : diskCerts) {
                        keyStore.setCertificateEntry(
                                String.format("%s%d", CERT_WFA_ALIAS, index), caCert);
                        index++;
                    }

                    try (FileOutputStream out = new FileOutputStream(ksFile)) {
                        keyStore.store(out, null);
                    }
                } else {
                    Log.d(TAG, "Loaded Passpoint key store with " + loadCount + " CA certs");
                    Enumeration<String> aliases = keyStore.aliases();
                    while (aliases.hasMoreElements()) {
                        Log.d("ZXC", "KS Alias '" + aliases.nextElement() + "'");
                    }
                }
            } else {
                keyStore.load(null, null);
                int index = 0;
                for (X509Certificate caCert : diskCerts) {
                    keyStore.setCertificateEntry(
                            String.format("%s%d", CERT_WFA_ALIAS, index), caCert);
                    index++;
                }

                try (FileOutputStream out = new FileOutputStream(ksFile)) {
                    keyStore.store(out, null);
                }
                Log.d(TAG, "Initialized Passpoint key store with " +
                        diskCerts.size() + " CA certs");
            }
            return keyStore;
        } catch (GeneralSecurityException gse) {
            throw new IOException(gse);
        }
    }

    private static Map<String, PasspointConfig> loadAllSps(Context context) {
        Map<String, PasspointConfig> passpointConfigs = new HashMap<>();

        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        List<WifiConfiguration> configs = wifiManager.getPrivilegedConfiguredNetworks();
        if (configs == null) {
            return passpointConfigs;
        }
        int count = 0;
        for (WifiConfiguration config : configs) {
            String moTree = config.getMoTree();
            if (moTree != null) {
                try {
                    passpointConfigs.put(config.FQDN, new PasspointConfig(config));
                    count++;
                } catch (IOException | SAXException e) {
                    Log.w(OSUManager.TAG, "Failed to parse MO: " + e);
                }
            }
        }
        Log.d(OSUManager.TAG, "Loaded " + count + " SPs");
        return passpointConfigs;
    }

    public KeyStore getKeyStore() {
        return mKeyStore;
    }

    public Context getContext() {
        return mContext;
    }

    /**
     * Connect to an OSU provisioning network. The connection should not bring down other existing
     * connection and the network should not be made the default network since the connection
     * is solely for sign up and is neither intended for nor likely provides access to any
     * generic resources.
     *
     * @param osuInfo The OSU info object that defines the parameters for the network. An OSU
     *                network is either an open network, or, if the OSU NAI is set, an "OSEN"
     *                network, which is an anonymous EAP-TLS network with special keys.
     * @return an Integer holding the network-id of the just added network configuration, or null
     * if the network existed prior to this call (was not added by the OSU infrastructure).
     * The value will be used at the end of the OSU flow to delete the network as applicable.
     * @throws IOException Issues:
     *                     1. The network id is not returned. addNetwork cannot be called from here since the method
     *                     runs in the context of the app and doesn't have the appropriate permission.
     *                     2. The connection is not immediately usable if the network was not previously selected
     *                     manually.
     */
    public Integer connect(OSUInfo osuInfo) throws IOException {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = '"' + osuInfo.getOsuSsid() + '"';
        if (osuInfo.getOSUBssid() != 0) {
            config.BSSID = Utils.macToString(osuInfo.getOSUBssid());
            Log.d(OSUManager.TAG, String.format("Setting BSSID of '%s' to %012x",
                    osuInfo.getOsuSsid(), osuInfo.getOSUBssid()));
        }

        if (osuInfo.getOSUProvider().getOsuNai() == null) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OSEN);
            config.allowedProtocols.set(WifiConfiguration.Protocol.OSEN);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GTK_NOT_USED);
            config.enterpriseConfig = new WifiEnterpriseConfig();
            config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.UNAUTH_TLS);
            config.enterpriseConfig.setIdentity(osuInfo.getOSUProvider().getOsuNai());
            Set<X509Certificate> cas = OSUSocketFactory.buildCertSet();
            config.enterpriseConfig.setCaCertificates(cas.toArray(new X509Certificate[cas.size()]));
        }

        int networkId = wifiManager.addNetwork(config);
        if (networkId < 0) {
            throw new IOException("Failed to add OSU network");
        }
        if (wifiManager.enableNetwork(networkId, true)) {
            return networkId;
        } else {
            throw new IOException("Failed to enable OSU network");
        }
    }

    /**
     * @param homeSP The Home SP associated with the keying material in question. Passing
     *               null returns a "system wide" KeyManager to support pre-provisioned certs based
     *               on names retrieved from the ClientCertInfo request.
     * @return A key manager suitable for the given configuration (or pre-provisioned keys).
     */
    public KeyManager getKeyManager(HomeSP homeSP) throws IOException {
        return homeSP != null
                ? new ClientKeyManager(homeSP, mKeyStore) : new WiFiKeyManager(mKeyStore);
    }

    public void provisioningComplete(OSUInfo osuInfo,
                                     MOData moData, Map<OSUCertType, List<X509Certificate>> certs,
                                     PrivateKey privateKey, Network osuNetwork) {
        try {
            String xml = moData.getMOTree().toXml();
            HomeSP homeSP = MOManager.buildSP(xml);

            Integer spNwk = addNetwork(homeSP, certs, privateKey, osuNetwork);
            if (spNwk == null) {
                notifyUser(OSUOperationStatus.ProvisioningFailure,
                        "Failed to save network configuration", osuInfo.getName(LOCALE));
            } else {
                if (addSP(xml) < 0) {
                    deleteNetwork(spNwk);
                    Log.e(TAG, "Failed to provision: " + homeSP.getFQDN());
                    notifyUser(OSUOperationStatus.ProvisioningFailure, "Failed to add MO",
                            osuInfo.getName(LOCALE));
                    return;
                }
                Set<X509Certificate> rootCerts = OSUSocketFactory.getRootCerts(mKeyStore);
                X509Certificate remCert = getCert(certs, OSUCertType.Remediation);
                X509Certificate polCert = getCert(certs, OSUCertType.Policy);
                int newCerts = 0;
                if (privateKey != null) {
                    X509Certificate cltCert = getCert(certs, OSUCertType.Client);
                    mKeyStore.setKeyEntry(CERT_CLT_KEY_ALIAS + homeSP.getFQDN(),
                            privateKey, null, new X509Certificate[]{cltCert});
                    mKeyStore.setCertificateEntry(CERT_CLT_CERT_ALIAS + homeSP.getFQDN(), cltCert);
                    newCerts++;
                }
                boolean usingShared = false;
                if (remCert != null) {
                    if (!rootCerts.contains(remCert)) {
                        if (remCert.equals(polCert)) {
                            mKeyStore.setCertificateEntry(CERT_SHARED_ALIAS + homeSP.getFQDN(),
                                    remCert);
                            usingShared = true;
                            newCerts++;
                        } else {
                            mKeyStore.setCertificateEntry(CERT_REM_ALIAS + homeSP.getFQDN(),
                                    remCert);
                            newCerts++;
                        }
                    }
                }
                if (!usingShared && polCert != null) {
                    if (!rootCerts.contains(polCert)) {
                        mKeyStore.setCertificateEntry(CERT_POLICY_ALIAS + homeSP.getFQDN(),
                                remCert);
                        newCerts++;
                    }
                }


                if (newCerts > 0) {
                    try (FileOutputStream out = new FileOutputStream(mKeyStoreFile)) {
                        mKeyStore.store(out, null);
                    }
                }
                notifyUser(OSUOperationStatus.ProvisioningSuccess, null, osuInfo.getName(LOCALE));
                Log.d(TAG, "Provisioning complete.");
            }
        } catch (IOException | GeneralSecurityException | SAXException e) {
            Log.e(TAG, "Failed to provision: " + e, e);
            notifyUser(OSUOperationStatus.ProvisioningFailure, e.toString(),
                    osuInfo.getName(LOCALE));
        }
    }

    public void remediationComplete(HomeSP homeSP, Collection<MOData> mods,
                                    Map<OSUCertType, List<X509Certificate>> certs,
                                    PrivateKey privateKey, boolean policy)
            throws IOException, GeneralSecurityException {

        HomeSP altSP = null;
        if (modifySP(homeSP, mods) > 0) {
            altSP = MOManager.modifySP(homeSP, getMOTree(homeSP), mods);
        }

        X509Certificate caCert = null;
        List<X509Certificate> clientCerts = null;
        if (certs != null) {
            List<X509Certificate> certList = certs.get(OSUCertType.AAA);
            caCert = certList != null && !certList.isEmpty() ? certList.iterator().next() : null;
            clientCerts = certs.get(OSUCertType.Client);
        }
        if (altSP != null || certs != null) {
            if (altSP == null) {
                altSP = homeSP;
            }
            updateNetwork(altSP, caCert, clientCerts, privateKey);

            if (privateKey != null) {
                X509Certificate cltCert = getCert(certs, OSUCertType.Client);
                mKeyStore.setKeyEntry(CERT_CLT_KEY_ALIAS + homeSP.getFQDN(),
                        privateKey, null, new X509Certificate[]{cltCert});
                mKeyStore.setCertificateEntry(CERT_CLT_CERT_ALIAS + homeSP.getFQDN(), cltCert);
            }
        }

        Intent intent = new Intent(OSUService.REMEDIATION_DONE_ACTION);
        intent.putExtra(OSUService.REMEDIATION_FQDN_EXTRA, homeSP.getFQDN());
        intent.putExtra(OSUService.REMEDIATION_POLICY_EXTRA, policy);
        mContext.sendBroadcast(intent);

        notifyUser(OSUOperationStatus.ProvisioningSuccess, null, homeSP.getFriendlyName());
    }

    public void serviceProviderDeleted(String fqdn) {
        int count = deleteCerts(mKeyStore, fqdn,
                CERT_REM_ALIAS, CERT_POLICY_ALIAS, CERT_SHARED_ALIAS, CERT_CLT_CERT_ALIAS);

        Log.d(TAG, "Passpoint network deleted, removing " + count + " key store entries");

        try {
            if (mKeyStore.getKey(CERT_CLT_KEY_ALIAS + fqdn, null) != null) {
                mKeyStore.deleteEntry(CERT_CLT_KEY_ALIAS + fqdn);
            }
        } catch (GeneralSecurityException e) {
                /**/
        }

        if (count > 0) {
            try (FileOutputStream out = new FileOutputStream(mKeyStoreFile)) {
                mKeyStore.store(out, null);
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Failed to remove certs from key store: " + e);
            }
        }
    }

    private static int deleteCerts(KeyStore keyStore, String fqdn, String... prefixes) {
        int count = 0;
        for (String prefix : prefixes) {
            try {
                String alias = prefix + fqdn;
                Certificate cert = keyStore.getCertificate(alias);
                if (cert != null) {
                    keyStore.deleteEntry(alias);
                    count++;
                }
            } catch (KeyStoreException kse) {
                /**/
            }
        }
        return count;
    }

    private static X509Certificate getCert(Map<OSUCertType, List<X509Certificate>> certMap,
                                           OSUCertType certType) {
        List<X509Certificate> certs = certMap.get(certType);
        if (certs == null || certs.isEmpty()) {
            return null;
        }
        return certs.iterator().next();
    }

    public String notifyUser(OSUOperationStatus status, String message, String spName) {
        if (status == OSUOperationStatus.UserInputComplete) {
            return null;
        }
        mAppBridge.showStatus(status, spName, message, null);
        return null;
    }

    public void provisioningFailed(String spName, String message) {
        notifyUser(OSUOperationStatus.ProvisioningFailure, message, spName);
    }

    private Integer addNetwork(HomeSP homeSP, Map<OSUCertType, List<X509Certificate>> certs,
                              PrivateKey privateKey, Network osuNetwork)
            throws IOException, GeneralSecurityException {

        List<X509Certificate> aaaTrust = certs.get(OSUCertType.AAA);
        if (aaaTrust.isEmpty()) {
            aaaTrust = certs.get(OSUCertType.CA);   // Get the CAs from the EST flow.
        }

        WifiConfiguration config = ConfigBuilder.buildConfig(homeSP,
                aaaTrust.iterator().next(),
                certs.get(OSUCertType.Client), privateKey);

        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        int nwkId = wifiManager.addNetwork(config);
        boolean saved = false;
        if (nwkId >= 0) {
            saved = wifiManager.saveConfiguration();
        }
        Log.d(OSUManager.TAG, "Wifi configuration " + nwkId +
                " " + (saved ? "saved" : "not saved"));

        if (saved) {
            reconnect(osuNetwork, nwkId);
            return nwkId;
        } else {
            return null;
        }
    }

    private void updateNetwork(HomeSP homeSP, X509Certificate caCert,
                              List<X509Certificate> clientCerts, PrivateKey privateKey)
            throws IOException, GeneralSecurityException {

        WifiConfiguration config = getWifiConfig(homeSP);
        if (config == null) {
            throw new IOException("Failed to find matching network config");
        }
        Log.d(OSUManager.TAG, "Found matching config " + config.networkId + ", updating");

        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;
        WifiConfiguration newConfig = ConfigBuilder.buildConfig(homeSP,
                caCert != null ? caCert : enterpriseConfig.getCaCertificate(),
                clientCerts, privateKey);
        newConfig.networkId = config.networkId;

        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wifiManager.save(newConfig, null);
        wifiManager.saveConfiguration();
    }

    private WifiConfiguration getWifiConfig(HomeSP homeSP) {
        PasspointConfig passpointConfig = mPasspointConfigs.get(homeSP.getFQDN());
        return passpointConfig != null ? passpointConfig.getWifiConfiguration() : null;
    }

    public MOTree getMOTree(HomeSP homeSP) {
        PasspointConfig config = mPasspointConfigs.get(homeSP.getFQDN());
        return config != null ? config.getmMOTree() : null;
    }

    public HomeSP getHomeSP(String fqdn) {
        PasspointConfig passpointConfig = mPasspointConfigs.get(fqdn);
        return passpointConfig != null ? passpointConfig.getHomeSP() : null;
    }

    public HomeSP getCurrentSP() {
        PasspointConfig passpointConfig = getActivePasspointConfig();
        return passpointConfig != null ? passpointConfig.getHomeSP() : null;
    }

    private PasspointConfig getActivePasspointConfig() {
        WifiInfo wifiInfo = getConnectionInfo();
        if (wifiInfo == null) {
            return null;
        }

        for (PasspointConfig passpointConfig : mPasspointConfigs.values()) {
            if (passpointConfig.getWifiConfiguration().networkId == wifiInfo.getNetworkId()) {
                return passpointConfig;
            }
        }
        return null;
    }

    private int addSP(String xml) throws IOException, SAXException {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        // TODO(b/32883320): use the new API for adding Passpoint configuration.
        return 0;
    }

    private int modifySP(HomeSP homeSP, Collection<MOData> mods) throws IOException {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        List<PasspointManagementObjectDefinition> defMods = new ArrayList<>(mods.size());
        for (MOData mod : mods) {
            defMods.add(new PasspointManagementObjectDefinition(mod.getBaseURI(),
                    mod.getURN(), mod.getMOTree().toXml()));
        }
        // TODO(b/32883320): use the new API to update Passpoint configuration.
        return 0;
    }

    private void reconnect(Network osuNetwork, int newNwkId) {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        if (osuNetwork != null) {
            wifiManager.disableNetwork(osuNetwork.netId);
        }
        if (newNwkId != WifiConfiguration.INVALID_NETWORK_ID) {
            wifiManager.enableNetwork(newNwkId, true);
        }
    }

    public void deleteNetwork(int id) {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wifiManager.disableNetwork(id);
        wifiManager.forget(id, null);
    }

    public WifiInfo getConnectionInfo() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        return wifiManager.getConnectionInfo();
    }

    public Network getCurrentNetwork() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        return wifiManager.getCurrentNetwork();
    }

    public WifiConfiguration getActiveWifiConfig() {
        WifiInfo wifiInfo = getConnectionInfo();
        if (wifiInfo == null) {
            return null;
        }
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs == null) {
            return null;
        }
        for (WifiConfiguration config : configs) {
            if (config.networkId == wifiInfo.getNetworkId()) {
                return config;
            }
        }
        return null;
    }

    private static class PasspointConfig {
        private final WifiConfiguration mWifiConfiguration;
        private final MOTree mMOTree;
        private final HomeSP mHomeSP;

        private PasspointConfig(WifiConfiguration config) throws IOException, SAXException {
            mWifiConfiguration = config;
            OMAParser omaParser = new OMAParser();
            mMOTree = omaParser.parse(config.getMoTree(), OMAConstants.PPS_URN);
            List<HomeSP> spList = MOManager.buildSPs(mMOTree);
            if (spList.size() != 1) {
                throw new OMAException("Expected exactly one HomeSP, got " + spList.size());
            }
            mHomeSP = spList.iterator().next();
        }

        public WifiConfiguration getWifiConfiguration() {
            return mWifiConfiguration;
        }

        public HomeSP getHomeSP() {
            return mHomeSP;
        }

        public MOTree getmMOTree() {
            return mMOTree;
        }
    }
}
