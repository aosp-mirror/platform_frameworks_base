/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.android.systemui.R;

public class NotificationIconDozeHelper extends NotificationDozeHelper {

    private final int mImageDarkAlpha;
    private final int mImageDarkColor = 0xffffffff;
    private final PorterDuffColorFilter mImageColorFilter = new PorterDuffColorFilter(
            0, PorterDuff.Mode.SRC_ATOP);

    private int mColor = Color.BLACK;

    public NotificationIconDozeHelper(Context ctx) {
        mImageDarkAlpha = ctx.getResources().getInteger(R.integer.doze_small_icon_alpha);
    }

    public void setColor(int color) {
        mColor = color;
    }

    public void setImageDark(ImageView target, boolean dark, boolean fade, long delay,
            boolean useGrayscale) {
        if (fade) {
            if (!useGrayscale) {
                fadeImageColorFilter(target, dark, delay);
                fadeImageAlpha(target, dark, delay);
            } else {
                fadeGrayscale(target, dark, delay);
            }
        } else {
            if (!useGrayscale) {
                updateImageColorFilter(target, dark);
                updateImageAlpha(target, dark);
            } else {
                updateGrayscale(target, dark);
            }
        }
    }

    private void fadeImageColorFilter(final ImageView target, boolean dark, long delay) {
        startIntensityAnimation(animation -> {
            updateImageColorFilter(target, (Float) animation.getAnimatedValue());
        }, dark, delay, null /* listener */);
    }

    private void fadeImageAlpha(final ImageView target, boolean dark, long delay) {
        startIntensityAnimation(animation -> {
            float t = (float) animation.getAnimatedValue();
            target.setImageAlpha((int) (255 * (1f - t) + mImageDarkAlpha * t));
        }, dark, delay, null /* listener */);
    }

    private void updateImageColorFilter(ImageView target, boolean dark) {
        updateImageColorFilter(target, dark ? 1f : 0f);
    }

    private void updateImageColorFilter(ImageView target, float intensity) {
        int color = NotificationUtils.interpolateColors(mColor, mImageDarkColor, intensity);
        mImageColorFilter.setColor(color);
        Drawable imageDrawable = target.getDrawable();

        // Also, the notification might have been modified during the animation, so background
        // might be null here.
        if (imageDrawable != null) {
            Drawable d = imageDrawable.mutate();
            // DrawableContainer ignores the color filter if it's already set, so clear it first to
            // get it set and invalidated properly.
            d.setColorFilter(null);
            d.setColorFilter(mImageColorFilter);
        }
    }

    private void updateImageAlpha(ImageView target, boolean dark) {
        target.setImageAlpha(dark ? mImageDarkAlpha : 255);
    }

}
