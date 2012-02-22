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


package com.android.mediaframeworktest.performance;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

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

import android.util.Log;

import com.android.mediaframeworktest.MediaFrameworkTest;
import android.test.suitebuilder.annotation.LargeTest;
import com.android.mediaframeworktest.VideoEditorHelper;

/**
 * Junit / Instrumentation - performance measurement for media player and
 * recorder
 */
public class VideoEditorPerformance extends
    ActivityInstrumentationTestCase<MediaFrameworkTest> {

    private final String TAG = "VideoEditorPerformance";

    private final String PROJECT_LOCATION = VideoEditorHelper.PROJECT_LOCATION_COMMON;

    private final String INPUT_FILE_PATH = VideoEditorHelper.INPUT_FILE_PATH_COMMON;

    private final String VIDEOEDITOR_OUTPUT = PROJECT_LOCATION +
        "VideoEditorPerformance.txt";

    public VideoEditorPerformance() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    private final String PROJECT_CLASS_NAME =
        "android.media.videoeditor.VideoEditorImpl";
    private VideoEditor mVideoEditor;
    private VideoEditorHelper mVideoEditorHelper;

    @Override
    protected void setUp() throws Exception {
        // setup for each test case.
        super.setUp();
        mVideoEditorHelper = new VideoEditorHelper();
        // Create a random String which will be used as project path, where all
        // project related files will be stored.
        final String projectPath =
            mVideoEditorHelper.createRandomFile(PROJECT_LOCATION);
        mVideoEditor = mVideoEditorHelper.createVideoEditor(projectPath);
    }

    @Override
    protected void tearDown() throws Exception {
        mVideoEditorHelper.destroyVideoEditor(mVideoEditor);
        // Clean the directory created as project path
        mVideoEditorHelper.deleteProject(new File(mVideoEditor.getPath()));
        System.gc();
        super.tearDown();
    }

    private void writeTimingInfo(String testCaseName, String[] information)
        throws Exception {
        File outFile = new File(VIDEOEDITOR_OUTPUT);
        Writer output = new BufferedWriter(new FileWriter(outFile, true));
        output.write(testCaseName + "\n\t");
        for (int i = 0; i < information.length; i++) {
            output.write(information[i]);
        }
        output.write("\n\n");
        output.close();
    }

    private final int NUM_OF_ITERATIONS=20;

    private int calculateTimeTaken(long beginTime, int numIterations)
        throws Exception {
        final long duration2 = SystemClock.uptimeMillis();
        final long durationToCreateMediaItem = (duration2 - beginTime);
        final int timeTaken1 = (int)(durationToCreateMediaItem / numIterations);
        return (timeTaken1);
    }

    private void createVideoItems(MediaVideoItem[] mediaVideoItem,
        String videoItemFileName, int renderingMode, int startTime, int endTime) throws Exception {
        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            try {
                mediaVideoItem[i] = new MediaVideoItem(mVideoEditor, "m" + i,
                    videoItemFileName, renderingMode);
                mediaVideoItem[i].setExtractBoundaries(startTime, endTime);
            } catch (Exception e1) {
                assertTrue(
                    "Can not create an object of Video Item with file name = "
                    + videoItemFileName + "------ID:m" + i + "       Issue = "
                    + e1.toString(), false);
            }
        }
    }

    private void addVideoItems(MediaVideoItem[] mediaVideoItem) throws Exception {
        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            try {
                mVideoEditor.addMediaItem(mediaVideoItem[i]);
            } catch (Exception e1) {
                assertTrue(
                    "Can not add an object of Video Item with ID:m" + i +
                    "    Issue = " + e1.toString(), false);
            }
        }
    }

    private void removeVideoItems(MediaVideoItem[] mediaVideoItem) throws Exception {
            for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            try {
            mVideoEditor.removeMediaItem(mediaVideoItem[i].getId());
            } catch (Exception e1) {
                assertTrue(
                    "Can not Remove an object of Video Item with ID:m" + i +
                    "    Issue = " + e1.toString(), false);
            }
        }
    }

    private void createImageItems(MediaImageItem[] mIi,
        String imageItemFileName, int renderingMode, int duration) throws Exception {
        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            try {
                mIi[i] = new MediaImageItem(mVideoEditor, "m" + i,
                    imageItemFileName, duration, renderingMode);
            } catch (Exception e1) {
                assertTrue( " Cannot create Image Item", false);
            }
        }
    }

    private void addImageItems(MediaImageItem[] mIi) throws Exception {
        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            try {
                mVideoEditor.addMediaItem(mIi[i]);
            } catch (Exception e1) {
                assertTrue("Cannot add Image item", false);
            }
        }
    }

    private void removeImageItems(MediaImageItem[] mIi) throws Exception {
            for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            try {
            mVideoEditor.removeMediaItem(mIi[i].getId());
            } catch (Exception e1) {
                assertTrue("Cannot remove image item", false);
            }
        }
    }
    /**
     * To test the performance of adding and removing the video media item
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceAddRemoveVideoItem() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final int videoItemStartTime = 0;
        final int videoItemEndTime = 5000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[3];
        final MediaVideoItem[] mediaVideoItem =
            new MediaVideoItem[NUM_OF_ITERATIONS];
        int timeTaken = 0;
        long startTime = 0;

        /** Time Take for creation of Media Video Item */
        startTime = SystemClock.uptimeMillis();
        createVideoItems(mediaVideoItem, videoItemFileName, renderingMode,
            videoItemStartTime, videoItemEndTime);

        timeTaken = calculateTimeTaken (startTime, NUM_OF_ITERATIONS);
        loggingInfo[0] = "Time taken to Create Media Video Item :" +
            timeTaken;

        /** Time Take for Addition of Media Video Item */
        startTime = SystemClock.uptimeMillis();
        addVideoItems(mediaVideoItem);
        timeTaken = calculateTimeTaken (startTime, NUM_OF_ITERATIONS);
        loggingInfo[1] = "\n\tTime taken to Add  Media Video Item :"
            + timeTaken;

        /** Time Take for Removal of Media Video Item */
        startTime = SystemClock.uptimeMillis();
        removeVideoItems(mediaVideoItem);
        timeTaken = calculateTimeTaken (startTime, NUM_OF_ITERATIONS);
        loggingInfo[2] = "\n\tTime taken to remove  Media Video Item :"
            + timeTaken;

        writeTimingInfo("testPerformanceAddRemoveVideoItem (in mSec)", loggingInfo);
    }

    /**
     * To test the performance of adding and removing the image media item
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceAddRemoveImageItem() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final int imageItemDuration = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[3];
        final MediaImageItem[] mediaImageItem =
            new MediaImageItem[NUM_OF_ITERATIONS];
        int timeTaken = 0;

        long beginTime = SystemClock.uptimeMillis();
        createImageItems(mediaImageItem, imageItemFileName, renderingMode,
            imageItemDuration);
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[0] = "Time taken to Create  Media Image Item :" +
            timeTaken;

        beginTime = SystemClock.uptimeMillis();
        addImageItems(mediaImageItem);
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[1] = "\n\tTime taken to add  Media Image Item :" +
            timeTaken;

        beginTime = SystemClock.uptimeMillis();
        removeImageItems(mediaImageItem);
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[2] = "\n\tTime taken to remove  Media Image Item :"
            + timeTaken;

        writeTimingInfo("testPerformanceAddRemoveImageItem (in mSec)",
            loggingInfo);
    }

    /**
     * To test the performance of adding and removing the transition
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceAddRemoveTransition() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
        "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final int videoItemStartTime1 = 0;
        final int videoItemEndTime1 = 20000;
        final String videoItemFileName2 = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final int videoItemStartTime2 = 0;
        final int videoItemEndTime2 = 20000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int transitionDuration = 5000;
        final int transitionBehavior = Transition.BEHAVIOR_MIDDLE_FAST;
        final String[] loggingInfo = new String[3];
        int timeTaken = 0;

        final MediaVideoItem[] mediaVideoItem =
            new MediaVideoItem[(NUM_OF_ITERATIONS *10) + 1];

        for (int i = 0; i < (NUM_OF_ITERATIONS *10); i+=2) {
            try {
                mediaVideoItem[i] = new MediaVideoItem(mVideoEditor, "m" + i,
                    videoItemFileName1, renderingMode);
                mediaVideoItem[i+1] = new MediaVideoItem(mVideoEditor,
                    "m" + (i+1), videoItemFileName2, renderingMode);
                mediaVideoItem[i].setExtractBoundaries(videoItemStartTime1,
                    videoItemEndTime1);
                mediaVideoItem[i+1].setExtractBoundaries(videoItemStartTime2,
                    videoItemEndTime2);
            } catch (Exception e1) {
                assertTrue("Can not create Video Object Item with file name = "
                    + e1.toString(), false);
            }
            mVideoEditor.addMediaItem(mediaVideoItem[i]);
            mVideoEditor.addMediaItem(mediaVideoItem[i+1]);
        }
        mediaVideoItem[(NUM_OF_ITERATIONS *10)] = new MediaVideoItem(mVideoEditor,
            "m" + (NUM_OF_ITERATIONS *10), videoItemFileName1, renderingMode);
        mediaVideoItem[(NUM_OF_ITERATIONS *10)].setExtractBoundaries(
            videoItemStartTime1, videoItemEndTime1);
        mVideoEditor.addMediaItem(mediaVideoItem[(NUM_OF_ITERATIONS *10)]);
        final TransitionCrossfade tranCrossfade[] =
            new TransitionCrossfade[(NUM_OF_ITERATIONS *10)];

        long beginTime = SystemClock.uptimeMillis();
        for (int i = 0; i < (NUM_OF_ITERATIONS *10); i++) {
            tranCrossfade[i] = new TransitionCrossfade("transition" + i,
                mediaVideoItem[i], mediaVideoItem[i+1], transitionDuration,
                transitionBehavior);
        }
        timeTaken = calculateTimeTaken(beginTime, (NUM_OF_ITERATIONS * 10));
        loggingInfo[0] = "Time taken to Create CrossFade Transition :" +
            timeTaken;

        beginTime = SystemClock.uptimeMillis();
        for (int i = 0; i < (NUM_OF_ITERATIONS *10); i++) {
            mVideoEditor.addTransition(tranCrossfade[i]);
        }
        timeTaken = calculateTimeTaken(beginTime, (NUM_OF_ITERATIONS * 10));
        loggingInfo[1] = "\n\tTime taken to add CrossFade Transition :" +
            timeTaken;

        beginTime = SystemClock.uptimeMillis();
        for (int i = 0; i < (NUM_OF_ITERATIONS *10); i++) {
            assertEquals("Removing Transitions", tranCrossfade[i], mVideoEditor
                .removeTransition(tranCrossfade[i].getId()));
        }
        timeTaken = calculateTimeTaken(beginTime, (NUM_OF_ITERATIONS * 10));
        loggingInfo[2] = "\n\tTime taken to remove CrossFade Transition :" +
            timeTaken;

        writeTimingInfo("testPerformanceAddRemoveTransition (in mSec)", loggingInfo);
    }

    /**
     * To test performance of Export
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceExport() throws Exception {
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int outHeight = MediaProperties.HEIGHT_480;
        final int outBitrate = MediaProperties.BITRATE_256K;
        final int outVcodec = MediaProperties.VCODEC_H264;
        final String[] loggingInfo = new String[1];
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_12Mbps_AACLC_44.1khz_64kbps_s_1_17.mp4";
        final String imageItemFileName1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFileName2 = INPUT_FILE_PATH +
            "H264_BP_640x480_15fps_1200Kbps_AACLC_48KHz_32kbps_m_1_17.3gp";
        final String imageItemFileName2 = INPUT_FILE_PATH + "IMG_176x144.jpg";
        final String videoItemFileName3 = INPUT_FILE_PATH +
            "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_161kbps_s_0_26.mp4";
        final String overlayFile = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        final String audioTrackFilename = INPUT_FILE_PATH +
            "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";
        final String maskFilename = INPUT_FILE_PATH +
            "TransitionSpiral_QVGA.jpg";

        final MediaVideoItem mediaItem1 = new MediaVideoItem(mVideoEditor,
            "m1", videoItemFileName1, renderingMode);
        mediaItem1.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaItem1);

        final MediaImageItem mediaItem2 = new MediaImageItem(mVideoEditor,
            "m2", imageItemFileName1, 10000, renderingMode);
        mVideoEditor.addMediaItem(mediaItem2);

        final MediaVideoItem mediaItem3 = new MediaVideoItem(mVideoEditor,
            "m3", videoItemFileName2, renderingMode);
        mediaItem3.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaItem3);

        final MediaImageItem mediaItem4 = new MediaImageItem(mVideoEditor,
            "m4", imageItemFileName2, 10000, renderingMode);
        mVideoEditor.addMediaItem(mediaItem4);

        final MediaVideoItem mediaItem5 = new MediaVideoItem(mVideoEditor,
            "m5", videoItemFileName3, renderingMode);
        mediaItem5.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaItem5);
        /**
         * 7.Add TransitionAlpha, Apply this  Transition as Begin for Media Item 1
         *  with duration = 2 sec behavior = BEHAVIOR_LINEAR, mask file name =
         * TransitionSpiral_QVGA.jpg , blending percent = 50%, invert = true;
         * */
        final TransitionAlpha transition1 =
            mVideoEditorHelper.createTAlpha("transition1", null, mediaItem1,
                2000, Transition.BEHAVIOR_LINEAR, maskFilename, 50, true);
        mVideoEditor.addTransition(transition1);

        /**
         * 8.Add Transition Sliding between MediaItem 2 and 3 ,
         *  Sliding Direction  = DIRECTION_RIGHT_OUT_LEFT_IN,
         *  behavior  = BEHAVIOR_MIDDLE_FAST and duration = 4sec
         * */
        final TransitionSliding transition2And3 =
            mVideoEditorHelper.createTSliding("transition2", mediaItem2,
                mediaItem3, 4000, Transition.BEHAVIOR_MIDDLE_FAST,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN);
        mVideoEditor.addTransition(transition2And3);

        /**
         * 9.Add Transition Crossfade between  Media Item 3 and 4,
         *  behavior = BEHAVIOR_MIDDLE_SLOW, duration = 3.5 sec
         * */
        final TransitionCrossfade transition3And4 =
            mVideoEditorHelper.createTCrossFade("transition3", mediaItem3,
                mediaItem4, 3500, Transition.BEHAVIOR_MIDDLE_SLOW);
        mVideoEditor.addTransition(transition3And4);

        /**
         * 10.Add Transition Fadeblack between  Media Item 4 and 5,
         *  behavior = BEHAVIOR_SPEED_DOWN, duration = 3.5 sec
         * */
        final TransitionFadeBlack transition4And5 =
            mVideoEditorHelper.createTFadeBlack("transition4", mediaItem4,
                mediaItem5, 3500, Transition.BEHAVIOR_SPEED_DOWN);
        mVideoEditor.addTransition(transition4And5);

        /**
         * 11.Add Effect 1 type="TYPE_SEPIA" to the MediaItem 1,
         *  start time=1sec and duration =4secs
         * */
        final EffectColor effectColor1 = mVideoEditorHelper.createEffectItem(
            mediaItem1, "effect1", 1000, 4000, EffectColor.TYPE_SEPIA, 0);
        mediaItem1.addEffect(effectColor1);

        /**
         * 12.Add Overlay 1  to the MediaItem 3: Frame Overlay with start time = 1 sec
         * duration = 4 sec with item  = IMG_640x480_Overlay1.png
         * */
        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile, 640,
            480);
        final OverlayFrame overlayFrame =
            mVideoEditorHelper.createOverlay(mediaItem3, "overlay",
                mBitmap, 1000, 4000);
        mediaItem3.addOverlay(overlayFrame);
        /**
         * 13.Add Effect 2 type="TYPE_NEGATIVE" to the MediaItem 2,
         *  start time=8sec and duration =2secs
         * */
        final EffectColor effectColor2 = mVideoEditorHelper.createEffectItem(
            mediaItem2, "effect2", 8000, 2000, EffectColor.TYPE_NEGATIVE, 0);
        mediaItem2.addEffect(effectColor2);
        /**
         * 14.Add Effect 3 type="TYPE_COLOR" to the MediaItem 3, color param = "PINK",
         *  start time=5 sec and duration =3secs
         * */
        final EffectColor effectColor3 = mVideoEditorHelper.createEffectItem(
            mediaItem3, "effect3", 5000, 3000, EffectColor.TYPE_COLOR,
            EffectColor.PINK);
        mediaItem3.addEffect(effectColor3);
        /**
         * 15.Add Effect 4 type="TYPE_FIFTIES" to the MediaItem 4,
         *  start time=2 sec and duration =1secs
        * */
        final EffectColor effectColor4 = mVideoEditorHelper.createEffectItem(
            mediaItem4, "effect4", 2000, 1000, EffectColor.TYPE_FIFTIES, 0);
        mediaItem4.addEffect(effectColor4);
        /**
         * 16.Add KenBurnsEffect for MediaItem 4 with
         *  duration = 3 sec and startTime = 4 sec
         *  StartRect
         *  left = org_height/3  ;  top = org_width/3
         *  bottom = org_width/2  ;  right = org_height/2
         *  EndRect
         *  left = 0  ;  top = 0
         *  bottom =  org_height;  right =  org_width
         * */

        final Rect startRect = new Rect((mediaItem4.getHeight() / 3),
            (mediaItem4.getWidth() / 3), (mediaItem4.getHeight() / 2),
            (mediaItem4.getWidth() / 2));
        final Rect endRect = new Rect(0, 0, mediaItem4.getWidth(),
            mediaItem4.getHeight());
        final EffectKenBurns kbEffectOnMediaItem = new EffectKenBurns(
            mediaItem4, "KBOnM2", startRect, endRect,4000 , 3000);
        mediaItem4.addEffect(kbEffectOnMediaItem);

        /** 17.Add Audio Track,Set extract boundaries o to 10 sec.
         * */
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioTrackFilename);
        mVideoEditor.addAudioTrack(audioTrack);
        /** 18.Enable Looping for Audio Track.
         * */
        audioTrack.enableLoop();
        int timeTaken = 0;
        final long beginTime = SystemClock.uptimeMillis();
            try {
                mVideoEditor.export(outFilename, outHeight, outBitrate,
                    new ExportProgressListener() {
                        public void onProgress(VideoEditor ve,
                            String outFileName, int progress) {
                        }
                    });
            } catch (Exception e) {
                assertTrue("Error in Export" + e.toString(), false);
            }
        mVideoEditorHelper.checkDeleteExistingFile(outFilename);

        timeTaken = calculateTimeTaken(beginTime, 1);
        loggingInfo[0] = "Time taken to do ONE export of storyboard duration "
            + mVideoEditor.getDuration() + " is :" + timeTaken;

        writeTimingInfo("testPerformanceExport (in mSec)", loggingInfo);
        mVideoEditorHelper.deleteProject(new File(mVideoEditor.getPath()));
    }


    /**
     * To test the performance of thumbnail extraction
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceThumbnailVideoItem() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final int videoItemStartTime = 0;
        final int videoItemEndTime = 20000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[1];

        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "m1", videoItemFileName, renderingMode);
        mediaVideoItem.setExtractBoundaries(videoItemStartTime,
            videoItemEndTime);

        int timeTaken = 0;
        long beginTime = SystemClock.uptimeMillis();
        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            mediaVideoItem.getThumbnail(mediaVideoItem.getWidth() / 2,
                mediaVideoItem.getHeight() / 2, i);
        }
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[0] = "Duration taken to get Video Thumbnails :" +
            timeTaken;

        writeTimingInfo("testPerformanceThumbnailVideoItem (in mSec)", loggingInfo);
    }

    /**
     * To test the performance of adding and removing the overlay to media item
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceOverlayVideoItem() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final int videoItemStartTime1 = 0;
        final int videoItemEndTime1 = 10000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String overlayFilename = INPUT_FILE_PATH
            + "IMG_640x480_Overlay1.png";
        final int overlayStartTime = 1000;
        final int overlayDuration = 5000;

        final String[] loggingInfo = new String[2];
        MediaVideoItem mediaVideoItem = null;

        try {
            mediaVideoItem = new MediaVideoItem(mVideoEditor, "m0",
                videoItemFileName1, renderingMode);
            mediaVideoItem.setExtractBoundaries(videoItemStartTime1,
                videoItemEndTime1);
        } catch (Exception e1) {
            assertTrue("Can not create Video Item with file name = "
                + e1.toString(), false);
        }
        final OverlayFrame overlayFrame[] = new OverlayFrame[NUM_OF_ITERATIONS];
        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFilename,
            640, 480);
        int timeTaken = 0;
        long beginTime = SystemClock.uptimeMillis();
        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            overlayFrame[i] = new OverlayFrame(mediaVideoItem, "overlay" + i,
            mBitmap, overlayStartTime, overlayDuration);
            mediaVideoItem.addOverlay(overlayFrame[i]);
        }
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[0] = "Time taken to add & create Overlay :" + timeTaken;

        beginTime = SystemClock.uptimeMillis();
        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            assertEquals("Removing Overlays", overlayFrame[i],
                mediaVideoItem.removeOverlay((overlayFrame[i].getId())));
        }
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[1] = "\n\tTime taken to remove  Overlay :" +
            timeTaken;

        writeTimingInfo("testPerformanceOverlayVideoItem (in mSec)", loggingInfo);
    }

    /**
     * To test the performance of get properties of a Video media item
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceVideoItemProperties() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final int videoItemStartTime1 = 0;
        final int videoItemEndTime1 = 10100;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int aspectRatio = MediaProperties.ASPECT_RATIO_3_2;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int duration = 77366;
        final int videoBitrate = 3169971;
        final int fps = 30;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int width = 1080;
        final int height = MediaProperties.HEIGHT_720;
        int timeTaken = 0;
        final String[] loggingInfo = new String[1];
        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "m0", videoItemFileName1, renderingMode);
        mediaVideoItem.setExtractBoundaries(videoItemStartTime1,
            videoItemEndTime1);
        long beginTime = SystemClock.uptimeMillis();
        for (int i = 0; i < (NUM_OF_ITERATIONS*10); i++) {
            try {
                assertEquals("Aspect Ratio Mismatch",
                    aspectRatio, mediaVideoItem.getAspectRatio());
                assertEquals("File Type Mismatch",
                    fileType, mediaVideoItem.getFileType());
                assertEquals("VideoCodec Mismatch",
                    videoCodecType, mediaVideoItem.getVideoType());
                assertEquals("duration Mismatch",
                    duration, mediaVideoItem.getDuration());
                assertEquals("Video Profile ",
                    videoProfile, mediaVideoItem.getVideoProfile());
                assertEquals("Video Level ",
                    videoLevel, mediaVideoItem.getVideoLevel());
                assertEquals("Video height ",
                    height, mediaVideoItem.getHeight());
                assertEquals("Video width ",
                    width, mediaVideoItem.getWidth());
            } catch (Exception e1) {
                assertTrue("Can not create Video Item with file name = "
                    + e1.toString(), false);
            }
        }
        timeTaken = calculateTimeTaken(beginTime, (NUM_OF_ITERATIONS*10));
        loggingInfo[0] = "Time taken to get Media Properties :"
            + timeTaken;
        writeTimingInfo("testPerformanceVideoItemProperties:", loggingInfo);
    }

    /**
     * To test the performance of generatePreview : with Transitions
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceGeneratePreviewWithTransitions()
        throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String imageItemFileName = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int transitionBehavior = Transition.BEHAVIOR_MIDDLE_FAST;
        long averageTime = 0;
        final String[] loggingInfo = new String[1];

        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "mediaItem1", videoItemFileName, renderingMode);
        mediaVideoItem.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final MediaImageItem mediaImageItem = new MediaImageItem(mVideoEditor,
            "mediaItem2", imageItemFileName, 10000, renderingMode);
        mVideoEditor.addMediaItem(mediaImageItem);

        final TransitionCrossfade transitionCrossFade = new TransitionCrossfade(
            "transitionCrossFade", mediaVideoItem, mediaImageItem,
            5000, transitionBehavior);
        mVideoEditor.addTransition(transitionCrossFade);

        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            final long duration1 = SystemClock.uptimeMillis();
            mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
                public void onProgress(Object item, int action, int progress) {
                }
            });
            final long duration2 = SystemClock.uptimeMillis();
            mVideoEditor.removeTransition(transitionCrossFade.getId());
            mVideoEditor.addTransition(transitionCrossFade);
            averageTime += (duration2 - duration1);
        }
        final long durationToAddObjects = averageTime;
        final float timeTaken = (float)durationToAddObjects *
            1.0f/(float)NUM_OF_ITERATIONS;
        loggingInfo[0] = "Time taken to Generate Preview with transition :"
            + timeTaken;
        writeTimingInfo("testPerformanceGeneratePreviewWithTransitions:",
            loggingInfo);
    }

    /**
     * To test the performance of generatePreview : with KenBurn
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceWithKenBurn() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String imageItemFileName = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        long averageTime = 0;
        final String[] loggingInfo = new String[1];
        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "mediaItem1", videoItemFileName, renderingMode);
        mediaVideoItem.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final MediaImageItem mediaImageItem = new MediaImageItem(mVideoEditor,
            "mediaItem2", imageItemFileName, 10000, renderingMode);
        mVideoEditor.addMediaItem(mediaImageItem);

        final Rect startRect = new Rect((mediaImageItem.getHeight() / 3),
            (mediaImageItem.getWidth() / 3), (mediaImageItem.getHeight() / 2),
            (mediaImageItem.getWidth() / 2));
        final Rect endRect = new Rect(0, 0, mediaImageItem.getWidth(),
            mediaImageItem.getHeight());
        final EffectKenBurns kbEffectOnMediaItem =
            new EffectKenBurns(mediaImageItem, "KBOnM2", startRect, endRect,
                500, 3000);
        mediaImageItem.addEffect(kbEffectOnMediaItem);

        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            final long duration1 = SystemClock.uptimeMillis();
            mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
                public void onProgress(Object item, int action, int progress) {
                }
            });
            final long duration2 = SystemClock.uptimeMillis();
            mediaImageItem.removeEffect(kbEffectOnMediaItem.getId());
            mediaImageItem.addEffect(kbEffectOnMediaItem);
            averageTime += duration2 - duration1;
        }

        final long durationToAddObjects = (averageTime);
        final float timeTaken = (float)durationToAddObjects *
            1.0f/(float)NUM_OF_ITERATIONS;
        loggingInfo[0] = "Time taken to Generate KenBurn Effect :"
            + timeTaken;
        writeTimingInfo("testPerformanceWithKenBurn", loggingInfo);
    }

    /**
     * To test the performance of generatePreview : with Transitions and
     * Effect,Overlapping scenario
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceEffectOverlappingTransition() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String videoItemFileName2 = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final int videoStartTime1 = 0;
        final int videoEndTime1 = 10000;
        final int videoStartTime2 = 0;
        final int videoEndTime2 = 10000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int transitionDuration = 5000;
        final int transitionBehavior = Transition.BEHAVIOR_MIDDLE_FAST;
        final int effectItemStartTime = 5000;
        final int effectItemDurationTime = 5000;
        final int effectType = EffectColor.TYPE_COLOR;
        final int effectColorType = EffectColor.GREEN;
        long averageDuration = 0;

        final String[] loggingInfo = new String[1];
        final MediaVideoItem mediaVideoItem1 = new MediaVideoItem(mVideoEditor,
            "mediaItem1", videoItemFileName1, renderingMode);
        mediaVideoItem1.setExtractBoundaries(videoStartTime1, videoEndTime1);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 = new MediaVideoItem(mVideoEditor,
            "mediaItem2", videoItemFileName2, renderingMode);
        mediaVideoItem2.setExtractBoundaries(videoStartTime2, videoEndTime2);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final TransitionCrossfade transitionCrossFade = new TransitionCrossfade(
            "transitionCrossFade", mediaVideoItem1, mediaVideoItem2,
            transitionDuration, transitionBehavior);
        mVideoEditor.addTransition(transitionCrossFade);

        final EffectColor effectColor = new EffectColor(mediaVideoItem1,
            "effect", effectItemStartTime, effectItemDurationTime, effectType,
             effectColorType);
        mediaVideoItem1.addEffect(effectColor);

        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            final long duration1 = SystemClock.uptimeMillis();
            mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
                public void onProgress(Object item, int action, int progress) {
                }
            });
            final long duration2 = SystemClock.uptimeMillis();
            mVideoEditor.removeTransition(transitionCrossFade.getId());
            mVideoEditor.addTransition(transitionCrossFade);
            averageDuration += (duration2 - duration1);
        }
        SystemClock.uptimeMillis();
        final long durationToAddObjects = (averageDuration);
        final float timeTaken = (float)durationToAddObjects *
            1.0f/(float)NUM_OF_ITERATIONS;
        loggingInfo[0] =
            "Time taken to testPerformanceEffectOverlappingTransition :"
            + timeTaken;
        writeTimingInfo("testPerformanceEffectOverlappingTransition:",
            loggingInfo);
    }

    /**
     * To test creation of story board with Transition and Two Effects, Effect
     * overlapping transitions
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceTransitionWithEffectOverlapping() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String videoItemFileName2 = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int transitionDuration = 5000;
        final int transitionBehavior = Transition.BEHAVIOR_MIDDLE_FAST;
        final int effectItemStartTime1 = 5000;
        final int effectItemDurationTime1 = 5000;
        final int effectType1 = EffectColor.TYPE_COLOR;
        final int effectColorType1 = EffectColor.GREEN;
        final int effectItemStartTime2 = 5000;
        final int effectItemDurationTime2 = 5000;
        final int effectType2 = EffectColor.TYPE_COLOR;
        final int effectColorType2 = EffectColor.GREEN;
        int averageTime = 0;
        final String[] loggingInfo = new String[1];

        final MediaVideoItem mediaVideoItem1 = new MediaVideoItem(mVideoEditor,
            "mediaItem1", videoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 = new MediaVideoItem(mVideoEditor,
            "mediaItem2", videoItemFileName2, renderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final TransitionCrossfade transitionCrossFade = new TransitionCrossfade(
            "transitionCrossFade", mediaVideoItem1, mediaVideoItem2,
            transitionDuration, transitionBehavior);
        mVideoEditor.addTransition(transitionCrossFade);

        final EffectColor effectColor1 = new EffectColor(mediaVideoItem1,
            "effect1", effectItemStartTime1, effectItemDurationTime1,
            effectType1, effectColorType1);
        mediaVideoItem1.addEffect(effectColor1);

        final EffectColor effectColor2 = new EffectColor(mediaVideoItem2,
            "effect2", effectItemStartTime2, effectItemDurationTime2,
            effectType2, effectColorType2);
        mediaVideoItem2.addEffect(effectColor2);

        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            final long duration1 = SystemClock.uptimeMillis();
            mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
                public void onProgress(Object item, int action, int progress) {
                }
            });
            final long duration2 = SystemClock.uptimeMillis();
            mVideoEditor.removeTransition(transitionCrossFade.getId());
            mVideoEditor.addTransition(transitionCrossFade);
            averageTime += duration2 - duration1;
        }
        final long durationToAddObjects = (averageTime);
        final float timeTaken = (float)durationToAddObjects *
            1.0f/(float)NUM_OF_ITERATIONS;
        loggingInfo[0] = "Time taken to TransitionWithEffectOverlapping :"
            + timeTaken;
        writeTimingInfo("testPerformanceTransitionWithEffectOverlapping",
            loggingInfo);
    }

    /**
     *To test ThumbnailList for H264
     */
    @LargeTest
    public void testThumbnailH264NonIFrame() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final int outWidth = 1080;
        final int outHeight = 720;
        final int atTime = 2400;
        long durationToAddObjects = 0;
        int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[1];
        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "m1", videoItemFilename, renderingMode);
        assertNotNull("MediaVideoItem", mediaVideoItem);

        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            final long duration1 = SystemClock.uptimeMillis();
            mediaVideoItem.getThumbnail(outWidth, outHeight, atTime + i);
            final long duration2 = SystemClock.uptimeMillis();
            durationToAddObjects += (duration2 - duration1);
        }
        final float timeTaken = (float)durationToAddObjects *
            1.0f/(float)NUM_OF_ITERATIONS;
        loggingInfo[0] = "Time taken for Thumbnail generation :"
            + timeTaken;
        writeTimingInfo("testThumbnailH264NonIFrame", loggingInfo);
    }

    /**
     *To test ThumbnailList for H264
     */
    @LargeTest
    public void testThumbnailH264AnIFrame() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final int outWidth = 1080;
        final int outHeight = 720;
        final int atTime = 3000;
        int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[1];
        long durationToAddObjects = 0;

        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "m1", videoItemFilename, renderingMode);
        assertNotNull("MediaVideoItem", mediaVideoItem);

        for (int i = 0; i < NUM_OF_ITERATIONS; i++) {
            final long duration1 = SystemClock.uptimeMillis();
            mediaVideoItem.getThumbnail(outWidth, outHeight, atTime + i);
            final long duration2 = SystemClock.uptimeMillis();
            durationToAddObjects += (duration2 - duration1);
        }
        final float timeTaken = (float)durationToAddObjects *
            1.0f/(float)NUM_OF_ITERATIONS;
        loggingInfo[0] = "Time taken Thumbnail generation :"
            + timeTaken;
        writeTimingInfo("testThumbnailH264AnIFrame", loggingInfo);
    }

    /**
     * To test the performance : With an audio track
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceWithAudioTrack() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String audioFilename1 = INPUT_FILE_PATH +
            "AACLC_44.1kHz_256kbps_s_1_17.mp4";
        final String audioFilename2 = INPUT_FILE_PATH +
            "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int audioVolume = 50;
        final String[] loggingInfo = new String[2];
        int timeTaken = 0;

        final MediaVideoItem mediaVideoItem = new MediaVideoItem(mVideoEditor,
            "mediaItem1", videoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final AudioTrack audioTrack1 = new AudioTrack(mVideoEditor,
            "Audio Track1", audioFilename1);
        audioTrack1.disableDucking();
        audioTrack1.setVolume(audioVolume);
        mVideoEditor.addAudioTrack(audioTrack1);

        long beginTime = SystemClock.uptimeMillis();
        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });
        timeTaken = calculateTimeTaken(beginTime, 1);
        loggingInfo[0] = "Time taken for 1st Audio Track (AACLC) :"
            + timeTaken;

        final AudioTrack audioTrack2 = new AudioTrack(mVideoEditor,
            "Audio Track2", audioFilename2);
        audioTrack2.enableLoop();

        beginTime = SystemClock.uptimeMillis();
        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });
        timeTaken = calculateTimeTaken(beginTime, 1);
        loggingInfo[1] = "\n\tTime taken for 2nd Audio Track(AMRNB) :"
            + timeTaken;

        writeTimingInfo("testPerformanceWithAudioTrack", loggingInfo);
    }

    /**
     * To test the performance of adding and removing the
     * image media item with 640 x 480
     *
     * @throws Exception
     */
    @LargeTest
    public void testPerformanceAddRemoveImageItem640x480() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int imageItemDuration = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String[] loggingInfo = new String[3];

        int timeTaken = 0;

        final MediaImageItem[] mediaImageItem =
            new MediaImageItem[NUM_OF_ITERATIONS];
        long beginTime = SystemClock.uptimeMillis();
        createImageItems(mediaImageItem, imageItemFileName, renderingMode,
            imageItemDuration);
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[0] = "Time taken to Create  Media Image Item (640x480) :"
            + timeTaken;

        beginTime = SystemClock.uptimeMillis();
        addImageItems(mediaImageItem);
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[1] = "\n\tTime taken to add  Media Image Item (640x480) :"
            + timeTaken;

        beginTime = SystemClock.uptimeMillis();
        removeImageItems(mediaImageItem);
        timeTaken = calculateTimeTaken(beginTime, NUM_OF_ITERATIONS);
        loggingInfo[2] = "\n\tTime taken to remove  Media Image Item (640x480) :"
            + timeTaken;
        writeTimingInfo("testPerformanceAddRemoveImageItem640x480 (in mSec)", loggingInfo);
    }


}
