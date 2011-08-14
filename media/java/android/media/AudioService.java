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

import android.app.ActivityManagerNative;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.System;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.VolumePanel;

import com.android.internal.telephony.ITelephony;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
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
public class AudioService extends IAudioService.Stub {

    private static final String TAG = "AudioService";

    /** How long to delay before persisting a change in volume/ringer mode. */
    private static final int PERSIST_DELAY = 3000;

    private Context mContext;
    private ContentResolver mContentResolver;
    private boolean mVoiceCapable;

    /** The UI */
    private VolumePanel mVolumePanel;

    // sendMsg() flags
    /** Used when a message should be shared across all stream types. */
    private static final int SHARED_MSG = -1;
    /** If the msg is already queued, replace it with this one. */
    private static final int SENDMSG_REPLACE = 0;
    /** If the msg is already queued, ignore this one and leave the old. */
    private static final int SENDMSG_NOOP = 1;
    /** If the msg is already queued, queue this one and leave the old. */
    private static final int SENDMSG_QUEUE = 2;

    // AudioHandler message.whats
    private static final int MSG_SET_SYSTEM_VOLUME = 0;
    private static final int MSG_PERSIST_VOLUME = 1;
    private static final int MSG_PERSIST_RINGER_MODE = 3;
    private static final int MSG_PERSIST_VIBRATE_SETTING = 4;
    private static final int MSG_MEDIA_SERVER_DIED = 5;
    private static final int MSG_MEDIA_SERVER_STARTED = 6;
    private static final int MSG_PLAY_SOUND_EFFECT = 7;
    private static final int MSG_BTA2DP_DOCK_TIMEOUT = 8;
    private static final int MSG_LOAD_SOUND_EFFECTS = 9;
    private static final int MSG_SET_FORCE_USE = 10;
    private static final int MSG_PERSIST_MEDIABUTTONRECEIVER = 11;
    private static final int MSG_BT_HEADSET_CNCT_FAILED = 12;
    private static final int MSG_RCDISPLAY_CLEAR = 13;
    private static final int MSG_RCDISPLAY_UPDATE = 14;

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
    private Object mSettingsLock = new Object();
    private boolean mMediaServerOk;

    private SoundPool mSoundPool;
    private Object mSoundEffectsLock = new Object();
    private static final int NUM_SOUNDPOOL_CHANNELS = 4;
    private static final int SOUND_EFFECT_VOLUME = 1000;

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
    private int[][] SOUND_EFFECT_FILES_MAP = new int[][] {
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
    private int[] MAX_STREAM_VOLUME = new int[] {
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
    /* STREAM_VOLUME_ALIAS[] indicates for each stream if it uses the volume settings
     * of another stream: This avoids multiplying the volume settings for hidden
     * stream types that follow other stream behavior for volume settings
     * NOTE: do not create loops in aliases! */
    private int[] STREAM_VOLUME_ALIAS = new int[] {
        AudioSystem.STREAM_VOICE_CALL,  // STREAM_VOICE_CALL
        AudioSystem.STREAM_SYSTEM,  // STREAM_SYSTEM
        AudioSystem.STREAM_RING,  // STREAM_RING
        AudioSystem.STREAM_MUSIC, // STREAM_MUSIC
        AudioSystem.STREAM_ALARM,  // STREAM_ALARM
        AudioSystem.STREAM_RING,   // STREAM_NOTIFICATION
        AudioSystem.STREAM_BLUETOOTH_SCO, // STREAM_BLUETOOTH_SCO
        AudioSystem.STREAM_SYSTEM,  // STREAM_SYSTEM_ENFORCED
        AudioSystem.STREAM_VOICE_CALL, // STREAM_DTMF
        AudioSystem.STREAM_MUSIC  // STREAM_TTS
    };

    private AudioSystem.ErrorCallback mAudioSystemCallback = new AudioSystem.ErrorCallback() {
        public void onError(int error) {
            switch (error) {
            case AudioSystem.AUDIO_STATUS_SERVER_DIED:
                if (mMediaServerOk) {
                    sendMsg(mAudioHandler, MSG_MEDIA_SERVER_DIED, SHARED_MSG, SENDMSG_NOOP, 0, 0,
                            null, 1500);
                    mMediaServerOk = false;
                }
                break;
            case AudioSystem.AUDIO_STATUS_OK:
                if (!mMediaServerOk) {
                    sendMsg(mAudioHandler, MSG_MEDIA_SERVER_STARTED, SHARED_MSG, SENDMSG_NOOP, 0, 0,
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
    private int mRingerMode;

    /** @see System#MODE_RINGER_STREAMS_AFFECTED */
    private int mRingerModeAffectedStreams;

    // Streams currently muted by ringer mode
    private int mRingerModeMutedStreams;

    /** @see System#MUTE_STREAMS_AFFECTED */
    private int mMuteAffectedStreams;

    /**
     * Has multiple bits per vibrate type to indicate the type's vibrate
     * setting. See {@link #setVibrateSetting(int, int)}.
     * <p>
     * NOTE: This is not the final decision of whether vibrate is on/off for the
     * type since it depends on the ringer mode. See {@link #shouldVibrate(int)}.
     */
    private int mVibrateSetting;

    // Broadcast receiver for device connections intent broadcasts
    private final BroadcastReceiver mReceiver = new AudioServiceBroadcastReceiver();

    //  Broadcast receiver for media button broadcasts (separate from mReceiver to
    //  independently change its priority)
    private final BroadcastReceiver mMediaButtonReceiver = new MediaButtonBroadcastReceiver();

    // Used to alter media button redirection when the phone is ringing.
    private boolean mIsRinging = false;

    // Devices currently connected
    private HashMap <Integer, String> mConnectedDevices = new HashMap <Integer, String>();

    // Forced device usage for communications
    private int mForcedUseForComm;

    // List of binder death handlers for setMode() client processes.
    // The last process to have called setMode() is at the top of the list.
    private ArrayList <SetModeDeathHandler> mSetModeDeathHandlers = new ArrayList <SetModeDeathHandler>();

    // List of clients having issued a SCO start request
    private ArrayList <ScoClient> mScoClients = new ArrayList <ScoClient>();

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
    // default volume applied to sound played with playSoundEffect()
    private static final int SOUND_EFFECT_DEFAULT_VOLUME_DB = -20;
    // volume applied to sound played with playSoundEffect() read from ro.config.sound_fx_volume
    private int SOUND_EFFECT_VOLUME_DB;
    // getActiveStreamType() will return STREAM_NOTIFICATION during this period after a notification
    // stopped
    private static final int NOTIFICATION_VOLUME_DELAY_MS = 5000;
    // previous volume adjustment direction received by checkForRingerModeChange()
    private int mPrevVolDirection = AudioManager.ADJUST_SAME;

    ///////////////////////////////////////////////////////////////////////////
    // Construction
    ///////////////////////////////////////////////////////////////////////////

    /** @hide */
    public AudioService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mVoiceCapable = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);

       // Intialized volume
        MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] = SystemProperties.getInt(
            "ro.config.vc_call_vol_steps",
           MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL]);

        SOUND_EFFECT_VOLUME_DB = SystemProperties.getInt(
                "ro.config.sound_fx_volume",
                SOUND_EFFECT_DEFAULT_VOLUME_DB);

        mVolumePanel = new VolumePanel(context, this);
        mForcedUseForComm = AudioSystem.FORCE_NONE;
        createAudioSystemThread();
        readPersistedSettings();
        mSettingsObserver = new SettingsObserver();
        createStreamStates();
        // Call setMode() to initialize mSetModeDeathHandlers
        mMode = AudioSystem.MODE_INVALID;
        setMode(AudioSystem.MODE_NORMAL, null);
        mMediaServerOk = true;

        // Call setRingerModeInt() to apply correct mute
        // state on streams affected by ringer mode.
        mRingerModeMutedStreams = 0;
        setRingerModeInt(getRingerMode(), false);

        AudioSystem.setErrorCallback(mAudioSystemCallback);

        // Register for device connection intent broadcasts.
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_HEADSET_PLUG);

        intentFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
        intentFilter.addAction(Intent.ACTION_USB_ANLG_HEADSET_PLUG);
        intentFilter.addAction(Intent.ACTION_USB_DGTL_HEADSET_PLUG);
        intentFilter.addAction(Intent.ACTION_HDMI_AUDIO_PLUG);
        intentFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(mReceiver, intentFilter);

        // Register for package removal intent broadcasts for media button receiver persistence
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addDataScheme("package");
        context.registerReceiver(mReceiver, pkgFilter);

        // Register for media button intent broadcasts.
        intentFilter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
        // Workaround for bug on priority setting
        //intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        intentFilter.setPriority(Integer.MAX_VALUE);
        context.registerReceiver(mMediaButtonReceiver, intentFilter);

        // Register for phone state monitoring
        TelephonyManager tmgr = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        tmgr.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
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

    private void createStreamStates() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        VolumeStreamState[] streams = mStreamStates = new VolumeStreamState[numStreamTypes];

        for (int i = 0; i < numStreamTypes; i++) {
            streams[i] = new VolumeStreamState(System.VOLUME_SETTINGS[STREAM_VOLUME_ALIAS[i]], i);
        }

        // Correct stream index values for streams with aliases
        for (int i = 0; i < numStreamTypes; i++) {
            if (STREAM_VOLUME_ALIAS[i] != i) {
                int index = rescaleIndex(streams[i].mIndex, STREAM_VOLUME_ALIAS[i], i);
                streams[i].mIndex = streams[i].getValidIndex(index);
                setStreamVolumeIndex(i, index);
                index = rescaleIndex(streams[i].mLastAudibleIndex, STREAM_VOLUME_ALIAS[i], i);
                streams[i].mLastAudibleIndex = streams[i].getValidIndex(index);
            }
        }
    }

    private void readPersistedSettings() {
        final ContentResolver cr = mContentResolver;

        mRingerMode = System.getInt(cr, System.MODE_RINGER, AudioManager.RINGER_MODE_NORMAL);
        // sanity check in case the settings are restored from a device with incompatible
        // ringer modes
        if (!AudioManager.isValidRingerMode(mRingerMode)) {
            mRingerMode = AudioManager.RINGER_MODE_NORMAL;
            System.putInt(cr, System.MODE_RINGER, mRingerMode);
        }

        mVibrateSetting = System.getInt(cr, System.VIBRATE_ON, 0);

        // make sure settings for ringer mode are consistent with device type: non voice capable
        // devices (tablets) include media stream in silent mode whereas phones don't.
        mRingerModeAffectedStreams = Settings.System.getInt(cr,
                Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                ((1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_NOTIFICATION)|
                 (1 << AudioSystem.STREAM_SYSTEM)|(1 << AudioSystem.STREAM_SYSTEM_ENFORCED)));
        if (mVoiceCapable) {
            mRingerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_MUSIC);
        } else {
            mRingerModeAffectedStreams |= (1 << AudioSystem.STREAM_MUSIC);
        }
        Settings.System.putInt(cr,
                Settings.System.MODE_RINGER_STREAMS_AFFECTED, mRingerModeAffectedStreams);

        mMuteAffectedStreams = System.getInt(cr,
                System.MUTE_STREAMS_AFFECTED,
                ((1 << AudioSystem.STREAM_MUSIC)|(1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_SYSTEM)));

        // Each stream will read its own persisted settings

        // Broadcast the sticky intent
        broadcastRingerMode();

        // Broadcast vibrate settings
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER);
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION);

