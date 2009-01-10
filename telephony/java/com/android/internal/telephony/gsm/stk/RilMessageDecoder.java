/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;

import com.android.internal.telephony.gsm.SIMFileHandler;
import com.android.internal.telephony.gsm.SimUtils;

import android.os.Handler;
import android.os.HandlerState;
import android.os.HandlerStateMachine;
import android.os.Message;

/**
 * Class used for queuing raw ril messages, decoding them into CommanParams
 * objects and sending the result back to the STK Service.
 */
class RilMessageDecoder extends HandlerStateMachine {

    // members
    private static RilMessageDecoder sInstance = null;
    private CommandParamsFactory mCmdParamsFactory = null;
    private RilMessage mCurrentRilMessage = null;
    private Handler mCaller = null;

    // States
    private StateStart mStateStart = new StateStart();
    private StateCmdParamsReady mStateCmdParamsReady = new StateCmdParamsReady();

    // constants
    static final int START = 1;
    static final int CMD_PARAMS_READY = 2;

    static synchronized RilMessageDecoder getInstance(Handler caller, SIMFileHandler fh) {
        if (sInstance == null) {
            sInstance = new RilMessageDecoder(caller, fh);
        }
        return sInstance;
    }

    private RilMessageDecoder(Handler caller, SIMFileHandler fh) {
        super("RilMessageDecoder");
        setDbg(false);
        setInitialState(mStateStart);

        mCaller = caller;
        mCmdParamsFactory = CommandParamsFactory.getInstance(this.getHandler(), fh);
    }

    class StateStart extends HandlerState {
        @Override public void processMessage(Message msg) {
            if (msg.what == START) {
                if (decodeMessageParams((RilMessage)msg.obj)) {
                    transitionTo(mStateCmdParamsReady);
                }
            } else {
                StkLog.d(this, "StateStart unexpected expecting START=" +
                         START + " got " + msg.what);
            }
        }
    }

    class StateCmdParamsReady extends HandlerState {
        @Override public void processMessage(Message msg) {
            if (msg.what == CMD_PARAMS_READY) {
                mCurrentRilMessage.mResCode = ResultCode.fromInt(msg.arg1);
                mCurrentRilMessage.mData = msg.obj;
                sendCmdForExecution(mCurrentRilMessage);
                transitionTo(mStateStart);
            } else {
                StkLog.d(this, "StateCmdParamsReady expecting CMD_PARAMS_READY="
                         + CMD_PARAMS_READY + " got " + msg.what);
                deferMessage(msg);
            }
        }
    }

    public void startDecodingMessageParams(RilMessage rilMsg) {
        Message msg = obtainMessage(START);
        msg.obj = rilMsg;
        sendMessage(msg);
    }

    private boolean decodeMessageParams(RilMessage rilMsg) {
        boolean decodingStarted;

        mCurrentRilMessage = rilMsg;
        switch(rilMsg.mId) {
        case Service.MSG_ID_SESSION_END:
        case Service.MSG_ID_CALL_SETUP:
            mCurrentRilMessage.mResCode = ResultCode.OK;
            sendCmdForExecution(mCurrentRilMessage);
            decodingStarted = false;
            break;
        case Service.MSG_ID_PROACTIVE_COMMAND:
        case Service.MSG_ID_EVENT_NOTIFY:
        case Service.MSG_ID_REFRESH:
            byte[] rawData = null;
            try {
                rawData = SimUtils.hexStringToBytes((String) rilMsg.mData);
            } catch (Exception e) {
                // zombie messages are dropped
                StkLog.d(this, "decodeMessageParams dropping zombie messages");
                decodingStarted = false;
                break;
            }
            try {
                // Start asynch parsing of the command parameters.
                mCmdParamsFactory.make(BerTlv.decode(rawData));
                decodingStarted = true;
            } catch (ResultException e) {
                // send to Service for proper RIL communication.
                mCurrentRilMessage.mResCode = e.result();
                sendCmdForExecution(mCurrentRilMessage);
                decodingStarted = false;
            }
            break;
        default:
            decodingStarted = false;
            break;
        }
        return decodingStarted;
    }

    private void sendCmdForExecution(RilMessage rilMsg) {
        Message msg = mCaller.obtainMessage(Service.MSG_ID_RIL_MSG_DECODED,
                new RilMessage(rilMsg));
        msg.sendToTarget();
    }
}
