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

package com.android.mediaframeworktest;

/**
 * 
 * This class has the names of the all the activity name and variables 
 * in the instrumentation test.
 *
 */
public class MediaNames {
    //A directory to hold all kinds of media files
    public static final String MEDIA_SAMPLE_POOL = "/sdcard/media_api/samples/";
    //Audio files
    public static final String MP3CBR = "/sdcard/media_api/music/MP3_256kbps_2ch.mp3";
    public static final String MP3VBR = "/sdcard/media_api/music/MP3_256kbps_2ch_VBR.mp3";
    public static final String SHORTMP3 = "/sdcard/media_api/music/SHORTMP3.mp3";
    public static final String MIDI = "/sdcard/media_api/music/ants.mid";
    public static final String WMA9 = "/sdcard/media_api/music/WMA9.wma";
    public static final String WMA10 = "/sdcard/media_api/music/WMA10.wma";
    public static final String WAV = "/sdcard/media_api/music/rings_2ch.wav";
    public static final String AMR = "/sdcard/media_api/music/test_amr_ietf.amr";
    public static final String OGG = "/sdcard/media_api/music/Revelation.ogg";
    public static final String SINE_200_1000 = "/sdcard/media_api/music/sine_200+1000Hz_44K_mo.wav";
  
    public static final int MP3CBR_LENGTH = 71000;
    public static final int MP3VBR_LENGTH = 71000;
    public static final int SHORTMP3_LENGTH = 286;
    public static final int MIDI_LENGTH = 17000;
    public static final int WMA9_LENGTH = 126559;
    public static final int WMA10_LENGTH = 126559;
    public static final int AMR_LENGTH = 37000;
    public static final int OGG_LENGTH = 4000;
    public static final int SEEK_TIME = 10000;
  
    public static final long PAUSE_WAIT_TIME = 3000;
    public static final long WAIT_TIME = 2000;
    public static final long WAIT_SNAPSHOT_TIME = 5000;

    //local video
    public static final String VIDEO_MP4 = "/sdcard/media_api/video/MPEG4_320_AAC_64.mp4";
    public static final String VIDEO_SHORT_3GP = "/sdcard/media_api/video/short.3gp";
    public static final String VIDEO_LARGE_SIZE_3GP = "/sdcard/media_api/video/border_large.3gp";
    public static final String VIDEO_H263_AAC = "/sdcard/media_api/video/H263_56_AAC_24.3gp";
    public static final String VIDEO_H263_AMR = "/sdcard/media_api/video/H263_56_AMRNB_6.3gp";
    public static final String VIDEO_H264_AAC = "/sdcard/media_api/video/H264_320_AAC_64.3gp";
    public static final String VIDEO_H264_AMR = "/sdcard/media_api/video/H264_320_AMRNB_6.3gp";
    public static final String VIDEO_WMV = "/sdcard/media_api/video/bugs.wmv";
    public static final String VIDEO_HIGHRES_H263 = "/sdcard/media_api/video/H263_500_AMRNB_12.3gp";
    public static final String VIDEO_HIGHRES_MP4 = "/sdcard/media_api/video/H264_500_AAC_128.3gp";
    
    //Media Recorder
    public static final String RECORDER_OUTPUT = "/sdcard/media_api/recorderOutput.amr";

    //video thumbnail
    public static final String THUMBNAIL_OUTPUT = "/sdcard/media_api/videoThumbnail.png";
    public static final String GOLDEN_THUMBNAIL_OUTPUT = "/sdcard/media_api/goldenThumbnail.png";
    public static final String GOLDEN_THUMBNAIL_OUTPUT_2 = "/sdcard/media_api/goldenThumbnail2.png";
    
