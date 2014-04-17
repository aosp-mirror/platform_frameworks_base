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

import android.content.Intent;
import android.media.routeprovider.RouteRequest;
import android.media.session.ISessionController;
import android.media.session.ISessionControllerCallback;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.SessionController;
import android.media.session.MediaMetadata;
import android.media.session.RouteCommand;
import android.media.session.RouteInfo;
import android.media.session.RouteOptions;
import android.media.session.RouteEvent;
import android.media.session.Session;
import android.media.session.SessionInfo;
import android.media.session.RouteInterface;
import android.media.session.PlaybackState;
import android.media.Rating;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This is the system implementation of a Session. Apps will interact with the
 * MediaSession wrapper class instead.
 */
public class MediaSessionRecord implements IBinder.DeathRecipient {
    private static final String TAG = "MediaSessionRecord";

    private final MessageHandler mHandler;

    private final int mPid;
    private final SessionInfo mSessionInfo;
    private final String mTag;
    private final ControllerStub mController;
    private final SessionStub mSession;
    private final SessionCb mSessionCb;
    private final MediaSessionService mService;

    private final Object mLock = new Object();
    private final ArrayList<ISessionControllerCallback> mControllerCallbacks =
            new ArrayList<ISessionControllerCallback>();
    private final ArrayList<RouteRequest> mRequests = new ArrayList<RouteRequest>();

    private boolean mTransportPerformerEnabled = false;
    private RouteInfo mRoute;
    private RouteOptions mRequest;
    private RouteConnectionRecord mConnection;
    // TODO define a RouteState class with relevant info
    private int mRouteState;

    // TransportPerformer fields

    private MediaMetadata mMetadata;
    private PlaybackState mPlaybackState;
    private int mRatingType;
    // End TransportPerformer fields

    private boolean mIsPublished = false;

    public MediaSessionRecord(int pid, String packageName, ISessionCallback cb, String tag,
            MediaSessionService service, Handler handler) {
        mPid = pid;
        mSessionInfo = new SessionInfo(UUID.randomUUID().toString(), packageName);
        mTag = tag;
        mController = new ControllerStub();
        mSession = new SessionStub();
        mSessionCb = new SessionCb(cb);
        mService = service;
        mHandler = new MessageHandler(handler.getLooper());
    }

    /**
     * Get the binder for the {@link Session}.
     *
     * @return The session binder apps talk to.
     */
    public ISession getSessionBinder() {
        return mSession;
    }

    /**
     * Get the binder for the {@link SessionController}.
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
    public SessionInfo getSessionInfo() {
        return mSessionInfo;
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
                if (mConnection != null) {
                    mConnection.disconnect();
                    mConnection = null;
                }
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
            if (mRoute == null || !TextUtils.equals(route.getId(), mRoute.getId())) {
                Log.w(TAG, "setRouteConnected: connected route is stale");
                // TODO figure out disconnection path
                return false;
            }
            if (request != mRequest) {
                Log.w(TAG, "setRouteConnected: connection request is stale");
                // TODO figure out disconnection path
                return false;
            }
            mConnection = connection;
            mConnection.setListener(mConnectionListener);
            mSessionCb.sendRouteConnected();
        }
        return true;
    }

    /**
     * Check if this session has been published by the app yet.
     *
     * @return True if it has been published, false otherwise.
     */
    public boolean isPublished() {
        return mIsPublished;
    }

    @Override
    public void binderDied() {
        mService.sessionDied(this);
    }

    private void onDestroy() {
        mService.destroySession(this);
    }

    private void pushPlaybackStateUpdate() {
        synchronized (mLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onPlaybackStateChanged(mPlaybackState);
                } catch (RemoteException e) {
                    Log.w(TAG, "Removing dead callback in pushPlaybackStateUpdate.", e);
                    mControllerCallbacks.remove(i);
                }
            }
        }
    }

    private void pushMetadataUpdate() {
        synchronized (mLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onMetadataChanged(mMetadata);
                } catch (RemoteException e) {
                    Log.w(TAG, "Removing dead callback in pushMetadataUpdate.", e);
                    mControllerCallbacks.remove(i);
                }
            }
        }
    }

    private void pushRouteUpdate() {
        synchronized (mLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onRouteChanged(mRoute);
                } catch (RemoteException e) {
                    Log.w(TAG, "Removing dead callback in pushRouteUpdate.", e);
                    mControllerCallbacks.remove(i);
                }
            }
        }
    }

    private void pushEvent(String event, Bundle data) {
        synchronized (mLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                ISessionControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onEvent(event, data);
                } catch (RemoteException e) {
                    Log.w(TAG, "Error with callback in pushEvent.", e);
                }
            }
        }
    }

    private void pushRouteCommand(RouteCommand command, ResultReceiver cb) {
        synchronized (mLock) {
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
            // TODO
        }
    };

    private final class SessionStub extends ISession.Stub {
        @Override
        public void destroy() {
            onDestroy();
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
        public void publish() {
            mIsPublished = true; // TODO push update to service
        }
        @Override
        public void setTransportPerformerEnabled() {
            mTransportPerformerEnabled = true;
        }

        @Override
        public void setMetadata(MediaMetadata metadata) {
            mMetadata = metadata;
            mHandler.post(MessageHandler.MSG_UPDATE_METADATA);
        }

        @Override
        public void setPlaybackState(PlaybackState state) {
            mPlaybackState = state;
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
        public void setRouteOptions(List<RouteOptions> options) throws RemoteException {
            mRequests.clear();
            for (int i = options.size() - 1; i >= 0; i--) {
                RouteRequest request = new RouteRequest(mSessionInfo, options.get(i),
                        false);
                mRequests.add(request);
            }
        }
    }

    class SessionCb {
        private final ISessionCallback mCb;

        public SessionCb(ISessionCallback cb) {
            mCb = cb;
        }

        public void sendMediaButton(KeyEvent keyEvent) {
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
            try {
                mCb.onMediaButton(mediaButtonIntent);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote failure in sendMediaRequest.", e);
            }
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
    }

    class ControllerStub extends ISessionController.Stub {
        @Override
        public void sendCommand(String command, Bundle extras, ResultReceiver cb)
                throws RemoteException {
            mSessionCb.sendCommand(command, extras, cb);
        }

        @Override
        public void sendMediaButton(KeyEvent mediaButtonIntent) {
            mSessionCb.sendMediaButton(mediaButtonIntent);
        }

        @Override
        public void registerCallbackListener(ISessionControllerCallback cb) {
            synchronized (mLock) {
                if (!mControllerCallbacks.contains(cb)) {
                    mControllerCallbacks.add(cb);
                }
            }
        }

        @Override
        public void unregisterCallbackListener(ISessionControllerCallback cb)
                throws RemoteException {
            synchronized (mLock) {
                mControllerCallbacks.remove(cb);
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
            return mPlaybackState;
        }

        @Override
        public int getRatingType() {
            return mRatingType;
        }

        @Override
        public boolean isTransportControlEnabled() {
            return mTransportPerformerEnabled;
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
