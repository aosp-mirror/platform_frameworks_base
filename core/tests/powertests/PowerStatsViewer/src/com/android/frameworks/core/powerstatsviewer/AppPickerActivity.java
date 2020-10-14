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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.frameworks.core.powerstatsviewer.AppInfoHelper.AppInfo;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;
import com.android.settingslib.utils.AsyncLoaderCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picker, showing a sorted list of applications consuming power.  Returns the selected
 * application UID or Process.INVALID_UID.
 */
public class AppPickerActivity extends ComponentActivity {
    private static final String TAG = "AppPicker";

    public static final ActivityResultContract<Void, Integer> CONTRACT =
            new ActivityResultContract<Void, Integer>() {
                @NonNull
                @Override
                public Intent createIntent(@NonNull Context context, Void aVoid) {
                    return new Intent(context, AppPickerActivity.class);
                }

                @Override
                public Integer parseResult(int resultCode, @Nullable Intent intent) {
                    if (resultCode != RESULT_OK || intent == null) {
                        return Process.INVALID_UID;
                    }
                    return intent.getIntExtra(Intent.EXTRA_UID, Process.INVALID_UID);
                }
            };

    private AppListAdapter mAppListAdapter;
    private RecyclerView mAppList;
    private View mLoadingView;

    private interface OnAppSelectedListener {
        void onAppSelected(int uid);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        setContentView(R.layout.app_picker_layout);

        mLoadingView = findViewById(R.id.loading_view);

        mAppList = findViewById(R.id.app_list_view);
        mAppList.setLayoutManager(new LinearLayoutManager(this));
        mAppListAdapter = new AppListAdapter(AppPickerActivity.this::setSelectedUid);
        mAppList.setAdapter(mAppListAdapter);

        LoaderManager.getInstance(this).initLoader(0, null,
                new AppListLoaderCallbacks());
    }

    protected void setSelectedUid(int uid) {
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_UID, uid);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public boolean onNavigateUp() {
        onBackPressed();
        return true;
    }

    private static class AppListLoader extends AsyncLoaderCompat<List<AppInfo>> {
        private final BatteryStatsHelper mStatsHelper;
        private final UserManager mUserManager;
        private final PackageManager mPackageManager;

        AppListLoader(Context context) {
            super(context);
            mUserManager = context.getSystemService(UserManager.class);
            mStatsHelper = new BatteryStatsHelper(context, false /* collectBatteryBroadcast */);
            mStatsHelper.create((Bundle) null);
            mStatsHelper.clearStats();
            mPackageManager = context.getPackageManager();
        }

        @Override
        public List<AppInfo> loadInBackground() {
            List<AppInfo> applicationList = new ArrayList<>();

            mStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED,
                    mUserManager.getUserProfiles());

            final List<BatterySipper> usageList = mStatsHelper.getUsageList();
            for (BatterySipper sipper : usageList) {
                AppInfo info =
                        AppInfoHelper.makeApplicationInfo(mPackageManager, sipper.getUid(), sipper);
                if (info != null) {
                    applicationList.add(info);
                }
            }

            applicationList.sort(
                    Comparator.comparing((AppInfo a) -> a.powerMah).reversed());
            return applicationList;
        }

        @Override
        protected void onDiscardResult(List<AppInfo> result) {
        }
    }

    private class AppListLoaderCallbacks implements
            LoaderManager.LoaderCallbacks<List<AppInfo>> {

        @NonNull
        @Override
        public Loader<List<AppInfo>> onCreateLoader(int id, Bundle args) {
            return new AppListLoader(AppPickerActivity.this);
        }

        @Override
        public void onLoadFinished(@NonNull Loader<List<AppInfo>> loader,
                List<AppInfo> applicationList) {
            mAppListAdapter.setApplicationList(applicationList);
            mAppList.setVisibility(View.VISIBLE);
            mLoadingView.setVisibility(View.GONE);
        }

        @Override
        public void onLoaderReset(@NonNull Loader<List<AppInfo>> loader) {
        }
    }

    public class AppListAdapter extends RecyclerView.Adapter<AppViewHolder> {
        private final OnAppSelectedListener mListener;
        private List<AppInfo> mApplicationList;

        public AppListAdapter(OnAppSelectedListener listener) {
            mListener = listener;
        }

        void setApplicationList(List<AppInfo> applicationList) {
            mApplicationList = applicationList;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mApplicationList.size();
        }

        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(viewGroup.getContext());
            View view = layoutInflater.inflate(R.layout.app_info_layout, viewGroup, false);
            return new AppViewHolder(view, mListener);
        }

        @Override
        public void onBindViewHolder(@NonNull AppViewHolder appViewHolder, int position) {
            AppInfo item = mApplicationList.get(position);
            appViewHolder.uid = item.uid;
            appViewHolder.titleView.setText(item.label);
            appViewHolder.uidView.setText(
                    String.format(Locale.getDefault(), "UID: %d", item.uid));
            appViewHolder.powerView.setText(
                    String.format(Locale.getDefault(), "%.1f mAh", item.powerMah));
            appViewHolder.iconView.setImageDrawable(
                    item.iconInfo.loadIcon(getPackageManager()));
            if (item.packages != null) {
                appViewHolder.packagesView.setText(item.packages);
                appViewHolder.packagesView.setVisibility(View.VISIBLE);
            } else {
                appViewHolder.packagesView.setVisibility(View.GONE);
            }
        }
    }

    // View Holder used when displaying apps
    public static class AppViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener {
        private final OnAppSelectedListener mListener;

        public int uid;
        public TextView titleView;
        public TextView uidView;
        public ImageView iconView;
        public TextView packagesView;
        public TextView powerView;

        AppViewHolder(View view, OnAppSelectedListener listener) {
            super(view);
            mListener = listener;
            view.setOnClickListener(this);
            titleView = view.findViewById(android.R.id.title);
            uidView = view.findViewById(R.id.uid);
            iconView = view.findViewById(android.R.id.icon);
            packagesView = view.findViewById(R.id.packages);
            powerView = view.findViewById(R.id.power_mah);
            powerView.setVisibility(View.VISIBLE);
        }

        @Override
        public void onClick(View v) {
            mListener.onAppSelected(uid);
        }
    }
}
