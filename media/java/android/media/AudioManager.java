/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

/**
 * AudioManager provides access to volume and ringer mode control.
 * <p>
 * Use <code>Context.getSystemService(Context.AUDIO_SERVICE)</code> to get
 * an instance of this class.
 */
public class AudioManager {

    private final Context mContext;
    private final Handler mHandler;

    // used to listen for updates to the sound effects settings so we don't
    // poll it for every UI sound
    private ContentObserver mContentObserver;


    private static String TAG = "AudioManager";
    private static boolean DEBUG = false;
    private static boolean localLOGV = DEBUG || android.util.Config.LOGV;

    /**
     * Broadcast intent, a hint for applications that audio is about to become
     * 'noisy' due to a change in audio outputs. For example, this intent may
     * be sent when a wired headset is unplugged, or when an A2DP audio
     * sink is disconnected, and the audio system is about to automatically
     * switch audio route to the speaker. Applications that are controlling
     * audio streams may consider pausing, reducing volume or some other action
     * on receipt of this intent so as not to surprise the user with audio
     * from the speaker.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY";

    /**
     * Sticky broadcast intent action indicating that the ringer mode has
     * changed. Includes the new ringer mode.
     *
     * @see #EXTRA_RINGER_MODE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String RINGER_MODE_CHANGED_ACTION = "android.media.RINGER_MODE_CHANGED";

    /**
     * The new ringer mode.
     *
     * @see #RINGER_MODE_CHANGED_ACTION
     * @see #RINGER_MODE_NORMAL
     * @see #RINGER_MODE_SILENT
     * @see #RINGER_MODE_VIBRATE
     */
    public static final String EXTRA_RINGER_MODE = "android.media.EXTRA_RINGER_MODE";

    /**
     * Broadcast intent action indicating that the vibrate setting has
     * changed. Includes the vibrate type and its new setting.
     *
     * @see #EXTRA_VIBRATE_TYPE
     * @see #EXTRA_VIBRATE_SETTING
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String VIBRATE_SETTING_CHANGED_ACTION = "android.media.VIBRATE_SETTING_CHANGED";

    /**
     * @hide Broadcast intent when the volume for a particular stream type changes.
     * Includes the stream and the new volume
     *
     * @see #EXTRA_VOLUME_STREAM_TYPE
     * @see #EXTRA_VOLUME_STREAM_VALUE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";

    /**
     * The new vibrate setting for a particular type.
     *
     * @see #VIBRATE_SETTING_CHANGED_ACTION
     * @see #EXTRA_VIBRATE_TYPE
     * @see #VIBRATE_SETTING_ON
     * @see #VIBRATE_SETTING_OFF
     * @see #VIBRATE_SETTING_ONLY_SILENT
     */
    public static final String EXTRA_VIBRATE_SETTING = "android.media.EXTRA_VIBRATE_SETTING";

    /**
     * The vibrate type whose setting has changed.
     *
     * @see #VIBRATE_SETTING_CHANGED_ACTION
     * @see #VIBRATE_TYPE_NOTIFICATION
     * @see #VIBRATE_TYPE_RINGER
     */
    public static final String EXTRA_VIBRATE_TYPE = "android.media.EXTRA_VIBRATE_TYPE";

    /**
     * @hide The stream type for the volume changed intent.
     */
    public static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    /**
     * @hide The volume associated with the stream for the volume changed intent.
     */
    public static final String EXTRA_VOLUME_STREAM_VALUE =
        "android.media.EXTRA_VOLUME_STREAM_VALUE";

