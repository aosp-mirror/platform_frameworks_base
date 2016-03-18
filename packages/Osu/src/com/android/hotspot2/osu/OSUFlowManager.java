package com.android.hotspot2.osu;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import android.util.Log;

import com.android.hotspot2.Utils;
import com.android.hotspot2.flow.FlowService;
import com.android.hotspot2.flow.OSUInfo;
import com.android.hotspot2.flow.PlatformAdapter;
import com.android.hotspot2.pps.HomeSP;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Iterator;
import java.util.LinkedList;

import javax.net.ssl.KeyManager;

public class OSUFlowManager {
    private static final boolean MATCH_BSSID = false;
    private static final long WAIT_QUANTA = 10000L;
    private static final long WAIT_TIMEOUT = 1800000L;

    private final Context mContext;
    private final LinkedList<OSUFlow> mQueue;
    private FlowWorker mWorker;
    private OSUFlow mCurrent;

    public OSUFlowManager(Context context) {
        mContext = context;
        mQueue = new LinkedList<>();
    }

    public enum FlowType {Provisioning, Remediation, Policy}

    public static class OSUFlow implements Runnable {
        private final OSUClient mOSUClient;
        private final PlatformAdapter mPlatformAdapter;
        private final HomeSP mHomeSP;
        private final String mSpName;
        private final FlowType mFlowType;
        private final KeyManager mKeyManager;
        private final Object mNetworkLock = new Object();
        private final Network mNetwork;
        private Network mResultNetwork;
        private boolean mNetworkCreated;
        private int mWifiNetworkId;
        private volatile long mLaunchTime;
        private volatile boolean mAborted;

        /**
         * A policy flow.
         * @param osuInfo The OSU information for the flow (SSID, BSSID, URL)
         * @param platformAdapter the platform adapter
         * @param km A key manager for TLS
         * @throws MalformedURLException
         */
        public OSUFlow(OSUInfo osuInfo, PlatformAdapter platformAdapter, KeyManager km)
                throws MalformedURLException {

            mWifiNetworkId = -1;
            mNetwork = null;
            mOSUClient = new OSUClient(osuInfo,
                    platformAdapter.getKeyStore(), platformAdapter.getContext());
            mPlatformAdapter = platformAdapter;
            mHomeSP = null;
            mSpName = osuInfo.getName(OSUManager.LOCALE);
            mFlowType = FlowType.Provisioning;
            mKeyManager = km;
        }

        /**
         * A Remediation flow for credential or policy provisioning.
         * @param network The network to use, only set for timed provisioning
         * @param osuURL The URL to connect to.
         * @param platformAdapter the platform adapter
         * @param km A key manager for TLS
         * @param homeSP The Home SP to which this remediation flow pertains.
         * @param flowType Remediation or Policy
         * @throws MalformedURLException
         */
        public OSUFlow(Network network, String osuURL,
                       PlatformAdapter platformAdapter, KeyManager km, HomeSP homeSP,
                       FlowType flowType) throws MalformedURLException {

            mNetwork = network;
            mWifiNetworkId = network.netId;
            mOSUClient = new OSUClient(osuURL,
                    platformAdapter.getKeyStore(), platformAdapter.getContext());
            mPlatformAdapter = platformAdapter;
            mHomeSP = homeSP;
            mSpName = homeSP.getFriendlyName();
            mFlowType = flowType;
            mKeyManager = km;
        }

        private boolean deleteNetwork(OSUFlow next) {
            synchronized (mNetworkLock) {
                if (!mNetworkCreated) {
                    return false;
                } else if (next.getFlowType() != FlowType.Provisioning) {
                    return true;
                }
                OSUInfo thisInfo = mOSUClient.getOSUInfo();
                OSUInfo thatInfo = next.mOSUClient.getOSUInfo();
                if (thisInfo.getOsuSsid().equals(thatInfo.getOsuSsid())
                        && thisInfo.getOSUBssid() == thatInfo.getOSUBssid()) {
                    // Reuse the OSU network from previous and carry forward the creation fact.
                    mNetworkCreated = true;
                    return false;
                } else {
                    return true;
                }
            }
        }

