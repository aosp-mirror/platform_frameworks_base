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

import android.content.Context;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.PopupWindow;

/**
 * Presents a menu as a small, simple popup anchored to another view.
 *
 * @hide
 */
public class MenuPopupHelper implements ViewTreeObserver.OnGlobalLayoutListener,
        PopupWindow.OnDismissListener, View.OnAttachStateChangeListener, MenuPresenter {
    private final Context mContext;
    private final MenuBuilder mMenu;
    private final boolean mOverflowOnly;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;

    private View mAnchorView;
    private MenuPopup mPopup;
    private ViewTreeObserver mTreeObserver;

    private int mDropDownGravity = Gravity.NO_GRAVITY;

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
            // TODO: Return a Cascading implementation of MenuPopup instead.
            return new StandardMenuPopup(
                    mContext, mMenu, mAnchorView, mPopupStyleAttr, mPopupStyleRes, mOverflowOnly);
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

        final View anchor = mAnchorView;
        if (anchor != null) {
            final boolean addGlobalListener = mTreeObserver == null;
            mTreeObserver = anchor.getViewTreeObserver(); // Refresh to latest
            if (addGlobalListener) mTreeObserver.addOnGlobalLayoutListener(this);
            anchor.addOnAttachStateChangeListener(this);
            mPopup.setAnchorView(anchor);
            mPopup.setGravity(mDropDownGravity);
        } else {
            return false;
        }

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
        if (mTreeObserver != null) {
            if (!mTreeObserver.isAlive()) mTreeObserver = mAnchorView.getViewTreeObserver();
            mTreeObserver.removeGlobalOnLayoutListener(this);
            mTreeObserver = null;
        }
        mAnchorView.removeOnAttachStateChangeListener(this);
    }

    public boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }

    @Override
    public void onGlobalLayout() {
        if (isShowing()) {
            final View anchor = mAnchorView;
            if (anchor == null || !anchor.isShown()) {
                dismiss();
            } else if (isShowing()) {
                // Recompute window size and position
                mPopup.show();
            }
        }
    }

    @Override
    public void onViewAttachedToWindow(View v) {
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        if (mTreeObserver != null) {
            if (!mTreeObserver.isAlive()) mTreeObserver = v.getViewTreeObserver();
            mTreeObserver.removeGlobalOnLayoutListener(this);
        }
        v.removeOnAttachStateChangeListener(this);
    }

    @Override
    public void initForMenu(Context context, MenuBuilder menu) {
        // Don't need to do anything; we added as a presenter in the constructor.
    }

    @Override
    public MenuView getMenuView(ViewGroup root) {
        throw new UnsupportedOperationException("MenuPopupHelpers manage their own views");
    }

    @Override
    public void updateMenuView(boolean cleared) {
        mPopup.updateMenuView(cleared);
    }

    @Override
    public void setCallback(Callback cb) {
        mPopup.setCallback(cb);
    }

    @Override
    public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
        return mPopup.onSubMenuSelected(subMenu);
    }

    @Override
    public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        mPopup.onCloseMenu(menu, allMenusAreClosing);
    }

    @Override
    public boolean flagActionItems() {
        return false;
    }

    @Override
    public boolean expandItemActionView(MenuBuilder menu, MenuItemImpl item) {
        return false;
    }

    @Override
    public boolean collapseItemActionView(MenuBuilder menu, MenuItemImpl item) {
        return false;
    }

    @Override
    public int getId() {
        return 0;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        return null;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
    }
}
