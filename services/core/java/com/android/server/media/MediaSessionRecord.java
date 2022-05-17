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

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.MediaMetadata;
import android.media.MediaRouter2Manager;
import android.media.Rating;
import android.media.RoutingSessionInfo;
import android.media.VolumeProvider;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionController;
import android.media.session.ISessionControllerCallback;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.MediaSession.QueueItem;
import android.media.session.ParcelableListBinder;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This is the system implementation of a Session. Apps will interact with the
 * MediaSession wrapper class instead.
 */
// TODO(jaewan): Do not call service method directly -- introduce listener instead.
public class MediaSessionRecord implements IBinder.DeathRecipient, MediaSessionRecordImpl {
    private static final String TAG = "MediaSessionRecord";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * The amount of time we'll send an assumed volume after the last volume
     * command before reverting to the last reported volume.
     */
    private static final int OPTIMISTIC_VOLUME_TIMEOUT = 1000;

    /**
     * These are states that usually indicate the user took an action and should
     * bump priority regardless of the old state.
     */
    private static final List<Integer> ALWAYS_PRIORITY_STATES = Arrays.asList(
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_NEXT);
    /**
     * These are states that usually indicate the user took an action if they
     * were entered from a non-priority state.
     */
    private static final List<Integer> TRANSITION_PRIORITY_STATES = Arrays.asList(
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_PLAYING);

    private static final AudioAttributes DEFAULT_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    private static int getVolumeStream(@Nullable AudioAttributes attr) {
        if (attr == null) {
            return DEFAULT_ATTRIBUTES.getVolumeControlStream();
        }
        final int stream = attr.getVolumeControlStream();
        if (stream == AudioManager.USE_DEFAULT_STREAM_TYPE) {
            return DEFAULT_ATTRIBUTES.getVolumeControlStream();
        }
        return stream;
    }

    private final MessageHandler mHandler;

    private final int mOwnerPid;
    private final int mOwnerUid;
    private final int mUserId;
    private final String mPackageName;
    private final String mTag;
    private final Bundle mSessionInfo;
    private final ControllerStub mController;
    private final MediaSession.Token mSessionToken;
    private final SessionStub mSession;
    private final SessionCb mSessionCb;
    private final MediaSessionService mService;
    private final Context mContext;
    private final boolean mVolumeAdjustmentForRemoteGroupSessions;

    private final Object mLock = new Object();
    private final CopyOnWriteArrayList<ISessionControllerCallbackHolder>
            mControllerCallbackHolders = new CopyOnWriteArrayList<>();

    private long mFlags;
    private MediaButtonReceiverHolder mMediaButtonReceiverHolder;
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
    private int mVolumeType = PlaybackInfo.PLAYBACK_TYPE_LOCAL;
    private int mVolumeControlType = VolumeProvider.VOLUME_CONTROL_ABSOLUTE;
    private int mMaxVolume = 0;
    private int mCurrentVolume = 0;
    private int mOptimisticVolume = -1;
    private String mVolumeControlId;
    // End volume handling fields

    private boolean mIsActive = false;
    private boolean mDestroyed = false;

    private long mDuration = -1;
    private String mMetadataDescription;

    private int mPolicies;

    public MediaSessionRecord(int ownerPid, int ownerUid, int userId, String ownerPackageName,
            ISessionCallback cb, String tag, Bundle sessionInfo,
            MediaSessionService service, Looper handlerLooper, int policies)
            throws RemoteException {
        mOwnerPid = ownerPid;
        mOwnerUid = ownerUid;
        mUserId = userId;
        mPackageName = ownerPackageName;
        mTag = tag;
        mSessionInfo = sessionInfo;
        mController = new ControllerStub();
        mSessionToken = new MediaSession.Token(ownerUid, mController);
        mSession = new SessionStub();
        mSessionCb = new SessionCb(cb);
        mService = service;
        mContext = mService.getContext();
        mHandler = new MessageHandler(handlerLooper);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mAudioAttrs = DEFAULT_ATTRIBUTES;
        mPolicies = policies;
        mVolumeAdjustmentForRemoteGroupSessions = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_volumeAdjustmentForRemoteGroupSessions);

