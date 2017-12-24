/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the
 * License.
 *
 */

package com.android.benchmark.app;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.TextView;

import com.android.benchmark.registry.BenchmarkGroup;
import com.android.benchmark.registry.BenchmarkRegistry;
import com.android.benchmark.R;

/**
 *
 */
public class BenchmarkListAdapter extends BaseExpandableListAdapter {

    private final LayoutInflater mInflater;
    private final BenchmarkRegistry mRegistry;

    BenchmarkListAdapter(LayoutInflater inflater,
                         BenchmarkRegistry registry) {
        mInflater = inflater;
        mRegistry = registry;
    }

    @Override
    public int getGroupCount() {
        return mRegistry.getGroupCount();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        return mRegistry.getBenchmarkCount(groupPosition);
    }

    @Override
    public Object getGroup(int groupPosition) {
        return mRegistry.getBenchmarkGroup(groupPosition);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        BenchmarkGroup benchmarkGroup = mRegistry.getBenchmarkGroup(groupPosition);

        if (benchmarkGroup != null) {
           return benchmarkGroup.getBenchmarks()[childPosition];
        }

        return null;
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        BenchmarkGroup group = (BenchmarkGroup) getGroup(groupPosition);
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.benchmark_list_group_row, null);
        }

        TextView title = (TextView) convertView.findViewById(R.id.group_name);
        title.setTypeface(null, Typeface.BOLD);
        title.setText(group.getTitle());
        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                             View convertView, ViewGroup parent) {
        BenchmarkGroup.Benchmark benchmark =
                (BenchmarkGroup.Benchmark) getChild(groupPosition, childPosition);
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.benchmark_list_item, null);
        }

        TextView name = (TextView) convertView.findViewById(R.id.benchmark_name);
        name.setText(benchmark.getName());
        CheckBox enabledBox = (CheckBox) convertView.findViewById(R.id.benchmark_enable_checkbox);
        enabledBox.setOnClickListener(benchmark);
        enabledBox.setChecked(benchmark.isEnabled());

        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }

    public int getChildrenHeight() {
        // TODO
        return 1024;
    }
}
