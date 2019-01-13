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

import static com.android.systemui.statusbar.phone.HeadsUpAppearanceController.CONTENT_FADE_DELAY;
import static com.android.systemui.statusbar.phone.HeadsUpAppearanceController.CONTENT_FADE_DURATION;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.view.View;

import androidx.collection.ArrayMap;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.AlphaOptimizedFrameLayout;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.stack.AnimationFilter;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ViewState;

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
    private static final boolean DEBUG_OVERFLOW = false;
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

    /**
     * The animation property used for all icons that were not isolated, when the isolation ends.
     * This just fades the alpha and doesn't affect the movement and has a delay.
     */
    private static final AnimationProperties UNISOLATION_PROPERTY_OTHERS
            = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(CONTENT_FADE_DURATION);

    /**
     * The animation property used for the icon when its isolation ends.
     * This animates the translation back to the right position.
     */
    private static final AnimationProperties UNISOLATION_PROPERTY = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(CONTENT_FADE_DURATION);

    private static final int MAX_VISIBLE_ICONS_WHEN_DARK = 5;
    public static final int MAX_STATIC_ICONS = 4;
    private static final int MAX_DOTS = 1;

    private boolean mIsStaticLayout = true;
    private final HashMap<View, IconState> mIconStates = new HashMap<>();
    private int mDotPadding;
    private int mStaticDotRadius;
    private int mStaticDotDiameter;
    private int mOverflowWidth;
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
    private boolean mDisallowNextAnimation;
    private boolean mAnimationsEnabled = true;
    private ArrayMap<String, ArrayList<StatusBarIcon>> mReplacingIcons;
    // Keep track of the last visible icon so collapsed container can report on its location
    private IconState mLastVisibleIconState;
    private IconState mFirstVisibleIconState;
    private float mVisualOverflowStart;
    // Keep track of overflow in range [0, 3]
    private int mNumDots;
    private StatusBarIconView mIsolatedIcon;
    private Rect mIsolatedIconLocation;
    private int[] mAbsolutePosition = new int[2];
    private View mIsolatedIconForAnimation;

    public NotificationIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDimens();
        setWillNotDraw(!(DEBUG || DEBUG_OVERFLOW));
    }

    private void initDimens() {
        mDotPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_dot_padding);
        mStaticDotRadius = getResources().getDimensionPixelSize(R.dimen.overflow_dot_radius);
        mStaticDotDiameter = 2 * mStaticDotRadius;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(getActualPaddingStart(), 0, getLayoutEnd(), getHeight(), paint);

        if (DEBUG_OVERFLOW) {
            if (mLastVisibleIconState == null) {
                return;
            }

            int height = getHeight();
            int end = getFinalTranslationX();

            // Visualize the "end" of the layout
            paint.setColor(Color.BLUE);
            canvas.drawLine(end, 0, end, height, paint);

            paint.setColor(Color.GREEN);
            int lastIcon = (int) mLastVisibleIconState.xTranslation;
            canvas.drawLine(lastIcon, 0, lastIcon, height, paint);

            if (mFirstVisibleIconState != null) {
                int firstIcon = (int) mFirstVisibleIconState.xTranslation;
                canvas.drawLine(firstIcon, 0, firstIcon, height, paint);
            }

            paint.setColor(Color.RED);
            canvas.drawLine(mVisualOverflowStart, 0, mVisualOverflowStart, height, paint);

            paint.setColor(Color.YELLOW);
            float overflow = getMaxOverflowStart();
            canvas.drawLine(overflow, 0, overflow, height, paint);
        }
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
                setIconSize(child.getWidth());
            }
        }
        getLocationOnScreen(mAbsolutePosition);
        if (mIsStaticLayout) {
            updateState();
        }
    }

    private void setIconSize(int size) {
        mIconSize = size;
        mOverflowWidth = mIconSize + (MAX_DOTS - 1) * (mStaticDotDiameter + mDotPadding);
    }

    private void updateState() {
        resetViewStates();
        calculateIconTranslations();
        applyIconStates();
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
        mIsolatedIconForAnimation = null;
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
        if (child instanceof StatusBarIconView) {
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
                    boolean isIsolatedIcon = child == mIsolatedIcon;
                    icon.setVisibleState(StatusBarIconView.STATE_HIDDEN, true /* animate */,
                            () -> removeTransientView(icon),
                            isIsolatedIcon ? CONTENT_FADE_DURATION : 0);
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
            iconState.alpha = mIsolatedIcon == null || view == mIsolatedIcon ? 1.0f : 0.0f;
            iconState.hidden = false;
        }
    }

    /**
     * Calculate the horizontal translations for each notification based on how much the icons
     * are inserted into the notification container.
     * If this is not a whole number, the fraction means by how much the icon is appearing.
     */
    public void calculateIconTranslations() {
        float translationX = getActualPaddingStart();
        int firstOverflowIndex = -1;
        int childCount = getChildCount();
        int maxVisibleIcons = mDark ? MAX_VISIBLE_ICONS_WHEN_DARK :
                mIsStaticLayout ? MAX_STATIC_ICONS : childCount;
        float layoutEnd = getLayoutEnd();
        float overflowStart = getMaxOverflowStart();
        mVisualOverflowStart = 0;
        mFirstVisibleIconState = null;
        boolean hasAmbient = mSpeedBumpIndex != -1 && mSpeedBumpIndex < getChildCount();
        for (int i = 0; i < childCount; i++) {
            View view = getChildAt(i);
            IconState iconState = mIconStates.get(view);
            iconState.xTranslation = translationX;
            if (mFirstVisibleIconState == null) {
                mFirstVisibleIconState = iconState;
            }
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

            boolean isOverflowing =
                    (translationX > (noOverflowAfter ? layoutEnd - mIconSize
                            : overflowStart - mIconSize));
            if (firstOverflowIndex == -1 && (forceOverflow || isOverflowing)) {
                firstOverflowIndex = noOverflowAfter && !forceOverflow ? i - 1 : i;
                mVisualOverflowStart = layoutEnd - mOverflowWidth;
                if (forceOverflow || mIsStaticLayout) {
                    mVisualOverflowStart = Math.min(translationX, mVisualOverflowStart);
                }
            }
            translationX += iconState.iconAppearAmount * view.getWidth() * drawingScale;
        }
        mNumDots = 0;
        if (firstOverflowIndex != -1) {
            translationX = mVisualOverflowStart;
            for (int i = firstOverflowIndex; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                int dotWidth = mStaticDotDiameter + mDotPadding;
                iconState.xTranslation = translationX;
                if (mNumDots < MAX_DOTS) {
                    if (mNumDots == 0 && iconState.iconAppearAmount < 0.8f) {
                        iconState.visibleState = StatusBarIconView.STATE_ICON;
                    } else {
                        iconState.visibleState = StatusBarIconView.STATE_DOT;
                        mNumDots++;
                    }
                    translationX += (mNumDots == MAX_DOTS ? MAX_DOTS * dotWidth : dotWidth)
                            * iconState.iconAppearAmount;
                    mLastVisibleIconState = iconState;
                } else {
                    iconState.visibleState = StatusBarIconView.STATE_HIDDEN;
                }
            }
        } else if (childCount > 0) {
            View lastChild = getChildAt(childCount - 1);
            mLastVisibleIconState = mIconStates.get(lastChild);
            mFirstVisibleIconState = mIconStates.get(getChildAt(0));
        }

        boolean center = mDark;
        if (center && translationX < getLayoutEnd()) {
            float initialTranslation =
                    mFirstVisibleIconState == null ? 0 : mFirstVisibleIconState.xTranslation;

            float contentWidth = 0;
            if (mLastVisibleIconState != null) {
                contentWidth = mLastVisibleIconState.xTranslation + mIconSize;
                contentWidth = Math.min(getWidth(), contentWidth) - initialTranslation;
            }
            float availableSpace = getLayoutEnd() - getActualPaddingStart();
            float delta = (availableSpace - contentWidth) / 2;

            if (firstOverflowIndex != -1) {
                // If we have an overflow, only count those half for centering because the dots
                // don't have a lot of visual weight.
                float deltaIgnoringOverflow = (getLayoutEnd() - mVisualOverflowStart) / 2;
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
        if (mIsolatedIcon != null) {
            IconState iconState = mIconStates.get(mIsolatedIcon);
            if (iconState != null) {
                // Most of the time the icon isn't yet added when this is called but only happening
                // later
                iconState.xTranslation = mIsolatedIconLocation.left - mAbsolutePosition[0]
                        - (1 - mIsolatedIcon.getIconScale()) * mIsolatedIcon.getWidth() / 2.0f;
                iconState.visibleState = StatusBarIconView.STATE_ICON;
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
     * Sets whether the layout should always show the same number of icons.
     * If this is true, the icon positions will be updated on layout.
     * If this if false, the layout is managed from the outside and layouting won't trigger a
     * repositioning of the icons.
     */
    public void setIsStaticLayout(boolean isStaticLayout) {
        mIsStaticLayout = isStaticLayout;
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

    public int getFinalTranslationX() {
        if (mLastVisibleIconState == null) {
            return 0;
        }

        int translation = (int) (isLayoutRtl() ? getWidth() - mLastVisibleIconState.xTranslation
                : mLastVisibleIconState.xTranslation + mIconSize);
        // There's a chance that last translation goes beyond the edge maybe
        return Math.min(getWidth(), translation);
    }

    private float getMaxOverflowStart() {
        return getLayoutEnd() - mOverflowWidth;
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

    public boolean hasOverflow() {
        return mNumDots > 0;
    }

    /**
     * If the overflow is in the range [1, max_dots - 1) (basically 1 or 2 dots), then
     * extra padding will have to be accounted for
     *
     * This method has no meaning for non-static containers
     */
    public boolean hasPartialOverflow() {
        return mNumDots > 0 && mNumDots < MAX_DOTS;
    }

    /**
     * Get padding that can account for extra dots up to the max. The only valid values for
     * this method are for 1 or 2 dots.
     * @return only extraDotPadding or extraDotPadding * 2
     */
    public int getPartialOverflowExtraPadding() {
        if (!hasPartialOverflow()) {
            return 0;
        }

        int partialOverflowAmount = (MAX_DOTS - mNumDots) * (mStaticDotDiameter + mDotPadding);

        int adjustedWidth = getFinalTranslationX() + partialOverflowAmount;
        // In case we actually give too much padding...
        if (adjustedWidth > getWidth()) {
            partialOverflowAmount = getWidth() - getFinalTranslationX();
        }

        return partialOverflowAmount;
    }

    // Give some extra room for btw notifications if we can
    public int getNoOverflowExtraPadding() {
        if (mNumDots != 0) {
            return 0;
        }

        int collapsedPadding = mOverflowWidth;

        if (collapsedPadding + getFinalTranslationX() > getWidth()) {
            collapsedPadding = getWidth() - getFinalTranslationX();
        }

        return collapsedPadding;
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

    public void setReplacingIcons(ArrayMap<String, ArrayList<StatusBarIcon>> replacingIcons) {
        mReplacingIcons = replacingIcons;
    }

    public void showIconIsolated(StatusBarIconView icon, boolean animated) {
        if (animated) {
            mIsolatedIconForAnimation = icon != null ? icon : mIsolatedIcon;
        }
        mIsolatedIcon = icon;
        updateState();
    }

    public void setIsolatedIconLocation(Rect isolatedIconLocation, boolean requireUpdate) {
        mIsolatedIconLocation = isolatedIconLocation;
        if (requireUpdate) {
            updateState();
        }
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
                    if (mIsolatedIconForAnimation != null) {
                        if (view == mIsolatedIconForAnimation) {
                            animationProperties = UNISOLATION_PROPERTY;
                            animationProperties.setDelay(
                                    mIsolatedIcon != null ? CONTENT_FADE_DELAY : 0);
                        } else {
                            animationProperties = UNISOLATION_PROPERTY_OTHERS;
                            animationProperties.setDelay(
                                    mIsolatedIcon == null ? CONTENT_FADE_DELAY : 0);
                        }
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
                boolean inShelf = iconAppearAmount == 1.0f;
                icon.setIsInShelf(inShelf);
            }
            justAdded = false;
            justReplaced = false;
            needsCannedAnimation = false;
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
