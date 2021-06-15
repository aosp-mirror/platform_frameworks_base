/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.bluetooth.BluetoothCodecConfig;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.AudioEffect;
import android.media.audiopolicy.AudioMix;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/* IF YOU CHANGE ANY OF THE CONSTANTS IN THIS FILE, DO NOT FORGET
 * TO UPDATE THE CORRESPONDING NATIVE GLUE AND AudioManager.java.
 * THANK YOU FOR YOUR COOPERATION.
 */

/**
 * @hide
 */
@TestApi
public class AudioSystem
{
    private static final boolean DEBUG_VOLUME = false;

    private static final String TAG = "AudioSystem";

    // private constructor to prevent instantiating AudioSystem
    private AudioSystem() {
        throw new UnsupportedOperationException("Trying to instantiate AudioSystem");
    }

    /* These values must be kept in sync with system/audio.h */
    /*
     * If these are modified, please also update Settings.System.VOLUME_SETTINGS
     * and attrs.xml and AudioManager.java.
     */
    /** @hide Used to identify the default audio stream volume */
    @TestApi
    public static final int STREAM_DEFAULT = -1;
    /** @hide Used to identify the volume of audio streams for phone calls */
    public static final int STREAM_VOICE_CALL = 0;
    /** @hide Used to identify the volume of audio streams for system sounds */
    public static final int STREAM_SYSTEM = 1;
    /** @hide Used to identify the volume of audio streams for the phone ring and message alerts */
    public static final int STREAM_RING = 2;
    /** @hide Used to identify the volume of audio streams for music playback */
    public static final int STREAM_MUSIC = 3;
    /** @hide Used to identify the volume of audio streams for alarms */
    public static final int STREAM_ALARM = 4;
    /** @hide Used to identify the volume of audio streams for notifications */
    public static final int STREAM_NOTIFICATION = 5;
    /** @hide
     *  Used to identify the volume of audio streams for phone calls when connected on bluetooth */
    public static final int STREAM_BLUETOOTH_SCO = 6;
    /** @hide Used to identify the volume of audio streams for enforced system sounds in certain
     * countries (e.g camera in Japan) */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int STREAM_SYSTEM_ENFORCED = 7;
    /** @hide Used to identify the volume of audio streams for DTMF tones */
    public static final int STREAM_DTMF = 8;
    /** @hide Used to identify the volume of audio streams exclusively transmitted through the
     *  speaker (TTS) of the device */
    public static final int STREAM_TTS = 9;
    /** @hide Used to identify the volume of audio streams for accessibility prompts */
    public static final int STREAM_ACCESSIBILITY = 10;
    /** @hide Used to identify the volume of audio streams for virtual assistant */
    public static final int STREAM_ASSISTANT = 11;
    /**
     * @hide
     * @deprecated Use {@link #numStreamTypes() instead}
     */
    public static final int NUM_STREAMS = 5;

    /*
     * Framework static final constants that are primitives or Strings
     * accessed by CTS tests or internal applications must be set from methods
     * (or in a static block) to prevent Java compile-time replacement.
     * We set them from methods so they are read from the device framework.
     * Do not un-hide or change to a numeric literal.
     */

    /** Maximum value for AudioTrack channel count
     * @hide
     */
    public static final int OUT_CHANNEL_COUNT_MAX = native_getMaxChannelCount();
    private static native int native_getMaxChannelCount();

    /** Maximum value for sample rate, used by AudioFormat.
     * @hide
     */
    public static final int SAMPLE_RATE_HZ_MAX = native_getMaxSampleRate();
    private static native int native_getMaxSampleRate();

    /** Minimum value for sample rate, used by AudioFormat.
     * @hide
     */
    public static final int SAMPLE_RATE_HZ_MIN = native_getMinSampleRate();
    private static native int native_getMinSampleRate();

    /** @hide */
    public static final int FCC_24 = 24; // fixed channel count 24; do not change.

    // Expose only the getter method publicly so we can change it in the future
    private static final int NUM_STREAM_TYPES = 12;

    /**
     * @hide
     * @return total number of stream types
     */
    @UnsupportedAppUsage
    @TestApi
    public static final int getNumStreamTypes() { return NUM_STREAM_TYPES; }

    /** @hide */
    public static final String[] STREAM_NAMES = new String[] {
        "STREAM_VOICE_CALL",
        "STREAM_SYSTEM",
        "STREAM_RING",
        "STREAM_MUSIC",
        "STREAM_ALARM",
        "STREAM_NOTIFICATION",
        "STREAM_BLUETOOTH_SCO",
        "STREAM_SYSTEM_ENFORCED",
        "STREAM_DTMF",
        "STREAM_TTS",
        "STREAM_ACCESSIBILITY",
        "STREAM_ASSISTANT"
    };

    /**
     * @hide
     * Sets the microphone mute on or off.
     *
     * @param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     * @return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    @UnsupportedAppUsage
    public static native int muteMicrophone(boolean on);

    /**
     * @hide
     * Checks whether the microphone mute is on or off.
     *
     * @return true if microphone is muted, false if it's not
     */
    @UnsupportedAppUsage
    public static native boolean isMicrophoneMuted();

    /* modes for setPhoneState, must match AudioSystem.h audio_mode */
    /** @hide */
    public static final int MODE_INVALID            = -2;
    /** @hide */
    public static final int MODE_CURRENT            = -1;
    /** @hide */
    public static final int MODE_NORMAL             = 0;
    /** @hide */
    public static final int MODE_RINGTONE           = 1;
    /** @hide */
    public static final int MODE_IN_CALL            = 2;
    /** @hide */
    public static final int MODE_IN_COMMUNICATION   = 3;
    /** @hide */
    public static final int MODE_CALL_SCREENING     = 4;
    /** @hide */
    public static final int NUM_MODES               = 5;

    /** @hide */
    public static String modeToString(int mode) {
        switch (mode) {
            case MODE_CURRENT: return "MODE_CURRENT";
            case MODE_IN_CALL: return "MODE_IN_CALL";
            case MODE_IN_COMMUNICATION: return "MODE_IN_COMMUNICATION";
            case MODE_INVALID: return "MODE_INVALID";
            case MODE_NORMAL: return "MODE_NORMAL";
            case MODE_RINGTONE: return "MODE_RINGTONE";
            case MODE_CALL_SCREENING: return "MODE_CALL_SCREENING";
            default: return "unknown mode (" + mode + ")";
        }
    }

    /* Formats for A2DP codecs, must match system/audio-base.h audio_format_t */
    /** @hide */
    public static final int AUDIO_FORMAT_INVALID        = 0xFFFFFFFF;
    /** @hide */
    public static final int AUDIO_FORMAT_DEFAULT        = 0;
    /** @hide */
    public static final int AUDIO_FORMAT_AAC            = 0x04000000;
    /** @hide */
    public static final int AUDIO_FORMAT_SBC            = 0x1F000000;
    /** @hide */
    public static final int AUDIO_FORMAT_APTX           = 0x20000000;
    /** @hide */
    public static final int AUDIO_FORMAT_APTX_HD        = 0x21000000;
    /** @hide */
    public static final int AUDIO_FORMAT_LDAC           = 0x23000000;

    /** @hide */
    @IntDef(flag = false, prefix = "AUDIO_FORMAT_", value = {
            AUDIO_FORMAT_INVALID,
            AUDIO_FORMAT_DEFAULT,
            AUDIO_FORMAT_AAC,
            AUDIO_FORMAT_SBC,
            AUDIO_FORMAT_APTX,
            AUDIO_FORMAT_APTX_HD,
            AUDIO_FORMAT_LDAC }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioFormatNativeEnumForBtCodec {}

    /**
     * @hide
     * Convert audio format enum values to Bluetooth codec values
     */
    public static int audioFormatToBluetoothSourceCodec(
            @AudioFormatNativeEnumForBtCodec int audioFormat) {
        switch (audioFormat) {
            case AUDIO_FORMAT_AAC: return BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC;
            case AUDIO_FORMAT_SBC: return BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC;
            case AUDIO_FORMAT_APTX: return BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX;
            case AUDIO_FORMAT_APTX_HD: return BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD;
            case AUDIO_FORMAT_LDAC: return BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC;
            default:
                Log.e(TAG, "Unknown audio format 0x" + Integer.toHexString(audioFormat)
                        + " for conversion to BT codec");
                return BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID;
        }
    }

    /**
     * @hide
     * Convert a Bluetooth codec to an audio format enum
     * @param btCodec the codec to convert.
     * @return the audio format, or {@link #AUDIO_FORMAT_DEFAULT} if unknown
     */
    public static @AudioFormatNativeEnumForBtCodec int bluetoothCodecToAudioFormat(int btCodec) {
        switch (btCodec) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
                return AudioSystem.AUDIO_FORMAT_SBC;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
                return AudioSystem.AUDIO_FORMAT_AAC;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
                return AudioSystem.AUDIO_FORMAT_APTX;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
                return AudioSystem.AUDIO_FORMAT_APTX_HD;
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:
                return AudioSystem.AUDIO_FORMAT_LDAC;
            default:
                Log.e(TAG, "Unknown BT codec 0x" + Integer.toHexString(btCodec)
                        + " for conversion to audio format");
                // TODO returning DEFAULT is the current behavior, should this return INVALID?
                return AudioSystem.AUDIO_FORMAT_DEFAULT;
        }
    }

