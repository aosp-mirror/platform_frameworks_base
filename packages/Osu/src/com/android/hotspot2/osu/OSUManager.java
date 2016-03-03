package com.android.hotspot2.osu;

import android.content.Context;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.util.Log;

import com.android.anqp.Constants;
import com.android.anqp.HSIconFileElement;
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
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;

public class OSUManager {
    public static final String TAG = "OSUMGR";
    public static final boolean R2_ENABLED = true;
    public static final boolean R2_MOCK = true;
    private static final boolean MATCH_BSSID = false;

    private static final String KEYSTORE_FILE = "passpoint.ks";

    private static final String OSU_COUNT = "osu-count";
    private static final String SP_NAME = "sp-name";
    private static final String PROV_SUCCESS = "prov-success";
    private static final String DEAUTH = "deauth";
    private static final String DEAUTH_DELAY = "deauth-delay";
    private static final String DEAUTH_URL = "deauth-url";
    private static final String PROV_MESSAGE = "prov-message";

    private static final long REMEDIATION_TIMEOUT = 120000L;
    // How many scan result batches to hang on to

    public enum FlowType {Provisioning, Remediation, Policy}

    public static final String CERT_WFA_ALIAS = "wfa-root-";
    public static final String CERT_REM_ALIAS = "rem-";
    public static final String CERT_POLICY_ALIAS = "pol-";
    public static final String CERT_SHARED_ALIAS = "shr-";
    public static final String CERT_CLT_CERT_ALIAS = "clt-";
    public static final String CERT_CLT_KEY_ALIAS = "prv-";
    public static final String CERT_CLT_CA_ALIAS = "aaa-";
    private static final long THREAD_TIMEOUT = 10;             // Seconds

    // Preferred icon parameters
    public static final Locale LOCALE = java.util.Locale.getDefault();

    private final Object mFlowLock = new Object();
    private final LinkedBlockingQueue<FlowWorker> mWorkQueue = new LinkedBlockingQueue<>();
    private FlowRunner mFlowThread;

    private final WifiNetworkAdapter mWifiNetworkAdapter;

