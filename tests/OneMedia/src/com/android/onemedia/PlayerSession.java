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

import android.content.Context;
import android.content.Intent;
import android.media.session.Route;
import android.media.session.RouteInfo;
import android.media.session.RouteOptions;
import android.media.session.RoutePlaybackControls;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionToken;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

import com.android.onemedia.playback.LocalRenderer;
import com.android.onemedia.playback.OneMRPRenderer;
import com.android.onemedia.playback.Renderer;
import com.android.onemedia.playback.RequestUtils;

import java.util.ArrayList;

public class PlayerSession {
    private static final String TAG = "PlayerSession";

    protected MediaSession mSession;
    protected Context mContext;
    protected Renderer mRenderer;
    protected MediaSession.Callback mCallback;
    protected Renderer.Listener mRenderListener;

    protected PlaybackState mPlaybackState;
    protected Listener mListener;
    protected ArrayList<RouteOptions> mRouteOptions;
    protected Route mRoute;
    protected RoutePlaybackControls mRouteControls;
    protected RouteListener mRouteListener;

    private String mContent;

    public PlayerSession(Context context) {
        mContext = context;
        mRenderer = new LocalRenderer(context, null);
        mCallback = new SessionCb();
        mRenderListener = new RenderListener();
        mPlaybackState = new PlaybackState();
        mPlaybackState.setActions(PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY);

        mRenderer.registerListener(mRenderListener);

        // TODO need an easier way to build route options
        mRouteOptions = new ArrayList<RouteOptions>();
        RouteOptions.Builder bob = new RouteOptions.Builder();
        bob.addInterface(RoutePlaybackControls.NAME);
        mRouteOptions.add(bob.build());
        mRouteListener = new RouteListener();
    }

    public void createSession() {
        if (mSession != null) {
            mSession.release();
        }
        MediaSessionManager man = (MediaSessionManager) mContext
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        Log.d(TAG, "Creating session for package " + mContext.getBasePackageName());
        mSession = man.createSession("OneMedia");
        mSession.addCallback(mCallback);
        mSession.addTransportControlsCallback(new TransportCallback());
        mSession.setPlaybackState(mPlaybackState);
        mSession.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setRouteOptions(mRouteOptions);
        mSession.setActive(true);
    }

