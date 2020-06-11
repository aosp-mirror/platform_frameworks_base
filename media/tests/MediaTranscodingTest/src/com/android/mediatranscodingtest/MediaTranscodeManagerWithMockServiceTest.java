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

package com.android.mediatranscodingtest;

import static org.testng.Assert.assertThrows;

import android.content.ContentResolver;
import android.content.Context;
import android.media.IMediaTranscodingService;
import android.media.ITranscodingClient;
import android.media.ITranscodingClientCallback;
import android.media.MediaFormat;
import android.media.MediaTranscodeManager;
import android.media.MediaTranscodeManager.TranscodingJob;
import android.media.MediaTranscodeManager.TranscodingRequest;
import android.media.TranscodingJobParcel;
import android.media.TranscodingRequestParcel;
import android.media.TranscodingResultParcel;
import android.media.TranscodingTestConfig;
import android.net.Uri;
import android.os.RemoteException;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Functional tests for MediaTranscodeManager in the media framework.
 * The test uses a mock Transcoding service as backend to test the API functionality.
 *
 * To run this test suite:
     make frameworks/base/media/tests/MediaTranscodingTest
     make mediatranscodingtest

     adb install -r testcases/mediatranscodingtest/arm64/mediatranscodingtest.apk

     adb shell am instrument -e class \
     com.android.mediatranscodingtest.MediaTranscodeManagerWithMockServiceTest \
     -w com.android.mediatranscodingtest/.MediaTranscodingTestRunner
 *
 */
