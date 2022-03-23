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

import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Log;
import android.util.Size;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;

/** A class to extract Drawables from a MessagingStyle/ConversationStyle message. */
public class LocalImageResolver {

    private static final String TAG = "LocalImageResolver";

    @VisibleForTesting
    static final int DEFAULT_MAX_SAFE_ICON_SIZE_PX = 480;

    /**
     * Resolve an image from the given Uri using {@link ImageDecoder} if it contains a
     * bitmap reference.
     */
    @Nullable
    public static Drawable resolveImage(Uri uri, Context context) throws IOException {
        try {
            final ImageDecoder.Source source =
                    ImageDecoder.createSource(context.getContentResolver(), uri);
            return ImageDecoder.decodeDrawable(source,
                    (decoder, info, s) -> LocalImageResolver.onHeaderDecoded(decoder, info,
                            DEFAULT_MAX_SAFE_ICON_SIZE_PX, DEFAULT_MAX_SAFE_ICON_SIZE_PX));
        } catch (Exception e) {
            // Invalid drawable resource can actually throw either NullPointerException or
            // ResourceNotFoundException. This sanitizes to expected output.
            throw new IOException(e);
        }
    }

    /**
     * Get the drawable from Icon using {@link ImageDecoder} if it contains a bitmap reference, or
     * using {@link Icon#loadDrawable(Context)} otherwise.  This will correctly apply the Icon's,
     * tint, if present, to the drawable.
     *
     * @return drawable or null if loading failed.
     */
    @Nullable
    public static Drawable resolveImage(@Nullable Icon icon, Context context) throws IOException {
        return resolveImage(icon, context, DEFAULT_MAX_SAFE_ICON_SIZE_PX,
                DEFAULT_MAX_SAFE_ICON_SIZE_PX);
    }

    /**
     * Get the drawable from Icon using {@link ImageDecoder} if it contains a bitmap reference, or
     * using {@link Icon#loadDrawable(Context)} otherwise.  This will correctly apply the Icon's,
     * tint, if present, to the drawable.
     *
     * @throws IOException if the icon could not be loaded for whichever reason
     */
    @Nullable
    public static Drawable resolveImage(@Nullable Icon icon, Context context, int maxWidth,
            int maxHeight) {
        if (icon == null) {
            return null;
        }

        switch (icon.getType()) {
            case Icon.TYPE_URI:
            case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                Uri uri = getResolvableUri(icon);
                if (uri != null) {
                    Drawable result = resolveImage(uri, context, maxWidth, maxHeight);
                    if (result != null) {
                        return tintDrawable(icon, result);
                    }
                }
                break;
            case Icon.TYPE_RESOURCE:
                Drawable result = resolveImage(icon.getResId(), context, maxWidth, maxHeight);
                if (result != null) {
                    return tintDrawable(icon, result);
                }
                break;
            case Icon.TYPE_BITMAP:
            case Icon.TYPE_ADAPTIVE_BITMAP:
                return resolveBitmapImage(icon, context, maxWidth, maxHeight);
            case Icon.TYPE_DATA:    // We can't really improve on raw data images.
            default:
                break;
        }

        // Fallback to straight drawable load if we fail with more efficient approach.
        try {
            return icon.loadDrawable(context);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    /**
     * Attempts to resolve the resource as a bitmap drawable constrained within max sizes.
     */
    @Nullable
    public static Drawable resolveImage(Uri uri, Context context, int maxWidth, int maxHeight) {
        final ImageDecoder.Source source =
                ImageDecoder.createSource(context.getContentResolver(), uri);
        return resolveImage(source, maxWidth, maxHeight);
    }

    /**
     * Attempts to resolve the resource as a bitmap drawable constrained within max sizes.
     *
     * @return decoded drawable or null if the passed resource is not a straight bitmap
     */
    @Nullable
    public static Drawable resolveImage(@DrawableRes int resId, Context context, int maxWidth,
            int maxHeight) {
        final ImageDecoder.Source source = ImageDecoder.createSource(context.getResources(), resId);
        return resolveImage(source, maxWidth, maxHeight);
    }

    @Nullable
    private static Drawable resolveBitmapImage(Icon icon, Context context, int maxWidth,
            int maxHeight) {
        Bitmap bitmap = icon.getBitmap();
        if (bitmap == null) {
            return null;
        }

        if (bitmap.getWidth() > maxWidth || bitmap.getHeight() > maxHeight) {
            Icon smallerIcon = icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP
                    ? Icon.createWithAdaptiveBitmap(bitmap) : Icon.createWithBitmap(bitmap);
            // We don't want to modify the source icon, create a copy.
            smallerIcon.setTintList(icon.getTintList())
                    .setTintBlendMode(icon.getTintBlendMode())
                    .scaleDownIfNecessary(maxWidth, maxHeight);
            return smallerIcon.loadDrawable(context);
        }

        return icon.loadDrawable(context);
    }

    @Nullable
    private static Drawable tintDrawable(Icon icon, @Nullable Drawable drawable) {
        if (drawable == null) {
            return null;
        }

        if (icon.hasTint()) {
            drawable.mutate();
            drawable.setTintList(icon.getTintList());
            drawable.setTintBlendMode(icon.getTintBlendMode());
        }

        return drawable;
    }

    private static Drawable resolveImage(ImageDecoder.Source source, int maxWidth, int maxHeight) {
        try {
            return ImageDecoder.decodeDrawable(source, (decoder, info, unused) -> {
                if (maxWidth <= 0 || maxHeight <= 0) {
                    return;
                }

                final Size size = info.getSize();
                if (size.getWidth() > size.getHeight()) {
                    if (size.getWidth() > maxWidth) {
                        final int targetHeight = size.getHeight() * maxWidth / size.getWidth();
                        decoder.setTargetSize(maxWidth, targetHeight);
                    }
                } else {
                    if (size.getHeight() > maxHeight) {
                        final int targetWidth = size.getWidth() * maxHeight / size.getHeight();
                        decoder.setTargetSize(targetWidth, maxHeight);
                    }
                }
            });

        // ImageDecoder documentation is misleading a bit - it'll throw NotFoundException
        // in some cases despite it not saying so. Rethrow it as an IOException to keep
        // our API contract.
        } catch (IOException | Resources.NotFoundException e) {
            Log.e(TAG, "Failed to load image drawable", e);
            return null;
        }
    }

    private static int getPowerOfTwoForSampleRatio(double ratio) {
        final int k = Integer.highestOneBit((int) Math.floor(ratio));
        return Math.max(1, k);
    }

    private static void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
            int maxWidth, int maxHeight) {
        final Size size = info.getSize();
        final int originalSize = Math.max(size.getHeight(), size.getWidth());
        final int maxSize = Math.max(maxWidth, maxHeight);
        final double ratio = (originalSize > maxSize)
                ? originalSize * 1f / maxSize
                : 1.0;
        decoder.setTargetSampleSize(getPowerOfTwoForSampleRatio(ratio));
    }

    /**
     * Gets the Uri for this icon, assuming the icon can be treated as a pure Uri.  Null otherwise.
     */
    @Nullable
    private static Uri getResolvableUri(@Nullable Icon icon) {
        if (icon == null || (icon.getType() != Icon.TYPE_URI
                && icon.getType() != Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
            return null;
        }
        return icon.getUri();
    }
}