        // May throw RemoteException if the session app is killed.
        mSessionCb.mCb.asBinder().linkToDeath(this, 0);
    }

    /**
     * Get the session binder for the {@link MediaSession}.
     *
     * @return The session binder apps talk to.
     */
    public ISession getSessionBinder() {
        return mSession;
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
    @Override
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Get the intent the app set for their media button receiver.
     *
     * @return The pending intent set by the app or null.
     */
    public MediaButtonReceiverHolder getMediaButtonReceiver() {
        return mMediaButtonReceiverHolder;
    }

    /**
     * Get the UID this session was created for.
     *
     * @return The UID for this session.
     */
    @Override
    public int getUid() {
        return mOwnerUid;
    }

    /**
     * Get the user id this session was created for.
     *
     * @return The user id for this session.
     */
    @Override
    public int getUserId() {
        return mUserId;
    }

    /**
     * Check if this session has system priorty and should receive media buttons
     * before any other sessions.
     *
     * @return True if this is a system priority session, false otherwise
     */
    @Override
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
     * @param asSystemService {@code true} if the event sent to the session as if it was come from
     *          the system service instead of the app process. This helps sessions to distinguish
     *          between the key injection by the app and key events from the hardware devices.
     *          Should be used only when the volume key events aren't handled by foreground
     *          activity. {@code false} otherwise to tell session about the real caller.
     * @param direction The direction to adjust volume in.
     * @param flags Any of the flags from {@link AudioManager}.
     * @param useSuggested True to use adjustSuggestedStreamVolumeForUid instead of
     *          adjustStreamVolumeForUid
     */
    public void adjustVolume(String packageName, String opPackageName, int pid, int uid,
            boolean asSystemService, int direction, int flags, boolean useSuggested) {
        int previousFlagPlaySound = flags & AudioManager.FLAG_PLAY_SOUND;
        if (checkPlaybackActiveState(true) || isSystemPriority()) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }
        if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            // Adjust the volume with a handler not to be blocked by other system service.
            int stream = getVolumeStream(mAudioAttrs);
            postAdjustLocalVolume(stream, direction, flags, opPackageName, pid, uid,
                    asSystemService, useSuggested, previousFlagPlaySound);
        } else {
            if (mVolumeControlType == VolumeProvider.VOLUME_CONTROL_FIXED) {
                if (DEBUG) {
                    Log.d(TAG, "Session does not support volume adjustment");
                }
            } else if (direction == AudioManager.ADJUST_TOGGLE_MUTE
                    || direction == AudioManager.ADJUST_MUTE
                    || direction == AudioManager.ADJUST_UNMUTE) {
                Log.w(TAG, "Muting remote playback is not supported");
            } else {
                if (DEBUG) {
                    Log.w(TAG, "adjusting volume, pkg=" + packageName + ", asSystemService="
                            + asSystemService + ", dir=" + direction);
                }
                mSessionCb.adjustVolume(packageName, pid, uid, asSystemService, direction);

                int volumeBefore = (mOptimisticVolume < 0 ? mCurrentVolume : mOptimisticVolume);
                mOptimisticVolume = volumeBefore + direction;
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
            // Always notify, even if the volume hasn't changed. This is important to ensure that
            // System UI receives an event if a hardware volume key is pressed but the session that
            // handles it does not allow volume adjustment. Without such an event, System UI would
            // not show volume controls to the user.
            mService.notifyRemoteVolumeChanged(flags, this);
        }
    }

    private void setVolumeTo(String packageName, String opPackageName, int pid, int uid, int value,
            int flags) {
        if (mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            int stream = getVolumeStream(mAudioAttrs);
            final int volumeValue = value;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mAudioManager.setStreamVolumeForUid(stream, volumeValue, flags,
                                opPackageName, uid, pid,
                                mContext.getApplicationInfo().targetSdkVersion);
                    } catch (IllegalArgumentException | SecurityException e) {
                        Log.e(TAG, "Cannot set volume: stream=" + stream + ", value=" + volumeValue
                                + ", flags=" + flags, e);
                    }
                }
            });
        } else {
            if (mVolumeControlType != VolumeProvider.VOLUME_CONTROL_ABSOLUTE) {
                if (DEBUG) {
                    Log.d(TAG, "Session does not support setting volume");
                }
            } else {
                value = Math.max(0, Math.min(value, mMaxVolume));
                mSessionCb.setVolumeTo(packageName, pid, uid, value);

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
            // Always notify, even if the volume hasn't changed.
            mService.notifyRemoteVolumeChanged(flags, this);
        }
    }

    /**
     * Check if this session has been set to active by the app.
     * <p>
     * It's not used to prioritize sessions for dispatching media keys since API 26, but still used
     * to filter session list in MediaSessionManager#getActiveSessions().
     *
     * @return True if the session is active, false otherwise.
     */
    @Override
    public boolean isActive() {
        return mIsActive && !mDestroyed;
    }

    /**
     * Check if the session's playback active state matches with the expectation. This always return
     * {@code false} if the playback state is {@code null}, where we cannot know the actual playback
     * state associated with the session.
     *
     * @param expected True if playback is expected to be active. false otherwise.
     * @return True if the session's playback matches with the expectation. false otherwise.
     */
    @Override
    public boolean checkPlaybackActiveState(boolean expected) {
        if (mPlaybackState == null) {
            return false;
        }
        return mPlaybackState.isActive() == expected;
    }

    /**
     * Get whether the playback is local.
     *
     * @return {@code true} if the playback is local.
     */
    @Override
    public boolean isPlaybackTypeLocal() {
        return mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL;
    }

    @Override
    public void binderDied() {
        mService.onSessionDied(this);
    }

    /**
     * Finish cleaning up this session, including disconnecting if connected and
     * removing the death observer from the callback binder.
     */
    @Override
    public void close() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            mSessionCb.mCb.asBinder().unlinkToDeath(this, 0);
            mDestroyed = true;
            mPlaybackState = null;
            mHandler.post(MessageHandler.MSG_DESTROYED);
        }
    }

    @Override
    public boolean isClosed() {
        synchronized (mLock) {
            return mDestroyed;
        }
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
     * @return {@code true} if the attempt to send media button was successfully.
     *         {@code false} otherwise.
     */
    public boolean sendMediaButton(String packageName, int pid, int uid, boolean asSystemService,
            KeyEvent ke, int sequenceId, ResultReceiver cb) {
        return mSessionCb.sendMediaButton(packageName, pid, uid, asSystemService, ke, sequenceId,
                cb);
    }

    @Override
    public boolean canHandleVolumeKey() {
        if (isPlaybackTypeLocal() || mVolumeAdjustmentForRemoteGroupSessions) {
            return true;
        }
        MediaRouter2Manager mRouter2Manager = MediaRouter2Manager.getInstance(mContext);
        List<RoutingSessionInfo> sessions =
                mRouter2Manager.getRoutingSessions(mPackageName);
        boolean foundNonSystemSession = false;
        boolean isGroup = false;
        for (RoutingSessionInfo session : sessions) {
            if (!session.isSystemSession()) {
                foundNonSystemSession = true;
                int selectedRouteCount = session.getSelectedRoutes().size();
                if (selectedRouteCount > 1) {
                    isGroup = true;
                    break;
                }
            }
        }
        if (!foundNonSystemSession) {
            Log.d(TAG, "No routing session for " + mPackageName);
            return false;
        }
        return !isGroup;
    }

    @Override
    public int getSessionPolicies() {
        synchronized (mLock) {
            return mPolicies;
        }
    }

    @Override
    public void setSessionPolicies(int policies) {
        synchronized (mLock) {
            mPolicies = policies;
        }
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + mTag + " " + this);

        final String indent = prefix + "  ";
        pw.println(indent + "ownerPid=" + mOwnerPid + ", ownerUid=" + mOwnerUid
                + ", userId=" + mUserId);
        pw.println(indent + "package=" + mPackageName);
        pw.println(indent + "launchIntent=" + mLaunchIntent);
        pw.println(indent + "mediaButtonReceiver=" + mMediaButtonReceiverHolder);
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
        final int pid;
        if (asSystemService) {
            opPackageName = mContext.getOpPackageName();
            uid = Process.SYSTEM_UID;
            pid = Process.myPid();
        } else {
            opPackageName = callingOpPackageName;
            uid = callingUid;
            pid = callingPid;
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (useSuggested) {
                        if (AudioSystem.isStreamActive(stream, 0)) {
                            mAudioManager.adjustSuggestedStreamVolumeForUid(stream,
                                    direction, flags, opPackageName, uid, pid,
                                    mContext.getApplicationInfo().targetSdkVersion);
                        } else {
                            mAudioManager.adjustSuggestedStreamVolumeForUid(
                                    AudioManager.USE_DEFAULT_STREAM_TYPE, direction,
                                    flags | previousFlagPlaySound, opPackageName, uid, pid,
                                    mContext.getApplicationInfo().targetSdkVersion);
                        }
                    } else {
                        mAudioManager.adjustStreamVolumeForUid(stream, direction, flags,
                                opPackageName, uid, pid,
                                mContext.getApplicationInfo().targetSdkVersion);
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
            String msg, ISessionControllerCallbackHolder holder, Exception e) {
        Log.v(TAG, msg + ", this=" + this + ", callback package=" + holder.mPackageName
                + ", exception=" + e);
    }

    private void pushPlaybackStateUpdate() {
        PlaybackState playbackState;
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            playbackState = mPlaybackState;
        }
        Collection<ISessionControllerCallbackHolder> deadCallbackHolders = null;
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            try {
                holder.mCallback.onPlaybackStateChanged(playbackState);
            } catch (DeadObjectException e) {
                if (deadCallbackHolders == null) {
                    deadCallbackHolders = new ArrayList<>();
                }
                deadCallbackHolders.add(holder);
                logCallbackException("Removing dead callback in pushPlaybackStateUpdate", holder,
                        e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushPlaybackStateUpdate", holder, e);
            }
        }
        if (deadCallbackHolders != null) {
            mControllerCallbackHolders.removeAll(deadCallbackHolders);
        }
    }

    private void pushMetadataUpdate() {
        MediaMetadata metadata;
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            metadata = mMetadata;
        }
        Collection<ISessionControllerCallbackHolder> deadCallbackHolders = null;
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            try {
                holder.mCallback.onMetadataChanged(metadata);
            } catch (DeadObjectException e) {
                if (deadCallbackHolders == null) {
                    deadCallbackHolders = new ArrayList<>();
                }
                deadCallbackHolders.add(holder);
                logCallbackException("Removing dead callback in pushMetadataUpdate", holder, e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushMetadataUpdate", holder, e);
            }
        }
        if (deadCallbackHolders != null) {
            mControllerCallbackHolders.removeAll(deadCallbackHolders);
        }
    }

    private void pushQueueUpdate() {
        ArrayList<QueueItem> toSend;
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            toSend = mQueue == null ? null : new ArrayList<>(mQueue);
        }
        Collection<ISessionControllerCallbackHolder> deadCallbackHolders = null;
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            ParceledListSlice<QueueItem> parcelableQueue = null;
            if (toSend != null) {
                parcelableQueue = new ParceledListSlice<>(toSend);
                // Limit the size of initial Parcel to prevent binder buffer overflow
                // as onQueueChanged is an async binder call.
                parcelableQueue.setInlineCountLimit(1);
            }

            try {
                holder.mCallback.onQueueChanged(parcelableQueue);
            } catch (DeadObjectException e) {
                if (deadCallbackHolders == null) {
                    deadCallbackHolders = new ArrayList<>();
                }
                deadCallbackHolders.add(holder);
                logCallbackException("Removing dead callback in pushQueueUpdate", holder, e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushQueueUpdate", holder, e);
            }
        }
        if (deadCallbackHolders != null) {
            mControllerCallbackHolders.removeAll(deadCallbackHolders);
        }
    }

    private void pushQueueTitleUpdate() {
        CharSequence queueTitle;
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            queueTitle = mQueueTitle;
        }
        Collection<ISessionControllerCallbackHolder> deadCallbackHolders = null;
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            try {
                holder.mCallback.onQueueTitleChanged(queueTitle);
            } catch (DeadObjectException e) {
                if (deadCallbackHolders == null) {
                    deadCallbackHolders = new ArrayList<>();
                }
                deadCallbackHolders.add(holder);
                logCallbackException("Removing dead callback in pushQueueTitleUpdate", holder, e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushQueueTitleUpdate", holder, e);
            }
        }
        if (deadCallbackHolders != null) {
            mControllerCallbackHolders.removeAll(deadCallbackHolders);
        }
    }

    private void pushExtrasUpdate() {
        Bundle extras;
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            extras = mExtras;
        }
        Collection<ISessionControllerCallbackHolder> deadCallbackHolders = null;
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            try {
                holder.mCallback.onExtrasChanged(extras);
            } catch (DeadObjectException e) {
                if (deadCallbackHolders == null) {
                    deadCallbackHolders = new ArrayList<>();
                }
                deadCallbackHolders.add(holder);
                logCallbackException("Removing dead callback in pushExtrasUpdate", holder, e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushExtrasUpdate", holder, e);
            }
        }
        if (deadCallbackHolders != null) {
            mControllerCallbackHolders.removeAll(deadCallbackHolders);
        }
    }

    private void pushVolumeUpdate() {
        PlaybackInfo info;
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            info = getVolumeAttributes();
        }
        Collection<ISessionControllerCallbackHolder> deadCallbackHolders = null;
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            try {
                holder.mCallback.onVolumeInfoChanged(info);
            } catch (DeadObjectException e) {
                if (deadCallbackHolders == null) {
                    deadCallbackHolders = new ArrayList<>();
                }
                deadCallbackHolders.add(holder);
                logCallbackException("Removing dead callback in pushVolumeUpdate", holder, e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushVolumeUpdate", holder, e);
            }
        }
        if (deadCallbackHolders != null) {
            mControllerCallbackHolders.removeAll(deadCallbackHolders);
        }
    }

    private void pushEvent(String event, Bundle data) {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
        }
        Collection<ISessionControllerCallbackHolder> deadCallbackHolders = null;
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            try {
                holder.mCallback.onEvent(event, data);
            } catch (DeadObjectException e) {
                if (deadCallbackHolders == null) {
                    deadCallbackHolders = new ArrayList<>();
                }
                deadCallbackHolders.add(holder);
                logCallbackException("Removing dead callback in pushEvent", holder, e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushEvent", holder, e);
            }
        }
        if (deadCallbackHolders != null) {
            mControllerCallbackHolders.removeAll(deadCallbackHolders);
        }
    }

    private void pushSessionDestroyed() {
        synchronized (mLock) {
            // This is the only method that may be (and can only be) called
            // after the session is destroyed.
            if (!mDestroyed) {
                return;
            }
        }
        for (ISessionControllerCallbackHolder holder : mControllerCallbackHolders) {
            try {
                holder.mCallback.asBinder().unlinkToDeath(holder.mDeathMonitor, 0);
                holder.mCallback.onSessionDestroyed();
            } catch (NoSuchElementException e) {
                logCallbackException("error unlinking to binder death", holder, e);
            } catch (DeadObjectException e) {
                logCallbackException("Removing dead callback in pushSessionDestroyed", holder, e);
            } catch (RemoteException e) {
                logCallbackException("unexpected exception in pushSessionDestroyed", holder, e);
            }
        }
        // After notifying clear all listeners
        mControllerCallbackHolders.clear();
    }

    private PlaybackState getStateWithUpdatedPosition() {
        PlaybackState state;
        long duration;
        synchronized (mLock) {
            if (mDestroyed) {
                return null;
            }
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

    private int getControllerHolderIndexForCb(ISessionControllerCallback cb) {
        IBinder binder = cb.asBinder();
        for (int i = mControllerCallbackHolders.size() - 1; i >= 0; i--) {
            if (binder.equals(mControllerCallbackHolders.get(i).mCallback.asBinder())) {
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
                        mAudioAttrs, mVolumeControlId);
            }
            volumeType = mVolumeType;
            attributes = mAudioAttrs;
        }
        int stream = getVolumeStream(attributes);
        int max = mAudioManager.getStreamMaxVolume(stream);
        int current = mAudioManager.getStreamVolume(stream);
        return new PlaybackInfo(volumeType, VolumeProvider.VOLUME_CONTROL_ABSOLUTE, max,
                current, attributes, null);
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
        public void destroySession() throws RemoteException {
            final long token = Binder.clearCallingIdentity();
            try {
                mService.onSessionDied(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void sendEvent(String event, Bundle data) throws RemoteException {
            mHandler.post(MessageHandler.MSG_SEND_EVENT, event,
                    data == null ? null : new Bundle(data));
        }

        @Override
        public ISessionController getController() throws RemoteException {
            return mController;
        }

        @Override
        public void setActive(boolean active) throws RemoteException {
            mIsActive = active;
            final long token = Binder.clearCallingIdentity();
            try {
                mService.onSessionActiveStateChanged(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            mHandler.post(MessageHandler.MSG_UPDATE_SESSION_STATE);
        }

        @Override
        public void setFlags(int flags) throws RemoteException {
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
        public void setMediaButtonReceiver(PendingIntent pi, String sessionPackageName)
                throws RemoteException {
            final long token = Binder.clearCallingIdentity();
            try {
                if ((mPolicies & MediaSessionPolicyProvider.SESSION_POLICY_IGNORE_BUTTON_RECEIVER)
                        != 0) {
                    return;
                }
                mMediaButtonReceiverHolder =
                        MediaButtonReceiverHolder.create(mContext, mUserId, pi, sessionPackageName);
                mService.onMediaButtonReceiverChanged(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setMediaButtonBroadcastReceiver(ComponentName receiver) throws RemoteException {
            final long token = Binder.clearCallingIdentity();
            try {
                if ((mPolicies & MediaSessionPolicyProvider.SESSION_POLICY_IGNORE_BUTTON_RECEIVER)
                        != 0) {
                    return;
                }
                mMediaButtonReceiverHolder = MediaButtonReceiverHolder.create(mUserId, receiver);
                mService.onMediaButtonReceiverChanged(MediaSessionRecord.this);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setLaunchPendingIntent(PendingIntent pi) throws RemoteException {
            mLaunchIntent = pi;
        }

        @Override
        public void setMetadata(MediaMetadata metadata, long duration, String metadataDescription)
                throws RemoteException {
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
        public void setPlaybackState(PlaybackState state) throws RemoteException {
            int oldState = mPlaybackState == null
                    ? PlaybackState.STATE_NONE : mPlaybackState.getState();
            int newState = state == null
                    ? PlaybackState.STATE_NONE : state.getState();
            boolean shouldUpdatePriority = ALWAYS_PRIORITY_STATES.contains(newState)
                    || (!TRANSITION_PRIORITY_STATES.contains(oldState)
                    && TRANSITION_PRIORITY_STATES.contains(newState));
            synchronized (mLock) {
                mPlaybackState = state;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                mService.onSessionPlaybackStateChanged(
                        MediaSessionRecord.this, shouldUpdatePriority);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            mHandler.post(MessageHandler.MSG_UPDATE_PLAYBACK_STATE);
        }

        @Override
        public void resetQueue() throws RemoteException {
            synchronized (mLock) {
                mQueue = null;
            }
            mHandler.post(MessageHandler.MSG_UPDATE_QUEUE);
        }

        @Override
        public IBinder getBinderForSetQueue() throws RemoteException {
            return new ParcelableListBinder<QueueItem>((list) -> {
                synchronized (mLock) {
                    mQueue = list;
                }
                mHandler.post(MessageHandler.MSG_UPDATE_QUEUE);
            });
        }

        @Override
        public void setQueueTitle(CharSequence title) throws RemoteException {
            mQueueTitle = title;
            mHandler.post(MessageHandler.MSG_UPDATE_QUEUE_TITLE);
        }

        @Override
        public void setExtras(Bundle extras) throws RemoteException {
            synchronized (mLock) {
                mExtras = extras == null ? null : new Bundle(extras);
            }
            mHandler.post(MessageHandler.MSG_UPDATE_EXTRAS);
        }

        @Override
        public void setRatingType(int type) throws RemoteException {
            mRatingType = type;
        }

        @Override
        public void setCurrentVolume(int volume) throws RemoteException {
            mCurrentVolume = volume;
            mHandler.post(MessageHandler.MSG_UPDATE_VOLUME);
        }

        @Override
        public void setPlaybackToLocal(AudioAttributes attributes) throws RemoteException {
            boolean typeChanged;
            synchronized (mLock) {
                typeChanged = mVolumeType == PlaybackInfo.PLAYBACK_TYPE_REMOTE;
                mVolumeType = PlaybackInfo.PLAYBACK_TYPE_LOCAL;
                mVolumeControlId = null;
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
        public void setPlaybackToRemote(int control, int max, String controlId)
                throws RemoteException {
            boolean typeChanged;
            synchronized (mLock) {
                typeChanged = mVolumeType == PlaybackInfo.PLAYBACK_TYPE_LOCAL;
                mVolumeType = PlaybackInfo.PLAYBACK_TYPE_REMOTE;
                mVolumeControlType = control;
                mMaxVolume = max;
                mVolumeControlId = controlId;
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
        private final ISessionCallback mCb;

        SessionCb(ISessionCallback cb) {
            mCb = cb;
        }

        public boolean sendMediaButton(String packageName, int pid, int uid,
                boolean asSystemService, KeyEvent keyEvent, int sequenceId, ResultReceiver cb) {
            try {
                if (KeyEvent.isMediaSessionKey(keyEvent.getKeyCode())) {
                    final String reason = "action=" + KeyEvent.actionToString(keyEvent.getAction())
                            + ";code=" + KeyEvent.keyCodeToString(keyEvent.getKeyCode());
                    mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                            pid, uid, packageName, reason);
                }
                if (asSystemService) {
                    mCb.onMediaButton(mContext.getPackageName(), Process.myPid(),
                            Process.SYSTEM_UID, createMediaButtonIntent(keyEvent), sequenceId, cb);
                } else {
                    mCb.onMediaButton(packageName, pid, uid,
                            createMediaButtonIntent(keyEvent), sequenceId, cb);
                }
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in sendMediaRequest.", e);
            }
            return false;
        }

        public boolean sendMediaButton(String packageName, int pid, int uid,
                boolean asSystemService, KeyEvent keyEvent) {
            try {
                if (KeyEvent.isMediaSessionKey(keyEvent.getKeyCode())) {
                    final String reason = "action=" + KeyEvent.actionToString(keyEvent.getAction())
                            + ";code=" + KeyEvent.keyCodeToString(keyEvent.getKeyCode());
                    mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                            pid, uid, packageName, reason);
                }
                if (asSystemService) {
                    mCb.onMediaButton(mContext.getPackageName(), Process.myPid(),
                            Process.SYSTEM_UID, createMediaButtonIntent(keyEvent), 0, null);
                } else {
                    mCb.onMediaButtonFromController(packageName, pid, uid,
                            createMediaButtonIntent(keyEvent));
                }
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in sendMediaRequest.", e);
            }
            return false;
        }

        public void sendCommand(String packageName, int pid, int uid, String command, Bundle args,
                ResultReceiver cb) {
            try {
                final String reason = TAG + ":" + command;
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onCommand(packageName, pid, uid, command, args, cb);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in sendCommand.", e);
            }
        }

        public void sendCustomAction(String packageName, int pid, int uid, String action,
                Bundle args) {
            try {
                final String reason = TAG + ":custom-" + action;
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onCustomAction(packageName, pid, uid, action, args);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in sendCustomAction.", e);
            }
        }

        public void prepare(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":prepare";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPrepare(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in prepare.", e);
            }
        }

        public void prepareFromMediaId(String packageName, int pid, int uid, String mediaId,
                Bundle extras) {
            try {
                final String reason = TAG + ":prepareFromMediaId";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPrepareFromMediaId(packageName, pid, uid, mediaId, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in prepareFromMediaId.", e);
            }
        }

        public void prepareFromSearch(String packageName, int pid, int uid, String query,
                Bundle extras) {
            try {
                final String reason = TAG + ":prepareFromSearch";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPrepareFromSearch(packageName, pid, uid, query, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in prepareFromSearch.", e);
            }
        }

        public void prepareFromUri(String packageName, int pid, int uid, Uri uri, Bundle extras) {
            try {
                final String reason = TAG + ":prepareFromUri";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPrepareFromUri(packageName, pid, uid, uri, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in prepareFromUri.", e);
            }
        }

        public void play(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":play";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPlay(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in play.", e);
            }
        }

        public void playFromMediaId(String packageName, int pid, int uid, String mediaId,
                Bundle extras) {
            try {
                final String reason = TAG + ":playFromMediaId";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPlayFromMediaId(packageName, pid, uid, mediaId, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in playFromMediaId.", e);
            }
        }

        public void playFromSearch(String packageName, int pid, int uid, String query,
                Bundle extras) {
            try {
                final String reason = TAG + ":playFromSearch";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPlayFromSearch(packageName, pid, uid, query, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in playFromSearch.", e);
            }
        }

        public void playFromUri(String packageName, int pid, int uid, Uri uri, Bundle extras) {
            try {
                final String reason = TAG + ":playFromUri";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPlayFromUri(packageName, pid, uid, uri, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in playFromUri.", e);
            }
        }

        public void skipToTrack(String packageName, int pid, int uid, long id) {
            try {
                final String reason = TAG + ":skipToTrack";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onSkipToTrack(packageName, pid, uid, id);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in skipToTrack", e);
            }
        }

        public void pause(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":pause";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPause(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in pause.", e);
            }
        }

        public void stop(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":stop";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onStop(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in stop.", e);
            }
        }

        public void next(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":next";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onNext(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in next.", e);
            }
        }

        public void previous(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":previous";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onPrevious(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in previous.", e);
            }
        }

        public void fastForward(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":fastForward";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onFastForward(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in fastForward.", e);
            }
        }

        public void rewind(String packageName, int pid, int uid) {
            try {
                final String reason = TAG + ":rewind";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onRewind(packageName, pid, uid);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in rewind.", e);
            }
        }

        public void seekTo(String packageName, int pid, int uid, long pos) {
            try {
                final String reason = TAG + ":seekTo";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onSeekTo(packageName, pid, uid, pos);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in seekTo.", e);
            }
        }

        public void rate(String packageName, int pid, int uid, Rating rating) {
            try {
                final String reason = TAG + ":rate";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onRate(packageName, pid, uid, rating);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in rate.", e);
            }
        }

        public void setPlaybackSpeed(String packageName, int pid, int uid, float speed) {
            try {
                final String reason = TAG + ":setPlaybackSpeed";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onSetPlaybackSpeed(packageName, pid, uid, speed);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in setPlaybackSpeed.", e);
            }
        }

        public void adjustVolume(String packageName, int pid, int uid, boolean asSystemService,
                int direction) {
            try {
                final String reason = TAG + ":adjustVolume";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                if (asSystemService) {
                    mCb.onAdjustVolume(mContext.getPackageName(), Process.myPid(),
                            Process.SYSTEM_UID, direction);
                } else {
                    mCb.onAdjustVolume(packageName, pid, uid, direction);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in adjustVolume.", e);
            }
        }

        public void setVolumeTo(String packageName, int pid, int uid, int value) {
            try {
                final String reason = TAG + ":setVolumeTo";
                mService.tempAllowlistTargetPkgIfPossible(getUid(), getPackageName(),
                        pid, uid, packageName, reason);
                mCb.onSetVolumeTo(packageName, pid, uid, value);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote failure in setVolumeTo.", e);
            }
        }

        private Intent createMediaButtonIntent(KeyEvent keyEvent) {
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
            return mediaButtonIntent;
        }
    }

    class ControllerStub extends ISessionController.Stub {
        @Override
        public void sendCommand(String packageName, String command, Bundle args,
                ResultReceiver cb) {
            mSessionCb.sendCommand(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    command, args, cb);
        }

        @Override
        public boolean sendMediaButton(String packageName, KeyEvent keyEvent) {
            return mSessionCb.sendMediaButton(packageName, Binder.getCallingPid(),
                    Binder.getCallingUid(), false, keyEvent);
        }

        @Override
        public void registerCallback(String packageName, ISessionControllerCallback cb) {
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
                    ISessionControllerCallbackHolder holder = new ISessionControllerCallbackHolder(
                        cb, packageName, Binder.getCallingUid(), () -> unregisterCallback(cb));
                    mControllerCallbackHolders.add(holder);
                    if (DEBUG) {
                        Log.d(TAG, "registering controller callback " + cb + " from controller"
                                + packageName);
                    }
                    // Avoid callback leaks
                    try {
                        // cb is not referenced outside of the MediaSessionRecord, so the death
                        // handler won't prevent MediaSessionRecord to be garbage collected.
                        cb.asBinder().linkToDeath(holder.mDeathMonitor, 0);
                    } catch (RemoteException e) {
                        unregisterCallback(cb);
                        Log.w(TAG, "registerCallback failed to linkToDeath", e);
                    }
                }
            }
        }

        @Override
        public void unregisterCallback(ISessionControllerCallback cb) {
            synchronized (mLock) {
                int index = getControllerHolderIndexForCb(cb);
                if (index != -1) {
                    try {
                        cb.asBinder().unlinkToDeath(
                          mControllerCallbackHolders.get(index).mDeathMonitor, 0);
                    } catch (NoSuchElementException e) {
                        Log.w(TAG, "error unlinking to binder death", e);
                    }
                    mControllerCallbackHolders.remove(index);
                }
                if (DEBUG) {
                    Log.d(TAG, "unregistering callback " + cb.asBinder());
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
        public Bundle getSessionInfo() {
            return mSessionInfo;
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
        public void adjustVolume(String packageName, String opPackageName, int direction,
                int flags) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.adjustVolume(packageName, opPackageName, pid, uid,
                        false, direction, flags, false /* useSuggested */);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setVolumeTo(String packageName, String opPackageName, int value, int flags) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                MediaSessionRecord.this.setVolumeTo(packageName, opPackageName, pid, uid, value,
                        flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void prepare(String packageName) {
            mSessionCb.prepare(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void prepareFromMediaId(String packageName, String mediaId, Bundle extras) {
            mSessionCb.prepareFromMediaId(packageName, Binder.getCallingPid(),
                    Binder.getCallingUid(), mediaId, extras);
        }

        @Override
        public void prepareFromSearch(String packageName, String query, Bundle extras) {
            mSessionCb.prepareFromSearch(packageName, Binder.getCallingPid(),
                    Binder.getCallingUid(), query, extras);
        }

        @Override
        public void prepareFromUri(String packageName, Uri uri, Bundle extras) {
            mSessionCb.prepareFromUri(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    uri, extras);
        }

        @Override
        public void play(String packageName) {
            mSessionCb.play(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void playFromMediaId(String packageName, String mediaId, Bundle extras) {
            mSessionCb.playFromMediaId(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    mediaId, extras);
        }

        @Override
        public void playFromSearch(String packageName, String query, Bundle extras) {
            mSessionCb.playFromSearch(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                     query, extras);
        }

        @Override
        public void playFromUri(String packageName, Uri uri, Bundle extras) {
            mSessionCb.playFromUri(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    uri, extras);
        }

        @Override
        public void skipToQueueItem(String packageName, long id) {
            mSessionCb.skipToTrack(packageName, Binder.getCallingPid(), Binder.getCallingUid(), id);
        }

        @Override
        public void pause(String packageName) {
            mSessionCb.pause(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void stop(String packageName) {
            mSessionCb.stop(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void next(String packageName) {
            mSessionCb.next(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void previous(String packageName) {
            mSessionCb.previous(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void fastForward(String packageName) {
            mSessionCb.fastForward(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void rewind(String packageName) {
            mSessionCb.rewind(packageName, Binder.getCallingPid(), Binder.getCallingUid());
        }

        @Override
        public void seekTo(String packageName, long pos) {
            mSessionCb.seekTo(packageName, Binder.getCallingPid(), Binder.getCallingUid(), pos);
        }

        @Override
        public void rate(String packageName, Rating rating) {
            mSessionCb.rate(packageName, Binder.getCallingPid(), Binder.getCallingUid(), rating);
        }

        @Override
        public void setPlaybackSpeed(String packageName,
                float speed) {
            mSessionCb.setPlaybackSpeed(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    speed);
        }

        @Override
        public void sendCustomAction(String packageName, String action, Bundle args) {
            mSessionCb.sendCustomAction(packageName, Binder.getCallingPid(), Binder.getCallingUid(),
                    action, args);
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
                return mQueue == null ? null : new ParceledListSlice<>(mQueue);
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

    private class ISessionControllerCallbackHolder {
        private final ISessionControllerCallback mCallback;
        private final String mPackageName;
        private final int mUid;
        private final IBinder.DeathRecipient mDeathMonitor;

        ISessionControllerCallbackHolder(ISessionControllerCallback callback, String packageName,
                int uid, IBinder.DeathRecipient deathMonitor) {
            mCallback = callback;
            mPackageName = packageName;
            mUid = uid;
            mDeathMonitor = deathMonitor;
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
