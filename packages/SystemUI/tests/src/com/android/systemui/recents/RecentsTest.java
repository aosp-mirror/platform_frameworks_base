/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.recents;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;

import static com.android.systemui.recents.RecentsImpl.RECENTS_ACTIVITY;
import static com.android.systemui.recents.RecentsImpl.RECENTS_PACKAGE;

import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityManager;
import android.os.SystemClock;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class RecentsTest extends SysuiTestCase {

    @Test
    public void testRecentsActivityType() throws Exception {
        // Clear the state
        final IActivityManager am = ActivityManager.getService();
        am.removeStacksWithActivityTypes(new int[] { ACTIVITY_TYPE_RECENTS });

        // Toggle recents, use a shell command because it is not exported
        runShellCommand("am start -n " + RECENTS_PACKAGE + "/" + RECENTS_ACTIVITY);

        // Verify that an activity was launched with the right activity type
        int retryCount = 0;
        while (retryCount < 10) {
            List<RunningTaskInfo> tasks = am.getTasks(Integer.MAX_VALUE);
            for (RunningTaskInfo info : tasks) {
                if (info.configuration.windowConfiguration.getActivityType()
                        == ACTIVITY_TYPE_RECENTS) {
                    // Found a recents activity with the right activity type
                    return;
                }
            }
            SystemClock.sleep(50);
            retryCount++;
        }
        fail("Expected Recents activity with ACTIVITY_TYPE_RECENTS");
    }
}