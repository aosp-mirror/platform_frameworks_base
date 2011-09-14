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

package com.android.mediaframeworktest.functional;

import java.io.File;
import java.util.List;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.videoeditor.AudioTrack;
import android.media.videoeditor.EffectColor;
import android.media.videoeditor.EffectKenBurns;
import android.media.videoeditor.ExtractAudioWaveformProgressListener;
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
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase;
import android.media.videoeditor.VideoEditor.MediaProcessingProgressListener;

import android.util.Log;
import java.lang.annotation.Annotation;

import com.android.mediaframeworktest.MediaFrameworkTest;
import android.test.suitebuilder.annotation.LargeTest;
import com.android.mediaframeworktest.VideoEditorHelper;

public class VideoEditorAPITest extends
        ActivityInstrumentationTestCase<MediaFrameworkTest> {
    private final String TAG = "VideoEditorTest";

    private final String PROJECT_LOCATION = VideoEditorHelper.PROJECT_LOCATION_COMMON;

    private final String INPUT_FILE_PATH = VideoEditorHelper.INPUT_FILE_PATH_COMMON;

    private final String PROJECT_CLASS_NAME =
        "android.media.videoeditor.VideoEditorImpl";
    private VideoEditor mVideoEditor;
    private VideoEditorHelper mVideoEditorHelper;

    public VideoEditorAPITest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
        // setup for each test case.
        super.setUp();
        mVideoEditorHelper = new VideoEditorHelper();
        // Create a random String which will be used as project path, where all
        // project related files will be stored.
        final String projectPath = mVideoEditorHelper.
            createRandomFile(PROJECT_LOCATION);
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
     * To Test Creation of Media Video Item.
     */
    // TODO : remove TC_API_001
    @LargeTest
    public void testMediaVideoItem() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int videoItemRenderingMode =
            MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);

        assertTrue("Media Video ID",
            mediaVideoItem1.getId().equals("mediaVideoItem1"));
        assertTrue("Media Video Filename",
            mediaVideoItem1.getFilename().equals(videoItemFileName));
        assertEquals("Media Video Rendering Mode",
            videoItemRenderingMode, mediaVideoItem1.getRenderingMode());
        assertEquals("Media Video Item Duration", mediaVideoItem1.getDuration(),
            mediaVideoItem1.getTimelineDuration());
        assertEquals("Media Video Overlay", 0,
            mediaVideoItem1.getAllOverlays().size());
        assertEquals("Media Video Effect", 0,
            mediaVideoItem1.getAllEffects().size());
        assertNull("Media Video Begin transition",
            mediaVideoItem1.getBeginTransition());
        assertNull("Media Video End transition",
            mediaVideoItem1.getEndTransition());
        mediaVideoItem1.setExtractBoundaries(1000,11000);
        boolean flagForException = false;
        if (mediaVideoItem1.getDuration() !=
            mediaVideoItem1.getTimelineDuration()) {
            flagForException = true;
        }
        assertTrue("Media Video Item Duration & Timeline are same",
            flagForException );
    }

    /**
     * To test creation of Media Video Item with Set Extract Boundaries With Get
     * the Begin and End Time.
     */
    // TODO : remove TC_API_002
    @LargeTest
    public void testMediaVideoItemExtractBoundaries() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int videoItemRenderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        mediaVideoItem1.setExtractBoundaries(1000, 11000);
        assertEquals("Media Item Duration = StoryBoard Duration",
            mediaVideoItem1.getTimelineDuration(), mVideoEditor.getDuration());
        try {
            mediaVideoItem1.setExtractBoundaries(0, 100000000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Set Extract with Invalid Values endTime > FileDuration",
            flagForException);

        flagForException = false;
        try {
            mediaVideoItem1.setExtractBoundaries(100000000, 11000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Set Extract with Invalid Values startTime > endTime",
            flagForException);

        flagForException = false;
        try {
            mediaVideoItem1.setExtractBoundaries(0, 0);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Set Extract with Invalid Values startTime = endTime",
            flagForException);

        mediaVideoItem1.setExtractBoundaries(1000, 10000);
        assertTrue("Media Item Duration is still the same",
            (mediaVideoItem1.getTimelineDuration() ==
            (mediaVideoItem1.getBoundaryEndTime()-
            mediaVideoItem1.getBoundaryBeginTime())) ? true : false);

        mediaVideoItem1.setExtractBoundaries(1,mediaVideoItem1.getDuration()-1);
        assertEquals("Media Item Start Time", 1,
            mediaVideoItem1.getBoundaryBeginTime());
        assertEquals("Media Item End Time", (mediaVideoItem1.getDuration() - 1),
            mediaVideoItem1.getBoundaryEndTime());

        mediaVideoItem1.setExtractBoundaries(1, mediaVideoItem1.getDuration());
        assertEquals("Media Item Duration = StoryBoard Duration",
            mediaVideoItem1.getTimelineDuration(), mVideoEditor.getDuration());

        mediaVideoItem1.setExtractBoundaries(0,mediaVideoItem1.getDuration()/2);
        assertEquals("Media Item Duration = StoryBoard Duration",
            mediaVideoItem1.getTimelineDuration(), mVideoEditor.getDuration());

        mediaVideoItem1.setExtractBoundaries(0, -1);
        assertEquals("Media Item Duration = StoryBoard Duration",
            mediaVideoItem1.getTimelineDuration(), mVideoEditor.getDuration());
    }

    /**
     * To test creation of Media Video Item with Set and Get rendering Mode
     */
    // TODO : remove TC_API_003
    @LargeTest
    public void testMediaVideoItemRenderingModes() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int videoItemRenderingMode= MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);
        mediaVideoItem1.setRenderingMode(MediaItem.RENDERING_MODE_CROPPING);
        assertEquals("MediaVideo Item rendering Mode",
            MediaItem.RENDERING_MODE_CROPPING,
            mediaVideoItem1.getRenderingMode());
        try {
            mediaVideoItem1.setRenderingMode(
                MediaItem.RENDERING_MODE_CROPPING + 911);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Item Invalid rendering Mode", flagForException);
        flagForException = false;
        try {
            mediaVideoItem1.setRenderingMode(
                MediaItem.RENDERING_MODE_BLACK_BORDER - 11);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Item Invalid rendering Mode", flagForException);
        assertEquals("MediaVideo Item rendering Mode",
            MediaItem.RENDERING_MODE_CROPPING,
            mediaVideoItem1.getRenderingMode());
        mediaVideoItem1.setRenderingMode(MediaItem.RENDERING_MODE_STRETCH);
        assertEquals("MediaVideo Item rendering Mode",
            MediaItem.RENDERING_MODE_STRETCH,
            mediaVideoItem1.getRenderingMode());
    }

    /** Test Case  TC_API_004 is removed */

    /**
     * To Test the Media Video API : Set Audio Volume, Get Audio Volume and Mute
     */
    // TODO : remove TC_API_005
    @LargeTest
    public void testMediaVideoItemAudioFeatures() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int videoItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);
        mediaVideoItem1.setVolume(77);
        assertEquals("Updated Volume is 77", 77, mediaVideoItem1.getVolume());

        mediaVideoItem1.setMute(true);
        assertTrue("Audio must be Muted", mediaVideoItem1.isMuted());

        mediaVideoItem1.setVolume(78);
        assertEquals("Updated Volume is 78", 78, mediaVideoItem1.getVolume());
        assertTrue("Audio must be Muted", mediaVideoItem1.isMuted());

        try {
            mediaVideoItem1.setVolume(1000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Invalid Set Volume", flagForException);

        mediaVideoItem1.setMute(false);
        assertFalse("Audio must be Un-Muted", mediaVideoItem1.isMuted());

        mediaVideoItem1.setVolume(0);
        assertFalse("Audio must be Un-Muted", mediaVideoItem1.isMuted());

        flagForException = false;
        try {
            mediaVideoItem1.setVolume(-1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Invalid Set Volume", flagForException);

        mediaVideoItem1.setVolume(100);
        assertEquals("MediaItem Volume", 100, mediaVideoItem1.getVolume());
        try {
            mediaVideoItem1.setVolume(101);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Invalid Set Volume", flagForException);
        assertEquals("MediaItem Volume", 100, mediaVideoItem1.getVolume());
    }

    /**
     * To Test the Media Video API : GetWaveFormData and
     * extractAudioWaveFormData
     */

    // TODO : remove TC_API_006
    @LargeTest
    public void testMediaVideoItemGetWaveformData() throws Exception {

        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int videoItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        assertNull("WaveForm data", mediaVideoItem1.getWaveformData());
        final int[] progressWaveform = new int[105];

        mediaVideoItem1.extractAudioWaveform(new
            ExtractAudioWaveformProgressListener() {
                int i = 0;
                public void onProgress(int progress) {
                    Log.i("WaveformData","progress=" +progress);
                    progressWaveform[i++] = progress;
                }
            });
        assertTrue("Progress of WaveForm data", mVideoEditorHelper
            .checkProgressCBValues(progressWaveform));
        assertNotNull("WaveForm data", mediaVideoItem1.getWaveformData());
        assertTrue("WaveForm Frame Duration",
            (mediaVideoItem1.getWaveformData().getFrameDuration() > 0?
            true : false));
        assertTrue("WaveForm Frame Count",
            (mediaVideoItem1.getWaveformData().getFramesCount() > 0 ?
            true : false));
        assertTrue("WaveForm Gain",
            (mediaVideoItem1.getWaveformData().getFrameGains().length > 0 ?
            true : false));

    }

    /**
     * To Test the Media Video API : Get Effect, GetAllEffects, remove Effect
     */

    // TODO : remove TC_API_007
    @LargeTest
    public void testMediaVideoItemEffect() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int videoItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem1 = mVideoEditorHelper.
            createMediaItem(mVideoEditor, "mediaVideoItem1", videoItemFileName,
            videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        assertTrue("Effect List Size",
            (mediaVideoItem1.getAllEffects().size() == 0) ? true : false);
        assertNull("Effect Item by ID", mediaVideoItem1.getEffect("xyx"));

        final EffectColor effectColor = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "Effecton MVi1", 0, 4000, EffectColor.TYPE_GRADIENT,
            EffectColor.GRAY);
        mediaVideoItem1.addEffect(effectColor);

        assertTrue("Effect List Size", (mediaVideoItem1.
            getAllEffects().size() == 1) ? true : false);
        assertEquals("Effect Item by Valid ID", effectColor,
            mediaVideoItem1.getEffect(effectColor.getId()));
        assertNull("Effect Item by Invalid ID",
            mediaVideoItem1.getEffect("xyz"));
        assertNull("Effect Item by Invalid ID",
            mediaVideoItem1.removeEffect("effectId"));
        assertTrue("Effect List Size",
            (mediaVideoItem1.getAllEffects().size() == 1) ? true : false);
        assertEquals("Effect Removed", effectColor,
            mediaVideoItem1.removeEffect(effectColor.getId()));
        assertTrue("Effect List Size",
            (mediaVideoItem1.getAllEffects().size() == 0) ? true : false);
        assertNull("Effect Item by ID", mediaVideoItem1.getEffect("effectId"));
    }

    /**
     * To Test the Media Video API : Get Before and after transition
     */

    // TODO : remove TC_API_008
    @LargeTest
    public void testMediaVideoItemTransitions() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int videoItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);
        assertNull("Begin Transition", mediaVideoItem1.getBeginTransition());
        assertNull("End Transition", mediaVideoItem1.getEndTransition());

        TransitionFadeBlack transition1 =
            mVideoEditorHelper.createTFadeBlack("transition1", mediaVideoItem1,
            null, 0, Transition.BEHAVIOR_SPEED_UP);
        mVideoEditor.addTransition(transition1);
        assertEquals("Begin transition", transition1,
            mediaVideoItem1.getEndTransition());

        assertNotNull("End Transition", mediaVideoItem1.getEndTransition());
        assertTrue(mediaVideoItem1.
            getEndTransition().getId().equals(transition1.getId()));
        assertTrue(mediaVideoItem1.getEndTransition().getDuration() ==
            transition1.getDuration() ? true : false);
        assertTrue(mediaVideoItem1.getEndTransition().getBehavior() ==
            transition1.getBehavior() ? true : false);

        TransitionFadeBlack transition2 = mVideoEditorHelper.createTFadeBlack(
            "transition2", null,mediaVideoItem1, 0, Transition.BEHAVIOR_LINEAR);
        mVideoEditor.addTransition(transition2);
        assertNotNull("Begin transition", mediaVideoItem1.getBeginTransition());
        assertEquals("End Transition", transition2,
            mediaVideoItem1.getBeginTransition());
        assertTrue(mediaVideoItem1.
            getBeginTransition().getId().equals(transition2.getId()));
        assertTrue(mediaVideoItem1. getBeginTransition().getDuration() ==
            transition2.getDuration() ? true : false);
        assertTrue(mediaVideoItem1.getBeginTransition().getBehavior() ==
            transition2.getBehavior() ? true : false);
    }

    /**
     * To Test the Media Video API : Get All Overlay, Get Overlay and remove Overlay
     *
     */

    // TODO : remove TC_API_009
    @LargeTest
    public void testMediaVideoItemOverlays() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final String overlayItemFileName = INPUT_FILE_PATH +
            "IMG_176x144_Overlay1.png";
        final int videoItemRenderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        assertTrue("Overlay List Size",
            (mediaVideoItem1.getAllOverlays().size() == 0) ? true : false);
        assertNull("Overlay Item by ID", mediaVideoItem1.getOverlay("xyz"));

        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayItemFileName,
            176, 144);
        final OverlayFrame overlayFrame = mVideoEditorHelper.createOverlay(
            mediaVideoItem1, "overlayId", mBitmap, 5000, 5000);
        mediaVideoItem1.addOverlay(overlayFrame);

        assertTrue("Overlay List Size",
            (mediaVideoItem1.getAllOverlays().size() == 1) ? true : false);
        assertEquals("Overlay Item by Valid ID", overlayFrame, mediaVideoItem1
            .getOverlay(overlayFrame.getId()));
        assertNull("Overlay Item by Invalid ID",
            mediaVideoItem1.getOverlay("xyz"));
        assertNull("Overlay Item by Invalid ID",
            mediaVideoItem1.removeOverlay("xyz"));
        assertTrue("Overlay List Size",
            (mediaVideoItem1.getAllOverlays().size() == 1) ? true : false);
        assertEquals("Overlay Removed", overlayFrame,
            mediaVideoItem1.removeOverlay(overlayFrame.getId()));
        assertTrue("Overlay List Size",
            (mediaVideoItem1.getAllOverlays().size() == 0) ? true : false);
        assertNull("Overlay Item by ID",mediaVideoItem1.getOverlay("effectId"));
    }

    /**
     * To Test Creation of Media Image Item.
     */
    // TODO : remove TC_API_010
    @LargeTest
    public void testMediaImageItem() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
                imageItemFileName, 5000, imageItemRenderingMode);
        assertTrue("Media Image ID",
            mediaImageItem1.getId().equals("mediaImageItem1"));
        assertTrue("Media IMage Filename",
            mediaImageItem1.getFilename().equals(imageItemFileName));
        assertEquals("Media Image Rendering Mode",
            imageItemRenderingMode, mediaImageItem1.getRenderingMode());
        assertEquals("Media Image Item Duration", mediaImageItem1.getDuration(),
            mediaImageItem1.getTimelineDuration());
        assertEquals("Media Image Overlay", 0,
            mediaImageItem1.getAllOverlays().size());
        assertEquals("Media Image Effect", 0,
            mediaImageItem1.getAllEffects().size());
        assertNull("Media Image Begin transition",
            mediaImageItem1.getBeginTransition());
        assertNull("Media Image End transition",
            mediaImageItem1.getEndTransition());
        assertEquals("Media Image Scaled Height", MediaProperties.HEIGHT_720,
            mediaImageItem1.getScaledHeight());
        assertEquals("Media Image Scaled Width", 960,
            mediaImageItem1.getScaledWidth());
        assertEquals("Media Image Aspect Ratio", MediaProperties.ASPECT_RATIO_4_3,
            mediaImageItem1.getAspectRatio());
        assertNotNull("Media Image Thumbnail",
            mediaImageItem1.getThumbnail(960, MediaProperties.HEIGHT_720, 2000));
    }

    /**
     * To Test the Media Image API : Get and Set rendering Mode
     */
    // TODO : remove TC_API_011
    @LargeTest
    public void testMediaImageItemRenderingModes() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final int imageItemRenderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, imageItemRenderingMode, 5000);
        mVideoEditor.addMediaItem(mediaImageItem1);

        mediaImageItem1.setRenderingMode(MediaItem.RENDERING_MODE_CROPPING);
        assertEquals("MediaVideo Item rendering Mode",
            MediaItem.RENDERING_MODE_CROPPING, mediaImageItem1.getRenderingMode());
        try {
            mediaImageItem1.setRenderingMode(
                MediaItem.RENDERING_MODE_CROPPING + 911);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Item Invalid rendering Mode", flagForException);

        flagForException = false;
        try {
            mediaImageItem1.setRenderingMode(
                MediaItem.RENDERING_MODE_BLACK_BORDER - 11);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Item Invalid rendering Mode", flagForException);

        assertEquals("MediaVideo Item rendering Mode",
            MediaItem.RENDERING_MODE_CROPPING,
            mediaImageItem1.getRenderingMode());
        mediaImageItem1.setRenderingMode(MediaItem.RENDERING_MODE_STRETCH);
        assertEquals("MediaVideo Item rendering Mode",
            MediaItem.RENDERING_MODE_STRETCH,
            mediaImageItem1.getRenderingMode());
    }

    /**
     * To Test the Media Image API : GetHeight and GetWidth
     */
    // TODO : remove TC_API_012
    @LargeTest
    public void testMediaImageItemHeightWidth() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, imageItemRenderingMode, 5000);
        mVideoEditor.addMediaItem(mediaImageItem1);

        assertEquals("Image Height = Image Scaled Height",
            mediaImageItem1.getScaledHeight(), mediaImageItem1.getHeight());
        assertEquals("Image Width = Image Scaled Width",
            mediaImageItem1.getScaledWidth(), mediaImageItem1.getWidth());
    }



