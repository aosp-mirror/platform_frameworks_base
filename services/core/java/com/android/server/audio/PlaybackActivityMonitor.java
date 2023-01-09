/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.media.AudioPlaybackConfiguration.EXTRA_PLAYER_EVENT_MUTE;
import static android.media.AudioPlaybackConfiguration.MUTED_BY_APP_OPS;
import static android.media.AudioPlaybackConfiguration.MUTED_BY_CLIENT_VOLUME;
import static android.media.AudioPlaybackConfiguration.MUTED_BY_MASTER;
import static android.media.AudioPlaybackConfiguration.MUTED_BY_STREAM_MUTED;
import static android.media.AudioPlaybackConfiguration.MUTED_BY_STREAM_VOLUME;
import static android.media.AudioPlaybackConfiguration.MUTED_BY_VOLUME_SHAPER;
import static android.media.AudioPlaybackConfiguration.PLAYER_PIID_INVALID;
import static android.media.AudioPlaybackConfiguration.PLAYER_UPDATE_MUTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioPlaybackConfiguration.FormatInfo;
import android.media.AudioPlaybackConfiguration.PlayerMuteEvent;
import android.media.AudioSystem;
import android.media.IPlaybackConfigDispatcher;
import android.media.PlayerBase;
import android.media.VolumeShaper;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Class to receive and dispatch updates from AudioSystem about recording configurations.
 */
