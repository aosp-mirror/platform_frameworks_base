/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Exposes custom clock face options and provides realistic preview images.
 *
 * APIs:
 *
 *   /list_options: List the available clock faces, which has the following columns
 *     name: name of the clock face
 *     title: title of the clock face
 *     id: value used to set the clock face
 *     thumbnail: uri of the thumbnail image, should be /thumbnail/{name}
 *     preview: uri of the preview image, should be /preview/{name}
 *
 *   /thumbnail/{id}: Opens a file stream for the thumbnail image for clock face {id}.
 *
 *   /preview/{id}: Opens a file stream for the preview image for clock face {id}.
 */
public final class ClockOptionsProvider extends ContentProvider {

    private static final String TAG = "ClockOptionsProvider";
    private static final String KEY_LIST_OPTIONS = "/list_options";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_THUMBNAIL = "thumbnail";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_THUMBNAIL = "thumbnail";
    private static final String COLUMN_PREVIEW = "preview";
    private static final String MIME_TYPE_PNG = "image/png";
    private static final String CONTENT_SCHEME = "content";
    private static final String AUTHORITY = "com.android.keyguard.clock";

    @Inject
    public Provider<List<ClockInfo>> mClockInfosProvider;

    @VisibleForTesting
    ClockOptionsProvider(Provider<List<ClockInfo>> clockInfosProvider) {
        mClockInfosProvider = clockInfosProvider;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() > 0 && (KEY_PREVIEW.equals(segments.get(0))
                || KEY_THUMBNAIL.equals(segments.get(0)))) {
            return MIME_TYPE_PNG;
        }
        return "vnd.android.cursor.dir/clock_faces";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if (!KEY_LIST_OPTIONS.equals(uri.getPath())) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {
                COLUMN_NAME, COLUMN_TITLE, COLUMN_ID, COLUMN_THUMBNAIL, COLUMN_PREVIEW});
        List<ClockInfo> clocks = mClockInfosProvider.get();
        for (int i = 0; i < clocks.size(); i++) {
            ClockInfo clock = clocks.get(i);
            cursor.newRow()
                    .add(COLUMN_NAME, clock.getName())
                    .add(COLUMN_TITLE, clock.getTitle())
                    .add(COLUMN_ID, clock.getId())
                    .add(COLUMN_THUMBNAIL, createThumbnailUri(clock))
                    .add(COLUMN_PREVIEW, createPreviewUri(clock));
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !(KEY_PREVIEW.equals(segments.get(0))
                || KEY_THUMBNAIL.equals(segments.get(0)))) {
            throw new FileNotFoundException("Invalid preview url");
        }
        String id = segments.get(1);
        if (TextUtils.isEmpty(id)) {
            throw new FileNotFoundException("Invalid preview url, missing id");
        }
        ClockInfo clock = null;
        List<ClockInfo> clocks = mClockInfosProvider.get();
        for (int i = 0; i < clocks.size(); i++) {
            if (id.equals(clocks.get(i).getId())) {
                clock = clocks.get(i);
                break;
            }
        }
        if (clock == null) {
            throw new FileNotFoundException("Invalid preview url, id not found");
        }
        return openPipeHelper(uri, MIME_TYPE_PNG, null, KEY_PREVIEW.equals(segments.get(0))
                ? clock.getPreview() : clock.getThumbnail(), new MyWriter());
    }

    private Uri createThumbnailUri(ClockInfo clock) {
        return new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(KEY_THUMBNAIL)
                .appendPath(clock.getId())
                .build();
    }

    private Uri createPreviewUri(ClockInfo clock) {
        return new Uri.Builder()
                .scheme(CONTENT_SCHEME)
                .authority(AUTHORITY)
                .appendPath(KEY_PREVIEW)
                .appendPath(clock.getId())
                .build();
    }

    private static class MyWriter implements ContentProvider.PipeDataWriter<Bitmap> {
        @Override
        public void writeDataToPipe(ParcelFileDescriptor output, Uri uri, String mimeType,
                Bundle opts, Bitmap bitmap) {
            try (AutoCloseOutputStream os = new AutoCloseOutputStream(output)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            } catch (Exception e) {
                Log.w(TAG, "fail to write to pipe", e);
            }
        }
    }
}
