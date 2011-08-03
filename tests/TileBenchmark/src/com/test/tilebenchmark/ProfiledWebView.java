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
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.WebView;

import com.test.tilebenchmark.ProfileActivity.ProfileCallback;
import com.test.tilebenchmark.RunData.TileData;

public class ProfiledWebView extends WebView {
    private int mSpeed;

    private boolean isTesting = false;
    private boolean isScrolling = false;
    private ProfileCallback mCallback;
    private long mContentInvalMillis;

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

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (isTesting && isScrolling) {
            if (canScrollVertically(1)) {
                scrollBy(0, mSpeed);
            } else {
                stopScrollTest();
                isScrolling = false;
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
        isScrolling = autoScrolling;
        mCallback = callback;
        isTesting = false;
        mContentInvalMillis = System.currentTimeMillis();
        registerPageSwapCallback();
        contentInvalidateAll();
        invalidate();
    }

    /*
     * Called after the manual contentInvalidateAll, after the tiles have all
     * been redrawn.
     */
    @Override
    protected void pageSwapCallback() {
        mContentInvalMillis = System.currentTimeMillis() - mContentInvalMillis;
        super.pageSwapCallback();
        Log.d("ProfiledWebView", "REDRAW TOOK " + mContentInvalMillis
                + "millis");
        isTesting = true;
        invalidate(); // ensure a redraw so that auto-scrolling can occur
        tileProfilingStart();
    }

    /*
     * Called once the page has stopped scrolling
     */
    public void stopScrollTest() {
        tileProfilingStop();
        isTesting = false;

        if (mCallback == null) {
            tileProfilingClear();
            return;
        }

        RunData data = new RunData(super.tileProfilingNumFrames());
        data.singleStats.put(getResources().getString(R.string.render_millis),
                (double)mContentInvalMillis);
        for (int frame = 0; frame < data.frames.length; frame++) {
            data.frames[frame] = new TileData[
                    tileProfilingNumTilesInFrame(frame)];
            for (int tile = 0; tile < data.frames[frame].length; tile++) {
                int left = tileProfilingGetInt(frame, tile, "left");
                int top = tileProfilingGetInt(frame, tile, "top");
                int right = tileProfilingGetInt(frame, tile, "right");
                int bottom = tileProfilingGetInt(frame, tile, "bottom");

                boolean isReady = super.tileProfilingGetInt(
                        frame, tile, "isReady") == 1;
                int level = tileProfilingGetInt(frame, tile, "level");

                float scale = tileProfilingGetFloat(frame, tile, "scale");

                data.frames[frame][tile] = data.new TileData(left, top, right, bottom,
                        isReady, level, scale);
            }
        }
        tileProfilingClear();

        mCallback.profileCallback(data);
    }

    @Override
    public void loadUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("file://")) {
            url = "http://" + url;
        }
        super.loadUrl(url);
    }

    public void setAutoScrollSpeed(int speedInt) {
        mSpeed = speedInt;
    }
}
