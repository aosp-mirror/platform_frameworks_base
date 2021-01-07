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

import static android.graphics.ColorSpace.Named.SRGB;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Picture;
import android.graphics.Rect;
import android.media.Image;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.screenshot.ScrollCaptureClient.Connection;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interaction controller between the UI and ScrollCaptureClient.
 */
public class ScrollCaptureController {
    private static final String TAG = "ScrollCaptureController";

    public static final int MAX_PAGES = 5;
    public static final int MAX_HEIGHT = 12000;

    private final Connection mConnection;
    private final Context mContext;

    private final Executor mUiExecutor;
    private final Executor mBgExecutor;
    private final ImageExporter mImageExporter;
    private Picture mPicture;

    public ScrollCaptureController(Context context, Connection connection, Executor uiExecutor,
            Executor bgExecutor, ImageExporter exporter) {
        mContext = context;
        mConnection = connection;
        mUiExecutor = uiExecutor;
        mBgExecutor = bgExecutor;
        mImageExporter = exporter;
    }

    /**
     * Run scroll capture!
     *
     * @param after action to take after the flow is complete
     */
    public void run(final Runnable after) {
        mConnection.start(MAX_PAGES, (session) -> startCapture(session, after));
    }

    private void startCapture(Session session, final Runnable onDismiss) {
        Rect requestRect = new Rect(0, 0,
                session.getMaxTileWidth(), session.getMaxTileHeight());
        Consumer<ScrollCaptureClient.CaptureResult> consumer =
                new Consumer<ScrollCaptureClient.CaptureResult>() {

                    int mFrameCount = 0;

                    @Override
                    public void accept(ScrollCaptureClient.CaptureResult result) {
                        mFrameCount++;
                        boolean emptyFrame = result.captured.height() == 0;
                        if (!emptyFrame) {
                            mPicture = stackBelow(mPicture, result.image, result.captured.width(),
                                    result.captured.height());
                        }
                        if (emptyFrame || mFrameCount >= MAX_PAGES
                                || requestRect.bottom > MAX_HEIGHT) {
                            if (mPicture != null) {
                                exportToFile(mPicture, session, onDismiss);
                            } else {
                                session.end(onDismiss);
                            }
                            return;
                        }
                        requestRect.offset(0, session.getMaxTileHeight());
                        session.requestTile(requestRect, /* consumer */ this);
                    }
                };

        // fire it up!
        session.requestTile(requestRect, consumer);
    };

    void exportToFile(Picture picture, Session session, Runnable afterEnd) {
        mImageExporter.setFormat(Bitmap.CompressFormat.PNG);
        mImageExporter.setQuality(6);
        ListenableFuture<Uri> future =
                mImageExporter.export(mBgExecutor, Bitmap.createBitmap(picture));
        future.addListener(() -> {
            picture.close(); // release resources
            try {
                launchViewer(future.get());
            } catch (InterruptedException | ExecutionException e) {
                Toast.makeText(mContext, "Failed to write image", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error storing screenshot to media store", e.getCause());
            }
            session.end(afterEnd); // end session, close connection, afterEnd.run()
        }, mUiExecutor);
    }

    /**
     * Combine the top {@link Picture} with an {@link Image} by appending the image directly
     * below, creating a result that is the combined height of both.
     * <p>
     * Note: no pixel data is transferred here, only a record of drawing commands. Backing
     * hardware buffers must not be modified/recycled until the picture is
     * {@link Picture#close closed}.
     *
     * @param top the existing picture
     * @param below the image to append below
     * @param cropWidth the width of the pixel data to use from the image
     * @param cropHeight the height of the pixel data to use from the image
     *
     * @return a new Picture which draws the previous picture with the image below it
     */
    private static Picture stackBelow(Picture top, Image below, int cropWidth, int cropHeight) {
        int width = cropWidth;
        int height = cropHeight;
        if (top != null) {
            height += top.getHeight();
            width = Math.max(width, top.getWidth());
        }
        Picture combined = new Picture();
        Canvas canvas = combined.beginRecording(width, height);
        int y = 0;
        if (top != null) {
            canvas.drawPicture(top, new Rect(0, 0, top.getWidth(), top.getHeight()));
            y += top.getHeight();
        }
        canvas.drawBitmap(Bitmap.wrapHardwareBuffer(
                below.getHardwareBuffer(), ColorSpace.get(SRGB)), 0, y, null);
        combined.endRecording();
        return combined;
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
