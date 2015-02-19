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

import android.media.audiopolicy.AudioMix;
import java.util.ArrayList;

/* IF YOU CHANGE ANY OF THE CONSTANTS IN THIS FILE, DO NOT FORGET
 * TO UPDATE THE CORRESPONDING NATIVE GLUE AND AudioManager.java.
 * THANK YOU FOR YOUR COOPERATION.
 */

/**
 * @hide
 */
public class AudioSystem
{
    /* These values must be kept in sync with system/audio.h */
    /*
     * If these are modified, please also update Settings.System.VOLUME_SETTINGS
     * and attrs.xml and AudioManager.java.
     */
    /* The default audio stream */
    public static final int STREAM_DEFAULT = -1;
    /* The audio stream for phone calls */
    public static final int STREAM_VOICE_CALL = 0;
    /* The audio stream for system sounds */
    public static final int STREAM_SYSTEM = 1;
    /* The audio stream for the phone ring and message alerts */
    public static final int STREAM_RING = 2;
    /* The audio stream for music playback */
    public static final int STREAM_MUSIC = 3;
    /* The audio stream for alarms */
    public static final int STREAM_ALARM = 4;
    /* The audio stream for notifications */
    public static final int STREAM_NOTIFICATION = 5;
    /* @hide The audio stream for phone calls when connected on bluetooth */
    public static final int STREAM_BLUETOOTH_SCO = 6;
    /* @hide The audio stream for enforced system sounds in certain countries (e.g camera in Japan) */
    public static final int STREAM_SYSTEM_ENFORCED = 7;
    /* @hide The audio stream for DTMF tones */
    public static final int STREAM_DTMF = 8;
    /* @hide The audio stream for text to speech (TTS) */
    public static final int STREAM_TTS = 9;
    /**
     * @deprecated Use {@link #numStreamTypes() instead}
     */
    public static final int NUM_STREAMS = 5;

    // Expose only the getter method publicly so we can change it in the future
    private static final int NUM_STREAM_TYPES = 10;
    public static final int getNumStreamTypes() { return NUM_STREAM_TYPES; }

    /*
     * Sets the microphone mute on or off.
     *
     * @param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     * @return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    public static native int muteMicrophone(boolean on);

    /*
     * Checks whether the microphone mute is on or off.
     *
     * @return true if microphone is muted, false if it's not
     */
    public static native boolean isMicrophoneMuted();

    /* modes for setPhoneState, must match AudioSystem.h audio_mode */
    public static final int MODE_INVALID            = -2;
    public static final int MODE_CURRENT            = -1;
    public static final int MODE_NORMAL             = 0;
    public static final int MODE_RINGTONE           = 1;
    public static final int MODE_IN_CALL            = 2;
    public static final int MODE_IN_COMMUNICATION   = 3;
    public static final int NUM_MODES               = 4;


    /* Routing bits for the former setRouting/getRouting API */
    /** @deprecated */
    @Deprecated public static final int ROUTE_EARPIECE          = (1 << 0);
    /** @deprecated */
    @Deprecated public static final int ROUTE_SPEAKER           = (1 << 1);
    /** @deprecated use {@link #ROUTE_BLUETOOTH_SCO} */
    @Deprecated public static final int ROUTE_BLUETOOTH = (1 << 2);
    /** @deprecated */
    @Deprecated public static final int ROUTE_BLUETOOTH_SCO     = (1 << 2);
    /** @deprecated */
    @Deprecated public static final int ROUTE_HEADSET           = (1 << 3);
    /** @deprecated */
    @Deprecated public static final int ROUTE_BLUETOOTH_A2DP    = (1 << 4);
    /** @deprecated */
    @Deprecated public static final int ROUTE_ALL               = 0xFFFFFFFF;

    // Keep in sync with system/core/include/system/audio.h
    public static final int AUDIO_SESSION_ALLOCATE = 0;

    /*
     * Checks whether the specified stream type is active.
     *
     * return true if any track playing on this stream is active.
     */
    public static native boolean isStreamActive(int stream, int inPastMs);

