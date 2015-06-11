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
import android.support.v4.util.CircularArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

public class UsageLogActivity extends ListActivity implements Runnable {
    private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 14;

    private UsageStatsManager mUsageStatsManager;
    private Adapter mAdapter;
    private Handler mHandler = new Handler();
    private long mLastTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUsageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        mLastTime = System.currentTimeMillis() - USAGE_STATS_PERIOD;

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
        UsageEvents events = mUsageStatsManager.queryEvents(mLastTime, now);
        long lastEventTime = mAdapter.update(events);
        if (lastEventTime >= 0) {
            mLastTime = lastEventTime + 1;
        }
        mHandler.postDelayed(this, 1000 * 5);
    }

    private class Adapter extends BaseAdapter {
        private static final int MAX_EVENTS = 50;
        private final CircularArray<UsageEvents.Event> mEvents = new CircularArray<>(MAX_EVENTS);

        public long update(UsageEvents results) {
            long lastTimeStamp = -1;
            while (results.hasNextEvent()) {
                UsageEvents.Event event = new UsageEvents.Event();
                results.getNextEvent(event);
                lastTimeStamp = event.getTimeStamp();
                if (mEvents.size() == MAX_EVENTS) {
                    mEvents.popLast();
                }
                mEvents.addFirst(event);
            }

            if (lastTimeStamp != 0) {
                notifyDataSetChanged();
            }
            return lastTimeStamp;
        }

        @Override
        public int getCount() {
            return mEvents.size();
        }

        @Override
        public UsageEvents.Event getItem(int position) {
            return mEvents.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getItemViewType(int position) {
            final int eventType = getItem(position).getEventType();
            if (eventType == UsageEvents.Event.CONFIGURATION_CHANGE) {
                return 1;
            }
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final UsageEvents.Event event = getItem(position);

            final ViewHolder holder;
            if (convertView == null) {
                holder = new ViewHolder();

                if (event.getEventType() == UsageEvents.Event.CONFIGURATION_CHANGE) {
                    convertView = LayoutInflater.from(UsageLogActivity.this)
                            .inflate(R.layout.config_row_item, parent, false);
                    holder.config = (TextView) convertView.findViewById(android.R.id.text1);
                } else {
                    convertView = LayoutInflater.from(UsageLogActivity.this)
                            .inflate(R.layout.row_item, parent, false);
                    holder.packageName = (TextView) convertView.findViewById(android.R.id.text1);
                    holder.state = (TextView) convertView.findViewById(android.R.id.text2);
                }
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            if (holder.packageName != null) {
                holder.packageName.setText(event.getPackageName());
            }

            if (holder.state != null) {
                holder.state.setText(eventToString(event.getEventType()));
            }

            if (holder.config != null &&
                    event.getEventType() == UsageEvents.Event.CONFIGURATION_CHANGE) {
                holder.config.setText(event.getConfiguration().toString());
            }
            return convertView;
        }

        private String eventToString(int eventType) {
            switch (eventType) {
                case UsageEvents.Event.MOVE_TO_FOREGROUND:
                    return "Foreground";

                case UsageEvents.Event.MOVE_TO_BACKGROUND:
                    return "Background";

                case UsageEvents.Event.CONFIGURATION_CHANGE:
                    return "Config change";

                case UsageEvents.Event.USER_INTERACTION:
                    return "User Interaction";

                default:
                    return "Unknown: " + eventType;
            }
        }
    }

    static class ViewHolder {
        public TextView packageName;
        public TextView state;
        public TextView config;
    }
}
