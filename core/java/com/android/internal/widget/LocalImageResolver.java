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
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Size;

import java.io.IOException;

/** A class to extract Drawables from a MessagingStyle/ConversationStyle message. */
public class LocalImageResolver {
    private static final String TAG = LocalImageResolver.class.getSimpleName();

    private static final int MAX_SAFE_ICON_SIZE_PX = 480;

    /**
     * Resolve an image from the given Uri using {@link ImageDecoder}
     */
    public static Drawable resolveImage(Uri uri, Context context) throws IOException {
        final ImageDecoder.Source source =
                ImageDecoder.createSource(context.getContentResolver(), uri);
        final Drawable drawable =
                ImageDecoder.decodeDrawable(source, LocalImageResolver::onHeaderDecoded);
        return drawable;
    }

    /**
     * Get the drawable from Icon using {@link ImageDecoder} if it contains a Uri, or
     * using {@link Icon#loadDrawable(Context)} otherwise.  This will correctly apply the Icon's,
     * tint, if present, to the drawable.
     */
    public static Drawable resolveImage(Icon icon, Context context) throws IOException {
        Uri uri = getResolvableUri(icon);
        if (uri != null) {
            Drawable result = resolveImage(uri, context);
            if (icon.hasTint()) {
                result.mutate();
                result.setTintList(icon.getTintList());
                result.setTintBlendMode(icon.getTintBlendMode());
            }
            return result;
        }
        return icon.loadDrawable(context);
    }

    public static Drawable resolveImage(Uri uri, Context context, int maxWidth, int maxHeight)
            throws IOException {
        final ImageDecoder.Source source =
                ImageDecoder.createSource(context.getContentResolver(), uri);
        return ImageDecoder.decodeDrawable(source, (decoder, info, unused) -> {
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
    }

    private static int getPowerOfTwoForSampleRatio(double ratio) {
        final int k = Integer.highestOneBit((int) Math.floor(ratio));
        return Math.max(1, k);
    }

    private static void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
            ImageDecoder.Source source) {
        final Size size = info.getSize();
        final int originalSize = Math.max(size.getHeight(), size.getWidth());
        final double ratio = (originalSize > MAX_SAFE_ICON_SIZE_PX)
                ? originalSize * 1f / MAX_SAFE_ICON_SIZE_PX
                : 1.0;
        decoder.setTargetSampleSize(getPowerOfTwoForSampleRatio(ratio));
    }

    /**
     * Gets the Uri for this icon, assuming the icon can be treated as a pure Uri.  Null otherwise.
     */
    @Nullable
    public static Uri getResolvableUri(@Nullable Icon icon) {
        if (icon == null || (icon.getType() != Icon.TYPE_URI
                && icon.getType() != Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
            return null;
        }
        return icon.getUri();
    }
}
