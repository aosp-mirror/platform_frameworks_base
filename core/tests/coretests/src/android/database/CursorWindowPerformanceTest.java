/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.database;

import android.test.PerformanceTestCase;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

/**
 * <pre>
 * m -j44 FrameworksCoreTests
 * adb install -r -g \
 *      ${ANDROID_PRODUCT_OUT}/testcases/FrameworksCoreTests/arm64/FrameworksCoreTests.apk
 * adb shell am instrument -r -e perf true \
 *      -e class 'android.database.CursorWindowPerformanceTest'
 *      -w 'com.android.frameworks.coretests/androidx.test.runner.AndroidJUnitRunner'
 * </pre>
 */
public class CursorWindowPerformanceTest extends TestCase implements PerformanceTestCase {

    private final CursorWindowTest mTest = new CursorWindowTest();

    @Override
    public boolean isPerformanceOnly() {
        return true;
    }

    // These test can only be run once.
    @Override
    public int startPerformance(Intermediates intermediates) {
        return 1;
    }

    @SmallTest
    public void testConstructor_WithName() {
        mTest.testConstructor_WithName();
    }

    @SmallTest
    public void testConstructorWithEmptyName() {
        mTest.testConstructorWithEmptyName();
    }

    @SmallTest
    public void testValues() {
        mTest.testValues();
    }
}
