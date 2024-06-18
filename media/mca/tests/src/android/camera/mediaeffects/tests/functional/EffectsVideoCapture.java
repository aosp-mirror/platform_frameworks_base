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

package android.camera.mediaeffects.tests.functional;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.filterfw.samples.CameraEffectsRecordingSample;
import android.net.Uri;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.view.KeyEvent;

import androidx.test.filters.LargeTest;

import java.io.File;

public class EffectsVideoCapture extends ActivityInstrumentationTestCase2
                                               <CameraEffectsRecordingSample> {
    private static final String TAG = "EffectsVideoCaptureTest";
    private static final long WAIT_FOR_PREVIEW = 4 * 1000; // 4 seconds

    public EffectsVideoCapture() {
        super(CameraEffectsRecordingSample.class);
    }

    private void captureVideos(String reportTag, Instrumentation inst) throws Exception{
        int total_num_of_videos = 1;
        int video_duration = 4 * 1000; // 4 seconds

        Log.v(TAG, reportTag);
        for (int i = 0; i < total_num_of_videos; i++) {
            Thread.sleep(WAIT_FOR_PREVIEW);
            // record a video
            inst.sendCharacterSync(KeyEvent.KEYCODE_CAMERA);
            Thread.sleep(video_duration);
            inst.sendCharacterSync(KeyEvent.KEYCODE_CAMERA);
        }
    }

    @LargeTest
    public void testBackEffectsVideoCapture() throws Exception {
        Instrumentation inst = getInstrumentation();

        Intent intent = new Intent();
        intent.setClass(getInstrumentation().getTargetContext(),
                CameraEffectsRecordingSample.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("OUTPUT_FILENAME", Environment.getExternalStorageDirectory().toString()
                + "/CameraEffectsRecordingTest.mp4");
        Activity act = inst.startActivitySync(intent);
        captureVideos("Back Camera Video Capture\n", inst);
        act.finish();

        // Verification
        File file = new File(Environment.getExternalStorageDirectory(),
                "CameraEffectsRecordingTest.mp4");
        Uri uri = Uri.fromFile(file);
        verify(getActivity(), uri);
    }

    // Verify result code, result data, and the duration.
    private void verify(CameraEffectsRecordingSample activity, Uri uri) throws Exception {
        assertNotNull(uri);
        // Verify the video file
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(activity, uri);
        String duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
        assertNotNull(duration);
        int durationValue = Integer.parseInt(duration);
        Log.v(TAG, "Video duration is " + durationValue);
        assertTrue(durationValue > 0);
    }
}
