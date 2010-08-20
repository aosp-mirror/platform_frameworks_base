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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * @hide
 */
public class ActionMenuView extends LinearLayout implements MenuBuilder.ItemInvoker, MenuView {
    private static final String TAG = "ActionMenuView";
    
    private MenuBuilder mMenu;

    private int mItemPadding;
    private int mItemMargin;
    private int mMaxItems;
    private boolean mReserveOverflow;
    private OverflowMenuButton mOverflowButton;
    private WeakReference<MenuPopupHelper> mOverflowPopup;
    
    public ActionMenuView(Context context) {
        this(context, null);
    }
    
    public ActionMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Theme);
        mItemPadding = a.getDimensionPixelOffset(
                com.android.internal.R.styleable.Theme_actionButtonPadding, 0);
        mItemMargin = mItemPadding / 2;
        a.recycle();

        // Measure for initial configuration
        mMaxItems = measureMaxActionButtons();

        // TODO There has to be a better way to indicate that we don't have a hard menu key.
        final int screen = getResources().getConfiguration().screenLayout;
        mReserveOverflow = (screen & Configuration.SCREENLAYOUT_SIZE_MASK) ==
                Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        final int screen = newConfig.screenLayout;
        mReserveOverflow = (screen & Configuration.SCREENLAYOUT_SIZE_MASK) ==
                Configuration.SCREENLAYOUT_SIZE_XLARGE;
        mMaxItems = measureMaxActionButtons();
        if (mMenu != null) {
            mMenu.setMaxActionItems(mMaxItems);
            updateChildren(false);
        }
    }

    private int measureMaxActionButtons() {
        final Resources res = getResources();
        final int size = res.getDimensionPixelSize(com.android.internal.R.dimen.action_icon_size);
        final int spaceAvailable = res.getDisplayMetrics().widthPixels / 2;
        final int itemSpace = size + mItemPadding;
        
        return spaceAvailable / (itemSpace > 0 ? itemSpace : 1);
    }

    public boolean isOverflowReserved() {
        return mReserveOverflow;
    }
    
    public void setOverflowReserved(boolean reserveOverflow) {
        mReserveOverflow = reserveOverflow;
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) {
            LayoutParams lp = (LayoutParams) p;
            return lp.leftMargin == mItemMargin && lp.rightMargin == mItemMargin &&
                    lp.gravity == Gravity.CENTER_VERTICAL &&
                    lp.width == LayoutParams.WRAP_CONTENT && lp.height == LayoutParams.WRAP_CONTENT;
        }
        return false;
    }
    
    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        params.leftMargin = mItemMargin;
        params.rightMargin = mItemMargin;
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }
    
    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return generateDefaultLayoutParams();
    }
    
    public int getItemMargin() {
        return mItemMargin;
    }

    public boolean invokeItem(MenuItemImpl item) {
        return mMenu.performItemAction(item, 0);
    }

    public int getWindowAnimations() {
        return 0;
    }

    public void initialize(MenuBuilder menu, int menuType) {
        menu.setMaxActionItems(mMaxItems);
        mMenu = menu;
        updateChildren(true);
    }

    public void updateChildren(boolean cleared) {
        final boolean reserveOverflow = mReserveOverflow;
        removeAllViews();
        
        final ArrayList<MenuItemImpl> itemsToShow = mMenu.getActionItems(reserveOverflow);
        final int itemCount = itemsToShow.size();
        
        for (int i = 0; i < itemCount; i++) {
            final MenuItemImpl itemData = itemsToShow.get(i);
            addItemView((ActionMenuItemView) itemData.getItemView(MenuBuilder.TYPE_ACTION_BUTTON,
                    this));
        }

        if (reserveOverflow) {
            if (mMenu.getNonActionItems(true).size() > 0) {
                OverflowMenuButton button = new OverflowMenuButton(mContext);
                addView(button);
                mOverflowButton = button;
            } else {
                mOverflowButton = null;
            }
        }
    }

    public boolean showOverflowMenu() {
        if (mOverflowButton != null) {
            final MenuPopupHelper popup =
                    new MenuPopupHelper(getContext(), mMenu, mOverflowButton, true);
            // Post this for later; we might still need a layout for the anchor to be right.
            post(new Runnable() {
                public void run() {
                    popup.show();
                }
            });
            mOverflowPopup = new WeakReference<MenuPopupHelper>(popup);
            return true;
        }
        return false;
    }

    public boolean isOverflowMenuShowing() {
        MenuPopupHelper popup = mOverflowPopup != null ? mOverflowPopup.get() : null;
        if (popup != null) {
            return popup.isShowing();
        }
        return false;
    }

    public boolean hideOverflowMenu() {
        MenuPopupHelper popup = mOverflowPopup != null ? mOverflowPopup.get() : null;
        if (popup != null) {
            popup.dismiss();
            return true;
        }
        return false;
    }

    private void addItemView(ActionMenuItemView view) {
        view.setItemInvoker(this);
        addView(view);
    }

    private class OverflowMenuButton extends ImageButton {
        public OverflowMenuButton(Context context) {
            super(context, null, com.android.internal.R.attr.actionButtonStyle);

            final Resources res = context.getResources();
            setClickable(true);
            setFocusable(true);
            setContentDescription(res.getString(com.android.internal.R.string.more_item_label));
            setImageDrawable(res.getDrawable(com.android.internal.R.drawable.ic_menu_more));
            setVisibility(VISIBLE);
            setEnabled(true);
        }

        @Override
        public boolean performClick() {
            if (super.performClick()) {
                return true;
            }

            // Change to overflow mode
            mMenu.getCallback().onMenuModeChange(mMenu);
            return true;
        }
    }
}
