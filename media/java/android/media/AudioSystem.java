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


/* IF YOU CHANGE ANY OF THE CONSTANTS IN THIS FILE, DO NOT FORGET
 * TO UPDATE THE CORRESPONDING NATIVE GLUE AND AudioManager.java.
 * THANK YOU FOR YOUR COOPERATION.
 */

/**
 * @hide
 */
public class AudioSystem
{
    /* These values must be kept in sync with AudioSystem.h */
    /*
     * If these are modified, please also update Settings.System.VOLUME_SETTINGS
     * and attrs.xml and AudioManager.java.
     */
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
    /* @hide The audio stream for incall music delivery */
    public static final int STREAM_INCALL_MUSIC = 10;
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
     * AudioPolicyService methods
     */

    //
    // audio device definitions: must be kept in sync with values in system/core/audio.h
    //

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
    public static final int DEVICE_OUT_ANLG_DOCK_HEADSET = 0x800;
    public static final int DEVICE_OUT_DGTL_DOCK_HEADSET = 0x1000;
    public static final int DEVICE_OUT_USB_ACCESSORY = 0x2000;
    public static final int DEVICE_OUT_USB_DEVICE = 0x4000;
    public static final int DEVICE_OUT_REMOTE_SUBMIX = 0x8000;

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
                                              DEVICE_OUT_AUX_DIGITAL |
                                              DEVICE_OUT_ANLG_DOCK_HEADSET |
                                              DEVICE_OUT_DGTL_DOCK_HEADSET |
                                              DEVICE_OUT_USB_ACCESSORY |
                                              DEVICE_OUT_USB_DEVICE |
                                              DEVICE_OUT_REMOTE_SUBMIX |
                                              DEVICE_OUT_DEFAULT);
    public static final int DEVICE_OUT_ALL_A2DP = (DEVICE_OUT_BLUETOOTH_A2DP |
                                                   DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                                                   DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER);
    public static final int DEVICE_OUT_ALL_SCO = (DEVICE_OUT_BLUETOOTH_SCO |
                                                  DEVICE_OUT_BLUETOOTH_SCO_HEADSET |
                                                  DEVICE_OUT_BLUETOOTH_SCO_CARKIT);
    public static final int DEVICE_OUT_ALL_USB = (DEVICE_OUT_USB_ACCESSORY |
                                                  DEVICE_OUT_USB_DEVICE);

    // input devices
    public static final int DEVICE_IN_COMMUNICATION = DEVICE_BIT_IN | 0x1;
    public static final int DEVICE_IN_AMBIENT = DEVICE_BIT_IN | 0x2;
    public static final int DEVICE_IN_BUILTIN_MIC = DEVICE_BIT_IN | 0x4;
    public static final int DEVICE_IN_BLUETOOTH_SCO_HEADSET = DEVICE_BIT_IN | 0x8;
    public static final int DEVICE_IN_WIRED_HEADSET = DEVICE_BIT_IN | 0x10;
    public static final int DEVICE_IN_AUX_DIGITAL = DEVICE_BIT_IN | 0x20;
    public static final int DEVICE_IN_VOICE_CALL = DEVICE_BIT_IN | 0x40;
    public static final int DEVICE_IN_BACK_MIC = DEVICE_BIT_IN | 0x80;
    public static final int DEVICE_IN_REMOTE_SUBMIX = DEVICE_BIT_IN | 0x100;
    public static final int DEVICE_IN_ANLG_DOCK_HEADSET = DEVICE_BIT_IN | 0x200;
    public static final int DEVICE_IN_DGTL_DOCK_HEADSET = DEVICE_BIT_IN | 0x400;
    public static final int DEVICE_IN_USB_ACCESSORY = DEVICE_BIT_IN | 0x800;
    public static final int DEVICE_IN_USB_DEVICE = DEVICE_BIT_IN | 0x1000;
    public static final int DEVICE_IN_DEFAULT = DEVICE_BIT_IN | DEVICE_BIT_DEFAULT;

    public static final int DEVICE_IN_ALL = (DEVICE_IN_COMMUNICATION |
                                             DEVICE_IN_AMBIENT |
                                             DEVICE_IN_BUILTIN_MIC |
                                             DEVICE_IN_BLUETOOTH_SCO_HEADSET |
                                             DEVICE_IN_WIRED_HEADSET |
                                             DEVICE_IN_AUX_DIGITAL |
                                             DEVICE_IN_VOICE_CALL |
                                             DEVICE_IN_BACK_MIC |
                                             DEVICE_IN_REMOTE_SUBMIX |
                                             DEVICE_IN_ANLG_DOCK_HEADSET |
                                             DEVICE_IN_DGTL_DOCK_HEADSET |
                                             DEVICE_IN_USB_ACCESSORY |
                                             DEVICE_IN_USB_DEVICE |
                                             DEVICE_IN_DEFAULT);
    public static final int DEVICE_IN_ALL_SCO = DEVICE_IN_BLUETOOTH_SCO_HEADSET;

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
    public static final String DEVICE_OUT_ANLG_DOCK_HEADSET_NAME = "analog_dock";
    public static final String DEVICE_OUT_DGTL_DOCK_HEADSET_NAME = "digital_dock";
    public static final String DEVICE_OUT_USB_ACCESSORY_NAME = "usb_accessory";
    public static final String DEVICE_OUT_USB_DEVICE_NAME = "usb_device";
    public static final String DEVICE_OUT_REMOTE_SUBMIX_NAME = "remote_submix";

    public static String getDeviceName(int device)
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
        case DEVICE_OUT_AUX_DIGITAL:
            return DEVICE_OUT_AUX_DIGITAL_NAME;
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
        case DEVICE_OUT_DEFAULT:
        default:
            return "";
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
    private static final int NUM_FORCE_CONFIG = 12;
    public static final int FORCE_DEFAULT = FORCE_NONE;

    // usage for setForceUse, must match AudioSystem::force_use
    public static final int FOR_COMMUNICATION = 0;
    public static final int FOR_MEDIA = 1;
    public static final int FOR_RECORD = 2;
    public static final int FOR_DOCK = 3;
    public static final int FOR_SYSTEM = 4;
    private static final int NUM_FORCE_USE = 5;

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
}
