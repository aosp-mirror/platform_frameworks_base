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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;

import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Class to encapsulate a number of common player operations:
 *   - AppOps for OP_PLAY_AUDIO
 *   - more to come (routing, transport control)
 * @hide
 */
public abstract class PlayerBase {

    private static final String TAG = "PlayerBase";
    /** Debug app ops */
    private static final boolean DEBUG_APP_OPS = false;
    private static final boolean DEBUG = DEBUG_APP_OPS || false;
    private static IAudioService sService; //lazy initialization, use getService()

    // parameters of the player that affect AppOps
    protected AudioAttributes mAttributes;

    // volumes of the subclass "player volumes", as seen by the client of the subclass
    //   (e.g. what was passed in AudioTrack.setVolume(float)). The actual volume applied is
    //   the combination of the player volume, and the PlayerBase pan and volume multipliers
    protected float mLeftVolume = 1.0f;
    protected float mRightVolume = 1.0f;
    protected float mAuxEffectSendLevel = 0.0f;

    // NEVER call into AudioService (see getService()) with mLock held: PlayerBase can run in
    // the same process as AudioService, which can synchronously call back into this class,
    // causing deadlocks between the two
    private final Object mLock = new Object();

    // for AppOps
    private @Nullable IAppOpsService mAppOps;
    private IAppOpsCallback mAppOpsCallback;
    @GuardedBy("mLock")
    private boolean mHasAppOpsPlayAudio = true;

    private final int mImplType;
    // uniquely identifies the Player Interface throughout the system (P I Id)
    private int mPlayerIId = AudioPlaybackConfiguration.PLAYER_PIID_UNASSIGNED;

    @GuardedBy("mLock")
    private int mState;
    @GuardedBy("mLock")
    private int mStartDelayMs = 0;
    @GuardedBy("mLock")
    private float mPanMultiplierL = 1.0f;
    @GuardedBy("mLock")
    private float mPanMultiplierR = 1.0f;
    @GuardedBy("mLock")
    private float mVolMultiplier = 1.0f;

