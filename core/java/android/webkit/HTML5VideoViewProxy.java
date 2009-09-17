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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.net.http.EventHandler;
import android.net.http.Headers;
import android.net.http.RequestHandle;
import android.net.http.RequestQueue;
import android.net.http.SslCertificate;
import android.net.http.SslError;
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
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    private static final int SET_POSTER        = 102;

    // Message Ids to be handled on the WebCore thread

    // The handler for WebCore thread messages;
    private Handler mWebCoreHandler;
    // The WebView instance that created this view.
    private WebView mWebView;
    // The ChildView instance used by the ViewManager.
    private ChildView mChildView;
    // The poster image to be shown when the video is not playing.
    private ImageView mPosterView;
    // The poster downloader.
    private PosterDownloader mPosterDownloader;
    // A helper class to control the playback. This executes on the UI thread!
    private static final class VideoPlayer {
        // The proxy that is currently playing (if any).
        private static HTML5VideoViewProxy mCurrentProxy;
        // The VideoView instance. This is a singleton for now, at least until
        // http://b/issue?id=1973663 is fixed.
        private static VideoView mVideoView;

        private static final WebChromeClient.CustomViewCallback mCallback =
            new WebChromeClient.CustomViewCallback() {
                public void onCustomViewHidden() {
                    // At this point the videoview is pretty much destroyed.
                    // It listens to SurfaceHolder.Callback.SurfaceDestroyed event
                    // which happens when the video view is detached from its parent
                    // view. This happens in the WebChromeClient before this method
                    // is invoked.
                    mCurrentProxy.playbackEnded();
                    mCurrentProxy = null;
                    mVideoView = null;
                }
            };

        public static void play(String url, HTML5VideoViewProxy proxy, WebChromeClient client) {
            if (mCurrentProxy != null) {
                // Some other video is already playing. Notify the caller that its playback ended.
                proxy.playbackEnded();
                return;
            }
            mCurrentProxy = proxy;
            mVideoView = new VideoView(proxy.getContext());
            mVideoView.setWillNotDraw(false);
            mVideoView.setMediaController(new MediaController(proxy.getContext()));
            mVideoView.setVideoURI(Uri.parse(url));
            mVideoView.start();
            client.onShowCustomView(mVideoView, mCallback);
        }
    }

    // Handler for the messages from WebCore thread to the UI thread.
    @Override
    public void handleMessage(Message msg) {
        // This executes on the UI thread.
        switch (msg.what) {
            case INIT: {
                mPosterView = new ImageView(mWebView.getContext());
                mChildView.mView = mPosterView;
                break;
            }
            case PLAY: {
                String url = (String) msg.obj;
                WebChromeClient client = mWebView.getWebChromeClient();
                if (client != null) {
                    VideoPlayer.play(url, this, client);
                }
                break;
            }
            case SET_POSTER: {
                Bitmap poster = (Bitmap) msg.obj;
                mPosterView.setImageBitmap(poster);
                break;
            }
        }
    }

    public void playbackEnded() {
        // TODO: notify WebKit
    }

    // Everything below this comment executes on the WebCore thread, except for
    // the EventHandler methods, which are called on the network thread.

    // A helper class that knows how to download posters
    private static final class PosterDownloader implements EventHandler {
        // The request queue. This is static as we have one queue for all posters.
        private static RequestQueue mRequestQueue;
        private static int mQueueRefCount = 0;
        // The poster URL
        private String mUrl;
        // The proxy we're doing this for.
        private final HTML5VideoViewProxy mProxy;
        // The poster bytes. We only touch this on the network thread.
        private ByteArrayOutputStream mPosterBytes;
        // The request handle. We only touch this on the WebCore thread.
        private RequestHandle mRequestHandle;
        // The response status code.
        private int mStatusCode;
        // The response headers.
        private Headers mHeaders;
        // The handler to handle messages on the WebCore thread.
        private Handler mHandler;

        public PosterDownloader(String url, HTML5VideoViewProxy proxy) {
            mUrl = url;
            mProxy = proxy;
            mHandler = new Handler();
        }
        // Start the download. Called on WebCore thread.
        public void start() {
            retainQueue();
            mRequestHandle = mRequestQueue.queueRequest(mUrl, "GET", null, this, null, 0);
        }
        // Cancel the download if active and release the queue. Called on WebCore thread.
        public void cancelAndReleaseQueue() {
            if (mRequestHandle != null) {
                mRequestHandle.cancel();
                mRequestHandle = null;
            }
            releaseQueue();
        }
        // EventHandler methods. Executed on the network thread.
        public void status(int major_version,
                int minor_version,
                int code,
                String reason_phrase) {
            mStatusCode = code;
        }

        public void headers(Headers headers) {
            mHeaders = headers;
        }

        public void data(byte[] data, int len) {
            if (mPosterBytes == null) {
                mPosterBytes = new ByteArrayOutputStream();
            }
            mPosterBytes.write(data, 0, len);
        }

        public void endData() {
            if (mStatusCode == 200) {
                if (mPosterBytes.size() > 0) {
                    Bitmap poster = BitmapFactory.decodeByteArray(
                            mPosterBytes.toByteArray(), 0, mPosterBytes.size());
                    if (poster != null) {
                        mProxy.doSetPoster(poster);
                    }
                }
                cleanup();
            } else if (mStatusCode >= 300 && mStatusCode < 400) {
                // We have a redirect.
                mUrl = mHeaders.getLocation();
                if (mUrl != null) {
                    mHandler.post(new Runnable() {
                       public void run() {
                           if (mRequestHandle != null) {
                               mRequestHandle.setupRedirect(mUrl, mStatusCode,
                                       new HashMap<String, String>());
                           }
                       }
                    });
                }
            }
        }

        public void certificate(SslCertificate certificate) {
            // Don't care.
        }

        public void error(int id, String description) {
            cleanup();
        }

        public boolean handleSslErrorRequest(SslError error) {
            // Don't care. If this happens, data() will never be called so
            // mPosterBytes will never be created, so no need to call cleanup.
            return false;
        }
        // Tears down the poster bytes stream. Called on network thread.
        private void cleanup() {
            if (mPosterBytes != null) {
                try {
                    mPosterBytes.close();
                } catch (IOException ignored) {
                    // Ignored.
                } finally {
                    mPosterBytes = null;
                }
            }
        }

        // Queue management methods. Called on WebCore thread.
        private void retainQueue() {
            if (mRequestQueue == null) {
                mRequestQueue = new RequestQueue(mProxy.getContext());
            }
            mQueueRefCount++;
        }

        private void releaseQueue() {
            if (mQueueRefCount == 0) {
                return;
            }
            if (--mQueueRefCount == 0) {
                mRequestQueue.shutdown();
                mRequestQueue = null;
            }
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
        // create the message handler for this thread
        createWebCoreHandler();
    }

    private void createWebCoreHandler() {
        mWebCoreHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    // TODO here we will process the messages from the VideoPlayer
                    // and will call native WebKit methods.
                }
            }
        };
    }

    private void doSetPoster(Bitmap poster) {
        if (poster == null) {
            return;
        }
        // Send the bitmap over to the UI thread.
        Message message = obtainMessage(SET_POSTER);
        message.obj = poster;
        sendMessage(message);
    }

    public Context getContext() {
        return mWebView.getContext();
    }

    // The public methods below are all called from WebKit only.
    /**
     * Play a video stream.
     * @param url is the URL of the video stream.
     * @param webview is the WebViewCore that is requesting the playback.
     */
    public void play(String url) {
        if (url == null) {
            return;
        }
         // We need to know the webview that is requesting the playback.
        Message message = obtainMessage(PLAY);
        message.obj = url;
        sendMessage(message);
    }

    /**
     * Create the child view that will cary the poster.
     */
    public void createView() {
        mChildView = mWebView.mViewManager.createView();
        sendMessage(obtainMessage(INIT));
    }

    /**
     * Attach the poster view.
     * @param x, y are the screen coordinates where the poster should be hung.
     * @param width, height denote the size of the poster.
     */
    public void attachView(int x, int y, int width, int height) {
        if (mChildView == null) {
            return;
        }
        mChildView.attachView(x, y, width, height);
    }

    /**
     * Remove the child view and, thus, the poster.
     */
    public void removeView() {
        if (mChildView == null) {
            return;
        }
        mChildView.removeView();
        // This is called by the C++ MediaPlayerPrivate dtor.
        // Cancel any active poster download.
        if (mPosterDownloader != null) {
            mPosterDownloader.cancelAndReleaseQueue();
        }
    }

    /**
     * Load the poster image.
     * @param url is the URL of the poster image.
     */
    public void loadPoster(String url) {
        if (url == null) {
            return;
        }
        // Cancel any active poster download.
        if (mPosterDownloader != null) {
            mPosterDownloader.cancelAndReleaseQueue();
        }
        // Load the poster asynchronously
        mPosterDownloader = new PosterDownloader(url, this);
        mPosterDownloader.start();
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
