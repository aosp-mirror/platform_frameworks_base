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
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.ISidefpsController;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Shows and hides the side fingerprint sensor (side-fps) overlay and handles side fps touch events.
 */
@SysUISingleton
public class SidefpsController {
    private static final String TAG = "SidefpsController";
    // TODO (b/188690214): define and retrieve values from framework via SensorProps
    static final int DISPLAY_HEIGHT = 1804;
    static final int DISPLAY_WIDTH = 2208;
    static final int SFPS_INDICATOR_HEIGHT = 225;
    static final int SFPS_Y = 500;
    static final int SFPS_INDICATOR_WIDTH = 50;

    @Nullable private SidefpsView mView;
    private final FingerprintManager mFingerprintManager;
    private final Context mContext;
    @NonNull private final LayoutInflater mInflater;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    private boolean mIsVisible = false;

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
            @Main DelayableExecutor fgExecutor) {
        mContext = context;
        mInflater = inflater;
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;

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

        mFingerprintManager.setSidefpsController(mSidefpsControllerImpl);
    }

    void show() {
        mView = (SidefpsView) mInflater.inflate(R.layout.sidefps_view, null, false);
        mView.setSensorProperties(mSensorProps);
        mWindowManager.addView(mView, computeLayoutParams());

    }

    void hide() {
        if (mView != null) {
            Log.v(TAG, "hideUdfpsOverlay | removing window");
            mWindowManager.removeView(mView);
            mView.setOnTouchListener(null);
            mView.setOnHoverListener(null);
            mView = null;
        } else {
            Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
        }
    }

    void onConfigurationChanged() {
        // If overlay was hidden, it should remain hidden
        if (!mIsVisible) {
            return;
        }
        // If the overlay needs to be shown, destroy the current overlay, and re-create and show
        // the overlay with the updated LayoutParams.
        hide();
        show();
    }

    @Nullable
    private FingerprintSensorPropertiesInternal findFirstSidefps() {
        for (FingerprintSensorPropertiesInternal props :
                mFingerprintManager.getSensorPropertiesInternal()) {
            if (props.isAnySidefpsType()) {
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

        // Default dimensions assume portrait mode.
        mCoreLayoutParams.x = DISPLAY_WIDTH - SFPS_INDICATOR_WIDTH;
        mCoreLayoutParams.y = SFPS_Y;
        mCoreLayoutParams.height = SFPS_INDICATOR_HEIGHT;
        mCoreLayoutParams.width = SFPS_INDICATOR_WIDTH;

        /*
        TODO (b/188692405): recalculate coordinates for non-portrait configurations and folding
         states
        */
        return mCoreLayoutParams;
    }
}
