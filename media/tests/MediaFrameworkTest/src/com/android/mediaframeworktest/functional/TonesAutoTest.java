/*
 * Copyright (C) 2008 The Android Open Source Project
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


//import android.content.Resources;
import android.util.Log;

import android.media.ToneGenerator;
import android.media.AudioManager;

/**
 * Junit / Instrumentation test case for the Sim tones tests
 
 */  
    public class TonesAutoTest {
        private static String TAG = "TonesAutoTest";

    // Test all DTMF tones one by one
    public static boolean tonesDtmfTest() throws Exception {
        Log.v(TAG, "DTMF tones test");
        ToneGenerator toneGen;
        int type;
        boolean result = true;

        toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        for (type = ToneGenerator.TONE_DTMF_0; type <= ToneGenerator.TONE_DTMF_D; type++) {
            if (toneGen.startTone(type)) {
                Thread.sleep(200);
                toneGen.stopTone();
                Thread.sleep(100);
            } else {
                result = false;
                break;
            }
        }

        toneGen.release();
        return result;
    }

    // Test all supervisory tones one by one
    public static boolean tonesSupervisoryTest() throws Exception {
      Log.v(TAG, "Supervisory tones test");
      ToneGenerator toneGen;
      int type;
      boolean result = true;

      toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
      
      for (type = ToneGenerator.TONE_SUP_DIAL;
      type <= ToneGenerator.TONE_SUP_RINGTONE; type++) {
          if (toneGen.startTone(type)) {
              Thread.sleep(2000);
              toneGen.stopTone();
              Thread.sleep(200);
          } else {
              result = false;
              break;
          }
      }

      for (type = ToneGenerator.TONE_SUP_INTERCEPT;
      type <= ToneGenerator.TONE_SUP_PIP; type++) {
          if (toneGen.startTone(type)) {
              Thread.sleep(5000);
              toneGen.stopTone();
              Thread.sleep(200);
          } else {
              result = false;
              break;
          }
      }

      toneGen.release();
      return result;
    }

    // Test all proprietary tones one by one
    public static boolean tonesProprietaryTest() throws Exception {
        Log.v(TAG, "Proprietary tones test");
        ToneGenerator toneGen;
        int type;
        boolean result = true;

        toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        for (type = ToneGenerator.TONE_PROP_BEEP; type <= ToneGenerator.TONE_PROP_BEEP2; type++) {
            if (toneGen.startTone(type)) {
                Thread.sleep(1000);
                toneGen.stopTone();
                Thread.sleep(100);
            } else {
                result = false;
                break;
            }
        }

        toneGen.release();
        return result;
    }

    // Test playback of 2 tones simultaneously
    public static boolean tonesSimultaneousTest() throws Exception {
        Log.v(TAG, "Simultaneous tones test");
        ToneGenerator toneGen1;
        ToneGenerator toneGen2;
        int type;
        boolean result = true;

        toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        toneGen2 = new ToneGenerator(AudioManager.STREAM_MUSIC, 50);

        if (toneGen1.startTone(ToneGenerator.TONE_DTMF_1)) {
            Thread.sleep(100);
            if (toneGen2.startTone(ToneGenerator.TONE_DTMF_2)) {
                Thread.sleep(500);
                toneGen1.stopTone();
                Thread.sleep(100);
                toneGen2.stopTone();
            } else {
                toneGen1.stopTone();
                result = false;
            }
        } else {
            result = false;
        }

        toneGen1.release();
        toneGen2.release();
        return result;
    }

    // Test start of new tone without stopping previous one 
    public static boolean tonesStressTest() throws Exception {
        Log.v(TAG, "Stress tones test");
        ToneGenerator toneGen;
        int type;
        boolean result = true;

        toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);

        for (type = ToneGenerator.TONE_DTMF_1; type <= ToneGenerator.TONE_DTMF_9; type++) {
            if (toneGen.startTone(type)) {
                Thread.sleep(200);
            } else {
                result = false;
                break;
            }
        }

        toneGen.release();
        return result;
    }
   
    // Perform all tones tests 
    public static boolean tonesAllTest() throws Exception {
        Log.v(TAG, "All tones tests");

        if (!tonesDtmfTest()) {
            return false;
        }
        if (!tonesSupervisoryTest()) {
            return false;
        }
        if (!tonesProprietaryTest()) {
            return false;
        }
        if (!tonesSimultaneousTest()) {
            return false;
        }
        if (!tonesStressTest()) {
            return false;
        }
        return true;
    }
}
