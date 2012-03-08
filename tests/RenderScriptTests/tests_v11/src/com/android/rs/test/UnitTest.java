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

package com.android.rs.test_v11;
import android.content.Context;
import android.renderscript.RenderScript.RSMessageHandler;
import android.util.Log;

public class UnitTest extends Thread {
    public String name;
    public int result;
    private ScriptField_ListAllocs_s.Item mItem;
    private RSTestCore mRSTC;
    private boolean msgHandled;
    protected Context mCtx;

    /* These constants must match those in shared.rsh */
    public static final int RS_MSG_TEST_PASSED = 100;
    public static final int RS_MSG_TEST_FAILED = 101;

    private static int numTests = 0;
    public int testID;

    protected UnitTest(RSTestCore rstc, String n, int initResult, Context ctx) {
        super();
        mRSTC = rstc;
        name = n;
        msgHandled = false;
        mCtx = ctx;
        result = initResult;
        testID = numTests++;
    }

    protected UnitTest(RSTestCore rstc, String n, Context ctx) {
        this(rstc, n, 0, ctx);
    }

    protected UnitTest(RSTestCore rstc, Context ctx) {
        this (rstc, "<Unknown>", ctx);
    }

    protected UnitTest(Context ctx) {
        this (null, ctx);
    }

    protected RSMessageHandler mRsMessage = new RSMessageHandler() {
        public void run() {
            if (result == 0) {
                switch (mID) {
                    case RS_MSG_TEST_PASSED:
                        result = 1;
                        break;
                    case RS_MSG_TEST_FAILED:
                        result = -1;
                        break;
                    default:
                        android.util.Log.v("RenderScript", "Unit test got unexpected message");
                        return;
                }
            }

            if (mItem != null) {
                mItem.result = result;
                msgHandled = true;
                try {
                    mRSTC.refreshTestResults();
                }
                catch (IllegalStateException e) {
                    /* Ignore the case where our message receiver has been
                       disconnected. This happens when we leave the application
                       before it finishes running all of the unit tests. */
                }
            }
        }
    };

    public void waitForMessage() {
        while (!msgHandled) {
            yield();
        }
    }

    public void setItem(ScriptField_ListAllocs_s.Item item) {
        mItem = item;
    }

    public void run() {
        /* This method needs to be implemented for each subclass */
        if (mRSTC != null) {
            mRSTC.refreshTestResults();
        }
    }
}