    //Metadata Utility
    public static final String[] THUMBNAIL_CAPTURE_TEST_FILES = {
      "/sdcard/media_api/metadata/test.mp4",
      "/sdcard/media_api/metadata/test1.3gp",
      "/sdcard/media_api/metadata/test2.3gp",
      "/sdcard/media_api/metadata/test3.3gp",
      "/sdcard/media_api/metadata/test4.3gp",
      "/sdcard/media_api/metadata/test5.3gp",
      "/sdcard/media_api/metadata/test6.3gp",
      "/sdcard/media_api/metadata/test7.3gp",
      "/sdcard/media_api/metadata/test8.3gp",
      "/sdcard/media_api/metadata/test9.3gp",
      "/sdcard/media_api/metadata/test10.3gp",
      "/sdcard/media_api/metadata/test11.3gp",
      "/sdcard/media_api/metadata/test12.3gp",
      "/sdcard/media_api/metadata/test13.3gp",
      "/sdcard/media_api/metadata/test14.3gp",
      "/sdcard/media_api/metadata/test15.3gp",
      "/sdcard/media_api/metadata/test16.3gp",
      "/sdcard/media_api/metadata/test17.3gp",
      "/sdcard/media_api/metadata/test18.3gp",
      "/sdcard/media_api/metadata/test19.3gp",
      "/sdcard/media_api/metadata/test20.3gp",
      "/sdcard/media_api/metadata/test21.3gp",
      "/sdcard/media_api/metadata/test22.3gp",
      "/sdcard/media_api/metadata/test23.3gp",
      "/sdcard/media_api/metadata/test24.3gp",
      "/sdcard/media_api/metadata/test25.3gp",
      "/sdcard/media_api/metadata/test26.3gp",
      "/sdcard/media_api/metadata/test27.3gp",
      "/sdcard/media_api/metadata/test28.3gp",
      "/sdcard/media_api/metadata/test29.3gp",
      "/sdcard/media_api/metadata/test30.3gp",
      "/sdcard/media_api/metadata/test31.3gp",
      "/sdcard/media_api/metadata/test32.3gp",
      "/sdcard/media_api/metadata/test33.3gp",
      "/sdcard/media_api/metadata/test35.mp4",
      "/sdcard/media_api/metadata/test36.m4v",
      "/sdcard/media_api/metadata/test34.wmv",
      "/sdcard/media_api/metadata/test_metadata.mp4",
  };

  public static final String[] METADATA_RETRIEVAL_TEST_FILES = {
      // Raw AAC is not supported
      // "/sdcard/media_api/test_raw.aac",
      // "/sdcard/media_api/test_adts.aac",
      // "/sdcard/media_api/test_adif.aac",
      "/sdcard/media_api/metadata/test_metadata.mp4",
      "/sdcard/media_api/metadata/WMA10.wma",
      "/sdcard/media_api/metadata/Leadsol_out.wav",
      "/sdcard/media_api/metadata/test_aac.mp4",
      "/sdcard/media_api/metadata/test_amr.mp4",
      "/sdcard/media_api/metadata/test_avc_amr.mp4",
      "/sdcard/media_api/metadata/test_metadata.mp4",
      "/sdcard/media_api/metadata/test_vbr.mp3",
      "/sdcard/media_api/metadata/test_cbr.mp3",
      "/sdcard/media_api/metadata/metadata_test1.mp3",
      "/sdcard/media_api/metadata/test33.3gp",
      "/sdcard/media_api/metadata/test35.mp4",
      "/sdcard/media_api/metadata/test36.m4v",
      "/sdcard/media_api/metadata/test_m4v_amr.mp4",
      "/sdcard/media_api/metadata/test_h263_amr.mp4",
      "/sdcard/media_api/metadata/test34.wmv",
  };
  
  public static final String[] ALBUMART_TEST_FILES = {
      "/sdcard/media_api/album_photo/test_22_16_mp3.mp3",
      "/sdcard/media_api/album_photo/PD_256kbps_48khz_mono_CBR_MCA.mp3",
      "/sdcard/media_api/album_photo/PD_256kbps_44.1khz_mono_CBR_DPA.mp3",
      "/sdcard/media_api/album_photo/PD_192kbps_32khz_mono_CBR_DPA.mp3",
      "/sdcard/media_api/album_photo/NIN_256kbps_48khz_mono_CBR_MCA.mp3",
      "/sdcard/media_api/album_photo/NIN_256kbps_44.1khz_mono_CBR_MCA.mp3",
      "/sdcard/media_api/album_photo/NIN_112kbps(96kbps)_48khz_stereo_VBR_MCA.mp3",
      "/sdcard/media_api/album_photo/NIN_112kbps(96kbps)_44.1khz_stereo_VBR_MCA.mp3",
      "/sdcard/media_api/album_photo/lightGreen1.mp3",
      "/sdcard/media_api/album_photo/babyBlue2 1.mp3",
      "/sdcard/media_api/album_photo/2-01 01 NIN_56kbps(64kbps)_32khz_stereo_VBR_MCA.mp3",
      "/sdcard/media_api/album_photo/02_NIN_112kbps(80kbps)_32khz_stereo_VBR_MCA.mp3",
      "/sdcard/media_api/album_photo/No_Woman_No_Cry_128K.wma",
      "/sdcard/media_api/album_photo/Beethoven_2.wma",
  };

