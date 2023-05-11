/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.datatransfer.contextsync;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;

/** Provides bitmap utility operations for rendering drawables to byte arrays. */
public class BitmapUtils {

    private static final int APP_ICON_BITMAP_DIMENSION = 256;

    /** Render a drawable to a bitmap, which is then reformatted as a byte array. */
    public static byte[] renderDrawableToByteArray(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            // Can't recycle the drawable's bitmap, so handle separately
            final Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap.getWidth() > APP_ICON_BITMAP_DIMENSION
                    || bitmap.getHeight() > APP_ICON_BITMAP_DIMENSION) {
                // Downscale, as the original drawable bitmap is too large.
                final Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap,
                        APP_ICON_BITMAP_DIMENSION, APP_ICON_BITMAP_DIMENSION, /* filter= */ true);
                final byte[] renderedBitmap = renderBitmapToByteArray(scaledBitmap);
                scaledBitmap.recycle();
                return renderedBitmap;
            }
            return renderBitmapToByteArray(bitmap);
        }
        final Bitmap bitmap = Bitmap.createBitmap(APP_ICON_BITMAP_DIMENSION,
                APP_ICON_BITMAP_DIMENSION,
                Bitmap.Config.ARGB_8888);
        try {
            final Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            drawable.draw(canvas);
            return renderBitmapToByteArray(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] renderBitmapToByteArray(Bitmap bitmap) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(bitmap.getByteCount());
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        return baos.toByteArray();
    }
}
