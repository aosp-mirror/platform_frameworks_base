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
import android.webkit.ViewManager.ChildView;
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
    }

    @Override
    public void handleMessage(Message msg) {
        // This executes on the UI thread.
        switch (msg.what) {
            case INIT:
                ChildView child = (ChildView) msg.obj;
                // Create the video view and set a default controller.
                VideoView v = new VideoView(mContext);
                // This is needed because otherwise there will be a black square
                // stuck on the screen.
                v.setWillNotDraw(false);
                v.setMediaController(new MediaController(mContext));
                child.mView = v;
                break;
            case PLAY:
                HashMap<String, Object> map =
                        (HashMap<String, Object>) msg.obj;
                String url = (String) map.get("url");
                VideoView view = (VideoView) map.get("view");
                view.setVideoURI(Uri.parse(url));
                view.start();
                break;
        }
    }

    /**
     * Play a video stream.
     * @param url is the URL of the video stream.
     * @param webview is the WebViewCore that is requesting the playback.
     */
    public void play(String url, ChildView child) {
        // We need to know the webview that is requesting the playback.
        Message message = obtainMessage(PLAY);
        HashMap<String, Object> map = new HashMap();
        map.put("url", url);
        map.put("view", child.mView);
        message.obj = map;
        sendMessage(message);
    }

    public ChildView createView(WebViewCore core) {
        WebView w = core.getWebView();
        if (w == null) {
            return null;
        }
        ChildView child = w.mViewManager.createView();
        sendMessage(obtainMessage(INIT, child));
        return child;
    }

    public void attachView(ChildView child, int x, int y, int width,
            int height) {
        child.attachView(x, y, width, height);
    }

    public void removeView(ChildView child) {
        child.removeView();
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
