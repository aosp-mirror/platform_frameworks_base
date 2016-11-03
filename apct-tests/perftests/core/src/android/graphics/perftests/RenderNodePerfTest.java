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

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.filters.LargeTest;
import android.view.RenderNode;

import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class RenderNodePerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testMeasureRenderNodeJniOverhead() {
        final RenderNode node = RenderNode.create("benchmark", null);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            node.setTranslationX(1.0f);
        }
    }

    @Test
    public void testCreateRenderNodeNoName() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            RenderNode node = RenderNode.create(null, null);
            node.destroy();
        }
    }

    @Test
    public void testCreateRenderNode() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            RenderNode node = RenderNode.create("LinearLayout", null);
            node.destroy();
        }
    }

    @Test
    public void testIsValid() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode node = RenderNode.create("LinearLayout", null);
        while (state.keepRunning()) {
            node.isValid();
        }
    }
}
