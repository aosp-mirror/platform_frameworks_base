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

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
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

    private static final boolean DEBUG = false;
    private static final int VOLUME_SHAPER_SYSTEM_DUCK_ID = 1;

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

    PlaybackActivityMonitor() {
        PlayMonitorClient.sListenerDeathMonitor = this;
        AudioPlaybackConfiguration.sPlayerDeathMonitor = this;
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
        synchronized(mPlayerLock) {
            mPlayers.put(newPiid, apc);
        }
        return newPiid;
    }

    public void playerAttributes(int piid, @NonNull AudioAttributes attr, int binderUid) {
        final boolean change;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                change = apc.handleAudioAttributesEvent(attr);
            } else {
                Log.e(TAG, "Error updating audio attributes");
                change = false;
            }
        }
        if (change) {
            dispatchPlaybackChange();
        }
    }

    public void playerEvent(int piid, int event, int binderUid) {
        if (DEBUG) { Log.v(TAG, String.format("playerEvent(piid=%d, event=%d)", piid, event)); }
        final boolean change;
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (apc == null) {
                return;
            }
            if (apc.getPlayerType() == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL) {
                // FIXME SoundPool not ready for state reporting
                return;
            }
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                //TODO add generation counter to only update to the latest state
                change = apc.handleStateEvent(event);
            } else {
                Log.e(TAG, "Error handling event " + event);
                change = false;
            }
            if (change && event == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                mDuckingManager.checkDuck(apc);
            }
        }
        if (change) {
            dispatchPlaybackChange();
        }
    }

    public void releasePlayer(int piid, int binderUid) {
        if (DEBUG) { Log.v(TAG, "releasePlayer() for piid=" + piid); }
        synchronized(mPlayerLock) {
            final AudioPlaybackConfiguration apc = mPlayers.get(new Integer(piid));
            if (checkConfigurationCaller(piid, apc, binderUid)) {
                mPlayers.remove(new Integer(piid));
                mDuckingManager.removeReleased(apc);
            }
        }
    }

    // Implementation of AudioPlaybackConfiguration.PlayerDeathMonitor
    @Override
    public void playerDeath(int piid) {
        releasePlayer(piid, 0);
    }

    protected void dump(PrintWriter pw) {
        // players
        pw.println("\nPlaybackActivityMonitor dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        synchronized(mPlayerLock) {
            for (AudioPlaybackConfiguration conf : mPlayers.values()) {
                conf.dump(pw);
            }
            // ducked players
            pw.println("\n  ducked players:");
            mDuckingManager.dump(pw);
            // players muted due to the device ringing or being in a call
            pw.println("\n  muted player piids:");
            for (int piid : mMutedPlayers) {
                pw.println(" " + piid);
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

    private void dispatchPlaybackChange() {
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
                            pmc.mDispatcherCb.dispatchPlaybackConfigChange(configsSystem);
                        } else {
                            pmc.mDispatcherCb.dispatchPlaybackConfigChange(configsPublic);
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
    public boolean duckPlayers(FocusRequester winner, FocusRequester loser) {
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
                    if (apc.getAudioAttributes().getContentType() ==
                            AudioAttributes.CONTENT_TYPE_SPEECH) {
                        // the player is speaking, ducking will make the speech unintelligible
                        // so let the app handle it instead
                        Log.v(TAG, "not ducking player " + apc.getPlayerInterfaceId()
                                + " uid:" + apc.getClientUid() + " pid:" + apc.getClientPid()
                                + " - SPEECH");
                        return false;
                    } else if (apc.getPlayerType()
                            == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL) {
                        // TODO support ducking of SoundPool players
                        Log.v(TAG, "not ducking player " + apc.getPlayerInterfaceId()
                                + " uid:" + apc.getClientUid() + " pid:" + apc.getClientPid()
                                + " - SoundPool");
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
    public void unduckPlayers(FocusRequester winner) {
        if (DEBUG) { Log.v(TAG, "unduckPlayers: uids winner=" + winner.getClientUid()); }
        synchronized (mPlayerLock) {
            mDuckingManager.unduckUid(winner.getClientUid(), mPlayers);
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
                        Log.v(TAG, "call: muting player" + piid + " uid:" + apc.getClientUid());
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
                        Log.v(TAG, "call: unmuting player" + piid + " uid:" + apc.getClientUid());
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
                    Log.v(TAG, "ducking (skipRamp=" + skipRamp + ") player piid:"
                            + apc.getPlayerInterfaceId() + " uid:" + mUid);
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
                            Log.v(TAG, "unducking player " + piid + " uid:" + mUid);
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
}
