/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Log;
import android.view.ScrollCaptureResponse;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.concurrent.futures.CallbackToFutureAdapter.Completer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.screenshot.ScrollCaptureClient.CaptureResult;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Interaction controller between the UI and ScrollCaptureClient.
 */
public class ScrollCaptureController {
    private static final String TAG = "ScrollCaptureController";
    private static final float MAX_PAGES_DEFAULT = 3f;

    private static final String SETTING_KEY_MAX_PAGES = "screenshot.scroll_max_pages";
    // Portion of the tiles to be acquired above the starting position in infinite scroll
    // situations. 1.0 means maximize the area above, 0 means just go down.
    private static final float IDEAL_PORTION_ABOVE = 0.4f;

    private boolean mScrollingUp = true;
    // If true, stop acquiring images when no more bitmap data is available in the current direction
    // or if the desired bitmap size is reached.
    private boolean mFinishOnBoundary;

    public static final int MAX_HEIGHT = 12000;

    private final Context mContext;
    private final Executor mBgExecutor;
    private final ImageTileSet mImageTileSet;
    private final ScrollCaptureClient mClient;

    private Completer<LongScreenshot> mCaptureCompleter;

    private ListenableFuture<Session> mSessionFuture;
    private Session mSession;
    private ListenableFuture<CaptureResult> mTileFuture;
    private ListenableFuture<Void> mEndFuture;

    static class LongScreenshot {
        private final ImageTileSet mImageTileSet;
        private final Session mSession;

        LongScreenshot(Session session, ImageTileSet imageTileSet) {
            mSession = session;
            mImageTileSet = imageTileSet;
        }

        /** Returns a bitmap containing the combinded result. */
        public Bitmap toBitmap() {
            return mImageTileSet.toBitmap();
        }

        public Bitmap toBitmap(Rect bounds) {
            return mImageTileSet.toBitmap(bounds);
        }

        /** Releases image resources from the screenshot. */
        public void release() {
            Log.d(TAG, "LongScreenshot :: release()");
            mImageTileSet.clear();
            mSession.release();
        }

        public int getLeft() {
            return mImageTileSet.getLeft();
        }

        public int getTop() {
            return mImageTileSet.getTop();
        }

        public int getBottom() {
            return mImageTileSet.getBottom();
        }

        public int getWidth() {
            return mImageTileSet.getWidth();
        }

        public int getHeight() {
            return mImageTileSet.getHeight();
        }

        /** @return the height of the visible area of the scrolling page, in pixels */
        public int getPageHeight() {
            return mSession.getPageHeight();
        }

        @Override
        public String toString() {
            return "LongScreenshot{w=" + mImageTileSet.getWidth()
                    + ", h=" + mImageTileSet.getHeight() + "}";
        }

        public Drawable getDrawable() {
            return mImageTileSet.getDrawable();
        }
    }

    @Inject
    ScrollCaptureController(Context context, @Background Executor bgExecutor,
            ScrollCaptureClient client, ImageTileSet imageTileSet) {
        mContext = context;
        mBgExecutor = bgExecutor;
        mClient = client;
        mImageTileSet = imageTileSet;
    }

    @VisibleForTesting
    float getTargetTopSizeRatio() {
        return IDEAL_PORTION_ABOVE;
    }

    /**
     * Run scroll capture. Performs a batch capture, collecting image tiles.
     *
     * @param response a scroll capture response from a previous request which is
     *                 {@link ScrollCaptureResponse#isConnected() connected}.
     * @return a future ImageTile set containing the result
     */
    ListenableFuture<LongScreenshot> run(ScrollCaptureResponse response) {
        Log.d(TAG, "run: " + response);
        return CallbackToFutureAdapter.getFuture(completer -> {
            Log.d(TAG, "getFuture(ImageTileSet) ");
            mCaptureCompleter = completer;
            mBgExecutor.execute(() -> {
                Log.d(TAG, "bgExecutor.execute");
                float maxPages = Settings.Secure.getFloat(mContext.getContentResolver(),
                        SETTING_KEY_MAX_PAGES, MAX_PAGES_DEFAULT);
                Log.d(TAG, "client start, maxPages=" + maxPages);
                mSessionFuture = mClient.start(response, maxPages);
                mSessionFuture.addListener(this::onStartComplete, mContext.getMainExecutor());
            });
            return "<batch scroll capture>";
        });
    }

