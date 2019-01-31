/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.media;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.session.ControllerCallbackLink;
import android.media.session.ControllerLink;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.MediaSession.QueueItem;
import android.media.session.PlaybackState;
import android.media.session.SessionCallbackLink;
import android.media.session.SessionLink;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * This is the system implementation of a Session. Apps will interact with the
 * MediaSession wrapper class instead.
 */
public class MediaSessionRecord implements IBinder.DeathRecipient {
    private static final String TAG = "MediaSessionRecord";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * The amount of time we'll send an assumed volume after the last volume
     * command before reverting to the last reported volume.
     */
    private static final int OPTIMISTIC_VOLUME_TIMEOUT = 1000;

    private final MessageHandler mHandler;

    private final int mOwnerPid;
    private final int mOwnerUid;
    private final int mUserId;
    private final String mPackageName;
    private final String mTag;
    private final ControllerLink mController;
    private final MediaSession.Token mSessionToken;
    private final SessionLink mSession;
    private final SessionCb mSessionCb;
    private final MediaSessionService.ServiceImpl mService;
    private final Context mContext;

    private final Object mLock = new Object();
    private final ArrayList<ControllerCallbackLinkHolder> mControllerCallbackHolders =
            new ArrayList<>();

    private long mFlags;
    private PendingIntent mMediaButtonReceiver;
    private PendingIntent mLaunchIntent;

    // TransportPerformer fields
    private Bundle mExtras;
    // Note: Avoid unparceling the bundle inside MediaMetadata since unparceling in system process
    // may result in throwing an exception.
    private MediaMetadata mMetadata;
    private PlaybackState mPlaybackState;
    private List<QueueItem> mQueue;
    private CharSequence mQueueTitle;
    private int mRatingType;
    // End TransportPerformer fields

    // Volume handling fields
    private AudioAttributes mAudioAttrs;
    private AudioManager mAudioManager;
    private AudioManagerInternal mAudioManagerInternal;
    private int mVolumeType = PlaybackInfo.PLAYBACK_TYPE_LOCAL;
    private int mVolumeControlType = VolumeProvider.VOLUME_CONTROL_ABSOLUTE;
    private int mMaxVolume = 0;
    private int mCurrentVolume = 0;
    private int mOptimisticVolume = -1;
    // End volume handling fields

    private boolean mIsActive = false;
    private boolean mDestroyed = false;

    private long mDuration = -1;
    private String mMetadataDescription;

    public MediaSessionRecord(int ownerPid, int ownerUid, int userId, String ownerPackageName,
            SessionCallbackLink cb, String tag, MediaSessionService.ServiceImpl service,
            Looper handlerLooper) {
        mOwnerPid = ownerPid;
        mOwnerUid = ownerUid;
        mUserId = userId;
        mPackageName = ownerPackageName;
        mTag = tag;
        mController = new ControllerLink(new ControllerStub());
        mSessionToken = new MediaSession.Token(mController);
        mSession = new SessionLink(new SessionStub());
        mSessionCb = new SessionCb(cb);
        mService = service;
        mContext = mService.getContext();
        mHandler = new MessageHandler(handlerLooper);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mAudioManagerInternal = LocalServices.getService(AudioManagerInternal.class);
        mAudioAttrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    }

    /**
     * Get the session link for the {@link MediaSession}.
     *
     * @return The session link apps talk to.
     */
    public SessionLink getSessionBinder() {
        return mSession;
    }

    /**
     * Get the controller link for the {@link MediaController}.
     *
     * @return The controller link apps talk to.
     */
    public ControllerLink getControllerLink() {
        return mController;
    }

    /**
     * Get the session token for creating {@link MediaController}.
     *
     * @return The session token.
     */
    public MediaSession.Token getSessionToken() {
        return mSessionToken;
    }

    /**
     * Get the info for this session.
     *
     * @return Info that identifies this session.
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Get the tag for the session.
     *
     * @return The session's tag.
     */
    public String getTag() {
        return mTag;
    }

    /**
     * Get the intent the app set for their media button receiver.
     *
     * @return The pending intent set by the app or null.
     */
    public PendingIntent getMediaButtonReceiver() {
        return mMediaButtonReceiver;
    }

    /**
     * Get this session's flags.
     *
     * @return The flags for this session.
     */
    public long getFlags() {
        return mFlags;
    }

    /**
     * Check if this session has the specified flag.
     *
     * @param flag The flag to check.
     * @return True if this session has that flag set, false otherwise.
     */
    public boolean hasFlag(int flag) {
        return (mFlags & flag) != 0;
    }

    /**
     * Get the UID this session was created for.
     *
     * @return The UID for this session.
     */
    public int getUid() {
        return mOwnerUid;
    }

    /**
     * Get the user id this session was created for.
     *
     * @return The user id for this session.
     */
    public int getUserId() {
        return mUserId;
    }

    /**
     * Check if this session has system priorty and should receive media buttons
     * before any other sessions.
     *
     * @return True if this is a system priority session, false otherwise
     */
    public boolean isSystemPriority() {
        return (mFlags & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY) != 0;
    }

