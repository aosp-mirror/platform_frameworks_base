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
import android.os.Bundle;
import android.support.v4.widget.DrawerLayout;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toolbar;
import com.android.settingslib.R;

import java.util.HashMap;
import java.util.List;

public class SettingsDrawerActivity extends Activity {

    private SettingsDrawerAdapter mDrawerAdapter;
    // Hold on to a cache of tiles to avoid loading the info multiple times.
    private final HashMap<Pair<String, String>, DashboardTile> mTileCache = new HashMap<>();
    private List<DashboardCategory> mDashboardCategories;
    private DrawerLayout mDrawerLayout;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.setContentView(R.layout.settings_with_drawer);
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        // Nope.
        Toolbar toolbar = (Toolbar) findViewById(R.id.action_bar);
        setActionBar(toolbar);
        mDrawerAdapter = new SettingsDrawerAdapter(this);
        ListView listView = (ListView) findViewById(R.id.left_drawer);
        listView.setAdapter(mDrawerAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position,
                    long id) {
                onTileClicked(mDrawerAdapter.getTile(position));
            };
        });
        getActionBar().setHomeAsUpIndicator(R.drawable.ic_menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            openDrawer();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mDrawerAdapter.updateCategories();
    }

    public void openDrawer() {
        mDrawerLayout.openDrawer(Gravity.START);
    }

    public void closeDrawer() {
        mDrawerLayout.closeDrawers();
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        LayoutInflater.from(this).inflate(layoutResID,
                (ViewGroup) findViewById(R.id.content_frame));
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
        // TODO: Do this in the background with some loading.
        mDrawerAdapter.updateCategories();
        getActionBar().setDisplayHomeAsUpEnabled(mDrawerAdapter.getCount() != 0);
    }

    public List<DashboardCategory> getDashboardCategories(boolean force) {
        if (force) {
            mDashboardCategories = TileUtils.getCategories(this, mTileCache);
        }
        return mDashboardCategories;
    }

    public boolean openTile(DashboardTile tile) {
        closeDrawer();
        int numUserHandles = tile.userHandle.size();
        if (numUserHandles > 1) {
            ProfileSelectDialog.show(getFragmentManager(), tile);
            return false;
        } else if (numUserHandles == 1) {
            startActivityAsUser(tile.intent, tile.userHandle.get(0));
        } else {
            startActivity(tile.intent);
        }
        return true;
    }

    protected void onTileClicked(DashboardTile tile) {
        if (openTile(tile)) {
            finish();
        }
    }

    public void onProfileTileOpen() {
        finish();
    }
}
