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
package com.android.settingslib.drawer;

import android.graphics.drawable.Icon;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

public class SettingsDrawerAdapter extends BaseAdapter {

    private final ArrayList<Item> mItems = new ArrayList<>();
    private final SettingsDrawerActivity mActivity;

    public SettingsDrawerAdapter(SettingsDrawerActivity activity) {
        mActivity = activity;
    }

    void updateCategories() {
        List<DashboardCategory> categories = mActivity.getDashboardCategories();
        mItems.clear();
        // Spacer.
        mItems.add(null);
        Item tile = new Item();
        tile.label = mActivity.getString(R.string.home);
        tile.icon = Icon.createWithResource(mActivity, R.drawable.home);
        mItems.add(tile);
        for (int i = 0; i < categories.size(); i++) {
            Item category = new Item();
            category.icon = null;
            DashboardCategory dashboardCategory = categories.get(i);
            category.label = dashboardCategory.title;
            mItems.add(category);
            for (int j = 0; j < dashboardCategory.tiles.size(); j++) {
                tile = new Item();
                Tile dashboardTile = dashboardCategory.tiles.get(j);
                tile.label = dashboardTile.title;
                tile.icon = dashboardTile.icon;
                tile.tile = dashboardTile;
                mItems.add(tile);
            }
        }
        notifyDataSetChanged();
    }

    public Tile getTile(int position) {
        return mItems.get(position) != null ? mItems.get(position).tile : null;
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public Object getItem(int position) {
        return mItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        return mItems.get(position) != null && mItems.get(position).icon != null;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Item item = mItems.get(position);
        if (item == null) {
            if (convertView == null || convertView.getId() != R.id.spacer) {
                convertView = LayoutInflater.from(mActivity).inflate(R.layout.drawer_spacer,
                        parent, false);
            }
            return convertView;
        }
        if (convertView != null && convertView.getId() == R.id.spacer) {
            convertView = null;
        }
        boolean isTile = item.icon != null;
        if (convertView == null || (isTile != (convertView.getId() == R.id.tile_item))) {
            convertView = LayoutInflater.from(mActivity).inflate(isTile ? R.layout.drawer_item
                            : R.layout.drawer_category,
                    parent, false);
        }
        if (isTile) {
            ((ImageView) convertView.findViewById(android.R.id.icon)).setImageIcon(item.icon);
        }
        ((TextView) convertView.findViewById(android.R.id.title)).setText(item.label);
        return convertView;
    }

    private static class Item {
        public Icon icon;
        public CharSequence label;
        public Tile tile;
    }
}
