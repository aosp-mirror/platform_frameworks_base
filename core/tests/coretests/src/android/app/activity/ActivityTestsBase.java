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

package android.app.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.AndroidTestCase;
import android.test.PerformanceTestCase;

public class ActivityTestsBase extends AndroidTestCase 
        implements PerformanceTestCase, LaunchpadActivity.CallingTest {
    public static final String PERMISSION_GRANTED =
            "com.android.frameworks.coretests.permission.TEST_GRANTED";
    public static final String PERMISSION_DENIED =
            "com.android.frameworks.coretests.permission.TEST_DENIED";

    protected Intent mIntent;

    private PerformanceTestCase.Intermediates mIntermediates;
    private String mExpecting;

    // Synchronization of activity result.
    private boolean mFinished;
    private int mResultCode = 0;
    private Intent mData;
    private RuntimeException mResultStack = null;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mIntent = new Intent(mContext, LaunchpadActivity.class);
        mIntermediates = null;
    }

    @Override
    protected void tearDown() throws Exception {
        mIntermediates = null;
        super.tearDown();
    }

    public boolean isPerformanceOnly() {
        return false;
    }

    public void setInternalIterations(int count) {
    }

    public void startTiming(boolean realTime) {
        if (mIntermediates != null) {
            mIntermediates.startTiming(realTime);
        }
    }

    public void addIntermediate(String name) {
        if (mIntermediates != null) {
            mIntermediates.addIntermediate(name);
        }
    }

    public void addIntermediate(String name, long timeInNS) {
        if (mIntermediates != null) {
            mIntermediates.addIntermediate(name, timeInNS);
        }
    }

    public void finishTiming(boolean realTime) {
        if (mIntermediates != null) {
            mIntermediates.finishTiming(realTime);
        }
    }

    public void activityFinished(int resultCode, Intent data, RuntimeException where) {
        finishWithResult(resultCode, data, where);
    }

    public Intent editIntent() {
        return mIntent;
    }

    public Context getContext() {
        return mContext;
    }

    public int startPerformance(Intermediates intermediates) {
        mIntermediates = intermediates;
        return 1;
    }

    public void finishGood() {
        finishWithResult(Activity.RESULT_OK, null);
    }

    public void finishBad(String error) {
        finishWithResult(Activity.RESULT_CANCELED, (new Intent()).setAction(error));
    }

    public void finishWithResult(int resultCode, Intent data) {
        RuntimeException where = new RuntimeException("Original error was here");
        where.fillInStackTrace();
        finishWithResult(resultCode, data, where);
    }

    public void finishWithResult(int resultCode, Intent data, RuntimeException where) {
        synchronized (this) {
            //System.out.println("*** Activity finished!!");
            mResultCode = resultCode;
            mData = data;
            mResultStack = where;
            mFinished = true;
            notifyAll();
        }
    }

    public int runLaunchpad(String action) {
        LaunchpadActivity.setCallingTest(this);

        synchronized (this) {
            mIntent.setAction(action);
            mFinished = false;
            //System.out.println("*** Starting: " + mIntent);
            mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(mIntent);
        }

        return waitForResultOrThrow(60 * 1000);
    }

    public int waitForResultOrThrow(int timeoutMs) {
        return waitForResultOrThrow(timeoutMs, null);
    }

    public int waitForResultOrThrow(int timeoutMs, String expected) {
        int res = waitForResult(timeoutMs, expected);

        if (res == Activity.RESULT_CANCELED) {
            if (mResultStack != null) {
                throw new RuntimeException(
                        mData != null ? mData.toString() : "Unable to launch",
                        mResultStack);
            } else {
                throw new RuntimeException(
                        mData != null ? mData.toString() : "Unable to launch");
            }
        }
        return res;
    }

    public int waitForResult(int timeoutMs, String expected) {
        mExpecting = expected;

        long endTime = System.currentTimeMillis() + timeoutMs;

        boolean timeout = false;
        synchronized (this) {
            while (!mFinished) {
                long delay = endTime - System.currentTimeMillis();
                if (delay < 0) {
                    timeout = true;
                    break;
                }

                try {
                    wait(delay);
                } catch (java.lang.InterruptedException e) {
                    // do nothing
                }
            }
        }

        mFinished = false;

        if (timeout) {
            mResultCode = Activity.RESULT_CANCELED;
            onTimeout();
        }
        return mResultCode;
    }

    public int getResultCode() {
        return mResultCode;
    }
    
    public Intent getResultData() {
        return mData;
    }
    
    public RuntimeException getResultStack() {
        return mResultStack;
    }
    
    public void onTimeout() {
        String msg = mExpecting == null
                ? "Timeout" : ("Timeout while expecting " + mExpecting);
        finishWithResult(Activity.RESULT_CANCELED, (new Intent()).setAction(msg));
    }
}

