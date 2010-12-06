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
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;

/**
 * @hide
 */
public class ActionMenuView extends LinearLayout implements MenuBuilder.ItemInvoker, MenuView {
    private static final String TAG = "ActionMenuView";

    // TODO Theme/style this.
    private static final int DIVIDER_PADDING = 12; // dips
    
    private MenuBuilder mMenu;

    private int mMaxItems;
    private boolean mReserveOverflow;
    private OverflowMenuButton mOverflowButton;
    private MenuPopupHelper mOverflowPopup;
    
    private float mButtonPaddingLeft;
    private float mButtonPaddingRight;
    private float mDividerPadding;
    
    private Drawable mDivider;

    private Runnable mShowOverflow = new Runnable() {
        public void run() {
            showOverflowMenu();
        }
    };
    
    private class OpenOverflowRunnable implements Runnable {
        private MenuPopupHelper mPopup;

        public OpenOverflowRunnable(MenuPopupHelper popup) {
            mPopup = popup;
        }

        public void run() {
            mOverflowPopup = mPopup;
            mPopup.show();
            mPostedOpenRunnable = null;
        }
    }

    private OpenOverflowRunnable mPostedOpenRunnable;

    public ActionMenuView(Context context) {
        this(context, null);
    }
    
    public ActionMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        final Resources res = getResources();

        // Measure for initial configuration
        mMaxItems = getMaxActionButtons();

        // TODO There has to be a better way to indicate that we don't have a hard menu key.
        final int screen = res.getConfiguration().screenLayout;
        mReserveOverflow = (screen & Configuration.SCREENLAYOUT_SIZE_MASK) ==
                Configuration.SCREENLAYOUT_SIZE_XLARGE;
        
        TypedArray a = context.obtainStyledAttributes(com.android.internal.R.styleable.Theme);
        final int buttonStyle = a.getResourceId(
                com.android.internal.R.styleable.Theme_actionButtonStyle, 0);
        mDivider = a.getDrawable(com.android.internal.R.styleable.Theme_dividerVertical);
        a.recycle();
        
        a = context.obtainStyledAttributes(buttonStyle, com.android.internal.R.styleable.View);
        mButtonPaddingLeft = a.getDimension(com.android.internal.R.styleable.View_paddingLeft, 0);
        mButtonPaddingRight = a.getDimension(com.android.internal.R.styleable.View_paddingRight, 0);
        a.recycle();
        
        mDividerPadding = DIVIDER_PADDING * res.getDisplayMetrics().density;

        setBaselineAligned(false);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final int screen = newConfig.screenLayout;
        mReserveOverflow = (screen & Configuration.SCREENLAYOUT_SIZE_MASK) ==
                Configuration.SCREENLAYOUT_SIZE_XLARGE;
        mMaxItems = getMaxActionButtons();
        if (mMenu != null) {
            mMenu.setMaxActionItems(mMaxItems);
            updateChildren(false);
        }

        if (mOverflowPopup != null && mOverflowPopup.isShowing()) {
            mOverflowPopup.dismiss();
            post(mShowOverflow);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mOverflowPopup != null && mOverflowPopup.isShowing()) {
            mOverflowPopup.dismiss();
        }
    }

    private int getMaxActionButtons() {
        return getResources().getInteger(com.android.internal.R.integer.max_action_buttons);
    }

    public boolean isOverflowReserved() {
        return mReserveOverflow;
    }
    
    public void setOverflowReserved(boolean reserveOverflow) {
        mReserveOverflow = reserveOverflow;
    }
    
    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }
    
    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return generateDefaultLayoutParams();
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
        
        boolean needsDivider = false;
        for (int i = 0; i < itemCount; i++) {
            if (needsDivider) {
                addView(makeDividerView(), makeDividerLayoutParams());
            }
            final MenuItemImpl itemData = itemsToShow.get(i);
            final View actionView = itemData.getActionView();
            if (actionView != null) {
                final ViewParent parent = actionView.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(actionView);
                }
                addView(actionView, makeActionViewLayoutParams());
            } else {
                needsDivider = addItemView(i == 0 || !needsDivider,
                        (ActionMenuItemView) itemData.getItemView(
                                MenuBuilder.TYPE_ACTION_BUTTON, this));
            }
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
        if (mOverflowButton != null && !isOverflowMenuShowing()) {
            mMenu.getCallback().onMenuModeChange(mMenu);
            return true;
        }
        return false;
    }

    public void openOverflowMenu() {
        OverflowPopup popup = new OverflowPopup(getContext(), mMenu, mOverflowButton, true);
        mPostedOpenRunnable = new OpenOverflowRunnable(popup);
        // Post this for later; we might still need a layout for the anchor to be right.
        post(mPostedOpenRunnable);
    }

    public boolean isOverflowMenuShowing() {
        return mOverflowPopup != null && mOverflowPopup.isShowing();
    }

    public boolean isOverflowMenuOpen() {
        return mOverflowPopup != null;
    }

    public boolean hideOverflowMenu() {
        if (mPostedOpenRunnable != null) {
            removeCallbacks(mPostedOpenRunnable);
            return true;
        }

        MenuPopupHelper popup = mOverflowPopup;
        if (popup != null) {
            popup.dismiss();
            return true;
        }
        return false;
    }

    private boolean addItemView(boolean needsDivider, ActionMenuItemView view) {
        view.setItemInvoker(this);
        boolean hasText = view.hasText();
        
        if (hasText && needsDivider) {
            addView(makeDividerView(), makeDividerLayoutParams());
        }
        addView(view);
        return hasText;
    }

    private ImageView makeDividerView() {
        ImageView result = new ImageView(mContext);
        result.setImageDrawable(mDivider);
        result.setScaleType(ImageView.ScaleType.FIT_XY);
        return result;
    }

    private LayoutParams makeDividerLayoutParams() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT);
        params.topMargin = (int) mDividerPadding;
        params.bottomMargin = (int) mDividerPadding;
        return params;
    }

    private LayoutParams makeActionViewLayoutParams() {
        LayoutParams params = generateDefaultLayoutParams();
        params.leftMargin = (int) mButtonPaddingLeft;
        params.rightMargin = (int) mButtonPaddingRight;
        return params;
    }

    private class OverflowMenuButton extends ImageButton {
        public OverflowMenuButton(Context context) {
            super(context, null, com.android.internal.R.attr.actionOverflowButtonStyle);

            setClickable(true);
            setFocusable(true);
            setVisibility(VISIBLE);
            setEnabled(true);
        }

        @Override
        public boolean performClick() {
            if (super.performClick()) {
                return true;
            }

            showOverflowMenu();
            return true;
        }
    }

    private class OverflowPopup extends MenuPopupHelper {
        public OverflowPopup(Context context, MenuBuilder menu, View anchorView,
                boolean overflowOnly) {
            super(context, menu, anchorView, overflowOnly);
        }

        @Override
        public void onDismiss() {
            super.onDismiss();
            mMenu.getCallback().onCloseMenu(mMenu, true);
            mOverflowPopup = null;
        }
    }
}
