/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ViewManager.ChildView;
import android.widget.AbsoluteLayout;
import android.widget.MediaController;
import android.widget.VideoView;

import java.util.HashMap;

/**
 * <p>Proxy for HTML5 video views.
 */
class HTML5VideoViewProxy extends Handler {
    // Logging tag.
    private static final String LOGTAG = "HTML5VideoViewProxy";

    // Message Ids for WebCore thread -> UI thread communication.
    private static final int INIT              = 100;
    private static final int PLAY              = 101;

    // The WebView instance that created this view.
    private WebView mWebView;
    // The ChildView instance used by the ViewManager.
    private ChildView mChildView;
    // The VideoView instance. Note that we could
    // also access this via mChildView.mView but it would
    // always require cast, so it is more convenient to store
    // it here as well.
    private HTML5VideoView mVideoView;

    // A VideoView subclass that responds to double-tap
    // events by going fullscreen.
    class HTML5VideoView extends VideoView {
        // Used to save the layout parameters if the view
        // is changed to fullscreen.
        private AbsoluteLayout.LayoutParams mEmbeddedLayoutParams;
        // Flag that denotes whether the view is fullscreen or not.
        private boolean mIsFullscreen;
        // Used to save the current playback position when
        // transitioning to/from fullscreen.
        private int mPlaybackPosition;
        // The callback object passed to the host application. This callback
        // is invoked when the host application dismisses our VideoView
        // (e.g. the user presses the back key).
        private WebChromeClient.CustomViewCallback mCallback =
                new WebChromeClient.CustomViewCallback() {
            public void onCustomViewHidden() {
                playEmbedded();
            }
        };

        // The OnPreparedListener, used to automatically resume
        // playback when transitioning to/from fullscreen.
        private MediaPlayer.OnPreparedListener mPreparedListener =
                new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                resumePlayback();
            }
        };

        HTML5VideoView(Context context) {
            super(context);
        }

        void savePlaybackPosition() {
            if (isPlaying()) {
                mPlaybackPosition = getCurrentPosition();
            }
        }

        void resumePlayback() {
            seekTo(mPlaybackPosition);
            start();
            setOnPreparedListener(null);
        }

        void playEmbedded() {
            // Attach to the WebView.
            mChildView.attachViewOnUIThread(mEmbeddedLayoutParams);
            // Make sure we're visible
            setVisibility(View.VISIBLE);
            // Set the onPrepared listener so we start
            // playing when the video view is reattached
            // and its surface is recreated.
            setOnPreparedListener(mPreparedListener);
            mIsFullscreen = false;
        }

        void playFullScreen() {
            WebChromeClient client = mWebView.getWebChromeClient();
            if (client == null) {
                return;
            }
            // Save the current layout params.
            mEmbeddedLayoutParams =
                    (AbsoluteLayout.LayoutParams) getLayoutParams();
            // Detach from the WebView.
            mChildView.removeViewOnUIThread();
            // Attach to the browser UI.
            client.onShowCustomView(this, mCallback);
            // Set the onPrepared listener so we start
            // playing when after the video view is reattached
            // and its surface is recreated.
            setOnPreparedListener(mPreparedListener);
            mIsFullscreen = true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            // TODO: implement properly (i.e. detect double tap)
            if (mIsFullscreen || !isPlaying()) {
                return super.onTouchEvent(ev);
            }
            playFullScreen();
            return true;
        }

        @Override
        public void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            savePlaybackPosition();
        }
    }

    /**
     * Private constructor.
     * @param context is the application context.
     */
    private HTML5VideoViewProxy(WebView webView) {
        // This handler is for the main (UI) thread.
        super(Looper.getMainLooper());
        // Save the WebView object.
        mWebView = webView;
    }

    @Override
    public void handleMessage(Message msg) {
        // This executes on the UI thread.
        switch (msg.what) {
            case INIT:
                // Create the video view and set a default controller.
                mVideoView = new HTML5VideoView(mWebView.getContext());
                // This is needed because otherwise there will be a black square
                // stuck on the screen.
                mVideoView.setWillNotDraw(false);
                mVideoView.setMediaController(new MediaController(mWebView.getContext()));
                mChildView.mView = mVideoView;
                break;
            case PLAY:
                if (mVideoView == null) {
                    return;
                }
                HashMap<String, Object> map =
                        (HashMap<String, Object>) msg.obj;
                String url = (String) map.get("url");
                mVideoView.setVideoURI(Uri.parse(url));
                mVideoView.start();
                break;
        }
    }

    /**
     * Play a video stream.
     * @param url is the URL of the video stream.
     * @param webview is the WebViewCore that is requesting the playback.
     */
    public void play(String url) {
         // We need to know the webview that is requesting the playback.
        Message message = obtainMessage(PLAY);
        HashMap<String, Object> map = new HashMap();
        map.put("url", url);
        message.obj = map;
        sendMessage(message);
    }

    public void createView() {
        mChildView = mWebView.mViewManager.createView();
        sendMessage(obtainMessage(INIT));
    }

    public void attachView(int x, int y, int width, int height) {
        if (mChildView == null) {
            return;
        }
        mChildView.attachView(x, y, width, height);
    }

    public void removeView() {
        if (mChildView == null) {
            return;
        }
        mChildView.removeView();
    }

    /**
     * The factory for HTML5VideoViewProxy instances.
     * @param webViewCore is the WebViewCore that is requesting the proxy.
     *
     * @return a new HTML5VideoViewProxy object.
     */
    public static HTML5VideoViewProxy getInstance(WebViewCore webViewCore) {
        return new HTML5VideoViewProxy(webViewCore.getWebView());
    }
}
