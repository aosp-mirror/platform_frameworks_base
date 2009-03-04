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
import android.os.ServiceManager;
import android.os.SystemProperties;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import android.net.NetworkInfo.DetailedState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.text.TextUtils;

import java.util.List;
import java.util.ArrayList;

/**
 * Track the state of mobile data connectivity. This is done by
 * receiving broadcast intents from the Phone process whenever
 * the state of data connectivity changes.
 *
 * {@hide}
 */
public class MobileDataStateTracker extends NetworkStateTracker {

    private static final String TAG = "MobileDataStateTracker";
    private static final boolean DBG = false;

    private Phone.DataState mMobileDataState;
    private ITelephony mPhoneService;
    private static final String[] sDnsPropNames = {
          "net.rmnet0.dns1",
          "net.rmnet0.dns2",
          "net.eth0.dns1",
          "net.eth0.dns2",
          "net.eth0.dns3",
          "net.eth0.dns4",
          "net.gprs.dns1",
          "net.gprs.dns2"
    };
    private List<String> mDnsServers;
    private String mInterfaceName;
    private int mDefaultGatewayAddr;
    private int mLastCallingPid = -1;

    /**
     * Create a new MobileDataStateTracker
     * @param context the application context of the caller
     * @param target a message handler for getting callbacks about state changes
     */
    public MobileDataStateTracker(Context context, Handler target) {
        super(context, target, ConnectivityManager.TYPE_MOBILE,
              TelephonyManager.getDefault().getNetworkType(), "MOBILE",
              TelephonyManager.getDefault().getNetworkTypeName());
        mPhoneService = null;
        mDnsServers = new ArrayList<String>();
    }

    /**
     * Begin monitoring mobile data connectivity.
     */
    public void startMonitoring() {
        IntentFilter filter =
                new IntentFilter(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        filter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);

        Intent intent = mContext.registerReceiver(new MobileDataStateReceiver(), filter);
        if (intent != null)
            mMobileDataState = getMobileDataState(intent);
        else
            mMobileDataState = Phone.DataState.DISCONNECTED;
    }

    private static Phone.DataState getMobileDataState(Intent intent) {
        String str = intent.getStringExtra(Phone.STATE_KEY);
        if (str != null)
            return Enum.valueOf(Phone.DataState.class, str);
        else
            return Phone.DataState.DISCONNECTED;
    }

