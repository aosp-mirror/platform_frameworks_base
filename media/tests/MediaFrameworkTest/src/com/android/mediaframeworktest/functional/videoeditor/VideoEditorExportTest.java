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
import android.media.videoeditor.TransitionAlpha;
import android.media.videoeditor.TransitionCrossfade;
import android.media.videoeditor.TransitionFadeBlack;
import android.media.videoeditor.TransitionSliding;
import android.media.videoeditor.VideoEditor;
import android.media.videoeditor.VideoEditor.ExportProgressListener;
import android.media.videoeditor.VideoEditor.MediaProcessingProgressListener;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase;


import android.util.Log;

import com.android.mediaframeworktest.MediaFrameworkTest;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import com.android.mediaframeworktest.VideoEditorHelper;

public class VideoEditorExportTest extends
    ActivityInstrumentationTestCase<MediaFrameworkTest> {
    private final String TAG = "TransitionTest";

    private final String PROJECT_LOCATION = VideoEditorHelper.PROJECT_LOCATION_COMMON;

    private final String INPUT_FILE_PATH = VideoEditorHelper.INPUT_FILE_PATH_COMMON;

    private VideoEditor mVideoEditor;

    private VideoEditorHelper mVideoEditorHelper;

    // Declares the annotation for Preview Test Cases
    public @interface TransitionTests {
    }

    public VideoEditorExportTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
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

    /**
     * To Test export : Merge and Trim different types of Video and Image files
     */
    // TODO :remove TC_EXP_001
    @LargeTest
    public void testExportMergeTrim() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFilename2 = INPUT_FILE_PATH
            + "H264_BP_640x480_12.5fps_256kbps_AACLC_16khz_24kbps_s_0_26.mp4";
        final String videoItemFilename3 = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final String imageItemFilename2 = INPUT_FILE_PATH + "IMG_176x144.jpg";
        final String imageItemFilename3 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/")
            + ".3gp";

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(2000, 7000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaImageItem mediaImageItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFilename1, 3000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem2);

        final MediaVideoItem mediaVideoItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem3.setExtractBoundaries(0, 2000);
        mVideoEditor.addMediaItem(mediaVideoItem3);

        final MediaVideoItem mediaVideoItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                videoItemFilename3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem4.setExtractBoundaries(mediaVideoItem4.getDuration()-5000,
            mediaVideoItem4.getDuration());
        mVideoEditor.addMediaItem(mediaVideoItem4);

        final MediaImageItem mediaImageItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                imageItemFilename2, 4000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem5);

        final MediaImageItem mediaImageItem6 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m6",
                imageItemFilename3, 2000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem6);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        final long storyBoardDuration = mediaVideoItem1.getTimelineDuration()
            + mediaImageItem2.getDuration() + mediaVideoItem3.getTimelineDuration()
            + mediaVideoItem4.getTimelineDuration() + mediaImageItem5.getDuration()
            + mediaImageItem6.getDuration();
        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_720, 0, storyBoardDuration,
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);
        mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }

    /**
     *To Test export : With Effect and Overlays on Different Media Items
     */
    // TODO :remove TC_EXP_002
    @LargeTest
    public void testExportEffectOverlay() throws Exception {
          final String videoItemFilename1 = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFilename2 = INPUT_FILE_PATH
              + "H264_BP_640x480_15fps_1200Kbps_AACLC_48KHz_64kps_m_0_27.3gp";
        final String videoItemFilename3 = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final String imageItemFilename2 = INPUT_FILE_PATH + "IMG_176x144.jpg";
        final String imageItemFilename3 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";

        final String overlayFile = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(2000, 7000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final EffectColor effectPink =
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effectPink",
                0, 2000, EffectColor.TYPE_COLOR, EffectColor.PINK);
        mediaVideoItem1.addEffect(effectPink);

        final EffectColor effectNegative =
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effectNegative",
                3000, 4000, EffectColor.TYPE_NEGATIVE, 0);
        mediaVideoItem1.addEffect(effectNegative);

        final MediaImageItem mediaImageItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFilename1, 3000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem2);

        final EffectColor effectFifties =
            mVideoEditorHelper.createEffectItem(mediaImageItem2, "effectFifties",
                0, 3000, EffectColor.TYPE_FIFTIES, 0);
        mediaImageItem2.addEffect(effectFifties);

        final MediaVideoItem mediaVideoItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem3);
        mediaVideoItem3.setExtractBoundaries(0, 8000);

        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile,
            640, 480);
        final OverlayFrame overlayFrame =
            mVideoEditorHelper.createOverlay(mediaVideoItem3, "overlay",
                mBitmap, 2000, 5000);
        mediaVideoItem3.addOverlay(overlayFrame);

        final EffectColor effectGreen =
            mVideoEditorHelper.createEffectItem(mediaVideoItem3, "effectGreen",
                0, 2000, EffectColor.TYPE_COLOR, EffectColor.GREEN);
        mediaVideoItem3.addEffect(effectGreen);

        final MediaVideoItem mediaVideoItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                videoItemFilename3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem4.setExtractBoundaries(mediaVideoItem4.getDuration()-5000,
            mediaVideoItem4.getDuration());
        mVideoEditor.addMediaItem(mediaVideoItem4);

        final EffectColor effectSepia =
            mVideoEditorHelper.createEffectItem(mediaVideoItem4, "effectSepia",
                0, 2000, EffectColor.TYPE_SEPIA, 0);
        mediaVideoItem4.addEffect(effectSepia);

        final MediaImageItem mediaImageItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                imageItemFilename2, 4000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem5);

        final EffectColor effectGray =
            mVideoEditorHelper.createEffectItem(mediaImageItem5, "effectGray",
                0, 2000, EffectColor.TYPE_COLOR, EffectColor.GRAY);
        mediaImageItem5.addEffect(effectGray);

        final MediaImageItem mediaImageItem6 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m6",
                imageItemFilename3, 2000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem6);

        final EffectColor effectGradient =
            mVideoEditorHelper.createEffectItem(mediaImageItem6,
                "effectGradient", 0, 2000, EffectColor.TYPE_GRADIENT,
                EffectColor.PINK);
        mediaImageItem6.addEffect(effectGradient);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        final long storyBoardDuration = mediaVideoItem1.getTimelineDuration()
            + mediaImageItem2.getDuration()
            + mediaVideoItem3.getTimelineDuration()
            + mediaVideoItem4.getTimelineDuration()
            + mediaImageItem5.getDuration()
            + mediaImageItem6.getDuration();
        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_720, 0, storyBoardDuration,
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);
        mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }

    /**
     * To test export : with Image with KenBurnEffect
     */
    // TODO : remove TC_EXP_003
    @LargeTest
    public void testExportEffectKenBurn() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int imageItemRenderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";

        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
                imageItemFileName, 5000, imageItemRenderingMode);
        mVideoEditor.addMediaItem(mediaImageItem);

        final Rect startRect = new Rect((mediaImageItem.getHeight() / 3),
            (mediaImageItem.getWidth() / 3), (mediaImageItem.getHeight() / 2),
            (mediaImageItem.getWidth() / 2));

        final Rect endRect = new Rect(0, 0, mediaImageItem.getWidth(),
            mediaImageItem.getHeight());

        final EffectKenBurns kbEffectOnMediaItem = new EffectKenBurns(
            mediaImageItem, "KBOnM2", startRect, endRect, 500, 3000);
        assertNotNull("EffectKenBurns", kbEffectOnMediaItem);
        mediaImageItem.addEffect(kbEffectOnMediaItem);

        assertEquals("KenBurn Start Rect", startRect,
            kbEffectOnMediaItem.getStartRect());
        assertEquals("KenBurn End Rect", endRect,
            kbEffectOnMediaItem.getEndRect());

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_720, 0, mediaImageItem.getDuration(),
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);
        mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }

    /**
     * To Test Export : With Video and Image and An Audio BackGround Track
     */
    // TODO : remove TC_EXP_004
    @LargeTest
    public void testExportAudio() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";
        final String audioTrackFilename = INPUT_FILE_PATH +
            "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFileName, 5000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem);

        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "a1", audioTrackFilename);
        audioTrack.setExtractBoundaries(2000, 5000);
        mVideoEditor.addAudioTrack(audioTrack);

        audioTrack.disableDucking();
        audioTrack.enableLoop();
        audioTrack.setVolume(75);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_720, 0, (mediaVideoItem.getTimelineDuration() +
            mediaImageItem.getDuration()),
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);

        mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }

    /**
     *To Test export : With Transition on Different Media Items
     */
    // TODO :remove TC_EXP_005
    @LargeTest
    public void testExportTransition() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFilename2 = INPUT_FILE_PATH
            + "H264_BP_640x480_12.5fps_256kbps_AACLC_16khz_24kbps_s_0_26.mp4";
        final String videoItemFilename3 = INPUT_FILE_PATH +
            "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";

        final String imageItemFilename2 = INPUT_FILE_PATH + "IMG_176x144.jpg";
        final String imageItemFilename3 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";
        final String maskFilename = INPUT_FILE_PATH +
            "TransitionSpiral_QVGA.jpg";

        final MediaVideoItem mediaItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaItem1.setExtractBoundaries(2000, 7000);
        mVideoEditor.addMediaItem(mediaItem1);

        final TransitionAlpha transition1 =
            mVideoEditorHelper.createTAlpha("transition1", null, mediaItem1,
                2000, Transition.BEHAVIOR_LINEAR, maskFilename, 50, true);
        mVideoEditor.addTransition(transition1);

        final MediaImageItem mediaItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFilename1, 8000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaItem2);

        final MediaVideoItem mediaItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaItem3.setExtractBoundaries(0, 8000);
        mVideoEditor.addMediaItem(mediaItem3);

        final TransitionSliding transition2And3 =
            mVideoEditorHelper.createTSliding("transition2", mediaItem2,
                mediaItem3, 4000, Transition.BEHAVIOR_MIDDLE_FAST,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN);
        mVideoEditor.addTransition(transition2And3);

        final MediaVideoItem mediaItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                videoItemFilename3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaItem4);
        mediaItem4.setExtractBoundaries(0, 8000);

        final TransitionCrossfade transition3And4 =
            mVideoEditorHelper.createTCrossFade("transition3", mediaItem3,
                mediaItem4, 3500, Transition.BEHAVIOR_MIDDLE_SLOW);
        mVideoEditor.addTransition(transition3And4);

        final MediaImageItem mediaItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                imageItemFilename2, 7000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaItem5);

        final TransitionFadeBlack transition4And5 =
            mVideoEditorHelper.createTFadeBlack("transition4", mediaItem4,
                mediaItem5, 3500, Transition.BEHAVIOR_SPEED_DOWN);
        mVideoEditor.addTransition(transition4And5);

        final MediaImageItem mediaItem6 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m6",
                imageItemFilename3, 3000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaItem6);

        final TransitionSliding transition5And6 =
            mVideoEditorHelper.createTSliding("transition5", mediaItem5,
                mediaItem6, 1000/*4000*/, Transition.BEHAVIOR_SPEED_UP,
                TransitionSliding.DIRECTION_LEFT_OUT_RIGHT_IN);
        mVideoEditor.addTransition(transition5And6);

        final TransitionSliding transition6 =
            mVideoEditorHelper.createTSliding("transition6", mediaItem6, null,
                1000 /*4000*/, Transition.BEHAVIOR_SPEED_UP,
                TransitionSliding.DIRECTION_TOP_OUT_BOTTOM_IN);
        mVideoEditor.addTransition(transition6);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        final long storyBoardDuration = mediaItem1.getTimelineDuration()
            + mediaItem2.getTimelineDuration()
            + mediaItem3.getTimelineDuration() - transition2And3.getDuration()
            + mediaItem4.getTimelineDuration() - transition3And4.getDuration()
            + mediaItem5.getTimelineDuration() - transition4And5.getDuration()
            + mediaItem6.getTimelineDuration() - transition5And6.getDuration();
        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_720, 0, storyBoardDuration,
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);
        mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }

    /**
     * To Test Export : Without any Media Items in the story Board
     *
     * @throws Exception
     */
    // TODO :remove TC_EXP_006
    @LargeTest
    public void testExportWithoutMediaItems() throws Exception {
        boolean flagForException = false;
        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export("/sdcard/Test.3gp", MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (IllegalStateException e) {
            flagForException = true;
        }
        assertTrue("Export without any MediaItems", flagForException);
    }

    /**
     * To Test Export : With Media Items add and removed in the story Board
     *
     * @throws Exception
     */
    // TODO :remove TC_EXP_007
    @LargeTest
    public void testExportWithoutMediaItemsAddRemove() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_1_17.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String maskFilename = INPUT_FILE_PATH + "TransitionSpiral_QVGA.jpg";
        boolean flagForException = false;

        final MediaVideoItem mediaItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaItem1.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaItem1);

        final MediaImageItem mediaItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFilename1, 15000,
                MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaItem2);

        final TransitionAlpha transition1 =
            mVideoEditorHelper.createTAlpha("transition1", mediaItem1, mediaItem2,
                3000, Transition.BEHAVIOR_LINEAR, maskFilename, 50, false);
        mVideoEditor.addTransition(transition1);

        final EffectColor effectColor =
            mVideoEditorHelper.createEffectItem(mediaItem2, "effect", 12000,
                3000, EffectColor.TYPE_COLOR, EffectColor.PINK);
        mediaItem2.addEffect(effectColor);

        mVideoEditor.removeMediaItem(mediaItem1.getId());
        mVideoEditor.removeMediaItem(mediaItem2.getId());
        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export("/sdcard/Test.3gp", MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (IllegalStateException e) {
            flagForException = true;
        }
        assertTrue("Export with MediaItem added and removed", flagForException);
    }

    /**
     * To Test Export : With Video and Image : MMS use case
     *
     * @throws Exception
     */
    // TODO :remove TC_EXP_008
    @LargeTest
    public void testExportMMS() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_1_17.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFilename2 = INPUT_FILE_PATH
            + "H264_BP_640x480_12.5fps_256kbps_AACLC_16khz_24kbps_s_0_26.mp4";
        final String maskFilename = INPUT_FILE_PATH + "TransitionSpiral_QVGA.jpg";
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";

        final MediaVideoItem mediaItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaItem1.setExtractBoundaries(2000, 7000);
        mVideoEditor.addMediaItem(mediaItem1);

        final TransitionAlpha transition1 =
            mVideoEditorHelper.createTAlpha("transition1", null, mediaItem1,
                2000, Transition.BEHAVIOR_LINEAR, maskFilename, 50, true);
        mVideoEditor.addTransition(transition1);

        final MediaImageItem mediaItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                imageItemFilename1, 8000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaItem2);

        final MediaVideoItem mediaItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaItem3.setExtractBoundaries(0, 8000);
        mVideoEditor.addMediaItem(mediaItem3);

        final TransitionSliding transition2And3 =
            mVideoEditorHelper.createTSliding("transition2", mediaItem2,
                mediaItem3, 4000, Transition.BEHAVIOR_MIDDLE_FAST,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN);
        mVideoEditor.addTransition(transition2And3);

        final TransitionCrossfade transition3 =
            mVideoEditorHelper.createTCrossFade("transition3", mediaItem3, null,
                3500, Transition.BEHAVIOR_MIDDLE_SLOW);
        mVideoEditor.addTransition(transition3);

        final EffectColor effectColor =
            mVideoEditorHelper.createEffectItem(mediaItem2, "effect", 0,
                3000, EffectColor.TYPE_COLOR, EffectColor.PINK);
        mediaItem2.addEffect(effectColor);

        mVideoEditor.setAspectRatio(MediaProperties.ASPECT_RATIO_11_9);

        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_144,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        final long storyBoardDuration = mediaItem1.getTimelineDuration()
            + mediaItem2.getTimelineDuration() + mediaItem3.getTimelineDuration()
            - transition2And3.getDuration();

        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_144, 0, storyBoardDuration,
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);
         mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }

    /**
     * To Test Export :Media Item having duration of 1 Hour
     *
     * @throws Exception
     */
    @Suppress
    @LargeTest
    public void testExportDuration1Hour() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H264_BP_640x480_15fps_384kbps_60_0.mp4";
        final String outFilename = mVideoEditorHelper.createRandomFile(
            mVideoEditor.getPath() + "/") + ".3gp";

        final MediaVideoItem mediaItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaItem1);
        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_144,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        }catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_720, 0, mediaItem1.getDuration(),
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);
        mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }

    /**
     * To Test Export : Storage location having very less space (Less than 100
     * KB)
     *
     * @throws Exception
     */
    @LargeTest
    public void testExportWithStorageFull() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH
            + "H264_BP_640x480_12.5fps_256kbps_AACLC_16khz_24kbps_s_0_26.mp4";
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";
        boolean flagForException = false;

        mVideoEditorHelper.createMediaItem(mVideoEditor, "m1", videoItemFilename1,
            MediaItem.RENDERING_MODE_BLACK_BORDER);
        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_144,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            flagForException = true;
        }
        assertTrue("Error in exporting file due to lack of storage space",
            flagForException);
    }

     /**
     * To Test Export :Two Media Items added
     *
     * @throws Exception
     */
    @LargeTest
    public void testExportTwoVideos() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_1_17.3gp";
        final String videoItemFileName1 = INPUT_FILE_PATH +
            "H264_BP_640x480_12.5fps_256kbps_AACLC_16khz_24kbps_s_0_26.mp4";
        final String outFilename = mVideoEditorHelper
            .createRandomFile(mVideoEditor.getPath() + "/") + ".3gp";

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem);

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                videoItemFileName1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            public void onProgress(Object item, int action, int progress) {
            }
        });

        try {
            final int[] progressUpdate = new int[100];
            mVideoEditor.export(outFilename, MediaProperties.HEIGHT_720,
                MediaProperties.BITRATE_800K, new ExportProgressListener() {
                    int i = 0;
                    public void onProgress(VideoEditor ve, String outFileName,
                        int progress) {
                            progressUpdate[i++] = progress;
                    }
                });
            mVideoEditorHelper.checkProgressCBValues(progressUpdate);
        } catch (Exception e) {
            assertTrue("Error in Export" + e.toString(), false);
        }
        mVideoEditorHelper.validateExport(mVideoEditor, outFilename,
            MediaProperties.HEIGHT_720, 0,
            (mediaVideoItem.getDuration()+ mediaVideoItem1.getDuration()),
            MediaProperties.VCODEC_H264, MediaProperties.ACODEC_AAC_LC);
        mVideoEditorHelper.checkDeleteExistingFile(outFilename);
    }
}