    /**
     * @hide
     * Convert a native audio format integer constant to a string.
     */
    public static String audioFormatToString(int audioFormat) {
        switch (audioFormat) {
            case /* AUDIO_FORMAT_INVALID         */ 0xFFFFFFFF:
                return "AUDIO_FORMAT_INVALID";
            case /* AUDIO_FORMAT_DEFAULT         */ 0:
                return "AUDIO_FORMAT_DEFAULT";
            case /* AUDIO_FORMAT_MP3             */ 0x01000000:
                return "AUDIO_FORMAT_MP3";
            case /* AUDIO_FORMAT_AMR_NB          */ 0x02000000:
                return "AUDIO_FORMAT_AMR_NB";
            case /* AUDIO_FORMAT_AMR_WB          */ 0x03000000:
                return "AUDIO_FORMAT_AMR_WB";
            case /* AUDIO_FORMAT_AAC             */ 0x04000000:
                return "AUDIO_FORMAT_AAC";
            case /* AUDIO_FORMAT_HE_AAC_V1       */ 0x05000000:
                return "AUDIO_FORMAT_HE_AAC_V1";
            case /* AUDIO_FORMAT_HE_AAC_V2       */ 0x06000000:
                return "AUDIO_FORMAT_HE_AAC_V2";
            case /* AUDIO_FORMAT_VORBIS          */ 0x07000000:
                return "AUDIO_FORMAT_VORBIS";
            case /* AUDIO_FORMAT_OPUS            */ 0x08000000:
                return "AUDIO_FORMAT_OPUS";
            case /* AUDIO_FORMAT_AC3             */ 0x09000000:
                return "AUDIO_FORMAT_AC3";
            case /* AUDIO_FORMAT_E_AC3           */ 0x0A000000:
                return "AUDIO_FORMAT_E_AC3";
            case /* AUDIO_FORMAT_DTS             */ 0x0B000000:
                return "AUDIO_FORMAT_DTS";
            case /* AUDIO_FORMAT_DTS_HD          */ 0x0C000000:
                return "AUDIO_FORMAT_DTS_HD";
            case /* AUDIO_FORMAT_IEC61937        */ 0x0D000000:
                return "AUDIO_FORMAT_IEC61937";
            case /* AUDIO_FORMAT_DOLBY_TRUEHD    */ 0x0E000000:
                return "AUDIO_FORMAT_DOLBY_TRUEHD";
            case /* AUDIO_FORMAT_EVRC            */ 0x10000000:
                return "AUDIO_FORMAT_EVRC";
            case /* AUDIO_FORMAT_EVRCB           */ 0x11000000:
                return "AUDIO_FORMAT_EVRCB";
            case /* AUDIO_FORMAT_EVRCWB          */ 0x12000000:
                return "AUDIO_FORMAT_EVRCWB";
            case /* AUDIO_FORMAT_EVRCNW          */ 0x13000000:
                return "AUDIO_FORMAT_EVRCNW";
            case /* AUDIO_FORMAT_AAC_ADIF        */ 0x14000000:
                return "AUDIO_FORMAT_AAC_ADIF";
            case /* AUDIO_FORMAT_WMA             */ 0x15000000:
                return "AUDIO_FORMAT_WMA";
            case /* AUDIO_FORMAT_WMA_PRO         */ 0x16000000:
                return "AUDIO_FORMAT_WMA_PRO";
            case /* AUDIO_FORMAT_AMR_WB_PLUS     */ 0x17000000:
                return "AUDIO_FORMAT_AMR_WB_PLUS";
            case /* AUDIO_FORMAT_MP2             */ 0x18000000:
                return "AUDIO_FORMAT_MP2";
            case /* AUDIO_FORMAT_QCELP           */ 0x19000000:
                return "AUDIO_FORMAT_QCELP";
            case /* AUDIO_FORMAT_DSD             */ 0x1A000000:
                return "AUDIO_FORMAT_DSD";
            case /* AUDIO_FORMAT_FLAC            */ 0x1B000000:
                return "AUDIO_FORMAT_FLAC";
            case /* AUDIO_FORMAT_ALAC            */ 0x1C000000:
                return "AUDIO_FORMAT_ALAC";
            case /* AUDIO_FORMAT_APE             */ 0x1D000000:
                return "AUDIO_FORMAT_APE";
            case /* AUDIO_FORMAT_AAC_ADTS        */ 0x1E000000:
                return "AUDIO_FORMAT_AAC_ADTS";
            case /* AUDIO_FORMAT_SBC             */ 0x1F000000:
                return "AUDIO_FORMAT_SBC";
            case /* AUDIO_FORMAT_APTX            */ 0x20000000:
                return "AUDIO_FORMAT_APTX";
            case /* AUDIO_FORMAT_APTX_HD         */ 0x21000000:
                return "AUDIO_FORMAT_APTX_HD";
            case /* AUDIO_FORMAT_AC4             */ 0x22000000:
                return "AUDIO_FORMAT_AC4";
            case /* AUDIO_FORMAT_LDAC            */ 0x23000000:
                return "AUDIO_FORMAT_LDAC";
            case /* AUDIO_FORMAT_MAT             */ 0x24000000:
                return "AUDIO_FORMAT_MAT";
            case /* AUDIO_FORMAT_AAC_LATM        */ 0x25000000:
                return "AUDIO_FORMAT_AAC_LATM";
            case /* AUDIO_FORMAT_CELT            */ 0x26000000:
                return "AUDIO_FORMAT_CELT";
            case /* AUDIO_FORMAT_APTX_ADAPTIVE   */ 0x27000000:
                return "AUDIO_FORMAT_APTX_ADAPTIVE";
            case /* AUDIO_FORMAT_LHDC            */ 0x28000000:
                return "AUDIO_FORMAT_LHDC";
            case /* AUDIO_FORMAT_LHDC_LL         */ 0x29000000:
                return "AUDIO_FORMAT_LHDC_LL";
            case /* AUDIO_FORMAT_APTX_TWSP       */ 0x2A000000:
                return "AUDIO_FORMAT_APTX_TWSP";

            /* Aliases */
            case /* AUDIO_FORMAT_PCM_16_BIT        */ 0x1:
                return "AUDIO_FORMAT_PCM_16_BIT";        // (PCM | PCM_SUB_16_BIT)
            case /* AUDIO_FORMAT_PCM_8_BIT         */ 0x2:
                return "AUDIO_FORMAT_PCM_8_BIT";        // (PCM | PCM_SUB_8_BIT)
            case /* AUDIO_FORMAT_PCM_32_BIT        */ 0x3:
                return "AUDIO_FORMAT_PCM_32_BIT";        // (PCM | PCM_SUB_32_BIT)
            case /* AUDIO_FORMAT_PCM_8_24_BIT      */ 0x4:
                return "AUDIO_FORMAT_PCM_8_24_BIT";        // (PCM | PCM_SUB_8_24_BIT)
            case /* AUDIO_FORMAT_PCM_FLOAT         */ 0x5:
                return "AUDIO_FORMAT_PCM_FLOAT";        // (PCM | PCM_SUB_FLOAT)
            case /* AUDIO_FORMAT_PCM_24_BIT_PACKED */ 0x6:
                return "AUDIO_FORMAT_PCM_24_BIT_PACKED";        // (PCM | PCM_SUB_24_BIT_PACKED)
            case /* AUDIO_FORMAT_AAC_MAIN          */ 0x4000001:
                return "AUDIO_FORMAT_AAC_MAIN";  // (AAC | AAC_SUB_MAIN)
            case /* AUDIO_FORMAT_AAC_LC            */ 0x4000002:
                return "AUDIO_FORMAT_AAC_LC";  // (AAC | AAC_SUB_LC)
            case /* AUDIO_FORMAT_AAC_SSR           */ 0x4000004:
                return "AUDIO_FORMAT_AAC_SSR";  // (AAC | AAC_SUB_SSR)
            case /* AUDIO_FORMAT_AAC_LTP           */ 0x4000008:
                return "AUDIO_FORMAT_AAC_LTP";  // (AAC | AAC_SUB_LTP)
            case /* AUDIO_FORMAT_AAC_HE_V1         */ 0x4000010:
                return "AUDIO_FORMAT_AAC_HE_V1";  // (AAC | AAC_SUB_HE_V1)
            case /* AUDIO_FORMAT_AAC_SCALABLE      */ 0x4000020:
                return "AUDIO_FORMAT_AAC_SCALABLE";  // (AAC | AAC_SUB_SCALABLE)
            case /* AUDIO_FORMAT_AAC_ERLC          */ 0x4000040:
                return "AUDIO_FORMAT_AAC_ERLC";  // (AAC | AAC_SUB_ERLC)
            case /* AUDIO_FORMAT_AAC_LD            */ 0x4000080:
                return "AUDIO_FORMAT_AAC_LD";  // (AAC | AAC_SUB_LD)
            case /* AUDIO_FORMAT_AAC_HE_V2         */ 0x4000100:
                return "AUDIO_FORMAT_AAC_HE_V2";  // (AAC | AAC_SUB_HE_V2)
            case /* AUDIO_FORMAT_AAC_ELD           */ 0x4000200:
                return "AUDIO_FORMAT_AAC_ELD";  // (AAC | AAC_SUB_ELD)
            case /* AUDIO_FORMAT_AAC_XHE           */ 0x4000300:
                return "AUDIO_FORMAT_AAC_XHE";  // (AAC | AAC_SUB_XHE)
            case /* AUDIO_FORMAT_AAC_ADTS_MAIN     */ 0x1e000001:
                return "AUDIO_FORMAT_AAC_ADTS_MAIN"; // (AAC_ADTS | AAC_SUB_MAIN)
            case /* AUDIO_FORMAT_AAC_ADTS_LC       */ 0x1e000002:
                return "AUDIO_FORMAT_AAC_ADTS_LC"; // (AAC_ADTS | AAC_SUB_LC)
            case /* AUDIO_FORMAT_AAC_ADTS_SSR      */ 0x1e000004:
                return "AUDIO_FORMAT_AAC_ADTS_SSR"; // (AAC_ADTS | AAC_SUB_SSR)
            case /* AUDIO_FORMAT_AAC_ADTS_LTP      */ 0x1e000008:
                return "AUDIO_FORMAT_AAC_ADTS_LTP"; // (AAC_ADTS | AAC_SUB_LTP)
            case /* AUDIO_FORMAT_AAC_ADTS_HE_V1    */ 0x1e000010:
                return "AUDIO_FORMAT_AAC_ADTS_HE_V1"; // (AAC_ADTS | AAC_SUB_HE_V1)
            case /* AUDIO_FORMAT_AAC_ADTS_SCALABLE */ 0x1e000020:
                return "AUDIO_FORMAT_AAC_ADTS_SCALABLE"; // (AAC_ADTS | AAC_SUB_SCALABLE)
            case /* AUDIO_FORMAT_AAC_ADTS_ERLC     */ 0x1e000040:
                return "AUDIO_FORMAT_AAC_ADTS_ERLC"; // (AAC_ADTS | AAC_SUB_ERLC)
            case /* AUDIO_FORMAT_AAC_ADTS_LD       */ 0x1e000080:
                return "AUDIO_FORMAT_AAC_ADTS_LD"; // (AAC_ADTS | AAC_SUB_LD)
            case /* AUDIO_FORMAT_AAC_ADTS_HE_V2    */ 0x1e000100:
                return "AUDIO_FORMAT_AAC_ADTS_HE_V2"; // (AAC_ADTS | AAC_SUB_HE_V2)
            case /* AUDIO_FORMAT_AAC_ADTS_ELD      */ 0x1e000200:
                return "AUDIO_FORMAT_AAC_ADTS_ELD"; // (AAC_ADTS | AAC_SUB_ELD)
            case /* AUDIO_FORMAT_AAC_ADTS_XHE      */ 0x1e000300:
                return "AUDIO_FORMAT_AAC_ADTS_XHE"; // (AAC_ADTS | AAC_SUB_XHE)
            case /* AUDIO_FORMAT_AAC_LATM_LC       */ 0x25000002:
                return "AUDIO_FORMAT_AAC_LATM_LC"; // (AAC_LATM | AAC_SUB_LC)
            case /* AUDIO_FORMAT_AAC_LATM_HE_V1    */ 0x25000010:
                return "AUDIO_FORMAT_AAC_LATM_HE_V1"; // (AAC_LATM | AAC_SUB_HE_V1)
            case /* AUDIO_FORMAT_AAC_LATM_HE_V2    */ 0x25000100:
                return "AUDIO_FORMAT_AAC_LATM_HE_V2"; // (AAC_LATM | AAC_SUB_HE_V2)
            case /* AUDIO_FORMAT_E_AC3_JOC         */ 0xA000001:
                return "AUDIO_FORMAT_E_AC3_JOC";  // (E_AC3 | E_AC3_SUB_JOC)
            case /* AUDIO_FORMAT_MAT_1_0           */ 0x24000001:
                return "AUDIO_FORMAT_MAT_1_0"; // (MAT | MAT_SUB_1_0)
            case /* AUDIO_FORMAT_MAT_2_0           */ 0x24000002:
                return "AUDIO_FORMAT_MAT_2_0"; // (MAT | MAT_SUB_2_0)
            case /* AUDIO_FORMAT_MAT_2_1           */ 0x24000003:
                return "AUDIO_FORMAT_MAT_2_1"; // (MAT | MAT_SUB_2_1)
            case /* AUDIO_FORMAT_DTS_UHD */           0x2E000000:
                return "AUDIO_FORMAT_DTS_UHD";
            case /* AUDIO_FORMAT_DRA */           0x2F000000:
                return "AUDIO_FORMAT_DRA";
            default:
                return "AUDIO_FORMAT_(" + audioFormat + ")";
        }
    }

