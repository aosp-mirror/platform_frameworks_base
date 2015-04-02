/*
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

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.ZenModePanel;

/** Quick settings tile: Do not disturb **/
public class DndTile extends QSTile<QSTile.BooleanState> {
    private static final Intent ZEN_SETTINGS = new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);

    private static final String ACTION_SET_VISIBLE = "com.android.systemui.dndtile.SET_VISIBLE";
    private static final String EXTRA_VISIBLE = "visible";
    private static final String PREF_KEY_VISIBLE = "DndTileVisible";
    private static final String PREF_KEY_COMBINED_ICON = "DndTileCombinedIcon";

    private final ZenModeController mController;
    private final DndDetailAdapter mDetailAdapter;

    private boolean mListening;
    private boolean mVisible;
    private boolean mShowingDetail;

    public DndTile(Host host) {
        super(host);
        mController = host.getZenModeController();
        mDetailAdapter = new DndDetailAdapter();
        mVisible = isVisible(host.getContext());
        mContext.registerReceiver(mReceiver, new IntentFilter(ACTION_SET_VISIBLE));
    }

    public static void setVisible(Context context, boolean visible) {
        context.sendBroadcast(new Intent(DndTile.ACTION_SET_VISIBLE)
                .putExtra(DndTile.EXTRA_VISIBLE, visible));
    }

    public static boolean isVisible(Context context) {
        return getSharedPrefs(context).getBoolean(PREF_KEY_VISIBLE, false);
    }

    public static void setCombinedIcon(Context context, boolean combined) {
        getSharedPrefs(context).edit().putBoolean(PREF_KEY_COMBINED_ICON, combined).commit();
    }

    public static boolean isCombinedIcon(Context context) {
        return getSharedPrefs(context).getBoolean(PREF_KEY_COMBINED_ICON, false);
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        if (mState.value) {
            mController.setZen(Global.ZEN_MODE_OFF);
        } else {
            mController.setZen(Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
            showDetail(true);
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int zen = arg instanceof Integer ? (Integer) arg : mController.getZen();
        state.value = zen != Global.ZEN_MODE_OFF;
        state.visible = mVisible;
        switch (zen) {
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_dnd_on);
                state.label = mContext.getString(R.string.quick_settings_dnd_priority_label);
                state.contentDescription = mContext.getString(
                        R.string.accessibility_quick_settings_dnd_priority_on);
                break;
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_dnd_on);
                state.label = mContext.getString(R.string.quick_settings_dnd_none_label);
                state.contentDescription = mContext.getString(
                        R.string.accessibility_quick_settings_dnd_none_on);
                break;
            default:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_dnd_off);
                state.label = mContext.getString(R.string.quick_settings_dnd_label);
                state.contentDescription =  mContext.getString(
                        R.string.accessibility_quick_settings_dnd_off);
                break;
        }
        if (mShowingDetail && !state.value) {
            showDetail(false);
        }
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_dnd_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_dnd_changed_off);
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (mListening) {
            mController.addCallback(mZenCallback);
        } else {
            mController.removeCallback(mZenCallback);
        }
    }

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        public void onZenChanged(int zen) {
            refreshState(zen);
        }
    };

    private static SharedPreferences getSharedPrefs(Context context) {
        return context.getSharedPreferences(context.getPackageName(), 0);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mVisible = intent.getBooleanExtra(EXTRA_VISIBLE, false);
            getSharedPrefs(mContext).edit().putBoolean(PREF_KEY_VISIBLE, mVisible).commit();
            refreshState();
        }
    };

    private final class DndDetailAdapter implements DetailAdapter, OnAttachStateChangeListener {

        @Override
        public int getTitle() {
            return R.string.quick_settings_dnd_label;
        }

        @Override
        public Boolean getToggleState() {
            return mState.value;
        }

        @Override
        public Intent getSettingsIntent() {
            return ZEN_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            if (!state) {
                mController.setZen(Global.ZEN_MODE_OFF);
                showDetail(false);
            }
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            final ZenModePanel zmp = convertView != null ? (ZenModePanel) convertView
                    : (ZenModePanel) LayoutInflater.from(context).inflate(
                            R.layout.zen_mode_panel, parent, false);
            if (convertView == null) {
                zmp.init(mController);
                zmp.setEmbedded(true);
                zmp.addOnAttachStateChangeListener(this);
            }
            return zmp;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            mShowingDetail = true;
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mShowingDetail = false;
        }
    }
}
