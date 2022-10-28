/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.display.color;

import static android.hardware.display.ColorDisplayManager.AUTO_MODE_CUSTOM_TIME;
import static android.hardware.display.ColorDisplayManager.AUTO_MODE_DISABLED;
import static android.hardware.display.ColorDisplayManager.AUTO_MODE_TWILIGHT;
import static android.hardware.display.ColorDisplayManager.COLOR_MODE_AUTOMATIC;
import static android.hardware.display.ColorDisplayManager.COLOR_MODE_BOOSTED;
import static android.hardware.display.ColorDisplayManager.COLOR_MODE_NATURAL;
import static android.hardware.display.ColorDisplayManager.COLOR_MODE_SATURATED;
import static android.hardware.display.ColorDisplayManager.VENDOR_COLOR_MODE_RANGE_MAX;
import static android.hardware.display.ColorDisplayManager.VENDOR_COLOR_MODE_RANGE_MIN;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.UserIdInt;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.ColorDisplayManager.AutoMode;
import android.hardware.display.ColorDisplayManager.ColorMode;
import android.hardware.display.IColorDisplayManager;
import android.hardware.display.Time;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.provider.Settings.System;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AnimationUtils;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Controls the display's color transforms.
 */
public final class ColorDisplayService extends SystemService {

    static final String TAG = "ColorDisplayService";

    /**
     * The identity matrix, used if one of the given matrices is {@code null}.
     */
    static final float[] MATRIX_IDENTITY = new float[16];

    static {
        Matrix.setIdentityM(MATRIX_IDENTITY, 0);
    }

    /**
     * The transition time, in milliseconds, for Night Display to turn on/off.
     */
    private static final long TRANSITION_DURATION = 3000L;

    private static final int MSG_USER_CHANGED = 0;
    private static final int MSG_SET_UP = 1;
    private static final int MSG_APPLY_NIGHT_DISPLAY_IMMEDIATE = 2;
    private static final int MSG_APPLY_NIGHT_DISPLAY_ANIMATED = 3;
    private static final int MSG_APPLY_GLOBAL_SATURATION = 4;
    private static final int MSG_APPLY_DISPLAY_WHITE_BALANCE = 5;
    private static final int MSG_APPLY_REDUCE_BRIGHT_COLORS = 6;

    /**
     * Return value if a setting has not been set.
     */
    private static final int NOT_SET = -1;

    /**
     * Evaluator used to animate color matrix transitions.
     */
    private static final ColorMatrixEvaluator COLOR_MATRIX_EVALUATOR = new ColorMatrixEvaluator();
    /**
     * Matrix and offset used for converting color to grayscale.
     */
    private static final float[] MATRIX_GRAYSCALE = new float[]{
            .2126f, .2126f, .2126f, 0f,
            .7152f, .7152f, .7152f, 0f,
            .0722f, .0722f, .0722f, 0f,
            0f, 0f, 0f, 1f
    };

    /**
     * Matrix and offset used for luminance inversion. Represents a transform from RGB to YIQ color
     * space, rotation around the Y axis by 180 degrees, transform back to RGB color space, and
     * subtraction from 1. The last row represents a non-multiplied addition, see surfaceflinger's
     * ProgramCache for full implementation details.
     */
    private static final float[] MATRIX_INVERT_COLOR = new float[]{
            0.402f, -0.598f, -0.599f, 0f,
            -1.174f, -0.174f, -1.175f, 0f,
            -0.228f, -0.228f, 0.772f, 0f,
            1f, 1f, 1f, 1f
    };

    @VisibleForTesting
    final DisplayWhiteBalanceTintController mDisplayWhiteBalanceTintController =
            new DisplayWhiteBalanceTintController();
    private final NightDisplayTintController mNightDisplayTintController =
            new NightDisplayTintController();
    private final TintController mGlobalSaturationTintController =
            new GlobalSaturationTintController();
    private final ReduceBrightColorsTintController mReduceBrightColorsTintController =
            new ReduceBrightColorsTintController();

    @VisibleForTesting
    final Handler mHandler;

    private final AppSaturationController mAppSaturationController = new AppSaturationController();

    private int mCurrentUser = UserHandle.USER_NULL;
    private ContentObserver mUserSetupObserver;
    private boolean mBootCompleted;

    private ContentObserver mContentObserver;

    private DisplayWhiteBalanceListener mDisplayWhiteBalanceListener;
    private ReduceBrightColorsListener mReduceBrightColorsListener;

    private NightDisplayAutoMode mNightDisplayAutoMode;

    /**
     * Map of color modes -> display composition colorspace
     */
    private SparseIntArray mColorModeCompositionColorSpaces = null;

    public ColorDisplayService(Context context) {
        super(context);
        mHandler = new TintHandler(DisplayThread.get().getLooper());
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COLOR_DISPLAY_SERVICE, new BinderService());
        publishLocalService(ColorDisplayServiceInternal.class, new ColorDisplayServiceInternal());
        publishLocalService(DisplayTransformManager.class, new DisplayTransformManager());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase >= PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;

