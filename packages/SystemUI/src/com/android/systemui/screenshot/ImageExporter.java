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

import static android.os.FileUtils.closeQuietly;

import android.annotation.IntRange;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.MediaStore;
import android.util.Log;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.exifinterface.media.ExifInterface;

import com.android.internal.annotations.VisibleForTesting;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.inject.Inject;

class ImageExporter {
    private static final String TAG = LogConfig.logTag(ImageExporter.class);

    static final Duration PENDING_ENTRY_TTL = Duration.ofHours(24);

    // ex: 'Screenshot_20201215-090626.png'
    private static final String FILENAME_PATTERN = "Screenshot_%1$tY%<tm%<td-%<tH%<tM%<tS.%2$s";
    private static final String SCREENSHOTS_PATH = Environment.DIRECTORY_PICTURES
            + File.separator + Environment.DIRECTORY_SCREENSHOTS;

    private static final String RESOLVER_INSERT_RETURNED_NULL =
            "ContentResolver#insert returned null.";
    private static final String RESOLVER_OPEN_FILE_RETURNED_NULL =
            "ContentResolver#openFile returned null.";
    private static final String RESOLVER_OPEN_FILE_EXCEPTION =
            "ContentResolver#openFile threw an exception.";
    private static final String OPEN_OUTPUT_STREAM_EXCEPTION =
            "ContentResolver#openOutputStream threw an exception.";
    private static final String EXIF_READ_EXCEPTION =
            "ExifInterface threw an exception reading from the file descriptor.";
    private static final String EXIF_WRITE_EXCEPTION =
            "ExifInterface threw an exception writing to the file descriptor.";
    private static final String RESOLVER_UPDATE_ZERO_ROWS =
            "Failed to publish entry. ContentResolver#update reported no rows updated.";
    private static final String IMAGE_COMPRESS_RETURNED_FALSE =
            "Bitmap.compress returned false. (Failure unknown)";

    private final ContentResolver mResolver;
    private CompressFormat mCompressFormat = CompressFormat.PNG;
    private int mQuality = 100;

    @Inject
    ImageExporter(ContentResolver resolver) {
        mResolver = resolver;
    }

    /**
     * Adjusts the output image format. This also determines extension of the filename created. The
     * default is {@link CompressFormat#PNG PNG}.
     *
     * @see CompressFormat
     *
     * @param format the image format for export
     */
    void setFormat(CompressFormat format) {
        mCompressFormat = format;
    }

    /**
     * Sets the quality format. The exact meaning is dependent on the {@link CompressFormat} used.
     *
     * @param quality the 'quality' level between 0 and 100
     */
    void setQuality(@IntRange(from = 0, to = 100) int quality) {
        mQuality = quality;
    }

    /**
     * Writes the given Bitmap to outputFile.
     */
    ListenableFuture<File> exportToRawFile(Executor executor, Bitmap bitmap,
            final File outputFile) {
        return CallbackToFutureAdapter.getFuture(
                (completer) -> {
                    executor.execute(() -> {
                        try (FileOutputStream stream = new FileOutputStream(outputFile)) {
                            bitmap.compress(mCompressFormat, mQuality, stream);
                            completer.set(outputFile);
                        } catch (IOException e) {
                            if (outputFile.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                outputFile.delete();
                            }
                            completer.setException(e);
                        }
                    });
                    return "Bitmap#compress";
                }
        );
    }

    /**
     * Export the image using the given executor.
     *
     * @param executor the thread for execution
     * @param bitmap the bitmap to export
     *
     * @return a listenable future result
     */
    ListenableFuture<Result> export(Executor executor, UUID requestId, Bitmap bitmap) {
        return export(executor, requestId, bitmap, ZonedDateTime.now());
    }

    /**
     * Export the image to MediaStore and publish.
     *
     * @param executor the thread for execution
     * @param bitmap the bitmap to export
     *
     * @return a listenable future result
     */
    ListenableFuture<Result> export(Executor executor, UUID requestId, Bitmap bitmap,
            ZonedDateTime captureTime) {

        final Task task = new Task(mResolver, requestId, bitmap, captureTime, mCompressFormat,
                mQuality, /* publish */ true);

        return CallbackToFutureAdapter.getFuture(
                (completer) -> {
                    executor.execute(() -> {
                        try {
                            completer.set(task.execute());
                        } catch (ImageExportException | InterruptedException e) {
                            completer.setException(e);
                        }
                    });
                    return task;
                }
        );
    }

