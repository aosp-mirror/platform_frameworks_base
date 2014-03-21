/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TextAppearanceSpan;
import android.util.Log;
import android.util.Pair;

import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Helper class to process legacy (Holo) notifications to make them look like quantum notifications.
 *
 * @hide
 */
public class LegacyNotificationUtil {

    private static final String TAG = "LegacyNotificationUtil";

    private static final Object sLock = new Object();
    private static LegacyNotificationUtil sInstance;

    private final ImageUtils mImageUtils = new ImageUtils();
    private final WeakHashMap<Bitmap, Pair<Boolean, Integer>> mGrayscaleBitmapCache =
            new WeakHashMap<Bitmap, Pair<Boolean, Integer>>();

    public static LegacyNotificationUtil getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new LegacyNotificationUtil();
            }
            return sInstance;
        }
    }

    /**
     * Checks whether a bitmap is grayscale. Grayscale here means "very close to a perfect
     * gray".
     *
     * @param bitmap The bitmap to test.
     * @return Whether the bitmap is grayscale.
     */
    public boolean isGrayscale(Bitmap bitmap) {
        synchronized (sLock) {
            Pair<Boolean, Integer> cached = mGrayscaleBitmapCache.get(bitmap);
            if (cached != null) {
                if (cached.second == bitmap.getGenerationId()) {
                    return cached.first;
                }
            }
        }
        boolean result;
        int generationId;
        synchronized (mImageUtils) {
            result = mImageUtils.isGrayscale(bitmap);

            // generationId and the check whether the Bitmap is grayscale can't be read atomically
            // here. However, since the thread is in the process of posting the notification, we can
            // assume that it doesn't modify the bitmap while we are checking the pixels.
            generationId = bitmap.getGenerationId();
        }
        synchronized (sLock) {
            mGrayscaleBitmapCache.put(bitmap, Pair.create(result, generationId));
        }
        return result;
    }

    /**
     * Checks whether a drawable is grayscale. Grayscale here means "very close to a perfect
     * gray".
     *
     * @param d The drawable to test.
     * @return Whether the drawable is grayscale.
     */
    public boolean isGrayscale(Drawable d) {
        if (d == null) {
            return false;
        } else if (d instanceof BitmapDrawable) {
            BitmapDrawable bd = (BitmapDrawable) d;
            return bd.getBitmap() != null && isGrayscale(bd.getBitmap());
        } else if (d instanceof AnimationDrawable) {
            AnimationDrawable ad = (AnimationDrawable) d;
            int count = ad.getNumberOfFrames();
            return count > 0 && isGrayscale(ad.getFrame(0));
        } else {
            return false;
        }
    }

    /**
     * Checks whether a drawable with a resoure id is grayscale. Grayscale here means "very close
     * to a perfect gray".
     *
     * @param context The context to load the drawable from.
     * @return Whether the drawable is grayscale.
     */
    public boolean isGrayscale(Context context, int drawableResId) {
        if (drawableResId != 0) {
            try {
                return isGrayscale(context.getDrawable(drawableResId));
            } catch (Resources.NotFoundException ex) {
                Log.e(TAG, "Drawable not found: " + drawableResId);
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * Inverts all the grayscale colors set by {@link android.text.style.TextAppearanceSpan}s on
     * the text.
     *
     * @param charSequence The text to process.
     * @return The color inverted text.
     */
    public CharSequence invertCharSequenceColors(CharSequence charSequence) {
        if (charSequence instanceof Spanned) {
            Spanned ss = (Spanned) charSequence;
            Object[] spans = ss.getSpans(0, ss.length(), Object.class);
            SpannableStringBuilder builder = new SpannableStringBuilder(ss.toString());
            for (Object span : spans) {
                Object resultSpan = span;
                if (span instanceof TextAppearanceSpan) {
                    resultSpan = processTextAppearanceSpan((TextAppearanceSpan) span);
                }
                builder.setSpan(resultSpan, ss.getSpanStart(span), ss.getSpanEnd(span),
                        ss.getSpanFlags(span));
            }
            return builder;
        }
        return charSequence;
    }

    private TextAppearanceSpan processTextAppearanceSpan(TextAppearanceSpan span) {
        ColorStateList colorStateList = span.getTextColor();
        if (colorStateList != null) {
            int[] colors = colorStateList.getColors();
            boolean changed = false;
            for (int i = 0; i < colors.length; i++) {
                if (ImageUtils.isGrayscale(colors[i])) {

                    // Allocate a new array so we don't change the colors in the old color state
                    // list.
                    if (!changed) {
                        colors = Arrays.copyOf(colors, colors.length);
                    }
                    colors[i] = processColor(colors[i]);
                    changed = true;
                }
            }
            if (changed) {
                return new TextAppearanceSpan(
                        span.getFamily(), span.getTextStyle(), span.getTextSize(),
                        new ColorStateList(colorStateList.getStates(), colors),
                        span.getLinkTextColor());
            }
        }
        return span;
    }

    private int processColor(int color) {
        return Color.argb(Color.alpha(color),
                255 - Color.red(color),
                255 - Color.green(color),
                255 - Color.blue(color));
    }
}
