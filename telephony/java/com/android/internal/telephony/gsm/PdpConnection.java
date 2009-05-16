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
import android.text.util.Regex;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataLink;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyEventLog;

/**
 * {@hide}
 */
public class PdpConnection extends DataConnection {

    private static final String LOG_TAG = "GSM";
    private static final boolean DBG  = true;
    private static final boolean FAKE_FAIL = false;

    /** Fail cause of last PDP activate, from RIL_LastPDPActivateFailCause */
    private static final int PDP_FAIL_RIL_BARRED = 8;
    private static final int PDP_FAIL_RIL_BAD_APN = 27;
    private static final int PDP_FAIL_RIL_USER_AUTHENTICATION = 29;
    private static final int PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUPPORTED = 32;
    private static final int PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUBSCRIBED = 33;
    private static final int PDP_FAIL_RIL_ERROR_UNSPECIFIED = 0xffff;

    //***** Instance Variables
    private String pdp_name;
    private ApnSetting apn;

    // dataLink is only used to support pppd link
    private DataLink dataLink;

    //***** Constructor
    PdpConnection(GSMPhone phone) {
        super(phone);
        this.dataLink = null;

        if (SystemProperties.get("ro.radio.use-ppp","no").equals("yes")) {
            dataLink = new PppLink((GsmDataConnectionTracker) phone.mDataConnection, phone);
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

        state = State.ACTIVATING;
        this.apn = apn;
        onConnectCompleted = onCompleted;
        createTime = -1;
        lastFailTime = -1;
        lastFailCause = FailCause.NONE;
        receivedDisconnectReq = false;

        if (FAKE_FAIL) {
            // for debug before baseband implement error in setup PDP
            if (apn.apn.equalsIgnoreCase("badapn")){
                notifyFail(FailCause.BAD_APN, onConnectCompleted);
                return;
            }
        }

        phone.mCM.setupDataCall(Integer.toString(RILConstants.GSM_PHONE), null, apn.apn, apn.user,
                apn.password, obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE));
    }

    private void tearDownData(Message msg) {
        if (dataLink != null) {
            dataLink.disconnect();
        }

        if (phone.mCM.getRadioState().isOn()) {
            phone.mCM.deactivateDataCall(cid, obtainMessage(EVENT_DEACTIVATE_DONE, msg));
        }
    }

    protected void disconnect(Message msg) {
        onDisconnect = msg;
        if (state == State.ACTIVE) {
            tearDownData(msg);
        } else if (state == State.ACTIVATING) {
            receivedDisconnectReq = true;
        } else {
            // state == INACTIVE.  Nothing to do, so notify immediately.
            notifyDisconnect(msg);
        }
    }

    public void clearSettings() {
        super.clearSettings();
        apn = null;
    }

    public String toString() {
        return "State=" + state + " Apn=" + apn +
               " create=" + createTime + " lastFail=" + lastFailTime +
               " lastFailCause=" + lastFailCause;
    }


    protected void notifyFail(FailCause cause, Message onCompleted) {
        if (onCompleted == null) return;

        state = State.INACTIVE;
        lastFailCause = cause;
        lastFailTime = System.currentTimeMillis();
        onConnectCompleted = null;

        if (DBG) {
            log("Notify PDP fail at " + lastFailTime +
                    " due to " + lastFailCause);
        }

        AsyncResult.forMessage(onCompleted, cause, new Exception());
        onCompleted.sendToTarget();
    }

    protected void notifySuccess(Message onCompleted) {
        if (onCompleted == null) {
            return;
        }

        state = State.ACTIVE;
        createTime = System.currentTimeMillis();
        onConnectCompleted = null;
        onCompleted.arg1 = cid;

        if (DBG) log("Notify PDP success at " + createTime);

        AsyncResult.forMessage(onCompleted);
        onCompleted.sendToTarget();
    }

    protected void notifyDisconnect(Message msg) {
        if (DBG) log("Notify PDP disconnect");

        if (msg != null) {
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        clearSettings();
    }

    protected void onLinkStateChanged(DataLink.LinkState linkState) {
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

    protected FailCause getFailCauseFromRequest(int rilCause) {
        FailCause cause;

        switch (rilCause) {
            case PDP_FAIL_RIL_BARRED:
                cause = FailCause.BARRED;
                break;
            case PDP_FAIL_RIL_BAD_APN:
                cause = FailCause.BAD_APN;
                break;
            case PDP_FAIL_RIL_USER_AUTHENTICATION:
                cause = FailCause.USER_AUTHENTICATION;
                break;
            case PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUPPORTED:
                cause = FailCause.SERVICE_OPTION_NOT_SUPPORTED;
                break;
            case PDP_FAIL_RIL_SERVICE_OPTION_NOT_SUBSCRIBED:
                cause = FailCause.SERVICE_OPTION_NOT_SUBSCRIBED;
                break;
            default:
                cause = FailCause.UNKNOWN;
        }
        return cause;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[PdpConnection] " + s);
    }

    @Override
    protected void onDeactivated(AsyncResult ar) {
        notifyDisconnect((Message) ar.userObj);
        if (DBG) log("PDP Connection Deactivated");
    }

    @Override
    protected void onSetupConnectionCompleted(AsyncResult ar) {
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
                    notifyFail(FailCause.RADIO_NOT_AVAILABLE,
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
                tearDownData(onDisconnect);
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
                                        && !((GSMPhone) phone).isDnsCheckDisabled()) {
                        // Work around a race condition where QMI does not fill in DNS:
                        // Deactivate PDP and let DataConnectionTracker retry.
                        // Do not apply the race condition workaround for MMS APN
                        // if Proxy is an IP-address.
                        // Otherwise, the default APN will not be restored anymore.
                        if (!apn.types[0].equals(Phone.APN_TYPE_MMS)
                                || !isIpAddress(apn.mmsProxy)) {
                            EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_BAD_DNS_ADDRESS,
                                    dnsServers[0]);
                            phone.mCM.deactivateDataCall(cid, obtainMessage(EVENT_FORCE_RETRY));
                            return;
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
    }

    private boolean isIpAddress(String address) {
        if (address == null) return false;

        return Regex.IP_ADDRESS_PATTERN.matcher(apn.mmsProxy).matches();
    }

    public ApnSetting getApn() {
        return this.apn;
    }
}
