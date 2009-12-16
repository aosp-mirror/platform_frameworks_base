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

import android.app.Dialog;
import android.graphics.Rect;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

class PluginFullScreenHolder extends Dialog {

    private static final String LOGTAG = "FullScreenHolder";

    private final WebView mWebView;
    private final int mNpp;
    private int mX;
    private int mY;
    private int mWidth;
    private int mHeight;

    PluginFullScreenHolder(WebView webView, int npp) {
        super(webView.getContext(), android.R.style.Theme_NoTitleBar_Fullscreen);
        mWebView = webView;
        mNpp = npp;
    }

    Rect getBound() {
        return new Rect(mX, mY, mWidth, mHeight);
    }

    /*
     * x, y, width, height are in the caller's view coordinate system. (x, y) is
     * relative to the top left corner of the caller's view.
     */
    void updateBound(int x, int y, int width, int height) {
        mX = x;
        mY = y;
        mWidth = width;
        mHeight = height;
    }

    @Override
    public void onBackPressed() {
        mWebView.mPrivateHandler.obtainMessage(WebView.HIDE_FULLSCREEN)
                .sendToTarget();
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
        final float x = event.getX();
        final float y = event.getY();
        // TODO: find a way to know when the dialog size changed so that we can
        // cache the ratio
        final View decorView = getWindow().getDecorView();
        event.setLocation(mX + x * mWidth / decorView.getWidth(),
                mY + y * mHeight / decorView.getHeight());
        mWebView.onTouchEvent(event);
        // always return true as we are the handler
        return true;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        mWebView.onTrackballEvent(event);
        // always return true as we are the handler
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        mWebView.getWebViewCore().sendMessage(
                WebViewCore.EventHub.HIDE_FULLSCREEN, mNpp, 0);
    }

}
