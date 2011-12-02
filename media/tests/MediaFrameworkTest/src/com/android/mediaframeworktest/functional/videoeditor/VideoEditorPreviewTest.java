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

package com.android.mediaframeworktest.functional.videoeditor;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.videoeditor.AudioTrack;
import android.media.videoeditor.Effect;
import android.media.videoeditor.EffectColor;
import android.media.videoeditor.EffectKenBurns;
import android.media.videoeditor.MediaImageItem;
import android.media.videoeditor.MediaItem;
import android.media.videoeditor.MediaProperties;
import android.media.videoeditor.MediaVideoItem;
import android.media.videoeditor.Overlay;
import android.media.videoeditor.OverlayFrame;
import android.media.videoeditor.Transition;
import android.media.videoeditor.TransitionAlpha;
import android.media.videoeditor.TransitionCrossfade;
import android.media.videoeditor.TransitionFadeBlack;
import android.media.videoeditor.TransitionSliding;
import android.media.videoeditor.VideoEditor;
import android.media.videoeditor.VideoEditor.ExportProgressListener;
import android.media.videoeditor.VideoEditor.MediaProcessingProgressListener;
import android.media.videoeditor.VideoEditor.PreviewProgressListener;
import android.media.videoeditor.VideoEditor.OverlayData;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase;
import android.view.SurfaceHolder;


import com.android.mediaframeworktest.MediaFrameworkTest;
import android.test.suitebuilder.annotation.LargeTest;
import com.android.mediaframeworktest.VideoEditorHelper;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.TimeUnit;

import android.util.Log;

