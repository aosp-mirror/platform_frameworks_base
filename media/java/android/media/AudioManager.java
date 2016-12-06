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
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.audiopolicy.AudioPolicy;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionLegacyHelper;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * AudioManager provides access to volume and ringer mode control.
 * <p>
 * Use <code>Context.getSystemService(Context.AUDIO_SERVICE)</code> to get
 * an instance of this class.
 */
public class AudioManager {

    private Context mOriginalContext;
    private Context mApplicationContext;
    private long mVolumeKeyUpTime;
    private final boolean mUseVolumeKeySounds;
    private final boolean mUseFixedVolume;
    private static String TAG = "AudioManager";
    private static final AudioPortEventHandler sAudioPortEventHandler = new AudioPortEventHandler();

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
     * @hide
     * Sticky broadcast intent action indicating that the internal ringer mode has
     * changed. Includes the new ringer mode.
     *
     * @see #EXTRA_RINGER_MODE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_RINGER_MODE_CHANGED_ACTION =
            "android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION";

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
     * @hide Broadcast intent when the devices for a particular stream type changes.
     * Includes the stream, the new devices and previous devices.
     * Notes:
     *  - for internal platform use only, do not make public,
     *  - never used for "remote" volume changes
     *
     * @see #EXTRA_VOLUME_STREAM_TYPE
     * @see #EXTRA_VOLUME_STREAM_DEVICES
     * @see #EXTRA_PREV_VOLUME_STREAM_DEVICES
     * @see #getDevicesForStream
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String STREAM_DEVICES_CHANGED_ACTION =
        "android.media.STREAM_DEVICES_CHANGED_ACTION";

    /**
     * @hide Broadcast intent when a stream mute state changes.
     * Includes the stream that changed and the new mute state
     *
     * @see #EXTRA_VOLUME_STREAM_TYPE
     * @see #EXTRA_STREAM_VOLUME_MUTED
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String STREAM_MUTE_CHANGED_ACTION =
        "android.media.STREAM_MUTE_CHANGED_ACTION";

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
     * @hide
     * The stream type alias for the volume changed intent.
     * For instance the intent may indicate a change of the {@link #STREAM_NOTIFICATION} stream
     * type (as indicated by the {@link #EXTRA_VOLUME_STREAM_TYPE} extra), but this is also
     * reflected by a change of the volume of its alias, {@link #STREAM_RING} on some devices,
     * {@link #STREAM_MUSIC} on others (e.g. a television).
     */
    public static final String EXTRA_VOLUME_STREAM_TYPE_ALIAS =
            "android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS";

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
     * @hide The devices associated with the stream for the stream devices changed intent.
     */
    public static final String EXTRA_VOLUME_STREAM_DEVICES =
        "android.media.EXTRA_VOLUME_STREAM_DEVICES";

    /**
     * @hide The previous devices associated with the stream for the stream devices changed intent.
     */
    public static final String EXTRA_PREV_VOLUME_STREAM_DEVICES =
        "android.media.EXTRA_PREV_VOLUME_STREAM_DEVICES";

    /**
     * @hide The new master volume mute state for the master mute changed intent.
     * Value is boolean
     */
    public static final String EXTRA_MASTER_VOLUME_MUTED =
        "android.media.EXTRA_MASTER_VOLUME_MUTED";

    /**
     * @hide The new stream volume mute state for the stream mute changed intent.
     * Value is boolean
     */
    public static final String EXTRA_STREAM_VOLUME_MUTED =
        "android.media.EXTRA_STREAM_VOLUME_MUTED";

    /**
     * Broadcast Action: Wired Headset plugged in or unplugged.
     *
     * You <em>cannot</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     *
     * <p>The intent will have the following extra values:
     * <ul>
     *   <li><em>state</em> - 0 for unplugged, 1 for plugged. </li>
     *   <li><em>name</em> - Headset type, human readable string </li>
     *   <li><em>microphone</em> - 1 if headset has a microphone, 0 otherwise </li>
     * </ul>
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_HEADSET_PLUG =
            "android.intent.action.HEADSET_PLUG";

    /**
     * Broadcast Action: A sticky broadcast indicating an HDMI cable was plugged or unplugged.
     *
     * The intent will have the following extra values: {@link #EXTRA_AUDIO_PLUG_STATE},
     * {@link #EXTRA_MAX_CHANNEL_COUNT}, {@link #EXTRA_ENCODINGS}.
     * <p>It can only be received by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_HDMI_AUDIO_PLUG =
            "android.media.action.HDMI_AUDIO_PLUG";

    /**
     * Extra used in {@link #ACTION_HDMI_AUDIO_PLUG} to communicate whether HDMI is plugged in
     * or unplugged.
     * An integer value of 1 indicates a plugged-in state, 0 is unplugged.
     */
    public static final String EXTRA_AUDIO_PLUG_STATE = "android.media.extra.AUDIO_PLUG_STATE";

    /**
     * Extra used in {@link #ACTION_HDMI_AUDIO_PLUG} to define the maximum number of channels
     * supported by the HDMI device.
     * The corresponding integer value is only available when the device is plugged in (as expressed
     * by {@link #EXTRA_AUDIO_PLUG_STATE}).
     */
    public static final String EXTRA_MAX_CHANNEL_COUNT = "android.media.extra.MAX_CHANNEL_COUNT";

    /**
     * Extra used in {@link #ACTION_HDMI_AUDIO_PLUG} to define the audio encodings supported by
     * the connected HDMI device.
     * The corresponding array of encoding values is only available when the device is plugged in
     * (as expressed by {@link #EXTRA_AUDIO_PLUG_STATE}). Encoding values are defined in
     * {@link AudioFormat} (for instance see {@link AudioFormat#ENCODING_PCM_16BIT}). Use
     * {@link android.content.Intent#getIntArrayExtra(String)} to retrieve the encoding values.
     */
    public static final String EXTRA_ENCODINGS = "android.media.extra.ENCODINGS";

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

    /**
     * Mute the volume. Has no effect if the stream is already muted.
     *
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     */
    public static final int ADJUST_MUTE = -100;

    /**
     * Unmute the volume. Has no effect if the stream is not muted.
     *
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     */
    public static final int ADJUST_UNMUTE = 100;

    /**
     * Toggle the mute state. If muted the stream will be unmuted. If not muted
     * the stream will be muted.
     *
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     */
    public static final int ADJUST_TOGGLE_MUTE = 101;

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
     * Adjusting the volume down from vibrated was prevented, display a hint in the UI.
     * @hide
     */
    public static final int FLAG_SHOW_VIBRATE_HINT = 1 << 11;

    /**
     * Adjusting the volume due to a hardware key press.
     * @hide
     */
    public static final int FLAG_FROM_KEY = 1 << 12;

    private static final String[] FLAG_NAMES = {
        "FLAG_SHOW_UI",
        "FLAG_ALLOW_RINGER_MODES",
        "FLAG_PLAY_SOUND",
        "FLAG_REMOVE_SOUND_AND_VIBRATE",
        "FLAG_VIBRATE",
        "FLAG_FIXED_VOLUME",
        "FLAG_BLUETOOTH_ABS_VOLUME",
        "FLAG_SHOW_SILENT_HINT",
        "FLAG_HDMI_SYSTEM_AUDIO_VOLUME",
        "FLAG_ACTIVE_MEDIA_ONLY",
        "FLAG_SHOW_UI_WARNINGS",
        "FLAG_SHOW_VIBRATE_HINT",
        "FLAG_FROM_KEY",
    };

