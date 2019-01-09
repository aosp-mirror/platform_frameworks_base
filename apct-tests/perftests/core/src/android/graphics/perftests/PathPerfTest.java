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

import android.graphics.Path;
import android.graphics.RectF;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class PathPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testReset() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        while (state.keepRunning()) {
            path.reset();
        }
    }

    @Test
    public void testAddReset() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        while (state.keepRunning()) {
            path.addRect(0, 0, 100, 100, Path.Direction.CW);
            path.reset();
        }
    }

    @Test
    public void testRewind() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        while (state.keepRunning()) {
            path.rewind();
        }
    }

    @Test
    public void testAddRewind() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        while (state.keepRunning()) {
            path.addRect(0, 0, 100, 100, Path.Direction.CW);
            path.rewind();
        }
    }

    @Test
    public void testIsEmpty() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        path.addRect(0, 0, 100, 100, Path.Direction.CW);
        while (state.keepRunning()) {
            path.isEmpty();
        }
    }

    @Test
    public void testIsConvex() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        path.addRect(0, 0, 100, 100, Path.Direction.CW);
        while (state.keepRunning()) {
            path.isConvex();
        }
    }

    @Test
    public void testGetSetFillType() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        path.addRect(0, 0, 100, 100, Path.Direction.CW);
        while (state.keepRunning()) {
            path.setFillType(Path.FillType.EVEN_ODD);
            path.getFillType();
        }
    }

    @Test
    public void testIsRect() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = new Path();
        path.addRect(0, 0, 100, 100, Path.Direction.CW);
        final RectF outRect = new RectF();
        while (state.keepRunning()) {
            path.isRect(outRect);
        }
    }
}
