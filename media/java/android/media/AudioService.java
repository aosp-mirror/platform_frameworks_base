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

import static android.Manifest.permission.REMOTE_AUDIO_PLAYBACK;
import static android.media.AudioManager.RINGER_MODE_NORMAL;
import static android.media.AudioManager.RINGER_MODE_SILENT;
import static android.media.AudioManager.RINGER_MODE_VIBRATE;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.PendingIntent.OnFinished;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.System;
import android.speech.RecognizerIntent;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.VolumePanel;

import com.android.internal.telephony.ITelephony;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;

/**
 * The implementation of the volume manager service.
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
public class AudioService extends IAudioService.Stub implements OnFinished {

    private static final String TAG = "AudioService";

    /** Debug remote control client/display feature */
    protected static final boolean DEBUG_RC = false;
    /** Debug volumes */
    protected static final boolean DEBUG_VOL = false;

    /** How long to delay before persisting a change in volume/ringer mode. */
    private static final int PERSIST_DELAY = 500;

    private Context mContext;
    private ContentResolver mContentResolver;
    private boolean mVoiceCapable;

    /** The UI */
    private VolumePanel mVolumePanel;

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
    private static final int MSG_PERSIST_MASTER_VOLUME = 2;
    private static final int MSG_PERSIST_RINGER_MODE = 3;
    private static final int MSG_MEDIA_SERVER_DIED = 4;
    private static final int MSG_MEDIA_SERVER_STARTED = 5;
    private static final int MSG_PLAY_SOUND_EFFECT = 6;
    private static final int MSG_BTA2DP_DOCK_TIMEOUT = 7;
    private static final int MSG_LOAD_SOUND_EFFECTS = 8;
    private static final int MSG_SET_FORCE_USE = 9;
    private static final int MSG_PERSIST_MEDIABUTTONRECEIVER = 10;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 11;
    private static final int MSG_RCDISPLAY_CLEAR = 12;
    private static final int MSG_RCDISPLAY_UPDATE = 13;
    private static final int MSG_SET_ALL_VOLUMES = 14;
    private static final int MSG_PERSIST_MASTER_VOLUME_MUTE = 15;
    private static final int MSG_REPORT_NEW_ROUTES = 16;
    private static final int MSG_REEVALUATE_REMOTE = 17;
    private static final int MSG_RCC_NEW_PLAYBACK_INFO = 18;
    private static final int MSG_RCC_NEW_VOLUME_OBS = 19;
    private static final int MSG_SET_FORCE_BT_A2DP_USE = 20;
    // start of messages handled under wakelock
    //   these messages can only be queued, i.e. sent with queueMsgUnderWakeLock(),
    //   and not with sendMsg(..., ..., SENDMSG_QUEUE, ...)
    private static final int MSG_SET_WIRED_DEVICE_CONNECTION_STATE = 21;
    private static final int MSG_SET_A2DP_CONNECTION_STATE = 22;
    // end of messages handled under wakelock
    private static final int MSG_SET_RSX_CONNECTION_STATE = 23; // change remote submix connection
    private static final int MSG_CHECK_MUSIC_ACTIVE = 24;
    private static final int MSG_BROADCAST_AUDIO_BECOMING_NOISY = 25;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME = 26;
    private static final int MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED = 27;

    // flags for MSG_PERSIST_VOLUME indicating if current and/or last audible volume should be
    // persisted
    private static final int PERSIST_CURRENT = 0x1;
    private static final int PERSIST_LAST_AUDIBLE = 0x2;

    private static final int BTA2DP_DOCK_TIMEOUT_MILLIS = 8000;
    // Timeout for connection to bluetooth headset service
    private static final int BT_HEADSET_CNCT_TIMEOUT_MS = 3000;

    /** @see AudioSystemThread */
    private AudioSystemThread mAudioSystemThread;
    /** @see AudioHandler */
    private AudioHandler mAudioHandler;
    /** @see VolumeStreamState */
    private VolumeStreamState[] mStreamStates;
    private SettingsObserver mSettingsObserver;

    private int mMode;
    // protects mRingerMode
    private final Object mSettingsLock = new Object();

    private boolean mMediaServerOk;

    private SoundPool mSoundPool;
    private final Object mSoundEffectsLock = new Object();
    private static final int NUM_SOUNDPOOL_CHANNELS = 4;

    // Internally master volume is a float in the 0.0 - 1.0 range,
    // but to support integer based AudioManager API we translate it to 0 - 100
    private static final int MAX_MASTER_VOLUME = 100;

    // Maximum volume adjust steps allowed in a single batch call.
    private static final int MAX_BATCH_VOLUME_ADJUST_STEPS = 4;

    /* Sound effect file names  */
    private static final String SOUND_EFFECTS_PATH = "/media/audio/ui/";
    private static final String[] SOUND_EFFECT_FILES = new String[] {
        "Effect_Tick.ogg",
        "KeypressStandard.ogg",
        "KeypressSpacebar.ogg",
        "KeypressDelete.ogg",
        "KeypressReturn.ogg"
    };

    /* Sound effect file name mapping sound effect id (AudioManager.FX_xxx) to
     * file index in SOUND_EFFECT_FILES[] (first column) and indicating if effect
     * uses soundpool (second column) */
    private final int[][] SOUND_EFFECT_FILES_MAP = new int[][] {
        {0, -1},  // FX_KEY_CLICK
        {0, -1},  // FX_FOCUS_NAVIGATION_UP
        {0, -1},  // FX_FOCUS_NAVIGATION_DOWN
        {0, -1},  // FX_FOCUS_NAVIGATION_LEFT
        {0, -1},  // FX_FOCUS_NAVIGATION_RIGHT
        {1, -1},  // FX_KEYPRESS_STANDARD
        {2, -1},  // FX_KEYPRESS_SPACEBAR
        {3, -1},  // FX_FOCUS_DELETE
        {4, -1}   // FX_FOCUS_RETURN
    };

   /** @hide Maximum volume index values for audio streams */
    private final int[] MAX_STREAM_VOLUME = new int[] {
        5,  // STREAM_VOICE_CALL
        7,  // STREAM_SYSTEM
        7,  // STREAM_RING
        15, // STREAM_MUSIC
        7,  // STREAM_ALARM
        7,  // STREAM_NOTIFICATION
        15, // STREAM_BLUETOOTH_SCO
        7,  // STREAM_SYSTEM_ENFORCED
        15, // STREAM_DTMF
        15  // STREAM_TTS
    };
    /* mStreamVolumeAlias[] indicates for each stream if it uses the volume settings
     * of another stream: This avoids multiplying the volume settings for hidden
     * stream types that follow other stream behavior for volume settings
     * NOTE: do not create loops in aliases!
     * Some streams alias to different streams according to device category (phone or tablet) or
     * use case (in call s off call...).See updateStreamVolumeAlias() for more details
     *  mStreamVolumeAlias contains the default aliases for a voice capable device (phone) and
     *  STREAM_VOLUME_ALIAS_NON_VOICE for a non voice capable device (tablet).*/
    private final int[] STREAM_VOLUME_ALIAS = new int[] {
        AudioSystem.STREAM_VOICE_CALL,      // STREAM_VOICE_CALL
        AudioSystem.STREAM_RING,            // STREAM_SYSTEM
        AudioSystem.STREAM_RING,            // STREAM_RING
        AudioSystem.STREAM_MUSIC,           // STREAM_MUSIC
        AudioSystem.STREAM_ALARM,           // STREAM_ALARM
        AudioSystem.STREAM_RING,            // STREAM_NOTIFICATION
        AudioSystem.STREAM_BLUETOOTH_SCO,   // STREAM_BLUETOOTH_SCO
        AudioSystem.STREAM_RING,            // STREAM_SYSTEM_ENFORCED
        AudioSystem.STREAM_RING,            // STREAM_DTMF
        AudioSystem.STREAM_MUSIC            // STREAM_TTS
    };
    private final int[] STREAM_VOLUME_ALIAS_NON_VOICE = new int[] {
        AudioSystem.STREAM_VOICE_CALL,      // STREAM_VOICE_CALL
        AudioSystem.STREAM_MUSIC,           // STREAM_SYSTEM
        AudioSystem.STREAM_RING,            // STREAM_RING
        AudioSystem.STREAM_MUSIC,           // STREAM_MUSIC
        AudioSystem.STREAM_ALARM,           // STREAM_ALARM
        AudioSystem.STREAM_RING,            // STREAM_NOTIFICATION
        AudioSystem.STREAM_BLUETOOTH_SCO,   // STREAM_BLUETOOTH_SCO
        AudioSystem.STREAM_MUSIC,           // STREAM_SYSTEM_ENFORCED
        AudioSystem.STREAM_MUSIC,           // STREAM_DTMF
        AudioSystem.STREAM_MUSIC            // STREAM_TTS
    };
    private int[] mStreamVolumeAlias;

    // stream names used by dumpStreamStates()
    private final String[] STREAM_NAMES = new String[] {
            "STREAM_VOICE_CALL",
            "STREAM_SYSTEM",
            "STREAM_RING",
            "STREAM_MUSIC",
            "STREAM_ALARM",
            "STREAM_NOTIFICATION",
            "STREAM_BLUETOOTH_SCO",
            "STREAM_SYSTEM_ENFORCED",
            "STREAM_DTMF",
            "STREAM_TTS"
    };

    private final AudioSystem.ErrorCallback mAudioSystemCallback = new AudioSystem.ErrorCallback() {
        public void onError(int error) {
            switch (error) {
            case AudioSystem.AUDIO_STATUS_SERVER_DIED:
                if (mMediaServerOk) {
                    sendMsg(mAudioHandler, MSG_MEDIA_SERVER_DIED, SENDMSG_NOOP, 0, 0,
                            null, 1500);
                    mMediaServerOk = false;
                }
                break;
            case AudioSystem.AUDIO_STATUS_OK:
                if (!mMediaServerOk) {
                    sendMsg(mAudioHandler, MSG_MEDIA_SERVER_STARTED, SENDMSG_NOOP, 0, 0,
                            null, 0);
                    mMediaServerOk = true;
                }
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
    // protected by mSettingsLock
    private int mRingerMode;

    /** @see System#MODE_RINGER_STREAMS_AFFECTED */
    private int mRingerModeAffectedStreams;

    // Streams currently muted by ringer mode
    private int mRingerModeMutedStreams;

    /** @see System#MUTE_STREAMS_AFFECTED */
    private int mMuteAffectedStreams;

    /**
     * NOTE: setVibrateSetting(), getVibrateSetting(), shouldVibrate() are deprecated.
     * mVibrateSetting is just maintained during deprecation period but vibration policy is
     * now only controlled by mHasVibrator and mRingerMode
     */
    private int mVibrateSetting;

    // Is there a vibrator
    private final boolean mHasVibrator;

    // Broadcast receiver for device connections intent broadcasts
    private final BroadcastReceiver mReceiver = new AudioServiceBroadcastReceiver();

    // Used to alter media button redirection when the phone is ringing.
    private boolean mIsRinging = false;

    // Devices currently connected
    private final HashMap <Integer, String> mConnectedDevices = new HashMap <Integer, String>();

    // Forced device usage for communications
    private int mForcedUseForComm;

    // True if we have master volume support
    private final boolean mUseMasterVolume;

    private final int[] mMasterVolumeRamp;

    // List of binder death handlers for setMode() client processes.
    // The last process to have called setMode() is at the top of the list.
    private final ArrayList <SetModeDeathHandler> mSetModeDeathHandlers = new ArrayList <SetModeDeathHandler>();

    // List of clients having issued a SCO start request
    private final ArrayList <ScoClient> mScoClients = new ArrayList <ScoClient>();

    // BluetoothHeadset API to control SCO connection
    private BluetoothHeadset mBluetoothHeadset;

    // Bluetooth headset device
    private BluetoothDevice mBluetoothHeadsetDevice;

    // Indicate if SCO audio connection is currently active and if the initiator is
    // audio service (internal) or bluetooth headset (external)
    private int mScoAudioState;
    // SCO audio state is not active
    private static final int SCO_STATE_INACTIVE = 0;
    // SCO audio activation request waiting for headset service to connect
    private static final int SCO_STATE_ACTIVATE_REQ = 1;
    // SCO audio state is active or starting due to a local request to start a virtual call
    private static final int SCO_STATE_ACTIVE_INTERNAL = 3;
    // SCO audio deactivation request waiting for headset service to connect
    private static final int SCO_STATE_DEACTIVATE_REQ = 5;

    // SCO audio state is active due to an action in BT handsfree (either voice recognition or
    // in call audio)
    private static final int SCO_STATE_ACTIVE_EXTERNAL = 2;
    // Deactivation request for all SCO connections (initiated by audio mode change)
    // waiting for headset service to connect
    private static final int SCO_STATE_DEACTIVATE_EXT_REQ = 4;

    // Current connection state indicated by bluetooth headset
    private int mScoConnectionState;

    // true if boot sequence has been completed
    private boolean mBootCompleted;
    // listener for SoundPool sample load completion indication
    private SoundPoolCallback mSoundPoolCallBack;
    // thread for SoundPool listener
    private SoundPoolListenerThread mSoundPoolListenerThread;
    // message looper for SoundPool listener
    private Looper mSoundPoolLooper = null;
    // volume applied to sound played with playSoundEffect()
    private static int sSoundEffectVolumeDb;
    // getActiveStreamType() will return:
    // - STREAM_NOTIFICATION on tablets during this period after a notification stopped
    // - STREAM_MUSIC on phones during this period after music or talkback/voice search prompt
    // stopped
    private static final int DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS = 5000;
    // previous volume adjustment direction received by checkForRingerModeChange()
    private int mPrevVolDirection = AudioManager.ADJUST_SAME;
    // Keyguard manager proxy
    private KeyguardManager mKeyguardManager;
    // mVolumeControlStream is set by VolumePanel to temporarily force the stream type which volume
    // is controlled by Vol keys.
    private int  mVolumeControlStream = -1;
    private final Object mForceControlStreamLock = new Object();
    // VolumePanel is currently the only client of forceVolumeControlStream() and runs in system
    // server process so in theory it is not necessary to monitor the client death.
    // However it is good to be ready for future evolutions.
    private ForceControlStreamClient mForceControlStreamClient = null;
    // Used to play ringtones outside system_server
    private volatile IRingtonePlayer mRingtonePlayer;

    private int mDeviceOrientation = Configuration.ORIENTATION_UNDEFINED;

    // Request to override default use of A2DP for media.
    private boolean mBluetoothA2dpEnabled;
    private final Object mBluetoothA2dpEnabledLock = new Object();

    // Monitoring of audio routes.  Protected by mCurAudioRoutes.
    final AudioRoutesInfo mCurAudioRoutes = new AudioRoutesInfo();
    final RemoteCallbackList<IAudioRoutesObserver> mRoutesObservers
            = new RemoteCallbackList<IAudioRoutesObserver>();

    /**
     * A fake stream type to match the notion of remote media playback
     */
    public final static int STREAM_REMOTE_MUSIC = -200;

    // Devices for which the volume is fixed and VolumePanel slider should be disabled
    final int mFixedVolumeDevices = AudioSystem.DEVICE_OUT_AUX_DIGITAL |
            AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET |
            AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET |
            AudioSystem.DEVICE_OUT_ALL_USB;

    private final boolean mMonitorOrientation;

    private boolean mDockAudioMediaEnabled = true;

    ///////////////////////////////////////////////////////////////////////////
    // Construction
    ///////////////////////////////////////////////////////////////////////////

    /** @hide */
    public AudioService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mVoiceCapable = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mMediaEventWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handleMediaEvent");

        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = vibrator == null ? false : vibrator.hasVibrator();

       // Intialized volume
        MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] = SystemProperties.getInt(
            "ro.config.vc_call_vol_steps",
           MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL]);

        sSoundEffectVolumeDb = context.getResources().getInteger(
                com.android.internal.R.integer.config_soundEffectVolumeDb);

        mVolumePanel = new VolumePanel(context, this);
        mMode = AudioSystem.MODE_NORMAL;
        mForcedUseForComm = AudioSystem.FORCE_NONE;

        createAudioSystemThread();

        boolean cameraSoundForced = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_camera_sound_forced);
        mCameraSoundForced = new Boolean(cameraSoundForced);
        sendMsg(mAudioHandler,
                MSG_SET_FORCE_USE,
                SENDMSG_QUEUE,
                AudioSystem.FOR_SYSTEM,
                cameraSoundForced ?
                        AudioSystem.FORCE_SYSTEM_ENFORCED : AudioSystem.FORCE_NONE,
                null,
                0);

        readPersistedSettings();
        mSettingsObserver = new SettingsObserver();
        updateStreamVolumeAlias(false /*updateVolumes*/);
        createStreamStates();

        mMediaServerOk = true;

        mSafeMediaVolumeState = new Integer(SAFE_MEDIA_VOLUME_NOT_CONFIGURED);

        // Call setRingerModeInt() to apply correct mute
        // state on streams affected by ringer mode.
        mRingerModeMutedStreams = 0;
        setRingerModeInt(getRingerMode(), false);

        AudioSystem.setErrorCallback(mAudioSystemCallback);

        // Register for device connection intent broadcasts.
        IntentFilter intentFilter =
                new IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
        intentFilter.addAction(Intent.ACTION_USB_AUDIO_ACCESSORY_PLUG);
        intentFilter.addAction(Intent.ACTION_USB_AUDIO_DEVICE_PLUG);
        intentFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);

        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        // Register a configuration change listener only if requested by system properties
        // to monitor orientation changes (off by default)
        mMonitorOrientation = SystemProperties.getBoolean("ro.audio.monitorOrientation", false);
        if (mMonitorOrientation) {
            Log.v(TAG, "monitoring device orientation");
            // initialize orientation in AudioSystem
            setOrientationForAudioSystem();
        }

        context.registerReceiver(mReceiver, intentFilter);

        // Register for package removal intent broadcasts for media button receiver persistence
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addDataScheme("package");
        context.registerReceiver(mReceiver, pkgFilter);

        // Register for phone state monitoring
        TelephonyManager tmgr = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        tmgr.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        mUseMasterVolume = context.getResources().getBoolean(
                com.android.internal.R.bool.config_useMasterVolume);
        restoreMasterVolume();

        mMasterVolumeRamp = context.getResources().getIntArray(
                com.android.internal.R.array.config_masterVolumeRamp);

        mMainRemote = new RemotePlaybackState(-1, MAX_STREAM_VOLUME[AudioManager.STREAM_MUSIC],
                MAX_STREAM_VOLUME[AudioManager.STREAM_MUSIC]);
        mHasRemotePlayback = false;
        mMainRemoteIsActive = false;
        postReevaluateRemote();
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

    private void checkAllAliasStreamVolumes() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            if (streamType != mStreamVolumeAlias[streamType]) {
                mStreamStates[streamType].
                                    setAllIndexes(mStreamStates[mStreamVolumeAlias[streamType]],
                                                  false /*lastAudible*/);
                mStreamStates[streamType].
                                    setAllIndexes(mStreamStates[mStreamVolumeAlias[streamType]],
                                                  true /*lastAudible*/);
            }
            // apply stream volume
            if (mStreamStates[streamType].muteCount() == 0) {
                mStreamStates[streamType].applyAllVolumes();
            }
        }
    }

    private void createStreamStates() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        VolumeStreamState[] streams = mStreamStates = new VolumeStreamState[numStreamTypes];

        for (int i = 0; i < numStreamTypes; i++) {
            streams[i] = new VolumeStreamState(System.VOLUME_SETTINGS[mStreamVolumeAlias[i]], i);
        }

        checkAllAliasStreamVolumes();
    }

    private void dumpStreamStates(PrintWriter pw) {
        pw.println("\nStream volumes (device: index)");
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int i = 0; i < numStreamTypes; i++) {
            pw.println("- "+STREAM_NAMES[i]+":");
            mStreamStates[i].dump(pw);
            pw.println("");
        }
        pw.print("\n- mute affected streams = 0x");
        pw.println(Integer.toHexString(mMuteAffectedStreams));
    }


    private void updateStreamVolumeAlias(boolean updateVolumes) {
        int dtmfStreamAlias;
        if (mVoiceCapable) {
            mStreamVolumeAlias = STREAM_VOLUME_ALIAS;
            dtmfStreamAlias = AudioSystem.STREAM_RING;
        } else {
            mStreamVolumeAlias = STREAM_VOLUME_ALIAS_NON_VOICE;
            dtmfStreamAlias = AudioSystem.STREAM_MUSIC;
        }
        if (isInCommunication()) {
            dtmfStreamAlias = AudioSystem.STREAM_VOICE_CALL;
        }
        mStreamVolumeAlias[AudioSystem.STREAM_DTMF] = dtmfStreamAlias;
        if (updateVolumes) {
            mStreamStates[AudioSystem.STREAM_DTMF].setAllIndexes(mStreamStates[dtmfStreamAlias],
                                                                 false /*lastAudible*/);
            mStreamStates[AudioSystem.STREAM_DTMF].setAllIndexes(mStreamStates[dtmfStreamAlias],
                                                                 true /*lastAudible*/);
            sendMsg(mAudioHandler,
                    MSG_SET_ALL_VOLUMES,
                    SENDMSG_QUEUE,
                    0,
                    0,
                    mStreamStates[AudioSystem.STREAM_DTMF], 0);
        }
    }

    private void readDockAudioSettings(ContentResolver cr)
    {
        mDockAudioMediaEnabled = Settings.Global.getInt(
                                        cr, Settings.Global.DOCK_AUDIO_MEDIA_ENABLED, 0) == 1;

        if (mDockAudioMediaEnabled) {
            mBecomingNoisyIntentDevices |= AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET;
        } else {
            mBecomingNoisyIntentDevices &= ~AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET;
        }

        sendMsg(mAudioHandler,
                MSG_SET_FORCE_USE,
                SENDMSG_QUEUE,
                AudioSystem.FOR_DOCK,
                mDockAudioMediaEnabled ?
                        AudioSystem.FORCE_ANALOG_DOCK : AudioSystem.FORCE_NONE,
                null,
                0);
    }

    private void readPersistedSettings() {
        final ContentResolver cr = mContentResolver;

        int ringerModeFromSettings =
                Settings.Global.getInt(
                        cr, Settings.Global.MODE_RINGER, AudioManager.RINGER_MODE_NORMAL);
        int ringerMode = ringerModeFromSettings;
        // sanity check in case the settings are restored from a device with incompatible
        // ringer modes
        if (!AudioManager.isValidRingerMode(ringerMode)) {
            ringerMode = AudioManager.RINGER_MODE_NORMAL;
        }
        if ((ringerMode == AudioManager.RINGER_MODE_VIBRATE) && !mHasVibrator) {
            ringerMode = AudioManager.RINGER_MODE_SILENT;
        }
        if (ringerMode != ringerModeFromSettings) {
            Settings.Global.putInt(cr, Settings.Global.MODE_RINGER, ringerMode);
        }
        synchronized(mSettingsLock) {
            mRingerMode = ringerMode;

            // System.VIBRATE_ON is not used any more but defaults for mVibrateSetting
            // are still needed while setVibrateSetting() and getVibrateSetting() are being
            // deprecated.
            mVibrateSetting = getValueForVibrateSetting(0,
                                            AudioManager.VIBRATE_TYPE_NOTIFICATION,
                                            mHasVibrator ? AudioManager.VIBRATE_SETTING_ONLY_SILENT
                                                            : AudioManager.VIBRATE_SETTING_OFF);
            mVibrateSetting = getValueForVibrateSetting(mVibrateSetting,
                                            AudioManager.VIBRATE_TYPE_RINGER,
                                            mHasVibrator ? AudioManager.VIBRATE_SETTING_ONLY_SILENT
                                                            : AudioManager.VIBRATE_SETTING_OFF);

            // make sure settings for ringer mode are consistent with device type: non voice capable
            // devices (tablets) include media stream in silent mode whereas phones don't.
            mRingerModeAffectedStreams = Settings.System.getIntForUser(cr,
                    Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                    ((1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_NOTIFICATION)|
                     (1 << AudioSystem.STREAM_SYSTEM)|(1 << AudioSystem.STREAM_SYSTEM_ENFORCED)),
                     UserHandle.USER_CURRENT);

            // ringtone, notification and system streams are always affected by ringer mode
            mRingerModeAffectedStreams |= (1 << AudioSystem.STREAM_RING)|
                                            (1 << AudioSystem.STREAM_NOTIFICATION)|
                                            (1 << AudioSystem.STREAM_SYSTEM);

            if (mVoiceCapable) {
                mRingerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_MUSIC);
            } else {
                mRingerModeAffectedStreams |= (1 << AudioSystem.STREAM_MUSIC);
            }
            synchronized (mCameraSoundForced) {
                if (mCameraSoundForced) {
                    mRingerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                } else {
                    mRingerModeAffectedStreams |= (1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                }
            }

            Settings.System.putIntForUser(cr,
                    Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                    mRingerModeAffectedStreams,
                    UserHandle.USER_CURRENT);

            readDockAudioSettings(cr);
        }

        mMuteAffectedStreams = System.getIntForUser(cr,
                System.MUTE_STREAMS_AFFECTED,
                ((1 << AudioSystem.STREAM_MUSIC)|
                 (1 << AudioSystem.STREAM_RING)|
                 (1 << AudioSystem.STREAM_SYSTEM)),
                 UserHandle.USER_CURRENT);

        boolean masterMute = System.getIntForUser(cr, System.VOLUME_MASTER_MUTE,
                                                  0, UserHandle.USER_CURRENT) == 1;
        AudioSystem.setMasterMute(masterMute);
        broadcastMasterMuteStatus(masterMute);

        // Each stream will read its own persisted settings

        // Broadcast the sticky intent
        broadcastRingerMode(ringerMode);

        // Broadcast vibrate settings
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER);
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION);

        // Restore the default media button receiver from the system settings
        restoreMediaButtonReceiver();
    }

    private int rescaleIndex(int index, int srcStream, int dstStream) {
        return (index * mStreamStates[dstStream].getMaxIndex() + mStreamStates[srcStream].getMaxIndex() / 2) / mStreamStates[srcStream].getMaxIndex();
    }

    ///////////////////////////////////////////////////////////////////////////
    // IPC methods
    ///////////////////////////////////////////////////////////////////////////

    /** @see AudioManager#adjustVolume(int, int) */
    public void adjustVolume(int direction, int flags) {
        adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, flags);
    }

    /** @see AudioManager#adjustLocalOrRemoteStreamVolume(int, int) with current assumption
     *  on streamType: fixed to STREAM_MUSIC */
    public void adjustLocalOrRemoteStreamVolume(int streamType, int direction) {
        if (DEBUG_VOL) Log.d(TAG, "adjustLocalOrRemoteStreamVolume(dir="+direction+")");
        if (checkUpdateRemoteStateIfActive(AudioSystem.STREAM_MUSIC)) {
            adjustRemoteVolume(AudioSystem.STREAM_MUSIC, direction, 0);
        } else if (AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0)) {
            adjustStreamVolume(AudioSystem.STREAM_MUSIC, direction, 0);
        }
    }

    /** @see AudioManager#adjustVolume(int, int, int) */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {
        if (DEBUG_VOL) Log.d(TAG, "adjustSuggestedStreamVolume() stream="+suggestedStreamType);
        int streamType;
        if (mVolumeControlStream != -1) {
            streamType = mVolumeControlStream;
        } else {
            streamType = getActiveStreamType(suggestedStreamType);
        }

        // Play sounds on STREAM_RING only and if lock screen is not on.
        if ((streamType != STREAM_REMOTE_MUSIC) &&
                (flags & AudioManager.FLAG_PLAY_SOUND) != 0 &&
                ((mStreamVolumeAlias[streamType] != AudioSystem.STREAM_RING)
                 || (mKeyguardManager != null && mKeyguardManager.isKeyguardLocked()))) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }

        if (streamType == STREAM_REMOTE_MUSIC) {
            // don't play sounds for remote
            flags &= ~(AudioManager.FLAG_PLAY_SOUND|AudioManager.FLAG_FIXED_VOLUME);
            //if (DEBUG_VOL) Log.i(TAG, "Need to adjust remote volume: calling adjustRemoteVolume()");
            adjustRemoteVolume(AudioSystem.STREAM_MUSIC, direction, flags);
        } else {
            adjustStreamVolume(streamType, direction, flags);
        }
    }

    /** @see AudioManager#adjustStreamVolume(int, int, int) */
    public void adjustStreamVolume(int streamType, int direction, int flags) {
        if (DEBUG_VOL) Log.d(TAG, "adjustStreamVolume() stream="+streamType+", dir="+direction);

        ensureValidDirection(direction);
        ensureValidStreamType(streamType);

        // use stream type alias here so that streams with same alias have the same behavior,
        // including with regard to silent mode control (e.g the use of STREAM_RING below and in
        // checkForRingerModeChange() in place of STREAM_RING or STREAM_NOTIFICATION)
        int streamTypeAlias = mStreamVolumeAlias[streamType];
        VolumeStreamState streamState = mStreamStates[streamTypeAlias];

        final int device = getDeviceForStream(streamTypeAlias);
        // get last audible index if stream is muted, current index otherwise
        final int aliasIndex = streamState.getIndex(device,
                                                  (streamState.muteCount() != 0) /* lastAudible */);
        boolean adjustVolume = true;

        // convert one UI step (+/-1) into a number of internal units on the stream alias
        int step = rescaleIndex(10, streamType, streamTypeAlias);

        if ((direction == AudioManager.ADJUST_RAISE) &&
                !checkSafeMediaVolume(streamTypeAlias, aliasIndex + step, device)) {
            return;
        }

        int index;
        int oldIndex;

        flags &= ~AudioManager.FLAG_FIXED_VOLUME;
        if ((streamTypeAlias == AudioSystem.STREAM_MUSIC) &&
               ((device & mFixedVolumeDevices) != 0)) {
            flags |= AudioManager.FLAG_FIXED_VOLUME;
            index = mStreamStates[streamType].getMaxIndex();
            oldIndex = index;
        } else {
            // If either the client forces allowing ringer modes for this adjustment,
            // or the stream type is one that is affected by ringer modes
            if (((flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0) ||
                    (streamTypeAlias == getMasterStreamType())) {
                int ringerMode = getRingerMode();
                // do not vibrate if already in vibrate mode
                if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                    flags &= ~AudioManager.FLAG_VIBRATE;
                }
                // Check if the ringer mode changes with this volume adjustment. If
                // it does, it will handle adjusting the volume, so we won't below
                adjustVolume = checkForRingerModeChange(aliasIndex, direction, step);
                if ((streamTypeAlias == getMasterStreamType()) &&
                        (mRingerMode == AudioManager.RINGER_MODE_SILENT)) {
                    streamState.setLastAudibleIndex(0, device);
                }
            }

            // If stream is muted, adjust last audible index only
            oldIndex = mStreamStates[streamType].getIndex(device,
                    (mStreamStates[streamType].muteCount() != 0) /* lastAudible */);

            if (streamState.muteCount() != 0) {
                if (adjustVolume) {
                    // Post a persist volume msg
                    // no need to persist volume on all streams sharing the same alias
                    streamState.adjustLastAudibleIndex(direction * step, device);
                    sendMsg(mAudioHandler,
                            MSG_PERSIST_VOLUME,
                            SENDMSG_QUEUE,
                            PERSIST_LAST_AUDIBLE,
                            device,
                            streamState,
                            PERSIST_DELAY);
                }
                index = mStreamStates[streamType].getIndex(device, true  /* lastAudible */);
            } else {
                if (adjustVolume && streamState.adjustIndex(direction * step, device)) {
                    // Post message to set system volume (it in turn will post a message
                    // to persist). Do not change volume if stream is muted.
                    sendMsg(mAudioHandler,
                            MSG_SET_DEVICE_VOLUME,
                            SENDMSG_QUEUE,
                            device,
                            0,
                            streamState,
                            0);
                }
                index = mStreamStates[streamType].getIndex(device, false  /* lastAudible */);
            }
        }
        sendVolumeUpdate(streamType, oldIndex, index, flags);
    }

    /** @see AudioManager#adjustMasterVolume(int) */
    public void adjustMasterVolume(int steps, int flags) {
        ensureValidSteps(steps);
        int volume = Math.round(AudioSystem.getMasterVolume() * MAX_MASTER_VOLUME);
        int delta = 0;
        int numSteps = Math.abs(steps);
        int direction = steps > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        for (int i = 0; i < numSteps; ++i) {
            delta = findVolumeDelta(direction, volume);
            volume += delta;
        }

        //Log.d(TAG, "adjustMasterVolume volume: " + volume + " steps: " + steps);
        setMasterVolume(volume, flags);
    }

    /** @see AudioManager#setStreamVolume(int, int, int) */
    public void setStreamVolume(int streamType, int index, int flags) {
        ensureValidStreamType(streamType);
        VolumeStreamState streamState = mStreamStates[mStreamVolumeAlias[streamType]];

        final int device = getDeviceForStream(streamType);
        int oldIndex;

        flags &= ~AudioManager.FLAG_FIXED_VOLUME;
        if ((mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC) &&
                ((device & mFixedVolumeDevices) != 0)) {
            flags |= AudioManager.FLAG_FIXED_VOLUME;
            index = mStreamStates[streamType].getMaxIndex();
            oldIndex = index;
        } else {
            // get last audible index if stream is muted, current index otherwise
            oldIndex = streamState.getIndex(device,
                                            (streamState.muteCount() != 0) /* lastAudible */);

            index = rescaleIndex(index * 10, streamType, mStreamVolumeAlias[streamType]);

            if (!checkSafeMediaVolume(mStreamVolumeAlias[streamType], index, device)) {
                return;
            }

            // setting volume on master stream type also controls silent mode
            if (((flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0) ||
                    (mStreamVolumeAlias[streamType] == getMasterStreamType())) {
                int newRingerMode;
                if (index == 0) {
                    newRingerMode = mHasVibrator ? AudioManager.RINGER_MODE_VIBRATE
                                                  : AudioManager.RINGER_MODE_SILENT;
                    setStreamVolumeInt(mStreamVolumeAlias[streamType],
                                       index,
                                       device,
                                       false,
                                       true);
                } else {
                    newRingerMode = AudioManager.RINGER_MODE_NORMAL;
                }
                setRingerMode(newRingerMode);
            }

            setStreamVolumeInt(mStreamVolumeAlias[streamType], index, device, false, true);
            // get last audible index if stream is muted, current index otherwise
            index = mStreamStates[streamType].getIndex(device,
                                    (mStreamStates[streamType].muteCount() != 0) /* lastAudible */);
        }
        sendVolumeUpdate(streamType, oldIndex, index, flags);
    }

    /** @see AudioManager#forceVolumeControlStream(int) */
    public void forceVolumeControlStream(int streamType, IBinder cb) {
        synchronized(mForceControlStreamLock) {
            mVolumeControlStream = streamType;
            if (mVolumeControlStream == -1) {
                if (mForceControlStreamClient != null) {
                    mForceControlStreamClient.release();
                    mForceControlStreamClient = null;
                }
            } else {
                mForceControlStreamClient = new ForceControlStreamClient(cb);
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
                }
            }
        }

        public void release() {
            if (mCb != null) {
                mCb.unlinkToDeath(this, 0);
                mCb = null;
            }
        }
    }

    private int findVolumeDelta(int direction, int volume) {
        int delta = 0;
        if (direction == AudioManager.ADJUST_RAISE) {
            if (volume == MAX_MASTER_VOLUME) {
                return 0;
            }
            // This is the default value if we make it to the end
            delta = mMasterVolumeRamp[1];
            // If we're raising the volume move down the ramp array until we
            // find the volume we're above and use that groups delta.
            for (int i = mMasterVolumeRamp.length - 1; i > 1; i -= 2) {
                if (volume >= mMasterVolumeRamp[i - 1]) {
                    delta = mMasterVolumeRamp[i];
                    break;
                }
            }
        } else if (direction == AudioManager.ADJUST_LOWER){
            if (volume == 0) {
                return 0;
            }
            int length = mMasterVolumeRamp.length;
            // This is the default value if we make it to the end
            delta = -mMasterVolumeRamp[length - 1];
            // If we're lowering the volume move up the ramp array until we
            // find the volume we're below and use the group below it's delta
            for (int i = 2; i < length; i += 2) {
                if (volume <= mMasterVolumeRamp[i]) {
                    delta = -mMasterVolumeRamp[i - 1];
                    break;
                }
            }
        }
        return delta;
    }

    private void sendBroadcastToAll(Intent intent) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void sendStickyBroadcastToAll(Intent intent) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // UI update and Broadcast Intent
    private void sendVolumeUpdate(int streamType, int oldIndex, int index, int flags) {
        if (!mVoiceCapable && (streamType == AudioSystem.STREAM_RING)) {
            streamType = AudioSystem.STREAM_NOTIFICATION;
        }

        mVolumePanel.postVolumeChanged(streamType, flags);

        if ((flags & AudioManager.FLAG_FIXED_VOLUME) == 0) {
            oldIndex = (oldIndex + 5) / 10;
            index = (index + 5) / 10;
            Intent intent = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
            intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, streamType);
            intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, index);
            intent.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, oldIndex);
            sendBroadcastToAll(intent);
        }
    }

    // UI update and Broadcast Intent
    private void sendMasterVolumeUpdate(int flags, int oldVolume, int newVolume) {
        mVolumePanel.postMasterVolumeChanged(flags);

        Intent intent = new Intent(AudioManager.MASTER_VOLUME_CHANGED_ACTION);
        intent.putExtra(AudioManager.EXTRA_PREV_MASTER_VOLUME_VALUE, oldVolume);
        intent.putExtra(AudioManager.EXTRA_MASTER_VOLUME_VALUE, newVolume);
        sendBroadcastToAll(intent);
    }

    // UI update and Broadcast Intent
    private void sendMasterMuteUpdate(boolean muted, int flags) {
        mVolumePanel.postMasterMuteChanged(flags);
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
     * @param lastAudible If true, stores new index as last audible one
     */
    private void setStreamVolumeInt(int streamType,
                                    int index,
                                    int device,
                                    boolean force,
                                    boolean lastAudible) {
        VolumeStreamState streamState = mStreamStates[streamType];

        // If stream is muted, set last audible index only
        if (streamState.muteCount() != 0) {
            // Do not allow last audible index to be 0
            if (index != 0) {
                streamState.setLastAudibleIndex(index, device);
                // Post a persist volume msg
                sendMsg(mAudioHandler,
                        MSG_PERSIST_VOLUME,
                        SENDMSG_QUEUE,
                        PERSIST_LAST_AUDIBLE,
                        device,
                        streamState,
                        PERSIST_DELAY);
            }
        } else {
            if (streamState.setIndex(index, device, lastAudible) || force) {
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
        }
    }

    /** @see AudioManager#setStreamSolo(int, boolean) */
    public void setStreamSolo(int streamType, boolean state, IBinder cb) {
        for (int stream = 0; stream < mStreamStates.length; stream++) {
            if (!isStreamAffectedByMute(stream) || stream == streamType) continue;
            // Bring back last audible volume
            mStreamStates[stream].mute(cb, state);
         }
    }

    /** @see AudioManager#setStreamMute(int, boolean) */
    public void setStreamMute(int streamType, boolean state, IBinder cb) {
        if (isStreamAffectedByMute(streamType)) {
            mStreamStates[streamType].mute(cb, state);
        }
    }

    /** get stream mute state. */
    public boolean isStreamMute(int streamType) {
        return (mStreamStates[streamType].muteCount() != 0);
    }

    /** @see AudioManager#setMasterMute(boolean, IBinder) */
    public void setMasterMute(boolean state, int flags, IBinder cb) {
        if (state != AudioSystem.getMasterMute()) {
            AudioSystem.setMasterMute(state);
            // Post a persist master volume msg
            sendMsg(mAudioHandler, MSG_PERSIST_MASTER_VOLUME_MUTE, SENDMSG_REPLACE, state ? 1
                    : 0, 0, null, PERSIST_DELAY);
            sendMasterMuteUpdate(state, flags);
        }
    }

    /** get master mute state. */
    public boolean isMasterMute() {
        return AudioSystem.getMasterMute();
    }

    /** @see AudioManager#getStreamVolume(int) */
    public int getStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        int device = getDeviceForStream(streamType);
        int index;

        if ((mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC) &&
                (device & mFixedVolumeDevices) != 0) {
            index = mStreamStates[streamType].getMaxIndex();
        } else {
            index = mStreamStates[streamType].getIndex(device, false  /* lastAudible */);
        }
        return (index + 5) / 10;
    }

    public int getMasterVolume() {
        if (isMasterMute()) return 0;
        return getLastAudibleMasterVolume();
    }

    public void setMasterVolume(int volume, int flags) {
        if (volume < 0) {
            volume = 0;
        } else if (volume > MAX_MASTER_VOLUME) {
            volume = MAX_MASTER_VOLUME;
        }
        doSetMasterVolume((float)volume / MAX_MASTER_VOLUME, flags);
    }

    private void doSetMasterVolume(float volume, int flags) {
        // don't allow changing master volume when muted
        if (!AudioSystem.getMasterMute()) {
            int oldVolume = getMasterVolume();
            AudioSystem.setMasterVolume(volume);

            int newVolume = getMasterVolume();
            if (newVolume != oldVolume) {
                // Post a persist master volume msg
                sendMsg(mAudioHandler, MSG_PERSIST_MASTER_VOLUME, SENDMSG_REPLACE,
                        Math.round(volume * (float)1000.0), 0, null, PERSIST_DELAY);
            }
            // Send the volume update regardless whether there was a change.
            sendMasterVolumeUpdate(flags, oldVolume, newVolume);
        }
    }

    /** @see AudioManager#getStreamMaxVolume(int) */
    public int getStreamMaxVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].getMaxIndex() + 5) / 10;
    }

    public int getMasterMaxVolume() {
        return MAX_MASTER_VOLUME;
    }

    /** Get last audible volume before stream was muted. */
    public int getLastAudibleStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        int device = getDeviceForStream(streamType);
        return (mStreamStates[streamType].getIndex(device, true  /* lastAudible */) + 5) / 10;
    }

    /** Get last audible master volume before it was muted. */
    public int getLastAudibleMasterVolume() {
        return Math.round(AudioSystem.getMasterVolume() * MAX_MASTER_VOLUME);
    }

    /** @see AudioManager#getMasterStreamType(int) */
    public int getMasterStreamType() {
        if (mVoiceCapable) {
            return AudioSystem.STREAM_RING;
        } else {
            return AudioSystem.STREAM_MUSIC;
        }
    }

    /** @see AudioManager#getRingerMode() */
    public int getRingerMode() {
        synchronized(mSettingsLock) {
            return mRingerMode;
        }
    }

    private void ensureValidRingerMode(int ringerMode) {
        if (!AudioManager.isValidRingerMode(ringerMode)) {
            throw new IllegalArgumentException("Bad ringer mode " + ringerMode);
        }
    }

    /** @see AudioManager#setRingerMode(int) */
    public void setRingerMode(int ringerMode) {
        if ((ringerMode == AudioManager.RINGER_MODE_VIBRATE) && !mHasVibrator) {
            ringerMode = AudioManager.RINGER_MODE_SILENT;
        }
        if (ringerMode != getRingerMode()) {
            setRingerModeInt(ringerMode, true);
            // Send sticky broadcast
            broadcastRingerMode(ringerMode);
        }
    }

    private void setRingerModeInt(int ringerMode, boolean persist) {
        synchronized(mSettingsLock) {
            mRingerMode = ringerMode;
        }

        // Mute stream if not previously muted by ringer mode and ringer mode
        // is not RINGER_MODE_NORMAL and stream is affected by ringer mode.
        // Unmute stream if previously muted by ringer mode and ringer mode
        // is RINGER_MODE_NORMAL or stream is not affected by ringer mode.
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
            if (isStreamMutedByRingerMode(streamType)) {
                if (!isStreamAffectedByRingerMode(streamType) ||
                    ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    // ring and notifications volume should never be 0 when not silenced
                    // on voice capable devices
                    if (mVoiceCapable &&
                            mStreamVolumeAlias[streamType] == AudioSystem.STREAM_RING) {
                        synchronized (mStreamStates[streamType]) {
                            Set set = mStreamStates[streamType].mLastAudibleIndex.entrySet();
                            Iterator i = set.iterator();
                            while (i.hasNext()) {
                                Map.Entry entry = (Map.Entry)i.next();
                                if ((Integer)entry.getValue() == 0) {
                                    entry.setValue(10);
                                }
                            }
                        }
                    }
                    mStreamStates[streamType].mute(null, false);
                    mRingerModeMutedStreams &= ~(1 << streamType);
                }
            } else {
                if (isStreamAffectedByRingerMode(streamType) &&
                    ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                   mStreamStates[streamType].mute(null, true);
                   mRingerModeMutedStreams |= (1 << streamType);
               }
            }
        }

        // Post a persist ringer mode msg
        if (persist) {
            sendMsg(mAudioHandler, MSG_PERSIST_RINGER_MODE,
                    SENDMSG_REPLACE, 0, 0, null, PERSIST_DELAY);
        }
    }

    private void restoreMasterVolume() {
        if (mUseMasterVolume) {
            float volume = Settings.System.getFloatForUser(mContentResolver,
                    Settings.System.VOLUME_MASTER, -1.0f, UserHandle.USER_CURRENT);
            if (volume >= 0.0f) {
                AudioSystem.setMasterVolume(volume);
            }
        }
    }

    /** @see AudioManager#shouldVibrate(int) */
    public boolean shouldVibrate(int vibrateType) {
        if (!mHasVibrator) return false;

        switch (getVibrateSetting(vibrateType)) {

            case AudioManager.VIBRATE_SETTING_ON:
                return getRingerMode() != AudioManager.RINGER_MODE_SILENT;

            case AudioManager.VIBRATE_SETTING_ONLY_SILENT:
                return getRingerMode() == AudioManager.RINGER_MODE_VIBRATE;

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

        mVibrateSetting = getValueForVibrateSetting(mVibrateSetting, vibrateType, vibrateSetting);

        // Broadcast change
        broadcastVibrateSetting(vibrateType);

    }

    /**
     * @see #setVibrateSetting(int, int)
     */
    public static int getValueForVibrateSetting(int existingValue, int vibrateType,
            int vibrateSetting) {

        // First clear the existing setting. Each vibrate type has two bits in
        // the value. Note '3' is '11' in binary.
        existingValue &= ~(3 << (vibrateType * 2));

        // Set into the old value
        existingValue |= (vibrateSetting & 3) << (vibrateType * 2);

        return existingValue;
    }

    private class SetModeDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death
        private int mPid;
        private int mMode = AudioSystem.MODE_NORMAL; // Current mode set by this client

        SetModeDeathHandler(IBinder cb, int pid) {
            mCb = cb;
            mPid = pid;
        }

        public void binderDied() {
            int newModeOwnerPid = 0;
            synchronized(mSetModeDeathHandlers) {
                Log.w(TAG, "setMode() client died");
                int index = mSetModeDeathHandlers.indexOf(this);
                if (index < 0) {
                    Log.w(TAG, "unregistered setMode() client died");
                } else {
                    newModeOwnerPid = setModeInt(AudioSystem.MODE_NORMAL, mCb, mPid);
                }
            }
            // when entering RINGTONE, IN_CALL or IN_COMMUNICATION mode, clear all
            // SCO connections not started by the application changing the mode
            if (newModeOwnerPid != 0) {
                 disconnectBluetoothSco(newModeOwnerPid);
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
    }

    /** @see AudioManager#setMode(int) */
    public void setMode(int mode, IBinder cb) {
        if (!checkAudioSettingsPermission("setMode()")) {
            return;
        }

        if (mode < AudioSystem.MODE_CURRENT || mode >= AudioSystem.NUM_MODES) {
            return;
        }

        int newModeOwnerPid = 0;
        synchronized(mSetModeDeathHandlers) {
            if (mode == AudioSystem.MODE_CURRENT) {
                mode = mMode;
            }
            newModeOwnerPid = setModeInt(mode, cb, Binder.getCallingPid());
        }
        // when entering RINGTONE, IN_CALL or IN_COMMUNICATION mode, clear all
        // SCO connections not started by the application changing the mode
        if (newModeOwnerPid != 0) {
             disconnectBluetoothSco(newModeOwnerPid);
        }
    }

    // must be called synchronized on mSetModeDeathHandlers
    // setModeInt() returns a valid PID if the audio mode was successfully set to
    // any mode other than NORMAL.
    int setModeInt(int mode, IBinder cb, int pid) {
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
        int status = AudioSystem.AUDIO_STATUS_OK;
        do {
            if (mode == AudioSystem.MODE_NORMAL) {
                // get new mode from client at top the list if any
                if (!mSetModeDeathHandlers.isEmpty()) {
                    hdlr = mSetModeDeathHandlers.get(0);
                    cb = hdlr.getBinder();
                    mode = hdlr.getMode();
                }
            } else {
                if (hdlr == null) {
                    hdlr = new SetModeDeathHandler(cb, pid);
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

            if (mode != mMode) {
                status = AudioSystem.setPhoneState(mode);
                if (status == AudioSystem.AUDIO_STATUS_OK) {
                    mMode = mode;
                } else {
                    if (hdlr != null) {
                        mSetModeDeathHandlers.remove(hdlr);
                        cb.unlinkToDeath(hdlr, 0);
                    }
                    // force reading new top of mSetModeDeathHandlers stack
                    mode = AudioSystem.MODE_NORMAL;
                }
            } else {
                status = AudioSystem.AUDIO_STATUS_OK;
            }
        } while (status != AudioSystem.AUDIO_STATUS_OK && !mSetModeDeathHandlers.isEmpty());

        if (status == AudioSystem.AUDIO_STATUS_OK) {
            if (mode != AudioSystem.MODE_NORMAL) {
                if (mSetModeDeathHandlers.isEmpty()) {
                    Log.e(TAG, "setMode() different from MODE_NORMAL with empty mode client stack");
                } else {
                    newModeOwnerPid = mSetModeDeathHandlers.get(0).getPid();
                }
            }
            int streamType = getActiveStreamType(AudioManager.USE_DEFAULT_STREAM_TYPE);
            if (streamType == STREAM_REMOTE_MUSIC) {
                // here handle remote media playback the same way as local playback
                streamType = AudioManager.STREAM_MUSIC;
            }
            int device = getDeviceForStream(streamType);
            int index = mStreamStates[mStreamVolumeAlias[streamType]].getIndex(device, false);
            setStreamVolumeInt(mStreamVolumeAlias[streamType], index, device, true, false);

            updateStreamVolumeAlias(true /*updateVolumes*/);
        }
        return newModeOwnerPid;
    }

    /** @see AudioManager#getMode() */
    public int getMode() {
        return mMode;
    }

    /** @see AudioManager#playSoundEffect(int) */
    public void playSoundEffect(int effectType) {
        sendMsg(mAudioHandler, MSG_PLAY_SOUND_EFFECT, SENDMSG_NOOP,
                effectType, -1, null, 0);
    }

    /** @see AudioManager#playSoundEffect(int, float) */
    public void playSoundEffectVolume(int effectType, float volume) {
        loadSoundEffects();
        sendMsg(mAudioHandler, MSG_PLAY_SOUND_EFFECT, SENDMSG_NOOP,
                effectType, (int) (volume * 1000), null, 0);
    }

    /**
     * Loads samples into the soundpool.
     * This method must be called at first when sound effects are enabled
     */
    public boolean loadSoundEffects() {
        int status;

        synchronized (mSoundEffectsLock) {
            if (!mBootCompleted) {
                Log.w(TAG, "loadSoundEffects() called before boot complete");
                return false;
            }

            if (mSoundPool != null) {
                return true;
            }
            mSoundPool = new SoundPool(NUM_SOUNDPOOL_CHANNELS, AudioSystem.STREAM_SYSTEM, 0);

            try {
                mSoundPoolCallBack = null;
                mSoundPoolListenerThread = new SoundPoolListenerThread();
                mSoundPoolListenerThread.start();
                // Wait for mSoundPoolCallBack to be set by the other thread
                mSoundEffectsLock.wait();
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting sound pool listener thread.");
            }

            if (mSoundPoolCallBack == null) {
                Log.w(TAG, "loadSoundEffects() could not create SoundPool listener or thread");
                if (mSoundPoolLooper != null) {
                    mSoundPoolLooper.quit();
                    mSoundPoolLooper = null;
                }
                mSoundPoolListenerThread = null;
                mSoundPool.release();
                mSoundPool = null;
                return false;
            }
            /*
             * poolId table: The value -1 in this table indicates that corresponding
             * file (same index in SOUND_EFFECT_FILES[] has not been loaded.
             * Once loaded, the value in poolId is the sample ID and the same
             * sample can be reused for another effect using the same file.
             */
            int[] poolId = new int[SOUND_EFFECT_FILES.length];
            for (int fileIdx = 0; fileIdx < SOUND_EFFECT_FILES.length; fileIdx++) {
                poolId[fileIdx] = -1;
            }
            /*
             * Effects whose value in SOUND_EFFECT_FILES_MAP[effect][1] is -1 must be loaded.
             * If load succeeds, value in SOUND_EFFECT_FILES_MAP[effect][1] is > 0:
             * this indicates we have a valid sample loaded for this effect.
             */

            int lastSample = 0;
            for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                // Do not load sample if this effect uses the MediaPlayer
                if (SOUND_EFFECT_FILES_MAP[effect][1] == 0) {
                    continue;
                }
                if (poolId[SOUND_EFFECT_FILES_MAP[effect][0]] == -1) {
                    String filePath = Environment.getRootDirectory()
                            + SOUND_EFFECTS_PATH
                            + SOUND_EFFECT_FILES[SOUND_EFFECT_FILES_MAP[effect][0]];
                    int sampleId = mSoundPool.load(filePath, 0);
                    if (sampleId <= 0) {
                        Log.w(TAG, "Soundpool could not load file: "+filePath);
                    } else {
                        SOUND_EFFECT_FILES_MAP[effect][1] = sampleId;
                        poolId[SOUND_EFFECT_FILES_MAP[effect][0]] = sampleId;
                        lastSample = sampleId;
                    }
                } else {
                    SOUND_EFFECT_FILES_MAP[effect][1] = poolId[SOUND_EFFECT_FILES_MAP[effect][0]];
                }
            }
            // wait for all samples to be loaded
            if (lastSample != 0) {
                mSoundPoolCallBack.setLastSample(lastSample);

                try {
                    mSoundEffectsLock.wait();
                    status = mSoundPoolCallBack.status();
                } catch (java.lang.InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting sound pool callback.");
                    status = -1;
                }
            } else {
                status = -1;
            }

            if (mSoundPoolLooper != null) {
                mSoundPoolLooper.quit();
                mSoundPoolLooper = null;
            }
            mSoundPoolListenerThread = null;
            if (status != 0) {
                Log.w(TAG,
                        "loadSoundEffects(), Error "
                                + ((lastSample != 0) ? mSoundPoolCallBack.status() : -1)
                                + " while loading samples");
                for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                    if (SOUND_EFFECT_FILES_MAP[effect][1] > 0) {
                        SOUND_EFFECT_FILES_MAP[effect][1] = -1;
                    }
                }

                mSoundPool.release();
                mSoundPool = null;
            }
        }
        return (status == 0);
    }

    /**
     *  Unloads samples from the sound pool.
     *  This method can be called to free some memory when
     *  sound effects are disabled.
     */
    public void unloadSoundEffects() {
        synchronized (mSoundEffectsLock) {
            if (mSoundPool == null) {
                return;
            }

            mAudioHandler.removeMessages(MSG_LOAD_SOUND_EFFECTS);
            mAudioHandler.removeMessages(MSG_PLAY_SOUND_EFFECT);

            int[] poolId = new int[SOUND_EFFECT_FILES.length];
            for (int fileIdx = 0; fileIdx < SOUND_EFFECT_FILES.length; fileIdx++) {
                poolId[fileIdx] = 0;
            }

            for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                if (SOUND_EFFECT_FILES_MAP[effect][1] <= 0) {
                    continue;
                }
                if (poolId[SOUND_EFFECT_FILES_MAP[effect][0]] == 0) {
                    mSoundPool.unload(SOUND_EFFECT_FILES_MAP[effect][1]);
                    SOUND_EFFECT_FILES_MAP[effect][1] = -1;
                    poolId[SOUND_EFFECT_FILES_MAP[effect][0]] = -1;
                }
            }
            mSoundPool.release();
            mSoundPool = null;
        }
    }

    class SoundPoolListenerThread extends Thread {
        public SoundPoolListenerThread() {
            super("SoundPoolListenerThread");
        }

        @Override
        public void run() {

            Looper.prepare();
            mSoundPoolLooper = Looper.myLooper();

            synchronized (mSoundEffectsLock) {
                if (mSoundPool != null) {
                    mSoundPoolCallBack = new SoundPoolCallback();
                    mSoundPool.setOnLoadCompleteListener(mSoundPoolCallBack);
                }
                mSoundEffectsLock.notify();
            }
            Looper.loop();
        }
    }

    private final class SoundPoolCallback implements
            android.media.SoundPool.OnLoadCompleteListener {

        int mStatus;
        int mLastSample;

        public int status() {
            return mStatus;
        }

        public void setLastSample(int sample) {
            mLastSample = sample;
        }

        public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
            synchronized (mSoundEffectsLock) {
                if (status != 0) {
                    mStatus = status;
                }
                if (sampleId == mLastSample) {
                    mSoundEffectsLock.notify();
                }
            }
        }
    }

    /** @see AudioManager#reloadAudioSettings() */
    public void reloadAudioSettings() {
        readAudioSettings(false /*userSwitch*/);
    }

    private void readAudioSettings(boolean userSwitch) {
        // restore ringer mode, ringer mode affected streams, mute affected streams and vibrate settings
        readPersistedSettings();

        // restore volume settings
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            VolumeStreamState streamState = mStreamStates[streamType];

            if (userSwitch && mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC) {
                continue;
            }

            synchronized (streamState) {
                streamState.readSettings();

                // unmute stream that was muted but is not affect by mute anymore
                if (streamState.muteCount() != 0 && !isStreamAffectedByMute(streamType) &&
                        !isStreamMutedByRingerMode(streamType)) {
                    int size = streamState.mDeathHandlers.size();
                    for (int i = 0; i < size; i++) {
                        streamState.mDeathHandlers.get(i).mMuteCount = 1;
                        streamState.mDeathHandlers.get(i).mute(false);
                    }
                }
            }
        }

        // apply new ringer mode before checking volume for alias streams so that streams
        // muted by ringer mode have the correct volume
        setRingerModeInt(getRingerMode(), false);

        checkAllAliasStreamVolumes();

        synchronized (mSafeMediaVolumeState) {
            if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE) {
                enforceSafeMediaVolume();
            }
        }
    }

    /** @see AudioManager#setSpeakerphoneOn() */
    public void setSpeakerphoneOn(boolean on){
        if (!checkAudioSettingsPermission("setSpeakerphoneOn()")) {
            return;
        }
        mForcedUseForComm = on ? AudioSystem.FORCE_SPEAKER : AudioSystem.FORCE_NONE;

        sendMsg(mAudioHandler, MSG_SET_FORCE_USE, SENDMSG_QUEUE,
                AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, null, 0);
    }

    /** @see AudioManager#isSpeakerphoneOn() */
    public boolean isSpeakerphoneOn() {
        return (mForcedUseForComm == AudioSystem.FORCE_SPEAKER);
    }

    /** @see AudioManager#setBluetoothScoOn() */
    public void setBluetoothScoOn(boolean on){
        if (!checkAudioSettingsPermission("setBluetoothScoOn()")) {
            return;
        }
        mForcedUseForComm = on ? AudioSystem.FORCE_BT_SCO : AudioSystem.FORCE_NONE;

        sendMsg(mAudioHandler, MSG_SET_FORCE_USE, SENDMSG_QUEUE,
                AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, null, 0);
        sendMsg(mAudioHandler, MSG_SET_FORCE_USE, SENDMSG_QUEUE,
                AudioSystem.FOR_RECORD, mForcedUseForComm, null, 0);
    }

    /** @see AudioManager#isBluetoothScoOn() */
    public boolean isBluetoothScoOn() {
        return (mForcedUseForComm == AudioSystem.FORCE_BT_SCO);
    }

    /** @see AudioManager#setBluetoothA2dpOn() */
    public void setBluetoothA2dpOn(boolean on) {
        synchronized (mBluetoothA2dpEnabledLock) {
            mBluetoothA2dpEnabled = on;
            sendMsg(mAudioHandler, MSG_SET_FORCE_BT_A2DP_USE, SENDMSG_QUEUE,
                    AudioSystem.FOR_MEDIA,
                    mBluetoothA2dpEnabled ? AudioSystem.FORCE_NONE : AudioSystem.FORCE_NO_BT_A2DP,
                    null, 0);
        }
    }

    /** @see AudioManager#isBluetoothA2dpOn() */
    public boolean isBluetoothA2dpOn() {
        synchronized (mBluetoothA2dpEnabledLock) {
            return mBluetoothA2dpEnabled;
        }
    }

    /** @see AudioManager#startBluetoothSco() */
    public void startBluetoothSco(IBinder cb){
        if (!checkAudioSettingsPermission("startBluetoothSco()") ||
                !mBootCompleted) {
            return;
        }
        ScoClient client = getScoClient(cb, true);
        client.incCount();
    }

    /** @see AudioManager#stopBluetoothSco() */
    public void stopBluetoothSco(IBinder cb){
        if (!checkAudioSettingsPermission("stopBluetoothSco()") ||
                !mBootCompleted) {
            return;
        }
        ScoClient client = getScoClient(cb, false);
        if (client != null) {
            client.decCount();
        }
    }


    private class ScoClient implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death
        private int mCreatorPid;
        private int mStartcount; // number of SCO connections started by this client

        ScoClient(IBinder cb) {
            mCb = cb;
            mCreatorPid = Binder.getCallingPid();
            mStartcount = 0;
        }

        public void binderDied() {
            synchronized(mScoClients) {
                Log.w(TAG, "SCO client died");
                int index = mScoClients.indexOf(this);
                if (index < 0) {
                    Log.w(TAG, "unregistered SCO client died");
                } else {
                    clearCount(true);
                    mScoClients.remove(this);
                }
            }
        }

        public void incCount() {
            synchronized(mScoClients) {
                requestScoState(BluetoothHeadset.STATE_AUDIO_CONNECTED);
                if (mStartcount == 0) {
                    try {
                        mCb.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        // client has already died!
                        Log.w(TAG, "ScoClient  incCount() could not link to "+mCb+" binder death");
                    }
                }
                mStartcount++;
            }
        }

        public void decCount() {
            synchronized(mScoClients) {
                if (mStartcount == 0) {
                    Log.w(TAG, "ScoClient.decCount() already 0");
                } else {
                    mStartcount--;
                    if (mStartcount == 0) {
                        try {
                            mCb.unlinkToDeath(this, 0);
                        } catch (NoSuchElementException e) {
                            Log.w(TAG, "decCount() going to 0 but not registered to binder");
                        }
                    }
                    requestScoState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                }
            }
        }

        public void clearCount(boolean stopSco) {
            synchronized(mScoClients) {
                if (mStartcount != 0) {
                    try {
                        mCb.unlinkToDeath(this, 0);
                    } catch (NoSuchElementException e) {
                        Log.w(TAG, "clearCount() mStartcount: "+mStartcount+" != 0 but not registered to binder");
                    }
                }
                mStartcount = 0;
                if (stopSco) {
                    requestScoState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                }
            }
        }

        public int getCount() {
            return mStartcount;
        }

        public IBinder getBinder() {
            return mCb;
        }

        public int getPid() {
            return mCreatorPid;
        }

        public int totalCount() {
            synchronized(mScoClients) {
                int count = 0;
                int size = mScoClients.size();
                for (int i = 0; i < size; i++) {
                    count += mScoClients.get(i).getCount();
                }
                return count;
            }
        }

        private void requestScoState(int state) {
            checkScoAudioState();
            if (totalCount() == 0) {
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    // Make sure that the state transitions to CONNECTING even if we cannot initiate
                    // the connection.
                    broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTING);
                    // Accept SCO audio activation only in NORMAL audio mode or if the mode is
                    // currently controlled by the same client process.
                    synchronized(mSetModeDeathHandlers) {
                        if ((mSetModeDeathHandlers.isEmpty() ||
                                mSetModeDeathHandlers.get(0).getPid() == mCreatorPid) &&
                                (mScoAudioState == SCO_STATE_INACTIVE ||
                                 mScoAudioState == SCO_STATE_DEACTIVATE_REQ)) {
                            if (mScoAudioState == SCO_STATE_INACTIVE) {
                                if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null) {
                                    if (mBluetoothHeadset.startScoUsingVirtualVoiceCall(
                                            mBluetoothHeadsetDevice)) {
                                        mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                                    } else {
                                        broadcastScoConnectionState(
                                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                                    }
                                } else if (getBluetoothHeadset()) {
                                    mScoAudioState = SCO_STATE_ACTIVATE_REQ;
                                }
                            } else {
                                mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                                broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_CONNECTED);
                            }
                        } else {
                            broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                        }
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED &&
                              (mScoAudioState == SCO_STATE_ACTIVE_INTERNAL ||
                               mScoAudioState == SCO_STATE_ACTIVATE_REQ)) {
                    if (mScoAudioState == SCO_STATE_ACTIVE_INTERNAL) {
                        if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null) {
                            if (!mBluetoothHeadset.stopScoUsingVirtualVoiceCall(
                                    mBluetoothHeadsetDevice)) {
                                mScoAudioState = SCO_STATE_INACTIVE;
                                broadcastScoConnectionState(
                                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                            }
                        } else if (getBluetoothHeadset()) {
                            mScoAudioState = SCO_STATE_DEACTIVATE_REQ;
                        }
                    } else {
                        mScoAudioState = SCO_STATE_INACTIVE;
                        broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                    }
                }
            }
        }
    }

    private void checkScoAudioState() {
        if (mBluetoothHeadset != null && mBluetoothHeadsetDevice != null &&
                mScoAudioState == SCO_STATE_INACTIVE &&
                mBluetoothHeadset.getAudioState(mBluetoothHeadsetDevice)
                != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
            mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
        }
    }

    private ScoClient getScoClient(IBinder cb, boolean create) {
        synchronized(mScoClients) {
            ScoClient client = null;
            int size = mScoClients.size();
            for (int i = 0; i < size; i++) {
                client = mScoClients.get(i);
                if (client.getBinder() == cb)
                    return client;
            }
            if (create) {
                client = new ScoClient(cb);
                mScoClients.add(client);
            }
            return client;
        }
    }

    public void clearAllScoClients(int exceptPid, boolean stopSco) {
        synchronized(mScoClients) {
            ScoClient savedClient = null;
            int size = mScoClients.size();
            for (int i = 0; i < size; i++) {
                ScoClient cl = mScoClients.get(i);
                if (cl.getPid() != exceptPid) {
                    cl.clearCount(stopSco);
                } else {
                    savedClient = cl;
                }
            }
            mScoClients.clear();
            if (savedClient != null) {
                mScoClients.add(savedClient);
            }
        }
    }

    private boolean getBluetoothHeadset() {
        boolean result = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            result = adapter.getProfileProxy(mContext, mBluetoothProfileServiceListener,
                                    BluetoothProfile.HEADSET);
        }
        // If we could not get a bluetooth headset proxy, send a failure message
        // without delay to reset the SCO audio state and clear SCO clients.
        // If we could get a proxy, send a delayed failure message that will reset our state
        // in case we don't receive onServiceConnected().
        sendMsg(mAudioHandler, MSG_BT_HEADSET_CNCT_FAILED,
                SENDMSG_REPLACE, 0, 0, null, result ? BT_HEADSET_CNCT_TIMEOUT_MS : 0);
        return result;
    }

    private void disconnectBluetoothSco(int exceptPid) {
        synchronized(mScoClients) {
            checkScoAudioState();
            if (mScoAudioState == SCO_STATE_ACTIVE_EXTERNAL ||
                    mScoAudioState == SCO_STATE_DEACTIVATE_EXT_REQ) {
                if (mBluetoothHeadsetDevice != null) {
                    if (mBluetoothHeadset != null) {
                        if (!mBluetoothHeadset.stopVoiceRecognition(
                                mBluetoothHeadsetDevice)) {
                            sendMsg(mAudioHandler, MSG_BT_HEADSET_CNCT_FAILED,
                                    SENDMSG_REPLACE, 0, 0, null, 0);
                        }
                    } else if (mScoAudioState == SCO_STATE_ACTIVE_EXTERNAL &&
                            getBluetoothHeadset()) {
                        mScoAudioState = SCO_STATE_DEACTIVATE_EXT_REQ;
                    }
                }
            } else {
                clearAllScoClients(exceptPid, true);
            }
        }
    }

    private void resetBluetoothSco() {
        synchronized(mScoClients) {
            clearAllScoClients(0, false);
            mScoAudioState = SCO_STATE_INACTIVE;
            broadcastScoConnectionState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
        }
    }

    private void broadcastScoConnectionState(int state) {
        if (state != mScoConnectionState) {
            Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, state);
            newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE,
                    mScoConnectionState);
            sendStickyBroadcastToAll(newIntent);
            mScoConnectionState = state;
        }
    }

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            BluetoothDevice btDevice;
            List<BluetoothDevice> deviceList;
            switch(profile) {
            case BluetoothProfile.A2DP:
                BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                deviceList = a2dp.getConnectedDevices();
                if (deviceList.size() > 0) {
                    btDevice = deviceList.get(0);
                    synchronized (mConnectedDevices) {
                        int state = a2dp.getConnectionState(btDevice);
                        int delay = checkSendBecomingNoisyIntent(
                                                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                                                (state == BluetoothA2dp.STATE_CONNECTED) ? 1 : 0);
                        queueMsgUnderWakeLock(mAudioHandler,
                                MSG_SET_A2DP_CONNECTION_STATE,
                                state,
                                0,
                                btDevice,
                                delay);
                    }
                }
                break;

            case BluetoothProfile.HEADSET:
                synchronized (mScoClients) {
                    // Discard timeout message
                    mAudioHandler.removeMessages(MSG_BT_HEADSET_CNCT_FAILED);
                    mBluetoothHeadset = (BluetoothHeadset) proxy;
                    deviceList = mBluetoothHeadset.getConnectedDevices();
                    if (deviceList.size() > 0) {
                        mBluetoothHeadsetDevice = deviceList.get(0);
                    } else {
                        mBluetoothHeadsetDevice = null;
                    }
                    // Refresh SCO audio state
                    checkScoAudioState();
                    // Continue pending action if any
                    if (mScoAudioState == SCO_STATE_ACTIVATE_REQ ||
                            mScoAudioState == SCO_STATE_DEACTIVATE_REQ ||
                            mScoAudioState == SCO_STATE_DEACTIVATE_EXT_REQ) {
                        boolean status = false;
                        if (mBluetoothHeadsetDevice != null) {
                            switch (mScoAudioState) {
                            case SCO_STATE_ACTIVATE_REQ:
                                mScoAudioState = SCO_STATE_ACTIVE_INTERNAL;
                                status = mBluetoothHeadset.startScoUsingVirtualVoiceCall(
                                        mBluetoothHeadsetDevice);
                                break;
                            case SCO_STATE_DEACTIVATE_REQ:
                                status = mBluetoothHeadset.stopScoUsingVirtualVoiceCall(
                                        mBluetoothHeadsetDevice);
                                break;
                            case SCO_STATE_DEACTIVATE_EXT_REQ:
                                status = mBluetoothHeadset.stopVoiceRecognition(
                                        mBluetoothHeadsetDevice);
                            }
                        }
                        if (!status) {
                            sendMsg(mAudioHandler, MSG_BT_HEADSET_CNCT_FAILED,
                                    SENDMSG_REPLACE, 0, 0, null, 0);
                        }
                    }
                }
                break;

            default:
                break;
            }
        }
        public void onServiceDisconnected(int profile) {
            switch(profile) {
            case BluetoothProfile.A2DP:
                synchronized (mConnectedDevices) {
                    if (mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP)) {
                        makeA2dpDeviceUnavailableNow(
                                mConnectedDevices.get(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP));
                    }
                }
                break;

            case BluetoothProfile.HEADSET:
                synchronized (mScoClients) {
                    mBluetoothHeadset = null;
                }
                break;

            default:
                break;
            }
        }
    };

    /** see AudioManager.setRemoteSubmixOn(boolean on) */
    public void setRemoteSubmixOn(boolean on, int address) {
        sendMsg(mAudioHandler, MSG_SET_RSX_CONNECTION_STATE,
                SENDMSG_REPLACE /* replace with QUEUE when multiple addresses are supported */,
                on ? 1 : 0 /*arg1*/,
                address /*arg2*/,
                null/*obj*/, 0/*delay*/);
    }

    private void onSetRsxConnectionState(int available, int address) {
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_IN_REMOTE_SUBMIX,
                available == 1 ?
                        AudioSystem.DEVICE_STATE_AVAILABLE : AudioSystem.DEVICE_STATE_UNAVAILABLE,
                String.valueOf(address) /*device_address*/);
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX,
                available == 1 ?
                        AudioSystem.DEVICE_STATE_AVAILABLE : AudioSystem.DEVICE_STATE_UNAVAILABLE,
                String.valueOf(address) /*device_address*/);
    }

    private void onCheckMusicActive() {
        synchronized (mSafeMediaVolumeState) {
            if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_INACTIVE) {
                int device = getDeviceForStream(AudioSystem.STREAM_MUSIC);

                if ((device & mSafeMediaVolumeDevices) != 0) {
                    sendMsg(mAudioHandler,
                            MSG_CHECK_MUSIC_ACTIVE,
                            SENDMSG_REPLACE,
                            0,
                            0,
                            null,
                            MUSIC_ACTIVE_POLL_PERIOD_MS);
                    int index = mStreamStates[AudioSystem.STREAM_MUSIC].getIndex(device,
                                                                            false /*lastAudible*/);
                    if (AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0) &&
                            (index > mSafeMediaVolumeIndex)) {
                        // Approximate cumulative active music time
                        mMusicActiveMs += MUSIC_ACTIVE_POLL_PERIOD_MS;
                        if (mMusicActiveMs > UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX) {
                            setSafeMediaVolumeEnabled(true);
                            mMusicActiveMs = 0;
                            mVolumePanel.postDisplaySafeVolumeWarning();
                        }
                    }
                }
            }
        }
    }

    private void onConfigureSafeVolume(boolean force) {
        synchronized (mSafeMediaVolumeState) {
            int mcc = mContext.getResources().getConfiguration().mcc;
            if ((mMcc != mcc) || ((mMcc == 0) && force)) {
                mSafeMediaVolumeIndex = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_safe_media_volume_index) * 10;
                boolean safeMediaVolumeEnabled = mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_safe_media_volume_enabled);
                if (safeMediaVolumeEnabled) {
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_ACTIVE;
                    enforceSafeMediaVolume();
                } else {
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_DISABLED;
                }
                mMcc = mcc;
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
    private boolean checkForRingerModeChange(int oldIndex, int direction,  int step) {
        boolean adjustVolumeIndex = true;
        int ringerMode = getRingerMode();

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
                    }
                } else {
                    // (oldIndex < step) is equivalent to (old UI index == 0)
                    if ((oldIndex < step) && mPrevVolDirection != AudioManager.ADJUST_LOWER) {
                        ringerMode = RINGER_MODE_SILENT;
                    }
                }
            }
            break;
        case RINGER_MODE_VIBRATE:
            if (!mHasVibrator) {
                Log.e(TAG, "checkForRingerModeChange() current ringer mode is vibrate" +
                        "but no vibrator is present");
                break;
            }
            if ((direction == AudioManager.ADJUST_LOWER)) {
                if (mPrevVolDirection != AudioManager.ADJUST_LOWER) {
                    ringerMode = RINGER_MODE_SILENT;
                }
            } else if (direction == AudioManager.ADJUST_RAISE) {
                ringerMode = RINGER_MODE_NORMAL;
            }
            adjustVolumeIndex = false;
            break;
        case RINGER_MODE_SILENT:
            if (direction == AudioManager.ADJUST_RAISE) {
                if (mHasVibrator) {
                    ringerMode = RINGER_MODE_VIBRATE;
                } else {
                    ringerMode = RINGER_MODE_NORMAL;
                }
            }
            adjustVolumeIndex = false;
            break;
        default:
            Log.e(TAG, "checkForRingerModeChange() wrong ringer mode: "+ringerMode);
            break;
        }

        setRingerMode(ringerMode);

        mPrevVolDirection = direction;

        return adjustVolumeIndex;
    }

    public boolean isStreamAffectedByRingerMode(int streamType) {
        return (mRingerModeAffectedStreams & (1 << streamType)) != 0;
    }

    private boolean isStreamMutedByRingerMode(int streamType) {
        return (mRingerModeMutedStreams & (1 << streamType)) != 0;
    }

    public boolean isStreamAffectedByMute(int streamType) {
        return (mMuteAffectedStreams & (1 << streamType)) != 0;
    }

    private void ensureValidDirection(int direction) {
        if (direction < AudioManager.ADJUST_LOWER || direction > AudioManager.ADJUST_RAISE) {
            throw new IllegalArgumentException("Bad direction " + direction);
        }
    }

    private void ensureValidSteps(int steps) {
        if (Math.abs(steps) > MAX_BATCH_VOLUME_ADJUST_STEPS) {
            throw new IllegalArgumentException("Bad volume adjust steps " + steps);
        }
    }

    private void ensureValidStreamType(int streamType) {
        if (streamType < 0 || streamType >= mStreamStates.length) {
            throw new IllegalArgumentException("Bad stream type " + streamType);
        }
    }

    private boolean isInCommunication() {
        boolean isOffhook = false;

        if (mVoiceCapable) {
            try {
                ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
                if (phone != null) isOffhook = phone.isOffhook();
            } catch (RemoteException e) {
                Log.w(TAG, "Couldn't connect to phone service", e);
            }
        }
        return (isOffhook || getMode() == AudioManager.MODE_IN_COMMUNICATION);
    }

    private int getActiveStreamType(int suggestedStreamType) {
        if (mVoiceCapable) {
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
                // Having the suggested stream be USE_DEFAULT_STREAM_TYPE is how remote control
                // volume can have priority over STREAM_MUSIC
                if (checkUpdateRemoteStateIfActive(AudioSystem.STREAM_MUSIC)) {
                    if (DEBUG_VOL)
                        Log.v(TAG, "getActiveStreamType: Forcing STREAM_REMOTE_MUSIC");
                    return STREAM_REMOTE_MUSIC;
                } else if (AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC,
                            DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS)) {
                    if (DEBUG_VOL)
                        Log.v(TAG, "getActiveStreamType: Forcing STREAM_MUSIC stream active");
                    return AudioSystem.STREAM_MUSIC;
                } else {
                    if (DEBUG_VOL)
                        Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING b/c default");
                    return AudioSystem.STREAM_RING;
                }
            } else if (AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0)) {
                if (DEBUG_VOL)
                    Log.v(TAG, "getActiveStreamType: Forcing STREAM_MUSIC stream active");
                return AudioSystem.STREAM_MUSIC;
            } else {
                if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Returning suggested type "
                        + suggestedStreamType);
                return suggestedStreamType;
            }
        } else {
            if (isInCommunication()) {
                if (AudioSystem.getForceUse(AudioSystem.FOR_COMMUNICATION)
                        == AudioSystem.FORCE_BT_SCO) {
                    if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_BLUETOOTH_SCO");
                    return AudioSystem.STREAM_BLUETOOTH_SCO;
                } else {
                    if (DEBUG_VOL)  Log.v(TAG, "getActiveStreamType: Forcing STREAM_VOICE_CALL");
                    return AudioSystem.STREAM_VOICE_CALL;
                }
            } else if (AudioSystem.isStreamActive(AudioSystem.STREAM_NOTIFICATION,
                    DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS) ||
                    AudioSystem.isStreamActive(AudioSystem.STREAM_RING,
                            DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS)) {
                if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_NOTIFICATION");
                return AudioSystem.STREAM_NOTIFICATION;
            } else if (suggestedStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                if (checkUpdateRemoteStateIfActive(AudioSystem.STREAM_MUSIC)) {
                    // Having the suggested stream be USE_DEFAULT_STREAM_TYPE is how remote control
                    // volume can have priority over STREAM_MUSIC
                    if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Forcing STREAM_REMOTE_MUSIC");
                    return STREAM_REMOTE_MUSIC;
                } else {
                    if (DEBUG_VOL)
                        Log.v(TAG, "getActiveStreamType: using STREAM_MUSIC as default");
                    return AudioSystem.STREAM_MUSIC;
                }
            } else {
                if (DEBUG_VOL) Log.v(TAG, "getActiveStreamType: Returning suggested type "
                        + suggestedStreamType);
                return suggestedStreamType;
            }
        }
    }

    private void broadcastRingerMode(int ringerMode) {
        // Send sticky broadcast
        Intent broadcast = new Intent(AudioManager.RINGER_MODE_CHANGED_ACTION);
        broadcast.putExtra(AudioManager.EXTRA_RINGER_MODE, ringerMode);
        broadcast.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        sendStickyBroadcastToAll(broadcast);
    }

    private void broadcastVibrateSetting(int vibrateType) {
        // Send broadcast
        if (ActivityManagerNative.isSystemReady()) {
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
        mMediaEventWakeLock.acquire();
        sendMsg(handler, msg, SENDMSG_QUEUE, arg1, arg2, obj, delay);
    }

    private static void sendMsg(Handler handler, int msg,
            int existingMsgPolicy, int arg1, int arg2, Object obj, int delay) {

        if (existingMsgPolicy == SENDMSG_REPLACE) {
            handler.removeMessages(msg);
        } else if (existingMsgPolicy == SENDMSG_NOOP && handler.hasMessages(msg)) {
            return;
        }

        handler.sendMessageDelayed(handler.obtainMessage(msg, arg1, arg2, obj), delay);
    }

    boolean checkAudioSettingsPermission(String method) {
        if (mContext.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS")
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        String msg = "Audio Settings Permission Denial: " + method + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid();
        Log.w(TAG, msg);
        return false;
    }

    private int getDeviceForStream(int stream) {
        int device = AudioSystem.getDevicesForStream(stream);
        if ((device & (device - 1)) != 0) {
            // Multiple device selection is either:
            //  - speaker + one other device: give priority to speaker in this case.
            //  - one A2DP device + another device: happens with duplicated output. In this case
            // retain the device on the A2DP output as the other must not correspond to an active
            // selection if not the speaker.
            if ((device & AudioSystem.DEVICE_OUT_SPEAKER) != 0) {
                device = AudioSystem.DEVICE_OUT_SPEAKER;
            } else {
                device &= AudioSystem.DEVICE_OUT_ALL_A2DP;
            }
        }
        return device;
    }

    public void setWiredDeviceConnectionState(int device, int state, String name) {
        synchronized (mConnectedDevices) {
            int delay = checkSendBecomingNoisyIntent(device, state);
            queueMsgUnderWakeLock(mAudioHandler,
                    MSG_SET_WIRED_DEVICE_CONNECTION_STATE,
                    device,
                    state,
                    name,
                    delay);
        }
    }

    public int setBluetoothA2dpDeviceConnectionState(BluetoothDevice device, int state)
    {
        int delay;
        synchronized (mConnectedDevices) {
            delay = checkSendBecomingNoisyIntent(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                                            (state == BluetoothA2dp.STATE_CONNECTED) ? 1 : 0);
            queueMsgUnderWakeLock(mAudioHandler,
                    MSG_SET_A2DP_CONNECTION_STATE,
                    state,
                    0,
                    device,
                    delay);
        }
        return delay;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Inner classes
    ///////////////////////////////////////////////////////////////////////////

    public class VolumeStreamState {
        private final int mStreamType;

        private String mVolumeIndexSettingName;
        private String mLastAudibleVolumeIndexSettingName;
        private int mIndexMax;
        private final ConcurrentHashMap<Integer, Integer> mIndex =
                                            new ConcurrentHashMap<Integer, Integer>(8, 0.75f, 4);
        private final ConcurrentHashMap<Integer, Integer> mLastAudibleIndex =
                                            new ConcurrentHashMap<Integer, Integer>(8, 0.75f, 4);
        private ArrayList<VolumeDeathHandler> mDeathHandlers; //handles mute/solo clients death

        private VolumeStreamState(String settingName, int streamType) {

            mVolumeIndexSettingName = settingName;
            mLastAudibleVolumeIndexSettingName = settingName + System.APPEND_FOR_LAST_AUDIBLE;

            mStreamType = streamType;
            mIndexMax = MAX_STREAM_VOLUME[streamType];
            AudioSystem.initStreamVolume(streamType, 0, mIndexMax);
            mIndexMax *= 10;

            // mDeathHandlers must be created before calling readSettings()
            mDeathHandlers = new ArrayList<VolumeDeathHandler>();

            readSettings();
        }

        public String getSettingNameForDevice(boolean lastAudible, int device) {
            String name = lastAudible ?
                            mLastAudibleVolumeIndexSettingName :
                            mVolumeIndexSettingName;
            String suffix = AudioSystem.getDeviceName(device);
            if (suffix.isEmpty()) {
                return name;
            }
            return name + "_" + suffix;
        }

        public synchronized void readSettings() {
            int remainingDevices = AudioSystem.DEVICE_OUT_ALL;

            // do not read system stream volume from settings: this stream is always aliased
            // to another stream type and its volume is never persisted. Values in settings can
            // only be stale values
            // on first call to readSettings() at init time, muteCount() is always 0 so we will
            // always create entries for default device
            if ((mStreamType == AudioSystem.STREAM_SYSTEM) ||
                    (mStreamType == AudioSystem.STREAM_SYSTEM_ENFORCED)) {
                int index = 10 * AudioManager.DEFAULT_STREAM_VOLUME[mStreamType];
                synchronized (mCameraSoundForced) {
                    if (mCameraSoundForced) {
                        index = mIndexMax;
                    }
                }
                if (muteCount() == 0) {
                    mIndex.put(AudioSystem.DEVICE_OUT_DEFAULT, index);
                }
                mLastAudibleIndex.put(AudioSystem.DEVICE_OUT_DEFAULT, index);
                return;
            }

            for (int i = 0; remainingDevices != 0; i++) {
                int device = (1 << i);
                if ((device & remainingDevices) == 0) {
                    continue;
                }
                remainingDevices &= ~device;

                // ignore settings for fixed volume devices: volume should always be at max
                if ((mStreamVolumeAlias[mStreamType] == AudioSystem.STREAM_MUSIC) &&
                        ((device & mFixedVolumeDevices) != 0)) {
                    if (muteCount() == 0) {
                        mIndex.put(device, mIndexMax);
                    }
                    mLastAudibleIndex.put(device, mIndexMax);
                    continue;
                }
                // retrieve current volume for device
                String name = getSettingNameForDevice(false /* lastAudible */, device);
                // if no volume stored for current stream and device, use default volume if default
                // device, continue otherwise
                int defaultIndex = (device == AudioSystem.DEVICE_OUT_DEFAULT) ?
                                        AudioManager.DEFAULT_STREAM_VOLUME[mStreamType] : -1;
                int index = Settings.System.getIntForUser(
                        mContentResolver, name, defaultIndex, UserHandle.USER_CURRENT);
                if (index == -1) {
                    continue;
                }

                // retrieve last audible volume for device
                name = getSettingNameForDevice(true  /* lastAudible */, device);
                // use stored last audible index if present, otherwise use current index if not 0
                // or default index
                defaultIndex = (index > 0) ?
                                    index : AudioManager.DEFAULT_STREAM_VOLUME[mStreamType];
                int lastAudibleIndex = Settings.System.getIntForUser(
                        mContentResolver, name, defaultIndex, UserHandle.USER_CURRENT);

                // a last audible index of 0 should never be stored for ring and notification
                // streams on phones (voice capable devices).
                if ((lastAudibleIndex == 0) && mVoiceCapable &&
                                (mStreamVolumeAlias[mStreamType] == AudioSystem.STREAM_RING)) {
                    lastAudibleIndex = AudioManager.DEFAULT_STREAM_VOLUME[mStreamType];
                    // Correct the data base
                    sendMsg(mAudioHandler,
                            MSG_PERSIST_VOLUME,
                            SENDMSG_QUEUE,
                            PERSIST_LAST_AUDIBLE,
                            device,
                            this,
                            PERSIST_DELAY);
                }
                mLastAudibleIndex.put(device, getValidIndex(10 * lastAudibleIndex));
                // the initial index should never be 0 for ring and notification streams on phones
                // (voice capable devices) if not in silent or vibrate mode.
                if ((index == 0) && (mRingerMode == AudioManager.RINGER_MODE_NORMAL) &&
                        mVoiceCapable &&
                        (mStreamVolumeAlias[mStreamType] == AudioSystem.STREAM_RING)) {
                    index = lastAudibleIndex;
                    // Correct the data base
                    sendMsg(mAudioHandler,
                            MSG_PERSIST_VOLUME,
                            SENDMSG_QUEUE,
                            PERSIST_CURRENT,
                            device,
                            this,
                            PERSIST_DELAY);
                }
                if (muteCount() == 0) {
                    mIndex.put(device, getValidIndex(10 * index));
                }
            }
        }

        public void applyDeviceVolume(int device) {
            AudioSystem.setStreamVolumeIndex(mStreamType,
                                             (getIndex(device, false  /* lastAudible */) + 5)/10,
                                             device);
        }

        public synchronized void applyAllVolumes() {
            // apply default volume first: by convention this will reset all
            // devices volumes in audio policy manager to the supplied value
            AudioSystem.setStreamVolumeIndex(mStreamType,
                    (getIndex(AudioSystem.DEVICE_OUT_DEFAULT, false /* lastAudible */) + 5)/10,
                    AudioSystem.DEVICE_OUT_DEFAULT);
            // then apply device specific volumes
            Set set = mIndex.entrySet();
            Iterator i = set.iterator();
            while (i.hasNext()) {
                Map.Entry entry = (Map.Entry)i.next();
                int device = ((Integer)entry.getKey()).intValue();
                if (device != AudioSystem.DEVICE_OUT_DEFAULT) {
                    AudioSystem.setStreamVolumeIndex(mStreamType,
                                                     ((Integer)entry.getValue() + 5)/10,
                                                     device);
                }
            }
        }

        public boolean adjustIndex(int deltaIndex, int device) {
            return setIndex(getIndex(device,
                                     false  /* lastAudible */) + deltaIndex,
                            device,
                            true  /* lastAudible */);
        }

        public synchronized boolean setIndex(int index, int device, boolean lastAudible) {
            int oldIndex = getIndex(device, false  /* lastAudible */);
            index = getValidIndex(index);
            synchronized (mCameraSoundForced) {
                if ((mStreamType == AudioSystem.STREAM_SYSTEM_ENFORCED) && mCameraSoundForced) {
                    index = mIndexMax;
                }
            }
            mIndex.put(device, getValidIndex(index));

            if (oldIndex != index) {
                if (lastAudible) {
                    mLastAudibleIndex.put(device, index);
                }
                // Apply change to all streams using this one as alias
                // if changing volume of current device, also change volume of current
                // device on aliased stream
                boolean currentDevice = (device == getDeviceForStream(mStreamType));
                int numStreamTypes = AudioSystem.getNumStreamTypes();
                for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                    if (streamType != mStreamType &&
                            mStreamVolumeAlias[streamType] == mStreamType) {
                        int scaledIndex = rescaleIndex(index, mStreamType, streamType);
                        mStreamStates[streamType].setIndex(scaledIndex,
                                                           device,
                                                           lastAudible);
                        if (currentDevice) {
                            mStreamStates[streamType].setIndex(scaledIndex,
                                                               getDeviceForStream(streamType),
                                                               lastAudible);
                        }
                    }
                }
                return true;
            } else {
                return false;
            }
        }

        public synchronized int getIndex(int device, boolean lastAudible) {
            ConcurrentHashMap <Integer, Integer> indexes;
            if (lastAudible) {
                indexes = mLastAudibleIndex;
            } else {
                indexes = mIndex;
            }
            Integer index = indexes.get(device);
            if (index == null) {
                // there is always an entry for AudioSystem.DEVICE_OUT_DEFAULT
                index = indexes.get(AudioSystem.DEVICE_OUT_DEFAULT);
            }
            return index.intValue();
        }

        public synchronized void setLastAudibleIndex(int index, int device) {
            // Apply change to all streams using this one as alias
            // if changing volume of current device, also change volume of current
            // device on aliased stream
            boolean currentDevice = (device == getDeviceForStream(mStreamType));
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != mStreamType &&
                        mStreamVolumeAlias[streamType] == mStreamType) {
                    int scaledIndex = rescaleIndex(index, mStreamType, streamType);
                    mStreamStates[streamType].setLastAudibleIndex(scaledIndex, device);
                    if (currentDevice) {
                        mStreamStates[streamType].setLastAudibleIndex(scaledIndex,
                                                                   getDeviceForStream(streamType));
                    }
                }
            }
            mLastAudibleIndex.put(device, getValidIndex(index));
        }

        public synchronized void adjustLastAudibleIndex(int deltaIndex, int device) {
            setLastAudibleIndex(getIndex(device,
                                         true  /* lastAudible */) + deltaIndex,
                                device);
        }

        public int getMaxIndex() {
            return mIndexMax;
        }

        // only called by setAllIndexes() which is already synchronized
        public ConcurrentHashMap <Integer, Integer> getAllIndexes(boolean lastAudible) {
            if (lastAudible) {
                return mLastAudibleIndex;
            } else {
                return mIndex;
            }
        }

        public synchronized void setAllIndexes(VolumeStreamState srcStream, boolean lastAudible) {
            ConcurrentHashMap <Integer, Integer> indexes = srcStream.getAllIndexes(lastAudible);
            Set set = indexes.entrySet();
            Iterator i = set.iterator();
            while (i.hasNext()) {
                Map.Entry entry = (Map.Entry)i.next();
                int device = ((Integer)entry.getKey()).intValue();
                int index = ((Integer)entry.getValue()).intValue();
                index = rescaleIndex(index, srcStream.getStreamType(), mStreamType);

                if (lastAudible) {
                    setLastAudibleIndex(index, device);
                } else {
                    setIndex(index, device, false /* lastAudible */);
                }
            }
        }

        public synchronized void setAllIndexesToMax() {
            Set set = mIndex.entrySet();
            Iterator i = set.iterator();
            while (i.hasNext()) {
                Map.Entry entry = (Map.Entry)i.next();
                entry.setValue(mIndexMax);
            }
            set = mLastAudibleIndex.entrySet();
            i = set.iterator();
            while (i.hasNext()) {
                Map.Entry entry = (Map.Entry)i.next();
                entry.setValue(mIndexMax);
            }
        }

        public synchronized void mute(IBinder cb, boolean state) {
            VolumeDeathHandler handler = getDeathHandler(cb, state);
            if (handler == null) {
                Log.e(TAG, "Could not get client death handler for stream: "+mStreamType);
                return;
            }
            handler.mute(state);
        }

        public int getStreamType() {
            return mStreamType;
        }

        private int getValidIndex(int index) {
            if (index < 0) {
                return 0;
            } else if (index > mIndexMax) {
                return mIndexMax;
            }

            return index;
        }

        private class VolumeDeathHandler implements IBinder.DeathRecipient {
            private IBinder mICallback; // To be notified of client's death
            private int mMuteCount; // Number of active mutes for this client

            VolumeDeathHandler(IBinder cb) {
                mICallback = cb;
            }

            // must be called while synchronized on parent VolumeStreamState
            public void mute(boolean state) {
                if (state) {
                    if (mMuteCount == 0) {
                        // Register for client death notification
                        try {
                            // mICallback can be 0 if muted by AudioService
                            if (mICallback != null) {
                                mICallback.linkToDeath(this, 0);
                            }
                            mDeathHandlers.add(this);
                            // If the stream is not yet muted by any client, set level to 0
                            if (muteCount() == 0) {
                                Set set = mIndex.entrySet();
                                Iterator i = set.iterator();
                                while (i.hasNext()) {
                                    Map.Entry entry = (Map.Entry)i.next();
                                    int device = ((Integer)entry.getKey()).intValue();
                                    setIndex(0, device, false /* lastAudible */);
                                }
                                sendMsg(mAudioHandler,
                                        MSG_SET_ALL_VOLUMES,
                                        SENDMSG_QUEUE,
                                        0,
                                        0,
                                        VolumeStreamState.this, 0);
                            }
                        } catch (RemoteException e) {
                            // Client has died!
                            binderDied();
                            return;
                        }
                    } else {
                        Log.w(TAG, "stream: "+mStreamType+" was already muted by this client");
                    }
                    mMuteCount++;
                } else {
                    if (mMuteCount == 0) {
                        Log.e(TAG, "unexpected unmute for stream: "+mStreamType);
                    } else {
                        mMuteCount--;
                        if (mMuteCount == 0) {
                            // Unregister from client death notification
                            mDeathHandlers.remove(this);
                            // mICallback can be 0 if muted by AudioService
                            if (mICallback != null) {
                                mICallback.unlinkToDeath(this, 0);
                            }
                            if (muteCount() == 0) {
                                // If the stream is not muted any more, restore its volume if
                                // ringer mode allows it
                                if (!isStreamAffectedByRingerMode(mStreamType) ||
                                        mRingerMode == AudioManager.RINGER_MODE_NORMAL) {
                                    Set set = mIndex.entrySet();
                                    Iterator i = set.iterator();
                                    while (i.hasNext()) {
                                        Map.Entry entry = (Map.Entry)i.next();
                                        int device = ((Integer)entry.getKey()).intValue();
                                        setIndex(getIndex(device,
                                                          true  /* lastAudible */),
                                                 device,
                                                 false  /* lastAudible */);
                                    }
                                    sendMsg(mAudioHandler,
                                            MSG_SET_ALL_VOLUMES,
                                            SENDMSG_QUEUE,
                                            0,
                                            0,
                                            VolumeStreamState.this, 0);
                                }
                            }
                        }
                    }
                }
            }

            public void binderDied() {
                Log.w(TAG, "Volume service client died for stream: "+mStreamType);
                if (mMuteCount != 0) {
                    // Reset all active mute requests from this client.
                    mMuteCount = 1;
                    mute(false);
                }
            }
        }

        private synchronized int muteCount() {
            int count = 0;
            int size = mDeathHandlers.size();
            for (int i = 0; i < size; i++) {
                count += mDeathHandlers.get(i).mMuteCount;
            }
            return count;
        }

        // only called by mute() which is already synchronized
        private VolumeDeathHandler getDeathHandler(IBinder cb, boolean state) {
            VolumeDeathHandler handler;
            int size = mDeathHandlers.size();
            for (int i = 0; i < size; i++) {
                handler = mDeathHandlers.get(i);
                if (cb == handler.mICallback) {
                    return handler;
                }
            }
            // If this is the first mute request for this client, create a new
            // client death handler. Otherwise, it is an out of sequence unmute request.
            if (state) {
                handler = new VolumeDeathHandler(cb);
            } else {
                Log.w(TAG, "stream was not muted by this client");
                handler = null;
            }
            return handler;
        }

        private void dump(PrintWriter pw) {
            pw.print("   Mute count: ");
            pw.println(muteCount());
            pw.print("   Current: ");
            Set set = mIndex.entrySet();
            Iterator i = set.iterator();
            while (i.hasNext()) {
                Map.Entry entry = (Map.Entry)i.next();
                pw.print(Integer.toHexString(((Integer)entry.getKey()).intValue())
                             + ": " + ((((Integer)entry.getValue()).intValue() + 5) / 10)+", ");
            }
            pw.print("\n   Last audible: ");
            set = mLastAudibleIndex.entrySet();
            i = set.iterator();
            while (i.hasNext()) {
                Map.Entry entry = (Map.Entry)i.next();
                pw.print(Integer.toHexString(((Integer)entry.getKey()).intValue())
                             + ": " + ((((Integer)entry.getValue()).intValue() + 5) / 10)+", ");
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

    /** Handles internal volume messages in separate volume thread. */
    private class AudioHandler extends Handler {

        private void setDeviceVolume(VolumeStreamState streamState, int device) {

            // Apply volume
            streamState.applyDeviceVolume(device);

            // Apply change to all streams using this one as alias
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType &&
                        mStreamVolumeAlias[streamType] == streamState.mStreamType) {
                    mStreamStates[streamType].applyDeviceVolume(getDeviceForStream(streamType));
                }
            }

            // Post a persist volume msg
            sendMsg(mAudioHandler,
                    MSG_PERSIST_VOLUME,
                    SENDMSG_QUEUE,
                    PERSIST_CURRENT|PERSIST_LAST_AUDIBLE,
                    device,
                    streamState,
                    PERSIST_DELAY);

        }

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

        private void persistVolume(VolumeStreamState streamState,
                                   int persistType,
                                   int device) {
            if ((persistType & PERSIST_CURRENT) != 0) {
                System.putIntForUser(mContentResolver,
                          streamState.getSettingNameForDevice(false /* lastAudible */, device),
                          (streamState.getIndex(device, false /* lastAudible */) + 5)/ 10,
                          UserHandle.USER_CURRENT);
            }
            if ((persistType & PERSIST_LAST_AUDIBLE) != 0) {
                System.putIntForUser(mContentResolver,
                        streamState.getSettingNameForDevice(true /* lastAudible */, device),
                        (streamState.getIndex(device, true  /* lastAudible */) + 5) / 10,
                        UserHandle.USER_CURRENT);
            }
        }

        private void persistRingerMode(int ringerMode) {
            Settings.Global.putInt(mContentResolver, Settings.Global.MODE_RINGER, ringerMode);
        }

        private void playSoundEffect(int effectType, int volume) {
            synchronized (mSoundEffectsLock) {
                if (mSoundPool == null) {
                    return;
                }
                float volFloat;
                // use default if volume is not specified by caller
                if (volume < 0) {
                    volFloat = (float)Math.pow(10, (float)sSoundEffectVolumeDb/20);
                } else {
                    volFloat = (float) volume / 1000.0f;
                }

                if (SOUND_EFFECT_FILES_MAP[effectType][1] > 0) {
                    mSoundPool.play(SOUND_EFFECT_FILES_MAP[effectType][1], volFloat, volFloat, 0, 0, 1.0f);
                } else {
                    MediaPlayer mediaPlayer = new MediaPlayer();
                    try {
                        String filePath = Environment.getRootDirectory() + SOUND_EFFECTS_PATH + SOUND_EFFECT_FILES[SOUND_EFFECT_FILES_MAP[effectType][0]];
                        mediaPlayer.setDataSource(filePath);
                        mediaPlayer.setAudioStreamType(AudioSystem.STREAM_SYSTEM);
                        mediaPlayer.prepare();
                        mediaPlayer.setVolume(volFloat, volFloat);
                        mediaPlayer.setOnCompletionListener(new OnCompletionListener() {
                            public void onCompletion(MediaPlayer mp) {
                                cleanupPlayer(mp);
                            }
                        });
                        mediaPlayer.setOnErrorListener(new OnErrorListener() {
                            public boolean onError(MediaPlayer mp, int what, int extra) {
                                cleanupPlayer(mp);
                                return true;
                            }
                        });
                        mediaPlayer.start();
                    } catch (IOException ex) {
                        Log.w(TAG, "MediaPlayer IOException: "+ex);
                    } catch (IllegalArgumentException ex) {
                        Log.w(TAG, "MediaPlayer IllegalArgumentException: "+ex);
                    } catch (IllegalStateException ex) {
                        Log.w(TAG, "MediaPlayer IllegalStateException: "+ex);
                    }
                }
            }
        }

        private void onHandlePersistMediaButtonReceiver(ComponentName receiver) {
            Settings.System.putStringForUser(mContentResolver,
                                             Settings.System.MEDIA_BUTTON_RECEIVER,
                                             receiver == null ? "" : receiver.flattenToString(),
                                             UserHandle.USER_CURRENT);
        }

        private void cleanupPlayer(MediaPlayer mp) {
            if (mp != null) {
                try {
                    mp.stop();
                    mp.release();
                } catch (IllegalStateException ex) {
                    Log.w(TAG, "MediaPlayer IllegalStateException: "+ex);
                }
            }
        }

        private void setForceUse(int usage, int config) {
            AudioSystem.setForceUse(usage, config);
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
                    persistVolume((VolumeStreamState) msg.obj, msg.arg1, msg.arg2);
                    break;

                case MSG_PERSIST_MASTER_VOLUME:
                    Settings.System.putFloatForUser(mContentResolver,
                                                    Settings.System.VOLUME_MASTER,
                                                    (float)msg.arg1 / (float)1000.0,
                                                    UserHandle.USER_CURRENT);
                    break;

                case MSG_PERSIST_MASTER_VOLUME_MUTE:
                    Settings.System.putIntForUser(mContentResolver,
                                                 Settings.System.VOLUME_MASTER_MUTE,
                                                 msg.arg1,
                                                 UserHandle.USER_CURRENT);
                    break;

                case MSG_PERSIST_RINGER_MODE:
                    // note that the value persisted is the current ringer mode, not the
                    // value of ringer mode as of the time the request was made to persist
                    persistRingerMode(getRingerMode());
                    break;

                case MSG_MEDIA_SERVER_DIED:
                    if (!mMediaServerOk) {
                        Log.e(TAG, "Media server died.");
                        // Force creation of new IAudioFlinger interface so that we are notified
                        // when new media_server process is back to life.
                        AudioSystem.setErrorCallback(mAudioSystemCallback);
                        sendMsg(mAudioHandler, MSG_MEDIA_SERVER_DIED, SENDMSG_NOOP, 0, 0,
                                null, 500);
                    }
                    break;

                case MSG_MEDIA_SERVER_STARTED:
                    Log.e(TAG, "Media server started.");
                    // indicate to audio HAL that we start the reconfiguration phase after a media
                    // server crash
                    // Note that MSG_MEDIA_SERVER_STARTED message is only received when the media server
                    // process restarts after a crash, not the first time it is started.
                    AudioSystem.setParameters("restarting=true");

                    // Restore device connection states
                    synchronized (mConnectedDevices) {
                        Set set = mConnectedDevices.entrySet();
                        Iterator i = set.iterator();
                        while (i.hasNext()) {
                            Map.Entry device = (Map.Entry)i.next();
                            AudioSystem.setDeviceConnectionState(
                                                            ((Integer)device.getKey()).intValue(),
                                                            AudioSystem.DEVICE_STATE_AVAILABLE,
                                                            (String)device.getValue());
                        }
                    }
                    // Restore call state
                    AudioSystem.setPhoneState(mMode);

                    // Restore forced usage for communcations and record
                    AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, mForcedUseForComm);
                    AudioSystem.setForceUse(AudioSystem.FOR_RECORD, mForcedUseForComm);
                    AudioSystem.setForceUse(AudioSystem.FOR_SYSTEM, mCameraSoundForced ?
                                    AudioSystem.FORCE_SYSTEM_ENFORCED : AudioSystem.FORCE_NONE);

                    // Restore stream volumes
                    int numStreamTypes = AudioSystem.getNumStreamTypes();
                    for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                        VolumeStreamState streamState = mStreamStates[streamType];
                        AudioSystem.initStreamVolume(streamType, 0, (streamState.mIndexMax + 5) / 10);

                        streamState.applyAllVolumes();
                    }

                    // Restore ringer mode
                    setRingerModeInt(getRingerMode(), false);

                    // Restore master volume
                    restoreMasterVolume();

                    // Reset device orientation (if monitored for this device)
                    if (mMonitorOrientation) {
                        setOrientationForAudioSystem();
                    }

                    synchronized (mBluetoothA2dpEnabledLock) {
                        AudioSystem.setForceUse(AudioSystem.FOR_MEDIA,
                                mBluetoothA2dpEnabled ?
                                        AudioSystem.FORCE_NONE : AudioSystem.FORCE_NO_BT_A2DP);
                    }
                    // indicate the end of reconfiguration phase to audio HAL
                    AudioSystem.setParameters("restarting=false");
                    break;

                case MSG_LOAD_SOUND_EFFECTS:
                    loadSoundEffects();
                    break;

                case MSG_PLAY_SOUND_EFFECT:
                    playSoundEffect(msg.arg1, msg.arg2);
                    break;

                case MSG_BTA2DP_DOCK_TIMEOUT:
                    // msg.obj  == address of BTA2DP device
                    synchronized (mConnectedDevices) {
                        makeA2dpDeviceUnavailableNow( (String) msg.obj );
                    }
                    break;

                case MSG_SET_FORCE_USE:
                case MSG_SET_FORCE_BT_A2DP_USE:
                    setForceUse(msg.arg1, msg.arg2);
                    break;

                case MSG_PERSIST_MEDIABUTTONRECEIVER:
                    onHandlePersistMediaButtonReceiver( (ComponentName) msg.obj );
                    break;

                case MSG_RCDISPLAY_CLEAR:
                    onRcDisplayClear();
                    break;

                case MSG_RCDISPLAY_UPDATE:
                    // msg.obj is guaranteed to be non null
                    onRcDisplayUpdate( (RemoteControlStackEntry) msg.obj, msg.arg1);
                    break;

                case MSG_BT_HEADSET_CNCT_FAILED:
                    resetBluetoothSco();
                    break;

                case MSG_SET_WIRED_DEVICE_CONNECTION_STATE:
                    onSetWiredDeviceConnectionState(msg.arg1, msg.arg2, (String)msg.obj);
                    mMediaEventWakeLock.release();
                    break;

                case MSG_SET_A2DP_CONNECTION_STATE:
                    onSetA2dpConnectionState((BluetoothDevice)msg.obj, msg.arg1);
                    mMediaEventWakeLock.release();
                    break;

                case MSG_REPORT_NEW_ROUTES: {
                    int N = mRoutesObservers.beginBroadcast();
                    if (N > 0) {
                        AudioRoutesInfo routes;
                        synchronized (mCurAudioRoutes) {
                            routes = new AudioRoutesInfo(mCurAudioRoutes);
                        }
                        while (N > 0) {
                            N--;
                            IAudioRoutesObserver obs = mRoutesObservers.getBroadcastItem(N);
                            try {
                                obs.dispatchAudioRoutesChanged(routes);
                            } catch (RemoteException e) {
                            }
                        }
                    }
                    mRoutesObservers.finishBroadcast();
                    break;
                }

                case MSG_REEVALUATE_REMOTE:
                    onReevaluateRemote();
                    break;

                case MSG_RCC_NEW_PLAYBACK_INFO:
                    onNewPlaybackInfoForRcc(msg.arg1 /* rccId */, msg.arg2 /* key */,
                            ((Integer)msg.obj).intValue() /* value */);
                    break;
                case MSG_RCC_NEW_VOLUME_OBS:
                    onRegisterVolumeObserverForRcc(msg.arg1 /* rccId */,
                            (IRemoteVolumeObserver)msg.obj /* rvo */);
                    break;

                case MSG_SET_RSX_CONNECTION_STATE:
                    onSetRsxConnectionState(msg.arg1/*available*/, msg.arg2/*address*/);
                    break;

                case MSG_CHECK_MUSIC_ACTIVE:
                    onCheckMusicActive();
                    break;

                case MSG_BROADCAST_AUDIO_BECOMING_NOISY:
                    onSendBecomingNoisyIntent();
                    break;

                case MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED:
                case MSG_CONFIGURE_SAFE_MEDIA_VOLUME:
                    onConfigureSafeVolume((msg.what == MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED));
                    break;
            }
        }
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver() {
            super(new Handler());
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.MODE_RINGER_STREAMS_AFFECTED), false, this);
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.DOCK_AUDIO_MEDIA_ENABLED), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            // FIXME This synchronized is not necessary if mSettingsLock only protects mRingerMode.
            //       However there appear to be some missing locks around mRingerModeMutedStreams
            //       and mRingerModeAffectedStreams, so will leave this synchronized for now.
            //       mRingerModeMutedStreams and mMuteAffectedStreams are safe (only accessed once).
            synchronized (mSettingsLock) {
                int ringerModeAffectedStreams = Settings.System.getIntForUser(mContentResolver,
                       Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                       ((1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_NOTIFICATION)|
                       (1 << AudioSystem.STREAM_SYSTEM)|(1 << AudioSystem.STREAM_SYSTEM_ENFORCED)),
                       UserHandle.USER_CURRENT);
                if (mVoiceCapable) {
                    ringerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_MUSIC);
                } else {
                    ringerModeAffectedStreams |= (1 << AudioSystem.STREAM_MUSIC);
                }
                synchronized (mCameraSoundForced) {
                    if (mCameraSoundForced) {
                        ringerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                    } else {
                        ringerModeAffectedStreams |= (1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                    }
                }
                if (ringerModeAffectedStreams != mRingerModeAffectedStreams) {
                    /*
                     * Ensure all stream types that should be affected by ringer mode
                     * are in the proper state.
                     */
                    mRingerModeAffectedStreams = ringerModeAffectedStreams;
                    setRingerModeInt(getRingerMode(), false);
                }
                readDockAudioSettings(mContentResolver);
            }
        }
    }

    // must be called synchronized on mConnectedDevices
    private void makeA2dpDeviceAvailable(String address) {
        // enable A2DP before notifying A2DP connection to avoid unecessary processing in
        // audio policy manager
        setBluetoothA2dpOnInt(true);
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_AVAILABLE,
                address);
        // Reset A2DP suspend state each time a new sink is connected
        AudioSystem.setParameters("A2dpSuspended=false");
        mConnectedDevices.put( new Integer(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP),
                address);
    }

    private void onSendBecomingNoisyIntent() {
        sendBroadcastToAll(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    // must be called synchronized on mConnectedDevices
    private void makeA2dpDeviceUnavailableNow(String address) {
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                address);
        mConnectedDevices.remove(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
    }

    // must be called synchronized on mConnectedDevices
    private void makeA2dpDeviceUnavailableLater(String address) {
        // prevent any activity on the A2DP audio output to avoid unwanted
        // reconnection of the sink.
        AudioSystem.setParameters("A2dpSuspended=true");
        // the device will be made unavailable later, so consider it disconnected right away
        mConnectedDevices.remove(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        // send the delayed message to make the device unavailable later
        Message msg = mAudioHandler.obtainMessage(MSG_BTA2DP_DOCK_TIMEOUT, address);
        mAudioHandler.sendMessageDelayed(msg, BTA2DP_DOCK_TIMEOUT_MILLIS);

    }

    // must be called synchronized on mConnectedDevices
    private void cancelA2dpDeviceTimeout() {
        mAudioHandler.removeMessages(MSG_BTA2DP_DOCK_TIMEOUT);
    }

    // must be called synchronized on mConnectedDevices
    private boolean hasScheduledA2dpDockTimeout() {
        return mAudioHandler.hasMessages(MSG_BTA2DP_DOCK_TIMEOUT);
    }

    private void onSetA2dpConnectionState(BluetoothDevice btDevice, int state)
    {
        if (btDevice == null) {
            return;
        }
        String address = btDevice.getAddress();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            address = "";
        }
        synchronized (mConnectedDevices) {
            boolean isConnected =
                (mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP) &&
                 mConnectedDevices.get(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP).equals(address));

            if (isConnected && state != BluetoothProfile.STATE_CONNECTED) {
                if (btDevice.isBluetoothDock()) {
                    if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        // introduction of a delay for transient disconnections of docks when
                        // power is rapidly turned off/on, this message will be canceled if
                        // we reconnect the dock under a preset delay
                        makeA2dpDeviceUnavailableLater(address);
                        // the next time isConnected is evaluated, it will be false for the dock
                    }
                } else {
                    makeA2dpDeviceUnavailableNow(address);
                }
                synchronized (mCurAudioRoutes) {
                    if (mCurAudioRoutes.mBluetoothName != null) {
                        mCurAudioRoutes.mBluetoothName = null;
                        sendMsg(mAudioHandler, MSG_REPORT_NEW_ROUTES,
                                SENDMSG_NOOP, 0, 0, null, 0);
                    }
                }
            } else if (!isConnected && state == BluetoothProfile.STATE_CONNECTED) {
                if (btDevice.isBluetoothDock()) {
                    // this could be a reconnection after a transient disconnection
                    cancelA2dpDeviceTimeout();
                    mDockAddress = address;
                } else {
                    // this could be a connection of another A2DP device before the timeout of
                    // a dock: cancel the dock timeout, and make the dock unavailable now
                    if(hasScheduledA2dpDockTimeout()) {
                        cancelA2dpDeviceTimeout();
                        makeA2dpDeviceUnavailableNow(mDockAddress);
                    }
                }
                makeA2dpDeviceAvailable(address);
                synchronized (mCurAudioRoutes) {
                    String name = btDevice.getAliasName();
                    if (!TextUtils.equals(mCurAudioRoutes.mBluetoothName, name)) {
                        mCurAudioRoutes.mBluetoothName = name;
                        sendMsg(mAudioHandler, MSG_REPORT_NEW_ROUTES,
                                SENDMSG_NOOP, 0, 0, null, 0);
                    }
                }
            }
        }
    }

    private boolean handleDeviceConnection(boolean connected, int device, String params) {
        synchronized (mConnectedDevices) {
            boolean isConnected = (mConnectedDevices.containsKey(device) &&
                    (params.isEmpty() || mConnectedDevices.get(device).equals(params)));

            if (isConnected && !connected) {
                AudioSystem.setDeviceConnectionState(device,
                                              AudioSystem.DEVICE_STATE_UNAVAILABLE,
                                              mConnectedDevices.get(device));
                 mConnectedDevices.remove(device);
                 return true;
            } else if (!isConnected && connected) {
                 AudioSystem.setDeviceConnectionState(device,
                                                      AudioSystem.DEVICE_STATE_AVAILABLE,
                                                      params);
                 mConnectedDevices.put(new Integer(device), params);
                 return true;
            }
        }
        return false;
    }

    // Devices which removal triggers intent ACTION_AUDIO_BECOMING_NOISY. The intent is only
    // sent if none of these devices is connected.
    int mBecomingNoisyIntentDevices =
            AudioSystem.DEVICE_OUT_WIRED_HEADSET | AudioSystem.DEVICE_OUT_WIRED_HEADPHONE |
            AudioSystem.DEVICE_OUT_ALL_A2DP | AudioSystem.DEVICE_OUT_AUX_DIGITAL |
            AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET | AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET |
            AudioSystem.DEVICE_OUT_ALL_USB;

    // must be called before removing the device from mConnectedDevices
    private int checkSendBecomingNoisyIntent(int device, int state) {
        int delay = 0;
        if ((state == 0) && ((device & mBecomingNoisyIntentDevices) != 0)) {
            int devices = 0;
            for (int dev : mConnectedDevices.keySet()) {
                if ((dev & mBecomingNoisyIntentDevices) != 0) {
                   devices |= dev;
                }
            }
            if (devices == device) {
                sendMsg(mAudioHandler,
                        MSG_BROADCAST_AUDIO_BECOMING_NOISY,
                        SENDMSG_REPLACE,
                        0,
                        0,
                        null,
                        0);
                delay = 1000;
            }
        }

        if (mAudioHandler.hasMessages(MSG_SET_A2DP_CONNECTION_STATE) ||
                mAudioHandler.hasMessages(MSG_SET_WIRED_DEVICE_CONNECTION_STATE)) {
            delay = 1000;
        }
        return delay;
    }

    private void sendDeviceConnectionIntent(int device, int state, String name)
    {
        Intent intent = new Intent();

        intent.putExtra("state", state);
        intent.putExtra("name", name);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

        int connType = 0;

        if (device == AudioSystem.DEVICE_OUT_WIRED_HEADSET) {
            connType = AudioRoutesInfo.MAIN_HEADSET;
            intent.setAction(Intent.ACTION_HEADSET_PLUG);
            intent.putExtra("microphone", 1);
        } else if (device == AudioSystem.DEVICE_OUT_WIRED_HEADPHONE) {
            connType = AudioRoutesInfo.MAIN_HEADPHONES;
            intent.setAction(Intent.ACTION_HEADSET_PLUG);
            intent.putExtra("microphone", 0);
        } else if (device == AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET) {
            connType = AudioRoutesInfo.MAIN_DOCK_SPEAKERS;
            intent.setAction(Intent.ACTION_ANALOG_AUDIO_DOCK_PLUG);
        } else if (device == AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET) {
            connType = AudioRoutesInfo.MAIN_DOCK_SPEAKERS;
            intent.setAction(Intent.ACTION_DIGITAL_AUDIO_DOCK_PLUG);
        } else if (device == AudioSystem.DEVICE_OUT_AUX_DIGITAL) {
            connType = AudioRoutesInfo.MAIN_HDMI;
            intent.setAction(Intent.ACTION_HDMI_AUDIO_PLUG);
        }

        synchronized (mCurAudioRoutes) {
            if (connType != 0) {
                int newConn = mCurAudioRoutes.mMainType;
                if (state != 0) {
                    newConn |= connType;
                } else {
                    newConn &= ~connType;
                }
                if (newConn != mCurAudioRoutes.mMainType) {
                    mCurAudioRoutes.mMainType = newConn;
                    sendMsg(mAudioHandler, MSG_REPORT_NEW_ROUTES,
                            SENDMSG_NOOP, 0, 0, null, 0);
                }
            }
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.USER_ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void onSetWiredDeviceConnectionState(int device, int state, String name)
    {
        synchronized (mConnectedDevices) {
            if ((state == 0) && ((device == AudioSystem.DEVICE_OUT_WIRED_HEADSET) ||
                    (device == AudioSystem.DEVICE_OUT_WIRED_HEADPHONE))) {
                setBluetoothA2dpOnInt(true);
            }
            boolean isUsb = ((device & AudioSystem.DEVICE_OUT_ALL_USB) != 0);
            handleDeviceConnection((state == 1), device, (isUsb ? name : ""));
            if (state != 0) {
                if ((device == AudioSystem.DEVICE_OUT_WIRED_HEADSET) ||
                    (device == AudioSystem.DEVICE_OUT_WIRED_HEADPHONE)) {
                    setBluetoothA2dpOnInt(false);
                }
                if ((device & mSafeMediaVolumeDevices) != 0) {
                    sendMsg(mAudioHandler,
                            MSG_CHECK_MUSIC_ACTIVE,
                            SENDMSG_REPLACE,
                            0,
                            0,
                            null,
                            MUSIC_ACTIVE_POLL_PERIOD_MS);
                }
            }
            if (!isUsb) {
                sendDeviceConnectionIntent(device, state, name);
            }
        }
    }

    /* cache of the address of the last dock the device was connected to */
    private String mDockAddress;

    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class AudioServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int device;
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
                        synchronized (mSettingsLock) {
                            if (mDockAudioMediaEnabled) {
                                config = AudioSystem.FORCE_ANALOG_DOCK;
                            } else {
                                config = AudioSystem.FORCE_NONE;
                            }
                        }
                        break;
                    case Intent.EXTRA_DOCK_STATE_HE_DESK:
                        config = AudioSystem.FORCE_DIGITAL_DOCK;
                        break;
                    case Intent.EXTRA_DOCK_STATE_UNDOCKED:
                    default:
                        config = AudioSystem.FORCE_NONE;
                }

                AudioSystem.setForceUse(AudioSystem.FOR_DOCK, config);
            } else if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                               BluetoothProfile.STATE_DISCONNECTED);
                device = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO;
                String address = null;

                BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (btDevice == null) {
                    return;
                }

                address = btDevice.getAddress();
                BluetoothClass btClass = btDevice.getBluetoothClass();
                if (btClass != null) {
                    switch (btClass.getDeviceClass()) {
                    case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                    case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                        device = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET;
                        break;
                    case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                        device = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT;
                        break;
                    }
                }

                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    address = "";
                }

                boolean connected = (state == BluetoothProfile.STATE_CONNECTED);
                if (handleDeviceConnection(connected, device, address)) {
                    synchronized (mScoClients) {
                        if (connected) {
                            mBluetoothHeadsetDevice = btDevice;
                        } else {
                            mBluetoothHeadsetDevice = null;
                            resetBluetoothSco();
                        }
                    }
                }
            } else if (action.equals(Intent.ACTION_USB_AUDIO_ACCESSORY_PLUG) ||
                           action.equals(Intent.ACTION_USB_AUDIO_DEVICE_PLUG)) {
                state = intent.getIntExtra("state", 0);
                int alsaCard = intent.getIntExtra("card", -1);
                int alsaDevice = intent.getIntExtra("device", -1);
                String params = (alsaCard == -1 && alsaDevice == -1 ? ""
                                    : "card=" + alsaCard + ";device=" + alsaDevice);
                device = action.equals(Intent.ACTION_USB_AUDIO_ACCESSORY_PLUG) ?
                        AudioSystem.DEVICE_OUT_USB_ACCESSORY : AudioSystem.DEVICE_OUT_USB_DEVICE;
                Log.v(TAG, "Broadcast Receiver: Got "
                        + (action.equals(Intent.ACTION_USB_AUDIO_ACCESSORY_PLUG) ?
                              "ACTION_USB_AUDIO_ACCESSORY_PLUG" : "ACTION_USB_AUDIO_DEVICE_PLUG")
                        + ", state = " + state + ", card: " + alsaCard + ", device: " + alsaDevice);
                setWiredDeviceConnectionState(device, state, params);
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                boolean broadcast = false;
                int scoAudioState = AudioManager.SCO_AUDIO_STATE_ERROR;
                synchronized (mScoClients) {
                    int btState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    // broadcast intent if the connection was initated by AudioService
                    if (!mScoClients.isEmpty() &&
                            (mScoAudioState == SCO_STATE_ACTIVE_INTERNAL ||
                             mScoAudioState == SCO_STATE_ACTIVATE_REQ ||
                             mScoAudioState == SCO_STATE_DEACTIVATE_REQ)) {
                        broadcast = true;
                    }
                    switch (btState) {
                    case BluetoothHeadset.STATE_AUDIO_CONNECTED:
                        scoAudioState = AudioManager.SCO_AUDIO_STATE_CONNECTED;
                        if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL &&
                            mScoAudioState != SCO_STATE_DEACTIVATE_REQ &&
                            mScoAudioState != SCO_STATE_DEACTIVATE_EXT_REQ) {
                            mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                        }
                        break;
                    case BluetoothHeadset.STATE_AUDIO_DISCONNECTED:
                        scoAudioState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
                        mScoAudioState = SCO_STATE_INACTIVE;
                        clearAllScoClients(0, false);
                        break;
                    case BluetoothHeadset.STATE_AUDIO_CONNECTING:
                        if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL &&
                            mScoAudioState != SCO_STATE_DEACTIVATE_REQ &&
                            mScoAudioState != SCO_STATE_DEACTIVATE_EXT_REQ) {
                            mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                        }
                    default:
                        // do not broadcast CONNECTING or invalid state
                        broadcast = false;
                        break;
                    }
                }
                if (broadcast) {
                    broadcastScoConnectionState(scoAudioState);
                    //FIXME: this is to maintain compatibility with deprecated intent
                    // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
                    Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
                    newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, scoAudioState);
                    sendStickyBroadcastToAll(newIntent);
                }
            } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mBootCompleted = true;
                sendMsg(mAudioHandler, MSG_LOAD_SOUND_EFFECTS, SENDMSG_NOOP,
                        0, 0, null, 0);

                mKeyguardManager =
                        (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
                mScoConnectionState = AudioManager.SCO_AUDIO_STATE_ERROR;
                resetBluetoothSco();
                getBluetoothHeadset();
                //FIXME: this is to maintain compatibility with deprecated intent
                // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
                Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
                newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                sendStickyBroadcastToAll(newIntent);

                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    adapter.getProfileProxy(mContext, mBluetoothProfileServiceListener,
                                            BluetoothProfile.A2DP);
                }

                sendMsg(mAudioHandler,
                        MSG_CONFIGURE_SAFE_MEDIA_VOLUME_FORCED,
                        SENDMSG_REPLACE,
                        0,
                        0,
                        null,
                        SAFE_VOLUME_CONFIGURE_TIMEOUT_MS);
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // a package is being removed, not replaced
                    String packageName = intent.getData().getSchemeSpecificPart();
                    if (packageName != null) {
                        removeMediaButtonReceiverForPackage(packageName);
                    }
                }
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                AudioSystem.setParameters("screen_state=on");
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                AudioSystem.setParameters("screen_state=off");
            } else if (action.equalsIgnoreCase(Intent.ACTION_CONFIGURATION_CHANGED)) {
                handleConfigurationChanged(context);
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                // attempt to stop music playback for background user
                sendMsg(mAudioHandler,
                        MSG_BROADCAST_AUDIO_BECOMING_NOISY,
                        SENDMSG_REPLACE,
                        0,
                        0,
                        null,
                        0);
                // the current audio focus owner is no longer valid
                discardAudioFocusOwner();

                // load volume settings for new user
                readAudioSettings(true /*userSwitch*/);
                // preserve STREAM_MUSIC volume from one user to the next.
                sendMsg(mAudioHandler,
                        MSG_SET_ALL_VOLUMES,
                        SENDMSG_QUEUE,
                        0,
                        0,
                        mStreamStates[AudioSystem.STREAM_MUSIC], 0);
            }
        }
    }

    //==========================================================================================
    // AudioFocus
    //==========================================================================================

    /* constant to identify focus stack entry that is used to hold the focus while the phone
     * is ringing or during a call. Used by com.android.internal.telephony.CallManager when
     * entering and exiting calls.
     */
    public final static String IN_VOICE_COMM_FOCUS_ID = "AudioFocus_For_Phone_Ring_And_Calls";

    private final static Object mAudioFocusLock = new Object();

    private final static Object mRingingLock = new Object();

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                //Log.v(TAG, " CALL_STATE_RINGING");
                synchronized(mRingingLock) {
                    mIsRinging = true;
                }
            } else if ((state == TelephonyManager.CALL_STATE_OFFHOOK)
                    || (state == TelephonyManager.CALL_STATE_IDLE)) {
                synchronized(mRingingLock) {
                    mIsRinging = false;
                }
            }
        }
    };

    /**
     * Discard the current audio focus owner.
     * Notify top of audio focus stack that it lost focus (regardless of possibility to reassign
     * focus), remove it from the stack, and clear the remote control display.
     */
    private void discardAudioFocusOwner() {
        synchronized(mAudioFocusLock) {
            if (!mFocusStack.empty() && (mFocusStack.peek().mFocusDispatcher != null)) {
                // notify the current focus owner it lost focus after removing it from stack
                FocusStackEntry focusOwner = mFocusStack.pop();
                try {
                    focusOwner.mFocusDispatcher.dispatchAudioFocusChange(
                            AudioManager.AUDIOFOCUS_LOSS, focusOwner.mClientId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failure to signal loss of audio focus due to "+ e);
                    e.printStackTrace();
                }
                focusOwner.unlinkToDeath();
                // clear RCD
                synchronized(mRCStack) {
                    clearRemoteControlDisplay_syncAfRcs();
                }
            }
        }
    }

    private void notifyTopOfAudioFocusStack() {
        // notify the top of the stack it gained focus
        if (!mFocusStack.empty() && (mFocusStack.peek().mFocusDispatcher != null)) {
            if (canReassignAudioFocus()) {
                try {
                    mFocusStack.peek().mFocusDispatcher.dispatchAudioFocusChange(
                            AudioManager.AUDIOFOCUS_GAIN, mFocusStack.peek().mClientId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failure to signal gain of audio control focus due to "+ e);
                    e.printStackTrace();
                }
            }
        }
    }

    private static class FocusStackEntry {
        public int mStreamType = -1;// no stream type
        public IAudioFocusDispatcher mFocusDispatcher = null;
        public IBinder mSourceRef = null;
        public String mClientId;
        public int mFocusChangeType;
        public AudioFocusDeathHandler mHandler;
        public String mPackageName;
        public int mCallingUid;

        public FocusStackEntry() {
        }

        public FocusStackEntry(int streamType, int duration,
                IAudioFocusDispatcher afl, IBinder source, String id, AudioFocusDeathHandler hdlr,
                String pn, int uid) {
            mStreamType = streamType;
            mFocusDispatcher = afl;
            mSourceRef = source;
            mClientId = id;
            mFocusChangeType = duration;
            mHandler = hdlr;
            mPackageName = pn;
            mCallingUid = uid;
        }

        public void unlinkToDeath() {
            try {
                if (mSourceRef != null && mHandler != null) {
                    mSourceRef.unlinkToDeath(mHandler, 0);
                    mHandler = null;
                }
            } catch (java.util.NoSuchElementException e) {
                Log.e(TAG, "Encountered " + e + " in FocusStackEntry.unlinkToDeath()");
            }
        }

        @Override
        protected void finalize() throws Throwable {
            unlinkToDeath(); // unlink exception handled inside method
            super.finalize();
        }
    }

    private final Stack<FocusStackEntry> mFocusStack = new Stack<FocusStackEntry>();

    /**
     * Helper function:
     * Display in the log the current entries in the audio focus stack
     */
    private void dumpFocusStack(PrintWriter pw) {
        pw.println("\nAudio Focus stack entries:");
        synchronized(mAudioFocusLock) {
            Iterator<FocusStackEntry> stackIterator = mFocusStack.iterator();
            while(stackIterator.hasNext()) {
                FocusStackEntry fse = stackIterator.next();
                pw.println("  source:" + fse.mSourceRef
                        + " -- pack: " + fse.mPackageName
                        + " -- client: " + fse.mClientId
                        + " -- duration: " + fse.mFocusChangeType
                        + " -- uid: " + fse.mCallingUid
                        + " -- stream: " + fse.mStreamType);
            }
        }
    }

    /**
     * Helper function:
     * Called synchronized on mAudioFocusLock
     * Remove a focus listener from the focus stack.
     * @param focusListenerToRemove the focus listener
     * @param signal if true and the listener was at the top of the focus stack, i.e. it was holding
     *   focus, notify the next item in the stack it gained focus.
     */
    private void removeFocusStackEntry(String clientToRemove, boolean signal) {
        // is the current top of the focus stack abandoning focus? (because of request, not death)
        if (!mFocusStack.empty() && mFocusStack.peek().mClientId.equals(clientToRemove))
        {
            //Log.i(TAG, "   removeFocusStackEntry() removing top of stack");
            FocusStackEntry fse = mFocusStack.pop();
            fse.unlinkToDeath();
            if (signal) {
                // notify the new top of the stack it gained focus
                notifyTopOfAudioFocusStack();
                // there's a new top of the stack, let the remote control know
                synchronized(mRCStack) {
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }
        } else {
            // focus is abandoned by a client that's not at the top of the stack,
            // no need to update focus.
            Iterator<FocusStackEntry> stackIterator = mFocusStack.iterator();
            while(stackIterator.hasNext()) {
                FocusStackEntry fse = (FocusStackEntry)stackIterator.next();
                if(fse.mClientId.equals(clientToRemove)) {
                    Log.i(TAG, " AudioFocus  abandonAudioFocus(): removing entry for "
                            + fse.mClientId);
                    stackIterator.remove();
                    fse.unlinkToDeath();
                }
            }
        }
    }

    /**
     * Helper function:
     * Called synchronized on mAudioFocusLock
     * Remove focus listeners from the focus stack for a particular client when it has died.
     */
    private void removeFocusStackEntryForClient(IBinder cb) {
        // is the owner of the audio focus part of the client to remove?
        boolean isTopOfStackForClientToRemove = !mFocusStack.isEmpty() &&
                mFocusStack.peek().mSourceRef.equals(cb);
        Iterator<FocusStackEntry> stackIterator = mFocusStack.iterator();
        while(stackIterator.hasNext()) {
            FocusStackEntry fse = (FocusStackEntry)stackIterator.next();
            if(fse.mSourceRef.equals(cb)) {
                Log.i(TAG, " AudioFocus  abandonAudioFocus(): removing entry for "
                        + fse.mClientId);
                stackIterator.remove();
                // the client just died, no need to unlink to its death
            }
        }
        if (isTopOfStackForClientToRemove) {
            // we removed an entry at the top of the stack:
            //  notify the new top of the stack it gained focus.
            notifyTopOfAudioFocusStack();
            // there's a new top of the stack, let the remote control know
            synchronized(mRCStack) {
                checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
            }
        }
    }

    /**
     * Helper function:
     * Returns true if the system is in a state where the focus can be reevaluated, false otherwise.
     */
    private boolean canReassignAudioFocus() {
        // focus requests are rejected during a phone call or when the phone is ringing
        // this is equivalent to IN_VOICE_COMM_FOCUS_ID having the focus
        if (!mFocusStack.isEmpty() && IN_VOICE_COMM_FOCUS_ID.equals(mFocusStack.peek().mClientId)) {
            return false;
        }
        return true;
    }

    /**
     * Inner class to monitor audio focus client deaths, and remove them from the audio focus
     * stack if necessary.
     */
    private class AudioFocusDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death

        AudioFocusDeathHandler(IBinder cb) {
            mCb = cb;
        }

        public void binderDied() {
            synchronized(mAudioFocusLock) {
                Log.w(TAG, "  AudioFocus   audio focus client died");
                removeFocusStackEntryForClient(mCb);
            }
        }

        public IBinder getBinder() {
            return mCb;
        }
    }


    /** @see AudioManager#requestAudioFocus(IAudioFocusDispatcher, int, int) */
    public int requestAudioFocus(int mainStreamType, int focusChangeHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName) {
        Log.i(TAG, " AudioFocus  requestAudioFocus() from " + clientId);
        // the main stream type for the audio focus request is currently not used. It may
        // potentially be used to handle multiple stream type-dependent audio focuses.

        // we need a valid binder callback for clients
        if (!cb.pingBinder()) {
            Log.e(TAG, " AudioFocus DOA client for requestAudioFocus(), aborting.");
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }

        synchronized(mAudioFocusLock) {
            if (!canReassignAudioFocus()) {
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }

            // handle the potential premature death of the new holder of the focus
            // (premature death == death before abandoning focus)
            // Register for client death notification
            AudioFocusDeathHandler afdh = new AudioFocusDeathHandler(cb);
            try {
                cb.linkToDeath(afdh, 0);
            } catch (RemoteException e) {
                // client has already died!
                Log.w(TAG, "AudioFocus  requestAudioFocus() could not link to "+cb+" binder death");
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }

            if (!mFocusStack.empty() && mFocusStack.peek().mClientId.equals(clientId)) {
                // if focus is already owned by this client and the reason for acquiring the focus
                // hasn't changed, don't do anything
                if (mFocusStack.peek().mFocusChangeType == focusChangeHint) {
                    // unlink death handler so it can be gc'ed.
                    // linkToDeath() creates a JNI global reference preventing collection.
                    cb.unlinkToDeath(afdh, 0);
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
                // the reason for the audio focus request has changed: remove the current top of
                // stack and respond as if we had a new focus owner
                FocusStackEntry fse = mFocusStack.pop();
                fse.unlinkToDeath();
            }

            // notify current top of stack it is losing focus
            if (!mFocusStack.empty() && (mFocusStack.peek().mFocusDispatcher != null)) {
                try {
                    mFocusStack.peek().mFocusDispatcher.dispatchAudioFocusChange(
                            -1 * focusChangeHint, // loss and gain codes are inverse of each other
                            mFocusStack.peek().mClientId);
                } catch (RemoteException e) {
                    Log.e(TAG, " Failure to signal loss of focus due to "+ e);
                    e.printStackTrace();
                }
            }

            // focus requester might already be somewhere below in the stack, remove it
            removeFocusStackEntry(clientId, false /* signal */);

            // push focus requester at the top of the audio focus stack
            mFocusStack.push(new FocusStackEntry(mainStreamType, focusChangeHint, fd, cb,
                    clientId, afdh, callingPackageName, Binder.getCallingUid()));

            // there's a new top of the stack, let the remote control know
            synchronized(mRCStack) {
                checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
            }
        }//synchronized(mAudioFocusLock)

        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** @see AudioManager#abandonAudioFocus(IAudioFocusDispatcher) */
    public int abandonAudioFocus(IAudioFocusDispatcher fl, String clientId) {
        Log.i(TAG, " AudioFocus  abandonAudioFocus() from " + clientId);
        try {
            // this will take care of notifying the new focus owner if needed
            synchronized(mAudioFocusLock) {
                removeFocusStackEntry(clientId, true);
            }
        } catch (java.util.ConcurrentModificationException cme) {
            // Catching this exception here is temporary. It is here just to prevent
            // a crash seen when the "Silent" notification is played. This is believed to be fixed
            // but this try catch block is left just to be safe.
            Log.e(TAG, "FATAL EXCEPTION AudioFocus  abandonAudioFocus() caused " + cme);
            cme.printStackTrace();
        }

        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }


    public void unregisterAudioFocusClient(String clientId) {
        synchronized(mAudioFocusLock) {
            removeFocusStackEntry(clientId, false);
        }
    }


    //==========================================================================================
    // RemoteControl
    //==========================================================================================
    public void dispatchMediaKeyEvent(KeyEvent keyEvent) {
        filterMediaKeyEvent(keyEvent, false /*needWakeLock*/);
    }

    public void dispatchMediaKeyEventUnderWakelock(KeyEvent keyEvent) {
        filterMediaKeyEvent(keyEvent, true /*needWakeLock*/);
    }

    private void filterMediaKeyEvent(KeyEvent keyEvent, boolean needWakeLock) {
        // sanity check on the incoming key event
        if (!isValidMediaKeyEvent(keyEvent)) {
            Log.e(TAG, "not dispatching invalid media key event " + keyEvent);
            return;
        }
        // event filtering for telephony
        synchronized(mRingingLock) {
            synchronized(mRCStack) {
                if ((mMediaReceiverForCalls != null) &&
                        (mIsRinging || (getMode() == AudioSystem.MODE_IN_CALL))) {
                    dispatchMediaKeyEventForCalls(keyEvent, needWakeLock);
                    return;
                }
            }
        }
        // event filtering based on voice-based interactions
        if (isValidVoiceInputKeyCode(keyEvent.getKeyCode())) {
            filterVoiceInputKeyEvent(keyEvent, needWakeLock);
        } else {
            dispatchMediaKeyEvent(keyEvent, needWakeLock);
        }
    }

    /**
     * Handles the dispatching of the media button events to the telephony package.
     * Precondition: mMediaReceiverForCalls != null
     * @param keyEvent a non-null KeyEvent whose key code is one of the supported media buttons
     * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held while this key event
     *     is dispatched.
     */
    private void dispatchMediaKeyEventForCalls(KeyEvent keyEvent, boolean needWakeLock) {
        Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        keyIntent.setPackage(mMediaReceiverForCalls.getPackageName());
        if (needWakeLock) {
            mMediaEventWakeLock.acquire();
            keyIntent.putExtra(EXTRA_WAKELOCK_ACQUIRED, WAKELOCK_RELEASE_ON_FINISHED);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendOrderedBroadcastAsUser(keyIntent, UserHandle.ALL,
                    null, mKeyEventDone, mAudioHandler, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Handles the dispatching of the media button events to one of the registered listeners,
     * or if there was none, broadcast an ACTION_MEDIA_BUTTON intent to the rest of the system.
     * @param keyEvent a non-null KeyEvent whose key code is one of the supported media buttons
     * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held while this key event
     *     is dispatched.
     */
    private void dispatchMediaKeyEvent(KeyEvent keyEvent, boolean needWakeLock) {
        if (needWakeLock) {
            mMediaEventWakeLock.acquire();
        }
        Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        synchronized(mRCStack) {
            if (!mRCStack.empty()) {
                // send the intent that was registered by the client
                try {
                    mRCStack.peek().mMediaIntent.send(mContext,
                            needWakeLock ? WAKELOCK_RELEASE_ON_FINISHED : 0 /*code*/,
                            keyIntent, AudioService.this, mAudioHandler);
                } catch (CanceledException e) {
                    Log.e(TAG, "Error sending pending intent " + mRCStack.peek());
                    e.printStackTrace();
                }
            } else {
                // legacy behavior when nobody registered their media button event receiver
                //    through AudioManager
                if (needWakeLock) {
                    keyIntent.putExtra(EXTRA_WAKELOCK_ACQUIRED, WAKELOCK_RELEASE_ON_FINISHED);
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mContext.sendOrderedBroadcastAsUser(keyIntent, UserHandle.ALL,
                            null, mKeyEventDone,
                            mAudioHandler, Activity.RESULT_OK, null, null);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    /**
     * The different actions performed in response to a voice button key event.
     */
    private final static int VOICEBUTTON_ACTION_DISCARD_CURRENT_KEY_PRESS = 1;
    private final static int VOICEBUTTON_ACTION_START_VOICE_INPUT = 2;
    private final static int VOICEBUTTON_ACTION_SIMULATE_KEY_PRESS = 3;

    private final Object mVoiceEventLock = new Object();
    private boolean mVoiceButtonDown;
    private boolean mVoiceButtonHandled;

    /**
     * Filter key events that may be used for voice-based interactions
     * @param keyEvent a non-null KeyEvent whose key code is that of one of the supported
     *    media buttons that can be used to trigger voice-based interactions.
     * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held while this key event
     *     is dispatched.
     */
    private void filterVoiceInputKeyEvent(KeyEvent keyEvent, boolean needWakeLock) {
        if (DEBUG_RC) {
            Log.v(TAG, "voice input key event: " + keyEvent + ", needWakeLock=" + needWakeLock);
        }

        int voiceButtonAction = VOICEBUTTON_ACTION_DISCARD_CURRENT_KEY_PRESS;
        int keyAction = keyEvent.getAction();
        synchronized (mVoiceEventLock) {
            if (keyAction == KeyEvent.ACTION_DOWN) {
                if (keyEvent.getRepeatCount() == 0) {
                    // initial down
                    mVoiceButtonDown = true;
                    mVoiceButtonHandled = false;
                } else if (mVoiceButtonDown && !mVoiceButtonHandled
                        && (keyEvent.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    // long-press, start voice-based interactions
                    mVoiceButtonHandled = true;
                    voiceButtonAction = VOICEBUTTON_ACTION_START_VOICE_INPUT;
                }
            } else if (keyAction == KeyEvent.ACTION_UP) {
                if (mVoiceButtonDown) {
                    // voice button up
                    mVoiceButtonDown = false;
                    if (!mVoiceButtonHandled && !keyEvent.isCanceled()) {
                        voiceButtonAction = VOICEBUTTON_ACTION_SIMULATE_KEY_PRESS;
                    }
                }
            }
        }//synchronized (mVoiceEventLock)

        // take action after media button event filtering for voice-based interactions
        switch (voiceButtonAction) {
            case VOICEBUTTON_ACTION_DISCARD_CURRENT_KEY_PRESS:
                if (DEBUG_RC) Log.v(TAG, "   ignore key event");
                break;
            case VOICEBUTTON_ACTION_START_VOICE_INPUT:
                if (DEBUG_RC) Log.v(TAG, "   start voice-based interactions");
                // then start the voice-based interactions
                startVoiceBasedInteractions(needWakeLock);
                break;
            case VOICEBUTTON_ACTION_SIMULATE_KEY_PRESS:
                if (DEBUG_RC) Log.v(TAG, "   send simulated key event, wakelock=" + needWakeLock);
                sendSimulatedMediaButtonEvent(keyEvent, needWakeLock);
                break;
        }
    }

    private void sendSimulatedMediaButtonEvent(KeyEvent originalKeyEvent, boolean needWakeLock) {
        // send DOWN event
        KeyEvent keyEvent = KeyEvent.changeAction(originalKeyEvent, KeyEvent.ACTION_DOWN);
        dispatchMediaKeyEvent(keyEvent, needWakeLock);
        // send UP event
        keyEvent = KeyEvent.changeAction(originalKeyEvent, KeyEvent.ACTION_UP);
        dispatchMediaKeyEvent(keyEvent, needWakeLock);

    }


    private static boolean isValidMediaKeyEvent(KeyEvent keyEvent) {
        if (keyEvent == null) {
            return false;
        }
        final int keyCode = keyEvent.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_CLOSE:
            case KeyEvent.KEYCODE_MEDIA_EJECT:
                break;
            default:
                return false;
        }
        return true;
    }

    /**
     * Checks whether the given key code is one that can trigger the launch of voice-based
     *   interactions.
     * @param keyCode the key code associated with the key event
     * @return true if the key is one of the supported voice-based interaction triggers
     */
    private static boolean isValidVoiceInputKeyCode(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Tell the system to start voice-based interactions / voice commands
     */
    private void startVoiceBasedInteractions(boolean needWakeLock) {
        Intent voiceIntent = null;
        // select which type of search to launch:
        // - screen on and device unlocked: action is ACTION_WEB_SEARCH
        // - device locked or screen off: action is ACTION_VOICE_SEARCH_HANDS_FREE
        //    with EXTRA_SECURE set to true if the device is securely locked
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        boolean isLocked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        if (!isLocked && pm.isScreenOn()) {
            voiceIntent = new Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH);
        } else {
            voiceIntent = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
            voiceIntent.putExtra(RecognizerIntent.EXTRA_SECURE,
                    isLocked && mKeyguardManager.isKeyguardSecure());
        }
        // start the search activity
        if (needWakeLock) {
            mMediaEventWakeLock.acquire();
        }
        try {
            if (voiceIntent != null) {
                voiceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                mContext.startActivity(voiceIntent);
            }
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No activity for search: " + e);
        } finally {
            if (needWakeLock) {
                mMediaEventWakeLock.release();
            }
        }
    }

    private PowerManager.WakeLock mMediaEventWakeLock;

    private static final int WAKELOCK_RELEASE_ON_FINISHED = 1980; //magic number

    // only set when wakelock was acquired, no need to check value when received
    private static final String EXTRA_WAKELOCK_ACQUIRED =
            "android.media.AudioService.WAKELOCK_ACQUIRED";

    public void onSendFinished(PendingIntent pendingIntent, Intent intent,
            int resultCode, String resultData, Bundle resultExtras) {
        if (resultCode == WAKELOCK_RELEASE_ON_FINISHED) {
            mMediaEventWakeLock.release();
        }
    }

    BroadcastReceiver mKeyEventDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            Bundle extras = intent.getExtras();
            if (extras == null) {
                return;
            }
            if (extras.containsKey(EXTRA_WAKELOCK_ACQUIRED)) {
                mMediaEventWakeLock.release();
            }
        }
    };

    private final Object mCurrentRcLock = new Object();
    /**
     * The one remote control client which will receive a request for display information.
     * This object may be null.
     * Access protected by mCurrentRcLock.
     */
    private IRemoteControlClient mCurrentRcClient = null;

    private final static int RC_INFO_NONE = 0;
    private final static int RC_INFO_ALL =
        RemoteControlClient.FLAG_INFORMATION_REQUEST_ALBUM_ART |
        RemoteControlClient.FLAG_INFORMATION_REQUEST_KEY_MEDIA |
        RemoteControlClient.FLAG_INFORMATION_REQUEST_METADATA |
        RemoteControlClient.FLAG_INFORMATION_REQUEST_PLAYSTATE;

    /**
     * A monotonically increasing generation counter for mCurrentRcClient.
     * Only accessed with a lock on mCurrentRcLock.
     * No value wrap-around issues as we only act on equal values.
     */
    private int mCurrentRcClientGen = 0;

    /**
     * Inner class to monitor remote control client deaths, and remove the client for the
     * remote control stack if necessary.
     */
    private class RcClientDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death
        private PendingIntent mMediaIntent;

        RcClientDeathHandler(IBinder cb, PendingIntent pi) {
            mCb = cb;
            mMediaIntent = pi;
        }

        public void binderDied() {
            Log.w(TAG, "  RemoteControlClient died");
            // remote control client died, make sure the displays don't use it anymore
            //  by setting its remote control client to null
            registerRemoteControlClient(mMediaIntent, null/*rcClient*/, null/*ignored*/);
            // the dead client was maybe handling remote playback, reevaluate
            postReevaluateRemote();
        }

        public IBinder getBinder() {
            return mCb;
        }
    }

    /**
     * A global counter for RemoteControlClient identifiers
     */
    private static int sLastRccId = 0;

    private class RemotePlaybackState {
        int mRccId;
        int mVolume;
        int mVolumeMax;
        int mVolumeHandling;

        private RemotePlaybackState(int id, int vol, int volMax) {
            mRccId = id;
            mVolume = vol;
            mVolumeMax = volMax;
            mVolumeHandling = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME_HANDLING;
        }
    }

    /**
     * Internal cache for the playback information of the RemoteControlClient whose volume gets to
     * be controlled by the volume keys ("main"), so we don't have to iterate over the RC stack
     * every time we need this info.
     */
    private RemotePlaybackState mMainRemote;
    /**
     * Indicates whether the "main" RemoteControlClient is considered active.
     * Use synchronized on mMainRemote.
     */
    private boolean mMainRemoteIsActive;
    /**
     * Indicates whether there is remote playback going on. True even if there is no "active"
     * remote playback (mMainRemoteIsActive is false), but a RemoteControlClient has declared it
     * handles remote playback.
     * Use synchronized on mMainRemote.
     */
    private boolean mHasRemotePlayback;

    private static class RemoteControlStackEntry {
        public int mRccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        /**
         * The target for the ACTION_MEDIA_BUTTON events.
         * Always non null.
         */
        public PendingIntent mMediaIntent;
        /**
         * The registered media button event receiver.
         * Always non null.
         */
        public ComponentName mReceiverComponent;
        public String mCallingPackageName;
        public int mCallingUid;
        /**
         * Provides access to the information to display on the remote control.
         * May be null (when a media button event receiver is registered,
         *     but no remote control client has been registered) */
        public IRemoteControlClient mRcClient;
        public RcClientDeathHandler mRcClientDeathHandler;
        /**
         * Information only used for non-local playback
         */
        public int mPlaybackType;
        public int mPlaybackVolume;
        public int mPlaybackVolumeMax;
        public int mPlaybackVolumeHandling;
        public int mPlaybackStream;
        public int mPlaybackState;
        public IRemoteVolumeObserver mRemoteVolumeObs;

        public void resetPlaybackInfo() {
            mPlaybackType = RemoteControlClient.PLAYBACK_TYPE_LOCAL;
            mPlaybackVolume = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME;
            mPlaybackVolumeMax = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME;
            mPlaybackVolumeHandling = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME_HANDLING;
            mPlaybackStream = AudioManager.STREAM_MUSIC;
            mPlaybackState = RemoteControlClient.PLAYSTATE_STOPPED;
            mRemoteVolumeObs = null;
        }

        /** precondition: mediaIntent != null, eventReceiver != null */
        public RemoteControlStackEntry(PendingIntent mediaIntent, ComponentName eventReceiver) {
            mMediaIntent = mediaIntent;
            mReceiverComponent = eventReceiver;
            mCallingUid = -1;
            mRcClient = null;
            mRccId = ++sLastRccId;

            resetPlaybackInfo();
        }

        public void unlinkToRcClientDeath() {
            if ((mRcClientDeathHandler != null) && (mRcClientDeathHandler.mCb != null)) {
                try {
                    mRcClientDeathHandler.mCb.unlinkToDeath(mRcClientDeathHandler, 0);
                    mRcClientDeathHandler = null;
                } catch (java.util.NoSuchElementException e) {
                    // not much we can do here
                    Log.e(TAG, "Encountered " + e + " in unlinkToRcClientDeath()");
                    e.printStackTrace();
                }
            }
        }

        @Override
        protected void finalize() throws Throwable {
            unlinkToRcClientDeath();// unlink exception handled inside method
            super.finalize();
        }
    }

    /**
     *  The stack of remote control event receivers.
     *  Code sections and methods that modify the remote control event receiver stack are
     *  synchronized on mRCStack, but also BEFORE on mFocusLock as any change in either
     *  stack, audio focus or RC, can lead to a change in the remote control display
     */
    private final Stack<RemoteControlStackEntry> mRCStack = new Stack<RemoteControlStackEntry>();

    /**
     * The component the telephony package can register so telephony calls have priority to
     * handle media button events
     */
    private ComponentName mMediaReceiverForCalls = null;

    /**
     * Helper function:
     * Display in the log the current entries in the remote control focus stack
     */
    private void dumpRCStack(PrintWriter pw) {
        pw.println("\nRemote Control stack entries:");
        synchronized(mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                pw.println("  pi: " + rcse.mMediaIntent +
                        " -- pack: " + rcse.mCallingPackageName +
                        "  -- ercvr: " + rcse.mReceiverComponent +
                        "  -- client: " + rcse.mRcClient +
                        "  -- uid: " + rcse.mCallingUid +
                        "  -- type: " + rcse.mPlaybackType +
                        "  state: " + rcse.mPlaybackState);
            }
        }
    }

    /**
     * Helper function:
     * Display in the log the current entries in the remote control stack, focusing
     * on RemoteControlClient data
     */
    private void dumpRCCStack(PrintWriter pw) {
        pw.println("\nRemote Control Client stack entries:");
        synchronized(mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                pw.println("  uid: " + rcse.mCallingUid +
                        "  -- id: " + rcse.mRccId +
                        "  -- type: " + rcse.mPlaybackType +
                        "  -- state: " + rcse.mPlaybackState +
                        "  -- vol handling: " + rcse.mPlaybackVolumeHandling +
                        "  -- vol: " + rcse.mPlaybackVolume +
                        "  -- volMax: " + rcse.mPlaybackVolumeMax +
                        "  -- volObs: " + rcse.mRemoteVolumeObs);

            }
        }
        synchronized (mMainRemote) {
            pw.println("\nRemote Volume State:");
            pw.println("  has remote: " + mHasRemotePlayback);
            pw.println("  is remote active: " + mMainRemoteIsActive);
            pw.println("  rccId: " + mMainRemote.mRccId);
            pw.println("  volume handling: "
                    + ((mMainRemote.mVolumeHandling == RemoteControlClient.PLAYBACK_VOLUME_FIXED) ?
                            "PLAYBACK_VOLUME_FIXED(0)" : "PLAYBACK_VOLUME_VARIABLE(1)"));
            pw.println("  volume: " + mMainRemote.mVolume);
            pw.println("  volume steps: " + mMainRemote.mVolumeMax);
        }
    }

    /**
     * Helper function:
     * Remove any entry in the remote control stack that has the same package name as packageName
     * Pre-condition: packageName != null
     */
    private void removeMediaButtonReceiverForPackage(String packageName) {
        synchronized(mRCStack) {
            if (mRCStack.empty()) {
                return;
            } else {
                RemoteControlStackEntry oldTop = mRCStack.peek();
                Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                // iterate over the stack entries
                while(stackIterator.hasNext()) {
                    RemoteControlStackEntry rcse = (RemoteControlStackEntry)stackIterator.next();
                    if (packageName.equalsIgnoreCase(rcse.mReceiverComponent.getPackageName())) {
                        // a stack entry is from the package being removed, remove it from the stack
                        stackIterator.remove();
                        rcse.unlinkToRcClientDeath();
                    }
                }
                if (mRCStack.empty()) {
                    // no saved media button receiver
                    mAudioHandler.sendMessage(
                            mAudioHandler.obtainMessage(MSG_PERSIST_MEDIABUTTONRECEIVER, 0, 0,
                                    null));
                } else if (oldTop != mRCStack.peek()) {
                    // the top of the stack has changed, save it in the system settings
                    // by posting a message to persist it
                    mAudioHandler.sendMessage(
                            mAudioHandler.obtainMessage(MSG_PERSIST_MEDIABUTTONRECEIVER, 0, 0,
                                    mRCStack.peek().mReceiverComponent));
                }
            }
        }
    }

    /**
     * Helper function:
     * Restore remote control receiver from the system settings.
     */
    private void restoreMediaButtonReceiver() {
        String receiverName = Settings.System.getStringForUser(mContentResolver,
                Settings.System.MEDIA_BUTTON_RECEIVER, UserHandle.USER_CURRENT);
        if ((null != receiverName) && !receiverName.isEmpty()) {
            ComponentName eventReceiver = ComponentName.unflattenFromString(receiverName);
            // construct a PendingIntent targeted to the restored component name
            // for the media button and register it
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            //     the associated intent will be handled by the component being registered
            mediaButtonIntent.setComponent(eventReceiver);
            PendingIntent pi = PendingIntent.getBroadcast(mContext,
                    0/*requestCode, ignored*/, mediaButtonIntent, 0/*flags*/);
            registerMediaButtonIntent(pi, eventReceiver);
        }
    }

    /**
     * Helper function:
     * Set the new remote control receiver at the top of the RC focus stack.
     * precondition: mediaIntent != null, target != null
     */
    private void pushMediaButtonReceiver(PendingIntent mediaIntent, ComponentName target) {
        // already at top of stack?
        if (!mRCStack.empty() && mRCStack.peek().mMediaIntent.equals(mediaIntent)) {
            return;
        }
        Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
        RemoteControlStackEntry rcse = null;
        boolean wasInsideStack = false;
        while(stackIterator.hasNext()) {
            rcse = (RemoteControlStackEntry)stackIterator.next();
            if(rcse.mMediaIntent.equals(mediaIntent)) {
                wasInsideStack = true;
                stackIterator.remove();
                break;
            }
        }
        if (!wasInsideStack) {
            rcse = new RemoteControlStackEntry(mediaIntent, target);
        }
        mRCStack.push(rcse);

        // post message to persist the default media button receiver
        mAudioHandler.sendMessage( mAudioHandler.obtainMessage(
                MSG_PERSIST_MEDIABUTTONRECEIVER, 0, 0, target/*obj*/) );
    }

    /**
     * Helper function:
     * Remove the remote control receiver from the RC focus stack.
     * precondition: pi != null
     */
    private void removeMediaButtonReceiver(PendingIntent pi) {
        Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
        while(stackIterator.hasNext()) {
            RemoteControlStackEntry rcse = (RemoteControlStackEntry)stackIterator.next();
            if(rcse.mMediaIntent.equals(pi)) {
                stackIterator.remove();
                rcse.unlinkToRcClientDeath();
                break;
            }
        }
    }

    /**
     * Helper function:
     * Called synchronized on mRCStack
     */
    private boolean isCurrentRcController(PendingIntent pi) {
        if (!mRCStack.empty() && mRCStack.peek().mMediaIntent.equals(pi)) {
            return true;
        }
        return false;
    }

    //==========================================================================================
    // Remote control display / client
    //==========================================================================================
    /**
     * Update the remote control displays with the new "focused" client generation
     */
    private void setNewRcClientOnDisplays_syncRcsCurrc(int newClientGeneration,
            PendingIntent newMediaIntent, boolean clearing) {
        // NOTE: Only one IRemoteControlDisplay supported in this implementation
        if (mRcDisplay != null) {
            try {
                mRcDisplay.setCurrentClientId(
                        newClientGeneration, newMediaIntent, clearing);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead display in setNewRcClientOnDisplays_syncRcsCurrc() "+e);
                // if we had a display before, stop monitoring its death
                rcDisplay_stopDeathMonitor_syncRcStack();
                mRcDisplay = null;
            }
        }
    }

    /**
     * Update the remote control clients with the new "focused" client generation
     */
    private void setNewRcClientGenerationOnClients_syncRcsCurrc(int newClientGeneration) {
        Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
        while(stackIterator.hasNext()) {
            RemoteControlStackEntry se = stackIterator.next();
            if ((se != null) && (se.mRcClient != null)) {
                try {
                    se.mRcClient.setCurrentClientGenerationId(newClientGeneration);
                } catch (RemoteException e) {
                    Log.w(TAG, "Dead client in setNewRcClientGenerationOnClients_syncRcsCurrc()"+e);
                    stackIterator.remove();
                    se.unlinkToRcClientDeath();
                }
            }
        }
    }

    /**
     * Update the displays and clients with the new "focused" client generation and name
     * @param newClientGeneration the new generation value matching a client update
     * @param newClientEventReceiver the media button event receiver associated with the client.
     *    May be null, which implies there is no registered media button event receiver.
     * @param clearing true if the new client generation value maps to a remote control update
     *    where the display should be cleared.
     */
    private void setNewRcClient_syncRcsCurrc(int newClientGeneration,
            PendingIntent newMediaIntent, boolean clearing) {
        // send the new valid client generation ID to all displays
        setNewRcClientOnDisplays_syncRcsCurrc(newClientGeneration, newMediaIntent, clearing);
        // send the new valid client generation ID to all clients
        setNewRcClientGenerationOnClients_syncRcsCurrc(newClientGeneration);
    }

    /**
     * Called when processing MSG_RCDISPLAY_CLEAR event
     */
    private void onRcDisplayClear() {
        if (DEBUG_RC) Log.i(TAG, "Clear remote control display");

        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                mCurrentRcClientGen++;
                // synchronously update the displays and clients with the new client generation
                setNewRcClient_syncRcsCurrc(mCurrentRcClientGen,
                        null /*newMediaIntent*/, true /*clearing*/);
            }
        }
    }

    /**
     * Called when processing MSG_RCDISPLAY_UPDATE event
     */
    private void onRcDisplayUpdate(RemoteControlStackEntry rcse, int flags /* USED ?*/) {
        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                if ((mCurrentRcClient != null) && (mCurrentRcClient.equals(rcse.mRcClient))) {
                    if (DEBUG_RC) Log.i(TAG, "Display/update remote control ");

                    mCurrentRcClientGen++;
                    // synchronously update the displays and clients with
                    //      the new client generation
                    setNewRcClient_syncRcsCurrc(mCurrentRcClientGen,
                            rcse.mMediaIntent /*newMediaIntent*/,
                            false /*clearing*/);

                    // tell the current client that it needs to send info
                    try {
                        mCurrentRcClient.onInformationRequested(mCurrentRcClientGen,
                                flags, mArtworkExpectedWidth, mArtworkExpectedHeight);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Current valid remote client is dead: "+e);
                        mCurrentRcClient = null;
                    }
                } else {
                    // the remote control display owner has changed between the
                    // the message to update the display was sent, and the time it
                    // gets to be processed (now)
                }
            }
        }
    }


    /**
     * Helper function:
     * Called synchronized on mRCStack
     */
    private void clearRemoteControlDisplay_syncAfRcs() {
        synchronized(mCurrentRcLock) {
            mCurrentRcClient = null;
        }
        // will cause onRcDisplayClear() to be called in AudioService's handler thread
        mAudioHandler.sendMessage( mAudioHandler.obtainMessage(MSG_RCDISPLAY_CLEAR) );
    }

    /**
     * Helper function for code readability: only to be called from
     *    checkUpdateRemoteControlDisplay_syncAfRcs() which checks the preconditions for
     *    this method.
     * Preconditions:
     *    - called synchronized mAudioFocusLock then on mRCStack
     *    - mRCStack.isEmpty() is false
     */
    private void updateRemoteControlDisplay_syncAfRcs(int infoChangedFlags) {
        RemoteControlStackEntry rcse = mRCStack.peek();
        int infoFlagsAboutToBeUsed = infoChangedFlags;
        // this is where we enforce opt-in for information display on the remote controls
        //   with the new AudioManager.registerRemoteControlClient() API
        if (rcse.mRcClient == null) {
            //Log.w(TAG, "Can't update remote control display with null remote control client");
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }
        synchronized(mCurrentRcLock) {
            if (!rcse.mRcClient.equals(mCurrentRcClient)) {
                // new RC client, assume every type of information shall be queried
                infoFlagsAboutToBeUsed = RC_INFO_ALL;
            }
            mCurrentRcClient = rcse.mRcClient;
        }
        // will cause onRcDisplayUpdate() to be called in AudioService's handler thread
        mAudioHandler.sendMessage( mAudioHandler.obtainMessage(MSG_RCDISPLAY_UPDATE,
                infoFlagsAboutToBeUsed /* arg1 */, 0, rcse /* obj, != null */) );
    }

    /**
     * Helper function:
     * Called synchronized on mAudioFocusLock, then mRCStack
     * Check whether the remote control display should be updated, triggers the update if required
     * @param infoChangedFlags the flags corresponding to the remote control client information
     *     that has changed, if applicable (checking for the update conditions might trigger a
     *     clear, rather than an update event).
     */
    private void checkUpdateRemoteControlDisplay_syncAfRcs(int infoChangedFlags) {
        // determine whether the remote control display should be refreshed
        // if either stack is empty, there is a mismatch, so clear the RC display
        if (mRCStack.isEmpty() || mFocusStack.isEmpty()) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }

        // determine which entry in the AudioFocus stack to consider, and compare against the
        // top of the stack for the media button event receivers : simply using the top of the
        // stack would make the entry disappear from the RemoteControlDisplay in conditions such as
        // notifications playing during music playback.
        // crawl the AudioFocus stack until an entry is found with the following characteristics:
        // - focus gain on STREAM_MUSIC stream
        // - non-transient focus gain on a stream other than music
        FocusStackEntry af = null;
        Iterator<FocusStackEntry> stackIterator = mFocusStack.iterator();
        while(stackIterator.hasNext()) {
            FocusStackEntry fse = (FocusStackEntry)stackIterator.next();
            if ((fse.mStreamType == AudioManager.STREAM_MUSIC)
                    || (fse.mFocusChangeType == AudioManager.AUDIOFOCUS_GAIN)) {
                af = fse;
                break;
            }
        }
        if (af == null) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }

        // if the audio focus and RC owners belong to different packages, there is a mismatch, clear
        if ((mRCStack.peek().mCallingPackageName != null)
                && (af.mPackageName != null)
                && !(mRCStack.peek().mCallingPackageName.compareTo(
                        af.mPackageName) == 0)) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }
        // if the audio focus didn't originate from the same Uid as the one in which the remote
        //   control information will be retrieved, clear
        if (mRCStack.peek().mCallingUid != af.mCallingUid) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }
        // refresh conditions were verified: update the remote controls
        // ok to call: synchronized mAudioFocusLock then on mRCStack, mRCStack is not empty
        updateRemoteControlDisplay_syncAfRcs(infoChangedFlags);
    }

    /**
     * see AudioManager.registerMediaButtonIntent(PendingIntent pi, ComponentName c)
     * precondition: mediaIntent != null, target != null
     */
    public void registerMediaButtonIntent(PendingIntent mediaIntent, ComponentName eventReceiver) {
        Log.i(TAG, "  Remote Control   registerMediaButtonIntent() for " + mediaIntent);

        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                pushMediaButtonReceiver(mediaIntent, eventReceiver);
                // new RC client, assume every type of information shall be queried
                checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
            }
        }
    }

    /**
     * see AudioManager.unregisterMediaButtonIntent(PendingIntent mediaIntent)
     * precondition: mediaIntent != null, eventReceiver != null
     */
    public void unregisterMediaButtonIntent(PendingIntent mediaIntent, ComponentName eventReceiver)
    {
        Log.i(TAG, "  Remote Control   unregisterMediaButtonIntent() for " + mediaIntent);

        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                boolean topOfStackWillChange = isCurrentRcController(mediaIntent);
                removeMediaButtonReceiver(mediaIntent);
                if (topOfStackWillChange) {
                    // current RC client will change, assume every type of info needs to be queried
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }
        }
    }

    /**
     * see AudioManager.registerMediaButtonEventReceiverForCalls(ComponentName c)
     * precondition: c != null
     */
    public void registerMediaButtonEventReceiverForCalls(ComponentName c) {
        if (mContext.checkCallingPermission("android.permission.MODIFY_PHONE_STATE")
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Invalid permissions to register media button receiver for calls");
            return;
        }
        synchronized(mRCStack) {
            mMediaReceiverForCalls = c;
        }
    }

    /**
     * see AudioManager.unregisterMediaButtonEventReceiverForCalls()
     */
    public void unregisterMediaButtonEventReceiverForCalls() {
        if (mContext.checkCallingPermission("android.permission.MODIFY_PHONE_STATE")
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Invalid permissions to unregister media button receiver for calls");
            return;
        }
        synchronized(mRCStack) {
            mMediaReceiverForCalls = null;
        }
    }

    /**
     * see AudioManager.registerRemoteControlClient(ComponentName eventReceiver, ...)
     * @return the unique ID of the RemoteControlStackEntry associated with the RemoteControlClient
     * Note: using this method with rcClient == null is a way to "disable" the IRemoteControlClient
     *     without modifying the RC stack, but while still causing the display to refresh (will
     *     become blank as a result of this)
     */
    public int registerRemoteControlClient(PendingIntent mediaIntent,
            IRemoteControlClient rcClient, String callingPackageName) {
        if (DEBUG_RC) Log.i(TAG, "Register remote control client rcClient="+rcClient);
        int rccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                // store the new display information
                Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                while(stackIterator.hasNext()) {
                    RemoteControlStackEntry rcse = stackIterator.next();
                    if(rcse.mMediaIntent.equals(mediaIntent)) {
                        // already had a remote control client?
                        if (rcse.mRcClientDeathHandler != null) {
                            // stop monitoring the old client's death
                            rcse.unlinkToRcClientDeath();
                        }
                        // save the new remote control client
                        rcse.mRcClient = rcClient;
                        rcse.mCallingPackageName = callingPackageName;
                        rcse.mCallingUid = Binder.getCallingUid();
                        if (rcClient == null) {
                            // here rcse.mRcClientDeathHandler is null;
                            rcse.resetPlaybackInfo();
                            break;
                        }
                        rccId = rcse.mRccId;

                        // there is a new (non-null) client:
                        // 1/ give the new client the current display (if any)
                        if (mRcDisplay != null) {
                            try {
                                rcse.mRcClient.plugRemoteControlDisplay(mRcDisplay);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Error connecting remote control display to client: "+e);
                                e.printStackTrace();
                            }
                        }
                        // 2/ monitor the new client's death
                        IBinder b = rcse.mRcClient.asBinder();
                        RcClientDeathHandler rcdh =
                                new RcClientDeathHandler(b, rcse.mMediaIntent);
                        try {
                            b.linkToDeath(rcdh, 0);
                        } catch (RemoteException e) {
                            // remote control client is DOA, disqualify it
                            Log.w(TAG, "registerRemoteControlClient() has a dead client " + b);
                            rcse.mRcClient = null;
                        }
                        rcse.mRcClientDeathHandler = rcdh;
                        break;
                    }
                }
                // if the eventReceiver is at the top of the stack
                // then check for potential refresh of the remote controls
                if (isCurrentRcController(mediaIntent)) {
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }
        }
        return rccId;
    }

    /**
     * see AudioManager.unregisterRemoteControlClient(PendingIntent pi, ...)
     * rcClient is guaranteed non-null
     */
    public void unregisterRemoteControlClient(PendingIntent mediaIntent,
            IRemoteControlClient rcClient) {
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                while(stackIterator.hasNext()) {
                    RemoteControlStackEntry rcse = stackIterator.next();
                    if ((rcse.mMediaIntent.equals(mediaIntent))
                            && rcClient.equals(rcse.mRcClient)) {
                        // we found the IRemoteControlClient to unregister
                        // stop monitoring its death
                        rcse.unlinkToRcClientDeath();
                        // reset the client-related fields
                        rcse.mRcClient = null;
                        rcse.mCallingPackageName = null;
                    }
                }
            }
        }
    }

    /**
     * The remote control displays.
     * Access synchronized on mRCStack
     * NOTE: Only one IRemoteControlDisplay supported in this implementation
     */
    private IRemoteControlDisplay mRcDisplay;
    private RcDisplayDeathHandler mRcDisplayDeathHandler;
    private int mArtworkExpectedWidth = -1;
    private int mArtworkExpectedHeight = -1;
    /**
     * Inner class to monitor remote control display deaths, and unregister them from the list
     * of displays if necessary.
     */
    private class RcDisplayDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death

        public RcDisplayDeathHandler(IBinder b) {
            if (DEBUG_RC) Log.i(TAG, "new RcDisplayDeathHandler for "+b);
            mCb = b;
        }

        public void binderDied() {
            synchronized(mRCStack) {
                Log.w(TAG, "RemoteControl: display died");
                mRcDisplay = null;
            }
        }

        public void unlinkToRcDisplayDeath() {
            if (DEBUG_RC) Log.i(TAG, "unlinkToRcDisplayDeath for "+mCb);
            try {
                mCb.unlinkToDeath(this, 0);
            } catch (java.util.NoSuchElementException e) {
                // not much we can do here, the display was being unregistered anyway
                Log.e(TAG, "Encountered " + e + " in unlinkToRcDisplayDeath()");
                e.printStackTrace();
            }
        }

    }

    private void rcDisplay_stopDeathMonitor_syncRcStack() {
        if (mRcDisplay != null) { // implies (mRcDisplayDeathHandler != null)
            // we had a display before, stop monitoring its death
            mRcDisplayDeathHandler.unlinkToRcDisplayDeath();
        }
    }

    private void rcDisplay_startDeathMonitor_syncRcStack() {
        if (mRcDisplay != null) {
            // new non-null display, monitor its death
            IBinder b = mRcDisplay.asBinder();
            mRcDisplayDeathHandler = new RcDisplayDeathHandler(b);
            try {
                b.linkToDeath(mRcDisplayDeathHandler, 0);
            } catch (RemoteException e) {
                // remote control display is DOA, disqualify it
                Log.w(TAG, "registerRemoteControlDisplay() has a dead client " + b);
                mRcDisplay = null;
            }
        }
    }

    /**
     * Register an IRemoteControlDisplay.
     * Notify all IRemoteControlClient of the new display and cause the RemoteControlClient
     * at the top of the stack to update the new display with its information.
     * Since only one IRemoteControlDisplay is supported, this will unregister the previous display.
     * @param rcd the IRemoteControlDisplay to register. No effect if null.
     */
    public void registerRemoteControlDisplay(IRemoteControlDisplay rcd) {
        if (DEBUG_RC) Log.d(TAG, ">>> registerRemoteControlDisplay("+rcd+")");
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                if ((mRcDisplay == rcd) || (rcd == null)) {
                    return;
                }
                // if we had a display before, stop monitoring its death
                rcDisplay_stopDeathMonitor_syncRcStack();
                mRcDisplay = rcd;
                // new display, start monitoring its death
                rcDisplay_startDeathMonitor_syncRcStack();

                // let all the remote control clients there is a new display
                // no need to unplug the previous because we only support one display
                // and the clients don't track the death of the display
                Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                while(stackIterator.hasNext()) {
                    RemoteControlStackEntry rcse = stackIterator.next();
                    if(rcse.mRcClient != null) {
                        try {
                            rcse.mRcClient.plugRemoteControlDisplay(mRcDisplay);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error connecting remote control display to client: " + e);
                            e.printStackTrace();
                        }
                    }
                }

                // we have a new display, of which all the clients are now aware: have it be updated
                checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
            }
        }
    }

    /**
     * Unregister an IRemoteControlDisplay.
     * Since only one IRemoteControlDisplay is supported, this has no effect if the one to
     *    unregister is not the current one.
     * @param rcd the IRemoteControlDisplay to unregister. No effect if null.
     */
    public void unregisterRemoteControlDisplay(IRemoteControlDisplay rcd) {
        if (DEBUG_RC) Log.d(TAG, "<<< unregisterRemoteControlDisplay("+rcd+")");
        synchronized(mRCStack) {
            // only one display here, so you can only unregister the current display
            if ((rcd == null) || (rcd != mRcDisplay)) {
                if (DEBUG_RC) Log.w(TAG, "    trying to unregister unregistered RCD");
                return;
            }
            // if we had a display before, stop monitoring its death
            rcDisplay_stopDeathMonitor_syncRcStack();
            mRcDisplay = null;

            // disconnect this remote control display from all the clients
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                if(rcse.mRcClient != null) {
                    try {
                        rcse.mRcClient.unplugRemoteControlDisplay(rcd);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error disconnecting remote control display to client: " + e);
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public void remoteControlDisplayUsesBitmapSize(IRemoteControlDisplay rcd, int w, int h) {
        synchronized(mRCStack) {
            // NOTE: Only one IRemoteControlDisplay supported in this implementation
            mArtworkExpectedWidth = w;
            mArtworkExpectedHeight = h;
        }
    }

    public void setPlaybackInfoForRcc(int rccId, int what, int value) {
        sendMsg(mAudioHandler, MSG_RCC_NEW_PLAYBACK_INFO, SENDMSG_QUEUE,
                rccId /* arg1 */, what /* arg2 */, Integer.valueOf(value) /* obj */, 0 /* delay */);
    }

    // handler for MSG_RCC_NEW_PLAYBACK_INFO
    private void onNewPlaybackInfoForRcc(int rccId, int key, int value) {
        if(DEBUG_RC) Log.d(TAG, "onNewPlaybackInfoForRcc(id=" + rccId +
                ", what=" + key + ",val=" + value + ")");
        synchronized(mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                if (rcse.mRccId == rccId) {
                    switch (key) {
                        case RemoteControlClient.PLAYBACKINFO_PLAYBACK_TYPE:
                            rcse.mPlaybackType = value;
                            postReevaluateRemote();
                            break;
                        case RemoteControlClient.PLAYBACKINFO_VOLUME:
                            rcse.mPlaybackVolume = value;
                            synchronized (mMainRemote) {
                                if (rccId == mMainRemote.mRccId) {
                                    mMainRemote.mVolume = value;
                                    mVolumePanel.postHasNewRemotePlaybackInfo();
                                }
                            }
                            break;
                        case RemoteControlClient.PLAYBACKINFO_VOLUME_MAX:
                            rcse.mPlaybackVolumeMax = value;
                            synchronized (mMainRemote) {
                                if (rccId == mMainRemote.mRccId) {
                                    mMainRemote.mVolumeMax = value;
                                    mVolumePanel.postHasNewRemotePlaybackInfo();
                                }
                            }
                            break;
                        case RemoteControlClient.PLAYBACKINFO_VOLUME_HANDLING:
                            rcse.mPlaybackVolumeHandling = value;
                            synchronized (mMainRemote) {
                                if (rccId == mMainRemote.mRccId) {
                                    mMainRemote.mVolumeHandling = value;
                                    mVolumePanel.postHasNewRemotePlaybackInfo();
                                }
                            }
                            break;
                        case RemoteControlClient.PLAYBACKINFO_USES_STREAM:
                            rcse.mPlaybackStream = value;
                            break;
                        case RemoteControlClient.PLAYBACKINFO_PLAYSTATE:
                            rcse.mPlaybackState = value;
                            synchronized (mMainRemote) {
                                if (rccId == mMainRemote.mRccId) {
                                    mMainRemoteIsActive = isPlaystateActive(value);
                                    postReevaluateRemote();
                                }
                            }
                            break;
                        default:
                            Log.e(TAG, "unhandled key " + key + " for RCC " + rccId);
                            break;
                    }
                    return;
                }
            }
        }
    }

    public void registerRemoteVolumeObserverForRcc(int rccId, IRemoteVolumeObserver rvo) {
        sendMsg(mAudioHandler, MSG_RCC_NEW_VOLUME_OBS, SENDMSG_QUEUE,
                rccId /* arg1 */, 0, rvo /* obj */, 0 /* delay */);
    }

    // handler for MSG_RCC_NEW_VOLUME_OBS
    private void onRegisterVolumeObserverForRcc(int rccId, IRemoteVolumeObserver rvo) {
        synchronized(mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                if (rcse.mRccId == rccId) {
                    rcse.mRemoteVolumeObs = rvo;
                    break;
                }
            }
        }
    }

    /**
     * Checks if a remote client is active on the supplied stream type. Update the remote stream
     * volume state if found and playing
     * @param streamType
     * @return false if no remote playing is currently playing
     */
    private boolean checkUpdateRemoteStateIfActive(int streamType) {
        synchronized(mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                if ((rcse.mPlaybackType == RemoteControlClient.PLAYBACK_TYPE_REMOTE)
                        && isPlaystateActive(rcse.mPlaybackState)
                        && (rcse.mPlaybackStream == streamType)) {
                    if (DEBUG_RC) Log.d(TAG, "remote playback active on stream " + streamType
                            + ", vol =" + rcse.mPlaybackVolume);
                    synchronized (mMainRemote) {
                        mMainRemote.mRccId = rcse.mRccId;
                        mMainRemote.mVolume = rcse.mPlaybackVolume;
                        mMainRemote.mVolumeMax = rcse.mPlaybackVolumeMax;
                        mMainRemote.mVolumeHandling = rcse.mPlaybackVolumeHandling;
                        mMainRemoteIsActive = true;
                    }
                    return true;
                }
            }
        }
        synchronized (mMainRemote) {
            mMainRemoteIsActive = false;
        }
        return false;
    }

    /**
     * Returns true if the given playback state is considered "active", i.e. it describes a state
     * where playback is happening, or about to
     * @param playState the playback state to evaluate
     * @return true if active, false otherwise (inactive or unknown)
     */
    private static boolean isPlaystateActive(int playState) {
        switch (playState) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
            case RemoteControlClient.PLAYSTATE_BUFFERING:
            case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
            case RemoteControlClient.PLAYSTATE_REWINDING:
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
            case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
                return true;
            default:
                return false;
        }
    }

    private void adjustRemoteVolume(int streamType, int direction, int flags) {
        int rccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        boolean volFixed = false;
        synchronized (mMainRemote) {
            if (!mMainRemoteIsActive) {
                if (DEBUG_VOL) Log.w(TAG, "adjustRemoteVolume didn't find an active client");
                return;
            }
            rccId = mMainRemote.mRccId;
            volFixed = (mMainRemote.mVolumeHandling ==
                    RemoteControlClient.PLAYBACK_VOLUME_FIXED);
        }
        // unlike "local" stream volumes, we can't compute the new volume based on the direction,
        // we can only notify the remote that volume needs to be updated, and we'll get an async'
        // update through setPlaybackInfoForRcc()
        if (!volFixed) {
            sendVolumeUpdateToRemote(rccId, direction);
        }

        // fire up the UI
        mVolumePanel.postRemoteVolumeChanged(streamType, flags);
    }

    private void sendVolumeUpdateToRemote(int rccId, int direction) {
        if (DEBUG_VOL) { Log.d(TAG, "sendVolumeUpdateToRemote(rccId="+rccId+" , dir="+direction); }
        if (direction == 0) {
            // only handling discrete events
            return;
        }
        IRemoteVolumeObserver rvo = null;
        synchronized (mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                //FIXME OPTIMIZE store this info in mMainRemote so we don't have to iterate?
                if (rcse.mRccId == rccId) {
                    rvo = rcse.mRemoteVolumeObs;
                    break;
                }
            }
        }
        if (rvo != null) {
            try {
                rvo.dispatchRemoteVolumeUpdate(direction, -1);
            } catch (RemoteException e) {
                Log.e(TAG, "Error dispatching relative volume update", e);
            }
        }
    }

    public int getRemoteStreamMaxVolume() {
        synchronized (mMainRemote) {
            if (mMainRemote.mRccId == RemoteControlClient.RCSE_ID_UNREGISTERED) {
                return 0;
            }
            return mMainRemote.mVolumeMax;
        }
    }

    public int getRemoteStreamVolume() {
        synchronized (mMainRemote) {
            if (mMainRemote.mRccId == RemoteControlClient.RCSE_ID_UNREGISTERED) {
                return 0;
            }
            return mMainRemote.mVolume;
        }
    }

    public void setRemoteStreamVolume(int vol) {
        if (DEBUG_VOL) { Log.d(TAG, "setRemoteStreamVolume(vol="+vol+")"); }
        int rccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        synchronized (mMainRemote) {
            if (mMainRemote.mRccId == RemoteControlClient.RCSE_ID_UNREGISTERED) {
                return;
            }
            rccId = mMainRemote.mRccId;
        }
        IRemoteVolumeObserver rvo = null;
        synchronized (mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                if (rcse.mRccId == rccId) {
                    //FIXME OPTIMIZE store this info in mMainRemote so we don't have to iterate?
                    rvo = rcse.mRemoteVolumeObs;
                    break;
                }
            }
        }
        if (rvo != null) {
            try {
                rvo.dispatchRemoteVolumeUpdate(0, vol);
            } catch (RemoteException e) {
                Log.e(TAG, "Error dispatching absolute volume update", e);
            }
        }
    }

    /**
     * Call to make AudioService reevaluate whether it's in a mode where remote players should
     * have their volume controlled. In this implementation this is only to reset whether
     * VolumePanel should display remote volumes
     */
    private void postReevaluateRemote() {
        sendMsg(mAudioHandler, MSG_REEVALUATE_REMOTE, SENDMSG_QUEUE, 0, 0, null, 0);
    }

    private void onReevaluateRemote() {
        if (DEBUG_VOL) { Log.w(TAG, "onReevaluateRemote()"); }
        // is there a registered RemoteControlClient that is handling remote playback
        boolean hasRemotePlayback = false;
        synchronized (mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                if (rcse.mPlaybackType == RemoteControlClient.PLAYBACK_TYPE_REMOTE) {
                    hasRemotePlayback = true;
                    break;
                }
            }
        }
        synchronized (mMainRemote) {
            if (mHasRemotePlayback != hasRemotePlayback) {
                mHasRemotePlayback = hasRemotePlayback;
                mVolumePanel.postRemoteSliderVisibility(hasRemotePlayback);
            }
        }
    }

    //==========================================================================================
    // Device orientation
    //==========================================================================================
    /**
     * Handles device configuration changes that may map to a change in the orientation.
     * This feature is optional, and is defined by the definition and value of the
     * "ro.audio.monitorOrientation" system property.
     */
    private void handleConfigurationChanged(Context context) {
        try {
            // reading new orientation "safely" (i.e. under try catch) in case anything
            // goes wrong when obtaining resources and configuration
            Configuration config = context.getResources().getConfiguration();
            if (mMonitorOrientation) {
                int newOrientation = config.orientation;
                if (newOrientation != mDeviceOrientation) {
                    mDeviceOrientation = newOrientation;
                    setOrientationForAudioSystem();
                }
            }
            sendMsg(mAudioHandler,
                    MSG_CONFIGURE_SAFE_MEDIA_VOLUME,
                    SENDMSG_REPLACE,
                    0,
                    0,
                    null,
                    0);

            boolean cameraSoundForced = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_camera_sound_forced);
            synchronized (mSettingsLock) {
                synchronized (mCameraSoundForced) {
                    if (cameraSoundForced != mCameraSoundForced) {
                        mCameraSoundForced = cameraSoundForced;

                        VolumeStreamState s = mStreamStates[AudioSystem.STREAM_SYSTEM_ENFORCED];
                        if (cameraSoundForced) {
                            s.setAllIndexesToMax();
                            mRingerModeAffectedStreams &=
                                    ~(1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                        } else {
                            s.setAllIndexes(mStreamStates[AudioSystem.STREAM_SYSTEM],
                                            false /*lastAudible*/);
                            s.setAllIndexes(mStreamStates[AudioSystem.STREAM_SYSTEM],
                                            true /*lastAudible*/);
                            mRingerModeAffectedStreams |=
                                    (1 << AudioSystem.STREAM_SYSTEM_ENFORCED);
                        }
                        // take new state into account for streams muted by ringer mode
                        setRingerModeInt(getRingerMode(), false);

                        sendMsg(mAudioHandler,
                                MSG_SET_FORCE_USE,
                                SENDMSG_QUEUE,
                                AudioSystem.FOR_SYSTEM,
                                cameraSoundForced ?
                                        AudioSystem.FORCE_SYSTEM_ENFORCED : AudioSystem.FORCE_NONE,
                                null,
                                0);

                        sendMsg(mAudioHandler,
                                MSG_SET_ALL_VOLUMES,
                                SENDMSG_QUEUE,
                                0,
                                0,
                                mStreamStates[AudioSystem.STREAM_SYSTEM_ENFORCED], 0);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving device orientation: " + e);
        }
    }

    private void setOrientationForAudioSystem() {
        switch (mDeviceOrientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                //Log.i(TAG, "orientation is landscape");
                AudioSystem.setParameters("orientation=landscape");
                break;
            case Configuration.ORIENTATION_PORTRAIT:
                //Log.i(TAG, "orientation is portrait");
                AudioSystem.setParameters("orientation=portrait");
                break;
            case Configuration.ORIENTATION_SQUARE:
                //Log.i(TAG, "orientation is square");
                AudioSystem.setParameters("orientation=square");
                break;
            case Configuration.ORIENTATION_UNDEFINED:
                //Log.i(TAG, "orientation is undefined");
                AudioSystem.setParameters("orientation=undefined");
                break;
            default:
                Log.e(TAG, "Unknown orientation");
        }
    }


    // Handles request to override default use of A2DP for media.
    public void setBluetoothA2dpOnInt(boolean on) {
        synchronized (mBluetoothA2dpEnabledLock) {
            mBluetoothA2dpEnabled = on;
            mAudioHandler.removeMessages(MSG_SET_FORCE_BT_A2DP_USE);
            AudioSystem.setForceUse(AudioSystem.FOR_MEDIA,
                    mBluetoothA2dpEnabled ? AudioSystem.FORCE_NONE : AudioSystem.FORCE_NO_BT_A2DP);
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
        synchronized (mCurAudioRoutes) {
            AudioRoutesInfo routes = new AudioRoutesInfo(mCurAudioRoutes);
            mRoutesObservers.register(observer);
            return routes;
        }
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
    private final int SAFE_MEDIA_VOLUME_NOT_CONFIGURED = 0;
    private final int SAFE_MEDIA_VOLUME_DISABLED = 1;
    private final int SAFE_MEDIA_VOLUME_INACTIVE = 2;
    private final int SAFE_MEDIA_VOLUME_ACTIVE = 3;
    private Integer mSafeMediaVolumeState;

    private int mMcc = 0;
    // mSafeMediaVolumeIndex is the cached value of config_safe_media_volume_index property
    private int mSafeMediaVolumeIndex;
    // mSafeMediaVolumeDevices lists the devices for which safe media volume is enforced,
    private final int mSafeMediaVolumeDevices = AudioSystem.DEVICE_OUT_WIRED_HEADSET |
                                                AudioSystem.DEVICE_OUT_WIRED_HEADPHONE;
    // mMusicActiveMs is the cumulative time of music activity since safe volume was disabled.
    // When this time reaches UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX, the safe media volume is re-enabled
    // automatically. mMusicActiveMs is rounded to a multiple of MUSIC_ACTIVE_POLL_PERIOD_MS.
    private int mMusicActiveMs;
    private static final int UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX = (20 * 3600 * 1000); // 20 hours
    private static final int MUSIC_ACTIVE_POLL_PERIOD_MS = 60000;  // 1 minute polling interval
    private static final int SAFE_VOLUME_CONFIGURE_TIMEOUT_MS = 30000;  // 30s after boot completed

    private void setSafeMediaVolumeEnabled(boolean on) {
        synchronized (mSafeMediaVolumeState) {
            if ((mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_NOT_CONFIGURED) &&
                    (mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_DISABLED)) {
                if (on && (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_INACTIVE)) {
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_ACTIVE;
                    enforceSafeMediaVolume();
                } else if (!on && (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE)) {
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_INACTIVE;
                    mMusicActiveMs = 0;
                    sendMsg(mAudioHandler,
                            MSG_CHECK_MUSIC_ACTIVE,
                            SENDMSG_REPLACE,
                            0,
                            0,
                            null,
                            MUSIC_ACTIVE_POLL_PERIOD_MS);
                }
            }
        }
    }

    private void enforceSafeMediaVolume() {
        VolumeStreamState streamState = mStreamStates[AudioSystem.STREAM_MUSIC];
        boolean lastAudible = (streamState.muteCount() != 0);
        int devices = mSafeMediaVolumeDevices;
        int i = 0;

        while (devices != 0) {
            int device = 1 << i++;
            if ((device & devices) == 0) {
                continue;
            }
            int index = streamState.getIndex(device, lastAudible);
            if (index > mSafeMediaVolumeIndex) {
                if (lastAudible) {
                    streamState.setLastAudibleIndex(mSafeMediaVolumeIndex, device);
                    sendMsg(mAudioHandler,
                            MSG_PERSIST_VOLUME,
                            SENDMSG_QUEUE,
                            PERSIST_LAST_AUDIBLE,
                            device,
                            streamState,
                            PERSIST_DELAY);
                } else {
                    streamState.setIndex(mSafeMediaVolumeIndex, device, true);
                    sendMsg(mAudioHandler,
                            MSG_SET_DEVICE_VOLUME,
                            SENDMSG_QUEUE,
                            device,
                            0,
                            streamState,
                            0);
                }
            }
            devices &= ~device;
        }
    }

    private boolean checkSafeMediaVolume(int streamType, int index, int device) {
        synchronized (mSafeMediaVolumeState) {
            if ((mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE) &&
                    (mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC) &&
                    ((device & mSafeMediaVolumeDevices) != 0) &&
                    (index > mSafeMediaVolumeIndex)) {
                mVolumePanel.postDisplaySafeVolumeWarning();
                return false;
            }
            return true;
        }
    }

    public void disableSafeMediaVolume() {
        synchronized (mSafeMediaVolumeState) {
            setSafeMediaVolumeEnabled(false);
        }
    }


    //==========================================================================================
    // Camera shutter sound policy.
    // config_camera_sound_forced configuration option in config.xml defines if the camera shutter
    // sound is forced (sound even if the device is in silent mode) or not. This option is false by
    // default and can be overridden by country specific overlay in values-mccXXX/config.xml.
    //==========================================================================================

    // cached value of com.android.internal.R.bool.config_camera_sound_forced
    private Boolean mCameraSoundForced;

    // called by android.hardware.Camera to populate CameraInfo.canDisableShutterSound
    public boolean isCameraSoundForced() {
        synchronized (mCameraSoundForced) {
            return mCameraSoundForced;
        }
    }

    private static final String[] RINGER_MODE_NAMES = new String[] {
            "SILENT",
            "VIBRATE",
            "NORMAL"
    };

    private void dumpRingerMode(PrintWriter pw) {
        pw.println("\nRinger mode: ");
        pw.println("- mode: "+RINGER_MODE_NAMES[mRingerMode]);
        pw.print("- ringer mode affected streams = 0x");
        pw.println(Integer.toHexString(mRingerModeAffectedStreams));
        pw.print("- ringer mode muted streams = 0x");
        pw.println(Integer.toHexString(mRingerModeMutedStreams));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        dumpFocusStack(pw);
        dumpRCStack(pw);
        dumpRCCStack(pw);
        dumpStreamStates(pw);
        dumpRingerMode(pw);
        pw.println("\nAudio routes:");
        pw.print("  mMainType=0x"); pw.println(Integer.toHexString(mCurAudioRoutes.mMainType));
        pw.print("  mBluetoothName="); pw.println(mCurAudioRoutes.mBluetoothName);
    }
}
