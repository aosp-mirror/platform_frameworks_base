/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tests.rollback.host;

import com.android.ddmlib.Log;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.rules.ExternalResource;

public final class AbandonSessionsRule extends ExternalResource {
    private static final String TAG = "AbandonSessionsRule";
    private final BaseHostJUnit4Test mHost;

    public AbandonSessionsRule(BaseHostJUnit4Test host) {
        mHost = host;
    }

    @Override
    protected void before() throws Throwable {
        abandonSessions(mHost.getDevice());
    }

    @Override
    protected void after() {
        try {
            abandonSessions(mHost.getDevice());
        } catch (Exception e) {
            mHost.getDevice().logOnDevice(TAG, Log.LogLevel.ERROR,
                    "%s", "Failed to abandon sessions");
        }
    }

    /**
     * Abandons all sessions to prevent interference in our tests.
     */
    private static void abandonSessions(ITestDevice device) throws Exception {
        // No point in abandoning applied or failed sessions. We care about ready sessions only.
        String cmdListReadySessions =
                "pm list staged-sessions --only-sessionid --only-parent --only-ready";
        String output = device.executeShellCommand(cmdListReadySessions);
        if (output.trim().isEmpty()) {
            // No sessions to abandon
            return;
        }
        // Ensure we have sufficient privilege to abandon sessions from other apps
        device.enableAdbRoot();
        device.executeShellCommand("for i in $(" + cmdListReadySessions
                + "); do pm install-abandon $i; done");
        device.disableAdbRoot();
    }
}
