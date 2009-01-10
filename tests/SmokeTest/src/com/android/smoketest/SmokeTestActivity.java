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

package com.android.smoketest;

import android.app.LauncherActivity;
import android.content.Intent;

/**
 * Initial launcher for UI access to smoke tests.  This does not actually launch the tests,
 * it simply provides manual access to the various UI activities that are used by the tests.
 * 
 * To run all of the tests in this suite:
 * adb shell am instrument \
 *   -w com.android.smoketest/.tests.SmokeTestInstrumentationTestRunner
 */
public class SmokeTestActivity extends LauncherActivity {

    @Override
    protected Intent getTargetIntent() {
        // TODO: partition into categories by label like the sample code app
        Intent targetIntent = new Intent(Intent.ACTION_MAIN, null);
        // TODO: Do we add a new top-level intent?  Or just leave it hardcoded like this?
        targetIntent.addCategory("android.intent.category.SMOKETEST_INSTRUMENTATION_TEST");
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return targetIntent;
    }
}