public class MediaTranscodeManagerWithMockServiceTest
        extends ActivityInstrumentationTestCase2<MediaTranscodingTest> {
    private static final String TAG = "MediaTranscodeManagerWithMockServiceTest";
    /** The time to wait for the transcode operation to complete before failing the test. */
    private static final int TRANSCODE_TIMEOUT_SECONDS = 2;
    private Context mContext;
    private MediaTranscodeManager mMediaTranscodeManager = null;
    private Uri mSourceHEVCVideoUri = null;
    private Uri mDestinationUri = null;

    // Use mock transcoding service for testing the api.
    private MockTranscodingService mTranscodingService = null;

    // Setting for transcoding to H.264.
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int BIT_RATE = 2000000;            // 2Mbps
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    // A mock transcoding service that will take constant 300ms to process each transcoding job.
    // Instead of doing real transcoding, it will return the dst uri directly.
    class MockTranscodingService extends IMediaTranscodingService.Stub {
        private final ScheduledExecutorService mJobScheduler = Executors.newScheduledThreadPool(1);
        private int mNumOfClients = 0;
        private AtomicInteger mJobId = new AtomicInteger();

        // A runnable that will process the job.
        private class ProcessingJobRunnable implements Runnable {
            private TranscodingJobParcel mJob;
            private ITranscodingClientCallback mCallback;
            private ConcurrentMap<Integer, ScheduledFuture<?>> mJobMap;

            ProcessingJobRunnable(ITranscodingClientCallback callback,
                    TranscodingJobParcel job,
                    ConcurrentMap<Integer, ScheduledFuture<?>> jobMap) {
                mJob = job;
                mCallback = callback;
                mJobMap = jobMap;
            }

            @Override
            public void run() {
                Log.d(TAG, "Start to process job " + mJob.jobId);
                TranscodingResultParcel result = new TranscodingResultParcel();
                try {
                    mCallback.onTranscodingFinished(mJob.jobId, result);
                    // Removes the job from job map.
                    mJobMap.remove(mJob.jobId);
                } catch (RemoteException re) {
                    Log.e(TAG, "Failed to callback to client");
                }
            }
        }

        @Override
        public ITranscodingClient registerClient(ITranscodingClientCallback callback,
                String clientName, String opPackageName, int clientUid, int clientPid)
                throws RemoteException {
            Log.d(TAG, "MockTranscodingService creates one client");

            ITranscodingClient client = new ITranscodingClient.Stub() {
                private final ConcurrentMap<Integer, ScheduledFuture<?>> mPendingTranscodingJobs =
                        new ConcurrentHashMap<Integer, ScheduledFuture<?>>();

                @Override
                public boolean submitRequest(TranscodingRequestParcel inRequest,
                        TranscodingJobParcel outjob) {
                    Log.d(TAG, "Mock client gets submitRequest");
                    try {
                        outjob.request = inRequest;
                        outjob.jobId = mJobId.getAndIncrement();
                        Log.i(TAG, "Generate new job " + outjob.jobId);
                        Log.i(TAG, "Source Uri " + inRequest.sourceFilePath);
                        Log.i(TAG, "Destination Uri " + inRequest.destinationFilePath);

                        // Schedules the job to run after inRequest.processingDelayMs.
                        ScheduledFuture<?> transcodingFuture = mJobScheduler.schedule(
                                new ProcessingJobRunnable(callback, outjob,
                                        mPendingTranscodingJobs),
                                inRequest.testConfig == null ? 0
                                        : inRequest.testConfig.processingTotalTimeMs,
                                TimeUnit.MILLISECONDS);
                        mPendingTranscodingJobs.put(outjob.jobId, transcodingFuture);
                    } catch (RejectedExecutionException e) {
                        Log.e(TAG, "Failed to schedule transcoding job: " + e);
                        return false;
                    }

                    return true;
                }

                @Override
                public boolean cancelJob(int jobId) throws RemoteException {
                    Log.d(TAG, "Mock client gets cancelJob " + jobId);
                    // Cancels the job is still in the mPendingTranscodingJobs.
                    if (mPendingTranscodingJobs.containsKey(jobId)) {
                        // Cancel the future task for transcoding.
                        mPendingTranscodingJobs.get(jobId).cancel(true);

                        // Remove the job from the mPendingTranscodingJobs.
                        mPendingTranscodingJobs.remove(jobId);
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean getJobWithId(int jobId, TranscodingJobParcel job)
                        throws RemoteException {
                    // This will be implemented this if needed in the test.
                    return true;
                }

                @Override
                public void unregister() throws RemoteException {
                    Log.d(TAG, "Mock client gets unregister");
                    // This will be implemented this if needed in the test.
                    mNumOfClients--;
                }
            };
            mNumOfClients++;
            return client;
        }

        @Override
        public int getNumOfClients() throws RemoteException {
            return mNumOfClients;
        }
    }

    public MediaTranscodeManagerWithMockServiceTest() {
        super("com.android.MediaTranscodeManagerWithMockServiceTest", MediaTranscodingTest.class);
    }


    private static Uri resourceToUri(Context context, int resId) {
        Uri uri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.getResources().getResourcePackageName(resId))
                .appendPath(context.getResources().getResourceTypeName(resId))
                .appendPath(context.getResources().getResourceEntryName(resId))
                .build();
        return uri;
    }

    private static Uri generateNewUri(Context context, String filename) {
        File outFile = new File(context.getExternalCacheDir(), filename);
        return Uri.fromFile(outFile);
    }

    // Generates a invalid uri which will let the mock service return transcoding failure.
    private static Uri generateInvalidTranscodingUri(Context context) {
        File outFile = new File(context.getExternalCacheDir(), "InvalidUri.mp4");
        return Uri.fromFile(outFile);
    }

    /**
     * Creates a MediaFormat with the basic set of values.
     */
    private static MediaFormat createMediaFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        return format;
    }

    @Override
    public void setUp() throws Exception {
        Log.d(TAG, "setUp");
        super.setUp();
        mTranscodingService = new MockTranscodingService();
        mContext = getInstrumentation().getContext();
        mMediaTranscodeManager = MediaTranscodeManager.getInstance(mContext, mTranscodingService);
        assertNotNull(mMediaTranscodeManager);

        // Setup source HEVC file uri.
        mSourceHEVCVideoUri = resourceToUri(mContext, R.raw.VideoOnlyHEVC);

        // Setup destination file.
        mDestinationUri = generateNewUri(mContext, "transcoded.mp4");
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Verify that setting null destination uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithNullDestinationUri() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setDestinationUri(null)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that setting null source uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithNullSourceUri() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(null)
                            .setDestinationUri(mDestinationUri)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .build();
        });
    }

    /**
     * Verify that not setting source uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithoutSourceUri() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setDestinationUri(mDestinationUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that not setting destination uri will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithoutDestinationUri() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that setting image transcoding mode will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithUnsupportedMode() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setDestinationUri(mDestinationUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_IMAGE)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .setVideoTrackFormat(createMediaFormat())
                            .build();
        });
    }

    /**
     * Verify that setting video transcoding without setting video format will throw exception.
     */
    @Test
    public void testCreateTranscodingRequestWithoutVideoFormat() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> {
            TranscodingRequest request =
                    new TranscodingRequest.Builder()
                            .setSourceUri(mSourceHEVCVideoUri)
                            .setDestinationUri(mDestinationUri)
                            .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                            .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                            .build();
        });
    }

    @Test
    public void testTranscodingOneVideo() throws Exception {
        Log.d(TAG, "Starting: testMediaTranscodeManager");

        Semaphore transcodeCompleteSemaphore = new Semaphore(0);
        TranscodingTestConfig testConfig = new TranscodingTestConfig();
        testConfig.passThroughMode = true;
        testConfig.processingTotalTimeMs = 300; // minimum time spent on transcoding.

        TranscodingRequest request =
                new TranscodingRequest.Builder()
                        .setSourceUri(mSourceHEVCVideoUri)
                        .setDestinationUri(mDestinationUri)
                        .setType(MediaTranscodeManager.TRANSCODING_TYPE_VIDEO)
                        .setPriority(MediaTranscodeManager.PRIORITY_REALTIME)
                        .setVideoTrackFormat(createMediaFormat())
                        .setTestConfig(testConfig)
                        .build();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();

        TranscodingJob job = mMediaTranscodeManager.enqueueRequest(request, listenerExecutor,
                transcodingJob -> {
                    Log.d(TAG, "Transcoding completed with result: ");
                    transcodeCompleteSemaphore.release();
                });
        assertNotNull(job);

        if (job != null) {
            Log.d(TAG, "testMediaTranscodeManager - Waiting for transcode to complete.");
            boolean finishedOnTime = transcodeCompleteSemaphore.tryAcquire(
                    TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue("Transcode failed to complete in time.", finishedOnTime);
        }
    }

}