    private class MobileDataStateReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                Phone.DataState state = getMobileDataState(intent);
                String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);
                String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);
                boolean unavailable = intent.getBooleanExtra(Phone.NETWORK_UNAVAILABLE_KEY, false);
                if (DBG) Log.d(TAG, "Received " + intent.getAction() +
                    " broadcast - state = " + state
                    + ", unavailable = " + unavailable
                    + ", reason = " + (reason == null ? "(unspecified)" : reason));
                mNetworkInfo.setIsAvailable(!unavailable);
                if (mMobileDataState != state) {
                    mMobileDataState = state;

                    switch (state) {
                        case DISCONNECTED:
                            setDetailedState(DetailedState.DISCONNECTED, reason, apnName);
                            if (mInterfaceName != null) {
                                NetworkUtils.resetConnections(mInterfaceName);
                            }
                            mInterfaceName = null;
                            mDefaultGatewayAddr = 0;
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
                            setupDnsProperties();
                            setDetailedState(DetailedState.CONNECTED, reason, apnName);
                            break;
                    }
                }
            } else if (intent.getAction().equals(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED)) {
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

    /**
     * Make sure that route(s) exist to the carrier DNS server(s).
     */
    public void addPrivateRoutes() {
        if (mInterfaceName != null) {
            for (String addrString : mDnsServers) {
                int addr = NetworkUtils.lookupHost(addrString);
                if (addr != -1) {
                    NetworkUtils.addHostRoute(mInterfaceName, addr);
                }
            }
        }
    }

    public void removePrivateRoutes() {
        if(mInterfaceName != null) {
            NetworkUtils.removeHostRoutes(mInterfaceName);
        }
    }

    public void removeDefaultRoute() {
        if(mInterfaceName != null) {
            mDefaultGatewayAddr = NetworkUtils.getDefaultRoute(mInterfaceName);
            NetworkUtils.removeDefaultRoute(mInterfaceName);
        }
    }

    public void restoreDefaultRoute() {
        // 0 is not a valid address for a gateway
        if (mInterfaceName != null && mDefaultGatewayAddr != 0) {
            NetworkUtils.setDefaultRoute(mInterfaceName, mDefaultGatewayAddr);
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
     * Return the IP addresses of the DNS servers available for the mobile data
     * network interface.
     * @return a list of DNS addresses, with no holes.
     */
    public String[] getNameServers() {
        return getNameServerList(sDnsPropNames);
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
        }
        return "net.tcp.buffersize." + networkTypeStr;
    }

    /**
     * Tear down mobile data connectivity, i.e., disable the ability to create
     * mobile data connections.
     */
    @Override
    public boolean teardown() {
        getPhoneService(false);
        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) {
                Log.w(TAG,
                    "Ignoring mobile data teardown request because could not acquire PhoneService");
                break;
            }

            try {
                return mPhoneService.disableDataConnectivity();
            } catch (RemoteException e) {
                if (retry == 0) getPhoneService(true);
            }
        }
        
        Log.w(TAG, "Failed to tear down mobile data connectivity");
        return false;
    }

    /**
     * Re-enable mobile data connectivity after a {@link #teardown()}.
     */
    public boolean reconnect() {
        getPhoneService(false);
        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++) {
            if (mPhoneService == null) {
                Log.w(TAG,
                    "Ignoring mobile data connect request because could not acquire PhoneService");
                break;
            }

            try {
                return mPhoneService.enableDataConnectivity();
            } catch (RemoteException e) {
                if (retry == 0) getPhoneService(true);
            }
        }

        Log.w(TAG, "Failed to set up mobile data connectivity");
        return false;
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
     * begin using the named feature. The only supported feature at
     * this time is {@code Phone.FEATURE_ENABLE_MMS}, which allows an application
     * to specify that it wants to send and/or receive MMS data.
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
        if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_MMS)) {
            mLastCallingPid = callingPid;
            return setEnableApn(Phone.APN_TYPE_MMS, true);
        } else {
            return -1;
        }
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
        if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_MMS)) {
            return setEnableApn(Phone.APN_TYPE_MMS, false);
        } else {
            return -1;
        }
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the mobile data network.
     * @param hostAddress the IP address of the host to which the route is desired,
     * in network byte order.
     * @return {@code true} on success, {@code false} on failure
     */
    @Override
    public boolean requestRouteToHost(int hostAddress) {
        if (mInterfaceName != null && hostAddress != -1) {
            if (DBG) {
                Log.d(TAG, "Requested host route to " + Integer.toHexString(hostAddress));
            }
            return NetworkUtils.addHostRoute(mInterfaceName, hostAddress) == 0;
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

    private void setupDnsProperties() {
        mDnsServers.clear();
        // Set up per-process DNS server list on behalf of the MMS process
        int i = 1;
        if (mInterfaceName != null) {
            for (String propName : sDnsPropNames) {
                if (propName.indexOf(mInterfaceName) != -1) {
                    String propVal = SystemProperties.get(propName);
                    if (propVal != null && propVal.length() != 0 && !propVal.equals("0.0.0.0")) {
                        mDnsServers.add(propVal);
                        if (mLastCallingPid != -1) {
                            SystemProperties.set("net.dns"  + i + "." + mLastCallingPid, propVal);
                        }
                        ++i;
                    }
                }
            }
        }
        if (i == 1) {
            Log.d(TAG, "DNS server addresses are not known.");
        } else if (mLastCallingPid != -1) {
            /*
            * Bump the property that tells the name resolver library
            * to reread the DNS server list from the properties.
            */
            String propVal = SystemProperties.get("net.dnschange");
            if (propVal.length() != 0) {
                try {
                    int n = Integer.parseInt(propVal);
                    SystemProperties.set("net.dnschange", "" + (n+1));
                } catch (NumberFormatException e) {
                }
            }
        }
        mLastCallingPid = -1;
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
}
