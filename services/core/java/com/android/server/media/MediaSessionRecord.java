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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.routing.IMediaRouter;
import android.media.routing.IMediaRouterDelegate;
import android.media.routing.IMediaRouterStateCallback;
import android.media.session.ISessionController;
import android.media.session.ISessionControllerCallback;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionInfo;
import android.media.session.PlaybackState;
import android.media.session.ParcelableVolumeInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
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
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.KeyEvent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This is the system implementation of a Session. Apps will interact with the
 * MediaSession wrapper class instead.
 */
public class MediaSessionRecord implements IBinder.DeathRecipient {
    private static final String TAG = "MediaSessionRecord";
    private static final boolean DEBUG = false;

    /**
     * The length of time a session will still be considered active after
     * pausing in ms.
     */
    private static final int ACTIVE_BUFFER = 30000;

    /**
     * The amount of time we'll send an assumed volume after the last volume
     * command before reverting to the last reported volume.
     */
    private static final int OPTIMISTIC_VOLUME_TIMEOUT = 1000;

    private final MessageHandler mHandler;

    private final int mOwnerPid;
    private final int mOwnerUid;
    private final int mUserId;
    private final MediaSessionInfo mSessionInfo;
    private final String mTag;
    private final ControllerStub mController;
    private final SessionStub mSession;
    private final SessionCb mSessionCb;
    private final MediaSessionService mService;

    private final Object mLock = new Object();
    private final ArrayList<ISessionControllerCallback> mControllerCallbacks =
            new ArrayList<ISessionControllerCallback>();

    private long mFlags;
    private IMediaRouter mMediaRouter;
    private ComponentName mMediaButtonReceiver;

    // TransportPerformer fields

    private MediaMetadata mMetadata;
    private PlaybackState mPlaybackState;
    private int mRatingType;
    private long mLastActiveTime;
    // End TransportPerformer fields

    // Volume handling fields
    private AudioManager mAudioManager;
    private int mVolumeType = MediaSession.PLAYBACK_TYPE_LOCAL;
    private int mAudioStream = AudioManager.STREAM_MUSIC;
    private int mVolumeControlType = VolumeProvider.VOLUME_CONTROL_ABSOLUTE;
    private int mMaxVolume = 0;
    private int mCurrentVolume = 0;
    private int mOptimisticVolume = -1;
    // End volume handling fields

    private boolean mIsActive = false;
    private boolean mDestroyed = false;

