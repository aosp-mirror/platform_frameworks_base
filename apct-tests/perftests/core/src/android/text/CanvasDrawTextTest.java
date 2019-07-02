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
package android.text;

import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class CanvasDrawTextTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.

    private static final TextPaint PAINT = new TextPaint();

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    @Test
    public void drawText_LongText_SmallWindow() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = mTextUtil.nextRandomParagraph(
                WORD_LENGTH, 4 * 1024 * 1024 /* 4mb text */).toString();
        final RenderNode node = RenderNode.create("benchmark", null);
        final RenderNode child = RenderNode.create("child", null);
        child.setLeftTopRightBottom(50, 50, 100, 100);

        RecordingCanvas canvas = node.start(100, 100);
        node.end(canvas);
        canvas = child.start(50, 50);
        child.end(canvas);

        final Random r = new Random(0);

        while (state.keepRunning()) {
            int start = r.nextInt(text.length() - 100);
            canvas.drawText(text, start, start + 100, 0, 0, PAINT);
        }
    }
}
