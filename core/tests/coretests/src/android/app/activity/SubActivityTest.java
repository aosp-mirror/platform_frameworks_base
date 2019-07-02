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

import androidx.test.filters.Suppress;

@Suppress
public class SubActivityTest extends ActivityTestsBase {

    public void testPendingResult() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), SubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.PENDING_RESULT_MODE);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testNoResult() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), SubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.NO_RESULT_MODE);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testResult() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), SubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.RESULT_MODE);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testFinishSub() throws Exception {
        mIntent.putExtra("component",
                new ComponentName(getContext(), RemoteSubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.FINISH_SUB_MODE);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testRemoteNoResult() throws Exception {
        mIntent.putExtra("component",
                new ComponentName(getContext(), RemoteSubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.NO_RESULT_MODE);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testRemoteResult() throws Exception {
        mIntent.putExtra("component",
                new ComponentName(getContext(), RemoteSubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.RESULT_MODE);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testRemoteFinishSub() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), SubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.FINISH_SUB_MODE);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testRemoteRestartNoResult() throws Exception {
        mIntent.putExtra("component",
                new ComponentName(getContext(), RemoteSubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.NO_RESULT_MODE);
        mIntent.putExtra("kill", true);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testRemoteRestartResult() throws Exception {
        mIntent.putExtra("component",
                new ComponentName(getContext(), RemoteSubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.RESULT_MODE);
        mIntent.putExtra("kill", true);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }

    public void testRemoteRestartFinishSub() throws Exception {
        mIntent.putExtra("component", new ComponentName(getContext(), SubActivityScreen.class));
        mIntent.putExtra("mode", SubActivityScreen.FINISH_SUB_MODE);
        mIntent.putExtra("kill", true);
        runLaunchpad(LaunchpadActivity.LAUNCH);
    }
}
