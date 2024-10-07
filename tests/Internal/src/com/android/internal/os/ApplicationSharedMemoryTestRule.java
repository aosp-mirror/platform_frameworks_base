/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.os;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import com.android.internal.os.ApplicationSharedMemory;

/** Test rule that sets up and tears down ApplicationSharedMemory for test. */
public class ApplicationSharedMemoryTestRule implements TestRule {

    private ApplicationSharedMemory mSavedInstance;

    @Override
    public Statement apply(final Statement base, final Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setup();
                try {
                    base.evaluate(); // Run the test
                } finally {
                    teardown();
                }
            }
        };
    }

    private void setup() {
        mSavedInstance = ApplicationSharedMemory.sInstance;
        ApplicationSharedMemory.sInstance = ApplicationSharedMemory.create();
    }

    private void teardown() {
        ApplicationSharedMemory.sInstance.close();
        ApplicationSharedMemory.sInstance = mSavedInstance;
        mSavedInstance = null;
    }
}