    /** The audio stream for phone calls */
    public static final int STREAM_VOICE_CALL = AudioSystem.STREAM_VOICE_CALL;
    /** The audio stream for system sounds */
    public static final int STREAM_SYSTEM = AudioSystem.STREAM_SYSTEM;
    /** The audio stream for the phone ring */
    public static final int STREAM_RING = AudioSystem.STREAM_RING;
    /** The audio stream for music playback */
    public static final int STREAM_MUSIC = AudioSystem.STREAM_MUSIC;
    /** The audio stream for alarms */
    public static final int STREAM_ALARM = AudioSystem.STREAM_ALARM;
    /** The audio stream for notifications */
    public static final int STREAM_NOTIFICATION = AudioSystem.STREAM_NOTIFICATION;
    /** @hide The audio stream for phone calls when connected to bluetooth */
    public static final int STREAM_BLUETOOTH_SCO = AudioSystem.STREAM_BLUETOOTH_SCO;
    /** Number of audio streams */
    /**
     * @deprecated Use AudioSystem.getNumStreamTypes() instead
     */
    public static final int NUM_STREAMS = AudioSystem.NUM_STREAMS;


    /** @hide Maximum volume index values for audio streams */
    public static final int[] MAX_STREAM_VOLUME = new int[] {
        6,  // STREAM_VOICE_CALL
        8,  // STREAM_SYSTEM
        8,  // STREAM_RING
        16, // STREAM_MUSIC
        8,  // STREAM_ALARM
        8,  // STREAM_NOTIFICATION
        16, // STREAM_BLUETOOTH_SCO
    };

    /**  @hide Default volume index values for audio streams */
    public static final int[] DEFAULT_STREAM_VOLUME = new int[] {
        4,  // STREAM_VOICE_CALL
        5,  // STREAM_SYSTEM
        5,  // STREAM_RING
        11, // STREAM_MUSIC
        6,  // STREAM_ALARM
        5,  // STREAM_NOTIFICATION
        7   // STREAM_BLUETOOTH_SCO
    };

    /**
     * Increase the ringer volume.
     *
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     */
    public static final int ADJUST_RAISE = 1;

    /**
     * Decrease the ringer volume.
     *
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     */
    public static final int ADJUST_LOWER = -1;

    /**
     * Maintain the previous ringer volume. This may be useful when needing to
     * show the volume toast without actually modifying the volume.
     *
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     */
    public static final int ADJUST_SAME = 0;

    // Flags should be powers of 2!

    /**
     * Show a toast containing the current volume.
     *
     * @see #adjustStreamVolume(int, int, int)
     * @see #adjustVolume(int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #setRingerMode(int)
     */
    public static final int FLAG_SHOW_UI = 1 << 0;

    /**
     * Whether to include ringer modes as possible options when changing volume.
     * For example, if true and volume level is 0 and the volume is adjusted
     * with {@link #ADJUST_LOWER}, then the ringer mode may switch the silent or
     * vibrate mode.
     * <p>
     * By default this is on for the ring stream. If this flag is included,
     * this behavior will be present regardless of the stream type being
     * affected by the ringer mode.
     *
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     */
    public static final int FLAG_ALLOW_RINGER_MODES = 1 << 1;

    /**
     * Whether to play a sound when changing the volume.
     * <p>
     * If this is given to {@link #adjustVolume(int, int)} or
     * {@link #adjustSuggestedStreamVolume(int, int, int)}, it may be ignored
     * in some cases (for example, the decided stream type is not
     * {@link AudioManager#STREAM_RING}, or the volume is being adjusted
     * downward).
     *
     * @see #adjustStreamVolume(int, int, int)
     * @see #adjustVolume(int, int)
     * @see #setStreamVolume(int, int, int)
     */
    public static final int FLAG_PLAY_SOUND = 1 << 2;

    /**
     * Removes any sounds/vibrate that may be in the queue, or are playing (related to
     * changing volume).
     */
    public static final int FLAG_REMOVE_SOUND_AND_VIBRATE = 1 << 3;

    /**
     * Whether to vibrate if going into the vibrate ringer mode.
     */
    public static final int FLAG_VIBRATE = 1 << 4;

    /**
     * Ringer mode that will be silent and will not vibrate. (This overrides the
     * vibrate setting.)
     *
     * @see #setRingerMode(int)
     * @see #getRingerMode()
     */
    public static final int RINGER_MODE_SILENT = 0;