    public MediaSessionRecord(int ownerPid, int ownerUid, int userId, String ownerPackageName,
            ISessionCallback cb, String tag, MediaSessionService service, Handler handler) {
        mOwnerPid = ownerPid;
        mOwnerUid = ownerUid;
        mUserId = userId;
        mSessionInfo = new MediaSessionInfo(UUID.randomUUID().toString(), ownerPackageName,
                ownerPid);
        mTag = tag;
        mController = new ControllerStub();
        mSession = new SessionStub();
        mSessionCb = new SessionCb(cb);
        mService = service;
        mHandler = new MessageHandler(handler.getLooper());
        mAudioManager = (AudioManager) service.getContext().getSystemService(Context.AUDIO_SERVICE);
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
    public MediaSessionInfo getSessionInfo() {
        return mSessionInfo;
    }

    public ComponentName getMediaButtonReceiver() {
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
     * Send a volume adjustment to the session owner.
     *
     * @param delta The amount to adjust the volume by.
     */
    public void adjustVolumeBy(int delta, int flags) {
        if (isPlaybackActive(false)) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }
        if (mVolumeType == MediaSession.PLAYBACK_TYPE_LOCAL) {
            if (delta == 0) {
                mAudioManager.adjustStreamVolume(mAudioStream, delta, flags);
            } else {
                int direction = 0;
                int steps = delta;
                if (delta > 0) {
                    direction = 1;
                } else if (delta < 0) {
                    direction = -1;
                    steps = -delta;
                }
                for (int i = 0; i < steps; i++) {
                    mAudioManager.adjustStreamVolume(mAudioStream, direction, flags);
                }
            }
        } else {
            if (mVolumeControlType == VolumeProvider.VOLUME_CONTROL_FIXED) {
                // Nothing to do, the volume cannot be changed
                return;
            }
            mSessionCb.adjustVolumeBy(delta);

            int volumeBefore = (mOptimisticVolume < 0 ? mCurrentVolume : mOptimisticVolume);
            mOptimisticVolume = volumeBefore + delta;
            mOptimisticVolume = Math.max(0, Math.min(mOptimisticVolume, mMaxVolume));
            mHandler.removeCallbacks(mClearOptimisticVolumeRunnable);
            mHandler.postDelayed(mClearOptimisticVolumeRunnable, OPTIMISTIC_VOLUME_TIMEOUT);
            if (volumeBefore != mOptimisticVolume) {
                pushVolumeUpdate();
            }

            if (DEBUG) {
                Log.d(TAG, "Adjusted optimistic volume to " + mOptimisticVolume + " max is "
                        + mMaxVolume);
            }
        }
    }

    public void setVolumeTo(int value, int flags) {
        if (mVolumeType == MediaSession.PLAYBACK_TYPE_LOCAL) {
            mAudioManager.setStreamVolume(mAudioStream, value, flags);
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
     * Check if the session is currently performing playback. This will also
     * return true if the session was recently paused.
     *
     * @param includeRecentlyActive True if playback that was recently paused
     *            should count, false if it shouldn't.
     * @return True if the session is performing playback, false otherwise.
     */
    public boolean isPlaybackActive(boolean includeRecentlyActive) {
        int state = mPlaybackState == null ? 0 : mPlaybackState.getState();
        if (MediaSession.isActiveState(state)) {
            return true;
        }
        if (includeRecentlyActive && state == mPlaybackState.STATE_PAUSED) {
            long inactiveTime = SystemClock.uptimeMillis() - mLastActiveTime;
            if (inactiveTime < ACTIVE_BUFFER) {
                return true;
            }
        }
        return false;
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
    public int getAudioStream() {
        return mAudioStream;
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
        }
    }

    public ISessionCallback getCallback() {
        return mSessionCb.mCb;
    }

    public void sendMediaButton(KeyEvent ke, int sequenceId, ResultReceiver cb) {
        mSessionCb.sendMediaButton(ke, sequenceId, cb);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + mTag + " " + this);

        final String indent = prefix + "  ";
        pw.println(indent + "ownerPid=" + mOwnerPid + ", ownerUid=" + mOwnerUid
                + ", userId=" + mUserId);
        pw.println(indent + "info=" + mSessionInfo.toString());
        pw.println(indent + "active=" + mIsActive);
        pw.println(indent + "flags=" + mFlags);
        pw.println(indent + "rating type=" + mRatingType);
        pw.println(indent + "controllers: " + mControllerCallbacks.size());
        pw.println(indent + "state=" + (mPlaybackState == null ? null : mPlaybackState.toString()));
        pw.println(indent + "metadata:" + getShortMetadataString());
    }

    private String getShortMetadataString() {
        int fields = mMetadata == null ? 0 : mMetadata.size();
        String title = mMetadata == null ? null : mMetadata
                .getString(MediaMetadata.METADATA_KEY_TITLE);
        return "size=" + fields + ", title=" + title;
    }

    private void pushPlaybackStateUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onPlaybackStateChanged(mPlaybackState);
                } catch (DeadObjectException e) {
                    mControllerCallbacks.remove(i);
                    Log.w(TAG, "Removed dead callback in pushPlaybackStateUpdate.", e);
                } catch (RemoteException e) {
                    Log.w(TAG, "unexpected exception in pushPlaybackStateUpdate.", e);
                }
            }
        }
    }

    private void pushMetadataUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onMetadataChanged(mMetadata);
                } catch (DeadObjectException e) {
                    Log.w(TAG, "Removing dead callback in pushMetadataUpdate. ", e);
                    mControllerCallbacks.remove(i);
                } catch (RemoteException e) {
                    Log.w(TAG, "unexpected exception in pushMetadataUpdate. ", e);
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
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onVolumeInfoChanged(info);
                } catch (DeadObjectException e) {
                    Log.w(TAG, "Removing dead callback in pushVolumeUpdate. ", e);
                } catch (RemoteException e) {
                    Log.w(TAG, "Unexpected exception in pushVolumeUpdate. ", e);
                }
            }
        }
    }

    private void pushEvent(String event, Bundle data) {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onEvent(event, data);
                } catch (DeadObjectException e) {
                    Log.w(TAG, "Removing dead callback in pushEvent.", e);
                    mControllerCallbacks.remove(i);
                } catch (RemoteException e) {
                    Log.w(TAG, "unexpected exception in pushEvent.", e);
                }
            }
        }
    }

    private PlaybackState getStateWithUpdatedPosition() {
        PlaybackState state = mPlaybackState;
        long duration = -1;
        if (mMetadata != null && mMetadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
            duration = mMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
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

    private int getControllerCbIndexForCb(ISessionControllerCallback cb) {
        IBinder binder = cb.asBinder();
        for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
            if (binder.equals(mControllerCallbacks.get(i).asBinder())) {
                return i;
            }
        }
        return -1;
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
            mService.destroySession(MediaSessionRecord.this);
        }

        @Override
        public void sendEvent(String event, Bundle data) {
            mHandler.post(MessageHandler.MSG_SEND_EVENT, event, data);
        }

        @Override
        public ISessionController getController() {
            return mController;
        }

        @Override
        public void setActive(boolean active) {
            mIsActive = active;
            mService.updateSession(MediaSessionRecord.this);
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
            mHandler.post(MessageHandler.MSG_UPDATE_SESSION_STATE);
        }

        @Override
        public void setMediaRouter(IMediaRouter router) {
            mMediaRouter = router;
            mHandler.post(MessageHandler.MSG_UPDATE_SESSION_STATE);
        }

        @Override
        public void setMediaButtonReceiver(ComponentName mbr) {
            mMediaButtonReceiver = mbr;
        }

        @Override
        public void setMetadata(MediaMetadata metadata) {
            mMetadata = metadata;
            mHandler.post(MessageHandler.MSG_UPDATE_METADATA);
        }

        @Override
        public void setPlaybackState(PlaybackState state) {
            int oldState = mPlaybackState == null ? 0 : mPlaybackState.getState();
            int newState = state == null ? 0 : state.getState();
            if (MediaSession.isActiveState(oldState) && newState == PlaybackState.STATE_PAUSED) {
                mLastActiveTime = SystemClock.elapsedRealtime();
            }
            mPlaybackState = state;
            mService.onSessionPlaystateChange(MediaSessionRecord.this, oldState, newState);
            mHandler.post(MessageHandler.MSG_UPDATE_PLAYBACK_STATE);
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
        public void configureVolumeHandling(int type, int arg1, int arg2) throws RemoteException {
            boolean typeChanged = type != mVolumeType;
            switch(type) {
                case MediaSession.PLAYBACK_TYPE_LOCAL:
                    mVolumeType = type;
                    int audioStream = arg1;
                    if (isValidStream(audioStream)) {
                        mAudioStream = audioStream;
                    } else {
                        Log.e(TAG, "Cannot set stream to " + audioStream + ". Using music stream");
                        mAudioStream = AudioManager.STREAM_MUSIC;
                    }
                    break;
                case MediaSession.PLAYBACK_TYPE_REMOTE:
                    mVolumeType = type;
                    mVolumeControlType = arg1;
                    mMaxVolume = arg2;
                    break;
                default:
                    throw new IllegalArgumentException("Volume handling type " + type
                            + " not recognized.");
            }
            if (typeChanged) {
                mService.onSessionPlaybackTypeChanged(MediaSessionRecord.this);
            }
        }

        private boolean isValidStream(int stream) {
            return stream >= AudioManager.STREAM_VOICE_CALL
                    && stream <= AudioManager.STREAM_NOTIFICATION;
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

        public void sendCommand(String command, Bundle extras, ResultReceiver cb) {
            try {
                mCb.onCommand(command, extras, cb);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendCommand.", e);
            }
        }

        public void play() {
            try {
                mCb.onPlay();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in play.", e);
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

        public void adjustVolumeBy(int delta) {
            try {
                mCb.onAdjustVolumeBy(delta);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in adjustVolumeBy.", e);
            }
        }

        public void setVolumeTo(int value) {
            try {
                mCb.onSetVolumeTo(value);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in adjustVolumeBy.", e);
            }
        }
    }

    class ControllerStub extends ISessionController.Stub {
        @Override
        public void sendCommand(String command, Bundle extras, ResultReceiver cb)
                throws RemoteException {
            mSessionCb.sendCommand(command, extras, cb);
        }

        @Override
        public boolean sendMediaButton(KeyEvent mediaButtonIntent) {
            return mSessionCb.sendMediaButton(mediaButtonIntent, 0, null);
        }

        @Override
        public void registerCallbackListener(ISessionControllerCallback cb) {
            synchronized (mLock) {
                if (getControllerCbIndexForCb(cb) < 0) {
                    mControllerCallbacks.add(cb);
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
                int index = getControllerCbIndexForCb(cb);
                if (index != -1) {
                    mControllerCallbacks.remove(index);
                }
                if (DEBUG) {
                    Log.d(TAG, "unregistering callback " + cb + ". index=" + index);
                }
            }
        }

        @Override
        public MediaSessionInfo getSessionInfo() {
            return mSessionInfo;
        }

        @Override
        public long getFlags() {
            return mFlags;
        }

        @Override
        public ParcelableVolumeInfo getVolumeAttributes() {
            synchronized (mLock) {
                int type;
                int max;
                int current;
                if (mVolumeType == MediaSession.PLAYBACK_TYPE_REMOTE) {
                    type = mVolumeControlType;
                    max = mMaxVolume;
                    current = mOptimisticVolume != -1 ? mOptimisticVolume
                            : mCurrentVolume;
                } else {
                    type = VolumeProvider.VOLUME_CONTROL_ABSOLUTE;
                    max = mAudioManager.getStreamMaxVolume(mAudioStream);
                    current = mAudioManager.getStreamVolume(mAudioStream);
                }
                return new ParcelableVolumeInfo(mVolumeType, mAudioStream, type, max, current);
            }
        }

        @Override
        public void adjustVolumeBy(int delta, int flags) {
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.adjustVolumeBy(delta, flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setVolumeTo(int value, int flags) {
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.setVolumeTo(value, flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void play() throws RemoteException {
            mSessionCb.play();
        }

        @Override
        public void pause() throws RemoteException {
            mSessionCb.pause();
        }

        @Override
        public void stop() throws RemoteException {
            mSessionCb.stop();
        }

        @Override
        public void next() throws RemoteException {
            mSessionCb.next();
        }

        @Override
        public void previous() throws RemoteException {
            mSessionCb.previous();
        }

        @Override
        public void fastForward() throws RemoteException {
            mSessionCb.fastForward();
        }

        @Override
        public void rewind() throws RemoteException {
            mSessionCb.rewind();
        }

        @Override
        public void seekTo(long pos) throws RemoteException {
            mSessionCb.seekTo(pos);
        }

        @Override
        public void rate(Rating rating) throws RemoteException {
            mSessionCb.rate(rating);
        }


        @Override
        public MediaMetadata getMetadata() {
            return mMetadata;
        }

        @Override
        public PlaybackState getPlaybackState() {
            return getStateWithUpdatedPosition();
        }

        @Override
        public int getRatingType() {
            return mRatingType;
        }

        @Override
        public boolean isTransportControlEnabled() {
            return MediaSessionRecord.this.isTransportControlEnabled();
        }

        @Override
        public IMediaRouterDelegate createMediaRouterDelegate(
                IMediaRouterStateCallback callback) {
            // todo
            return null;
        }
    }

    private class MessageHandler extends Handler {
        private static final int MSG_UPDATE_METADATA = 1;
        private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
        private static final int MSG_SEND_EVENT = 3;
        private static final int MSG_UPDATE_SESSION_STATE = 4;
        private static final int MSG_UPDATE_VOLUME = 5;

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
                case MSG_SEND_EVENT:
                    pushEvent((String) msg.obj, msg.getData());
                    break;
                case MSG_UPDATE_SESSION_STATE:
                    // TODO add session state
                    break;
                case MSG_UPDATE_VOLUME:
                    pushVolumeUpdate();
                    break;
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