        private Network connect() throws IOException {
            Network network = networkMatch();

            synchronized (mNetworkLock) {
                mResultNetwork = network;
                if (mResultNetwork != null) {
                    return mResultNetwork;
                }
            }

            Log.d(OSUManager.TAG, "No network match for " + toString());

            int osuNetworkId = -1;
            boolean created = false;

            if (mFlowType == FlowType.Provisioning) {
                osuNetworkId = mPlatformAdapter.connect(mOSUClient.getOSUInfo());
                created = true;
            }

            synchronized (mNetworkLock) {
                mNetworkCreated = created;
                if (created) {
                    mWifiNetworkId = osuNetworkId;
                }
                Log.d(OSUManager.TAG, String.format("%s waiting for %snet ID %d",
                        toString(), created ? "created " : "existing ", osuNetworkId));

                while (mResultNetwork == null && !mAborted) {
                    try {
                        mNetworkLock.wait();
                    } catch (InterruptedException ie) {
                        throw new IOException("Interrupted");
                    }
                }
                if (mAborted) {
                    throw new IOException("Aborted");
                }
                Utils.delay(500L);
            }
            return mResultNetwork;
        }

        private Network networkMatch() {
            if (mFlowType == FlowType.Provisioning) {
                OSUInfo match = mOSUClient.getOSUInfo();
                WifiConfiguration config = mPlatformAdapter.getActiveWifiConfig();
                if (config != null && bssidMatch(match, mPlatformAdapter)
                        && Utils.decodeSsid(config.SSID).equals(match.getOsuSsid())) {
                    synchronized (mNetworkLock) {
                        mWifiNetworkId = config.networkId;
                    }
                    return mPlatformAdapter.getCurrentNetwork();
                }
            } else {
                WifiConfiguration config = mPlatformAdapter.getActiveWifiConfig();
                synchronized (mNetworkLock) {
                    mWifiNetworkId = config != null ? config.networkId : -1;
                }
                return mNetwork;
            }
            return null;
        }

        private void networkChange() {
            WifiInfo connectionInfo = mPlatformAdapter.getConnectionInfo();
            if (connectionInfo == null) {
                return;
            }
            Network network = mPlatformAdapter.getCurrentNetwork();
            Log.d(OSUManager.TAG, "New network " + network
                    + ", current OSU " + mOSUClient.getOSUInfo() +
                    ", addr " + Utils.toIpString(connectionInfo.getIpAddress()));

            synchronized (mNetworkLock) {
                if (mResultNetwork == null && network != null && connectionInfo.getIpAddress() != 0
                        && connectionInfo.getNetworkId() == mWifiNetworkId) {
                    mResultNetwork = network;
                    mNetworkLock.notifyAll();
                }
            }
        }

        public boolean createdNetwork() {
            synchronized (mNetworkLock) {
                return mNetworkCreated;
            }
        }

        public FlowType getFlowType() {
            return mFlowType;
        }

        public PlatformAdapter getPlatformAdapter() {
            return mPlatformAdapter;
        }

        private void setLaunchTime() {
            mLaunchTime = SystemClock.currentThreadTimeMillis();
        }

        public long getLaunchTime() {
            return mLaunchTime;
        }

        private int getWifiNetworkId() {
            synchronized (mNetworkLock) {
                return mWifiNetworkId;
            }
        }

        @Override
        public void run() {
            try {
                Network network = connect();
                Log.d(OSUManager.TAG, "OSU SSID Associated at " + network);

                if (mFlowType == FlowType.Provisioning) {
                    mOSUClient.provision(mPlatformAdapter, network, mKeyManager);
                } else {
                    mOSUClient.remediate(mPlatformAdapter, network,
                            mKeyManager, mHomeSP, mFlowType);
                }
            } catch (Throwable t) {
                if (mAborted) {
                    Log.d(OSUManager.TAG, "OSU flow aborted: " + t, t);
                } else {
                    Log.w(OSUManager.TAG, "OSU flow failed: " + t, t);
                    mPlatformAdapter.provisioningFailed(mSpName, t.getMessage());
                }
            } finally {
                if (!mAborted) {
                    mOSUClient.close(false);
                }
            }
        }

