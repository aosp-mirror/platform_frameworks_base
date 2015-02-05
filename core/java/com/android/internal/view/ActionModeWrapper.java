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
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.widget.ActionBarContextView;

/**
 * ActionMode implementation that wraps several actions modes and creates them on the fly depending
 * on the ActionMode type chosen by the client.
 */
public class ActionModeWrapper extends ActionMode {

    private ActionMode mActionMode;
    private final Context mContext;
    private MenuBuilder mMenu;
    private final ActionMode.Callback mCallback;
    private boolean mTypeLocked = false;

    private CharSequence mTitle;
    private CharSequence mSubtitle;
    private View mCustomView;

    // Fields for StandaloneActionMode
    private ActionBarContextView mActionModeView;
    private boolean mIsFocusable;

    public ActionModeWrapper(Context context, ActionMode.Callback callback) {
        mContext = context;
        mMenu = new MenuBuilder(context).setDefaultShowAsAction(
                MenuItem.SHOW_AS_ACTION_IF_ROOM);
        mCallback = callback;
    }

    @Override
    public void setTitle(CharSequence title) {
        if (mActionMode != null) {
            mActionMode.setTitle(title);
        } else {
            mTitle = title;
        }
    }

    @Override
    public void setTitle(int resId) {
        if (mActionMode != null) {
            mActionMode.setTitle(resId);
        } else {
            mTitle = resId != 0 ? mContext.getString(resId) : null;
        }
    }

    @Override
    public void setSubtitle(CharSequence subtitle) {
        if (mActionMode != null) {
            mActionMode.setSubtitle(subtitle);
        } else {
            mSubtitle = subtitle;
        }
    }

    @Override
    public void setSubtitle(int resId) {
        if (mActionMode != null) {
            mActionMode.setSubtitle(resId);
        } else {
            mSubtitle = resId != 0 ? mContext.getString(resId) : null;
        }
    }

    @Override
    public void setCustomView(View view) {
        if (mActionMode != null) {
            mActionMode.setCustomView(view);
        } else {
            mCustomView = view;
        }
    }

    /**
     * Set the current type as final and create the necessary ActionMode. After this call, any
     * changes to the ActionMode type will be ignored.
     */
    public void lockType() {
        mTypeLocked = true;
        switch (getType()) {
            case ActionMode.TYPE_PRIMARY:
            default:
                mActionMode = new StandaloneActionMode(
                        mActionModeView.getContext(),
                        mActionModeView, mCallback, mIsFocusable, mMenu);
                break;
            case ActionMode.TYPE_FLOATING:
                // Not implemented yet.
                break;
        }

        if (mActionMode == null) {
            return;
        }

        mActionMode.setTitle(mTitle);
        mActionMode.setSubtitle(mSubtitle);
        if (mCustomView != null) {
            mActionMode.setCustomView(mCustomView);
        }

        mTitle = null;
        mSubtitle = null;
        mCustomView = null;
    }

    @Override
    public void setType(int type) {
        if (!mTypeLocked) {
            super.setType(type);
        } else {
            throw new IllegalStateException(
                    "You can't change the ActionMode's type after onCreateActionMode.");
        }
    }

    @Override
    public void invalidate() {
        if (mActionMode != null) {
            mActionMode.invalidate();
        }
    }

    @Override
    public void finish() {
        if (mActionMode != null) {
            mActionMode.finish();
        }
    }

    @Override
    public Menu getMenu() {
        return mMenu;
    }

    @Override
    public CharSequence getTitle() {
        if (mActionMode != null) {
            return mActionMode.getTitle();
        }
        return mTitle;
    }

    @Override
    public CharSequence getSubtitle() {
        if (mActionMode != null) {
            return mActionMode.getSubtitle();
        }
        return mSubtitle;
    }

    @Override
    public View getCustomView() {
        if (mActionMode != null) {
            return mActionMode.getCustomView();
        }
        return mCustomView;
    }

    @Override
    public MenuInflater getMenuInflater() {
        return new MenuInflater(mContext);
    }

    public void setActionModeView(ActionBarContextView actionModeView) {
        mActionModeView = actionModeView;
    }

    public void setFocusable(boolean focusable) {
        mIsFocusable = focusable;
    }

}
