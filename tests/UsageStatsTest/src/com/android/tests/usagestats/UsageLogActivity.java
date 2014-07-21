/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.android.tests.usagestats;

import android.app.ListActivity;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;

public class UsageLogActivity extends ListActivity implements Runnable {
    private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 14;

    private UsageStatsManager mUsageStatsManager;
    private Adapter mAdapter;
    private Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUsageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        mAdapter = new Adapter();
        setListAdapter(mAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(this);
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        long beginTime = now - USAGE_STATS_PERIOD;
        UsageEvents events = mUsageStatsManager.queryEvents(beginTime, now);
        mAdapter.update(events);
        mHandler.postDelayed(this, 1000 * 5);
    }

    private class Adapter extends BaseAdapter {

        private final ArrayList<UsageEvents.Event> mEvents = new ArrayList<>();

        public void update(UsageEvents results) {
            mEvents.clear();
            while (results.hasNextEvent()) {
                UsageEvents.Event event = new UsageEvents.Event();
                results.getNextEvent(event);
                mEvents.add(event);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mEvents.size();
        }

        @Override
        public Object getItem(int position) {
            return mEvents.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(UsageLogActivity.this)
                        .inflate(R.layout.row_item, parent, false);
                holder = new ViewHolder();
                holder.packageName = (TextView) convertView.findViewById(android.R.id.text1);
                holder.state = (TextView) convertView.findViewById(android.R.id.text2);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            holder.packageName.setText(mEvents.get(position).getComponent().toShortString());
            String state;
            switch (mEvents.get(position).getEventType()) {
                case UsageEvents.Event.MOVE_TO_FOREGROUND:
                    state = "Foreground";
                    break;

                case UsageEvents.Event.MOVE_TO_BACKGROUND:
                    state = "Background";
                    break;

                default:
                    state = "Unknown: " + mEvents.get(position).getEventType();
                    break;
            }
            holder.state.setText(state);
            return convertView;
        }
    }

    static class ViewHolder {
        public TextView packageName;
        public TextView state;
    }
}
