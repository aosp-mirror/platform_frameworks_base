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

package com.android.dumprendertree2.scriptsupport;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;

/**
 * Extends InstrumentationTestRunner to allow the script to pass arguments to the application
 */
public class ScriptTestRunner extends InstrumentationTestRunner {
    String mTestsRelativePath;

    @Override
    public void onCreate(Bundle arguments) {
        mTestsRelativePath = arguments.getString("path");
        super.onCreate(arguments);
    }

    public String getTestsRelativePath() {
        return mTestsRelativePath;
    }
}