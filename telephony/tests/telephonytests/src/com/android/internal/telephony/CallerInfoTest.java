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

package com.android.internal.telephony;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import android.util.Log;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase;
import android.util.StringBuilderPrinter;

/*
 * Check the CallerInfo utility class works as expected.
 *
 */

public class CallerInfoTest extends AndroidTestCase {
    private CallerInfo mInfo;
    private Context mContext;

    private static final String kEmergencyNumber = "Emergency Number";
    private static final int kToken = 0xdeadbeef;
    private static final String TAG = "CallerInfoUnitTest";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = new MockContext();
        mInfo = new CallerInfo();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Checks the caller info instance is flagged as an emergency if
     * the number is an emergency one. There is no test for the
     * contact based constructors because emergency number are not in
     * the contact DB.
     */
    @SmallTest
    public void testEmergencyIsProperlySet() throws Exception {
        assertFalse(mInfo.isEmergencyNumber());

        mInfo = CallerInfo.getCallerInfo(mContext, "911");
        assertIsValidEmergencyCallerInfo();

        mInfo = CallerInfo.getCallerInfo(mContext, "tel:911");
        assertIsValidEmergencyCallerInfo();


        // This one hits the content resolver.
        mInfo = CallerInfo.getCallerInfo(mContext, "18001234567");
        assertFalse(mInfo.isEmergencyNumber());
    }

    /**
     * Same as testEmergencyIsProperlySet but uses the async query api.
     */
    @SmallTest
    public void testEmergencyIsProperlySetUsingAsyncQuery() throws Exception {
        QueryRunner query;

        query = new QueryRunner("911");
        query.runAndCheckCompletion();
        assertIsValidEmergencyCallerInfo();

        query = new QueryRunner("tel:911");
        query.runAndCheckCompletion();
        assertIsValidEmergencyCallerInfo();

        query = new QueryRunner("18001234567");
        query.runAndCheckCompletion();
        assertFalse(mInfo.isEmergencyNumber());
    }

    /**
     * For emergency caller info, phoneNumber should be set to the
     * string emergency_call_dialog_number_for_display and the
     * photoResource should be set to the picture_emergency drawable.
     */
    @SmallTest
    public void testEmergencyNumberAndPhotoAreSet() throws Exception {
        mInfo = CallerInfo.getCallerInfo(mContext, "911");

        assertIsValidEmergencyCallerInfo();
    }

    // TODO: Add more tests:
    /**
     * Check if the voice mail number cannot be retrieved that the
     * original phone number is preserved.
     */
    /**
     * Check the markAs* methods work.
     */


    //
    // Helpers
    //

    // Partial implementation of MockResources.
    public class MockResources extends android.test.mock.MockResources
    {
        @Override
        public String getString(int resId) throws Resources.NotFoundException {
            switch (resId) {
                case com.android.internal.R.string.emergency_call_dialog_number_for_display:
                    return kEmergencyNumber;
                default:
                    throw new UnsupportedOperationException("Missing handling for resid " + resId);
            }
        }
    }

    // Partial implementation of MockContext.
    public class MockContext extends android.test.mock.MockContext {
        private ContentResolver mResolver;
        private Resources mResources;

        public MockContext() {
            mResolver = new android.test.mock.MockContentResolver();
            mResources = new MockResources();
        }

        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }
    }

    /**
     * Class to run a CallerInfoAsyncQuery in a separate thread, with
     * its own Looper. We cannot use the main Looper because on the
     * 1st quit the thread is maked dead, ie no further test can use
     * it. Also there is not way to inject a Looper instance in the
     * query, so we have to use a thread with its own looper.
     */
    private class QueryRunner extends Thread
            implements CallerInfoAsyncQuery.OnQueryCompleteListener {
        private Looper mLooper;
        private String mNumber;
        private boolean mAsyncCompleted;

        public QueryRunner(String number) {
            super();
            mNumber = number;
        }

        // Run the query in the thread, wait for completion.
        public void runAndCheckCompletion() throws InterruptedException {
            start();
            join();
            assertTrue(mAsyncCompleted);
        }

        @Override
        public void run() {
            Looper.prepare();
            mLooper = Looper.myLooper();
            mAsyncCompleted = false;
            // The query will pick the thread local looper we've just prepared.
            CallerInfoAsyncQuery.startQuery(kToken, mContext, mNumber, this, null);
            mLooper.loop();
        }

        // Quit the Looper on the 1st callback
        // (EVENT_EMERGENCY_NUMBER). There is another message
        // (EVENT_END_OF_QUEUE) that will never be delivered because
        // the test has exited. The corresponding stack trace
        // "Handler{xxxxx} sending message to a Handler on a dead
        // thread" can be ignored.
        public void onQueryComplete(int token, Object cookie, CallerInfo info) {
            mAsyncCompleted = true;
            mInfo = info;
            mLooper.quit();
        }
    }

    /**
     * Fail if mInfo does not contain a valid emergency CallerInfo instance.
     */
    private void assertIsValidEmergencyCallerInfo() throws Exception {
        assertTrue(mInfo.isEmergencyNumber());

        // For emergency caller info, phoneNumber should be set to the
        // string emergency_call_dialog_number_for_display and the
        // photoResource should be set to the picture_emergency drawable.
        assertEquals(kEmergencyNumber, mInfo.phoneNumber);
        assertEquals(com.android.internal.R.drawable.picture_emergency, mInfo.photoResource);

        // The name should be null
        assertNull(mInfo.name);
        assertEquals(0, mInfo.namePresentation);
        assertNull(mInfo.cnapName);
        assertEquals(0, mInfo.numberPresentation);

        assertFalse(mInfo.contactExists);
        assertEquals(0, mInfo.person_id);
        assertFalse(mInfo.needUpdate);
        assertNull(mInfo.contactRefUri);

        assertNull(mInfo.phoneLabel);
        assertEquals(0, mInfo.numberType);
        assertNull(mInfo.numberLabel);

        assertNull(mInfo.contactRingtoneUri);
        assertFalse(mInfo.shouldSendToVoicemail);

        assertNull(mInfo.cachedPhoto);
        assertFalse(mInfo.isCachedPhotoCurrent);
    }
}
