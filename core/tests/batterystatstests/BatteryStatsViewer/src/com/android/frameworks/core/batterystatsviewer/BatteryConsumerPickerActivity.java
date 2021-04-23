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
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

/**
 * Picker, showing a sorted lists of applications and other types of entities consuming power.
 * Opens BatteryStatsViewerActivity upon item selection.
 */
public class BatteryConsumerPickerActivity extends FragmentActivity {
    private static final String PREF_SELECTED_BATTERY_CONSUMER = "batteryConsumerId";

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.battery_consumer_picker_activity_layout);

        ViewPager viewPager = findViewById(R.id.pager);

        FragmentStatePagerAdapter adapter = new FragmentStatePagerAdapter(
                getSupportFragmentManager()) {

            @Override
            public int getCount() {
                return 2;
            }

            @NonNull
            @Override
            public Fragment getItem(int position) {
                switch (position) {
                    case 0:
                        return new BatteryConsumerPickerFragment(
                                BatteryConsumerPickerFragment.PICKER_TYPE_APP);
                    case 1:
                    default:
                        return new BatteryConsumerPickerFragment(
                                BatteryConsumerPickerFragment.PICKER_TYPE_DRAIN);
                }
            }

            @Override
            public CharSequence getPageTitle(int position) {
                switch (position) {
                    case 0:
                        return "Apps";
                    case 1:
                        return "Drains";
                }
                return null;
            }
        };

        viewPager.setAdapter(adapter);
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.setupWithViewPager(viewPager);
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
}
