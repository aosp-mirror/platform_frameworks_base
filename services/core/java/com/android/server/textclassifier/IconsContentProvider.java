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
package com.android.server.textclassifier;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.textclassifier.IconsUriHelper.ResourceInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A content provider that is used to access icons returned from the TextClassifier service.
 *
 * <p>Use {@link IconsUriHelper#getContentUri(String, int)} to access a uri for a specific resource.
 * The uri may be passed to other processes to access the specified resource.
 *
 * <p>NOTE: Care must be taken to avoid leaking resources to non-permitted apps via this provider.
 */
public final class IconsContentProvider extends ContentProvider {

    private static final String TAG = "IconsContentProvider";

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        try {
            final ResourceInfo res = IconsUriHelper.getInstance().getResourceInfo(uri);
            final Drawable drawable = Icon.createWithResource(res.packageName, res.id)
                    .loadDrawableAsUser(getContext(), UserHandle.getCallingUserId());
            final byte[] data = getBitmapData(drawable);
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readSide = pipe[0];
            final ParcelFileDescriptor writeSide = pipe[1];
            try (OutputStream out = new AutoCloseOutputStream(writeSide)) {
                out.write(data);
                return readSide;
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Error retrieving icon for uri: " + uri, e);
        }
        return null;
    }

    /**
     * Returns the bitmap data for the specified drawable.
     */
    @VisibleForTesting
    public static byte[] getBitmapData(Drawable drawable) {
        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            throw new IllegalStateException("The icon is zero-sized");
        }

        final Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);

        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        final byte[] byteArray = stream.toByteArray();
        bitmap.recycle();
        return byteArray;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
