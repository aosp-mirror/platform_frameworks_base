/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.PhoneStateListener;
import android.telephony.PreciseCallState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.location.GpsNetInitiatedHandler;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Handles network connection requests and network state change updates for AGPS data download.
 */
class GnssNetworkConnectivityHandler {
    static final String TAG = "GnssNetworkConnectivityHandler";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    // for mAGpsDataConnectionState
    private static final int AGPS_DATA_CONNECTION_CLOSED = 0;
    private static final int AGPS_DATA_CONNECTION_OPENING = 1;
    private static final int AGPS_DATA_CONNECTION_OPEN = 2;

    // these need to match AGnssStatusValue enum in IAGnssCallback.hal
    /** AGPS status event values. */
    private static final int GPS_REQUEST_AGPS_DATA_CONN = 1;
    private static final int GPS_RELEASE_AGPS_DATA_CONN = 2;
    private static final int GPS_AGPS_DATA_CONNECTED = 3;
    private static final int GPS_AGPS_DATA_CONN_DONE = 4;
    private static final int GPS_AGPS_DATA_CONN_FAILED = 5;

    // these must match the ApnIpType enum in IAGnss.hal
    private static final int APN_INVALID = 0;
    private static final int APN_IPV4 = 1;
    private static final int APN_IPV6 = 2;
    private static final int APN_IPV4V6 = 3;

    // these must match the NetworkCapability enum flags in IAGnssRil.hal
    private static final int AGNSS_NET_CAPABILITY_NOT_METERED = 1 << 0;
    private static final int AGNSS_NET_CAPABILITY_NOT_ROAMING = 1 << 1;

    // these need to match AGnssType enum in IAGnssCallback.hal
    public static final int AGPS_TYPE_SUPL = 1;
    public static final int AGPS_TYPE_C2K = 2;
    private static final int AGPS_TYPE_EIMS = 3;
    private static final int AGPS_TYPE_IMS = 4;

    // Default time limit in milliseconds for the ConnectivityManager to find a suitable
    // network with SUPL connectivity or report an error.
    private static final int SUPL_NETWORK_REQUEST_TIMEOUT_MILLIS = 20 * 1000;

    private static final int HASH_MAP_INITIAL_CAPACITY_TO_TRACK_CONNECTED_NETWORKS = 5;

    // Keeps track of networks and their state as notified by the network request callbacks.
    // Limit initial capacity to 5 as the number of connected networks will likely be small.
    // NOTE: Must be accessed/modified only through the mHandler thread.
    private HashMap<Network, NetworkAttributes> mAvailableNetworkAttributes =
            new HashMap<>(HASH_MAP_INITIAL_CAPACITY_TO_TRACK_CONNECTED_NETWORKS);

    // Phone State Listeners to track all the active sub IDs
    private HashMap<Integer, SubIdPhoneStateListener> mPhoneStateListeners;

    private final ConnectivityManager mConnMgr;

    private final Handler mHandler;
    private final GnssNetworkListener mGnssNetworkListener;

    private int mAGpsDataConnectionState;
    private InetAddress mAGpsDataConnectionIpAddr;
    private int mAGpsType;
    private int mActiveSubId = -1;
    private final GpsNetInitiatedHandler mNiHandler;


    private final Context mContext;

    // Wakelocks
    private static final String WAKELOCK_KEY = "GnssNetworkConnectivityHandler";
    private static final long WAKELOCK_TIMEOUT_MILLIS = 60 * 1000;
    private final PowerManager.WakeLock mWakeLock;

    /**
     * Network attributes needed when updating HAL about network connectivity status changes.
     */
    private static class NetworkAttributes {
        private NetworkCapabilities mCapabilities;
        private String mApn;
        private int mType = ConnectivityManager.TYPE_NONE;

