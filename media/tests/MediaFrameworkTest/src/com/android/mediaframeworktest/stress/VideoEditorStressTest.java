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

package com.android.mediaframeworktest.stress;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.Writer;
import java.util.List;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.videoeditor.AudioTrack;
import android.media.videoeditor.EffectColor;
import android.media.videoeditor.EffectKenBurns;
import android.media.videoeditor.MediaImageItem;
import android.media.videoeditor.MediaItem;
import android.media.videoeditor.MediaProperties;
import android.media.videoeditor.MediaVideoItem;
import android.media.videoeditor.OverlayFrame;
import android.media.videoeditor.Transition;
import android.media.videoeditor.TransitionCrossfade;
import android.media.videoeditor.TransitionAlpha;
import android.media.videoeditor.TransitionFadeBlack;
import android.media.videoeditor.TransitionSliding;
import android.media.videoeditor.VideoEditor;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase;
import android.media.videoeditor.VideoEditor.MediaProcessingProgressListener;
import android.os.Environment;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase;
import android.media.videoeditor.VideoEditor.ExportProgressListener;
import android.media.videoeditor.VideoEditorFactory;
import android.media.videoeditor.ExtractAudioWaveformProgressListener;

import android.os.Debug;
import android.util.Log;
import com.android.mediaframeworktest.MediaFrameworkPerfTestRunner;
import com.android.mediaframeworktest.MediaFrameworkTest;
import android.test.suitebuilder.annotation.LargeTest;
import com.android.mediaframeworktest.VideoEditorHelper;
import com.android.mediaframeworktest.MediaTestUtil;

/**
 * Junit / Instrumentation - performance measurement for media player and
 * recorder
 */