    /**
     * Constructor. Must be given audio attributes, as they are required for AppOps.
     * @param attr non-null audio attributes
     * @param class non-null class of the implementation of this abstract class
     */
    PlayerBase(@NonNull AudioAttributes attr, int implType) {
        if (attr == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        mAttributes = attr;
        mImplType = implType;
        mState = AudioPlaybackConfiguration.PLAYER_STATE_IDLE;
    };

    /**
     * Call from derived class when instantiation / initialization is successful
     */
    protected void baseRegisterPlayer() {
        int newPiid = AudioPlaybackConfiguration.PLAYER_PIID_INVALID;
        IBinder b = ServiceManager.getService(Context.APP_OPS_SERVICE);
        mAppOps = IAppOpsService.Stub.asInterface(b);
        // initialize mHasAppOpsPlayAudio
        updateAppOpsPlayAudio();
        // register a callback to monitor whether the OP_PLAY_AUDIO is still allowed
        mAppOpsCallback = new IAppOpsCallbackWrapper(this);
        try {
            mAppOps.startWatchingMode(AppOpsManager.OP_PLAY_AUDIO,
                    ActivityThread.currentPackageName(), mAppOpsCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Error registering appOps callback", e);
            mHasAppOpsPlayAudio = false;
        }
        try {
            newPiid = getService().trackPlayer(
                    new PlayerIdCard(mImplType, mAttributes, new IPlayerWrapper(this)));
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, player will not be tracked", e);
        }
        mPlayerIId = newPiid;
    }

    /**
     * To be called whenever the audio attributes of the player change
     * @param attr non-null audio attributes
     */
    void baseUpdateAudioAttributes(@NonNull AudioAttributes attr) {
        if (attr == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        try {
            getService().playerAttributes(mPlayerIId, attr);
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, STARTED state will not be tracked", e);
        }
        synchronized (mLock) {
            boolean attributesChanged = (mAttributes != attr);
            mAttributes = attr;
            updateAppOpsPlayAudio_sync(attributesChanged);
        }
    }

    private void updateState(int state) {
        final int piid;
        synchronized (mLock) {
            mState = state;
            piid = mPlayerIId;
        }
        try {
            getService().playerEvent(piid, state);
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, "
                    + AudioPlaybackConfiguration.toLogFriendlyPlayerState(state)
                    + " state will not be tracked for piid=" + piid, e);
        }
    }

    void baseStart() {
        if (DEBUG) { Log.v(TAG, "baseStart() piid=" + mPlayerIId); }
        updateState(AudioPlaybackConfiguration.PLAYER_STATE_STARTED);
        synchronized (mLock) {
            if (isRestricted_sync()) {
                playerSetVolume(true/*muting*/,0, 0);
            }
        }
    }

    void baseSetStartDelayMs(int delayMs) {
        synchronized(mLock) {
            mStartDelayMs = Math.max(delayMs, 0);
        }
    }

    protected int getStartDelayMs() {
        synchronized(mLock) {
            return mStartDelayMs;
        }
    }

    void basePause() {
        if (DEBUG) { Log.v(TAG, "basePause() piid=" + mPlayerIId); }
        updateState(AudioPlaybackConfiguration.PLAYER_STATE_PAUSED);
    }

    void baseStop() {
        if (DEBUG) { Log.v(TAG, "baseStop() piid=" + mPlayerIId); }
        updateState(AudioPlaybackConfiguration.PLAYER_STATE_STOPPED);
    }

    void baseSetPan(float pan) {
        final float p = Math.min(Math.max(-1.0f, pan), 1.0f);
        synchronized (mLock) {
            if (p >= 0.0f) {
                mPanMultiplierL = 1.0f - p;
                mPanMultiplierR = 1.0f;
            } else {
                mPanMultiplierL = 1.0f;
                mPanMultiplierR = 1.0f + p;
            }
        }
        updatePlayerVolume();
    }

    private void updatePlayerVolume() {
        final float finalLeftVol, finalRightVol;
        final boolean isRestricted;
        synchronized (mLock) {
            finalLeftVol = mVolMultiplier * mLeftVolume * mPanMultiplierL;
            finalRightVol = mVolMultiplier * mRightVolume * mPanMultiplierR;
            isRestricted = isRestricted_sync();
        }
        playerSetVolume(isRestricted /*muting*/, finalLeftVol, finalRightVol);
    }

    void setVolumeMultiplier(float vol) {
        synchronized (mLock) {
            this.mVolMultiplier = vol;
        }
        updatePlayerVolume();
    }

    void baseSetVolume(float leftVolume, float rightVolume) {
        synchronized (mLock) {
            mLeftVolume = leftVolume;
            mRightVolume = rightVolume;
        }
        updatePlayerVolume();
    }

    int baseSetAuxEffectSendLevel(float level) {
        synchronized (mLock) {
            mAuxEffectSendLevel = level;
            if (isRestricted_sync()) {
                return AudioSystem.SUCCESS;
            }
        }
        return playerSetAuxEffectSendLevel(false/*muting*/, level);
    }

    /**
     * To be called from a subclass release or finalize method.
     * Releases AppOps related resources.
     */
    void baseRelease() {
        if (DEBUG) { Log.v(TAG, "baseRelease() piid=" + mPlayerIId + " state=" + mState); }
        boolean releasePlayer = false;
        synchronized (mLock) {
            if (mState != AudioPlaybackConfiguration.PLAYER_STATE_RELEASED) {
                releasePlayer = true;
                mState = AudioPlaybackConfiguration.PLAYER_STATE_RELEASED;
            }
        }
        try {
            if (releasePlayer) {
                getService().releasePlayer(mPlayerIId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, the player will still be tracked", e);
        }
        try {
            if (mAppOps != null) {
                mAppOps.stopWatchingMode(mAppOpsCallback);
            }
        } catch (Exception e) {
            // nothing to do here, the object is supposed to be released anyway
        }
    }

    private void updateAppOpsPlayAudio() {
        synchronized (mLock) {
            updateAppOpsPlayAudio_sync(false);
        }
    }

    /**
     * To be called whenever a condition that might affect audibility of this player is updated.
     * Must be called synchronized on mLock.
     */
    void updateAppOpsPlayAudio_sync(boolean attributesChanged) {
        boolean oldHasAppOpsPlayAudio = mHasAppOpsPlayAudio;
        try {
            int mode = AppOpsManager.MODE_IGNORED;
            if (mAppOps != null) {
                mode = mAppOps.checkAudioOperation(AppOpsManager.OP_PLAY_AUDIO,
                    mAttributes.getUsage(),
                    Process.myUid(), ActivityThread.currentPackageName());
            }
            mHasAppOpsPlayAudio = (mode == AppOpsManager.MODE_ALLOWED);
        } catch (RemoteException e) {
            mHasAppOpsPlayAudio = false;
        }

        // AppsOps alters a player's volume; when the restriction changes, reflect it on the actual
        // volume used by the player
        try {
            if (oldHasAppOpsPlayAudio != mHasAppOpsPlayAudio ||
                    attributesChanged) {
                getService().playerHasOpPlayAudio(mPlayerIId, mHasAppOpsPlayAudio);
                if (!isRestricted_sync()) {
                    if (DEBUG_APP_OPS) {
                        Log.v(TAG, "updateAppOpsPlayAudio: unmuting player, vol=" + mLeftVolume
                                + "/" + mRightVolume);
                    }
                    playerSetVolume(false/*muting*/,
                            mLeftVolume * mPanMultiplierL, mRightVolume * mPanMultiplierR);
                    playerSetAuxEffectSendLevel(false/*muting*/, mAuxEffectSendLevel);
                } else {
                    if (DEBUG_APP_OPS) {
                        Log.v(TAG, "updateAppOpsPlayAudio: muting player");
                    }
                    playerSetVolume(true/*muting*/, 0.0f, 0.0f);
                    playerSetAuxEffectSendLevel(true/*muting*/, 0.0f);
                }
            }
        } catch (Exception e) {
            // failing silently, player might not be in right state
        }
    }

    /**
     * To be called by the subclass whenever an operation is potentially restricted.
     * As the media player-common behavior are incorporated into this class, the subclass's need
     * to call this method should be removed, and this method could become private.
     * FIXME can this method be private so subclasses don't have to worry about when to check
     *    the restrictions.
     * @return
     */
    boolean isRestricted_sync() {
        // check app ops
        if (mHasAppOpsPlayAudio) {
            return false;
        }
        // check bypass flag
        if ((mAttributes.getAllFlags() & AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY) != 0) {
            return false;
        }
        // check force audibility flag and camera restriction
        if (((mAttributes.getAllFlags() & AudioAttributes.FLAG_AUDIBILITY_ENFORCED) != 0)
                && (mAttributes.getUsage() == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)) {
            boolean cameraSoundForced = false;
            try {
                cameraSoundForced = getService().isCameraSoundForced();
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot access AudioService in isRestricted_sync()");
            } catch (NullPointerException e) {
                Log.e(TAG, "Null AudioService in isRestricted_sync()");
            }
            if (cameraSoundForced) {
                return false;
            }
        }
        return true;
    }

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
     * @hide
     * @param delayMs
     */
    public void setStartDelayMs(int delayMs) {
        baseSetStartDelayMs(delayMs);
    }

    //=====================================================================
    // Abstract methods a subclass needs to implement
    /**
     * Abstract method for the subclass behavior's for volume and muting commands
     * @param muting if true, the player is to be muted, and the volume values can be ignored
     * @param leftVolume the left volume to use if muting is false
     * @param rightVolume the right volume to use if muting is false
     */
    abstract void playerSetVolume(boolean muting, float leftVolume, float rightVolume);

    /**
     * Abstract method to apply a {@link VolumeShaper.Configuration}
     * and a {@link VolumeShaper.Operation} to the Player.
     * This should be overridden by the Player to call into the native
     * VolumeShaper implementation. Multiple {@code VolumeShapers} may be
     * concurrently active for a given Player, each accessible by the
     * {@code VolumeShaper} id.
     *
     * The {@code VolumeShaper} implementation caches the id returned
     * when applying a fully specified configuration
     * from {VolumeShaper.Configuration.Builder} to track later
     * operation changes requested on it.
     *
     * @param configuration a {@code VolumeShaper.Configuration} object
     *        created by {@link VolumeShaper.Configuration.Builder} or
     *        an created from a {@code VolumeShaper} id
     *        by the {@link VolumeShaper.Configuration} constructor.
     * @param operation a {@code VolumeShaper.Operation}.
     * @return a negative error status or a
     *         non-negative {@code VolumeShaper} id on success.
     */
    /* package */ abstract int playerApplyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation);

    /**
     * Abstract method to get the current VolumeShaper state.
     * @param id the {@code VolumeShaper} id returned from
     *           sending a fully specified {@code VolumeShaper.Configuration}
     *           through {@link #playerApplyVolumeShaper}
     * @return a {@code VolumeShaper.State} object or null if
     *         there is no {@code VolumeShaper} for the id.
     */
    /* package */ abstract @Nullable VolumeShaper.State playerGetVolumeShaperState(int id);

    abstract int playerSetAuxEffectSendLevel(boolean muting, float level);
    abstract void playerStart();
    abstract void playerPause();
    abstract void playerStop();

    //=====================================================================
    private static class IAppOpsCallbackWrapper extends IAppOpsCallback.Stub {
        private final WeakReference<PlayerBase> mWeakPB;

        public IAppOpsCallbackWrapper(PlayerBase pb) {
            mWeakPB = new WeakReference<PlayerBase>(pb);
        }

        @Override
        public void opChanged(int op, int uid, String packageName) {
            if (op == AppOpsManager.OP_PLAY_AUDIO) {
                if (DEBUG_APP_OPS) { Log.v(TAG, "opChanged: op=PLAY_AUDIO pack=" + packageName); }
                final PlayerBase pb = mWeakPB.get();
                if (pb != null) {
                    pb.updateAppOpsPlayAudio();
                }
            }
        }
    }

    //=====================================================================
    /**
     * Wrapper around an implementation of IPlayer for all subclasses of PlayerBase
     * that doesn't keep a strong reference on PlayerBase
     */
    private static class IPlayerWrapper extends IPlayer.Stub {
        private final WeakReference<PlayerBase> mWeakPB;

        public IPlayerWrapper(PlayerBase pb) {
            mWeakPB = new WeakReference<PlayerBase>(pb);
        }

        @Override
        public void start() {
            final PlayerBase pb = mWeakPB.get();
            if (pb != null) {
                pb.playerStart();
            }
        }

        @Override
        public void pause() {
            final PlayerBase pb = mWeakPB.get();
            if (pb != null) {
                pb.playerPause();
            }
        }

        @Override
        public void stop() {
            final PlayerBase pb = mWeakPB.get();
            if (pb != null) {
                pb.playerStop();
            }
        }

        @Override
        public void setVolume(float vol) {
            final PlayerBase pb = mWeakPB.get();
            if (pb != null) {
                pb.setVolumeMultiplier(vol);
            }
        }

        @Override
        public void setPan(float pan) {
            final PlayerBase pb = mWeakPB.get();
            if (pb != null) {
                pb.baseSetPan(pan);
            }
        }

        @Override
        public void setStartDelayMs(int delayMs) {
            final PlayerBase pb = mWeakPB.get();
            if (pb != null) {
                pb.baseSetStartDelayMs(delayMs);
            }
        }

        @Override
        public void applyVolumeShaper(
                @NonNull VolumeShaper.Configuration configuration,
                @NonNull VolumeShaper.Operation operation) {
            final PlayerBase pb = mWeakPB.get();
            if (pb != null) {
                pb.playerApplyVolumeShaper(configuration, operation);
            }
        }
    }

    //=====================================================================
    /**
     * Class holding all the information about a player that needs to be known at registration time
     */
    public static class PlayerIdCard implements Parcelable {
        public final int mPlayerType;

        public static final int AUDIO_ATTRIBUTES_NONE = 0;
        public static final int AUDIO_ATTRIBUTES_DEFINED = 1;
        public final AudioAttributes mAttributes;
        public final IPlayer mIPlayer;

        PlayerIdCard(int type, @NonNull AudioAttributes attr, @NonNull IPlayer iplayer) {
            mPlayerType = type;
            mAttributes = attr;
            mIPlayer = iplayer;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPlayerType);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mPlayerType);
            mAttributes.writeToParcel(dest, 0);
            dest.writeStrongBinder(mIPlayer == null ? null : mIPlayer.asBinder());
        }

        public static final @android.annotation.NonNull Parcelable.Creator<PlayerIdCard> CREATOR
        = new Parcelable.Creator<PlayerIdCard>() {
            /**
             * Rebuilds an PlayerIdCard previously stored with writeToParcel().
             * @param p Parcel object to read the PlayerIdCard from
             * @return a new PlayerIdCard created from the data in the parcel
             */
            public PlayerIdCard createFromParcel(Parcel p) {
                return new PlayerIdCard(p);
            }
            public PlayerIdCard[] newArray(int size) {
                return new PlayerIdCard[size];
            }
        };

        private PlayerIdCard(Parcel in) {
            mPlayerType = in.readInt();
            mAttributes = AudioAttributes.CREATOR.createFromParcel(in);
            // IPlayer can be null if unmarshalling a Parcel coming from who knows where
            final IBinder b = in.readStrongBinder();
            mIPlayer = (b == null ? null : IPlayer.Stub.asInterface(b));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || !(o instanceof PlayerIdCard)) return false;

            PlayerIdCard that = (PlayerIdCard) o;

            // FIXME change to the binder player interface once supported as a member
            return ((mPlayerType == that.mPlayerType) && mAttributes.equals(that.mAttributes));
        }
    }

    //=====================================================================
    // Utilities

    /**
     * Use to generate warning or exception in legacy code paths that allowed passing stream types
     * to qualify audio playback.
     * @param streamType the stream type to check
     * @throws IllegalArgumentException
     * @deprecated This method is not intended to be used by applications.
     */
    @java.lang.Deprecated
    public static void deprecateStreamTypeForPlayback(int streamType, @NonNull String className,
            @NonNull String opName) throws IllegalArgumentException {
        // STREAM_ACCESSIBILITY was introduced at the same time the use of stream types
        // for audio playback was deprecated, so it is not allowed at all to qualify a playback
        // use case
        if (streamType == AudioManager.STREAM_ACCESSIBILITY) {
            throw new IllegalArgumentException("Use of STREAM_ACCESSIBILITY is reserved for "
                    + "volume control");
        }
        Log.w(className, "Use of stream types is deprecated for operations other than " +
                "volume control");
        Log.w(className, "See the documentation of " + opName + " for what to use instead with " +
                "android.media.AudioAttributes to qualify your playback use case");
    }
}
