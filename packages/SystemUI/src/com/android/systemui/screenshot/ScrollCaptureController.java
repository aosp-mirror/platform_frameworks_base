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
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.screenshot.ScrollCaptureClient.Connection;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;

import com.google.common.util.concurrent.ListenableFuture;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interaction controller between the UI and ScrollCaptureClient.
 */
public class ScrollCaptureController {
    private static final String TAG = "ScrollCaptureController";

    private static final boolean USE_TILED_IMAGE = false;

    public static final int MAX_PAGES = 5;
    public static final int MAX_HEIGHT = 12000;

    private final Connection mConnection;
    private final Context mContext;

    private final Executor mUiExecutor;
    private final Executor mBgExecutor;
    private final ImageExporter mImageExporter;
    private final ImageTileSet mImageTileSet;

    private ZonedDateTime mCaptureTime;
    private UUID mRequestId;

    public ScrollCaptureController(Context context, Connection connection, Executor uiExecutor,
            Executor bgExecutor, ImageExporter exporter) {
        mContext = context;
        mConnection = connection;
        mUiExecutor = uiExecutor;
        mBgExecutor = bgExecutor;
        mImageExporter = exporter;
        mImageTileSet = new ImageTileSet();
    }

    /**
     * Run scroll capture!
     *
     * @param after action to take after the flow is complete
     */
    public void run(final Runnable after) {
        mCaptureTime = ZonedDateTime.now();
        mRequestId = UUID.randomUUID();
        mConnection.start((session) -> startCapture(session, after));
    }

    private void startCapture(Session session, final Runnable onDismiss) {
        Consumer<ScrollCaptureClient.CaptureResult> consumer =
                new Consumer<ScrollCaptureClient.CaptureResult>() {

                    int mFrameCount = 0;
                    int mTop = 0;

                    @Override
                    public void accept(ScrollCaptureClient.CaptureResult result) {
                        mFrameCount++;

                        boolean emptyFrame = result.captured.height() == 0;
                        if (!emptyFrame) {
                            mImageTileSet.addTile(new ImageTile(result.image, result.captured));
                        }

                        if (emptyFrame || mFrameCount >= MAX_PAGES
                                || mTop + session.getTileHeight() > MAX_HEIGHT) {
                            if (!mImageTileSet.isEmpty()) {
                                exportToFile(mImageTileSet.toBitmap(), session, onDismiss);
                                mImageTileSet.clear();
                            } else {
                                session.end(onDismiss);
                            }
                            return;
                        }
                        mTop += result.captured.height();
                        session.requestTile(mTop, /* consumer */ this);
                    }
                };

        // fire it up!
        session.requestTile(0, consumer);
    };

    void exportToFile(Bitmap bitmap, Session session, Runnable afterEnd) {
        mImageExporter.setFormat(Bitmap.CompressFormat.PNG);
        mImageExporter.setQuality(6);
        ListenableFuture<ImageExporter.Result> future =
                mImageExporter.export(mBgExecutor, mRequestId, bitmap, mCaptureTime);
        future.addListener(() -> {
            try {
                ImageExporter.Result result = future.get();
                launchViewer(result.uri);
            } catch (InterruptedException | ExecutionException e) {
                Toast.makeText(mContext, "Failed to write image", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error storing screenshot to media store", e.getCause());
            }
            session.end(afterEnd); // end session, close connection, afterEnd.run()
        }, mUiExecutor);
    }

    void launchViewer(Uri uri) {
        Intent editIntent = new Intent(Intent.ACTION_VIEW);
        editIntent.setType("image/png");
        editIntent.setData(uri);
        editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivityAsUser(editIntent, UserHandle.CURRENT);
    }
}
