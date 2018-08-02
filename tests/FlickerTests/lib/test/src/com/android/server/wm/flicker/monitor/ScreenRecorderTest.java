/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker.monitor;

import static android.os.SystemClock.sleep;

import static com.android.server.wm.flicker.monitor.ScreenRecorder.DEFAULT_OUTPUT_PATH;
import static com.android.server.wm.flicker.monitor.ScreenRecorder.getPath;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Contains {@link ScreenRecorder} tests.
 * To run this test: {@code atest FlickerLibTest:ScreenRecorderTest}
 */
public class ScreenRecorderTest {
    private static final String TEST_VIDEO_FILENAME = "test.mp4";
    private ScreenRecorder mScreenRecorder;

    @Before
    public void setup() {
        mScreenRecorder = new ScreenRecorder();
    }

    @After
    public void teardown() {
        DEFAULT_OUTPUT_PATH.toFile().delete();
        getPath(TEST_VIDEO_FILENAME).toFile().delete();
    }

    @Test
    public void videoIsRecorded() {
        mScreenRecorder.start();
        sleep(100);
        mScreenRecorder.stop();
        File file = DEFAULT_OUTPUT_PATH.toFile();
        assertThat(file.exists()).isTrue();
    }

    @Test
    public void videoCanBeSaved() {
        mScreenRecorder.start();
        sleep(100);
        mScreenRecorder.stop();
        mScreenRecorder.save(TEST_VIDEO_FILENAME);
        File file = getPath(TEST_VIDEO_FILENAME).toFile();
        assertThat(file.exists()).isTrue();
    }
}
