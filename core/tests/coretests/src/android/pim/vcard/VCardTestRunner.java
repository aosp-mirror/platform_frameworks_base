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
package android.pim.vcard;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import junit.framework.TestSuite;

/**
 * Usage: adb shell am instrument -w com.android.vcard.tests/.VCardTestRunnerTestRunner
 */
public class VCardTestRunner extends InstrumentationTestRunner {
    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(VCardUtilsTests.class);
        suite.addTestSuite(VCardTestUtilsTests.class);
        suite.addTestSuite(VCardImporterTests.class);
        suite.addTestSuite(VCardExporterTests.class);
        suite.addTestSuite(VCardJapanizationTests.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return VCardTestRunner.class.getClassLoader();
    }
}