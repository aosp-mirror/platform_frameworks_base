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

package com.android.internal.telephony.cdma;

import android.os.*;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataLink;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyEventLog;

/**
 * {@hide}
 *
 */
public class CdmaDataConnection extends DataConnection {

    private static final String LOG_TAG = "CDMA";
    private static final boolean DBG = true;

    /** Fail cause of last Data Call activate from RIL_LastDataCallActivateFailCause */
    private final static int PS_NET_DOWN_REASON_OPERATOR_DETERMINED_BARRING         = 8;
    private final static int PS_NET_DOWN_REASON_UNKNOWN_APN                         = 27;
    private final static int PS_NET_DOWN_REASON_AUTH_FAILED                         = 29;
    private final static int PS_NET_DOWN_REASON_OPTION_NOT_SUPPORTED                = 32;
    private final static int PS_NET_DOWN_REASON_OPTION_UNSUBSCRIBED                 = 33;

/** It is likely that the number of error codes listed below will be removed
 * in the foreseeable future.  They have been added, but not agreed upon.
 *
 */
    private final static int PS_NET_DOWN_REASON_NOT_SPECIFIED                       = 0;
    private final static int PS_NET_DOWN_REASON_CLOSE_IN_PROGRESS                   = 1;
    private final static int PS_NET_DOWN_REASON_NW_INITIATED_TERMINATION            = 2;
    private final static int PS_NET_DOWN_REASON_APP_PREEMPTED                       = 3;
    private final static int PS_NET_DOWN_REASON_LLC_SNDCP_FAILURE                   = 25;
    private final static int PS_NET_DOWN_REASON_INSUFFICIENT_RESOURCES              = 26;
    private final static int PS_NET_DOWN_REASON_UNKNOWN_PDP                         = 28;
    private final static int PS_NET_DOWN_REASON_GGSN_REJECT                         = 30;
    private final static int PS_NET_DOWN_REASON_ACTIVATION_REJECT                   = 31;
    private final static int PS_NET_DOWN_REASON_OPTION_TEMP_OOO                     = 34;
    private final static int PS_NET_DOWN_REASON_NSAPI_ALREADY_USED                  = 35;
    private final static int PS_NET_DOWN_REASON_REGULAR_DEACTIVATION                = 36;
    private final static int PS_NET_DOWN_REASON_QOS_NOT_ACCEPTED                    = 37;
    private final static int PS_NET_DOWN_REASON_NETWORK_FAILURE                     = 38;
    private final static int PS_NET_DOWN_REASON_UMTS_REATTACH_REQ                   = 39;
    private final static int PS_NET_DOWN_REASON_TFT_SEMANTIC_ERROR                  = 41;
    private final static int PS_NET_DOWN_REASON_TFT_SYNTAX_ERROR                    = 42;
    private final static int PS_NET_DOWN_REASON_UNKNOWN_PDP_CONTEXT                 = 43;
    private final static int PS_NET_DOWN_REASON_FILTER_SEMANTIC_ERROR               = 44;
    private final static int PS_NET_DOWN_REASON_FILTER_SYNTAX_ERROR                 = 45;
    private final static int PS_NET_DOWN_REASON_PDP_WITHOUT_ACTIVE_TFT              = 46;
    private final static int PS_NET_DOWN_REASON_INVALID_TRANSACTION_ID              = 81;
    private final static int PS_NET_DOWN_REASON_MESSAGE_INCORRECT_SEMANTIC          = 95;
    private final static int PS_NET_DOWN_REASON_INVALID_MANDATORY_INFO              = 96;
    private final static int PS_NET_DOWN_REASON_MESSAGE_TYPE_UNSUPPORTED            = 97;
    private final static int PS_NET_DOWN_REASON_MSG_TYPE_NONCOMPATIBLE_STATE        = 98;
    private final static int PS_NET_DOWN_REASON_UNKNOWN_INFO_ELEMENT                = 99;
    private final static int PS_NET_DOWN_REASON_CONDITIONAL_IE_ERROR                = 100;
    private final static int PS_NET_DOWN_REASON_MSG_AND_PROTOCOL_STATE_UNCOMPATIBLE = 101;
    private final static int PS_NET_DOWN_REASON_PROTOCOL_ERROR                      = 111;
    private final static int PS_NET_DOWN_REASON_APN_TYPE_CONFLICT                   = 112;
    private final static int PS_NET_DOWN_REASON_UNKNOWN_CAUSE_CODE                  = 113;
    private final static int PS_NET_DOWN_REASON_INTERNAL_MIN                        = 200;
    private final static int PS_NET_DOWN_REASON_INTERNAL_ERROR                      = 201;
    private final static int PS_NET_DOWN_REASON_INTERNAL_CALL_ENDED                 = 202;
    private final static int PS_NET_DOWN_REASON_INTERNAL_UNKNOWN_CAUSE_CODE         = 203;
    private final static int PS_NET_DOWN_REASON_INTERNAL_MAX                        = 204;
    private final static int PS_NET_DOWN_REASON_CDMA_LOCK                           = 500;
    private final static int PS_NET_DOWN_REASON_INTERCEPT                           = 501;
    private final static int PS_NET_DOWN_REASON_REORDER                             = 502;
    private final static int PS_NET_DOWN_REASON_REL_SO_REJ                          = 503;
    private final static int PS_NET_DOWN_REASON_INCOM_CALL                          = 504;
    private final static int PS_NET_DOWN_REASON_ALERT_STOP                          = 505;
    private final static int PS_NET_DOWN_REASON_ACTIVATION                          = 506;
    private final static int PS_NET_DOWN_REASON_MAX_ACCESS_PROBE                    = 507;
    private final static int PS_NET_DOWN_REASON_CCS_NOT_SUPPORTED_BY_BS             = 508;
    private final static int PS_NET_DOWN_REASON_NO_RESPONSE_FROM_BS                 = 509;
    private final static int PS_NET_DOWN_REASON_REJECTED_BY_BS                      = 510;
    private final static int PS_NET_DOWN_REASON_INCOMPATIBLE                        = 511;
    private final static int PS_NET_DOWN_REASON_ALREADY_IN_TC                       = 512;
    private final static int PS_NET_DOWN_REASON_USER_CALL_ORIG_DURING_GPS           = 513;
    private final static int PS_NET_DOWN_REASON_USER_CALL_ORIG_DURING_SMS           = 514;
    private final static int PS_NET_DOWN_REASON_NO_CDMA_SRV                         = 515;
    private final static int PS_NET_DOWN_REASON_CONF_FAILED                         = 1000;
    private final static int PS_NET_DOWN_REASON_INCOM_REJ                           = 1001;
    private final static int PS_NET_DOWN_REASON_NO_GW_SRV                           = 1002;
    private final static int PS_NET_DOWN_REASON_CD_GEN_OR_BUSY                      = 1500;
    private final static int PS_NET_DOWN_REASON_CD_BILL_OR_AUTH                     = 1501;
    private final static int PS_NET_DOWN_REASON_CHG_HDR                             = 1502;
    private final static int PS_NET_DOWN_REASON_EXIT_HDR                            = 1503;
    private final static int PS_NET_DOWN_REASON_HDR_NO_SESSION                      = 1504;
    private final static int PS_NET_DOWN_REASON_HDR_ORIG_DURING_GPS_FIX             = 1505;
    private final static int PS_NET_DOWN_REASON_HDR_CS_TIMEOUT                      = 1506;
    private final static int PS_NET_DOWN_REASON_HDR_RELEASED_BY_CM                  = 1507;
    private final static int PS_NET_DOWN_REASON_CLIENT_END                          = 2000;
    private final static int PS_NET_DOWN_REASON_NO_SRV                              = 2001;
    private final static int PS_NET_DOWN_REASON_FADE                                = 2002;
    private final static int PS_NET_DOWN_REASON_REL_NORMAL                          = 2003;
    private final static int PS_NET_DOWN_REASON_ACC_IN_PROG                         = 2004;
    private final static int PS_NET_DOWN_REASON_ACC_FAIL                            = 2005;
    private final static int PS_NET_DOWN_REASON_REDIR_OR_HANDOFF                    = 2006;