        /**
         * Returns true if the capabilities that we pass on to HAL change between {@curCapabilities}
         * and {@code newCapabilities}.
         */
        private static boolean hasCapabilitiesChanged(NetworkCapabilities curCapabilities,
                NetworkCapabilities newCapabilities) {
            if (curCapabilities == null || newCapabilities == null) {
                return true;
            }

            // Monitor for roaming and metered capability changes.
            return hasCapabilityChanged(curCapabilities, newCapabilities,
                    NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                    || hasCapabilityChanged(curCapabilities, newCapabilities,
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        private static boolean hasCapabilityChanged(NetworkCapabilities curCapabilities,
                NetworkCapabilities newCapabilities, int capability) {
            return curCapabilities.hasCapability(capability)
                    != newCapabilities.hasCapability(capability);
        }

        private static short getCapabilityFlags(NetworkCapabilities capabilities) {
            short capabilityFlags = 0;
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                capabilityFlags |= AGNSS_NET_CAPABILITY_NOT_ROAMING;
            }
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                capabilityFlags |= AGNSS_NET_CAPABILITY_NOT_METERED;
            }
            return capabilityFlags;
        }
    }

    /**
     * Callback used to listen for data connectivity changes.
     */
    private ConnectivityManager.NetworkCallback mNetworkConnectivityCallback;

    /**
     * Callback used to listen for availability of a requested SUPL connection.
     * It is kept as a separate instance from {@link #mNetworkConnectivityCallback} to be able to
     * manage the registration/un-registration lifetimes separately.
     */
    private ConnectivityManager.NetworkCallback mSuplConnectivityCallback;

    /**
     * Interface to listen for network availability changes.
     */
    interface GnssNetworkListener {
        void onNetworkAvailable();
    }

    GnssNetworkConnectivityHandler(Context context,
            GnssNetworkListener gnssNetworkListener,
            Looper looper,
            GpsNetInitiatedHandler niHandler) {
        mContext = context;
        mGnssNetworkListener = gnssNetworkListener;

        SubscriptionManager subManager = mContext.getSystemService(SubscriptionManager.class);
        if (subManager != null) {
            subManager.addOnSubscriptionsChangedListener(mOnSubscriptionsChangeListener);
        }

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);

        mHandler = new Handler(looper);
        mNiHandler = niHandler;
        mConnMgr = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mSuplConnectivityCallback = null;
    }

    /**
     * SubId Phone State Listener is used cache the last active Sub ID when a call is made,
     * which will be used during an emergency call to set the Network Specifier to the particular
     * sub when an emergency supl connection is requested
     */
    private final class SubIdPhoneStateListener extends PhoneStateListener {
        private Integer mSubId;
        SubIdPhoneStateListener(Integer subId) {
            mSubId = subId;
        }
        @Override
        public void onPreciseCallStateChanged(PreciseCallState state) {
            if (PreciseCallState.PRECISE_CALL_STATE_ACTIVE == state.getForegroundCallState()
                    || PreciseCallState.PRECISE_CALL_STATE_DIALING
                    == state.getForegroundCallState()) {
                mActiveSubId = mSubId;
                if (DEBUG) Log.d(TAG, "mActiveSubId: " + mActiveSubId);
            }
        }
    };

