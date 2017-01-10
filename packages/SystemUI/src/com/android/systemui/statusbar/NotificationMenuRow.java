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
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider.MenuItem;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider.OnMenuClickListener;

public class NotificationMenuRow extends FrameLayout
        implements PluginListener<NotificationMenuRowProvider>, View.OnClickListener {

    private static final int ICON_ALPHA_ANIM_DURATION = 200;

    private ExpandableNotificationRow mParent;
    private OnMenuClickListener mListener;
    private NotificationMenuRowProvider mMenuProvider;
    private ArrayList<MenuItem> mMenuItems = new ArrayList<>();

    private ValueAnimator mFadeAnimator;
    private boolean mMenuFadedIn = false;
    private boolean mAnimating = false;
    private boolean mOnLeft = true;
    private boolean mDismissing = false;
    private boolean mSnapping = false;
    private boolean mIconsPlaced = false;

    private int[] mIconLocation = new int[2];
    private int[] mParentLocation = new int[2];

    private float mHorizSpaceForIcon;
    private int mVertSpaceForIcons;

    private int mIconPadding;
    private int mIconTint;

    private float mAlpha = 0f;

    public NotificationMenuRow(Context context) {
        this(context, null);
    }

    public NotificationMenuRow(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationMenuRow(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationMenuRow(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs);
        PluginManager.getInstance(getContext()).addPluginListener(
                NotificationMenuRowProvider.ACTION, this,
                NotificationMenuRowProvider.VERSION, false /* Allow multiple */);
        mMenuItems.add(getSettingsMenuItem(context));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        final Resources res = getResources();
        mHorizSpaceForIcon = res.getDimensionPixelSize(R.dimen.notification_menu_icon_size);
        mVertSpaceForIcons = res.getDimensionPixelSize(R.dimen.notification_min_height);
        mIconPadding = res.getDimensionPixelSize(R.dimen.notification_menu_icon_padding);
        mIconTint = res.getColor(R.color.notification_gear_color);
        updateMenu(false /* notify */);
    }

    public static MenuItem getSettingsMenuItem(Context context) {
        Drawable d = context.getResources().getDrawable(R.drawable.ic_settings);
        String s = context.getResources().getString(R.string.notification_menu_gear_description);
        NotificationInfo content = (NotificationInfo) LayoutInflater.from(context).inflate(
                R.layout.notification_info, null, false);
        MenuItem settings = new MenuItem(d, s, content);
        return settings;
    }

    private void updateMenu(boolean notify) {
        removeAllViews();
        mMenuItems.clear();
        if (mMenuProvider != null) {
            mMenuItems.addAll(mMenuProvider.getMenuItems(getContext()));
        }
        mMenuItems.add(getSettingsMenuItem(getContext()));
        for (int i = 0; i < mMenuItems.size(); i++) {
            final View v = createMenuView(mMenuItems.get(i));
            mMenuItems.get(i).menuView = v;
        }
        resetState(notify);
    }

    private View createMenuView(MenuItem item) {
        AlphaOptimizedImageView iv = new AlphaOptimizedImageView(getContext());
        addView(iv);
        iv.setPadding(mIconPadding, mIconPadding, mIconPadding, mIconPadding);
        iv.setImageDrawable(item.icon);
        iv.setOnClickListener(this);
        iv.setColorFilter(mIconTint);
        iv.setAlpha(mAlpha);
        FrameLayout.LayoutParams lp = (LayoutParams) iv.getLayoutParams();
        lp.width = (int) mHorizSpaceForIcon;
        lp.height = (int) mHorizSpaceForIcon;
        return iv;
    }

    public void resetState(boolean notify) {
        setMenuAlpha(0f);
        mIconsPlaced = false;
        mMenuFadedIn = false;
        mAnimating = false;
        mSnapping = false;
        mDismissing = false;
        setMenuLocation(mOnLeft ? 1 : -1 /* on left */);
        if (mListener != null && notify) {
            mListener.onMenuReset(mParent);
        }
    }

    public void setMenuClickListener(OnMenuClickListener listener) {
        mListener = listener;
    }

    public void setNotificationRowParent(ExpandableNotificationRow parent) {
        mParent = parent;
        setMenuLocation(mOnLeft ? 1 : -1);
    }

    public void setAppName(String appName) {
        Resources res = getResources();
        final int count = mMenuItems.size();
        for (int i = 0; i < count; i++) {
            MenuItem item = mMenuItems.get(i);
            String description = String.format(
                    res.getString(R.string.notification_menu_accessibility),
                    appName, item.menuDescription);
            item.menuView.setContentDescription(description);
        }
    }

    public ExpandableNotificationRow getNotificationParent() {
        return mParent;
    }

    public void setMenuAlpha(float alpha) {
        mAlpha = alpha;
        if (alpha == 0) {
            mMenuFadedIn = false; // Can fade in again once it's gone.
            setVisibility(View.INVISIBLE);
        } else {
            setVisibility(View.VISIBLE);
        }
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            getChildAt(i).setAlpha(mAlpha);
        }
    }

    /**
     * Returns whether the menu is displayed on the left side of the view or not.
     */
    public boolean isMenuOnLeft() {
        return mOnLeft;
    }

    /**
     * Returns the horizontal space in pixels required to display the menu.
     */
    public float getSpaceForMenu() {
        return mHorizSpaceForIcon * getChildCount();
    }

    /**
     * Indicates whether the menu is visible at 1 alpha. Does not indicate if entire view is
     * visible.
     */
    public boolean isVisible() {
        return mAlpha > 0;
    }

    public void cancelFadeAnimator() {
        if (mFadeAnimator != null) {
            mFadeAnimator.cancel();
        }
    }

    public void updateMenuAlpha(final float transX, final float size) {
        if (mAnimating || !mMenuFadedIn) {
            // Don't adjust when animating, or if the menu hasn't been shown yet.
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
        setMenuAlpha(desiredAlpha);
    }

    public void fadeInMenu(final boolean fromLeft, final float transX,
            final float notiThreshold) {
        if (mDismissing || mAnimating) {
            return;
        }
        if (isMenuLocationChange(transX)) {
            setMenuAlpha(0f);
        }
        setMenuLocation((int) transX);
        mFadeAnimator = ValueAnimator.ofFloat(mAlpha, 1);
        mFadeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final float absTrans = Math.abs(transX);

                boolean pastGear = (fromLeft && transX <= notiThreshold)
                        || (!fromLeft && absTrans <= notiThreshold);
                if (pastGear && !mMenuFadedIn) {
                    setMenuAlpha((float) animation.getAnimatedValue());
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
                setMenuAlpha(0f);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimating = false;
                mMenuFadedIn = mAlpha == 1;
            }
        });
        mFadeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mFadeAnimator.setDuration(ICON_ALPHA_ANIM_DURATION);
        mFadeAnimator.start();
    }

    public void updateVerticalLocation() {
        if (mParent == null || mMenuItems.size() == 0) {
            return;
        }
        int parentHeight = mParent.getCollapsedHeight();
        float translationY;
        if (parentHeight < mVertSpaceForIcons) {
            translationY = (parentHeight / 2) - (mHorizSpaceForIcon / 2);
        } else {
            translationY = (mVertSpaceForIcons - mHorizSpaceForIcon) / 2;
        }
        setTranslationY(translationY);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        mIconsPlaced = false;
        setMenuLocation(mOnLeft ? 1 : -1);
    }

    public void setMenuLocation(int translation) {
        boolean onLeft = translation > 0;
        if ((mIconsPlaced && onLeft == mOnLeft) || mSnapping || mParent == null) {
            // Do nothing
            return;
        }
        final boolean isRtl = mParent.isLayoutRtl();
        final int count = getChildCount();
        final int width = getWidth();
        for (int i = 0; i < count; i++) {
            final View v = getChildAt(i);
            final float left = isRtl
                    ? -(width - mHorizSpaceForIcon * (i + 1))
                    : i * mHorizSpaceForIcon;
            final float right = isRtl
                    ? -i * mHorizSpaceForIcon
                    : width - (mHorizSpaceForIcon * (i + 1));
            v.setTranslationX(onLeft ? left : right);
        }
        mOnLeft = onLeft;
        mIconsPlaced = true;
    }

    public boolean isMenuLocationChange(float translation) {
        boolean onLeft = translation > mIconPadding;
        boolean onRight = translation < -mIconPadding;
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
        if (mListener == null) {
            // Nothing to do
            return;
        }
        v.getLocationOnScreen(mIconLocation);
        mParent.getLocationOnScreen(mParentLocation);
        final int centerX = (int) (mHorizSpaceForIcon / 2);
        final int centerY = (int) (v.getTranslationY() * 2 + v.getHeight()) / 2;
        final int x = mIconLocation[0] - mParentLocation[0] + centerX;
        final int y = mIconLocation[1] - mParentLocation[1] + centerY;
        final int index = indexOfChild(v);
        mListener.onMenuClicked(mParent, x, y, mMenuItems.get(index));
    }

    @Override
    public void onPluginConnected(NotificationMenuRowProvider plugin, Context pluginContext) {
        mMenuProvider = plugin;
        updateMenu(false /* notify */);
    }

    @Override
    public void onPluginDisconnected(NotificationMenuRowProvider plugin) {
        mMenuProvider = null;
        updateMenu(false /* notify */);
    }
}