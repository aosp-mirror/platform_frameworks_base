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

import android.app.Instrumentation;

import junit.framework.TestSuite;
import junit.framework.Test;
import junit.framework.TestResult;

/**
 * A {@link junit.framework.TestSuite} that injects {@link android.app.Instrumentation} into
 * {@link InstrumentationTestCase} before running them.
 */
public class InstrumentationTestSuite extends TestSuite {

    private final Instrumentation mInstrumentation;

    /**
     * @param instr The instrumentation that will be injected into each
     *   test before running it.
     */
    public InstrumentationTestSuite(Instrumentation instr) {
        mInstrumentation = instr;
    }


    public InstrumentationTestSuite(String name, Instrumentation instr) {
        super(name);
        mInstrumentation = instr;
    }

    /**
     * @param theClass Inspected for methods starting with 'test'
     * @param instr The instrumentation to inject into each test before
     *   running.
     */
    public InstrumentationTestSuite(final Class theClass, Instrumentation instr) {
        super(theClass);
        mInstrumentation = instr;
    }


    @Override
    public void addTestSuite(Class testClass) {
        addTest(new InstrumentationTestSuite(testClass, mInstrumentation));
    }


    @Override
    public void runTest(Test test, TestResult result) {

        if (test instanceof InstrumentationTestCase) {
            ((InstrumentationTestCase) test).injectInstrumentation(mInstrumentation);
        }

        // run the test as usual
        super.runTest(test, result);
    }
}