/**    This Test Case can be removed as this is already checked in TC 010 */
    /**
     * To Test the Media Image API : Scaled Height and Scaled GetWidth
     */
    // TODO : remove TC_API_013
    @LargeTest
    public void testMediaImageItemScaledHeightWidth() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, imageItemRenderingMode, 5000);
        mVideoEditor.addMediaItem(mediaImageItem1);

        assertNotSame("Image Height = Image Scaled Height",
            mediaImageItem1.getScaledHeight(), mediaImageItem1.getHeight());
        assertNotSame("Image Width = Image Scaled Width",
            mediaImageItem1.getScaledWidth(), mediaImageItem1.getWidth());
    }

    /**
     * To Test the Media Image API : Get Effect, GetAllEffects, remove Effect
     */

    // TODO : remove TC_API_014
    @LargeTest
    public void testMediaImageItemEffect() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, 5000, imageItemRenderingMode);
        mVideoEditor.addMediaItem(mediaImageItem1);

        assertTrue("Effect List Size",
            (mediaImageItem1.getAllEffects().size() == 0) ? true : false);
        assertNull("Effect Item by ID", mediaImageItem1.getEffect("xyx"));

        final EffectColor effectColor =
            mVideoEditorHelper.createEffectItem(mediaImageItem1,
            "Effecton MVi1", 0, 4000, EffectColor.TYPE_GRADIENT, EffectColor.GRAY);
        mediaImageItem1.addEffect(effectColor);

        assertTrue("Effect List Size",
            (mediaImageItem1.getAllEffects().size() == 1) ? true : false);
        assertEquals("Effect Item by Valid ID",
            effectColor, mediaImageItem1.getEffect(effectColor.getId()));
        assertNull("Effect Item by Invalid ID",
            mediaImageItem1.getEffect("xyz"));
        assertNull("Effect Item by Invalid ID",
            mediaImageItem1.removeEffect("effectId"));
        assertTrue("Effect List Size",
            (mediaImageItem1.getAllEffects().size() == 1) ? true : false);
        assertEquals("Effect Removed", effectColor,
            mediaImageItem1.removeEffect(effectColor.getId()));
        assertTrue("Effect List Size",
            (mediaImageItem1.getAllEffects().size() == 0) ? true : false);
        assertNull("Effect Item by ID", mediaImageItem1.getEffect("effectId"));
    }

    /**
     * To Test the Media Image API : Get Before and after transition
     */

    // TODO : remove TC_API_015
    @LargeTest
    public void testMediaImageItemTransitions() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, 5000, imageItemRenderingMode);
        mVideoEditor.addMediaItem(mediaImageItem1);

        assertNull("Begin Transition", mediaImageItem1.getBeginTransition());
        assertNull("End Transition", mediaImageItem1.getEndTransition());

        TransitionFadeBlack transition1 =
            mVideoEditorHelper.createTFadeBlack("transition1", mediaImageItem1,
            null, 0, Transition.BEHAVIOR_SPEED_UP);
        mVideoEditor.addTransition(transition1);

        assertEquals("Begin transition", transition1,
            mediaImageItem1.getEndTransition());
        assertNotNull("End Transition", mediaImageItem1.getEndTransition());
        assertTrue(mediaImageItem1.getEndTransition().getId().equals
            (transition1.getId()));
        assertTrue(mediaImageItem1.getEndTransition().getDuration() ==
            transition1.getDuration() ? true : false);
        assertTrue(mediaImageItem1.getEndTransition().getBehavior() ==
            transition1.getBehavior() ? true : false);

        TransitionFadeBlack transition2 = mVideoEditorHelper.createTFadeBlack(
            "transition2",null, mediaImageItem1, 0, Transition.BEHAVIOR_SPEED_UP);
        mVideoEditor.addTransition(transition2);

        assertNotNull("Begin transition", mediaImageItem1.getBeginTransition());
        assertEquals("End Transition", transition2,
            mediaImageItem1.getBeginTransition());
        assertTrue(mediaImageItem1.getBeginTransition().getId().equals(
            transition2.getId()));
        assertTrue(mediaImageItem1.getBeginTransition().getDuration() ==
            transition2.getDuration() ? true : false);
        assertTrue(mediaImageItem1.getBeginTransition().getBehavior() ==
            transition2.getBehavior() ? true : false);
    }

    /**
     * To Test the Media Image API : Get All Overlay, Get Overlay and remove
     * Overlay
     */

    // TODO : remove TC_API_016
    @LargeTest
    public void testMediaImageItemOverlays() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String overlayItemFileName = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, 12000, imageItemRenderingMode);
        mVideoEditor.addMediaItem(mediaImageItem1);

        assertTrue("Overlay List Size",
            (mediaImageItem1.getAllOverlays().size() == 0) ? true : false);
        assertNull("Overlay Item by ID", mediaImageItem1.getOverlay("xyz"));
        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayItemFileName,
            640, 480);
        final OverlayFrame overlayFrame =
            mVideoEditorHelper.createOverlay(mediaImageItem1, "overlayId",
            mBitmap, 5000, 5000);
        mediaImageItem1.addOverlay(overlayFrame);

        assertTrue("Overlay List Size",
            (mediaImageItem1.getAllOverlays().size() == 1) ? true : false);
        assertEquals("Overlay Item by Valid ID", overlayFrame, mediaImageItem1
            .getOverlay(overlayFrame.getId()));
        assertNull("Overlay Item by Invalid ID",
            mediaImageItem1.getOverlay("xyz"));
        assertNull("Remove Overlay Item by Invalid ID",
            mediaImageItem1.removeOverlay("xyz"));
        assertTrue("Overlay List Size",
            (mediaImageItem1.getAllOverlays().size() == 1) ? true : false);
        assertEquals("Overlay Removed",
            overlayFrame, mediaImageItem1.removeOverlay(overlayFrame.getId()));
        assertTrue("Overlay List Size",
            (mediaImageItem1.getAllOverlays().size() == 0) ? true : false);
        assertNull("Overlay Item by ID",
            mediaImageItem1.getOverlay("effectId"));
    }

    /**
     * To test creation of Audio Track
     */

    // TODO : remove TC_API_017
    @LargeTest
    public void testAudioTrack() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);

        assertEquals("Audio Track Item Duration", audioTrack.getDuration(),
            audioTrack.getTimelineDuration());
        assertEquals("Audio Track Start Time", 0, audioTrack.getStartTime());
        assertFalse("Audio Track is Looping", audioTrack.isLooping());
        audioTrack.getVolume();
        assertFalse("Audio Track Ducking is Disabled",
            audioTrack.isDuckingEnabled());
        assertTrue("Audio Track Filename",
            audioTrack.getFilename().equals(audioFileName));
         assertEquals("Audio Ducking Threshold", 0,
            audioTrack.getDuckingThreshhold());
         assertFalse("Audio Track Mute", audioTrack.isMuted());
         audioTrack.getDuckedTrackVolume();
    }

    /**
     * To test creation of Audio Track with set extract boundaries
     */
    // TODO : remove TC_API_018
    @LargeTest
    public void testAudioTrackExtractBoundaries() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        boolean flagForException = false;
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);

        audioTrack.setExtractBoundaries(1000, 5000);
        assertEquals("Audio Track Start time", 1000,
            audioTrack.getBoundaryBeginTime());
        assertEquals("Audio Track End time", 5000,
            audioTrack.getBoundaryEndTime());
        try {
            audioTrack.setExtractBoundaries(0, 100000000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Audio Track With endTime > FileDuration", flagForException);
        flagForException = false;
        try {
            audioTrack.setExtractBoundaries(100000000, 5000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Audio Track With startTime > FileDuration",
            flagForException);
        flagForException = false;
        try {
            audioTrack.setExtractBoundaries(0, 0);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        /* This is under discussion.  Hence, checked for False */
        assertFalse("Audio Track With startTime = endTime", flagForException);
        assertEquals("Audio Track Start time", 0,
            audioTrack.getBoundaryBeginTime());
        assertEquals("Audio Track End time", 0,
            audioTrack.getBoundaryEndTime());
        assertEquals("Audio Track Start time",0,
            audioTrack.getBoundaryBeginTime());
        assertEquals("Audio Track End time", (audioTrack.getTimelineDuration()),
            audioTrack.getBoundaryEndTime());
        audioTrack.setExtractBoundaries(0, audioTrack.getDuration() / 2);
        assertEquals("Audio Track Start time",0,
            audioTrack.getBoundaryBeginTime());
        assertEquals("Audio Track End time", (audioTrack.getDuration() / 2),
            audioTrack.getBoundaryEndTime());
        audioTrack.setExtractBoundaries(1, audioTrack.getDuration() - 1);
        assertEquals("Audio Track Start time", 1,
            audioTrack.getBoundaryBeginTime());
        assertEquals("Audio Track End time", (audioTrack.getDuration() - 1),
            audioTrack.getBoundaryEndTime());

        flagForException = false;
        try {
                audioTrack.setExtractBoundaries(0, -1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue ("Audio Track end time < 0",flagForException);
    }

    /**
     * To test creation of Audio Track with set Start Time and Get Time
     */
    // TODO : remove TC_API_019
    @LargeTest
    public void testAudioTrackSetGetTime() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        boolean flagForException = false;
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);
        /** set StartTime API is removed and start time is always 0 */
        assertEquals("Audio Track Start Time", 0, audioTrack.getStartTime());
    }

    /**
     * To Test the Audio Track API: Enable Ducking
     */
    // TODO : remove TC_API_020
    @LargeTest
    public void testAudioTrackEnableDucking() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        boolean flagForException = false;
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);

        assertFalse("Audio Ducking Disabled by default",
            audioTrack.isDuckingEnabled());
        audioTrack.enableDucking(45, 70);
        assertTrue("Audio Ducking Enabled", audioTrack.isDuckingEnabled());
        assertEquals("Audio Ducking Threshold", 45,
            audioTrack.getDuckingThreshhold());
        assertEquals("Audio Ducking Volume", 70,
            audioTrack.getDuckedTrackVolume());
        audioTrack.enableDucking(85, 70);
        assertEquals("Audio Ducking Threshold", 85,
            audioTrack.getDuckingThreshhold());
        assertEquals("Audio Ducking Volume", 70,
            audioTrack.getDuckedTrackVolume());
        try {
            audioTrack.enableDucking(91, 70);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Enable ducking threshold > 90", flagForException);
        flagForException = false;
        try {
            audioTrack.enableDucking(90, 101);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Enable ducking volume > 100", flagForException);
        flagForException = false;
        try {
            audioTrack.enableDucking(91, 101);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Enable ducking volume > 100 and threshold > 91",
            flagForException);
        flagForException = false;
        try {
            audioTrack.enableDucking(-1, 100);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Enable ducking threshold < 0", flagForException);
        flagForException = false;
        try {
            audioTrack.enableDucking(1, -1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Enable ducking lowVolume < 0", flagForException);
        flagForException = false;
        try {
            audioTrack.enableDucking(0, 50);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertFalse("Enable ducking threshold = 0", flagForException);
    }

    /**
     * To Test the Audio Track API: Looping
     */
    // TODO : remove TC_API_021
    @LargeTest
    public void testAudioTrackLooping() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);
        assertFalse("Audio Looping", audioTrack.isLooping());
        audioTrack.enableLoop();
        assertTrue("Audio Looping", audioTrack.isLooping());
        audioTrack.disableLoop();
        assertFalse("Audio Looping", audioTrack.isLooping());
    }

    /**
     * To Test the Audio Track API:Extract waveform data
     */
    // TODO : remove TC_API_022

    @LargeTest
    public void testAudioTrackWaveFormData() throws Exception {
        /** Image item is added as dummy as Audio track cannot be added without
         * a media item in the story board
         */
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, 5000, imageItemRenderingMode);
        mVideoEditor.addMediaItem(mediaImageItem);

        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);

        mVideoEditor.addAudioTrack(audioTrack);
        assertNull("WaveForm data", audioTrack.getWaveformData());

        final int[] progressUpdate = new int[105];
        mVideoEditor.generatePreview(new MediaProcessingProgressListener() {
            int i = 0;
            public void onProgress(Object item, int action, int progress) {
                progressUpdate[i++] = progress;
            }
        });

        final int[] progressWaveform = new int[105];

        audioTrack.extractAudioWaveform(
            new ExtractAudioWaveformProgressListener() {
                int i = 0;
                public void onProgress(int progress) {
                    Log.i("AudioWaveformData","progress=" +progress);
                    progressWaveform[i++] = progress;
            }
        });
        assertTrue("Progress of WaveForm data", mVideoEditorHelper
            .checkProgressCBValues(progressWaveform));
        assertNotNull("WaveForm data", audioTrack.getWaveformData());
        assertTrue("WaveForm Frame Duration",
            (audioTrack.getWaveformData().getFrameDuration() > 0 ?
            true : false));
        assertTrue("WaveForm Frame Count",
            (audioTrack.getWaveformData().getFramesCount() > 0 ? true : false));
        assertTrue("WaveForm Gain",
            (audioTrack.getWaveformData().getFrameGains().length > 0 ?
            true : false));
    }

    /**
     * To Test the Audio Track API: Mute
     */
    // TODO : remove TC_API_023
    @LargeTest
    public void testAudioTrackMute() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        assertFalse("Audio Track UnMute", audioTrack.isMuted());
        audioTrack.setMute(true);
        assertTrue("Audio Track Mute", audioTrack.isMuted());
        audioTrack.setMute(false);
        assertFalse("Audio Track UnMute", audioTrack.isMuted());
    }

    /**
     * To Test the Audio Track API: Get Volume and Set Volume
     */
    // TODO : remove TC_API_024
    @LargeTest
    public void testAudioTrackGetSetVolume() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        boolean flagForException = false;
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        audioTrack.setVolume(0);
        assertEquals("Audio Volume", 0, audioTrack.getVolume());
        assertFalse("Audio Track UnMute", audioTrack.isMuted());
        audioTrack.setVolume(45);
        assertEquals("Audio Volume", 45, audioTrack.getVolume());
        assertFalse("Audio Track UnMute", audioTrack.isMuted());
        try {
            audioTrack.setVolume(-1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Volume = -1", flagForException);
        assertEquals("Audio Volume", 45, audioTrack.getVolume());
        flagForException = false;
        try {
            audioTrack.setVolume(101);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Volume = 101", flagForException);
        flagForException = false;
        try {
            audioTrack.setVolume(1000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Volume = 10000", flagForException);
        assertEquals("Audio Volume", 45, audioTrack.getVolume());
    }

    /**
     * To test Effect Color.
     */
    // TODO : remove TC_API_025
    @LargeTest
    public void testAllEffects() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_30fps_512Kbps_0_27.mp4";
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final EffectColor effectColor1 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect1", 1000, 1000, EffectColor.TYPE_COLOR,
            EffectColor.PINK);
        mediaVideoItem1.addEffect(effectColor1);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor1.getMediaItem());
        assertTrue("Effect Id", effectColor1.getId().equals("effect1"));
        assertEquals("Effect StartTime", 1000, effectColor1.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor1.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_COLOR,
            effectColor1.getType());
        assertEquals("Effect Color", EffectColor.PINK, effectColor1.getColor());

        final EffectColor effectColor2 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect2", 2000, 1000, EffectColor.TYPE_COLOR,
            EffectColor.GRAY);
        mediaVideoItem1.addEffect(effectColor2);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor2.getMediaItem());
        assertTrue("Effect Id", effectColor2.getId().equals("effect2"));
        assertEquals("Effect StartTime", 2000, effectColor2.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor2.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_COLOR,
            effectColor2.getType());
        assertEquals("Effect Color", EffectColor.GRAY, effectColor2.getColor());

        final EffectColor effectColor3 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect3", 3000, 1000, EffectColor.TYPE_COLOR,
            EffectColor.GREEN);
        mediaVideoItem1.addEffect(effectColor3);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor3.getMediaItem());
        assertTrue("Effect Id", effectColor3.getId().equals("effect3"));
        assertEquals("Effect StartTime", 3000, effectColor3.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor3.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_COLOR,
            effectColor3.getType());
        assertEquals("Effect Color", EffectColor.GREEN, effectColor3.getColor());

        final EffectColor effectColor4 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect4", 4000, 1000, EffectColor.TYPE_GRADIENT,
            EffectColor.PINK);
        mediaVideoItem1.addEffect(effectColor4);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor4.getMediaItem());
        assertTrue("Effect Id", effectColor4.getId().equals("effect4"));
        assertEquals("Effect StartTime", 4000, effectColor4.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor4.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_GRADIENT,
            effectColor4.getType());
        assertEquals("Effect Color", EffectColor.PINK, effectColor4.getColor());

        final EffectColor effectColor5 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect5", 5000, 1000,
            EffectColor.TYPE_GRADIENT, EffectColor.GRAY);
        mediaVideoItem1.addEffect(effectColor5);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor5.getMediaItem());
        assertTrue("Effect Id", effectColor5.getId().equals("effect5"));
        assertEquals("Effect StartTime", 5000, effectColor5.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor5.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_GRADIENT,
            effectColor5.getType());
        assertEquals("Effect Color", EffectColor.GRAY, effectColor5.getColor());

        final EffectColor effectColor6 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect6", 6000, 1000,
            EffectColor.TYPE_GRADIENT, EffectColor.GREEN);
        mediaVideoItem1.addEffect(effectColor6);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor6.getMediaItem());
        assertTrue("Effect Id", effectColor6.getId().equals("effect6"));
        assertEquals("Effect StartTime", 6000, effectColor6.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor6.getDuration());
        assertEquals("Effect Type",
            EffectColor.TYPE_GRADIENT, effectColor6.getType());
        assertEquals("Effect Color",
            EffectColor.GREEN, effectColor6.getColor());

        final EffectColor effectColor7 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect7", 7000, 1000,
            EffectColor.TYPE_FIFTIES, 0);
        mediaVideoItem1.addEffect(effectColor7);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor7.getMediaItem());
        assertTrue("Effect Id", effectColor7.getId().equals("effect7"));
        assertEquals("Effect StartTime", 7000, effectColor7.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor7.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_FIFTIES,
            effectColor7.getType());
        assertEquals("Effect Color", -1, effectColor7.getColor());

        final EffectColor effectColor8 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect8", 8000, 1000, EffectColor.TYPE_SEPIA, 0);
        mediaVideoItem1.addEffect(effectColor8);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor8.getMediaItem());
        assertTrue("Effect Id", effectColor8.getId().equals("effect8"));
        assertEquals("Effect StartTime", 8000, effectColor8.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor8.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_SEPIA,
            effectColor8.getType());
        assertEquals("Effect Color", -1, effectColor8.getColor());

        final EffectColor effectColor9 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect9", 9000, 1000,
            EffectColor.TYPE_NEGATIVE, 0);
        mediaVideoItem1.addEffect(effectColor9);

        assertEquals("Associated Media Item", mediaVideoItem1,
            effectColor9.getMediaItem());
        assertTrue("Effect Id", effectColor9.getId().equals("effect9"));
        assertEquals("Effect StartTime", 9000, effectColor9.getStartTime());
        assertEquals("Effect EndTime", 1000, effectColor9.getDuration());
        assertEquals("Effect Type", EffectColor.TYPE_NEGATIVE,
            effectColor9.getType());
        assertEquals("Effect Color", -1, effectColor9.getColor());
        try {
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effect9",
                9000, 1000, EffectColor.TYPE_COLOR - 1, 0);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect type Invalid", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effect9",
                9000, 1000, EffectColor.TYPE_FIFTIES + 1, 0);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect type Invalid", flagForException);
        try {
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effect10",
                10000, 1000, EffectColor.TYPE_FIFTIES +
                EffectColor.TYPE_GRADIENT, 0);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect type Invalid", flagForException);
    }

    /**
     * To test Effect Color : Set duration and Get Duration
     */
    // TODO : remove TC_API_026
    @LargeTest
    public void testEffectSetgetDuration() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_30fps_512Kbps_0_27.mp4";
        final int videoItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final EffectColor effectColor1 = mVideoEditorHelper.createEffectItem(
            mediaVideoItem1, "effect1", 1000, 2000,
            EffectColor.TYPE_COLOR, EffectColor.PINK);
        mediaVideoItem1.addEffect(effectColor1);

        effectColor1.setDuration(5000);
        assertEquals("Updated Effect Duration", 5000,
            effectColor1.getDuration());
        try {
            effectColor1.setDuration(mediaVideoItem1.getDuration() + 1000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect Color duration > mediaVideoItemDuration",
            flagForException);
        assertEquals("Effect Duration", 5000, effectColor1.getDuration());
        flagForException = false;
        try {
            effectColor1.setDuration(-1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect Color duration = -1", flagForException);
    }

    /**
     * To test Effect Color : UNDEFINED color param value
     */
    // TODO : remove TC_API_027
    @LargeTest
    public void testEffectUndefinedColorParam() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_30fps_512Kbps_0_27.mp4";
        final int videoItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);
        try{
        mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effect1", 1000,
            2000, EffectColor.TYPE_COLOR, 0xabcdabcd);
        }catch (IllegalArgumentException e){
            flagForException = true;
        }
        assertTrue("Invalid Effect added",flagForException);
    }

    /**
     * To test Effect Color : with Invalid StartTime and Duration
     */
    // TODO : remove TC_API_028
    @LargeTest
    public void testEffectInvalidStartTimeAndDuration() throws Exception {
        final String videoItemFileName = INPUT_FILE_PATH +
            "H264_BP_640x480_15fps_1200Kbps_AACLC_48KHz_32kbps_m_1_17.3gp";
        final int videoItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaVideoItem1",
            videoItemFileName, videoItemRenderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        try {
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effect1",
                400000000, 2000, EffectColor.TYPE_COLOR, EffectColor.GREEN);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect with invalid StartTime", flagForException);

        flagForException = false;
        try {
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effect1", -1,
                2000, EffectColor.TYPE_COLOR, EffectColor.GREEN);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect with invalid StartTime", flagForException);

        flagForException = false;
        try {
            mVideoEditorHelper.createEffectItem(mediaVideoItem1, "effect1",
                2000, -1, EffectColor.TYPE_COLOR, EffectColor.GREEN);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect with invalid Duration", flagForException);
    }


    /** Test cases 29, 30, 31, 32 and 33 are removed */


    /**
     * To test Effect : with NULL Media Item
     */
    // TODO : remove TC_API_034
    @LargeTest
    public void testEffectNullMediaItem() throws Exception {
        boolean flagForException = false;
        try {
            mVideoEditorHelper.createEffectItem(null, "effect1", 1000, 4000,
                EffectColor.TYPE_COLOR, EffectColor.GREEN);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Effect with null MediaItem", flagForException);
    }

    /**
     * To test Effect : KenBurn Effect
     */
    // TODO : remove TC_API_035
    @LargeTest
    public void testEffectKenBurn() throws Exception {
        // Test ken burn effect using a JPEG file.
        testEffectKenBurn(INPUT_FILE_PATH + "IMG_640x480.jpg",
         "mediaImageItem1");

        // Test ken burn effect using a PNG file
        testEffectKenBurn(INPUT_FILE_PATH + "IMG_640x480.png",
         "mediaImageItem2");
    }

    private void testEffectKenBurn(final String imageItemFileName,
     final String MediaId) throws Exception {
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, MediaId,
            imageItemFileName, 5000, imageItemRenderingMode);
        mVideoEditor.addMediaItem(mediaImageItem);

        final Rect startRect = new Rect((mediaImageItem.getHeight() / 3),
            (mediaImageItem.getWidth() / 3), (mediaImageItem.getHeight() / 2),
            (mediaImageItem.getWidth() / 2));
        final Rect endRect = new Rect(0, 0, mediaImageItem.getWidth(),
            mediaImageItem.getHeight());

        final EffectKenBurns kbEffectOnMediaItem = new EffectKenBurns(
            mediaImageItem, "KBOnM2", startRect, endRect, 500, 3000);

        assertNotNull("EffectKenBurns: " + imageItemFileName,
            kbEffectOnMediaItem);

        mediaImageItem.addEffect(kbEffectOnMediaItem);
        assertEquals("KenBurn Start Rect: " + imageItemFileName, startRect,
            kbEffectOnMediaItem.getStartRect());

        assertEquals("KenBurn End Rect: " + imageItemFileName, endRect,
            kbEffectOnMediaItem.getEndRect());
    }

    /**
     * To test KenBurnEffect : Set StartRect and EndRect
     */

    // TODO : remove TC_API_036
    @LargeTest
    public void testEffectKenBurnSet() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int imageItemRenderingMode =MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "mediaImageItem1",
            imageItemFileName, 5000, imageItemRenderingMode);
        mVideoEditor.addMediaItem(mediaImageItem);

        final Rect startRect = new Rect((mediaImageItem.getHeight() / 3),
            (mediaImageItem.getWidth() / 3), (mediaImageItem.getHeight() / 2),
            (mediaImageItem.getWidth() / 2));
        final Rect endRect = new Rect(0, 0, mediaImageItem.getWidth(),
            mediaImageItem.getHeight());

        EffectKenBurns kbEffectOnMediaItem=null;
        kbEffectOnMediaItem = new EffectKenBurns(mediaImageItem, "KBOnM2",
            startRect, endRect, 500, 3000);

        assertNotNull("EffectKenBurns", kbEffectOnMediaItem);
        mediaImageItem.addEffect(kbEffectOnMediaItem);
        assertEquals("KenBurn Start Rect", startRect,
            kbEffectOnMediaItem.getStartRect());
        assertEquals("KenBurn End Rect", endRect,
            kbEffectOnMediaItem.getEndRect());

        final Rect startRect1 = new Rect((mediaImageItem.getHeight() / 5),
            (mediaImageItem.getWidth() / 5), (mediaImageItem.getHeight() / 4),
            (mediaImageItem.getWidth() / 4));
        final Rect endRect1 = new Rect(10, 10, mediaImageItem.getWidth() / 4,
            mediaImageItem.getHeight() / 4);

        /* Added newly to take care of removal set APIs */
        kbEffectOnMediaItem = new EffectKenBurns(mediaImageItem, "KBOnM2_changed",
            startRect1, endRect1, 500, 3000);

        assertEquals("KenBurn Start Rect", startRect1,
            kbEffectOnMediaItem.getStartRect());
        assertEquals("KenBurn End Rect", endRect1,
            kbEffectOnMediaItem.getEndRect());

        final Rect zeroRect = new Rect(0, 0, 0, 0);
        try {
            kbEffectOnMediaItem = new EffectKenBurns(mediaImageItem, "KBOnM2_zeroStart",
                zeroRect, endRect, 500, 3000);

        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Invalid Start Rect", flagForException);

        flagForException = false;
        try {
            kbEffectOnMediaItem = new EffectKenBurns(mediaImageItem, "KBOnM2_zeroEnd",
                startRect, zeroRect, 500, 3000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Invalid End Rect", flagForException);
    }

    /**
     * To test Transition : Fade To Black with all behavior
     * SPEED_UP/SPEED_DOWN/LINEAR/MIDDLE_SLOW/MIDDLE_FAST
     */

    // TODO : remove TC_API_037
    @LargeTest
    public void testTransitionFadeBlack() throws Exception {

        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String videoItemFilename2 = INPUT_FILE_PATH +
            "H263_profile0_176x144_15fps_128kbps_1_35.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFilename3 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final String videoItemFilename4 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_256kbps_0_30.mp4";
        final String videoItemFilename5 = INPUT_FILE_PATH +
            "H263_profile0_176x144_10fps_96kbps_0_25.3gp";
        boolean flagForException = false;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
            videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
            videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem2.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final TransitionFadeBlack transition1And2 = mVideoEditorHelper
            .createTFadeBlack("transition1And2", mediaVideoItem1,
            mediaVideoItem2, 3000, Transition.BEHAVIOR_SPEED_UP);
        mVideoEditor.addTransition(transition1And2);

        assertTrue("Transition ID",
            transition1And2.getId().equals("transition1And2"));
        assertEquals("Transtion After Media item",
            mediaVideoItem1, transition1And2.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem2,
            transition1And2.getBeforeMediaItem());
        assertEquals("Transtion Duration", 3000, transition1And2.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_UP,
            transition1And2.getBehavior());

        final MediaImageItem mediaImageItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                imageItemFilename1, 15000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem3);

        final TransitionFadeBlack transition2And3 =
            mVideoEditorHelper.createTFadeBlack("transition2And3", mediaVideoItem2,
                mediaImageItem3, 1000, Transition.BEHAVIOR_SPEED_DOWN);
        mVideoEditor.addTransition(transition2And3);

        assertTrue("Transition ID",
            transition2And3.getId().equals("transition2And3"));
        assertEquals("Transtion After Media item", mediaVideoItem2,
            transition2And3.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaImageItem3,
            transition2And3.getBeforeMediaItem());
        assertEquals("Transtion Duration", 1000, transition2And3.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_DOWN,
            transition2And3.getBehavior());

        final MediaVideoItem mediaVideoItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                videoItemFilename3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem4.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaVideoItem4);

        final TransitionFadeBlack transition3And4 =
            mVideoEditorHelper.createTFadeBlack("transition3And4", mediaImageItem3,
                mediaVideoItem4, 5000, Transition.BEHAVIOR_LINEAR);
        mVideoEditor.addTransition(transition3And4);

        assertTrue("Transition ID",
            transition3And4.getId().equals("transition3And4"));
        assertEquals("Transtion After Media item", mediaImageItem3,
            transition3And4.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem4,
            transition3And4.getBeforeMediaItem());
        assertEquals("Transtion Duration", 5000, transition3And4.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_LINEAR,
            transition3And4.getBehavior());

        final MediaVideoItem mediaVideoItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                videoItemFilename4, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem5);

        final TransitionFadeBlack transition4And5 =
            mVideoEditorHelper.createTFadeBlack("transition4And5", mediaVideoItem4,
                mediaVideoItem5, 8000, Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(transition4And5);

        assertTrue("Transition ID",
            transition4And5.getId().equals("transition4And5"));
        assertEquals("Transtion After Media item", mediaVideoItem4,
            transition4And5.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem5,
            transition4And5.getBeforeMediaItem());
        assertEquals("Transtion Duration", 8000, transition4And5.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_MIDDLE_FAST,
            transition4And5.getBehavior());

        final MediaVideoItem mediaVideoItem6 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m6",
                videoItemFilename5, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem6.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaVideoItem6);

        final TransitionFadeBlack transition5And6 =
            mVideoEditorHelper.createTFadeBlack("transition5And6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_SLOW);
        mVideoEditor.addTransition(transition5And6);

        assertTrue("Transition ID",
            transition5And6.getId().equals("transition5And6"));
        assertEquals("Transtion After Media item", mediaVideoItem5,
            transition5And6.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem6,
            transition5And6.getBeforeMediaItem());
        assertEquals("Transtion Duration", 2000, transition5And6.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_MIDDLE_SLOW,
            transition5And6.getBehavior());
        flagForException = false;
        try {
            mVideoEditorHelper.createTFadeBlack("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_SPEED_UP - 1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition FadeBlack with Invalid behavior", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createTFadeBlack("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_FAST + 1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition FadeBlack with Invalid behavior", flagForException);
    }

    /**
     * To test Transition : CrossFade with all behavior
     * SPEED_UP/SPEED_DOWN/LINEAR/MIDDLE_SLOW/MIDDLE_FAST
     */

    // TODO : remove TC_API_038
    @LargeTest
    public void testTransitionCrossFade() throws Exception {

        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String videoItemFilename2 = INPUT_FILE_PATH +
            "H263_profile0_176x144_15fps_128kbps_1_35.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_1600x1200.jpg";
        final String videoItemFilename3 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final String videoItemFilename4 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_256kbps_0_30.mp4";
        final String videoItemFilename5 = INPUT_FILE_PATH +
            "H263_profile0_176x144_10fps_96kbps_0_25.3gp";
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem2.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final TransitionCrossfade transition1And2 =
            mVideoEditorHelper.createTCrossFade("transition1And2", mediaVideoItem1,
                mediaVideoItem2, 3000, Transition.BEHAVIOR_SPEED_UP);
        mVideoEditor.addTransition(transition1And2);

        assertTrue("Transition ID",
            transition1And2.getId().equals("transition1And2"));
        assertEquals("Transtion After Media item", mediaVideoItem1,
            transition1And2.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem2,
            transition1And2.getBeforeMediaItem());
        assertEquals("Transtion Duration", 3000, transition1And2.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_UP,
            transition1And2.getBehavior());

        final MediaImageItem mediaImageItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                imageItemFilename1, 15000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem3);

        final TransitionCrossfade transition2And3 =
            mVideoEditorHelper.createTCrossFade("transition2And3", mediaVideoItem2,
                mediaImageItem3, 1000, Transition.BEHAVIOR_SPEED_DOWN);
        mVideoEditor.addTransition(transition2And3);

        assertTrue("Transition ID",
            transition2And3.getId().equals("transition2And3"));
        assertEquals("Transtion After Media item", mediaVideoItem2,
            transition2And3.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaImageItem3,
            transition2And3.getBeforeMediaItem());
        assertEquals("Transtion Duration", 1000, transition2And3.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_DOWN,
            transition2And3.getBehavior());

        final MediaVideoItem mediaVideoItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                videoItemFilename3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem4.setExtractBoundaries(0, 18000);
        mVideoEditor.addMediaItem(mediaVideoItem4);

        final TransitionCrossfade transition3And4 =
            mVideoEditorHelper.createTCrossFade("transition3And4", mediaImageItem3,
                mediaVideoItem4, 5000, Transition.BEHAVIOR_LINEAR);
        mVideoEditor.addTransition(transition3And4);

        assertTrue("Transition ID",
            transition3And4.getId().equals("transition3And4"));
        assertEquals("Transtion After Media item", mediaImageItem3,
            transition3And4.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem4,
            transition3And4.getBeforeMediaItem());
        assertEquals("Transtion Duration", 5000, transition3And4.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_LINEAR,
            transition3And4.getBehavior());

        final MediaVideoItem mediaVideoItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                videoItemFilename4, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem5);

        final TransitionCrossfade transition4And5 =
            mVideoEditorHelper.createTCrossFade("transition4And5", mediaVideoItem4,
                mediaVideoItem5, 8000, Transition.BEHAVIOR_MIDDLE_FAST);
        mVideoEditor.addTransition(transition4And5);

        assertTrue("Transition ID",
            transition4And5.getId().equals("transition4And5"));
        assertEquals("Transtion After Media item", mediaVideoItem4,
            transition4And5.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem5,
            transition4And5.getBeforeMediaItem());
        assertEquals("Transtion Duration", 8000, transition4And5.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_MIDDLE_FAST,
            transition4And5.getBehavior());

        final MediaVideoItem mediaVideoItem6 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m6",
                videoItemFilename5, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem6.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaVideoItem6);

        final TransitionCrossfade transition5And6 =
            mVideoEditorHelper.createTCrossFade("transition5And6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_SLOW);
        mVideoEditor.addTransition(transition5And6);

        assertTrue("Transition ID",
            transition5And6.getId().equals("transition5And6"));
        assertEquals("Transtion After Media item", mediaVideoItem5,
            transition5And6.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem6,
            transition5And6.getBeforeMediaItem());
        assertEquals("Transtion Duration", 2000, transition5And6.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_MIDDLE_SLOW,
            transition5And6.getBehavior());

        flagForException = false;
        try {
            mVideoEditorHelper.createTCrossFade("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_SPEED_UP - 1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition FadeBlack with Invalid behavior", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createTCrossFade("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_FAST + 1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition FadeBlack with Invalid behavior", flagForException);
    }

    /**
     * To test Transition : Sliding with all behavior
     * SPEED_UP/SPEED_DOWN/LINEAR/MIDDLE_SLOW/MIDDLE_FAST and Direction =
     * DIRECTION_RIGHT_OUT_LEFT_IN
     * ,DIRECTION_LEFT_OUT_RIGHT_IN,DIRECTION_TOP_OUT_BOTTOM_IN
     * ,DIRECTION_BOTTOM_OUT_TOP_IN
     */

    // TODO : remove TC_API_039
    @LargeTest
    public void testTransitionSliding() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String videoItemFilename2 = INPUT_FILE_PATH +
            "H263_profile0_176x144_15fps_128kbps_1_35.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH +
            "IMG_1600x1200.jpg";
        final String videoItemFilename3 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final String videoItemFilename4 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_256kbps_0_30.mp4";
        final String videoItemFilename5 = INPUT_FILE_PATH +
            "H263_profile0_176x144_10fps_96kbps_0_25.3gp";
        boolean flagForException = false;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem2.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final TransitionSliding transition1And2 =
            mVideoEditorHelper.createTSliding("transition1And2", mediaVideoItem1,
                mediaVideoItem2, 3000, Transition.BEHAVIOR_SPEED_UP,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN);
        mVideoEditor.addTransition(transition1And2);

        assertTrue("Transition ID",
            transition1And2.getId().equals("transition1And2"));
        assertEquals("Transtion After Media item", mediaVideoItem1,
            transition1And2.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem2,
            transition1And2.getBeforeMediaItem());
        assertEquals("Transtion Duration", 3000, transition1And2.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_UP,
            transition1And2.getBehavior());
        assertEquals("Transition Sliding",
            TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN,
            transition1And2.getDirection());

        final MediaImageItem mediaImageItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                imageItemFilename1, 15000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem3);

        final TransitionSliding transition2And3 =
            mVideoEditorHelper.createTSliding("transition2And3",
                mediaVideoItem2, mediaImageItem3, 1000,
                Transition.BEHAVIOR_SPEED_DOWN,
                TransitionSliding.DIRECTION_LEFT_OUT_RIGHT_IN);
        mVideoEditor.addTransition(transition2And3);

        assertTrue("Transition ID",
            transition2And3.getId().equals("transition2And3"));
        assertEquals("Transtion After Media item", mediaVideoItem2,
            transition2And3.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaImageItem3,
            transition2And3.getBeforeMediaItem());
        assertEquals("Transtion Duration", 1000, transition2And3.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_DOWN,
            transition2And3.getBehavior());
        assertEquals("Transition Sliding",
            TransitionSliding.DIRECTION_LEFT_OUT_RIGHT_IN,
            transition2And3.getDirection());

        final MediaVideoItem mediaVideoItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                videoItemFilename3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem4.setExtractBoundaries(0, 18000);
        mVideoEditor.addMediaItem(mediaVideoItem4);

        final TransitionSliding transition3And4 =
            mVideoEditorHelper.createTSliding("transition3And4", mediaImageItem3,
                mediaVideoItem4, 5000, Transition.BEHAVIOR_LINEAR,
                TransitionSliding.DIRECTION_TOP_OUT_BOTTOM_IN);
        mVideoEditor.addTransition(transition3And4);

        assertTrue("Transition ID",
            transition3And4.getId().equals("transition3And4"));
        assertEquals("Transtion After Media item", mediaImageItem3,
            transition3And4.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem4,
            transition3And4.getBeforeMediaItem());
        assertEquals("Transtion Duration", 5000, transition3And4.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_LINEAR,
            transition3And4.getBehavior());
        assertEquals("Transition Sliding",
            TransitionSliding.DIRECTION_TOP_OUT_BOTTOM_IN,
            transition3And4.getDirection());

        final MediaVideoItem mediaVideoItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                videoItemFilename4, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem5);

        final TransitionSliding transition4And5 =
            mVideoEditorHelper.createTSliding("transition4And5", mediaVideoItem4,
                mediaVideoItem5, 8000, Transition.BEHAVIOR_MIDDLE_FAST,
                TransitionSliding.DIRECTION_BOTTOM_OUT_TOP_IN);
        mVideoEditor.addTransition(transition4And5);

        assertTrue("Transition ID",
            transition4And5.getId().equals("transition4And5"));
        assertEquals("Transtion After Media item", mediaVideoItem4,
            transition4And5.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem5,
            transition4And5.getBeforeMediaItem());
        assertEquals("Transtion Duration", 8000, transition4And5.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_MIDDLE_FAST,
            transition4And5.getBehavior());
        assertEquals("Transition Sliding",
            TransitionSliding.DIRECTION_BOTTOM_OUT_TOP_IN,
            transition4And5.getDirection());

        final MediaVideoItem mediaVideoItem6 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m6",
                videoItemFilename5, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem6.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaVideoItem6);

        final TransitionSliding transition5And6 =
            mVideoEditorHelper.createTSliding("transition5And6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_SLOW,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN);
        mVideoEditor.addTransition(transition5And6);

        assertTrue("Transition ID",
            transition5And6.getId().equals("transition5And6"));
        assertEquals("Transtion After Media item", mediaVideoItem5,
            transition5And6.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem6,
            transition5And6.getBeforeMediaItem());
        assertEquals("Transtion Duration", 2000, transition5And6.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_MIDDLE_SLOW,
            transition5And6.getBehavior());
        assertEquals("Transition Sliding",
            TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN,
            transition5And6.getDirection());

        flagForException = false;
        try {
            mVideoEditorHelper.createTSliding("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_SLOW,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN - 1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition Sliding with Invalid Direction", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createTSliding("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_FAST + 1,
                TransitionSliding.DIRECTION_BOTTOM_OUT_TOP_IN + 1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition Sliding with Invalid behavior", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createTSliding("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_SPEED_UP - 1,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition Sliding with Invalid behavior", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createTSliding("transitiond6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_FAST + 1,
                TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition Sliding with Invalid behavior", flagForException);
    }

    /**
     * To test Transition : Alpha with all behavior
     * SPEED_UP/SPEED_DOWN/LINEAR/MIDDLE_SLOW/MIDDLE_FAST
     */

    // TODO : remove TC_API_040
    @LargeTest
    public void testTransitionAlpha() throws Exception {

        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String videoItemFilename2 = INPUT_FILE_PATH +
            "H263_profile0_176x144_15fps_128kbps_1_35.3gp";
        final String imageItemFilename1 = INPUT_FILE_PATH +
            "IMG_640x480.jpg";
        final String videoItemFilename3 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final String videoItemFilename4 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_256kbps_0_30.mp4";
        final String videoItemFilename5 = INPUT_FILE_PATH +
            "H263_profile0_176x144_10fps_96kbps_0_25.3gp";
        final String maskFilename = INPUT_FILE_PATH +
            "TransitionSpiral_QVGA.jpg";
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                videoItemFilename2, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem2.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final TransitionAlpha transition1And2 =
            mVideoEditorHelper.createTAlpha("transition1And2", mediaVideoItem1,
            mediaVideoItem2, 3000, Transition.BEHAVIOR_SPEED_UP, maskFilename,
            10, false);
        mVideoEditor.addTransition(transition1And2);

        assertTrue("Transition ID",
            transition1And2.getId().equals("transition1And2"));
        assertEquals("Transtion After Media item", mediaVideoItem1,
            transition1And2.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem2,
            transition1And2.getBeforeMediaItem());
        assertEquals("Transtion Duration", 3000, transition1And2.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_UP,
            transition1And2.getBehavior());
        assertTrue("Transition maskFile",
            transition1And2.getMaskFilename().equals(maskFilename));
        assertEquals("Transition BlendingPercent", 10,
            transition1And2.getBlendingPercent());
        assertFalse("Transition Invert", transition1And2.isInvert());

        final MediaImageItem mediaImageItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                imageItemFilename1, 15000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem3);

        final TransitionAlpha transition2And3 =
            mVideoEditorHelper.createTAlpha("transition2And3", mediaVideoItem2,
                mediaImageItem3, 1000, Transition.BEHAVIOR_SPEED_DOWN,
                maskFilename, 30, false);
        mVideoEditor.addTransition(transition2And3);

        assertTrue("Transition ID",
            transition2And3.getId().equals("transition2And3"));
        assertEquals("Transtion After Media item", mediaVideoItem2,
            transition2And3.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaImageItem3,
            transition2And3.getBeforeMediaItem());
        assertEquals("Transtion Duration", 1000, transition2And3.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_SPEED_DOWN,
            transition2And3.getBehavior());
        assertTrue("Transition maskFile",
            transition2And3.getMaskFilename().equals(maskFilename));
        assertEquals("Transition BlendingPercent", 30,
            transition2And3.getBlendingPercent());
        assertFalse("Transition Invert", transition2And3.isInvert());

        final MediaVideoItem mediaVideoItem4 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                videoItemFilename3, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem4.setExtractBoundaries(0, 18000);
        mVideoEditor.addMediaItem(mediaVideoItem4);

        final TransitionAlpha transition3And4 =
            mVideoEditorHelper.createTAlpha("transition3And4", mediaImageItem3,
            mediaVideoItem4, 5000, Transition.BEHAVIOR_LINEAR, maskFilename,
            50, false);
        mVideoEditor.addTransition(transition3And4);

        assertTrue("Transition ID",
            transition3And4.getId().equals("transition3And4"));
        assertEquals("Transtion After Media item", mediaImageItem3,
            transition3And4.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem4,
            transition3And4.getBeforeMediaItem());
        assertEquals("Transtion Duration", 5000, transition3And4.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_LINEAR,
            transition3And4.getBehavior());
        assertTrue("Transition maskFile",
            transition3And4.getMaskFilename().equals(maskFilename));
        assertEquals("Transition BlendingPercent", 50,
            transition3And4.getBlendingPercent());
        assertFalse("Transition Invert", transition3And4.isInvert());

        final MediaVideoItem mediaVideoItem5 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                videoItemFilename4, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem5);

        final TransitionAlpha transition4And5 =
            mVideoEditorHelper.createTAlpha("transition4And5", mediaVideoItem4,
            mediaVideoItem5, 8000, Transition.BEHAVIOR_MIDDLE_FAST,
            maskFilename, 70, true);
        mVideoEditor.addTransition(transition4And5);

        assertTrue("Transition ID",
            transition4And5.getId().equals("transition4And5"));
        assertEquals("Transtion After Media item", mediaVideoItem4,
            transition4And5.getAfterMediaItem());
        assertEquals("Transtion Before Media item", mediaVideoItem5,
            transition4And5.getBeforeMediaItem());
        assertEquals("Transtion Duration", 8000, transition4And5.getDuration());
        assertEquals("Transtion Behavior", Transition.BEHAVIOR_MIDDLE_FAST,
            transition4And5.getBehavior());
        assertTrue("Transition maskFile",
            transition4And5.getMaskFilename().equals(maskFilename));
        assertEquals("Transition BlendingPercent", 70,
            transition4And5.getBlendingPercent());
        assertTrue("Transition Invert", transition4And5.isInvert());

        final MediaVideoItem mediaVideoItem6 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m6",
                videoItemFilename5, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem6.setExtractBoundaries(0, 20000);
        mVideoEditor.addMediaItem(mediaVideoItem6);

        try {
            mVideoEditorHelper.createTAlpha("transition5And6", mediaVideoItem5,
                mediaVideoItem6, 2000, Transition.BEHAVIOR_MIDDLE_SLOW,
                INPUT_FILE_PATH + "imDummyFile.jpg", 70,
                true);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("MaskFile is not exsisting", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createTAlpha("transition5And6", null, null, 2000,
                Transition.BEHAVIOR_MIDDLE_SLOW, maskFilename, 101, true);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Invalid Blending Percent", flagForException);

        flagForException = false;
        try {
            mVideoEditorHelper.createTAlpha("transitiond6", mediaVideoItem4,
                mediaVideoItem5, 2000, Transition.BEHAVIOR_SPEED_UP - 1,
                maskFilename, 30, false);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition FadeBlack with Invalid behavior", flagForException);
        flagForException = false;
        try {
            mVideoEditorHelper.createTAlpha("transitiond6", mediaVideoItem4,
                mediaVideoItem5, 2000, Transition.BEHAVIOR_MIDDLE_FAST + 1,
                maskFilename, 30, false);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Transition FadeBlack with Invalid behavior", flagForException);
    }

    /**
     * To test Frame Overlay for Media Video Item
     */

    // TODO : remove TC_API_041
    @LargeTest
    public void testFrameOverlayVideoItem() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H263_profile0_176x144_10fps_256kbps_0_25.3gp";
        final String overlayFile1 = INPUT_FILE_PATH +  "IMG_176x144_Overlay1.png";
        final String overlayFile2 = INPUT_FILE_PATH +  "IMG_176x144_Overlay2.png";
        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final Bitmap mBitmap1 =  mVideoEditorHelper.getBitmap(overlayFile1,
            176, 144);
        final OverlayFrame overlayFrame1 = mVideoEditorHelper.createOverlay(
            mediaVideoItem1, "overlayId1", mBitmap1, 5000, 5000);
        mediaVideoItem1.addOverlay(overlayFrame1);

        assertEquals("Overlay : Media Item", mediaVideoItem1,
            overlayFrame1.getMediaItem());
        assertTrue("Overlay Id", overlayFrame1.getId().equals("overlayId1"));
        assertEquals("Overlay Bitmap", mBitmap1, overlayFrame1.getBitmap());
        assertEquals("Overlay Start Time", 5000, overlayFrame1.getStartTime());
        assertEquals("Overlay Duration", 5000, overlayFrame1.getDuration());

        Bitmap upddateBmp = mVideoEditorHelper.getBitmap(overlayFile2, 176, 144);
        overlayFrame1.setBitmap(upddateBmp);
        assertEquals("Overlay Update Bitmap", upddateBmp, overlayFrame1.getBitmap());
        upddateBmp.recycle();
    }

    /**
     * To test Frame Overlay for Media Video Item : Set duration and Get
     * Duration
     */

    // TODO : remove TC_API_042
    @LargeTest
    public void testFrameOverlaySetAndGet() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_30fps_512Kbps_0_27.mp4";
        final String overlayFile1 = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        boolean flagForException = false;

        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
            640, 480);

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
            videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final OverlayFrame overlayFrame1 = mVideoEditorHelper.createOverlay(
            mediaVideoItem1, "overlayId1", mBitmap, 5000, 5000);
        mediaVideoItem1.addOverlay(overlayFrame1);
        overlayFrame1.setDuration(5000);

        assertEquals("Overlay Duration", 5000, overlayFrame1.getDuration());
        try {
            overlayFrame1.setDuration(mediaVideoItem1.getDuration() + 10000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay Duration > MediaVideo Item Duration",
            flagForException);

        assertEquals("Overlay Duration", 5000, overlayFrame1.getDuration());
        flagForException = false;

        try {
            overlayFrame1.setDuration(-1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay Duration = -1", flagForException);
    }

    /**
     * To test Frame Overlay for Media Video Item : Set duration and Get
     * Duration
     */

    // TODO : remove TC_API_043
    @LargeTest
    public void testFrameOverlayInvalidTime() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_1200kbps_AACLC_48khz_64kbps_m_1_17.3gp";
        final String overlayFile1 = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        boolean flagForException = false;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        try {
            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
                640, 480);
            mVideoEditorHelper.createOverlay(mediaVideoItem1, "overlayId1",
                mBitmap, 400000000, 2000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay With Invalid Start Time", flagForException);

        flagForException = false;
        try {
            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
                640, 480);
            mVideoEditorHelper.createOverlay(mediaVideoItem1, "overlayId2",
                mBitmap, -1, 2000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay With Invalid Start Time", flagForException);

        flagForException = false;
        try {
            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
            640, 480);
            mVideoEditorHelper.createOverlay(mediaVideoItem1, "overlayId3",
                mBitmap, 2000, -1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay With Invalid Start Time", flagForException);
    }

    /**
     * To test Frame Overlay for Media Image Item
     */
    // TODO : remove TC_API_045
    @LargeTest
    public void testFrameOverlayImageItem() throws Exception {
        final String imageItemFilename1 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String overlayFile1 = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        final String overlayFile2 = INPUT_FILE_PATH + "IMG_640x480_Overlay2.png";

        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                imageItemFilename1, 10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem1);

        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1, 640,
            480);
        final OverlayFrame overlayFrame1 = mVideoEditorHelper.createOverlay(
            mediaImageItem1, "overlayId1", mBitmap, 5000, 5000);
        mediaImageItem1.addOverlay(overlayFrame1);

        assertEquals("Overlay : Media Item", mediaImageItem1,
            overlayFrame1.getMediaItem());
        assertTrue("Overlay Id", overlayFrame1.getId().equals("overlayId1"));
        assertEquals("Overlay Bitmap",mBitmap ,overlayFrame1.getBitmap());
        assertEquals("Overlay Start Time", 5000, overlayFrame1.getStartTime());
        assertEquals("Overlay Duration", 5000, overlayFrame1.getDuration());
        Bitmap upddateBmp = mVideoEditorHelper.getBitmap(overlayFile2, 640, 480);

        overlayFrame1.setBitmap(upddateBmp);
        assertEquals("Overlay Update Bitmap", upddateBmp, overlayFrame1.getBitmap());
        upddateBmp.recycle();
    }

    /**
     * To test Frame Overlay for Media Image Item : Set duration and Get
     * Duration
     */

    // TODO : remove TC_API_046
    @LargeTest
    public void testFrameOverlaySetAndGetImage() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String overlayFile1 = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        boolean flagForException = false;

        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, 10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem1);

        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
            640, 480);
        final OverlayFrame overlayFrame1 = mVideoEditorHelper.createOverlay(
            mediaImageItem1, "overlayId1", mBitmap, 5000, 5000);
        mediaImageItem1.addOverlay(overlayFrame1);

        overlayFrame1.setDuration(5000);
        assertEquals("Overlay Duration", 5000, overlayFrame1.getDuration());

        try {
            overlayFrame1.setDuration(mediaImageItem1.getDuration() + 10000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay Duration > Media Item Duration", flagForException);
        assertEquals("Overlay Duration", 5000, overlayFrame1.getDuration());

        flagForException = false;
        try {
            overlayFrame1.setDuration(-1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay Duration = -1", flagForException);
    }

    /**
     * To test  Frame Overlay for  Media Image Item :Invalid StartTime and
     * Duration
     */

    // TODO : remove TC_API_047
    @LargeTest
    public void testFrameOverlayInvalidTimeImage() throws Exception {
        final String videoItemFilename1 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String overlayFile1 = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        boolean flagForException = false;

        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, 10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem1);

        try {
            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
                640, 480);
            mVideoEditorHelper.createOverlay(mediaImageItem1, "overlayId1",
                mBitmap, 400000000, 2000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay With Invalid Start Time", flagForException);

        flagForException = false;
        try {
            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
                640, 480);
            mVideoEditorHelper.createOverlay(mediaImageItem1, "overlayId2",
                mBitmap, -1, 2000);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay With Invalid Start Time", flagForException);

        flagForException = false;
        try {
            final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1,
                640, 480);
            mVideoEditorHelper.createOverlay(mediaImageItem1, "overlayId3",
                mBitmap, 2000, -1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Overlay With Invalid Start Time", flagForException);
    }

    /**
     * To Test Frame Overlay Media Image Item :JPG File
     */

    // TODO : remove TC_API_048
    @LargeTest
    public void testFrameOverlayJPGImage() throws Exception {

        final String imageItemFilename = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String overlayFile1 = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        boolean flagForException = false;
        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                imageItemFilename, 10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mVideoEditor.addMediaItem(mediaImageItem1);
        final Bitmap mBitmap =  mVideoEditorHelper.getBitmap(overlayFile1, 640,
            480);
        mVideoEditorHelper.createOverlay(mediaImageItem1, "overlayId1",
            mBitmap, 5000, 5000);
    }

    /**
     * To test Video Editor API
     *
     * @throws Exception
     */
    // TODO : remove TC_API_049
    @LargeTest
    public void testVideoEditorAPI() throws Exception {

        final String videoItemFileName1 = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final String videoItemFileName2 = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_1200kbps_AACLC_48khz_64kbps_m_1_17.3gp";
        final String videoItemFileName3 = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final String imageItemFileName1 = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final String imageItemFileName2 = INPUT_FILE_PATH + "IMG_176x144.jpg";
        final String audioFilename1 = INPUT_FILE_PATH +
            "AMRNB_8KHz_12.2Kbps_m_1_17.3gp";
        final String audioFilename2 = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        TransitionCrossfade transition2And4;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName1, renderingMode);
        mediaVideoItem1.setExtractBoundaries(0, 10000);
        mVideoEditor.addMediaItem(mediaVideoItem1);

        final MediaVideoItem mediaVideoItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2",
                videoItemFileName2, renderingMode);
        mediaVideoItem2.setExtractBoundaries(mediaVideoItem2.getDuration() / 4,
            mediaVideoItem2.getDuration() / 2);
        mVideoEditor.addMediaItem(mediaVideoItem2);

        final MediaVideoItem mediaVideoItem3 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m3",
                videoItemFileName3, renderingMode);
        mediaVideoItem3.setExtractBoundaries(mediaVideoItem3.getDuration() / 2,
            mediaVideoItem3.getDuration());
        mVideoEditor.addMediaItem(mediaVideoItem3);

        final MediaImageItem mediaImageItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m4",
                imageItemFileName1, 5000, renderingMode);

        final MediaImageItem mediaImageItem2 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m5",
                imageItemFileName2, 5000, renderingMode);

        List<MediaItem> mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 3, mediaList.size());

        mVideoEditor.insertMediaItem(mediaImageItem1, mediaVideoItem2.getId());
        mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 4, mediaList.size());
        assertEquals("Media item 1", mediaVideoItem1, mediaList.get(0));
        assertEquals("Media item 2", mediaVideoItem2, mediaList.get(1));
        assertEquals("Media item 4", mediaImageItem1, mediaList.get(2));
        assertEquals("Media item 3", mediaVideoItem3, mediaList.get(3));

        mVideoEditor.insertMediaItem(mediaImageItem2, mediaImageItem1.getId());
        mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 5, mediaList.size());
        assertEquals("Media item 1", mediaVideoItem1, mediaList.get(0));
        assertEquals("Media item 2", mediaVideoItem2, mediaList.get(1));
        assertEquals("Media item 4", mediaImageItem1, mediaList.get(2));
        assertEquals("Media item 5", mediaImageItem2, mediaList.get(3));
        assertEquals("Media item 3", mediaVideoItem3, mediaList.get(4));

        mVideoEditor.moveMediaItem(mediaVideoItem1.getId(), mediaImageItem2.getId());
        mediaList = mVideoEditor.getAllMediaItems();
        assertEquals("Media Item List Size", 5, mediaList.size());
        assertEquals("Media item 2", mediaVideoItem2, mediaList.get(0));
        assertEquals("Media item 4", mediaImageItem1, mediaList.get(1));
        assertEquals("Media item 5", mediaImageItem2, mediaList.get(2));
        assertEquals("Media item 1", mediaVideoItem1, mediaList.get(3));
        assertEquals("Media item 3", mediaVideoItem3, mediaList.get(4));

        assertEquals("Media Item 1", mediaVideoItem1,
            mVideoEditor.getMediaItem(mediaVideoItem1.getId()));

        flagForException = false;
        transition2And4 = null;
        try{
            transition2And4 = mVideoEditorHelper.createTCrossFade(
                "transition2And4", mediaVideoItem2, mediaImageItem1, 2000,
                Transition.BEHAVIOR_MIDDLE_FAST);
            mVideoEditor.addTransition(transition2And4);
        }
        catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertFalse("Transition2and4 cannot be created", flagForException);


        TransitionCrossfade transition1And3 = null;
        flagForException = false;
        try{
            transition1And3 = mVideoEditorHelper.createTCrossFade(
                "transition1And3", mediaVideoItem1, mediaVideoItem2, 5000,
                Transition.BEHAVIOR_MIDDLE_FAST);
                mVideoEditor.addTransition(transition1And3);
            }catch (IllegalArgumentException e) {
                flagForException = true;
            }
        assertTrue("Transition1and3 cannot be created", flagForException);

        List<Transition> transitionList = mVideoEditor.getAllTransitions();
        assertEquals("Transition List", 1, transitionList.size());

        assertEquals("Transition 2", transition2And4,
            mVideoEditor.getTransition(transition2And4.getId()));

        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFilename1);
        mVideoEditor.addAudioTrack(audioTrack);

        List<AudioTrack> audioList = mVideoEditor.getAllAudioTracks();
        assertEquals("Audio List", 1, audioList.size());

        final AudioTrack audioTrack1 = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack1", audioFilename2);
        flagForException = false;
        try {
            mVideoEditor.addAudioTrack(audioTrack1);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Audio Track support is 1 ", flagForException);

        flagForException = false;
        try {
            mVideoEditor.insertAudioTrack(audioTrack1,"audioTrack");
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Audio Track supports is 1 ", flagForException);

        assertEquals("Removing AudioTrack", audioTrack,
            mVideoEditor.removeAudioTrack(audioTrack.getId()));

        assertEquals("Removing transition", transition2And4,
            mVideoEditor.removeTransition(transition2And4.getId()));

        assertEquals("Removing Media Item", mediaVideoItem2,
            mVideoEditor.removeMediaItem(mediaVideoItem2.getId()));

        mVideoEditor.setAspectRatio(MediaProperties.ASPECT_RATIO_16_9);
        assertEquals("Check Aspect Ratio", MediaProperties.ASPECT_RATIO_16_9,
            mVideoEditor.getAspectRatio());

        long storyBoardDuration = mediaVideoItem1.getTimelineDuration()
            + mediaVideoItem3.getTimelineDuration()
            + mediaImageItem1.getDuration()
            + mediaImageItem2.getDuration();
        assertEquals("Story Board Duration", storyBoardDuration,
            mVideoEditor.getDuration());
    }

    /**
     * To add Audio Track Greater than MediaItem Duration
     *
     * @throws Exception
     */
    // TODO : remove TC_API_050
    @LargeTest
    public void testVideoLessThanAudio() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final String audioTrackFilename = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFileName1, renderingMode);
        mVideoEditor.addMediaItem(mediaVideoItem1);
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrackId", audioTrackFilename);
        mVideoEditor.addAudioTrack(audioTrack);
        assertEquals("Storyboard = mediaItem Duration",
            mediaVideoItem1.getDuration(), mVideoEditor.getDuration());
        assertTrue("Audio Duration > mediaItem Duration",
            (audioTrack.getDuration() > mediaVideoItem1.getDuration() ?
            true : false));
    }

    /**
     * To test Video Editor API with 1080 P
     *
     * @throws Exception
     */
    // TODO : remove TC_API_051
    @LargeTest
    public void testVideoContentHD() throws Exception {
        final String videoItemFileName1 = INPUT_FILE_PATH
            + "H264_BP_1920x1080_30fps_1200Kbps_1_10.mp4";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem1;
        // 1080p resolution is supported on some devices
        // but not on other devices.
        // So this test case is not generic and
        // hence we always assert true
        boolean flagForException = true;
        try {
            mediaVideoItem1 = mVideoEditorHelper.createMediaItem(mVideoEditor,
                "m1", videoItemFileName1, renderingMode);
        } catch (IllegalArgumentException e) {
        }
        assertTrue("VideoContent 1920x1080", flagForException);
    }


    /**
     * To test: Remove audio track
     *
     * @throws Exception
     */
    // TODO : remove TC_API_052
    @LargeTest
    public void testRemoveAudioTrack() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        boolean flagForException = false;

        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack1", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);

        assertEquals("Audio Track Item Duration", audioTrack.getDuration(),
            audioTrack.getTimelineDuration());
        assertTrue("Audio Track ID", audioTrack.getId().equals("audioTrack1"));
        assertNotNull("Remove Audio Track",
            mVideoEditor.removeAudioTrack("audioTrack1"));
        try{
            mVideoEditor.removeAudioTrack("audioTrack1");
        }catch (IllegalArgumentException e){
            flagForException = true;
        }
        assertTrue("Remove Audio Track not possible", flagForException);
    }

      /**
     * To test: Disable ducking
     *
     * @throws Exception
     */
    // TODO : remove TC_API_053
    @LargeTest
    public void testAudioDuckingDisable() throws Exception {
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);

        audioTrack.disableDucking();
        assertFalse("Audio Track Ducking is Disabled",
            audioTrack.isDuckingEnabled());
    }


    // TODO : remove TC_API_054
    /** This test case is added with Test case ID TC_API_010 */

      /**
     * To test: Need a basic test case for the get value for TransitionAlpha
     *  ( ie. getBlendingPercent, getMaskFilename, isInvert)
     *
     * @throws Exception
     */
    // TODO : remove TC_API_055
    @LargeTest
    public void testTransitionAlphaBasic() throws Exception {

        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String maskFilename = INPUT_FILE_PATH + "IMG_640x480_Overlay1.png";
        boolean flagForException = false;

        final MediaVideoItem mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaVideoItem1.setExtractBoundaries(0, 15000);

        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2", maskFilename,
                10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaImageItem.setDuration(15000);

        mVideoEditor.addMediaItem(mediaVideoItem1);
        mVideoEditor.addMediaItem(mediaImageItem);
        final TransitionAlpha transition1And2 =
            mVideoEditorHelper.createTAlpha("transition1And2", mediaVideoItem1,
                mediaImageItem, 3000, Transition.BEHAVIOR_SPEED_UP,
                maskFilename, 10, false);
        mVideoEditor.addTransition(transition1And2);
        assertTrue("Transition maskFile",
            transition1And2.getMaskFilename().equals(maskFilename));
        assertEquals("Transition BlendingPercent", 10,
            transition1And2.getBlendingPercent());
        assertFalse("Transition Invert", transition1And2.isInvert());
    }

    /**
     * To test: NULL arguments to the Video Editor APIs
     *
     * @throws Exception
     */
    // TODO : remove TC_API_056
    @LargeTest
    public void testNullAPIs() throws Exception {

        final String videoItemFilename1 = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final String maskFilename = INPUT_FILE_PATH +
            "IMG_640x480_Overlay1.png";
        final String audioFileName = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        boolean flagForException = false;

        try {
            mVideoEditor.addAudioTrack(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Video Editor with null Audio Track", flagForException);
        flagForException = false;
        try {
            mVideoEditor.addMediaItem(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Video Editor with NULL Image Item ", flagForException);
        flagForException = false;
        try {
            mVideoEditor.addMediaItem(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Video Editor with NULL Video Item ", flagForException);

        MediaVideoItem mediaVideoItem1 = null;
        try {
            mediaVideoItem1 =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename1, MediaItem.RENDERING_MODE_BLACK_BORDER);
        } catch (IllegalArgumentException e) {
            assertTrue("Cannot Create Video Item", false);
        }
        mediaVideoItem1.setExtractBoundaries(0, 15000);
        mVideoEditor.addMediaItem(mediaVideoItem1);
        flagForException = false;
        try {
            mediaVideoItem1.addEffect(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Video with null effect ", flagForException);
        flagForException = false;
        try {
            mediaVideoItem1.addOverlay(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Video with null overlay ", flagForException);

        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m2", maskFilename,
                10000, MediaItem.RENDERING_MODE_BLACK_BORDER);
        mediaImageItem.setDuration(15000);
        mVideoEditor.addMediaItem(mediaImageItem);
        flagForException = false;
        try {
            mediaImageItem.addEffect(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Image with null effect ", flagForException);
        flagForException = false;
        try {
            mediaImageItem.addOverlay(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Image with null overlay ", flagForException);

        final AudioTrack audioTrack = mVideoEditorHelper.createAudio(
            mVideoEditor, "audioTrack", audioFileName);
        mVideoEditor.addAudioTrack(audioTrack);

        flagForException = false;
        try {
            mVideoEditor.addTransition(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Added null transition ", flagForException);

        flagForException = false;
        try {
            mVideoEditor.addTransition(null);
        } catch(IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Added null transition ", flagForException);

    }
}
