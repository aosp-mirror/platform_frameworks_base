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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.R;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.notification.stack.AnimationFilter;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ViewState;

import java.util.ArrayList;
import java.util.List;

/**
 * A container for Status bar system icons. Limits the number of system icons and handles overflow
 * similar to {@link NotificationIconContainer}.
 *
 * Children are expected to implement {@link StatusIconDisplayable}
 */
public class StatusIconContainer extends AlphaOptimizedLinearLayout {

    private static final String TAG = "StatusIconContainer";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_OVERFLOW = false;
    // Max 8 status icons including battery
    private static final int MAX_ICONS = 7;
    private static final int MAX_DOTS = 1;

    private int mDotPadding;
    private int mIconSpacing;
    private int mStaticDotDiameter;
    private int mUnderflowWidth;
    private int mUnderflowStart = 0;
    // Whether or not we can draw into the underflow space
    private boolean mNeedsUnderflow;
    // Individual StatusBarIconViews draw their etc dots centered in this width
    private int mIconDotFrameWidth;
    private boolean mShouldRestrictIcons = true;
    // Used to count which states want to be visible during layout
    private ArrayList<StatusIconState> mLayoutStates = new ArrayList<>();
    // So we can count and measure properly
    private ArrayList<View> mMeasureViews = new ArrayList<>();
    // Any ignored icon will never be added as a child
    private ArrayList<String> mIgnoredSlots = new ArrayList<>();

    public StatusIconContainer(Context context) {
        this(context, null);
    }

    public StatusIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDimens();
        setWillNotDraw(!DEBUG_OVERFLOW);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    public void setShouldRestrictIcons(boolean should) {
        mShouldRestrictIcons = should;
    }

    public boolean isRestrictingIcons() {
        return mShouldRestrictIcons;
    }

