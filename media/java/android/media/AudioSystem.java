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
 * TO UPDATE THE CORRESPONDING NATIVE GLUE.  THANK YOU FOR YOUR COOPERATION
 */

/**
 * @hide
 */
public class AudioSystem
{
    /* FIXME: Need to finalize this and correlate with native layer */
    /*
     * If these are modified, please also update Settings.System.VOLUME_SETTINGS
     * and attrs.xml
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
     * param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     * return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    public static native int muteMicrophone(boolean on);

    /*
     * Checks whether the microphone mute is on or off.
     *
     * return true if microphone is muted, false if it's not
     */
    public static native boolean isMicrophoneMuted();

    /*
     * Sets the audio mode.
     *
     * param mode  the requested audio mode (NORMAL, RINGTONE, or IN_CALL).
     *              Informs the HAL about the current audio state so that
     *              it can route the audio appropriately.
     * return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    /** @deprecated use {@link #setPhoneState(int)} */
    public static int setMode(int mode) {
        return AUDIO_STATUS_ERROR;
    }
    /*
     * Returns the current audio mode.
     *
     * return      the current audio mode (NORMAL, RINGTONE, or IN_CALL).
     *              Returns the current current audio state from the HAL.
     *              
     */
    /** @deprecated Do not use. */
    public static int getMode() {
        return MODE_INVALID;
    }

    /* modes for setPhoneState */
    public static final int MODE_INVALID            = -2;
    public static final int MODE_CURRENT            = -1;
    public static final int MODE_NORMAL             = 0;
    public static final int MODE_RINGTONE           = 1;
    public static final int MODE_IN_CALL            = 2;
    public static final int MODE_IN_COMMUNICATION   = 3;
    public static final int NUM_MODES               = 4;


    /* Routing bits for setRouting/getRouting API */
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
     * Sets the audio routing for a specified mode
     *
     * param mode   audio mode to change route. E.g., MODE_RINGTONE.
     * param routes bit vector of routes requested, created from one or
     *               more of ROUTE_xxx types. Set bits indicate that route should be on
     * param mask   bit vector of routes to change, created from one or more of
     * ROUTE_xxx types. Unset bits indicate the route should be left unchanged
     * return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    /** @deprecated use {@link #setDeviceConnectionState(int,int,String)} */
    public static int setRouting(int mode, int routes, int mask) {
        return AUDIO_STATUS_ERROR;
    }

    /*
     * Returns the current audio routing bit vector for a specified mode.
     *
     * param mode audio mode to change route (e.g., MODE_RINGTONE)
     * return an audio route bit vector that can be compared with ROUTE_xxx
     * bits
     */
    /** @deprecated use {@link #getDeviceConnectionState(int,String)} */
    public static int getRouting(int mode) {
        return 0;
    }

    /*
     * Checks whether the specified stream type is active.
     *
     * return true if any track playing on this stream is active.
     */
    public static native boolean isStreamActive(int stream);

    /*
     * Sets a group generic audio configuration parameters. The use of these parameters
     * are platform dependant, see libaudio
     *
     * param keyValuePairs  list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     */
    public static native int setParameters(String keyValuePairs);

    /*
     * Gets a group generic audio configuration parameters. The use of these parameters
     * are platform dependant, see libaudio
     *
     * param keys  list of parameters
     * return value: list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     */
    public static native String getParameters(String keys);

    /*
    private final static String TAG = "audio";

    private void log(String msg) {
        Log.d(TAG, "[AudioSystem] " + msg);
    }
    */

    // These match the enum in libs/android_runtime/android_media_AudioSystem.cpp
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
     * param cb the callback to run
     */
    public static void setErrorCallback(ErrorCallback cb)
    {
        mErrorCallback = cb;
    }

    private static void errorCallbackFromNative(int error)
    {
        if (mErrorCallback != null) {
            mErrorCallback.onError(error);
        }
    }

    /*
     * AudioPolicyService methods
     */

    // output devices
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
    public static final int DEVICE_OUT_DEFAULT = 0x8000;
    // input devices
    public static final int DEVICE_IN_COMMUNICATION = 0x10000;
    public static final int DEVICE_IN_AMBIENT = 0x20000;
    public static final int DEVICE_IN_BUILTIN_MIC1 = 0x40000;
    public static final int DEVICE_IN_BUILTIN_MIC2 = 0x80000;
    public static final int DEVICE_IN_MIC_ARRAY = 0x100000;
    public static final int DEVICE_IN_BLUETOOTH_SCO_HEADSET = 0x200000;
    public static final int DEVICE_IN_WIRED_HEADSET = 0x400000;
    public static final int DEVICE_IN_AUX_DIGITAL = 0x800000;
    public static final int DEVICE_IN_DEFAULT = 0x80000000;

    // device states
    public static final int DEVICE_STATE_UNAVAILABLE = 0;
    public static final int DEVICE_STATE_AVAILABLE = 1;

    // phone state
    public static final int PHONE_STATE_OFFCALL = 0;
    public static final int PHONE_STATE_RINGING = 1;
    public static final int PHONE_STATE_INCALL = 2;

    // config for setForceUse
    public static final int FORCE_NONE = 0;
    public static final int FORCE_SPEAKER = 1;
    public static final int FORCE_HEADPHONES = 2;
    public static final int FORCE_BT_SCO = 3;
    public static final int FORCE_BT_A2DP = 4;
    public static final int FORCE_WIRED_ACCESSORY = 5;
    public static final int FORCE_BT_CAR_DOCK = 6;
    public static final int FORCE_BT_DESK_DOCK = 7;
    public static final int FORCE_DEFAULT = FORCE_NONE;

    // usage for serForceUse
    public static final int FOR_COMMUNICATION = 0;
    public static final int FOR_MEDIA = 1;
    public static final int FOR_RECORD = 2;
    public static final int FOR_DOCK = 3;

    public static native int setDeviceConnectionState(int device, int state, String device_address);
    public static native int getDeviceConnectionState(int device, String device_address);
    public static native int setPhoneState(int state);
    public static native int setRingerMode(int mode, int mask);
    public static native int setForceUse(int usage, int config);
    public static native int getForceUse(int usage);
    public static native int initStreamVolume(int stream, int indexMin, int indexMax);
    public static native int setStreamVolumeIndex(int stream, int index);
    public static native int getStreamVolumeIndex(int stream);
}
