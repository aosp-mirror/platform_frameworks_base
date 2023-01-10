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

import static android.app.admin.DevicePolicyResources.Strings.SystemUi.QS_WORK_PROFILE_LABEL;

import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.ManagedProfileController;

import javax.inject.Inject;

/** Quick settings tile: Work profile on/off */
public class WorkModeTile extends QSTileImpl<BooleanState> implements
        ManagedProfileController.Callback {
    private final Icon mIcon = ResourceIcon.get(R.drawable.stat_sys_managed_profile_status);

    private final ManagedProfileController mProfileController;

    @Inject
    public WorkModeTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            ManagedProfileController managedProfileController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mProfileController = managedProfileController;
        mProfileController.observe(getLifecycle(), this);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_MANAGED_PROFILE_SETTINGS);
    }

    @Override
    public void handleClick(@Nullable View view) {
        mProfileController.setWorkModeEnabled(!mState.value);
    }

    @Override
    public boolean isAvailable() {
        return mProfileController.hasActiveProfile();
    }

    @Override
    @MainThread
    public void onManagedProfileChanged() {
        refreshState(mProfileController.isWorkModeEnabled());
    }

    @Override
    @MainThread
    public void onManagedProfileRemoved() {
        mHost.removeTile(getTileSpec());
        mHost.unmarkTileAsAutoAdded(getTileSpec());
    }

    @Override
    public CharSequence getTileLabel() {
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getString(QS_WORK_PROFILE_LABEL,
                () -> mContext.getString(R.string.quick_settings_work_mode_label));
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
        } else {
            state.slash.isSlashed = true;
        }
        state.label = getTileLabel();
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_WORKMODE;
    }
}
