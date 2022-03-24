/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.PopupWindow;

import com.android.internal.R;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.widget.floatingtoolbar.FloatingToolbar;

import java.util.Arrays;
import java.util.Objects;

public final class FloatingActionMode extends ActionMode {

    private static final int MAX_HIDE_DURATION = 3000;
    private static final int MOVING_HIDE_DELAY = 50;

    @NonNull private final Context mContext;
    @NonNull private final ActionMode.Callback2 mCallback;
    @NonNull private final MenuBuilder mMenu;
    @NonNull private final Rect mContentRect;
    @NonNull private final Rect mContentRectOnScreen;
    @NonNull private final Rect mPreviousContentRectOnScreen;
    @NonNull private final int[] mViewPositionOnScreen;
    @NonNull private final int[] mPreviousViewPositionOnScreen;
    @NonNull private final int[] mRootViewPositionOnScreen;
    @NonNull private final Rect mViewRectOnScreen;
    @NonNull private final Rect mPreviousViewRectOnScreen;
    @NonNull private final Rect mScreenRect;
    @NonNull private final View mOriginatingView;
    @NonNull private final Point mDisplaySize;
    private final int mBottomAllowance;

    private final Runnable mMovingOff = new Runnable() {
        public void run() {
            if (isViewStillActive()) {
                mFloatingToolbarVisibilityHelper.setMoving(false);
                mFloatingToolbarVisibilityHelper.updateToolbarVisibility();
            }
        }
    };

    private final Runnable mHideOff = new Runnable() {
        public void run() {
            if (isViewStillActive()) {
                mFloatingToolbarVisibilityHelper.setHideRequested(false);
                mFloatingToolbarVisibilityHelper.updateToolbarVisibility();
            }
        }
    };

    @NonNull private FloatingToolbar mFloatingToolbar;
    @NonNull private FloatingToolbarVisibilityHelper mFloatingToolbarVisibilityHelper;

