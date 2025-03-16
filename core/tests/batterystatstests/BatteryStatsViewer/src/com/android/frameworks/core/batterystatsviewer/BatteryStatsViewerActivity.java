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
import android.os.BatteryConsumer;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
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

import java.util.ArrayList;
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
    private BatteryUsageStats mBatteryUsageStats;

    private static SparseArray<String> sProcStateNames = new SparseArray<>();
    static {
        sProcStateNames.put(BatteryConsumer.PROCESS_STATE_UNSPECIFIED, "-");
        sProcStateNames.put(BatteryConsumer.PROCESS_STATE_FOREGROUND, "FG");
        sProcStateNames.put(BatteryConsumer.PROCESS_STATE_BACKGROUND, "BG");
        sProcStateNames.put(BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE, "FGS");
        sProcStateNames.put(BatteryConsumer.PROCESS_STATE_CACHED, "Cached");
    }

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
            AsyncLoaderCompat<BatteryUsageStats> {
        private final BatteryStatsManager mBatteryStatsManager;
        private final boolean mForceFreshStats;

        BatteryUsageStatsLoader(Context context, boolean forceFreshStats) {
            super(context);
            mBatteryStatsManager = context.getSystemService(BatteryStatsManager.class);
            mForceFreshStats = forceFreshStats;
        }

        @Override
        public BatteryUsageStats loadInBackground() {
            final int maxStatsAgeMs = mForceFreshStats ? 0 : BATTERY_STATS_REFRESH_RATE_MILLIS;
            final BatteryUsageStatsQuery queryDefault =
                    new BatteryUsageStatsQuery.Builder()
                            .includeProcessStateData()
                            .includeScreenStateData()
                            .includePowerStateData()
                            .setMaxStatsAgeMs(maxStatsAgeMs)
                            .build();
            return mBatteryStatsManager.getBatteryUsageStats(queryDefault);
        }

        @Override
        protected void onDiscardResult(BatteryUsageStats result) {
        }
    }

    private class BatteryUsageStatsLoaderCallbacks
            implements LoaderCallbacks<BatteryUsageStats> {
        @NonNull
        @Override
        public Loader<BatteryUsageStats> onCreateLoader(int id, Bundle args) {
            return new BatteryUsageStatsLoader(BatteryStatsViewerActivity.this,
                    args.getBoolean(FORCE_FRESH_STATS));
        }

        @Override
        public void onLoadFinished(@NonNull Loader<BatteryUsageStats> loader,
                BatteryUsageStats batteryUsageStats) {
            onBatteryUsageStatsLoaded(batteryUsageStats);
        }

        @Override
        public void onLoaderReset(@NonNull Loader<BatteryUsageStats> loader) {
        }
    }

    private void onBatteryUsageStatsLoaded(BatteryUsageStats batteryUsageStats) {
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
            public static class SliceViewHolder {
                public TableRow tableRow;
                public int procState;
                public int powerState;
                public int screenState;
                public TextView powerTextView;
                public TextView durationTextView;
            }

            public ImageView iconImageView;
            public TextView titleTextView;
            public TextView value1TextView;
            public TextView value2TextView;
            public TableLayout table;
            public List<SliceViewHolder> slices = new ArrayList<>();

            ViewHolder(View itemView) {
                super(itemView);

                iconImageView = itemView.findViewById(R.id.icon);
                titleTextView = itemView.findViewById(R.id.title);
                value1TextView = itemView.findViewById(R.id.value1);
                value2TextView = itemView.findViewById(R.id.value2);
                table = itemView.findViewById(R.id.table);

                for (int i = 0; i < sProcStateNames.size(); i++) {
                    int procState = sProcStateNames.keyAt(i);
                    slices.add(createSliceViewHolder(procState,
                            BatteryConsumer.POWER_STATE_BATTERY,
                            BatteryConsumer.SCREEN_STATE_ON,
                            R.id.power_b_on, R.id.duration_b_on));
                    slices.add(createSliceViewHolder(procState,
                            BatteryConsumer.POWER_STATE_BATTERY,
                            BatteryConsumer.SCREEN_STATE_OTHER,
                            R.id.power_b_off, R.id.duration_b_off));
                    slices.add(createSliceViewHolder(procState,
                            BatteryConsumer.POWER_STATE_OTHER,
                            BatteryConsumer.SCREEN_STATE_ON,
                            R.id.power_c_on, R.id.duration_c_on));
                    slices.add(createSliceViewHolder(procState,
                            BatteryConsumer.POWER_STATE_OTHER,
                            BatteryConsumer.SCREEN_STATE_OTHER,
                            R.id.power_c_off, R.id.duration_c_off));
                }
            }

            private SliceViewHolder createSliceViewHolder(int procState, int powerState,
                    int screenState, int powerTextViewResId, int durationTextViewResId) {
                TableRow powerRow = table.findViewWithTag("procstate" + procState);
                SliceViewHolder svh = new SliceViewHolder();
                svh.tableRow = powerRow;
                svh.procState = procState;
                svh.powerState = powerState;
                svh.screenState = screenState;
                svh.powerTextView = powerRow.findViewById(powerTextViewResId);
                svh.durationTextView = powerRow.findViewById(durationTextViewResId);
                return svh;
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
            ViewGroup itemView = (ViewGroup) layoutInflater.inflate(
                    R.layout.battery_consumer_entry_layout, parent, false);
            TableLayout table = itemView.findViewById(R.id.table);
            int offset = 1;     // Skip header
            for (int i = 0; i < sProcStateNames.size(); i++) {
                View powerRow = layoutInflater.inflate(R.layout.battery_consumer_slices_layout,
                        itemView, false);
                ((TextView) powerRow.findViewById(R.id.procState))
                        .setText(sProcStateNames.valueAt(i));
                powerRow.setTag("procstate" + sProcStateNames.keyAt(i));
                table.addView(powerRow, offset++);
            }

            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
            BatteryConsumerData.Entry entry = mEntries.get(position);
            switch (entry.entryType) {
                case UID:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_energy_24, 0);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setProportionText(viewHolder.value2TextView, entry);
                    bindSlices(viewHolder, entry);
                    break;
                case DEVICE_TOTAL_POWER:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_sum_24, 0);
                    setPowerText(viewHolder.value1TextView, entry.value1);
                    setPowerText(viewHolder.value2TextView, entry.value2);
                    break;
                case DEVICE_POWER:
                    setTitleIconAndBackground(viewHolder, entry.title,
                            R.drawable.gm_calculate_24,
                            R.color.battery_consumer_bg_power_profile);
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

        private void bindSlices(ViewHolder viewHolder, BatteryConsumerData.Entry entry) {
            if (entry.slices == null || entry.slices.isEmpty()) {
                viewHolder.table.setVisibility(View.GONE);
                return;
            }
            viewHolder.table.setVisibility(View.VISIBLE);

            boolean[] procStateRowPopulated =
                    new boolean[BatteryConsumer.PROCESS_STATE_COUNT];
            for (BatteryConsumerData.Slice s : entry.slices) {
                if (s.powerMah != 0 || s.durationMs != 0) {
                    procStateRowPopulated[s.processState] = true;
                }
            }

            for (ViewHolder.SliceViewHolder sliceViewHolder : viewHolder.slices) {
                BatteryConsumerData.Slice slice = null;
                for (BatteryConsumerData.Slice s : entry.slices) {
                    if (s.powerState == sliceViewHolder.powerState
                            && s.screenState == sliceViewHolder.screenState
                            && s.processState == sliceViewHolder.procState) {
                        slice = s;
                        break;
                    }
                }
                if (!procStateRowPopulated[sliceViewHolder.procState]) {
                    sliceViewHolder.tableRow.setVisibility(View.GONE);
                } else {
                    sliceViewHolder.tableRow.setVisibility(View.VISIBLE);

                    if (slice != null && (slice.powerMah != 0 || slice.durationMs != 0)) {
                        sliceViewHolder.powerTextView.setText(
                                String.format(Locale.getDefault(), "%.1f", slice.powerMah));
                    } else {
                        sliceViewHolder.powerTextView.setText(null);
                    }

                    if (slice != null && slice.durationMs != 0) {
                        sliceViewHolder.durationTextView.setVisibility(View.VISIBLE);
                        String timeString;
                        if (slice.durationMs < MILLIS_IN_MINUTE) {
                            timeString = String.format(Locale.getDefault(), "%ds",
                                    slice.durationMs / 1000);
                        } else if (slice.durationMs < 60 * MILLIS_IN_MINUTE) {
                            timeString = String.format(Locale.getDefault(), "%dm %ds",
                                    slice.durationMs / MILLIS_IN_MINUTE,
                                    (slice.durationMs % MILLIS_IN_MINUTE) / 1000);
                        } else {
                            timeString = String.format(Locale.getDefault(), "%dm",
                                    slice.durationMs / MILLIS_IN_MINUTE);
                        }
                        sliceViewHolder.durationTextView.setText(timeString);
                    } else {
                        sliceViewHolder.durationTextView.setVisibility(View.GONE);
                    }
                }
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
