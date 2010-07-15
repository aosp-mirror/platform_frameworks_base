/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo;
import android.net.NetworkProperties;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.text.TextUtils;

/**
 * Track the state of mobile data connectivity. This is done by
 * receiving broadcast intents from the Phone process whenever
 * the state of data connectivity changes.
 *
 * {@hide}
 */
public class MobileDataStateTracker implements NetworkStateTracker {

    private static final String TAG = "MobileDataStateTracker";
    private static final boolean DBG = true;

    private Phone.DataState mMobileDataState;
    private ITelephony mPhoneService;

    private String mApnType;
    private BroadcastReceiver mStateReceiver;
    private static String[] sDnsPropNames;
    private NetworkInfo mNetworkInfo;
    private boolean mTeardownRequested = false;
    private Handler mTarget;
    private Context mContext;
    private NetworkProperties mNetworkProperties;
    private boolean mPrivateDnsRouteSet = false;
    private int mDefaultGatewayAddr = 0;
    private boolean mDefaultRouteSet = false;

    /**
     * Create a new MobileDataStateTracker
     * @param context the application context of the caller
     * @param target a message handler for getting callbacks about state changes
     * @param netType the ConnectivityManager network type
     * @param apnType the Phone apnType
     * @param tag the name of this network
     */
    public MobileDataStateTracker(Context context, Handler target, int netType, String tag) {
        mTarget = target;
        mContext = context;
        mNetworkInfo = new NetworkInfo(netType,
                TelephonyManager.getDefault().getNetworkType(), tag,
                TelephonyManager.getDefault().getNetworkTypeName());
        mApnType = networkTypeToApnType(netType);

        mPhoneService = null;

        sDnsPropNames = new String[] {
                "net.rmnet0.dns1",
                "net.rmnet0.dns2",
                "net.eth0.dns1",
                "net.eth0.dns2",
                "net.eth0.dns3",
                "net.eth0.dns4",
                "net.gprs.dns1",
                "net.gprs.dns2",
                "net.ppp0.dns1",
                "net.ppp0.dns2"};

    }

    /**
     * Return the IP addresses of the DNS servers available for the mobile data
     * network interface.
     * @return a list of DNS addresses, with no holes.
     */
    public String[] getDnsPropNames() {
        return sDnsPropNames;
    }

    public boolean isPrivateDnsRouteSet() {
        return mPrivateDnsRouteSet;
    }

    public void privateDnsRouteSet(boolean enabled) {
        mPrivateDnsRouteSet = enabled;
    }

    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    public int getDefaultGatewayAddr() {
        return mDefaultGatewayAddr;
    }

    public boolean isDefaultRouteSet() {
        return mDefaultRouteSet;
    }

    public void defaultRouteSet(boolean enabled) {
        mDefaultRouteSet = enabled;
    }

    /**
     * This is not implemented.
     */
    public void releaseWakeLock() {
    }

    /**
     * Begin monitoring mobile data connectivity.
     */
    public void startMonitoring() {
        IntentFilter filter =
                new IntentFilter(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        filter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);

        mStateReceiver = new MobileDataStateReceiver();
        mContext.registerReceiver(mStateReceiver, filter);
        mMobileDataState = Phone.DataState.DISCONNECTED;
    }

    /**
     * Record the roaming status of the device, and if it is a change from the previous
     * status, send a notification to any listeners.
     * @param isRoaming {@code true} if the device is now roaming, {@code false}
     * if it is no longer roaming.
     */
    private void setRoamingStatus(boolean isRoaming) {
        if (isRoaming != mNetworkInfo.isRoaming()) {
            mNetworkInfo.setRoaming(isRoaming);
            Message msg = mTarget.obtainMessage(EVENT_ROAMING_CHANGED, mNetworkInfo);
            msg.sendToTarget();
        }
    }

    private void setSubtype(int subtype, String subtypeName) {
        if (mNetworkInfo.isConnected()) {
            int oldSubtype = mNetworkInfo.getSubtype();
            if (subtype != oldSubtype) {
                mNetworkInfo.setSubtype(subtype, subtypeName);
                Message msg = mTarget.obtainMessage(
                        EVENT_NETWORK_SUBTYPE_CHANGED, oldSubtype, 0, mNetworkInfo);
                msg.sendToTarget();
            }
        }
    }

