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

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class CanvasPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testBasicViewGroupDraw() {
        // This test is a clone of BM_DisplayListCanvas_basicViewGroupDraw

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode node = RenderNode.create("benchmark", null);
        RenderNode child = RenderNode.create("child", null);
        child.setLeftTopRightBottom(50, 50, 100, 100);

        RecordingCanvas canvas = node.start(100, 100);
        node.end(canvas);
        canvas = child.start(50, 50);
        canvas.drawColor(Color.WHITE);
        child.end(canvas);

        while (state.keepRunning()) {
            canvas = node.start(200, 200);
            int save = canvas.save();
            canvas.clipRect(1, 1, 199, 199);
            canvas.enableZ();
            for (int i = 0; i < 5; i++) {
                canvas.drawRenderNode(child);
            }
            canvas.disableZ();
            canvas.restoreToCount(save);
            node.end(canvas);
        }
    }

    @Test
    public void testRecordSimpleBitmapView() {
        // This test is a clone of BM_DisplayListCanvas_record_simpleBitmapView

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        RenderNode node = RenderNode.create("benchmark", null);

        RecordingCanvas canvas = node.start(100, 100);
        node.end(canvas);
        Bitmap bitmap = Bitmap.createBitmap(80, 80, Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);

        while (state.keepRunning()) {
            canvas = node.start(100, 100);
            {
                canvas.save();
                canvas.drawRect(0, 0, 100, 100, paint);
                canvas.restore();
            }
            {
                canvas.save();
                canvas.translate(10, 10);
                canvas.drawBitmap(bitmap, 0, 0, null);
                canvas.restore();
            }
            node.end(canvas);
        }
    }
}
