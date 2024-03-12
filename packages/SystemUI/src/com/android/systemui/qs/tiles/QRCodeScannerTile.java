/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.res.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

/** Quick settings tile: QR Code Scanner **/
public class QRCodeScannerTile extends QSTileImpl<QSTile.State> {

    public static final String TILE_SPEC = "qr_code_scanner";

    private static final String TAG = "QRCodeScanner";

    private final CharSequence mLabel = mContext.getString(R.string.qr_code_scanner_title);
    private final QRCodeScannerController mQRCodeScannerController;

    private final QRCodeScannerController.Callback mCallback =
            new QRCodeScannerController.Callback() {
                public void onQRCodeScannerActivityChanged() {
                    refreshState();
                }
            };

    @Inject
    public QRCodeScannerTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            QRCodeScannerController qrCodeScannerController) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mQRCodeScannerController = qrCodeScannerController;
        mQRCodeScannerController.observe(getLifecycle(), mCallback);
    }

    @Override
    protected void handleInitialize() {
        mQRCodeScannerController.registerQRCodeScannerChangeObservers(
                QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE);
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mQRCodeScannerController.unregisterQRCodeScannerChangeObservers(
                QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE);
    }

    @Override
    public State newTileState() {
        State state = new State();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        Intent intent = mQRCodeScannerController.getIntent();
        if (intent == null) {
            // This should never happen as the fact that we are handling clicks means that the
            // scanner is available. This is just a safety check.
            Log.e(TAG, "Expected a non-null intent");
            return;
        }

        ActivityLaunchAnimator.Controller animationController =
                view == null ? null : ActivityLaunchAnimator.Controller.fromView(view,
                        InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE);
        mActivityStarter.startActivity(intent, true /* dismissShade */,
                animationController, true /* showOverLockscreenWhenLocked */);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.label = mContext.getString(R.string.qr_code_scanner_title);
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(R.drawable.ic_qr_code_scanner);
        state.state = mQRCodeScannerController.isAbleToLaunchScannerActivity() ? Tile.STATE_INACTIVE
                : Tile.STATE_UNAVAILABLE;
        // The assumption is that if the OEM has the QR code scanner module enabled then the scanner
        // would go to "Unavailable" state only when GMS core is updating.
        state.secondaryLabel = state.state == Tile.STATE_UNAVAILABLE
                ? mContext.getString(R.string.qr_code_scanner_updating_secondary_label) : null;
    }

    @Override
    public int getMetricsCategory() {
        // New logging doesn't use this, keeping the function for legacy code.
        return 0;
    }

    @Override
    public boolean isAvailable() {
        return mQRCodeScannerController.isCameraAvailable();
    }

    @Nullable
    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mLabel;
    }
}
