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

package com.android.mediaframeworktest.functional.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import androidx.test.filters.LargeTest;

import com.android.mediaframeworktest.MediaFrameworkTest;

/**
 * Junit / Instrumentation test case for the media AudioTrack api
 
 */  
public class MediaAudioTrackTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {    
    private String TAG = "MediaAudioTrackTest";
   
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
    
    private static void assumeTrue(String message, boolean cond) {
        assertTrue("(assume)"+message, cond);
    }
    
    private void log(String testName, String message) {
        Log.v(TAG, "["+testName+"] "+message);
    }
    
    private void loge(String testName, String message) {
        Log.e(TAG, "["+testName+"] "+message);
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
                        int _inTest_streamType, int _inTest_mode, 
                        int _inTest_config, int _inTest_format,
                        // parameter-dependent expected results
                        int _expected_stateForMode) {
        
        int[] testSampleRates = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};
        String failedRates = "Failure for rate(s): ";
        boolean localRes, finalRes = true;
        
        for (int i = 0 ; i < testSampleRates.length ; i++) {
            //Log.v("MediaAudioTrackTest", "[ constructorTestMultiSampleRate ] testing "+ testSampleRates[i]);
            AudioTrack track = null;
            try {
                track = new AudioTrack(
                        _inTest_streamType, 
                        testSampleRates[i], 
                        _inTest_config, 
                        _inTest_format,
                        AudioTrack.getMinBufferSize(testSampleRates[i], 
                                _inTest_config, _inTest_format), 
                        _inTest_mode);
            } catch(IllegalArgumentException iae) {
                Log.e("MediaAudioTrackTest", "[ constructorTestMultiSampleRate ] exception at SR "
                        + testSampleRates[i]+": \n" + iae);
                localRes = false;
            }
            if (track != null) {
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
                log("constructorTestMultiSampleRate", "failed to construct "
                        +"AudioTrack(streamType="+_inTest_streamType 
                        +", sampleRateInHz=" + testSampleRates[i]
                        +", channelConfig=" + _inTest_config
                        +", audioFormat=" + _inTest_format  
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
    //      AudioTrack constructor and AudioTrack.getMinBufferSize(...) for 16bit PCM
    //----------------------------------
       
    //Test case 1: constructor for streaming AudioTrack, mono, 16bit at misc valid sample rates
    @LargeTest
    public void testConstructorMono16MusicStream() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STREAM, 
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.STATE_INITIALIZED);

        assertTrue("testConstructorMono16MusicStream: " + res.mResultLog, res.mResult);
    }
    
    
    //Test case 2: constructor for streaming AudioTrack, stereo, 16bit at misc valid sample rates
    @LargeTest
    public void testConstructorStereo16MusicStream() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STREAM, 
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.STATE_INITIALIZED);