public class VideoEditorPreviewTest extends
    ActivityInstrumentationTestCase<MediaFrameworkTest> {
    private final String TAG = "VideoEditorTest";

    private final String PROJECT_LOCATION = VideoEditorHelper.PROJECT_LOCATION_COMMON;

    private final String INPUT_FILE_PATH = VideoEditorHelper.INPUT_FILE_PATH_COMMON;

    private final String PROJECT_CLASS_NAME =
        "android.media.videoeditor.VideoEditorImpl";

    private VideoEditor mVideoEditor;

    private VideoEditorHelper mVideoEditorHelper;

    private class EventHandler extends Handler {
        public EventHandler( Looper lp)
        {
            super(lp);
        }
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                default:
                MediaFrameworkTest.testInvalidateOverlay();
            }
        }
    }
    private EventHandler mEventHandler;

    private boolean previewStart;
    private boolean previewStop;
    private boolean previewError;

    /* Minimum waiting time for Semaphore to wait for release */
    private final long minWaitingTime = 3000;

    // Declares the annotation for Preview Test Cases
    public @interface Preview {
    }

    public VideoEditorPreviewTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(looper);

        } else {
            //Handle error when looper can not be created.
            ;
        }
    }

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

    protected void setPreviewStart() {
        previewStart = true;
    }
    protected void setPreviewStop() {
        previewStop = true;
    }
    protected void setPreviewError() {
        previewError = true;
    }
    protected void validatePreviewProgress(int startMs, int endMs,
        boolean loop, long duration) throws Exception {

        final int[] progressUpdate = new int[100];
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        previewStart = false;
        previewStop = false;
        previewError = false;
        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            int i = 0;
            public void onProgress(Object item, int action, int progress) {
                progressUpdate[i++] = progress;
            }
        });
        mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();

        long waitingTime = minWaitingTime;
        if (endMs == -1) {
            waitingTime += duration;
        }
        else {
            waitingTime += (endMs - startMs);
        }
        blockTillPreviewCompletes.acquire();
        try {
        mVideoEditor.startPreview(surfaceHolder, startMs, endMs, loop, 1,
            new PreviewProgressListener() {
                public void onProgress(VideoEditor videoEditor, long timeMs,
                    OverlayData overlayData) {

                        if ( overlayData != null) {
                            if(overlayData.needsRendering()) {
                                overlayData.renderOverlay(MediaFrameworkTest.mDestBitmap);
                                mEventHandler.sendMessage(mEventHandler.obtainMessage(1, 2, 3));
                            }
                        }
                }
                public void onStart(VideoEditor videoEditor) {
                    setPreviewStart();
                }
                public void onStop(VideoEditor videoEditor) {
                    setPreviewStop();
                    blockTillPreviewCompletes.release();
                }
                public void onError(VideoEditor videoEditor, int error) {
                    setPreviewError();
                    blockTillPreviewCompletes.release();
                }
        });
        } catch (Exception e) {
            blockTillPreviewCompletes.release();
        }
        blockTillPreviewCompletes.tryAcquire(waitingTime, TimeUnit.MILLISECONDS);

        mVideoEditor.stopPreview();
        assertTrue("Preview Failed to start", previewStart);
        assertTrue("Preview Failed to stop", previewStop);
        assertFalse("Preview Error occurred", previewError);

        blockTillPreviewCompletes.release();
    }

    // -----------------------------------------------------------------
    // Preview
    // -----------------------------------------------------------------

    /**
     *To test Preview : FULL Preview of current work (beginning till end)
     */
    // TODO : remove TC_PRV_001
    @LargeTest
    public void testPreviewTheStoryBoard() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final String videoItemFileName2 = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_256kbps_0_30.mp4";
        final String videoItemFileName3 = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_1_17.3gp";
        previewStart = false;
        previewStop = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
                videoItemFileName1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem2",
                videoItemFileName2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem2);
        mediaVideoItem2.setExtractBoundaries(0, 10000);

        final MediaVideoItem mediaVideoItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem3",
                videoItemFileName3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem3.setExtractBoundaries(0, 10000);

        mVideoEditor.insertMediaItem(mediaVideoItem3, mediaVideoItem1.getId());
        List<MediaItem> mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item 1", mediaVideoItem1, mediaList.get(0));
        assertEquals("Media Item 3", mediaVideoItem3, mediaList.get(1));
        assertEquals("Media Item 2", mediaVideoItem2, mediaList.get(2));

        mediaVideoItem1.setRenderingMode(MediaItem.RENDERING_MODE_BLACK_BORDER);
        assertEquals("Media Item 1 Rendering Mode",
            MediaItem.RENDERING_MODE_BLACK_BORDER,
            mediaVideoItem1.getRenderingMode());

        mediaVideoItem2.setRenderingMode(MediaItem.RENDERING_MODE_BLACK_BORDER);
        assertEquals("Media Item 2 Rendering Mode",
            MediaItem.RENDERING_MODE_BLACK_BORDER,
            mediaVideoItem2.getRenderingMode());

        mediaVideoItem3.setRenderingMode(MediaItem.RENDERING_MODE_STRETCH);
        assertEquals("Media Item 3 Rendering Mode",
            MediaItem.RENDERING_MODE_STRETCH,
            mediaVideoItem3.getRenderingMode());

        mVideoEditor.setAspectRatio(MediaProperties.ASPECT_RATIO_5_3);
        assertEquals("Aspect Ratio", MediaProperties.ASPECT_RATIO_5_3,
            mVideoEditor.getAspectRatio());

        validatePreviewProgress(0, -1, false, mVideoEditor.getDuration());
    }

    /**
     * To test Preview : Preview of start + 10 sec till end of story board
     */
    // TODO : remove TC_PRV_002
    @LargeTest
    public void testPreviewTheStoryBoardFromDuration() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final String videoItemFileName2 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_256kbps_0_30.mp4";
        final String videoItemFileName3 = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_1_17.3gp";
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        previewStart = false;
        previewStop = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
                videoItemFileName1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem2",
                videoItemFileName2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem2.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final MediaVideoItem mediaVideoItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem3",
                videoItemFileName3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem3.setExtractBoundaries(0, 10000);

        mVideoEditor.insertMediaItem(mediaVideoItem3, mediaVideoItem1.getId());

        List<MediaItem> mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item 1", mediaVideoItem1, mediaList.get(0));
        assertEquals("Media Item 3", mediaVideoItem3, mediaList.get(1));
        assertEquals("Media Item 2", mediaVideoItem2, mediaList.get(2));
        mediaVideoItem1.setRenderingMode(MediaItem.RENDERING_MODE_BLACK_BORDER);

        assertEquals("Media Item 1 Rendering Mode",
            MediaItem.RENDERING_MODE_BLACK_BORDER,
            mediaVideoItem1.getRenderingMode());
        mediaVideoItem2.setRenderingMode(MediaItem.RENDERING_MODE_BLACK_BORDER);

        assertEquals("Media Item 2 Rendering Mode",
            MediaItem.RENDERING_MODE_BLACK_BORDER,
            mediaVideoItem2.getRenderingMode());
        mediaVideoItem3.setRenderingMode(MediaItem.RENDERING_MODE_STRETCH);

        assertEquals("Media Item 3 Rendering Mode",
            MediaItem.RENDERING_MODE_STRETCH,
            mediaVideoItem3.getRenderingMode());

        mVideoEditor.setAspectRatio(MediaProperties.ASPECT_RATIO_5_3);
        assertEquals("Aspect Ratio", MediaProperties.ASPECT_RATIO_5_3,
            mVideoEditor.getAspectRatio());

        validatePreviewProgress(10000, -1, false, mVideoEditor.getDuration());
    }

    /**
     * To test Preview : Preview of current Effects applied
     */
    // TODO : remove TC_PRV_003
    @LargeTest
    public void testPreviewOfEffects() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";

        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        previewStart = false;
        previewStop = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
                videoItemFileName1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final EffectColor effectNegative =
            mVideoEditorHelper.createEffectItem(mediaVideoItem1,
                "effectNegative", 0, 2000, EffectColor.TYPE_NEGATIVE, 0);
        mediaVideoItem1.addEffect(effectNegative);

        final EffectColor effectGreen =
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effectGreen",
                2000, 3000, EffectColor.TYPE_COLOR, EffectColor.GREEN);
        mediaVideoItem1.addEffect(effectGreen);

        final EffectColor effectFifties =
            mVideoEditorHelper.createEffectItem(mediaVideoItem1,
                "effectFifties", 5000, 4000, EffectColor.TYPE_FIFTIES, 0);
        mediaVideoItem1.addEffect(effectFifties);

        List<Effect> effectList = mediaVideoItem1.getAllEffects();
        assertEquals("Effect List Size", 3, effectList.size());
        assertEquals("Effect negative", effectNegative, effectList.get(0));
        assertEquals("Effect Green", effectGreen, effectList.get(1));
        assertEquals("Effect Fifties", effectFifties, effectList.get(2));

        mVideoEditor.setAspectRatio(MediaProperties.ASPECT_RATIO_4_3);
        assertEquals("Aspect Ratio", MediaProperties.ASPECT_RATIO_4_3,
            mVideoEditor.getAspectRatio());

        final long storyboardDuration = mVideoEditor.getDuration() ;
        validatePreviewProgress(0, (int)(storyboardDuration/2), false, (storyboardDuration/2));

        assertEquals("Removing Effect : Negative", effectNegative,
            mediaVideoItem1.removeEffect(effectNegative.getId()));

        effectList = mediaVideoItem1.getAllEffects();

        assertEquals("Effect List Size", 2, effectList.size());
        assertEquals("Effect Green", effectGreen, effectList.get(0));
        assertEquals("Effect Fifties", effectFifties, effectList.get(1));

        validatePreviewProgress(0, -1, false, mVideoEditor.getDuration());
    }

    /**
     *To test Preview : Preview of current Transitions applied (with multiple
     * generatePreview)
     */
    // TODO : remove TC_PRV_004
    @LargeTest
    public void testPreviewWithTransition() throws Exception {

        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H263_profile0_176x144_10fps_96kbps_0_25.3gp";
        final String imageItemFileName1 = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final String videoItemFileName2 = INPUT_FILE_PATH +
            "MPEG4_SP_800x480_515kbps_15fps_AMR_NB_8KHz_12.2kbps_m_0_26.mp4";
        final String maskFilename = INPUT_FILE_PATH +
            "TransitionSpiral_QVGA.jpg";
        previewStart = false;
        previewStop = false;
        previewError = false;

        final Semaphore blockTillPreviewCompletes = new Semaphore(1);

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFileName1, 10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFileName2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem2.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final TransitionCrossfade transition1And2CrossFade =
            mVideoEditorHelper.createTCrossFade("transition_1_2_CF",
                mediaVideoItem1, mediaImageItem1, 2000,
                Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(transition1And2CrossFade);

        final TransitionAlpha transition2And3Alpha =
            mVideoEditorHelper.createTAlpha("transition_2_3", mediaImageItem1,
                mediaVideoItem2, 4000, Transition.BEHAVIOR_SPEED_UP,
                maskFilename, 50, true);
        mVideoEditor.addTransition(transition2And3Alpha);

        final TransitionFadeBlack transition1FadeBlack =
            mVideoEditorHelper.createTFadeBlack("transition_1FB", null,
                mediaVideoItem1, 2000, Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(transition1FadeBlack);

        List<Transition> transitionList = mVideoEditor.getAllTransitions();
        assertEquals("Transition List Size", 3, transitionList.size());
        assertEquals("Transition 1", transition1And2CrossFade,
            transitionList.get(0));
        assertEquals("Transition 2", transition2And3Alpha, transitionList.get(1));
        assertEquals("Transition 3", transition1FadeBlack, transitionList.get(2));

        mVideoEditor.setAspectRatio(MediaProperties.ASPECT_RATIO_3_2);

        final int[] progressValues = new int[300];
        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            int i = 0;

            public void onProgress(Object item, int action, int progress) {
                if (item instanceof TransitionCrossfade) {
                    progressValues[i] = progress;
                    assertEquals("Object", item, transition1And2CrossFade);
                    assertEquals("Action", action,
                        MediaProcessingProgressListener.ACTION_ENCODE);
                } else if (item instanceof TransitionAlpha) {
                    progressValues[i] = progress;
                    assertEquals("Object", item, transition2And3Alpha);
                    assertEquals("Action", action,
                        MediaProcessingProgressListener.ACTION_ENCODE);
                } else if (item instanceof TransitionFadeBlack) {
                    progressValues[i] = progress;
                    assertEquals("Object", item, transition1FadeBlack);
                    assertEquals("Action", action,
                        MediaProcessingProgressListener.ACTION_ENCODE);
                }
                i++;
            }
        });

        mVideoEditorHelper.checkProgressCBValues(progressValues);
        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();
        /* As transition takes more time buffer of 10 sec is added */
        long waitingTime = minWaitingTime + 10000 + 10000;

        blockTillPreviewCompletes.acquire();
        try {
        mVideoEditor.startPreview(surfaceHolder, 0, 10000, false, 1,
            new PreviewProgressListener() {
            public void onProgress(VideoEditor videoEditor, long timeMs,
                OverlayData overlayData) {
                }
                public void onStart(VideoEditor videoEditor) {
                    setPreviewStart();
                }
                public void onStop(VideoEditor videoEditor) {
                    setPreviewStop();
                    blockTillPreviewCompletes.release();
                }
                public void onError(VideoEditor videoEditor, int error) {
                    setPreviewError();
                    blockTillPreviewCompletes.release();
                }
        });
        } catch (Exception e) {
            blockTillPreviewCompletes.release();
        }
        blockTillPreviewCompletes.tryAcquire(waitingTime, TimeUnit.MILLISECONDS);
        mVideoEditor.stopPreview();
        blockTillPreviewCompletes.release();
        assertTrue("Preview Failed to start", previewStart);
        assertTrue("Preview Failed to stop", previewStop);
        assertFalse("Preview Error occurred", previewError);

        assertEquals("Removing Transition " + transition1And2CrossFade.getId(),
            transition1And2CrossFade,
            mVideoEditor.removeTransition(transition1And2CrossFade.getId()));
        transitionList = mVideoEditor.getAllTransitions();
        assertEquals("Transition List Size", 2, transitionList.size());
        assertEquals("Transition 1", transition2And3Alpha, transitionList.get(0));
        assertEquals("Transition 2", transition1FadeBlack, transitionList.get(1));

        validatePreviewProgress(0, -1, false, mVideoEditor.getDuration());


        final TransitionSliding transition1And2Sliding =
            mVideoEditorHelper.createTSliding("transition_1_2Sliding",
                mediaVideoItem1, mediaImageItem1, 4000,
                Transition.BEHAVIOR_MIDDLE_FAST,
                TransitionSliding.DIRECTION_LEFT_OUT_RIGHT_IN);
        mVideoEditor.addTransition(transition1And2Sliding);

        transitionList = mVideoEditor.getAllTransitions();
        assertEquals("Transition List Size", 3, transitionList.size());
        assertEquals("Transition 1", transition2And3Alpha, transitionList.get(0));
        assertEquals("Transition 2", transition1FadeBlack, transitionList.get(1));
        assertEquals("Transition 3", transition1And2Sliding,
            transitionList.get(2));

        validatePreviewProgress(5000, -1, false, (mVideoEditor.getDuration()));

    }

    /**
     * To test Preview : Preview of current Overlay applied
     */
    // TODO : remove TC_PRV_005
    @LargeTest
    public void testPreviewWithOverlay() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_1200kbps_AACLC_48khz_64kbps_m_1_17.3gp";
        final String overlayFilename1 = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";
        final String overlayFilename2 = INPUT_FILE_PATH +
            "IMG_640x480_Overlay2.png";
        final int previewFrom = 5000;
        final int previewTo = 10000;
        final boolean previewLoop = false;
        final int previewCallbackFrameCount = 1;
        final int setAspectRatio = MediaProperties.ASPECT_RATIO_4_3;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        previewStart = false;
        previewStop = false;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName, renderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem);
        mediaVideoItem.setExtractBoundaries(0, 10000);

        final Bitmap mBitmap1 =  mVideoEditorHelper.getBitmap(overlayFilename1,
            640, 480);
        final OverlayFrame overlayOnMvi1 =
            mVideoEditorHelper.createOverlay(mediaVideoItem, "OverlayOnMvi1",
                mBitmap1, 0, 5000);
        mediaVideoItem.addOverlay(overlayOnMvi1);

        final Bitmap mBitmap2 =  mVideoEditorHelper.getBitmap(overlayFilename2,
            640, 480);
        final OverlayFrame overlayOnMvi2 =
            mVideoEditorHelper.createOverlay(mediaVideoItem, "OverlayOnMvi2",
                mBitmap2, 5000, 9000);
        mediaVideoItem.addOverlay(overlayOnMvi2);

        List<Overlay> overlayList = mediaVideoItem.getAllOverlays();
        assertEquals("Overlay Size", 2, overlayList.size());
        assertEquals("Overlay 1", overlayOnMvi1, overlayList.get(0));
        assertEquals("Overlay 2", overlayOnMvi2, overlayList.get(1));

        mVideoEditor.setAspectRatio(setAspectRatio);

        validatePreviewProgress(0 /* previewFrom */, -1, previewLoop,
            mVideoEditor.getDuration());
    }

    /**
     * To test Preview : Preview of current Trim applied (with default aspect
     * ratio)
     */
    // TODO : remove TC_PRV_006
    @LargeTest
    public void testPreviewWithTrim() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName, MediaItem.RENDERING_MODE_CROPPING);
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        boolean flagForException = false;
        previewStart = false;
        previewStop = false;
        mediaVideoItem.setExtractBoundaries(mediaVideoItem.getDuration() / 2,
            mediaVideoItem.getDuration());
        mVideoEditor.addMediaItem(mediaVideoItem);

        validatePreviewProgress(1000, -1, false, mVideoEditor.getDuration());
    }

    /**
     * To test Preview : Preview of current work having Overlay and Effect
     * applied
     */

    // TODO : remove TC_PRV_007
    @LargeTest
    public void testPreviewWithOverlayEffectKenBurn() throws Exception {

        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final String overlayFilename = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        previewStart = false;
        previewStop = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaImageItem mediaImageItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFileName, 10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem2);

        final MediaVideoItem mediaVideoItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFileName1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem3);

        final EffectColor effectColor =
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "Effect1",
                1000, 3000, EffectColor.TYPE_COLOR, EffectColor.GREEN);
        mediaVideoItem1.addEffect(effectColor);

        final Rect startRect = new Rect((mediaImageItem2.getHeight() / 3),
            (mediaImageItem2.getWidth() / 3), (mediaImageItem2.getHeight() / 2),
            (mediaImageItem2.getWidth() / 2));
        final Rect endRect = new Rect(0, 0, mediaImageItem2.getWidth(),
            mediaImageItem2.getHeight());

        final EffectKenBurns kbeffectOnMI2 = new EffectKenBurns(mediaImageItem2,
            "KBOnM2", startRect, endRect, 0, 10000);
        assertNotNull("EffectKenBurns", kbeffectOnMI2);
        mediaImageItem2.addEffect(kbeffectOnMI2);

        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFilename,
            640, 480);
        final OverlayFrame overlayFrame =
            mVideoEditorHelper.createOverlay(mediaVideoItem3, "OverlayID",
                mBitmap, (mediaImageItem2.getDuration() / 4),
                (mediaVideoItem3.getDuration() / 3));
        mediaVideoItem3.addOverlay(overlayFrame);

        validatePreviewProgress(5000, -1, false, mVideoEditor.getDuration());
    }

    /**
     *To test Preview : Export during preview
     */
    // TODO : remove TC_PRV_008
    @LargeTest
    public void testPreviewDuringExport() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        previewStart = false;
        previewStop = false;
        previewError = false;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        long waitingTime = minWaitingTime + mVideoEditor.getDuration();


        blockTillPreviewCompletes.acquire();
                    final String fileName = mVideoEditor.getPath() + "\test.3gp";
                    final int height = MediaProperties.HEIGHT_480;
                    final int bitrate = MediaProperties.BITRATE_512K;

            try {
                mVideoEditor.export(fileName, height, bitrate,
                    new ExportProgressListener() {
                        public void onProgress(VideoEditor ve,
                            String outFileName,int progress) {

                        }
                    });
            } catch (IOException e) {
                assertTrue("UnExpected Error in Export" +
                    e.toString(), false);
            }

        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();
        try {

            mVideoEditor.startPreview(surfaceHolder, 5000, -1, false, 1,
                new PreviewProgressListener() {

                    public void onProgress(VideoEditor videoEditor, long timeMs,
                        OverlayData overlayData) {
                    }
                public void onStart(VideoEditor videoEditor) {
                    setPreviewStart();
                }
                public void onStop(VideoEditor videoEditor) {
                    setPreviewStop();
                    blockTillPreviewCompletes.release();
                }
                public void onError(VideoEditor videoEditor, int error) {
                    setPreviewError();
                    blockTillPreviewCompletes.release();
                }
            });

        } catch (Exception e) {
            blockTillPreviewCompletes.release();
        }
        blockTillPreviewCompletes.tryAcquire(waitingTime, TimeUnit.MILLISECONDS);
        mVideoEditor.stopPreview();
        assertTrue("Preview Failed to start", previewStart);
        assertTrue("Preview Failed to stop", previewStop);
        assertFalse("Preview Error occurred", previewError);

        blockTillPreviewCompletes.release();
    }

    /**
     * To test Preview : Preview of current Effects applied (with from time >
     * total duration)
     */
    // TODO : remove TC_PRV_009
    @LargeTest
    public void testPreviewWithDurationGreaterThanMediaDuration()
        throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName, renderingMode);
        try {
            mediaVideoItem1.setExtractBoundaries(0, 20000);
        } catch (Exception e) {
            assertTrue("Exception during setExtract Boundaries", false);
        }
        mVideoEditor.addMediaItem(mediaVideoItem1);
        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();
        long waitingTime = minWaitingTime + (mVideoEditor.getDuration() - 30000);
        if(waitingTime < 0)
        {
            waitingTime = minWaitingTime;
        }

        blockTillPreviewCompletes.acquire();
        try {
            mVideoEditor.startPreview(surfaceHolder, 30000, -1, true, 1,
            new PreviewProgressListener() {
                public void onProgress(VideoEditor videoEditor, long timeMs,
                    OverlayData overlayData) {
            }
                public void onStart(VideoEditor videoEditor) {
                    setPreviewStart();
                }
                public void onStop(VideoEditor videoEditor) {
                    setPreviewStop();
                    blockTillPreviewCompletes.release();
                }
                public void onError(VideoEditor videoEditor, int error) {
                    setPreviewError();
                    blockTillPreviewCompletes.release();
                }
        });

        } catch (IllegalArgumentException e) {
            blockTillPreviewCompletes.release();
            flagForException = true;
        }
        blockTillPreviewCompletes.tryAcquire(waitingTime, TimeUnit.MILLISECONDS);
        assertTrue("Expected Error in Preview", flagForException);
        mVideoEditor.stopPreview();
        blockTillPreviewCompletes.release();
    }

    /**
     * To test Preview : Preview of current Effects applied (with Render Preview
     * Frame)
     */
    // TODO : remove TC_PRV_010
    @LargeTest
    public void testPreviewWithRenderPreviewFrame() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final Semaphore blockTillPreviewCompletes = new Semaphore(1);
        boolean flagForException = false;
        OverlayData overlayData1 = new OverlayData();
        previewStart = false;
        previewStop = false;

        final String overlayFilename1 = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor,
            "m1", videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final EffectColor effectPink =
            mVideoEditorHelper.createEffectItem(mediaVideoItem,
                "effectNegativeOnMvi", 1000, 3000, EffectColor.TYPE_COLOR,
                 EffectColor.PINK);
        mediaVideoItem.addEffect(effectPink);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });
        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();

        assertEquals("Render preview Frame at 5 Sec", 5000,
            mVideoEditor.renderPreviewFrame(surfaceHolder, 5000,
            overlayData1));

        assertEquals("Render preview Frame at 7 Sec", 7000,
            mVideoEditor.renderPreviewFrame(surfaceHolder, 7000,
            overlayData1));

        validatePreviewProgress(5000, -1, false, mVideoEditor.getDuration());
    }

    /**
     * To test Preview : Preview of current work from selected jump location
     * till end with Audio Track
     */
    // TODO : remove TC_PRV_011
    @LargeTest
    public void testPreviewWithEndAudioTrack() throws Exception {
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String imageItemFilename2 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String audioFilename = INPUT_FILE_PATH +
            "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";

        boolean flagForException = false;
        previewStart = false;
        previewStop = false;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                imageItemFilename1, 7000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem1);

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem.setExtractBoundaries(1000, 8000);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final MediaImageItem mediaImageItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                imageItemFilename2, 7000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem2);

        final AudioTrack audioTrack =
            mVideoEditorHelper.createAudio(mVideoEditor, "a1", audioFilename);
        mVideoEditor.addAudioTrack(audioTrack);

        List<AudioTrack> audioList = mVideoEditor.getAllAudioTracks();
        assertEquals("Audio Track List size", 1, audioList.size());
        assertEquals("Audio Track", audioTrack, audioList.get(0));
        mVideoEditor.setAspectRatio(MediaProperties.ASPECT_RATIO_4_3);

        validatePreviewProgress(10000, -1, false, mVideoEditor.getDuration());
    }

    /**
     * To test render Preview Frame
     */
    // TODO : remove TC_PRV_012
    @LargeTest
    public void testRenderPreviewFrame() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH
            + "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final String videoItemFileName2 = INPUT_FILE_PATH
            + "MPEG4_SP_800x480_515kbps_15fps_AMR_NB_8KHz_12.2kbps_m_0_26.mp4";
        final String videoItemFileName3 = INPUT_FILE_PATH
            + "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String imageItemFilename1 = INPUT_FILE_PATH
            + "IMG_1600x1200.jpg";
        final String imageItemFilename2 = INPUT_FILE_PATH
            + "IMG_176x144.jpg";
        final String audioFilename = INPUT_FILE_PATH
            + "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";
        OverlayData overlayData1 = new OverlayData();
        previewStart = false;
        previewStop = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                videoItemFileName2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(mediaVideoItem2.getDuration() / 4,
            mediaVideoItem2.getDuration() / 2);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final MediaVideoItem mediaVideoItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFileName3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(mediaVideoItem2.getDuration() / 2,
            mediaVideoItem2.getDuration());
        mVideoEditor.addMediaItem(mediaVideoItem3);

        final MediaImageItem mediaImageItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                imageItemFilename1, 5000, MediaItem.RENDERING_MODE_BLACK_BORDER);

        final MediaImageItem mediaImageItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                imageItemFilename2, 5000, MediaItem.RENDERING_MODE_BLACK_BORDER);

        List<MediaItem> mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 3, mediaList.size());

        mVideoEditor.insertMediaItem(mediaImageItem4, mediaVideoItem2.getId());
        mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 4, mediaList.size());
        assertEquals("Media item 1", mediaVideoItem1, mediaList.get(0));
        assertEquals("Media item 2", mediaVideoItem2, mediaList.get(1));
        assertEquals("Media item 4", mediaImageItem4, mediaList.get(2));
        assertEquals("Media item 3", mediaVideoItem3, mediaList.get(3));

        mVideoEditor.insertMediaItem(mediaImageItem5, mediaImageItem4.getId());
        mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 5, mediaList.size());
        assertEquals("Media item 1", mediaVideoItem1, mediaList.get(0));
        assertEquals("Media item 2", mediaVideoItem2, mediaList.get(1));
        assertEquals("Media item 4", mediaImageItem4, mediaList.get(2));
        assertEquals("Media item 5", mediaImageItem5, mediaList.get(3));
        assertEquals("Media item 3", mediaVideoItem3, mediaList.get(4));

        mVideoEditor.moveMediaItem(mediaVideoItem1.getId(),
            mediaImageItem5.getId());
        mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 5, mediaList.size());
        assertEquals("Media item 2", mediaVideoItem2, mediaList.get(0));
        assertEquals("Media item 4", mediaImageItem4, mediaList.get(1));
        assertEquals("Media item 5", mediaImageItem5, mediaList.get(2));
        assertEquals("Media item 1", mediaVideoItem1, mediaList.get(3));
        assertEquals("Media item 3", mediaVideoItem3, mediaList.get(4));

        final TransitionCrossfade transition2And4CrossFade =
            mVideoEditorHelper.createTCrossFade("transition2And4CrossFade",
                mediaVideoItem2, mediaImageItem4, 2000,
                Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(transition2And4CrossFade);

        final TransitionCrossfade transition1And3CrossFade =
            mVideoEditorHelper.createTCrossFade("transition1And3CrossFade",
                mediaVideoItem1, mediaVideoItem3, 5000,
                Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(transition1And3CrossFade);

        final AudioTrack audioTrack =
            mVideoEditorHelper.createAudio(mVideoEditor, "a1", audioFilename);
        audioTrack.setExtractBoundaries(0, 2000);
        mVideoEditor.addAudioTrack(audioTrack);

        audioTrack.enableLoop();

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();

        mVideoEditor.renderPreviewFrame(surfaceHolder, mVideoEditor.getDuration()/4, overlayData1);
        Thread.sleep(1000);
        mVideoEditor.renderPreviewFrame(surfaceHolder, mVideoEditor.getDuration()/2, overlayData1);
        Thread.sleep(1000);
        mVideoEditor.renderPreviewFrame(surfaceHolder, mVideoEditor.getDuration(), overlayData1);

    }

    /**
     * To Test Preview : Without any Media Items in the story Board
     */
    // TODO : remove TC_PRV_013
    @LargeTest
    public void testStartPreviewWithoutMediaItems() throws Exception {
        boolean flagForException = false;

        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();
        try{
            mVideoEditor.startPreview(surfaceHolder, 0, -1, false, 1,
                new PreviewProgressListener() {
                    public void onProgress(VideoEditor videoEditor, long timeMs,
                        OverlayData overlayData) {
                    }
                    public void onStart(VideoEditor videoEditor) {
                        setPreviewStart();
                    }
                    public void onStop(VideoEditor videoEditor) {
                        setPreviewStop();
                    }
                    public void onError(VideoEditor videoEditor, int error) {
                        setPreviewError();
                    }
            });
        }catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Preview without Media Items", flagForException);
    }

    /**
     * To Test Preview : Add Media and Remove Media Item (Without any Media
     * Items in the story Board)
     */
    // TODO : remove TC_PRV_014
    @LargeTest
    public void testStartPreviewAddRemoveMediaItems() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String alphaFilename = INPUT_FILE_PATH +
            "TransitionSpiral_QVGA.jpg";
        boolean flagForException = false;

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFilename1, 15000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem);

        final TransitionAlpha transition1And2 =
            mVideoEditorHelper.createTAlpha("transition", mediaVideoItem,
                mediaImageItem, 3000, Transition.BEHAVIOR_SPEED_UP,
                alphaFilename, 10, false);
        mVideoEditor.addTransition(transition1And2);

        final EffectColor effectColor =
            mVideoEditorHelper.createEffectItem(mediaImageItem, "effect", 5000,
                3000, EffectColor.TYPE_COLOR, EffectColor.PINK);
        mediaImageItem.addEffect(effectColor);

        assertEquals("removing Media item 1", mediaVideoItem,
            mVideoEditor.removeMediaItem(mediaVideoItem.getId()));
        assertEquals("removing Media item 2", mediaImageItem,
            mVideoEditor.removeMediaItem(mediaImageItem.getId()));

        try{
            mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
                public void onProgress(Object item, int action, int progress) {
                }
            });
            final SurfaceHolder surfaceHolder =
                MediaFrameworkTest.mSurfaceView.getHolder();
            mVideoEditor.startPreview(surfaceHolder, 0, -1, false, 1,
                new PreviewProgressListener() {
                    public void onProgress(VideoEditor videoEditor, long timeMs,
                        OverlayData overlayData) {
                    }
                    public void onStart(VideoEditor videoEditor) {
                        setPreviewStart();
                    }
                    public void onStop(VideoEditor videoEditor) {
                        setPreviewStop();
                    }
                    public void onError(VideoEditor videoEditor, int error) {
                        setPreviewError();
                    }
            });
        }catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Preview with removed Media Items", flagForException);

    }

    /**
     * To test Preview : Preview of current Effects applied (with Render Preview
     * Frame)
     */
    // TODO : remove TC_PRV_015
    @LargeTest
    public void testPreviewWithRenderPreviewFrameWithoutGenerate() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        boolean flagForException = false;
        long duration = 0;
        OverlayData overlayData1 = new OverlayData();

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor,
            "m1", videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final SurfaceHolder surfaceHolder =
            MediaFrameworkTest.mSurfaceView.getHolder();
        duration = mVideoEditor.getDuration();
        /* RenderPreviewFrame returns -1 to indicate last frame */
        try {
            mVideoEditor.renderPreviewFrame(surfaceHolder, duration,
            overlayData1);
        } catch ( IllegalStateException e) {
            flagForException = true;
        }
        assertTrue (" Render Preview Frame without generate", flagForException);
        duration = mVideoEditor.getDuration() + 1000;
        try {
            mVideoEditor.renderPreviewFrame(surfaceHolder, duration,
            overlayData1);
        } catch ( IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue (" Preview time greater than duration", flagForException);
    }
}
