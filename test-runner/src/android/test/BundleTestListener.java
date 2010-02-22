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

package android.test;

import junit.framework.*;
import junit.framework.TestCase;
import junit.runner.BaseTestRunner;
import android.os.Bundle;

/**
 * A {@link TestListener} that adds test case results to a bundle.
 * 
 * {@hide} - This class is deprecated, and will be going away.  Please don't use it.
 */
public class BundleTestListener implements TestListener {

    private Bundle mBundle;
    private boolean mFailed;

    public BundleTestListener(Bundle bundle) {
        mBundle = bundle;
    }


    public void addError(Test test, Throwable t) {
        mBundle.putString(getComboName(test), BaseTestRunner.getFilteredTrace(t));
        mFailed = true;
    }

    public void addFailure(Test test, junit.framework.AssertionFailedError t) {
        mBundle.putString(getComboName(test), BaseTestRunner.getFilteredTrace(t));
        mFailed = true;
    }

    public void endTest(Test test) {
        if (!mFailed) {
            mBundle.putString(getComboName(test), "passed");
        }
    }

    public void startTest(Test test) {
        mFailed = false;
    }

    private String getComboName(Test test) {
        return test.getClass().getName() + ":" + ((TestCase) test).getName();
    }

}