    // ***** Instance Variables

    // ***** Constructor
    CdmaDataConnection(CDMAPhone phone) {
        super(phone);

        if (DBG) log("CdmaDataConnection <constructor>");
    }

    /**
     * Setup a data connection
     *
     * @param onCompleted
     *            notify success or not after down
     */
    void connect(Message onCompleted) {
        if (DBG) log("CdmaDataConnection Connecting...");

        state = State.ACTIVATING;
        onConnectCompleted = onCompleted;
        createTime = -1;
        lastFailTime = -1;
        lastFailCause = FailCause.NONE;
        receivedDisconnectReq = false;
        phone.mCM.setupDataCall(Integer.toString(RILConstants.CDMA_PHONE), null, null, null,
                null, obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE));
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


    public String toString() {
        return "State=" + state + " create=" + createTime + " lastFail="
                + lastFailTime + " lastFailCause=" + lastFailCause;
    }


    protected void notifyFail(FailCause cause, Message onCompleted) {
        if (onCompleted == null) {
            return;
        }
        state = State.INACTIVE;
        lastFailCause = cause;
        lastFailTime = System.currentTimeMillis();
        onConnectCompleted = null;

        if(DBG) {
            log("Notify data connection fail at " + lastFailTime +
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

        if (DBG) log("Notify data connection success at " + createTime);

        AsyncResult.forMessage(onCompleted);
        onCompleted.sendToTarget();
    }

    protected void notifyDisconnect(Message msg) {
        if (DBG) log("Notify data connection disconnect");

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
                phone.mCM.getLastDataCallFailCause(obtainMessage(EVENT_GET_LAST_FAIL_DONE));
                break;
        }
    }

    protected FailCause getFailCauseFromRequest(int rilCause) {
        FailCause cause;

        switch (rilCause) {
            case PS_NET_DOWN_REASON_OPERATOR_DETERMINED_BARRING:
                cause = FailCause.OPERATOR_BARRED;
                break;
            case PS_NET_DOWN_REASON_AUTH_FAILED:
                cause = FailCause.USER_AUTHENTICATION;
                break;
            case PS_NET_DOWN_REASON_OPTION_NOT_SUPPORTED:
                cause = FailCause.SERVICE_OPTION_NOT_SUPPORTED;
                break;
            case PS_NET_DOWN_REASON_OPTION_UNSUBSCRIBED:
                cause = FailCause.SERVICE_OPTION_NOT_SUBSCRIBED;
                break;
            default:
                cause = FailCause.UNKNOWN;
        }
        return cause;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaDataConnection] " + s);
    }