  //TEST_PATH_1: is a video and contains metadata for key "num-tracks"
  // TEST_PATH_2: any valid media file.
  // TEST_PATH_3: invalid media file
  public static final String TEST_PATH_1 = "/sdcard/media_api/metadata/test.mp4";
  public static final String TEST_PATH_3 = "/sdcard/media_api/data.txt";
  public static final String TEST_PATH_4 = "somenonexistingpathname";
  public static final String TEST_PATH_5 = "mem://012345";
  
  //Meta data expected result
  //The expected tag result in the following order
  //cd_track_number, album, artist, author, composer, date, genre
  //title, years, duration
  public static final String META_DATA_MP3 [][] = {
      {"/sdcard/media_api/metaDataTestMedias/MP3/ID3V1_ID3V2.mp3", "1/10", "ID3V2.3 Album", "ID3V2.3 Artist",
          "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, "Blues",
          "ID3V2.3 Title", "1234", "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/ID3V2.mp3", "1/10", "ID3V2.3 Album", "ID3V2.3 Artist",
          "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, "Blues", 
          "ID3V2.3 Title", "1234", "287", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/ID3V1.mp3", "1", "test ID3V1 Album", "test ID3V1 Artist",
          null, null, null, "255", "test ID3V1 Title", "1234", "231332", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V1.mp3" , null, null, null,
              null, null, null, null, null, null, "231330", "1", null},
      //The corrupted TALB field in id3v2 would not switch to id3v1 tag automatically
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TALB.mp3", "01", null, "ID3V2.3 Artist",
          "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, 
          "Blues", "ID3V2.3 Title", "1234", "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TCOM.mp3", "01", "ID3V2.3 Album", 
           "ID3V2.3 Artist", "ID3V2.3 Lyricist", null, null, 
           "Blues", "ID3V2.3 Title", "1234", "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TCOM_2.mp3", "01", "ID3V2.3 Album", 
           "ID3V2.3 Artist", null, null, null, "Blues", "ID3V2.3 Title", "1234", "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TRCK.mp3", "dd", "ID3V2.3 Album", 
           "ID3V2.3 Artist", "ID3V2.3 Lyricist", "ID3V2.3 Composer", null,
           "Blues", "ID3V2.3 Title", "1234", "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TRCK_2.mp3", "01", "ID3V2.3 Album", 
           "ID3V2.3 Artist", null, null, null, null, "ID3V2.3 Title", null, "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TYER.mp3", "01", "ID3V2.3 Album",
           "ID3V2.3 Artist", null, null, null, null, "ID3V2.3 Title", "9999", "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TYER_2.mp3", "01", "ID3V2.3 Album",
           "ID3V2.3 Artist", "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, 
           "Blues", "ID3V2.3 Title", null, "295", "1", null},
      {"/sdcard/media_api/metaDataTestMedias/MP3/Corrupted_ID3V2_TIT.mp3", null, null, null,
          null, null, null, null, null, null, "295", "1", null}
  };

  //output recorded video

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
  
  public static final String INVALD_VIDEO_PATH = "/sdcard/media_api/filepathdoesnotexist" +
      "/filepathdoesnotexist/temp.3gp";
  
 
  public static final long RECORDED_TIME = 5000;
  public static final long VALID_VIDEO_DURATION = 2000;

  //Streaming test files
  public static final byte [] STREAM_SERVER = new byte[] {(byte)75,(byte)17,(byte)48,(byte)204};
  public static final String STREAM_H264_480_360_1411k = 
      "http://75.17.48.204:10088/yslau/stress_media/h264_regular.mp4";
  public static final String STREAM_WMV = 
      "http://75.17.48.204:10088/yslau/stress_media/bugs.wmv";
  public static final String STREAM_H263_176x144_325k = 
      "http://75.17.48.204:10088/yslau/stress_media/h263_regular.3gp";
  public static final String STREAM_H264_352x288_1536k = 
      "http://75.17.48.204:10088/yslau/stress_media/h264_highBitRate.mp4";
  public static final String STREAM_MP3= 
      "http://75.17.48.204:10088/yslau/stress_media/mp3_regular.mp3";
  public static final String STREAM_MPEG4_QVGA_128k = 
      "http://75.17.48.204:10088/yslau/stress_media/mpeg4_qvga_24fps.3gp";
  public static final int STREAM_H264_480_360_1411k_DURATION = 46000;
  public static final int VIDEO_H263_AAC_DURATION = 501000;
  public static final int VIDEO_H263_AMR_DURATION = 502000;
}
