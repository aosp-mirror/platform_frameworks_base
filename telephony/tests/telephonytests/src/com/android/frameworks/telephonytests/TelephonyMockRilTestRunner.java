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

package com.android.frameworks.telephonytests;

import android.os.Bundle;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import android.util.Log;

import java.io.IOException;

import com.android.internal.telephony.RilChannel;
import com.android.internal.telephony.mockril.MockRilTest;

import junit.framework.TestSuite;

public class TelephonyMockRilTestRunner extends InstrumentationTestRunner {

    public RilChannel mMockRilChannel;

    @Override
    public TestSuite getAllTests() {
        log("getAllTests E");
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(MockRilTest.class);
        log("getAllTests X");
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        log("getLoader EX");
        return TelephonyMockRilTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        log("onCreate E");
        try {
            mMockRilChannel = RilChannel.makeRilChannel();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        log("onCreate X");

        super.onCreate(icicle);
    }

    @Override
    public void onDestroy() {
        // I've not seen this called
        log("onDestroy EX");
        super.onDestroy();
    }

    @Override
    public void onStart() {
        // Called when the instrumentation thread is started.
        // At the moment we don't need the thread so return
        // which will shut down this unused thread.
        log("onStart EX");
        super.onStart();
    }

    @Override
    public void finish(int resultCode, Bundle results) {
        // Called when complete so I ask the mMockRilChannel to quit.
        log("finish E");
        mMockRilChannel.close();
        log("finish X");
        super.finish(resultCode, results);
    }

    private void log(String s) {
        Log.e("TelephonyMockRilTestRunner", s);
    }
}
