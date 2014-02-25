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
import android.media.session.IMediaController;
import android.media.session.IMediaControllerCallback;
import android.media.session.IMediaSession;
import android.media.session.IMediaSessionCallback;
import android.media.session.MediaMetadata;
import android.media.session.PlaybackState;
import android.media.Rating;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * This is the system implementation of a Session. Apps will interact with the
 * MediaSession wrapper class instead.
 */
public class MediaSessionRecord implements IBinder.DeathRecipient {
    private static final String TAG = "MediaSessionImpl";

    private final MessageHandler mHandler;

    private final int mPid;
    private final String mPackageName;
    private final String mTag;
    private final ControllerStub mController;
    private final SessionStub mSession;
    private final SessionCb mSessionCb;
    private final MediaSessionService mService;

    private final Object mControllerLock = new Object();
    private final ArrayList<IMediaControllerCallback> mControllerCallbacks =
            new ArrayList<IMediaControllerCallback>();
    private final ArrayList<String> mInterfaces = new ArrayList<String>();

    private boolean mTransportPerformerEnabled = false;
    private Bundle mRoute;

    // TransportPerformer fields

    private MediaMetadata mMetadata;
    private PlaybackState mPlaybackState;
    private int mRatingType;
    // End TransportPerformer fields

    private boolean mIsPublished = false;

    public MediaSessionRecord(int pid, String packageName, IMediaSessionCallback cb, String tag,
            MediaSessionService service, Handler handler) {
        mPid = pid;
        mPackageName = packageName;
        mTag = tag;
        mController = new ControllerStub();
        mSession = new SessionStub();
        mSessionCb = new SessionCb(cb);
        mService = service;
        mHandler = new MessageHandler(handler.getLooper());
    }

    public IMediaSession getSessionBinder() {
        return mSession;
    }

    public IMediaController getControllerBinder() {
        return mController;
    }

    @Override
    public void binderDied() {
        mService.sessionDied(this);
    }

    public boolean isPublished() {
        return mIsPublished;
    }

    private void onDestroy() {
        mService.destroySession(this);
    }

    private void pushPlaybackStateUpdate() {
        synchronized (mControllerLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                IMediaControllerCallback cb = mControllerCallbacks.get(i);
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
        synchronized (mControllerLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                IMediaControllerCallback cb = mControllerCallbacks.get(i);
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
        synchronized (mControllerLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                IMediaControllerCallback cb = mControllerCallbacks.get(i);
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
        synchronized (mControllerLock) {
            for (int i = mControllerCallbacks.size() - 1; i >= 0; i--) {
                IMediaControllerCallback cb = mControllerCallbacks.get(i);
                try {
                    cb.onEvent(event, data);
                } catch (RemoteException e) {
                    Log.w(TAG, "Removing dead callback in pushRouteUpdate.", e);
                    mControllerCallbacks.remove(i);
                }
            }
        }
    }

    private final class SessionStub extends IMediaSession.Stub {

        @Override
        public void destroy() {
            onDestroy();
        }

        @Override
        public void sendEvent(String event, Bundle data) {
            mHandler.post(MessageHandler.MSG_SEND_EVENT, event, data);
        }

        @Override
        public IMediaController getMediaController() {
            return mController;
        }

        @Override
        public void setRouteState(Bundle routeState) {
        }

        @Override
        public void setRoute(Bundle mediaRouteDescriptor) {
            mRoute = mediaRouteDescriptor;
            mHandler.post(MessageHandler.MSG_UPDATE_ROUTE);
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
        public List<String> getSupportedInterfaces() {
            return mInterfaces;
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
    }

    class SessionCb {
        private final IMediaSessionCallback mCb;

        public SessionCb(IMediaSessionCallback cb) {
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

    class ControllerStub extends IMediaController.Stub {
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
        public void registerCallbackListener(IMediaControllerCallback cb) {
            synchronized (mControllerLock) {
                if (!mControllerCallbacks.contains(cb)) {
                    mControllerCallbacks.add(cb);
                }
            }
        }

        @Override
        public void unregisterCallbackListener(IMediaControllerCallback cb)
                throws RemoteException {
            synchronized (mControllerLock) {
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
        public boolean isTransportControlEnabled() throws RemoteException {
            return mTransportPerformerEnabled;
        }
    }

    private class MessageHandler extends Handler {
        private static final int MSG_UPDATE_METADATA = 1;
        private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
        private static final int MSG_UPDATE_ROUTE = 3;
        private static final int MSG_SEND_EVENT = 4;

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