    @Override
    protected void onDeactivated(AsyncResult ar) {
        notifyDisconnect((Message) ar.userObj);
        if (DBG) log("CDMA Connection Deactivated");
    }

    @Override
    protected void onSetupConnectionCompleted(AsyncResult ar) {
        if (ar.exception != null) {
            Log.e(LOG_TAG, "CdmaDataConnection Init failed " + ar.exception);

            if (receivedDisconnectReq) {
                // Don't bother reporting the error if there's already a
                // pending disconnect request, since DataConnectionTracker
                // has already updated its state.
                notifyDisconnect(onDisconnect);
            } else {
                if (ar.exception instanceof CommandException
                        && ((CommandException) (ar.exception)).getCommandError()
                        == CommandException.Error.RADIO_NOT_AVAILABLE) {
                    notifyFail(FailCause.RADIO_NOT_AVAILABLE, onConnectCompleted);
                } else {
                    phone.mCM.getLastDataCallFailCause(obtainMessage(EVENT_GET_LAST_FAIL_DONE));
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
                                        && !((CDMAPhone) phone).isDnsCheckDisabled()) {
                        // Work around a race condition where QMI does not fill in DNS:
                        // Deactivate PDP and let DataConnectionTracker retry.
                        EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_BAD_DNS_ADDRESS,
                                    dnsServers[0]);
                        phone.mCM.deactivateDataCall(cid, obtainMessage(EVENT_FORCE_RETRY));
                        return;
                    }
                }

                onLinkStateChanged(DataLink.LinkState.LINK_UP);

                if (DBG) log("CdmaDataConnection setup on cid = " + cid);
            }
        }
    }
}
