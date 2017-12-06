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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.notification.NotificationUtils.isHapticFeedbackDisabled;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.AsyncTask;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.v4.util.ArrayMap;
import android.support.v4.util.ArraySet;
import android.util.AttributeSet;
import android.view.View;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.AlphaOptimizedFrameLayout;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.stack.AnimationFilter;
import com.android.systemui.statusbar.stack.AnimationProperties;
import com.android.systemui.statusbar.stack.ViewState;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A container for notification icons. It handles overflowing icons properly and positions them
 * correctly on the screen.
 */
public class NotificationIconContainer extends AlphaOptimizedFrameLayout {
    /**
     * A float value indicating how much before the overflow start the icons should transform into
     * a dot. A value of 0 means that they are exactly at the end and a value of 1 means it starts
     * 1 icon width early.
     */
    public static final float OVERFLOW_EARLY_AMOUNT = 0.2f;
    private static final int NO_VALUE = Integer.MIN_VALUE;
    private static final String TAG = "NotificationIconContainer";
    private static final boolean DEBUG = false;
    private static final int CANNED_ANIMATION_DURATION = 100;
    private static final AnimationProperties DOT_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);

    private static final AnimationProperties ICON_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateY().animateAlpha()
                .animateScale();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }

    }.setDuration(CANNED_ANIMATION_DURATION)
            .setCustomInterpolator(View.TRANSLATION_Y, Interpolators.ICON_OVERSHOT);

    /**
     * Temporary AnimationProperties to avoid unnecessary allocations.
     */
    private static final AnimationProperties sTempProperties = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    };

    private static final AnimationProperties ADD_ICON_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200).setDelay(50);

    public static final int MAX_VISIBLE_ICONS_WHEN_DARK = 5;

    private boolean mShowAllIcons = true;
    private final HashMap<View, IconState> mIconStates = new HashMap<>();
    private int mDotPadding;
    private int mStaticDotRadius;
    private int mActualLayoutWidth = NO_VALUE;
    private float mActualPaddingEnd = NO_VALUE;
    private float mActualPaddingStart = NO_VALUE;
    private boolean mDark;
    private boolean mChangingViewPositions;
    private int mAddAnimationStartIndex = -1;
    private int mCannedAnimationStartIndex = -1;
    private int mSpeedBumpIndex = -1;
    private int mIconSize;
    private float mOpenedAmount = 0.0f;
    private float mVisualOverflowAdaption;
    private boolean mDisallowNextAnimation;
    private boolean mAnimationsEnabled = true;
    private boolean mVibrateOnAnimation;
    private Vibrator mVibrator;
    private ArrayMap<String, ArrayList<StatusBarIcon>> mReplacingIcons;
    private int mDarkOffsetX;

    public NotificationIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDimens();
        setWillNotDraw(!DEBUG);
        mVibrator = mContext.getSystemService(Vibrator.class);
    }

    private void initDimens() {
        mDotPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_dot_padding);
        mStaticDotRadius = getResources().getDimensionPixelSize(R.dimen.overflow_dot_radius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(getActualPaddingStart(), 0, getLayoutEnd(), getHeight(), paint);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initDimens();
    }
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float centerY = getHeight() / 2.0f;
        // we layout all our children on the left at the top
        mIconSize = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // We need to layout all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int top = (int) (centerY - height / 2.0f);
            child.layout(0, top, width, top + height);
            if (i == 0) {
                mIconSize = child.getWidth();
            }
        }
        if (mShowAllIcons) {
            resetViewStates();
            calculateIconTranslations();
            applyIconStates();
        }
    }

    public void applyIconStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            ViewState childState = mIconStates.get(child);
            if (childState != null) {
                childState.applyToView(child);
            }
        }
        mAddAnimationStartIndex = -1;
        mCannedAnimationStartIndex = -1;
        mDisallowNextAnimation = false;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        boolean isReplacingIcon = isReplacingIcon(child);
        if (!mChangingViewPositions) {
            IconState v = new IconState();
            if (isReplacingIcon) {
                v.justAdded = false;
                v.justReplaced = true;
            }
            mIconStates.put(child, v);
        }
        int childIndex = indexOfChild(child);
        if (childIndex < getChildCount() - 1 && !isReplacingIcon
            && mIconStates.get(getChildAt(childIndex + 1)).iconAppearAmount > 0.0f) {
            if (mAddAnimationStartIndex < 0) {
                mAddAnimationStartIndex = childIndex;
            } else {
                mAddAnimationStartIndex = Math.min(mAddAnimationStartIndex, childIndex);
            }
        }
        if (mDark && child instanceof StatusBarIconView) {
            ((StatusBarIconView) child).setDark(mDark, false, 0);
        }
    }

    private boolean isReplacingIcon(View child) {
        if (mReplacingIcons == null) {
            return false;
        }
        if (!(child instanceof StatusBarIconView)) {
            return false;
        }
        StatusBarIconView iconView = (StatusBarIconView) child;
        Icon sourceIcon = iconView.getSourceIcon();
        String groupKey = iconView.getNotification().getGroupKey();
        ArrayList<StatusBarIcon> statusBarIcons = mReplacingIcons.get(groupKey);
        if (statusBarIcons != null) {
            StatusBarIcon replacedIcon = statusBarIcons.get(0);
            if (sourceIcon.sameAs(replacedIcon.icon)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if (child instanceof StatusBarIconView) {
            boolean isReplacingIcon = isReplacingIcon(child);
            final StatusBarIconView icon = (StatusBarIconView) child;
            if (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                    && child.getVisibility() == VISIBLE && isReplacingIcon) {
                int animationStartIndex = findFirstViewIndexAfter(icon.getTranslationX());
                if (mAddAnimationStartIndex < 0) {
                    mAddAnimationStartIndex = animationStartIndex;
                } else {
                    mAddAnimationStartIndex = Math.min(mAddAnimationStartIndex, animationStartIndex);
                }
            }
            if (!mChangingViewPositions) {
                mIconStates.remove(child);
                if (!isReplacingIcon) {
                    addTransientView(icon, 0);
                    icon.setVisibleState(StatusBarIconView.STATE_HIDDEN, true /* animate */,
                            () -> removeTransientView(icon));
                }
            }
        }
    }

    /**
     * Finds the first view with a translation bigger then a given value
     */
    private int findFirstViewIndexAfter(float translationX) {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view.getTranslationX() > translationX) {
                return i;
            }
        }
        return getChildCount();
    }

    public void resetViewStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            ViewState iconState = mIconStates.get(view);
            iconState.initFrom(view);
            iconState.alpha = 1.0f;
            iconState.hidden = false;
        }
    }

    /**
     * Calulate the horizontal translations for each notification based on how much the icons
     * are inserted into the notification container.
     * If this is not a whole number, the fraction means by how much the icon is appearing.
     */
    public void calculateIconTranslations() {
        float translationX = getActualPaddingStart();
        int firstOverflowIndex = -1;
        int childCount = getChildCount();
        int maxVisibleIcons = mDark ? MAX_VISIBLE_ICONS_WHEN_DARK : childCount;
        float layoutEnd = getLayoutEnd();
        float overflowStart = layoutEnd - mIconSize * (2 + OVERFLOW_EARLY_AMOUNT);
        boolean hasAmbient = mSpeedBumpIndex != -1 && mSpeedBumpIndex < getChildCount();
        float visualOverflowStart = 0;
        for (int i = 0; i < childCount; i++) {
            View view = getChildAt(i);
            IconState iconState = mIconStates.get(view);
            iconState.xTranslation = translationX;
            boolean forceOverflow = mSpeedBumpIndex != -1 && i >= mSpeedBumpIndex
                    && iconState.iconAppearAmount > 0.0f || i >= maxVisibleIcons;
            boolean noOverflowAfter = i == childCount - 1;
            float drawingScale = mDark && view instanceof StatusBarIconView
                    ? ((StatusBarIconView) view).getIconScaleFullyDark()
                    : 1f;
            if (mOpenedAmount != 0.0f) {
                noOverflowAfter = noOverflowAfter && !hasAmbient && !forceOverflow;
            }
            iconState.visibleState = StatusBarIconView.STATE_ICON;
            if (firstOverflowIndex == -1 && (forceOverflow
                    || (translationX >= (noOverflowAfter ? layoutEnd - mIconSize : overflowStart)))) {
                firstOverflowIndex = noOverflowAfter && !forceOverflow ? i - 1 : i;
                int totalDotLength = mStaticDotRadius * 6 + 2 * mDotPadding;
                visualOverflowStart = overflowStart + mIconSize * (1 + OVERFLOW_EARLY_AMOUNT)
                        - totalDotLength / 2
                        - mIconSize * 0.5f + mStaticDotRadius;
                if (forceOverflow) {
                    visualOverflowStart = Math.min(translationX, visualOverflowStart
                            + mStaticDotRadius * 2 + mDotPadding);
                } else {
                    visualOverflowStart += (translationX - overflowStart) / mIconSize
                            * (mStaticDotRadius * 2 + mDotPadding);
                }
                if (mShowAllIcons) {
                    // We want to perfectly position the overflow in the static state, such that
                    // it's perfectly centered instead of measuring it from the end.
                    mVisualOverflowAdaption = 0;
                    if (firstOverflowIndex != -1) {
                        View firstOverflowView = getChildAt(i);
                        IconState overflowState = mIconStates.get(firstOverflowView);
                        float totalAmount = layoutEnd - overflowState.xTranslation;
                        float newPosition = overflowState.xTranslation + totalAmount / 2
                                - totalDotLength / 2
                                - mIconSize * 0.5f + mStaticDotRadius;
                        mVisualOverflowAdaption = newPosition - visualOverflowStart;
                        visualOverflowStart = newPosition;
                    }
                } else {
                    visualOverflowStart += mVisualOverflowAdaption * (1f - mOpenedAmount);
                }
            }
            translationX += iconState.iconAppearAmount * view.getWidth() * drawingScale;
        }
        if (firstOverflowIndex != -1) {
            int numDots = 1;
            translationX = visualOverflowStart;
            for (int i = firstOverflowIndex; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                int dotWidth = mStaticDotRadius * 2 + mDotPadding;
                iconState.xTranslation = translationX;
                if (numDots <= 3) {
                    if (numDots == 1 && iconState.iconAppearAmount < 0.8f) {
                        iconState.visibleState = StatusBarIconView.STATE_ICON;
                        numDots--;
                    } else {
                        iconState.visibleState = StatusBarIconView.STATE_DOT;
                    }
                    translationX += (numDots == 3 ? 3 * dotWidth : dotWidth)
                            * iconState.iconAppearAmount;
                } else {
                    iconState.visibleState = StatusBarIconView.STATE_HIDDEN;
                }
                numDots++;
            }
        }
        boolean center = mDark;
        if (center && translationX < getLayoutEnd()) {
            float delta = (getLayoutEnd() - translationX) / 2;
            if (firstOverflowIndex != -1) {
                // If we have an overflow, only count those half for centering because the dots
                // don't have a lot of visual weight.
                float deltaIgnoringOverflow = (getLayoutEnd() - visualOverflowStart) / 2;
                delta = (deltaIgnoringOverflow + delta) / 2;
            }
            for (int i = 0; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                iconState.xTranslation += delta;
            }
        }

        if (isLayoutRtl()) {
            for (int i = 0; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                iconState.xTranslation = getWidth() - iconState.xTranslation - view.getWidth();
            }
        }

        if (mDark && mDarkOffsetX != 0) {
            for (int i = 0; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                iconState.xTranslation += mDarkOffsetX;
            }
        }
    }

    private float getLayoutEnd() {
        return getActualWidth() - getActualPaddingEnd();
    }

    private float getActualPaddingEnd() {
        if (mActualPaddingEnd == NO_VALUE) {
            return getPaddingEnd();
        }
        return mActualPaddingEnd;
    }

    private float getActualPaddingStart() {
        if (mActualPaddingStart == NO_VALUE) {
            return getPaddingStart();
        }
        return mActualPaddingStart;
    }

    /**
     * Sets whether the layout should always show all icons.
     * If this is true, the icon positions will be updated on layout.
     * If this if false, the layout is managed from the outside and layouting won't trigger a
     * repositioning of the icons.
     */
    public void setShowAllIcons(boolean showAllIcons) {
        mShowAllIcons = showAllIcons;
    }

    public void setActualLayoutWidth(int actualLayoutWidth) {
        mActualLayoutWidth = actualLayoutWidth;
        if (DEBUG) {
            invalidate();
        }
    }

    public void setActualPaddingEnd(float paddingEnd) {
        mActualPaddingEnd = paddingEnd;
        if (DEBUG) {
            invalidate();
        }
    }

    public void setActualPaddingStart(float paddingStart) {
        mActualPaddingStart = paddingStart;
        if (DEBUG) {
            invalidate();
        }
    }

    public int getActualWidth() {
        if (mActualLayoutWidth == NO_VALUE) {
            return getWidth();
        }
        return mActualLayoutWidth;
    }

    public void setChangingViewPositions(boolean changingViewPositions) {
        mChangingViewPositions = changingViewPositions;
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        mDark = dark;
        mDisallowNextAnimation |= !fade;
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view instanceof StatusBarIconView) {
                ((StatusBarIconView) view).setDark(dark, fade, delay);
            }
        }
    }

    public IconState getIconState(StatusBarIconView icon) {
        return mIconStates.get(icon);
    }

    public void setSpeedBumpIndex(int speedBumpIndex) {
        mSpeedBumpIndex = speedBumpIndex;
    }

    public void setOpenedAmount(float expandAmount) {
        mOpenedAmount = expandAmount;
    }

    public float getVisualOverflowAdaption() {
        return mVisualOverflowAdaption;
    }

    public void setVisualOverflowAdaption(float visualOverflowAdaption) {
        mVisualOverflowAdaption = visualOverflowAdaption;
    }

    public boolean hasOverflow() {
        float width = (getChildCount() + OVERFLOW_EARLY_AMOUNT) * mIconSize;
        return width - (getWidth() - getActualPaddingStart() - getActualPaddingEnd()) > 0;
    }

    public void setVibrateOnAnimation(boolean vibrateOnAnimation) {
        mVibrateOnAnimation = vibrateOnAnimation;
    }

    public int getIconSize() {
        return mIconSize;
    }

    public void setAnimationsEnabled(boolean enabled) {
        if (!enabled && mAnimationsEnabled) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                ViewState childState = mIconStates.get(child);
                if (childState != null) {
                    childState.cancelAnimations(child);
                    childState.applyToView(child);
                }
            }
        }
        mAnimationsEnabled = enabled;
    }

    public void setDarkOffsetX(int offsetX) {
        mDarkOffsetX = offsetX;
    }

    public void setReplacingIcons(ArrayMap<String, ArrayList<StatusBarIcon>> replacingIcons) {
        mReplacingIcons = replacingIcons;
    }

    public class IconState extends ViewState {
        public static final int NO_VALUE = NotificationIconContainer.NO_VALUE;
        public float iconAppearAmount = 1.0f;
        public float clampedAppearAmount = 1.0f;
        public int visibleState;
        public boolean justAdded = true;
        private boolean justReplaced;
        public boolean needsCannedAnimation;
        public boolean useFullTransitionAmount;
        public boolean useLinearTransitionAmount;
        public boolean translateContent;
        public int iconColor = StatusBarIconView.NO_COLOR;
        public boolean noAnimations;
        public boolean isLastExpandIcon;
        public int customTransformHeight = NO_VALUE;

        @Override
        public void applyToView(View view) {
            if (view instanceof StatusBarIconView) {
                StatusBarIconView icon = (StatusBarIconView) view;
                boolean animate = false;
                AnimationProperties animationProperties = null;
                boolean animationsAllowed = mAnimationsEnabled && !mDisallowNextAnimation
                        && !noAnimations;
                if (animationsAllowed) {
                    if (justAdded || justReplaced) {
                        super.applyToView(icon);
                        if (justAdded && iconAppearAmount != 0.0f) {
                            icon.setAlpha(0.0f);
                            icon.setVisibleState(StatusBarIconView.STATE_HIDDEN,
                                    false /* animate */);
                            animationProperties = ADD_ICON_PROPERTIES;
                            animate = true;
                        }
                    } else if (visibleState != icon.getVisibleState()) {
                        animationProperties = DOT_ANIMATION_PROPERTIES;
                        animate = true;
                    }
                    if (!animate && mAddAnimationStartIndex >= 0
                            && indexOfChild(view) >= mAddAnimationStartIndex
                            && (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                            || visibleState != StatusBarIconView.STATE_HIDDEN)) {
                        animationProperties = DOT_ANIMATION_PROPERTIES;
                        animate = true;
                    }
                    if (needsCannedAnimation) {
                        AnimationFilter animationFilter = sTempProperties.getAnimationFilter();
                        animationFilter.reset();
                        animationFilter.combineFilter(
                                ICON_ANIMATION_PROPERTIES.getAnimationFilter());
                        sTempProperties.resetCustomInterpolators();
                        sTempProperties.combineCustomInterpolators(ICON_ANIMATION_PROPERTIES);
                        if (animationProperties != null) {
                            animationFilter.combineFilter(animationProperties.getAnimationFilter());
                            sTempProperties.combineCustomInterpolators(animationProperties);
                        }
                        animationProperties = sTempProperties;
                        animationProperties.setDuration(CANNED_ANIMATION_DURATION);
                        animate = true;
                        mCannedAnimationStartIndex = indexOfChild(view);
                    }
                    if (!animate && mCannedAnimationStartIndex >= 0
                            && indexOfChild(view) > mCannedAnimationStartIndex
                            && (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                            || visibleState != StatusBarIconView.STATE_HIDDEN)) {
                        AnimationFilter animationFilter = sTempProperties.getAnimationFilter();
                        animationFilter.reset();
                        animationFilter.animateX();
                        sTempProperties.resetCustomInterpolators();
                        animationProperties = sTempProperties;
                        animationProperties.setDuration(CANNED_ANIMATION_DURATION);
                        animate = true;
                    }
                }
                icon.setVisibleState(visibleState, animationsAllowed);
                icon.setIconColor(iconColor, needsCannedAnimation && animationsAllowed);
                if (animate) {
                    animateTo(icon, animationProperties);
                } else {
                    super.applyToView(view);
                }
                boolean wasInShelf = icon.isInShelf();
                boolean inShelf = iconAppearAmount == 1.0f;
                icon.setIsInShelf(inShelf);
                if (shouldVibrateChange(wasInShelf != inShelf)) {
                    AsyncTask.execute(
                            () -> mVibrator.vibrate(VibrationEffect.get(
                                    VibrationEffect.EFFECT_TICK)));
                }
            }
            justAdded = false;
            justReplaced = false;
            needsCannedAnimation = false;
        }

        private boolean shouldVibrateChange(boolean inShelfChanged) {
            if (!mVibrateOnAnimation) {
                return false;
            }
            if (justAdded) {
                return false;
            }
            if (!mAnimationsEnabled) {
                return false;
            }
            if (!inShelfChanged) {
                return false;
            }
            if (isHapticFeedbackDisabled(mContext)) {
                return false;
            }
            return true;
        }

        public boolean hasCustomTransformHeight() {
            return isLastExpandIcon && customTransformHeight != NO_VALUE;
        }

        @Override
        public void initFrom(View view) {
            super.initFrom(view);
            if (view instanceof StatusBarIconView) {
                iconColor = ((StatusBarIconView) view).getStaticDrawableColor();
            }
        }
    }
}