    public void onDestroy() {
        if (mSession != null) {
            mSession.release();
        }
        if (mRenderer != null) {
            mRenderer.unregisterListener(mRenderListener);
            mRenderer.onDestroy();
        }
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public MediaSessionToken getSessionToken() {
        return mSession.getSessionToken();
    }

    public void setContent(Bundle request) {
        mRenderer.setContent(request);
        mContent = request.getString(RequestUtils.EXTRA_KEY_SOURCE);
    }

    public void setNextContent(Bundle request) {
        mRenderer.setNextContent(request);
    }

    private void updateState(int newState) {
        float rate = newState == PlaybackState.STATE_PLAYING ? 1 : 0;
        long position = mRenderer == null ? -1 : mRenderer.getSeekPosition();
        mPlaybackState.setState(newState, position, rate);
        mSession.setPlaybackState(mPlaybackState);
    }

    public interface Listener {
        public void onPlayStateChanged(PlaybackState state);
    }

    private class RenderListener implements Renderer.Listener {

        @Override
        public void onError(int type, int extra, Bundle extras, Throwable error) {
            Log.d(TAG, "Sending onError with type " + type + " and extra " + extra);
            mPlaybackState.setState(PlaybackState.STATE_ERROR, -1, 0);
            if (error != null) {
                mPlaybackState.setErrorMessage(error.getLocalizedMessage());
            }
            mSession.setPlaybackState(mPlaybackState);
            if (mListener != null) {
                mListener.onPlayStateChanged(mPlaybackState);
            }
        }

        @Override
        public void onStateChanged(int newState) {
            if (newState != Renderer.STATE_ERROR) {
                mPlaybackState.setErrorMessage(null);
            }
            long position = -1;
            if (mRenderer != null) {
                position = mRenderer.getSeekPosition();
            }
            switch (newState) {
                case Renderer.STATE_ENDED:
                case Renderer.STATE_STOPPED:
                    mPlaybackState.setState(PlaybackState.STATE_STOPPED, position, 0);
                    break;
                case Renderer.STATE_INIT:
                case Renderer.STATE_PREPARING:
                    mPlaybackState.setState(PlaybackState.STATE_BUFFERING, position, 0);
                    break;
                case Renderer.STATE_ERROR:
                    mPlaybackState.setState(PlaybackState.STATE_ERROR, position, 0);
                    break;
                case Renderer.STATE_PAUSED:
                    mPlaybackState.setState(PlaybackState.STATE_PAUSED, position, 0);
                    break;
                case Renderer.STATE_PLAYING:
                    mPlaybackState.setState(PlaybackState.STATE_PLAYING, position, 1);
                    break;
                default:
                    mPlaybackState.setState(PlaybackState.STATE_ERROR, position, 0);
                    mPlaybackState.setErrorMessage("unkown state");
                    break;
            }
            mSession.setPlaybackState(mPlaybackState);
            if (mListener != null) {
                mListener.onPlayStateChanged(mPlaybackState);
            }
        }

        @Override
        public void onBufferingUpdate(int percent) {
        }

        @Override
        public void onFocusLost() {
            Log.d(TAG, "Focus lost, changing state to " + Renderer.STATE_PAUSED);
            long position = mRenderer == null ? -1 : mRenderer.getSeekPosition();
            mPlaybackState.setState(PlaybackState.STATE_PAUSED, position, 0);
            mSession.setPlaybackState(mPlaybackState);
            if (mListener != null) {
                mListener.onPlayStateChanged(mPlaybackState);
            }
        }

        @Override
        public void onNextStarted() {
        }

    }

    private class SessionCb extends MediaSession.Callback {
        @Override
        public void onMediaButtonEvent(Intent mediaRequestIntent) {
            if (Intent.ACTION_MEDIA_BUTTON.equals(mediaRequestIntent.getAction())) {
                KeyEvent event = (KeyEvent) mediaRequestIntent
                        .getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        Log.d(TAG, "play button received");
                        mRenderer.onPlay();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        Log.d(TAG, "pause button received");
                        mRenderer.onPause();
                        break;
                }
            }
        }

        @Override
        public void onRequestRouteChange(RouteInfo route) {
            if (mRenderer != null) {
                mRenderer.onStop();
            }
            if (route == null) {
                // Use local route
                mRoute = null;
                mRenderer = new LocalRenderer(mContext, null);
                mRenderer.registerListener(mRenderListener);
                updateState(PlaybackState.STATE_NONE);
            } else {
                // Use remote route
                mSession.connect(route, mRouteOptions.get(0));
                mRenderer = null;
                updateState(PlaybackState.STATE_CONNECTING);
            }
        }

        @Override
        public void onRouteConnected(Route route) {
            mRoute = route;
            mRouteControls = RoutePlaybackControls.from(route);
            mRouteControls.addListener(mRouteListener);
            Log.d(TAG, "Connected to route, registering listener");
            mRenderer = new OneMRPRenderer(mRouteControls);
            updateState(PlaybackState.STATE_NONE);
        }

        @Override
        public void onRouteDisconnected(Route route, int reason) {

        }
    }

    private class TransportCallback extends MediaSession.TransportControlsCallback {
        @Override
        public void onPlay() {
            mRenderer.onPlay();
        }

        @Override
        public void onPause() {
            mRenderer.onPause();
        }
    }

    private class RouteListener extends RoutePlaybackControls.Listener {
        @Override
        public void onPlaybackStateChange(int state) {
            Log.d(TAG, "Updating state to " + state);
            updateState(state);
        }
    }

}