    /*
     * Checks whether the specified stream type is active on a remotely connected device. The notion
     * of what constitutes a remote device is enforced by the audio policy manager of the platform.
     *
     * return true if any track playing on this stream is active on a remote device.
     */
    public static native boolean isStreamActiveRemotely(int stream, int inPastMs);

    /*
     * Checks whether the specified audio source is active.
     *
     * return true if any recorder using this source is currently recording
     */
    public static native boolean isSourceActive(int source);

    /*
     * Returns a new unused audio session ID
     */
    public static native int newAudioSessionId();

    /*
     * Sets a group generic audio configuration parameters. The use of these parameters
     * are platform dependent, see libaudio
     *
     * param keyValuePairs  list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     */
    public static native int setParameters(String keyValuePairs);

    /*
     * Gets a group generic audio configuration parameters. The use of these parameters
     * are platform dependent, see libaudio
     *
     * param keys  list of parameters
     * return value: list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     */
    public static native String getParameters(String keys);

    // These match the enum AudioError in frameworks/base/core/jni/android_media_AudioSystem.cpp
    /* Command sucessful or Media server restarted. see ErrorCallback */
    public static final int AUDIO_STATUS_OK = 0;
    /* Command failed or unspecified audio error.  see ErrorCallback */
    public static final int AUDIO_STATUS_ERROR = 1;
    /* Media server died. see ErrorCallback */
    public static final int AUDIO_STATUS_SERVER_DIED = 100;

    private static ErrorCallback mErrorCallback;

    /*
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

    /*
     * Registers a callback to be invoked when an error occurs.
     * @param cb the callback to run
     */
    public static void setErrorCallback(ErrorCallback cb)
    {
        synchronized (AudioSystem.class) {
            mErrorCallback = cb;
            if (cb != null) {
                cb.onError(checkAudioFlinger());
            }
        }
    }

    private static void errorCallbackFromNative(int error)
    {
        ErrorCallback errorCallback = null;
        synchronized (AudioSystem.class) {
            if (mErrorCallback != null) {
                errorCallback = mErrorCallback;
            }
        }
        if (errorCallback != null) {
            errorCallback.onError(error);
        }
    }

    /*
     * Error codes used by public APIs (AudioTrack, AudioRecord, AudioManager ...)
     * Must be kept in sync with frameworks/base/core/jni/android_media_AudioErrors.h
     */
    public static final int SUCCESS            = 0;
    public static final int ERROR              = -1;
    public static final int BAD_VALUE          = -2;
    public static final int INVALID_OPERATION  = -3;
    public static final int PERMISSION_DENIED  = -4;
    public static final int NO_INIT            = -5;
    public static final int DEAD_OBJECT        = -6;

    /*
     * AudioPolicyService methods
     */

    //
    // audio device definitions: must be kept in sync with values in system/core/audio.h
    //

    public static final int DEVICE_NONE = 0x0;
    // reserved bits
    public static final int DEVICE_BIT_IN = 0x80000000;
    public static final int DEVICE_BIT_DEFAULT = 0x40000000;
    // output devices, be sure to update AudioManager.java also
    public static final int DEVICE_OUT_EARPIECE = 0x1;
    public static final int DEVICE_OUT_SPEAKER = 0x2;
    public static final int DEVICE_OUT_WIRED_HEADSET = 0x4;
    public static final int DEVICE_OUT_WIRED_HEADPHONE = 0x8;
    public static final int DEVICE_OUT_BLUETOOTH_SCO = 0x10;
    public static final int DEVICE_OUT_BLUETOOTH_SCO_HEADSET = 0x20;
    public static final int DEVICE_OUT_BLUETOOTH_SCO_CARKIT = 0x40;
    public static final int DEVICE_OUT_BLUETOOTH_A2DP = 0x80;
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES = 0x100;
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER = 0x200;
    public static final int DEVICE_OUT_AUX_DIGITAL = 0x400;
    public static final int DEVICE_OUT_HDMI = DEVICE_OUT_AUX_DIGITAL;
    public static final int DEVICE_OUT_ANLG_DOCK_HEADSET = 0x800;
    public static final int DEVICE_OUT_DGTL_DOCK_HEADSET = 0x1000;
    public static final int DEVICE_OUT_USB_ACCESSORY = 0x2000;
    public static final int DEVICE_OUT_USB_DEVICE = 0x4000;
    public static final int DEVICE_OUT_REMOTE_SUBMIX = 0x8000;
    public static final int DEVICE_OUT_TELEPHONY_TX = 0x10000;
    public static final int DEVICE_OUT_LINE = 0x20000;
    public static final int DEVICE_OUT_HDMI_ARC = 0x40000;
    public static final int DEVICE_OUT_SPDIF = 0x80000;
    public static final int DEVICE_OUT_FM = 0x100000;
    public static final int DEVICE_OUT_AUX_LINE = 0x200000;
    public static final int DEVICE_OUT_SPEAKER_SAFE = 0x400000;

