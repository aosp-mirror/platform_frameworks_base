/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.rs.image;

import android.os.Bundle;
import android.os.Environment;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * ImageProcessing benchmark test.
 * To run the test, please use command
 *
 * adb shell am instrument -w com.android.rs.image/.ImageProcessingTestRunner
 *
 */
public class ImageProcessingTest extends ActivityInstrumentationTestCase2<ImageProcessingActivity> {
    private final String TAG = "ImageProcessingTest";
    private final String RESULT_FILE = "image_processing_result.txt";
    private ImageProcessingActivity mAct;

    public ImageProcessingTest() {
        super(ImageProcessingActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mAct = getActivity();
   }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * ImageProcessing benchmark test
     */
    @LargeTest
    public void testImageProcessingBench() {
        long t = mAct.getBenchmark();
        Log.v(TAG, "t = " + t);

        // write result into a file
        File externalStorage = Environment.getExternalStorageDirectory();
        if (!externalStorage.canWrite()) {
            Log.v(TAG, "sdcard is not writable");
            return;
        }
        File resultFile = new File(externalStorage, RESULT_FILE);
        resultFile.setWritable(true, false);
        try {
            BufferedWriter results = new BufferedWriter(new FileWriter(resultFile));
            results.write("Renderscript frame time core: " + t + " ms");
            results.close();
            Log.v(TAG, "Saved results in: " + resultFile.getAbsolutePath());
        } catch (IOException e) {
            Log.v(TAG, "Unable to write result file " + e.getMessage());
        }
    }
}
