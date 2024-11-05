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

import static com.android.server.accessibility.AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID;

import android.accessibilityservice.MagnificationConfig;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.view.accessibility.MagnificationAnimationCallback;

import com.android.internal.accessibility.util.AccessibilityStatsLogUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.wm.WindowManagerInternal;
import com.android.window.flags.Flags;

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
        MagnificationGestureHandler.Callback,
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

    private final Executor mBackgroundExecutor;

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

    public MagnificationController(AccessibilityManagerService ams, Object lock,
            Context context, MagnificationScaleProvider scaleProvider,
            Executor backgroundExecutor) {
        mAms = ams;
        mLock = lock;
        mContext = context;
        mScaleProvider = scaleProvider;
        mBackgroundExecutor = backgroundExecutor;
        LocalServices.getService(WindowManagerInternal.class)
                .getAccessibilityController().setUiChangesForAccessibilityCallbacks(this);
        mSupportWindowMagnification = context.getPackageManager().hasSystemFeature(
                FEATURE_WINDOW_MAGNIFICATION);

        mAlwaysOnMagnificationFeatureFlag = new AlwaysOnMagnificationFeatureFlag(context);
        mAlwaysOnMagnificationFeatureFlag.addOnChangedListener(
                mBackgroundExecutor, mAms::updateAlwaysOnMagnification);
    }

    @VisibleForTesting
    public MagnificationController(AccessibilityManagerService ams, Object lock,
            Context context, FullScreenMagnificationController fullScreenMagnificationController,
            MagnificationConnectionManager magnificationConnectionManager,
            MagnificationScaleProvider scaleProvider, Executor backgroundExecutor) {
        this(ams, lock, context, scaleProvider, backgroundExecutor);
        mFullScreenMagnificationController = fullScreenMagnificationController;
        mMagnificationConnectionManager = magnificationConnectionManager;
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
                mWindowModeEnabledTimeArray.put(displayId, SystemClock.uptimeMillis());
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
                duration = SystemClock.uptimeMillis() - mWindowModeEnabledTimeArray.get(displayId);
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
        if (Flags.alwaysDrawMagnificationFullscreenBorder()) {
            getMagnificationConnectionManager()
                    .onFullscreenMagnificationActivationChanged(displayId, activated);
        }

        if (activated) {
            synchronized (mLock) {
                mFullScreenModeEnabledTimeArray.put(displayId, SystemClock.uptimeMillis());
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
                duration = SystemClock.uptimeMillis()
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
