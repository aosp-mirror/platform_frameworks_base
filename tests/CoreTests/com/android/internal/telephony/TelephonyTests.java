/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.telephony.gsm.AdnRecordTest;
import com.android.internal.telephony.gsm.GSMPhoneTest;
import com.android.internal.telephony.gsm.GsmAlphabetTest;
import com.android.internal.telephony.gsm.SMSDispatcherTest;
import com.android.internal.telephony.gsm.SimPhoneBookTest;
import com.android.internal.telephony.gsm.SimSmsTest;
import com.android.internal.telephony.gsm.SimUtilsTest;

import junit.framework.TestSuite;

/**
 * To run these tests:
 * $ mmm java/tests && adb sync
 * $ adb shell am instrument -w \
 *    -e class com.android.internal.telephony.TelephonyTests \
 *    android.core/android.test.InstrumentationTestRunner
 */
public class TelephonyTests {
    public static TestSuite suite() {
        TestSuite suite = new TestSuite(TelephonyTests.class.getName());

        suite.addTestSuite(PhoneNumberWatcherTest.class);
        suite.addTestSuite(ATResponseParserTest.class);
        suite.addTestSuite(PhoneNumberUtilsTest.class);
        suite.addTestSuite(SMSDispatcherTest.class);
        //suite.addTestSuite(GSMPhoneTest.class);
        suite.addTestSuite(AdnRecordTest.class);
        suite.addTestSuite(GsmAlphabetTest.class);
        suite.addTestSuite(SimUtilsTest.class);
        suite.addTestSuite(SimPhoneBookTest.class);
        suite.addTestSuite(SimSmsTest.class);

        return suite;
    }
}
