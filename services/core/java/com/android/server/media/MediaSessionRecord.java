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
import android.content.pm.ParceledListSlice;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionController;
import android.media.session.ISessionControllerCallback;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.ParcelableVolumeInfo;
import android.media.session.PlaybackState;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.ArrayList;

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

    private static final int UID_NOT_SET = -1;

    private final MessageHandler mHandler;

    private final int mOwnerPid;
    private final int mOwnerUid;
    private final int mUserId;
    private final String mPackageName;
    private final String mTag;
    private final ControllerStub mController;
    private final SessionStub mSession;
    private final SessionCb mSessionCb;
    private final MediaSessionService mService;

    private final Object mLock = new Object();
    private final ArrayList<ISessionControllerCallbackHolder> mControllerCallbackHolders =
            new ArrayList<>();

    private long mFlags;
    private PendingIntent mMediaButtonReceiver;
    private PendingIntent mLaunchIntent;

    // TransportPerformer fields

    private Bundle mExtras;
    private MediaMetadata mMetadata;
    private PlaybackState mPlaybackState;
    private ParceledListSlice mQueue;
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

    private int mCallingUid = UID_NOT_SET;
    private String mCallingPackage;

    public MediaSessionRecord(int ownerPid, int ownerUid, int userId, String ownerPackageName,
            ISessionCallback cb, String tag, MediaSessionService service, Looper handlerLooper) {
        mOwnerPid = ownerPid;
        mOwnerUid = ownerUid;
        mUserId = userId;
        mPackageName = ownerPackageName;
        mTag = tag;
        mController = new ControllerStub();
        mSession = new SessionStub();
        mSessionCb = new SessionCb(cb);
        mService = service;
        mHandler = new MessageHandler(handlerLooper);
        mAudioManager = (AudioManager) service.getContext().getSystemService(Context.AUDIO_SERVICE);
        mAudioManagerInternal = LocalServices.getService(AudioManagerInternal.class);
        mAudioAttrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    }

    /**
     * Get the binder for the {@link MediaSession}.
     *
     * @return The session binder apps talk to.
     */
    public ISession getSessionBinder() {
        return mSession;
    }

    /**
     * Get the binder for the {@link MediaController}.
     *
     * @return The controller binder apps talk to.
     */
    public ISessionController getControllerBinder() {
        return mController;
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
     * @param direction The direction to adjust volume in.
     * @param flags Any of the flags from {@link AudioManager}.
     * @param packageName The package that made the original volume request.
     * @param uid The uid that made the original volume request.
     * @param useSuggested True to use adjustSuggestedStreamVolume instead of
     *            adjustStreamVolume.
     */
    public void adjustVolume(int direction, int flags, String packageName, int uid,
            boolean useSuggested) {
        int previousFlagPlaySound = flags & AudioManager.FLAG_PLAY_SOUND;
        if (isPlaybackActive() || hasFlag(MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY)) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }
        if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            // Adjust the volume with a handler not to be blocked by other system service.
            int stream = AudioAttributes.toLegacyStreamType(mAudioAttrs);
            postAdjustLocalVolume(stream, direction, flags, packageName, uid, useSuggested,
                    previousFlagPlaySound);
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
            mSessionCb.adjustVolume(direction);

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

    public void setVolumeTo(int value, int flags, String packageName, int uid) {
        if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            int stream = AudioAttributes.toLegacyStreamType(mAudioAttrs);
            mAudioManagerInternal.setStreamVolumeForUid(stream, value, flags, packageName, uid);
        } else {
            if (mVolumeControlType != VolumeProvider.VOLUME_CONTROL_ABSOLUTE) {
                // Nothing to do. The volume can't be set directly.
                return;
            }
            value = Math.max(0, Math.min(value, mMaxVolume));
            mSessionCb.setVolumeTo(value);

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

    public ISessionCallback getCallback() {
        return mSessionCb.mCb;
    }

    public void sendMediaButton(KeyEvent ke, int sequenceId,
            ResultReceiver cb, int uid, String packageName) {
        updateCallingPackage(uid, packageName);
        mSessionCb.sendMediaButton(ke, sequenceId, cb);
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
        pw.println(indent + "metadata:" + getShortMetadataString());
        pw.println(indent + "queueTitle=" + mQueueTitle + ", size="
                + (mQueue == null ? 0 : mQueue.getList().size()));
    }

    @Override
    public String toString() {
        return mPackageName + "/" + mTag + " (userId=" + mUserId + ")";
    }

    private void postAdjustLocalVolume(final int stream, final int direction, final int flags,
            final String packageName, final int uid, final boolean useSuggested,
            final int previousFlagPlaySound) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (useSuggested) {
                        if (AudioSystem.isStreamActive(stream, 0)) {
                            mAudioManagerInternal.adjustSuggestedStreamVolumeForUid(stream,
                                    direction, flags, packageName, uid);
                        } else {
                            mAudioManagerInternal.adjustSuggestedStreamVolumeForUid(
                                    AudioManager.USE_DEFAULT_STREAM_TYPE, direction,
                                    flags | previousFlagPlaySound, packageName, uid);
                        }
                    } else {
                        mAudioManagerInternal.adjustStreamVolumeForUid(stream, direction, flags,
                                packageName, uid);
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Cannot adjust volume: direction=" + direction + ", stream="
                            + stream + ", flags=" + flags + ", packageName=" + packageName
                            + ", uid=" + uid + ", useSuggested=" + useSuggested
                            + ", previousFlagPlaySound=" + previousFlagPlaySound, e);
                }
            }
        });
    }

    private String getShortMetadataString() {
        int fields = mMetadata == null ? 0 : mMetadata.size();
        MediaDescription description = mMetadata == null ? null : mMetadata
                .getDescription();
        return "size=" + fields + ", description=" + description;
    }

    private void logCallbackException(
            String msg, ISessionControllerCallbackHolder holder, Exception e) {
        Log.v(TAG, msg + ", this=" + this + ", callback package=" + holder.mPackageName
                + ", exception=" + e);
    }

    private void pushPlaybackStateUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onPlaybackStateChanged(mPlaybackState);
                } catch (DeadObjectException e) {
                    mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushPlaybackStateUpdate",
                            holder, e);
                } catch (RemoteException e) {
                    logCallbackException("unexpected exception in pushPlaybackStateUpdate",
                            holder, e);
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
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onMetadataChanged(mMetadata);
                } catch (DeadObjectException e) {
                    logCallbackException("Removing dead callback in pushMetadataUpdate", holder, e);
                    mControllerCallbackHolders.remove(i);
                } catch (RemoteException e) {
                    logCallbackException("unexpected exception in pushMetadataUpdate", holder, e);
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
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onQueueChanged(mQueue);
                } catch (DeadObjectException e) {
                    mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushQueueUpdate", holder, e);
                } catch (RemoteException e) {
                    logCallbackException("unexpected exception in pushQueueUpdate", holder, e);
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
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onQueueTitleChanged(mQueueTitle);
                } catch (DeadObjectException e) {
                    mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushQueueTitleUpdate",
                            holder, e);
                } catch (RemoteException e) {
                    logCallbackException("unexpected exception in pushQueueTitleUpdate",
                            holder, e);
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
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onExtrasChanged(mExtras);
                } catch (DeadObjectException e) {
                    mControllerCallbackHolders.remove(i);
                    logCallbackException("Removed dead callback in pushExtrasUpdate", holder, e);
                } catch (RemoteException e) {
                    logCallbackException("unexpected exception in pushExtrasUpdate", holder, e);
                }
            }
        }
    }

    private void pushVolumeUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            ParcelableVolumeInfo info = mController.getVolumeAttributes();
            for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onVolumeInfoChanged(info);
                } catch (DeadObjectException e) {
                    logCallbackException("Removing dead callback in pushVolumeUpdate", holder, e);
                } catch (RemoteException e) {
                    logCallbackException("Unexpected exception in pushVolumeUpdate", holder, e);
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
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onEvent(event, data);
                } catch (DeadObjectException e) {
                    logCallbackException("Removing dead callback in pushEvent", holder, e);
                    mControllerCallbackHolders.remove(i);
                } catch (RemoteException e) {
                    logCallbackException("unexpected exception in pushEvent", holder, e);
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
                ISessionControllerCallbackHolder holder = mControllerCallbackHolders.get(i);
                try {
                    holder.mCallback.onSessionDestroyed();
                } catch (DeadObjectException e) {
                    logCallbackException("Removing dead callback in pushEvent", holder, e);
                    mControllerCallbackHolders.remove(i);
                } catch (RemoteException e) {
                    logCallbackException("unexpected exception in pushEvent", holder, e);
                }
            }
            // After notifying clear all listeners
            mControllerCallbackHolders.clear();
        }
    }

    private PlaybackState getStateWithUpdatedPosition() {
        PlaybackState state;
        long duration = -1;
        synchronized (mLock) {
            state = mPlaybackState;
            if (mMetadata != null && mMetadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                duration = mMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
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

    private int getControllerHolderIndexForCb(ISessionControllerCallback cb) {
        IBinder binder = cb.asBinder();
        for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
            if (binder.equals(mControllerCallbackHolders.get(i).mCallback.asBinder())) {
                return i;
            }
        }
        return -1;
    }

    private void updateCallingPackage() {
        updateCallingPackage(UID_NOT_SET, null);
    }

    private void updateCallingPackage(int uid, String packageName) {
        if (uid == UID_NOT_SET) {
            uid = Binder.getCallingUid();
        }
        synchronized (mLock) {
            if (mCallingUid == UID_NOT_SET || mCallingUid != uid) {
                mCallingUid = uid;
                mCallingPackage = packageName != null ? packageName : getPackageName(uid);
            }
        }
    }

    private String getPackageName(int uid) {
        Context context = mService.getContext();
        if (context == null) {
            return null;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            return packages[0];
        }
        return null;
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

    private final class SessionStub extends ISession.Stub {
        @Override
        public void destroy() {
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
        public ISessionController getController() {
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
                int pid = getCallingPid();
                int uid = getCallingUid();
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
        public void setMetadata(MediaMetadata metadata) {
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
        public void setQueue(ParceledListSlice queue) {
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

        @Override
        public String getCallingPackage() {
            return mCallingPackage;
        }
    }

    class SessionCb {
        private final ISessionCallback mCb;

        public SessionCb(ISessionCallback cb) {
            mCb = cb;
        }

        public boolean sendMediaButton(KeyEvent keyEvent, int sequenceId, ResultReceiver cb) {
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
            try {
                mCb.onMediaButton(mediaButtonIntent, sequenceId, cb);
                return true;
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendMediaRequest.", e);
            }
            return false;
        }

        public void sendCommand(String command, Bundle args, ResultReceiver cb) {
            try {
                mCb.onCommand(command, args, cb);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendCommand.", e);
            }
        }

        public void sendCustomAction(String action, Bundle args) {
            try {
                mCb.onCustomAction(action, args);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendCustomAction.", e);
            }
        }

        public void prepare() {
            try {
                mCb.onPrepare();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in prepare.", e);
            }
        }

        public void prepareFromMediaId(String mediaId, Bundle extras) {
            try {
                mCb.onPrepareFromMediaId(mediaId, extras);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in prepareFromMediaId.", e);
            }
        }

        public void prepareFromSearch(String query, Bundle extras) {
            try {
                mCb.onPrepareFromSearch(query, extras);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in prepareFromSearch.", e);
            }
        }

        public void prepareFromUri(Uri uri, Bundle extras) {
            try {
                mCb.onPrepareFromUri(uri, extras);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in prepareFromUri.", e);
            }
        }

        public void play() {
            try {
                mCb.onPlay();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in play.", e);
            }
        }

        public void playFromMediaId(String mediaId, Bundle extras) {
            try {
                mCb.onPlayFromMediaId(mediaId, extras);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in playFromMediaId.", e);
            }
        }

        public void playFromSearch(String query, Bundle extras) {
            try {
                mCb.onPlayFromSearch(query, extras);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in playFromSearch.", e);
            }
        }

        public void playFromUri(Uri uri, Bundle extras) {
            try {
                mCb.onPlayFromUri(uri, extras);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in playFromUri.", e);
            }
        }

        public void skipToTrack(long id) {
            try {
                mCb.onSkipToTrack(id);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in skipToTrack", e);
            }
        }

        public void pause() {
            try {
                mCb.onPause();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in pause.", e);
            }
        }

        public void stop() {
            try {
                mCb.onStop();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in stop.", e);
            }
        }

        public void next() {
            try {
                mCb.onNext();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in next.", e);
            }
        }

        public void previous() {
            try {
                mCb.onPrevious();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in previous.", e);
            }
        }

        public void fastForward() {
            try {
                mCb.onFastForward();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in fastForward.", e);
            }
        }

        public void rewind() {
            try {
                mCb.onRewind();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in rewind.", e);
            }
        }

        public void seekTo(long pos) {
            try {
                mCb.onSeekTo(pos);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in seekTo.", e);
            }
        }

        public void rate(Rating rating) {
            try {
                mCb.onRate(rating);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in rate.", e);
            }
        }

        public void adjustVolume(int direction) {
            try {
                mCb.onAdjustVolume(direction);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in adjustVolume.", e);
            }
        }

        public void setVolumeTo(int value) {
            try {
                mCb.onSetVolumeTo(value);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in setVolumeTo.", e);
            }
        }
    }

    class ControllerStub extends ISessionController.Stub {
        @Override
        public void sendCommand(String command, Bundle args, ResultReceiver cb)
                throws RemoteException {
            updateCallingPackage();
            mSessionCb.sendCommand(command, args, cb);
        }

        @Override
        public boolean sendMediaButton(KeyEvent mediaButtonIntent) {
            updateCallingPackage();
            return mSessionCb.sendMediaButton(mediaButtonIntent, 0, null);
        }

        @Override
        public void registerCallbackListener(ISessionControllerCallback cb) {
            synchronized (mLock) {
                // If this session is already destroyed tell the caller and
                // don't add them.
                if (mDestroyed) {
                    try {
                        cb.onSessionDestroyed();
                    } catch (Exception e) {
                        // ignored
                    }
                    return;
                }
                if (getControllerHolderIndexForCb(cb) < 0) {
                    mControllerCallbackHolders.add(new ISessionControllerCallbackHolder(cb,
                          Binder.getCallingUid()));
                    if (DEBUG) {
                        Log.d(TAG, "registering controller callback " + cb);
                    }
                }
            }
        }

        @Override
        public void unregisterCallbackListener(ISessionControllerCallback cb)
                throws RemoteException {
            synchronized (mLock) {
                int index = getControllerHolderIndexForCb(cb);
                if (index != -1) {
                    mControllerCallbackHolders.remove(index);
                }
                if (DEBUG) {
                    Log.d(TAG, "unregistering callback " + cb + ". index=" + index);
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
        public ParcelableVolumeInfo getVolumeAttributes() {
            int volumeType;
            AudioAttributes attributes;
            synchronized (mLock) {
                if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                    int current = mOptimisticVolume != -1 ? mOptimisticVolume : mCurrentVolume;
                    return new ParcelableVolumeInfo(
                            mVolumeType, mAudioAttrs, mVolumeControlType, mMaxVolume, current);
                }
                volumeType = mVolumeType;
                attributes = mAudioAttrs;
            }
            int stream = AudioAttributes.toLegacyStreamType(attributes);
            int max = mAudioManager.getStreamMaxVolume(stream);
            int current = mAudioManager.getStreamVolume(stream);
            return new ParcelableVolumeInfo(
                    volumeType, attributes, VolumeProvider.VOLUME_CONTROL_ABSOLUTE, max, current);
        }

        @Override
        public void adjustVolume(int direction, int flags, String packageName) {
            updateCallingPackage();
            int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.adjustVolume(direction, flags, packageName, uid, false);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setVolumeTo(int value, int flags, String packageName) {
            updateCallingPackage();
            int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.setVolumeTo(value, flags, packageName, uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void prepare() throws RemoteException {
            updateCallingPackage();
            mSessionCb.prepare();
        }

        @Override
        public void prepareFromMediaId(String mediaId, Bundle extras)
                throws RemoteException {
            updateCallingPackage();
            mSessionCb.prepareFromMediaId(mediaId, extras);
        }

        @Override
        public void prepareFromSearch(String query, Bundle extras) throws RemoteException {
            updateCallingPackage();
            mSessionCb.prepareFromSearch(query, extras);
        }

        @Override
        public void prepareFromUri(Uri uri, Bundle extras) throws RemoteException {
            updateCallingPackage();
            mSessionCb.prepareFromUri(uri, extras);
        }

        @Override
        public void play() throws RemoteException {
            updateCallingPackage();
            mSessionCb.play();
        }

        @Override
        public void playFromMediaId(String mediaId, Bundle extras) throws RemoteException {
            updateCallingPackage();
            mSessionCb.playFromMediaId(mediaId, extras);
        }

        @Override
        public void playFromSearch(String query, Bundle extras) throws RemoteException {
            updateCallingPackage();
            mSessionCb.playFromSearch(query, extras);
        }

        @Override
        public void playFromUri(Uri uri, Bundle extras) throws RemoteException {
            updateCallingPackage();
            mSessionCb.playFromUri(uri, extras);
        }

        @Override
        public void skipToQueueItem(long id) {
            updateCallingPackage();
            mSessionCb.skipToTrack(id);
        }

        @Override
        public void pause() throws RemoteException {
            updateCallingPackage();
            mSessionCb.pause();
        }

        @Override
        public void stop() throws RemoteException {
            updateCallingPackage();
            mSessionCb.stop();
        }

        @Override
        public void next() throws RemoteException {
            updateCallingPackage();
            mSessionCb.next();
        }

        @Override
        public void previous() throws RemoteException {
            updateCallingPackage();
            mSessionCb.previous();
        }

        @Override
        public void fastForward() throws RemoteException {
            updateCallingPackage();
            mSessionCb.fastForward();
        }

        @Override
        public void rewind() throws RemoteException {
            updateCallingPackage();
            mSessionCb.rewind();
        }

        @Override
        public void seekTo(long pos) throws RemoteException {
            updateCallingPackage();
            mSessionCb.seekTo(pos);
        }

        @Override
        public void rate(Rating rating) throws RemoteException {
            updateCallingPackage();
            mSessionCb.rate(rating);
        }

        @Override
        public void sendCustomAction(String action, Bundle args)
                throws RemoteException {
            updateCallingPackage();
            mSessionCb.sendCustomAction(action, args);
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
        public ParceledListSlice getQueue() {
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

        @Override
        public boolean isTransportControlEnabled() {
            return MediaSessionRecord.this.isTransportControlEnabled();
        }
    }

    private class ISessionControllerCallbackHolder {
        private final ISessionControllerCallback mCallback;
        private final String mPackageName;

        ISessionControllerCallbackHolder(ISessionControllerCallback callback, int uid) {
            mCallback = callback;
            mPackageName = getPackageName(uid);
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
