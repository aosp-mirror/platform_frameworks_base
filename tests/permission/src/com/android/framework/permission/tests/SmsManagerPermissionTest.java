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

package com.android.framework.permission.tests;

import java.util.ArrayList;

import android.telephony.SmsManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Verify that SmsManager apis cannot be called without required permissions.
 */
public class SmsManagerPermissionTest extends AndroidTestCase {

    private static final String MSG_CONTENTS = "hi";
    private static final short DEST_PORT = (short)1004;
    private static final String DEST_NUMBER = "4567";
    private static final String SRC_NUMBER = "1234";

    /**
     * Verify that SmsManager.sendTextMessage requires permissions.
     * <p>Tests Permission:
     *   {@link android.Manifest.permission#SEND_SMS}.
     */
    @SmallTest
    public void testSendTextMessage() {
        try {
            SmsManager.getDefault().sendTextMessage(SRC_NUMBER, DEST_NUMBER, MSG_CONTENTS, null,
                    null);
            fail("SmsManager.sendTextMessage did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Verify that SmsManager.sendDataMessage requires permissions.
     * <p>Tests Permission:
     *   {@link android.Manifest.permission#SEND_SMS}.
     */
    @SmallTest
    public void testSendDataMessage() {
        try {
            SmsManager.getDefault().sendDataMessage(SRC_NUMBER, DEST_NUMBER, DEST_PORT,
                    MSG_CONTENTS.getBytes(), null, null);
            fail("SmsManager.sendDataMessage did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Verify that SmsManager.sendMultipartMessage requires permissions.
     * <p>Tests Permission:
     *   {@link android.Manifest.permission#SEND_MMS}.
     */
    @SmallTest
    public void testSendMultipartMessage() {
        try {
            ArrayList<String> msgParts = new ArrayList<String>(2);
            msgParts.add(MSG_CONTENTS);
            msgParts.add("foo");
            SmsManager.getDefault().sendMultipartTextMessage(SRC_NUMBER, DEST_NUMBER, msgParts,
                    null, null);
            fail("SmsManager.sendMultipartTextMessage did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }
}
