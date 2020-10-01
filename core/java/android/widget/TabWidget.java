/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.R;

/**
 *
 * Displays a list of tab labels representing each page in the parent's tab
 * collection.
 * <p>
 * The container object for this widget is {@link android.widget.TabHost TabHost}.
 * When the user selects a tab, this object sends a message to the parent
 * container, TabHost, to tell it to switch the displayed page. You typically
 * won't use many methods directly on this object. The container TabHost is
 * used to add labels, add the callback handler, and manage callbacks. You
 * might call this object to iterate the list of tabs, or to tweak the layout
 * of the tab list, but most methods should be called on the containing TabHost
 * object.
 *
 * @attr ref android.R.styleable#TabWidget_divider
 * @attr ref android.R.styleable#TabWidget_tabStripEnabled
 * @attr ref android.R.styleable#TabWidget_tabStripLeft
 * @attr ref android.R.styleable#TabWidget_tabStripRight
 *
 * @deprecated new applications should use fragment APIs instead of this class:
 * Use <a href="{@docRoot}guide/navigation/navigation-swipe-view">TabLayout and ViewPager</a>
 * instead.
 */
@Deprecated
public class TabWidget extends LinearLayout implements OnFocusChangeListener {
    private final Rect mBounds = new Rect();

    private OnTabSelectionChanged mSelectionChangedListener;

