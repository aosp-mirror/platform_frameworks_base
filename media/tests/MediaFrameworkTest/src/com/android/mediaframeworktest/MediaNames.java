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
  
    //Audio files
    public static final String MP3CBR = "/sdcard/music/MP3CBR.mp3";  
    public static final String MP3VBR = "/sdcard/music/MP3VBR.mp3";
    public static final String SHORTMP3 = "/sdcard/music/SHORTMP3.mp3";
    public static final String MIDI = "/sdcard/music/MIDI.mid";
    public static final String WMA9 = "/sdcard/music/WMA9.wma";
    public static final String WMA10 = "/sdcard/music/WMA10.wma";
    public static final String WAV = "/sdcard/music/complicated_wav.wav";
    public static final String AMR = "/sdcard/music/AMRNB.amr";
    public static final String OGG = "/sdcard/music/Mists_of_Time-4T.ogg";
    public static final String OGGSHORT = "/sdcard/music/Skippy.ogg";
  
    public static final int MP3CBR_LENGTH = 231116;  
    public static final int MP3VBR_LENGTH = 126407;
    public static final int SHORTMP3_LENGTH = 286;
    public static final int MIDI_LENGTH = 210528;
    public static final int WMA9_LENGTH = 126559;
    public static final int WMA10_LENGTH = 126559;
    public static final int AMR_LENGTH = 126540;
    public static final int OGG_LENGTH = 40000;
    public static final int SEEK_TIME = 10000;
  
    public static final long PAUSE_WAIT_TIME = 3000;
    public static final long WAIT_TIME = 2000;
    public static final long WAIT_LONG = 4000;
  
    //Streaming Video
    public static final String VIDEO_HTTP3GP = "http://pvs.pv.com/jj/lipsync0.3gp";  
    public static final String VIDEO_RTSP3GP = "rtsp://63.241.31.203/public/jj/md.3gp";
    public static final String VIDEO_RTSP3GP2 = "rtsp://pvs.pv.com/public/live_dvd1.3gp";
    public static final String VIDEO_RTSP3GP3 = 
      "rtsp://ehug.rtsp-youtube.l.google.com/" +
      "Ci4LENy73wIaJQmeRVCJq4HuQBMYDSANFEIJbXYtZ29vZ2xlSARSB2RldGFpbHMM/0/0/0/video.3gp";
    //public static final String VIDEO_RTSP3GP = "rtsp://193.159.241.21/sp/alizee05.3gp";
  
    //local video
    public static final String VIDEO_MP4 = "/sdcard/video/gingerkids.MP4";
    public static final String VIDEO_LONG_3GP = "/sdcard/video/radiohead.3gp";
    public static final String VIDEO_SHORT_3GP = "/sdcard/video/short.3gp";
    public static final String VIDEO_LARGE_SIZE_3GP = "/sdcard/video/border_large.3gp";
    public static final String VIDEO_H263_AAC = "/sdcard/video/H263_AAC.3gp";
    public static final String VIDEO_H263_AMR = "/sdcard/video/H263_AMR.3gp";		
    public static final String VIDEO_H264_AAC = "/sdcard/video/H264_AAC.3gp";
    public static final String VIDEO_H264_AMR = "/sdcard/video/H264_AMR.3gp";   
    public static final String VIDEO_WMV = "/sdcard/video/bugs.wmv";
    public static final String VIDEO_HIGHRES_H263 = "/sdcard/video/h263_qcif_30fps.3gp";
    public static final String VIDEO_HIGHRES_MP4 = "/sdcard/video/mpeg4_qvga_24fps.3gp";
    
    //ringtone
    public static final String ringtone = "/sdcard/ringtones/F1_NewVoicemail.mp3";
  
    //streaming mp3
    public static final String STREAM_LARGE_MP3 = 
      "http://wms.pv.com:7070/MediaDownloadContent/mp3/BuenaVista_04_Pueblo_Nuevo.mp3";
    public static final String STREAM_SMALL_MP3 = 
      "http://wms.pv.com:7070/MediaDownloadContent/mp3/ID3V2_TestFile.mp3"; 
    public static final String STREAM_REGULAR_MP3 = 
      "http://wms.pv.com:7070/MediaDownloadContent/mp3/ElectricCosmo.mp3";
  
    //streaming mp3
    public static final String STREAM_MP3_1 = 
      "http://wms.pv.com:7070/MediaDownloadContent/mp3/chadthi_jawani_128kbps.mp3";
    public static final String STREAM_MP3_2 = 
      "http://wms.pv.com:7070/MediaDownloadContent/mp3/dualStereo.mp3";
    public static final String STREAM_MP3_3 = 
      "http://wms.pv.com:7070/mediadownloadcontent/UserUploads/15%20Keep%20Holding%20On.mp3";
    public static final String STREAM_MP3_4 = 
      "http://wms.pv.com:7070/mediadownloadcontent/UserUploads/1%20-%20Apologize.mp3";
    public static final String STREAM_MP3_5 = 
      "http://wms.pv.com:7070/mediadownloadcontent/UserUploads/" +
      "03%20You're%20Gonna%20Miss%20This.mp3";
    public static final String STREAM_MP3_6 = 
      "http://wms.pv.com:7070/mediadownloadcontent/UserUploads" +
      "/02%20Looney%20Tunes%20%C3%82%C2%B7%20Light%20Cavalry%20Overture%20(LP%20Version).mp3";
    public static final String STREAM_MP3_7 = 
      "http://wms.pv.com:7070/mediadownloadcontent/UserUploads" +
      "/01%20Love%20Song%20(Album%20Version).mp3";
    public static final String STREAM_MP3_8 = 
      "http://wms.pv.com:7070/MediaDownloadContent/UserUploads/1%20-%20Apologize.mp3";
    public static final String STREAM_MP3_9 = 
      "http://wms.pv.com:7070/MediaDownloadContent/UserUploads" +
      "/1%20-%20Smile%20(Explicit%20Version).mp3";
    public static final String STREAM_MP3_10 = 
      "http://wms.pv.com:7070/MediaDownloadContent/UserUploads/beefcake.mp3";

    //Sonivox
    public static String MIDIFILES[] = { "/sdcard/music/Leadsol.mxmf",
        "/sdcard/music/abba.imy", "/sdcard/music/ants.mid",
        "/sdcard/music/greensleeves.rtttl", "/sdcard/music/test.ota"};
  
    //Performance measurement
    public static String[] WAVFILES = { "/sdcard/music_perf/WAV/M1F1-AlawWE-AFsp.wav",
        "/sdcard/music_perf/WAV/M1F1-float64-AFsp.wav",
        "/sdcard/music_perf/WAV/song.wav",
        "/sdcard/music_perf/WAV/WAVEtest.wav",
        "/sdcard/music_perf/WAV/WAVEtest_out.wav",
        "/sdcard/music_perf/WAV/test_out.wav"};
                    		
    public static String[] AMRNBFILES = { 
        "/sdcard/music_perf/AMR/AI_AMR-NB_5.9kbps_6.24kbps_8khz_mono_NMC.amr",
        "/sdcard/music_perf/AMR/AI_AMR-NB_5.15kbps_5.46kbps_8khz_mono_NMC.amr",
        "/sdcard/music_perf/AMR/AI_AMR-NB_7.4kbps_7.80kbps_8khz_mono_NMC.amr",
        "/sdcard/music_perf/AMR/AI_AMR-NB_7.95kbps_9.6kbps_8khz_mono_NMC.amr",
        "/sdcard/music_perf/AMR/AI_AMR-NB_10.2kbps_10.48kbps_8khz_mono_NMC.amr"};
  
    public static String[] AMRWBFILES = { 
        "/sdcard/music_perf/AMRWB/NIN_AMR-WB_15.85kbps_16kbps.amr",
        "/sdcard/music_perf/AMRWB/NIN_AMR-WB_18.25kbps_18kbps.amr",
        "/sdcard/music_perf/AMRWB/NIN_AMR-WB_19.85kbps_20kbps.amr",
        "/sdcard/music_perf/AMRWB/NIN_AMR-WB_23.05kbps_23kbps.amr",
        "/sdcard/music_perf/AMRWB/NIN_AMR-WB_23.85kbps_24kbps.amr",
        "/sdcard/music_perf/AMRWB/PD_AMR-WB_19.85kbps_20kbps.amr",
        "/sdcard/music_perf/AMRWB/PD_AMR-WB_23.05kbps_23kbps.amr",
        "/sdcard/music_perf/AMRWB/PD_AMR-WB_23.85kbps_24kbps.amr",
        "/sdcard/music_perf/AMRWB/WC_AMR-WB_23.05kbps_23kbps.amr",
        "/sdcard/music_perf/AMRWB/WC_AMR-WB_23.85kbps_24kbps.amr", };
 
    public static String[] MP3FILES = { 
        "/sdcard/music_perf/MP3/NIN_56kbps_32khz_stereo_VBR_MCA.MP3",
        "/sdcard/music_perf/MP3/NIN_80kbps_32khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_80kbps_44.1khz_stereo_VBR_MCA.mp3", 
        "/sdcard/music_perf/MP3/NIN_80kbps_48khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_112kbps_32khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_112kbps_44.1khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_112kbps_48khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_192kbps_32khz_mono_CBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_192kbps_44.1khz_mono_CBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_192kbps_48khz_mono_CBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_256kbps_44.1khz_mono_CBR_MCA.mp3",
        "/sdcard/music_perf/MP3/NIN_256kbps_48khz_mono_CBR_MCA.mp3",
        "/sdcard/music_perf/MP3/PD_112kbps_32khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/PD_112kbps_44.1khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/PD_112kbps_48khz_stereo_VBR_MCA.mp3",
        "/sdcard/music_perf/MP3/PD_192kbps_32khz_mono_CBR_DPA.mp3",
        "/sdcard/music_perf/MP3/PD_256kbps_44.1khz_mono_CBR_DPA.mp3",
        "/sdcard/music_perf/MP3/PD_256kbps_48khz_mono_CBR_MCA.mp3",
        "/sdcard/music_perf/MP3/WC_256kbps_44.1khz_mono_CBR_DPA.mp3",
        "/sdcard/music_perf/MP3/WC_256kbps_48khz_mono_CBR_DPA.mp3",
        "/sdcard/music_perf/regular_album_photo/Apologize.mp3",
        "/sdcard/music_perf/regular_album_photo/Because_Of_You.mp3",
        "/sdcard/music_perf/regular_album_photo/Complicated.mp3",
        "/sdcard/music_perf/regular_album_photo/Glamorous.mp3",
        "/sdcard/music_perf/regular_album_photo/Im_With_You.mp3",
        "/sdcard/music_perf/regular_album_photo/Smile.mp3",
        "/sdcard/music_perf/regular_album_photo/Suddenly_I_See.mp3",
        "/sdcard/music_perf/regular_album_photo/When You Say Nothing At All.mp3",
        "/sdcard/music_perf/regular_album_photo/my_happy_ending.mp3"};
  
    public static String[] AACFILES = { 
        "/sdcard/music_perf/AAC/AI_AAC_24kbps_12khz_Mono_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/AI_AAC_56kbps_22.05khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/AI_AAC_56kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/AI_AAC_56kbps_44.1khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/AI_AAC_80kbps_32khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/AI_AAC_80kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/NIN_AAC_56kbps_22.05khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/NIN_AAC_56kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/NIN_AAC_56kbps_44.1khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/NIN_AAC_80kbps_32khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/NIN_AAC_80kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PD_AAC_56kbps_22.05khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PD_AAC_56kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PD_AAC_56kbps_44.1khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PD_AAC_80kbps_32khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PD_AAC_80kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PV_AAC_56kbps_22.05khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PV_AAC_56kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PV_AAC_56kbps_44.1khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PV_AAC_80kbps_32khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/PV_AAC_80kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/WC_AAC_56kbps_22.05khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/WC_AAC_56kbps_32khz_Stereo_CBR_SSE.mp4",
        "/sdcard/music_perf/AAC/WC_AAC_56kbps_44.1khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/WC_AAC_80kbps_32khz_Stereo_1pCBR_SSE.mp4",
        "/sdcard/music_perf/AAC/WC_AAC_80kbps_32khz_Stereo_CBR_SSE.mp4",      
    };
    
    public static String[] VIDEOFILES = { "/sdcard/video_perf/AI_CTO_Mpeg4_32kbps_10fps_SQCIF_128x96+AAC_8kbps_8khz_mono_QTE.mp4",
      "/sdcard/video_perf/AI_CTO_Mpeg4_32kbps_12fps_SQCIF_128x96+AAC_8kbps_8khz_mono_QTE.mp4",
      "/sdcard/video_perf/AI_CTO_Mpeg4_32kbps_15fps_SQCIF_128x96+AAC_8kbps_8khz_mono_QTE.mp4",
      "/sdcard/video_perf/AI_CTO_Mpeg4_32kbps_5fps_SQCIF_128x96+AAC_8kbps_8khz_mono_QTE.mp4",
      "/sdcard/video_perf/AI_CTO_Mpeg4_32kbps_5fps_SQCIF_128x96+AAC_8kbps_8khz_mono_SSE.mp4",
      "/sdcard/video_perf/AI_CTO_Mpeg4_32kbps_7.5fps_SQCIF_128x96+AAC_8kbps_8khz_mono_QTE.mp4",
      "/sdcard/video_perf/AI_WMV_1024kbps_20fps_QCIF_176x144_noaudio_SSE.wmv",
      "/sdcard/video_perf/AI_WMV_1024kbps_25fps_QCIF_176x144_noaudio_SSE.wmv",
      "/sdcard/video_perf/Chicken.wmv",
      "/sdcard/video_perf/MP_qcif_15fps_100kbps_48kHz_192kbps_30secs.wmv",
      "/sdcard/video_perf/NIN_CTO_H264_123kbps_5fps_QCIF_176x144+AMR_12.2kbps_8khz_mono_QTE.3gp",
      "/sdcard/video_perf/NIN_CTO_H264_96kbps_10.2fps_QCIF_176x144+AMR_12.2kbps_8khz_mono_QTE.3gp",
      "/sdcard/video_perf/NIN_CTO_H264_96kbps_12fps_QCIF_176x144+AMR_12.2kbps_8khz_mono_QTE.3gp",
      "/sdcard/video_perf/NIN_CTO_H264_96kbps_15fps_QCIF_176x144+AMR_12.2kbps_8khz_mono_QTE.3gp",
      "/sdcard/video_perf/NIN_CTO_Mpeg4_123kbps_15fps_QCIF_176x144+AAC_32kbps_22khz_mono_SSE.3gp",
      "/sdcard/video_perf/NIN_CTO_Mpeg4_123kbps_7.5fps_QCIF_176x144+AAC_32kbps_22khz_stereo_SSE.3gp",
      "/sdcard/video_perf/NIN_CTO_Mpeg4_128kbps_10fps_QCIF_176x144+AAC+_32kbps_48khz_stereo_SSE.3gp",
      "/sdcard/video_perf/NIN_CTO_Mpeg4_128kbps_12fps_QCIF_176x144+AAC+_32kbps_48khz_stereo_SSE.3gp",
      "/sdcard/video_perf/NIN_CTO_Mpeg4_128kbps_15fps_QCIF_176x144+AAC+_32kbps_48khz_stereo_SSE.3gp",
      "/sdcard/video_perf/NIN_CTO_Mpeg4_128kbps_5fps_QCIF_176x144+AAC+_32kbps_48khz_stereo_SSE.3gp",
      "/sdcard/video_perf/NIN_CTO_Mpeg4_128kbps_7.5fps_QCIF_176x144+AAC+_32kbps_48khz_stereo_SSE.3gp",
      "/sdcard/video_perf/NIN_H263_128kbps_10fps_QCIF_174x144_noaudio_SSE.mp4",
      "/sdcard/video_perf/NIN_H263_128kbps_15fps_QCIF_174x144_noaudio_SSE.mp4",
      "/sdcard/video_perf/NIN_H263_48kbps_10fps_QCIF_174x144_noaudio_SSE.3gp",
      "/sdcard/video_perf/NIN_H263_48kbps_12fps_QCIF_174x144_noaudio_SSE.3gp",
      "/sdcard/video_perf/NIN_H264_123kbps_15fps_QCIF_176x144+AAC_32kbps_22khz_stereo_SSE.3gp",
      "/sdcard/video_perf/NIN_H264_123kbps_7.5fps_QCIF_176x144+AAC_32kbps_22khz_stereo_SSE.3gp",
      "/sdcard/video_perf/PV_H264_2000kbps_20fps_CIF_352x288+AAC_96kbps_48khz_stereo_SSE.mp4",
      "/sdcard/video_perf/PV_H264_2000kbps_25fps_CIF_352x288+AAC_96kbps_48khz_stereo_SSE.mp4",
      "/sdcard/video_perf/PV_H264_2000kbps_30fps_CIF_352x288+AAC_128kbps_48khz_stereo_SSE.mp4",
      "/sdcard/video_perf/Stevie-1.wmv",
      "/sdcard/video_perf/WC_H264_1600kbps_20fps_QCIF_176x144+AAC_96kbps_48khz_mono_SSE.mp4",
      "/sdcard/video_perf/WC_H264_1600kbps_25fps_QCIF_176x144+AAC_96kbps_48khz_mono_SSE.mp4",
      "/sdcard/video_perf/WC_H264_1600kbps_30fps_QCIF_176x144+AAC_96kbps_48khz_mono_SSE.mp4",
      "/sdcard/video_perf/bugs.wmv",
      "/sdcard/video_perf/niceday.wmv",
      "/sdcard/video_perf/eaglesatopnflpe.wmv",
     
    };
    
    //wma - only support up to wma 9
    public static String[] WMASUPPORTED = {
      "/sdcard/music_perf/WMASUPPORTED/AI_WMA9.2_32kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/AI_WMA9.2_48kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/NIN_WMA9.2_32kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/NIN_WMA9.2_48kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/PD_WMA9.2_32kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/PD_WMA9.2_48kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/PV_WMA9.2_32kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/PV_WMA9.2_48kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/WC_WMA9.2_32kbps_44.1khz_mono_CBR_DPA.wma",
      "/sdcard/music_perf/WMASUPPORTED/WC_WMA9.2_48kbps_44.1khz_mono_CBR_DPA.wma"
      
    };
    
    public static String[] WMAUNSUPPORTED = { 
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_127kbps_48khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_128kbps_44.1khz_stereo_2pVBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_128kbps_48khz_stereo_2pVBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_128kbps_88khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_128kbps_96khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_192kbps_44.1khz_stereo_2pVBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_192kbps_88khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_192kbps_96khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_256kbps_44khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_256kbps_48khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_256kbps_88khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_256kbps_96khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_384kbps_44khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_384kbps_48khz_stereo_CBR_DPA.wma",
      "/sdcard/music_perf/WMAUNSUPPORTED/AI_WMA10_384kbps_88khz_stereo_CBR_DPA.wma"
    };
    
    //Media Recorder
    public static final String RECORDER_OUTPUT = "/sdcard/recorderOutput.amr";
    
    //video thumbnail
    public static final String THUMBNAIL_OUTPUT = "/sdcard/videoThumbnail.png";
    public static final String GOLDEN_THUMBNAIL_OUTPUT = "/sdcard/goldenThumbnail.png";
    
    //Metadata Utility
    public static final String[] THUMBNAIL_CAPTURE_TEST_FILES = {
      "/sdcard/metadata/test.mp4",
      "/sdcard/metadata/test1.3gp",
      "/sdcard/metadata/test2.3gp",
      "/sdcard/metadata/test3.3gp",
      "/sdcard/metadata/test4.3gp",
      "/sdcard/metadata/test5.3gp",
      "/sdcard/metadata/test6.3gp",
      "/sdcard/metadata/test7.3gp",
      "/sdcard/metadata/test8.3gp",
      "/sdcard/metadata/test9.3gp",
      "/sdcard/metadata/test10.3gp",
      "/sdcard/metadata/test11.3gp",
      "/sdcard/metadata/test12.3gp",
      "/sdcard/metadata/test13.3gp",
      "/sdcard/metadata/test14.3gp",
      "/sdcard/metadata/test15.3gp",
      "/sdcard/metadata/test16.3gp",
      "/sdcard/metadata/test17.3gp",
      "/sdcard/metadata/test18.3gp",
      "/sdcard/metadata/test19.3gp",
      "/sdcard/metadata/test20.3gp",
      "/sdcard/metadata/test21.3gp",
      "/sdcard/metadata/test22.3gp",
      "/sdcard/metadata/test23.3gp",
      "/sdcard/metadata/test24.3gp",
      "/sdcard/metadata/test25.3gp",
      "/sdcard/metadata/test26.3gp",
      "/sdcard/metadata/test27.3gp",
      "/sdcard/metadata/test28.3gp",
      "/sdcard/metadata/test29.3gp",
      "/sdcard/metadata/test30.3gp",
      "/sdcard/metadata/test31.3gp",
      "/sdcard/metadata/test32.3gp",
      "/sdcard/metadata/test33.3gp",
      "/sdcard/metadata/test35.mp4",
      "/sdcard/metadata/test36.m4v",
      "/sdcard/metadata/test34.wmv",
      "/sdcard/metadata/test_metadata.mp4",
  };
  
  public static final String[] METADATA_RETRIEVAL_TEST_FILES = {
      // Raw AAC is not supported
      // "/sdcard/test_raw.aac",
      // "/sdcard/test_adts.aac",
      // "/sdcard/test_adif.aac",
      "/sdcard/metadata/test_metadata.mp4",
      "/sdcard/metadata/WMA10.wma",
      "/sdcard/metadata/Leadsol_out.wav",
      "/sdcard/metadata/test_aac.mp4",
      "/sdcard/metadata/test_amr.mp4",
      "/sdcard/metadata/test_avc_amr.mp4",
      "/sdcard/metadata/test_metadata.mp4",
      "/sdcard/metadata/test_vbr.mp3",
      "/sdcard/metadata/test_cbr.mp3",
      "/sdcard/metadata/metadata_test1.mp3",
      "/sdcard/metadata/test33.3gp",
      "/sdcard/metadata/test35.mp4",
      "/sdcard/metadata/test36.m4v",
      "/sdcard/metadata/test_m4v_amr.mp4",
      "/sdcard/metadata/test_h263_amr.mp4",
      "/sdcard/metadata/test34.wmv",
  };
  
  public static final String[] ALBUMART_TEST_FILES = {
      "/sdcard/album_photo/test_22_16_mp3.mp3",
      "/sdcard/album_photo/PD_256kbps_48khz_mono_CBR_MCA.mp3",
      "/sdcard/album_photo/PD_256kbps_44.1khz_mono_CBR_DPA.mp3",
      "/sdcard/album_photo/PD_192kbps_32khz_mono_CBR_DPA.mp3",
      "/sdcard/album_photo/NIN_256kbps_48khz_mono_CBR_MCA.mp3",
      "/sdcard/album_photo/NIN_256kbps_44.1khz_mono_CBR_MCA.mp3",
      "/sdcard/album_photo/NIN_112kbps(96kbps)_48khz_stereo_VBR_MCA.mp3",
      "/sdcard/album_photo/NIN_112kbps(96kbps)_44.1khz_stereo_VBR_MCA.mp3",
      "/sdcard/album_photo/lightGreen1.mp3",
      "/sdcard/album_photo/babyBlue2 1.mp3",
      "/sdcard/album_photo/2-01 01 NIN_56kbps(64kbps)_32khz_stereo_VBR_MCA.mp3",
      "/sdcard/album_photo/02_NIN_112kbps(80kbps)_32khz_stereo_VBR_MCA.mp3",
      "/sdcard/album_photo/No_Woman_No_Cry_128K.wma",
      "/sdcard/album_photo/Beethoven_2.wma",
  };

  //TEST_PATH_1: is a video and contains metadata for key "num-tracks"
  // TEST_PATH_2: any valid media file.
  // TEST_PATH_3: invalid media file
  public static final String TEST_PATH_1 = "/sdcard/metadata/test.mp4";
  public static final String TEST_PATH_3 = "/sdcard/data.txt";
  public static final String TEST_PATH_4 = "somenonexistingpathname";
  public static final String TEST_PATH_5 = "mem://012345";
  
  //Meta data expected result
  //The expected tag result in the following order
  //cd_track_number, album, artist, author, composer, date, genre
  //title, years, duration
  public static final String META_DATA_MP3 [][] = {
      {"/sdcard/metaDataTestMedias/MP3/ID3V1_ID3V2.mp3", "1/10", "ID3V2.3 Album", "ID3V2.3 Artist",
          "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, "Blues",
          "ID3V2.3 Title", "1234", "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/ID3V2.mp3", "1/10", "ID3V2.3 Album", "ID3V2.3 Artist",
          "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, "Blues", 
          "ID3V2.3 Title", "1234", "313", "1"},
      {"/sdcard/metaDataTestMedias/MP3/ID3V1.mp3", null, "test ID3V1 Album", "test ID3V1 Artist",
          null, null, null, null, "test ID3V1 Title", "1234", "231332", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V1.mp3" , null, null, null,
              null, null, null, null, null, null, "231330", "1"},
      //The corrupted TALB field in id3v2 would not switch to id3v1 tag automatically
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TALB.mp3", "01", null, "ID3V2.3 Artist",
          "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, 
          "Blues", "ID3V2.3 Title", "1234", "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TCOM.mp3", "01", "ID3V2.3 Album", 
           "ID3V2.3 Artist", "ID3V2.3 Lyricist", null, null, 
           "Blues", "ID3V2.3 Title", "1234", "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TCOM_2.mp3", "01", "ID3V2.3 Album", 
           "ID3V2.3 Artist", null, null, null, "Blues", "ID3V2.3 Title", "1234", "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TRCK.mp3", "dd", "ID3V2.3 Album", 
           "ID3V2.3 Artist", "ID3V2.3 Lyricist", "ID3V2.3 Composer", null,
           "Blues", "ID3V2.3 Title", "1234", "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TRCK_2.mp3", "01", "ID3V2.3 Album", 
           "ID3V2.3 Artist", null, null, null, null, "ID3V2.3 Title", null, "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TYER.mp3", "01", "ID3V2.3 Album",
           "ID3V2.3 Artist", null, null, null, null, "ID3V2.3 Title", "9999", "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TYER_2.mp3", "01", "ID3V2.3 Album",
           "ID3V2.3 Artist", "ID3V2.3 Lyricist", "ID3V2.3 Composer", null, 
           "Blues", "ID3V2.3 Title", null, "321", "1"},
      {"/sdcard/metaDataTestMedias/MP3/Corrupted_ID3V2_TIT.mp3", null, null, null,
          null, null, null, null, null, null, "577", "1"}
  };

  public static final String META_DATA_OTHERS [][] = {
      {"/sdcard/metaDataTestMedias/3GP/cat.3gp", null, null, null,
          null, null, "20080309T002415.000Z", null,
          null, null, "1404928", "2"},
      {"/sdcard/metaDataTestMedias/AMR/AMR_NB.amr", null, null, null,
          null, null, null, null,
          null, null, "126540", "1"},
      {"/sdcard/metaDataTestMedias/AMRWB/AMR_WB.amr", null, null, null,
          null, null, null, null,
          null, null, "231180", "1"},     
      {"/sdcard/metaDataTestMedias/M4A/Jaws Of Life_ver1.m4a", null, "Suspended Animation", 
          "John Petrucci", null, null, "20070510T125223.000Z", 
          null, null, "2005", "231180", "1"},     
      {"/sdcard/metaDataTestMedias/M4V/sample_iPod.m4v", null, null, 
          null, null, null, "20051220T202015.000Z", 
          null, null, null, "3771392", "2"}, 
      {"/sdcard/metaDataTestMedias/MIDI/MIDI.mid", null, "Suspended Animation", 
          "John Petrucci", null, null, "20070510T125223.000Z", 
          null, null, "2005", "231180", "1"}, 
      {"/sdcard/metaDataTestMedias/MP4/kung_fu_panda_h264.mp4", null, "mp4 album Kung Fu Panda", 
          "mp4 artist Kung Fu Panda", null, null, "20080517T091451.000Z", 
          "Kung Fu Panda", "Kung Fu Panda", "2008", "5667840", "2"},
      {"/sdcard/metaDataTestMedias/OGG/Ring_Classic_02.ogg", null, "Suspended Animation", 
          "John Petrucci", null, null, "20070510T125223.000Z", 
          null, null, "2005", "231180", "1"},
      {"/sdcard/metaDataTestMedias/OGG/When You Say Nothing At All.ogg", 
          null, "Suspended Animation", "John Petrucci", 
          null, null, "20070510T125223.000Z", null, null, "2005", "231180", "1"},
      {"/sdcard/metaDataTestMedias/WAV/Im With You.wav", null, null, 
          null, null, null, null, 
          null, null, null, "224000", "1"},
      {"/sdcard/metaDataTestMedias/WMA/WMA9.wma", "6", "Ten Songs in the Key of Betrayal", 
          "Alien Crime Syndicate", "Alien Crime Syndicate", 
          "wma 9 Composer", "20040521T175729.483Z", 
          "Rock", "Run for the Money", "2004", "134479", "1"},
      {"/sdcard/metaDataTestMedias/WMA/WMA10.wma", "09", "wma 10 Album", 
          "wma 10 Album Artist", "wma 10 Artist", "wma 10 Composer", "20070705T063625.097Z", 
          "Acid Jazz", "wma 10 Title", "2010", "126574", "1"},
      {"/sdcard/metaDataTestMedias/WMV/bugs.wmv", "8", "wmv 9 Album", 
          null, "wmv 9 Artist ", null, "20051122T155247.540Z", 
          null, "Looney Tunes - Hare-Breadth Hurry", "2005", "193482", "2"},
      {"/sdcard/metaDataTestMedias/WMV/clips_ver7.wmv", "50", "wmv 7 Album", 
          null, "Hallau Shoots & Company", null, "20020226T170045.891Z", 
          null, "CODEC Shootout", "1986", "43709", "2"}
  };
  
  //output recorded video
  
  public static final String RECORDED_HVGA_H263 = "/sdcard/HVGA_H263.3gp";
  public static final String RECORDED_QVGA_H263 = "/sdcard/QVGA_H263.3gp";
  public static final String RECORDED_SQVGA_H263 = "/sdcard/SQVGA_H263.3gp";
  public static final String RECORDED_CIF_H263 = "/sdcard/CIF_H263.3gp";
  public static final String RECORDED_QCIF_H263 = "/sdcard/QCIF_H263.3gp";
  
  public static final String RECORDED_HVGA_MP4 = "/sdcard/HVGA_mp4.mp4";
  public static final String RECORDED_QVGA_MP4 = "/sdcard/QVGA_mp4.mp4";
  public static final String RECORDED_SQVGA_MP4 = "/sdcard/SQVGA_mp4.mp4";
  public static final String RECORDED_CIF_MP4 = "/sdcard/CIF_mp4.mp4";
  public static final String RECORDED_QCIF_MP4 = "/sdcard/QCIF_mp4.mp4";
  
  public static final String RECORDED_VIDEO_3GP = "/sdcard/temp.3gp";
  
 
  public static final long RECORDED_TIME = 3000;
  public static final long VALID_VIDEO_DURATION = 2000;
 
  
}
