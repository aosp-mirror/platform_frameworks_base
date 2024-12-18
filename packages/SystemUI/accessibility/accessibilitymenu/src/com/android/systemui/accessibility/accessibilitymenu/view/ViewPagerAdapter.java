/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.accessibilitymenu.view;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService;
import com.android.systemui.accessibility.accessibilitymenu.R;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut;

import java.util.List;

/** The pager adapter, which provides the pages to the view pager widget. */
class ViewPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** List of shortcuts, split into sub lists per page */
    private List<List<A11yMenuShortcut>> mShortcutList;
    private final AccessibilityMenuService mService;
    private int mVerticalSpacing = 0;

    ViewPagerAdapter(AccessibilityMenuService service) {
        mService = service;
    }

    public void setVerticalSpacing(int spacing) {
        if (mVerticalSpacing != spacing) {
            mVerticalSpacing = spacing;
            notifyDataSetChanged();
        }
    }

    public void set(List<List<A11yMenuShortcut>> tList) {
        mShortcutList = tList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.grid_view, parent, false);
        return new MenuViewHolder(view.findViewById(R.id.gridview));
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        A11yMenuAdapter adapter = new A11yMenuAdapter(
                mService, mShortcutList.get(position));
        GridView gridView = (GridView) holder.itemView;
        gridView.setNumColumns(A11yMenuViewPager.GridViewParams.getGridColumnCount(mService));
        gridView.setAdapter(adapter);
        gridView.setVerticalSpacing(mVerticalSpacing);
    }

    @Override
    public int getItemCount() {
        if (mShortcutList == null) {
            return 0;
        }
        return mShortcutList.size();
    }

    static class MenuViewHolder extends RecyclerView.ViewHolder {
        MenuViewHolder(View itemView) {
            super(itemView);
        }
    }
}