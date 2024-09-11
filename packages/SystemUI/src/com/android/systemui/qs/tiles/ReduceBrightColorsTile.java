/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.systemui.qs.dagger.QSFlagsModule.RBC_AVAILABLE;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.accessibility.extradim.ExtraDimDialogManager;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R.drawable;

import javax.inject.Inject;
import javax.inject.Named;

/** Quick settings tile: Reduce Bright Colors **/
public class ReduceBrightColorsTile extends QSTileImpl<QSTile.BooleanState>
        implements ReduceBrightColorsController.Listener {

    public static final String TILE_SPEC = "reduce_brightness";
    private boolean mIsAvailable;
    private final ReduceBrightColorsController mReduceBrightColorsController;
    private boolean mIsListening;
    private final boolean mInUpgradeMode;
    private final ExtraDimDialogManager mExtraDimDialogManager;

    @Inject
    public ReduceBrightColorsTile(
            @Named(RBC_AVAILABLE) boolean isAvailable,
            ReduceBrightColorsController reduceBrightColorsController,
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            ExtraDimDialogManager extraDimDialogManager
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mReduceBrightColorsController = reduceBrightColorsController;
        mReduceBrightColorsController.observe(getLifecycle(), this);
        mExtraDimDialogManager = extraDimDialogManager;

        mInUpgradeMode = reduceBrightColorsController.isInUpgradeMode(mContext.getResources());
        mIsAvailable = isAvailable || mInUpgradeMode;
    }

    @Override
    public boolean isAvailable() {
        return mIsAvailable;
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return goToEvenDimmer() ? new Intent(Settings.ACTION_DISPLAY_SETTINGS) : new Intent(
                Settings.ACTION_REDUCE_BRIGHT_COLORS_SETTINGS);
    }

    private boolean goToEvenDimmer() {
        if (mInUpgradeMode) {
            return true;
        }
        return false;
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        if (goToEvenDimmer()) {
            mExtraDimDialogManager.dismissKeyguardIfNeededAndShowDialog(expandable);
        } else {
            super.handleLongClick(expandable);
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (goToEvenDimmer()) {
            mExtraDimDialogManager.dismissKeyguardIfNeededAndShowDialog(expandable);
        } else {
            mReduceBrightColorsController.setReduceBrightColorsActivated(!mState.value);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.reduce_bright_colors_feature_name);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mReduceBrightColorsController.isReduceBrightColorsActivated();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.label = mContext.getString(R.string.reduce_bright_colors_feature_name);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(state.value
                ? drawable.qs_extra_dim_icon_on
                : drawable.qs_extra_dim_icon_off);
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public void onActivated(boolean activated) {
        refreshState();
    }
}
