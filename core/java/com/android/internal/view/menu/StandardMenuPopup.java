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

package com.android.internal.view.menu;

import android.content.Context;
import android.content.res.Resources;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnKeyListener;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.MenuPopupWindow;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.PopupWindow.OnDismissListener;

import java.util.Objects;

/**
 * A standard menu popup in which when a submenu is opened, it replaces its parent menu in the
 * viewport.
 */
final class StandardMenuPopup extends MenuPopup implements OnDismissListener, OnItemClickListener,
        MenuPresenter, OnKeyListener {
    private static final int ITEM_LAYOUT = com.android.internal.R.layout.popup_menu_item_layout;

    private final Context mContext;

    private final MenuBuilder mMenu;
    private final MenuAdapter mAdapter;
    private final boolean mOverflowOnly;
    private final int mPopupMaxWidth;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;
    // The popup window is final in order to couple its lifecycle to the lifecycle of the
    // StandardMenuPopup.
    private final MenuPopupWindow mPopup;

    private final OnGlobalLayoutListener mGlobalLayoutListener = new OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            // Only move the popup if it's showing and non-modal. We don't want
            // to be moving around the only interactive window, since there's a
            // good chance the user is interacting with it.
            if (isShowing() && !mPopup.isModal()) {
                final View anchor = mShownAnchorView;
                if (anchor == null || !anchor.isShown()) {
                    dismiss();
                } else {
                    // Recompute window size and position
                    mPopup.show();
                }
            }
        }
    };

    private final OnAttachStateChangeListener mAttachStateChangeListener =
            new OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            if (mTreeObserver != null) {
                if (!mTreeObserver.isAlive()) mTreeObserver = v.getViewTreeObserver();
                mTreeObserver.removeGlobalOnLayoutListener(mGlobalLayoutListener);
            }
            v.removeOnAttachStateChangeListener(this);
        }
    };

    private PopupWindow.OnDismissListener mOnDismissListener;

    private View mAnchorView;
    private View mShownAnchorView;
    private Callback mPresenterCallback;
    private ViewTreeObserver mTreeObserver;

    /** Whether the popup has been dismissed. Once dismissed, it cannot be opened again. */
    private boolean mWasDismissed;

    /** Whether the cached content width value is valid. */
    private boolean mHasContentWidth;

    /** Cached content width. */
    private int mContentWidth;

    private int mDropDownGravity = Gravity.NO_GRAVITY;

    private boolean mShowTitle;

    public StandardMenuPopup(Context context, MenuBuilder menu, View anchorView, int popupStyleAttr,
            int popupStyleRes, boolean overflowOnly) {
        mContext = Objects.requireNonNull(context);
        mMenu = menu;
        mOverflowOnly = overflowOnly;
        final LayoutInflater inflater = LayoutInflater.from(context);
        mAdapter = new MenuAdapter(menu, inflater, mOverflowOnly, ITEM_LAYOUT);
        mPopupStyleAttr = popupStyleAttr;
        mPopupStyleRes = popupStyleRes;

        final Resources res = context.getResources();
        mPopupMaxWidth = Math.max(res.getDisplayMetrics().widthPixels / 2,
                res.getDimensionPixelSize(com.android.internal.R.dimen.config_prefDialogWidth));

        mAnchorView = anchorView;

        mPopup = new MenuPopupWindow(mContext, null, mPopupStyleAttr, mPopupStyleRes);

        // Present the menu using our context, not the menu builder's context.
        menu.addMenuPresenter(this, context);
    }

    @Override
    public void setForceShowIcon(boolean forceShow) {
        mAdapter.setForceShowIcon(forceShow);
    }

    @Override
    public void setGravity(int gravity) {
        mDropDownGravity = gravity;
    }

    private boolean tryShow() {
        if (isShowing()) {
            return true;
        }

        if (mWasDismissed || mAnchorView == null) {
            return false;
        }

        mShownAnchorView = mAnchorView;

        mPopup.setOnDismissListener(this);
        mPopup.setOnItemClickListener(this);
        mPopup.setAdapter(mAdapter);
        mPopup.setModal(true);

        final View anchor = mShownAnchorView;
        final boolean addGlobalListener = mTreeObserver == null;
        mTreeObserver = anchor.getViewTreeObserver(); // Refresh to latest
        if (addGlobalListener) {
            mTreeObserver.addOnGlobalLayoutListener(mGlobalLayoutListener);
        }
        anchor.addOnAttachStateChangeListener(mAttachStateChangeListener);
        mPopup.setAnchorView(anchor);
        mPopup.setDropDownGravity(mDropDownGravity);

        if (!mHasContentWidth) {
            mContentWidth = measureIndividualMenuWidth(mAdapter, null, mContext, mPopupMaxWidth);
            mHasContentWidth = true;
        }

        mPopup.setContentWidth(mContentWidth);
        mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        mPopup.setEpicenterBounds(getEpicenterBounds());
        mPopup.show();

        ListView listView = mPopup.getListView();
        listView.setOnKeyListener(this);

        if (mShowTitle && mMenu.getHeaderTitle() != null) {
            FrameLayout titleItemView =
                    (FrameLayout) LayoutInflater.from(mContext).inflate(
                            com.android.internal.R.layout.popup_menu_header_item_layout,
                            listView,
                            false);
            TextView titleView = (TextView) titleItemView.findViewById(
                    com.android.internal.R.id.title);
            if (titleView != null) {
                titleView.setText(mMenu.getHeaderTitle());
            }
            titleItemView.setEnabled(false);
            listView.addHeaderView(titleItemView, null, false);

            // Update to show the title.
            mPopup.show();
        }
        return true;
    }

    @Override
    public void show() {
        if (!tryShow()) {
            throw new IllegalStateException("StandardMenuPopup cannot be used without an anchor");
        }
    }

    @Override
    public void dismiss() {
        if (isShowing()) {
            mPopup.dismiss();
        }
    }

    @Override
    public void addMenu(MenuBuilder menu) {
        // No-op: standard implementation has only one menu which is set in the constructor.
    }

    @Override
    public boolean isShowing() {
        return !mWasDismissed && mPopup.isShowing();
    }

    @Override
    public void onDismiss() {
        mWasDismissed = true;
        mMenu.close();

        if (mTreeObserver != null) {
            if (!mTreeObserver.isAlive()) mTreeObserver = mShownAnchorView.getViewTreeObserver();
            mTreeObserver.removeGlobalOnLayoutListener(mGlobalLayoutListener);
            mTreeObserver = null;
        }
        mShownAnchorView.removeOnAttachStateChangeListener(mAttachStateChangeListener);

        if (mOnDismissListener != null) {
            mOnDismissListener.onDismiss();
        }
    }

    @Override
    public void updateMenuView(boolean cleared) {
        mHasContentWidth = false;

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void setCallback(Callback cb) {
        mPresenterCallback = cb;
    }

    @Override
    public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
        if (subMenu.hasVisibleItems()) {
            final MenuPopupHelper subPopup = new MenuPopupHelper(mContext, subMenu,
                    mShownAnchorView, mOverflowOnly, mPopupStyleAttr, mPopupStyleRes);
            subPopup.setPresenterCallback(mPresenterCallback);
            subPopup.setForceShowIcon(MenuPopup.shouldPreserveIconSpacing(subMenu));

            // Pass responsibility for handling onDismiss to the submenu.
            subPopup.setOnDismissListener(mOnDismissListener);
            mOnDismissListener = null;

            // Close this menu popup to make room for the submenu popup.
            mMenu.close(false /* closeAllMenus */);

            // Show the new sub-menu popup at the same location as this popup.
            int horizontalOffset = mPopup.getHorizontalOffset();
            final int verticalOffset = mPopup.getVerticalOffset();

            // As xOffset of parent menu popup is subtracted with Anchor width for Gravity.RIGHT,
            // So, again to display sub-menu popup in same xOffset, add the Anchor width.
            final int hgrav = Gravity.getAbsoluteGravity(mDropDownGravity,
                mAnchorView.getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (hgrav == Gravity.RIGHT) {
              horizontalOffset += mAnchorView.getWidth();
            }

            if (subPopup.tryShow(horizontalOffset, verticalOffset)) {
                if (mPresenterCallback != null) {
                    mPresenterCallback.onOpenSubMenu(subMenu);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        // Only care about the (sub)menu we're presenting.
        if (menu != mMenu) return;

        dismiss();
        if (mPresenterCallback != null) {
            mPresenterCallback.onCloseMenu(menu, allMenusAreClosing);
        }
    }

    @Override
    public boolean flagActionItems() {
        return false;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        return null;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
    }

    @Override
    public void setAnchorView(View anchor) {
        mAnchorView = anchor;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
            dismiss();
            return true;
        }
        return false;
    }

    @Override
    public void setOnDismissListener(OnDismissListener listener) {
        mOnDismissListener = listener;
    }

    @Override
    public ListView getListView() {
        return mPopup.getListView();
    }


    @Override
    public void setHorizontalOffset(int x) {
        mPopup.setHorizontalOffset(x);
    }

    @Override
    public void setVerticalOffset(int y) {
        mPopup.setVerticalOffset(y);
    }

    @Override
    public void setShowTitle(boolean showTitle) {
        mShowTitle = showTitle;
    }
}