        // Restore the default media button receiver from the system settings
        restoreMediaButtonReceiver();
    }

    private void setStreamVolumeIndex(int stream, int index) {
        AudioSystem.setStreamVolumeIndex(stream, (index + 5)/10);
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

    /** @see AudioManager#adjustVolume(int, int, int) */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {

        int streamType;
        if ((flags & AudioManager.FLAG_FORCE_STREAM) != 0) {
            streamType = suggestedStreamType;
        } else {
            streamType = getActiveStreamType(suggestedStreamType);
        }

        // Don't play sound on other streams
        if (streamType != AudioSystem.STREAM_RING && (flags & AudioManager.FLAG_PLAY_SOUND) != 0) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }

        adjustStreamVolume(streamType, direction, flags);
    }

    /** @see AudioManager#adjustStreamVolume(int, int, int) */
    public void adjustStreamVolume(int streamType, int direction, int flags) {
        ensureValidDirection(direction);
        ensureValidStreamType(streamType);


        VolumeStreamState streamState = mStreamStates[STREAM_VOLUME_ALIAS[streamType]];
        final int oldIndex = (streamState.muteCount() != 0) ? streamState.mLastAudibleIndex : streamState.mIndex;
        boolean adjustVolume = true;

        // If either the client forces allowing ringer modes for this adjustment,
        // or the stream type is one that is affected by ringer modes
        if (((flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0) ||
             (!mVoiceCapable && streamType != AudioSystem.STREAM_VOICE_CALL &&
               streamType != AudioSystem.STREAM_BLUETOOTH_SCO) ||
                (mVoiceCapable && streamType == AudioSystem.STREAM_RING)) {
            // Check if the ringer mode changes with this volume adjustment. If
            // it does, it will handle adjusting the volume, so we won't below
            adjustVolume = checkForRingerModeChange(oldIndex, direction);
        }

        // If stream is muted, adjust last audible index only
        int index;
        if (streamState.muteCount() != 0) {
            if (adjustVolume) {
                streamState.adjustLastAudibleIndex(direction);
                // Post a persist volume msg
                sendMsg(mAudioHandler, MSG_PERSIST_VOLUME, streamType,
                        SENDMSG_REPLACE, 0, 1, streamState, PERSIST_DELAY);
            }
            index = streamState.mLastAudibleIndex;
        } else {
            if (adjustVolume && streamState.adjustIndex(direction)) {
                // Post message to set system volume (it in turn will post a message
                // to persist). Do not change volume if stream is muted.
                sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, STREAM_VOLUME_ALIAS[streamType], SENDMSG_NOOP, 0, 0,
                        streamState, 0);
            }
            index = streamState.mIndex;
        }

        sendVolumeUpdate(streamType, oldIndex, index, flags);
    }

    /** @see AudioManager#setStreamVolume(int, int, int) */
    public void setStreamVolume(int streamType, int index, int flags) {
        ensureValidStreamType(streamType);
        VolumeStreamState streamState = mStreamStates[STREAM_VOLUME_ALIAS[streamType]];

        final int oldIndex = (streamState.muteCount() != 0) ? streamState.mLastAudibleIndex : streamState.mIndex;

        index = rescaleIndex(index * 10, streamType, STREAM_VOLUME_ALIAS[streamType]);
        setStreamVolumeInt(STREAM_VOLUME_ALIAS[streamType], index, false, true);

        index = (streamState.muteCount() != 0) ? streamState.mLastAudibleIndex : streamState.mIndex;

        sendVolumeUpdate(streamType, oldIndex, index, flags);
    }

    // UI update and Broadcast Intent
    private void sendVolumeUpdate(int streamType, int oldIndex, int index, int flags) {
        if (!mVoiceCapable && (streamType == AudioSystem.STREAM_RING)) {
            streamType = AudioSystem.STREAM_NOTIFICATION;
        }

        mVolumePanel.postVolumeChanged(streamType, flags);

        oldIndex = (oldIndex + 5) / 10;
        index = (index + 5) / 10;
        Intent intent = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, streamType);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, index);
        intent.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, oldIndex);
        mContext.sendBroadcast(intent);
    }

    /**
     * Sets the stream state's index, and posts a message to set system volume.
     * This will not call out to the UI. Assumes a valid stream type.
     *
     * @param streamType Type of the stream
     * @param index Desired volume index of the stream
     * @param force If true, set the volume even if the desired volume is same
     * as the current volume.
     * @param lastAudible If true, stores new index as last audible one
     */
    private void setStreamVolumeInt(int streamType, int index, boolean force, boolean lastAudible) {
        VolumeStreamState streamState = mStreamStates[streamType];

        // If stream is muted, set last audible index only
        if (streamState.muteCount() != 0) {
            // Do not allow last audible index to be 0
            if (index != 0) {
                streamState.setLastAudibleIndex(index);
                // Post a persist volume msg
                sendMsg(mAudioHandler, MSG_PERSIST_VOLUME, streamType,
                        SENDMSG_REPLACE, 0, 1, streamState, PERSIST_DELAY);
            }
        } else {
            if (streamState.setIndex(index, lastAudible) || force) {
                // Post message to set system volume (it in turn will post a message
                // to persist).
                sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, streamType, SENDMSG_NOOP, 0, 0,
                        streamState, 0);
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

    /** @see AudioManager#getStreamVolume(int) */
    public int getStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].mIndex + 5) / 10;
    }

    /** @see AudioManager#getStreamMaxVolume(int) */
    public int getStreamMaxVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].getMaxIndex() + 5) / 10;
    }


    /** Get last audible volume before stream was muted. */
    public int getLastAudibleStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].mLastAudibleIndex + 5) / 10;
    }

    /** @see AudioManager#getRingerMode() */
    public int getRingerMode() {
        return mRingerMode;
    }

    /** @see AudioManager#setRingerMode(int) */
    public void setRingerMode(int ringerMode) {
        synchronized (mSettingsLock) {
            if (ringerMode != mRingerMode) {
                setRingerModeInt(ringerMode, true);
                // Send sticky broadcast
                broadcastRingerMode();
            }
        }
    }

    private void setRingerModeInt(int ringerMode, boolean persist) {
        mRingerMode = ringerMode;

        // Mute stream if not previously muted by ringer mode and ringer mode
        // is not RINGER_MODE_NORMAL and stream is affected by ringer mode.
        // Unmute stream if previously muted by ringer mode and ringer mode
        // is RINGER_MODE_NORMAL or stream is not affected by ringer mode.
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
            if (isStreamMutedByRingerMode(streamType)) {
                if (!isStreamAffectedByRingerMode(streamType) ||
                    mRingerMode == AudioManager.RINGER_MODE_NORMAL) {
                    mStreamStates[streamType].mute(null, false);
                    mRingerModeMutedStreams &= ~(1 << streamType);
                }
            } else {
                if (isStreamAffectedByRingerMode(streamType) &&
                    mRingerMode != AudioManager.RINGER_MODE_NORMAL) {
                   mStreamStates[streamType].mute(null, true);
                   mRingerModeMutedStreams |= (1 << streamType);
               }
            }
        }

        // Post a persist ringer mode msg
        if (persist) {
            sendMsg(mAudioHandler, MSG_PERSIST_RINGER_MODE, SHARED_MSG,
                    SENDMSG_REPLACE, 0, 0, null, PERSIST_DELAY);
        }
    }

    /** @see AudioManager#shouldVibrate(int) */
    public boolean shouldVibrate(int vibrateType) {

        switch (getVibrateSetting(vibrateType)) {

            case AudioManager.VIBRATE_SETTING_ON:
                return mRingerMode != AudioManager.RINGER_MODE_SILENT;

            case AudioManager.VIBRATE_SETTING_ONLY_SILENT:
                return mRingerMode == AudioManager.RINGER_MODE_VIBRATE;

            case AudioManager.VIBRATE_SETTING_OFF:
                // return false, even for incoming calls
                return false;

            default:
                return false;
        }
    }

    /** @see AudioManager#getVibrateSetting(int) */
    public int getVibrateSetting(int vibrateType) {
        return (mVibrateSetting >> (vibrateType * 2)) & 3;
    }

    /** @see AudioManager#setVibrateSetting(int, int) */
    public void setVibrateSetting(int vibrateType, int vibrateSetting) {

        mVibrateSetting = getValueForVibrateSetting(mVibrateSetting, vibrateType, vibrateSetting);

        // Broadcast change
        broadcastVibrateSetting(vibrateType);

        // Post message to set ringer mode (it in turn will post a message
        // to persist)
        sendMsg(mAudioHandler, MSG_PERSIST_VIBRATE_SETTING, SHARED_MSG, SENDMSG_NOOP, 0, 0,
                null, 0);
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

        SetModeDeathHandler(IBinder cb) {
            mCb = cb;
            mPid = Binder.getCallingPid();
        }

        public void binderDied() {
            synchronized(mSetModeDeathHandlers) {
                Log.w(TAG, "setMode() client died");
                int index = mSetModeDeathHandlers.indexOf(this);
                if (index < 0) {
                    Log.w(TAG, "unregistered setMode() client died");
                } else {
                    mSetModeDeathHandlers.remove(this);
                    // If dead client was a the top of client list,
                    // apply next mode in the stack
                    if (index == 0) {
                        // mSetModeDeathHandlers is never empty as the initial entry
                        // created when AudioService starts is never removed
                        SetModeDeathHandler hdlr = mSetModeDeathHandlers.get(0);
                        int mode = hdlr.getMode();
                        if (AudioService.this.mMode != mode) {
                            if (AudioSystem.setPhoneState(mode) == AudioSystem.AUDIO_STATUS_OK) {
                                AudioService.this.mMode = mode;
                                if (mode != AudioSystem.MODE_NORMAL) {
                                    disconnectBluetoothSco(mCb);
                                }
                            }
                        }
                    }
                }
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

        synchronized (mSettingsLock) {
            if (mode == AudioSystem.MODE_CURRENT) {
                mode = mMode;
            }
            if (mode != mMode) {

                // automatically handle audio focus for mode changes
                handleFocusForCalls(mMode, mode, cb);

                if (AudioSystem.setPhoneState(mode) == AudioSystem.AUDIO_STATUS_OK) {
                    mMode = mode;

                    synchronized(mSetModeDeathHandlers) {
                        SetModeDeathHandler hdlr = null;
                        Iterator iter = mSetModeDeathHandlers.iterator();
                        while (iter.hasNext()) {
                            SetModeDeathHandler h = (SetModeDeathHandler)iter.next();
                            if (h.getBinder() == cb) {
                                hdlr = h;
                                // Remove from client list so that it is re-inserted at top of list
                                iter.remove();
                                break;
                            }
                        }
                        if (hdlr == null) {
                            hdlr = new SetModeDeathHandler(cb);
                            // cb is null when setMode() is called by AudioService constructor
                            if (cb != null) {
                                // Register for client death notification
                                try {
                                    cb.linkToDeath(hdlr, 0);
                                } catch (RemoteException e) {
                                    // Client has died!
                                    Log.w(TAG, "setMode() could not link to "+cb+" binder death");
                                }
                            }
                        }
                        // Last client to call setMode() is always at top of client list
                        // as required by SetModeDeathHandler.binderDied()
                        mSetModeDeathHandlers.add(0, hdlr);
                        hdlr.setMode(mode);
                    }

                    // when entering RINGTONE, IN_CALL or IN_COMMUNICATION mode, clear all
                    // SCO connections not started by the application changing the mode
                    if (mode != AudioSystem.MODE_NORMAL) {
                        disconnectBluetoothSco(cb);
                    }
                }
            }
            int streamType = getActiveStreamType(AudioManager.USE_DEFAULT_STREAM_TYPE);
            int index = mStreamStates[STREAM_VOLUME_ALIAS[streamType]].mIndex;
            setStreamVolumeInt(STREAM_VOLUME_ALIAS[streamType], index, true, false);
        }
    }

    /** pre-condition: oldMode != newMode */
    private void handleFocusForCalls(int oldMode, int newMode, IBinder cb) {
        // if ringing
        if (newMode == AudioSystem.MODE_RINGTONE) {
            // if not ringing silently
            int ringVolume = AudioService.this.getStreamVolume(AudioManager.STREAM_RING);
            if (ringVolume > 0) {
                // request audio focus for the communication focus entry
                requestAudioFocus(AudioManager.STREAM_RING,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, cb,
                        null /* IAudioFocusDispatcher allowed to be null only for this clientId */,
                        IN_VOICE_COMM_FOCUS_ID /*clientId*/,
                        "system");

            }
        }
        // if entering call
        else if ((newMode == AudioSystem.MODE_IN_CALL)
                || (newMode == AudioSystem.MODE_IN_COMMUNICATION)) {
            // request audio focus for the communication focus entry
            // (it's ok if focus was already requested during ringing)
            requestAudioFocus(AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, cb,
                    null /* IAudioFocusDispatcher allowed to be null only for this clientId */,
                    IN_VOICE_COMM_FOCUS_ID /*clientId*/,
                    "system");
        }
        // if exiting call
        else if (newMode == AudioSystem.MODE_NORMAL) {
            // abandon audio focus for communication focus entry
            abandonAudioFocus(null, IN_VOICE_COMM_FOCUS_ID);
        }
    }

    /** @see AudioManager#getMode() */
    public int getMode() {
        return mMode;
    }

    /** @see AudioManager#playSoundEffect(int) */
    public void playSoundEffect(int effectType) {
        sendMsg(mAudioHandler, MSG_PLAY_SOUND_EFFECT, SHARED_MSG, SENDMSG_NOOP,
                effectType, -1, null, 0);
    }

    /** @see AudioManager#playSoundEffect(int, float) */
    public void playSoundEffectVolume(int effectType, float volume) {
        loadSoundEffects();
        sendMsg(mAudioHandler, MSG_PLAY_SOUND_EFFECT, SHARED_MSG, SENDMSG_NOOP,
                effectType, (int) (volume * 1000), null, 0);
    }

    /**
     * Loads samples into the soundpool.
     * This method must be called at when sound effects are enabled
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
            if (mSoundPool == null) {
                Log.w(TAG, "loadSoundEffects() could not allocate sound pool");
                return false;
            }

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
        // restore ringer mode, ringer mode affected streams, mute affected streams and vibrate settings
        readPersistedSettings();

        // restore volume settings
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            VolumeStreamState streamState = mStreamStates[streamType];

            String settingName = System.VOLUME_SETTINGS[STREAM_VOLUME_ALIAS[streamType]];
            String lastAudibleSettingName = settingName + System.APPEND_FOR_LAST_AUDIBLE;
            int index = Settings.System.getInt(mContentResolver,
                                           settingName,
                                           AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            if (STREAM_VOLUME_ALIAS[streamType] != streamType) {
                index = rescaleIndex(index * 10, STREAM_VOLUME_ALIAS[streamType], streamType);
            } else {
                index *= 10;
            }
            streamState.mIndex = streamState.getValidIndex(index);

            index = (index + 5) / 10;
            index = Settings.System.getInt(mContentResolver,
                                            lastAudibleSettingName,
                                            (index > 0) ? index : AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            if (STREAM_VOLUME_ALIAS[streamType] != streamType) {
                index = rescaleIndex(index * 10, STREAM_VOLUME_ALIAS[streamType], streamType);
            } else {
                index *= 10;
            }
            streamState.mLastAudibleIndex = streamState.getValidIndex(index);

            // unmute stream that was muted but is not affect by mute anymore
            if (streamState.muteCount() != 0 && !isStreamAffectedByMute(streamType)) {
                int size = streamState.mDeathHandlers.size();
                for (int i = 0; i < size; i++) {
                    streamState.mDeathHandlers.get(i).mMuteCount = 1;
                    streamState.mDeathHandlers.get(i).mute(false);
                }
            }
            // apply stream volume
            if (streamState.muteCount() == 0) {
                setStreamVolumeIndex(streamType, streamState.mIndex);
            }
        }

        // apply new ringer mode
        setRingerModeInt(getRingerMode(), false);
    }

    /** @see AudioManager#setSpeakerphoneOn() */
    public void setSpeakerphoneOn(boolean on){
        if (!checkAudioSettingsPermission("setSpeakerphoneOn()")) {
            return;
        }
        mForcedUseForComm = on ? AudioSystem.FORCE_SPEAKER : AudioSystem.FORCE_NONE;

        sendMsg(mAudioHandler, MSG_SET_FORCE_USE, SHARED_MSG, SENDMSG_QUEUE,
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

        sendMsg(mAudioHandler, MSG_SET_FORCE_USE, SHARED_MSG, SENDMSG_QUEUE,
                AudioSystem.FOR_COMMUNICATION, mForcedUseForComm, null, 0);
        sendMsg(mAudioHandler, MSG_SET_FORCE_USE, SHARED_MSG, SENDMSG_QUEUE,
                AudioSystem.FOR_RECORD, mForcedUseForComm, null, 0);
    }

    /** @see AudioManager#isBluetoothScoOn() */
    public boolean isBluetoothScoOn() {
        return (mForcedUseForComm == AudioSystem.FORCE_BT_SCO);
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
                    if ((AudioService.this.mMode == AudioSystem.MODE_NORMAL ||
                            mSetModeDeathHandlers.get(0).getPid() == mCreatorPid) &&
                            mBluetoothHeadsetDevice != null &&
                            (mScoAudioState == SCO_STATE_INACTIVE ||
                             mScoAudioState == SCO_STATE_DEACTIVATE_REQ)) {
                        if (mScoAudioState == SCO_STATE_INACTIVE) {
                            if (mBluetoothHeadset != null) {
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
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED &&
                              mBluetoothHeadsetDevice != null &&
                              (mScoAudioState == SCO_STATE_ACTIVE_INTERNAL ||
                               mScoAudioState == SCO_STATE_ACTIVATE_REQ)) {
                    if (mScoAudioState == SCO_STATE_ACTIVE_INTERNAL) {
                        if (mBluetoothHeadset != null) {
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

    public void clearAllScoClients(IBinder exceptBinder, boolean stopSco) {
        synchronized(mScoClients) {
            ScoClient savedClient = null;
            int size = mScoClients.size();
            for (int i = 0; i < size; i++) {
                ScoClient cl = mScoClients.get(i);
                if (cl.getBinder() != exceptBinder) {
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
        sendMsg(mAudioHandler, MSG_BT_HEADSET_CNCT_FAILED, 0,
                SENDMSG_REPLACE, 0, 0, null, result ? BT_HEADSET_CNCT_TIMEOUT_MS : 0);
        return result;
    }

    private void disconnectBluetoothSco(IBinder exceptBinder) {
        synchronized(mScoClients) {
            checkScoAudioState();
            if (mScoAudioState == SCO_STATE_ACTIVE_EXTERNAL ||
                    mScoAudioState == SCO_STATE_DEACTIVATE_EXT_REQ) {
                if (mBluetoothHeadsetDevice != null) {
                    if (mBluetoothHeadset != null) {
                        if (!mBluetoothHeadset.stopVoiceRecognition(
                                mBluetoothHeadsetDevice)) {
                            sendMsg(mAudioHandler, MSG_BT_HEADSET_CNCT_FAILED, 0,
                                    SENDMSG_REPLACE, 0, 0, null, 0);
                        }
                    } else if (mScoAudioState == SCO_STATE_ACTIVE_EXTERNAL &&
                            getBluetoothHeadset()) {
                        mScoAudioState = SCO_STATE_DEACTIVATE_EXT_REQ;
                    }
                }
            } else {
                clearAllScoClients(exceptBinder, true);
            }
        }
    }

    private void resetBluetoothSco() {
        synchronized(mScoClients) {
            clearAllScoClients(null, false);
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
            mContext.sendStickyBroadcast(newIntent);
            mScoConnectionState = state;
        }
    }

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            synchronized (mScoClients) {
                // Discard timeout message
                mAudioHandler.removeMessages(MSG_BT_HEADSET_CNCT_FAILED);
                mBluetoothHeadset = (BluetoothHeadset) proxy;
                List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();
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
                        sendMsg(mAudioHandler, MSG_BT_HEADSET_CNCT_FAILED, 0,
                                SENDMSG_REPLACE, 0, 0, null, 0);
                    }
                }
            }
        }
        public void onServiceDisconnected(int profile) {
            synchronized (mScoClients) {
                mBluetoothHeadset = null;
            }
        }
    };

    ///////////////////////////////////////////////////////////////////////////
    // Internal methods
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Checks if the adjustment should change ringer mode instead of just
     * adjusting volume. If so, this will set the proper ringer mode and volume
     * indices on the stream states.
     */
    private boolean checkForRingerModeChange(int oldIndex, int direction) {
        boolean adjustVolumeIndex = true;
        int newRingerMode = mRingerMode;

        if (mRingerMode == AudioManager.RINGER_MODE_NORMAL) {
            // audible mode, at the bottom of the scale
            if ((direction == AudioManager.ADJUST_LOWER &&
                 mPrevVolDirection != AudioManager.ADJUST_LOWER) &&
                ((oldIndex + 5) / 10 == 0)) {
                // "silent mode", but which one?
                newRingerMode = System.getInt(mContentResolver, System.VIBRATE_IN_SILENT, 1) == 1
                    ? AudioManager.RINGER_MODE_VIBRATE
                    : AudioManager.RINGER_MODE_SILENT;
            }
        } else {
            if (direction == AudioManager.ADJUST_RAISE) {
                // exiting silent mode
                newRingerMode = AudioManager.RINGER_MODE_NORMAL;
            } else {
                // prevent last audible index to reach 0
                adjustVolumeIndex = false;
            }
        }

        if (newRingerMode != mRingerMode) {
            setRingerMode(newRingerMode);

            /*
             * If we are changing ringer modes, do not increment/decrement the
             * volume index. Instead, the handler for the message above will
             * take care of changing the index.
             */
            adjustVolumeIndex = false;
        }

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

    private void ensureValidStreamType(int streamType) {
        if (streamType < 0 || streamType >= mStreamStates.length) {
            throw new IllegalArgumentException("Bad stream type " + streamType);
        }
    }

    private int getActiveStreamType(int suggestedStreamType) {

        if (mVoiceCapable) {
            boolean isOffhook = false;
            try {
                ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
                if (phone != null) isOffhook = phone.isOffhook();
            } catch (RemoteException e) {
                Log.w(TAG, "Couldn't connect to phone service", e);
            }

            if (isOffhook || getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                if (AudioSystem.getForceUse(AudioSystem.FOR_COMMUNICATION)
                        == AudioSystem.FORCE_BT_SCO) {
                    // Log.v(TAG, "getActiveStreamType: Forcing STREAM_BLUETOOTH_SCO...");
                    return AudioSystem.STREAM_BLUETOOTH_SCO;
                } else {
                    // Log.v(TAG, "getActiveStreamType: Forcing STREAM_VOICE_CALL...");
                    return AudioSystem.STREAM_VOICE_CALL;
                }
            } else if (AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0)) {
                // Log.v(TAG, "getActiveStreamType: Forcing STREAM_MUSIC...");
                return AudioSystem.STREAM_MUSIC;
            } else if (suggestedStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                // Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING..."
                //        + " b/c USE_DEFAULT_STREAM_TYPE...");
                return AudioSystem.STREAM_RING;
            } else {
                // Log.v(TAG, "getActiveStreamType: Returning suggested type " + suggestedStreamType);
                return suggestedStreamType;
            }
        } else {
            if (getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                if (AudioSystem.getForceUse(AudioSystem.FOR_COMMUNICATION)
                        == AudioSystem.FORCE_BT_SCO) {
                    // Log.v(TAG, "getActiveStreamType: Forcing STREAM_BLUETOOTH_SCO...");
                    return AudioSystem.STREAM_BLUETOOTH_SCO;
                } else {
                    // Log.v(TAG, "getActiveStreamType: Forcing STREAM_VOICE_CALL...");
                    return AudioSystem.STREAM_VOICE_CALL;
                }
            } else if (AudioSystem.isStreamActive(AudioSystem.STREAM_NOTIFICATION,
                            NOTIFICATION_VOLUME_DELAY_MS) ||
                       AudioSystem.isStreamActive(AudioSystem.STREAM_RING,
                            NOTIFICATION_VOLUME_DELAY_MS)) {
                // Log.v(TAG, "getActiveStreamType: Forcing STREAM_NOTIFICATION...");
                return AudioSystem.STREAM_NOTIFICATION;
            } else if (AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0) ||
                       (suggestedStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE)) {
                // Log.v(TAG, "getActiveStreamType: Forcing STREAM_MUSIC "
                //        + " b/c USE_DEFAULT_STREAM_TYPE...");
                return AudioSystem.STREAM_MUSIC;
            } else {
                // Log.v(TAG, "getActiveStreamType: Returning suggested type " + suggestedStreamType);
                return suggestedStreamType;
            }
        }
    }

    private void broadcastRingerMode() {
        // Send sticky broadcast
        Intent broadcast = new Intent(AudioManager.RINGER_MODE_CHANGED_ACTION);
        broadcast.putExtra(AudioManager.EXTRA_RINGER_MODE, mRingerMode);
        broadcast.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        long origCallerIdentityToken = Binder.clearCallingIdentity();
        mContext.sendStickyBroadcast(broadcast);
        Binder.restoreCallingIdentity(origCallerIdentityToken);
    }

    private void broadcastVibrateSetting(int vibrateType) {
        // Send broadcast
        if (ActivityManagerNative.isSystemReady()) {
            Intent broadcast = new Intent(AudioManager.VIBRATE_SETTING_CHANGED_ACTION);
            broadcast.putExtra(AudioManager.EXTRA_VIBRATE_TYPE, vibrateType);
            broadcast.putExtra(AudioManager.EXTRA_VIBRATE_SETTING, getVibrateSetting(vibrateType));
            mContext.sendBroadcast(broadcast);
        }
    }

    // Message helper methods
    private static int getMsg(int baseMsg, int streamType) {
        return (baseMsg & 0xffff) | streamType << 16;
    }

    private static int getMsgBase(int msg) {
        return msg & 0xffff;
    }

    private static void sendMsg(Handler handler, int baseMsg, int streamType,
            int existingMsgPolicy, int arg1, int arg2, Object obj, int delay) {
        int msg = (streamType == SHARED_MSG) ? baseMsg : getMsg(baseMsg, streamType);

        if (existingMsgPolicy == SENDMSG_REPLACE) {
            handler.removeMessages(msg);
        } else if (existingMsgPolicy == SENDMSG_NOOP && handler.hasMessages(msg)) {
            return;
        }

        handler
                .sendMessageDelayed(handler.obtainMessage(msg, arg1, arg2, obj), delay);
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


    ///////////////////////////////////////////////////////////////////////////
    // Inner classes
    ///////////////////////////////////////////////////////////////////////////

    public class VolumeStreamState {
        private final int mStreamType;

        private String mVolumeIndexSettingName;
        private String mLastAudibleVolumeIndexSettingName;
        private int mIndexMax;
        private int mIndex;
        private int mLastAudibleIndex;
        private ArrayList<VolumeDeathHandler> mDeathHandlers; //handles mute/solo requests client death

        private VolumeStreamState(String settingName, int streamType) {

            setVolumeIndexSettingName(settingName);

            mStreamType = streamType;

            final ContentResolver cr = mContentResolver;
            mIndexMax = MAX_STREAM_VOLUME[streamType];
            mIndex = Settings.System.getInt(cr,
                                            mVolumeIndexSettingName,
                                            AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            mLastAudibleIndex = Settings.System.getInt(cr,
                                                       mLastAudibleVolumeIndexSettingName,
                                                       (mIndex > 0) ? mIndex : AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            AudioSystem.initStreamVolume(streamType, 0, mIndexMax);
            mIndexMax *= 10;
            mIndex = getValidIndex(10 * mIndex);
            mLastAudibleIndex = getValidIndex(10 * mLastAudibleIndex);
            setStreamVolumeIndex(streamType, mIndex);
            mDeathHandlers = new ArrayList<VolumeDeathHandler>();
        }

        public void setVolumeIndexSettingName(String settingName) {
            mVolumeIndexSettingName = settingName;
            mLastAudibleVolumeIndexSettingName = settingName + System.APPEND_FOR_LAST_AUDIBLE;
        }

        public boolean adjustIndex(int deltaIndex) {
            return setIndex(mIndex + deltaIndex * 10, true);
        }

        public boolean setIndex(int index, boolean lastAudible) {
            int oldIndex = mIndex;
            mIndex = getValidIndex(index);

            if (oldIndex != mIndex) {
                if (lastAudible) {
                    mLastAudibleIndex = mIndex;
                }
                // Apply change to all streams using this one as alias
                int numStreamTypes = AudioSystem.getNumStreamTypes();
                for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                    if (streamType != mStreamType && STREAM_VOLUME_ALIAS[streamType] == mStreamType) {
                        mStreamStates[streamType].setIndex(rescaleIndex(mIndex, mStreamType, streamType), lastAudible);
                    }
                }
                return true;
            } else {
                return false;
            }
        }

        public void setLastAudibleIndex(int index) {
            mLastAudibleIndex = getValidIndex(index);
        }

        public void adjustLastAudibleIndex(int deltaIndex) {
            setLastAudibleIndex(mLastAudibleIndex + deltaIndex * 10);
        }

        public int getMaxIndex() {
            return mIndexMax;
        }

        public void mute(IBinder cb, boolean state) {
            VolumeDeathHandler handler = getDeathHandler(cb, state);
            if (handler == null) {
                Log.e(TAG, "Could not get client death handler for stream: "+mStreamType);
                return;
            }
            handler.mute(state);
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

            public void mute(boolean state) {
                synchronized(mDeathHandlers) {
                    if (state) {
                        if (mMuteCount == 0) {
                            // Register for client death notification
                            try {
                                // mICallback can be 0 if muted by AudioService
                                if (mICallback != null) {
                                    mICallback.linkToDeath(this, 0);
                                }
                                mDeathHandlers.add(this);
                                // If the stream is not yet muted by any client, set lvel to 0
                                if (muteCount() == 0) {
                                    setIndex(0, false);
                                    sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, mStreamType, SENDMSG_NOOP, 0, 0,
                                            VolumeStreamState.this, 0);
                                }
                            } catch (RemoteException e) {
                                // Client has died!
                                binderDied();
                                mDeathHandlers.notify();
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
                                // Unregistr from client death notification
                                mDeathHandlers.remove(this);
                                // mICallback can be 0 if muted by AudioService
                                if (mICallback != null) {
                                    mICallback.unlinkToDeath(this, 0);
                                }
                                if (muteCount() == 0) {
                                    // If the stream is not muted any more, restore it's volume if
                                    // ringer mode allows it
                                    if (!isStreamAffectedByRingerMode(mStreamType) || mRingerMode == AudioManager.RINGER_MODE_NORMAL) {
                                        setIndex(mLastAudibleIndex, false);
                                        sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, mStreamType, SENDMSG_NOOP, 0, 0,
                                                VolumeStreamState.this, 0);
                                    }
                                }
                            }
                        }
                    }
                    mDeathHandlers.notify();
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

        private int muteCount() {
            int count = 0;
            int size = mDeathHandlers.size();
            for (int i = 0; i < size; i++) {
                count += mDeathHandlers.get(i).mMuteCount;
            }
            return count;
        }

        private VolumeDeathHandler getDeathHandler(IBinder cb, boolean state) {
            synchronized(mDeathHandlers) {
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

        private void setSystemVolume(VolumeStreamState streamState) {

            // Adjust volume
            setStreamVolumeIndex(streamState.mStreamType, streamState.mIndex);

            // Apply change to all streams using this one as alias
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType &&
                    STREAM_VOLUME_ALIAS[streamType] == streamState.mStreamType) {
                    setStreamVolumeIndex(streamType, mStreamStates[streamType].mIndex);
                }
            }

            // Post a persist volume msg
            sendMsg(mAudioHandler, MSG_PERSIST_VOLUME, streamState.mStreamType,
                    SENDMSG_REPLACE, 1, 1, streamState, PERSIST_DELAY);
        }

        private void persistVolume(VolumeStreamState streamState, boolean current, boolean lastAudible) {
            if (current) {
                System.putInt(mContentResolver, streamState.mVolumeIndexSettingName,
                              (streamState.mIndex + 5)/ 10);
            }
            if (lastAudible) {
                System.putInt(mContentResolver, streamState.mLastAudibleVolumeIndexSettingName,
                    (streamState.mLastAudibleIndex + 5) / 10);
            }
        }

        private void persistRingerMode() {
            System.putInt(mContentResolver, System.MODE_RINGER, mRingerMode);
        }

        private void persistVibrateSetting() {
            System.putInt(mContentResolver, System.VIBRATE_ON, mVibrateSetting);
        }

        private void playSoundEffect(int effectType, int volume) {
            synchronized (mSoundEffectsLock) {
                if (mSoundPool == null) {
                    return;
                }
                float volFloat;
                // use default if volume is not specified by caller
                if (volume < 0) {
                    volFloat = (float)Math.pow(10, SOUND_EFFECT_VOLUME_DB/20);
                } else {
                    volFloat = (float) volume / 1000.0f;
                }

                if (SOUND_EFFECT_FILES_MAP[effectType][1] > 0) {
                    mSoundPool.play(SOUND_EFFECT_FILES_MAP[effectType][1], volFloat, volFloat, 0, 0, 1.0f);
                } else {
                    MediaPlayer mediaPlayer = new MediaPlayer();
                    if (mediaPlayer != null) {
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
        }

        private void persistMediaButtonReceiver(ComponentName receiver) {
            Settings.System.putString(mContentResolver, Settings.System.MEDIA_BUTTON_RECEIVER,
                    receiver == null ? "" : receiver.flattenToString());
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
            int baseMsgWhat = getMsgBase(msg.what);

            switch (baseMsgWhat) {

                case MSG_SET_SYSTEM_VOLUME:
                    setSystemVolume((VolumeStreamState) msg.obj);
                    break;

                case MSG_PERSIST_VOLUME:
                    persistVolume((VolumeStreamState) msg.obj, (msg.arg1 != 0), (msg.arg2 != 0));
                    break;

                case MSG_PERSIST_RINGER_MODE:
                    persistRingerMode();
                    break;

                case MSG_PERSIST_VIBRATE_SETTING:
                    persistVibrateSetting();
                    break;

                case MSG_MEDIA_SERVER_DIED:
                    if (!mMediaServerOk) {
                        Log.e(TAG, "Media server died.");
                        // Force creation of new IAudioFlinger interface so that we are notified
                        // when new media_server process is back to life.
                        AudioSystem.setErrorCallback(mAudioSystemCallback);
                        sendMsg(mAudioHandler, MSG_MEDIA_SERVER_DIED, SHARED_MSG, SENDMSG_NOOP, 0, 0,
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
                    Set set = mConnectedDevices.entrySet();
                    Iterator i = set.iterator();
                    while(i.hasNext()){
                        Map.Entry device = (Map.Entry)i.next();
                        AudioSystem.setDeviceConnectionState(((Integer)device.getKey()).intValue(),
                                                             AudioSystem.DEVICE_STATE_AVAILABLE,
                                                             (String)device.getValue());
                    }

                    // Restore call state
                    AudioSystem.setPhoneState(mMode);

                    // Restore forced usage for communcations and record
                    AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, mForcedUseForComm);
                    AudioSystem.setForceUse(AudioSystem.FOR_RECORD, mForcedUseForComm);

                    // Restore stream volumes
                    int numStreamTypes = AudioSystem.getNumStreamTypes();
                    for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                        int index;
                        VolumeStreamState streamState = mStreamStates[streamType];
                        AudioSystem.initStreamVolume(streamType, 0, (streamState.mIndexMax + 5) / 10);
                        if (streamState.muteCount() == 0) {
                            index = streamState.mIndex;
                        } else {
                            index = 0;
                        }
                        setStreamVolumeIndex(streamType, index);
                    }

                    // Restore ringer mode
                    setRingerModeInt(getRingerMode(), false);

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
                    makeA2dpDeviceUnavailableNow( (String) msg.obj );
                    break;

                case MSG_SET_FORCE_USE:
                    setForceUse(msg.arg1, msg.arg2);
                    break;

                case MSG_PERSIST_MEDIABUTTONRECEIVER:
                    persistMediaButtonReceiver( (ComponentName) msg.obj );
                    break;

                case MSG_RCDISPLAY_CLEAR:
                    // TODO remove log before release
                    Log.i(TAG, "Clear remote control display");
                    Intent clearIntent = new Intent(AudioManager.REMOTE_CONTROL_CLIENT_CHANGED);
                    // no extra means no IRemoteControlClient, which is a request to clear
                    clearIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    mContext.sendBroadcast(clearIntent);
                    break;

                case MSG_RCDISPLAY_UPDATE:
                    synchronized(mCurrentRcLock) {
                        if ((mCurrentRcClient == null) ||
                                (!mCurrentRcClient.equals((IRemoteControlClient)msg.obj))) {
                            // the remote control display owner has changed between the
                            // the message to update the display was sent, and the time it
                            // gets to be processed (now)
                        } else {
                            mCurrentRcClientGen++;
                            // TODO remove log before release
                            Log.i(TAG, "Display/update remote control ");
                            Intent rcClientIntent = new Intent(
                                    AudioManager.REMOTE_CONTROL_CLIENT_CHANGED);
                            rcClientIntent.putExtra(AudioManager.EXTRA_REMOTE_CONTROL_CLIENT,
                                    mCurrentRcClientGen);
                            rcClientIntent.putExtra(
                                    AudioManager.EXTRA_REMOTE_CONTROL_CLIENT_INFO_CHANGED,
                                    msg.arg1);
                            rcClientIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                            mContext.sendBroadcast(rcClientIntent);
                        }
                    }
                    break;

                case MSG_BT_HEADSET_CNCT_FAILED:
                    resetBluetoothSco();
                    break;
            }
        }
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver() {
            super(new Handler());
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.MODE_RINGER_STREAMS_AFFECTED), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            synchronized (mSettingsLock) {
                int ringerModeAffectedStreams = Settings.System.getInt(mContentResolver,
                       Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                       ((1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_NOTIFICATION)|
                       (1 << AudioSystem.STREAM_SYSTEM)|(1 << AudioSystem.STREAM_SYSTEM_ENFORCED)));
                if (mVoiceCapable) {
                    ringerModeAffectedStreams &= ~(1 << AudioSystem.STREAM_MUSIC);
                } else {
                    ringerModeAffectedStreams |= (1 << AudioSystem.STREAM_MUSIC);
                }
                if (ringerModeAffectedStreams != mRingerModeAffectedStreams) {
                    /*
                     * Ensure all stream types that should be affected by ringer mode
                     * are in the proper state.
                     */
                    mRingerModeAffectedStreams = ringerModeAffectedStreams;
                    setRingerModeInt(getRingerMode(), false);
                }
            }
        }
    }

    private void makeA2dpDeviceAvailable(String address) {
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_AVAILABLE,
                address);
        // Reset A2DP suspend state each time a new sink is connected
        AudioSystem.setParameters("A2dpSuspended=false");
        mConnectedDevices.put( new Integer(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP),
                address);
    }

    private void makeA2dpDeviceUnavailableNow(String address) {
        Intent noisyIntent = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        mContext.sendBroadcast(noisyIntent);
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                address);
        mConnectedDevices.remove(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
    }

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

    private void cancelA2dpDeviceTimeout() {
        mAudioHandler.removeMessages(MSG_BTA2DP_DOCK_TIMEOUT);
    }

    private boolean hasScheduledA2dpDockTimeout() {
        return mAudioHandler.hasMessages(MSG_BTA2DP_DOCK_TIMEOUT);
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
                AudioSystem.setForceUse(AudioSystem.FOR_DOCK, config);
            } else if (action.equals(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                               BluetoothProfile.STATE_DISCONNECTED);
                BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String address = btDevice.getAddress();
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
                }
            } else if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                               BluetoothProfile.STATE_DISCONNECTED);
                int device = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO;
                BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String address = null;
                if (btDevice != null) {
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
                }

                boolean isConnected = (mConnectedDevices.containsKey(device) &&
                                       mConnectedDevices.get(device).equals(address));

                synchronized (mScoClients) {
                    if (isConnected && state != BluetoothProfile.STATE_CONNECTED) {
                        AudioSystem.setDeviceConnectionState(device,
                                                             AudioSystem.DEVICE_STATE_UNAVAILABLE,
                                                             address);
                        mConnectedDevices.remove(device);
                        mBluetoothHeadsetDevice = null;
                        resetBluetoothSco();
                    } else if (!isConnected && state == BluetoothProfile.STATE_CONNECTED) {
                        AudioSystem.setDeviceConnectionState(device,
                                                             AudioSystem.DEVICE_STATE_AVAILABLE,
                                                             address);
                        mConnectedDevices.put(new Integer(device), address);
                        mBluetoothHeadsetDevice = btDevice;
                    }
                }
            } else if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                int state = intent.getIntExtra("state", 0);
                int microphone = intent.getIntExtra("microphone", 0);

                if (microphone != 0) {
                    boolean isConnected =
                        mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_WIRED_HEADSET);
                    if (state == 0 && isConnected) {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADSET,
                                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                                "");
                        mConnectedDevices.remove(AudioSystem.DEVICE_OUT_WIRED_HEADSET);
                    } else if (state == 1 && !isConnected)  {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADSET,
                                AudioSystem.DEVICE_STATE_AVAILABLE,
                                "");
                        mConnectedDevices.put(
                                new Integer(AudioSystem.DEVICE_OUT_WIRED_HEADSET), "");
                    }
                } else {
                    boolean isConnected =
                        mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
                    if (state == 0 && isConnected) {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE,
                                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                                "");
                        mConnectedDevices.remove(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
                    } else if (state == 1 && !isConnected)  {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE,
                                AudioSystem.DEVICE_STATE_AVAILABLE,
                                "");
                        mConnectedDevices.put(
                                new Integer(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE), "");
                    }
                }
            } else if (action.equals(Intent.ACTION_USB_ANLG_HEADSET_PLUG)) {
                int state = intent.getIntExtra("state", 0);
                Log.v(TAG, "Broadcast Receiver: Got ACTION_USB_ANLG_HEADSET_PLUG, state = "+state);
                boolean isConnected =
                    mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET);
                if (state == 0 && isConnected) {
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET,
                                                         AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
                    mConnectedDevices.remove(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET);
                } else if (state == 1 && !isConnected)  {
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET,
                                                         AudioSystem.DEVICE_STATE_AVAILABLE, "");
                    mConnectedDevices.put(
                            new Integer(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET), "");
                }
            } else if (action.equals(Intent.ACTION_HDMI_AUDIO_PLUG)) {
                int state = intent.getIntExtra("state", 0);
                Log.v(TAG, "Broadcast Receiver: Got ACTION_HDMI_AUDIO_PLUG, state = "+state);
                boolean isConnected =
                    mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_AUX_DIGITAL);
                if (state == 0 && isConnected) {
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_AUX_DIGITAL,
                                                         AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
                    mConnectedDevices.remove(AudioSystem.DEVICE_OUT_AUX_DIGITAL);
                } else if (state == 1 && !isConnected)  {
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_AUX_DIGITAL,
                                                         AudioSystem.DEVICE_STATE_AVAILABLE, "");
                    mConnectedDevices.put( new Integer(AudioSystem.DEVICE_OUT_AUX_DIGITAL), "");
                }
            } else if (action.equals(Intent.ACTION_USB_DGTL_HEADSET_PLUG)) {
                int state = intent.getIntExtra("state", 0);
                Log.v(TAG, "Broadcast Receiver: Got ACTION_USB_DGTL_HEADSET_PLUG, state = "+state);
                boolean isConnected =
                    mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET);
                if (state == 0 && isConnected) {
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET,
                                                         AudioSystem.DEVICE_STATE_UNAVAILABLE, "");
                    mConnectedDevices.remove(AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET);
                } else if (state == 1 && !isConnected)  {
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET,
                                                         AudioSystem.DEVICE_STATE_AVAILABLE, "");
                    mConnectedDevices.put(
                            new Integer(AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET), "");
                }
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                boolean broadcast = false;
                int state = AudioManager.SCO_AUDIO_STATE_ERROR;
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
                        state = AudioManager.SCO_AUDIO_STATE_CONNECTED;
                        if (mScoAudioState != SCO_STATE_ACTIVE_INTERNAL &&
                            mScoAudioState != SCO_STATE_DEACTIVATE_REQ &&
                            mScoAudioState != SCO_STATE_DEACTIVATE_EXT_REQ) {
                            mScoAudioState = SCO_STATE_ACTIVE_EXTERNAL;
                        }
                        break;
                    case BluetoothHeadset.STATE_AUDIO_DISCONNECTED:
                        state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
                        mScoAudioState = SCO_STATE_INACTIVE;
                        clearAllScoClients(null, false);
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
                    broadcastScoConnectionState(state);
                    //FIXME: this is to maintain compatibility with deprecated intent
                    // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
                    Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
                    newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, state);
                    mContext.sendStickyBroadcast(newIntent);
                }
            } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mBootCompleted = true;
                sendMsg(mAudioHandler, MSG_LOAD_SOUND_EFFECTS, SHARED_MSG, SENDMSG_NOOP,
                        0, 0, null, 0);

                mScoConnectionState = AudioManager.SCO_AUDIO_STATE_ERROR;
                resetBluetoothSco();
                getBluetoothHeadset();
                //FIXME: this is to maintain compatibility with deprecated intent
                // AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED. Remove when appropriate.
                Intent newIntent = new Intent(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED);
                newIntent.putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED);
                mContext.sendStickyBroadcast(newIntent);
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // a package is being removed, not replaced
                    String packageName = intent.getData().getSchemeSpecificPart();
                    if (packageName != null) {
                        removeMediaButtonReceiverForPackage(packageName);
                    }
                }
            }
        }
    }

    //==========================================================================================
    // AudioFocus
    //==========================================================================================

    /* constant to identify focus stack entry that is used to hold the focus while the phone
     * is ringing or during a call
     */
    private final static String IN_VOICE_COMM_FOCUS_ID = "AudioFocus_For_Phone_Ring_And_Calls";

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
            if (mSourceRef != null && mHandler != null) {
                mSourceRef.unlinkToDeath(mHandler, 0);
            }
        }
    }

    private Stack<FocusStackEntry> mFocusStack = new Stack<FocusStackEntry>();

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
                pw.println("     source:" + fse.mSourceRef + " -- client: " + fse.mClientId
                        + " -- duration: " + fse.mFocusChangeType
                        + " -- uid: " + fse.mCallingUid);
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
        // is the current top of the focus stack abandoning focus? (because of death or request)
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
                    checkUpdateRemoteControlDisplay(RC_INFO_ALL);
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
     * Remove focus listeners from the focus stack for a particular client.
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
            }
        }
        if (isTopOfStackForClientToRemove) {
            // we removed an entry at the top of the stack:
            //  notify the new top of the stack it gained focus.
            notifyTopOfAudioFocusStack();
            // there's a new top of the stack, let the remote control know
            synchronized(mRCStack) {
                checkUpdateRemoteControlDisplay(RC_INFO_ALL);
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

            if (!mFocusStack.empty() && mFocusStack.peek().mClientId.equals(clientId)) {
                // if focus is already owned by this client and the reason for acquiring the focus
                // hasn't changed, don't do anything
                if (mFocusStack.peek().mFocusChangeType == focusChangeHint) {
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
                // the reason for the audio focus request has changed: remove the current top of
                // stack and respond as if we had a new focus owner
                mFocusStack.pop();
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
            removeFocusStackEntry(clientId, false);

            // handle the potential premature death of the new holder of the focus
            // (premature death == death before abandoning focus)
            // Register for client death notification
            AudioFocusDeathHandler afdh = new AudioFocusDeathHandler(cb);
            try {
                cb.linkToDeath(afdh, 0);
            } catch (RemoteException e) {
                // client has already died!
                Log.w(TAG, "AudioFocus  requestAudioFocus() could not link to "+cb+" binder death");
            }

            // push focus requester at the top of the audio focus stack
            mFocusStack.push(new FocusStackEntry(mainStreamType, focusChangeHint, fd, cb,
                    clientId, afdh, callingPackageName, Binder.getCallingUid()));

            // there's a new top of the stack, let the remote control know
            synchronized(mRCStack) {
                checkUpdateRemoteControlDisplay(RC_INFO_ALL);
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
    /**
     * Receiver for media button intents. Handles the dispatching of the media button event
     * to one of the registered listeners, or if there was none, resumes the intent broadcast
     * to the rest of the system.
     */
    private class MediaButtonBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                return;
            }
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (event != null) {
                // if in a call or ringing, do not break the current phone app behavior
                // TODO modify this to let the phone app specifically get the RC focus
                //      add modify the phone app to take advantage of the new API
                synchronized(mRingingLock) {
                    if (mIsRinging || (getMode() == AudioSystem.MODE_IN_CALL) ||
                            (getMode() == AudioSystem.MODE_IN_COMMUNICATION) ||
                            (getMode() == AudioSystem.MODE_RINGTONE) ) {
                        return;
                    }
                }
                synchronized(mRCStack) {
                    if (!mRCStack.empty()) {
                        // create a new intent specifically aimed at the current registered listener
                        Intent targetedIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                        targetedIntent.putExtras(intent.getExtras());
                        targetedIntent.setComponent(mRCStack.peek().mReceiverComponent);
                        // trap the current broadcast
                        abortBroadcast();
                        //Log.v(TAG, " Sending intent" + targetedIntent);
                        context.sendBroadcast(targetedIntent, null);
                    }
                }
            }
        }
    }

    private final Object mCurrentRcLock = new Object();
    /**
     * The one remote control client to be polled for display information.
     * This object may be null.
     * Access protected by mCurrentRcLock.
     */
    private IRemoteControlClient mCurrentRcClient = null;

    private final static int RC_INFO_NONE = 0;
    private final static int RC_INFO_ALL =
        AudioManager.RemoteControlParameters.FLAG_INFORMATION_CHANGED_ALBUM_ART |
        AudioManager.RemoteControlParameters.FLAG_INFORMATION_CHANGED_KEY_MEDIA |
        AudioManager.RemoteControlParameters.FLAG_INFORMATION_CHANGED_METADATA |
        AudioManager.RemoteControlParameters.FLAG_INFORMATION_CHANGED_PLAYSTATE;

    /**
     * A monotonically increasing generation counter for mCurrentRcClient.
     * Only accessed with a lock on mCurrentRcLock.
     * No value wrap-around issues as we only act on equal values.
     */
    private int mCurrentRcClientGen = 0;

    /**
     * Returns the current remote control client.
     * @param rcClientId the counter value that matches the extra
     *     {@link AudioManager#EXTRA_REMOTE_CONTROL_CLIENT} in the
     *     {@link AudioManager#REMOTE_CONTROL_CLIENT_CHANGED} event
     * @return the current IRemoteControlClient from which information to display on the remote
     *     control can be retrieved, or null if rcClientId doesn't match the current generation
     *     counter.
     */
    public IRemoteControlClient getRemoteControlClient(int rcClientId) {
        synchronized(mCurrentRcLock) {
            if (rcClientId == mCurrentRcClientGen) {
                return mCurrentRcClient;
            } else {
                return null;
            }
        }
    }

    /**
     * Inner class to monitor remote control client deaths, and remove the client for the
     * remote control stack if necessary.
     */
    private class RcClientDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death
        private ComponentName mRcEventReceiver;

        RcClientDeathHandler(IBinder cb, ComponentName eventReceiver) {
            mCb = cb;
            mRcEventReceiver = eventReceiver;
        }

        public void binderDied() {
            Log.w(TAG, "  RemoteControlClient died");
            // remote control client died, make sure the displays don't use it anymore
            //  by setting its remote control client to null
            registerRemoteControlClient(mRcEventReceiver, null, null/*ignored*/);
        }

        public IBinder getBinder() {
            return mCb;
        }
    }

    private static class RemoteControlStackEntry {
        /** the target for the ACTION_MEDIA_BUTTON events */
        public ComponentName mReceiverComponent;// always non null
        public String mCallingPackageName;
        public int mCallingUid;

        /** provides access to the information to display on the remote control */
        public IRemoteControlClient mRcClient;
        public RcClientDeathHandler mRcClientDeathHandler;

        public RemoteControlStackEntry(ComponentName r) {
            mReceiverComponent = r;
            mCallingUid = -1;
            mRcClient = null;
        }

        public void unlinkToRcClientDeath() {
            if ((mRcClientDeathHandler != null) && (mRcClientDeathHandler.mCb != null)) {
                try {
                    mRcClientDeathHandler.mCb.unlinkToDeath(mRcClientDeathHandler, 0);
                } catch (java.util.NoSuchElementException e) {
                    // not much we can do here
                    Log.e(TAG, "Encountered " + e + " in unlinkToRcClientDeath()");
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     *  The stack of remote control event receivers.
     *  Code sections and methods that modify the remote control event receiver stack are
     *  synchronized on mRCStack, but also BEFORE on mFocusLock as any change in either
     *  stack, audio focus or RC, can lead to a change in the remote control display
     */
    private Stack<RemoteControlStackEntry> mRCStack = new Stack<RemoteControlStackEntry>();

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
                pw.println("     receiver: " + rcse.mReceiverComponent +
                        "  -- client: " + rcse.mRcClient +
                        "  -- uid: " + rcse.mCallingUid);
            }
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
                    }
                }
                if (mRCStack.empty()) {
                    // no saved media button receiver
                    mAudioHandler.sendMessage(
                            mAudioHandler.obtainMessage(MSG_PERSIST_MEDIABUTTONRECEIVER, 0, 0,
                                    null));
                    return;
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
     * Restore remote control receiver from the system settings
     */
    private void restoreMediaButtonReceiver() {
        String receiverName = Settings.System.getString(mContentResolver,
                Settings.System.MEDIA_BUTTON_RECEIVER);
        if ((null != receiverName) && !receiverName.isEmpty()) {
            ComponentName receiverComponentName = ComponentName.unflattenFromString(receiverName);
            registerMediaButtonEventReceiver(receiverComponentName);
        }
        // upon restoring (e.g. after boot), do we want to refresh all remotes?
    }

    /**
     * Helper function:
     * Set the new remote control receiver at the top of the RC focus stack
     */
    private void pushMediaButtonReceiver(ComponentName newReceiver) {
        // already at top of stack?
        if (!mRCStack.empty() && mRCStack.peek().mReceiverComponent.equals(newReceiver)) {
            return;
        }
        Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
        RemoteControlStackEntry rcse = null;
        boolean wasInsideStack = false;
        while(stackIterator.hasNext()) {
            rcse = (RemoteControlStackEntry)stackIterator.next();
            if(rcse.mReceiverComponent.equals(newReceiver)) {
                wasInsideStack = true;
                stackIterator.remove();
                break;
            }
        }
        if (!wasInsideStack) {
            rcse = new RemoteControlStackEntry(newReceiver);
        }
        mRCStack.push(rcse);

        // post message to persist the default media button receiver
        mAudioHandler.sendMessage( mAudioHandler.obtainMessage(
                MSG_PERSIST_MEDIABUTTONRECEIVER, 0, 0, newReceiver/*obj*/) );
    }

    /**
     * Helper function:
     * Remove the remote control receiver from the RC focus stack
     */
    private void removeMediaButtonReceiver(ComponentName newReceiver) {
        Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
        while(stackIterator.hasNext()) {
            RemoteControlStackEntry rcse = (RemoteControlStackEntry)stackIterator.next();
            if(rcse.mReceiverComponent.equals(newReceiver)) {
                stackIterator.remove();
                break;
            }
        }
    }

    /**
     * Helper function:
     * Called synchronized on mRCStack
     */
    private boolean isCurrentRcController(ComponentName eventReceiver) {
        if (!mRCStack.empty() && mRCStack.peek().mReceiverComponent.equals(eventReceiver)) {
            return true;
        }
        return false;
    }

    /**
     * Helper function:
     * Called synchronized on mRCStack
     */
    private void clearRemoteControlDisplay() {
        synchronized(mCurrentRcLock) {
            mCurrentRcClient = null;
        }
        mAudioHandler.sendMessage( mAudioHandler.obtainMessage(MSG_RCDISPLAY_CLEAR) );
    }

    /**
     * Helper function:
     * Called synchronized on mRCStack
     * mRCStack.empty() is false
     */
    private void updateRemoteControlDisplay(int infoChangedFlags) {
        RemoteControlStackEntry rcse = mRCStack.peek();
        int infoFlagsAboutToBeUsed = infoChangedFlags;
        // this is where we enforce opt-in for information display on the remote controls
        //   with the new AudioManager.registerRemoteControlClient() API
        if (rcse.mRcClient == null) {
            //Log.w(TAG, "Can't update remote control display with null remote control client");
            clearRemoteControlDisplay();
            return;
        }
        synchronized(mCurrentRcLock) {
            if (!rcse.mRcClient.equals(mCurrentRcClient)) {
                // new RC client, assume every type of information shall be queried
                infoFlagsAboutToBeUsed = RC_INFO_ALL;
            }
            mCurrentRcClient = rcse.mRcClient;
        }
        mAudioHandler.sendMessage( mAudioHandler.obtainMessage(MSG_RCDISPLAY_UPDATE,
                infoFlagsAboutToBeUsed /* arg1 */, 0, rcse.mRcClient /* obj */) );
    }

    /**
     * Helper function:
     * Called synchronized on mFocusLock, then mRCStack
     * Check whether the remote control display should be updated, triggers the update if required
     * @param infoChangedFlags the flags corresponding to the remote control client information
     *     that has changed, if applicable (checking for the update conditions might trigger a
     *     clear, rather than an update event).
     */
    private void checkUpdateRemoteControlDisplay(int infoChangedFlags) {
        // determine whether the remote control display should be refreshed
        // if either stack is empty, there is a mismatch, so clear the RC display
        if (mRCStack.isEmpty() || mFocusStack.isEmpty()) {
            clearRemoteControlDisplay();
            return;
        }
        // if the top of the two stacks belong to different packages, there is a mismatch, clear
        if ((mRCStack.peek().mCallingPackageName != null)
                && (mFocusStack.peek().mPackageName != null)
                && !(mRCStack.peek().mCallingPackageName.compareTo(
                        mFocusStack.peek().mPackageName) == 0)) {
            clearRemoteControlDisplay();
            return;
        }
        // if the audio focus didn't originate from the same Uid as the one in which the remote
        //   control information will be retrieved, clear
        if (mRCStack.peek().mCallingUid != mFocusStack.peek().mCallingUid) {
            clearRemoteControlDisplay();
            return;
        }
        // refresh conditions were verified: update the remote controls
        updateRemoteControlDisplay(infoChangedFlags);
    }

    /** see AudioManager.registerMediaButtonEventReceiver(ComponentName eventReceiver) */
    public void registerMediaButtonEventReceiver(ComponentName eventReceiver) {
        Log.i(TAG, "  Remote Control   registerMediaButtonEventReceiver() for " + eventReceiver);

        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                pushMediaButtonReceiver(eventReceiver);
                // new RC client, assume every type of information shall be queried
                checkUpdateRemoteControlDisplay(RC_INFO_ALL);
            }
        }
    }

    /** see AudioManager.unregisterMediaButtonEventReceiver(ComponentName eventReceiver) */
    public void unregisterMediaButtonEventReceiver(ComponentName eventReceiver) {
        Log.i(TAG, "  Remote Control   unregisterMediaButtonEventReceiver() for " + eventReceiver);

        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                boolean topOfStackWillChange = isCurrentRcController(eventReceiver);
                removeMediaButtonReceiver(eventReceiver);
                if (topOfStackWillChange) {
                    // current RC client will change, assume every type of info needs to be queried
                    checkUpdateRemoteControlDisplay(RC_INFO_ALL);
                }
            }
        }
    }

    /** see AudioManager.registerRemoteControlClient(ComponentName eventReceiver, ...) */
    public void registerRemoteControlClient(ComponentName eventReceiver,
            IRemoteControlClient rcClient, String callingPackageName) {
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                // store the new display information
                Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                while(stackIterator.hasNext()) {
                    RemoteControlStackEntry rcse = stackIterator.next();
                    if(rcse.mReceiverComponent.equals(eventReceiver)) {
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
                            break;
                        }
                        // monitor the new client's death
                        IBinder b = rcClient.asBinder();
                        RcClientDeathHandler rcdh =
                                new RcClientDeathHandler(b, rcse.mReceiverComponent);
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
                if (isCurrentRcController(eventReceiver)) {
                    checkUpdateRemoteControlDisplay(RC_INFO_ALL);
                }
            }
        }
    }

    /** see AudioManager.notifyRemoteControlInformationChanged(ComponentName er, int infoFlag) */
    public void notifyRemoteControlInformationChanged(ComponentName eventReceiver, int infoFlag) {
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                // only refresh if the eventReceiver is at the top of the stack
                if (isCurrentRcController(eventReceiver)) {
                    checkUpdateRemoteControlDisplay(infoFlag);
                }
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // TODO probably a lot more to do here than just the audio focus and remote control stacks
        dumpFocusStack(pw);
        dumpRCStack(pw);
    }


}
