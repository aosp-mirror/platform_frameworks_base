/*
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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes.AttributeSystemUsage;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioPolicy.AudioPolicyFocusListener;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.media.audiopolicy.AudioVolumeGroupChangeHandler;
import android.media.projection.MediaProjection;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionLegacyHelper;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;


/**
 * AudioManager provides access to volume and ringer mode control.
 */
@SystemService(Context.AUDIO_SERVICE)
public class AudioManager {

    private Context mOriginalContext;
    private Context mApplicationContext;
    private long mVolumeKeyUpTime;
    private final boolean mUseVolumeKeySounds;
    private final boolean mUseFixedVolume;
    private static final String TAG = "AudioManager";
    private static final boolean DEBUG = false;
    private static final AudioPortEventHandler sAudioPortEventHandler = new AudioPortEventHandler();
    private static final AudioVolumeGroupChangeHandler sAudioAudioVolumeGroupChangedHandler =
            new AudioVolumeGroupChangeHandler();

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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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

    /** Used to identify the volume of audio streams for phone calls */
    public static final int STREAM_VOICE_CALL = AudioSystem.STREAM_VOICE_CALL;
    /** Used to identify the volume of audio streams for system sounds */
    public static final int STREAM_SYSTEM = AudioSystem.STREAM_SYSTEM;
    /** Used to identify the volume of audio streams for the phone ring */
    public static final int STREAM_RING = AudioSystem.STREAM_RING;
    /** Used to identify the volume of audio streams for music playback */
    public static final int STREAM_MUSIC = AudioSystem.STREAM_MUSIC;
    /** Used to identify the volume of audio streams for alarms */
    public static final int STREAM_ALARM = AudioSystem.STREAM_ALARM;
    /** Used to identify the volume of audio streams for notifications */
    public static final int STREAM_NOTIFICATION = AudioSystem.STREAM_NOTIFICATION;
    /** @hide Used to identify the volume of audio streams for phone calls when connected
     *        to bluetooth */
    @UnsupportedAppUsage
    public static final int STREAM_BLUETOOTH_SCO = AudioSystem.STREAM_BLUETOOTH_SCO;
    /** @hide Used to identify the volume of audio streams for enforced system sounds
     *        in certain countries (e.g camera in Japan) */
    @UnsupportedAppUsage
    public static final int STREAM_SYSTEM_ENFORCED = AudioSystem.STREAM_SYSTEM_ENFORCED;
    /** Used to identify the volume of audio streams for DTMF Tones */
    public static final int STREAM_DTMF = AudioSystem.STREAM_DTMF;
    /** @hide Used to identify the volume of audio streams exclusively transmitted through the
     *        speaker (TTS) of the device */
    @UnsupportedAppUsage
    public static final int STREAM_TTS = AudioSystem.STREAM_TTS;
    /** Used to identify the volume of audio streams for accessibility prompts */
    public static final int STREAM_ACCESSIBILITY = AudioSystem.STREAM_ACCESSIBILITY;
    /** @hide Used to identify the volume of audio streams for virtual assistant */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int STREAM_ASSISTANT = AudioSystem.STREAM_ASSISTANT;

    /** Number of audio streams */
    /**
     * @deprecated Do not iterate on volume stream type values.
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

    /** @hide */
    @IntDef(flag = false, prefix = "ADJUST", value = {
            ADJUST_RAISE,
            ADJUST_LOWER,
            ADJUST_SAME,
            ADJUST_MUTE,
            ADJUST_UNMUTE,
            ADJUST_TOGGLE_MUTE }
            )
    @Retention(RetentionPolicy.SOURCE)
    public @interface VolumeAdjustment {}

    /** @hide */
    public static final String adjustToString(int adj) {
        switch (adj) {
            case ADJUST_RAISE: return "ADJUST_RAISE";
            case ADJUST_LOWER: return "ADJUST_LOWER";
            case ADJUST_SAME: return "ADJUST_SAME";
            case ADJUST_MUTE: return "ADJUST_MUTE";
            case ADJUST_UNMUTE: return "ADJUST_UNMUTE";
            case ADJUST_TOGGLE_MUTE: return "ADJUST_TOGGLE_MUTE";
            default: return new StringBuilder("unknown adjust mode ").append(adj).toString();
        }
    }

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
     * This flag can be used in the places in order to denote (or check) that a volume adjustment
     * request is from a hardware key press. (e.g. {@link MediaController}).
     * @hide
     */
    public static final int FLAG_FROM_KEY = 1 << 12;

    // The iterator of TreeMap#entrySet() returns the entries in ascending key order.
    private static final TreeMap<Integer, String> FLAG_NAMES = new TreeMap<>();

    static {
        FLAG_NAMES.put(FLAG_SHOW_UI, "FLAG_SHOW_UI");
        FLAG_NAMES.put(FLAG_ALLOW_RINGER_MODES, "FLAG_ALLOW_RINGER_MODES");
        FLAG_NAMES.put(FLAG_PLAY_SOUND, "FLAG_PLAY_SOUND");
        FLAG_NAMES.put(FLAG_REMOVE_SOUND_AND_VIBRATE, "FLAG_REMOVE_SOUND_AND_VIBRATE");
        FLAG_NAMES.put(FLAG_VIBRATE, "FLAG_VIBRATE");
        FLAG_NAMES.put(FLAG_FIXED_VOLUME, "FLAG_FIXED_VOLUME");
        FLAG_NAMES.put(FLAG_BLUETOOTH_ABS_VOLUME, "FLAG_BLUETOOTH_ABS_VOLUME");
        FLAG_NAMES.put(FLAG_SHOW_SILENT_HINT, "FLAG_SHOW_SILENT_HINT");
        FLAG_NAMES.put(FLAG_HDMI_SYSTEM_AUDIO_VOLUME, "FLAG_HDMI_SYSTEM_AUDIO_VOLUME");
        FLAG_NAMES.put(FLAG_ACTIVE_MEDIA_ONLY, "FLAG_ACTIVE_MEDIA_ONLY");
        FLAG_NAMES.put(FLAG_SHOW_UI_WARNINGS, "FLAG_SHOW_UI_WARNINGS");
        FLAG_NAMES.put(FLAG_SHOW_VIBRATE_HINT, "FLAG_SHOW_VIBRATE_HINT");
        FLAG_NAMES.put(FLAG_FROM_KEY, "FLAG_FROM_KEY");
    }

