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
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.util.ArrayList;

/**
 * Wraps a notification view inflated from a template.
 */
public class NotificationTemplateViewWrapper extends NotificationViewWrapper {

    private final ColorMatrix mGrayscaleColorMatrix = new ColorMatrix();
    private final PorterDuffColorFilter mIconColorFilter = new PorterDuffColorFilter(
            0, PorterDuff.Mode.SRC_ATOP);
    private final int mIconDarkAlpha;
    private final int mIconDarkColor;
    private final Interpolator mLinearOutSlowInInterpolator;

    private int mColor;
    private ViewInvertHelper mInvertHelper;
    private ImageView mIcon;
    protected ImageView mPicture;

    /**
     * Whether the icon needs to be forced grayscale when in dark mode.
     */
    private boolean mIconForceGraysaleWhenDark;
    private TextView mSubText;
    private View mSubTextDivider;
    private ImageView mExpandButton;
    private View mNotificationHeader;
    private View.OnClickListener mExpandClickListener;
    private HeaderTouchListener mHeaderTouchListener;

    protected NotificationTemplateViewWrapper(Context ctx, View view) {
        super(view);
        mIconDarkAlpha = ctx.getResources().getInteger(R.integer.doze_small_icon_alpha);
        mIconDarkColor =
                ctx.getColor(R.color.doze_small_icon_background_color);
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(ctx,
                android.R.interpolator.linear_out_slow_in);
        resolveViews();
    }

    private void resolveViews() {
        View mainColumn = mView.findViewById(com.android.internal.R.id.notification_main_column);
        mInvertHelper = mainColumn != null
                ? new ViewInvertHelper(mainColumn, NotificationPanelView.DOZE_ANIMATION_DURATION)
                : null;
        mIcon = (ImageView) mView.findViewById(com.android.internal.R.id.icon);
        mPicture = (ImageView) mView.findViewById(com.android.internal.R.id.right_icon);
        mSubText = (TextView) mView.findViewById(com.android.internal.R.id.header_sub_text);
        mSubTextDivider = mView.findViewById(com.android.internal.R.id.sub_text_divider);
        mExpandButton = (ImageView) mView.findViewById(com.android.internal.R.id.expand_button);
        mColor = resolveColor(mExpandButton);
        mNotificationHeader = mView.findViewById(com.android.internal.R.id.notification_header);
        // Post to make sure the parent lays out its children before we get their bounds
        mHeaderTouchListener = new HeaderTouchListener();
        mExpandButton.post(new Runnable() {
            @Override
            public void run() {
                // let's set up our touch regions
                mHeaderTouchListener.bindTouchRects(mNotificationHeader, mIcon, mExpandButton);
            }
        });

        // If the icon already has a color filter, we assume that we already forced the icon to be
        // white when we created the notification.
        final Drawable iconDrawable = mIcon != null ? mIcon.getDrawable() : null;
        mIconForceGraysaleWhenDark = iconDrawable != null && iconDrawable.getColorFilter() != null;
    }

    private int resolveColor(ImageView icon) {
        if (icon != null && icon.getDrawable() != null) {
            ColorFilter filter = icon.getDrawable().getColorFilter();
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
        int color = interpolateColor(mColor, mIconDarkColor, intensity);
        mIconColorFilter.setColor(color);
        Drawable iconDrawable = target.getDrawable();

        // The background might be null for legacy notifications. Also, the notification might have
        // been modified during the animation, so background might be null here.
        if (iconDrawable != null) {
            iconDrawable.mutate().setColorFilter(mIconColorFilter);
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

    @Override
    public void setSubTextVisible(boolean visible) {
        if (mSubText == null) {
            return;
        }
        boolean subTextAvailable = !TextUtils.isEmpty(mSubText.getText());
        if (visible && subTextAvailable) {
            mSubText.setVisibility(View.VISIBLE);
            mSubTextDivider.setVisibility(View.VISIBLE);
        } else {
            mSubText.setVisibility(View.GONE);
            mSubTextDivider.setVisibility(View.GONE);
        }
    }

    @Override
    public void updateExpandability(boolean expandable, View.OnClickListener onClickListener) {
        mExpandButton.setVisibility(expandable ? View.VISIBLE : View.GONE);
        mNotificationHeader.setOnTouchListener(expandable ? mHeaderTouchListener : null);
        mExpandClickListener = onClickListener;
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

    public class HeaderTouchListener implements View.OnTouchListener {

        private final ArrayList<Rect> mTouchRects = new ArrayList<>();
        private int mTouchSlop;
        private boolean mTrackGesture;
        private float mDownX;
        private float mDownY;

        public HeaderTouchListener() {
        }

        public void bindTouchRects(View parent, View icon, View expandButton) {
            mTouchRects.clear();
            addRectAroundViewView(icon);
            addRectAroundViewView(expandButton);
            addInBetweenRect(parent);
            mTouchSlop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
        }

        private void addInBetweenRect(View parent) {
            final Rect r = new Rect();
            r.top = 0;
            r.bottom = (int) (32 * parent.getResources().getDisplayMetrics().density);
            Rect leftRect = mTouchRects.get(0);
            r.left = leftRect.right;
            Rect rightRect = mTouchRects.get(1);
            r.right = rightRect.left;
            mTouchRects.add(r);
        }

        private void addRectAroundViewView(View view) {
            final Rect r = getRectAroundView(view);
            mTouchRects.add(r);
        }

        private Rect getRectAroundView(View view) {
            float size = 48 * view.getResources().getDisplayMetrics().density;
            final Rect r = new Rect();
            r.top = (int) ((view.getTop() + view.getBottom()) / 2.0f - size / 2.0f);
            r.bottom = (int) (r.top + size);
            r.left = (int) ((view.getLeft() + view.getRight()) / 2.0f - size / 2.0f);
            r.right = (int) (r.left + size);
            return r;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    mTrackGesture = false;
                    if (isInside(x, y)) {
                        mTrackGesture = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mTrackGesture) {
                        if (Math.abs(mDownX - x) > mTouchSlop
                                || Math.abs(mDownY - y) > mTouchSlop) {
                            mTrackGesture = false;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (mTrackGesture) {
                        mExpandClickListener.onClick(mNotificationHeader);
                    }
                    break;
            }
            return mTrackGesture;
        }

        private boolean isInside(float x, float y) {
            for (int i = 0; i < mTouchRects.size(); i++) {
                Rect r = mTouchRects.get(i);
                if (r.contains((int) x, (int) y)) {
                    mDownX = x;
                    mDownY = y;
                    return true;
                }
            }
            return false;
        }
    }
}
