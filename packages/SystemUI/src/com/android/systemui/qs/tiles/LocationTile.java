/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2017 The ABC rom
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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTile.DetailAdapter;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;

import java.util.ArrayList;
import java.util.Arrays;

public class LocationTile extends QSTile<QSTile.State> {

    private final LocationController mController;
    private final Callback mCallback = new Callback();

    private String[] mEntries;
    private Integer[] mValues = {Settings.Secure.LOCATION_MODE_BATTERY_SAVING,
                Settings.Secure.LOCATION_MODE_SENSORS_ONLY,
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
    };
    private boolean mShowingDetail;
    ArrayList<Integer> mAnimationList
            = new ArrayList<Integer>();

    private final AnimationIcon mEnable =
            new AnimationIcon(R.drawable.ic_signal_location_enable_animation,
                    R.drawable.ic_signal_location_disable);
    private final AnimationIcon mDisable =
            new AnimationIcon(R.drawable.ic_signal_location_disable_animation,
                    R.drawable.ic_signal_location_enable);

    public LocationTile(Host host) {
        super(host);
        mEntries = mContext.getResources().getStringArray(R.array.location_mode_entries);
        mController = host.getLocationController();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.FLASH;
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new LocationDetailAdapter();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addSettingsChangedCallback(mCallback);
        } else {
            mController.removeSettingsChangedCallback(mCallback);
        }
    }

    private final class Callback implements LocationSettingsChangeCallback {
        @Override
        public void onLocationSettingsChanged(boolean enabled) {
            refreshState();
        }
    };

    @Override
    public QSTile.State newTileState() {
        return new QSTile.State();
    }

    @Override
    protected void handleClick() {
        mShowingDetail = true;
        mAnimationList.clear();
        MetricsLogger.action(mContext, getMetricsCategory());
        if (!mController.isLocationEnabled()) {
            mController.setLocationEnabled(true);
        }
        showDetail(true);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }

    @Override
    protected void handleUpdateState(QSTile.State state, Object arg) {
        if (mAnimationList.isEmpty() && mShowingDetail && arg == null) {
            return;
        }
        int currentMode = mController.getLocationCurrentState();
        switch (currentMode) {
            case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_battery_saving);
                state.label = mContext.getString(R.string.quick_settings_location_battery_saving_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_location_battery_saving);
                break;
            case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_gps_only);
                state.label = mContext.getString(R.string.quick_settings_location_gps_only_label);
                state.icon = mEnable;
                break;
            case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_high_accuracy);
                state.label = mContext.getString(R.string.quick_settings_location_high_accuracy_label);
                state.icon = mEnable;
                break;
            default:
                state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_location_off);
                state.label = mContext.getString(R.string.quick_settings_location_off_custom_label);
                state.icon = mDisable;
                break;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_location_label);
    }

    private class RadioAdapter extends ArrayAdapter<String> {

        public RadioAdapter(Context context, int resource, String[] objects) {
            super(context, resource, objects);
        }

        public RadioAdapter(Context context, int resource,
                            int textViewResourceId, String[] objects) {
            super(context, resource, textViewResourceId, objects);
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            view = super.getView(position, view, parent);
            view.setMinimumHeight(mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_detail_item_height));
            if (!mController.isLocationEnabled()) {
                view.setVisibility(View.GONE);
            } else {
                view.setVisibility(View.VISIBLE);
            }
            notifyDataSetChanged();
            return view;
        }
    }

    private class LocationDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItems;

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.FLASH;
        }

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_location_label);
        }

        @Override
        public Boolean getToggleState() {
            return mController.isLocationEnabled();
        }

        @Override
        public void setToggleState(boolean state) {
            MetricsLogger.action(mContext, getMetricsCategory());
            if (!state) {
                mController.setLocationEnabled(state);
                showDetail(false);
            }
        }

        @Override
        public Intent getSettingsIntent() {
            return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItems = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            ListView listView = mItems.getListView();
            listView.setOnItemClickListener(this);
            listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            listView.setDivider(null);
            RadioAdapter adapter = new RadioAdapter(context,
                    android.R.layout.simple_list_item_single_choice, mEntries);
            int indexOfSelection = Arrays.asList(mValues).indexOf(mController.getLocationCurrentState());
            mItems.setAdapter(adapter);
            listView.setItemChecked(indexOfSelection, true);
            mItems.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mUiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mShowingDetail = false;
                            refreshState(true);
                        }
                    }, 100);
                }
            });
            return mItems;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            int selectedLocationmode = mValues[position];
            mController.setLocationMode(selectedLocationmode);
        }
    }
}
