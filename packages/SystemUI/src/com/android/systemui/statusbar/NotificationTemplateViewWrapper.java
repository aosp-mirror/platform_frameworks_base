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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.phone.NotificationPanelView;

/**
 * Wraps a notification view inflated from a template.
 */
public class NotificationTemplateViewWrapper extends NotificationViewWrapper {

    private final ColorMatrix mGrayscaleColorMatrix = new ColorMatrix();
    private final PorterDuffColorFilter mIconColorFilter = new PorterDuffColorFilter(
            0, PorterDuff.Mode.SRC_ATOP);
    private final int mIconDarkAlpha;
    private final int mIconBackgroundDarkColor;
    private final Interpolator mLinearOutSlowInInterpolator;

    private int mIconBackgroundColor;
    private ViewInvertHelper mInvertHelper;
    private ImageView mIcon;
    protected ImageView mPicture;

    /** Whether the icon needs to be forced grayscale when in dark mode. */
    private boolean mIconForceGraysaleWhenDark;

    protected NotificationTemplateViewWrapper(Context ctx, View view) {
        super(view);
        mIconDarkAlpha = ctx.getResources().getInteger(R.integer.doze_small_icon_alpha);
        mIconBackgroundDarkColor =
                ctx.getResources().getColor(R.color.doze_small_icon_background_color);
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(ctx,
                android.R.interpolator.linear_out_slow_in);
        resolveViews();
    }

    private void resolveViews() {
        View mainColumn = mView.findViewById(com.android.internal.R.id.notification_main_column);
        mInvertHelper = mainColumn != null
                ? new ViewInvertHelper(mainColumn, NotificationPanelView.DOZE_ANIMATION_DURATION)
                : null;
        ImageView largeIcon = (ImageView) mView.findViewById(com.android.internal.R.id.icon);
        ImageView rightIcon = (ImageView) mView.findViewById(com.android.internal.R.id.right_icon);
        mIcon = resolveIcon(largeIcon, rightIcon);
        mPicture = resolvePicture(largeIcon);
        mIconBackgroundColor = resolveBackgroundColor(mIcon);

        // If the icon already has a color filter, we assume that we already forced the icon to be
        // white when we created the notification.
        mIconForceGraysaleWhenDark = mIcon != null && mIcon.getDrawable().getColorFilter() != null;
    }

    private ImageView resolveIcon(ImageView largeIcon, ImageView rightIcon) {
        return largeIcon != null && largeIcon.getBackground() != null ? largeIcon
                : rightIcon != null && rightIcon.getVisibility() == View.VISIBLE ? rightIcon
                : null;
    }

    private ImageView resolvePicture(ImageView largeIcon) {
        return largeIcon != null && largeIcon.getBackground() == null
                ? largeIcon
                : null;
    }

    private int resolveBackgroundColor(ImageView icon) {
        if (icon != null && icon.getBackground() != null) {
            ColorFilter filter = icon.getBackground().getColorFilter();
            if (filter instanceof PorterDuffColorFilter) {
                return ((PorterDuffColorFilter) filter).getColor();
            }
        }
        return 0;
    }

    @Override
    public void notifyContentUpdated() {
        super.notifyContentUpdated();

        // Reinspect the notification.
        resolveViews();
    }

    @Override
    public void setDark(boolean dark, boolean fade, long delay) {
        if (mInvertHelper != null) {
            if (fade) {
                mInvertHelper.fade(dark, delay);
            } else {
                mInvertHelper.update(dark);
            }
        }
        if (mIcon != null) {
            if (fade) {
                fadeIconColorFilter(mIcon, dark, delay);
                fadeIconAlpha(mIcon, dark, delay);
                if (!mIconForceGraysaleWhenDark) {
                    fadeGrayscale(mIcon, dark, delay);
                }
            } else {
                updateIconColorFilter(mIcon, dark);
                updateIconAlpha(mIcon, dark);
                if (!mIconForceGraysaleWhenDark) {
                    updateGrayscale(mIcon, dark);
                }
            }
        }
        setPictureGrayscale(dark, fade, delay);
    }

    protected void setPictureGrayscale(boolean grayscale, boolean fade, long delay) {
        if (mPicture != null) {
            if (fade) {
                fadeGrayscale(mPicture, grayscale, delay);
            } else {
                updateGrayscale(mPicture, grayscale);
            }
        }
    }

    private void startIntensityAnimation(ValueAnimator.AnimatorUpdateListener updateListener,
            boolean dark, long delay, Animator.AnimatorListener listener) {
        float startIntensity = dark ? 0f : 1f;
        float endIntensity = dark ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(startIntensity, endIntensity);
        animator.addUpdateListener(updateListener);
        animator.setDuration(NotificationPanelView.DOZE_ANIMATION_DURATION);
        animator.setInterpolator(mLinearOutSlowInInterpolator);
        animator.setStartDelay(delay);
        if (listener != null) {
            animator.addListener(listener);
        }
        animator.start();
    }

    private void fadeIconColorFilter(final ImageView target, boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateIconColorFilter(target, (Float) animation.getAnimatedValue());
            }
        }, dark, delay, null /* listener */);
    }

    private void fadeIconAlpha(final ImageView target, boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                target.setImageAlpha((int) (255 * (1f - t) + mIconDarkAlpha * t));
            }
        }, dark, delay, null /* listener */);
    }

    protected void fadeGrayscale(final ImageView target, final boolean dark, long delay) {
        startIntensityAnimation(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateGrayscaleMatrix((float) animation.getAnimatedValue());
                target.setColorFilter(new ColorMatrixColorFilter(mGrayscaleColorMatrix));
            }
        }, dark, delay, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!dark) {
                    target.setColorFilter(null);
                }
            }
        });
    }

    private void updateIconColorFilter(ImageView target, boolean dark) {
        updateIconColorFilter(target, dark ? 1f : 0f);
    }

    private void updateIconColorFilter(ImageView target, float intensity) {
        int color = interpolateColor(mIconBackgroundColor, mIconBackgroundDarkColor, intensity);
        mIconColorFilter.setColor(color);
        Drawable background = target.getBackground();

        // The background might be null for legacy notifications. Also, the notification might have
        // been modified during the animation, so background might be null here.
        if (background != null) {
            background.mutate().setColorFilter(mIconColorFilter);
        }
    }

    private void updateIconAlpha(ImageView target, boolean dark) {
        target.setImageAlpha(dark ? mIconDarkAlpha : 255);
    }

    protected void updateGrayscale(ImageView target, boolean dark) {
        if (dark) {
            updateGrayscaleMatrix(1f);
            target.setColorFilter(new ColorMatrixColorFilter(mGrayscaleColorMatrix));
        } else {
            target.setColorFilter(null);
        }
    }

    private void updateGrayscaleMatrix(float intensity) {
        mGrayscaleColorMatrix.setSaturation(1 - intensity);
    }

    private static int interpolateColor(int source, int target, float t) {
        int aSource = Color.alpha(source);
        int rSource = Color.red(source);
        int gSource = Color.green(source);
        int bSource = Color.blue(source);
        int aTarget = Color.alpha(target);
        int rTarget = Color.red(target);
        int gTarget = Color.green(target);
        int bTarget = Color.blue(target);
        return Color.argb(
                (int) (aSource * (1f - t) + aTarget * t),
                (int) (rSource * (1f - t) + rTarget * t),
                (int) (gSource * (1f - t) + gTarget * t),
                (int) (bSource * (1f - t) + bTarget * t));
    }
}