    public static final int DEVICE_OUT_DEFAULT = DEVICE_BIT_DEFAULT;

    public static final int DEVICE_OUT_ALL = (DEVICE_OUT_EARPIECE |
                                              DEVICE_OUT_SPEAKER |
                                              DEVICE_OUT_WIRED_HEADSET |
                                              DEVICE_OUT_WIRED_HEADPHONE |
                                              DEVICE_OUT_BLUETOOTH_SCO |
                                              DEVICE_OUT_BLUETOOTH_SCO_HEADSET |
                                              DEVICE_OUT_BLUETOOTH_SCO_CARKIT |
                                              DEVICE_OUT_BLUETOOTH_A2DP |
                                              DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                                              DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER |
                                              DEVICE_OUT_HDMI |
                                              DEVICE_OUT_ANLG_DOCK_HEADSET |
                                              DEVICE_OUT_DGTL_DOCK_HEADSET |
                                              DEVICE_OUT_USB_ACCESSORY |
                                              DEVICE_OUT_USB_DEVICE |
                                              DEVICE_OUT_REMOTE_SUBMIX |
                                              DEVICE_OUT_TELEPHONY_TX |
                                              DEVICE_OUT_LINE |
                                              DEVICE_OUT_HDMI_ARC |
                                              DEVICE_OUT_SPDIF |
                                              DEVICE_OUT_FM |
                                              DEVICE_OUT_AUX_LINE |
                                              DEVICE_OUT_SPEAKER_SAFE |
                                              DEVICE_OUT_DEFAULT);
    public static final int DEVICE_OUT_ALL_A2DP = (DEVICE_OUT_BLUETOOTH_A2DP |
                                                   DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                                                   DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER);
    public static final int DEVICE_OUT_ALL_SCO = (DEVICE_OUT_BLUETOOTH_SCO |
                                                  DEVICE_OUT_BLUETOOTH_SCO_HEADSET |
                                                  DEVICE_OUT_BLUETOOTH_SCO_CARKIT);
    public static final int DEVICE_OUT_ALL_USB = (DEVICE_OUT_USB_ACCESSORY |
                                                  DEVICE_OUT_USB_DEVICE);
    public static final int DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO = (DEVICE_OUT_AUX_LINE |
                                                                DEVICE_OUT_HDMI_ARC |
                                                                DEVICE_OUT_SPDIF);
    public static final int DEVICE_ALL_HDMI_SYSTEM_AUDIO_AND_SPEAKER =
            (DEVICE_OUT_ALL_HDMI_SYSTEM_AUDIO |
             DEVICE_OUT_SPEAKER);

