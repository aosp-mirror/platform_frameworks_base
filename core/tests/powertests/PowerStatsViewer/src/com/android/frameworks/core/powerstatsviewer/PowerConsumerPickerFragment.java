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

package com.android.frameworks.core.powerstatsviewer;

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

import com.android.frameworks.core.powerstatsviewer.PowerConsumerInfoHelper.PowerConsumerInfo;
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
public class PowerConsumerPickerFragment extends Fragment {
    private static final String TAG = "AppPicker";

    public static final String PICKER_TYPE = "pickertype";

    public static final int PICKER_TYPE_APP = 0;
    public static final int PICKER_TYPE_DRAIN = 1;

    private PowerConsumerListAdapter mPowerConsumerListAdapter;
    private RecyclerView mAppList;
    private View mLoadingView;

    private interface OnPowerConsumerSelectedListener {
        void onPowerConsumerSelected(String uid);
    }

    public PowerConsumerPickerFragment(int pickerType) {
        Bundle args = new Bundle();
        args.putInt(PICKER_TYPE, pickerType);
        setArguments(args);
    }

    public PowerConsumerPickerFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.power_consumer_picker_layout, container, false);
        mLoadingView = view.findViewById(R.id.loading_view);

        mAppList = view.findViewById(R.id.list_view);
        mAppList.setLayoutManager(new LinearLayoutManager(getContext()));
        mPowerConsumerListAdapter = new PowerConsumerListAdapter(
                PowerConsumerPickerFragment.this::setSelectedPowerConsumer);
        mAppList.setAdapter(mPowerConsumerListAdapter);

        LoaderManager.getInstance(this).initLoader(0, getArguments(),
                new PowerConsumerListLoaderCallbacks());
        return view;
    }

    public void setSelectedPowerConsumer(String id) {
        ((PowerConsumerPickerActivity) getActivity()).setSelectedPowerConsumer(id);
    }

    private static class PowerConsumerListLoader extends
            AsyncLoaderCompat<List<PowerConsumerInfo>> {
        private final BatteryStatsHelper mStatsHelper;
        private final int mPickerType;
        private final UserManager mUserManager;
        private final PackageManager mPackageManager;

        PowerConsumerListLoader(Context context, int pickerType) {
            super(context);
            mUserManager = context.getSystemService(UserManager.class);
            mStatsHelper = new BatteryStatsHelper(context, false /* collectBatteryBroadcast */);
            mPickerType = pickerType;
            mStatsHelper.create((Bundle) null);
            mStatsHelper.clearStats();
            mPackageManager = context.getPackageManager();
        }

        @Override
        public List<PowerConsumerInfo> loadInBackground() {
            List<PowerConsumerInfoHelper.PowerConsumerInfo> powerConsumerList = new ArrayList<>();

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

                powerConsumerList.add(
                        PowerConsumerInfoHelper.makePowerConsumerInfo(mPackageManager, sipper));
            }

            powerConsumerList.sort(
                    Comparator.comparing((PowerConsumerInfo a) -> a.powerMah).reversed());
            return powerConsumerList;
        }

        @Override
        protected void onDiscardResult(List<PowerConsumerInfo> result) {
        }
    }

    private class PowerConsumerListLoaderCallbacks implements
            LoaderManager.LoaderCallbacks<List<PowerConsumerInfo>> {

        @NonNull
        @Override
        public Loader<List<PowerConsumerInfo>> onCreateLoader(int id, Bundle args) {
            return new PowerConsumerListLoader(getContext(), args.getInt(PICKER_TYPE));
        }

        @Override
        public void onLoadFinished(@NonNull Loader<List<PowerConsumerInfo>> loader,
                List<PowerConsumerInfoHelper.PowerConsumerInfo> powerConsumerList) {
            mPowerConsumerListAdapter.setPowerConsumerList(powerConsumerList);
            mAppList.setVisibility(View.VISIBLE);
            mLoadingView.setVisibility(View.GONE);
        }

        @Override
        public void onLoaderReset(
                @NonNull Loader<List<PowerConsumerInfoHelper.PowerConsumerInfo>> loader) {
        }
    }

    public class PowerConsumerListAdapter extends RecyclerView.Adapter<PowerConsumerViewHolder> {
        private final OnPowerConsumerSelectedListener mListener;
        private List<PowerConsumerInfo> mPowerConsumerList;

        public PowerConsumerListAdapter(OnPowerConsumerSelectedListener listener) {
            mListener = listener;
        }

        void setPowerConsumerList(List<PowerConsumerInfo> powerConsumerList) {
            mPowerConsumerList = powerConsumerList;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mPowerConsumerList.size();
        }

        @NonNull
        @Override
        public PowerConsumerViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
                int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(viewGroup.getContext());
            View view = layoutInflater.inflate(R.layout.power_consumer_info_layout, viewGroup,
                    false);
            return new PowerConsumerViewHolder(view, mListener);
        }

        @Override
        public void onBindViewHolder(@NonNull PowerConsumerViewHolder viewHolder, int position) {
            PowerConsumerInfoHelper.PowerConsumerInfo item = mPowerConsumerList.get(position);
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
    public static class PowerConsumerViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener {
        private final OnPowerConsumerSelectedListener mListener;

        public String id;
        public TextView titleView;
        public TextView detailsView;
        public ImageView iconView;
        public TextView packagesView;
        public TextView powerView;

        PowerConsumerViewHolder(View view, OnPowerConsumerSelectedListener listener) {
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
            mListener.onPowerConsumerSelected(id);
        }
    }
}
