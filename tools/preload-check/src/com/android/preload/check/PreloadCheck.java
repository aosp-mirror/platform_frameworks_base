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

package com.android.preload.check;

import static org.junit.Assert.assertTrue;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.IDeviceTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@RunWith(DeviceJUnit4ClassRunner.class)
public class PreloadCheck implements IDeviceTest {
    private ITestDevice mTestDevice;

    private static final String TEST_CLASSPATH = "/data/local/tmp/preload-check-device.jar";

    @Override
    public void setDevice(ITestDevice testDevice) {
        mTestDevice = testDevice;
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }

    /**
     * Test that checks work as expected.
     */
    @Test
    public void testStatus() throws Exception {
        run("com.android.preload.check.IntegrityCheck");
    }

    /**
     * b/130206915.
     */
    @Test
    public void testAsyncTask() throws Exception {
        run("com.android.preload.check.NotInitialized", "android.os.AsyncTask");
    }

    /**
     * Just a check for something we expect to see initialized.
     */
    @Test
    public void testAnimator() throws Exception {
        run("com.android.preload.check.Initialized", "android.animation.Animator");
    }

    /**
     * Test the classes mentioned in the embedded preloaded-classes blacklist.
     */
    @Test
    public void testBlackList() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getClass()
                .getResourceAsStream("/preloaded-classes-blacklist")))) {
            String s;
            while ((s = br.readLine()) != null) {
                s = s.trim();
                if (s.startsWith("#") || s.isEmpty()) {
                    continue;
                }
                try {
                    run("com.android.preload.check.NotInitialized", s);
                } catch (Throwable t) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t.getMessage());
                }
            }
        }
        if (sb.length() > 0) {
            throw new RuntimeException(sb.toString());
        }
    }

    /**
     * Test the classes ending in NoPreloadHolder are not initialized.
     */
    @Test
    public void testNoPreloadHolder() throws Exception {
        run("com.android.preload.check.NotInitializedRegex", ".*NoPreloadHolder$", "true");
    }

    private void run(String cmd, String... args) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("app_process ")
                .append("-cp ").append(TEST_CLASSPATH)
                .append(" /system/bin ")
                .append(cmd);
        for (String arg : args) {
            sb.append(' ').append(escape(arg));
        }
        String res = mTestDevice.executeShellCommand(sb.toString());
        assertTrue(sb.toString() + "\n===\n" + res, res.trim().endsWith("OK"));
    }

    private static String escape(String input) {
        if (input.indexOf('$') == -1) {
            return input;
        }
        return input.replace("$", "\\$");
    }
}
