/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.internal.telephony.mockril;

import android.os.Bundle;
import android.util.Log;

import com.android.internal.communication.MsgHeader;
import com.android.internal.communication.Msg;
import com.android.internal.telephony.RilChannel;
import com.android.internal.telephony.ril_proto.RilCtrlCmds;
import com.android.internal.telephony.ril_proto.RilCmds;
import com.google.protobuf.micro.MessageMicro;

import java.io.IOException;

/**
 * Contain a list of commands to control Mock RIL. Before using these commands the devices
 * needs to be set with Mock RIL. Refer to hardware/ril/mockril/README.txt for details.
 *
 */
public class MockRilController {
    private static final String TAG = "MockRILController";
    private RilChannel mRilChannel = null;
    private Msg mMessage = null;

    public MockRilController() throws IOException {
        mRilChannel = RilChannel.makeRilChannel();
    }

    /**
     * Close the channel after the communication is done.
     * This method has to be called after the test is finished.
     */
    public void closeChannel() {
        mRilChannel.close();
    }

    /**
     * Send commands and return true on success
     * @param cmd for MsgHeader
     * @param token for MsgHeader
     * @param status for MsgHeader
     * @param pbData for Msg data
     * @return true if command is sent successfully, false if it fails
     */
    private boolean sendCtrlCommand(int cmd, long token, int status, MessageMicro pbData) {
        try {
            Msg.send(mRilChannel, cmd, token, status, pbData);
        } catch (IOException e) {
            Log.v(TAG, "send command : %d failed: " + e.getStackTrace());
            return false;
        }
        return true;
    }

    /**
     * Get control response
     * @return Msg if response is received, else return null.
     */
    private Msg getCtrlResponse() {
        Msg response = null;
        try {
            response = Msg.recv(mRilChannel);
        } catch (IOException e) {
            Log.v(TAG, "receive response for getRadioState() error: " + e.getStackTrace());
            return null;
        }
        return response;
    }

    /**
     * @return the radio state if it is valid, otherwise return -1
     */
    public int getRadioState() {
        if (!sendCtrlCommand(RilCtrlCmds.CTRL_CMD_GET_RADIO_STATE, 0, 0, null)) {
            return -1;
        }
        Msg response = getCtrlResponse();
        if (response == null) {
            Log.v(TAG, "failed to get response");
            return -1;
        }
        response.printHeader(TAG);
        RilCtrlCmds.CtrlRspRadioState resp =
            response.getDataAs(RilCtrlCmds.CtrlRspRadioState.class);
        int state = resp.getState();
        if ((state >= RilCmds.RADIOSTATE_OFF) && (state <= RilCmds.RADIOSTATE_NV_READY))
            return state;
        else
            return -1;
    }

    /**
     * Set the radio state of mock ril to the given state
     * @param state for given radio state
     * @return true if the state is set successful, false if it fails
     */
    public boolean setRadioState(int state) {
        RilCtrlCmds.CtrlReqRadioState req = new RilCtrlCmds.CtrlReqRadioState();
        if (state < 0 || state > RilCmds.RADIOSTATE_NV_READY) {
            Log.v(TAG, "the give radio state is not valid.");
            return false;
        }
        req.setState(state);
        if (!sendCtrlCommand(RilCtrlCmds.CTRL_CMD_SET_RADIO_STATE, 0, 0, req)) {
            Log.v(TAG, "send set radio state request failed.");
            return false;
        }
        Msg response = getCtrlResponse();
        if (response == null) {
            Log.v(TAG, "failed to get response for setRadioState");
            return false;
        }
        response.printHeader(TAG);
        RilCtrlCmds.CtrlRspRadioState resp =
            response.getDataAs(RilCtrlCmds.CtrlRspRadioState.class);
        int curstate = resp.getState();
        return curstate == state;
    }

    /**
     * Start an incoming call for the given phone number
     *
     * @param phoneNumber is the number to show as incoming call
     * @return true if the incoming call is started successfully, false if it fails.
     */
    public boolean startIncomingCall(String phoneNumber) {
        RilCtrlCmds.CtrlReqSetMTCall req = new RilCtrlCmds.CtrlReqSetMTCall();

        req.setPhoneNumber(phoneNumber);
        if (!sendCtrlCommand(RilCtrlCmds.CTRL_CMD_SET_MT_CALL, 0, 0, req)) {
            Log.v(TAG, "send CMD_SET_MT_CALL request failed");
            return false;
        }
        return true;
    }

    /**
     * Hang up a connection remotelly for the given call fail cause
     *
     * @param connectionID is the connection to be hung up
     * @param failCause is the call fail cause defined in ril.h
     * @return true if the hangup is successful, false if it fails
     */
    public boolean hangupRemote(int connectionId, int failCause) {
        RilCtrlCmds.CtrlHangupConnRemote req = new RilCtrlCmds.CtrlHangupConnRemote();
        req.setConnectionId(connectionId);
        req.setCallFailCause(failCause);

        if (!sendCtrlCommand(RilCtrlCmds.CTRL_CMD_HANGUP_CONN_REMOTE, 0, 0, req)) {
            Log.v(TAG, "send CTRL_CMD_HANGUP_CONN_REMOTE request failed");
            return false;
        }
        return true;
    }

    /**
     * Set call transition flag to the Mock Ril
     *
     * @param flag is a boolean value for the call transiton flag
     *             true: call transition: dialing->alert, alert->active is controlled
     *             false: call transition is automatically handled by Mock Ril
     * @return true if the request is successful, false if it failed to set the flag
     */
    public boolean setCallTransitionFlag(boolean flag) {
        RilCtrlCmds.CtrlSetCallTransitionFlag req = new RilCtrlCmds.CtrlSetCallTransitionFlag();

        req.setFlag(flag);

        if (!sendCtrlCommand(RilCtrlCmds.CTRL_CMD_SET_CALL_TRANSITION_FLAG, 0, 0, req)) {
            Log.v(TAG, "send CTRL_CMD_SET_CALL_TRANSITION_FLAG request failed");
            return false;
        }
        return true;
    }

    /**
     * Set the dialing call to alert if the call transition flag is true
     *
     * @return true if the call transition is successful, false if it fails
     */
    public boolean setDialCallToAlert() {
        if (!sendCtrlCommand(RilCtrlCmds.CTRL_CMD_SET_CALL_ALERT, 0, 0, null)) {
            Log.v(TAG, "send CTRL_CMD_SET_CALL_ALERT request failed");
            return false;
        }
        return true;
   }

   /**
    * Set the alert call to active if the call transition flag is true
    *
    * @return true if the call transition is successful, false if it fails
    */
   public boolean setAlertCallToActive() {
        if (!sendCtrlCommand(RilCtrlCmds.CTRL_CMD_SET_CALL_ACTIVE, 0, 0, null)) {
            Log.v(TAG, "send CTRL_CMD_SET_CALL_ACTIVE request failed");
            return false;
        }
        return true;
   }
}
