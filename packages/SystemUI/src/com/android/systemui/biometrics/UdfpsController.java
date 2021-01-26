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

package com.android.systemui.biometrics;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.Spline;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.BrightnessSynchronizer;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.SystemSettings;

import java.io.FileWriter;
import java.io.IOException;

import javax.inject.Inject;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and coordinates triggering of the high-brightness mode (HBM).
 *
 * Note that the current architecture is designed so that a single {@link UdfpsController}
 * controls/manages all UDFPS sensors. In other words, a single controller is registered with
 * {@link com.android.server.biometrics.sensors.fingerprint.FingerprintService}, and interfaces such
 * as {@link FingerprintManager#onPointerDown(int, int, int, float, float)} or
 * {@link IUdfpsOverlayController#showUdfpsOverlay(int)}should all have
 * {@code sensorId} parameters.
 */
@SuppressWarnings("deprecation")
class UdfpsController implements DozeReceiver {
    private static final String TAG = "UdfpsController";
    // Gamma approximation for the sRGB color space.
    private static final float DISPLAY_GAMMA = 2.2f;
    private static final long AOD_INTERRUPT_TIMEOUT_MILLIS = 1000;

    private final Context mContext;
    private final FingerprintManager mFingerprintManager;
    private final WindowManager mWindowManager;
    private final SystemSettings mSystemSettings;
    private final DelayableExecutor mFgExecutor;
    private final StatusBarStateController mStatusBarStateController;
    // Currently the UdfpsController supports a single UDFPS sensor. If devices have multiple
    // sensors, this, in addition to a lot of the code here, will be updated.
    @VisibleForTesting final FingerprintSensorPropertiesInternal mSensorProps;
    private final WindowManager.LayoutParams mCoreLayoutParams;
    private final UdfpsView mView;
    // Debugfs path to control the high-brightness mode.
    private final String mHbmPath;
    private final String mHbmEnableCommand;
    private final String mHbmDisableCommand;
    private final boolean mHbmSupported;
    // Brightness in nits in the high-brightness mode.
    private final float mMaxNits;
    // A spline mapping from the device's backlight value, normalized to the range [0, 1.0], to a
    // brightness in nits.
    private final Spline mBacklightToNitsSpline;
    // A spline mapping from a value in nits to a backlight value of a hypothetical panel whose
    // maximum backlight value corresponds to our panel's high-brightness mode.
    // The output is normalized to the range [0, 1.0].
    private Spline mNitsToHbmBacklightSpline;
    // Default non-HBM backlight value normalized to the range [0, 1.0]. Used as a fallback when the
    // actual brightness value cannot be retrieved.
    private final float mDefaultBrightness;
    // Indicates whether the overlay is currently showing. Even if it has been requested, it might
    // not be showing.
    private boolean mIsOverlayShowing;
    // Indicates whether the overlay has been requested.
    private boolean mIsOverlayRequested;
    // Reason the overlay has been requested. See IUdfpsOverlayController for definitions.
    private int mRequestReason;

    // The fingerprint AOD trigger doesn't provide an ACTION_UP/ACTION_CANCEL event to tell us when
    // to turn off high brightness mode. To get around this limitation, the state of the AOD
    // interrupt is being tracked and a timeout is used as a last resort to turn off high brightness
    // mode.
    private boolean mIsAodInterruptActive;
    @Nullable private Runnable mCancelAodTimeoutAction;

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay(int sensorId, int reason) {
            UdfpsController.this.showOverlay(reason);
        }

        @Override
        public void hideUdfpsOverlay(int sensorId) {
            UdfpsController.this.hideOverlay();
        }

        @Override
        public void onEnrollmentProgress(int sensorId, int remaining) {
            mView.onEnrollmentProgress(remaining);
        }

        @Override
        public void onEnrollmentHelp(int sensorId) {
            mView.onEnrollmentHelp();
        }

        @Override
        public void setDebugMessage(int sensorId, String message) {
            mView.setDebugMessage(message);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private final UdfpsView.OnTouchListener mOnTouchListener = (v, event) -> {
        UdfpsView view = (UdfpsView) v;
        final boolean isFingerDown = view.isShowScrimAndDot();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                final boolean isValidTouch = view.isValidTouch(event.getX(), event.getY(),
                        event.getPressure());
                if (!isFingerDown && isValidTouch) {
                    onFingerDown((int) event.getX(), (int) event.getY(), event.getTouchMinor(),
                            event.getTouchMajor());
                } else if (isFingerDown && !isValidTouch) {
                    onFingerUp();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isFingerDown) {
                    onFingerUp();
                }
                return true;

            default:
                return false;
        }
    };

    @Inject
    UdfpsController(@NonNull Context context,
            @Main Resources resources,
            LayoutInflater inflater,
            @Nullable FingerprintManager fingerprintManager,
            DisplayManager displayManager,
            WindowManager windowManager,
            SystemSettings systemSettings,
            @NonNull StatusBarStateController statusBarStateController,
            @Main DelayableExecutor fgExecutor) {
        mContext = context;
        // The fingerprint manager is queried for UDFPS before this class is constructed, so the
        // fingerprint manager should never be null.
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mSystemSettings = systemSettings;
        mFgExecutor = fgExecutor;
        mStatusBarStateController = statusBarStateController;

        mSensorProps = findFirstUdfps();
        // At least one UDFPS sensor exists
        checkArgument(mSensorProps != null);

        mCoreLayoutParams = new WindowManager.LayoutParams(
                // TODO(b/152419866): Use the UDFPS window type when it becomes available.
                WindowManager.LayoutParams.TYPE_BOOT_PROGRESS,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        mCoreLayoutParams.setTitle(TAG);
        mCoreLayoutParams.setFitInsetsTypes(0);
        mCoreLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mCoreLayoutParams.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        mView = (UdfpsView) inflater.inflate(R.layout.udfps_view, null, false);
        mView.setSensorProperties(mSensorProps);

        mHbmPath = resources.getString(R.string.udfps_hbm_sysfs_path);
        mHbmEnableCommand = resources.getString(R.string.udfps_hbm_enable_command);
        mHbmDisableCommand = resources.getString(R.string.udfps_hbm_disable_command);

        mHbmSupported = !TextUtils.isEmpty(mHbmPath);
        mView.setHbmSupported(mHbmSupported);

        // This range only consists of the minimum and maximum values, which only cover
        // non-high-brightness mode.
        float[] nitsRange = toFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessNits));
        if (nitsRange.length < 2) {
            throw new IllegalArgumentException(
                    String.format("nitsRange.length: %d. Must be >= 2", nitsRange.length));
        }

        // The last value of this range corresponds to the high-brightness mode.
        float[] nitsAutoBrightnessValues = toFloatArray(resources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits));
        if (nitsAutoBrightnessValues.length < 2) {
            throw new IllegalArgumentException(
                    String.format("nitsAutoBrightnessValues.length: %d. Must be >= 2",
                            nitsAutoBrightnessValues.length));
        }

        mMaxNits = nitsAutoBrightnessValues[nitsAutoBrightnessValues.length - 1];
        float[] hbmNitsRange = nitsRange.clone();
        hbmNitsRange[hbmNitsRange.length - 1] = mMaxNits;

        // This range only consists of the minimum and maximum backlight values, which only apply
        // in non-high-brightness mode.
        float[] normalizedBacklightRange = normalizeBacklightRange(
                resources.getIntArray(
                        com.android.internal.R.array.config_screenBrightnessBacklight));
        if (normalizedBacklightRange.length < 2) {
            throw new IllegalArgumentException(
                    String.format("normalizedBacklightRange.length: %d. Must be >= 2",
                            normalizedBacklightRange.length));
        }
        if (normalizedBacklightRange.length != nitsRange.length) {
            throw new IllegalArgumentException(
                    "normalizedBacklightRange.length != nitsRange.length");
        }

        mBacklightToNitsSpline = Spline.createSpline(normalizedBacklightRange, nitsRange);
        mNitsToHbmBacklightSpline = Spline.createSpline(hbmNitsRange, normalizedBacklightRange);
        mDefaultBrightness = obtainDefaultBrightness(mContext);

        // TODO(b/160025856): move to the "dump" method.
        Log.v(TAG, String.format("ctor | mNitsRange: [%f, %f]", nitsRange[0],
                nitsRange[nitsRange.length - 1]));
        Log.v(TAG, String.format("ctor | mHbmNitsRange: [%f, %f]", hbmNitsRange[0],
                hbmNitsRange[hbmNitsRange.length - 1]));
        Log.v(TAG, String.format("ctor | mNormalizedBacklightRange: [%f, %f]",
                normalizedBacklightRange[0],
                normalizedBacklightRange[normalizedBacklightRange.length - 1]));

        mFingerprintManager.setUdfpsOverlayController(new UdfpsOverlayController());
        mIsOverlayShowing = false;
    }

    @Nullable
    private FingerprintSensorPropertiesInternal findFirstUdfps() {
        for (FingerprintSensorPropertiesInternal props :
                mFingerprintManager.getSensorPropertiesInternal()) {
            if (props.isAnyUdfpsType()) {
                return props;
            }
        }
        return null;
    }

    @Override
    public void dozeTimeTick() {
        mView.dozeTimeTick();
    }

    /**
     * @return where the UDFPS exists on the screen in pixels.
     */
    public RectF getSensorLocation() {
        return mView.getSensorRect();
    }

    private void showOverlay(int reason) {
        if (mIsOverlayRequested) {
            return;
        }
        mIsOverlayRequested = true;
        mRequestReason = reason;
        updateOverlay();
    }

    private void hideOverlay() {
        if (!mIsOverlayRequested) {
            return;
        }
        mIsOverlayRequested = false;
        mRequestReason = IUdfpsOverlayController.REASON_UNKNOWN;
        updateOverlay();
    }

    private void updateOverlay() {
        if (mIsOverlayRequested) {
            showUdfpsOverlay(mRequestReason);
        } else {
            hideUdfpsOverlay();
        }
    }

    private WindowManager.LayoutParams computeLayoutParams() {
        Point p = new Point();
        // Gets the size based on the current rotation of the display.
        mContext.getDisplay().getRealSize(p);
        mCoreLayoutParams.width = p.x;
        mCoreLayoutParams.x = p.x;
        mCoreLayoutParams.height = p.y;
        mCoreLayoutParams.y = p.y;
        return mCoreLayoutParams;
    }

    void onConfigurationChanged() {
        // When the configuration changes it's almost always necessary to destroy and re-create
        // the overlay's window to pass it the new LayoutParams.
        // Hiding the overlay will destroy its window. It's safe to hide the overlay regardless
        // of whether it is already hidden.
        hideUdfpsOverlay();
        // If the overlay needs to be shown, this will re-create and show the overlay with the
        // updated LayoutParams. Otherwise, the overlay will remain hidden.
        updateOverlay();
    }

    private void showUdfpsOverlay(int reason) {
        mFgExecutor.execute(() -> {
            if (!mIsOverlayShowing) {
                try {
                    Log.v(TAG, "showUdfpsOverlay | adding window");
                    mView.setUdfpsAnimation(getUdfpsAnimationForReason(reason));
                    mWindowManager.addView(mView, computeLayoutParams());
                    mView.setOnTouchListener(mOnTouchListener);
                    mIsOverlayShowing = true;
                } catch (RuntimeException e) {
                    Log.e(TAG, "showUdfpsOverlay | failed to add window", e);
                }
            } else {
                Log.v(TAG, "showUdfpsOverlay | the overlay is already showing");
            }
        });
    }

    @Nullable
    private UdfpsAnimation getUdfpsAnimationForReason(int reason) {
        Log.d(TAG, "getUdfpsAnimationForReason: " + reason);
        switch (reason) {
            case IUdfpsOverlayController.REASON_ENROLL:
                return new UdfpsAnimationEnroll(mContext);
            case IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD:
                return new UdfpsAnimationKeyguard(mView, mContext, mStatusBarStateController);
            case IUdfpsOverlayController.REASON_AUTH_FPM_OTHER:
                return new UdfpsAnimationFpmOther(mContext);
            default:
                Log.d(TAG, "Animation for reason " + reason + " not supported yet");
                return null;
        }
    }

    private void hideUdfpsOverlay() {
        mFgExecutor.execute(() -> {
            if (mIsOverlayShowing) {
                Log.v(TAG, "hideUdfpsOverlay | removing window");
                mView.setUdfpsAnimation(null);
                mView.setOnTouchListener(null);
                // Reset the controller back to its starting state.
                onFingerUp();
                mWindowManager.removeView(mView);
                mIsOverlayShowing = false;
            } else {
                Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
            }
        });
    }

    // Returns a value in the range of [0, 255].
    private int computeScrimOpacity() {
        // Backlight setting can be NaN, -1.0f, and [0.0f, 1.0f].
        float backlightSetting = mSystemSettings.getFloatForUser(
                Settings.System.SCREEN_BRIGHTNESS_FLOAT, mDefaultBrightness,
                UserHandle.USER_CURRENT);

        // Constrain the backlight setting to [0.0f, 1.0f].
        float backlightValue = MathUtils.constrain(backlightSetting,
                PowerManager.BRIGHTNESS_MIN,
                PowerManager.BRIGHTNESS_MAX);

        // Interpolate the backlight value to nits.
        float nits = mBacklightToNitsSpline.interpolate(backlightValue);

        // Interpolate nits to a backlight value for a panel with enabled HBM.
        float interpolatedHbmBacklightValue = mNitsToHbmBacklightSpline.interpolate(nits);

        float gammaCorrectedHbmBacklightValue = (float) Math.pow(interpolatedHbmBacklightValue,
                1.0f / DISPLAY_GAMMA);
        float scrimOpacity = PowerManager.BRIGHTNESS_MAX - gammaCorrectedHbmBacklightValue;

        // Interpolate the opacity value from [0.0f, 1.0f] to [0, 255].
        return BrightnessSynchronizer.brightnessFloatToInt(scrimOpacity);
    }

    /**
     * Request fingerprint scan.
     *
     * This is intented to be called in response to a sensor that triggers an AOD interrupt for the
     * fingerprint sensor.
     */
    void onAodInterrupt(int screenX, int screenY, float major, float minor) {
        if (mIsAodInterruptActive) {
            return;
        }
        mIsAodInterruptActive = true;
        // Since the sensor that triggers the AOD interrupt doesn't provide ACTION_UP/ACTION_CANCEL,
        // we need to be careful about not letting the screen accidentally remain in high brightness
        // mode. As a mitigation, queue a call to cancel the fingerprint scan.
        mCancelAodTimeoutAction = mFgExecutor.executeDelayed(this::onCancelAodInterrupt,
                AOD_INTERRUPT_TIMEOUT_MILLIS);
        // using a hard-coded value for major and minor until it is available from the sensor
        onFingerDown(screenX, screenY, minor, major);
    }

    /**
     * Cancel fingerprint scan.
     *
     * This is intented to be called after the fingerprint scan triggered by the AOD interrupt
     * either succeeds or fails.
     */
    void onCancelAodInterrupt() {
        if (!mIsAodInterruptActive) {
            return;
        }
        if (mCancelAodTimeoutAction != null) {
            mCancelAodTimeoutAction.run();
            mCancelAodTimeoutAction = null;
        }
        mIsAodInterruptActive = false;
        onFingerUp();
    }

    private void onFingerDown(int x, int y, float minor, float major) {
        if (mHbmSupported) {
            try {
                FileWriter fw = new FileWriter(mHbmPath);
                fw.write(mHbmEnableCommand);
                fw.close();
            } catch (IOException e) {
                mView.hideScrimAndDot();
                Log.e(TAG, "onFingerDown | failed to enable HBM: " + e.getMessage());
            }
        }
        mView.setScrimAlpha(computeScrimOpacity());
        mView.setRunAfterShowingScrimAndDot(() -> {
            mFingerprintManager.onPointerDown(mSensorProps.sensorId, x, y, minor, major);
        });
        mView.showScrimAndDot();
    }

    private void onFingerUp() {
        mFingerprintManager.onPointerUp(mSensorProps.sensorId);
        // Hiding the scrim before disabling HBM results in less noticeable flicker.
        mView.hideScrimAndDot();
        if (mHbmSupported) {
            try {
                FileWriter fw = new FileWriter(mHbmPath);
                fw.write(mHbmDisableCommand);
                fw.close();
            } catch (IOException e) {
                mView.showScrimAndDot();
                Log.e(TAG, "onFingerUp | failed to disable HBM: " + e.getMessage());
            }
        }
    }

    private static float obtainDefaultBrightness(Context context) {
        return MathUtils.constrain(context.getDisplay().getBrightnessDefault(),
                PowerManager.BRIGHTNESS_MIN, PowerManager.BRIGHTNESS_MAX);
    }

    private static float[] toFloatArray(TypedArray array) {
        final int n = array.length();
        float[] vals = new float[n];
        for (int i = 0; i < n; i++) {
            vals[i] = array.getFloat(i, PowerManager.BRIGHTNESS_OFF_FLOAT);
        }
        array.recycle();
        return vals;
    }

    private static float[] normalizeBacklightRange(int[] backlight) {
        final int n = backlight.length;
        float[] normalizedBacklight = new float[n];
        for (int i = 0; i < n; i++) {
            normalizedBacklight[i] = BrightnessSynchronizer.brightnessIntToFloat(backlight[i]);
        }
        return normalizedBacklight;
    }
}
