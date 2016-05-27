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
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.frameworks.perftests.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@LargeTest
public class VectorDrawablePerfTest extends ActivityInstrumentationTestCase2<StubActivity> {

    private static final String TAG = "PathPerfTest";
    private static final int REPEAT_TIMES = 200;
    private static final boolean DBG_PERF = false;

    private int[] mTestWidths = {1024, 512};
    private int[] mTestHeights = {512, 1024};

    private Instrumentation mInstrument = new Instrumentation();
    private String KEY_VECTORDRAWABLE_DRAW_TIME = "VectorDrawable_Draw_Time_MicroSec";

    public VectorDrawablePerfTest() {
        super(StubActivity.class);
    }

    // Save a bitmap into a PNG, only for debugging purpose.
    private void saveBitmapIntoPNG(Bitmap bitmap, int resId) throws IOException {
        // Save the image to the disk.
        FileOutputStream out = null;
        try {
            String originalFilePath = getActivity().getResources().getString(resId);
            File originalFile = new File(originalFilePath);
            String fileFullName = originalFile.getName();
            String fileTitle = fileFullName.substring(0, fileFullName.lastIndexOf("."));

            File externalFilesDir = getActivity().getExternalFilesDir(null);
            File outputFile = new File(externalFilesDir, fileTitle + "_golden.png");
            if (!outputFile.exists()) {
                outputFile.createNewFile();
            }

            out = new FileOutputStream(outputFile, false);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            Log.v(TAG, "Write test No." + outputFile.getAbsolutePath() + " to file successfully.");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    @LargeTest
    public void testVectorDrawableInflatePerf() throws IOException {
        int resId = R.drawable.vector_drawable01;
        VectorDrawable vd = (VectorDrawable) getActivity().getDrawable(resId);

        int w = 1024, h = 1024;
        Bitmap.Config conf = Bitmap.Config.ARGB_8888;
        Bitmap bmp = Bitmap.createBitmap(w, h, conf);
        Canvas canvas = new Canvas(bmp);

        long startTime = System.nanoTime();
        for (int i = 0; i < REPEAT_TIMES; i++) {
            // Use different width / height each to force the vectorDrawable abandon the cache.
            vd.setBounds(0, 0, mTestWidths[i % 2], mTestHeights[i % 2]);
            vd.draw(canvas);
        }
        long duration = System.nanoTime() - startTime;
        long avgDurationMicroSecond = duration / REPEAT_TIMES / 1000;

        // Double check the bitmap pixels to make sure we draw correct content.
        int backgroundColor = bmp.getPixel(w / 4, h / 2);
        int objColor = bmp.getPixel(w / 8, h / 2);
        assertTrue("The background should be white", backgroundColor == 0xFFFFFFFF);
        assertTrue("The object should be black", objColor == 0xFF000000);

        if (DBG_PERF) {
            Log.v(TAG, "avg drawing vector drawable in bitmap size " + w + "x" + h + ":"
                    + avgDurationMicroSecond + "micro seconds");
        }

        final Bundle status = new Bundle();
        status.putLong(KEY_VECTORDRAWABLE_DRAW_TIME, avgDurationMicroSecond);
        mInstrument.sendStatus(Activity.RESULT_OK, status);
    }
}
