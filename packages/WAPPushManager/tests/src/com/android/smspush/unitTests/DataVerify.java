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

package com.android.smspush.unitTests;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.util.HexDump;

/**
 * To verify that receiver application receives correct body data.
 */
public class DataVerify extends Service {
    private static final String LOG_TAG = "WAP PUSH";
    private static final int TIME_WAIT = 100;
    private static final int WAIT_COUNT = 100;
    private static byte[] mLastReceivedPdu = null;
    private static boolean sDataSet = false;

    private class IDataVerifyStub extends IDataVerify.Stub {
        public Context mContext;

        public IDataVerifyStub() {
        }

        boolean arrayCompare(byte[] arr1, byte[] arr2) {
            int i;

            if (arr1 == null || arr2 == null) {
                if (arr1 == null) {
                    Log.w(LOG_TAG, "arr1 is null");
                } else {
                    Log.w(LOG_TAG, "arr2 is null");
                }
                return false;
            }

            if (arr1.length != arr2.length) {
                return false;
            }

            for (i = 0; i < arr1.length; i++) {
                if (arr1[i] != arr2[i]) return false;
            }
            return true;
        }

        /**
         * Compare pdu and received pdu
         */
        public synchronized boolean verifyData(byte[] pdu) {
            int cnt = 0;

            while (!sDataSet) {
                // wait for the activity receive data.
                try {
                    Thread.sleep(TIME_WAIT);
                    if (cnt++ > WAIT_COUNT) {
                        // don't wait more than 10 sec.
                        return false;
                    }
                } catch (InterruptedException e) {}
            }

            Log.v(LOG_TAG, "verify pdu");
            boolean ret = arrayCompare(pdu, mLastReceivedPdu);
            return ret;
        }

        /**
         * Clear the old data. This method must be called before starting the test
         */
        public void resetData() {
            mLastReceivedPdu = null;
            sDataSet = false;
        }
    }

    private final IDataVerifyStub binder = new IDataVerifyStub();

    /**
     * Constructor
     */
    public DataVerify() {
    }

    /**
     * Receiver application must call this method when it receives the wap push message
     */
    public static void SetLastReceivedPdu(byte[] pdu) {
        mLastReceivedPdu = pdu;
        sDataSet = true;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return binder;
    }

}


