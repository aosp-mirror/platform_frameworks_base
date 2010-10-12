/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.util.Log;
import android.test.InstrumentationTestCase;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.android.internal.communication.MsgHeader;
import com.android.internal.communication.Msg;
import com.android.internal.telephony.RilChannel;
import com.android.internal.telephony.ril_proto.RilCtrlCmds;
import com.android.internal.telephony.ril_proto.RilCmds;

import com.android.frameworks.telephonytests.TelephonyMockRilTestRunner;
import com.google.protobuf.micro.InvalidProtocolBufferMicroException;

// Test suite for test ril
public class MockRilTest extends InstrumentationTestCase {
    private static final String TAG = "MockRilTest";

    RilChannel mMockRilChannel;
    TelephonyMockRilTestRunner mRunner;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mRunner = (TelephonyMockRilTestRunner)getInstrumentation();
        mMockRilChannel = mRunner.mMockRilChannel;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    static void log(String s) {
        Log.v(TAG, s);
    }

    /**
     * Test Case 1: Test protobuf serialization and deserialization
     * @throws InvalidProtocolBufferMicroException
     */
    public void testProtobufSerDes() throws InvalidProtocolBufferMicroException {
        log("testProtobufSerdes E");

        RilCtrlCmds.CtrlRspRadioState rs = new RilCtrlCmds.CtrlRspRadioState();
        assertTrue(String.format("expected rs.state == 0 was %d", rs.getState()),
                rs.getState() == 0);
        rs.setState(1);
        assertTrue(String.format("expected rs.state == 1 was %d", rs.getState()),
                rs.getState() == 1);

        byte[] rs_ser = rs.toByteArray();
        RilCtrlCmds.CtrlRspRadioState rsNew = RilCtrlCmds.CtrlRspRadioState.parseFrom(rs_ser);
        assertTrue(String.format("expected rsNew.state == 1 was %d", rs.getState()),
                rs.getState() == 1);

        log("testProtobufSerdes X");
    }

    /**
     * Test case 2: Test echo command works using writeMsg & readMsg
     */
    public void testEchoMsg() throws IOException {
        log("testEchoMsg E");

        MsgHeader mh = new MsgHeader();
        mh.setCmd(0);
        mh.setToken(1);
        mh.setStatus(2);
        ByteBuffer data = ByteBuffer.allocate(3);
        data.put((byte)3);
        data.put((byte)4);
        data.put((byte)5);
        Msg.send(mMockRilChannel, mh, data);

        Msg respMsg = Msg.recv(mMockRilChannel);
        assertTrue(String.format("expected mhd.header.cmd == 0 was %d",respMsg.getCmd()),
                respMsg.getCmd() == 0);
        assertTrue(String.format("expected mhd.header.token == 1 was %d",respMsg.getToken()),
                respMsg.getToken() == 1);
        assertTrue(String.format("expected mhd.header.status == 2 was %d", respMsg.getStatus()),
                respMsg.getStatus() == 2);
        assertTrue(String.format("expected mhd.data[0] == 3 was %d", respMsg.getData(0)),
                respMsg.getData(0) == 3);
        assertTrue(String.format("expected mhd.data[1] == 4 was %d", respMsg.getData(1)),
                respMsg.getData(1) == 4);
        assertTrue(String.format("expected mhd.data[2] == 5 was %d", respMsg.getData(2)),
                respMsg.getData(2) == 5);

        log("testEchoMsg X");
    }

    /**
     * Test case 3: Test get as
     */
    public void testGetAs() {
        log("testGetAs E");

        // Use a message header as the protobuf data content
        MsgHeader mh = new MsgHeader();
        mh.setCmd(12345);
        mh.setToken(9876);
        mh.setStatus(7654);
        mh.setLengthData(4321);
        byte[] data = mh.toByteArray();
        MsgHeader mhResult = Msg.getAs(MsgHeader.class, data);

        assertTrue(String.format("expected cmd == 12345 was %d", mhResult.getCmd()),
                mhResult.getCmd() == 12345);
        assertTrue(String.format("expected token == 9876 was %d", mhResult.getToken()),
                mhResult.getToken() == 9876);
        assertTrue(String.format("expected status == 7654 was %d", mhResult.getStatus()),
                mhResult.getStatus() == 7654);
        assertTrue(String.format("expected lengthData == 4321 was %d", mhResult.getLengthData()),
                mhResult.getLengthData() == 4321);

        Msg msg = Msg.obtain();
        msg.setData(ByteBuffer.wrap(data));

        mhResult = msg.getDataAs(MsgHeader.class);

        assertTrue(String.format("expected cmd == 12345 was %d", mhResult.getCmd()),
                mhResult.getCmd() == 12345);
        assertTrue(String.format("expected token == 9876 was %d", mhResult.getToken()),
                mhResult.getToken() == 9876);
        assertTrue(String.format("expected status == 7654 was %d", mhResult.getStatus()),
                mhResult.getStatus() == 7654);
        assertTrue(String.format("expected lengthData == 4321 was %d", mhResult.getLengthData()),
                mhResult.getLengthData() == 4321);

        log("testGetAs X");
    }

