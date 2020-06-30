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
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.textclassifier.IconsUriHelper.ResourceInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

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
    private static final String MIME_TYPE = "image/png";

    private final PipeDataWriter<Pair<ResourceInfo, Integer>> mWriter =
            (writeSide, uri, mimeType, bundle, args) -> {
                try (OutputStream out = new AutoCloseOutputStream(writeSide)) {
                    final ResourceInfo res = args.first;
                    final int userId = args.second;
                    final Drawable drawable = Icon.createWithResource(res.packageName, res.id)
                                .loadDrawableAsUser(getContext(), userId);
                    getBitmap(drawable).compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception e) {
                    Log.e(TAG, "Error retrieving icon for uri: " + uri, e);
                }
            };

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        final ResourceInfo res = IconsUriHelper.getInstance().getResourceInfo(uri);
        if (res == null) {
            Log.e(TAG, "No icon found for uri: " + uri);
            return null;
        }

        try {
            final Pair<ResourceInfo, Integer> args = new Pair(res, UserHandle.getCallingUserId());
            return openPipeHelper(uri, MIME_TYPE, /* bundle= */ null, args, mWriter);
        } catch (IOException e) {
            Log.e(TAG, "Error opening pipe helper for icon at uri: " + uri, e);
        }

        return null;
    }

    private static Bitmap getBitmap(Drawable drawable) {
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

        return bitmap;
    }

    /**
     * Returns true if the drawables are considered the same.
     */
    @VisibleForTesting
    public static boolean sameIcon(Drawable one, Drawable two) {
        final ByteArrayOutputStream stream1 = new ByteArrayOutputStream();
        getBitmap(one).compress(Bitmap.CompressFormat.PNG, 100, stream1);
        final ByteArrayOutputStream stream2 = new ByteArrayOutputStream();
        getBitmap(two).compress(Bitmap.CompressFormat.PNG, 100, stream2);
        return Arrays.equals(stream1.toByteArray(), stream2.toByteArray());
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
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
