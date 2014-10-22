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
package com.android.onemedia;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.onemedia.playback.IRequestCallback;
import com.android.onemedia.playback.RequestUtils;

import java.util.ArrayList;

public class PlayerService extends Service {
    private static final String TAG = "PlayerService";

    private PlayerBinder mBinder;
    private PlayerSession mSession;
    private NotificationHelper mNotifyHelper;
    private Intent mIntent;
    private boolean mStarted = false;

    private ArrayList<IPlayerCallback> mCbs = new ArrayList<IPlayerCallback>();

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        mIntent = onCreateServiceIntent();
        if (mSession == null) {
            mSession = onCreatePlayerController();
            mSession.createSession();
            mSession.setListener(mPlayerListener);
            mNotifyHelper = new NotificationHelper(this, mSession.mSession);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (mBinder == null) {
            mBinder = new PlayerBinder();
        }
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mSession.onDestroy();
        mSession = null;
    }

    public void onPlaybackStarted() {
        if (!mStarted) {
            Log.d(TAG, "Starting self");
            startService(onCreateServiceIntent());
            mNotifyHelper.onStart();
            mStarted = true;
        }
    }

    public void onPlaybackEnded() {
        if (mStarted) {
            Log.d(TAG, "Stopping self");
            mNotifyHelper.onStop();
            stopSelf();
            mStarted = false;
        }
    }

    protected Intent onCreateServiceIntent() {
        return new Intent(this, PlayerService.class).setPackage(getBasePackageName());
    }

    protected PlayerSession onCreatePlayerController() {
        return new PlayerSession(this);
    }

    protected ArrayList<String> getAllowedPackages() {
        return null;
    }

    private final PlayerSession.Listener mPlayerListener = new PlayerSession.Listener() {
        @Override
        public void onPlayStateChanged(PlaybackState state) {
            switch (state.getState()) {
                case PlaybackState.STATE_PLAYING:
                    onPlaybackStarted();
                    break;
                case PlaybackState.STATE_STOPPED:
                case PlaybackState.STATE_ERROR:
                    onPlaybackEnded();
                    break;
            }
        }
    };

    public class PlayerBinder extends IPlayerService.Stub {
        @Override
        public void sendRequest(String action, Bundle params, IRequestCallback cb) {
            if (RequestUtils.ACTION_SET_CONTENT.equals(action)) {
                mSession.setContent(params);
            } else if (RequestUtils.ACTION_SET_NEXT_CONTENT.equals(action)) {
                mSession.setNextContent(params);
            }
        }

        @Override
        public void registerCallback(final IPlayerCallback cb) throws RemoteException {
            if (!mCbs.contains(cb)) {
                mCbs.add(cb);
                cb.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        mCbs.remove(cb);
                    }
                }, 0);
            }
            try {
                cb.onSessionChanged(getSessionToken());
            } catch (RemoteException e) {
                mCbs.remove(cb);
                throw e;
            }
        }

        @Override
        public void unregisterCallback(IPlayerCallback cb) throws RemoteException {
            mCbs.remove(cb);
        }

        @Override
        public MediaSession.Token getSessionToken() throws RemoteException {
            if (mSession == null) {
                Log.e(TAG, "Error in PlayerService: mSession=null in getSessionToken()");
                return null;
            }
            return mSession.getSessionToken();
        }

        @Override
        public void setIcon(Bitmap icon) {
            mSession.setIcon(icon);
        }
    }

}
