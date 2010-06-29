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
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.LinearLayout;

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
        
        final Resources res = getResources();
        final int size = res.getDimensionPixelSize(com.android.internal.R.dimen.action_icon_size);
        final int spaceAvailable = res.getDisplayMetrics().widthPixels / 2;
        final int itemSpace = size + mItemPadding;
        
        mMaxItems = spaceAvailable / (itemSpace > 0 ? itemSpace : 1);
    }
    
    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) {
            LayoutParams lp = (LayoutParams) p;
            return lp.leftMargin == mItemMargin && lp.rightMargin == mItemMargin &&
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
        removeAllViews();
        
        final ArrayList<MenuItemImpl> itemsToShow = mMenu.getActionItems();
        final int itemCount = itemsToShow.size();
        
        for (int i = 0; i < itemCount; i++) {
            final MenuItemImpl itemData = itemsToShow.get(i);
            addItemView((ActionMenuItemView) itemData.getItemView(MenuBuilder.TYPE_ACTION_BUTTON,
                    this));
        }
    }

    private void addItemView(ActionMenuItemView view) {
        view.setItemInvoker(this);
        addView(view);
    }
}
