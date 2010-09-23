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

package com.android.rs.test;
import android.renderscript.RenderScript.RSMessage;

public class UnitTest extends Thread {
    public String name;
    public int result;

    /* These constants must match those in shared.rsh */
    public static final int RS_MSG_TEST_PASSED = 100;
    public static final int RS_MSG_TEST_FAILED = 101;

    protected UnitTest(String n, int initResult) {
        super();
        name = n;
        result = initResult;
    }

    protected UnitTest(String n) {
        this(n, 0);
    }

    protected UnitTest() {
        this ("<Unknown>");
    }

    protected RSMessage mRsMessage = new RSMessage() {
        public void run() {
            switch (mID) {
                case RS_MSG_TEST_PASSED:
                    result = 1;
                    break;
                case RS_MSG_TEST_FAILED:
                    result = -1;
                    break;
                default:
                    break;
            }
        }
    };

    public void run() {
        /* This method needs to be implemented for each subclass */
    }
}

