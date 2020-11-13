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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Picture;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.screenshot.ScrollCaptureClient.Connection;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
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
    private Picture mPicture;

    public ScrollCaptureController(Context context, Connection connection) {
        mContext = context;
        mConnection = connection;
    }

    /**
     * Run scroll capture!
     *
     * @param after action to take after the flow is complete
     */
    public void run(final Runnable after) {
        mConnection.start(MAX_PAGES, (session) -> startCapture(session, after));
    }

    private void startCapture(Session session, final Runnable after) {
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
                            Uri uri = null;
                            if (mPicture != null) {
                                // This is probably on a binder thread right now ¯\_(ツ)_/¯
                                uri = writeImage(Bitmap.createBitmap(mPicture));
                                // Release those buffers!
                                mPicture.close();
                            }
                            if (uri != null) {
                                launchViewer(uri);
                            } else {
                                Toast.makeText(mContext, "Failed to create tall screenshot",
                                        Toast.LENGTH_SHORT).show();
                            }
                            session.end(after); // end session, close connection, after.run()
                            return;
                        }
                        requestRect.offset(0, session.getMaxTileHeight());
                        session.requestTile(requestRect, /* consumer */ this);
                    }
                };

        // fire it up!
        session.requestTile(requestRect, consumer);
    };


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

    Uri writeImage(Bitmap image) {
        ContentResolver resolver = mContext.getContentResolver();
        long mImageTime = System.currentTimeMillis();
        String imageDate = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(mImageTime));
        String mImageFileName = String.format("tall_Screenshot_%s.png", imageDate);
        String mScreenshotId = String.format("Screenshot_%s", UUID.randomUUID());
        try {
            // Save the screenshot to the MediaStore
            final ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES
                    + File.separator + Environment.DIRECTORY_SCREENSHOTS);
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, mImageFileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            values.put(MediaStore.MediaColumns.DATE_ADDED, mImageTime / 1000);
            values.put(MediaStore.MediaColumns.DATE_MODIFIED, mImageTime / 1000);
            values.put(
                    MediaStore.MediaColumns.DATE_EXPIRES,
                    (mImageTime + DateUtils.DAY_IN_MILLIS) / 1000);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            final Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values);
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw new IOException("Failed to compress");
                    }
                }

                // Next, write metadata to help index the screenshot
                try (ParcelFileDescriptor pfd = resolver.openFile(uri, "rw", null)) {
                    final ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());

                    exif.setAttribute(ExifInterface.TAG_SOFTWARE,
                            "Android " + Build.DISPLAY);

                    exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH,
                            Integer.toString(image.getWidth()));
                    exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH,
                            Integer.toString(image.getHeight()));

                    final ZonedDateTime time = ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(mImageTime), ZoneId.systemDefault());
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(time));
                    exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                            DateTimeFormatter.ofPattern("SSS").format(time));

                    if (Objects.equals(time.getOffset(), ZoneOffset.UTC)) {
                        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00");
                    } else {
                        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                                DateTimeFormatter.ofPattern("XXX").format(time));
                    }
                    exif.saveAttributes();
                }

                // Everything went well above, publish it!
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                values.putNull(MediaStore.MediaColumns.DATE_EXPIRES);
                resolver.update(uri, values, null, null);
                return uri;
            } catch (Exception e) {
                resolver.delete(uri, null);
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, "unable to save screenshot", e);
        }
        return null;
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
