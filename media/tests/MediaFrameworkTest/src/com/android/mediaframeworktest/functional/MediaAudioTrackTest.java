/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.content.Context;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

/**
 * Junit / Instrumentation test case for the media AudioTrack api
 
 */  
public class MediaAudioTrackTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {    
    private String TAG = "MediaAudioTrack";
   
    public MediaAudioTrackTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
      super.setUp();
    }
    
    @Override 
    protected void tearDown() throws Exception {     
        super.tearDown();              
    }
    
    //-----------------------------------------------------------------
    // private class to hold test reslts
    public class TestResults {
        public boolean mResult = false;
        public String  mResultLog = "";
        public TestResults(boolean b, String s) { mResult = b; mResultLog = s; }
    }
    
    //-----------------------------------------------------------------
    // generic test methods
    public TestResults constructorTestMultiSampleRate(
                        // parameters tested by this method
                        int _inTest_streamType, int _inTest_mode, int _inTest_config,
                        // parameter-dependent expected results
                        int _expected_stateForMode) {
        
        int[] testSampleRates = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};
        String failedRates = "Failure for rate(s): ";
        boolean localRes, finalRes = true;
        
        for(int i = 0 ; i < testSampleRates.length ; i++) {
            //Log.v("MediaAudioTrackTest", "[ constructorTestMultiSampleRate ] testing "+ testSampleRates[i]);
            AudioTrack track = null;
            try {
                track = new AudioTrack(
                        _inTest_streamType, 
                        testSampleRates[i], 
                        _inTest_config, 
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioTrack.getMinBufferSize(testSampleRates[i], 
                                _inTest_config, AudioFormat.ENCODING_PCM_16BIT),//testSampleRates[i]*4 
                        _inTest_mode);
            } catch(IllegalArgumentException iae) {
                Log.e("MediaAudioTrackTest", "[ constructorTestMultiSampleRate ] exception at SR "
                        + testSampleRates[i]+": \n" + iae);
            }
            if(track != null) {
                localRes = (track.getState() == _expected_stateForMode);
                track.release();
            }
            else {
                localRes = false;
            }
            
            if (!localRes) {
                //log the error for the test runner
                failedRates += Integer.toString(testSampleRates[i]) + "Hz ";
                //log the error for logcat
                Log.e("MediaAudioTrackTest", "[ constructorTestMultiSampleRate ] failed to construct "
                        +"AudioTrack(streamType="+_inTest_streamType 
                        +", sampleRateInHz=" + testSampleRates[i]
                        +", channelConfig=" + _inTest_config
                        +", audioFormat=AudioFormat.ENCODING_PCM_16BIT"  
                        +", bufferSizeInBytes=" + AudioTrack.getMinBufferSize(testSampleRates[i], 
                                _inTest_config, AudioFormat.ENCODING_PCM_16BIT)
                        +", mode="+ _inTest_mode );
                //mark test as failed
                finalRes = false;
            }
        }
        return new TestResults(finalRes, failedRates);
    }
    
    //-----------------------------------------------------------------
    // AUDIOTRACK TESTS:
    //----------------------------------
    
    //-----------------------------------------------------------------
    //      AudioTrack constructor and AudioTrack.getMinBufferSize(...)
    //----------------------------------
       
    //Test case 1: constructor for streaming AudioTrack, mono, 16bit at misc valid sample rates
    @MediumTest
    public void testConstructorMono16MusicStream() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STREAM, 
                    AudioFormat.CHANNEL_CONFIGURATION_MONO,
                AudioTrack.STATE_INITIALIZED);

        assertTrue("testConstructorMono16MusicStream: " + res.mResultLog, res.mResult);
    }
    
    
    //Test case 2: constructor for streaming AudioTrack, stereo, 16bit at misc valid sample rates
    @MediumTest
    public void testConstructorStereo16MusicStream() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STREAM, 
                    AudioFormat.CHANNEL_CONFIGURATION_STEREO,
                AudioTrack.STATE_INITIALIZED);

        assertTrue("testConstructorStereo16MusicStream: " + res.mResultLog, res.mResult);
    }
    
    
    //Test case 3: constructor for static AudioTrack, mono, 16bit at misc valid sample rates
    @MediumTest
    public void testConstructorMono16MusicStatic() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STATIC, 
                    AudioFormat.CHANNEL_CONFIGURATION_MONO,
                AudioTrack.STATE_NO_STATIC_DATA);

        assertTrue("testConstructorMono16MusicStatic: " + res.mResultLog, res.mResult);
    }
    
    
    //Test case 4: constructor for static AudioTrack, stereo, 16bit at misc valid sample rates
    @MediumTest
    public void testConstructorStereo16MusicStatic() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STATIC, 
                    AudioFormat.CHANNEL_CONFIGURATION_STEREO,
                AudioTrack.STATE_NO_STATIC_DATA);

        assertTrue("testConstructorStereo16MusicStatic: " + res.mResultLog, res.mResult);
    }

}

