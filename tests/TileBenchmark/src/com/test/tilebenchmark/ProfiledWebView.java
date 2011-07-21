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
import android.webkit.WebView;

import com.test.tilebenchmark.ProfileActivity.ProfileCallback;

public class ProfiledWebView extends WebView {
    private int mSpeed;

    private boolean isScrolling = false;
    private ProfileCallback mCallback;

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
        if (isScrolling) {
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
     * If autoScrolling isn't set, stop must be called manually.
     */
    public void startScrollTest(ProfileCallback callback, boolean autoScrolling) {
        isScrolling = autoScrolling;
        mCallback = callback;
        tileProfilingStart();
        invalidate();
    }

    /*
     * Called once the page has stopped scrolling
     */
    public void stopScrollTest() {
        super.tileProfilingStop();

        if (mCallback == null) {
            tileProfilingClear();
            return;
        }

        TileData data[][] = new TileData[super.tileProfilingNumFrames()][];
        for (int frame = 0; frame < data.length; frame++) {
            data[frame] = new TileData[
                    tileProfilingNumTilesInFrame(frame)];
            for (int tile = 0; tile < data[frame].length; tile++) {
                int left = tileProfilingGetInt(frame, tile, "left");
                int top = tileProfilingGetInt(frame, tile, "top");
                int right = tileProfilingGetInt(frame, tile, "right");
                int bottom = tileProfilingGetInt(frame, tile, "bottom");

                boolean isReady = super.tileProfilingGetInt(
                        frame, tile, "isReady") == 1;
                int level = tileProfilingGetInt(frame, tile, "level");

                float scale = tileProfilingGetFloat(frame, tile, "scale");

                data[frame][tile] = new TileData(left, top, right, bottom,
                        isReady, level, scale);
            }
        }
        super.tileProfilingClear();

        mCallback.profileCallback(data);
    }

    @Override
    public void loadUrl(String url) {
        if (!url.startsWith("http://")) {
            url = "http://" + url;
        }
        super.loadUrl(url);
    }

    public void setAutoScrollSpeed(int speedInt) {
        mSpeed = speedInt;
    }
}
