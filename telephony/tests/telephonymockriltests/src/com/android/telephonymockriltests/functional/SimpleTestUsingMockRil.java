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

package com.android.telephonymockriltests.functional;

import com.android.internal.telephony.mockril.MockRilController;
import android.test.InstrumentationTestCase;
import android.util.Log;

import com.android.telephonymockriltests.TelephonyMockTestRunner;

/**
 * A simple test that using Mock RIL Controller
 */
public class SimpleTestUsingMockRil extends InstrumentationTestCase {
    private static final String TAG = "SimpleTestUsingMockRil";
    private MockRilController mMockRilCtrl = null;
    private TelephonyMockTestRunner mRunner;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mRunner = (TelephonyMockTestRunner)getInstrumentation();
        mMockRilCtrl = mRunner.mController;
        assertNotNull(mMockRilCtrl);
    }

    /**
     * Get the current radio state of RIL
     */
    public void testGetRadioState() {
        int state = mMockRilCtrl.getRadioState();
        Log.v(TAG, "testGetRadioState: " + state);
        assertTrue(state >= 0 && state <= 9);
    }

    /**
     * Set the current radio state of RIL
     * and verify the radio state is set correctly
     */
    public void testSetRadioState() {
        for (int state = 0; state <= 9; state++) {
            Log.v(TAG, "set radio state to be " + state);
            assertTrue("set radio state: " + state + " failed.",
                       mMockRilCtrl.setRadioState(state));
        }
        assertFalse("use an invalid radio state", mMockRilCtrl.setRadioState(-1));
        assertFalse("the radio state doesn't exist", mMockRilCtrl.setRadioState(10));
    }
}
