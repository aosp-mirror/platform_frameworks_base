/**
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

package com.android.internal.util;

import android.os.Debug;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.TestCase;

/**
 * Test for AsyncChannel.
 */
public class AsyncChannelTest extends TestCase {
    private static final boolean DBG = true;
    private static final boolean WAIT_FOR_DEBUGGER = false;
    private static final String TAG = "AsyncChannelTest";

    @SmallTest
    public void test1() throws Exception {
        if (DBG) log("test1");
        if (WAIT_FOR_DEBUGGER) Debug.waitForDebugger();
        assertTrue(1 == 1);
    }

    protected void log(String s) {
        Log.d(TAG, s);
    }
}
