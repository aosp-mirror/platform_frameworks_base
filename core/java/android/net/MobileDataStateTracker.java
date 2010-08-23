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

import java.net.InetAddress;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.os.Handler;
import android.os.ServiceManager;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import android.net.NetworkInfo.DetailedState;
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
public class MobileDataStateTracker extends NetworkStateTracker {

    private static final String TAG = "MobileDataStateTracker";
    private static final boolean DBG = true;

    private Phone.DataState mMobileDataState;
    private ITelephony mPhoneService;

    private String mApnType;
    private String mApnTypeToWatchFor;
    private String mApnName;
    private boolean mEnabled;
    private BroadcastReceiver mStateReceiver;

    /**
     * Create a new MobileDataStateTracker
     * @param context the application context of the caller
     * @param target a message handler for getting callbacks about state changes
     * @param netType the ConnectivityManager network type
     * @param apnType the Phone apnType
     * @param tag the name of this network
     */
    public MobileDataStateTracker(Context context, Handler target, int netType, String tag) {
        super(context, target, netType,
                TelephonyManager.getDefault().getNetworkType(), tag,
                TelephonyManager.getDefault().getNetworkTypeName());
        mApnType = networkTypeToApnType(netType);
        if (TextUtils.equals(mApnType, Phone.APN_TYPE_HIPRI)) {
            mApnTypeToWatchFor = Phone.APN_TYPE_DEFAULT;
        } else {
            mApnTypeToWatchFor = mApnType;
        }

        mPhoneService = null;
        if(netType == ConnectivityManager.TYPE_MOBILE) {
            mEnabled = true;
        } else {
            mEnabled = false;
        }

        mDnsPropNames = new String[] {
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
     * Begin monitoring mobile data connectivity.
     */
    public void startMonitoring() {
        IntentFilter filter =
                new IntentFilter(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        filter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);

        mStateReceiver = new MobileDataStateReceiver();
        Intent intent = mContext.registerReceiver(mStateReceiver, filter);
        if (intent != null)
            mMobileDataState = getMobileDataState(intent);
        else
            mMobileDataState = Phone.DataState.DISCONNECTED;
    }

    private Phone.DataState getMobileDataState(Intent intent) {
        String str = intent.getStringExtra(Phone.STATE_KEY);
        if (str != null) {
            String apnTypeList =
                    intent.getStringExtra(Phone.DATA_APN_TYPES_KEY);
            if (isApnTypeIncluded(apnTypeList)) {
                return Enum.valueOf(Phone.DataState.class, str);
            }
        }
        return Phone.DataState.DISCONNECTED;
    }

    private boolean isApnTypeIncluded(String typeList) {
        /* comma seperated list - split and check */
        if (typeList == null)
            return false;

        String[] list = typeList.split(",");
        for(int i=0; i< list.length; i++) {
            if (TextUtils.equals(list[i], mApnTypeToWatchFor) ||
                TextUtils.equals(list[i], Phone.APN_TYPE_ALL)) {
                return true;
            }
        }
        return false;
    }

    private class MobileDataStateReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            synchronized(this) {
                if (intent.getAction().equals(TelephonyIntents.
                        ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                    Phone.DataState state = getMobileDataState(intent);
                    String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);
                    String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);
                    String apnTypeList = intent.getStringExtra(Phone.DATA_APN_TYPES_KEY);
                    mApnName = apnName;

                    boolean unavailable = intent.getBooleanExtra(Phone.NETWORK_UNAVAILABLE_KEY,
                            false);

                    // set this regardless of the apnTypeList.  It's all the same radio/network
                    // underneath
                    mNetworkInfo.setIsAvailable(!unavailable);

                    if (isApnTypeIncluded(apnTypeList)) {
                        if (mEnabled == false) {
                            // if we're not enabled but the APN Type is supported by this connection
                            // we should record the interface name if one's provided.  If the user
                            // turns on this network we will need the interfacename but won't get
                            // a fresh connected message - TODO fix this when we get per-APN
                            // notifications
                            if (state == Phone.DataState.CONNECTED) {
                                if (DBG) Log.d(TAG, "replacing old mInterfaceName (" +
                                        mInterfaceName + ") with " +
                                        intent.getStringExtra(Phone.DATA_IFACE_NAME_KEY) +
                                        " for " + mApnType);
                                mInterfaceName = intent.getStringExtra(Phone.DATA_IFACE_NAME_KEY);
                            }
                            return;
                        }
                    } else {
                        return;
                    }

                    if (DBG) Log.d(TAG, mApnType + " Received state= " + state + ", old= " +
                            mMobileDataState + ", reason= " +
                            (reason == null ? "(unspecified)" : reason) +
                            ", apnTypeList= " + apnTypeList);

                    if (mMobileDataState != state) {
                        mMobileDataState = state;
                        switch (state) {
                            case DISCONNECTED:
                                if(isTeardownRequested()) {
                                    mEnabled = false;
                                    setTeardownRequested(false);
                                }

                                setDetailedState(DetailedState.DISCONNECTED, reason, apnName);
                                if (mInterfaceName != null) {
                                    NetworkUtils.resetConnections(mInterfaceName);
                                }
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
                                mInterfaceName = intent.getStringExtra(Phone.DATA_IFACE_NAME_KEY);
                                if (mInterfaceName == null) {
                                    Log.d(TAG, "CONNECTED event did not supply interface name.");
                                }
                                setDetailedState(DetailedState.CONNECTED, reason, apnName);
                                break;
                        }
                    }
                } else if (intent.getAction().
                        equals(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED)) {
                    mEnabled = false;
                    String reason = intent.getStringExtra(Phone.FAILURE_REASON_KEY);
                    String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);
                    if (DBG) Log.d(TAG, "Received " + intent.getAction() + " broadcast" +
                            reason == null ? "" : "(" + reason + ")");
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
     */
    @Override
    public boolean teardown() {
        // since we won't get a notification currently (TODO - per APN notifications)
        // we won't get a disconnect message until all APN's on the current connection's
        // APN list are disabled.  That means privateRoutes for DNS and such will remain on -
        // not a problem since that's all shared with whatever other APN is still on, but
        // ugly.
        setTeardownRequested(true);
        return (setEnableApn(mApnType, false) != Phone.APN_REQUEST_FAILED);
    }

    /**
     * Re-enable mobile data connectivity after a {@link #teardown()}.
     */
    public boolean reconnect() {
        setTeardownRequested(false);
        switch (setEnableApn(mApnType, true)) {
            case Phone.APN_ALREADY_ACTIVE:
                // TODO - remove this when we get per-apn notifications
                mEnabled = true;
                // need to set self to CONNECTING so the below message is handled.
                mMobileDataState = Phone.DataState.CONNECTING;
                setDetailedState(DetailedState.CONNECTING, Phone.REASON_APN_CHANGED, null);
                //send out a connected message
                Intent intent = new Intent(TelephonyIntents.
                        ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
                intent.putExtra(Phone.STATE_KEY, Phone.DataState.CONNECTED.toString());
                intent.putExtra(Phone.STATE_CHANGE_REASON_KEY, Phone.REASON_APN_CHANGED);
                intent.putExtra(Phone.DATA_APN_TYPES_KEY, mApnTypeToWatchFor);
                intent.putExtra(Phone.DATA_APN_KEY, mApnName);
                intent.putExtra(Phone.DATA_IFACE_NAME_KEY, mInterfaceName);
                intent.putExtra(Phone.NETWORK_UNAVAILABLE_KEY, false);
                if (mStateReceiver != null) mStateReceiver.onReceive(mContext, intent);
                break;
            case Phone.APN_REQUEST_STARTED:
                mEnabled = true;
                // no need to do anything - we're already due some status update intents
                break;
            case Phone.APN_REQUEST_FAILED:
                if (mPhoneService == null && mApnType == Phone.APN_TYPE_DEFAULT) {
                    // on startup we may try to talk to the phone before it's ready
                    // since the phone will come up enabled, go with that.
                    // TODO - this also comes up on telephony crash: if we think mobile data is
                    // off and the telephony stuff crashes and has to restart it will come up
                    // enabled (making a data connection).  We will then be out of sync.
                    // A possible solution is a broadcast when telephony restarts.
                    mEnabled = true;
                    return false;
                }
                // else fall through
            case Phone.APN_TYPE_NOT_AVAILABLE:
                // Default is always available, but may be off due to
                // AirplaneMode or E-Call or whatever..
                if (mApnType != Phone.APN_TYPE_DEFAULT) {
                    mEnabled = false;
                }
                break;
            default:
                Log.e(TAG, "Error in reconnect - unexpected response.");
                mEnabled = false;
                break;
        }
        return mEnabled;
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
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the mobile data network.
     * @param hostAddress the IP address of the host to which the route is desired,
     * @return {@code true} on success, {@code false} on failure
     */
    @Override
    public boolean requestRouteToHost(InetAddress hostAddress) {
        if (DBG) {
            Log.d(TAG, "Requested host route to " + hostAddress.getHostAddress() +
                    " for " + mApnType + "(" + mInterfaceName + ")");
        }
        if (mInterfaceName != null) {
            return NetworkUtils.addHostRoute(mInterfaceName, hostAddress, null);
        } else {
            return false;
        }
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
}
