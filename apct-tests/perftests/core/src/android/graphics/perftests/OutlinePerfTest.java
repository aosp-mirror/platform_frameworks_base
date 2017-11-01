/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.graphics.perftests;

import android.graphics.Outline;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.filters.LargeTest;
import android.view.RenderNode;

import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class OutlinePerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testSetEmpty() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        Outline outline = new Outline();
        while (state.keepRunning()) {
            outline.setEmpty();
        }
    }

    @Test
    public void testSetRoundRect() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Outline outline = new Outline();
        while (state.keepRunning()) {
            outline.setRoundRect(50, 50, 150, 150, 5);
        }
    }
}
