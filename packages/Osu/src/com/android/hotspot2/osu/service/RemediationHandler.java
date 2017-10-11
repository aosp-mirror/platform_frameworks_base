package com.android.hotspot2.osu.service;

import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Network;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.hotspot2.PasspointMatch;
import com.android.hotspot2.Utils;
import com.android.hotspot2.flow.FlowService;
import com.android.hotspot2.omadm.MOManager;
import com.android.hotspot2.omadm.MOTree;
import com.android.hotspot2.omadm.OMAConstants;
import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.OMAParser;
import com.android.hotspot2.osu.OSUManager;
import com.android.hotspot2.pps.HomeSP;
import com.android.hotspot2.pps.UpdateInfo;
import com.android.hotspot2.flow.IFlowService;

import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.android.hotspot2.pps.UpdateInfo.UpdateRestriction;

public class RemediationHandler implements AlarmManager.OnAlarmListener {
    private final Context mContext;
    private final File mStateFile;

    private final Map<String, PasspointConfig> mPasspointConfigs = new HashMap<>();
    private final Map<String, List<RemediationEvent>> mUpdates = new HashMap<>();
    private final LinkedList<PendingUpdate> mOutstanding = new LinkedList<>();

    private WifiInfo mActiveWifiInfo;
    private PasspointConfig mActivePasspointConfig;

    public RemediationHandler(Context context, File stateFile) {
        mContext = context;
        mStateFile = stateFile;
        Log.d(OSUManager.TAG, "State file: " + stateFile);
        reloadAll(context, mPasspointConfigs, stateFile, mUpdates);
        mActivePasspointConfig = getActivePasspointConfig();
        calculateTimeout();
    }

    /**
     * Network configs change: Re-evaluate set of HomeSPs and recalculate next time-out.
     */
    public void networkConfigChange() {
        Log.d(OSUManager.TAG, "Networks changed");
        mPasspointConfigs.clear();
        mUpdates.clear();
        Iterator<PendingUpdate> updates = mOutstanding.iterator();
        while (updates.hasNext()) {
            PendingUpdate update = updates.next();
            if (!update.isWnmBased()) {
                updates.remove();
            }
        }
        reloadAll(mContext, mPasspointConfigs, mStateFile, mUpdates);
        calculateTimeout();
    }

    /**
     * Connected to new network: Try to rematch any outstanding remediation entries to the new
     * config.
     */
    public void newConnection(WifiInfo newNetwork) {
        mActivePasspointConfig = newNetwork != null ? getActivePasspointConfig() : null;
        if (mActivePasspointConfig != null) {
            Log.d(OSUManager.TAG, "New connection to "
                    + mActivePasspointConfig.getHomeSP().getFQDN());
        } else {
            Log.d(OSUManager.TAG, "No passpoint connection");
            return;
        }
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        Network network = wifiManager.getCurrentNetwork();

        Iterator<PendingUpdate> updates = mOutstanding.iterator();
        while (updates.hasNext()) {
            PendingUpdate update = updates.next();
            try {
                if (update.matches(wifiInfo, mActivePasspointConfig.getHomeSP())) {
                    update.remediate(network);
                    updates.remove();
                } else if (update.isWnmBased()) {
                    Log.d(OSUManager.TAG, "WNM sender mismatches with BSS, cancelling remediation");
                    // Drop WNM update if it doesn't match the connected network
                    updates.remove();
                }
            } catch (IOException ioe) {
                updates.remove();
            }
        }
    }

    /**
     * Remediation timer fired: Iterate HomeSP and either pass on to remediation if there is a
     * policy match or put on hold-off queue until a new network connection is made.
     */
    @Override
    public void onAlarm() {
        Log.d(OSUManager.TAG, "Remediation timer");
        calculateTimeout();
    }

    /**
     * Remediation frame received, either pass on to pre-remediation check right away or await
     * network connection.
     */
    public void wnmReceived(long bssid, String url) {
        PendingUpdate update = new PendingUpdate(bssid, url);
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        try {
            if (mActivePasspointConfig == null) {
                Log.d(OSUManager.TAG, String.format("WNM remediation frame '%s' through %012x " +
                        "received, adding to outstanding remediations", url, bssid));
                mOutstanding.addFirst(new PendingUpdate(bssid, url));
            } else if (update.matches(wifiInfo, mActivePasspointConfig.getHomeSP())) {
                Log.d(OSUManager.TAG, String.format("WNM remediation frame '%s' through %012x " +
                        "received, remediating now", url, bssid));
                update.remediate(wifiManager.getCurrentNetwork());
            } else {
                Log.w(OSUManager.TAG, String.format("WNM remediation frame '%s' through %012x " +
                        "does not meet restriction", url, bssid));
            }
        } catch (IOException ioe) {
            Log.w(OSUManager.TAG, "Failed to remediate from WNM: " + ioe);
        }
    }

