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

package com.android.mediaframeworktest.functional.mediaplayback;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;
import com.android.mediaframeworktest.MediaProfileReader;
import com.android.mediaframeworktest.functional.CodecTest;

import android.content.Context;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

import java.io.File;

/**
 * Junit / Instrumentation test case for the media player api
 */
public class MediaPlayerApiTest extends  ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private boolean duratoinWithinTolerence = false;
    private String TAG = "MediaPlayerApiTest";
    private boolean isWMAEnable = false;
    private boolean isWMVEnable = false;

    Context mContext;

    public MediaPlayerApiTest() {
       super("com.android.mediaframeworktest", MediaFrameworkTest.class);
       isWMAEnable = MediaProfileReader.getWMAEnable();
       isWMVEnable = MediaProfileReader.getWMVEnable();
    }

    protected void setUp() throws Exception {
       //Insert a 2 second before launching the test activity. This is
       //the workaround for the race condition of requesting the updated surface.
       Thread.sleep(2000);
       getActivity();
       super.setUp();
    }

    public boolean verifyDuration(int duration, int expectedDuration){
      if ((duration > expectedDuration * 1.1) || (duration < expectedDuration * 0.9))
         return false;
      else
        return true;
    }   
    

    
    //Audio    
    //Wait for PV bugs for MP3 duration
    @MediumTest
    public void testMP3CBRGetDuration() throws Exception {
      int duration = CodecTest.getDuration(MediaNames.MP3CBR);
      duratoinWithinTolerence = verifyDuration(duration, MediaNames.MP3CBR_LENGTH);
      assertTrue("MP3CBR getDuration", duratoinWithinTolerence);  
    }
    
    @MediumTest
    public void testMP3VBRGetDuration() throws Exception {
      int duration = CodecTest.getDuration(MediaNames.MP3VBR);
      Log.v(TAG, "getDuration");
      duratoinWithinTolerence = verifyDuration(duration, MediaNames.MP3VBR_LENGTH);
      assertTrue("MP3VBR getDuration", duratoinWithinTolerence);  
    }

    @MediumTest
    public void testMIDIGetDuration() throws Exception {
      int duration = CodecTest.getDuration(MediaNames.MIDI);  
      duratoinWithinTolerence = verifyDuration(duration, MediaNames.MIDI_LENGTH);
      assertTrue("MIDI getDuration", duratoinWithinTolerence);  
    }

    @MediumTest
    public void testAMRGetDuration() throws Exception {
      int duration = CodecTest.getDuration(MediaNames.AMR);  
      duratoinWithinTolerence = verifyDuration(duration, MediaNames.AMR_LENGTH);
      assertTrue("AMR getDuration", duratoinWithinTolerence);  
    }
    
    /*
    public void testOGGGetDuration() throws Exception {
      int duration = CodecTest.getDuration(MediaNames.OGG);  
      duratoinWithinTolerence = verifyDuration(duration, MediaNames.OGG_LENGTH);
      assertTrue("OGG getDuration", duratoinWithinTolerence);  
    }*/
    
    
    //Test cases for GetCurrentPosition
    @LargeTest
    public void testMP3CBRGetCurrentPosition() throws Exception {
      boolean currentPosition = CodecTest.getCurrentPosition(MediaNames.MP3CBR);       
      assertTrue("MP3CBR GetCurrentPosition", currentPosition);  
    }
  
    @LargeTest
    public void testMP3VBRGetCurrentPosition() throws Exception {
      boolean currentPosition = CodecTest.getCurrentPosition(MediaNames.MP3VBR);  
      assertTrue("MP3VBR GetCurrentPosition", currentPosition);  
    }
  
    @LargeTest
    public void testMIDIGetCurrentPosition() throws Exception {
      boolean currentPosition = CodecTest.getCurrentPosition(MediaNames.MIDI);  
      assertTrue("MIDI GetCurrentPosition", currentPosition);  
    }

    @LargeTest
    public void testAMRGetCurrentPosition() throws Exception {
      boolean currentPosition = CodecTest.getCurrentPosition(MediaNames.AMR);  
      assertTrue("AMR GetCurrentPosition", currentPosition);  
    }  
    
    /*
    public void testOGGGetCurrentPosition() throws Exception {
      boolean currentPosition = CodecTest.getCurrentPosition(MediaNames.OGG);  
      assertTrue("OGG GetCurrentPosition", currentPosition);  
     */    
    
    //Test cases for pause
    @LargeTest
    public void testMP3CBRPause() throws Exception {
      boolean isPaused = CodecTest.pause(MediaNames.MP3CBR);       
      assertTrue("MP3CBR Pause", isPaused);  
    }
  
    @LargeTest
    public void testMP3VBRPause() throws Exception {
      boolean isPaused = CodecTest.pause(MediaNames.MP3VBR);  
      assertTrue("MP3VBR Pause", isPaused);  
    }
    
    @LargeTest
    public void testMIDIPause() throws Exception {
      boolean isPaused = CodecTest.pause(MediaNames.MIDI);  
      assertTrue("MIDI Pause", isPaused);  
    }

    @LargeTest
    public void testAMRPause() throws Exception {
      boolean isPaused = CodecTest.pause(MediaNames.AMR);  
      assertTrue("AMR Pause", isPaused);  
    }
    
    /*
    public void testOGGPause() throws Exception {
      boolean isPaused = CodecTest.pause(MediaNames.OGG);  
      assertTrue("OGG Pause", isPaused);  
    }*/
    
    @MediumTest
    public void testMP3CBRPrepareStopRelease() throws Exception {
      CodecTest.prepareStopRelease(MediaNames.MP3CBR);       
      assertTrue("MP3CBR prepareStopRelease", true);  
    }
    
    @MediumTest
    public void testMIDIPrepareStopRelease() throws Exception {
      CodecTest.prepareStopRelease(MediaNames.MIDI);  
      assertTrue("MIDI prepareStopRelease", true);  
    }
    
    //One test case for seek before start 
    @MediumTest
    public void testMP3CBRSeekBeforeStart() throws Exception {
      boolean seekBeforePlay = CodecTest.seektoBeforeStart(MediaNames.MP3CBR);       
      assertTrue("MP3CBR SeekBeforePlay", seekBeforePlay);  
    }
    
    //Skip test - Bug# 1120249
    /*
    public void testMP3CBRpreparePauseRelease() throws Exception {
      CodecTest.preparePauseRelease(MediaNames.MP3CBR);       
      assertTrue("MP3CBR preparePauseRelease", true);  
    }
  
    public void testMIDIpreparePauseRelease() throws Exception {
      CodecTest.preparePauseRelease(MediaNames.MIDI);  
      assertTrue("MIDI preparePauseRelease", true);  
    }
    */
    
    
    //Test cases for setLooping 
    @LargeTest
    public void testMP3CBRSetLooping() throws Exception {
      boolean isLoop = CodecTest.setLooping(MediaNames.MP3CBR);     
      assertTrue("MP3CBR setLooping", isLoop);  
    }
    
    @LargeTest
    public void testMP3VBRSetLooping() throws Exception {
      boolean isLoop = CodecTest.setLooping(MediaNames.MP3VBR);
      Log.v(TAG, "setLooping");
      assertTrue("MP3VBR setLooping", isLoop);  
    }
    
    @LargeTest
    public void testMIDISetLooping() throws Exception {
      boolean isLoop = CodecTest.setLooping(MediaNames.MIDI);  
      assertTrue("MIDI setLooping", isLoop);  
    }

    @LargeTest
    public void testAMRSetLooping() throws Exception {
      boolean isLoop = CodecTest.setLooping(MediaNames.AMR);  
      assertTrue("AMR setLooping", isLoop);  
    }
    
    /*
    public void testOGGSetLooping() throws Exception {
      boolean isLoop = CodecTest.setLooping(MediaNames.OGG);  
      assertTrue("OGG setLooping", isLoop);  
    } */ 
    
    //Test cases for seekTo
    @LargeTest
    public void testMP3CBRSeekTo() throws Exception {
      boolean isLoop = CodecTest.seekTo(MediaNames.MP3CBR);     
      assertTrue("MP3CBR seekTo", isLoop);  
    }
    
    @LargeTest
    public void testMP3VBRSeekTo() throws Exception {
      boolean isLoop = CodecTest.seekTo(MediaNames.MP3VBR);
      Log.v(TAG, "seekTo");
      assertTrue("MP3VBR seekTo", isLoop);  
    }
    
    @LargeTest
    public void testMIDISeekTo() throws Exception {
      boolean isLoop = CodecTest.seekTo(MediaNames.MIDI);  
      assertTrue("MIDI seekTo", isLoop);  
    }

    @LargeTest
    public void testAMRSeekTo() throws Exception {
      boolean isLoop = CodecTest.seekTo(MediaNames.AMR);  
      assertTrue("AMR seekTo", isLoop);  
    }
    
    /*
    public void testOGGSeekTo() throws Exception {
      boolean isLoop = CodecTest.seekTo(MediaNames.OGG);  
      assertTrue("OGG seekTo", isLoop);  
    }*/
    
    
    //Jump to the end of the files
    @LargeTest
    public void testMP3CBRSeekToEnd() throws Exception {
      boolean isEnd = CodecTest.seekToEnd(MediaNames.MP3CBR);     
      assertTrue("MP3CBR seekToEnd", isEnd);  
    }
    
    @LargeTest
    public void testMP3VBRSeekToEnd() throws Exception {
      boolean isEnd = CodecTest.seekToEnd(MediaNames.MP3VBR);
      Log.v(TAG, "seekTo");
      assertTrue("MP3VBR seekToEnd", isEnd);  
    }
    
    @LargeTest
    public void testMIDISeekToEnd() throws Exception {
      boolean isEnd = CodecTest.seekToEnd(MediaNames.MIDI);  
      assertTrue("MIDI seekToEnd", isEnd);  
    }
    
    @LargeTest
    public void testAMRSeekToEnd() throws Exception {
      boolean isEnd = CodecTest.seekToEnd(MediaNames.AMR);  
      assertTrue("AMR seekToEnd", isEnd);  
    }
    
    /*
    public void testOGGSeekToEnd() throws Exception {
      boolean isEnd = CodecTest.seekToEnd(MediaNames.OGG);  
      assertTrue("OGG seekToEnd", isEnd);  
    }*/
    
    @LargeTest
    public void testWAVSeekToEnd() throws Exception {
        boolean isEnd = CodecTest.seekToEnd(MediaNames.WAV);
        assertTrue("WAV seekToEnd", isEnd);
    }  
    
    @MediumTest
    public void testLargeVideoHeigth() throws Exception {
      int height = 0;
      height = CodecTest.videoHeight(MediaNames.VIDEO_LARGE_SIZE_3GP);
      Log.v(TAG, "Video height = " +  height);
      assertEquals("streaming video height", 240, height);           
    }
    
    @MediumTest
    public void testLargeVideoWidth() throws Exception {
      int width = 0;
      width = CodecTest.videoWidth(MediaNames.VIDEO_LARGE_SIZE_3GP);
      Log.v(TAG, "Video width = " +  width);
      assertEquals("streaming video width", 320, width);           
    }
    
    @LargeTest
    public void testVideoMP4SeekTo() throws Exception {
      boolean isSeek = CodecTest.videoSeekTo(MediaNames.VIDEO_MP4);
      assertTrue("Local MP4 SeekTo", isSeek);          
    }

    @LargeTest
    public void testVideoH263AACSeekTo() throws Exception {
      boolean isSeek = CodecTest.videoSeekTo(MediaNames.VIDEO_H263_AAC);
      assertTrue("H263AAC SeekTo", isSeek);         
    }
    
    @LargeTest
    public void testVideoH263AMRSeekTo() throws Exception {
      boolean isSeek = CodecTest.videoSeekTo(MediaNames.VIDEO_H263_AMR);
      assertTrue("H263AMR SeekTo", isSeek);         
    }
    
    @LargeTest
    public void testVideoH264AACSeekTo() throws Exception {
      boolean isSeek = CodecTest.videoSeekTo(MediaNames.VIDEO_H264_AAC);
      assertTrue("H264AAC SeekTo", isSeek);         
    }
    
    @LargeTest
    public void testVideoH264AMRSeekTo() throws Exception {
      boolean isSeek = CodecTest.videoSeekTo(MediaNames.VIDEO_H264_AMR);
      assertTrue("H264AMR SeekTo", isSeek);         
    }

    @LargeTest
    public void testVideoWebmSeekTo() throws Exception {
      boolean isSeek = CodecTest.videoSeekTo(MediaNames.VIDEO_WEBM);
      assertTrue("WEBM SeekTo", isSeek);
    }

    @LargeTest
    public void testSoundRecord() throws Exception {
      boolean isRecordered = CodecTest.mediaRecorderRecord(MediaNames.RECORDER_OUTPUT);
      assertTrue("Recorder", isRecordered);         
    }
  
    @LargeTest
    public void testGetThumbnail() throws Exception {
      boolean getThumbnail = CodecTest.getThumbnail(MediaNames.VIDEO_H264_AAC, MediaNames.GOLDEN_THUMBNAIL_OUTPUT);
      assertTrue("Get Thumbnail", getThumbnail);         
    }
    
    //Play a mid file which the duration is around 210 seconds
    @LargeTest
    public void testMidiResources() throws Exception {
      boolean midiResources = CodecTest.resourcesPlayback(MediaFrameworkTest.midiafd,16000);
      assertTrue("Play midi from resources", midiResources);         
    }
    
    @LargeTest
    public void testMp3Resources() throws Exception {
      boolean mp3Resources = CodecTest.resourcesPlayback(MediaFrameworkTest.mp3afd,25000);
      assertTrue("Play mp3 from resources", mp3Resources);         
    }
    
    @MediumTest
    public void testPrepareAsyncReset() throws Exception {
      //assertTrue(MediaFrameworkTest.checkStreamingServer());
      boolean isReset = CodecTest.prepareAsyncReset(MediaNames.STREAM_MP3);
      assertTrue("PrepareAsync Reset", isReset);         
    }
    
    @MediumTest
    public void testIsLooping() throws Exception {
        boolean isLooping = CodecTest.isLooping(MediaNames.AMR);
        assertTrue("isLooping", isLooping);
    }

    @MediumTest
    public void testIsLoopingAfterReset() throws Exception {
        boolean isLooping = CodecTest.isLoopingAfterReset(MediaNames.AMR);
        assertTrue("isLooping after reset", isLooping);
    }
    
    @LargeTest
    public void testLocalMp3PrepareAsyncCallback() throws Exception {
        boolean onPrepareSuccess = 
            CodecTest.prepareAsyncCallback(MediaNames.MP3CBR, false);
        assertTrue("LocalMp3prepareAsyncCallback", onPrepareSuccess);
    }

    @LargeTest
    public void testLocalH263AMRPrepareAsyncCallback() throws Exception {
        boolean onPrepareSuccess =
            CodecTest.prepareAsyncCallback(MediaNames.VIDEO_H263_AMR, false);
        assertTrue("testLocalH263AMRPrepareAsyncCallback", onPrepareSuccess);
    }
    
    @LargeTest
    public void testStreamPrepareAsyncCallback() throws Exception {
        //assertTrue(MediaFrameworkTest.checkStreamingServer());
        boolean onPrepareSuccess = 
            CodecTest.prepareAsyncCallback(MediaNames.STREAM_H264_480_360_1411k, false);
        assertTrue("StreamH264PrepareAsyncCallback", onPrepareSuccess);
    }
    
    @LargeTest
    public void testStreamPrepareAsyncCallbackReset() throws Exception {
        //assertTrue(MediaFrameworkTest.checkStreamingServer());
        boolean onPrepareSuccess = 
            CodecTest.prepareAsyncCallback(MediaNames.STREAM_H264_480_360_1411k, true);
        assertTrue("StreamH264PrepareAsyncCallback", onPrepareSuccess);
    }
}