    // input devices
    public static final int DEVICE_IN_COMMUNICATION = DEVICE_BIT_IN | 0x1;
    public static final int DEVICE_IN_AMBIENT = DEVICE_BIT_IN | 0x2;
    public static final int DEVICE_IN_BUILTIN_MIC = DEVICE_BIT_IN | 0x4;
    public static final int DEVICE_IN_BLUETOOTH_SCO_HEADSET = DEVICE_BIT_IN | 0x8;
    public static final int DEVICE_IN_WIRED_HEADSET = DEVICE_BIT_IN | 0x10;
    public static final int DEVICE_IN_AUX_DIGITAL = DEVICE_BIT_IN | 0x20;
    public static final int DEVICE_IN_HDMI = DEVICE_IN_AUX_DIGITAL;
    public static final int DEVICE_IN_VOICE_CALL = DEVICE_BIT_IN | 0x40;
    public static final int DEVICE_IN_TELEPHONY_RX = DEVICE_IN_VOICE_CALL;
    public static final int DEVICE_IN_BACK_MIC = DEVICE_BIT_IN | 0x80;
    public static final int DEVICE_IN_REMOTE_SUBMIX = DEVICE_BIT_IN | 0x100;
    public static final int DEVICE_IN_ANLG_DOCK_HEADSET = DEVICE_BIT_IN | 0x200;
    public static final int DEVICE_IN_DGTL_DOCK_HEADSET = DEVICE_BIT_IN | 0x400;
    public static final int DEVICE_IN_USB_ACCESSORY = DEVICE_BIT_IN | 0x800;
    public static final int DEVICE_IN_USB_DEVICE = DEVICE_BIT_IN | 0x1000;
    public static final int DEVICE_IN_FM_TUNER = DEVICE_BIT_IN | 0x2000;
    public static final int DEVICE_IN_TV_TUNER = DEVICE_BIT_IN | 0x4000;
    public static final int DEVICE_IN_LINE = DEVICE_BIT_IN | 0x8000;
    public static final int DEVICE_IN_SPDIF = DEVICE_BIT_IN | 0x10000;
    public static final int DEVICE_IN_BLUETOOTH_A2DP = DEVICE_BIT_IN | 0x20000;
    public static final int DEVICE_IN_LOOPBACK = DEVICE_BIT_IN | 0x40000;
    public static final int DEVICE_IN_DEFAULT = DEVICE_BIT_IN | DEVICE_BIT_DEFAULT;

    public static final int DEVICE_IN_ALL = (DEVICE_IN_COMMUNICATION |
                                             DEVICE_IN_AMBIENT |
                                             DEVICE_IN_BUILTIN_MIC |
                                             DEVICE_IN_BLUETOOTH_SCO_HEADSET |
                                             DEVICE_IN_WIRED_HEADSET |
                                             DEVICE_IN_HDMI |
                                             DEVICE_IN_TELEPHONY_RX |
                                             DEVICE_IN_BACK_MIC |
                                             DEVICE_IN_REMOTE_SUBMIX |
                                             DEVICE_IN_ANLG_DOCK_HEADSET |
                                             DEVICE_IN_DGTL_DOCK_HEADSET |
                                             DEVICE_IN_USB_ACCESSORY |
                                             DEVICE_IN_USB_DEVICE |
                                             DEVICE_IN_FM_TUNER |
                                             DEVICE_IN_TV_TUNER |
                                             DEVICE_IN_LINE |
                                             DEVICE_IN_SPDIF |
                                             DEVICE_IN_BLUETOOTH_A2DP |
                                             DEVICE_IN_LOOPBACK |
                                             DEVICE_IN_DEFAULT);
    public static final int DEVICE_IN_ALL_SCO = DEVICE_IN_BLUETOOTH_SCO_HEADSET;
    public static final int DEVICE_IN_ALL_USB = (DEVICE_IN_USB_ACCESSORY |
                                                 DEVICE_IN_USB_DEVICE);

    // device states, must match AudioSystem::device_connection_state
    public static final int DEVICE_STATE_UNAVAILABLE = 0;
    public static final int DEVICE_STATE_AVAILABLE = 1;
    private static final int NUM_DEVICE_STATES = 1;

