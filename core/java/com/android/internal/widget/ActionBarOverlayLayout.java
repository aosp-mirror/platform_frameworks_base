/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.widget;

import android.view.ViewGroup;
import com.android.internal.app.ActionBarImpl;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

/**
 * Special layout for the containing of an overlay action bar (and its
 * content) to correctly handle fitting system windows when the content
 * has request that its layout ignore them.
 */
public class ActionBarOverlayLayout extends ViewGroup {
    private int mActionBarHeight;
    private ActionBarImpl mActionBar;
    private int mWindowVisibility = View.VISIBLE;

    // The main UI elements that we handle the layout of.
    private View mContent;
    private View mActionBarTop;
    private View mActionBarBottom;

    // Some interior UI elements.
    private ActionBarContainer mContainerView;
    private ActionBarView mActionView;

    private boolean mOverlayMode;
    private int mLastSystemUiVisibility;
    private final Rect mBaseContentInsets = new Rect();
    private final Rect mLastBaseContentInsets = new Rect();
    private final Rect mContentInsets = new Rect();
    private final Rect mBaseInnerInsets = new Rect();
    private final Rect mInnerInsets = new Rect();
    private final Rect mLastInnerInsets = new Rect();

    static final int[] mActionBarSizeAttr = new int [] {
            com.android.internal.R.attr.actionBarSize
    };

    public ActionBarOverlayLayout(Context context) {
        super(context);
        init(context);
    }