public class VideoEditorStressTest
        extends ActivityInstrumentationTestCase<MediaFrameworkTest> {

    private final String TAG = "VideoEditorStressTest";

    private final String PROJECT_LOCATION = VideoEditorHelper.PROJECT_LOCATION_COMMON;

    private final String INPUT_FILE_PATH = VideoEditorHelper.INPUT_FILE_PATH_COMMON;

    private final String VIDEOEDITOR_OUTPUT = PROJECT_LOCATION +
        "VideoEditorStressMemOutput.txt";

    private long BeginJavaMemory;
    private long AfterJavaMemory;

    private long BeginNativeMemory;
    private long AfterNativeMemory;

    public VideoEditorStressTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
        new File(VIDEOEDITOR_OUTPUT).delete();
    }

    private final String PROJECT_CLASS_NAME =
        "android.media.videoeditor.VideoEditorImpl";
    private VideoEditor mVideoEditor;
    private MediaTestUtil mMediaTestUtil;
    private VideoEditorHelper mVideoEditorHelper;

    @Override
    protected void setUp() throws Exception {
        // setup for each test case.
        super.setUp();
        getActivity();
        mMediaTestUtil = new MediaTestUtil(
            "/sdcard/VideoEditorMediaServerMemoryLog.txt",
             this.getName(), "mediaserver");
        mVideoEditorHelper = new VideoEditorHelper();
        // Create a random String which will be used as project path, where all
        // project related files will be stored.
        final String projectPath =
            mVideoEditorHelper.createRandomFile(PROJECT_LOCATION);
        mVideoEditor = mVideoEditorHelper.createVideoEditor(projectPath);
    }

    @Override
    protected void tearDown() throws Exception {
        final String[] loggingInfo = new String[1];
        mMediaTestUtil.getMemorySummary();
        loggingInfo[0] = "\n" +this.getName();
        writeTimingInfo(loggingInfo);
        loggingInfo[0] = " diff :  " + (AfterNativeMemory - BeginNativeMemory);
        writeTimingInfo(loggingInfo);
        mVideoEditorHelper.destroyVideoEditor(mVideoEditor);
        // Clean the directory created as project path
        mVideoEditorHelper.deleteProject(new File(mVideoEditor.getPath()));
        System.gc();
        super.tearDown();
    }

    private void writeTimingInfo(String[] information)
        throws Exception {
        File outFile = new File(VIDEOEDITOR_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(outFile, true));
        for (int i = 0; i < information.length; i++) {
            output.write(information[i]);
        }
        output.close();
    }

    private void writeTestCaseHeader(String testCaseName)
        throws Exception {
        File outFile = new File(VIDEOEDITOR_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(outFile, true));
        output.write("\n\n" + testCaseName + "\n");
        output.close();
    }

    private void getBeginMemory() throws Exception {
        System.gc();
        Thread.sleep(2500);
        BeginNativeMemory = Debug.getNativeHeapAllocatedSize();
        mMediaTestUtil.getStartMemoryLog();
    }
    private void getAfterMemory_updateLog(String[] loggingInfo, boolean when,
        int iteration)
        throws Exception {
        System.gc();
        Thread.sleep(2500);
        AfterNativeMemory = Debug.getNativeHeapAllocatedSize();
        if(when == false){
            loggingInfo[0] = "\n Before Remove: iteration No.= " + iteration +
                "\t " + (AfterNativeMemory - BeginNativeMemory);
        } else {
            loggingInfo[0] = "\n After Remove: iteration No.= " + iteration +
                "\t " + (AfterNativeMemory - BeginNativeMemory);
        }
        writeTimingInfo(loggingInfo);
        mMediaTestUtil.getMemoryLog();
    }

    /**
     * To stress test MediaItem(Video Item) adding functionality
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_001
    @LargeTest
    public void testStressAddRemoveVideoItem() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_176x144_15fps_144kbps_AMRNB_8kHz_12.2kbps_m_1_17.3gp";
        final String videoItemFileName2 = INPUT_FILE_PATH +
            "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final String videoItemFileName3 = INPUT_FILE_PATH +
            "H263_profile0_176x144_15fps_128kbps_1_35.3gp";
        final String videoItemFileName4 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_1200kbps_AACLC_48khz_64kbps_m_1_17.3gp";
        final String[] loggingInfo = new String[1];
        writeTestCaseHeader("testStressAddRemoveVideoItem");
        int i = 0;
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
                    "m1" + i, videoItemFileName1, renderingMode);
                mediaItem1.setExtractBoundaries(0, 5000);
                mVideoEditor.addMediaItem(mediaItem1);
            }
            if (i % 4 == 1) {
                final MediaVideoItem mediaItem2 = new MediaVideoItem(mVideoEditor,
                    "m2" + i, videoItemFileName2, renderingMode);
                mediaItem2.setExtractBoundaries(0, 10000);
                mVideoEditor.addMediaItem(mediaItem2);
            }
            if (i % 4 == 2) {
                final MediaVideoItem mediaItem3 = new MediaVideoItem(mVideoEditor,
                    "m3" + i, videoItemFileName3, renderingMode);
                mediaItem3.setExtractBoundaries(30000, 45000);
                mVideoEditor.addMediaItem(mediaItem3);
            }
            if (i % 4 == 3) {
                final MediaVideoItem mediaItem4 = new MediaVideoItem(mVideoEditor,
                    "m4" + i, videoItemFileName4, renderingMode);
                mediaItem4.setExtractBoundaries(10000, 30000);
                mVideoEditor.addMediaItem(mediaItem4);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                mVideoEditor.removeMediaItem("m1" + i);
            }
            if (i % 4 == 1) {
                mVideoEditor.removeMediaItem("m2" + i);
            }
            if (i % 4 == 2) {
                mVideoEditor.removeMediaItem("m3" + i);
            }
            if (i % 4 == 3) {
                mVideoEditor.removeMediaItem("m4" + i);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, true, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To stress test MediaItem(Image Item) adding functionality
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_002
    @LargeTest
    public void testStressAddRemoveImageItem() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String ImageItemFileName1 = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final String ImageItemFileName2 = INPUT_FILE_PATH +
            "IMG_640x480.jpg";
        final String ImageItemFileName3 = INPUT_FILE_PATH +
            "IMG_320x240.jpg";
        final String ImageItemFileName4 = INPUT_FILE_PATH +
            "IMG_176x144.jpg";
        final String[] loggingInfo = new String[1];
        int i = 0;
        writeTestCaseHeader("testStressAddRemoveImageItem");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                final MediaImageItem mediaItem1 = new MediaImageItem(mVideoEditor,
                    "m1"+ i, ImageItemFileName1, 5000, renderingMode);
                mVideoEditor.addMediaItem(mediaItem1);
            }
            if (i % 4 == 1) {
                final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
                    "m2"+ i, ImageItemFileName2, 10000, renderingMode);
                mVideoEditor.addMediaItem(mediaItem2);
            }
            if (i % 4 == 2) {
                final MediaImageItem mediaItem3 = new MediaImageItem(mVideoEditor,
                    "m3"+ i, ImageItemFileName3, 15000, renderingMode);
                mVideoEditor.addMediaItem(mediaItem3);
            }
            if (i % 4 == 3) {
                final MediaImageItem mediaItem4 = new MediaImageItem(mVideoEditor,
                    "m4"+ i, ImageItemFileName4, 20000, renderingMode);
                mVideoEditor.addMediaItem(mediaItem4);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                mVideoEditor.removeMediaItem("m1"+i);
            }
            if (i % 4 == 1) {
                mVideoEditor.removeMediaItem("m2"+i);
            }
            if (i % 4 == 2) {
                mVideoEditor.removeMediaItem("m3"+i);
            }
            if (i % 4 == 3) {
                mVideoEditor.removeMediaItem("m4"+i);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, true, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To stress test transition
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_003
    @LargeTest
    public void testStressAddRemoveTransition() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_1_17.mp4";
        final String ImageItemFileName2 = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final String VideoItemFileName3 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final String maskFilename = INPUT_FILE_PATH +
            "TransitionSpiral_QVGA.jpg";
        final String[] loggingInfo = new String[1];
        int i = 0;
        writeTestCaseHeader("testStressAddRemoveTransition");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
                    "m1"+i, VideoItemFileName1, renderingMode);
                mVideoEditor.addMediaItem(mediaItem1);
                mediaItem1.setExtractBoundaries(0, 10000);
                final TransitionCrossfade tranCrossfade =
                    new TransitionCrossfade("transCF" + i, null,
                        mediaItem1, 5000, Transition.BEHAVIOR_MIDDLE_FAST);
                mVideoEditor.addTransition(tranCrossfade);
            }
            if (i % 4 == 1) {
                final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
                    "m1"+i, VideoItemFileName1, renderingMode);
                mVideoEditor.addMediaItem(mediaItem1);
                mediaItem1.setExtractBoundaries(0, 10000);

                final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
                    "m2" +i, ImageItemFileName2, 10000, renderingMode);
                mVideoEditor.addMediaItem(mediaItem2);

                final TransitionAlpha transitionAlpha =
                    mVideoEditorHelper.createTAlpha("transAlpha" + i, mediaItem1,
                        mediaItem2, 5000, Transition.BEHAVIOR_SPEED_UP,
                        maskFilename, 10, false);
                transitionAlpha.setDuration(4000);
                mVideoEditor.addTransition(transitionAlpha);
            }
            if (i % 4 == 2) {
                final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
                    "m2" + i, ImageItemFileName2, 10000, renderingMode);
                mVideoEditor.addMediaItem(mediaItem2);

                final MediaVideoItem mediaItem3 = new MediaVideoItem(mVideoEditor,
                    "m3" + i, VideoItemFileName3, renderingMode);
                mVideoEditor.addMediaItem(mediaItem3);

                mediaItem3.setExtractBoundaries(0, 10000);
                final TransitionAlpha transitionAlpha =
                    mVideoEditorHelper.createTAlpha("transAlpha" + i, mediaItem2,
                        mediaItem3, 5000, Transition.BEHAVIOR_SPEED_UP,
                        maskFilename, 10, false);
                transitionAlpha.setDuration(4000);
                mVideoEditor.addTransition(transitionAlpha);

                mediaItem3.setExtractBoundaries(0, 6000);

                final TransitionSliding transition2And3 =
                    mVideoEditorHelper.createTSliding("transSlide" +i, mediaItem2,
                        mediaItem3, 3000, Transition.BEHAVIOR_MIDDLE_FAST,
                        TransitionSliding.DIRECTION_LEFT_OUT_RIGHT_IN);
                mVideoEditor.addTransition(transition2And3);
            }
            if (i % 4 == 3) {
                final MediaVideoItem mediaItem3 = new MediaVideoItem(mVideoEditor,
                    "m3" + i, VideoItemFileName3, renderingMode);
                mVideoEditor.addMediaItem(mediaItem3);
                mediaItem3.setExtractBoundaries(0, 5000);

                final TransitionFadeBlack transition3 =
                    mVideoEditorHelper.createTFadeBlack("transFB" +i, mediaItem3,
                        null, 2500, Transition.BEHAVIOR_SPEED_UP);
                transition3.setDuration(500);
                mVideoEditor.addTransition(transition3);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                mVideoEditor.removeTransition("transCF" + i);
                mVideoEditor.removeMediaItem("m1" + i);
            }
            if (i % 4 == 1) {
                mVideoEditor.removeTransition("transAlpha" + i);
                mVideoEditor.removeMediaItem("m1" + i);
                mVideoEditor.removeMediaItem("m2" + i);
            }
            if (i % 4 == 2) {
                mVideoEditor.removeTransition("transSlide" +i);
                mVideoEditor.removeMediaItem("m2" + i);
                mVideoEditor.removeMediaItem("m3" + i);
            }
            if (i % 4 == 3) {
                mVideoEditor.removeMediaItem("m3" + i);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, true, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To stress test overlay
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_004
    @LargeTest
    public void testStressAddRemoveOverlay() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final String ImageItemFileName2 = INPUT_FILE_PATH +
            "IMG_640x480.jpg";
        final String OverlayFile3 = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";
        final String OverlayFile4 = INPUT_FILE_PATH +
            "IMG_640x480_Overlay2.png";
        final String[] loggingInfo = new String[1];
        int i = 0;
        final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
            "m1", VideoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaItem1);

        final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
            "m2", ImageItemFileName2, 10000, renderingMode);
        mVideoEditor.addMediaItem(mediaItem2);
        writeTestCaseHeader("testStressAddRemoveOverlay");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 3 == 0) {
                mediaItem1.setExtractBoundaries(0, 10000);
                final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(
                    OverlayFile3, 640, 480);
                final OverlayFrame overlayFrame =
                    mVideoEditorHelper.createOverlay(mediaItem1, "overlay" + i,
                        mBitmap, 1000, 5000);
                mediaItem1.addOverlay(overlayFrame);
                mediaItem1.removeOverlay("overlay"+i);
            }
            if (i % 3 == 1) {
                final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(
                    OverlayFile4, 640, 480);
                final OverlayFrame overlayFrame =
                    mVideoEditorHelper.createOverlay(mediaItem2, "overlay" + i,
                        mBitmap, 1000, 5000);
                mediaItem2.addOverlay(overlayFrame);
                mediaItem2.removeOverlay("overlay"+i);
            }
            if (i % 3 == 2) {
                mediaItem1.setExtractBoundaries(0, 10000);
                final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(
                    OverlayFile4, 640, 480);
                final OverlayFrame overlayFrame =
                    mVideoEditorHelper.createOverlay(mediaItem1, "overlay" + i,
                        mBitmap, 0, mediaItem1.getDuration());
                mediaItem1.addOverlay(overlayFrame);
                mediaItem1.removeOverlay("overlay"+i);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);
    }

    /**
     * To stress test Effects
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_005
    @LargeTest
    public void testStressAddRemoveEffects() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_1200kbps_AACLC_48khz_64kbps_m_1_17.3gp";
        final String ImageItemFileName2 = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final String[] loggingInfo = new String[1];
        final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
            "m1", VideoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaItem1);
        final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
            "m2", ImageItemFileName2, 10000, renderingMode);
        int i = 0;
        mVideoEditor.addMediaItem(mediaItem2);
        writeTestCaseHeader("testStressAddRemoveEffects");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 5 == 0) {
                mediaItem1.setExtractBoundaries(10000, 30000);
                final EffectColor effectColor1 =
                    mVideoEditorHelper.createEffectItem(mediaItem1, "effect1"+i,
                        10000, (mediaItem1.getTimelineDuration()-1000),
                        EffectColor.TYPE_COLOR, EffectColor.GREEN);
                mediaItem1.addEffect(effectColor1);
            }
            if (i % 5 == 1) {
                mediaItem2.setDuration(20000);
                final EffectColor effectColor1 =
                    mVideoEditorHelper.createEffectItem(mediaItem2, "effect1"+i,
                        0, 4000, EffectColor.TYPE_GRADIENT, EffectColor.GRAY);
                mediaItem2.addEffect(effectColor1);
            }
            if (i % 5 == 2) {
                mediaItem1.setExtractBoundaries(10000, 30000);
                final EffectColor effectColor1 =
                    mVideoEditorHelper.createEffectItem(mediaItem1, "effect1"+i,
                        (mediaItem1.getTimelineDuration() - 4000), 4000,
                        EffectColor.TYPE_SEPIA, 0);
                mediaItem1.addEffect(effectColor1);
            }
            if (i % 5 == 3) {
                mediaItem2.setDuration(20000);
                final EffectColor effectColor1 =
                    mVideoEditorHelper.createEffectItem(mediaItem2, "effect1"+i,
                        10000, 4000, EffectColor.TYPE_NEGATIVE, 0);
                mediaItem2.addEffect(effectColor1);
            }
            if (i % 5 == 4) {
                mediaItem2.setDuration(20000);
                final Rect startRect = new Rect((mediaItem2.getHeight() / 3),
                    (mediaItem2.getWidth() / 3), (mediaItem2.getHeight() / 2),
                    (mediaItem2.getWidth() / 2));
                final Rect endRect = new Rect(0, 0, mediaItem2.getWidth(),
                    mediaItem2.getHeight());
                final EffectKenBurns kbEffectOnMediaItem = new EffectKenBurns(
                    mediaItem2, "KBOnM2" + i, startRect, endRect, 500,
                    (mediaItem2.getDuration() - 500));
                mediaItem2.addEffect(kbEffectOnMediaItem);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        for ( i = 0; i < 50; i++) {
            if (i % 5 == 0) {
                mediaItem1.removeEffect("effect1"+i);
            }
            if (i % 5 == 1) {
                mediaItem1.removeEffect("effect1"+i);
            }
            if (i % 5 == 2) {
                mediaItem1.removeEffect("effect1"+i);
            }
            if (i % 5 == 3) {
                mediaItem1.removeEffect("effect1"+i);
            }
            if (i % 5 == 4) {
                mediaItem1.removeEffect("KBOnM2"+i);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, true, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * This method will test thumbnail list extraction in a loop = 200 for Video
     * Item
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_006
    @LargeTest
    public void testStressThumbnailVideoItem() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
                + "H264_BP_640x480_15fps_1200Kbps_AACLC_48KHz_64kps_m_0_27.3gp";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[1];
        int i = 0;
        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "m1", videoItemFileName, renderingMode);
        writeTestCaseHeader("testStressThumbnailVideoItem");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                final Bitmap[] thumbNails =
                    mediaVideoItem.getThumbnailList(mediaVideoItem.getWidth()*3,
                        mediaVideoItem.getHeight()/2, i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 4 == 1) {
                final Bitmap[] thumbNails =
                    mediaVideoItem.getThumbnailList(mediaVideoItem.getWidth()/2,
                        mediaVideoItem.getHeight() * 3, i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 4 == 2) {
                final Bitmap[] thumbNails =
                    mediaVideoItem.getThumbnailList(mediaVideoItem.getWidth()*2,
                        mediaVideoItem.getHeight() / 3, i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 4 == 3) {
                final Bitmap[] thumbNails =
                    mediaVideoItem.getThumbnailList(mediaVideoItem.getWidth(),
                        mediaVideoItem.getHeight(), i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);
    }

    /**
     * To stress test media properties
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_007
    @LargeTest
    public void testStressMediaProperties() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String ImageItemFileName2 = INPUT_FILE_PATH +
            "IMG_640x480.jpg";
        final String AudioItemFileName3 = INPUT_FILE_PATH +
            "AACLC_44.1kHz_256kbps_s_1_17.mp4";
        final String[] loggingInfo = new String[1];
        int i = 0;
        final int videoAspectRatio = MediaProperties.ASPECT_RATIO_3_2;
        final int videoFileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int videoDuration = 77366;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int videoHeight = MediaProperties.HEIGHT_720;
        final int videoWidth = 1080;

        final int imageAspectRatio = MediaProperties.ASPECT_RATIO_4_3;
        final int imageFileType = MediaProperties.FILE_JPEG;
        final int imageWidth = 640;
        final int imageHeight = MediaProperties.HEIGHT_480;

        final int audioDuration = 77554;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 44100;
        final int audioChannel = 2;
        writeTestCaseHeader("testStressMediaProperties");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 3 == 0) {
                final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
                    "m1" + i, VideoItemFileName1, renderingMode);
                mVideoEditor.addMediaItem(mediaItem1);
                mediaItem1.setExtractBoundaries(0, 20000);
                assertEquals("Aspect Ratio Mismatch",
                    videoAspectRatio, mediaItem1.getAspectRatio());
                assertEquals("File Type Mismatch",
                    videoFileType, mediaItem1.getFileType());
                assertEquals("VideoCodec Mismatch",
                    videoCodecType, mediaItem1.getVideoType());
                assertEquals("duration Mismatch",
                    videoDuration, mediaItem1.getDuration());
                assertEquals("Video Profile ",
                    videoProfile, mediaItem1.getVideoProfile());
                assertEquals("Video Level ",
                    videoLevel, mediaItem1.getVideoLevel());
                assertEquals("Video height ",
                    videoHeight, mediaItem1.getHeight());
                assertEquals("Video width ",
                    videoWidth, mediaItem1.getWidth());
                mVideoEditor.removeMediaItem("m1" + i);
            }
            if (i % 3 == 1) {
                final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
                    "m2" + i, ImageItemFileName2, 10000, renderingMode);
                mVideoEditor.addMediaItem(mediaItem2);
                assertEquals("Aspect Ratio Mismatch",
                    imageAspectRatio, mediaItem2.getAspectRatio());
                assertEquals("File Type Mismatch",
                    imageFileType, mediaItem2.getFileType());
                assertEquals("Image height",
                    imageHeight, mediaItem2.getHeight());
                assertEquals("Image width",
                    imageWidth, mediaItem2.getWidth());
                mVideoEditor.removeMediaItem("m2" + i);
            }
            if (i % 3 == 2) {
                final AudioTrack mediaItem3 = new AudioTrack(mVideoEditor,
                    "m3" + i, AudioItemFileName3);
                mVideoEditor.addAudioTrack(mediaItem3);
                assertEquals("AudioType Mismatch", audioCodecType,
                    mediaItem3.getAudioType());
                assertEquals("Audio Sampling", audioSamplingFrequency,
                    mediaItem3.getAudioSamplingFrequency());
                assertEquals("Audio Channels",
                    audioChannel, mediaItem3.getAudioChannels());
                assertEquals("duration Mismatch", audioDuration,
                    mediaItem3.getDuration());
                mVideoEditor.removeAudioTrack("m3" + i);
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);
    }

    /**
     * To stress test insert and move of mediaitems
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_008
    @LargeTest
    public void testStressInsertMovieItems() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String VideoItemFileName2 = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_1_17.mp4";
        final String VideoItemFileName3 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_1200kbps_AACLC_48khz_64kbps_m_1_17.3gp";
        final String[] loggingInfo = new String[1];
        int i = 0;
        writeTestCaseHeader("testStressInsertMoveItems");

        final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
            "m1", VideoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaItem1);
        mediaItem1.setExtractBoundaries(0, 10000);

        final MediaVideoItem mediaItem2 = new MediaVideoItem(mVideoEditor,
            "m2", VideoItemFileName2, renderingMode);
        mVideoEditor.addMediaItem(mediaItem2);
        mediaItem2.setExtractBoundaries(0, 15000);

        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            final MediaVideoItem mediaItem3 = new MediaVideoItem(mVideoEditor,
                "m3" + i, VideoItemFileName3, renderingMode);
            mediaItem3.setExtractBoundaries(0, 15000);
            mVideoEditor.insertMediaItem(mediaItem3, "m1");
            mVideoEditor.moveMediaItem("m2", "m3" + i);
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        for ( i = 0; i < 50; i++) {
            mVideoEditor.removeMediaItem("m3" + i);
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, true, i);
            }
        }
        mVideoEditor.removeMediaItem("m2");
        mVideoEditor.removeMediaItem("m1");
        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To stress test : load and save
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_009
    @LargeTest
    public void testStressLoadAndSave() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String VideoItemFileName2 = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_1_17.mp4";
        final String VideoItemFileName3 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_1200kbps_AACLC_48khz_64kbps_m_1_17.3gp";
        final String ImageItemFileName4 = INPUT_FILE_PATH +
            "IMG_640x480.jpg";
        final String ImageItemFileName5 = INPUT_FILE_PATH +
            "IMG_176x144.jpg";
        final String OverlayFile6 = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";
        final String[] loggingInfo = new String[1];
        int i = 0;
        final String[] projectPath = new String[10];
        writeTestCaseHeader("testStressLoadAndSave");
        getBeginMemory();
        for( i=0; i < 10; i++){

            projectPath[i] =
                mVideoEditorHelper.createRandomFile(PROJECT_LOCATION);
            final VideoEditor mVideoEditor1 =
                mVideoEditorHelper.createVideoEditor(projectPath[i]);

            final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor1,
                "m1", VideoItemFileName1, renderingMode);
            mVideoEditor1.addMediaItem(mediaItem1);
            mediaItem1.setExtractBoundaries(0, 10000);

            final MediaVideoItem mediaItem2 = new MediaVideoItem(mVideoEditor1,
                "m2", VideoItemFileName2, renderingMode);
            mVideoEditor1.addMediaItem(mediaItem2);
            mediaItem2.setExtractBoundaries(mediaItem2.getDuration()/4,
                mediaItem2.getDuration()/2);

            final MediaVideoItem mediaItem3 = new MediaVideoItem(mVideoEditor1,
                "m3", VideoItemFileName3, renderingMode);
            mVideoEditor1.addMediaItem(mediaItem3);
            mediaItem3.setExtractBoundaries(mediaItem3.getDuration()/2,
                mediaItem3.getDuration());

            final MediaImageItem mediaItem4 = new MediaImageItem(mVideoEditor1,
                "m4", ImageItemFileName4, 5000, renderingMode);
            mVideoEditor1.addMediaItem(mediaItem4);

            final MediaImageItem mediaItem5 = new MediaImageItem(mVideoEditor1,
                "m5", ImageItemFileName5, 5000, renderingMode);
            mVideoEditor1.addMediaItem(mediaItem5);

            final EffectColor effectColor1 =
                mVideoEditorHelper.createEffectItem(mediaItem3, "effect1",
                    10000, 2000, EffectColor.TYPE_COLOR, EffectColor.GREEN);
            mediaItem3.addEffect(effectColor1);

            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(OverlayFile6,
                640, 480);
            final OverlayFrame overlayFrame =
                mVideoEditorHelper.createOverlay(mediaItem4, "overlay",
                    mBitmap, 4000, 1000);
            mediaItem4.addOverlay(overlayFrame);

            final TransitionCrossfade tranCrossfade =
                new TransitionCrossfade("transCF", mediaItem1,
                    mediaItem2, 5000, Transition.BEHAVIOR_MIDDLE_FAST);
            mVideoEditor1.addTransition(tranCrossfade);

            final EffectColor effectColor2 =
                mVideoEditorHelper.createEffectItem(mediaItem4, "effect2", 0,
                    mediaItem4.getDuration(), EffectColor.TYPE_COLOR,
                    EffectColor.PINK);
            mediaItem4.addEffect(effectColor2);

            mVideoEditor1.generatePreview(new MediaProcessingProgressListener() {
                public void onProgress(Object item, int action, int progress) {
                }
            });

            mVideoEditor1.save();
            mVideoEditor1.release();

            getAfterMemory_updateLog(loggingInfo, false, i);
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        for( i=0; i<10; i++){
            final VideoEditor mVideoEditor1b =
                VideoEditorFactory.load(projectPath[i], true);
            List<MediaItem> mediaList = mVideoEditor1b.getAllMediaItems();
            assertEquals("Media Item List Size", 5, mediaList.size());

            mediaList.get(3).removeEffect("effect1");
            mediaList.get(3).removeEffect("effect2");
            mediaList.get(2).removeOverlay("overlay");
            mVideoEditor1b.removeTransition("transCF");
            mVideoEditor1b.removeMediaItem("m5");
            mVideoEditor1b.removeMediaItem("m4");
            mVideoEditor1b.removeMediaItem("m3");
            mVideoEditor1b.removeMediaItem("m2");
            mVideoEditor1b.removeMediaItem("m1");
            mVideoEditor1b.release();
            getAfterMemory_updateLog(loggingInfo, true, i);
        }
        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To stress test : Multiple Export
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_010
    @LargeTest
    public void testStressMultipleExport() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String VideoItemFileName2 = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_1_17.mp4";
        final String[] loggingInfo = new String[1];
        final String outFilename = mVideoEditorHelper.createRandomFile(
            mVideoEditor.getPath() + "/") + ".3gp";
        int i = 0;
        writeTestCaseHeader("testStressMultipleExport");
        getBeginMemory();
        final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
            "m1", VideoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaItem1);
        mediaItem1.setExtractBoundaries(0, 10000);

        final MediaVideoItem mediaItem2 = new MediaVideoItem(mVideoEditor,
            "m2", VideoItemFileName2, renderingMode);
        mVideoEditor.addMediaItem(mediaItem2);
        mediaItem2.setExtractBoundaries(0, 15000);

        for ( i = 0; i < 50; i++) {
            if(i%4 ==0){
                final int aspectRatio = MediaProperties.ASPECT_RATIO_11_9;
                mVideoEditor.setAspectRatio(aspectRatio);
                mVideoEditor.export(outFilename, MediaProperties.HEIGHT_288,
                    MediaProperties.BITRATE_256K,MediaProperties.ACODEC_AAC_LC,
                        MediaProperties.VCODEC_H263,
                        new ExportProgressListener() {
                        public void onProgress(VideoEditor ve, String outFileName,
                            int progress) {
                        }
                    });
            }
            if(i%4 ==1){
                final int aspectRatio = MediaProperties.ASPECT_RATIO_5_3;
                mVideoEditor.setAspectRatio(aspectRatio);
                mVideoEditor.export(outFilename, MediaProperties.HEIGHT_144,
                    MediaProperties.BITRATE_384K,MediaProperties.ACODEC_AAC_LC,
                        MediaProperties.VCODEC_MPEG4,
                        new ExportProgressListener() {
                        public void onProgress(VideoEditor ve, String outFileName,
                            int progress) {
                        }
                    });
            }
            if(i%4 ==2){
                final int aspectRatio = MediaProperties.ASPECT_RATIO_11_9;
                mVideoEditor.setAspectRatio(aspectRatio);
                mVideoEditor.export(outFilename, MediaProperties.HEIGHT_144,
                    MediaProperties.BITRATE_512K,MediaProperties.ACODEC_AAC_LC,
                        MediaProperties.VCODEC_H264,
                        new ExportProgressListener() {
                        public void onProgress(VideoEditor ve, String outFileName,
                            int progress) {
                        }
                    });
            }
            if(i%4 ==3){
                final int aspectRatio = MediaProperties.ASPECT_RATIO_3_2;
                mVideoEditor.setAspectRatio(aspectRatio);
                mVideoEditor.export(outFilename, MediaProperties.HEIGHT_480,
                    MediaProperties.BITRATE_800K,MediaProperties.ACODEC_AAC_LC,
                        MediaProperties.VCODEC_H264,
                        new ExportProgressListener() {
                        public void onProgress(VideoEditor ve, String outFileName,
                            int progress) {
                        }
                    });
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        mVideoEditor.removeMediaItem("m2");
        mVideoEditor.removeMediaItem("m1");

        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To stress test Media Item,Overlays,Transitions and Ken Burn
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_011
    @LargeTest
    public void testStressOverlayTransKenBurn() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String VideoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String ImageItemFileName2 = INPUT_FILE_PATH +
            "IMG_640x480.jpg";
        final String OverlayFile3 = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";
        final String audioFilename4 = INPUT_FILE_PATH +
            "AACLC_44.1kHz_256kbps_s_1_17.mp4";
        int i = 0;
        final String[] loggingInfo = new String[1];
        writeTestCaseHeader("testStressOverlayTransKenBurn");
        getBeginMemory();
        for ( i = 0; i < 10; i++) {
            final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
                "m1" + i, VideoItemFileName1, renderingMode);
            mVideoEditor.addMediaItem(mediaItem1);
            mediaItem1.setExtractBoundaries(0, 10000);

            final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
                "m2" + i, ImageItemFileName2, 10000, renderingMode);
            mVideoEditor.addMediaItem(mediaItem2);

            final EffectColor effectColor1 =
                mVideoEditorHelper.createEffectItem(mediaItem1, "effect1"+i,
                    (mediaItem1.getDuration() - 4000), 4000,
                    EffectColor.TYPE_SEPIA, 0);
            mediaItem1.addEffect(effectColor1);

            final TransitionCrossfade tranCrossfade =
                new TransitionCrossfade("transCF" + i, mediaItem1,
                    mediaItem2, 4000, Transition.BEHAVIOR_MIDDLE_FAST);
            mVideoEditor.addTransition(tranCrossfade);

            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(OverlayFile3,
                640, 480);
            final OverlayFrame overlayFrame =
                mVideoEditorHelper.createOverlay(mediaItem1, "overlay" + i,
                    mBitmap, 1000, 5000);
            mediaItem1.addOverlay(overlayFrame);

            final Rect startRect = new Rect((mediaItem2.getHeight() / 3),
                (mediaItem2.getWidth() / 3), (mediaItem2.getHeight() / 2),
                (mediaItem2.getWidth() / 2));
            final Rect endRect = new Rect(0, 0, mediaItem2.getWidth(),
                mediaItem2.getHeight());

            final EffectKenBurns kbEffectOnMediaItem = new EffectKenBurns(
                mediaItem2, "KBOnM2" + i, startRect, endRect, 500,
                (mediaItem2.getDuration()-500));
            mediaItem2.addEffect(kbEffectOnMediaItem);

            if(i == 5) {
                final AudioTrack audioTrack1 = new AudioTrack(mVideoEditor,
                    "Audio Track1", audioFilename4);
                mVideoEditor.addAudioTrack(audioTrack1);
            }
            getAfterMemory_updateLog(loggingInfo, false, i);
        }
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        for ( i = 0; i < 10; i++) {
            MediaImageItem m2 = (MediaImageItem)mVideoEditor.getMediaItem("m2"+i);
            MediaVideoItem m1 = (MediaVideoItem)mVideoEditor.getMediaItem("m1"+i);
            m2.removeEffect("KBOnM2" + i);
            m1.removeOverlay("overlay" + i);
            mVideoEditor.removeTransition("transCF" + i);
            m1.removeEffect("effect1" + i);
            mVideoEditor.removeMediaItem("m2" + i);
            mVideoEditor.removeMediaItem("m1" + i);
            if(i == 5) {
                mVideoEditor.removeAudioTrack("Audio Track1");
            }
            getAfterMemory_updateLog(loggingInfo, true, i);
        }
        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To test the performance : With an audio track with Video
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_012
    @LargeTest
    public void testStressAudioTrackVideo() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String audioFilename1 = INPUT_FILE_PATH +
            "AACLC_44.1kHz_256kbps_s_1_17.mp4";
        final String audioFilename2 = INPUT_FILE_PATH +
            "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int audioVolume = 50;
        final String[] loggingInfo = new String[1];
        int i = 1;
        writeTestCaseHeader("testStressAudioTrackVideo");
        getBeginMemory();
        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "mediaItem1", videoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final AudioTrack audioTrack1 = new AudioTrack(mVideoEditor,
            "Audio Track1", audioFilename1);
        audioTrack1.disableDucking();
        audioTrack1.setVolume(audioVolume);
        mVideoEditor.addAudioTrack(audioTrack1);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        mVideoEditor.removeAudioTrack("Audio Track1");

        final AudioTrack audioTrack2 = new AudioTrack(mVideoEditor,
            "Audio Track2", audioFilename2);
        audioTrack2.enableLoop();

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        mVideoEditor.removeMediaItem("mediaItem1");

        getAfterMemory_updateLog(loggingInfo, true, i);
    }

    /**
     * To Test Stress : Story Board creation with out preview or export
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_013
    @LargeTest
    public void testStressStoryBoard() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_161kbps_s_0_26.mp4";
        final String videoItemFileName2 = INPUT_FILE_PATH +
            "MPEG4_SP_854x480_15fps_256kbps_AACLC_16khz_48kbps_s_0_26.mp4";
        final String videoItemFileName3= INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final String imageItemFileName4 = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final String imageItemFileName5 = INPUT_FILE_PATH +
            "IMG_176x144.jpg";
        final String audioFilename6 = INPUT_FILE_PATH +
            "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";
        final String audioFilename7 = INPUT_FILE_PATH +
            "AACLC_44.1kHz_256kbps_s_1_17.mp4";

        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int audioVolume = 50;
        final String[] loggingInfo = new String[1];
        int i = 1;

        writeTestCaseHeader("testStressStoryBoard");
        getBeginMemory();
        final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
            "m1", videoItemFileName1, renderingMode);
        mediaItem1.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaItem1);

        final MediaVideoItem mediaItem2 = new MediaVideoItem(mVideoEditor,
            "m2", videoItemFileName2, renderingMode);
        mediaItem2.setExtractBoundaries(mediaItem2.getDuration()/4,
            mediaItem2.getDuration()/2);
        mVideoEditor.addMediaItem(mediaItem2);

        final MediaVideoItem mediaItem3 = new MediaVideoItem(mVideoEditor,
            "m3", videoItemFileName3, renderingMode);
        mediaItem3.setExtractBoundaries(mediaItem3.getDuration()/2,
            mediaItem3.getDuration());
        mVideoEditor.addMediaItem(mediaItem3);

        final MediaImageItem mediaItem4 = new MediaImageItem(mVideoEditor,
            "m4", imageItemFileName4, 5000, renderingMode);
        mVideoEditor.addMediaItem(mediaItem4);

        final MediaImageItem mediaItem5 = new MediaImageItem(mVideoEditor,
            "m5", imageItemFileName5, 5000, renderingMode);
        mVideoEditor.addMediaItem(mediaItem5);

        final TransitionCrossfade tranCrossfade =
            new TransitionCrossfade("transCF", mediaItem2, mediaItem3, 2500,
                Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(tranCrossfade);

        final TransitionCrossfade tranCrossfade1 =
            new TransitionCrossfade("transCF1", mediaItem3, mediaItem4, 2500,
                Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(tranCrossfade1);

        final AudioTrack audioTrack1 = new AudioTrack(mVideoEditor,
            "Audio Track1", audioFilename6);
        mVideoEditor.addAudioTrack(audioTrack1);

        mVideoEditor.removeAudioTrack("Audio Track1");
        final AudioTrack audioTrack2 = new AudioTrack(mVideoEditor,
            "Audio Track2", audioFilename7);
        mVideoEditor.addAudioTrack(audioTrack2);
        audioTrack2.enableLoop();
        getAfterMemory_updateLog(loggingInfo, false, i);

        /** Remove items and check for memory leak if any */
        getBeginMemory();
        mVideoEditor.removeAudioTrack("Audio Track2");
        mVideoEditor.removeTransition("transCF");
        mVideoEditor.removeTransition("transCF1");
        mVideoEditor.removeMediaItem("m5");
        mVideoEditor.removeMediaItem("m4");
        mVideoEditor.removeMediaItem("m3");
        mVideoEditor.removeMediaItem("m2");
        mVideoEditor.removeMediaItem("m1");

        getAfterMemory_updateLog(loggingInfo, true, i);
    }

     /**
     * To test the performance : With an audio track Only
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_014
    @LargeTest
    public void testStressAudioTrackOnly() throws Exception {

        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String AudioItemFileName1 = INPUT_FILE_PATH +
            "AACLC_44.1kHz_256kbps_s_1_17.mp4";
        final String[] loggingInfo = new String[1];
        int i = 0;
        writeTestCaseHeader("testStressAudioTrackOnly");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            final AudioTrack mediaItem1 = new AudioTrack(mVideoEditor,
                "m1" + i, AudioItemFileName1);
            mVideoEditor.addAudioTrack(mediaItem1);
            mediaItem1.enableLoop();
            mVideoEditor.removeAudioTrack("m1" + i);
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);
    }

    /**
     * This method will test thumbnail list extraction in a loop = 200 for Image
     * Item
     *
     * @throws Exception
     */
    // TODO : remove TC_STR_016  -- New Test Case
    @LargeTest
    public void testStressThumbnailImageItem() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[1];
        int i = 0;
        final MediaImageItem mediaImageItem = new MediaImageItem(mVideoEditor,
            "m1", imageItemFileName, 5000, renderingMode);
        writeTestCaseHeader("testStressThumbnailImageItem");
        getBeginMemory();
        for ( i = 0; i < 50; i++) {
            if (i % 4 == 0) {
                final Bitmap[] thumbNails = mediaImageItem.getThumbnailList(
                    mediaImageItem.getWidth() / 2 ,
                    mediaImageItem.getHeight() / 2, i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 4 == 1) {
                final Bitmap[] thumbNails = mediaImageItem.getThumbnailList(
                    mediaImageItem.getWidth() / 2,
                    mediaImageItem.getHeight() * 3, i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 4 == 2) {
                final Bitmap[] thumbNails = mediaImageItem.getThumbnailList(
                    mediaImageItem.getWidth() * 2,
                    mediaImageItem.getHeight() / 3, i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 4 == 3) {
                final Bitmap[] thumbNails = mediaImageItem.getThumbnailList(
                    mediaImageItem.getWidth(),
                    mediaImageItem.getHeight(), i, 5000, 2);
                // Recycle this Bitmap array
                for (int i1 = 0; i1 < thumbNails.length; i1++) {
                    thumbNails[i1].recycle();
                }
            }
            if (i % 10 == 0) {
                getAfterMemory_updateLog(loggingInfo, false, i);
            }
        }
        getAfterMemory_updateLog(loggingInfo, false, i);
    }
}
