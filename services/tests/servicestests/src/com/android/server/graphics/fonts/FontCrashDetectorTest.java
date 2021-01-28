/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.FileUtils;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class FontCrashDetectorTest {

    private File mCacheDir;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mCacheDir = new File(context.getCacheDir(), "UpdatableFontDirTest");
        FileUtils.deleteContentsAndDir(mCacheDir);
        mCacheDir.mkdirs();
    }

    @Test
    public void detectCrash() throws Exception {
        // Prepare a marker file.
        File file = new File(mCacheDir, "detectCrash");
        assertThat(file.createNewFile()).isTrue();

        FontCrashDetector detector = new FontCrashDetector(file);
        assertThat(detector.hasCrashed()).isTrue();

        detector.clear();
        assertThat(detector.hasCrashed()).isFalse();
        assertThat(file.exists()).isFalse();
    }

    @Test
    public void monitorCrash() {
        File file = new File(mCacheDir, "monitorCrash");
        FontCrashDetector detector = new FontCrashDetector(file);
        assertThat(detector.hasCrashed()).isFalse();

        FontCrashDetector.MonitoredBlock block = detector.start();
        assertThat(file.exists()).isTrue();

        block.close();
        assertThat(file.exists()).isFalse();
    }
}