    /**
     * Test case 3: test get radio state
     */
    public void testGetRadioState() throws IOException {
        log("testGetRadioState E");

        Msg.send(mMockRilChannel, 1, 9876, 0, null);

        Msg resp = Msg.recv(mMockRilChannel);
        //resp.printHeader("testGetRadioState");

        assertTrue(String.format("expected cmd == 1 was %d", resp.getCmd()),
                resp.getCmd() == 1);
        assertTrue(String.format("expected token == 9876 was %d", resp.getToken()),
                resp.getToken() == 9876);
        assertTrue(String.format("expected status == 0 was %d", resp.getStatus()),
                resp.getStatus() == 0);

        RilCtrlCmds.CtrlRspRadioState rsp = resp.getDataAs(RilCtrlCmds.CtrlRspRadioState.class);

        int state = rsp.getState();
        log("testGetRadioState state=" + state);
        assertTrue(String.format("expected RadioState >= 0 && RadioState <= 9 was %d", state),
                ((state >= 0) && (state <= 9)));

        log("testGetRadioState X");
    }

    /**
     * Test case 5: test set radio state
     */
    public void testSetRadioState() throws IOException {
        log("testSetRadioState E");

        RilCtrlCmds.CtrlReqRadioState cmdrs = new RilCtrlCmds.CtrlReqRadioState();
        assertEquals(0, cmdrs.getState());

        cmdrs.setState(RilCmds.RADIOSTATE_SIM_NOT_READY);
        assertEquals(2, cmdrs.getState());

        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_SET_RADIO_STATE, 0, 0, cmdrs);

        Msg resp = Msg.recv(mMockRilChannel);
        log("get response status :" + resp.getStatus());
        log("get response for command: " + resp.getCmd());
        log("get command token: " + resp.getToken());

        RilCtrlCmds.CtrlRspRadioState rsp = resp.getDataAs(RilCtrlCmds.CtrlRspRadioState.class);

        int state = rsp.getState();
        log("get response for testSetRadioState: " + state);
        assertTrue(RilCmds.RADIOSTATE_SIM_NOT_READY == state);
    }

    /**
     * Test case 6: test start incoming call and hangup it.
     */
    public void testStartIncomingCallAndHangup() throws IOException {
        log("testStartIncomingCallAndHangup");
        RilCtrlCmds.CtrlReqSetMTCall cmd = new RilCtrlCmds.CtrlReqSetMTCall();
        String incomingCall = "6502889108";
        // set the MT call
        cmd.setPhoneNumber(incomingCall);
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_SET_MT_CALL, 0, 0, cmd);
        // get response
        Msg resp = Msg.recv(mMockRilChannel);
        log("Get response status: " + resp.getStatus());
        assertTrue("The ril is not in a proper state to set MT calls.",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);

        // allow the incoming call alerting for some time
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {}

        // we are playing a trick to assume the current is 1
        RilCtrlCmds.CtrlHangupConnRemote hangupCmd = new RilCtrlCmds.CtrlHangupConnRemote();
        hangupCmd.setConnectionId(1);
        hangupCmd.setCallFailCause(16);   // normal hangup
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_HANGUP_CONN_REMOTE, 0, 0, hangupCmd);

        // get response
        resp = Msg.recv(mMockRilChannel);
        log("Get response for hangup connection: " + resp.getStatus());
        assertTrue("CTRL_CMD_HANGUP_CONN_REMOTE failed",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);
    }

    /**
     * Test case 7: test set call transition flag
     */
    public void testSetCallTransitionFlag() throws IOException {
        log("testSetCallTransitionFlag");
        // Set flag to true:
        RilCtrlCmds.CtrlSetCallTransitionFlag cmd = new RilCtrlCmds.CtrlSetCallTransitionFlag();
        cmd.setFlag(true);
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_SET_CALL_TRANSITION_FLAG, 0, 0, cmd);

        Msg resp = Msg.recv(mMockRilChannel);
        log("Get response status: " + resp.getStatus());
        assertTrue("Set call transition flag failed",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);

        // add a dialing call
        RilCtrlCmds.CtrlReqAddDialingCall cmdDialCall = new RilCtrlCmds.CtrlReqAddDialingCall();
        String phoneNumber = "5102345678";
        cmdDialCall.setPhoneNumber(phoneNumber);
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_ADD_DIALING_CALL, 0, 0, cmdDialCall);
        resp = Msg.recv(mMockRilChannel);
        log("Get response status for adding a dialing call: " + resp.getStatus());
        assertTrue("add dialing call failed",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {}

        // send command to force call state change
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_SET_CALL_ALERT, 0, 0, null);
        resp = Msg.recv(mMockRilChannel);
        log("Get response status: " + resp.getStatus());
        assertTrue("Set call alert failed",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {}

        // send command to force call state change
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_SET_CALL_ACTIVE, 0, 0, null);
        resp = Msg.recv(mMockRilChannel);
        log("Get response status: " + resp.getStatus());
        assertTrue("Set call active failed",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);

        // hangup the active all remotely
        RilCtrlCmds.CtrlHangupConnRemote hangupCmd = new RilCtrlCmds.CtrlHangupConnRemote();
        hangupCmd.setConnectionId(1);
        hangupCmd.setCallFailCause(16);   // normal hangup
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_HANGUP_CONN_REMOTE, 0, 0, hangupCmd);
        resp = Msg.recv(mMockRilChannel);
        log("Get response for hangup connection: " + resp.getStatus());
        assertTrue("CTRL_CMD_HANGUP_CONN_REMOTE failed",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);

        // set the flag to false
        cmd.setFlag(false);
        Msg.send(mMockRilChannel, RilCtrlCmds.CTRL_CMD_SET_CALL_TRANSITION_FLAG, 0, 0, cmd);
        resp = Msg.recv(mMockRilChannel);
        assertTrue("Set call transition flag failed",
                   resp.getStatus() == RilCtrlCmds.CTRL_STATUS_OK);
    }
}