    /**
     * Ringer mode that will be silent and will vibrate. (This will cause the
     * phone ringer to always vibrate, but the notification vibrate to only
     * vibrate if set.)
     *
     * @see #setRingerMode(int)
     * @see #getRingerMode()
     */
    public static final int RINGER_MODE_VIBRATE = 1;

    /**
     * Ringer mode that may be audible and may vibrate. It will be audible if
     * the volume before changing out of this mode was audible. It will vibrate
     * if the vibrate setting is on.
     *
     * @see #setRingerMode(int)
     * @see #getRingerMode()
     */
    public static final int RINGER_MODE_NORMAL = 2;

    /**
     * Vibrate type that corresponds to the ringer.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     * @see #shouldVibrate(int)
     */
    public static final int VIBRATE_TYPE_RINGER = 0;

    /**
     * Vibrate type that corresponds to notifications.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     * @see #shouldVibrate(int)
     */
    public static final int VIBRATE_TYPE_NOTIFICATION = 1;

    /**
     * Vibrate setting that suggests to never vibrate.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     */
    public static final int VIBRATE_SETTING_OFF = 0;

    /**
     * Vibrate setting that suggests to vibrate when possible.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     */
    public static final int VIBRATE_SETTING_ON = 1;

    /**
     * Vibrate setting that suggests to only vibrate when in the vibrate ringer
     * mode.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     */
    public static final int VIBRATE_SETTING_ONLY_SILENT = 2;

    /**
     * Suggests using the default stream type. This may not be used in all
     * places a stream type is needed.
     */
    public static final int USE_DEFAULT_STREAM_TYPE = Integer.MIN_VALUE;

    private static IAudioService sService;

    /**
     * @hide
     */
    public AudioManager(Context context) {
        mContext = context;
        mHandler = new Handler(context.getMainLooper());
    }

