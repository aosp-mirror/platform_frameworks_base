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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.ArrayList;

public class MenuAdapter extends BaseAdapter {
    static final int ITEM_LAYOUT = com.android.internal.R.layout.popup_menu_item_layout;

    MenuBuilder mAdapterMenu;

    private int mExpandedIndex = -1;

    private boolean mForceShowIcon;
    private final boolean mOverflowOnly;
    private final LayoutInflater mInflater;

    public MenuAdapter(MenuBuilder menu, LayoutInflater inflater, boolean overflowOnly) {
        mOverflowOnly = overflowOnly;
        mInflater = inflater;
        mAdapterMenu = menu;
        findExpandedIndex();
    }

    public boolean getForceShowIcon() {
        return mForceShowIcon;
    }

    public void setForceShowIcon(boolean forceShow) {
        mForceShowIcon = forceShow;
    }

    public int getCount() {
        ArrayList<MenuItemImpl> items = mOverflowOnly ?
                mAdapterMenu.getNonActionItems() : mAdapterMenu.getVisibleItems();
        if (mExpandedIndex < 0) {
            return items.size();
        }
        return items.size() - 1;
    }

    public MenuBuilder getAdapterMenu() {
        return mAdapterMenu;
    }

    public MenuItemImpl getItem(int position) {
        ArrayList<MenuItemImpl> items = mOverflowOnly ?
                mAdapterMenu.getNonActionItems() : mAdapterMenu.getVisibleItems();
        if (mExpandedIndex >= 0 && position >= mExpandedIndex) {
            position++;
        }
        return items.get(position);
    }

    public long getItemId(int position) {
        // Since a menu item's ID is optional, we'll use the position as an
        // ID for the item in the AdapterView
        return position;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mInflater.inflate(ITEM_LAYOUT, parent, false);
        }

        MenuView.ItemView itemView = (MenuView.ItemView) convertView;
        if (mForceShowIcon) {
            ((ListMenuItemView) convertView).setForceShowIcon(true);
        }
        itemView.initialize(getItem(position), 0);
        return convertView;
    }

    void findExpandedIndex() {
        final MenuItemImpl expandedItem = mAdapterMenu.getExpandedItem();
        if (expandedItem != null) {
            final ArrayList<MenuItemImpl> items = mAdapterMenu.getNonActionItems();
            final int count = items.size();
            for (int i = 0; i < count; i++) {
                final MenuItemImpl item = items.get(i);
                if (item == expandedItem) {
                    mExpandedIndex = i;
                    return;
                }
            }
        }
        mExpandedIndex = -1;
    }

    @Override
    public void notifyDataSetChanged() {
        findExpandedIndex();
        super.notifyDataSetChanged();
    }
}