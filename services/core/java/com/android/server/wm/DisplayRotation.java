/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.util.RotationUtils.deltaRotation;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ANIM;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_OPEN;
import static com.android.server.wm.DisplayRotationProto.FIXED_TO_USER_ROTATION_MODE;
import static com.android.server.wm.DisplayRotationProto.FROZEN_TO_USER_ROTATION;
import static com.android.server.wm.DisplayRotationProto.IS_FIXED_TO_USER_ROTATION;
import static com.android.server.wm.DisplayRotationProto.LAST_ORIENTATION;
import static com.android.server.wm.DisplayRotationProto.ROTATION;
import static com.android.server.wm.DisplayRotationProto.USER_ROTATION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.WINDOWS_FREEZING_SCREENS_ACTIVE;
import static com.android.server.wm.WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION;

import android.annotation.AnimRes;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.power.Boost;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayAddress;
import android.view.IWindowManager;
import android.view.Surface;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.Set;

/**
 * Defines the mapping between orientation and rotation of a display.
 * Non-public methods are assumed to run inside WM lock.
 */
public class DisplayRotation {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayRotation" : TAG_WM;

    private static class RotationAnimationPair {
        @AnimRes
        int mEnter;
        @AnimRes
        int mExit;
    }

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final DisplayPolicy mDisplayPolicy;
    private final DisplayWindowSettings mDisplayWindowSettings;
    private final Context mContext;
    private final Object mLock;
    @Nullable
    private final DisplayRotationImmersiveAppCompatPolicy mCompatPolicyForImmersiveApps;

    public final boolean isDefaultDisplay;
    private final boolean mSupportAutoRotation;
    private final int mLidOpenRotation;
    private final int mCarDockRotation;
    private final int mDeskDockRotation;
    private final int mUndockedHdmiRotation;
    private final RotationAnimationPair mTmpRotationAnim = new RotationAnimationPair();
    private final RotationHistory mRotationHistory = new RotationHistory();

    private OrientationListener mOrientationListener;
    private StatusBarManagerInternal mStatusBarManagerInternal;
    private SettingsObserver mSettingsObserver;
    @Nullable
    private FoldController mFoldController;

    @ScreenOrientation
    private int mCurrentAppOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    /**
     * Last applied orientation of the display.
     *
     * @see #updateOrientationFromApp
     */
    @ScreenOrientation
    private int mLastOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    /**
     * Current rotation of the display.
     *
     * @see #updateRotationUnchecked
     */
    @Surface.Rotation
    private int mRotation;

    @VisibleForTesting
    int mLandscapeRotation;  // default landscape
    @VisibleForTesting
    int mSeascapeRotation;   // "other" landscape, 180 degrees from mLandscapeRotation
    @VisibleForTesting
    int mPortraitRotation;   // default portrait
    @VisibleForTesting
    int mUpsideDownRotation; // "other" portrait

    int mLastSensorRotation = -1;

    private boolean mAllowSeamlessRotationDespiteNavBarMoving;

    private int mDeferredRotationPauseCount;

    /**
     * A count of the windows which are 'seamlessly rotated', e.g. a surface at an old orientation
     * is being transformed. We freeze orientation updates while any windows are seamlessly rotated,
     * so we need to track when this hits zero so we can apply deferred orientation updates.
     */
    private int mSeamlessRotationCount;

    /**
     * True in the interval from starting seamless rotation until the last rotated window draws in
     * the new orientation.
     */
    private boolean mRotatingSeamlessly;

    /**
     * Behavior of rotation suggestions.
     *
     * @see Settings.Secure#SHOW_ROTATION_SUGGESTIONS
     */
    private int mShowRotationSuggestions;

    private static final int ALLOW_ALL_ROTATIONS_UNDEFINED = -1;
    private static final int ALLOW_ALL_ROTATIONS_DISABLED = 0;
    private static final int ALLOW_ALL_ROTATIONS_ENABLED = 1;

    @IntDef({ ALLOW_ALL_ROTATIONS_UNDEFINED, ALLOW_ALL_ROTATIONS_DISABLED,
            ALLOW_ALL_ROTATIONS_ENABLED })
    @Retention(RetentionPolicy.SOURCE)
    private @interface AllowAllRotations {}

    /**
     * Whether to allow the screen to rotate to all rotations (including 180 degree) according to
     * the sensor even when the current orientation is not
     * {@link ActivityInfo#SCREEN_ORIENTATION_FULL_SENSOR} or
     * {@link ActivityInfo#SCREEN_ORIENTATION_FULL_USER}.
     */
    @AllowAllRotations
    private int mAllowAllRotations = ALLOW_ALL_ROTATIONS_UNDEFINED;

    @WindowManagerPolicy.UserRotationMode
    private int mUserRotationMode = WindowManagerPolicy.USER_ROTATION_FREE;

    @Surface.Rotation
    private int mUserRotation = Surface.ROTATION_0;

    private static final int CAMERA_ROTATION_DISABLED = 0;
    private static final int CAMERA_ROTATION_ENABLED = 1;
    private int mCameraRotationMode = CAMERA_ROTATION_DISABLED;

    /**
     * Flag that indicates this is a display that may run better when fixed to user rotation.
     */
    private boolean mDefaultFixedToUserRotation;

    /**
     * A flag to indicate if the display rotation should be fixed to user specified rotation
     * regardless of all other states (including app requested orientation). {@code true} the
     * display rotation should be fixed to user specified rotation, {@code false} otherwise.
     */
    private int mFixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;

    private int mDemoHdmiRotation;
    private int mDemoRotation;
    private boolean mDemoHdmiRotationLock;
    private boolean mDemoRotationLock;

    DisplayRotation(WindowManagerService service, DisplayContent displayContent,
            DisplayAddress displayAddress) {
        this(service, displayContent, displayAddress, displayContent.getDisplayPolicy(),
                service.mDisplayWindowSettings, service.mContext, service.getWindowManagerLock());
    }

    @VisibleForTesting
    DisplayRotation(WindowManagerService service, DisplayContent displayContent,
            DisplayAddress displayAddress, DisplayPolicy displayPolicy,
            DisplayWindowSettings displayWindowSettings, Context context, Object lock) {
        mService = service;
        mDisplayContent = displayContent;
        mDisplayPolicy = displayPolicy;
        mDisplayWindowSettings = displayWindowSettings;
        mContext = context;
        mLock = lock;
        isDefaultDisplay = displayContent.isDefaultDisplay;
        mCompatPolicyForImmersiveApps = initImmersiveAppCompatPolicy(service, displayContent);

        mSupportAutoRotation =
                mContext.getResources().getBoolean(R.bool.config_supportAutoRotation);
        mLidOpenRotation = readRotation(R.integer.config_lidOpenRotation);
        mCarDockRotation = readRotation(R.integer.config_carDockRotation);
        mDeskDockRotation = readRotation(R.integer.config_deskDockRotation);
        mUndockedHdmiRotation = readRotation(R.integer.config_undockedHdmiRotation);

        mRotation = readDefaultDisplayRotation(displayAddress);

        if (isDefaultDisplay) {
            final Handler uiHandler = UiThread.getHandler();
            mOrientationListener = new OrientationListener(mContext, uiHandler);
            mOrientationListener.setCurrentRotation(mRotation);
            mSettingsObserver = new SettingsObserver(uiHandler);
            mSettingsObserver.observe();
            if (mSupportAutoRotation && mContext.getResources().getBoolean(
                    R.bool.config_windowManagerHalfFoldAutoRotateOverride)) {
                mFoldController = new FoldController();
            }
        }
    }

    @VisibleForTesting
    @Nullable
    DisplayRotationImmersiveAppCompatPolicy initImmersiveAppCompatPolicy(
                WindowManagerService service, DisplayContent displayContent) {
        return DisplayRotationImmersiveAppCompatPolicy.createIfNeeded(
                service.mLetterboxConfiguration, this, displayContent);
    }

    // Change the default value to the value specified in the sysprop
    // ro.bootanim.set_orientation_<display_id>. Four values are supported: ORIENTATION_0,
    // ORIENTATION_90, ORIENTATION_180 and ORIENTATION_270.
    // If the value isn't specified or is ORIENTATION_0, nothing will be changed.
    // This is needed to support having default orientation different from the natural
    // device orientation. For example, on tablets that may want to keep natural orientation
    // portrait for applications compatibility but have landscape orientation as a default choice
    // from the UX perspective.
    @Surface.Rotation
    private int readDefaultDisplayRotation(DisplayAddress displayAddress) {
        if (!(displayAddress instanceof DisplayAddress.Physical)) {
            return Surface.ROTATION_0;
        }
        final DisplayAddress.Physical physicalAddress = (DisplayAddress.Physical) displayAddress;
        String syspropValue = SystemProperties.get(
                "ro.bootanim.set_orientation_" + physicalAddress.getPhysicalDisplayId(),
                "ORIENTATION_0");
        if (syspropValue.equals("ORIENTATION_90")) {
            return Surface.ROTATION_90;
        } else if (syspropValue.equals("ORIENTATION_180")) {
            return Surface.ROTATION_180;
        } else if (syspropValue.equals("ORIENTATION_270")) {
            return Surface.ROTATION_270;
        }
        return Surface.ROTATION_0;
    }

