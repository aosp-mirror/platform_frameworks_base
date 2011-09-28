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

package com.android.mediaframeworktest.functional.audio;

import com.android.mediaframeworktest.MediaFrameworkTest;
import android.content.Context;
import android.media.AudioManager;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Junit / Instrumentation test case for the media AudioManager api
 */

public class MediaAudioManagerTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {

    private String TAG = "MediaAudioManagerTest";
    private AudioManager mAudioManager;
    private int[] ringtoneMode = {AudioManager.RINGER_MODE_NORMAL,
             AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE};

    public MediaAudioManagerTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mAudioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
     }

     @Override
     protected void tearDown() throws Exception {
         super.tearDown();
     }

     public boolean validateSetRingTone(int i) {
         int getRingtone = mAudioManager.getRingerMode();
         if (i != getRingtone)
             return false;
         else
             return true;
     }

     // Test case 1: Simple test case to validate the set ringtone mode
     @MediumTest
     public void testSetRingtoneMode() throws Exception {
         boolean result = false;

         for (int i = 0; i < ringtoneMode.length; i++) {
             mAudioManager.setRingerMode(ringtoneMode[i]);
             result = validateSetRingTone(ringtoneMode[i]);
             assertTrue("SetRingtoneMode : " + ringtoneMode[i], result);
         }
     }
 }
