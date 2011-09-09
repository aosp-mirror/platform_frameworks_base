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
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ServiceManager;

import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;

import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo;
import android.net.LinkProperties;
import android.telephony.TelephonyManager;
import android.util.Slog;
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
    private static final boolean VDBG = false;

    private Phone.DataState mMobileDataState;
    private ITelephony mPhoneService;

    private String mApnType;
    private NetworkInfo mNetworkInfo;
    private boolean mTeardownRequested = false;
    private Handler mTarget;
    private Context mContext;
    private LinkProperties mLinkProperties;
    private LinkCapabilities mLinkCapabilities;
    private boolean mPrivateDnsRouteSet = false;
    private boolean mDefaultRouteSet = false;

    private Handler mHandler;
    private AsyncChannel mDataConnectionTrackerAc;
    private Messenger mMessenger;

    /**
     * Create a new MobileDataStateTracker
     * @param netType the ConnectivityManager network type
     * @param tag the name of this network
     */
    public MobileDataStateTracker(int netType, String tag) {
        mNetworkInfo = new NetworkInfo(netType,
                TelephonyManager.getDefault().getNetworkType(), tag,
                TelephonyManager.getDefault().getNetworkTypeName());
        mApnType = networkTypeToApnType(netType);
    }

    /**
     * Begin monitoring data connectivity.
     *
     * @param context is the current Android context
     * @param target is the Hander to which to return the events.
     */
    public void startMonitoring(Context context, Handler target) {
        mTarget = target;
        mContext = context;

        HandlerThread handlerThread = new HandlerThread("ConnectivityServiceThread");
        handlerThread.start();
        mHandler = new MdstHandler(handlerThread.getLooper(), this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        filter.addAction(DataConnectionTracker.ACTION_DATA_CONNECTION_TRACKER_MESSENGER);

        mContext.registerReceiver(new MobileDataStateReceiver(), filter);
        mMobileDataState = Phone.DataState.DISCONNECTED;
    }

    static class MdstHandler extends Handler {
        private MobileDataStateTracker mMdst;

        MdstHandler(Looper looper, MobileDataStateTracker mdst) {
            super(looper);
            mMdst = mdst;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) {
                            mMdst.log("MdstHandler connected");
                        }
                        mMdst.mDataConnectionTrackerAc = (AsyncChannel) msg.obj;
                    } else {
                        if (DBG) {
                            mMdst.log("MdstHandler %s NOT connected error=" + msg.arg1);
                        }
                    }
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    mMdst.log("Disconnected from DataStateTracker");
                    mMdst.mDataConnectionTrackerAc = null;
                    break;
                default: {
                    mMdst.log("Ignorning unknown message=" + msg);
                    break;
                }
            }
        }
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

    private class MobileDataStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(TelephonyIntents.
                    ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                String apnType = intent.getStringExtra(Phone.DATA_APN_TYPE_KEY);
                if (VDBG) {
                    log(String.format("Broadcast received: ACTION_ANY_DATA_CONNECTION_STATE_CHANGED"
                        + "mApnType=%s %s received apnType=%s", mApnType,
                        TextUtils.equals(apnType, mApnType) ? "==" : "!=", apnType));
                }
                if (!TextUtils.equals(apnType, mApnType)) {
                    return;
                }
                mNetworkInfo.setSubtype(TelephonyManager.getDefault().getNetworkType(),
                        TelephonyManager.getDefault().getNetworkTypeName());
                Phone.DataState state = Enum.valueOf(Phone.DataState.class,
                        intent.getStringExtra(Phone.STATE_KEY));
                String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);
                String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);

                mNetworkInfo.setIsAvailable(!intent.getBooleanExtra(Phone.NETWORK_UNAVAILABLE_KEY,
                        false));

                if (DBG) {
                    log("Received state=" + state + ", old=" + mMobileDataState +
                        ", reason=" + (reason == null ? "(unspecified)" : reason));
                }
                if (mMobileDataState != state) {
                    mMobileDataState = state;
                    switch (state) {
                        case DISCONNECTED:
                            if(isTeardownRequested()) {
                                setTeardownRequested(false);
                            }

                            setDetailedState(DetailedState.DISCONNECTED, reason, apnName);
                            // can't do this here - ConnectivityService needs it to clear stuff
                            // it's ok though - just leave it to be refreshed next time
                            // we connect.
                            //if (DBG) log("clearing mInterfaceName for "+ mApnType +
                            //        " as it DISCONNECTED");
                            //mInterfaceName = null;
                            break;
                        case CONNECTING:
                            setDetailedState(DetailedState.CONNECTING, reason, apnName);
                            break;
                        case SUSPENDED:
                            setDetailedState(DetailedState.SUSPENDED, reason, apnName);
                            break;
                        case CONNECTED:
                            mLinkProperties = intent.getParcelableExtra(
                                    Phone.DATA_LINK_PROPERTIES_KEY);
                            if (mLinkProperties == null) {
                                log("CONNECTED event did not supply link properties.");
                                mLinkProperties = new LinkProperties();
                            }
                            mLinkCapabilities = intent.getParcelableExtra(
                                    Phone.DATA_LINK_CAPABILITIES_KEY);
                            if (mLinkCapabilities == null) {
                                log("CONNECTED event did not supply link capabilities.");
                                mLinkCapabilities = new LinkCapabilities();
                            }
                            setDetailedState(DetailedState.CONNECTED, reason, apnName);
                            break;
                    }
                } else {
                    // There was no state change. Check if LinkProperties has been updated.
                    if (TextUtils.equals(reason, Phone.REASON_LINK_PROPERTIES_CHANGED)) {
                        mLinkProperties = intent.getParcelableExtra(Phone.DATA_LINK_PROPERTIES_KEY);
                        if (mLinkProperties == null) {
                            log("No link property in LINK_PROPERTIES change event.");
                            mLinkProperties = new LinkProperties();
                        }
                        // Just update reason field in this NetworkInfo
                        mNetworkInfo.setDetailedState(mNetworkInfo.getDetailedState(), reason,
                                                      mNetworkInfo.getExtraInfo());
                        Message msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED,
                                                            mNetworkInfo);
                        msg.sendToTarget();
                    }
                }
            } else if (intent.getAction().
                    equals(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED)) {
                String apnType = intent.getStringExtra(Phone.DATA_APN_TYPE_KEY);
                if (!TextUtils.equals(apnType, mApnType)) {
                    if (DBG) {
                        log(String.format(
                                "Broadcast received: ACTION_ANY_DATA_CONNECTION_FAILED ignore, " +
                                "mApnType=%s != received apnType=%s", mApnType, apnType));
                    }
                    return;
                }
                String reason = intent.getStringExtra(Phone.FAILURE_REASON_KEY);
                String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);
                if (DBG) {
                    log("Received " + intent.getAction() +
                                " broadcast" + reason == null ? "" : "(" + reason + ")");
                }
                setDetailedState(DetailedState.FAILED, reason, apnName);
            } else if (intent.getAction().
                    equals(DataConnectionTracker.ACTION_DATA_CONNECTION_TRACKER_MESSENGER)) {
                if (DBG) log(mApnType + " got ACTION_DATA_CONNECTION_TRACKER_MESSENGER");
                mMessenger = intent.getParcelableExtra(DataConnectionTracker.EXTRA_MESSENGER);
                AsyncChannel ac = new AsyncChannel();
                ac.connect(mContext, MobileDataStateTracker.this.mHandler, mMessenger);
            } else {
                if (DBG) log("Broadcast received: ignore " + intent.getAction());
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
        return mNetworkInfo.isAvailable();
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
        case TelephonyManager.NETWORK_TYPE_IDEN:
            networkTypeStr = "iden";
            break;
        case TelephonyManager.NETWORK_TYPE_LTE:
            networkTypeStr = "lte";
            break;
        case TelephonyManager.NETWORK_TYPE_EHRPD:
            networkTypeStr = "ehrpd";
            break;
        default:
            loge("unknown network type: " + tm.getNetworkType());
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
     * @param reason a {@code String} indicating a reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo optional {@code String} providing extra information about the state change
     */
    private void setDetailedState(NetworkInfo.DetailedState state, String reason,
            String extraInfo) {
        if (DBG) log("setDetailed state, old ="
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
            Message msg = mTarget.obtainMessage(EVENT_STATE_CHANGED, new NetworkInfo(mNetworkInfo));
            msg.sendToTarget();
        }
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
                // set IDLE here , avoid the following second FAILED not sent out
                mNetworkInfo.setDetailedState(DetailedState.IDLE, null, null);
                retValue = true;
                break;
            case Phone.APN_REQUEST_FAILED:
            case Phone.APN_TYPE_NOT_AVAILABLE:
                break;
            default:
                loge("Error in reconnect - unexpected response.");
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
                log("Ignoring mobile radio request because could not acquire PhoneService");
                break;
            }

            try {
                return mPhoneService.setRadio(turnOn);
            } catch (RemoteException e) {
                if (retry == 0) getPhoneService(true);
            }
        }

        log("Could not set radio power to " + (turnOn ? "on" : "off"));
        return false;
    }

    /**
     * @param enabled
     */
    public void setDataEnable(boolean enabled) {
        try {
            log("setDataEnable: E enabled=" + enabled);
            mDataConnectionTrackerAc.sendMessage(DataConnectionTracker.CMD_SET_DATA_ENABLE,
                    enabled ? DataConnectionTracker.ENABLED : DataConnectionTracker.DISABLED);
            log("setDataEnable: X enabled=" + enabled);
        } catch (Exception e) {
            log("setDataEnable: X mAc was null" + e);
        }
    }

    /**
     * carrier dependency is met/unmet
     * @param met
     */
    public void setDependencyMet(boolean met) {
        Bundle bundle = Bundle.forPair(DataConnectionTracker.APN_TYPE_KEY, mApnType);
        try {
            log("setDependencyMet: E met=" + met);
            Message msg = Message.obtain();
            msg.what = DataConnectionTracker.CMD_SET_DEPENDENCY_MET;
            msg.arg1 = (met ? DataConnectionTracker.ENABLED : DataConnectionTracker.DISABLED);
            msg.setData(bundle);
            mDataConnectionTrackerAc.sendMessage(msg);
            log("setDependencyMet: X met=" + met);
        } catch (NullPointerException e) {
            log("setDependencyMet: X mAc was null" + e);
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
                log("Ignoring feature request because could not acquire PhoneService");
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

        log("Could not " + (enable ? "enable" : "disable") + " APN type \"" + apnType + "\"");
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
            case ConnectivityManager.TYPE_MOBILE_FOTA:
                return Phone.APN_TYPE_FOTA;
            case ConnectivityManager.TYPE_MOBILE_IMS:
                return Phone.APN_TYPE_IMS;
            case ConnectivityManager.TYPE_MOBILE_CBS:
                return Phone.APN_TYPE_CBS;
            default:
                sloge("Error mapping networkType " + netType + " to apnType.");
                return null;
        }
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkProperties()
     */
    public LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkCapabilities()
     */
    public LinkCapabilities getLinkCapabilities() {
        return new LinkCapabilities(mLinkCapabilities);
    }

    private void log(String s) {
        Slog.d(TAG, mApnType + ": " + s);
    }

    private void loge(String s) {
        Slog.e(TAG, mApnType + ": " + s);
    }

    static private void sloge(String s) {
        Slog.e(TAG, s);
    }
}
