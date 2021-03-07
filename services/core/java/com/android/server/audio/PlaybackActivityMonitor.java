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

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioSystem;
import android.media.IPlaybackConfigDispatcher;
import android.media.PlayerBase;
import android.media.VolumeShaper;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Class to receive and dispatch updates from AudioSystem about recording configurations.
 */
public final class PlaybackActivityMonitor
        implements AudioPlaybackConfiguration.PlayerDeathMonitor, PlayerFocusEnforcer {

    public static final String TAG = "AudioService.PlaybackActivityMonitor";

    /*package*/ static final boolean DEBUG = false;
    /*package*/ static final int VOLUME_SHAPER_SYSTEM_DUCK_ID = 1;
    /*package*/ static final int VOLUME_SHAPER_SYSTEM_FADEOUT_ID = 2;

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
    private static final VolumeShaper.Operation PLAY_CREATE_IF_NEEDED =
            new VolumeShaper.Operation.Builder(VolumeShaper.Operation.PLAY)
                    .createIfNeeded()
                    .build();

    // TODO support VolumeShaper on those players
    private static final int[] UNDUCKABLE_PLAYER_TYPES = {
            AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO,
            AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL,
    };

    // like a PLAY_CREATE_IF_NEEDED operation but with a skip to the end of the ramp
    private static final VolumeShaper.Operation PLAY_SKIP_RAMP =
            new VolumeShaper.Operation.Builder(PLAY_CREATE_IF_NEEDED).setXOffset(1.0f).build();

    private final ArrayList<PlayMonitorClient> mClients = new ArrayList<PlayMonitorClient>();
    // a public client is one that needs an anonymized version of the playback configurations, we
    // keep track of whether there is at least one to know when we need to create the list of
    // playback configurations that do not contain uid/pid/package name information.
    private boolean mHasPublicClients = false;

    private final Object mPlayerLock = new Object();
    private final HashMap<Integer, AudioPlaybackConfiguration> mPlayers =
            new HashMap<Integer, AudioPlaybackConfiguration>();

    private final Context mContext;
    private int mSavedAlarmVolume = -1;
    private final int mMaxAlarmVolume;
    private int mPrivilegedAlarmActiveCount = 0;

    PlaybackActivityMonitor(Context context, int maxAlarmVolume) {
        mContext = context;
        mMaxAlarmVolume = maxAlarmVolume;
        PlayMonitorClient.sListenerDeathMonitor = this;
        AudioPlaybackConfiguration.sPlayerDeathMonitor = this;
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
                        sEventLogger.log(new AudioEventLogger.StringEvent("unbanning uid:" + uid));
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
                        sEventLogger.log(new AudioEventLogger.StringEvent("banning uid:" + uid));
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
        sEventLogger.log(new NewPlayerEvent(apc));
        synchronized(mPlayerLock) {
            mPlayers.put(newPiid, apc);
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
                sEventLogger.log(new AudioAttrEvent(piid, attr));
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
     * @param deviceId The new player device id
     * @param binderUid Calling binder uid
     */
    public void playerEvent(int piid, int event, int deviceId, int binderUid) {
        if (DEBUG) {
            Log.v(TAG, String.format("playerEvent(piid=%d, deviceId=%d, event=%s)",
                    piid, deviceId, AudioPlaybackConfiguration.playerStateToString(event)));
        }
        final boolean change;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (apc == null) {
                return;
            }
            sEventLogger.log(new PlayerEvent(piid, event, deviceId));
            if (event == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                for (Integer uidInteger: mBannedUids) {
                    if (checkBanPlayer(apc, uidInteger.intValue())) {
                        // player was banned, do not update its state
                        sEventLogger.log(new AudioEventLogger.StringEvent(
                                "not starting piid:" + piid + " ,is banned"));
                        return;
                    }
                }
            }
            if (apc.getPlayerType() == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL) {
                // FIXME SoundPool not ready for state reporting
                return;
            }
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                //TODO add generation counter to only update to the latest state
                checkVolumeForPrivilegedAlarm(apc, event);
                change = apc.handleStateEvent(event, deviceId);
            } else {
                Log.e(TAG, "Error handling event " + event);
                change = false;
            }
            if (change && event == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                mDuckingManager.checkDuck(apc);
                mFadingManager.checkFade(apc);
            }
        }
        if (change) {
            dispatchPlaybackChange(event == AudioPlaybackConfiguration.PLAYER_STATE_RELEASED);
        }
    }

    public void playerHasOpPlayAudio(int piid, boolean hasOpPlayAudio, int binderUid) {
        // no check on UID yet because this is only for logging at the moment
        sEventLogger.log(new PlayerOpPlayAudioEvent(piid, hasOpPlayAudio, binderUid));
    }

    public void releasePlayer(int piid, int binderUid) {
        if (DEBUG) { Log.v(TAG, "releasePlayer() for piid=" + piid); }
        boolean change = false;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                sEventLogger.log(new AudioEventLogger.StringEvent(
                        "releasing player piid:" + piid));
                mPlayers.remove(new Integer(piid));
                mDuckingManager.removeReleased(apc);
                mFadingManager.removeReleased(apc);
                checkVolumeForPrivilegedAlarm(apc, AudioPlaybackConfiguration.PLAYER_STATE_RELEASED);
                change = apc.handleStateEvent(AudioPlaybackConfiguration.PLAYER_STATE_RELEASED,
                        AudioPlaybackConfiguration.PLAYER_DEVICEID_INVALID);
            }
        }
        if (change) {
            dispatchPlaybackChange(true /*iplayerreleased*/);
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
     * Return all cached capture policies.
     */
    public HashMap<Integer, Integer> getAllAllowedCapturePolicies() {
        return mAllowedCapturePolicies;
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

    protected void dump(PrintWriter pw) {
        // players
        pw.println("\nPlaybackActivityMonitor dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        synchronized(mPlayerLock) {
            pw.println("\n  playback listeners:");
            synchronized(mClients) {
                for (PlayMonitorClient pmc : mClients) {
                    pw.print(" " + (pmc.mIsPrivileged ? "(S)" : "(P)")
                            + pmc.toString());
                }
            }
            pw.println("\n");
            // all players
            pw.println("\n  players:");
            final List<Integer> piidIntList = new ArrayList<Integer>(mPlayers.keySet());
            Collections.sort(piidIntList);
            for (Integer piidInt : piidIntList) {
                final AudioPlaybackConfiguration apc = mPlayers.get(piidInt);
                if (apc != null) {
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
            pw.print("\n  muted player piids:");
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
        synchronized (mClients) {
            // typical use case, nobody is listening, don't do any work
            if (mClients.isEmpty()) {
                return;
            }
        }
        if (DEBUG) { Log.v(TAG, "dispatchPlaybackChange to " + mClients.size() + " clients"); }
        final List<AudioPlaybackConfiguration> configsSystem;
        // list of playback configurations for "public consumption". It is only computed if there
        // are non-system playback activity listeners.
        final List<AudioPlaybackConfiguration> configsPublic;
        synchronized (mPlayerLock) {
            if (mPlayers.isEmpty()) {
                return;
            }
            configsSystem = new ArrayList<AudioPlaybackConfiguration>(mPlayers.values());
        }
        synchronized (mClients) {
            // was done at beginning of method, but could have changed
            if (mClients.isEmpty()) {
                return;
            }
            configsPublic = mHasPublicClients ? anonymizeForPublicConsumption(configsSystem) : null;
            final Iterator<PlayMonitorClient> clientIterator = mClients.iterator();
            while (clientIterator.hasNext()) {
                final PlayMonitorClient pmc = clientIterator.next();
                try {
                    // do not spam the logs if there are problems communicating with this client
                    if (pmc.mErrorCount < PlayMonitorClient.MAX_ERRORS) {
                        if (pmc.mIsPrivileged) {
                            pmc.mDispatcherCb.dispatchPlaybackConfigChange(configsSystem,
                                    iplayerReleased);
                        } else {
                            // non-system clients don't have the control interface IPlayer, so
                            // they don't need to flush commands when a player was released
                            pmc.mDispatcherCb.dispatchPlaybackConfigChange(configsPublic, false);
                        }
                    }
                } catch (RemoteException e) {
                    pmc.mErrorCount++;
                    Log.e(TAG, "Error (" + pmc.mErrorCount +
                            ") trying to dispatch playback config change to " + pmc, e);
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
            mDuckingManager.duckUid(loser.getClientUid(), apcsToDuck);
        }
        return true;
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
                        sEventLogger.log((new AudioEventLogger.StringEvent("call: muting piid:"
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
                        sEventLogger.log(new AudioEventLogger.StringEvent("call: unmuting piid:"
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
            //###
            //mDuckingManager.duckUid(loser.getClientUid(), apcsToFadeOut);
            if (loserHasActivePlayers) {
                mFadingManager.fadeOutUid(loser.getClientUid(), apcsToFadeOut);
            }
        }

        return loserHasActivePlayers;
    }

    @Override
    public void forgetUid(int uid) {
        mFadingManager.forgetUid(uid);
    }

    //=================================================================
    // Track playback activity listeners

    void registerPlaybackCallback(IPlaybackConfigDispatcher pcdb, boolean isPrivileged) {
        if (pcdb == null) {
            return;
        }
        synchronized(mClients) {
            final PlayMonitorClient pmc = new PlayMonitorClient(pcdb, isPrivileged);
            if (pmc.init()) {
                if (!isPrivileged) {
                    mHasPublicClients = true;
                }
                mClients.add(pmc);
            }
        }
    }

    void unregisterPlaybackCallback(IPlaybackConfigDispatcher pcdb) {
        if (pcdb == null) {
            return;
        }
        synchronized(mClients) {
            final Iterator<PlayMonitorClient> clientIterator = mClients.iterator();
            boolean hasPublicClients = false;
            // iterate over the clients to remove the dispatcher to remove, and reevaluate at
            // the same time if we still have a public client.
            while (clientIterator.hasNext()) {
                PlayMonitorClient pmc = clientIterator.next();
                if (pcdb.equals(pmc.mDispatcherCb)) {
                    pmc.release();
                    clientIterator.remove();
                } else {
                    if (!pmc.mIsPrivileged) {
                        hasPublicClients = true;
                    }
                }
            }
            mHasPublicClients = hasPublicClients;
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

        final IPlaybackConfigDispatcher mDispatcherCb;
        final boolean mIsPrivileged;

        int mErrorCount = 0;
        // number of errors after which we don't update this client anymore to not spam the logs
        static final int MAX_ERRORS = 5;

        PlayMonitorClient(IPlaybackConfigDispatcher pcdb, boolean isPrivileged) {
            mDispatcherCb = pcdb;
            mIsPrivileged = isPrivileged;
        }

        public void binderDied() {
            Log.w(TAG, "client died");
            sListenerDeathMonitor.unregisterPlaybackCallback(mDispatcherCb);
        }

        boolean init() {
            try {
                mDispatcherCb.asBinder().linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "Could not link to client death", e);
                return false;
            }
        }

        void release() {
            mDispatcherCb.asBinder().unlinkToDeath(this, 0);
        }
    }

    //=================================================================
    // Class to handle ducking related operations for a given UID
    private static final class DuckingManager {
        private final HashMap<Integer, DuckedApp> mDuckers = new HashMap<Integer, DuckedApp>();

        synchronized void duckUid(int uid, ArrayList<AudioPlaybackConfiguration> apcsToDuck) {
            if (DEBUG) {  Log.v(TAG, "DuckingManager: duckUid() uid:"+ uid); }
            if (!mDuckers.containsKey(uid)) {
                mDuckers.put(uid, new DuckedApp(uid));
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
            private final ArrayList<Integer> mDuckedPlayers = new ArrayList<Integer>();

            DuckedApp(int uid) {
                mUid = uid;
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
                    sEventLogger.log((new DuckEvent(apc, skipRamp)).printLog(TAG));
                    apc.getPlayerProxy().applyVolumeShaper(
                            DUCK_VSHAPE,
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
                            sEventLogger.log((new AudioEventLogger.StringEvent("unducking piid:"
                                    + piid)).printLog(TAG));
                            apc.getPlayerProxy().applyVolumeShaper(
                                    DUCK_ID,
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
    private final static class PlayerEvent extends AudioEventLogger.Event {
        // only keeping the player interface ID as it uniquely identifies the player in the event
        final int mPlayerIId;
        final int mState;
        final int mDeviceId;

        PlayerEvent(int piid, int state, int deviceId) {
            mPlayerIId = piid;
            mState = state;
            mDeviceId = deviceId;
        }

        @Override
        public String eventToString() {
            return new StringBuilder("player piid:").append(mPlayerIId).append(" state:")
                    .append(AudioPlaybackConfiguration.toLogFriendlyPlayerState(mState))
                    .append(" DeviceId:").append(mDeviceId).toString();
        }
    }

    private final static class PlayerOpPlayAudioEvent extends AudioEventLogger.Event {
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

    private final static class NewPlayerEvent extends AudioEventLogger.Event {
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

    private abstract static class VolumeShaperEvent extends AudioEventLogger.Event {
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
        @Override
        String getVSAction() {
            return "ducking";
        }

        DuckEvent(@NonNull AudioPlaybackConfiguration apc, boolean skipRamp) {
            super(apc, skipRamp);
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

    private static final class AudioAttrEvent extends AudioEventLogger.Event {
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

    static final AudioEventLogger sEventLogger = new AudioEventLogger(100,
            "playback activity as reported through PlayerBase");
}
