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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.HomeControlsPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.shared.plugins.PluginManager;

import javax.inject.Inject;


/**
 * Temporary control test for prototyping
 */
public class ControlsTile extends QSTileImpl<BooleanState> {
    private ControlsDetailAdapter mDetailAdapter;
    private final ActivityStarter mActivityStarter;
    private PluginManager mPluginManager;
    private HomeControlsPlugin mPlugin;
    private Intent mHomeAppIntent;

    @Inject
    public ControlsTile(QSHost host,
            ActivityStarter activityStarter,
            PluginManager pluginManager) {
        super(host);
        mActivityStarter = activityStarter;
        mPluginManager = pluginManager;
        mDetailAdapter = (ControlsDetailAdapter) createDetailAdapter();

        mHomeAppIntent = new Intent(Intent.ACTION_VIEW);
        mHomeAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mHomeAppIntent.setComponent(new ComponentName("com.google.android.apps.chromecast.app",
                "com.google.android.apps.chromecast.app.DiscoveryActivity"));
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {

    }

    @Override
    public void setDetailListening(boolean listening) {
        if (mPlugin == null) return;

        mPlugin.setVisible(listening);
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    public Intent getLongClickIntent() {
        return mHomeAppIntent;
    }

    @Override
    protected void handleSecondaryClick() {
        showDetail(true);
    }

    @Override
    public CharSequence getTileLabel() {
        return "Controls";
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.icon = ResourceIcon.get(R.drawable.ic_lightbulb_outline_gm2_24px);
        state.label = "Controls";
    }

    @Override
    public boolean supportsDetailView() {
        return getDetailAdapter() != null && mQSSettingsPanelOption == QSSettingsPanel.OPEN_CLICK;
    }

    @Override
    public int getMetricsCategory() {
        return -1;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return "On";
        } else {
            return "Off";
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    protected DetailAdapter createDetailAdapter() {
        mDetailAdapter = new ControlsDetailAdapter();
        return mDetailAdapter;
    }

    private class ControlsDetailAdapter implements DetailAdapter {
        private View mDetailView;
        protected LinearLayout mHomeControlsLayout;

        public CharSequence getTitle() {
            return "Controls";
        }

        public Boolean getToggleState() {
            return null;
        }

        public boolean getToggleEnabled() {
            return false;
        }

        public View createDetailView(Context context, View convertView, final ViewGroup parent) {
            mHomeControlsLayout = (LinearLayout) LayoutInflater.from(context).inflate(
                R.layout.home_controls, parent, false);
            mHomeControlsLayout.setVisibility(View.VISIBLE);
            mPluginManager.addPluginListener(
                    new PluginListener<HomeControlsPlugin>() {
                        @Override
                        public void onPluginConnected(HomeControlsPlugin plugin,
                                                      Context pluginContext) {
                            mPlugin = plugin;
                            mPlugin.sendParentGroup(mHomeControlsLayout);
                            mPlugin.setVisible(true);
                        }

                        @Override
                        public void onPluginDisconnected(HomeControlsPlugin plugin) {

                        }
                    }, HomeControlsPlugin.class, false);
            return mHomeControlsLayout;
        }

        public Intent getSettingsIntent() {
            return mHomeAppIntent;
        }

        public void setToggleState(boolean state) {

        }

        public int getMetricsCategory() {
            return -1;
        }

        public boolean hasHeader() {
            return false;
        }
    }
}
