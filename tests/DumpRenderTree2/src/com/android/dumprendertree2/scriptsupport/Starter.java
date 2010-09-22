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

package com.android.dumprendertree2.scriptsupport;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import com.android.dumprendertree2.TestsListActivity;
import com.android.dumprendertree2.forwarder.ForwarderManager;

/**
 * A class which provides methods that can be invoked by a script running on the host machine to
 * run the tests.
 *
 * It starts a TestsListActivity and does not return until all the tests finish executing.
 */
public class Starter extends ActivityInstrumentationTestCase2<TestsListActivity> {
    private static final String LOG_TAG = "Starter";
    private boolean mEverythingFinished;

    public Starter() {
        super(TestsListActivity.class);
    }

    /**
     * This method is called from adb to start executing the tests. It doesn't return
     * until everything is finished so that the script can wait for the end if it needs
     * to.
     */
    public void startLayoutTests() {
        ScriptTestRunner runner = (ScriptTestRunner)getInstrumentation();
        String relativePath = runner.getTestsRelativePath();

        ForwarderManager.getForwarderManager().start();

        Intent intent = new Intent();
        intent.setClassName("com.android.dumprendertree2", "TestsListActivity");
        intent.setAction(Intent.ACTION_RUN);
        intent.putExtra(TestsListActivity.EXTRA_TEST_PATH, relativePath);
        setActivityIntent(intent);
        getActivity().registerOnEverythingFinishedCallback(new OnEverythingFinishedCallback() {
            /** This method is safe to call on any thread */
            @Override
            public void onFinished() {
                synchronized (Starter.this) {
                    mEverythingFinished = true;
                    Starter.this.notifyAll();
                }
            }
        });

        synchronized (this) {
            while (!mEverythingFinished) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, "startLayoutTests()", e);
                }
            }
        }

        ForwarderManager.getForwarderManager().stop();
    }
}