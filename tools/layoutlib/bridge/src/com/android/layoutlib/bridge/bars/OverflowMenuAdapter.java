/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.bars;

import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuItemImpl;
import com.android.internal.view.menu.MenuView;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.ArrayList;

/**
 * Provides an adapter for Overflow menu popup. This is very similar to
 * {@code MenuPopupHelper.MenuAdapter}
 */
public class OverflowMenuAdapter extends BaseAdapter {

    private final MenuBuilder mMenu;
    private int mExpandedIndex = -1;
    private final Context context;

    public OverflowMenuAdapter(MenuBuilder menu, Context context) {
        mMenu = menu;
        findExpandedIndex();
        this.context = context;
    }

    @Override
    public int getCount() {
        ArrayList<MenuItemImpl> items = mMenu.getNonActionItems();
        if (mExpandedIndex < 0) {
            return items.size();
        }
        return items.size() - 1;
    }

    @Override
    public MenuItemImpl getItem(int position) {
        ArrayList<MenuItemImpl> items = mMenu.getNonActionItems();
        if (mExpandedIndex >= 0 && position >= mExpandedIndex) {
            position++;
        }
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        // Since a menu item's ID is optional, we'll use the position as an
        // ID for the item in the AdapterView
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            LayoutInflater mInflater = LayoutInflater.from(context);
            convertView = mInflater.inflate(com.android.internal.R.layout.popup_menu_item_layout,
                    parent, false);
        }

        MenuView.ItemView itemView = (MenuView.ItemView) convertView;
        itemView.initialize(getItem(position), 0);
        return convertView;
    }

    private void findExpandedIndex() {
        final MenuItemImpl expandedItem = mMenu.getExpandedItem();
        if (expandedItem != null) {
            final ArrayList<MenuItemImpl> items = mMenu.getNonActionItems();
            final int count = items.size();
            for (int i = 0; i < count; i++) {
                final MenuItemImpl item = items.get(i);
                if (item == expandedItem) {
                    mExpandedIndex = i;
                    return;
                }
            }
        }
    }
}
