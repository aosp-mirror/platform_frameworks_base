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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ClassLoaderResourcePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final String EXISTENT_RESOURCE = "java/util/logging/logging.properties";
    private static final String MISSING_RESOURCE = "missing_entry";

    @Test
    public void timeGetBootResource_hit() {
        ClassLoader currentClassLoader = getClass().getClassLoader();
        Assert.assertNotNull(currentClassLoader.getResource(EXISTENT_RESOURCE));

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            currentClassLoader.getResource(EXISTENT_RESOURCE);
        }
    }

    @Test
    public void timeGetBootResource_miss() {
        ClassLoader currentClassLoader = getClass().getClassLoader();
        Assert.assertNull(currentClassLoader.getResource(MISSING_RESOURCE));

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            currentClassLoader.getResource(MISSING_RESOURCE);
        }
    }
}