    /**
     * Delete the entry.
     *
     * @param executor the thread for execution
     * @param uri the uri of the image to publish
     *
     * @return a listenable future result
     */
    ListenableFuture<Result> delete(Executor executor, Uri uri) {
        return CallbackToFutureAdapter.getFuture((completer) -> {
            executor.execute(() -> {
                mResolver.delete(uri, null);

                Result result = new Result();
                result.uri = uri;
                result.deleted = true;
                completer.set(result);
            });
            return "ContentResolver#delete";
        });
    }

    static class Result {
        Uri uri;
        UUID requestId;
        String fileName;
        long timestamp;
        CompressFormat format;
        boolean published;
        boolean deleted;

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("Result{");
            sb.append("uri=").append(uri);
            sb.append(", requestId=").append(requestId);
            sb.append(", fileName='").append(fileName).append('\'');
            sb.append(", timestamp=").append(timestamp);
            sb.append(", format=").append(format);
            sb.append(", published=").append(published);
            sb.append(", deleted=").append(deleted);
            sb.append('}');
            return sb.toString();
        }
    }

    private static class Task {
        private final ContentResolver mResolver;
        private final UUID mRequestId;
        private final Bitmap mBitmap;
        private final ZonedDateTime mCaptureTime;
        private final CompressFormat mFormat;
        private final int mQuality;
        private final String mFileName;
        private final boolean mPublish;

        Task(ContentResolver resolver, UUID requestId, Bitmap bitmap, ZonedDateTime captureTime,
                CompressFormat format, int quality, boolean publish) {
            mResolver = resolver;
            mRequestId = requestId;
            mBitmap = bitmap;
            mCaptureTime = captureTime;
            mFormat = format;
            mQuality = quality;
            mFileName = createFilename(mCaptureTime, mFormat);
            mPublish = publish;
        }

        public Result execute() throws ImageExportException, InterruptedException {
            Trace.beginSection("ImageExporter_execute");
            Uri uri = null;
            Instant start = null;
            Result result = new Result();
            try {
                if (LogConfig.DEBUG_STORAGE) {
                    Log.d(TAG, "image export started");
                    start = Instant.now();
                }

                uri = createEntry(mResolver, mFormat, mCaptureTime, mFileName);
                throwIfInterrupted();

                writeImage(mResolver, mBitmap, mFormat, mQuality, uri);
                throwIfInterrupted();

                int width = mBitmap.getWidth();
                int height = mBitmap.getHeight();
                writeExif(mResolver, uri, mRequestId, width, height, mCaptureTime);
                throwIfInterrupted();

                if (mPublish) {
                    publishEntry(mResolver, uri);
                    result.published = true;
                }

                result.timestamp = mCaptureTime.toInstant().toEpochMilli();
                result.requestId = mRequestId;
                result.uri = uri;
                result.fileName = mFileName;
                result.format = mFormat;

                if (LogConfig.DEBUG_STORAGE) {
                    Log.d(TAG, "image export completed: "
                            + Duration.between(start, Instant.now()).toMillis() + " ms");
                }
            } catch (ImageExportException e) {
                if (uri != null) {
                    mResolver.delete(uri, null);
                }
                throw e;
            } finally {
                Trace.endSection();
            }
            return result;
        }

        @Override
        public String toString() {
            return "export [" + mBitmap + "] to [" + mFormat + "] at quality " + mQuality;
        }
    }

    private static Uri createEntry(ContentResolver resolver, CompressFormat format,
            ZonedDateTime time, String fileName) throws ImageExportException {
        Trace.beginSection("ImageExporter_createEntry");
        try {
            final ContentValues values = createMetadata(time, format, fileName);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new ImageExportException(RESOLVER_INSERT_RETURNED_NULL);
            }
            return uri;
        } finally {
            Trace.endSection();
        }
    }

    private static void writeImage(ContentResolver resolver, Bitmap bitmap, CompressFormat format,
            int quality, Uri contentUri) throws ImageExportException {
        Trace.beginSection("ImageExporter_writeImage");
        try (OutputStream out = resolver.openOutputStream(contentUri)) {
            long start = SystemClock.elapsedRealtime();
            if (!bitmap.compress(format, quality, out)) {
                throw new ImageExportException(IMAGE_COMPRESS_RETURNED_FALSE);
            } else if (LogConfig.DEBUG_STORAGE) {
                Log.d(TAG, "Bitmap.compress took "
                        + (SystemClock.elapsedRealtime() - start) + " ms");
            }
        } catch (IOException ex) {
            throw new ImageExportException(OPEN_OUTPUT_STREAM_EXCEPTION, ex);
        } finally {
            Trace.endSection();
        }
    }

    private static void writeExif(ContentResolver resolver, Uri uri, UUID requestId, int width,
            int height, ZonedDateTime captureTime) throws ImageExportException {
        Trace.beginSection("ImageExporter_writeExif");
        ParcelFileDescriptor pfd = null;
        try {
            pfd = resolver.openFile(uri, "rw", null);
            if (pfd == null) {
                throw new ImageExportException(RESOLVER_OPEN_FILE_RETURNED_NULL);
            }
            ExifInterface exif;
            try {
                exif = new ExifInterface(pfd.getFileDescriptor());
            } catch (IOException e) {
                throw new ImageExportException(EXIF_READ_EXCEPTION, e);
            }

            updateExifAttributes(exif, requestId, width, height, captureTime);
            try {
                exif.saveAttributes();
            } catch (IOException e) {
                throw new ImageExportException(EXIF_WRITE_EXCEPTION, e);
            }
        } catch (FileNotFoundException e) {
            throw new ImageExportException(RESOLVER_OPEN_FILE_EXCEPTION, e);
        } finally {
            closeQuietly(pfd);
            Trace.endSection();
        }
    }

    private static void publishEntry(ContentResolver resolver, Uri uri)
            throws ImageExportException {
        Trace.beginSection("ImageExporter_publishEntry");
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            values.putNull(MediaStore.MediaColumns.DATE_EXPIRES);
            final int rowsUpdated = resolver.update(uri, values, /* extras */ null);
            if (rowsUpdated < 1) {
                throw new ImageExportException(RESOLVER_UPDATE_ZERO_ROWS);
            }
        } finally {
            Trace.endSection();
        }
    }

    @VisibleForTesting
    static String createFilename(ZonedDateTime time, CompressFormat format) {
        return String.format(FILENAME_PATTERN, time, fileExtension(format));
    }

    static ContentValues createMetadata(ZonedDateTime captureTime, CompressFormat format,
            String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, SCREENSHOTS_PATH);
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(format));
        values.put(MediaStore.MediaColumns.DATE_ADDED, captureTime.toEpochSecond());
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, captureTime.toEpochSecond());
        values.put(MediaStore.MediaColumns.DATE_EXPIRES,
                captureTime.plus(PENDING_ENTRY_TTL).toEpochSecond());
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        return values;
    }

    static void updateExifAttributes(ExifInterface exif, UUID uniqueId, int width, int height,
            ZonedDateTime captureTime) {
        exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, uniqueId.toString());

        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Android " + Build.DISPLAY);
        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, Integer.toString(width));
        exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, Integer.toString(height));

        String dateTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(captureTime);
        String subSec = DateTimeFormatter.ofPattern("SSS").format(captureTime);
        String timeZone = DateTimeFormatter.ofPattern("xxx").format(captureTime);

        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSec);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, timeZone);
    }

    static String getMimeType(CompressFormat format) {
        switch (format) {
            case JPEG:
                return "image/jpeg";
            case PNG:
                return "image/png";
            case WEBP:
            case WEBP_LOSSLESS:
            case WEBP_LOSSY:
                return "image/webp";
            default:
                throw new IllegalArgumentException("Unknown CompressFormat!");
        }
    }

    static String fileExtension(CompressFormat format) {
        switch (format) {
            case JPEG:
                return "jpg";
            case PNG:
                return "png";
            case WEBP:
            case WEBP_LOSSY:
            case WEBP_LOSSLESS:
                return "webp";
            default:
                throw new IllegalArgumentException("Unknown CompressFormat!");
        }
    }

    private static void throwIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    static final class ImageExportException extends IOException {
        ImageExportException(String message) {
            super(message);
        }

        ImageExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