    public static final String DEVICE_OUT_EARPIECE_NAME = "earpiece";
    public static final String DEVICE_OUT_SPEAKER_NAME = "speaker";
    public static final String DEVICE_OUT_WIRED_HEADSET_NAME = "headset";
    public static final String DEVICE_OUT_WIRED_HEADPHONE_NAME = "headphone";
    public static final String DEVICE_OUT_BLUETOOTH_SCO_NAME = "bt_sco";
    public static final String DEVICE_OUT_BLUETOOTH_SCO_HEADSET_NAME = "bt_sco_hs";
    public static final String DEVICE_OUT_BLUETOOTH_SCO_CARKIT_NAME = "bt_sco_carkit";
    public static final String DEVICE_OUT_BLUETOOTH_A2DP_NAME = "bt_a2dp";
    public static final String DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES_NAME = "bt_a2dp_hp";
    public static final String DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER_NAME = "bt_a2dp_spk";
    public static final String DEVICE_OUT_AUX_DIGITAL_NAME = "aux_digital";
    public static final String DEVICE_OUT_HDMI_NAME = "hdmi";
    public static final String DEVICE_OUT_ANLG_DOCK_HEADSET_NAME = "analog_dock";
    public static final String DEVICE_OUT_DGTL_DOCK_HEADSET_NAME = "digital_dock";
    public static final String DEVICE_OUT_USB_ACCESSORY_NAME = "usb_accessory";
    public static final String DEVICE_OUT_USB_DEVICE_NAME = "usb_device";
    public static final String DEVICE_OUT_REMOTE_SUBMIX_NAME = "remote_submix";
    public static final String DEVICE_OUT_TELEPHONY_TX_NAME = "telephony_tx";
    public static final String DEVICE_OUT_LINE_NAME = "line";
    public static final String DEVICE_OUT_HDMI_ARC_NAME = "hmdi_arc";
    public static final String DEVICE_OUT_SPDIF_NAME = "spdif";
    public static final String DEVICE_OUT_FM_NAME = "fm_transmitter";
    public static final String DEVICE_OUT_AUX_LINE_NAME = "aux_line";
    public static final String DEVICE_OUT_SPEAKER_SAFE_NAME = "speaker_safe";

