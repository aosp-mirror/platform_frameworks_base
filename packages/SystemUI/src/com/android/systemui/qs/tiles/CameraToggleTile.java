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

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.CameraToggleController;

import javax.inject.Inject;

public class CameraToggleTile extends QSTileImpl<QSTile.BooleanState> {

    private CameraToggleController mCameraToggleController;

    @Inject
    protected CameraToggleTile(QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            CameraToggleController cameraToggleController) {
        super(host, backgroundLooper, mainHandler, metricsLogger, statusBarStateController,
                activityStarter, qsLogger);
        mCameraToggleController = cameraToggleController;
        mCameraToggleController.addCallback((b) -> refreshState());
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        mCameraToggleController.setCameraEnabled(!mCameraToggleController.isCameraEnabled());
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.icon = new CameraToggleTileIcon();
        state.state = mCameraToggleController.isCameraEnabled()
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.value = mCameraToggleController.isCameraEnabled();
        state.label = "Camera";
        if (!mCameraToggleController.isCameraAvailable()) {
            state.secondaryLabel = "Currently in use";
        } else {
            state.secondaryLabel = null;
        }
        state.handlesLongClick = false;
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return "Camera";
    }

    class CameraToggleTileIcon extends Icon {

        @Override
        public Drawable getDrawable(Context context) {
            return context.getDrawable(R.drawable.ic_camera);
        }
    }
}
