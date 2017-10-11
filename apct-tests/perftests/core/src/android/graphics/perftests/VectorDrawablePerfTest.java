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

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.VectorDrawable;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.BitmapUtils;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.StubActivity;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.perftests.core.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import static junit.framework.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class VectorDrawablePerfTest {

    private static final boolean DUMP_BITMAP = false;

    private int[] mTestWidths = {1024, 512};
    private int[] mTestHeights = {512, 1024};

    @Rule
    public ActivityTestRule<StubActivity> mActivityRule =
            new ActivityTestRule(StubActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testBitmapDrawPerf() {
        int resId = R.drawable.vector_drawable01;
        Activity activity = mActivityRule.getActivity();
        VectorDrawable vd = (VectorDrawable) activity.getDrawable(resId);

        int w = 1024, h = 1024;
        Bitmap.Config conf = Bitmap.Config.ARGB_8888;
        Bitmap bmp = Bitmap.createBitmap(w, h, conf);
        Canvas canvas = new Canvas(bmp);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 0;
        while (state.keepRunning()) {
            // Use different width / height each to force the vectorDrawable abandon the cache.
            vd.setBounds(0, 0, mTestWidths[i % 2], mTestHeights[i % 2]);
            i++;
            vd.draw(canvas);
        }

        // Double check the bitmap pixels to make sure we draw correct content.
        int backgroundColor = bmp.getPixel(w / 4, h / 2);
        int objColor = bmp.getPixel(w / 8, h / 2 + 1);
        int emptyColor = bmp.getPixel(w * 3 / 4, h * 3 / 4);
        assertTrue("The background should be white", backgroundColor == Color.WHITE);
        assertTrue("The object should be black", objColor == Color.BLACK);
        assertTrue("The right bottom part should be empty", emptyColor == Color.TRANSPARENT);

        if (DUMP_BITMAP) {
            BitmapUtils.saveBitmapIntoPNG(activity, bmp, resId);
        }
    }
}
