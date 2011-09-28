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

import android.graphics.Bitmap;
import android.media.videoeditor.MediaImageItem;
import android.media.videoeditor.MediaItem;
import android.media.videoeditor.MediaVideoItem;
import android.media.videoeditor.VideoEditor;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.VideoEditorHelper;

public class MediaItemThumbnailTest extends
    ActivityInstrumentationTestCase<MediaFrameworkTest> {
    private final String TAG = "MediaItemThumbailTest";

    private final String PROJECT_LOCATION = VideoEditorHelper.PROJECT_LOCATION_COMMON;

    private final String INPUT_FILE_PATH = VideoEditorHelper.INPUT_FILE_PATH_COMMON;

    private VideoEditor mVideoEditor;

    private VideoEditorHelper mVideoEditorHelper;

    public MediaItemThumbnailTest() {
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

    protected void validateThumbnail(Bitmap thumbNailBmp, int outWidth,
        int outHeight) throws Exception {
        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertEquals("Thumbnail Height", outHeight, thumbNailBmp.getHeight());
        assertEquals("Thumbnail Width", outWidth, thumbNailBmp.getWidth());
        thumbNailBmp.recycle();
    }

    // -----------------------------------------------------------------
    // THUMBNAIL
    // -----------------------------------------------------------------
    /**
     * To test thumbnail / frame extraction on H.263 QCIF.
     */
    // TODO : TC_TN_001
    @LargeTest
    public void testThumbnailForH263QCIF() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_0_26.3gp";
        final int atTime = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final int outWidth = (mediaVideoItem.getWidth() / 2);
        final int outHeight = mediaVideoItem.getHeight();

        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on MPEG4 VGA .
     */
    // TODO : TC_TN_002
    @LargeTest
    public void testThumbnailForMPEG4VGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_30fps_512Kbps_0_23.3gp";
        final int atTime = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = (mediaVideoItem.getWidth() / 2);
        final int outHeight = mediaVideoItem.getHeight();
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on MPEG4 NTSC.
     */
    // TODO : TC_TN_003
    @LargeTest
    public void testThumbnailForMPEG4NTSC() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4";
        final int atTime = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = mediaVideoItem.getWidth() / 2;
        final int outHeight = mediaVideoItem.getHeight() / 2;
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on MPEG4 WVGA.
     */
    // TODO : TC_TN_004
    @LargeTest
    public void testThumbnailForMPEG4WVGA() throws Exception {

        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_800x480_515kbps_15fps_AMR_NB_8KHz_12.2kbps_m_0_26.mp4";
        final int atTime = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = mediaVideoItem.getWidth() * 2;
        final int outHeight = mediaVideoItem.getHeight();
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on MPEG4 QCIF.
     */
    // TODO : TC_TN_005
    @LargeTest
    public void testThumbnailForMPEG4QCIF() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int atTime = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = mediaVideoItem.getWidth();
        final int outHeight = mediaVideoItem.getHeight() * 2;
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on H264 QCIF.
     */
    // TODO : TC_TN_006
    @LargeTest
    public void testThumbnailForH264QCIF() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H264_BP_176x144_15fps_144kbps_AMRNB_8kHz_12.2kbps_m_1_17.3gp";

        final int atTime = 0;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = mediaVideoItem.getWidth() * 2;
        final int outHeight = mediaVideoItem.getHeight() * 2;
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on H264 VGA.
     */
    // TODO : TC_TN_007
    @LargeTest
    public void testThumbnailForH264VGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_192kbps_1_5.mp4";
        final int outWidth = 32;
        final int outHeight = 32;
        final int atTime = 0;

        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }
    /**
     * To test thumbnail / frame extraction on H264 WVGA.
     */
    // TODO : TC_TN_008
    @LargeTest
    public void testThumbnailForH264WVGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_AACLC_24KHz_38Kbps_s_1_17.mp4";
        final int outWidth = 64;
        final int outHeight = 64;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final long atTime = mediaVideoItem.getDuration() / 2;
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on H264 854x480.
     */
    // TODO : TC_TN_009
    @LargeTest
    public void testThumbnailForH264854_480() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_854x480_15fps_256kbps_AACLC_16khz_48kbps_s_0_26.mp4";
        final int outWidth = 128;
        final int outHeight = 128;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        MediaVideoItem mediaVideoItem = null;
        mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final long atTime = mediaVideoItem.getDuration() - 1000;
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on H264 960x720.
     */
    // TODO : TC_TN_010
    @LargeTest
    public void testThumbnailForH264HD960() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_960x720_25fps_800kbps_AACLC_48Khz_192Kbps_s_1_17.mp4";
        final int outWidth = 75;
        final int outHeight = 75;

        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final long atTime = mediaVideoItem.getDuration() - 1000;
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on H264 1080x720 .
     */
    // TODO : TC_TN_011
    @LargeTest
    public void testThumbnailForH264HD1080() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_1080x720_30fps_800kbps_1_17.mp4";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = mediaVideoItem.getWidth() / 2;
        final int outHeight = mediaVideoItem.getHeight() / 2;
        final long atTime = mediaVideoItem.getDuration() / 4;
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * Check the thumbnail / frame extraction precision at 0,100 and 200 ms
     */
    // TODO : TC_TN_012
    @LargeTest
    public void testThumbnailForH264VGADifferentDuration() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final int atTime = 0;
        final int atTime1 = 100;
        final int atTime2 = 200;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = mediaVideoItem.getWidth();
        final int outHeight = mediaVideoItem.getHeight();

        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);

        // get Thumbnail @ 100ms
        final Bitmap thumbNailBmpAt100 =
            mediaVideoItem.getThumbnail(outWidth, outHeight, atTime1);
        validateThumbnail(thumbNailBmpAt100, outWidth, outHeight);

        // get Thumbnail @ 200ms
        final Bitmap thumbNailBmpAt200 = mediaVideoItem.getThumbnail(
            outWidth, outHeight, atTime2);
        validateThumbnail(thumbNailBmpAt200, outWidth, outHeight);
    }

    /**
     *Check the thumbnail / frame extraction precision at
     * FileDuration,FileDuration/2 + 100 andFileDuration/2 + 200 ms
     */
    // TODO : TC_TN_013
    @LargeTest
    public void testThumbnailForMP4VGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "MPEG4_SP_640x480_15fps_256kbps_0_30.mp4";
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, MediaItem.RENDERING_MODE_BLACK_BORDER);

        final int outWidth = mediaVideoItem.getWidth();
        final int outHeight = mediaVideoItem.getHeight();
        final long atTime = mediaVideoItem.getDuration() / 2;
        final long atTime1 = atTime + 100;
        final long atTime2 = atTime + 200;

        // get Thumbnail @ duration/2
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);

        // get Thumbnail @ duration/2 + 100ms
        final Bitmap thumbNailBmpAt100 = mediaVideoItem.getThumbnail(
            outWidth, outHeight, atTime1);
        validateThumbnail(thumbNailBmpAt100, outWidth, outHeight);

        // get Thumbnail @ duration/2 + 200ms
        final Bitmap thumbNailBmpAt200 = mediaVideoItem.getThumbnail(
            outWidth, outHeight, atTime2);
        validateThumbnail(thumbNailBmpAt200, outWidth, outHeight);
    }

    /**
     * Check the thumbnail / frame extraction on JPEG file
     */
    // TODO : TC_TN_014
    @LargeTest
    public void testThumbnailForImage() throws Exception {
        final String imageItemFilename = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int mediaDuration = 1000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        int outWidth = 0;
        int outHeight = 0;

        final MediaImageItem mii = mVideoEditorHelper.createMediaItem(
            mVideoEditor, "m1", imageItemFilename, mediaDuration, renderingMode);
        assertNotNull("Media Image Item is Null",  mii);
        outWidth =  mii.getWidth() / 2;
        outHeight =  mii.getHeight() / 2;

        final Bitmap thumbNailBmp = mii.getThumbnail(outWidth,
            outHeight, mediaDuration);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }
    /**
     *To test ThumbnailList for H263 QCIF
     */
    // TODO : TC_TN_015
    @LargeTest
    public void testThumbnailListH263QCIF() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H263_profile0_176x144_15fps_256kbps_AACLC_32kHz_128kbps_s_1_17.3gp";
        final int startTime = 0;
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final int outWidth = mediaVideoItem.getWidth() / 4;
        final int outHeight = mediaVideoItem.getHeight() / 4;
        final long endTime = mediaVideoItem.getDuration() / 2;

        final Bitmap thumbNailBmp[] = mediaVideoItem.getThumbnailList(
            outWidth, outHeight, startTime, endTime, tnCount);
        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertEquals("Thumbnail Count", tnCount, thumbNailBmp.length);

        for (int i = 0; i < thumbNailBmp.length; i++) {
            validateThumbnail(thumbNailBmp[i], outWidth, outHeight);
            thumbNailBmp[i] = null;
        }
    }

    /**
     *To test ThumbnailList for MPEG4 QCIF
     */
    // TODO : TC_TN_016
    @LargeTest
    public void testThumbnailListMPEG4QCIF() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final int outWidth = mediaVideoItem.getWidth() / 2;
        final int outHeight = mediaVideoItem.getHeight() / 2;
        final long startTime = mediaVideoItem.getDuration() / 2;
        final long endTime = mediaVideoItem.getDuration();

        final Bitmap thumbNailBmp[] = mediaVideoItem.getThumbnailList(
            outWidth, outHeight, startTime, endTime, tnCount);

        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertEquals("Thumbnail Count", tnCount, thumbNailBmp.length);
        for (int i = 0; i < thumbNailBmp.length; i++) {
            validateThumbnail(thumbNailBmp[i], outWidth, outHeight);
            thumbNailBmp[i] = null;
        }
    }

    /**
     *To test ThumbnailList for H264 VGA
     */
    // TODO : TC_TN_017
    @LargeTest
    public void testThumbnailListH264VGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final int outWidth = mediaVideoItem.getWidth() / 2;
        final int outHeight = mediaVideoItem.getHeight() / 2;
        final long startTime = mediaVideoItem.getDuration() / 3;
        final long endTime = mediaVideoItem.getDuration() / 2;

        final Bitmap thumbNailBmp[] = mediaVideoItem.getThumbnailList(
            outWidth, outHeight, startTime, endTime, tnCount);
        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertEquals("Thumbnail Count", tnCount, thumbNailBmp.length);
        for (int i = 0; i < thumbNailBmp.length; i++) {
            validateThumbnail(thumbNailBmp[i], outWidth, outHeight);
            thumbNailBmp[i] = null;
        }
    }

    /**
     *To test ThumbnailList for H264 WVGA
     */
    // TODO : TC_TN_018
    @LargeTest
    public void testThumbnailListH264WVGA() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_AACLC_24KHz_38Kbps_s_1_17.mp4";
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final int outWidth = mediaVideoItem.getWidth() / 2;
        final int outHeight = mediaVideoItem.getHeight() / 2;
        final long startTime = mediaVideoItem.getDuration() / 3;
        final long endTime = mediaVideoItem.getDuration() / 2;

        final Bitmap thumbNailBmp[] = mediaVideoItem.getThumbnailList(
            outWidth, outHeight, startTime, endTime, tnCount);
        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertEquals("Thumbnail Count", tnCount, thumbNailBmp.length);
        for (int i = 0; i < thumbNailBmp.length; i++) {
            validateThumbnail(thumbNailBmp[i], outWidth, outHeight);
            thumbNailBmp[i] = null;
        }
    }

    /**
     *To test ThumbnailList for H264 VGA ,Time exceeding file duration
     */
    // TODO : TC_TN_019
    @LargeTest
    public void testThumbnailH264VGAExceedingFileDuration() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_640x480_30fps_256kbps_1_17.mp4";
        boolean flagForException = false;
        int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        try {
            final MediaVideoItem mediaVideoItem =
                mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = mediaVideoItem.getWidth() / 2;
            final int outHeight = mediaVideoItem.getHeight() / 2;
            final long atTime = mediaVideoItem.getDuration() + 2000;
            mediaVideoItem.getThumbnail(outWidth, outHeight, atTime);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Exception in Extracting thumbanil with Invalid Time",
            flagForException);
    }

    /**
     *To test ThumbnailList for VGA Image
     */
    // TODO : TC_TN_020
    @LargeTest
    public void testThumbnailListVGAImage() throws Exception {
        final String imageItemFilename = INPUT_FILE_PATH + "IMG_640x480.jpg";
        final int imageItemDuration = 10000;
        final int startTime = 0;
        final int endTime = 0;
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaImageItem mediaImageItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                imageItemFilename, imageItemDuration, renderingMode);
        final int outWidth = mediaImageItem.getWidth() / 2;
        final int outHeight = mediaImageItem.getHeight() / 2;

        final Bitmap thumbNailBmp[] = mediaImageItem.getThumbnailList
            (outWidth, outHeight, startTime, endTime, tnCount);
        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertEquals("Thumbnail Count", tnCount, thumbNailBmp.length);
        for (int i = 0; i < thumbNailBmp.length; i++) {
            validateThumbnail(thumbNailBmp[i], outWidth, outHeight);
            thumbNailBmp[i] = null;
        }
    }

    /**
     *To test ThumbnailList for Invalid file path
     */
    // TODO : TC_TN_021
    @LargeTest
    public void testThumbnailForInvalidFilePath() throws Exception {
        final String imageItemFileName = INPUT_FILE_PATH + "/sdcard/abc.jpg";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        try{
        final MediaImageItem mii = new MediaImageItem(mVideoEditor, "m1",
            imageItemFileName, 3000, renderingMode);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        } catch (IOException e) {
            flagForException = true;
        }
        assertTrue(" Invalid File Path", flagForException);
    }

    /**
     * To test thumbnail / frame extraction with setBoundaries
     */
    // TODO : TC_TN_022
    @LargeTest
    public void testThumbnailForMPEG4WVGAWithSetBoundaries() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "MPEG4_SP_800x480_515kbps_15fps_AMR_NB_8KHz_12.2kbps_m_0_26.mp4";
        final int atTime = 10000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        mediaVideoItem.setExtractBoundaries(1000,
            (mediaVideoItem.getDuration() - 21000));

        final int outWidth = (mediaVideoItem.getWidth() / 2);
        final int outHeight = (mediaVideoItem.getHeight() / 2);
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail(outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     *To test ThumbnailList for H264 WVGA with setExtractboundaries
     */
    // TODO : TC_TN_023
    @LargeTest
    public void testThumbnailListForH264WVGAWithSetBoundaries() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_1_17.mp4";
        final int thumbNailStartTime = 10000;
        final int thumbNailEndTime = 12000;
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final int outWidth = (mediaVideoItem.getWidth() / 2);
        final int outHeight = (mediaVideoItem.getHeight() / 2);

        mediaVideoItem.setExtractBoundaries(10000, 12000);

        final Bitmap thumbNailBmp[] = mediaVideoItem.getThumbnailList
            (outWidth, outHeight, thumbNailStartTime, thumbNailEndTime,
             tnCount);
        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertTrue("Thumbnail Size", (thumbNailBmp.length > 0) ? true : false);
        for (int i = 0; i < thumbNailBmp.length; i++) {
            validateThumbnail(thumbNailBmp[i], outWidth, outHeight);
            thumbNailBmp[i] = null;
        }
    }

    /**
     *To test ThumbnailList for H264 WVGA with count > frame available
     */
    // TODO : TC_TN_024
    @LargeTest
    public void testThumbnailListForH264WVGAWithCount() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_AACLC_24KHz_38Kbps_s_1_17.mp4";
        final int tnCount = 70;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);

        final int outWidth = (mediaVideoItem.getWidth() / 2);
        final int outHeight = (mediaVideoItem.getHeight() / 2);
        final long thumbNailStartTime = mediaVideoItem.getDuration() / 2;
        final long thumbNailEndTime = thumbNailStartTime + 4000;
        Bitmap thumbNailBmp[] = null;
        boolean flagForException = false;
        try{
            thumbNailBmp = mediaVideoItem.getThumbnailList(outWidth, outHeight,
                thumbNailStartTime, thumbNailEndTime, tnCount);
        }catch (Exception e){
            assertTrue("Unable to get Thumbnail list", flagForException);
        }
        if (thumbNailBmp.length <= tnCount) {
            flagForException = true;
        }
        assertTrue("Thumbnail count more than asked", flagForException);
    }

    /**
     *To test ThumbnailList for H264 WVGA with startTime > End Time
     */
    // TODO : TC_TN_025
    @LargeTest
    public void testThumbnailListH264WVGAWithStartGreaterEnd() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_AACLC_24KHz_38Kbps_s_1_17.mp4";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final int tnCount = 10;
        boolean flagForException = false;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = (mediaVideoItem.getWidth() / 2);
        final int outHeight = (mediaVideoItem.getHeight() / 2);
        final long thumbNailStartTime = mediaVideoItem.getDuration() / 2;
        final long thumbNailEndTime = thumbNailStartTime - 1000;
        try{
            mediaVideoItem.getThumbnailList(outWidth, outHeight,
                thumbNailStartTime, thumbNailEndTime, tnCount);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Thumbnail Extraction where start time > end time",
            flagForException);
    }

    /**
     *To test ThumbnailList TC_TN_026 for H264 WVGA with startTime = End Time
     */
    // TODO : TC_TN_026
    @LargeTest
    public void testThumbnailListH264WVGAWithStartEqualEnd() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "H264_BP_800x480_15fps_512kbps_AACLC_24KHz_38Kbps_s_1_17.mp4";
        final int tnCount = 1;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;

        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = (mediaVideoItem.getWidth() / 2);
        final int outHeight = (mediaVideoItem.getHeight() / 2);
        final long thumbNailStartTime = mediaVideoItem.getDuration() / 2;
        final long thumbNailEndTime = thumbNailStartTime;
        final Bitmap thumbNailBmp[] = mediaVideoItem.getThumbnailList(outWidth,
            outHeight, thumbNailStartTime, thumbNailEndTime, tnCount);
        assertNotNull("Thumbnail Retrived is Null", thumbNailBmp);
        assertEquals("Thumbnail Count", tnCount, thumbNailBmp.length);
        for (int i = 0; i < thumbNailBmp.length; i++) {
            validateThumbnail(thumbNailBmp[i], outWidth, outHeight);
            thumbNailBmp[i] = null;
        }
    }

    /**
     *To test ThumbnailList TC_TN_027 for file where video duration is less
     * than file duration.
     */
    // TODO : TC_TN_027
    @LargeTest
    public void testThumbnailForVideoDurationLessFileDuration() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "H264_BP_640x480_15fps_1200Kbps_AACLC_48KHz_64kps_m_0_27.3gp";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
            final MediaVideoItem mediaVideoItem =
                mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = (mediaVideoItem.getWidth() / 2);
            final int outHeight = (mediaVideoItem.getHeight() / 2);
            final long atTime = mediaVideoItem.getDuration() - 2000;
            final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail (outWidth,
                outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);

    }

    /**
     *To test ThumbnailList TC_TN_028 for file which has video part corrupted
     */
    // TODO : TC_TN_028
    @LargeTest
    public void testThumbnailWithCorruptedVideoPart() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH +
            "corrupted_H264_BP_640x480_12.5fps_256kbps_AACLC_16khz_24kbps_s_0_26.mp4";
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;

        try {
            final MediaVideoItem mediaVideoItem =
                 mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = mediaVideoItem.getWidth();
            final int outHeight = mediaVideoItem.getHeight() * 2;
            final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail
                (outWidth, outHeight, mediaVideoItem.getDuration()/2);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Corrupted File cannot be read", flagForException);
    }

    /**
     * Check the thumbnail / frame list extraction for Height as Negative Value
     */
    // TODO : TC_TN_029
    @LargeTest
    public void testThumbnailWithNegativeHeight() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        try {
            final MediaVideoItem mediaVideoItem =
                mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = (mediaVideoItem.getWidth() / 2);
            final int outHeight = -1;
            final long thumbNailStartTime =
                mediaVideoItem.getBoundaryBeginTime()/2;
            final long thumbNailEndTime = mediaVideoItem.getBoundaryEndTime();
            mediaVideoItem.getThumbnailList(outWidth, outHeight,
                thumbNailStartTime, thumbNailEndTime, tnCount);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Thumbnail List with negative Height", flagForException);
    }

    /**
     * Check the thumbnail for Height as Zero
     */
    // TODO : TC_TN_030
    @LargeTest
    public void testThumbnailWithHeightAsZero() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int atTime = 100;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        try {
            final MediaVideoItem mediaVideoItem =
                mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = (mediaVideoItem.getWidth() / 2);
            final int outHeight = -1;
            mediaVideoItem.getThumbnail(outWidth, outHeight, atTime);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Thumbnail List with Zero Height", flagForException);
    }

    /**
     * Check the thumbnail for Height = 10
     */
    // TODO : TC_TN_031
    @LargeTest
    public void testThumbnailWithHeight() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int atTime = 1000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
            final MediaVideoItem mediaVideoItem =
                mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = (mediaVideoItem.getWidth() / 2);
            final int outHeight = 10;
            final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail (outWidth,
                outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * Check the thumbnail / frame list extraction for Width as Negative Value
     */
    // TODO : TC_TN_032
    @LargeTest
    public void testThumbnailWithNegativeWidth() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int tnCount = 10;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        try {
            final MediaVideoItem mediaVideoItem =
                mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = -1;
            final int outHeight = mediaVideoItem.getHeight();
            final long thumbNailStartTime =
                mediaVideoItem.getBoundaryBeginTime()/2;
            final long thumbNailEndTime = mediaVideoItem.getBoundaryEndTime();
            mediaVideoItem.getThumbnailList(outWidth, outHeight, thumbNailStartTime,
                thumbNailEndTime, tnCount);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Thumbnail List with negative Height", flagForException);
    }

    /**
     * Check the thumbnail / frame list extraction for Width zero
     */
    // TODO : TC_TN_033
    @LargeTest
    public void testThumbnailWithWidthAsZero() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int atTime = 1000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        boolean flagForException = false;
        try {
            final MediaVideoItem mediaVideoItem =
                mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                    videoItemFilename, renderingMode);
            final int outWidth = 0;
            final int outHeight = mediaVideoItem.getHeight() / 2;
            mediaVideoItem.getThumbnail(outWidth, outHeight, atTime);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Thumbnail List with Zero Width", flagForException);
    }

    /**
     * Check the thumbnail for Width = 10
     */
    // TODO : TC_TN_034
    @LargeTest
    public void testThumbnailWithWidth() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        final int atTime = 1000;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth = 10;
        final int outHeight = mediaVideoItem.getHeight();
        final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail (outWidth,
            outHeight, atTime);
        validateThumbnail(thumbNailBmp, outWidth, outHeight);
    }

    /**
     * To test thumbnail / frame extraction on MPEG4 (time beyond file duration).
     */
    // TODO : TC_TN_035
    @LargeTest
    public void testThumbnailMPEG4withMorethanFileDuration() throws Exception {
        final String videoItemFilename = INPUT_FILE_PATH
            + "MPEG4_SP_176x144_30fps_256kbps_AACLC_44.1kHz_96kbps_s_1_17.3gp";
        boolean flagForException = false;
        final int renderingMode = MediaItem.RENDERING_MODE_BLACK_BORDER;
        final MediaVideoItem mediaVideoItem =
            mVideoEditorHelper.createMediaItem(mVideoEditor, "m1",
                videoItemFilename, renderingMode);
        final int outWidth =  mediaVideoItem.getWidth()/2;
        final int outHeight =  mediaVideoItem.getHeight()/2;
        final long atTime = mediaVideoItem.getDuration() + 100;
        try{
            final Bitmap thumbNailBmp = mediaVideoItem.getThumbnail (outWidth,
            outHeight, atTime);
        } catch (IllegalArgumentException e) {
            flagForException = true;
        }
        assertTrue("Thumbnail duration is more than file duration",
            flagForException);
    }
}
