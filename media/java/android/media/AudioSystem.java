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
    /**
     * @deprecated Use {@link #numStreamTypes() instead}
     */
    public static final int NUM_STREAMS = 5;

    // Expose only the getter method publicly so we can change it in the future
    private static final int NUM_STREAM_TYPES = 6;
    public static final int getNumStreamTypes() { return NUM_STREAM_TYPES; }
    
    /* max and min volume levels */
    /* Maximum volume setting, for use with setVolume(int,int) */
    public static final int MAX_VOLUME = 100;
    /* Minimum volume setting, for use with setVolume(int,int) */
    public static final int MIN_VOLUME = 0;

    /*
     * Sets the volume of a specified audio stream.
     *
     * param type   the stream type to set the volume of (e.g. STREAM_MUSIC)
     * param volume the volume level to set (0-100)
     * return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    public static native int setVolume(int type, int volume);

    /*
     * Returns the volume of a specified audio stream.
     *
     * param type the stream type to get the volume of (e.g. STREAM_MUSIC)
     * return the current volume (0-100)
     */
    public static native int getVolume(int type);

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
    public static native int setMode(int mode);

    /*
     * Returns the current audio mode.
     *
     * return      the current audio mode (NORMAL, RINGTONE, or IN_CALL).
     *              Returns the current current audio state from the HAL.
     */
    public static native int getMode();

    /* modes for setMode/getMode/setRoute/getRoute */
    public static final int MODE_INVALID            = -2;
    public static final int MODE_CURRENT            = -1;
    public static final int MODE_NORMAL             = 0;
    public static final int MODE_RINGTONE           = 1;
    public static final int MODE_IN_CALL            = 2;
    public static final int NUM_MODES               = 3;
    

    /* Routing bits for setRouting/getRouting API */
    public static final int ROUTE_EARPIECE          = (1 << 0);
    public static final int ROUTE_SPEAKER           = (1 << 1);
     
    /** @deprecated use {@link #ROUTE_BLUETOOTH_SCO} */        
    @Deprecated public static final int ROUTE_BLUETOOTH = (1 << 2);
    public static final int ROUTE_BLUETOOTH_SCO     = (1 << 2);
    public static final int ROUTE_HEADSET           = (1 << 3);
    public static final int ROUTE_BLUETOOTH_A2DP    = (1 << 4);
    public static final int ROUTE_ALL               = 0xFFFFFFFF;  

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
    public static native int setRouting(int mode, int routes, int mask);

    /*
     * Returns the current audio routing bit vector for a specified mode.
     *
     * param mode audio mode to change route (e.g., MODE_RINGTONE)
     * return an audio route bit vector that can be compared with ROUTE_xxx
     * bits
     */
    public static native int getRouting(int mode);

    /*
     * Checks whether any music is active.
     *
     * return true if any music tracks are active.
     */
    public static native boolean isMusicActive();

    /*
     * Sets a generic audio configuration parameter. The use of these parameters
     * are platform dependant, see libaudio
     *
     * ** Temporary interface - DO NOT USE
     *
     * TODO: Replace with a more generic key:value get/set mechanism
     *
     * param key   name of parameter to set. Must not be null.
     * param value value of parameter. Must not be null.
     */
    public static native void setParameter(String key, String value);

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
         * - UDIO_STATUS_ERROR
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
}