public final class PlaybackActivityMonitor
        implements AudioPlaybackConfiguration.PlayerDeathMonitor, PlayerFocusEnforcer {

    public static final String TAG = "AS.PlaybackActivityMon";

    /*package*/ static final boolean DEBUG = false;
    /*package*/ static final int VOLUME_SHAPER_SYSTEM_DUCK_ID = 1;
    /*package*/ static final int VOLUME_SHAPER_SYSTEM_FADEOUT_ID = 2;
    /*package*/ static final int VOLUME_SHAPER_SYSTEM_MUTE_AWAIT_CONNECTION_ID = 3;
    /*package*/ static final int VOLUME_SHAPER_SYSTEM_STRONG_DUCK_ID = 4;

    // ducking settings for a "normal duck" at -14dB
    private static final VolumeShaper.Configuration DUCK_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                .setId(VOLUME_SHAPER_SYSTEM_DUCK_ID)
                .setCurve(new float[] { 0.f, 1.f } /* times */,
                    new float[] { 1.f, 0.2f } /* volumes */)
                .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                .setDuration(MediaFocusControl.getFocusRampTimeMs(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()))
                .build();
    private static final VolumeShaper.Configuration DUCK_ID =
            new VolumeShaper.Configuration(VOLUME_SHAPER_SYSTEM_DUCK_ID);

    // ducking settings for a "strong duck" at -35dB (attenuation factor of 0.017783)
    private static final VolumeShaper.Configuration STRONG_DUCK_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                .setId(VOLUME_SHAPER_SYSTEM_STRONG_DUCK_ID)
                .setCurve(new float[] { 0.f, 1.f } /* times */,
                        new float[] { 1.f, 0.017783f } /* volumes */)
                .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                .setDuration(MediaFocusControl.getFocusRampTimeMs(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .build()))
                    .build();
    private static final VolumeShaper.Configuration STRONG_DUCK_ID =
            new VolumeShaper.Configuration(VOLUME_SHAPER_SYSTEM_STRONG_DUCK_ID);

    private static final VolumeShaper.Operation PLAY_CREATE_IF_NEEDED =
            new VolumeShaper.Operation.Builder(VolumeShaper.Operation.PLAY)
                    .createIfNeeded()
                    .build();

    private static final long UNMUTE_DURATION_MS = 100;
    private static final VolumeShaper.Configuration MUTE_AWAIT_CONNECTION_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                    .setId(VOLUME_SHAPER_SYSTEM_MUTE_AWAIT_CONNECTION_ID)
                    .setCurve(new float[] { 0.f, 1.f } /* times */,
                            new float[] { 1.f, 0.f } /* volumes */)
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    // even though we specify a duration, it's only used for the unmute,
                    // for muting this volume shaper is run with PLAY_SKIP_RAMP
                    .setDuration(UNMUTE_DURATION_MS)
                    .build();

    // TODO support VolumeShaper on those players
    private static final int[] UNDUCKABLE_PLAYER_TYPES = {
            AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO,
            AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL,
    };

    // like a PLAY_CREATE_IF_NEEDED operation but with a skip to the end of the ramp
    private static final VolumeShaper.Operation PLAY_SKIP_RAMP =
            new VolumeShaper.Operation.Builder(PLAY_CREATE_IF_NEEDED).setXOffset(1.0f).build();

    private final ConcurrentLinkedQueue<PlayMonitorClient> mClients = new ConcurrentLinkedQueue<>();

    private final Object mPlayerLock = new Object();
    @GuardedBy("mPlayerLock")
    private final HashMap<Integer, AudioPlaybackConfiguration> mPlayers =
            new HashMap<Integer, AudioPlaybackConfiguration>();

    @GuardedBy("mPlayerLock")
    private final SparseIntArray mPortIdToPiid = new SparseIntArray();

    private final Context mContext;
    private int mSavedAlarmVolume = -1;
    private final int mMaxAlarmVolume;
    private int mPrivilegedAlarmActiveCount = 0;
    private final Consumer<AudioDeviceAttributes> mMuteAwaitConnectionTimeoutCb;

    PlaybackActivityMonitor(Context context, int maxAlarmVolume,
            Consumer<AudioDeviceAttributes> muteTimeoutCallback) {
        mContext = context;
        mMaxAlarmVolume = maxAlarmVolume;
        PlayMonitorClient.sListenerDeathMonitor = this;
        AudioPlaybackConfiguration.sPlayerDeathMonitor = this;
        mMuteAwaitConnectionTimeoutCb = muteTimeoutCallback;
        initEventHandler();
    }

    //=================================================================
    private final ArrayList<Integer> mBannedUids = new ArrayList<Integer>();

    // see AudioManagerInternal.disableAudioForUid(boolean disable, int uid)
    public void disableAudioForUid(boolean disable, int uid) {
        synchronized(mPlayerLock) {
            final int index = mBannedUids.indexOf(new Integer(uid));
            if (index >= 0) {
                if (!disable) {
                    if (DEBUG) { // hidden behind DEBUG, too noisy otherwise
                        sEventLogger.enqueue(new EventLogger.StringEvent("unbanning uid:" + uid));
                    }
                    mBannedUids.remove(index);
                    // nothing else to do, future playback requests from this uid are ok
                } // no else to handle, uid already present, so disabling again is no-op
            } else {
                if (disable) {
                    for (AudioPlaybackConfiguration apc : mPlayers.values()) {
                        checkBanPlayer(apc, uid);
                    }
                    if (DEBUG) { // hidden behind DEBUG, too noisy otherwise
                        sEventLogger.enqueue(new EventLogger.StringEvent("banning uid:" + uid));
                    }
                    mBannedUids.add(new Integer(uid));
                } // no else to handle, uid already not in list, so enabling again is no-op
            }
        }
    }

    private boolean checkBanPlayer(@NonNull AudioPlaybackConfiguration apc, int uid) {
        final boolean toBan = (apc.getClientUid() == uid);
        if (toBan) {
            final int piid = apc.getPlayerInterfaceId();
            try {
                Log.v(TAG, "banning player " + piid + " uid:" + uid);
                apc.getPlayerProxy().pause();
            } catch (Exception e) {
                Log.e(TAG, "error banning player " + piid + " uid:" + uid, e);
            }
        }
        return toBan;
    }

    //=================================================================
    // Player to ignore (only handling single player, designed for ignoring
    // in the logs one specific player such as the touch sounds player)
    @GuardedBy("mPlayerLock")
    private ArrayList<Integer> mDoNotLogPiidList = new ArrayList<>();

    /*package*/ void ignorePlayerIId(int doNotLogPiid) {
        synchronized (mPlayerLock) {
            mDoNotLogPiidList.add(doNotLogPiid);
        }
    }

    //=================================================================
    // Track players and their states
    // methods playerAttributes, playerEvent, releasePlayer are all oneway calls
    //  into AudioService. They trigger synchronous dispatchPlaybackChange() which updates
    //  all listeners as oneway calls.

    public int trackPlayer(PlayerBase.PlayerIdCard pic) {
        final int newPiid = AudioSystem.newAudioPlayerId();
        if (DEBUG) { Log.v(TAG, "trackPlayer() new piid=" + newPiid); }
        final AudioPlaybackConfiguration apc =
                new AudioPlaybackConfiguration(pic, newPiid,
                        Binder.getCallingUid(), Binder.getCallingPid());
        apc.init();
        synchronized (mAllowedCapturePolicies) {
            int uid = apc.getClientUid();
            if (mAllowedCapturePolicies.containsKey(uid)) {
                updateAllowedCapturePolicy(apc, mAllowedCapturePolicies.get(uid));
            }
        }
        sEventLogger.enqueue(new NewPlayerEvent(apc));
        synchronized(mPlayerLock) {
            mPlayers.put(newPiid, apc);
            maybeMutePlayerAwaitingConnection(apc);
        }
        return newPiid;
    }

    public void playerAttributes(int piid, @NonNull AudioAttributes attr, int binderUid) {
        final boolean change;
        synchronized (mAllowedCapturePolicies) {
            if (mAllowedCapturePolicies.containsKey(binderUid)
                    && attr.getAllowedCapturePolicy() < mAllowedCapturePolicies.get(binderUid)) {
                attr = new AudioAttributes.Builder(attr)
                        .setAllowedCapturePolicy(mAllowedCapturePolicies.get(binderUid)).build();
            }
        }
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                sEventLogger.enqueue(new AudioAttrEvent(piid, attr));
                change = apc.handleAudioAttributesEvent(attr);
            } else {
                Log.e(TAG, "Error updating audio attributes");
                change = false;
            }
        }
        if (change) {
            dispatchPlaybackChange(false);
        }
    }

    /**
     * Update player session ID
     * @param piid Player id to update
     * @param sessionId The new audio session ID
     * @param binderUid Calling binder uid
     */
    public void playerSessionId(int piid, int sessionId, int binderUid) {
        final boolean change;
        synchronized (mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                change = apc.handleSessionIdEvent(sessionId);
            } else {
                Log.e(TAG, "Error updating audio session");
                change = false;
            }
        }
        if (change) {
            dispatchPlaybackChange(false);
        }
    }

    private static final int FLAGS_FOR_SILENCE_OVERRIDE =
            AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY |
            AudioAttributes.FLAG_BYPASS_MUTE;

    private void checkVolumeForPrivilegedAlarm(AudioPlaybackConfiguration apc, int event) {
        if (event == AudioPlaybackConfiguration.PLAYER_UPDATE_DEVICE_ID) {
            return;
        }
        if (event == AudioPlaybackConfiguration.PLAYER_STATE_STARTED ||
                apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
            if ((apc.getAudioAttributes().getAllFlags() & FLAGS_FOR_SILENCE_OVERRIDE)
                        == FLAGS_FOR_SILENCE_OVERRIDE  &&
                    apc.getAudioAttributes().getUsage() == AudioAttributes.USAGE_ALARM &&
                    mContext.checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE,
                            apc.getClientPid(), apc.getClientUid()) ==
                            PackageManager.PERMISSION_GRANTED) {
                if (event == AudioPlaybackConfiguration.PLAYER_STATE_STARTED &&
                        apc.getPlayerState() != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                    if (mPrivilegedAlarmActiveCount++ == 0) {
                        mSavedAlarmVolume = AudioSystem.getStreamVolumeIndex(
                                AudioSystem.STREAM_ALARM, AudioSystem.DEVICE_OUT_SPEAKER);
                        AudioSystem.setStreamVolumeIndexAS(AudioSystem.STREAM_ALARM,
                                mMaxAlarmVolume, AudioSystem.DEVICE_OUT_SPEAKER);
                    }
                } else if (event != AudioPlaybackConfiguration.PLAYER_STATE_STARTED &&
                        apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                    if (--mPrivilegedAlarmActiveCount == 0) {
                        if (AudioSystem.getStreamVolumeIndex(
                                AudioSystem.STREAM_ALARM, AudioSystem.DEVICE_OUT_SPEAKER) ==
                                mMaxAlarmVolume) {
                            AudioSystem.setStreamVolumeIndexAS(AudioSystem.STREAM_ALARM,
                                    mSavedAlarmVolume, AudioSystem.DEVICE_OUT_SPEAKER);
                        }
                    }
                }
            }
        }
    }

    /**
     * Update player event
     * @param piid Player id to update
     * @param event The new player event
     * @param eventValue The value associated with this event
     * @param binderUid Calling binder uid
     */
    public void playerEvent(int piid, int event, int eventValue, int binderUid) {
        if (DEBUG) {
            Log.v(TAG, TextUtils.formatSimple("playerEvent(piid=%d, event=%s, eventValue=%d)",
                    piid, AudioPlaybackConfiguration.playerStateToString(event), eventValue));
        }
        boolean change;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (apc == null) {
                return;
            }

            final boolean doNotLog = mDoNotLogPiidList.contains(piid);
            if (doNotLog && event != AudioPlaybackConfiguration.PLAYER_STATE_RELEASED) {
                // do not log nor dispatch events for "ignored" players other than the release
                return;
            }
            sEventLogger.enqueue(new PlayerEvent(piid, event, eventValue));

            if (event == AudioPlaybackConfiguration.PLAYER_UPDATE_PORT_ID) {
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_II_UPDATE_PORT_EVENT, eventValue, piid));
                return;
            } else if (event == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                for (Integer uidInteger: mBannedUids) {
                    if (checkBanPlayer(apc, uidInteger.intValue())) {
                        // player was banned, do not update its state
                        sEventLogger.enqueue(new EventLogger.StringEvent(
                                "not starting piid:" + piid + " ,is banned"));
                        return;
                    }
                }
            }
            if (apc.getPlayerType() == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL
                    && event != AudioPlaybackConfiguration.PLAYER_STATE_RELEASED) {
                // FIXME SoundPool not ready for state reporting
                return;
            }
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                //TODO add generation counter to only update to the latest state
                checkVolumeForPrivilegedAlarm(apc, event);
                change = apc.handleStateEvent(event, eventValue);
            } else {
                Log.e(TAG, "Error handling event " + event);
                change = false;
            }
            if (change) {
                if (event == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                    mDuckingManager.checkDuck(apc);
                    mFadingManager.checkFade(apc);
                }
                if (doNotLog) {
                    // do not dispatch events for "ignored" players
                    change = false;
                }
            }
        }
        if (change) {
            dispatchPlaybackChange(event == AudioPlaybackConfiguration.PLAYER_STATE_RELEASED);
        }
    }

    /**
     * Update event for port
     * @param portId Port id to update
     * @param event The new port event
     * @param extras The values associated with this event
     * @param binderUid Calling binder uid
     */
    public void portEvent(int portId, int event, @Nullable PersistableBundle extras,
            int binderUid) {
        if (!UserHandle.isCore(binderUid)) {
            Log.e(TAG, "Forbidden operation from uid " + binderUid);
            return;
        }

        if (DEBUG) {
            Log.v(TAG, TextUtils.formatSimple("BLA portEvent(portId=%d, event=%s, extras=%s)",
                    portId, AudioPlaybackConfiguration.playerStateToString(event), extras));
        }

        synchronized (mPlayerLock) {
            int piid = mPortIdToPiid.get(portId, PLAYER_PIID_INVALID);
            if (piid == PLAYER_PIID_INVALID) {
                if (DEBUG) {
                    Log.v(TAG, "No piid assigned for invalid/internal port id " + portId);
                }
                return;
            }
            final AudioPlaybackConfiguration apc = mPlayers.get(piid);
            if (apc == null) {
                if (DEBUG) {
                    Log.v(TAG, "No AudioPlaybackConfiguration assigned for piid " + piid);
                }
                return;
            }

            if (apc.getPlayerType()
                    == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL) {
                // FIXME SoundPool not ready for state reporting
                return;
            }

            if (event == AudioPlaybackConfiguration.PLAYER_UPDATE_MUTED) {
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_IIL_UPDATE_PLAYER_MUTED_EVENT, piid,
                                portId,
                                extras));
            } else if (event == AudioPlaybackConfiguration.PLAYER_UPDATE_FORMAT) {
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_IIL_UPDATE_PLAYER_FORMAT, piid,
                                portId,
                                extras));
            }
        }
    }

    public void playerHasOpPlayAudio(int piid, boolean hasOpPlayAudio, int binderUid) {
        // no check on UID yet because this is only for logging at the moment
        sEventLogger.enqueue(new PlayerOpPlayAudioEvent(piid, hasOpPlayAudio, binderUid));
    }

    public void releasePlayer(int piid, int binderUid) {
        if (DEBUG) { Log.v(TAG, "releasePlayer() for piid=" + piid); }
        boolean change = false;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                sEventLogger.enqueue(new EventLogger.StringEvent(
                        "releasing player piid:" + piid));
                mPlayers.remove(new Integer(piid));
                mDuckingManager.removeReleased(apc);
                mFadingManager.removeReleased(apc);
                mMutedPlayersAwaitingConnection.remove(Integer.valueOf(piid));
                checkVolumeForPrivilegedAlarm(apc, AudioPlaybackConfiguration.PLAYER_STATE_RELEASED);
                change = apc.handleStateEvent(AudioPlaybackConfiguration.PLAYER_STATE_RELEASED,
                        AudioPlaybackConfiguration.PLAYER_DEVICEID_INVALID);

                // remove all port ids mapped to the released player
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_I_CLEAR_PORTS_FOR_PIID, piid, /*arg2=*/0));

                if (change && mDoNotLogPiidList.contains(piid)) {
                    // do not dispatch a change for a "do not log" player
                    change = false;
                }
            }
        }
        if (change) {
            dispatchPlaybackChange(true /*iplayerreleased*/);
        }
    }

    /*package*/ void onAudioServerDied() {
        sEventLogger.enqueue(
                new EventLogger.StringEvent(
                        "clear port id to piid map"));
        synchronized (mPlayerLock) {
            if (DEBUG) {
                Log.v(TAG, "clear port id to piid map:\n" + mPortIdToPiid);
            }
            mPortIdToPiid.clear();
        }
    }

    /**
     * A map of uid to capture policy.
     */
    private final HashMap<Integer, Integer> mAllowedCapturePolicies =
            new HashMap<Integer, Integer>();

    /**
     * Cache allowed capture policy, which specifies whether the audio played by the app may or may
     * not be captured by other apps or the system.
     *
     * @param uid the uid of requested app
     * @param capturePolicy one of
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_ALL},
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_SYSTEM},
     *     {@link AudioAttributes#ALLOW_CAPTURE_BY_NONE}.
     */
    public void setAllowedCapturePolicy(int uid, int capturePolicy) {
        synchronized (mAllowedCapturePolicies) {
            if (capturePolicy == AudioAttributes.ALLOW_CAPTURE_BY_ALL) {
                // When the capture policy is ALLOW_CAPTURE_BY_ALL, it is okay to
                // remove it from cached capture policy as it is the default value.
                mAllowedCapturePolicies.remove(uid);
                return;
            } else {
                mAllowedCapturePolicies.put(uid, capturePolicy);
            }
        }
        synchronized (mPlayerLock) {
            for (AudioPlaybackConfiguration apc : mPlayers.values()) {
                if (apc.getClientUid() == uid) {
                    updateAllowedCapturePolicy(apc, capturePolicy);
                }
            }
        }
    }

    /**
     * Return the capture policy for given uid.
     * @param uid the uid to query its cached capture policy.
     * @return cached capture policy for given uid or AudioAttributes.ALLOW_CAPTURE_BY_ALL
     *         if there is not cached capture policy.
     */
    public int getAllowedCapturePolicy(int uid) {
        return mAllowedCapturePolicies.getOrDefault(uid, AudioAttributes.ALLOW_CAPTURE_BY_ALL);
    }

    /**
     * Return a copy of all cached capture policies.
     */
    public HashMap<Integer, Integer> getAllAllowedCapturePolicies() {
        synchronized (mAllowedCapturePolicies) {
            return (HashMap<Integer, Integer>) mAllowedCapturePolicies.clone();
        }
    }

    private void updateAllowedCapturePolicy(AudioPlaybackConfiguration apc, int capturePolicy) {
        AudioAttributes attr = apc.getAudioAttributes();
        if (attr.getAllowedCapturePolicy() >= capturePolicy) {
            return;
        }
        apc.handleAudioAttributesEvent(
                new AudioAttributes.Builder(apc.getAudioAttributes())
                        .setAllowedCapturePolicy(capturePolicy).build());
    }

    // Implementation of AudioPlaybackConfiguration.PlayerDeathMonitor
    @Override
    public void playerDeath(int piid) {
        releasePlayer(piid, 0);
    }

    /**
     * Returns true if a player belonging to the app with given uid is active.
     *
     * @param uid the app uid
     * @return true if a player is active, false otherwise
     */
    public boolean isPlaybackActiveForUid(int uid) {
        synchronized (mPlayerLock) {
            for (AudioPlaybackConfiguration apc : mPlayers.values()) {
                if (apc.isActive() && apc.getClientUid() == uid) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Return true if an active playback for media use case is currently routed to
     * a remote submix device with the supplied address.
     * @param address
     */
    public boolean hasActiveMediaPlaybackOnSubmixWithAddress(@NonNull String address) {
        synchronized (mPlayerLock) {
            for (AudioPlaybackConfiguration apc : mPlayers.values()) {
                AudioDeviceInfo device = apc.getAudioDeviceInfo();
                if (apc.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA
                        && apc.isActive() && device != null
                        && device.getInternalType() == AudioSystem.DEVICE_OUT_REMOTE_SUBMIX
                        && address.equals(device.getAddress())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void dump(PrintWriter pw) {
        // players
        pw.println("\nPlaybackActivityMonitor dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        synchronized(mPlayerLock) {
            pw.println("\n  playback listeners:");
            for (PlayMonitorClient pmc : mClients) {
                pw.print(" " + (pmc.isPrivileged() ? "(S)" : "(P)")
                        + pmc.toString());
            }
            pw.println("\n");
            // all players
            pw.println("\n  players:");
            final List<Integer> piidIntList = new ArrayList<Integer>(mPlayers.keySet());
            Collections.sort(piidIntList);
            for (Integer piidInt : piidIntList) {
                final AudioPlaybackConfiguration apc = mPlayers.get(piidInt);
                if (apc != null) {
                    if (mDoNotLogPiidList.contains(apc.getPlayerInterfaceId())) {
                        pw.print("(not logged)");
                    }
                    apc.dump(pw);
                }
            }
            // ducked players
            pw.println("\n  ducked players piids:");
            mDuckingManager.dump(pw);
            // faded out players
            pw.println("\n  faded out players piids:");
            mFadingManager.dump(pw);
            // players muted due to the device ringing or being in a call
            pw.print("\n  muted player piids due to call/ring:");
            for (int piid : mMutedPlayers) {
                pw.print(" " + piid);
            }
            pw.println();
            // banned players:
            pw.print("\n  banned uids:");
            for (int uid : mBannedUids) {
                pw.print(" " + uid);
            }
            pw.println("\n");
            // muted players:
            pw.print("\n  muted players (piids) awaiting device connection:");
            for (int piid : mMutedPlayersAwaitingConnection) {
                pw.print(" " + piid);
            }
            pw.println("\n");
            // portId to piid mappings:
            pw.println("\n  current portId to piid map:");
            for (int i = 0; i < mPortIdToPiid.size(); ++i) {
                pw.println(
                        "  portId: " + mPortIdToPiid.keyAt(i) + " piid: " + mPortIdToPiid.valueAt(
                                i));
            }
            pw.println("\n");
            // log
            sEventLogger.dump(pw);
        }

        synchronized (mAllowedCapturePolicies) {
            pw.println("\n  allowed capture policies:");
            for (HashMap.Entry<Integer, Integer> entry : mAllowedCapturePolicies.entrySet()) {
                pw.println("  uid: " + entry.getKey() + " policy: " + entry.getValue());
            }
        }
    }

    /**
     * Check that piid and uid are valid for the given valid configuration.
     * @param piid the piid of the player.
     * @param apc the configuration found for this piid.
     * @param binderUid actual uid of client trying to signal a player state/event/attributes.
     * @return true if the call is valid and the change should proceed, false otherwise. Always
     *      returns false when apc is null.
     */
    private static boolean checkConfigurationCaller(int piid,
            final AudioPlaybackConfiguration apc, int binderUid) {
        if (apc == null) {
            return false;
        } else if ((binderUid != 0) && (apc.getClientUid() != binderUid)) {
            Log.e(TAG, "Forbidden operation from uid " + binderUid + " for player " + piid);
            return false;
        }
        return true;
    }

    /**
     * Sends new list after update of playback configurations
     * @param iplayerReleased indicates if the change was due to a player being released
     */
    private void dispatchPlaybackChange(boolean iplayerReleased) {
        if (DEBUG) { Log.v(TAG, "dispatchPlaybackChange to " + mClients.size() + " clients"); }
        final List<AudioPlaybackConfiguration> configsSystem;
        // list of playback configurations for "public consumption". It is computed lazy if there
        // are non-system playback activity listeners.
        List<AudioPlaybackConfiguration> configsPublic = null;
        synchronized (mPlayerLock) {
            if (mPlayers.isEmpty()) {
                return;
            }
            configsSystem = new ArrayList<>(mPlayers.values());
        }

        final Iterator<PlayMonitorClient> clientIterator = mClients.iterator();
        while (clientIterator.hasNext()) {
            final PlayMonitorClient pmc = clientIterator.next();
            // do not spam the logs if there are problems communicating with this client
            if (!pmc.reachedMaxErrorCount()) {
                if (pmc.isPrivileged()) {
                    pmc.dispatchPlaybackConfigChange(configsSystem,
                            iplayerReleased);
                } else {
                    if (configsPublic == null) {
                        configsPublic = anonymizeForPublicConsumption(configsSystem);
                    }
                    // non-system clients don't have the control interface IPlayer, so
                    // they don't need to flush commands when a player was released
                    pmc.dispatchPlaybackConfigChange(configsPublic, false);
                }
            }
        }
    }

    private ArrayList<AudioPlaybackConfiguration> anonymizeForPublicConsumption(
            List<AudioPlaybackConfiguration> sysConfigs) {
        ArrayList<AudioPlaybackConfiguration> publicConfigs =
                new ArrayList<AudioPlaybackConfiguration>();
        // only add active anonymized configurations,
        for (AudioPlaybackConfiguration config : sysConfigs) {
            if (config.isActive()) {
                publicConfigs.add(AudioPlaybackConfiguration.anonymizedCopy(config));
            }
        }
        return publicConfigs;
    }


    //=================================================================
    // PlayerFocusEnforcer implementation
    private final ArrayList<Integer> mMutedPlayers = new ArrayList<Integer>();

    private final DuckingManager mDuckingManager = new DuckingManager();

    @Override
    public boolean duckPlayers(@NonNull FocusRequester winner, @NonNull FocusRequester loser,
                               boolean forceDuck) {
        if (DEBUG) {
            Log.v(TAG, String.format("duckPlayers: uids winner=%d loser=%d",
                    winner.getClientUid(), loser.getClientUid()));
        }
        synchronized (mPlayerLock) {
            if (mPlayers.isEmpty()) {
                return true;
            }
            // check if this UID needs to be ducked (return false if not), and gather list of
            // eligible players to duck
            final Iterator<AudioPlaybackConfiguration> apcIterator = mPlayers.values().iterator();
            final ArrayList<AudioPlaybackConfiguration> apcsToDuck =
                    new ArrayList<AudioPlaybackConfiguration>();
            while (apcIterator.hasNext()) {
                final AudioPlaybackConfiguration apc = apcIterator.next();
                if (!winner.hasSameUid(apc.getClientUid())
                        && loser.hasSameUid(apc.getClientUid())
                        && apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED)
                {
                    if (!forceDuck && (apc.getAudioAttributes().getContentType() ==
                            AudioAttributes.CONTENT_TYPE_SPEECH)) {
                        // the player is speaking, ducking will make the speech unintelligible
                        // so let the app handle it instead
                        Log.v(TAG, "not ducking player " + apc.getPlayerInterfaceId()
                                + " uid:" + apc.getClientUid() + " pid:" + apc.getClientPid()
                                + " - SPEECH");
                        return false;
                    } else if (ArrayUtils.contains(UNDUCKABLE_PLAYER_TYPES, apc.getPlayerType())) {
                        Log.v(TAG, "not ducking player " + apc.getPlayerInterfaceId()
                                + " uid:" + apc.getClientUid() + " pid:" + apc.getClientPid()
                                + " due to type:"
                                + AudioPlaybackConfiguration.toLogFriendlyPlayerType(
                                        apc.getPlayerType()));
                        return false;
                    }
                    apcsToDuck.add(apc);
                }
            }
            // add the players eligible for ducking to the list, and duck them
            // (if apcsToDuck is empty, this will at least mark this uid as ducked, so when
            //  players of the same uid start, they will be ducked by DuckingManager.checkDuck())
            mDuckingManager.duckUid(loser.getClientUid(), apcsToDuck, reqCausesStrongDuck(winner));
        }
        return true;
    }

    private boolean reqCausesStrongDuck(FocusRequester requester) {
        if (requester.getGainRequest() != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
            return false;
        }
        final int reqUsage = requester.getAudioAttributes().getUsage();
        if ((reqUsage == AudioAttributes.USAGE_ASSISTANT)
                || (reqUsage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)) {
            return true;
        }
        return false;
    }

    @Override
    public void restoreVShapedPlayers(@NonNull FocusRequester winner) {
        if (DEBUG) { Log.v(TAG, "unduckPlayers: uids winner=" + winner.getClientUid()); }
        synchronized (mPlayerLock) {
            mDuckingManager.unduckUid(winner.getClientUid(), mPlayers);
            mFadingManager.unfadeOutUid(winner.getClientUid(), mPlayers);
        }
    }

    @Override
    public void mutePlayersForCall(int[] usagesToMute) {
        if (DEBUG) {
            String log = new String("mutePlayersForCall: usages=");
            for (int usage : usagesToMute) { log += " " + usage; }
            Log.v(TAG, log);
        }
        synchronized (mPlayerLock) {
            final Set<Integer> piidSet = mPlayers.keySet();
            final Iterator<Integer> piidIterator = piidSet.iterator();
            // find which players to mute
            while (piidIterator.hasNext()) {
                final Integer piid = piidIterator.next();
                final AudioPlaybackConfiguration apc = mPlayers.get(piid);
                if (apc == null) {
                    continue;
                }
                final int playerUsage = apc.getAudioAttributes().getUsage();
                boolean mute = false;
                for (int usageToMute : usagesToMute) {
                    if (playerUsage == usageToMute) {
                        mute = true;
                        break;
                    }
                }
                if (mute) {
                    try {
                        sEventLogger.enqueue((new EventLogger.StringEvent("call: muting piid:"
                                + piid + " uid:" + apc.getClientUid())).printLog(TAG));
                        apc.getPlayerProxy().setVolume(0.0f);
                        mMutedPlayers.add(new Integer(piid));
                    } catch (Exception e) {
                        Log.e(TAG, "call: error muting player " + piid, e);
                    }
                }
            }
        }
    }

    @Override
    public void unmutePlayersForCall() {
        if (DEBUG) {
            Log.v(TAG, "unmutePlayersForCall()");
        }
        synchronized (mPlayerLock) {
            if (mMutedPlayers.isEmpty()) {
                return;
            }
            for (int piid : mMutedPlayers) {
                final AudioPlaybackConfiguration apc = mPlayers.get(piid);
                if (apc != null) {
                    try {
                        sEventLogger.enqueue(new EventLogger.StringEvent("call: unmuting piid:"
                                + piid).printLog(TAG));
                        apc.getPlayerProxy().setVolume(1.0f);
                    } catch (Exception e) {
                        Log.e(TAG, "call: error unmuting player " + piid + " uid:"
                                + apc.getClientUid(), e);
                    }
                }
            }
            mMutedPlayers.clear();
        }
    }

    private final FadeOutManager mFadingManager = new FadeOutManager();

    /**
     *
     * @param winner the new non-transient focus owner
     * @param loser the previous focus owner
     * @return true if there are players being faded out
     */
    @Override
    public boolean fadeOutPlayers(@NonNull FocusRequester winner, @NonNull FocusRequester loser) {
        if (DEBUG) {
            Log.v(TAG, "fadeOutPlayers: winner=" + winner.getPackageName()
                    +  " loser=" + loser.getPackageName());
        }
        boolean loserHasActivePlayers = false;

        // find which players to fade out
        synchronized (mPlayerLock) {
            if (mPlayers.isEmpty()) {
                if (DEBUG) { Log.v(TAG, "no players to fade out"); }
                return false;
            }
            if (!FadeOutManager.canCauseFadeOut(winner, loser)) {
                return false;
            }
            // check if this UID needs to be faded out (return false if not), and gather list of
            // eligible players to fade out
            final Iterator<AudioPlaybackConfiguration> apcIterator = mPlayers.values().iterator();
            final ArrayList<AudioPlaybackConfiguration> apcsToFadeOut =
                    new ArrayList<AudioPlaybackConfiguration>();
            while (apcIterator.hasNext()) {
                final AudioPlaybackConfiguration apc = apcIterator.next();
                if (!winner.hasSameUid(apc.getClientUid())
                        && loser.hasSameUid(apc.getClientUid())
                        && apc.getPlayerState()
                        == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                    if (!FadeOutManager.canBeFadedOut(apc)) {
                        // the player is not eligible to be faded out, bail
                        Log.v(TAG, "not fading out player " + apc.getPlayerInterfaceId()
                                + " uid:" + apc.getClientUid() + " pid:" + apc.getClientPid()
                                + " type:"
                                + AudioPlaybackConfiguration.toLogFriendlyPlayerType(
                                        apc.getPlayerType())
                                + " attr:" + apc.getAudioAttributes());
                        return false;
                    }
                    loserHasActivePlayers = true;
                    apcsToFadeOut.add(apc);
                }
            }
            if (loserHasActivePlayers) {
                mFadingManager.fadeOutUid(loser.getClientUid(), apcsToFadeOut);
            }
        }

        return loserHasActivePlayers;
    }

    @Override
    public void forgetUid(int uid) {
        final HashMap<Integer, AudioPlaybackConfiguration> players;
        synchronized (mPlayerLock) {
            players = (HashMap<Integer, AudioPlaybackConfiguration>) mPlayers.clone();
        }
        mFadingManager.unfadeOutUid(uid, players);
    }

    //=================================================================
    // Track playback activity listeners

    void registerPlaybackCallback(IPlaybackConfigDispatcher pcdb, boolean isPrivileged) {
        if (pcdb == null) {
            return;
        }
        final PlayMonitorClient pmc = new PlayMonitorClient(pcdb, isPrivileged);
        if (pmc.init()) {
            mClients.add(pmc);
        }
    }

    void unregisterPlaybackCallback(IPlaybackConfigDispatcher pcdb) {
        if (pcdb == null) {
            return;
        }
        final Iterator<PlayMonitorClient> clientIterator = mClients.iterator();
        // iterate over the clients to remove the dispatcher
        while (clientIterator.hasNext()) {
            PlayMonitorClient pmc = clientIterator.next();
            if (pmc.equalsDispatcher(pcdb)) {
                pmc.release();
                clientIterator.remove();
            }
        }
    }

    List<AudioPlaybackConfiguration> getActivePlaybackConfigurations(boolean isPrivileged) {
        synchronized(mPlayers) {
            if (isPrivileged) {
                return new ArrayList<AudioPlaybackConfiguration>(mPlayers.values());
            } else {
                final List<AudioPlaybackConfiguration> configsPublic;
                synchronized (mPlayerLock) {
                    configsPublic = anonymizeForPublicConsumption(
                            new ArrayList<AudioPlaybackConfiguration>(mPlayers.values()));
                }
                return configsPublic;
            }
        }
    }


    /**
     * Inner class to track clients that want to be notified of playback updates
     */
    private static final class PlayMonitorClient implements IBinder.DeathRecipient {

        // can afford to be static because only one PlaybackActivityMonitor ever instantiated
        static PlaybackActivityMonitor sListenerDeathMonitor;

        // number of errors after which we don't update this client anymore to not spam the logs
        private static final int MAX_ERRORS = 5;

        private final IPlaybackConfigDispatcher mDispatcherCb;

        @GuardedBy("this")
        private final boolean mIsPrivileged;
        @GuardedBy("this")
        private boolean mIsReleased = false;
        @GuardedBy("this")
        private int mErrorCount = 0;

        PlayMonitorClient(IPlaybackConfigDispatcher pcdb, boolean isPrivileged) {
            mDispatcherCb = pcdb;
            mIsPrivileged = isPrivileged;
        }

        @Override
        public void binderDied() {
            Log.w(TAG, "client died");
            sListenerDeathMonitor.unregisterPlaybackCallback(mDispatcherCb);
        }

        synchronized boolean init() {
            if (mIsReleased) {
                // Do not init after release
                return false;
            }
            try {
                mDispatcherCb.asBinder().linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "Could not link to client death", e);
                return false;
            }
        }

        synchronized void release() {
            mDispatcherCb.asBinder().unlinkToDeath(this, 0);
            mIsReleased = true;
        }

        void dispatchPlaybackConfigChange(List<AudioPlaybackConfiguration> configs,
                boolean flush) {
            synchronized (this) {
                if (mIsReleased) {
                    // Do not dispatch anything after release
                    return;
                }
            }
            try {
                mDispatcherCb.dispatchPlaybackConfigChange(configs, flush);
            } catch (RemoteException e) {
                synchronized (this) {
                    mErrorCount++;
                    Log.e(TAG, "Error (" + mErrorCount
                            + ") trying to dispatch playback config change to " + this, e);
                }
            }
        }

        synchronized boolean isPrivileged() {
            return mIsPrivileged;
        }

        synchronized boolean reachedMaxErrorCount() {
            return mErrorCount >= MAX_ERRORS;
        }

        synchronized boolean equalsDispatcher(IPlaybackConfigDispatcher pcdb) {
            if (pcdb == null) {
                return false;
            }
            return pcdb.asBinder().equals(mDispatcherCb.asBinder());
        }
    }

    //=================================================================
    // Class to handle ducking related operations for a given UID
    private static final class DuckingManager {
        private final HashMap<Integer, DuckedApp> mDuckers = new HashMap<Integer, DuckedApp>();

        synchronized void duckUid(int uid, ArrayList<AudioPlaybackConfiguration> apcsToDuck,
                boolean requestCausesStrongDuck) {
            if (DEBUG) {  Log.v(TAG, "DuckingManager: duckUid() uid:"+ uid); }
            if (!mDuckers.containsKey(uid)) {
                mDuckers.put(uid, new DuckedApp(uid, requestCausesStrongDuck));
            }
            final DuckedApp da = mDuckers.get(uid);
            for (AudioPlaybackConfiguration apc : apcsToDuck) {
                da.addDuck(apc, false /*skipRamp*/);
            }
        }

        synchronized void unduckUid(int uid, HashMap<Integer, AudioPlaybackConfiguration> players) {
            if (DEBUG) {  Log.v(TAG, "DuckingManager: unduckUid() uid:"+ uid); }
            final DuckedApp da = mDuckers.remove(uid);
            if (da == null) {
                return;
            }
            da.removeUnduckAll(players);
        }

        // pre-condition: apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
        synchronized void checkDuck(@NonNull AudioPlaybackConfiguration apc) {
            if (DEBUG) {  Log.v(TAG, "DuckingManager: checkDuck() player piid:"
                    + apc.getPlayerInterfaceId()+ " uid:"+ apc.getClientUid()); }
            final DuckedApp da = mDuckers.get(apc.getClientUid());
            if (da == null) {
                return;
            }
            da.addDuck(apc, true /*skipRamp*/);
        }

        synchronized void dump(PrintWriter pw) {
            for (DuckedApp da : mDuckers.values()) {
                da.dump(pw);
            }
        }

        synchronized void removeReleased(@NonNull AudioPlaybackConfiguration apc) {
            final int uid = apc.getClientUid();
            if (DEBUG) {  Log.v(TAG, "DuckingManager: removedReleased() player piid: "
                    + apc.getPlayerInterfaceId() + " uid:" + uid); }
            final DuckedApp da = mDuckers.get(uid);
            if (da == null) {
                return;
            }
            da.removeReleased(apc);
        }

        private static final class DuckedApp {
            private final int mUid;
            /** determines whether ducking is done with DUCK_VSHAPE or STRONG_DUCK_VSHAPE */
            private final boolean mUseStrongDuck;
            private final ArrayList<Integer> mDuckedPlayers = new ArrayList<Integer>();

            DuckedApp(int uid, boolean useStrongDuck) {
                mUid = uid;
                mUseStrongDuck = useStrongDuck;
            }

            void dump(PrintWriter pw) {
                pw.print("\t uid:" + mUid + " piids:");
                for (int piid : mDuckedPlayers) {
                    pw.print(" " + piid);
                }
                pw.println("");
            }

            // pre-conditions:
            //  * apc != null
            //  * apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
            void addDuck(@NonNull AudioPlaybackConfiguration apc, boolean skipRamp) {
                final int piid = new Integer(apc.getPlayerInterfaceId());
                if (mDuckedPlayers.contains(piid)) {
                    if (DEBUG) { Log.v(TAG, "player piid:" + piid + " already ducked"); }
                    return;
                }
                try {
                    sEventLogger.enqueue((new DuckEvent(apc, skipRamp, mUseStrongDuck))
                            .printLog(TAG));
                    apc.getPlayerProxy().applyVolumeShaper(
                            mUseStrongDuck ? STRONG_DUCK_VSHAPE : DUCK_VSHAPE,
                            skipRamp ? PLAY_SKIP_RAMP : PLAY_CREATE_IF_NEEDED);
                    mDuckedPlayers.add(piid);
                } catch (Exception e) {
                    Log.e(TAG, "Error ducking player piid:" + piid + " uid:" + mUid, e);
                }
            }

            void removeUnduckAll(HashMap<Integer, AudioPlaybackConfiguration> players) {
                for (int piid : mDuckedPlayers) {
                    final AudioPlaybackConfiguration apc = players.get(piid);
                    if (apc != null) {
                        try {
                            sEventLogger.enqueue((new EventLogger.StringEvent("unducking piid:"
                                    + piid)).printLog(TAG));
                            apc.getPlayerProxy().applyVolumeShaper(
                                    mUseStrongDuck ? STRONG_DUCK_ID : DUCK_ID,
                                    VolumeShaper.Operation.REVERSE);
                        } catch (Exception e) {
                            Log.e(TAG, "Error unducking player piid:" + piid + " uid:" + mUid, e);
                        }
                    } else {
                        // this piid was in the list of ducked players, but wasn't found
                        if (DEBUG) {
                            Log.v(TAG, "Error unducking player piid:" + piid
                                    + ", player not found for uid " + mUid);
                        }
                    }
                }
                mDuckedPlayers.clear();
            }

            void removeReleased(@NonNull AudioPlaybackConfiguration apc) {
                mDuckedPlayers.remove(new Integer(apc.getPlayerInterfaceId()));
            }
        }
    }

    //=================================================================
    // For logging
    private static final class PlayerEvent extends EventLogger.Event {
        // only keeping the player interface ID as it uniquely identifies the player in the event
        final int mPlayerIId;
        final int mEvent;
        final int mEventValue;

        PlayerEvent(int piid, int event, int eventValue) {
            mPlayerIId = piid;
            mEvent = event;
            mEventValue = eventValue;
        }

        @Override
        public String eventToString() {
            StringBuilder builder = new StringBuilder("player piid:").append(mPlayerIId).append(
                            " event:")
                    .append(AudioPlaybackConfiguration.toLogFriendlyPlayerState(mEvent));

            switch (mEvent) {
                case AudioPlaybackConfiguration.PLAYER_UPDATE_PORT_ID:
                    return AudioPlaybackConfiguration.toLogFriendlyPlayerState(mEvent) + " portId:"
                            + mEventValue + " mapped to player piid:" + mPlayerIId;
                case AudioPlaybackConfiguration.PLAYER_UPDATE_DEVICE_ID:
                    if (mEventValue != 0) {
                        builder.append(" deviceId:").append(mEventValue);
                    }
                    return builder.toString();
                case AudioPlaybackConfiguration.PLAYER_UPDATE_MUTED:
                    builder.append(" source:");
                    if (mEventValue <= 0) {
                        builder.append("none ");
                    } else {
                        if ((mEventValue & MUTED_BY_MASTER) != 0) {
                            builder.append("masterMute ");
                        }
                        if ((mEventValue & MUTED_BY_STREAM_VOLUME) != 0) {
                            builder.append("streamVolume ");
                        }
                        if ((mEventValue & MUTED_BY_STREAM_MUTED) != 0) {
                            builder.append("streamMute ");
                        }
                        if ((mEventValue & MUTED_BY_APP_OPS) != 0) {
                            builder.append("appOps ");
                        }
                        if ((mEventValue & MUTED_BY_CLIENT_VOLUME) != 0) {
                            builder.append("clientVolume ");
                        }
                        if ((mEventValue & MUTED_BY_VOLUME_SHAPER) != 0) {
                            builder.append("volumeShaper ");
                        }
                    }
                    return builder.toString();
                default:
                    return builder.toString();
            }
        }
    }

    private static final class PlayerOpPlayAudioEvent extends EventLogger.Event {
        // only keeping the player interface ID as it uniquely identifies the player in the event
        final int mPlayerIId;
        final boolean mHasOp;
        final int mUid;

        PlayerOpPlayAudioEvent(int piid, boolean hasOp, int uid) {
            mPlayerIId = piid;
            mHasOp = hasOp;
            mUid = uid;
        }

        @Override
        public String eventToString() {
            return new StringBuilder("player piid:").append(mPlayerIId)
                    .append(" has OP_PLAY_AUDIO:").append(mHasOp)
                    .append(" in uid:").append(mUid).toString();
        }
    }

    private static final class NewPlayerEvent extends EventLogger.Event {
        private final int mPlayerIId;
        private final int mPlayerType;
        private final int mClientUid;
        private final int mClientPid;
        private final AudioAttributes mPlayerAttr;
        private final int mSessionId;

        NewPlayerEvent(AudioPlaybackConfiguration apc) {
            mPlayerIId = apc.getPlayerInterfaceId();
            mPlayerType = apc.getPlayerType();
            mClientUid = apc.getClientUid();
            mClientPid = apc.getClientPid();
            mPlayerAttr = apc.getAudioAttributes();
            mSessionId = apc.getSessionId();
        }

        @Override
        public String eventToString() {
            return new String("new player piid:" + mPlayerIId + " uid/pid:" + mClientUid + "/"
                    + mClientPid + " type:"
                    + AudioPlaybackConfiguration.toLogFriendlyPlayerType(mPlayerType)
                    + " attr:" + mPlayerAttr
                    + " session:" + mSessionId);
        }
    }

    private abstract static class VolumeShaperEvent extends EventLogger.Event {
        private final int mPlayerIId;
        private final boolean mSkipRamp;
        private final int mClientUid;
        private final int mClientPid;

        abstract String getVSAction();

        VolumeShaperEvent(@NonNull AudioPlaybackConfiguration apc, boolean skipRamp) {
            mPlayerIId = apc.getPlayerInterfaceId();
            mSkipRamp = skipRamp;
            mClientUid = apc.getClientUid();
            mClientPid = apc.getClientPid();
        }

        @Override
        public String eventToString() {
            return new StringBuilder(getVSAction()).append(" player piid:").append(mPlayerIId)
                    .append(" uid/pid:").append(mClientUid).append("/").append(mClientPid)
                    .append(" skip ramp:").append(mSkipRamp).toString();
        }
    }

    static final class DuckEvent extends VolumeShaperEvent {
        final boolean mUseStrongDuck;

        @Override
        String getVSAction() {
            return mUseStrongDuck ? "ducking (strong)" : "ducking";
        }

        DuckEvent(@NonNull AudioPlaybackConfiguration apc, boolean skipRamp, boolean useStrongDuck)
        {
            super(apc, skipRamp);
            mUseStrongDuck = useStrongDuck;
        }
    }

    static final class FadeOutEvent extends VolumeShaperEvent {
        @Override
        String getVSAction() {
            return "fading out";
        }

        FadeOutEvent(@NonNull AudioPlaybackConfiguration apc, boolean skipRamp) {
            super(apc, skipRamp);
        }
    }

    private static final class AudioAttrEvent extends EventLogger.Event {
        private final int mPlayerIId;
        private final AudioAttributes mPlayerAttr;

        AudioAttrEvent(int piid, AudioAttributes attr) {
            mPlayerIId = piid;
            mPlayerAttr = attr;
        }

        @Override
        public String eventToString() {
            return new String("player piid:" + mPlayerIId + " new AudioAttributes:" + mPlayerAttr);
        }
    }

    private static final class MuteAwaitConnectionEvent extends EventLogger.Event {
        private final @NonNull int[] mUsagesToMute;

        MuteAwaitConnectionEvent(@NonNull int[] usagesToMute) {
            mUsagesToMute = usagesToMute;
        }

        @Override
        public String eventToString() {
            return "muteAwaitConnection muting usages " + Arrays.toString(mUsagesToMute);
        }
    }

    private static final class PlayerFormatEvent extends EventLogger.Event {
        private final int mPlayerIId;
        private final AudioPlaybackConfiguration.FormatInfo mFormat;

        PlayerFormatEvent(int piid, AudioPlaybackConfiguration.FormatInfo format) {
            mPlayerIId = piid;
            mFormat = format;
        }

        @Override
        public String eventToString() {
            return new String("player piid:" + mPlayerIId + " format update:" + mFormat);
        }
    }

    static final EventLogger
            sEventLogger = new EventLogger(100,
            "playback activity as reported through PlayerBase");

    //==========================================================================================
    // Mute conditional on device connection
    //==========================================================================================
    void muteAwaitConnection(@NonNull int[] usagesToMute,
            @NonNull AudioDeviceAttributes dev, long timeOutMs) {
        sEventLogger.enqueueAndLog(
                "muteAwaitConnection() dev:" + dev + " timeOutMs:" + timeOutMs,
                EventLogger.Event.ALOGI, TAG);
        synchronized (mPlayerLock) {
            mutePlayersExpectingDevice(usagesToMute);
            // schedule timeout (remove previously scheduled first)
            mEventHandler.removeMessages(MSG_L_TIMEOUT_MUTE_AWAIT_CONNECTION);
            mEventHandler.sendMessageDelayed(
                    mEventHandler.obtainMessage(MSG_L_TIMEOUT_MUTE_AWAIT_CONNECTION, dev),
                    timeOutMs);
        }
    }

    void cancelMuteAwaitConnection(String source) {
        sEventLogger.enqueueAndLog("cancelMuteAwaitConnection() from:" + source,
                EventLogger.Event.ALOGI, TAG);
        synchronized (mPlayerLock) {
            // cancel scheduled timeout, ignore device, only one expected device at a time
            mEventHandler.removeMessages(MSG_L_TIMEOUT_MUTE_AWAIT_CONNECTION);
            // unmute immediately
            unmutePlayersExpectingDevice();
        }
    }

    /**
     * List of the piids of the players that are muted until a specific audio device connects
     */
    @GuardedBy("mPlayerLock")
    private final ArrayList<Integer> mMutedPlayersAwaitingConnection = new ArrayList<Integer>();

    /**
     * List of AudioAttributes usages to mute until a specific audio device connects
     */
    @GuardedBy("mPlayerLock")
    private @Nullable int[] mMutedUsagesAwaitingConnection = null;

    @GuardedBy("mPlayerLock")
    private void mutePlayersExpectingDevice(@NonNull int[] usagesToMute) {
        sEventLogger.enqueue(new MuteAwaitConnectionEvent(usagesToMute));
        mMutedUsagesAwaitingConnection = usagesToMute;
        final Set<Integer> piidSet = mPlayers.keySet();
        final Iterator<Integer> piidIterator = piidSet.iterator();
        // find which players to mute
        while (piidIterator.hasNext()) {
            final Integer piid = piidIterator.next();
            final AudioPlaybackConfiguration apc = mPlayers.get(piid);
            if (apc == null) {
                continue;
            }
            maybeMutePlayerAwaitingConnection(apc);
        }
    }

    @GuardedBy("mPlayerLock")
    private void maybeMutePlayerAwaitingConnection(@NonNull AudioPlaybackConfiguration apc) {
        if (mMutedUsagesAwaitingConnection == null) {
            return;
        }
        for (int usage : mMutedUsagesAwaitingConnection) {
            if (usage == apc.getAudioAttributes().getUsage()) {
                try {
                    sEventLogger.enqueue((new EventLogger.StringEvent(
                            "awaiting connection: muting piid:"
                                    + apc.getPlayerInterfaceId()
                                    + " uid:" + apc.getClientUid())).printLog(TAG));
                    apc.getPlayerProxy().applyVolumeShaper(
                            MUTE_AWAIT_CONNECTION_VSHAPE,
                            PLAY_SKIP_RAMP);
                    mMutedPlayersAwaitingConnection.add(apc.getPlayerInterfaceId());
                } catch (Exception e) {
                    Log.e(TAG, "awaiting connection: error muting player "
                            + apc.getPlayerInterfaceId(), e);
                }
            }
        }
    }

    @GuardedBy("mPlayerLock")
    private void unmutePlayersExpectingDevice() {
        mMutedUsagesAwaitingConnection = null;
        for (int piid : mMutedPlayersAwaitingConnection) {
            final AudioPlaybackConfiguration apc = mPlayers.get(piid);
            if (apc == null) {
                continue;
            }
            try {
                sEventLogger.enqueue(new EventLogger.StringEvent(
                        "unmuting piid:" + piid).printLog(TAG));
                apc.getPlayerProxy().applyVolumeShaper(MUTE_AWAIT_CONNECTION_VSHAPE,
                        VolumeShaper.Operation.REVERSE);
            } catch (Exception e) {
                Log.e(TAG, "Error unmuting player " + piid + " uid:"
                        + apc.getClientUid(), e);
            }
        }
        mMutedPlayersAwaitingConnection.clear();
    }

    //=================================================================
    // Message handling
    private Handler mEventHandler;
    private HandlerThread mEventThread;

    /**
     * timeout for a mute awaiting a device connection
     * args:
     *     msg.obj: the audio device being expected
     *         type: AudioDeviceAttributes
     */
    private static final int MSG_L_TIMEOUT_MUTE_AWAIT_CONNECTION = 1;

    /**
     * assign new port id to piid
     * args:
     *     msg.arg1: port id
     *     msg.arg2: piid
     */
    private static final int MSG_II_UPDATE_PORT_EVENT = 2;

    /**
     * event for player getting muted
     * args:
     *     msg.arg1: piid
     *     msg.arg2: port id
     *     msg.obj: extras describing the mute reason
     *         type: PersistableBundle
     */
    private static final int MSG_IIL_UPDATE_PLAYER_MUTED_EVENT = 3;

    /**
     * clear all ports assigned to a given piid
     * args:
     *     msg.arg1: the piid
     */
    private static final int MSG_I_CLEAR_PORTS_FOR_PIID = 4;

    /**
     * event for player reporting playback format and spatialization status
     * args:
     *     msg.arg1: piid
     *     msg.arg2: port id
     *     msg.obj: extras describing the sample rate, channel mask, spatialized
     *         type: PersistableBundle
     */
    private static final int MSG_IIL_UPDATE_PLAYER_FORMAT = 5;

    private void initEventHandler() {
        mEventThread = new HandlerThread(TAG);
        mEventThread.start();
        mEventHandler = new Handler(mEventThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_L_TIMEOUT_MUTE_AWAIT_CONNECTION:
                        sEventLogger.enqueueAndLog("Timeout for muting waiting for "
                                + (AudioDeviceAttributes) msg.obj + ", unmuting",
                                EventLogger.Event.ALOGI, TAG);
                        synchronized (mPlayerLock) {
                            unmutePlayersExpectingDevice();
                        }
                        mMuteAwaitConnectionTimeoutCb.accept((AudioDeviceAttributes) msg.obj);
                        break;

                    case MSG_II_UPDATE_PORT_EVENT:
                        synchronized (mPlayerLock) {
                            mPortIdToPiid.put(/*portId*/msg.arg1, /*piid*/msg.arg2);
                        }
                        break;
                    case MSG_IIL_UPDATE_PLAYER_MUTED_EVENT:
                        // TODO: replace PersistableBundle with own struct
                        PersistableBundle extras = (PersistableBundle) msg.obj;
                        if (extras == null) {
                            Log.w(TAG, "Received mute event with no extras");
                            break;
                        }
                        @PlayerMuteEvent int eventValue = extras.getInt(EXTRA_PLAYER_EVENT_MUTE);

                        synchronized (mPlayerLock) {
                            int piid = msg.arg1;

                            sEventLogger.enqueue(
                                    new PlayerEvent(piid, PLAYER_UPDATE_MUTED, eventValue));

                            final AudioPlaybackConfiguration apc;
                            synchronized (mPlayerLock) {
                                apc = mPlayers.get(piid);
                            }
                            if (apc == null || !apc.handleMutedEvent(eventValue)) {
                                break;  // do not dispatch
                            }
                            dispatchPlaybackChange(/* iplayerReleased= */false);
                        }
                        break;

                    case MSG_I_CLEAR_PORTS_FOR_PIID:
                        int piid = msg.arg1;
                        if (piid == AudioPlaybackConfiguration.PLAYER_PIID_INVALID) {
                            Log.w(TAG, "Received clear ports with invalid piid");
                            break;
                        }

                        synchronized (mPlayerLock) {
                            int portIdx;
                            while ((portIdx = mPortIdToPiid.indexOfValue(piid)) >= 0) {
                                mPortIdToPiid.removeAt(portIdx);
                            }
                        }
                        break;

                    case MSG_IIL_UPDATE_PLAYER_FORMAT:
                        final PersistableBundle formatExtras = (PersistableBundle) msg.obj;
                        if (formatExtras == null) {
                            Log.w(TAG, "Received format event with no extras");
                            break;
                        }
                        final boolean spatialized = formatExtras.getBoolean(
                                AudioPlaybackConfiguration.EXTRA_PLAYER_EVENT_SPATIALIZED, false);
                        final int sampleRate = formatExtras.getInt(
                                AudioPlaybackConfiguration.EXTRA_PLAYER_EVENT_SAMPLE_RATE, 0);
                        final int nativeChannelMask = formatExtras.getInt(
                                AudioPlaybackConfiguration.EXTRA_PLAYER_EVENT_CHANNEL_MASK, 0);
                        final FormatInfo format =
                                new FormatInfo(spatialized, nativeChannelMask, sampleRate);

                        sEventLogger.enqueue(new PlayerFormatEvent(msg.arg1, format));

                        final AudioPlaybackConfiguration apc;
                        synchronized (mPlayerLock) {
                            apc = mPlayers.get(msg.arg1);
                        }
                        if (apc == null || !apc.handleFormatEvent(format)) {
                            break;  // do not dispatch
                        }
                        // TODO optimize for no dispatch to non-privileged listeners
                        dispatchPlaybackChange(/* iplayerReleased= */false);
                        break;
                    default:
                        break;
                }
            }
        };
    }
}