    private int readRotation(int resID) {
        try {
            final int rotation = mContext.getResources().getInteger(resID);
            switch (rotation) {
                case 0:
                    return Surface.ROTATION_0;
                case 90:
                    return Surface.ROTATION_90;
                case 180:
                    return Surface.ROTATION_180;
                case 270:
                    return Surface.ROTATION_270;
            }
        } catch (Resources.NotFoundException e) {
            // fall through
        }
        return -1;
    }

    /**
     * Updates the configuration which may have different values depending on current user, e.g.
     * runtime resource overlay.
     */
    void updateUserDependentConfiguration(Resources currentUserRes) {
        mAllowSeamlessRotationDespiteNavBarMoving =
                currentUserRes.getBoolean(R.bool.config_allowSeamlessRotationDespiteNavBarMoving);
    }

    void configure(int width, int height) {
        final Resources res = mContext.getResources();
        if (width > height) {
            mLandscapeRotation = Surface.ROTATION_0;
            mSeascapeRotation = Surface.ROTATION_180;
            if (res.getBoolean(R.bool.config_reverseDefaultRotation)) {
                mPortraitRotation = Surface.ROTATION_90;
                mUpsideDownRotation = Surface.ROTATION_270;
            } else {
                mPortraitRotation = Surface.ROTATION_270;
                mUpsideDownRotation = Surface.ROTATION_90;
            }
        } else {
            mPortraitRotation = Surface.ROTATION_0;
            mUpsideDownRotation = Surface.ROTATION_180;
            if (res.getBoolean(R.bool.config_reverseDefaultRotation)) {
                mLandscapeRotation = Surface.ROTATION_270;
                mSeascapeRotation = Surface.ROTATION_90;
            } else {
                mLandscapeRotation = Surface.ROTATION_90;
                mSeascapeRotation = Surface.ROTATION_270;
            }
        }

        // For demo purposes, allow the rotation of the HDMI display to be controlled.
        // By default, HDMI locks rotation to landscape.
        if ("portrait".equals(SystemProperties.get("persist.demo.hdmirotation"))) {
            mDemoHdmiRotation = mPortraitRotation;
        } else {
            mDemoHdmiRotation = mLandscapeRotation;
        }
        mDemoHdmiRotationLock = SystemProperties.getBoolean("persist.demo.hdmirotationlock", false);

        // For demo purposes, allow the rotation of the remote display to be controlled.
        // By default, remote display locks rotation to landscape.
        if ("portrait".equals(SystemProperties.get("persist.demo.remoterotation"))) {
            mDemoRotation = mPortraitRotation;
        } else {
            mDemoRotation = mLandscapeRotation;
        }
        mDemoRotationLock = SystemProperties.getBoolean("persist.demo.rotationlock", false);

        // It's physically impossible to rotate the car's screen.
        final boolean isCar = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE);
        // It's also not likely to rotate a TV screen.
        final boolean isTv = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);
        mDefaultFixedToUserRotation =
                (isCar || isTv || mService.mIsPc || mDisplayContent.forceDesktopMode())
                // For debug purposes the next line turns this feature off with:
                // $ adb shell setprop config.override_forced_orient true
                // $ adb shell wm size reset
                && !"true".equals(SystemProperties.get("config.override_forced_orient"));
    }

    void applyCurrentRotation(@Surface.Rotation int rotation) {
        mRotationHistory.addRecord(this, rotation);
        if (mOrientationListener != null) {
            mOrientationListener.setCurrentRotation(rotation);
        }
    }

    @VisibleForTesting
    void setRotation(@Surface.Rotation int rotation) {
        mRotation = rotation;
    }

    @Surface.Rotation
    int getRotation() {
        return mRotation;
    }

    @ScreenOrientation
    int getLastOrientation() {
        return mLastOrientation;
    }

    boolean updateOrientation(@ScreenOrientation int newOrientation, boolean forceUpdate) {
        if (newOrientation == mLastOrientation && !forceUpdate) {
            return false;
        }
        mLastOrientation = newOrientation;
        if (newOrientation != mCurrentAppOrientation) {
            mCurrentAppOrientation = newOrientation;
            if (isDefaultDisplay) {
                updateOrientationListenerLw();
            }
        }
        return updateRotationUnchecked(forceUpdate);
    }

    /**
     * Update rotation of the display and send configuration if the rotation is changed.
     *
     * @return {@code true} if the rotation has been changed and the new config is sent.
     */
    boolean updateRotationAndSendNewConfigIfChanged() {
        final boolean changed = updateRotationUnchecked(false /* forceUpdate */);
        if (changed) {
            mDisplayContent.sendNewConfiguration();
        }
        return changed;
    }

    /**
     * Update rotation with an option to force the update. This updates the container's perception
     * of rotation and, depending on the top activities, will freeze the screen or start seamless
     * rotation. The display itself gets rotated in {@link DisplayContent#applyRotationLocked}
     * during {@link DisplayContent#sendNewConfiguration}.
     *
     * @param forceUpdate Force the rotation update. Sometimes in WM we might skip updating
     *                    orientation because we're waiting for some rotation to finish or display
     *                    to unfreeze, which results in configuration of the previously visible
     *                    activity being applied to a newly visible one. Forcing the rotation
     *                    update allows to workaround this issue.
     * @return {@code true} if the rotation has been changed. In this case YOU MUST CALL
     *         {@link DisplayContent#sendNewConfiguration} TO COMPLETE THE ROTATION AND UNFREEZE
     *         THE SCREEN.
     */
    boolean updateRotationUnchecked(boolean forceUpdate) {
        final int displayId = mDisplayContent.getDisplayId();
        if (!forceUpdate) {
            if (mDeferredRotationPauseCount > 0) {
                // Rotation updates have been paused temporarily. Defer the update until updates
                // have been resumed.
                ProtoLog.v(WM_DEBUG_ORIENTATION, "Deferring rotation, rotation is paused.");
                return false;
            }

            if (mDisplayContent.inTransition()
                    && !mDisplayContent.mTransitionController.useShellTransitionsRotation()) {
                // Rotation updates cannot be performed while the previous rotation change animation
                // is still in progress. Skip this update. We will try updating again after the
                // animation is finished and the display is unfrozen.
                ProtoLog.v(WM_DEBUG_ORIENTATION, "Deferring rotation, animation in progress.");
                return false;
            }
            if (mService.mDisplayFrozen) {
                // Even if the screen rotation animation has finished (e.g. isAnimating returns
                // false), there is still some time where we haven't yet unfrozen the display. We
                // also need to abort rotation here.
                ProtoLog.v(WM_DEBUG_ORIENTATION,
                        "Deferring rotation, still finishing previous rotation");
                return false;
            }

            if (mDisplayContent.mFixedRotationTransitionListener.shouldDeferRotation()) {
                // Makes sure that after the transition is finished, updateOrientation() can see
                // the difference from the latest orientation source.
                mLastOrientation = SCREEN_ORIENTATION_UNSET;
                // During the recents animation, the closing app might still be considered on top.
                // In order to ignore its requested orientation to avoid a sensor led rotation (e.g
                // user rotating the device while the recents animation is running), we ignore
                // rotation update while the animation is running.
                return false;
            }
        }

        if (!mService.mDisplayEnabled) {
            // No point choosing a rotation if the display is not enabled.
            ProtoLog.v(WM_DEBUG_ORIENTATION, "Deferring rotation, display is not enabled.");
            return false;
        }

        final int oldRotation = mRotation;
        final int lastOrientation = mLastOrientation;
        int rotation = rotationForOrientation(lastOrientation, oldRotation);
        // Use the saved rotation for tabletop mode, if set.
        if (mFoldController != null && mFoldController.shouldRevertOverriddenRotation()) {
            int prevRotation = rotation;
            rotation = mFoldController.revertOverriddenRotation();
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "Reverting orientation. Rotating to %s from %s rather than %s.",
                    Surface.rotationToString(rotation),
                    Surface.rotationToString(oldRotation),
                    Surface.rotationToString(prevRotation));
        }
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Computed rotation=%s (%d) for display id=%d based on lastOrientation=%s (%d) and "
                        + "oldRotation=%s (%d)",
                Surface.rotationToString(rotation), rotation,
                displayId,
                ActivityInfo.screenOrientationToString(lastOrientation), lastOrientation,
                Surface.rotationToString(oldRotation), oldRotation);

        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Display id=%d selected orientation %s (%d), got rotation %s (%d)", displayId,
                ActivityInfo.screenOrientationToString(lastOrientation), lastOrientation,
                Surface.rotationToString(rotation), rotation);

        if (oldRotation == rotation) {
            // No change.
            return false;
        }

        // Preemptively cancel the running recents animation -- SysUI can't currently handle this
        // case properly since the signals it receives all happen post-change. We do this earlier
        // in the rotation flow, since DisplayContent.updateDisplayOverrideConfigurationLocked seems
        // to happen too late.
        final RecentsAnimationController recentsAnimationController =
                mService.getRecentsAnimationController();
        if (recentsAnimationController != null) {
            recentsAnimationController.cancelAnimationForDisplayChange();
        }

        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Display id=%d rotation changed to %d from %d, lastOrientation=%d",
                        displayId, rotation, oldRotation, lastOrientation);

        if (deltaRotation(oldRotation, rotation) != Surface.ROTATION_180) {
            mDisplayContent.mWaitingForConfig = true;
        }

        mRotation = rotation;

        mDisplayContent.setLayoutNeeded();

        if (mDisplayContent.mTransitionController.isShellTransitionsEnabled()) {
            final boolean wasCollecting = mDisplayContent.mTransitionController.isCollecting();
            final TransitionRequestInfo.DisplayChange change = wasCollecting ? null
                    : new TransitionRequestInfo.DisplayChange(mDisplayContent.getDisplayId(),
                            oldRotation, mRotation);

            mDisplayContent.requestChangeTransitionIfNeeded(
                    ActivityInfo.CONFIG_WINDOW_CONFIGURATION, change);
            if (wasCollecting) {
                // Use remote-rotation infra since the transition has already been requested
                // TODO(shell-transitions): Remove this once lifecycle management can cover all
                //                          rotation cases.
                startRemoteRotation(oldRotation, mRotation);
            }
            return true;
        }

        mService.mWindowsFreezingScreen = WINDOWS_FREEZING_SCREENS_ACTIVE;
        mService.mH.sendNewMessageDelayed(WindowManagerService.H.WINDOW_FREEZE_TIMEOUT,
                mDisplayContent, WINDOW_FREEZE_TIMEOUT_DURATION);

        if (shouldRotateSeamlessly(oldRotation, rotation, forceUpdate)) {
            // The screen rotation animation uses a screenshot to freeze the screen while windows
            // resize underneath. When we are rotating seamlessly, we allow the elements to
            // transition to their rotated state independently and without a freeze required.
            prepareSeamlessRotation();
        } else {
            prepareNormalRotationAnimation();
        }

        // Give a remote handler (system ui) some time to reposition things.
        startRemoteRotation(oldRotation, mRotation);

        return true;
    }

    private void startRemoteRotation(int fromRotation, int toRotation) {
        mDisplayContent.mRemoteDisplayChangeController.performRemoteDisplayChange(
                fromRotation, toRotation, null /* newDisplayAreaInfo */,
                (transaction) -> continueRotation(toRotation, transaction)
        );
    }

    private void continueRotation(int targetRotation, WindowContainerTransaction t) {
        if (targetRotation != mRotation) {
            // Drop it, this is either coming from an outdated remote rotation; or, we've
            // already moved on.
            return;
        }

        if (mDisplayContent.mTransitionController.isShellTransitionsEnabled()) {
            if (!mDisplayContent.mTransitionController.isCollecting()) {
                throw new IllegalStateException("Trying to rotate outside a transition");
            }
            mDisplayContent.mTransitionController.collect(mDisplayContent);
        }
        mService.mAtmService.deferWindowLayout();
        try {
            mDisplayContent.sendNewConfiguration();
            if (t != null) {
                mService.mAtmService.mWindowOrganizerController.applyTransaction(t);
            }
        } finally {
            mService.mAtmService.continueWindowLayout();
        }
    }

    void prepareNormalRotationAnimation() {
        cancelSeamlessRotation();
        final RotationAnimationPair anim = selectRotationAnimation();
        mService.startFreezingDisplay(anim.mExit, anim.mEnter, mDisplayContent);
    }

    /**
     * This ensures that normal rotation animation is used. E.g. {@link #mRotatingSeamlessly} was
     * set by previous {@link #updateRotationUnchecked}, but another orientation change happens
     * before calling {@link DisplayContent#sendNewConfiguration} (remote rotation hasn't finished)
     * and it doesn't choose seamless rotation.
     */
    void cancelSeamlessRotation() {
        if (!mRotatingSeamlessly) {
            return;
        }
        mDisplayContent.forAllWindows(w -> {
            if (w.mSeamlesslyRotated) {
                w.cancelSeamlessRotation();
                w.mSeamlesslyRotated = false;
            }
        }, true /* traverseTopToBottom */);
        mSeamlessRotationCount = 0;
        mRotatingSeamlessly = false;
        mDisplayContent.finishAsyncRotationIfPossible();
    }

    private void prepareSeamlessRotation() {
        // We are careful to reset this in case a window was removed before it finished
        // seamless rotation.
        mSeamlessRotationCount = 0;
        mRotatingSeamlessly = true;
    }

    boolean isRotatingSeamlessly() {
        return mRotatingSeamlessly;
    }

    boolean hasSeamlessRotatingWindow() {
        return mSeamlessRotationCount > 0;
    }

    @VisibleForTesting
    boolean shouldRotateSeamlessly(int oldRotation, int newRotation, boolean forceUpdate) {
        // Display doesn't need to be frozen because application has been started in correct
        // rotation already, so the rest of the windows can use seamless rotation.
        if (mDisplayContent.hasTopFixedRotationLaunchingApp()) {
            return true;
        }

        final WindowState w = mDisplayPolicy.getTopFullscreenOpaqueWindow();
        if (w == null || w != mDisplayContent.mCurrentFocus) {
            return false;
        }
        // We only enable seamless rotation if the top window has requested it and is in the
        // fullscreen opaque state. Seamless rotation requires freezing various Surface states and
        // won't work well with animations, so we disable it in the animation case for now.
        if (w.getAttrs().rotationAnimation != ROTATION_ANIMATION_SEAMLESS || w.inMultiWindowMode()
                || w.isAnimatingLw()) {
            return false;
        }

        if (!canRotateSeamlessly(oldRotation, newRotation)) {
            return false;
        }

        // If the bounds of activity window is different from its parent, then reject to be seamless
        // because the window position may change after rotation that will look like a sudden jump.
        if (w.mActivityRecord != null && !w.mActivityRecord.matchParentBounds()) {
            return false;
        }

        // In the presence of the PINNED root task or System Alert windows we unfortunately can not
        // seamlessly rotate.
        if (mDisplayContent.getDefaultTaskDisplayArea().hasPinnedTask()
                || mDisplayContent.hasAlertWindowSurfaces()) {
            return false;
        }

        // We can't rotate (seamlessly or not) while waiting for the last seamless rotation to
        // complete (that is, waiting for windows to redraw). It's tempting to check
        // mSeamlessRotationCount but that could be incorrect in the case of window-removal.
        if (!forceUpdate && mDisplayContent.getWindow(win -> win.mSeamlesslyRotated) != null) {
            return false;
        }

        return true;
    }

    boolean canRotateSeamlessly(int oldRotation, int newRotation) {
        // If the navigation bar can't change sides, then it will jump when we change orientations
        // and we don't rotate seamlessly - unless that is allowed, eg. with gesture navigation
        // where the navbar is low-profile enough that this isn't very noticeable.
        if (mAllowSeamlessRotationDespiteNavBarMoving || mDisplayPolicy.navigationBarCanMove()) {
            return true;
        }
        // For the upside down rotation we don't rotate seamlessly as the navigation bar moves
        // position. Note most apps (using orientation:sensor or user as opposed to fullSensor)
        // will not enter the reverse portrait orientation, so actually the orientation won't change
        // at all.
        return oldRotation != Surface.ROTATION_180 && newRotation != Surface.ROTATION_180;
    }

    void markForSeamlessRotation(WindowState w, boolean seamlesslyRotated) {
        if (seamlesslyRotated == w.mSeamlesslyRotated || w.mForceSeamlesslyRotate) {
            return;
        }

        w.mSeamlesslyRotated = seamlesslyRotated;
        if (seamlesslyRotated) {
            mSeamlessRotationCount++;
        } else {
            mSeamlessRotationCount--;
        }
        if (mSeamlessRotationCount == 0) {
            ProtoLog.i(WM_DEBUG_ORIENTATION,
                    "Performing post-rotate rotation after seamless rotation");
            // Finish seamless rotation.
            mRotatingSeamlessly = false;
            mDisplayContent.finishAsyncRotationIfPossible();

            updateRotationAndSendNewConfigIfChanged();
        }
    }

    /**
     * Returns the animation to run for a rotation transition based on the top fullscreen windows
     * {@link android.view.WindowManager.LayoutParams#rotationAnimation} and whether it is currently
     * fullscreen and frontmost.
     */
    private RotationAnimationPair selectRotationAnimation() {
        // If the screen is off or non-interactive, force a jumpcut.
        final boolean forceJumpcut = !mDisplayPolicy.isScreenOnFully()
                || !mService.mPolicy.okToAnimate(false /* ignoreScreenOn */);
        final WindowState topFullscreen = mDisplayPolicy.getTopFullscreenOpaqueWindow();
        ProtoLog.i(WM_DEBUG_ANIM, "selectRotationAnimation topFullscreen=%s"
                + " rotationAnimation=%d forceJumpcut=%b",
                topFullscreen,
                topFullscreen == null ? 0 : topFullscreen.getAttrs().rotationAnimation,
                forceJumpcut);
        if (forceJumpcut) {
            mTmpRotationAnim.mExit = R.anim.rotation_animation_jump_exit;
            mTmpRotationAnim.mEnter = R.anim.rotation_animation_enter;
            return mTmpRotationAnim;
        }
        if (topFullscreen != null) {
            int animationHint = topFullscreen.getRotationAnimationHint();
            if (animationHint < 0 && mDisplayPolicy.isTopLayoutFullscreen()) {
                animationHint = topFullscreen.getAttrs().rotationAnimation;
            }
            switch (animationHint) {
                case ROTATION_ANIMATION_CROSSFADE:
                case ROTATION_ANIMATION_SEAMLESS: // Crossfade is fallback for seamless.
                    mTmpRotationAnim.mExit = R.anim.rotation_animation_xfade_exit;
                    mTmpRotationAnim.mEnter = R.anim.rotation_animation_enter;
                    break;
                case ROTATION_ANIMATION_JUMPCUT:
                    mTmpRotationAnim.mExit = R.anim.rotation_animation_jump_exit;
                    mTmpRotationAnim.mEnter = R.anim.rotation_animation_enter;
                    break;
                case ROTATION_ANIMATION_ROTATE:
                default:
                    mTmpRotationAnim.mExit = mTmpRotationAnim.mEnter = 0;
                    break;
            }
        } else {
            mTmpRotationAnim.mExit = mTmpRotationAnim.mEnter = 0;
        }
        return mTmpRotationAnim;
    }

    /**
     * Validate whether the current top fullscreen has specified the same
     * {@link android.view.WindowManager.LayoutParams#rotationAnimation} value as that being passed
     * in from the previous top fullscreen window.
     *
     * @param exitAnimId exiting resource id from the previous window.
     * @param enterAnimId entering resource id from the previous window.
     * @param forceDefault For rotation animations only, if true ignore the animation values and
     *                     just return false.
     * @return {@code true} if the previous values are still valid, false if they should be replaced
     *         with the default.
     */
    boolean validateRotationAnimation(int exitAnimId, int enterAnimId, boolean forceDefault) {
        switch (exitAnimId) {
            case R.anim.rotation_animation_xfade_exit:
            case R.anim.rotation_animation_jump_exit:
                // These are the only cases that matter.
                if (forceDefault) {
                    return false;
                }
                final RotationAnimationPair anim = selectRotationAnimation();
                return exitAnimId == anim.mExit && enterAnimId == anim.mEnter;
            default:
                return true;
        }
    }

    void restoreSettings(int userRotationMode, int userRotation, int fixedToUserRotation) {
        mFixedToUserRotation = fixedToUserRotation;

        // We will retrieve user rotation and user rotation mode from settings for default display.
        if (isDefaultDisplay) {
            return;
        }
        if (userRotationMode != WindowManagerPolicy.USER_ROTATION_FREE
                && userRotationMode != WindowManagerPolicy.USER_ROTATION_LOCKED) {
            Slog.w(TAG, "Trying to restore an invalid user rotation mode " + userRotationMode
                    + " for " + mDisplayContent);
            userRotationMode = WindowManagerPolicy.USER_ROTATION_FREE;
        }
        if (userRotation < Surface.ROTATION_0 || userRotation > Surface.ROTATION_270) {
            Slog.w(TAG, "Trying to restore an invalid user rotation " + userRotation
                    + " for " + mDisplayContent);
            userRotation = Surface.ROTATION_0;
        }
        mUserRotationMode = userRotationMode;
        mUserRotation = userRotation;
    }

    void setFixedToUserRotation(int fixedToUserRotation) {
        if (mFixedToUserRotation == fixedToUserRotation) {
            return;
        }

        mFixedToUserRotation = fixedToUserRotation;
        mDisplayWindowSettings.setFixedToUserRotation(mDisplayContent, fixedToUserRotation);
        if (mDisplayContent.mFocusedApp != null) {
            // We record the last focused TDA that respects orientation request, check if this
            // change may affect it.
            mDisplayContent.onLastFocusedTaskDisplayAreaChanged(
                    mDisplayContent.mFocusedApp.getDisplayArea());
        }
        mDisplayContent.updateOrientation();
    }

    @VisibleForTesting
    void setUserRotation(int userRotationMode, int userRotation) {
        if (isDefaultDisplay) {
            // We'll be notified via settings listener, so we don't need to update internal values.
            final ContentResolver res = mContext.getContentResolver();
            final int accelerometerRotation =
                    userRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED ? 0 : 1;
            Settings.System.putIntForUser(res, Settings.System.ACCELEROMETER_ROTATION,
                    accelerometerRotation, UserHandle.USER_CURRENT);
            Settings.System.putIntForUser(res, Settings.System.USER_ROTATION, userRotation,
                    UserHandle.USER_CURRENT);
            return;
        }

        boolean changed = false;
        if (mUserRotationMode != userRotationMode) {
            mUserRotationMode = userRotationMode;
            changed = true;
        }
        if (mUserRotation != userRotation) {
            mUserRotation = userRotation;
            changed = true;
        }
        mDisplayWindowSettings.setUserRotation(mDisplayContent, userRotationMode,
                userRotation);
        if (changed) {
            mService.updateRotation(true /* alwaysSendConfiguration */,
                    false /* forceRelayout */);
        }
    }

    void freezeRotation(int rotation) {
        rotation = (rotation == -1) ? mRotation : rotation;
        setUserRotation(WindowManagerPolicy.USER_ROTATION_LOCKED, rotation);
    }

    void thawRotation() {
        setUserRotation(WindowManagerPolicy.USER_ROTATION_FREE, mUserRotation);
    }

    boolean isRotationFrozen() {
        if (!isDefaultDisplay) {
            return mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED;
        }

        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) == 0;
    }

    boolean isFixedToUserRotation() {
        switch (mFixedToUserRotation) {
            case IWindowManager.FIXED_TO_USER_ROTATION_DISABLED:
                return false;
            case IWindowManager.FIXED_TO_USER_ROTATION_ENABLED:
                return true;
            default:
                return mDefaultFixedToUserRotation;
        }
    }

    int getFixedToUserRotationMode() {
        return mFixedToUserRotation;
    }

    public int getLandscapeRotation() {
        return mLandscapeRotation;
    }

    public int getSeascapeRotation() {
        return mSeascapeRotation;
    }

    public int getPortraitRotation() {
        return mPortraitRotation;
    }

    public int getUpsideDownRotation() {
        return mUpsideDownRotation;
    }

    public int getCurrentAppOrientation() {
        return mCurrentAppOrientation;
    }

    public DisplayPolicy getDisplayPolicy() {
        return mDisplayPolicy;
    }

    public WindowOrientationListener getOrientationListener() {
        return mOrientationListener;
    }

    public int getUserRotation() {
        return mUserRotation;
    }

    public int getUserRotationMode() {
        return mUserRotationMode;
    }

    public void updateOrientationListener() {
        synchronized (mLock) {
            updateOrientationListenerLw();
        }
    }

    /**
     * Temporarily pauses rotation changes until resumed.
     * <p>
     * This can be used to prevent rotation changes from occurring while the user is performing
     * certain operations, such as drag and drop.
     * <p>
     * This call nests and must be matched by an equal number of calls to {@link #resume}.
     */
    void pause() {
        mDeferredRotationPauseCount++;
    }

    /** Resumes normal rotation changes after being paused. */
    void resume() {
        if (mDeferredRotationPauseCount <= 0) {
            return;
        }

        mDeferredRotationPauseCount--;
        if (mDeferredRotationPauseCount == 0) {
            updateRotationAndSendNewConfigIfChanged();
        }
    }

    /**
     * Various use cases for invoking this function:
     * <li>Screen turning off, should always disable listeners if already enabled.</li>
     * <li>Screen turned on and current app has sensor based orientation, enable listeners
     *     if not already enabled.</li>
     * <li>Screen turned on and current app does not have sensor orientation, disable listeners
     *     if already enabled.</li>
     * <li>Screen turning on and current app has sensor based orientation, enable listeners
     *     if needed.</li>
     * <li>screen turning on and current app has nosensor based orientation, do nothing.</li>
     */
    private void updateOrientationListenerLw() {
        if (mOrientationListener == null || !mOrientationListener.canDetectOrientation()) {
            // If sensor is turned off or nonexistent for some reason.
            return;
        }

        final boolean screenOnEarly = mDisplayPolicy.isScreenOnEarly();
        final boolean awake = mDisplayPolicy.isAwake();
        final boolean keyguardDrawComplete = mDisplayPolicy.isKeyguardDrawComplete();
        final boolean windowManagerDrawComplete = mDisplayPolicy.isWindowManagerDrawComplete();

        // Could have been invoked due to screen turning on or off or
        // change of the currently visible window's orientation.
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "screenOnEarly=%b, awake=%b, currentAppOrientation=%d, "
                        + "orientationSensorEnabled=%b, keyguardDrawComplete=%b, "
                        + "windowManagerDrawComplete=%b",
                screenOnEarly, awake, mCurrentAppOrientation, mOrientationListener.mEnabled,
                keyguardDrawComplete, windowManagerDrawComplete);

        boolean disable = true;

        // If the orientation listener uses a wake sensor, keep the orientation listener on if the
        // screen is on (regardless of wake state). This allows the AoD to rotate.
        //
        // Note: We postpone the rotating of the screen until the keyguard as well as the
        // window manager have reported a draw complete or the keyguard is going away in dismiss
        // mode.
        if (screenOnEarly
                && (awake || mOrientationListener.shouldStayEnabledWhileDreaming())
                && ((keyguardDrawComplete && windowManagerDrawComplete))) {
            if (needSensorRunning()) {
                disable = false;
                // Enable listener if not already enabled.
                if (!mOrientationListener.mEnabled) {
                    mOrientationListener.enable();
                }
            }
        }
        // Check if sensors need to be disabled.
        if (disable) {
            mOrientationListener.disable();
        }
    }

    /**
     * We always let the sensor be switched on by default except when
     * the user has explicitly disabled sensor based rotation or when the
     * screen is switched off.
     */
    private boolean needSensorRunning() {
        if (isFixedToUserRotation()) {
            // We are sure we only respect user rotation settings, so we are sure we will not
            // support sensor rotation.
            return false;
        }

        if (mSupportAutoRotation) {
            if (mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    || mCurrentAppOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                // If the application has explicitly requested to follow the
                // orientation, then we need to turn the sensor on.
                return true;
            }
        }

        final int dockMode = mDisplayPolicy.getDockMode();
        if ((mDisplayPolicy.isCarDockEnablesAccelerometer()
                && dockMode == Intent.EXTRA_DOCK_STATE_CAR)
                || (mDisplayPolicy.isDeskDockEnablesAccelerometer()
                        && (dockMode == Intent.EXTRA_DOCK_STATE_DESK
                                || dockMode == Intent.EXTRA_DOCK_STATE_LE_DESK
                                || dockMode == Intent.EXTRA_DOCK_STATE_HE_DESK))) {
            // Enable accelerometer if we are docked in a dock that enables accelerometer
            // orientation management.
            return true;
        }

        if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED) {
            // If the setting for using the sensor by default is enabled, then
            // we will always leave it on.  Note that the user could go to
            // a window that forces an orientation that does not use the
            // sensor and in theory we could turn it off... however, when next
            // turning it on we won't have a good value for the current
            // orientation for a little bit, which can cause orientation
            // changes to lag, so we'd like to keep it always on.  (It will
            // still be turned off when the screen is off.)

            // When locked we can provide rotation suggestions users can approve to change the
            // current screen rotation. To do this the sensor needs to be running.
            return mSupportAutoRotation &&
                    mShowRotationSuggestions == Settings.Secure.SHOW_ROTATION_SUGGESTIONS_ENABLED;
        }
        return mSupportAutoRotation;
    }

    /**
     * If this is true we have updated our desired orientation, but not yet changed the real
     * orientation our applied our screen rotation animation. For example, because a previous
     * screen rotation was in progress.
     *
     * @return {@code true} if the there is an ongoing rotation change.
     */
    boolean needsUpdate() {
        final int oldRotation = mRotation;
        final int rotation = rotationForOrientation(mLastOrientation, oldRotation);
        return oldRotation != rotation;
    }


    /**
     * Resets whether the screen can be rotated via the accelerometer in all 4 rotations as the
     * default behavior.
     *
     * To be called if there is potential that the value changed. For example if the active display
     * changed.
     *
     * At the moment it is called from
     * {@link DisplayWindowSettings#applyRotationSettingsToDisplayLocked}.
     */
    void resetAllowAllRotations() {
        mAllowAllRotations = ALLOW_ALL_ROTATIONS_UNDEFINED;
    }

    /**
     * Given an orientation constant, returns the appropriate surface rotation, taking into account
     * sensors, docking mode, rotation lock, and other factors.
     *
     * @param orientation  An orientation constant, such as
     *                     {@link ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE}.
     * @param lastRotation The most recently used rotation.
     * @return The surface rotation to use.
     */
    @VisibleForTesting
    @Surface.Rotation
    int rotationForOrientation(@ScreenOrientation int orientation,
            @Surface.Rotation int lastRotation) {
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "rotationForOrientation(orient=%s (%d), last=%s (%d)); user=%s (%d) %s",
                ActivityInfo.screenOrientationToString(orientation), orientation,
                Surface.rotationToString(lastRotation), lastRotation,
                Surface.rotationToString(mUserRotation), mUserRotation,
                mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED
                        ? "USER_ROTATION_LOCKED" : "");

        if (isFixedToUserRotation()) {
            return mUserRotation;
        }

        int sensorRotation = mOrientationListener != null
                ? mOrientationListener.getProposedRotation() // may be -1
                : -1;
        mLastSensorRotation = sensorRotation;
        if (sensorRotation < 0) {
            sensorRotation = lastRotation;
        }

        final int lidState = mDisplayPolicy.getLidState();
        final int dockMode = mDisplayPolicy.getDockMode();
        final boolean hdmiPlugged = mDisplayPolicy.isHdmiPlugged();
        final boolean carDockEnablesAccelerometer =
                mDisplayPolicy.isCarDockEnablesAccelerometer();
        final boolean deskDockEnablesAccelerometer =
                mDisplayPolicy.isDeskDockEnablesAccelerometer();

        final int preferredRotation;
        if (!isDefaultDisplay) {
            // For secondary displays we ignore things like displays sensors, docking mode and
            // rotation lock, and always prefer user rotation.
            preferredRotation = mUserRotation;
        } else if (lidState == LID_OPEN && mLidOpenRotation >= 0) {
            // Ignore sensor when lid switch is open and rotation is forced.
            preferredRotation = mLidOpenRotation;
        } else if (dockMode == Intent.EXTRA_DOCK_STATE_CAR
                && (carDockEnablesAccelerometer || mCarDockRotation >= 0)) {
            // Ignore sensor when in car dock unless explicitly enabled.
            // This case can override the behavior of NOSENSOR, and can also
            // enable 180 degree rotation while docked.
            preferredRotation = carDockEnablesAccelerometer ? sensorRotation : mCarDockRotation;
        } else if ((dockMode == Intent.EXTRA_DOCK_STATE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_LE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_HE_DESK)
                && (deskDockEnablesAccelerometer || mDeskDockRotation >= 0)) {
            // Ignore sensor when in desk dock unless explicitly enabled.
            // This case can override the behavior of NOSENSOR, and can also
            // enable 180 degree rotation while docked.
            preferredRotation = deskDockEnablesAccelerometer ? sensorRotation : mDeskDockRotation;
        } else if (hdmiPlugged && mDemoHdmiRotationLock) {
            // Ignore sensor when plugged into HDMI when demo HDMI rotation lock enabled.
            // Note that the dock orientation overrides the HDMI orientation.
            preferredRotation = mDemoHdmiRotation;
        } else if (hdmiPlugged && dockMode == Intent.EXTRA_DOCK_STATE_UNDOCKED
                && mUndockedHdmiRotation >= 0) {
            // Ignore sensor when plugged into HDMI and an undocked orientation has
            // been specified in the configuration (only for legacy devices without
            // full multi-display support).
            // Note that the dock orientation overrides the HDMI orientation.
            preferredRotation = mUndockedHdmiRotation;
        } else if (mDemoRotationLock) {
            // Ignore sensor when demo rotation lock is enabled.
            // Note that the dock orientation and HDMI rotation lock override this.
            preferredRotation = mDemoRotation;
        } else if (mDisplayPolicy.isPersistentVrModeEnabled()) {
            // While in VR, apps always prefer a portrait rotation. This does not change
            // any apps that explicitly set landscape, but does cause sensors be ignored,
            // and ignored any orientation lock that the user has set (this conditional
            // should remain above the ORIENTATION_LOCKED conditional below).
            preferredRotation = mPortraitRotation;
        } else if (orientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            // Application just wants to remain locked in the last rotation.
            preferredRotation = lastRotation;
        } else if (!mSupportAutoRotation) {
            // If we don't support auto-rotation then bail out here and ignore
            // the sensor and any rotation lock settings.
            preferredRotation = -1;
        } else if (((mUserRotationMode == WindowManagerPolicy.USER_ROTATION_FREE
                            || isTabletopAutoRotateOverrideEnabled())
                        && (orientation == ActivityInfo.SCREEN_ORIENTATION_USER
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                                || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER))
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR
                || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
            // Otherwise, use sensor only if requested by the application or enabled
            // by default for USER or UNSPECIFIED modes.  Does not apply to NOSENSOR.
            if (sensorRotation != Surface.ROTATION_180
                    || getAllowAllRotations() == ALLOW_ALL_ROTATIONS_ENABLED
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER) {
                preferredRotation = sensorRotation;
            } else {
                preferredRotation = lastRotation;
            }
        } else if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED
                && orientation != ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                && orientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                && orientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                && orientation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                && orientation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            // Apply rotation lock. Does not apply to NOSENSOR or specific rotations.
            // The idea is that the user rotation expresses a weak preference for the direction
            // of gravity and as NOSENSOR is never affected by gravity, then neither should
            // NOSENSOR be affected by rotation lock (although it will be affected by docks).
            // Also avoid setting user rotation when app has preference over one particular rotation
            // to avoid leaving the rotation to the reverse of it which has the compatible
            // orientation, but isn't what app wants, when the user rotation is the reverse of the
            // preferred rotation.
            preferredRotation = mUserRotation;
        } else {
            // No overriding preference.
            // We will do exactly what the application asked us to do.
            preferredRotation = -1;
        }

        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                // Return portrait unless overridden.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                return mPortraitRotation;

            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                // Return landscape unless overridden.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                return mLandscapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                // Return reverse portrait unless overridden.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                return mUpsideDownRotation;

            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                // Return seascape unless overridden.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                return mSeascapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                // Return either landscape rotation.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                if (isLandscapeOrSeascape(lastRotation)) {
                    return lastRotation;
                }
                return mLandscapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // Return either portrait rotation.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                if (isAnyPortrait(lastRotation)) {
                    return lastRotation;
                }
                return mPortraitRotation;

            default:
                // For USER, UNSPECIFIED, NOSENSOR, SENSOR and FULL_SENSOR,
                // just return the preferred orientation we already calculated.
                if (preferredRotation >= 0) {
                    return preferredRotation;
                }
                return Surface.ROTATION_0;
        }
    }

    private int getAllowAllRotations() {
        if (mAllowAllRotations == ALLOW_ALL_ROTATIONS_UNDEFINED) {
            // Can't read this during init() because the context doesn't have display metrics at
            // that time so we cannot determine tablet vs. phone then.
            mAllowAllRotations = mContext.getResources().getBoolean(
                    R.bool.config_allowAllRotations)
                    ? ALLOW_ALL_ROTATIONS_ENABLED
                    : ALLOW_ALL_ROTATIONS_DISABLED;
        }

        return mAllowAllRotations;
    }

    boolean isLandscapeOrSeascape(@Surface.Rotation final int rotation) {
        return rotation == mLandscapeRotation || rotation == mSeascapeRotation;
    }

    boolean isAnyPortrait(@Surface.Rotation final int rotation) {
        return rotation == mPortraitRotation || rotation == mUpsideDownRotation;
    }

    private boolean isValidRotationChoice(final int preferredRotation) {
        // Determine if the given app orientation is compatible with the provided rotation choice.
        switch (mCurrentAppOrientation) {
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                // Works with any of the 4 rotations.
                return preferredRotation >= 0;

            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // It's possible for the user pref to be set at 180 because of FULL_USER. This would
                // make switching to USER_PORTRAIT appear at 180. Provide choice to back to portrait
                // but never to go to 180.
                return preferredRotation == mPortraitRotation;

            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                // Works landscape or seascape.
                return isLandscapeOrSeascape(preferredRotation);

            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
                // When all rotations enabled it works with any of the 4 rotations
                if (getAllowAllRotations() == ALLOW_ALL_ROTATIONS_ENABLED) {
                    return preferredRotation >= 0;
                }

                // Works with any rotation except upside down.
                return (preferredRotation >= 0) && (preferredRotation != Surface.ROTATION_180);
        }

        return false;
    }

    private boolean isTabletopAutoRotateOverrideEnabled() {
        return mFoldController != null && mFoldController.overrideFrozenRotation();
    }

    private boolean isRotationChoiceAllowed(@Surface.Rotation final int proposedRotation) {
        final boolean isRotationLockEnforced = mCompatPolicyForImmersiveApps != null
                && mCompatPolicyForImmersiveApps.isRotationLockEnforced(proposedRotation);

        // Don't show rotation choice button if
        if (!isRotationLockEnforced // not enforcing locked rotation
                // and the screen rotation is not locked by the user.
                && mUserRotationMode != WindowManagerPolicy.USER_ROTATION_LOCKED) {
            return false;
        }

        // Don't show rotation choice if we are in tabletop or book modes.
        if (isTabletopAutoRotateOverrideEnabled()) return false;

        // We should only enable rotation choice if the rotation isn't forced by the lid, dock,
        // demo, hdmi, vr, etc mode.

        // Determine if the rotation is currently forced.
        if (isFixedToUserRotation()) {
            return false; // Rotation is forced to user settings.
        }

        final int lidState = mDisplayPolicy.getLidState();
        if (lidState == LID_OPEN && mLidOpenRotation >= 0) {
            return false; // Rotation is forced mLidOpenRotation.
        }

        final int dockMode = mDisplayPolicy.getDockMode();
        final boolean carDockEnablesAccelerometer = false;
        if (dockMode == Intent.EXTRA_DOCK_STATE_CAR && !carDockEnablesAccelerometer) {
            return false; // Rotation forced to mCarDockRotation.
        }

        final boolean deskDockEnablesAccelerometer =
                mDisplayPolicy.isDeskDockEnablesAccelerometer();
        if ((dockMode == Intent.EXTRA_DOCK_STATE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_LE_DESK
                || dockMode == Intent.EXTRA_DOCK_STATE_HE_DESK)
                && !deskDockEnablesAccelerometer) {
            return false; // Rotation forced to mDeskDockRotation.
        }

        final boolean hdmiPlugged = mDisplayPolicy.isHdmiPlugged();
        if (hdmiPlugged && mDemoHdmiRotationLock) {
            return false; // Rotation forced to mDemoHdmiRotation.

        } else if (hdmiPlugged && dockMode == Intent.EXTRA_DOCK_STATE_UNDOCKED
                && mUndockedHdmiRotation >= 0) {
            return false; // Rotation forced to mUndockedHdmiRotation.

        } else if (mDemoRotationLock) {
            return false; // Rotation forced to mDemoRotation.

        } else if (mDisplayPolicy.isPersistentVrModeEnabled()) {
            return false; // Rotation forced to mPortraitRotation.

        } else if (!mSupportAutoRotation) {
            return false;
        }

        // Ensure that some rotation choice is possible for the given orientation.
        switch (mCurrentAppOrientation) {
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // NOSENSOR description is ambiguous, in reality WM ignores user choice.
                return true;
        }

        // Rotation is forced, should be controlled by system.
        return false;
    }

    /** Notify the StatusBar that system rotation suggestion has changed. */
    private void sendProposedRotationChangeToStatusBarInternal(int rotation, boolean isValid) {
        if (mStatusBarManagerInternal == null) {
            mStatusBarManagerInternal = LocalServices.getService(StatusBarManagerInternal.class);
        }
        if (mStatusBarManagerInternal != null) {
            mStatusBarManagerInternal.onProposedRotationChanged(rotation, isValid);
        }
    }

    private static String allowAllRotationsToString(int allowAll) {
        switch (allowAll) {
            case -1:
                return "unknown";
            case 0:
                return "false";
            case 1:
                return "true";
            default:
                return Integer.toString(allowAll);
        }
    }

    public void onUserSwitch() {
        if (mSettingsObserver != null) {
            mSettingsObserver.onChange(false);
        }
    }

    /** Return whether the rotation settings has changed. */
    private boolean updateSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        boolean shouldUpdateRotation = false;

        synchronized (mLock) {
            boolean shouldUpdateOrientationListener = false;

            // Configure rotation suggestions.
            final int showRotationSuggestions =
                    ActivityManager.isLowRamDeviceStatic()
                            ? Settings.Secure.SHOW_ROTATION_SUGGESTIONS_DISABLED
                            : Settings.Secure.getIntForUser(resolver,
                            Settings.Secure.SHOW_ROTATION_SUGGESTIONS,
                            Settings.Secure.SHOW_ROTATION_SUGGESTIONS_DEFAULT,
                            UserHandle.USER_CURRENT);
            if (mShowRotationSuggestions != showRotationSuggestions) {
                mShowRotationSuggestions = showRotationSuggestions;
                shouldUpdateOrientationListener = true;
            }

            // Configure rotation lock.
            final int userRotation = Settings.System.getIntForUser(resolver,
                    Settings.System.USER_ROTATION, Surface.ROTATION_0,
                    UserHandle.USER_CURRENT);
            if (mUserRotation != userRotation) {
                mUserRotation = userRotation;
                shouldUpdateRotation = true;
            }

            final int userRotationMode = Settings.System.getIntForUser(resolver,
                    Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) != 0
                            ? WindowManagerPolicy.USER_ROTATION_FREE
                            : WindowManagerPolicy.USER_ROTATION_LOCKED;
            if (mUserRotationMode != userRotationMode) {
                mUserRotationMode = userRotationMode;
                shouldUpdateOrientationListener = true;
                shouldUpdateRotation = true;
            }

            if (shouldUpdateOrientationListener) {
                updateOrientationListenerLw(); // Enable or disable the orientation listener.
            }

            final int cameraRotationMode = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.CAMERA_AUTOROTATE, 0,
                    UserHandle.USER_CURRENT);
            if (mCameraRotationMode != cameraRotationMode) {
                mCameraRotationMode = cameraRotationMode;
                shouldUpdateRotation = true;
            }
        }

        return shouldUpdateRotation;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayRotation");
        pw.println(prefix + "  mCurrentAppOrientation="
                + ActivityInfo.screenOrientationToString(mCurrentAppOrientation));
        pw.println(prefix + "  mLastOrientation=" + mLastOrientation);
        pw.print(prefix + "  mRotation=" + mRotation);
        pw.println(" mDeferredRotationPauseCount=" + mDeferredRotationPauseCount);

        pw.print(prefix + "  mLandscapeRotation=" + Surface.rotationToString(mLandscapeRotation));
        pw.println(" mSeascapeRotation=" + Surface.rotationToString(mSeascapeRotation));
        pw.print(prefix + "  mPortraitRotation=" + Surface.rotationToString(mPortraitRotation));
        pw.println(" mUpsideDownRotation=" + Surface.rotationToString(mUpsideDownRotation));

        pw.println(prefix + "  mSupportAutoRotation=" + mSupportAutoRotation);
        if (mOrientationListener != null) {
            mOrientationListener.dump(pw, prefix + "  ");
        }
        pw.println();

        pw.print(prefix + "  mCarDockRotation=" + Surface.rotationToString(mCarDockRotation));
        pw.println(" mDeskDockRotation=" + Surface.rotationToString(mDeskDockRotation));
        pw.print(prefix + "  mUserRotationMode="
                + WindowManagerPolicy.userRotationModeToString(mUserRotationMode));
        pw.print(" mUserRotation=" + Surface.rotationToString(mUserRotation));
        pw.print(" mCameraRotationMode=" + mCameraRotationMode);
        pw.println(" mAllowAllRotations=" + allowAllRotationsToString(mAllowAllRotations));

        pw.print(prefix + "  mDemoHdmiRotation=" + Surface.rotationToString(mDemoHdmiRotation));
        pw.print(" mDemoHdmiRotationLock=" + mDemoHdmiRotationLock);
        pw.println(" mUndockedHdmiRotation=" + Surface.rotationToString(mUndockedHdmiRotation));
        pw.println(prefix + "  mLidOpenRotation=" + Surface.rotationToString(mLidOpenRotation));
        pw.println(prefix + "  mFixedToUserRotation=" + isFixedToUserRotation());

        if (!mRotationHistory.mRecords.isEmpty()) {
            pw.println();
            pw.println(prefix + "  RotationHistory");
            prefix = "    " + prefix;
            for (RotationHistory.Record r : mRotationHistory.mRecords) {
                r.dump(prefix, pw);
            }
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ROTATION, getRotation());
        proto.write(FROZEN_TO_USER_ROTATION, isRotationFrozen());
        proto.write(USER_ROTATION, getUserRotation());
        proto.write(FIXED_TO_USER_ROTATION_MODE, mFixedToUserRotation);
        proto.write(LAST_ORIENTATION, mLastOrientation);
        proto.write(IS_FIXED_TO_USER_ROTATION, isFixedToUserRotation());
        proto.end(token);
    }

    boolean isDeviceInPosture(DeviceStateController.FoldState state, boolean isTabletop) {
        if (mFoldController == null) return false;
        return mFoldController.isDeviceInPosture(state, isTabletop);
    }

    boolean isDisplaySeparatingHinge() {
        return mFoldController != null && mFoldController.isSeparatingHinge();
    }

    /**
     * Called by the DeviceStateManager callback when the device state changes.
     */
    void foldStateChanged(DeviceStateController.FoldState foldState) {
        if (mFoldController != null) {
            synchronized (mLock) {
                mFoldController.foldStateChanged(foldState);
            }
        }
    }

    private class FoldController {
        @Surface.Rotation
        private int mHalfFoldSavedRotation = -1; // No saved rotation
        private DeviceStateController.FoldState mFoldState =
                DeviceStateController.FoldState.UNKNOWN;
        private boolean mInHalfFoldTransition = false;
        private final boolean mIsDisplayAlwaysSeparatingHinge;
        private final Set<Integer> mTabletopRotations;

        FoldController() {
            mTabletopRotations = new ArraySet<>();
            int[] tabletop_rotations = mContext.getResources().getIntArray(
                    R.array.config_deviceTabletopRotations);
            if (tabletop_rotations != null) {
                for (int angle : tabletop_rotations) {
                    switch (angle) {
                        case 0:
                            mTabletopRotations.add(Surface.ROTATION_0);
                            break;
                        case 90:
                            mTabletopRotations.add(Surface.ROTATION_90);
                            break;
                        case 180:
                            mTabletopRotations.add(Surface.ROTATION_180);
                            break;
                        case 270:
                            mTabletopRotations.add(Surface.ROTATION_270);
                            break;
                        default:
                            ProtoLog.e(WM_DEBUG_ORIENTATION,
                                    "Invalid surface rotation angle in "
                                            + "config_deviceTabletopRotations: %d",
                                    angle);
                    }
                }
            } else {
                ProtoLog.w(WM_DEBUG_ORIENTATION,
                        "config_deviceTabletopRotations is not defined. Half-fold "
                                + "letterboxing will work inconsistently.");
            }
            mIsDisplayAlwaysSeparatingHinge = mContext.getResources().getBoolean(
                    R.bool.config_isDisplayHingeAlwaysSeparating);
        }

        boolean isDeviceInPosture(DeviceStateController.FoldState state, boolean isTabletop) {
            if (state != mFoldState) {
                return false;
            }
            if (mFoldState == DeviceStateController.FoldState.HALF_FOLDED) {
                return !(isTabletop ^ mTabletopRotations.contains(mRotation));
            }
            return true;
        }

        DeviceStateController.FoldState getFoldState() {
            return mFoldState;
        }

        boolean isSeparatingHinge() {
            return mFoldState == DeviceStateController.FoldState.HALF_FOLDED
                    || (mFoldState == DeviceStateController.FoldState.OPEN
                        && mIsDisplayAlwaysSeparatingHinge);
        }

        boolean overrideFrozenRotation() {
            return mFoldState == DeviceStateController.FoldState.HALF_FOLDED;
        }

        boolean shouldRevertOverriddenRotation() {
            return mFoldState == DeviceStateController.FoldState.OPEN // When transitioning to open.
                    && mInHalfFoldTransition
                    && mHalfFoldSavedRotation != -1 // Ignore if we've already reverted.
                    && mUserRotationMode
                    == WindowManagerPolicy.USER_ROTATION_LOCKED; // Ignore if we're unlocked.
        }

        int revertOverriddenRotation() {
            int savedRotation = mHalfFoldSavedRotation;
            mHalfFoldSavedRotation = -1;
            mInHalfFoldTransition = false;
            return savedRotation;
        }

        void foldStateChanged(DeviceStateController.FoldState newState) {
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "foldStateChanged: displayId %d, halfFoldStateChanged %s, "
                    + "saved rotation: %d, mUserRotation: %d, mLastSensorRotation: %d, "
                    + "mLastOrientation: %d, mRotation: %d",
                    mDisplayContent.getDisplayId(), newState.name(), mHalfFoldSavedRotation,
                    mUserRotation, mLastSensorRotation, mLastOrientation, mRotation);
            if (mFoldState == DeviceStateController.FoldState.UNKNOWN) {
                mFoldState = newState;
                return;
            }
            if (newState == DeviceStateController.FoldState.HALF_FOLDED
                    && mFoldState != DeviceStateController.FoldState.HALF_FOLDED) {
                // The device has transitioned to HALF_FOLDED state: save the current rotation and
                // update the device rotation.
                mHalfFoldSavedRotation = mRotation;
                mFoldState = newState;
                // Now mFoldState is set to HALF_FOLDED, the overrideFrozenRotation function will
                // return true, so rotation is unlocked.
                mService.updateRotation(false /* alwaysSendConfiguration */,
                        false /* forceRelayout */);
            } else {
                mInHalfFoldTransition = true;
                mFoldState = newState;
                // Tell the device to update its orientation.
                mService.updateRotation(false /* alwaysSendConfiguration */,
                        false /* forceRelayout */);
            }
            // Alert the activity of possible new bounds.
            final Task topFullscreenTask =
                    mDisplayContent.getTask(t -> t.getWindowingMode() == WINDOWING_MODE_FULLSCREEN);
            if (topFullscreenTask != null) {
                final ActivityRecord top = topFullscreenTask.topRunningActivity();
                if (top != null) {
                    top.recomputeConfiguration();
                }
            }
        }
    }

    private class OrientationListener extends WindowOrientationListener implements Runnable {
        transient boolean mEnabled;

        OrientationListener(Context context, Handler handler) {
            super(context, handler);
        }

        @Override
        public boolean isKeyguardShowingAndNotOccluded() {
            return mService.isKeyguardShowingAndNotOccluded();
        }

        @Override
        public boolean isRotationResolverEnabled() {
            return mUserRotationMode == WindowManagerPolicy.USER_ROTATION_FREE
                    && mCameraRotationMode == CAMERA_ROTATION_ENABLED
                    && !mService.mPowerManager.isPowerSaveMode();
        }


        @Override
        public void onProposedRotationChanged(@Surface.Rotation int rotation) {
            ProtoLog.v(WM_DEBUG_ORIENTATION, "onProposedRotationChanged, rotation=%d", rotation);
            // Send interaction power boost to improve redraw performance.
            mService.mPowerManagerInternal.setPowerBoost(Boost.INTERACTION, 0);
            if (isRotationChoiceAllowed(rotation)) {
                final boolean isValid = isValidRotationChoice(rotation);
                sendProposedRotationChangeToStatusBarInternal(rotation, isValid);
            } else {
                mService.updateRotation(false /* alwaysSendConfiguration */,
                        false /* forceRelayout */);
            }
        }

        @Override
        public void enable() {
            mEnabled = true;
            getHandler().post(this);
            ProtoLog.v(WM_DEBUG_ORIENTATION, "Enabling listeners");
        }

        @Override
        public void disable() {
            mEnabled = false;
            getHandler().post(this);
            ProtoLog.v(WM_DEBUG_ORIENTATION, "Disabling listeners");
        }

        @Override
        public void run() {
            if (mEnabled) {
                super.enable();
            } else {
                super.disable();
            }
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SHOW_ROTATION_SUGGESTIONS), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ACCELEROMETER_ROTATION), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.USER_ROTATION), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.CAMERA_AUTOROTATE), false, this,
                    UserHandle.USER_ALL);

            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            if (updateSettings()) {
                mService.updateRotation(true /* alwaysSendConfiguration */,
                        false /* forceRelayout */);
            }
        }
    }

    private static class RotationHistory {
        private static final int MAX_SIZE = 8;
        private static final int NO_FOLD_CONTROLLER = -2;
        private static class Record {
            final @Surface.Rotation int mFromRotation;
            final @Surface.Rotation int mToRotation;
            final @Surface.Rotation int mUserRotation;
            final @WindowManagerPolicy.UserRotationMode int mUserRotationMode;
            final int mSensorRotation;
            final boolean mIgnoreOrientationRequest;
            final String mNonDefaultRequestingTaskDisplayArea;
            final String mLastOrientationSource;
            final @ActivityInfo.ScreenOrientation int mSourceOrientation;
            final long mTimestamp = System.currentTimeMillis();
            final int mHalfFoldSavedRotation;
            final boolean mInHalfFoldTransition;
            final DeviceStateController.FoldState mFoldState;
            @Nullable final String mDisplayRotationCompatPolicySummary;

            Record(DisplayRotation dr, int fromRotation, int toRotation) {
                mFromRotation = fromRotation;
                mToRotation = toRotation;
                mUserRotation = dr.mUserRotation;
                mUserRotationMode = dr.mUserRotationMode;
                final OrientationListener listener = dr.mOrientationListener;
                mSensorRotation = (listener == null || !listener.mEnabled)
                        ? -2 /* disabled */ : dr.mLastSensorRotation;
                final DisplayContent dc = dr.mDisplayContent;
                mIgnoreOrientationRequest = dc.getIgnoreOrientationRequest();
                final TaskDisplayArea requestingTda = dc.getOrientationRequestingTaskDisplayArea();
                mNonDefaultRequestingTaskDisplayArea = requestingTda == null
                        ? "none" : requestingTda != dc.getDefaultTaskDisplayArea()
                        ? requestingTda.toString() : null;
                final WindowContainer<?> source = dc.getLastOrientationSource();
                if (source != null) {
                    mLastOrientationSource = source.toString();
                    final WindowState w = source.asWindowState();
                    mSourceOrientation =
                            w != null ? w.mAttrs.screenOrientation : source.mOrientation;
                } else {
                    mLastOrientationSource = null;
                    mSourceOrientation = SCREEN_ORIENTATION_UNSET;
                }
                if (dr.mFoldController != null) {
                    mHalfFoldSavedRotation = dr.mFoldController.mHalfFoldSavedRotation;
                    mInHalfFoldTransition = dr.mFoldController.mInHalfFoldTransition;
                    mFoldState = dr.mFoldController.mFoldState;
                } else {
                    mHalfFoldSavedRotation = NO_FOLD_CONTROLLER;
                    mInHalfFoldTransition = false;
                    mFoldState = DeviceStateController.FoldState.UNKNOWN;
                }
                mDisplayRotationCompatPolicySummary = dc.mDisplayRotationCompatPolicy == null
                        ? null
                        : dc.mDisplayRotationCompatPolicy
                                .getSummaryForDisplayRotationHistoryRecord();
            }

            void dump(String prefix, PrintWriter pw) {
                pw.println(prefix + TimeUtils.logTimeOfDay(mTimestamp)
                        + " " + Surface.rotationToString(mFromRotation)
                        + " to " + Surface.rotationToString(mToRotation));
                pw.println(prefix + "  source=" + mLastOrientationSource
                        + " " + ActivityInfo.screenOrientationToString(mSourceOrientation));
                pw.println(prefix + "  mode="
                        + WindowManagerPolicy.userRotationModeToString(mUserRotationMode)
                        + " user=" + Surface.rotationToString(mUserRotation)
                        + " sensor=" + Surface.rotationToString(mSensorRotation));
                if (mIgnoreOrientationRequest) pw.println(prefix + "  ignoreRequest=true");
                if (mNonDefaultRequestingTaskDisplayArea != null) {
                    pw.println(prefix + "  requestingTda=" + mNonDefaultRequestingTaskDisplayArea);
                }
                if (mHalfFoldSavedRotation != NO_FOLD_CONTROLLER) {
                    pw.println(prefix + " halfFoldSavedRotation="
                            + mHalfFoldSavedRotation
                            + " mInHalfFoldTransition=" + mInHalfFoldTransition
                            + " mFoldState=" + mFoldState);
                }
                if (mDisplayRotationCompatPolicySummary != null) {
                    pw.println(prefix + mDisplayRotationCompatPolicySummary);
                }
            }
        }

        final ArrayDeque<Record> mRecords = new ArrayDeque<>(MAX_SIZE);

        void addRecord(DisplayRotation dr, int toRotation) {
            if (mRecords.size() >= MAX_SIZE) {
                mRecords.removeFirst();
            }
            final int fromRotation = dr.mDisplayContent.getWindowConfiguration().getRotation();
            mRecords.addLast(new Record(dr, fromRotation, toRotation));
        }
    }
}
