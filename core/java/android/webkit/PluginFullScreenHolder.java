/*
 * Copyright 2009, The Android Open Source Project
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package android.webkit;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

class PluginFullScreenHolder {

    private final WebView mWebView;
    private final int mNpp;
    private final int mOrientation;

    // The container for the plugin view
    private static CustomFrameLayout mLayout;

    private View mContentView;

    PluginFullScreenHolder(WebView webView, int orientation, int npp) {
        mWebView = webView;
        mNpp = npp;
        mOrientation = orientation;
    }

    public void setContentView(View contentView) {

        // Create a FrameLayout that will contain the plugin's view
        mLayout = new CustomFrameLayout(mWebView.getContext());
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER);

        mLayout.addView(contentView, layoutParams);
        mLayout.setVisibility(View.VISIBLE);

        // fixed size is only used either during pinch zoom or surface is too
        // big. Make sure it is not fixed size before setting it to the full
        // screen content view. The SurfaceView will be set to the correct mode
        // by the ViewManager when it is re-attached to the WebView.
        if (contentView instanceof SurfaceView) {
            final SurfaceView sView = (SurfaceView) contentView;
            if (sView.isFixedSize()) {
                sView.getHolder().setSizeFromLayout();
            }
        }

        mContentView = contentView;
    }

    public void show() {
        // Other plugins may attempt to draw so hide them while we're active.
        if (mWebView.getViewManager() != null)
            mWebView.getViewManager().hideAll();

        WebChromeClient client = mWebView.getWebChromeClient();
        client.onShowCustomView(mLayout, mOrientation, mCallback);
    }

    public void hide() {
        WebChromeClient client = mWebView.getWebChromeClient();
        client.onHideCustomView();
    }

    private class CustomFrameLayout extends FrameLayout {

        CustomFrameLayout(Context context) {
            super(context);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (event.isSystem()) {
                return super.onKeyDown(keyCode, event);
            }
            mWebView.onKeyDown(keyCode, event);
            // always return true as we are the handler
            return true;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (event.isSystem()) {
                return super.onKeyUp(keyCode, event);
            }
            mWebView.onKeyUp(keyCode, event);
            // always return true as we are the handler
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // always return true as we don't want the event to propagate any further
            return true;
        }

        @Override
        public boolean onTrackballEvent(MotionEvent event) {
            mWebView.onTrackballEvent(event);
            // always return true as we are the handler
            return true;
        }
    }
    
    private final WebChromeClient.CustomViewCallback mCallback =
        new WebChromeClient.CustomViewCallback() {
            public void onCustomViewHidden() {

                mWebView.mPrivateHandler.obtainMessage(WebView.HIDE_FULLSCREEN)
                    .sendToTarget();

                mWebView.getWebViewCore().sendMessage(
                        WebViewCore.EventHub.HIDE_FULLSCREEN, mNpp, 0);

                mLayout.removeView(mContentView);
                mLayout = null;

                // Re enable plugin views.
                mWebView.getViewManager().showAll();
            }
        };
}
