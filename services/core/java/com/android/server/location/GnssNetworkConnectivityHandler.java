/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.location;

import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Telephony.Carriers;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

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

    // Default time limit in milliseconds for the ConnectivityManager to find a suitable
    // network with SUPL connectivity or report an error.
    private static final int SUPL_NETWORK_REQUEST_TIMEOUT_MILLIS = 10 * 1000;

    private static final int HASH_MAP_INITIAL_CAPACITY_TO_TRACK_CONNECTED_NETWORKS = 5;

    // keeps track of networks and their state as notified by the network request callbacks.
    // Limit initial capacity to 5 as the number of connected networks will likely be small.
    private ConcurrentHashMap<Network, NetworkAttributes> mAvailableNetworkAttributes =
            new ConcurrentHashMap<>(HASH_MAP_INITIAL_CAPACITY_TO_TRACK_CONNECTED_NETWORKS);

    private final ConnectivityManager mConnMgr;

    private final Handler mHandler;
    private final GnssNetworkListener mGnssNetworkListener;

    private int mAGpsDataConnectionState;
    private InetAddress mAGpsDataConnectionIpAddr;

    private final Context mContext;

    // Wakelocks
    private static final String WAKELOCK_KEY = "GnssNetworkConnectivityHandler";
    private static final long WAKELOCK_TIMEOUT_MILLIS = 60 * 1000;
    private final PowerManager.WakeLock mWakeLock;

    /**
     * Network attributes needed when updating HAL about network connectivity status changes.
     */
    private static class NetworkAttributes {
        NetworkCapabilities mCapabilities;
        String mApn;
        int mType = ConnectivityManager.TYPE_NONE;

        /**
         * Returns true if the capabilities that we pass on to HAL change between {@curCapabilities}
         * and {@code newCapabilities}.
         */
        static boolean hasCapabilitiesChanged(NetworkCapabilities curCapabilities,
                NetworkCapabilities newCapabilities) {
            if (curCapabilities == null || newCapabilities == null) {
                return true;
            }

            return curCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                    != newCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
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
    public interface GnssNetworkListener {
        void onNetworkAvailable();
    }

    GnssNetworkConnectivityHandler(Context context,
            GnssNetworkListener gnssNetworkListener,
            Looper looper) {
        mContext = context;
        mGnssNetworkListener = gnssNetworkListener;

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);

        mHandler = new Handler(looper);
        mConnMgr = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mSuplConnectivityCallback = createSuplConnectivityCallback();
    }

    public void registerNetworkCallbacks() {
        mAvailableNetworkAttributes.clear();
        if (mNetworkConnectivityCallback != null) {
            mConnMgr.unregisterNetworkCallback(mNetworkConnectivityCallback);
        }

        // register for connectivity change events.
        NetworkRequest.Builder networkRequestBuilder = new NetworkRequest.Builder();
        networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        networkRequestBuilder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        NetworkRequest networkRequest = networkRequestBuilder.build();
        mNetworkConnectivityCallback = createNetworkConnectivityCallback();
        mConnMgr.registerNetworkCallback(networkRequest, mNetworkConnectivityCallback);
    }

    /**
     * @return {@code true} if there is a data network available for outgoing connections,
     * {@code false} otherwise.
     */
    public boolean isDataNetworkConnected() {
        NetworkInfo activeNetworkInfo = mConnMgr.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    /**
     * called from native code to update AGPS status
     */
    public void onReportAGpsStatus(int type, int status, byte[] ipaddr) {
        switch (status) {
            case GPS_REQUEST_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_REQUEST_AGPS_DATA_CONN");
                Log.v(TAG, "Received SUPL IP addr[]: " + Arrays.toString(ipaddr));
                InetAddress connectionIpAddress = null;
                if (ipaddr != null) {
                    try {
                        connectionIpAddress = InetAddress.getByAddress(ipaddr);
                        if (DEBUG) Log.d(TAG, "IP address converted to: " + connectionIpAddress);
                    } catch (UnknownHostException e) {
                        Log.e(TAG, "Bad IP Address: " + ipaddr, e);
                    }
                }
                requestSuplConnection(connectionIpAddress);
                break;
            case GPS_RELEASE_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_RELEASE_AGPS_DATA_CONN");
                releaseSuplConnection(GPS_RELEASE_AGPS_DATA_CONN);
                break;
            case GPS_AGPS_DATA_CONNECTED:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONNECTED");
                break;
            case GPS_AGPS_DATA_CONN_DONE:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONN_DONE");
                break;
            case GPS_AGPS_DATA_CONN_FAILED:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONN_FAILED");
                break;
            default:
                if (DEBUG) Log.d(TAG, "Received Unknown AGPS status: " + status);
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
                updateNetworkState(network, true, capabilities);
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
                updateNetworkState(network, false, null);
            }
        };
    }

    private ConnectivityManager.NetworkCallback createSuplConnectivityCallback() {
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (DEBUG) Log.d(TAG, "SUPL network connection available.");
                // Specific to a change to a SUPL enabled network becoming ready
                suplConnectionAvailable(network);
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "SUPL network connection lost.");
                releaseSuplConnection(GPS_RELEASE_AGPS_DATA_CONN);
            }

            @Override
            public void onUnavailable() {
                Log.i(TAG, "SUPL network connection request timed out.");
                // Could not setup the connection to the network in the specified time duration.
                releaseSuplConnection(GPS_AGPS_DATA_CONN_FAILED);
            }
        };
    }

    private void requestSuplConnection(InetAddress inetAddress) {
        postEvent(() -> handleRequestSuplConnection(inetAddress));
    }

    private void suplConnectionAvailable(Network network) {
        postEvent(() -> handleSuplConnectionAvailable(network));
    }

    private void releaseSuplConnection(int connStatus) {
        postEvent(() -> handleReleaseSuplConnection(connStatus));
    }

    private void updateNetworkState(Network network, boolean isConnected,
            NetworkCapabilities capabilities) {
        postEvent(() -> handleUpdateNetworkState(network, isConnected, capabilities));
    }

    private void postEvent(Runnable event) {
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
        boolean networkAvailable = isConnected && TelephonyManager.getDefault().getDataEnabled();
        NetworkAttributes networkAttributes = updateTrackedNetworksState(isConnected, network,
                capabilities);
        String apnName = networkAttributes.mApn;
        int type = networkAttributes.mType;
        // When isConnected is false, capabilities argument is null. So, use last received
        // capabilities.
        capabilities = networkAttributes.mCapabilities;
        boolean isRoaming = !capabilities.hasTransport(
                NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);

        Log.i(TAG, String.format(
                "updateNetworkState, state=%s, connected=%s, network=%s, capabilities=%s"
                        + ", availableNetworkCount: %d",
                agpsDataConnStateAsString(),
                isConnected,
                network,
                capabilities,
                mAvailableNetworkAttributes.size()));

        if (native_is_agps_ril_supported()) {
            String defaultApn = getSelectedApn();
            if (defaultApn == null) {
                defaultApn = "dummy-apn";
            }

            native_update_network_state(
                    isConnected,
                    type,
                    isRoaming,
                    networkAvailable,
                    apnName,
                    defaultApn);
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

        // TODO(b/119278134): The synchronous method ConnectivityManager.getNetworkInfo() must
        // not be called inside the asynchronous ConnectivityManager.NetworkCallback methods.
        NetworkInfo info = mConnMgr.getNetworkInfo(network);
        if (info != null) {
            networkAttributes.mApn = info.getExtraInfo();
            networkAttributes.mType = info.getType();
        }

        // Start tracking this network for connection status updates.
        mAvailableNetworkAttributes.put(network, networkAttributes);
        return networkAttributes;
    }

    private void handleSuplConnectionAvailable(Network network) {
        // TODO(b/119278134): The synchronous method ConnectivityManager.getNetworkInfo() must
        // not be called inside the asynchronous ConnectivityManager.NetworkCallback methods.
        NetworkInfo info = mConnMgr.getNetworkInfo(network);
        String apnName = null;
        if (info != null) {
            apnName = info.getExtraInfo();
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
            if (apnName == null) {
                // assign a dummy value in the case of C2K as otherwise we will have a runtime
                // exception in the following call to native_agps_data_conn_open
                apnName = "dummy-apn";
            }
            int apnIpType = getApnIpType(apnName);
            setRouting();
            if (DEBUG) {
                String message = String.format(
                        "native_agps_data_conn_open: mAgpsApn=%s, mApnIpType=%s",
                        apnName,
                        apnIpType);
                Log.d(TAG, message);
            }
            native_agps_data_conn_open(apnName, apnIpType);
            mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPEN;
        }
    }

    private void handleRequestSuplConnection(InetAddress address) {
        if (DEBUG) {
            String message = String.format(
                    "requestSuplConnection, state=%s, address=%s",
                    agpsDataConnStateAsString(),
                    address);
            Log.d(TAG, message);
        }

        if (mAGpsDataConnectionState != AGPS_DATA_CONNECTION_CLOSED) {
            return;
        }
        mAGpsDataConnectionIpAddr = address;
        mAGpsDataConnectionState = AGPS_DATA_CONNECTION_OPENING;

        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
        NetworkRequest request = requestBuilder.build();
        mConnMgr.requestNetwork(
                request,
                mSuplConnectivityCallback,
                SUPL_NETWORK_REQUEST_TIMEOUT_MILLIS);
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

        mConnMgr.unregisterNetworkCallback(mSuplConnectivityCallback);
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

    private void setRouting() {
        if (mAGpsDataConnectionIpAddr == null) {
            return;
        }

        // TODO(25876485): replace the use of this deprecated API
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
                return "<Unknown>";
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
                return "<Unknown>";
        }
    }

    private int getApnIpType(String apn) {
        ensureInHandlerThread();
        if (apn == null) {
            return APN_INVALID;
        }
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        // Carrier configuration may override framework roaming state, we need to use the actual
        // modem roaming state instead of the framework roaming state.
        boolean isDataRoamingFromRegistration = phone.getServiceState()
                .getDataRoamingFromRegistration();
        String projection = isDataRoamingFromRegistration ? Carriers.ROAMING_PROTOCOL :
                Carriers.PROTOCOL;
        String selection = String.format("current = 1 and apn = '%s' and carrier_enabled = 1", apn);
        try (Cursor cursor = mContext.getContentResolver().query(
                Carriers.CONTENT_URI,
                new String[]{projection},
                selection,
                null,
                Carriers.DEFAULT_SORT_ORDER)) {
            if (null != cursor && cursor.moveToFirst()) {
                return translateToApnIpType(cursor.getString(0), apn);
            } else {
                Log.e(TAG, "No entry found in query for APN: " + apn);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error encountered on APN query for: " + apn, e);
        }

        return APN_INVALID;
    }

    private int translateToApnIpType(String ipProtocol, String apn) {
        if ("IP".equals(ipProtocol)) {
            return APN_IPV4;
        }
        if ("IPV6".equals(ipProtocol)) {
            return APN_IPV6;
        }
        if ("IPV4V6".equals(ipProtocol)) {
            return APN_IPV4V6;
        }

        // we hit the default case so the ipProtocol is not recognized
        String message = String.format("Unknown IP Protocol: %s, for APN: %s", ipProtocol, apn);
        Log.e(TAG, message);
        return APN_INVALID;
    }

    private String getSelectedApn() {
        Uri uri = Uri.parse("content://telephony/carriers/preferapn");
        try (Cursor cursor = mContext.getContentResolver().query(
                uri,
                new String[]{"apn"},
                null /* selection */,
                null /* selectionArgs */,
                Carriers.DEFAULT_SORT_ORDER)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            } else {
                Log.e(TAG, "No APN found to select.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error encountered on selecting the APN.", e);
        }

        return null;
    }

    // AGPS support
    private native void native_agps_data_conn_open(String apn, int apnIpType);

    private native void native_agps_data_conn_closed();

    private native void native_agps_data_conn_failed();

    // AGPS ril support
    private static native boolean native_is_agps_ril_supported();

    private native void native_update_network_state(boolean connected, int type,
            boolean roaming, boolean available, String extraInfo, String defaultAPN);
}
