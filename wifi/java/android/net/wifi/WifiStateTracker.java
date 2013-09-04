/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.BaseNetworkStateTracker;
import android.net.LinkCapabilities;
import android.net.LinkQualityInfo;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.SamplingDataTracker;
import android.net.WifiLinkQualityInfo;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.util.Slog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Track the state of wifi for connectivity service.
 *
 * @hide
 */
public class WifiStateTracker extends BaseNetworkStateTracker {

    private static final String NETWORKTYPE = "WIFI";
    private static final String TAG = "WifiStateTracker";

    private static final boolean LOGV = true;

    private AtomicBoolean mTeardownRequested = new AtomicBoolean(false);
    private AtomicBoolean mPrivateDnsRouteSet = new AtomicBoolean(false);
    private AtomicBoolean mDefaultRouteSet = new AtomicBoolean(false);

    private NetworkInfo.State mLastState = NetworkInfo.State.UNKNOWN;

    private WifiInfo mWifiInfo;

    /* For sending events to connectivity service handler */
    private Handler mCsHandler;
    private BroadcastReceiver mWifiStateReceiver;
    private WifiManager mWifiManager;

    private SamplingDataTracker mSamplingDataTracker = new SamplingDataTracker();

    public WifiStateTracker(int netType, String networkName) {
        mNetworkInfo = new NetworkInfo(netType, 0, networkName, "");
        mLinkProperties = new LinkProperties();
        mLinkCapabilities = new LinkCapabilities();

        mNetworkInfo.setIsAvailable(false);
        setTeardownRequested(false);
    }


    public void setTeardownRequested(boolean isRequested) {
        mTeardownRequested.set(isRequested);
    }

    public boolean isTeardownRequested() {
        return mTeardownRequested.get();
    }

    /**
     * Begin monitoring wifi connectivity
     */
    public void startMonitoring(Context context, Handler target) {
        mCsHandler = target;
        mContext = context;

        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);

