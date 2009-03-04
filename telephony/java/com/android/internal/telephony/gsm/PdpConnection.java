/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.os.*;
import android.database.Cursor;
import android.provider.Telephony;
import android.text.util.Regex;
import android.util.EventLog;
import android.util.Log;

import java.util.ArrayList;
import com.android.internal.telephony.Phone;

/**
 * {@hide}
 */
public class PdpConnection extends Handler {

    private static final String LOG_TAG = "GSM";
    private static final boolean DBG  = true;
    private static final boolean FAKE_FAIL = false;

    public enum PdpState {
        ACTIVE,     /* has active pdp context */
        ACTIVATING, /* during connecting process */
        INACTIVE;    /* has empty pdp context */

        public String toString() {
            switch (this) {
                case ACTIVE: return "active";
                case ACTIVATING: return "setting up";
                default: return "inactive";
            }
        }

        public boolean isActive() {
            return this == ACTIVE;
        }

        public boolean isInactive() {
            return this == INACTIVE;
        }
    }

    public enum PdpFailCause {
        NONE,
        BAD_APN,
        BAD_PAP_SECRET,
        BARRED,
        USER_AUTHENTICATION,
        SERVICE_OPTION_NOT_SUPPORTED,
        SERVICE_OPTION_NOT_SUBSCRIBED,
        SIM_LOCKED,
        RADIO_OFF,
        NO_SIGNAL,
        NO_DATA_PLAN,
        RADIO_NOT_AVIALABLE,
        SUSPENED_TEMPORARY,
        RADIO_ERROR_RETRY,
        UNKNOWN;

        public boolean isPermanentFail() {
            return (this == RADIO_OFF);
        }

        public String toString() {
            switch (this) {
                case NONE: return "no error";
                case BAD_APN: return "bad apn";
                case BAD_PAP_SECRET:return "bad pap secret";
                case BARRED: return "barred";
                case USER_AUTHENTICATION: return "error user autentication";
                case SERVICE_OPTION_NOT_SUPPORTED: return "data not supported";
                case SERVICE_OPTION_NOT_SUBSCRIBED: return "datt not subcribed";
                case SIM_LOCKED: return "sim locked";
                case RADIO_OFF: return "radio is off";
                case NO_SIGNAL: return "no signal";
                case NO_DATA_PLAN: return "no data plan";
                case RADIO_NOT_AVIALABLE: return "radio not available";
                case SUSPENED_TEMPORARY: return "suspend temporary";
                case RADIO_ERROR_RETRY: return "transient radio error";
                default: return "unknown data error";
            }
        }
    }

    /** Fail cause of last PDP activate, from RIL_LastPDPActivateFailCause */
    private static final int PDP_FAIL_RIL_BARRED = 8;
    private static final int PDP_FAIL_RIL_BAD_APN = 27;
    private static final int PDP_FAIL_RIL_USER_AUTHENTICATION = 29;
    private static final int PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUPPORTED = 32;
    private static final int PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUBSCRIBED = 33;
    private static final int PDP_FAIL_RIL_ERROR_UNSPECIFIED = 0xffff;

    //***** Event codes
    private static final int EVENT_SETUP_PDP_DONE = 1;
    private static final int EVENT_GET_LAST_FAIL_DONE = 2;
    private static final int EVENT_LINK_STATE_CHANGED = 3;
    private static final int EVENT_DEACTIVATE_DONE = 4;
    private static final int EVENT_FORCE_RETRY = 5;

    //***** Instance Variables
    private GSMPhone phone;
    private String pdp_name;
    private PdpState state;
    private Message onConnectCompleted;
    private Message onDisconnect;
    private int cid;
    private long createTime;
    private long lastFailTime;
    private PdpFailCause lastFailCause;
    private ApnSetting apn;
    private String interfaceName;
    private String ipAddress;
    private String gatewayAddress;
    private String[] dnsServers;

    private static final String NULL_IP = "0.0.0.0";

    // dataLink is only used to support pppd link
    DataLink dataLink;
    // receivedDisconnectReq is set when disconnect pdp link during activating
    private boolean receivedDisconnectReq;

    //***** Constructor
    PdpConnection(GSMPhone phone)
    {
        this.phone = phone;
        this.state = PdpState.INACTIVE;
        onConnectCompleted = null;
        onDisconnect = null;
        this.cid = -1;
        this.createTime = -1;
        this.lastFailTime = -1;
        this.lastFailCause = PdpFailCause.NONE;
        this.apn = null;
        this.dataLink = null;
        receivedDisconnectReq = false;
        this.dnsServers = new String[2];

        if (SystemProperties.get("ro.radio.use-ppp","no").equals("yes")) {
            dataLink = new PppLink(phone.mDataConnection);
            dataLink.setOnLinkChange(this, EVENT_LINK_STATE_CHANGED, null);
        }
    }

