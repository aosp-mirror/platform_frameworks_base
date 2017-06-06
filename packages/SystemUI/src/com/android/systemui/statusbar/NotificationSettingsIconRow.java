/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

public class NotificationSettingsIconRow extends FrameLayout implements View.OnClickListener {

    private static final int GEAR_ALPHA_ANIM_DURATION = 200;

    public interface SettingsIconRowListener {
        /**
         * Called when the gear behind a notification is touched.
         */
        public void onGearTouched(ExpandableNotificationRow row, int x, int y);

        /**
         * Called when a notification is slid back over the gear.
         */
        public void onSettingsIconRowReset(ExpandableNotificationRow row);
    }

    private ExpandableNotificationRow mParent;
    private AlphaOptimizedImageView mGearIcon;
    private float mHorizSpaceForGear;
    private SettingsIconRowListener mListener;

    private ValueAnimator mFadeAnimator;
    private boolean mSettingsFadedIn = false;
    private boolean mAnimating = false;
    private boolean mOnLeft = true;
    private boolean mDismissing = false;
    private boolean mSnapping = false;
    private boolean mIconPlaced = false;

    private int[] mGearLocation = new int[2];
    private int[] mParentLocation = new int[2];
    private int mVertSpaceForGear;

    public NotificationSettingsIconRow(Context context) {
        this(context, null);
    }