    /**
     * Send a volume adjustment to the session owner. Direction must be one of
     * {@link AudioManager#ADJUST_LOWER}, {@link AudioManager#ADJUST_RAISE},
     * {@link AudioManager#ADJUST_SAME}.
     *
     * @param packageName The package that made the original volume request.
     * @param opPackageName The op package that made the original volume request.
     * @param pid The pid that made the original volume request.
     * @param uid The uid that made the original volume request.
     * @param caller caller binder. can be {@code null} if it's from the volume key.
     * @param asSystemService {@code true} if the event sent to the session as if it was come from
     *          the system service instead of the app process. This helps sessions to distinguish
     *          between the key injection by the app and key events from the hardware devices.
     *          Should be used only when the volume key events aren't handled by foreground
     *          activity. {@code false} otherwise to tell session about the real caller.
     * @param direction The direction to adjust volume in.
     * @param flags Any of the flags from {@link AudioManager}.
     * @param useSuggested True to use adjustSuggestedStreamVolume instead of
     */
    public void adjustVolume(String packageName, String opPackageName, int pid, int uid,
            ControllerCallbackLink caller, boolean asSystemService, int direction, int flags,
            boolean useSuggested) {
        int previousFlagPlaySound = flags & AudioManager.FLAG_PLAY_SOUND;
        if (isPlaybackActive() || hasFlag(MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY)) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }
        if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            // Adjust the volume with a handler not to be blocked by other system service.
            int stream = AudioAttributes.toLegacyStreamType(mAudioAttrs);
            postAdjustLocalVolume(stream, direction, flags, opPackageName, pid, uid,
                    asSystemService, useSuggested, previousFlagPlaySound);
        } else {
            if (mVolumeControlType == VolumeProvider.VOLUME_CONTROL_FIXED) {
                // Nothing to do, the volume cannot be changed
                return;
            }
            if (direction == AudioManager.ADJUST_TOGGLE_MUTE
                    || direction == AudioManager.ADJUST_MUTE
                    || direction == AudioManager.ADJUST_UNMUTE) {
                Log.w(TAG, "Muting remote playback is not supported");
                return;
            }
            if (DEBUG) {
                Log.w(TAG, "adjusting volume, pkg=" + packageName + ", asSystemService="
                        + asSystemService + ", dir=" + direction);
            }
            mSessionCb.adjustVolume(packageName, pid, uid, caller, asSystemService, direction);

            int volumeBefore = (mOptimisticVolume < 0 ? mCurrentVolume : mOptimisticVolume);
            mOptimisticVolume = volumeBefore + direction;
            mOptimisticVolume = Math.max(0, Math.min(mOptimisticVolume, mMaxVolume));
            mHandler.removeCallbacks(mClearOptimisticVolumeRunnable);
            mHandler.postDelayed(mClearOptimisticVolumeRunnable, OPTIMISTIC_VOLUME_TIMEOUT);
            if (volumeBefore != mOptimisticVolume) {
                pushVolumeUpdate();
            }
            mService.notifyRemoteVolumeChanged(flags, this);

            if (DEBUG) {
                Log.d(TAG, "Adjusted optimistic volume to " + mOptimisticVolume + " max is "
                        + mMaxVolume);
            }
        }
    }

    private void setVolumeTo(String packageName, String opPackageName, int pid, int uid,
            ControllerCallbackLink caller, int value, int flags) {
        if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            int stream = AudioAttributes.toLegacyStreamType(mAudioAttrs);
            final int volumeValue = value;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mAudioManagerInternal.setStreamVolumeForUid(stream, volumeValue, flags,
                                opPackageName, uid);
                    } catch (IllegalArgumentException | SecurityException e) {
                        Log.e(TAG, "Cannot set volume: stream=" + stream + ", value=" + volumeValue
                                + ", flags=" + flags, e);
                    }
                }
            });
        } else {
            if (mVolumeControlType != VolumeProvider.VOLUME_CONTROL_ABSOLUTE) {
                // Nothing to do. The volume can't be set directly.
                return;
            }
            value = Math.max(0, Math.min(value, mMaxVolume));
            mSessionCb.setVolumeTo(packageName, pid, uid, caller, value);

            int volumeBefore = (mOptimisticVolume < 0 ? mCurrentVolume : mOptimisticVolume);
            mOptimisticVolume = Math.max(0, Math.min(value, mMaxVolume));
            mHandler.removeCallbacks(mClearOptimisticVolumeRunnable);
            mHandler.postDelayed(mClearOptimisticVolumeRunnable, OPTIMISTIC_VOLUME_TIMEOUT);
            if (volumeBefore != mOptimisticVolume) {
                pushVolumeUpdate();
            }
            mService.notifyRemoteVolumeChanged(flags, this);

            if (DEBUG) {
                Log.d(TAG, "Set optimistic volume to " + mOptimisticVolume + " max is "
                        + mMaxVolume);
            }
        }
    }

    /**
     * Check if this session has been set to active by the app.
     *
     * @return True if the session is active, false otherwise.
     */
    public boolean isActive() {
        return mIsActive && !mDestroyed;
    }

    /**
     * Get the playback state.
     *
     * @return The current playback state.
     */
    public PlaybackState getPlaybackState() {
        return mPlaybackState;
    }

    /**
     * Check if the session is currently performing playback.
     *
     * @return True if the session is performing playback, false otherwise.
     */
    public boolean isPlaybackActive() {
        int state = mPlaybackState == null ? PlaybackState.STATE_NONE : mPlaybackState.getState();
        return MediaSession.isActiveState(state);
    }

    /**
     * Get the type of playback, either local or remote.
     *
     * @return The current type of playback.
     */
    public int getPlaybackType() {
        return mVolumeType;
    }

    /**
     * Get the local audio stream being used. Only valid if playback type is
     * local.
     *
     * @return The audio stream the session is using.
     */
    public AudioAttributes getAudioAttributes() {
        return mAudioAttrs;
    }

    /**
     * Get the type of volume control. Only valid if playback type is remote.
     *
     * @return The volume control type being used.
     */
    public int getVolumeControl() {
        return mVolumeControlType;
    }

    /**
     * Get the max volume that can be set. Only valid if playback type is
     * remote.
     *
     * @return The max volume that can be set.
     */
    public int getMaxVolume() {
        return mMaxVolume;
    }

    /**
     * Get the current volume for this session. Only valid if playback type is
     * remote.
     *
     * @return The current volume of the remote playback.
     */
    public int getCurrentVolume() {
        return mCurrentVolume;
    }

    /**
     * Get the volume we'd like it to be set to. This is only valid for a short
     * while after a call to adjust or set volume.
     *
     * @return The current optimistic volume or -1.
     */
    public int getOptimisticVolume() {
        return mOptimisticVolume;
    }

    public boolean isTransportControlEnabled() {
        return hasFlag(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    }

    @Override
    public void binderDied() {
        mService.sessionDied(this);
    }

    /**
     * Finish cleaning up this session, including disconnecting if connected and
     * removing the death observer from the callback binder.
     */
    public void onDestroy() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            mDestroyed = true;
            mHandler.post(MessageHandler.MSG_DESTROYED);
        }
    }

    public SessionCallbackLink getCallback() {
        return mSessionCb.mCb;
    }

    /**
     * Sends media button.
     *
     * @param packageName caller package name
     * @param pid caller pid
     * @param uid caller uid
     * @param asSystemService {@code true} if the event sent to the session as if it was come from
     *          the system service instead of the app process.
     * @param ke key events
     * @param sequenceId (optional) sequence id. Use this only when a wake lock is needed.
     * @param cb (optional) result receiver to receive callback. Use this only when a wake lock is
     *           needed.
     * @return {@code true} if the attempt to send media button was successfuly.
     *         {@code false} otherwise.
     */
    public boolean sendMediaButton(String packageName, int pid, int uid, boolean asSystemService,
            KeyEvent ke, int sequenceId, ResultReceiver cb) {
        return mSessionCb.sendMediaButton(packageName, pid, uid, asSystemService, ke, sequenceId,
                cb);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + mTag + " " + this);

        final String indent = prefix + "  ";
        pw.println(indent + "ownerPid=" + mOwnerPid + ", ownerUid=" + mOwnerUid
                + ", userId=" + mUserId);
        pw.println(indent + "package=" + mPackageName);
        pw.println(indent + "launchIntent=" + mLaunchIntent);
        pw.println(indent + "mediaButtonReceiver=" + mMediaButtonReceiver);
        pw.println(indent + "active=" + mIsActive);
        pw.println(indent + "flags=" + mFlags);
        pw.println(indent + "rating type=" + mRatingType);
        pw.println(indent + "controllers: " + mControllerCallbackHolders.size());
        pw.println(indent + "state=" + (mPlaybackState == null ? null : mPlaybackState.toString()));
        pw.println(indent + "audioAttrs=" + mAudioAttrs);
        pw.println(indent + "volumeType=" + mVolumeType + ", controlType=" + mVolumeControlType
                + ", max=" + mMaxVolume + ", current=" + mCurrentVolume);
        pw.println(indent + "metadata: " + mMetadataDescription);
        pw.println(indent + "queueTitle=" + mQueueTitle + ", size="
                + (mQueue == null ? 0 : mQueue.size()));
    }

    @Override
    public String toString() {
        return mPackageName + "/" + mTag + " (userId=" + mUserId + ")";
    }

    private void postAdjustLocalVolume(final int stream, final int direction, final int flags,
            final String callingOpPackageName, final int callingPid, final int callingUid,
            final boolean asSystemService, final boolean useSuggested,
            final int previousFlagPlaySound) {
        if (DEBUG) {
            Log.w(TAG, "adjusting local volume, stream=" + stream + ", dir=" + direction
                    + ", asSystemService=" + asSystemService + ", useSuggested=" + useSuggested);
        }
        // Must use opPackageName for adjusting volumes with UID.
        final String opPackageName;
        final int uid;
        if (asSystemService) {
            opPackageName = mContext.getOpPackageName();
            uid = Process.SYSTEM_UID;
        } else {
            opPackageName = callingOpPackageName;
            uid = callingUid;
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (useSuggested) {
                        if (AudioSystem.isStreamActive(stream, 0)) {
                            mAudioManagerInternal.adjustSuggestedStreamVolumeForUid(stream,
                                    direction, flags, opPackageName, uid);
                        } else {
                            mAudioManagerInternal.adjustSuggestedStreamVolumeForUid(
                                    AudioManager.USE_DEFAULT_STREAM_TYPE, direction,
                                    flags | previousFlagPlaySound, opPackageName, uid);
                        }
                    } else {
                        mAudioManagerInternal.adjustStreamVolumeForUid(stream, direction, flags,
                                opPackageName, uid);
                    }
                } catch (IllegalArgumentException | SecurityException e) {
                    Log.e(TAG, "Cannot adjust volume: direction=" + direction + ", stream="
                            + stream + ", flags=" + flags + ", opPackageName=" + opPackageName
                            + ", uid=" + uid + ", useSuggested=" + useSuggested
                            + ", previousFlagPlaySound=" + previousFlagPlaySound, e);
                }
            }
        });
    }

    private void logCallbackException(
            String msg, ControllerCallbackLinkHolder holder, Exception e) {
        Log.v(TAG, msg + ", this=" + this + ", callback package=" + holder.mPackageName
                + ", exception=" + e);
    }

    private void pushPlaybackStateUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifyPlaybackStateChanged(mPlaybackState);
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushPlaybackStateUpdate",
                                holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushPlaybackStateUpdate",
                                holder, e);
                    }
                }
            }
        }
    }

    private void pushMetadataUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifyMetadataChanged(mMetadata);
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushMetadataUpdate",
                                holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushMetadataUpdate",
                                holder, e);
                    }
                }
            }
        }
    }

    private void pushQueueUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifyQueueChanged(mQueue);
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushQueueUpdate",
                                holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushQueueUpdate", holder, e);
                    }
                }
            }
        }
    }

    private void pushQueueTitleUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifyQueueTitleChanged(mQueueTitle);
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushQueueTitleUpdate",
                                holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushQueueTitleUpdate",
                                holder, e);
                    }
                }
            }
        }
    }

    private void pushExtrasUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifyExtrasChanged(mExtras);
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushExtrasUpdate",
                                holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushExtrasUpdate", holder, e);
                    }
                }
            }
        }
    }

    private void pushVolumeUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            PlaybackInfo info = getVolumeAttributes();
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifyVolumeInfoChanged(info);
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushVolumeUpdate",
                                holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushVolumeUpdate", holder, e);
                    }
                }
            }
        }
    }

    private void pushEvent(String event, Bundle data) {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifyEvent(event, data);
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushEvent", holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushEvent", holder, e);
                    }
                }
            }
        }
    }

    private void pushSessionDestroyed() {
        synchronized (mLock) {
            // This is the only method that may be (and can only be) called
            // after the session is destroyed.
            if (!mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ControllerCallbackLinkHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.notifySessionDestroyed();
                } catch (RuntimeException e) {
                    if (e.getCause() instanceof DeadObjectException) {
                        mControllerCallbackHolders.remove(i);
                        logCallbackException("Removing dead callback in pushSessionDestroyed",
                                holder, e);
                    } else {
                        logCallbackException("unexpected exception in pushSessionDestroyed",
                                holder, e);
                    }
                }
            }
            // After notifying clear all listeners
            mControllerCallbackHolders.clear();
        }
    }

    private PlaybackState getStateWithUpdatedPosition() {
        PlaybackState state;
        long duration;
        synchronized (mLock) {
            state = mPlaybackState;
            duration = mDuration;
        }
        PlaybackState result = null;
        if (state != null) {
            if (state.getState() == PlaybackState.STATE_PLAYING
                    || state.getState() == PlaybackState.STATE_FAST_FORWARDING
                    || state.getState() == PlaybackState.STATE_REWINDING) {
                long updateTime = state.getLastPositionUpdateTime();
                long currentTime = SystemClock.elapsedRealtime();
                if (updateTime > 0) {
                    long position = (long) (state.getPlaybackSpeed()
                            * (currentTime - updateTime)) + state.getPosition();
                    if (duration >= 0 && position > duration) {
                        position = duration;
                    } else if (position < 0) {
                        position = 0;
                    }
                    PlaybackState.Builder builder = new PlaybackState.Builder(state);
                    builder.setState(state.getState(), position, state.getPlaybackSpeed(),
                            currentTime);
                    result = builder.build();
                }
            }
        }
        return result == null ? state : result;
    }

    private int getControllerHolderIndexForCb(ControllerCallbackLink cb) {
        IBinder binder = cb.getBinder();
        for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
            if (binder.equals(mControllerCallbackHolders.get(i).mCallback.getBinder())) {
                return i;
            }
        }
        return -1;
    }

    private PlaybackInfo getVolumeAttributes() {
        int volumeType;
        AudioAttributes attributes;
        synchronized (mLock) {
            if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                int current = mOptimisticVolume != -1 ? mOptimisticVolume : mCurrentVolume;
                return new PlaybackInfo(mVolumeType, mVolumeControlType, mMaxVolume, current,
                        mAudioAttrs);
            }
            volumeType = mVolumeType;
            attributes = mAudioAttrs;
        }
        int stream = AudioAttributes.toLegacyStreamType(attributes);
        int max = mAudioManager.getStreamMaxVolume(stream);
        int current = mAudioManager.getStreamVolume(stream);
        return new PlaybackInfo(volumeType, VolumeProvider.VOLUME_CONTROL_ABSOLUTE, max,
                current, attributes);
    }

    private final Runnable mClearOptimisticVolumeRunnable = new Runnable() {
        @Override
        public void run() {
            boolean needUpdate = (mOptimisticVolume != mCurrentVolume);
            mOptimisticVolume = -1;
            if (needUpdate) {
                pushVolumeUpdate();
            }
        }
    };

    private final class SessionStub extends SessionLink.SessionStub {
        @Override
        public void destroySession() {
            final long token = Binder.clearCallingIdentity();
            try {
                mService.destroySession(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void sendEvent(String event, Bundle data) {
            mHandler.post(MessageHandler.MSG_SEND_EVENT, event,
                    data == null ? null : new Bundle(data));
        }

        @Override
        public ControllerLink getController() {
            return mController;
        }

        @Override
        public void setActive(boolean active) {
            mIsActive = active;
            final long token = Binder.clearCallingIdentity();
            try {
                mService.updateSession(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            mHandler.post(MessageHandler.MSG_UPDATE_SESSION_STATE);
        }

        @Override
        public void setFlags(int flags) {
            if ((flags & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY) != 0) {
                int pid = Binder.getCallingPid();
                int uid = Binder.getCallingUid();
                mService.enforcePhoneStatePermission(pid, uid);
            }
            mFlags = flags;
            if ((flags & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY) != 0) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mService.setGlobalPrioritySession(MediaSessionRecord.this);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            mHandler.post(MessageHandler.MSG_UPDATE_SESSION_STATE);
        }

        @Override
        public void setMediaButtonReceiver(PendingIntent pi) {
            mMediaButtonReceiver = pi;
            final long token = Binder.clearCallingIdentity();
            try {
                mService.onMediaButtonReceiverChanged(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setLaunchPendingIntent(PendingIntent pi) {
            mLaunchIntent = pi;
        }

        @Override
        public void setMetadata(MediaMetadata metadata, long duration, String metadataDescription) {
            synchronized (mLock) {
                MediaMetadata temp = metadata == null ? null : new MediaMetadata.Builder(metadata)
                        .build();
                // This is to guarantee that the underlying bundle is unparceled
                // before we set it to prevent concurrent reads from throwing an
                // exception
                if (temp != null) {
                    temp.size();
                }
                mMetadata = temp;
                mDuration = duration;
                mMetadataDescription = metadataDescription;
            }
            mHandler.post(MessageHandler.MSG_UPDATE_METADATA);
        }

        @Override
        public void setPlaybackState(PlaybackState state) {
            int oldState = mPlaybackState == null
                    ? PlaybackState.STATE_NONE : mPlaybackState.getState();
            int newState = state == null
                    ? PlaybackState.STATE_NONE : state.getState();
            synchronized (mLock) {
                mPlaybackState = state;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                mService.onSessionPlaystateChanged(MediaSessionRecord.this, oldState, newState);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            mHandler.post(MessageHandler.MSG_UPDATE_PLAYBACK_STATE);
        }

        @Override
        public void setQueue(List<QueueItem> queue) {
            synchronized (mLock) {
                mQueue = queue;
            }
            mHandler.post(MessageHandler.MSG_UPDATE_QUEUE);
        }

        @Override
        public void setQueueTitle(CharSequence title) {
            mQueueTitle = title;
            mHandler.post(MessageHandler.MSG_UPDATE_QUEUE_TITLE);
        }

        @Override
        public void setExtras(Bundle extras) {
            synchronized (mLock) {
                mExtras = extras == null ? null : new Bundle(extras);
            }
            mHandler.post(MessageHandler.MSG_UPDATE_EXTRAS);
        }

        @Override
        public void setRatingType(int type) {
            mRatingType = type;
        }

        @Override
        public void setCurrentVolume(int volume) {
            mCurrentVolume = volume;
            mHandler.post(MessageHandler.MSG_UPDATE_VOLUME);
        }

        @Override
        public void setPlaybackToLocal(AudioAttributes attributes) {
            boolean typeChanged;
            synchronized (mLock) {
                typeChanged = mVolumeType == PlaybackInfo.PLAYBACK_TYPE_REMOTE;
                mVolumeType = PlaybackInfo.PLAYBACK_TYPE_LOCAL;
                if (attributes != null) {
                    mAudioAttrs = attributes;
                } else {
                    Log.e(TAG, "Received null audio attributes, using existing attributes");
                }
            }
            if (typeChanged) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mService.onSessionPlaybackTypeChanged(MediaSessionRecord.this);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                mHandler.post(MessageHandler.MSG_UPDATE_VOLUME);
            }
        }

        @Override
        public void setPlaybackToRemote(int control, int max) {
            boolean typeChanged;
            synchronized (mLock) {
                typeChanged = mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL;
                mVolumeType = PlaybackInfo.PLAYBACK_TYPE_REMOTE;
                mVolumeControlType = control;
                mMaxVolume = max;
            }
            if (typeChanged) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mService.onSessionPlaybackTypeChanged(MediaSessionRecord.this);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                mHandler.post(MessageHandler.MSG_UPDATE_VOLUME);
            }
        }
    }

    class SessionCb {
        private final SessionCallbackLink mCb;

        SessionCb(SessionCallbackLink cb) {
            mCb = cb;
        }

        public boolean sendMediaButton(String packageName, int pid, int uid,
                boolean asSystemService, KeyEvent keyEvent, int sequenceId, ResultReceiver cb) {
            try {
                if (asSystemService) {
                    mCb.notifyMediaButton(mContext.getPackageName(), Process.myPid(),
                            Process.SYSTEM_UID, createMediaButtonIntent(keyEvent), sequenceId, cb);
                } else {
                    mCb.notifyMediaButton(packageName, pid, uid,
                            createMediaButtonIntent(keyEvent), sequenceId, cb);
                }
                return true;
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in sendMediaRequest.", e);
            }
            return false;
        }

        public boolean sendMediaButton(String packageName, int pid, int uid,
                ControllerCallbackLink caller, boolean asSystemService,
                KeyEvent keyEvent) {
            try {
                if (asSystemService) {
                    mCb.notifyMediaButton(mContext.getPackageName(), Process.myPid(),
                            Process.SYSTEM_UID, createMediaButtonIntent(keyEvent), 0, null);
                } else {
                    mCb.notifyMediaButtonFromController(packageName, pid, uid, caller,
                            createMediaButtonIntent(keyEvent));
                }
                return true;
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in sendMediaRequest.", e);
            }
            return false;
        }

        public void sendCommand(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String command, Bundle args, ResultReceiver cb) {
            try {
                mCb.notifyCommand(packageName, pid, uid, caller, command, args, cb);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in sendCommand.", e);
            }
        }

        public void sendCustomAction(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String action,
                Bundle args) {
            try {
                mCb.notifyCustomAction(packageName, pid, uid, caller, action, args);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in sendCustomAction.", e);
            }
        }

        public void prepare(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            try {
                mCb.notifyPrepare(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in prepare.", e);
            }
        }

        public void prepareFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
            try {
                mCb.notifyPrepareFromMediaId(packageName, pid, uid, caller, mediaId, extras);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in prepareFromMediaId.", e);
            }
        }

        public void prepareFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
            try {
                mCb.notifyPrepareFromSearch(packageName, pid, uid, caller, query, extras);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in prepareFromSearch.", e);
            }
        }

        public void prepareFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
            try {
                mCb.notifyPrepareFromUri(packageName, pid, uid, caller, uri, extras);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in prepareFromUri.", e);
            }
        }

        public void play(String packageName, int pid, int uid, ControllerCallbackLink caller) {
            try {
                mCb.notifyPlay(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in play.", e);
            }
        }

        public void playFromMediaId(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String mediaId, Bundle extras) {
            try {
                mCb.notifyPlayFromMediaId(packageName, pid, uid, caller, mediaId, extras);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in playFromMediaId.", e);
            }
        }

        public void playFromSearch(String packageName, int pid, int uid,
                ControllerCallbackLink caller, String query, Bundle extras) {
            try {
                mCb.notifyPlayFromSearch(packageName, pid, uid, caller, query, extras);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in playFromSearch.", e);
            }
        }

        public void playFromUri(String packageName, int pid, int uid,
                ControllerCallbackLink caller, Uri uri, Bundle extras) {
            try {
                mCb.notifyPlayFromUri(packageName, pid, uid, caller, uri, extras);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in playFromUri.", e);
            }
        }

        public void skipToTrack(String packageName, int pid, int uid,
                ControllerCallbackLink caller, long id) {
            try {
                mCb.notifySkipToTrack(packageName, pid, uid, caller, id);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in skipToTrack", e);
            }
        }

        public void pause(String packageName, int pid, int uid, ControllerCallbackLink caller) {
            try {
                mCb.notifyPause(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in pause.", e);
            }
        }

        public void stop(String packageName, int pid, int uid, ControllerCallbackLink caller) {
            try {
                mCb.notifyStop(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in stop.", e);
            }
        }

        public void next(String packageName, int pid, int uid, ControllerCallbackLink caller) {
            try {
                mCb.notifyNext(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in next.", e);
            }
        }

        public void previous(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            try {
                mCb.notifyPrevious(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in previous.", e);
            }
        }

        public void fastForward(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            try {
                mCb.notifyFastForward(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in fastForward.", e);
            }
        }

        public void rewind(String packageName, int pid, int uid,
                ControllerCallbackLink caller) {
            try {
                mCb.notifyRewind(packageName, pid, uid, caller);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in rewind.", e);
            }
        }

        public void seekTo(String packageName, int pid, int uid, ControllerCallbackLink caller,
                long pos) {
            try {
                mCb.notifySeekTo(packageName, pid, uid, caller, pos);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in seekTo.", e);
            }
        }

        public void rate(String packageName, int pid, int uid, ControllerCallbackLink caller,
                Rating rating) {
            try {
                mCb.notifyRate(packageName, pid, uid, caller, rating);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in rate.", e);
            }
        }

        public void adjustVolume(String packageName, int pid, int uid,
                ControllerCallbackLink caller, boolean asSystemService, int direction) {
            try {
                if (asSystemService) {
                    mCb.notifyAdjustVolume(mContext.getPackageName(), Process.myPid(),
                            Process.SYSTEM_UID, null, direction);
                } else {
                    mCb.notifyAdjustVolume(packageName, pid, uid, caller, direction);
                }
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in adjustVolume.", e);
            }
        }

        public void setVolumeTo(String packageName, int pid, int uid,
                ControllerCallbackLink caller, int value) {
            try {
                mCb.notifySetVolumeTo(packageName, pid, uid, caller, value);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Remote failure in setVolumeTo.", e);
            }
        }

        private Intent createMediaButtonIntent(KeyEvent keyEvent) {
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
            return mediaButtonIntent;
        }
    }

    class ControllerStub extends ControllerLink.ControllerStub {
        @Override
        public void sendCommand(String packageName, ControllerCallbackLink caller,
                String command, Bundle args, ResultReceiver cb) {
            mSessionCb.sendCommand(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller, command, args, cb);
        }

        @Override
        public boolean sendMediaButton(String packageName, ControllerCallbackLink cb,
                KeyEvent keyEvent) {
            return mSessionCb.sendMediaButton(packageName, Binder.getCallingPid(),
                    Binder.getCallingUid(), cb, false, keyEvent);
        }

        @Override
        public void registerCallback(String packageName, ControllerCallbackLink cb) {
            synchronized (mLock) {
                // If this session is already destroyed tell the caller and
                // don't add them.
                if (mDestroyed) {
                    try {
                        cb.notifySessionDestroyed();
                    } catch (Exception e) {
                        // ignored
                    }
                    return;
                }
                if (getControllerHolderIndexForCb(cb) < 0) {
                    mControllerCallbackHolders.add(new ControllerCallbackLinkHolder(cb,
                            packageName, Binder.getCallingUid()));
                    if (DEBUG) {
                        Log.d(TAG, "registering controller callback " + cb + " from controller"
                                + packageName);
                    }
                }
            }
        }

        @Override
        public void unregisterCallback(ControllerCallbackLink cb) {
            synchronized (mLock) {
                int index = getControllerHolderIndexForCb(cb);
                if (index != -1) {
                    mControllerCallbackHolders.remove(index);
                }
                if (DEBUG) {
                    Log.d(TAG, "unregistering callback " + cb.getBinder());
                }
            }
        }

        @Override
        public String getPackageName() {
            return mPackageName;
        }

        @Override
        public String getTag() {
            return mTag;
        }

        @Override
        public PendingIntent getLaunchPendingIntent() {
            return mLaunchIntent;
        }

        @Override
        public long getFlags() {
            return mFlags;
        }

        @Override
        public PlaybackInfo getVolumeAttributes() {
            return MediaSessionRecord.this.getVolumeAttributes();
        }

        @Override
        public void adjustVolume(String packageName, String opPackageName,
                ControllerCallbackLink caller, int direction, int flags) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.adjustVolume(packageName, opPackageName, pid, uid, caller,
                        false, direction, flags, false /* useSuggested */);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setVolumeTo(String packageName, String opPackageName,
                ControllerCallbackLink caller, int value, int flags) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.setVolumeTo(packageName, opPackageName, pid, uid, caller,
                        value, flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void prepare(String packageName, ControllerCallbackLink caller) {
            mSessionCb.prepare(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        @Override
        public void prepareFromMediaId(String packageName, ControllerCallbackLink caller,
                String mediaId, Bundle extras) {
            mSessionCb.prepareFromMediaId(packageName, Binder.getCallingPid(),
                    Binder.getCallingUid(), caller, mediaId, extras);
        }

        @Override
        public void prepareFromSearch(String packageName, ControllerCallbackLink caller,
                String query, Bundle extras) {
            mSessionCb.prepareFromSearch(packageName, Binder.getCallingPid(),
                    Binder.getCallingUid(), caller, query, extras);
        }

        @Override
        public void prepareFromUri(String packageName, ControllerCallbackLink caller,
                Uri uri, Bundle extras) {
            mSessionCb.prepareFromUri(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller, uri, extras);
        }

        @Override
        public void play(String packageName, ControllerCallbackLink caller) {
            mSessionCb.play(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        @Override
        public void playFromMediaId(String packageName, ControllerCallbackLink caller,
                String mediaId, Bundle extras) {
            mSessionCb.playFromMediaId(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller, mediaId, extras);
        }

        @Override
        public void playFromSearch(String packageName, ControllerCallbackLink caller,
                String query, Bundle extras) {
            mSessionCb.playFromSearch(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller, query, extras);
        }

        @Override
        public void playFromUri(String packageName, ControllerCallbackLink caller,
                Uri uri, Bundle extras) {
            mSessionCb.playFromUri(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller, uri, extras);
        }

        @Override
        public void skipToQueueItem(String packageName, ControllerCallbackLink caller,
                long id) {
            mSessionCb.skipToTrack(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller, id);
        }

        @Override
        public void pause(String packageName, ControllerCallbackLink caller) {
            mSessionCb.pause(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        @Override
        public void stop(String packageName, ControllerCallbackLink caller) {
            mSessionCb.stop(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        @Override
        public void next(String packageName, ControllerCallbackLink caller) {
            mSessionCb.next(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        @Override
        public void previous(String packageName, ControllerCallbackLink caller) {
            mSessionCb.previous(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller);
        }

        @Override
        public void fastForward(String packageName, ControllerCallbackLink caller) {
            mSessionCb.fastForward(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller);
        }

        @Override
        public void rewind(String packageName, ControllerCallbackLink caller) {
            mSessionCb.rewind(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller);
        }

        @Override
        public void seekTo(String packageName, ControllerCallbackLink caller, long pos) {
            mSessionCb.seekTo(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller,
                    pos);
        }

        @Override
        public void rate(String packageName, ControllerCallbackLink caller, Rating rating) {
            mSessionCb.rate(packageName, Binder.getCallingPid(), Binder.getCallingUid(), caller,
                    rating);
        }

        @Override
        public void sendCustomAction(String packageName, ControllerCallbackLink caller,
                String action, Bundle args) {
            mSessionCb.sendCustomAction(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    caller, action, args);
        }

        @Override
        public MediaMetadata getMetadata() {
            synchronized (mLock) {
                return mMetadata;
            }
        }

        @Override
        public PlaybackState getPlaybackState() {
            return getStateWithUpdatedPosition();
        }

        @Override
        public List<QueueItem> getQueue() {
            synchronized (mLock) {
                return mQueue;
            }
        }

        @Override
        public CharSequence getQueueTitle() {
            return mQueueTitle;
        }

        @Override
        public Bundle getExtras() {
            synchronized (mLock) {
                return mExtras;
            }
        }

        @Override
        public int getRatingType() {
            return mRatingType;
        }
    }

    private class ControllerCallbackLinkHolder {
        private final ControllerCallbackLink mCallback;
        private final String mPackageName;
        private final int mUid;

        ControllerCallbackLinkHolder(ControllerCallbackLink callback, String packageName,
                int uid) {
            mCallback = callback;
            mPackageName = packageName;
            mUid = uid;
        }
    }

    private class MessageHandler extends Handler {
        private static final int MSG_UPDATE_METADATA = 1;
        private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
        private static final int MSG_UPDATE_QUEUE = 3;
        private static final int MSG_UPDATE_QUEUE_TITLE = 4;
        private static final int MSG_UPDATE_EXTRAS = 5;
        private static final int MSG_SEND_EVENT = 6;
        private static final int MSG_UPDATE_SESSION_STATE = 7;
        private static final int MSG_UPDATE_VOLUME = 8;
        private static final int MSG_DESTROYED = 9;

        public MessageHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_METADATA:
                    pushMetadataUpdate();
                    break;
                case MSG_UPDATE_PLAYBACK_STATE:
                    pushPlaybackStateUpdate();
                    break;
                case MSG_UPDATE_QUEUE:
                    pushQueueUpdate();
                    break;
                case MSG_UPDATE_QUEUE_TITLE:
                    pushQueueTitleUpdate();
                    break;
                case MSG_UPDATE_EXTRAS:
                    pushExtrasUpdate();
                    break;
                case MSG_SEND_EVENT:
                    pushEvent((String) msg.obj, msg.getData());
                    break;
                case MSG_UPDATE_SESSION_STATE:
                    // TODO add session state
                    break;
                case MSG_UPDATE_VOLUME:
                    pushVolumeUpdate();
                    break;
                case MSG_DESTROYED:
                    pushSessionDestroyed();
            }
        }

        public void post(int what) {
            post(what, null);
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
        }

        public void post(int what, Object obj, Bundle data) {
            Message msg = obtainMessage(what, obj);
            msg.setData(data);
            msg.sendToTarget();
        }
    }

}