    /**
     * Callback to indicate that remediation has succeeded.
     * @param fqdn The SPs FQDN
     * @param policy set if this update was a policy update rather than a subscription update.
     */
    public void remediationDone(String fqdn, boolean policy) {
        Log.d(OSUManager.TAG, "Remediation complete for " + fqdn);
        long now = System.currentTimeMillis();
        List<RemediationEvent> events = mUpdates.get(fqdn);
        if (events == null) {
            events = new ArrayList<>();
            events.add(new RemediationEvent(fqdn, policy, now));
            mUpdates.put(fqdn, events);
        } else {
            Iterator<RemediationEvent> eventsIterator = events.iterator();
            while (eventsIterator.hasNext()) {
                RemediationEvent event = eventsIterator.next();
                if (event.isPolicy() == policy) {
                    eventsIterator.remove();
                }
            }
            events.add(new RemediationEvent(fqdn, policy, now));
        }
        saveUpdates(mStateFile, mUpdates);
    }

    public String getCurrentSpName() {
        PasspointConfig config = getActivePasspointConfig();
        return config != null ? config.getHomeSP().getFriendlyName() : "unknown";
    }

    private PasspointConfig getActivePasspointConfig() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mActiveWifiInfo = wifiManager.getConnectionInfo();
        if (mActiveWifiInfo == null) {
            return null;
        }

