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
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.utils.AsyncLoaderCompat;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class BatteryStatsViewerActivity extends ComponentActivity {
    public static final String EXTRA_BATTERY_CONSUMER = "batteryConsumerId";

    private static final int BATTERY_STATS_REFRESH_RATE_MILLIS = 60 * 1000;
    private static final int MILLIS_IN_MINUTE = 60000;
    private static final int LOADER_BATTERY_USAGE_STATS = 1;

    private BatteryStatsDataAdapter mBatteryStatsDataAdapter;
    private final Runnable mBatteryStatsRefresh = this::loadBatteryStats;
    private String mBatteryConsumerId;
    private TextView mTitleView;
    private TextView mDetailsView;
    private ImageView mIconView;
    private TextView mPackagesView;
    private View mHeadingsView;
    private RecyclerView mBatteryConsumerDataView;
    private View mLoadingView;
    private View mEmptyView;
    private List<BatteryUsageStats> mBatteryUsageStats;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBatteryConsumerId = getIntent().getStringExtra(EXTRA_BATTERY_CONSUMER);

        setContentView(R.layout.battery_stats_viewer_layout);

        mTitleView = findViewById(android.R.id.title);
        mDetailsView = findViewById(R.id.details);
        mIconView = findViewById(android.R.id.icon);
        mPackagesView = findViewById(R.id.packages);
        mHeadingsView = findViewById(R.id.headings);

        mBatteryConsumerDataView = findViewById(R.id.battery_consumer_data_view);
        mBatteryConsumerDataView.setLayoutManager(new LinearLayoutManager(this));
        mBatteryStatsDataAdapter = new BatteryStatsDataAdapter();
        mBatteryConsumerDataView.setAdapter(mBatteryStatsDataAdapter);

        mLoadingView = findViewById(R.id.loading_view);
        mEmptyView = findViewById(R.id.empty_view);

        LoaderManager loaderManager = LoaderManager.getInstance(this);
        loaderManager.restartLoader(LOADER_BATTERY_USAGE_STATS, null,
                new BatteryUsageStatsLoaderCallbacks());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBatteryStats();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getMainThreadHandler().removeCallbacks(mBatteryStatsRefresh);
    }

    private void loadBatteryStats() {
        LoaderManager loaderManager = LoaderManager.getInstance(this);
        loaderManager.restartLoader(LOADER_BATTERY_USAGE_STATS, null,
                new BatteryUsageStatsLoaderCallbacks());
        getMainThreadHandler().postDelayed(mBatteryStatsRefresh, BATTERY_STATS_REFRESH_RATE_MILLIS);
    }

    private static class BatteryUsageStatsLoader extends
            AsyncLoaderCompat<List<BatteryUsageStats>> {
        private final BatteryStatsManager mBatteryStatsManager;

        BatteryUsageStatsLoader(Context context) {
            super(context);
            mBatteryStatsManager = context.getSystemService(BatteryStatsManager.class);
        }

        @Override
        public List<BatteryUsageStats> loadInBackground() {
            final BatteryUsageStatsQuery queryDefault =
                    new BatteryUsageStatsQuery.Builder()
                            .includePowerModels()
                            .build();
            final BatteryUsageStatsQuery queryPowerProfileModeledOnly =
                    new BatteryUsageStatsQuery.Builder()
                            .powerProfileModeledOnly()
                            .includePowerModels()
                            .build();
            return mBatteryStatsManager.getBatteryUsageStats(
                    List.of(queryDefault, queryPowerProfileModeledOnly));
        }

        @Override
        protected void onDiscardResult(List<BatteryUsageStats> result) {
        }
    }

    private class BatteryUsageStatsLoaderCallbacks
            implements LoaderCallbacks<List<BatteryUsageStats>> {
        @NonNull
        @Override
        public Loader<List<BatteryUsageStats>> onCreateLoader(int id, Bundle args) {
            return new BatteryUsageStatsLoader(BatteryStatsViewerActivity.this);
        }

        @Override
        public void onLoadFinished(@NonNull Loader<List<BatteryUsageStats>> loader,
                List<BatteryUsageStats> batteryUsageStats) {
            onBatteryUsageStatsLoaded(batteryUsageStats);
        }

        @Override
        public void onLoaderReset(@NonNull Loader<List<BatteryUsageStats>> loader) {
        }
    }

    private void onBatteryUsageStatsLoaded(List<BatteryUsageStats> batteryUsageStats) {
        mBatteryUsageStats = batteryUsageStats;
        onBatteryStatsDataLoaded();
    }

    public void onBatteryStatsDataLoaded() {
        BatteryConsumerData batteryConsumerData = new BatteryConsumerData(this,
                mBatteryUsageStats, mBatteryConsumerId);

        BatteryConsumerInfoHelper.BatteryConsumerInfo
                batteryConsumerInfo = batteryConsumerData.getBatteryConsumerInfo();
        if (batteryConsumerInfo == null) {
            Toast.makeText(this, "Battery consumer not found: " + mBatteryConsumerId,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        } else {
            mTitleView.setText(batteryConsumerInfo.label);
            if (batteryConsumerInfo.details != null) {
                mDetailsView.setText(batteryConsumerInfo.details);
                mDetailsView.setVisibility(View.VISIBLE);
            } else {
                mDetailsView.setVisibility(View.GONE);
            }
            if (batteryConsumerInfo.iconInfo != null) {
                mIconView.setImageDrawable(
                        batteryConsumerInfo.iconInfo.loadIcon(getPackageManager()));
            } else {
                mIconView.setImageResource(R.drawable.gm_device_24);
            }
            if (batteryConsumerInfo.packages != null) {
                mPackagesView.setText(batteryConsumerInfo.packages);
                mPackagesView.setVisibility(View.VISIBLE);
            } else {
                mPackagesView.setVisibility(View.GONE);
            }

            if (batteryConsumerInfo.isSystemBatteryConsumer) {
                mHeadingsView.setVisibility(View.VISIBLE);
            } else {
                mHeadingsView.setVisibility(View.GONE);
            }
        }

        mBatteryStatsDataAdapter.setEntries(batteryConsumerData.getEntries());
        if (batteryConsumerData.getEntries().isEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            mBatteryConsumerDataView.setVisibility(View.GONE);
        } else {
            mEmptyView.setVisibility(View.GONE);
            mBatteryConsumerDataView.setVisibility(View.VISIBLE);
        }

        mLoadingView.setVisibility(View.GONE);
    }

    private static class BatteryStatsDataAdapter extends
            RecyclerView.Adapter<BatteryStatsDataAdapter.ViewHolder> {
        public static class ViewHolder extends RecyclerView.ViewHolder {
            public ImageView iconImageView;
            public TextView titleTextView;
            public TextView amountTextView;
            public TextView percentTextView;

            ViewHolder(View itemView) {
                super(itemView);

                iconImageView = itemView.findViewById(R.id.icon);
                titleTextView = itemView.findViewById(R.id.title);
                amountTextView = itemView.findViewById(R.id.amount);
                percentTextView = itemView.findViewById(R.id.percent);
            }
        }

        private List<BatteryConsumerData.Entry> mEntries = Collections.emptyList();

        public void setEntries(List<BatteryConsumerData.Entry> entries) {
            mEntries = entries;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
            View itemView = layoutInflater.inflate(R.layout.battery_consumer_entry_layout, parent,
                    false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
            BatteryConsumerData.Entry entry = mEntries.get(position);
            switch (entry.entryType) {
                case POWER_MODELED:
                    viewHolder.titleTextView.setText(entry.title);
                    viewHolder.amountTextView.setText(
                            String.format(Locale.getDefault(), "%.1f mAh", entry.value));
                    viewHolder.iconImageView.setImageResource(R.drawable.gm_calculate_24);
                    viewHolder.itemView.setBackgroundResource(
                            R.color.battery_consumer_bg_power_profile);
                    break;
                case POWER_MEASURED:
                    viewHolder.titleTextView.setText(entry.title);
                    viewHolder.amountTextView.setText(
                            String.format(Locale.getDefault(), "%.1f mAh", entry.value));
                    viewHolder.iconImageView.setImageResource(R.drawable.gm_amp_24);
                    viewHolder.itemView.setBackgroundResource(
                            R.color.battery_consumer_bg_measured_energy);
                    break;
                case POWER_CUSTOM:
                    viewHolder.titleTextView.setText(entry.title);
                    viewHolder.amountTextView.setText(
                            String.format(Locale.getDefault(), "%.1f mAh", entry.value));
                    viewHolder.iconImageView.setImageResource(R.drawable.gm_custom_24);
                    viewHolder.itemView.setBackgroundResource(
                            R.color.battery_consumer_bg_measured_energy);
                    break;
                case DURATION:
                    viewHolder.titleTextView.setText(entry.title);
                    final long durationMs = (long) entry.value;
                    CharSequence text;
                    if (durationMs < MILLIS_IN_MINUTE) {
                        text = String.format(Locale.getDefault(), "%,d ms", durationMs);
                    } else {
                        text = String.format(Locale.getDefault(), "%,d m %d s",
                                durationMs / MILLIS_IN_MINUTE,
                                (durationMs % MILLIS_IN_MINUTE) / 1000);
                    }

                    viewHolder.amountTextView.setText(text);
                    viewHolder.iconImageView.setImageResource(R.drawable.gm_timer_24);
                    viewHolder.itemView.setBackground(null);
                    break;
            }

            double proportion;
            if (entry.isSystemBatteryConsumer) {
                proportion = entry.value != 0 ? entry.total * 100 / entry.value : 0;
            } else {
                proportion = entry.total != 0 ? entry.value * 100 / entry.total : 0;
            }
            viewHolder.percentTextView.setText(
                    String.format(Locale.getDefault(), "%.1f%%", proportion));
        }
    }
}
