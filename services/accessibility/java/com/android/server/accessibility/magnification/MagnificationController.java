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

package com.android.server.accessibility.magnification;

import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN;
import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_WINDOW;
import static android.content.pm.PackageManager.FEATURE_WINDOW_MAGNIFICATION;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_NONE;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.util.MathUtils.sqrt;

import static com.android.server.accessibility.AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID;

import android.accessibilityservice.MagnificationConfig;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseDoubleArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.ViewConfiguration;
import android.view.accessibility.MagnificationAnimationCallback;

import com.android.internal.accessibility.util.AccessibilityStatsLogUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.wm.WindowManagerInternal;

import java.util.concurrent.Executor;

/**
 * Handles all magnification controllers initialization, generic interactions,
 * magnification mode transition and magnification switch UI show/hide logic
 * in the following callbacks:
 *
 * <ol>
 *   <li> 1. {@link #onTouchInteractionStart} shows magnification switch UI when
 *   the user touch interaction starts if magnification capabilities is all. </li>
 *   <li> 2. {@link #onTouchInteractionEnd} shows magnification switch UI when
 *   the user touch interaction ends if magnification capabilities is all. </li>
 *   <li> 3. {@link #onWindowMagnificationActivationState} updates magnification switch UI
 *   depending on magnification capabilities and magnification active state when window
 *   magnification activation state change.</li>
 *   <li> 4. {@link #onFullScreenMagnificationActivationState} updates magnification switch UI
 *   depending on magnification capabilities and magnification active state when fullscreen
 *   magnification activation state change.</li>
 *   <li> 4. {@link #onRequestMagnificationSpec} updates magnification switch UI depending on
 *   magnification capabilities and magnification active state when new magnification spec is
 *   changed by external request from calling public APIs. </li>
 * </ol>
 *
 *  <b>Note</b> Updates magnification switch UI when magnification mode transition
 *  is done and before invoking {@link TransitionCallBack#onResult}.
 */
