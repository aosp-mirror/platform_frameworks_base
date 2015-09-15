/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.view.menu;

import com.android.internal.view.menu.MenuPresenter.Callback;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;

/**
 * Presents a menu as a small, simple popup anchored to another view.
 *
 * @hide
 */
public class MenuPopupHelper implements PopupWindow.OnDismissListener {
    private final Context mContext;
    private final MenuBuilder mMenu;
    private final boolean mOverflowOnly;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;

    private View mAnchorView;
    private MenuPopup mPopup;

    private int mDropDownGravity = Gravity.NO_GRAVITY;
    private Callback mPresenterCallback;

    public MenuPopupHelper(Context context, MenuBuilder menu) {
        this(context, menu, null, false, com.android.internal.R.attr.popupMenuStyle, 0);
    }

    public MenuPopupHelper(Context context, MenuBuilder menu, View anchorView) {
        this(context, menu, anchorView, false, com.android.internal.R.attr.popupMenuStyle, 0);
    }

    public MenuPopupHelper(Context context, MenuBuilder menu, View anchorView,
            boolean overflowOnly, int popupStyleAttr) {
        this(context, menu, anchorView, overflowOnly, popupStyleAttr, 0);
    }

    public MenuPopupHelper(Context context, MenuBuilder menu, View anchorView,
            boolean overflowOnly, int popupStyleAttr, int popupStyleRes) {
        mContext = context;
        mMenu = menu;
        mOverflowOnly = overflowOnly;
        mPopupStyleAttr = popupStyleAttr;
        mPopupStyleRes = popupStyleRes;
        mAnchorView = anchorView;
        mPopup = createMenuPopup();
    }

    private MenuPopup createMenuPopup() {
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableCascadingSubmenus)) {
            return new CascadingMenuPopup(mContext, mAnchorView, mPopupStyleAttr, mPopupStyleRes,
                    mOverflowOnly);
        }
        return new StandardMenuPopup(
                mContext, mMenu, mAnchorView, mPopupStyleAttr, mPopupStyleRes, mOverflowOnly);
    }

    public void setAnchorView(View anchor) {
        mAnchorView = anchor;
        mPopup.setAnchorView(anchor);
    }

    public void setForceShowIcon(boolean forceShow) {
        mPopup.setForceShowIcon(forceShow);
    }

    public void setGravity(int gravity) {
        mDropDownGravity = gravity;
        mPopup.setGravity(gravity);
    }

    public int getGravity() {
        return mDropDownGravity;
    }

    public void show() {
        if (!tryShow()) {
            throw new IllegalStateException("MenuPopupHelper cannot be used without an anchor");
        }
    }

    public ShowableListMenu getPopup() {
        return mPopup;
    }

    /**
     * Attempts to show the popup anchored to the view specified by {@link #setAnchorView(View)}.
     *
     * @return {@code true} if the popup was shown or was already showing prior to calling this
     *         method, {@code false} otherwise
     */
    public boolean tryShow() {
        if (isShowing()) {
            return true;
        }

        if (mAnchorView == null) {
            return false;
        }

        mPopup = createMenuPopup();
        mPopup.setAnchorView(mAnchorView);
        mPopup.setGravity(mDropDownGravity);
        mPopup.setCallback(mPresenterCallback);

        // In order for subclasses of MenuPopupHelper to satisfy the OnDismissedListener interface,
        // we must set the listener to this outer Helper rather than to the inner MenuPopup.
        // Not to worry -- the inner MenuPopup will call our own #onDismiss method after it's done
        // its own handling.
        mPopup.setOnDismissListener(this);

        mPopup.addMenu(mMenu);
        mPopup.show();
        return true;
    }

    public void dismiss() {
        if (isShowing()) {
            mPopup.dismiss();
        }
    }

    @Override
    public void onDismiss() {
        mPopup = null;
    }

    public boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }


    public void setCallback(MenuPresenter.Callback cb) {
        mPresenterCallback = cb;
        mPopup.setCallback(cb);
    }
}
