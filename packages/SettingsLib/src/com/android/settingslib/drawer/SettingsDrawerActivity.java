/**
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib.drawer;

import android.annotation.LayoutRes;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.v4.widget.DrawerLayout;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toolbar;

import com.android.settingslib.R;
import com.android.settingslib.applications.InterestingConfigChanges;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SettingsDrawerActivity extends Activity {

    protected static final boolean DEBUG_TIMING = false;
    private static final String TAG = "SettingsDrawerActivity";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String EXTRA_SHOW_MENU = "show_drawer_menu";

    private static List<DashboardCategory> sDashboardCategories;
    private static HashMap<Pair<String, String>, Tile> sTileCache;
    // Serves as a temporary list of tiles to ignore until we heard back from the PM that they
    // are disabled.
    private static ArraySet<ComponentName> sTileBlacklist = new ArraySet<>();
    private static InterestingConfigChanges sConfigTracker;

    private final PackageReceiver mPackageReceiver = new PackageReceiver();
    private final List<CategoryListener> mCategoryListeners = new ArrayList<>();

    private SettingsDrawerAdapter mDrawerAdapter;
    private FrameLayout mContentHeaderContainer;
    private DrawerLayout mDrawerLayout;
    private boolean mShowingMenu;
    private UserManager mUserManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        long startTime = System.currentTimeMillis();

        TypedArray theme = getTheme().obtainStyledAttributes(android.R.styleable.Theme);
        if (!theme.getBoolean(android.R.styleable.Theme_windowNoTitle, false)) {
            getWindow().addFlags(LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().addFlags(LayoutParams.FLAG_TRANSLUCENT_STATUS);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }
        super.setContentView(R.layout.settings_with_drawer);
        mContentHeaderContainer = (FrameLayout) findViewById(R.id.content_header_container);
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (mDrawerLayout == null) {
            return;
        }
        Toolbar toolbar = (Toolbar) findViewById(R.id.action_bar);
        if (theme.getBoolean(android.R.styleable.Theme_windowNoTitle, false)) {
            toolbar.setVisibility(View.GONE);
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            mDrawerLayout = null;
            return;
        }
        getDashboardCategories();
        setActionBar(toolbar);
        mDrawerAdapter = new SettingsDrawerAdapter(this);
        ListView listView = (ListView) findViewById(R.id.left_drawer);
        listView.setAdapter(mDrawerAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position,
                    long id) {
                onTileClicked(mDrawerAdapter.getTile(position));
            }
        });

        mUserManager = UserManager.get(this);
        if (DEBUG_TIMING) Log.d(TAG, "onCreate took " + (System.currentTimeMillis() - startTime)
                + " ms");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mShowingMenu && mDrawerLayout != null && item.getItemId() == android.R.id.home
                && mDrawerAdapter.getCount() != 0) {
            openDrawer();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mDrawerLayout != null) {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            registerReceiver(mPackageReceiver, filter);

            new CategoriesUpdater().execute();
        }
        final Intent intent = getIntent();
        if (intent != null) {
            if (intent.hasExtra(EXTRA_SHOW_MENU)) {
                if (intent.getBooleanExtra(EXTRA_SHOW_MENU, false)) {
                    // Intent explicitly set to show menu.
                    showMenuIcon();
                }
            } else if (isTopLevelTile(intent)) {
                showMenuIcon();
            }
        }
    }

    @Override
    protected void onPause() {
        if (mDrawerLayout != null) {
            unregisterReceiver(mPackageReceiver);
        }

        super.onPause();
    }

    private boolean isTopLevelTile(Intent intent) {
        final ComponentName componentName = intent.getComponent();
        if (componentName == null) {
            return false;
        }
        // Look for a tile that has the same component as incoming intent
        final List<DashboardCategory> categories = getDashboardCategories();
        for (DashboardCategory category : categories) {
            for (Tile tile : category.tiles) {
                if (TextUtils.equals(tile.intent.getComponent().getClassName(),
                        componentName.getClassName())) {
                    if (DEBUG) {
                        Log.d(TAG, "intent is for top level tile: " + tile.title);
                    }
                    return true;
                }
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Intent is not for top level settings " + intent);
        }
        return false;
    }

    public void addCategoryListener(CategoryListener listener) {
        mCategoryListeners.add(listener);
    }

    public void remCategoryListener(CategoryListener listener) {
        mCategoryListeners.remove(listener);
    }

    public void setIsDrawerPresent(boolean isPresent) {
        if (isPresent) {
            mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
            updateDrawer();
        } else {
            if (mDrawerLayout != null) {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
                mDrawerLayout = null;
            }
        }
    }

    public void openDrawer() {
        if (mDrawerLayout != null) {
            mDrawerLayout.openDrawer(Gravity.START);
        }
    }

    public void closeDrawer() {
        if (mDrawerLayout != null) {
            mDrawerLayout.closeDrawers();
        }
    }

    public void setContentHeaderView(View headerView) {
        mContentHeaderContainer.removeAllViews();
        if (headerView != null) {
            mContentHeaderContainer.addView(headerView);
        }
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        final ViewGroup parent = (ViewGroup) findViewById(R.id.content_frame);
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

    public void updateDrawer() {
        if (mDrawerLayout == null) {
            return;
        }
        // TODO: Do this in the background with some loading.
        mDrawerAdapter.updateCategories();
        if (mDrawerAdapter.getCount() != 0) {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        } else {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }
    }

    public void showMenuIcon() {
        mShowingMenu = true;
        getActionBar().setHomeAsUpIndicator(R.drawable.ic_menu);
        getActionBar().setHomeActionContentDescription(R.string.content_description_menu_button);
        getActionBar().setDisplayHomeAsUpEnabled(true);
    }

    public List<DashboardCategory> getDashboardCategories() {
        if (sDashboardCategories == null) {
            sTileCache = new HashMap<>();
            sConfigTracker = new InterestingConfigChanges();
            // Apply initial current config.
            sConfigTracker.applyNewConfig(getResources());
            sDashboardCategories = TileUtils.getCategories(this, sTileCache);
        }
        return sDashboardCategories;
    }

    protected void onCategoriesChanged() {
        updateDrawer();
        final int N = mCategoryListeners.size();
        for (int i = 0; i < N; i++) {
            mCategoryListeners.get(i).onCategoriesChanged();
        }
    }

    public boolean openTile(Tile tile) {
        closeDrawer();
        if (tile == null) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TASK));
            return true;
        }
        try {
            updateUserHandlesIfNeeded(tile);
            int numUserHandles = tile.userHandle.size();
            if (numUserHandles > 1) {
                ProfileSelectDialog.show(getFragmentManager(), tile);
                return false;
            } else if (numUserHandles == 1) {
                // Show menu on top level items.
                tile.intent.putExtra(EXTRA_SHOW_MENU, true);
                tile.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivityAsUser(tile.intent, tile.userHandle.get(0));
            } else {
                // Show menu on top level items.
                tile.intent.putExtra(EXTRA_SHOW_MENU, true);
                tile.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(tile.intent);
            }
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Couldn't find tile " + tile.intent, e);
        }
        return true;
    }

    private void updateUserHandlesIfNeeded(Tile tile) {
        List<UserHandle> userHandles = tile.userHandle;

        for (int i = userHandles.size() - 1; i >= 0; i--) {
            if (mUserManager.getUserInfo(userHandles.get(i).getIdentifier()) == null) {
                if (DEBUG) {
                    Log.d(TAG, "Delete the user: " + userHandles.get(i).getIdentifier());
                }
                userHandles.remove(i);
            }
        }
    }

    protected void onTileClicked(Tile tile) {
        if (openTile(tile)) {
            finish();
        }
    }

    public HashMap<Pair<String, String>, Tile> getTileCache() {
        if (sTileCache == null) {
            getDashboardCategories();
        }
        return sTileCache;
    }

    public void onProfileTileOpen() {
        finish();
    }

    public void setTileEnabled(ComponentName component, boolean enabled) {
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
            new CategoriesUpdater().execute();
        }
    }

    public interface CategoryListener {
        void onCategoriesChanged();
    }

    private class CategoriesUpdater extends AsyncTask<Void, Void, List<DashboardCategory>> {
        @Override
        protected List<DashboardCategory> doInBackground(Void... params) {
            if (sConfigTracker.applyNewConfig(getResources())) {
                sTileCache.clear();
            }
            return TileUtils.getCategories(SettingsDrawerActivity.this, sTileCache);
        }

        @Override
        protected void onPreExecute() {
            if (sConfigTracker == null || sTileCache == null) {
                getDashboardCategories();
            }
        }

        @Override
        protected void onPostExecute(List<DashboardCategory> dashboardCategories) {
            for (int i = 0; i < dashboardCategories.size(); i++) {
                DashboardCategory category = dashboardCategories.get(i);
                for (int j = 0; j < category.tiles.size(); j++) {
                    Tile tile = category.tiles.get(j);
                    if (sTileBlacklist.contains(tile.intent.getComponent())) {
                        category.tiles.remove(j--);
                    }
                }
            }
            sDashboardCategories = dashboardCategories;
            onCategoriesChanged();
        }
    }

    private class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            new CategoriesUpdater().execute();
        }
    }
}
