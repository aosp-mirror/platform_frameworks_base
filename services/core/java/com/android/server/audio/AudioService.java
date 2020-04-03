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

package com.android.server.audio;

import static android.Manifest.permission.REMOTE_AUDIO_PLAYBACK;
import static android.media.AudioManager.RINGER_MODE_NORMAL;
import static android.media.AudioManager.RINGER_MODE_SILENT;
import static android.media.AudioManager.RINGER_MODE_VIBRATE;
import static android.media.AudioManager.STREAM_SYSTEM;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.provider.Settings.Secure.VOLUME_HUSH_MUTE;
import static android.provider.Settings.Secure.VOLUME_HUSH_OFF;
import static android.provider.Settings.Secure.VOLUME_HUSH_VIBRATE;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.app.NotificationManager;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.hdmi.HdmiAudioSystemClient;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPlaybackClient;
import android.hardware.hdmi.HdmiTvClient;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbManager;
import android.hidl.manager.V1_0.IServiceManager;
import android.media.AudioAttributes;
import android.media.AudioAttributes.AttributeSystemUsage;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IAudioServerStateDispatcher;
import android.media.IAudioService;
import android.media.IPlaybackConfigDispatcher;
import android.media.IRecordingConfigDispatcher;
import android.media.IRingtonePlayer;
import android.media.IStrategyPreferredDeviceDispatcher;
import android.media.IVolumeController;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetrics;
import android.media.PlayerBase;
import android.media.VolumePolicy;
import android.media.audiofx.AudioEffect;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.media.projection.IMediaProjectionManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.UserManagerInternal.UserRestrictionsListener;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.System;
import android.service.notification.ZenModeConfig;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.IntArray;
import android.util.Log;
import android.util.MathUtils;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.audio.AudioServiceEvents.PhoneStateEvent;
import com.android.server.audio.AudioServiceEvents.VolumeEvent;
import com.android.server.pm.UserManagerService;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The implementation of the audio service for volume, audio focus, device management...
 * <p>
 * This implementation focuses on delivering a responsive UI. Most methods are
 * asynchronous to external calls. For example, the task of setting a volume
 * will update our internal state, but in a separate thread will set the system
 * volume and later persist to the database. Similarly, setting the ringer mode
 * will update the state and broadcast a change and in a separate thread later
 * persist the ringer mode.
 *
 * @hide
 */
