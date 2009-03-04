/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.unit_tests;

import com.google.android.net.ParentalControl;
import com.google.android.net.ParentalControlState;

import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.Assert;

public class ParentalControlTest extends AndroidTestCase {

    private boolean mOnResultCalled = false;

    public class Callback implements ParentalControl.Callback {
        public void onResult(ParentalControlState state) {
            synchronized (ParentalControlTest.class) {
                mOnResultCalled = true;
                ParentalControlTest.class.notifyAll();
            }
        }
    }

    @SmallTest
    public void testParentalControlCallback() {
        synchronized (ParentalControlTest.class) {
            ParentalControl.getParentalControlState(new Callback(), null);
            try {
                long start = SystemClock.uptimeMillis();
                ParentalControlTest.class.wait(20 * 1000);
                long end = SystemClock.uptimeMillis();
                Log.d("AndroidTests", "ParentalControlTest callback took " + (end-start) + " ms.");
            } catch (InterruptedException ex) {
            }
        }

        Assert.assertTrue(mOnResultCalled);
    }
}
