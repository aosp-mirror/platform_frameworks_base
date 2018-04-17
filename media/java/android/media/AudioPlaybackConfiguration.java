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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * The AudioPlaybackConfiguration class collects the information describing an audio playback
 * session.
 */
public final class AudioPlaybackConfiguration implements Parcelable {
    private static final String TAG = new String("AudioPlaybackConfiguration");

    private static final boolean DEBUG = false;

    /** @hide */
    public static final int PLAYER_PIID_INVALID = -1;
    /** @hide */
    public static final int PLAYER_PIID_UNASSIGNED = 0;
    /** @hide */
    public static final int PLAYER_UPID_INVALID = -1;

    // information about the implementation
    /**
     * @hide
     * An unknown type of player
     */
    @SystemApi
    public static final int PLAYER_TYPE_UNKNOWN = -1;
    /**
     * @hide
     * Player backed by a java android.media.AudioTrack player
     */
    @SystemApi
    public static final int PLAYER_TYPE_JAM_AUDIOTRACK = 1;
    /**
     * @hide
     * Player backed by a java android.media.MediaPlayer player
     */
    @SystemApi
    public static final int PLAYER_TYPE_JAM_MEDIAPLAYER = 2;
    /**
     * @hide
     * Player backed by a java android.media.SoundPool player
     */
    @SystemApi
    public static final int PLAYER_TYPE_JAM_SOUNDPOOL = 3;
    /**
     * @hide
     * Player backed by a C OpenSL ES AudioPlayer player with a BufferQueue source
     */
    @SystemApi
    public static final int PLAYER_TYPE_SLES_AUDIOPLAYER_BUFFERQUEUE = 11;
    /**
     * @hide
     * Player backed by a C OpenSL ES AudioPlayer player with a URI or FD source
     */
    @SystemApi
    public static final int PLAYER_TYPE_SLES_AUDIOPLAYER_URI_FD = 12;

    /**
     * @hide
     * Player backed an AAudio player.
     * Note this type is not in System API so it will not be returned in public API calls
     */
    // TODO unhide for SystemApi, update getPlayerType()
    public static final int PLAYER_TYPE_AAUDIO = 13;

    /**
     * @hide
     * Player backed a hardware source, whose state is visible in the Android audio policy manager.
     * Note this type is not in System API so it will not be returned in public API calls
     */
    // TODO unhide for SystemApi, update getPlayerType()
    public static final int PLAYER_TYPE_HW_SOURCE = 14;

    /**
     * @hide
     * Player is a proxy for an audio player whose audio and state doesn't go through the Android
     * audio framework.
     * Note this type is not in System API so it will not be returned in public API calls
     */
    // TODO unhide for SystemApi, update getPlayerType()
    public static final int PLAYER_TYPE_EXTERNAL_PROXY = 15;

