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
import android.graphics.RenderNode;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

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
    public void testIsValid() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode node = RenderNode.create("LinearLayout", null);
        while (state.keepRunning()) {
            node.hasDisplayList();
        }
    }

    @Test
    public void testStartEnd() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode node = RenderNode.create("LinearLayout", null);
        while (state.keepRunning()) {
            node.beginRecording(100, 100);
            node.endRecording();
        }
    }

    @Test
    public void testStartEndDeepHierarchy() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode[] nodes = new RenderNode[30];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = RenderNode.create("LinearLayout", null);
        }

        while (state.keepRunning()) {
            for (int i = 0; i < nodes.length; i++) {
                nodes[i].beginRecording(100, 100);
            }
            for (int i = nodes.length - 1; i >= 0; i--) {
                nodes[i].endRecording();
            }
        }
    }

    @Test
    public void testHasIdentityMatrix() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode node = RenderNode.create("LinearLayout", null);
        while (state.keepRunning()) {
            node.hasIdentityMatrix();
        }
    }

    @Test
    public void testSetOutline() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode node = RenderNode.create("LinearLayout", null);
        Outline a = new Outline();
        a.setRoundRect(0, 0, 100, 100, 10);
        Outline b = new Outline();
        b.setRect(50, 50, 150, 150);
        b.setAlpha(0.5f);

        while (state.keepRunning()) {
            node.setOutline(a);
            node.setOutline(b);
        }
    }
}
