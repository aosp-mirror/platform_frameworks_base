/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Context;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.test.SimulatedCommands;
import com.android.internal.telephony.TestPhoneNotifier;

/**
 * This class creates a HandlerThread which waits for the various messages.
 */
public class GSMTestHandler extends HandlerThread implements Handler.Callback {

    private Handler mHandler;
    private Message mCurrentMessage;

    private Boolean mMsgConsumed;
    private SimulatedCommands sc;
    private GSMPhone mGSMPhone;
    private Context mContext;

    private static final int FAIL_TIMEOUT_MILLIS = 5 * 1000;

    public GSMTestHandler(Context context) {
        super("GSMPhoneTest");
        mMsgConsumed = false;
        mContext = context;
   }

    @Override
    protected void onLooperPrepared() {
        sc = new SimulatedCommands();
        mGSMPhone = new GSMPhone(mContext, sc, new TestPhoneNotifier(), true);
        mHandler = new Handler(getLooper(), this);
        synchronized (this) {
            notifyAll();
        }
    }

    public boolean handleMessage(Message msg) {
        synchronized (this) {
            mCurrentMessage = msg;
            this.notifyAll();
            while(!mMsgConsumed) {
                try {
                    this.wait();
                } catch (InterruptedException e) {}
            }
            mMsgConsumed = false;
        }
        return true;
    }


    public void cleanup() {
        Looper looper = getLooper();
        if (looper != null) looper.quit();
        mHandler = null;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public SimulatedCommands getSimulatedCommands() {
        return sc;
    }

    public GSMPhone getGSMPhone() {
        return mGSMPhone;
    }

    public Message waitForMessage(int code) {
        Message msg;
        while(true) {
            msg = null;
            synchronized (this) {
                try {
                    this.wait(FAIL_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                }

                // Check if timeout has occurred.
                if (mCurrentMessage != null) {
                    // Consume the message
                    msg = Message.obtain();
                    msg.copyFrom(mCurrentMessage);
                    mCurrentMessage = null;
                    mMsgConsumed = true;
                    this.notifyAll();
                }
            }
            if (msg == null || code == GSMPhoneTest.ANY_MESSAGE || msg.what == code) return msg;
       }
    }
}
