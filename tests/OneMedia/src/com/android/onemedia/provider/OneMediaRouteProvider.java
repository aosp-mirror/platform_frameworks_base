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

import android.media.routeprovider.RouteConnection;
import android.media.routeprovider.RouteInterfaceHandler;
import android.media.routeprovider.RoutePlaybackControlsHandler;
import android.media.routeprovider.RouteProviderService;
import android.media.routeprovider.RouteRequest;
import android.media.session.RouteInfo;
import android.media.session.RoutePlaybackControls;
import android.media.session.RouteInterface;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import com.android.onemedia.playback.LocalRenderer;
import com.android.onemedia.playback.Renderer;
import com.android.onemedia.playback.RequestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test of MediaRouteProvider. Show a dummy provider with a simple interface for
 * playing music.
 */
public class OneMediaRouteProvider extends RouteProviderService {
    private static final String TAG = "OneMRP";
    private static final boolean DEBUG = true;

    private Renderer mRenderer;
    private RenderListener mRenderListener;
    private PlaybackState mPlaybackState;
    private RouteConnection mConnection;
    private RoutePlaybackControlsHandler mControls;
    private String mRouteId;
    private Handler mHandler;

    @Override
    public void onCreate() {
        mHandler = new Handler();
        mRouteId = UUID.randomUUID().toString();
        mRenderer = new LocalRenderer(this, null);
        mRenderListener = new RenderListener();
        mPlaybackState = new PlaybackState();
        mPlaybackState.setActions(PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY);

        mRenderer.registerListener(mRenderListener);

        if (DEBUG) {
            Log.d(TAG, "onCreate, routeId is " + mRouteId);
        }
    }

    @Override
    public List<RouteInfo> getMatchingRoutes(List<RouteRequest> requests) {
        RouteInfo.Builder bob = new RouteInfo.Builder();
        bob.setName("OneMedia").setId(mRouteId);
        // TODO add a helper library for generating route info with the correct
        // options
        Log.d(TAG, "Requests:");
        for (RouteRequest request : requests) {
            List<String> ifaces = request.getConnectionOptions().getInterfaceNames();
            Log.d(TAG, "  request ifaces:" + ifaces.toString());
            if (ifaces != null && ifaces.size() == 1
                    && RoutePlaybackControls.NAME.equals(ifaces.get(0))) {
                bob.addRouteOptions(request.getConnectionOptions());
            }
        }
        ArrayList<RouteInfo> result = new ArrayList<RouteInfo>();
        if (bob.getOptionsSize() > 0) {
            RouteInfo info = bob.build();
            result.add(info);
        }
        if (DEBUG) {
            Log.d(TAG, "getRoutes returning " + result.toString());
        }
        return result;
    }

    @Override
    public RouteConnection connect(RouteInfo route, RouteRequest request) {
        if (mConnection != null) {
            disconnect(mConnection);
        }
        RouteConnection connection = new RouteConnection(this, route);
        mControls = RoutePlaybackControlsHandler.addTo(connection);
        mControls.addListener(new PlayHandler(mRouteId), mHandler);
        if (DEBUG) {
            Log.d(TAG, "Connected to route");
        }
        return connection;
    }

    private class PlayHandler extends RoutePlaybackControlsHandler.Listener {
        private final String mRouteId;

        public PlayHandler(String routeId) {
            mRouteId = routeId;
        }

        @Override
        public void playNow(String content, ResultReceiver cb) {
            if (DEBUG) {
                Log.d(TAG, "Attempting to play " + content);
            }
            // look up the route and send a play command to it
            Bundle bundle = new Bundle();
            bundle.putString(RequestUtils.EXTRA_KEY_SOURCE, content);
            mRenderer.setContent(bundle);
            RouteInterfaceHandler.sendResult(cb, RouteInterface.RESULT_SUCCESS, null);
        }

        @Override
        public boolean resume() {
            mRenderer.onPlay();
            return true;
        }

        @Override
        public boolean pause() {
            mRenderer.onPause();
            return true;
        }
    }

    private class RenderListener implements Renderer.Listener {

        @Override
        public void onError(int type, int extra, Bundle extras, Throwable error) {
            Log.d(TAG, "Sending onError with type " + type + " and extra " + extra);
            if (mControls != null) {
                mControls.sendPlaybackChangeEvent(PlaybackState.STATE_ERROR);
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

            mControls.sendPlaybackChangeEvent(mPlaybackState.getState());
        }

        @Override
        public void onBufferingUpdate(int percent) {
        }

        @Override
        public void onFocusLost() {
            Log.d(TAG, "Focus lost, changing state to " + Renderer.STATE_PAUSED);
            mPlaybackState.setState(PlaybackState.STATE_PAUSED, mRenderer.getSeekPosition(), 0);
            mRenderer.onPause();
        }

        @Override
        public void onNextStarted() {
        }
    }
}
