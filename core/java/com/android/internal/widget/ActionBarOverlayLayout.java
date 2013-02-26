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

import com.android.internal.app.ActionBarImpl;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Special layout for the containing of an overlay action bar (and its
 * content) to correctly handle fitting system windows when the content
 * has request that its layout ignore them.
 */
public class ActionBarOverlayLayout extends FrameLayout {
    private int mActionBarHeight;
    private ActionBarImpl mActionBar;
    private int mWindowVisibility = View.VISIBLE;
    private View mContent;
    private View mActionBarTop;
    private ActionBarContainer mContainerView;
    private ActionBarView mActionView;
    private View mActionBarBottom;
    private boolean mOverlayMode;
    private int mLastSystemUiVisibility;
    private final Rect mLocalInsets = new Rect();

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

    public void setOverlayMode(boolean mode) {
        mOverlayMode = mode;
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
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)view.getLayoutParams();
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

        int topSpace = 0;
        if (stable || mActionBarTop.getVisibility() == VISIBLE) {
            // This is the space needed on top of the window for the action bar.
            topSpace = mActionBarHeight;
        }
        if (mActionBar != null && mActionBar.hasNonEmbeddedTabs()) {
            View tabs = mContainerView.getTabContainer();
            if (tabs != null && (stable || tabs.getVisibility() == VISIBLE)) {
                // If tabs are not embedded, increase space on top to account for them.
                topSpace += mActionBarHeight;
            }
        }

        int bottomSpace = 0;
        if (mActionView.isSplitActionBar()) {
            if ((mActionBarBottom != null
                    && (stable || mActionBarBottom.getVisibility() == VISIBLE))) {
                // If action bar is split, adjust bottom insets for it.
                bottomSpace = mActionBarHeight;
            }
        }

        // If the window has not requested system UI layout flags, we need to
        // make sure its content is not being covered by system UI...  though it
        // will still be covered by the action bar since they have requested it to
        // overlay.
        boolean res = computeFitSystemWindows(insets, mLocalInsets);
        if (!mOverlayMode && !stable) {
            mLocalInsets.top += topSpace;
            mLocalInsets.bottom += bottomSpace;
        } else {
            insets.top += topSpace;
            insets.bottom += bottomSpace;
        }
        changed |= applyInsets(mContent, mLocalInsets, true, true, true, true);

        if (changed) {
            requestLayout();
        }

        super.fitSystemWindows(insets);
        return true;
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
}
