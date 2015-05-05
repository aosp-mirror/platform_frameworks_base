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

package com.android.mediaframeworktest;

/**
 *
 * This class has the names of the all the activity name and variables in the
 * instrumentation test.
 *
 */
public class MediaNames {
    // A directory to hold all kinds of media files
    public static final String MEDIA_SAMPLE_POOL = "/sdcard/media_api/samples/";
    // A file to hold all streaming URLs
    public static final String MEDIA_STREAMING_SRC = "/sdcard/media_api/streaming.txt";

    // Audio files
    public static final String MP3CBR = "/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_CBR.mp3";
    public static final String MP3VBR = "/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_VBR.mp3";
    public static final String MP3ABR = "/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_ABR.mp3";
    public static final String SHORTMP3 = "/sdcard/media_api/music/SHORTMP3.mp3";
    public static final String MIDI = "/sdcard/media_api/music/ants.mid";
    public static final String WAV = "/sdcard/media_api/music/rings_2ch.wav";
    public static final String AMR = "/sdcard/media_api/music/test_amr_ietf.amr";
    public static final String SINE_200_1000 = "/sdcard/media_api/music/sine_200+1000Hz_44K_mo.wav";
    // public static final String OGG =
    // "/sdcard/media_api/music/Revelation.ogg";

    public static final int MP3CBR_LENGTH = 71000;
    public static final int MP3VBR_LENGTH = 71000;
    public static final int SHORTMP3_LENGTH = 286;
    public static final int MIDI_LENGTH = 17000;
    public static final int AMR_LENGTH = 37000;
    public static final int SEEK_TIME = 10000;

    public static final long PAUSE_WAIT_TIME = 3000;
    public static final long WAIT_TIME = 2000;
    public static final long WAIT_SNAPSHOT_TIME = 5000;

    // local video
    public static final String VIDEO_MP4 = "/sdcard/media_api/video/MPEG4_320_AAC_64.mp4";
    public static final String VIDEO_SHORT_3GP = "/sdcard/media_api/video/short.3gp";
    public static final String VIDEO_LARGE_SIZE_3GP = "/sdcard/media_api/video/border_large.3gp";
    public static final String VIDEO_H263_AAC = "/sdcard/media_api/video/H263_56_AAC_24.3gp";
    public static final String VIDEO_H263_AMR = "/sdcard/media_api/video/H263_56_AMRNB_6.3gp";
    public static final String VIDEO_H264_AAC = "/sdcard/media_api/video/H264_320_AAC_64.3gp";
    public static final String VIDEO_H264_AMR = "/sdcard/media_api/video/H264_320_AMRNB_6.3gp";
    public static final String VIDEO_HEVC_AAC = "/sdcard/media_api/video/HEVC_320_AAC_128.mp4";
    public static final String VIDEO_MPEG2_AAC = "/sdcard/media_api/video/MPEG2_1500_AAC_128.mp4";
    public static final String VIDEO_HIGHRES_H263 = "/sdcard/media_api/video/H263_500_AMRNB_12.3gp";
    public static final String VIDEO_HIGHRES_MP4 = "/sdcard/media_api/video/H264_500_AAC_128.3gp";
    public static final String VIDEO_WEBM = "/sdcard/media_api/video/big-buck-bunny_trailer.webm";

    // Media Recorder
    public static final String RECORDER_OUTPUT = "/sdcard/media_api/recorderOutput.amr";

    // video thumbnail
    public static final String THUMBNAIL_OUTPUT = "/sdcard/media_api/videoThumbnail.png";
    public static final String GOLDEN_THUMBNAIL_OUTPUT = "/sdcard/media_api/goldenThumbnail.png";

    /*
     * Metadata Utility Test media files which contain meta data.
     */
    public static final String[] THUMBNAIL_METADATA_TEST_FILES = {
        "/sdcard/media_api/video/H263_500_AMRNB_12.3gp",
        "/sdcard/media_api/video/H263_56_AAC_24.3gp",
        "/sdcard/media_api/video/H263_56_AMRNB_6.3gp",
        "/sdcard/media_api/video/H264_320_AAC_64.3gp",
        "/sdcard/media_api/video/H264_320_AMRNB_6.3gp",
        "/sdcard/media_api/video/H264_500_AAC_128.3gp",
        "/sdcard/media_api/video/H264_HVGA_500_NO_AUDIO.3gp",
        "/sdcard/media_api/video/H264_QVGA_500_NO_AUDIO.3gp",
        "/sdcard/media_api/video/MPEG4_320_AAC_64.mp4",
        "/sdcard/media_api/video/border_large.3gp",
        "/sdcard/media_api/videoeditor/H264_BP_800x480_15fps_512kbps_AACLC_24KHz_38Kbps_s_1_17.mp4",
        "/sdcard/media_api/videoeditor/H264_MP_960x720_25fps_800kbps_AACLC_48Khz_192Kbps_s_1_17.mp4",
        "/sdcard/media_api/videoeditor/MPEG4_SP_640x480_15fps_512kbps_AACLC_48khz_132kbps_s_0_26.mp4",
        "/sdcard/media_api/videoeditor/MPEG4_SP_176x144_12fps_92kbps_AMRNB_8KHz_12.2kbps_m_0_27.3gp",
        "/sdcard/media_api/videoeditor/MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_161kbps_s_0_26.mp4"
    };

