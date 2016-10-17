/*
 * Copyright (C) 2016 The Dirty Unicorns Project
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
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.utils.du.DUActionUtils;
import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTile.DetailAdapter;

import java.util.ArrayList;
import java.util.Arrays;

public class NavigationBarTile extends QSTile<QSTile.State> {

    private static final String NAVBAR_MODE_ENTRIES_NAME = "systemui_navbar_mode_entries";
    private static final String NAVBAR_MODE_VALUES_NAME = "systemui_navbar_mode_values";
    private static final String SETTINGS_PACKAGE_NAME = "com.android.settings";
    private static final String NAVBAR_SETTINGS = "com.android.settings.Settings$NavigationSettingsActivity";
    private static final String FLING_SETTINGS = "com.android.settings.Settings$FlingSettingsActivity";
    private static final String SMARTBAR_SETTINGS = "com.android.settings.Settings$SmartbarSettingsActivity";

    private String[] mEntries, mValues;
    private boolean mShowingDetail;
    ArrayList<Integer> mAnimationList
            = new ArrayList<Integer>();

    public NavigationBarTile(Host host) {
        super(host);
        populateList();
    }

    private void populateList() {
        try {
            Context context = mContext.createPackageContext(SETTINGS_PACKAGE_NAME, 0);
            Resources mSettingsResources = context.getResources();
            int id = mSettingsResources.getIdentifier(NAVBAR_MODE_ENTRIES_NAME,
                    "array", SETTINGS_PACKAGE_NAME);
            if (id < 0) {
                return;
            }
            mEntries = mSettingsResources.getStringArray(id);
            id = mSettingsResources.getIdentifier(NAVBAR_MODE_VALUES_NAME,
                    "array", SETTINGS_PACKAGE_NAME);
            if (id < 0) {
                return;
            }
            mValues = mSettingsResources.getStringArray(id);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QUICK_SETTINGS;
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new NavbarDetailAdapter();
    }

    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.NAVIGATION_BAR_MODE),
                    false, mObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.NAVIGATION_BAR_VISIBLE),
                    false, mObserver);
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    @Override
    public QSTile.State newTileState() {
        return new QSTile.State();
    }

    private int getNavigationBar() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
            Settings.Secure.NAVIGATION_BAR_MODE, 0);
    }

    private boolean navbarEnabled() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
            Settings.Secure.NAVIGATION_BAR_VISIBLE,
            DUActionUtils.hasNavbarByDefault(mContext) ? 1 : 0) == 1;
    }

    @Override
    protected void handleClick() {
        if (mEntries.length > 0) {
            mShowingDetail = true;
            mAnimationList.clear();
            showDetail(true);
        }
    }

    @Override
    protected void handleSecondaryClick() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(SETTINGS_PACKAGE_NAME, NAVBAR_SETTINGS);
        mHost.startActivityDismissingKeyguard(intent);
    }

    @Override
    protected void handleLongClick() {
        if (navbarEnabled()) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(SETTINGS_PACKAGE_NAME, getNavigationBar() == 0 ? SMARTBAR_SETTINGS
                    : FLING_SETTINGS);
            mHost.startActivityDismissingKeyguard(intent);
        } else {
            // Do nothing
        }
    }

    @Override
    protected void handleUpdateState(QSTile.State state, Object arg) {
        if (mAnimationList.isEmpty() && mShowingDetail && arg == null) {
            return;
        }

        int navMode = getNavigationBar();

        if (navbarEnabled()) {
            if (navMode == 0) {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_smartbar);
                state.label = mContext.getString(R.string.quick_settings_smartbar);
            } else if (navMode == 1){
                state.icon = ResourceIcon.get(R.drawable.ic_qs_fling);
                state.label = mContext.getString(R.string.quick_settings_fling);
            }
        } else {
            if (navMode == 0) {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_smartbar_off);
                state.label = mContext.getString(R.string.quick_settings_smartbar_off);
            } else if (navMode == 1){
                state.icon = ResourceIcon.get(R.drawable.ic_qs_fling_off);
                state.label = mContext.getString(R.string.quick_settings_fling_off);
            }
        }
    }

    @Override
    public Intent getLongClickIntent() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_navigation_bar);
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
            if (!navbarEnabled()) {
                view.setVisibility(View.GONE);
            } else {
                view.setVisibility(View.VISIBLE);
            }
            notifyDataSetChanged();
            return view;
        }
    }

    private class NavbarDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItems;

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QUICK_SETTINGS;
        }   

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_navigation_bar);
        }

        @Override
        public Boolean getToggleState() {
            return navbarEnabled();
        }

        @Override
        public Intent getSettingsIntent() {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(SETTINGS_PACKAGE_NAME, NAVBAR_SETTINGS);
            return intent;
        }

        @Override
        public void setToggleState(boolean state) {
            MetricsLogger.action(mContext, getMetricsCategory());
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.NAVIGATION_BAR_VISIBLE, state ? 1 : 0);
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
            int indexOfSelection = Arrays.asList(mValues).indexOf(String.valueOf(getNavigationBar()));
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
            int selectedNavmode = Integer.valueOf(mValues[position]);
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.NAVIGATION_BAR_MODE, selectedNavmode);
        }
    }
}
