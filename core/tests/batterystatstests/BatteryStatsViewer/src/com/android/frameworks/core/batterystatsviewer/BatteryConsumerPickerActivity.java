/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.core.batterystatsviewer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Bundle;
import android.os.UidBatteryConsumer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.android.settingslib.utils.AsyncLoaderCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picker, showing a sorted lists of applications and other types of entities consuming power.
 * Opens BatteryStatsViewerActivity upon item selection.
 */
public class BatteryConsumerPickerActivity extends ComponentActivity {
    private static final String PREF_SELECTED_BATTERY_CONSUMER = "batteryConsumerId";
    private static final int BATTERY_STATS_REFRESH_RATE_MILLIS = 60 * 1000;
    private static final String FORCE_FRESH_STATS = "force_fresh_stats";
    private BatteryConsumerListAdapter mBatteryConsumerListAdapter;
    private RecyclerView mAppList;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private final Runnable mBatteryStatsRefresh = this::refreshPeriodically;

    private interface OnBatteryConsumerSelectedListener {
        void onBatteryConsumerSelected(String batteryConsumerId);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.battery_consumer_picker_layout);

        mSwipeRefreshLayout = findViewById(R.id.swipe_refresh);
        mSwipeRefreshLayout.setColorSchemeResources(android.R.color.holo_green_light);
        mSwipeRefreshLayout.setRefreshing(true);
        mSwipeRefreshLayout.setOnRefreshListener(this::onRefresh);
        mAppList = findViewById(R.id.list_view);
        mAppList.setLayoutManager(new LinearLayoutManager(this));
        mBatteryConsumerListAdapter =
                new BatteryConsumerListAdapter((this::setSelectedBatteryConsumer));
        mAppList.setAdapter(mBatteryConsumerListAdapter);

