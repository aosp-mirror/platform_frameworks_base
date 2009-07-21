/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mediaframeworktest.performance;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;

import android.database.sqlite.SQLiteDatabase;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;

import android.media.MediaMetadataRetriever;

/**
 * Junit / Instrumentation - performance measurement for media player and 
 * recorder
 */
public class MediaPlayerPerformance extends ActivityInstrumentationTestCase<MediaFrameworkTest> {

    private String TAG = "MediaFrameworkPerformance";

    private SQLiteDatabase mDB;
    private SurfaceHolder mSurfaceHolder = null;
    private static final int NUM_STRESS_LOOP = 10;
    private static final int NUM_PLAYBACk_IN_EACH_LOOP = 20;
    private static final long MEDIA_STRESS_WAIT_TIME = 5000; //5 seconds
    private static final String MEDIA_MEMORY_OUTPUT =
        "/sdcard/mediaMemOutput.txt";

    //the tolerant memory leak
    private static final int MAX_ACCEPTED_MEMORY_LEAK_KB = 150;

    private static int mStartMemory = 0;
    private static int mEndMemory = 0;
    private static int mStartPid = 0;
    private static int mEndPid = 0;


    public MediaPlayerPerformance() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    protected void setUp() throws Exception {
        super.setUp();
    }

    public void createDB() {
        mDB = SQLiteDatabase.openOrCreateDatabase("/sdcard/perf.db", null);
        mDB.execSQL("CREATE TABLE perfdata (_id INTEGER PRIMARY KEY," + 
                "file TEXT," + "setdatatime LONG," + "preparetime LONG," +
                "playtime LONG" + ");");
    }

    public void audioPlaybackStartupTime(String[] testFile) {
        long t1 = 0;
        long t2 = 0;
        long t3 = 0;
        long t4 = 0;
        long setDataSourceDuration = 0;
        long prepareDuration = 0;
        long startDuration = 0;
        long totalSetDataTime = 0;
        long totalPrepareTime = 0;
        long totalStartDuration = 0;

        int numberOfFiles = testFile.length;
        Log.v(TAG, "File length " + numberOfFiles);
        for (int k = 0; k < numberOfFiles; k++) {
            MediaPlayer mp = new MediaPlayer();
            try {
                t1 = SystemClock.uptimeMillis();
                FileInputStream fis = new FileInputStream(testFile[k]);
                FileDescriptor fd = fis.getFD();
                mp.setDataSource(fd);
                fis.close();
                t2 = SystemClock.uptimeMillis();
                mp.prepare();
                t3 = SystemClock.uptimeMillis();
                mp.start();
                t4 = SystemClock.uptimeMillis();
            } catch (Exception e) {
                Log.v(TAG, e.toString());
            }
            setDataSourceDuration = t2 - t1;
            prepareDuration = t3 - t2;
            startDuration = t4 - t3;
            totalSetDataTime = totalSetDataTime + setDataSourceDuration;
            totalPrepareTime = totalPrepareTime + prepareDuration;
            totalStartDuration = totalStartDuration + startDuration;
            mDB.execSQL("INSERT INTO perfdata (file, setdatatime, preparetime," +
                    " playtime) VALUES (" + '"' + testFile[k] + '"' + ',' +
                    setDataSourceDuration + ',' + prepareDuration +
            		',' + startDuration + ");");
            Log.v(TAG, "File name " + testFile[k]);
            mp.stop();
            mp.release();
        }
        Log.v(TAG, "setDataSource average " + totalSetDataTime / numberOfFiles);
        Log.v(TAG, "prepare average " + totalPrepareTime / numberOfFiles);
        Log.v(TAG, "start average " + totalStartDuration / numberOfFiles);

    }

    @Suppress
    public void testStartUpTime() throws Exception {
        createDB();
        audioPlaybackStartupTime(MediaNames.MP3FILES);
        audioPlaybackStartupTime(MediaNames.AACFILES);

    }