    public static final String DEVICE_IN_COMMUNICATION_NAME = "communication";
    public static final String DEVICE_IN_AMBIENT_NAME = "ambient";
    public static final String DEVICE_IN_BUILTIN_MIC_NAME = "mic";
    public static final String DEVICE_IN_BLUETOOTH_SCO_HEADSET_NAME = "bt_sco_hs";
    public static final String DEVICE_IN_WIRED_HEADSET_NAME = "headset";
    public static final String DEVICE_IN_AUX_DIGITAL_NAME = "aux_digital";
    public static final String DEVICE_IN_TELEPHONY_RX_NAME = "telephony_rx";
    public static final String DEVICE_IN_BACK_MIC_NAME = "back_mic";
    public static final String DEVICE_IN_REMOTE_SUBMIX_NAME = "remote_submix";
    public static final String DEVICE_IN_ANLG_DOCK_HEADSET_NAME = "analog_dock";
    public static final String DEVICE_IN_DGTL_DOCK_HEADSET_NAME = "digital_dock";
    public static final String DEVICE_IN_USB_ACCESSORY_NAME = "usb_accessory";
    public static final String DEVICE_IN_USB_DEVICE_NAME = "usb_device";
    public static final String DEVICE_IN_FM_TUNER_NAME = "fm_tuner";
    public static final String DEVICE_IN_TV_TUNER_NAME = "tv_tuner";
    public static final String DEVICE_IN_LINE_NAME = "line";
    public static final String DEVICE_IN_SPDIF_NAME = "spdif";
    public static final String DEVICE_IN_BLUETOOTH_A2DP_NAME = "bt_a2dp";
    public static final String DEVICE_IN_LOOPBACK_NAME = "loopback";

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
        case DEVICE_OUT_SPDIF:
            return DEVICE_OUT_SPDIF_NAME;
        case DEVICE_OUT_FM:
            return DEVICE_OUT_FM_NAME;
        case DEVICE_OUT_AUX_LINE:
            return DEVICE_OUT_AUX_LINE_NAME;
        case DEVICE_OUT_SPEAKER_SAFE:
            return DEVICE_OUT_SPEAKER_SAFE_NAME;
        case DEVICE_OUT_DEFAULT:
        default:
            return Integer.toString(device);
        }
    }

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
        case DEVICE_IN_DEFAULT:
        default:
            return Integer.toString(device);
        }
    }

    // phone state, match audio_mode???
    public static final int PHONE_STATE_OFFCALL = 0;
    public static final int PHONE_STATE_RINGING = 1;
    public static final int PHONE_STATE_INCALL = 2;

    // device categories config for setForceUse, must match AudioSystem::forced_config
    public static final int FORCE_NONE = 0;
    public static final int FORCE_SPEAKER = 1;
    public static final int FORCE_HEADPHONES = 2;
    public static final int FORCE_BT_SCO = 3;
    public static final int FORCE_BT_A2DP = 4;
    public static final int FORCE_WIRED_ACCESSORY = 5;
    public static final int FORCE_BT_CAR_DOCK = 6;
    public static final int FORCE_BT_DESK_DOCK = 7;
    public static final int FORCE_ANALOG_DOCK = 8;
    public static final int FORCE_DIGITAL_DOCK = 9;
    public static final int FORCE_NO_BT_A2DP = 10;
    public static final int FORCE_SYSTEM_ENFORCED = 11;
    public static final int FORCE_HDMI_SYSTEM_AUDIO_ENFORCED = 12;
    private static final int NUM_FORCE_CONFIG = 13;
    public static final int FORCE_DEFAULT = FORCE_NONE;

    // usage for setForceUse, must match AudioSystem::force_use
    public static final int FOR_COMMUNICATION = 0;
    public static final int FOR_MEDIA = 1;
    public static final int FOR_RECORD = 2;
    public static final int FOR_DOCK = 3;
    public static final int FOR_SYSTEM = 4;
    public static final int FOR_HDMI_SYSTEM_AUDIO = 5;
    private static final int NUM_FORCE_USE = 6;

    // usage for AudioRecord.startRecordingSync(), must match AudioSystem::sync_event_t
    public static final int SYNC_EVENT_NONE = 0;
    public static final int SYNC_EVENT_PRESENTATION_COMPLETE = 1;

    public static native int setDeviceConnectionState(int device, int state, String device_address);
    public static native int getDeviceConnectionState(int device, String device_address);
    public static native int setPhoneState(int state);
    public static native int setForceUse(int usage, int config);
    public static native int getForceUse(int usage);
    public static native int initStreamVolume(int stream, int indexMin, int indexMax);
    public static native int setStreamVolumeIndex(int stream, int index, int device);
    public static native int getStreamVolumeIndex(int stream, int device);
    public static native int setMasterVolume(float value);
    public static native float getMasterVolume();
    public static native int setMasterMute(boolean mute);
    public static native boolean getMasterMute();
    public static native int getDevicesForStream(int stream);

    // helpers for android.media.AudioManager.getProperty(), see description there for meaning
    public static native int getPrimaryOutputSamplingRate();
    public static native int getPrimaryOutputFrameCount();
    public static native int getOutputLatency(int stream);

    public static native int setLowRamDevice(boolean isLowRamDevice);
    public static native int checkAudioFlinger();

    public static native int listAudioPorts(ArrayList<AudioPort> ports, int[] generation);
    public static native int createAudioPatch(AudioPatch[] patch,
                                            AudioPortConfig[] sources, AudioPortConfig[] sinks);
    public static native int releaseAudioPatch(AudioPatch patch);
    public static native int listAudioPatches(ArrayList<AudioPatch> patches, int[] generation);
    public static native int setAudioPortConfig(AudioPortConfig config);

    // must be kept in sync with value in include/system/audio.h
    public static final int AUDIO_HW_SYNC_INVALID = 0;

    public static native int getAudioHwSyncForSession(int sessionId);

    public static native int registerPolicyMixes(ArrayList<AudioMix> mixes, boolean register);
}