    private void initDimens() {
        // This is the same value that StatusBarIconView uses
        mIconDotFrameWidth = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size);
        mDotPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_dot_padding);
        mIconSpacing = getResources().getDimensionPixelSize(R.dimen.status_bar_system_icon_spacing);
        int radius = getResources().getDimensionPixelSize(R.dimen.overflow_dot_radius);
        mStaticDotDiameter = 2 * radius;
        mUnderflowWidth = mIconDotFrameWidth + (MAX_DOTS - 1) * (mStaticDotDiameter + mDotPadding);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float midY = getHeight() / 2.0f;

        // Layout all child views so that we can move them around later
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int top = (int) (midY - height / 2.0f);
            child.layout(0, top, width, top + height);
        }

        resetViewStates();
        calculateIconTranslations();
        applyIconStates();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (DEBUG_OVERFLOW) {
            Paint paint = new Paint();
            paint.setStyle(Style.STROKE);
            paint.setColor(Color.RED);

            // Show bounding box
            canvas.drawRect(getPaddingStart(), 0, getWidth() - getPaddingEnd(), getHeight(), paint);

            // Show etc box
            paint.setColor(Color.GREEN);
            canvas.drawRect(
                    mUnderflowStart, 0, mUnderflowStart + mUnderflowWidth, getHeight(), paint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mMeasureViews.clear();
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int count = getChildCount();
        // Collect all of the views which want to be laid out
        for (int i = 0; i < count; i++) {
            StatusIconDisplayable icon = (StatusIconDisplayable) getChildAt(i);
            if (icon.isIconVisible() && !icon.isIconBlocked()
                    && !mIgnoredSlots.contains(icon.getSlot())) {
                mMeasureViews.add((View) icon);
            }
        }

        int visibleCount = mMeasureViews.size();
        int maxVisible = visibleCount <= MAX_ICONS ? MAX_ICONS : MAX_ICONS - 1;
        int totalWidth = mPaddingLeft + mPaddingRight;
        boolean trackWidth = true;

        // Measure all children so that they report the correct width
        int childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED);
        mNeedsUnderflow = mShouldRestrictIcons && visibleCount > MAX_ICONS;
        for (int i = 0; i < visibleCount; i++) {
            // Walking backwards
            View child = mMeasureViews.get(visibleCount - i - 1);
            measureChild(child, childWidthSpec, heightMeasureSpec);
            int spacing = i == visibleCount - 1 ? 0 : mIconSpacing;
            if (mShouldRestrictIcons) {
                if (i < maxVisible && trackWidth) {
                    totalWidth += getViewTotalMeasuredWidth(child) + spacing;
                } else if (trackWidth) {
                    // We've hit the icon limit; add space for dots
                    totalWidth += mUnderflowWidth;
                    trackWidth = false;
                }
            } else {
                totalWidth += getViewTotalMeasuredWidth(child) + spacing;
            }
        }

        if (mode == MeasureSpec.EXACTLY) {
            if (!mNeedsUnderflow && totalWidth > width) {
                mNeedsUnderflow = true;
            }
            setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec));
        } else {
            if (mode == MeasureSpec.AT_MOST && totalWidth > width) {
                mNeedsUnderflow = true;
                totalWidth = width;
            }
            setMeasuredDimension(totalWidth, MeasureSpec.getSize(heightMeasureSpec));
        }
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        StatusIconState vs = new StatusIconState();
        vs.justAdded = true;
        child.setTag(R.id.status_bar_view_state_tag, vs);
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        child.setTag(R.id.status_bar_view_state_tag, null);
    }

    /**
     * Add a name of an icon slot to be ignored. It will not show up nor be measured
     * @param slotName name of the icon as it exists in
     * frameworks/base/core/res/res/values/config.xml
     */
    public void addIgnoredSlot(String slotName) {
        addIgnoredSlotInternal(slotName);
        requestLayout();
    }

    /**
     * Add a list of slots to be ignored
     * @param slots names of the icons to ignore
     */
    public void addIgnoredSlots(List<String> slots) {
        for (String slot : slots) {
            addIgnoredSlotInternal(slot);
        }

        requestLayout();
    }

    private void addIgnoredSlotInternal(String slotName) {
        if (!mIgnoredSlots.contains(slotName)) {
            mIgnoredSlots.add(slotName);
        }
    }

    /**
     * Remove a slot from the list of ignored icon slots. It will then be shown when set to visible
     * by the {@link StatusBarIconController}.
     * @param slotName name of the icon slot to remove from the ignored list
     */
    public void removeIgnoredSlot(String slotName) {
        if (mIgnoredSlots.contains(slotName)) {
            mIgnoredSlots.remove(slotName);
        }

        requestLayout();
    }

    /**
     * Sets the list of ignored icon slots clearing the current list.
     * @param slots names of the icons to ignore
     */
    public void setIgnoredSlots(List<String> slots) {
        mIgnoredSlots.clear();
        addIgnoredSlots(slots);
    }

    /**
     * Layout is happening from end -> start
     */
    private void calculateIconTranslations() {
        mLayoutStates.clear();
        float width = getWidth();
        float translationX = width - getPaddingEnd();
        float contentStart = getPaddingStart();
        int childCount = getChildCount();
        // Underflow === don't show content until that index
        if (DEBUG) Log.d(TAG, "calculateIconTranslations: start=" + translationX
                + " width=" + width + " underflow=" + mNeedsUnderflow);

        // Collect all of the states which want to be visible
        for (int i = childCount - 1; i >= 0; i--) {
            View child = getChildAt(i);
            StatusIconDisplayable iconView = (StatusIconDisplayable) child;
            StatusIconState childState = getViewStateFromChild(child);

            if (!iconView.isIconVisible() || iconView.isIconBlocked()
                    || mIgnoredSlots.contains(iconView.getSlot())) {
                childState.visibleState = STATE_HIDDEN;
                if (DEBUG) Log.d(TAG, "skipping child (" + iconView.getSlot() + ") not visible");
                continue;
            }

            // Move translationX to the spot within StatusIconContainer's layout to add the view
            // without cutting off the child view.
            translationX -= getViewTotalWidth(child);
            childState.visibleState = STATE_ICON;
            childState.xTranslation = translationX;
            mLayoutStates.add(0, childState);

            // Shift translationX over by mIconSpacing for the next view.
            translationX -= mIconSpacing;
        }

        // Show either 1-MAX_ICONS icons, or (MAX_ICONS - 1) icons + overflow
        int totalVisible = mLayoutStates.size();
        int maxVisible = totalVisible <= MAX_ICONS ? MAX_ICONS : MAX_ICONS - 1;

        mUnderflowStart = 0;
        int visible = 0;
        int firstUnderflowIndex = -1;
        for (int i = totalVisible - 1; i >= 0; i--) {
            StatusIconState state = mLayoutStates.get(i);
            // Allow room for underflow if we found we need it in onMeasure
            if (mNeedsUnderflow && (state.xTranslation < (contentStart + mUnderflowWidth))||
                    (mShouldRestrictIcons && visible >= maxVisible)) {
                firstUnderflowIndex = i;
                break;
            }
            mUnderflowStart = (int) Math.max(
                    contentStart, state.xTranslation - mUnderflowWidth - mIconSpacing);
            visible++;
        }

        if (firstUnderflowIndex != -1) {
            int totalDots = 0;
            int dotWidth = mStaticDotDiameter + mDotPadding;
            int dotOffset = mUnderflowStart + mUnderflowWidth - mIconDotFrameWidth;
            for (int i = firstUnderflowIndex; i >= 0; i--) {
                StatusIconState state = mLayoutStates.get(i);
                if (totalDots < MAX_DOTS) {
                    state.xTranslation = dotOffset;
                    state.visibleState = STATE_DOT;
                    dotOffset -= dotWidth;
                    totalDots++;
                } else {
                    state.visibleState = STATE_HIDDEN;
                }
            }
        }

        // Stole this from NotificationIconContainer. Not optimal but keeps the layout logic clean
        if (isLayoutRtl()) {
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                StatusIconState state = getViewStateFromChild(child);
                state.xTranslation = width - state.xTranslation - child.getWidth();
            }
        }
    }

    private void applyIconStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            StatusIconState vs = getViewStateFromChild(child);
            if (vs != null) {
                vs.applyToView(child);
            }
        }
    }

    private void resetViewStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            StatusIconState vs = getViewStateFromChild(child);
            if (vs == null) {
                continue;
            }

            vs.initFrom(child);
            vs.alpha = 1.0f;
            vs.hidden = false;
        }
    }

    private static @Nullable StatusIconState getViewStateFromChild(View child) {
        return (StatusIconState) child.getTag(R.id.status_bar_view_state_tag);
    }

    private static int getViewTotalMeasuredWidth(View child) {
        return child.getMeasuredWidth() + child.getPaddingStart() + child.getPaddingEnd();
    }

    private static int getViewTotalWidth(View child) {
        return child.getWidth() + child.getPaddingStart() + child.getPaddingEnd();
    }

    public static class StatusIconState extends ViewState {
        /// StatusBarIconView.STATE_*
        public int visibleState = STATE_ICON;
        public boolean justAdded = true;

        // How far we are from the end of the view actually is the most relevant for animation
        float distanceToViewEnd = -1;

        @Override
        public void applyToView(View view) {
            float parentWidth = 0;
            if (view.getParent() instanceof View) {
                parentWidth = ((View) view.getParent()).getWidth();
            }

            float currentDistanceToEnd = parentWidth - xTranslation;

            if (!(view instanceof StatusIconDisplayable)) {
                return;
            }
            StatusIconDisplayable icon = (StatusIconDisplayable) view;
            AnimationProperties animationProperties = null;
            boolean animateVisibility = true;

            // Figure out which properties of the state transition (if any) we need to animate
            if (justAdded
                    || icon.getVisibleState() == STATE_HIDDEN && visibleState == STATE_ICON) {
                // Icon is appearing, fade it in by putting it where it will be and animating alpha
                super.applyToView(view);
                view.setAlpha(0.f);
                icon.setVisibleState(STATE_HIDDEN);
                animationProperties = ADD_ICON_PROPERTIES;
            } else if (icon.getVisibleState() != visibleState) {
                if (icon.getVisibleState() == STATE_ICON && visibleState == STATE_HIDDEN) {
                    // Disappearing, don't do anything fancy
                    animateVisibility = false;
                } else {
                    // all other transitions (to/from dot, etc)
                    animationProperties = ANIMATE_ALL_PROPERTIES;
                }
            } else if (visibleState != STATE_HIDDEN && distanceToViewEnd != currentDistanceToEnd) {
                // Visibility isn't changing, just animate position
                animationProperties = X_ANIMATION_PROPERTIES;
            }

            icon.setVisibleState(visibleState, animateVisibility);
            if (animationProperties != null) {
                animateTo(view, animationProperties);
            } else {
                super.applyToView(view);
            }

            justAdded = false;
            distanceToViewEnd = currentDistanceToEnd;

        }
    }

    private static final AnimationProperties ADD_ICON_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200).setDelay(50);

    private static final AnimationProperties X_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);

    private static final AnimationProperties ANIMATE_ALL_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX().animateY()
                .animateAlpha().animateScale();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);
}