    /**
     * Subscription Changed Listener is used to get all active subscriptions and create a
     * Phone State Listener for each Sub ID that we find in the active subscription list
     */
    private final SubscriptionManager.OnSubscriptionsChangedListener mOnSubscriptionsChangeListener
            = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            if (mPhoneStateListeners == null) {
                // Capacity=2 Load-Factor=1.0, as typically no more than 2 SIMs
                mPhoneStateListeners = new HashMap<Integer, SubIdPhoneStateListener>(2,1);
            }
            SubscriptionManager subManager = mContext.getSystemService(SubscriptionManager.class);
            TelephonyManager telManager = mContext.getSystemService(TelephonyManager.class);
            if (subManager != null && telManager != null) {
                List<SubscriptionInfo> subscriptionInfoList =
                        subManager.getActiveSubscriptionInfoList();
                HashSet<Integer> activeSubIds = new HashSet<Integer>();
                if (subscriptionInfoList != null) {
                    if (DEBUG) Log.d(TAG, "Active Sub List size: " + subscriptionInfoList.size());
                    // populate phone state listeners with all new active subs
                    for (SubscriptionInfo subInfo : subscriptionInfoList) {
                        activeSubIds.add(subInfo.getSubscriptionId());
                        if (!mPhoneStateListeners.containsKey(subInfo.getSubscriptionId())) {
                            TelephonyManager subIdTelManager =
                                    telManager.createForSubscriptionId(subInfo.getSubscriptionId());
                            if (subIdTelManager != null) {
                                if (DEBUG) Log.d(TAG, "Listener sub" + subInfo.getSubscriptionId());
                                SubIdPhoneStateListener subIdPhoneStateListener =
                                        new SubIdPhoneStateListener(subInfo.getSubscriptionId());
                                mPhoneStateListeners.put(subInfo.getSubscriptionId(),
                                        subIdPhoneStateListener);
                                subIdTelManager.listen(subIdPhoneStateListener,
                                        PhoneStateListener.LISTEN_PRECISE_CALL_STATE);
                            }
                        }
                    }
                }
                // clean up phone state listeners than no longer have active subs
                Iterator<Map.Entry<Integer, SubIdPhoneStateListener> > iterator =
                        mPhoneStateListeners.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Integer, SubIdPhoneStateListener> element = iterator.next();
                    if (!activeSubIds.contains(element.getKey())) {
                        TelephonyManager subIdTelManager =
                                telManager.createForSubscriptionId(element.getKey());
                        if (subIdTelManager != null) {
                            if (DEBUG) Log.d(TAG, "unregister listener sub " + element.getKey());
                            subIdTelManager.listen(element.getValue(),
                                                   PhoneStateListener.LISTEN_NONE);
                            // removes the element from mPhoneStateListeners
                            iterator.remove();
                        } else {
                            Log.e(TAG, "Telephony Manager for Sub " + element.getKey() + " null");
                        }
                    }
                }
                // clean up cached active phone call sub if it is no longer an active sub
                if (!activeSubIds.contains(mActiveSubId)) {
                    mActiveSubId = -1;
                }
            }
        }
    };

    void registerNetworkCallbacks() {
        // register for connectivity change events.
        NetworkRequest.Builder networkRequestBuilder = new NetworkRequest.Builder();
        networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        networkRequestBuilder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        NetworkRequest networkRequest = networkRequestBuilder.build();
        mNetworkConnectivityCallback = createNetworkConnectivityCallback();
        mConnMgr.registerNetworkCallback(networkRequest, mNetworkConnectivityCallback, mHandler);
    }

    void unregisterNetworkCallbacks() {
        mConnMgr.unregisterNetworkCallback(mNetworkConnectivityCallback);
        mNetworkConnectivityCallback = null;
    }

    /**
     * @return {@code true} if there is a data network available for outgoing connections,
     * {@code false} otherwise.
     */
    boolean isDataNetworkConnected() {
        NetworkInfo activeNetworkInfo = mConnMgr.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    /**
     * Returns the active Sub ID for emergency SUPL connection.
     */
    int getActiveSubId() {
        return mActiveSubId;
    }

    /**
     * Called from native code to update AGPS connection status, or to request or release a SUPL
     * connection.
     *
     * <p>Note: {@code suplIpAddr} parameter is not present from IAGnssCallback.hal@2.0 onwards
     * and is set to {@code null}.
     */
    void onReportAGpsStatus(int agpsType, int agpsStatus, byte[] suplIpAddr) {
        if (DEBUG) Log.d(TAG, "AGPS_DATA_CONNECTION: " + agpsDataConnStatusAsString(agpsStatus));
        switch (agpsStatus) {
            case GPS_REQUEST_AGPS_DATA_CONN:
                runOnHandler(() -> handleRequestSuplConnection(agpsType, suplIpAddr));
                break;
            case GPS_RELEASE_AGPS_DATA_CONN:
                runOnHandler(() -> handleReleaseSuplConnection(GPS_RELEASE_AGPS_DATA_CONN));
                break;
            case GPS_AGPS_DATA_CONNECTED:
            case GPS_AGPS_DATA_CONN_DONE:
            case GPS_AGPS_DATA_CONN_FAILED:
                break;
            default:
                Log.w(TAG, "Received unknown AGPS status: " + agpsStatus);
        }
    }

    private ConnectivityManager.NetworkCallback createNetworkConnectivityCallback() {
        return new ConnectivityManager.NetworkCallback() {
            // Used to filter out network capabilities changes that we are not interested in.
            // NOTE: Not using a ConcurrentHashMap and also not using locking around updates
            //       and access to the map object because it is all done inside the same
            //       handler thread invoking the callback methods.
            private HashMap<Network, NetworkCapabilities>
                    mAvailableNetworkCapabilities = new HashMap<>(
                    HASH_MAP_INITIAL_CAPACITY_TO_TRACK_CONNECTED_NETWORKS);

            @Override
            public void onCapabilitiesChanged(Network network,
                    NetworkCapabilities capabilities) {
                // This callback is invoked for any change in the network capabilities including
                // initial availability, and changes while still available. Only process if the
                // capabilities that we pass on to HAL change.
                if (!NetworkAttributes.hasCapabilitiesChanged(
                        mAvailableNetworkCapabilities.get(network), capabilities)) {
                    if (VERBOSE) {
                        Log.v(TAG, "Relevant network capabilities unchanged. Capabilities: "
                                + capabilities);
                    }
                    return;
                }

                mAvailableNetworkCapabilities.put(network, capabilities);
                if (DEBUG) {
                    Log.d(TAG, "Network connected/capabilities updated. Available networks count: "
                            + mAvailableNetworkCapabilities.size());
                }

                mGnssNetworkListener.onNetworkAvailable();

                // Always on, notify HAL so it can get data it needs
                handleUpdateNetworkState(network, true, capabilities);
            }

            @Override
            public void onLost(Network network) {
                if (mAvailableNetworkCapabilities.remove(network) == null) {
                    Log.w(TAG, "Incorrectly received network callback onLost() before"
                            + " onCapabilitiesChanged() for network: " + network);
                    return;
                }

                Log.i(TAG, "Network connection lost. Available networks count: "
                        + mAvailableNetworkCapabilities.size());
                handleUpdateNetworkState(network, false, null);
            }
        };
    }

    private ConnectivityManager.NetworkCallback createSuplConnectivityCallback() {
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                if (DEBUG) Log.d(TAG, "SUPL network connection available.");
                // Specific to a change to a SUPL enabled network becoming ready
                handleSuplConnectionAvailable(network, linkProperties);
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "SUPL network connection lost.");
                handleReleaseSuplConnection(GPS_RELEASE_AGPS_DATA_CONN);
            }

            @Override
            public void onUnavailable() {
                Log.i(TAG, "SUPL network connection request timed out.");
                // Could not setup the connection to the network in the specified time duration.
                handleReleaseSuplConnection(GPS_AGPS_DATA_CONN_FAILED);
            }
        };
    }

    private void runOnHandler(Runnable event) {
        // hold a wake lock until this message is delivered
        // note that this assumes the message will not be removed from the queue before
        // it is handled (otherwise the wake lock would be leaked).
        mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
        if (!mHandler.post(runEventAndReleaseWakeLock(event))) {
            mWakeLock.release();
        }
    }

    private Runnable runEventAndReleaseWakeLock(Runnable event) {
        return () -> {
            try {
                event.run();
            } finally {
                mWakeLock.release();
            }
        };
    }

    private void handleUpdateNetworkState(Network network, boolean isConnected,
            NetworkCapabilities capabilities) {
        boolean networkAvailable = false;
        TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
        if (telephonyManager != null) {
            networkAvailable = isConnected && telephonyManager.getDataEnabled();
        }
        NetworkAttributes networkAttributes = updateTrackedNetworksState(isConnected, network,
                capabilities);
        String apn = networkAttributes.mApn;
        int type = networkAttributes.mType;
        // When isConnected is false, capabilities argument is null. So, use last received
        // capabilities.
        capabilities = networkAttributes.mCapabilities;
        Log.i(TAG, String.format(
                "updateNetworkState, state=%s, connected=%s, network=%s, capabilityFlags=%d"
                        + ", availableNetworkCount: %d",
                agpsDataConnStateAsString(),
                isConnected,
                network,
                NetworkAttributes.getCapabilityFlags(capabilities),
                mAvailableNetworkAttributes.size()));

        if (native_is_agps_ril_supported()) {
            native_update_network_state(
                    isConnected,
                    type,
                    !capabilities.hasTransport(
                            NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING), /* isRoaming */
                    networkAvailable,
                    apn != null ? apn : "",
                    network.getNetworkHandle(),
                    NetworkAttributes.getCapabilityFlags(capabilities));
        } else if (DEBUG) {
            Log.d(TAG, "Skipped network state update because GPS HAL AGPS-RIL is not  supported");
        }
    }

    private NetworkAttributes updateTrackedNetworksState(boolean isConnected, Network network,
            NetworkCapabilities capabilities) {
        if (!isConnected) {
            // Connection lost event. So, remove it from tracked networks.
            return mAvailableNetworkAttributes.remove(network);
        }

        NetworkAttributes networkAttributes = mAvailableNetworkAttributes.get(network);
        if (networkAttributes != null) {
            // Capabilities updated event for the connected network.
            networkAttributes.mCapabilities = capabilities;
            return networkAttributes;
        }

        // Initial capabilities event (equivalent to connection available event).
        networkAttributes = new NetworkAttributes();
        networkAttributes.mCapabilities = capabilities;

        // TODO: The synchronous method ConnectivityManager.getNetworkInfo() should not be called
        //       inside the asynchronous ConnectivityManager.NetworkCallback methods.
        NetworkInfo info = mConnMgr.getNetworkInfo(network);
        if (info != null) {
            networkAttributes.mApn = info.getExtraInfo();
            networkAttributes.mType = info.getType();
        }

        // Start tracking this network for connection status updates.
        mAvailableNetworkAttributes.put(network, networkAttributes);
        return networkAttributes;
    }

    private void handleSuplConnectionAvailable(Network network, LinkProperties linkProperties) {
        // TODO: The synchronous method ConnectivityManager.getNetworkInfo() should not be called
        //       inside the asynchronous ConnectivityManager.NetworkCallback methods.
        NetworkInfo info = mConnMgr.getNetworkInfo(network);
        String apn = null;
        if (info != null) {
            apn = info.getExtraInfo();
        }

        if (DEBUG) {
            String message = String.format(
                    "handleSuplConnectionAvailable: state=%s, suplNetwork=%s, info=%s",
                    agpsDataConnStateAsString(),
                    network,
                    info);
            Log.d(TAG, message);
        }

        if (mAGpsDataConnectionState == AGPS_DATA_CONNECTION_OPENING) {
            if (apn == null) {
                // assign a placeholder value in the case of C2K as otherwise we will have a runtime
                // exception in the following call to native_agps_data_conn_open
                apn = "dummy-apn";
            }

            // Setting route to host is needed for GNSS HAL implementations earlier than
            // @2.0::IAgnssCallback. The HAL @2.0::IAgnssCallback.agnssStatusCb() method does
            // not require setting route to SUPL host and hence does not provide an IP address.
            if (mAGpsDataConnectionIpAddr != null) {
                setRouting();
            }

            int apnIpType = getLinkIpType(linkProperties);
            if (DEBUG) {
                String message = String.format(
                        "native_agps_data_conn_open: mAgpsApn=%s, mApnIpType=%s",
                        apn,
                        apnIpType);
                Log.d(TAG, message);
            }
            native_agps_data_conn_open(network.getNetworkHandle(), apn, apnIpType);
            mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPEN;
        }
    }

    private void handleRequestSuplConnection(int agpsType, byte[] suplIpAddr) {
        mAGpsDataConnectionIpAddr = null;
        mAGpsType = agpsType;
        if (suplIpAddr != null) {
            if (VERBOSE) Log.v(TAG, "Received SUPL IP addr[]: " + Arrays.toString(suplIpAddr));
            try {
                mAGpsDataConnectionIpAddr = InetAddress.getByAddress(suplIpAddr);
                if (DEBUG) Log.d(TAG, "IP address converted to: " + mAGpsDataConnectionIpAddr);
            } catch (UnknownHostException e) {
                Log.e(TAG, "Bad IP Address: " + Arrays.toString(suplIpAddr), e);
            }
        }

        if (DEBUG) {
            String message = String.format(
                    "requestSuplConnection, state=%s, agpsType=%s, address=%s",
                    agpsDataConnStateAsString(),
                    agpsTypeAsString(agpsType),
                    mAGpsDataConnectionIpAddr);
            Log.d(TAG, message);
        }

        if (mAGpsDataConnectionState != AGPS_DATA_CONNECTION_CLOSED) {
            return;
        }
        mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPENING;

        // The transport type must be set to NetworkCapabilities.TRANSPORT_CELLULAR for the
        // deprecated requestRouteToHostAddress() method in ConnectivityService to work for
        // pre-gnss@2.0 devices.
        NetworkRequest.Builder networkRequestBuilder = new NetworkRequest.Builder();
        networkRequestBuilder.addCapability(getNetworkCapability(mAGpsType));
        networkRequestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        // During an emergency call, and when we have cached the Active Sub Id, we set the
        // Network Specifier so that the network request goes to the correct Sub Id
        if (mNiHandler.getInEmergency() && mActiveSubId >= 0) {
            if (DEBUG) Log.d(TAG, "Adding Network Specifier: " + Integer.toString(mActiveSubId));
            networkRequestBuilder.setNetworkSpecifier(Integer.toString(mActiveSubId));
            networkRequestBuilder.removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
        }
        NetworkRequest networkRequest = networkRequestBuilder.build();
        // Make sure we only have a single request.
        if (mSuplConnectivityCallback != null) {
            mConnMgr.unregisterNetworkCallback(mSuplConnectivityCallback);
        }
        mSuplConnectivityCallback = createSuplConnectivityCallback();
        try {
            mConnMgr.requestNetwork(
                    networkRequest,
                    mSuplConnectivityCallback,
                    mHandler,
                    SUPL_NETWORK_REQUEST_TIMEOUT_MILLIS);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to request network.", e);
            mSuplConnectivityCallback = null;
            handleReleaseSuplConnection(GPS_AGPS_DATA_CONN_FAILED);
        }
    }

    private int getNetworkCapability(int agpsType) {
        switch (agpsType) {
            case AGPS_TYPE_C2K:
            case AGPS_TYPE_SUPL:
                return NetworkCapabilities.NET_CAPABILITY_SUPL;
            case AGPS_TYPE_EIMS:
                return NetworkCapabilities.NET_CAPABILITY_EIMS;
            case AGPS_TYPE_IMS:
                return NetworkCapabilities.NET_CAPABILITY_IMS;
            default:
                throw new IllegalArgumentException("agpsType: " + agpsType);
        }
    }

    private void handleReleaseSuplConnection(int agpsDataConnStatus) {
        if (DEBUG) {
            String message = String.format(
                    "releaseSuplConnection, state=%s, status=%s",
                    agpsDataConnStateAsString(),
                    agpsDataConnStatusAsString(agpsDataConnStatus));
            Log.d(TAG, message);
        }

        if (mAGpsDataConnectionState == AGPS_DATA_CONNECTION_CLOSED) {
            return;
        }

        mAGpsDataConnectionState = AGPS_DATA_CONNECTION_CLOSED;
        if (mSuplConnectivityCallback != null) {
            mConnMgr.unregisterNetworkCallback(mSuplConnectivityCallback);
            mSuplConnectivityCallback = null;
        }
        switch (agpsDataConnStatus) {
            case GPS_AGPS_DATA_CONN_FAILED:
                native_agps_data_conn_failed();
                break;
            case GPS_RELEASE_AGPS_DATA_CONN:
                native_agps_data_conn_closed();
                break;
            default:
                Log.e(TAG, "Invalid status to release SUPL connection: " + agpsDataConnStatus);
        }
    }

    // TODO: Delete this method when all devices upgrade to HAL @2.0::IAGnssCallback
    //       interface which does not require setting route to host.
    private void setRouting() {
        boolean result = mConnMgr.requestRouteToHostAddress(
                ConnectivityManager.TYPE_MOBILE_SUPL,
                mAGpsDataConnectionIpAddr);

        if (!result) {
            Log.e(TAG, "Error requesting route to host: " + mAGpsDataConnectionIpAddr);
        } else if (DEBUG) {
            Log.d(TAG, "Successfully requested route to host: " + mAGpsDataConnectionIpAddr);
        }
    }

    /**
     * Ensures the calling function is running in the thread associated with {@link #mHandler}.
     */
    private void ensureInHandlerThread() {
        if (mHandler != null && Looper.myLooper() == mHandler.getLooper()) {
            return;
        }
        throw new IllegalStateException("This method must run on the Handler thread.");
    }

    /**
     * @return A string representing the current state stored in {@link #mAGpsDataConnectionState}.
     */
    private String agpsDataConnStateAsString() {
        switch (mAGpsDataConnectionState) {
            case AGPS_DATA_CONNECTION_CLOSED:
                return "CLOSED";
            case AGPS_DATA_CONNECTION_OPEN:
                return "OPEN";
            case AGPS_DATA_CONNECTION_OPENING:
                return "OPENING";
            default:
                return "<Unknown>(" + mAGpsDataConnectionState + ")";
        }
    }

    /**
     * @return A string representing the given GPS_AGPS_DATA status.
     */
    private String agpsDataConnStatusAsString(int agpsDataConnStatus) {
        switch (agpsDataConnStatus) {
            case GPS_AGPS_DATA_CONNECTED:
                return "CONNECTED";
            case GPS_AGPS_DATA_CONN_DONE:
                return "DONE";
            case GPS_AGPS_DATA_CONN_FAILED:
                return "FAILED";
            case GPS_RELEASE_AGPS_DATA_CONN:
                return "RELEASE";
            case GPS_REQUEST_AGPS_DATA_CONN:
                return "REQUEST";
            default:
                return "<Unknown>(" + agpsDataConnStatus + ")";
        }
    }

    private String agpsTypeAsString(int agpsType) {
        switch (agpsType) {
            case AGPS_TYPE_SUPL:
                return "SUPL";
            case AGPS_TYPE_C2K:
                return "C2K";
            case AGPS_TYPE_EIMS:
                return "EIMS";
            case AGPS_TYPE_IMS:
                return "IMS";
            default:
                return "<Unknown>(" + agpsType + ")";
        }
    }

    private int getLinkIpType(LinkProperties linkProperties) {
        ensureInHandlerThread();
        boolean isIPv4 = false;
        boolean isIPv6 = false;

        List<LinkAddress> linkAddresses = linkProperties.getLinkAddresses();
        for (LinkAddress linkAddress : linkAddresses) {
            InetAddress inetAddress = linkAddress.getAddress();
            if (inetAddress instanceof Inet4Address) {
                isIPv4 = true;
            } else if (inetAddress instanceof Inet6Address) {
                isIPv6 = true;
            }
            if (DEBUG) Log.d(TAG, "LinkAddress : " + inetAddress.toString());
        }

        if (isIPv4 && isIPv6) {
            return APN_IPV4V6;
        }
        if (isIPv4) {
            return APN_IPV4;
        }
        if (isIPv6) {
            return APN_IPV6;
        }
        return APN_INVALID;
    }

    protected boolean isNativeAgpsRilSupported() {
        return native_is_agps_ril_supported();
    }

    // AGPS support
    private native void native_agps_data_conn_open(long networkHandle, String apn, int apnIpType);

    private native void native_agps_data_conn_closed();

    private native void native_agps_data_conn_failed();

    // AGPS ril support
    private static native boolean native_is_agps_ril_supported();

    private native void native_update_network_state(boolean connected, int type, boolean roaming,
            boolean available, String apn, long networkHandle, short capabilities);
}