public class AudioService extends IAudioService.Stub
        implements AccessibilityManager.TouchExplorationStateChangeListener,
            AccessibilityManager.AccessibilityServicesStateChangeListener {

    private static final String TAG = "AS.AudioService";

    /** Debug audio mode */
    protected static final boolean DEBUG_MODE = false;

    /** Debug audio policy feature */
    protected static final boolean DEBUG_AP = false;

    /** Debug volumes */
    protected static final boolean DEBUG_VOL = false;

    /** debug calls to devices APIs */
    protected static final boolean DEBUG_DEVICES = false;

    /** How long to delay before persisting a change in volume/ringer mode. */
    private static final int PERSIST_DELAY = 500;

    /** How long to delay after a volume down event before unmuting a stream */
    private static final int UNMUTE_STREAM_DELAY = 350;

    /**
     * Delay before disconnecting a device that would cause BECOMING_NOISY intent to be sent,
     * to give a chance to applications to pause.
     */
    @VisibleForTesting
    public static final int BECOMING_NOISY_DELAY_MS = 1000;

    /**
     * Only used in the result from {@link #checkForRingerModeChange(int, int, int)}
     */
    private static final int FLAG_ADJUST_VOLUME = 1;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final AppOpsManager mAppOps;

    // the platform type affects volume and silent mode behavior
    private final int mPlatformType;

    // indicates whether the system maps all streams to a single stream.
    private final boolean mIsSingleVolume;

    private boolean isPlatformVoice() {
        return mPlatformType == AudioSystem.PLATFORM_VOICE;
    }

    /*package*/ boolean isPlatformTelevision() {
        return mPlatformType == AudioSystem.PLATFORM_TELEVISION;
    }

    /*package*/ boolean isPlatformAutomotive() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /** The controller for the volume UI. */
    private final VolumeController mVolumeController = new VolumeController();

    // sendMsg() flags
    /** If the msg is already queued, replace it with this one. */
    private static final int SENDMSG_REPLACE = 0;
    /** If the msg is already queued, ignore this one and leave the old. */
    private static final int SENDMSG_NOOP = 1;
    /** If the msg is already queued, queue this one and leave the old. */
    private static final int SENDMSG_QUEUE = 2;

    // AudioHandler messages
    private static final int MSG_SET_DEVICE_VOLUME = 0;
    private static final int MSG_PERSIST_VOLUME = 1;
    private static final int MSG_PERSIST_VOLUME_GROUP = 2;
    private static final int MSG_PERSIST_RINGER_MODE = 3;
    private static final int MSG_AUDIO_SERVER_DIED = 4;
    private static final int MSG_PLAY_SOUND_EFFECT = 5;
    private static final int MSG_LOAD_SOUND_EFFECTS = 7;
    private static final int MSG_SET_FORCE_USE = 8;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 9;
    private static final int MSG_SET_ALL_VOLUMES = 10;
    private static final int MSG_CHECK_MUSIC_ACTIVE = 11;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME = 12;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED = 13;
    private static final int MSG_PERSIST_SAFE_VOLUME_STATE = 14;
    private static final int MSG_UNLOAD_SOUND_EFFECTS = 15;
    private static final int MSG_SYSTEM_READY = 16;
    private static final int MSG_PERSIST_MUSIC_ACTIVE_MS = 17;
    private static final int MSG_UNMUTE_STREAM = 18;
    private static final int MSG_DYN_POLICY_MIX_STATE_UPDATE = 19;
    private static final int MSG_INDICATE_SYSTEM_READY = 20;
    private static final int MSG_ACCESSORY_PLUG_MEDIA_UNMUTE = 21;
    private static final int MSG_NOTIFY_VOL_EVENT = 22;
    private static final int MSG_DISPATCH_AUDIO_SERVER_STATE = 23;
    private static final int MSG_ENABLE_SURROUND_FORMATS = 24;
    private static final int MSG_UPDATE_RINGER_MODE = 25;
    private static final int MSG_SET_DEVICE_STREAM_VOLUME = 26;
    private static final int MSG_OBSERVE_DEVICES_FOR_ALL_STREAMS = 27;
    private static final int MSG_HDMI_VOLUME_CHECK = 28;
    private static final int MSG_PLAYBACK_CONFIG_CHANGE = 29;
    private static final int MSG_BROADCAST_MICROPHONE_MUTE = 30;
    // start of messages handled under wakelock
    //   these messages can only be queued, i.e. sent with queueMsgUnderWakeLock(),
    //   and not with sendMsg(..., ..., SENDMSG_QUEUE, ...)
    private static final int MSG_DISABLE_AUDIO_FOR_UID = 100;
    // end of messages handled under wakelock

    // retry delay in case of failure to indicate system ready to AudioFlinger
    private static final int INDICATE_SYSTEM_READY_RETRY_DELAY_MS = 1000;

    /** @see AudioSystemThread */
    private AudioSystemThread mAudioSystemThread;
    /** @see AudioHandler */
    private AudioHandler mAudioHandler;
    /** @see VolumeStreamState */
    private VolumeStreamState[] mStreamStates;

    /*package*/ int getVssVolumeForDevice(int stream, int device) {
        return mStreamStates[stream].getIndex(device);
    }

    private SettingsObserver mSettingsObserver;

    private int mMode = AudioSystem.MODE_NORMAL;
    // protects mRingerMode
    private final Object mSettingsLock = new Object();

   /** Maximum volume index values for audio streams */
    protected static int[] MAX_STREAM_VOLUME = new int[] {
        5,  // STREAM_VOICE_CALL
        7,  // STREAM_SYSTEM
        7,  // STREAM_RING
        15, // STREAM_MUSIC
        7,  // STREAM_ALARM
        7,  // STREAM_NOTIFICATION
        15, // STREAM_BLUETOOTH_SCO
        7,  // STREAM_SYSTEM_ENFORCED
        15, // STREAM_DTMF
        15, // STREAM_TTS
        15, // STREAM_ACCESSIBILITY
        15  // STREAM_ASSISTANT
    };

    /** Minimum volume index values for audio streams */
    protected static int[] MIN_STREAM_VOLUME = new int[] {
        1,  // STREAM_VOICE_CALL
        0,  // STREAM_SYSTEM
        0,  // STREAM_RING
        0,  // STREAM_MUSIC
        1,  // STREAM_ALARM
        0,  // STREAM_NOTIFICATION
        0,  // STREAM_BLUETOOTH_SCO
        0,  // STREAM_SYSTEM_ENFORCED
        0,  // STREAM_DTMF
        0,  // STREAM_TTS
        1,  // STREAM_ACCESSIBILITY
        0   // STREAM_ASSISTANT
    };

    /* mStreamVolumeAlias[] indicates for each stream if it uses the volume settings
     * of another stream: This avoids multiplying the volume settings for hidden
     * stream types that follow other stream behavior for volume settings
     * NOTE: do not create loops in aliases!
     * Some streams alias to different streams according to device category (phone or tablet) or
     * use case (in call vs off call...). See updateStreamVolumeAlias() for more details.
     *  mStreamVolumeAlias contains STREAM_VOLUME_ALIAS_VOICE aliases for a voice capable device
     *  (phone), STREAM_VOLUME_ALIAS_TELEVISION for a television or set-top box and
     *  STREAM_VOLUME_ALIAS_DEFAULT for other devices (e.g. tablets).*/
    private final int[] STREAM_VOLUME_ALIAS_VOICE = new int[] {
        AudioSystem.STREAM_VOICE_CALL,      // STREAM_VOICE_CALL
        AudioSystem.STREAM_RING,            // STREAM_SYSTEM
        AudioSystem.STREAM_RING,            // STREAM_RING
        AudioSystem.STREAM_MUSIC,           // STREAM_MUSIC
        AudioSystem.STREAM_ALARM,           // STREAM_ALARM
        AudioSystem.STREAM_RING,            // STREAM_NOTIFICATION
        AudioSystem.STREAM_BLUETOOTH_SCO,   // STREAM_BLUETOOTH_SCO
        AudioSystem.STREAM_RING,            // STREAM_SYSTEM_ENFORCED
        AudioSystem.STREAM_RING,            // STREAM_DTMF
        AudioSystem.STREAM_MUSIC,           // STREAM_TTS
        AudioSystem.STREAM_MUSIC,           // STREAM_ACCESSIBILITY
        AudioSystem.STREAM_MUSIC            // STREAM_ASSISTANT
    };
    private final int[] STREAM_VOLUME_ALIAS_TELEVISION = new int[] {
        AudioSystem.STREAM_MUSIC,       // STREAM_VOICE_CALL
        AudioSystem.STREAM_MUSIC,       // STREAM_SYSTEM
        AudioSystem.STREAM_MUSIC,       // STREAM_RING
        AudioSystem.STREAM_MUSIC,       // STREAM_MUSIC
        AudioSystem.STREAM_MUSIC,       // STREAM_ALARM
        AudioSystem.STREAM_MUSIC,       // STREAM_NOTIFICATION
        AudioSystem.STREAM_BLUETOOTH_SCO,       // STREAM_BLUETOOTH_SCO
        AudioSystem.STREAM_MUSIC,       // STREAM_SYSTEM_ENFORCED
        AudioSystem.STREAM_MUSIC,       // STREAM_DTMF
        AudioSystem.STREAM_MUSIC,       // STREAM_TTS
        AudioSystem.STREAM_MUSIC,       // STREAM_ACCESSIBILITY
        AudioSystem.STREAM_MUSIC        // STREAM_ASSISTANT
    };
    private final int[] STREAM_VOLUME_ALIAS_DEFAULT = new int[] {
        AudioSystem.STREAM_VOICE_CALL,      // STREAM_VOICE_CALL
        AudioSystem.STREAM_RING,            // STREAM_SYSTEM
        AudioSystem.STREAM_RING,            // STREAM_RING
        AudioSystem.STREAM_MUSIC,           // STREAM_MUSIC
        AudioSystem.STREAM_ALARM,           // STREAM_ALARM
        AudioSystem.STREAM_RING,            // STREAM_NOTIFICATION
        AudioSystem.STREAM_BLUETOOTH_SCO,   // STREAM_BLUETOOTH_SCO
        AudioSystem.STREAM_RING,            // STREAM_SYSTEM_ENFORCED
        AudioSystem.STREAM_RING,            // STREAM_DTMF
        AudioSystem.STREAM_MUSIC,           // STREAM_TTS
        AudioSystem.STREAM_MUSIC,           // STREAM_ACCESSIBILITY
        AudioSystem.STREAM_MUSIC            // STREAM_ASSISTANT
    };
    protected static int[] mStreamVolumeAlias;

    /**
     * Map AudioSystem.STREAM_* constants to app ops.  This should be used
     * after mapping through mStreamVolumeAlias.
     */
    private static final int[] STREAM_VOLUME_OPS = new int[] {
        AppOpsManager.OP_AUDIO_VOICE_VOLUME,            // STREAM_VOICE_CALL
        AppOpsManager.OP_AUDIO_MEDIA_VOLUME,            // STREAM_SYSTEM
        AppOpsManager.OP_AUDIO_RING_VOLUME,             // STREAM_RING
        AppOpsManager.OP_AUDIO_MEDIA_VOLUME,            // STREAM_MUSIC
        AppOpsManager.OP_AUDIO_ALARM_VOLUME,            // STREAM_ALARM
        AppOpsManager.OP_AUDIO_NOTIFICATION_VOLUME,     // STREAM_NOTIFICATION
        AppOpsManager.OP_AUDIO_BLUETOOTH_VOLUME,        // STREAM_BLUETOOTH_SCO
        AppOpsManager.OP_AUDIO_MEDIA_VOLUME,            // STREAM_SYSTEM_ENFORCED
        AppOpsManager.OP_AUDIO_MEDIA_VOLUME,            // STREAM_DTMF
        AppOpsManager.OP_AUDIO_MEDIA_VOLUME,            // STREAM_TTS
        AppOpsManager.OP_AUDIO_ACCESSIBILITY_VOLUME,    // STREAM_ACCESSIBILITY
        AppOpsManager.OP_AUDIO_MEDIA_VOLUME             // STREAM_ASSISTANT
    };

    private final boolean mUseFixedVolume;

    /**
    * Default stream type used for volume control in the absence of playback
    * e.g. user on homescreen, no app playing anything, presses hardware volume buttons, this
    *    stream type is controlled.
    */
    protected static final int DEFAULT_VOL_STREAM_NO_PLAYBACK = AudioSystem.STREAM_MUSIC;

    private final AudioSystem.ErrorCallback mAudioSystemCallback = new AudioSystem.ErrorCallback() {
        public void onError(int error) {
            switch (error) {
                case AudioSystem.AUDIO_STATUS_SERVER_DIED:
                    // check for null in case error callback is called during instance creation
                    if (mRecordMonitor != null) {
                        mRecordMonitor.onAudioServerDied();
                    }
                    sendMsg(mAudioHandler, MSG_AUDIO_SERVER_DIED,
                            SENDMSG_NOOP, 0, 0, null, 0);
                    sendMsg(mAudioHandler, MSG_DISPATCH_AUDIO_SERVER_STATE,
                            SENDMSG_QUEUE, 0, 0, null, 0);
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Current ringer mode from one of {@link AudioManager#RINGER_MODE_NORMAL},
     * {@link AudioManager#RINGER_MODE_SILENT}, or
     * {@link AudioManager#RINGER_MODE_VIBRATE}.
     */
    @GuardedBy("mSettingsLock")
    private int mRingerMode;  // internal ringer mode, affects muting of underlying streams
    @GuardedBy("mSettingsLock")
    private int mRingerModeExternal = -1;  // reported ringer mode to outside clients (AudioManager)

    /** @see System#MODE_RINGER_STREAMS_AFFECTED */
    private int mRingerModeAffectedStreams = 0;

    private int mZenModeAffectedStreams = 0;

    // Streams currently muted by ringer mode and dnd
    private int mRingerAndZenModeMutedStreams;

    /** Streams that can be muted. Do not resolve to aliases when checking.
     * @see System#MUTE_STREAMS_AFFECTED */
    private int mMuteAffectedStreams;

    @NonNull
    private SoundEffectsHelper mSfxHelper;

    /**
     * NOTE: setVibrateSetting(), getVibrateSetting(), shouldVibrate() are deprecated.
     * mVibrateSetting is just maintained during deprecation period but vibration policy is
     * now only controlled by mHasVibrator and mRingerMode
     */
    private int mVibrateSetting;

    // Is there a vibrator
    private final boolean mHasVibrator;
    // Used to play vibrations
    private Vibrator mVibrator;
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    // Broadcast receiver for device connections intent broadcasts
    private final BroadcastReceiver mReceiver = new AudioServiceBroadcastReceiver();

    private IMediaProjectionManager mProjectionService; // to validate projection token

    /** Interface for UserManagerService. */
    private final UserManagerInternal mUserManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;

    private final UserRestrictionsListener mUserRestrictionsListener =
            new AudioServiceUserRestrictionsListener();

    // List of binder death handlers for setMode() client processes.
    // The last process to have called setMode() is at the top of the list.
    // package-private so it can be accessed in AudioDeviceBroker.getSetModeDeathHandlers
    //TODO candidate to be moved to separate class that handles synchronization
    @GuardedBy("mDeviceBroker.mSetModeLock")
    /*package*/ final ArrayList<SetModeDeathHandler> mSetModeDeathHandlers =
            new ArrayList<SetModeDeathHandler>();

    // true if boot sequence has been completed
    private boolean mSystemReady;
    // true if Intent.ACTION_USER_SWITCHED has ever been received
    private boolean mUserSwitchedReceived;
    // previous volume adjustment direction received by checkForRingerModeChange()
    private int mPrevVolDirection = AudioManager.ADJUST_SAME;
    // mVolumeControlStream is set by VolumePanel to temporarily force the stream type which volume
    // is controlled by Vol keys.
    private int mVolumeControlStream = -1;
    // interpretation of whether the volume stream has been selected by the user by clicking on a
    // volume slider to change which volume is controlled by the volume keys. Is false
    // when mVolumeControlStream is -1.
    private boolean mUserSelectedVolumeControlStream = false;
    private final Object mForceControlStreamLock = new Object();
    // VolumePanel is currently the only client of forceVolumeControlStream() and runs in system
    // server process so in theory it is not necessary to monitor the client death.
    // However it is good to be ready for future evolutions.
    private ForceControlStreamClient mForceControlStreamClient = null;
    // Used to play ringtones outside system_server
    private volatile IRingtonePlayer mRingtonePlayer;

    // Devices for which the volume is fixed (volume is either max or muted)
    Set<Integer> mFixedVolumeDevices = new HashSet<>(Arrays.asList(
            AudioSystem.DEVICE_OUT_HDMI,
            AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET,
            AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET,
            AudioSystem.DEVICE_OUT_HDMI_ARC,
            AudioSystem.DEVICE_OUT_SPDIF,
            AudioSystem.DEVICE_OUT_AUX_LINE));
    // Devices for which the volume is always max, no volume panel
    Set<Integer> mFullVolumeDevices = new HashSet<>();
    // Devices for the which use the "absolute volume" concept (framework sends audio signal
    // full scale, and volume control separately) and can be used for multiple use cases reflected
    // by the audio mode (e.g. media playback in MODE_NORMAL, and phone calls in MODE_IN_CALL).
    Set<Integer> mAbsVolumeMultiModeCaseDevices = new HashSet<>(
            Arrays.asList(AudioSystem.DEVICE_OUT_HEARING_AID));

    private final boolean mMonitorRotation;

    private boolean mDockAudioMediaEnabled = true;

    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    // Used when safe volume warning message display is requested by setStreamVolume(). In this
    // case, the new requested volume, stream type and device are stored in mPendingVolumeCommand
    // and used later when/if disableSafeMediaVolume() is called.
    private StreamVolumeCommand mPendingVolumeCommand;

    private PowerManager.WakeLock mAudioEventWakeLock;

    private final MediaFocusControl mMediaFocusControl;

    // Pre-scale for Bluetooth Absolute Volume
    private float[] mPrescaleAbsoluteVolume = new float[] {
        0.5f,    // Pre-scale for index 1
        0.7f,    // Pre-scale for index 2
        0.85f,   // Pre-scale for index 3
    };

    private NotificationManager mNm;
    private AudioManagerInternal.RingerModeDelegate mRingerModeDelegate;
    private VolumePolicy mVolumePolicy = VolumePolicy.DEFAULT;
    private long mLoweredFromNormalToVibrateTime;

    // Array of Uids of valid accessibility services to check if caller is one of them
    private int[] mAccessibilityServiceUids;
    private final Object mAccessibilityServiceUidsLock = new Object();

    private int mEncodedSurroundMode;
    private String mEnabledSurroundFormats;
    private boolean mSurroundModeChanged;

    private boolean mMicMuteFromSwitch;
    private boolean mMicMuteFromApi;
    private boolean mMicMuteFromRestrictions;

    @GuardedBy("mSettingsLock")
    private int mAssistantUid;

    @GuardedBy("mSettingsLock")
    private int mCurrentImeUid;

    private final Object mSupportedSystemUsagesLock = new Object();
    @GuardedBy("mSupportedSystemUsagesLock")
    private @AttributeSystemUsage int[] mSupportedSystemUsages =
            new int[]{AudioAttributes.USAGE_CALL_ASSISTANT};

    // Defines the format for the connection "address" for ALSA devices
    public static String makeAlsaAddressString(int card, int device) {
        return "card=" + card + ";device=" + device + ";";
    }

    public static final class Lifecycle extends SystemService {
        private AudioService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new AudioService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.AUDIO_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemReady();
            }
        }
    }

    final private IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int procState, long procStateSeq,
            int capability) {
        }

        @Override public void onUidGone(int uid, boolean disabled) {
            // Once the uid is no longer running, no need to keep trying to disable its audio.
            disableAudioForUid(false, uid);
        }

        @Override public void onUidActive(int uid) throws RemoteException {
        }

        @Override public void onUidIdle(int uid, boolean disabled) {
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
            disableAudioForUid(cached, uid);
        }

        private void disableAudioForUid(boolean disable, int uid) {
            queueMsgUnderWakeLock(mAudioHandler, MSG_DISABLE_AUDIO_FOR_UID,
                    disable ? 1 : 0 /* arg1 */,  uid /* arg2 */,
                    null /* obj */,  0 /* delay */);
        }
    };

    @GuardedBy("mSettingsLock")
    private boolean mRttEnabled = false;

    ///////////////////////////////////////////////////////////////////////////
    // Construction
    ///////////////////////////////////////////////////////////////////////////

    /** @hide */
    public AudioService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mAppOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);

        mPlatformType = AudioSystem.getPlatformType(context);

        mIsSingleVolume = AudioSystem.isSingleVolume(context);

        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mAudioEventWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handleAudioEvent");

        mSfxHelper = new SoundEffectsHelper(mContext);

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = mVibrator == null ? false : mVibrator.hasVibrator();

        // Initialize volume
        // Priority 1 - Android Property
        // Priority 2 - Audio Policy Service
        // Priority 3 - Default Value
        if (AudioProductStrategy.getAudioProductStrategies().size() > 0) {
            int numStreamTypes = AudioSystem.getNumStreamTypes();

            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                AudioAttributes attr =
                        AudioProductStrategy.getAudioAttributesForStrategyWithLegacyStreamType(
                                streamType);
                int maxVolume = AudioSystem.getMaxVolumeIndexForAttributes(attr);
                if (maxVolume != -1) {
                    MAX_STREAM_VOLUME[streamType] = maxVolume;
                }
                int minVolume = AudioSystem.getMinVolumeIndexForAttributes(attr);
                if (minVolume != -1) {
                    MIN_STREAM_VOLUME[streamType] = minVolume;
                }
            }
        }

        int maxCallVolume = SystemProperties.getInt("ro.config.vc_call_vol_steps", -1);
        if (maxCallVolume != -1) {
            MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] = maxCallVolume;
        }

        int defaultCallVolume = SystemProperties.getInt("ro.config.vc_call_vol_default", -1);
        if (defaultCallVolume != -1 &&
                defaultCallVolume <= MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] &&
                defaultCallVolume >= MIN_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] = defaultCallVolume;
        } else {
            AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] =
                    (MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] * 3) / 4;
        }

        int maxMusicVolume = SystemProperties.getInt("ro.config.media_vol_steps", -1);
        if (maxMusicVolume != -1) {
            MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC] = maxMusicVolume;
        }

        int defaultMusicVolume = SystemProperties.getInt("ro.config.media_vol_default", -1);
        if (defaultMusicVolume != -1 &&
                defaultMusicVolume <= MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC] &&
                defaultMusicVolume >= MIN_STREAM_VOLUME[AudioSystem.STREAM_MUSIC]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_MUSIC] = defaultMusicVolume;
        } else {
            if (isPlatformTelevision()) {
                AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_MUSIC] =
                        MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC] / 4;
            } else {
                AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_MUSIC] =
                        MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC] / 3;
            }
        }

        int maxAlarmVolume = SystemProperties.getInt("ro.config.alarm_vol_steps", -1);
        if (maxAlarmVolume != -1) {
            MAX_STREAM_VOLUME[AudioSystem.STREAM_ALARM] = maxAlarmVolume;
        }

        int defaultAlarmVolume = SystemProperties.getInt("ro.config.alarm_vol_default", -1);
        if (defaultAlarmVolume != -1 &&
                defaultAlarmVolume <= MAX_STREAM_VOLUME[AudioSystem.STREAM_ALARM]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_ALARM] = defaultAlarmVolume;
        } else {
            // Default is 6 out of 7 (default maximum), so scale accordingly.
            AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_ALARM] =
                        6 * MAX_STREAM_VOLUME[AudioSystem.STREAM_ALARM] / 7;
        }

        int maxSystemVolume = SystemProperties.getInt("ro.config.system_vol_steps", -1);
        if (maxSystemVolume != -1) {
            MAX_STREAM_VOLUME[AudioSystem.STREAM_SYSTEM] = maxSystemVolume;
        }

        int defaultSystemVolume = SystemProperties.getInt("ro.config.system_vol_default", -1);
        if (defaultSystemVolume != -1 &&
                defaultSystemVolume <= MAX_STREAM_VOLUME[AudioSystem.STREAM_SYSTEM]) {
            AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_SYSTEM] = defaultSystemVolume;
        } else {
            // Default is to use maximum.
            AudioSystem.DEFAULT_STREAM_VOLUME[AudioSystem.STREAM_SYSTEM] =
                        MAX_STREAM_VOLUME[AudioSystem.STREAM_SYSTEM];
        }

        createAudioSystemThread();

        AudioSystem.setErrorCallback(mAudioSystemCallback);

        updateAudioHalPids();

        boolean cameraSoundForced = readCameraSoundForced();
        mCameraSoundForced = new Boolean(cameraSoundForced);
        sendMsg(mAudioHandler,
                MSG_SET_FORCE_USE,
                SENDMSG_QUEUE,
                AudioSystem.FOR_SYSTEM,
                cameraSoundForced ?
                        AudioSystem.FORCE_SYSTEM_ENFORCED : AudioSystem.FORCE_NONE,
                new String("AudioService ctor"),
                0);

        mSafeMediaVolumeState = Settings.Global.getInt(mContentResolver,
                                            Settings.Global.AUDIO_SAFE_VOLUME_STATE,
                                            SAFE_MEDIA_VOLUME_NOT_CONFIGURED);
        // The default safe volume index read here will be replaced by the actual value when
        // the mcc is read by onConfigureSafeVolume()
        mSafeMediaVolumeIndex = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_safe_media_volume_index) * 10;

        mUseFixedVolume = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useFixedVolume);

        mDeviceBroker = new AudioDeviceBroker(mContext, this);

        mRecordMonitor = new RecordingActivityMonitor(mContext);

        // must be called before readPersistedSettings() which needs a valid mStreamVolumeAlias[]
        // array initialized by updateStreamVolumeAlias()
        updateStreamVolumeAlias(false /*updateVolumes*/, TAG);
        readPersistedSettings();
        readUserRestrictions();
        mSettingsObserver = new SettingsObserver();
        createStreamStates();

        // must be called after createStreamStates() as it uses MUSIC volume as default if no
        // persistent data
        initVolumeGroupStates();

        // mSafeUsbMediaVolumeIndex must be initialized after createStreamStates() because it
        // relies on audio policy having correct ranges for volume indexes.
        mSafeUsbMediaVolumeIndex = getSafeUsbMediaVolumeIndex();

        mPlaybackMonitor =
                new PlaybackActivityMonitor(context, MAX_STREAM_VOLUME[AudioSystem.STREAM_ALARM]);

        mMediaFocusControl = new MediaFocusControl(mContext, mPlaybackMonitor);

        readAndSetLowRamDevice();

        mIsCallScreeningModeSupported = AudioSystem.isCallScreeningModeSupported();

        // Call setRingerModeInt() to apply correct mute
        // state on streams affected by ringer mode.
        mRingerAndZenModeMutedStreams = 0;
        setRingerModeInt(getRingerModeInternal(), false);

        // Register for device connection intent broadcasts.
        IntentFilter intentFilter =
                new IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        intentFilter.addAction(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED);
        intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_BACKGROUND);
        intentFilter.addAction(Intent.ACTION_USER_FOREGROUND);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);

        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mMonitorRotation = SystemProperties.getBoolean("ro.audio.monitorRotation", false);
        if (mMonitorRotation) {
            RotationHelper.init(mContext, mAudioHandler);
        }

        intentFilter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intentFilter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);

        context.registerReceiverAsUser(mReceiver, UserHandle.ALL, intentFilter, null, null);

        LocalServices.addService(AudioManagerInternal.class, new AudioServiceInternal());

        mUserManagerInternal.addUserRestrictionsListener(mUserRestrictionsListener);

        mRecordMonitor.initMonitor();

        final float[] preScale = new float[3];
        preScale[0] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index1,
                1, 1);
        preScale[1] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index2,
                1, 1);
        preScale[2] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index3,
                1, 1);
        for (int i = 0; i < preScale.length; i++) {
            if (0.0f <= preScale[i] && preScale[i] <= 1.0f) {
                mPrescaleAbsoluteVolume[i] = preScale[i];
            }
        }
    }

    public void systemReady() {
        sendMsg(mAudioHandler, MSG_SYSTEM_READY, SENDMSG_QUEUE,
                0, 0, null, 0);
        if (false) {
            // This is turned off for now, because it is racy and thus causes apps to break.
            // Currently banning a uid means that if an app tries to start playing an audio
            // stream, that will be preventing, and unbanning it will not allow that stream
            // to resume.  However these changes in uid state are racy with what the app is doing,
            // so that after taking a process out of the cached state we can't guarantee that
            // we will unban the uid before the app actually tries to start playing audio.
            // (To do that, the activity manager would need to wait until it knows for sure
            // that the ban has been removed, before telling the app to do whatever it is
            // supposed to do that caused it to go out of the cached state.)
            try {
                ActivityManager.getService().registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_CACHED | ActivityManager.UID_OBSERVER_GONE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }
        }
    }

    public void onSystemReady() {
        mSystemReady = true;
        scheduleLoadSoundEffects();

        mDeviceBroker.onSystemReady();

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_HDMI_CEC)) {
            synchronized (mHdmiClientLock) {
                mHdmiCecSink = false;
                mHdmiManager = mContext.getSystemService(HdmiControlManager.class);
                if (mHdmiManager != null) {
                    mHdmiManager.addHdmiControlStatusChangeListener(
                            mHdmiControlStatusChangeListenerCallback);
                }
                mHdmiTvClient = mHdmiManager.getTvClient();
                if (mHdmiTvClient != null) {
                    mFixedVolumeDevices.removeAll(
                            AudioSystem.DEVICE_ALL_HDMI_SYSTEM_AUDIO_AND_SPEAKER_SET);
                }
                mHdmiPlaybackClient = mHdmiManager.getPlaybackClient();
                if (mHdmiPlaybackClient != null) {
                    // not a television: HDMI output will be always at max
                    mFixedVolumeDevices.remove(AudioSystem.DEVICE_OUT_HDMI);
                    mFullVolumeDevices.add(AudioSystem.DEVICE_OUT_HDMI);
                }
                mHdmiAudioSystemClient = mHdmiManager.getAudioSystemClient();
            }
        }

        mNm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        sendMsg(mAudioHandler,
                MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED,
                SENDMSG_REPLACE,
                0,
                0,
                TAG,
                SystemProperties.getBoolean("audio.safemedia.bypass", false) ?
                        0 : SAFE_VOLUME_CONFIGURE_TIMEOUT_MS);

        initA11yMonitoring();

        mRoleObserver = new RoleObserver();
        mRoleObserver.register();

        onIndicateSystemReady();

        setMicMuteFromSwitchInput();
    }

    RoleObserver mRoleObserver;

    class RoleObserver implements OnRoleHoldersChangedListener {
        private RoleManager mRm;
        private final Executor mExecutor;

        RoleObserver() {
            mExecutor = mContext.getMainExecutor();
        }

        public void register() {
            mRm = (RoleManager) mContext.getSystemService(Context.ROLE_SERVICE);
            if (mRm != null) {
                mRm.addOnRoleHoldersChangedListenerAsUser(mExecutor, this, UserHandle.ALL);
                updateAssistantUId(true);
            }
        }

        @Override
        public void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
            if (RoleManager.ROLE_ASSISTANT.equals(roleName)) {
                updateAssistantUId(false);
            }
        }

        public String getAssistantRoleHolder() {
            String assitantPackage = "";
            if (mRm != null) {
                List<String> assistants = mRm.getRoleHolders(RoleManager.ROLE_ASSISTANT);
                assitantPackage = assistants.size() == 0 ? "" : assistants.get(0);
            }
            return assitantPackage;
        }
    }

    void onIndicateSystemReady() {
        if (AudioSystem.systemReady() == AudioSystem.SUCCESS) {
            return;
        }
        sendMsg(mAudioHandler,
                MSG_INDICATE_SYSTEM_READY,
                SENDMSG_REPLACE,
                0,
                0,
                null,
                INDICATE_SYSTEM_READY_RETRY_DELAY_MS);
    }

    public void onAudioServerDied() {
        if (!mSystemReady ||
                (AudioSystem.checkAudioFlinger() != AudioSystem.AUDIO_STATUS_OK)) {
            Log.e(TAG, "Audioserver died.");
            sendMsg(mAudioHandler, MSG_AUDIO_SERVER_DIED, SENDMSG_NOOP, 0, 0,
                    null, 500);
            return;
        }
        Log.e(TAG, "Audioserver started.");

        updateAudioHalPids();

        // indicate to audio HAL that we start the reconfiguration phase after a media
        // server crash
        // Note that we only execute this when the media server
        // process restarts after a crash, not the first time it is started.
        AudioSystem.setParameters("restarting=true");

        readAndSetLowRamDevice();

        mIsCallScreeningModeSupported = AudioSystem.isCallScreeningModeSupported();

        // Restore device connection states, BT state
        mDeviceBroker.onAudioServerDied();

        // Restore call state
        synchronized (mDeviceBroker.mSetModeLock) {
            if (AudioSystem.setPhoneState(mMode, getModeOwnerUid())
                    ==  AudioSystem.AUDIO_STATUS_OK) {
                mModeLogger.log(new AudioEventLogger.StringEvent(
                        "onAudioServerDied causes setPhoneState(" + AudioSystem.modeToString(mMode)
                        + ", uid=" + getModeOwnerUid() + ")"));
            }
        }
        final int forSys;
        synchronized (mSettingsLock) {
            forSys = mCameraSoundForced ?
                    AudioSystem.FORCE_SYSTEM_ENFORCED : AudioSystem.FORCE_NONE;
        }

        mDeviceBroker.setForceUse_Async(AudioSystem.FOR_SYSTEM, forSys, "onAudioServerDied");

        // Restore stream volumes
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
            VolumeStreamState streamState = mStreamStates[streamType];
            AudioSystem.initStreamVolume(
                streamType, streamState.mIndexMin / 10, streamState.mIndexMax / 10);

            streamState.applyAllVolumes();
        }

        // Restore audio volume groups
        restoreVolumeGroups();

        // Restore mono mode
        updateMasterMono(mContentResolver);

        // Restore audio balance
        updateMasterBalance(mContentResolver);

        // Restore ringer mode
        setRingerModeInt(getRingerModeInternal(), false);

        // Reset device rotation (if monitored for this device)
        if (mMonitorRotation) {
            RotationHelper.updateOrientation();
        }

        synchronized (mSettingsLock) {
            final int forDock = mDockAudioMediaEnabled ?
                    AudioSystem.FORCE_ANALOG_DOCK : AudioSystem.FORCE_NONE;
            mDeviceBroker.setForceUse_Async(AudioSystem.FOR_DOCK, forDock, "onAudioServerDied");
            sendEncodedSurroundMode(mContentResolver, "onAudioServerDied");
            sendEnabledSurroundFormats(mContentResolver, true);
            updateAssistantUId(true);
            updateCurrentImeUid(true);
            AudioSystem.setRttEnabled(mRttEnabled);
        }
        synchronized (mAccessibilityServiceUidsLock) {
            AudioSystem.setA11yServicesUids(mAccessibilityServiceUids);
        }
        synchronized (mHdmiClientLock) {
            if (mHdmiManager != null && mHdmiTvClient != null) {
                setHdmiSystemAudioSupported(mHdmiSystemAudioSupported);
            }
        }

        synchronized (mSupportedSystemUsagesLock) {
            AudioSystem.setSupportedSystemUsages(mSupportedSystemUsages);
        }

        synchronized (mAudioPolicies) {
            for (AudioPolicyProxy policy : mAudioPolicies.values()) {
                final int status = policy.connectMixes();
                if (status != AudioSystem.SUCCESS) {
                    // note that PERMISSION_DENIED may also indicate trouble getting to APService
                    Log.e(TAG, "onAudioServerDied: error "
                            + AudioSystem.audioSystemErrorToString(status)
                            + " when connecting mixes for policy " + policy.toLogFriendlyString());
                    policy.release();
                } else {
                    final int deviceAffinitiesStatus = policy.setupDeviceAffinities();
                    if (deviceAffinitiesStatus != AudioSystem.SUCCESS) {
                        Log.e(TAG, "onAudioServerDied: error "
                                + AudioSystem.audioSystemErrorToString(deviceAffinitiesStatus)
                                + " when connecting device affinities for policy "
                                + policy.toLogFriendlyString());
                        policy.release();
                    }
                }
            }
        }

        // Restore capture policies
        synchronized (mPlaybackMonitor) {
            HashMap<Integer, Integer> allowedCapturePolicies =
                    mPlaybackMonitor.getAllAllowedCapturePolicies();
            for (HashMap.Entry<Integer, Integer> entry : allowedCapturePolicies.entrySet()) {
                int result = AudioSystem.setAllowedCapturePolicy(
                        entry.getKey(),
                        AudioAttributes.capturePolicyToFlags(entry.getValue(), 0x0));
                if (result != AudioSystem.AUDIO_STATUS_OK) {
                    Log.e(TAG, "Failed to restore capture policy, uid: "
                            + entry.getKey() + ", capture policy: " + entry.getValue()
                            + ", result: " + result);
                    // When restoring capture policy failed, set the capture policy as
                    // ALLOW_CAPTURE_BY_ALL, which will result in removing the cached
                    // capture policy in PlaybackActivityMonitor.
                    mPlaybackMonitor.setAllowedCapturePolicy(
                            entry.getKey(), AudioAttributes.ALLOW_CAPTURE_BY_ALL);
                }
            }
        }

        onIndicateSystemReady();
        // indicate the end of reconfiguration phase to audio HAL
        AudioSystem.setParameters("restarting=false");

        sendMsg(mAudioHandler, MSG_DISPATCH_AUDIO_SERVER_STATE,
                SENDMSG_QUEUE, 1, 0, null, 0);

        setMicMuteFromSwitchInput();
    }

    private void onDispatchAudioServerStateChange(boolean state) {
        synchronized (mAudioServerStateListeners) {
            for (AsdProxy asdp : mAudioServerStateListeners.values()) {
                try {
                    asdp.callback().dispatchAudioServerStateChange(state);
                } catch (RemoteException e) {
                    Log.w(TAG, "Could not call dispatchAudioServerStateChange()", e);
                }
            }
        }
    }

    private void createAudioSystemThread() {
        mAudioSystemThread = new AudioSystemThread();
        mAudioSystemThread.start();
        waitForAudioHandlerCreation();
    }

    /** Waits for the volume handler to be created by the other thread. */
    private void waitForAudioHandlerCreation() {
        synchronized(this) {
            while (mAudioHandler == null) {
                try {
                    // Wait for mAudioHandler to be set by the other thread
                    wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting on volume handler.");
                }
            }
        }
    }

    /**
     * @see AudioManager#setSupportedSystemUsages(int[])
     */
    public void setSupportedSystemUsages(@NonNull @AttributeSystemUsage int[] systemUsages) {
        enforceModifyAudioRoutingPermission();
        verifySystemUsages(systemUsages);

        synchronized (mSupportedSystemUsagesLock) {
            AudioSystem.setSupportedSystemUsages(systemUsages);
            mSupportedSystemUsages = systemUsages;
        }
    }

    /**
     * @see AudioManager#getSupportedSystemUsages()
     */
    public @NonNull @AttributeSystemUsage int[] getSupportedSystemUsages() {
        enforceModifyAudioRoutingPermission();
        synchronized (mSupportedSystemUsagesLock) {
            return Arrays.copyOf(mSupportedSystemUsages, mSupportedSystemUsages.length);
        }
    }

    private void verifySystemUsages(@NonNull int[] systemUsages) {
        for (int i = 0; i < systemUsages.length; i++) {
            if (!AudioAttributes.isSystemUsage(systemUsages[i])) {
                throw new IllegalArgumentException("Non-system usage provided: " + systemUsages[i]);
            }
        }
    }

    /**
     * @return the {@link android.media.audiopolicy.AudioProductStrategy} discovered from the
     * platform configuration file.
     */
    @NonNull
    public List<AudioProductStrategy> getAudioProductStrategies() {
        return AudioProductStrategy.getAudioProductStrategies();
    }

    /**
     * @return the List of {@link android.media.audiopolicy.AudioVolumeGroup} discovered from the
     * platform configuration file.
     */
    @NonNull
    public List<AudioVolumeGroup> getAudioVolumeGroups() {
        return AudioVolumeGroup.getAudioVolumeGroups();
    }

    private void checkAllAliasStreamVolumes() {
        synchronized (mSettingsLock) {
            synchronized (VolumeStreamState.class) {
                int numStreamTypes = AudioSystem.getNumStreamTypes();
                for (int streamType = 0; streamType < numStreamTypes; streamType++) {
                    mStreamStates[streamType]
                            .setAllIndexes(mStreamStates[mStreamVolumeAlias[streamType]], TAG);
                    // apply stream volume
                    if (!mStreamStates[streamType].mIsMuted) {
                        mStreamStates[streamType].applyAllVolumes();
                    }
                }
            }
        }
    }


    /**
     * Called from AudioDeviceBroker when DEVICE_OUT_HDMI is connected or disconnected.
     */
    /*package*/ void postCheckVolumeCecOnHdmiConnection(
            @AudioService.ConnectionState  int state, String caller) {
        sendMsg(mAudioHandler, MSG_HDMI_VOLUME_CHECK, SENDMSG_REPLACE,
                state /*arg1*/, 0 /*arg2 ignored*/, caller /*obj*/, 0 /*delay*/);
    }

    private void onCheckVolumeCecOnHdmiConnection(
            @AudioService.ConnectionState int state, String caller) {
        if (state == AudioService.CONNECTION_STATE_CONNECTED) {
            // DEVICE_OUT_HDMI is now connected
            if (mSafeMediaVolumeDevices.contains(AudioSystem.DEVICE_OUT_HDMI)) {
                sendMsg(mAudioHandler,
                        MSG_CHECK_MUSIC_ACTIVE,
                        SENDMSG_REPLACE,
                        0,
                        0,
                        caller,
                        MUSIC_ACTIVE_POLL_PERIOD_MS);
            }

            if (isPlatformTelevision()) {
                checkAddAllFixedVolumeDevices(AudioSystem.DEVICE_OUT_HDMI, caller);
                synchronized (mHdmiClientLock) {
                    if (mHdmiManager != null && mHdmiPlaybackClient != null) {
                        updateHdmiCecSinkLocked(mHdmiCecSink | false);
                    }
                }
            }
            sendEnabledSurroundFormats(mContentResolver, true);
        } else {
            // DEVICE_OUT_HDMI disconnected
            if (isPlatformTelevision()) {
                synchronized (mHdmiClientLock) {
                    if (mHdmiManager != null) {
                        updateHdmiCecSinkLocked(mHdmiCecSink | false);
                    }
                }
            }
        }
    }

    private void checkAddAllFixedVolumeDevices(int device, String caller) {
        final int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            if (!mStreamStates[streamType].hasIndexForDevice(device)) {
                // set the default value, if device is affected by a full/fix/abs volume rule, it
                // will taken into account in checkFixedVolumeDevices()
                mStreamStates[streamType].setIndex(
                        mStreamStates[mStreamVolumeAlias[streamType]]
                                .getIndex(AudioSystem.DEVICE_OUT_DEFAULT),
                        device, caller);
            }
            mStreamStates[streamType].checkFixedVolumeDevices();
        }
    }

    private void checkAllFixedVolumeDevices()
    {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            mStreamStates[streamType].checkFixedVolumeDevices();
        }
    }

    private void checkAllFixedVolumeDevices(int streamType) {
        mStreamStates[streamType].checkFixedVolumeDevices();
    }

    private void checkMuteAffectedStreams() {
        // any stream with a min level > 0 is not muteable by definition
        // STREAM_VOICE_CALL and STREAM_BLUETOOTH_SCO can be muted by applications
        // that has the the MODIFY_PHONE_STATE permission.
        for (int i = 0; i < mStreamStates.length; i++) {
            final VolumeStreamState vss = mStreamStates[i];
            if (vss.mIndexMin > 0 &&
                (vss.mStreamType != AudioSystem.STREAM_VOICE_CALL &&
                vss.mStreamType != AudioSystem.STREAM_BLUETOOTH_SCO)) {
                mMuteAffectedStreams &= ~(1 << vss.mStreamType);
            }
        }
    }

    private void createStreamStates() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        VolumeStreamState[] streams = mStreamStates = new VolumeStreamState[numStreamTypes];

        for (int i = 0; i < numStreamTypes; i++) {
            streams[i] =
                    new VolumeStreamState(System.VOLUME_SETTINGS_INT[mStreamVolumeAlias[i]], i);
        }

        checkAllFixedVolumeDevices();
        checkAllAliasStreamVolumes();
        checkMuteAffectedStreams();
        updateDefaultVolumes();
    }

    // Update default indexes from aliased streams. Must be called after mStreamStates is created
    private void updateDefaultVolumes() {
        for (int stream = 0; stream < mStreamStates.length; stream++) {
            if (stream != mStreamVolumeAlias[stream]) {
                AudioSystem.DEFAULT_STREAM_VOLUME[stream] = (rescaleIndex(
                        AudioSystem.DEFAULT_STREAM_VOLUME[mStreamVolumeAlias[stream]] * 10,
                        mStreamVolumeAlias[stream],
                        stream) + 5) / 10;
            }
        }
    }

    private void dumpStreamStates(PrintWriter pw) {
        pw.println("\nStream volumes (device: index)");
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int i = 0; i < numStreamTypes; i++) {
            pw.println("- " + AudioSystem.STREAM_NAMES[i] + ":");
            mStreamStates[i].dump(pw);
            pw.println("");
        }
        pw.print("\n- mute affected streams = 0x");
        pw.println(Integer.toHexString(mMuteAffectedStreams));
    }

    private void updateStreamVolumeAlias(boolean updateVolumes, String caller) {
        int dtmfStreamAlias;
        final int a11yStreamAlias = sIndependentA11yVolume ?
                AudioSystem.STREAM_ACCESSIBILITY : AudioSystem.STREAM_MUSIC;
        final int assistantStreamAlias = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_useAssistantVolume) ?
                AudioSystem.STREAM_ASSISTANT : AudioSystem.STREAM_MUSIC;

        if (mIsSingleVolume) {
            mStreamVolumeAlias = STREAM_VOLUME_ALIAS_TELEVISION;
            dtmfStreamAlias = AudioSystem.STREAM_MUSIC;
        } else {
            switch (mPlatformType) {
                case AudioSystem.PLATFORM_VOICE:
                    mStreamVolumeAlias = STREAM_VOLUME_ALIAS_VOICE;
                    dtmfStreamAlias = AudioSystem.STREAM_RING;
                    break;
                default:
                    mStreamVolumeAlias = STREAM_VOLUME_ALIAS_DEFAULT;
                    dtmfStreamAlias = AudioSystem.STREAM_MUSIC;
            }
        }

        if (mIsSingleVolume) {
            mRingerModeAffectedStreams = 0;
        } else {
            if (isInCommunication()) {
                dtmfStreamAlias = AudioSystem.STREAM_VOICE_CALL;
                mRingerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_DTMF);
            } else {
                mRingerModeAffectedStreams |= (1 << AudioSystem.STREAM_DTMF);
            }
        }

        mStreamVolumeAlias[AudioSystem.STREAM_DTMF] = dtmfStreamAlias;
        mStreamVolumeAlias[AudioSystem.STREAM_ACCESSIBILITY] = a11yStreamAlias;
        mStreamVolumeAlias[AudioSystem.STREAM_ASSISTANT] = assistantStreamAlias;

        if (updateVolumes && mStreamStates != null) {
            updateDefaultVolumes();

            synchronized (mSettingsLock) {
                synchronized (VolumeStreamState.class) {
                    mStreamStates[AudioSystem.STREAM_DTMF]
                            .setAllIndexes(mStreamStates[dtmfStreamAlias], caller);
                    mStreamStates[AudioSystem.STREAM_ACCESSIBILITY].mVolumeIndexSettingName =
                            System.VOLUME_SETTINGS_INT[a11yStreamAlias];
                    mStreamStates[AudioSystem.STREAM_ACCESSIBILITY].setAllIndexes(
                            mStreamStates[a11yStreamAlias], caller);
                }
            }
            if (sIndependentA11yVolume) {
                // restore the a11y values from the settings
                mStreamStates[AudioSystem.STREAM_ACCESSIBILITY].readSettings();
            }

            // apply stream mute states according to new value of mRingerModeAffectedStreams
            setRingerModeInt(getRingerModeInternal(), false);
            sendMsg(mAudioHandler,
                    MSG_SET_ALL_VOLUMES,
                    SENDMSG_QUEUE,
                    0,
                    0,
                    mStreamStates[AudioSystem.STREAM_DTMF], 0);
            sendMsg(mAudioHandler,
                    MSG_SET_ALL_VOLUMES,
                    SENDMSG_QUEUE,
                    0,
                    0,
                    mStreamStates[AudioSystem.STREAM_ACCESSIBILITY], 0);
        }
    }

    private void readDockAudioSettings(ContentResolver cr)
    {
        mDockAudioMediaEnabled = Settings.Global.getInt(
                                        cr, Settings.Global.DOCK_AUDIO_MEDIA_ENABLED, 0) == 1;

        sendMsg(mAudioHandler,
                MSG_SET_FORCE_USE,
                SENDMSG_QUEUE,
                AudioSystem.FOR_DOCK,
                mDockAudioMediaEnabled ?
                        AudioSystem.FORCE_ANALOG_DOCK : AudioSystem.FORCE_NONE,
                new String("readDockAudioSettings"),
                0);
    }


    private void updateMasterMono(ContentResolver cr)
    {
        final boolean masterMono = System.getIntForUser(
                cr, System.MASTER_MONO, 0 /* default */, UserHandle.USER_CURRENT) == 1;
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Master mono %b", masterMono));
        }
        AudioSystem.setMasterMono(masterMono);
    }

    private void updateMasterBalance(ContentResolver cr) {
        final float masterBalance = System.getFloatForUser(
                cr, System.MASTER_BALANCE, 0.f /* default */, UserHandle.USER_CURRENT);
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Master balance %f", masterBalance));
        }
        if (AudioSystem.setMasterBalance(masterBalance) != 0) {
            Log.e(TAG, String.format("setMasterBalance failed for %f", masterBalance));
        }
    }

    private void sendEncodedSurroundMode(ContentResolver cr, String eventSource)
    {
        final int encodedSurroundMode = Settings.Global.getInt(
                cr, Settings.Global.ENCODED_SURROUND_OUTPUT,
                Settings.Global.ENCODED_SURROUND_OUTPUT_AUTO);
        sendEncodedSurroundMode(encodedSurroundMode, eventSource);
    }

    private void sendEncodedSurroundMode(int encodedSurroundMode, String eventSource)
    {
        // initialize to guaranteed bad value
        int forceSetting = AudioSystem.NUM_FORCE_CONFIG;
        switch (encodedSurroundMode) {
            case Settings.Global.ENCODED_SURROUND_OUTPUT_AUTO:
                forceSetting = AudioSystem.FORCE_NONE;
                break;
            case Settings.Global.ENCODED_SURROUND_OUTPUT_NEVER:
                forceSetting = AudioSystem.FORCE_ENCODED_SURROUND_NEVER;
                break;
            case Settings.Global.ENCODED_SURROUND_OUTPUT_ALWAYS:
                forceSetting = AudioSystem.FORCE_ENCODED_SURROUND_ALWAYS;
                break;
            case Settings.Global.ENCODED_SURROUND_OUTPUT_MANUAL:
                forceSetting = AudioSystem.FORCE_ENCODED_SURROUND_MANUAL;
                break;
            default:
                Log.e(TAG, "updateSurroundSoundSettings: illegal value "
                        + encodedSurroundMode);
                break;
        }
        if (forceSetting != AudioSystem.NUM_FORCE_CONFIG) {
            mDeviceBroker.setForceUse_Async(AudioSystem.FOR_ENCODED_SURROUND, forceSetting,
                    eventSource);
        }
    }

    private void sendEnabledSurroundFormats(ContentResolver cr, boolean forceUpdate) {
        if (mEncodedSurroundMode != Settings.Global.ENCODED_SURROUND_OUTPUT_MANUAL) {
            // Manually enable surround formats only when the setting is in manual mode.
            return;
        }
        String enabledSurroundFormats = Settings.Global.getString(
                cr, Settings.Global.ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS);
        if (enabledSurroundFormats == null) {
            // Never allow enabledSurroundFormats as a null, which could happen when
            // ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS is not appear in settings DB.
            enabledSurroundFormats = "";
        }
        if (!forceUpdate && TextUtils.equals(enabledSurroundFormats, mEnabledSurroundFormats)) {
            // Update enabled surround formats to AudioPolicyManager only when forceUpdate
            // is true or enabled surround formats changed.
            return;
        }

        mEnabledSurroundFormats = enabledSurroundFormats;
        String[] surroundFormats = TextUtils.split(enabledSurroundFormats, ",");
        ArrayList<Integer> formats = new ArrayList<>();
        for (String format : surroundFormats) {
            try {
                int audioFormat = Integer.valueOf(format);
                boolean isSurroundFormat = false;
                for (int sf : AudioFormat.SURROUND_SOUND_ENCODING) {
                    if (sf == audioFormat) {
                        isSurroundFormat = true;
                        break;
                    }
                }
                if (isSurroundFormat && !formats.contains(audioFormat)) {
                    formats.add(audioFormat);
                }
            } catch (Exception e) {
                Log.e(TAG, "Invalid enabled surround format:" + format);
            }
        }
        // Set filtered surround formats to settings DB in case
        // there are invalid surround formats in original settings.
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS,
                TextUtils.join(",", formats));
        sendMsg(mAudioHandler, MSG_ENABLE_SURROUND_FORMATS, SENDMSG_QUEUE, 0, 0, formats, 0);
    }

    private void onEnableSurroundFormats(ArrayList<Integer> enabledSurroundFormats) {
        // Set surround format enabled accordingly.
        for (int surroundFormat : AudioFormat.SURROUND_SOUND_ENCODING) {
            boolean enabled = enabledSurroundFormats.contains(surroundFormat);
            int ret = AudioSystem.setSurroundFormatEnabled(surroundFormat, enabled);
            Log.i(TAG, "enable surround format:" + surroundFormat + " " + enabled + " " + ret);
        }
    }

    @GuardedBy("mSettingsLock")
    private void updateAssistantUId(boolean forceUpdate) {
        int assistantUid = 0;

        // Consider assistants in the following order of priority:
        // 1) apk in assistant role
        // 2) voice interaction service
        // 3) assistant service

        String packageName = "";
        if (mRoleObserver != null) {
            packageName = mRoleObserver.getAssistantRoleHolder();
        }
        if (TextUtils.isEmpty(packageName)) {
            String assistantName = Settings.Secure.getStringForUser(
                            mContentResolver,
                            Settings.Secure.VOICE_INTERACTION_SERVICE, UserHandle.USER_CURRENT);
            if (TextUtils.isEmpty(assistantName)) {
                assistantName = Settings.Secure.getStringForUser(
                        mContentResolver,
                        Settings.Secure.ASSISTANT, UserHandle.USER_CURRENT);
            }
            if (!TextUtils.isEmpty(assistantName)) {
                ComponentName componentName = ComponentName.unflattenFromString(assistantName);
                if (componentName == null) {
                    Slog.w(TAG, "Invalid service name for "
                            + Settings.Secure.VOICE_INTERACTION_SERVICE + ": " + assistantName);
                    return;
                }
                packageName = componentName.getPackageName();
            }
        }
        if (!TextUtils.isEmpty(packageName)) {
            PackageManager pm = mContext.getPackageManager();
            ActivityManager am =
                          (ActivityManager) mContext.getSystemService(mContext.ACTIVITY_SERVICE);

            if (pm.checkPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD, packageName)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    assistantUid = pm.getPackageUidAsUser(packageName, am.getCurrentUser());
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG,
                            "updateAssistantUId() could not find UID for package: " + packageName);
                }
            }
        }

        if (assistantUid != mAssistantUid || forceUpdate) {
            AudioSystem.setAssistantUid(assistantUid);
            mAssistantUid = assistantUid;
        }
    }

    @GuardedBy("mSettingsLock")
    private void updateCurrentImeUid(boolean forceUpdate) {
        String imeId = Settings.Secure.getStringForUser(
                mContentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD, UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(imeId)) {
            Log.e(TAG, "updateCurrentImeUid() could not find current IME");
            return;
        }
        ComponentName componentName = ComponentName.unflattenFromString(imeId);
        if (componentName == null) {
            Log.e(TAG, "updateCurrentImeUid() got invalid service name for "
                    + Settings.Secure.DEFAULT_INPUT_METHOD + ": " + imeId);
            return;
        }
        String packageName = componentName.getPackageName();
        int currentUserId = LocalServices.getService(ActivityManagerInternal.class)
                .getCurrentUserId();
        int currentImeUid = LocalServices.getService(PackageManagerInternal.class)
                .getPackageUidInternal(packageName, 0 /* flags */, currentUserId);
        if (currentImeUid < 0) {
            Log.e(TAG, "updateCurrentImeUid() could not find UID for package: " + packageName);
            return;
        }

        if (currentImeUid != mCurrentImeUid || forceUpdate) {
            AudioSystem.setCurrentImeUid(currentImeUid);
            mCurrentImeUid = currentImeUid;
        }
    }

    private void readPersistedSettings() {
        final ContentResolver cr = mContentResolver;

        int ringerModeFromSettings =
                Settings.Global.getInt(
                        cr, Settings.Global.MODE_RINGER, AudioManager.RINGER_MODE_NORMAL);
        int ringerMode = ringerModeFromSettings;
        // sanity check in case the settings are restored from a device with incompatible
        // ringer modes
        if (!isValidRingerMode(ringerMode)) {
            ringerMode = AudioManager.RINGER_MODE_NORMAL;
        }
        if ((ringerMode == AudioManager.RINGER_MODE_VIBRATE) && !mHasVibrator) {
            ringerMode = AudioManager.RINGER_MODE_SILENT;
        }
        if (ringerMode != ringerModeFromSettings) {
            Settings.Global.putInt(cr, Settings.Global.MODE_RINGER, ringerMode);
        }
        if (mUseFixedVolume || mIsSingleVolume) {
            ringerMode = AudioManager.RINGER_MODE_NORMAL;
        }
        synchronized(mSettingsLock) {
            mRingerMode = ringerMode;
            if (mRingerModeExternal == -1) {
                mRingerModeExternal = mRingerMode;
            }

            // System.VIBRATE_ON is not used any more but defaults for mVibrateSetting
            // are still needed while setVibrateSetting() and getVibrateSetting() are being
            // deprecated.
            mVibrateSetting = AudioSystem.getValueForVibrateSetting(0,
                                            AudioManager.VIBRATE_TYPE_NOTIFICATION,
                                            mHasVibrator ? AudioManager.VIBRATE_SETTING_ONLY_SILENT
                                                            : AudioManager.VIBRATE_SETTING_OFF);
            mVibrateSetting = AudioSystem.getValueForVibrateSetting(mVibrateSetting,
                                            AudioManager.VIBRATE_TYPE_RINGER,
                                            mHasVibrator ? AudioManager.VIBRATE_SETTING_ONLY_SILENT
                                                            : AudioManager.VIBRATE_SETTING_OFF);

            updateRingerAndZenModeAffectedStreams();
            readDockAudioSettings(cr);
            sendEncodedSurroundMode(cr, "readPersistedSettings");
            sendEnabledSurroundFormats(cr, true);
            updateAssistantUId(true);
            updateCurrentImeUid(true);
            AudioSystem.setRttEnabled(mRttEnabled);
        }

        mMuteAffectedStreams = System.getIntForUser(cr,
                System.MUTE_STREAMS_AFFECTED, AudioSystem.DEFAULT_MUTE_STREAMS_AFFECTED,
                UserHandle.USER_CURRENT);

        updateMasterMono(cr);

        updateMasterBalance(cr);

        // Each stream will read its own persisted settings

        // Broadcast the sticky intents
        broadcastRingerMode(AudioManager.RINGER_MODE_CHANGED_ACTION, mRingerModeExternal);
        broadcastRingerMode(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION, mRingerMode);

        // Broadcast vibrate settings
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER);
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION);

        // Load settings for the volume controller
        mVolumeController.loadSettings(cr);
    }

    private void readUserRestrictions() {
        final int currentUser = getCurrentUserId();

        // Check the current user restriction.
        boolean masterMute =
                mUserManagerInternal.getUserRestriction(currentUser,
                        UserManager.DISALLOW_UNMUTE_DEVICE)
                        || mUserManagerInternal.getUserRestriction(currentUser,
                        UserManager.DISALLOW_ADJUST_VOLUME);
        if (mUseFixedVolume) {
            masterMute = false;
            AudioSystem.setMasterVolume(1.0f);
        }
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Master mute %s, user=%d", masterMute, currentUser));
        }
        setSystemAudioMute(masterMute);
        AudioSystem.setMasterMute(masterMute);
        broadcastMasterMuteStatus(masterMute);

        mMicMuteFromRestrictions = mUserManagerInternal.getUserRestriction(
                currentUser, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Mic mute %b, user=%d", mMicMuteFromRestrictions,
                    currentUser));
        }
        setMicrophoneMuteNoCallerCheck(currentUser);
    }

    private int getIndexRange(int streamType) {
        return (mStreamStates[streamType].getMaxIndex() - mStreamStates[streamType].getMinIndex());
    }

    private int rescaleIndex(int index, int srcStream, int dstStream) {
        int srcRange = getIndexRange(srcStream);
        int dstRange = getIndexRange(dstStream);
        if (srcRange == 0) {
            Log.e(TAG, "rescaleIndex : index range should not be zero");
            return mStreamStates[dstStream].getMinIndex();
        }

        return mStreamStates[dstStream].getMinIndex()
                + ((index - mStreamStates[srcStream].getMinIndex()) * dstRange + srcRange / 2)
                / srcRange;
    }

    private int rescaleStep(int step, int srcStream, int dstStream) {
        int srcRange = getIndexRange(srcStream);
        int dstRange = getIndexRange(dstStream);
        if (srcRange == 0) {
            Log.e(TAG, "rescaleStep : index range should not be zero");
            return 0;
        }

        return ((step * dstRange + srcRange / 2) / srcRange);
    }

    ///////////////////////////////////////////////////////////////////////////
    // IPC methods
    ///////////////////////////////////////////////////////////////////////////
    /** @see AudioManager#setPreferredDeviceForStrategy(AudioProductStrategy, AudioDeviceInfo) */
    public int setPreferredDeviceForStrategy(int strategy, AudioDeviceAttributes device) {
        if (device == null) {
            return AudioSystem.ERROR;
        }
        enforceModifyAudioRoutingPermission();
        final String logString = String.format(
                "setPreferredDeviceForStrategy u/pid:%d/%d strat:%d dev:%s",
                Binder.getCallingUid(), Binder.getCallingPid(), strategy, device.toString());
        sDeviceLogger.log(new AudioEventLogger.StringEvent(logString).printLog(TAG));
        if (device.getRole() == AudioDeviceAttributes.ROLE_INPUT) {
            Log.e(TAG, "Unsupported input routing in " + logString);
            return AudioSystem.ERROR;
        }

        final int status = mDeviceBroker.setPreferredDeviceForStrategySync(strategy, device);
        if (status != AudioSystem.SUCCESS) {
            Log.e(TAG, String.format("Error %d in %s)", status, logString));
        }

        return status;
    }

    /** @see AudioManager#removePreferredDeviceForStrategy(AudioProductStrategy) */
    public int removePreferredDeviceForStrategy(int strategy) {
        enforceModifyAudioRoutingPermission();
        final String logString =
                String.format("removePreferredDeviceForStrategy strat:%d", strategy);
        sDeviceLogger.log(new AudioEventLogger.StringEvent(logString).printLog(TAG));

        final int status = mDeviceBroker.removePreferredDeviceForStrategySync(strategy);
        if (status != AudioSystem.SUCCESS) {
            Log.e(TAG, String.format("Error %d in %s)", status, logString));
        }
        return status;
    }

    /** @see AudioManager#getPreferredDeviceForStrategy(AudioProductStrategy) */
    public AudioDeviceAttributes getPreferredDeviceForStrategy(int strategy) {
        enforceModifyAudioRoutingPermission();
        AudioDeviceAttributes[] devices = new AudioDeviceAttributes[1];
        final long identity = Binder.clearCallingIdentity();
        final int status = AudioSystem.getPreferredDeviceForStrategy(strategy, devices);
        Binder.restoreCallingIdentity(identity);
        if (status != AudioSystem.SUCCESS) {
            Log.e(TAG, String.format("Error %d in getPreferredDeviceForStrategy(%d)",
                    status, strategy));
            return null;
        } else {
            return devices[0];
        }
    }

    /** @see AudioManager#addOnPreferredDeviceForStrategyChangedListener(Executor, AudioManager.OnPreferredDeviceForStrategyChangedListener) */
    public void registerStrategyPreferredDeviceDispatcher(
            @Nullable IStrategyPreferredDeviceDispatcher dispatcher) {
        if (dispatcher == null) {
            return;
        }
        enforceModifyAudioRoutingPermission();
        mDeviceBroker.registerStrategyPreferredDeviceDispatcher(dispatcher);
    }

    /** @see AudioManager#removeOnPreferredDeviceForStrategyChangedListener(AudioManager.OnPreferredDeviceForStrategyChangedListener) */
    public void unregisterStrategyPreferredDeviceDispatcher(
            @Nullable IStrategyPreferredDeviceDispatcher dispatcher) {
        if (dispatcher == null) {
            return;
        }
        enforceModifyAudioRoutingPermission();
        mDeviceBroker.unregisterStrategyPreferredDeviceDispatcher(dispatcher);
    }

    /** @see AudioManager#getDevicesForAttributes(AudioAttributes) */
    public @NonNull ArrayList<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes) {
        Objects.requireNonNull(attributes);
        enforceModifyAudioRoutingPermission();
        return AudioSystem.getDevicesForAttributes(attributes);
    }


    /** @see AudioManager#adjustVolume(int, int) */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags,
            String callingPackage, String caller) {
        final IAudioPolicyCallback extVolCtlr;
        synchronized (mExtVolumeControllerLock) {
            extVolCtlr = mExtVolumeController;
        }
        if (extVolCtlr != null) {
            sendMsg(mAudioHandler, MSG_NOTIFY_VOL_EVENT, SENDMSG_QUEUE,
                    direction, 0 /*ignored*/,
                    extVolCtlr, 0 /*delay*/);

            new MediaMetrics.Item(mAnalyticsId + "adjustSuggestedStreamVolume")
                    .setUid(Binder.getCallingUid())
                    .putInt("extVolCtlr", extVolCtlr != null ? 1 : 0)
                    .putInt("direction", direction)
                    .putInt("flags", flags)
                    .putString("callingPackage", callingPackage)
                    .putString("caller", caller)
                    .record();
        } else {
            adjustSuggestedStreamVolume(direction, suggestedStreamType, flags, callingPackage,
                    caller, Binder.getCallingUid());
        }
    }

    private void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags,
            String callingPackage, String caller, int uid) {
        if (DEBUG_VOL) Log.d(TAG, "adjustSuggestedStreamVolume() stream=" + suggestedStreamType
                + ", flags=" + flags + ", caller=" + caller
                + ", volControlStream=" + mVolumeControlStream
                + ", userSelect=" + mUserSelectedVolumeControlStream);
        if (direction != AudioManager.ADJUST_SAME) {
            sVolumeLogger.log(new VolumeEvent(VolumeEvent.VOL_ADJUST_SUGG_VOL, suggestedStreamType,
                    direction/*val1*/, flags/*val2*/, new StringBuilder(callingPackage)
                    .append("/").append(caller).append(" uid:").append(uid).toString()));
        }
        final int streamType;
        synchronized (mForceControlStreamLock) {
            // Request lock in case mVolumeControlStream is changed by other thread.
            if (mUserSelectedVolumeControlStream) { // implies mVolumeControlStream != -1
                streamType = mVolumeControlStream;
            } else {
                final int maybeActiveStreamType = getActiveStreamType(suggestedStreamType);
                final boolean activeForReal;
                if (maybeActiveStreamType == AudioSystem.STREAM_RING
                        || maybeActiveStreamType == AudioSystem.STREAM_NOTIFICATION) {
                    activeForReal = wasStreamActiveRecently(maybeActiveStreamType, 0);
                } else {
                    activeForReal = AudioSystem.isStreamActive(maybeActiveStreamType, 0);
                }
                if (activeForReal || mVolumeControlStream == -1) {
                    streamType = maybeActiveStreamType;
                } else {
                    streamType = mVolumeControlStream;
                }
            }
        }

        final boolean isMute = isMuteAdjust(direction);

        ensureValidStreamType(streamType);
        final int resolvedStream = mStreamVolumeAlias[streamType];

        // Play sounds on STREAM_RING only.
        if ((flags & AudioManager.FLAG_PLAY_SOUND) != 0 &&
                resolvedStream != AudioSystem.STREAM_RING) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }

        // For notifications/ring, show the ui before making any adjustments
        // Don't suppress mute/unmute requests
        // Don't suppress adjustments for single volume device
        if (mVolumeController.suppressAdjustment(resolvedStream, flags, isMute)
                && !mIsSingleVolume) {
            direction = 0;
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
            flags &= ~AudioManager.FLAG_VIBRATE;
            if (DEBUG_VOL) Log.d(TAG, "Volume controller suppressed adjustment");
        }

        adjustStreamVolume(streamType, direction, flags, callingPackage, caller, uid);
    }

    /** @see AudioManager#adjustStreamVolume(int, int, int) */
    public void adjustStreamVolume(int streamType, int direction, int flags,
            String callingPackage) {
        if ((streamType == AudioManager.STREAM_ACCESSIBILITY) && !canChangeAccessibilityVolume()) {
            Log.w(TAG, "Trying to call adjustStreamVolume() for a11y without"
                    + "CHANGE_ACCESSIBILITY_VOLUME / callingPackage=" + callingPackage);
            return;
        }
        sVolumeLogger.log(new VolumeEvent(VolumeEvent.VOL_ADJUST_STREAM_VOL, streamType,
                direction/*val1*/, flags/*val2*/, callingPackage));
        adjustStreamVolume(streamType, direction, flags, callingPackage, callingPackage,
                Binder.getCallingUid());
    }

    protected void adjustStreamVolume(int streamType, int direction, int flags,
            String callingPackage, String caller, int uid) {
        if (mUseFixedVolume) {
            return;
        }
        if (DEBUG_VOL) Log.d(TAG, "adjustStreamVolume() stream=" + streamType + ", dir=" + direction
                + ", flags=" + flags + ", caller=" + caller);

        MediaMetrics.Item mmi = new MediaMetrics.Item(mAnalyticsId + "adjustStreamVolume")
                .setUid(uid)
                .setPid(Binder.getCallingPid())
                .putInt("streamType", streamType)
                .putInt("direction", direction)
                .putInt("flags", flags)
                .putString("callingPackage", callingPackage)
                .putString("caller", caller);

        ensureValidDirection(direction);
        ensureValidStreamType(streamType);

        boolean isMuteAdjust = isMuteAdjust(direction);

        if (isMuteAdjust && !isStreamAffectedByMute(streamType)) {
            mmi.putString(mAnalyticsPropEarlyReturn, "isMuteAdjust").record();
            return;
        }

        // If adjust is mute and the stream is STREAM_VOICE_CALL or STREAM_BLUETOOTH_SCO, make sure
        // that the calling app have the MODIFY_PHONE_STATE permission.
        if (isMuteAdjust &&
            (streamType == AudioSystem.STREAM_VOICE_CALL ||
                streamType == AudioSystem.STREAM_BLUETOOTH_SCO) &&
            mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MODIFY_PHONE_STATE Permission Denial: adjustStreamVolume from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            mmi.putString(mAnalyticsPropEarlyReturn, "Permission Denial").record();
            return;
        }

        // If the stream is STREAM_ASSISTANT,
        // make sure that the calling app have the MODIFY_AUDIO_ROUTING permission.
        if (streamType == AudioSystem.STREAM_ASSISTANT &&
            mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_AUDIO_ROUTING)
                    != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MODIFY_AUDIO_ROUTING Permission Denial: adjustStreamVolume from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }

        // use stream type alias here so that streams with same alias have the same behavior,
        // including with regard to silent mode control (e.g the use of STREAM_RING below and in
        // checkForRingerModeChange() in place of STREAM_RING or STREAM_NOTIFICATION)
        int streamTypeAlias = mStreamVolumeAlias[streamType];

        VolumeStreamState streamState = mStreamStates[streamTypeAlias];

        final int device = getDeviceForStream(streamTypeAlias);

        int aliasIndex = streamState.getIndex(device);
        boolean adjustVolume = true;
        int step;

        // skip a2dp absolute volume control request when the device
        // is not an a2dp device
        if (!AudioSystem.DEVICE_OUT_ALL_A2DP_SET.contains(device)
                && (flags & AudioManager.FLAG_BLUETOOTH_ABS_VOLUME) != 0) {
            mmi.putString(mAnalyticsPropEarlyReturn, "skip a2dp").record();
            return;
        }

        // If we are being called by the system (e.g. hardware keys) check for current user
        // so we handle user restrictions correctly.
        if (uid == android.os.Process.SYSTEM_UID) {
            uid = UserHandle.getUid(getCurrentUserId(), UserHandle.getAppId(uid));
        }
        if (mAppOps.noteOp(STREAM_VOLUME_OPS[streamTypeAlias], uid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            mmi.putString(mAnalyticsPropEarlyReturn, "mode not allowed").record();
            return;
        }

        // reset any pending volume command
        synchronized (mSafeMediaVolumeStateLock) {
            mPendingVolumeCommand = null;
        }

        flags &= ~AudioManager.FLAG_FIXED_VOLUME;
        if (streamTypeAlias == AudioSystem.STREAM_MUSIC && isFixedVolumeDevice(device)) {
            flags |= AudioManager.FLAG_FIXED_VOLUME;

            // Always toggle between max safe volume and 0 for fixed volume devices where safe
            // volume is enforced, and max and 0 for the others.
            // This is simulated by stepping by the full allowed volume range
            if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE &&
                    mSafeMediaVolumeDevices.contains(device)) {
                step = safeMediaVolumeIndex(device);
            } else {
                step = streamState.getMaxIndex();
            }
            if (aliasIndex != 0) {
                aliasIndex = step;
            }
        } else {
            // convert one UI step (+/-1) into a number of internal units on the stream alias
            step = rescaleStep(10, streamType, streamTypeAlias);
        }

        // If either the client forces allowing ringer modes for this adjustment,
        // or the stream type is one that is affected by ringer modes
        if (((flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0) ||
                (streamTypeAlias == getUiSoundsStreamType())) {
            int ringerMode = getRingerModeInternal();
            // do not vibrate if already in vibrate mode
            if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                flags &= ~AudioManager.FLAG_VIBRATE;
            }
            // Check if the ringer mode handles this adjustment. If it does we don't
            // need to adjust the volume further.
            final int result = checkForRingerModeChange(aliasIndex, direction, step,
                    streamState.mIsMuted, callingPackage, flags);
            adjustVolume = (result & FLAG_ADJUST_VOLUME) != 0;
            // If suppressing a volume adjustment in silent mode, display the UI hint
            if ((result & AudioManager.FLAG_SHOW_SILENT_HINT) != 0) {
                flags |= AudioManager.FLAG_SHOW_SILENT_HINT;
            }
            // If suppressing a volume down adjustment in vibrate mode, display the UI hint
            if ((result & AudioManager.FLAG_SHOW_VIBRATE_HINT) != 0) {
                flags |= AudioManager.FLAG_SHOW_VIBRATE_HINT;
            }
        }

        // If the ringer mode or zen is muting the stream, do not change stream unless
        // it'll cause us to exit dnd
        if (!volumeAdjustmentAllowedByDnd(streamTypeAlias, flags)) {
            adjustVolume = false;
        }
        int oldIndex = mStreamStates[streamType].getIndex(device);

        if (adjustVolume && (direction != AudioManager.ADJUST_SAME)) {
            mAudioHandler.removeMessages(MSG_UNMUTE_STREAM);

            if (isMuteAdjust && !mFullVolumeDevices.contains(device)) {
                boolean state;
                if (direction == AudioManager.ADJUST_TOGGLE_MUTE) {
                    state = !streamState.mIsMuted;
                } else {
                    state = direction == AudioManager.ADJUST_MUTE;
                }
                if (streamTypeAlias == AudioSystem.STREAM_MUSIC) {
                    setSystemAudioMute(state);
                }
                for (int stream = 0; stream < mStreamStates.length; stream++) {
                    if (streamTypeAlias == mStreamVolumeAlias[stream]) {
                        if (!(readCameraSoundForced()
                                    && (mStreamStates[stream].getStreamType()
                                        == AudioSystem.STREAM_SYSTEM_ENFORCED))) {
                            mStreamStates[stream].mute(state);
                        }
                    }
                }
            } else if ((direction == AudioManager.ADJUST_RAISE) &&
                    !checkSafeMediaVolume(streamTypeAlias, aliasIndex + step, device)) {
                Log.e(TAG, "adjustStreamVolume() safe volume index = " + oldIndex);
                mVolumeController.postDisplaySafeVolumeWarning(flags);
            } else if (!isFullVolumeDevice(device)
                    && (streamState.adjustIndex(direction * step, device, caller)
                            || streamState.mIsMuted)) {
                // Post message to set system volume (it in turn will post a
                // message to persist).
                if (streamState.mIsMuted) {
                    // Unmute the stream if it was previously muted
                    if (direction == AudioManager.ADJUST_RAISE) {
                        // unmute immediately for volume up
                        streamState.mute(false);
                    } else if (direction == AudioManager.ADJUST_LOWER) {
                        if (mIsSingleVolume) {
                            sendMsg(mAudioHandler, MSG_UNMUTE_STREAM, SENDMSG_QUEUE,
                                    streamTypeAlias, flags, null, UNMUTE_STREAM_DELAY);
                        }
                    }
                }
                sendMsg(mAudioHandler,
                        MSG_SET_DEVICE_VOLUME,
                        SENDMSG_QUEUE,
                        device,
                        0,
                        streamState,
                        0);

                mmi.putInt("device", device)
                        .putInt("streamType2", streamState.mStreamType)
                        .putInt("isMuted", streamState.mIsMuted ? 1 : 0)
                        .putInt("indexMin", streamState.mIndexMin)
                        .putInt("indexMax", streamState.mIndexMax)
                        .putInt("observedDevices", streamState.mObservedDevices)
                        .putInt("flags2", flags)
                        .putInt("streamTypeAlias", streamTypeAlias);
            }

            int newIndex = mStreamStates[streamType].getIndex(device);

            // Check if volume update should be send to AVRCP
            if (streamTypeAlias == AudioSystem.STREAM_MUSIC
                    && AudioSystem.DEVICE_OUT_ALL_A2DP_SET.contains(device)
                    && (flags & AudioManager.FLAG_BLUETOOTH_ABS_VOLUME) == 0) {
                if (DEBUG_VOL) {
                    Log.d(TAG, "adjustSreamVolume: postSetAvrcpAbsoluteVolumeIndex index="
                            + newIndex + "stream=" + streamType);
                }
                mDeviceBroker.postSetAvrcpAbsoluteVolumeIndex(newIndex / 10);
                mmi.putInt("postSetAvrcpAbsoluteVolumeIndex", newIndex / 10);
            }

            // Check if volume update should be send to Hearing Aid
            if (device == AudioSystem.DEVICE_OUT_HEARING_AID) {
                // only modify the hearing aid attenuation when the stream to modify matches
                // the one expected by the hearing aid
                if (streamType == getHearingAidStreamType()) {
                    if (DEBUG_VOL) {
                        Log.d(TAG, "adjustSreamVolume postSetHearingAidVolumeIndex index="
                                + newIndex + " stream=" + streamType);
                    }
                    mDeviceBroker.postSetHearingAidVolumeIndex(newIndex, streamType);
                }
            }

            // Check if volume update should be sent to Hdmi system audio.
            if (streamTypeAlias == AudioSystem.STREAM_MUSIC) {
                setSystemAudioVolume(oldIndex, newIndex, getStreamMaxVolume(streamType), flags);
            }
            synchronized (mHdmiClientLock) {
                if (mHdmiManager != null) {
                    // mHdmiCecSink true => mHdmiPlaybackClient != null
                    if (mHdmiCecSink
                            && streamTypeAlias == AudioSystem.STREAM_MUSIC
                            // vol change on a full volume device
                            && isFullVolumeDevice(device)) {
                        int keyCode = KeyEvent.KEYCODE_UNKNOWN;
                        switch (direction) {
                            case AudioManager.ADJUST_RAISE:
                                keyCode = KeyEvent.KEYCODE_VOLUME_UP;
                                break;
                            case AudioManager.ADJUST_LOWER:
                                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
                                break;
                            case AudioManager.ADJUST_TOGGLE_MUTE:
                                keyCode = KeyEvent.KEYCODE_VOLUME_MUTE;
                                break;
                            default:
                                break;
                        }
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            final long ident = Binder.clearCallingIdentity();
                            try {
                                mHdmiPlaybackClient.sendVolumeKeyEvent(keyCode, true);
                                mHdmiPlaybackClient.sendVolumeKeyEvent(keyCode, false);
                                mmi.putInt("HDMI.sendVolumeKeyEvent", keyCode);
                            } finally {
                                Binder.restoreCallingIdentity(ident);
                            }
                        }
                    }

                    if (streamTypeAlias == AudioSystem.STREAM_MUSIC
                            && (oldIndex != newIndex || isMuteAdjust)) {
                        maybeSendSystemAudioStatusCommand(isMuteAdjust);
                    }
                }
            }
        }
        mmi.record();
        int index = mStreamStates[streamType].getIndex(device);
        sendVolumeUpdate(streamType, oldIndex, index, flags, device);
    }

    // Called after a delay when volume down is pressed while muted
    private void onUnmuteStream(int stream, int flags) {
        boolean wasMuted;
        synchronized (VolumeStreamState.class) {
            final VolumeStreamState streamState = mStreamStates[stream];
            wasMuted = streamState.mute(false); // if unmuting causes a change, it was muted

            final int device = getDeviceForStream(stream);
            final int index = streamState.getIndex(device);
            sendVolumeUpdate(stream, index, index, flags, device);
        }
        if (stream == AudioSystem.STREAM_MUSIC && wasMuted) {
            synchronized (mHdmiClientLock) {
                maybeSendSystemAudioStatusCommand(true);
            }
        }
    }

    @GuardedBy("mHdmiClientLock")
    private void maybeSendSystemAudioStatusCommand(boolean isMuteAdjust) {
        if (mHdmiAudioSystemClient == null
                || !mHdmiSystemAudioSupported) {
            return;
        }

        final long identity = Binder.clearCallingIdentity();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(
                isMuteAdjust, getStreamVolume(AudioSystem.STREAM_MUSIC),
                getStreamMaxVolume(AudioSystem.STREAM_MUSIC),
                isStreamMute(AudioSystem.STREAM_MUSIC));
        Binder.restoreCallingIdentity(identity);
    }

    private void setSystemAudioVolume(int oldVolume, int newVolume, int maxVolume, int flags) {
        // Sets the audio volume of AVR when we are in system audio mode. The new volume info
        // is tranformed to HDMI-CEC commands and passed through CEC bus.
        synchronized (mHdmiClientLock) {
            if (mHdmiManager == null
                    || mHdmiTvClient == null
                    || oldVolume == newVolume
                    || (flags & AudioManager.FLAG_HDMI_SYSTEM_AUDIO_VOLUME) != 0
                    || !mHdmiSystemAudioSupported) {
                return;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                mHdmiTvClient.setSystemAudioVolume(oldVolume, newVolume, maxVolume);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    // StreamVolumeCommand contains the information needed to defer the process of
    // setStreamVolume() in case the user has to acknowledge the safe volume warning message.
    class StreamVolumeCommand {
        public final int mStreamType;
        public final int mIndex;
        public final int mFlags;
        public final int mDevice;

        StreamVolumeCommand(int streamType, int index, int flags, int device) {
            mStreamType = streamType;
            mIndex = index;
            mFlags = flags;
            mDevice = device;
        }

        @Override
        public String toString() {
            return new StringBuilder().append("{streamType=").append(mStreamType).append(",index=")
                    .append(mIndex).append(",flags=").append(mFlags).append(",device=")
                    .append(mDevice).append('}').toString();
        }
    };

    private int getNewRingerMode(int stream, int index, int flags) {
        // setRingerMode does nothing if the device is single volume,so the value would be unchanged
        if (mIsSingleVolume) {
            return getRingerModeExternal();
        }

        // setting volume on ui sounds stream type also controls silent mode
        if (((flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0) ||
                (stream == getUiSoundsStreamType())) {
            int newRingerMode;
            if (index == 0) {
                newRingerMode = mHasVibrator ? AudioManager.RINGER_MODE_VIBRATE
                        : mVolumePolicy.volumeDownToEnterSilent ? AudioManager.RINGER_MODE_SILENT
                                : AudioManager.RINGER_MODE_NORMAL;
            } else {
                newRingerMode = AudioManager.RINGER_MODE_NORMAL;
            }
            return newRingerMode;
        }
        return getRingerModeExternal();
    }

    private boolean isAndroidNPlus(String caller) {
        try {
            final ApplicationInfo applicationInfo =
                    mContext.getPackageManager().getApplicationInfoAsUser(
                            caller, 0, UserHandle.getUserId(Binder.getCallingUid()));
            if (applicationInfo.targetSdkVersion >= Build.VERSION_CODES.N) {
                return true;
            }
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private boolean wouldToggleZenMode(int newMode) {
        if (getRingerModeExternal() == AudioManager.RINGER_MODE_SILENT
                && newMode != AudioManager.RINGER_MODE_SILENT) {
            return true;
        } else if (getRingerModeExternal() != AudioManager.RINGER_MODE_SILENT
                && newMode == AudioManager.RINGER_MODE_SILENT) {
            return true;
        }
        return false;
    }

    private void onSetStreamVolume(int streamType, int index, int flags, int device,
            String caller) {
        final int stream = mStreamVolumeAlias[streamType];
        setStreamVolumeInt(stream, index, device, false, caller);
        // setting volume on ui sounds stream type also controls silent mode
        if (((flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0) ||
                (stream == getUiSoundsStreamType())) {
            setRingerMode(getNewRingerMode(stream, index, flags),
                    TAG + ".onSetStreamVolume", false /*external*/);
        }
        // setting non-zero volume for a muted stream unmutes the stream and vice versa,
        // except for BT SCO stream where only explicit mute is allowed to comply to BT requirements
        if (streamType != AudioSystem.STREAM_BLUETOOTH_SCO) {
            mStreamStates[stream].mute(index == 0);
        }
    }

    private void enforceModifyAudioRoutingPermission() {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_AUDIO_ROUTING)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Missing MODIFY_AUDIO_ROUTING permission");
        }
    }

    /** @see AudioManager#setVolumeIndexForAttributes(attr, int, int) */
    public void setVolumeIndexForAttributes(@NonNull AudioAttributes attr, int index, int flags,
                                            String callingPackage) {
        enforceModifyAudioRoutingPermission();
        Objects.requireNonNull(attr, "attr must not be null");
        final int volumeGroup = getVolumeGroupIdForAttributes(attr);
        if (sVolumeGroupStates.indexOfKey(volumeGroup) < 0) {
            Log.e(TAG, ": no volume group found for attributes " + attr.toString());
            return;
        }
        final VolumeGroupState vgs = sVolumeGroupStates.get(volumeGroup);

        sVolumeLogger.log(new VolumeEvent(VolumeEvent.VOL_SET_GROUP_VOL, attr, vgs.name(),
                index/*val1*/, flags/*val2*/, callingPackage));

        vgs.setVolumeIndex(index, flags);

        // For legacy reason, propagate to all streams associated to this volume group
        for (final int groupedStream : vgs.getLegacyStreamTypes()) {
            try {
                ensureValidStreamType(groupedStream);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "volume group " + volumeGroup + " has internal streams (" + groupedStream
                        + "), do not change associated stream volume");
                continue;
            }
            setStreamVolume(groupedStream, index, flags, callingPackage, callingPackage,
                            Binder.getCallingUid());
        }
    }

    @Nullable
    private AudioVolumeGroup getAudioVolumeGroupById(int volumeGroupId) {
        for (final AudioVolumeGroup avg : AudioVolumeGroup.getAudioVolumeGroups()) {
            if (avg.getId() == volumeGroupId) {
                return avg;
            }
        }

        Log.e(TAG, ": invalid volume group id: " + volumeGroupId + " requested");
        return null;
    }

    /** @see AudioManager#getVolumeIndexForAttributes(attr) */
    public int getVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        enforceModifyAudioRoutingPermission();
        Objects.requireNonNull(attr, "attr must not be null");
        final int volumeGroup = getVolumeGroupIdForAttributes(attr);
        if (sVolumeGroupStates.indexOfKey(volumeGroup) < 0) {
            throw new IllegalArgumentException("No volume group for attributes " + attr);
        }
        final VolumeGroupState vgs = sVolumeGroupStates.get(volumeGroup);
        return vgs.getVolumeIndex();
    }

    /** @see AudioManager#getMaxVolumeIndexForAttributes(attr) */
    public int getMaxVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        enforceModifyAudioRoutingPermission();
        Objects.requireNonNull(attr, "attr must not be null");
        return AudioSystem.getMaxVolumeIndexForAttributes(attr);
    }

    /** @see AudioManager#getMinVolumeIndexForAttributes(attr) */
    public int getMinVolumeIndexForAttributes(@NonNull AudioAttributes attr) {
        enforceModifyAudioRoutingPermission();
        Objects.requireNonNull(attr, "attr must not be null");
        return AudioSystem.getMinVolumeIndexForAttributes(attr);
    }

    /** @see AudioManager#setStreamVolume(int, int, int) */
    public void setStreamVolume(int streamType, int index, int flags, String callingPackage) {
        if ((streamType == AudioManager.STREAM_ACCESSIBILITY) && !canChangeAccessibilityVolume()) {
            Log.w(TAG, "Trying to call setStreamVolume() for a11y without"
                    + " CHANGE_ACCESSIBILITY_VOLUME  callingPackage=" + callingPackage);
            return;
        }
        if ((streamType == AudioManager.STREAM_VOICE_CALL) && (index == 0)
                && (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED)) {
            Log.w(TAG, "Trying to call setStreamVolume() for STREAM_VOICE_CALL and index 0 without"
                    + " MODIFY_PHONE_STATE  callingPackage=" + callingPackage);
            return;
        }
        if ((streamType == AudioManager.STREAM_ASSISTANT)
            && (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_AUDIO_ROUTING)
                    != PackageManager.PERMISSION_GRANTED)) {
            Log.w(TAG, "Trying to call setStreamVolume() for STREAM_ASSISTANT without"
                    + " MODIFY_AUDIO_ROUTING  callingPackage=" + callingPackage);
            return;
        }
        sVolumeLogger.log(new VolumeEvent(VolumeEvent.VOL_SET_STREAM_VOL, streamType,
                index/*val1*/, flags/*val2*/, callingPackage));
        setStreamVolume(streamType, index, flags, callingPackage, callingPackage,
                Binder.getCallingUid());
    }

    private boolean canChangeAccessibilityVolume() {
        synchronized (mAccessibilityServiceUidsLock) {
            if (PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.CHANGE_ACCESSIBILITY_VOLUME)) {
                return true;
            }
            if (mAccessibilityServiceUids != null) {
                int callingUid = Binder.getCallingUid();
                for (int i = 0; i < mAccessibilityServiceUids.length; i++) {
                    if (mAccessibilityServiceUids[i] == callingUid) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /*package*/ int getHearingAidStreamType() {
        return getHearingAidStreamType(mMode);
    }

    private int getHearingAidStreamType(int mode) {
        switch (mode) {
            case AudioSystem.MODE_IN_COMMUNICATION:
            case AudioSystem.MODE_IN_CALL:
                return AudioSystem.STREAM_VOICE_CALL;
            case AudioSystem.MODE_NORMAL:
            default:
                // other conditions will influence the stream type choice, read on...
                break;
        }
        if (mVoiceActive.get()) {
            return AudioSystem.STREAM_VOICE_CALL;
        }
        return AudioSystem.STREAM_MUSIC;
    }

    private AtomicBoolean mVoiceActive = new AtomicBoolean(false);

    private final IPlaybackConfigDispatcher mVoiceActivityMonitor =
            new IPlaybackConfigDispatcher.Stub() {
        @Override
        public void dispatchPlaybackConfigChange(List<AudioPlaybackConfiguration> configs,
                                                 boolean flush) {
            sendMsg(mAudioHandler, MSG_PLAYBACK_CONFIG_CHANGE, SENDMSG_REPLACE,
                    0 /*arg1 ignored*/, 0 /*arg2 ignored*/,
                    configs /*obj*/, 0 /*delay*/);
        }
    };

    private void onPlaybackConfigChange(List<AudioPlaybackConfiguration> configs) {
        boolean voiceActive = false;
        for (AudioPlaybackConfiguration config : configs) {
            final int usage = config.getAudioAttributes().getUsage();
            if ((usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                    || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    && config.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                voiceActive = true;
                break;
            }
        }
        if (mVoiceActive.getAndSet(voiceActive) != voiceActive) {
            updateHearingAidVolumeOnVoiceActivityUpdate();
        }
    }

    private void updateHearingAidVolumeOnVoiceActivityUpdate() {
        final int streamType = getHearingAidStreamType();
        final int index = getStreamVolume(streamType);
        sVolumeLogger.log(new VolumeEvent(VolumeEvent.VOL_VOICE_ACTIVITY_HEARING_AID,
                mVoiceActive.get(), streamType, index));
        mDeviceBroker.postSetHearingAidVolumeIndex(index * 10, streamType);

    }

    /**
     * Manage an audio mode change for audio devices that use an "absolute volume" model,
     * i.e. the framework sends the full scale signal, and the actual volume for the use case
     * is communicated separately.
     */
    void updateAbsVolumeMultiModeDevices(int oldMode, int newMode) {
        if (oldMode == newMode) {
            return;
        }
        switch (newMode) {
            case AudioSystem.MODE_IN_COMMUNICATION:
            case AudioSystem.MODE_IN_CALL:
            case AudioSystem.MODE_NORMAL:
                break;
            case AudioSystem.MODE_RINGTONE:
                // not changing anything for ringtone
                return;
            case AudioSystem.MODE_CURRENT:
            case AudioSystem.MODE_INVALID:
            default:
                // don't know what to do in this case, better bail
                return;
        }

        int streamType = getHearingAidStreamType(newMode);

        final Set<Integer> deviceTypes = AudioSystem.generateAudioDeviceTypesSet(
                AudioSystem.getDevicesForStream(streamType));
        final Set<Integer> absVolumeMultiModeCaseDevices = AudioSystem.intersectionAudioDeviceTypes(
                mAbsVolumeMultiModeCaseDevices, deviceTypes);
        if (absVolumeMultiModeCaseDevices.isEmpty()) {
            return;
        }

        // handling of specific interfaces goes here:
        if (AudioSystem.isSingleAudioDeviceType(
                absVolumeMultiModeCaseDevices, AudioSystem.DEVICE_OUT_HEARING_AID)) {
            final int index = getStreamVolume(streamType);
            sVolumeLogger.log(new VolumeEvent(VolumeEvent.VOL_MODE_CHANGE_HEARING_AID,
                    newMode, streamType, index));
            mDeviceBroker.postSetHearingAidVolumeIndex(index * 10, streamType);
        }
    }

    private void setStreamVolume(int streamType, int index, int flags, String callingPackage,
            String caller, int uid) {
        if (DEBUG_VOL) {
            Log.d(TAG, "setStreamVolume(stream=" + streamType+", index=" + index
                    + ", calling=" + callingPackage + ")");
        }
        if (mUseFixedVolume) {
            return;
        }

        ensureValidStreamType(streamType);
        int streamTypeAlias = mStreamVolumeAlias[streamType];
        VolumeStreamState streamState = mStreamStates[streamTypeAlias];

        final int device = getDeviceForStream(streamType);
        int oldIndex;

        // skip a2dp absolute volume control request when the device
        // is not an a2dp device
        if (!AudioSystem.DEVICE_OUT_ALL_A2DP_SET.contains(device)
                && (flags & AudioManager.FLAG_BLUETOOTH_ABS_VOLUME) != 0) {
            return;
        }
        // If we are being called by the system (e.g. hardware keys) check for current user
        // so we handle user restrictions correctly.
        if (uid == android.os.Process.SYSTEM_UID) {
            uid = UserHandle.getUid(getCurrentUserId(), UserHandle.getAppId(uid));
        }
        if (mAppOps.noteOp(STREAM_VOLUME_OPS[streamTypeAlias], uid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        if (isAndroidNPlus(callingPackage)
                && wouldToggleZenMode(getNewRingerMode(streamTypeAlias, index, flags))
                && !mNm.isNotificationPolicyAccessGrantedForPackage(callingPackage)) {
            throw new SecurityException("Not allowed to change Do Not Disturb state");
        }

        if (!volumeAdjustmentAllowedByDnd(streamTypeAlias, flags)) {
            return;
        }

        synchronized (mSafeMediaVolumeStateLock) {
            // reset any pending volume command
            mPendingVolumeCommand = null;

            oldIndex = streamState.getIndex(device);

            index = rescaleIndex(index * 10, streamType, streamTypeAlias);

            if (streamTypeAlias == AudioSystem.STREAM_MUSIC
                    && AudioSystem.DEVICE_OUT_ALL_A2DP_SET.contains(device)
                    && (flags & AudioManager.FLAG_BLUETOOTH_ABS_VOLUME) == 0) {
                if (DEBUG_VOL) {
                    Log.d(TAG, "setStreamVolume postSetAvrcpAbsoluteVolumeIndex index=" + index
                            + "stream=" + streamType);
                }
                mDeviceBroker.postSetAvrcpAbsoluteVolumeIndex(index / 10);
            }

            if (device == AudioSystem.DEVICE_OUT_HEARING_AID
                    && streamType == getHearingAidStreamType()) {
                Log.i(TAG, "setStreamVolume postSetHearingAidVolumeIndex index=" + index
                        + " stream=" + streamType);
                mDeviceBroker.postSetHearingAidVolumeIndex(index, streamType);
            }

            if (streamTypeAlias == AudioSystem.STREAM_MUSIC) {
                setSystemAudioVolume(oldIndex, index, getStreamMaxVolume(streamType), flags);
            }

            flags &= ~AudioManager.FLAG_FIXED_VOLUME;
            if (streamTypeAlias == AudioSystem.STREAM_MUSIC && isFixedVolumeDevice(device)) {
                flags |= AudioManager.FLAG_FIXED_VOLUME;

                // volume is either 0 or max allowed for fixed volume devices
                if (index != 0) {
                    if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE &&
                            mSafeMediaVolumeDevices.contains(device)) {
                        index = safeMediaVolumeIndex(device);
                    } else {
                        index = streamState.getMaxIndex();
                    }
                }
            }

            if (!checkSafeMediaVolume(streamTypeAlias, index, device)) {
                mVolumeController.postDisplaySafeVolumeWarning(flags);
                mPendingVolumeCommand = new StreamVolumeCommand(
                                                    streamType, index, flags, device);
            } else {
                onSetStreamVolume(streamType, index, flags, device, caller);
                index = mStreamStates[streamType].getIndex(device);
            }
        }
        synchronized (mHdmiClientLock) {
            if (streamTypeAlias == AudioSystem.STREAM_MUSIC
                    && (oldIndex != index)) {
                maybeSendSystemAudioStatusCommand(false);
            }
        }
        sendVolumeUpdate(streamType, oldIndex, index, flags, device);
    }



    private int getVolumeGroupIdForAttributes(@NonNull AudioAttributes attributes) {
        Objects.requireNonNull(attributes, "attributes must not be null");
        int volumeGroupId = getVolumeGroupIdForAttributesInt(attributes);
        if (volumeGroupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {
            return volumeGroupId;
        }
        // The default volume group is the one hosted by default product strategy, i.e.
        // supporting Default Attributes
        return getVolumeGroupIdForAttributesInt(AudioProductStrategy.sDefaultAttributes);
    }

    private int getVolumeGroupIdForAttributesInt(@NonNull AudioAttributes attributes) {
        Objects.requireNonNull(attributes, "attributes must not be null");
        for (final AudioProductStrategy productStrategy :
                AudioProductStrategy.getAudioProductStrategies()) {
            int volumeGroupId = productStrategy.getVolumeGroupIdForAudioAttributes(attributes);
            if (volumeGroupId != AudioVolumeGroup.DEFAULT_VOLUME_GROUP) {
                return volumeGroupId;
            }
        }
        return AudioVolumeGroup.DEFAULT_VOLUME_GROUP;
    }


    // No ringer or zen muted stream volumes can be changed unless it'll exit dnd
    private boolean volumeAdjustmentAllowedByDnd(int streamTypeAlias, int flags) {
        switch (mNm.getZenMode()) {
            case Settings.Global.ZEN_MODE_OFF:
                return true;
            case Settings.Global.ZEN_MODE_NO_INTERRUPTIONS:
            case Settings.Global.ZEN_MODE_ALARMS:
            case Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                return !isStreamMutedByRingerOrZenMode(streamTypeAlias)
                        || streamTypeAlias == getUiSoundsStreamType()
                        || (flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0;
        }

        return true;
    }

    /** @see AudioManager#forceVolumeControlStream(int) */
    public void forceVolumeControlStream(int streamType, IBinder cb) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (DEBUG_VOL) { Log.d(TAG, String.format("forceVolumeControlStream(%d)", streamType)); }
        synchronized(mForceControlStreamLock) {
            if (mVolumeControlStream != -1 && streamType != -1) {
                mUserSelectedVolumeControlStream = true;
            }
            mVolumeControlStream = streamType;
            if (mVolumeControlStream == -1) {
                if (mForceControlStreamClient != null) {
                    mForceControlStreamClient.release();
                    mForceControlStreamClient = null;
                }
                mUserSelectedVolumeControlStream = false;
            } else {
                if (null == mForceControlStreamClient) {
                    mForceControlStreamClient = new ForceControlStreamClient(cb);
                } else {
                    if (mForceControlStreamClient.getBinder() == cb) {
                        Log.d(TAG, "forceVolumeControlStream cb:" + cb + " is already linked.");
                    } else {
                        mForceControlStreamClient.release();
                        mForceControlStreamClient = new ForceControlStreamClient(cb);
                    }
                }
            }
        }
    }

    private class ForceControlStreamClient implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death

        ForceControlStreamClient(IBinder cb) {
            if (cb != null) {
                try {
                    cb.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    // Client has died!
                    Log.w(TAG, "ForceControlStreamClient() could not link to "+cb+" binder death");
                    cb = null;
                }
            }
            mCb = cb;
        }

        public void binderDied() {
            synchronized(mForceControlStreamLock) {
                Log.w(TAG, "SCO client died");
                if (mForceControlStreamClient != this) {
                    Log.w(TAG, "unregistered control stream client died");
                } else {
                    mForceControlStreamClient = null;
                    mVolumeControlStream = -1;
                    mUserSelectedVolumeControlStream = false;
                }
            }
        }

        public void release() {
            if (mCb != null) {
                mCb.unlinkToDeath(this, 0);
                mCb = null;
            }
        }

        public IBinder getBinder() {
            return mCb;
        }
    }

    private void sendBroadcastToAll(Intent intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void sendStickyBroadcastToAll(Intent intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int getCurrentUserId() {
        final long ident = Binder.clearCallingIdentity();
        try {
            UserInfo currentUser = ActivityManager.getService().getCurrentUser();
            return currentUser.id;
        } catch (RemoteException e) {
            // Activity manager not running, nothing we can do assume user 0.
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return UserHandle.USER_SYSTEM;
    }

    // UI update and Broadcast Intent
    protected void sendVolumeUpdate(int streamType, int oldIndex, int index, int flags, int device)
    {
        streamType = mStreamVolumeAlias[streamType];

        if (streamType == AudioSystem.STREAM_MUSIC) {
            flags = updateFlagsForTvPlatform(flags);
            if (isFullVolumeDevice(device)) {
                flags &= ~AudioManager.FLAG_SHOW_UI;
            }
        }
        mVolumeController.postVolumeChanged(streamType, flags);

        new MediaMetrics.Item(mAnalyticsId + "sendVolumeUpdate")
                .putInt("streamType", streamType)
                .putInt("oldIndex", oldIndex)
                .putInt("index", index)
                .putInt("flags", flags)
                .putInt("device", device)
                .record();
    }

    // If Hdmi-CEC system audio mode is on and we are a TV panel, never show volume bar.
    private int updateFlagsForTvPlatform(int flags) {
        synchronized (mHdmiClientLock) {
            if (mHdmiTvClient != null && mHdmiSystemAudioSupported) {
                flags &= ~AudioManager.FLAG_SHOW_UI;
            }
        }
        return flags;
    }

    // UI update and Broadcast Intent
    private void sendMasterMuteUpdate(boolean muted, int flags) {
        mVolumeController.postMasterMuteChanged(updateFlagsForTvPlatform(flags));
        broadcastMasterMuteStatus(muted);
    }

    private void broadcastMasterMuteStatus(boolean muted) {
        Intent intent = new Intent(AudioManager.MASTER_MUTE_CHANGED_ACTION);
        intent.putExtra(AudioManager.EXTRA_MASTER_VOLUME_MUTED, muted);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        sendStickyBroadcastToAll(intent);
    }

    /**
     * Sets the stream state's index, and posts a message to set system volume.
     * This will not call out to the UI. Assumes a valid stream type.
     *
     * @param streamType Type of the stream
     * @param index Desired volume index of the stream
     * @param device the device whose volume must be changed
     * @param force If true, set the volume even if the desired volume is same
     * as the current volume.
     */
    private void setStreamVolumeInt(int streamType,
                                    int index,
                                    int device,
                                    boolean force,
                                    String caller) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mAnalyticsId + "setStreamVolumeInt")
                .putInt("streamType", streamType)
                .putInt("index", index)
                .putInt("device", device)
                .putInt("force", force ? 1 : 0)
                .putString("caller", caller);

        if (isFullVolumeDevice(device)) {
            mmi.putString(mAnalyticsPropEarlyReturn, "mFullVolumeDevices");
            return;
        }
        VolumeStreamState streamState = mStreamStates[streamType];

        if (streamState.setIndex(index, device, caller) || force) {
            mmi.putInt("sendMsg", 1);
            // Post message to set system volume (it in turn will post a message
            // to persist).
            sendMsg(mAudioHandler,
                    MSG_SET_DEVICE_VOLUME,
                    SENDMSG_QUEUE,
                    device,
                    0,
                    streamState,
                    0);
        }
        mmi.record();
    }

    private void setSystemAudioMute(boolean state) {
        synchronized (mHdmiClientLock) {
            if (mHdmiManager == null || mHdmiTvClient == null || !mHdmiSystemAudioSupported) return;
            final long token = Binder.clearCallingIdentity();
            try {
                mHdmiTvClient.setSystemAudioMute(state);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /** get stream mute state. */
    public boolean isStreamMute(int streamType) {
        if (streamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
            streamType = getActiveStreamType(streamType);
        }
        synchronized (VolumeStreamState.class) {
            ensureValidStreamType(streamType);
            return mStreamStates[streamType].mIsMuted;
        }
    }

    private class RmtSbmxFullVolDeathHandler implements IBinder.DeathRecipient {
        private IBinder mICallback; // To be notified of client's death

        RmtSbmxFullVolDeathHandler(IBinder cb) {
            mICallback = cb;
            try {
                cb.linkToDeath(this, 0/*flags*/);
            } catch (RemoteException e) {
                Log.e(TAG, "can't link to death", e);
            }
        }

        boolean isHandlerFor(IBinder cb) {
            return mICallback.equals(cb);
        }

        void forget() {
            try {
                mICallback.unlinkToDeath(this, 0/*flags*/);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "error unlinking to death", e);
            }
        }

        public void binderDied() {
            Log.w(TAG, "Recorder with remote submix at full volume died " + mICallback);
            forceRemoteSubmixFullVolume(false, mICallback);
        }
    }

    /**
     * call must be synchronized on mRmtSbmxFullVolDeathHandlers
     * @return true if there is a registered death handler, false otherwise */
    private boolean discardRmtSbmxFullVolDeathHandlerFor(IBinder cb) {
        Iterator<RmtSbmxFullVolDeathHandler> it = mRmtSbmxFullVolDeathHandlers.iterator();
        while (it.hasNext()) {
            final RmtSbmxFullVolDeathHandler handler = it.next();
            if (handler.isHandlerFor(cb)) {
                handler.forget();
                mRmtSbmxFullVolDeathHandlers.remove(handler);
                return true;
            }
        }
        return false;
    }

    /** call synchronized on mRmtSbmxFullVolDeathHandlers */
    private boolean hasRmtSbmxFullVolDeathHandlerFor(IBinder cb) {
        Iterator<RmtSbmxFullVolDeathHandler> it = mRmtSbmxFullVolDeathHandlers.iterator();
        while (it.hasNext()) {
            if (it.next().isHandlerFor(cb)) {
                return true;
            }
        }
        return false;
    }

    private int mRmtSbmxFullVolRefCount = 0;
    private ArrayList<RmtSbmxFullVolDeathHandler> mRmtSbmxFullVolDeathHandlers =
            new ArrayList<RmtSbmxFullVolDeathHandler>();

    public void forceRemoteSubmixFullVolume(boolean startForcing, IBinder cb) {
        if (cb == null) {
            return;
        }
        if ((PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.CAPTURE_AUDIO_OUTPUT))) {
            Log.w(TAG, "Trying to call forceRemoteSubmixFullVolume() without CAPTURE_AUDIO_OUTPUT");
            return;
        }
        synchronized(mRmtSbmxFullVolDeathHandlers) {
            boolean applyRequired = false;
            if (startForcing) {
                if (!hasRmtSbmxFullVolDeathHandlerFor(cb)) {
                    mRmtSbmxFullVolDeathHandlers.add(new RmtSbmxFullVolDeathHandler(cb));
                    if (mRmtSbmxFullVolRefCount == 0) {
                        mFullVolumeDevices.add(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX);
                        mFixedVolumeDevices.add(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX);
                        applyRequired = true;
                    }
                    mRmtSbmxFullVolRefCount++;
                }
            } else {
                if (discardRmtSbmxFullVolDeathHandlerFor(cb) && (mRmtSbmxFullVolRefCount > 0)) {
                    mRmtSbmxFullVolRefCount--;
                    if (mRmtSbmxFullVolRefCount == 0) {
                        mFullVolumeDevices.remove(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX);
                        mFixedVolumeDevices.remove(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX);
                        applyRequired = true;
                    }
                }
            }
            if (applyRequired) {
                // Assumes only STREAM_MUSIC going through DEVICE_OUT_REMOTE_SUBMIX
                checkAllFixedVolumeDevices(AudioSystem.STREAM_MUSIC);
                mStreamStates[AudioSystem.STREAM_MUSIC].applyAllVolumes();
            }
        }
    }

    private void setMasterMuteInternal(boolean mute, int flags, String callingPackage, int uid,
            int userId) {
        // If we are being called by the system check for user we are going to change
        // so we handle user restrictions correctly.
        if (uid == android.os.Process.SYSTEM_UID) {
            uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
        }
        // If OP_AUDIO_MASTER_VOLUME is set, disallow unmuting.
        if (!mute && mAppOps.noteOp(AppOpsManager.OP_AUDIO_MASTER_VOLUME, uid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        if (userId != UserHandle.getCallingUserId() &&
                mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        setMasterMuteInternalNoCallerCheck(mute, flags, userId);
    }

    private void setMasterMuteInternalNoCallerCheck(boolean mute, int flags, int userId) {
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Master mute %s, %d, user=%d", mute, flags, userId));
        }
        if (!isPlatformAutomotive() && mUseFixedVolume) {
            // If using fixed volume, we don't mute.
            // TODO: remove the isPlatformAutomotive check here.
            // The isPlatformAutomotive check is added for safety but may not be necessary.
            return;
        }
        // For automotive,
        // - the car service is always running as system user
        // - foreground users are non-system users
        // Car service is in charge of dispatching the key event include master mute to Android.
        // Therefore, the getCurrentUser() is always different to the foreground user.
        if ((isPlatformAutomotive() && userId == UserHandle.USER_SYSTEM)
                || (getCurrentUserId() == userId)) {
            if (mute != AudioSystem.getMasterMute()) {
                setSystemAudioMute(mute);
                AudioSystem.setMasterMute(mute);
                sendMasterMuteUpdate(mute, flags);
            }
        }
    }

    /** get master mute state. */
    public boolean isMasterMute() {
        return AudioSystem.getMasterMute();
    }

    public void setMasterMute(boolean mute, int flags, String callingPackage, int userId) {
        enforceModifyAudioRoutingPermission();
        setMasterMuteInternal(mute, flags, callingPackage, Binder.getCallingUid(),
                userId);
    }

    /** @see AudioManager#getStreamVolume(int) */
    public int getStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        int device = getDeviceForStream(streamType);
        synchronized (VolumeStreamState.class) {
            int index = mStreamStates[streamType].getIndex(device);

            // by convention getStreamVolume() returns 0 when a stream is muted.
            if (mStreamStates[streamType].mIsMuted) {
                index = 0;
            }
            if (index != 0 && (mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC) &&
                    isFixedVolumeDevice(device)) {
                index = mStreamStates[streamType].getMaxIndex();
            }
            return (index + 5) / 10;
        }
    }

    /** @see AudioManager#getStreamMaxVolume(int) */
    public int getStreamMaxVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].getMaxIndex() + 5) / 10;
    }

    /** @see AudioManager#getStreamMinVolumeInt(int) */
    public int getStreamMinVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].getMinIndex() + 5) / 10;
    }

    /** Get last audible volume before stream was muted. */
    public int getLastAudibleStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        int device = getDeviceForStream(streamType);
        return (mStreamStates[streamType].getIndex(device) + 5) / 10;
    }

    /** @see AudioManager#getUiSoundsStreamType()  */
    public int getUiSoundsStreamType() {
        return mStreamVolumeAlias[AudioSystem.STREAM_SYSTEM];
    }

    /** @see AudioManager#setMicrophoneMute(boolean) */
    @Override
    public void setMicrophoneMute(boolean on, String callingPackage, int userId) {
        // If we are being called by the system check for user we are going to change
        // so we handle user restrictions correctly.
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.SYSTEM_UID) {
            uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
        }
        MediaMetrics.Item mmi = new MediaMetrics.Item(mAnalyticsId + "setMicrophoneMute")
                .setUid(uid)
                .putInt("userId", userId)
                .putString("callingPackage", callingPackage);

        // If OP_MUTE_MICROPHONE is set, disallow unmuting.
        if (!on && mAppOps.noteOp(AppOpsManager.OP_MUTE_MICROPHONE, uid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            mmi.putString(mAnalyticsPropEarlyReturn, "disallow unmuting").record();
            return;
        }
        if (!checkAudioSettingsPermission("setMicrophoneMute()")) {
            mmi.putString(mAnalyticsPropEarlyReturn, "!checkAudioSettingsPermission").record();
            return;
        }
        if (userId != UserHandle.getCallingUserId() &&
                mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            mmi.putString(mAnalyticsPropEarlyReturn, "permission").record();
            return;
        }
        mMicMuteFromApi = on;

        setMicrophoneMuteNoCallerCheck(userId);

        mmi.putInt("mMicMuteFromApi", mMicMuteFromApi ? 1 : 0)
                .record();
    }

    /** @see AudioManager#setMicrophoneMuteFromSwitch(boolean) */
    public void setMicrophoneMuteFromSwitch(boolean on) {
        int userId = Binder.getCallingUid();
        if (userId != android.os.Process.SYSTEM_UID) {
            Log.e(TAG, "setMicrophoneMuteFromSwitch() called from non system user!");
            return;
        }
        mMicMuteFromSwitch = on;
        new MediaMetrics.Item(mAnalyticsId + "setMicrophoneMuteFromSwitch")
                .setUid(userId)
                .putInt("mMicMuteFromSwitch", mMicMuteFromSwitch ? 1 : 0)
                .record();
        setMicrophoneMuteNoCallerCheck(userId);
    }

    private void setMicMuteFromSwitchInput() {
        InputManager im = mContext.getSystemService(InputManager.class);
        final int isMicMuted = im.isMicMuted();
        if (isMicMuted != InputManager.SWITCH_STATE_UNKNOWN) {
            new MediaMetrics.Item(mAnalyticsId + "setMicMuteFromSwitchInput")
                    .putInt("isMicMuted", isMicMuted)
                    .record();
            setMicrophoneMuteFromSwitch(im.isMicMuted() != InputManager.SWITCH_STATE_OFF);
        }
    }

    public boolean isMicrophoneMuted() {
        return mMicMuteFromSwitch || mMicMuteFromRestrictions || mMicMuteFromApi;
    }

    private void setMicrophoneMuteNoCallerCheck(int userId) {
        final boolean muted = isMicrophoneMuted();
        if (DEBUG_VOL) {
            Log.d(TAG, String.format("Mic mute %b, user=%d", muted, userId));
        }
        // only mute for the current user
        if (getCurrentUserId() == userId || userId == android.os.Process.SYSTEM_UID) {
            final boolean currentMute = AudioSystem.isMicrophoneMuted();
            final long identity = Binder.clearCallingIdentity();
            AudioSystem.muteMicrophone(muted);

            new MediaMetrics.Item(mAnalyticsId + "setMicrophoneMuteNoCallerCheck")
                    .setUid(userId)
                    .putInt("muted", muted ? 1 : 0)
                    .putInt("currentMute", currentMute ? 1 : 0)
                    .putLong("identity", identity)
                    .record();
            try {
                if (muted != currentMute) {
                    sendMsg(mAudioHandler, MSG_BROADCAST_MICROPHONE_MUTE,
                                SENDMSG_NOOP, 0, 0, null, 0);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public int getRingerModeExternal() {
        synchronized(mSettingsLock) {
            return mRingerModeExternal;
        }
    }

    @Override
    public int getRingerModeInternal() {
        synchronized(mSettingsLock) {
            return mRingerMode;
        }
    }

    private void ensureValidRingerMode(int ringerMode) {
        if (!isValidRingerMode(ringerMode)) {
            throw new IllegalArgumentException("Bad ringer mode " + ringerMode);
        }
    }

    /** @see AudioManager#isValidRingerMode(int) */
    public boolean isValidRingerMode(int ringerMode) {
        return ringerMode >= 0 && ringerMode <= AudioManager.RINGER_MODE_MAX;
    }

    public void setRingerModeExternal(int ringerMode, String caller) {
        if (isAndroidNPlus(caller) && wouldToggleZenMode(ringerMode)
                && !mNm.isNotificationPolicyAccessGrantedForPackage(caller)) {
            throw new SecurityException("Not allowed to change Do Not Disturb state");
        }

        setRingerMode(ringerMode, caller, true /*external*/);
    }

    public void setRingerModeInternal(int ringerMode, String caller) {
        enforceVolumeController("setRingerModeInternal");
        setRingerMode(ringerMode, caller, false /*external*/);
    }

    public void silenceRingerModeInternal(String reason) {
        VibrationEffect effect = null;
        int ringerMode = AudioManager.RINGER_MODE_SILENT;
        int toastText = 0;

        int silenceRingerSetting = Settings.Secure.VOLUME_HUSH_OFF;
        if (mContext.getResources()
                .getBoolean(com.android.internal.R.bool.config_volumeHushGestureEnabled)) {
            silenceRingerSetting = Settings.Secure.getIntForUser(mContentResolver,
                    Settings.Secure.VOLUME_HUSH_GESTURE, VOLUME_HUSH_OFF,
                    UserHandle.USER_CURRENT);
        }

        switch(silenceRingerSetting) {
            case VOLUME_HUSH_MUTE:
                effect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);
                ringerMode = AudioManager.RINGER_MODE_SILENT;
                toastText = com.android.internal.R.string.volume_dialog_ringer_guidance_silent;
                break;
            case VOLUME_HUSH_VIBRATE:
                effect = VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK);
                ringerMode = AudioManager.RINGER_MODE_VIBRATE;
                toastText = com.android.internal.R.string.volume_dialog_ringer_guidance_vibrate;
                break;
        }
        maybeVibrate(effect, reason);
        setRingerModeInternal(ringerMode, reason);
        Toast.makeText(mContext, toastText, Toast.LENGTH_SHORT).show();
    }

    private boolean maybeVibrate(VibrationEffect effect, String reason) {
        if (!mHasVibrator) {
            return false;
        }
        final boolean hapticsDisabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0, UserHandle.USER_CURRENT) == 0;
        if (hapticsDisabled) {
            return false;
        }

        if (effect == null) {
            return false;
        }
        mVibrator.vibrate(Binder.getCallingUid(), mContext.getOpPackageName(), effect,
                reason, VIBRATION_ATTRIBUTES);
        return true;
    }

    private void setRingerMode(int ringerMode, String caller, boolean external) {
        if (mUseFixedVolume || mIsSingleVolume) {
            return;
        }
        if (caller == null || caller.length() == 0) {
            throw new IllegalArgumentException("Bad caller: " + caller);
        }
        ensureValidRingerMode(ringerMode);
        if ((ringerMode == AudioManager.RINGER_MODE_VIBRATE) && !mHasVibrator) {
            ringerMode = AudioManager.RINGER_MODE_SILENT;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mSettingsLock) {
                final int ringerModeInternal = getRingerModeInternal();
                final int ringerModeExternal = getRingerModeExternal();
                new MediaMetrics.Item(mAnalyticsId + "setRingerMode")
                        .putInt("ringerMode", ringerMode)
                        .putInt("external", external ? 1 : 0)
                        .putString("caller", caller)
                        .putInt("ringerModeInternal", ringerModeInternal)
                        .putInt("ringerModeExternal", ringerModeExternal)
                        .record();
                if (external) {
                    setRingerModeExt(ringerMode);
                    if (mRingerModeDelegate != null) {
                        ringerMode = mRingerModeDelegate.onSetRingerModeExternal(ringerModeExternal,
                                ringerMode, caller, ringerModeInternal, mVolumePolicy);
                    }
                    if (ringerMode != ringerModeInternal) {
                        setRingerModeInt(ringerMode, true /*persist*/);
                    }
                } else /*internal*/ {
                    if (ringerMode != ringerModeInternal) {
                        setRingerModeInt(ringerMode, true /*persist*/);
                    }
                    if (mRingerModeDelegate != null) {
                        ringerMode = mRingerModeDelegate.onSetRingerModeInternal(ringerModeInternal,
                                ringerMode, caller, ringerModeExternal, mVolumePolicy);
                    }
                    setRingerModeExt(ringerMode);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void setRingerModeExt(int ringerMode) {
        synchronized(mSettingsLock) {
            if (ringerMode == mRingerModeExternal) return;
            mRingerModeExternal = ringerMode;
        }
        // Send sticky broadcast
        broadcastRingerMode(AudioManager.RINGER_MODE_CHANGED_ACTION, ringerMode);
    }

    @GuardedBy("mSettingsLock")
    private void muteRingerModeStreams() {
        // Mute stream if not previously muted by ringer mode and (ringer mode
        // is not RINGER_MODE_NORMAL OR stream is zen muted) and stream is affected by ringer mode.
        // Unmute stream if previously muted by ringer/zen mode and ringer mode
        // is RINGER_MODE_NORMAL or stream is not affected by ringer mode.
        int numStreamTypes = AudioSystem.getNumStreamTypes();

        if (mNm == null) {
            mNm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        final int ringerMode = mRingerMode; // Read ringer mode as reading primitives is atomic
        final boolean ringerModeMute = ringerMode == AudioManager.RINGER_MODE_VIBRATE
                || ringerMode == AudioManager.RINGER_MODE_SILENT;
        final boolean shouldRingSco = ringerMode == AudioManager.RINGER_MODE_VIBRATE
                && isBluetoothScoOn();
        // Ask audio policy engine to force use Bluetooth SCO channel if needed
        final String eventSource = "muteRingerModeStreams() from u/pid:" + Binder.getCallingUid()
                + "/" + Binder.getCallingPid();
        sendMsg(mAudioHandler, MSG_SET_FORCE_USE, SENDMSG_QUEUE, AudioSystem.FOR_VIBRATE_RINGING,
                shouldRingSco ? AudioSystem.FORCE_BT_SCO : AudioSystem.FORCE_NONE, eventSource, 0);

        for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
            final boolean isMuted = isStreamMutedByRingerOrZenMode(streamType);
            final boolean muteAllowedBySco =
                    !(shouldRingSco && streamType == AudioSystem.STREAM_RING);
            final boolean shouldZenMute = shouldZenMuteStream(streamType);
            final boolean shouldMute = shouldZenMute || (ringerModeMute
                    && isStreamAffectedByRingerMode(streamType) && muteAllowedBySco);
            if (isMuted == shouldMute) continue;
            if (!shouldMute) {
                // unmute
                // ring and notifications volume should never be 0 when not silenced
                if (mStreamVolumeAlias[streamType] == AudioSystem.STREAM_RING) {
                    synchronized (VolumeStreamState.class) {
                        final VolumeStreamState vss = mStreamStates[streamType];
                        for (int i = 0; i < vss.mIndexMap.size(); i++) {
                            int device = vss.mIndexMap.keyAt(i);
                            int value = vss.mIndexMap.valueAt(i);
                            if (value == 0) {
                                vss.setIndex(10, device, TAG);
                            }
                        }
                        // Persist volume for stream ring when it is changed here
                      final int device = getDeviceForStream(streamType);
                      sendMsg(mAudioHandler,
                              MSG_PERSIST_VOLUME,
                              SENDMSG_QUEUE,
                              device,
                              0,
                              mStreamStates[streamType],
                              PERSIST_DELAY);
                    }
                }
                mStreamStates[streamType].mute(false);
                mRingerAndZenModeMutedStreams &= ~(1 << streamType);
            } else {
                // mute
                mStreamStates[streamType].mute(true);
                mRingerAndZenModeMutedStreams |= (1 << streamType);
            }
        }
    }

    private boolean isAlarm(int streamType) {
        return streamType == AudioSystem.STREAM_ALARM;
    }

    private boolean isNotificationOrRinger(int streamType) {
        return streamType == AudioSystem.STREAM_NOTIFICATION
                || streamType == AudioSystem.STREAM_RING;
    }

    private boolean isMedia(int streamType) {
        return streamType == AudioSystem.STREAM_MUSIC;
    }


    private boolean isSystem(int streamType) {
        return streamType == AudioSystem.STREAM_SYSTEM;
    }

    private void setRingerModeInt(int ringerMode, boolean persist) {
        final boolean change;
        synchronized(mSettingsLock) {
            change = mRingerMode != ringerMode;
            mRingerMode = ringerMode;
            muteRingerModeStreams();
        }

        // Post a persist ringer mode msg
        if (persist) {
            sendMsg(mAudioHandler, MSG_PERSIST_RINGER_MODE,
                    SENDMSG_REPLACE, 0, 0, null, PERSIST_DELAY);
        }
        if (change) {
            // Send sticky broadcast
            broadcastRingerMode(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION, ringerMode);
        }
    }

    /*package*/ void postUpdateRingerModeServiceInt() {
        sendMsg(mAudioHandler, MSG_UPDATE_RINGER_MODE, SENDMSG_QUEUE, 0, 0, null, 0);
    }

    private void onUpdateRingerModeServiceInt() {
        setRingerModeInt(getRingerModeInternal(), false);
    }

    /** @see AudioManager#shouldVibrate(int) */
    public boolean shouldVibrate(int vibrateType) {
        if (!mHasVibrator) return false;

        switch (getVibrateSetting(vibrateType)) {

            case AudioManager.VIBRATE_SETTING_ON:
                return getRingerModeExternal() != AudioManager.RINGER_MODE_SILENT;

            case AudioManager.VIBRATE_SETTING_ONLY_SILENT:
                return getRingerModeExternal() == AudioManager.RINGER_MODE_VIBRATE;

            case AudioManager.VIBRATE_SETTING_OFF:
                // return false, even for incoming calls
                return false;

            default:
                return false;
        }
    }

    /** @see AudioManager#getVibrateSetting(int) */
    public int getVibrateSetting(int vibrateType) {
        if (!mHasVibrator) return AudioManager.VIBRATE_SETTING_OFF;
        return (mVibrateSetting >> (vibrateType * 2)) & 3;
    }

    /** @see AudioManager#setVibrateSetting(int, int) */
    public void setVibrateSetting(int vibrateType, int vibrateSetting) {

        if (!mHasVibrator) return;

        mVibrateSetting = AudioSystem.getValueForVibrateSetting(mVibrateSetting, vibrateType,
                vibrateSetting);

        // Broadcast change
        broadcastVibrateSetting(vibrateType);

    }

    /**
     * Return the pid of the current audio mode owner
     * @return 0 if nobody owns the mode
     */
    /*package*/ int getModeOwnerPid() {
        int modeOwnerPid = 0;
        try {
            modeOwnerPid = mSetModeDeathHandlers.get(0).getPid();
        } catch (Exception e) {
            // nothing to do, modeOwnerPid is not modified
        }
        return modeOwnerPid;
    }

    /**
     * Return the uid of the current audio mode owner
     * @return 0 if nobody owns the mode
     */
    /*package*/ int getModeOwnerUid() {
        int modeOwnerUid = 0;
        try {
            modeOwnerUid = mSetModeDeathHandlers.get(0).getUid();
        } catch (Exception e) {
            // nothing to do, modeOwnerUid is not modified
        }
        return modeOwnerUid;
    }

    private class SetModeDeathHandler implements IBinder.DeathRecipient {
        private final IBinder mCb; // To be notified of client's death
        private final int mPid;
        private final int mUid;
        private int mMode = AudioSystem.MODE_NORMAL; // Current mode set by this client

        SetModeDeathHandler(IBinder cb, int pid, int uid) {
            mCb = cb;
            mPid = pid;
            mUid = uid;
        }

        public void binderDied() {
            int oldModeOwnerPid;
            int newModeOwnerPid = 0;
            synchronized (mDeviceBroker.mSetModeLock) {
                Log.w(TAG, "setMode() client died");
                oldModeOwnerPid = getModeOwnerPid();
                int index = mSetModeDeathHandlers.indexOf(this);
                if (index < 0) {
                    Log.w(TAG, "unregistered setMode() client died");
                } else {
                    newModeOwnerPid = setModeInt(AudioSystem.MODE_NORMAL, mCb, mPid, mUid, TAG);
                }
            }
            // when entering RINGTONE, IN_CALL or IN_COMMUNICATION mode, clear all
            // SCO connections not started by the application changing the mode when pid changes
            if ((newModeOwnerPid != oldModeOwnerPid) && (newModeOwnerPid != 0)) {
                mDeviceBroker.postDisconnectBluetoothSco(newModeOwnerPid);
            }
        }

        public int getPid() {
            return mPid;
        }

        public void setMode(int mode) {
            mMode = mode;
        }

        public int getMode() {
            return mMode;
        }

        public IBinder getBinder() {
            return mCb;
        }

        public int getUid() {
            return mUid;
        }
    }

    /** @see AudioManager#setMode(int) */
    public void setMode(int mode, IBinder cb, String callingPackage) {
        if (DEBUG_MODE) {
            Log.v(TAG, "setMode(mode=" + mode + ", callingPackage=" + callingPackage + ")");
        }
        if (!checkAudioSettingsPermission("setMode()")) {
            return;
        }
        final boolean hasModifyPhoneStatePermission = mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MODIFY_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED;
        final int callingPid = Binder.getCallingPid();
        if ((mode == AudioSystem.MODE_IN_CALL) && !hasModifyPhoneStatePermission) {
            Log.w(TAG, "MODIFY_PHONE_STATE Permission Denial: setMode(MODE_IN_CALL) from pid="
                    + callingPid + ", uid=" + Binder.getCallingUid());
            return;
        }

        if (mode == AudioSystem.MODE_CALL_SCREENING && !mIsCallScreeningModeSupported) {
            Log.w(TAG, "setMode(MODE_CALL_SCREENING) not permitted "
                    + "when call screening is not supported");
            return;
        }

        if (mode < AudioSystem.MODE_CURRENT || mode >= AudioSystem.NUM_MODES) {
            return;
        }

        int oldModeOwnerPid;
        int newModeOwnerPid;
        synchronized (mDeviceBroker.mSetModeLock) {
            if (mode == AudioSystem.MODE_CURRENT) {
                mode = mMode;
            }
            oldModeOwnerPid = getModeOwnerPid();
            // Do not allow changing mode if a call is active and the requester
            // does not have permission to modify phone state or is not the mode owner.
            if (((mMode == AudioSystem.MODE_IN_CALL)
                    || (mMode == AudioSystem.MODE_IN_COMMUNICATION))
                    && !(hasModifyPhoneStatePermission || (oldModeOwnerPid == callingPid))) {
                Log.w(TAG, "setMode(" + mode + ") from pid=" + callingPid
                        + ", uid=" + Binder.getCallingUid()
                        + ", cannot change mode from " + mMode
                        + " without permission or being mode owner");
                return;
            }
            newModeOwnerPid = setModeInt(
                mode, cb, callingPid, Binder.getCallingUid(), callingPackage);
        }
        // when entering RINGTONE, IN_CALL or IN_COMMUNICATION mode, clear all
        // SCO connections not started by the application changing the mode when pid changes
        if ((newModeOwnerPid != oldModeOwnerPid) && (newModeOwnerPid != 0)) {
            mDeviceBroker.postDisconnectBluetoothSco(newModeOwnerPid);
        }
    }

    // setModeInt() returns a valid PID if the audio mode was successfully set to
    // any mode other than NORMAL.
    @GuardedBy("mDeviceBroker.mSetModeLock")
    private int setModeInt(int mode, IBinder cb, int pid, int uid, String caller) {
        if (DEBUG_MODE) {
            Log.v(TAG, "setModeInt(mode=" + mode + ", pid=" + pid
                    + ", uid=" + uid + ", caller=" + caller + ")");
        }
        int newModeOwnerPid = 0;
        if (cb == null) {
            Log.e(TAG, "setModeInt() called with null binder");
            return newModeOwnerPid;
        }

        SetModeDeathHandler hdlr = null;
        Iterator iter = mSetModeDeathHandlers.iterator();
        while (iter.hasNext()) {
            SetModeDeathHandler h = (SetModeDeathHandler)iter.next();
            if (h.getPid() == pid) {
                hdlr = h;
                // Remove from client list so that it is re-inserted at top of list
                iter.remove();
                hdlr.getBinder().unlinkToDeath(hdlr, 0);
                break;
            }
        }
        final int oldMode = mMode;
        int status = AudioSystem.AUDIO_STATUS_OK;
        int actualMode;
        do {
            actualMode = mode;
            if (mode == AudioSystem.MODE_NORMAL) {
                // get new mode from client at top the list if any
                if (!mSetModeDeathHandlers.isEmpty()) {
                    hdlr = mSetModeDeathHandlers.get(0);
                    cb = hdlr.getBinder();
                    actualMode = hdlr.getMode();
                    if (DEBUG_MODE) {
                        Log.w(TAG, " using mode=" + mode + " instead due to death hdlr at pid="
                                + hdlr.mPid);
                    }
                }
            } else {
                if (hdlr == null) {
                    hdlr = new SetModeDeathHandler(cb, pid, uid);
                }
                // Register for client death notification
                try {
                    cb.linkToDeath(hdlr, 0);
                } catch (RemoteException e) {
                    // Client has died!
                    Log.w(TAG, "setMode() could not link to "+cb+" binder death");
                }

                // Last client to call setMode() is always at top of client list
                // as required by SetModeDeathHandler.binderDied()
                mSetModeDeathHandlers.add(0, hdlr);
                hdlr.setMode(mode);
            }

            if (actualMode != mMode) {
                final long identity = Binder.clearCallingIdentity();
                status = AudioSystem.setPhoneState(actualMode, getModeOwnerUid());
                Binder.restoreCallingIdentity(identity);
                if (status == AudioSystem.AUDIO_STATUS_OK) {
                    if (DEBUG_MODE) { Log.v(TAG, " mode successfully set to " + actualMode); }
                    mMode = actualMode;
                } else {
                    if (hdlr != null) {
                        mSetModeDeathHandlers.remove(hdlr);
                        cb.unlinkToDeath(hdlr, 0);
                    }
                    // force reading new top of mSetModeDeathHandlers stack
                    if (DEBUG_MODE) { Log.w(TAG, " mode set to MODE_NORMAL after phoneState pb"); }
                    mode = AudioSystem.MODE_NORMAL;
                }
            } else {
                status = AudioSystem.AUDIO_STATUS_OK;
            }
        } while (status != AudioSystem.AUDIO_STATUS_OK && !mSetModeDeathHandlers.isEmpty());

        if (status == AudioSystem.AUDIO_STATUS_OK) {
            if (actualMode != AudioSystem.MODE_NORMAL) {
                newModeOwnerPid = getModeOwnerPid();
                if (newModeOwnerPid == 0) {
                    Log.e(TAG, "setMode() different from MODE_NORMAL with empty mode client stack");
                }
            }
            // Note: newModeOwnerPid is always 0 when actualMode is MODE_NORMAL
            mModeLogger.log(
                    new PhoneStateEvent(caller, pid, mode, newModeOwnerPid, actualMode));
            int streamType = getActiveStreamType(AudioManager.USE_DEFAULT_STREAM_TYPE);
            int device = getDeviceForStream(streamType);
            int index = mStreamStates[mStreamVolumeAlias[streamType]].getIndex(device);
            setStreamVolumeInt(mStreamVolumeAlias[streamType], index, device, true, caller);

            updateStreamVolumeAlias(true /*updateVolumes*/, caller);

            // change of mode may require volume to be re-applied on some devices
            updateAbsVolumeMultiModeDevices(oldMode, actualMode);
        }
        return newModeOwnerPid;
    }

    /** @see AudioManager#getMode() */
    public int getMode() {
        return mMode;
    }

    /** cached value read from audiopolicy manager after initialization. */
    private boolean mIsCallScreeningModeSupported = false;

    /** @see AudioManager#isCallScreeningModeSupported() */
    public boolean isCallScreeningModeSupported() {
        return mIsCallScreeningModeSupported;
    }

    /** @see AudioManager#setRttEnabled() */
    @Override
    public void setRttEnabled(boolean rttEnabled) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "MODIFY_PHONE_STATE Permission Denial: setRttEnabled from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        synchronized (mSettingsLock) {
            mRttEnabled = rttEnabled;
            final long identity = Binder.clearCallingIdentity();
            try {
                AudioSystem.setRttEnabled(rttEnabled);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    //==========================================================================================
    // Sound Effects
    //==========================================================================================
    private static final class LoadSoundEffectReply
            implements SoundEffectsHelper.OnEffectsLoadCompleteHandler {
        private static final int SOUND_EFFECTS_LOADING = 1;
        private static final int SOUND_EFFECTS_LOADED = 0;
        private static final int SOUND_EFFECTS_ERROR = -1;
        private static final int SOUND_EFFECTS_LOAD_TIMEOUT_MS = 5000;

        private int mStatus = SOUND_EFFECTS_LOADING;

        @Override
        public synchronized void run(boolean success) {
            mStatus = success ? SOUND_EFFECTS_LOADED : SOUND_EFFECTS_ERROR;
            notify();
        }

        public synchronized boolean waitForLoaded(int attempts) {
            while ((mStatus == SOUND_EFFECTS_LOADING) && (attempts-- > 0)) {
                try {
                    wait(SOUND_EFFECTS_LOAD_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting sound pool loaded.");
                }
            }
            return mStatus == SOUND_EFFECTS_LOADED;
        }
    }

    /** @see AudioManager#playSoundEffect(int) */
    public void playSoundEffect(int effectType) {
        playSoundEffectVolume(effectType, -1.0f);
    }

    /** @see AudioManager#playSoundEffect(int, float) */
    public void playSoundEffectVolume(int effectType, float volume) {
        // do not try to play the sound effect if the system stream is muted
        if (isStreamMutedByRingerOrZenMode(STREAM_SYSTEM)) {
            return;
        }

        if (effectType >= AudioManager.NUM_SOUND_EFFECTS || effectType < 0) {
            Log.w(TAG, "AudioService effectType value " + effectType + " out of range");
            return;
        }
        new MediaMetrics.Item(mAnalyticsId + "playSoundEffectVolume")
                .putInt("effectType", effectType)
                .putDouble("volume", volume)
                .record();

        sendMsg(mAudioHandler, MSG_PLAY_SOUND_EFFECT, SENDMSG_QUEUE,
                effectType, (int) (volume * 1000), null, 0);
    }

    /**
     * Loads samples into the soundpool.
     * This method must be called at first when sound effects are enabled
     */
    public boolean loadSoundEffects() {
        LoadSoundEffectReply reply = new LoadSoundEffectReply();
        sendMsg(mAudioHandler, MSG_LOAD_SOUND_EFFECTS, SENDMSG_QUEUE, 0, 0, reply, 0);

        boolean loaded = reply.waitForLoaded(3 /*attempts*/);
        new MediaMetrics.Item(mAnalyticsId + "loadSoundEffects")
                .putInt("loaded", loaded ? 1 : 0)
                .record();
        return loaded;
    }

    /**
     * Schedule loading samples into the soundpool.
     * This method can be overridden to schedule loading at a later time.
     */
    protected void scheduleLoadSoundEffects() {
        sendMsg(mAudioHandler, MSG_LOAD_SOUND_EFFECTS, SENDMSG_QUEUE, 0, 0, null, 0);
        new MediaMetrics.Item(mAnalyticsId + "scheduleLoadSoundEffects")
                .record();
    }

    /**
     *  Unloads samples from the sound pool.
     *  This method can be called to free some memory when
     *  sound effects are disabled.
     */
    public void unloadSoundEffects() {
        sendMsg(mAudioHandler, MSG_UNLOAD_SOUND_EFFECTS, SENDMSG_QUEUE, 0, 0, null, 0);
        new MediaMetrics.Item(mAnalyticsId + "unloadSoundEffects")
                .record();
    }

    /** @see AudioManager#reloadAudioSettings() */
    public void reloadAudioSettings() {
        readAudioSettings(false /*userSwitch*/);
    }

    private void readAudioSettings(boolean userSwitch) {
        // restore ringer mode, ringer mode affected streams, mute affected streams and vibrate settings
        readPersistedSettings();
        readUserRestrictions();

        // restore volume settings
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            VolumeStreamState streamState = mStreamStates[streamType];

            if (userSwitch && mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC) {
                continue;
            }

            streamState.readSettings();
            synchronized (VolumeStreamState.class) {
                // unmute stream that was muted but is not affect by mute anymore
                if (streamState.mIsMuted && ((!isStreamAffectedByMute(streamType) &&
                        !isStreamMutedByRingerOrZenMode(streamType)) || mUseFixedVolume)) {
                    streamState.mIsMuted = false;
                }
            }
        }

        // apply new ringer mode before checking volume for alias streams so that streams
        // muted by ringer mode have the correct volume
        setRingerModeInt(getRingerModeInternal(), false);

        checkAllFixedVolumeDevices();
        checkAllAliasStreamVolumes();
        checkMuteAffectedStreams();

        synchronized (mSafeMediaVolumeStateLock) {
            mMusicActiveMs = MathUtils.constrain(Settings.Secure.getIntForUser(mContentResolver,
                    Settings.Secure.UNSAFE_VOLUME_MUSIC_ACTIVE_MS, 0, UserHandle.USER_CURRENT),
                    0, UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX);
            if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE) {
                enforceSafeMediaVolume(TAG);
            }
        }

        readVolumeGroupsSettings();
    }

    /** @see AudioManager#setSpeakerphoneOn(boolean) */
    public void setSpeakerphoneOn(boolean on){
        if (!checkAudioSettingsPermission("setSpeakerphoneOn()")) {
            return;
        }

        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            synchronized (mSetModeDeathHandlers) {
                for (SetModeDeathHandler h : mSetModeDeathHandlers) {
                    if (h.getMode() == AudioSystem.MODE_IN_CALL) {
                        Log.w(TAG, "getMode is call, Permission Denial: setSpeakerphoneOn from pid="
                                + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
                        return;
                    }
                }
            }
        }

        // for logging only
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final String eventSource = new StringBuilder("setSpeakerphoneOn(").append(on)
                .append(") from u/pid:").append(uid).append("/")
                .append(pid).toString();
        final boolean stateChanged = mDeviceBroker.setSpeakerphoneOn(on, eventSource);
        new MediaMetrics.Item(mAnalyticsId + "setSpeakerphoneOn")
                .setUid(uid)
                .setPid(pid)
                .putInt("on", on ? 1 : 0)
                .putInt("stateChanged", stateChanged ? 1 : 0)
                .record();

        if (stateChanged) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mContext.sendBroadcastAsUser(
                        new Intent(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
                                .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /** @see AudioManager#isSpeakerphoneOn() */
    public boolean isSpeakerphoneOn() {
        return mDeviceBroker.isSpeakerphoneOn();
    }

    /** @see AudioManager#setBluetoothScoOn(boolean) */
    public void setBluetoothScoOn(boolean on) {
        if (!checkAudioSettingsPermission("setBluetoothScoOn()")) {
            return;
        }

        // Only enable calls from system components
        if (UserHandle.getCallingAppId() >= FIRST_APPLICATION_UID) {
            mDeviceBroker.setBluetoothScoOnByApp(on);
            return;
        }

        // for logging only
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final String eventSource = new StringBuilder("setBluetoothScoOn(").append(on)
                .append(") from u/pid:").append(uid).append("/").append(pid).toString();

        //bt sco
        new MediaMetrics.Item(mAnalyticsId + "setBluetoothScoOn")
                .setUid(uid)
                .setPid(pid)
                .putInt("on", on ? 1 : 0)
                .record();

        mDeviceBroker.setBluetoothScoOn(on, eventSource);
    }

    /** @see AudioManager#isBluetoothScoOn()
     * Note that it doesn't report internal state, but state seen by apps (which may have
     * called setBluetoothScoOn() */
    public boolean isBluetoothScoOn() {
        return mDeviceBroker.isBluetoothScoOnForApp();
    }

    // TODO investigate internal users due to deprecation of SDK API
    /** @see AudioManager#setBluetoothA2dpOn(boolean) */
    public void setBluetoothA2dpOn(boolean on) {
        // for logging only
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final String eventSource = new StringBuilder("setBluetoothA2dpOn(").append(on)
                .append(") from u/pid:").append(uid).append("/")
                .append(pid).toString();

        new MediaMetrics.Item(mAnalyticsId + "setBluetoothA2dpOn")
                .setUid(uid)
                .setPid(pid)
                .putInt("on", on ? 1 : 0)
                .record();

        mDeviceBroker.setBluetoothA2dpOn_Async(on, eventSource);
    }

    /** @see AudioManager#isBluetoothA2dpOn() */
    public boolean isBluetoothA2dpOn() {
        return mDeviceBroker.isBluetoothA2dpOn();
    }

    /** @see AudioManager#startBluetoothSco() */
    public void startBluetoothSco(IBinder cb, int targetSdkVersion) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int scoAudioMode =
                (targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR2) ?
                        BtHelper.SCO_MODE_VIRTUAL_CALL : BtHelper.SCO_MODE_UNDEFINED;
        final String eventSource = new StringBuilder("startBluetoothSco()")
                .append(") from u/pid:").append(uid).append("/")
                .append(pid).toString();

        new MediaMetrics.Item(mAnalyticsId + "startBluetoothSco")
                .setUid(uid)
                .setPid(pid)
                .putInt("scoAudioMode", scoAudioMode)
                .record();
        startBluetoothScoInt(cb, scoAudioMode, eventSource);

    }

    /** @see AudioManager#startBluetoothScoVirtualCall() */
    public void startBluetoothScoVirtualCall(IBinder cb) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final String eventSource = new StringBuilder("startBluetoothScoVirtualCall()")
                .append(") from u/pid:").append(uid).append("/")
                .append(pid).toString();

        new MediaMetrics.Item(mAnalyticsId + "startBluetoothScoVirtualCall")
                .setUid(uid)
                .setPid(pid)
                .record();
        startBluetoothScoInt(cb, BtHelper.SCO_MODE_VIRTUAL_CALL, eventSource);
    }

    void startBluetoothScoInt(IBinder cb, int scoAudioMode, @NonNull String eventSource) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mAnalyticsId + "startBluetoothScoInt")
                .putInt("scoAudioMode", scoAudioMode);

        if (!checkAudioSettingsPermission("startBluetoothSco()") ||
                !mSystemReady) {
            mmi.putString(mAnalyticsPropEarlyReturn, "permission or systemReady").record();
            return;
        }
        synchronized (mDeviceBroker.mSetModeLock) {
            mDeviceBroker.startBluetoothScoForClient_Sync(cb, scoAudioMode, eventSource);
        }
        mmi.record();
    }

    /** @see AudioManager#stopBluetoothSco() */
    public void stopBluetoothSco(IBinder cb){
        if (!checkAudioSettingsPermission("stopBluetoothSco()") ||
                !mSystemReady) {
            return;
        }
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final String eventSource =  new StringBuilder("stopBluetoothSco()")
                .append(") from u/pid:").append(uid).append("/")
                .append(pid).toString();
        synchronized (mDeviceBroker.mSetModeLock) {
            mDeviceBroker.stopBluetoothScoForClient_Sync(cb, eventSource);
        }
        new MediaMetrics.Item(mAnalyticsId + "stopBluetoothSco")
                .setUid(uid)
                .setPid(pid)
                .record();
    }


    /*package*/ ContentResolver getContentResolver() {
        return mContentResolver;
    }

    private void onCheckMusicActive(String caller) {
        synchronized (mSafeMediaVolumeStateLock) {
            if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_INACTIVE) {
                int device = getDeviceForStream(AudioSystem.STREAM_MUSIC);

                if (mSafeMediaVolumeDevices.contains(device)) {
                    sendMsg(mAudioHandler,
                            MSG_CHECK_MUSIC_ACTIVE,
                            SENDMSG_REPLACE,
                            0,
                            0,
                            caller,
                            MUSIC_ACTIVE_POLL_PERIOD_MS);
                    int index = mStreamStates[AudioSystem.STREAM_MUSIC].getIndex(device);
                    if (AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0)
                            && (index > safeMediaVolumeIndex(device))) {
                        // Approximate cumulative active music time
                        mMusicActiveMs += MUSIC_ACTIVE_POLL_PERIOD_MS;
                        if (mMusicActiveMs > UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX) {
                            setSafeMediaVolumeEnabled(true, caller);
                            mMusicActiveMs = 0;
                        }
                        saveMusicActiveMs();
                    }
                }
            }
        }
    }

    private void saveMusicActiveMs() {
        mAudioHandler.obtainMessage(MSG_PERSIST_MUSIC_ACTIVE_MS, mMusicActiveMs, 0).sendToTarget();
    }

    private int getSafeUsbMediaVolumeIndex() {
        // determine UI volume index corresponding to the wanted safe gain in dBFS
        int min = MIN_STREAM_VOLUME[AudioSystem.STREAM_MUSIC];
        int max = MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC];

        mSafeUsbMediaVolumeDbfs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_safe_media_volume_usb_mB) / 100.0f;

        while (Math.abs(max - min) > 1) {
            int index = (max + min) / 2;
            float gainDB = AudioSystem.getStreamVolumeDB(
                    AudioSystem.STREAM_MUSIC, index, AudioSystem.DEVICE_OUT_USB_HEADSET);
            if (Float.isNaN(gainDB)) {
                //keep last min in case of read error
                break;
            } else if (gainDB == mSafeUsbMediaVolumeDbfs) {
                min = index;
                break;
            } else if (gainDB < mSafeUsbMediaVolumeDbfs) {
                min = index;
            } else {
                max = index;
            }
        }
        return min * 10;
    }

    private void onConfigureSafeVolume(boolean force, String caller) {
        synchronized (mSafeMediaVolumeStateLock) {
            int mcc = mContext.getResources().getConfiguration().mcc;
            if ((mMcc != mcc) || ((mMcc == 0) && force)) {
                mSafeMediaVolumeIndex = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_safe_media_volume_index) * 10;

                mSafeUsbMediaVolumeIndex = getSafeUsbMediaVolumeIndex();

                boolean safeMediaVolumeEnabled =
                        SystemProperties.getBoolean("audio.safemedia.force", false)
                        || mContext.getResources().getBoolean(
                                com.android.internal.R.bool.config_safe_media_volume_enabled);

                boolean safeMediaVolumeBypass =
                        SystemProperties.getBoolean("audio.safemedia.bypass", false);

                // The persisted state is either "disabled" or "active": this is the state applied
                // next time we boot and cannot be "inactive"
                int persistedState;
                if (safeMediaVolumeEnabled && !safeMediaVolumeBypass) {
                    persistedState = SAFE_MEDIA_VOLUME_ACTIVE;
                    // The state can already be "inactive" here if the user has forced it before
                    // the 30 seconds timeout for forced configuration. In this case we don't reset
                    // it to "active".
                    if (mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_INACTIVE) {
                        if (mMusicActiveMs == 0) {
                            mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_ACTIVE;
                            enforceSafeMediaVolume(caller);
                        } else {
                            // We have existing playback time recorded, already confirmed.
                            mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_INACTIVE;
                        }
                    }
                } else {
                    persistedState = SAFE_MEDIA_VOLUME_DISABLED;
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_DISABLED;
                }
                mMcc = mcc;
                sendMsg(mAudioHandler,
                        MSG_PERSIST_SAFE_VOLUME_STATE,
                        SENDMSG_QUEUE,
                        persistedState,
                        0,
                        null,
                        0);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal methods
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Checks if the adjustment should change ringer mode instead of just
     * adjusting volume. If so, this will set the proper ringer mode and volume
     * indices on the stream states.
     */
    private int checkForRingerModeChange(int oldIndex, int direction, int step, boolean isMuted,
            String caller, int flags) {
        int result = FLAG_ADJUST_VOLUME;
        if (isPlatformTelevision() || mIsSingleVolume) {
            return result;
        }

        int ringerMode = getRingerModeInternal();

        switch (ringerMode) {
        case RINGER_MODE_NORMAL:
            if (direction == AudioManager.ADJUST_LOWER) {
                if (mHasVibrator) {
                    // "step" is the delta in internal index units corresponding to a
                    // change of 1 in UI index units.
                    // Because of rounding when rescaling from one stream index range to its alias
                    // index range, we cannot simply test oldIndex == step:
                    //   (step <= oldIndex < 2 * step) is equivalent to: (old UI index == 1)
                    if (step <= oldIndex && oldIndex < 2 * step) {
                        ringerMode = RINGER_MODE_VIBRATE;
                        mLoweredFromNormalToVibrateTime = SystemClock.uptimeMillis();
                    }
                } else {
                    if (oldIndex == step && mVolumePolicy.volumeDownToEnterSilent) {
                        ringerMode = RINGER_MODE_SILENT;
                    }
                }
            } else if (mIsSingleVolume && (direction == AudioManager.ADJUST_TOGGLE_MUTE
                    || direction == AudioManager.ADJUST_MUTE)) {
                if (mHasVibrator) {
                    ringerMode = RINGER_MODE_VIBRATE;
                } else {
                    ringerMode = RINGER_MODE_SILENT;
                }
                // Setting the ringer mode will toggle mute
                result &= ~FLAG_ADJUST_VOLUME;
            }
            break;
        case RINGER_MODE_VIBRATE:
            if (!mHasVibrator) {
                Log.e(TAG, "checkForRingerModeChange() current ringer mode is vibrate" +
                        "but no vibrator is present");
                break;
            }
            if ((direction == AudioManager.ADJUST_LOWER)) {
                // This is the case we were muted with the volume turned up
                if (mIsSingleVolume && oldIndex >= 2 * step && isMuted) {
                    ringerMode = RINGER_MODE_NORMAL;
                } else if (mPrevVolDirection != AudioManager.ADJUST_LOWER) {
                    if (mVolumePolicy.volumeDownToEnterSilent) {
                        final long diff = SystemClock.uptimeMillis()
                                - mLoweredFromNormalToVibrateTime;
                        if (diff > mVolumePolicy.vibrateToSilentDebounce
                                && mRingerModeDelegate.canVolumeDownEnterSilent()) {
                            ringerMode = RINGER_MODE_SILENT;
                        }
                    } else {
                        result |= AudioManager.FLAG_SHOW_VIBRATE_HINT;
                    }
                }
            } else if (direction == AudioManager.ADJUST_RAISE
                    || direction == AudioManager.ADJUST_TOGGLE_MUTE
                    || direction == AudioManager.ADJUST_UNMUTE) {
                ringerMode = RINGER_MODE_NORMAL;
            }
            result &= ~FLAG_ADJUST_VOLUME;
            break;
        case RINGER_MODE_SILENT:
            if (mIsSingleVolume && direction == AudioManager.ADJUST_LOWER && oldIndex >= 2 * step && isMuted) {
                // This is the case we were muted with the volume turned up
                ringerMode = RINGER_MODE_NORMAL;
            } else if (direction == AudioManager.ADJUST_RAISE
                    || direction == AudioManager.ADJUST_TOGGLE_MUTE
                    || direction == AudioManager.ADJUST_UNMUTE) {
                if (!mVolumePolicy.volumeUpToExitSilent) {
                    result |= AudioManager.FLAG_SHOW_SILENT_HINT;
                } else {
                  if (mHasVibrator && direction == AudioManager.ADJUST_RAISE) {
                      ringerMode = RINGER_MODE_VIBRATE;
                  } else {
                      // If we don't have a vibrator or they were toggling mute
                      // go straight back to normal.
                      ringerMode = RINGER_MODE_NORMAL;
                  }
                }
            }
            result &= ~FLAG_ADJUST_VOLUME;
            break;
        default:
            Log.e(TAG, "checkForRingerModeChange() wrong ringer mode: "+ringerMode);
            break;
        }

        if (isAndroidNPlus(caller) && wouldToggleZenMode(ringerMode)
                && !mNm.isNotificationPolicyAccessGrantedForPackage(caller)
                && (flags & AudioManager.FLAG_FROM_KEY) == 0) {
            throw new SecurityException("Not allowed to change Do Not Disturb state");
        }

        setRingerMode(ringerMode, TAG + ".checkForRingerModeChange", false /*external*/);

        mPrevVolDirection = direction;

        return result;
    }

    @Override
    public boolean isStreamAffectedByRingerMode(int streamType) {
        return (mRingerModeAffectedStreams & (1 << streamType)) != 0;
    }

    private boolean shouldZenMuteStream(int streamType) {
        if (mNm.getZenMode() != Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            return false;
        }

        NotificationManager.Policy zenPolicy = mNm.getConsolidatedNotificationPolicy();
        final boolean muteAlarms = (zenPolicy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0;
        final boolean muteMedia = (zenPolicy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) == 0;
        final boolean muteSystem = (zenPolicy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM) == 0;
        final boolean muteNotificationAndRing = ZenModeConfig
                .areAllPriorityOnlyRingerSoundsMuted(zenPolicy);
        return muteAlarms && isAlarm(streamType)
                || muteMedia && isMedia(streamType)
                || muteSystem && isSystem(streamType)
                || muteNotificationAndRing && isNotificationOrRinger(streamType);
    }

    private boolean isStreamMutedByRingerOrZenMode(int streamType) {
        return (mRingerAndZenModeMutedStreams & (1 << streamType)) != 0;
    }

    /**
     * Notifications, ringer and system sounds are controlled by the ringer:
     * {@link ZenModeHelper.RingerModeDelegate#getRingerModeAffectedStreams(int)} but can
     * also be muted by DND based on the DND mode:
     * DND total silence: media and alarms streams can be muted by DND
     * DND alarms only: no streams additionally controlled by DND
     * DND priority only: alarms, media, system, ringer and notification streams can be muted by
     * DND.  The current applied zenPolicy determines which streams will be muted by DND.
     * @return true if changed, else false
     */
    private boolean updateZenModeAffectedStreams() {
        if (!mSystemReady) {
            return false;
        }

        int zenModeAffectedStreams = 0;
        final int zenMode = mNm.getZenMode();

        if (zenMode == Settings.Global.ZEN_MODE_NO_INTERRUPTIONS) {
            zenModeAffectedStreams |= 1 << AudioManager.STREAM_ALARM;
            zenModeAffectedStreams |= 1 << AudioManager.STREAM_MUSIC;
        } else if (zenMode == Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            NotificationManager.Policy zenPolicy = mNm.getConsolidatedNotificationPolicy();
            if ((zenPolicy.priorityCategories
                    & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0) {
                zenModeAffectedStreams |= 1 << AudioManager.STREAM_ALARM;
            }

            if ((zenPolicy.priorityCategories
                    & NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA) == 0) {
                zenModeAffectedStreams |= 1 << AudioManager.STREAM_MUSIC;
            }

            // even if zen isn't muting the system stream, the ringer mode can still mute
            // the system stream
            if ((zenPolicy.priorityCategories
                    & NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM) == 0) {
                zenModeAffectedStreams |= 1 << AudioManager.STREAM_SYSTEM;
            }

            if (ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(zenPolicy)) {
                zenModeAffectedStreams |= 1 << AudioManager.STREAM_NOTIFICATION;
                zenModeAffectedStreams |= 1 << AudioManager.STREAM_RING;
            }
        }

        if (mZenModeAffectedStreams != zenModeAffectedStreams) {
            mZenModeAffectedStreams = zenModeAffectedStreams;
            return true;
        }

        return false;
    }

    @GuardedBy("mSettingsLock")
    private boolean updateRingerAndZenModeAffectedStreams() {
        boolean updatedZenModeAffectedStreams = updateZenModeAffectedStreams();
        int ringerModeAffectedStreams = Settings.System.getIntForUser(mContentResolver,
                Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                ((1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_NOTIFICATION)|
                 (1 << AudioSystem.STREAM_SYSTEM)|(1 << AudioSystem.STREAM_SYSTEM_ENFORCED)),
                 UserHandle.USER_CURRENT);

        if (mIsSingleVolume) {
            ringerModeAffectedStreams = 0;
        } else if (mRingerModeDelegate != null) {
            ringerModeAffectedStreams = mRingerModeDelegate
                    .getRingerModeAffectedStreams(ringerModeAffectedStreams);
        }
        if (mCameraSoundForced) {
            ringerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
        } else {
            ringerModeAffectedStreams |= (1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
        }
        if (mStreamVolumeAlias[AudioSystem.STREAM_DTMF] == AudioSystem.STREAM_RING) {
            ringerModeAffectedStreams |= (1 << AudioSystem.STREAM_DTMF);
        } else {
            ringerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_DTMF);
        }

        if (ringerModeAffectedStreams != mRingerModeAffectedStreams) {
            Settings.System.putIntForUser(mContentResolver,
                    Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                    ringerModeAffectedStreams,
                    UserHandle.USER_CURRENT);
            mRingerModeAffectedStreams = ringerModeAffectedStreams;
            return true;
        }
        return updatedZenModeAffectedStreams;
    }

    @Override
    public boolean isStreamAffectedByMute(int streamType) {
        return (mMuteAffectedStreams & (1 << streamType)) != 0;
    }

    private void ensureValidDirection(int direction) {
        switch (direction) {
            case AudioManager.ADJUST_LOWER:
            case AudioManager.ADJUST_RAISE:
            case AudioManager.ADJUST_SAME:
            case AudioManager.ADJUST_MUTE:
            case AudioManager.ADJUST_UNMUTE:
            case AudioManager.ADJUST_TOGGLE_MUTE:
                break;
            default:
                throw new IllegalArgumentException("Bad direction " + direction);
        }
    }

    private void ensureValidStreamType(int streamType) {
        if (streamType < 0 || streamType >= mStreamStates.length) {
            throw new IllegalArgumentException("Bad stream type " + streamType);
        }
    }

    private boolean isMuteAdjust(int adjust) {
        return adjust == AudioManager.ADJUST_MUTE || adjust == AudioManager.ADJUST_UNMUTE
                || adjust == AudioManager.ADJUST_TOGGLE_MUTE;
    }

    /** only public for mocking/spying, do not call outside of AudioService */
    @VisibleForTesting
    public boolean isInCommunication() {
        boolean IsInCall = false;

        TelecomManager telecomManager =
                (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);

        final long ident = Binder.clearCallingIdentity();
        IsInCall = telecomManager.isInCall();
        Binder.restoreCallingIdentity(ident);

        return (IsInCall || getMode() == AudioManager.MODE_IN_COMMUNICATION ||
                getMode() == AudioManager.MODE_IN_CALL);
    }

    /**
     * For code clarity for getActiveStreamType(int)
     * @param delay_ms max time since last stream activity to consider
     * @return true if stream is active in streams handled by AudioFlinger now or
     *     in the last "delay_ms" ms.
     */
    private boolean wasStreamActiveRecently(int stream, int delay_ms) {
        return AudioSystem.isStreamActive(stream, delay_ms)
                || AudioSystem.isStreamActiveRemotely(stream, delay_ms);
    }

    private int getActiveStreamType(int suggestedStreamType) {
        if (mIsSingleVolume
                && suggestedStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
            return AudioSystem.STREAM_MUSIC;
        }

        switch (mPlatformType) {
        case AudioSystem.PLATFORM_VOICE:
            if (isInCommunication()) {
                if (AudioSystem.getForceUse(AudioSystem.FOR_COMMUNICATION)
                        == AudioSystem.FORCE_BT_SCO) {
                    // Log.v(TAG, "getActiveStreamType: Forcing STREAM_BLUETOOTH_SCO...");
                    return AudioSystem.STREAM_BLUETOOTH_SCO;
                } else {
                    // Log.v(TAG, "getActiveStreamType: Forcing STREAM_VOICE_CALL...");
                    return AudioSystem.STREAM_VOICE_CALL;
                }
            } else if (suggestedStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                if (wasStreamActiveRecently(AudioSystem.STREAM_RING, sStreamOverrideDelayMs)) {
                    if (DEBUG_VOL)
                        Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING stream active");
                    return AudioSystem.STREAM_RING;
                } else if (wasStreamActiveRecently(
                        AudioSystem.STREAM_NOTIFICATION, sStreamOverrideDelayMs)) {
                    if (DEBUG_VOL)
                        Log.v(TAG, "getActiveStreamType: Forcing STREAM_NOTIFICATION stream active");
                    return AudioSystem.STREAM_NOTIFICATION;
                } else {
                    if (DEBUG_VOL) {
                        Log.v(TAG, "getActiveStreamType: Forcing DEFAULT_VOL_STREAM_NO_PLAYBACK("
                                + DEFAULT_VOL_STREAM_NO_PLAYBACK + ") b/c default");
                    }
                    return DEFAULT_VOL_STREAM_NO_PLAYBACK;
                }
            } else if (
                    wasStreamActiveRecently(AudioSystem.STREAM_NOTIFICATION, sStreamOverrideDelayMs)) {
                if (DEBUG_VOL)
                    Log.v(TAG, "getActiveStreamType: Forcing STREAM_NOTIFICATION stream active");
                return AudioSystem.STREAM_NOTIFICATION;
            } else if (wasStreamActiveRecently(AudioSystem.STREAM_RING, sStreamOverrideDelayMs)) {
                if (DEBUG_VOL)
                    Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING stream active");
                return AudioSystem.STREAM_RING;
            }
        default:
            if (isInCommunication()) {
                if (AudioSystem.getForceUse(AudioSystem.FOR_COMMUNICATION)
                        == AudioSystem.FORCE_BT_SCO) {
                    if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_BLUETOOTH_SCO");
                    return AudioSystem.STREAM_BLUETOOTH_SCO;
                } else {
                    if (DEBUG_VOL)  Log.v(TAG, "getActiveStreamType: Forcing STREAM_VOICE_CALL");
                    return AudioSystem.STREAM_VOICE_CALL;
                }
            } else if (AudioSystem.isStreamActive(
                    AudioSystem.STREAM_NOTIFICATION, sStreamOverrideDelayMs)) {
                if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_NOTIFICATION");
                return AudioSystem.STREAM_NOTIFICATION;
            } else if (AudioSystem.isStreamActive(
                    AudioSystem.STREAM_RING, sStreamOverrideDelayMs)) {
                if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING");
                return AudioSystem.STREAM_RING;
            } else if (suggestedStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                if (AudioSystem.isStreamActive(
                        AudioSystem.STREAM_NOTIFICATION, sStreamOverrideDelayMs)) {
                    if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_NOTIFICATION");
                    return AudioSystem.STREAM_NOTIFICATION;
                } else if (AudioSystem.isStreamActive(
                        AudioSystem.STREAM_RING, sStreamOverrideDelayMs)) {
                    if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING");
                    return AudioSystem.STREAM_RING;
                } else {
                    if (DEBUG_VOL) {
                        Log.v(TAG, "getActiveStreamType: Forcing DEFAULT_VOL_STREAM_NO_PLAYBACK("
                                + DEFAULT_VOL_STREAM_NO_PLAYBACK + ") b/c default");
                    }
                    return DEFAULT_VOL_STREAM_NO_PLAYBACK;
                }
            }
            break;
        }
        if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Returning suggested type "
                + suggestedStreamType);
        return suggestedStreamType;
    }

    private void broadcastRingerMode(String action, int ringerMode) {
        // Send sticky broadcast
        Intent broadcast = new Intent(action);
        broadcast.putExtra(AudioManager.EXTRA_RINGER_MODE, ringerMode);
        broadcast.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        sendStickyBroadcastToAll(broadcast);
    }

    private void broadcastVibrateSetting(int vibrateType) {
        // Send broadcast
        if (mActivityManagerInternal.isSystemReady()) {
            Intent broadcast = new Intent(AudioManager.VIBRATE_SETTING_CHANGED_ACTION);
            broadcast.putExtra(AudioManager.EXTRA_VIBRATE_TYPE, vibrateType);
            broadcast.putExtra(AudioManager.EXTRA_VIBRATE_SETTING, getVibrateSetting(vibrateType));
            sendBroadcastToAll(broadcast);
        }
    }

    // Message helper methods
    /**
     * Queue a message on the given handler's message queue, after acquiring the service wake lock.
     * Note that the wake lock needs to be released after the message has been handled.
     */
    private void queueMsgUnderWakeLock(Handler handler, int msg,
            int arg1, int arg2, Object obj, int delay) {
        final long ident = Binder.clearCallingIdentity();
        // Always acquire the wake lock as AudioService because it is released by the
        // message handler.
        mAudioEventWakeLock.acquire();
        Binder.restoreCallingIdentity(ident);
        sendMsg(handler, msg, SENDMSG_QUEUE, arg1, arg2, obj, delay);
    }

    private static void sendMsg(Handler handler, int msg,
            int existingMsgPolicy, int arg1, int arg2, Object obj, int delay) {
        if (existingMsgPolicy == SENDMSG_REPLACE) {
            handler.removeMessages(msg);
        } else if (existingMsgPolicy == SENDMSG_NOOP && handler.hasMessages(msg)) {
            return;
        }

        final long time = SystemClock.uptimeMillis() + delay;
        handler.sendMessageAtTime(handler.obtainMessage(msg, arg1, arg2, obj), time);
    }

    boolean checkAudioSettingsPermission(String method) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        String msg = "Audio Settings Permission Denial: " + method + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid();
        Log.w(TAG, msg);
        return false;
    }

    /** only public for mocking/spying, do not call outside of AudioService */
    @VisibleForTesting
    public int getDeviceForStream(int stream) {
        int device = getDevicesForStream(stream);
        if ((device & (device - 1)) != 0) {
            // Multiple device selection is either:
            //  - speaker + one other device: give priority to speaker in this case.
            //  - one A2DP device + another device: happens with duplicated output. In this case
            // retain the device on the A2DP output as the other must not correspond to an active
            // selection if not the speaker.
            //  - HDMI-CEC system audio mode only output: give priority to available item in order.
            // FIXME: Haven't applied audio device type refactor to this API
            //  as it is going to be deprecated.
            if ((device & AudioSystem.DEVICE_OUT_SPEAKER) != 0) {
                device = AudioSystem.DEVICE_OUT_SPEAKER;
            } else if ((device & AudioSystem.DEVICE_OUT_HDMI_ARC) != 0) {
                device = AudioSystem.DEVICE_OUT_HDMI_ARC;
            } else if ((device & AudioSystem.DEVICE_OUT_SPDIF) != 0) {
                device = AudioSystem.DEVICE_OUT_SPDIF;
            } else if ((device & AudioSystem.DEVICE_OUT_AUX_LINE) != 0) {
                device = AudioSystem.DEVICE_OUT_AUX_LINE;
            } else {
                for (int deviceType : AudioSystem.DEVICE_OUT_ALL_A2DP_SET) {
                    if ((deviceType & device) == deviceType) {
                        return deviceType;
                    }
                }
            }
        }
        return device;
    }

    private int getDevicesForStream(int stream) {
        return getDevicesForStream(stream, true /*checkOthers*/);
    }

    private int getDevicesForStream(int stream, boolean checkOthers) {
        ensureValidStreamType(stream);
        synchronized (VolumeStreamState.class) {
            return mStreamStates[stream].observeDevicesForStream_syncVSS(checkOthers);
        }
    }

    private void observeDevicesForStreams(int skipStream) {
        synchronized (VolumeStreamState.class) {
            for (int stream = 0; stream < mStreamStates.length; stream++) {
                if (stream != skipStream) {
                    mStreamStates[stream].observeDevicesForStream_syncVSS(false /*checkOthers*/);
                }
            }
        }
    }

    /** only public for mocking/spying, do not call outside of AudioService */
    @VisibleForTesting
    public void postObserveDevicesForAllStreams() {
        sendMsg(mAudioHandler,
                MSG_OBSERVE_DEVICES_FOR_ALL_STREAMS,
                SENDMSG_QUEUE, 0 /*arg1*/, 0 /*arg2*/, null /*obj*/,
                0 /*delay*/);
    }

    private void onObserveDevicesForAllStreams() {
        observeDevicesForStreams(-1);
    }

    /*package*/ static final int CONNECTION_STATE_DISCONNECTED = 0;
    /*package*/ static final int CONNECTION_STATE_CONNECTED = 1;
    /**
     * The states that can be used with AudioService.setWiredDeviceConnectionState()
     * Attention: those values differ from those in BluetoothProfile, follow annotations to
     * distinguish between @ConnectionState and @BtProfileConnectionState
     */
    @IntDef({
            CONNECTION_STATE_DISCONNECTED,
            CONNECTION_STATE_CONNECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionState {}

    /**
     * see AudioManager.setWiredDeviceConnectionState()
     */
    public void setWiredDeviceConnectionState(int type,
            @ConnectionState int state, String address, String name,
            String caller) {
        enforceModifyAudioRoutingPermission();
        if (state != CONNECTION_STATE_CONNECTED
                && state != CONNECTION_STATE_DISCONNECTED) {
            throw new IllegalArgumentException("Invalid state " + state);
        }
        new MediaMetrics.Item(mAnalyticsId + "setWiredDeviceConnectionState")
                .putInt("type", type)
                .putInt("state", state)
                .putString("address", address)
                .putString("name", name)
                .putString("caller", caller)
                .record();
        mDeviceBroker.setWiredDeviceConnectionState(type, state, address, name, caller);
    }

    /**
     * @hide
     * The states that can be used with AudioService.setBluetoothHearingAidDeviceConnectionState()
     * and AudioService.setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent()
     */
    @IntDef({
            BluetoothProfile.STATE_DISCONNECTED,
            BluetoothProfile.STATE_CONNECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BtProfileConnectionState {}

    /**
     * See AudioManager.setBluetoothHearingAidDeviceConnectionState()
     */
    public void setBluetoothHearingAidDeviceConnectionState(
            @NonNull BluetoothDevice device, @BtProfileConnectionState int state,
            boolean suppressNoisyIntent, int musicDevice)
    {
        if (device == null) {
            throw new IllegalArgumentException("Illegal null device");
        }
        if (state != BluetoothProfile.STATE_CONNECTED
                && state != BluetoothProfile.STATE_DISCONNECTED) {
            throw new IllegalArgumentException("Illegal BluetoothProfile state for device "
                    + " (dis)connection, got " + state);
        }
        if (state == BluetoothProfile.STATE_CONNECTED) {
            mPlaybackMonitor.registerPlaybackCallback(mVoiceActivityMonitor, true);
        } else {
            mPlaybackMonitor.unregisterPlaybackCallback(mVoiceActivityMonitor);
        }
        mDeviceBroker.postBluetoothHearingAidDeviceConnectionState(
                device, state, suppressNoisyIntent, musicDevice, "AudioService");
    }

    /**
     * See AudioManager.setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent()
     */
    public void setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(
            @NonNull BluetoothDevice device, @AudioService.BtProfileConnectionState int state,
            int profile, boolean suppressNoisyIntent, int a2dpVolume) {
        if (device == null) {
            throw new IllegalArgumentException("Illegal null device");
        }
        if (state != BluetoothProfile.STATE_CONNECTED
                && state != BluetoothProfile.STATE_DISCONNECTED) {
            throw new IllegalArgumentException("Illegal BluetoothProfile state for device "
                    + " (dis)connection, got " + state);
        }
        mDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(device, state,
                profile, suppressNoisyIntent, a2dpVolume);
    }

    /**
     * See AudioManager.handleBluetoothA2dpDeviceConfigChange()
     * @param device
     */
    public void handleBluetoothA2dpDeviceConfigChange(BluetoothDevice device)
    {
        if (device == null) {
            throw new IllegalArgumentException("Illegal null device");
        }
        mDeviceBroker.postBluetoothA2dpDeviceConfigChange(device);
    }

    private static final Set<Integer> DEVICE_MEDIA_UNMUTED_ON_PLUG_SET;
    static {
        DEVICE_MEDIA_UNMUTED_ON_PLUG_SET = new HashSet<>();
        DEVICE_MEDIA_UNMUTED_ON_PLUG_SET.add(AudioSystem.DEVICE_OUT_WIRED_HEADSET);
        DEVICE_MEDIA_UNMUTED_ON_PLUG_SET.add(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
        DEVICE_MEDIA_UNMUTED_ON_PLUG_SET.add(AudioSystem.DEVICE_OUT_LINE);
        DEVICE_MEDIA_UNMUTED_ON_PLUG_SET.addAll(AudioSystem.DEVICE_OUT_ALL_A2DP_SET);
        DEVICE_MEDIA_UNMUTED_ON_PLUG_SET.addAll(AudioSystem.DEVICE_OUT_ALL_USB_SET);
        DEVICE_MEDIA_UNMUTED_ON_PLUG_SET.add(AudioSystem.DEVICE_OUT_HDMI);
    }

    /** only public for mocking/spying, do not call outside of AudioService */
    @VisibleForTesting
    public void postAccessoryPlugMediaUnmute(int newDevice) {
        sendMsg(mAudioHandler, MSG_ACCESSORY_PLUG_MEDIA_UNMUTE, SENDMSG_QUEUE,
                newDevice, 0, null, 0);
    }

    private void onAccessoryPlugMediaUnmute(int newDevice) {
        if (DEBUG_VOL) {
            Log.i(TAG, String.format("onAccessoryPlugMediaUnmute newDevice=%d [%s]",
                    newDevice, AudioSystem.getOutputDeviceName(newDevice)));
        }

        if (mNm.getZenMode() != Settings.Global.ZEN_MODE_NO_INTERRUPTIONS
                && !isStreamMutedByRingerOrZenMode(AudioSystem.STREAM_MUSIC)
                && DEVICE_MEDIA_UNMUTED_ON_PLUG_SET.contains(newDevice)
                && mStreamStates[AudioSystem.STREAM_MUSIC].mIsMuted
                && mStreamStates[AudioSystem.STREAM_MUSIC].getIndex(newDevice) != 0
                && (newDevice & AudioSystem.getDevicesForStream(AudioSystem.STREAM_MUSIC)) != 0) {
            if (DEBUG_VOL) {
                Log.i(TAG, String.format("onAccessoryPlugMediaUnmute unmuting device=%d [%s]",
                        newDevice, AudioSystem.getOutputDeviceName(newDevice)));
            }
            mStreamStates[AudioSystem.STREAM_MUSIC].mute(false);
        }
    }

    /**
     * See AudioManager.hasHapticChannels(Uri).
     */
    public boolean hasHapticChannels(Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(mContext, uri, null);
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

    ///////////////////////////////////////////////////////////////////////////
    // Inner classes
    ///////////////////////////////////////////////////////////////////////////
    /**
     * Key is the AudioManager VolumeGroupId
     * Value is the VolumeGroupState
     */
    private static final SparseArray<VolumeGroupState> sVolumeGroupStates = new SparseArray<>();

    private void initVolumeGroupStates() {
        for (final AudioVolumeGroup avg : getAudioVolumeGroups()) {
            try {
                // if no valid attributes, this volume group is not controllable, throw exception
                ensureValidAttributes(avg);
            } catch (IllegalArgumentException e) {
                // Volume Groups without attributes are not controllable through set/get volume
                // using attributes. Do not append them.
                if (DEBUG_VOL) {
                    Log.d(TAG, "volume group " + avg.name() + " for internal policy needs");
                }
                continue;
            }
            sVolumeGroupStates.append(avg.getId(), new VolumeGroupState(avg));
        }
        for (int i = 0; i < sVolumeGroupStates.size(); i++) {
            final VolumeGroupState vgs = sVolumeGroupStates.valueAt(i);
            vgs.applyAllVolumes();
        }
    }

    private void ensureValidAttributes(AudioVolumeGroup avg) {
        boolean hasAtLeastOneValidAudioAttributes = avg.getAudioAttributes().stream()
                .anyMatch(aa -> !aa.equals(AudioProductStrategy.sDefaultAttributes));
        if (!hasAtLeastOneValidAudioAttributes) {
            throw new IllegalArgumentException("Volume Group " + avg.name()
                    + " has no valid audio attributes");
        }
    }

    private void readVolumeGroupsSettings() {
        if (DEBUG_VOL) {
            Log.v(TAG, "readVolumeGroupsSettings");
        }
        for (int i = 0; i < sVolumeGroupStates.size(); i++) {
            final VolumeGroupState vgs = sVolumeGroupStates.valueAt(i);
            vgs.readSettings();
            vgs.applyAllVolumes();
        }
    }

    // Called upon crash of AudioServer
    private void restoreVolumeGroups() {
        if (DEBUG_VOL) {
            Log.v(TAG, "restoreVolumeGroups");
        }
        for (int i = 0; i < sVolumeGroupStates.size(); i++) {
            final VolumeGroupState vgs = sVolumeGroupStates.valueAt(i);
            vgs.applyAllVolumes();
        }
    }

    private void dumpVolumeGroups(PrintWriter pw) {
        pw.println("\nVolume Groups (device: index)");
        for (int i = 0; i < sVolumeGroupStates.size(); i++) {
            final VolumeGroupState vgs = sVolumeGroupStates.valueAt(i);
            vgs.dump(pw);
            pw.println("");
        }
    }

    // NOTE: Locking order for synchronized objects related to volume management:
    //  1     mSettingsLock
    //  2       VolumeGroupState.class
    private class VolumeGroupState {
        private final AudioVolumeGroup mAudioVolumeGroup;
        private final SparseIntArray mIndexMap = new SparseIntArray(8);
        private int mIndexMin;
        private int mIndexMax;
        private int mLegacyStreamType = AudioSystem.STREAM_DEFAULT;
        private int mPublicStreamType = AudioSystem.STREAM_MUSIC;
        private AudioAttributes mAudioAttributes = AudioProductStrategy.sDefaultAttributes;

        // No API in AudioSystem to get a device from strategy or from attributes.
        // Need a valid public stream type to use current API getDeviceForStream
        private int getDeviceForVolume() {
            return getDeviceForStream(mPublicStreamType);
        }

        private VolumeGroupState(AudioVolumeGroup avg) {
            mAudioVolumeGroup = avg;
            if (DEBUG_VOL) {
                Log.v(TAG, "VolumeGroupState for " + avg.toString());
            }
            for (final AudioAttributes aa : avg.getAudioAttributes()) {
                if (!aa.equals(AudioProductStrategy.sDefaultAttributes)) {
                    mAudioAttributes = aa;
                    break;
                }
            }
            final int[] streamTypes = mAudioVolumeGroup.getLegacyStreamTypes();
            if (streamTypes.length != 0) {
                // Uses already initialized MIN / MAX if a stream type is attached to group
                mLegacyStreamType = streamTypes[0];
                for (final int streamType : streamTypes) {
                    if (streamType != AudioSystem.STREAM_DEFAULT
                            && streamType < AudioSystem.getNumStreamTypes()) {
                        mPublicStreamType = streamType;
                        break;
                    }
                }
                mIndexMin = MIN_STREAM_VOLUME[mPublicStreamType];
                mIndexMax = MAX_STREAM_VOLUME[mPublicStreamType];
            } else if (!avg.getAudioAttributes().isEmpty()) {
                mIndexMin = AudioSystem.getMinVolumeIndexForAttributes(mAudioAttributes);
                mIndexMax = AudioSystem.getMaxVolumeIndexForAttributes(mAudioAttributes);
            } else {
                Log.e(TAG, "volume group: " + mAudioVolumeGroup.name()
                        + " has neither valid attributes nor valid stream types assigned");
                return;
            }
            // Load volume indexes from data base
            readSettings();
        }

        public @NonNull int[] getLegacyStreamTypes() {
            return mAudioVolumeGroup.getLegacyStreamTypes();
        }

        public String name() {
            return mAudioVolumeGroup.name();
        }

        public int getVolumeIndex() {
            return getIndex(getDeviceForVolume());
        }

        public void setVolumeIndex(int index, int flags) {
            if (mUseFixedVolume) {
                return;
            }
            setVolumeIndex(index, getDeviceForVolume(), flags);
        }

        private void setVolumeIndex(int index, int device, int flags) {
            // Set the volume index
            setVolumeIndexInt(index, device, flags);

            // Update local cache
            mIndexMap.put(device, index);

            // update data base - post a persist volume group msg
            sendMsg(mAudioHandler,
                    MSG_PERSIST_VOLUME_GROUP,
                    SENDMSG_QUEUE,
                    device,
                    0,
                    this,
                    PERSIST_DELAY);
        }

        private void setVolumeIndexInt(int index, int device, int flags) {
            // Set the volume index
            AudioSystem.setVolumeIndexForAttributes(mAudioAttributes, index, device);
        }

        public int getIndex(int device) {
            synchronized (VolumeGroupState.class) {
                int index = mIndexMap.get(device, -1);
                // there is always an entry for AudioSystem.DEVICE_OUT_DEFAULT
                return (index != -1) ? index : mIndexMap.get(AudioSystem.DEVICE_OUT_DEFAULT);
            }
        }

        public boolean hasIndexForDevice(int device) {
            synchronized (VolumeGroupState.class) {
                return (mIndexMap.get(device, -1) != -1);
            }
        }

        public int getMaxIndex() {
            return mIndexMax;
        }

        public int getMinIndex() {
            return mIndexMin;
        }

        public void applyAllVolumes() {
            synchronized (VolumeGroupState.class) {
                // apply device specific volumes first
                int index;
                for (int i = 0; i < mIndexMap.size(); i++) {
                    final int device = mIndexMap.keyAt(i);
                    if (device != AudioSystem.DEVICE_OUT_DEFAULT) {
                        index = mIndexMap.valueAt(i);
                        if (DEBUG_VOL) {
                            Log.v(TAG, "applyAllVolumes: restore index " + index + " for group "
                                    + mAudioVolumeGroup.name() + " and device "
                                    + AudioSystem.getOutputDeviceName(device));
                        }
                        setVolumeIndexInt(index, device, 0 /*flags*/);
                    }
                }
                // apply default volume last: by convention , default device volume will be used
                // by audio policy manager if no explicit volume is present for a given device type
                index = getIndex(AudioSystem.DEVICE_OUT_DEFAULT);
                if (DEBUG_VOL) {
                    Log.v(TAG, "applyAllVolumes: restore default device index " + index
                            + " for group " + mAudioVolumeGroup.name());
                }
                setVolumeIndexInt(index, AudioSystem.DEVICE_OUT_DEFAULT, 0 /*flags*/);
            }
        }

        private void persistVolumeGroup(int device) {
            if (mUseFixedVolume) {
                return;
            }
            if (DEBUG_VOL) {
                Log.v(TAG, "persistVolumeGroup: storing index " + getIndex(device) + " for group "
                        + mAudioVolumeGroup.name() + " and device "
                        + AudioSystem.getOutputDeviceName(device));
            }
            boolean success = Settings.System.putIntForUser(mContentResolver,
                    getSettingNameForDevice(device),
                    getIndex(device),
                    UserHandle.USER_CURRENT);
            if (!success) {
                Log.e(TAG, "persistVolumeGroup failed for group " +  mAudioVolumeGroup.name());
            }
        }

        public void readSettings() {
            synchronized (VolumeGroupState.class) {
                // First clear previously loaded (previous user?) settings
                mIndexMap.clear();
                // force maximum volume on all streams if fixed volume property is set
                if (mUseFixedVolume) {
                    mIndexMap.put(AudioSystem.DEVICE_OUT_DEFAULT, mIndexMax);
                    return;
                }
                for (int device : AudioSystem.DEVICE_OUT_ALL_SET) {
                    // retrieve current volume for device
                    // if no volume stored for current volume group and device, use default volume
                    // if default device, continue otherwise
                    int defaultIndex = (device == AudioSystem.DEVICE_OUT_DEFAULT)
                            ? AudioSystem.DEFAULT_STREAM_VOLUME[mPublicStreamType] : -1;
                    int index;
                    String name = getSettingNameForDevice(device);
                    index = Settings.System.getIntForUser(
                            mContentResolver, name, defaultIndex, UserHandle.USER_CURRENT);
                    if (index == -1) {
                        continue;
                    }
                    if (DEBUG_VOL) {
                        Log.v(TAG, "readSettings: found stored index " + getValidIndex(index)
                                 + " for group " + mAudioVolumeGroup.name() + ", device: " + name);
                    }
                    mIndexMap.put(device, getValidIndex(index));
                }
            }
        }

        private int getValidIndex(int index) {
            if (index < mIndexMin) {
                return mIndexMin;
            } else if (mUseFixedVolume || index > mIndexMax) {
                return mIndexMax;
            }
            return index;
        }

        public @NonNull String getSettingNameForDevice(int device) {
            final String suffix = AudioSystem.getOutputDeviceName(device);
            if (suffix.isEmpty()) {
                return mAudioVolumeGroup.name();
            }
            return mAudioVolumeGroup.name() + "_" + AudioSystem.getOutputDeviceName(device);
        }

        private void dump(PrintWriter pw) {
            pw.println("- VOLUME GROUP " + mAudioVolumeGroup.name() + ":");
            pw.print("   Min: ");
            pw.println(mIndexMin);
            pw.print("   Max: ");
            pw.println(mIndexMax);
            pw.print("   Current: ");
            for (int i = 0; i < mIndexMap.size(); i++) {
                if (i > 0) {
                    pw.print(", ");
                }
                final int device = mIndexMap.keyAt(i);
                pw.print(Integer.toHexString(device));
                final String deviceName = device == AudioSystem.DEVICE_OUT_DEFAULT ? "default"
                        : AudioSystem.getOutputDeviceName(device);
                if (!deviceName.isEmpty()) {
                    pw.print(" (");
                    pw.print(deviceName);
                    pw.print(")");
                }
                pw.print(": ");
                pw.print(mIndexMap.valueAt(i));
            }
            pw.println();
            pw.print("   Devices: ");
            int n = 0;
            final int devices = getDeviceForVolume();
            for (int device : AudioSystem.DEVICE_OUT_ALL_SET) {
                if ((devices & device) == device) {
                    if (n++ > 0) {
                        pw.print(", ");
                    }
                    pw.print(AudioSystem.getOutputDeviceName(device));
                }
            }
        }
    }


    // NOTE: Locking order for synchronized objects related to volume or ringer mode management:
    //  1 mScoclient OR mSafeMediaVolumeState
    //  2   mSetModeLock
    //  3     mSettingsLock
    //  4       VolumeStreamState.class
    private class VolumeStreamState {
        private final int mStreamType;
        private int mIndexMin;
        private int mIndexMax;

        private boolean mIsMuted;
        private String mVolumeIndexSettingName;
        private int mObservedDevices;

        private final SparseIntArray mIndexMap = new SparseIntArray(8);
        private final Intent mVolumeChanged;
        private final Intent mStreamDevicesChanged;

        private VolumeStreamState(String settingName, int streamType) {

            mVolumeIndexSettingName = settingName;

            mStreamType = streamType;
            mIndexMin = MIN_STREAM_VOLUME[streamType] * 10;
            mIndexMax = MAX_STREAM_VOLUME[streamType] * 10;
            AudioSystem.initStreamVolume(streamType, mIndexMin / 10, mIndexMax / 10);

            readSettings();
            mVolumeChanged = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
            mVolumeChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, mStreamType);
            mStreamDevicesChanged = new Intent(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
            mStreamDevicesChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, mStreamType);
        }

        public int observeDevicesForStream_syncVSS(boolean checkOthers) {
            final int devices = AudioSystem.getDevicesForStream(mStreamType);
            if (devices == mObservedDevices) {
                return devices;
            }
            final int prevDevices = mObservedDevices;
            mObservedDevices = devices;
            if (checkOthers) {
                // one stream's devices have changed, check the others
                observeDevicesForStreams(mStreamType);
            }
            // log base stream changes to the event log
            if (mStreamVolumeAlias[mStreamType] == mStreamType) {
                EventLogTags.writeStreamDevicesChanged(mStreamType, prevDevices, devices);
            }
            sendBroadcastToAll(mStreamDevicesChanged
                    .putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_DEVICES, prevDevices)
                    .putExtra(AudioManager.EXTRA_VOLUME_STREAM_DEVICES, devices));
            return devices;
        }

        public @Nullable String getSettingNameForDevice(int device) {
            if (!hasValidSettingsName()) {
                return null;
            }
            final String suffix = AudioSystem.getOutputDeviceName(device);
            if (suffix.isEmpty()) {
                return mVolumeIndexSettingName;
            }
            return mVolumeIndexSettingName + "_" + suffix;
        }

        private boolean hasValidSettingsName() {
            return (mVolumeIndexSettingName != null && !mVolumeIndexSettingName.isEmpty());
        }

        public void readSettings() {
            synchronized (mSettingsLock) {
                synchronized (VolumeStreamState.class) {
                    // force maximum volume on all streams if fixed volume property is set
                    if (mUseFixedVolume) {
                        mIndexMap.put(AudioSystem.DEVICE_OUT_DEFAULT, mIndexMax);
                        return;
                    }
                    // do not read system stream volume from settings: this stream is always aliased
                    // to another stream type and its volume is never persisted. Values in settings can
                    // only be stale values
                    if ((mStreamType == AudioSystem.STREAM_SYSTEM) ||
                            (mStreamType == AudioSystem.STREAM_SYSTEM_ENFORCED)) {
                        int index = 10 * AudioSystem.DEFAULT_STREAM_VOLUME[mStreamType];
                        if (mCameraSoundForced) {
                            index = mIndexMax;
                        }
                        mIndexMap.put(AudioSystem.DEVICE_OUT_DEFAULT, index);
                        return;
                    }
                }
            }
            synchronized (VolumeStreamState.class) {
                for (int device : AudioSystem.DEVICE_OUT_ALL_SET) {

                    // retrieve current volume for device
                    // if no volume stored for current stream and device, use default volume if default
                    // device, continue otherwise
                    int defaultIndex = (device == AudioSystem.DEVICE_OUT_DEFAULT) ?
                            AudioSystem.DEFAULT_STREAM_VOLUME[mStreamType] : -1;
                    int index;
                    if (!hasValidSettingsName()) {
                        index = defaultIndex;
                    } else {
                        String name = getSettingNameForDevice(device);
                        index = Settings.System.getIntForUser(
                                mContentResolver, name, defaultIndex, UserHandle.USER_CURRENT);
                    }
                    if (index == -1) {
                        continue;
                    }

                    mIndexMap.put(device, getValidIndex(10 * index));
                }
            }
        }

        private int getAbsoluteVolumeIndex(int index) {
            /* Special handling for Bluetooth Absolute Volume scenario
             * If we send full audio gain, some accessories are too loud even at its lowest
             * volume. We are not able to enumerate all such accessories, so here is the
             * workaround from phone side.
             * Pre-scale volume at lowest volume steps 1 2 and 3.
             * For volume step 0, set audio gain to 0 as some accessories won't mute on their end.
             */
            if (index == 0) {
                // 0% for volume 0
                index = 0;
            } else if (index > 0 && index <= 3) {
                // Pre-scale for volume steps 1 2 and 3
                index = (int) (mIndexMax * mPrescaleAbsoluteVolume[index - 1]) / 10;
            } else {
                // otherwise, full gain
                index = (mIndexMax + 5) / 10;
            }
            return index;
        }

        private void setStreamVolumeIndex(int index, int device) {
            // Only set audio policy BT SCO stream volume to 0 when the stream is actually muted.
            // This allows RX path muting by the audio HAL only when explicitly muted but not when
            // index is just set to 0 to repect BT requirements
            if (mStreamType == AudioSystem.STREAM_BLUETOOTH_SCO && index == 0 && !mIsMuted) {
                index = 1;
            }
            AudioSystem.setStreamVolumeIndexAS(mStreamType, index, device);
        }

        // must be called while synchronized VolumeStreamState.class
        /*package*/ void applyDeviceVolume_syncVSS(int device, boolean isAvrcpAbsVolSupported) {
            int index;
            if (mIsMuted) {
                index = 0;
            } else if (AudioSystem.DEVICE_OUT_ALL_A2DP_SET.contains(device)
                    && isAvrcpAbsVolSupported) {
                index = getAbsoluteVolumeIndex((getIndex(device) + 5)/10);
            } else if (isFullVolumeDevice(device)) {
                index = (mIndexMax + 5)/10;
            } else if (device == AudioSystem.DEVICE_OUT_HEARING_AID) {
                index = (mIndexMax + 5)/10;
            } else {
                index = (getIndex(device) + 5)/10;
            }
            setStreamVolumeIndex(index, device);
        }

        public void applyAllVolumes() {
            final boolean isAvrcpAbsVolSupported = mDeviceBroker.isAvrcpAbsoluteVolumeSupported();
            synchronized (VolumeStreamState.class) {
                // apply device specific volumes first
                int index;
                for (int i = 0; i < mIndexMap.size(); i++) {
                    final int device = mIndexMap.keyAt(i);
                    if (device != AudioSystem.DEVICE_OUT_DEFAULT) {
                        if (mIsMuted) {
                            index = 0;
                        } else if (AudioSystem.DEVICE_OUT_ALL_A2DP_SET.contains(device)
                                && isAvrcpAbsVolSupported) {
                            index = getAbsoluteVolumeIndex((getIndex(device) + 5)/10);
                        } else if (isFullVolumeDevice(device)) {
                            index = (mIndexMax + 5)/10;
                        } else if (device == AudioSystem.DEVICE_OUT_HEARING_AID) {
                            index = (mIndexMax + 5)/10;
                        } else {
                            index = (mIndexMap.valueAt(i) + 5)/10;
                        }
                        setStreamVolumeIndex(index, device);
                    }
                }
                // apply default volume last: by convention , default device volume will be used
                // by audio policy manager if no explicit volume is present for a given device type
                if (mIsMuted) {
                    index = 0;
                } else {
                    index = (getIndex(AudioSystem.DEVICE_OUT_DEFAULT) + 5)/10;
                }
                setStreamVolumeIndex(index, AudioSystem.DEVICE_OUT_DEFAULT);
            }
        }

        public boolean adjustIndex(int deltaIndex, int device, String caller) {
            return setIndex(getIndex(device) + deltaIndex, device, caller);
        }

        public boolean setIndex(int index, int device, String caller) {
            boolean changed;
            int oldIndex;
            synchronized (mSettingsLock) {
                synchronized (VolumeStreamState.class) {
                    oldIndex = getIndex(device);
                    index = getValidIndex(index);
                    if ((mStreamType == AudioSystem.STREAM_SYSTEM_ENFORCED) && mCameraSoundForced) {
                        index = mIndexMax;
                    }
                    mIndexMap.put(device, index);

                    changed = oldIndex != index;
                    // Apply change to all streams using this one as alias if:
                    // - the index actually changed OR
                    // - there is no volume index stored for this device on alias stream.
                    // If changing volume of current device, also change volume of current
                    // device on aliased stream
                    final boolean isCurrentDevice = (device == getDeviceForStream(mStreamType));
                    final int numStreamTypes = AudioSystem.getNumStreamTypes();
                    for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                        final VolumeStreamState aliasStreamState = mStreamStates[streamType];
                        if (streamType != mStreamType &&
                                mStreamVolumeAlias[streamType] == mStreamType &&
                                (changed || !aliasStreamState.hasIndexForDevice(device))) {
                            final int scaledIndex = rescaleIndex(index, mStreamType, streamType);
                            aliasStreamState.setIndex(scaledIndex, device, caller);
                            if (isCurrentDevice) {
                                aliasStreamState.setIndex(scaledIndex,
                                        getDeviceForStream(streamType), caller);
                            }
                        }
                    }
                    // Mirror changes in SPEAKER ringtone volume on SCO when
                    if (changed && mStreamType == AudioSystem.STREAM_RING
                            && device == AudioSystem.DEVICE_OUT_SPEAKER) {
                        for (int i = 0; i < mIndexMap.size(); i++) {
                            int otherDevice = mIndexMap.keyAt(i);
                            if (AudioSystem.DEVICE_OUT_ALL_SCO_SET.contains(otherDevice)) {
                                mIndexMap.put(otherDevice, index);
                            }
                        }
                    }
                }
            }
            if (changed) {
                oldIndex = (oldIndex + 5) / 10;
                index = (index + 5) / 10;
                // log base stream changes to the event log
                if (mStreamVolumeAlias[mStreamType] == mStreamType) {
                    if (caller == null) {
                        Log.w(TAG, "No caller for volume_changed event", new Throwable());
                    }
                    EventLogTags.writeVolumeChanged(mStreamType, oldIndex, index, mIndexMax / 10,
                            caller);
                }
                // fire changed intents for all streams
                mVolumeChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, index);
                mVolumeChanged.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, oldIndex);
                mVolumeChanged.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE_ALIAS,
                        mStreamVolumeAlias[mStreamType]);
                sendBroadcastToAll(mVolumeChanged);
            }
            return changed;
        }

        public int getIndex(int device) {
            synchronized (VolumeStreamState.class) {
                int index = mIndexMap.get(device, -1);
                if (index == -1) {
                    // there is always an entry for AudioSystem.DEVICE_OUT_DEFAULT
                    index = mIndexMap.get(AudioSystem.DEVICE_OUT_DEFAULT);
                }
                return index;
            }
        }

        public boolean hasIndexForDevice(int device) {
            synchronized (VolumeStreamState.class) {
                return (mIndexMap.get(device, -1) != -1);
            }
        }

        public int getMaxIndex() {
            return mIndexMax;
        }

        public int getMinIndex() {
            return mIndexMin;
        }

        /**
         * Copies all device/index pairs from the given VolumeStreamState after initializing
         * them with the volume for DEVICE_OUT_DEFAULT. No-op if the source VolumeStreamState
         * has the same stream type as this instance.
         * @param srcStream
         * @param caller
         */
        // must be sync'd on mSettingsLock before VolumeStreamState.class
        @GuardedBy("VolumeStreamState.class")
        public void setAllIndexes(VolumeStreamState srcStream, String caller) {
            if (mStreamType == srcStream.mStreamType) {
                return;
            }
            int srcStreamType = srcStream.getStreamType();
            // apply default device volume from source stream to all devices first in case
            // some devices are present in this stream state but not in source stream state
            int index = srcStream.getIndex(AudioSystem.DEVICE_OUT_DEFAULT);
            index = rescaleIndex(index, srcStreamType, mStreamType);
            for (int i = 0; i < mIndexMap.size(); i++) {
                mIndexMap.put(mIndexMap.keyAt(i), index);
            }
            // Now apply actual volume for devices in source stream state
            SparseIntArray srcMap = srcStream.mIndexMap;
            for (int i = 0; i < srcMap.size(); i++) {
                int device = srcMap.keyAt(i);
                index = srcMap.valueAt(i);
                index = rescaleIndex(index, srcStreamType, mStreamType);

                setIndex(index, device, caller);
            }
        }

        // must be sync'd on mSettingsLock before VolumeStreamState.class
        @GuardedBy("VolumeStreamState.class")
        public void setAllIndexesToMax() {
            for (int i = 0; i < mIndexMap.size(); i++) {
                mIndexMap.put(mIndexMap.keyAt(i), mIndexMax);
            }
        }

        /**
         * Mute/unmute the stream
         * @param state the new mute state
         * @return true if the mute state was changed
         */
        public boolean mute(boolean state) {
            boolean changed = false;
            synchronized (VolumeStreamState.class) {
                if (state != mIsMuted) {
                    changed = true;
                    mIsMuted = state;

                    // Set the new mute volume. This propagates the values to
                    // the audio system, otherwise the volume won't be changed
                    // at the lower level.
                    sendMsg(mAudioHandler,
                            MSG_SET_ALL_VOLUMES,
                            SENDMSG_QUEUE,
                            0,
                            0,
                            this, 0);
                }
            }
            if (changed) {
                // Stream mute changed, fire the intent.
                Intent intent = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
                intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, mStreamType);
                intent.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, state);
                sendBroadcastToAll(intent);
            }
            return changed;
        }

        public int getStreamType() {
            return mStreamType;
        }

        public void checkFixedVolumeDevices() {
            final boolean isAvrcpAbsVolSupported = mDeviceBroker.isAvrcpAbsoluteVolumeSupported();
            synchronized (VolumeStreamState.class) {
                // ignore settings for fixed volume devices: volume should always be at max or 0
                if (mStreamVolumeAlias[mStreamType] == AudioSystem.STREAM_MUSIC) {
                    for (int i = 0; i < mIndexMap.size(); i++) {
                        int device = mIndexMap.keyAt(i);
                        int index = mIndexMap.valueAt(i);
                        if (isFullVolumeDevice(device)
                                || (isFixedVolumeDevice(device) && index != 0)) {
                            mIndexMap.put(device, mIndexMax);
                        }
                        applyDeviceVolume_syncVSS(device, isAvrcpAbsVolSupported);
                    }
                }
            }
        }

        private int getValidIndex(int index) {
            if (index < mIndexMin) {
                return mIndexMin;
            } else if (mUseFixedVolume || index > mIndexMax) {
                return mIndexMax;
            }

            return index;
        }

        private void dump(PrintWriter pw) {
            pw.print("   Muted: ");
            pw.println(mIsMuted);
            pw.print("   Min: ");
            pw.println((mIndexMin + 5) / 10);
            pw.print("   Max: ");
            pw.println((mIndexMax + 5) / 10);
            pw.print("   streamVolume:"); pw.println(getStreamVolume(mStreamType));
            pw.print("   Current: ");
            for (int i = 0; i < mIndexMap.size(); i++) {
                if (i > 0) {
                    pw.print(", ");
                }
                final int device = mIndexMap.keyAt(i);
                pw.print(Integer.toHexString(device));
                final String deviceName = device == AudioSystem.DEVICE_OUT_DEFAULT ? "default"
                        : AudioSystem.getOutputDeviceName(device);
                if (!deviceName.isEmpty()) {
                    pw.print(" (");
                    pw.print(deviceName);
                    pw.print(")");
                }
                pw.print(": ");
                final int index = (mIndexMap.valueAt(i) + 5) / 10;
                pw.print(index);
            }
            pw.println();
            pw.print("   Devices: ");
            final int devices = getDevicesForStream(mStreamType);
            int device, i = 0, n = 0;
            // iterate all devices from 1 to DEVICE_OUT_DEFAULT exclusive
            // (the default device is not returned by getDevicesForStream)
            while ((device = 1 << i) != AudioSystem.DEVICE_OUT_DEFAULT) {
                if ((devices & device) != 0) {
                    if (n++ > 0) {
                        pw.print(", ");
                    }
                    pw.print(AudioSystem.getOutputDeviceName(device));
                }
                i++;
            }
        }
    }

    /** Thread that handles native AudioSystem control. */
    private class AudioSystemThread extends Thread {
        AudioSystemThread() {
            super("AudioService");
        }

        @Override
        public void run() {
            // Set this thread up so the handler will work on it
            Looper.prepare();

            synchronized(AudioService.this) {
                mAudioHandler = new AudioHandler();

                // Notify that the handler has been created
                AudioService.this.notify();
            }

            // Listen for volume change requests that are set by VolumePanel
            Looper.loop();
        }
    }

    private static final class DeviceVolumeUpdate {
        final int mStreamType;
        final int mDevice;
        final @NonNull String mCaller;
        private static final int NO_NEW_INDEX = -2049;
        private final int mVssVolIndex;

        // Constructor with volume index, meant to cause this volume to be set and applied for the
        // given stream type on the given device
        DeviceVolumeUpdate(int streamType, int vssVolIndex, int device, @NonNull String caller) {
            mStreamType = streamType;
            mVssVolIndex = vssVolIndex;
            mDevice = device;
            mCaller = caller;
        }

        // Constructor with no volume index, meant to cause re-apply of volume for the given
        // stream type on the given device
        DeviceVolumeUpdate(int streamType, int device, @NonNull String caller) {
            mStreamType = streamType;
            mVssVolIndex = NO_NEW_INDEX;
            mDevice = device;
            mCaller = caller;
        }

        boolean hasVolumeIndex() {
            return mVssVolIndex != NO_NEW_INDEX;
        }

        int getVolumeIndex() throws IllegalStateException {
            Preconditions.checkState(mVssVolIndex != NO_NEW_INDEX);
            return mVssVolIndex;
        }
    }

    /** only public for mocking/spying, do not call outside of AudioService */
    @VisibleForTesting
    public void postSetVolumeIndexOnDevice(int streamType, int vssVolIndex, int device,
                                                String caller) {
        sendMsg(mAudioHandler,
                MSG_SET_DEVICE_STREAM_VOLUME,
                SENDMSG_QUEUE, 0 /*arg1*/, 0 /*arg2*/,
                new DeviceVolumeUpdate(streamType, vssVolIndex, device, caller),
                0 /*delay*/);
    }

    /*package*/ void postApplyVolumeOnDevice(int streamType, int device, @NonNull String caller) {
        sendMsg(mAudioHandler,
                MSG_SET_DEVICE_STREAM_VOLUME,
                SENDMSG_QUEUE, 0 /*arg1*/, 0 /*arg2*/,
                new DeviceVolumeUpdate(streamType, device, caller),
                0 /*delay*/);
    }

    private void onSetVolumeIndexOnDevice(@NonNull DeviceVolumeUpdate update) {
        final VolumeStreamState streamState = mStreamStates[update.mStreamType];
        if (update.hasVolumeIndex()) {
            final int index = update.getVolumeIndex();
            streamState.setIndex(index, update.mDevice, update.mCaller);
            sVolumeLogger.log(new AudioEventLogger.StringEvent(update.mCaller + " dev:0x"
                    + Integer.toHexString(update.mDevice) + " volIdx:" + index));
        } else {
            sVolumeLogger.log(new AudioEventLogger.StringEvent(update.mCaller
                    + " update vol on dev:0x" + Integer.toHexString(update.mDevice)));
        }
        setDeviceVolume(streamState, update.mDevice);
    }

    /*package*/ void setDeviceVolume(VolumeStreamState streamState, int device) {

        final boolean isAvrcpAbsVolSupported = mDeviceBroker.isAvrcpAbsoluteVolumeSupported();

        synchronized (VolumeStreamState.class) {
            // Apply volume
            streamState.applyDeviceVolume_syncVSS(device, isAvrcpAbsVolSupported);

            // Apply change to all streams using this one as alias
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType &&
                        mStreamVolumeAlias[streamType] == streamState.mStreamType) {
                    // Make sure volume is also maxed out on A2DP device for aliased stream
                    // that may have a different device selected
                    int streamDevice = getDeviceForStream(streamType);
                    if ((device != streamDevice) && isAvrcpAbsVolSupported
                            && AudioSystem.DEVICE_OUT_ALL_A2DP_SET.contains(device)) {
                        mStreamStates[streamType].applyDeviceVolume_syncVSS(device,
                                isAvrcpAbsVolSupported);
                    }
                    mStreamStates[streamType].applyDeviceVolume_syncVSS(streamDevice,
                            isAvrcpAbsVolSupported);
                }
            }
        }
        // Post a persist volume msg
        sendMsg(mAudioHandler,
                MSG_PERSIST_VOLUME,
                SENDMSG_QUEUE,
                device,
                0,
                streamState,
                PERSIST_DELAY);

    }

    /** Handles internal volume messages in separate volume thread. */
    private class AudioHandler extends Handler {

        private void setAllVolumes(VolumeStreamState streamState) {

            // Apply volume
            streamState.applyAllVolumes();

            // Apply change to all streams using this one as alias
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType &&
                        mStreamVolumeAlias[streamType] == streamState.mStreamType) {
                    mStreamStates[streamType].applyAllVolumes();
                }
            }
        }

        private void persistVolume(VolumeStreamState streamState, int device) {
            if (mUseFixedVolume) {
                return;
            }
            if (mIsSingleVolume && (streamState.mStreamType != AudioSystem.STREAM_MUSIC)) {
                return;
            }
            if (streamState.hasValidSettingsName()) {
                System.putIntForUser(mContentResolver,
                        streamState.getSettingNameForDevice(device),
                        (streamState.getIndex(device) + 5)/ 10,
                        UserHandle.USER_CURRENT);
            }
        }

        private void persistRingerMode(int ringerMode) {
            if (mUseFixedVolume) {
                return;
            }
            Settings.Global.putInt(mContentResolver, Settings.Global.MODE_RINGER, ringerMode);
        }

        private void onPersistSafeVolumeState(int state) {
            Settings.Global.putInt(mContentResolver,
                    Settings.Global.AUDIO_SAFE_VOLUME_STATE,
                    state);
        }

        private void onNotifyVolumeEvent(@NonNull IAudioPolicyCallback apc,
                @AudioManager.VolumeAdjustment int direction) {
            try {
                apc.notifyVolumeAdjust(direction);
            } catch(Exception e) {
                // nothing we can do about this. Do not log error, too much potential for spam
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                case MSG_SET_DEVICE_VOLUME:
                    setDeviceVolume((VolumeStreamState) msg.obj, msg.arg1);
                    break;

                case MSG_SET_ALL_VOLUMES:
                    setAllVolumes((VolumeStreamState) msg.obj);
                    break;

                case MSG_PERSIST_VOLUME:
                    persistVolume((VolumeStreamState) msg.obj, msg.arg1);
                    break;

                case MSG_PERSIST_VOLUME_GROUP:
                    final VolumeGroupState vgs = (VolumeGroupState) msg.obj;
                    vgs.persistVolumeGroup(msg.arg1);
                    break;

                case MSG_PERSIST_RINGER_MODE:
                    // note that the value persisted is the current ringer mode, not the
                    // value of ringer mode as of the time the request was made to persist
                    persistRingerMode(getRingerModeInternal());
                    break;

                case MSG_AUDIO_SERVER_DIED:
                    onAudioServerDied();
                    break;

                case MSG_DISPATCH_AUDIO_SERVER_STATE:
                    onDispatchAudioServerStateChange(msg.arg1 == 1);
                    break;

                case MSG_UNLOAD_SOUND_EFFECTS:
                    mSfxHelper.unloadSoundEffects();
                    break;

                case MSG_LOAD_SOUND_EFFECTS:
                {
                    LoadSoundEffectReply reply = (LoadSoundEffectReply) msg.obj;
                    if (mSystemReady) {
                        mSfxHelper.loadSoundEffects(reply);
                    } else {
                        Log.w(TAG, "[schedule]loadSoundEffects() called before boot complete");
                        if (reply != null) {
                            reply.run(false);
                        }
                    }
                }
                    break;

                case MSG_PLAY_SOUND_EFFECT:
                    mSfxHelper.playSoundEffect(msg.arg1, msg.arg2);
                    break;

                case MSG_SET_FORCE_USE:
                {
                    final String eventSource = (String) msg.obj;
                    final int useCase = msg.arg1;
                    final int config = msg.arg2;
                    if (useCase == AudioSystem.FOR_MEDIA) {
                        Log.wtf(TAG, "Invalid force use FOR_MEDIA in AudioService from "
                                + eventSource);
                        break;
                    }
                    sForceUseLogger.log(
                            new AudioServiceEvents.ForceUseEvent(useCase, config, eventSource));
                    AudioSystem.setForceUse(useCase, config);
                }
                    break;

                case MSG_DISABLE_AUDIO_FOR_UID:
                    mPlaybackMonitor.disableAudioForUid( msg.arg1 == 1 /* disable */,
                            msg.arg2 /* uid */);
                    mAudioEventWakeLock.release();
                    break;

                case MSG_CHECK_MUSIC_ACTIVE:
                    onCheckMusicActive((String) msg.obj);
                    break;

                case MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED:
                case MSG_CONFIGURE_SAFE_MEDIA_VOLUME:
                    onConfigureSafeVolume((msg.what == MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED),
                            (String) msg.obj);
                    break;
                case MSG_PERSIST_SAFE_VOLUME_STATE:
                    onPersistSafeVolumeState(msg.arg1);
                    break;

                case MSG_SYSTEM_READY:
                    onSystemReady();
                    break;

                case MSG_INDICATE_SYSTEM_READY:
                    onIndicateSystemReady();
                    break;

                case MSG_ACCESSORY_PLUG_MEDIA_UNMUTE:
                    onAccessoryPlugMediaUnmute(msg.arg1);
                    break;

                case MSG_PERSIST_MUSIC_ACTIVE_MS:
                    final int musicActiveMs = msg.arg1;
                    Settings.Secure.putIntForUser(mContentResolver,
                            Settings.Secure.UNSAFE_VOLUME_MUSIC_ACTIVE_MS, musicActiveMs,
                            UserHandle.USER_CURRENT);
                    break;

                case MSG_UNMUTE_STREAM:
                    onUnmuteStream(msg.arg1, msg.arg2);
                    break;

                case MSG_DYN_POLICY_MIX_STATE_UPDATE:
                    onDynPolicyMixStateUpdate((String) msg.obj, msg.arg1);
                    break;

                case MSG_NOTIFY_VOL_EVENT:
                    onNotifyVolumeEvent((IAudioPolicyCallback) msg.obj, msg.arg1);
                    break;

                case MSG_ENABLE_SURROUND_FORMATS:
                    onEnableSurroundFormats((ArrayList<Integer>) msg.obj);
                    break;

                case MSG_UPDATE_RINGER_MODE:
                    onUpdateRingerModeServiceInt();
                    break;

                case MSG_SET_DEVICE_STREAM_VOLUME:
                    onSetVolumeIndexOnDevice((DeviceVolumeUpdate) msg.obj);
                    break;

                case MSG_OBSERVE_DEVICES_FOR_ALL_STREAMS:
                    onObserveDevicesForAllStreams();
                    break;

                case MSG_HDMI_VOLUME_CHECK:
                    onCheckVolumeCecOnHdmiConnection(msg.arg1, (String) msg.obj);
                    break;

                case MSG_PLAYBACK_CONFIG_CHANGE:
                    onPlaybackConfigChange((List<AudioPlaybackConfiguration>) msg.obj);
                    break;

                case MSG_BROADCAST_MICROPHONE_MUTE:
                    mContext.sendBroadcastAsUser(
                            new Intent(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
                                    .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                                    UserHandle.ALL);
                    break;
            }
        }
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver() {
            super(new Handler());
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.ZEN_MODE), false, this);
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.ZEN_MODE_CONFIG_ETAG), false, this);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.MODE_RINGER_STREAMS_AFFECTED), false, this);
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.DOCK_AUDIO_MEDIA_ENABLED), false, this);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.MASTER_MONO), false, this);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.MASTER_BALANCE), false, this);

            mEncodedSurroundMode = Settings.Global.getInt(
                    mContentResolver, Settings.Global.ENCODED_SURROUND_OUTPUT,
                    Settings.Global.ENCODED_SURROUND_OUTPUT_AUTO);
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.ENCODED_SURROUND_OUTPUT), false, this);

            mEnabledSurroundFormats = Settings.Global.getString(
                    mContentResolver, Settings.Global.ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS);
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.ENCODED_SURROUND_OUTPUT_ENABLED_FORMATS), false, this);

            mContentResolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.VOICE_INTERACTION_SERVICE), false, this);
            mContentResolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DEFAULT_INPUT_METHOD), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            // FIXME This synchronized is not necessary if mSettingsLock only protects mRingerMode.
            //       However there appear to be some missing locks around mRingerAndZenModeMutedStreams
            //       and mRingerModeAffectedStreams, so will leave this synchronized for now.
            //       mRingerAndZenModeMutedStreams and mMuteAffectedStreams are safe (only accessed once).
            synchronized (mSettingsLock) {
                if (updateRingerAndZenModeAffectedStreams()) {
                    /*
                     * Ensure all stream types that should be affected by ringer mode
                     * are in the proper state.
                     */
                    setRingerModeInt(getRingerModeInternal(), false);
                }
                readDockAudioSettings(mContentResolver);
                updateMasterMono(mContentResolver);
                updateMasterBalance(mContentResolver);
                updateEncodedSurroundOutput();
                sendEnabledSurroundFormats(mContentResolver, mSurroundModeChanged);
                updateAssistantUId(false);
                updateCurrentImeUid(false);
            }
        }

        private void updateEncodedSurroundOutput() {
            int newSurroundMode = Settings.Global.getInt(
                mContentResolver, Settings.Global.ENCODED_SURROUND_OUTPUT,
                Settings.Global.ENCODED_SURROUND_OUTPUT_AUTO);
            // Did it change?
            if (mEncodedSurroundMode != newSurroundMode) {
                // Send to AudioPolicyManager
                sendEncodedSurroundMode(newSurroundMode, "SettingsObserver");
                mDeviceBroker.toggleHdmiIfConnected_Async();
                mEncodedSurroundMode = newSurroundMode;
                mSurroundModeChanged = true;
            } else {
                mSurroundModeChanged = false;
            }
        }
    }

    public void avrcpSupportsAbsoluteVolume(String address, boolean support) {
        // address is not used for now, but may be used when multiple a2dp devices are supported
        sVolumeLogger.log(new AudioEventLogger.StringEvent("avrcpSupportsAbsoluteVolume addr="
                + address + " support=" + support));
        mDeviceBroker.setAvrcpAbsoluteVolumeSupported(support);
        sendMsg(mAudioHandler, MSG_SET_DEVICE_VOLUME, SENDMSG_QUEUE,
                    AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, 0,
                    mStreamStates[AudioSystem.STREAM_MUSIC], 0);
    }

    /**
     * @return true if there is currently a registered dynamic mixing policy that affects media
     * and is not a render + loopback policy
     */
    // only public for mocking/spying
    @VisibleForTesting
    public boolean hasMediaDynamicPolicy() {
        synchronized (mAudioPolicies) {
            if (mAudioPolicies.isEmpty()) {
                return false;
            }
            final Collection<AudioPolicyProxy> appColl = mAudioPolicies.values();
            for (AudioPolicyProxy app : appColl) {
                if (app.hasMixAffectingUsage(AudioAttributes.USAGE_MEDIA,
                        AudioMix.ROUTE_FLAG_LOOP_BACK_RENDER)) {
                    return true;
                }
            }
            return false;
        }
    }

    /*package*/ void checkMusicActive(int deviceType, String caller) {
        if (mSafeMediaVolumeDevices.contains(deviceType)) {
            sendMsg(mAudioHandler,
                    MSG_CHECK_MUSIC_ACTIVE,
                    SENDMSG_REPLACE,
                    0,
                    0,
                    caller,
                    MUSIC_ACTIVE_POLL_PERIOD_MS);
        }
    }

    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class AudioServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            int outDevice;
            int inDevice;
            int state;

            if (action.equals(Intent.ACTION_DOCK_EVENT)) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                int config;
                switch (dockState) {
                    case Intent.EXTRA_DOCK_STATE_DESK:
                        config = AudioSystem.FORCE_BT_DESK_DOCK;
                        break;
                    case Intent.EXTRA_DOCK_STATE_CAR:
                        config = AudioSystem.FORCE_BT_CAR_DOCK;
                        break;
                    case Intent.EXTRA_DOCK_STATE_LE_DESK:
                        config = AudioSystem.FORCE_ANALOG_DOCK;
                        break;
                    case Intent.EXTRA_DOCK_STATE_HE_DESK:
                        config = AudioSystem.FORCE_DIGITAL_DOCK;
                        break;
                    case Intent.EXTRA_DOCK_STATE_UNDOCKED:
                    default:
                        config = AudioSystem.FORCE_NONE;
                }
                // Low end docks have a menu to enable or disable audio
                // (see mDockAudioMediaEnabled)
                if (!((dockState == Intent.EXTRA_DOCK_STATE_LE_DESK)
                        || ((dockState == Intent.EXTRA_DOCK_STATE_UNDOCKED)
                                && (mDockState == Intent.EXTRA_DOCK_STATE_LE_DESK)))) {
                    mDeviceBroker.setForceUse_Async(AudioSystem.FOR_DOCK, config,
                            "ACTION_DOCK_EVENT intent");
                }
                mDockState = dockState;
            } else if (action.equals(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED)
                    || action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                mDeviceBroker.receiveBtEvent(intent);
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                if (mMonitorRotation) {
                    RotationHelper.enable();
                }
                AudioSystem.setParameters("screen_state=on");
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                if (mMonitorRotation) {
                    //reduce wakeups (save current) by only listening when display is on
                    RotationHelper.disable();
                }
                AudioSystem.setParameters("screen_state=off");
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                handleConfigurationChanged(context);
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                if (mUserSwitchedReceived) {
                    // attempt to stop music playback for background user except on first user
                    // switch (i.e. first boot)
                    mDeviceBroker.postBroadcastBecomingNoisy();
                }
                mUserSwitchedReceived = true;
                // the current audio focus owner is no longer valid
                mMediaFocusControl.discardAudioFocusOwner();

                // load volume settings for new user
                readAudioSettings(true /*userSwitch*/);
                // preserve STREAM_MUSIC volume from one user to the next.
                sendMsg(mAudioHandler,
                        MSG_SET_ALL_VOLUMES,
                        SENDMSG_QUEUE,
                        0,
                        0,
                        mStreamStates[AudioSystem.STREAM_MUSIC], 0);
            } else if (action.equals(Intent.ACTION_USER_BACKGROUND)) {
                // Disable audio recording for the background user/profile
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId >= 0) {
                    // TODO Kill recording streams instead of killing processes holding permission
                    UserInfo userInfo = UserManagerService.getInstance().getUserInfo(userId);
                    killBackgroundUserProcessesWithRecordAudioPermission(userInfo);
                }
                UserManagerService.getInstance().setUserRestriction(
                        UserManager.DISALLOW_RECORD_AUDIO, true, userId);
            } else if (action.equals(Intent.ACTION_USER_FOREGROUND)) {
                // Enable audio recording for foreground user/profile
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                UserManagerService.getInstance().setUserRestriction(
                        UserManager.DISALLOW_RECORD_AUDIO, false, userId);
            } else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (state == BluetoothAdapter.STATE_OFF ||
                        state == BluetoothAdapter.STATE_TURNING_OFF) {
                    mDeviceBroker.disconnectAllBluetoothProfiles();
                }
            } else if (action.equals(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) ||
                    action.equals(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)) {
                handleAudioEffectBroadcast(context, intent);
            } else if (action.equals(Intent.ACTION_PACKAGES_SUSPENDED)) {
                final int[] suspendedUids = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                final String[] suspendedPackages =
                        intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                if (suspendedPackages == null || suspendedUids == null
                        || suspendedPackages.length != suspendedUids.length) {
                    return;
                }
                for (int i = 0; i < suspendedUids.length; i++) {
                    if (!TextUtils.isEmpty(suspendedPackages[i])) {
                        mMediaFocusControl.noFocusForSuspendedApp(
                                suspendedPackages[i], suspendedUids[i]);
                    }
                }
            }
        }
    } // end class AudioServiceBroadcastReceiver

    private class AudioServiceUserRestrictionsListener implements UserRestrictionsListener {

        @Override
        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions,
                Bundle prevRestrictions) {
            // Update mic mute state.
            {
                final boolean wasRestricted =
                        prevRestrictions.getBoolean(UserManager.DISALLOW_UNMUTE_MICROPHONE);
                final boolean isRestricted =
                        newRestrictions.getBoolean(UserManager.DISALLOW_UNMUTE_MICROPHONE);
                if (wasRestricted != isRestricted) {
                    mMicMuteFromRestrictions = isRestricted;
                    setMicrophoneMuteNoCallerCheck(userId);
                }
            }

            // Update speaker mute state.
            {
                final boolean wasRestricted =
                        prevRestrictions.getBoolean(UserManager.DISALLOW_ADJUST_VOLUME)
                                || prevRestrictions.getBoolean(UserManager.DISALLOW_UNMUTE_DEVICE);
                final boolean isRestricted =
                        newRestrictions.getBoolean(UserManager.DISALLOW_ADJUST_VOLUME)
                                || newRestrictions.getBoolean(UserManager.DISALLOW_UNMUTE_DEVICE);
                if (wasRestricted != isRestricted) {
                    setMasterMuteInternalNoCallerCheck(isRestricted, /* flags =*/ 0, userId);
                }
            }
        }
    } // end class AudioServiceUserRestrictionsListener

    private void handleAudioEffectBroadcast(Context context, Intent intent) {
        String target = intent.getPackage();
        if (target != null) {
            Log.w(TAG, "effect broadcast already targeted to " + target);
            return;
        }
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        // TODO this should target a user-selected panel
        List<ResolveInfo> ril = context.getPackageManager().queryBroadcastReceivers(
                intent, 0 /* flags */);
        if (ril != null && ril.size() != 0) {
            ResolveInfo ri = ril.get(0);
            if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                intent.setPackage(ri.activityInfo.packageName);
                context.sendBroadcastAsUser(intent, UserHandle.ALL);
                return;
            }
        }
        Log.w(TAG, "couldn't find receiver package for effect intent");
    }

    private void killBackgroundUserProcessesWithRecordAudioPermission(UserInfo oldUser) {
        PackageManager pm = mContext.getPackageManager();
        // Find the home activity of the user. It should not be killed to avoid expensive restart,
        // when the user switches back. For managed profiles, we should kill all recording apps
        ComponentName homeActivityName = null;
        if (!oldUser.isManagedProfile()) {
            homeActivityName = LocalServices.getService(
                    ActivityTaskManagerInternal.class).getHomeActivityForUser(oldUser.id);
        }
        final String[] permissions = { Manifest.permission.RECORD_AUDIO };
        List<PackageInfo> packages;
        try {
            packages = AppGlobals.getPackageManager()
                    .getPackagesHoldingPermissions(permissions, 0, oldUser.id).getList();
        } catch (RemoteException e) {
            throw new AndroidRuntimeException(e);
        }
        for (int j = packages.size() - 1; j >= 0; j--) {
            PackageInfo pkg = packages.get(j);
            // Skip system processes
            if (UserHandle.getAppId(pkg.applicationInfo.uid) < FIRST_APPLICATION_UID) {
                continue;
            }
            // Skip packages that have permission to interact across users
            if (pm.checkPermission(Manifest.permission.INTERACT_ACROSS_USERS, pkg.packageName)
                    == PackageManager.PERMISSION_GRANTED) {
                continue;
            }
            if (homeActivityName != null
                    && pkg.packageName.equals(homeActivityName.getPackageName())
                    && pkg.applicationInfo.isSystemApp()) {
                continue;
            }
            try {
                final int uid = pkg.applicationInfo.uid;
                ActivityManager.getService().killUid(UserHandle.getAppId(uid),
                        UserHandle.getUserId(uid),
                        "killBackgroundUserProcessesWithAudioRecordPermission");
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling killUid", e);
            }
        }
    }


    //==========================================================================================
    // Audio Focus
    //==========================================================================================
    /**
     * Returns whether a focus request is eligible to force ducking.
     * Will return true if:
     * - the AudioAttributes have a usage of USAGE_ASSISTANCE_ACCESSIBILITY,
     * - the focus request is AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
     * - the associated Bundle has KEY_ACCESSIBILITY_FORCE_FOCUS_DUCKING set to true,
     * - the uid of the requester is a known accessibility service or root.
     * @param aa AudioAttributes of the focus request
     * @param uid uid of the focus requester
     * @return true if ducking is to be forced
     */
    private boolean forceFocusDuckingForAccessibility(@Nullable AudioAttributes aa,
            int request, int uid) {
        if (aa == null || aa.getUsage() != AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                || request != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
            return false;
        }
        final Bundle extraInfo = aa.getBundle();
        if (extraInfo == null ||
                !extraInfo.getBoolean(AudioFocusRequest.KEY_ACCESSIBILITY_FORCE_FOCUS_DUCKING)) {
            return false;
        }
        if (uid == 0) {
            return true;
        }
        synchronized (mAccessibilityServiceUidsLock) {
            if (mAccessibilityServiceUids != null) {
                int callingUid = Binder.getCallingUid();
                for (int i = 0; i < mAccessibilityServiceUids.length; i++) {
                    if (mAccessibilityServiceUids[i] == callingUid) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSupportedSystemUsage(@AudioAttributes.AttributeUsage int usage) {
        synchronized (mSupportedSystemUsagesLock) {
            for (int i = 0; i < mSupportedSystemUsages.length; i++) {
                if (mSupportedSystemUsages[i] == usage) {
                    return true;
                }
            }
            return false;
        }
    }

    private void validateAudioAttributesUsage(@NonNull AudioAttributes audioAttributes) {
        @AudioAttributes.AttributeUsage int usage = audioAttributes.getSystemUsage();
        if (AudioAttributes.isSystemUsage(usage)) {
            if (callerHasPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)) {
                if (!isSupportedSystemUsage(usage)) {
                    throw new IllegalArgumentException(
                            "Unsupported usage " + AudioAttributes.usageToString(usage));
                }
            } else {
                throw new SecurityException("Missing MODIFY_AUDIO_ROUTING permission");
            }
        }
    }

    private boolean isValidAudioAttributesUsage(@NonNull AudioAttributes audioAttributes) {
        @AudioAttributes.AttributeUsage int usage = audioAttributes.getSystemUsage();
        if (AudioAttributes.isSystemUsage(usage)) {
            return callerHasPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
                    && isSupportedSystemUsage(usage);
        }
        return true;
    }

    public int requestAudioFocus(AudioAttributes aa, int durationHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName, int flags,
            IAudioPolicyCallback pcb, int sdk) {
        // permission checks
        if (aa != null && !isValidAudioAttributesUsage(aa)) {
            Log.w(TAG, "Request using unsupported usage.");
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        if ((flags & AudioManager.AUDIOFOCUS_FLAG_LOCK) == AudioManager.AUDIOFOCUS_FLAG_LOCK) {
            if (AudioSystem.IN_VOICE_COMM_FOCUS_ID.equals(clientId)) {
                if (PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(
                            android.Manifest.permission.MODIFY_PHONE_STATE)) {
                    Log.e(TAG, "Invalid permission to (un)lock audio focus", new Exception());
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                }
            } else {
                // only a registered audio policy can be used to lock focus
                synchronized (mAudioPolicies) {
                    if (!mAudioPolicies.containsKey(pcb.asBinder())) {
                        Log.e(TAG, "Invalid unregistered AudioPolicy to (un)lock audio focus");
                        return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                    }
                }
            }
        }

        if (callingPackageName == null || clientId == null || aa == null) {
            Log.e(TAG, "Invalid null parameter to request audio focus");
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        int uid = Binder.getCallingUid();
        new MediaMetrics.Item(mAnalyticsId + "requestAudioFocus")
                .setUid(uid)
                .putInt("durationHint", durationHint)
                .putString("clientId", clientId)
                .putString("callingPackage", callingPackageName)
                .putInt("flags", flags)
                .putInt("sdk", sdk)
                .record();

        return mMediaFocusControl.requestAudioFocus(aa, durationHint, cb, fd,
                clientId, callingPackageName, flags, sdk,
                forceFocusDuckingForAccessibility(aa, durationHint, uid));
    }

    public int abandonAudioFocus(IAudioFocusDispatcher fd, String clientId, AudioAttributes aa,
            String callingPackageName) {
        MediaMetrics.Item mmi = new MediaMetrics.Item(mAnalyticsId + "abandonAudioFocus")
                .putString("clientId", clientId)
                .putString("callingPackage", callingPackageName);

        if (aa != null && !isValidAudioAttributesUsage(aa)) {
            Log.w(TAG, "Request using unsupported usage.");
            mmi.putString(mAnalyticsPropEarlyReturn, "unsupported usage").record();

            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        mmi.record();
        return mMediaFocusControl.abandonAudioFocus(fd, clientId, aa, callingPackageName);
    }

    public void unregisterAudioFocusClient(String clientId) {
        new MediaMetrics.Item(mAnalyticsId + "unregisterAudioFocusClient")
                .putString("clientId", clientId)
                .record();
        mMediaFocusControl.unregisterAudioFocusClient(clientId);
    }

    public int getCurrentAudioFocus() {
        return mMediaFocusControl.getCurrentAudioFocus();
    }

    public int getFocusRampTimeMs(int focusGain, AudioAttributes attr) {
        return mMediaFocusControl.getFocusRampTimeMs(focusGain, attr);
    }

    /** only public for mocking/spying, do not call outside of AudioService */
    @VisibleForTesting
    public boolean hasAudioFocusUsers() {
        return mMediaFocusControl.hasAudioFocusUsers();
    }

    //==========================================================================================
    private boolean readCameraSoundForced() {
        return SystemProperties.getBoolean("audio.camerasound.force", false) ||
                mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_camera_sound_forced);
    }

    //==========================================================================================
    // Device orientation
    //==========================================================================================
    /**
     * Handles device configuration changes that may map to a change in rotation.
     * Monitoring rotation is optional, and is defined by the definition and value
     * of the "ro.audio.monitorRotation" system property.
     */
    private void handleConfigurationChanged(Context context) {
        try {
            // reading new configuration "safely" (i.e. under try catch) in case anything
            // goes wrong.
            Configuration config = context.getResources().getConfiguration();
            sendMsg(mAudioHandler,
                    MSG_CONFIGURE_SAFE_MEDIA_VOLUME,
                    SENDMSG_REPLACE,
                    0,
                    0,
                    TAG,
                    0);

            boolean cameraSoundForced = readCameraSoundForced();
            synchronized (mSettingsLock) {
                final boolean cameraSoundForcedChanged = (cameraSoundForced != mCameraSoundForced);
                mCameraSoundForced = cameraSoundForced;
                if (cameraSoundForcedChanged) {
                    if (!mIsSingleVolume) {
                        synchronized (VolumeStreamState.class) {
                            VolumeStreamState s = mStreamStates[AudioSystem.STREAM_SYSTEM_ENFORCED];
                            if (cameraSoundForced) {
                                s.setAllIndexesToMax();
                                mRingerModeAffectedStreams &=
                                        ~(1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                            } else {
                                s.setAllIndexes(mStreamStates[AudioSystem.STREAM_SYSTEM], TAG);
                                mRingerModeAffectedStreams |=
                                        (1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                            }
                        }
                        // take new state into account for streams muted by ringer mode
                        setRingerModeInt(getRingerModeInternal(), false);
                    }
                    mDeviceBroker.setForceUse_Async(AudioSystem.FOR_SYSTEM,
                            cameraSoundForced ?
                                    AudioSystem.FORCE_SYSTEM_ENFORCED : AudioSystem.FORCE_NONE,
                            "handleConfigurationChanged");
                    sendMsg(mAudioHandler,
                            MSG_SET_ALL_VOLUMES,
                            SENDMSG_QUEUE,
                            0,
                            0,
                            mStreamStates[AudioSystem.STREAM_SYSTEM_ENFORCED], 0);

                }
            }
            mVolumeController.setLayoutDirection(config.getLayoutDirection());
        } catch (Exception e) {
            Log.e(TAG, "Error handling configuration change: ", e);
        }
    }

    @Override
    public void setRingtonePlayer(IRingtonePlayer player) {
        mContext.enforceCallingOrSelfPermission(REMOTE_AUDIO_PLAYBACK, null);
        mRingtonePlayer = player;
    }

    @Override
    public IRingtonePlayer getRingtonePlayer() {
        return mRingtonePlayer;
    }

    @Override
    public AudioRoutesInfo startWatchingRoutes(IAudioRoutesObserver observer) {
        return mDeviceBroker.startWatchingRoutes(observer);
    }


    //==========================================================================================
    // Safe media volume management.
    // MUSIC stream volume level is limited when headphones are connected according to safety
    // regulation. When the user attempts to raise the volume above the limit, a warning is
    // displayed and the user has to acknowlegde before the volume is actually changed.
    // The volume index corresponding to the limit is stored in config_safe_media_volume_index
    // property. Platforms with a different limit must set this property accordingly in their
    // overlay.
    //==========================================================================================

    // mSafeMediaVolumeState indicates whether the media volume is limited over headphones.
    // It is SAFE_MEDIA_VOLUME_NOT_CONFIGURED at boot time until a network service is connected
    // or the configure time is elapsed. It is then set to SAFE_MEDIA_VOLUME_ACTIVE or
    // SAFE_MEDIA_VOLUME_DISABLED according to country option. If not SAFE_MEDIA_VOLUME_DISABLED, it
    // can be set to SAFE_MEDIA_VOLUME_INACTIVE by calling AudioService.disableSafeMediaVolume()
    // (when user opts out).
    private static final int SAFE_MEDIA_VOLUME_NOT_CONFIGURED = 0;
    private static final int SAFE_MEDIA_VOLUME_DISABLED = 1;
    private static final int SAFE_MEDIA_VOLUME_INACTIVE = 2;  // confirmed
    private static final int SAFE_MEDIA_VOLUME_ACTIVE = 3;  // unconfirmed
    private int mSafeMediaVolumeState;
    private final Object mSafeMediaVolumeStateLock = new Object();

    private int mMcc = 0;
    // mSafeMediaVolumeIndex is the cached value of config_safe_media_volume_index property
    private int mSafeMediaVolumeIndex;
    // mSafeUsbMediaVolumeDbfs is the cached value of the config_safe_media_volume_usb_mB
    // property, divided by 100.0.
    private float mSafeUsbMediaVolumeDbfs;
    // mSafeUsbMediaVolumeIndex is used for USB Headsets and is the music volume UI index
    // corresponding to a gain of mSafeUsbMediaVolumeDbfs (defaulting to -37dB) in audio
    // flinger mixer.
    // We remove -22 dBs from the theoretical -15dB to account for the EQ + bass boost
    // amplification when both effects are on with all band gains at maximum.
    // This level corresponds to a loudness of 85 dB SPL for the warning to be displayed when
    // the headset is compliant to EN 60950 with a max loudness of 100dB SPL.
    private int mSafeUsbMediaVolumeIndex;
    // mSafeMediaVolumeDevices lists the devices for which safe media volume is enforced,
    /*package*/ final Set<Integer> mSafeMediaVolumeDevices = new HashSet<>(
            Arrays.asList(AudioSystem.DEVICE_OUT_WIRED_HEADSET,
                    AudioSystem.DEVICE_OUT_WIRED_HEADPHONE, AudioSystem.DEVICE_OUT_USB_HEADSET));
    // mMusicActiveMs is the cumulative time of music activity since safe volume was disabled.
    // When this time reaches UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX, the safe media volume is re-enabled
    // automatically. mMusicActiveMs is rounded to a multiple of MUSIC_ACTIVE_POLL_PERIOD_MS.
    private int mMusicActiveMs;
    private static final int UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX = (20 * 3600 * 1000); // 20 hours
    private static final int MUSIC_ACTIVE_POLL_PERIOD_MS = 60000;  // 1 minute polling interval
    private static final int SAFE_VOLUME_CONFIGURE_TIMEOUT_MS = 30000;  // 30s after boot completed

    private int safeMediaVolumeIndex(int device) {
        if (!mSafeMediaVolumeDevices.contains(device)) {
            return MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC];
        }
        if (device == AudioSystem.DEVICE_OUT_USB_HEADSET) {
            return mSafeUsbMediaVolumeIndex;
        } else {
            return mSafeMediaVolumeIndex;
        }
    }

    private void setSafeMediaVolumeEnabled(boolean on, String caller) {
        synchronized (mSafeMediaVolumeStateLock) {
            if ((mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_NOT_CONFIGURED) &&
                    (mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_DISABLED)) {
                if (on && (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_INACTIVE)) {
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_ACTIVE;
                    enforceSafeMediaVolume(caller);
                } else if (!on && (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE)) {
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_INACTIVE;
                    mMusicActiveMs = 1;  // nonzero = confirmed
                    saveMusicActiveMs();
                    sendMsg(mAudioHandler,
                            MSG_CHECK_MUSIC_ACTIVE,
                            SENDMSG_REPLACE,
                            0,
                            0,
                            caller,
                            MUSIC_ACTIVE_POLL_PERIOD_MS);
                }
            }
        }
    }

    private void enforceSafeMediaVolume(String caller) {
        VolumeStreamState streamState = mStreamStates[AudioSystem.STREAM_MUSIC];
        Set<Integer> devices = mSafeMediaVolumeDevices;

        for (int device : devices) {
            int index = streamState.getIndex(device);
            if (index > safeMediaVolumeIndex(device)) {
                streamState.setIndex(safeMediaVolumeIndex(device), device, caller);
                sendMsg(mAudioHandler,
                        MSG_SET_DEVICE_VOLUME,
                        SENDMSG_QUEUE,
                        device,
                        0,
                        streamState,
                        0);
            }
        }
    }

    private boolean checkSafeMediaVolume(int streamType, int index, int device) {
        synchronized (mSafeMediaVolumeStateLock) {
            if ((mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE)
                    && (mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC)
                    && (mSafeMediaVolumeDevices.contains(device))
                    && (index > safeMediaVolumeIndex(device))) {
                return false;
            }
            return true;
        }
    }

    @Override
    public void disableSafeMediaVolume(String callingPackage) {
        enforceVolumeController("disable the safe media volume");
        synchronized (mSafeMediaVolumeStateLock) {
            setSafeMediaVolumeEnabled(false, callingPackage);
            if (mPendingVolumeCommand != null) {
                onSetStreamVolume(mPendingVolumeCommand.mStreamType,
                                  mPendingVolumeCommand.mIndex,
                                  mPendingVolumeCommand.mFlags,
                                  mPendingVolumeCommand.mDevice,
                                  callingPackage);
                mPendingVolumeCommand = null;
            }
        }
    }

    //==========================================================================================
    // Hdmi CEC:
    // - System audio mode:
    //     If Hdmi Cec's system audio mode is on, audio service should send the volume change
    //     to HdmiControlService so that the audio receiver can handle it.
    // - CEC sink:
    //     OUT_HDMI becomes a "full volume device", i.e. output is always at maximum level
    //     and volume changes won't be taken into account on this device. Volume adjustments
    //     are transformed into key events for the HDMI playback client.
    //==========================================================================================

    @GuardedBy("mHdmiClientLock")
    private void updateHdmiCecSinkLocked(boolean hdmiCecSink) {
        mHdmiCecSink = hdmiCecSink;
        if (mHdmiCecSink) {
            if (DEBUG_VOL) {
                Log.d(TAG, "CEC sink: setting HDMI as full vol device");
            }
            mFullVolumeDevices.add(AudioSystem.DEVICE_OUT_HDMI);
        } else {
            if (DEBUG_VOL) {
                Log.d(TAG, "TV, no CEC: setting HDMI as regular vol device");
            }
            // Android TV devices without CEC service apply software volume on
            // HDMI output
            mFullVolumeDevices.remove(AudioSystem.DEVICE_OUT_HDMI);
        }

        checkAddAllFixedVolumeDevices(AudioSystem.DEVICE_OUT_HDMI,
                "HdmiPlaybackClient.DisplayStatusCallback");
    }

    private class MyHdmiControlStatusChangeListenerCallback
            implements HdmiControlManager.HdmiControlStatusChangeListener {
        public void onStatusChange(boolean isCecEnabled, boolean isCecAvailable) {
            synchronized (mHdmiClientLock) {
                if (mHdmiManager == null) return;
                updateHdmiCecSinkLocked(isCecEnabled ? isCecAvailable : false);
            }
        }
    };

    private final Object mHdmiClientLock = new Object();

    // If HDMI-CEC system audio is supported
    private boolean mHdmiSystemAudioSupported = false;
    // Set only when device is tv.
    @GuardedBy("mHdmiClientLock")
    private HdmiTvClient mHdmiTvClient;
    // true if the device has system feature PackageManager.FEATURE_LEANBACK.
    // cached HdmiControlManager interface
    @GuardedBy("mHdmiClientLock")
    private HdmiControlManager mHdmiManager;
    // Set only when device is a set-top box.
    @GuardedBy("mHdmiClientLock")
    private HdmiPlaybackClient mHdmiPlaybackClient;
    // true if we are a set-top box, an HDMI sink is connected and it supports CEC.
    @GuardedBy("mHdmiClientLock")
    private boolean mHdmiCecSink;
    // Set only when device is an audio system.
    @GuardedBy("mHdmiClientLock")
    private HdmiAudioSystemClient mHdmiAudioSystemClient;

    private MyHdmiControlStatusChangeListenerCallback mHdmiControlStatusChangeListenerCallback =
            new MyHdmiControlStatusChangeListenerCallback();

    @Override
    public int setHdmiSystemAudioSupported(boolean on) {
        int device = AudioSystem.DEVICE_NONE;
        synchronized (mHdmiClientLock) {
            if (mHdmiManager != null) {
                if (mHdmiTvClient == null && mHdmiAudioSystemClient == null) {
                    Log.w(TAG, "Only Hdmi-Cec enabled TV or audio system device supports"
                            + "system audio mode.");
                    return device;
                }
                if (mHdmiSystemAudioSupported != on) {
                    mHdmiSystemAudioSupported = on;
                    final int config = on ? AudioSystem.FORCE_HDMI_SYSTEM_AUDIO_ENFORCED :
                        AudioSystem.FORCE_NONE;
                    mDeviceBroker.setForceUse_Async(AudioSystem.FOR_HDMI_SYSTEM_AUDIO, config,
                            "setHdmiSystemAudioSupported");
                }
                device = getDevicesForStream(AudioSystem.STREAM_MUSIC);
            }
        }
        return device;
    }

    @Override
    public boolean isHdmiSystemAudioSupported() {
        return mHdmiSystemAudioSupported;
    }

    //==========================================================================================
    // Accessibility

    private void initA11yMonitoring() {
        final AccessibilityManager accessibilityManager =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        updateDefaultStreamOverrideDelay(accessibilityManager.isTouchExplorationEnabled());
        updateA11yVolumeAlias(accessibilityManager.isAccessibilityVolumeStreamActive());
        accessibilityManager.addTouchExplorationStateChangeListener(this, null);
        accessibilityManager.addAccessibilityServicesStateChangeListener(this, null);
    }

    //---------------------------------------------------------------------------------
    // A11y: taking touch exploration into account for selecting the default
    //   stream override timeout when adjusting volume
    //---------------------------------------------------------------------------------

    // - STREAM_NOTIFICATION on tablets during this period after a notification stopped
    // - STREAM_RING on phones during this period after a notification stopped
    // - STREAM_MUSIC otherwise

    private static final int DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS = 0;
    private static final int TOUCH_EXPLORE_STREAM_TYPE_OVERRIDE_DELAY_MS = 1000;

    private static int sStreamOverrideDelayMs;

    @Override
    public void onTouchExplorationStateChanged(boolean enabled) {
        updateDefaultStreamOverrideDelay(enabled);
    }

    private void updateDefaultStreamOverrideDelay(boolean touchExploreEnabled) {
        if (touchExploreEnabled) {
            sStreamOverrideDelayMs = TOUCH_EXPLORE_STREAM_TYPE_OVERRIDE_DELAY_MS;
        } else {
            sStreamOverrideDelayMs = DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS;
        }
        if (DEBUG_VOL) Log.d(TAG, "Touch exploration enabled=" + touchExploreEnabled
                + " stream override delay is now " + sStreamOverrideDelayMs + " ms");
    }

    //---------------------------------------------------------------------------------
    // A11y: taking a11y state into account for the handling of a11y prompts volume
    //---------------------------------------------------------------------------------

    private static boolean sIndependentA11yVolume = false;

    // implementation of AccessibilityServicesStateChangeListener
    @Override
    public void onAccessibilityServicesStateChanged(AccessibilityManager accessibilityManager) {
        updateA11yVolumeAlias(accessibilityManager.isAccessibilityVolumeStreamActive());
    }

    private void updateA11yVolumeAlias(boolean a11VolEnabled) {
        if (DEBUG_VOL) Log.d(TAG, "Accessibility volume enabled = " + a11VolEnabled);
        if (sIndependentA11yVolume != a11VolEnabled) {
            sIndependentA11yVolume = a11VolEnabled;
            // update the volume mapping scheme
            updateStreamVolumeAlias(true /*updateVolumes*/, TAG);
            // update the volume controller behavior
            mVolumeController.setA11yMode(sIndependentA11yVolume ?
                    VolumePolicy.A11Y_MODE_INDEPENDENT_A11Y_VOLUME :
                        VolumePolicy.A11Y_MODE_MEDIA_A11Y_VOLUME);
            mVolumeController.postVolumeChanged(AudioManager.STREAM_ACCESSIBILITY, 0);
        }
    }

    //==========================================================================================
    // Camera shutter sound policy.
    // config_camera_sound_forced configuration option in config.xml defines if the camera shutter
    // sound is forced (sound even if the device is in silent mode) or not. This option is false by
    // default and can be overridden by country specific overlay in values-mccXXX/config.xml.
    //==========================================================================================

    // cached value of com.android.internal.R.bool.config_camera_sound_forced
    @GuardedBy("mSettingsLock")
    private boolean mCameraSoundForced;

    // called by android.hardware.Camera to populate CameraInfo.canDisableShutterSound
    public boolean isCameraSoundForced() {
        synchronized (mSettingsLock) {
            return mCameraSoundForced;
        }
    }

    //==========================================================================================
    // AudioService logging and dumpsys
    //==========================================================================================
    static final int LOG_NB_EVENTS_PHONE_STATE = 20;
    static final int LOG_NB_EVENTS_DEVICE_CONNECTION = 30;
    static final int LOG_NB_EVENTS_FORCE_USE = 20;
    static final int LOG_NB_EVENTS_VOLUME = 40;
    static final int LOG_NB_EVENTS_DYN_POLICY = 10;

    final private AudioEventLogger mModeLogger = new AudioEventLogger(LOG_NB_EVENTS_PHONE_STATE,
            "phone state (logged after successful call to AudioSystem.setPhoneState(int, int))");

    // logs for wired + A2DP device connections:
    // - wired: logged before onSetWiredDeviceConnectionState() is executed
    // - A2DP: logged at reception of method call
    /*package*/ static final AudioEventLogger sDeviceLogger = new AudioEventLogger(
            LOG_NB_EVENTS_DEVICE_CONNECTION, "wired/A2DP/hearing aid device connection");

    static final AudioEventLogger sForceUseLogger = new AudioEventLogger(
            LOG_NB_EVENTS_FORCE_USE,
            "force use (logged before setForceUse() is executed)");

    static final AudioEventLogger sVolumeLogger = new AudioEventLogger(LOG_NB_EVENTS_VOLUME,
            "volume changes (logged when command received by AudioService)");

    final private AudioEventLogger mDynPolicyLogger = new AudioEventLogger(LOG_NB_EVENTS_DYN_POLICY,
            "dynamic policy events (logged when command received by AudioService)");

    private static final String[] RINGER_MODE_NAMES = new String[] {
            "SILENT",
            "VIBRATE",
            "NORMAL"
    };

    private void dumpRingerMode(PrintWriter pw) {
        pw.println("\nRinger mode: ");
        pw.println("- mode (internal) = " + RINGER_MODE_NAMES[mRingerMode]);
        pw.println("- mode (external) = " + RINGER_MODE_NAMES[mRingerModeExternal]);
        pw.println("- zen mode:" + Settings.Global.zenModeToString(mNm.getZenMode()));
        dumpRingerModeStreams(pw, "affected", mRingerModeAffectedStreams);
        dumpRingerModeStreams(pw, "muted", mRingerAndZenModeMutedStreams);
        pw.print("- delegate = "); pw.println(mRingerModeDelegate);
    }

    private void dumpRingerModeStreams(PrintWriter pw, String type, int streams) {
        pw.print("- ringer mode "); pw.print(type); pw.print(" streams = 0x");
        pw.print(Integer.toHexString(streams));
        if (streams != 0) {
            pw.print(" (");
            boolean first = true;
            for (int i = 0; i < AudioSystem.STREAM_NAMES.length; i++) {
                final int stream = (1 << i);
                if ((streams & stream) != 0) {
                    if (!first) pw.print(',');
                    pw.print(AudioSystem.STREAM_NAMES[i]);
                    streams &= ~stream;
                    first = false;
                }
            }
            if (streams != 0) {
                if (!first) pw.print(',');
                pw.print(streams);
            }
            pw.print(')');
        }
        pw.println();
    }

    private String dumpDeviceTypes(@NonNull Set<Integer> deviceTypes) {
        Iterator<Integer> it = deviceTypes.iterator();
        if (!it.hasNext()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("0x" + Integer.toHexString(it.next()));
        while (it.hasNext()) {
            sb.append("," + "0x" + Integer.toHexString(it.next()));
        }
        return sb.toString();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        if (mAudioHandler != null) {
            pw.println("\nMessage handler (watch for unhandled messages):");
            mAudioHandler.dump(new PrintWriterPrinter(pw), "  ");
        } else {
            pw.println("\nMessage handler is null");
        }
        mMediaFocusControl.dump(pw);
        dumpStreamStates(pw);
        dumpVolumeGroups(pw);
        dumpRingerMode(pw);
        pw.println("\nAudio routes:");
        pw.print("  mMainType=0x"); pw.println(Integer.toHexString(
                mDeviceBroker.getCurAudioRoutes().mainType));
        pw.print("  mBluetoothName="); pw.println(mDeviceBroker.getCurAudioRoutes().bluetoothName);

        pw.println("\nOther state:");
        pw.print("  mVolumeController="); pw.println(mVolumeController);
        pw.print("  mSafeMediaVolumeState=");
        pw.println(safeMediaVolumeStateToString(mSafeMediaVolumeState));
        pw.print("  mSafeMediaVolumeIndex="); pw.println(mSafeMediaVolumeIndex);
        pw.print("  mSafeUsbMediaVolumeIndex="); pw.println(mSafeUsbMediaVolumeIndex);
        pw.print("  mSafeUsbMediaVolumeDbfs="); pw.println(mSafeUsbMediaVolumeDbfs);
        pw.print("  sIndependentA11yVolume="); pw.println(sIndependentA11yVolume);
        pw.print("  mPendingVolumeCommand="); pw.println(mPendingVolumeCommand);
        pw.print("  mMusicActiveMs="); pw.println(mMusicActiveMs);
        pw.print("  mMcc="); pw.println(mMcc);
        pw.print("  mCameraSoundForced="); pw.println(mCameraSoundForced);
        pw.print("  mHasVibrator="); pw.println(mHasVibrator);
        pw.print("  mVolumePolicy="); pw.println(mVolumePolicy);
        pw.print("  mAvrcpAbsVolSupported=");
        pw.println(mDeviceBroker.isAvrcpAbsoluteVolumeSupported());
        pw.print("  mIsSingleVolume="); pw.println(mIsSingleVolume);
        pw.print("  mUseFixedVolume="); pw.println(mUseFixedVolume);
        pw.print("  mFixedVolumeDevices="); pw.println(dumpDeviceTypes(mFixedVolumeDevices));
        pw.print("  mHdmiCecSink="); pw.println(mHdmiCecSink);
        pw.print("  mHdmiAudioSystemClient="); pw.println(mHdmiAudioSystemClient);
        pw.print("  mHdmiPlaybackClient="); pw.println(mHdmiPlaybackClient);
        pw.print("  mHdmiTvClient="); pw.println(mHdmiTvClient);
        pw.print("  mHdmiSystemAudioSupported="); pw.println(mHdmiSystemAudioSupported);
        pw.print("  mIsCallScreeningModeSupported="); pw.println(mIsCallScreeningModeSupported);

        dumpAudioPolicies(pw);
        mDynPolicyLogger.dump(pw);
        mPlaybackMonitor.dump(pw);
        mRecordMonitor.dump(pw);

        pw.println("\nAudioDeviceBroker:");
        mDeviceBroker.dump(pw, "  ");
        pw.println("\nSoundEffects:");
        mSfxHelper.dump(pw, "  ");

        pw.println("\n");
        pw.println("\nEvent logs:");
        mModeLogger.dump(pw);
        pw.println("\n");
        sDeviceLogger.dump(pw);
        pw.println("\n");
        sForceUseLogger.dump(pw);
        pw.println("\n");
        sVolumeLogger.dump(pw);
        pw.println("\n");
        dumpSupportedSystemUsage(pw);
    }

    private void dumpSupportedSystemUsage(PrintWriter pw) {
        pw.println("Supported System Usages:");
        synchronized (mSupportedSystemUsagesLock) {
            for (int i = 0; i < mSupportedSystemUsages.length; i++) {
                pw.printf("\t%s\n", AudioAttributes.usageToString(mSupportedSystemUsages[i]));
            }
        }
    }

    /**
     * Audio Analytics ids.
     */
    private static final String mAnalyticsId = "audio.service.";
    private static final String mAnalyticsPropEarlyReturn = "earlyReturn";

    private static String safeMediaVolumeStateToString(int state) {
        switch(state) {
            case SAFE_MEDIA_VOLUME_NOT_CONFIGURED: return "SAFE_MEDIA_VOLUME_NOT_CONFIGURED";
            case SAFE_MEDIA_VOLUME_DISABLED: return "SAFE_MEDIA_VOLUME_DISABLED";
            case SAFE_MEDIA_VOLUME_INACTIVE: return "SAFE_MEDIA_VOLUME_INACTIVE";
            case SAFE_MEDIA_VOLUME_ACTIVE: return "SAFE_MEDIA_VOLUME_ACTIVE";
        }
        return null;
    }

    // Inform AudioFlinger of our device's low RAM attribute
    private static void readAndSetLowRamDevice()
    {
        boolean isLowRamDevice = ActivityManager.isLowRamDeviceStatic();
        long totalMemory = 1024 * 1024 * 1024; // 1GB is the default if ActivityManager fails.

        try {
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            ActivityManager.getService().getMemoryInfo(info);
            totalMemory = info.totalMem;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot obtain MemoryInfo from ActivityManager, assume low memory device");
            isLowRamDevice = true;
        }

        final int status = AudioSystem.setLowRamDevice(isLowRamDevice, totalMemory);
        if (status != 0) {
            Log.w(TAG, "AudioFlinger informed of device's low RAM attribute; status " + status);
        }
    }

    private void enforceVolumeController(String action) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                "Only SystemUI can " + action);
    }

    @Override
    public void setVolumeController(final IVolumeController controller) {
        enforceVolumeController("set the volume controller");

        // return early if things are not actually changing
        if (mVolumeController.isSameBinder(controller)) {
            return;
        }

        // dismiss the old volume controller
        mVolumeController.postDismiss();
        if (controller != null) {
            // we are about to register a new controller, listen for its death
            try {
                controller.asBinder().linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        if (mVolumeController.isSameBinder(controller)) {
                            Log.w(TAG, "Current remote volume controller died, unregistering");
                            setVolumeController(null);
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                // noop
            }
        }
        mVolumeController.setController(controller);
        if (DEBUG_VOL) Log.d(TAG, "Volume controller: " + mVolumeController);
    }

    @Override
    public void notifyVolumeControllerVisible(final IVolumeController controller, boolean visible) {
        enforceVolumeController("notify about volume controller visibility");

        // return early if the controller is not current
        if (!mVolumeController.isSameBinder(controller)) {
            return;
        }

        mVolumeController.setVisible(visible);
        if (DEBUG_VOL) Log.d(TAG, "Volume controller visible: " + visible);
    }

    @Override
    public void setVolumePolicy(VolumePolicy policy) {
        enforceVolumeController("set volume policy");
        if (policy != null && !policy.equals(mVolumePolicy)) {
            mVolumePolicy = policy;
            if (DEBUG_VOL) Log.d(TAG, "Volume policy changed: " + mVolumePolicy);
        }
    }

    public static class VolumeController {
        private static final String TAG = "VolumeController";

        private IVolumeController mController;
        private boolean mVisible;
        private long mNextLongPress;
        private int mLongPressTimeout;

        public void setController(IVolumeController controller) {
            mController = controller;
            mVisible = false;
        }

        public void loadSettings(ContentResolver cr) {
            mLongPressTimeout = Settings.Secure.getIntForUser(cr,
                    Settings.Secure.LONG_PRESS_TIMEOUT, 500, UserHandle.USER_CURRENT);
        }

        public boolean suppressAdjustment(int resolvedStream, int flags, boolean isMute) {
            if (isMute) {
                return false;
            }
            boolean suppress = false;
            if (resolvedStream != AudioSystem.STREAM_MUSIC && mController != null) {
                final long now = SystemClock.uptimeMillis();
                if ((flags & AudioManager.FLAG_SHOW_UI) != 0 && !mVisible) {
                    // ui will become visible
                    if (mNextLongPress < now) {
                        mNextLongPress = now + mLongPressTimeout;
                    }
                    suppress = true;
                } else if (mNextLongPress > 0) {  // in a long-press
                    if (now > mNextLongPress) {
                        // long press triggered, no more suppression
                        mNextLongPress = 0;
                    } else {
                        // keep suppressing until the long press triggers
                        suppress = true;
                    }
                }
            }
            return suppress;
        }

        public void setVisible(boolean visible) {
            mVisible = visible;
        }

        public boolean isSameBinder(IVolumeController controller) {
            return Objects.equals(asBinder(), binder(controller));
        }

        public IBinder asBinder() {
            return binder(mController);
        }

        private static IBinder binder(IVolumeController controller) {
            return controller == null ? null : controller.asBinder();
        }

        @Override
        public String toString() {
            return "VolumeController(" + asBinder() + ",mVisible=" + mVisible + ")";
        }

        public void postDisplaySafeVolumeWarning(int flags) {
            if (mController == null)
                return;
            try {
                mController.displaySafeVolumeWarning(flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling displaySafeVolumeWarning", e);
            }
        }

        public void postVolumeChanged(int streamType, int flags) {
            if (mController == null)
                return;
            try {
                mController.volumeChanged(streamType, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling volumeChanged", e);
            }
        }

        public void postMasterMuteChanged(int flags) {
            if (mController == null)
                return;
            try {
                mController.masterMuteChanged(flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling masterMuteChanged", e);
            }
        }

        public void setLayoutDirection(int layoutDirection) {
            if (mController == null)
                return;
            try {
                mController.setLayoutDirection(layoutDirection);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling setLayoutDirection", e);
            }
        }

        public void postDismiss() {
            if (mController == null)
                return;
            try {
                mController.dismiss();
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling dismiss", e);
            }
        }

        public void setA11yMode(int a11yMode) {
            if (mController == null)
                return;
            try {
                mController.setA11yMode(a11yMode);
            } catch (RemoteException e) {
                Log.w(TAG, "Error calling setA11Mode", e);
            }
        }
    }

    /**
     * Interface for system components to get some extra functionality through
     * LocalServices.
     */
    final class AudioServiceInternal extends AudioManagerInternal {
        @Override
        public void setRingerModeDelegate(RingerModeDelegate delegate) {
            mRingerModeDelegate = delegate;
            if (mRingerModeDelegate != null) {
                synchronized (mSettingsLock) {
                    updateRingerAndZenModeAffectedStreams();
                }
                setRingerModeInternal(getRingerModeInternal(), TAG + ".setRingerModeDelegate");
            }
        }

        @Override
        public void adjustSuggestedStreamVolumeForUid(int streamType, int direction, int flags,
                String callingPackage, int uid) {
            // direction and stream type swap here because the public
            // adjustSuggested has a different order than the other methods.
            adjustSuggestedStreamVolume(direction, streamType, flags, callingPackage,
                    callingPackage, uid);
        }

        @Override
        public void adjustStreamVolumeForUid(int streamType, int direction, int flags,
                String callingPackage, int uid) {
            if (direction != AudioManager.ADJUST_SAME) {
                sVolumeLogger.log(new VolumeEvent(VolumeEvent.VOL_ADJUST_VOL_UID, streamType,
                        direction/*val1*/, flags/*val2*/, new StringBuilder(callingPackage)
                        .append(" uid:").append(uid).toString()));
            }
            adjustStreamVolume(streamType, direction, flags, callingPackage,
                    callingPackage, uid);
        }

        @Override
        public void setStreamVolumeForUid(int streamType, int direction, int flags,
                String callingPackage, int uid) {
            setStreamVolume(streamType, direction, flags, callingPackage, callingPackage, uid);
        }

        @Override
        public int getRingerModeInternal() {
            return AudioService.this.getRingerModeInternal();
        }

        @Override
        public void setRingerModeInternal(int ringerMode, String caller) {
            AudioService.this.setRingerModeInternal(ringerMode, caller);
        }

        @Override
        public void silenceRingerModeInternal(String caller) {
            AudioService.this.silenceRingerModeInternal(caller);
        }

        @Override
        public void updateRingerModeAffectedStreamsInternal() {
            synchronized (mSettingsLock) {
                if (updateRingerAndZenModeAffectedStreams()) {
                    setRingerModeInt(getRingerModeInternal(), false);
                }
            }
        }

        @Override
        public void setAccessibilityServiceUids(IntArray uids) {
            synchronized (mAccessibilityServiceUidsLock) {
                if (uids.size() == 0) {
                    mAccessibilityServiceUids = null;
                } else {
                    boolean changed = (mAccessibilityServiceUids == null)
                            || (mAccessibilityServiceUids.length != uids.size());
                    if (!changed) {
                        for (int i = 0; i < mAccessibilityServiceUids.length; i++) {
                            if (uids.get(i) != mAccessibilityServiceUids[i]) {
                                changed = true;
                                break;
                            }
                        }
                    }
                    if (changed) {
                        mAccessibilityServiceUids = uids.toArray();
                    }
                }
                AudioSystem.setA11yServicesUids(mAccessibilityServiceUids);
            }
        }
    }

    //==========================================================================================
    // Audio policy management
    //==========================================================================================
    public String registerAudioPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb,
            boolean hasFocusListener, boolean isFocusPolicy, boolean isTestFocusPolicy,
            boolean isVolumeController, IMediaProjection projection) {
        AudioSystem.setDynamicPolicyCallback(mDynPolicyCallback);

        if (!isPolicyRegisterAllowed(policyConfig,
                                     isFocusPolicy || isTestFocusPolicy || hasFocusListener,
                                     isVolumeController,
                                     projection)) {
            Slog.w(TAG, "Permission denied to register audio policy for pid "
                    + Binder.getCallingPid() + " / uid " + Binder.getCallingUid()
                    + ", need MODIFY_AUDIO_ROUTING or MediaProjection that can project audio");
            return null;
        }

        mDynPolicyLogger.log((new AudioEventLogger.StringEvent("registerAudioPolicy for "
                + pcb.asBinder() + " with config:" + policyConfig)).printLog(TAG));

        String regId = null;
        synchronized (mAudioPolicies) {
            if (mAudioPolicies.containsKey(pcb.asBinder())) {
                Slog.e(TAG, "Cannot re-register policy");
                return null;
            }
            try {
                AudioPolicyProxy app = new AudioPolicyProxy(policyConfig, pcb, hasFocusListener,
                        isFocusPolicy, isTestFocusPolicy, isVolumeController, projection);
                pcb.asBinder().linkToDeath(app, 0/*flags*/);
                regId = app.getRegistrationId();
                mAudioPolicies.put(pcb.asBinder(), app);
            } catch (RemoteException e) {
                // audio policy owner has already died!
                Slog.w(TAG, "Audio policy registration failed, could not link to " + pcb +
                        " binder death", e);
                return null;
            } catch (IllegalStateException e) {
                Slog.w(TAG, "Audio policy registration failed for binder " + pcb, e);
                return null;
            }
        }
        return regId;
    }

    /**
     * Apps with MODIFY_AUDIO_ROUTING can register any policy.
     * Apps with an audio capable MediaProjection are allowed to register a RENDER|LOOPBACK policy
     * as those policy do not modify the audio routing.
     */
    private boolean isPolicyRegisterAllowed(AudioPolicyConfig policyConfig,
                                            boolean hasFocusAccess,
                                            boolean isVolumeController,
                                            IMediaProjection projection) {

        boolean requireValidProjection = false;
        boolean requireCaptureAudioOrMediaOutputPerm = false;
        boolean requireModifyRouting = false;
        ArrayList<AudioMix> voiceCommunicationCaptureMixes = null;


        if (hasFocusAccess || isVolumeController) {
            requireModifyRouting |= true;
        } else if (policyConfig.getMixes().isEmpty()) {
            // An empty policy could be used to lock the focus or add mixes later
            requireModifyRouting |= true;
        }
        for (AudioMix mix : policyConfig.getMixes()) {
            // If mix is requesting privileged capture
            if (mix.getRule().allowPrivilegedPlaybackCapture()) {
                // then it must have CAPTURE_MEDIA_OUTPUT or CAPTURE_AUDIO_OUTPUT permission
                requireCaptureAudioOrMediaOutputPerm |= true;

                // and its format must be low quality enough
                String error = mix.canBeUsedForPrivilegedCapture(mix.getFormat());
                if (error != null) {
                    Log.e(TAG, error);
                    return false;
                }

                // If mix is trying to excplicitly capture USAGE_VOICE_COMMUNICATION
                if (mix.containsMatchAttributeRuleForUsage(
                        AudioAttributes.USAGE_VOICE_COMMUNICATION)) {
                    // then it must have CAPTURE_USAGE_VOICE_COMMUNICATION_OUTPUT permission
                    // Note that for UID, USERID or EXCLDUE rules, the capture will be silenced
                    // in AudioPolicyMix
                    if (voiceCommunicationCaptureMixes == null) {
                        voiceCommunicationCaptureMixes = new ArrayList<AudioMix>();
                    }
                    voiceCommunicationCaptureMixes.add(mix);
                }
            }

            // If mix is RENDER|LOOPBACK, then an audio MediaProjection is enough
            // otherwise MODIFY_AUDIO_ROUTING permission is required
            if (mix.getRouteFlags() == mix.ROUTE_FLAG_LOOP_BACK_RENDER && projection != null) {
                requireValidProjection |= true;
            } else {
                requireModifyRouting |= true;
            }
        }

        if (requireCaptureAudioOrMediaOutputPerm
                && !callerHasPermission(android.Manifest.permission.CAPTURE_MEDIA_OUTPUT)
                && !callerHasPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)) {
            Log.e(TAG, "Privileged audio capture requires CAPTURE_MEDIA_OUTPUT or "
                      + "CAPTURE_AUDIO_OUTPUT system permission");
            return false;
        }

        if (voiceCommunicationCaptureMixes != null && voiceCommunicationCaptureMixes.size() > 0) {
            if (!callerHasPermission(
                    android.Manifest.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT)) {
                Log.e(TAG, "Privileged audio capture for voice communication requires "
                        + "CAPTURE_VOICE_COMMUNICATION_OUTPUT system permission");
                return false;
            }

            // If permission check succeeded, we set the flag in each of the mixing rules
            for (AudioMix mix : voiceCommunicationCaptureMixes) {
                mix.getRule().setVoiceCommunicationCaptureAllowed(true);
            }
        }

        if (requireValidProjection && !canProjectAudio(projection)) {
            return false;
        }

        if (requireModifyRouting
                && !callerHasPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)) {
            Log.e(TAG, "Can not capture audio without MODIFY_AUDIO_ROUTING");
            return false;
        }

        return true;
    }

    private boolean callerHasPermission(String permission) {
        return mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return true if projection is a valid MediaProjection that can project audio. */
    private boolean canProjectAudio(IMediaProjection projection) {
        if (projection == null) {
            Log.e(TAG, "MediaProjection is null");
            return false;
        }

        IMediaProjectionManager projectionService = getProjectionService();
        if (projectionService == null) {
            Log.e(TAG, "Can't get service IMediaProjectionManager");
            return false;
        }

        try {
            if (!projectionService.isValidMediaProjection(projection)) {
                Log.w(TAG, "App passed invalid MediaProjection token");
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call .isValidMediaProjection() on IMediaProjectionManager"
                    + projectionService.asBinder(), e);
            return false;
        }

        try {
            if (!projection.canProjectAudio()) {
                Log.w(TAG, "App passed MediaProjection that can not project audio");
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call .canProjectAudio() on valid IMediaProjection"
                    + projection.asBinder(), e);
            return false;
        }

        return true;
    }

    private IMediaProjectionManager getProjectionService() {
        if (mProjectionService == null) {
            IBinder b = ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE);
            mProjectionService = IMediaProjectionManager.Stub.asInterface(b);
        }
        return mProjectionService;
    }

    /**
     * See {@link AudioManager#unregisterAudioPolicyAsync(AudioPolicy)}
     * Declared oneway
     * @param pcb nullable because on service interface
     */
    public void unregisterAudioPolicyAsync(@Nullable IAudioPolicyCallback pcb) {
        unregisterAudioPolicy(pcb);
    }

    /**
     * See {@link AudioManager#unregisterAudioPolicy(AudioPolicy)}
     * @param pcb nullable because on service interface
     */
    public void unregisterAudioPolicy(@Nullable IAudioPolicyCallback pcb) {
        if (pcb == null) {
            return;
        }
        unregisterAudioPolicyInt(pcb);
    }


    private void unregisterAudioPolicyInt(@NonNull IAudioPolicyCallback pcb) {
        mDynPolicyLogger.log((new AudioEventLogger.StringEvent("unregisterAudioPolicyAsync for "
                + pcb.asBinder()).printLog(TAG)));
        synchronized (mAudioPolicies) {
            AudioPolicyProxy app = mAudioPolicies.remove(pcb.asBinder());
            if (app == null) {
                Slog.w(TAG, "Trying to unregister unknown audio policy for pid "
                        + Binder.getCallingPid() + " / uid " + Binder.getCallingUid());
                return;
            } else {
                pcb.asBinder().unlinkToDeath(app, 0/*flags*/);
            }
            app.release();
        }
        // TODO implement clearing mix attribute matching info in native audio policy
    }

    /**
     * Checks whether caller has MODIFY_AUDIO_ROUTING permission, and the policy is registered.
     * @param errorMsg log warning if permission check failed.
     * @return null if the operation on the audio mixes should be cancelled.
     */
    @GuardedBy("mAudioPolicies")
    private AudioPolicyProxy checkUpdateForPolicy(IAudioPolicyCallback pcb, String errorMsg) {
        // permission check
        final boolean hasPermissionForPolicy =
                (PackageManager.PERMISSION_GRANTED == mContext.checkCallingPermission(
                        android.Manifest.permission.MODIFY_AUDIO_ROUTING));
        if (!hasPermissionForPolicy) {
            Slog.w(TAG, errorMsg + " for pid " +
                    + Binder.getCallingPid() + " / uid "
                    + Binder.getCallingUid() + ", need MODIFY_AUDIO_ROUTING");
            return null;
        }
        // policy registered?
        final AudioPolicyProxy app = mAudioPolicies.get(pcb.asBinder());
        if (app == null) {
            Slog.w(TAG, errorMsg + " for pid " +
                    + Binder.getCallingPid() + " / uid "
                    + Binder.getCallingUid() + ", unregistered policy");
            return null;
        }
        return app;
    }

    public int addMixForPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb) {
        if (DEBUG_AP) { Log.d(TAG, "addMixForPolicy for " + pcb.asBinder()
                + " with config:" + policyConfig); }
        synchronized (mAudioPolicies) {
            final AudioPolicyProxy app =
                    checkUpdateForPolicy(pcb, "Cannot add AudioMix in audio policy");
            if (app == null){
                return AudioManager.ERROR;
            }
            return app.addMixes(policyConfig.getMixes()) == AudioSystem.SUCCESS
                ? AudioManager.SUCCESS : AudioManager.ERROR;
        }
    }

    public int removeMixForPolicy(AudioPolicyConfig policyConfig, IAudioPolicyCallback pcb) {
        if (DEBUG_AP) { Log.d(TAG, "removeMixForPolicy for " + pcb.asBinder()
                + " with config:" + policyConfig); }
        synchronized (mAudioPolicies) {
            final AudioPolicyProxy app =
                    checkUpdateForPolicy(pcb, "Cannot add AudioMix in audio policy");
            if (app == null) {
                return AudioManager.ERROR;
            }
            return app.removeMixes(policyConfig.getMixes()) == AudioSystem.SUCCESS
                ? AudioManager.SUCCESS : AudioManager.ERROR;
        }
    }

    /** see AudioPolicy.setUidDeviceAffinity() */
    public int setUidDeviceAffinity(IAudioPolicyCallback pcb, int uid,
            @NonNull int[] deviceTypes, @NonNull String[] deviceAddresses) {
        if (DEBUG_AP) {
            Log.d(TAG, "setUidDeviceAffinity for " + pcb.asBinder() + " uid:" + uid);
        }
        synchronized (mAudioPolicies) {
            final AudioPolicyProxy app =
                    checkUpdateForPolicy(pcb, "Cannot change device affinity in audio policy");
            if (app == null) {
                return AudioManager.ERROR;
            }
            if (!app.hasMixRoutedToDevices(deviceTypes, deviceAddresses)) {
                return AudioManager.ERROR;
            }
            return app.setUidDeviceAffinities(uid, deviceTypes, deviceAddresses);
        }
    }

    /** see AudioPolicy.setUserIdDeviceAffinity() */
    public int setUserIdDeviceAffinity(IAudioPolicyCallback pcb, int userId,
            @NonNull int[] deviceTypes, @NonNull String[] deviceAddresses) {
        if (DEBUG_AP) {
            Log.d(TAG, "setUserIdDeviceAffinity for " + pcb.asBinder() + " user:" + userId);
        }

        synchronized (mAudioPolicies) {
            final AudioPolicyProxy app =
                    checkUpdateForPolicy(pcb, "Cannot change device affinity in audio policy");
            if (app == null) {
                return AudioManager.ERROR;
            }
            if (!app.hasMixRoutedToDevices(deviceTypes, deviceAddresses)) {
                return AudioManager.ERROR;
            }
            return app.setUserIdDeviceAffinities(userId, deviceTypes, deviceAddresses);
        }
    }

    /** see AudioPolicy.removeUidDeviceAffinity() */
    public int removeUidDeviceAffinity(IAudioPolicyCallback pcb, int uid) {
        if (DEBUG_AP) {
            Log.d(TAG, "removeUidDeviceAffinity for " + pcb.asBinder() + " uid:" + uid);
        }
        synchronized (mAudioPolicies) {
            final AudioPolicyProxy app =
                    checkUpdateForPolicy(pcb, "Cannot remove device affinity in audio policy");
            if (app == null) {
                return AudioManager.ERROR;
            }
            return app.removeUidDeviceAffinities(uid);
        }
    }

    /** see AudioPolicy.removeUserIdDeviceAffinity() */
    public int removeUserIdDeviceAffinity(IAudioPolicyCallback pcb, int userId) {
        if (DEBUG_AP) {
            Log.d(TAG, "removeUserIdDeviceAffinity for " + pcb.asBinder()
                    + " userId:" + userId);
        }
        synchronized (mAudioPolicies) {
            final AudioPolicyProxy app =
                    checkUpdateForPolicy(pcb, "Cannot remove device affinity in audio policy");
            if (app == null) {
                return AudioManager.ERROR;
            }
            return app.removeUserIdDeviceAffinities(userId);
        }
    }

    public int setFocusPropertiesForPolicy(int duckingBehavior, IAudioPolicyCallback pcb) {
        if (DEBUG_AP) Log.d(TAG, "setFocusPropertiesForPolicy() duck behavior=" + duckingBehavior
                + " policy " +  pcb.asBinder());
        synchronized (mAudioPolicies) {
            final AudioPolicyProxy app =
                    checkUpdateForPolicy(pcb, "Cannot change audio policy focus properties");
            if (app == null){
                return AudioManager.ERROR;
            }
            if (!mAudioPolicies.containsKey(pcb.asBinder())) {
                Slog.e(TAG, "Cannot change audio policy focus properties, unregistered policy");
                return AudioManager.ERROR;
            }
            if (duckingBehavior == AudioPolicy.FOCUS_POLICY_DUCKING_IN_POLICY) {
                // is there already one policy managing ducking?
                for (AudioPolicyProxy policy : mAudioPolicies.values()) {
                    if (policy.mFocusDuckBehavior == AudioPolicy.FOCUS_POLICY_DUCKING_IN_POLICY) {
                        Slog.e(TAG, "Cannot change audio policy ducking behavior, already handled");
                        return AudioManager.ERROR;
                    }
                }
            }
            app.mFocusDuckBehavior = duckingBehavior;
            mMediaFocusControl.setDuckingInExtPolicyAvailable(
                    duckingBehavior == AudioPolicy.FOCUS_POLICY_DUCKING_IN_POLICY);
        }
        return AudioManager.SUCCESS;
    }

    /** see AudioManager.hasRegisteredDynamicPolicy */
    public boolean hasRegisteredDynamicPolicy() {
        synchronized (mAudioPolicies) {
            return !mAudioPolicies.isEmpty();
        }
    }

    private final Object mExtVolumeControllerLock = new Object();
    private IAudioPolicyCallback mExtVolumeController;
    private void setExtVolumeController(IAudioPolicyCallback apc) {
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_handleVolumeKeysInWindowManager)) {
            Log.e(TAG, "Cannot set external volume controller: device not set for volume keys" +
                    " handled in PhoneWindowManager");
            return;
        }
        synchronized (mExtVolumeControllerLock) {
            if (mExtVolumeController != null && !mExtVolumeController.asBinder().pingBinder()) {
                Log.e(TAG, "Cannot set external volume controller: existing controller");
            }
            mExtVolumeController = apc;
        }
    }

    private void dumpAudioPolicies(PrintWriter pw) {
        pw.println("\nAudio policies:");
        synchronized (mAudioPolicies) {
            for (AudioPolicyProxy policy : mAudioPolicies.values()) {
                pw.println(policy.toLogFriendlyString());
            }
        }
    }

    //======================
    // Audio policy callbacks from AudioSystem for dynamic policies
    //======================
    private final AudioSystem.DynamicPolicyCallback mDynPolicyCallback =
            new AudioSystem.DynamicPolicyCallback() {
        public void onDynamicPolicyMixStateUpdate(String regId, int state) {
            if (!TextUtils.isEmpty(regId)) {
                sendMsg(mAudioHandler, MSG_DYN_POLICY_MIX_STATE_UPDATE, SENDMSG_QUEUE,
                        state /*arg1*/, 0 /*arg2 ignored*/, regId /*obj*/, 0 /*delay*/);
            }
        }
    };

    private void onDynPolicyMixStateUpdate(String regId, int state) {
        if (DEBUG_AP) Log.d(TAG, "onDynamicPolicyMixStateUpdate("+ regId + ", " + state +")");
        synchronized (mAudioPolicies) {
            for (AudioPolicyProxy policy : mAudioPolicies.values()) {
                for (AudioMix mix : policy.getMixes()) {
                    if (mix.getRegistration().equals(regId)) {
                        try {
                            policy.mPolicyCallback.notifyMixStateUpdate(regId, state);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Can't call notifyMixStateUpdate() on IAudioPolicyCallback "
                                    + policy.mPolicyCallback.asBinder(), e);
                        }
                        return;
                    }
                }
            }
        }
    }

    //======================
    // Audio policy callbacks from AudioSystem for recording configuration updates
    //======================
    private final RecordingActivityMonitor mRecordMonitor;

    public void registerRecordingCallback(IRecordingConfigDispatcher rcdb) {
        final boolean isPrivileged =
                (PackageManager.PERMISSION_GRANTED == mContext.checkCallingPermission(
                        android.Manifest.permission.MODIFY_AUDIO_ROUTING));
        mRecordMonitor.registerRecordingCallback(rcdb, isPrivileged);
    }

    public void unregisterRecordingCallback(IRecordingConfigDispatcher rcdb) {
        mRecordMonitor.unregisterRecordingCallback(rcdb);
    }

    public List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
        final boolean isPrivileged =
                (PackageManager.PERMISSION_GRANTED == mContext.checkCallingPermission(
                        android.Manifest.permission.MODIFY_AUDIO_ROUTING));
        return mRecordMonitor.getActiveRecordingConfigurations(isPrivileged);
    }

    //======================
    // Audio recording state notification from clients
    //======================
    /**
     * Track a recorder provided by the client
     */
    public int trackRecorder(IBinder recorder) {
        return mRecordMonitor.trackRecorder(recorder);
    }

    /**
     * Receive an event from the client about a tracked recorder
     */
    public void recorderEvent(int riid, int event) {
        mRecordMonitor.recorderEvent(riid, event);
    }

    /**
     * Stop tracking the recorder
     */
    public void releaseRecorder(int riid) {
        mRecordMonitor.releaseRecorder(riid);
    }

    public void disableRingtoneSync(final int userId) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "disable sound settings syncing for another profile");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            // Disable the sync setting so the profile uses its own sound settings.
            Settings.Secure.putIntForUser(mContentResolver, Settings.Secure.SYNC_PARENT_SOUNDS,
                    0 /* false */, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    //======================
    // Audio playback notification
    //======================
    private final PlaybackActivityMonitor mPlaybackMonitor;

    public void registerPlaybackCallback(IPlaybackConfigDispatcher pcdb) {
        final boolean isPrivileged =
                (PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MODIFY_AUDIO_ROUTING));
        mPlaybackMonitor.registerPlaybackCallback(pcdb, isPrivileged);
    }

    public void unregisterPlaybackCallback(IPlaybackConfigDispatcher pcdb) {
        mPlaybackMonitor.unregisterPlaybackCallback(pcdb);
    }

    public List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
        final boolean isPrivileged =
                (PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MODIFY_AUDIO_ROUTING));
        return mPlaybackMonitor.getActivePlaybackConfigurations(isPrivileged);
    }

    public int trackPlayer(PlayerBase.PlayerIdCard pic) {
        if (pic != null && pic.mAttributes != null) {
            validateAudioAttributesUsage(pic.mAttributes);
        }
        return mPlaybackMonitor.trackPlayer(pic);
    }

    public void playerAttributes(int piid, AudioAttributes attr) {
        if (attr != null) {
            validateAudioAttributesUsage(attr);
        }
        mPlaybackMonitor.playerAttributes(piid, attr, Binder.getCallingUid());
    }

    public void playerEvent(int piid, int event) {
        mPlaybackMonitor.playerEvent(piid, event, Binder.getCallingUid());
    }

    public void playerHasOpPlayAudio(int piid, boolean hasOpPlayAudio) {
        mPlaybackMonitor.playerHasOpPlayAudio(piid, hasOpPlayAudio, Binder.getCallingUid());
    }

    public void releasePlayer(int piid) {
        mPlaybackMonitor.releasePlayer(piid, Binder.getCallingUid());
    }

    /**
     * Specifies whether the audio played by this app may or may not be captured by other apps or
     * the system.
     *
     * @param capturePolicy one of
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_ALL},
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_SYSTEM},
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_NONE}.
     * @return AudioSystem.AUDIO_STATUS_OK if set allowed capture policy succeed.
     * @throws IllegalArgumentException if the argument is not a valid value.
     */
    public int setAllowedCapturePolicy(int capturePolicy) {
        int callingUid = Binder.getCallingUid();
        int flags = AudioAttributes.capturePolicyToFlags(capturePolicy, 0x0);
        final long identity = Binder.clearCallingIdentity();
        synchronized (mPlaybackMonitor) {
            int result = AudioSystem.setAllowedCapturePolicy(callingUid, flags);
            if (result == AudioSystem.AUDIO_STATUS_OK) {
                mPlaybackMonitor.setAllowedCapturePolicy(callingUid, capturePolicy);
            }
            Binder.restoreCallingIdentity(identity);
            return result;
        }
    }

    /**
     * Return the capture policy.
     * @return the cached capture policy for the calling uid.
     */
    public int getAllowedCapturePolicy() {
        int callingUid = Binder.getCallingUid();
        final long identity = Binder.clearCallingIdentity();
        int capturePolicy = mPlaybackMonitor.getAllowedCapturePolicy(callingUid);
        Binder.restoreCallingIdentity(identity);
        return capturePolicy;
    }

    //======================
    // Audio device management
    //======================
    private final AudioDeviceBroker mDeviceBroker;

    //======================
    // Audio policy proxy
    //======================
    private static final class AudioDeviceArray {
        final @NonNull int[] mDeviceTypes;
        final @NonNull String[] mDeviceAddresses;
        AudioDeviceArray(@NonNull int[] types,  @NonNull String[] addresses) {
            mDeviceTypes = types;
            mDeviceAddresses = addresses;
        }
    }

    /**
     * This internal class inherits from AudioPolicyConfig, each instance contains all the
     * mixes of an AudioPolicy and their configurations.
     */
    public class AudioPolicyProxy extends AudioPolicyConfig implements IBinder.DeathRecipient {
        private static final String TAG = "AudioPolicyProxy";
        final IAudioPolicyCallback mPolicyCallback;
        final boolean mHasFocusListener;
        final boolean mIsVolumeController;
        final HashMap<Integer, AudioDeviceArray> mUidDeviceAffinities =
                new HashMap<Integer, AudioDeviceArray>();

        final HashMap<Integer, AudioDeviceArray> mUserIdDeviceAffinities =
                new HashMap<>();

        final IMediaProjection mProjection;
        private final class UnregisterOnStopCallback extends IMediaProjectionCallback.Stub {
            public void onStop() {
                unregisterAudioPolicyAsync(mPolicyCallback);
            }
        };
        UnregisterOnStopCallback mProjectionCallback;

        /**
         * Audio focus ducking behavior for an audio policy.
         * This variable reflects the value that was successfully set in
         * {@link AudioService#setFocusPropertiesForPolicy(int, IAudioPolicyCallback)}. This
         * implies that a value of FOCUS_POLICY_DUCKING_IN_POLICY means the corresponding policy
         * is handling ducking for audio focus.
         */
        int mFocusDuckBehavior = AudioPolicy.FOCUS_POLICY_DUCKING_DEFAULT;
        boolean mIsFocusPolicy = false;
        boolean mIsTestFocusPolicy = false;

        AudioPolicyProxy(AudioPolicyConfig config, IAudioPolicyCallback token,
                boolean hasFocusListener, boolean isFocusPolicy, boolean isTestFocusPolicy,
                boolean isVolumeController, IMediaProjection projection) {
            super(config);
            setRegistration(new String(config.hashCode() + ":ap:" + mAudioPolicyCounter++));
            mPolicyCallback = token;
            mHasFocusListener = hasFocusListener;
            mIsVolumeController = isVolumeController;
            mProjection = projection;
            if (mHasFocusListener) {
                mMediaFocusControl.addFocusFollower(mPolicyCallback);
                // can only ever be true if there is a focus listener
                if (isFocusPolicy) {
                    mIsFocusPolicy = true;
                    mIsTestFocusPolicy = isTestFocusPolicy;
                    mMediaFocusControl.setFocusPolicy(mPolicyCallback, mIsTestFocusPolicy);
                }
            }
            if (mIsVolumeController) {
                setExtVolumeController(mPolicyCallback);
            }
            if (mProjection != null) {
                mProjectionCallback = new UnregisterOnStopCallback();
                try {
                    mProjection.registerCallback(mProjectionCallback);
                } catch (RemoteException e) {
                    release();
                    throw new IllegalStateException("MediaProjection callback registration failed, "
                            + "could not link to " + projection + " binder death", e);
                }
            }
            int status = connectMixes();
            if (status != AudioSystem.SUCCESS) {
                release();
                throw new IllegalStateException("Could not connect mix, error: " + status);
            }
        }

        public void binderDied() {
            Log.i(TAG, "audio policy " + mPolicyCallback + " died");
            release();
        }

        String getRegistrationId() {
            return getRegistration();
        }

        void release() {
            if (mIsFocusPolicy) {
                mMediaFocusControl.unsetFocusPolicy(mPolicyCallback, mIsTestFocusPolicy);
            }
            if (mFocusDuckBehavior == AudioPolicy.FOCUS_POLICY_DUCKING_IN_POLICY) {
                mMediaFocusControl.setDuckingInExtPolicyAvailable(false);
            }
            if (mHasFocusListener) {
                mMediaFocusControl.removeFocusFollower(mPolicyCallback);
            }
            if (mProjectionCallback != null) {
                try {
                    mProjection.unregisterCallback(mProjectionCallback);
                } catch (RemoteException e) {
                    Log.e(TAG, "Fail to unregister Audiopolicy callback from MediaProjection");
                }
            }
            if (mIsVolumeController) {
                synchronized (mExtVolumeControllerLock) {
                    mExtVolumeController = null;
                }
            }
            final long identity = Binder.clearCallingIdentity();
            AudioSystem.registerPolicyMixes(mMixes, false);
            Binder.restoreCallingIdentity(identity);
            synchronized (mAudioPolicies) {
                mAudioPolicies.remove(mPolicyCallback.asBinder());
            }
            try {
                mPolicyCallback.notifyUnregistration();
            } catch (RemoteException e) { }
        }

        boolean hasMixAffectingUsage(int usage, int excludedFlags) {
            for (AudioMix mix : mMixes) {
                if (mix.isAffectingUsage(usage)
                        && ((mix.getRouteFlags() & excludedFlags) != excludedFlags)) {
                    return true;
                }
            }
            return false;
        }

        // Verify all the devices in the array are served by mixes defined in this policy
        boolean hasMixRoutedToDevices(@NonNull int[] deviceTypes,
                @NonNull String[] deviceAddresses) {
            for (int i = 0; i < deviceTypes.length; i++) {
                boolean hasDevice = false;
                for (AudioMix mix : mMixes) {
                    // this will check both that the mix has ROUTE_FLAG_RENDER and the device
                    // is reached by this mix
                    if (mix.isRoutedToDevice(deviceTypes[i], deviceAddresses[i])) {
                        hasDevice = true;
                        break;
                    }
                }
                if (!hasDevice) {
                    return false;
                }
            }
            return true;
        }

        int addMixes(@NonNull ArrayList<AudioMix> mixes) {
            // TODO optimize to not have to unregister the mixes already in place
            synchronized (mMixes) {
                AudioSystem.registerPolicyMixes(mMixes, false);
                this.add(mixes);
                return AudioSystem.registerPolicyMixes(mMixes, true);
            }
        }

        int removeMixes(@NonNull ArrayList<AudioMix> mixes) {
            // TODO optimize to not have to unregister the mixes already in place
            synchronized (mMixes) {
                AudioSystem.registerPolicyMixes(mMixes, false);
                this.remove(mixes);
                return AudioSystem.registerPolicyMixes(mMixes, true);
            }
        }

        @AudioSystem.AudioSystemError int connectMixes() {
            final long identity = Binder.clearCallingIdentity();
            int status = AudioSystem.registerPolicyMixes(mMixes, true);
            Binder.restoreCallingIdentity(identity);
            return status;
        }

        int setUidDeviceAffinities(int uid, @NonNull int[] types, @NonNull String[] addresses) {
            final Integer Uid = new Integer(uid);
            if (mUidDeviceAffinities.remove(Uid) != null) {
                if (removeUidDeviceAffinitiesFromSystem(uid) != AudioSystem.SUCCESS) {
                    Log.e(TAG, "AudioSystem. removeUidDeviceAffinities(" + uid + ") failed, "
                            + " cannot call AudioSystem.setUidDeviceAffinities");
                    return AudioManager.ERROR;
                }
            }
            AudioDeviceArray deviceArray = new AudioDeviceArray(types, addresses);
            if (setUidDeviceAffinitiesOnSystem(uid, deviceArray) == AudioSystem.SUCCESS) {
                mUidDeviceAffinities.put(Uid, deviceArray);
                return AudioManager.SUCCESS;
            }
            Log.e(TAG, "AudioSystem. setUidDeviceAffinities(" + uid + ") failed");
            return AudioManager.ERROR;
        }

        int removeUidDeviceAffinities(int uid) {
            if (mUidDeviceAffinities.remove(new Integer(uid)) != null) {
                if (removeUidDeviceAffinitiesFromSystem(uid) == AudioSystem.SUCCESS) {
                    return AudioManager.SUCCESS;
                }
            }
            Log.e(TAG, "AudioSystem. removeUidDeviceAffinities failed");
            return AudioManager.ERROR;
        }

        @AudioSystem.AudioSystemError private int removeUidDeviceAffinitiesFromSystem(int uid) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return AudioSystem.removeUidDeviceAffinities(uid);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @AudioSystem.AudioSystemError private int setUidDeviceAffinitiesOnSystem(int uid,
                AudioDeviceArray deviceArray) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return AudioSystem.setUidDeviceAffinities(uid, deviceArray.mDeviceTypes,
                        deviceArray.mDeviceAddresses);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        int setUserIdDeviceAffinities(int userId,
                @NonNull int[] types, @NonNull String[] addresses) {
            final Integer UserId = new Integer(userId);
            if (mUserIdDeviceAffinities.remove(UserId) != null) {
                if (removeUserIdDeviceAffinitiesFromSystem(userId) != AudioSystem.SUCCESS) {
                    Log.e(TAG, "AudioSystem. removeUserIdDeviceAffinities("
                            + UserId + ") failed, "
                            + " cannot call AudioSystem.setUserIdDeviceAffinities");
                    return AudioManager.ERROR;
                }
            }
            AudioDeviceArray audioDeviceArray = new AudioDeviceArray(types, addresses);
            if (setUserIdDeviceAffinitiesOnSystem(userId, audioDeviceArray)
                    == AudioSystem.SUCCESS) {
                mUserIdDeviceAffinities.put(UserId, audioDeviceArray);
                return AudioManager.SUCCESS;
            }
            Log.e(TAG, "AudioSystem.setUserIdDeviceAffinities(" + userId + ") failed");
            return AudioManager.ERROR;
        }

        int removeUserIdDeviceAffinities(int userId) {
            if (mUserIdDeviceAffinities.remove(new Integer(userId)) != null) {
                if (removeUserIdDeviceAffinitiesFromSystem(userId) == AudioSystem.SUCCESS) {
                    return AudioManager.SUCCESS;
                }
            }
            Log.e(TAG, "AudioSystem.removeUserIdDeviceAffinities failed");
            return AudioManager.ERROR;
        }

        @AudioSystem.AudioSystemError private int removeUserIdDeviceAffinitiesFromSystem(
                @UserIdInt int userId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return AudioSystem.removeUserIdDeviceAffinities(userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @AudioSystem.AudioSystemError private int setUserIdDeviceAffinitiesOnSystem(
                @UserIdInt int userId, AudioDeviceArray deviceArray) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return AudioSystem.setUserIdDeviceAffinities(userId, deviceArray.mDeviceTypes,
                        deviceArray.mDeviceAddresses);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @AudioSystem.AudioSystemError int setupDeviceAffinities() {
            for (Map.Entry<Integer, AudioDeviceArray> uidEntry : mUidDeviceAffinities.entrySet()) {
                int uidStatus = removeUidDeviceAffinitiesFromSystem(uidEntry.getKey());
                if (uidStatus != AudioSystem.SUCCESS) {
                    Log.e(TAG,
                            "setupDeviceAffinities failed to remove device affinity for uid "
                                    + uidEntry.getKey());
                    return uidStatus;
                }
                uidStatus = setUidDeviceAffinitiesOnSystem(uidEntry.getKey(), uidEntry.getValue());
                if (uidStatus != AudioSystem.SUCCESS) {
                    Log.e(TAG,
                            "setupDeviceAffinities failed to set device affinity for uid "
                                    + uidEntry.getKey());
                    return uidStatus;
                }
            }

            for (Map.Entry<Integer, AudioDeviceArray> userIdEntry :
                    mUserIdDeviceAffinities.entrySet()) {
                int userIdStatus = removeUserIdDeviceAffinitiesFromSystem(userIdEntry.getKey());
                if (userIdStatus != AudioSystem.SUCCESS) {
                    Log.e(TAG,
                            "setupDeviceAffinities failed to remove device affinity for userId "
                                    + userIdEntry.getKey());
                    return userIdStatus;
                }
                userIdStatus = setUserIdDeviceAffinitiesOnSystem(userIdEntry.getKey(),
                                userIdEntry.getValue());
                if (userIdStatus != AudioSystem.SUCCESS) {
                    Log.e(TAG,
                            "setupDeviceAffinities failed to set device affinity for userId "
                                    + userIdEntry.getKey());
                    return userIdStatus;
                }
            }
            return AudioSystem.SUCCESS;
        }

        /** @return human readable debug informations summarizing the state of the object. */
        public String toLogFriendlyString() {
            String textDump = super.toLogFriendlyString();
            textDump += " Uid Device Affinities:\n";
            String spacer = "     ";
            textDump += logFriendlyAttributeDeviceArrayMap("Uid",
                    mUidDeviceAffinities, spacer);
            textDump += " UserId Device Affinities:\n";
            textDump += logFriendlyAttributeDeviceArrayMap("UserId",
                    mUserIdDeviceAffinities, spacer);
            textDump += " Proxy:\n";
            textDump += "   is focus policy= " + mIsFocusPolicy + "\n";
            if (mIsFocusPolicy) {
                textDump += "     focus duck behaviour= " + mFocusDuckBehavior + "\n";
                textDump += "     is test focus policy= " + mIsTestFocusPolicy + "\n";
                textDump += "     has focus listener= " + mHasFocusListener  + "\n";
            }
            textDump += "   media projection= " + mProjection + "\n";
            return textDump;
        }

        private String logFriendlyAttributeDeviceArrayMap(String attribute,
                Map<Integer, AudioDeviceArray> map, String spacer) {
            final StringBuilder stringBuilder = new StringBuilder();
            for (Map.Entry<Integer, AudioDeviceArray> mapEntry : map.entrySet()) {
                stringBuilder.append(spacer).append(attribute).append(": ")
                        .append(mapEntry.getKey()).append("\n");
                AudioDeviceArray deviceArray = mapEntry.getValue();
                String deviceSpacer = spacer + "   ";
                for (int i = 0; i < deviceArray.mDeviceTypes.length; i++) {
                    stringBuilder.append(deviceSpacer).append("Type: 0x")
                            .append(Integer.toHexString(deviceArray.mDeviceTypes[i]))
                            .append(" Address: ").append(deviceArray.mDeviceAddresses[i])
                                    .append("\n");
                }
            }
            return stringBuilder.toString();
        }
    };

    //======================
    // Audio policy: focus
    //======================
    /**  */
    public int dispatchFocusChange(AudioFocusInfo afi, int focusChange, IAudioPolicyCallback pcb) {
        if (afi == null) {
            throw new IllegalArgumentException("Illegal null AudioFocusInfo");
        }
        if (pcb == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy callback");
        }
        synchronized (mAudioPolicies) {
            if (!mAudioPolicies.containsKey(pcb.asBinder())) {
                throw new IllegalStateException("Unregistered AudioPolicy for focus dispatch");
            }
            return mMediaFocusControl.dispatchFocusChange(afi, focusChange);
        }
    }

    public void setFocusRequestResultFromExtPolicy(AudioFocusInfo afi, int requestResult,
            IAudioPolicyCallback pcb) {
        if (afi == null) {
            throw new IllegalArgumentException("Illegal null AudioFocusInfo");
        }
        if (pcb == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy callback");
        }
        synchronized (mAudioPolicies) {
            if (!mAudioPolicies.containsKey(pcb.asBinder())) {
                throw new IllegalStateException("Unregistered AudioPolicy for external focus");
            }
            mMediaFocusControl.setFocusRequestResultFromExtPolicy(afi, requestResult);
        }
    }


    //======================
    // Audioserver state displatch
    //======================
    private class AsdProxy implements IBinder.DeathRecipient {
        private final IAudioServerStateDispatcher mAsd;

        AsdProxy(IAudioServerStateDispatcher asd) {
            mAsd = asd;
        }

        public void binderDied() {
            synchronized (mAudioServerStateListeners) {
                mAudioServerStateListeners.remove(mAsd.asBinder());
            }
        }

        IAudioServerStateDispatcher callback() {
            return mAsd;
        }
    }

    private HashMap<IBinder, AsdProxy> mAudioServerStateListeners =
            new HashMap<IBinder, AsdProxy>();

    private void checkMonitorAudioServerStatePermission() {
        if (!(mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED ||
              mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_AUDIO_ROUTING) ==
                PackageManager.PERMISSION_GRANTED)) {
            throw new SecurityException("Not allowed to monitor audioserver state");
        }
    }

    public void registerAudioServerStateDispatcher(IAudioServerStateDispatcher asd) {
        checkMonitorAudioServerStatePermission();
        synchronized (mAudioServerStateListeners) {
            if (mAudioServerStateListeners.containsKey(asd.asBinder())) {
                Slog.w(TAG, "Cannot re-register audio server state dispatcher");
                return;
            }
            AsdProxy asdp = new AsdProxy(asd);
            try {
                asd.asBinder().linkToDeath(asdp, 0/*flags*/);
            } catch (RemoteException e) {

            }
            mAudioServerStateListeners.put(asd.asBinder(), asdp);
        }
    }

    public void unregisterAudioServerStateDispatcher(IAudioServerStateDispatcher asd) {
        checkMonitorAudioServerStatePermission();
        synchronized (mAudioServerStateListeners) {
            AsdProxy asdp = mAudioServerStateListeners.remove(asd.asBinder());
            if (asdp == null) {
                Slog.w(TAG, "Trying to unregister unknown audioserver state dispatcher for pid "
                        + Binder.getCallingPid() + " / uid " + Binder.getCallingUid());
                return;
            } else {
                asd.asBinder().unlinkToDeath(asdp, 0/*flags*/);
            }
        }
    }

    public boolean isAudioServerRunning() {
        checkMonitorAudioServerStatePermission();
        return (AudioSystem.checkAudioFlinger() == AudioSystem.AUDIO_STATUS_OK);
    }

    //======================
    // Audio HAL process dump
    //======================

    private static final String AUDIO_HAL_SERVICE_PREFIX = "android.hardware.audio";

    private Set<Integer> getAudioHalPids() {
        try {
            IServiceManager serviceManager = IServiceManager.getService();
            ArrayList<IServiceManager.InstanceDebugInfo> dump =
                    serviceManager.debugDump();
            HashSet<Integer> pids = new HashSet<>();
            for (IServiceManager.InstanceDebugInfo info : dump) {
                if (info.pid != IServiceManager.PidConstant.NO_PID
                        && info.interfaceName != null
                        && info.interfaceName.startsWith(AUDIO_HAL_SERVICE_PREFIX)) {
                    pids.add(info.pid);
                }
            }
            return pids;
        } catch (RemoteException e) {
            return new HashSet<Integer>();
        }
    }

    private void updateAudioHalPids() {
        Set<Integer> pidsSet = getAudioHalPids();
        if (pidsSet.isEmpty()) {
            Slog.w(TAG, "Could not retrieve audio HAL service pids");
            return;
        }
        int[] pidsArray = pidsSet.stream().mapToInt(Integer::intValue).toArray();
        AudioSystem.setAudioHalPids(pidsArray);
    }

    //======================
    // misc
    //======================
    private final HashMap<IBinder, AudioPolicyProxy> mAudioPolicies =
            new HashMap<IBinder, AudioPolicyProxy>();
    @GuardedBy("mAudioPolicies")
    private int mAudioPolicyCounter = 0;

    //======================
    // Helper functions for full and fixed volume device
    //======================
    private boolean isFixedVolumeDevice(int deviceType) {
        if (deviceType == AudioSystem.DEVICE_OUT_REMOTE_SUBMIX
                && mRecordMonitor.isLegacyRemoteSubmixActive()) {
            return false;
        }
        return mFixedVolumeDevices.contains(deviceType);
    }

    private boolean isFullVolumeDevice(int deviceType) {
        if (deviceType == AudioSystem.DEVICE_OUT_REMOTE_SUBMIX
                && mRecordMonitor.isLegacyRemoteSubmixActive()) {
            return false;
        }
        return mFullVolumeDevices.contains(deviceType);
    }
}
