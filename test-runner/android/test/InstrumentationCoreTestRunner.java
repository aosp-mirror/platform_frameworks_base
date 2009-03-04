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

import java.io.File;

import android.os.Bundle;

/**
 * This test runner extends the default InstrumentationTestRunner. It overrides
 * the {@code onCreate(Bundle)} method and sets the system properties necessary
 * for many core tests to run. This is needed because there are some core tests
 * that need writing access to the filesystem.
 *
 * @hide
 */
public class InstrumentationCoreTestRunner extends InstrumentationTestRunner {

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        
        File cacheDir = getTargetContext().getCacheDir();

        System.setProperty("user.language", "en");
        System.setProperty("user.region", "US");
        System.setProperty("java.io.tmpdir", cacheDir.getAbsolutePath());
    }
}
