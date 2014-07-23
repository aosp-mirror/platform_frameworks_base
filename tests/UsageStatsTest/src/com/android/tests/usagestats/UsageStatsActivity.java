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

package com.android.tests.usagestats;

import android.app.ListActivity;
import android.app.usage.PackageUsageStats;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;

public class UsageStatsActivity extends ListActivity {

    private UsageStatsManager mUsageStatsManager;
    private Adapter mAdapter;
    private Comparator<PackageUsageStats> mComparator = new Comparator<PackageUsageStats>() {
        @Override
        public int compare(PackageUsageStats o1, PackageUsageStats o2) {
            return Long.compare(o2.getTotalTimeSpent(), o1.getTotalTimeSpent());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUsageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        mAdapter = new Adapter();
        updateAdapter();
        setListAdapter(mAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAdapter();
    }

    private void updateAdapter() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -14);
        UsageStats stats = mUsageStatsManager.getRecentStatsSince(cal.getTimeInMillis());
        mAdapter.update(stats);
    }

    private class Adapter extends BaseAdapter {
        private ArrayList<PackageUsageStats> mStats = new ArrayList<>();

        public void update(UsageStats stats) {
            mStats.clear();
            if (stats == null) {
                return;
            }

            final int packageCount = stats.getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                mStats.add(stats.getPackage(i));
            }

            Collections.sort(mStats, mComparator);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mStats.size();
        }

        @Override
        public Object getItem(int position) {
            return mStats.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(UsageStatsActivity.this)
                        .inflate(R.layout.row_item, parent, false);
                holder = new ViewHolder();
                holder.packageName = (TextView) convertView.findViewById(android.R.id.text1);
                holder.usageTime = (TextView) convertView.findViewById(android.R.id.text2);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            holder.packageName.setText(mStats.get(position).getPackageName());
            holder.usageTime.setText(DateUtils.formatDuration(
                    mStats.get(position).getTotalTimeSpent()));
            return convertView;
        }
    }

    private static class ViewHolder {
        TextView packageName;
        TextView usageTime;
    }
}