    private void onStartComplete() {
        try {
            mSession = mSessionFuture.get();
            Log.d(TAG, "got session " + mSession);
            requestNextTile(0);
        } catch (InterruptedException | ExecutionException e) {
            // Failure to start, propagate to caller
            Log.d(TAG, "session start failed!");
            mCaptureCompleter.setException(e);
        }
    }

    private void requestNextTile(int topPx) {
        Log.d(TAG, "requestNextTile: " + topPx);
        mTileFuture = mSession.requestTile(topPx);
        mTileFuture.addListener(() -> {
            try {
                Log.d(TAG, "onCaptureResult");
                onCaptureResult(mTileFuture.get());
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "requestTile failed!", e);
                mCaptureCompleter.setException(e);
            }
        }, mContext.getMainExecutor());
    }

    private void onCaptureResult(CaptureResult result) {
        Log.d(TAG, "onCaptureResult: " + result + " scrolling " + (mScrollingUp ? "UP" : "DOWN")
                + " finish on boundary: " + mFinishOnBoundary);
        boolean emptyResult = result.captured.height() == 0;
        boolean partialResult = !emptyResult
                && result.captured.height() < result.requested.height();
        boolean finish = false;

        if (partialResult || emptyResult) {
            // Potentially reached a vertical boundary. Extend in the other direction.
            if (mFinishOnBoundary) {
                Log.d(TAG, "Partial/empty: finished!");
                finish = true;
            } else {
                // We hit a boundary, clear the tiles, capture everything in the opposite direction,
                // then finish.
                mImageTileSet.clear();
                mFinishOnBoundary = true;
                mScrollingUp = !mScrollingUp;
                Log.d(TAG, "Partial/empty: cleared, switch direction to finish");
            }
        } else {
            // Got the full requested result, but may have got enough bitmap data now
            int expectedTiles = mImageTileSet.size() + 1;
            if (expectedTiles >= mSession.getMaxTiles()) {
                Log.d(TAG, "Hit max tiles: finished");
                // If we ever hit the max tiles, we've got enough bitmap data to finish (even if we
                // weren't sure we'd finish on this pass).
                finish = true;
            } else {
                if (mScrollingUp && !mFinishOnBoundary) {
                    // During the initial scroll up, we only want to acquire the portion described
                    // by IDEAL_PORTION_ABOVE.
                    if (expectedTiles >= mSession.getMaxTiles() * IDEAL_PORTION_ABOVE) {
                        Log.d(TAG, "Hit ideal portion above: clear and switch direction");
                        // We got enough above the start point, now see how far down it can go.
                        mImageTileSet.clear();
                        mScrollingUp = false;
                    }
                }
            }
        }

        if (!emptyResult) {
            mImageTileSet.addTile(new ImageTile(result.image, result.captured));
        }

        Log.d(TAG, "bounds: " + mImageTileSet.getLeft() + "," + mImageTileSet.getTop()
                + " - " +  mImageTileSet.getRight() + "," + mImageTileSet.getBottom()
                + " (" + mImageTileSet.getWidth() + "x" + mImageTileSet.getHeight() + ")");


        // Stop when "too tall"
        if (mImageTileSet.getHeight() > MAX_HEIGHT) {
            Log.d(TAG, "Max height reached.");
            finish = true;
        }

        if (finish) {
            Log.d(TAG, "Stop.");
            finishCapture();
            return;
        }

        // Partial or empty results caused the direction the flip, so we can reliably use the
        // requested edges to determine the next top.
        int nextTop = (mScrollingUp) ? result.requested.top - mSession.getTileHeight()
                : result.requested.bottom;
        requestNextTile(nextTop);
    }

    private void finishCapture() {
        Log.d(TAG, "finishCapture()");
        mEndFuture = mSession.end();
        mEndFuture.addListener(() -> {
            Log.d(TAG, "endCapture completed");
            // Provide result to caller and complete the top-level future
            // Caller is responsible for releasing this resource (ImageReader/HardwareBuffers)
            mCaptureCompleter.set(new LongScreenshot(mSession, mImageTileSet));
        }, mContext.getMainExecutor());
    }
}