        mWifiStateReceiver = new WifiStateReceiver();
        mContext.registerReceiver(mWifiStateReceiver, filter);
    }

    /**
     * Disable connectivity to a network
     * TODO: do away with return value after making MobileDataStateTracker async
     */
    public boolean teardown() {
        mTeardownRequested.set(true);
        mWifiManager.stopWifi();
        return true;
    }

    /**
     * Re-enable connectivity to a network after a {@link #teardown()}.
     */
    public boolean reconnect() {
        mTeardownRequested.set(false);
        mWifiManager.startWifi();
        return true;
    }

    /**
     * Captive check is complete, switch to network
     */
    @Override
    public void captivePortalCheckComplete() {
        mWifiManager.captivePortalCheckComplete();
    }

    @Override
    public void captivePortalCheckCompleted(boolean isCaptivePortal) {
        // not implemented
    }

    /**
     * Turn the wireless radio off for a network.
     * @param turnOn {@code true} to turn the radio on, {@code false}
     */
    public boolean setRadio(boolean turnOn) {
        mWifiManager.setWifiEnabled(turnOn);
        return true;
    }

    /**
     * Wi-Fi is considered available as long as we have a connection to the
     * supplicant daemon and there is at least one enabled network. If a teardown
     * was explicitly requested, then Wi-Fi can be restarted with a reconnect
     * request, so it is considered available. If the driver has been stopped
     * for any reason other than a teardown request, Wi-Fi is considered
     * unavailable.
     * @return {@code true} if Wi-Fi connections are possible
     */
    public boolean isAvailable() {
        return mNetworkInfo.isAvailable();
    }

    @Override
    public void setUserDataEnable(boolean enabled) {
        Slog.w(TAG, "ignoring setUserDataEnable(" + enabled + ")");
    }

    @Override
    public void setPolicyDataEnable(boolean enabled) {
        // ignored
    }

    /**
     * Check if private DNS route is set for the network
     */
    public boolean isPrivateDnsRouteSet() {
        return mPrivateDnsRouteSet.get();
    }

    /**
     * Set a flag indicating private DNS route is set
     */
    public void privateDnsRouteSet(boolean enabled) {
        mPrivateDnsRouteSet.set(enabled);
    }

    /**
     * Fetch NetworkInfo for the network
     */
    @Override
    public NetworkInfo getNetworkInfo() {
        return new NetworkInfo(mNetworkInfo);
    }

    /**
     * Fetch LinkProperties for the network
     */
    @Override
    public LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    /**
     * A capability is an Integer/String pair, the capabilities
     * are defined in the class LinkSocket#Key.
     *
     * @return a copy of this connections capabilities, may be empty but never null.
     */
    @Override
    public LinkCapabilities getLinkCapabilities() {
        return new LinkCapabilities(mLinkCapabilities);
    }

    /**
     * Return link info
     * @return an object of type WifiLinkQualityInfo
     */
    @Override
    public LinkQualityInfo getLinkQualityInfo() {
        if (mNetworkInfo == null) {
            // no data available yet; just return
            return null;
        }

        WifiLinkQualityInfo li = new WifiLinkQualityInfo();
        li.setNetworkType(mNetworkInfo.getType());

        synchronized(mSamplingDataTracker.mSamplingDataLock) {
            mSamplingDataTracker.setCommonLinkQualityInfoFields(li);
            li.setTxGood(mSamplingDataTracker.getSampledTxPacketCount());
            li.setTxBad(mSamplingDataTracker.getSampledTxPacketErrorCount());
        }

        // li.setTheoreticalRxBandwidth(??);
        // li.setTheoreticalTxBandwidth(??);

        if (mWifiInfo != null) {
            li.setBssid(mWifiInfo.getBSSID());

            int rssi = mWifiInfo.getRssi();
            li.setRssi(rssi);

            li.setNormalizedSignalStrength(mWifiManager.calculateSignalLevel(rssi,
                    LinkQualityInfo.NORMALIZED_SIGNAL_STRENGTH_RANGE));
        }

        return li;
    }

    /**
     * Check if default route is set
     */
    public boolean isDefaultRouteSet() {
        return mDefaultRouteSet.get();
    }

    /**
     * Set a flag indicating default route is set for the network
     */
    public void defaultRouteSet(boolean enabled) {
        mDefaultRouteSet.set(enabled);
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.wifi";
    }

    private class WifiStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);

                mLinkProperties = intent.getParcelableExtra(
                        WifiManager.EXTRA_LINK_PROPERTIES);
                if (mLinkProperties == null) {
                    mLinkProperties = new LinkProperties();
                }
                mLinkCapabilities = intent.getParcelableExtra(
                        WifiManager.EXTRA_LINK_CAPABILITIES);
                if (mLinkCapabilities == null) {
                    mLinkCapabilities = new LinkCapabilities();
                }

                mWifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                // don't want to send redundant state messages
                // but send portal check detailed state notice
                NetworkInfo.State state = mNetworkInfo.getState();
                if (mLastState == state &&
                        mNetworkInfo.getDetailedState() != DetailedState.CAPTIVE_PORTAL_CHECK) {
                    return;
                } else {
                    mLastState = state;
                    /* lets not sample traffic data across state changes */
                    mSamplingDataTracker.resetSamplingData();
                }

                Message msg = mCsHandler.obtainMessage(EVENT_STATE_CHANGED,
                        new NetworkInfo(mNetworkInfo));
                msg.sendToTarget();
            } else if (intent.getAction().equals(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION)) {
                mLinkProperties = intent.getParcelableExtra(WifiManager.EXTRA_LINK_PROPERTIES);
                Message msg = mCsHandler.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                msg.sendToTarget();
            }
        }
    }

    public void setDependencyMet(boolean met) {
        // not supported on this network
    }

    @Override
    public void addStackedLink(LinkProperties link) {
        mLinkProperties.addStackedLink(link);
    }

    @Override
    public void removeStackedLink(LinkProperties link) {
        mLinkProperties.removeStackedLink(link);
    }

    @Override
    public void supplyMessenger(Messenger messenger) {
        // not supported on this network
    }

    @Override
    public void startSampling(SamplingDataTracker.SamplingSnapshot s) {
        mSamplingDataTracker.startSampling(s);
    }

    @Override
    public void stopSampling(SamplingDataTracker.SamplingSnapshot s) {
        mSamplingDataTracker.stopSampling(s);
    }
}

