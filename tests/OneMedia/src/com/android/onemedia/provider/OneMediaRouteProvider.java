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
package com.android.onemedia.provider;

import android.media.routing.MediaRouteSelector;
import android.media.routing.MediaRouteService;
import android.media.routing.MediaRouter.ConnectionInfo;
import android.media.routing.MediaRouter.ConnectionRequest;
import android.media.routing.MediaRouter.DestinationInfo;
import android.media.routing.MediaRouter.DiscoveryRequest;
import android.media.routing.MediaRouter.RouteInfo;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.support.media.protocols.MediaPlayerProtocol;
import android.support.media.protocols.MediaPlayerProtocol.MediaInfo;
import android.support.media.protocols.MediaPlayerProtocol.MediaStatus;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import com.android.onemedia.playback.LocalRenderer;
import com.android.onemedia.playback.Renderer;
import com.android.onemedia.playback.RequestUtils;

import java.util.ArrayList;

/**
 * Test of MediaRouteProvider. Show a dummy provider with a simple interface for
 * playing music.
 */
public class OneMediaRouteProvider extends MediaRouteService {
    private static final String TAG = "OneMRP";
    private static final boolean DEBUG = true;

    private static final String TEST_DESTINATION_ID = "testDestination";
    private static final String TEST_ROUTE_ID = "testRoute";

    private Renderer mRenderer;
    private RenderListener mRenderListener;
    private PlaybackState mPlaybackState;
    private Handler mHandler;

    private OneStub mStub;

    @Override
    public void onCreate() {
        mHandler = new Handler();
        mRenderer = new LocalRenderer(this, null);
        mRenderListener = new RenderListener();
        PlaybackState.Builder bob = new PlaybackState.Builder();
        bob.setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY);
        mPlaybackState = bob.build();

        mRenderer.registerListener(mRenderListener);
    }

    @Override
    public ClientSession onCreateClientSession(ClientInfo client) {
        if (client.getUid() != Process.myUid()) {
            // for testing purposes, only allow connections from this application
            // since this provider is not fully featured
            return null;
        }
        return new OneSession(client);
    }

    private final class OneSession extends ClientSession {
        private final ClientInfo mClient;

        public OneSession(ClientInfo client) {
            mClient = client;
        }

        @Override
        public boolean onStartDiscovery(DiscoveryRequest req, DiscoveryCallback callback) {
            for (MediaRouteSelector selector : req.getSelectors()) {
                if (isMatch(selector)) {
                    DestinationInfo destination = new DestinationInfo.Builder(
                            TEST_DESTINATION_ID, getServiceMetadata(), "OneMedia")
                            .setDescription("Test route from OneMedia app.")
                            .build();
                    ArrayList<RouteInfo> routes = new ArrayList<RouteInfo>();
                    routes.add(new RouteInfo.Builder(
                            TEST_ROUTE_ID, destination, selector).build());
                    callback.onDestinationFound(destination, routes);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onStopDiscovery() {
        }

        @Override
        public boolean onConnect(ConnectionRequest req, ConnectionCallback callback) {
            if (req.getRoute().getId().equals(TEST_ROUTE_ID)) {
                mStub = new OneStub();
                ConnectionInfo connection = new ConnectionInfo.Builder(req.getRoute())
                        .setProtocolStub(MediaPlayerProtocol.class, mStub)
                        .build();
                callback.onConnected(connection);
                return true;
            }
            return false;
        }

        @Override
        public void onDisconnect() {
            mStub = null;
        }

        private boolean isMatch(MediaRouteSelector selector) {
            if (!selector.containsProtocol(MediaPlayerProtocol.class)) {
                return false;
            }
            for (String protocol : selector.getRequiredProtocols()) {
                if (!protocol.equals(MediaPlayerProtocol.class.getName())) {
                    return false;
                }
            }
            return true;
        }
    }

    private final class OneStub extends MediaPlayerProtocol.Stub {
        MediaInfo mMediaInfo;

        public OneStub() {
            super(mHandler);
        }

        @Override
        public void onLoad(MediaInfo mediaInfo, boolean autoplay, long playPosition,
                Bundle extras) {
            if (DEBUG) {
                Log.d(TAG, "Attempting to play " + mediaInfo.getContentId());
            }
            // look up the route and send a play command to it
            mMediaInfo = mediaInfo;
            Bundle bundle = new Bundle();
            bundle.putString(RequestUtils.EXTRA_KEY_SOURCE, mediaInfo.getContentId());
            mRenderer.setContent(bundle);
        }

        @Override
        public void onPlay(Bundle extras) {
            mRenderer.onPlay();
        }

        @Override
        public void onPause(Bundle extras) {
            mRenderer.onPause();
        }
    }

    private class RenderListener implements Renderer.Listener {

        @Override
        public void onError(int type, int extra, Bundle extras, Throwable error) {
            Log.d(TAG, "Sending onError with type " + type + " and extra " + extra);
            sendStatusUpdate(PlaybackState.STATE_ERROR);
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

            sendStatusUpdate(mPlaybackState.getState());
        }

        @Override
        public void onBufferingUpdate(int percent) {
        }

        @Override
        public void onFocusLost() {
            Log.d(TAG, "Focus lost, pausing");
            // Don't update state here, we'll get a separate call to
            // onStateChanged when it pauses
            mRenderer.onPause();
        }

        @Override
        public void onNextStarted() {
        }

        private void sendStatusUpdate(int state) {
            if (mStub != null) {
                MediaStatus status = new MediaStatus(1, mStub.mMediaInfo);
                switch (state) {
                    case PlaybackState.STATE_BUFFERING:
                    case PlaybackState.STATE_FAST_FORWARDING:
                    case PlaybackState.STATE_REWINDING:
                    case PlaybackState.STATE_SKIPPING_TO_NEXT:
                    case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                        status.setPlayerState(MediaStatus.PLAYER_STATE_BUFFERING);
                        break;
                    case PlaybackState.STATE_CONNECTING:
                    case PlaybackState.STATE_STOPPED:
                        status.setPlayerState(MediaStatus.PLAYER_STATE_IDLE);
                        break;
                    case PlaybackState.STATE_PAUSED:
                        status.setPlayerState(MediaStatus.PLAYER_STATE_PAUSED);
                        break;
                    case PlaybackState.STATE_PLAYING:
                        status.setPlayerState(MediaStatus.PLAYER_STATE_PLAYING);
                        break;
                    case PlaybackState.STATE_NONE:
                    case PlaybackState.STATE_ERROR:
                    default:
                        status.setPlayerState(MediaStatus.PLAYER_STATE_UNKNOWN);
                        break;
                }
                mStub.sendStatusUpdatedEvent(status, null);
            }
        }
    }
}
