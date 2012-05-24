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

import com.android.internal.telephony.DataConnection.UpdateLinkPropertyResult;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import android.app.PendingIntent;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.ProxyProperties;
import android.os.Message;

import java.util.ArrayList;
import java.util.Collection;

/**
 * AsyncChannel to a DataConnection
 */
public class DataConnectionAc extends AsyncChannel {
    private static final boolean DBG = false;
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

    public static final int REQ_GET_REFCOUNT = BASE + 16;
    public static final int RSP_GET_REFCOUNT = BASE + 17;

    public static final int REQ_ADD_APNCONTEXT = BASE + 18;
    public static final int RSP_ADD_APNCONTEXT = BASE + 19;

    public static final int REQ_REMOVE_APNCONTEXT = BASE + 20;
    public static final int RSP_REMOVE_APNCONTEXT = BASE + 21;

    public static final int REQ_GET_APNCONTEXT_LIST = BASE + 22;
    public static final int RSP_GET_APNCONTEXT_LIST = BASE + 23;

    public static final int REQ_SET_RECONNECT_INTENT = BASE + 24;
    public static final int RSP_SET_RECONNECT_INTENT = BASE + 25;

    public static final int REQ_GET_RECONNECT_INTENT = BASE + 26;
    public static final int RSP_GET_RECONNECT_INTENT = BASE + 27;

