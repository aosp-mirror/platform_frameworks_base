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
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.ManagedProfileController;

/** Quick settings tile: Work profile on/off */
public class WorkModeTile extends QSTile<QSTile.BooleanState> implements
        ManagedProfileController.Callback {
    private final AnimationIcon mEnable =
            new AnimationIcon(R.drawable.ic_signal_workmode_enable_animation,
                    R.drawable.ic_signal_workmode_disable);
    private final AnimationIcon mDisable =
            new AnimationIcon(R.drawable.ic_signal_workmode_disable_animation,
                    R.drawable.ic_signal_workmode_enable);

    private final ManagedProfileController mProfileController;

    public WorkModeTile(Host host) {
        super(host);
        mProfileController = host.getManagedProfileController();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mProfileController.addCallback(this);
        } else {
            mProfileController.removeCallback(this);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_SYNC_SETTINGS);
    }

    @Override
    public void handleClick() {
        MetricsLogger.action(mContext, getMetricsCategory(), !mState.value);
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
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_work_mode_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (arg instanceof Boolean) {
            state.value = (Boolean) arg;
        } else {
            state.value = mProfileController.isWorkModeEnabled();
        }

        state.label = mContext.getString(R.string.quick_settings_work_mode_label);
        if (state.value) {
            state.icon = mEnable;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_work_mode_on);
        } else {
            state.icon = mDisable;
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_work_mode_off);
        }
        state.minimalAccessibilityClassName = state.expandedAccessibilityClassName
                = Switch.class.getName();
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
