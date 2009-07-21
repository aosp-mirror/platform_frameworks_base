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

package com.android.internal.telephony;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * {@hide}
 */
public abstract class DataConnection extends Handler {

    // the inherited class

    public enum State {
        ACTIVE, /* has active data connection */
        ACTIVATING, /* during connecting process */
        INACTIVE; /* has empty data connection */

        public String toString() {
            switch (this) {
            case ACTIVE:
                return "active";
            case ACTIVATING:
                return "setting up";
            default:
                return "inactive";
            }
        }

        public boolean isActive() {
            return this == ACTIVE;
        }

        public boolean isInactive() {
            return this == INACTIVE;
        }
    }

    public enum FailCause {
        NONE,
        OPERATOR_BARRED,
        INSUFFICIENT_RESOURCES,
        MISSING_UKNOWN_APN,
        UNKNOWN_PDP_ADDRESS,
        USER_AUTHENTICATION,
        ACTIVATION_REJECT_GGSN,
        ACTIVATION_REJECT_UNSPECIFIED,
        SERVICE_OPTION_NOT_SUPPORTED,
        SERVICE_OPTION_NOT_SUBSCRIBED,
        SERVICE_OPTION_OUT_OF_ORDER,
        NSAPI_IN_USE,
        PROTOCOL_ERRORS,
        REGISTRATION_FAIL,
        GPRS_REGISTRATION_FAIL,
        UNKNOWN,

        RADIO_NOT_AVAILABLE,
        RADIO_ERROR_RETRY;