    /* Routing bits for the former setRouting/getRouting API */
    /** @hide @deprecated */
    @Deprecated public static final int ROUTE_EARPIECE          = (1 << 0);
    /** @hide @deprecated */
    @Deprecated public static final int ROUTE_SPEAKER           = (1 << 1);
    /** @hide @deprecated use {@link #ROUTE_BLUETOOTH_SCO} */
    @Deprecated public static final int ROUTE_BLUETOOTH = (1 << 2);
    /** @hide @deprecated */
    @Deprecated public static final int ROUTE_BLUETOOTH_SCO     = (1 << 2);
    /** @hide @deprecated */
    @Deprecated public static final int ROUTE_HEADSET           = (1 << 3);
    /** @hide @deprecated */
    @Deprecated public static final int ROUTE_BLUETOOTH_A2DP    = (1 << 4);
    /** @hide @deprecated */
    @Deprecated public static final int ROUTE_ALL               = 0xFFFFFFFF;

    // Keep in sync with system/media/audio/include/system/audio.h
    /**  @hide */
    public static final int AUDIO_SESSION_ALLOCATE = 0;

    /**
     * @hide
     * Checks whether the specified stream type is active.
     *
     * return true if any track playing on this stream is active.
     */
    @UnsupportedAppUsage
    public static native boolean isStreamActive(int stream, int inPastMs);

    /**
     * @hide
     * Checks whether the specified stream type is active on a remotely connected device. The notion
     * of what constitutes a remote device is enforced by the audio policy manager of the platform.
     *
     * return true if any track playing on this stream is active on a remote device.
     */
    public static native boolean isStreamActiveRemotely(int stream, int inPastMs);

    /**
     * @hide
     * Checks whether the specified audio source is active.
     *
     * return true if any recorder using this source is currently recording
     */
    @UnsupportedAppUsage
    public static native boolean isSourceActive(int source);

    /**
     * @hide
     * Returns a new unused audio session ID
     */
    public static native int newAudioSessionId();

    /**
     * @hide
     * Returns a new unused audio player ID
     */
    public static native int newAudioPlayerId();

    /**
     * @hide
     * Returns a new unused audio recorder ID
     */
    public static native int newAudioRecorderId();


    /**
     * @hide
     * Sets a group generic audio configuration parameters. The use of these parameters
     * are platform dependent, see libaudio
     *
     * param keyValuePairs  list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     */
    @UnsupportedAppUsage
    public static native int setParameters(String keyValuePairs);

    /**
     * @hide
     * Gets a group generic audio configuration parameters. The use of these parameters
     * are platform dependent, see libaudio
     *
     * param keys  list of parameters
     * return value: list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     */
    @UnsupportedAppUsage
    public static native String getParameters(String keys);

    // These match the enum AudioError in frameworks/base/core/jni/android_media_AudioSystem.cpp
    /** @hide Command successful or Media server restarted. see ErrorCallback */
    public static final int AUDIO_STATUS_OK = 0;
    /** @hide Command failed or unspecified audio error.  see ErrorCallback */
    public static final int AUDIO_STATUS_ERROR = 1;
    /** @hide Media server died. see ErrorCallback */
    public static final int AUDIO_STATUS_SERVER_DIED = 100;

    // all accesses must be synchronized (AudioSystem.class)
    private static ErrorCallback sErrorCallback;

    /** @hide
     * Handles the audio error callback.
     */
    public interface ErrorCallback
    {
        /*
         * Callback for audio server errors.
         * param error   error code:
         * - AUDIO_STATUS_OK
         * - AUDIO_STATUS_SERVER_DIED
         * - AUDIO_STATUS_ERROR
         */
        void onError(int error);
    };

    /**
     * @hide
     * Registers a callback to be invoked when an error occurs.
     * @param cb the callback to run
     */
    @UnsupportedAppUsage
    public static void setErrorCallback(ErrorCallback cb)
    {
        synchronized (AudioSystem.class) {
            sErrorCallback = cb;
            if (cb != null) {
                cb.onError(checkAudioFlinger());
            }
        }
    }

    @UnsupportedAppUsage
    private static void errorCallbackFromNative(int error)
    {
        ErrorCallback errorCallback;
        synchronized (AudioSystem.class) {
            errorCallback = sErrorCallback;
        }
        if (errorCallback != null) {
            errorCallback.onError(error);
        }
    }

    /**
     * @hide
     * Handles events from the audio policy manager about dynamic audio policies
     * @see android.media.audiopolicy.AudioPolicy
     */
    public interface DynamicPolicyCallback
    {
        void onDynamicPolicyMixStateUpdate(String regId, int state);
    }

    //keep in sync with include/media/AudioPolicy.h
    private final static int DYNAMIC_POLICY_EVENT_MIX_STATE_UPDATE = 0;

    // all accesses must be synchronized (AudioSystem.class)
    private static DynamicPolicyCallback sDynPolicyCallback;