    private static final int CMD_TO_STRING_COUNT = RSP_GET_RECONNECT_INTENT - BASE + 1;
    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[REQ_IS_INACTIVE - BASE] = "REQ_IS_INACTIVE";
        sCmdToString[RSP_IS_INACTIVE - BASE] = "RSP_IS_INACTIVE";
        sCmdToString[REQ_GET_CID - BASE] = "REQ_GET_CID";
        sCmdToString[RSP_GET_CID - BASE] = "RSP_GET_CID";
        sCmdToString[REQ_GET_APNSETTING - BASE] = "REQ_GET_APNSETTING";
        sCmdToString[RSP_GET_APNSETTING - BASE] = "RSP_GET_APNSETTING";
        sCmdToString[REQ_GET_LINK_PROPERTIES - BASE] = "REQ_GET_LINK_PROPERTIES";
        sCmdToString[RSP_GET_LINK_PROPERTIES - BASE] = "RSP_GET_LINK_PROPERTIES";
        sCmdToString[REQ_SET_LINK_PROPERTIES_HTTP_PROXY - BASE] =
                "REQ_SET_LINK_PROPERTIES_HTTP_PROXY";
        sCmdToString[RSP_SET_LINK_PROPERTIES_HTTP_PROXY - BASE] =
                "RSP_SET_LINK_PROPERTIES_HTTP_PROXY";
        sCmdToString[REQ_GET_LINK_CAPABILITIES - BASE] = "REQ_GET_LINK_CAPABILITIES";
        sCmdToString[RSP_GET_LINK_CAPABILITIES - BASE] = "RSP_GET_LINK_CAPABILITIES";
        sCmdToString[REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE - BASE] =
                "REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE";
        sCmdToString[RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE - BASE] =
                "RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE";
        sCmdToString[REQ_RESET - BASE] = "REQ_RESET";
        sCmdToString[RSP_RESET - BASE] = "RSP_RESET";
        sCmdToString[REQ_GET_REFCOUNT - BASE] = "REQ_GET_REFCOUNT";
        sCmdToString[RSP_GET_REFCOUNT - BASE] = "RSP_GET_REFCOUNT";
        sCmdToString[REQ_ADD_APNCONTEXT - BASE] = "REQ_ADD_APNCONTEXT";
        sCmdToString[RSP_ADD_APNCONTEXT - BASE] = "RSP_ADD_APNCONTEXT";
        sCmdToString[REQ_REMOVE_APNCONTEXT - BASE] = "REQ_REMOVE_APNCONTEXT";
        sCmdToString[RSP_REMOVE_APNCONTEXT - BASE] = "RSP_REMOVE_APNCONTEXT";
        sCmdToString[REQ_GET_APNCONTEXT_LIST - BASE] = "REQ_GET_APNCONTEXT_LIST";
        sCmdToString[RSP_GET_APNCONTEXT_LIST - BASE] = "RSP_GET_APNCONTEXT_LIST";
        sCmdToString[REQ_SET_RECONNECT_INTENT - BASE] = "REQ_SET_RECONNECT_INTENT";
        sCmdToString[RSP_SET_RECONNECT_INTENT - BASE] = "RSP_SET_RECONNECT_INTENT";
        sCmdToString[REQ_GET_RECONNECT_INTENT - BASE] = "REQ_GET_RECONNECT_INTENT";
        sCmdToString[RSP_GET_RECONNECT_INTENT - BASE] = "RSP_GET_RECONNECT_INTENT";
    }
    protected static String cmdToString(int cmd) {
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            return sCmdToString[cmd];
        } else {
            return AsyncChannel.cmdToString(cmd + BASE);
        }
    }

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
     * Request the Reference Count.
     * Response {@link #rspRefCount}
     */
    public void reqRefCount() {
        sendMessage(REQ_GET_REFCOUNT);
        if (DBG) log("reqRefCount");
    }

    /**
     * Evaluate a RSP_GET_REFCOUNT message and return the refCount.
     *
     * @param response Message
     * @return ref count or -1 if an error
     */
    public int rspRefCount(Message response) {
        int retVal = response.arg1;
        if (DBG) log("rspRefCount=" + retVal);
        return retVal;
    }

    /**
     * @return connection id or -1 if an error
     */
    public int getRefCountSync() {
        Message response = sendMessageSynchronously(REQ_GET_REFCOUNT);
        if ((response != null) && (response.what == RSP_GET_REFCOUNT)) {
            return rspRefCount(response);
        } else {
            log("rspRefCount error response=" + response);
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

    public UpdateLinkPropertyResult rspUpdateLinkPropertiesDataCallState(Message response) {
        UpdateLinkPropertyResult retVal = (UpdateLinkPropertyResult)response.obj;
        if (DBG) log("rspUpdateLinkPropertiesState: retVal=" + retVal);
        return retVal;
    }

    /**
     * Update link properties in the data connection
     *
     * @return the removed and added addresses.
     */
    public UpdateLinkPropertyResult updateLinkPropertiesDataCallStateSync(DataCallState newState) {
        Message response =
            sendMessageSynchronously(REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE, newState);
        if ((response != null) &&
            (response.what == RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE)) {
            return rspUpdateLinkPropertiesDataCallState(response);
        } else {
            log("getLinkProperties error response=" + response);
            return new UpdateLinkPropertyResult(new LinkProperties());
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
            log("restSync error response=" + response);
        }
    }

    /**
     * Request to add ApnContext association.
     * Response RSP_ADD_APNCONTEXT when complete.
     */
    public void reqAddApnContext(ApnContext apnContext) {
        Message response = sendMessageSynchronously(REQ_ADD_APNCONTEXT, apnContext);
        if (DBG) log("reqAddApnContext");
    }

    /**
     * Add ApnContext association synchronoulsy.
     *
     * @param ApnContext to associate
     */
    public void addApnContextSync(ApnContext apnContext) {
        Message response = sendMessageSynchronously(REQ_ADD_APNCONTEXT, apnContext);
        if ((response != null) && (response.what == RSP_ADD_APNCONTEXT)) {
            if (DBG) log("addApnContext ok");
        } else {
            log("addApnContext error response=" + response);
        }
    }

    /**
     * Request to remove ApnContext association.
     * Response RSP_REMOVE_APNCONTEXT when complete.
     */
    public void reqRemomveApnContext(ApnContext apnContext) {
        Message response = sendMessageSynchronously(REQ_REMOVE_APNCONTEXT, apnContext);
        if (DBG) log("reqRemomveApnContext");
    }

    /**
     * Remove ApnContext associateion.
     *
     * @param ApnContext to dissociate
     */
    public void removeApnContextSync(ApnContext apnContext) {
        Message response = sendMessageSynchronously(REQ_REMOVE_APNCONTEXT, apnContext);
        if ((response != null) && (response.what == RSP_REMOVE_APNCONTEXT)) {
            if (DBG) log("removeApnContext ok");
        } else {
            log("removeApnContext error response=" + response);
        }
    }

    /**
     * Request to retrive ApnContext List associated with DC.
     * Response RSP_GET_APNCONTEXT_LIST when complete.
     */
    public void reqGetApnList(ApnContext apnContext) {
        Message response = sendMessageSynchronously(REQ_GET_APNCONTEXT_LIST);
        if (DBG) log("reqGetApnList");
    }

    /**
     * Retrieve Collection of ApnContext from the response message.
     *
     * @param Message sent from DC in response to REQ_GET_APNCONTEXT_LIST.
     * @return Collection of ApnContext
     */
    public Collection<ApnContext> rspApnList(Message response) {
        Collection<ApnContext> retVal = (Collection<ApnContext>)response.obj;
        if (retVal == null) retVal = new ArrayList<ApnContext>();
        return retVal;
    }

    /**
     * Retrieve collection of ApnContext currently associated with
     * the DataConnectionA synchronously.
     *
     * @return Collection of ApnContext
     */
    public Collection<ApnContext> getApnListSync() {
        Message response = sendMessageSynchronously(REQ_GET_APNCONTEXT_LIST);
        if ((response != null) && (response.what == RSP_GET_APNCONTEXT_LIST)) {
            if (DBG) log("getApnList ok");
            return rspApnList(response);
        } else {
            log("getApnList error response=" + response);
            // return dummy list with no entry
            return new ArrayList<ApnContext>();
        }
    }

    /**
     * Request to set Pending ReconnectIntent to DC.
     * Response RSP_SET_RECONNECT_INTENT when complete.
     */
    public void reqSetReconnectIntent(PendingIntent intent) {
        Message response = sendMessageSynchronously(REQ_SET_RECONNECT_INTENT, intent);
        if (DBG) log("reqSetReconnectIntent");
    }

    /**
     * Set pending reconnect intent to DC synchronously.
     *
     * @param PendingIntent to set.
     */
    public void setReconnectIntentSync(PendingIntent intent) {
        Message response = sendMessageSynchronously(REQ_SET_RECONNECT_INTENT, intent);
        if ((response != null) && (response.what == RSP_SET_RECONNECT_INTENT)) {
            if (DBG) log("setReconnectIntent ok");
        } else {
            log("setReconnectIntent error response=" + response);
        }
    }

    /**
     * Request to get Pending ReconnectIntent to DC.
     * Response RSP_GET_RECONNECT_INTENT when complete.
     */
    public void reqGetReconnectIntent() {
        Message response = sendMessageSynchronously(REQ_GET_RECONNECT_INTENT);
        if (DBG) log("reqGetReconnectIntent");
    }

    /**
     * Retrieve reconnect intent from response message from DC.
     *
     * @param Message which contains the reconnect intent.
     * @return PendingIntent from the response.
     */
    public PendingIntent rspReconnectIntent(Message response) {
        PendingIntent retVal = (PendingIntent) response.obj;
        return retVal;
    }

    /**
     * Retrieve reconnect intent currently set in DC synchronously.
     *
     * @return PendingIntent reconnect intent current ly set in DC
     */
    public PendingIntent getReconnectIntentSync() {
        Message response = sendMessageSynchronously(REQ_GET_RECONNECT_INTENT);
        if ((response != null) && (response.what == RSP_GET_RECONNECT_INTENT)) {
            if (DBG) log("getReconnectIntent ok");
            return rspReconnectIntent(response);
        } else {
            log("getReconnectIntent error response=" + response);
            return null;
        }
    }

    @Override
    public String toString() {
        return dataConnection.getName();
    }

    private void log(String s) {
        android.util.Log.d(mLogTag, "DataConnectionAc " + s);
    }
}
