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

    /** Fail cause of last PDP activate, from RIL_LastPDPActivateFailCause */
    private static final int PDP_FAIL_OPERATOR_BARRED = 0x08;
    private static final int PDP_FAIL_INSUFFICIENT_RESOURCES = 0x1A;
    private static final int PDP_FAIL_MISSING_UKNOWN_APN = 0x1B;
    private static final int PDP_FAIL_UNKNOWN_PDP_ADDRESS_TYPE = 0x1C;
    private static final int PDP_FAIL_USER_AUTHENTICATION = 0x1D;
    private static final int PDP_FAIL_ACTIVATION_REJECT_GGSN = 0x1E;
    private static final int PDP_FAIL_ACTIVATION_REJECT_UNSPECIFIED = 0x1F;
    private static final int PDP_FAIL_SERVICE_OPTION_NOT_SUPPORTED = 0x20;
    private static final int PDP_FAIL_SERVICE_OPTION_NOT_SUBSCRIBED = 0x21;
    private static final int PDP_FAIL_SERVICE_OPTION_OUT_OF_ORDER = 0x22;
    private static final int PDP_FAIL_NSAPI_IN_USE      = 0x23;
    private static final int PDP_FAIL_PROTOCOL_ERRORS   = 0x6F;
    private static final int PDP_FAIL_ERROR_UNSPECIFIED = 0xffff;

    private static final int PDP_FAIL_REGISTRATION_FAIL = -1;
    private static final int PDP_FAIL_GPRS_REGISTRATION_FAIL = -2;

    //***** Instance Variables
    private String pdp_name;
    private ApnSetting apn;

    //***** Constructor
    PdpConnection(GSMPhone phone) {
        super(phone);
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

        phone.mCM.setupDataCall(Integer.toString(RILConstants.GSM_PHONE), null, apn.apn, apn.user,
                apn.password, obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE));
    }

    private void tearDownData(Message msg) {
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
            case PDP_FAIL_OPERATOR_BARRED:
                cause = FailCause.OPERATOR_BARRED;
                break;
            case PDP_FAIL_INSUFFICIENT_RESOURCES:
                cause = FailCause.INSUFFICIENT_RESOURCES;
                break;
            case PDP_FAIL_MISSING_UKNOWN_APN:
                cause = FailCause.MISSING_UKNOWN_APN;
                break;
            case PDP_FAIL_UNKNOWN_PDP_ADDRESS_TYPE:
                cause = FailCause.UNKNOWN_PDP_ADDRESS;
                break;
            case PDP_FAIL_USER_AUTHENTICATION:
                cause = FailCause.USER_AUTHENTICATION;
                break;
            case PDP_FAIL_ACTIVATION_REJECT_GGSN:
                cause = FailCause.ACTIVATION_REJECT_GGSN;
                break;
            case PDP_FAIL_ACTIVATION_REJECT_UNSPECIFIED:
                cause = FailCause.ACTIVATION_REJECT_UNSPECIFIED;
                break;
            case PDP_FAIL_SERVICE_OPTION_OUT_OF_ORDER:
                cause = FailCause.SERVICE_OPTION_OUT_OF_ORDER;
                break;
            case PDP_FAIL_SERVICE_OPTION_NOT_SUPPORTED:
                cause = FailCause.SERVICE_OPTION_NOT_SUPPORTED;
                break;
            case PDP_FAIL_SERVICE_OPTION_NOT_SUBSCRIBED:
                cause = FailCause.SERVICE_OPTION_NOT_SUBSCRIBED;
                break;
            case PDP_FAIL_NSAPI_IN_USE:
                cause = FailCause.NSAPI_IN_USE;
                break;
            case PDP_FAIL_PROTOCOL_ERRORS:
                cause = FailCause.PROTOCOL_ERRORS;
                break;
            case PDP_FAIL_ERROR_UNSPECIFIED:
                cause = FailCause.UNKNOWN;
                break;
            case PDP_FAIL_REGISTRATION_FAIL:
                cause = FailCause.REGISTRATION_FAIL;
                break;
            case PDP_FAIL_GPRS_REGISTRATION_FAIL:
                cause = FailCause.GPRS_REGISTRATION_FAIL;
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

                onLinkStateChanged(DataLink.LinkState.LINK_UP);

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
