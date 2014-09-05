
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

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.util.Log;

import com.android.onemedia.playback.RequestUtils;

public class PlayerController {
    private static final String TAG = "PlayerController";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTED = 1;

    protected MediaController mController;
    protected IPlayerService mBinder;
    protected MediaController.TransportControls mTransportControls;

    private final Intent mServiceIntent;
    private Activity mContext;
    private Listener mListener;
    private SessionCallback mControllerCb;
    private MediaSessionManager mManager;
    private Handler mHandler = new Handler();

    private boolean mResumed;
    private Bitmap mArt;

    public PlayerController(Activity context, Intent serviceIntent) {
        mContext = context;
        if (serviceIntent == null) {
            mServiceIntent = new Intent(mContext, PlayerService.class);
        } else {
            mServiceIntent = serviceIntent;
        }
        mControllerCb = new SessionCallback();
        mManager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);

        mResumed = false;
    }

    public void setListener(Listener listener) {
        mListener = listener;
        Log.d(TAG, "Listener set to " + listener + " session is " + mController);
        if (mListener != null) {
            mHandler = new Handler();
            mListener.onConnectionStateChange(
                    mController == null ? STATE_DISCONNECTED : STATE_CONNECTED);
        }
    }

    public void onResume() {
        mResumed = true;
        Log.d(TAG, "onResume. Binding to service with intent " + mServiceIntent.toString());
        bindToService();
    }

    public void onPause() {
        mResumed = false;
        Log.d(TAG, "onPause, unbinding from service");
        unbindFromService();
    }

    public void setArt(Bitmap art) {
        mArt = art;
        if (mBinder != null) {
            try {
                mBinder.setIcon(art);
            } catch (RemoteException e) {
            }
        }
    }

    public void play() {
        if (mTransportControls != null) {
            mTransportControls.play();
        }
    }

    public void pause() {
        if (mTransportControls != null) {
            mTransportControls.pause();
        }
    }

    public void setContent(String source) {
        RequestUtils.ContentBuilder bob = new RequestUtils.ContentBuilder();
        bob.setSource(source);
        try {
            mBinder.sendRequest(RequestUtils.ACTION_SET_CONTENT, bob.build(), null);
        } catch (RemoteException e) {
            Log.d(TAG, "setContent failed, service may have died.", e);
        }
    }

    public void setNextContent(String source) {
        RequestUtils.ContentBuilder bob = new RequestUtils.ContentBuilder();
        bob.setSource(source);
        try {
            mBinder.sendRequest(RequestUtils.ACTION_SET_NEXT_CONTENT, bob.build(), null);
        } catch (RemoteException e) {
            Log.d(TAG, "setNexctContent failed, service may have died.", e);
        }
    }

    public void showRoutePicker() {
        // TODO
    }

    public MediaSession.Token getSessionToken() {
        if (mBinder != null) {
            try {
                return mBinder.getSessionToken();
            } catch (RemoteException e) {
            }
        }
        return null;
    }

    private void unbindFromService() {
        mContext.unbindService(mServiceConnection);
    }

    private void bindToService() {
        mContext.bindService(mServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mController != null) {
                mController.unregisterCallback(mControllerCb);
            }
            mBinder = null;
            mController = null;
            mTransportControls = null;
            mContext.setMediaController(null);
            Log.d(TAG, "Disconnected from PlayerService");

            if (mListener != null) {
                mListener.onConnectionStateChange(STATE_DISCONNECTED);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = IPlayerService.Stub.asInterface(service);
            Log.d(TAG, "service is " + service + " binder is " + mBinder);
            MediaSession.Token token;
            try {
                token = mBinder.getSessionToken();
            } catch (RemoteException e) {
                Log.e(TAG, "Error getting session", e);
                return;
            }
            mController = new MediaController(mContext, token);
            mContext.setMediaController(mController);
            mController.registerCallback(mControllerCb, mHandler);
            mTransportControls = mController.getTransportControls();
            if (mArt != null) {
                setArt(mArt);
            }
            Log.d(TAG, "Ready to use PlayerService");

            if (mListener != null) {
                mListener.onConnectionStateChange(STATE_CONNECTED);
                if (mTransportControls != null) {
                    mListener.onPlaybackStateChange(mController.getPlaybackState());
                }
            }
        }
    };

    private class SessionCallback extends MediaController.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) {
                return;
            }
            Log.d(TAG, "Received playback state change to state " + state.getState());
            if (mListener != null) {
                mListener.onPlaybackStateChange(state);
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (metadata == null) {
                return;
            }
            Log.d(TAG, "Received metadata change, " + metadata.getDescription());
            if (mListener != null) {
                mListener.onMetadataChange(metadata);
            }
        }
    }

    public interface Listener {
        public void onPlaybackStateChange(PlaybackState state);
        public void onMetadataChange(MediaMetadata metadata);
        public void onConnectionStateChange(int state);
    }

}