    public FloatingActionMode(
            Context context, ActionMode.Callback2 callback,
            View originatingView, FloatingToolbar floatingToolbar) {
        mContext = Objects.requireNonNull(context);
        mCallback = Objects.requireNonNull(callback);
        mMenu = new MenuBuilder(context).setDefaultShowAsAction(
                MenuItem.SHOW_AS_ACTION_IF_ROOM);
        setType(ActionMode.TYPE_FLOATING);
        mMenu.setCallback(new MenuBuilder.Callback() {
            @Override
            public void onMenuModeChange(MenuBuilder menu) {}

            @Override
            public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
                return mCallback.onActionItemClicked(FloatingActionMode.this, item);
            }
        });
        mContentRect = new Rect();
        mContentRectOnScreen = new Rect();
        mPreviousContentRectOnScreen = new Rect();
        mViewPositionOnScreen = new int[2];
        mPreviousViewPositionOnScreen = new int[2];
        mRootViewPositionOnScreen = new int[2];
        mViewRectOnScreen = new Rect();
        mPreviousViewRectOnScreen = new Rect();
        mScreenRect = new Rect();
        mOriginatingView = Objects.requireNonNull(originatingView);
        mOriginatingView.getLocationOnScreen(mViewPositionOnScreen);
        // Allow the content rect to overshoot a little bit beyond the
        // bottom view bound if necessary.
        mBottomAllowance = context.getResources()
                .getDimensionPixelSize(R.dimen.content_rect_bottom_clip_allowance);
        mDisplaySize = new Point();
        setFloatingToolbar(Objects.requireNonNull(floatingToolbar));
    }

    private void setFloatingToolbar(FloatingToolbar floatingToolbar) {
        mFloatingToolbar = floatingToolbar
                .setMenu(mMenu)
                .setOnMenuItemClickListener(item -> mMenu.performItemAction(item, 0));
        mFloatingToolbarVisibilityHelper = new FloatingToolbarVisibilityHelper(mFloatingToolbar);
        mFloatingToolbarVisibilityHelper.activate();
    }

    @Override
    public void setTitle(CharSequence title) {}

    @Override
    public void setTitle(int resId) {}

    @Override
    public void setSubtitle(CharSequence subtitle) {}

    @Override
    public void setSubtitle(int resId) {}

    @Override
    public void setCustomView(View view) {}

    @Override
    public void invalidate() {
        mCallback.onPrepareActionMode(this, mMenu);
        invalidateContentRect();  // Will re-layout and show the toolbar if necessary.
    }

    @Override
    public void invalidateContentRect() {
        mCallback.onGetContentRect(this, mOriginatingView, mContentRect);
        updateViewLocationInWindow(/* forceRepositionToolbar= */ true);
    }

    public void updateViewLocationInWindow() {
        updateViewLocationInWindow(/* forceRepositionToolbar= */ false);
    }

    private void updateViewLocationInWindow(boolean forceRepositionToolbar) {
        mOriginatingView.getLocationOnScreen(mViewPositionOnScreen);
        mOriginatingView.getRootView().getLocationOnScreen(mRootViewPositionOnScreen);
        mOriginatingView.getGlobalVisibleRect(mViewRectOnScreen);
        mViewRectOnScreen.offset(mRootViewPositionOnScreen[0], mRootViewPositionOnScreen[1]);

        if (forceRepositionToolbar
                || !Arrays.equals(mViewPositionOnScreen, mPreviousViewPositionOnScreen)
                || !mViewRectOnScreen.equals(mPreviousViewRectOnScreen)) {
            repositionToolbar();
            mPreviousViewPositionOnScreen[0] = mViewPositionOnScreen[0];
            mPreviousViewPositionOnScreen[1] = mViewPositionOnScreen[1];
            mPreviousViewRectOnScreen.set(mViewRectOnScreen);
        }
    }

    private void repositionToolbar() {
        mContentRectOnScreen.set(mContentRect);

        // Offset the content rect into screen coordinates, taking into account any transformations
        // that may be applied to the originating view or its ancestors.
        final ViewParent parent = mOriginatingView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).getChildVisibleRect(
                    mOriginatingView, mContentRectOnScreen,
                    null /* offset */, true /* forceParentCheck */);
            mContentRectOnScreen.offset(mRootViewPositionOnScreen[0], mRootViewPositionOnScreen[1]);
        } else {
            mContentRectOnScreen.offset(mViewPositionOnScreen[0], mViewPositionOnScreen[1]);
        }

        if (isContentRectWithinBounds()) {
            mFloatingToolbarVisibilityHelper.setOutOfBounds(false);
            // Make sure that content rect is not out of the view's visible bounds.
            mContentRectOnScreen.set(
                    Math.max(mContentRectOnScreen.left, mViewRectOnScreen.left),
                    Math.max(mContentRectOnScreen.top, mViewRectOnScreen.top),
                    Math.min(mContentRectOnScreen.right, mViewRectOnScreen.right),
                    Math.min(mContentRectOnScreen.bottom,
                            mViewRectOnScreen.bottom + mBottomAllowance));

            if (!mContentRectOnScreen.equals(mPreviousContentRectOnScreen)) {
                // Content rect is moving
                if (!mPreviousContentRectOnScreen.isEmpty()) {
                    mOriginatingView.removeCallbacks(mMovingOff);
                    mFloatingToolbarVisibilityHelper.setMoving(true);
                    mOriginatingView.postDelayed(mMovingOff, MOVING_HIDE_DELAY);
                } else {
                    // mPreviousContentRectOnScreen is empty. That means we are are showing the
                    // toolbar rather than moving it. And we should show it right away.
                }

                mFloatingToolbar.setContentRect(mContentRectOnScreen);
                mFloatingToolbar.updateLayout();
            }
        } else {
            mFloatingToolbarVisibilityHelper.setOutOfBounds(true);
            mContentRectOnScreen.setEmpty();
        }
        mFloatingToolbarVisibilityHelper.updateToolbarVisibility();

        mPreviousContentRectOnScreen.set(mContentRectOnScreen);
    }

    private boolean isContentRectWithinBounds() {
        mContext.getDisplayNoVerify().getRealSize(mDisplaySize);
        mScreenRect.set(0, 0, mDisplaySize.x, mDisplaySize.y);

        return intersectsClosed(mContentRectOnScreen, mScreenRect)
            && intersectsClosed(mContentRectOnScreen, mViewRectOnScreen);
    }

    /*
     * Same as Rect.intersects, but includes cases where the rectangles touch.
    */
    private static boolean intersectsClosed(Rect a, Rect b) {
         return a.left <= b.right && b.left <= a.right
                 && a.top <= b.bottom && b.top <= a.bottom;
    }

    @Override
    public void hide(long duration) {
        if (duration == ActionMode.DEFAULT_HIDE_DURATION) {
            duration = ViewConfiguration.getDefaultActionModeHideDuration();
        }
        duration = Math.min(MAX_HIDE_DURATION, duration);
        mOriginatingView.removeCallbacks(mHideOff);
        if (duration <= 0) {
            mHideOff.run();
        } else {
            mFloatingToolbarVisibilityHelper.setHideRequested(true);
            mFloatingToolbarVisibilityHelper.updateToolbarVisibility();
            mOriginatingView.postDelayed(mHideOff, duration);
        }
    }

    /**
     * If this is set to true, the action mode view will dismiss itself on touch events outside of
     * its window. This only makes sense if the action mode view is a PopupWindow that is touchable
     * but not focusable, which means touches outside of the window will be delivered to the window
     * behind. The default is false.
     *
     * This is for internal use only and the approach to this may change.
     * @hide
     *
     * @param outsideTouchable whether or not this action mode is "outside touchable"
     * @param onDismiss optional. Sets a callback for when this action mode popup dismisses itself
     */
    public void setOutsideTouchable(
            boolean outsideTouchable, @Nullable PopupWindow.OnDismissListener onDismiss) {
        mFloatingToolbar.setOutsideTouchable(outsideTouchable, onDismiss);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        mFloatingToolbarVisibilityHelper.setWindowFocused(hasWindowFocus);
        mFloatingToolbarVisibilityHelper.updateToolbarVisibility();
    }

    @Override
    public void finish() {
        reset();
        mCallback.onDestroyActionMode(this);
    }

    @Override
    public Menu getMenu() {
        return mMenu;
    }

    @Override
    public CharSequence getTitle() {
        return null;
    }

    @Override
    public CharSequence getSubtitle() {
        return null;
    }

    @Override
    public View getCustomView() {
        return null;
    }

    @Override
    public MenuInflater getMenuInflater() {
        return new MenuInflater(mContext);
    }

    private void reset() {
        mFloatingToolbar.dismiss();
        mFloatingToolbarVisibilityHelper.deactivate();
        mOriginatingView.removeCallbacks(mMovingOff);
        mOriginatingView.removeCallbacks(mHideOff);
    }

    private boolean isViewStillActive() {
        return mOriginatingView.getWindowVisibility() == View.VISIBLE
                && mOriginatingView.isShown();
    }

    /**
     * A helper for showing/hiding the floating toolbar depending on certain states.
     */
    private static final class FloatingToolbarVisibilityHelper {

        private static final long MIN_SHOW_DURATION_FOR_MOVE_HIDE = 500;

        private final FloatingToolbar mToolbar;

        private boolean mHideRequested;
        private boolean mMoving;
        private boolean mOutOfBounds;
        private boolean mWindowFocused = true;

        private boolean mActive;

        private long mLastShowTime;

        public FloatingToolbarVisibilityHelper(FloatingToolbar toolbar) {
            mToolbar = Objects.requireNonNull(toolbar);
        }

        public void activate() {
            mHideRequested = false;
            mMoving = false;
            mOutOfBounds = false;
            mWindowFocused = true;

            mActive = true;
        }

        public void deactivate() {
            mActive = false;
            mToolbar.dismiss();
        }

        public void setHideRequested(boolean hide) {
            mHideRequested = hide;
        }

        public void setMoving(boolean moving) {
            // Avoid unintended flickering by allowing the toolbar to show long enough before
            // triggering the 'moving' flag - which signals a hide.
            final boolean showingLongEnough =
                System.currentTimeMillis() - mLastShowTime > MIN_SHOW_DURATION_FOR_MOVE_HIDE;
            if (!moving || showingLongEnough) {
                mMoving = moving;
            }
        }

        public void setOutOfBounds(boolean outOfBounds) {
            mOutOfBounds = outOfBounds;
        }

        public void setWindowFocused(boolean windowFocused) {
            mWindowFocused = windowFocused;
        }

        public void updateToolbarVisibility() {
            if (!mActive) {
                return;
            }

            if (mHideRequested || mMoving || mOutOfBounds || !mWindowFocused) {
                mToolbar.hide();
            } else {
                mToolbar.show();
                mLastShowTime = System.currentTimeMillis();
            }
        }
    }
}
