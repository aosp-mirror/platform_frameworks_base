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
import android.media.routing.MediaRouteSelector;
import android.media.routing.MediaRouter;
import android.media.routing.MediaRouter.ConnectionRequest;
import android.media.routing.MediaRouter.DestinationInfo;
import android.media.routing.MediaRouter.RouteInfo;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.support.media.protocols.MediaPlayerProtocol;
import android.support.media.protocols.MediaPlayerProtocol.MediaStatus;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.android.onemedia.playback.LocalRenderer;
import com.android.onemedia.playback.OneMRPRenderer;
import com.android.onemedia.playback.Renderer;
import com.android.onemedia.playback.RequestUtils;

import java.util.ArrayList;
import java.util.List;

public class PlayerSession {
    private static final String TAG = "PlayerSession";

    protected MediaSession mSession;
    protected MediaRouter mRouter;
    protected Context mContext;
    protected Renderer mRenderer;
    protected MediaSession.Callback mCallback;
    protected Renderer.Listener mRenderListener;

    protected PlaybackState mPlaybackState;
    protected Listener mListener;

    private String mContent;

    public PlayerSession(Context context) {
        mContext = context;
        mRenderer = new LocalRenderer(context, null);
        mCallback = new SessionCb();
        mRenderListener = new RenderListener();
        PlaybackState.Builder psBob = new PlaybackState.Builder();
        psBob.setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY);
        mPlaybackState = psBob.build();