    private final AppBridge mAppBridge;
    private final Context mContext;
    private final IconCache mIconCache;
    private final SubscriptionTimer mSubscriptionTimer;
    private final Set<String> mOSUSSIDs = new HashSet<>();
    private final Map<OSUProvider, OSUInfo> mOSUMap = new HashMap<>();
    private final File mKeyStoreFile;
    private final KeyStore mKeyStore;
    private volatile RedirectListener mRedirectListener;
    private final AtomicInteger mOSUSequence = new AtomicInteger();
    private volatile Network mActiveNetwork;
    private volatile FlowWorker mRemediationFlow;
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
        mKeyStoreFile = new File(context.getFilesDir(), KEYSTORE_FILE);
        Log.d(TAG, "KS file: " + mKeyStoreFile.getPath());
        KeyStore ks = null;
        try {
            //ks = loadKeyStore(KEYSTORE_FILE, readCertsFromDisk(WFA_CA_LOC));
            ks = loadKeyStore(mKeyStoreFile, OSUSocketFactory.buildCertSet());
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

    public KeyStore getKeyStore() {
        return mKeyStore;
    }

    private static class FlowWorker implements Runnable {
        private final OSUClient mOSUClient;
        private final OSUManager mOSUManager;
        private final HomeSP mHomeSP;
        private final String mSpName;
        private final FlowType mFlowType;
        private final KeyManager mKeyManager;
        private final long mLaunchTime;
        private final Network mNetwork;

        private FlowWorker(Network network, OSUInfo osuInfo, OSUManager osuManager, KeyManager km)
                throws MalformedURLException {
            mNetwork = network;
            mOSUClient = new OSUClient(osuInfo, osuManager.getKeyStore());
            mOSUManager = osuManager;
            mHomeSP = null;
            mSpName = osuInfo.getName(LOCALE);
            mFlowType = FlowType.Provisioning;
            mKeyManager = km;
            mLaunchTime = System.currentTimeMillis();
        }

        private FlowWorker(Network network, String osuURL, OSUManager osuManager, KeyManager km,
                           HomeSP homeSP, FlowType flowType) throws MalformedURLException {
            mNetwork = network;
            mOSUClient = new OSUClient(osuURL, osuManager.getKeyStore());
            mOSUManager = osuManager;
            mHomeSP = homeSP;
            mSpName = homeSP.getFriendlyName();
            mFlowType = flowType;
            mKeyManager = km;
            mLaunchTime = System.currentTimeMillis();
        }

        public long getLaunchTime() {
            return mLaunchTime;
        }

        private Network getNetwork() {
            return mNetwork;
        }

        @Override
        public void run() {
            Log.d(TAG, "OSU SSID Associated at " + mNetwork);
            try {
                if (mFlowType == FlowType.Provisioning) {
                    mOSUClient.provision(mOSUManager, mNetwork, mKeyManager);
                } else {
                    mOSUClient.remediate(mOSUManager, mNetwork, mKeyManager, mHomeSP, mFlowType);
                }
            } catch (Throwable t) {
                Log.w(TAG, "OSU flow failed: " + t, t);
                mOSUManager.provisioningFailed(mSpName, t.getMessage(), mHomeSP, mFlowType);
            }
        }

        @Override
        public String toString() {
            return mFlowType + " for " + mSpName;
        }
    }

    private static class FlowRunner extends Thread {
        private final LinkedBlockingQueue<FlowWorker> mWorkQueue;
        private final OSUManager mOSUManager;

        private FlowRunner(LinkedBlockingQueue<FlowWorker> workQueue, OSUManager osuManager) {
            mWorkQueue = workQueue;
            mOSUManager = osuManager;
            setDaemon(true);
            setName("OSU Client Thread");
        }

        @Override
        public void run() {
            for (;;) {
                FlowWorker flowWorker;
                try {
                    flowWorker = mWorkQueue.poll(THREAD_TIMEOUT, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    flowWorker = null;
                }
                if (flowWorker == null) {
                    if (mOSUManager.serviceThreadExit()) {
                        return;
                    } else {
                        continue;
                    }
                }
                Log.d(TAG, "Starting " + flowWorker);
                flowWorker.run();
            }
        }
    }

    private void startOsuFlow(FlowWorker flowWorker) {
        synchronized (mFlowLock) {
            mWorkQueue.offer(flowWorker);
            if (mFlowThread == null) {
                mFlowThread = new FlowRunner(mWorkQueue, this);
                mFlowThread.start();
            }
        }
    }

    private boolean serviceThreadExit() {
        synchronized (mFlowLock) {
            if (mWorkQueue.isEmpty()) {
                mFlowThread = null;
                return true;
            } else {
                return false;
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
                Utils.unquote(config.SSID).equals(selection.getOsuSsid())) {

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

    public void networkDeleted(WifiConfiguration configuration) {
        Log.d("ZXZ", "Network deleted: " + configuration.FQDN);
        HomeSP homeSP = mWifiNetworkAdapter.getHomeSP(configuration);
        if (homeSP != null) {
            spDeleted(homeSP.getFQDN());
        }
    }

    public void networkChanged(WifiConfiguration configuration) {
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
            mIconCache.tick(true);
            if (current > 0) {
                notifyOSUCount();
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
        mActiveNetwork = network;
        if (mPendingOSU != null &&
                wifiConfiguration != null &&
                network != null &&
                bssidMatch(mPendingOSU) &&
                Utils.unquote(wifiConfiguration.SSID).equals(mPendingOSU.getOsuSsid())) {

            try {
                Log.d(TAG, "New network " + network + ", current OSU " + mPendingOSU);
                initiateProvisioning(mPendingOSU, network);
            } catch (IOException ioe) {
                notifyUser(OSUOperationStatus.ProvisioningFailure, ioe.getMessage(),
                        mPendingOSU.getName(LOCALE));
            } finally {
                mPendingOSU = null;
            }
        }

        if (mRemediationFlow != null && network != null &&
                mRemediationFlow.getNetwork().netId == network.netId) {
            startOsuFlow(mRemediationFlow);
            mRemediationFlow = null;
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
        startOsuFlow(new FlowWorker(network, osuInfo, this, getKeyManager(null, mKeyStore)));
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

    public void pushScanResults(Collection<ScanResult> scanResults) {
        Map<OSUProvider, ScanResult> results = mOSUCache.pushScanResults(scanResults);
        if (results != null) {
            updateOSUInfoCache(results);
        }
        mIconCache.tick(false);
    }

    private void updateOSUInfoCache(Map<OSUProvider, ScanResult> results) {
        Map<OSUProvider, OSUInfo> osus = new HashMap<>();
        for (Map.Entry<OSUProvider, ScanResult> entry : results.entrySet()) {
            OSUInfo existing = mOSUMap.get(entry.getKey());
            long bssid = Utils.parseMac(entry.getValue().BSSID);

            if (existing == null) {
                osus.put(entry.getKey(), new OSUInfo(entry.getValue(), entry.getKey().getSSID(),
                        entry.getKey(), mOSUSequence.getAndIncrement()));
            } else if (existing.getBSSID() != bssid) {
                HSIconFileElement icon = mIconCache.getIcon(existing);
                if (icon != null && icon.equals(existing.getIconFileElement())) {
                    OSUInfo osuInfo = new OSUInfo(entry.getValue(), entry.getKey().getSSID(),
                            entry.getKey(), existing.getOsuID());
                    osuInfo.setIconFileElement(icon, existing.getIconFileName());
                    osus.put(entry.getKey(), osuInfo);
                } else {
                    osus.put(entry.getKey(), new OSUInfo(entry.getValue(), entry.getKey().getSSID(),
                            entry.getKey(), mOSUSequence.getAndIncrement()));
                }
            } else {
                // Maintain existing entries.
                osus.put(entry.getKey(), existing);
            }
        }

        mOSUMap.clear();
        mOSUMap.putAll(osus);

        mOSUSSIDs.clear();
        for (OSUInfo osuInfo : mOSUMap.values()) {
            mOSUSSIDs.add(osuInfo.getOsuSsid());
        }

        int mods = mIconCache.resolveIcons(mOSUMap.values());

        if (mOSUMap.isEmpty() || mods > 0) {
            notifyOSUCount();
        }
    }

    public void notifyIconReceived(long bssid, String fileName, byte[] data) {
        if (mIconCache.notifyIconReceived(bssid, fileName, data) > 0) {
            notifyOSUCount();
        }
    }

    public void doIconQuery(long bssid, String fileName) {
        mWifiNetworkAdapter.doIconQuery(bssid, fileName);
    }

    private void notifyOSUCount() {
        int count = 0;
        for (OSUInfo existing : mOSUMap.values()) {
            if (existing.getIconStatus() == OSUInfo.IconStatus.Available) {
                count++;
            }
        }
        Log.d(TAG, "Latest OSU info: " + count + " with icons, map " + mOSUMap);
        mAppBridge.showOsuCount(count, getAvailableOSUs());
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
    public void wnmRemediate(long bssid, String url, PasspointMatch match)
            throws IOException, SAXException {
        HomeSP homeSP = mWifiNetworkAdapter.getCurrentSP();
        if (homeSP == null) {
            throw new IOException("Remediation request on unidentified Passpoint network ");
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

        FlowWorker flowWorker = new FlowWorker(network, url, this,
                getKeyManager(homeSP, mKeyStore), homeSP, FlowType.Remediation);

        if (mActiveNetwork != null && wifiInfo.getNetworkId() == mActiveNetwork.netId) {
            startOsuFlow(flowWorker);
        } else {
            mRemediationFlow = flowWorker;
        }
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

                HomeSP activeSP = mWifiNetworkAdapter.getCurrentSP();

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

        startOsuFlow(new FlowWorker(network, url, this,
                getKeyManager(homeSP, mKeyStore),
                homeSP, policy ? FlowType.Policy : FlowType.Remediation));
    }

    public MOTree getMOTree(HomeSP homeSP) throws IOException {
        return mWifiNetworkAdapter.getMOTree(homeSP);
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

    public void provisioningFailed(String spName, String message,
                                   HomeSP homeSP, FlowType flowType) {
        if (mRedirectListener != null) {
            mRedirectListener.abort();
            mRedirectListener = null;
        }
        notifyUser(OSUOperationStatus.ProvisioningFailure, message, spName);
    }

    public void provisioningComplete(OSUInfo osuInfo,
                                     MOData moData, Map<OSUCertType, List<X509Certificate>> certs,
                                     PrivateKey privateKey, Network osuNetwork) {
        try {
            String xml = moData.getMOTree().toXml();
            HomeSP homeSP = MOManager.buildSP(xml);

            Integer spNwk = mWifiNetworkAdapter.addNetwork(homeSP, certs, privateKey, osuNetwork);
            if (spNwk == null) {
                notifyUser(OSUOperationStatus.ProvisioningFailure,
                        "Failed to save network configuration", osuInfo.getName(LOCALE));
            } else {
                if (mWifiNetworkAdapter.addSP(xml) < 0) {
                    mWifiNetworkAdapter.deleteNetwork(spNwk);
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

                Log.d("ZXZ", "Got " + newCerts + " new certs.");
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

    public void remediationComplete(HomeSP homeSP, Collection<MOData> mods,
                                    Map<OSUCertType, List<X509Certificate>> certs,
                                    PrivateKey privateKey)
            throws IOException, GeneralSecurityException {

        HomeSP altSP = null;
        if (mWifiNetworkAdapter.modifySP(homeSP, mods) > 0) {
            altSP = MOManager.modifySP(homeSP, mWifiNetworkAdapter.getMOTree(homeSP), mods);
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
            mWifiNetworkAdapter.updateNetwork(altSP, caCert, clientCerts, privateKey);
        }
        notifyUser(OSUOperationStatus.ProvisioningSuccess, null, homeSP.getFriendlyName());
    }

    protected OMADMAdapter getOMADMAdapter() {
        return OMADMAdapter.getInstance(mContext);
    }
}
