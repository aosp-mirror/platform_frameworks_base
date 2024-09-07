/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;

import static com.android.systemui.statusbar.policy.RotationLockControllerImpl.hasSufficientPermission;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/** Quick settings tile: Rotation **/
public class RotationLockTile extends QSTileImpl<BooleanState> implements
        BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "rotation";

    private static final String EMPTY_SECONDARY_STRING = "";

    private final Icon mIcon = ResourceIcon.get(com.android.internal.R.drawable.ic_qs_auto_rotate);
    private final RotationLockController mController;
    private final SensorPrivacyManager mPrivacyManager;
    private final BatteryController mBatteryController;
    private final UserSettingObserver mSetting;
    private final boolean mAllowRotationResolver;

    @Inject
    public RotationLockTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            RotationLockController rotationLockController,
            SensorPrivacyManager privacyManager,
            BatteryController batteryController,
            SecureSettings secureSettings
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = rotationLockController;
        mController.observe(this, mCallback);
        mPrivacyManager = privacyManager;
        mBatteryController = batteryController;
        int currentUser = host.getUserContext().getUserId();
        mSetting = new UserSettingObserver(
                secureSettings,
                mHandler,
                Secure.CAMERA_AUTOROTATE,
                currentUser
        ) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                // mHandler is the background handler so calling this is OK
                handleRefreshState(null);
            }
        };
        mBatteryController.observe(getLifecycle(), this);
        mAllowRotationResolver = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_allowRotationResolver);
    }

    @Override
    protected void handleInitialize() {
        mPrivacyManager.addSensorPrivacyListener(CAMERA, mSensorPrivacyChangedListener);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        refreshState();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_AUTO_ROTATE_SETTINGS);
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        final boolean newState = !mState.value;
        mController.setRotationLocked(!newState, /* caller= */ "RotationLockTile#handleClick");
        refreshState(newState);
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean rotationLocked = mController.isRotationLocked();

        final boolean powerSave = mBatteryController.isPowerSave();
        final boolean cameraLocked = mPrivacyManager.isSensorPrivacyEnabled(CAMERA);
        final boolean cameraRotation = mAllowRotationResolver &&
                !powerSave && !cameraLocked && hasSufficientPermission(mContext)
                        && mController.isCameraRotationEnabled();
        state.value = !rotationLocked;
        state.label = mContext.getString(R.string.quick_settings_rotation_unlocked_label);
        state.icon = ResourceIcon.get(R.drawable.qs_auto_rotate_icon_off);
        state.contentDescription = getAccessibilityString(rotationLocked);
        if (!rotationLocked) {
            state.secondaryLabel = cameraRotation ? mContext.getResources().getString(
                    R.string.rotation_lock_camera_rotation_on)
                    : EMPTY_SECONDARY_STRING;
            state.icon = ResourceIcon.get(R.drawable.qs_auto_rotate_icon_on);
        } else {
            state.secondaryLabel = EMPTY_SECONDARY_STRING;
        }
        state.stateDescription = state.secondaryLabel;

        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mSetting.setListening(false);
        mPrivacyManager.removeSensorPrivacyListener(CAMERA, mSensorPrivacyChangedListener);
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mSetting.setUserId(newUserId);
        handleRefreshState(null);
    }

    public static boolean isCurrentOrientationLockPortrait(RotationLockController controller,
            Resources resources) {
        int lockOrientation = controller.getRotationLockOrientation();
        if (lockOrientation == Configuration.ORIENTATION_UNDEFINED) {
            // Freely rotating device; use current rotation
            return resources.getConfiguration().orientation
                    != Configuration.ORIENTATION_LANDSCAPE;
        } else {
            return lockOrientation != Configuration.ORIENTATION_LANDSCAPE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_ROTATIONLOCK;
    }

    /**
     * Get the correct accessibility string based on the state
     *
     * @param locked Whether or not rotation is locked.
     */
    private String getAccessibilityString(boolean locked) {
        return mContext.getString(R.string.accessibility_quick_settings_rotation);
    }

    private final RotationLockControllerCallback mCallback = new RotationLockControllerCallback() {
        @Override
        public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
            refreshState(rotationLocked);
        }
    };

    private final SensorPrivacyManager.OnSensorPrivacyChangedListener
            mSensorPrivacyChangedListener =
            (sensor, enabled) -> refreshState();
}