    private static IAudioService getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }

    /**
     * Adjusts the volume of a particular stream by one step in a direction.
     *
     * @param streamType The stream type to adjust. One of {@link #STREAM_VOICE_CALL},
     * {@link #STREAM_SYSTEM}, {@link #STREAM_RING}, {@link #STREAM_MUSIC} or
     * {@link #STREAM_ALARM}
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param flags One or more flags.
     * @see #adjustVolume(int, int)
     * @see #setStreamVolume(int, int, int)
     */
    public void adjustStreamVolume(int streamType, int direction, int flags) {
        IAudioService service = getService();
        try {
            service.adjustStreamVolume(streamType, direction, flags);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in adjustStreamVolume", e);
        }
    }

    /**
     * Adjusts the volume of the most relevant stream. For example, if a call is
     * active, it will have the highest priority regardless of if the in-call
     * screen is showing. Another example, if music is playing in the background
     * and a call is not active, the music stream will be adjusted.
     *
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param flags One or more flags.
     * @see #adjustSuggestedStreamVolume(int, int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     */
    public void adjustVolume(int direction, int flags) {
        IAudioService service = getService();
        try {
            service.adjustVolume(direction, flags);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in adjustVolume", e);
        }
    }

    /**
     * Adjusts the volume of the most relevant stream, or the given fallback
     * stream.
     *
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param suggestedStreamType The stream type that will be used if there
     *            isn't a relevant stream. {@link #USE_DEFAULT_STREAM_TYPE} is valid here.
     * @param flags One or more flags.
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {
        IAudioService service = getService();
        try {
            service.adjustSuggestedStreamVolume(direction, suggestedStreamType, flags);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in adjustVolume", e);
        }
    }

    /**
     * Returns the current ringtone mode.
     *
     * @return The current ringtone mode, one of {@link #RINGER_MODE_NORMAL},
     *         {@link #RINGER_MODE_SILENT}, or {@link #RINGER_MODE_VIBRATE}.
     * @see #setRingerMode(int)
     */
    public int getRingerMode() {
        IAudioService service = getService();
        try {
            return service.getRingerMode();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getRingerMode", e);
            return RINGER_MODE_NORMAL;
        }
    }

    /**
     * Returns the maximum volume index for a particular stream.
     *
     * @param streamType The stream type whose maximum volume index is returned.
     * @return The maximum valid volume index for the stream.
     * @see #getStreamVolume(int)
     */
    public int getStreamMaxVolume(int streamType) {
        IAudioService service = getService();
        try {
            return service.getStreamMaxVolume(streamType);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getStreamMaxVolume", e);
            return 0;
        }
    }

    /**
     * Returns the current volume index for a particular stream.
     *
     * @param streamType The stream type whose volume index is returned.
     * @return The current volume index for the stream.
     * @see #getStreamMaxVolume(int)
     * @see #setStreamVolume(int, int, int)
     */
    public int getStreamVolume(int streamType) {
        IAudioService service = getService();
        try {
            return service.getStreamVolume(streamType);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getStreamVolume", e);
            return 0;
        }
    }

    /**
     * Sets the ringer mode.
     * <p>
     * Silent mode will mute the volume and will not vibrate. Vibrate mode will
     * mute the volume and vibrate. Normal mode will be audible and may vibrate
     * according to user settings.
     *
     * @param ringerMode The ringer mode, one of {@link #RINGER_MODE_NORMAL},
     *            {@link #RINGER_MODE_SILENT}, or {@link #RINGER_MODE_VIBRATE}.
     * @see #getRingerMode()
     */
    public void setRingerMode(int ringerMode) {
        IAudioService service = getService();
        try {
            service.setRingerMode(ringerMode);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setRingerMode", e);
        }
    }

    /**
     * Sets the volume index for a particular stream.
     *
     * @param streamType The stream whose volume index should be set.
     * @param index The volume index to set. See
     *            {@link #getStreamMaxVolume(int)} for the largest valid value.
     * @param flags One or more flags.
     * @see #getStreamMaxVolume(int)
     * @see #getStreamVolume(int)
     */
    public void setStreamVolume(int streamType, int index, int flags) {
        IAudioService service = getService();
        try {
            service.setStreamVolume(streamType, index, flags);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setStreamVolume", e);
        }
    }

    /**
     * Solo or unsolo a particular stream. All other streams are muted.
     * <p>
     * The solo command is protected against client process death: if a process
     * with an active solo request on a stream dies, all streams that were muted
     * because of this request will be unmuted automatically.
     * <p>
     * The solo requests for a given stream are cumulative: the AudioManager
     * can receive several solo requests from one or more clients and the stream
     * will be unsoloed only when the same number of unsolo requests are received.
     * <p>
     * For a better user experience, applications MUST unsolo a soloed stream
     * in onPause() and solo is again in onResume() if appropriate.
     *
     * @param streamType The stream to be soloed/unsoloed.
     * @param state The required solo state: true for solo ON, false for solo OFF
     */
    public void setStreamSolo(int streamType, boolean state) {
        IAudioService service = getService();
        try {
            service.setStreamSolo(streamType, state, mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setStreamSolo", e);
        }
    }

    /**
     * Mute or unmute an audio stream.
     * <p>
     * The mute command is protected against client process death: if a process
     * with an active mute request on a stream dies, this stream will be unmuted
     * automatically.
     * <p>
     * The mute requests for a given stream are cumulative: the AudioManager
     * can receive several mute requests from one or more clients and the stream
     * will be unmuted only when the same number of unmute requests are received.
     * <p>
     * For a better user experience, applications MUST unmute a muted stream
     * in onPause() and mute is again in onResume() if appropriate.
     *
     * @param streamType The stream to be muted/unmuted.
     * @param state The required mute state: true for mute ON, false for mute OFF
     */
    public void setStreamMute(int streamType, boolean state) {
        IAudioService service = getService();
        try {
            service.setStreamMute(streamType, state, mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setStreamMute", e);
        }
    }

    /**
     * Returns whether a particular type should vibrate according to user
     * settings and the current ringer mode.
     * <p>
     * This shouldn't be needed by most clients that use notifications to
     * vibrate. The notification manager will not vibrate if the policy doesn't
     * allow it, so the client should always set a vibrate pattern and let the
     * notification manager control whether or not to actually vibrate.
     *
     * @param vibrateType The type of vibrate. One of
     *            {@link #VIBRATE_TYPE_NOTIFICATION} or
     *            {@link #VIBRATE_TYPE_RINGER}.
     * @return Whether the type should vibrate at the instant this method is
     *         called.
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     */
    public boolean shouldVibrate(int vibrateType) {
        IAudioService service = getService();
        try {
            return service.shouldVibrate(vibrateType);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in shouldVibrate", e);
            return false;
        }
    }

    /**
     * Returns whether the user's vibrate setting for a vibrate type.
     * <p>
     * This shouldn't be needed by most clients that want to vibrate, instead
     * see {@link #shouldVibrate(int)}.
     *
     * @param vibrateType The type of vibrate. One of
     *            {@link #VIBRATE_TYPE_NOTIFICATION} or
     *            {@link #VIBRATE_TYPE_RINGER}.
     * @return The vibrate setting, one of {@link #VIBRATE_SETTING_ON},
     *         {@link #VIBRATE_SETTING_OFF}, or
     *         {@link #VIBRATE_SETTING_ONLY_SILENT}.
     * @see #setVibrateSetting(int, int)
     * @see #shouldVibrate(int)
     */
    public int getVibrateSetting(int vibrateType) {
        IAudioService service = getService();
        try {
            return service.getVibrateSetting(vibrateType);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getVibrateSetting", e);
            return VIBRATE_SETTING_OFF;
        }
    }

    /**
     * Sets the setting for when the vibrate type should vibrate.
     *
     * @param vibrateType The type of vibrate. One of
     *            {@link #VIBRATE_TYPE_NOTIFICATION} or
     *            {@link #VIBRATE_TYPE_RINGER}.
     * @param vibrateSetting The vibrate setting, one of
     *            {@link #VIBRATE_SETTING_ON},
     *            {@link #VIBRATE_SETTING_OFF}, or
     *            {@link #VIBRATE_SETTING_ONLY_SILENT}.
     * @see #getVibrateSetting(int)
     * @see #shouldVibrate(int)
     */
    public void setVibrateSetting(int vibrateType, int vibrateSetting) {
        IAudioService service = getService();
        try {
            service.setVibrateSetting(vibrateType, vibrateSetting);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setVibrateSetting", e);
        }
    }

    /**
     * Sets the speakerphone on or off.
     *
     * @param on set <var>true</var> to turn on speakerphone;
     *           <var>false</var> to turn it off
     */
    public void setSpeakerphoneOn(boolean on){
        setRouting(MODE_IN_CALL, on ? ROUTE_SPEAKER : ROUTE_EARPIECE, ROUTE_ALL);
    }

    /**
     * Checks whether the speakerphone is on or off.
     *
     * @return true if speakerphone is on, false if it's off
     */
    public boolean isSpeakerphoneOn() {
        return (getRouting(MODE_IN_CALL) & ROUTE_SPEAKER) == 0 ? false : true;
     }

    /**
     * Sets audio routing to the Bluetooth headset on or off.
     *
     * @param on set <var>true</var> to route SCO (voice) audio to/from Bluetooth
     *           headset; <var>false</var> to route audio to/from phone earpiece
     */
    public void setBluetoothScoOn(boolean on){
        // Don't disable A2DP when turning off SCO.
        // A2DP does not affect in-call routing.
        setRouting(MODE_RINGTONE,
               on ? ROUTE_BLUETOOTH_SCO: ROUTE_SPEAKER, ROUTE_ALL & ~ROUTE_BLUETOOTH_A2DP);
        setRouting(MODE_NORMAL,
                on ? ROUTE_BLUETOOTH_SCO: ROUTE_SPEAKER, ROUTE_ALL & ~ROUTE_BLUETOOTH_A2DP);
        setRouting(MODE_IN_CALL,
                on ? ROUTE_BLUETOOTH_SCO: ROUTE_EARPIECE, ROUTE_ALL);
    }

    /**
     * Checks whether audio routing to the Bluetooth headset is on or off.
     *
     * @return true if SCO audio is being routed to/from Bluetooth headset;
     *         false if otherwise
     */
    public boolean isBluetoothScoOn() {
        return (getRouting(MODE_IN_CALL) & ROUTE_BLUETOOTH_SCO) == 0 ? false : true;
    }

    /**
     * Sets A2DP audio routing to the Bluetooth headset on or off.
     *
     * @param on set <var>true</var> to route A2DP audio to/from Bluetooth
     *           headset; <var>false</var> disable A2DP audio
     */
    public void setBluetoothA2dpOn(boolean on){
        // the audio flinger chooses A2DP as a higher priority,
        // so there is no need to disable other routes.
        setRouting(MODE_RINGTONE,
               on ? ROUTE_BLUETOOTH_A2DP: 0, ROUTE_BLUETOOTH_A2DP);
        setRouting(MODE_NORMAL,
                on ? ROUTE_BLUETOOTH_A2DP: 0, ROUTE_BLUETOOTH_A2DP);
    }

    /**
     * Checks whether A2DP audio routing to the Bluetooth headset is on or off.
     *
     * @return true if A2DP audio is being routed to/from Bluetooth headset;
     *         false if otherwise
     */
    public boolean isBluetoothA2dpOn() {
        return (getRouting(MODE_NORMAL) & ROUTE_BLUETOOTH_A2DP) == 0 ? false : true;
    }

    /**
     * Sets audio routing to the wired headset on or off.
     *
     * @param on set <var>true</var> to route audio to/from wired
     *           headset; <var>false</var> disable wired headset audio
     * @hide
     */
    public void setWiredHeadsetOn(boolean on){
        // A2DP has higher priority than wired headset, so headset connect/disconnect events
        // should not affect A2DP routing
        setRouting(MODE_NORMAL,
                on ? ROUTE_HEADSET : ROUTE_SPEAKER, ROUTE_ALL & ~ROUTE_BLUETOOTH_A2DP);
        setRouting(MODE_RINGTONE,
                on ? ROUTE_HEADSET | ROUTE_SPEAKER : ROUTE_SPEAKER, ROUTE_ALL & ~ROUTE_BLUETOOTH_A2DP);
        setRouting(MODE_IN_CALL,
                on ? ROUTE_HEADSET : ROUTE_EARPIECE, ROUTE_ALL);
    }

    /**
     * Checks whether audio routing to the wired headset is on or off.
     *
     * @return true if audio is being routed to/from wired headset;
     *         false if otherwise
     * @hide
     */
    public boolean isWiredHeadsetOn() {
        return (getRouting(MODE_NORMAL) & ROUTE_HEADSET) == 0 ? false : true;
    }

    /**
     * Sets the microphone mute on or off.
     *
     * @param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     */
    public void setMicrophoneMute(boolean on){
        IAudioService service = getService();
        try {
            service.setMicrophoneMute(on);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setMicrophoneMute", e);
        }
    }

    /**
     * Checks whether the microphone mute is on or off.
     *
     * @return true if microphone is muted, false if it's not
     */
    public boolean isMicrophoneMute() {
        IAudioService service = getService();
        try {
            return service.isMicrophoneMute();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in isMicrophoneMute", e);
            return false;
        }
    }

    /**
     * Sets the audio mode.
     *
     * @param mode  the requested audio mode (NORMAL, RINGTONE, or IN_CALL).
     *              Informs the HAL about the current audio state so that
     *              it can route the audio appropriately.
     */
    public void setMode(int mode) {
        IAudioService service = getService();
        try {
            service.setMode(mode);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setMode", e);
        }
    }

    /**
     * Returns the current audio mode.
     *
     * @return      the current audio mode (NORMAL, RINGTONE, or IN_CALL).
     *              Returns the current current audio state from the HAL.
     */
    public int getMode() {
        IAudioService service = getService();
        try {
            return service.getMode();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getMode", e);
            return MODE_INVALID;
        }
    }

    /* modes for setMode/getMode/setRoute/getRoute */
    /**
     * Audio harware modes.
     */
    /**
     * Invalid audio mode.
     */
    public static final int MODE_INVALID            = AudioSystem.MODE_INVALID;
    /**
     * Current audio mode. Used to apply audio routing to current mode.
     */
    public static final int MODE_CURRENT            = AudioSystem.MODE_CURRENT;
    /**
     * Normal audio mode: not ringing and no call established.
     */
    public static final int MODE_NORMAL             = AudioSystem.MODE_NORMAL;
    /**
     * Ringing audio mode. An incoming is being signaled.
     */
    public static final int MODE_RINGTONE           = AudioSystem.MODE_RINGTONE;
    /**
     * In call audio mode. A call is established.
     */
    public static final int MODE_IN_CALL            = AudioSystem.MODE_IN_CALL;

    /* Routing bits for setRouting/getRouting API */
    /**
     * Routing audio output to earpiece
     */
    public static final int ROUTE_EARPIECE          = AudioSystem.ROUTE_EARPIECE;
    /**
     * Routing audio output to spaker
     */
    public static final int ROUTE_SPEAKER           = AudioSystem.ROUTE_SPEAKER;
    /**
     * @deprecated use {@link #ROUTE_BLUETOOTH_SCO}
     */
    @Deprecated public static final int ROUTE_BLUETOOTH = AudioSystem.ROUTE_BLUETOOTH_SCO;
    /**
     * Routing audio output to bluetooth SCO
     */
    public static final int ROUTE_BLUETOOTH_SCO     = AudioSystem.ROUTE_BLUETOOTH_SCO;
    /**
     * Routing audio output to headset
     */
    public static final int ROUTE_HEADSET           = AudioSystem.ROUTE_HEADSET;
    /**
     * Routing audio output to bluetooth A2DP
     */
    public static final int ROUTE_BLUETOOTH_A2DP    = AudioSystem.ROUTE_BLUETOOTH_A2DP;
    /**
     * Used for mask parameter of {@link #setRouting(int,int,int)}.
     */
    public static final int ROUTE_ALL               = AudioSystem.ROUTE_ALL;

    /**
     * Sets the audio routing for a specified mode
     *
     * @param mode   audio mode to change route. E.g., MODE_RINGTONE.
     * @param routes bit vector of routes requested, created from one or
     *               more of ROUTE_xxx types. Set bits indicate that route should be on
     * @param mask   bit vector of routes to change, created from one or more of
     * ROUTE_xxx types. Unset bits indicate the route should be left unchanged
     */
    public void setRouting(int mode, int routes, int mask) {
        IAudioService service = getService();
        try {
            service.setRouting(mode, routes, mask);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setRouting", e);
        }
    }

    /**
     * Returns the current audio routing bit vector for a specified mode.
     *
     * @param mode audio mode to get route (e.g., MODE_RINGTONE)
     * @return an audio route bit vector that can be compared with ROUTE_xxx
     * bits
     */
    public int getRouting(int mode) {
        IAudioService service = getService();
        try {
            return service.getRouting(mode);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getRouting", e);
            return -1;
        }
    }

    /**
     * Checks whether any music is active.
     *
     * @return true if any music tracks are active.
     */
    public boolean isMusicActive() {
        IAudioService service = getService();
        try {
            return service.isMusicActive();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in isMusicActive", e);
            return false;
        }
    }

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
    /**
     * @hide
     */
    public void setParameter(String key, String value) {
        IAudioService service = getService();
        try {
            service.setParameter(key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setParameter", e);
        }
    }

    /* Sound effect identifiers */
    /**
     * Keyboard and direction pad click sound
     * @see #playSoundEffect(int)
     */
    public static final int FX_KEY_CLICK = 0;
    /**
     * Focus has moved up
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_UP = 1;
    /**
     * Focus has moved down
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_DOWN = 2;
    /**
     * Focus has moved left
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_LEFT = 3;
    /**
     * Focus has moved right
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_RIGHT = 4;
    /**
     * IME standard keypress sound
     * @see #playSoundEffect(int)
     */
    public static final int FX_KEYPRESS_STANDARD = 5;
    /**
     * IME spacebar keypress sound
     * @see #playSoundEffect(int)
     */
    public static final int FX_KEYPRESS_SPACEBAR = 6;
    /**
     * IME delete keypress sound
     * @see #playSoundEffect(int)
     */
    public static final int FX_KEYPRESS_DELETE = 7;
    /**
     * IME return_keypress sound
     * @see #playSoundEffect(int)
     */
    public static final int FX_KEYPRESS_RETURN = 8;
    /**
     * @hide Number of sound effects
     */
    public static final int NUM_SOUND_EFFECTS = 9;

    /**
     * Plays a sound effect (Key clicks, lid open/close...)
     * @param effectType The type of sound effect. One of
     *            {@link #FX_KEY_CLICK},
     *            {@link #FX_FOCUS_NAVIGATION_UP},
     *            {@link #FX_FOCUS_NAVIGATION_DOWN},
     *            {@link #FX_FOCUS_NAVIGATION_LEFT},
     *            {@link #FX_FOCUS_NAVIGATION_RIGHT},
     *            {@link #FX_KEYPRESS_STANDARD},
     *            {@link #FX_KEYPRESS_SPACEBAR},
     *            {@link #FX_KEYPRESS_DELETE},
     *            {@link #FX_KEYPRESS_RETURN},
     * NOTE: This version uses the UI settings to determine
     * whether sounds are heard or not.
     */
    public void  playSoundEffect(int effectType) {
        if (effectType < 0 || effectType >= NUM_SOUND_EFFECTS) {
            return;
        }

        if (!querySoundEffectsEnabled()) {
            return;
        }

        IAudioService service = getService();
        try {
            service.playSoundEffect(effectType);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in playSoundEffect"+e);
        }
    }

    /**
     * Plays a sound effect (Key clicks, lid open/close...)
     * @param effectType The type of sound effect. One of
     *            {@link #FX_KEY_CLICK},
     *            {@link #FX_FOCUS_NAVIGATION_UP},
     *            {@link #FX_FOCUS_NAVIGATION_DOWN},
     *            {@link #FX_FOCUS_NAVIGATION_LEFT},
     *            {@link #FX_FOCUS_NAVIGATION_RIGHT},
     *            {@link #FX_KEYPRESS_STANDARD},
     *            {@link #FX_KEYPRESS_SPACEBAR},
     *            {@link #FX_KEYPRESS_DELETE},
     *            {@link #FX_KEYPRESS_RETURN},
     * @param volume Sound effect volume
     * NOTE: This version is for applications that have their own
     * settings panel for enabling and controlling volume.
     */
    public void  playSoundEffect(int effectType, float volume) {
        if (effectType < 0 || effectType >= NUM_SOUND_EFFECTS) {
            return;
        }

        IAudioService service = getService();
        try {
            service.playSoundEffectVolume(effectType, volume);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in playSoundEffect"+e);
        }
    }

    /**
     * Settings has an in memory cache, so this is fast.
     */
    private boolean querySoundEffectsEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 0) != 0;
    }


    /**
     *  Load Sound effects.
     *  This method must be called when sound effects are enabled.
     */
    public void loadSoundEffects() {
        IAudioService service = getService();
        try {
            service.loadSoundEffects();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in loadSoundEffects"+e);
        }
    }

    /**
     *  Unload Sound effects.
     *  This method can be called to free some memory when
     *  sound effects are disabled.
     */
    public void unloadSoundEffects() {
        IAudioService service = getService();
        try {
            service.unloadSoundEffects();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in unloadSoundEffects"+e);
        }
    }

     /**
      * {@hide}
      */
     private IBinder mICallBack = new Binder();
}
