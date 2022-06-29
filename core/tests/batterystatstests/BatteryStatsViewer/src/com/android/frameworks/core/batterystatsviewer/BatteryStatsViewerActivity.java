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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.android.settingslib.utils.AsyncLoaderCompat;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class BatteryStatsViewerActivity extends ComponentActivity {
    public static final String EXTRA_BATTERY_CONSUMER = "batteryConsumerId";

    private static final int BATTERY_STATS_REFRESH_RATE_MILLIS = 60 * 1000;
    private static final int MILLIS_IN_MINUTE = 60000;
    private static final String FORCE_FRESH_STATS = "force_fresh_stats";

    private BatteryStatsDataAdapter mBatteryStatsDataAdapter;
    private final Runnable mBatteryStatsRefresh = this::refreshPeriodically;
    private String mBatteryConsumerId;
    private TextView mTitleView;
    private TextView mDetailsView;
    private ImageView mIconView;
    private TextView mPackagesView;
    private View mHeadingsView;
    private RecyclerView mBatteryConsumerDataView;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private View mCardView;
    private View mEmptyView;
    private List<BatteryUsageStats> mBatteryUsageStats;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBatteryConsumerId = getIntent().getStringExtra(EXTRA_BATTERY_CONSUMER);

        setContentView(R.layout.battery_stats_viewer_layout);

        mSwipeRefreshLayout = findViewById(R.id.swipe_refresh);
        mSwipeRefreshLayout.setColorSchemeResources(android.R.color.holo_green_light);
        mSwipeRefreshLayout.setRefreshing(true);
        mSwipeRefreshLayout.setOnRefreshListener(this::onRefresh);

        mCardView = findViewById(R.id.app_card);
        mTitleView = findViewById(android.R.id.title);
        mDetailsView = findViewById(R.id.details);
        mIconView = findViewById(android.R.id.icon);
        mPackagesView = findViewById(R.id.packages);
        mHeadingsView = findViewById(R.id.headings);

        mBatteryConsumerDataView = findViewById(R.id.battery_consumer_data_view);
        mBatteryConsumerDataView.setLayoutManager(new LinearLayoutManager(this));
        mBatteryStatsDataAdapter = new BatteryStatsDataAdapter();
        mBatteryConsumerDataView.setAdapter(mBatteryStatsDataAdapter);

        mEmptyView = findViewById(R.id.empty_view);
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
                new BatteryUsageStatsLoaderCallbacks());
    }

    private static class BatteryUsageStatsLoader extends
            AsyncLoaderCompat<List<BatteryUsageStats>> {
        private final BatteryStatsManager mBatteryStatsManager;
        private final boolean mForceFreshStats;

        BatteryUsageStatsLoader(Context context, boolean forceFreshStats) {
            super(context);
            mBatteryStatsManager = context.getSystemService(BatteryStatsManager.class);
            mForceFreshStats = forceFreshStats;
        }

        @Override
        public List<BatteryUsageStats> loadInBackground() {
            final int maxStatsAgeMs = mForceFreshStats ? 0 : BATTERY_STATS_REFRESH_RATE_MILLIS;
            final BatteryUsageStatsQuery queryDefault =
                    new BatteryUsageStatsQuery.Builder()
                            .includePowerModels()
                            .includeProcessStateData()
                            .setMaxStatsAgeMs(maxStatsAgeMs)
                            .build();
            final BatteryUsageStatsQuery queryPowerProfileModeledOnly =
                    new BatteryUsageStatsQuery.Builder()
                            .powerProfileModeledOnly()
                            .includePowerModels()
                            .includeProcessStateData()
                            .setMaxStatsAgeMs(maxStatsAgeMs)
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
            return new BatteryUsageStatsLoader(BatteryStatsViewerActivity.this,
                    args.getBoolean(FORCE_FRESH_STATS));
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

            if (batteryConsumerInfo.consumerType
                    == BatteryConsumerData.ConsumerType.DEVICE_POWER_COMPONENT) {
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

        mCardView.setVisibility(View.VISIBLE);
        mSwipeRefreshLayout.setRefreshing(false);
    }

    private static class BatteryStatsDataAdapter extends
            RecyclerView.Adapter<BatteryStatsDataAdapter.ViewHolder> {
        public static class ViewHolder extends RecyclerView.ViewHolder {
            public ImageView iconImageView;
            public TextView titleTextView;
            public TextView value1TextView;
            public TextView value2TextView;

            ViewHolder(View itemView) {
                super(itemView);

                iconImageView = itemView.findViewById(R.id.icon);
                titleTextView = itemView.findViewById(R.id.title);
                value1TextView = itemView.findViewById(R.id.value1);
                value2TextView = itemView.findViewById(R.id.value2);
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
                case UID_TOTAL_POWER:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_sum_24, 0);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setProportionText(viewHolder.value2TextView, entry);
                    break;
                case UID_POWER_MODELED:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_calculate_24,
                            R.color.battery_consumer_bg_power_profile);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setProportionText(viewHolder.value2TextView, entry);
                    break;
                case UID_POWER_MODELED_PROCESS_STATE:
                    setTitleIconAndBackground(viewHolder, "    " + entry.title,
                            R.drawable.gm_calculate_24,
                            R.color.battery_consumer_bg_power_profile);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    viewHolder.value2TextView.setVisibility(View.INVISIBLE);
                    break;
                case UID_POWER_MEASURED:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_amp_24,
                            R.color.battery_consumer_bg_measured_energy);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setProportionText(viewHolder.value2TextView, entry);
                    break;
                case UID_POWER_MEASURED_PROCESS_STATE:
                    setTitleIconAndBackground(viewHolder, "    " + entry.title,
                            R.drawable.gm_amp_24,
                            R.color.battery_consumer_bg_measured_energy);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    viewHolder.value2TextView.setVisibility(View.INVISIBLE);
                    break;
                case UID_POWER_CUSTOM:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_custom_24,
                            R.color.battery_consumer_bg_measured_energy);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setProportionText(viewHolder.value2TextView, entry);
                    break;
                case UID_DURATION:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_timer_24, 0);
                    setDurationText(viewHolder.value1TextView, (long) entry.value1);
                    setProportionText(viewHolder.value2TextView, entry);
                    break;
                case DEVICE_TOTAL_POWER:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_sum_24, 0);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setPowerText(viewHolder.value2TextView, entry.value2);
                    break;
                case DEVICE_POWER_MODELED:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_calculate_24,
                            R.color.battery_consumer_bg_power_profile);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setPowerText(viewHolder.value2TextView, entry.value2);
                    break;
                case DEVICE_POWER_MEASURED:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_amp_24,
                            R.color.battery_consumer_bg_measured_energy);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setPowerText(viewHolder.value2TextView, entry.value2);
                    break;
                case DEVICE_POWER_CUSTOM:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_custom_24,
                            R.color.battery_consumer_bg_measured_energy);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setPowerText(viewHolder.value2TextView, entry.value2);
                    break;
                case DEVICE_DURATION:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_timer_24, 0);
                    setDurationText(viewHolder.value1TextView, (long) entry.value1);
                    viewHolder.value2TextView.setVisibility(View.GONE);
                    break;
            }
        }

        private void setTitleIconAndBackground(ViewHolder viewHolder, String title, int icon,
                int background) {
            viewHolder.titleTextView.setText(title);
            viewHolder.iconImageView.setImageResource(icon);
            viewHolder.itemView.setBackgroundResource(background);
        }

        private void setProportionText(TextView textView, BatteryConsumerData.Entry entry) {
            final double proportion = entry.value2 != 0 ? entry.value1 * 100 / entry.value2 : 0;
            textView.setText(
                    String.format(Locale.getDefault(), "%.1f%%", proportion));
            textView.setVisibility(View.VISIBLE);
        }

        private void setPowerText(TextView textView, double powerMah) {
            textView.setText(String.format(Locale.getDefault(), "%.1f", powerMah));
            textView.setVisibility(View.VISIBLE);
        }

        private void setDurationText(TextView textView, long durationMs) {
            CharSequence text;
            if (durationMs < MILLIS_IN_MINUTE) {
                text = String.format(Locale.getDefault(), "%,d ms", durationMs);
            } else {
                text = String.format(Locale.getDefault(), "%,d m %d s",
                        durationMs / MILLIS_IN_MINUTE,
                        (durationMs % MILLIS_IN_MINUTE) / 1000);
            }

            textView.setText(text);
            textView.setVisibility(View.VISIBLE);
        }
    }
}
