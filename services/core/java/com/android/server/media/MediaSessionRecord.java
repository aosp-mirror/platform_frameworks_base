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

import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.routeprovider.RouteRequest;
import android.media.session.ISessionController;
import android.media.session.ISessionControllerCallback;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.MediaController;
import android.media.session.RemoteVolumeProvider;
import android.media.session.RouteCommand;
import android.media.session.RouteInfo;
import android.media.session.RouteOptions;
import android.media.session.RouteEvent;
import android.media.session.MediaSession;
import android.media.session.MediaSessionInfo;
import android.media.session.RouteInterface;
import android.media.session.PlaybackState;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
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
     * These are the playback states that count as currently active.
     */
    private static final int[] ACTIVE_STATES = {
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_PLAYING };

    /**
     * The length of time a session will still be considered active after
     * pausing in ms.
     */
    private static final int ACTIVE_BUFFER = 30000;

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
    private final ArrayList<RouteRequest> mRequests = new ArrayList<RouteRequest>();

    private RouteInfo mRoute;
    private RouteOptions mRequest;
    private RouteConnectionRecord mConnection;
    // TODO define a RouteState class with relevant info
    private int mRouteState;
    private long mFlags;

    // TransportPerformer fields

    private MediaMetadata mMetadata;
    private PlaybackState mPlaybackState;
    private int mRatingType;
    private long mLastActiveTime;
    // End TransportPerformer fields

    // Volume handling fields
    private int mPlaybackType = MediaSession.VOLUME_TYPE_LOCAL;
    private int mAudioStream = AudioManager.STREAM_MUSIC;
    private int mVolumeControlType = RemoteVolumeProvider.VOLUME_CONTROL_ABSOLUTE;
    private int mMaxVolume = 0;
    private int mCurrentVolume = 0;
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
     * Get the set of route requests this session is interested in.
     *
     * @return The list of RouteRequests
     */
    public List<RouteRequest> getRouteRequests() {
        return mRequests;
    }

    /**
     * Get the route this session is currently on.
     *
     * @return The route the session is on.
     */
    public RouteInfo getRoute() {
        return mRoute;
    }

    /**
     * Get the info for this session.
     *
     * @return Info that identifies this session.
     */
    public MediaSessionInfo getSessionInfo() {
        return mSessionInfo;
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
     * Set the selected route. This does not connect to the route, just notifies
     * the app that a new route has been selected.
     *
     * @param route The route that was selected.
     */
    public void selectRoute(RouteInfo route) {
        synchronized (mLock) {
            if (route != mRoute) {
                disconnect(MediaSession.DISCONNECT_REASON_ROUTE_CHANGED);
            }
            mRoute = route;
        }
        mSessionCb.sendRouteChange(route);
    }

    /**
     * Update the state of the route this session is using and notify the
     * session.
     *
     * @param state The new state of the route.
     */
    public void setRouteState(int state) {
        mSessionCb.sendRouteStateChange(state);
    }

    /**
     * Send an event to this session from the route it is using.
     *
     * @param event The event to send.
     */
    public void sendRouteEvent(RouteEvent event) {
        mSessionCb.sendRouteEvent(event);
    }

    /**
     * Send a volume adjustment to the session owner.
     *
     * @param delta The amount to adjust the volume by.
     */
    public void adjustVolumeBy(int delta) {
        if (mVolumeControlType == RemoteVolumeProvider.VOLUME_CONTROL_FIXED) {
            // Nothing to do, the volume cannot be changed
            return;
        }
        mSessionCb.adjustVolumeBy(delta);
    }

    public void setVolumeTo(int value) {
        if (mVolumeControlType != RemoteVolumeProvider.VOLUME_CONTROL_ABSOLUTE) {
            // Nothing to do. The volume can't be set directly.
            return;
        }
        mSessionCb.setVolumeTo(value);
    }

    /**
     * Set the connection to use for the selected route and notify the app it is
     * now connected.
     *
     * @param route The route the connection is to.
     * @param request The request that was used to connect.
     * @param connection The connection to the route.
     * @return True if this connection is still valid, false if it is stale.
     */
    public boolean setRouteConnected(RouteInfo route, RouteOptions request,
            RouteConnectionRecord connection) {
        synchronized (mLock) {
            if (mDestroyed) {
                Log.i(TAG, "setRouteConnected: session has been destroyed");
                connection.disconnect();
                return false;
            }
            if (mRoute == null || !TextUtils.equals(route.getId(), mRoute.getId())) {
                Log.w(TAG, "setRouteConnected: connected route is stale");
                connection.disconnect();
                return false;
            }
            if (request != mRequest) {
                Log.w(TAG, "setRouteConnected: connection request is stale");
                connection.disconnect();
                return false;
            }
            mConnection = connection;
            mConnection.setListener(mConnectionListener);
            mSessionCb.sendRouteConnected();
        }
        return true;
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
        if (isActiveState(state)) {
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
        return mPlaybackType;
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
     * @return True if this session is currently connected to a route.
     */
    public boolean isConnected() {
        return mConnection != null;
    }

    public void disconnect(int reason) {
        synchronized (mLock) {
            if (!mDestroyed) {
                disconnectLocked(reason);
            }
        }
    }

    private void disconnectLocked(int reason) {
        if (mConnection != null) {
            mConnection.setListener(null);
            mConnection.disconnect();
            mConnection = null;
            pushDisconnected(reason);
        }
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
            if (isConnected()) {
                disconnectLocked(MediaSession.DISCONNECT_REASON_SESSION_DESTROYED);
            }
            mRoute = null;
            mRequest = null;
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
        pw.println(indent + "route requests {");
        int size = mRequests.size();
        for (int i = 0; i < size; i++) {
            pw.println(indent + "  " + mRequests.get(i).toString());
        }
        pw.println(indent + "}");
        pw.println(indent + "route=" + (mRoute == null ? null : mRoute.toString()));
        pw.println(indent + "connection=" + (mConnection == null ? null : mConnection.toString()));
        pw.println(indent + "params=" + (mRequest == null ? null : mRequest.toString()));
    }

    private boolean isActiveState(int state) {
        for (int i = 0; i < ACTIVE_STATES.length; i++) {
            if (ACTIVE_STATES[i] == state) {
                return true;
            }
        }
        return false;
    }

    private String getShortMetadataString() {
        int fields = mMetadata == null ? 0 : mMetadata.size();
        String title = mMetadata == null ? null : mMetadata
                .getString(MediaMetadata.METADATA_KEY_TITLE);
        return "size=" + fields + ", title=" + title;
    }

    private void pushDisconnected(int reason) {
        synchronized (mLock) {
            mSessionCb.sendRouteDisconnected(reason);
        }
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
                    Log.w(TAG, "Removed dead callback in pushPlaybackStateUpdate. size="
                            + mControllerCallbacks.size() + " cb=" + cb, e);
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
                    Log.w(TAG, "Removing dead callback in pushMetadataUpdate. " + cb, e);
                    mControllerCallbacks.remove(i);
                } catch (RemoteException e) {
                    Log.w(TAG, "unexpected exception in pushMetadataUpdate. " + cb, e);
                }
            }
        }
    }

    private void pushRouteUpdate() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onRouteChanged(mRoute);
                } catch (DeadObjectException e) {
                    Log.w(TAG, "Removing dead callback in pushRouteUpdate.", e);
                    mControllerCallbacks.remove(i);
                } catch (RemoteException e) {
                    Log.w(TAG, "unexpected exception in pushRouteUpdate.", e);
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

    private void pushRouteCommand(RouteCommand command, ResultReceiver cb) {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            if (mRoute == null || !TextUtils.equals(command.getRouteInfo(), mRoute.getId())) {
                if (cb != null) {
                    cb.send(RouteInterface.RESULT_ROUTE_IS_STALE, null);
                    return;
                }
            }
            if (mConnection != null) {
                mConnection.sendCommand(command, cb);
            } else if (cb != null) {
                cb.send(RouteInterface.RESULT_NOT_CONNECTED, null);
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
                if (updateTime > 0) {
                    long position = (long) (state.getPlaybackRate()
                            * (SystemClock.elapsedRealtime() - updateTime)) + state.getPosition();
                    if (duration >= 0 && position > duration) {
                        position = duration;
                    } else if (position < 0) {
                        position = 0;
                    }
                    result = new PlaybackState(state);
                    result.setState(state.getState(), position, state.getPlaybackRate());
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

    private final RouteConnectionRecord.Listener mConnectionListener
            = new RouteConnectionRecord.Listener() {
        @Override
        public void onEvent(RouteEvent event) {
            RouteEvent eventForSession = new RouteEvent(null, event.getIface(),
                    event.getEvent(), event.getExtras());
            mSessionCb.sendRouteEvent(eventForSession);
        }

        @Override
        public void disconnect() {
            MediaSessionRecord.this.disconnect(MediaSession.DISCONNECT_REASON_PROVIDER_DISCONNECTED);
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
        public void setMetadata(MediaMetadata metadata) {
            mMetadata = metadata;
            mHandler.post(MessageHandler.MSG_UPDATE_METADATA);
        }

        @Override
        public void setPlaybackState(PlaybackState state) {
            int oldState = mPlaybackState == null ? 0 : mPlaybackState.getState();
            int newState = state == null ? 0 : state.getState();
            if (isActiveState(oldState) && newState == PlaybackState.STATE_PAUSED) {
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
        public void sendRouteCommand(RouteCommand command, ResultReceiver cb) {
            mHandler.post(MessageHandler.MSG_SEND_COMMAND,
                    new Pair<RouteCommand, ResultReceiver>(command, cb));
        }

        @Override
        public boolean setRoute(RouteInfo route) throws RemoteException {
            // TODO decide if allowed to set route and if the route exists
            return false;
        }

        @Override
        public void connectToRoute(RouteInfo route, RouteOptions request)
                throws RemoteException {
            if (mRoute == null || !TextUtils.equals(route.getId(), mRoute.getId())) {
                throw new RemoteException("RouteInfo does not match current route");
            }
            mService.connectToRoute(MediaSessionRecord.this, route, request);
            mRequest = request;
        }

        @Override
        public void disconnectFromRoute(RouteInfo route) {
            if (route != null && mRoute != null
                    && TextUtils.equals(route.getId(), mRoute.getId())) {
                disconnect(MediaSession.DISCONNECT_REASON_SESSION_DISCONNECTED);
            }
        }

        @Override
        public void setRouteOptions(List<RouteOptions> options) throws RemoteException {
            mRequests.clear();
            for (int i = options.size() - 1; i >= 0; i--) {
                RouteRequest request = new RouteRequest(mSessionInfo, options.get(i),
                        false);
                mRequests.add(request);
            }
        }

        @Override
        public void setCurrentVolume(int volume) {
            mCurrentVolume = volume;
        }

        @Override
        public void configureVolumeHandling(int type, int arg1, int arg2) throws RemoteException {
            switch(type) {
                case MediaSession.VOLUME_TYPE_LOCAL:
                    mPlaybackType = type;
                    int audioStream = arg1;
                    if (isValidStream(audioStream)) {
                        mAudioStream = audioStream;
                    } else {
                        Log.e(TAG, "Cannot set stream to " + audioStream + ". Using music stream");
                        mAudioStream = AudioManager.STREAM_MUSIC;
                    }
                    break;
                case MediaSession.VOLUME_TYPE_REMOTE:
                    mPlaybackType = type;
                    mVolumeControlType = arg1;
                    mMaxVolume = arg2;
                    break;
                default:
                    throw new IllegalArgumentException("Volume handling type " + type
                            + " not recognized.");
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

        public void sendRouteChange(RouteInfo route) {
            try {
                mCb.onRequestRouteChange(route);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendRouteChange.", e);
            }
        }

        public void sendRouteStateChange(int state) {
            try {
                mCb.onRouteStateChange(state);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendRouteStateChange.", e);
            }
        }

        public void sendRouteEvent(RouteEvent event) {
            try {
                mCb.onRouteEvent(event);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendRouteEvent.", e);
            }
        }

        public void sendRouteConnected() {
            try {
                mCb.onRouteConnected(mRoute, mRequest);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendRouteStateChange.", e);
            }
        }

        public void sendRouteDisconnected(int reason) {
            try {
                mCb.onRouteDisconnected(mRoute, reason);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendRouteDisconnected");
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
        public void showRoutePicker() {
            mService.showRoutePickerForSession(MediaSessionRecord.this);
        }
    }

    private class MessageHandler extends Handler {
        private static final int MSG_UPDATE_METADATA = 1;
        private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
        private static final int MSG_UPDATE_ROUTE = 3;
        private static final int MSG_SEND_EVENT = 4;
        private static final int MSG_UPDATE_ROUTE_FILTERS = 5;
        private static final int MSG_SEND_COMMAND = 6;
        private static final int MSG_UPDATE_SESSION_STATE = 7;

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
                case MSG_UPDATE_ROUTE:
                    pushRouteUpdate();
                    break;
                case MSG_SEND_EVENT:
                    pushEvent((String) msg.obj, msg.getData());
                    break;
                case MSG_SEND_COMMAND:
                    Pair<RouteCommand, ResultReceiver> cmd =
                            (Pair<RouteCommand, ResultReceiver>) msg.obj;
                    pushRouteCommand(cmd.first, cmd.second);
                    break;
                case MSG_UPDATE_SESSION_STATE:
                    // TODO add session state
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