    /** @hide */
    public static String flagsToString(int flags) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FLAG_NAMES.length; i++) {
            final int flag = 1 << i;
            if ((flags & flag) != 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(FLAG_NAMES[i]);
                flags &= ~flag;
            }
        }
        if (flags != 0) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(flags);
        }
        return sb.toString();
    }

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
     * Maximum valid ringer mode value. Values must start from 0 and be contiguous.
     * @hide
     */
    public static final int RINGER_MODE_MAX = RINGER_MODE_NORMAL;

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
        setContext(context);
        mUseVolumeKeySounds = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_useVolumeKeySounds);
        mUseFixedVolume = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_useFixedVolume);
    }

    private Context getContext() {
        if (mApplicationContext == null) {
            setContext(mOriginalContext);
        }
        if (mApplicationContext != null) {
            return mApplicationContext;
        }
        return mOriginalContext;
    }

    private void setContext(Context context) {
        mApplicationContext = context.getApplicationContext();
        if (mApplicationContext != null) {
            mOriginalContext = null;
        } else {
            mOriginalContext = context;
        }
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
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(getContext());
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
                && mVolumeKeyUpTime + AudioSystem.PLAY_SOUND_DELAY > SystemClock.uptimeMillis()) {
            /*
             * The user has hit another key during the delay (e.g., 300ms)
             * since the last volume key up, so cancel any sounds.
             */
            adjustSuggestedStreamVolume(ADJUST_SAME,
                    stream, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
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
                adjustSuggestedStreamVolume(
                        keyCode == KeyEvent.KEYCODE_VOLUME_UP
                                ? ADJUST_RAISE
                                : ADJUST_LOWER,
                        stream,
                        FLAG_SHOW_UI | FLAG_VIBRATE);
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (event.getRepeatCount() == 0) {
                    MediaSessionLegacyHelper.getHelper(getContext())
                            .sendVolumeKeyEvent(event, false);
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
                    adjustSuggestedStreamVolume(
                            ADJUST_SAME,
                            stream,
                            FLAG_PLAY_SOUND);
                }
                mVolumeKeyUpTime = SystemClock.uptimeMillis();
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                MediaSessionLegacyHelper.getHelper(getContext())
                        .sendVolumeKeyEvent(event, false);
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
            service.adjustStreamVolume(streamType, direction, flags,
                    getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adjusts the volume of the most relevant stream. For example, if a call is
     * active, it will have the highest priority regardless of if the in-call
     * screen is showing. Another example, if music is playing in the background
     * and a call is not active, the music stream will be adjusted.
     * <p>
     * This method should only be used by applications that replace the
     * platform-wide management of audio settings or the main telephony
     * application.
     * <p>
     * This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     *
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE},
     *            {@link #ADJUST_SAME}, {@link #ADJUST_MUTE},
     *            {@link #ADJUST_UNMUTE}, or {@link #ADJUST_TOGGLE_MUTE}.
     * @param flags One or more flags.
     * @see #adjustSuggestedStreamVolume(int, int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #isVolumeFixed()
     */
    public void adjustVolume(int direction, int flags) {
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(getContext());
        helper.sendAdjustVolumeBy(USE_DEFAULT_STREAM_TYPE, direction, flags);
    }

    /**
     * Adjusts the volume of the most relevant stream, or the given fallback
     * stream.
     * <p>
     * This method should only be used by applications that replace the
     * platform-wide management of audio settings or the main telephony
     * application.
     * <p>
     * This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     *
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE},
     *            {@link #ADJUST_SAME}, {@link #ADJUST_MUTE},
     *            {@link #ADJUST_UNMUTE}, or {@link #ADJUST_TOGGLE_MUTE}.
     * @param suggestedStreamType The stream type that will be used if there
     *            isn't a relevant stream. {@link #USE_DEFAULT_STREAM_TYPE} is
     *            valid here.
     * @param flags One or more flags.
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #isVolumeFixed()
     */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(getContext());
        helper.sendAdjustVolumeBy(suggestedStreamType, direction, flags);
    }

    /** @hide */
    public void setMasterMute(boolean mute, int flags) {
        IAudioService service = getService();
        try {
            service.setMasterMute(mute, flags, getContext().getOpPackageName(),
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            return service.getRingerModeExternal();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
        IAudioService service = getService();
        try {
            return service.isValidRingerMode(ringerMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the minimum volume index for a particular stream.
     *
     * @param streamType The stream type whose minimum volume index is returned.
     * @return The minimum valid volume index for the stream.
     * @see #getStreamVolume(int)
     * @hide
     */
    public int getStreamMinVolume(int streamType) {
        IAudioService service = getService();
        try {
            return service.getStreamMinVolume(streamType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            return service.getLastAudibleStreamVolume(streamType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the stream type whose volume is driving the UI sounds volume.
     * UI sounds are screen lock/unlock, camera shutter, key clicks...
     * It is assumed that this stream type is also tied to ringer mode changes.
     * @hide
     */
    public int getUiSoundsStreamType() {
        IAudioService service = getService();
        try {
            return service.getUiSoundsStreamType();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     * * <p>From N onward, ringer mode adjustments that would toggle Do Not Disturb are not allowed
     * unless the app has been granted Do Not Disturb Access.
     * See {@link NotificationManager#isNotificationPolicyAccessGranted()}.
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
            service.setRingerModeExternal(ringerMode, getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the volume index for a particular stream.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * <p>From N onward, volume adjustments that would toggle Do Not Disturb are not allowed unless
     * the app has been granted Do Not Disturb Access.
     * See {@link NotificationManager#isNotificationPolicyAccessGranted()}.
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
            service.setStreamVolume(streamType, index, flags, getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Solo or unsolo a particular stream.
     * <p>
     * Do not use. This method has been deprecated and is now a no-op.
     * {@link #requestAudioFocus} should be used for exclusive audio playback.
     *
     * @param streamType The stream to be soloed/unsoloed.
     * @param state The required solo state: true for solo ON, false for solo
     *            OFF
     * @see #isVolumeFixed()
     * @deprecated Do not use. If you need exclusive audio playback use
     *             {@link #requestAudioFocus}.
     */
    @Deprecated
    public void setStreamSolo(int streamType, boolean state) {
        Log.w(TAG, "setStreamSolo has been deprecated. Do not use.");
    }

    /**
     * Mute or unmute an audio stream.
     * <p>
     * This method should only be used by applications that replace the
     * platform-wide management of audio settings or the main telephony
     * application.
     * <p>
     * This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * <p>
     * This method was deprecated in API level 22. Prior to API level 22 this
     * method had significantly different behavior and should be used carefully.
     * The following applies only to pre-22 platforms:
     * <ul>
     * <li>The mute command is protected against client process death: if a
     * process with an active mute request on a stream dies, this stream will be
     * unmuted automatically.</li>
     * <li>The mute requests for a given stream are cumulative: the AudioManager
     * can receive several mute requests from one or more clients and the stream
     * will be unmuted only when the same number of unmute requests are
     * received.</li>
     * <li>For a better user experience, applications MUST unmute a muted stream
     * in onPause() and mute is again in onResume() if appropriate.</li>
     * </ul>
     *
     * @param streamType The stream to be muted/unmuted.
     * @param state The required mute state: true for mute ON, false for mute
     *            OFF
     * @see #isVolumeFixed()
     * @deprecated Use {@link #adjustStreamVolume(int, int, int)} with
     *             {@link #ADJUST_MUTE} or {@link #ADJUST_UNMUTE} instead.
     */
    @Deprecated
    public void setStreamMute(int streamType, boolean state) {
        Log.w(TAG, "setStreamMute is deprecated. adjustStreamVolume should be used instead.");
        int direction = state ? ADJUST_MUTE : ADJUST_UNMUTE;
        if (streamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
            adjustSuggestedStreamVolume(direction, streamType, 0);
        } else {
            adjustStreamVolume(streamType, direction, 0);
        }
    }

    /**
     * Returns the current mute state for a particular stream.
     *
     * @param streamType The stream to get mute state for.
     * @return The mute state for the given stream.
     * @see #adjustStreamVolume(int, int, int)
     */
    public boolean isStreamMute(int streamType) {
        IAudioService service = getService();
        try {
            return service.isStreamMute(streamType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
        return getContext().getResources().getBoolean(
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
            service.startBluetoothSco(mICallBack,
                    getContext().getApplicationInfo().targetSdkVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
                == AudioSystem.DEVICE_STATE_AVAILABLE) {
            return true;
        } else if (AudioSystem.getDeviceConnectionState(DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES,"")
                == AudioSystem.DEVICE_STATE_AVAILABLE) {
            return true;
        } else if (AudioSystem.getDeviceConnectionState(DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER,"")
                == AudioSystem.DEVICE_STATE_AVAILABLE) {
            return true;
        }
        return false;
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
    public void setMicrophoneMute(boolean on) {
        IAudioService service = getService();
        try {
            service.setMicrophoneMute(on, getContext().getOpPackageName(),
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            service.setMode(mode, mICallBack, mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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

        if (!querySoundEffectsEnabled(Process.myUserHandle().getIdentifier())) {
            return;
        }

        IAudioService service = getService();
        try {
            service.playSoundEffect(effectType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     * @param userId The current user to pull sound settings from
     * NOTE: This version uses the UI settings to determine
     * whether sounds are heard or not.
     * @hide
     */
    public void  playSoundEffect(int effectType, int userId) {
        if (effectType < 0 || effectType >= NUM_SOUND_EFFECTS) {
            return;
        }

        if (!querySoundEffectsEnabled(userId)) {
            return;
        }

        IAudioService service = getService();
        try {
            service.playSoundEffect(effectType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Settings has an in memory cache, so this is fast.
     */
    private boolean querySoundEffectsEnabled(int user) {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.SOUND_EFFECTS_ENABLED, 0, user) != 0;
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
     * Handler for events (audio focus change, recording config change) coming from the
     * audio service.
     */
    private final ServiceEventHandlerDelegate mServiceEventHandlerDelegate =
            new ServiceEventHandlerDelegate(null);

    /**
     * Event types
     */
    private final static int MSSG_FOCUS_CHANGE = 0;
    private final static int MSSG_RECORDING_CONFIG_CHANGE = 1;

    /**
     * Helper class to handle the forwarding of audio service events to the appropriate listener
     */
    private class ServiceEventHandlerDelegate {
        private final Handler mHandler;

        ServiceEventHandlerDelegate(Handler handler) {
            Looper looper;
            if (handler == null) {
                if ((looper = Looper.myLooper()) == null) {
                    looper = Looper.getMainLooper();
                }
            } else {
                looper = handler.getLooper();
            }

            if (looper != null) {
                // implement the event handler delegate to receive events from audio service
                mHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case MSSG_FOCUS_CHANGE:
                                OnAudioFocusChangeListener listener = null;
                                synchronized(mFocusListenerLock) {
                                    listener = findFocusListener((String)msg.obj);
                                }
                                if (listener != null) {
                                    Log.d(TAG, "AudioManager dispatching onAudioFocusChange("
                                            + msg.arg1 + ") for " + msg.obj);
                                    listener.onAudioFocusChange(msg.arg1);
                                }
                                break;
                            case MSSG_RECORDING_CONFIG_CHANGE:
                                final RecordConfigChangeCallbackData cbData =
                                        (RecordConfigChangeCallbackData) msg.obj;
                                if (cbData.mCb != null) {
                                    cbData.mCb.onRecordingConfigChanged(cbData.mConfigs);
                                }
                                break;
                            default:
                                Log.e(TAG, "Unknown event " + msg.what);
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
            final Message m = mServiceEventHandlerDelegate.getHandler().obtainMessage(
                    MSSG_FOCUS_CHANGE/*what*/, focusChange/*arg1*/, 0/*arg2 ignored*/, id/*obj*/);
            mServiceEventHandlerDelegate.getHandler().sendMessage(m);
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
      * @hide
      * A focus change request whose granting is delayed: the request was successful, but the
      * requester will only be granted audio focus once the condition that prevented immediate
      * granting has ended.
      * See {@link #requestAudioFocus(OnAudioFocusChangeListener, AudioAttributes, int, int)}
      */
    public static final int AUDIOFOCUS_REQUEST_DELAYED = 2;


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

        try {
            // status is guaranteed to be either AUDIOFOCUS_REQUEST_FAILED or
            // AUDIOFOCUS_REQUEST_GRANTED as focus is requested without the
            // AUDIOFOCUS_FLAG_DELAY_OK flag
            status = requestAudioFocus(l,
                    new AudioAttributes.Builder()
                            .setInternalLegacyStreamType(streamType).build(),
                    durationHint,
                    0 /* flags, legacy behavior */);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Audio focus request denied due to ", e);
        }

        return status;
    }

    // when adding new flags, add them to the relevant AUDIOFOCUS_FLAGS_APPS or SYSTEM masks
    /**
     * @hide
     * Use this flag when requesting audio focus to indicate it is ok for the requester to not be
     * granted audio focus immediately (as indicated by {@link #AUDIOFOCUS_REQUEST_DELAYED}) when
     * the system is in a state where focus cannot change, but be granted focus later when
     * this condition ends.
     */
    @SystemApi
    public static final int AUDIOFOCUS_FLAG_DELAY_OK = 0x1 << 0;
    /**
     * @hide
     * Use this flag when requesting audio focus to indicate that the requester
     * will pause its media playback (if applicable) when losing audio focus with
     * {@link #AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}, rather than ducking.
     * <br>On some platforms, the ducking may be handled without the application being aware of it
     * (i.e. it will not transiently lose focus). For applications that for instance play spoken
     * content, such as audio book or podcast players, ducking may never be acceptable, and will
     * thus always pause. This flag enables them to be declared as such whenever they request focus.
     */
    @SystemApi
    public static final int AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS = 0x1 << 1;
    /**
     * @hide
     * Use this flag to lock audio focus so granting is temporarily disabled.
     * <br>This flag can only be used by owners of a registered
     * {@link android.media.audiopolicy.AudioPolicy} in
     * {@link #requestAudioFocus(OnAudioFocusChangeListener, AudioAttributes, int, int, AudioPolicy)}
     */
    @SystemApi
    public static final int AUDIOFOCUS_FLAG_LOCK     = 0x1 << 2;
    /** @hide */
    public static final int AUDIOFOCUS_FLAGS_APPS = AUDIOFOCUS_FLAG_DELAY_OK
            | AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS;
    /** @hide */
    public static final int AUDIOFOCUS_FLAGS_SYSTEM = AUDIOFOCUS_FLAG_DELAY_OK
            | AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS | AUDIOFOCUS_FLAG_LOCK;

    /**
     * @hide
     * Request audio focus.
     * Send a request to obtain the audio focus. This method differs from
     * {@link #requestAudioFocus(OnAudioFocusChangeListener, int, int)} in that it can express
     * that the requester accepts delayed grants of audio focus.
     * @param l the listener to be notified of audio focus changes. It is not allowed to be null
     *     when the request is flagged with {@link #AUDIOFOCUS_FLAG_DELAY_OK}.
     * @param requestAttributes non null {@link AudioAttributes} describing the main reason for
     *     requesting audio focus.
     * @param durationHint use {@link #AUDIOFOCUS_GAIN_TRANSIENT} to indicate this focus request
     *      is temporary, and focus will be abandonned shortly. Examples of transient requests are
     *      for the playback of driving directions, or notifications sounds.
     *      Use {@link #AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK} to indicate also that it's ok for
     *      the previous focus owner to keep playing if it ducks its audio output.
     *      Alternatively use {@link #AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE} for a temporary request
     *      that benefits from the system not playing disruptive sounds like notifications, for
     *      usecases such as voice memo recording, or speech recognition.
     *      Use {@link #AUDIOFOCUS_GAIN} for a focus request of unknown duration such
     *      as the playback of a song or a video.
     * @param flags 0 or a combination of {link #AUDIOFOCUS_FLAG_DELAY_OK}
     *     and {@link #AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS}.
     *     <br>Use 0 when not using any flags for the request, which behaves like
     *     {@link #requestAudioFocus(OnAudioFocusChangeListener, int, int)}, where either audio
     *     focus is granted immediately, or the grant request fails because the system is in a
     *     state where focus cannot change (e.g. a phone call).
     * @return {@link #AUDIOFOCUS_REQUEST_FAILED}, {@link #AUDIOFOCUS_REQUEST_GRANTED}
     *     or {@link #AUDIOFOCUS_REQUEST_DELAYED}.
     *     The return value is never {@link #AUDIOFOCUS_REQUEST_DELAYED} when focus is requested
     *     without the {@link #AUDIOFOCUS_FLAG_DELAY_OK} flag.
     * @throws IllegalArgumentException
     */
    @SystemApi
    public int requestAudioFocus(OnAudioFocusChangeListener l,
            @NonNull AudioAttributes requestAttributes,
            int durationHint,
            int flags) throws IllegalArgumentException {
        if (flags != (flags & AUDIOFOCUS_FLAGS_APPS)) {
            throw new IllegalArgumentException("Invalid flags 0x"
                    + Integer.toHexString(flags).toUpperCase());
        }
        return requestAudioFocus(l, requestAttributes, durationHint,
                flags & AUDIOFOCUS_FLAGS_APPS,
                null /* no AudioPolicy*/);
    }

    /**
     * @hide
     * Request or lock audio focus.
     * This method is to be used by system components that have registered an
     * {@link android.media.audiopolicy.AudioPolicy} to request audio focus, but also to "lock" it
     * so focus granting is temporarily disabled.
     * @param l see the description of the same parameter in
     *     {@link #requestAudioFocus(OnAudioFocusChangeListener, AudioAttributes, int, int)}
     * @param requestAttributes non null {@link AudioAttributes} describing the main reason for
     *     requesting audio focus.
     * @param durationHint see the description of the same parameter in
     *     {@link #requestAudioFocus(OnAudioFocusChangeListener, AudioAttributes, int, int)}
     * @param flags 0 or a combination of {link #AUDIOFOCUS_FLAG_DELAY_OK},
     *     {@link #AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS}, and {@link #AUDIOFOCUS_FLAG_LOCK}.
     *     <br>Use 0 when not using any flags for the request, which behaves like
     *     {@link #requestAudioFocus(OnAudioFocusChangeListener, int, int)}, where either audio
     *     focus is granted immediately, or the grant request fails because the system is in a
     *     state where focus cannot change (e.g. a phone call).
     * @param ap a registered {@link android.media.audiopolicy.AudioPolicy} instance when locking
     *     focus, or null.
     * @return see the description of the same return value in
     *     {@link #requestAudioFocus(OnAudioFocusChangeListener, AudioAttributes, int, int)}
     * @throws IllegalArgumentException
     */
    @SystemApi
    public int requestAudioFocus(OnAudioFocusChangeListener l,
            @NonNull AudioAttributes requestAttributes,
            int durationHint,
            int flags,
            AudioPolicy ap) throws IllegalArgumentException {
        // parameter checking
        if (requestAttributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes argument");
        }
        if ((durationHint < AUDIOFOCUS_GAIN) ||
                (durationHint > AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)) {
            throw new IllegalArgumentException("Invalid duration hint");
        }
        if (flags != (flags & AUDIOFOCUS_FLAGS_SYSTEM)) {
            throw new IllegalArgumentException("Illegal flags 0x"
                + Integer.toHexString(flags).toUpperCase());
        }
        if (((flags & AUDIOFOCUS_FLAG_DELAY_OK) == AUDIOFOCUS_FLAG_DELAY_OK) && (l == null)) {
            throw new IllegalArgumentException(
                    "Illegal null focus listener when flagged as accepting delayed focus grant");
        }
        if (((flags & AUDIOFOCUS_FLAG_LOCK) == AUDIOFOCUS_FLAG_LOCK) && (ap == null)) {
            throw new IllegalArgumentException(
                    "Illegal null audio policy when locking audio focus");
        }

        int status = AUDIOFOCUS_REQUEST_FAILED;
        registerAudioFocusListener(l);
        IAudioService service = getService();
        try {
            status = service.requestAudioFocus(requestAttributes, durationHint, mICallBack,
                    mAudioFocusDispatcher, getIdForAudioFocusListener(l),
                    getContext().getOpPackageName() /* package name */, flags,
                    ap != null ? ap.cb() : null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            service.requestAudioFocus(new AudioAttributes.Builder()
                        .setInternalLegacyStreamType(streamType).build(),
                    durationHint, mICallBack, null,
                    AudioSystem.IN_VOICE_COMM_FOCUS_ID,
                    getContext().getOpPackageName(),
                    AUDIOFOCUS_FLAG_LOCK,
                    null /* policy token */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            service.abandonAudioFocus(null, AudioSystem.IN_VOICE_COMM_FOCUS_ID,
                    null /*AudioAttributes, legacy behavior*/);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     *  Abandon audio focus. Causes the previous focus owner, if any, to receive focus.
     *  @param l the listener with which focus was requested.
     *  @return {@link #AUDIOFOCUS_REQUEST_FAILED} or {@link #AUDIOFOCUS_REQUEST_GRANTED}
     */
    public int abandonAudioFocus(OnAudioFocusChangeListener l) {
        return abandonAudioFocus(l, null /*AudioAttributes, legacy behavior*/);
    }

    /**
     * @hide
     * Abandon audio focus. Causes the previous focus owner, if any, to receive focus.
     *  @param l the listener with which focus was requested.
     * @param aa the {@link AudioAttributes} with which audio focus was requested
     * @return {@link #AUDIOFOCUS_REQUEST_FAILED} or {@link #AUDIOFOCUS_REQUEST_GRANTED}
     */
    @SystemApi
    public int abandonAudioFocus(OnAudioFocusChangeListener l, AudioAttributes aa) {
        int status = AUDIOFOCUS_REQUEST_FAILED;
        unregisterAudioFocusListener(l);
        IAudioService service = getService();
        try {
            status = service.abandonAudioFocus(mAudioFocusDispatcher,
                    getIdForAudioFocusListener(l), aa);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
        if (!eventReceiver.getPackageName().equals(getContext().getPackageName())) {
            Log.e(TAG, "registerMediaButtonEventReceiver() error: " +
                    "receiver and context package names don't match");
            return;
        }
        // construct a PendingIntent for the media button and register it
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        //     the associated intent will be handled by the component being registered
        mediaButtonIntent.setComponent(eventReceiver);
        PendingIntent pi = PendingIntent.getBroadcast(getContext(),
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
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(getContext());
        helper.addMediaButtonListener(pi, eventReceiver, getContext());
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
        PendingIntent pi = PendingIntent.getBroadcast(getContext(),
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
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(getContext());
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
        rcClient.registerWithSession(MediaSessionLegacyHelper.getHelper(getContext()));
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
        rcClient.unregisterWithSession(MediaSessionLegacyHelper.getHelper(getContext()));
    }

    /**
     * Registers a {@link RemoteController} instance for it to receive media
     * metadata updates and playback state information from applications using
     * {@link RemoteControlClient}, and control their playback.
     * <p>
     * Registration requires the {@link RemoteController.OnClientUpdateListener} listener to be
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


    //====================================================================
    // Audio policy
    /**
     * @hide
     * Register the given {@link AudioPolicy}.
     * This call is synchronous and blocks until the registration process successfully completed
     * or failed to complete.
     * @param policy the non-null {@link AudioPolicy} to register.
     * @return {@link #ERROR} if there was an error communicating with the registration service
     *    or if the user doesn't have the required
     *    {@link android.Manifest.permission#MODIFY_AUDIO_ROUTING} permission,
     *    {@link #SUCCESS} otherwise.
     */
    @SystemApi
    public int registerAudioPolicy(@NonNull AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        IAudioService service = getService();
        try {
            String regId = service.registerAudioPolicy(policy.getConfig(), policy.cb(),
                    policy.hasFocusListener());
            if (regId == null) {
                return ERROR;
            } else {
                policy.setRegistration(regId);
            }
            // successful registration
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return SUCCESS;
    }

    /**
     * @hide
     * @param policy the non-null {@link AudioPolicy} to unregister.
     */
    @SystemApi
    public void unregisterAudioPolicyAsync(@NonNull AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        IAudioService service = getService();
        try {
            service.unregisterAudioPolicyAsync(policy.cb());
            policy.setRegistration(null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    //====================================================================
    // Recording configuration
    /**
     * Interface for receiving update notifications about the recording configuration. Extend
     * this abstract class and register it with
     * {@link AudioManager#registerAudioRecordingCallback(AudioRecordingCallback, Handler)}
     * to be notified.
     * Use {@link AudioManager#getActiveRecordingConfigurations()} to query the current
     * configuration.
     * @see AudioRecordingConfiguration
     */
    public static abstract class AudioRecordingCallback {
        /**
         * Called whenever the device recording configuration has changed.
         * @param configs list containing the results of
         *      {@link AudioManager#getActiveRecordingConfigurations()}.
         */
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {}
    }

    private static class AudioRecordingCallbackInfo {
        final AudioRecordingCallback mCb;
        final Handler mHandler;
        AudioRecordingCallbackInfo(AudioRecordingCallback cb, Handler handler) {
            mCb = cb;
            mHandler = handler;
        }
    }

    private final static class RecordConfigChangeCallbackData {
        final AudioRecordingCallback mCb;
        final List<AudioRecordingConfiguration> mConfigs;

        RecordConfigChangeCallbackData(AudioRecordingCallback cb,
                List<AudioRecordingConfiguration> configs) {
            mCb = cb;
            mConfigs = configs;
        }
    }

    /**
     * Register a callback to be notified of audio recording changes through
     * {@link AudioRecordingCallback}
     * @param cb non-null callback to register
     * @param handler the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the {@link Handler} associated with the main
     * {@link Looper} will be used.
     */
    public void registerAudioRecordingCallback(@NonNull AudioRecordingCallback cb, Handler handler)
    {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        }

        synchronized(mRecordCallbackLock) {
            // lazy initialization of the list of recording callbacks
            if (mRecordCallbackList == null) {
                mRecordCallbackList = new ArrayList<AudioRecordingCallbackInfo>();
            }
            final int oldCbCount = mRecordCallbackList.size();
            if (!hasRecordCallback_sync(cb)) {
                mRecordCallbackList.add(new AudioRecordingCallbackInfo(cb,
                        new ServiceEventHandlerDelegate(handler).getHandler()));
                final int newCbCount = mRecordCallbackList.size();
                if ((oldCbCount == 0) && (newCbCount > 0)) {
                    // register binder for callbacks
                    final IAudioService service = getService();
                    try {
                        service.registerRecordingCallback(mRecCb);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            } else {
                Log.w(TAG, "attempt to call registerAudioRecordingCallback() on a previously"
                        + "registered callback");
            }
        }
    }

    /**
     * Unregister an audio recording callback previously registered with
     * {@link #registerAudioRecordingCallback(AudioRecordingCallback, Handler)}.
     * @param cb non-null callback to unregister
     */
    public void unregisterAudioRecordingCallback(@NonNull AudioRecordingCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        }
        synchronized(mRecordCallbackLock) {
            if (mRecordCallbackList == null) {
                return;
            }
            final int oldCbCount = mRecordCallbackList.size();
            if (removeRecordCallback_sync(cb)) {
                final int newCbCount = mRecordCallbackList.size();
                if ((oldCbCount > 0) && (newCbCount == 0)) {
                    // unregister binder for callbacks
                    final IAudioService service = getService();
                    try {
                        service.unregisterRecordingCallback(mRecCb);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            } else {
                Log.w(TAG, "attempt to call unregisterAudioRecordingCallback() on a callback"
                        + " already unregistered or never registered");
            }
        }
    }

    /**
     * Returns the current active audio recording configurations of the device.
     * @return a non-null list of recording configurations. An empty list indicates there is
     *     no recording active when queried.
     * @see AudioRecordingConfiguration
     */
    public @NonNull List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
        final IAudioService service = getService();
        try {
            return service.getActiveRecordingConfigurations();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * constants for the recording events, to keep in sync
     * with frameworks/av/include/media/AudioPolicy.h
     */
    /** @hide */
    public final static int RECORD_CONFIG_EVENT_START = 1;
    /** @hide */
    public final static int RECORD_CONFIG_EVENT_STOP = 0;

    /**
     * All operations on this list are sync'd on mRecordCallbackLock.
     * List is lazy-initialized in
     * {@link #registerAudioRecordingCallback(AudioRecordingCallback, Handler)}.
     * List can be null.
     */
    private List<AudioRecordingCallbackInfo> mRecordCallbackList;
    private final Object mRecordCallbackLock = new Object();

    /**
     * Must be called synchronized on mRecordCallbackLock
     */
    private boolean hasRecordCallback_sync(@NonNull AudioRecordingCallback cb) {
        if (mRecordCallbackList != null) {
            for (int i=0 ; i < mRecordCallbackList.size() ; i++) {
                if (cb.equals(mRecordCallbackList.get(i).mCb)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Must be called synchronized on mRecordCallbackLock
     */
    private boolean removeRecordCallback_sync(@NonNull AudioRecordingCallback cb) {
        if (mRecordCallbackList != null) {
            for (int i=0 ; i < mRecordCallbackList.size() ; i++) {
                if (cb.equals(mRecordCallbackList.get(i).mCb)) {
                    mRecordCallbackList.remove(i);
                    return true;
                }
            }
        }
        return false;
    }

    private final IRecordingConfigDispatcher mRecCb = new IRecordingConfigDispatcher.Stub() {

        public void dispatchRecordingConfigChange(List<AudioRecordingConfiguration> configs) {
            synchronized(mRecordCallbackLock) {
                if (mRecordCallbackList != null) {
                    for (int i=0 ; i < mRecordCallbackList.size() ; i++) {
                        final AudioRecordingCallbackInfo arci = mRecordCallbackList.get(i);
                        if (arci.mHandler != null) {
                            final Message m = arci.mHandler.obtainMessage(
                                    MSSG_RECORDING_CONFIG_CHANGE/*what*/,
                                    new RecordConfigChangeCallbackData(arci.mCb, configs)/*obj*/);
                            arci.mHandler.sendMessage(m);
                        }
                    }
                }
            }
        }

    };

    //=====================================================================

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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
    public void setWiredDeviceConnectionState(int type, int state, String address, String name) {
        IAudioService service = getService();
        try {
            service.setWiredDeviceConnectionState(type, state, address, name,
                    mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
        }
        return delay;
    }

    /** {@hide} */
    public IRingtonePlayer getRingtonePlayer() {
        try {
            return getService().getRingtonePlayer();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Used as a key for {@link #getProperty} to request the native or optimal output sample rate
     * for this device's low latency output stream, in decimal Hz.  Latency-sensitive apps
     * should use this value as a default, and offer the user the option to override it.
     * The low latency output stream is typically either the device's primary output stream,
     * or another output stream with smaller buffers.
     */
    // FIXME Deprecate
    public static final String PROPERTY_OUTPUT_SAMPLE_RATE =
            "android.media.property.OUTPUT_SAMPLE_RATE";

    /**
     * Used as a key for {@link #getProperty} to request the native or optimal output buffer size
     * for this device's low latency output stream, in decimal PCM frames.  Latency-sensitive apps
     * should use this value as a minimum, and offer the user the option to override it.
     * The low latency output stream is typically either the device's primary output stream,
     * or another output stream with smaller buffers.
     */
    // FIXME Deprecate
    public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER =
            "android.media.property.OUTPUT_FRAMES_PER_BUFFER";

    /**
     * Used as a key for {@link #getProperty} to determine if the default microphone audio source
     * supports near-ultrasound frequencies (range of 18 - 21 kHz).
     */
    public static final String PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND =
            "android.media.property.SUPPORT_MIC_NEAR_ULTRASOUND";

    /**
     * Used as a key for {@link #getProperty} to determine if the default speaker audio path
     * supports near-ultrasound frequencies (range of 18 - 21 kHz).
     */
    public static final String PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND =
            "android.media.property.SUPPORT_SPEAKER_NEAR_ULTRASOUND";

    /**
     * Used as a key for {@link #getProperty} to determine if the unprocessed audio source is
     * available and supported with the expected frequency range and level response.
     */
    public static final String PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED =
            "android.media.property.SUPPORT_AUDIO_SOURCE_UNPROCESSED";
    /**
     * Returns the value of the property with the specified key.
     * @param key One of the strings corresponding to a property key: either
     *            {@link #PROPERTY_OUTPUT_SAMPLE_RATE},
     *            {@link #PROPERTY_OUTPUT_FRAMES_PER_BUFFER},
     *            {@link #PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND},
     *            {@link #PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND}, or
     *            {@link #PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED}.
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
        } else if (PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND.equals(key)) {
            // Will throw a RuntimeException Resources.NotFoundException if this config value is
            // not found.
            return String.valueOf(getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_supportMicNearUltrasound));
        } else if (PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND.equals(key)) {
            return String.valueOf(getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_supportSpeakerNearUltrasound));
        } else if (PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED.equals(key)) {
            return String.valueOf(getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_supportAudioSourceUnprocessed));
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Only useful for volume controllers.
     * @hide
     */
    public boolean isStreamAffectedByMute(int streamType) {
        try {
            return getService().isStreamAffectedByMute(streamType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Only useful for volume controllers.
     * @hide
     */
    public void disableSafeMediaVolume() {
        try {
            getService().disableSafeMediaVolume(mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Only useful for volume controllers.
     * @hide
     */
    public void setRingerModeInternal(int ringerMode) {
        try {
            getService().setRingerModeInternal(ringerMode, getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Only useful for volume controllers.
     * @hide
     */
    public int getRingerModeInternal() {
        try {
            return getService().getRingerModeInternal();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Only useful for volume controllers.
     * @hide
     */
    public void setVolumePolicy(VolumePolicy policy) {
        try {
            getService().setVolumePolicy(policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if Hdmi Cec system audio mode is supported.
     *
     * @hide
     */
    @SystemApi
    public boolean isHdmiSystemAudioSupported() {
        try {
            return getService().isHdmiSystemAudioSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
    public static int listAudioPorts(ArrayList<AudioPort> ports) {
        return updateAudioPortCache(ports, null, null);
    }

    /**
     * Returns a list of descriptors for all audio ports managed by the audio framework as
     * it was before the last update calback.
     * @param ports An AudioPort ArrayList where the list will be returned.
     * @hide
     */
    public static int listPreviousAudioPorts(ArrayList<AudioPort> ports) {
        return updateAudioPortCache(null, null, ports);
    }

    /**
     * Specialized version of listAudioPorts() listing only audio devices (AudioDevicePort)
     * @see listAudioPorts(ArrayList<AudioPort>)
     * @hide
     */
    public static int listAudioDevicePorts(ArrayList<AudioDevicePort> devices) {
        if (devices == null) {
            return ERROR_BAD_VALUE;
        }
        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
        int status = updateAudioPortCache(ports, null, null);
        if (status == SUCCESS) {
            filterDevicePorts(ports, devices);
        }
        return status;
    }

    /**
     * Specialized version of listPreviousAudioPorts() listing only audio devices (AudioDevicePort)
     * @see listPreviousAudioPorts(ArrayList<AudioPort>)
     * @hide
     */
    public static int listPreviousAudioDevicePorts(ArrayList<AudioDevicePort> devices) {
        if (devices == null) {
            return ERROR_BAD_VALUE;
        }
        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
        int status = updateAudioPortCache(null, null, ports);
        if (status == SUCCESS) {
            filterDevicePorts(ports, devices);
        }
        return status;
    }

    private static void filterDevicePorts(ArrayList<AudioPort> ports,
                                          ArrayList<AudioDevicePort> devices) {
        devices.clear();
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i) instanceof AudioDevicePort) {
                devices.add((AudioDevicePort)ports.get(i));
            }
        }
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
    public static int createAudioPatch(AudioPatch[] patch,
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
    public static int releaseAudioPatch(AudioPatch patch) {
        return AudioSystem.releaseAudioPatch(patch);
    }

    /**
     * List all existing connections between audio ports.
     * @param patches An AudioPatch array where the list will be returned.
     * @hide
     */
    public static int listAudioPatches(ArrayList<AudioPatch> patches) {
        return updateAudioPortCache(null, patches, null);
    }

    /**
     * Set the gain on the specified AudioPort. The AudioGainConfig config is build by
     * AudioGain.buildConfig()
     * @hide
     */
    public static int setAudioPortGain(AudioPort port, AudioGainConfig gain) {
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
        sAudioPortEventHandler.init();
        sAudioPortEventHandler.registerListener(l);
    }

    /**
     * Unregister an audio port list update listener.
     * @hide
     */
    public void unregisterAudioPortUpdateListener(OnAudioPortUpdateListener l) {
        sAudioPortEventHandler.unregisterListener(l);
    }

    //
    // AudioPort implementation
    //

    static final int AUDIOPORT_GENERATION_INIT = 0;
    static Integer sAudioPortGeneration = new Integer(AUDIOPORT_GENERATION_INIT);
    static ArrayList<AudioPort> sAudioPortsCached = new ArrayList<AudioPort>();
    static ArrayList<AudioPort> sPreviousAudioPortsCached = new ArrayList<AudioPort>();
    static ArrayList<AudioPatch> sAudioPatchesCached = new ArrayList<AudioPatch>();

    static int resetAudioPortGeneration() {
        int generation;
        synchronized (sAudioPortGeneration) {
            generation = sAudioPortGeneration;
            sAudioPortGeneration = AUDIOPORT_GENERATION_INIT;
        }
        return generation;
    }

    static int updateAudioPortCache(ArrayList<AudioPort> ports, ArrayList<AudioPatch> patches,
                                    ArrayList<AudioPort> previousPorts) {
        sAudioPortEventHandler.init();
        synchronized (sAudioPortGeneration) {

            if (sAudioPortGeneration == AUDIOPORT_GENERATION_INIT) {
                int[] patchGeneration = new int[1];
                int[] portGeneration = new int[1];
                int status;
                ArrayList<AudioPort> newPorts = new ArrayList<AudioPort>();
                ArrayList<AudioPatch> newPatches = new ArrayList<AudioPatch>();

                do {
                    newPorts.clear();
                    status = AudioSystem.listAudioPorts(newPorts, portGeneration);
                    if (status != SUCCESS) {
                        Log.w(TAG, "updateAudioPortCache: listAudioPorts failed");
                        return status;
                    }
                    newPatches.clear();
                    status = AudioSystem.listAudioPatches(newPatches, patchGeneration);
                    if (status != SUCCESS) {
                        Log.w(TAG, "updateAudioPortCache: listAudioPatches failed");
                        return status;
                    }
                } while (patchGeneration[0] != portGeneration[0]);

                for (int i = 0; i < newPatches.size(); i++) {
                    for (int j = 0; j < newPatches.get(i).sources().length; j++) {
                        AudioPortConfig portCfg = updatePortConfig(newPatches.get(i).sources()[j],
                                                                   newPorts);
                        newPatches.get(i).sources()[j] = portCfg;
                    }
                    for (int j = 0; j < newPatches.get(i).sinks().length; j++) {
                        AudioPortConfig portCfg = updatePortConfig(newPatches.get(i).sinks()[j],
                                                                   newPorts);
                        newPatches.get(i).sinks()[j] = portCfg;
                    }
                }
                for (Iterator<AudioPatch> i = newPatches.iterator(); i.hasNext(); ) {
                    AudioPatch newPatch = i.next();
                    boolean hasInvalidPort = false;
                    for (AudioPortConfig portCfg : newPatch.sources()) {
                        if (portCfg == null) {
                            hasInvalidPort = true;
                            break;
                        }
                    }
                    for (AudioPortConfig portCfg : newPatch.sinks()) {
                        if (portCfg == null) {
                            hasInvalidPort = true;
                            break;
                        }
                    }
                    if (hasInvalidPort) {
                        // Temporarily remove patches with invalid ports. One who created the patch
                        // is responsible for dealing with the port change.
                        i.remove();
                    }
                }

                sPreviousAudioPortsCached = sAudioPortsCached;
                sAudioPortsCached = newPorts;
                sAudioPatchesCached = newPatches;
                sAudioPortGeneration = portGeneration[0];
            }
            if (ports != null) {
                ports.clear();
                ports.addAll(sAudioPortsCached);
            }
            if (patches != null) {
                patches.clear();
                patches.addAll(sAudioPatchesCached);
            }
            if (previousPorts != null) {
                previousPorts.clear();
                previousPorts.addAll(sPreviousAudioPortsCached);
            }
        }
        return SUCCESS;
    }

    static AudioPortConfig updatePortConfig(AudioPortConfig portCfg, ArrayList<AudioPort> ports) {
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

    private OnAmPortUpdateListener mPortListener = null;

    /**
     * The message sent to apps when the contents of the device list changes if they provide
     * a {#link Handler} object to addOnAudioDeviceConnectionListener().
     */
    private final static int MSG_DEVICES_CALLBACK_REGISTERED = 0;
    private final static int MSG_DEVICES_DEVICES_ADDED = 1;
    private final static int MSG_DEVICES_DEVICES_REMOVED = 2;

    /**
     * The list of {@link AudioDeviceCallback} objects to receive add/remove notifications.
     */
    private ArrayMap<AudioDeviceCallback, NativeEventHandlerDelegate>
        mDeviceCallbacks =
            new ArrayMap<AudioDeviceCallback, NativeEventHandlerDelegate>();

    /**
     * The following are flags to allow users of {@link AudioManager#getDevices(int)} to filter
     * the results list to only those device types they are interested in.
     */
    /**
     * Specifies to the {@link AudioManager#getDevices(int)} method to include
     * source (i.e. input) audio devices.
     */
    public static final int GET_DEVICES_INPUTS    = 0x0001;

    /**
     * Specifies to the {@link AudioManager#getDevices(int)} method to include
     * sink (i.e. output) audio devices.
     */
    public static final int GET_DEVICES_OUTPUTS   = 0x0002;

    /**
     * Specifies to the {@link AudioManager#getDevices(int)} method to include both
     * source and sink devices.
     */
    public static final int GET_DEVICES_ALL = GET_DEVICES_OUTPUTS | GET_DEVICES_INPUTS;

    /**
     * Determines if a given AudioDevicePort meets the specified filter criteria.
     * @param port  The port to test.
     * @param flags A set of bitflags specifying the criteria to test.
     * @see {@link GET_DEVICES_OUTPUTS} and {@link GET_DEVICES_INPUTS}
     **/
    private static boolean checkFlags(AudioDevicePort port, int flags) {
        return port.role() == AudioPort.ROLE_SINK && (flags & GET_DEVICES_OUTPUTS) != 0 ||
               port.role() == AudioPort.ROLE_SOURCE && (flags & GET_DEVICES_INPUTS) != 0;
    }

    private static boolean checkTypes(AudioDevicePort port) {
        return AudioDeviceInfo.convertInternalDeviceToDeviceType(port.type()) !=
                    AudioDeviceInfo.TYPE_UNKNOWN &&
                port.type() != AudioSystem.DEVICE_IN_BACK_MIC;
    }

    /**
     * Returns an array of {@link AudioDeviceInfo} objects corresponding to the audio devices
     * currently connected to the system and meeting the criteria specified in the
     * <code>flags</code> parameter.
     * @param flags A set of bitflags specifying the criteria to test.
     * @see {@link GET_DEVICES_OUTPUTS}, {@link GET_DEVICES_INPUTS} and {@link GET_DEVICES_ALL}.
     * @return A (possibly zero-length) array of AudioDeviceInfo objects.
     */
    public AudioDeviceInfo[] getDevices(int flags) {
        return getDevicesStatic(flags);
    }

    /**
     * Does the actual computation to generate an array of (externally-visible) AudioDeviceInfo
     * objects from the current (internal) AudioDevicePort list.
     */
    private static AudioDeviceInfo[]
        infoListFromPortList(ArrayList<AudioDevicePort> ports, int flags) {

        // figure out how many AudioDeviceInfo we need space for...
        int numRecs = 0;
        for (AudioDevicePort port : ports) {
            if (checkTypes(port) && checkFlags(port, flags)) {
                numRecs++;
            }
        }

        // Now load them up...
        AudioDeviceInfo[] deviceList = new AudioDeviceInfo[numRecs];
        int slot = 0;
        for (AudioDevicePort port : ports) {
            if (checkTypes(port) && checkFlags(port, flags)) {
                deviceList[slot++] = new AudioDeviceInfo(port);
            }
        }

        return deviceList;
    }

    /*
     * Calculate the list of ports that are in ports_B, but not in ports_A. This is used by
     * the add/remove callback mechanism to provide a list of the newly added or removed devices
     * rather than the whole list and make the app figure it out.
     * Note that calling this method with:
     *  ports_A == PREVIOUS_ports and ports_B == CURRENT_ports will calculated ADDED ports.
     *  ports_A == CURRENT_ports and ports_B == PREVIOUS_ports will calculated REMOVED ports.
     */
    private static AudioDeviceInfo[] calcListDeltas(
            ArrayList<AudioDevicePort> ports_A, ArrayList<AudioDevicePort> ports_B, int flags) {

        ArrayList<AudioDevicePort> delta_ports = new ArrayList<AudioDevicePort>();

        AudioDevicePort cur_port = null;
        for (int cur_index = 0; cur_index < ports_B.size(); cur_index++) {
            boolean cur_port_found = false;
            cur_port = ports_B.get(cur_index);
            for (int prev_index = 0;
                 prev_index < ports_A.size() && !cur_port_found;
                 prev_index++) {
                cur_port_found = (cur_port.id() == ports_A.get(prev_index).id());
            }

            if (!cur_port_found) {
                delta_ports.add(cur_port);
            }
        }

        return infoListFromPortList(delta_ports, flags);
    }

    /**
     * Generates a list of AudioDeviceInfo objects corresponding to the audio devices currently
     * connected to the system and meeting the criteria specified in the <code>flags</code>
     * parameter.
     * This is an internal function. The public API front is getDevices(int).
     * @param flags A set of bitflags specifying the criteria to test.
     * @see {@link GET_DEVICES_OUTPUTS}, {@link GET_DEVICES_INPUTS} and {@link GET_DEVICES_ALL}.
     * @return A (possibly zero-length) array of AudioDeviceInfo objects.
     * @hide
     */
    public static AudioDeviceInfo[] getDevicesStatic(int flags) {
        ArrayList<AudioDevicePort> ports = new ArrayList<AudioDevicePort>();
        int status = AudioManager.listAudioDevicePorts(ports);
        if (status != AudioManager.SUCCESS) {
            // fail and bail!
            return new AudioDeviceInfo[0];  // Always return an array.
        }

        return infoListFromPortList(ports, flags);
    }

    /**
     * Registers an {@link AudioDeviceCallback} object to receive notifications of changes
     * to the set of connected audio devices.
     * @param callback The {@link AudioDeviceCallback} object to receive connect/disconnect
     * notifications.
     * @param handler Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the {@link Handler} associated with the main
     * {@link Looper} will be used.
     */
    public void registerAudioDeviceCallback(AudioDeviceCallback callback,
            android.os.Handler handler) {
        synchronized (mDeviceCallbacks) {
            if (callback != null && !mDeviceCallbacks.containsKey(callback)) {
                if (mDeviceCallbacks.size() == 0) {
                    if (mPortListener == null) {
                        mPortListener = new OnAmPortUpdateListener();
                    }
                    registerAudioPortUpdateListener(mPortListener);
                }
                NativeEventHandlerDelegate delegate =
                        new NativeEventHandlerDelegate(callback, handler);
                mDeviceCallbacks.put(callback, delegate);
                broadcastDeviceListChange(delegate.getHandler());
            }
        }
    }

    /**
     * Unregisters an {@link AudioDeviceCallback} object which has been previously registered
     * to receive notifications of changes to the set of connected audio devices.
     * @param callback The {@link AudioDeviceCallback} object that was previously registered
     * with {@link AudioManager#registerAudioDeviceCallback) to be unregistered.
     */
    public void unregisterAudioDeviceCallback(AudioDeviceCallback callback) {
        synchronized (mDeviceCallbacks) {
            if (mDeviceCallbacks.containsKey(callback)) {
                mDeviceCallbacks.remove(callback);
                if (mDeviceCallbacks.size() == 0) {
                    unregisterAudioPortUpdateListener(mPortListener);
                }
            }
        }
    }

    // Since we need to calculate the changes since THE LAST NOTIFICATION, and not since the
    // (unpredictable) last time updateAudioPortCache() was called by someone, keep a list
    // of the ports that exist at the time of the last notification.
    private ArrayList<AudioDevicePort> mPreviousPorts = new ArrayList<AudioDevicePort>();

    /**
     * Internal method to compute and generate add/remove messages and then send to any
     * registered callbacks.
     */
    private void broadcastDeviceListChange(Handler handler) {
        int status;

        // Get the new current set of ports
        ArrayList<AudioDevicePort> current_ports = new ArrayList<AudioDevicePort>();
        status = AudioManager.listAudioDevicePorts(current_ports);
        if (status != AudioManager.SUCCESS) {
            return;
        }

        if (handler != null) {
            // This is the callback for the registration, so send the current list
            AudioDeviceInfo[] deviceList =
                    infoListFromPortList(current_ports, GET_DEVICES_ALL);
            handler.sendMessage(
                    Message.obtain(handler, MSG_DEVICES_CALLBACK_REGISTERED, deviceList));
        } else {
            AudioDeviceInfo[] added_devices =
                    calcListDeltas(mPreviousPorts, current_ports, GET_DEVICES_ALL);
            AudioDeviceInfo[] removed_devices =
                    calcListDeltas(current_ports, mPreviousPorts, GET_DEVICES_ALL);

            if (added_devices.length != 0 || removed_devices.length != 0) {
                synchronized (mDeviceCallbacks) {
                    for (int i = 0; i < mDeviceCallbacks.size(); i++) {
                        handler = mDeviceCallbacks.valueAt(i).getHandler();
                        if (handler != null) {
                            if (added_devices.length != 0) {
                                handler.sendMessage(Message.obtain(handler,
                                                                   MSG_DEVICES_DEVICES_ADDED,
                                                                   added_devices));
                            }
                            if (removed_devices.length != 0) {
                                handler.sendMessage(Message.obtain(handler,
                                                                   MSG_DEVICES_DEVICES_REMOVED,
                                                                   removed_devices));
                            }
                        }
                    }
                }
            }
        }

        mPreviousPorts = current_ports;
    }

    /**
     * Handles Port list update notifications from the AudioManager
     */
    private class OnAmPortUpdateListener implements AudioManager.OnAudioPortUpdateListener {
        static final String TAG = "OnAmPortUpdateListener";
        public void onAudioPortListUpdate(AudioPort[] portList) {
            broadcastDeviceListChange(null);
        }

        /**
         * Callback method called upon audio patch list update.
         * Note: We don't do anything with Patches at this time, so ignore this notification.
         * @param patchList the updated list of audio patches.
         */
        public void onAudioPatchListUpdate(AudioPatch[] patchList) {}

        /**
         * Callback method called when the mediaserver dies
         */
        public void onServiceDied() {
            broadcastDeviceListChange(null);
        }
    }

    //---------------------------------------------------------
    // Inner classes
    //--------------------
    /**
     * Helper class to handle the forwarding of native events to the appropriate listener
     * (potentially) handled in a different thread.
     */
    private class NativeEventHandlerDelegate {
        private final Handler mHandler;

        NativeEventHandlerDelegate(final AudioDeviceCallback callback,
                                   Handler handler) {
            // find the looper for our new event handler
            Looper looper;
            if (handler != null) {
                looper = handler.getLooper();
            } else {
                // no given handler, use the looper the addListener call was called in
                looper = Looper.getMainLooper();
            }

            // construct the event handler with this looper
            if (looper != null) {
                // implement the event handler delegate
                mHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch(msg.what) {
                        case MSG_DEVICES_CALLBACK_REGISTERED:
                        case MSG_DEVICES_DEVICES_ADDED:
                            if (callback != null) {
                                callback.onAudioDevicesAdded((AudioDeviceInfo[])msg.obj);
                            }
                            break;

                        case MSG_DEVICES_DEVICES_REMOVED:
                            if (callback != null) {
                                callback.onAudioDevicesRemoved((AudioDeviceInfo[])msg.obj);
                            }
                           break;

                        default:
                            Log.e(TAG, "Unknown native event type: " + msg.what);
                            break;
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
}
