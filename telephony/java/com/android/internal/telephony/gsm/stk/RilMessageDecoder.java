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
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.BlockingQueue;

/**
 * Class used for queuing raw ril messages, decoding them into CommanParams 
 * objects and sending the result back to the STK Service. 
 *
 */
class RilMessageDecoder extends Handler {

    // members
    private final BlockingQueue<RilMessage> mInQueue;
    private static RilMessageDecoder sInstance = null;
    private CommandParamsFactory mCmdParamsFactory = null;
    private RilMessage mCurrentRilMessage = null;
    private Handler mCaller = null;

    // constants
    static final int START = 1;
    static final int CMD_PARAMS_READY = 2;

    static RilMessageDecoder getInstance(BlockingQueue<RilMessage> inQ,
            Handler caller, SIMFileHandler fh) {
        if (sInstance != null) {
            return sInstance;
        }
        if (inQ != null) {
            HandlerThread thread = new HandlerThread("Stk RIL Messages decoder");
            thread.start();
            return new RilMessageDecoder(thread.getLooper(), inQ, caller, fh);
        }
        return null;
    }

    private RilMessageDecoder(Looper looper, BlockingQueue<RilMessage> inQ,
            Handler caller, SIMFileHandler fh) {
        super(looper);
        mInQueue = inQ;
        mCaller = caller;
        mCmdParamsFactory = CommandParamsFactory.getInstance(this, fh);
    }

    public void handleMessage(Message msg) {
        switch(msg.what) {
        case START:
            start();
            break;
        case CMD_PARAMS_READY:
            mCurrentRilMessage.mResCode = ResultCode.fromInt(msg.arg1);
            mCurrentRilMessage.mData = msg.obj;
            sendCmdForExecution();
            break;
        }
    }

    private void start() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    mCurrentRilMessage = mInQueue.take();
                    StkLog.d(this, "Decoding new message");
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    // fall through and retry
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (mCurrentRilMessage != null) {
                decodeMessage(mCurrentRilMessage);
            }
        }
    }

    private void decodeMessage(RilMessage msg) {
        switch(msg.mId) {
        case Service.MSG_ID_SESSION_END:
        case Service.MSG_ID_CALL_SETUP:
            mCurrentRilMessage.mResCode = ResultCode.OK;
            sendCmdForExecution();
            break;
        case Service.MSG_ID_PROACTIVE_COMMAND:
        case Service.MSG_ID_EVENT_NOTIFY:
        case Service.MSG_ID_REFRESH:
            byte[] rawData = null;
            try {
                rawData = SimUtils.hexStringToBytes((String) msg.mData);
            } catch (Exception e) {
                // zombie messages are dropped
                getNextMessage();
                return;
            }
            try {
                // Start asynch parsing of the command parameters. 
                mCmdParamsFactory.make(BerTlv.decode(rawData));
            } catch (ResultException e) {
                // send to Service for proper RIL communication.
                mCurrentRilMessage.mResCode = e.result();
                sendCmdForExecution();
            }
            break;
        }
    }

    private void sendCmdForExecution() {
        Message msg = mCaller.obtainMessage(Service.MSG_ID_RIL_MSG_DECODED,
                new RilMessage(mCurrentRilMessage));
        msg.sendToTarget();
        getNextMessage();
    }

    private void getNextMessage() {
        Message nextMsg = this.obtainMessage(START);
        nextMsg.sendToTarget();
    }
}