            // Register listeners now that boot is complete.
            if (mCurrentUser != UserHandle.USER_NULL && mUserSetupObserver == null) {
                mHandler.sendEmptyMessage(MSG_SET_UP);
            }
        }
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        if (mCurrentUser == UserHandle.USER_NULL) {
            final Message message = mHandler.obtainMessage(MSG_USER_CHANGED);
            message.arg1 = user.getUserIdentifier();
            mHandler.sendMessage(message);
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        final Message message = mHandler.obtainMessage(MSG_USER_CHANGED);
        message.arg1 = to.getUserIdentifier();
        mHandler.sendMessage(message);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        if (mCurrentUser == user.getUserIdentifier()) {
            final Message message = mHandler.obtainMessage(MSG_USER_CHANGED);
            message.arg1 = UserHandle.USER_NULL;
            mHandler.sendMessage(message);
        }
    }

    @VisibleForTesting void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (mUserSetupObserver != null) {
                cr.unregisterContentObserver(mUserSetupObserver);
                mUserSetupObserver = null;
            } else if (mBootCompleted) {
                tearDown();
            }
        }

        mCurrentUser = userHandle;

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (!isUserSetupCompleted(cr, mCurrentUser)) {
                mUserSetupObserver = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (isUserSetupCompleted(cr, mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            mUserSetupObserver = null;

                            if (mBootCompleted) {
                                setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Secure.getUriFor(Secure.USER_SETUP_COMPLETE),
                        false /* notifyForDescendants */, mUserSetupObserver, mCurrentUser);
            } else if (mBootCompleted) {
                setUp();
            }
        }
    }

    private static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Secure.getIntForUser(cr, Secure.USER_SETUP_COMPLETE, 0, userHandle) == 1;
    }

    private void setUpDisplayCompositionColorSpaces(Resources res) {
        mColorModeCompositionColorSpaces = null;

        final int[] colorModes = res.getIntArray(R.array.config_displayCompositionColorModes);
        if (colorModes == null) {
            return;
        }

        final int[] compSpaces = res.getIntArray(R.array.config_displayCompositionColorSpaces);
        if (compSpaces == null) {
            return;
        }

        if (colorModes.length != compSpaces.length) {
            Slog.e(TAG, "Number of composition color spaces doesn't match specified color modes");
            return;
        }

        mColorModeCompositionColorSpaces = new SparseIntArray(colorModes.length);
        for (int i = 0; i < colorModes.length; i++) {
            mColorModeCompositionColorSpaces.put(colorModes[i], compSpaces[i]);
        }
    }

    private void setUp() {
        Slog.d(TAG, "setUp: currentUser=" + mCurrentUser);

        // Listen for external changes to any of the settings.
        if (mContentObserver == null) {
            mContentObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);

                    final String setting = uri == null ? null : uri.getLastPathSegment();
                    if (setting != null) {
                        switch (setting) {
                            case Secure.NIGHT_DISPLAY_ACTIVATED:
                                final boolean activated = mNightDisplayTintController
                                        .isActivatedSetting();
                                if (mNightDisplayTintController.isActivatedStateNotSet()
                                        || mNightDisplayTintController.isActivated() != activated) {
                                    mNightDisplayTintController.setActivated(activated);
                                }
                                break;
                            case Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE:
                                final int temperature = mNightDisplayTintController
                                        .getColorTemperatureSetting();
                                if (mNightDisplayTintController.getColorTemperature()
                                        != temperature) {
                                    mNightDisplayTintController
                                            .onColorTemperatureChanged(temperature);
                                }
                                break;
                            case Secure.NIGHT_DISPLAY_AUTO_MODE:
                                onNightDisplayAutoModeChanged(getNightDisplayAutoModeInternal());
                                break;
                            case Secure.NIGHT_DISPLAY_CUSTOM_START_TIME:
                                onNightDisplayCustomStartTimeChanged(
                                        getNightDisplayCustomStartTimeInternal().getLocalTime());
                                break;
                            case Secure.NIGHT_DISPLAY_CUSTOM_END_TIME:
                                onNightDisplayCustomEndTimeChanged(
                                        getNightDisplayCustomEndTimeInternal().getLocalTime());
                                break;
                            case System.DISPLAY_COLOR_MODE:
                                onDisplayColorModeChanged(getColorModeInternal());
                                break;
                            case Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED:
                                onAccessibilityInversionChanged();
                                onAccessibilityActivated();
                                break;
                            case Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED:
                                onAccessibilityDaltonizerChanged();
                                onAccessibilityActivated();
                                break;
                            case Secure.ACCESSIBILITY_DISPLAY_DALTONIZER:
                                onAccessibilityDaltonizerChanged();
                                break;
                            case Secure.DISPLAY_WHITE_BALANCE_ENABLED:
                                updateDisplayWhiteBalanceStatus();
                                break;
                            case Secure.REDUCE_BRIGHT_COLORS_ACTIVATED:
                                onReduceBrightColorsActivationChanged(/*userInitiated*/ true);
                                mHandler.sendEmptyMessage(MSG_APPLY_REDUCE_BRIGHT_COLORS);
                                break;
                            case Secure.REDUCE_BRIGHT_COLORS_LEVEL:
                                onReduceBrightColorsStrengthLevelChanged();
                                mHandler.sendEmptyMessage(MSG_APPLY_REDUCE_BRIGHT_COLORS);
                                break;
                        }
                    }
                }
            };
        }
        final ContentResolver cr = getContext().getContentResolver();
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_ACTIVATED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_AUTO_MODE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_START_TIME),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_END_TIME),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(System.getUriFor(System.DISPLAY_COLOR_MODE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(
                Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_DALTONIZER),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.DISPLAY_WHITE_BALANCE_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.REDUCE_BRIGHT_COLORS_ACTIVATED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.REDUCE_BRIGHT_COLORS_LEVEL),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);

        // Apply the accessibility settings first, since they override most other settings.
        onAccessibilityInversionChanged();
        onAccessibilityDaltonizerChanged();

        setUpDisplayCompositionColorSpaces(getContext().getResources());

        // Set the color mode, if valid, and immediately apply the updated tint matrix based on the
        // existing activated state. This ensures consistency of tint across the color mode change.
        onDisplayColorModeChanged(getColorModeInternal());

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        if (mNightDisplayTintController.isAvailable(getContext())) {
            // Reset the activated state.
            mNightDisplayTintController.setActivated(null);

            // Prepare the night display color transformation matrix.
            mNightDisplayTintController.setUp(getContext(), dtm.needsLinearColorMatrix());
            mNightDisplayTintController
                    .setMatrix(mNightDisplayTintController.getColorTemperatureSetting());

            // Initialize the current auto mode.
            onNightDisplayAutoModeChanged(getNightDisplayAutoModeInternal());

            // Force the initialization of the current saved activation state.
            if (mNightDisplayTintController.isActivatedStateNotSet()) {
                mNightDisplayTintController
                        .setActivated(mNightDisplayTintController.isActivatedSetting());
            }
        }

        if (mDisplayWhiteBalanceTintController.isAvailable(getContext())) {
            // Prepare the display white balance transform matrix.
            mDisplayWhiteBalanceTintController.setUp(getContext(), true /* needsLinear */);

            updateDisplayWhiteBalanceStatus();
        }

        if (mReduceBrightColorsTintController.isAvailable(getContext())) {
            mReduceBrightColorsTintController.setUp(getContext(), dtm.needsLinearColorMatrix());
            onReduceBrightColorsStrengthLevelChanged();
            final boolean reset = resetReduceBrightColors();
            if (!reset) {
                onReduceBrightColorsActivationChanged(/*userInitiated*/ false);
                mHandler.sendEmptyMessage(MSG_APPLY_REDUCE_BRIGHT_COLORS);
            }
        }
    }

    private void tearDown() {
        Slog.d(TAG, "tearDown: currentUser=" + mCurrentUser);

        if (mContentObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mContentObserver);
        }

        if (mNightDisplayTintController.isAvailable(getContext())) {
            if (mNightDisplayAutoMode != null) {
                mNightDisplayAutoMode.onStop();
                mNightDisplayAutoMode = null;
            }
            mNightDisplayTintController.endAnimator();
        }

        if (mDisplayWhiteBalanceTintController.isAvailable(getContext())) {
            mDisplayWhiteBalanceTintController.endAnimator();
        }

        if (mGlobalSaturationTintController.isAvailable(getContext())) {
            mGlobalSaturationTintController.setActivated(null);
        }

        if (mReduceBrightColorsTintController.isAvailable(getContext())) {
            mReduceBrightColorsTintController.setActivated(null);
        }
    }

    private boolean resetReduceBrightColors() {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return false;
        }

        final boolean isSettingActivated = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 0, mCurrentUser) == 1;
        final boolean shouldResetOnReboot = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS, 0, mCurrentUser) == 0;
        if (isSettingActivated && mReduceBrightColorsTintController.isActivatedStateNotSet()
                && shouldResetOnReboot) {
            return Secure.putIntForUser(getContext().getContentResolver(),
                    Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 0, mCurrentUser);
        }
        return false;
    }

    private void onNightDisplayAutoModeChanged(int autoMode) {
        Slog.d(TAG, "onNightDisplayAutoModeChanged: autoMode=" + autoMode);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onStop();
            mNightDisplayAutoMode = null;
        }

        if (autoMode == AUTO_MODE_CUSTOM_TIME) {
            mNightDisplayAutoMode = new CustomNightDisplayAutoMode();
        } else if (autoMode == AUTO_MODE_TWILIGHT) {
            mNightDisplayAutoMode = new TwilightNightDisplayAutoMode();
        }

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onStart();
        }
    }

    private void onNightDisplayCustomStartTimeChanged(LocalTime startTime) {
        Slog.d(TAG, "onNightDisplayCustomStartTimeChanged: startTime=" + startTime);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    private void onNightDisplayCustomEndTimeChanged(LocalTime endTime) {
        Slog.d(TAG, "onNightDisplayCustomEndTimeChanged: endTime=" + endTime);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    private int getCompositionColorSpace(int mode) {
        if (mColorModeCompositionColorSpaces == null) {
            return Display.COLOR_MODE_INVALID;
        }

        return mColorModeCompositionColorSpaces.get(mode, Display.COLOR_MODE_INVALID);
    }

    private void onDisplayColorModeChanged(int mode) {
        if (mode == NOT_SET) {
            return;
        }

        mNightDisplayTintController.cancelAnimator();
        mDisplayWhiteBalanceTintController.cancelAnimator();

        if (mNightDisplayTintController.isAvailable(getContext())) {
            final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
            mNightDisplayTintController.setUp(getContext(), dtm.needsLinearColorMatrix(mode));
            mNightDisplayTintController
                    .setMatrix(mNightDisplayTintController.getColorTemperatureSetting());
        }

        // dtm.setColorMode() needs to be called before
        // updateDisplayWhiteBalanceStatus(), this is because the latter calls
        // DisplayTransformManager.needsLinearColorMatrix(), therefore it is dependent
        // on the state of DisplayTransformManager.
        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        dtm.setColorMode(mode, mNightDisplayTintController.getMatrix(),
                getCompositionColorSpace(mode));

        if (mDisplayWhiteBalanceTintController.isAvailable(getContext())) {
            updateDisplayWhiteBalanceStatus();
        }
    }

    private void onAccessibilityActivated() {
        onDisplayColorModeChanged(getColorModeInternal());
    }

    private boolean isAccessiblityDaltonizerEnabled() {
        return Secure.getIntForUser(getContext().getContentResolver(),
            Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, mCurrentUser) != 0;
    }

    private boolean isAccessiblityInversionEnabled() {
        return Secure.getIntForUser(getContext().getContentResolver(),
            Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, mCurrentUser) != 0;
    }

    private boolean isAccessibilityEnabled() {
        return isAccessiblityDaltonizerEnabled() || isAccessiblityInversionEnabled();
    }

    /**
     * Apply the accessibility daltonizer transform based on the settings value.
     */
    private void onAccessibilityDaltonizerChanged() {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return;
        }
        final int daltonizerMode = isAccessiblityDaltonizerEnabled()
                ? Secure.getIntForUser(getContext().getContentResolver(),
                    Secure.ACCESSIBILITY_DISPLAY_DALTONIZER,
                    AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY, mCurrentUser)
                : AccessibilityManager.DALTONIZER_DISABLED;

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        if (daltonizerMode == AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY) {
            // Monochromacy isn't supported by the native Daltonizer implementation; use grayscale.
            dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE,
                    MATRIX_GRAYSCALE);
            dtm.setDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
        } else {
            dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE, null);
            dtm.setDaltonizerMode(daltonizerMode);
        }
    }

    /**
     * Apply the accessibility inversion transform based on the settings value.
     */
    private void onAccessibilityInversionChanged() {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return;
        }
        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR,
                isAccessiblityInversionEnabled() ? MATRIX_INVERT_COLOR : null);
    }

    private void onReduceBrightColorsActivationChanged(boolean userInitiated) {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return;
        }
        final boolean activated = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 0, mCurrentUser) == 1;
        mReduceBrightColorsTintController.setActivated(activated);
        if (mReduceBrightColorsListener != null) {
            mReduceBrightColorsListener.onReduceBrightColorsActivationChanged(activated,
                    userInitiated);
        }
    }

    private void onReduceBrightColorsStrengthLevelChanged() {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return;
        }
        int strength = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.REDUCE_BRIGHT_COLORS_LEVEL, NOT_SET, mCurrentUser);
        if (strength == NOT_SET) {
            strength = getContext().getResources().getInteger(
                    R.integer.config_reduceBrightColorsStrengthDefault);
        }
        mReduceBrightColorsTintController.setMatrix(strength);
        if (mReduceBrightColorsListener != null) {
            mReduceBrightColorsListener.onReduceBrightColorsStrengthChanged(strength);
        }
    }

    /**
     * Applies current color temperature matrix, or removes it if deactivated.
     *
     * @param immediate {@code true} skips transition animation
     */
    private void applyTint(TintController tintController, boolean immediate) {
        tintController.cancelAnimator();

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        final float[] from = dtm.getColorMatrix(tintController.getLevel());
        final float[] to = tintController.getMatrix();

        if (immediate) {
            dtm.setColorMatrix(tintController.getLevel(), to);
        } else {
            TintValueAnimator valueAnimator = TintValueAnimator.ofMatrix(COLOR_MATRIX_EVALUATOR,
                    from == null ? MATRIX_IDENTITY : from, to);
            tintController.setAnimator(valueAnimator);
            valueAnimator.setDuration(TRANSITION_DURATION);
            valueAnimator.setInterpolator(AnimationUtils.loadInterpolator(
                    getContext(), android.R.interpolator.fast_out_slow_in));
            valueAnimator.addUpdateListener((ValueAnimator animator) -> {
                final float[] value = (float[]) animator.getAnimatedValue();
                dtm.setColorMatrix(tintController.getLevel(), value);
                ((TintValueAnimator) animator).updateMinMaxComponents();
            });
            valueAnimator.addListener(new AnimatorListenerAdapter() {

                private boolean mIsCancelled;

                @Override
                public void onAnimationCancel(Animator animator) {
                    mIsCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animator) {
                    TintValueAnimator t = (TintValueAnimator) animator;
                    Slog.d(TAG, tintController.getClass().getSimpleName()
                            + " Animation cancelled: " + mIsCancelled
                            + " to matrix: " + TintController.matrixToString(to, 16)
                            + " min matrix coefficients: "
                            + TintController.matrixToString(t.getMin(), 16)
                            + " max matrix coefficients: "
                            + TintController.matrixToString(t.getMax(), 16));
                    if (!mIsCancelled) {
                        // Ensure final color matrix is set at the end of the animation. If the
                        // animation is cancelled then don't set the final color matrix so the new
                        // animator can pick up from where this one left off.
                        dtm.setColorMatrix(tintController.getLevel(), to);
                    }
                    tintController.setAnimator(null);
                }
            });
            valueAnimator.start();
        }
    }

    /**
     * Returns the first date time corresponding to the local time that occurs before the provided
     * date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the prior LocalDateTime corresponding to this local time
     */
    @VisibleForTesting
    static LocalDateTime getDateTimeBefore(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time yesterday.
        return ldt.isAfter(compareTime) ? ldt.minusDays(1) : ldt;
    }

    /**
     * Returns the first date time corresponding to this local time that occurs after the provided
     * date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the next LocalDateTime corresponding to this local time
     */
    @VisibleForTesting
    static LocalDateTime getDateTimeAfter(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time tomorrow.
        return ldt.isBefore(compareTime) ? ldt.plusDays(1) : ldt;
    }

    @VisibleForTesting
    void updateDisplayWhiteBalanceStatus() {
        boolean oldActivated = mDisplayWhiteBalanceTintController.isActivated();
        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        mDisplayWhiteBalanceTintController.setActivated(isDisplayWhiteBalanceSettingEnabled()
                && !mNightDisplayTintController.isActivated()
                && !isAccessibilityEnabled()
                && dtm.needsLinearColorMatrix()
                && mDisplayWhiteBalanceTintController.isAllowed());
        boolean activated = mDisplayWhiteBalanceTintController.isActivated();

        if (mDisplayWhiteBalanceListener != null && oldActivated != activated) {
            mDisplayWhiteBalanceListener.onDisplayWhiteBalanceStatusChanged(activated);
        }

        // If disabled, clear the tint. If enabled, do nothing more here and let the next
        // temperature update set the correct tint.
        if (!activated) {
            mHandler.sendEmptyMessage(MSG_APPLY_DISPLAY_WHITE_BALANCE);
        }
    }

    private boolean setDisplayWhiteBalanceSettingEnabled(boolean enabled) {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return false;
        }
        return Secure.putIntForUser(getContext().getContentResolver(),
                Secure.DISPLAY_WHITE_BALANCE_ENABLED,
                enabled ? 1 : 0, mCurrentUser);
    }

    private boolean isDisplayWhiteBalanceSettingEnabled() {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return false;
        }
        return Secure.getIntForUser(getContext().getContentResolver(),
                Secure.DISPLAY_WHITE_BALANCE_ENABLED,
                getContext().getResources()
                        .getBoolean(R.bool.config_displayWhiteBalanceEnabledDefault) ? 1
                        : 0,
                mCurrentUser) == 1;
    }

    private boolean setReduceBrightColorsActivatedInternal(boolean activated) {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return false;
        }
        return Secure.putIntForUser(getContext().getContentResolver(),
                Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, activated ? 1 : 0, mCurrentUser);
    }

    private boolean setReduceBrightColorsStrengthInternal(int strength) {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return false;
        }
        return Secure.putIntForUser(getContext().getContentResolver(),
                Secure.REDUCE_BRIGHT_COLORS_LEVEL, strength, mCurrentUser);
    }

    private boolean isDeviceColorManagedInternal() {
        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        return dtm.isDeviceColorManaged();
    }

    private int getTransformCapabilitiesInternal() {
        int availabilityFlags = ColorDisplayManager.CAPABILITY_NONE;
        if (SurfaceControl.getProtectedContentSupport()) {
            availabilityFlags |= ColorDisplayManager.CAPABILITY_PROTECTED_CONTENT;
        }
        final Resources res = getContext().getResources();
        if (res.getBoolean(R.bool.config_setColorTransformAccelerated)) {
            availabilityFlags |= ColorDisplayManager.CAPABILITY_HARDWARE_ACCELERATION_GLOBAL;
        }
        if (res.getBoolean(R.bool.config_setColorTransformAcceleratedPerLayer)) {
            availabilityFlags |= ColorDisplayManager.CAPABILITY_HARDWARE_ACCELERATION_PER_APP;
        }
        return availabilityFlags;
    }

    private boolean setNightDisplayAutoModeInternal(@AutoMode int autoMode) {
        if (getNightDisplayAutoModeInternal() != autoMode) {
            Secure.putStringForUser(getContext().getContentResolver(),
                    Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME,
                    null,
                    mCurrentUser);
        }
        return Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NIGHT_DISPLAY_AUTO_MODE, autoMode, mCurrentUser);
    }

    private int getNightDisplayAutoModeInternal() {
        int autoMode = getNightDisplayAutoModeRawInternal();
        if (autoMode == NOT_SET) {
            autoMode = getContext().getResources().getInteger(
                    R.integer.config_defaultNightDisplayAutoMode);
        }
        if (autoMode != AUTO_MODE_DISABLED
                && autoMode != AUTO_MODE_CUSTOM_TIME
                && autoMode != AUTO_MODE_TWILIGHT) {
            Slog.e(TAG, "Invalid autoMode: " + autoMode);
            autoMode = AUTO_MODE_DISABLED;
        }
        return autoMode;
    }

    private int getNightDisplayAutoModeRawInternal() {
        if (mCurrentUser == UserHandle.USER_NULL) {
            return NOT_SET;
        }
        return Secure
                .getIntForUser(getContext().getContentResolver(), Secure.NIGHT_DISPLAY_AUTO_MODE,
                        NOT_SET, mCurrentUser);
    }

    private Time getNightDisplayCustomStartTimeInternal() {
        int startTimeValue = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_START_TIME, NOT_SET, mCurrentUser);
        if (startTimeValue == NOT_SET) {
            startTimeValue = getContext().getResources().getInteger(
                    R.integer.config_defaultNightDisplayCustomStartTime);
        }
        return new Time(LocalTime.ofSecondOfDay(startTimeValue / 1000));
    }

    private boolean setNightDisplayCustomStartTimeInternal(Time startTime) {
        return Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_START_TIME,
                startTime.getLocalTime().toSecondOfDay() * 1000,
                mCurrentUser);
    }

    private Time getNightDisplayCustomEndTimeInternal() {
        int endTimeValue = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_END_TIME, NOT_SET, mCurrentUser);
        if (endTimeValue == NOT_SET) {
            endTimeValue = getContext().getResources().getInteger(
                    R.integer.config_defaultNightDisplayCustomEndTime);
        }
        return new Time(LocalTime.ofSecondOfDay(endTimeValue / 1000));
    }

    private boolean setNightDisplayCustomEndTimeInternal(Time endTime) {
        return Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NIGHT_DISPLAY_CUSTOM_END_TIME, endTime.getLocalTime().toSecondOfDay() * 1000,
                mCurrentUser);
    }

    /**
     * Returns the last time the night display transform activation state was changed, or {@link
     * LocalDateTime#MIN} if night display has never been activated.
     */
    private LocalDateTime getNightDisplayLastActivatedTimeSetting() {
        final ContentResolver cr = getContext().getContentResolver();
        final String lastActivatedTime = Secure.getStringForUser(
                cr, Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME, getContext().getUserId());
        if (lastActivatedTime != null) {
            try {
                return LocalDateTime.parse(lastActivatedTime);
            } catch (DateTimeParseException ignored) {
            }
            // Uses the old epoch time.
            try {
                return LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(lastActivatedTime)),
                        ZoneId.systemDefault());
            } catch (DateTimeException | NumberFormatException ignored) {
            }
        }
        return LocalDateTime.MIN;
    }

    void setSaturationLevelInternal(int saturationLevel) {
        final Message message = mHandler.obtainMessage(MSG_APPLY_GLOBAL_SATURATION);
        message.arg1 = saturationLevel;
        mHandler.sendMessage(message);
    }

    boolean setAppSaturationLevelInternal(String callingPackageName,
            String affectedPackageName, int saturationLevel) {
        return mAppSaturationController
                .setSaturationLevel(callingPackageName, affectedPackageName, mCurrentUser,
                        saturationLevel);
    }

    private void setColorModeInternal(@ColorMode int colorMode) {
        if (!isColorModeAvailable(colorMode)) {
            throw new IllegalArgumentException("Invalid colorMode: " + colorMode);
        }
        System.putIntForUser(getContext().getContentResolver(), System.DISPLAY_COLOR_MODE,
                colorMode,
                mCurrentUser);
    }

    private @ColorMode int getColorModeInternal() {
        final ContentResolver cr = getContext().getContentResolver();
        if (isAccessibilityEnabled()) {
            // There are restrictions on the available color modes combined with a11y transforms.
            final int a11yColorMode = getContext().getResources().getInteger(
                    R.integer.config_accessibilityColorMode);
            if (a11yColorMode >= 0) {
                return a11yColorMode;
            }
        }

        int colorMode = System.getIntForUser(cr, System.DISPLAY_COLOR_MODE, -1, mCurrentUser);
        if (colorMode == -1) {
            // There might be a system property controlling color mode that we need to respect; if
            // not, this will set a suitable default.
            colorMode = getCurrentColorModeFromSystemProperties();
        }

        // This happens when a color mode is no longer available (e.g., after system update or B&R)
        // or the device does not support any color mode.
        if (!isColorModeAvailable(colorMode)) {
            final int[] mappedColorModes = getContext().getResources().getIntArray(
                    R.array.config_mappedColorModes);
            if (colorMode != -1 && mappedColorModes.length > colorMode
                    && isColorModeAvailable(mappedColorModes[colorMode])) {
                colorMode = mappedColorModes[colorMode];
            } else {
                final int[] availableColorModes = getContext().getResources().getIntArray(
                        R.array.config_availableColorModes);
                if (availableColorModes.length > 0) {
                    colorMode = availableColorModes[0];
                } else {
                    colorMode = NOT_SET;
                }
            }
        }

        return colorMode;
    }

    /**
     * Get the current color mode from system properties, or return -1 if invalid.
     *
     * See {@link DisplayTransformManager}
     */
    private @ColorMode int getCurrentColorModeFromSystemProperties() {
        final int displayColorSetting = SystemProperties.getInt("persist.sys.sf.native_mode", 0);
        if (displayColorSetting == 0) {
            return "1.0".equals(SystemProperties.get("persist.sys.sf.color_saturation"))
                    ? COLOR_MODE_NATURAL : COLOR_MODE_BOOSTED;
        } else if (displayColorSetting == 1) {
            return COLOR_MODE_SATURATED;
        } else if (displayColorSetting == 2) {
            return COLOR_MODE_AUTOMATIC;
        } else if (displayColorSetting >= VENDOR_COLOR_MODE_RANGE_MIN
                && displayColorSetting <= VENDOR_COLOR_MODE_RANGE_MAX) {
            return displayColorSetting;
        } else {
            return -1;
        }
    }

    private boolean isColorModeAvailable(@ColorMode int colorMode) {
        final int[] availableColorModes = getContext().getResources().getIntArray(
                R.array.config_availableColorModes);
        if (availableColorModes != null) {
            for (int mode : availableColorModes) {
                if (mode == colorMode) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("COLOR DISPLAY MANAGER dumpsys (color_display)");

        pw.println("Night display:");
        if (mNightDisplayTintController.isAvailable(getContext())) {
            pw.println("    Activated: " + mNightDisplayTintController.isActivated());
            pw.println("    Color temp: " + mNightDisplayTintController.getColorTemperature());
        } else {
            pw.println("    Not available");
        }

        pw.println("Global saturation:");
        if (mGlobalSaturationTintController.isAvailable(getContext())) {
            pw.println("    Activated: " + mGlobalSaturationTintController.isActivated());
        } else {
            pw.println("    Not available");
        }

        mAppSaturationController.dump(pw);

        pw.println("Display white balance:");
        if (mDisplayWhiteBalanceTintController.isAvailable(getContext())) {
            pw.println("    Activated: " + mDisplayWhiteBalanceTintController.isActivated());
            mDisplayWhiteBalanceTintController.dump(pw);
        } else {
            pw.println("    Not available");
        }

        pw.println("Reduce bright colors:");
        if (mReduceBrightColorsTintController.isAvailable(getContext())) {
            pw.println("    Activated: " + mReduceBrightColorsTintController.isActivated());
            mReduceBrightColorsTintController.dump(pw);
        } else {
            pw.println("    Not available");
        }

        pw.println("Color mode: " + getColorModeInternal());
    }

    private abstract class NightDisplayAutoMode {

        public abstract void onActivated(boolean activated);

        public abstract void onStart();

        public abstract void onStop();

        public void onCustomStartTimeChanged(LocalTime startTime) {
        }

        public void onCustomEndTimeChanged(LocalTime endTime) {
        }
    }

    private final class CustomNightDisplayAutoMode extends NightDisplayAutoMode implements
            AlarmManager.OnAlarmListener {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private LocalTime mStartTime;
        private LocalTime mEndTime;

        private LocalDateTime mLastActivatedTime;

        CustomNightDisplayAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
        }

        private void updateActivated() {
            final LocalDateTime now = LocalDateTime.now();
            final LocalDateTime start = getDateTimeBefore(mStartTime, now);
            final LocalDateTime end = getDateTimeAfter(mEndTime, start);
            boolean activate = now.isBefore(end);

            if (mLastActivatedTime != null) {
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now)
                        && mLastActivatedTime.isAfter(start)
                        && (mLastActivatedTime.isAfter(end) || now.isBefore(end))) {
                    activate = mNightDisplayTintController.isActivatedSetting();
                }
            }

            if (mNightDisplayTintController.isActivatedStateNotSet()
                    || (mNightDisplayTintController.isActivated() != activate)) {
                mNightDisplayTintController.setActivated(activate, activate ? start : end);
            }

            updateNextAlarm(mNightDisplayTintController.isActivated(), now);
        }

        private void updateNextAlarm(@Nullable Boolean activated, @NonNull LocalDateTime now) {
            if (activated != null) {
                final LocalDateTime next = activated ? getDateTimeAfter(mEndTime, now)
                        : getDateTimeAfter(mStartTime, now);
                final long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                mAlarmManager.setExact(AlarmManager.RTC, millis, TAG, this, null);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            mStartTime = getNightDisplayCustomStartTimeInternal().getLocalTime();
            mEndTime = getNightDisplayCustomEndTimeInternal().getLocalTime();

            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);

            mAlarmManager.cancel(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();
            updateNextAlarm(activated, LocalDateTime.now());
        }

        @Override
        public void onCustomStartTimeChanged(LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onAlarm() {
            Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private final class TwilightNightDisplayAutoMode extends NightDisplayAutoMode implements
            TwilightListener {

        private final TwilightManager mTwilightManager;
        private LocalDateTime mLastActivatedTime;

        TwilightNightDisplayAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
        }

        private void updateActivated(TwilightState state) {
            if (state == null) {
                // If there isn't a valid TwilightState then just keep the current activated
                // state.
                return;
            }

            boolean activate = state.isNight();
            if (mLastActivatedTime != null) {
                final LocalDateTime now = LocalDateTime.now();
                final LocalDateTime sunrise = state.sunrise();
                final LocalDateTime sunset = state.sunset();
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now) && (mLastActivatedTime.isBefore(sunrise)
                        ^ mLastActivatedTime.isBefore(sunset))) {
                    activate = mNightDisplayTintController.isActivatedSetting();
                }
            }

            if (mNightDisplayTintController.isActivatedStateNotSet() || (
                    mNightDisplayTintController.isActivated() != activate)) {
                mNightDisplayTintController.setActivated(activate);
            }
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();

            // Force an update to initialize state.
            updateActivated(mTwilightManager.getLastTwilightState());
        }

        @Override
        public void onStop() {
            mTwilightManager.unregisterListener(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            Slog.d(TAG, "onTwilightStateChanged: isNight="
                    + (state == null ? null : state.isNight()));
            updateActivated(state);
        }
    }

    /**
     * Only animates matrices and saves min and max coefficients for logging.
     */
    static class TintValueAnimator extends ValueAnimator {
        private float[] min;
        private float[] max;

        public static TintValueAnimator ofMatrix(ColorMatrixEvaluator evaluator,
                Object... values) {
            TintValueAnimator anim = new TintValueAnimator();
            anim.setObjectValues(values);
            anim.setEvaluator(evaluator);
            if (values == null || values.length == 0) {
                return null;
            }
            float[] m = (float[]) values[0];
            anim.min = new float[m.length];
            anim.max = new float[m.length];
            for (int i = 0; i < m.length; ++i) {
                anim.min[i] = Float.MAX_VALUE;
                anim.max[i] = Float.MIN_VALUE;
            }
            return anim;
        }

        public void updateMinMaxComponents() {
            float[] value = (float[]) getAnimatedValue();
            if (value == null) {
                return;
            }
            for (int i = 0; i < value.length; ++i) {
                min[i] = Math.min(min[i], value[i]);
                max[i] = Math.max(max[i], value[i]);
            }
        }

        public float[] getMin() {
            return min;
        }

        public float[] getMax() {
            return max;
        }
    }

    /**
     * Interpolates between two 4x4 color transform matrices (in column-major order).
     */
    private static class ColorMatrixEvaluator implements TypeEvaluator<float[]> {

        /**
         * Result matrix returned by {@link #evaluate(float, float[], float[])}.
         */
        private final float[] mResultMatrix = new float[16];

        @Override
        public float[] evaluate(float fraction, float[] startValue, float[] endValue) {
            for (int i = 0; i < mResultMatrix.length; i++) {
                mResultMatrix[i] = MathUtils.lerp(startValue[i], endValue[i], fraction);
            }
            return mResultMatrix;
        }
    }

    private final class NightDisplayTintController extends TintController {

        private final float[] mMatrix = new float[16];
        private final float[] mColorTempCoefficients = new float[9];

        private Boolean mIsAvailable;
        private Integer mColorTemp;

        /**
         * Set coefficients based on whether the color matrix is linear or not.
         */
        @Override
        public void setUp(Context context, boolean needsLinear) {
            final String[] coefficients = context.getResources().getStringArray(needsLinear
                    ? R.array.config_nightDisplayColorTemperatureCoefficients
                    : R.array.config_nightDisplayColorTemperatureCoefficientsNative);
            for (int i = 0; i < 9 && i < coefficients.length; i++) {
                mColorTempCoefficients[i] = Float.parseFloat(coefficients[i]);
            }
        }

        @Override
        public void setMatrix(int cct) {
            if (mMatrix.length != 16) {
                Slog.d(TAG, "The display transformation matrix must be 4x4");
                return;
            }

            Matrix.setIdentityM(mMatrix, 0);

            final float squareTemperature = cct * cct;
            final float red = squareTemperature * mColorTempCoefficients[0]
                    + cct * mColorTempCoefficients[1] + mColorTempCoefficients[2];
            final float green = squareTemperature * mColorTempCoefficients[3]
                    + cct * mColorTempCoefficients[4] + mColorTempCoefficients[5];
            final float blue = squareTemperature * mColorTempCoefficients[6]
                    + cct * mColorTempCoefficients[7] + mColorTempCoefficients[8];
            mMatrix[0] = red;
            mMatrix[5] = green;
            mMatrix[10] = blue;
        }

        @Override
        public float[] getMatrix() {
            return isActivated() ? mMatrix : MATRIX_IDENTITY;
        }

        @Override
        public void setActivated(Boolean activated) {
            setActivated(activated, LocalDateTime.now());
        }

        /**
         * Use directly when it is important that the last activation time be exact (for example, an
         * automatic change). Otherwise use {@link #setActivated(Boolean)}.
         */
        public void setActivated(Boolean activated, @NonNull LocalDateTime lastActivationTime) {
            if (activated == null) {
                super.setActivated(null);
                return;
            }

            boolean activationStateChanged = activated != isActivated();

            if (!isActivatedStateNotSet() && activationStateChanged) {
                // This is a true state change, so set this as the last activation time.
                Secure.putStringForUser(getContext().getContentResolver(),
                        Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME,
                        lastActivationTime.toString(),
                        mCurrentUser);
            }

            if (isActivatedStateNotSet() || activationStateChanged) {
                super.setActivated(activated);
                if (isActivatedSetting() != activated) {
                    Secure.putIntForUser(getContext().getContentResolver(),
                            Secure.NIGHT_DISPLAY_ACTIVATED,
                            activated ? 1 : 0, mCurrentUser);
                }
                onActivated(activated);
            }
        }

        @Override
        public int getLevel() {
            return LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;
        }

        @Override
        public boolean isAvailable(Context context) {
            if (mIsAvailable == null) {
                mIsAvailable = ColorDisplayManager.isNightDisplayAvailable(context);
            }
            return mIsAvailable;
        }

        private void onActivated(boolean activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");
            if (mNightDisplayAutoMode != null) {
                mNightDisplayAutoMode.onActivated(activated);
            }

            if (mDisplayWhiteBalanceTintController.isAvailable(getContext())) {
                updateDisplayWhiteBalanceStatus();
            }

            mHandler.sendEmptyMessage(MSG_APPLY_NIGHT_DISPLAY_ANIMATED);
        }

        int getColorTemperature() {
            return mColorTemp != null ? clampNightDisplayColorTemperature(mColorTemp)
                    : getColorTemperatureSetting();
        }

        boolean setColorTemperature(int temperature) {
            mColorTemp = temperature;
            final boolean success = Secure.putIntForUser(getContext().getContentResolver(),
                    Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, temperature, mCurrentUser);
            onColorTemperatureChanged(temperature);
            return success;
        }

        void onColorTemperatureChanged(int temperature) {
            setMatrix(temperature);
            mHandler.sendEmptyMessage(MSG_APPLY_NIGHT_DISPLAY_IMMEDIATE);
        }

        boolean isActivatedSetting() {
            if (mCurrentUser == UserHandle.USER_NULL) {
                return false;
            }
            return Secure.getIntForUser(getContext().getContentResolver(),
                    Secure.NIGHT_DISPLAY_ACTIVATED, 0, mCurrentUser) == 1;
        }

        int getColorTemperatureSetting() {
            if (mCurrentUser == UserHandle.USER_NULL) {
                return NOT_SET;
            }
            return clampNightDisplayColorTemperature(Secure.getIntForUser(
                    getContext().getContentResolver(), Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                    NOT_SET,
                    mCurrentUser));
        }

        private int clampNightDisplayColorTemperature(int colorTemperature) {
            if (colorTemperature == NOT_SET) {
                colorTemperature = getContext().getResources().getInteger(
                        R.integer.config_nightDisplayColorTemperatureDefault);
            }
            final int minimumTemperature = ColorDisplayManager
                    .getMinimumColorTemperature(getContext());
            final int maximumTemperature = ColorDisplayManager
                    .getMaximumColorTemperature(getContext());
            if (colorTemperature < minimumTemperature) {
                colorTemperature = minimumTemperature;
            } else if (colorTemperature > maximumTemperature) {
                colorTemperature = maximumTemperature;
            }

            return colorTemperature;
        }
    }

    /**
     * Local service that allows color transforms to be enabled from other system services.
     */
    public class ColorDisplayServiceInternal {

        /** Sets whether DWB should be allowed in the current state. */
        public void setDisplayWhiteBalanceAllowed(boolean allowed)  {
            mDisplayWhiteBalanceTintController.setAllowed(allowed);
            updateDisplayWhiteBalanceStatus();
        }

        /**
         * Set the current CCT value for the display white balance transform, and if the transform
         * is enabled, apply it.
         *
         * @param cct the color temperature in Kelvin.
         */
        public boolean setDisplayWhiteBalanceColorTemperature(int cct) {
            // Update the transform matrix even if it can't be applied.
            mDisplayWhiteBalanceTintController.setMatrix(cct);

            if (mDisplayWhiteBalanceTintController.isActivated()) {
                mHandler.sendEmptyMessage(MSG_APPLY_DISPLAY_WHITE_BALANCE);
                return true;
            }
            return false;
        }

        /** Get the luminance of the current chromatic adaptation matrix. */
        public float getDisplayWhiteBalanceLuminance() {
            return mDisplayWhiteBalanceTintController.getLuminance();
        }

        /**
         * Reset the CCT value for the display white balance transform to its default value.
         */
        public boolean resetDisplayWhiteBalanceColorTemperature() {
            int temperatureDefault = getContext().getResources()
                    .getInteger(R.integer.config_displayWhiteBalanceColorTemperatureDefault);
            Slog.d(TAG, "resetDisplayWhiteBalanceColorTemperature: " + temperatureDefault);
            return setDisplayWhiteBalanceColorTemperature(temperatureDefault);
        }

        /**
         * Sets the listener and returns whether display white balance is currently enabled.
         */
        public boolean setDisplayWhiteBalanceListener(DisplayWhiteBalanceListener listener) {
            mDisplayWhiteBalanceListener = listener;
            return mDisplayWhiteBalanceTintController.isActivated();
        }

        /**
         * Returns whether Display white balance is currently enabled.
         */
        public boolean isDisplayWhiteBalanceEnabled() {
            return isDisplayWhiteBalanceSettingEnabled();
        }

        /**
         * Sets the listener and returns whether reduce bright colors is currently enabled.
         */
        public boolean setReduceBrightColorsListener(ReduceBrightColorsListener listener) {
            mReduceBrightColorsListener = listener;
            return mReduceBrightColorsTintController.isActivated();
        }

        /**
         * Returns whether reduce bright colors is currently active.
         */
        public boolean isReduceBrightColorsActivated() {
            return mReduceBrightColorsTintController.isActivated();
        }

        public int getReduceBrightColorsStrength() {
            return mReduceBrightColorsTintController.getStrength();
        }

        /**
         * Gets the computed brightness, in nits, when the reduce bright colors feature is applied
         * at the current strength.
         *
         * @hide
         */
        public float getReduceBrightColorsAdjustedBrightnessNits(float nits) {
            return mReduceBrightColorsTintController.getAdjustedBrightness(nits);
        }

        /**
         * Adds a {@link WeakReference<ColorTransformController>} for a newly started activity, and
         * invokes {@link ColorTransformController#applyAppSaturation(float[], float[])} if needed.
         */
        public boolean attachColorTransformController(String packageName, @UserIdInt int userId,
                WeakReference<ColorTransformController> controller) {
            return mAppSaturationController
                    .addColorTransformController(packageName, userId, controller);
        }
    }

    /**
     * Listener for changes in display white balance status.
     */
    public interface DisplayWhiteBalanceListener {

        /**
         * Notify that the display white balance status has changed, either due to preemption by
         * another transform or the feature being turned off.
         */
        void onDisplayWhiteBalanceStatusChanged(boolean activated);
    }

    /**
     * Listener for changes in reduce bright colors status.
     */
    public interface ReduceBrightColorsListener {

        /**
         * Notify that the reduce bright colors activation status has changed.
         */
        void onReduceBrightColorsActivationChanged(boolean activated, boolean userInitiated);

        /**
         * Notify that the reduce bright colors strength has changed.
         */
        void onReduceBrightColorsStrengthChanged(int strength);
    }

    private final class TintHandler extends Handler {

        private TintHandler(Looper looper) {
            super(looper, null, true /* async */);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_CHANGED:
                    onUserChanged(msg.arg1);
                    break;
                case MSG_SET_UP:
                    setUp();
                    break;
                case MSG_APPLY_GLOBAL_SATURATION:
                    mGlobalSaturationTintController.setMatrix(msg.arg1);
                    applyTint(mGlobalSaturationTintController, false);
                    break;
                case MSG_APPLY_REDUCE_BRIGHT_COLORS:
                    applyTint(mReduceBrightColorsTintController, true);
                    break;
                case MSG_APPLY_NIGHT_DISPLAY_IMMEDIATE:
                    applyTint(mNightDisplayTintController, true);
                    break;
                case MSG_APPLY_NIGHT_DISPLAY_ANIMATED:
                    applyTint(mNightDisplayTintController, false);
                    break;
                case MSG_APPLY_DISPLAY_WHITE_BALANCE:
                    applyTint(mDisplayWhiteBalanceTintController, false);
                    break;
            }
        }
    }

    /**
     * Interface for applying transforms to a given AppWindow.
     */
    public interface ColorTransformController {

        /**
         * Apply the given saturation (grayscale) matrix to the associated AppWindow.
         */
        void applyAppSaturation(@Size(9) float[] matrix, @Size(3) float[] translation);
    }

    @VisibleForTesting
    final class BinderService extends IColorDisplayManager.Stub {

        @Override
        public void setColorMode(int colorMode) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set display color mode");
            final long token = Binder.clearCallingIdentity();
            try {
                setColorModeInternal(colorMode);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getColorMode() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getColorModeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isDeviceColorManaged() {
            final long token = Binder.clearCallingIdentity();
            try {
                return isDeviceColorManagedInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setSaturationLevel(int level) {
            final boolean hasTransformsPermission = getContext()
                    .checkCallingPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
                    == PackageManager.PERMISSION_GRANTED;
            final boolean hasLegacyPermission = getContext()
                    .checkCallingPermission(Manifest.permission.CONTROL_DISPLAY_SATURATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasTransformsPermission && !hasLegacyPermission) {
                throw new SecurityException("Permission required to set display saturation level");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                setSaturationLevelInternal(level);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return true;
        }

        @Override
        public boolean isSaturationActivated() {
            getContext().enforceCallingPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to get display saturation level");
            final long token = Binder.clearCallingIdentity();
            try {
                return !mGlobalSaturationTintController.isActivatedStateNotSet()
                        && mGlobalSaturationTintController.isActivated();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setAppSaturationLevel(String packageName, int level) {
            getContext().enforceCallingPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set display saturation level");
            final String callingPackageName = LocalServices.getService(PackageManagerInternal.class)
                    .getNameForUid(Binder.getCallingUid());
            final long token = Binder.clearCallingIdentity();
            try {
                return setAppSaturationLevelInternal(callingPackageName, packageName, level);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public int getTransformCapabilities() {
            getContext().enforceCallingPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to query transform capabilities");
            final long token = Binder.clearCallingIdentity();
            try {
                return getTransformCapabilitiesInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setNightDisplayActivated(boolean activated) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set night display activated");
            final long token = Binder.clearCallingIdentity();
            try {
                mNightDisplayTintController.setActivated(activated);
                return true;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isNightDisplayActivated() {
            final long token = Binder.clearCallingIdentity();
            try {
                return mNightDisplayTintController.isActivated();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setNightDisplayColorTemperature(int temperature) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set night display temperature");
            final long token = Binder.clearCallingIdentity();
            try {
                return mNightDisplayTintController.setColorTemperature(temperature);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getNightDisplayColorTemperature() {
            final long token = Binder.clearCallingIdentity();
            try {
                return mNightDisplayTintController.getColorTemperature();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setNightDisplayAutoMode(int autoMode) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set night display auto mode");
            final long token = Binder.clearCallingIdentity();
            try {
                return setNightDisplayAutoModeInternal(autoMode);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getNightDisplayAutoMode() {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to get night display auto mode");
            final long token = Binder.clearCallingIdentity();
            try {
                return getNightDisplayAutoModeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getNightDisplayAutoModeRaw() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getNightDisplayAutoModeRawInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setNightDisplayCustomStartTime(Time startTime) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set night display custom start time");
            final long token = Binder.clearCallingIdentity();
            try {
                return setNightDisplayCustomStartTimeInternal(startTime);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public Time getNightDisplayCustomStartTime() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getNightDisplayCustomStartTimeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setNightDisplayCustomEndTime(Time endTime) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set night display custom end time");
            final long token = Binder.clearCallingIdentity();
            try {
                return setNightDisplayCustomEndTimeInternal(endTime);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public Time getNightDisplayCustomEndTime() {
            final long token = Binder.clearCallingIdentity();
            try {
                return getNightDisplayCustomEndTimeInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setDisplayWhiteBalanceEnabled(boolean enabled) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set night display activated");
            final long token = Binder.clearCallingIdentity();
            try {
                return setDisplayWhiteBalanceSettingEnabled(enabled);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isDisplayWhiteBalanceEnabled() {
            final long token = Binder.clearCallingIdentity();
            try {
                return isDisplayWhiteBalanceSettingEnabled();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isReduceBrightColorsActivated() {
            final long token = Binder.clearCallingIdentity();
            try {
                return mReduceBrightColorsTintController.isActivated();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setReduceBrightColorsActivated(boolean activated) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set reduce bright colors activation state");
            final long token = Binder.clearCallingIdentity();
            try {
                return setReduceBrightColorsActivatedInternal(activated);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getReduceBrightColorsStrength() {
            final long token = Binder.clearCallingIdentity();
            try {
                return mReduceBrightColorsTintController.getStrength();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public float getReduceBrightColorsOffsetFactor() {
            final long token = Binder.clearCallingIdentity();
            try {
                return mReduceBrightColorsTintController.getOffsetFactor();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setReduceBrightColorsStrength(int strength) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set reduce bright colors strength");
            final long token = Binder.clearCallingIdentity();
            try {
                return setReduceBrightColorsStrengthInternal(strength);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int handleShellCommand(ParcelFileDescriptor in,
                ParcelFileDescriptor out, ParcelFileDescriptor err, String[] args) {
            getContext().enforceCallingOrSelfPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to use ADB color transform commands");
            final long token = Binder.clearCallingIdentity();
            try {
                return new ColorDisplayShellCommand(ColorDisplayService.this)
                    .exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                        err.getFileDescriptor(),
                        args);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
