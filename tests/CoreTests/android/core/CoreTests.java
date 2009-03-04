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

package android.core;

import com.android.internal.telephony.TelephonyTests;

import junit.framework.TestSuite;

import android.graphics.ColorStateListTest;
import android.location.LocationManagerProximityTest;
import android.location.LocationTest;
import android.test.AndroidTestRunnerTest;
import android.test.InstrumentationTestRunnerTest;
import android.util.*;
import android.view.FocusFinderTest;
import android.view.ViewGroupAttributesTest;
import android.webkit.*;

public class CoreTests extends TestSuite {
    
    /**
     * To run these tests:
     * $ mmm java/tests && adb sync
     * $ adb shell am instrument -w \
     *    -e class android.core.CoreTests \
     *    android.core/android.test.InstrumentationTestRunner
     */
    public static TestSuite suite() {
        TestSuite suite = new TestSuite(CoreTests.class.getName());

        // Re-enable StateListDrawableTest when we are running in the
        // framework-test directory which allows access to package private
        // access for MockView
        // suite.addTestSuite(StateListDrawableTest.class);
        suite.addTestSuite(DayOfMonthCursorTest.class);
        suite.addTestSuite(MonthDisplayHelperTest.class);
        suite.addTestSuite(StateSetTest.class);
        suite.addTestSuite(ColorStateListTest.class);
        suite.addTestSuite(FocusFinderTest.class);
        suite.addTestSuite(ViewGroupAttributesTest.class);
        suite.addTest(TelephonyTests.suite());
        suite.addTestSuite(FloatMathTest.class);
        suite.addTest(JavaTests.suite());
        suite.addTestSuite(LocationTest.class);
        suite.addTestSuite(LocationManagerProximityTest.class);
        suite.addTestSuite(AndroidTestRunnerTest.class);
        suite.addTestSuite(InstrumentationTestRunnerTest.class);
        suite.addTestSuite(CookieTest.class);
        
        return suite;
    }
}
