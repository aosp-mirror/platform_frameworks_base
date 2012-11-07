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

import android.media.videoeditor.AudioTrack;
import android.media.videoeditor.MediaImageItem;
import android.media.videoeditor.MediaItem;
import android.media.videoeditor.MediaProperties;
import android.media.videoeditor.MediaVideoItem;
import android.media.videoeditor.VideoEditor;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.VideoEditorHelper;
import com.android.mediaframeworktest.MediaProfileReader;

public class MediaPropertiesTest extends
    ActivityInstrumentationTestCase<MediaFrameworkTest> {
    private final String TAG = "MediaPropertiesTest";

    private final String PROJECT_LOCATION = VideoEditorHelper.PROJECT_LOCATION_COMMON;

    private final String INPUT_FILE_PATH = VideoEditorHelper.INPUT_FILE_PATH_COMMON;

    private VideoEditor mVideoEditor;

    private VideoEditorHelper mVideoEditorHelper;

    public MediaPropertiesTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    @Override
    protected void setUp() throws Exception {
        // setup for each test case.
        super.setUp();
        mVideoEditorHelper = new VideoEditorHelper();
        // Create a random String which will be used as project path,
        // where all project related files will be stored.
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

    protected void validateVideoProperties(int aspectRatio, int fileType,
        int videoCodecType, int duration, int videoBitrate, int fps,
        int videoProfile, int videoLevel, int width, int height, int audioCodecType,
        int audioSamplingFrequency, int audioChannel, int audioBitrate,
        MediaVideoItem mvi) throws Exception {
        assertEquals("Aspect Ratio Mismatch", aspectRatio, mvi.getAspectRatio());
        assertEquals("File Type Mismatch", fileType, mvi.getFileType());
        assertEquals("VideoCodec Mismatch", videoCodecType, mvi.getVideoType());

        assertTrue("Video duration Mismatch", mVideoEditorHelper.checkRange (
            duration, mvi.getDuration(), 10));
        assertEquals("Video Profile " + mvi.getVideoProfile(), videoProfile,
            mvi.getVideoProfile());
        assertEquals("Video Level " + mvi.getVideoLevel(), videoLevel,
            mvi.getVideoLevel());
        assertEquals("Video height " + mvi.getHeight(), height, mvi.getHeight());
        assertEquals("Video width " + mvi.getWidth(), width, mvi.getWidth());
        /** Check FPS with 10% range */
        assertTrue("fps Mismatch" + mvi.getFps(),
            mVideoEditorHelper.checkRange(fps, mvi.getFps(), 10));

        assertEquals("AudioType Mismatch ", audioCodecType, mvi.getAudioType());
        assertEquals("Audio Sampling " + mvi.getAudioSamplingFrequency(),
            audioSamplingFrequency, mvi.getAudioSamplingFrequency());
        // PV SW AAC codec always returns number of channels as Stereo.
        // So we do not assert for number of audio channels for AAC_LC
        if ( audioCodecType != MediaProperties.ACODEC_AAC_LC ) {
            assertEquals("Audio Channels " + mvi.getAudioChannels(), audioChannel,
                mvi.getAudioChannels());
        }
    }

    protected void validateAudioProperties(int audioCodecType, int duration,
        int audioSamplingFrequency, int audioChannel, int audioBitrate,
        AudioTrack aT) throws Exception {
        assertEquals("AudioType Mismatch ", audioCodecType, aT.getAudioType());
        assertTrue("Video duration Mismatch", mVideoEditorHelper.checkRange (
            duration, aT.getDuration(), 10));
        assertEquals("Audio Sampling " + aT.getAudioSamplingFrequency(),
            audioSamplingFrequency, aT.getAudioSamplingFrequency());
        // PV SW AAC codec always returns number of channels as Stereo.
        // So we do not assert for number of audio channels for AAC_LC
        if ( audioCodecType != MediaProperties.ACODEC_AAC_LC ) {
            assertEquals("Audio Channels " + aT.getAudioChannels(), audioChannel,
                aT.getAudioChannels());
        }
    }

    protected void validateImageProperties(int aspectRatio, int fileType,
        int width, int height, MediaImageItem mii)
        throws Exception {
        assertEquals("Aspect Ratio Mismatch", aspectRatio, mii.getAspectRatio());
        assertEquals("File Type Mismatch", fileType, mii.getFileType());
        assertEquals("Image height " + mii.getHeight(), height, mii.getHeight());
        assertEquals("Image width " + mii.getWidth(), width, mii.getWidth());
    }


    /**
     *To test Media Properties for file MPEG4 854 x 480
     */
    @LargeTest
    public void testPropertiesMPEG4854_480() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_854x480_15fps_256kbps_AACLC_16khz_48kbps_s_0_26.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_16_9;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_MPEG4;
        final int duration = 26933;
        final int videoBitrate = 319000;
        final int audioBitrate = 48000;
        final int fps = 15;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 16000;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.MPEG4Profile.MPEG4ProfileSimple;
        final int videoLevel = MediaProperties.MPEG4Level.MPEG4Level1;
        final int width = 854;
        final int height = MediaProperties.HEIGHT_480;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename,
            MediaItem.RENDERING_MODE_BLACK_BORDER);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }


    /**
     *To test Media Properties for file MPEG4 WVGA
     */
    @LargeTest
    public void testPropertiesMPEGWVGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_800x480_515kbps_15fps_AMR_NB_8KHz_12.2kbps_m_0_26.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_5_3;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_MPEG4;
        final int duration = 26933;
        final int videoBitrate = 384000;
        final int audioBitrate = 12800;
        final int fps = 15;
        final int audioCodecType = MediaProperties.ACODEC_AMRNB;
        final int audioSamplingFrequency = 8000;
        final int audioChannel = 1;
        final int videoProfile = MediaProperties.MPEG4Profile.MPEG4ProfileSimple;
        final int videoLevel = MediaProperties.MPEG4Level.MPEG4Level1;
        final int width = 800;
        final int height = MediaProperties.HEIGHT_480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test media properties for MPEG4 720x480 (NTSC) + AAC file.
     */
    @LargeTest
    public void testPropertiesMPEGNTSC() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_161kbps_s_0_26.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_3_2;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_MPEG4;
        final int duration = 26866;
        final int videoBitrate = 403000;
        final int audioBitrate = 160000;
        final int fps = 30;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 48000;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.MPEG4Profile.MPEG4ProfileSimple;
        final int videoLevel = MediaProperties.MPEG4Level.MPEG4Level1;
        final int width = 720;
        final int height = MediaProperties.HEIGHT_480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test Media Properties for file MPEG4 VGA
     */
    @LargeTest
    public void testPropertiesMPEGVGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_4_3;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_MPEG4;
        final int duration = 26933;
        final int videoBitrate = 533000;
        final int audioBitrate = 128000;
        final int fps = 15;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 48000;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.MPEG4Profile.MPEG4ProfileSimple;
        final int videoLevel = MediaProperties.MPEG4Level.MPEG4Level1;
        final int width = 640;
        final int height = MediaProperties.HEIGHT_480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test Media Properties for file MPEG4 QCIF
     */
    @LargeTest
    public void testPropertiesMPEGQCIF() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_12fps_92kbps_AMRNB_8KHz_12.2kbps_m_0_27.3gp";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_11_9;
        final int fileType = MediaProperties.FILE_3GP;
        final int videoCodecType = MediaProperties.VCODEC_MPEG4;
        final int duration = 27000;
        final int videoBitrate = 384000;
        final int audioBitrate = 12200;
        final int fps = 12;
        final int audioCodecType = MediaProperties.ACODEC_AMRNB;
        final int audioSamplingFrequency = 8000;
        final int audioChannel = 1;
        final int videoProfile = MediaProperties.MPEG4Profile.MPEG4ProfileSimple;
        final int videoLevel = MediaProperties.MPEG4Level.MPEG4Level1;
        final int width = 176;
        final int height = MediaProperties.HEIGHT_144;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To To test media properties for H263 176x144 (QCIF) + AAC (mono) file.
     */
    @LargeTest
    public void testPropertiesH263QCIF() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_16kHz_32kbps_m_0_26.3gp";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_11_9;
        final int fileType = MediaProperties.FILE_3GP;
        final int videoCodecType = MediaProperties.VCODEC_H263;
        final int duration = 26933;
        final int videoBitrate = 384000;
        final int audioBitrate = 64000;
        final int fps = 15;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 16000;
        final int audioChannel = 1;
        final int videoProfile = MediaProperties.H263Profile.H263ProfileBaseline;
        final int videoLevel = MediaProperties.H263Level.H263Level10;
        final int width = 176;
        final int height = MediaProperties.HEIGHT_144;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test Media Properties for file H264 VGA
     */
    @LargeTest
    public void testPropertiesH264VGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H264_BP_640x480_15fps_1200Kbps_AACLC_48KHz_64kps_m_0_27.3gp";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_4_3;
        final int fileType = MediaProperties.FILE_3GP;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int duration = 77600;
        final int videoBitrate = 745000;
        final int audioBitrate = 64000;
        final int fps = 15;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 48000;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int width = 640;
        final int height = MediaProperties.HEIGHT_480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test Media Properties for file H264 NTSC
     */
    @LargeTest
    public void testPropertiesH264NTSC() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H264_BP_720x480_25fps_256kbps_AMRNB_8khz_12.2kbps_m_0_26.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_3_2;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int duration = 26880;
        final int videoBitrate = 244000;
        final int audioBitrate = 12200;
        final int fps = 25;
        final int audioCodecType = MediaProperties.ACODEC_AMRNB;
        final int audioSamplingFrequency = 8000;
        final int audioChannel = 1;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int width = 720;
        final int height = MediaProperties.HEIGHT_480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test media properties for H264 800x480 (WVGA) + AAC file.
     */
    @LargeTest
    public void testPropertiesH264WVGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
              "H264_BP_800x480_15fps_512kbps_AACLC_24KHz_38Kbps_s_1_17.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_5_3;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int duration = 77466;
        final int videoBitrate = 528000;
        final int audioBitrate = 38000;
        final int fps = 15;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 24000;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int width = 800;
        final int height = MediaProperties.HEIGHT_480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test Media Properties for file H264 HD1280
     */
    @LargeTest
    public void testPropertiesH264HD1280() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H264_BP_1280x720_15fps_512kbps_AACLC_16khz_48kbps_s_1_17.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_16_9;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int duration = 77600;
        final int videoBitrate = 606000;
        final int audioBitrate = 48000;
        final int fps = 15;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 16000;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int width = 1280;
        final int height = MediaProperties.HEIGHT_720;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test media properties for H264 1080x720 + AAC file
     */
    @LargeTest
    public void testPropertiesH264HD1080WithAudio() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H264_BP_1080x720_30fps_12Mbps_AACLC_44.1khz_64kbps_s_1_17.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_3_2;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int duration = 77500;
        final int videoBitrate = 1190000;
        final int audioBitrate = 64000;
        final int fps = 10;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 44100;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int width = 1080;
        final int height = MediaProperties.HEIGHT_720;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test Media Properties for file WMV - Unsupported type
     */
    @LargeTest
    public void testPropertiesWMVFile() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "WMV_V7_640x480_15fps_512Kbps_wma_V9_44khz_48Kbps_s_1_30.wmv";
        boolean flagForException = false;
        if (MediaProfileReader.getWMVEnable() == false) {
            flagForException = true;
        } else {
            try {
                new MediaVideoItem(mVideoEditor, "m1", videoItemFilename,
                    MediaItem.RENDERING_MODE_BLACK_BORDER);
            } catch (IllegalArgumentException e) {
                flagForException = true;
            } catch (IOException e) {
                flagForException = true;
            }
        }
        assertTrue("Media Properties for a WMV File -- Unsupported file type",
            flagForException);
    }

    /**
     *To test media properties for H.264 Main/Advanced profile.
     */
    @LargeTest
    public void testPropertiesH264MainLineProfile() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H264_MP_960x720_25fps_800kbps_AACLC_48Khz_192Kbps_s_1_17.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_4_3;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int fileType = MediaProperties.FILE_MP4;
        final int duration = 77500;
        final int videoBitrate = 800000;
        final int audioBitrate = 192000;
        final int fps = 25;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 48000;
        final int audioChannel = 2;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileMain;
        final int videoLevel = MediaProperties.H264Level.H264Level31;
        final int width = 960;
        final int height = MediaProperties.HEIGHT_720;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);

    }

    /**
     *To test Media Properties for non existing file.
     */
    @LargeTest
    public void testPropertiesForNonExsitingFile() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH + "abc.3gp";
        boolean flagForException = false;

        try {
            new MediaVideoItem(mVideoEditor, "m1", videoItemFilename,
                MediaItem.RENDERING_MODE_BLACK_BORDER);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        } catch (IOException e) {
            flagForException = true;
        }
        assertTrue("Media Properties for non exsisting file", flagForException);
     }

    /**
     *To test Media Properties for file H264 HD1080
     */
    @LargeTest
    public void testPropertiesH264HD1080WithoutAudio() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final int aspectRatio = MediaProperties.ASPECT_RATIO_3_2;
        final int fileType = MediaProperties.FILE_MP4;
        final int videoCodecType = MediaProperties.VCODEC_H264;
        final int duration = 77366;
        final int videoBitrate = 859000;
        final int audioBitrate = 0;
        final int fps = 30;
        final int audioCodecType = -1;
        final int audioSamplingFrequency = 0;
        final int audioChannel = 0;
        final int videoProfile = MediaProperties.H264Profile.H264ProfileBaseline;
        final int videoLevel = MediaProperties.H264Level.H264Level13;
        final int width = 1080;
        final int height = MediaProperties.HEIGHT_720;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mvi = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", videoItemFilename, renderingMode);

        validateVideoProperties(aspectRatio, fileType, videoCodecType, duration,
            videoBitrate, fps, videoProfile, videoLevel, width, height, audioCodecType,
            audioSamplingFrequency, audioChannel, audioBitrate, mvi);
    }

    /**
     *To test Media Properties for Image file of JPEG Type
     */
    @LargeTest
    public void testPropertiesVGAImage() throws Exception {
        final String imageItemFilename = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int imageItemDuration = 10000;
        final int aspectRatio = MediaProperties.ASPECT_RATIO_4_3;
        final int fileType = MediaProperties.FILE_JPEG;
        final int width = 640;
        final int height = MediaProperties.HEIGHT_480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaImageItem mii = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", imageItemFilename, imageItemDuration,
            renderingMode);
        validateImageProperties(aspectRatio, fileType, width, height, mii);
    }

    /**
     *To test Media Properties for Image file of PNG Type
     */
    @LargeTest
    public void testPropertiesPNG() throws Exception {
        final String imageItemFilename = INPUT_FILE_PATH + "IMG_640x480.png";
        final int imageItemDuration = 10000;
        final int aspectRatio = MediaProperties.ASPECT_RATIO_4_3;
        final int fileType = MediaProperties.FILE_PNG;
        final int width = 640;
        final int height = 480;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaImageItem mii = mVideoEditorHelper.createMediaItem
            (mVideoEditor, "m1", imageItemFilename, imageItemDuration,
            renderingMode);
        validateImageProperties(aspectRatio, fileType, width, height, mii);
    }

    /**
     *To test Media Properties for file GIF - Unsupported type
     */
    @LargeTest
    public void testPropertiesGIFFile() throws Exception {

        final String imageItemFilename = INPUT_FILE_PATH + "IMG_640x480.gif";
        final int imageItemDuration = 10000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        try {
            new MediaImageItem(mVideoEditor, "m1", imageItemFilename,
                imageItemDuration, renderingMode);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Properties for a GIF File -- Unsupported file type",
            flagForException);
    }

    /**
     *To test Media Properties for file Text file named as 3GP
     */
    @LargeTest
    public void testPropertiesofDirtyFile() throws Exception {

        final String videoItemFilename = INPUT_FILE_PATH +
            "Text_FileRenamedTo3gp.3gp";
        boolean flagForException = false;

        try {
            new MediaVideoItem(mVideoEditor, "m1", videoItemFilename,
                MediaItem.RENDERING_MODE_BLACK_BORDER);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Properties for a Dirty  File ",
            flagForException);
    }

    /**
     *To test Media Properties for file name as NULL
     */
    @LargeTest
    public void testPropertieNULLFile() throws Exception {
        final String videoItemFilename = null;
        boolean flagForException = false;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        try {
            new MediaVideoItem(mVideoEditor, "m1", videoItemFilename,
                renderingMode);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Properties for NULL  File ",
            flagForException);
    }

    /**
     *To test Media Properties for file which is of type MPEG2
     */
    @LargeTest
    public void testPropertiesMPEG2File() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "MPEG2_640x480_30fps_192kbps_1_5.mp4";
        boolean flagForException = false;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        try {
            new MediaVideoItem(mVideoEditor, "m1", videoItemFilename,
                renderingMode);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Media Properties for a MPEG2 File --Unsupported file type",
            flagForException);
    }

    /**
     *To test Media Properties for file without Video only Audio
     */
    @LargeTest
    public void testProperties3GPWithoutVideoMediaItem() throws Exception {
        final String audioFilename = INPUT_FILE_PATH +
            "AACLC_48KHz_256Kbps_s_1_17.3gp";
        boolean flagForException = false;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        try {
            new MediaVideoItem(mVideoEditor, "m1", audioFilename,
                renderingMode);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Exception in Creaing Media Video item object without video",
            flagForException);
    }

    /**
     *To test media properties for Audio Track file. (No Video, AAC Audio)
     */
    @LargeTest
    public void testProperties3GPWithoutVideoAudioTrack() throws Exception {

        final String audioFilename = INPUT_FILE_PATH +
            "AACLC_44.1kHz_256kbps_s_1_17.mp4";
        final int duration = 77554;
        final int audioBitrate = 384000;
        final int audioCodecType = MediaProperties.ACODEC_AAC_LC;
        final int audioSamplingFrequency = 44100;
        final int audioChannel = 2;

        final AudioTrack audioTrack = mVideoEditorHelper.createAudio
            (mVideoEditor, "a1", audioFilename);

        validateAudioProperties(audioCodecType, duration, audioSamplingFrequency,
            audioChannel, audioBitrate, audioTrack);
    }

        /**
     *To test media properties for Audio Track file. MP3 file
     */
    @LargeTest
    public void testPropertiesMP3AudioTrack() throws Exception {

        final String audioFilename = INPUT_FILE_PATH +
            "MP3_48KHz_128kbps_s_1_17.mp3";
        final int duration = 77640;
        final int audioBitrate = 128000;
        final int audioCodecType = MediaProperties.ACODEC_MP3;
        final int audioSamplingFrequency = 48000;
        final int audioChannel = 2;

        final AudioTrack audioTrack = mVideoEditorHelper.createAudio
            (mVideoEditor, "a1", audioFilename);

        validateAudioProperties(audioCodecType, duration, audioSamplingFrequency,
            audioChannel, audioBitrate, audioTrack);
    }
}
