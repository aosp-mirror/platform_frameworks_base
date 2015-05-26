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

import android.content.Context;
import android.graphics.Rect;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.android.internal.util.Preconditions;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.widget.FloatingToolbar;

public class FloatingActionMode extends ActionMode {

    private static final int MAX_SNOOZE_TIME = 3000;
    private static final int MOVING_HIDE_DELAY = 300;

    private final Context mContext;
    private final ActionMode.Callback2 mCallback;
    private final MenuBuilder mMenu;
    private final Rect mContentRect;
    private final Rect mContentRectOnWindow;
    private final Rect mPreviousContentRectOnWindow;
    private final int[] mViewPosition;
    private final Rect mViewRect;
    private final Rect mScreenRect;
    private final View mOriginatingView;

    private final Runnable mMovingOff = new Runnable() {
        public void run() {
            mFloatingToolbarVisibilityHelper.setMoving(false);
        }
    };

    private final Runnable mSnoozeOff = new Runnable() {
        public void run() {
            mFloatingToolbarVisibilityHelper.setSnoozed(false);
        }
    };

    private FloatingToolbar mFloatingToolbar;
    private FloatingToolbarVisibilityHelper mFloatingToolbarVisibilityHelper;

    public FloatingActionMode(
            Context context, ActionMode.Callback2 callback, View originatingView) {
        mContext = Preconditions.checkNotNull(context);
        mCallback = Preconditions.checkNotNull(callback);
        mMenu = new MenuBuilder(context).setDefaultShowAsAction(
                MenuItem.SHOW_AS_ACTION_IF_ROOM);
        setType(ActionMode.TYPE_FLOATING);
        mContentRect = new Rect();
        mContentRectOnWindow = new Rect();
        mPreviousContentRectOnWindow = new Rect();
        mViewPosition = new int[2];
        mViewRect = new Rect();
        mScreenRect = new Rect();
        mOriginatingView = Preconditions.checkNotNull(originatingView);
        mOriginatingView.getLocationInWindow(mViewPosition);
    }

    public void setFloatingToolbar(FloatingToolbar floatingToolbar) {
        mFloatingToolbar = floatingToolbar
                .setMenu(mMenu)
                .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                        @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        return mCallback.onActionItemClicked(FloatingActionMode.this, item);
                    }
                });
        mFloatingToolbarVisibilityHelper = new FloatingToolbarVisibilityHelper(mFloatingToolbar);
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
        checkToolbarInitialized();
        mCallback.onPrepareActionMode(this, mMenu);
        mFloatingToolbar.updateLayout();
        invalidateContentRect();
    }

    @Override
    public void invalidateContentRect() {
        checkToolbarInitialized();
        mCallback.onGetContentRect(this, mOriginatingView, mContentRect);
        repositionToolbar();
    }

    public void updateViewLocationInWindow() {
        checkToolbarInitialized();
        mOriginatingView.getLocationInWindow(mViewPosition);
        mOriginatingView.getGlobalVisibleRect(mViewRect);
        repositionToolbar();
    }

    private void repositionToolbar() {
        checkToolbarInitialized();
        mContentRectOnWindow.set(
                mContentRect.left + mViewPosition[0],
                mContentRect.top + mViewPosition[1],
                mContentRect.right + mViewPosition[0],
                mContentRect.bottom + mViewPosition[1]);
        if (!mContentRectOnWindow.equals(mPreviousContentRectOnWindow)) {
            if (!mPreviousContentRectOnWindow.isEmpty()) {
                notifyContentRectMoving();
            }
            mFloatingToolbar.setContentRect(mContentRectOnWindow);
            mFloatingToolbar.updateLayout();
        }
        mPreviousContentRectOnWindow.set(mContentRectOnWindow);

        if (isContentRectWithinBounds()) {
            mFloatingToolbarVisibilityHelper.setOutOfBounds(false);
        } else {
            mFloatingToolbarVisibilityHelper.setOutOfBounds(true);
        }
    }

    private boolean isContentRectWithinBounds() {
       mScreenRect.set(
           0,
           0,
           mContext.getResources().getDisplayMetrics().widthPixels,
           mContext.getResources().getDisplayMetrics().heightPixels);

       return Rect.intersects(mContentRectOnWindow, mScreenRect)
           && Rect.intersects(mContentRectOnWindow, mViewRect);
    }

    private void notifyContentRectMoving() {
        mOriginatingView.removeCallbacks(mMovingOff);
        mFloatingToolbarVisibilityHelper.setMoving(true);
        mOriginatingView.postDelayed(mMovingOff, MOVING_HIDE_DELAY);
    }

    @Override
    public void snooze(int snoozeTime) {
        checkToolbarInitialized();
        snoozeTime = Math.min(MAX_SNOOZE_TIME, snoozeTime);
        mOriginatingView.removeCallbacks(mSnoozeOff);
        if (snoozeTime <= 0) {
            mSnoozeOff.run();
        } else {
            mFloatingToolbarVisibilityHelper.setSnoozed(true);
            mOriginatingView.postDelayed(mSnoozeOff, snoozeTime);
        }
    }

    @Override
    public void finish() {
        checkToolbarInitialized();
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

    /**
     * @throws IlllegalStateException
     */
    private void checkToolbarInitialized() {
        Preconditions.checkState(mFloatingToolbar != null);
        Preconditions.checkState(mFloatingToolbarVisibilityHelper != null);
    }

    private void reset() {
        mOriginatingView.removeCallbacks(mMovingOff);
        mOriginatingView.removeCallbacks(mSnoozeOff);
    }


    /**
     * A helper that shows/hides the floating toolbar depending on certain states.
     */
    private static final class FloatingToolbarVisibilityHelper {

        private final FloatingToolbar mToolbar;

        private boolean mSnoozed;
        private boolean mMoving;
        private boolean mOutOfBounds;

        public FloatingToolbarVisibilityHelper(FloatingToolbar toolbar) {
            mToolbar = Preconditions.checkNotNull(toolbar);
        }

        public void setSnoozed(boolean snoozed) {
            mSnoozed = snoozed;
            updateToolbarVisibility();
        }

        public void setMoving(boolean moving) {
            mMoving = moving;
            updateToolbarVisibility();
        }

        public void setOutOfBounds(boolean outOfBounds) {
            mOutOfBounds = outOfBounds;
            updateToolbarVisibility();
        }

        private void updateToolbarVisibility() {
            if (mSnoozed || mMoving || mOutOfBounds) {
                mToolbar.hide();
            } else if (mToolbar.isHidden()) {
                mToolbar.show();
            }
        }
    }
}
