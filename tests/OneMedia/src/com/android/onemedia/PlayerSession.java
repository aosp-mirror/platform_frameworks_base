package com.android.onemedia;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionToken;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

import com.android.onemedia.playback.LocalRenderer;
import com.android.onemedia.playback.Renderer;
import com.android.onemedia.playback.RendererFactory;

public class PlayerSession {
    private static final String TAG = "PlayerController";

    protected MediaSession mSession;
    protected Context mContext;
    protected RendererFactory mRendererFactory;
    protected LocalRenderer mRenderer;
    protected ControllerCb mCallback;
    protected RenderListener mRenderListener;

    public PlayerSession(Context context) {
        mContext = context;
        mRendererFactory = new RendererFactory();
        mRenderer = new LocalRenderer(context, null);
        mCallback = new ControllerCb();
        mRenderListener = new RenderListener();

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

    public MediaSessionToken getSessionToken() {
        return mSession.getSessionToken();
    }

    public void setContent(Bundle request) {
        mRenderer.setContent(request);
    }

    public void setNextContent(Bundle request) {
        mRenderer.setNextContent(request);
    }

    protected class RenderListener implements Renderer.Listener {

        @Override
        public void onError(int type, int extra, Bundle extras, Throwable error) {
            mSession.setPlaybackState(Renderer.STATE_ERROR);
        }

        @Override
        public void onStateChanged(int newState) {
            mSession.setPlaybackState(newState);
        }

        @Override
        public void onBufferingUpdate(int percent) {
        }

        @Override
        public void onFocusLost() {
            mSession.setPlaybackState(Renderer.STATE_PAUSED);
        }

        @Override
        public void onNextStarted() {
        }

    }

    protected class ControllerCb extends MediaSession.Callback {

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

}
