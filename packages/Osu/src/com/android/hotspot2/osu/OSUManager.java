package com.android.hotspot2.osu;

import android.content.Context;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.util.Log;

import com.android.anqp.Constants;
import com.android.anqp.OSUProvider;
import com.android.hotspot2.AppBridge;
import com.android.hotspot2.OMADMAdapter;
import com.android.hotspot2.PasspointMatch;
import com.android.hotspot2.Utils;
import com.android.hotspot2.WifiNetworkAdapter;
import com.android.hotspot2.omadm.MOManager;
import com.android.hotspot2.omadm.MOTree;
import com.android.hotspot2.osu.commands.MOData;
import com.android.hotspot2.osu.service.RedirectListener;
import com.android.hotspot2.osu.service.SubscriptionTimer;
import com.android.hotspot2.pps.HomeSP;
import com.android.hotspot2.pps.UpdateInfo;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;

public class OSUManager {
    public static final String TAG = "OSUMGR";
    public static final boolean R2_ENABLED = true;
    public static final boolean R2_MOCK = true;
    private static final boolean MATCH_BSSID = false;

    private static final String KEYSTORE_FILE = "passpoint.ks";
    private static final String WFA_CA_LOC = "/etc/security/wfa";

    private static final String OSU_COUNT = "osu-count";
    private static final String SP_NAME = "sp-name";
    private static final String PROV_SUCCESS = "prov-success";
    private static final String DEAUTH = "deauth";
    private static final String DEAUTH_DELAY = "deauth-delay";
    private static final String DEAUTH_URL = "deauth-url";
    private static final String PROV_MESSAGE = "prov-message";

    private static final long REMEDIATION_TIMEOUT = 120000L;
    // How many scan result batches to hang on to

    public static final int FLOW_PROVISIONING = 1;
    public static final int FLOW_REMEDIATION = 2;
    public static final int FLOW_POLICY = 3;

    public static final String CERT_WFA_ALIAS = "wfa-root-";
    public static final String CERT_REM_ALIAS = "rem-";
    public static final String CERT_POLICY_ALIAS = "pol-";
    public static final String CERT_SHARED_ALIAS = "shr-";
    public static final String CERT_CLT_CERT_ALIAS = "clt-";
    public static final String CERT_CLT_KEY_ALIAS = "prv-";
    public static final String CERT_CLT_CA_ALIAS = "aaa-";

    // Preferred icon parameters
    private static final Set<String> ICON_TYPES =
            new HashSet<>(Arrays.asList("image/png", "image/jpeg"));
    private static final int ICON_WIDTH = 64;
    private static final int ICON_HEIGHT = 64;
    public static final Locale LOCALE = java.util.Locale.getDefault();

    private final WifiNetworkAdapter mWifiNetworkAdapter;

    private final AppBridge mAppBridge;
    private final Context mContext;
    private final IconCache mIconCache;
    private final SubscriptionTimer mSubscriptionTimer;
    private final Set<String> mOSUSSIDs = new HashSet<>();
    private final Map<OSUProvider, OSUInfo> mOSUMap = new HashMap<>();
    private final KeyStore mKeyStore;
    private RedirectListener mRedirectListener;
    private final AtomicInteger mOSUSequence = new AtomicInteger();
    private OSUThread mProvisioningThread;
    private final Map<String, OSUThread> mServiceThreads = new HashMap<>();
    private volatile OSUInfo mPendingOSU;
    private volatile Integer mOSUNwkID;

    private final OSUCache mOSUCache;

