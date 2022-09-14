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

package android.graphics.perftests;

import static android.graphics.PathIterator.VERB_DONE;

import android.graphics.Path;
import android.graphics.PathIterator;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class PathIteratorPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Path constructCircularPath(int numSegments) {
        Path path = new Path();
        float angleIncrement = (float) (2 * Math.PI / numSegments);
        float radius = 200f;
        float prevX = 0f, prevY = 0f;
        float angle = 0f;
        for (int i = 0; i <= numSegments; ++i) {
            float x = (float) Math.cos(angle) * radius;
            float y = (float) Math.sin(angle) * radius;
            if (i > 0) {
                path.cubicTo(prevX, prevY, x, y, x, y);
            } else {
                path.moveTo(x, y);
            }
            prevX = x;
            prevY = y;
            angle += angleIncrement;
        }
        return path;
    }

    private void testNextSegmentImpl(int numSegments) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Path path = constructCircularPath(numSegments);
        while (state.keepRunning()) {
            PathIterator iterator = path.getPathIterator();
            PathIterator.Segment segment = iterator.next();
            while (segment.getVerb() != VERB_DONE) {
                segment = iterator.next();
            }
        }
    }

    private void testNextFloatsImpl(int numSegments) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        float[] points = new float[8];
        Path path = constructCircularPath(numSegments);
        while (state.keepRunning()) {
            PathIterator iterator = path.getPathIterator();
            int verb = iterator.next(points, 0);
            while (verb != VERB_DONE) {
                verb = iterator.next(points, 0);
            }
        }
    }

    @Test
    public void testNextSegment10() {
        testNextSegmentImpl(10);
    }

    @Test
    public void testNextSegment100() {
        testNextSegmentImpl(100);
    }

    @Test
    public void testNextSegment1000() {
        testNextSegmentImpl(1000);
    }

    @Test
    public void testNextArray10() {
        testNextFloatsImpl(10);
    }

    @Test
    public void testNextArray100() {
        testNextFloatsImpl(100);
    }

    @Test
    public void testNextArray1000() {
        testNextFloatsImpl(1000);
    }

}
