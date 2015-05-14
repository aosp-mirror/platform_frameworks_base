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

    private final Context mContext;
    private final ActionMode.Callback2 mCallback;
    private final MenuBuilder mMenu;
    private final Rect mContentRect;
    private final Rect mContentRectOnWindow;
    private final Rect mPreviousContentRectOnWindow;
    private final int[] mViewPosition;
    private final View mOriginatingView;
    private FloatingToolbar mFloatingToolbar;

    public FloatingActionMode(
            Context context, ActionMode.Callback2 callback, View originatingView) {
        mContext = context;
        mCallback = callback;
        mMenu = new MenuBuilder(context).setDefaultShowAsAction(
                MenuItem.SHOW_AS_ACTION_IF_ROOM);
        setType(ActionMode.TYPE_FLOATING);
        mContentRect = new Rect();
        mContentRectOnWindow = new Rect();
        mPreviousContentRectOnWindow = new Rect();
        mViewPosition = new int[2];
        mOriginatingView = originatingView;
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
        Preconditions.checkNotNull(mFloatingToolbar);
        mCallback.onPrepareActionMode(this, mMenu);
        mFloatingToolbar.updateLayout();
        invalidateContentRect();
    }

    @Override
    public void invalidateContentRect() {
        Preconditions.checkNotNull(mFloatingToolbar);
        mCallback.onGetContentRect(this, mOriginatingView, mContentRect);
        repositionToolbar();
    }

    public void updateViewLocationInWindow() {
        Preconditions.checkNotNull(mFloatingToolbar);
        mOriginatingView.getLocationInWindow(mViewPosition);
        repositionToolbar();
    }

    private void repositionToolbar() {
        mContentRectOnWindow.set(
                mContentRect.left + mViewPosition[0],
                mContentRect.top + mViewPosition[1],
                mContentRect.right + mViewPosition[0],
                mContentRect.bottom + mViewPosition[1]);
        if (!mContentRectOnWindow.equals(mPreviousContentRectOnWindow)) {
            mFloatingToolbar.setContentRect(mContentRectOnWindow);
            mFloatingToolbar.updateLayout();
        }
        mPreviousContentRectOnWindow.set(mContentRectOnWindow);
    }

    @Override
    public void finish() {
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

}
