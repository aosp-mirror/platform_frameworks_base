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

import static android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL;
import static android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE;
import static android.media.audio.Flags.FLAG_MUTED_BY_PORT_VOLUME_API;
import static android.media.audio.Flags.FLAG_ROUTED_DEVICE_IDS;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    public static final int PLAYER_UPID_INVALID = -1;
    /** @hide */
    public static final int PLAYER_DEVICEID_INVALID = 0;
    /** @hide */
    public static final int[] PLAYER_DEVICEIDS_INVALID = new int[0];

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
     */
    @SystemApi
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
    /**
     * @hide
     * The state used to update device id, does not actually change the state of the player
     */
    public static final int PLAYER_UPDATE_DEVICE_ID = 5;
    /**
     * @hide
     * The state used to update port id, does not actually change the state of the player
     */
    public static final int PLAYER_UPDATE_PORT_ID = 6;
    /**
     * @hide
     * Used to update the mute state of a player through its port id
     */
    public static final int PLAYER_UPDATE_MUTED = 7;
    /**
     * @hide
     * Used to update the spatialization status and format of a player through its port id
     */
    public static final int PLAYER_UPDATE_FORMAT = 8;

    /** @hide */
    @IntDef({
        PLAYER_STATE_UNKNOWN,
        PLAYER_STATE_RELEASED,
        PLAYER_STATE_IDLE,
        PLAYER_STATE_STARTED,
        PLAYER_STATE_PAUSED,
        PLAYER_STATE_STOPPED,
        PLAYER_UPDATE_DEVICE_ID,
        PLAYER_UPDATE_PORT_ID,
        PLAYER_UPDATE_MUTED,
        PLAYER_UPDATE_FORMAT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlayerState {}

    /** @hide */
    public static String playerStateToString(@PlayerState int state) {
        switch (state) {
            case PLAYER_STATE_UNKNOWN: return "PLAYER_STATE_UNKNOWN";
            case PLAYER_STATE_RELEASED: return "PLAYER_STATE_RELEASED";
            case PLAYER_STATE_IDLE: return "PLAYER_STATE_IDLE";
            case PLAYER_STATE_STARTED: return "PLAYER_STATE_STARTED";
            case PLAYER_STATE_PAUSED: return "PLAYER_STATE_PAUSED";
            case PLAYER_STATE_STOPPED: return "PLAYER_STATE_STOPPED";
            case PLAYER_UPDATE_DEVICE_ID: return "PLAYER_UPDATE_DEVICE_ID";
            case PLAYER_UPDATE_PORT_ID: return "PLAYER_UPDATE_PORT_ID";
            case PLAYER_UPDATE_MUTED: return "PLAYER_UPDATE_MUTED";
            case PLAYER_UPDATE_FORMAT: return "PLAYER_UPDATE_FORMAT";
            default:
                return "invalid state " + state;
        }
    }

    /**
     * @hide
     * Used to update the spatialization status of a player through its port ID. Must be kept in
     * sync with frameworks/native/include/audiomanager/AudioManager.h
     */
    public static final String EXTRA_PLAYER_EVENT_SPATIALIZED =
            "android.media.extra.PLAYER_EVENT_SPATIALIZED";
    /**
     * @hide
     * Used to update the sample rate of a player through its port ID. Must be kept in sync with
     * frameworks/native/include/audiomanager/AudioManager.h
     */
    public static final String EXTRA_PLAYER_EVENT_SAMPLE_RATE =
            "android.media.extra.PLAYER_EVENT_SAMPLE_RATE";
    /**
     * @hide
     * Used to update the channel mask of a player through its port ID. Must be kept in sync with
     * frameworks/native/include/audiomanager/AudioManager.h
     */
    public static final String EXTRA_PLAYER_EVENT_CHANNEL_MASK =
            "android.media.extra.PLAYER_EVENT_CHANNEL_MASK";
    /**
     * @hide
     * Used to update the mute state of a player through its port ID. Must be kept in sync with
     * frameworks/native/include/audiomanager/AudioManager.h
     */
    public static final String EXTRA_PLAYER_EVENT_MUTE =
            "android.media.extra.PLAYER_EVENT_MUTE";

    /**
     * @hide
     * Flag used when muted by master volume.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_MASTER = (1 << 0);
    /**
     * @hide
     * Flag used when muted by stream volume.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_STREAM_VOLUME = (1 << 1);
    /**
     * @hide
     * Flag used when muted by stream mute.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_STREAM_MUTED = (1 << 2);
    /**
     * @hide
     * Flag used when playback is muted by AppOpsManager#OP_PLAY_AUDIO.
     */
    @SystemApi
    @FlaggedApi(FLAG_MUTED_BY_PORT_VOLUME_API)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_OP_PLAY_AUDIO = (1 << 3);
    /**
     * @hide
     * Flag used when playback is muted by AppOpsManager#OP_PLAY_AUDIO.
     * @deprecated see {@link MUTED_BY_OP_PLAY_AUDIO}
     */
    @SystemApi
    @Deprecated
    @FlaggedApi(FLAG_MUTED_BY_PORT_VOLUME_API)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_APP_OPS = MUTED_BY_OP_PLAY_AUDIO;
    /**
     * @hide
     * Flag used when muted by client volume.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_CLIENT_VOLUME = (1 << 4);
    /**
     * @hide
     * Flag used when muted by volume shaper.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_VOLUME_SHAPER = (1 << 5);
    /**
     * @hide
     * Flag used when muted by the track's port volume.
     *
     * <p>Note: this will replace the stream volume mute when using the AudioFlinger port volume
     * APIs
     */
    @SystemApi
    @FlaggedApi(FLAG_MUTED_BY_PORT_VOLUME_API)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_PORT_VOLUME = (1 << 6);

    /**
     * @hide
     * Flag used when playback is muted by AppOpsManager#OP_CONTROL_AUDIO.
     */
    @SystemApi
    @FlaggedApi(FLAG_MUTED_BY_PORT_VOLUME_API)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int MUTED_BY_OP_CONTROL_AUDIO = (1 << 7);

    /** @hide */
    @IntDef(
            flag = true,
            value = {MUTED_BY_MASTER, MUTED_BY_STREAM_VOLUME, MUTED_BY_STREAM_MUTED,
                    MUTED_BY_OP_PLAY_AUDIO, MUTED_BY_CLIENT_VOLUME, MUTED_BY_VOLUME_SHAPER,
                    MUTED_BY_PORT_VOLUME, MUTED_BY_OP_CONTROL_AUDIO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlayerMuteEvent {
    }

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

    // lock for updateable properties
    private final Object mUpdateablePropLock = new Object();

    @GuardedBy("mUpdateablePropLock")
    private @NonNull int[] mDeviceIds = AudioPlaybackConfiguration.PLAYER_DEVICEIDS_INVALID;
    @GuardedBy("mUpdateablePropLock")
    private int mSessionId;
    @GuardedBy("mUpdateablePropLock")
    private @NonNull FormatInfo mFormatInfo;
    @GuardedBy("mUpdateablePropLock")
    @PlayerMuteEvent private int mMutedState;

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
        if (DEBUG) {
            Log.d(TAG, "new: piid=" + piid + " iplayer=" + pic.mIPlayer
                    + " sessionId=" + pic.mSessionId);
        }
        mPlayerIId = piid;
        mPlayerType = pic.mPlayerType;
        mClientUid = uid;
        mClientPid = pid;
        mMutedState = 0;
        mDeviceIds = AudioPlaybackConfiguration.PLAYER_DEVICEIDS_INVALID;
        mPlayerState = PLAYER_STATE_IDLE;
        mPlayerAttr = pic.mAttributes;
        if ((sPlayerDeathMonitor != null) && (pic.mIPlayer != null)) {
            mIPlayerShell = new IPlayerShell(this, pic.mIPlayer);
        } else {
            mIPlayerShell = null;
        }
        mSessionId = pic.mSessionId;
        mFormatInfo = FormatInfo.DEFAULT;
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

    // sets the fields that are updateable and require synchronization
    private void setUpdateableFields(int[] deviceIds, int sessionId, int mutedState,
            FormatInfo format)
    {
        synchronized (mUpdateablePropLock) {
            mDeviceIds = deviceIds;
            mSessionId = sessionId;
            mMutedState = mutedState;
            mFormatInfo = format;
        }
    }
    // Note that this method is called server side, so no "privileged" information is ever sent
    // to a client that is not supposed to have access to it.
    /**
     * @hide
     * Creates a copy of the playback configuration that is stripped of any data enabling
     * identification of which application it is associated with ("anonymized").
     * @param in the instance to copy from
     */
    public static AudioPlaybackConfiguration anonymizedCopy(AudioPlaybackConfiguration in) {
        final AudioPlaybackConfiguration anonymCopy = new AudioPlaybackConfiguration(in.mPlayerIId);
        anonymCopy.mPlayerState = in.mPlayerState;
        // do not reuse the full attributes: only usage, content type and public flags are allowed
        AudioAttributes.Builder builder = new AudioAttributes.Builder()
                .setContentType(in.mPlayerAttr.getContentType())
                .setFlags(in.mPlayerAttr.getFlags())
                .setAllowedCapturePolicy(
                        in.mPlayerAttr.getAllowedCapturePolicy() == ALLOW_CAPTURE_BY_ALL
                                ? ALLOW_CAPTURE_BY_ALL : ALLOW_CAPTURE_BY_NONE);
        if (AudioAttributes.isSystemUsage(in.mPlayerAttr.getSystemUsage())) {
            builder.setSystemUsage(in.mPlayerAttr.getSystemUsage());
        } else {
            builder.setUsage(in.mPlayerAttr.getUsage());
        }
        anonymCopy.mPlayerAttr = builder.build();
        // anonymized data
        anonymCopy.mPlayerType = PLAYER_TYPE_UNKNOWN;
        anonymCopy.mClientUid = PLAYER_UPID_INVALID;
        anonymCopy.mClientPid = PLAYER_UPID_INVALID;
        anonymCopy.mIPlayerShell = null;
        anonymCopy.setUpdateableFields(
                /*deviceIds*/ new int[0],
                /*sessionId*/ AudioSystem.AUDIO_SESSION_ALLOCATE,
                /*mutedState*/ 0,
                FormatInfo.DEFAULT);
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
     * Returns information about the {@link AudioDeviceInfo} used for this playback.
     * @return the audio playback device or null if the device is not available at the time of
     * query.
     * @deprecated this information was never populated
     */
    @Deprecated
    @FlaggedApi(FLAG_ROUTED_DEVICE_IDS)
    public @Nullable AudioDeviceInfo getAudioDeviceInfo() {
        final int[] deviceIds;
        synchronized (mUpdateablePropLock) {
            deviceIds = mDeviceIds;
        }
        if (deviceIds.length == 0) {
            return null;
        }
        return AudioManager.getDeviceForPortId(deviceIds[0], AudioManager.GET_DEVICES_OUTPUTS);
    }

    /**
     * @hide
     * Returns information about the List of {@link AudioDeviceInfo} used for this playback.
     * @return the audio playback devices
     */
    @SystemApi
    @FlaggedApi(FLAG_ROUTED_DEVICE_IDS)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @NonNull List<AudioDeviceInfo> getAudioDeviceInfos() {
        List<AudioDeviceInfo> audioDeviceInfos = new ArrayList<AudioDeviceInfo>();
        final int[] deviceIds;
        synchronized (mUpdateablePropLock) {
            deviceIds = mDeviceIds;
        }

        for (int i = 0; i < deviceIds.length; i++) {
            AudioDeviceInfo audioDeviceInfo = AudioManager.getDeviceForPortId(deviceIds[i],
                    AudioManager.GET_DEVICES_OUTPUTS);
            if (audioDeviceInfo != null) {
                audioDeviceInfos.add(audioDeviceInfo);
            }
        }
        return audioDeviceInfos;
    }

    /**
     * @hide
     * Return the audio session ID associated with this player.
     * See {@link AudioManager#generateAudioSessionId()}.
     * @return an audio session ID
     */
    @SystemApi
    public @IntRange(from = 0) int getSessionId() {
        synchronized (mUpdateablePropLock) {
            return mSessionId;
        }
    }

    /**
     * @hide
     * Used for determining if the current player is muted.
     * <br>Note that if this result is true then {@link #getMutedBy} will be > 0.
     * @return {@code true} if the player associated with this configuration has been muted (by any
     * given MUTED_BY_* source event) or {@code false} otherwise.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean isMuted() {
        synchronized (mUpdateablePropLock) {
            return mMutedState != 0;
        }
    }

    /**
     * @hide
     * Returns a bitmask expressing the mute state as a combination of MUTED_BY_* flags.
     * <br>A value of 0 corresponds to an unmuted player.
     * @return the mute state.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    @PlayerMuteEvent public int getMutedBy() {
        synchronized (mUpdateablePropLock) {
            return mMutedState;
        }
    }

    /**
     * @hide
     * Return the type of player linked to this configuration.
     * <br>Note that player types not exposed in the system API will be represented as
     * {@link #PLAYER_TYPE_UNKNOWN}.
     * @return the type of the player.
     */
    @SystemApi
    public @PlayerType int getPlayerType() {
        switch (mPlayerType) {
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
     * Return whether this player's output is being processed by the spatializer effect backing
     * the {@link android.media.Spatializer} implementation.
     * @return true if spatialized, false if not or playback hasn't started
     */
    @SystemApi
    public boolean isSpatialized() {
        synchronized (mUpdateablePropLock) {
            return mFormatInfo.mIsSpatialized;
        }
    }

    /**
     * @hide
     * Return the sample rate in Hz of the content being played.
     * @return the sample rate in Hertz, or 0 if playback hasn't started
     */
    @SystemApi
    public @IntRange(from = 0) int getSampleRate() {
        synchronized (mUpdateablePropLock) {
            return mFormatInfo.mSampleRate;
        }
    }

    /**
     * @hide
     * Return the player's channel mask
     * @return the channel mask, or 0 if playback hasn't started. See {@link AudioFormat} and
     *     the definitions for the <code>CHANNEL_OUT_*</code> values used for the mask's bitfield
     */
    @SystemApi
    public @AudioFormat.ChannelOut int getChannelMask() {
        synchronized (mUpdateablePropLock) {
            return (AudioFormat.convertNativeChannelMaskToOutMask(mFormatInfo.mNativeChannelMask));
        }
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
     * Handle a change of audio session ID
     * @param sessionId the audio session ID
     */
    public boolean handleSessionIdEvent(int sessionId) {
        synchronized (mUpdateablePropLock) {
            final boolean changed = sessionId != mSessionId;
            mSessionId = sessionId;
            return changed;
        }
    }

    /**
     * @hide
     * Handle a change of the muted state
     * @param mutedState the mute reason as a combination of {@link PlayerMuteEvent} flags
     * @return true if the state changed, false otherwise
     */
    public boolean handleMutedEvent(@PlayerMuteEvent int mutedState) {
        synchronized (mUpdateablePropLock) {
            final boolean changed = mMutedState != mutedState;
            mMutedState = mutedState;
            return changed;
        }
    }

    /**
     * @hide
     * Handle a change of playback format
     * @param fi the latest format information
     * @return true if the format changed, false otherwise
     */
    public boolean handleFormatEvent(@NonNull FormatInfo fi) {
        synchronized (mUpdateablePropLock) {
            final boolean changed = !mFormatInfo.equals(fi);
            mFormatInfo = fi;
            return changed;
        }
    }

    /**
     * @hide
     * Handle a player state change
     * @param event
     * @param deviceIds an array of device ids. This can be empty.
     * <br>Note device ids are non-empty for {@code PLAYER_UPDATE_DEVICE_ID} or
     * <br>{@code PLAYER_STATE_STARTED} events, as the device ids will be emptied when pausing
     * <br>or stopping playback. It will be set to active devices when playback starts or
     * <br>it will be changed when PLAYER_UPDATE_DEVICE_ID is sent. The latter can happen if the
     * <br>devices change in the middle of playback.
     * @return true if the state changed, false otherwise
     */
    public boolean handleStateEvent(int event, int[] deviceIds) {
        boolean changed = false;
        synchronized (mUpdateablePropLock) {

            // Do not update if it is only device id update
            if (event != PLAYER_UPDATE_DEVICE_ID) {
                changed = (mPlayerState != event);
                mPlayerState = event;
            }

            if (event == PLAYER_STATE_STARTED || event == PLAYER_UPDATE_DEVICE_ID) {
                changed = changed || !Arrays.equals(mDeviceIds, deviceIds);
                mDeviceIds = deviceIds;
            }

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

    private boolean isMuteAffectingActiveState() {
        return (mMutedState & MUTED_BY_CLIENT_VOLUME) != 0
                || (mMutedState & MUTED_BY_VOLUME_SHAPER) != 0
                || (mMutedState & MUTED_BY_OP_PLAY_AUDIO) != 0;
    }

    /**
     * @hide
     * Returns true if the player is considered "active", i.e. actively playing with unmuted
     * volume, and thus in a state that should make it considered for the list public (sanitized)
     * active playback configurations
     * @return true if active
     */
    @SystemApi
    public boolean isActive() {
        switch (mPlayerState) {
            case PLAYER_STATE_STARTED:
                return !isMuteAffectingActiveState();
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
        pw.println("  " + this);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AudioPlaybackConfiguration> CREATOR
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
        synchronized (mUpdateablePropLock) {
            return Objects.hash(mPlayerIId, Arrays.toString(mDeviceIds), mMutedState, mPlayerType,
                    mClientUid, mClientPid, mSessionId);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        synchronized (mUpdateablePropLock) {
            dest.writeInt(mPlayerIId);
            dest.writeIntArray(mDeviceIds);
            dest.writeInt(mMutedState);
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
            dest.writeInt(mSessionId);
            mFormatInfo.writeToParcel(dest, 0);
        }
    }

    private AudioPlaybackConfiguration(Parcel in) {
        mPlayerIId = in.readInt();
        mDeviceIds = new int[in.readInt()];
        for (int i = 0; i < mDeviceIds.length; i++) {
            mDeviceIds[i] = in.readInt();
        }
        mMutedState = in.readInt();
        mPlayerType = in.readInt();
        mClientUid = in.readInt();
        mClientPid = in.readInt();
        mPlayerState = in.readInt();
        mPlayerAttr = AudioAttributes.CREATOR.createFromParcel(in);
        final IPlayer p = IPlayer.Stub.asInterface(in.readStrongBinder());
        mIPlayerShell = (p == null) ? null : new IPlayerShell(null, p);
        mSessionId = in.readInt();
        mFormatInfo = FormatInfo.CREATOR.createFromParcel(in);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof AudioPlaybackConfiguration)) return false;

        AudioPlaybackConfiguration that = (AudioPlaybackConfiguration) o;

        return ((mPlayerIId == that.mPlayerIId)
                && Arrays.equals(mDeviceIds, that.mDeviceIds)
                && (mMutedState == that.mMutedState)
                && (mPlayerType == that.mPlayerType)
                && (mClientUid == that.mClientUid)
                && (mClientPid == that.mClientPid))
                && (mSessionId == that.mSessionId);
    }

    @Override
    public String toString() {
        StringBuilder apcToString = new StringBuilder();
        synchronized (mUpdateablePropLock) {
            apcToString.append("AudioPlaybackConfiguration piid:").append(mPlayerIId).append(
                    " deviceIds:").append(Arrays.toString(mDeviceIds)).append(" type:").append(
                    toLogFriendlyPlayerType(mPlayerType)).append(" u/pid:").append(
                    mClientUid).append(
                    "/").append(mClientPid).append(" state:").append(
                    toLogFriendlyPlayerState(mPlayerState)).append(" attr:").append(
                    mPlayerAttr).append(
                    " sessionId:").append(mSessionId).append(" mutedState:");
            if (mMutedState == 0) {
                apcToString.append("none ");
            } else {
                if ((mMutedState & MUTED_BY_MASTER) != 0) {
                    apcToString.append("master ");
                }
                if ((mMutedState & MUTED_BY_STREAM_VOLUME) != 0) {
                    apcToString.append("streamVolume ");
                }
                if ((mMutedState & MUTED_BY_STREAM_MUTED) != 0) {
                    apcToString.append("streamMute ");
                }
                if ((mMutedState & MUTED_BY_OP_PLAY_AUDIO) != 0) {
                    apcToString.append("opPlayAudio ");
                }
                if ((mMutedState & MUTED_BY_CLIENT_VOLUME) != 0) {
                    apcToString.append("clientVolume ");
                }
                if ((mMutedState & MUTED_BY_VOLUME_SHAPER) != 0) {
                    apcToString.append("volumeShaper ");
                }
                if ((mMutedState & MUTED_BY_PORT_VOLUME) != 0) {
                    apcToString.append("portVolume ");
                }
                if ((mMutedState & MUTED_BY_OP_CONTROL_AUDIO) != 0) {
                    apcToString.append("opControlAudio ");
                }
            }
            apcToString.append(" ").append(mFormatInfo);
        }
        return apcToString.toString();
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

    /**
     * @hide
     * Class to store sample rate, channel mask, and spatialization status
     */
    public static final class FormatInfo implements Parcelable {
        static final FormatInfo DEFAULT = new FormatInfo(
                /*spatialized*/ false, /*channel mask*/ 0, /*sample rate*/ 0);
        final boolean mIsSpatialized;
        final int mNativeChannelMask;
        final int mSampleRate;

        public FormatInfo(boolean isSpatialized, int nativeChannelMask, int sampleRate) {
            mIsSpatialized = isSpatialized;
            mNativeChannelMask = nativeChannelMask;
            mSampleRate = sampleRate;
        }

        @Override
        public String toString() {
            return "FormatInfo{"
                    + "isSpatialized=" + mIsSpatialized
                    + ", channelMask=0x" + Integer.toHexString(mNativeChannelMask)
                    + ", sampleRate=" + mSampleRate
                    + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FormatInfo)) return false;
            FormatInfo that = (FormatInfo) o;
            return mIsSpatialized == that.mIsSpatialized
                    && mNativeChannelMask == that.mNativeChannelMask
                    && mSampleRate == that.mSampleRate;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIsSpatialized, mNativeChannelMask, mSampleRate);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@androidx.annotation.NonNull Parcel dest, int flags) {
            dest.writeBoolean(mIsSpatialized);
            dest.writeInt(mNativeChannelMask);
            dest.writeInt(mSampleRate);
        }

        private FormatInfo(Parcel in) {
            this(
                    in.readBoolean(), // isSpatialized
                    in.readInt(),     // channelMask
                    in.readInt()      // sampleRate
            );
        }

        public static final @NonNull Parcelable.Creator<FormatInfo> CREATOR =
                new Parcelable.Creator<FormatInfo>() {
            public FormatInfo createFromParcel(Parcel p) {
                return new FormatInfo(p);
            }
            public FormatInfo[] newArray(int size) {
                return new FormatInfo[size];
            }
        };
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
            case PLAYER_UPDATE_DEVICE_ID: return "device updated";
            case PLAYER_UPDATE_PORT_ID: return "port updated";
            case PLAYER_UPDATE_MUTED: return "muted updated";
            default:
                return "unknown player state - FIXME";
        }
    }
}
