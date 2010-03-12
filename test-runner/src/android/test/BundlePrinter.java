/*
 * Copyright (C) 2007 The Android Open Source Project
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

import java.io.PrintStream;

import android.os.Bundle;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.runner.BaseTestRunner;
import junit.textui.ResultPrinter;


/**
 * Subclass of ResultPrinter that adds test case results to a bundle.
 * 
 * {@hide} - This class is deprecated, and will be going away.  Please don't use it.
 */
public class BundlePrinter extends ResultPrinter {

    private Bundle mResults;
    private boolean mFailure;
    private boolean mError;
    
    public BundlePrinter(PrintStream writer, Bundle result) {
        super(writer);
        mResults = result;
    }
    
    @Override
    public void addError(Test test, Throwable t) {
        mResults.putString(getComboName(test), BaseTestRunner.getFilteredTrace(t));
        mFailure = true;
        super.addError(test, t);
    }

    @Override
    public void addFailure(Test test, AssertionFailedError t) {
        mResults.putString(getComboName(test), BaseTestRunner.getFilteredTrace(t));
        mError = true;
        super.addFailure(test, t);
    }

    @Override
    public void endTest(Test test) {
        if (!mFailure && !mError) {
            mResults.putString(getComboName(test), "passed");
        }
        super.endTest(test);
    }

    @Override
    public void startTest(Test test) {
        mFailure = false;
        mError = false;
        super.startTest(test);
    }
    
    private String getComboName(Test test) {
        return test.getClass().getName() + ":" + ((TestCase) test).getName();
    }
    
}