    public ActionBarOverlayLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        TypedArray ta = getContext().getTheme().obtainStyledAttributes(mActionBarSizeAttr);
        mActionBarHeight = ta.getDimensionPixelSize(0, 0);
        ta.recycle();
    }

    public void setActionBar(ActionBarImpl impl, boolean overlayMode) {
        mActionBar = impl;
        mOverlayMode = overlayMode;
        if (getWindowToken() != null) {
            // This is being initialized after being added to a window;
            // make sure to update all state now.
            mActionBar.setWindowVisibility(mWindowVisibility);
            if (mLastSystemUiVisibility != 0) {
                int newVis = mLastSystemUiVisibility;
                onWindowSystemUiVisibilityChanged(newVis);
                requestFitSystemWindows();
            }
        }
    }

    public void setShowingForActionMode(boolean showing) {
        if (showing) {
            // Here's a fun hack: if the status bar is currently being hidden,
            // and the application has asked for stable content insets, then
            // we will end up with the action mode action bar being shown
            // without the status bar, but moved below where the status bar
            // would be.  Not nice.  Trying to have this be positioned
            // correctly is not easy (basically we need yet *another* content
            // inset from the window manager to know where to put it), so
            // instead we will just temporarily force the status bar to be shown.
            if ((getWindowSystemUiVisibility() & (SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | SYSTEM_UI_FLAG_LAYOUT_STABLE))
                    == (SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | SYSTEM_UI_FLAG_LAYOUT_STABLE)) {
                setDisabledSystemUiVisibility(SYSTEM_UI_FLAG_FULLSCREEN);
            }
        } else {
            setDisabledSystemUiVisibility(0);
        }
    }

    @Override
    public void onWindowSystemUiVisibilityChanged(int visible) {
        super.onWindowSystemUiVisibilityChanged(visible);
        pullChildren();
        final int diff = mLastSystemUiVisibility ^ visible;
        mLastSystemUiVisibility = visible;
        final boolean barVisible = (visible&SYSTEM_UI_FLAG_FULLSCREEN) == 0;
        final boolean wasVisible = mActionBar != null ? mActionBar.isSystemShowing() : true;
        final boolean stable = (visible&SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0;
        if (mActionBar != null) {
            // We want the bar to be visible if it is not being hidden,
            // or the app has not turned on a stable UI mode (meaning they
            // are performing explicit layout around the action bar).
            mActionBar.enableContentAnimations(!stable);
            if (barVisible || !stable) mActionBar.showForSystem();
            else mActionBar.hideForSystem();
        }
        if ((diff&SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0) {
            if (mActionBar != null) {
                requestFitSystemWindows();
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVisibility = visibility;
        if (mActionBar != null) {
            mActionBar.setWindowVisibility(visibility);
        }
    }

    private boolean applyInsets(View view, Rect insets, boolean left, boolean top,
            boolean bottom, boolean right) {
        boolean changed = false;
        LayoutParams lp = (LayoutParams)view.getLayoutParams();
        if (left && lp.leftMargin != insets.left) {
            changed = true;
            lp.leftMargin = insets.left;
        }
        if (top && lp.topMargin != insets.top) {
            changed = true;
            lp.topMargin = insets.top;
        }
        if (right && lp.rightMargin != insets.right) {
            changed = true;
            lp.rightMargin = insets.right;
        }
        if (bottom && lp.bottomMargin != insets.bottom) {
            changed = true;
            lp.bottomMargin = insets.bottom;
        }
        return changed;
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        pullChildren();

        final int vis = getWindowSystemUiVisibility();
        final boolean stable = (vis & SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0;

        // The top and bottom action bars are always within the content area.
        boolean changed = applyInsets(mActionBarTop, insets, true, true, false, true);
        if (mActionBarBottom != null) {
            changed |= applyInsets(mActionBarBottom, insets, true, false, true, true);
        }

        mBaseInnerInsets.set(insets);
        computeFitSystemWindows(mBaseInnerInsets, mBaseContentInsets);
        if (!mLastBaseContentInsets.equals(mBaseContentInsets)) {
            changed = true;
            mLastBaseContentInsets.set(mBaseContentInsets);
        }

        if (changed) {
            requestLayout();
        }

        // We don't do any more at this point.  To correctly compute the content/inner
        // insets in all cases, we need to know the measured size of the various action
        // bar elements.  fitSystemWindows() happens before the measure pass, so we can't
        // do that here.  Instead we will take this up in onMeasure().
        return true;
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        pullChildren();

        int maxHeight = 0;
        int maxWidth = 0;
        int childState = 0;

        int topInset = 0;
        int bottomInset = 0;

        measureChildWithMargins(mActionBarTop, widthMeasureSpec, 0, heightMeasureSpec, 0);
        LayoutParams lp = (LayoutParams) mActionBarTop.getLayoutParams();
        maxWidth = Math.max(maxWidth,
                mActionBarTop.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
        maxHeight = Math.max(maxHeight,
                mActionBarTop.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        childState = combineMeasuredStates(childState, mActionBarTop.getMeasuredState());

        // xlarge screen layout doesn't have bottom action bar.
        if (mActionBarBottom != null) {
            measureChildWithMargins(mActionBarBottom, widthMeasureSpec, 0, heightMeasureSpec, 0);
            lp = (LayoutParams) mActionBarBottom.getLayoutParams();
            maxWidth = Math.max(maxWidth,
                    mActionBarBottom.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
            maxHeight = Math.max(maxHeight,
                    mActionBarBottom.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            childState = combineMeasuredStates(childState, mActionBarBottom.getMeasuredState());
        }

        final int vis = getWindowSystemUiVisibility();
        final boolean stable = (vis & SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0;

        if (stable) {
            // This is the standard space needed for the action bar.  For stable measurement,
            // we can't depend on the size currently reported by it -- this must remain constant.
            topInset = mActionBarHeight;
            if (mActionBar != null && mActionBar.hasNonEmbeddedTabs()) {
                View tabs = mContainerView.getTabContainer();
                if (tabs != null) {
                    // If tabs are not embedded, increase space on top to account for them.
                    topInset += mActionBarHeight;
                }
            }
        } else if (mActionBarTop.getVisibility() == VISIBLE) {
            // This is the space needed on top of the window for all of the action bar
            // and tabs.
            topInset = mActionBarTop.getMeasuredHeight();
        }

        if (mActionView.isSplitActionBar()) {
            // If action bar is split, adjust bottom insets for it.
            if (mActionBarBottom != null) {
                if (stable) {
                    bottomInset = mActionBarHeight;
                } else {
                    bottomInset = mActionBarBottom.getMeasuredHeight();
                }
            }
        }

        // If the window has not requested system UI layout flags, we need to
        // make sure its content is not being covered by system UI...  though it
        // will still be covered by the action bar if they have requested it to
        // overlay.
        mContentInsets.set(mBaseContentInsets);
        mInnerInsets.set(mBaseInnerInsets);
        if (!mOverlayMode && !stable) {
            mContentInsets.top += topInset;
            mContentInsets.bottom += bottomInset;
        } else {
            mInnerInsets.top += topInset;
            mInnerInsets.bottom += bottomInset;
        }
        applyInsets(mContent, mContentInsets, true, true, true, true);

        if (!mLastInnerInsets.equals(mInnerInsets)) {
            // If the inner insets have changed, we need to dispatch this down to
            // the app's fitSystemWindows().  We do this before measuring the content
            // view to keep the same semantics as the normal fitSystemWindows() call.
            mLastInnerInsets.set(mInnerInsets);
            super.fitSystemWindows(mInnerInsets);
        }

        measureChildWithMargins(mContent, widthMeasureSpec, 0, heightMeasureSpec, 0);
        lp = (LayoutParams) mContent.getLayoutParams();
        maxWidth = Math.max(maxWidth,
                mContent.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
        maxHeight = Math.max(maxHeight,
                mContent.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        childState = combineMeasuredStates(childState, mContent.getMeasuredState());

        // Account for padding too
        maxWidth += getPaddingLeft() + getPaddingRight();
        maxHeight += getPaddingTop() + getPaddingBottom();

        // Check against our minimum height and width
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int count = getChildCount();

        final int parentLeft = getPaddingLeft();
        final int parentRight = right - left - getPaddingRight();

        final int parentTop = getPaddingTop();
        final int parentBottom = bottom - top - getPaddingBottom();

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();

                int childLeft = parentLeft + lp.leftMargin;
                int childTop;
                if (child == mActionBarBottom) {
                    childTop = parentBottom - height - lp.bottomMargin;
                } else {
                    childTop = parentTop + lp.topMargin;
                }

                child.layout(childLeft, childTop, childLeft + width, childTop + height);
            }
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    void pullChildren() {
        if (mContent == null) {
            mContent = findViewById(com.android.internal.R.id.content);
            mActionBarTop = findViewById(com.android.internal.R.id.top_action_bar);
            mContainerView = (ActionBarContainer)findViewById(
                    com.android.internal.R.id.action_bar_container);
            mActionView = (ActionBarView) findViewById(com.android.internal.R.id.action_bar);
            mActionBarBottom = findViewById(com.android.internal.R.id.split_action_bar);
        }
    }


    public static class LayoutParams extends MarginLayoutParams {
        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.MarginLayoutParams source) {
            super(source);
        }
    }
}
