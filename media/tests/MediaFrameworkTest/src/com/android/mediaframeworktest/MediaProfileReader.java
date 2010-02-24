package com.android.mediaframeworktest;

import android.media.MediaRecorder;
import android.media.EncoderCapabilities;
import android.media.EncoderCapabilities.VideoEncoderCap;
import android.media.EncoderCapabilities.AudioEncoderCap;
import android.media.DecoderCapabilities;
import android.media.DecoderCapabilities.VideoDecoder;
import android.media.DecoderCapabilities.AudioDecoder;

import android.os.SystemProperties;
import java.util.List;
import java.util.HashMap;

public class MediaProfileReader
{
    private static final List<VideoDecoder> videoDecoders = DecoderCapabilities.getVideoDecoders();
    private static final List<AudioDecoder> audioDecoders = DecoderCapabilities.getAudioDecoders();
    private static final List<VideoEncoderCap> videoEncoders = EncoderCapabilities.getVideoEncoders();
    private static final List<AudioEncoderCap> audioEncoders = EncoderCapabilities.getAudioEncoders();
    private static final HashMap<Integer, String> encoderMap = new HashMap<Integer, String>();

    static {
        initEncoderMap();
    };

    public static List<VideoEncoderCap> getVideoEncoders() {
        return videoEncoders;
    }

    public static List<AudioEncoderCap> getAudioEncoders() {
        return audioEncoders;
    }

    public static String getDeviceType() {
        // push all the property into one big table
        String s;
        s = SystemProperties.get("ro.product.name");
        return s;
    }

    public static boolean getWMAEnable() {
        for (AudioDecoder decoder: audioDecoders) {
            if (decoder == AudioDecoder.AUDIO_DECODER_WMA) {
                return true;
            }
        }
        return false;
    }

    public static boolean getWMVEnable(){
        for (VideoDecoder decoder: videoDecoders) {
            if (decoder == VideoDecoder.VIDEO_DECODER_WMV) {
                return true;
            }
        }
        return false;
    }

    public static String getVideoCodecName(int videoEncoder) {
        if (videoEncoder != MediaRecorder.VideoEncoder.H263 &&
            videoEncoder != MediaRecorder.VideoEncoder.H264 &&
            videoEncoder != MediaRecorder.VideoEncoder.MPEG_4_SP) {
            throw new IllegalArgumentException("Unsupported video encoder " + videoEncoder);
        }
        return encoderMap.get(videoEncoder);
    }

    public static String getAudioCodecName(int audioEncoder) {
        if (audioEncoder != MediaRecorder.AudioEncoder.AMR_NB &&
            audioEncoder != MediaRecorder.AudioEncoder.AMR_WB &&
            audioEncoder != MediaRecorder.AudioEncoder.AAC &&
            audioEncoder != MediaRecorder.AudioEncoder.AAC_PLUS &&
            audioEncoder != MediaRecorder.AudioEncoder.EAAC_PLUS) {
            throw new IllegalArgumentException("Unsupported audio encodeer " + audioEncoder);
        }
        return encoderMap.get(audioEncoder);
    }

    private MediaProfileReader() {} // Don't call me

    private static void initEncoderMap() {
        // video encoders
        encoderMap.put(MediaRecorder.VideoEncoder.H263, "h263");
        encoderMap.put(MediaRecorder.VideoEncoder.H264, "h264");
        encoderMap.put(MediaRecorder.VideoEncoder.MPEG_4_SP, "m4v");

        // audio encoders
        encoderMap.put(MediaRecorder.AudioEncoder.AMR_NB, "amrnb");
        encoderMap.put(MediaRecorder.AudioEncoder.AMR_WB, "amrwb");
        encoderMap.put(MediaRecorder.AudioEncoder.AAC, "aac");
        encoderMap.put(MediaRecorder.AudioEncoder.AAC_PLUS, "aacplus");
        encoderMap.put(MediaRecorder.AudioEncoder.EAAC_PLUS, "eaacplus");
    }
}