    public OSUManager(Context context) {
        mContext = context;
        mAppBridge = new AppBridge(context);
        mIconCache = new IconCache(this);
        mWifiNetworkAdapter = new WifiNetworkAdapter(context, this);
        mSubscriptionTimer = new SubscriptionTimer(this, mWifiNetworkAdapter, context);
        mOSUCache = new OSUCache();
        KeyStore ks = null;
        try {
            //ks = loadKeyStore(KEYSTORE_FILE, readCertsFromDisk(WFA_CA_LOC));
            ks = loadKeyStore(new File(context.getFilesDir(), KEYSTORE_FILE),
                    OSUSocketFactory.buildCertSet());
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize Passpoint keystore, OSU disabled", e);
        }
        mKeyStore = ks;
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

    private static Set<X509Certificate> readCertsFromDisk(String dir) throws CertificateException {
        Set<X509Certificate> certs = new HashSet<>();
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        File caDir = new File(dir);
        File[] certFiles = caDir.listFiles();
        if (certFiles != null) {
            for (File certFile : certFiles) {
                try {
                    try (FileInputStream in = new FileInputStream(certFile)) {
                        Certificate cert = certFactory.generateCertificate(in);
                        if (cert instanceof X509Certificate) {
                            certs.add((X509Certificate) cert);
                        }
                    }
                } catch (CertificateException | IOException e) {
                            /* Ignore */
                }
            }
        }
        return certs;
    }

    public KeyStore getKeyStore() {
        return mKeyStore;
    }

    private static class OSUThread extends Thread {
        private final OSUClient mOSUClient;
        private final OSUManager mOSUManager;
        private final HomeSP mHomeSP;
        private final String mSpName;
        private final int mFlowType;
        private final KeyManager mKeyManager;
        private final long mLaunchTime;
        private final Object mLock = new Object();
        private boolean mLocalAddressSet;
        private Network mNetwork;

        private OSUThread(OSUInfo osuInfo, OSUManager osuManager, KeyManager km)
                throws MalformedURLException {
            mOSUClient = new OSUClient(osuInfo, osuManager.getKeyStore());
            mOSUManager = osuManager;
            mHomeSP = null;
            mSpName = osuInfo.getName(LOCALE);
            mFlowType = FLOW_PROVISIONING;
            mKeyManager = km;
            mLaunchTime = System.currentTimeMillis();

            setDaemon(true);
            setName("OSU Client Thread");
        }

        private OSUThread(String osuURL, OSUManager osuManager, KeyManager km, HomeSP homeSP,
                          int flowType) throws MalformedURLException {
            mOSUClient = new OSUClient(osuURL, osuManager.getKeyStore());
            mOSUManager = osuManager;
            mHomeSP = homeSP;
            mSpName = homeSP.getFriendlyName();
            mFlowType = flowType;
            mKeyManager = km;
            mLaunchTime = System.currentTimeMillis();

            setDaemon(true);
            setName("OSU Client Thread");
        }

        public long getLaunchTime() {
            return mLaunchTime;
        }

        private void connect(Network network) {
            synchronized (mLock) {
                mNetwork = network;
                mLocalAddressSet = true;
                mLock.notifyAll();
            }
            Log.d(TAG, "Client notified...");
        }

        @Override
        public void run() {
            Log.d(TAG, mFlowType + "-" + getName() + " running.");
            Network network;
            synchronized (mLock) {
                while (!mLocalAddressSet) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) {
                        /**/
                    }
                    Log.d(TAG, "OSU Thread running...");
                }
                network = mNetwork;
            }

            if (network == null) {
                Log.d(TAG, "Association failed, exiting OSU flow");
                mOSUManager.provisioningFailed(mSpName, "Network cannot be reached",
                        mHomeSP, mFlowType);
                return;
            }

            Log.d(TAG, "OSU SSID Associated at " + network.toString());
            try {
                if (mFlowType == FLOW_PROVISIONING) {
                    mOSUClient.provision(mOSUManager, network, mKeyManager);
                } else {
                    mOSUClient.remediate(mOSUManager, network, mKeyManager, mHomeSP, mFlowType);
                }
            } catch (Throwable t) {
                Log.w(TAG, "OSU flow failed: " + t, t);
                mOSUManager.provisioningFailed(mSpName, t.getMessage(), mHomeSP, mFlowType);
            }
        }
    }

    /*
    public void startOSU() {
        registerUserInputListener(new UserInputListener() {
            @Override
            public void requestUserInput(URL target, Network network, URL endRedirect) {
                Log.d(TAG, "Browser to " + target + ", land at " + endRedirect);

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
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }

            @Override
            public String operationStatus(String spIdentity, OSUOperationStatus status,
                                          String message) {
                Log.d(TAG, "OSU OP Status: " + status + ", message " + message);
                Intent intent = new Intent(Intent.ACTION_OSU_NOTIFICATION);
                intent.putExtra(SP_NAME, spIdentity);
                intent.putExtra(PROV_SUCCESS, status == OSUOperationStatus.ProvisioningSuccess);
                if (message != null) {
                    intent.putExtra(PROV_MESSAGE, message);
                }
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                return null;
            }

            @Override
            public void deAuthNotification(String spIdentity, boolean ess, int delay, URL url) {
                Log.i(TAG, "De-authentication imminent for " + (ess ? "ess" : "bss") +
                        ", redirect to " + url);
                Intent intent = new Intent(Intent.ACTION_OSU_NOTIFICATION);
                intent.putExtra(SP_NAME, spIdentity);
                intent.putExtra(DEAUTH, ess);
                intent.putExtra(DEAUTH_DELAY, delay);
                intent.putExtra(DEAUTH_URL, url.toString());
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }
        });
        addOSUListener(new OSUListener() {
            @Override
            public void osuNotification(int count) {
                Intent intent = new Intent(Intent.ACTION_OSU_NOTIFICATION);
                intent.putExtra(OSU_COUNT, count);
                intent.setFlags(
                        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }
        });
        mWifiNetworkAdapter.initialize();
        mSubscriptionTimer.checkUpdates();
    }
    */

    public List<OSUInfo> getAvailableOSUs() {
        synchronized (mOSUMap) {
            List<OSUInfo> completeOSUs = new ArrayList<>();
            for (OSUInfo osuInfo : mOSUMap.values()) {
                if (osuInfo.getIconStatus() == OSUInfo.IconStatus.Available) {
                    completeOSUs.add(osuInfo);
                }
            }
            return completeOSUs;
        }
    }

    public void recheckTimers() {
        mSubscriptionTimer.checkUpdates();
    }

    public void setOSUSelection(int osuID) {
        OSUInfo selection = null;
        for (OSUInfo osuInfo : mOSUMap.values()) {
            Log.d("ZXZ", "In select: " + osuInfo + ", id " + osuInfo.getOsuID());
            if (osuInfo.getOsuID() == osuID &&
                    osuInfo.getIconStatus() == OSUInfo.IconStatus.Available) {
                selection = osuInfo;
                break;
            }
        }

        Log.d(TAG, "Selected OSU ID " + osuID + ", matches " + selection);

        if (selection == null) {
            mPendingOSU = null;
            return;
        }

        mPendingOSU = selection;
        WifiConfiguration config = mWifiNetworkAdapter.getActiveWifiConfig();

        if (config != null &&
                bssidMatch(selection) &&
                Utils.unquote(config.SSID).equals(selection.getSSID())) {

            try {
                // Go straight to provisioning if the network is already selected.
                // Also note that mOSUNwkID is left unset to leave the network around after
                // flow completion since it was not added by the OSU flow.
                initiateProvisioning(mPendingOSU, mWifiNetworkAdapter.getCurrentNetwork());
            } catch (IOException ioe) {
                notifyUser(OSUOperationStatus.ProvisioningFailure, ioe.getMessage(),
                        mPendingOSU.getName(LOCALE));
            } finally {
                mPendingOSU = null;
            }
        } else {
            try {
                mOSUNwkID = mWifiNetworkAdapter.connect(selection, mPendingOSU.getName(LOCALE));
            } catch (IOException ioe) {
                notifyUser(OSUOperationStatus.ProvisioningFailure, ioe.getMessage(),
                        selection.getName(LOCALE));
            }
        }
    }

    public void networkConfigChange(WifiConfiguration configuration) {
        mWifiNetworkAdapter.networkConfigChange(configuration);
    }

    public void networkConnectEvent(WifiInfo wifiInfo) {
        if (wifiInfo != null) {
            setActiveNetwork(mWifiNetworkAdapter.getActiveWifiConfig(),
                    mWifiNetworkAdapter.getCurrentNetwork());
        }
    }

    public void wifiStateChange(boolean on) {
        if (!on) {
            int current = mOSUMap.size();
            mOSUMap.clear();
            mOSUCache.clearAll();
            mIconCache.clear();
            if (current > 0) {
                notifyOSUCount(0);
            }
        }
    }

    private boolean bssidMatch(OSUInfo osuInfo) {
        if (MATCH_BSSID) {
            WifiInfo wifiInfo = mWifiNetworkAdapter.getConnectionInfo();
            return wifiInfo != null && Utils.parseMac(wifiInfo.getBSSID()) == osuInfo.getBSSID();
        } else {
            return true;
        }
    }

    public void setActiveNetwork(WifiConfiguration wifiConfiguration, Network network) {
        Log.d(TAG, "Network change: " + network + ", cfg " +
                (wifiConfiguration != null ? wifiConfiguration.SSID : "-") + ", osu " + mPendingOSU);
        if (mPendingOSU != null &&
                wifiConfiguration != null &&
                network != null &&
                bssidMatch(mPendingOSU) &&
                Utils.unquote(wifiConfiguration.SSID).equals(mPendingOSU.getSSID())) {

            try {
                Log.d(TAG, "New network " + network + ", current OSU " + mPendingOSU);
                initiateProvisioning(mPendingOSU, network);
            } catch (IOException ioe) {
                notifyUser(OSUOperationStatus.ProvisioningFailure, ioe.getMessage(),
                        mPendingOSU.getName(LOCALE));
            } finally {
                mPendingOSU = null;
            }
            return;
        }

        /*
        // !!! Hack to force start remediation at connection time
        else if (wifiConfiguration != null && wifiConfiguration.isPasspoint()) {
            HomeSP homeSP = mWifiConfigStore.getHomeSPForConfig(wifiConfiguration);
            if (homeSP != null && homeSP.getSubscriptionUpdate() != null) {
                if (!mServiceThreads.containsKey(homeSP.getFQDN())) {
                    try {
                        remediate(homeSP);
                    } catch (IOException ioe) {
                        Log.w(TAG, "Failed to remediate: " + ioe);
                    }
                }
            }
        }
        */
        else if (wifiConfiguration == null) {
            mServiceThreads.clear();
        }
    }


    /**
     * Called when an OSU has been selected and the associated network is fully connected.
     *
     * @param osuInfo The selected OSUInfo or null if the current OSU flow is cancelled externally,
     *                e.g. WiFi is turned off or the OSU network is otherwise detected as
     *                unreachable.
     * @param network The currently associated network (for the OSU SSID).
     * @throws IOException
     * @throws GeneralSecurityException
     */
    private void initiateProvisioning(OSUInfo osuInfo, Network network)
            throws IOException {
        synchronized (mWifiNetworkAdapter) {
            if (mProvisioningThread != null) {
                mProvisioningThread.connect(null);
                mProvisioningThread = null;
            }
            if (mRedirectListener != null) {
                mRedirectListener.abort();
                mRedirectListener = null;
            }
            if (osuInfo != null) {
                //new ConnMonitor().start();
                mProvisioningThread = new OSUThread(osuInfo, this, getKeyManager(null, mKeyStore));
                mProvisioningThread.start();
                //mWifiNetworkAdapter.associate(osuInfo.getSSID(),
                //        osuInfo.getBSSID(), osuInfo.getOSUProvider().getOsuNai());
                mProvisioningThread.connect(network);
            }
        }
    }

    /**
     * @param homeSP The Home SP associated with the keying material in question. Passing
     *               null returns a "system wide" KeyManager to support pre-provisioned certs based
     *               on names retrieved from the ClientCertInfo request.
     * @return A key manager suitable for the given configuration (or pre-provisioned keys).
     */
    private static KeyManager getKeyManager(HomeSP homeSP, KeyStore keyStore)
            throws IOException {
        return homeSP != null ? new ClientKeyManager(homeSP, keyStore) :
                new WiFiKeyManager(keyStore);
    }

    public boolean isOSU(String ssid) {
        synchronized (mOSUMap) {
            return mOSUSSIDs.contains(ssid);
        }
    }

    public void tickleIconCache(boolean all) {
        mIconCache.tickle(all);

        if (all) {
            synchronized (mOSUMap) {
                int current = mOSUMap.size();
                mOSUMap.clear();
                mOSUCache.clearAll();
                mIconCache.clear();
                if (current > 0) {
                    notifyOSUCount(0);
                }
            }
        }
    }

    public void pushScanResults(Collection<ScanResult> scanResults) {
        Map<OSUProvider, ScanResult> results = mOSUCache.pushScanResults(scanResults);
        if (results != null) {
            updateOSUInfoCache(results);
        }
    }

    private void updateOSUInfoCache(Map<OSUProvider, ScanResult> results) {
        Map<OSUProvider, OSUInfo> osus = new HashMap<>();
        for (Map.Entry<OSUProvider, ScanResult> entry : results.entrySet()) {
            OSUInfo existing = mOSUMap.get(entry.getKey());
            long bssid = Utils.parseMac(entry.getValue().BSSID);

            if (existing == null || existing.getBSSID() != bssid) {
                osus.put(entry.getKey(), new OSUInfo(entry.getValue(), entry.getKey().getSSID(),
                        entry.getKey(), mOSUSequence.getAndIncrement()));
            } else {
                // Maintain existing entries.
                osus.put(entry.getKey(), existing);
            }
        }

        mOSUMap.clear();
        mOSUMap.putAll(osus);

        mOSUSSIDs.clear();
        for (OSUInfo osuInfo : mOSUMap.values()) {
            mOSUSSIDs.add(osuInfo.getSSID());
        }

        if (mOSUMap.isEmpty()) {
            notifyOSUCount(0);
        }
        initiateIconQueries();
        Log.d(TAG, "Latest (app) OSU info: " + mOSUMap);
    }

    public void iconResults(List<OSUInfo> osuInfos) {
        int newIcons = 0;
        for (OSUInfo osuInfo : osuInfos) {
            if (osuInfo.getIconStatus() == OSUInfo.IconStatus.Available) {
                newIcons++;
            }
        }
        if (newIcons > 0) {
            int count = 0;
            for (OSUInfo existing : mOSUMap.values()) {
                if (existing.getIconStatus() == OSUInfo.IconStatus.Available) {
                    count++;
                }
            }
            Log.d(TAG, "Icon results for " + count + " OSUs");
            notifyOSUCount(count);
        }
    }

    private void notifyOSUCount(int count) {
        mAppBridge.showOsuCount(count, getAvailableOSUs());
    }

    private void initiateIconQueries() {
        for (OSUInfo osuInfo : mOSUMap.values()) {
            if (osuInfo.getIconStatus() == OSUInfo.IconStatus.NotQueried) {
                mIconCache.startIconQuery(osuInfo,
                        osuInfo.getIconInfo(LOCALE, ICON_TYPES, ICON_WIDTH, ICON_HEIGHT));
            }
        }
    }

    public void deauth(long bssid, boolean ess, int delay, String url) throws MalformedURLException {
        Log.d(TAG, String.format("De-auth imminent on %s, delay %ss to '%s'",
                ess ? "ess" : "bss",
                delay,
                url));
        mWifiNetworkAdapter.setHoldoffTime(delay * Constants.MILLIS_IN_A_SEC, ess);
        HomeSP homeSP = mWifiNetworkAdapter.getCurrentSP();
        String spName = homeSP != null ? homeSP.getFriendlyName() : "unknown";
        mAppBridge.showDeauth(spName, ess, delay, url);
    }

    // !!! Consistently check passpoint match.
    // !!! Convert to a one-thread thread-pool
    public void wnmRemediate(long bssid, String url, PasspointMatch match)
            throws IOException, SAXException {
        WifiConfiguration config = mWifiNetworkAdapter.getActiveWifiConfig();
        HomeSP homeSP = MOManager.buildSP(config.getMoTree());
        if (homeSP == null) {
            throw new IOException("Remediation request for unidentified Passpoint network " +
                    config.networkId);
        }
        Network network = mWifiNetworkAdapter.getCurrentNetwork();
        if (network == null) {
            throw new IOException("Failed to determine current network");
        }
        WifiInfo wifiInfo = mWifiNetworkAdapter.getConnectionInfo();
        if (wifiInfo == null || Utils.parseMac(wifiInfo.getBSSID()) != bssid) {
            throw new IOException("Mismatching BSSID");
        }
        Log.d(TAG, "WNM Remediation on " + network.netId + " FQDN " + homeSP.getFQDN());

        doRemediate(url, network, homeSP, false);
    }

    public void remediate(HomeSP homeSP, boolean policy) throws IOException, SAXException {
        UpdateInfo updateInfo;
        if (policy) {
            if (homeSP.getPolicy() == null) {
                throw new IOException("No policy object");
            }
            updateInfo = homeSP.getPolicy().getPolicyUpdate();
        } else {
            updateInfo = homeSP.getSubscriptionUpdate();
        }
        switch (updateInfo.getUpdateRestriction()) {
            case HomeSP: {
                Network network = mWifiNetworkAdapter.getCurrentNetwork();
                if (network == null) {
                    throw new IOException("Failed to determine current network");
                }

                WifiConfiguration config = mWifiNetworkAdapter.getActivePasspointNetwork();
                HomeSP activeSP = MOManager.buildSP(config.getMoTree());

                if (activeSP == null || !activeSP.getFQDN().equals(homeSP.getFQDN())) {
                    throw new IOException("Remediation restricted to HomeSP");
                }
                doRemediate(updateInfo.getURI(), network, homeSP, policy);
                break;
            }
            case RoamingPartner: {
                Network network = mWifiNetworkAdapter.getCurrentNetwork();
                if (network == null) {
                    throw new IOException("Failed to determine current network");
                }

                WifiInfo wifiInfo = mWifiNetworkAdapter.getConnectionInfo();
                if (wifiInfo == null) {
                    throw new IOException("Unable to determine WiFi info");
                }

                PasspointMatch match = mWifiNetworkAdapter.
                        matchProviderWithCurrentNetwork(homeSP.getFQDN());

                if (match == PasspointMatch.HomeProvider ||
                        match == PasspointMatch.RoamingProvider) {
                    doRemediate(updateInfo.getURI(), network, homeSP, policy);
                } else {
                    throw new IOException("No roaming network match: " + match);
                }
                break;
            }
            case Unrestricted: {
                Network network = mWifiNetworkAdapter.getCurrentNetwork();
                doRemediate(updateInfo.getURI(), network, homeSP, policy);
                break;
            }
        }
    }

    private void doRemediate(String url, Network network, HomeSP homeSP, boolean policy)
            throws IOException {
        synchronized (mWifiNetworkAdapter) {
            OSUThread existing = mServiceThreads.get(homeSP.getFQDN());
            if (existing != null) {
                if (System.currentTimeMillis() - existing.getLaunchTime() > REMEDIATION_TIMEOUT) {
                    throw new IOException("Ignoring recurring remediation request");
                } else {
                    existing.connect(null);
                }
            }

            try {
                OSUThread osuThread = new OSUThread(url, this,
                        getKeyManager(homeSP, mKeyStore),
                        homeSP, policy ? FLOW_POLICY : FLOW_REMEDIATION);
                osuThread.start();
                osuThread.connect(network);
                mServiceThreads.put(homeSP.getFQDN(), osuThread);
            } catch (MalformedURLException me) {
                throw new IOException("Failed to start remediation: " + me);
            }
        }
    }

    public MOTree getMOTree(HomeSP homeSP) throws IOException {
        return mWifiNetworkAdapter.getMOTree(homeSP);
    }

    public void notifyIconReceived(long bssid, String fileName, byte[] data) {
        mIconCache.notifyIconReceived(bssid, fileName, data);
    }

    public void doIconQuery(long bssid, String fileName) {
        mWifiNetworkAdapter.doIconQuery(bssid, fileName);
    }

    protected URL prepareUserInput(String spName) throws IOException {
        mRedirectListener = new RedirectListener(this, spName);
        return mRedirectListener.getURL();
    }

    protected boolean startUserInput(URL target, Network network) throws IOException {
        mRedirectListener.startService();
        mWifiNetworkAdapter.launchBrowser(target, network, mRedirectListener.getURL());

        return mRedirectListener.waitForUser();
    }

    public String notifyUser(OSUOperationStatus status, String message, String spName) {
        if (status == OSUOperationStatus.UserInputComplete) {
            return null;
        }
        if (mOSUNwkID != null) {
            // Delete the OSU network if it was added by the OSU flow
            mWifiNetworkAdapter.deleteNetwork(mOSUNwkID);
            mOSUNwkID = null;
        }
        mAppBridge.showStatus(status, spName, message, null);
        return null;
    }

    public void provisioningFailed(String spName, String message, HomeSP homeSP,
                                   int flowType) {
        synchronized (mWifiNetworkAdapter) {
            switch (flowType) {
                case FLOW_PROVISIONING:
                    mProvisioningThread = null;
                    if (mRedirectListener != null) {
                        mRedirectListener.abort();
                        mRedirectListener = null;
                    }
                    break;
                case FLOW_REMEDIATION:
                case FLOW_POLICY:
                    mServiceThreads.remove(homeSP.getFQDN());
                    if (mServiceThreads.isEmpty() && mRedirectListener != null) {
                        mRedirectListener.abort();
                        mRedirectListener = null;
                    }
                    break;
            }
        }
        notifyUser(OSUOperationStatus.ProvisioningFailure, message, spName);
    }

    public void provisioningComplete(OSUInfo osuInfo,
                                     MOData moData, Map<OSUCertType, List<X509Certificate>> certs,
                                     PrivateKey privateKey, Network osuNetwork) {
        synchronized (mWifiNetworkAdapter) {
            mProvisioningThread = null;
        }
        try {
            Log.d("ZXZ", "MOTree.toXML: " + moData.getMOTree().toXml());
            HomeSP homeSP = mWifiNetworkAdapter.addSP(moData.getMOTree());

            Integer spNwk = mWifiNetworkAdapter.addNetwork(homeSP, certs, privateKey, osuNetwork);
            if (spNwk == null) {
                notifyUser(OSUOperationStatus.ProvisioningFailure,
                        "Failed to save network configuration", osuInfo.getName(LOCALE));
                mWifiNetworkAdapter.removeSP(homeSP.getFQDN());
            } else {
                Set<X509Certificate> rootCerts = OSUSocketFactory.getRootCerts(mKeyStore);
                X509Certificate remCert = getCert(certs, OSUCertType.Remediation);
                X509Certificate polCert = getCert(certs, OSUCertType.Policy);
                if (privateKey != null) {
                    X509Certificate cltCert = getCert(certs, OSUCertType.Client);
                    mKeyStore.setKeyEntry(CERT_CLT_KEY_ALIAS + homeSP,
                            privateKey.getEncoded(),
                            new X509Certificate[]{cltCert});
                    mKeyStore.setCertificateEntry(CERT_CLT_CERT_ALIAS, cltCert);
                }
                boolean usingShared = false;
                int newCerts = 0;
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
                    try (FileOutputStream out = new FileOutputStream(KEYSTORE_FILE)) {
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

    private static X509Certificate getCert(Map<OSUCertType, List<X509Certificate>> certMap,
                                           OSUCertType certType) {
        List<X509Certificate> certs = certMap.get(certType);
        if (certs == null || certs.isEmpty()) {
            return null;
        }
        return certs.iterator().next();
    }

    public void spDeleted(String fqdn) {
        int count = deleteCerts(mKeyStore, fqdn,
                CERT_REM_ALIAS, CERT_POLICY_ALIAS, CERT_SHARED_ALIAS);

        if (count > 0) {
            try (FileOutputStream out = new FileOutputStream(KEYSTORE_FILE)) {
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

    public void remediationComplete(HomeSP homeSP, Collection<MOData> mods,
                                    Map<OSUCertType, List<X509Certificate>> certs,
                                    PrivateKey privateKey)
            throws IOException, GeneralSecurityException {

        HomeSP altSP = mWifiNetworkAdapter.modifySP(homeSP, mods);
        X509Certificate caCert = null;
        List<X509Certificate> clientCerts = null;
        if (certs != null) {
            List<X509Certificate> certList = certs.get(OSUCertType.AAA);
            caCert = certList != null && !certList.isEmpty() ? certList.iterator().next() : null;
            clientCerts = certs.get(OSUCertType.Client);
        }
        if (altSP != null || certs != null) {
            if (altSP == null) {
                altSP = homeSP;     // No MO mods, only certs and key
            }
            mWifiNetworkAdapter.updateNetwork(altSP, caCert, clientCerts, privateKey);
        }
        notifyUser(OSUOperationStatus.ProvisioningSuccess, null, homeSP.getFriendlyName());
    }

    protected OMADMAdapter getOMADMAdapter() {
        return OMADMAdapter.getInstance(mContext);
    }
}
