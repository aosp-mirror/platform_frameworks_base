/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.mediaframeworktest.power;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;
import android.media.MediaPlayer;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.io.File;

/**
 * Junit / Instrumentation test case for the power measurment the media player
 */
public class MediaPlayerPowerTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaPlayerPowerTest";
    private String MP3_POWERTEST =
            Environment.getExternalStorageDirectory().toString() + "/power_sample_mp3.mp3";
    private String MP3_STREAM = "http://75.17.48.204:10088/power_media/power_sample_mp3.mp3";
    private String OGG_STREAM = "http://75.17.48.204:10088/power_media/power_sample_ogg.mp3";
    private String AAC_STREAM = "http://75.17.48.204:10088/power_media/power_sample_aac.mp3";

    public MediaPlayerPowerTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    protected void setUp() throws Exception {
        getActivity();
        super.setUp();

    }

    public void audioPlayback(String filePath) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(filePath);
            mp.prepare();
            mp.start();
            Thread.sleep(200000);
            mp.stop();
            mp.release();
        } catch (Exception e) {
            Log.v(TAG, e.toString());
            assertTrue("MP3 Playback", false);
        }
    }

    // A very simple test case which start the audio player.
    // Power measurment will be done in other application.
    public void testPowerLocalMP3Playback() throws Exception {
        audioPlayback(MP3_POWERTEST);
    }

    public void testPowerStreamMP3Playback() throws Exception {
        audioPlayback(MP3_STREAM);
    }

    public void testPowerStreamOGGPlayback() throws Exception {
        audioPlayback(OGG_STREAM);
    }

    public void testPowerStreamAACPlayback() throws Exception {
        audioPlayback(AAC_STREAM);
    }
}
