/*
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.app.ActivityManager;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Switch;

import com.android.internal.app.NightDisplayController;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

public class NightDisplayTile extends QSTile<QSTile.BooleanState>
        implements NightDisplayController.Callback {

    private NightDisplayController mController;
    private boolean mIsListening;

    public NightDisplayTile(Host host) {
        super(host);
        mController = new NightDisplayController(mContext, ActivityManager.getCurrentUser());
    }

    @Override
    public boolean isAvailable() {
        return NightDisplayController.isAvailable(mContext);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        final boolean activated = !mState.value;
        MetricsLogger.action(mContext, getMetricsCategory(), activated);
        mController.setActivated(activated);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        // Stop listening to the old controller.
        if (mIsListening) {
            mController.setListener(null);
        }

        // Make a new controller for the new user.
        mController = new NightDisplayController(mContext, newUserId);
        if (mIsListening) {
            mController.setListener(this);
        }

        super.handleUserSwitch(newUserId);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean isActivated = mController.isActivated();
        state.value = isActivated;
        state.label = mContext.getString(R.string.quick_settings_night_display_label);
        state.icon = ResourceIcon.get(isActivated ? R.drawable.ic_qs_night_display_on
                : R.drawable.ic_qs_night_display_off);
        state.contentDescription = mContext.getString(isActivated
                ? R.string.quick_settings_night_display_summary_on
                : R.string.quick_settings_night_display_summary_off);
        state.minimalAccessibilityClassName = state.expandedAccessibilityClassName
                = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_NIGHT_DISPLAY;
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS);
    }

    @Override
    protected void setListening(boolean listening) {
        mIsListening = listening;
        if (listening) {
            mController.setListener(this);
            refreshState();
        } else {
            mController.setListener(null);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_night_display_label);
    }

    @Override
    public void onActivated(boolean activated) {
        refreshState();
    }
}
