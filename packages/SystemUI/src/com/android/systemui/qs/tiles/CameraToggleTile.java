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

import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
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

public class CameraToggleTile extends QSTileImpl<QSTile.BooleanState> implements
        CameraToggleController.Callback {

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
        mCameraToggleController.observe(getLifecycle(), this);
    }

    @Override
    public boolean isAvailable() {
        return /*getHost().getContext().getPackageManager().hasSystemFeature(FEATURE_CAMERA_TOGGLE)
                && */whitelistIpcs(() -> DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                "camera_toggle_enabled",
                false));
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        mCameraToggleController.setCameraBlocked(!mCameraToggleController.isCameraBlocked());
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean isBlocked = arg == null ? mCameraToggleController.setCameraBlocked()
                : (boolean) arg;

        state.icon = ResourceIcon.get(R.drawable.ic_camera_blocked);
        state.state = isBlocked ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.value = isBlocked;
        state.label = getTileLabel();
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
        return mContext.getString(R.string.quick_settings_camera_label);
    }

    @Override
    public void onCameraBlockedChanged(boolean enable) {
        refreshState(enable);
    }
}