    public void wmametadatautility(String[] testFile) {
        long t1 = 0;
        long t2 = 0;
        long sum = 0;
        long duration = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String value;
        for (int i = 0, n = testFile.length; i < n; ++i) {
            try {
                t1 = SystemClock.uptimeMillis();
                retriever.setDataSource(testFile[i]);
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER);
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
                value =
                    retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
                t2 = SystemClock.uptimeMillis();
                duration = t2 - t1;
                Log.v(TAG, "Time taken = " + duration);
                sum = sum + duration;
            } catch (Exception e) {
                Log.v(TAG, e.getMessage());
            }

        }
        Log.v(TAG, "Average duration = " + sum / testFile.length);
    }


    // Note: This test is to assume the mediaserver's pid is 34
    public void mediaStressPlayback(String testFilePath) {
        for (int i = 0; i < NUM_PLAYBACk_IN_EACH_LOOP; i++) {
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setDataSource(testFilePath);
                mp.setDisplay(MediaFrameworkTest.mSurfaceView.getHolder());
                mp.prepare();
                mp.start();
                Thread.sleep(MEDIA_STRESS_WAIT_TIME);
                mp.release();
            } catch (Exception e) {
                mp.release();
                Log.v(TAG, e.toString());
            }
        }
    }

    // Note: This test is to assume the mediaserver's pid is 34
    private void stressVideoRecord(int frameRate, int width, int height, int videoFormat,
            int outFormat, String outFile, boolean videoOnly) {
        // Video recording
        for (int i = 0; i < NUM_PLAYBACk_IN_EACH_LOOP; i++) {
            MediaRecorder mRecorder = new MediaRecorder();
            try {
                if (!videoOnly) {
                    Log.v(TAG, "setAudioSource");
                    mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                }
                mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                mRecorder.setOutputFormat(outFormat);
                Log.v(TAG, "output format " + outFormat);
                mRecorder.setOutputFile(outFile);
                mRecorder.setVideoFrameRate(frameRate);
                mRecorder.setVideoSize(width, height);
                Log.v(TAG, "setEncoder");
                mRecorder.setVideoEncoder(videoFormat);
                if (!videoOnly) {
                    mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                }
                mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
                mRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
                mRecorder.prepare();
                mRecorder.start();
                Thread.sleep(MEDIA_STRESS_WAIT_TIME);
                mRecorder.stop();
                mRecorder.release();
            } catch (Exception e) {
                Log.v("record video failed ", e.toString());
                mRecorder.release();
            }
        }
    }

    public void stressAudioRecord(String filePath) {
        // This test is only for the short media file
        for (int i = 0; i < NUM_PLAYBACk_IN_EACH_LOOP; i++) {
            MediaRecorder mRecorder = new MediaRecorder();
            try {
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                mRecorder.setOutputFile(filePath);
                mRecorder.prepare();
                mRecorder.start();
                Thread.sleep(MEDIA_STRESS_WAIT_TIME);
                mRecorder.stop();
                mRecorder.release();
            } catch (Exception e) {
                Log.v(TAG, e.toString());
                mRecorder.release();
            }
        }
    }

    //Write the ps output to the file
    public void getMemoryWriteToLog(Writer output) {
        String memusage = null;
        memusage = captureMediaserverInfo();
        Log.v(TAG, memusage);
        try {
            //Write to file output
            output.write(memusage);
        } catch (Exception e) {
            e.toString();
        }
    }

    public String captureMediaserverInfo() {
        String cm = "ps mediaserver";
        String memoryUsage = null;

        int ch;
        try {
            Process p = Runtime.getRuntime().exec(cm);
            InputStream in = p.getInputStream();
            StringBuffer sb = new StringBuffer(512);
            while ((ch = in.read()) != -1) {
                sb.append((char) ch);
            }
            memoryUsage = sb.toString();
        } catch (IOException e) {
            Log.v(TAG, e.toString());
        }
        String[] poList = memoryUsage.split("\r|\n|\r\n");
        String memusage = poList[1].concat("\n");
        return memusage;
    }

    public int getMediaserverPid(){
        String memoryUsage = null;
        int pidvalue = 0;
        memoryUsage = captureMediaserverInfo();
        String[] poList2 = memoryUsage.split("\t|\\s+");
        String pid = poList2[1];
        pidvalue = Integer.parseInt(pid);
        Log.v(TAG, "PID = " + pidvalue);
        return pidvalue;
    }

    public int getMediaserverVsize(){
        String memoryUsage = captureMediaserverInfo();
        String[] poList2 = memoryUsage.split("\t|\\s+");
        String vsize = poList2[3];
        int vsizevalue = Integer.parseInt(vsize);
        Log.v(TAG, "VSIZE = " + vsizevalue);
        return vsizevalue;
    }

    public boolean validateMemoryResult (int startPid, int startMemory, Writer output) throws Exception {
        mEndPid = getMediaserverPid();
        mEndMemory = getMediaserverVsize();

        //Write the total memory different into the output file
        output.write("The total diff = " + (mEndMemory - startMemory));
        output.write("\n\n");
        //mediaserver crash
        if (startPid != mEndPid){
            output.write("mediaserver died. Test failed\n");
            return false;
        }
        //memory leak greter than the tolerant
        if ((mEndMemory - startMemory) > MAX_ACCEPTED_MEMORY_LEAK_KB )
            return false;
        return true;
    }

    @Suppress
    public void testWmaParseTime() throws Exception {
        // createDB();
        wmametadatautility(MediaNames.WMASUPPORTED);
    }


    // Test case 1: Capture the memory usage after every 20 h263 playback
    @LargeTest
    public void testH263VideoPlaybackMemoryUsage() throws Exception {
        boolean memoryResult = false;
        mStartPid = getMediaserverPid();
        mStartMemory = getMediaserverVsize();

        File h263MemoryOut = new File(MEDIA_MEMORY_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(h263MemoryOut, true));
        output.write("H263 Video Playback Only\n");
        getMemoryWriteToLog(output);
        for (int i = 0; i < NUM_STRESS_LOOP; i++) {
            mediaStressPlayback(MediaNames.VIDEO_HIGHRES_H263);
            getMemoryWriteToLog(output);
        }
        output.write("\n");
        memoryResult = validateMemoryResult(mStartPid, mStartMemory, output);
        output.close();
        assertTrue("H263 playback memory test", memoryResult);
    }

    // Test case 2: Capture the memory usage after every 20 h264 playback
    @LargeTest
    public void testH264VideoPlaybackMemoryUsage() throws Exception {
        boolean memoryResult = false;
        mStartPid = getMediaserverPid();
        mStartMemory = getMediaserverVsize();

        File h264MemoryOut = new File(MEDIA_MEMORY_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(h264MemoryOut, true));
        output.write("H264 Video Playback only\n");
        getMemoryWriteToLog(output);
        for (int i = 0; i < NUM_STRESS_LOOP; i++) {
            mediaStressPlayback(MediaNames.VIDEO_H264_AMR);
            getMemoryWriteToLog(output);
        }
        output.write("\n");
        memoryResult = validateMemoryResult(mStartPid, mStartMemory, output);
        output.close();
        assertTrue("H264 playback memory test", memoryResult);
    }

    // Test case 3: Capture the memory usage after each 20 WMV playback
    @LargeTest
    public void testWMVVideoPlaybackMemoryUsage() throws Exception {
        boolean memoryResult = false;
        mStartPid = getMediaserverPid();
        mStartMemory = getMediaserverVsize();

        File wmvMemoryOut = new File(MEDIA_MEMORY_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(wmvMemoryOut, true));
        output.write("WMV video playback only\n");
        getMemoryWriteToLog(output);
        for (int i = 0; i < NUM_STRESS_LOOP; i++) {
            mediaStressPlayback(MediaNames.VIDEO_WMV);
            getMemoryWriteToLog(output);
        }
        output.write("\n");
        memoryResult = validateMemoryResult(mStartPid, mStartMemory, output);
        output.close();
        assertTrue("wmv playback memory test", memoryResult);
    }

    // Test case 4: Capture the memory usage after every 20 video only recorded
    @LargeTest
    public void testH263RecordVideoOnlyMemoryUsage() throws Exception {
        boolean memoryResult = false;
        mStartPid = getMediaserverPid();
        mStartMemory = getMediaserverVsize();

        File videoH263RecordOnlyMemoryOut = new File(MEDIA_MEMORY_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(videoH263RecordOnlyMemoryOut, true));
        output.write("H263 video record only\n");
        getMemoryWriteToLog(output);
        for (int i = 0; i < NUM_STRESS_LOOP; i++) {
            stressVideoRecord(20, 352, 288, MediaRecorder.VideoEncoder.H263,
                    MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_VIDEO_3GP, true);
            getMemoryWriteToLog(output);
        }
        output.write("\n");
        memoryResult = validateMemoryResult(mStartPid, mStartMemory, output);
        output.close();
        assertTrue("H263 record only memory test", memoryResult);
    }

    // Test case 5: Capture the memory usage after every 20 video only recorded
    @LargeTest
    public void testMpeg4RecordVideoOnlyMemoryUsage() throws Exception {
        boolean memoryResult = false;
        mStartPid = getMediaserverPid();
        mStartMemory = getMediaserverVsize();

        File videoMp4RecordOnlyMemoryOut = new File(MEDIA_MEMORY_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(videoMp4RecordOnlyMemoryOut, true));
        output.write("MPEG4 video record only\n");
        getMemoryWriteToLog(output);
        for (int i = 0; i < NUM_STRESS_LOOP; i++) {
            stressVideoRecord(20, 352, 288, MediaRecorder.VideoEncoder.MPEG_4_SP,
                    MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_VIDEO_3GP, true);
            getMemoryWriteToLog(output);
        }
        output.write("\n");
        memoryResult = validateMemoryResult(mStartPid, mStartMemory, output);
        output.close();
        assertTrue("mpeg4 record only memory test", memoryResult);
    }

    // Test case 6: Capture the memory usage after every 20 video and audio
    // recorded
    @LargeTest
    public void testRecordVidedAudioMemoryUsage() throws Exception {
        boolean memoryResult = false;
        mStartPid = getMediaserverPid();
        mStartMemory = getMediaserverVsize();

        File videoRecordAudioMemoryOut = new File(MEDIA_MEMORY_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(videoRecordAudioMemoryOut, true));
        output.write("Audio and h263 video record\n");
        getMemoryWriteToLog(output);
        for (int i = 0; i < NUM_STRESS_LOOP; i++) {
            stressVideoRecord(20, 352, 288, MediaRecorder.VideoEncoder.H263,
                    MediaRecorder.OutputFormat.MPEG_4, MediaNames.RECORDED_VIDEO_3GP, false);
            getMemoryWriteToLog(output);
        }
        output.write("\n");
        memoryResult = validateMemoryResult(mStartPid, mStartMemory, output);
        output.close();
        assertTrue("H263 audio video record memory test", memoryResult);
    }

    // Test case 7: Capture the memory usage after every 20 audio only recorded
    @LargeTest
    public void testRecordAudioOnlyMemoryUsage() throws Exception {
        boolean memoryResult = false;
        mStartPid = getMediaserverPid();
        mStartMemory = getMediaserverVsize();

        File audioOnlyMemoryOut = new File(MEDIA_MEMORY_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(audioOnlyMemoryOut, true));
        output.write("Audio record only\n");
        getMemoryWriteToLog(output);
        for (int i = 0; i < NUM_STRESS_LOOP; i++) {
            stressAudioRecord(MediaNames.RECORDER_OUTPUT);
            getMemoryWriteToLog(output);
        }
        output.write("\n");
        memoryResult = validateMemoryResult(mStartPid, mStartMemory, output);
        output.close();
        assertTrue("audio record only memory test", memoryResult);
    }
}
