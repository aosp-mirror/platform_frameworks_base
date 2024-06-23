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

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Prefs;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.DataSaverController;

import javax.inject.Inject;

public class DataSaverTile extends QSTileImpl<BooleanState> implements
        DataSaverController.Listener{

    public static final String TILE_SPEC = "saver";

    private static final String INTERACTION_JANK_TAG = "start_data_saver";

    private final DataSaverController mDataSaverController;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;

    @Inject
    public DataSaverTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            DataSaverController dataSaverController,
            DialogTransitionAnimator dialogTransitionAnimator,
            SystemUIDialog.Factory systemUIDialogFactory
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mDataSaverController = dataSaverController;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mDataSaverController.observe(getLifecycle(), this);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DATA_SAVER_SETTINGS);
    }
    @Override
    protected void handleClick(@Nullable View view) {
        if (mState.value
                || Prefs.getBoolean(mContext, Prefs.Key.QS_DATA_SAVER_DIALOG_SHOWN, false)) {
            // Do it right away.
            toggleDataSaver();
            return;
        }

        // Show a dialog to confirm first. Dialogs shown by the DialogTransitionAnimator must be
        // created and shown on the main thread, so we post it to the UI handler.
        mUiHandler.post(() -> {
            SystemUIDialog dialog = mSystemUIDialogFactory.create();
            dialog.setTitle(com.android.internal.R.string.data_saver_enable_title);
            dialog.setMessage(com.android.internal.R.string.data_saver_description);
            dialog.setPositiveButton(com.android.internal.R.string.data_saver_enable_button,
                    (dialogInterface, which) -> {
                        toggleDataSaver();
                        Prefs.putBoolean(mContext, Prefs.Key.QS_DATA_SAVER_DIALOG_SHOWN, true);
                    });
            dialog.setNeutralButton(com.android.internal.R.string.cancel, null);
            dialog.setShowForAllUsers(true);

            if (view != null) {
                mDialogTransitionAnimator.showFromView(dialog, view, new DialogCuj(
                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                        INTERACTION_JANK_TAG));
            } else {
                dialog.show();
            }
        });
    }

    private void toggleDataSaver() {
        mState.value = !mDataSaverController.isDataSaverEnabled();
        mDataSaverController.setDataSaverEnabled(mState.value);
        refreshState(mState.value);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.data_saver);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = arg instanceof Boolean ? ((Boolean) arg).booleanValue()
                : mDataSaverController.isDataSaverEnabled();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.label = mContext.getString(R.string.data_saver);
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(state.value ? R.drawable.qs_data_saver_icon_on
                : R.drawable.qs_data_saver_icon_off);
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_DATA_SAVER;
    }

    @Override
    public void onDataSaverChanged(boolean isDataSaving) {
        refreshState(isDataSaving);
    }
}