public class MagnificationController implements MagnificationConnectionManager.Callback,
        MagnificationGestureHandler.Callback, MagnificationKeyHandler.Callback,
        FullScreenMagnificationController.MagnificationInfoChangedCallback,
        WindowManagerInternal.AccessibilityControllerInternal.UiChangesForAccessibilityCallbacks {

    private static final boolean DEBUG = false;
    private static final String TAG = "MagnificationController";

    private final AccessibilityManagerService mAms;
    private final PointF mTempPoint = new PointF();
    private final Object mLock;
    private final Context mContext;
    @GuardedBy("mLock")
    private final SparseArray<DisableMagnificationCallback>
            mMagnificationEndRunnableSparseArray = new SparseArray();

    private final AlwaysOnMagnificationFeatureFlag mAlwaysOnMagnificationFeatureFlag;
    private final MagnificationScaleProvider mScaleProvider;
    private FullScreenMagnificationController mFullScreenMagnificationController;
    private MagnificationConnectionManager mMagnificationConnectionManager;
    private int mMagnificationCapabilities = ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
    /** Whether the platform supports window magnification feature. */
    private final boolean mSupportWindowMagnification;
    private final MagnificationScaleStepProvider mScaleStepProvider;
    private final MagnificationPanStepProvider mPanStepProvider;

    private final Executor mBackgroundExecutor;

    private final Handler mHandler;
    // Prefer this to SystemClock, because it allows for tests to influence behavior.
    private SystemClock mSystemClock;
    private boolean[] mActivePanDirections = {false, false, false, false};
    private int mActivePanDisplay = Display.INVALID_DISPLAY;
    // The time that panning by keyboard last took place. Since users can pan
    // in multiple directions at once (for example, up + left), tracking last
    // panned time ensures that panning doesn't occur too frequently.
    private long mLastPannedTime = 0;
    private boolean mRepeatKeysEnabled = true;

    private @ZoomDirection int mActiveZoomDirection = ZOOM_DIRECTION_IN;
    private int mActiveZoomDisplay = Display.INVALID_DISPLAY;

    private int mInitialKeyboardRepeatIntervalMs =
            ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT;
    @VisibleForTesting
    public static final int KEYBOARD_REPEAT_INTERVAL_MS = 60;

    @GuardedBy("mLock")
    private final SparseIntArray mCurrentMagnificationModeArray = new SparseIntArray();
    @GuardedBy("mLock")
    private final SparseIntArray mLastMagnificationActivatedModeArray = new SparseIntArray();
    // Track the active user to reset the magnification and get the associated user settings.
    private @UserIdInt int mUserId = UserHandle.USER_SYSTEM;
    @GuardedBy("mLock")
    private final SparseBooleanArray mIsImeVisibleArray = new SparseBooleanArray();
    @GuardedBy("mLock")
    private final SparseLongArray mWindowModeEnabledTimeArray = new SparseLongArray();
    @GuardedBy("mLock")
    private final SparseLongArray mFullScreenModeEnabledTimeArray = new SparseLongArray();

    /**
     * The transitioning magnification modes on the displays. The controller notifies
     * magnification change depending on the target config mode.
     * If the target mode is null, it means the config mode of the display is not
     * transitioning.
     */
    @GuardedBy("mLock")
    private final SparseArray<Integer> mTransitionModes = new SparseArray();

    @GuardedBy("mLock")
    private final SparseArray<WindowManagerInternal.AccessibilityControllerInternal
            .UiChangesForAccessibilityCallbacks> mAccessibilityCallbacksDelegateArray =
            new SparseArray<>();

    // Direction magnification scale can be altered.
    public static final int ZOOM_DIRECTION_IN = 0;
    public static final int ZOOM_DIRECTION_OUT = 1;

    @IntDef({ZOOM_DIRECTION_IN, ZOOM_DIRECTION_OUT})
    public @interface ZoomDirection {
    }

    // Directions magnification center can be moved.
    public static final int PAN_DIRECTION_LEFT = 0;
    public static final int PAN_DIRECTION_RIGHT = 1;
    public static final int PAN_DIRECTION_UP = 2;
    public static final int PAN_DIRECTION_DOWN = 3;

    @IntDef({PAN_DIRECTION_LEFT, PAN_DIRECTION_RIGHT, PAN_DIRECTION_UP, PAN_DIRECTION_DOWN})
    public @interface PanDirection {
    }

    /**
     * A callback to inform the magnification transition result on the given display.
     */
    public interface TransitionCallBack {
        /**
         * Invoked when the transition ends.
         *
         * @param displayId The display id.
         * @param success {@code true} if the transition success.
         */
        void onResult(int displayId, boolean success);
    }

    /**
     * Functional interface for providing time. Tests may extend this interface to "control time".
     */
    @VisibleForTesting
    interface SystemClock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }

    /** The real system clock for use in production. */
    private static class SystemClockImpl implements SystemClock {
        @Override
        public long uptimeMillis() {
            return android.os.SystemClock.uptimeMillis();
        }
    }


    /**
     * An interface to configure how much the magnification scale should be affected when moving in
     * steps.
     */
    public interface MagnificationScaleStepProvider {
        /**
         * Calculate the next value given which direction (in/out) to adjust the magnification
         * scale.
         *
         * @param currentScale The current magnification scale value.
         * @param direction    Whether to zoom in or out.
         * @return The next scale value.
         */
        float nextScaleStep(float currentScale, @ZoomDirection int direction);
    }

    public static class DefaultMagnificationScaleStepProvider implements
            MagnificationScaleStepProvider {
        // Factor of magnification scale. For example, when this value is 1.189, scale
        // value will be changed x1.000, x1.189, x1.414, x1.681, x2.000, ...
        // Note: this value is 2.0 ^ (1 / 4).
        public static final float ZOOM_STEP_SCALE_FACTOR = 1.18920712f;

        @Override
        public float nextScaleStep(float currentScale, @ZoomDirection int direction) {
            final int stepDelta = direction == ZOOM_DIRECTION_IN ? 1 : -1;
            final long scaleIndex = Math.round(
                    Math.log(currentScale) / Math.log(ZOOM_STEP_SCALE_FACTOR));
            final float nextScale = (float) Math.pow(ZOOM_STEP_SCALE_FACTOR,
                    scaleIndex + stepDelta);
            return MagnificationScaleProvider.constrainScale(nextScale);
        }
    }

    /**
     * An interface to configure how much the magnification center should be affected when panning
     * in steps.
     */
    public interface MagnificationPanStepProvider {
        /**
         * Calculate the next value based on the current scale.
         *
         * @param currentScale The current magnification scale value.
         * @param displayId The displayId for the display being magnified.
         * @return The next pan step value.
         */
        float nextPanStep(float currentScale, int displayId);
    }

    public static class DefaultMagnificationPanStepProvider implements
            MagnificationPanStepProvider, DisplayManager.DisplayListener {
        // We want panning to be 40 dip per keystroke at scale 2, and 1 dip per keystroke at scale
        // 20. This can be defined using y = mx + b to get the slope and intercept.
        // This works even if the device does not allow magnification up to 20x; we will still get
        // a reasonable lineary ramp of panning movement for each scale step.
        private static final float DEFAULT_SCALE = 2.0f;
        private static final float PAN_STEP_AT_DEFAULT_SCALE_DIP = 40.0f;
        private static final float SCALE_FOR_1_DIP_PAN = 20.0f;

        private SparseDoubleArray mPanStepSlopes;
        private SparseDoubleArray mPanStepIntercepts;

        private final DisplayManager mDisplayManager;

        DefaultMagnificationPanStepProvider(Context context) {
            mDisplayManager = context.getSystemService(DisplayManager.class);
            mDisplayManager.registerDisplayListener(this, /*handler=*/null);
            mPanStepSlopes = new SparseDoubleArray();
            mPanStepIntercepts = new SparseDoubleArray();
        }

        @Override
        public void onDisplayAdded(int displayId) {
            updateForDisplay(displayId);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateForDisplay(displayId);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            mPanStepSlopes.delete(displayId);
            mPanStepIntercepts.delete(displayId);
        }

        @Override
        public float nextPanStep(float currentScale, int displayId) {
            if (mPanStepSlopes.indexOfKey(displayId) < 0) {
                updateForDisplay(displayId);
            }
            return Math.max((float) (mPanStepSlopes.get(displayId) * currentScale
                    + mPanStepIntercepts.get(displayId)), 1);
        }

        private void updateForDisplay(int displayId) {
            Display display = mDisplayManager.getDisplay(displayId);
            if (display == null) {
                return;
            }
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            final float panStepAtDefaultScaleInPx = TypedValue.convertDimensionToPixels(
                    TypedValue.COMPLEX_UNIT_DIP, PAN_STEP_AT_DEFAULT_SCALE_DIP, metrics);
            final float panStepAtMaxScaleInPx = TypedValue.convertDimensionToPixels(
                    TypedValue.COMPLEX_UNIT_DIP, 1.0f, metrics);
            final float panStepSlope = (panStepAtMaxScaleInPx - panStepAtDefaultScaleInPx)
                    / (SCALE_FOR_1_DIP_PAN - DEFAULT_SCALE);
            mPanStepSlopes.put(displayId, panStepSlope);
            mPanStepIntercepts.put(displayId,
                    panStepAtDefaultScaleInPx - panStepSlope * DEFAULT_SCALE);
        }
    }

    public MagnificationController(AccessibilityManagerService ams, Object lock,
            Context context, MagnificationScaleProvider scaleProvider,
            Executor backgroundExecutor, Looper looper) {
        mAms = ams;
        mLock = lock;
        mContext = context;
        mScaleProvider = scaleProvider;
        mBackgroundExecutor = backgroundExecutor;
        mHandler = new Handler(looper);
        mSystemClock = new SystemClockImpl();
        LocalServices.getService(WindowManagerInternal.class)
                .getAccessibilityController().setUiChangesForAccessibilityCallbacks(this);
        mSupportWindowMagnification = context.getPackageManager().hasSystemFeature(
                FEATURE_WINDOW_MAGNIFICATION);
        mScaleStepProvider = new DefaultMagnificationScaleStepProvider();
        mPanStepProvider = new DefaultMagnificationPanStepProvider(mContext);

        mAlwaysOnMagnificationFeatureFlag = new AlwaysOnMagnificationFeatureFlag(context);
        mAlwaysOnMagnificationFeatureFlag.addOnChangedListener(
                mBackgroundExecutor, mAms::updateAlwaysOnMagnification);
    }

    @VisibleForTesting
    public MagnificationController(AccessibilityManagerService ams, Object lock,
            Context context, FullScreenMagnificationController fullScreenMagnificationController,
            MagnificationConnectionManager magnificationConnectionManager,
            MagnificationScaleProvider scaleProvider, Executor backgroundExecutor, Looper looper,
            SystemClock systemClock) {
        this(ams, lock, context, scaleProvider, backgroundExecutor, looper);
        mFullScreenMagnificationController = fullScreenMagnificationController;
        mMagnificationConnectionManager = magnificationConnectionManager;
        mSystemClock = systemClock;
    }

    @Override
    public void onPerformScaleAction(int displayId, float scale, boolean updatePersistence) {
        if (getFullScreenMagnificationController().isActivated(displayId)) {
            getFullScreenMagnificationController().setScaleAndCenter(displayId, scale,
                    Float.NaN, Float.NaN, /* isScaleTransient= */ !updatePersistence, false,
                    MAGNIFICATION_GESTURE_HANDLER_ID);
            if (updatePersistence) {
                getFullScreenMagnificationController().persistScale(displayId);
            }
        } else if (getMagnificationConnectionManager().isWindowMagnifierEnabled(displayId)) {
            getMagnificationConnectionManager().setScale(displayId, scale);
            if (updatePersistence) {
                getMagnificationConnectionManager().persistScale(displayId);
            }
        }
    }

    @Override
    public void onAccessibilityActionPerformed(int displayId) {
        updateMagnificationUIControls(displayId, ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
    }

    @Override
    public void onTouchInteractionStart(int displayId, int mode) {
        handleUserInteractionChanged(displayId, mode);
    }

    @Override
    public void onTouchInteractionEnd(int displayId, int mode) {
        handleUserInteractionChanged(displayId, mode);
    }

    @Override
    public void onPanMagnificationStart(int displayId,
            @MagnificationController.PanDirection int direction) {
        // Update the current panning state for any callbacks.
        boolean isAlreadyPanning = mActivePanDisplay != Display.INVALID_DISPLAY;
        mActivePanDisplay = displayId;
        mActivePanDirections[direction] = true;
        // React immediately to any new key press by panning in the new composite direction.
        panMagnificationByStep(mActivePanDisplay, mActivePanDirections);
        if (!isAlreadyPanning && mRepeatKeysEnabled) {
            mHandler.sendMessageDelayed(
                    PooledLambda.obtainMessage(MagnificationController::maybeContinuePan, this),
                    mInitialKeyboardRepeatIntervalMs);
        }
    }

    @Override
    public void onPanMagnificationStop(@MagnificationController.PanDirection int direction) {
        // Stop panning in this direction.
        mActivePanDirections[direction] = false;
        if (!mActivePanDirections[PAN_DIRECTION_LEFT]
                && !mActivePanDirections[PAN_DIRECTION_RIGHT]
                && !mActivePanDirections[PAN_DIRECTION_UP]
                && !mActivePanDirections[PAN_DIRECTION_DOWN]) {
            // Stop all panning if no more pan directions were in started.
            mActivePanDisplay = Display.INVALID_DISPLAY;
        }
    }

    @Override
    public void onScaleMagnificationStart(int displayId,
            @MagnificationController.ZoomDirection int direction) {
        if (mActiveZoomDisplay != Display.INVALID_DISPLAY) {
            // Only allow one zoom direction at a time (even if the other keyboard
            // shortcut has been pressed). Return early if we are already zooming.
            return;
        }
        mActiveZoomDirection = direction;
        mActiveZoomDisplay = displayId;
        scaleMagnificationByStep(displayId, direction);
        if (mRepeatKeysEnabled) {
            mHandler.sendMessageDelayed(
                    PooledLambda.obtainMessage(MagnificationController::maybeContinueZoom, this),
                    mInitialKeyboardRepeatIntervalMs);
        }
    }

    @Override
    public void onScaleMagnificationStop(@MagnificationController.ZoomDirection int direction) {
        if (direction == mActiveZoomDirection) {
            mActiveZoomDisplay = Display.INVALID_DISPLAY;
        }
    }

    @Override
    public void onKeyboardInteractionStop() {
        mActiveZoomDisplay = Display.INVALID_DISPLAY;
        mActivePanDisplay = Display.INVALID_DISPLAY;
        mActivePanDirections = new boolean[]{false, false, false, false};
    }

    private void maybeContinuePan() {
        if (mActivePanDisplay == Display.INVALID_DISPLAY) {
            return;
        }
        if (mSystemClock.uptimeMillis() - mLastPannedTime >= KEYBOARD_REPEAT_INTERVAL_MS) {
            panMagnificationByStep(mActivePanDisplay, mActivePanDirections);
        }
        if (mRepeatKeysEnabled) {
            mHandler.sendMessageDelayed(
                    PooledLambda.obtainMessage(MagnificationController::maybeContinuePan, this),
                    KEYBOARD_REPEAT_INTERVAL_MS);
        }
    }

    private void maybeContinueZoom() {
        if (mActiveZoomDisplay != Display.INVALID_DISPLAY) {
            scaleMagnificationByStep(mActiveZoomDisplay, mActiveZoomDirection);
            if (mRepeatKeysEnabled) {
                mHandler.sendMessageDelayed(
                        PooledLambda.obtainMessage(MagnificationController::maybeContinueZoom,
                                this),
                        KEYBOARD_REPEAT_INTERVAL_MS);
            }
        }
    }

    public void setRepeatKeysEnabled(boolean isRepeatKeysEnabled) {
        mRepeatKeysEnabled = isRepeatKeysEnabled;
    }

    public void setRepeatKeysTimeoutMs(int repeatKeysTimeoutMs) {
        mInitialKeyboardRepeatIntervalMs = repeatKeysTimeoutMs;
    }

    @VisibleForTesting
    public int getInitialKeyboardRepeatIntervalMs() {
        return mInitialKeyboardRepeatIntervalMs;
    }

    private void handleUserInteractionChanged(int displayId, int mode) {
        if (mMagnificationCapabilities != Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL) {
            return;
        }
        updateMagnificationUIControls(displayId, mode);
    }

    private void updateMagnificationUIControls(int displayId, int mode) {
        final boolean isActivated = isActivated(displayId, mode);
        final boolean showModeSwitchButton;
        final boolean enableSettingsPanel;
        synchronized (mLock) {
            showModeSwitchButton = isActivated
                    && mMagnificationCapabilities == ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
            enableSettingsPanel = isActivated
                    && (mMagnificationCapabilities == ACCESSIBILITY_MAGNIFICATION_MODE_ALL
                    || mMagnificationCapabilities == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        }

        if (showModeSwitchButton) {
            getMagnificationConnectionManager().showMagnificationButton(displayId, mode);
        } else {
            getMagnificationConnectionManager().removeMagnificationButton(displayId);
        }

        if (!enableSettingsPanel) {
            // Whether the settings panel needs to be shown is controlled in system UI.
            // Here, we only guarantee that the settings panel is closed when it is not needed.
            getMagnificationConnectionManager().removeMagnificationSettingsPanel(displayId);
        }
    }

    /** Returns {@code true} if the platform supports window magnification feature. */
    public boolean supportWindowMagnification() {
        return mSupportWindowMagnification;
    }

    /**
     * Transitions to the target Magnification mode with current center of the magnification mode
     * if it is available.
     *
     * @param displayId The logical display
     * @param targetMode The target magnification mode
     * @param transitionCallBack The callback invoked when the transition is finished.
     */
    public void transitionMagnificationModeLocked(int displayId, int targetMode,
            @NonNull TransitionCallBack transitionCallBack) {
        // check if target mode is already activated
        if (isActivated(displayId, targetMode)) {
            transitionCallBack.onResult(displayId, true);
            return;
        }

        final PointF currentCenter = getCurrentMagnificationCenterLocked(displayId, targetMode);
        final DisableMagnificationCallback animationCallback =
                getDisableMagnificationEndRunnableLocked(displayId);

        if (currentCenter == null && animationCallback == null) {
            transitionCallBack.onResult(displayId, true);
            return;
        }

        if (animationCallback != null) {
            if (animationCallback.mCurrentMode == targetMode) {
                animationCallback.restoreToCurrentMagnificationMode();
                return;
            } else {
                Slog.w(TAG, "discard duplicate request");
                return;
            }
        }

        if (currentCenter == null) {
            Slog.w(TAG, "Invalid center, ignore it");
            transitionCallBack.onResult(displayId, true);
            return;
        }

        setTransitionState(displayId, targetMode);

        final FullScreenMagnificationController screenMagnificationController =
                getFullScreenMagnificationController();
        final MagnificationConnectionManager magnificationConnectionManager =
                getMagnificationConnectionManager();
        final float scale = getTargetModeScaleFromCurrentMagnification(displayId, targetMode);
        final DisableMagnificationCallback animationEndCallback =
                new DisableMagnificationCallback(transitionCallBack, displayId, targetMode,
                        scale, currentCenter, true);

        setDisableMagnificationCallbackLocked(displayId, animationEndCallback);

        if (targetMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW) {
            screenMagnificationController.reset(displayId, animationEndCallback);
        } else {
            magnificationConnectionManager.disableWindowMagnification(displayId, false,
                    animationEndCallback);
        }
    }

    /**
     * Transitions to the targeting magnification config mode with current center of the
     * magnification mode if it is available. It disables the current magnifier immediately then
     * transitions to the targeting magnifier.
     *
     * @param displayId  The logical display id
     * @param config The targeting magnification config
     * @param animate    {@code true} to animate the transition, {@code false}
     *                   to transition immediately
     * @param id        The ID of the service requesting the change
     */
    public void transitionMagnificationConfigMode(int displayId, MagnificationConfig config,
            boolean animate, int id) {
        if (DEBUG) {
            Slog.d(TAG, "transitionMagnificationConfigMode displayId = " + displayId
                    + ", config = " + config);
        }
        synchronized (mLock) {
            final int targetMode = config.getMode();
            final boolean targetActivated = config.isActivated();
            final PointF currentCenter = getCurrentMagnificationCenterLocked(displayId, targetMode);
            final PointF magnificationCenter = new PointF(config.getCenterX(), config.getCenterY());
            if (currentCenter != null) {
                final float centerX = Float.isNaN(config.getCenterX())
                        ? currentCenter.x
                        : config.getCenterX();
                final float centerY = Float.isNaN(config.getCenterY())
                        ? currentCenter.y
                        : config.getCenterY();
                magnificationCenter.set(centerX, centerY);
            }

            final DisableMagnificationCallback animationCallback =
                    getDisableMagnificationEndRunnableLocked(displayId);
            if (animationCallback != null) {
                Slog.w(TAG, "Discard previous animation request");
                animationCallback.setExpiredAndRemoveFromListLocked();
            }
            final FullScreenMagnificationController screenMagnificationController =
                    getFullScreenMagnificationController();
            final MagnificationConnectionManager magnificationConnectionManager =
                    getMagnificationConnectionManager();
            final float targetScale = Float.isNaN(config.getScale())
                    ? getTargetModeScaleFromCurrentMagnification(displayId, targetMode)
                    : config.getScale();
            try {
                setTransitionState(displayId, targetMode);
                final MagnificationAnimationCallback magnificationAnimationCallback = animate
                        ? success -> mAms.changeMagnificationMode(displayId, targetMode)
                        : null;
                // Activate or deactivate target mode depending on config activated value
                if (targetMode == MAGNIFICATION_MODE_WINDOW) {
                    screenMagnificationController.reset(displayId, false);
                    if (targetActivated) {
                        magnificationConnectionManager.enableWindowMagnification(displayId,
                                targetScale, magnificationCenter.x, magnificationCenter.y,
                                magnificationAnimationCallback, id);
                    } else {
                        magnificationConnectionManager.disableWindowMagnification(displayId, false);
                    }
                } else if (targetMode == MAGNIFICATION_MODE_FULLSCREEN) {
                    magnificationConnectionManager.disableWindowMagnification(
                            displayId, false, null);
                    if (targetActivated) {
                        if (!screenMagnificationController.isRegistered(displayId)) {
                            screenMagnificationController.register(displayId);
                        }
                        screenMagnificationController.setScaleAndCenter(displayId, targetScale,
                                magnificationCenter.x, magnificationCenter.y,
                                /* isScaleTransient= */ false, magnificationAnimationCallback, id);
                    } else {
                        if (screenMagnificationController.isRegistered(displayId)) {
                            screenMagnificationController.reset(displayId, false);
                        }
                    }
                }
            } finally {
                if (!animate) {
                    mAms.changeMagnificationMode(displayId, targetMode);
                }
                // Reset transition state after enabling target mode.
                setTransitionState(displayId, null);
            }
        }
    }

    /**
     * Sets magnification config mode transition state. Called when the mode transition starts and
     * ends. If the targetMode and the display id are null, it resets all
     * the transition state.
     *
     * @param displayId  The logical display id
     * @param targetMode The transition target mode. It is not transitioning, if the target mode
     *                   is set null
     */
    private void setTransitionState(Integer displayId, Integer targetMode) {
        synchronized (mLock) {
            if (targetMode == null && displayId == null) {
                mTransitionModes.clear();
            } else {
                mTransitionModes.put(displayId, targetMode);
            }
        }
    }

    // We assume the target mode is different from the current mode, and there is only
    // two modes, so we get the target scale from another mode.
    private float getTargetModeScaleFromCurrentMagnification(int displayId, int targetMode) {
        if (targetMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW) {
            return getFullScreenMagnificationController().getScale(displayId);
        } else {
            return getMagnificationConnectionManager().getScale(displayId);
        }
    }

    /**
     * Return {@code true} if disable magnification animation callback of the display is running.
     *
     * @param displayId The logical display id
     */
    public boolean hasDisableMagnificationCallback(int displayId) {
        synchronized (mLock) {
            final DisableMagnificationCallback animationCallback =
                    getDisableMagnificationEndRunnableLocked(displayId);
            if (animationCallback != null) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private void setCurrentMagnificationModeAndSwitchDelegate(int displayId, int mode) {
        mCurrentMagnificationModeArray.put(displayId, mode);
        assignMagnificationWindowManagerDelegateByMode(displayId, mode);
    }

    @GuardedBy("mLock")
    private void assignMagnificationWindowManagerDelegateByMode(int displayId, int mode) {
        if (mode == ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN) {
            mAccessibilityCallbacksDelegateArray.put(displayId,
                    getFullScreenMagnificationController());
        } else if (mode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW) {
            mAccessibilityCallbacksDelegateArray.put(
                    displayId, getMagnificationConnectionManager());
        } else {
            mAccessibilityCallbacksDelegateArray.delete(displayId);
        }
    }

    @Override
    public void onRectangleOnScreenRequested(int displayId, int left, int top, int right,
            int bottom) {
        WindowManagerInternal.AccessibilityControllerInternal.UiChangesForAccessibilityCallbacks
                delegate;
        synchronized (mLock) {
            delegate = mAccessibilityCallbacksDelegateArray.get(displayId);
        }
        if (delegate != null) {
            delegate.onRectangleOnScreenRequested(displayId, left, top, right, bottom);
        }
    }

    @Override
    public void onRequestMagnificationSpec(int displayId, int serviceId) {
        final MagnificationConnectionManager magnificationConnectionManager;
        synchronized (mLock) {
            updateMagnificationUIControls(displayId, ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
            magnificationConnectionManager = mMagnificationConnectionManager;
        }
        if (magnificationConnectionManager != null) {
            mMagnificationConnectionManager.disableWindowMagnification(displayId, false);
        }
    }

    @Override
    public void onWindowMagnificationActivationState(int displayId, boolean activated) {
        if (activated) {
            synchronized (mLock) {
                mWindowModeEnabledTimeArray.put(displayId, mSystemClock.uptimeMillis());
                setCurrentMagnificationModeAndSwitchDelegate(displayId,
                        ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
                mLastMagnificationActivatedModeArray.put(displayId,
                        ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
            }
            logMagnificationModeWithImeOnIfNeeded(displayId);
            disableFullScreenMagnificationIfNeeded(displayId);
        } else {
            long duration;
            float scale;
            synchronized (mLock) {
                setCurrentMagnificationModeAndSwitchDelegate(displayId,
                        ACCESSIBILITY_MAGNIFICATION_MODE_NONE);
                duration = mSystemClock.uptimeMillis() - mWindowModeEnabledTimeArray.get(displayId);
                scale = mMagnificationConnectionManager.getLastActivatedScale(displayId);
            }
            logMagnificationUsageState(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW, duration, scale);
        }
        updateMagnificationUIControls(displayId, ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
    }

    @Override
    public void onChangeMagnificationMode(int displayId, int magnificationMode) {
        mAms.changeMagnificationMode(displayId, magnificationMode);
    }

    @Override
    public void onSourceBoundsChanged(int displayId, Rect bounds) {
        if (shouldNotifyMagnificationChange(displayId, MAGNIFICATION_MODE_WINDOW)) {
            // notify sysui the magnification scale changed on window magnifier
            mMagnificationConnectionManager.onUserMagnificationScaleChanged(
                    mUserId, displayId, getMagnificationConnectionManager().getScale(displayId));

            final MagnificationConfig config = new MagnificationConfig.Builder()
                    .setMode(MAGNIFICATION_MODE_WINDOW)
                    .setActivated(
                            getMagnificationConnectionManager().isWindowMagnifierEnabled(displayId))
                    .setScale(getMagnificationConnectionManager().getScale(displayId))
                    .setCenterX(bounds.exactCenterX())
                    .setCenterY(bounds.exactCenterY()).build();
            mAms.notifyMagnificationChanged(displayId, new Region(bounds), config);
        }
    }

    @Override
    public void onFullScreenMagnificationChanged(int displayId, @NonNull Region region,
            @NonNull MagnificationConfig config) {
        if (shouldNotifyMagnificationChange(displayId, MAGNIFICATION_MODE_FULLSCREEN)) {
            // notify sysui the magnification scale changed on fullscreen magnifier
            mMagnificationConnectionManager.onUserMagnificationScaleChanged(
                    mUserId, displayId, config.getScale());

            mAms.notifyMagnificationChanged(displayId, region, config);
        }
    }

    /**
     * Should notify magnification change for the given display under the conditions below
     *
     * <ol>
     *   <li> 1. No mode transitioning and the change mode is active. </li>
     *   <li> 2. No mode transitioning and all the modes are inactive. </li>
     *   <li> 3. It is mode transitioning and the change mode is the transition mode. </li>
     * </ol>
     *
     * @param displayId  The logical display id
     * @param changeMode The mode that has magnification spec change
     */
    private boolean shouldNotifyMagnificationChange(int displayId, int changeMode) {
        synchronized (mLock) {
            final boolean fullScreenActivated = mFullScreenMagnificationController != null
                    && mFullScreenMagnificationController.isActivated(displayId);
            final boolean windowEnabled = mMagnificationConnectionManager != null
                    && mMagnificationConnectionManager.isWindowMagnifierEnabled(displayId);
            final Integer transitionMode = mTransitionModes.get(displayId);
            if (((changeMode == MAGNIFICATION_MODE_FULLSCREEN && fullScreenActivated)
                    || (changeMode == MAGNIFICATION_MODE_WINDOW && windowEnabled))
                    && (transitionMode == null)) {
                return true;
            }
            if ((!fullScreenActivated && !windowEnabled)
                    && (transitionMode == null)) {
                return true;
            }
            if (transitionMode != null && changeMode == transitionMode) {
                return true;
            }
        }
        return false;
    }

    private void disableFullScreenMagnificationIfNeeded(int displayId) {
        final FullScreenMagnificationController fullScreenMagnificationController =
                getFullScreenMagnificationController();
        // Internal request may be for transition, so we just need to check external request.
        final boolean isMagnifyByExternalRequest =
                fullScreenMagnificationController.getIdOfLastServiceToMagnify(displayId) > 0;
        if (isMagnifyByExternalRequest || isActivated(displayId,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN)) {
            fullScreenMagnificationController.reset(displayId, false);
        }
    }

    @Override
    public void onFullScreenMagnificationActivationState(int displayId, boolean activated) {
        getMagnificationConnectionManager()
                .onFullscreenMagnificationActivationChanged(displayId, activated);

        if (activated) {
            synchronized (mLock) {
                mFullScreenModeEnabledTimeArray.put(displayId, mSystemClock.uptimeMillis());
                setCurrentMagnificationModeAndSwitchDelegate(displayId,
                        ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
                mLastMagnificationActivatedModeArray.put(displayId,
                        ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
            }
            logMagnificationModeWithImeOnIfNeeded(displayId);
            disableWindowMagnificationIfNeeded(displayId);
        } else {
            long duration;
            float scale;
            synchronized (mLock) {
                setCurrentMagnificationModeAndSwitchDelegate(displayId,
                        ACCESSIBILITY_MAGNIFICATION_MODE_NONE);
                duration = mSystemClock.uptimeMillis()
                        - mFullScreenModeEnabledTimeArray.get(displayId);
                scale = mFullScreenMagnificationController.getLastActivatedScale(displayId);
            }
            logMagnificationUsageState(
                    ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN, duration, scale);
        }
        updateMagnificationUIControls(displayId, ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    private void disableWindowMagnificationIfNeeded(int displayId) {
        final MagnificationConnectionManager magnificationConnectionManager =
                getMagnificationConnectionManager();
        if (isActivated(displayId, ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW)) {
            magnificationConnectionManager.disableWindowMagnification(displayId, false);
        }
    }

    @Override
    public void onImeWindowVisibilityChanged(int displayId, boolean shown) {
        synchronized (mLock) {
            mIsImeVisibleArray.put(displayId, shown);
        }
        getMagnificationConnectionManager().onImeWindowVisibilityChanged(displayId, shown);
        logMagnificationModeWithImeOnIfNeeded(displayId);
    }

    /**
     * Returns the last activated magnification mode. If there is no activated magnifier before, it
     * returns fullscreen mode by default.
     */
    public int getLastMagnificationActivatedMode(int displayId) {
        synchronized (mLock) {
            return mLastMagnificationActivatedModeArray.get(displayId,
                    ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        }
    }

    /**
     * Wrapper method of logging the magnification activated mode and its duration of the usage
     * when the magnification is disabled.
     *
     * @param mode The activated magnification mode.
     * @param duration The duration in milliseconds during the magnification is activated.
     * @param scale The last magnification scale for the activation
     */
    @VisibleForTesting
    public void logMagnificationUsageState(int mode, long duration, float scale) {
        AccessibilityStatsLogUtils.logMagnificationUsageState(mode, duration, scale);
    }

    /**
     * Wrapper method of logging the activated mode of the magnification when the IME window
     * is shown on the screen.
     *
     * @param mode The activated magnification mode.
     */
    @VisibleForTesting
    public void logMagnificationModeWithIme(int mode) {
        AccessibilityStatsLogUtils.logMagnificationModeWithImeOn(mode);
    }

    /**
     * Updates the active user ID of {@link FullScreenMagnificationController} and {@link
     * MagnificationConnectionManager}.
     *
     * @param userId the currently active user ID
     */
    public void updateUserIdIfNeeded(int userId) {
        if (mUserId == userId) {
            return;
        }
        mUserId = userId;
        final FullScreenMagnificationController fullMagnificationController;
        final MagnificationConnectionManager magnificationConnectionManager;
        synchronized (mLock) {
            fullMagnificationController = mFullScreenMagnificationController;
            magnificationConnectionManager = mMagnificationConnectionManager;
            mAccessibilityCallbacksDelegateArray.clear();
            mCurrentMagnificationModeArray.clear();
            mLastMagnificationActivatedModeArray.clear();
            mIsImeVisibleArray.clear();
        }

        mScaleProvider.onUserChanged(userId);
        if (fullMagnificationController != null) {
            fullMagnificationController.resetAllIfNeeded(false);
        }
        if (magnificationConnectionManager != null) {
            magnificationConnectionManager.disableAllWindowMagnifiers();
        }
    }

    /**
     * Removes the magnification instance with given id.
     *
     * @param displayId The logical display id.
     */
    public void onDisplayRemoved(int displayId) {
        synchronized (mLock) {
            if (mFullScreenMagnificationController != null) {
                mFullScreenMagnificationController.onDisplayRemoved(displayId);
            }
            if (mMagnificationConnectionManager != null) {
                mMagnificationConnectionManager.onDisplayRemoved(displayId);
            }
            mAccessibilityCallbacksDelegateArray.delete(displayId);
            mCurrentMagnificationModeArray.delete(displayId);
            mLastMagnificationActivatedModeArray.delete(displayId);
            mIsImeVisibleArray.delete(displayId);
        }
        mScaleProvider.onDisplayRemoved(displayId);
    }

    /**
     * Called when the given user is removed.
     */
    public void onUserRemoved(int userId) {
        mScaleProvider.onUserRemoved(userId);
    }

    public void setMagnificationCapabilities(int capabilities) {
        mMagnificationCapabilities = capabilities;
    }

    /**
     * Called when the following typing focus feature is switched.
     *
     * @param enabled Enable the following typing focus feature
     */
    public void setMagnificationFollowTypingEnabled(boolean enabled) {
        getMagnificationConnectionManager().setMagnificationFollowTypingEnabled(enabled);
        getFullScreenMagnificationController().setMagnificationFollowTypingEnabled(enabled);
    }

    /**
     * Called when the always on magnification feature is switched.
     *
     * @param enabled Enable the always on magnification feature
     */
    public void setAlwaysOnMagnificationEnabled(boolean enabled) {
        getFullScreenMagnificationController().setAlwaysOnMagnificationEnabled(enabled);
    }

    public boolean isAlwaysOnMagnificationFeatureFlagEnabled() {
        return mAlwaysOnMagnificationFeatureFlag.isFeatureFlagEnabled();
    }

    private DisableMagnificationCallback getDisableMagnificationEndRunnableLocked(
            int displayId) {
        return mMagnificationEndRunnableSparseArray.get(displayId);
    }

    private void setDisableMagnificationCallbackLocked(int displayId,
            @Nullable DisableMagnificationCallback callback) {
        mMagnificationEndRunnableSparseArray.put(displayId, callback);
        if (DEBUG) {
            Slog.d(TAG, "setDisableMagnificationCallbackLocked displayId = " + displayId
                    + ", callback = " + callback);
        }
    }

    private void logMagnificationModeWithImeOnIfNeeded(int displayId) {
        final int currentActivateMode;

        synchronized (mLock) {
            currentActivateMode = mCurrentMagnificationModeArray.get(displayId,
                    ACCESSIBILITY_MAGNIFICATION_MODE_NONE);
            if (!mIsImeVisibleArray.get(displayId, false)
                    || currentActivateMode == ACCESSIBILITY_MAGNIFICATION_MODE_NONE) {
                return;
            }
        }
        logMagnificationModeWithIme(currentActivateMode);
    }

    /**
     * Getter of {@link FullScreenMagnificationController}.
     *
     * @return {@link FullScreenMagnificationController}.
     */
    public FullScreenMagnificationController getFullScreenMagnificationController() {
        synchronized (mLock) {
            if (mFullScreenMagnificationController == null) {
                mFullScreenMagnificationController = new FullScreenMagnificationController(
                        mContext,
                        mAms.getTraceManager(),
                        mLock,
                        this,
                        mScaleProvider,
                        mBackgroundExecutor,
                        () -> isMagnificationSystemUIConnectionReady()
                );
            }
        }
        return mFullScreenMagnificationController;
    }

    private boolean isMagnificationSystemUIConnectionReady() {
        return isMagnificationConnectionManagerInitialized()
                && getMagnificationConnectionManager().waitConnectionWithTimeoutIfNeeded();
    }

    /**
     * Is {@link #mFullScreenMagnificationController} is initialized.
     * @return {code true} if {@link #mFullScreenMagnificationController} is initialized.
     */
    public boolean isFullScreenMagnificationControllerInitialized() {
        synchronized (mLock) {
            return mFullScreenMagnificationController != null;
        }
    }

    /**
     * Getter of {@link MagnificationConnectionManager}.
     *
     * @return {@link MagnificationConnectionManager}.
     */
    public MagnificationConnectionManager getMagnificationConnectionManager() {
        synchronized (mLock) {
            if (mMagnificationConnectionManager == null) {
                mMagnificationConnectionManager = new MagnificationConnectionManager(mContext,
                        mLock, this, mAms.getTraceManager(),
                        mScaleProvider);
            }
            return mMagnificationConnectionManager;
        }
    }

    private boolean isMagnificationConnectionManagerInitialized() {
        synchronized (mLock) {
            return mMagnificationConnectionManager != null;
        }
    }

    private @Nullable PointF getCurrentMagnificationCenterLocked(int displayId, int targetMode) {
        if (targetMode == ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN) {
            if (mMagnificationConnectionManager == null
                    || !mMagnificationConnectionManager.isWindowMagnifierEnabled(displayId)) {
                return null;
            }
            mTempPoint.set(mMagnificationConnectionManager.getCenterX(displayId),
                    mMagnificationConnectionManager.getCenterY(displayId));
        } else {
            if (mFullScreenMagnificationController == null
                    || !mFullScreenMagnificationController.isActivated(displayId)) {
                return null;
            }
            mTempPoint.set(mFullScreenMagnificationController.getCenterX(displayId),
                    mFullScreenMagnificationController.getCenterY(displayId));
        }
        return mTempPoint;
    }

    /**
     * Return {@code true} if the specified magnification mode on the given display is activated
     * or not.
     *
     * @param displayId The logical displayId.
     * @param mode It's either ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN or
     * ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW.
     */
    public boolean isActivated(int displayId, int mode) {
        boolean isActivated = false;
        if (mode == ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN) {
            synchronized (mLock) {
                if (mFullScreenMagnificationController == null) {
                    return false;
                }
                isActivated = mFullScreenMagnificationController.isActivated(displayId);
            }
        } else if (mode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW) {
            synchronized (mLock) {
                if (mMagnificationConnectionManager == null) {
                    return false;
                }
                isActivated = mMagnificationConnectionManager.isWindowMagnifierEnabled(displayId);
            }
        }
        return isActivated;
    }

    /**
     * Scales the magnifier on the given display one step in/out based on the direction param.
     *
     * @param displayId The logical display id.
     * @param direction Whether the scale should be zoomed in or out.
     */
    private void scaleMagnificationByStep(int displayId, @ZoomDirection int direction) {
        if (getFullScreenMagnificationController().isActivated(displayId)) {
            final float magnificationScale = getFullScreenMagnificationController().getScale(
                    displayId);
            final float nextMagnificationScale = mScaleStepProvider.nextScaleStep(
                    magnificationScale, direction);
            getFullScreenMagnificationController().setScaleAndCenter(displayId,
                    nextMagnificationScale,
                    Float.NaN, Float.NaN, true, MAGNIFICATION_GESTURE_HANDLER_ID);
        }

        if (getMagnificationConnectionManager().isWindowMagnifierEnabled(displayId)) {
            final float magnificationScale = getMagnificationConnectionManager().getScale(
                    displayId);
            final float nextMagnificationScale = mScaleStepProvider.nextScaleStep(
                    magnificationScale, direction);
            getMagnificationConnectionManager().setScale(displayId, nextMagnificationScale);
        }
    }

    /**
     * Pans the magnifier on the given display one step left/right/up/down based on the direction
     * param.
     *
     * @param displayId The logical display id.
     * @param directions The directions to pan, indexed by {@code PanDirection}. If two or more
     *                   are active, panning may be diagonal.
     */
    private void panMagnificationByStep(int displayId, boolean[] directions) {
        if (directions.length != 4) {
            Slog.d(TAG, "Invalid number of panning directions");
            return;
        }
        final boolean fullscreenActivated =
                getFullScreenMagnificationController().isActivated(displayId);
        final boolean windowActivated =
                getMagnificationConnectionManager().isWindowMagnifierEnabled(displayId);
        if (!fullscreenActivated && !windowActivated) {
            return;
        }

        int numDirections = (directions[PAN_DIRECTION_LEFT] ? 1 : 0)
                + (directions[PAN_DIRECTION_RIGHT] ? 1 : 0)
                + (directions[PAN_DIRECTION_UP] ? 1 : 0)
                + (directions[PAN_DIRECTION_DOWN] ? 1 : 0);
        if (numDirections == 0) {
            return;
        }

        final float scale = fullscreenActivated
                ? getFullScreenMagnificationController().getScale(displayId)
                        : getMagnificationConnectionManager().getScale(displayId);
        float step = mPanStepProvider.nextPanStep(scale, displayId);

        // If the user is trying to pan diagonally (2 directions), divide by the sqrt(2)
        // so that the apparent step length (the radius of the step) is the same as
        // panning in just one direction.
        // Note that if numDirections is 3 or 4, opposite directions will cancel and
        // there's no need to rescale {@code step}.
        if (numDirections == 2) {
            step /= sqrt(2);
        }

        // If two directions cancel out, they will be added and subtracted below for net change 0.
        // This makes the logic simpler than removing out opposite directions manually.
        float offsetX = 0;
        float offsetY = 0;
        if (directions[PAN_DIRECTION_LEFT]) {
            offsetX -= step;
        }
        if (directions[PAN_DIRECTION_RIGHT]) {
            offsetX += step;
        }
        if (directions[PAN_DIRECTION_UP]) {
            offsetY -= step;
        }
        if (directions[PAN_DIRECTION_DOWN]) {
            offsetY += step;
        }

        if (fullscreenActivated) {
            final float centerX = getFullScreenMagnificationController().getCenterX(displayId);
            final float centerY = getFullScreenMagnificationController().getCenterY(displayId);
            getFullScreenMagnificationController().setScaleAndCenter(displayId, scale,
                    centerX + offsetX, centerY + offsetY, true, MAGNIFICATION_GESTURE_HANDLER_ID);
        } else {
            getMagnificationConnectionManager().moveWindowMagnification(displayId, offsetX,
                    offsetY);
        }

        mLastPannedTime = mSystemClock.uptimeMillis();
    }

    private final class DisableMagnificationCallback implements
            MagnificationAnimationCallback {
        private final TransitionCallBack mTransitionCallBack;
        private boolean mExpired = false;
        private final int mDisplayId;
        // The mode the in-progress animation is going to.
        private final int mTargetMode;
        // The mode the in-progress animation is going from.
        private final int mCurrentMode;
        private final float mCurrentScale;
        private final PointF mCurrentCenter = new PointF();
        private final boolean mAnimate;

        DisableMagnificationCallback(@Nullable TransitionCallBack transitionCallBack,
                int displayId, int targetMode, float scale, PointF currentCenter, boolean animate) {
            mTransitionCallBack = transitionCallBack;
            mDisplayId = displayId;
            mTargetMode = targetMode;
            mCurrentMode = mTargetMode ^ ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
            mCurrentScale = scale;
            mCurrentCenter.set(currentCenter);
            mAnimate = animate;
        }

        @Override
        public void onResult(boolean success) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onResult success = " + success);
                }
                if (mExpired) {
                    return;
                }
                setExpiredAndRemoveFromListLocked();
                setTransitionState(mDisplayId, null);

                if (success) {
                    adjustCurrentCenterIfNeededLocked();
                    applyMagnificationModeLocked(mTargetMode);
                } else {
                    // Notify magnification change if magnification is inactive when the
                    // transition is failed. This is for the failed transition from
                    // full-screen to window mode. Disable magnification callback helps to send
                    // magnification inactive change since FullScreenMagnificationController
                    // would not notify magnification change if the spec is not changed.
                    final FullScreenMagnificationController screenMagnificationController =
                            getFullScreenMagnificationController();
                    if (mCurrentMode == ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
                            && !screenMagnificationController.isActivated(mDisplayId)) {
                        MagnificationConfig.Builder configBuilder =
                                new MagnificationConfig.Builder();
                        Region region = new Region();
                        configBuilder.setMode(MAGNIFICATION_MODE_FULLSCREEN)
                                .setActivated(screenMagnificationController.isActivated(mDisplayId))
                                .setScale(screenMagnificationController.getScale(mDisplayId))
                                .setCenterX(screenMagnificationController.getCenterX(mDisplayId))
                                .setCenterY(screenMagnificationController.getCenterY(mDisplayId));
                        screenMagnificationController.getMagnificationRegion(mDisplayId,
                                region);
                        mAms.notifyMagnificationChanged(mDisplayId, region, configBuilder.build());
                    }
                }
                updateMagnificationUIControls(mDisplayId, mTargetMode);
                if (mTransitionCallBack != null) {
                    mTransitionCallBack.onResult(mDisplayId, success);
                }
            }
        }

        private void adjustCurrentCenterIfNeededLocked() {
            if (mTargetMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW) {
                return;
            }
            final Region outRegion = new Region();
            getFullScreenMagnificationController().getMagnificationRegion(mDisplayId, outRegion);
            if (outRegion.contains((int) mCurrentCenter.x, (int) mCurrentCenter.y)) {
                return;
            }
            final Rect bounds = outRegion.getBounds();
            mCurrentCenter.set(bounds.exactCenterX(), bounds.exactCenterY());
        }

        void restoreToCurrentMagnificationMode() {
            synchronized (mLock) {
                if (mExpired) {
                    return;
                }
                setExpiredAndRemoveFromListLocked();
                setTransitionState(mDisplayId, null);
                applyMagnificationModeLocked(mCurrentMode);
                updateMagnificationUIControls(mDisplayId, mCurrentMode);
                if (mTransitionCallBack != null) {
                    mTransitionCallBack.onResult(mDisplayId, true);
                }
            }
        }

        void setExpiredAndRemoveFromListLocked() {
            mExpired = true;
            setDisableMagnificationCallbackLocked(mDisplayId, null);
        }

        private void applyMagnificationModeLocked(int mode) {
            if (mode == ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN) {
                final FullScreenMagnificationController fullScreenMagnificationController =
                        getFullScreenMagnificationController();
                if (!fullScreenMagnificationController.isRegistered(mDisplayId)) {
                    fullScreenMagnificationController.register(mDisplayId);
                }
                fullScreenMagnificationController.setScaleAndCenter(mDisplayId, mCurrentScale,
                        mCurrentCenter.x, mCurrentCenter.y, mAnimate,
                        MAGNIFICATION_GESTURE_HANDLER_ID);
            } else {
                getMagnificationConnectionManager().enableWindowMagnification(mDisplayId,
                        mCurrentScale, mCurrentCenter.x,
                        mCurrentCenter.y, mAnimate ? STUB_ANIMATION_CALLBACK : null,
                        MAGNIFICATION_GESTURE_HANDLER_ID);
            }
        }
    }
}
