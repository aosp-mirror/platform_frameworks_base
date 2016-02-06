package com.android.hotspot2;

import android.content.Context;
import android.content.Intent;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ICaptivePortal;
import android.net.Network;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.android.configparse.ConfigBuilder;
import com.android.hotspot2.omadm.MOManager;
import com.android.hotspot2.omadm.MOTree;
import com.android.hotspot2.omadm.OMAConstants;
import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.OMAParser;
import com.android.hotspot2.osu.OSUCertType;
import com.android.hotspot2.osu.OSUInfo;
import com.android.hotspot2.osu.OSUManager;
import com.android.hotspot2.osu.commands.MOData;
import com.android.hotspot2.pps.HomeSP;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WifiNetworkAdapter {
    private final Context mContext;
    private final OSUManager mOSUManager;
    private final Map<String, PasspointConfig> mPasspointConfigs = new HashMap<>();

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

    public WifiNetworkAdapter(Context context, OSUManager osuManager) {
        mOSUManager = osuManager;
        mContext = context;
    }

    public void initialize() {
        loadAllSps();
    }

    public void networkConfigChange(WifiConfiguration configuration) {
        loadAllSps();
    }

    private void loadAllSps() {
        Log.d(OSUManager.TAG, "Loading all SPs");
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        for (WifiConfiguration config : wifiManager.getPrivilegedConfiguredNetworks()) {
            String moTree = config.getMoTree();
            if (moTree != null) {
                try {
                    mPasspointConfigs.put(config.FQDN, new PasspointConfig(config));
                } catch (IOException | SAXException e) {
                    Log.w(OSUManager.TAG, "Failed to parse MO: " + e);
                }
            }
        }
    }

    public Collection<HomeSP> getLoadedSPs() {
        List<HomeSP> homeSPs = new ArrayList<>();
        for (PasspointConfig config : mPasspointConfigs.values()) {
            homeSPs.add(config.getHomeSP());
        }
        return homeSPs;
    }

    public MOTree getMOTree(HomeSP homeSP) {
        PasspointConfig config = mPasspointConfigs.get(homeSP.getFQDN());
        return config != null ? config.getmMOTree() : null;
    }

    public void launchBrowser(URL target, Network network, URL endRedirect) {
        Log.d(OSUManager.TAG, "Browser to " + target + ", land at " + endRedirect);

        final Intent intent = new Intent(
                ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK, network);
        intent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL,
                new CaptivePortal(new ICaptivePortal.Stub() {
                    @Override
                    public void appResponse(int response) {
                    }
                }));
        //intent.setData(Uri.parse(target.toString()));     !!! Doesn't work!
        intent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL, target.toString());
        intent.setFlags(
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    public HomeSP addSP(MOTree instanceTree) throws IOException, SAXException {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        String xml = instanceTree.toXml();
        wifiManager.addPasspointManagementObject(xml);
        return MOManager.buildSP(xml);
    }

    public void removeSP(String fqdn) throws IOException {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    }

    public HomeSP modifySP(HomeSP homeSP, Collection<MOData> mods)
            throws IOException {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        return null;
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
        for (WifiConfiguration config : wifiManager.getConfiguredNetworks()) {
            if (config.networkId == wifiInfo.getNetworkId()) {
                return config;
            }
        }
        return null;
    }

    public WifiInfo getConnectionInfo() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        return wifiManager.getConnectionInfo();
    }

    public PasspointMatch matchProviderWithCurrentNetwork(String fqdn) {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        int ordinal = wifiManager.matchProviderWithCurrentNetwork(fqdn);
        return ordinal >= 0 && ordinal < PasspointMatch.values().length ?
                PasspointMatch.values()[ordinal] : null;
    }

    public WifiConfiguration getWifiConfig(HomeSP homeSP) {
        PasspointConfig passpointConfig = mPasspointConfigs.get(homeSP.getFQDN());
        return passpointConfig != null ? passpointConfig.getWifiConfiguration() : null;
    }

    public WifiConfiguration getActivePasspointNetwork() {
        PasspointConfig passpointConfig = getActivePasspointConfig();
        return passpointConfig != null ? passpointConfig.getWifiConfiguration() : null;
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

    public HomeSP getCurrentSP() {
        PasspointConfig passpointConfig = getActivePasspointConfig();
        return passpointConfig != null ? passpointConfig.getHomeSP() : null;
    }

    public void doIconQuery(long bssid, String fileName) {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        Log.d("ZXZ", String.format("Icon query for %012x '%s'", bssid, fileName));
        wifiManager.queryPasspointIcon(bssid, fileName);
    }

    public Integer addNetwork(HomeSP homeSP, Map<OSUCertType, List<X509Certificate>> certs,
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

    public void updateNetwork(HomeSP homeSP, X509Certificate caCert,
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

    /**
     * Connect to an OSU provisioning network. The connection should not bring down other existing
     * connection and the network should not be made the default network since the connection
     * is solely for sign up and is neither intended for nor likely provides access to any
     * generic resources.
     *
     * @param osuInfo The OSU info object that defines the parameters for the network. An OSU
     *                network is either an open network, or, if the OSU NAI is set, an "OSEN"
     *                network, which is an anonymous EAP-TLS network with special keys.
     * @param info    An opaque string that is passed on to any user notification. The string is used
     *                for the name of the service provider.
     * @return an Integer holding the network-id of the just added network configuration, or null
     * if the network existed prior to this call (was not added by the OSU infrastructure).
     * The value will be used at the end of the OSU flow to delete the network as applicable.
     * @throws IOException Issues:
     *                     1. The network id is not returned. addNetwork cannot be called from here since the method
     *                     runs in the context of the app and doesn't have the appropriate permission.
     *                     2. The connection is not immediately usable if the network was not previously selected
     *                     manually.
     */
    public Integer connect(OSUInfo osuInfo, final String info) throws IOException {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = '"' + osuInfo.getSSID() + '"';
        if (osuInfo.getOSUBssid() != 0) {
            config.BSSID = Utils.macToString(osuInfo.getOSUBssid());
            Log.d(OSUManager.TAG, String.format("Setting BSSID of '%s' to %012x",
                    osuInfo.getSSID(), osuInfo.getOSUBssid()));
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
            // !!! OSEN CA Cert???
        }

        int networkId = wifiManager.addNetwork(config);
        if (wifiManager.enableNetwork(networkId, true)) {
            return networkId;
        } else {
            return null;
        }

        /* sequence of addNetwork(), enableNetwork(), saveConfiguration() and reconnect()
        wifiManager.connect(config, new WifiManager.ActionListener() {
            @Override
            public void onSuccess() {
                // Connection event comes from network change intent registered in initialize
            }

            @Override
            public void onFailure(int reason) {
                mOSUManager.notifyUser(OSUOperationStatus.ProvisioningFailure,
                        "Cannot connect to OSU network: " + reason, info);
            }
        });
        return null;

        /*
        try {
            int nwkID = wifiManager.addOrUpdateOSUNetwork(config);
            if (nwkID == WifiConfiguration.INVALID_NETWORK_ID) {
                throw new IOException("Failed to add OSU network");
            }
            wifiManager.enableNetwork(nwkID, false);
            wifiManager.reconnect();
            return nwkID;
        }
        catch (SecurityException se) {
            Log.d("ZXZ", "Blah: " + se, se);
            wifiManager.connect(config, new WifiManager.ActionListener() {
                @Override
                public void onSuccess() {
                    // Connection event comes from network change intent registered in initialize
                }

                @Override
                public void onFailure(int reason) {
                    mOSUManager.notifyUser(OSUOperationStatus.ProvisioningFailure,
                            "Cannot connect to OSU network: " + reason, info);
                }
            });
            return null;
        }
        */
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

    /**
     * Set the re-authentication hold off time for the current network
     *
     * @param holdoff hold off time in milliseconds
     * @param ess     set if the hold off pertains to an ESS rather than a BSS
     */
    public void setHoldoffTime(long holdoff, boolean ess) {

    }
}