    /** @hide */
    public static String flagsToString(int flags) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> entry : FLAG_NAMES.entrySet()) {
            final int flag = entry.getKey();
            if ((flags & flag) != 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(entry.getValue());
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
     * For test purposes only, will throw NPE with some methods that require a Context.
     */
    @UnsupportedAppUsage
    public AudioManager() {
        mUseVolumeKeySounds = true;
        mUseFixedVolume = false;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
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

    @UnsupportedAppUsage
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
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * <p>From N onward, ringer mode adjustments that would toggle Do Not Disturb are not allowed
     * unless the app has been granted Do Not Disturb Access.
     * See {@link NotificationManager#isNotificationPolicyAccessGranted()}.
     *
     * @param streamType The stream type to adjust. One of {@link #STREAM_VOICE_CALL},
     * {@link #STREAM_SYSTEM}, {@link #STREAM_RING}, {@link #STREAM_MUSIC},
     * {@link #STREAM_ALARM} or {@link #STREAM_ACCESSIBILITY}.
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param flags One or more flags.
     * @see #adjustVolume(int, int)
     * @see #setStreamVolume(int, int, int)
     * @throws SecurityException if the adjustment triggers a Do Not Disturb change
     *   and the caller is not granted notification policy access.
     */
    public void adjustStreamVolume(int streamType, int direction, int flags) {
        final IAudioService service = getService();
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
    @UnsupportedAppUsage
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setMasterMute(boolean mute, int flags) {
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
    @UnsupportedAppUsage
    public static boolean isValidRingerMode(int ringerMode) {
        if (ringerMode < 0 || ringerMode > RINGER_MODE_MAX) {
            return false;
        }
        final IAudioService service = getService();
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
        final IAudioService service = getService();
        try {
            return service.getStreamMaxVolume(streamType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the minimum volume index for a particular stream.
     * @param streamType The stream type whose minimum volume index is returned. Must be one of
     *     {@link #STREAM_VOICE_CALL}, {@link #STREAM_SYSTEM},
     *     {@link #STREAM_RING}, {@link #STREAM_MUSIC}, {@link #STREAM_ALARM},
     *     {@link #STREAM_NOTIFICATION}, {@link #STREAM_DTMF} or {@link #STREAM_ACCESSIBILITY}.
     * @return The minimum valid volume index for the stream.
     * @see #getStreamVolume(int)
     */
    public int getStreamMinVolume(int streamType) {
        if (!isPublicStreamType(streamType)) {
            throw new IllegalArgumentException("Invalid stream type " + streamType);
        }
        return getStreamMinVolumeInt(streamType);
    }

    /**
     * @hide
     * Same as {@link #getStreamMinVolume(int)} but without the check on the public stream type.
     * @param streamType The stream type whose minimum volume index is returned.
     * @return The minimum valid volume index for the stream.
     * @see #getStreamVolume(int)
     */
    public int getStreamMinVolumeInt(int streamType) {
        final IAudioService service = getService();
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
        final IAudioService service = getService();
        try {
            return service.getStreamVolume(streamType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // keep in sync with frameworks/av/services/audiopolicy/common/include/Volume.h
    private static final float VOLUME_MIN_DB = -758.0f;

    /** @hide */
    @IntDef(flag = false, prefix = "STREAM", value = {
            STREAM_VOICE_CALL,
            STREAM_SYSTEM,
            STREAM_RING,
            STREAM_MUSIC,
            STREAM_ALARM,
            STREAM_NOTIFICATION,
            STREAM_DTMF,
            STREAM_ACCESSIBILITY }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface PublicStreamTypes {}

    /**
     * Returns the volume in dB (decibel) for the given stream type at the given volume index, on
     * the given type of audio output device.
     * @param streamType stream type for which the volume is queried.
     * @param index the volume index for which the volume is queried. The index value must be
     *     between the minimum and maximum index values for the given stream type (see
     *     {@link #getStreamMinVolume(int)} and {@link #getStreamMaxVolume(int)}).
     * @param deviceType the type of audio output device for which volume is queried.
     * @return a volume expressed in dB.
     *     A negative value indicates the audio signal is attenuated. A typical maximum value
     *     at the maximum volume index is 0 dB (no attenuation nor amplification). Muting is
     *     reflected by a value of {@link Float#NEGATIVE_INFINITY}.
     */
    public float getStreamVolumeDb(@PublicStreamTypes int streamType, int index,
            @AudioDeviceInfo.AudioDeviceTypeOut int deviceType) {
        if (!isPublicStreamType(streamType)) {
            throw new IllegalArgumentException("Invalid stream type " + streamType);
        }
        if (index > getStreamMaxVolume(streamType) || index < getStreamMinVolume(streamType)) {
            throw new IllegalArgumentException("Invalid stream volume index " + index);
        }
        if (!AudioDeviceInfo.isValidAudioDeviceTypeOut(deviceType)) {
            throw new IllegalArgumentException("Invalid audio output device type " + deviceType);
        }
        final float gain = AudioSystem.getStreamVolumeDB(streamType, index,
                AudioDeviceInfo.convertDeviceTypeToInternalDevice(deviceType));
        if (gain <= VOLUME_MIN_DB) {
            return Float.NEGATIVE_INFINITY;
        } else {
            return gain;
        }
    }

    private static boolean isPublicStreamType(int streamType) {
        switch (streamType) {
            case STREAM_VOICE_CALL:
            case STREAM_SYSTEM:
            case STREAM_RING:
            case STREAM_MUSIC:
            case STREAM_ALARM:
            case STREAM_NOTIFICATION:
            case STREAM_DTMF:
            case STREAM_ACCESSIBILITY:
                return true;
            default:
                return false;
        }
    }

    /**
     * Get last audible volume before stream was muted.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getLastAudibleStreamVolume(int streamType) {
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
     * @throws SecurityException if the volume change triggers a Do Not Disturb change
     *   and the caller is not granted notification policy access.
     */
    public void setStreamVolume(int streamType, int index, int flags) {
        final IAudioService service = getService();
        try {
            service.setStreamVolume(streamType, index, flags, getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the volume index for a particular {@link AudioAttributes}.
     * @param attr The {@link AudioAttributes} whose volume index should be set.
     * @param index The volume index to set. See
     *          {@link #getMaxVolumeIndexForAttributes(AudioAttributes)} for the largest valid value
     *          {@link #getMinVolumeIndexForAttributes(AudioAttributes)} for the lowest valid value.
     * @param flags One or more flags.
     * @see #getMaxVolumeIndexForAttributes(AudioAttributes)
     * @see #getMinVolumeIndexForAttributes(AudioAttributes)
     * @see #isVolumeFixed()
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setVolumeIndexForAttributes(@NonNull AudioAttributes attr, int index, int flags) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        try {
            service.setVolumeIndexForAttributes(attr, index, flags,
                                                getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current volume index for a particular {@link AudioAttributes}.
     *
     * @param attr The {@link AudioAttributes} whose volume index is returned.
     * @return The current volume index for the stream.
     * @see #getMaxVolumeIndexForAttributes(AudioAttributes)
     * @see #getMinVolumeIndexForAttributes(AudioAttributes)
     * @see #setVolumeForAttributes(AudioAttributes, int, int)
     * @hide
     */
    @SystemApi
    @IntRange(from = 0)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int getVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        try {
            return service.getVolumeIndexForAttributes(attr);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the maximum volume index for a particular {@link AudioAttributes}.
     *
     * @param attr The {@link AudioAttributes} whose maximum volume index is returned.
     * @return The maximum valid volume index for the {@link AudioAttributes}.
     * @see #getVolumeIndexForAttributes(AudioAttributes)
     * @hide
     */
    @SystemApi
    @IntRange(from = 0)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int getMaxVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        try {
            return service.getMaxVolumeIndexForAttributes(attr);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the minimum volume index for a particular {@link AudioAttributes}.
     *
     * @param attr The {@link AudioAttributes} whose minimum volume index is returned.
     * @return The minimum valid volume index for the {@link AudioAttributes}.
     * @see #getVolumeIndexForAttributes(AudioAttributes)
     * @hide
     */
    @SystemApi
    @IntRange(from = 0)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int getMinVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        try {
            return service.getMinVolumeIndexForAttributes(attr);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the system usages to be supported on this device.
     * @param systemUsages array of system usages to support {@link AttributeSystemUsage}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setSupportedSystemUsages(@NonNull @AttributeSystemUsage int[] systemUsages) {
        Objects.requireNonNull(systemUsages, "systemUsages must not be null");
        final IAudioService service = getService();
        try {
            service.setSupportedSystemUsages(systemUsages);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the system usages supported on this device.
     * @return array of supported system usages {@link AttributeSystemUsage}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @NonNull @AttributeSystemUsage int[] getSupportedSystemUsages() {
        final IAudioService service = getService();
        try {
            return service.getSupportedSystemUsages();
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
        final IAudioService service = getService();
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
    @UnsupportedAppUsage
    public boolean isMasterMute() {
        final IAudioService service = getService();
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    @UnsupportedAppUsage
    public void forceVolumeControlStream(int streamType) {
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
        try {
            return service.isSpeakerphoneOn();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
     }

    /**
     * Specifies whether the audio played by this app may or may not be captured by other apps or
     * the system.
     *
     * The default is {@link AudioAttributes#ALLOW_CAPTURE_BY_ALL}.
     *
     * There are multiple ways to set this policy:
     * <ul>
     * <li> for each track independently, see
     *    {@link AudioAttributes.Builder#setAllowedCapturePolicy(int)} </li>
     * <li> application-wide at runtime, with this method </li>
     * <li> application-wide at build time, see {@code allowAudioPlaybackCapture} in the application
     *       manifest. </li>
     * </ul>
     * The most restrictive policy is always applied.
     *
     * See {@link AudioPlaybackCaptureConfiguration} for more details on
     * which audio signals can be captured.
     *
     * @param capturePolicy one of
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_ALL},
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_SYSTEM},
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_NONE}.
     * @throws RuntimeException if the argument is not a valid value.
     */
    public void setAllowedCapturePolicy(@AudioAttributes.CapturePolicy int capturePolicy) {
        // TODO: also pass the package in case multiple packages have the same UID
        final IAudioService service = getService();
        try {
            int result = service.setAllowedCapturePolicy(capturePolicy);
            if (result != AudioSystem.AUDIO_STATUS_OK) {
                Log.e(TAG, "Could not setAllowedCapturePolicy: " + result);
                return;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the capture policy.
     * @return the capture policy set by {@link #setAllowedCapturePolicy(int)} or
     *         the default if it was not called.
     */
    @AudioAttributes.CapturePolicy
    public int getAllowedCapturePolicy() {
        int result = AudioAttributes.ALLOW_CAPTURE_BY_ALL;
        try {
            result = getService().getAllowedCapturePolicy();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query allowed capture policy: " + e);
        }
        return result;
    }

    //====================================================================
    // Audio Product Strategy routing

    /**
     * @hide
     * Set the preferred device for a given strategy, i.e. the audio routing to be used by
     * this audio strategy. Note that the device may not be available at the time the preferred
     * device is set, but it will be used once made available.
     * <p>Use {@link #removePreferredDeviceForStrategy(AudioProductStrategy)} to cancel setting
     * this preference for this strategy.</p>
     * @param strategy the audio strategy whose routing will be affected
     * @param device the audio device to route to when available
     * @return true if the operation was successful, false otherwise
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean setPreferredDeviceForStrategy(@NonNull AudioProductStrategy strategy,
            @NonNull AudioDeviceAttributes device) {
        Objects.requireNonNull(strategy);
        Objects.requireNonNull(device);
        try {
            final int status =
                    getService().setPreferredDeviceForStrategy(strategy.getId(), device);
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Removes the preferred audio device previously set with
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)}.
     * @param strategy the audio strategy whose routing will be affected
     * @return true if the operation was successful, false otherwise (invalid strategy, or no
     *     device set for example)
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean removePreferredDeviceForStrategy(@NonNull AudioProductStrategy strategy) {
        Objects.requireNonNull(strategy);
        try {
            final int status =
                    getService().removePreferredDeviceForStrategy(strategy.getId());
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return the preferred device for an audio strategy, previously set with
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)}
     * @param strategy the strategy to query
     * @return the preferred device for that strategy, or null if none was ever set or if the
     *    strategy is invalid
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @Nullable AudioDeviceAttributes getPreferredDeviceForStrategy(
            @NonNull AudioProductStrategy strategy) {
        Objects.requireNonNull(strategy);
        try {
            return getService().getPreferredDeviceForStrategy(strategy.getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Interface to be notified of changes in the preferred audio device set for a given audio
     * strategy.
     * <p>Note that this listener will only be invoked whenever
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)} or
     * {@link #removePreferredDeviceForStrategy(AudioProductStrategy)} causes a change in
     * preferred device. It will not be invoked directly after registration with
     * {@link #addOnPreferredDeviceForStrategyChangedListener(Executor, OnPreferredDeviceForStrategyChangedListener)}
     * to indicate which strategies had preferred devices at the time of registration.</p>
     * @see #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)
     * @see #removePreferredDeviceForStrategy(AudioProductStrategy)
     * @see #getPreferredDeviceForStrategy(AudioProductStrategy)
     */
    @SystemApi
    public interface OnPreferredDeviceForStrategyChangedListener {
        /**
         * Called on the listener to indicate that the preferred audio device for the given
         * strategy has changed.
         * @param strategy the {@link AudioProductStrategy} whose preferred device changed
         * @param device <code>null</code> if the preferred device was removed, or the newly set
         *              preferred audio device
         */
        void onPreferredDeviceForStrategyChanged(@NonNull AudioProductStrategy strategy,
                @Nullable AudioDeviceAttributes device);
    }

    /**
     * @hide
     * Adds a listener for being notified of changes to the strategy-preferred audio device.
     * @param executor
     * @param listener
     * @throws SecurityException if the caller doesn't hold the required permission
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void addOnPreferredDeviceForStrategyChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPreferredDeviceForStrategyChangedListener listener)
            throws SecurityException {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        synchronized (mPrefDevListenerLock) {
            if (hasPrefDevListener(listener)) {
                throw new IllegalArgumentException(
                        "attempt to call addOnPreferredDeviceForStrategyChangedListener() "
                                + "on a previously registered listener");
            }
            // lazy initialization of the list of strategy-preferred device listener
            if (mPrefDevListeners == null) {
                mPrefDevListeners = new ArrayList<>();
            }
            final int oldCbCount = mPrefDevListeners.size();
            mPrefDevListeners.add(new PrefDevListenerInfo(listener, executor));
            if (oldCbCount == 0 && mPrefDevListeners.size() > 0) {
                // register binder for callbacks
                if (mPrefDevDispatcherStub == null) {
                    mPrefDevDispatcherStub = new StrategyPreferredDeviceDispatcherStub();
                }
                try {
                    getService().registerStrategyPreferredDeviceDispatcher(mPrefDevDispatcherStub);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * @hide
     * Removes a previously added listener of changes to the strategy-preferred audio device.
     * @param listener
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void removeOnPreferredDeviceForStrategyChangedListener(
            @NonNull OnPreferredDeviceForStrategyChangedListener listener) {
        Objects.requireNonNull(listener);
        synchronized (mPrefDevListenerLock) {
            if (!removePrefDevListener(listener)) {
                throw new IllegalArgumentException(
                        "attempt to call removeOnPreferredDeviceForStrategyChangedListener() "
                                + "on an unregistered listener");
            }
            if (mPrefDevListeners.size() == 0) {
                // unregister binder for callbacks
                try {
                    getService().unregisterStrategyPreferredDeviceDispatcher(
                            mPrefDevDispatcherStub);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } finally {
                    mPrefDevDispatcherStub = null;
                    mPrefDevListeners = null;
                }
            }
        }
    }


    private final Object mPrefDevListenerLock = new Object();
    /**
     * List of listeners for preferred device for strategy and their associated Executor.
     * List is lazy-initialized on first registration
     */
    @GuardedBy("mPrefDevListenerLock")
    private @Nullable ArrayList<PrefDevListenerInfo> mPrefDevListeners;

    private static class PrefDevListenerInfo {
        final @NonNull OnPreferredDeviceForStrategyChangedListener mListener;
        final @NonNull Executor mExecutor;
        PrefDevListenerInfo(OnPreferredDeviceForStrategyChangedListener listener, Executor exe) {
            mListener = listener;
            mExecutor = exe;
        }
    }

    @GuardedBy("mPrefDevListenerLock")
    private StrategyPreferredDeviceDispatcherStub mPrefDevDispatcherStub;

    private final class StrategyPreferredDeviceDispatcherStub
            extends IStrategyPreferredDeviceDispatcher.Stub {

        @Override
        public void dispatchPrefDeviceChanged(int strategyId,
                                              @Nullable AudioDeviceAttributes device) {
            // make a shallow copy of listeners so callback is not executed under lock
            final ArrayList<PrefDevListenerInfo> prefDevListeners;
            synchronized (mPrefDevListenerLock) {
                if (mPrefDevListeners == null || mPrefDevListeners.size() == 0) {
                    return;
                }
                prefDevListeners = (ArrayList<PrefDevListenerInfo>) mPrefDevListeners.clone();
            }
            final AudioProductStrategy strategy =
                    AudioProductStrategy.getAudioProductStrategyWithId(strategyId);
            final long ident = Binder.clearCallingIdentity();
            try {
                for (PrefDevListenerInfo info : prefDevListeners) {
                    info.mExecutor.execute(() ->
                            info.mListener.onPreferredDeviceForStrategyChanged(strategy, device));
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("mPrefDevListenerLock")
    private @Nullable PrefDevListenerInfo getPrefDevListenerInfo(
            OnPreferredDeviceForStrategyChangedListener listener) {
        if (mPrefDevListeners == null) {
            return null;
        }
        for (PrefDevListenerInfo info : mPrefDevListeners) {
            if (info.mListener == listener) {
                return info;
            }
        }
        return null;
    }

    @GuardedBy("mPrefDevListenerLock")
    private boolean hasPrefDevListener(OnPreferredDeviceForStrategyChangedListener listener) {
        return getPrefDevListenerInfo(listener) != null;
    }

    @GuardedBy("mPrefDevListenerLock")
    /**
     * @return true if the listener was removed from the list
     */
    private boolean removePrefDevListener(OnPreferredDeviceForStrategyChangedListener listener) {
        final PrefDevListenerInfo infoToRemove = getPrefDevListenerInfo(listener);
        if (infoToRemove != null) {
            mPrefDevListeners.remove(infoToRemove);
            return true;
        }
        return false;
    }

    //====================================================================
    // Offload query
    /**
     * Returns whether offloaded playback of an audio format is supported on the device.
     * <p>Offloaded playback is the feature where the decoding and playback of an audio stream
     * is not competing with other software resources. In general, it is supported by dedicated
     * hardware, such as audio DSPs.
     * <p>Note that this query only provides information about the support of an audio format,
     * it does not indicate whether the resources necessary for the offloaded playback are
     * available at that instant.
     * @param format the audio format (codec, sample rate, channels) being checked.
     * @param attributes the {@link AudioAttributes} to be used for playback
     * @return true if the given audio format can be offloaded.
     */
    public static boolean isOffloadedPlaybackSupported(@NonNull AudioFormat format,
            @NonNull AudioAttributes attributes) {
        if (format == null) {
            throw new NullPointerException("Illegal null AudioFormat");
        }
        if (attributes == null) {
            throw new NullPointerException("Illegal null AudioAttributes");
        }
        return AudioSystem.isOffloadSupported(format, attributes);
    }

    //====================================================================
    // Bluetooth SCO control
    /**
     * Sticky broadcast intent action indicating that the Bluetooth SCO audio
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
     * Sticky broadcast intent action indicating that the Bluetooth SCO audio
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
        final IAudioService service = getService();
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
    @UnsupportedAppUsage
    public void startBluetoothScoVirtualCall() {
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
     * Checks whether a Bluetooth A2DP audio peripheral is connected or not.
     *
     * @return true if a Bluetooth A2DP peripheral is connected
     *         false if otherwise
     * @deprecated Use {@link AudioManager#getDevices(int)} instead to list available audio devices.
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
     * @deprecated Use {@link AudioManager#getDevices(int)} instead to list available audio devices.
     */
    public boolean isWiredHeadsetOn() {
        if (AudioSystem.getDeviceConnectionState(DEVICE_OUT_WIRED_HEADSET,"")
                == AudioSystem.DEVICE_STATE_UNAVAILABLE &&
            AudioSystem.getDeviceConnectionState(DEVICE_OUT_WIRED_HEADPHONE,"")
                == AudioSystem.DEVICE_STATE_UNAVAILABLE &&
            AudioSystem.getDeviceConnectionState(DEVICE_OUT_USB_HEADSET, "")
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
        final IAudioService service = getService();
        try {
            service.setMicrophoneMute(on, getContext().getOpPackageName(),
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Sets the microphone from switch mute on or off.
     * <p>
     * This method should only be used by InputManager to notify
     * Audio Subsystem about Microphone Mute switch state.
     *
     * @param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     */
    @UnsupportedAppUsage
    public void setMicrophoneMuteFromSwitch(boolean on) {
        final IAudioService service = getService();
        try {
            service.setMicrophoneMuteFromSwitch(on);
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
        final IAudioService service = getService();
        try {
            return service.isMicrophoneMuted();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Broadcast Action: microphone muting state changed.
     *
     * You <em>cannot</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     *
     * <p>The intent has no extra values, use {@link #isMicrophoneMute} to check whether the
     * microphone is muted.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_MICROPHONE_MUTE_CHANGED =
            "android.media.action.MICROPHONE_MUTE_CHANGED";

    /**
     * Broadcast Action: speakerphone state changed.
     *
     * You <em>cannot</em> receive this through components declared
     * in manifests, only by explicitly registering for it with
     * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     *
     * <p>The intent has no extra values, use {@link #isSpeakerphoneOn} to check whether the
     * speakerphone functionality is enabled or not.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SPEAKERPHONE_STATE_CHANGED =
            "android.media.action.SPEAKERPHONE_STATE_CHANGED";

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
     * @param mode  the requested audio mode.
     *              Informs the HAL about the current audio state so that
     *              it can route the audio appropriately.
     */
    public void setMode(@AudioMode int mode) {
        final IAudioService service = getService();
        try {
            service.setMode(mode, mICallBack, mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current audio mode.
     *
     * @return      the current audio mode.
     */
    @AudioMode
    public int getMode() {
        final IAudioService service = getService();
        try {
            int mode = service.getMode();
            int sdk;
            try {
                sdk = getContext().getApplicationInfo().targetSdkVersion;
            } catch (NullPointerException e) {
                // some tests don't have a Context
                sdk = Build.VERSION.SDK_INT;
            }
            if (mode == MODE_CALL_SCREENING && sdk <= Build.VERSION_CODES.Q) {
                mode = MODE_IN_CALL;
            }
            return mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
    * Indicates if the platform supports a special call screening and call monitoring mode.
    * <p>
    * When this mode is supported, it is possible to perform call screening and monitoring
    * functions while other use cases like music or movie playback are active.
    * <p>
    * Use {@link #setMode(int)} with mode {@link #MODE_CALL_SCREENING} to place the platform in
    * call screening mode.
    * <p>
    * If call screening mode is not supported, setting mode to
    * MODE_CALL_SCREENING will be ignored and will not change current mode reported by
    *  {@link #getMode()}.
    * @return true if call screening mode is supported, false otherwise.
    */
    public boolean isCallScreeningModeSupported() {
        final IAudioService service = getService();
        try {
            return service.isCallScreeningModeSupported();
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
    /**
     * Call screening in progress. Call is connected and audio is accessible to call
     * screening applications but other audio use cases are still possible.
     */
    public static final int MODE_CALL_SCREENING     = AudioSystem.MODE_CALL_SCREENING;

    /** @hide */
    @IntDef(flag = false, prefix = "MODE_", value = {
            MODE_NORMAL,
            MODE_RINGTONE,
            MODE_IN_CALL,
            MODE_IN_COMMUNICATION,
            MODE_CALL_SCREENING }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioMode {}

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
    @UnsupportedAppUsage
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
        final IAudioService service = getService();
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
    @UnsupportedAppUsage
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

        final IAudioService service = getService();
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

        final IAudioService service = getService();
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

        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
        final IAudioService service = getService();
        try {
            service.unloadSoundEffects();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Used to indicate no audio focus has been gained or lost, or requested.
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
     * Internal class to hold the AudioFocusRequest as well as the Handler for the callback
     */
    private static class FocusRequestInfo {
        @NonNull  final AudioFocusRequest mRequest;
        @Nullable final Handler mHandler;
        FocusRequestInfo(@NonNull AudioFocusRequest afr, @Nullable Handler handler) {
            mRequest = afr;
            mHandler = handler;
        }
    }

    /**
     * Map to convert focus event listener IDs, as used in the AudioService audio focus stack,
     * to actual listener objects.
     */
    @UnsupportedAppUsage
    private final ConcurrentHashMap<String, FocusRequestInfo> mAudioFocusIdListenerMap =
            new ConcurrentHashMap<String, FocusRequestInfo>();

    private FocusRequestInfo findFocusRequestInfo(String id) {
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
    private final static int MSSG_PLAYBACK_CONFIG_CHANGE = 2;

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
                            case MSSG_FOCUS_CHANGE: {
                                final FocusRequestInfo fri = findFocusRequestInfo((String)msg.obj);
                                if (fri != null)  {
                                    final OnAudioFocusChangeListener listener =
                                            fri.mRequest.getOnAudioFocusChangeListener();
                                    if (listener != null) {
                                        Log.d(TAG, "dispatching onAudioFocusChange("
                                                + msg.arg1 + ") to " + msg.obj);
                                        listener.onAudioFocusChange(msg.arg1);
                                    }
                                }
                            } break;
                            case MSSG_RECORDING_CONFIG_CHANGE: {
                                final RecordConfigChangeCallbackData cbData =
                                        (RecordConfigChangeCallbackData) msg.obj;
                                if (cbData.mCb != null) {
                                    cbData.mCb.onRecordingConfigChanged(cbData.mConfigs);
                                }
                            } break;
                            case MSSG_PLAYBACK_CONFIG_CHANGE: {
                                final PlaybackConfigChangeCallbackData cbData =
                                        (PlaybackConfigChangeCallbackData) msg.obj;
                                if (cbData.mCb != null) {
                                    if (DEBUG) {
                                        Log.d(TAG, "dispatching onPlaybackConfigChanged()");
                                    }
                                    cbData.mCb.onPlaybackConfigChanged(cbData.mConfigs);
                                }
                            } break;
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
        @Override
        public void dispatchAudioFocusChange(int focusChange, String id) {
            final FocusRequestInfo fri = findFocusRequestInfo(id);
            if (fri != null)  {
                final OnAudioFocusChangeListener listener =
                        fri.mRequest.getOnAudioFocusChangeListener();
                if (listener != null) {
                    final Handler h = (fri.mHandler == null) ?
                            mServiceEventHandlerDelegate.getHandler() : fri.mHandler;
                    final Message m = h.obtainMessage(
                            MSSG_FOCUS_CHANGE/*what*/, focusChange/*arg1*/, 0/*arg2 ignored*/,
                            id/*obj*/);
                    h.sendMessage(m);
                }
            }
        }

        @Override
        public void dispatchFocusResultFromExtPolicy(int requestResult, String clientId) {
            synchronized (mFocusRequestsLock) {
                // TODO use generation counter as the key instead
                final BlockingFocusResultReceiver focusReceiver =
                        mFocusRequestsAwaitingResult.remove(clientId);
                if (focusReceiver != null) {
                    focusReceiver.notifyResult(requestResult);
                } else {
                    Log.e(TAG, "dispatchFocusResultFromExtPolicy found no result receiver");
                }
            }
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
     * Registers a listener to be called when audio focus changes and keeps track of the associated
     * focus request (including Handler to use for the listener).
     * @param afr the full request parameters
     */
    public void registerAudioFocusRequest(@NonNull AudioFocusRequest afr) {
        final Handler h = afr.getOnAudioFocusChangeListenerHandler();
        final FocusRequestInfo fri = new FocusRequestInfo(afr, (h == null) ? null :
            new ServiceEventHandlerDelegate(h).getHandler());
        final String key = getIdForAudioFocusListener(afr.getOnAudioFocusChangeListener());
        mAudioFocusIdListenerMap.put(key, fri);
    }

    /**
     * @hide
     * Causes the specified listener to not be called anymore when focus is gained or lost.
     * @param l the listener to unregister.
     */
    public void unregisterAudioFocusRequest(OnAudioFocusChangeListener l) {
        // remove locally
        mAudioFocusIdListenerMap.remove(getIdForAudioFocusListener(l));
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
      * A focus change request whose granting is delayed: the request was successful, but the
      * requester will only be granted audio focus once the condition that prevented immediate
      * granting has ended.
      * See {@link #requestAudioFocus(AudioFocusRequest)} and
      * {@link AudioFocusRequest.Builder#setAcceptsDelayedFocusGain(boolean)}
      */
    public static final int AUDIOFOCUS_REQUEST_DELAYED = 2;

    /** @hide */
    @IntDef(flag = false, prefix = "AUDIOFOCUS_REQUEST", value = {
            AUDIOFOCUS_REQUEST_FAILED,
            AUDIOFOCUS_REQUEST_GRANTED,
            AUDIOFOCUS_REQUEST_DELAYED }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface FocusRequestResult {}

    /**
     * @hide
     * code returned when a synchronous focus request on the client-side is to be blocked
     * until the external audio focus policy decides on the response for the client
     */
    public static final int AUDIOFOCUS_REQUEST_WAITING_FOR_EXT_POLICY = 100;

    /**
     * Timeout duration in ms when waiting on an external focus policy for the result for a
     * focus request
     */
    private static final int EXT_FOCUS_POLICY_TIMEOUT_MS = 200;

    private static final String FOCUS_CLIENT_ID_STRING = "android_audio_focus_client_id";

    private final Object mFocusRequestsLock = new Object();
    /**
     * Map of all receivers of focus request results, one per unresolved focus request.
     * Receivers are added before sending the request to the external focus policy,
     * and are removed either after receiving the result, or after the timeout.
     * This variable is lazily initialized.
     */
    @GuardedBy("mFocusRequestsLock")
    private HashMap<String, BlockingFocusResultReceiver> mFocusRequestsAwaitingResult;


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
     *  @deprecated use {@link #requestAudioFocus(AudioFocusRequest)}
     */
    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) {
        PlayerBase.deprecateStreamTypeForPlayback(streamType,
                "AudioManager", "requestAudioFocus()");
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
     * Request audio focus.
     * See the {@link AudioFocusRequest} for information about the options available to configure
     * your request, and notification of focus gain and loss.
     * @param focusRequest a {@link AudioFocusRequest} instance used to configure how focus is
     *   requested.
     * @return {@link #AUDIOFOCUS_REQUEST_FAILED}, {@link #AUDIOFOCUS_REQUEST_GRANTED}
     *     or {@link #AUDIOFOCUS_REQUEST_DELAYED}.
     *     <br>Note that the return value is never {@link #AUDIOFOCUS_REQUEST_DELAYED} when focus
     *     is requested without building the {@link AudioFocusRequest} with
     *     {@link AudioFocusRequest.Builder#setAcceptsDelayedFocusGain(boolean)} set to
     *     {@code true}.
     * @throws NullPointerException if passed a null argument
     */
    public int requestAudioFocus(@NonNull AudioFocusRequest focusRequest) {
        return requestAudioFocus(focusRequest, null /* no AudioPolicy*/);
    }

    /**
     *  Abandon audio focus. Causes the previous focus owner, if any, to receive focus.
     *  @param focusRequest the {@link AudioFocusRequest} that was used when requesting focus
     *      with {@link #requestAudioFocus(AudioFocusRequest)}.
     *  @return {@link #AUDIOFOCUS_REQUEST_FAILED} or {@link #AUDIOFOCUS_REQUEST_GRANTED}
     *  @throws IllegalArgumentException if passed a null argument
     */
    public int abandonAudioFocusRequest(@NonNull AudioFocusRequest focusRequest) {
        if (focusRequest == null) {
            throw new IllegalArgumentException("Illegal null AudioFocusRequest");
        }
        return abandonAudioFocus(focusRequest.getOnAudioFocusChangeListener(),
                focusRequest.getAudioAttributes());
    }

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
     * @param flags 0 or a combination of {link #AUDIOFOCUS_FLAG_DELAY_OK},
     *     {@link #AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS} and {@link #AUDIOFOCUS_FLAG_LOCK}.
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
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
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
     * @deprecated use {@link #requestAudioFocus(AudioFocusRequest, AudioPolicy)}
     */
    @SystemApi
    @RequiresPermission(anyOf= {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            android.Manifest.permission.MODIFY_AUDIO_ROUTING
    })
    public int requestAudioFocus(OnAudioFocusChangeListener l,
            @NonNull AudioAttributes requestAttributes,
            int durationHint,
            int flags,
            AudioPolicy ap) throws IllegalArgumentException {
        // parameter checking
        if (requestAttributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes argument");
        }
        if (!AudioFocusRequest.isValidFocusGain(durationHint)) {
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
        if (((flags & AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS)
                == AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS) && (l == null)) {
            throw new IllegalArgumentException(
                    "Illegal null focus listener when flagged as pausing instead of ducking");
        }
        if (((flags & AUDIOFOCUS_FLAG_LOCK) == AUDIOFOCUS_FLAG_LOCK) && (ap == null)) {
            throw new IllegalArgumentException(
                    "Illegal null audio policy when locking audio focus");
        }

        final AudioFocusRequest afr = new AudioFocusRequest.Builder(durationHint)
                .setOnAudioFocusChangeListenerInt(l, null /* no Handler for this legacy API */)
                .setAudioAttributes(requestAttributes)
                .setAcceptsDelayedFocusGain((flags & AUDIOFOCUS_FLAG_DELAY_OK)
                        == AUDIOFOCUS_FLAG_DELAY_OK)
                .setWillPauseWhenDucked((flags & AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS)
                        == AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS)
                .setLocksFocus((flags & AUDIOFOCUS_FLAG_LOCK) == AUDIOFOCUS_FLAG_LOCK)
                .build();
        return requestAudioFocus(afr, ap);
    }

    /**
     * @hide
     * Request or lock audio focus.
     * This method is to be used by system components that have registered an
     * {@link android.media.audiopolicy.AudioPolicy} to request audio focus, but also to "lock" it
     * so focus granting is temporarily disabled.
     * @param afr see the description of the same parameter in
     *     {@link #requestAudioFocus(AudioFocusRequest)}
     * @param ap a registered {@link android.media.audiopolicy.AudioPolicy} instance when locking
     *     focus, or null.
     * @return {@link #AUDIOFOCUS_REQUEST_FAILED}, {@link #AUDIOFOCUS_REQUEST_GRANTED}
     *     or {@link #AUDIOFOCUS_REQUEST_DELAYED}.
     * @throws NullPointerException if the AudioFocusRequest is null
     * @throws IllegalArgumentException when trying to lock focus without an AudioPolicy
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int requestAudioFocus(@NonNull AudioFocusRequest afr, @Nullable AudioPolicy ap) {
        if (afr == null) {
            throw new NullPointerException("Illegal null AudioFocusRequest");
        }
        // this can only be checked now, not during the creation of the AudioFocusRequest instance
        if (afr.locksFocus() && ap == null) {
            throw new IllegalArgumentException(
                    "Illegal null audio policy when locking audio focus");
        }
        registerAudioFocusRequest(afr);
        final IAudioService service = getService();
        final int status;
        int sdk;
        try {
            sdk = getContext().getApplicationInfo().targetSdkVersion;
        } catch (NullPointerException e) {
            // some tests don't have a Context
            sdk = Build.VERSION.SDK_INT;
        }

        final String clientId = getIdForAudioFocusListener(afr.getOnAudioFocusChangeListener());
        final BlockingFocusResultReceiver focusReceiver;
        synchronized (mFocusRequestsLock) {
            try {
                // TODO status contains result and generation counter for ext policy
                status = service.requestAudioFocus(afr.getAudioAttributes(),
                        afr.getFocusGain(), mICallBack,
                        mAudioFocusDispatcher,
                        clientId,
                        getContext().getOpPackageName() /* package name */, afr.getFlags(),
                        ap != null ? ap.cb() : null,
                        sdk);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (status != AudioManager.AUDIOFOCUS_REQUEST_WAITING_FOR_EXT_POLICY) {
                // default path with no external focus policy
                return status;
            }
            if (mFocusRequestsAwaitingResult == null) {
                mFocusRequestsAwaitingResult =
                        new HashMap<String, BlockingFocusResultReceiver>(1);
            }
            focusReceiver = new BlockingFocusResultReceiver(clientId);
            mFocusRequestsAwaitingResult.put(clientId, focusReceiver);
        }
        focusReceiver.waitForResult(EXT_FOCUS_POLICY_TIMEOUT_MS);
        if (DEBUG && !focusReceiver.receivedResult()) {
            Log.e(TAG, "requestAudio response from ext policy timed out, denying request");
        }
        synchronized (mFocusRequestsLock) {
            mFocusRequestsAwaitingResult.remove(clientId);
        }
        return focusReceiver.requestResult();
    }

    // helper class that abstracts out the handling of spurious wakeups in Object.wait()
    private static final class SafeWaitObject {
        private boolean mQuit = false;

        public void safeNotify() {
            synchronized (this) {
                mQuit = true;
                this.notify();
            }
        }

        public void safeWait(long millis) throws InterruptedException {
            final long timeOutTime = java.lang.System.currentTimeMillis() + millis;
            synchronized (this) {
                while (!mQuit) {
                    final long timeToWait = timeOutTime - java.lang.System.currentTimeMillis();
                    if (timeToWait < 0) { break; }
                    this.wait(timeToWait);
                }
            }
        }
    }

    private static final class BlockingFocusResultReceiver {
        private final SafeWaitObject mLock = new SafeWaitObject();
        @GuardedBy("mLock")
        private boolean mResultReceived = false;
        // request denied by default (e.g. timeout)
        private int mFocusRequestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        private final String mFocusClientId;

        BlockingFocusResultReceiver(String clientId) {
            mFocusClientId = clientId;
        }

        boolean receivedResult() { return mResultReceived; }
        int requestResult() { return mFocusRequestResult; }

        void notifyResult(int requestResult) {
            synchronized (mLock) {
                mResultReceived = true;
                mFocusRequestResult = requestResult;
                mLock.safeNotify();
            }
        }

        public void waitForResult(long timeOutMs) {
            synchronized (mLock) {
                if (mResultReceived) {
                    // the result was received before waiting
                    return;
                }
                try {
                    mLock.safeWait(timeOutMs);
                } catch (InterruptedException e) { }
            }
        }
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
    @UnsupportedAppUsage
    public void requestAudioFocusForCall(int streamType, int durationHint) {
        final IAudioService service = getService();
        try {
            service.requestAudioFocus(new AudioAttributes.Builder()
                        .setInternalLegacyStreamType(streamType).build(),
                    durationHint, mICallBack, null,
                    AudioSystem.IN_VOICE_COMM_FOCUS_ID,
                    getContext().getOpPackageName(),
                    AUDIOFOCUS_FLAG_LOCK,
                    null /* policy token */, 0 /* sdk n/a here*/);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return the volume ramping time for a sound to be played after the given focus request,
     *   and to play a sound of the given attributes
     * @param focusGain
     * @param attr
     * @return
     */
    public int getFocusRampTimeMs(int focusGain, AudioAttributes attr) {
        final IAudioService service = getService();
        try {
            return service.getFocusRampTimeMs(focusGain, attr);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Set the result to the audio focus request received through
     * {@link AudioPolicyFocusListener#onAudioFocusRequest(AudioFocusInfo, int)}.
     * @param afi the information about the focus requester
     * @param requestResult the result to the focus request to be passed to the requester
     * @param ap a valid registered {@link AudioPolicy} configured as a focus policy.
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setFocusRequestResult(@NonNull AudioFocusInfo afi,
            @FocusRequestResult int requestResult, @NonNull AudioPolicy ap) {
        if (afi == null) {
            throw new IllegalArgumentException("Illegal null AudioFocusInfo");
        }
        if (ap == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy");
        }
        final IAudioService service = getService();
        try {
            service.setFocusRequestResultFromExtPolicy(afi, requestResult, ap.cb());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Notifies an application with a focus listener of gain or loss of audio focus.
     * This method can only be used by owners of an {@link AudioPolicy} configured with
     * {@link AudioPolicy.Builder#setIsAudioFocusPolicy(boolean)} set to true.
     * @param afi the recipient of the focus change, that has previously requested audio focus, and
     *     that was received by the {@code AudioPolicy} through
     *     {@link AudioPolicy.AudioPolicyFocusListener#onAudioFocusRequest(AudioFocusInfo, int)}.
     * @param focusChange one of focus gain types ({@link #AUDIOFOCUS_GAIN},
     *     {@link #AUDIOFOCUS_GAIN_TRANSIENT}, {@link #AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK} or
     *     {@link #AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE})
     *     or one of the focus loss types ({@link AudioManager#AUDIOFOCUS_LOSS},
     *     {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT},
     *     or {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}).
     *     <br>For the focus gain, the change type should be the same as the app requested.
     * @param ap a valid registered {@link AudioPolicy} configured as a focus policy.
     * @return {@link #AUDIOFOCUS_REQUEST_GRANTED} if the dispatch was successfully sent, or
     *     {@link #AUDIOFOCUS_REQUEST_FAILED} if the focus client didn't have a listener, or
     *     if there was an error sending the request.
     * @throws NullPointerException if the {@link AudioFocusInfo} or {@link AudioPolicy} are null.
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int dispatchAudioFocusChange(@NonNull AudioFocusInfo afi, int focusChange,
            @NonNull AudioPolicy ap) {
        if (afi == null) {
            throw new NullPointerException("Illegal null AudioFocusInfo");
        }
        if (ap == null) {
            throw new NullPointerException("Illegal null AudioPolicy");
        }
        final IAudioService service = getService();
        try {
            return service.dispatchFocusChange(afi, focusChange, ap.cb());
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
    @UnsupportedAppUsage
    public void abandonAudioFocusForCall() {
        final IAudioService service = getService();
        try {
            service.abandonAudioFocus(null, AudioSystem.IN_VOICE_COMM_FOCUS_ID,
                    null /*AudioAttributes, legacy behavior*/, getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     *  Abandon audio focus. Causes the previous focus owner, if any, to receive focus.
     *  @param l the listener with which focus was requested.
     *  @return {@link #AUDIOFOCUS_REQUEST_FAILED} or {@link #AUDIOFOCUS_REQUEST_GRANTED}
     *  @deprecated use {@link #abandonAudioFocusRequest(AudioFocusRequest)}
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
     * @deprecated use {@link #abandonAudioFocusRequest(AudioFocusRequest)}
     */
    @SystemApi
    @SuppressLint("Doclava125") // no permission enforcement, but only "undoes" what would have been
                                // done by a matching requestAudioFocus
    public int abandonAudioFocus(OnAudioFocusChangeListener l, AudioAttributes aa) {
        int status = AUDIOFOCUS_REQUEST_FAILED;
        unregisterAudioFocusRequest(l);
        final IAudioService service = getService();
        try {
            status = service.abandonAudioFocus(mAudioFocusDispatcher,
                    getIdForAudioFocusListener(l), aa, getContext().getOpPackageName());
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
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int registerAudioPolicy(@NonNull AudioPolicy policy) {
        return registerAudioPolicyStatic(policy);
    }

    static int registerAudioPolicyStatic(@NonNull AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        final IAudioService service = getService();
        try {
            MediaProjection projection = policy.getMediaProjection();
            String regId = service.registerAudioPolicy(policy.getConfig(), policy.cb(),
                    policy.hasFocusListener(), policy.isFocusPolicy(), policy.isTestFocusPolicy(),
                    policy.isVolumeController(),
                    projection == null ? null : projection.getProjection());
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
     * Unregisters an {@link AudioPolicy} asynchronously.
     * @param policy the non-null {@link AudioPolicy} to unregister.
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void unregisterAudioPolicyAsync(@NonNull AudioPolicy policy) {
        unregisterAudioPolicyAsyncStatic(policy);
    }

    static void unregisterAudioPolicyAsyncStatic(@NonNull AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        final IAudioService service = getService();
        try {
            service.unregisterAudioPolicyAsync(policy.cb());
            policy.setRegistration(null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Unregisters an {@link AudioPolicy} synchronously.
     * This method also invalidates all {@link AudioRecord} and {@link AudioTrack} objects
     * associated with mixes of this policy.
     * @param policy the non-null {@link AudioPolicy} to unregister.
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void unregisterAudioPolicy(@NonNull AudioPolicy policy) {
        Preconditions.checkNotNull(policy, "Illegal null AudioPolicy argument");
        final IAudioService service = getService();
        try {
            policy.invalidateCaptorsAndInjectors();
            service.unregisterAudioPolicy(policy.cb());
            policy.setRegistration(null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @return true if an AudioPolicy was previously registered
     */
    @TestApi
    public boolean hasRegisteredDynamicPolicy() {
        final IAudioService service = getService();
        try {
            return service.hasRegisteredDynamicPolicy();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    //====================================================================
    // Notification of playback activity & playback configuration
    /**
     * Interface for receiving update notifications about the playback activity on the system.
     * Extend this abstract class and register it with
     * {@link AudioManager#registerAudioPlaybackCallback(AudioPlaybackCallback, Handler)}
     * to be notified.
     * Use {@link AudioManager#getActivePlaybackConfigurations()} to query the current
     * configuration.
     * @see AudioPlaybackConfiguration
     */
    public static abstract class AudioPlaybackCallback {
        /**
         * Called whenever the playback activity and configuration has changed.
         * @param configs list containing the results of
         *      {@link AudioManager#getActivePlaybackConfigurations()}.
         */
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {}
    }

    private static class AudioPlaybackCallbackInfo {
        final AudioPlaybackCallback mCb;
        final Handler mHandler;
        AudioPlaybackCallbackInfo(AudioPlaybackCallback cb, Handler handler) {
            mCb = cb;
            mHandler = handler;
        }
    }

    private final static class PlaybackConfigChangeCallbackData {
        final AudioPlaybackCallback mCb;
        final List<AudioPlaybackConfiguration> mConfigs;

        PlaybackConfigChangeCallbackData(AudioPlaybackCallback cb,
                List<AudioPlaybackConfiguration> configs) {
            mCb = cb;
            mConfigs = configs;
        }
    }

    /**
     * Register a callback to be notified of audio playback changes through
     * {@link AudioPlaybackCallback}
     * @param cb non-null callback to register
     * @param handler the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the {@link Handler} associated with the main
     * {@link Looper} will be used.
     */
    public void registerAudioPlaybackCallback(@NonNull AudioPlaybackCallback cb,
                                              @Nullable Handler handler)
    {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioPlaybackCallback argument");
        }

        synchronized(mPlaybackCallbackLock) {
            // lazy initialization of the list of playback callbacks
            if (mPlaybackCallbackList == null) {
                mPlaybackCallbackList = new ArrayList<AudioPlaybackCallbackInfo>();
            }
            final int oldCbCount = mPlaybackCallbackList.size();
            if (!hasPlaybackCallback_sync(cb)) {
                mPlaybackCallbackList.add(new AudioPlaybackCallbackInfo(cb,
                        new ServiceEventHandlerDelegate(handler).getHandler()));
                final int newCbCount = mPlaybackCallbackList.size();
                if ((oldCbCount == 0) && (newCbCount > 0)) {
                    // register binder for callbacks
                    try {
                        getService().registerPlaybackCallback(mPlayCb);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            } else {
                Log.w(TAG, "attempt to call registerAudioPlaybackCallback() on a previously"
                        + "registered callback");
            }
        }
    }

    /**
     * Unregister an audio playback callback previously registered with
     * {@link #registerAudioPlaybackCallback(AudioPlaybackCallback, Handler)}.
     * @param cb non-null callback to unregister
     */
    public void unregisterAudioPlaybackCallback(@NonNull AudioPlaybackCallback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Illegal null AudioPlaybackCallback argument");
        }
        synchronized(mPlaybackCallbackLock) {
            if (mPlaybackCallbackList == null) {
                Log.w(TAG, "attempt to call unregisterAudioPlaybackCallback() on a callback"
                        + " that was never registered");
                return;
            }
            final int oldCbCount = mPlaybackCallbackList.size();
            if (removePlaybackCallback_sync(cb)) {
                final int newCbCount = mPlaybackCallbackList.size();
                if ((oldCbCount > 0) && (newCbCount == 0)) {
                    // unregister binder for callbacks
                    try {
                        getService().unregisterPlaybackCallback(mPlayCb);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            } else {
                Log.w(TAG, "attempt to call unregisterAudioPlaybackCallback() on a callback"
                        + " already unregistered or never registered");
            }
        }
    }

    /**
     * Returns the current active audio playback configurations of the device
     * @return a non-null list of playback configurations. An empty list indicates there is no
     *     playback active when queried.
     * @see AudioPlaybackConfiguration
     */
    public @NonNull List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
        final IAudioService service = getService();
        try {
            return service.getActivePlaybackConfigurations();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * All operations on this list are sync'd on mPlaybackCallbackLock.
     * List is lazy-initialized in
     * {@link #registerAudioPlaybackCallback(AudioPlaybackCallback, Handler)}.
     * List can be null.
     */
    private List<AudioPlaybackCallbackInfo> mPlaybackCallbackList;
    private final Object mPlaybackCallbackLock = new Object();

    /**
     * Must be called synchronized on mPlaybackCallbackLock
     */
    private boolean hasPlaybackCallback_sync(@NonNull AudioPlaybackCallback cb) {
        if (mPlaybackCallbackList != null) {
            for (int i=0 ; i < mPlaybackCallbackList.size() ; i++) {
                if (cb.equals(mPlaybackCallbackList.get(i).mCb)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Must be called synchronized on mPlaybackCallbackLock
     */
    private boolean removePlaybackCallback_sync(@NonNull AudioPlaybackCallback cb) {
        if (mPlaybackCallbackList != null) {
            for (int i=0 ; i < mPlaybackCallbackList.size() ; i++) {
                if (cb.equals(mPlaybackCallbackList.get(i).mCb)) {
                    mPlaybackCallbackList.remove(i);
                    return true;
                }
            }
        }
        return false;
    }

    private final IPlaybackConfigDispatcher mPlayCb = new IPlaybackConfigDispatcher.Stub() {
        @Override
        public void dispatchPlaybackConfigChange(List<AudioPlaybackConfiguration> configs,
                boolean flush) {
            if (flush) {
                Binder.flushPendingCommands();
            }
            synchronized(mPlaybackCallbackLock) {
                if (mPlaybackCallbackList != null) {
                    for (int i=0 ; i < mPlaybackCallbackList.size() ; i++) {
                        final AudioPlaybackCallbackInfo arci = mPlaybackCallbackList.get(i);
                        if (arci.mHandler != null) {
                            final Message m = arci.mHandler.obtainMessage(
                                    MSSG_PLAYBACK_CONFIG_CHANGE/*what*/,
                                    new PlaybackConfigChangeCallbackData(arci.mCb, configs)/*obj*/);
                            arci.mHandler.sendMessage(m);
                        }
                    }
                }
            }
        }

    };

    //====================================================================
    // Notification of recording activity & recording configuration
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
    public void registerAudioRecordingCallback(@NonNull AudioRecordingCallback cb,
                                               @Nullable Handler handler)
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
    public static final int RECORD_CONFIG_EVENT_NONE = -1;
    /** @hide */
    public static final int RECORD_CONFIG_EVENT_START = 0;
    /** @hide */
    public static final int RECORD_CONFIG_EVENT_STOP = 1;
    /** @hide */
    public static final int RECORD_CONFIG_EVENT_UPDATE = 2;
    /** @hide */
    public static final int RECORD_CONFIG_EVENT_RELEASE = 3;
    /**
     * keep in sync with frameworks/native/include/audiomanager/AudioManager.h
     */
    /** @hide */
    public static final int RECORD_RIID_INVALID = -1;
    /** @hide */
    public static final int RECORDER_STATE_STARTED = 0;
    /** @hide */
    public static final int RECORDER_STATE_STOPPED = 1;

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
        @Override
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
    @UnsupportedAppUsage
    public void reloadAudioSettings() {
        final IAudioService service = getService();
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
        final IAudioService service = getService();
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_EARPIECE = AudioSystem.DEVICE_OUT_EARPIECE;
    /** @hide
     *  The audio output device code for the built-in speaker */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_SPEAKER = AudioSystem.DEVICE_OUT_SPEAKER;
    /** @hide
     * The audio output device code for a wired headset with attached microphone */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_WIRED_HEADSET = AudioSystem.DEVICE_OUT_WIRED_HEADSET;
    /** @hide
     * The audio output device code for a wired headphone without attached microphone */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_WIRED_HEADPHONE = AudioSystem.DEVICE_OUT_WIRED_HEADPHONE;
    /** @hide
     * The audio output device code for a USB headphone with attached microphone */
    public static final int DEVICE_OUT_USB_HEADSET = AudioSystem.DEVICE_OUT_USB_HEADSET;
    /** @hide
     * The audio output device code for generic Bluetooth SCO, for voice */
    public static final int DEVICE_OUT_BLUETOOTH_SCO = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO;
    /** @hide
     * The audio output device code for Bluetooth SCO Headset Profile (HSP) and
     * Hands-Free Profile (HFP), for voice
     */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_SCO_HEADSET =
            AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET;
    /** @hide
     * The audio output device code for Bluetooth SCO car audio, for voice */
    public static final int DEVICE_OUT_BLUETOOTH_SCO_CARKIT =
            AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT;
    /** @hide
     * The audio output device code for generic Bluetooth A2DP, for music */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_A2DP = AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP;
    /** @hide
     * The audio output device code for Bluetooth A2DP headphones, for music */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES =
            AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES;
    /** @hide
     * The audio output device code for Bluetooth A2DP external speaker, for music */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER =
            AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER;
    /** @hide
     * The audio output device code for S/PDIF (legacy) or HDMI
     * Deprecated: replaced by {@link #DEVICE_OUT_HDMI} */
    public static final int DEVICE_OUT_AUX_DIGITAL = AudioSystem.DEVICE_OUT_AUX_DIGITAL;
    /** @hide
     * The audio output device code for HDMI */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_HDMI = AudioSystem.DEVICE_OUT_HDMI;
    /** @hide
     * The audio output device code for an analog wired headset attached via a
     *  docking station
     */
    @UnsupportedAppUsage
    public static final int DEVICE_OUT_ANLG_DOCK_HEADSET = AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET;
    /** @hide
     * The audio output device code for a digital wired headset attached via a
     *  docking station
     */
    @UnsupportedAppUsage
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
     * The audio input device code for HDMI ARC
     */
    public static final int DEVICE_IN_HDMI_ARC =
                                    AudioSystem.DEVICE_IN_HDMI_ARC;

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
     *            {@link #STREAM_DTMF},
     *            {@link #STREAM_ACCESSIBILITY}.
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
    @UnsupportedAppUsage
    public int getDevicesForStream(int streamType) {
        switch (streamType) {
        case STREAM_VOICE_CALL:
        case STREAM_SYSTEM:
        case STREAM_RING:
        case STREAM_MUSIC:
        case STREAM_ALARM:
        case STREAM_NOTIFICATION:
        case STREAM_DTMF:
        case STREAM_ACCESSIBILITY:
            return AudioSystem.getDevicesForStream(streamType);
        default:
            return 0;
        }
    }

    /**
     * @hide
     * Get the audio devices that would be used for the routing of the given audio attributes.
     * @param attributes the {@link AudioAttributes} for which the routing is being queried
     * @return an empty list if there was an issue with the request, a list of audio devices
     *   otherwise (typically one device, except for duplicated paths).
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @NonNull List<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes) {
        Objects.requireNonNull(attributes);
        final IAudioService service = getService();
        try {
            return service.getDevicesForAttributes(attributes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

     /**
     * Indicate wired accessory connection state change.
     * @param device type of device connected/disconnected (AudioManager.DEVICE_OUT_xxx)
     * @param state  new connection state: 1 connected, 0 disconnected
     * @param name   device name
     * {@hide}
     */
    @UnsupportedAppUsage
    public void setWiredDeviceConnectionState(int type, int state, String address, String name) {
        final IAudioService service = getService();
        try {
            service.setWiredDeviceConnectionState(type, state, address, name,
                    mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

     /**
     * Indicate Hearing Aid connection state change and eventually suppress
     * the {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY} intent.
     * This operation is asynchronous but its execution will still be sequentially scheduled
     * relative to calls to {@link #setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(
     * * BluetoothDevice, int, int, boolean, int)} and
     * and {@link #handleBluetoothA2dpDeviceConfigChange(BluetoothDevice)}.
     * @param device Bluetooth device connected/disconnected
     * @param state new connection state (BluetoothProfile.STATE_xxx)
     * @param musicDevice Default get system volume for the connecting device.
     * (either {@link android.bluetooth.BluetoothProfile.hearingaid} or
     * {@link android.bluetooth.BluetoothProfile.HEARING_AID})
     * @param suppressNoisyIntent if true the
     * {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY} intent will not be sent.
     * {@hide}
     */
    public void setBluetoothHearingAidDeviceConnectionState(
                BluetoothDevice device, int state, boolean suppressNoisyIntent,
                int musicDevice) {
        final IAudioService service = getService();
        try {
            service.setBluetoothHearingAidDeviceConnectionState(device,
                state, suppressNoisyIntent, musicDevice);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

     /**
     * Indicate A2DP source or sink connection state change and eventually suppress
     * the {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY} intent.
     * This operation is asynchronous but its execution will still be sequentially scheduled
     * relative to calls to {@link #setBluetoothHearingAidDeviceConnectionState(BluetoothDevice,
     * int, boolean, int)} and
     * {@link #handleBluetoothA2dpDeviceConfigChange(BluetoothDevice)}.
     * @param device Bluetooth device connected/disconnected
     * @param state  new connection state, {@link BluetoothProfile#STATE_CONNECTED}
     *     or {@link BluetoothProfile#STATE_DISCONNECTED}
     * @param profile profile for the A2DP device
     * @param a2dpVolume New volume for the connecting device. Does nothing if disconnecting.
     * (either {@link android.bluetooth.BluetoothProfile.A2DP} or
     * {@link android.bluetooth.BluetoothProfile.A2DP_SINK})
     * @param suppressNoisyIntent if true the
     * {@link AudioManager.ACTION_AUDIO_BECOMING_NOISY} intent will not be sent.
     * {@hide}
     */
    public void setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(
            BluetoothDevice device, int state,
            int profile, boolean suppressNoisyIntent, int a2dpVolume) {
        final IAudioService service = getService();
        try {
            service.setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(device,
                state, profile, suppressNoisyIntent, a2dpVolume);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

     /**
     * Indicate A2DP device configuration has changed.
     * This operation is asynchronous but its execution will still be sequentially scheduled
     * relative to calls to
     * {@link #setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(BluetoothDevice, int, int,
     * boolean, int)} and
     * {@link #setBluetoothHearingAidDeviceConnectionState(BluetoothDevice, int, boolean, int)}
     * @param device Bluetooth device whose configuration has changed.
     * {@hide}
     */
    public void handleBluetoothA2dpDeviceConfigChange(BluetoothDevice device) {
        final IAudioService service = getService();
        try {
            service.handleBluetoothA2dpDeviceConfigChange(device);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * @hide
     * Sets an additional audio output device delay in milliseconds.
     *
     * The additional output delay is a request to the output device to
     * delay audio presentation (generally with respect to video presentation for better
     * synchronization).
     * It may not be supported by all output devices,
     * and typically increases the audio latency by the amount of additional
     * audio delay requested.
     *
     * If additional audio delay is supported by an audio output device,
     * it is expected to be supported for all output streams (and configurations)
     * opened on that device.
     *
     * @param device an instance of {@link AudioDeviceInfo} returned from {@link getDevices()}.
     * @param delayMillis delay in milliseconds desired.  This should be in range of {@code 0}
     *     to the value returned by {@link #getMaxAdditionalOutputDeviceDelay()}.
     * @return true if successful, false if the device does not support output device delay
     *     or the delay is not in range of {@link #getMaxAdditionalOutputDeviceDelay()}.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean setAdditionalOutputDeviceDelay(
            @NonNull AudioDeviceInfo device, @IntRange(from = 0) long delayMillis) {
        Objects.requireNonNull(device);
        // Implement the setter in r-dev or r-tv-dev as needed.
        return false;
    }

    /**
     * @hide
     * Returns the current additional audio output device delay in milliseconds.
     *
     * @param device an instance of {@link AudioDeviceInfo} returned from {@link getDevices()}.
     * @return the additional output device delay. This is a non-negative number.
     *     {@code 0} is returned if unsupported.
     */
    @SystemApi
    @IntRange(from = 0)
    public long getAdditionalOutputDeviceDelay(@NonNull AudioDeviceInfo device) {
        Objects.requireNonNull(device);
        // Implement the getter in r-dev or r-tv-dev as needed.
        return 0;
    }

    /**
     * @hide
     * Returns the maximum additional audio output device delay in milliseconds.
     *
     * @param device an instance of {@link AudioDeviceInfo} returned from {@link getDevices()}.
     * @return the maximum output device delay in milliseconds that can be set.
     *     This is a non-negative number
     *     representing the additional audio delay supported for the device.
     *     {@code 0} is returned if unsupported.
     */
    @SystemApi
    @IntRange(from = 0)
    public long getMaxAdditionalOutputDeviceDelay(@NonNull AudioDeviceInfo device) {
        Objects.requireNonNull(device);
        // Implement the getter in r-dev or r-tv-dev as needed.
        return 0;
    }

    /**
     * Returns the estimated latency for the given stream type in milliseconds.
     *
     * DO NOT UNHIDE. The existing approach for doing A/V sync has too many problems. We need
     * a better solution.
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @SuppressLint("Doclava125") // FIXME is this still used?
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

    /** @hide */
    @TestApi
    @SystemApi
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public static int releaseAudioPatch(AudioPatch patch) {
        return AudioSystem.releaseAudioPatch(patch);
    }

    /**
     * List all existing connections between audio ports.
     * @param patches An AudioPatch array where the list will be returned.
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public void registerAudioPortUpdateListener(OnAudioPortUpdateListener l) {
        sAudioPortEventHandler.init();
        sAudioPortEventHandler.registerListener(l);
    }

    /**
     * Unregister an audio port list update listener.
     * @hide
     */
    @UnsupportedAppUsage
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
                    // Loop until patch generation is the same as port generation unless audio ports
                    // and audio patches are not null.
                } while (patchGeneration[0] != portGeneration[0]
                        && (ports == null || patches == null));
                // If the patch generation doesn't equal port generation, return ERROR here in case
                // of mismatch between audio ports and audio patches.
                if (patchGeneration[0] != portGeneration[0]) {
                    return ERROR;
                }

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
     * a {@link Handler} object to addOnAudioDeviceConnectionListener().
     */
    private final static int MSG_DEVICES_CALLBACK_REGISTERED = 0;
    private final static int MSG_DEVICES_DEVICES_ADDED = 1;
    private final static int MSG_DEVICES_DEVICES_REMOVED = 2;

    /**
     * The list of {@link AudioDeviceCallback} objects to receive add/remove notifications.
     */
    private final ArrayMap<AudioDeviceCallback, NativeEventHandlerDelegate> mDeviceCallbacks =
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

    /** @hide */
    @IntDef(flag = true, prefix = "GET_DEVICES", value = {
            GET_DEVICES_INPUTS,
            GET_DEVICES_OUTPUTS }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioDeviceRole {}

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
                    AudioDeviceInfo.TYPE_UNKNOWN;
    }

    /**
     * Returns an array of {@link AudioDeviceInfo} objects corresponding to the audio devices
     * currently connected to the system and meeting the criteria specified in the
     * <code>flags</code> parameter.
     * @param flags A set of bitflags specifying the criteria to test.
     * @see #GET_DEVICES_OUTPUTS
     * @see #GET_DEVICES_INPUTS
     * @see #GET_DEVICES_ALL
     * @return A (possibly zero-length) array of AudioDeviceInfo objects.
     */
    public AudioDeviceInfo[] getDevices(@AudioDeviceRole int flags) {
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
     * @see #GET_DEVICES_OUTPUTS
     * @see #GET_DEVICES_INPUTS
     * @see #GET_DEVICES_ALL
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
            @Nullable Handler handler) {
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
                broadcastDeviceListChange_sync(delegate.getHandler());
            }
        }
    }

    /**
     * Unregisters an {@link AudioDeviceCallback} object which has been previously registered
     * to receive notifications of changes to the set of connected audio devices.
     * @param callback The {@link AudioDeviceCallback} object that was previously registered
     * with {@link AudioManager#registerAudioDeviceCallback} to be unregistered.
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

    /**
     * Set port id for microphones by matching device type and address.
     * @hide
     */
    public static void setPortIdForMicrophones(ArrayList<MicrophoneInfo> microphones) {
        AudioDeviceInfo[] devices = getDevicesStatic(AudioManager.GET_DEVICES_INPUTS);
        for (int i = microphones.size() - 1; i >= 0; i--) {
            boolean foundPortId = false;
            for (AudioDeviceInfo device : devices) {
                if (device.getPort().type() == microphones.get(i).getInternalDeviceType()
                        && TextUtils.equals(device.getAddress(), microphones.get(i).getAddress())) {
                    microphones.get(i).setId(device.getId());
                    foundPortId = true;
                    break;
                }
            }
            if (!foundPortId) {
                Log.i(TAG, "Failed to find port id for device with type:"
                        + microphones.get(i).getType() + " address:"
                        + microphones.get(i).getAddress());
                microphones.remove(i);
            }
        }
    }

    /**
     * Convert {@link AudioDeviceInfo} to {@link MicrophoneInfo}.
     * @hide
     */
    public static MicrophoneInfo microphoneInfoFromAudioDeviceInfo(AudioDeviceInfo deviceInfo) {
        int deviceType = deviceInfo.getType();
        int micLocation = (deviceType == AudioDeviceInfo.TYPE_BUILTIN_MIC
                || deviceType == AudioDeviceInfo.TYPE_TELEPHONY) ? MicrophoneInfo.LOCATION_MAINBODY
                : deviceType == AudioDeviceInfo.TYPE_UNKNOWN ? MicrophoneInfo.LOCATION_UNKNOWN
                        : MicrophoneInfo.LOCATION_PERIPHERAL;
        MicrophoneInfo microphone = new MicrophoneInfo(
                deviceInfo.getPort().name() + deviceInfo.getId(),
                deviceInfo.getPort().type(), deviceInfo.getAddress(), micLocation,
                MicrophoneInfo.GROUP_UNKNOWN, MicrophoneInfo.INDEX_IN_THE_GROUP_UNKNOWN,
                MicrophoneInfo.POSITION_UNKNOWN, MicrophoneInfo.ORIENTATION_UNKNOWN,
                new ArrayList<Pair<Float, Float>>(), new ArrayList<Pair<Integer, Integer>>(),
                MicrophoneInfo.SENSITIVITY_UNKNOWN, MicrophoneInfo.SPL_UNKNOWN,
                MicrophoneInfo.SPL_UNKNOWN, MicrophoneInfo.DIRECTIONALITY_UNKNOWN);
        microphone.setId(deviceInfo.getId());
        return microphone;
    }

    /**
     * Add {@link MicrophoneInfo} by device information while filtering certain types.
     */
    private void addMicrophonesFromAudioDeviceInfo(ArrayList<MicrophoneInfo> microphones,
                    HashSet<Integer> filterTypes) {
        AudioDeviceInfo[] devices = getDevicesStatic(GET_DEVICES_INPUTS);
        for (AudioDeviceInfo device : devices) {
            if (filterTypes.contains(device.getType())) {
                continue;
            }
            MicrophoneInfo microphone = microphoneInfoFromAudioDeviceInfo(device);
            microphones.add(microphone);
        }
    }

    /**
     * Returns a list of {@link MicrophoneInfo} that corresponds to the characteristics
     * of all available microphones. The list is empty when no microphones are available
     * on the device. An error during the query will result in an IOException being thrown.
     *
     * @return a list that contains all microphones' characteristics
     * @throws IOException if an error occurs.
     */
    public List<MicrophoneInfo> getMicrophones() throws IOException {
        ArrayList<MicrophoneInfo> microphones = new ArrayList<MicrophoneInfo>();
        int status = AudioSystem.getMicrophones(microphones);
        HashSet<Integer> filterTypes = new HashSet<>();
        filterTypes.add(AudioDeviceInfo.TYPE_TELEPHONY);
        if (status != AudioManager.SUCCESS) {
            // fail and populate microphones with unknown characteristics by device information.
            if (status != AudioManager.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "getMicrophones failed:" + status);
            }
            Log.i(TAG, "fallback on device info");
            addMicrophonesFromAudioDeviceInfo(microphones, filterTypes);
            return microphones;
        }
        setPortIdForMicrophones(microphones);
        filterTypes.add(AudioDeviceInfo.TYPE_BUILTIN_MIC);
        addMicrophonesFromAudioDeviceInfo(microphones, filterTypes);
        return microphones;
    }

    /**
     * Returns a list of audio formats that corresponds to encoding formats
     * supported on offload path for A2DP playback.
     *
     * @return a list of {@link BluetoothCodecConfig} objects containing encoding formats
     * supported for offload A2DP playback
     * @hide
     */
    public List<BluetoothCodecConfig> getHwOffloadEncodingFormatsSupportedForA2DP() {
        ArrayList<Integer> formatsList = new ArrayList<Integer>();
        ArrayList<BluetoothCodecConfig> codecConfigList = new ArrayList<BluetoothCodecConfig>();

        int status = AudioSystem.getHwOffloadEncodingFormatsSupportedForA2DP(formatsList);
        if (status != AudioManager.SUCCESS) {
            Log.e(TAG, "getHwOffloadEncodingFormatsSupportedForA2DP failed:" + status);
            return codecConfigList;
        }

        for (Integer format : formatsList) {
            int btSourceCodec = AudioSystem.audioFormatToBluetoothSourceCodec(format);
            if (btSourceCodec
                    != BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                codecConfigList.add(new BluetoothCodecConfig(btSourceCodec));
            }
        }
        return codecConfigList;
    }

    // Since we need to calculate the changes since THE LAST NOTIFICATION, and not since the
    // (unpredictable) last time updateAudioPortCache() was called by someone, keep a list
    // of the ports that exist at the time of the last notification.
    private ArrayList<AudioDevicePort> mPreviousPorts = new ArrayList<AudioDevicePort>();

    /**
     * Internal method to compute and generate add/remove messages and then send to any
     * registered callbacks. Must be called synchronized on mDeviceCallbacks.
     */
    private void broadcastDeviceListChange_sync(Handler handler) {
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
                for (int i = 0; i < mDeviceCallbacks.size(); i++) {
                    handler = mDeviceCallbacks.valueAt(i).getHandler();
                    if (handler != null) {
                        if (removed_devices.length != 0) {
                            handler.sendMessage(Message.obtain(handler,
                                    MSG_DEVICES_DEVICES_REMOVED,
                                    removed_devices));
                        }
                        if (added_devices.length != 0) {
                            handler.sendMessage(Message.obtain(handler,
                                    MSG_DEVICES_DEVICES_ADDED,
                                    added_devices));
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
            synchronized (mDeviceCallbacks) {
                broadcastDeviceListChange_sync(null);
            }
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
            synchronized (mDeviceCallbacks) {
                broadcastDeviceListChange_sync(null);
            }
        }
    }


    /**
     * @hide
     * Abstract class to receive event notification about audioserver process state.
     */
    @SystemApi
    public abstract static class AudioServerStateCallback {
        public void onAudioServerDown() { }
        public void onAudioServerUp() { }
    }

    private Executor mAudioServerStateExec;
    private AudioServerStateCallback mAudioServerStateCb;
    private final Object mAudioServerStateCbLock = new Object();

    private final IAudioServerStateDispatcher mAudioServerStateDispatcher =
            new IAudioServerStateDispatcher.Stub() {
        @Override
        public void dispatchAudioServerStateChange(boolean state) {
            Executor exec;
            AudioServerStateCallback cb;

            synchronized (mAudioServerStateCbLock) {
                exec = mAudioServerStateExec;
                cb = mAudioServerStateCb;
            }

            if ((exec == null) || (cb == null)) {
                return;
            }
            if (state) {
                exec.execute(() -> cb.onAudioServerUp());
            } else {
                exec.execute(() -> cb.onAudioServerDown());
            }
        }
    };

    /**
     * @hide
     * Registers a callback for notification of audio server state changes.
     * @param executor {@link Executor} to handle the callbacks
     * @param stateCallback the callback to receive the audio server state changes
     *        To remove the callabck, pass a null reference for both executor and stateCallback.
     */
    @SystemApi
    public void setAudioServerStateCallback(@NonNull Executor executor,
            @NonNull AudioServerStateCallback stateCallback) {
        if (stateCallback == null) {
            throw new IllegalArgumentException("Illegal null AudioServerStateCallback");
        }
        if (executor == null) {
            throw new IllegalArgumentException(
                    "Illegal null Executor for the AudioServerStateCallback");
        }

        synchronized (mAudioServerStateCbLock) {
            if (mAudioServerStateCb != null) {
                throw new IllegalStateException(
                    "setAudioServerStateCallback called with already registered callabck");
            }
            final IAudioService service = getService();
            try {
                service.registerAudioServerStateDispatcher(mAudioServerStateDispatcher);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mAudioServerStateExec = executor;
            mAudioServerStateCb = stateCallback;
        }
    }

    /**
     * @hide
     * Unregisters the callback for notification of audio server state changes.
     */
    @SystemApi
    public void clearAudioServerStateCallback() {
        synchronized (mAudioServerStateCbLock) {
            if (mAudioServerStateCb != null) {
                final IAudioService service = getService();
                try {
                    service.unregisterAudioServerStateDispatcher(
                            mAudioServerStateDispatcher);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            mAudioServerStateExec = null;
            mAudioServerStateCb = null;
        }
    }

    /**
     * @hide
     * Checks if native audioservice is running or not.
     * @return true if native audioservice runs, false otherwise.
     */
    @SystemApi
    public boolean isAudioServerRunning() {
        final IAudioService service = getService();
        try {
            return service.isAudioServerRunning();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns all surround formats.
     * @return a map where the key is a surround format and
     * the value indicates the surround format is enabled or not
     */
    public Map<Integer, Boolean> getSurroundFormats() {
        Map<Integer, Boolean> surroundFormats = new HashMap<>();
        int status = AudioSystem.getSurroundFormats(surroundFormats, false);
        if (status != AudioManager.SUCCESS) {
            // fail and bail!
            Log.e(TAG, "getSurroundFormats failed:" + status);
            return new HashMap<Integer, Boolean>(); // Always return a map.
        }
        return surroundFormats;
    }

    /**
     * @hide
     * Set a certain surround format as enabled or not.
     * @param audioFormat a surround format, the value is one of
     *        {@link AudioFormat#ENCODING_AC3}, {@link AudioFormat#ENCODING_E_AC3},
     *        {@link AudioFormat#ENCODING_DTS}, {@link AudioFormat#ENCODING_DTS_HD},
     *        {@link AudioFormat#ENCODING_AAC_LC}, {@link AudioFormat#ENCODING_DOLBY_TRUEHD},
     *        {@link AudioFormat#ENCODING_E_AC3_JOC}. Once {@link AudioFormat#ENCODING_AAC_LC} is
     *        set as enabled, {@link AudioFormat#ENCODING_AAC_LC},
     *        {@link AudioFormat#ENCODING_AAC_HE_V1}, {@link AudioFormat#ENCODING_AAC_HE_V2},
     *        {@link AudioFormat#ENCODING_AAC_ELD}, {@link AudioFormat#ENCODING_AAC_XHE} are
     *        all enabled.
     * @param enabled the required surround format state, true for enabled, false for disabled
     * @return true if successful, otherwise false
     */
    public boolean setSurroundFormatEnabled(
            @AudioFormat.SurroundSoundEncoding int audioFormat, boolean enabled) {
        int status = AudioSystem.setSurroundFormatEnabled(audioFormat, enabled);
        return status == AudioManager.SUCCESS;
    }

    /**
     * @hide
     * Returns all surround formats that are reported by the connected HDMI device.
     * The keys are not affected by calling setSurroundFormatEnabled(), and the values
     * are not affected by calling setSurroundFormatEnabled() when in AUTO mode.
     * This information can used to show the AUTO setting for SurroundSound.
     *
     * @return a map where the key is a surround format and
     * the value indicates the surround format is enabled or not
     */
    public Map<Integer, Boolean> getReportedSurroundFormats() {
        Map<Integer, Boolean> reportedSurroundFormats = new HashMap<>();
        int status = AudioSystem.getSurroundFormats(reportedSurroundFormats, true);
        if (status != AudioManager.SUCCESS) {
            // fail and bail!
            Log.e(TAG, "getReportedSurroundFormats failed:" + status);
            return new HashMap<Integer, Boolean>(); // Always return a map.
        }
        return reportedSurroundFormats;
    }

    /**
     * Return if audio haptic coupled playback is supported or not.
     *
     * @return whether audio haptic playback supported.
     */
    public static boolean isHapticPlaybackSupported() {
        return AudioSystem.isHapticPlaybackSupported();
    }

    /**
     * @hide
     * Introspection API to retrieve audio product strategies.
     * When implementing {Car|Oem}AudioManager, use this method  to retrieve the collection of
     * audio product strategies, which is indexed by a weakly typed index in order to be extended
     * by OEM without any needs of AOSP patches.
     * The {Car|Oem}AudioManager can expose API to build {@link AudioAttributes} for a given product
     * strategy refered either by its index or human readable string. It will allow clients
     * application to start streaming data using these {@link AudioAttributes} on the selected
     * device by Audio Policy Engine.
     * @return a (possibly zero-length) array of
     *         {@see android.media.audiopolicy.AudioProductStrategy} objects.
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static List<AudioProductStrategy> getAudioProductStrategies() {
        final IAudioService service = getService();
        try {
            return service.getAudioProductStrategies();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Introspection API to retrieve audio volume groups.
     * When implementing {Car|Oem}AudioManager, use this method  to retrieve the collection of
     * audio volume groups.
     * @return a (possibly zero-length) List of
     *         {@see android.media.audiopolicy.AudioVolumeGroup} objects.
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static List<AudioVolumeGroup> getAudioVolumeGroups() {
        final IAudioService service = getService();
        try {
            return service.getAudioVolumeGroups();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Callback registered by client to be notified upon volume group change.
     */
    @SystemApi
    public abstract static class VolumeGroupCallback {
        /**
         * Callback method called upon audio volume group change.
         * @param group the group for which the volume has changed
         */
        public void onAudioVolumeGroupChanged(int group, int flags) {}
    }

   /**
    * @hide
    * Register an audio volume group change listener.
    * @param callback the {@link VolumeGroupCallback} to register
    */
    @SystemApi
    public void registerVolumeGroupCallback(
            @NonNull Executor executor,
            @NonNull VolumeGroupCallback callback) {
        Preconditions.checkNotNull(executor, "executor must not be null");
        Preconditions.checkNotNull(callback, "volume group change cb must not be null");
        sAudioAudioVolumeGroupChangedHandler.init();
        // TODO: make use of executor
        sAudioAudioVolumeGroupChangedHandler.registerListener(callback);
    }

   /**
    * @hide
    * Unregister an audio volume group change listener.
    * @param callback the {@link VolumeGroupCallback} to unregister
    */
    @SystemApi
    public void unregisterVolumeGroupCallback(
            @NonNull VolumeGroupCallback callback) {
        Preconditions.checkNotNull(callback, "volume group change cb must not be null");
        sAudioAudioVolumeGroupChangedHandler.unregisterListener(callback);
    }

    /**
     * Return if an asset contains haptic channels or not.
     * @param uri the {@link Uri} of the asset.
     * @return true if the assert contains haptic channels.
     * @hide
     */
    public static boolean hasHapticChannels(Uri uri) {
        try {
            return getService().hasHapticChannels(uri);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
