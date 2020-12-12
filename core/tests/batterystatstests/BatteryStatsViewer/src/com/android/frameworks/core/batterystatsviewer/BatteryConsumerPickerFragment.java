/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.content.pm.PackageManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.frameworks.core.batterystatsviewer.BatteryConsumerInfoHelper.BatteryConsumerInfo;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;
import com.android.settingslib.utils.AsyncLoaderCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picker, showing a sorted lists of applications or other types of entities consuming power.
 * Returns the selected entity ID or null.
 */
public class BatteryConsumerPickerFragment extends Fragment {
    private static final String TAG = "AppPicker";

    public static final String PICKER_TYPE = "pickertype";

    public static final int PICKER_TYPE_APP = 0;
    public static final int PICKER_TYPE_DRAIN = 1;

    private BatteryConsumerListAdapter mBatteryConsumerListAdapter;
    private RecyclerView mAppList;
    private View mLoadingView;

    private interface OnBatteryConsumerSelectedListener {
        void onBatteryConsumerSelected(String batteryConsumerId);
    }

    public BatteryConsumerPickerFragment(int pickerType) {
        Bundle args = new Bundle();
        args.putInt(PICKER_TYPE, pickerType);
        setArguments(args);
    }

    public BatteryConsumerPickerFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.battery_consumer_picker_layout, container, false);
        mLoadingView = view.findViewById(R.id.loading_view);

        mAppList = view.findViewById(R.id.list_view);
        mAppList.setLayoutManager(new LinearLayoutManager(getContext()));
        mBatteryConsumerListAdapter = new BatteryConsumerListAdapter(
                BatteryConsumerPickerFragment.this::setSelectedBatteryConsumer);
        mAppList.setAdapter(mBatteryConsumerListAdapter);

        LoaderManager.getInstance(this).initLoader(0, getArguments(),
                new BatteryConsumerListLoaderCallbacks());
        return view;
    }

    public void setSelectedBatteryConsumer(String id) {
        ((BatteryConsumerPickerActivity) getActivity()).setSelectedBatteryConsumer(id);
    }

    private static class BatteryConsumerListLoader extends
            AsyncLoaderCompat<List<BatteryConsumerInfo>> {
        private final BatteryStatsHelper mStatsHelper;
        private final int mPickerType;
        private final UserManager mUserManager;
        private final PackageManager mPackageManager;

        BatteryConsumerListLoader(Context context, int pickerType) {
            super(context);
            mUserManager = context.getSystemService(UserManager.class);
            mStatsHelper = new BatteryStatsHelper(context, false /* collectBatteryBroadcast */);
            mPickerType = pickerType;
            mStatsHelper.create((Bundle) null);
            mStatsHelper.clearStats();
            mPackageManager = context.getPackageManager();
        }

        @Override
        public List<BatteryConsumerInfo> loadInBackground() {
            List<BatteryConsumerInfo> batteryConsumerList = new ArrayList<>();

            mStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, UserHandle.myUserId());

            final List<BatterySipper> usageList = mStatsHelper.getUsageList();
            for (BatterySipper sipper : usageList) {
                switch (mPickerType) {
                    case PICKER_TYPE_APP:
                        if (sipper.drainType != BatterySipper.DrainType.APP) {
                            continue;
                        }
                        break;
                    case PICKER_TYPE_DRAIN:
                    default:
                        if (sipper.drainType == BatterySipper.DrainType.APP) {
                            continue;
                        }
                }

                batteryConsumerList.add(
                        BatteryConsumerInfoHelper.makeBatteryConsumerInfo(mPackageManager, sipper));
            }

            batteryConsumerList.sort(
                    Comparator.comparing((BatteryConsumerInfo a) -> a.powerMah).reversed());
            return batteryConsumerList;
        }

        @Override
        protected void onDiscardResult(List<BatteryConsumerInfo> result) {
        }
    }

    private class BatteryConsumerListLoaderCallbacks implements
            LoaderManager.LoaderCallbacks<List<BatteryConsumerInfo>> {

        @NonNull
        @Override
        public Loader<List<BatteryConsumerInfo>> onCreateLoader(int id, Bundle args) {
            return new BatteryConsumerListLoader(getContext(), args.getInt(PICKER_TYPE));
        }

        @Override
        public void onLoadFinished(@NonNull Loader<List<BatteryConsumerInfo>> loader,
                List<BatteryConsumerInfo> batteryConsumerList) {
            mBatteryConsumerListAdapter.setBatteryConsumerList(batteryConsumerList);
            mAppList.setVisibility(View.VISIBLE);
            mLoadingView.setVisibility(View.GONE);
        }

        @Override
        public void onLoaderReset(
                @NonNull Loader<List<BatteryConsumerInfo>> loader) {
        }
    }

    public class BatteryConsumerListAdapter extends
            RecyclerView.Adapter<BatteryConsumerViewHolder> {
        private final OnBatteryConsumerSelectedListener mListener;
        private List<BatteryConsumerInfo> mBatteryConsumerList;

        public BatteryConsumerListAdapter(OnBatteryConsumerSelectedListener listener) {
            mListener = listener;
        }

        void setBatteryConsumerList(List<BatteryConsumerInfo> batteryConsumerList) {
            mBatteryConsumerList = batteryConsumerList;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mBatteryConsumerList.size();
        }

        @NonNull
        @Override
        public BatteryConsumerViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
                int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(viewGroup.getContext());
            View view = layoutInflater.inflate(R.layout.battery_consumer_info_layout, viewGroup,
                    false);
            return new BatteryConsumerViewHolder(view, mListener);
        }

        @Override
        public void onBindViewHolder(@NonNull BatteryConsumerViewHolder viewHolder, int position) {
            BatteryConsumerInfo item = mBatteryConsumerList.get(position);
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
            viewHolder.iconView.setImageDrawable(
                    item.iconInfo.loadIcon(getContext().getPackageManager()));
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
