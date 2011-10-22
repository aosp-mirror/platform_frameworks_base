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

package com.android.internal.telephony.gsm;

import android.os.HandlerThread;
import android.test.AndroidTestCase;
import android.util.Log;

import java.nio.charset.Charset;

/**
 * Test SMS-PP data download to UICC.
 * Uses test messages from 3GPP TS 31.124 section 27.22.5.
 */
public class UsimDataDownloadTest extends AndroidTestCase {
    private static final String TAG = "UsimDataDownloadTest";

    class TestHandlerThread extends HandlerThread {
        private UsimDataDownloadHandler mHandler;

        TestHandlerThread() {
            super("TestHandlerThread");
        }

        @Override
        protected void onLooperPrepared() {
            synchronized (this) {
                mHandler = new UsimDataDownloadHandler(mCm);
                notifyAll();
            }
        }

        UsimDataDownloadHandler getHandler() {
            synchronized (this) {
                while (mHandler == null) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {}
                }
                return mHandler;
            }
        }
    }

    private UsimDataDownloadCommands mCm;
    private TestHandlerThread mHandlerThread;
    UsimDataDownloadHandler mHandler;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mCm = new UsimDataDownloadCommands(mContext);
        mHandlerThread = new TestHandlerThread();
        mHandlerThread.start();
        mHandler = mHandlerThread.getHandler();
        Log.d(TAG, "mHandler is constructed");
    }

    @Override
    protected void tearDown() throws Exception {
        mHandlerThread.quit();
        super.tearDown();
    }

    // SMS-PP Message 3.1.1
    private static final byte[] SMS_PP_MESSAGE_3_1_1 = {
            // Service center address
            0x09, (byte) 0x91, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0xf8,

            0x04, 0x04, (byte) 0x91, 0x21, 0x43, 0x7f, 0x16, (byte) 0x89, 0x10, 0x10, 0x00, 0x00,
            0x00, 0x00, 0x0d, 0x54, 0x65, 0x73, 0x74, 0x4d, 0x65, 0x73, 0x73, 0x61,
            0x67, 0x65, 0x20, 0x31
    };

    // SMS-PP Download Envelope 3.1.1
    private static final String SMS_PP_ENVELOPE_3_1_1 = "d12d8202838106099111223344556677f88b1c04"
            + "049121437f16891010000000000d546573744d6573736167652031";

    // SMS-PP Message 3.1.5
    private static final byte[] SMS_PP_MESSAGE_3_1_5 = {
            // Service center address
            0x09, (byte) 0x91, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0xf8,

            0x44, 0x04, (byte) 0x91, 0x21, 0x43, 0x7f, (byte) 0xf6, (byte) 0x89, 0x10, 0x10, 0x00,
            0x00, 0x00, 0x00, 0x1e, 0x02, 0x70, 0x00, 0x00, 0x19, 0x00, 0x0d, 0x00, 0x00,
            0x00, 0x00, (byte) 0xbf, (byte) 0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            (byte) 0xdc, (byte) 0xdc, (byte) 0xdc, (byte) 0xdc, (byte) 0xdc, (byte) 0xdc,
            (byte) 0xdc, (byte) 0xdc, (byte) 0xdc, (byte) 0xdc
    };

    // SMS-PP Download Envelope 3.1.5
    private static final String SMS_PP_ENVELOPE_3_1_5 = "d13e8202838106099111223344556677f88b2d44"
            + "049121437ff6891010000000001e0270000019000d00000000bfff00000000000100"
            + "dcdcdcdcdcdcdcdcdcdc";

    public void testDataDownloadMessage1() {
        SmsMessage message = SmsMessage.createFromPdu(SMS_PP_MESSAGE_3_1_1);
        assertTrue("message is SMS-PP data download", message.isUsimDataDownload());

        mCm.expectSendEnvelope(SMS_PP_ENVELOPE_3_1_1, 0x90, 0x00, "");
        mCm.expectAcknowledgeGsmSms(true, 0);
        mHandler.startDataDownload(message);
        mCm.assertExpectedMethodsCalled();

        mCm.expectSendEnvelope(SMS_PP_ENVELOPE_3_1_1, 0x90, 0x00, "0123456789");
        mCm.expectAcknowledgeGsmSmsWithPdu(true, "00077f16050123456789");
        mHandler.startDataDownload(message);
        mCm.assertExpectedMethodsCalled();

        mCm.expectSendEnvelope(SMS_PP_ENVELOPE_3_1_1, 0x62, 0xff, "0123456789abcdef");
        mCm.expectAcknowledgeGsmSmsWithPdu(false, "00d5077f16080123456789abcdef");
        mHandler.startDataDownload(message);
        mCm.assertExpectedMethodsCalled();
    }

    public void testDataDownloadMessage5() {
        SmsMessage message = SmsMessage.createFromPdu(SMS_PP_MESSAGE_3_1_5);
        assertTrue("message is SMS-PP data download", message.isUsimDataDownload());

        mCm.expectSendEnvelope(SMS_PP_ENVELOPE_3_1_5, 0x90, 0x00, "9876543210");
        mCm.expectAcknowledgeGsmSmsWithPdu(true, "00077ff6059876543210");
        mHandler.startDataDownload(message);
        mCm.assertExpectedMethodsCalled();

        mCm.expectSendEnvelope(SMS_PP_ENVELOPE_3_1_5, 0x93, 0x00, "");
        mCm.expectAcknowledgeGsmSms(false, 0xd4);   // SIM toolkit busy
        mHandler.startDataDownload(message);
        mCm.assertExpectedMethodsCalled();
    }
}
