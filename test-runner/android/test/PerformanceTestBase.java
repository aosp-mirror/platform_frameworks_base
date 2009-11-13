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

import android.os.Bundle;
import android.os.PerformanceCollector;
import android.os.PerformanceCollector.PerformanceResultsWriter;

import java.lang.reflect.Method;

/**
 * Provides hooks and wrappers to automatically and manually collect and report
 * performance data in tests.
 *
 * {@hide} Pending approval for public API.
 */
public class PerformanceTestBase extends InstrumentationTestCase implements PerformanceTestCase {

    private static PerformanceCollector sPerfCollector = new PerformanceCollector();
    private static int sNumTestMethods = 0;
    private static int sNumTestMethodsLeft = 0;

    // Count number of tests, used to emulate beforeClass and afterClass from JUnit4
    public PerformanceTestBase() {
        if (sNumTestMethods == 0) {
            Method methods[] = getClass().getMethods();
            for (Method m : methods) {
                if (m.getName().startsWith("test")) {
                    sNumTestMethods ++;
                    sNumTestMethodsLeft ++;
                }
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // @beforeClass
        // Will skew timing measured by TestRunner, but not by PerformanceCollector
        if (sNumTestMethodsLeft == sNumTestMethods) {
            sPerfCollector.beginSnapshot(this.getClass().getName());
        }
    }

    @Override
    protected void tearDown() throws Exception {
        // @afterClass
        // Will skew timing measured by TestRunner, but not by PerformanceCollector
        if (--sNumTestMethodsLeft == 0) {
            sPerfCollector.endSnapshot();
        }
        super.tearDown();
    }

    public void setPerformanceResultsWriter(PerformanceResultsWriter writer) {
        sPerfCollector.setPerformanceResultsWriter(writer);
    }

    /**
     * @see PerformanceCollector#beginSnapshot(String)
     */
    protected void beginSnapshot(String label) {
        sPerfCollector.beginSnapshot(label);
    }

    /**
     * @see PerformanceCollector#endSnapshot()
     */
    protected Bundle endSnapshot() {
        return sPerfCollector.endSnapshot();
    }

    /**
     * @see PerformanceCollector#startTiming(String)
     */
    protected void startTiming(String label) {
        sPerfCollector.startTiming(label);
    }

    /**
     * @see PerformanceCollector#addIteration(String)
     */
    protected Bundle addIteration(String label) {
        return sPerfCollector.addIteration(label);
    }

    /**
     * @see PerformanceCollector#stopTiming(String)
     */
    protected Bundle stopTiming(String label) {
        return sPerfCollector.stopTiming(label);
    }

    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        return 0;
    }

    public boolean isPerformanceOnly() {
        return true;
    }
}
