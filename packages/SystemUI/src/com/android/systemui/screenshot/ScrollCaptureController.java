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

import android.annotation.UiThread;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.screenshot.ScrollCaptureClient.CaptureResult;
import com.android.systemui.screenshot.ScrollCaptureClient.Connection;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;

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

    private Session mSession;

    public static final int MAX_HEIGHT = 12000;

    private final Context mContext;

    private final Executor mUiExecutor;
    private final Executor mBgExecutor;
    private final ImageExporter mImageExporter;
    private final ImageTileSet mImageTileSet;

    private ZonedDateTime mCaptureTime;
    private UUID mRequestId;
    private ScrollCaptureCallback mCaptureCallback;

    public ScrollCaptureController(Context context, Executor uiExecutor, Executor bgExecutor,
            ImageExporter exporter) {
        mContext = context;
        mUiExecutor = uiExecutor;
        mBgExecutor = bgExecutor;
        mImageExporter = exporter;
        mImageTileSet = new ImageTileSet(context.getMainThreadHandler());
    }

    /**
     * Run scroll capture!
     *
     * @param connection connection to the remote window to be used
     * @param callback request callback to report back to the service
     */
    public void start(Connection connection, ScrollCaptureCallback callback) {
        mCaptureTime = ZonedDateTime.now();
        mRequestId = UUID.randomUUID();
        mCaptureCallback = callback;

        float maxPages = Settings.Secure.getFloat(mContext.getContentResolver(),
                SETTING_KEY_MAX_PAGES, MAX_PAGES_DEFAULT);
        connection.start(this::startCapture, maxPages);
    }

    private void onCaptureResult(CaptureResult result) {
        Log.d(TAG, "onCaptureResult: " + result + " scrolling up: " + mScrollingUp
                + " finish on boundary: " + mFinishOnBoundary);
        boolean emptyResult = result.captured.height() == 0;
        boolean partialResult = !emptyResult
                && result.captured.height() < result.requested.height();
        boolean finish = false;

        if (partialResult || emptyResult) {
            // Potentially reached a vertical boundary. Extend in the other direction.
            if (mFinishOnBoundary) {
                finish = true;
            } else {
                // We hit a boundary, clear the tiles, capture everything in the opposite direction,
                // then finish.
                mImageTileSet.clear();
                mFinishOnBoundary = true;
                mScrollingUp = !mScrollingUp;
            }
        } else {
            // Got the full requested result, but may have got enough bitmap data now
            int expectedTiles = mImageTileSet.size() + 1;
            boolean hitMaxTiles = expectedTiles >= mSession.getMaxTiles();
            if (hitMaxTiles && mFinishOnBoundary) {
                finish = true;
            } else {
                if (mScrollingUp) {
                    if (expectedTiles >= mSession.getMaxTiles() * IDEAL_PORTION_ABOVE) {
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
            Session session = mSession;
            mSession = null;
            Log.d(TAG, "Stop.");
            mUiExecutor.execute(() -> afterCaptureComplete(session));
            return;
        }

        int nextTop = (mScrollingUp)
                ? result.captured.top - mSession.getTileHeight() : result.captured.bottom;
        Log.d(TAG, "requestTile: " + nextTop);
        mSession.requestTile(nextTop, /* consumer */ this::onCaptureResult);
    }

    private void startCapture(Session session) {
        mSession = session;
        session.requestTile(0, this::onCaptureResult);
    }

    @UiThread
    void afterCaptureComplete(Session session) {
        Log.d(TAG, "afterCaptureComplete");

        if (mImageTileSet.isEmpty()) {
            mCaptureCallback.onError();
        } else {
            mCaptureCallback.onComplete(mImageTileSet, session.getPageHeight());
        }
    }

    /**
     * Callback for image capture completion or error.
     */
    public interface ScrollCaptureCallback {
        void onComplete(ImageTileSet imageTileSet, int pageHeight);
        void onError();
    }

    /**
     * Callback for image export completion or error.
     */
    public interface ExportCallback {
        void onExportComplete(Uri outputUri);
        void onError();
    }

}
