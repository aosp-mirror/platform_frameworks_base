package com.android.mediaframeworktest;

import android.media.MediaRecorder;
import android.os.SystemProperties;
import java.util.HashMap;

public class MediaProfileReader {

    public static final HashMap<String, Integer>
    OUTPUT_FORMAT_TABLE = new HashMap<String, Integer>();
    public static String MEDIA_ENC_VID = "ro.media.enc.vid.";
    public static String MEDIA_AUD_VID = "ro.media.enc.aud.";
    public static String[] VIDEO_ENCODER_PROPERTY = {".width", ".height", ".bps", ".fps",};
    public static String[] AUDIO_ENCODER_PROPERTY = {".bps", ".hz", ".ch",};

    public static String getVideoCodecProperty() {
        String s;
        s = SystemProperties.get("ro.media.enc.vid.codec");
        return s;
    }

    public static String getAudioCodecProperty() {
        String s;
        s = SystemProperties.get("ro.media.enc.aud.codec");
        return s;
    }

    public static String getDeviceType() {
        // push all the property into one big table
        String s;
        s = SystemProperties.get("ro.product.name");
        return s;
    }

    public static boolean getWMAEnable() {
        // push all the property into one big table
        int wmaEnable = 1;
        wmaEnable = SystemProperties.getInt("ro.media.dec.aud.wma.enabled",
                wmaEnable);
        if (wmaEnable == 1) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean getWMVEnable(){
        int wmvEnable = 1;
        wmvEnable = SystemProperties.getInt("ro.media.dec.vid.wmv.enabled",
                wmvEnable);
        if (wmvEnable == 1) {
            return true;
        } else {
            return false;
        }
    }

    public static void createVideoProfileTable() {
        // push all the property into one big table
        String encoderType = getVideoCodecProperty();
        if (encoderType.length() != 0) {
            String encoder[] = encoderType.split(",");
            for (int i = 0; i < encoder.length; i++) {
                for (int j = 0; j < VIDEO_ENCODER_PROPERTY.length; j++) {
                    String propertyName = MEDIA_ENC_VID + encoder[i] + VIDEO_ENCODER_PROPERTY[j];
                    String prop = SystemProperties.get(propertyName);
                    // push to the table
                    String propRange[] = prop.split(",");
                    OUTPUT_FORMAT_TABLE.put((encoder[i] + VIDEO_ENCODER_PROPERTY[j] + "_low"),
                            Integer.parseInt(propRange[0]));
                    OUTPUT_FORMAT_TABLE.put((encoder[i] + VIDEO_ENCODER_PROPERTY[j] + "_high"),
                            Integer.parseInt(propRange[1]));
                }

            }
        }
    }

    public static void createAudioProfileTable() {
        // push all the property into one big table
        String audioType = getAudioCodecProperty();
        String encoder[] = audioType.split(",");
        if (audioType.length() != 0) {
            for (int i = 0; i < encoder.length; i++) {
                for (int j = 0; j < AUDIO_ENCODER_PROPERTY.length; j++) {
                    String propertyName = MEDIA_AUD_VID + encoder[i] + AUDIO_ENCODER_PROPERTY[j];
                    String prop = SystemProperties.get(propertyName);
                    // push to the table
                    String propRange[] = prop.split(",");
                    OUTPUT_FORMAT_TABLE.put((encoder[i] + AUDIO_ENCODER_PROPERTY[j] + "_low"),
                            Integer.parseInt(propRange[0]));
                    OUTPUT_FORMAT_TABLE.put((encoder[i] + AUDIO_ENCODER_PROPERTY[j] + "_high"),
                            Integer.parseInt(propRange[1]));
                }
            }
        }
    }

    public static void createEncoderTable(){
        OUTPUT_FORMAT_TABLE.put("h263", MediaRecorder.VideoEncoder.H263);
        OUTPUT_FORMAT_TABLE.put("h264", MediaRecorder.VideoEncoder.H264);
        OUTPUT_FORMAT_TABLE.put("m4v", MediaRecorder.VideoEncoder.MPEG_4_SP);
        OUTPUT_FORMAT_TABLE.put("amrnb", MediaRecorder.AudioEncoder.AMR_NB);
        OUTPUT_FORMAT_TABLE.put("amrwb", MediaRecorder.AudioEncoder.AMR_WB);
        OUTPUT_FORMAT_TABLE.put("aac", MediaRecorder.AudioEncoder.AAC);
        OUTPUT_FORMAT_TABLE.put("aacplus", MediaRecorder.AudioEncoder.AAC_PLUS);
        OUTPUT_FORMAT_TABLE.put("eaacplus",
                MediaRecorder.AudioEncoder.EAAC_PLUS);
    }
}
