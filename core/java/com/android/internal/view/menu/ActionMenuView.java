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
import android.util.AttributeSet;
import android.widget.LinearLayout;

import java.util.ArrayList;

/**
 * @hide
 */
public class ActionMenuView extends LinearLayout implements MenuBuilder.ItemInvoker, MenuView {
    private static final String TAG = "ActionMenuView";
    
    // TODO: Make this a ViewConfiguration constant.
    private static final int MAX_ACTION_ITEMS = 3;
    
    private MenuBuilder mMenu;
    
    public ActionMenuView(Context context) {
        this(context, null);
    }
    
    public ActionMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean invokeItem(MenuItemImpl item) {
        return mMenu.performItemAction(item, 0);
    }

    public int getWindowAnimations() {
        return 0;
    }

    public void initialize(MenuBuilder menu, int menuType) {
        menu.setMaxActionItems(MAX_ACTION_ITEMS);
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