    /** @hide */
    public static void setDynamicPolicyCallback(DynamicPolicyCallback cb)
    {
        synchronized (AudioSystem.class) {
            sDynPolicyCallback = cb;
            native_register_dynamic_policy_callback();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static void dynamicPolicyCallbackFromNative(int event, String regId, int val)
    {
        DynamicPolicyCallback cb;
        synchronized (AudioSystem.class) {
            cb = sDynPolicyCallback;
        }
        if (cb != null) {
            switch(event) {
                case DYNAMIC_POLICY_EVENT_MIX_STATE_UPDATE:
                    cb.onDynamicPolicyMixStateUpdate(regId, val);
                    break;
                default:
                    Log.e(TAG, "dynamicPolicyCallbackFromNative: unknown event " + event);
            }
        }
    }

    /**
     * @hide
     * Handles events from the audio policy manager about recording events
     * @see android.media.AudioManager.AudioRecordingCallback
     */
    public interface AudioRecordingCallback
    {
        /**
         * Callback for recording activity notifications events
         * @param event
         * @param riid recording identifier
         * @param uid uid of the client app performing the recording
         * @param session
         * @param source
         * @param recordingFormat an array of ints containing respectively the client and device
         *    recording configurations (2*3 ints), followed by the patch handle:
         *    index 0: client format
         *          1: client channel mask
         *          2: client sample rate
         *          3: device format
         *          4: device channel mask
         *          5: device sample rate
         *          6: patch handle
         * @param packName package name of the client app performing the recording. NOT SUPPORTED
         */
        void onRecordingConfigurationChanged(int event, int riid, int uid, int session, int source,
                        int portId, boolean silenced, int[] recordingFormat,
                        AudioEffect.Descriptor[] clienteffects, AudioEffect.Descriptor[] effects,
                        int activeSource, String packName);
    }

    // all accesses must be synchronized (AudioSystem.class)
    private static AudioRecordingCallback sRecordingCallback;

    /** @hide */
    public static void setRecordingCallback(AudioRecordingCallback cb) {
        synchronized (AudioSystem.class) {
            sRecordingCallback = cb;
            native_register_recording_callback();
        }
    }

    /**
     * Callback from native for recording configuration updates.
     * @param event
     * @param riid
     * @param uid
     * @param session
     * @param source
     * @param portId
     * @param silenced
     * @param recordingFormat see
     *     {@link AudioRecordingCallback#onRecordingConfigurationChanged(int, int, int, int, int, \
     int, boolean, int[], AudioEffect.Descriptor[], AudioEffect.Descriptor[], int, String)}
     *     for the description of the record format.
     * @param cleintEffects
     * @param effects
     * @param activeSource
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static void recordingCallbackFromNative(int event, int riid, int uid, int session,
                          int source, int portId, boolean silenced, int[] recordingFormat,
                          AudioEffect.Descriptor[] clientEffects, AudioEffect.Descriptor[] effects,
                          int activeSource) {
        AudioRecordingCallback cb;
        synchronized (AudioSystem.class) {
            cb = sRecordingCallback;
        }

        String clientEffectName =  clientEffects.length == 0 ? "None" : clientEffects[0].name;
        String effectName =  effects.length == 0 ? "None" : effects[0].name;

        if (cb != null) {
            ArrayList<AudioPatch> audioPatches = new ArrayList<>();
            if (AudioManager.listAudioPatches(audioPatches) == AudioManager.SUCCESS) {
                boolean patchFound = false;
                int patchHandle = recordingFormat[6];
                for (AudioPatch patch : audioPatches) {
                    if (patch.id() == patchHandle) {
                        patchFound = true;
                        break;
                    }
                }
                if (!patchFound) {
                    // The cached audio patches in AudioManager is not up-to-date.
                    // Reset audio port generation to ensure callback side can
                    // get up-to-date audio port information.
                    AudioManager.resetAudioPortGeneration();
                }
            }
            // TODO receive package name from native
            cb.onRecordingConfigurationChanged(event, riid, uid, session, source, portId, silenced,
                                        recordingFormat, clientEffects, effects, activeSource, "");
        }
    }

    /**
     * @hide
     * Handles events from the audio policy manager about routing events
     */
    public interface RoutingUpdateCallback {
        /**
         * Callback to notify a routing update event occurred
         */
        void onRoutingUpdated();
    }

    @GuardedBy("AudioSystem.class")
    private static RoutingUpdateCallback sRoutingUpdateCallback;

    /** @hide */
    public static void setRoutingCallback(RoutingUpdateCallback cb) {
        synchronized (AudioSystem.class) {
            sRoutingUpdateCallback = cb;
            native_register_routing_callback();
        }
    }

    private static void routingCallbackFromNative() {
        final RoutingUpdateCallback cb;
        synchronized (AudioSystem.class) {
            cb = sRoutingUpdateCallback;
        }
        if (cb == null) {
            Log.e(TAG, "routing update from APM was not captured");
            return;
        }
        cb.onRoutingUpdated();
    }

    /*
     * Error codes used by public APIs (AudioTrack, AudioRecord, AudioManager ...)
     * Must be kept in sync with frameworks/base/core/jni/android_media_AudioErrors.h
     */
    /** @hide */
    public static final int SUCCESS            = 0;
    /** @hide */
    public static final int ERROR              = -1;
    /** @hide */
    public static final int BAD_VALUE          = -2;
    /** @hide */
    public static final int INVALID_OPERATION  = -3;
    /** @hide */
    public static final int PERMISSION_DENIED  = -4;
    /** @hide */
    public static final int NO_INIT            = -5;
    /** @hide */
    public static final int DEAD_OBJECT        = -6;
    /** @hide */
    public static final int WOULD_BLOCK        = -7;

    /** @hide */
    @IntDef({
            SUCCESS,
            ERROR,
            BAD_VALUE,
            INVALID_OPERATION,
            PERMISSION_DENIED,
            NO_INIT,
            DEAD_OBJECT,
            WOULD_BLOCK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioSystemError {}

    /**
     * @hide
     * Convert an int error value to its String value for readability.
     * Accepted error values are the java AudioSystem errors, matching android_media_AudioErrors.h,
     * which map onto the native status_t type.
     * @param error one of the java AudioSystem errors
     * @return a human-readable string
     */
    public static String audioSystemErrorToString(@AudioSystemError int error) {
        switch(error) {
            case SUCCESS:
                return "SUCCESS";
            case ERROR:
                return "ERROR";
            case BAD_VALUE:
                return "BAD_VALUE";
            case INVALID_OPERATION:
                return "INVALID_OPERATION";
            case PERMISSION_DENIED:
                return "PERMISSION_DENIED";
            case NO_INIT:
                return "NO_INIT";
            case DEAD_OBJECT:
                return "DEAD_OBJECT";
            case WOULD_BLOCK:
                return "WOULD_BLOCK";
            default:
                return ("unknown error:" + error);
        }
    }

    /*
     * AudioPolicyService methods
     */

    //
    // audio device definitions: must be kept in sync with values in system/core/audio.h
    //
    /** @hide */
    public static final int DEVICE_NONE = 0x0;
    // reserved bits
    /** @hide */
    public static final int DEVICE_BIT_IN = 0x80000000;
    /** @hide */
    public static final int DEVICE_BIT_DEFAULT = 0x40000000;
    // output devices, be sure to update AudioManager.java also
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_EARPIECE = 0x1;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_SPEAKER = 0x2;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_WIRED_HEADSET = 0x4;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_WIRED_HEADPHONE = 0x8;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_SCO = 0x10;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_SCO_HEADSET = 0x20;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_SCO_CARKIT = 0x40;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_A2DP = 0x80;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES = 0x100;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER = 0x200;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_AUX_DIGITAL = 0x400;
    /** @hide */
    public static final int DEVICE_OUT_HDMI = DEVICE_OUT_AUX_DIGITAL;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_ANLG_DOCK_HEADSET = 0x800;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_DGTL_DOCK_HEADSET = 0x1000;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_USB_ACCESSORY = 0x2000;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_USB_DEVICE = 0x4000;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_REMOTE_SUBMIX = 0x8000;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_TELEPHONY_TX = 0x10000;
    /** @hide */
    public static final int DEVICE_OUT_LINE = 0x20000;
    /** @hide */
    public static final int DEVICE_OUT_HDMI_ARC = 0x40000;
    /** @hide */
    public static final int DEVICE_OUT_HDMI_EARC = 0x40001;
    /** @hide */
    public static final int DEVICE_OUT_SPDIF = 0x80000;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_FM = 0x100000;
    /** @hide */
    public static final int DEVICE_OUT_AUX_LINE = 0x200000;
    /** @hide */
    public static final int DEVICE_OUT_SPEAKER_SAFE = 0x400000;
    /** @hide */
    public static final int DEVICE_OUT_IP = 0x800000;
    /** @hide */
    public static final int DEVICE_OUT_BUS = 0x1000000;
    /** @hide */
    public static final int DEVICE_OUT_PROXY = 0x2000000;
    /** @hide */
    public static final int DEVICE_OUT_USB_HEADSET = 0x4000000;
    /** @hide */
    public static final int DEVICE_OUT_HEARING_AID = 0x8000000;
    /** @hide */
    public static final int DEVICE_OUT_ECHO_CANCELLER = 0x10000000;
    /** @hide */
    public static final int DEVICE_OUT_BLE_HEADSET = 0x20000000;
    /** @hide */
    public static final int DEVICE_OUT_BLE_SPEAKER = 0x20000001;

    /** @hide */
    public static final int DEVICE_OUT_DEFAULT = DEVICE_BIT_DEFAULT;

    // Deprecated in R because multiple device types are no longer accessed as a bit mask.
    // Removing this will get lint warning about changing hidden apis.
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_ALL_USB = (DEVICE_OUT_USB_ACCESSORY |
                                                  DEVICE_OUT_USB_DEVICE |
                                                  DEVICE_OUT_USB_HEADSET);

    /** @hide */
    public static final Set<Integer> DEVICE_OUT_ALL_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_OUT_ALL_A2DP_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_OUT_ALL_SCO_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_OUT_ALL_USB_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_ALL_HDMI_SYSTEM_AUDIO_AND_SPEAKER_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_OUT_ALL_BLE_SET;
    static {
        DEVICE_OUT_ALL_SET = new HashSet<>();
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_EARPIECE);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_SPEAKER);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_WIRED_HEADSET);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_WIRED_HEADPHONE);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLUETOOTH_SCO);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLUETOOTH_SCO_HEADSET);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLUETOOTH_SCO_CARKIT);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLUETOOTH_A2DP);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_HDMI);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_ANLG_DOCK_HEADSET);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_DGTL_DOCK_HEADSET);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_USB_ACCESSORY);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_USB_DEVICE);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_REMOTE_SUBMIX);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_TELEPHONY_TX);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_LINE);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_HDMI_ARC);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_HDMI_EARC);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_SPDIF);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_FM);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_AUX_LINE);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_SPEAKER_SAFE);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_IP);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BUS);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_PROXY);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_USB_HEADSET);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_HEARING_AID);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_ECHO_CANCELLER);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLE_HEADSET);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_BLE_SPEAKER);
        DEVICE_OUT_ALL_SET.add(DEVICE_OUT_DEFAULT);

        DEVICE_OUT_ALL_A2DP_SET = new HashSet<>();
        DEVICE_OUT_ALL_A2DP_SET.add(DEVICE_OUT_BLUETOOTH_A2DP);
        DEVICE_OUT_ALL_A2DP_SET.add(DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES);
        DEVICE_OUT_ALL_A2DP_SET.add(DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER);

        DEVICE_OUT_ALL_SCO_SET = new HashSet<>();
        DEVICE_OUT_ALL_SCO_SET.add(DEVICE_OUT_BLUETOOTH_SCO);
        DEVICE_OUT_ALL_SCO_SET.add(DEVICE_OUT_BLUETOOTH_SCO_HEADSET);
        DEVICE_OUT_ALL_SCO_SET.add(DEVICE_OUT_BLUETOOTH_SCO_CARKIT);

        DEVICE_OUT_ALL_USB_SET = new HashSet<>();
        DEVICE_OUT_ALL_USB_SET.add(DEVICE_OUT_USB_ACCESSORY);
        DEVICE_OUT_ALL_USB_SET.add(DEVICE_OUT_USB_DEVICE);
        DEVICE_OUT_ALL_USB_SET.add(DEVICE_OUT_USB_HEADSET);

        DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO_SET = new HashSet<>();
        DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO_SET.add(DEVICE_OUT_AUX_LINE);
        DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO_SET.add(DEVICE_OUT_HDMI_ARC);
        DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO_SET.add(DEVICE_OUT_HDMI_EARC);
        DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO_SET.add(DEVICE_OUT_SPDIF);

        DEVICE_ALL_HDMI_SYSTEM_AUDIO_AND_SPEAKER_SET = new HashSet<>();
        DEVICE_ALL_HDMI_SYSTEM_AUDIO_AND_SPEAKER_SET.addAll(DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO_SET);
        DEVICE_ALL_HDMI_SYSTEM_AUDIO_AND_SPEAKER_SET.add(DEVICE_OUT_SPEAKER);

        DEVICE_OUT_ALL_BLE_SET = new HashSet<>();
        DEVICE_OUT_ALL_BLE_SET.add(DEVICE_OUT_BLE_HEADSET);
        DEVICE_OUT_ALL_BLE_SET.add(DEVICE_OUT_BLE_SPEAKER);
    }

    // input devices
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_COMMUNICATION = DEVICE_BIT_IN | 0x1;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_AMBIENT = DEVICE_BIT_IN | 0x2;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_BUILTIN_MIC = DEVICE_BIT_IN | 0x4;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_BLUETOOTH_SCO_HEADSET = DEVICE_BIT_IN | 0x8;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_WIRED_HEADSET = DEVICE_BIT_IN | 0x10;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_AUX_DIGITAL = DEVICE_BIT_IN | 0x20;
    /** @hide */
    public static final int DEVICE_IN_HDMI = DEVICE_IN_AUX_DIGITAL;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_VOICE_CALL = DEVICE_BIT_IN | 0x40;
    /** @hide */
    public static final int DEVICE_IN_TELEPHONY_RX = DEVICE_IN_VOICE_CALL;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_BACK_MIC = DEVICE_BIT_IN | 0x80;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_REMOTE_SUBMIX = DEVICE_BIT_IN | 0x100;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_ANLG_DOCK_HEADSET = DEVICE_BIT_IN | 0x200;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_DGTL_DOCK_HEADSET = DEVICE_BIT_IN | 0x400;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_USB_ACCESSORY = DEVICE_BIT_IN | 0x800;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_USB_DEVICE = DEVICE_BIT_IN | 0x1000;
    /** @hide */
    public static final int DEVICE_IN_FM_TUNER = DEVICE_BIT_IN | 0x2000;
    /** @hide */
    public static final int DEVICE_IN_TV_TUNER = DEVICE_BIT_IN | 0x4000;
    /** @hide */
    public static final int DEVICE_IN_LINE = DEVICE_BIT_IN | 0x8000;
    /** @hide */
    public static final int DEVICE_IN_SPDIF = DEVICE_BIT_IN | 0x10000;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_BLUETOOTH_A2DP = DEVICE_BIT_IN | 0x20000;
    /** @hide */
    public static final int DEVICE_IN_LOOPBACK = DEVICE_BIT_IN | 0x40000;
    /** @hide */
    public static final int DEVICE_IN_IP = DEVICE_BIT_IN | 0x80000;
    /** @hide */
    public static final int DEVICE_IN_BUS = DEVICE_BIT_IN | 0x100000;
    /** @hide */
    public static final int DEVICE_IN_PROXY = DEVICE_BIT_IN | 0x1000000;
    /** @hide */
    public static final int DEVICE_IN_USB_HEADSET = DEVICE_BIT_IN | 0x2000000;
    /** @hide */
    public static final int DEVICE_IN_BLUETOOTH_BLE = DEVICE_BIT_IN | 0x4000000;
    /** @hide */
    public static final int DEVICE_IN_HDMI_ARC = DEVICE_BIT_IN | 0x8000000;
    /** @hide */
    public static final int DEVICE_IN_HDMI_EARC = DEVICE_BIT_IN | 0x8000001;
    /** @hide */
    public static final int DEVICE_IN_ECHO_REFERENCE = DEVICE_BIT_IN | 0x10000000;
    /** @hide */
    public static final int DEVICE_IN_BLE_HEADSET = DEVICE_BIT_IN | 0x20000000;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_IN_DEFAULT = DEVICE_BIT_IN | DEVICE_BIT_DEFAULT;

    /** @hide */
    public static final Set<Integer> DEVICE_IN_ALL_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_IN_ALL_SCO_SET;
    /** @hide */
    public static final Set<Integer> DEVICE_IN_ALL_USB_SET;
    static {
        DEVICE_IN_ALL_SET = new HashSet<>();
        DEVICE_IN_ALL_SET.add(DEVICE_IN_COMMUNICATION);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_AMBIENT);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_BUILTIN_MIC);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_BLUETOOTH_SCO_HEADSET);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_WIRED_HEADSET);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_HDMI);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_TELEPHONY_RX);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_BACK_MIC);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_REMOTE_SUBMIX);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_ANLG_DOCK_HEADSET);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_DGTL_DOCK_HEADSET);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_USB_ACCESSORY);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_USB_DEVICE);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_FM_TUNER);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_TV_TUNER);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_LINE);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_SPDIF);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_BLUETOOTH_A2DP);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_LOOPBACK);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_IP);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_BUS);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_PROXY);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_USB_HEADSET);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_BLUETOOTH_BLE);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_HDMI_ARC);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_HDMI_EARC);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_ECHO_REFERENCE);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_BLE_HEADSET);
        DEVICE_IN_ALL_SET.add(DEVICE_IN_DEFAULT);

        DEVICE_IN_ALL_SCO_SET = new HashSet<>();
        DEVICE_IN_ALL_SCO_SET.add(DEVICE_IN_BLUETOOTH_SCO_HEADSET);

        DEVICE_IN_ALL_USB_SET = new HashSet<>();
        DEVICE_IN_ALL_USB_SET.add(DEVICE_IN_USB_ACCESSORY);
        DEVICE_IN_ALL_USB_SET.add(DEVICE_IN_USB_DEVICE);
        DEVICE_IN_ALL_USB_SET.add(DEVICE_IN_USB_HEADSET);
    }

    /** @hide */
    public static final String LEGACY_REMOTE_SUBMIX_ADDRESS = "0";

    // device states, must match AudioSystem::device_connection_state
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_STATE_UNAVAILABLE = 0;
    /** @hide */
    @UnsupportedAppUsage
    public static final int DEVICE_STATE_AVAILABLE = 1;
    private static final int NUM_DEVICE_STATES = 1;

    /** @hide */
    public static String deviceStateToString(int state) {
        switch (state) {
            case DEVICE_STATE_UNAVAILABLE: return "DEVICE_STATE_UNAVAILABLE";
            case DEVICE_STATE_AVAILABLE: return "DEVICE_STATE_AVAILABLE";
            default: return "unknown state (" + state + ")";
        }
    }

    /** @hide */ public static final String DEVICE_OUT_EARPIECE_NAME = "earpiece";
    /** @hide */ public static final String DEVICE_OUT_SPEAKER_NAME = "speaker";
    /** @hide */ public static final String DEVICE_OUT_WIRED_HEADSET_NAME = "headset";
    /** @hide */ public static final String DEVICE_OUT_WIRED_HEADPHONE_NAME = "headphone";
    /** @hide */ public static final String DEVICE_OUT_BLUETOOTH_SCO_NAME = "bt_sco";
    /** @hide */ public static final String DEVICE_OUT_BLUETOOTH_SCO_HEADSET_NAME = "bt_sco_hs";
    /** @hide */ public static final String DEVICE_OUT_BLUETOOTH_SCO_CARKIT_NAME = "bt_sco_carkit";
    /** @hide */ public static final String DEVICE_OUT_BLUETOOTH_A2DP_NAME = "bt_a2dp";
    /** @hide */
    public static final String DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES_NAME = "bt_a2dp_hp";
    /** @hide */ public static final String DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER_NAME = "bt_a2dp_spk";
    /** @hide */ public static final String DEVICE_OUT_AUX_DIGITAL_NAME = "aux_digital";
    /** @hide */ public static final String DEVICE_OUT_HDMI_NAME = "hdmi";
    /** @hide */ public static final String DEVICE_OUT_ANLG_DOCK_HEADSET_NAME = "analog_dock";
    /** @hide */ public static final String DEVICE_OUT_DGTL_DOCK_HEADSET_NAME = "digital_dock";
    /** @hide */ public static final String DEVICE_OUT_USB_ACCESSORY_NAME = "usb_accessory";
    /** @hide */ public static final String DEVICE_OUT_USB_DEVICE_NAME = "usb_device";
    /** @hide */ public static final String DEVICE_OUT_REMOTE_SUBMIX_NAME = "remote_submix";
    /** @hide */ public static final String DEVICE_OUT_TELEPHONY_TX_NAME = "telephony_tx";
    /** @hide */ public static final String DEVICE_OUT_LINE_NAME = "line";
    /** @hide */ public static final String DEVICE_OUT_HDMI_ARC_NAME = "hmdi_arc";
    /** @hide */ public static final String DEVICE_OUT_HDMI_EARC_NAME = "hmdi_earc";
    /** @hide */ public static final String DEVICE_OUT_SPDIF_NAME = "spdif";
    /** @hide */ public static final String DEVICE_OUT_FM_NAME = "fm_transmitter";
    /** @hide */ public static final String DEVICE_OUT_AUX_LINE_NAME = "aux_line";
    /** @hide */ public static final String DEVICE_OUT_SPEAKER_SAFE_NAME = "speaker_safe";
    /** @hide */ public static final String DEVICE_OUT_IP_NAME = "ip";
    /** @hide */ public static final String DEVICE_OUT_BUS_NAME = "bus";
    /** @hide */ public static final String DEVICE_OUT_PROXY_NAME = "proxy";
    /** @hide */ public static final String DEVICE_OUT_USB_HEADSET_NAME = "usb_headset";
    /** @hide */ public static final String DEVICE_OUT_HEARING_AID_NAME = "hearing_aid_out";
    /** @hide */ public static final String DEVICE_OUT_ECHO_CANCELLER_NAME = "echo_canceller";
    /** @hide */ public static final String DEVICE_OUT_BLE_HEADSET_NAME = "ble_headset";
    /** @hide */ public static final String DEVICE_OUT_BLE_SPEAKER_NAME = "ble_speaker";

    /** @hide */ public static final String DEVICE_IN_COMMUNICATION_NAME = "communication";
    /** @hide */ public static final String DEVICE_IN_AMBIENT_NAME = "ambient";
    /** @hide */ public static final String DEVICE_IN_BUILTIN_MIC_NAME = "mic";
    /** @hide */ public static final String DEVICE_IN_BLUETOOTH_SCO_HEADSET_NAME = "bt_sco_hs";
    /** @hide */ public static final String DEVICE_IN_WIRED_HEADSET_NAME = "headset";
    /** @hide */ public static final String DEVICE_IN_AUX_DIGITAL_NAME = "aux_digital";
    /** @hide */ public static final String DEVICE_IN_TELEPHONY_RX_NAME = "telephony_rx";
    /** @hide */ public static final String DEVICE_IN_BACK_MIC_NAME = "back_mic";
    /** @hide */ public static final String DEVICE_IN_REMOTE_SUBMIX_NAME = "remote_submix";
    /** @hide */ public static final String DEVICE_IN_ANLG_DOCK_HEADSET_NAME = "analog_dock";
    /** @hide */ public static final String DEVICE_IN_DGTL_DOCK_HEADSET_NAME = "digital_dock";
    /** @hide */ public static final String DEVICE_IN_USB_ACCESSORY_NAME = "usb_accessory";
    /** @hide */ public static final String DEVICE_IN_USB_DEVICE_NAME = "usb_device";
    /** @hide */ public static final String DEVICE_IN_FM_TUNER_NAME = "fm_tuner";
    /** @hide */ public static final String DEVICE_IN_TV_TUNER_NAME = "tv_tuner";
    /** @hide */ public static final String DEVICE_IN_LINE_NAME = "line";
    /** @hide */ public static final String DEVICE_IN_SPDIF_NAME = "spdif";
    /** @hide */ public static final String DEVICE_IN_BLUETOOTH_A2DP_NAME = "bt_a2dp";
    /** @hide */ public static final String DEVICE_IN_LOOPBACK_NAME = "loopback";
    /** @hide */ public static final String DEVICE_IN_IP_NAME = "ip";
    /** @hide */ public static final String DEVICE_IN_BUS_NAME = "bus";
    /** @hide */ public static final String DEVICE_IN_PROXY_NAME = "proxy";
    /** @hide */ public static final String DEVICE_IN_USB_HEADSET_NAME = "usb_headset";
    /** @hide */ public static final String DEVICE_IN_BLUETOOTH_BLE_NAME = "bt_ble";
    /** @hide */ public static final String DEVICE_IN_ECHO_REFERENCE_NAME = "echo_reference";
    /** @hide */ public static final String DEVICE_IN_HDMI_ARC_NAME = "hdmi_arc";
    /** @hide */ public static final String DEVICE_IN_HDMI_EARC_NAME = "hdmi_earc";
    /** @hide */ public static final String DEVICE_IN_BLE_HEADSET_NAME = "ble_headset";

    /** @hide */
    @UnsupportedAppUsage
    public static String getOutputDeviceName(int device)
    {
        switch(device) {
        case DEVICE_OUT_EARPIECE:
            return DEVICE_OUT_EARPIECE_NAME;
        case DEVICE_OUT_SPEAKER:
            return DEVICE_OUT_SPEAKER_NAME;
        case DEVICE_OUT_WIRED_HEADSET:
            return DEVICE_OUT_WIRED_HEADSET_NAME;
        case DEVICE_OUT_WIRED_HEADPHONE:
            return DEVICE_OUT_WIRED_HEADPHONE_NAME;
        case DEVICE_OUT_BLUETOOTH_SCO:
            return DEVICE_OUT_BLUETOOTH_SCO_NAME;
        case DEVICE_OUT_BLUETOOTH_SCO_HEADSET:
            return DEVICE_OUT_BLUETOOTH_SCO_HEADSET_NAME;
        case DEVICE_OUT_BLUETOOTH_SCO_CARKIT:
            return DEVICE_OUT_BLUETOOTH_SCO_CARKIT_NAME;
        case DEVICE_OUT_BLUETOOTH_A2DP:
            return DEVICE_OUT_BLUETOOTH_A2DP_NAME;
        case DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES:
            return DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES_NAME;
        case DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER:
            return DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER_NAME;
        case DEVICE_OUT_HDMI:
            return DEVICE_OUT_HDMI_NAME;
        case DEVICE_OUT_ANLG_DOCK_HEADSET:
            return DEVICE_OUT_ANLG_DOCK_HEADSET_NAME;
        case DEVICE_OUT_DGTL_DOCK_HEADSET:
            return DEVICE_OUT_DGTL_DOCK_HEADSET_NAME;
        case DEVICE_OUT_USB_ACCESSORY:
            return DEVICE_OUT_USB_ACCESSORY_NAME;
        case DEVICE_OUT_USB_DEVICE:
            return DEVICE_OUT_USB_DEVICE_NAME;
        case DEVICE_OUT_REMOTE_SUBMIX:
            return DEVICE_OUT_REMOTE_SUBMIX_NAME;
        case DEVICE_OUT_TELEPHONY_TX:
            return DEVICE_OUT_TELEPHONY_TX_NAME;
        case DEVICE_OUT_LINE:
            return DEVICE_OUT_LINE_NAME;
        case DEVICE_OUT_HDMI_ARC:
            return DEVICE_OUT_HDMI_ARC_NAME;
        case DEVICE_OUT_HDMI_EARC:
            return DEVICE_OUT_HDMI_EARC_NAME;
        case DEVICE_OUT_SPDIF:
            return DEVICE_OUT_SPDIF_NAME;
        case DEVICE_OUT_FM:
            return DEVICE_OUT_FM_NAME;
        case DEVICE_OUT_AUX_LINE:
            return DEVICE_OUT_AUX_LINE_NAME;
        case DEVICE_OUT_SPEAKER_SAFE:
            return DEVICE_OUT_SPEAKER_SAFE_NAME;
        case DEVICE_OUT_IP:
            return DEVICE_OUT_IP_NAME;
        case DEVICE_OUT_BUS:
            return DEVICE_OUT_BUS_NAME;
        case DEVICE_OUT_PROXY:
            return DEVICE_OUT_PROXY_NAME;
        case DEVICE_OUT_USB_HEADSET:
            return DEVICE_OUT_USB_HEADSET_NAME;
        case DEVICE_OUT_HEARING_AID:
            return DEVICE_OUT_HEARING_AID_NAME;
        case DEVICE_OUT_ECHO_CANCELLER:
            return DEVICE_OUT_ECHO_CANCELLER_NAME;
        case DEVICE_OUT_BLE_HEADSET:
            return DEVICE_OUT_BLE_HEADSET_NAME;
        case DEVICE_OUT_BLE_SPEAKER:
            return DEVICE_OUT_BLE_SPEAKER_NAME;
        case DEVICE_OUT_DEFAULT:
        default:
            return Integer.toString(device);
        }
    }

    /** @hide */
    public static String getInputDeviceName(int device)
    {
        switch(device) {
        case DEVICE_IN_COMMUNICATION:
            return DEVICE_IN_COMMUNICATION_NAME;
        case DEVICE_IN_AMBIENT:
            return DEVICE_IN_AMBIENT_NAME;
        case DEVICE_IN_BUILTIN_MIC:
            return DEVICE_IN_BUILTIN_MIC_NAME;
        case DEVICE_IN_BLUETOOTH_SCO_HEADSET:
            return DEVICE_IN_BLUETOOTH_SCO_HEADSET_NAME;
        case DEVICE_IN_WIRED_HEADSET:
            return DEVICE_IN_WIRED_HEADSET_NAME;
        case DEVICE_IN_AUX_DIGITAL:
            return DEVICE_IN_AUX_DIGITAL_NAME;
        case DEVICE_IN_TELEPHONY_RX:
            return DEVICE_IN_TELEPHONY_RX_NAME;
        case DEVICE_IN_BACK_MIC:
            return DEVICE_IN_BACK_MIC_NAME;
        case DEVICE_IN_REMOTE_SUBMIX:
            return DEVICE_IN_REMOTE_SUBMIX_NAME;
        case DEVICE_IN_ANLG_DOCK_HEADSET:
            return DEVICE_IN_ANLG_DOCK_HEADSET_NAME;
        case DEVICE_IN_DGTL_DOCK_HEADSET:
            return DEVICE_IN_DGTL_DOCK_HEADSET_NAME;
        case DEVICE_IN_USB_ACCESSORY:
            return DEVICE_IN_USB_ACCESSORY_NAME;
        case DEVICE_IN_USB_DEVICE:
            return DEVICE_IN_USB_DEVICE_NAME;
        case DEVICE_IN_FM_TUNER:
            return DEVICE_IN_FM_TUNER_NAME;
        case DEVICE_IN_TV_TUNER:
            return DEVICE_IN_TV_TUNER_NAME;
        case DEVICE_IN_LINE:
            return DEVICE_IN_LINE_NAME;
        case DEVICE_IN_SPDIF:
            return DEVICE_IN_SPDIF_NAME;
        case DEVICE_IN_BLUETOOTH_A2DP:
            return DEVICE_IN_BLUETOOTH_A2DP_NAME;
        case DEVICE_IN_LOOPBACK:
            return DEVICE_IN_LOOPBACK_NAME;
        case DEVICE_IN_IP:
            return DEVICE_IN_IP_NAME;
        case DEVICE_IN_BUS:
            return DEVICE_IN_BUS_NAME;
        case DEVICE_IN_PROXY:
            return DEVICE_IN_PROXY_NAME;
        case DEVICE_IN_USB_HEADSET:
            return DEVICE_IN_USB_HEADSET_NAME;
        case DEVICE_IN_BLUETOOTH_BLE:
            return DEVICE_IN_BLUETOOTH_BLE_NAME;
        case DEVICE_IN_ECHO_REFERENCE:
            return DEVICE_IN_ECHO_REFERENCE_NAME;
        case DEVICE_IN_HDMI_ARC:
            return DEVICE_IN_HDMI_ARC_NAME;
        case DEVICE_IN_HDMI_EARC:
            return DEVICE_IN_HDMI_EARC_NAME;
        case DEVICE_IN_BLE_HEADSET:
            return DEVICE_IN_BLE_HEADSET_NAME;
        case DEVICE_IN_DEFAULT:
        default:
            return Integer.toString(device);
        }
    }

    /**
     * @hide
     * Returns a human readable name for a given device type
     * @param device a native device type, NOT an AudioDeviceInfo type
     * @return a string describing the device type
     */
    public static @NonNull String getDeviceName(int device) {
        if ((device & DEVICE_BIT_IN) != 0) {
            return getInputDeviceName(device);
        }
        return getOutputDeviceName(device);
    }

    // phone state, match audio_mode???
    /** @hide */ public static final int PHONE_STATE_OFFCALL = 0;
    /** @hide */ public static final int PHONE_STATE_RINGING = 1;
    /** @hide */ public static final int PHONE_STATE_INCALL = 2;

    // device categories config for setForceUse, must match audio_policy_forced_cfg_t
    /** @hide */ @UnsupportedAppUsage public static final int FORCE_NONE = 0;
    /** @hide */ public static final int FORCE_SPEAKER = 1;
    /** @hide */ public static final int FORCE_HEADPHONES = 2;
    /** @hide */ public static final int FORCE_BT_SCO = 3;
    /** @hide */ public static final int FORCE_BT_A2DP = 4;
    /** @hide */ public static final int FORCE_WIRED_ACCESSORY = 5;
    /** @hide */ @UnsupportedAppUsage public static final int FORCE_BT_CAR_DOCK = 6;
    /** @hide */ @UnsupportedAppUsage public static final int FORCE_BT_DESK_DOCK = 7;
    /** @hide */ @UnsupportedAppUsage public static final int FORCE_ANALOG_DOCK = 8;
    /** @hide */ @UnsupportedAppUsage public static final int FORCE_DIGITAL_DOCK = 9;
    /** @hide */ public static final int FORCE_NO_BT_A2DP = 10;
    /** @hide */ public static final int FORCE_SYSTEM_ENFORCED = 11;
    /** @hide */ public static final int FORCE_HDMI_SYSTEM_AUDIO_ENFORCED = 12;
    /** @hide */ public static final int FORCE_ENCODED_SURROUND_NEVER = 13;
    /** @hide */ public static final int FORCE_ENCODED_SURROUND_ALWAYS = 14;
    /** @hide */ public static final int FORCE_ENCODED_SURROUND_MANUAL = 15;
    /** @hide */ public static final int NUM_FORCE_CONFIG = 16;
    /** @hide */ public static final int FORCE_DEFAULT = FORCE_NONE;

    /** @hide */
    public static String forceUseConfigToString(int config) {
        switch (config) {
            case FORCE_NONE: return "FORCE_NONE";
            case FORCE_SPEAKER: return "FORCE_SPEAKER";
            case FORCE_HEADPHONES: return "FORCE_HEADPHONES";
            case FORCE_BT_SCO: return "FORCE_BT_SCO";
            case FORCE_BT_A2DP: return "FORCE_BT_A2DP";
            case FORCE_WIRED_ACCESSORY: return "FORCE_WIRED_ACCESSORY";
            case FORCE_BT_CAR_DOCK: return "FORCE_BT_CAR_DOCK";
            case FORCE_BT_DESK_DOCK: return "FORCE_BT_DESK_DOCK";
            case FORCE_ANALOG_DOCK: return "FORCE_ANALOG_DOCK";
            case FORCE_DIGITAL_DOCK: return "FORCE_DIGITAL_DOCK";
            case FORCE_NO_BT_A2DP: return "FORCE_NO_BT_A2DP";
            case FORCE_SYSTEM_ENFORCED: return "FORCE_SYSTEM_ENFORCED";
            case FORCE_HDMI_SYSTEM_AUDIO_ENFORCED: return "FORCE_HDMI_SYSTEM_AUDIO_ENFORCED";
            case FORCE_ENCODED_SURROUND_NEVER: return "FORCE_ENCODED_SURROUND_NEVER";
            case FORCE_ENCODED_SURROUND_ALWAYS: return "FORCE_ENCODED_SURROUND_ALWAYS";
            case FORCE_ENCODED_SURROUND_MANUAL: return "FORCE_ENCODED_SURROUND_MANUAL";
            default: return "unknown config (" + config + ")" ;
        }
    }

    // usage for setForceUse, must match audio_policy_force_use_t
    /** @hide */ public static final int FOR_COMMUNICATION = 0;
    /** @hide */ public static final int FOR_MEDIA = 1;
    /** @hide */ public static final int FOR_RECORD = 2;
    /** @hide */ public static final int FOR_DOCK = 3;
    /** @hide */ public static final int FOR_SYSTEM = 4;
    /** @hide */ public static final int FOR_HDMI_SYSTEM_AUDIO = 5;
    /** @hide */ public static final int FOR_ENCODED_SURROUND = 6;
    /** @hide */ public static final int FOR_VIBRATE_RINGING = 7;
    private static final int NUM_FORCE_USE = 8;

    // Device role in audio policy
    public static final int DEVICE_ROLE_NONE = 0;
    public static final int DEVICE_ROLE_PREFERRED = 1;
    public static final int DEVICE_ROLE_DISABLED = 2;

    /** @hide */
    public static String forceUseUsageToString(int usage) {
        switch (usage) {
            case FOR_COMMUNICATION: return "FOR_COMMUNICATION";
            case FOR_MEDIA: return "FOR_MEDIA";
            case FOR_RECORD: return "FOR_RECORD";
            case FOR_DOCK: return "FOR_DOCK";
            case FOR_SYSTEM: return "FOR_SYSTEM";
            case FOR_HDMI_SYSTEM_AUDIO: return "FOR_HDMI_SYSTEM_AUDIO";
            case FOR_ENCODED_SURROUND: return "FOR_ENCODED_SURROUND";
            case FOR_VIBRATE_RINGING: return "FOR_VIBRATE_RINGING";
            default: return "unknown usage (" + usage + ")" ;
        }
    }

    /** @hide Wrapper for native methods called from AudioService */
    public static int setStreamVolumeIndexAS(int stream, int index, int device) {
        if (DEBUG_VOLUME) {
            Log.i(TAG, "setStreamVolumeIndex: " + STREAM_NAMES[stream]
                    + " dev=" + Integer.toHexString(device) + " idx=" + index);
        }
        return setStreamVolumeIndex(stream, index, device);
    }

    // usage for AudioRecord.startRecordingSync(), must match AudioSystem::sync_event_t
    /** @hide */ public static final int SYNC_EVENT_NONE = 0;
    /** @hide */ public static final int SYNC_EVENT_PRESENTATION_COMPLETE = 1;
    /** @hide
     *  Not used by native implementation.
     *  See {@link AudioRecord.Builder#setSharedAudioEvent(MediaSyncEvent) */
    public static final int SYNC_EVENT_SHARE_AUDIO_HISTORY = 100;

    /**
     * @hide
     * @return command completion status, one of {@link #AUDIO_STATUS_OK},
     *     {@link #AUDIO_STATUS_ERROR} or {@link #AUDIO_STATUS_SERVER_DIED}
     */
    @UnsupportedAppUsage
    public static native int setDeviceConnectionState(int device, int state,
                                                      String device_address, String device_name,
                                                      int codecFormat);
    /** @hide */
    @UnsupportedAppUsage
    public static native int getDeviceConnectionState(int device, String device_address);
    /** @hide */
    public static native int handleDeviceConfigChange(int device,
                                                      String device_address,
                                                      String device_name,
                                                      int codecFormat);
    /** @hide */
    @UnsupportedAppUsage
    public static int setPhoneState(int state) {
        Log.w(TAG, "Do not use this method! Use AudioManager.setMode() instead.");
        return 0;
    }
    /**
     * @hide
     * Send the current audio mode to audio policy manager and audio HAL.
     * @param state the audio mode
     * @param uid the UID of the app owning the audio mode
     * @return command completion status.
     */
    public static native int setPhoneState(int state, int uid);
    /** @hide */
    @UnsupportedAppUsage
    public static native int setForceUse(int usage, int config);
    /** @hide */
    @UnsupportedAppUsage
    public static native int getForceUse(int usage);
    /** @hide */
    @UnsupportedAppUsage
    public static native int initStreamVolume(int stream, int indexMin, int indexMax);
    @UnsupportedAppUsage
    private static native int setStreamVolumeIndex(int stream, int index, int device);
    /** @hide */
    public static native int getStreamVolumeIndex(int stream, int device);
    /**
     * @hide
     * set a volume for the given {@link AudioAttributes} and for all other stream that belong to
     * the same volume group.
     * @param attributes the {@link AudioAttributes} to be considered
     * @param index to be applied
     * @param device the volume device to be considered
     * @return command completion status.
     */
    public static native int setVolumeIndexForAttributes(@NonNull AudioAttributes attributes,
                                                         int index, int device);
   /**
    * @hide
    * get the volume index for the given {@link AudioAttributes}.
    * @param attributes the {@link AudioAttributes} to be considered
    * @param device the volume device to be considered
    * @return volume index for the given {@link AudioAttributes} and volume device.
    */
    public static native int getVolumeIndexForAttributes(@NonNull AudioAttributes attributes,
                                                         int device);
    /**
     * @hide
     * get the minimum volume index for the given {@link AudioAttributes}.
     * @param attributes the {@link AudioAttributes} to be considered
     * @return minimum volume index for the given {@link AudioAttributes}.
     */
    public static native int getMinVolumeIndexForAttributes(@NonNull AudioAttributes attributes);
    /**
     * @hide
     * get the maximum volume index for the given {@link AudioAttributes}.
     * @param attributes the {@link AudioAttributes} to be considered
     * @return maximum volume index for the given {@link AudioAttributes}.
     */
    public static native int getMaxVolumeIndexForAttributes(@NonNull AudioAttributes attributes);

    /** @hide */
    public static native int setMasterVolume(float value);
    /** @hide */
    public static native float getMasterVolume();
    /** @hide */
    @UnsupportedAppUsage
    public static native int setMasterMute(boolean mute);
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static native boolean getMasterMute();
    /** @hide */
    @UnsupportedAppUsage
    public static native int getDevicesForStream(int stream);

    /**
     * @hide
     * Do not use directly, see {@link AudioManager#getDevicesForAttributes(AudioAttributes)}
     * Get the audio devices that would be used for the routing of the given audio attributes.
     * @param attributes the {@link AudioAttributes} for which the routing is being queried
     * @return an empty list if there was an issue with the request, a list of audio devices
     *   otherwise (typically one device, except for duplicated paths).
     */
    public static @NonNull ArrayList<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes) {
        Objects.requireNonNull(attributes);
        final AudioDeviceAttributes[] devices = new AudioDeviceAttributes[MAX_DEVICE_ROUTING];
        final int res = getDevicesForAttributes(attributes, devices);
        final ArrayList<AudioDeviceAttributes> routeDevices = new ArrayList<>();
        if (res != SUCCESS) {
            Log.e(TAG, "error " + res + " in getDevicesForAttributes for " + attributes);
            return routeDevices;
        }

        for (AudioDeviceAttributes device : devices) {
            if (device != null) {
                routeDevices.add(device);
            }
        }
        return routeDevices;
    }

    /**
     * Maximum number of audio devices a track is ever routed to, determines the size of the
     * array passed to {@link #getDevicesForAttributes(AudioAttributes, AudioDeviceAttributes[])}
     */
    private static final int MAX_DEVICE_ROUTING = 4;

    private static native int getDevicesForAttributes(@NonNull AudioAttributes aa,
                                                      @NonNull AudioDeviceAttributes[] devices);

    /** @hide returns true if master mono is enabled. */
    public static native boolean getMasterMono();
    /** @hide enables or disables the master mono mode. */
    public static native int setMasterMono(boolean mono);
    /** @hide enables or disables the RTT mode. */
    public static native int setRttEnabled(boolean enabled);

    /** @hide returns master balance value in range -1.f -> 1.f, where 0.f is dead center. */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
    public static native float getMasterBalance();
    /** @hide Changes the audio balance of the device. */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
    public static native int setMasterBalance(float balance);

    // helpers for android.media.AudioManager.getProperty(), see description there for meaning
    /** @hide */
    @UnsupportedAppUsage(trackingBug = 134049522)
    public static native int getPrimaryOutputSamplingRate();
    /** @hide */
    @UnsupportedAppUsage(trackingBug = 134049522)
    public static native int getPrimaryOutputFrameCount();
    /** @hide */
    @UnsupportedAppUsage
    public static native int getOutputLatency(int stream);

    /** @hide */
    public static native int setLowRamDevice(boolean isLowRamDevice, long totalMemory);
    /** @hide */
    @UnsupportedAppUsage
    public static native int checkAudioFlinger();
    /** @hide */
    public static native void setAudioFlingerBinder(IBinder audioFlinger);

    /** @hide */
    public static native int listAudioPorts(ArrayList<AudioPort> ports, int[] generation);
    /** @hide */
    public static native int createAudioPatch(AudioPatch[] patch,
                                            AudioPortConfig[] sources, AudioPortConfig[] sinks);
    /** @hide */
    public static native int releaseAudioPatch(AudioPatch patch);
    /** @hide */
    public static native int listAudioPatches(ArrayList<AudioPatch> patches, int[] generation);
    /** @hide */
    public static native int setAudioPortConfig(AudioPortConfig config);

    /** @hide */
    public static native int startAudioSource(AudioPortConfig config,
                                              AudioAttributes audioAttributes);
    /** @hide */
    public static native int stopAudioSource(int handle);

    // declare this instance as having a dynamic policy callback handler
    private static native final void native_register_dynamic_policy_callback();
    // declare this instance as having a recording configuration update callback handler
    private static native final void native_register_recording_callback();
    // declare this instance as having a routing update callback handler
    private static native void native_register_routing_callback();

    // must be kept in sync with value in include/system/audio.h
    /** @hide */ public static final int AUDIO_HW_SYNC_INVALID = 0;

    /** @hide */
    public static native int getAudioHwSyncForSession(int sessionId);

    /** @hide */
    public static native int registerPolicyMixes(ArrayList<AudioMix> mixes, boolean register);

    /** @hide see AudioPolicy.setUidDeviceAffinities() */
    public static native int setUidDeviceAffinities(int uid, @NonNull int[] types,
            @NonNull String[] addresses);

    /** @hide see AudioPolicy.removeUidDeviceAffinities() */
    public static native int removeUidDeviceAffinities(int uid);

    /** @hide see AudioPolicy.setUserIdDeviceAffinities() */
    public static native int setUserIdDeviceAffinities(int userId, @NonNull int[] types,
            @NonNull String[] addresses);

    /** @hide see AudioPolicy.removeUserIdDeviceAffinities() */
    public static native int removeUserIdDeviceAffinities(int userId);

    /** @hide */
    public static native int systemReady();

    /** @hide */
    public static native float getStreamVolumeDB(int stream, int index, int device);

    /**
     * @hide
     * Communicate supported system usages to audio policy service.
     */
    public static native int setSupportedSystemUsages(int[] systemUsages);

    /**
     * @hide
     * @see AudioManager#setAllowedCapturePolicy()
     */
    public static native int setAllowedCapturePolicy(int uid, int flags);

    /**
     * @hide
     * Compressed audio offload decoding modes supported by audio HAL implementation.
     * Keep in sync with system/media/include/media/audio.h.
     */
    public static final int OFFLOAD_NOT_SUPPORTED = 0;
    public static final int OFFLOAD_SUPPORTED = 1;
    public static final int OFFLOAD_GAPLESS_SUPPORTED = 2;

    static int getOffloadSupport(@NonNull AudioFormat format, @NonNull AudioAttributes attr) {
        return native_get_offload_support(format.getEncoding(), format.getSampleRate(),
                format.getChannelMask(), format.getChannelIndexMask(),
                attr.getVolumeControlStream());
    }

    private static native int native_get_offload_support(int encoding, int sampleRate,
            int channelMask, int channelIndexMask, int streamType);

    /** @hide */
    public static native int getMicrophones(ArrayList<MicrophoneInfo> microphonesInfo);

    /** @hide */
    public static native int getSurroundFormats(Map<Integer, Boolean> surroundFormats);

    /** @hide */
    public static native int getReportedSurroundFormats(ArrayList<Integer> surroundFormats);

    /**
     * @hide
     * Returns a list of audio formats (codec) supported on the A2DP offload path.
     */
    public static native int getHwOffloadEncodingFormatsSupportedForA2DP(
            ArrayList<Integer> formatList);

    /** @hide */
    public static native int setSurroundFormatEnabled(int audioFormat, boolean enabled);

    /**
     * @hide
     * Communicate UID of active assistant to audio policy service.
     */
    public static native int setAssistantUid(int uid);

    /**
     * @hide
     * Communicate UIDs of active accessibility services to audio policy service.
     */
    public static native int setA11yServicesUids(int[] uids);

    /**
     * @hide
     * Communicate UID of current InputMethodService to audio policy service.
     */
    public static native int setCurrentImeUid(int uid);


    /**
     * @hide
     * @see AudioManager#isHapticPlaybackSupported()
     */
    public static native boolean isHapticPlaybackSupported();

    /**
     * @hide
     * Send audio HAL server process pids to native audioserver process for use
     * when generating audio HAL servers tombstones
     */
    public static native int setAudioHalPids(int[] pids);

    /**
     * @hide
     * @see AudioManager#isCallScreeningModeSupported()
     */
    public static native boolean isCallScreeningModeSupported();

    // use case routing by product strategy

    /**
     * @hide
     * Set device as role for product strategy.
     * @param strategy the id of the strategy to configure
     * @param role the role of the devices
     * @param devices the list of devices to be set as role for the given strategy
     * @return {@link #SUCCESS} if successfully set
     */
    public static int setDevicesRoleForStrategy(
            int strategy, int role, @NonNull List<AudioDeviceAttributes> devices) {
        if (devices.isEmpty()) {
            return BAD_VALUE;
        }
        int[] types = new int[devices.size()];
        String[] addresses = new String[devices.size()];
        for (int i = 0; i < devices.size(); ++i) {
            types[i] = devices.get(i).getInternalType();
            addresses[i] = devices.get(i).getAddress();
        }
        return setDevicesRoleForStrategy(strategy, role, types, addresses);
    }

    /**
     * @hide
     * Set device as role for product strategy.
     * @param strategy the id of the strategy to configure
     * @param role the role of the devices
     * @param types all device types
     * @param addresses all device addresses
     * @return {@link #SUCCESS} if successfully set
     */
    private static native int setDevicesRoleForStrategy(
            int strategy, int role, @NonNull int[] types, @NonNull String[] addresses);

    /**
     * @hide
     * Remove devices as role for the strategy
     * @param strategy the id of the strategy to configure
     * @param role the role of the devices
     * @return {@link #SUCCESS} if successfully removed
     */
    public static native int removeDevicesRoleForStrategy(int strategy, int role);

    /**
     * @hide
     * Query previously set devices as role for a strategy
     * @param strategy the id of the strategy to query for
     * @param role the role of the devices
     * @param devices a list that will contain the devices of role
     * @return {@link #SUCCESS} if there is a preferred device and it was successfully retrieved
     *     and written to the array
     */
    public static native int getDevicesForRoleAndStrategy(
            int strategy, int role, @NonNull List<AudioDeviceAttributes> devices);

    // use case routing by capture preset

    private static Pair<int[], String[]> populateInputDevicesTypeAndAddress(
            @NonNull List<AudioDeviceAttributes> devices) {
        int[] types = new int[devices.size()];
        String[] addresses = new String[devices.size()];
        for (int i = 0; i < devices.size(); ++i) {
            types[i] = devices.get(i).getInternalType();
            if (types[i] == AudioSystem.DEVICE_NONE) {
                types[i] = AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(
                        devices.get(i).getType());
            }
            addresses[i] = devices.get(i).getAddress();
        }
        return new Pair<int[], String[]>(types, addresses);
    }

    /**
     * @hide
     * Set devices as role for capture preset.
     * @param capturePreset the capture preset to configure
     * @param role the role of the devices
     * @param devices the list of devices to be set as role for the given capture preset
     * @return {@link #SUCCESS} if successfully set
     */
    public static int setDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devices) {
        if (devices.isEmpty()) {
            return BAD_VALUE;
        }
        Pair<int[], String[]> typeAddresses = populateInputDevicesTypeAndAddress(devices);
        return setDevicesRoleForCapturePreset(
                capturePreset, role, typeAddresses.first, typeAddresses.second);
    }

    /**
     * @hide
     * Set devices as role for capture preset.
     * @param capturePreset the capture preset to configure
     * @param role the role of the devices
     * @param types all device types
     * @param addresses all device addresses
     * @return {@link #SUCCESS} if successfully set
     */
    private static native int setDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull int[] types, @NonNull String[] addresses);

    /**
     * @hide
     * Add devices as role for capture preset.
     * @param capturePreset the capture preset to configure
     * @param role the role of the devices
     * @param devices the list of devices to be added as role for the given capture preset
     * @return {@link #SUCCESS} if successfully add
     */
    public static int addDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devices) {
        if (devices.isEmpty()) {
            return BAD_VALUE;
        }
        Pair<int[], String[]> typeAddresses = populateInputDevicesTypeAndAddress(devices);
        return addDevicesRoleForCapturePreset(
                capturePreset, role, typeAddresses.first, typeAddresses.second);
    }

    /**
     * @hide
     * Add devices as role for capture preset.
     * @param capturePreset the capture preset to configure
     * @param role the role of the devices
     * @param types all device types
     * @param addresses all device addresses
     * @return {@link #SUCCESS} if successfully set
     */
    private static native int addDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull int[] types, @NonNull String[] addresses);

    /**
     * @hide
     * Remove devices as role for the capture preset
     * @param capturePreset the capture preset to configure
     * @param role the role of the devices
     * @param devices the devices to be removed
     * @return {@link #SUCCESS} if successfully removed
     */
    public static int removeDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devices) {
        if (devices.isEmpty()) {
            return BAD_VALUE;
        }
        Pair<int[], String[]> typeAddresses = populateInputDevicesTypeAndAddress(devices);
        return removeDevicesRoleForCapturePreset(
                capturePreset, role, typeAddresses.first, typeAddresses.second);
    }

    /**
     * @hide
     * Remove devices as role for capture preset.
     * @param capturePreset the capture preset to configure
     * @param role the role of the devices
     * @param types all device types
     * @param addresses all device addresses
     * @return {@link #SUCCESS} if successfully set
     */
    private static native int removeDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull int[] types, @NonNull String[] addresses);

    /**
     * @hide
     * Remove all devices as role for the capture preset
     * @param capturePreset the capture preset to configure
     * @param role the role of the devices
     * @return {@link #SUCCESS} if successfully removed
     */
    public static native int clearDevicesRoleForCapturePreset(int capturePreset, int role);

    /**
     * @hide
     * Query previously set devices as role for a capture preset
     * @param capturePreset the capture preset to query for
     * @param role the role of the devices
     * @param devices a list that will contain the devices of role
     * @return {@link #SUCCESS} if there is a preferred device and it was successfully retrieved
     *     and written to the array
     */
    public static native int getDevicesForRoleAndCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devices);

    /**
     * @hide
     * Set the vibrators' information. The value will be used to initialize HapticGenerator.
     * @param vibrators a list of all available vibrators
     * @return command completion status
     */
    public static native int setVibratorInfos(@NonNull List<Vibrator> vibrators);

    // Items shared with audio service

    /**
     * @hide
     * The delay before playing a sound. This small period exists so the user
     * can press another key (non-volume keys, too) to have it NOT be audible.
     * <p>
     * PhoneWindow will implement this part.
     */
    public static final int PLAY_SOUND_DELAY = 300;

    /**
     * @hide
     * Constant to identify a focus stack entry that is used to hold the focus while the phone
     * is ringing or during a call. Used by com.android.internal.telephony.CallManager when
     * entering and exiting calls.
     */
    public final static String IN_VOICE_COMM_FOCUS_ID = "AudioFocus_For_Phone_Ring_And_Calls";

    /**
     * @hide
     * @see AudioManager#setVibrateSetting(int, int)
     */
    public static int getValueForVibrateSetting(int existingValue, int vibrateType,
            int vibrateSetting) {

        // First clear the existing setting. Each vibrate type has two bits in
        // the value. Note '3' is '11' in binary.
        existingValue &= ~(3 << (vibrateType * 2));

        // Set into the old value
        existingValue |= (vibrateSetting & 3) << (vibrateType * 2);

        return existingValue;
    }

    /** @hide */
    public static int getDefaultStreamVolume(int streamType) {
        return DEFAULT_STREAM_VOLUME[streamType];
    }

    /** @hide */
    public static int[] DEFAULT_STREAM_VOLUME = new int[] {
        4,  // STREAM_VOICE_CALL
        7,  // STREAM_SYSTEM
        5,  // STREAM_RING
        5, // STREAM_MUSIC
        6,  // STREAM_ALARM
        5,  // STREAM_NOTIFICATION
        7,  // STREAM_BLUETOOTH_SCO
        7,  // STREAM_SYSTEM_ENFORCED
        5, // STREAM_DTMF
        5, // STREAM_TTS
        5, // STREAM_ACCESSIBILITY
        5, // STREAM_ASSISTANT
    };

    /** @hide */
    @TestApi
    public static @NonNull String streamToString(int stream) {
        if (stream >= 0 && stream < STREAM_NAMES.length) return STREAM_NAMES[stream];
        if (stream == AudioManager.USE_DEFAULT_STREAM_TYPE) return "USE_DEFAULT_STREAM_TYPE";
        return "UNKNOWN_STREAM_" + stream;
    }

    /** @hide The platform has no specific capabilities */
    public static final int PLATFORM_DEFAULT = 0;
    /** @hide The platform is voice call capable (a phone) */
    public static final int PLATFORM_VOICE = 1;
    /** @hide The platform is a television or a set-top box */
    public static final int PLATFORM_TELEVISION = 2;

    /**
     * @hide
     * Return the platform type that this is running on. One of:
     * <ul>
     * <li>{@link #PLATFORM_VOICE}</li>
     * <li>{@link #PLATFORM_TELEVISION}</li>
     * <li>{@link #PLATFORM_DEFAULT}</li>
     * </ul>
     */
    public static int getPlatformType(Context context) {
        if (((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE))
                .isVoiceCapable()) {
            return PLATFORM_VOICE;
        } else if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return PLATFORM_TELEVISION;
        } else {
            return PLATFORM_DEFAULT;
        }
    }

    /**
     * @hide
     * @return whether the system uses a single volume stream.
     */
    public static boolean isSingleVolume(Context context) {
        boolean forceSingleVolume = context.getResources().getBoolean(
                com.android.internal.R.bool.config_single_volume);
        return getPlatformType(context) == PLATFORM_TELEVISION || forceSingleVolume;
    }

    /**
     * @hide
     * Return a set of audio device types from a bit mask audio device type, which may
     * represent multiple audio device types.
     * FIXME: Remove this when getting ride of bit mask usage of audio device types.
     */
    public static Set<Integer> generateAudioDeviceTypesSet(int types) {
        Set<Integer> deviceTypes = new HashSet<>();
        Set<Integer> allDeviceTypes =
                (types & DEVICE_BIT_IN) == 0 ? DEVICE_OUT_ALL_SET : DEVICE_IN_ALL_SET;
        for (int deviceType : allDeviceTypes) {
            if ((types & deviceType) == deviceType) {
                deviceTypes.add(deviceType);
            }
        }
        return deviceTypes;
    }

    /**
     * @hide
     * Return the intersection of two audio device types collections.
     */
    public static Set<Integer> intersectionAudioDeviceTypes(
            @NonNull Set<Integer> a, @NonNull Set<Integer> b) {
        Set<Integer> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return intersection;
    }

    /**
     * @hide
     * Return true if the audio device types collection only contains the given device type.
     */
    public static boolean isSingleAudioDeviceType(@NonNull Set<Integer> types, int type) {
        return types.size() == 1 && types.contains(type);
    }

    /** @hide */
    public static final int DEFAULT_MUTE_STREAMS_AFFECTED =
            (1 << STREAM_MUSIC) |
            (1 << STREAM_RING) |
            (1 << STREAM_NOTIFICATION) |
            (1 << STREAM_SYSTEM) |
            (1 << STREAM_VOICE_CALL) |
            (1 << STREAM_BLUETOOTH_SCO);

    /**
     * @hide
     * Event posted by AudioTrack and AudioRecord JNI (JNIDeviceCallback) when routing changes.
     * Keep in sync with core/jni/android_media_DeviceCallback.h.
     */
    final static int NATIVE_EVENT_ROUTING_CHANGE = 1000;
}

