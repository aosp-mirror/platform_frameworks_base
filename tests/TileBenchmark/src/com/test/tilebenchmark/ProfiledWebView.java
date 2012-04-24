/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.test.tilebenchmark;

import android.content.Context;
import android.os.CountDownTimer;
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.WebSettingsClassic;
import android.webkit.WebView;
import android.webkit.WebViewClassic;

import java.util.ArrayList;

import com.test.tilebenchmark.ProfileActivity.ProfileCallback;
import com.test.tilebenchmark.RunData.TileData;

public class ProfiledWebView extends WebView implements WebViewClassic.PageSwapDelegate {
    private static final String LOGTAG = "ProfiledWebView";

    private int mSpeed;

    private boolean mIsTesting = false;
    private boolean mIsScrolling = false;
    private ProfileCallback mCallback;
    private long mContentInvalMillis;
    private static final int LOAD_STALL_MILLIS = 2000; // nr of millis after load,
                                                       // before test is forced

    // ignore anim end events until this many millis after load
    private static final long ANIM_SAFETY_THRESHOLD = 200;
    private long mLoadTime;
    private long mAnimationTime;

    public ProfiledWebView(Context context) {
        super(context);
    }

    public ProfiledWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ProfiledWebView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public ProfiledWebView(Context context, AttributeSet attrs, int defStyle,
            boolean privateBrowsing) {
        super(context, attrs, defStyle, privateBrowsing);
    }

    private class JavaScriptInterface {
        Context mContext;

        /** Instantiate the interface and set the context */
        JavaScriptInterface(Context c) {
            mContext = c;
        }

        public void animationComplete() {
            mAnimationTime = System.currentTimeMillis();
        }
    }

    public void init(Context c) {
        WebSettingsClassic settings = getWebViewClassic().getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setSupportZoom(true);
        settings.setEnableSmoothTransition(true);
        settings.setBuiltInZoomControls(true);
        settings.setLoadWithOverviewMode(true);
        settings.setProperty("use_minimal_memory", "false"); // prefetch tiles, as browser does
        addJavascriptInterface(new JavaScriptInterface(c), "Android");
        mAnimationTime = 0;
        mLoadTime = 0;
    }

    public void setUseMinimalMemory(boolean minimal) {
        WebSettingsClassic settings = getWebViewClassic().getSettings();
        settings.setProperty("use_minimal_memory", minimal ? "true" : "false");
    }

    public void onPageFinished() {
        mLoadTime = System.currentTimeMillis();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (mIsTesting && mIsScrolling) {
            if (canScrollVertically(1)) {
                scrollBy(0, mSpeed);
            } else {
                stopScrollTest();
                mIsScrolling = false;
            }
        }
        super.onDraw(canvas);
    }

    /*
     * Called once the page is loaded to start scrolling for evaluating tiles.
     * If autoScrolling isn't set, stop must be called manually. Before
     * scrolling, invalidate all content and redraw it, measuring time taken.
     */
    public void startScrollTest(ProfileCallback callback, boolean autoScrolling) {
        mCallback = callback;
        mIsTesting = false;
        mIsScrolling = false;
        WebSettingsClassic settings = getWebViewClassic().getSettings();
        settings.setProperty("tree_updates", "0");


        if (autoScrolling) {
            // after a while, force it to start even if the pages haven't swapped
            new CountDownTimer(LOAD_STALL_MILLIS, LOAD_STALL_MILLIS) {
                @Override
                public void onTick(long millisUntilFinished) {
                }

                @Override
                public void onFinish() {
                    // invalidate all content, and kick off redraw
                    Log.d("ProfiledWebView",
                            "kicking off test with callback registration, and tile discard...");
                    getWebViewClassic().discardAllTextures();
                    invalidate();
                    mIsScrolling = true;
                    mContentInvalMillis = System.currentTimeMillis();
                }
            }.start();
        } else {
            mIsTesting = true;
            getWebViewClassic().tileProfilingStart();
        }
    }

    /*
     * Called after the manual contentInvalidateAll, after the tiles have all
     * been redrawn.
     * From PageSwapDelegate.
     */
    @Override
    public void onPageSwapOccurred(boolean startAnim) {
        if (!mIsTesting && mIsScrolling) {
            // kick off testing
            mContentInvalMillis = System.currentTimeMillis() - mContentInvalMillis;
            Log.d("ProfiledWebView", "REDRAW TOOK " + mContentInvalMillis + "millis");
            mIsTesting = true;
            invalidate(); // ensure a redraw so that auto-scrolling can occur
            getWebViewClassic().tileProfilingStart();
        }
    }

    private double animFramerate() {
        WebSettingsClassic settings = getWebViewClassic().getSettings();
        String updatesString = settings.getProperty("tree_updates");
        int updates = (updatesString == null) ? -1 : Integer.parseInt(updatesString);

        long animationTime;
        if (mAnimationTime == 0 || mAnimationTime - mLoadTime < ANIM_SAFETY_THRESHOLD) {
            animationTime = System.currentTimeMillis() - mLoadTime;
        } else {
            animationTime = mAnimationTime - mLoadTime;
        }

        return updates * 1000.0 / animationTime;
    }

    public void setDoubleBuffering(boolean useDoubleBuffering) {
        WebSettingsClassic settings = getWebViewClassic().getSettings();
        settings.setProperty("use_double_buffering", useDoubleBuffering ? "true" : "false");
    }

    /*
     * Called once the page has stopped scrolling
     */
    public void stopScrollTest() {
        getWebViewClassic().tileProfilingStop();
        mIsTesting = false;

        if (mCallback == null) {
            getWebViewClassic().tileProfilingClear();
            return;
        }

        RunData data = new RunData(getWebViewClassic().tileProfilingNumFrames());
        // record the time spent (before scrolling) rendering the page
        data.singleStats.put(getResources().getString(R.string.render_millis),
                (double)mContentInvalMillis);

        // record framerate
        double framerate = animFramerate();
        Log.d(LOGTAG, "anim framerate was "+framerate);
        data.singleStats.put(getResources().getString(R.string.animation_framerate),
                framerate);

        for (int frame = 0; frame < data.frames.length; frame++) {
            data.frames[frame] = new TileData[
                    getWebViewClassic().tileProfilingNumTilesInFrame(frame)];
            for (int tile = 0; tile < data.frames[frame].length; tile++) {
                int left = getWebViewClassic().tileProfilingGetInt(frame, tile, "left");
                int top = getWebViewClassic().tileProfilingGetInt(frame, tile, "top");
                int right = getWebViewClassic().tileProfilingGetInt(frame, tile, "right");
                int bottom = getWebViewClassic().tileProfilingGetInt(frame, tile, "bottom");

                boolean isReady = getWebViewClassic().tileProfilingGetInt(
                        frame, tile, "isReady") == 1;
                int level = getWebViewClassic().tileProfilingGetInt(frame, tile, "level");

                float scale = getWebViewClassic().tileProfilingGetFloat(frame, tile, "scale");

                data.frames[frame][tile] = data.new TileData(left, top, right, bottom,
                        isReady, level, scale);
            }
        }
        getWebViewClassic().tileProfilingClear();

        mCallback.profileCallback(data);
    }

    @Override
    public void loadUrl(String url) {
        mAnimationTime = 0;
        mLoadTime = 0;
        if (!url.startsWith("http://") && !url.startsWith("file://")) {
            url = "http://" + url;
        }
        super.loadUrl(url);
    }

    public void setAutoScrollSpeed(int speedInt) {
        mSpeed = speedInt;
    }

    public WebViewClassic getWebViewClassic() {
        return WebViewClassic.fromWebView(this);
    }
}
