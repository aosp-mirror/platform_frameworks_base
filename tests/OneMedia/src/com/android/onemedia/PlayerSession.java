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
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionToken;
import android.media.session.PlaybackState;
import android.media.session.TransportPerformer;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

import com.android.onemedia.playback.LocalRenderer;
import com.android.onemedia.playback.Renderer;
import com.android.onemedia.playback.RendererFactory;

public class PlayerSession {
    private static final String TAG = "PlayerSession";

    protected MediaSession mSession;
    protected Context mContext;
    protected RendererFactory mRendererFactory;
    protected LocalRenderer mRenderer;
    protected MediaSession.Callback mCallback;
    protected Renderer.Listener mRenderListener;
    protected TransportPerformer mPerformer;

    protected PlaybackState mPlaybackState;
    protected Listener mListener;

    public PlayerSession(Context context) {
        mContext = context;
        mRendererFactory = new RendererFactory();
        mRenderer = new LocalRenderer(context, null);
        mCallback = new ControllerCb();
        mRenderListener = new RenderListener();
        mPlaybackState = new PlaybackState();
        mPlaybackState.setActions(PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY);

        mRenderer.registerListener(mRenderListener);
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
        mPerformer = mSession.setTransportPerformerEnabled();
        mPerformer.addListener(new TransportListener());
        mPerformer.setPlaybackState(mPlaybackState);
        mSession.publish();
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
    }

    public void setNextContent(Bundle request) {
        mRenderer.setNextContent(request);
    }

    public interface Listener {
        public void onPlayStateChanged(PlaybackState state);
    }

    private class RenderListener implements Renderer.Listener {

        @Override
        public void onError(int type, int extra, Bundle extras, Throwable error) {
            Log.d(TAG, "Sending onError with type " + type + " and extra " + extra);
            mPlaybackState.setState(PlaybackState.PLAYSTATE_ERROR);
            if (error != null) {
                mPlaybackState.setErrorMessage(error.getLocalizedMessage());
            }
            mPerformer.setPlaybackState(mPlaybackState);
            if (mListener != null) {
                mListener.onPlayStateChanged(mPlaybackState);
            }
        }

        @Override
        public void onStateChanged(int newState) {
            if (newState != Renderer.STATE_ERROR) {
                mPlaybackState.setErrorMessage(null);
            }
            switch (newState) {
                case Renderer.STATE_ENDED:
                case Renderer.STATE_STOPPED:
                    mPlaybackState.setState(PlaybackState.PLAYSTATE_STOPPED);
                    break;
                case Renderer.STATE_INIT:
                case Renderer.STATE_PREPARING:
                    mPlaybackState.setState(PlaybackState.PLAYSTATE_BUFFERING);
                    break;
                case Renderer.STATE_ERROR:
                    mPlaybackState.setState(PlaybackState.PLAYSTATE_ERROR);
                    break;
                case Renderer.STATE_PAUSED:
                    mPlaybackState.setState(PlaybackState.PLAYSTATE_PAUSED);
                    break;
                case Renderer.STATE_PLAYING:
                    mPlaybackState.setState(PlaybackState.PLAYSTATE_PLAYING);
                    break;
                default:
                    mPlaybackState.setState(PlaybackState.PLAYSTATE_ERROR);
                    mPlaybackState.setErrorMessage("unkown state");
                    break;
            }
            mPlaybackState.setPosition(mRenderer.getSeekPosition());
            mPerformer.setPlaybackState(mPlaybackState);
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
            mPlaybackState.setState(PlaybackState.PLAYSTATE_PAUSED);
            mPlaybackState.setPosition(mRenderer.getSeekPosition());
            mPerformer.setPlaybackState(mPlaybackState);
            if (mListener != null) {
                mListener.onPlayStateChanged(mPlaybackState);
            }
        }

        @Override
        public void onNextStarted() {
        }

    }

    private class ControllerCb extends MediaSession.Callback {

        @Override
        public void onMediaButton(Intent mediaRequestIntent) {
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
    }

    private class TransportListener extends TransportPerformer.Listener {
        @Override
        public void onPlay() {
            mRenderer.onPlay();
        }

        @Override
        public void onPause() {
            mRenderer.onPause();
        }
    }

}
