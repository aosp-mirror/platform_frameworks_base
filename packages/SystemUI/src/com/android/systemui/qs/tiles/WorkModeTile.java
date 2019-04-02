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

import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.ManagedProfileController;

import javax.inject.Inject;

/** Quick settings tile: Work profile on/off */
public class WorkModeTile extends QSTileImpl<BooleanState> implements
        ManagedProfileController.Callback {
    private final Icon mIcon = ResourceIcon.get(R.drawable.stat_sys_managed_profile_status);

    private final ManagedProfileController mProfileController;

    @Inject
    public WorkModeTile(QSHost host, ManagedProfileController managedProfileController) {
        super(host);
        mProfileController = managedProfileController;
        mProfileController.observe(getLifecycle(), this);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_MANAGED_PROFILE_SETTINGS);
    }

    @Override
    public void handleClick() {
        mProfileController.setWorkModeEnabled(!mState.value);
    }

    @Override
    public boolean isAvailable() {
        return mProfileController.hasActiveProfile();
    }

    @Override
    public void onManagedProfileChanged() {
        refreshState(mProfileController.isWorkModeEnabled());
    }

    @Override
    public void onManagedProfileRemoved() {
        mHost.removeTile(getTileSpec());
        mHost.unmarkTileAsAutoAdded(getTileSpec());
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_work_mode_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (!isAvailable()) {
            onManagedProfileRemoved();
        }

        if (state.slash == null) {
            state.slash = new SlashState();
        }

        if (arg instanceof Boolean) {
            state.value = (Boolean) arg;
        } else {
            state.value = mProfileController.isWorkModeEnabled();
        }

        state.icon = mIcon;
        if (state.value) {
            state.slash.isSlashed = false;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_work_mode_on);
        } else {
            state.slash.isSlashed = true;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_work_mode_off);
        }
        state.label = mContext.getString(R.string.quick_settings_work_mode_label);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_WORKMODE;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_work_mode_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_work_mode_changed_off);
        }
    }
}