    public NotificationSettingsIconRow(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationSettingsIconRow(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationSettingsIconRow(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mGearIcon = (AlphaOptimizedImageView) findViewById(R.id.gear_icon);
        mGearIcon.setOnClickListener(this);
        setOnClickListener(this);
        mHorizSpaceForGear =
                getResources().getDimensionPixelOffset(R.dimen.notification_gear_width);
        mVertSpaceForGear = getResources().getDimensionPixelOffset(R.dimen.notification_min_height);
        resetState();
    }

    public void resetState() {
        setGearAlpha(0f);
        mIconPlaced = false;
        mSettingsFadedIn = false;
        mAnimating = false;
        mSnapping = false;
        mDismissing = false;
        setIconLocation(true /* on left */);
        if (mListener != null) {
            mListener.onSettingsIconRowReset(mParent);
        }
    }

    public void setGearListener(SettingsIconRowListener listener) {
        mListener = listener;
    }

    public void setNotificationRowParent(ExpandableNotificationRow parent) {
        mParent = parent;
        setIconLocation(mOnLeft);
    }

    public void setAppName(String appName) {
        Resources res = getResources();
        String description = String.format(res.getString(R.string.notification_gear_accessibility),
                appName);
        mGearIcon.setContentDescription(description);
    }

    public ExpandableNotificationRow getNotificationParent() {
        return mParent;
    }

    public void setGearAlpha(float alpha) {
        if (alpha == 0) {
            mSettingsFadedIn = false; // Can fade in again once it's gone.
            setVisibility(View.INVISIBLE);
        } else {
            setVisibility(View.VISIBLE);
        }
        mGearIcon.setAlpha(alpha);
    }

    /**
     * Returns whether the icon is on the left side of the view or not.
     */
    public boolean isIconOnLeft() {
        return mOnLeft;
    }

    /**
     * Returns the horizontal space in pixels required to display the gear behind a notification.
     */
    public float getSpaceForGear() {
        return mHorizSpaceForGear;
    }

    /**
     * Indicates whether the gear is visible at 1 alpha. Does not indicate
     * if entire view is visible.
     */
    public boolean isVisible() {
        return mGearIcon.getAlpha() > 0;
    }

    public void cancelFadeAnimator() {
        if (mFadeAnimator != null) {
            mFadeAnimator.cancel();
        }
    }

    public void updateSettingsIcons(final float transX, final float size) {
        if (mAnimating || !mSettingsFadedIn) {
            // Don't adjust when animating, or if the gear hasn't been shown yet.
            return;
        }

        final float fadeThreshold = size * 0.3f;
        final float absTrans = Math.abs(transX);
        float desiredAlpha = 0;

        if (absTrans == 0) {
            desiredAlpha = 0;
        } else if (absTrans <= fadeThreshold) {
            desiredAlpha = 1;
        } else {
            desiredAlpha = 1 - ((absTrans - fadeThreshold) / (size - fadeThreshold));
        }
        setGearAlpha(desiredAlpha);
    }

    public void fadeInSettings(final boolean fromLeft, final float transX,
            final float notiThreshold) {
        if (mDismissing || mAnimating) {
            return;
        }
        if (isIconLocationChange(transX)) {
            setGearAlpha(0f);
        }
        setIconLocation(transX > 0 /* fromLeft */);
        mFadeAnimator = ValueAnimator.ofFloat(mGearIcon.getAlpha(), 1);
        mFadeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final float absTrans = Math.abs(transX);

                boolean pastGear = (fromLeft && transX <= notiThreshold)
                        || (!fromLeft && absTrans <= notiThreshold);
                if (pastGear && !mSettingsFadedIn) {
                    setGearAlpha((float) animation.getAnimatedValue());
                }
            }
        });
        mFadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mAnimating = true;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // TODO should animate back to 0f from current alpha
                mGearIcon.setAlpha(0f);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimating = false;
                mSettingsFadedIn = mGearIcon.getAlpha() == 1;
            }
        });
        mFadeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mFadeAnimator.setDuration(GEAR_ALPHA_ANIM_DURATION);
        mFadeAnimator.start();
    }

    public void updateVerticalLocation() {
        if (mParent == null) {
            return;
        }
        int parentHeight = mParent.getCollapsedHeight();
        if (parentHeight < mVertSpaceForGear) {
            mGearIcon.setTranslationY((parentHeight / 2) - (mGearIcon.getHeight() / 2));
        } else {
            mGearIcon.setTranslationY((mVertSpaceForGear - mGearIcon.getHeight()) / 2);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mIconPlaced) {
            setIconLocation(mOnLeft, true /* force */);
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        if (mIconPlaced) {
            setIconLocation(mOnLeft, true /* force */);
        }
    }

    public void setIconLocation(boolean onLeft) {
        setIconLocation(onLeft, false /* force */);
    }

    private void setIconLocation(boolean onLeft, boolean force) {
        if (mParent == null || mGearIcon.getWidth() == 0
                || (!force && ((mIconPlaced && onLeft == mOnLeft) || mSnapping))) {
            // Do nothing
            return;
        }
        final boolean isRtl = mParent.isLayoutRtl();

        // TODO No need to cast to float here once b/28050538 is fixed.
        final float left = (float) (isRtl ? -(mParent.getWidth() - mHorizSpaceForGear) : 0);
        final float right = (float) (isRtl ? 0 : (mParent.getWidth() - mHorizSpaceForGear));
        final float centerX = ((mHorizSpaceForGear - mGearIcon.getWidth()) / 2);
        setTranslationX(onLeft ? left + centerX : right + centerX);
        mOnLeft = onLeft;
        mIconPlaced = true;
    }

    public boolean isIconLocationChange(float translation) {
        boolean onLeft = translation > mGearIcon.getPaddingStart();
        boolean onRight = translation < -mGearIcon.getPaddingStart();
        if ((mOnLeft && onRight) || (!mOnLeft && onLeft)) {
            return true;
        }
        return false;
    }

    public void setDismissing() {
        mDismissing = true;
    }

    public void setSnapping(boolean snapping) {
        mSnapping = snapping;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.gear_icon) {
            if (mListener != null) {
                mGearIcon.getLocationOnScreen(mGearLocation);
                mParent.getLocationOnScreen(mParentLocation);

                final int centerX = (int) (mHorizSpaceForGear / 2);
                final int centerY =
                        (int) (mGearIcon.getTranslationY() * 2 + mGearIcon.getHeight())/ 2;
                final int x = mGearLocation[0] - mParentLocation[0] + centerX;
                final int y = mGearLocation[1] - mParentLocation[1] + centerY;
                mListener.onGearTouched(mParent, x, y);
            }
        } else {
            // Do nothing when the background is touched.
        }
    }
}
