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

    private int mDropDownGravity = Gravity.START;
    private boolean mForceShowIcon;
    private boolean mShowTitle;
    private Callback mPresenterCallback;

    private int mInitXOffset;
    private int mInitYOffset;

    /** Whether the popup has anchor-relative offsets. */
    private boolean mHasOffsets;

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
        mForceShowIcon = forceShow;
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

    public void show(int x, int y) {
        if (!tryShow(x, y)) {
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

        mInitXOffset = 0;
        mInitYOffset = 0;
        mHasOffsets = false;
        mShowTitle = false;

        showPopup();
        return true;
    }

    /**
     * Shows the popup menu and makes a best-effort to anchor it to the
     * specified (x,y) coordinate relative to the anchor view.
     * <p>
     * If the popup's resolved gravity is {@link Gravity#LEFT}, this will
     * display the popup with its top-left corner at (x,y) relative to the
     * anchor view. If the resolved gravity is {@link Gravity#RIGHT}, the
     * popup's top-right corner will be at (x,y).
     * <p>
     * If the popup cannot be displayed fully on-screen, this method will
     * attempt to scroll the anchor view's ancestors and/or offset the popup
     * such that it may be displayed fully on-screen.
     *
     * @param x x coordinate relative to the anchor view
     * @param y y coordinate relative to the anchor view
     * @return {@code true} if the popup was shown or was already showing prior
     *         to calling this method, {@code false} otherwise
     */
    public boolean tryShow(int x, int y) {
        if (isShowing()) {
            return true;
        }

        if (mAnchorView == null) {
            return false;
        }

        mInitXOffset = x;
        mInitYOffset = y;
        mHasOffsets = true;
        mShowTitle = true;

        showPopup();
        return true;
    }

    private void showPopup() {
        mPopup = createMenuPopup();
        mPopup.setAnchorView(mAnchorView);
        mPopup.setCallback(mPresenterCallback);
        mPopup.setForceShowIcon(mForceShowIcon);
        mPopup.setGravity(mDropDownGravity);
        mPopup.setShowTitle(mShowTitle);

        if (mHasOffsets) {
            // If the resolved drop-down gravity is RIGHT, the popup's right
            // edge will be aligned with the anchor view. Adjust by the anchor
            // width such that the top-right corner is at the X offset.
            final int hgrav = Gravity.getAbsoluteGravity(mDropDownGravity,
                    mAnchorView.getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
            final int resolvedXOffset;
            if (hgrav == Gravity.RIGHT) {
                resolvedXOffset = mInitXOffset - mAnchorView.getWidth();
            } else {
                resolvedXOffset = mInitXOffset;
            }

            mPopup.setHorizontalOffset(resolvedXOffset);
            mPopup.setVerticalOffset(mInitYOffset);
        }

        // In order for subclasses of MenuPopupHelper to satisfy the OnDismissedListener interface,
        // we must set the listener to this outer Helper rather than to the inner MenuPopup.
        // Not to worry -- the inner MenuPopup will call our own #onDismiss method after it's done
        // its own handling.
        mPopup.setOnDismissListener(this);

        mPopup.addMenu(mMenu);
        mPopup.show();
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