        for (PasspointConfig passpointConfig : mPasspointConfigs.values()) {
            if (passpointConfig.getWifiConfiguration().networkId
                    == mActiveWifiInfo.getNetworkId()) {
                return passpointConfig;
            }
        }
        return null;
    }

    private void calculateTimeout() {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        Network network = wifiManager.getCurrentNetwork();

        boolean newBaseTimes = false;
        for (PasspointConfig passpointConfig : mPasspointConfigs.values()) {
            HomeSP homeSP = passpointConfig.getHomeSP();

            for (boolean policy : new boolean[] {false, true}) {
                Long expiry = getNextUpdate(homeSP, policy, now);
                Log.d(OSUManager.TAG, "Next remediation for " + homeSP.getFQDN()
                        + (policy ? "/policy" : "/subscription")
                        + " is " + toExpiry(expiry));
                if (expiry == null || inProgress(homeSP, policy)) {
                    continue;
                } else if (expiry < 0) {
                    next = now - expiry;
                    newBaseTimes = true;
                    continue;
                }

                if (expiry <= now) {
                    String uri = policy ? homeSP.getPolicy().getPolicyUpdate().getURI()
                            : homeSP.getSubscriptionUpdate().getURI();
                    PendingUpdate update = new PendingUpdate(homeSP, uri, policy);
                    try {
                        if (update.matches(mActiveWifiInfo, homeSP)) {
                            update.remediate(network);
                        } else {
                            Log.d(OSUManager.TAG, "Remediation for "
                                    + homeSP.getFQDN() + " pending");
                            mOutstanding.addLast(update);
                        }
                    } catch (IOException ioe) {
                        Log.w(OSUManager.TAG, "Failed to remediate "
                                + homeSP.getFQDN() + ": " + ioe);
                    }
                } else {
                    next = Math.min(next, expiry);
                }
            }
        }
        if (newBaseTimes) {
            saveUpdates(mStateFile, mUpdates);
        }
        Log.d(OSUManager.TAG, "Next time-out at " + toExpiry(next));
        AlarmManager alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC, next, "osu-remediation", this, null);
    }

    private static String toExpiry(Long time) {
        if (time == null) {
            return "n/a";
        } else if (time < 0) {
            return Utils.toHMS(-time) + " from now";
        } else if (time > 0xffffffffffffL) {
            return "infinity";
        } else {
            return Utils.toUTCString(time);
        }
    }

    /**
     * Get the next update time for the homeSP subscription or policy entry. Automatically add a
     * wall time reference if it is missing.
     * @param homeSP The HomeSP to check
     * @param policy policy or subscription object.
     * @return -interval if no wall time ref, null if n/a, otherwise wall time of next update.
     */
    private Long getNextUpdate(HomeSP homeSP, boolean policy, long now) {
        long interval;
        if (policy) {
            interval = homeSP.getPolicy().getPolicyUpdate().getInterval();
        } else if (homeSP.getSubscriptionUpdate() != null) {
            interval = homeSP.getSubscriptionUpdate().getInterval();
        } else {
            return null;
        }
        if (interval < 0) {
            return null;
        }

        RemediationEvent event = getMatchingEvent(mUpdates.get(homeSP.getFQDN()), policy);
        if (event == null) {
            List<RemediationEvent> events = mUpdates.get(homeSP.getFQDN());
            if (events == null) {
                events = new ArrayList<>();
                mUpdates.put(homeSP.getFQDN(), events);
            }
            events.add(new RemediationEvent(homeSP.getFQDN(), policy, now));
            return -interval;
        }
        return event.getLastUpdate() + interval;
    }

    private boolean inProgress(HomeSP homeSP, boolean policy) {
        Iterator<PendingUpdate> updates = mOutstanding.iterator();
        while (updates.hasNext()) {
            PendingUpdate update = updates.next();
            if (update.getHomeSP() != null
                    && update.getHomeSP().getFQDN().equals(homeSP.getFQDN())) {
                if (update.isPolicy() && !policy) {
                    // Subscription updates takes precedence over policy updates
                    updates.remove();
                    return false;
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    private static RemediationEvent getMatchingEvent(
            List<RemediationEvent> events, boolean policy) {
        if (events == null) {
            return null;
        }
        for (RemediationEvent event : events) {
            if (event.isPolicy() == policy) {
                return event;
            }
        }
        return null;
    }

    private static void reloadAll(Context context, Map<String, PasspointConfig> passpointConfigs,
                                  File stateFile, Map<String, List<RemediationEvent>> updates) {

        loadAllSps(context, passpointConfigs);
        try {
            loadUpdates(stateFile, updates);
        } catch (IOException ioe) {
            Log.w(OSUManager.TAG, "Failed to load updates file: " + ioe);
        }

        boolean change = false;
        Iterator<Map.Entry<String, List<RemediationEvent>>> events = updates.entrySet().iterator();
        while (events.hasNext()) {
            Map.Entry<String, List<RemediationEvent>> event = events.next();
            if (!passpointConfigs.containsKey(event.getKey())) {
                events.remove();
                change = true;
            }
        }
        Log.d(OSUManager.TAG, "Updates: " + updates);
        if (change) {
            saveUpdates(stateFile, updates);
        }
    }

    private static void loadAllSps(Context context, Map<String, PasspointConfig> passpointConfigs) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        List<WifiConfiguration> configs = wifiManager.getPrivilegedConfiguredNetworks();
        if (configs == null) {
            return;
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
    }

    private static void loadUpdates(File file, Map<String, List<RemediationEvent>> updates)
            throws IOException {
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    RemediationEvent event = new RemediationEvent(line);
                    List<RemediationEvent> events = updates.get(event.getFqdn());
                    if (events == null) {
                        events = new ArrayList<>();
                        updates.put(event.getFqdn(), events);
                    }
                    events.add(event);
                } catch (IOException | NumberFormatException e) {
                    Log.w(OSUManager.TAG, "Bad line in " + file + ": '" + line + "': " + e);
                }
            }
        }
    }

    private static void saveUpdates(File file, Map<String, List<RemediationEvent>> updates) {
        try (BufferedWriter out = new BufferedWriter(new FileWriter(file, false))) {
            for (List<RemediationEvent> events : updates.values()) {
                for (RemediationEvent event : events) {
                    Log.d(OSUManager.TAG, "Writing wall time ref for " + event);
                    out.write(event.toString());
                    out.newLine();
                }
            }
        } catch (IOException ioe) {
            Log.w(OSUManager.TAG, "Failed to save update state: " + ioe);
        }
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

        public MOTree getMOTree() {
            return mMOTree;
        }
    }

    private static class RemediationEvent {
        private final String mFqdn;
        private final boolean mPolicy;
        private final long mLastUpdate;

        private RemediationEvent(String value) throws IOException {
            String[] segments = value.split(" ");
            if (segments.length != 3) {
                throw new IOException("Bad line: '" + value + "'");
            }
            mFqdn = segments[0];
            mPolicy = segments[1].equals("1");
            mLastUpdate = Long.parseLong(segments[2]);
        }

        private RemediationEvent(String fqdn, boolean policy, long now) {
            mFqdn = fqdn;
            mPolicy = policy;
            mLastUpdate = now;
        }

        public String getFqdn() {
            return mFqdn;
        }

        public boolean isPolicy() {
            return mPolicy;
        }

        public long getLastUpdate() {
            return mLastUpdate;
        }

        @Override
        public String toString() {
            return String.format("%s %c %d", mFqdn, mPolicy ? '1' : '0', mLastUpdate);
        }
    }

    private class PendingUpdate {
        private final HomeSP mHomeSP;       // For time based updates
        private final long mBssid;          // WNM based
        private final String mUrl;          // WNM based
        private final boolean mPolicy;

        private PendingUpdate(HomeSP homeSP, String url, boolean policy) {
            mHomeSP = homeSP;
            mPolicy = policy;
            mBssid = 0L;
            mUrl = url;
        }

        private PendingUpdate(long bssid, String url) {
            mBssid = bssid;
            mUrl = url;
            mHomeSP = null;
            mPolicy = false;
        }

        private boolean matches(WifiInfo wifiInfo, HomeSP activeSP) throws IOException {
            if (mHomeSP == null) {
                // WNM initiated remediation, HomeSP restriction
                Log.d(OSUManager.TAG, String.format("Checking applicability of %s to %012x\n",
                        wifiInfo != null ? wifiInfo.getBSSID() : "-", mBssid));
                return wifiInfo != null
                        && Utils.parseMac(wifiInfo.getBSSID()) == mBssid
                        && passesRestriction(activeSP);   // !!! b/28600780
            } else {
                return passesRestriction(mHomeSP);
            }
        }

        private boolean passesRestriction(HomeSP restrictingSP)
                throws IOException {
            UpdateInfo updateInfo;
            if (mPolicy) {
                if (restrictingSP.getPolicy() == null) {
                    throw new IOException("No policy object");
                }
                updateInfo = restrictingSP.getPolicy().getPolicyUpdate();
            } else {
                updateInfo = restrictingSP.getSubscriptionUpdate();
            }

            if (updateInfo.getUpdateRestriction() == UpdateRestriction.Unrestricted) {
                return true;
            }

            PasspointMatch match = matchProviderWithCurrentNetwork(restrictingSP.getFQDN());
            Log.d(OSUManager.TAG, "Current match for '" + restrictingSP.getFQDN()
                    + "' is " + match + ", restriction " + updateInfo.getUpdateRestriction());
            return match == PasspointMatch.HomeProvider
                    || (match == PasspointMatch.RoamingProvider
                    && updateInfo.getUpdateRestriction() == UpdateRestriction.RoamingPartner);
        }

        private void remediate(Network network) {
            RemediationHandler.this.remediate(mHomeSP != null ? mHomeSP.getFQDN() : null,
                    mUrl, mPolicy, network);
        }

        private HomeSP getHomeSP() {
            return mHomeSP;
        }

        private boolean isPolicy() {
            return mPolicy;
        }

        private boolean isWnmBased() {
            return mHomeSP == null;
        }

        private PasspointMatch matchProviderWithCurrentNetwork(String fqdn) {
            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            return Utils.mapEnum(wifiManager.matchProviderWithCurrentNetwork(fqdn),
                    PasspointMatch.class);
        }
    }

    /**
     * Initiate remediation
     * @param spFqdn The FQDN of the current SP, not set for WNM based remediation
     * @param url The URL of the remediation server
     * @param policy Set if this is a policy update rather than a subscription update
     * @param network The network to use for remediation
     */
    private void remediate(final String spFqdn, final String url,
                           final boolean policy, final Network network) {
        mContext.bindService(new Intent(mContext, FlowService.class), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IFlowService fs = IFlowService.Stub.asInterface(service);
                    fs.remediate(spFqdn, url, policy, network);
                } catch (RemoteException re) {
                    Log.e(OSUManager.TAG, "Caught re: " + re);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        }, Context.BIND_AUTO_CREATE);
    }
}
