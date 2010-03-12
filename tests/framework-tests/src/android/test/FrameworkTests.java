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

import com.android.internal.os.LoggingPrintStreamTest;
import junit.framework.TestSuite;
import com.android.internal.http.multipart.MultipartTest;
import com.android.internal.policy.impl.LockPatternKeyguardViewTest;

/**
 * Tests that are loaded in the boot classpath along with the Android framework
 * classes. This enables you to access package-private members in the framework
 * classes; doing so is not possible when the test classes are loaded in an
 * application classloader.
 */
public class FrameworkTests {
    public static TestSuite suite() {
        TestSuite suite = new TestSuite(FrameworkTests.class.getName());

        suite.addTestSuite(MultipartTest.class);
        suite.addTestSuite(LoggingPrintStreamTest.class);
        suite.addTestSuite(LockPatternKeyguardViewTest.class);

        return suite;
    }
}