    // This value will be set to 0 as soon as the first tab is added to TabHost.
    @UnsupportedAppUsage(trackingBug = 137825207, maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@code androidx.viewpager.widget.ViewPager} and "
                    + "{@code com.google.android.material.tabs.TabLayout} instead.\n"
                    + "See <a href=\"{@docRoot}guide/navigation/navigation-swipe-view"
                    + "\">TabLayout and ViewPager</a>")
    private int mSelectedTab = -1;

    @Nullable
    private Drawable mLeftStrip;

    @Nullable
    private Drawable mRightStrip;

    @UnsupportedAppUsage(trackingBug = 137825207, maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@code androidx.viewpager.widget.ViewPager} and "
                    + "{@code com.google.android.material.tabs.TabLayout} instead.\n"
                    + "See <a href=\"{@docRoot}guide/navigation/navigation-swipe-view"
                    + "\">TabLayout and ViewPager</a>")
    private boolean mDrawBottomStrips = true;
    private boolean mStripMoved;

    // When positive, the widths and heights of tabs will be imposed so that
    // they fit in parent.
    private int mImposedTabsHeight = -1;
    private int[] mImposedTabWidths;

    public TabWidget(Context context) {
        this(context, null);
    }

    public TabWidget(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.tabWidgetStyle);
    }

    public TabWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TabWidget(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.TabWidget, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.TabWidget,
                attrs, a, defStyleAttr, defStyleRes);

        mDrawBottomStrips = a.getBoolean(R.styleable.TabWidget_tabStripEnabled, mDrawBottomStrips);

        // Tests the target SDK version, as set in the Manifest. Could not be
        // set using styles.xml in a values-v? directory which targets the
        // current platform SDK version instead.
        final boolean isTargetSdkDonutOrLower =
                context.getApplicationInfo().targetSdkVersion <= Build.VERSION_CODES.DONUT;

        final boolean hasExplicitLeft = a.hasValueOrEmpty(R.styleable.TabWidget_tabStripLeft);
        if (hasExplicitLeft) {
            mLeftStrip = a.getDrawable(R.styleable.TabWidget_tabStripLeft);
        } else if (isTargetSdkDonutOrLower) {
            mLeftStrip = context.getDrawable(R.drawable.tab_bottom_left_v4);
        } else {
            mLeftStrip = context.getDrawable(R.drawable.tab_bottom_left);
        }

        final boolean hasExplicitRight = a.hasValueOrEmpty(R.styleable.TabWidget_tabStripRight);
        if (hasExplicitRight) {
            mRightStrip = a.getDrawable(R.styleable.TabWidget_tabStripRight);
        } else if (isTargetSdkDonutOrLower) {
            mRightStrip = context.getDrawable(R.drawable.tab_bottom_right_v4);
        } else {
            mRightStrip = context.getDrawable(R.drawable.tab_bottom_right);
        }

        a.recycle();

        setChildrenDrawingOrderEnabled(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mStripMoved = true;

        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        if (mSelectedTab == -1) {
            return i;
        } else {
            // Always draw the selected tab last, so that drop shadows are drawn
            // in the correct z-order.
            if (i == childCount - 1) {
                return mSelectedTab;
            } else if (i >= mSelectedTab) {
                return i + 1;
            } else {
                return i;
            }
        }
    }

    @Override
    void measureChildBeforeLayout(View child, int childIndex, int widthMeasureSpec, int totalWidth,
            int heightMeasureSpec, int totalHeight) {
        if (!isMeasureWithLargestChildEnabled() && mImposedTabsHeight >= 0) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                    totalWidth + mImposedTabWidths[childIndex], MeasureSpec.EXACTLY);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(mImposedTabsHeight,
                    MeasureSpec.EXACTLY);
        }

        super.measureChildBeforeLayout(child, childIndex,
                widthMeasureSpec, totalWidth, heightMeasureSpec, totalHeight);
    }

    @Override
    void measureHorizontal(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.measureHorizontal(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // First, measure with no constraint
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int unspecifiedWidth = MeasureSpec.makeSafeMeasureSpec(width,
                MeasureSpec.UNSPECIFIED);
        mImposedTabsHeight = -1;
        super.measureHorizontal(unspecifiedWidth, heightMeasureSpec);

        int extraWidth = getMeasuredWidth() - width;
        if (extraWidth > 0) {
            final int count = getChildCount();

            int childCount = 0;
            for (int i = 0; i < count; i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                childCount++;
            }

            if (childCount > 0) {
                if (mImposedTabWidths == null || mImposedTabWidths.length != count) {
                    mImposedTabWidths = new int[count];
                }
                for (int i = 0; i < count; i++) {
                    final View child = getChildAt(i);
                    if (child.getVisibility() == GONE) continue;
                    final int childWidth = child.getMeasuredWidth();
                    final int delta = extraWidth / childCount;
                    final int newWidth = Math.max(0, childWidth - delta);
                    mImposedTabWidths[i] = newWidth;
                    // Make sure the extra width is evenly distributed, no int division remainder
                    extraWidth -= childWidth - newWidth; // delta may have been clamped
                    childCount--;
                    mImposedTabsHeight = Math.max(mImposedTabsHeight, child.getMeasuredHeight());
                }
            }
        }

        // Measure again, this time with imposed tab widths and respecting
        // initial spec request.
        super.measureHorizontal(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Returns the tab indicator view at the given index.
     *
     * @param index the zero-based index of the tab indicator view to return
     * @return the tab indicator view at the given index
     */
    public View getChildTabViewAt(int index) {
        return getChildAt(index);
    }

    /**
     * Returns the number of tab indicator views.
     *
     * @return the number of tab indicator views
     */
    public int getTabCount() {
        return getChildCount();
    }

    /**
     * Sets the drawable to use as a divider between the tab indicators.
     *
     * @param drawable the divider drawable
     * @attr ref android.R.styleable#TabWidget_divider
     */
    @Override
    public void setDividerDrawable(@Nullable Drawable drawable) {
        super.setDividerDrawable(drawable);
    }

    /**
     * Sets the drawable to use as a divider between the tab indicators.
     *
     * @param resId the resource identifier of the drawable to use as a divider
     * @attr ref android.R.styleable#TabWidget_divider
     */
    public void setDividerDrawable(@DrawableRes int resId) {
        setDividerDrawable(mContext.getDrawable(resId));
    }

    /**
     * Sets the drawable to use as the left part of the strip below the tab
     * indicators.
     *
     * @param drawable the left strip drawable
     * @see #getLeftStripDrawable()
     * @attr ref android.R.styleable#TabWidget_tabStripLeft
     */
    public void setLeftStripDrawable(@Nullable Drawable drawable) {
        mLeftStrip = drawable;
        requestLayout();
        invalidate();
    }

    /**
     * Sets the drawable to use as the left part of the strip below the tab
     * indicators.
     *
     * @param resId the resource identifier of the drawable to use as the left
     *              strip drawable
     * @see #getLeftStripDrawable()
     * @attr ref android.R.styleable#TabWidget_tabStripLeft
     */
    public void setLeftStripDrawable(@DrawableRes int resId) {
        setLeftStripDrawable(mContext.getDrawable(resId));
    }

    /**
     * @return the drawable used as the left part of the strip below the tab
     *         indicators, may be {@code null}
     * @see #setLeftStripDrawable(int)
     * @see #setLeftStripDrawable(Drawable)
     * @attr ref android.R.styleable#TabWidget_tabStripLeft
     */
    @Nullable
    public Drawable getLeftStripDrawable() {
        return mLeftStrip;
    }

    /**
     * Sets the drawable to use as the right part of the strip below the tab
     * indicators.
     *
     * @param drawable the right strip drawable
     * @see #getRightStripDrawable()
     * @attr ref android.R.styleable#TabWidget_tabStripRight
     */
    public void setRightStripDrawable(@Nullable Drawable drawable) {
        mRightStrip = drawable;
        requestLayout();
        invalidate();
    }

    /**
     * Sets the drawable to use as the right part of the strip below the tab
     * indicators.
     *
     * @param resId the resource identifier of the drawable to use as the right
     *              strip drawable
     * @see #getRightStripDrawable()
     * @attr ref android.R.styleable#TabWidget_tabStripRight
     */
    public void setRightStripDrawable(@DrawableRes int resId) {
        setRightStripDrawable(mContext.getDrawable(resId));
    }

    /**
     * @return the drawable used as the right part of the strip below the tab
     *         indicators, may be {@code null}
     * @see #setRightStripDrawable(int)
     * @see #setRightStripDrawable(Drawable)
     * @attr ref android.R.styleable#TabWidget_tabStripRight
     */
    @Nullable
    public Drawable getRightStripDrawable() {
        return mRightStrip;
    }

    /**
     * Controls whether the bottom strips on the tab indicators are drawn or
     * not.  The default is to draw them.  If the user specifies a custom
     * view for the tab indicators, then the TabHost class calls this method
     * to disable drawing of the bottom strips.
     * @param stripEnabled true if the bottom strips should be drawn.
     */
    public void setStripEnabled(boolean stripEnabled) {
        mDrawBottomStrips = stripEnabled;
        invalidate();
    }

    /**
     * Indicates whether the bottom strips on the tab indicators are drawn
     * or not.
     */
    public boolean isStripEnabled() {
        return mDrawBottomStrips;
    }

    @Override
    public void childDrawableStateChanged(View child) {
        if (getTabCount() > 0 && child == getChildTabViewAt(mSelectedTab)) {
            // To make sure that the bottom strip is redrawn
            invalidate();
        }
        super.childDrawableStateChanged(child);
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        // Do nothing if there are no tabs.
        if (getTabCount() == 0) return;

        // If the user specified a custom view for the tab indicators, then
        // do not draw the bottom strips.
        if (!mDrawBottomStrips) {
            // Skip drawing the bottom strips.
            return;
        }

        final View selectedChild = getChildTabViewAt(mSelectedTab);

        final Drawable leftStrip = mLeftStrip;
        final Drawable rightStrip = mRightStrip;

        if (leftStrip != null) {
            leftStrip.setState(selectedChild.getDrawableState());
        }
        if (rightStrip != null) {
            rightStrip.setState(selectedChild.getDrawableState());
        }

        if (mStripMoved) {
            final Rect bounds = mBounds;
            bounds.left = selectedChild.getLeft();
            bounds.right = selectedChild.getRight();
            final int myHeight = getHeight();
            if (leftStrip != null) {
                leftStrip.setBounds(Math.min(0, bounds.left - leftStrip.getIntrinsicWidth()),
                        myHeight - leftStrip.getIntrinsicHeight(), bounds.left, myHeight);
            }
            if (rightStrip != null) {
                rightStrip.setBounds(bounds.right, myHeight - rightStrip.getIntrinsicHeight(),
                        Math.max(getWidth(), bounds.right + rightStrip.getIntrinsicWidth()),
                        myHeight);
            }
            mStripMoved = false;
        }

        if (leftStrip != null) {
            leftStrip.draw(canvas);
        }
        if (rightStrip != null) {
            rightStrip.draw(canvas);
        }
    }

    /**
     * Sets the current tab.
     * <p>
     * This method is used to bring a tab to the front of the Widget,
     * and is used to post to the rest of the UI that a different tab
     * has been brought to the foreground.
     * <p>
     * Note, this is separate from the traditional "focus" that is
     * employed from the view logic.
     * <p>
     * For instance, if we have a list in a tabbed view, a user may be
     * navigating up and down the list, moving the UI focus (orange
     * highlighting) through the list items.  The cursor movement does
     * not effect the "selected" tab though, because what is being
     * scrolled through is all on the same tab.  The selected tab only
     * changes when we navigate between tabs (moving from the list view
     * to the next tabbed view, in this example).
     * <p>
     * To move both the focus AND the selected tab at once, please use
     * {@link #focusCurrentTab}. Normally, the view logic takes care of
     * adjusting the focus, so unless you're circumventing the UI,
     * you'll probably just focus your interest here.
     *
     * @param index the index of the tab that you want to indicate as the
     *              selected tab (tab brought to the front of the widget)
     * @see #focusCurrentTab
     */
    public void setCurrentTab(int index) {
        if (index < 0 || index >= getTabCount() || index == mSelectedTab) {
            return;
        }

        if (mSelectedTab != -1) {
            getChildTabViewAt(mSelectedTab).setSelected(false);
        }
        mSelectedTab = index;
        getChildTabViewAt(mSelectedTab).setSelected(true);
        mStripMoved = true;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return TabWidget.class.getName();
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);
        event.setItemCount(getTabCount());
        event.setCurrentItemIndex(mSelectedTab);
    }

    /**
     * Sets the current tab and focuses the UI on it.
     * This method makes sure that the focused tab matches the selected
     * tab, normally at {@link #setCurrentTab}.  Normally this would not
     * be an issue if we go through the UI, since the UI is responsible
     * for calling TabWidget.onFocusChanged(), but in the case where we
     * are selecting the tab programmatically, we'll need to make sure
     * focus keeps up.
     *
     *  @param index The tab that you want focused (highlighted in orange)
     *  and selected (tab brought to the front of the widget)
     *
     *  @see #setCurrentTab
     */
    public void focusCurrentTab(int index) {
        final int oldTab = mSelectedTab;

        // set the tab
        setCurrentTab(index);

        // change the focus if applicable.
        if (oldTab != index) {
            getChildTabViewAt(index).requestFocus();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        final int count = getTabCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildTabViewAt(i);
            child.setEnabled(enabled);
        }
    }

    @Override
    public void addView(View child) {
        if (child.getLayoutParams() == null) {
            final LinearLayout.LayoutParams lp = new LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            lp.setMargins(0, 0, 0, 0);
            child.setLayoutParams(lp);
        }

        // Ensure you can navigate to the tab with the keyboard, and you can touch it
        child.setFocusable(true);
        child.setClickable(true);

        if (child.getPointerIcon() == null) {
            child.setPointerIcon(PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_HAND));
        }

        super.addView(child);

        // TODO: detect this via geometry with a tabwidget listener rather
        // than potentially interfere with the view's listener
        child.setOnClickListener(new TabClickListener(getTabCount() - 1));
    }

    @Override
    public void removeAllViews() {
        super.removeAllViews();
        mSelectedTab = -1;
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (!isEnabled()) {
            return null;
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }

    /**
     * Provides a way for {@link TabHost} to be notified that the user clicked
     * on a tab indicator.
     */
    @UnsupportedAppUsage(trackingBug = 137825207, maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "Use {@code androidx.viewpager.widget.ViewPager} and "
                    + "{@code com.google.android.material.tabs.TabLayout} instead.\n"
                    + "See <a href=\"{@docRoot}guide/navigation/navigation-swipe-view"
                    + "\">TabLayout and ViewPager</a>")
    void setTabSelectionListener(OnTabSelectionChanged listener) {
        mSelectionChangedListener = listener;
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        // No-op. Tab selection is separate from keyboard focus.
    }

    // registered with each tab indicator so we can notify tab host
    private class TabClickListener implements OnClickListener {
        private final int mTabIndex;

        private TabClickListener(int tabIndex) {
            mTabIndex = tabIndex;
        }

        public void onClick(View v) {
            mSelectionChangedListener.onTabSelectionChanged(mTabIndex, true);
        }
    }

    /**
     * Lets {@link TabHost} know that the user clicked on a tab indicator.
     */
    interface OnTabSelectionChanged {
        /**
         * Informs the TabHost which tab was selected. It also indicates
         * if the tab was clicked/pressed or just focused into.
         *
         * @param tabIndex index of the tab that was selected
         * @param clicked whether the selection changed due to a touch/click or
         *                due to focus entering the tab through navigation.
         *                {@code true} if it was due to a press/click and
         *                {@code false} otherwise.
         */
        void onTabSelectionChanged(int tabIndex, boolean clicked);
    }
}
