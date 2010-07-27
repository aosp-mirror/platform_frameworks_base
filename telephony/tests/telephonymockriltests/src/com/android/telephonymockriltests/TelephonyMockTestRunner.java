/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.telephonymockriltests;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import com.android.internal.telephony.mockril.MockRilController;
import android.util.Log;

import com.android.telephonymockriltests.functional.SimpleTestUsingMockRil;

import java.io.IOException;
import junit.framework.TestSuite;
import junit.framework.TestCase;

/**
 * Test runner for telephony tests that using Mock RIL
 *
 */
public class TelephonyMockTestRunner extends InstrumentationTestRunner {
    private static final String TAG="TelephonyMockTestRunner";
    public MockRilController mController;

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(SimpleTestUsingMockRil.class);
        return suite;
    }

    @Override
    public void onCreate(Bundle icicle) {
        try {
            mController = new MockRilController();
        } catch (IOException e) {
            e.printStackTrace();
            TestCase.assertTrue("Create Mock RIl Controller failed", false);
        }
        TestCase.assertNotNull(mController);
        super.onCreate(icicle);
    }

    @Override
    public void finish(int resultCode, Bundle results) {
        if (mController != null)
            mController.closeChannel();
        super.finish(resultCode, results);
    }
}
