/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.ProxyProperties;
import android.os.Message;

/**
 * AsyncChannel to a DataConnection
 */
public class DataConnectionAc extends AsyncChannel {
    private static final boolean DBG = true;
    private String mLogTag;

    public DataConnection dataConnection;

    public static final int BASE = Protocol.BASE_DATA_CONNECTION_AC;

    public static final int REQ_IS_INACTIVE = BASE + 0;
    public static final int RSP_IS_INACTIVE = BASE + 1;

    public static final int REQ_GET_CID = BASE + 2;
    public static final int RSP_GET_CID = BASE + 3;

    public static final int REQ_GET_APNSETTING = BASE + 4;
    public static final int RSP_GET_APNSETTING = BASE + 5;

    public static final int REQ_GET_LINK_PROPERTIES = BASE + 6;
    public static final int RSP_GET_LINK_PROPERTIES = BASE + 7;

    public static final int REQ_SET_LINK_PROPERTIES_HTTP_PROXY = BASE + 8;
    public static final int RSP_SET_LINK_PROPERTIES_HTTP_PROXY = BASE + 9;

    public static final int REQ_GET_LINK_CAPABILITIES = BASE + 10;
    public static final int RSP_GET_LINK_CAPABILITIES = BASE + 11;

    public static final int REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE = BASE + 12;
    public static final int RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE = BASE + 13;

    public static final int REQ_RESET = BASE + 14;
    public static final int RSP_RESET = BASE + 15;

    /**
     * enum used to notify action taken or necessary to be
     * taken after the link property is changed.
     */
    public enum LinkPropertyChangeAction {
        NONE, CHANGED, RESET;

        public static LinkPropertyChangeAction fromInt(int value) {
            if (value == NONE.ordinal()) {
                return NONE;
            } else if (value == CHANGED.ordinal()) {
                return CHANGED;
            } else if (value == RESET.ordinal()) {
                return RESET;
            } else {
                throw new RuntimeException("LinkPropertyChangeAction.fromInt: bad value=" + value);
            }
        }
    }

    public DataConnectionAc(DataConnection dc, String logTag) {
        dataConnection = dc;
        mLogTag = logTag;
    }

    /**
     * Request if the state machine is in the inactive state.
     * Response {@link #rspIsInactive}
     */
    public void reqIsInactive() {
        sendMessage(REQ_IS_INACTIVE);
        if (DBG) log("reqIsInactive");
    }

    /**
     * Evaluate RSP_IS_INACTIVE.
     *
     * @return true if the state machine is in the inactive state.
     */
    public boolean rspIsInactive(Message response) {
        boolean retVal = response.arg1 == 1;
        if (DBG) log("rspIsInactive=" + retVal);
        return retVal;
    }

    /**
     * @return true if the state machine is in the inactive state.
     */
    public boolean isInactiveSync() {
        Message response = sendMessageSynchronously(REQ_IS_INACTIVE);
        if ((response != null) && (response.what == RSP_IS_INACTIVE)) {
            return rspIsInactive(response);
        } else {
            log("rspIsInactive error response=" + response);
            return false;
        }
    }

    /**
     * Request the Connection ID.
     * Response {@link #rspCid}
     */
    public void reqCid() {
        sendMessage(REQ_GET_CID);
        if (DBG) log("reqCid");
    }

    /**
     * Evaluate a RSP_GET_CID message and return the cid.
     *
     * @param response Message
     * @return connection id or -1 if an error
     */
    public int rspCid(Message response) {
        int retVal = response.arg1;
        if (DBG) log("rspCid=" + retVal);
        return retVal;
    }

    /**
     * @return connection id or -1 if an error
     */
    public int getCidSync() {
        Message response = sendMessageSynchronously(REQ_GET_CID);
        if ((response != null) && (response.what == RSP_GET_CID)) {
            return rspCid(response);
        } else {
            log("rspCid error response=" + response);
            return -1;
        }
    }

    /**
     * Request the connections ApnSetting.
     * Response {@link #rspApnSetting}
     */
    public void reqApnSetting() {
        sendMessage(REQ_GET_APNSETTING);
        if (DBG) log("reqApnSetting");
    }

    /**
     * Evaluate a RSP_APN_SETTING message and return the ApnSetting.
     *
     * @param response Message
     * @return ApnSetting, maybe null
     */
    public ApnSetting rspApnSetting(Message response) {
        ApnSetting retVal = (ApnSetting) response.obj;
        if (DBG) log("rspApnSetting=" + retVal);
        return retVal;
    }

    /**
     * Get the connections ApnSetting.
     *
     * @return ApnSetting or null if an error
     */
    public ApnSetting getApnSettingSync() {
        Message response = sendMessageSynchronously(REQ_GET_APNSETTING);
        if ((response != null) && (response.what == RSP_GET_APNSETTING)) {
            return rspApnSetting(response);
        } else {
            log("getApnSetting error response=" + response);
            return null;
        }
    }

