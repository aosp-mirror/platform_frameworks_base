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

package com.android.systemui.biometrics;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.ISidefpsController;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

import kotlin.Unit;

/**
 * Shows and hides the side fingerprint sensor (side-fps) overlay and handles side fps touch events.
 */
@SysUISingleton
public class SidefpsController {
    private static final String TAG = "SidefpsController";
    @NonNull private final Context mContext;
    @NonNull private final LayoutInflater mInflater;
    private final FingerprintManager mFingerprintManager;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    @VisibleForTesting @NonNull final BiometricOrientationEventListener mOrientationListener;

    // TODO: update mDisplayHeight and mDisplayWidth for multi-display devices
    private final int mDisplayHeight;
    private final int mDisplayWidth;

    private boolean mIsVisible = false;
    @Nullable private SidefpsView mView;

    static final int SFPS_AFFORDANCE_WIDTH = 50; // in default portrait mode

    @NonNull
    private final ISidefpsController mSidefpsControllerImpl = new ISidefpsController.Stub() {
        @Override
        public void show() {
            mFgExecutor.execute(() -> {
                SidefpsController.this.show();
                mIsVisible = true;
            });
        }

        @Override
        public void hide() {
            mFgExecutor.execute(() -> {
                SidefpsController.this.hide();
                mIsVisible = false;
            });
        }
    };

    @VisibleForTesting
    final FingerprintSensorPropertiesInternal mSensorProps;
    private final WindowManager.LayoutParams mCoreLayoutParams;

    @Inject
    public SidefpsController(@NonNull Context context,
            @NonNull LayoutInflater inflater,
            @Nullable FingerprintManager fingerprintManager,
            @NonNull WindowManager windowManager,
            @Main DelayableExecutor fgExecutor,
            @NonNull DisplayManager displayManager,
            @Main Handler handler) {
        mContext = context;
        mInflater = inflater;
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;
        mOrientationListener = new BiometricOrientationEventListener(
                context,
                () -> {
                    onOrientationChanged();
                    return Unit.INSTANCE;
                },
                displayManager,
                handler);

        mSensorProps = findFirstSidefps();
        checkArgument(mSensorProps != null);

        mCoreLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                getCoreLayoutParamFlags(),
                PixelFormat.TRANSLUCENT);
        mCoreLayoutParams.setTitle(TAG);
        // Overrides default, avoiding status bars during layout
        mCoreLayoutParams.setFitInsetsTypes(0);
        mCoreLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        mCoreLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mCoreLayoutParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        mDisplayHeight = displayMetrics.heightPixels;
        mDisplayWidth = displayMetrics.widthPixels;

        mFingerprintManager.setSidefpsController(mSidefpsControllerImpl);
    }

    private void show() {
        mView = (SidefpsView) mInflater.inflate(R.layout.sidefps_view, null, false);
        mView.setSensorProperties(mSensorProps);
        mWindowManager.addView(mView, computeLayoutParams());

        mOrientationListener.enable();
    }

    private void hide() {
        if (mView != null) {
            mWindowManager.removeView(mView);
            mView.setOnTouchListener(null);
            mView.setOnHoverListener(null);
            mView = null;
        } else {
            Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
        }

        mOrientationListener.disable();
    }

    private void onOrientationChanged() {
        // If mView is null or if view is hidden, then return.
        if (mView == null || !mIsVisible) {
            return;
        }

        // If the overlay needs to be displayed with a new configuration, destroy the current
        // overlay, and re-create and show the overlay with the updated LayoutParams.
        hide();
        show();
    }

    @Nullable
    private FingerprintSensorPropertiesInternal findFirstSidefps() {
        for (FingerprintSensorPropertiesInternal props :
                mFingerprintManager.getSensorPropertiesInternal()) {
            if (props.isAnySidefpsType()) {
                // TODO(b/188690214): L155-L173 can be removed once sensorLocationX,
                //  sensorLocationY, and sensorRadius are defined in sensorProps by the HAL
                int sensorLocationX = 25;
                int sensorLocationY = 610;
                int sensorRadius = 112;

                FingerprintSensorPropertiesInternal tempProps =
                        new FingerprintSensorPropertiesInternal(
                                props.sensorId,
                                props.sensorStrength,
                                props.maxEnrollmentsPerUser,
                                props.componentInfo,
                                props.sensorType,
                                props.resetLockoutRequiresHardwareAuthToken,
                                sensorLocationX,
                                sensorLocationY,
                                sensorRadius
                        );
                props = tempProps;
                return props;
            }
        }
        return null;
    }

    private int getCoreLayoutParamFlags() {
        return WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    }

    /**
     * Computes layout params depending on orientation & folding configuration of device
     */
    private WindowManager.LayoutParams computeLayoutParams() {
        mCoreLayoutParams.flags = getCoreLayoutParamFlags();
        // Y value of top of affordance in portrait mode, X value of left of affordance in landscape
        int sfpsLocationY = mSensorProps.sensorLocationY - mSensorProps.sensorRadius;
        int sfpsAffordanceHeight = mSensorProps.sensorRadius * 2;

        // Calculate coordinates of drawable area for the fps affordance, accounting for orientation
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                mCoreLayoutParams.x = sfpsLocationY;
                mCoreLayoutParams.y = 0;
                mCoreLayoutParams.height = SFPS_AFFORDANCE_WIDTH;
                mCoreLayoutParams.width = sfpsAffordanceHeight;
                break;
            case Surface.ROTATION_270:
                mCoreLayoutParams.x = mDisplayHeight - sfpsLocationY - sfpsAffordanceHeight;
                mCoreLayoutParams.y = mDisplayWidth - SFPS_AFFORDANCE_WIDTH;
                mCoreLayoutParams.height = SFPS_AFFORDANCE_WIDTH;
                mCoreLayoutParams.width = sfpsAffordanceHeight;
                break;
            default: // Portrait
                mCoreLayoutParams.x = mDisplayWidth - SFPS_AFFORDANCE_WIDTH;
                mCoreLayoutParams.y = sfpsLocationY;
                mCoreLayoutParams.height = sfpsAffordanceHeight;
                mCoreLayoutParams.width = SFPS_AFFORDANCE_WIDTH;
        }
        return mCoreLayoutParams;
    }
}
