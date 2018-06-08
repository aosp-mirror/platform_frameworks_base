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

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;

import com.android.internal.util.ArrayUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;

/**
 * A view that can be used for both the dimmed and normal background of an notification.
 */
public class NotificationBackgroundView extends View {

    private final boolean mDontModifyCorners;
    private Drawable mBackground;
    private int mClipTopAmount;
    private int mActualHeight;
    private int mClipBottomAmount;
    private int mTintColor;
    private float[] mCornerRadii = new float[8];
    private boolean mBottomIsRounded;
    private int mBackgroundTop;
    private boolean mBottomAmountClips = true;
    private boolean mExpandAnimationRunning;
    private float mActualWidth;
    private int mDrawableAlpha = 255;
    private boolean mIsPressedAllowed;

    private boolean mTopAmountRounded;
    private float mDistanceToTopRoundness;

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDontModifyCorners = getResources().getBoolean(
                R.bool.config_clipNotificationsToOutline);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mClipTopAmount + mClipBottomAmount < mActualHeight - mBackgroundTop
                || mExpandAnimationRunning) {
            canvas.save();
            if (!mExpandAnimationRunning) {
                canvas.clipRect(0, mClipTopAmount, getWidth(), mActualHeight - mClipBottomAmount);
            }
            draw(canvas, mBackground);
            canvas.restore();
        }
    }

    private void draw(Canvas canvas, Drawable drawable) {
        if (drawable != null) {
            int top = mBackgroundTop;
            int bottom = mActualHeight;
            if (mBottomIsRounded && mBottomAmountClips && !mExpandAnimationRunning) {
                bottom -= mClipBottomAmount;
            }
            int left = 0;
            int right = getWidth();
            if (mExpandAnimationRunning) {
                left = (int) ((getWidth() - mActualWidth) / 2.0f);
                right = (int) (left + mActualWidth);
            }
            if (mTopAmountRounded) {
                int clipTop = (int) (mClipTopAmount - mDistanceToTopRoundness);
                top += clipTop;
                if (clipTop >= 0) {
                    bottom += clipTop;
                }
            }
            drawable.setBounds(left, top, right, bottom);
            drawable.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        setState(getDrawableState());
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    /**
     * Sets a background drawable. As we need to change our bounds independently of layout, we need
     * the notion of a background independently of the regular View background..
     */
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        mBackground.mutate();
        if (mBackground != null) {
            mBackground.setCallback(this);
            setTint(mTintColor);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        updateBackgroundRadii();
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    public void setTint(int tintColor) {
        if (tintColor != 0) {
            mBackground.setColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP);
        } else {
            mBackground.clearColorFilter();
        }
        mTintColor = tintColor;
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        if (mExpandAnimationRunning) {
            return;
        }
        mActualHeight = actualHeight;
        invalidate();
    }

    public int getActualHeight() {
        return mActualHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    public void setDistanceToTopRoundness(float distanceToTopRoundness) {
        if (distanceToTopRoundness != mDistanceToTopRoundness) {
            mTopAmountRounded = distanceToTopRoundness >= 0;
            mDistanceToTopRoundness = distanceToTopRoundness;
            invalidate();
        }
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        if (mBackground != null && mBackground.isStateful()) {
            if (!mIsPressedAllowed) {
                drawableState = ArrayUtils.removeInt(drawableState,
                        com.android.internal.R.attr.state_pressed);
            }
            mBackground.setState(drawableState);
        }
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
        }
    }

    public void setDrawableAlpha(int drawableAlpha) {
        mDrawableAlpha = drawableAlpha;
        if (mExpandAnimationRunning) {
            return;
        }
        mBackground.setAlpha(drawableAlpha);
    }

    public void setRoundness(float topRoundness, float bottomRoundNess) {
        if (topRoundness == mCornerRadii[0] && bottomRoundNess == mCornerRadii[4]) {
            return;
        }
        mBottomIsRounded = bottomRoundNess != 0.0f;
        mCornerRadii[0] = topRoundness;
        mCornerRadii[1] = topRoundness;
        mCornerRadii[2] = topRoundness;
        mCornerRadii[3] = topRoundness;
        mCornerRadii[4] = bottomRoundNess;
        mCornerRadii[5] = bottomRoundNess;
        mCornerRadii[6] = bottomRoundNess;
        mCornerRadii[7] = bottomRoundNess;
        updateBackgroundRadii();
    }

    public void setBottomAmountClips(boolean clips) {
        if (clips != mBottomAmountClips) {
            mBottomAmountClips = clips;
            invalidate();
        }
    }

    private void updateBackgroundRadii() {
        if (mDontModifyCorners) {
            return;
        }
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            gradientDrawable.setCornerRadii(mCornerRadii);
        }
    }

    public void setBackgroundTop(int backgroundTop) {
        mBackgroundTop = backgroundTop;
        invalidate();
    }

    public void setExpandAnimationParams(ActivityLaunchAnimator.ExpandAnimationParameters params) {
        mActualHeight = params.getHeight();
        mActualWidth = params.getWidth();
        float alphaProgress = Interpolators.ALPHA_IN.getInterpolation(
                params.getProgress(
                        ActivityLaunchAnimator.ANIMATION_DURATION_FADE_CONTENT /* delay */,
                        ActivityLaunchAnimator.ANIMATION_DURATION_FADE_APP /* duration */));
        mBackground.setAlpha((int) (mDrawableAlpha * (1.0f - alphaProgress)));
        invalidate();
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            gradientDrawable.setXfermode(
                    running ? new PorterDuffXfermode(PorterDuff.Mode.SRC) : null);
            // Speed optimization: disable AA if transfer mode is not SRC_OVER. AA is not easy to
            // spot during animation anyways.
            gradientDrawable.setAntiAlias(!running);
        }
        if (!mExpandAnimationRunning) {
            setDrawableAlpha(mDrawableAlpha);
        }
        invalidate();
    }

    public void setPressedAllowed(boolean allowed) {
        mIsPressedAllowed = allowed;
    }
}