    /**
     * Request the connections LinkProperties.
     * Response {@link #rspLinkProperties}
     */
    public void reqLinkProperties() {
        sendMessage(REQ_GET_LINK_PROPERTIES);
        if (DBG) log("reqLinkProperties");
    }

    /**
     * Evaluate RSP_GET_LINK_PROPERTIES
     *
     * @param response
     * @return LinkProperties, maybe null.
     */
    public LinkProperties rspLinkProperties(Message response) {
        LinkProperties retVal = (LinkProperties) response.obj;
        if (DBG) log("rspLinkProperties=" + retVal);
        return retVal;
    }

    /**
     * Get the connections LinkProperties.
     *
     * @return LinkProperties or null if an error
     */
    public LinkProperties getLinkPropertiesSync() {
        Message response = sendMessageSynchronously(REQ_GET_LINK_PROPERTIES);
        if ((response != null) && (response.what == RSP_GET_LINK_PROPERTIES)) {
            return rspLinkProperties(response);
        } else {
            log("getLinkProperties error response=" + response);
            return null;
        }
    }

    /**
     * Request setting the connections LinkProperties.HttpProxy.
     * Response RSP_SET_LINK_PROPERTIES when complete.
     */
    public void reqSetLinkPropertiesHttpProxy(ProxyProperties proxy) {
        sendMessage(REQ_SET_LINK_PROPERTIES_HTTP_PROXY, proxy);
        if (DBG) log("reqSetLinkPropertiesHttpProxy proxy=" + proxy);
    }

    /**
     * Set the connections LinkProperties.HttpProxy
     */
    public void setLinkPropertiesHttpProxySync(ProxyProperties proxy) {
        Message response =
            sendMessageSynchronously(REQ_SET_LINK_PROPERTIES_HTTP_PROXY, proxy);
        if ((response != null) && (response.what == RSP_SET_LINK_PROPERTIES_HTTP_PROXY)) {
            if (DBG) log("setLinkPropertiesHttpPoxy ok");
        } else {
            log("setLinkPropertiesHttpPoxy error response=" + response);
        }
    }

    /**
     * Request update LinkProperties from DataCallState
     * Response {@link #rspUpdateLinkPropertiesDataCallState}
     */
    public void reqUpdateLinkPropertiesDataCallState(DataCallState newState) {
        sendMessage(REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE, newState);
        if (DBG) log("reqUpdateLinkPropertiesDataCallState");
    }

    public LinkPropertyChangeAction rspUpdateLinkPropertiesDataCallState(Message response) {
        LinkPropertyChangeAction retVal = LinkPropertyChangeAction.fromInt(response.arg1);
        if (DBG) log("rspUpdateLinkPropertiesState=" + retVal);
        return retVal;
    }

    /**
     * Update link properties in the data connection
     *
     * @return true if link property has been updated. false otherwise.
     */
    public LinkPropertyChangeAction updateLinkPropertiesDataCallStateSync(DataCallState newState) {
        Message response =
            sendMessageSynchronously(REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE, newState);
        if ((response != null) &&
            (response.what == RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE)) {
            return rspUpdateLinkPropertiesDataCallState(response);
        } else {
            log("getLinkProperties error response=" + response);
            return LinkPropertyChangeAction.NONE;
        }
    }

    /**
     * Request the connections LinkCapabilities.
     * Response {@link #rspLinkCapabilities}
     */
    public void reqLinkCapabilities() {
        sendMessage(REQ_GET_LINK_CAPABILITIES);
        if (DBG) log("reqLinkCapabilities");
    }

    /**
     * Evaluate RSP_GET_LINK_CAPABILITIES
     *
     * @param response
     * @return LinkCapabilites, maybe null.
     */
    public LinkCapabilities rspLinkCapabilities(Message response) {
        LinkCapabilities retVal = (LinkCapabilities) response.obj;
        if (DBG) log("rspLinkCapabilities=" + retVal);
        return retVal;
    }

    /**
     * Get the connections LinkCapabilities.
     *
     * @return LinkCapabilities or null if an error
     */
    public LinkCapabilities getLinkCapabilitiesSync() {
        Message response = sendMessageSynchronously(REQ_GET_LINK_CAPABILITIES);
        if ((response != null) && (response.what == RSP_GET_LINK_CAPABILITIES)) {
            return rspLinkCapabilities(response);
        } else {
            log("getLinkCapabilities error response=" + response);
            return null;
        }
    }

    /**
     * Request the connections LinkCapabilities.
     * Response RSP_RESET when complete
     */
    public void reqReset() {
        sendMessage(REQ_RESET);
        if (DBG) log("reqReset");
    }

    /**
     * Reset the connection and wait for it to complete.
     */
    public void resetSync() {
        Message response = sendMessageSynchronously(REQ_RESET);
        if ((response != null) && (response.what == RSP_RESET)) {
            if (DBG) log("restSync ok");
        } else {
            if (DBG) log("restSync error response=" + response);
        }
    }

    private void log(String s) {
        android.util.Log.d(mLogTag, "DataConnectionAc " + s);
    }
}
