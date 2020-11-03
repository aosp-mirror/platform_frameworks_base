/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * A class to extract Bitmaps from a MessagingStyle message.
 */
public class LocalImageResolver {
    private static final String TAG = LocalImageResolver.class.getSimpleName();

    private static final int MAX_SAFE_ICON_SIZE_PX = 480;

    @Nullable
    public static Drawable resolveImage(Uri uri, Context context) throws IOException {
        BitmapFactory.Options onlyBoundsOptions = getBoundsOptionsForImage(uri, context);
        if ((onlyBoundsOptions.outWidth == -1) || (onlyBoundsOptions.outHeight == -1)) {
            return null;
        }

        int originalSize =
                (onlyBoundsOptions.outHeight > onlyBoundsOptions.outWidth)
                        ? onlyBoundsOptions.outHeight
                        : onlyBoundsOptions.outWidth;

        double ratio = (originalSize > MAX_SAFE_ICON_SIZE_PX)
                        ? (originalSize / MAX_SAFE_ICON_SIZE_PX)
                        : 1.0;

        BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
        bitmapOptions.inSampleSize = getPowerOfTwoForSampleRatio(ratio);
        InputStream input = context.getContentResolver().openInputStream(uri);
        Bitmap bitmap = BitmapFactory.decodeStream(input, null, bitmapOptions);
        input.close();
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    private static BitmapFactory.Options getBoundsOptionsForImage(Uri uri, Context context)
            throws IOException {
        BitmapFactory.Options onlyBoundsOptions = new BitmapFactory.Options();
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IllegalArgumentException();
            }
            onlyBoundsOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, onlyBoundsOptions);
        } catch (IllegalArgumentException iae) {
            onlyBoundsOptions.outWidth = -1;
            onlyBoundsOptions.outHeight = -1;
            Log.e(TAG, "error loading image", iae);
        }
        return onlyBoundsOptions;
    }

    private static int getPowerOfTwoForSampleRatio(double ratio) {
        int k = Integer.highestOneBit((int) Math.floor(ratio));
        return Math.max(1, k);
    }
}