        mRenderer.registerListener(mRenderListener);
    }

    public void createSession() {
        releaseSession();

        MediaSessionManager man = (MediaSessionManager) mContext
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        Log.d(TAG, "Creating session for package " + mContext.getBasePackageName());

        mRouter = new MediaRouter(mContext);
        mRouter.addSelector(new MediaRouteSelector.Builder()
                .addRequiredProtocol(MediaPlayerProtocol.class)
                .build());
        mRouter.addSelector(new MediaRouteSelector.Builder()
                .setRequiredFeatures(MediaRouter.ROUTE_FEATURE_LIVE_AUDIO)
                .setOptionalFeatures(MediaRouter.ROUTE_FEATURE_LIVE_VIDEO)
                .build());
        mRouter.setRoutingCallback(new RoutingCallback(), null);

        mSession = new MediaSession(mContext, "OneMedia");
        mSession.setCallback(mCallback);
        mSession.setPlaybackState(mPlaybackState);
        mSession.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                | MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        mSession.setMediaRouter(mRouter);
        mSession.setActive(true);
    }

    public void onDestroy() {
        releaseSession();
        if (mRenderer != null) {
            mRenderer.unregisterListener(mRenderListener);
            mRenderer.onDestroy();
        }
    }

    private void releaseSession() {
        if (mSession != null) {
            mSession.release();
            mSession = null;
        }
        if (mRouter != null) {
            mRouter.release();
            mRouter = null;
        }
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public MediaSession.Token getSessionToken() {
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
        PlaybackState.Builder bob = new PlaybackState.Builder(mPlaybackState);
        bob.setState(newState, position, rate, SystemClock.elapsedRealtime());
        bob.setErrorMessage(null);
        mPlaybackState = bob.build();
        mSession.setPlaybackState(mPlaybackState);
    }

    public interface Listener {
        public void onPlayStateChanged(PlaybackState state);
    }

    private class RenderListener implements Renderer.Listener {

        @Override
        public void onError(int type, int extra, Bundle extras, Throwable error) {
            Log.d(TAG, "Sending onError with type " + type + " and extra " + extra);
            PlaybackState.Builder bob = new PlaybackState.Builder(mPlaybackState);
            bob.setState(PlaybackState.STATE_ERROR, -1, 0, 0);
            if (error != null) {
                bob.setErrorMessage(error.getLocalizedMessage());
            }
            mPlaybackState = bob.build();
            mSession.setPlaybackState(mPlaybackState);
            if (mListener != null) {
                mListener.onPlayStateChanged(mPlaybackState);
            }
        }

        @Override
        public void onStateChanged(int newState) {
            long position = -1;
            if (mRenderer != null) {
                position = mRenderer.getSeekPosition();
            }
            int pbState;
            float rate = 0;
            String errorMsg = null;
            switch (newState) {
                case Renderer.STATE_ENDED:
                case Renderer.STATE_STOPPED:
                    pbState = PlaybackState.STATE_STOPPED;
                    break;
                case Renderer.STATE_INIT:
                case Renderer.STATE_PREPARING:
                    pbState = PlaybackState.STATE_BUFFERING;
                    break;
                case Renderer.STATE_ERROR:
                    pbState = PlaybackState.STATE_ERROR;
                    break;
                case Renderer.STATE_PAUSED:
                    pbState = PlaybackState.STATE_PAUSED;
                    break;
                case Renderer.STATE_PLAYING:
                    pbState = PlaybackState.STATE_PLAYING;
                    rate = 1;
                    break;
                default:
                    pbState = PlaybackState.STATE_ERROR;
                    errorMsg = "unknown state";
                    break;
            }
            PlaybackState.Builder bob = new PlaybackState.Builder(mPlaybackState);
            bob.setState(pbState, position, rate, SystemClock.elapsedRealtime());
            bob.setErrorMessage(errorMsg);
            mPlaybackState = bob.build();
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
            PlaybackState.Builder bob = new PlaybackState.Builder(mPlaybackState);
            bob.setState(PlaybackState.STATE_PAUSED, position, 0, SystemClock.elapsedRealtime());
            bob.setErrorMessage(null);
            mPlaybackState = bob.build();
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
        public void onPlay() {
            mRenderer.onPlay();
        }

        @Override
        public void onPause() {
            mRenderer.onPause();
        }
    }

    private class RoutingCallback extends MediaRouter.RoutingCallback {
        @Override
        public void onConnectionStateChanged(int state) {
            if (state == MediaRouter.CONNECTION_STATE_CONNECTING) {
                if (mRenderer != null) {
                    mRenderer.onStop();
                }
                mRenderer = null;
                updateState(PlaybackState.STATE_CONNECTING);
                return;
            }

            MediaRouter.ConnectionInfo connection = mRouter.getConnection();
            if (connection != null) {
                MediaPlayerProtocol protocol =
                        connection.getProtocolObject(MediaPlayerProtocol.class);
                if (protocol != null) {
                    Log.d(TAG, "Connected to route using media player protocol");

                    protocol.setCallback(new PlayerCallback(), null);
                    mRenderer = new OneMRPRenderer(protocol);
                    updateState(PlaybackState.STATE_NONE);
                    return;
                }
            }

            // Use local route
            mRenderer = new LocalRenderer(mContext, null);
            mRenderer.registerListener(mRenderListener);
            updateState(PlaybackState.STATE_NONE);
        }
    }

    private class PlayerCallback extends MediaPlayerProtocol.Callback {
        @Override
        public void onStatusUpdated(MediaStatus status, Bundle extras) {
            if (status != null) {
                Log.d(TAG, "Received status update: " + status.toBundle());
                switch (status.getPlayerState()) {
                    case MediaStatus.PLAYER_STATE_BUFFERING:
                        updateState(PlaybackState.STATE_BUFFERING);
                        break;
                    case MediaStatus.PLAYER_STATE_IDLE:
                        updateState(PlaybackState.STATE_STOPPED);
                        break;
                    case MediaStatus.PLAYER_STATE_PAUSED:
                        updateState(PlaybackState.STATE_PAUSED);
                        break;
                    case MediaStatus.PLAYER_STATE_PLAYING:
                        updateState(PlaybackState.STATE_PLAYING);
                        break;
                    case MediaStatus.PLAYER_STATE_UNKNOWN:
                        updateState(PlaybackState.STATE_NONE);
                        break;
                }
            }
        }
    }
}
