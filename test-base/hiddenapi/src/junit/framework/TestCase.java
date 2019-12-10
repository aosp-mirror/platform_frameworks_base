/*
 * Copyright (C) 2019 The Android Open Source Project
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

package junit.framework;

import android.compat.annotation.UnsupportedAppUsage;

/**
 * Stub only
 */
@SuppressWarnings({ "unchecked", "deprecation", "all" })
public abstract class TestCase extends Assert implements Test {

    /**
     * the name of the test case
     */
    @UnsupportedAppUsage
    private String fName;

    /**
     * Stub only
     */
    public int countTestCases() {
        throw new RuntimeException("Stub!");
    }

    /**
     * Stub only
     */
    public void run(TestResult result) {
        throw new RuntimeException("Stub!");
    }
}
