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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.media.audio.Flags.autoPublicVolumeApiHardening;
import static android.media.audio.Flags.automaticBtDeviceType;
import static android.media.audio.Flags.FLAG_FOCUS_EXCLUSIVE_WITH_RECORDING;
import static android.media.audio.Flags.FLAG_FOCUS_FREEZE_TEST_API;
import static android.media.audio.Flags.FLAG_SUPPORTED_DEVICE_TYPES_API;
import static android.media.audiopolicy.Flags.FLAG_ENABLE_FADE_MANAGER_CONFIGURATION;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
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
import android.app.compat.CompatChanges;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.companion.virtual.VirtualDeviceManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes.AttributeSystemUsage;
import android.media.AudioDeviceInfo;
import android.media.CallbackUtil.ListenerInfo;
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
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AudioManager provides access to volume and ringer mode control.
 */
@SystemService(Context.AUDIO_SERVICE)
public class AudioManager {

    private Context mOriginalContext;
    private Context mApplicationContext;
    private int mOriginalContextDeviceId = DEVICE_ID_DEFAULT;
    private @Nullable VirtualDeviceManager mVirtualDeviceManager; // Lazy initialized.
    private static final String TAG = "AudioManager";
    private static final boolean DEBUG = false;
    private static final AudioPortEventHandler sAudioPortEventHandler = new AudioPortEventHandler();
    private static final AudioVolumeGroupChangeHandler sAudioAudioVolumeGroupChangedHandler =
            new AudioVolumeGroupChangeHandler();

    private static WeakReference<Context> sContext;

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
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SuppressLint("ActionValue")
    public static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";

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
    @SystemApi
    @SuppressLint("ActionValue")
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
    @SystemApi
    @SuppressLint("ActionValue")
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
    @SystemApi
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int STREAM_ASSISTANT = AudioSystem.STREAM_ASSISTANT;

    /** Number of audio streams */
    /**
     * @deprecated Do not iterate on volume stream type values.
     */
    @Deprecated public static final int NUM_STREAMS = AudioSystem.NUM_STREAMS;

    /** @hide */
    private static final int[] PUBLIC_STREAM_TYPES = { AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_DTMF,  AudioManager.STREAM_ACCESSIBILITY };

    /** @hide */
    @TestApi
    public static final int[] getPublicStreamTypes() {
        return PUBLIC_STREAM_TYPES;
    }

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
    @SystemApi
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
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int FLAG_FROM_KEY = 1 << 12;

    /**
     * Indicates that an absolute volume controller is notifying AudioService of a change in the
     * volume or mute status of an external audio system.
     * @hide
     */
    public static final int FLAG_ABSOLUTE_VOLUME = 1 << 13;

