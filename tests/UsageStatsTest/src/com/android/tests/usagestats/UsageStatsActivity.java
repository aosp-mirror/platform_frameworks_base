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

import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class UsageStatsActivity extends ListActivity {
    private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 14;
    private static final String EXTRA_KEY_TIMEOUT = "com.android.tests.usagestats.extra.TIMEOUT";
    private UsageStatsManager mUsageStatsManager;
    private ClipboardManager mClipboard;
    private ClipData mClip;
    private Adapter mAdapter;
    private Comparator<UsageStats> mComparator = new Comparator<UsageStats>() {
        @Override
        public int compare(UsageStats o1, UsageStats o2) {
            return Long.compare(o2.getTotalTimeInForeground(), o1.getTotalTimeInForeground());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUsageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        mClipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        mAdapter = new Adapter();
        setListAdapter(mAdapter);
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.containsKey(UsageStatsManager.EXTRA_TIME_USED)) {
            System.err.println("UsageStatsActivity " + extras);
            Toast.makeText(this, "Timeout of observed app\n" + extras, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras != null && extras.containsKey(UsageStatsManager.EXTRA_TIME_USED)) {
            System.err.println("UsageStatsActivity " + extras);
            Toast.makeText(this, "Timeout of observed app\n" + extras, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.log:
                startActivity(new Intent(this, UsageLogActivity.class));
                return true;
            case R.id.call_is_app_inactive:
                callIsAppInactive();
                return true;
            case R.id.set_app_limit:
                callSetAppLimit();
                return true;
            case R.id.set_app_usage_limit:
                callSetAppUsageLimit();
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAdapter();
    }

    private void callIsAppInactive() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter package name");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("com.android.tests.usagestats");
        builder.setView(input);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final String packageName = input.getText().toString().trim();
                if (!TextUtils.isEmpty(packageName)) {
                    showInactive(packageName);
                }
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void callSetAppLimit() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter package name");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("com.android.tests.usagestats");
        builder.setView(input);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final String packageName = input.getText().toString().trim();
                if (!TextUtils.isEmpty(packageName)) {
                    String[] packages = packageName.split(",");
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setClass(UsageStatsActivity.this, UsageStatsActivity.class);
                    intent.setPackage(getPackageName());
                    intent.putExtra(EXTRA_KEY_TIMEOUT, true);
                    mUsageStatsManager.registerAppUsageObserver(1, packages,
                            60, TimeUnit.SECONDS, PendingIntent.getActivity(UsageStatsActivity.this,
                                    1, intent, 0));
                }
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void callSetAppUsageLimit() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter package name");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("com.android.tests.usagestats");
        builder.setView(input);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final String packageName = input.getText().toString().trim();
                if (!TextUtils.isEmpty(packageName)) {
                    String[] packages = packageName.split(",");
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setClass(UsageStatsActivity.this, UsageStatsActivity.class);
                    intent.setPackage(getPackageName());
                    intent.putExtra(EXTRA_KEY_TIMEOUT, true);
                    mUsageStatsManager.registerAppUsageLimitObserver(1, packages,
                            Duration.ofSeconds(60), Duration.ofSeconds(60),
                            PendingIntent.getActivity(UsageStatsActivity.this, 1, intent, 0));
                }
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void showInactive(String packageName) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(
                "isAppInactive(\"" + packageName + "\") = "
                        + (mUsageStatsManager.isAppInactive(packageName) ? "true" : "false"));
        builder.show();
    }

    private void updateAdapter() {
        long now = System.currentTimeMillis();
        long beginTime = now - USAGE_STATS_PERIOD;
        Map<String, UsageStats> stats = mUsageStatsManager.queryAndAggregateUsageStats(
                beginTime, now);
        mAdapter.update(stats);
    }

    private class Adapter extends BaseAdapter {
        private ArrayList<UsageStats> mStats = new ArrayList<>();

        public void update(Map<String, UsageStats> stats) {
            mStats.clear();
            if (stats == null) {
                return;
            }

            mStats.addAll(stats.values());
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
                    mStats.get(position).getTotalTimeInForeground()));

            //copy package name to the clipboard for convenience
            holder.packageName.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    String text = holder.packageName.getText().toString();
                    mClip = ClipData.newPlainText("package_name", text);
                    mClipboard.setPrimaryClip(mClip);

                    Toast.makeText(getApplicationContext(), "package name copied to clipboard",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
            });

            return convertView;
        }
    }

    private static class ViewHolder {
        TextView packageName;
        TextView usageTime;
    }
}