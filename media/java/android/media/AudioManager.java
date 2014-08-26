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

import android.Manifest;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.RemoteController.OnClientUpdateListener;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionLegacyHelper;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.ArrayList;


/**
 * AudioManager provides access to volume and ringer mode control.
 * <p>
 * Use <code>Context.getSystemService(Context.AUDIO_SERVICE)</code> to get
 * an instance of this class.
 */
public class AudioManager {

    private final Context mContext;
    private long mVolumeKeyUpTime;
    private final boolean mUseMasterVolume;
    private final boolean mUseVolumeKeySounds;
    private final boolean mUseFixedVolume;
    private final Binder mToken = new Binder();
    private static String TAG = "AudioManager";
    AudioPortEventHandler mAudioPortEventHandler;

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
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode and listen to {@link #RINGER_MODE_CHANGED_ACTION} instead.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String VIBRATE_SETTING_CHANGED_ACTION =
        "android.media.VIBRATE_SETTING_CHANGED";

    /**
     * @hide Broadcast intent when the volume for a particular stream type changes.
     * Includes the stream, the new volume and previous volumes.
     * Notes:
     *  - for internal platform use only, do not make public,
     *  - never used for "remote" volume changes
     *
     * @see #EXTRA_VOLUME_STREAM_TYPE
     * @see #EXTRA_VOLUME_STREAM_VALUE
     * @see #EXTRA_PREV_VOLUME_STREAM_VALUE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";

    /**
     * @hide Broadcast intent when the master volume changes.
     * Includes the new volume
     *
     * @see #EXTRA_MASTER_VOLUME_VALUE
     * @see #EXTRA_PREV_MASTER_VOLUME_VALUE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String MASTER_VOLUME_CHANGED_ACTION =
        "android.media.MASTER_VOLUME_CHANGED_ACTION";

    /**
     * @hide Broadcast intent when the master mute state changes.
     * Includes the the new volume
     *
     * @see #EXTRA_MASTER_VOLUME_MUTED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String MASTER_MUTE_CHANGED_ACTION =
        "android.media.MASTER_MUTE_CHANGED_ACTION";

    /**
     * The new vibrate setting for a particular type.
     *
     * @see #VIBRATE_SETTING_CHANGED_ACTION
     * @see #EXTRA_VIBRATE_TYPE
     * @see #VIBRATE_SETTING_ON
     * @see #VIBRATE_SETTING_OFF
     * @see #VIBRATE_SETTING_ONLY_SILENT
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode and listen to {@link #RINGER_MODE_CHANGED_ACTION} instead.
     */
    public static final String EXTRA_VIBRATE_SETTING = "android.media.EXTRA_VIBRATE_SETTING";

    /**
     * The vibrate type whose setting has changed.
     *
     * @see #VIBRATE_SETTING_CHANGED_ACTION
     * @see #VIBRATE_TYPE_NOTIFICATION
     * @see #VIBRATE_TYPE_RINGER
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode and listen to {@link #RINGER_MODE_CHANGED_ACTION} instead.
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

    /**
     * @hide The previous volume associated with the stream for the volume changed intent.
     */
    public static final String EXTRA_PREV_VOLUME_STREAM_VALUE =
        "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    /**
     * @hide The new master volume value for the master volume changed intent.
     * Value is integer between 0 and 100 inclusive.
     */
    public static final String EXTRA_MASTER_VOLUME_VALUE =
        "android.media.EXTRA_MASTER_VOLUME_VALUE";

    /**
     * @hide The previous master volume value for the master volume changed intent.
     * Value is integer between 0 and 100 inclusive.
     */
    public static final String EXTRA_PREV_MASTER_VOLUME_VALUE =
        "android.media.EXTRA_PREV_MASTER_VOLUME_VALUE";

    /**
     * @hide The new master volume mute state for the master mute changed intent.
     * Value is boolean
     */
    public static final String EXTRA_MASTER_VOLUME_MUTED =
        "android.media.EXTRA_MASTER_VOLUME_MUTED";

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
    /** @hide The audio stream for enforced system sounds in certain countries (e.g camera in Japan) */
    public static final int STREAM_SYSTEM_ENFORCED = AudioSystem.STREAM_SYSTEM_ENFORCED;
    /** The audio stream for DTMF Tones */
    public static final int STREAM_DTMF = AudioSystem.STREAM_DTMF;
    /** @hide The audio stream for text to speech (TTS) */
    public static final int STREAM_TTS = AudioSystem.STREAM_TTS;
    /** Number of audio streams */
    /**
     * @deprecated Use AudioSystem.getNumStreamTypes() instead
     */
    @Deprecated public static final int NUM_STREAMS = AudioSystem.NUM_STREAMS;


    /**  @hide Default volume index values for audio streams */
    public static final int[] DEFAULT_STREAM_VOLUME = new int[] {
        4,  // STREAM_VOICE_CALL
        7,  // STREAM_SYSTEM
        5,  // STREAM_RING
        11, // STREAM_MUSIC
        6,  // STREAM_ALARM
        5,  // STREAM_NOTIFICATION
        7,  // STREAM_BLUETOOTH_SCO
        7,  // STREAM_SYSTEM_ENFORCED
        11, // STREAM_DTMF
        11  // STREAM_TTS
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
     * Indicates to VolumePanel that the volume slider should be disabled as user
     * cannot change the stream volume
     * @hide
     */
    public static final int FLAG_FIXED_VOLUME = 1 << 5;

    /**
     * Indicates the volume set/adjust call is for Bluetooth absolute volume
     * @hide
     */
    public static final int FLAG_BLUETOOTH_ABS_VOLUME = 1 << 6;

    /**
     * Adjusting the volume was prevented due to silent mode, display a hint in the UI.
     * @hide
     */
    public static final int FLAG_SHOW_SILENT_HINT = 1 << 7;

    /**
     * Indicates the volume call is for Hdmi Cec system audio volume
     * @hide
     */
    public static final int FLAG_HDMI_SYSTEM_AUDIO_VOLUME = 1 << 8;

    /**
     * Indicates that this should only be handled if media is actively playing.
     * @hide
     */
    public static final int FLAG_ACTIVE_MEDIA_ONLY = 1 << 9;

    /**
     * Like FLAG_SHOW_UI, but only dialog warnings and confirmations, no sliders.
     * @hide
     */
    public static final int FLAG_SHOW_UI_WARNINGS = 1 << 10;

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

    // maximum valid ringer mode value. Values must start from 0 and be contiguous.
    private static final int RINGER_MODE_MAX = RINGER_MODE_NORMAL;

    /**
     * Vibrate type that corresponds to the ringer.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     * @see #shouldVibrate(int)
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
     */
    public static final int VIBRATE_TYPE_RINGER = 0;

    /**
     * Vibrate type that corresponds to notifications.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     * @see #shouldVibrate(int)
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
     */
    public static final int VIBRATE_TYPE_NOTIFICATION = 1;

    /**
     * Vibrate setting that suggests to never vibrate.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
     */
    public static final int VIBRATE_SETTING_OFF = 0;

    /**
     * Vibrate setting that suggests to vibrate when possible.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
     */
    public static final int VIBRATE_SETTING_ON = 1;

    /**
     * Vibrate setting that suggests to only vibrate when in the vibrate ringer
     * mode.
     *
     * @see #setVibrateSetting(int, int)
     * @see #getVibrateSetting(int)
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
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
        mUseMasterVolume = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useMasterVolume);
        mUseVolumeKeySounds = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useVolumeKeySounds);
        mAudioPortEventHandler = new AudioPortEventHandler(this);
        mUseFixedVolume = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useFixedVolume);
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
     * Sends a simulated key event for a media button.
     * To simulate a key press, you must first send a KeyEvent built with a
     * {@link KeyEvent#ACTION_DOWN} action, then another event with the {@link KeyEvent#ACTION_UP}
     * action.
     * <p>The key event will be sent to the current media key event consumer which registered with
     * {@link AudioManager#registerMediaButtonEventReceiver(PendingIntent)}.
     * @param keyEvent a {@link KeyEvent} instance whose key code is one of
     *     {@link KeyEvent#KEYCODE_MUTE},
     *     {@link KeyEvent#KEYCODE_HEADSETHOOK},
     *     {@link KeyEvent#KEYCODE_MEDIA_PLAY},
     *     {@link KeyEvent#KEYCODE_MEDIA_PAUSE},
     *     {@link KeyEvent#KEYCODE_MEDIA_PLAY_PAUSE},
     *     {@link KeyEvent#KEYCODE_MEDIA_STOP},
     *     {@link KeyEvent#KEYCODE_MEDIA_NEXT},
     *     {@link KeyEvent#KEYCODE_MEDIA_PREVIOUS},
     *     {@link KeyEvent#KEYCODE_MEDIA_REWIND},
     *     {@link KeyEvent#KEYCODE_MEDIA_RECORD},
     *     {@link KeyEvent#KEYCODE_MEDIA_FAST_FORWARD},
     *     {@link KeyEvent#KEYCODE_MEDIA_CLOSE},
     *     {@link KeyEvent#KEYCODE_MEDIA_EJECT},
     *     or {@link KeyEvent#KEYCODE_MEDIA_AUDIO_TRACK}.
     */
    public void dispatchMediaKeyEvent(KeyEvent keyEvent) {
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        helper.sendMediaButtonEvent(keyEvent, false);
    }

    /**
     * @hide
     */
    public void preDispatchKeyEvent(KeyEvent event, int stream) {
        /*
         * If the user hits another key within the play sound delay, then
         * cancel the sound
         */
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_MUTE
                && mVolumeKeyUpTime + AudioService.PLAY_SOUND_DELAY
                        > SystemClock.uptimeMillis()) {
            /*
             * The user has hit another key during the delay (e.g., 300ms)
             * since the last volume key up, so cancel any sounds.
             */
            if (mUseMasterVolume) {
                adjustMasterVolume(ADJUST_SAME, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            } else {
                adjustSuggestedStreamVolume(ADJUST_SAME,
                        stream, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            }
        }
    }

    /**
     * @hide
     */
    public void handleKeyDown(KeyEvent event, int stream) {
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                /*
                 * Adjust the volume in on key down since it is more
                 * responsive to the user.
                 */
                int flags = FLAG_SHOW_UI | FLAG_VIBRATE;

                if (mUseMasterVolume) {
                    adjustMasterVolume(
                            keyCode == KeyEvent.KEYCODE_VOLUME_UP
                                    ? ADJUST_RAISE
                                    : ADJUST_LOWER,
                            flags);
                } else {
                    adjustSuggestedStreamVolume(
                            keyCode == KeyEvent.KEYCODE_VOLUME_UP
                                    ? ADJUST_RAISE
                                    : ADJUST_LOWER,
                            stream,
                            flags);
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (event.getRepeatCount() == 0) {
                    if (mUseMasterVolume) {
                        setMasterMute(!isMasterMute());
                    } else {
                        // TODO: Actually handle MUTE.
                    }
                }
                break;
        }
    }

    /**
     * @hide
     */
    public void handleKeyUp(KeyEvent event, int stream) {
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                /*
                 * Play a sound. This is done on key up since we don't want the
                 * sound to play when a user holds down volume down to mute.
                 */
                if (mUseVolumeKeySounds) {
                    if (mUseMasterVolume) {
                        adjustMasterVolume(ADJUST_SAME, FLAG_PLAY_SOUND);
                    } else {
                        int flags = FLAG_PLAY_SOUND;
                        adjustSuggestedStreamVolume(
                                ADJUST_SAME,
                                stream,
                                flags);
                    }
                }
                mVolumeKeyUpTime = SystemClock.uptimeMillis();
                break;
        }
    }

    /**
     * Indicates if the device implements a fixed volume policy.
     * <p>Some devices may not have volume control and may operate at a fixed volume,
     * and may not enable muting or changing the volume of audio streams.
     * This method will return true on such devices.
     * <p>The following APIs have no effect when volume is fixed:
     * <ul>
     *   <li> {@link #adjustVolume(int, int)}
     *   <li> {@link #adjustSuggestedStreamVolume(int, int, int)}
     *   <li> {@link #adjustStreamVolume(int, int, int)}
     *   <li> {@link #setStreamVolume(int, int, int)}
     *   <li> {@link #setRingerMode(int)}
     *   <li> {@link #setStreamSolo(int, boolean)}
     *   <li> {@link #setStreamMute(int, boolean)}
     * </ul>
     */
    public boolean isVolumeFixed() {
        return mUseFixedVolume;
    }

    /**
     * Adjusts the volume of a particular stream by one step in a direction.
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
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
            if (mUseMasterVolume) {
                service.adjustMasterVolume(direction, flags, mContext.getOpPackageName());
            } else {
                service.adjustStreamVolume(streamType, direction, flags,
                        mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in adjustStreamVolume", e);
        }
    }

    /**
     * Adjusts the volume of the most relevant stream. For example, if a call is
     * active, it will have the highest priority regardless of if the in-call
     * screen is showing. Another example, if music is playing in the background
     * and a call is not active, the music stream will be adjusted.
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param flags One or more flags.
     * @see #adjustSuggestedStreamVolume(int, int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #isVolumeFixed()
     */
    public void adjustVolume(int direction, int flags) {
        IAudioService service = getService();
        try {
            if (mUseMasterVolume) {
                service.adjustMasterVolume(direction, flags, mContext.getOpPackageName());
            } else {
                MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
                helper.sendAdjustVolumeBy(USE_DEFAULT_STREAM_TYPE, direction, flags);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in adjustVolume", e);
        }
    }

    /**
     * Adjusts the volume of the most relevant stream, or the given fallback
     * stream.
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     *
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param suggestedStreamType The stream type that will be used if there
     *            isn't a relevant stream. {@link #USE_DEFAULT_STREAM_TYPE} is valid here.
     * @param flags One or more flags.
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #isVolumeFixed()
     */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {
        IAudioService service = getService();
        try {
            if (mUseMasterVolume) {
                service.adjustMasterVolume(direction, flags, mContext.getOpPackageName());
            } else {
                MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
                helper.sendAdjustVolumeBy(suggestedStreamType, direction, flags);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in adjustSuggestedStreamVolume", e);
        }
    }

    /**
     * Adjusts the master volume for the device's audio amplifier.
     * <p>
     *
     * @param steps The number of volume steps to adjust. A positive
     *            value will raise the volume.
     * @param flags One or more flags.
     * @hide
     */
    public void adjustMasterVolume(int steps, int flags) {
        IAudioService service = getService();
        try {
            service.adjustMasterVolume(steps, flags, mContext.getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in adjustMasterVolume", e);
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
     * Checks valid ringer mode values.
     *
     * @return true if the ringer mode indicated is valid, false otherwise.
     *
     * @see #setRingerMode(int)
     * @hide
     */
    public static boolean isValidRingerMode(int ringerMode) {
        if (ringerMode < 0 || ringerMode > RINGER_MODE_MAX) {
            return false;
        }
        return true;
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
            if (mUseMasterVolume) {
                return service.getMasterMaxVolume();
            } else {
                return service.getStreamMaxVolume(streamType);
            }
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
            if (mUseMasterVolume) {
                return service.getMasterVolume();
            } else {
                return service.getStreamVolume(streamType);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getStreamVolume", e);
            return 0;
        }
    }

    /**
     * Get last audible volume before stream was muted.
     *
     * @hide
     */
    public int getLastAudibleStreamVolume(int streamType) {
        IAudioService service = getService();
        try {
            if (mUseMasterVolume) {
                return service.getLastAudibleMasterVolume();
            } else {
                return service.getLastAudibleStreamVolume(streamType);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getLastAudibleStreamVolume", e);
            return 0;
        }
    }

    /**
     * Get the stream type whose volume is driving the UI sounds volume.
     * UI sounds are screen lock/unlock, camera shutter, key clicks...
     * It is assumed that this stream type is also tied to ringer mode changes.
     * @hide
     */
    public int getMasterStreamType() {
        IAudioService service = getService();
        try {
            return service.getMasterStreamType();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getMasterStreamType", e);
            return STREAM_RING;
        }
    }

    /**
     * Sets the ringer mode.
     * <p>
     * Silent mode will mute the volume and will not vibrate. Vibrate mode will
     * mute the volume and vibrate. Normal mode will be audible and may vibrate
     * according to user settings.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * @param ringerMode The ringer mode, one of {@link #RINGER_MODE_NORMAL},
     *            {@link #RINGER_MODE_SILENT}, or {@link #RINGER_MODE_VIBRATE}.
     * @see #getRingerMode()
     * @see #isVolumeFixed()
     */
    public void setRingerMode(int ringerMode) {
        if (!isValidRingerMode(ringerMode)) {
            return;
        }
        IAudioService service = getService();
        try {
            service.setRingerMode(ringerMode);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setRingerMode", e);
        }
    }

    /**
     * Sets the volume index for a particular stream.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * @param streamType The stream whose volume index should be set.
     * @param index The volume index to set. See
     *            {@link #getStreamMaxVolume(int)} for the largest valid value.
     * @param flags One or more flags.
     * @see #getStreamMaxVolume(int)
     * @see #getStreamVolume(int)
     * @see #isVolumeFixed()
     */
    public void setStreamVolume(int streamType, int index, int flags) {
        IAudioService service = getService();
        try {
            if (mUseMasterVolume) {
                service.setMasterVolume(index, flags, mContext.getOpPackageName());
            } else {
                service.setStreamVolume(streamType, index, flags, mContext.getOpPackageName());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setStreamVolume", e);
        }
    }

    /**
     * Returns the maximum volume index for master volume.
     *
     * @hide
     */
    public int getMasterMaxVolume() {
        IAudioService service = getService();
        try {
            return service.getMasterMaxVolume();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getMasterMaxVolume", e);
            return 0;
        }
    }

    /**
     * Returns the current volume index for master volume.
     *
     * @return The current volume index for master volume.
     * @hide
     */
    public int getMasterVolume() {
        IAudioService service = getService();
        try {
            return service.getMasterVolume();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getMasterVolume", e);
            return 0;
        }
    }

    /**
     * Get last audible volume before master volume was muted.
     *
     * @hide
     */
    public int getLastAudibleMasterVolume() {
        IAudioService service = getService();
        try {
            return service.getLastAudibleMasterVolume();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in getLastAudibleMasterVolume", e);
            return 0;
        }
    }

    /**
     * Sets the volume index for master volume.
     *
     * @param index The volume index to set. See
     *            {@link #getMasterMaxVolume()} for the largest valid value.
     * @param flags One or more flags.
     * @see #getMasterMaxVolume()
     * @see #getMasterVolume()
     * @hide
     */
    public void setMasterVolume(int index, int flags) {
        IAudioService service = getService();
        try {
            service.setMasterVolume(index, flags, mContext.getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setMasterVolume", e);
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
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     *
     * @param streamType The stream to be soloed/unsoloed.
     * @param state The required solo state: true for solo ON, false for solo OFF
     *
     * @see #isVolumeFixed()
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
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     *
     * @param streamType The stream to be muted/unmuted.
     * @param state The required mute state: true for mute ON, false for mute OFF
     *
     * @see #isVolumeFixed()
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
     * get stream mute state.
     *
     * @hide
     */
    public boolean isStreamMute(int streamType) {
        IAudioService service = getService();
        try {
            return service.isStreamMute(streamType);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in isStreamMute", e);
            return false;
        }
    }

    /**
     * set master mute state.
     *
     * @hide
     */
    public void setMasterMute(boolean state) {
        setMasterMute(state, FLAG_SHOW_UI);
    }

    /**
     * set master mute state with optional flags.
     *
     * @hide
     */
    public void setMasterMute(boolean state, int flags) {
        IAudioService service = getService();
        try {
            service.setMasterMute(state, flags, mContext.getOpPackageName(), mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setMasterMute", e);
        }
    }

    /**
     * get master mute state.
     *
     * @hide
     */
    public boolean isMasterMute() {
        IAudioService service = getService();
        try {
            return service.isMasterMute();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in isMasterMute", e);
            return false;
        }
    }

    /**
     * forces the stream controlled by hard volume keys
     * specifying streamType == -1 releases control to the
     * logic.
     *
     * @hide
     */
    public void forceVolumeControlStream(int streamType) {
        IAudioService service = getService();
        try {
            service.forceVolumeControlStream(streamType, mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in forceVolumeControlStream", e);
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
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
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
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
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
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
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
     * @deprecated Applications should maintain their own vibrate policy based on
     * current ringer mode that can be queried via {@link #getRingerMode()}.
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
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     *
     * @param on set <var>true</var> to turn on speakerphone;
     *           <var>false</var> to turn it off
     */
    public void setSpeakerphoneOn(boolean on){
        IAudioService service = getService();
        try {
            service.setSpeakerphoneOn(on);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setSpeakerphoneOn", e);
        }
    }

    /**
     * Checks whether the speakerphone is on or off.
     *
     * @return true if speakerphone is on, false if it's off
     */
    public boolean isSpeakerphoneOn() {
        IAudioService service = getService();
        try {
            return service.isSpeakerphoneOn();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in isSpeakerphoneOn", e);
            return false;
        }
     }

    //====================================================================
    // Bluetooth SCO control
    /**
     * Sticky broadcast intent action indicating that the bluetoooth SCO audio
     * connection state has changed. The intent contains on extra {@link #EXTRA_SCO_AUDIO_STATE}
     * indicating the new state which is either {@link #SCO_AUDIO_STATE_DISCONNECTED}
     * or {@link #SCO_AUDIO_STATE_CONNECTED}
     *
     * @see #startBluetoothSco()
     * @deprecated Use  {@link #ACTION_SCO_AUDIO_STATE_UPDATED} instead
     */
    @Deprecated
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCO_AUDIO_STATE_CHANGED =
            "android.media.SCO_AUDIO_STATE_CHANGED";

     /**
     * Sticky broadcast intent action indicating that the bluetoooth SCO audio
     * connection state has been updated.
     * <p>This intent has two extras:
     * <ul>
     *   <li> {@link #EXTRA_SCO_AUDIO_STATE} - The new SCO audio state. </li>
     *   <li> {@link #EXTRA_SCO_AUDIO_PREVIOUS_STATE}- The previous SCO audio state. </li>
     * </ul>
     * <p> EXTRA_SCO_AUDIO_STATE or EXTRA_SCO_AUDIO_PREVIOUS_STATE can be any of:
     * <ul>
     *   <li> {@link #SCO_AUDIO_STATE_DISCONNECTED}, </li>
     *   <li> {@link #SCO_AUDIO_STATE_CONNECTING} or </li>
     *   <li> {@link #SCO_AUDIO_STATE_CONNECTED}, </li>
     * </ul>
     * @see #startBluetoothSco()
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SCO_AUDIO_STATE_UPDATED =
            "android.media.ACTION_SCO_AUDIO_STATE_UPDATED";

    /**
     * Extra for intent {@link #ACTION_SCO_AUDIO_STATE_CHANGED} or
     * {@link #ACTION_SCO_AUDIO_STATE_UPDATED} containing the new bluetooth SCO connection state.
     */
    public static final String EXTRA_SCO_AUDIO_STATE =
            "android.media.extra.SCO_AUDIO_STATE";

    /**
     * Extra for intent {@link #ACTION_SCO_AUDIO_STATE_UPDATED} containing the previous
     * bluetooth SCO connection state.
     */
    public static final String EXTRA_SCO_AUDIO_PREVIOUS_STATE =
            "android.media.extra.SCO_AUDIO_PREVIOUS_STATE";

    /**
     * Value for extra EXTRA_SCO_AUDIO_STATE or EXTRA_SCO_AUDIO_PREVIOUS_STATE
     * indicating that the SCO audio channel is not established
     */
    public static final int SCO_AUDIO_STATE_DISCONNECTED = 0;
    /**
     * Value for extra {@link #EXTRA_SCO_AUDIO_STATE} or {@link #EXTRA_SCO_AUDIO_PREVIOUS_STATE}
     * indicating that the SCO audio channel is established
     */
    public static final int SCO_AUDIO_STATE_CONNECTED = 1;
    /**
     * Value for extra EXTRA_SCO_AUDIO_STATE or EXTRA_SCO_AUDIO_PREVIOUS_STATE
     * indicating that the SCO audio channel is being established
     */
    public static final int SCO_AUDIO_STATE_CONNECTING = 2;
    /**
     * Value for extra EXTRA_SCO_AUDIO_STATE indicating that
     * there was an error trying to obtain the state
     */
    public static final int SCO_AUDIO_STATE_ERROR = -1;


    /**
     * Indicates if current platform supports use of SCO for off call use cases.
     * Application wanted to use bluetooth SCO audio when the phone is not in call
     * must first call this method to make sure that the platform supports this
     * feature.
     * @return true if bluetooth SCO can be used for audio when not in call
     *         false otherwise
     * @see #startBluetoothSco()
    */
    public boolean isBluetoothScoAvailableOffCall() {
        return mContext.getResources().getBoolean(
               com.android.internal.R.bool.config_bluetooth_sco_off_call);
    }

    /**
     * Start bluetooth SCO audio connection.
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS}.
     * <p>This method can be used by applications wanting to send and received audio
     * to/from a bluetooth SCO headset while the phone is not in call.
     * <p>As the SCO connection establishment can take several seconds,
     * applications should not rely on the connection to be available when the method
     * returns but instead register to receive the intent {@link #ACTION_SCO_AUDIO_STATE_UPDATED}
     * and wait for the state to be {@link #SCO_AUDIO_STATE_CONNECTED}.
     * <p>As the ACTION_SCO_AUDIO_STATE_UPDATED intent is sticky, the application can check the SCO
     * audio state before calling startBluetoothSco() by reading the intent returned by the receiver
     * registration. If the state is already CONNECTED, no state change will be received via the
     * intent after calling startBluetoothSco(). It is however useful to call startBluetoothSco()
     * so that the connection stays active in case the current initiator stops the connection.
     * <p>Unless the connection is already active as described above, the state will always
     * transition from DISCONNECTED to CONNECTING and then either to CONNECTED if the connection
     * succeeds or back to DISCONNECTED if the connection fails (e.g no headset is connected).
     * <p>When finished with the SCO connection or if the establishment fails, the application must
     * call {@link #stopBluetoothSco()} to clear the request and turn down the bluetooth connection.
     * <p>Even if a SCO connection is established, the following restrictions apply on audio
     * output streams so that they can be routed to SCO headset:
     * <ul>
     *   <li> the stream type must be {@link #STREAM_VOICE_CALL} </li>
     *   <li> the format must be mono </li>
     *   <li> the sampling must be 16kHz or 8kHz </li>
     * </ul>
     * <p>The following restrictions apply on input streams:
     * <ul>
     *   <li> the format must be mono </li>
     *   <li> the sampling must be 8kHz </li>
     * </ul>
     * <p>Note that the phone application always has the priority on the usage of the SCO
     * connection for telephony. If this method is called while the phone is in call
     * it will be ignored. Similarly, if a call is received or sent while an application
     * is using the SCO connection, the connection will be lost for the application and NOT
     * returned automatically when the call ends.
     * <p>NOTE: up to and including API version
     * {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}, this method initiates a virtual
     * voice call to the bluetooth headset.
     * After API version {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2} only a raw SCO audio
     * connection is established.
     * @see #stopBluetoothSco()
     * @see #ACTION_SCO_AUDIO_STATE_UPDATED
     */
    public void startBluetoothSco(){
        IAudioService service = getService();
        try {
            service.startBluetoothSco(mICallBack, mContext.getApplicationInfo().targetSdkVersion);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in startBluetoothSco", e);
        }
    }

    /**
     * @hide
     * Start bluetooth SCO audio connection in virtual call mode.
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS}.
     * <p>Similar to {@link #startBluetoothSco()} with explicit selection of virtual call mode.
     * Telephony and communication applications (VoIP, Video Chat) should preferably select
     * virtual call mode.
     * Applications using voice input for search or commands should first try raw audio connection
     * with {@link #startBluetoothSco()} and fall back to startBluetoothScoVirtualCall() in case of
     * failure.
     * @see #startBluetoothSco()
     * @see #stopBluetoothSco()
     * @see #ACTION_SCO_AUDIO_STATE_UPDATED
     */
    public void startBluetoothScoVirtualCall() {
        IAudioService service = getService();
        try {
            service.startBluetoothScoVirtualCall(mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in startBluetoothScoVirtualCall", e);
        }
    }

    /**
     * Stop bluetooth SCO audio connection.
     * <p>Requires Permission:
     *   {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS}.
     * <p>This method must be called by applications having requested the use of
     * bluetooth SCO audio with {@link #startBluetoothSco()} when finished with the SCO
     * connection or if connection fails.
     * @see #startBluetoothSco()
     */
    // Also used for connections started with {@link #startBluetoothScoVirtualCall()}
    public void stopBluetoothSco(){
        IAudioService service = getService();
        try {
            service.stopBluetoothSco(mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in stopBluetoothSco", e);
        }
    }

    /**
     * Request use of Bluetooth SCO headset for communications.
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     *
     * @param on set <var>true</var> to use bluetooth SCO for communications;
     *               <var>false</var> to not use bluetooth SCO for communications
     */
    public void setBluetoothScoOn(boolean on){
        IAudioService service = getService();
        try {
            service.setBluetoothScoOn(on);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setBluetoothScoOn", e);
        }
    }

    /**
     * Checks whether communications use Bluetooth SCO.
     *
     * @return true if SCO is used for communications;
     *         false if otherwise
     */
    public boolean isBluetoothScoOn() {
        IAudioService service = getService();
        try {
            return service.isBluetoothScoOn();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in isBluetoothScoOn", e);
            return false;
        }
    }

    /**
     * @param on set <var>true</var> to route A2DP audio to/from Bluetooth
     *           headset; <var>false</var> disable A2DP audio
     * @deprecated Do not use.
     */
    @Deprecated public void setBluetoothA2dpOn(boolean on){
    }

    /**
     * Checks whether A2DP audio routing to the Bluetooth headset is on or off.
     *
     * @return true if A2DP audio is being routed to/from Bluetooth headset;
     *         false if otherwise
     */
    public boolean isBluetoothA2dpOn() {
        if (AudioSystem.getDeviceConnectionState(DEVICE_OUT_BLUETOOTH_A2DP,"")
            == AudioSystem.DEVICE_STATE_UNAVAILABLE) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Sets audio routing to the wired headset on or off.
     *
     * @param on set <var>true</var> to route audio to/from wired
     *           headset; <var>false</var> disable wired headset audio
     * @deprecated Do not use.
     */
    @Deprecated public void setWiredHeadsetOn(boolean on){
    }

    /**
     * Checks whether a wired headset is connected or not.
     * <p>This is not a valid indication that audio playback is
     * actually over the wired headset as audio routing depends on other conditions.
     *
     * @return true if a wired headset is connected.
     *         false if otherwise
     * @deprecated Use only to check is a headset is connected or not.
     */
    public boolean isWiredHeadsetOn() {
        if (AudioSystem.getDeviceConnectionState(DEVICE_OUT_WIRED_HEADSET,"")
                == AudioSystem.DEVICE_STATE_UNAVAILABLE &&
            AudioSystem.getDeviceConnectionState(DEVICE_OUT_WIRED_HEADPHONE,"")
                == AudioSystem.DEVICE_STATE_UNAVAILABLE) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Sets the microphone mute on or off.
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     *
     * @param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     */
    public void setMicrophoneMute(boolean on){
        IAudioService service = getService();
        try {
            service.setMicrophoneMute(on, mContext.getOpPackageName());
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
        return AudioSystem.isMicrophoneMuted();
    }

    /**
     * Sets the audio mode.
     * <p>
     * The audio mode encompasses audio routing AND the behavior of
     * the telephony layer. Therefore this method should only be used by applications that
     * replace the platform-wide management of audio settings or the main telephony application.
     * In particular, the {@link #MODE_IN_CALL} mode should only be used by the telephony
     * application when it places a phone call, as it will cause signals from the radio layer
     * to feed the platform mixer.
     *
     * @param mode  the requested audio mode ({@link #MODE_NORMAL}, {@link #MODE_RINGTONE},
     *              {@link #MODE_IN_CALL} or {@link #MODE_IN_COMMUNICATION}).
     *              Informs the HAL about the current audio state so that
     *              it can route the audio appropriately.
     */
    public void setMode(int mode) {
        IAudioService service = getService();
        try {
            service.setMode(mode, mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setMode", e);
        }
    }

    /**
     * Returns the current audio mode.
     *
     * @return      the current audio mode ({@link #MODE_NORMAL}, {@link #MODE_RINGTONE},
     *              {@link #MODE_IN_CALL} or {@link #MODE_IN_COMMUNICATION}).
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
     * In call audio mode. A telephony call is established.
     */
    public static final int MODE_IN_CALL            = AudioSystem.MODE_IN_CALL;
    /**
     * In communication audio mode. An audio/video chat or VoIP call is established.
     */
    public static final int MODE_IN_COMMUNICATION   = AudioSystem.MODE_IN_COMMUNICATION;

    /* Routing bits for setRouting/getRouting API */
    /**
     * Routing audio output to earpiece
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated public static final int ROUTE_EARPIECE          = AudioSystem.ROUTE_EARPIECE;
    /**
     * Routing audio output to speaker
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated public static final int ROUTE_SPEAKER           = AudioSystem.ROUTE_SPEAKER;
    /**
     * @deprecated use {@link #ROUTE_BLUETOOTH_SCO}
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated public static final int ROUTE_BLUETOOTH = AudioSystem.ROUTE_BLUETOOTH_SCO;
    /**
     * Routing audio output to bluetooth SCO
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated public static final int ROUTE_BLUETOOTH_SCO     = AudioSystem.ROUTE_BLUETOOTH_SCO;
    /**
     * Routing audio output to headset
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated public static final int ROUTE_HEADSET           = AudioSystem.ROUTE_HEADSET;
    /**
     * Routing audio output to bluetooth A2DP
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated public static final int ROUTE_BLUETOOTH_A2DP    = AudioSystem.ROUTE_BLUETOOTH_A2DP;
    /**
     * Used for mask parameter of {@link #setRouting(int,int,int)}.
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated public static final int ROUTE_ALL               = AudioSystem.ROUTE_ALL;

    /**
     * Sets the audio routing for a specified mode
     *
     * @param mode   audio mode to change route. E.g., MODE_RINGTONE.
     * @param routes bit vector of routes requested, created from one or
     *               more of ROUTE_xxx types. Set bits indicate that route should be on
     * @param mask   bit vector of routes to change, created from one or more of
     * ROUTE_xxx types. Unset bits indicate the route should be left unchanged
     *
     * @deprecated   Do not set audio routing directly, use setSpeakerphoneOn(),
     * setBluetoothScoOn() methods instead.
     */
    @Deprecated
    public void setRouting(int mode, int routes, int mask) {
    }

    /**
     * Returns the current audio routing bit vector for a specified mode.
     *
     * @param mode audio mode to get route (e.g., MODE_RINGTONE)
     * @return an audio route bit vector that can be compared with ROUTE_xxx
     * bits
     * @deprecated   Do not query audio routing directly, use isSpeakerphoneOn(),
     * isBluetoothScoOn(), isBluetoothA2dpOn() and isWiredHeadsetOn() methods instead.
     */
    @Deprecated
    public int getRouting(int mode) {
        return -1;
    }

    /**
     * Checks whether any music is active.
     *
     * @return true if any music tracks are active.
     */
    public boolean isMusicActive() {
        return AudioSystem.isStreamActive(STREAM_MUSIC, 0);
    }

    /**
     * @hide
     * Checks whether any music or media is actively playing on a remote device (e.g. wireless
     *   display). Note that BT audio sinks are not considered remote devices.
     * @return true if {@link AudioManager#STREAM_MUSIC} is active on a remote device
     */
    public boolean isMusicActiveRemotely() {
        return AudioSystem.isStreamActiveRemotely(STREAM_MUSIC, 0);
    }

    /**
     * @hide
     * Checks whether the current audio focus is exclusive.
     * @return true if the top of the audio focus stack requested focus
     *     with {@link #AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE}
     */
    public boolean isAudioFocusExclusive() {
        IAudioService service = getService();
        try {
            return service.getCurrentAudioFocus() == AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE;
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in isAudioFocusExclusive()", e);
            return false;
        }
    }

    /**
     * Return a new audio session identifier not associated with any player or effect.
     * An audio session identifier is a system wide unique identifier for a set of audio streams
     * (one or more mixed together).
     * <p>The primary use of the audio session ID is to associate audio effects to audio players,
     * such as {@link MediaPlayer} or {@link AudioTrack}: all audio effects sharing the same audio
     * session ID will be applied to the mixed audio content of the players that share the same
     * audio session.
     * <p>This method can for instance be used when creating one of the
     * {@link android.media.audiofx.AudioEffect} objects to define the audio session of the effect,
     * or to specify a session for a speech synthesis utterance
     * in {@link android.speech.tts.TextToSpeech.Engine}.
     * @return a new unclaimed and unused audio session identifier, or {@link #ERROR} when the
     *   system failed to generate a new session, a condition in which audio playback or recording
     *   will subsequently fail as well.
     */
    public int generateAudioSessionId() {
        int session = AudioSystem.newAudioSessionId();
        if (session > 0) {
            return session;
        } else {
            Log.e(TAG, "Failure to generate a new audio session ID");
            return ERROR;
        }
    }

    /**
     * A special audio session ID to indicate that the audio session ID isn't known and the
     * framework should generate a new value. This can be used when building a new
     * {@link AudioTrack} instance with
     * {@link AudioTrack#AudioTrack(AudioAttributes, AudioFormat, int, int, int)}.
     */
    public static final int AUDIO_SESSION_ID_GENERATE = AudioSystem.AUDIO_SESSION_ALLOCATE;


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
     * @deprecated Use {@link #setParameters(String)} instead
     */
    @Deprecated public void setParameter(String key, String value) {
        setParameters(key+"="+value);
    }

    /**
     * Sets a variable number of parameter values to audio hardware.
     *
     * @param keyValuePairs list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     *
     */
    public void setParameters(String keyValuePairs) {
        AudioSystem.setParameters(keyValuePairs);
    }

    /**
     * Gets a variable number of parameter values from audio hardware.
     *
     * @param keys list of parameters
     * @return list of parameters key value pairs in the form:
     *    key1=value1;key2=value2;...
     */
    public String getParameters(String keys) {
        return AudioSystem.getParameters(keys);
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
     * Invalid keypress sound
     * @see #playSoundEffect(int)
     */
    public static final int FX_KEYPRESS_INVALID = 9;
    /**
     * @hide Number of sound effects
     */
    public static final int NUM_SOUND_EFFECTS = 10;

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
     *            {@link #FX_KEYPRESS_INVALID},
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
     *            {@link #FX_KEYPRESS_INVALID},
     * @param volume Sound effect volume.
     * The volume value is a raw scalar so UI controls should be scaled logarithmically.
     * If a volume of -1 is specified, the AudioManager.STREAM_MUSIC stream volume minus 3dB will be used.
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
     * @hide
     * Used to indicate no audio focus has been gained or lost.
     */
    public static final int AUDIOFOCUS_NONE = 0;

    /**
     * Used to indicate a gain of audio focus, or a request of audio focus, of unknown duration.
     * @see OnAudioFocusChangeListener#onAudioFocusChange(int)
     * @see #requestAudioFocus(OnAudioFocusChangeListener, int, int)
     */
    public static final int AUDIOFOCUS_GAIN = 1;
    /**
     * Used to indicate a temporary gain or request of audio focus, anticipated to last a short
     * amount of time. Examples of temporary changes are the playback of driving directions, or an
     * event notification.
     * @see OnAudioFocusChangeListener#onAudioFocusChange(int)
     * @see #requestAudioFocus(OnAudioFocusChangeListener, int, int)
     */
    public static final int AUDIOFOCUS_GAIN_TRANSIENT = 2;
    /**
     * Used to indicate a temporary request of audio focus, anticipated to last a short
     * amount of time, and where it is acceptable for other audio applications to keep playing
     * after having lowered their output level (also referred to as "ducking").
     * Examples of temporary changes are the playback of driving directions where playback of music
     * in the background is acceptable.
     * @see OnAudioFocusChangeListener#onAudioFocusChange(int)
     * @see #requestAudioFocus(OnAudioFocusChangeListener, int, int)
     */
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3;
    /**
     * Used to indicate a temporary request of audio focus, anticipated to last a short
     * amount of time, during which no other applications, or system components, should play
     * anything. Examples of exclusive and transient audio focus requests are voice
     * memo recording and speech recognition, during which the system shouldn't play any
     * notifications, and media playback should have paused.
     * @see #requestAudioFocus(OnAudioFocusChangeListener, int, int)
     */
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4;
    /**
     * Used to indicate a loss of audio focus of unknown duration.
     * @see OnAudioFocusChangeListener#onAudioFocusChange(int)
     */
    public static final int AUDIOFOCUS_LOSS = -1 * AUDIOFOCUS_GAIN;
    /**
     * Used to indicate a transient loss of audio focus.
     * @see OnAudioFocusChangeListener#onAudioFocusChange(int)
     */
    public static final int AUDIOFOCUS_LOSS_TRANSIENT = -1 * AUDIOFOCUS_GAIN_TRANSIENT;
    /**
     * Used to indicate a transient loss of audio focus where the loser of the audio focus can
     * lower its output volume if it wants to continue playing (also referred to as "ducking"), as
     * the new focus owner doesn't require others to be silent.
     * @see OnAudioFocusChangeListener#onAudioFocusChange(int)
     */
    public static final int AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK =
            -1 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;

    /**
     * Interface definition for a callback to be invoked when the audio focus of the system is
     * updated.
     */
    public interface OnAudioFocusChangeListener {
        /**
         * Called on the listener to notify it the audio focus for this listener has been changed.
         * The focusChange value indicates whether the focus was gained,
         * whether the focus was lost, and whether that loss is transient, or whether the new focus
         * holder will hold it for an unknown amount of time.
         * When losing focus, listeners can use the focus change information to decide what
         * behavior to adopt when losing focus. A music player could for instance elect to lower
         * the volume of its music stream (duck) for transient focus losses, and pause otherwise.
         * @param focusChange the type of focus change, one of {@link AudioManager#AUDIOFOCUS_GAIN},
         *   {@link AudioManager#AUDIOFOCUS_LOSS}, {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT}
         *   and {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}.
         */
        public void onAudioFocusChange(int focusChange);
    }

    /**
     * Map to convert focus event listener IDs, as used in the AudioService audio focus stack,
     * to actual listener objects.
     */
    private final HashMap<String, OnAudioFocusChangeListener> mAudioFocusIdListenerMap =
            new HashMap<String, OnAudioFocusChangeListener>();
    /**
     * Lock to prevent concurrent changes to the list of focus listeners for this AudioManager
     * instance.
     */
    private final Object mFocusListenerLock = new Object();

    private OnAudioFocusChangeListener findFocusListener(String id) {
        return mAudioFocusIdListenerMap.get(id);
    }

    /**
     * Handler for audio focus events coming from the audio service.
     */
    private final FocusEventHandlerDelegate mAudioFocusEventHandlerDelegate =
            new FocusEventHandlerDelegate();

    /**
     * Helper class to handle the forwarding of audio focus events to the appropriate listener
     */
    private class FocusEventHandlerDelegate {
        private final Handler mHandler;

        FocusEventHandlerDelegate() {
            Looper looper;
            if ((looper = Looper.myLooper()) == null) {
                looper = Looper.getMainLooper();
            }

            if (looper != null) {
                // implement the event handler delegate to receive audio focus events
                mHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        OnAudioFocusChangeListener listener = null;
                        synchronized(mFocusListenerLock) {
                            listener = findFocusListener((String)msg.obj);
                        }
                        if (listener != null) {
                            listener.onAudioFocusChange(msg.what);
                        }
                    }
                };
            } else {
                mHandler = null;
            }
        }

        Handler getHandler() {
            return mHandler;
        }
    }

    private final IAudioFocusDispatcher mAudioFocusDispatcher = new IAudioFocusDispatcher.Stub() {

        public void dispatchAudioFocusChange(int focusChange, String id) {
            Message m = mAudioFocusEventHandlerDelegate.getHandler().obtainMessage(focusChange, id);
            mAudioFocusEventHandlerDelegate.getHandler().sendMessage(m);
        }

    };

    private String getIdForAudioFocusListener(OnAudioFocusChangeListener l) {
        if (l == null) {
            return new String(this.toString());
        } else {
            return new String(this.toString() + l.toString());
        }
    }

    /**
     * @hide
     * Registers a listener to be called when audio focus changes. Calling this method is optional
     * before calling {@link #requestAudioFocus(OnAudioFocusChangeListener, int, int)}, as it
     * will register the listener as well if it wasn't registered already.
     * @param l the listener to be notified of audio focus changes.
     */
    public void registerAudioFocusListener(OnAudioFocusChangeListener l) {
        synchronized(mFocusListenerLock) {
            if (mAudioFocusIdListenerMap.containsKey(getIdForAudioFocusListener(l))) {
                return;
            }
            mAudioFocusIdListenerMap.put(getIdForAudioFocusListener(l), l);
        }
    }

    /**
     * @hide
     * Causes the specified listener to not be called anymore when focus is gained or lost.
     * @param l the listener to unregister.
     */
    public void unregisterAudioFocusListener(OnAudioFocusChangeListener l) {

        // remove locally
        synchronized(mFocusListenerLock) {
            mAudioFocusIdListenerMap.remove(getIdForAudioFocusListener(l));
        }
    }


    /**
     * A failed focus change request.
     */
    public static final int AUDIOFOCUS_REQUEST_FAILED = 0;
    /**
     * A successful focus change request.
     */
    public static final int AUDIOFOCUS_REQUEST_GRANTED = 1;


    /**
     *  Request audio focus.
     *  Send a request to obtain the audio focus
     *  @param l the listener to be notified of audio focus changes
     *  @param streamType the main audio stream type affected by the focus request
     *  @param durationHint use {@link #AUDIOFOCUS_GAIN_TRANSIENT} to indicate this focus request
     *      is temporary, and focus will be abandonned shortly. Examples of transient requests are
     *      for the playback of driving directions, or notifications sounds.
     *      Use {@link #AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK} to indicate also that it's ok for
     *      the previous focus owner to keep playing if it ducks its audio output.
     *      Alternatively use {@link #AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE} for a temporary request
     *      that benefits from the system not playing disruptive sounds like notifications, for
     *      usecases such as voice memo recording, or speech recognition.
     *      Use {@link #AUDIOFOCUS_GAIN} for a focus request of unknown duration such
     *      as the playback of a song or a video.
     *  @return {@link #AUDIOFOCUS_REQUEST_FAILED} or {@link #AUDIOFOCUS_REQUEST_GRANTED}
     */
    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) {
        int status = AUDIOFOCUS_REQUEST_FAILED;
        if ((durationHint < AUDIOFOCUS_GAIN) ||
                (durationHint > AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)) {
            Log.e(TAG, "Invalid duration hint, audio focus request denied");
            return status;
        }
        registerAudioFocusListener(l);
        //TODO protect request by permission check?
        IAudioService service = getService();
        try {
            status = service.requestAudioFocus(streamType, durationHint, mICallBack,
                    mAudioFocusDispatcher, getIdForAudioFocusListener(l),
                    mContext.getOpPackageName() /* package name */);
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call requestAudioFocus() on AudioService due to "+e);
        }
        return status;
    }

    /**
     * @hide
     * Used internally by telephony package to request audio focus. Will cause the focus request
     * to be associated with the "voice communication" identifier only used in AudioService
     * to identify this use case.
     * @param streamType use STREAM_RING for focus requests when ringing, VOICE_CALL for
     *    the establishment of the call
     * @param durationHint the type of focus request. AUDIOFOCUS_GAIN_TRANSIENT is recommended so
     *    media applications resume after a call
     */
    public void requestAudioFocusForCall(int streamType, int durationHint) {
        IAudioService service = getService();
        try {
            service.requestAudioFocus(streamType, durationHint, mICallBack, null,
                    MediaFocusControl.IN_VOICE_COMM_FOCUS_ID,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call requestAudioFocusForCall() on AudioService due to "+e);
        }
    }

    /**
     * @hide
     * Used internally by telephony package to abandon audio focus, typically after a call or
     * when ringing ends and the call is rejected or not answered.
     * Should match one or more calls to {@link #requestAudioFocusForCall(int, int)}.
     */
    public void abandonAudioFocusForCall() {
        IAudioService service = getService();
        try {
            service.abandonAudioFocus(null, MediaFocusControl.IN_VOICE_COMM_FOCUS_ID);
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call abandonAudioFocusForCall() on AudioService due to "+e);
        }
    }

    /**
     *  Abandon audio focus. Causes the previous focus owner, if any, to receive focus.
     *  @param l the listener with which focus was requested.
     *  @return {@link #AUDIOFOCUS_REQUEST_FAILED} or {@link #AUDIOFOCUS_REQUEST_GRANTED}
     */
    public int abandonAudioFocus(OnAudioFocusChangeListener l) {
        int status = AUDIOFOCUS_REQUEST_FAILED;
        unregisterAudioFocusListener(l);
        IAudioService service = getService();
        try {
            status = service.abandonAudioFocus(mAudioFocusDispatcher,
                    getIdForAudioFocusListener(l));
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call abandonAudioFocus() on AudioService due to "+e);
        }
        return status;
    }


    //====================================================================
    // Remote Control
    /**
     * Register a component to be the sole receiver of MEDIA_BUTTON intents.
     * @param eventReceiver identifier of a {@link android.content.BroadcastReceiver}
     *      that will receive the media button intent. This broadcast receiver must be declared
     *      in the application manifest. The package of the component must match that of
     *      the context you're registering from.
     * @deprecated Use {@link MediaSession#setMediaButtonReceiver(PendingIntent)} instead.
     */
    @Deprecated
    public void registerMediaButtonEventReceiver(ComponentName eventReceiver) {
        if (eventReceiver == null) {
            return;
        }
        if (!eventReceiver.getPackageName().equals(mContext.getPackageName())) {
            Log.e(TAG, "registerMediaButtonEventReceiver() error: " +
                    "receiver and context package names don't match");
            return;
        }
        // construct a PendingIntent for the media button and register it
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        //     the associated intent will be handled by the component being registered
        mediaButtonIntent.setComponent(eventReceiver);
        PendingIntent pi = PendingIntent.getBroadcast(mContext,
                0/*requestCode, ignored*/, mediaButtonIntent, 0/*flags*/);
        registerMediaButtonIntent(pi, eventReceiver);
    }

    /**
     * Register a component to be the sole receiver of MEDIA_BUTTON intents.  This is like
     * {@link #registerMediaButtonEventReceiver(android.content.ComponentName)}, but allows
     * the buttons to go to any PendingIntent.  Note that you should only use this form if
     * you know you will continue running for the full time until unregistering the
     * PendingIntent.
     * @param eventReceiver target that will receive media button intents.  The PendingIntent
     * will be sent an {@link Intent#ACTION_MEDIA_BUTTON} event when a media button action
     * occurs, with {@link Intent#EXTRA_KEY_EVENT} added and holding the key code of the
     * media button that was pressed.
     * @deprecated Use {@link MediaSession#setMediaButtonReceiver(PendingIntent)} instead.
     */
    @Deprecated
    public void registerMediaButtonEventReceiver(PendingIntent eventReceiver) {
        if (eventReceiver == null) {
            return;
        }
        registerMediaButtonIntent(eventReceiver, null);
    }

    /**
     * @hide
     * no-op if (pi == null) or (eventReceiver == null)
     */
    public void registerMediaButtonIntent(PendingIntent pi, ComponentName eventReceiver) {
        if (pi == null) {
            Log.e(TAG, "Cannot call registerMediaButtonIntent() with a null parameter");
            return;
        }
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        helper.addMediaButtonListener(pi, eventReceiver, mContext);
    }

    /**
     * Unregister the receiver of MEDIA_BUTTON intents.
     * @param eventReceiver identifier of a {@link android.content.BroadcastReceiver}
     *      that was registered with {@link #registerMediaButtonEventReceiver(ComponentName)}.
     * @deprecated Use {@link MediaSession} instead.
     */
    @Deprecated
    public void unregisterMediaButtonEventReceiver(ComponentName eventReceiver) {
        if (eventReceiver == null) {
            return;
        }
        // construct a PendingIntent for the media button and unregister it
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        //     the associated intent will be handled by the component being registered
        mediaButtonIntent.setComponent(eventReceiver);
        PendingIntent pi = PendingIntent.getBroadcast(mContext,
                0/*requestCode, ignored*/, mediaButtonIntent, 0/*flags*/);
        unregisterMediaButtonIntent(pi);
    }

    /**
     * Unregister the receiver of MEDIA_BUTTON intents.
     * @param eventReceiver same PendingIntent that was registed with
     *      {@link #registerMediaButtonEventReceiver(PendingIntent)}.
     * @deprecated Use {@link MediaSession} instead.
     */
    @Deprecated
    public void unregisterMediaButtonEventReceiver(PendingIntent eventReceiver) {
        if (eventReceiver == null) {
            return;
        }
        unregisterMediaButtonIntent(eventReceiver);
    }

    /**
     * @hide
     */
    public void unregisterMediaButtonIntent(PendingIntent pi) {
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        helper.removeMediaButtonListener(pi);
    }

    /**
     * Registers the remote control client for providing information to display on the remote
     * controls.
     * @param rcClient The remote control client from which remote controls will receive
     *      information to display.
     * @see RemoteControlClient
     * @deprecated Use {@link MediaSession} instead.
     */
    @Deprecated
    public void registerRemoteControlClient(RemoteControlClient rcClient) {
        if ((rcClient == null) || (rcClient.getRcMediaIntent() == null)) {
            return;
        }
        rcClient.registerWithSession(MediaSessionLegacyHelper.getHelper(mContext));
    }

    /**
     * Unregisters the remote control client that was providing information to display on the
     * remote controls.
     * @param rcClient The remote control client to unregister.
     * @see #registerRemoteControlClient(RemoteControlClient)
     * @deprecated Use {@link MediaSession} instead.
     */
    @Deprecated
    public void unregisterRemoteControlClient(RemoteControlClient rcClient) {
        if ((rcClient == null) || (rcClient.getRcMediaIntent() == null)) {
            return;
        }
        rcClient.unregisterWithSession(MediaSessionLegacyHelper.getHelper(mContext));
    }

    /**
     * Registers a {@link RemoteController} instance for it to receive media
     * metadata updates and playback state information from applications using
     * {@link RemoteControlClient}, and control their playback.
     * <p>
     * Registration requires the {@link OnClientUpdateListener} listener to be
     * one of the enabled notification listeners (see
     * {@link android.service.notification.NotificationListenerService}).
     *
     * @param rctlr the object to register.
     * @return true if the {@link RemoteController} was successfully registered,
     *         false if an error occurred, due to an internal system error, or
     *         insufficient permissions.
     * @deprecated Use
     *             {@link MediaSessionManager#addOnActiveSessionsChangedListener(android.media.session.MediaSessionManager.OnActiveSessionsChangedListener, ComponentName)}
     *             and {@link MediaController} instead.
     */
    @Deprecated
    public boolean registerRemoteController(RemoteController rctlr) {
        if (rctlr == null) {
            return false;
        }
        rctlr.startListeningToSessions();
        return true;
    }

    /**
     * Unregisters a {@link RemoteController}, causing it to no longer receive
     * media metadata and playback state information, and no longer be capable
     * of controlling playback.
     *
     * @param rctlr the object to unregister.
     * @deprecated Use
     *             {@link MediaSessionManager#removeOnActiveSessionsChangedListener(android.media.session.MediaSessionManager.OnActiveSessionsChangedListener)}
     *             instead.
     */
    @Deprecated
    public void unregisterRemoteController(RemoteController rctlr) {
        if (rctlr == null) {
            return;
        }
        rctlr.stopListeningToSessions();
    }

    /**
     * @hide
     * Registers a remote control display that will be sent information by remote control clients.
     * Use this method if your IRemoteControlDisplay is not going to display artwork, otherwise
     * use {@link #registerRemoteControlDisplay(IRemoteControlDisplay, int, int)} to pass the
     * artwork size directly, or
     * {@link #remoteControlDisplayUsesBitmapSize(IRemoteControlDisplay, int, int)} later if artwork
     * is not yet needed.
     * <p>Registration requires the {@link Manifest.permission#MEDIA_CONTENT_CONTROL} permission.
     * @param rcd the IRemoteControlDisplay
     */
    public void registerRemoteControlDisplay(IRemoteControlDisplay rcd) {
        // passing a negative value for art work width and height as they are unknown at this stage
        registerRemoteControlDisplay(rcd, /*w*/-1, /*h*/ -1);
    }

    /**
     * @hide
     * Registers a remote control display that will be sent information by remote control clients.
     * <p>Registration requires the {@link Manifest.permission#MEDIA_CONTENT_CONTROL} permission.
     * @param rcd
     * @param w the maximum width of the expected bitmap. Negative values indicate it is
     *   useless to send artwork.
     * @param h the maximum height of the expected bitmap. Negative values indicate it is
     *   useless to send artwork.
     */
    public void registerRemoteControlDisplay(IRemoteControlDisplay rcd, int w, int h) {
        if (rcd == null) {
            return;
        }
        IAudioService service = getService();
        try {
            service.registerRemoteControlDisplay(rcd, w, h);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in registerRemoteControlDisplay " + e);
        }
    }

    /**
     * @hide
     * Unregisters a remote control display that was sent information by remote control clients.
     * @param rcd
     */
    public void unregisterRemoteControlDisplay(IRemoteControlDisplay rcd) {
        if (rcd == null) {
            return;
        }
        IAudioService service = getService();
        try {
            service.unregisterRemoteControlDisplay(rcd);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in unregisterRemoteControlDisplay " + e);
        }
    }

    /**
     * @hide
     * Sets the artwork size a remote control display expects when receiving bitmaps.
     * @param rcd
     * @param w the maximum width of the expected bitmap. Negative values indicate it is
     *   useless to send artwork.
     * @param h the maximum height of the expected bitmap. Negative values indicate it is
     *   useless to send artwork.
     */
    public void remoteControlDisplayUsesBitmapSize(IRemoteControlDisplay rcd, int w, int h) {
        if (rcd == null) {
            return;
        }
        IAudioService service = getService();
        try {
            service.remoteControlDisplayUsesBitmapSize(rcd, w, h);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in remoteControlDisplayUsesBitmapSize " + e);
        }
    }

    /**
     * @hide
     * Controls whether a remote control display needs periodic checks of the RemoteControlClient
     * playback position to verify that the estimated position has not drifted from the actual
     * position. By default the check is not performed.
     * The IRemoteControlDisplay must have been previously registered for this to have any effect.
     * @param rcd the IRemoteControlDisplay for which the anti-drift mechanism will be enabled
     *     or disabled. No effect is null.
     * @param wantsSync if true, RemoteControlClient instances which expose their playback position
     *     to the framework will regularly compare the estimated playback position with the actual
     *     position, and will update the IRemoteControlDisplay implementation whenever a drift is
     *     detected.
     */
    public void remoteControlDisplayWantsPlaybackPositionSync(IRemoteControlDisplay rcd,
            boolean wantsSync) {
        if (rcd == null) {
            return;
        }
        IAudioService service = getService();
        try {
            service.remoteControlDisplayWantsPlaybackPositionSync(rcd, wantsSync);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in remoteControlDisplayWantsPlaybackPositionSync " + e);
        }
    }

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Register the given {@link AudioPolicy}.
     * This call is synchronous and blocks until the registration process successfully completed
     * or failed to complete.
     * @param policy the {@link AudioPolicy} to register.
     * @return {@link #ERROR} if there was an error communicating with the registration service
     *    or if the user doesn't have the required
     *    {@link android.Manifest.permission#MODIFY_AUDIO_ROUTING} permission,
     *    {@link #SUCCESS} otherwise.
     */
    public int registerAudioPolicy(AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        IAudioService service = getService();
        try {
            if (!service.registerAudioPolicy(policy.getConfig(), policy.token())) {
                return ERROR;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in registerAudioPolicyAsync()", e);
            return ERROR;
        }
        return SUCCESS;
    }

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * @param policy the {@link AudioPolicy} to unregister.
     */
    public void unregisterAudioPolicyAsync(AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        IAudioService service = getService();
        try {
            service.unregisterAudioPolicyAsync(policy.token());
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in unregisterAudioPolicyAsync()", e);
        }
    }


    /**
     *  @hide
     *  Reload audio settings. This method is called by Settings backup
     *  agent when audio settings are restored and causes the AudioService
     *  to read and apply restored settings.
     */
    public void reloadAudioSettings() {
        IAudioService service = getService();
        try {
            service.reloadAudioSettings();
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in reloadAudioSettings"+e);
        }
    }

    /**
     * @hide
     * Notifies AudioService that it is connected to an A2DP device that supports absolute volume,
     * so that AudioService can send volume change events to the A2DP device, rather than handling
     * them.
     */
    public void avrcpSupportsAbsoluteVolume(String address, boolean support) {
        IAudioService service = getService();
        try {
            service.avrcpSupportsAbsoluteVolume(address, support);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in avrcpSupportsAbsoluteVolume", e);
        }
    }

     /**
      * {@hide}
      */
     private final IBinder mICallBack = new Binder();

    /**
     * Checks whether the phone is in silent mode, with or without vibrate.
     *
     * @return true if phone is in silent mode, with or without vibrate.
     *
     * @see #getRingerMode()
     *
     * @hide pending API Council approval
     */
    public boolean isSilentMode() {
        int ringerMode = getRingerMode();
        boolean silentMode =
            (ringerMode == RINGER_MODE_SILENT) ||
            (ringerMode == RINGER_MODE_VIBRATE);
        return silentMode;
    }

    // This section re-defines new output device constants from AudioSystem, because the AudioSystem
    // class is not used by other parts of the framework, which instead use definitions and methods
    // from AudioManager. AudioSystem is an internal class used by AudioManager and AudioService.

    /** @hide
     * The audio device code for representing "no device." */
    public static final int DEVICE_NONE = AudioSystem.DEVICE_NONE;
    /** @hide
     *  The audio output device code for the small speaker at the front of the device used
     *  when placing calls.  Does not refer to an in-ear headphone without attached microphone,
     *  such as earbuds, earphones, or in-ear monitors (IEM). Those would be handled as a
     *  {@link #DEVICE_OUT_WIRED_HEADPHONE}.
     */
    public static final int DEVICE_OUT_EARPIECE = AudioSystem.DEVICE_OUT_EARPIECE;
    /** @hide
     *  The audio output device code for the built-in speaker */
    public static final int DEVICE_OUT_SPEAKER = AudioSystem.DEVICE_OUT_SPEAKER;
    /** @hide
     * The audio output device code for a wired headset with attached microphone */
    public static final int DEVICE_OUT_WIRED_HEADSET = AudioSystem.DEVICE_OUT_WIRED_HEADSET;
    /** @hide
     * The audio output device code for a wired headphone without attached microphone */
    public static final int DEVICE_OUT_WIRED_HEADPHONE = AudioSystem.DEVICE_OUT_WIRED_HEADPHONE;
    /** @hide
     * The audio output device code for generic Bluetooth SCO, for voice */
    public static final int DEVICE_OUT_BLUETOOTH_SCO = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO;
    /** @hide
     * The audio output device code for Bluetooth SCO Headset Profile (HSP) and
     * Hands-Free Profile (HFP), for voice
     */
    public static final int DEVICE_OUT_BLUETOOTH_SCO_HEADSET =
            AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET;
    /** @hide
     * The audio output device code for Bluetooth SCO car audio, for voice */
    public static final int DEVICE_OUT_BLUETOOTH_SCO_CARKIT =
            AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT;
    /** @hide
     * The audio output device code for generic Bluetooth A2DP, for music */
    public static final int DEVICE_OUT_BLUETOOTH_A2DP = AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP;
    /** @hide
     * The audio output device code for Bluetooth A2DP headphones, for music */
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES =
            AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES;
    /** @hide
     * The audio output device code for Bluetooth A2DP external speaker, for music */
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER =
            AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER;
    /** @hide
     * The audio output device code for S/PDIF (legacy) or HDMI
     * Deprecated: replaced by {@link #DEVICE_OUT_HDMI} */
    public static final int DEVICE_OUT_AUX_DIGITAL = AudioSystem.DEVICE_OUT_AUX_DIGITAL;
    /** @hide
     * The audio output device code for HDMI */
    public static final int DEVICE_OUT_HDMI = AudioSystem.DEVICE_OUT_HDMI;
    /** @hide
     * The audio output device code for an analog wired headset attached via a
     *  docking station
     */
    public static final int DEVICE_OUT_ANLG_DOCK_HEADSET = AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET;
    /** @hide
     * The audio output device code for a digital wired headset attached via a
     *  docking station
     */
    public static final int DEVICE_OUT_DGTL_DOCK_HEADSET = AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET;
    /** @hide
     * The audio output device code for a USB audio accessory. The accessory is in USB host
     * mode and the Android device in USB device mode
     */
    public static final int DEVICE_OUT_USB_ACCESSORY = AudioSystem.DEVICE_OUT_USB_ACCESSORY;
    /** @hide
     * The audio output device code for a USB audio device. The device is in USB device
     * mode and the Android device in USB host mode
     */
    public static final int DEVICE_OUT_USB_DEVICE = AudioSystem.DEVICE_OUT_USB_DEVICE;
    /** @hide
     * The audio output device code for projection output.
     */
    public static final int DEVICE_OUT_REMOTE_SUBMIX = AudioSystem.DEVICE_OUT_REMOTE_SUBMIX;
    /** @hide
     * The audio output device code the telephony voice TX path.
     */
    public static final int DEVICE_OUT_TELEPHONY_TX = AudioSystem.DEVICE_OUT_TELEPHONY_TX;
    /** @hide
     * The audio output device code for an analog jack with line impedance detected.
     */
    public static final int DEVICE_OUT_LINE = AudioSystem.DEVICE_OUT_LINE;
    /** @hide
     * The audio output device code for HDMI Audio Return Channel.
     */
    public static final int DEVICE_OUT_HDMI_ARC = AudioSystem.DEVICE_OUT_HDMI_ARC;
    /** @hide
     * The audio output device code for S/PDIF digital connection.
     */
    public static final int DEVICE_OUT_SPDIF = AudioSystem.DEVICE_OUT_SPDIF;
    /** @hide
     * The audio output device code for built-in FM transmitter.
     */
    public static final int DEVICE_OUT_FM = AudioSystem.DEVICE_OUT_FM;
    /** @hide
     * This is not used as a returned value from {@link #getDevicesForStream}, but could be
     *  used in the future in a set method to select whatever default device is chosen by the
     *  platform-specific implementation.
     */
    public static final int DEVICE_OUT_DEFAULT = AudioSystem.DEVICE_OUT_DEFAULT;

    /** @hide
     * The audio input device code for default built-in microphone
     */
    public static final int DEVICE_IN_BUILTIN_MIC = AudioSystem.DEVICE_IN_BUILTIN_MIC;
    /** @hide
     * The audio input device code for a Bluetooth SCO headset
     */
    public static final int DEVICE_IN_BLUETOOTH_SCO_HEADSET =
                                    AudioSystem.DEVICE_IN_BLUETOOTH_SCO_HEADSET;
    /** @hide
     * The audio input device code for wired headset microphone
     */
    public static final int DEVICE_IN_WIRED_HEADSET =
                                    AudioSystem.DEVICE_IN_WIRED_HEADSET;
    /** @hide
     * The audio input device code for HDMI
     */
    public static final int DEVICE_IN_HDMI =
                                    AudioSystem.DEVICE_IN_HDMI;
    /** @hide
     * The audio input device code for telephony voice RX path
     */
    public static final int DEVICE_IN_TELEPHONY_RX =
                                    AudioSystem.DEVICE_IN_TELEPHONY_RX;
    /** @hide
     * The audio input device code for built-in microphone pointing to the back
     */
    public static final int DEVICE_IN_BACK_MIC =
                                    AudioSystem.DEVICE_IN_BACK_MIC;
    /** @hide
     * The audio input device code for analog from a docking station
     */
    public static final int DEVICE_IN_ANLG_DOCK_HEADSET =
                                    AudioSystem.DEVICE_IN_ANLG_DOCK_HEADSET;
    /** @hide
     * The audio input device code for digital from a docking station
     */
    public static final int DEVICE_IN_DGTL_DOCK_HEADSET =
                                    AudioSystem.DEVICE_IN_DGTL_DOCK_HEADSET;
    /** @hide
     * The audio input device code for a USB audio accessory. The accessory is in USB host
     * mode and the Android device in USB device mode
     */
    public static final int DEVICE_IN_USB_ACCESSORY =
                                    AudioSystem.DEVICE_IN_USB_ACCESSORY;
    /** @hide
     * The audio input device code for a USB audio device. The device is in USB device
     * mode and the Android device in USB host mode
     */
    public static final int DEVICE_IN_USB_DEVICE =
                                    AudioSystem.DEVICE_IN_USB_DEVICE;
    /** @hide
     * The audio input device code for a FM radio tuner
     */
    public static final int DEVICE_IN_FM_TUNER = AudioSystem.DEVICE_IN_FM_TUNER;
    /** @hide
     * The audio input device code for a TV tuner
     */
    public static final int DEVICE_IN_TV_TUNER = AudioSystem.DEVICE_IN_TV_TUNER;
    /** @hide
     * The audio input device code for an analog jack with line impedance detected
     */
    public static final int DEVICE_IN_LINE = AudioSystem.DEVICE_IN_LINE;
    /** @hide
     * The audio input device code for a S/PDIF digital connection
     */
    public static final int DEVICE_IN_SPDIF = AudioSystem.DEVICE_IN_SPDIF;
    /** @hide
     * The audio input device code for audio loopback
     */
    public static final int DEVICE_IN_LOOPBACK = AudioSystem.DEVICE_IN_LOOPBACK;

    /**
     * Return true if the device code corresponds to an output device.
     * @hide
     */
    public static boolean isOutputDevice(int device)
    {
        return (device & AudioSystem.DEVICE_BIT_IN) == 0;
    }

    /**
     * Return true if the device code corresponds to an input device.
     * @hide
     */
    public static boolean isInputDevice(int device)
    {
        return (device & AudioSystem.DEVICE_BIT_IN) == AudioSystem.DEVICE_BIT_IN;
    }


    /**
     * Return the enabled devices for the specified output stream type.
     *
     * @param streamType The stream type to query. One of
     *            {@link #STREAM_VOICE_CALL},
     *            {@link #STREAM_SYSTEM},
     *            {@link #STREAM_RING},
     *            {@link #STREAM_MUSIC},
     *            {@link #STREAM_ALARM},
     *            {@link #STREAM_NOTIFICATION},
     *            {@link #STREAM_DTMF}.
     *
     * @return The bit-mask "or" of audio output device codes for all enabled devices on this
     *         stream. Zero or more of
     *            {@link #DEVICE_OUT_EARPIECE},
     *            {@link #DEVICE_OUT_SPEAKER},
     *            {@link #DEVICE_OUT_WIRED_HEADSET},
     *            {@link #DEVICE_OUT_WIRED_HEADPHONE},
     *            {@link #DEVICE_OUT_BLUETOOTH_SCO},
     *            {@link #DEVICE_OUT_BLUETOOTH_SCO_HEADSET},
     *            {@link #DEVICE_OUT_BLUETOOTH_SCO_CARKIT},
     *            {@link #DEVICE_OUT_BLUETOOTH_A2DP},
     *            {@link #DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES},
     *            {@link #DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER},
     *            {@link #DEVICE_OUT_HDMI},
     *            {@link #DEVICE_OUT_ANLG_DOCK_HEADSET},
     *            {@link #DEVICE_OUT_DGTL_DOCK_HEADSET}.
     *            {@link #DEVICE_OUT_USB_ACCESSORY}.
     *            {@link #DEVICE_OUT_USB_DEVICE}.
     *            {@link #DEVICE_OUT_REMOTE_SUBMIX}.
     *            {@link #DEVICE_OUT_TELEPHONY_TX}.
     *            {@link #DEVICE_OUT_LINE}.
     *            {@link #DEVICE_OUT_HDMI_ARC}.
     *            {@link #DEVICE_OUT_SPDIF}.
     *            {@link #DEVICE_OUT_FM}.
     *            {@link #DEVICE_OUT_DEFAULT} is not used here.
     *
     * The implementation may support additional device codes beyond those listed, so
     * the application should ignore any bits which it does not recognize.
     * Note that the information may be imprecise when the implementation
     * cannot distinguish whether a particular device is enabled.
     *
     * {@hide}
     */
    public int getDevicesForStream(int streamType) {
        switch (streamType) {
        case STREAM_VOICE_CALL:
        case STREAM_SYSTEM:
        case STREAM_RING:
        case STREAM_MUSIC:
        case STREAM_ALARM:
        case STREAM_NOTIFICATION:
        case STREAM_DTMF:
            return AudioSystem.getDevicesForStream(streamType);
        default:
            return 0;
        }
    }

     /**
     * Indicate wired accessory connection state change.
     * @param device type of device connected/disconnected (AudioManager.DEVICE_OUT_xxx)
     * @param state  new connection state: 1 connected, 0 disconnected
     * @param name   device name
     * {@hide}
     */
    public void setWiredDeviceConnectionState(int device, int state, String name) {
        IAudioService service = getService();
        try {
            service.setWiredDeviceConnectionState(device, state, name);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setWiredDeviceConnectionState "+e);
        }
    }

     /**
     * Indicate A2DP source or sink connection state change.
     * @param device Bluetooth device connected/disconnected
     * @param state  new connection state (BluetoothProfile.STATE_xxx)
     * @param profile profile for the A2DP device
     * (either {@link android.bluetooth.BluetoothProfile.A2DP} or
     * {@link android.bluetooth.BluetoothProfile.A2DP_SINK})
     * @return a delay in ms that the caller should wait before broadcasting
     * BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED intent.
     * {@hide}
     */
    public int setBluetoothA2dpDeviceConnectionState(BluetoothDevice device, int state,
            int profile) {
        IAudioService service = getService();
        int delay = 0;
        try {
            delay = service.setBluetoothA2dpDeviceConnectionState(device, state, profile);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setBluetoothA2dpDeviceConnectionState "+e);
        } finally {
            return delay;
        }
    }

    /** {@hide} */
    public IRingtonePlayer getRingtonePlayer() {
        try {
            return getService().getRingtonePlayer();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Used as a key for {@link #getProperty} to request the native or optimal output sample rate
     * for this device's primary output stream, in decimal Hz.
     */
    public static final String PROPERTY_OUTPUT_SAMPLE_RATE =
            "android.media.property.OUTPUT_SAMPLE_RATE";

    /**
     * Used as a key for {@link #getProperty} to request the native or optimal output buffer size
     * for this device's primary output stream, in decimal PCM frames.
     */
    public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER =
            "android.media.property.OUTPUT_FRAMES_PER_BUFFER";

    /**
     * Returns the value of the property with the specified key.
     * @param key One of the strings corresponding to a property key: either
     *            {@link #PROPERTY_OUTPUT_SAMPLE_RATE} or
     *            {@link #PROPERTY_OUTPUT_FRAMES_PER_BUFFER}
     * @return A string representing the associated value for that property key,
     *         or null if there is no value for that key.
     */
    public String getProperty(String key) {
        if (PROPERTY_OUTPUT_SAMPLE_RATE.equals(key)) {
            int outputSampleRate = AudioSystem.getPrimaryOutputSamplingRate();
            return outputSampleRate > 0 ? Integer.toString(outputSampleRate) : null;
        } else if (PROPERTY_OUTPUT_FRAMES_PER_BUFFER.equals(key)) {
            int outputFramesPerBuffer = AudioSystem.getPrimaryOutputFrameCount();
            return outputFramesPerBuffer > 0 ? Integer.toString(outputFramesPerBuffer) : null;
        } else {
            // null or unknown key
            return null;
        }
    }

    /**
     * Returns the estimated latency for the given stream type in milliseconds.
     *
     * DO NOT UNHIDE. The existing approach for doing A/V sync has too many problems. We need
     * a better solution.
     * @hide
     */
    public int getOutputLatency(int streamType) {
        return AudioSystem.getOutputLatency(streamType);
    }

    /**
     * Registers a global volume controller interface.  Currently limited to SystemUI.
     *
     * @hide
     */
    public void setVolumeController(IVolumeController controller) {
        try {
            getService().setVolumeController(controller);
        } catch (RemoteException e) {
            Log.w(TAG, "Error setting volume controller", e);
        }
    }

    /**
     * Notify audio manager about volume controller visibility changes.
     * Currently limited to SystemUI.
     *
     * @hide
     */
    public void notifyVolumeControllerVisible(IVolumeController controller, boolean visible) {
        try {
            getService().notifyVolumeControllerVisible(controller, visible);
        } catch (RemoteException e) {
            Log.w(TAG, "Error notifying about volume controller visibility", e);
        }
    }

    /**
     * Only useful for volume controllers.
     * @hide
     */
    public boolean isStreamAffectedByRingerMode(int streamType) {
        try {
            return getService().isStreamAffectedByRingerMode(streamType);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling isStreamAffectedByRingerMode", e);
            return false;
        }
    }

    /**
     * Only useful for volume controllers.
     * @hide
     */
    public void disableSafeMediaVolume() {
        try {
            getService().disableSafeMediaVolume();
        } catch (RemoteException e) {
            Log.w(TAG, "Error disabling safe media volume", e);
        }
    }

    /**
     * Set Hdmi Cec system audio mode.
     *
     * @param on whether to be on system audio mode
     * @return output device type. 0 (DEVICE_NONE) if failed to set device.
     * @hide
     */
    public int setHdmiSystemAudioSupported(boolean on) {
        try {
            return getService().setHdmiSystemAudioSupported(on);
        } catch (RemoteException e) {
            Log.w(TAG, "Error setting system audio mode", e);
            return AudioSystem.DEVICE_NONE;
        }
    }

    /**
     * Return codes for listAudioPorts(), createAudioPatch() ...
     */

    /** @hide
     * CANDIDATE FOR PUBLIC API
     */
    public static final int SUCCESS = AudioSystem.SUCCESS;
    /**
     * A default error code.
     */
    public static final int ERROR = AudioSystem.ERROR;
    /** @hide
     * CANDIDATE FOR PUBLIC API
     */
    public static final int ERROR_BAD_VALUE = AudioSystem.BAD_VALUE;
    /** @hide
     */
    public static final int ERROR_INVALID_OPERATION = AudioSystem.INVALID_OPERATION;
    /** @hide
     */
    public static final int ERROR_PERMISSION_DENIED = AudioSystem.PERMISSION_DENIED;
    /** @hide
     */
    public static final int ERROR_NO_INIT = AudioSystem.NO_INIT;
    /**
     * An error code indicating that the object reporting it is no longer valid and needs to
     * be recreated.
     */
    public static final int ERROR_DEAD_OBJECT = AudioSystem.DEAD_OBJECT;

    /**
     * Returns a list of descriptors for all audio ports managed by the audio framework.
     * Audio ports are nodes in the audio framework or audio hardware that can be configured
     * or connected and disconnected with createAudioPatch() or releaseAudioPatch().
     * See AudioPort for a list of attributes of each audio port.
     * @param ports An AudioPort ArrayList where the list will be returned.
     * @hide
     */
    public int listAudioPorts(ArrayList<AudioPort> ports) {
        return updateAudioPortCache(ports, null);
    }

    /**
     * Specialized version of listAudioPorts() listing only audio devices (AudioDevicePort)
     * @see listAudioPorts(ArrayList<AudioPort>)
     * @hide
     */
    public int listAudioDevicePorts(ArrayList<AudioPort> devices) {
        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
        int status = updateAudioPortCache(ports, null);
        if (status == SUCCESS) {
            devices.clear();
            for (int i = 0; i < ports.size(); i++) {
                if (ports.get(i) instanceof AudioDevicePort) {
                    devices.add(ports.get(i));
                }
            }
        }
        return status;
    }

    /**
     * Create a connection between two or more devices. The framework will reject the request if
     * device types are not compatible or the implementation does not support the requested
     * configuration.
     * NOTE: current implementation is limited to one source and one sink per patch.
     * @param patch AudioPatch array where the newly created patch will be returned.
     *              As input, if patch[0] is not null, the specified patch will be replaced by the
     *              new patch created. This avoids calling releaseAudioPatch() when modifying a
     *              patch and allows the implementation to optimize transitions.
     * @param sources List of source audio ports. All must be AudioPort.ROLE_SOURCE.
     * @param sinks   List of sink audio ports. All must be AudioPort.ROLE_SINK.
     *
     * @return - {@link #SUCCESS} if connection is successful.
     *         - {@link #ERROR_BAD_VALUE} if incompatible device types are passed.
     *         - {@link #ERROR_INVALID_OPERATION} if the requested connection is not supported.
     *         - {@link #ERROR_PERMISSION_DENIED} if the client does not have permission to create
     *         a patch.
     *         - {@link #ERROR_DEAD_OBJECT} if the server process is dead
     *         - {@link #ERROR} if patch cannot be connected for any other reason.
     *
     *         patch[0] contains the newly created patch
     * @hide
     */
    public int createAudioPatch(AudioPatch[] patch,
                                 AudioPortConfig[] sources,
                                 AudioPortConfig[] sinks) {
        return AudioSystem.createAudioPatch(patch, sources, sinks);
    }

    /**
     * Releases an existing audio patch connection.
     * @param patch The audio patch to disconnect.
     * @return - {@link #SUCCESS} if disconnection is successful.
     *         - {@link #ERROR_BAD_VALUE} if the specified patch does not exist.
     *         - {@link #ERROR_PERMISSION_DENIED} if the client does not have permission to release
     *         a patch.
     *         - {@link #ERROR_DEAD_OBJECT} if the server process is dead
     *         - {@link #ERROR} if patch cannot be released for any other reason.
     * @hide
     */
    public int releaseAudioPatch(AudioPatch patch) {
        return AudioSystem.releaseAudioPatch(patch);
    }

    /**
     * List all existing connections between audio ports.
     * @param patches An AudioPatch array where the list will be returned.
     * @hide
     */
    public int listAudioPatches(ArrayList<AudioPatch> patches) {
        return updateAudioPortCache(null, patches);
    }

    /**
     * Set the gain on the specified AudioPort. The AudioGainConfig config is build by
     * AudioGain.buildConfig()
     * @hide
     */
    public int setAudioPortGain(AudioPort port, AudioGainConfig gain) {
        if (port == null || gain == null) {
            return ERROR_BAD_VALUE;
        }
        AudioPortConfig activeConfig = port.activeConfig();
        AudioPortConfig config = new AudioPortConfig(port, activeConfig.samplingRate(),
                                        activeConfig.channelMask(), activeConfig.format(), gain);
        config.mConfigMask = AudioPortConfig.GAIN;
        return AudioSystem.setAudioPortConfig(config);
    }

    /**
     * Listener registered by client to be notified upon new audio port connections,
     * disconnections or attributes update.
     * @hide
     */
    public interface OnAudioPortUpdateListener {
        /**
         * Callback method called upon audio port list update.
         * @param portList the updated list of audio ports
         */
        public void onAudioPortListUpdate(AudioPort[] portList);

        /**
         * Callback method called upon audio patch list update.
         * @param patchList the updated list of audio patches
         */
        public void onAudioPatchListUpdate(AudioPatch[] patchList);

        /**
         * Callback method called when the mediaserver dies
         */
        public void onServiceDied();
    }

    /**
     * Register an audio port list update listener.
     * @hide
     */
    public void registerAudioPortUpdateListener(OnAudioPortUpdateListener l) {
        mAudioPortEventHandler.registerListener(l);
    }

    /**
     * Unregister an audio port list update listener.
     * @hide
     */
    public void unregisterAudioPortUpdateListener(OnAudioPortUpdateListener l) {
        mAudioPortEventHandler.unregisterListener(l);
    }

    //
    // AudioPort implementation
    //

    static final int AUDIOPORT_GENERATION_INIT = 0;
    Integer mAudioPortGeneration = new Integer(AUDIOPORT_GENERATION_INIT);
    ArrayList<AudioPort> mAudioPortsCached = new ArrayList<AudioPort>();
    ArrayList<AudioPatch> mAudioPatchesCached = new ArrayList<AudioPatch>();

    int resetAudioPortGeneration() {
        int generation;
        synchronized (mAudioPortGeneration) {
            generation = mAudioPortGeneration;
            mAudioPortGeneration = AUDIOPORT_GENERATION_INIT;
        }
        return generation;
    }

    int updateAudioPortCache(ArrayList<AudioPort> ports, ArrayList<AudioPatch> patches) {
        synchronized (mAudioPortGeneration) {

            if (mAudioPortGeneration == AUDIOPORT_GENERATION_INIT) {
                int[] patchGeneration = new int[1];
                int[] portGeneration = new int[1];
                int status;
                ArrayList<AudioPort> newPorts = new ArrayList<AudioPort>();
                ArrayList<AudioPatch> newPatches = new ArrayList<AudioPatch>();

                do {
                    newPorts.clear();
                    status = AudioSystem.listAudioPorts(newPorts, portGeneration);
                    if (status != SUCCESS) {
                        return status;
                    }
                    newPatches.clear();
                    status = AudioSystem.listAudioPatches(newPatches, patchGeneration);
                    if (status != SUCCESS) {
                        return status;
                    }
                } while (patchGeneration[0] != portGeneration[0]);

                for (int i = 0; i < newPatches.size(); i++) {
                    for (int j = 0; j < newPatches.get(i).sources().length; j++) {
                        AudioPortConfig portCfg = updatePortConfig(newPatches.get(i).sources()[j],
                                                                   newPorts);
                        if (portCfg == null) {
                            return ERROR;
                        }
                        newPatches.get(i).sources()[j] = portCfg;
                    }
                    for (int j = 0; j < newPatches.get(i).sinks().length; j++) {
                        AudioPortConfig portCfg = updatePortConfig(newPatches.get(i).sinks()[j],
                                                                   newPorts);
                        if (portCfg == null) {
                            return ERROR;
                        }
                        newPatches.get(i).sinks()[j] = portCfg;
                    }
                }

                mAudioPortsCached = newPorts;
                mAudioPatchesCached = newPatches;
                mAudioPortGeneration = portGeneration[0];
            }
            if (ports != null) {
                ports.clear();
                ports.addAll(mAudioPortsCached);
            }
            if (patches != null) {
                patches.clear();
                patches.addAll(mAudioPatchesCached);
            }
        }
        return SUCCESS;
    }

    AudioPortConfig updatePortConfig(AudioPortConfig portCfg, ArrayList<AudioPort> ports) {
        AudioPort port = portCfg.port();
        int k;
        for (k = 0; k < ports.size(); k++) {
            // compare handles because the port returned by JNI is not of the correct
            // subclass
            if (ports.get(k).handle().equals(port.handle())) {
                port = ports.get(k);
                break;
            }
        }
        if (k == ports.size()) {
            // this hould never happen
            Log.e(TAG, "updatePortConfig port not found for handle: "+port.handle().id());
            return null;
        }
        AudioGainConfig gainCfg = portCfg.gain();
        if (gainCfg != null) {
            AudioGain gain = port.gain(gainCfg.index());
            gainCfg = gain.buildConfig(gainCfg.mode(),
                                       gainCfg.channelMask(),
                                       gainCfg.values(),
                                       gainCfg.rampDurationMs());
        }
        return port.buildConfig(portCfg.samplingRate(),
                                                 portCfg.channelMask(),
                                                 portCfg.format(),
                                                 gainCfg);
    }
}
