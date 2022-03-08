/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.view;

import static android.util.MathUtils.constrain;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.webkit.WebView;

/**
 * ScrollCapture for WebView.
 */
class WebViewCaptureHelper implements ScrollCaptureViewHelper<WebView> {
    private static final String TAG = "WebViewScrollCapture";

    private final Rect mRequestWebViewLocal = new Rect();
    private final Rect mWebViewBounds = new Rect();

    private int mOriginScrollY;
    private int mOriginScrollX;

    @Override
    public boolean onAcceptSession(@NonNull WebView view) {
        return view.isVisibleToUser()
                && (view.getContentHeight() * view.getScale()) > view.getHeight();
    }

    @Override
    public void onPrepareForStart(@NonNull WebView view, @NonNull Rect scrollBounds) {
        mOriginScrollX = view.getScrollX();
        mOriginScrollY = view.getScrollY();
    }

    @NonNull
    @Override
    public ScrollResult onScrollRequested(@NonNull WebView view, @NonNull Rect scrollBounds,
            @NonNull Rect requestRect) {

        int scrollDelta = view.getScrollY() - mOriginScrollY;

        ScrollResult result = new ScrollResult();
        result.requestedArea = new Rect(requestRect);
        result.availableArea = new Rect();
        result.scrollDelta = scrollDelta;

        mWebViewBounds.set(0, 0, view.getWidth(), view.getHeight());

        if (!view.isVisibleToUser()) {
            return result;
        }

        // Map the request into local coordinates
        mRequestWebViewLocal.set(requestRect);
        mRequestWebViewLocal.offset(0, -scrollDelta);

        // Offset to center the rect vertically, clamp to available content
        int upLimit = min(0, -view.getScrollY());
        int contentHeightPx = (int) (view.getContentHeight() * view.getScale());
        int downLimit = max(0, (contentHeightPx - view.getHeight()) - view.getScrollY());
        int scrollToCenter = mRequestWebViewLocal.centerY() - mWebViewBounds.centerY();
        int scrollMovement = constrain(scrollToCenter, upLimit, downLimit);

        // Scroll and update relative based on  the new position
        view.scrollBy(mOriginScrollX, scrollMovement);
        scrollDelta = view.getScrollY() - mOriginScrollY;
        mRequestWebViewLocal.offset(0, -scrollMovement);
        result.scrollDelta = scrollDelta;

        if (mRequestWebViewLocal.intersect(mWebViewBounds)) {
            result.availableArea = new Rect(mRequestWebViewLocal);
            result.availableArea.offset(0, result.scrollDelta);
        }
        return result;
    }

    @Override
    public void onPrepareForEnd(@NonNull WebView view) {
        view.scrollTo(mOriginScrollX, mOriginScrollY);
    }

}