    /** @hide */
    @IntDef({
        PLAYER_TYPE_UNKNOWN,
        PLAYER_TYPE_JAM_AUDIOTRACK,
        PLAYER_TYPE_JAM_MEDIAPLAYER,
        PLAYER_TYPE_JAM_SOUNDPOOL,
        PLAYER_TYPE_SLES_AUDIOPLAYER_BUFFERQUEUE,
        PLAYER_TYPE_SLES_AUDIOPLAYER_URI_FD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlayerType {}

    /**
     * @hide
     * An unknown player state
     */
    @SystemApi
    public static final int PLAYER_STATE_UNKNOWN = -1;
    /**
     * @hide
     * The resources of the player have been released, it cannot play anymore
     */
    @SystemApi
    public static final int PLAYER_STATE_RELEASED = 0;
    /**
     * @hide
     * The state of a player when it's created
     */
    @SystemApi
    public static final int PLAYER_STATE_IDLE = 1;
    /**
     * @hide
     * The state of a player that is actively playing
     */
    @SystemApi
    public static final int PLAYER_STATE_STARTED = 2;
    /**
     * @hide
     * The state of a player where playback is paused
     */
    @SystemApi
    public static final int PLAYER_STATE_PAUSED = 3;
    /**
     * @hide
     * The state of a player where playback is stopped
     */
    @SystemApi
    public static final int PLAYER_STATE_STOPPED = 4;

    /** @hide */
    @IntDef({
        PLAYER_STATE_UNKNOWN,
        PLAYER_STATE_RELEASED,
        PLAYER_STATE_IDLE,
        PLAYER_STATE_STARTED,
        PLAYER_STATE_PAUSED,
        PLAYER_STATE_STOPPED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlayerState {}

    // immutable data
    private final int mPlayerIId;

    // not final due to anonymization step
    private int mPlayerType;
    private int mClientUid;
    private int mClientPid;
    // the IPlayer reference and death monitor
    private IPlayerShell mIPlayerShell;

    private int mPlayerState;
    private AudioAttributes mPlayerAttr; // never null

    /**
     * Never use without initializing parameters afterwards
     */
    private AudioPlaybackConfiguration(int piid) {
        mPlayerIId = piid;
        mIPlayerShell = null;
    }

    /**
     * @hide
     */
    public AudioPlaybackConfiguration(PlayerBase.PlayerIdCard pic, int piid, int uid, int pid) {
        if (DEBUG) { Log.d(TAG, "new: piid=" + piid + " iplayer=" + pic.mIPlayer); }
        mPlayerIId = piid;
        mPlayerType = pic.mPlayerType;
        mClientUid = uid;
        mClientPid = pid;
        mPlayerState = PLAYER_STATE_IDLE;
        mPlayerAttr = pic.mAttributes;
        if ((sPlayerDeathMonitor != null) && (pic.mIPlayer != null)) {
            mIPlayerShell = new IPlayerShell(this, pic.mIPlayer);
        } else {
            mIPlayerShell = null;
        }
    }

    /**
     * @hide
     */
    public void init() {
        synchronized (this) {
            if (mIPlayerShell != null) {
                mIPlayerShell.monitorDeath();
            }
        }
    }

    // Note that this method is called server side, so no "privileged" information is ever sent
    // to a client that is not supposed to have access to it.
    /**
     * @hide
     * Creates a copy of the playback configuration that is stripped of any data enabling
     * identification of which application it is associated with ("anonymized").
     * @param toSanitize
     */
    public static AudioPlaybackConfiguration anonymizedCopy(AudioPlaybackConfiguration in) {
        final AudioPlaybackConfiguration anonymCopy = new AudioPlaybackConfiguration(in.mPlayerIId);
        anonymCopy.mPlayerState = in.mPlayerState;
        // do not reuse the full attributes: only usage, content type and public flags are allowed
        anonymCopy.mPlayerAttr = new AudioAttributes.Builder()
                .setUsage(in.mPlayerAttr.getUsage())
                .setContentType(in.mPlayerAttr.getContentType())
                .setFlags(in.mPlayerAttr.getFlags())
                .build();
        // anonymized data
        anonymCopy.mPlayerType = PLAYER_TYPE_UNKNOWN;
        anonymCopy.mClientUid = PLAYER_UPID_INVALID;
        anonymCopy.mClientPid = PLAYER_UPID_INVALID;
        anonymCopy.mIPlayerShell = null;
        return anonymCopy;
    }

    /**
     * Return the {@link AudioAttributes} of the corresponding player.
     * @return the audio attributes of the player
     */
    public AudioAttributes getAudioAttributes() {
        return mPlayerAttr;
    }

    /**
     * @hide
     * Return the uid of the client application that created this player.
     * @return the uid of the client
     */
    @SystemApi
    public int getClientUid() {
        return mClientUid;
    }

    /**
     * @hide
     * Return the pid of the client application that created this player.
     * @return the pid of the client
     */
    @SystemApi
    public int getClientPid() {
        return mClientPid;
    }

    /**
     * @hide
     * Return the type of player linked to this configuration. The return value is one of
     * {@link #PLAYER_TYPE_JAM_AUDIOTRACK}, {@link #PLAYER_TYPE_JAM_MEDIAPLAYER},
     * {@link #PLAYER_TYPE_JAM_SOUNDPOOL}, {@link #PLAYER_TYPE_SLES_AUDIOPLAYER_BUFFERQUEUE},
     * {@link #PLAYER_TYPE_SLES_AUDIOPLAYER_URI_FD}, or {@link #PLAYER_TYPE_UNKNOWN}.
     * <br>Note that player types not exposed in the system API will be represented as
     * {@link #PLAYER_TYPE_UNKNOWN}.
     * @return the type of the player.
     */
    @SystemApi
    public @PlayerType int getPlayerType() {
        switch (mPlayerType) {
            case PLAYER_TYPE_AAUDIO:
            case PLAYER_TYPE_HW_SOURCE:
            case PLAYER_TYPE_EXTERNAL_PROXY:
                return PLAYER_TYPE_UNKNOWN;
            default:
                return mPlayerType;
        }
    }

    /**
     * @hide
     * Return the current state of the player linked to this configuration. The return value is one
     * of {@link #PLAYER_STATE_IDLE}, {@link #PLAYER_STATE_PAUSED}, {@link #PLAYER_STATE_STARTED},
     * {@link #PLAYER_STATE_STOPPED}, {@link #PLAYER_STATE_RELEASED} or
     * {@link #PLAYER_STATE_UNKNOWN}.
     * @return the state of the player.
     */
    @SystemApi
    public @PlayerState int getPlayerState() {
        return mPlayerState;
    }

    /**
     * @hide
     * Return an identifier unique for the lifetime of the player.
     * @return a player interface identifier
     */
    @SystemApi
    public int getPlayerInterfaceId() {
        return mPlayerIId;
    }

    /**
     * @hide
     * Return a proxy for the player associated with this playback configuration
     * @return a proxy player
     */
    @SystemApi
    public PlayerProxy getPlayerProxy() {
        final IPlayerShell ips;
        synchronized (this) {
            ips = mIPlayerShell;
        }
        return ips == null ? null : new PlayerProxy(this);
    }

    /**
     * @hide
     * @return the IPlayer interface for the associated player
     */
    IPlayer getIPlayer() {
        final IPlayerShell ips;
        synchronized (this) {
            ips = mIPlayerShell;
        }
        return ips == null ? null : ips.getIPlayer();
    }

    /**
     * @hide
     * Handle a change of audio attributes
     * @param attr
     */
    public boolean handleAudioAttributesEvent(@NonNull AudioAttributes attr) {
        final boolean changed = !attr.equals(mPlayerAttr);
        mPlayerAttr = attr;
        return changed;
    }

    /**
     * @hide
     * Handle a player state change
     * @param event
     * @return true if the state changed, false otherwise
     */
    public boolean handleStateEvent(int event) {
        final boolean changed;
        synchronized (this) {
            changed = (mPlayerState != event);
            mPlayerState = event;
            if (changed && (event == PLAYER_STATE_RELEASED) && (mIPlayerShell != null)) {
                mIPlayerShell.release();
                mIPlayerShell = null;
            }
        }
        return changed;
    }

    // To report IPlayer death from death recipient
    /** @hide */
    public interface PlayerDeathMonitor {
        public void playerDeath(int piid);
    }
    /** @hide */
    public static PlayerDeathMonitor sPlayerDeathMonitor;

    private void playerDied() {
        if (sPlayerDeathMonitor != null) {
            sPlayerDeathMonitor.playerDeath(mPlayerIId);
        }
    }

    /**
     * @hide
     * Returns true if the player is considered "active", i.e. actively playing, and thus
     * in a state that should make it considered for the list public (sanitized) active playback
     * configurations
     * @return true if active
     */
    public boolean isActive() {
        switch (mPlayerState) {
            case PLAYER_STATE_STARTED:
                return true;
            case PLAYER_STATE_UNKNOWN:
            case PLAYER_STATE_RELEASED:
            case PLAYER_STATE_IDLE:
            case PLAYER_STATE_PAUSED:
            case PLAYER_STATE_STOPPED:
            default:
                return false;
        }
    }

    /**
     * @hide
     * For AudioService dump
     * @param pw
     */
    public void dump(PrintWriter pw) {
        pw.println("  " + toLogFriendlyString(this));
    }

    /**
     * @hide
     */
    public static String toLogFriendlyString(AudioPlaybackConfiguration apc) {
        return new String("ID:" + apc.mPlayerIId
                + " -- type:" + toLogFriendlyPlayerType(apc.mPlayerType)
                + " -- u/pid:" + apc.mClientUid +"/" + apc.mClientPid
                + " -- state:" + toLogFriendlyPlayerState(apc.mPlayerState)
                + " -- attr:" + apc.mPlayerAttr);
    }

    public static final Parcelable.Creator<AudioPlaybackConfiguration> CREATOR
            = new Parcelable.Creator<AudioPlaybackConfiguration>() {
        /**
         * Rebuilds an AudioPlaybackConfiguration previously stored with writeToParcel().
         * @param p Parcel object to read the AudioPlaybackConfiguration from
         * @return a new AudioPlaybackConfiguration created from the data in the parcel
         */
        public AudioPlaybackConfiguration createFromParcel(Parcel p) {
            return new AudioPlaybackConfiguration(p);
        }
        public AudioPlaybackConfiguration[] newArray(int size) {
            return new AudioPlaybackConfiguration[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mPlayerIId, mPlayerType, mClientUid, mClientPid);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPlayerIId);
        dest.writeInt(mPlayerType);
        dest.writeInt(mClientUid);
        dest.writeInt(mClientPid);
        dest.writeInt(mPlayerState);
        mPlayerAttr.writeToParcel(dest, 0);
        final IPlayerShell ips;
        synchronized (this) {
            ips = mIPlayerShell;
        }
        dest.writeStrongInterface(ips == null ? null : ips.getIPlayer());
    }

    private AudioPlaybackConfiguration(Parcel in) {
        mPlayerIId = in.readInt();
        mPlayerType = in.readInt();
        mClientUid = in.readInt();
        mClientPid = in.readInt();
        mPlayerState = in.readInt();
        mPlayerAttr = AudioAttributes.CREATOR.createFromParcel(in);
        final IPlayer p = IPlayer.Stub.asInterface(in.readStrongBinder());
        mIPlayerShell = (p == null) ? null : new IPlayerShell(null, p);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof AudioPlaybackConfiguration)) return false;

        AudioPlaybackConfiguration that = (AudioPlaybackConfiguration) o;

        return ((mPlayerIId == that.mPlayerIId)
                && (mPlayerType == that.mPlayerType)
                && (mClientUid == that.mClientUid)
                && (mClientPid == that.mClientPid));
    }

    //=====================================================================
    // Inner class for corresponding IPlayer and its death monitoring
    static final class IPlayerShell implements IBinder.DeathRecipient {

        final AudioPlaybackConfiguration mMonitor; // never null
        private volatile IPlayer mIPlayer;

        IPlayerShell(@NonNull AudioPlaybackConfiguration monitor, @NonNull IPlayer iplayer) {
            mMonitor = monitor;
            mIPlayer = iplayer;
        }

        synchronized void monitorDeath() {
            if (mIPlayer == null) {
                return;
            }
            try {
                mIPlayer.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                if (mMonitor != null) {
                    Log.w(TAG, "Could not link to client death for piid=" + mMonitor.mPlayerIId, e);
                } else {
                    Log.w(TAG, "Could not link to client death", e);
                }
            }
        }

        IPlayer getIPlayer() {
            return mIPlayer;
        }

        public void binderDied() {
            if (mMonitor != null) {
                if (DEBUG) { Log.i(TAG, "IPlayerShell binderDied for piid=" + mMonitor.mPlayerIId);}
                mMonitor.playerDied();
            } else if (DEBUG) { Log.i(TAG, "IPlayerShell binderDied"); }
        }

        synchronized void release() {
            if (mIPlayer == null) {
                return;
            }
            mIPlayer.asBinder().unlinkToDeath(this, 0);
            mIPlayer = null;
            Binder.flushPendingCommands();
        }
    }

    //=====================================================================
    // Utilities

    /** @hide */
    public static String toLogFriendlyPlayerType(int type) {
        switch (type) {
            case PLAYER_TYPE_UNKNOWN: return "unknown";
            case PLAYER_TYPE_JAM_AUDIOTRACK: return "android.media.AudioTrack";
            case PLAYER_TYPE_JAM_MEDIAPLAYER: return "android.media.MediaPlayer";
            case PLAYER_TYPE_JAM_SOUNDPOOL:   return "android.media.SoundPool";
            case PLAYER_TYPE_SLES_AUDIOPLAYER_BUFFERQUEUE:
                return "OpenSL ES AudioPlayer (Buffer Queue)";
            case PLAYER_TYPE_SLES_AUDIOPLAYER_URI_FD:
                return "OpenSL ES AudioPlayer (URI/FD)";
            case PLAYER_TYPE_AAUDIO: return "AAudio";
            case PLAYER_TYPE_HW_SOURCE: return "hardware source";
            case PLAYER_TYPE_EXTERNAL_PROXY: return "external proxy";
            default:
                return "unknown player type " + type + " - FIXME";
        }
    }

    /** @hide */
    public static String toLogFriendlyPlayerState(int state) {
        switch (state) {
            case PLAYER_STATE_UNKNOWN: return "unknown";
            case PLAYER_STATE_RELEASED: return "released";
            case PLAYER_STATE_IDLE: return "idle";
            case PLAYER_STATE_STARTED: return "started";
            case PLAYER_STATE_PAUSED: return "paused";
            case PLAYER_STATE_STOPPED: return "stopped";
            default:
                return "unknown player state - FIXME";
        }
    }
}