    /**
     * Setup PDP connection for provided apn
     * @param apn for this connection
     * @param onCompleted notify success or not after down
     */
    void connect(ApnSetting apn, Message onCompleted) {
        if (DBG) log("Connecting to carrier: '" + apn.carrier
                + "' APN: '" + apn.apn
                + "' proxy: '" + apn.proxy + "' port: '" + apn.port);

        setHttpProxy (apn.proxy, apn.port);

        state = PdpState.ACTIVATING;
        this.apn = apn;
        onConnectCompleted = onCompleted;
        createTime = -1;
        lastFailTime = -1;
        lastFailCause = PdpFailCause.NONE;
        receivedDisconnectReq = false;

        if (FAKE_FAIL) {
            // for debug before baseband implement error in setup PDP
            if (apn.apn.equalsIgnoreCase("badapn")){
                notifyFail(PdpFailCause.BAD_APN, onConnectCompleted);
                return;
            }
        }

        phone.mCM.setupDefaultPDP(apn.apn, apn.user, apn.password,
                obtainMessage(EVENT_SETUP_PDP_DONE));
    }

    void disconnect(Message msg) {
        onDisconnect = msg;
        if (state == PdpState.ACTIVE) {
            if (dataLink != null) {
                dataLink.disconnect();
            }

            if (phone.mCM.getRadioState().isOn()) {
                phone.mCM.deactivateDefaultPDP(cid, obtainMessage(EVENT_DEACTIVATE_DONE, msg));
            }
        } else if (state == PdpState.ACTIVATING) {
            receivedDisconnectReq = true;
        } else {
            // state == INACTIVE.  Nothing to do, so notify immediately.
            notifyDisconnect(msg);
        }
    }

    private void
    setHttpProxy(String httpProxy, String httpPort)
    {
        if (httpProxy == null || httpProxy.length() == 0) {
            phone.setSystemProperty("net.gprs.http-proxy", null);
            return;
        }

        if (httpPort == null || httpPort.length() == 0) {
            httpPort = "8080";     // Default to port 8080
        }

        phone.setSystemProperty("net.gprs.http-proxy",
                "http://" + httpProxy + ":" + httpPort + "/");
    }

    public String toString() {
        return "State=" + state + " Apn=" + apn +
               " create=" + createTime + " lastFail=" + lastFailTime +
               " lastFailCause=" + lastFailCause;
    }

    public long getConnectionTime() {
        return createTime;
    }

    public long getLastFailTime() {
        return lastFailTime;
    }

    public PdpFailCause getLastFailCause() {
        return lastFailCause;
    }

    public ApnSetting getApn() {
        return apn;
    }

    String getInterface() {
        return interfaceName;
    }

    String getIpAddress() {
        return ipAddress;
    }

    String getGatewayAddress() {
        return gatewayAddress;
    }

    String[] getDnsServers() {
        return dnsServers;
    }

    public PdpState getState() {
        return state;
    }

    private void notifyFail(PdpFailCause cause, Message onCompleted) {
        if (onCompleted == null) return;

        state = PdpState.INACTIVE;
        lastFailCause = cause;
        lastFailTime = System.currentTimeMillis();
        onConnectCompleted = null;

        if (DBG) log("Notify PDP fail at " + lastFailTime
                + " due to " + lastFailCause);

        AsyncResult.forMessage(onCompleted, cause, new Exception());
        onCompleted.sendToTarget();
    }

    private void notifySuccess(Message onCompleted) {
        if (onCompleted == null) return;

        state = PdpState.ACTIVE;
        createTime = System.currentTimeMillis();
        onConnectCompleted = null;
        onCompleted.arg1 = cid;

        if (DBG) log("Notify PDP success at " + createTime);

        AsyncResult.forMessage(onCompleted);
        onCompleted.sendToTarget();
    }