    private class MobileDataStateReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            synchronized(this) {
                if (intent.getAction().equals(TelephonyIntents.
                        ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                    String apnType = intent.getStringExtra(Phone.DATA_APN_TYPE_KEY);

                    if (!TextUtils.equals(apnType, mApnType)) {
                        return;
                    }
                    Phone.DataState state = Enum.valueOf(Phone.DataState.class,
                            intent.getStringExtra(Phone.STATE_KEY));
                    String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);
                    String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);

                    boolean unavailable = intent.getBooleanExtra(Phone.NETWORK_UNAVAILABLE_KEY,
                            false);
                    mNetworkInfo.setIsAvailable(!unavailable);

                    if (DBG) Log.d(TAG, mApnType + " Received state= " + state + ", old= " +
                            mMobileDataState + ", reason= " +
                            (reason == null ? "(unspecified)" : reason));

                    if (mMobileDataState != state) {
                        mMobileDataState = state;
                        switch (state) {
                            case DISCONNECTED:
                                if(isTeardownRequested()) {
                                    setTeardownRequested(false);
                                }

                                setDetailedState(DetailedState.DISCONNECTED, reason, apnName);
                                if (mNetworkProperties != null) {
                                    String iface = mNetworkProperties.getInterfaceName();
                                    if (iface != null) NetworkUtils.resetConnections(iface);
                                }
                                // TODO - check this
                                // can't do this here - ConnectivityService needs it to clear stuff
                                // it's ok though - just leave it to be refreshed next time
                                // we connect.
                                //if (DBG) Log.d(TAG, "clearing mInterfaceName for "+ mApnType +
                                //        " as it DISCONNECTED");
                                //mInterfaceName = null;
                                //mDefaultGatewayAddr = 0;
                                break;
                            case CONNECTING:
                                setDetailedState(DetailedState.CONNECTING, reason, apnName);
                                break;
                            case SUSPENDED:
                                setDetailedState(DetailedState.SUSPENDED, reason, apnName);
                                break;
                            case CONNECTED:
                                mNetworkProperties = intent.getParcelableExtra(
                                        Phone.DATA_NETWORK_PROPERTIES_KEY);
                                if (mNetworkProperties == null) {
                                    Log.d(TAG,
                                            "CONNECTED event did not supply network properties.");
                                }
                                setDetailedState(DetailedState.CONNECTED, reason, apnName);
                                break;
                        }
                    }
                } else if (intent.getAction().
                        equals(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED)) {
                    String apnType = intent.getStringExtra(Phone.DATA_APN_TYPE_KEY);
                    if (!TextUtils.equals(apnType, mApnType)) {
                        return;
                    }
                    String reason = intent.getStringExtra(Phone.FAILURE_REASON_KEY);
                    String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);
                    if (DBG) Log.d(TAG, mApnType + "Received " + intent.getAction() +
                            " broadcast" + reason == null ? "" : "(" + reason + ")");
                    setDetailedState(DetailedState.FAILED, reason, apnName);
                }
                TelephonyManager tm = TelephonyManager.getDefault();
                setRoamingStatus(tm.isNetworkRoaming());
                setSubtype(tm.getNetworkType(), tm.getNetworkTypeName());
            }
        }
    }

    private void getPhoneService(boolean forceRefresh) {
        if ((mPhoneService == null) || forceRefresh) {
            mPhoneService = ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
        }
    }

    /**
     * Report whether data connectivity is possible.
     */
    public boolean isAvailable() {
        getPhoneService(false);

        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) break;

            try {
                return mPhoneService.isDataConnectivityPossible();
            } catch (RemoteException e) {
                // First-time failed, get the phone service again
                if (retry == 0) getPhoneService(true);
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     * The mobile data network subtype indicates what generation network technology is in effect,
     * e.g., GPRS, EDGE, UMTS, etc.
     */
    public int getNetworkSubtype() {
        return TelephonyManager.getDefault().getNetworkType();
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        String networkTypeStr = "unknown";
        TelephonyManager tm = new TelephonyManager(mContext);
        //TODO We have to edit the parameter for getNetworkType regarding CDMA
        switch(tm.getNetworkType()) {
        case TelephonyManager.NETWORK_TYPE_GPRS:
            networkTypeStr = "gprs";
            break;
        case TelephonyManager.NETWORK_TYPE_EDGE:
            networkTypeStr = "edge";
            break;
        case TelephonyManager.NETWORK_TYPE_UMTS:
            networkTypeStr = "umts";
            break;
        case TelephonyManager.NETWORK_TYPE_HSDPA:
            networkTypeStr = "hsdpa";
            break;
        case TelephonyManager.NETWORK_TYPE_HSUPA:
            networkTypeStr = "hsupa";
            break;
        case TelephonyManager.NETWORK_TYPE_HSPA:
            networkTypeStr = "hspa";
            break;
        case TelephonyManager.NETWORK_TYPE_CDMA:
            networkTypeStr = "cdma";
            break;
        case TelephonyManager.NETWORK_TYPE_1xRTT:
            networkTypeStr = "1xrtt";
            break;
        case TelephonyManager.NETWORK_TYPE_EVDO_0:
            networkTypeStr = "evdo";
            break;
        case TelephonyManager.NETWORK_TYPE_EVDO_A:
            networkTypeStr = "evdo";
            break;
        case TelephonyManager.NETWORK_TYPE_EVDO_B:
            networkTypeStr = "evdo";
            break;
        }
        return "net.tcp.buffersize." + networkTypeStr;
    }

    /**
     * Tear down mobile data connectivity, i.e., disable the ability to create
     * mobile data connections.
     * TODO - make async and return nothing?
     */
    public boolean teardown() {
        setTeardownRequested(true);
        return (setEnableApn(mApnType, false) != Phone.APN_REQUEST_FAILED);
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new @{code DetailedState}
     */
    private void setDetailedState(NetworkInfo.DetailedState state) {
        setDetailedState(state, null, null);
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new @{code DetailedState}
     * @param reason a {@code String} indicating a reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo optional {@code String} providing extra information about the state change
     */
    private void setDetailedState(NetworkInfo.DetailedState state, String reason, String extraInfo) {
        if (DBG) Log.d(TAG, "setDetailed state, old ="
                + mNetworkInfo.getDetailedState() + " and new state=" + state);
        if (state != mNetworkInfo.getDetailedState()) {
            boolean wasConnecting = (mNetworkInfo.getState() == NetworkInfo.State.CONNECTING);
            String lastReason = mNetworkInfo.getReason();
            /*
             * If a reason was supplied when the CONNECTING state was entered, and no
             * reason was supplied for entering the CONNECTED state, then retain the
             * reason that was supplied when going to CONNECTING.
             */
            if (wasConnecting && state == NetworkInfo.DetailedState.CONNECTED && reason == null
                    && lastReason != null)
                reason = lastReason;
            mNetworkInfo.setDetailedState(state, reason, extraInfo);
            Message msg = mTarget.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
            msg.sendToTarget();
        }
    }

    private void setDetailedStateInternal(NetworkInfo.DetailedState state) {
        mNetworkInfo.setDetailedState(state, null, null);
    }

    public void setTeardownRequested(boolean isRequested) {
        mTeardownRequested = isRequested;
    }

    public boolean isTeardownRequested() {
        return mTeardownRequested;
    }

    /**
     * Re-enable mobile data connectivity after a {@link #teardown()}.
     * TODO - make async and always get a notification?
     */
    public boolean reconnect() {
        boolean retValue = false; //connected or expect to be?
        setTeardownRequested(false);
        switch (setEnableApn(mApnType, true)) {
            case Phone.APN_ALREADY_ACTIVE:
                // need to set self to CONNECTING so the below message is handled.
                retValue = true;
                break;
            case Phone.APN_REQUEST_STARTED:
                // no need to do anything - we're already due some status update intents
                retValue = true;
                break;
            case Phone.APN_REQUEST_FAILED:
            case Phone.APN_TYPE_NOT_AVAILABLE:
                break;
            default:
                Log.e(TAG, "Error in reconnect - unexpected response.");
                break;
        }
        return retValue;
    }

    /**
     * Turn on or off the mobile radio. No connectivity will be possible while the
     * radio is off. The operation is a no-op if the radio is already in the desired state.
     * @param turnOn {@code true} if the radio should be turned on, {@code false} if
     */
    public boolean setRadio(boolean turnOn) {
        getPhoneService(false);
        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) {
                Log.w(TAG,
                    "Ignoring mobile radio request because could not acquire PhoneService");
                break;
            }

            try {
                return mPhoneService.setRadio(turnOn);
            } catch (RemoteException e) {
                if (retry == 0) getPhoneService(true);
            }
        }

        Log.w(TAG, "Could not set radio power to " + (turnOn ? "on" : "off"));
        return false;
    }

    /**
     * Tells the phone sub-system that the caller wants to
     * begin using the named feature. The only supported features at
     * this time are {@code Phone.FEATURE_ENABLE_MMS}, which allows an application
     * to specify that it wants to send and/or receive MMS data, and
     * {@code Phone.FEATURE_ENABLE_SUPL}, which is used for Assisted GPS.
     * @param feature the name of the feature to be used
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is feature-specific.
     * specific, except that the value {@code -1}
     * always indicates failure. For {@code Phone.FEATURE_ENABLE_MMS},
     * the other possible return values are
     * <ul>
     * <li>{@code Phone.APN_ALREADY_ACTIVE}</li>
     * <li>{@code Phone.APN_REQUEST_STARTED}</li>
     * <li>{@code Phone.APN_TYPE_NOT_AVAILABLE}</li>
     * <li>{@code Phone.APN_REQUEST_FAILED}</li>
     * </ul>
     */
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * Tells the phone sub-system that the caller is finished
     * using the named feature. The only supported feature at
     * this time is {@code Phone.FEATURE_ENABLE_MMS}, which allows an application
     * to specify that it wants to send and/or receive MMS data.
     * @param feature the name of the feature that is no longer needed
     * @param callingPid the process ID of the process that is issuing this request
     * @param callingUid the user ID of the process that is issuing this request
     * @return an integer value representing the outcome of the request.
     * The interpretation of this value is feature-specific, except that
     * the value {@code -1} always indicates failure.
     */
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return -1;
    }

    /**
     * This is not supported.
     */
    public void interpretScanResultsAvailable() {
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("Mobile data state: ");

        sb.append(mMobileDataState);
        return sb.toString();
    }

   /**
     * Internal method supporting the ENABLE_MMS feature.
     * @param apnType the type of APN to be enabled or disabled (e.g., mms)
     * @param enable {@code true} to enable the specified APN type,
     * {@code false} to disable it.
     * @return an integer value representing the outcome of the request.
     */
    private int setEnableApn(String apnType, boolean enable) {
        getPhoneService(false);
        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) {
                Log.w(TAG,
                    "Ignoring feature request because could not acquire PhoneService");
                break;
            }

            try {
                if (enable) {
                    return mPhoneService.enableApnType(apnType);
                } else {
                    return mPhoneService.disableApnType(apnType);
                }
            } catch (RemoteException e) {
                if (retry == 0) getPhoneService(true);
            }
        }

        Log.w(TAG, "Could not " + (enable ? "enable" : "disable")
                + " APN type \"" + apnType + "\"");
        return Phone.APN_REQUEST_FAILED;
    }

    public static String networkTypeToApnType(int netType) {
        switch(netType) {
            case ConnectivityManager.TYPE_MOBILE:
                return Phone.APN_TYPE_DEFAULT;  // TODO - use just one of these
            case ConnectivityManager.TYPE_MOBILE_MMS:
                return Phone.APN_TYPE_MMS;
            case ConnectivityManager.TYPE_MOBILE_SUPL:
                return Phone.APN_TYPE_SUPL;
            case ConnectivityManager.TYPE_MOBILE_DUN:
                return Phone.APN_TYPE_DUN;
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
                return Phone.APN_TYPE_HIPRI;
            default:
                Log.e(TAG, "Error mapping networkType " + netType + " to apnType.");
                return null;
        }
    }

    public NetworkProperties getNetworkProperties() {
        return mNetworkProperties;
    }
}