        public void abort() {
            synchronized (mNetworkLock) {
                mAborted = true;
                mNetworkLock.notifyAll();
            }
            // Sockets cannot be closed on the main thread...
            // TODO: Might want to change this to a handler.
            new Thread() {
                @Override
                public void run() {
                    try {
                        mOSUClient.close(true);
                    } catch (Throwable t) {
                        Log.d(OSUManager.TAG, "Exception aborting " + toString());
                    }
                }
            }.start();
        }

        @Override
        public String toString() {
            return mFlowType + " for " + mSpName;
        }
    }

    private class FlowWorker extends Thread {
        private final PlatformAdapter mPlatformAdapter;

        private FlowWorker(PlatformAdapter platformAdapter) {
            mPlatformAdapter = platformAdapter;
        }

        @Override
        public void run() {
            for (; ; ) {
                synchronized (mQueue) {
                    if (mCurrent != null && mCurrent.createdNetwork()
                            && (mQueue.isEmpty() || mCurrent.deleteNetwork(mQueue.getLast()))) {
                        mPlatformAdapter.deleteNetwork(mCurrent.getWifiNetworkId());
                    }

                    mCurrent = null;
                    while (mQueue.isEmpty()) {
                        try {
                            mQueue.wait(WAIT_QUANTA);
                        } catch (InterruptedException ie) {
                            return;
                        }
                        if (mQueue.isEmpty()) {
                            // Bail out on time out
                            Log.d(OSUManager.TAG, "Flow worker terminating.");
                            mWorker = null;
                            mContext.stopService(new Intent(mContext, FlowService.class));
                            return;
                        }
                    }
                    mCurrent = mQueue.removeLast();
                    mCurrent.setLaunchTime();
                }
                Log.d(OSUManager.TAG, "Starting " + mCurrent);
                mCurrent.run();
                Log.d(OSUManager.TAG, "Exiting " + mCurrent);
            }
        }
    }

    /*
     * Provisioning:    Wait until there is an active WiFi info and the active WiFi config
     *                  matches SSID and optionally BSSID.
     * WNM Remediation: Wait until the active WiFi info matches BSSID.
     * Timed remediation: The network is given (may be cellular).
     */

    public void appendFlow(OSUFlow flow) {
        synchronized (mQueue) {
            if (mCurrent != null &&
                    SystemClock.currentThreadTimeMillis()
                            - mCurrent.getLaunchTime() >= WAIT_TIMEOUT) {
                Log.d(OSUManager.TAG, "Aborting stale OSU flow " + mCurrent);
                mCurrent.abort();
                mCurrent = null;
            }

            if (flow.getFlowType() == FlowType.Provisioning) {
                // Kill any outstanding provisioning flows.
                Iterator<OSUFlow> flows = mQueue.iterator();
                while (flows.hasNext()) {
                    if (flows.next().getFlowType() == FlowType.Provisioning) {
                        flows.remove();
                    }
                }

                if (mCurrent != null
                        && mCurrent.getFlowType() == FlowType.Provisioning) {
                    Log.d(OSUManager.TAG, "Aborting current provisioning flow " + mCurrent);
                    mCurrent.abort();
                    mCurrent = null;
                }

                mQueue.addLast(flow);
            } else {
                mQueue.addFirst(flow);
            }

            if (mWorker == null) {
                // TODO: Might want to change this to a handler.
                mWorker = new FlowWorker(flow.getPlatformAdapter());
                mWorker.start();
            }

            mQueue.notifyAll();
        }
    }

    public void networkChange() {
        OSUFlow pending;
        synchronized (mQueue) {
            pending = mCurrent;
        }
        Log.d(OSUManager.TAG, "Network change, current flow: " + pending);
        if (pending != null) {
            pending.networkChange();
        }
    }

    private static boolean bssidMatch(OSUInfo osuInfo, PlatformAdapter platformAdapter) {
        if (MATCH_BSSID) {
            WifiInfo wifiInfo = platformAdapter.getConnectionInfo();
            return wifiInfo != null && Utils.parseMac(wifiInfo.getBSSID()) == osuInfo.getOSUBssid();
        } else {
            return true;
        }
    }
}
