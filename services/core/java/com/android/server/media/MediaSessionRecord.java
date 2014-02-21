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
import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;

/**
 * This is the system implementation of a Session. Apps will interact with the
 * MediaSession wrapper class instead.
 */
public class MediaSessionRecord implements IBinder.DeathRecipient {
    private static final String TAG = "MediaSessionImpl";

    private final int mPid;
    private final String mPackageName;
    private final String mTag;
    private final ControllerStub mController;
    private final SessionStub mSession;
    private final SessionCb mSessionCb;
    private final MediaSessionService mService;

    private final ArrayList<IMediaControllerCallback> mSessionCallbacks =
            new ArrayList<IMediaControllerCallback>();

    private int mPlaybackState = RemoteControlClient.PLAYSTATE_NONE;

    public MediaSessionRecord(int pid, String packageName, IMediaSessionCallback cb, String tag,
            MediaSessionService service) {
        mPid = pid;
        mPackageName = packageName;
        mTag = tag;
        mController = new ControllerStub();
        mSession = new SessionStub();
        mSessionCb = new SessionCb(cb);
        mService = service;
    }

    public IMediaSession getSessionBinder() {
        return mSession;
    }

    public IMediaController getControllerBinder() {
        return mController;
    }

    public void setPlaybackStateInternal(int state) {
        mPlaybackState = state;
        for (int i = mSessionCallbacks.size() - 1; i >= 0; i--) {
            IMediaControllerCallback cb = mSessionCallbacks.get(i);
            try {
                cb.onPlaybackUpdate(state);
            } catch (RemoteException e) {
                Log.d(TAG, "SessionCallback object dead in setPlaybackState.", e);
                mSessionCallbacks.remove(i);
            }
        }
    }

    @Override
    public void binderDied() {
        mService.sessionDied(this);
    }

    private void onDestroy() {
        mService.destroySession(this);
    }

    private final class SessionStub extends IMediaSession.Stub {

        @Override
        public void setPlaybackState(int state) throws RemoteException {
            setPlaybackStateInternal(state);
        }

        @Override
        public void destroy() throws RemoteException {
            onDestroy();
        }

        @Override
        public void sendEvent(Bundle data) throws RemoteException {
        }

        @Override
        public IMediaController getMediaSessionToken() throws RemoteException {
            return mController;
        }

        @Override
        public void setMetadata(Bundle metadata) throws RemoteException {
        }

        @Override
        public void setRouteState(Bundle routeState) throws RemoteException {
        }

        @Override
        public void setRoute(Bundle medaiRouteDescriptor) throws RemoteException {
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
                Log.d(TAG, "Controller object dead in sendMediaRequest.", e);
                onDestroy();
            }
        }

        public void sendCommand(String command, Bundle extras) {
            try {
                mCb.onCommand(command, extras);
            } catch (RemoteException e) {
                Log.d(TAG, "Controller object dead in sendCommand.", e);
                onDestroy();
            }
        }

        public void registerCallbackListener(IMediaSessionCallback cb) {

        }

    }

    class ControllerStub extends IMediaController.Stub {
        /*
         */
        @Override
        public void sendCommand(String command, Bundle extras) throws RemoteException {
            mSessionCb.sendCommand(command, extras);
        }

        @Override
        public void sendMediaButton(KeyEvent mediaButtonIntent) {
            mSessionCb.sendMediaButton(mediaButtonIntent);
        }

        /*
         */
        @Override
        public void registerCallbackListener(IMediaControllerCallback cb) throws RemoteException {
            if (!mSessionCallbacks.contains(cb)) {
                mSessionCallbacks.add(cb);
            }
        }

        /*
         */
        @Override
        public void unregisterCallbackListener(IMediaControllerCallback cb)
                throws RemoteException {
            mSessionCallbacks.remove(cb);
        }

        /*
         */
        @Override
        public int getPlaybackState() throws RemoteException {
            return mPlaybackState;
        }
    }

}
