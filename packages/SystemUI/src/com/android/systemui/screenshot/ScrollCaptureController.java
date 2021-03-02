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

    private static final int UP = -1;
    private static final int DOWN = 1;

    private int mDirection = DOWN;
    private boolean mAtBottomEdge;
    private boolean mAtTopEdge;
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
        Log.d(TAG, "onCaptureResult: " + result);
        boolean emptyResult = result.captured.height() == 0;
        boolean partialResult = !emptyResult
                && result.captured.height() < result.requested.height();
        boolean finish = false;

        if (partialResult || emptyResult) {
            // Potentially reached a vertical boundary. Extend in the other direction.
            switch (mDirection) {
                case DOWN:
                    Log.d(TAG, "Reached bottom edge.");
                    mAtBottomEdge = true;
                    mDirection = UP;
                    break;
                case UP:
                    Log.d(TAG, "Reached top edge.");
                    mAtTopEdge = true;
                    mDirection = DOWN;
                    break;
            }

            if (mAtTopEdge && mAtBottomEdge) {
                Log.d(TAG, "Reached both top and bottom edge, ending.");
                finish = true;
            } else {
                // only reverse if the edge was relatively close to the starting point
                if (mImageTileSet.getHeight() < mSession.getPageHeight() * 3) {
                    Log.d(TAG, "Restarting in reverse direction.");

                    // Because of temporary limitations, we cannot just jump to the opposite edge
                    // and continue there. Instead, clear the results and start over capturing from
                    // here in the other direction.
                    mImageTileSet.clear();
                } else {
                    Log.d(TAG, "Capture is tall enough, stopping here.");
                    finish = true;
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
        if (mImageTileSet.size() >= mSession.getMaxTiles()
                || mImageTileSet.getHeight() > MAX_HEIGHT) {
            Log.d(TAG, "Max height and/or tile count reached.");
            finish = true;
        }

        if (finish) {
            Session session = mSession;
            mSession = null;
            Log.d(TAG, "Stop.");
            mUiExecutor.execute(() -> afterCaptureComplete(session));
            return;
        }

        int nextTop = (mDirection == DOWN) ? result.captured.bottom
                : result.captured.top - mSession.getTileHeight();
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
            mCaptureCallback.onComplete(mImageTileSet);
        }
    }

    /**
     * Callback for image capture completion or error.
     */
    public interface ScrollCaptureCallback {
        void onComplete(ImageTileSet imageTileSet);
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
