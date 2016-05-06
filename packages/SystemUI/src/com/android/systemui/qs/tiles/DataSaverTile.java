/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.content.DialogInterface;
import android.content.Intent;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.DataSaverController;

public class DataSaverTile extends QSTile<QSTile.BooleanState> implements
        DataSaverController.Listener{

    private final DataSaverController mDataSaverController;

    public DataSaverTile(Host host) {
        super(host);
        mDataSaverController = host.getNetworkController().getDataSaverController();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mDataSaverController.addListener(this);
        } else {
            mDataSaverController.remListener(this);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return CellularTile.CELLULAR_SETTINGS;
    }

    @Override
    protected void handleClick() {
        if (mState.value
                || Prefs.getBoolean(mContext, Prefs.Key.QS_DATA_SAVER_DIALOG_SHOWN, false)) {
            // Do it right away.
            toggleDataSaver();
            return;
        }
        // Shows dialog first
        SystemUIDialog dialog = new SystemUIDialog(mContext);
        dialog.setTitle(com.android.internal.R.string.data_saver_enable_title);
        dialog.setMessage(com.android.internal.R.string.data_saver_description);
        dialog.setPositiveButton(com.android.internal.R.string.data_saver_enable_button,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        toggleDataSaver();
                    }
                });
        dialog.setNegativeButton(com.android.internal.R.string.cancel, null);
        dialog.setShowForAllUsers(true);
        dialog.show();
        Prefs.putBoolean(mContext, Prefs.Key.QS_DATA_SAVER_DIALOG_SHOWN, true);
    }

    private void toggleDataSaver() {
        mState.value = !mDataSaverController.isDataSaverEnabled();
        MetricsLogger.action(mContext, getMetricsCategory(), mState.value);
        mDataSaverController.setDataSaverEnabled(mState.value);
        refreshState(mState.value);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.data_saver);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = arg instanceof Boolean ? (Boolean) arg
                : mDataSaverController.isDataSaverEnabled();
        state.label = mContext.getString(R.string.data_saver);
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(state.value ? R.drawable.ic_data_saver
                : R.drawable.ic_data_saver_off);
        state.minimalAccessibilityClassName = state.expandedAccessibilityClassName
                = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_DATA_SAVER;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_data_saver_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_data_saver_changed_off);
        }
    }

    @Override
    public void onDataSaverChanged(boolean isDataSaving) {
        refreshState(isDataSaving);
    }
}