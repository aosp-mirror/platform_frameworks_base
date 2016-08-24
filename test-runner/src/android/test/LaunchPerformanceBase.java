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

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;


/**
 * Base class for all launch performance Instrumentation classes.
 *
 * @hide
 */
@Deprecated
public class LaunchPerformanceBase extends Instrumentation {

    public static final String LOG_TAG = "Launch Performance";

    protected Bundle mResults;
    protected Intent mIntent;

    public LaunchPerformanceBase() {
        mResults = new Bundle();
        mIntent = new Intent(Intent.ACTION_MAIN);
        mIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        setAutomaticPerformanceSnapshots();
    }

    /**
     * Launches intent, and waits for idle before returning.
     *
     * @hide
     */
    protected void LaunchApp() {
        startActivitySync(mIntent);
        waitForIdleSync();
    }
}
