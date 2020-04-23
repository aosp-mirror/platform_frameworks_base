/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.mediaframeworktest.functional;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaTranscodeManager;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import com.android.mediaframeworktest.MediaFrameworkTest;

import org.junit.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/*
 * Functional tests for MediaTranscodeManager in the media framework.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaFrameworkTest
     make mediaframeworktest

     adb install -r out/target/product/dream/data/app/mediaframeworktest.apk

     adb shell am instrument -e class \
     com.android.mediaframeworktest.functional.MediaTranscodeManagerTest \
     -w com.android.mediaframeworktest/.MediaFrameworkTestRunner
 *
 */
public class MediaTranscodeManagerTest
        extends ActivityInstrumentationTestCase2<MediaFrameworkTest>  {
    private static final String TAG = "MediaTranscodeManagerTest";
    private Context mContext;
    private MediaTranscodeManager mMediaTranscodeManager = null;

    /**  The time to wait for the transcode operation to complete before failing the test. */
    private static final int TRANSCODE_TIMEOUT_SECONDS = 2;

    public MediaTranscodeManagerTest() {
        super("com.android.MediaTranscodeManagerTest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getActivity();
        mMediaTranscodeManager =
                MediaTranscodeManager.getInstance(mContext);
        assertNotNull(mMediaTranscodeManager);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testMediaTranscodeManager() throws InterruptedException {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        Semaphore transcodeCompleteSemaphore = new Semaphore(0);
        MediaTranscodeManager.TranscodingRequest request =
                new MediaTranscodeManager.TranscodingRequest.Builder().build();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();

        MediaTranscodeManager.TranscodingJob job;
        job = mMediaTranscodeManager.enqueueTranscodingRequest(request, listenerExecutor,
                transcodingJob -> {
                Log.d(TAG, "Transcoding completed with result: " + transcodingJob.getResult());
                transcodeCompleteSemaphore.release();
            });
        assertNotNull(job);

        job.setOnProgressChangedListener(
                listenerExecutor, progress -> Log.d(TAG, "Progress: " + progress));

        if (job != null) {
            Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to complete.");
            boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                    TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue("Transcode failed to complete in time.", finishedOnTime);
        }
    }
}
