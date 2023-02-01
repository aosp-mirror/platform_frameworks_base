/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.inputmethod.stresstest;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Takes a screenshot when the test fails.
 *
 * <p>Use {@link com.android.tradefed.device.metric.FilePullerLogCollector} to collect screenshots
 * taken.
 * For example, in AndroidTest.xml:
 * <code>
 *     <metrics_collector class="com.android.tradefed.device.metric.FilePullerLogCollector">
 *         <option name="directory-keys" value="/sdcard/MyTest/" />
 *         <option name="collect-on-run-ended-only" value="true" />
 *     </metrics_collector>
 * </code>
 * in MyTest.java:
 * <code>
 *     @Rule
 *     public ScreenCaptureRule mScreenCaptureRule = new ScreenCaptureRule("/sdcard/MyTest");
 * </code>
 */
public class ScreenCaptureRule extends TestWatcher {

    private static final String TAG = "ScreenCaptureRule";

    private final String mOutDir;

    public ScreenCaptureRule(String outDir) {
        mOutDir = outDir;
    }

    @Override
    protected void failed(Throwable e, Description description) {
        super.failed(e, description);
        String time = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String fileName = "screenshot-" + time + ".png";
        capture(fileName);
    }

    /** Take a screenshot. */
    public void capture(String fileName) {
        SystemUtil.runCommandAndPrintOnLogcat(TAG, String.format("mkdir -p %s", mOutDir));
        SystemUtil.runCommandAndPrintOnLogcat(TAG,
                String.format("screencap %s/%s", mOutDir, fileName));
    }
}