        if (icicle == null) {
            final String batteryConsumerId = getPreferences(Context.MODE_PRIVATE)
                    .getString(PREF_SELECTED_BATTERY_CONSUMER, null);
            if (batteryConsumerId != null) {
                startBatteryStatsActivity(batteryConsumerId);
            }
        }
    }

    public void setSelectedBatteryConsumer(String batteryConsumerId) {
        getPreferences(Context.MODE_PRIVATE).edit()
                .putString(PREF_SELECTED_BATTERY_CONSUMER, batteryConsumerId)
                .apply();
        startBatteryStatsActivity(batteryConsumerId);
    }

    private void startBatteryStatsActivity(String batteryConsumerId) {
        final Intent intent = new Intent(this, BatteryStatsViewerActivity.class)
                .putExtra(BatteryStatsViewerActivity.EXTRA_BATTERY_CONSUMER, batteryConsumerId);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPeriodically();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getMainThreadHandler().removeCallbacks(mBatteryStatsRefresh);
    }

    private void refreshPeriodically() {
        loadBatteryUsageStats(false);
        getMainThreadHandler().postDelayed(mBatteryStatsRefresh, BATTERY_STATS_REFRESH_RATE_MILLIS);
    }

    private void onRefresh() {
        loadBatteryUsageStats(true);
    }

    private void loadBatteryUsageStats(boolean forceFreshStats) {
        Bundle args = new Bundle();
        args.putBoolean(FORCE_FRESH_STATS, forceFreshStats);
        LoaderManager.getInstance(this).restartLoader(0, args,
                new BatteryConsumerListLoaderCallbacks());
    }

    private static class BatteryConsumerListLoader extends
            AsyncLoaderCompat<List<BatteryConsumerInfoHelper.BatteryConsumerInfo>> {
        private final BatteryStatsManager mBatteryStatsManager;
        private final PackageManager mPackageManager;
        private final boolean mForceFreshStats;

        BatteryConsumerListLoader(Context context, boolean forceFreshStats) {
            super(context);
            mBatteryStatsManager = context.getSystemService(BatteryStatsManager.class);
            mPackageManager = context.getPackageManager();
            mForceFreshStats = forceFreshStats;
        }

        @Override
        public List<BatteryConsumerInfoHelper.BatteryConsumerInfo> loadInBackground() {
            final BatteryUsageStatsQuery query = mForceFreshStats
                    ? new BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(0).build()
                    : BatteryUsageStatsQuery.DEFAULT;
            final BatteryUsageStats batteryUsageStats =
                    mBatteryStatsManager.getBatteryUsageStats(query);
            List<BatteryConsumerInfoHelper.BatteryConsumerInfo> batteryConsumerList =
                    new ArrayList<>();

            batteryConsumerList.add(
                    BatteryConsumerInfoHelper.makeBatteryConsumerInfo(
                            batteryUsageStats,
                            BatteryConsumerData.AGGREGATE_BATTERY_CONSUMER_ID,
                            mPackageManager));

            for (UidBatteryConsumer consumer : batteryUsageStats.getUidBatteryConsumers()) {
                batteryConsumerList.add(
                        BatteryConsumerInfoHelper.makeBatteryConsumerInfo(batteryUsageStats,
                                BatteryConsumerData.batteryConsumerId(consumer),
                                mPackageManager));
            }

            batteryConsumerList.sort(
                    Comparator.comparing(
                            (BatteryConsumerInfoHelper.BatteryConsumerInfo a) -> a.powerMah)
                            .reversed());

            return batteryConsumerList;
        }

        @Override
        protected void onDiscardResult(List<BatteryConsumerInfoHelper.BatteryConsumerInfo> result) {
        }
    }

    private class BatteryConsumerListLoaderCallbacks implements
            LoaderManager.LoaderCallbacks<List<BatteryConsumerInfoHelper.BatteryConsumerInfo>> {

        @NonNull
        @Override
        public Loader<List<BatteryConsumerInfoHelper.BatteryConsumerInfo>> onCreateLoader(int id,
                Bundle args) {
            return new BatteryConsumerListLoader(BatteryConsumerPickerActivity.this,
                    args.getBoolean(FORCE_FRESH_STATS));
        }

        @Override
        public void onLoadFinished(
                @NonNull Loader<List<BatteryConsumerInfoHelper.BatteryConsumerInfo>> loader,
                List<BatteryConsumerInfoHelper.BatteryConsumerInfo> batteryConsumerList) {
            mBatteryConsumerListAdapter.setBatteryConsumerList(batteryConsumerList);
            mSwipeRefreshLayout.setRefreshing(false);
        }

        @Override
        public void onLoaderReset(
                @NonNull Loader<List<BatteryConsumerInfoHelper.BatteryConsumerInfo>> loader) {
        }
    }

    public class BatteryConsumerListAdapter
            extends RecyclerView.Adapter<BatteryConsumerViewHolder> {
        private final OnBatteryConsumerSelectedListener mListener;
        private List<BatteryConsumerInfoHelper.BatteryConsumerInfo> mBatteryConsumerList =
                Collections.emptyList();

        public BatteryConsumerListAdapter(OnBatteryConsumerSelectedListener listener) {
            mListener = listener;
        }

        void setBatteryConsumerList(
                List<BatteryConsumerInfoHelper.BatteryConsumerInfo> batteryConsumerList) {
            mBatteryConsumerList = batteryConsumerList;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mBatteryConsumerList.size();
        }

        @NonNull
        @Override
        public BatteryConsumerViewHolder onCreateViewHolder(
                @NonNull ViewGroup viewGroup,
                int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(viewGroup.getContext());
            View view = layoutInflater.inflate(R.layout.battery_consumer_info_layout, viewGroup,
                    false);
            return new BatteryConsumerViewHolder(view, mListener);
        }

        @Override
        public void onBindViewHolder(@NonNull BatteryConsumerViewHolder viewHolder, int position) {
            BatteryConsumerInfoHelper.BatteryConsumerInfo item = mBatteryConsumerList.get(position);
            viewHolder.id = item.id;
            viewHolder.titleView.setText(item.label);
            if (item.details != null) {
                viewHolder.detailsView.setText(item.details);
                viewHolder.detailsView.setVisibility(View.VISIBLE);
            } else {
                viewHolder.detailsView.setVisibility(View.GONE);
            }
            viewHolder.powerView.setText(
                    String.format(Locale.getDefault(), "%.1f mAh", item.powerMah));
            if (item.iconInfo != null) {
                viewHolder.iconView.setImageDrawable(
                        item.iconInfo.loadIcon(getPackageManager()));
            } else {
                viewHolder.iconView.setImageResource(R.drawable.gm_device_24);
            }
            if (item.packages != null) {
                viewHolder.packagesView.setText(item.packages);
                viewHolder.packagesView.setVisibility(View.VISIBLE);
            } else {
                viewHolder.packagesView.setVisibility(View.GONE);
            }
        }
    }

    // View Holder used when displaying apps
    public static class BatteryConsumerViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener {
        private final OnBatteryConsumerSelectedListener mListener;

        public String id;
        public TextView titleView;
        public TextView detailsView;
        public ImageView iconView;
        public TextView packagesView;
        public TextView powerView;

        BatteryConsumerViewHolder(View view, OnBatteryConsumerSelectedListener listener) {
            super(view);
            mListener = listener;
            view.setOnClickListener(this);
            titleView = view.findViewById(android.R.id.title);
            detailsView = view.findViewById(R.id.details);
            iconView = view.findViewById(android.R.id.icon);
            packagesView = view.findViewById(R.id.packages);
            powerView = view.findViewById(R.id.power_mah);
            powerView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onClick(View v) {
            mListener.onBatteryConsumerSelected(id);
        }
    }
}