        assertTrue("testConstructorStereo16MusicStream: " + res.mResultLog, res.mResult);
    }
    
    
    //Test case 3: constructor for static AudioTrack, mono, 16bit at misc valid sample rates
    @LargeTest
    public void testConstructorMono16MusicStatic() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STATIC, 
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.STATE_NO_STATIC_DATA);

        assertTrue("testConstructorMono16MusicStatic: " + res.mResultLog, res.mResult);
    }
    
    
    //Test case 4: constructor for static AudioTrack, stereo, 16bit at misc valid sample rates
    @LargeTest
    public void testConstructorStereo16MusicStatic() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STATIC, 
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.STATE_NO_STATIC_DATA);

        assertTrue("testConstructorStereo16MusicStatic: " + res.mResultLog, res.mResult);
    }
    
    
    //-----------------------------------------------------------------
    //      AudioTrack constructor and AudioTrack.getMinBufferSize(...) for 8bit PCM
    //----------------------------------
       
    //Test case 1: constructor for streaming AudioTrack, mono, 8bit at misc valid sample rates
    @LargeTest
    public void testConstructorMono8MusicStream() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STREAM, 
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_8BIT,
                AudioTrack.STATE_INITIALIZED);

        assertTrue("testConstructorMono8MusicStream: " + res.mResultLog, res.mResult);
    }
    
    //Test case 2: constructor for streaming AudioTrack, stereo, 8bit at misc valid sample rates
    @LargeTest
    public void testConstructorStereo8MusicStream() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STREAM, 
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_8BIT,
                AudioTrack.STATE_INITIALIZED);

        assertTrue("testConstructorStereo8MusicStream: " + res.mResultLog, res.mResult);
    }
    
    //Test case 3: constructor for static AudioTrack, mono, 8bit at misc valid sample rates
    @LargeTest
    public void testConstructorMono8MusicStatic() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STATIC, 
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_8BIT,
                AudioTrack.STATE_NO_STATIC_DATA);

        assertTrue("testConstructorMono8MusicStatic: " + res.mResultLog, res.mResult);
    }
    
    //Test case 4: constructor for static AudioTrack, stereo, 8bit at misc valid sample rates
    @LargeTest
    public void testConstructorStereo8MusicStatic() throws Exception {
        
        TestResults res = constructorTestMultiSampleRate(
                AudioManager.STREAM_MUSIC, AudioTrack.MODE_STATIC, 
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_8BIT,
                AudioTrack.STATE_NO_STATIC_DATA);

        assertTrue("testConstructorStereo8MusicStatic: " + res.mResultLog, res.mResult);
    }
    
    
    //-----------------------------------------------------------------
    //      AudioTrack constructor for all stream types
    //----------------------------------
        
    //Test case 1: constructor for all stream types
    @LargeTest
    public void testConstructorStreamType() throws Exception {
        // constants for test
        final int TYPE_TEST_SR = 22050;
        final int TYPE_TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TYPE_TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TYPE_TEST_MODE = AudioTrack.MODE_STREAM;
        final int[] STREAM_TYPES = { AudioManager.STREAM_ALARM, AudioManager.STREAM_BLUETOOTH_SCO, 
                AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_RING, AudioManager.STREAM_SYSTEM, 
                AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_DTMF, };
        final String[] STREAM_NAMES = { "STREAM_ALARM", "STREAM_BLUETOOTH_SCO", "STREAM_MUSIC",
                "STREAM_NOTIFICATION", "STREAM_RING", "STREAM_SYSTEM", "STREAM_VOICE_CALL", "STREAM_DTMF" };
        
        boolean localTestRes = true;
        AudioTrack track = null;
        // test: loop constructor on all stream types
        for (int i = 0 ; i < STREAM_TYPES.length ; i++)
        {
            try {
            //-------- initialization --------------
                track = new AudioTrack(STREAM_TYPES[i], 
                        TYPE_TEST_SR, TYPE_TEST_CONF, TYPE_TEST_FORMAT,
                        AudioTrack.getMinBufferSize(TYPE_TEST_SR, TYPE_TEST_CONF, TYPE_TEST_FORMAT), 
                        TYPE_TEST_MODE);
            } catch (IllegalArgumentException iae) {
                loge("testConstructorStreamType", "exception for stream type "
                        + STREAM_NAMES[i] + ": "+ iae);
                localTestRes = false;
            }
            //--------  test   --------------
            if (track != null) {
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    localTestRes = false;
                    Log.e("MediaAudioTrackTest", 
                            "[ testConstructorStreamType ] failed for stream type "+STREAM_NAMES[i]);
                }
            //--------  tear down  --------------
                track.release();
            }
            else {
                localTestRes = false;
            }
        }

        assertTrue("testConstructorStreamType", localTestRes);
    }
    
    
    //-----------------------------------------------------------------
    //      Playback head position
    //----------------------------------
  
    //Test case 1: getPlaybackHeadPosition() at 0 after initialization
    @LargeTest
    public void testPlaybackHeadPositionAfterInit() throws Exception {
        // constants for test
        final String TEST_NAME = "testPlaybackHeadPositionAfterInit";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT), TEST_MODE);
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, track.getPlaybackHeadPosition() == 0);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 2: getPlaybackHeadPosition() increases after play()
    @LargeTest
    public void testPlaybackHeadPositionIncrease() throws Exception {
        // constants for test
        final String TEST_NAME = "testPlaybackHeadPositionIncrease";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        Thread.sleep(100);
        log(TEST_NAME, "position ="+ track.getPlaybackHeadPosition());
        assertTrue(TEST_NAME, track.getPlaybackHeadPosition() > 0);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 3: getPlaybackHeadPosition() is 0 after flush();
    @LargeTest
    public void testPlaybackHeadPositionAfterFlush() throws Exception {
        // constants for test
        final String TEST_NAME = "testPlaybackHeadPositionAfterFlush";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        Thread.sleep(100);
        track.stop();
        track.flush();
        log(TEST_NAME, "position ="+ track.getPlaybackHeadPosition());
        assertTrue(TEST_NAME, track.getPlaybackHeadPosition() == 0);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 3: getPlaybackHeadPosition() is 0 after stop();
    @LargeTest
    public void testPlaybackHeadPositionAfterStop() throws Exception {
        // constants for test
        final String TEST_NAME = "testPlaybackHeadPositionAfterStop";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        final int TEST_LOOP_CNT = 10;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        Thread.sleep(100);
        track.stop();
        int count = 0;
        int pos;
        do {
            Thread.sleep(200);
            pos = track.getPlaybackHeadPosition();
            count++;
        } while((pos != 0) && (count < TEST_LOOP_CNT));
        log(TEST_NAME, "position =" + pos + ", read count ="+count);
        assertTrue(TEST_NAME, pos == 0);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 4: getPlaybackHeadPosition() is > 0 after play(); pause();
    @LargeTest
    public void testPlaybackHeadPositionAfterPause() throws Exception {
        // constants for test
        final String TEST_NAME = "testPlaybackHeadPositionAfterPause";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        Thread.sleep(100);
        track.pause();
        int pos = track.getPlaybackHeadPosition();
        log(TEST_NAME, "position ="+ pos);
        assertTrue(TEST_NAME, pos > 0);
        //-------- tear down      --------------
        track.release();
    }
    
    
    //-----------------------------------------------------------------
    //      Playback properties
    //----------------------------------
    
    //Test case 1: setStereoVolume() with max volume returns SUCCESS
    @LargeTest
    public void testSetStereoVolumeMax() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetStereoVolumeMax";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        float maxVol = AudioTrack.getMaxVolume();
        assertTrue(TEST_NAME, track.setStereoVolume(maxVol, maxVol) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 2: setStereoVolume() with min volume returns SUCCESS
    @LargeTest
    public void testSetStereoVolumeMin() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetStereoVolumeMin";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        float minVol = AudioTrack.getMinVolume();
        assertTrue(TEST_NAME, track.setStereoVolume(minVol, minVol) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 3: setStereoVolume() with mid volume returns SUCCESS
    @LargeTest
    public void testSetStereoVolumeMid() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetStereoVolumeMid";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        float midVol = (AudioTrack.getMaxVolume() - AudioTrack.getMinVolume()) / 2;
        assertTrue(TEST_NAME, track.setStereoVolume(midVol, midVol) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 4: setPlaybackRate() with half the content rate returns SUCCESS
    @LargeTest
    public void testSetPlaybackRate() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackRate";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.play();
        assertTrue(TEST_NAME, track.setPlaybackRate((int)(TEST_SR/2)) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 5: setPlaybackRate(0) returns bad value error
    @LargeTest
    public void testSetPlaybackRateZero() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackRateZero";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, track.setPlaybackRate(0) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 6: setPlaybackRate() accepts values twice the output sample rate
    @LargeTest
    public void testSetPlaybackRateTwiceOutputSR() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackRateTwiceOutputSR";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        int outputSR = AudioTrack.getNativeOutputSampleRate(TEST_STREAM_TYPE);
        //--------    test        --------------
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.play();
        assertTrue(TEST_NAME, track.setPlaybackRate(2*outputSR) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 7: setPlaybackRate() and retrieve value, should be the same for half the content SR
    @LargeTest
    public void testSetGetPlaybackRate() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetGetPlaybackRate";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_STEREO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize/2];
        //--------    test        --------------
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.play();
        track.setPlaybackRate((int)(TEST_SR/2));
        assertTrue(TEST_NAME, track.getPlaybackRate() == (int)(TEST_SR/2));
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 8: setPlaybackRate() invalid operation if track not initialized
    @LargeTest
    public void testSetPlaybackRateUninit() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackRateUninit";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STATIC;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_NO_STATIC_DATA);
        assertTrue(TEST_NAME, 
                track.setPlaybackRate(TEST_SR/2) == AudioTrack.ERROR_INVALID_OPERATION);
        //-------- tear down      --------------
        track.release();
    }
    
    //-----------------------------------------------------------------
    //      Playback progress
    //----------------------------------
    
    //Test case 1: setPlaybackHeadPosition() on playing track
    @LargeTest
    public void testSetPlaybackHeadPositionPlaying() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackHeadPositionPlaying";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        assertTrue(TEST_NAME,
                track.setPlaybackHeadPosition(10) == AudioTrack.ERROR_INVALID_OPERATION);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 2: setPlaybackHeadPosition() on stopped track
    @LargeTest
    public void testSetPlaybackHeadPositionStopped() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackHeadPositionStopped";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        track.stop();
        assumeTrue(TEST_NAME, track.getPlayState() == AudioTrack.PLAYSTATE_STOPPED);
        assertTrue(TEST_NAME, track.setPlaybackHeadPosition(10) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 3: setPlaybackHeadPosition() on paused track
    @LargeTest
    public void testSetPlaybackHeadPositionPaused() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackHeadPositionPaused";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        track.pause();
        assumeTrue(TEST_NAME, track.getPlayState() == AudioTrack.PLAYSTATE_PAUSED);
        assertTrue(TEST_NAME, track.setPlaybackHeadPosition(10) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 4: setPlaybackHeadPosition() beyond what has been written
    @LargeTest
    public void testSetPlaybackHeadPositionTooFar() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetPlaybackHeadPositionTooFar";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        // make up a frame index that's beyond what has been written: go from buffer size to frame
        //   count (given the audio track properties), and add 77.
        int frameIndexTooFar = (2*minBuffSize/2) + 77;
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        track.write(data, 0, data.length);
        track.write(data, 0, data.length);
        track.play();
        track.stop();
        assumeTrue(TEST_NAME, track.getPlayState() == AudioTrack.PLAYSTATE_STOPPED);
        assertTrue(TEST_NAME, track.setPlaybackHeadPosition(frameIndexTooFar) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    
    //Test case 5: setLoopPoints() fails for MODE_STREAM
    @LargeTest
    public void testSetLoopPointsStream() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetLoopPointsStream";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, track.setLoopPoints(2, 50, 2) == AudioTrack.ERROR_INVALID_OPERATION);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 6: setLoopPoints() fails start > end
    @LargeTest
    public void testSetLoopPointsStartAfterEnd() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetLoopPointsStartAfterEnd";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STATIC;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, track.setLoopPoints(50, 0, 2) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 6: setLoopPoints() success
    @LargeTest
    public void testSetLoopPointsSuccess() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetLoopPointsSuccess";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STATIC;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, track.setLoopPoints(0, 50, 2) == AudioTrack.SUCCESS);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 7: setLoopPoints() fails with loop length bigger than content
    @LargeTest
    public void testSetLoopPointsLoopTooLong() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetLoopPointsLoopTooLong";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STATIC;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        int dataSizeInFrames = minBuffSize/2;
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_NO_STATIC_DATA);
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, 
                track.setLoopPoints(10, dataSizeInFrames+20, 2) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    //Test case 8: setLoopPoints() fails with start beyond what can be written for the track
    @LargeTest
    public void testSetLoopPointsStartTooFar() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetLoopPointsStartTooFar";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STATIC;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        int dataSizeInFrames = minBuffSize/2;//16bit data
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_NO_STATIC_DATA);
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, 
                track.setLoopPoints(dataSizeInFrames+20, dataSizeInFrames+50, 2) 
                    == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }

    //Test case 9: setLoopPoints() fails with end beyond what can be written for the track
    @LargeTest
    public void testSetLoopPointsEndTooFar() throws Exception {
        // constants for test
        final String TEST_NAME = "testSetLoopPointsEndTooFar";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STATIC;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        int dataSizeInFrames = minBuffSize/2;//16bit data
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_NO_STATIC_DATA);
        track.write(data, 0, data.length);
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME, 
                track.setLoopPoints(dataSizeInFrames-10, dataSizeInFrames+50, 2) 
                    == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    
    //-----------------------------------------------------------------
    //      Audio data supply
    //----------------------------------
    
    //Test case 1: write() fails when supplying less data (bytes) than declared
    @LargeTest
    public void testWriteByteOffsetTooBig() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteByteOffsetTooBig";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 10, data.length) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 2: write() fails when supplying less data (shorts) than declared
    @LargeTest
    public void testWriteShortOffsetTooBig() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteShortOffsetTooBig";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        short data[] = new short[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 10, data.length) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 3: write() fails when supplying less data (bytes) than declared
    @LargeTest
    public void testWriteByteSizeTooBig() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteByteSizeTooBig";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, data.length + 10) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 4: write() fails when supplying less data (shorts) than declared
    @LargeTest
    public void testWriteShortSizeTooBig() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteShortSizeTooBig";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        short data[] = new short[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, data.length + 10) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 5: write() fails with negative offset
    @LargeTest
    public void testWriteByteNegativeOffset() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteByteNegativeOffset";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, -10, data.length - 10) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 6: write() fails with negative offset
    @LargeTest
    public void testWriteShortNegativeOffset() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteShortNegativeOffset";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        short data[] = new short[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, -10, data.length - 10) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 7: write() fails with negative size
    @LargeTest
    public void testWriteByteNegativeSize() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteByteNegativeSize";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, -10) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 8: write() fails with negative size
    @LargeTest
    public void testWriteShortNegativeSize() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteShortNegativeSize";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        short data[] = new short[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, -10) == AudioTrack.ERROR_BAD_VALUE);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 9: write() succeeds and returns the size that was written for 16bit
    @LargeTest
    public void testWriteByte() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteByte";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, data.length) == data.length);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 10: write() succeeds and returns the size that was written for 16bit
    @LargeTest
    public void testWriteShort() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteShort";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        short data[] = new short[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, data.length) == data.length);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 11: write() succeeds and returns the size that was written for 8bit
    @LargeTest
    public void testWriteByte8bit() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteByte8bit";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_8BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        byte data[] = new byte[minBuffSize];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, data.length) == data.length);
        //-------- tear down      --------------
        track.release();
    }
    
    //Test case 12: write() succeeds and returns the size that was written for 8bit
    @LargeTest
    public void testWriteShort8bit() throws Exception {
        // constants for test
        final String TEST_NAME = "testWriteShort8bit";
        final int TEST_SR = 22050;
        final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
        final int TEST_FORMAT = AudioFormat.ENCODING_PCM_8BIT;
        final int TEST_MODE = AudioTrack.MODE_STREAM;
        final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
        
        //-------- initialization --------------
        int minBuffSize = AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT);
        AudioTrack track = new AudioTrack(TEST_STREAM_TYPE, TEST_SR, TEST_CONF, TEST_FORMAT, 
                2*minBuffSize, TEST_MODE);
        short data[] = new short[minBuffSize/2];
        //--------    test        --------------
        assumeTrue(TEST_NAME, track.getState() == AudioTrack.STATE_INITIALIZED);
        assertTrue(TEST_NAME,
                track.write(data, 0, data.length) == data.length);
        //-------- tear down      --------------
        track.release();
    }
    
    //-----------------------------------------------------------------
    //      Getters
    //----------------------------------
    
    //Test case 1: getMinBufferSize() return ERROR_BAD_VALUE if SR < 4000
    @LargeTest
    public void testGetMinBufferSizeTooLowSR() throws Exception {
      // constant for test
      final String TEST_NAME = "testGetMinBufferSizeTooLowSR";
      final int TEST_SR = 3999;
      final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
      final int TEST_FORMAT = AudioFormat.ENCODING_PCM_8BIT;
      final int TEST_MODE = AudioTrack.MODE_STREAM;
      final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
      
      //-------- initialization & test  --------------
      assertTrue(TEST_NAME, 
          AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT) 
              == AudioTrack.ERROR_BAD_VALUE);
    }    
    
    //Test case 2: getMinBufferSize() return ERROR_BAD_VALUE if SR > 48000
    @LargeTest
    public void testGetMinBufferSizeTooHighSR() throws Exception {
      // constant for testg
      final String TEST_NAME = "testGetMinBufferSizeTooHighSR";
      final int TEST_SR = 48001;
      final int TEST_CONF = AudioFormat.CHANNEL_OUT_MONO;
      final int TEST_FORMAT = AudioFormat.ENCODING_PCM_8BIT;
      final int TEST_MODE = AudioTrack.MODE_STREAM;
      final int TEST_STREAM_TYPE = AudioManager.STREAM_MUSIC;
      
      //-------- initialization & test --------------
      assertTrue(TEST_NAME, 
          AudioTrack.getMinBufferSize(TEST_SR, TEST_CONF, TEST_FORMAT) 
              == AudioTrack.ERROR_BAD_VALUE);
    }    
   
}
