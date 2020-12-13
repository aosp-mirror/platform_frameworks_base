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

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

/**
 * Picker, showing a sorted lists of applications and other types of entities consuming power.
 * Returns the selected entity ID or null.
 */
public class BatteryConsumerPickerActivity extends FragmentActivity {

    public static final ActivityResultContract<Void, String> CONTRACT =
            new ActivityResultContract<Void, String>() {
                @NonNull
                @Override
                public Intent createIntent(@NonNull Context context, Void aVoid) {
                    return new Intent(context, BatteryConsumerPickerActivity.class);
                }

                @Override
                public String parseResult(int resultCode, @Nullable Intent intent) {
                    if (resultCode != RESULT_OK || intent == null) {
                        return null;
                    }
                    return intent.getStringExtra(Intent.EXTRA_RETURN_RESULT);
                }
            };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        getActionBar().setDisplayHomeAsUpEnabled(true);

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
    }

    public void setSelectedBatteryConsumer(String batteryConsumerId) {
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, batteryConsumerId);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public boolean onNavigateUp() {
        onBackPressed();
        return true;
    }
}
