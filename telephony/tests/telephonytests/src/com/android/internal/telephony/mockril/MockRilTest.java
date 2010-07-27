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
     * Test protobuf serialization and deserialization
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
     * Test echo command works using writeMsg & readMsg
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
     * Test get as
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
}