        public boolean isPermanentFail() {
            return (this == OPERATOR_BARRED) || (this == MISSING_UKNOWN_APN) ||
                   (this == UNKNOWN_PDP_ADDRESS) || (this == USER_AUTHENTICATION) ||
                   (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
                   (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                   (this == SERVICE_OPTION_NOT_SUBSCRIBED) || (this == NSAPI_IN_USE) ||
                   (this == PROTOCOL_ERRORS);
        }

        public boolean isEventLoggable() {
            return (this == OPERATOR_BARRED) || (this == INSUFFICIENT_RESOURCES) ||
                    (this == UNKNOWN_PDP_ADDRESS) || (this == USER_AUTHENTICATION) ||
                    (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
                    (this == SERVICE_OPTION_NOT_SUBSCRIBED) ||
                    (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                    (this == SERVICE_OPTION_OUT_OF_ORDER) || (this == NSAPI_IN_USE) ||
                    (this == PROTOCOL_ERRORS);
        }

        @Override
        public String toString() {
            switch (this) {
            case NONE:
                return "No Error";
            case OPERATOR_BARRED:
                return "Operator Barred";
            case INSUFFICIENT_RESOURCES:
                return "Insufficient Resources";
            case MISSING_UKNOWN_APN:
                return "Missing / Unknown APN";
            case UNKNOWN_PDP_ADDRESS:
                return "Unknown PDP Address";
            case USER_AUTHENTICATION:
                return "Error User Autentication";
            case ACTIVATION_REJECT_GGSN:
                return "Activation Reject GGSN";
            case ACTIVATION_REJECT_UNSPECIFIED:
                return "Activation Reject unspecified";
            case SERVICE_OPTION_NOT_SUPPORTED:
                return "Data Not Supported";
            case SERVICE_OPTION_NOT_SUBSCRIBED:
                return "Data Not subscribed";
            case SERVICE_OPTION_OUT_OF_ORDER:
                return "Data Services Out of Order";
            case NSAPI_IN_USE:
                return "NSAPI in use";
            case PROTOCOL_ERRORS:
                return "Protocol Errors";
            case REGISTRATION_FAIL:
                return "Network Registration Failure";
            case GPRS_REGISTRATION_FAIL:
                return "Data Network Registration Failure";
            case RADIO_NOT_AVAILABLE:
                return "Radio Not Available";
            case RADIO_ERROR_RETRY:
                return "Transient Radio Rrror";
            default:
                return "Unknown Data Error";
            }
        }
    }

    // ***** Event codes
    protected static final int EVENT_SETUP_DATA_CONNECTION_DONE = 1;
    protected static final int EVENT_GET_LAST_FAIL_DONE = 2;
    protected static final int EVENT_LINK_STATE_CHANGED = 3;
    protected static final int EVENT_DEACTIVATE_DONE = 4;
    protected static final int EVENT_FORCE_RETRY = 5;

    //***** Tag IDs for EventLog
    protected static final int EVENT_LOG_BAD_DNS_ADDRESS = 50100;


    //***** Member Variables
    protected PhoneBase phone;
    protected Message onConnectCompleted;
    protected Message onDisconnect;
    protected int cid;
    protected String interfaceName;
    protected String ipAddress;
    protected String gatewayAddress;
    protected String[] dnsServers;
    protected State state;
    protected long createTime;
    protected long lastFailTime;
    protected FailCause lastFailCause;
    protected static final String NULL_IP = "0.0.0.0";
    Object userData;

    // receivedDisconnectReq is set when disconnect during activation
    protected boolean receivedDisconnectReq;

    /* Instance Methods */
    protected abstract void onSetupConnectionCompleted(AsyncResult ar);

    protected abstract void onDeactivated(AsyncResult ar);

    protected abstract void disconnect(Message msg);

    protected abstract void notifyFail(FailCause cause, Message onCompleted);

    protected abstract void notifyDisconnect(Message msg);

    protected abstract void onLinkStateChanged(DataLink.LinkState linkState);

    protected abstract FailCause getFailCauseFromRequest(int rilCause);

    public abstract String toString();

    protected abstract void log(String s);


   //***** Constructor
    protected DataConnection(PhoneBase phone) {
        super();
        this.phone = phone;
        onConnectCompleted = null;
        onDisconnect = null;
        this.cid = -1;
        receivedDisconnectReq = false;
        this.dnsServers = new String[2];

        clearSettings();
    }

    protected void setHttpProxy(String httpProxy, String httpPort) {
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

    public String getInterface() {
        return interfaceName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getGatewayAddress() {
        return gatewayAddress;
    }

    public String[] getDnsServers() {
        return dnsServers;
    }

    public void clearSettings() {
        log("DataConnection.clearSettings()");

        this.state = State.INACTIVE;
        this.createTime = -1;
        this.lastFailTime = -1;
        this.lastFailCause = FailCause.NONE;

        receivedDisconnectReq = false;
        onConnectCompleted = null;
        interfaceName = null;
        ipAddress = null;
        gatewayAddress = null;
        dnsServers[0] = null;
        dnsServers[1] = null;
    }

    protected void onGetLastFailCompleted(AsyncResult ar) {
        if (receivedDisconnectReq) {
            // Don't bother reporting the error if there's already a
            // pending disconnect request, since DataConnectionTracker
            // has already updated its state.
            notifyDisconnect(onDisconnect);
        } else {
            FailCause cause = FailCause.UNKNOWN;

            if (ar.exception == null) {
                int rilFailCause = ((int[]) (ar.result))[0];
                cause = getFailCauseFromRequest(rilFailCause);
            }
            notifyFail(cause, onConnectCompleted);
        }
    }

    protected void onForceRetry() {
        if (receivedDisconnectReq) {
            notifyDisconnect(onDisconnect);
        } else {
            notifyFail(FailCause.RADIO_ERROR_RETRY, onConnectCompleted);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        log("DataConnection.handleMessage()");

        switch (msg.what) {

        case EVENT_SETUP_DATA_CONNECTION_DONE:
            onSetupConnectionCompleted((AsyncResult) msg.obj);
            break;

        case EVENT_FORCE_RETRY:
            onForceRetry();
            break;

        case EVENT_GET_LAST_FAIL_DONE:
            onGetLastFailCompleted((AsyncResult) msg.obj);
            break;

        case EVENT_LINK_STATE_CHANGED:
            ar = (AsyncResult) msg.obj;
            DataLink.LinkState ls  = (DataLink.LinkState) ar.result;
            onLinkStateChanged(ls);
            break;

        case EVENT_DEACTIVATE_DONE:
            onDeactivated((AsyncResult) msg.obj);
            break;
        }
    }

    public State getState() {
        log("DataConnection.getState()");
        return state;
    }

    public long getConnectionTime() {
        log("DataConnection.getConnectionTime()");
        return createTime;
    }

    public long getLastFailTime() {
        log("DataConnection.getLastFailTime()");
        return lastFailTime;
    }

    public FailCause getLastFailCause() {
        log("DataConnection.getLastFailCause()");
        return lastFailCause;
    }
}
