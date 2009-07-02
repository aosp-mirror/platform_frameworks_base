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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.MediaController;
import android.widget.VideoView;

import java.util.HashMap;

/**
 * <p>A View that displays Videos. Instances of this class
 * are created on the WebCore thread. However, their code
 * executes on the UI thread. Right now there is only one
 * such view for fullscreen video rendering.
 *
 */
class HTML5VideoViewProxy extends Handler {
    // Logging tag.
    private static final String LOGTAG = "HTML5VideoViewProxy";

    // Message Ids
    private static final int INIT              = 100;
    private static final int PLAY              = 101;

    // The singleton instance.
    private static HTML5VideoViewProxy sInstance;
    // The VideoView driven via this proxy.
    private VideoView mVideoView;
    // The context object used to initialize the VideoView and the
    // MediaController.
    private Context mContext;

    /**
     * Private constructor.
     * @param context is the application context.
     */
    private HTML5VideoViewProxy(Context context) {
        // This handler is for the main (UI) thread.
        super(Looper.getMainLooper());
        // Save the context object.
        mContext = context;
        // Send a message to the UI thread to create the VideoView.
        // This need to be done on the UI thread, or else the
        // event Handlers used by the VideoView and MediaController
        // will be attached to the wrong thread.
        Message message = obtainMessage(INIT);
        sendMessage(message);
    }

    @Override
    public void handleMessage(Message msg) {
        // This executes on the UI thread.
        switch (msg.what) {
            case INIT:
                // Create the video view and set a default controller.
                mVideoView = new VideoView(mContext);
                mVideoView.setMediaController(new MediaController(mContext));
                break;
            case PLAY:
                // Check if the fullscreen video view is currently playing.
                // If it is, ignore the message.
                if (!mVideoView.isPlaying()) {
                    HashMap<String, Object> map =
                        (HashMap<String, Object>) msg.obj;
                    String url = (String) map.get("url");
                    WebView webview = (WebView) map.get("webview");
                    WebChromeClient client = webview.getWebChromeClient();
                    if (client != null) {
                        mVideoView.setVideoURI(Uri.parse(url));
                        mVideoView.start();
                        client.onShowCustomView(mVideoView);
                    }
                }
                break;
        }
    }

    /**
     * Play a video stream.
     * @param url is the URL of the video stream.
     * @param webview is the WebViewCore that is requesting the playback.
     */
    public void play(String url, WebViewCore webviewCore) {
        // We need to know the webview that is requesting the playback.
        Message message = obtainMessage(PLAY);
        HashMap<String, Object> map = new HashMap();
        map.put("url", url);
        map.put("webview", webviewCore.getWebView());
        message.obj = map;
        sendMessage(message);
    }

    /**
     * The factory for HTML5VideoViewProxy instances. Right now,
     * it only produces a singleton.
     * @param webViewCore is the WebViewCore that is requesting the proxy.
     *
     * @return the HTML5VideoViewProxy singleton.
     */
    public static HTML5VideoViewProxy getInstance(WebViewCore webViewCore) {
        if (sInstance == null) {
            sInstance = new HTML5VideoViewProxy(webViewCore.getWebView().getContext());
        }
        return sInstance;
    }
}
