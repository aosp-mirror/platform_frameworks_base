/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settingslib.drawer;

import android.annotation.LayoutRes;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.ArraySet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

public class SettingsDrawerActivity extends Activity {

    protected static final boolean DEBUG_TIMING = false;
    private static final String TAG = "SettingsDrawerActivity";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String EXTRA_SHOW_MENU = "show_drawer_menu";

    // Serves as a temporary list of tiles to ignore until we heard back from the PM that they
    // are disabled.
    private static ArraySet<ComponentName> sTileBlacklist = new ArraySet<>();

    private final PackageReceiver mPackageReceiver = new PackageReceiver();
    private final List<CategoryListener> mCategoryListeners = new ArrayList<>();

    private FrameLayout mContentHeaderContainer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        long startTime = System.currentTimeMillis();

        TypedArray theme = getTheme().obtainStyledAttributes(android.R.styleable.Theme);
        if (!theme.getBoolean(android.R.styleable.Theme_windowNoTitle, false)) {
            getWindow().addFlags(LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }
        super.setContentView(R.layout.settings_with_drawer);
        mContentHeaderContainer = findViewById(R.id.content_header_container);

        Toolbar toolbar = findViewById(R.id.action_bar);
        if (theme.getBoolean(android.R.styleable.Theme_windowNoTitle, false)) {
            toolbar.setVisibility(View.GONE);
            return;
        }
        setActionBar(toolbar);

        if (DEBUG_TIMING) {
            Log.d(TAG, "onCreate took " + (System.currentTimeMillis() - startTime)
                    + " ms");
        }
    }

    @Override
    public boolean onNavigateUp() {
        if (!super.onNavigateUp()) {
            finish();
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(mPackageReceiver, filter);

        new CategoriesUpdateTask().execute();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(mPackageReceiver);
        super.onPause();
    }

    public void addCategoryListener(CategoryListener listener) {
        mCategoryListeners.add(listener);
    }

    public void remCategoryListener(CategoryListener listener) {
        mCategoryListeners.remove(listener);
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        final ViewGroup parent = findViewById(R.id.content_frame);
        if (parent != null) {
            parent.removeAllViews();
        }
        LayoutInflater.from(this).inflate(layoutResID, parent);
    }

    @Override
    public void setContentView(View view) {
        ((ViewGroup) findViewById(R.id.content_frame)).addView(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        ((ViewGroup) findViewById(R.id.content_frame)).addView(view, params);
    }

    private void onCategoriesChanged() {
        final int N = mCategoryListeners.size();
        for (int i = 0; i < N; i++) {
            mCategoryListeners.get(i).onCategoriesChanged();
        }
    }

    /**
     * @return whether or not the enabled state actually changed.
     */
    public boolean setTileEnabled(ComponentName component, boolean enabled) {
        PackageManager pm = getPackageManager();
        int state = pm.getComponentEnabledSetting(component);
        boolean isEnabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        if (isEnabled != enabled || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            if (enabled) {
                sTileBlacklist.remove(component);
            } else {
                sTileBlacklist.add(component);
            }
            pm.setComponentEnabledSetting(component, enabled
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        }
        return false;
    }

    /**
     * Updates dashboard categories. Only necessary to call this after setTileEnabled
     */
    public void updateCategories() {
        new CategoriesUpdateTask().execute();
    }

    public String getSettingPkg() {
        return TileUtils.SETTING_PKG;
    }

    public interface CategoryListener {
        void onCategoriesChanged();
    }

    private class CategoriesUpdateTask extends AsyncTask<Void, Void, Void> {

        private final CategoryManager mCategoryManager;

        public CategoriesUpdateTask() {
            mCategoryManager = CategoryManager.get(SettingsDrawerActivity.this);
        }

        @Override
        protected Void doInBackground(Void... params) {
            mCategoryManager.reloadAllCategories(SettingsDrawerActivity.this, getSettingPkg());
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mCategoryManager.updateCategoryFromBlacklist(sTileBlacklist);
            onCategoriesChanged();
        }
    }

    private class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            new CategoriesUpdateTask().execute();
        }
    }
}
