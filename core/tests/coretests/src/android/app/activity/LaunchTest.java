/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app.activity;

import android.content.ComponentName;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;

@Suppress  // Flaky.
public class LaunchTest extends ActivityTestsBase {

    @LargeTest
    public void testColdActivity() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), TestedActivity.class));
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    @LargeTest
    public void testLocalActivity() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), LocalActivity.class));
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    @LargeTest
    public void testColdScreen() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), TestedScreen.class));
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    @LargeTest
    public void testLocalScreen() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), LocalScreen.class));
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    @LargeTest
    public void testForwardResult() throws Exception {
        runLaunchpad(LaunchpadActivity.FORWARD_RESULT);
    }

    // The following is disabled until we can catch and recover from
    // application errors.
    public void xxtestBadParcelable() throws Exception {
        // All we really care about for this test is that the system
        // doesn't crash.
        runLaunchpad(LaunchpadActivity.BAD_PARCELABLE);
    }

    @LargeTest
    public void testClearTopInCreate() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), ClearTop.class));
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    @LargeTest
    public void testClearTopWhileResumed() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), ClearTop.class));
        mIntent.putExtra(ClearTop.WAIT_CLEAR_TASK, true);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }
}