    private void notifyDisconnect(Message msg) {
        if (DBG) log("Notify PDP disconnect");

        if (msg != null) {
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        clearSettings();
    }

    void clearSettings() {
        state = PdpState.INACTIVE;
        receivedDisconnectReq = false;
        createTime = -1;
        lastFailTime = -1;
        lastFailCause = PdpFailCause.NONE;
        apn = null;
        onConnectCompleted = null;
        interfaceName = null;
        ipAddress = null;
        gatewayAddress = null;
        dnsServers[0] = null;
        dnsServers[1] = null;
    }

    private void onLinkStateChanged(DataLink.LinkState linkState) {
        switch (linkState) {
            case LINK_UP:
                notifySuccess(onConnectCompleted);
                break;

            case LINK_DOWN:
            case LINK_EXITED:
                phone.mCM.getLastPdpFailCause(
                        obtainMessage (EVENT_GET_LAST_FAIL_DONE));
                break;
        }
    }

    private PdpFailCause getFailCauseFromRequest(int rilCause) {
        PdpFailCause cause;

        switch (rilCause) {
            case PDP_FAIL_RIL_BARRED:
                cause = PdpFailCause.BARRED;
                break;
            case PDP_FAIL_RIL_BAD_APN:
                cause = PdpFailCause.BAD_APN;
                break;
            case PDP_FAIL_RIL_USER_AUTHENTICATION:
                cause = PdpFailCause.USER_AUTHENTICATION;
                break;
            case PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUPPORTED:
                cause = PdpFailCause.SERVICE_OPTION_NOT_SUPPORTED;
                break;
            case PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUBSCRIBED:
                cause = PdpFailCause.SERVICE_OPTION_NOT_SUBSCRIBED;
                break;
            default:
                cause = PdpFailCause.UNKNOWN;
        }
        return cause;
    }


    private void log(String s) {
        Log.d(LOG_TAG, "[PdpConnection] " + s);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_SETUP_PDP_DONE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "PDP Context Init failed " + ar.exception);

                    if (receivedDisconnectReq) {
                        // Don't bother reporting the error if there's already a
                        // pending disconnect request, since DataConnectionTracker
                        // has already updated its state.
                        notifyDisconnect(onDisconnect);
                    } else {
                        if ( ar.exception instanceof CommandException &&
                                ((CommandException) (ar.exception)).getCommandError()
                                == CommandException.Error.RADIO_NOT_AVAILABLE) {
                            notifyFail(PdpFailCause.RADIO_NOT_AVIALABLE,
                                    onConnectCompleted);
                        } else {
                            phone.mCM.getLastPdpFailCause(
                                    obtainMessage(EVENT_GET_LAST_FAIL_DONE));
                        }
                    }
                } else {
                    if (receivedDisconnectReq) {
                        // Don't bother reporting success if there's already a
                        // pending disconnect request, since DataConnectionTracker
                        // has already updated its state.
                        // Set ACTIVE so that disconnect does the right thing.
                        state = PdpState.ACTIVE;
                        disconnect(onDisconnect);
                    } else {
                        String[] response = ((String[]) ar.result);
                        cid = Integer.parseInt(response[0]);

                        if (response.length > 2) {
                            interfaceName = response[1];
                            ipAddress = response[2];
                            String prefix = "net." + interfaceName + ".";
                            gatewayAddress = SystemProperties.get(prefix + "gw");
                            dnsServers[0] = SystemProperties.get(prefix + "dns1");
                            dnsServers[1] = SystemProperties.get(prefix + "dns2");
                            if (DBG) {
                                log("interface=" + interfaceName + " ipAddress=" + ipAddress
                                    + " gateway=" + gatewayAddress + " DNS1=" + dnsServers[0]
                                    + " DNS2=" + dnsServers[1]);
                            }

                            if (NULL_IP.equals(dnsServers[0]) && NULL_IP.equals(dnsServers[1])
                                    && !phone.isDnsCheckDisabled()) {
                                // Work around a race condition where QMI does not fill in DNS:
                                // Deactivate PDP and let DataConnectionTracker retry.
                                // Do not apply the race condition workaround for MMS APN
                                // if Proxy is an IP-address.
                                // Otherwise, the default APN will not be restored anymore.
                                if (!apn.types[0].equals(Phone.APN_TYPE_MMS)
                                        || !isIpAddress(apn.mmsProxy)) {
                                    EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_BAD_DNS_ADDRESS,
                                            dnsServers[0]);
                                    phone.mCM.deactivateDefaultPDP(cid,
                                            obtainMessage(EVENT_FORCE_RETRY));
                                    break;
                                }
                            }
                        }

                        if (dataLink != null) {
                            dataLink.connect();
                        } else {
                            onLinkStateChanged(DataLink.LinkState.LINK_UP);
                        }

                        if (DBG) log("PDP setup on cid = " + cid);
                    }
                }
                break;
            case EVENT_FORCE_RETRY:
                if (receivedDisconnectReq) {
                    notifyDisconnect(onDisconnect);
                } else {
                    ar = (AsyncResult) msg.obj;
                    notifyFail(PdpFailCause.RADIO_ERROR_RETRY, onConnectCompleted);
                }
                break;
            case EVENT_GET_LAST_FAIL_DONE:
                if (receivedDisconnectReq) {
                    // Don't bother reporting the error if there's already a
                    // pending disconnect request, since DataConnectionTracker
                    // has already updated its state.
                    notifyDisconnect(onDisconnect);
                } else {
                    ar = (AsyncResult) msg.obj;
                    PdpFailCause cause = PdpFailCause.UNKNOWN;

                    if (ar.exception == null) {
                        int rilFailCause = ((int[]) (ar.result))[0];
                        cause = getFailCauseFromRequest(rilFailCause);
                    }
                    notifyFail(cause, onConnectCompleted);
                }

                break;
            case EVENT_LINK_STATE_CHANGED:
                ar = (AsyncResult) msg.obj;
                DataLink.LinkState ls  = (DataLink.LinkState) ar.result;
                onLinkStateChanged(ls);
                break;
            case EVENT_DEACTIVATE_DONE:
                ar = (AsyncResult) msg.obj;
                notifyDisconnect((Message) ar.userObj);
                break;
        }
    }

    private boolean isIpAddress(String address) {
        if (address == null) return false;

        return Regex.IP_ADDRESS_PATTERN.matcher(apn.mmsProxy).matches();
    }
}