    /** @hide */
    @IntDef(prefix = {"ENCODED_SURROUND_OUTPUT_"}, value = {
            ENCODED_SURROUND_OUTPUT_UNKNOWN,
            ENCODED_SURROUND_OUTPUT_AUTO,
            ENCODED_SURROUND_OUTPUT_NEVER,
            ENCODED_SURROUND_OUTPUT_ALWAYS,
            ENCODED_SURROUND_OUTPUT_MANUAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncodedSurroundOutputMode {}

    /**
     * The mode for surround sound formats is unknown.
     */
    public static final int ENCODED_SURROUND_OUTPUT_UNKNOWN = -1;

    /**
     * The surround sound formats are available for use if they are detected. This is the default
     * mode.
     */
    public static final int ENCODED_SURROUND_OUTPUT_AUTO = 0;

    /**
     * The surround sound formats are NEVER available, even if they are detected by the hardware.
     * Those formats will not be reported.
     */
    public static final int ENCODED_SURROUND_OUTPUT_NEVER = 1;

    /**
     * The surround sound formats are ALWAYS available, even if they are not detected by the
     * hardware. Those formats will be reported as part of the HDMI output capability.
     * Applications are then free to use either PCM or encoded output.
     */
    public static final int ENCODED_SURROUND_OUTPUT_ALWAYS = 2;

    /**
     * Surround sound formats are available according to the choice of user, even if they are not
     * detected by the hardware. Those formats will be reported as part of the HDMI output
     * capability. Applications are then free to use either PCM or encoded output.
     */
    public static final int ENCODED_SURROUND_OUTPUT_MANUAL = 3;

    /**
     * @hide
     * This list contains all the flags that can be used in internal APIs for volume
     * related operations */
    @IntDef(flag = true, prefix = "FLAG", value = {
            FLAG_SHOW_UI,
            FLAG_ALLOW_RINGER_MODES,
            FLAG_PLAY_SOUND,
            FLAG_REMOVE_SOUND_AND_VIBRATE,
            FLAG_VIBRATE,
            FLAG_FIXED_VOLUME,
            FLAG_BLUETOOTH_ABS_VOLUME,
            FLAG_SHOW_SILENT_HINT,
            FLAG_HDMI_SYSTEM_AUDIO_VOLUME,
            FLAG_ACTIVE_MEDIA_ONLY,
            FLAG_SHOW_UI_WARNINGS,
            FLAG_SHOW_VIBRATE_HINT,
            FLAG_FROM_KEY,
            FLAG_ABSOLUTE_VOLUME,
    })
    @Retention(RetentionPolicy.SOURCE)
    // TODO(308698465) remove due to potential conflict with the new flags class
    public @interface Flags {}

    /**
     * @hide
     * This list contains all the flags that can be used in SDK-visible methods for volume
     * related operations.
     * See for instance {@link #adjustVolume(int, int)},
     * {@link #adjustStreamVolume(int, int, int)},
     * {@link #adjustSuggestedStreamVolume(int, int, int)},
     * {@link #adjustVolumeGroupVolume(int, int, int)},
     * {@link #setStreamVolume(int, int, int)}
     * The list contains all volume flags, but the values commented out of the list are there for
     * maintenance reasons (for when adding flags or changing their visibility),
     * and to document why some are not in the list (hidden or SystemApi). */
    @IntDef(flag = true, prefix = "FLAG", value = {
            FLAG_SHOW_UI,
            FLAG_ALLOW_RINGER_MODES,
            FLAG_PLAY_SOUND,
            FLAG_REMOVE_SOUND_AND_VIBRATE,
            FLAG_VIBRATE,
            //FLAG_FIXED_VOLUME,             removed due to @hide
            //FLAG_BLUETOOTH_ABS_VOLUME,     removed due to @SystemApi
            //FLAG_SHOW_SILENT_HINT,         removed due to @hide
            //FLAG_HDMI_SYSTEM_AUDIO_VOLUME, removed due to @hide
            //FLAG_ACTIVE_MEDIA_ONLY,        removed due to @hide
            //FLAG_SHOW_UI_WARNINGS,         removed due to @hide
            //FLAG_SHOW_VIBRATE_HINT,        removed due to @hide
            //FLAG_FROM_KEY,                 removed due to @SystemApi
            //FLAG_ABSOLUTE_VOLUME,          removed due to @hide
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PublicVolumeFlags {}

    /**
     * @hide
     * Like PublicVolumeFlags, but for all the flags that can be used in @SystemApi methods for
     * volume related operations.
     * See for instance {@link #setVolumeIndexForAttributes(AudioAttributes, int, int)},
     * {@link #setVolumeGroupVolumeIndex(int, int, int)},
     * {@link #setStreamVolumeForUid(int, int, int, String, int, int, int)},
     * {@link #adjustStreamVolumeForUid(int, int, int, String, int, int, int)},
     * {@link #adjustSuggestedStreamVolumeForUid(int, int, int, String, int, int, int)}
     * The list contains all volume flags, but the values commented out of the list are there for
     * maintenance reasons (for when adding flags or changing their visibility),
     * and to document which hidden values are not in the list. */
    @IntDef(flag = true, prefix = "FLAG", value = {
            FLAG_SHOW_UI,
            FLAG_ALLOW_RINGER_MODES,
            FLAG_PLAY_SOUND,
            FLAG_REMOVE_SOUND_AND_VIBRATE,
            FLAG_VIBRATE,
            //FLAG_FIXED_VOLUME,             removed due to @hide
            FLAG_BLUETOOTH_ABS_VOLUME,
            //FLAG_SHOW_SILENT_HINT,         removed due to @hide
            //FLAG_HDMI_SYSTEM_AUDIO_VOLUME, removed due to @hide
            //FLAG_ACTIVE_MEDIA_ONLY,        removed due to @hide
            //FLAG_SHOW_UI_WARNINGS,         removed due to @hide
            //FLAG_SHOW_VIBRATE_HINT,        removed due to @hide
            FLAG_FROM_KEY,
            //FLAG_ABSOLUTE_VOLUME,          removed due to @hide
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemVolumeFlags {}

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
        FLAG_NAMES.put(FLAG_ABSOLUTE_VOLUME, "FLAG_ABSOLUTE_VOLUME");
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public AudioManager() {
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public AudioManager(Context context) {
        setContext(context);
        initPlatform();
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
        if (context == null) {
            return;
        }
        mOriginalContextDeviceId = context.getDeviceId();
        mApplicationContext = context.getApplicationContext();
        if (mApplicationContext != null) {
            mOriginalContext = null;
        } else {
            mOriginalContext = context;
        }
        sContext = new WeakReference<>(context);
    }

    @UnsupportedAppUsage
    static IAudioService getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }

    private VirtualDeviceManager getVirtualDeviceManager() {
        if (mVirtualDeviceManager != null) {
            return mVirtualDeviceManager;
        }
        mVirtualDeviceManager = getContext().getSystemService(VirtualDeviceManager.class);
        return mVirtualDeviceManager;
    }

    /**
     * Sends a simulated key event for a media button. To simulate a key press, you must first send
     * a KeyEvent built with a {@link KeyEvent#ACTION_DOWN} action, then another event with the
     * {@link KeyEvent#ACTION_UP} action.
     *
     * <p>The key event will be sent to the current media key event consumer which registered with
     * {@link AudioManager#registerMediaButtonEventReceiver(PendingIntent)}.
     *
     * @param keyEvent a media session {@link KeyEvent}, as defined by {@link
     *     KeyEvent#isMediaSessionKey}.
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
                && AudioSystem.PLAY_SOUND_DELAY > SystemClock.uptimeMillis()) {
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
        boolean res = false;
        try {
            res = getService().isVolumeFixed();
        } catch (RemoteException e) {
            Log.e(TAG, "Error querying isVolumeFixed", e);
        }
        return res;
    }

    /**
     * Adjusts the volume of a particular stream by one step in a direction.
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * <p>From N onward, ringer mode adjustments that would toggle Do Not Disturb are not allowed
     * unless the app has been granted Notification Policy Access.
     * See {@link NotificationManager#isNotificationPolicyAccessGranted()}.
     *
     * @param streamType The stream type to adjust. One of {@link #STREAM_VOICE_CALL},
     * {@link #STREAM_SYSTEM}, {@link #STREAM_RING}, {@link #STREAM_MUSIC},
     * {@link #STREAM_ALARM} or {@link #STREAM_ACCESSIBILITY}.
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param flags
     * @see #adjustVolume(int, int)
     * @see #setStreamVolume(int, int, int)
     * @throws SecurityException if the adjustment triggers a Do Not Disturb change
     *   and the caller is not granted notification policy access.
     */
    public void adjustStreamVolume(int streamType, int direction, @PublicVolumeFlags int flags) {
        final IAudioService service = getService();
        try {
            service.adjustStreamVolumeWithAttribution(streamType, direction, flags,
                    getContext().getOpPackageName(), getContext().getAttributionTag());
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
     * @param flags
     * @see #adjustSuggestedStreamVolume(int, int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #isVolumeFixed()
     */
    public void adjustVolume(int direction, @PublicVolumeFlags int flags) {
        if (applyAutoHardening()) {
            final IAudioService service = getService();
            try {
                service.adjustVolume(direction, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(getContext());
            helper.sendAdjustVolumeBy(USE_DEFAULT_STREAM_TYPE, direction, flags);
        }
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
     * @param flags
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #isVolumeFixed()
     */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType,
            @PublicVolumeFlags int flags) {
        if (applyAutoHardening()) {
            final IAudioService service = getService();
            try {
                service.adjustSuggestedStreamVolume(direction, suggestedStreamType, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(getContext());
            helper.sendAdjustVolumeBy(suggestedStreamType, direction, flags);
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setMasterMute(boolean mute, int flags) {
        final IAudioService service = getService();
        try {
            service.setMasterMute(mute, flags, getContext().getOpPackageName(),
                    UserHandle.getCallingUserId(), getContext().getAttributionTag());
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
     * Returns the current user setting for ramping ringer on incoming phone call ringtone.
     *
     * @return true if the incoming phone call ringtone is configured to gradually increase its
     * volume, false otherwise.
     */
    public boolean isRampingRingerEnabled() {
        return Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.APPLY_RAMPING_RINGER, 0) != 0;
    }

    /**
     * Sets the flag for enabling ramping ringer on incoming phone call ringtone.
     *
     * @see #isRampingRingerEnabled()
     * @hide
     */
    @TestApi
    public void setRampingRingerEnabled(boolean enabled) {
        Settings.System.putInt(getContext().getContentResolver(),
                Settings.System.APPLY_RAMPING_RINGER, enabled ? 1 : 0);
    }

    /**
     * Checks valid ringer mode values.
     *
     * @return true if the ringer mode indicated is valid, false otherwise.
     *
     * @see #setRingerMode(int)
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
    @TestApi
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

    /**
     * @hide
     * Checks whether a stream type is part of the public SDK
     * @param streamType
     * @return true if the stream type is available in SDK
     */
    public static boolean isPublicStreamType(int streamType) {
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
    @SystemApi
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
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
     * unless the app has been granted Notification Policy Access.
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
     * the app has been granted Notification Policy Access.
     * See {@link NotificationManager#isNotificationPolicyAccessGranted()}.
     * @param streamType The stream whose volume index should be set.
     * @param index The volume index to set. See
     *            {@link #getStreamMaxVolume(int)} for the largest valid value.
     * @param flags
     * @see #getStreamMaxVolume(int)
     * @see #getStreamVolume(int)
     * @see #isVolumeFixed()
     * @throws SecurityException if the volume change triggers a Do Not Disturb change
     *   and the caller is not granted notification policy access.
     */
    public void setStreamVolume(int streamType, int index, @PublicVolumeFlags int flags) {
        final IAudioService service = getService();
        try {
            service.setStreamVolumeWithAttribution(streamType, index, flags,
                    getContext().getOpPackageName(), getContext().getAttributionTag());
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
     * @param flags
     * @see #getMaxVolumeIndexForAttributes(AudioAttributes)
     * @see #getMinVolumeIndexForAttributes(AudioAttributes)
     * @see #isVolumeFixed()
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setVolumeIndexForAttributes(@NonNull AudioAttributes attr, int index,
            @SystemVolumeFlags int flags) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        int groupId = getVolumeGroupIdForAttributes(attr);
        setVolumeGroupVolumeIndex(groupId, index, flags);
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int getVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        int groupId = getVolumeGroupIdForAttributes(attr);
        return getVolumeGroupVolumeIndex(groupId);
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int getMaxVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        int groupId = getVolumeGroupIdForAttributes(attr);
        return getVolumeGroupMaxVolumeIndex(groupId);
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int getMinVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        Preconditions.checkNotNull(attr, "attr must not be null");
        final IAudioService service = getService();
        int groupId = getVolumeGroupIdForAttributes(attr);
        return getVolumeGroupMinVolumeIndex(groupId);
    }

    /**
     * Returns the volume group id associated to the given {@link AudioAttributes}.
     *
     * @param attributes The {@link AudioAttributes} to consider.
     * @return audio volume group id supporting the given {@link AudioAttributes} if found,
     * {@code android.media.audiopolicy.AudioVolumeGroup.DEFAULT_VOLUME_GROUP} otherwise.
     */
    public int getVolumeGroupIdForAttributes(@NonNull AudioAttributes attributes) {
        Preconditions.checkNotNull(attributes, "Audio Attributes must not be null");
        return AudioProductStrategy.getVolumeGroupIdForAudioAttributes(attributes,
                /* fallbackOnDefault= */ true);
    }

    /**
     * Sets the volume index for a particular group associated to given id.
     * <p> Call first in prior {@link #getVolumeGroupIdForAttributes(AudioAttributes)}
     * to retrieve the volume group id supporting the given {@link AudioAttributes}.
     *
     * @param groupId of the {@link android.media.audiopolicy.AudioVolumeGroup} to consider.
     * @param index The volume index to set. See
     *          {@link #getVolumeGroupMaxVolumeIndex(id)} for the largest valid value
     *          {@link #getVolumeGroupMinVolumeIndex(id)} for the lowest valid value.
     * @param flags
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            android.Manifest.permission.MODIFY_AUDIO_ROUTING
    })
    public void setVolumeGroupVolumeIndex(int groupId, int index, @SystemVolumeFlags int flags) {
        final IAudioService service = getService();
        try {
            service.setVolumeGroupVolumeIndex(groupId, index, flags,
                    getContext().getOpPackageName(), getContext().getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current volume index for a particular group associated to given id.
     * <p> Call first in prior {@link #getVolumeGroupIdForAttributes(AudioAttributes)}
     * to retrieve the volume group id supporting the given {@link AudioAttributes}.
     *
     * @param groupId of the {@link android.media.audiopolicy.AudioVolumeGroup} to consider.
     * @return The current volume index for the stream.
     * @hide
     */
    @SystemApi
    @IntRange(from = 0)
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            android.Manifest.permission.MODIFY_AUDIO_ROUTING
    })
    public int getVolumeGroupVolumeIndex(int groupId) {
        final IAudioService service = getService();
        try {
            return service.getVolumeGroupVolumeIndex(groupId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the maximum volume index for a particular group associated to given id.
     * <p> Call first in prior {@link #getVolumeGroupIdForAttributes(AudioAttributes)}
     * to retrieve the volume group id supporting the given {@link AudioAttributes}.
     *
     * @param groupId of the {@link android.media.audiopolicy.AudioVolumeGroup} to consider.
     * @return The maximum valid volume index for the {@link AudioAttributes}.
     * @hide
     */
    @SystemApi
    @IntRange(from = 0)
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            android.Manifest.permission.MODIFY_AUDIO_ROUTING
    })
    public int getVolumeGroupMaxVolumeIndex(int groupId) {
        final IAudioService service = getService();
        try {
            return service.getVolumeGroupMaxVolumeIndex(groupId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the minimum volume index for a particular group associated to given id.
     * <p> Call first in prior {@link #getVolumeGroupIdForAttributes(AudioAttributes)}
     * to retrieve the volume group id supporting the given {@link AudioAttributes}.
     *
     * @param groupId of the {@link android.media.audiopolicy.AudioVolumeGroup} to consider.
     * @return The minimum valid volume index for the {@link AudioAttributes}.
     * @hide
     */
    @SystemApi
    @IntRange(from = 0)
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            android.Manifest.permission.MODIFY_AUDIO_ROUTING
    })
    public int getVolumeGroupMinVolumeIndex(int groupId) {
        final IAudioService service = getService();
        try {
            return service.getVolumeGroupMinVolumeIndex(groupId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adjusts the volume of a particular group associated to given id by one step in a direction.
     * <p> If the volume group is associated to a stream type, it fallbacks on
     * {@link #adjustStreamVolume(int, int, int)} for compatibility reason.
     * <p> Call first in prior {@link #getVolumeGroupIdForAttributes(AudioAttributes)} to retrieve
     * the volume group id supporting the given {@link AudioAttributes}.
     *
     * @param groupId of the audio volume group to consider.
     * @param direction The direction to adjust the volume. One of
     *            {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *            {@link #ADJUST_SAME}.
     * @param flags
     * @throws SecurityException if the adjustment triggers a Do Not Disturb change and the caller
     * is not granted notification policy access.
     */
    public void adjustVolumeGroupVolume(int groupId, int direction, @PublicVolumeFlags int flags) {
        IAudioService service = getService();
        try {
            service.adjustVolumeGroupVolume(groupId, direction, flags,
                    getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get last audible volume of the group associated to given id before it was muted.
     * <p> Call first in prior {@link #getVolumeGroupIdForAttributes(AudioAttributes)} to retrieve
     * the volume group id supporting the given {@link AudioAttributes}.
     *
     * @param groupId of the {@link android.media.audiopolicy.AudioVolumeGroup} to consider.
     * @return current volume if not muted, volume before muted otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
    @IntRange(from = 0)
    public int getLastAudibleVolumeForVolumeGroup(int groupId) {
        IAudioService service = getService();
        try {
            return service.getLastAudibleVolumeForVolumeGroup(groupId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current mute state for a particular volume group associated to the given id.
     * <p> Call first in prior {@link #getVolumeGroupIdForAttributes(AudioAttributes)} to retrieve
     * the volume group id supporting the given {@link AudioAttributes}.
     *
     * @param groupId of the audio volume group to consider.
     * @return The mute state for the given audio volume group id.
     * @see #adjustVolumeGroupVolume(int, int, int)
     */
    public boolean isVolumeGroupMuted(int groupId) {
        IAudioService service = getService();
        try {
            return service.isVolumeGroupMuted(groupId);
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
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
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
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
     * @deprecated Use {@link AudioManager#setCommunicationDevice(AudioDeviceInfo)} or
     *           {@link AudioManager#clearCommunicationDevice()} instead.
     */
    @Deprecated public void setSpeakerphoneOn(boolean on) {
        final IAudioService service = getService();
        try {
            service.setSpeakerphoneOn(mICallBack, on);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the speakerphone is on or off.
     *
     * @return true if speakerphone is on, false if it's off
     * @deprecated Use {@link AudioManager#getCommunicationDevice()} instead.
     */
    @Deprecated public boolean isSpeakerphoneOn() {
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean setPreferredDeviceForStrategy(@NonNull AudioProductStrategy strategy,
            @NonNull AudioDeviceAttributes device) {
        return setPreferredDevicesForStrategy(strategy, Arrays.asList(device));
    }

    /**
     * @hide
     * Removes the preferred audio device(s) previously set with
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)} or
     * {@link #setPreferredDevicesForStrategy(AudioProductStrategy, List<AudioDeviceAttributes>)}.
     * @param strategy the audio strategy whose routing will be affected
     * @return true if the operation was successful, false otherwise (invalid strategy, or no
     *     device set for example)
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean removePreferredDeviceForStrategy(@NonNull AudioProductStrategy strategy) {
        Objects.requireNonNull(strategy);
        try {
            final int status =
                    getService().removePreferredDevicesForStrategy(strategy.getId());
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return the preferred device for an audio strategy, previously set with
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)} or
     * {@link #setPreferredDevicesForStrategy(AudioProductStrategy, List<AudioDeviceAttributes>)}
     * @param strategy the strategy to query
     * @return the preferred device for that strategy, if multiple devices are set as preferred
     *    devices, the first one in the list will be returned. Null will be returned if none was
     *    ever set or if the strategy is invalid
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Nullable
    public AudioDeviceAttributes getPreferredDeviceForStrategy(
            @NonNull AudioProductStrategy strategy) {
        List<AudioDeviceAttributes> devices = getPreferredDevicesForStrategy(strategy);
        return devices.isEmpty() ? null : devices.get(0);
    }

    /**
     * @hide
     * Set the preferred devices for a given strategy, i.e. the audio routing to be used by
     * this audio strategy. Note that the devices may not be available at the time the preferred
     * devices is set, but it will be used once made available.
     * <p>Use {@link #removePreferredDeviceForStrategy(AudioProductStrategy)} to cancel setting
     * this preference for this strategy.</p>
     * Note that the list of devices is not a list ranked by preference, but a list of one or more
     * devices used simultaneously to output the same audio signal.
     * @param strategy the audio strategy whose routing will be affected
     * @param devices a non-empty list of the audio devices to route to when available
     * @return true if the operation was successful, false otherwise
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean setPreferredDevicesForStrategy(@NonNull AudioProductStrategy strategy,
                                                  @NonNull List<AudioDeviceAttributes> devices) {
        Objects.requireNonNull(strategy);
        Objects.requireNonNull(devices);
        if (devices.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tried to set preferred devices for strategy with a empty list");
        }
        for (AudioDeviceAttributes device : devices) {
            Objects.requireNonNull(device);
        }
        try {
            final int status =
                    getService().setPreferredDevicesForStrategy(strategy.getId(), devices);
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return the preferred devices for an audio strategy, previously set with
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)}
     * {@link #setPreferredDevicesForStrategy(AudioProductStrategy, List<AudioDeviceAttributes>)}
     * @param strategy the strategy to query
     * @return list of the preferred devices for that strategy
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @NonNull
    public List<AudioDeviceAttributes> getPreferredDevicesForStrategy(
            @NonNull AudioProductStrategy strategy) {
        Objects.requireNonNull(strategy);
        try {
            return getService().getPreferredDevicesForStrategy(strategy.getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Set a device as non-default for a given strategy, i.e. the audio routing to be avoided by
     * this audio strategy.
     * <p>Use
     * {@link #removeDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)}
     * to cancel setting this preference for this strategy.</p>
     * @param strategy the audio strategy whose routing will be affected
     * @param device the audio device to not route to when available
     * @return true if the operation was successful, false otherwise
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean setDeviceAsNonDefaultForStrategy(@NonNull AudioProductStrategy strategy,
                                                    @NonNull AudioDeviceAttributes device) {
        Objects.requireNonNull(strategy);
        Objects.requireNonNull(device);
        try {
            final int status =
                    getService().setDeviceAsNonDefaultForStrategy(strategy.getId(), device);
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Removes the audio device(s) from the non-default device list previously set with
     * {@link #setDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)}
     * @param strategy the audio strategy whose routing will be affected
     * @param device the audio device to remove from the non-default device list
     * @return true if the operation was successful, false otherwise (invalid strategy, or no
     *     device set for example)
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean removeDeviceAsNonDefaultForStrategy(@NonNull AudioProductStrategy strategy,
                                                       @NonNull AudioDeviceAttributes device) {
        Objects.requireNonNull(strategy);
        Objects.requireNonNull(device);
        try {
            final int status =
                    getService().removeDeviceAsNonDefaultForStrategy(strategy.getId(), device);
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the audio device(s) from the non-default device list previously set with
     * {@link #setDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)}
     * @param strategy the audio strategy to query
     * @return list of non-default devices for the strategy
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @NonNull
    public List<AudioDeviceAttributes> getNonDefaultDevicesForStrategy(
            @NonNull AudioProductStrategy strategy) {
        Objects.requireNonNull(strategy);
        try {
            return getService().getNonDefaultDevicesForStrategy(strategy.getId());
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
     * {@link #setPreferredDevicesForStrategy(AudioProductStrategy, List<AudioDeviceAttributes>)}
     * {@link #removePreferredDeviceForStrategy(AudioProductStrategy)} causes a change in
     * preferred device. It will not be invoked directly after registration with
     * {@link #addOnPreferredDeviceForStrategyChangedListener(Executor, OnPreferredDeviceForStrategyChangedListener)}
     * to indicate which strategies had preferred devices at the time of registration.</p>
     * @see #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)
     * @see #removePreferredDeviceForStrategy(AudioProductStrategy)
     * @see #getPreferredDeviceForStrategy(AudioProductStrategy)
     * @deprecated use #OnPreferredDevicesForStrategyChangedListener
     */
    @SystemApi
    @Deprecated
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
     * Interface to be notified of changes in the preferred audio devices set for a given audio
     * strategy.
     * <p>Note that this listener will only be invoked whenever
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)},
     * {@link #setPreferredDevicesForStrategy(AudioProductStrategy, List<AudioDeviceAttributes>)},
     * {@link #setDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)},
     * {@link #removeDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)}
     * or {@link #removePreferredDeviceForStrategy(AudioProductStrategy)} causes a change in
     * preferred device(s). It will not be invoked directly after registration with
     * {@link #addOnPreferredDevicesForStrategyChangedListener(
     * Executor, OnPreferredDevicesForStrategyChangedListener)}
     * to indicate which strategies had preferred devices at the time of registration.</p>
     * @see #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)
     * @see #setPreferredDevicesForStrategy(AudioProductStrategy, List)
     * @see #removePreferredDeviceForStrategy(AudioProductStrategy)
     * @see #getPreferredDevicesForStrategy(AudioProductStrategy)
     */
    @SystemApi
    public interface OnPreferredDevicesForStrategyChangedListener {
        /**
         * Called on the listener to indicate that the preferred audio devices for the given
         * strategy has changed.
         * @param strategy the {@link AudioProductStrategy} whose preferred device changed
         * @param devices a list of newly set preferred audio devices
         */
        void onPreferredDevicesForStrategyChanged(@NonNull AudioProductStrategy strategy,
                                                  @NonNull List<AudioDeviceAttributes> devices);
    }

    /**
     * @hide
     * Adds a listener for being notified of changes to the strategy-preferred audio device.
     * @param executor
     * @param listener
     * @throws SecurityException if the caller doesn't hold the required permission
     * @deprecated use {@link #addOnPreferredDevicesForStrategyChangedListener(
     *             Executor, AudioManager.OnPreferredDevicesForStrategyChangedListener)} instead
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Deprecated
    public void addOnPreferredDeviceForStrategyChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPreferredDeviceForStrategyChangedListener listener)
            throws SecurityException {
        // No-op, the method is deprecated.
    }

    /**
     * @hide
     * Removes a previously added listener of changes to the strategy-preferred audio device.
     * @param listener
     * @deprecated use {@link #removeOnPreferredDevicesForStrategyChangedListener(
     *             AudioManager.OnPreferredDevicesForStrategyChangedListener)} instead
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Deprecated
    public void removeOnPreferredDeviceForStrategyChangedListener(
            @NonNull OnPreferredDeviceForStrategyChangedListener listener) {
        // No-op, the method is deprecated.
    }

    /**
     * @hide
     * Adds a listener for being notified of changes to the strategy-preferred audio device.
     * @param executor
     * @param listener
     * @throws SecurityException if the caller doesn't hold the required permission
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void addOnPreferredDevicesForStrategyChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPreferredDevicesForStrategyChangedListener listener)
            throws SecurityException {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        mPrefDevListenerMgr.addListener(
                executor, listener, "addOnPreferredDevicesForStrategyChangedListener",
                () -> new StrategyPreferredDevicesDispatcherStub());
    }

    /**
     * @hide
     * Removes a previously added listener of changes to the strategy-preferred audio device.
     * @param listener
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void removeOnPreferredDevicesForStrategyChangedListener(
            @NonNull OnPreferredDevicesForStrategyChangedListener listener) {
        Objects.requireNonNull(listener);
        mPrefDevListenerMgr.removeListener(
                listener, "removeOnPreferredDevicesForStrategyChangedListener");
    }

    /**
     * @hide
     * Interface to be notified of changes in the non-default audio devices set for a given audio
     * strategy.
     * <p>Note that this listener will only be invoked whenever
     * {@link #setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceAttributes)},
     * {@link #setPreferredDevicesForStrategy(AudioProductStrategy, List<AudioDeviceAttributes>)},
     * {@link #setDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)},
     * {@link #removeDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)}
     * or {@link #removePreferredDeviceForStrategy(AudioProductStrategy)} causes a change in
     * non-default device(s). It will not be invoked directly after registration with
     * {@link #addOnNonDefaultDevicesForStrategyChangedListener(
     * Executor, OnNonDefaultDevicesForStrategyChangedListener)}
     * to indicate which strategies had preferred devices at the time of registration.</p>
     * @see #setDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)
     * @see #removeDeviceAsNonDefaultForStrategy(AudioProductStrategy, AudioDeviceAttributes)
     */
    @SystemApi
    public interface OnNonDefaultDevicesForStrategyChangedListener {
        /**
         * Called on the listener to indicate that the non-default audio devices for the given
         * strategy has changed.
         * @param strategy the {@link AudioProductStrategy} whose non-default device changed
         * @param devices a list of newly set non-default audio devices
         */
        void onNonDefaultDevicesForStrategyChanged(@NonNull AudioProductStrategy strategy,
                                                   @NonNull List<AudioDeviceAttributes> devices);
    }

    /**
     * @hide
     * Adds a listener for being notified of changes to the non-default audio devices for
     * strategies.
     * @param executor
     * @param listener
     * @throws SecurityException if the caller doesn't hold the required permission
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void addOnNonDefaultDevicesForStrategyChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnNonDefaultDevicesForStrategyChangedListener listener)
            throws SecurityException {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);

        mNonDefDevListenerMgr.addListener(
                executor, listener, "addOnNonDefaultDevicesForStrategyChangedListener",
                () -> new StrategyNonDefaultDevicesDispatcherStub());
    }

    /**
     * @hide
     * Removes a previously added listener of changes to the non-default audio device for
     * strategies.
     * @param listener
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void removeOnNonDefaultDevicesForStrategyChangedListener(
            @NonNull OnNonDefaultDevicesForStrategyChangedListener listener) {
        Objects.requireNonNull(listener);
        mNonDefDevListenerMgr.removeListener(
                listener, "removeOnNonDefaultDevicesForStrategyChangedListener");
    }

    /**
     * Manages the OnPreferredDevicesForStrategyChangedListener listeners and the
     * StrategyPreferredDevicesDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnPreferredDevicesForStrategyChangedListener>
            mPrefDevListenerMgr = new CallbackUtil.LazyListenerManager();

    /**
     * Manages the OnNonDefaultDevicesForStrategyChangedListener listeners and the
     * StrategyNonDefaultDevicesDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnNonDefaultDevicesForStrategyChangedListener>
            mNonDefDevListenerMgr = new CallbackUtil.LazyListenerManager();

    private final class StrategyPreferredDevicesDispatcherStub
            extends IStrategyPreferredDevicesDispatcher.Stub
            implements CallbackUtil.DispatcherStub {

        @Override
        public void dispatchPrefDevicesChanged(int strategyId,
                                               @NonNull List<AudioDeviceAttributes> devices) {
            final AudioProductStrategy strategy =
                    AudioProductStrategy.getAudioProductStrategyWithId(strategyId);

            mPrefDevListenerMgr.callListeners(
                    (listener) -> listener.onPreferredDevicesForStrategyChanged(strategy, devices));
        }

        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    getService().registerStrategyPreferredDevicesDispatcher(this);
                } else {
                    getService().unregisterStrategyPreferredDevicesDispatcher(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    private final class StrategyNonDefaultDevicesDispatcherStub
            extends IStrategyNonDefaultDevicesDispatcher.Stub
            implements CallbackUtil.DispatcherStub {

        @Override
        public void dispatchNonDefDevicesChanged(int strategyId,
                                                 @NonNull List<AudioDeviceAttributes> devices) {
            final AudioProductStrategy strategy =
                    AudioProductStrategy.getAudioProductStrategyWithId(strategyId);

            mNonDefDevListenerMgr.callListeners(
                    (listener) -> listener.onNonDefaultDevicesForStrategyChanged(
                            strategy, devices));
        }

        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    getService().registerStrategyNonDefaultDevicesDispatcher(this);
                } else {
                    getService().unregisterStrategyNonDefaultDevicesDispatcher(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    //====================================================================
    // Audio Capture Preset routing

    /**
     * @hide
     * Set the preferred device for a given capture preset, i.e. the audio routing to be used by
     * this capture preset. Note that the device may not be available at the time the preferred
     * device is set, but it will be used once made available.
     * <p>Use {@link #clearPreferredDevicesForCapturePreset(int)} to cancel setting this preference
     * for this capture preset.</p>
     * @param capturePreset the audio capture preset whose routing will be affected
     * @param device the audio device to route to when available
     * @return true if the operation was successful, false otherwise
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean setPreferredDeviceForCapturePreset(@MediaRecorder.SystemSource int capturePreset,
                                                      @NonNull AudioDeviceAttributes device) {
        return setPreferredDevicesForCapturePreset(capturePreset, Arrays.asList(device));
    }

    /**
     * @hide
     * Remove all the preferred audio devices previously set
     * @param capturePreset the audio capture preset whose routing will be affected
     * @return true if the operation was successful, false otherwise (invalid capture preset, or no
     *     device set for example)
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean clearPreferredDevicesForCapturePreset(
            @MediaRecorder.SystemSource int capturePreset) {
        if (!MediaRecorder.isValidAudioSource(capturePreset)) {
            return false;
        }
        try {
            final int status = getService().clearPreferredDevicesForCapturePreset(capturePreset);
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return the preferred devices for an audio capture preset, previously set with
     * {@link #setPreferredDeviceForCapturePreset(int, AudioDeviceAttributes)}
     * @param capturePreset the capture preset to query
     * @return a list that contains preferred devices for that capture preset.
     */
    @NonNull
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public List<AudioDeviceAttributes> getPreferredDevicesForCapturePreset(
            @MediaRecorder.SystemSource int capturePreset) {
        if (!MediaRecorder.isValidAudioSource(capturePreset)) {
            return new ArrayList<AudioDeviceAttributes>();
        }
        try {
            return getService().getPreferredDevicesForCapturePreset(capturePreset);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private boolean setPreferredDevicesForCapturePreset(
            @MediaRecorder.SystemSource int capturePreset,
            @NonNull List<AudioDeviceAttributes> devices) {
        Objects.requireNonNull(devices);
        if (!MediaRecorder.isValidAudioSource(capturePreset)) {
            return false;
        }
        if (devices.size() != 1) {
            throw new IllegalArgumentException(
                    "Only support setting one preferred devices for capture preset");
        }
        for (AudioDeviceAttributes device : devices) {
            Objects.requireNonNull(device);
        }
        try {
            final int status =
                    getService().setPreferredDevicesForCapturePreset(capturePreset, devices);
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Interface to be notified of changes in the preferred audio devices set for a given capture
     * preset.
     * <p>Note that this listener will only be invoked whenever
     * {@link #setPreferredDeviceForCapturePreset(int, AudioDeviceAttributes)} or
     * {@link #clearPreferredDevicesForCapturePreset(int)} causes a change in
     * preferred device. It will not be invoked directly after registration with
     * {@link #addOnPreferredDevicesForCapturePresetChangedListener(
     * Executor, OnPreferredDevicesForCapturePresetChangedListener)}
     * to indicate which strategies had preferred devices at the time of registration.</p>
     * @see #setPreferredDeviceForCapturePreset(int, AudioDeviceAttributes)
     * @see #clearPreferredDevicesForCapturePreset(int)
     * @see #getPreferredDevicesForCapturePreset(int)
     */
    @SystemApi
    public interface OnPreferredDevicesForCapturePresetChangedListener {
        /**
         * Called on the listener to indicate that the preferred audio devices for the given
         * capture preset has changed.
         * @param capturePreset the capture preset whose preferred device changed
         * @param devices a list of newly set preferred audio devices
         */
        void onPreferredDevicesForCapturePresetChanged(
                @MediaRecorder.SystemSource int capturePreset,
                @NonNull List<AudioDeviceAttributes> devices);
    }

    /**
     * @hide
     * Adds a listener for being notified of changes to the capture-preset-preferred audio device.
     * @param executor
     * @param listener
     * @throws SecurityException if the caller doesn't hold the required permission
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void addOnPreferredDevicesForCapturePresetChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPreferredDevicesForCapturePresetChangedListener listener)
            throws SecurityException {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        int status = addOnDevRoleForCapturePresetChangedListener(
                executor, listener, AudioSystem.DEVICE_ROLE_PREFERRED);
        if (status == AudioSystem.ERROR) {
            // This must not happen
            throw new RuntimeException("Unknown error happened");
        }
        if (status == AudioSystem.BAD_VALUE) {
            throw new IllegalArgumentException(
                    "attempt to call addOnPreferredDevicesForCapturePresetChangedListener() "
                            + "on a previously registered listener");
        }
    }

    /**
     * @hide
     * Removes a previously added listener of changes to the capture-preset-preferred audio device.
     * @param listener
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void removeOnPreferredDevicesForCapturePresetChangedListener(
            @NonNull OnPreferredDevicesForCapturePresetChangedListener listener) {
        Objects.requireNonNull(listener);
        int status = removeOnDevRoleForCapturePresetChangedListener(
                listener, AudioSystem.DEVICE_ROLE_PREFERRED);
        if (status == AudioSystem.ERROR) {
            // This must not happen
            throw new RuntimeException("Unknown error happened");
        }
        if (status == AudioSystem.BAD_VALUE) {
            throw new IllegalArgumentException(
                    "attempt to call removeOnPreferredDevicesForCapturePresetChangedListener() "
                            + "on an unregistered listener");
        }
    }

    private <T> int addOnDevRoleForCapturePresetChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull T listener, int deviceRole) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        DevRoleListeners<T> devRoleListeners =
                (DevRoleListeners<T>) mDevRoleForCapturePresetListeners.get(deviceRole);
        if (devRoleListeners == null) {
            return AudioSystem.ERROR;
        }
        synchronized (devRoleListeners.mDevRoleListenersLock) {
            if (devRoleListeners.hasDevRoleListener(listener)) {
                return AudioSystem.BAD_VALUE;
            }
            // lazy initialization of the list of device role listener
            if (devRoleListeners.mListenerInfos == null) {
                devRoleListeners.mListenerInfos = new ArrayList<>();
            }
            final int oldCbCount = devRoleListeners.mListenerInfos.size();
            devRoleListeners.mListenerInfos.add(new DevRoleListenerInfo<T>(executor, listener));
            if (oldCbCount == 0 && devRoleListeners.mListenerInfos.size() > 0) {
                // register binder for callbacks
                synchronized (mDevRoleForCapturePresetListenersLock) {
                    int deviceRoleListenerStatus = mDeviceRoleListenersStatus;
                    mDeviceRoleListenersStatus |= (1 << deviceRole);
                    if (deviceRoleListenerStatus != 0) {
                        // There are already device role changed listeners active.
                        return AudioSystem.SUCCESS;
                    }
                    if (mDevicesRoleForCapturePresetDispatcherStub == null) {
                        mDevicesRoleForCapturePresetDispatcherStub =
                                new CapturePresetDevicesRoleDispatcherStub();
                    }
                    try {
                        getService().registerCapturePresetDevicesRoleDispatcher(
                                mDevicesRoleForCapturePresetDispatcherStub);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }
        return AudioSystem.SUCCESS;
    }

    private <T> int removeOnDevRoleForCapturePresetChangedListener(
            @NonNull T listener, int deviceRole) {
        Objects.requireNonNull(listener);
        DevRoleListeners<T> devRoleListeners =
                (DevRoleListeners<T>) mDevRoleForCapturePresetListeners.get(deviceRole);
        if (devRoleListeners == null) {
            return AudioSystem.ERROR;
        }
        synchronized (devRoleListeners.mDevRoleListenersLock) {
            if (!devRoleListeners.removeDevRoleListener(listener)) {
                return AudioSystem.BAD_VALUE;
            }
            if (devRoleListeners.mListenerInfos.size() == 0) {
                // unregister binder for callbacks
                synchronized (mDevRoleForCapturePresetListenersLock) {
                    mDeviceRoleListenersStatus ^= (1 << deviceRole);
                    if (mDeviceRoleListenersStatus != 0) {
                        // There are some other device role changed listeners active.
                        return AudioSystem.SUCCESS;
                    }
                    try {
                        getService().unregisterCapturePresetDevicesRoleDispatcher(
                                mDevicesRoleForCapturePresetDispatcherStub);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }
        return AudioSystem.SUCCESS;
    }

    private final Map<Integer, Object> mDevRoleForCapturePresetListeners = Map.of(
            AudioSystem.DEVICE_ROLE_PREFERRED,
            new DevRoleListeners<OnPreferredDevicesForCapturePresetChangedListener>());

    private class DevRoleListenerInfo<T> {
        final @NonNull Executor mExecutor;
        final @NonNull T mListener;
        DevRoleListenerInfo(Executor executor, T listener) {
            mExecutor = executor;
            mListener = listener;
        }
    }

    private class DevRoleListeners<T> {
        private final Object mDevRoleListenersLock = new Object();
        @GuardedBy("mDevRoleListenersLock")
        private @Nullable ArrayList<DevRoleListenerInfo<T>> mListenerInfos;

        @GuardedBy("mDevRoleListenersLock")
        private @Nullable DevRoleListenerInfo<T> getDevRoleListenerInfo(T listener) {
            if (mListenerInfos == null) {
                return null;
            }
            for (DevRoleListenerInfo<T> listenerInfo : mListenerInfos) {
                if (listenerInfo.mListener == listener) {
                    return listenerInfo;
                }
            }
            return null;
        }

        @GuardedBy("mDevRoleListenersLock")
        private boolean hasDevRoleListener(T listener) {
            return getDevRoleListenerInfo(listener) != null;
        }

        @GuardedBy("mDevRoleListenersLock")
        private boolean removeDevRoleListener(T listener) {
            final DevRoleListenerInfo<T> infoToRemove = getDevRoleListenerInfo(listener);
            if (infoToRemove != null) {
                mListenerInfos.remove(infoToRemove);
                return true;
            }
            return false;
        }
    }

    private final Object mDevRoleForCapturePresetListenersLock = new Object();
    /**
     * Record if there is a listener added for device role change. If there is a listener added for
     * a specified device role change, the bit at position `1 << device_role` is set.
     */
    @GuardedBy("mDevRoleForCapturePresetListenersLock")
    private int mDeviceRoleListenersStatus = 0;
    @GuardedBy("mDevRoleForCapturePresetListenersLock")
    private CapturePresetDevicesRoleDispatcherStub mDevicesRoleForCapturePresetDispatcherStub;

    private final class CapturePresetDevicesRoleDispatcherStub
            extends ICapturePresetDevicesRoleDispatcher.Stub {

        @Override
        public void dispatchDevicesRoleChanged(
                int capturePreset, int role, List<AudioDeviceAttributes> devices) {
            final Object listenersObj = mDevRoleForCapturePresetListeners.get(role);
            if (listenersObj == null) {
                return;
            }
            switch (role) {
                case AudioSystem.DEVICE_ROLE_PREFERRED: {
                    final DevRoleListeners<OnPreferredDevicesForCapturePresetChangedListener>
                            listeners =
                            (DevRoleListeners<OnPreferredDevicesForCapturePresetChangedListener>)
                            listenersObj;
                    final ArrayList<DevRoleListenerInfo<
                            OnPreferredDevicesForCapturePresetChangedListener>> prefDevListeners;
                    synchronized (listeners.mDevRoleListenersLock) {
                        if (listeners.mListenerInfos.isEmpty()) {
                            return;
                        }
                        prefDevListeners = (ArrayList<DevRoleListenerInfo<
                                OnPreferredDevicesForCapturePresetChangedListener>>)
                                listeners.mListenerInfos.clone();
                    }
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        for (DevRoleListenerInfo<
                                OnPreferredDevicesForCapturePresetChangedListener> info :
                                prefDevListeners) {
                            info.mExecutor.execute(() ->
                                    info.mListener.onPreferredDevicesForCapturePresetChanged(
                                            capturePreset, devices));
                        }
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                } break;
                default:
                    break;
            }
        }
    }

    //====================================================================
    // Direct playback query

    /** Return value for {@link #getDirectPlaybackSupport(AudioFormat, AudioAttributes)}:
        direct playback not supported. */
    public static final int DIRECT_PLAYBACK_NOT_SUPPORTED = AudioSystem.DIRECT_NOT_SUPPORTED;
    /** Return value for {@link #getDirectPlaybackSupport(AudioFormat, AudioAttributes)}:
        direct offload playback supported. Compressed offload is a variant of direct playback.
        It is the feature that allows audio processing tasks to be done on the Android device but
        not on the application processor, instead, it is handled by dedicated hardware such as audio
        DSPs. That will allow the application processor to be idle as much as possible, which is
        good for power saving. Compressed offload playback supports
        {@link AudioTrack.StreamEventCallback} for event notifications. */
    public static final int DIRECT_PLAYBACK_OFFLOAD_SUPPORTED =
            AudioSystem.DIRECT_OFFLOAD_SUPPORTED;
    /** Return value for {@link #getDirectPlaybackSupport(AudioFormat, AudioAttributes)}:
        direct offload playback supported with gapless transitions. Compressed offload is a variant
        of direct playback. It is the feature that allows audio processing tasks to be done on the
        Android device but not on the application processor, instead, it is handled by dedicated
        hardware such as audio DSPs. That will allow the application processor to be idle as much as
        possible, which is good for power saving. Compressed offload playback supports
        {@link AudioTrack.StreamEventCallback} for event notifications. Gapless transitions
        indicates the ability to play consecutive audio tracks without an audio silence in
        between. */
    public static final int DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED =
            AudioSystem.DIRECT_OFFLOAD_GAPLESS_SUPPORTED;
    /** Return value for {@link #getDirectPlaybackSupport(AudioFormat, AudioAttributes)}:
        direct playback supported. This value covers direct playback that is bitstream pass-through
        such as compressed pass-through. */
    public static final int DIRECT_PLAYBACK_BITSTREAM_SUPPORTED =
            AudioSystem.DIRECT_BITSTREAM_SUPPORTED;

    /** @hide */
    @IntDef(flag = true, prefix = "DIRECT_PLAYBACK_", value = {
            DIRECT_PLAYBACK_NOT_SUPPORTED,
            DIRECT_PLAYBACK_OFFLOAD_SUPPORTED,
            DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED,
            DIRECT_PLAYBACK_BITSTREAM_SUPPORTED}
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioDirectPlaybackMode {}

    /**
     * Returns a bitfield representing the different forms of direct playback currently available
     * for a given audio format.
     * <p>Direct playback means that the audio stream is not altered by the framework. The audio
     * stream will not be resampled, volume scaled, downmixed or mixed with other content by
     * the framework. But it may be wrapped in a higher level protocol such as IEC61937 for
     * passthrough.
     * <p>Checking for direct support can help the app select the representation of audio content
     * that most closely matches the capabilities of the device and peripherals (e.g. A/V receiver)
     * connected to it. Note that the provided stream can still be re-encoded or mixed with other
     * streams, if needed.
     * @param format the audio format (codec, sample rate, channels) being checked.
     * @param attributes the {@link AudioAttributes} to be used for playback
     * @return the direct playback mode available with given format and attributes. The returned
     *         value will be {@link #DIRECT_PLAYBACK_NOT_SUPPORTED} or a combination of
     *         {@link #DIRECT_PLAYBACK_OFFLOAD_SUPPORTED},
     *         {@link #DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED} and
     *         {@link #DIRECT_PLAYBACK_BITSTREAM_SUPPORTED}. Note that if
     *         {@link #DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED} is present in the returned value,
     *         then {@link #DIRECT_PLAYBACK_OFFLOAD_SUPPORTED} will be too.
     */
    @AudioDirectPlaybackMode
    public static int getDirectPlaybackSupport(@NonNull AudioFormat format,
                                               @NonNull AudioAttributes attributes) {
        Objects.requireNonNull(format);
        Objects.requireNonNull(attributes);
        return AudioSystem.getDirectPlaybackSupport(format, attributes);
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
        return AudioSystem.getOffloadSupport(format, attributes) != PLAYBACK_OFFLOAD_NOT_SUPPORTED;
    }

    /** Return value for {@link #getPlaybackOffloadSupport(AudioFormat, AudioAttributes)}:
        offload playback not supported */
    public static final int PLAYBACK_OFFLOAD_NOT_SUPPORTED = AudioSystem.OFFLOAD_NOT_SUPPORTED;
    /** Return value for {@link #getPlaybackOffloadSupport(AudioFormat, AudioAttributes)}:
        offload playback supported */
    public static final int PLAYBACK_OFFLOAD_SUPPORTED = AudioSystem.OFFLOAD_SUPPORTED;
    /** Return value for {@link #getPlaybackOffloadSupport(AudioFormat, AudioAttributes)}:
        offload playback supported with gapless transitions */
    public static final int PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED =
            AudioSystem.OFFLOAD_GAPLESS_SUPPORTED;

    /** @hide */
    @IntDef(flag = false, prefix = "PLAYBACK_OFFLOAD_", value = {
            PLAYBACK_OFFLOAD_NOT_SUPPORTED,
            PLAYBACK_OFFLOAD_SUPPORTED,
            PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioOffloadMode {}

    /**
     * Returns whether offloaded playback of an audio format is supported on the device or not and
     * when supported whether gapless transitions are possible or not.
     * <p>Offloaded playback is the feature where the decoding and playback of an audio stream
     * is not competing with other software resources. In general, it is supported by dedicated
     * hardware, such as audio DSPs.
     * <p>Note that this query only provides information about the support of an audio format,
     * it does not indicate whether the resources necessary for the offloaded playback are
     * available at that instant.
     * @param format the audio format (codec, sample rate, channels) being checked.
     * @param attributes the {@link AudioAttributes} to be used for playback
     * @return {@link #PLAYBACK_OFFLOAD_NOT_SUPPORTED} if offload playback if not supported,
     *         {@link #PLAYBACK_OFFLOAD_SUPPORTED} if offload playback is supported or
     *         {@link #PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED} if gapless transitions are
     *         also supported.
     * @deprecated Use {@link #getDirectPlaybackSupport(AudioFormat, AudioAttributes)} instead
     */
    @Deprecated
    @AudioOffloadMode
    public static int getPlaybackOffloadSupport(@NonNull AudioFormat format,
            @NonNull AudioAttributes attributes) {
        if (format == null) {
            throw new NullPointerException("Illegal null AudioFormat");
        }
        if (attributes == null) {
            throw new NullPointerException("Illegal null AudioAttributes");
        }
        return AudioSystem.getOffloadSupport(format, attributes);
    }

    //====================================================================
    // Immersive audio

    /**
     * Return a handle to the optional platform's {@link Spatializer}
     * @return the {@code Spatializer} instance.
     * @see Spatializer#getImmersiveAudioLevel() to check for the level of support of the effect
     *   on the platform
     */
    public @NonNull Spatializer getSpatializer() {
        return new Spatializer(this);
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
     *   {@link Manifest.permission#MODIFY_AUDIO_SETTINGS}.
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
     * @deprecated Use {@link AudioManager#setCommunicationDevice(AudioDeviceInfo)} instead.
     */
    @Deprecated public void startBluetoothSco() {
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
     *   {@link Manifest.permission#MODIFY_AUDIO_SETTINGS}.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     *   {@link Manifest.permission#MODIFY_AUDIO_SETTINGS}.
     * <p>This method must be called by applications having requested the use of
     * bluetooth SCO audio with {@link #startBluetoothSco()} when finished with the SCO
     * connection or if connection fails.
     * @see #startBluetoothSco()
     * @deprecated Use {@link AudioManager#clearCommunicationDevice()} instead.
     */
    // Also used for connections started with {@link #startBluetoothScoVirtualCall()}
    @Deprecated public void stopBluetoothSco() {
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
     * @deprecated Use {@link AudioManager#getCommunicationDevice()} instead.
     */
    @Deprecated public boolean isBluetoothScoOn() {
        final IAudioService service = getService();
        try {
            return service.isBluetoothScoOn();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Use {@link MediaRouter#selectRoute} instead.
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
                    UserHandle.getCallingUserId(), getContext().getAttributionTag());
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     * This change id controls use of audio modes for call audio redirection.
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long CALL_REDIRECTION_AUDIO_MODES = 189472651L; // buganizer id

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
            } else if (mode == MODE_CALL_REDIRECT
                    && !CompatChanges.isChangeEnabled(CALL_REDIRECTION_AUDIO_MODES)) {
                mode = MODE_IN_CALL;
            } else if (mode == MODE_COMMUNICATION_REDIRECT
                    && !CompatChanges.isChangeEnabled(CALL_REDIRECTION_AUDIO_MODES)) {
                mode = MODE_IN_COMMUNICATION;
            }
            return mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Interface definition of a callback that is notified when the audio mode changes
     */
    public interface OnModeChangedListener {
        /**
         * Called on the listener to indicate that the audio mode has changed
         *
         * @param mode The current audio mode
         */
        void onModeChanged(@AudioMode int mode);
    }

    /**
     * manages the OnModeChangedListener listeners and the ModeDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnModeChangedListener> mModeChangedListenerMgr =
            new CallbackUtil.LazyListenerManager();


    final class ModeDispatcherStub extends IAudioModeDispatcher.Stub
            implements CallbackUtil.DispatcherStub {

        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    getService().registerModeDispatcher(this);
                } else {
                    getService().unregisterModeDispatcher(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchAudioModeChanged(int mode) {
            mModeChangedListenerMgr.callListeners((listener) -> listener.onModeChanged(mode));
        }
    }

    /**
     * Adds a listener to be notified of changes to the audio mode.
     * See {@link #getMode()}
     * @param executor
     * @param listener
     */
    public void addOnModeChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnModeChangedListener listener) {
        mModeChangedListenerMgr.addListener(executor, listener, "addOnModeChangedListener",
                () -> new ModeDispatcherStub());
    }

    /**
     * Removes a previously added listener for changes to audio mode.
     * See {@link #getMode()}
     * @param listener
     */
    public void removeOnModeChangedListener(@NonNull OnModeChangedListener listener) {
        mModeChangedListenerMgr.removeListener(listener, "removeOnModeChangedListener");
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
     * Audio hardware modes.
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

    /**
     * A telephony call is established and its audio is being redirected to another device.
     */
    public static final int MODE_CALL_REDIRECT   = AudioSystem.MODE_CALL_REDIRECT;

    /**
     * An audio/video chat or VoIP call is established and its audio is being redirected to another
     * device.
     */
    public static final int MODE_COMMUNICATION_REDIRECT = AudioSystem.MODE_COMMUNICATION_REDIRECT;

    /** @hide */
    @IntDef(flag = false, prefix = "MODE_", value = {
            MODE_NORMAL,
            MODE_RINGTONE,
            MODE_IN_CALL,
            MODE_IN_COMMUNICATION,
            MODE_CALL_SCREENING,
            MODE_CALL_REDIRECT,
            MODE_COMMUNICATION_REDIRECT}
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
        final IAudioService service = getService();
        try {
            return service.isMusicActive(false /*remotely*/);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Checks whether any music or media is actively playing on a remote device (e.g. wireless
     *   display). Note that BT audio sinks are not considered remote devices.
     * @return true if {@link AudioManager#STREAM_MUSIC} is active on a remote device
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isMusicActiveRemotely() {
        final IAudioService service = getService();
        try {
            return service.isMusicActive(true /*remotely*/);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.BLUETOOTH_STACK)
    public void setHfpEnabled(boolean enable) {
        AudioSystem.setParameters("hfp_enable=" + enable);
    }

    /**
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.BLUETOOTH_STACK)
    public void setHfpVolume(int volume) {
        AudioSystem.setParameters("hfp_volume=" + volume);
    }

    /**
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.BLUETOOTH_STACK)
    public void setHfpSamplingRate(int rate) {
        AudioSystem.setParameters("hfp_set_sampling_rate=" + rate);
    }

    /**
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.BLUETOOTH_STACK)
    public void setBluetoothHeadsetProperties(@NonNull String name, boolean hasNrecEnabled,
            boolean hasWbsEnabled) {
        AudioSystem.setParameters("bt_headset_name=" + name
                + ";bt_headset_nrec=" + (hasNrecEnabled ? "on" : "off")
                + ";bt_wbs=" + (hasWbsEnabled ? "on" : "off"));
    }

    /**
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.BLUETOOTH_STACK)
    public void setA2dpSuspended(boolean enable) {
        final IAudioService service = getService();
        try {
            service.setA2dpSuspended(enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Suspends the use of LE Audio.
     *
     * @param enable {@code true} to suspend le audio, {@code false} to unsuspend
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.BLUETOOTH_STACK)
    public void setLeAudioSuspended(boolean enable) {
        final IAudioService service = getService();
        try {
            service.setLeAudioSuspended(enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Back sound
     * @see #playSoundEffect(int)
     */
    public static final int FX_BACK = 10;

    /**
     * @hide Home sound
     * <p>
     * To be played by the framework when the home app becomes active if config_enableHomeSound is
     * set to true. This is currently only used on TV devices.
     * Note that this sound is only available if a sound file is specified in audio_assets.xml.
     * @see #playSoundEffect(int)
     */
    public static final int FX_HOME = 11;

    /**
     * @hide Navigation repeat sound 1
     * <p>
     * To be played by the framework when a focus navigation is repeatedly triggered
     * (e.g. due to long-pressing) and {@link #areNavigationRepeatSoundEffectsEnabled()} is true.
     * This is currently only used on TV devices.
     * Note that this sound is only available if a sound file is specified in audio_assets.xml
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_REPEAT_1 = 12;

    /**
     * @hide Navigation repeat sound 2
     * <p>
     * To be played by the framework when a focus navigation is repeatedly triggered
     * (e.g. due to long-pressing) and {@link #areNavigationRepeatSoundEffectsEnabled()} is true.
     * This is currently only used on TV devices.
     * Note that this sound is only available if a sound file is specified in audio_assets.xml
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_REPEAT_2 = 13;

    /**
     * @hide Navigation repeat sound 3
     * <p>
     * To be played by the framework when a focus navigation is repeatedly triggered
     * (e.g. due to long-pressing) and {@link #areNavigationRepeatSoundEffectsEnabled()} is true.
     * This is currently only used on TV devices.
     * Note that this sound is only available if a sound file is specified in audio_assets.xml
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_REPEAT_3 = 14;

    /**
     * @hide Navigation repeat sound 4
     * <p>
     * To be played by the framework when a focus navigation is repeatedly triggered
     * (e.g. due to long-pressing) and {@link #areNavigationRepeatSoundEffectsEnabled()} is true.
     * This is currently only used on TV devices.
     * Note that this sound is only available if a sound file is specified in audio_assets.xml
     * @see #playSoundEffect(int)
     */
    public static final int FX_FOCUS_NAVIGATION_REPEAT_4 = 15;

    /**
     * @hide Number of sound effects
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int NUM_SOUND_EFFECTS = 16;

    /** @hide */
    @IntDef(prefix = { "FX_" }, value = {
            FX_KEY_CLICK,
            FX_FOCUS_NAVIGATION_UP,
            FX_FOCUS_NAVIGATION_DOWN,
            FX_FOCUS_NAVIGATION_LEFT,
            FX_FOCUS_NAVIGATION_RIGHT,
            FX_KEYPRESS_STANDARD,
            FX_KEYPRESS_SPACEBAR,
            FX_KEYPRESS_DELETE,
            FX_KEYPRESS_RETURN,
            FX_KEYPRESS_INVALID,
            FX_BACK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemSoundEffect {}

    /**
     * @hide Number of FX_FOCUS_NAVIGATION_REPEAT_* sound effects
     */
    public static final int NUM_NAVIGATION_REPEAT_SOUND_EFFECTS = 4;

    /**
     * @hide
     * @param n a value in [0, {@link #NUM_NAVIGATION_REPEAT_SOUND_EFFECTS}[
     * @return The id of a navigation repeat sound effect or -1 if out of bounds
     */
    public static int getNthNavigationRepeatSoundEffect(int n) {
        switch (n) {
            case 0:
                return FX_FOCUS_NAVIGATION_REPEAT_1;
            case 1:
                return FX_FOCUS_NAVIGATION_REPEAT_2;
            case 2:
                return FX_FOCUS_NAVIGATION_REPEAT_3;
            case 3:
                return FX_FOCUS_NAVIGATION_REPEAT_4;
            default:
                Log.w(TAG, "Invalid navigation repeat sound effect id: " + n);
                return -1;
        }
    }

    /**
     * @hide
     */
    public void setNavigationRepeatSoundEffectsEnabled(boolean enabled) {
        try {
            getService().setNavigationRepeatSoundEffectsEnabled(enabled);
        } catch (RemoteException e) {

        }
    }

    /**
     * @hide
     * @return true if the navigation repeat sound effects are enabled
     */
    public boolean areNavigationRepeatSoundEffectsEnabled() {
        try {
            return getService().areNavigationRepeatSoundEffectsEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @param enabled
     */
    public void setHomeSoundEffectEnabled(boolean enabled) {
        try {
            getService().setHomeSoundEffectEnabled(enabled);
        } catch (RemoteException e) {

        }
    }

    /**
     * @hide
     * @return true if the home sound effect is enabled
     */
    public boolean isHomeSoundEffectEnabled() {
        try {
            return getService().isHomeSoundEffectEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Plays a sound effect (Key clicks, lid open/close...)
     * @param effectType The type of sound effect.
     * NOTE: This version uses the UI settings to determine
     * whether sounds are heard or not.
     */
    public void playSoundEffect(@SystemSoundEffect int effectType) {
        playSoundEffect(effectType, UserHandle.USER_CURRENT);
    }

    /**
     * Plays a sound effect (Key clicks, lid open/close...)
     * @param effectType The type of sound effect.
     * @param userId The current user to pull sound settings from
     * NOTE: This version uses the UI settings to determine
     * whether sounds are heard or not.
     * @hide
     */
    public void playSoundEffect(@SystemSoundEffect int effectType, int userId) {
        if (effectType < 0 || effectType >= NUM_SOUND_EFFECTS) {
            return;
        }

        if (delegateSoundEffectToVdm(effectType)) {
            return;
        }

        final IAudioService service = getService();
        try {
            service.playSoundEffect(effectType, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Plays a sound effect (Key clicks, lid open/close...)
     * @param effectType The type of sound effect.
     * @param volume Sound effect volume.
     * The volume value is a raw scalar so UI controls should be scaled logarithmically.
     * If a volume of -1 is specified, the AudioManager.STREAM_MUSIC stream volume minus 3dB will be used.
     * NOTE: This version is for applications that have their own
     * settings panel for enabling and controlling volume.
     */
    public void playSoundEffect(@SystemSoundEffect int effectType, float volume) {
        if (effectType < 0 || effectType >= NUM_SOUND_EFFECTS) {
            return;
        }

        if (delegateSoundEffectToVdm(effectType)) {
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
     * Checks whether this {@link AudioManager} instance is associated with {@link VirtualDevice}
     * configured with custom device policy for audio. If there is such device, request to play
     * sound effect is forwarded to {@link VirtualDeviceManager}.
     *
     * @param effectType - The type of sound effect.
     * @return true if the request was forwarded to {@link VirtualDeviceManager} instance,
     * false otherwise.
     */
    private boolean delegateSoundEffectToVdm(@SystemSoundEffect int effectType) {
        if (hasCustomPolicyVirtualDeviceContext()) {
            VirtualDeviceManager vdm = getVirtualDeviceManager();
            if (vdm != null) {
                vdm.playSoundEffect(mOriginalContextDeviceId, effectType);
                return true;
            }
        }
        return false;
    }

    private boolean hasCustomPolicyVirtualDeviceContext() {
        if (mOriginalContextDeviceId == DEVICE_ID_DEFAULT) {
            return false;
        }

        VirtualDeviceManager vdm = getVirtualDeviceManager();
        return vdm != null && vdm.getDevicePolicy(mOriginalContextDeviceId, POLICY_TYPE_AUDIO)
                != DEVICE_POLICY_DEFAULT;
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
     * @hide
     */
    public static String audioFocusToString(int focus) {
        switch (focus) {
            case AUDIOFOCUS_NONE:
                return "AUDIOFOCUS_NONE";
            case AUDIOFOCUS_GAIN:
                return "AUDIOFOCUS_GAIN";
            case AUDIOFOCUS_GAIN_TRANSIENT:
                return "AUDIOFOCUS_GAIN_TRANSIENT";
            case AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                return "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
            case AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                return "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
            case AUDIOFOCUS_LOSS:
                return "AUDIOFOCUS_LOSS";
            case AUDIOFOCUS_LOSS_TRANSIENT:
                return "AUDIOFOCUS_LOSS_TRANSIENT";
            case AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: // Note CAN_DUCK not MAY_DUCK.
                return "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
            default:
                return "AUDIO_FOCUS_UNKNOWN(" + focus + ")";
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
                                        Slog.i(TAG, "dispatching onAudioFocusChange("
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
    private static final int EXT_FOCUS_POLICY_TIMEOUT_MS = 250;

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

    /**
     * @hide
     * flag set on test API calls,
     * see {@link #requestAudioFocusForTest(AudioFocusRequest, String, int, int)},
     */
    public static final int AUDIOFOCUS_FLAG_TEST = 0x1 << 3;
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
     * @param focusReqType use {@link #AUDIOFOCUS_GAIN_TRANSIENT} to indicate this focus request
     *      is temporary, and focus will be abandoned shortly. Examples of transient requests are
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
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public int requestAudioFocus(OnAudioFocusChangeListener l,
            @NonNull AudioAttributes requestAttributes,
            int focusReqType,
            int flags) throws IllegalArgumentException {
        if (flags != (flags & AUDIOFOCUS_FLAGS_APPS)) {
            throw new IllegalArgumentException("Invalid flags 0x"
                    + Integer.toHexString(flags).toUpperCase());
        }
        return requestAudioFocus(l, requestAttributes, focusReqType,
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
     * @param focusReqType see the description of the same parameter in
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
            Manifest.permission.MODIFY_PHONE_STATE,
            Manifest.permission.MODIFY_AUDIO_ROUTING
    })
    public int requestAudioFocus(OnAudioFocusChangeListener l,
            @NonNull AudioAttributes requestAttributes,
            int focusReqType,
            int flags,
            AudioPolicy ap) throws IllegalArgumentException {
        // parameter checking
        if (requestAttributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes argument");
        }
        if (!AudioFocusRequest.isValidFocusGain(focusReqType)) {
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

        final AudioFocusRequest afr = new AudioFocusRequest.Builder(focusReqType)
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
     * Test API to request audio focus for an arbitrary client operating from a (fake) given UID.
     * Used to simulate conditions of the test, not the behavior of the focus requester under test.
     * @param afr the parameters of the request
     * @param clientFakeId the identifier of the AudioManager the client would be requesting from
     * @param clientFakeUid the UID of the client, here an arbitrary int,
     *                      doesn't have to be a real UID
     * @param clientTargetSdk the target SDK used by the client
     * @return return code indicating status of the request
     */
    @TestApi
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
    public @FocusRequestResult int requestAudioFocusForTest(@NonNull AudioFocusRequest afr,
            @NonNull String clientFakeId, int clientFakeUid, int clientTargetSdk) {
        Objects.requireNonNull(afr);
        Objects.requireNonNull(clientFakeId);
        int status;
        BlockingFocusResultReceiver focusReceiver;
        synchronized (mFocusRequestsLock) {
            try {
                status = getService().requestAudioFocusForTest(afr.getAudioAttributes(),
                        afr.getFocusGain(),
                        mICallBack,
                        mAudioFocusDispatcher,
                        clientFakeId, "com.android.test.fakeclient",
                        afr.getFlags() | AudioManager.AUDIOFOCUS_FLAG_TEST,
                        clientFakeUid, clientTargetSdk);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (status != AudioManager.AUDIOFOCUS_REQUEST_WAITING_FOR_EXT_POLICY) {
                // default path with no external focus policy
                return status;
            }

            focusReceiver = addClientIdToFocusReceiverLocked(clientFakeId);
        }

        return handleExternalAudioPolicyWaitIfNeeded(clientFakeId, focusReceiver);
    }

    /**
     * @hide
     * Test API to abandon audio focus for an arbitrary client.
     * Used to simulate conditions of the test, not the behavior of the focus requester under test.
     * @param afr the parameters used for the request
     * @param clientFakeId clientFakeId the identifier of the AudioManager from which the client
     *      would be requesting
     * @return return code indicating status of the request
     */
    @TestApi
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
    public @FocusRequestResult int abandonAudioFocusForTest(@NonNull AudioFocusRequest afr,
            @NonNull String clientFakeId) {
        Objects.requireNonNull(afr);
        Objects.requireNonNull(clientFakeId);
        try {
            return getService().abandonAudioFocusForTest(mAudioFocusDispatcher,
                    clientFakeId, afr.getAudioAttributes(), "com.android.test.fakeclient");
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return the duration of the fade out applied when a player of the given AudioAttributes
     * is losing audio focus
     * @param aa the AudioAttributes of the player losing focus with {@link #AUDIOFOCUS_LOSS}
     * @return a duration in ms, 0 indicates no fade out is applied
     */
    @TestApi
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
    public @IntRange(from = 0) long getFadeOutDurationOnFocusLossMillis(@NonNull AudioAttributes aa)
    {
        Objects.requireNonNull(aa);
        try {
            return getService().getFadeOutDurationOnFocusLossMillis(aa);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Test method to return the list of UIDs currently marked as ducked because of their
     * audio focus status
     * @return the list of UIDs, can be empty when no app is being ducked.
     */
    @TestApi
    @FlaggedApi(FLAG_FOCUS_FREEZE_TEST_API)
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
    public @NonNull List<Integer> getFocusDuckedUidsForTest() {
        try {
            return getService().getFocusDuckedUidsForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Test method to return the duration of the fade out applied on the players of a focus loser
     * @return the fade out duration in ms
     */
    @TestApi
    @FlaggedApi(FLAG_FOCUS_FREEZE_TEST_API)
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
    public long getFocusFadeOutDurationForTest() {
        try {
            return getService().getFocusFadeOutDurationForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Test method to return the length of time after a fade-out before the focus loser is unmuted
     * (and is faded back in).
     * @return the time gap after a fade-out completion on focus loss, and fade-in start in ms.
     */
    @TestApi
    @FlaggedApi(FLAG_FOCUS_FREEZE_TEST_API)
    @RequiresPermission("android.permission.QUERY_AUDIO_STATE")
    public long getFocusUnmuteDelayAfterFadeOutForTest() {
        try {
            return getService().getFocusUnmuteDelayAfterFadeOutForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Test method to start preventing applications from requesting audio focus during a test,
     * which could interfere with the functionality/behavior under test.
     * Calling this method needs to be paired with a call to {@link #exitAudioFocusFreezeForTest}
     * when the testing is done. If this is not the case (e.g. in case of a test crash),
     * a death observer mechanism will ensure the system is not left in a bad state, but this should
     * not be relied on when implementing tests.
     * @param exemptedUids a list of UIDs that are exempt from the freeze. This would for instance
     *     be those of the test runner and other players used in the test, or the "fake" UIDs used
     *     for testing with {@link #requestAudioFocusForTest(AudioFocusRequest, String, int, int)}.
     * @return true if the focus freeze mode is successfully entered, false if there was an issue,
     *     such as another freeze in place at the time of invocation.
     *     A false result should result in a test failure as this would indicate the system is not
     *     in a proper state with a predictable behavior for audio focus management.
     */
    @TestApi
    @FlaggedApi(FLAG_FOCUS_FREEZE_TEST_API)
    @RequiresPermission("Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    public boolean enterAudioFocusFreezeForTest(@NonNull List<Integer> exemptedUids) {
        Objects.requireNonNull(exemptedUids);
        try {
            final int[] uids = exemptedUids.stream().mapToInt(Integer::intValue).toArray();
            return getService().enterAudioFocusFreezeForTest(mICallBack, uids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Test method to end preventing applications from requesting audio focus during a test.
     * @return true if the focus freeze mode is successfully exited, false if there was an issue,
     *     such as the freeze already having ended, or not started.
     */
    @TestApi
    @FlaggedApi(FLAG_FOCUS_FREEZE_TEST_API)
    @RequiresPermission("Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    public boolean exitAudioFocusFreezeForTest() {
        try {
            return getService().exitAudioFocusFreezeForTest(mICallBack);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int requestAudioFocus(@NonNull AudioFocusRequest afr, @Nullable AudioPolicy ap) {
        if (afr == null) {
            throw new NullPointerException("Illegal null AudioFocusRequest");
        }
        // this can only be checked now, not during the creation of the AudioFocusRequest instance
        if (afr.locksFocus() && ap == null) {
            throw new IllegalArgumentException(
                    "Illegal null audio policy when locking audio focus");
        }

        if (hasCustomPolicyVirtualDeviceContext()) {
            // If the focus request was made within context associated with VirtualDevice
            // configured with custom device policy for audio, bypass audio service focus handling.
            // The custom device policy for audio means that audio associated with this device
            // is likely rerouted to VirtualAudioDevice and playback on the VirtualAudioDevice
            // shouldn't affect non-virtual audio tracks (and vice versa).
            return AUDIOFOCUS_REQUEST_GRANTED;
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
        BlockingFocusResultReceiver focusReceiver;
        synchronized (mFocusRequestsLock) {

            try {
                // TODO status contains result and generation counter for ext policy
                status = service.requestAudioFocus(afr.getAudioAttributes(),
                        afr.getFocusGain(), mICallBack,
                        mAudioFocusDispatcher,
                        clientId,
                        getContext().getOpPackageName() /* package name */,
                        getContext().getAttributionTag(),
                        afr.getFlags(),
                        ap != null ? ap.cb() : null,
                        sdk);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (status != AudioManager.AUDIOFOCUS_REQUEST_WAITING_FOR_EXT_POLICY) {
                // default path with no external focus policy
                return status;
            }
            focusReceiver = addClientIdToFocusReceiverLocked(clientId);
        }

        return handleExternalAudioPolicyWaitIfNeeded(clientId, focusReceiver);
    }

    @GuardedBy("mFocusRequestsLock")
    private BlockingFocusResultReceiver addClientIdToFocusReceiverLocked(String clientId) {
        BlockingFocusResultReceiver focusReceiver;
        if (mFocusRequestsAwaitingResult == null) {
            mFocusRequestsAwaitingResult =
                    new HashMap<String, BlockingFocusResultReceiver>(1);
        }
        focusReceiver = new BlockingFocusResultReceiver(clientId);
        mFocusRequestsAwaitingResult.put(clientId, focusReceiver);
        return focusReceiver;
    }

    private @FocusRequestResult int handleExternalAudioPolicyWaitIfNeeded(String clientId,
            BlockingFocusResultReceiver focusReceiver) {
        focusReceiver.waitForResult(EXT_FOCUS_POLICY_TIMEOUT_MS);
        if (DEBUG && !focusReceiver.receivedResult()) {
            Log.e(TAG, "handleExternalAudioPolicyWaitIfNeeded"
                    + " response from ext policy timed out, denying request");
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
                    if (timeToWait <= 0) {
                        break;
                    }
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
     * @param focusReqType the type of focus request. AUDIOFOCUS_GAIN_TRANSIENT is recommended so
     *    media applications resume after a call
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void requestAudioFocusForCall(int streamType, int focusReqType) {
        final IAudioService service = getService();
        try {
            service.requestAudioFocus(new AudioAttributes.Builder()
                        .setInternalLegacyStreamType(streamType).build(),
                    focusReqType, mICallBack, null,
                    AudioSystem.IN_VOICE_COMM_FOCUS_ID,
                    getContext().getOpPackageName(),
                    getContext().getAttributionTag(),
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
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
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
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
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
     * Notifies an application with a focus listener of gain or loss of audio focus
     *
     * <p>This is similar to {@link #dispatchAudioFocusChange(AudioFocusInfo, int, AudioPolicy)} but
     * with additional functionality  of fade. The players of the application with  audio focus
     * change, provided they meet the active {@link FadeManagerConfiguration} requirements, are
     * faded before dispatching the callback to the application. For example, players of the
     * application losing audio focus will be faded out, whereas players of the application gaining
     * audio focus will be faded in, if needed.
     *
     * <p>The applicability of fade is decided against the supplied active {@link AudioFocusInfo}.
     * This list cannot be {@code null}. The list can be empty if no other active
     * {@link AudioFocusInfo} available at the time of the dispatch.
     *
     * <p>The {@link FadeManagerConfiguration} supplied here is prioritized over existing fade
     * configurations. If none supplied, either the {@link FadeManagerConfiguration} set through
     * {@link AudioPolicy} or the default will be used to determine the fade properties.
     *
     * <p>This method can only be used by owners of an {@link AudioPolicy} configured with
     * {@link AudioPolicy.Builder#setIsAudioFocusPolicy(boolean)} set to true.
     *
     * @param afi the recipient of the focus change, that has previously requested audio focus, and
     *     that was received by the {@code AudioPolicy} through
     *     {@link AudioPolicy.AudioPolicyFocusListener#onAudioFocusRequest(AudioFocusInfo, int)}
     * @param focusChange one of focus gain types ({@link #AUDIOFOCUS_GAIN},
     *     {@link #AUDIOFOCUS_GAIN_TRANSIENT}, {@link #AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK} or
     *     {@link #AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE})
     *     or one of the focus loss types ({@link AudioManager#AUDIOFOCUS_LOSS},
     *     {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT},
     *     or {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}).
     *     <br>For the focus gain, the change type should be the same as the app requested
     * @param ap a valid registered {@link AudioPolicy} configured as a focus policy.
     * @param otherActiveAfis active {@link AudioFocusInfo} that are granted audio focus at the time
     *     of dispatch
     * @param transientFadeMgrConfig {@link FadeManagerConfiguration} that will be used for fading
     *     players resulting from this dispatch. This is a transient configuration that is only
     *     valid for this focus change and shall be discarded after processing this request.
     * @return {@link #AUDIOFOCUS_REQUEST_FAILED} if the focus client didn't have a listener or if
     *     there was an error sending the request, or {@link #AUDIOFOCUS_REQUEST_GRANTED} if the
     *     dispatch was successfully sent, or {@link #AUDIOFOCUS_REQUEST_DELAYED} if
     *     the request was successful but the dispatch of focus change was delayed due to a fade
     *     operation.
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION)
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    @FocusRequestResult
    public int dispatchAudioFocusChangeWithFade(@NonNull AudioFocusInfo afi, int focusChange,
            @NonNull AudioPolicy ap, @NonNull List<AudioFocusInfo> otherActiveAfis,
            @Nullable FadeManagerConfiguration transientFadeMgrConfig) {
        Objects.requireNonNull(afi, "AudioFocusInfo cannot be null");
        Objects.requireNonNull(ap, "AudioPolicy cannot be null");
        Objects.requireNonNull(otherActiveAfis, "Other active AudioFocusInfo list cannot be null");

        IAudioService service = getService();
        try {
            return service.dispatchFocusChangeWithFade(afi, focusChange, ap.cb(), otherActiveAfis,
                    transientFadeMgrConfig);
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
    @SuppressLint("RequiresPermission") // no permission enforcement, but only "undoes" what would
    // have been done by a matching requestAudioFocus
    public int abandonAudioFocus(OnAudioFocusChangeListener l, AudioAttributes aa) {
        if (hasCustomPolicyVirtualDeviceContext()) {
            // If this AudioManager instance is running within VirtualDevice context configured
            // with custom device policy for audio, the audio focus handling is bypassed.
            return AUDIOFOCUS_REQUEST_GRANTED;
        }
        unregisterAudioFocusRequest(l);
        final IAudioService service = getService();
        try {
            return service.abandonAudioFocus(mAudioFocusDispatcher,
                    getIdForAudioFocusListener(l), aa, getContext().getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
                0/*requestCode, ignored*/, mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE);
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
                0/*requestCode, ignored*/, mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE);
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
     *    {@link Manifest.permission#MODIFY_AUDIO_ROUTING} permission,
     *    {@link #SUCCESS} otherwise.
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
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
                    projection == null ? null : projection.getProjection(),
                    policy.getAttributionSource());
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
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
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
            policy.reset();
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
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void unregisterAudioPolicy(@NonNull AudioPolicy policy) {
        Preconditions.checkNotNull(policy, "Illegal null AudioPolicy argument");
        final IAudioService service = getService();
        try {
            policy.invalidateCaptorsAndInjectors();
            service.unregisterAudioPolicy(policy.cb());
            policy.reset();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @return All currently registered audio policy mixes.
     */
    @TestApi
    @FlaggedApi(android.media.audiopolicy.Flags.FLAG_AUDIO_MIX_TEST_API)
    @NonNull
    public List<android.media.audiopolicy.AudioMix> getRegisteredPolicyMixes() {
        if (!android.media.audiopolicy.Flags.audioMixTestApi()) {
            return Collections.emptyList();
        }

        final IAudioService service = getService();
        try {
            return service.getRegisteredPolicyMixes();
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void reloadAudioSettings() {
        final IAudioService service = getService();
        try {
            service.reloadAudioSettings();
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
     * The audio output device code for HDMI enhanced Audio Return Channel.
     */
    public static final int DEVICE_OUT_HDMI_EARC = AudioSystem.DEVICE_OUT_HDMI_EARC;
    /** @hide
     * The audio output device code for S/PDIF digital connection.
     */
    public static final int DEVICE_OUT_SPDIF = AudioSystem.DEVICE_OUT_SPDIF;
    /** @hide
     * The audio output device code for built-in FM transmitter.
     */
    public static final int DEVICE_OUT_FM = AudioSystem.DEVICE_OUT_FM;
    /** @hide
     * The audio output device code for echo reference injection point.
     */
    public static final int DEVICE_OUT_ECHO_CANCELLER = AudioSystem.DEVICE_OUT_ECHO_CANCELLER;
    /** @hide
     * The audio output device code for a BLE audio headset.
     */
    public static final int DEVICE_OUT_BLE_HEADSET = AudioSystem.DEVICE_OUT_BLE_HEADSET;
    /** @hide
     * The audio output device code for a BLE audio speaker.
     */
    public static final int DEVICE_OUT_BLE_SPEAKER = AudioSystem.DEVICE_OUT_BLE_SPEAKER;
    /** @hide
     * The audio output device code for a BLE audio brodcast group.
     */
    public static final int DEVICE_OUT_BLE_BROADCAST = AudioSystem.DEVICE_OUT_BLE_BROADCAST;
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
     * The audio input device code for HDMI EARC
     */
    public static final int DEVICE_IN_HDMI_EARC =
                                    AudioSystem.DEVICE_IN_HDMI_EARC;

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
    /** @hide
     * The audio input device code for an echo reference capture point.
     */
    public static final int DEVICE_IN_ECHO_REFERENCE = AudioSystem.DEVICE_IN_ECHO_REFERENCE;
    /** @hide
     * The audio input device code for a BLE audio headset.
     */
    public static final int DEVICE_IN_BLE_HEADSET = AudioSystem.DEVICE_IN_BLE_HEADSET;

    /**
     * Return true if the device code corresponds to an output device.
     * @hide
     */
    public static boolean isOutputDevice(int device)
    {
        return !AudioSystem.isInputDevice(device);
    }

    /**
     * Return true if the device code corresponds to an input device.
     * @hide
     */
    public static boolean isInputDevice(int device)
    {
        return AudioSystem.isInputDevice(device);
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
     *            {@link #DEVICE_OUT_HDMI_EARC}.
     *            {@link #DEVICE_OUT_SPDIF}.
     *            {@link #DEVICE_OUT_FM}.
     *            {@link #DEVICE_OUT_DEFAULT} is not used here.
     *
     * The implementation may support additional device codes beyond those listed, so
     * the application should ignore any bits which it does not recognize.
     * Note that the information may be imprecise when the implementation
     * cannot distinguish whether a particular device is enabled.
     *
     * @deprecated on {@link android.os.Build.VERSION_CODES#T} as new devices
     *             will have multi-bit device types.
     *             Prefer to use {@link #getDevicesForAttributes()} instead,
     *             noting that getDevicesForStream() has a few small discrepancies
     *             for better volume handling.
     * @hide
     */
    @UnsupportedAppUsage
    @Deprecated
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
                final IAudioService service = getService();
                try {
                    return service.getDeviceMaskForStream(streamType);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            default:
                return 0;
        }
    }

    /**
     * @hide
     * Get the audio devices that would be used for the routing of the given audio attributes.
     * @param attributes the {@link AudioAttributes} for which the routing is being queried.
     *   For queries about output devices (playback use cases), a valid usage must be specified in
     *   the audio attributes via AudioAttributes.Builder.setUsage(). The capture preset MUST NOT
     *   be changed from default.
     *   For queries about input devices (capture use case), a valid capture preset MUST be
     *   specified in the audio attributes via AudioAttributes.Builder.setCapturePreset(). If a
     *   capture preset is present, then this has precedence over any usage or content type also
     *   present in the audio attrirutes.
     * @return an empty list if there was an issue with the request, a list of audio devices
     *   otherwise (typically one device, except for duplicated paths).
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE
    })
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

    // Each listener corresponds to a unique callback stub because each listener can subscribe to
    // different AudioAttributes.
    private final ConcurrentHashMap<OnDevicesForAttributesChangedListener,
            IDevicesForAttributesCallbackStub> mDevicesForAttributesListenerToStub =
                    new ConcurrentHashMap<>();

    private static final class IDevicesForAttributesCallbackStub
            extends IDevicesForAttributesCallback.Stub {
        ListenerInfo<OnDevicesForAttributesChangedListener> mInfo;

        IDevicesForAttributesCallbackStub(@NonNull OnDevicesForAttributesChangedListener listener,
                @NonNull Executor executor) {
            mInfo = new ListenerInfo<>(listener, executor);
        }

        public void register(boolean register, AudioAttributes attributes) {
            try {
                if (register) {
                    getService().addOnDevicesForAttributesChangedListener(attributes, this);
                } else {
                    getService().removeOnDevicesForAttributesChangedListener(this);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        @Override
        public void onDevicesForAttributesChanged(AudioAttributes attributes, boolean forVolume,
                List<AudioDeviceAttributes> devices) {
            // forVolume is ignored. The case where it is `true` is not handled.
            mInfo.mExecutor.execute(() ->
                    mInfo.mListener.onDevicesForAttributesChanged(
                            attributes, devices));
        }
    }

    /**
     * @hide
     * Interface to be notified of when routing changes for the registered audio attributes.
     */
    @SystemApi
    public interface OnDevicesForAttributesChangedListener {
        /**
         * Called on the listener to indicate that the audio devices for the given audio
         * attributes have changed.
         * @param attributes the {@link AudioAttributes} whose routing changed
         * @param devices a list of newly routed audio devices
         */
        void onDevicesForAttributesChanged(@NonNull AudioAttributes attributes,
                @NonNull List<AudioDeviceAttributes> devices);
    }

    /**
     * @hide
     * Adds a listener for being notified of routing changes for the given {@link AudioAttributes}.
     * @param attributes the {@link AudioAttributes} to listen for routing changes
     * @param executor
     * @param listener
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE
    })
    public void addOnDevicesForAttributesChangedListener(@NonNull AudioAttributes attributes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnDevicesForAttributesChangedListener listener) {
        Objects.requireNonNull(attributes);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);

        synchronized (mDevicesForAttributesListenerToStub) {
            IDevicesForAttributesCallbackStub callbackStub =
                    mDevicesForAttributesListenerToStub.get(listener);

            if (callbackStub == null) {
                callbackStub = new IDevicesForAttributesCallbackStub(listener, executor);
                mDevicesForAttributesListenerToStub.put(listener, callbackStub);
            }

            callbackStub.register(true, attributes);
        }
    }

    /**
     * @hide
     * Removes a previously registered listener for being notified of routing changes for the given
     * {@link AudioAttributes}.
     * @param listener
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE
    })
    public void removeOnDevicesForAttributesChangedListener(
            @NonNull OnDevicesForAttributesChangedListener listener) {
        Objects.requireNonNull(listener);

        synchronized (mDevicesForAttributesListenerToStub) {
            IDevicesForAttributesCallbackStub callbackStub =
                    mDevicesForAttributesListenerToStub.get(listener);
            if (callbackStub != null) {
                callbackStub.register(false, null /* attributes */);
            }

            mDevicesForAttributesListenerToStub.remove(listener);
        }
    }

    /**
     * Get the audio devices that would be used for the routing of the given audio attributes.
     * These are the devices anticipated to play sound from an {@link AudioTrack} created with
     * the specified {@link AudioAttributes}.
     * The audio routing can change if audio devices are physically connected or disconnected or
     * concurrently through {@link AudioRouting} or {@link MediaRouter}.
     * @param attributes the {@link AudioAttributes} for which the routing is being queried
     * @return an empty list if there was an issue with the request, a list of
     * {@link AudioDeviceInfo} otherwise (typically one device, except for duplicated paths).
     */
    public @NonNull List<AudioDeviceInfo> getAudioDevicesForAttributes(
            @NonNull AudioAttributes attributes) {
        final List<AudioDeviceAttributes> devicesForAttributes;
        try {
            Objects.requireNonNull(attributes);
            final IAudioService service = getService();
            devicesForAttributes = service.getDevicesForAttributesUnprotected(attributes);
        } catch (Exception e) {
            Log.i(TAG, "No audio devices available for specified attributes.");
            return Collections.emptyList();
        }

        // Map from AudioDeviceAttributes to AudioDeviceInfo
        AudioDeviceInfo[] outputDeviceInfos = getDevicesStatic(GET_DEVICES_OUTPUTS);
        List<AudioDeviceInfo> deviceInfosForAttributes = new ArrayList<>();
        for (AudioDeviceAttributes deviceForAttributes : devicesForAttributes) {
            for (AudioDeviceInfo deviceInfo : outputDeviceInfos) {
                if (deviceForAttributes.getType() == deviceInfo.getType()
                        && TextUtils.equals(deviceForAttributes.getAddress(),
                                deviceInfo.getAddress())) {
                    deviceInfosForAttributes.add(deviceInfo);
                }
            }
        }
        return Collections.unmodifiableList(deviceInfosForAttributes);
    }

    /**
     * @hide
     * Volume behavior for an audio device that has no particular volume behavior set. Invalid as
     * an argument to {@link #setDeviceVolumeBehavior(AudioDeviceAttributes, int)} and should not
     * be returned by {@link #getDeviceVolumeBehavior(AudioDeviceAttributes)}.
     */
    public static final int DEVICE_VOLUME_BEHAVIOR_UNSET = -1;
    /**
     * @hide
     * Volume behavior for an audio device where a software attenuation is applied
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    public static final int DEVICE_VOLUME_BEHAVIOR_VARIABLE = 0;
    /**
     * @hide
     * Volume behavior for an audio device where the volume is always set to provide no attenuation
     *     nor gain (e.g. unit gain).
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    public static final int DEVICE_VOLUME_BEHAVIOR_FULL = 1;
    /**
     * @hide
     * Volume behavior for an audio device where the volume is either set to muted, or to provide
     *     no attenuation nor gain (e.g. unit gain).
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    public static final int DEVICE_VOLUME_BEHAVIOR_FIXED = 2;
    /**
     * @hide
     * Volume behavior for an audio device where no software attenuation is applied, and
     *     the volume is kept synchronized between the host and the device itself through a
     *     device-specific protocol such as BT AVRCP.
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    public static final int DEVICE_VOLUME_BEHAVIOR_ABSOLUTE = 3;
    /**
     * @hide
     * Volume behavior for an audio device where no software attenuation is applied, and
     *     the volume is kept synchronized between the host and the device itself through a
     *     device-specific protocol (such as for hearing aids), based on the audio mode (e.g.
     *     normal vs in phone call).
     * @see #setMode(int)
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    public static final int DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE = 4;

    /**
     * @hide
     * A variant of {@link #DEVICE_VOLUME_BEHAVIOR_ABSOLUTE} where the host cannot reliably set
     * the volume percentage of the audio device. Specifically, {@link #setStreamVolume} will have
     * no effect, or an unreliable effect.
     */
    @SystemApi
    public static final int DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY = 5;

    /** @hide */
    @IntDef({
            DEVICE_VOLUME_BEHAVIOR_VARIABLE,
            DEVICE_VOLUME_BEHAVIOR_FULL,
            DEVICE_VOLUME_BEHAVIOR_FIXED,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceVolumeBehavior {}

    /** @hide */
    @IntDef({
            DEVICE_VOLUME_BEHAVIOR_UNSET,
            DEVICE_VOLUME_BEHAVIOR_VARIABLE,
            DEVICE_VOLUME_BEHAVIOR_FULL,
            DEVICE_VOLUME_BEHAVIOR_FIXED,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceVolumeBehaviorState {}

    /**
     * Variants of absolute volume behavior that are set in {@link AudioDeviceVolumeManager}.
     * @hide
     */
    @IntDef({
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AbsoluteDeviceVolumeBehavior {}

    /**
     * @hide
     * Throws IAE on an invalid volume behavior value
     * @param volumeBehavior behavior value to check
     */
    public static void enforceValidVolumeBehavior(int volumeBehavior) {
        switch (volumeBehavior) {
            case DEVICE_VOLUME_BEHAVIOR_VARIABLE:
            case DEVICE_VOLUME_BEHAVIOR_FULL:
            case DEVICE_VOLUME_BEHAVIOR_FIXED:
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE:
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE:
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY:
                return;
            default:
                throw new IllegalArgumentException("Illegal volume behavior " + volumeBehavior);
        }
    }

    /**
     * @hide
     * Sets the volume behavior for an audio output device.
     * @see #DEVICE_VOLUME_BEHAVIOR_VARIABLE
     * @see #DEVICE_VOLUME_BEHAVIOR_FULL
     * @see #DEVICE_VOLUME_BEHAVIOR_FIXED
     * @see #DEVICE_VOLUME_BEHAVIOR_ABSOLUTE
     * @see #DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE
     * @param device the device to be affected
     * @param deviceVolumeBehavior one of the device behaviors
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public void setDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device,
            @DeviceVolumeBehavior int deviceVolumeBehavior) {
        // verify arguments (validity of device type is enforced in server)
        Objects.requireNonNull(device);
        enforceValidVolumeBehavior(deviceVolumeBehavior);
        // communicate with service
        final IAudioService service = getService();
        try {
            service.setDeviceVolumeBehavior(device, deviceVolumeBehavior,
                    mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Controls whether DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY may be returned by
     * getDeviceVolumeBehavior. If this is disabled, DEVICE_VOLUME_BEHAVIOR_FULL is returned
     * in its place.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Overridable
    public static final long RETURN_DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY = 240663182L;

    /**
     * @hide
     * Returns the volume device behavior for the given audio device
     * @param device the audio device
     * @return the volume behavior for the device
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public @DeviceVolumeBehavior
    int getDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device) {
        // verify arguments (validity of device type is enforced in server)
        Objects.requireNonNull(device);
        // communicate with service
        final IAudioService service = getService();
        try {
            int behavior = service.getDeviceVolumeBehavior(device);
            if (!CompatChanges.isChangeEnabled(RETURN_DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY)
                    && behavior == DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY) {
                return AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL;
            }
            return behavior;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns {@code true} if the volume device behavior is {@link #DEVICE_VOLUME_BEHAVIOR_FULL}.
     */
    @TestApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public boolean isFullVolumeDevice() {
        final AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
        final List<AudioDeviceAttributes> devices = getDevicesForAttributes(attributes);
        for (AudioDeviceAttributes device : devices) {
            if (getDeviceVolumeBehavior(device) == DEVICE_VOLUME_BEHAVIOR_FULL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indicate wired accessory connection state change.
     * @param device type of device connected/disconnected (AudioManager.DEVICE_OUT_xxx)
     * @param state  new connection state: 1 connected, 0 disconnected
     * @param name   device name
     * {@hide}
     */
    @UnsupportedAppUsage
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setWiredDeviceConnectionState(int device, int state, String address, String name) {
        AudioDeviceAttributes attributes = new AudioDeviceAttributes(device, address, name);
        setWiredDeviceConnectionState(attributes, state);
    }

    /**
     * Indicate wired accessory connection state change and attributes.
     * @param state      new connection state: 1 connected, 0 disconnected
     * @param attributes attributes of the connected device
     * {@hide}
     */
    @UnsupportedAppUsage
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setWiredDeviceConnectionState(AudioDeviceAttributes attributes, int state) {
        final IAudioService service = getService();
        try {
            service.setWiredDeviceConnectionState(attributes, state,
                    mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicate wired accessory connection state change.
     * @param device {@link AudioDeviceAttributes} of the device to "fake-connect"
     * @param connected true for connected, false for disconnected
     * {@hide}
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setTestDeviceConnectionState(@NonNull AudioDeviceAttributes device,
            boolean connected) {
        try {
            getService().setTestDeviceConnectionState(device, connected);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicate Bluetooth profile connection state change.
     * Configuration changes for A2DP are indicated by having the same <code>newDevice</code> and
     * <code>previousDevice</code>
     * This operation is asynchronous.
     *
     * @param newDevice Bluetooth device connected or null if there is no new devices
     * @param previousDevice Bluetooth device disconnected or null if there is no disconnected
     * devices
     * @param info contain all info related to the device. {@link BluetoothProfileConnectionInfo}
     * {@hide}
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.BLUETOOTH_STACK)
    public void handleBluetoothActiveDeviceChanged(@Nullable BluetoothDevice newDevice,
            @Nullable BluetoothDevice previousDevice,
            @NonNull BluetoothProfileConnectionInfo info) {
        final IAudioService service = getService();
        try {
            service.handleBluetoothActiveDeviceChanged(newDevice, previousDevice, info);
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean setAdditionalOutputDeviceDelay(
            @NonNull AudioDeviceInfo device, @IntRange(from = 0) long delayMillis) {
        Objects.requireNonNull(device);
        try {
            return getService().setAdditionalOutputDeviceDelay(
                new AudioDeviceAttributes(device), delayMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            return getService().getAdditionalOutputDeviceDelay(new AudioDeviceAttributes(device));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            return getService().getMaxAdditionalOutputDeviceDelay(
                    new AudioDeviceAttributes(device));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Returns the registered volume controller interface.
     *
     * @hide
     */
    @Nullable
    public IVolumeController getVolumeController() {
        try {
            return getService().getVolumeController();
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
     * Test method for enabling/disabling the volume controller long press timeout for checking
     * whether two consecutive volume adjustments should be treated as a volume long press.
     *
     * <p>Used only for testing
     *
     * @param enable true for enabling, otherwise will be disabled (test mode)
     *
     * @hide
     **/
    @TestApi
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setVolumeControllerLongPressTimeoutEnabled(boolean enable) {
        try {
            getService().setVolumeControllerLongPressTimeoutEnabled(enable);
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
     * Check whether a user can mute this stream type from a given UI element.
     *
     * <p>Only useful for volume controllers.
     *
     * @param streamType type of stream to check if it's mutable from UI
     *
     * @hide
     */
    public boolean isStreamMutableByUi(int streamType) {
        try {
            return getService().isStreamMutableByUi(streamType);
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
     * @hide
     * Lower media volume to RS1 interval
     */
    public void lowerVolumeToRs1() {
        try {
            getService().lowerVolumeToRs1(mApplicationContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @return the RS2 upper bound used for momentary exposure warnings
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public float getRs2Value() {
        try {
            return getService().getOutputRs2UpperBound();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Sets the RS2 upper bound used for momentary exposure warnings
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setRs2Value(float rs2Value) {
        try {
            getService().setOutputRs2UpperBound(rs2Value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @return the current computed sound dose value
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public float getCsd() {
        try {
            return getService().getCsd();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Sets the computed sound dose value to {@code csd}. A negative value will
     * reset all the CSD related timeouts: after a momentary exposure warning and
     * before the momentary exposure reaches RS2 (see IEC62368-1 10.6.5)
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setCsd(float csd) {
        try {
            getService().setCsd(csd);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Forces the computation of MEL values (used for CSD) on framework level. This will have the
     * result of ignoring the MEL values computed on HAL level. Should only be used in testing
     * since this can affect the certification of a device with EN50332-3 regulation.
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void forceUseFrameworkMel(boolean useFrameworkMel) {
        try {
            getService().forceUseFrameworkMel(useFrameworkMel);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Forces the computation of CSD on all output devices.
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void forceComputeCsdOnAllDevices(boolean computeCsdOnAllDevices) {
        try {
            getService().forceComputeCsdOnAllDevices(computeCsdOnAllDevices);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether CSD is enabled and supported by the current active audio module HAL.
     * This method will return {@code false) for setups in which CSD as a feature is available
     * (see {@link AudioManager#isCsdAsAFeatureAvailable()}) and not enabled (see
     * {@link AudioManager#isCsdAsAFeatureEnabled()}).
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean isCsdEnabled() {
        try {
            return getService().isCsdEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether CSD as a feature can be manipulated by a client. This method
     * returns {@code true} in countries where there isn't a safe hearing regulation
     * enforced.
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean isCsdAsAFeatureAvailable() {
        try {
            return getService().isCsdAsAFeatureAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns {@code true} if the client has enabled CSD. This function should only
     * be called if {@link AudioManager#isCsdAsAFeatureAvailable()} returns {@code true}.
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean isCsdAsAFeatureEnabled() {
        try {
            return getService().isCsdAsAFeatureEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Enables/disables the CSD feature. This function should only be called if
     * {@link AudioManager#isCsdAsAFeatureAvailable()} returns {@code true}.
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setCsdAsAFeatureEnabled(boolean csdToggleValue) {
        try {
            getService().setCsdAsAFeatureEnabled(csdToggleValue);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Describes an audio device that has not been categorized with a specific
     * audio type.
     */
    public static final int AUDIO_DEVICE_CATEGORY_UNKNOWN = 0;

    /**
     * @hide
     * Describes an audio device which is categorized as something different.
     */
    public static final int AUDIO_DEVICE_CATEGORY_OTHER = 1;

    /**
     * @hide
     * Describes an audio device which was categorized as speakers.
     */
    public static final int AUDIO_DEVICE_CATEGORY_SPEAKER = 2;

    /**
     * @hide
     * Describes an audio device which was categorized as headphones.
     */
    public static final int AUDIO_DEVICE_CATEGORY_HEADPHONES = 3;

    /**
     * @hide
     * Describes an audio device which was categorized as car-kit.
     */
    public static final int AUDIO_DEVICE_CATEGORY_CARKIT = 4;

    /**
     * @hide
     * Describes an audio device which was categorized as watch.
     */
    public static final int AUDIO_DEVICE_CATEGORY_WATCH = 5;

    /**
     * @hide
     * Describes an audio device which was categorized as hearing aid.
     */
    public static final int AUDIO_DEVICE_CATEGORY_HEARING_AID = 6;

    /**
     * @hide
     * Describes an audio device which was categorized as receiver.
     */
    public static final int AUDIO_DEVICE_CATEGORY_RECEIVER = 7;

    /** @hide */
    @IntDef(flag = false, prefix = "AUDIO_DEVICE_CATEGORY", value = {
            AUDIO_DEVICE_CATEGORY_UNKNOWN,
            AUDIO_DEVICE_CATEGORY_OTHER,
            AUDIO_DEVICE_CATEGORY_SPEAKER,
            AUDIO_DEVICE_CATEGORY_HEADPHONES,
            AUDIO_DEVICE_CATEGORY_CARKIT,
            AUDIO_DEVICE_CATEGORY_WATCH,
            AUDIO_DEVICE_CATEGORY_HEARING_AID,
            AUDIO_DEVICE_CATEGORY_RECEIVER }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioDeviceCategory {}

    /** @hide */
    public static String audioDeviceCategoryToString(int audioDeviceCategory) {
        switch (audioDeviceCategory) {
            case AUDIO_DEVICE_CATEGORY_UNKNOWN: return "AUDIO_DEVICE_CATEGORY_UNKNOWN";
            case AUDIO_DEVICE_CATEGORY_OTHER: return "AUDIO_DEVICE_CATEGORY_OTHER";
            case AUDIO_DEVICE_CATEGORY_SPEAKER: return "AUDIO_DEVICE_CATEGORY_SPEAKER";
            case AUDIO_DEVICE_CATEGORY_HEADPHONES: return "AUDIO_DEVICE_CATEGORY_HEADPHONES";
            case AUDIO_DEVICE_CATEGORY_CARKIT: return "AUDIO_DEVICE_CATEGORY_CARKIT";
            case AUDIO_DEVICE_CATEGORY_WATCH: return "AUDIO_DEVICE_CATEGORY_WATCH";
            case AUDIO_DEVICE_CATEGORY_HEARING_AID: return "AUDIO_DEVICE_CATEGORY_HEARING_AID";
            case AUDIO_DEVICE_CATEGORY_RECEIVER: return "AUDIO_DEVICE_CATEGORY_RECEIVER";
            default:
                return new StringBuilder("unknown AudioDeviceCategory ").append(
                        audioDeviceCategory).toString();
        }
    }

    /**
     * @hide
     * Sets the audio device type of a Bluetooth device given its MAC address
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setBluetoothAudioDeviceCategory_legacy(@NonNull String address, boolean isBle,
            @AudioDeviceCategory int btAudioDeviceType) {
        if (automaticBtDeviceType()) {
            // do nothing
            return;
        }
        try {
            getService().setBluetoothAudioDeviceCategory_legacy(address, isBle, btAudioDeviceType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the audio device type of a Bluetooth device given its MAC address
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    @AudioDeviceCategory
    public int getBluetoothAudioDeviceCategory_legacy(@NonNull String address, boolean isBle) {
        if (automaticBtDeviceType()) {
            return AUDIO_DEVICE_CATEGORY_UNKNOWN;
        }
        try {
            return getService().getBluetoothAudioDeviceCategory_legacy(address, isBle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Sets the audio device type of a Bluetooth device given its MAC address
     *
     * @return {@code true} if the device type was set successfully. If the
     *         audio device type was automatically identified this method will
     *         return {@code false}.
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean setBluetoothAudioDeviceCategory(@NonNull String address,
            @AudioDeviceCategory int btAudioDeviceCategory) {
        if (!automaticBtDeviceType()) {
            return false;
        }
        try {
            return getService().setBluetoothAudioDeviceCategory(address, btAudioDeviceCategory);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the audio device type of a Bluetooth device given its MAC address
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    @AudioDeviceCategory
    public int getBluetoothAudioDeviceCategory(@NonNull String address) {
        if (!automaticBtDeviceType()) {
            return AUDIO_DEVICE_CATEGORY_UNKNOWN;
        }
        try {
            return getService().getBluetoothAudioDeviceCategory(address);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns {@code true} if the audio device type of a Bluetooth device can
     * be automatically identified
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean isBluetoothAudioDeviceCategoryFixed(@NonNull String address) {
        if (!automaticBtDeviceType()) {
            return false;
        }
        try {
            return getService().isBluetoothAudioDeviceCategoryFixed(address);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Sound dose warning at every 100% of dose during integration window
     */
    public static final int CSD_WARNING_DOSE_REACHED_1X = 1;
    /**
     * @hide
     * Sound dose warning when 500% of dose is reached during integration window
     */
    public static final int CSD_WARNING_DOSE_REPEATED_5X = 2;
    /**
     * @hide
     * Sound dose warning after a momentary exposure event
     */
    public static final int CSD_WARNING_MOMENTARY_EXPOSURE = 3;
    /**
     * @hide
     * Sound dose warning at every 100% of dose during integration window
     */
    public static final int CSD_WARNING_ACCUMULATION_START = 4;

    /** @hide */
    @IntDef(flag = false, value = {
            CSD_WARNING_DOSE_REACHED_1X,
            CSD_WARNING_DOSE_REPEATED_5X,
            CSD_WARNING_MOMENTARY_EXPOSURE,
            CSD_WARNING_ACCUMULATION_START }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface CsdWarning {}

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
     * @hide
     * Queries the volume policy
     * @return the volume policy currently in use
     */
    @TestApi
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    public @NonNull VolumePolicy getVolumePolicy() {
        try {
            return getService().getVolumePolicy();
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
    @SuppressLint("RequiresPermission") // FIXME is this still used?
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

    private static final int AUDIOPORT_GENERATION_INIT = 0;
    private static Object sAudioPortGenerationLock = new Object();
    @GuardedBy("sAudioPortGenerationLock")
    private static int sAudioPortGeneration = AUDIOPORT_GENERATION_INIT;
    private static ArrayList<AudioPort> sAudioPortsCached = new ArrayList<AudioPort>();
    private static ArrayList<AudioPort> sPreviousAudioPortsCached = new ArrayList<AudioPort>();
    private static ArrayList<AudioPatch> sAudioPatchesCached = new ArrayList<AudioPatch>();

    static int resetAudioPortGeneration() {
        int generation;
        synchronized (sAudioPortGenerationLock) {
            generation = sAudioPortGeneration;
            sAudioPortGeneration = AUDIOPORT_GENERATION_INIT;
        }
        return generation;
    }

    static int updateAudioPortCache(ArrayList<AudioPort> ports, ArrayList<AudioPatch> patches,
                                    ArrayList<AudioPort> previousPorts) {
        sAudioPortEventHandler.init();
        synchronized (sAudioPortGenerationLock) {

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
            // This can happen in case of stale audio patch referring to a removed device and is
            // handled by the caller.
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
     * a {@link Handler} object to {@link registerAudioDeviceCallback}.
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
     * Returns a Set of unique Integers corresponding to audio device type identifiers that can
     * <i>potentially</i> be connected to the system and meeting the criteria specified in the
     * <code>direction</code> parameter.
     * Note that this set contains {@link AudioDeviceInfo} device type identifiers for both devices
     * currently available <i>and</i> those that can be available if the user connects an audio
     * peripheral. Examples include TYPE_WIRED_HEADSET if the Android device supports an analog
     * headset jack or TYPE_USB_DEVICE if the Android device supports a USB host-mode port.
     * These are generally a superset of device type identifiers associated with the
     * AudioDeviceInfo objects returned from AudioManager.getDevices().
     * @param direction The constant specifying whether input or output devices are queried.
     * @see #GET_DEVICES_OUTPUTS
     * @see #GET_DEVICES_INPUTS
     * @return A (possibly zero-length) Set of Integer objects corresponding to the audio
     * device types of devices supported by the implementation.
     * @throws IllegalArgumentException If an invalid direction constant is specified.
     */
    @FlaggedApi(FLAG_SUPPORTED_DEVICE_TYPES_API)
    public @NonNull Set<Integer>
            getSupportedDeviceTypes(@AudioDeviceRole int direction) {
        if (direction != GET_DEVICES_OUTPUTS && direction != GET_DEVICES_INPUTS) {
            throw new IllegalArgumentException("AudioManager.getSupportedDeviceTypes(0x"
                    + Integer.toHexString(direction) + ") - Invalid.");
        }

        IntArray internalDeviceTypes = new IntArray();
        int status = AudioSystem.getSupportedDeviceTypes(direction, internalDeviceTypes);
        if (status != AudioManager.SUCCESS) {
            Log.e(TAG, "AudioManager.getSupportedDeviceTypes(" + direction + ") failed. status:"
                    + status);
        }

        // convert to external (AudioDeviceInfo.getType()) device IDs
        HashSet<Integer> externalDeviceTypes = new HashSet<Integer>();
        for (int index = 0; index < internalDeviceTypes.size(); index++) {
            // Set will eliminate any duplicates which AudioSystem.getSupportedDeviceTypes()
            // returns
            externalDeviceTypes.add(
                    AudioDeviceInfo.convertInternalDeviceToDeviceType(
                        internalDeviceTypes.get(index)));
        }

        return externalDeviceTypes;
    }

     /**
     * Returns an array of {@link AudioDeviceInfo} objects corresponding to the audio devices
     * currently connected to the system and meeting the criteria specified in the
     * <code>flags</code> parameter.
     * Notes that Android audio framework only support one device per device type. In that case,
     * if there are multiple audio device with the same device type connected to the Android device,
     * only the last reported device will be known by Android audio framework and returned by this
     * API.
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
     * Returns an {@link AudioDeviceInfo} corresponding to the specified {@link AudioPort} ID.
     * @param portId The audio port ID to look up for.
     * @param flags A set of bitflags specifying the criteria to test.
     * @see #GET_DEVICES_OUTPUTS
     * @see #GET_DEVICES_INPUTS
     * @see #GET_DEVICES_ALL
     * @return An AudioDeviceInfo or null if no device with matching port ID is found.
     * @hide
     */
    public static AudioDeviceInfo getDeviceForPortId(int portId, int flags) {
        if (portId == 0) {
            return null;
        }
        AudioDeviceInfo[] devices = getDevicesStatic(flags);
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == portId) {
                return device;
            }
        }
        return null;
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
        @AudioDeviceInfo.AudioDeviceType int deviceType = deviceInfo.getType();
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
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public @NonNull List<BluetoothCodecConfig> getHwOffloadFormatsSupportedForA2dp() {
        ArrayList<Integer> formatsList = new ArrayList<>();
        ArrayList<BluetoothCodecConfig> codecConfigList = new ArrayList<>();

        int status = AudioSystem.getHwOffloadFormatsSupportedForBluetoothMedia(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, formatsList);
        if (status != AudioManager.SUCCESS) {
            Log.e(TAG, "getHwOffloadEncodingFormatsSupportedForA2DP failed:" + status);
            return codecConfigList;
        }

        for (Integer format : formatsList) {
            int btSourceCodec = AudioSystem.audioFormatToBluetoothSourceCodec(format);
            if (btSourceCodec != BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                codecConfigList.add(
                        new BluetoothCodecConfig.Builder().setCodecType(btSourceCodec).build());
            }
        }
        return codecConfigList;
    }

    private List<BluetoothLeAudioCodecConfig> getHwOffloadFormatsSupportedForLeAudio(
            @AudioSystem.BtOffloadDeviceType int deviceType) {
        ArrayList<Integer> formatsList = new ArrayList<>();
        ArrayList<BluetoothLeAudioCodecConfig> leAudioCodecConfigList = new ArrayList<>();

        int status = AudioSystem.getHwOffloadFormatsSupportedForBluetoothMedia(
                deviceType, formatsList);
        if (status != AudioManager.SUCCESS) {
            Log.e(TAG, "getHwOffloadEncodingFormatsSupportedForLeAudio failed:" + status);
            return leAudioCodecConfigList;
        }

        for (Integer format : formatsList) {
            int btLeAudioCodec = AudioSystem.audioFormatToBluetoothLeAudioSourceCodec(format);
            if (btLeAudioCodec != BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                leAudioCodecConfigList.add(new BluetoothLeAudioCodecConfig.Builder()
                                            .setCodecType(btLeAudioCodec)
                                            .build());
            }
        }
        return leAudioCodecConfigList;
    }

    /**
     * Returns a list of audio formats that corresponds to encoding formats
     * supported on offload path for Le audio playback.
     *
     * @return a list of {@link BluetoothLeAudioCodecConfig} objects containing encoding formats
     * supported for offload Le Audio playback
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public List<BluetoothLeAudioCodecConfig> getHwOffloadFormatsSupportedForLeAudio() {
        return getHwOffloadFormatsSupportedForLeAudio(AudioSystem.DEVICE_OUT_BLE_HEADSET);
    }

    /**
     * Returns a list of audio formats that corresponds to encoding formats
     * supported on offload path for Le Broadcast playback.
     *
     * @return a list of {@link BluetoothLeAudioCodecConfig} objects containing encoding formats
     * supported for offload Le Broadcast playback
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public List<BluetoothLeAudioCodecConfig> getHwOffloadFormatsSupportedForLeBroadcast() {
        return getHwOffloadFormatsSupportedForLeAudio(AudioSystem.DEVICE_OUT_BLE_BROADCAST);
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
     * Sets the surround sound mode.
     *
     * @return true if successful, otherwise false
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public boolean setEncodedSurroundMode(@EncodedSurroundOutputMode int mode) {
        try {
            return getService().setEncodedSurroundMode(mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the surround sound mode.
     *
     * @return true if successful, otherwise false
     */
    public @EncodedSurroundOutputMode int getEncodedSurroundMode() {
        try {
            return getService().getEncodedSurroundMode(
                    getContext().getApplicationInfo().targetSdkVersion);
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
    @TestApi
    @NonNull
    public Map<Integer, Boolean> getSurroundFormats() {
        try {
            return getService().getSurroundFormats();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets and persists a certain surround format as enabled or not.
     * <p>
     * This API is called by TvSettings surround sound menu when user enables or disables a
     * surround sound format. This setting is persisted as global user setting.
     * Applications should revert their changes to surround sound settings unless they intend to
     * modify the global user settings across all apps. The framework does not auto-revert an
     * application's settings after a lifecycle event. Audio focus is not required to apply these
     * settings.
     *
     * @param enabled the required surround format state, true for enabled, false for disabled
     * @return true if successful, otherwise false
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public boolean setSurroundFormatEnabled(
            @AudioFormat.SurroundSoundEncoding int audioFormat, boolean enabled) {
        try {
            return getService().setSurroundFormatEnabled(audioFormat, enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets whether a certain surround format is enabled or not.
     * @param audioFormat a surround format
     *
     * @return whether the required surround format is enabled
     */
    public boolean isSurroundFormatEnabled(@AudioFormat.SurroundSoundEncoding int audioFormat) {
        try {
            return getService().isSurroundFormatEnabled(audioFormat);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns all surround formats that are reported by the connected HDMI device.
     * The return values are not affected by calling setSurroundFormatEnabled.
     *
     * @return a list of surround formats
     */
    @TestApi
    @NonNull
    public List<Integer> getReportedSurroundFormats() {
        try {
            return getService().getReportedSurroundFormats();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Indicates whether a platform supports the Ultrasound feature which covers the playback
     * and recording of 20kHz~ sounds. If platform supports Ultrasound, then the
     * usage will be
     * To start the Ultrasound playback:
     *     - Create an AudioTrack with {@link AudioAttributes.CONTENT_TYPE_ULTRASOUND}.
     * To start the Ultrasound capture:
     *     - Create an AudioRecord with {@link MediaRecorder.AudioSource.ULTRASOUND}.
     *
     * @return whether the ultrasound feature is supported, true when platform supports both
     * Ultrasound playback and capture, false otherwise.
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.ACCESS_ULTRASOUND)
    public boolean isUltrasoundSupported() {
        try {
            return getService().isUltrasoundSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Indicates whether the platform supports capturing content from the hotword recognition
     * pipeline. To capture content of this type, create an AudioRecord with
     * {@link AudioRecord.Builder.setRequestHotwordStream(boolean, boolean)}.
     * @param lookbackAudio Query if the hotword stream additionally supports providing buffered
     * audio prior to stream open.
     * @return True if the platform supports capturing hotword content, and if lookbackAudio
     * is true, if it additionally supports capturing buffered hotword content prior to stream
     * open. False otherwise.
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD)
    public boolean isHotwordStreamSupported(boolean lookbackAudio) {
        try {
            return getService().isHotwordStreamSupported(lookbackAudio);
        } catch (RemoteException e) {
            return false;
        }
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
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
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    // TODO also open to MODIFY_AUDIO_SETTINGS_PRIVILEGED b/341780042
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
     *
     * @param context the {@link Context} to resolve the uri.
     * @param uri the {@link Uri} of the asset.
     * @return true if the assert contains haptic channels.
     * @hide
     */
    public static boolean hasHapticChannelsImpl(@NonNull Context context, @NonNull Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.containsKey(MediaFormat.KEY_HAPTIC_CHANNEL_COUNT)
                        && format.getInteger(MediaFormat.KEY_HAPTIC_CHANNEL_COUNT) > 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "hasHapticChannels failure:" + e);
        }
        return false;
    }

    /**
     * Return if an asset contains haptic channels or not.
     *
     * @param context the {@link Context} to resolve the uri.
     * @param uri the {@link Uri} of the asset.
     * @return true if the assert contains haptic channels.
     * @hide
     */
    public static boolean hasHapticChannels(@Nullable Context context, @NonNull Uri uri) {
        Objects.requireNonNull(uri);

        if (context != null) {
            return hasHapticChannelsImpl(context, uri);
        }

        Context cachedContext = sContext.get();
        if (cachedContext != null) {
            if (DEBUG) {
                Log.d(TAG, "Try to use static context to query if having haptic channels");
            }
            return hasHapticChannelsImpl(cachedContext, uri);
        }

        // Try with audio service context, this may fail to get correct result.
        if (DEBUG) {
            Log.d(TAG, "Try to use audio service context to query if having haptic channels");
        }
        try {
            return getService().hasHapticChannels(uri);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether or not there is an active RTT call.
     * This method should be called by Telecom service.
     * @hide
     * TODO: make this a @SystemApi
     */
    public static void setRttEnabled(boolean rttEnabled) {
        try {
            getService().setRttEnabled(rttEnabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * <p>This API checks if the caller has the necessary permissions based on the provided
     * component name, uid, and pid values.
     * See {@link #adjustSuggestedStreamVolume(int, int, int)}.
     *
     * @param suggestedStreamType The stream type that will be used if there
     *         isn't a relevant stream. {@link #USE_DEFAULT_STREAM_TYPE} is
     *         valid here.
     * @param direction The direction to adjust the volume. One of
     *         {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE},
     *         {@link #ADJUST_SAME}, {@link #ADJUST_MUTE},
     *         {@link #ADJUST_UNMUTE}, or {@link #ADJUST_TOGGLE_MUTE}.
     * @param flags
     * @param packageName the package name of client application
     * @param uid the uid of client application
     * @param pid the pid of client application
     * @param targetSdkVersion the target sdk version of client application
     * @see #adjustVolume(int, int)
     * @see #adjustStreamVolume(int, int, int)
     * @see #setStreamVolume(int, int, int)
     * @see #isVolumeFixed()
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void adjustSuggestedStreamVolumeForUid(int suggestedStreamType, int direction,
            @SystemVolumeFlags int flags,
            @NonNull String packageName, int uid, int pid, int targetSdkVersion) {
        try {
            getService().adjustSuggestedStreamVolumeForUid(suggestedStreamType, direction, flags,
                    packageName, uid, pid, UserHandle.getUserHandleForUid(uid), targetSdkVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adjusts the volume of a particular stream by one step in a direction.
     * <p>
     * This method should only be used by applications that replace the platform-wide
     * management of audio settings or the main telephony application.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * <p>From N onward, ringer mode adjustments that would toggle Do Not Disturb are not allowed
     * unless the app has been granted Notification Policy Access.
     * See {@link NotificationManager#isNotificationPolicyAccessGranted()}.
     * <p>This API checks if the caller has the necessary permissions based on the provided
     * component name, uid, and pid values.
     * See {@link #adjustStreamVolume(int, int, int)}.
     *
     * @param streamType The stream type to adjust. One of {@link #STREAM_VOICE_CALL},
     *         {@link #STREAM_SYSTEM}, {@link #STREAM_RING}, {@link #STREAM_MUSIC},
     *         {@link #STREAM_ALARM} or {@link #STREAM_ACCESSIBILITY}.
     * @param direction The direction to adjust the volume. One of
     *         {@link #ADJUST_LOWER}, {@link #ADJUST_RAISE}, or
     *         {@link #ADJUST_SAME}.
     * @param flags
     * @param packageName the package name of client application
     * @param uid the uid of client application
     * @param pid the pid of client application
     * @param targetSdkVersion the target sdk version of client application
     * @see #adjustVolume(int, int)
     * @see #setStreamVolume(int, int, int)
     * @throws SecurityException if the adjustment triggers a Do Not Disturb change
     *         and the caller is not granted notification policy access.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void adjustStreamVolumeForUid(int streamType, int direction,
            @SystemVolumeFlags int flags,
            @NonNull String packageName, int uid, int pid, int targetSdkVersion) {
        try {
            getService().adjustStreamVolumeForUid(streamType, direction, flags, packageName, uid,
                    pid, UserHandle.getUserHandleForUid(uid), targetSdkVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the volume index for a particular stream.
     * <p>This method has no effect if the device implements a fixed volume policy
     * as indicated by {@link #isVolumeFixed()}.
     * <p>From N onward, volume adjustments that would toggle Do Not Disturb are not allowed unless
     * the app has been granted Notification Policy Access.
     * See {@link NotificationManager#isNotificationPolicyAccessGranted()}.
     * <p>This API checks if the caller has the necessary permissions based on the provided
     * component name, uid, and pid values.
     * See {@link #setStreamVolume(int, int, int)}.
     *
     * @param streamType The stream whose volume index should be set.
     * @param index The volume index to set. See
     *         {@link #getStreamMaxVolume(int)} for the largest valid value.
     * @param flags
     * @param packageName the package name of client application
     * @param uid the uid of client application
     * @param pid the pid of client application
     * @param targetSdkVersion the target sdk version of client application
     * @see #getStreamMaxVolume(int)
     * @see #getStreamVolume(int)
     * @see #isVolumeFixed()
     * @throws SecurityException if the volume change triggers a Do Not Disturb change
     *         and the caller is not granted notification policy access.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void setStreamVolumeForUid(int streamType, int index,
            @SystemVolumeFlags int flags,
            @NonNull String packageName, int uid, int pid, int targetSdkVersion) {
        try {
            getService().setStreamVolumeForUid(streamType, index, flags, packageName, uid, pid,
                    UserHandle.getUserHandleForUid(uid), targetSdkVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /** @hide
     * TODO: make this a @SystemApi */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setMultiAudioFocusEnabled(boolean enabled) {
        try {
            getService().setMultiAudioFocusEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Retrieves the Hardware A/V synchronization ID corresponding to the given audio session ID.
     * For more details on Hardware A/V synchronization please refer to
     *  <a href="https://source.android.com/devices/tv/multimedia-tunneling/">
     * media tunneling documentation</a>.
     * @param sessionId the audio session ID for which the HW A/V sync ID is retrieved.
     * @return the HW A/V sync ID for this audio session (an integer different from 0).
     * @throws UnsupportedOperationException if HW A/V synchronization is not supported.
     */
    public int getAudioHwSyncForSession(int sessionId) {
        int hwSyncId = AudioSystem.getAudioHwSyncForSession(sessionId);
        if (hwSyncId == AudioSystem.AUDIO_HW_SYNC_INVALID) {
            throw new UnsupportedOperationException("HW A/V synchronization is not supported.");
        }
        return hwSyncId;
    }

    /**
     * Selects the audio device that should be used for communication use cases, for instance voice
     * or video calls. This method can be used by voice or video chat applications to select a
     * different audio device than the one selected by default by the platform.
     * <p>The device selection is expressed as an {@link AudioDeviceInfo} among devices returned by
     * {@link #getAvailableCommunicationDevices()}. Note that only devices in a sink role
     * (AKA output devices, see {@link AudioDeviceInfo#isSink()}) can be specified. The matching
     * source device is selected automatically by the platform.
     * <p>The selection is active as long as the requesting application process lives, until
     * {@link #clearCommunicationDevice} is called or until the device is disconnected.
     * It is therefore important for applications to clear the request when a call ends or the
     * the requesting activity or service is stopped or destroyed.
     * <p>In case of simultaneous requests by multiple applications the priority is given to the
     * application currently controlling the audio mode (see {@link #setMode(int)}). This is the
     * latest application having selected mode {@link #MODE_IN_COMMUNICATION} or mode
     * {@link #MODE_IN_CALL}. Note that <code>MODE_IN_CALL</code> can only be selected by the main
     * telephony application with permission
     * {@link Manifest.permission#MODIFY_PHONE_STATE}.
     * <p> If the requested devices is not currently available, the request will be rejected and
     * the method will return false.
     * <p>This API replaces the following deprecated APIs:
     * <ul>
     *   <li> {@link #startBluetoothSco()}
     *   <li> {@link #stopBluetoothSco()}
     *   <li> {@link #setSpeakerphoneOn(boolean)}
     * </ul>
     * <h4>Example</h4>
     * <p>The example below shows how to enable and disable speakerphone mode.
     * <pre class="prettyprint">
     * // Get an AudioManager instance
     * AudioManager audioManager = Context.getSystemService(AudioManager.class);
     * AudioDeviceInfo speakerDevice = null;
     * List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
     * for (AudioDeviceInfo device : devices) {
     *     if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
     *         speakerDevice = device;
     *         break;
     *     }
     * }
     * if (speakerDevice != null) {
     *     // Turn speakerphone ON.
     *     boolean result = audioManager.setCommunicationDevice(speakerDevice);
     *     if (!result) {
     *         // Handle error.
     *     }
     *     // Turn speakerphone OFF.
     *     audioManager.clearCommunicationDevice();
     * }
     * </pre>
     * @param device the requested audio device.
     * @return <code>true</code> if the request was accepted, <code>false</code> otherwise.
     * @throws IllegalArgumentException If an invalid device is specified.
     */
    public boolean setCommunicationDevice(@NonNull AudioDeviceInfo device) {
        Objects.requireNonNull(device);
        try {
            if (device.getId() == 0) {
                Log.w(TAG, "setCommunicationDevice: device not found: " + device);
                return false;
            }
            return getService().setCommunicationDevice(mICallBack, device.getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancels previous communication device selection made with
     * {@link #setCommunicationDevice(AudioDeviceInfo)}.
     */
    public void clearCommunicationDevice() {
        try {
            getService().setCommunicationDevice(mICallBack, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns currently selected audio device for communication.
     * <p>This API replaces the following deprecated APIs:
     * <ul>
     *   <li> {@link #isBluetoothScoOn()}
     *   <li> {@link #isSpeakerphoneOn()}
     * </ul>
     * @return an {@link AudioDeviceInfo} indicating which audio device is
     * currently selected for communication use cases. Can be null on platforms
     * not supporting {@link android.content.pm.PackageManager#FEATURE_TELEPHONY}.
     * is used.
     */
    @Nullable
    public AudioDeviceInfo getCommunicationDevice() {
        try {
            return getDeviceForPortId(
                    getService().getCommunicationDevice(), GET_DEVICES_OUTPUTS);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of audio devices that can be selected for communication use cases via
     * {@link #setCommunicationDevice(AudioDeviceInfo)}.
     * @return a list of {@link AudioDeviceInfo} suitable for use with setCommunicationDevice().
     */
    @NonNull
    public List<AudioDeviceInfo> getAvailableCommunicationDevices() {
        try {
            ArrayList<AudioDeviceInfo> devices = new ArrayList<>();
            int[] portIds = getService().getAvailableCommunicationDeviceIds();
            for (int portId : portIds) {
                AudioDeviceInfo device = getDeviceForPortId(portId, GET_DEVICES_OUTPUTS);
                if (device == null) {
                    continue;
                }
                devices.add(device);
            }
            return devices;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of direct {@link AudioProfile} that are supported for the specified
     * {@link AudioAttributes}. This can be empty in case of an error or if no direct playback
     * is possible.
     *
     * <p>Direct playback means that the audio stream is not resampled or downmixed
     * by the framework. Checking for direct support can help the app select the representation
     * of audio content that most closely matches the capabilities of the device and peripherals
     * (e.g. A/V receiver) connected to it. Note that the provided stream can still be re-encoded
     * or mixed with other streams, if needed.
     * <p>When using this information to inform your application which audio format to play,
     * query again whenever audio output devices change (see {@link AudioDeviceCallback}).
     * @param attributes a non-null {@link AudioAttributes} instance.
     * @return a list of {@link AudioProfile}
     */
    @NonNull
    public List<AudioProfile> getDirectProfilesForAttributes(@NonNull AudioAttributes attributes) {
        Objects.requireNonNull(attributes);
        ArrayList<AudioProfile> audioProfilesList = new ArrayList<>();
        int status = AudioSystem.getDirectProfilesForAttributes(attributes, audioProfilesList);
        if (status != SUCCESS) {
            Log.w(TAG, "getDirectProfilesForAttributes failed.");
            return new ArrayList<>();
        }
        return audioProfilesList;
    }

    /**
     * @hide
     * Returns an {@link AudioDeviceInfo} corresponding to a connected device of the type provided.
     * The type must be a valid output type defined in <code>AudioDeviceInfo</code> class,
     * for instance {@link AudioDeviceInfo#TYPE_BUILTIN_SPEAKER}.
     * The method will return null if no device of the provided type is connected.
     * If more than one device of the provided type is connected, an object corresponding to the
     * first device encountered in the enumeration list will be returned.
     * @param deviceType The device device for which an <code>AudioDeviceInfo</code>
     *                   object is queried.
     * @return An AudioDeviceInfo object or null if no device with the requested type is connected.
     * @throws IllegalArgumentException If an invalid device type is specified.
     */
    @TestApi
    @Nullable
    public static AudioDeviceInfo getDeviceInfoFromType(
            @AudioDeviceInfo.AudioDeviceTypeOut int deviceType) {
        return getDeviceInfoFromTypeAndAddress(deviceType, null);
    }

    /**
     * @hide
     * Returns an {@link AudioDeviceInfo} corresponding to a connected device of the type and
     * address provided.
     * The type must be a valid output type defined in <code>AudioDeviceInfo</code> class,
     * for instance {@link AudioDeviceInfo#TYPE_BUILTIN_SPEAKER}.
     * If a null address is provided, the matching will happen on the type only.
     * The method will return null if no device of the provided type and address is connected.
     * If more than one device of the provided type is connected, an object corresponding to the
     * first device encountered in the enumeration list will be returned.
     * @param type The device device for which an <code>AudioDeviceInfo</code>
     *             object is queried.
     * @param address The device address for which an <code>AudioDeviceInfo</code>
     *                object is queried or null if requesting match on type only.
     * @return An AudioDeviceInfo object or null if no matching device is connected.
     * @throws IllegalArgumentException If an invalid device type is specified.
     */
    @Nullable
    public static AudioDeviceInfo getDeviceInfoFromTypeAndAddress(
            @AudioDeviceInfo.AudioDeviceTypeOut int type, @Nullable String address) {
        AudioDeviceInfo[] devices = getDevicesStatic(GET_DEVICES_OUTPUTS);
        AudioDeviceInfo deviceForType = null;
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == type) {
                deviceForType = device;
                if (address == null || address.equals(device.getAddress())) {
                    return device;
                }
            }
        }
        return deviceForType;
    }

    /**
     * Listener registered by client to be notified upon communication audio device change.
     * See {@link #setCommunicationDevice(AudioDeviceInfo)}.
     */
    public interface OnCommunicationDeviceChangedListener {
        /**
         * Callback method called upon communication audio device change.
         * @param device the audio device requested for communication use cases.
         *               Can be null on platforms not supporting
         *               {@link android.content.pm.PackageManager#FEATURE_TELEPHONY}.
         */
        void onCommunicationDeviceChanged(@Nullable AudioDeviceInfo device);
    }

    /**
     * manages the OnCommunicationDeviceChangedListener listeners and the
     * CommunicationDeviceDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnCommunicationDeviceChangedListener>
            mCommDeviceChangedListenerMgr = new CallbackUtil.LazyListenerManager();
    /**
     * Adds a listener for being notified of changes to the communication audio device.
     * See {@link #setCommunicationDevice(AudioDeviceInfo)}.
     * @param executor
     * @param listener
     */
    public void addOnCommunicationDeviceChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnCommunicationDeviceChangedListener listener) {
        mCommDeviceChangedListenerMgr.addListener(
                            executor, listener, "addOnCommunicationDeviceChangedListener",
                            () -> new CommunicationDeviceDispatcherStub());
    }

    /**
     * Removes a previously added listener of changes to the communication audio device.
     * See {@link #setCommunicationDevice(AudioDeviceInfo)}.
     * @param listener
     */
    public void removeOnCommunicationDeviceChangedListener(
            @NonNull OnCommunicationDeviceChangedListener listener) {
        mCommDeviceChangedListenerMgr.removeListener(listener,
                "removeOnCommunicationDeviceChangedListener");
    }

    private final class CommunicationDeviceDispatcherStub
            extends ICommunicationDeviceDispatcher.Stub implements CallbackUtil.DispatcherStub {

        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    getService().registerCommunicationDeviceDispatcher(this);
                } else {
                    getService().unregisterCommunicationDeviceDispatcher(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchCommunicationDeviceChanged(int portId) {
            AudioDeviceInfo device = getDeviceForPortId(portId, GET_DEVICES_OUTPUTS);
            mCommDeviceChangedListenerMgr.callListeners(
                    (listener) -> listener.onCommunicationDeviceChanged(device));
        }
    }


    /**
     * @hide
     * Indicates if the platform allows accessing the uplink and downlink audio of an ongoing
     * PSTN call.
     * When true, {@link getCallUplinkInjectionAudioTrack(AudioFormat)} can be used to obtain
     * an AudioTrack for call uplink audio injection and
     * {@link getCallDownlinkExtractionAudioRecord(AudioFormat)} can be used to obtain
     * an AudioRecord for call downlink audio extraction.
     * @return true if PSTN call audio is accessible, false otherwise.
     */
    @TestApi
    @SystemApi
    @RequiresPermission(Manifest.permission.CALL_AUDIO_INTERCEPTION)
    public boolean isPstnCallAudioInterceptable() {
        final IAudioService service = getService();
        try {
            return service.isPstnCallAudioInterceptable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @IntDef(flag = false, prefix = "CALL_REDIRECT_", value = {
            CALL_REDIRECT_NONE,
            CALL_REDIRECT_PSTN,
            CALL_REDIRECT_VOIP }
            )
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallRedirectionMode {}

    /**
     * Not used for call redirection
     * @hide
     */
    public static final int CALL_REDIRECT_NONE = 0;
    /**
     * Used to redirect  PSTN call
     * @hide
     */
    public static final int CALL_REDIRECT_PSTN = 1;
    /**
     * Used to redirect  VoIP call
     * @hide
     */
    public static final int CALL_REDIRECT_VOIP = 2;


    private @CallRedirectionMode int getCallRedirectMode() {
        int mode = getMode();
        if (mode == MODE_IN_CALL || mode == MODE_CALL_SCREENING
                || mode == MODE_CALL_REDIRECT) {
            return CALL_REDIRECT_PSTN;
        } else if (mode == MODE_IN_COMMUNICATION || mode == MODE_COMMUNICATION_REDIRECT) {
            return CALL_REDIRECT_VOIP;
        }
        return CALL_REDIRECT_NONE;
    }

    private void checkCallRedirectionFormat(AudioFormat format, boolean isOutput) {
        if (format.getEncoding() != AudioFormat.ENCODING_PCM_16BIT
                && format.getEncoding() != AudioFormat.ENCODING_PCM_FLOAT) {
            throw new UnsupportedOperationException(" Unsupported encoding ");
        }
        if (format.getSampleRate() < 8000
                || format.getSampleRate() > 48000) {
            throw new UnsupportedOperationException(" Unsupported sample rate ");
        }
        if (isOutput && format.getChannelMask() != AudioFormat.CHANNEL_OUT_MONO
                && format.getChannelMask() != AudioFormat.CHANNEL_OUT_STEREO) {
            throw new UnsupportedOperationException(" Unsupported output channel mask ");
        }
        if (!isOutput && format.getChannelMask() != AudioFormat.CHANNEL_IN_MONO
                && format.getChannelMask() != AudioFormat.CHANNEL_IN_STEREO) {
            throw new UnsupportedOperationException(" Unsupported input channel mask ");
        }
    }

    class CallIRedirectionClientInfo {
        public WeakReference trackOrRecord;
        public int redirectMode;
    }

    private Object mCallRedirectionLock = new Object();
    @GuardedBy("mCallRedirectionLock")
    private CallInjectionModeChangedListener mCallRedirectionModeListener;
    @GuardedBy("mCallRedirectionLock")
    private ArrayList<CallIRedirectionClientInfo> mCallIRedirectionClients;

    /**
     * @hide
     * Returns an AudioTrack that can be used to inject audio to an active call uplink.
     * This can be used for functions like call screening or call audio redirection and is reserved
     * to system apps with privileged permission.
     * @param format the desired audio format for audio playback.
     * p>Formats accepted are:
     * <ul>
     *   <li><em>Sampling rate</em> - 8kHz to 48kHz. </li>
     *   <li><em>Channel mask</em> - Mono or Stereo </li>
     *   <li><em>Sample format</em> - PCM 16 bit or FLOAT 32 bit </li>
     * </ul>
     *
     * @return The AudioTrack used for audio injection
     * @throws NullPointerException if AudioFormat argument is null.
     * @throws UnsupportedOperationException if on unsupported AudioFormat is specified.
     * @throws IllegalArgumentException if an invalid AudioFormat is specified.
     * @throws SecurityException if permission CALL_AUDIO_INTERCEPTION  is missing .
     * @throws IllegalStateException if current audio mode is not MODE_IN_CALL,
     *         MODE_IN_COMMUNICATION, MODE_CALL_SCREENING, MODE_CALL_REDIRECT
     *         or MODE_COMMUNICATION_REDIRECT.
     */
    @TestApi
    @SystemApi
    @RequiresPermission(Manifest.permission.CALL_AUDIO_INTERCEPTION)
    public @NonNull AudioTrack getCallUplinkInjectionAudioTrack(@NonNull AudioFormat format) {
        Objects.requireNonNull(format);
        checkCallRedirectionFormat(format, true /* isOutput */);

        AudioTrack track = null;
        int redirectMode = getCallRedirectMode();
        if (redirectMode == CALL_REDIRECT_NONE) {
            throw new IllegalStateException(
                    " not available in mode " + AudioSystem.modeToString(getMode()));
        } else if (redirectMode == CALL_REDIRECT_PSTN && !isPstnCallAudioInterceptable()) {
            throw new UnsupportedOperationException(" PSTN Call audio not accessible ");
        }

        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setSystemUsage(AudioAttributes.USAGE_CALL_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(format)
                .setCallRedirectionMode(redirectMode)
                .build();

        if (track != null && track.getState() != AudioTrack.STATE_UNINITIALIZED) {
            synchronized (mCallRedirectionLock) {
                if (mCallRedirectionModeListener == null) {
                    mCallRedirectionModeListener = new CallInjectionModeChangedListener();
                    try {
                        addOnModeChangedListener(
                                Executors.newSingleThreadExecutor(), mCallRedirectionModeListener);
                    } catch (Exception e) {
                        Log.e(TAG, "addOnModeChangedListener failed with exception: " + e);
                        mCallRedirectionModeListener = null;
                        throw new UnsupportedOperationException(" Cannot register mode listener ");
                    }
                    mCallIRedirectionClients = new ArrayList<CallIRedirectionClientInfo>();
                }
                CallIRedirectionClientInfo info = new CallIRedirectionClientInfo();
                info.redirectMode = redirectMode;
                info.trackOrRecord = new WeakReference<AudioTrack>(track);
                mCallIRedirectionClients.add(info);
            }
        } else {
            throw new UnsupportedOperationException(" Cannot create the AudioTrack");
        }
        return track;
    }

    /**
     * @hide
     * Returns an AudioRecord that can be used to extract audio from an active call downlink.
     * This can be used for functions like call screening or call audio redirection and is reserved
     * to system apps with privileged permission.
     * @param format the desired audio format for audio capture.
     *<p>Formats accepted are:
     * <ul>
     *   <li><em>Sampling rate</em> - 8kHz to 48kHz. </li>
     *   <li><em>Channel mask</em> - Mono or Stereo </li>
     *   <li><em>Sample format</em> - PCM 16 bit or FLOAT 32 bit </li>
     * </ul>
     *
     * @return The AudioRecord used for audio extraction
     * @throws UnsupportedOperationException if on unsupported AudioFormat is specified.
     * @throws IllegalArgumentException if an invalid AudioFormat is specified.
     * @throws NullPointerException if AudioFormat argument is null.
     * @throws SecurityException if permission CALL_AUDIO_INTERCEPTION  is missing .
     * @throws IllegalStateException if current audio mode is not MODE_IN_CALL,
     *         MODE_IN_COMMUNICATION, MODE_CALL_SCREENING, MODE_CALL_REDIRECT
     *         or MODE_COMMUNICATION_REDIRECT.
     */
    @TestApi
    @SystemApi
    @RequiresPermission(Manifest.permission.CALL_AUDIO_INTERCEPTION)
    public @NonNull AudioRecord getCallDownlinkExtractionAudioRecord(@NonNull AudioFormat format) {
        Objects.requireNonNull(format);
        checkCallRedirectionFormat(format, false /* isOutput */);

        AudioRecord record = null;
        int redirectMode = getCallRedirectMode();
        if (redirectMode == CALL_REDIRECT_NONE) {
            throw new IllegalStateException(
                    " not available in mode " + AudioSystem.modeToString(getMode()));
        } else if (redirectMode == CALL_REDIRECT_PSTN && !isPstnCallAudioInterceptable()) {
            throw new UnsupportedOperationException(" PSTN Call audio not accessible ");
        }

        record = new AudioRecord.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setInternalCapturePreset(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                        .build())
                .setAudioFormat(format)
                .setCallRedirectionMode(redirectMode)
                .build();

        if (record != null && record.getState() != AudioRecord.STATE_UNINITIALIZED) {
            synchronized (mCallRedirectionLock) {
                if (mCallRedirectionModeListener == null) {
                    mCallRedirectionModeListener = new CallInjectionModeChangedListener();
                    try {
                        addOnModeChangedListener(
                                Executors.newSingleThreadExecutor(), mCallRedirectionModeListener);
                    } catch (Exception e) {
                        Log.e(TAG, "addOnModeChangedListener failed with exception: " + e);
                        mCallRedirectionModeListener = null;
                        throw new UnsupportedOperationException(" Cannot register mode listener ");
                    }
                    mCallIRedirectionClients = new ArrayList<CallIRedirectionClientInfo>();
                }
                CallIRedirectionClientInfo info = new CallIRedirectionClientInfo();
                info.redirectMode = redirectMode;
                info.trackOrRecord = new WeakReference<AudioRecord>(record);
                mCallIRedirectionClients.add(info);
            }
        } else {
            throw new UnsupportedOperationException(" Cannot create the AudioRecord");
        }
        return record;
    }

    class CallInjectionModeChangedListener implements OnModeChangedListener {
        @Override
        public void onModeChanged(@AudioMode int mode) {
            synchronized (mCallRedirectionLock) {
                final ArrayList<CallIRedirectionClientInfo> clientInfos =
                        (ArrayList<CallIRedirectionClientInfo>) mCallIRedirectionClients.clone();
                for (CallIRedirectionClientInfo info : clientInfos) {
                    Object trackOrRecord = info.trackOrRecord.get();
                    if (trackOrRecord != null) {
                        if ((info.redirectMode ==  CALL_REDIRECT_PSTN
                                && mode != MODE_IN_CALL && mode != MODE_CALL_SCREENING
                                && mode != MODE_CALL_REDIRECT)
                                || (info.redirectMode == CALL_REDIRECT_VOIP
                                    && mode != MODE_IN_COMMUNICATION
                                    && mode != MODE_COMMUNICATION_REDIRECT)) {
                            if (trackOrRecord instanceof AudioTrack) {
                                AudioTrack track = (AudioTrack) trackOrRecord;
                                track.release();
                            } else {
                                AudioRecord record = (AudioRecord) trackOrRecord;
                                record.release();
                            }
                            mCallIRedirectionClients.remove(info);
                        }
                    }
                }
                if (mCallIRedirectionClients.isEmpty()) {
                    try {
                        if (mCallRedirectionModeListener != null) {
                            removeOnModeChangedListener(mCallRedirectionModeListener);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "removeOnModeChangedListener failed with exception: " + e);
                    } finally {
                        mCallRedirectionModeListener = null;
                        mCallIRedirectionClients = null;
                    }
                }
            }
        }
    }

    //---------------------------------------------------------
    // audio device connection-dependent muting
    /**
     * @hide
     * Mute a set of playback use cases until a given audio device is connected.
     * Automatically unmute upon connection of the device, or after the given timeout, whichever
     * happens first.
     * @param usagesToMute non-empty array of {@link AudioAttributes} usages (for example
     *                     {@link AudioAttributes#USAGE_MEDIA}) to mute until the
     *                     device connects
     * @param device the audio device expected to connect within the timeout duration
     * @param timeout the maximum amount of time to wait for the device connection
     * @param timeUnit the unit for the timeout
     * @throws IllegalStateException when trying to issue the command while another is already in
     *         progress and hasn't been cancelled by
     *         {@link #cancelMuteAwaitConnection(AudioDeviceAttributes)}. See
     *         {@link #getMutingExpectedDevice()} to check if a muting command is active.
     * @see #registerMuteAwaitConnectionCallback(Executor, AudioManager.MuteAwaitConnectionCallback)
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void muteAwaitConnection(@NonNull int[] usagesToMute,
            @NonNull AudioDeviceAttributes device,
            long timeout, @NonNull TimeUnit timeUnit) throws IllegalStateException {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be greater than 0");
        }
        Objects.requireNonNull(usagesToMute);
        if (usagesToMute.length == 0) {
            throw new IllegalArgumentException("Array of usages to mute cannot be empty");
        }
        Objects.requireNonNull(device);
        Objects.requireNonNull(timeUnit);
        try {
            getService().muteAwaitConnection(usagesToMute, device, timeUnit.toMillis(timeout));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Query which audio device, if any, is causing some playback use cases to be muted until it
     * connects.
     * @return the audio device used in
     *        {@link #muteAwaitConnection(int[], AudioDeviceAttributes, long, TimeUnit)}, or null
     *        if there is no active muting command (either because the muting command was not issued
     *        or because it timed out)
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @Nullable AudioDeviceAttributes getMutingExpectedDevice() {
        try {
            return getService().getMutingExpectedDevice();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Cancel a {@link #muteAwaitConnection(int[], AudioDeviceAttributes, long, TimeUnit)}
     * command.
     * @param device the device whose connection was expected when the {@code muteAwaitConnection}
     *               command was issued.
     * @throws IllegalStateException when trying to issue the command for a device whose connection
     *         is not anticipated by a previous call to
     *         {@link #muteAwaitConnection(int[], AudioDeviceAttributes, long, TimeUnit)}
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void cancelMuteAwaitConnection(@NonNull AudioDeviceAttributes device)
            throws IllegalStateException {
        Objects.requireNonNull(device);
        try {
            getService().cancelMuteAwaitConnection(device);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * A callback class to receive events about the muting and unmuting of playback use cases
     * conditional on the upcoming connection of an audio device.
     * @see #registerMuteAwaitConnectionCallback(Executor, AudioManager.MuteAwaitConnectionCallback)
     */
    @SystemApi
    public abstract static class MuteAwaitConnectionCallback {

        /**
         * An event where the expected audio device connected
         * @see MuteAwaitConnectionCallback#onUnmutedEvent(int, AudioDeviceAttributes, int[])
         */
        public static final int EVENT_CONNECTION = 1;
        /**
         * An event where the expected audio device failed connect before the timeout happened
         * @see MuteAwaitConnectionCallback#onUnmutedEvent(int, AudioDeviceAttributes, int[])
         */
        public static final int EVENT_TIMEOUT    = 2;
        /**
         * An event where the {@code muteAwaitConnection()} command
         * was cancelled with {@link #cancelMuteAwaitConnection(AudioDeviceAttributes)}
         * @see MuteAwaitConnectionCallback#onUnmutedEvent(int, AudioDeviceAttributes, int[])
         */
        public static final int EVENT_CANCEL     = 3;

        /** @hide */
        @IntDef(flag = false, prefix = "EVENT_", value = {
                EVENT_CONNECTION,
                EVENT_TIMEOUT,
                EVENT_CANCEL }
        )
        @Retention(RetentionPolicy.SOURCE)
        public @interface UnmuteEvent {}

        /**
         * Called when a number of playback use cases are muted in response to a call to
         * {@link #muteAwaitConnection(int[], AudioDeviceAttributes, long, TimeUnit)}.
         * @param device the audio device whose connection is expected. Playback use cases are
         *               unmuted when that device connects
         * @param mutedUsages an array of {@link AudioAttributes} usages that describe the affected
         *                    playback use cases.
         */
        public void onMutedUntilConnection(
                @NonNull AudioDeviceAttributes device,
                @NonNull int[] mutedUsages) {}

        /**
         * Called when an event occurred that caused playback uses cases to be unmuted
         * @param unmuteEvent the nature of the event
         * @param device the device that was expected to connect
         * @param mutedUsages the array of {@link AudioAttributes} usages that were muted until
         *                    the event occurred
         */
        public void onUnmutedEvent(
                @UnmuteEvent int unmuteEvent,
                @NonNull AudioDeviceAttributes device, @NonNull int[] mutedUsages) {}
    }


    /**
     * @hide
     * Register a callback to receive updates on the playback muting conditional on a specific
     * audio device connection.
     * @param executor the {@link Executor} handling the callback
     * @param callback the callback to register
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void registerMuteAwaitConnectionCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull MuteAwaitConnectionCallback callback) {
        synchronized (mMuteAwaitConnectionListenerLock) {
            final Pair<ArrayList<ListenerInfo<MuteAwaitConnectionCallback>>,
                    MuteAwaitConnectionDispatcherStub> res =
                    CallbackUtil.addListener("registerMuteAwaitConnectionCallback",
                            executor, callback, mMuteAwaitConnectionListeners,
                            mMuteAwaitConnDispatcherStub,
                            () -> new MuteAwaitConnectionDispatcherStub(),
                            stub -> stub.register(true));
            mMuteAwaitConnectionListeners = res.first;
            mMuteAwaitConnDispatcherStub = res.second;
        }
    }

    /**
     * @hide
     * Unregister a previously registered callback for playback muting conditional on device
     * connection.
     * @param callback the callback to unregister
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void unregisterMuteAwaitConnectionCallback(
            @NonNull MuteAwaitConnectionCallback callback) {
        synchronized (mMuteAwaitConnectionListenerLock) {
            final Pair<ArrayList<ListenerInfo<MuteAwaitConnectionCallback>>,
                    MuteAwaitConnectionDispatcherStub> res =
                    CallbackUtil.removeListener("unregisterMuteAwaitConnectionCallback",
                            callback, mMuteAwaitConnectionListeners, mMuteAwaitConnDispatcherStub,
                            stub -> stub.register(false));
            mMuteAwaitConnectionListeners = res.first;
            mMuteAwaitConnDispatcherStub = res.second;
        }
    }

    /**
     * Add UIDs that can be considered as assistant.
     *
     * @param assistantUids UIDs of the services that can be considered as assistant.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void addAssistantServicesUids(@NonNull int[] assistantUids) {
        try {
            getService().addAssistantServicesUids(assistantUids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove UIDs that can be considered as assistant.
     *
     * @param assistantUids UIDs of the services that should be remove.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void removeAssistantServicesUids(@NonNull int[] assistantUids) {
        try {
            getService().removeAssistantServicesUids(assistantUids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the assistants UIDs that been added with the
     * {@link #addAssistantServicesUids(int[])} and not yet removed with
     * {@link #removeAssistantServicesUids(int[])}
     *
     * <p> Note that during native audioserver crash and after boot up the list of assistant
     * UIDs will be reset to an empty list (i.e. no UID will be considered as assistant)
     * Just after user switch, the list of assistant will also reset to empty.
     * In both cases,The component's UID of the assistiant role or assistant setting will be
     * automitically added to the list by the audio service.
     *
     * @return array of assistants UIDs
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @NonNull int[] getAssistantServicesUids() {
        try {
            int[] uids = getService().getAssistantServicesUids();
            return Arrays.copyOf(uids, uids.length);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets UIDs that can be considered as active assistant. Calling the API with a new array will
     * overwrite previous UIDs. If the array of UIDs is empty then no UID will be considered active.
     * In this manner calling the API with an empty array will remove all UIDs previously set.
     *
     * @param assistantUids UIDs of the services that can be considered active assistant. Can be
     * an empty array, for this no UID will be considered active.
     *
     * <p> Note that during audio service crash reset and after boot up the list of active assistant
     * UIDs will be reset to an empty list (i.e. no UID will be considered as an active assistant).
     * Just after user switch the list of active assistant will also reset to empty.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setActiveAssistantServiceUids(@NonNull int[]  assistantUids) {
        try {
            getService().setActiveAssistantServiceUids(assistantUids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get active assistant UIDs last set with the
     * {@link #setActiveAssistantServiceUids(int[])}
     *
     * @return array of active assistants UIDs
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @NonNull int[] getActiveAssistantServicesUids() {
        try {
            int[] uids = getService().getActiveAssistantServiceUids();
            return Arrays.copyOf(uids, uids.length);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns an {@link AudioHalVersionInfo} indicating the Audio Hal Version. If there is no audio
     * HAL found, null will be returned.
     *
     * @return @see @link #AudioHalVersionInfo The version of Audio HAL.
     * @hide
     */
    @TestApi
    public static @Nullable AudioHalVersionInfo getHalVersion() {
        try {
            return getService().getHalVersion();
        } catch (RemoteException e) {
            Log.e(TAG, "Error querying getHalVersion", e);
            throw e.rethrowFromSystemServer();
        }
    }

    //====================================================================
    // Preferred mixer attributes

    /**
     * Returns the {@link AudioMixerAttributes} that can be used to set as preferred mixer
     * attributes via {@link #setPreferredMixerAttributes(
     * AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)}.
     * <p>Note that only USB devices are guaranteed to expose configurable mixer attributes. An
     * empty list may be returned for all other types of devices as they may not allow dynamic
     * configuration.
     *
     * @param device the device to query
     * @return a list of {@link AudioMixerAttributes} that can be used as preferred mixer attributes
     *         for the given device.
     * @see #setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)
     */
    @NonNull
    public List<AudioMixerAttributes> getSupportedMixerAttributes(@NonNull AudioDeviceInfo device) {
        Objects.requireNonNull(device);
        List<AudioMixerAttributes> mixerAttrs = new ArrayList<>();
        return (AudioSystem.getSupportedMixerAttributes(device.getId(), mixerAttrs)
                == AudioSystem.SUCCESS) ? mixerAttrs : new ArrayList<>();
    }

    /**
     * Configures the mixer attributes for a particular {@link AudioAttributes} over a given
     * {@link AudioDeviceInfo}.
     * <p>Call {@link #getSupportedMixerAttributes(AudioDeviceInfo)} to determine which mixer
     * attributes can be used with the given device.
     * <p>The ownership of preferred mixer attributes is recognized by uid. When a playback from the
     * same uid is routed to the given audio device when calling this API, the output mixer/stream
     * will be configured with the values previously set via this API.
     * <p>Use {@link #clearPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo)}
     * to cancel setting mixer attributes for this {@link AudioAttributes}.
     *
     * @param attributes the {@link AudioAttributes} whose mixer attributes should be set.
     *                   Currently, only {@link AudioAttributes#USAGE_MEDIA} is supported. When
     *                   playing audio targeted at the given device, use the same attributes for
     *                   playback.
     * @param device the device to be routed. Currently, only USB device will be allowed.
     * @param mixerAttributes the preferred mixer attributes. When playing audio targeted at the
     *                        given device, use the same {@link AudioFormat} for both playback
     *                        and the mixer attributes.
     * @return true only if the preferred mixer attributes are set successfully.
     * @see #getPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo)
     * @see #clearPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo)
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)
    public boolean setPreferredMixerAttributes(@NonNull AudioAttributes attributes,
            @NonNull AudioDeviceInfo device,
            @NonNull AudioMixerAttributes mixerAttributes) {
        Objects.requireNonNull(attributes);
        Objects.requireNonNull(device);
        Objects.requireNonNull(mixerAttributes);
        try {
            final int status = getService().setPreferredMixerAttributes(
                    attributes, device.getId(), mixerAttributes);
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns current preferred mixer attributes that is set via
     * {@link #setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)}
     *
     * @param attributes the {@link AudioAttributes} whose mixer attributes should be set.
     * @param device the expected routing device
     * @return the preferred mixer attributes, which will be null when no preferred mixer attributes
     *         have been set, or when they have been cleared.
     * @see #setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)
     * @see #clearPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo)
     */
    @Nullable
    public AudioMixerAttributes getPreferredMixerAttributes(
            @NonNull AudioAttributes attributes,
            @NonNull AudioDeviceInfo device) {
        Objects.requireNonNull(attributes);
        Objects.requireNonNull(device);
        List<AudioMixerAttributes> mixerAttrList = new ArrayList<>();
        int ret = AudioSystem.getPreferredMixerAttributes(
                attributes, device.getId(), mixerAttrList);
        if (ret == AudioSystem.SUCCESS) {
            return mixerAttrList.isEmpty() ? null : mixerAttrList.get(0);
        } else {
            Log.e(TAG, "Failed calling getPreferredMixerAttributes, ret=" + ret);
            return null;
        }
    }

    /**
     * Clears the current preferred mixer attributes that were previously set via
     * {@link #setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)}
     *
     * @param attributes the {@link AudioAttributes} whose mixer attributes should be cleared.
     * @param device the expected routing device
     * @return true only if the preferred mixer attributes are removed successfully.
     * @see #setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)
     * @see #getPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo)
     */
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)
    public boolean clearPreferredMixerAttributes(
            @NonNull AudioAttributes attributes,
            @NonNull AudioDeviceInfo device) {
        Objects.requireNonNull(attributes);
        Objects.requireNonNull(device);
        try {
            final int status = getService().clearPreferredMixerAttributes(
                    attributes, device.getId());
            return status == AudioSystem.SUCCESS;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Interface to be notified of changes in the preferred mixer attributes.
     * <p>Note that this listener will only be invoked whenever
     * {@link #setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)}
     * or {@link #clearPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo)} or device
     * disconnection causes a change in preferred mixer attributes.
     * @see #setPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo, AudioMixerAttributes)
     * @see #clearPreferredMixerAttributes(AudioAttributes, AudioDeviceInfo)
     */
    public interface OnPreferredMixerAttributesChangedListener {
        /**
         * Called on the listener to indicate that the preferred mixer attributes for the audio
         * attributes over the given device has changed.
         *
         * @param attributes the audio attributes for playback
         * @param device the targeted device
         * @param mixerAttributes the {@link AudioMixerAttributes} that contains information for
         *                        preferred mixer attributes or null if preferred mixer attributes
         *                        is cleared
         */
        void onPreferredMixerAttributesChanged(
                @NonNull AudioAttributes attributes,
                @NonNull AudioDeviceInfo device,
                @Nullable AudioMixerAttributes mixerAttributes);
    }

    /**
     * Manage the {@link OnPreferredMixerAttributesChangedListener} listeners and the
     * {@link PreferredMixerAttributesDispatcherStub}.
     */
    private final CallbackUtil.LazyListenerManager<OnPreferredMixerAttributesChangedListener>
            mPrefMixerAttributesListenerMgr = new CallbackUtil.LazyListenerManager();

    /**
     * Adds a listener for being notified of changes to the preferred mixer attributes.
     * @param executor the executor to execute the callback
     * @param listener the listener to be notified of changes in the preferred mixer attributes.
     */
    public void addOnPreferredMixerAttributesChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnPreferredMixerAttributesChangedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        mPrefMixerAttributesListenerMgr.addListener(executor, listener,
                "addOnPreferredMixerAttributesChangedListener",
                () -> new PreferredMixerAttributesDispatcherStub());
    }

    /**
     * Removes a previously added listener of changes to the preferred mixer attributes.
     * @param listener the listener to be notified of changes in the preferred mixer attributes,
     *                 which were added via {@link #addOnPreferredMixerAttributesChangedListener(
     *                 Executor, OnPreferredMixerAttributesChangedListener)}.
     */
    public void removeOnPreferredMixerAttributesChangedListener(
            @NonNull OnPreferredMixerAttributesChangedListener listener) {
        Objects.requireNonNull(listener);
        mPrefMixerAttributesListenerMgr.removeListener(listener,
                "removeOnPreferredMixerAttributesChangedListener");
    }

    private final class PreferredMixerAttributesDispatcherStub
            extends IPreferredMixerAttributesDispatcher.Stub
            implements CallbackUtil.DispatcherStub {

        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    getService().registerPreferredMixerAttributesDispatcher(this);
                } else {
                    getService().unregisterPreferredMixerAttributesDispatcher(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchPrefMixerAttributesChanged(@NonNull AudioAttributes attr,
                                                       int deviceId,
                                                       @Nullable AudioMixerAttributes mixerAttr) {
            // TODO: If the device is disconnected, we may not be able to find the device with
            // given device id. We need a better to carry the device information via binder.
            AudioDeviceInfo device = getDeviceForPortId(deviceId, GET_DEVICES_OUTPUTS);
            if (device == null) {
                Log.d(TAG, "Drop preferred mixer attributes changed as the device("
                        + deviceId + ") is disconnected");
                return;
            }
            mPrefMixerAttributesListenerMgr.callListeners(
                    (listener) -> listener.onPreferredMixerAttributesChanged(
                            attr, device, mixerAttr));
        }
    }

    /**
     * Requests if the implementation supports controlling the latency modes
     * over the Bluetooth A2DP or LE Audio links.
     *
     * @return true if supported, false otherwise
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean supportsBluetoothVariableLatency() {
        try {
            return getService().supportsBluetoothVariableLatency();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables the variable Bluetooth latency control mechanism in the
     * audio framework and the audio HAL. This does not apply to the latency mode control
     * on the spatializer output as this is a built-in feature.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setBluetoothVariableLatencyEnabled(boolean enabled) {
        try {
            getService().setBluetoothVariableLatencyEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates if the variable Bluetooth latency control mechanism is enabled or disabled.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean isBluetoothVariableLatencyEnabled() {
        try {
            return getService().isBluetoothVariableLatencyEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    //====================================================================
    // Stream aliasing changed listener, getter for stream alias or independent streams

    /**
     * manages the stream aliasing listeners and StreamAliasingDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<Runnable> mStreamAliasingListenerMgr =
            new CallbackUtil.LazyListenerManager();


    final class StreamAliasingDispatcherStub extends IStreamAliasingDispatcher.Stub
            implements CallbackUtil.DispatcherStub {

        @Override
        public void register(boolean register) {
            try {
                getService().registerStreamAliasingDispatcher(this, register);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchStreamAliasingChanged() {
            mStreamAliasingListenerMgr.callListeners((listener) -> listener.run());
        }
    }

    /**
     * @hide
     * Adds a listener to be notified of changes to volume stream type aliasing.
     * See {@link #getIndependentStreamTypes()} and {@link #getStreamTypeAlias(int)}
     * @param executor the Executor running the listener
     * @param onStreamAliasingChangedListener the listener to add for the aliasing changes
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void addOnStreamAliasingChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Runnable onStreamAliasingChangedListener) {
        mStreamAliasingListenerMgr.addListener(executor, onStreamAliasingChangedListener,
                "addOnStreamAliasingChangedListener",
                () -> new StreamAliasingDispatcherStub());
    }

    /**
     * @hide
     * Removes a previously added listener for changes to stream aliasing.
     * See {@link #getIndependentStreamTypes()} and {@link #getStreamTypeAlias(int)}
     * @see #addOnStreamAliasingChangedListener(Executor, Runnable)
     * @param onStreamAliasingChangedListener the previously added listener of aliasing changes
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void removeOnStreamAliasingChangedListener(
            @NonNull Runnable onStreamAliasingChangedListener) {
        mStreamAliasingListenerMgr.removeListener(onStreamAliasingChangedListener,
                "removeOnStreamAliasingChangedListener");
    }

    /**
     * @hide
     * Test method to temporarily override whether STREAM_NOTIFICATION is aliased to STREAM_RING,
     * volumes will be updated in case of a change.
     * @param isAliased if true, STREAM_NOTIFICATION is aliased to STREAM_RING
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setNotifAliasRingForTest(boolean isAliased) {
        final IAudioService service = getService();
        try {
            service.setNotifAliasRingForTest(isAliased);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Blocks until permission updates have propagated through the audio system.
     * Only useful in tests, where adoptShellPermissions can change the permission state of
     * an app without the app being killed.
     */
    @TestApi
    @SuppressWarnings("UnflaggedApi") // @TestApi without associated feature.
    public void permissionUpdateBarrier() {
        final IAudioService service = getService();
        try {
            service.permissionUpdateBarrier();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * @hide
     * Return the list of independent stream types for volume control.
     * A stream type is considered independent when the volume changes of that type do not
     * affect any other independent volume control stream type.
     * An independent stream type is its own alias when using {@link #getStreamTypeAlias(int)}.
     * @return list of independent stream types, where each value can be one of
     *     {@link #STREAM_VOICE_CALL}, {@link #STREAM_SYSTEM}, {@link #STREAM_RING},
     *     {@link #STREAM_MUSIC}, {@link #STREAM_ALARM}, {@link #STREAM_NOTIFICATION},
     *     {@link #STREAM_DTMF} and {@link #STREAM_ACCESSIBILITY}.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public @NonNull List<Integer> getIndependentStreamTypes() {
        final IAudioService service = getService();
        try {
            return service.getIndependentStreamTypes();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return the stream type that a given stream is aliased to.
     * A stream alias means that any change to the source stream will also be applied to the alias,
     * and vice-versa.
     * If a stream is independent (i.e. part of the stream types returned by
     * {@link #getIndependentStreamTypes()}), its alias is itself.
     * @param sourceStreamType the stream type to query for the alias.
     * @return the stream type the source type is aliased to.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public @PublicStreamTypes int getStreamTypeAlias(@PublicStreamTypes int sourceStreamType) {
        final IAudioService service = getService();
        try {
            return service.getStreamTypeAlias(sourceStreamType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether the system uses {@link AudioVolumeGroup} for volume control
     * @return true when volume control is performed through volume groups, false if it uses
     *     stream types.
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean isVolumeControlUsingVolumeGroups() {
        final IAudioService service = getService();
        try {
            return service.isVolumeControlUsingVolumeGroups();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Checks whether a notification sound should be played or not, as reported by the state
     * of the audio framework. Querying whether playback should proceed is favored over
     * playing and letting the sound be muted or not.
     * @param aa the {@link AudioAttributes} of the notification about to maybe play
     * @return true if the audio framework state is such that the notification should be played
     *    because at time of checking, and the notification will be heard,
     *    false otherwise
     */
    @TestApi
    @FlaggedApi(FLAG_FOCUS_EXCLUSIVE_WITH_RECORDING)
    @RequiresPermission(android.Manifest.permission.QUERY_AUDIO_STATE)
    public boolean shouldNotificationSoundPlay(@NonNull final AudioAttributes aa) {
        final IAudioService service = getService();
        try {
            return service.shouldNotificationSoundPlay(Objects.requireNonNull(aa));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    //====================================================================
    // Mute await connection

    private final Object mMuteAwaitConnectionListenerLock = new Object();

    @GuardedBy("mMuteAwaitConnectionListenerLock")
    private @Nullable ArrayList<ListenerInfo<MuteAwaitConnectionCallback>>
            mMuteAwaitConnectionListeners;

    @GuardedBy("mMuteAwaitConnectionListenerLock")
    private MuteAwaitConnectionDispatcherStub mMuteAwaitConnDispatcherStub;

    private final class MuteAwaitConnectionDispatcherStub
            extends IMuteAwaitConnectionCallback.Stub {
        public void register(boolean register) {
            try {
                getService().registerMuteAwaitConnectionDispatcher(this, register);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        @Override
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        public void dispatchOnMutedUntilConnection(AudioDeviceAttributes device,
                int[] mutedUsages) {
            CallbackUtil.callListeners(mMuteAwaitConnectionListeners,
                    mMuteAwaitConnectionListenerLock,
                    (listener) -> listener.onMutedUntilConnection(device, mutedUsages));
        }

        @Override
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        public void dispatchOnUnmutedEvent(int event, AudioDeviceAttributes device,
                int[] mutedUsages) {
            CallbackUtil.callListeners(mMuteAwaitConnectionListeners,
                    mMuteAwaitConnectionListenerLock,
                    (listener) -> listener.onUnmutedEvent(event, device, mutedUsages));
        }
    }

    //====================================================================
    // Flag related utilities

    private boolean mIsAutomotive = false;

    private void initPlatform() {
        try {
            final Context context = getContext();
            if (context != null) {
                mIsAutomotive = context.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying system feature for AUTOMOTIVE", e);
        }
    }

    private boolean applyAutoHardening() {
        if (mIsAutomotive && autoPublicVolumeApiHardening()) {
            return true;
        }
        return false;
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
