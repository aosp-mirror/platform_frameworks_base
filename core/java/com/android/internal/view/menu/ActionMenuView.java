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
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
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
    private int mWidthLimit;
    private boolean mReserveOverflow;
    private OverflowMenuButton mOverflowButton;
    private MenuPopupHelper mOverflowPopup;

    private float mDividerPadding;
    
    private Drawable mDivider;

    private final Runnable mShowOverflow = new Runnable() {
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
            if (mPopup.tryShow()) {
                mOverflowPopup = mPopup;
                mPostedOpenRunnable = null;
            }
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
        final Configuration config = res.getConfiguration();
        mReserveOverflow = config.isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_LARGE);
        mWidthLimit = res.getDisplayMetrics().widthPixels / 2;
        
        TypedArray a = context.obtainStyledAttributes(com.android.internal.R.styleable.Theme);
        mDivider = a.getDrawable(com.android.internal.R.styleable.Theme_dividerVertical);
        a.recycle();
        
        mDividerPadding = DIVIDER_PADDING * res.getDisplayMetrics().density;

        setBaselineAligned(false);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mReserveOverflow = newConfig.isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_LARGE);
        mMaxItems = getMaxActionButtons();
        mWidthLimit = getResources().getDisplayMetrics().widthPixels / 2;
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
        removeCallbacks(mShowOverflow);
        if (mPostedOpenRunnable != null) {
            removeCallbacks(mPostedOpenRunnable);
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
    
    public View getOverflowButton() {
        return mOverflowButton;
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
        if (p instanceof LayoutParams) {
            LayoutParams result = new LayoutParams((LayoutParams) p);
            if (result.gravity <= Gravity.NO_GRAVITY) {
                result.gravity = Gravity.CENTER_VERTICAL;
            }
            return result;
        }
        return generateDefaultLayoutParams();
    }

    public boolean invokeItem(MenuItemImpl item) {
        return mMenu.performItemAction(item, 0);
    }

    public int getWindowAnimations() {
        return 0;
    }

    public void initialize(MenuBuilder menu, int menuType) {
        int width = mWidthLimit;
        if (mReserveOverflow) {
            if (mOverflowButton == null) {
                OverflowMenuButton button = new OverflowMenuButton(mContext);
                mOverflowButton = button;
            }
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            mOverflowButton.measure(spec, spec);
            width -= mOverflowButton.getMeasuredWidth();
        }

        menu.setActionWidthLimit(width);

        menu.setMaxActionItems(mMaxItems);
        final boolean cleared = mMenu != menu;
        mMenu = menu;
        updateChildren(cleared);
    }

    public void updateChildren(boolean cleared) {
        final ArrayList<MenuItemImpl> itemsToShow = mMenu.getActionItems(mReserveOverflow);
        final int itemCount = itemsToShow.size();
        
        boolean needsDivider = false;
        int childIndex = 0;
        for (int i = 0; i < itemCount; i++) {
            final MenuItemImpl itemData = itemsToShow.get(i);
            boolean hasDivider = false;

            if (needsDivider) {
                if (!isDivider(getChildAt(childIndex))) {
                    addView(makeDividerView(), childIndex, makeDividerLayoutParams());
                }
                hasDivider = true;
                childIndex++;
            }

            View childToAdd = itemData.getActionView();
            boolean needsPreDivider = false;
            if (childToAdd != null) {
                childToAdd.setLayoutParams(makeActionViewLayoutParams(childToAdd));
            } else {
                ActionMenuItemView view = (ActionMenuItemView) itemData.getItemView(
                        MenuBuilder.TYPE_ACTION_BUTTON, this);
                view.setItemInvoker(this);
                needsPreDivider = i > 0 && !hasDivider && view.hasText() &&
                        itemData.getIcon() == null;
                needsDivider = view.hasText();
                childToAdd = view;
            }

            boolean addPreDivider = removeChildrenUntil(childIndex, childToAdd, needsPreDivider);

            if (addPreDivider) addView(makeDividerView(), childIndex, makeDividerLayoutParams());
            if (needsPreDivider) childIndex++;

            if (getChildAt(childIndex) != childToAdd) {
                addView(childToAdd, childIndex);
            }
            childIndex++;
        }

        final boolean hasOverflow = mOverflowButton != null && mOverflowButton.getParent() == this;
        final boolean needsOverflow = mReserveOverflow && mMenu.getNonActionItems(true).size() > 0;

        if (hasOverflow != needsOverflow) {
            if (needsOverflow) {
                if (mOverflowButton == null) {
                    OverflowMenuButton button = new OverflowMenuButton(mContext);
                    mOverflowButton = button;
                }
                boolean addDivider = removeChildrenUntil(childIndex, mOverflowButton, true);
                if (addDivider && itemCount > 0) {
                    addView(makeDividerView(), childIndex, makeDividerLayoutParams());
                    childIndex++;
                }
                addView(mOverflowButton, childIndex);
                childIndex++;
            } else {
                removeView(mOverflowButton);
            }
        } else {
            if (needsOverflow) {
                boolean overflowDivider = itemCount > 0;
                boolean addDivider = removeChildrenUntil(childIndex, mOverflowButton,
                        overflowDivider);
                if (addDivider && itemCount > 0) {
                    addView(makeDividerView(), childIndex, makeDividerLayoutParams());
                }
                if (overflowDivider) {
                    childIndex += 2;
                } else {
                    childIndex++;
                }
            }
        }

        while (getChildCount() > childIndex) {
            removeViewAt(childIndex);
        }
    }

    private boolean removeChildrenUntil(int start, View targetChild, boolean needsPreDivider) {
        final int childCount = getChildCount();
        boolean found = false;
        for (int i = start; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child == targetChild) {
                found = true;
                break;
            }
        }

        if (!found) {
            return needsPreDivider;
        }

        for (int i = start; i < getChildCount(); ) {
            final View child = getChildAt(i);
            if (needsPreDivider && isDivider(child)) {
                needsPreDivider = false;
                i++;
                continue;
            }
            if (child == targetChild) break;
            removeViewAt(i);
        }

        return needsPreDivider;
    }

    private static boolean isDivider(View v) {
        return v != null && v.getId() == com.android.internal.R.id.action_menu_divider;
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
        result.setId(com.android.internal.R.id.action_menu_divider);
        return result;
    }

    private LayoutParams makeDividerLayoutParams() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT);
        params.topMargin = (int) mDividerPadding;
        params.bottomMargin = (int) mDividerPadding;
        return params;
    }

    private LayoutParams makeActionViewLayoutParams(View view) {
        return generateLayoutParams(view.getLayoutParams());
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

            playSoundEffect(SoundEffectConstants.CLICK);
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