    public static final String[] ALBUMART_TEST_FILES = {
        "/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_ID3V1_ID3V2.mp3",
        "/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_ID3V2.mp3",
        "/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_ID3V1.mp3",
    };

    // TEST_PATH_1: is a video and contains metadata for key "num-tracks"
    // TEST_PATH_2: any valid media file.
    // TEST_PATH_3: invalid media file
    public static final String TEST_PATH_1 = "/sdcard/media_api/video/MPEG4_320_AAC_64.mp4";
    public static final String TEST_PATH_3 = "/sdcard/media_api/data.txt";
    public static final String TEST_PATH_4 = "somenonexistingpathname";
    public static final String TEST_PATH_5 = "mem://012345";

    // Meta data expected result
    // The expected tag result in the following order
    // cd_track_number, album, artist, author, composer, date, genre
    // title, years, duration
    public static final String META_DATA_MP3[][] = {
        {"/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_ID3V1_ID3V2.mp3", "2/34",
         "Test ID3V2 Album", "Test ID3V2 Artist", null, "Test ID3V2 Composer",
         null, "(1)Classic Rock", "Test ID3V2 Title ", null, "77640", "1", null},
        {"/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_ID3V2.mp3", "1/10",
         "Test ID3V2 Album", "Test ID3V2 Artist", null, "Test ID3V2 Composer",
         null, "(74)Acid Jazz", "Test ID3V2 Tag", null, "77640", "1", null},
        {"/sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_ID3V1.mp3", "2",
         "Test ID3V1 Album", "Test ID3V1 Artist", null, null, null, "(15)",
         "Test ID3V1 Title", "2011", "77640", "1", null}
    };

    // output recorded video
    public static final String RECORDED_HVGA_H263 = "/sdcard/HVGA_H263.3gp";
    public static final String RECORDED_QVGA_H263 = "/sdcard/QVGA_H263.3gp";
    public static final String RECORDED_SQVGA_H263 = "/sdcard/SQVGA_H263.3gp";
    public static final String RECORDED_CIF_H263 = "/sdcard/CIF_H263.3gp";
    public static final String RECORDED_QCIF_H263 = "/sdcard/QCIF_H263.3gp";
    public static final String RECORDED_PORTRAIT_H263 = "/sdcard/QCIF_mp4.3gp";

    public static final String RECORDED_HVGA_MP4 = "/sdcard/HVGA_mp4.mp4";
    public static final String RECORDED_QVGA_MP4 = "/sdcard/QVGA_mp4.mp4";
    public static final String RECORDED_SQVGA_MP4 = "/sdcard/SQVGA_mp4.mp4";
    public static final String RECORDED_CIF_MP4 = "/sdcard/CIF_mp4.mp4";
    public static final String RECORDED_QCIF_MP4 = "/sdcard/QCIF_mp4.mp4";

    public static final String RECORDED_VIDEO_3GP = "/sdcard/temp.3gp";

    public static final String INVALD_VIDEO_PATH =
            "/sdcard/media_api/filepathdoesnotexist" + "/filepathdoesnotexist/temp.3gp";

    public static final String RECORDED_SURFACE_3GP = "/sdcard/surface.3gp";

    public static final long RECORDED_TIME = 5000;
    public static final long VALID_VIDEO_DURATION = 2000;

    // Streaming test files
    public static final byte[] STREAM_SERVER =
            new byte[] {(byte) 75, (byte) 17, (byte) 48, (byte) 204};
    public static final String STREAM_H264_480_360_1411k =
            "http://75.17.48.204:10088/yslau/stress_media/h264_regular.mp4";
    public static final String STREAM_WMV = "http://75.17.48.204:10088/yslau/stress_media/bugs.wmv";
    public static final String STREAM_H263_176x144_325k =
            "http://75.17.48.204:10088/yslau/stress_media/h263_regular.3gp";
    public static final String STREAM_H264_352x288_1536k =
            "http://75.17.48.204:10088/yslau/stress_media/h264_highBitRate.mp4";
    public static final String STREAM_MP3 =
            "http://75.17.48.204:10088/yslau/stress_media/mp3_regular.mp3";
    public static final String STREAM_MPEG4_QVGA_128k =
            "http://75.17.48.204:10088/yslau/stress_media/mpeg4_qvga_24fps.3gp";
    public static final int STREAM_H264_480_360_1411k_DURATION = 46000;
    public static final int VIDEO_H263_AAC_DURATION = 501000;
    public static final int VIDEO_H263_AMR_DURATION = 502000;

    // Video files for WiFi IOT video streaming test.
    public static final String[] NETWORK_VIDEO_FILES = {
            "H264_BP_720x480_25fps_256kbps_AMRNB_8khz_12.2kbps_m_0_26.mp4",
            "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_161kbps_s_0_26.mp4",
            "MPEG4_SP_720x480_30fps_280kbps_AACLC_48kHz_96kbps_s_0_21.mp4"
    };
}
