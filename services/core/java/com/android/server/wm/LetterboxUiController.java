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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.isFixedOrientation;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.screenOrientationToString;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.content.res.Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS;
import static android.view.WindowManager.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION;

import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__BOTTOM;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__LEFT;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__RIGHT;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__TOP;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__UNKNOWN_POSITION;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__BOTTOM_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_BOTTOM;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_LEFT;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_RIGHT;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_TOP;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__LEFT_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__RIGHT_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__TOP_TO_CENTER;
import static com.android.server.wm.ActivityRecord.computeAspectRatio;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_POSITION_MULTIPLIER_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
import static com.android.server.wm.LetterboxConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
import static com.android.server.wm.LetterboxConfiguration.letterboxBackgroundTypeToString;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.TaskDescription;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.server.wm.LetterboxConfiguration.LetterboxBackgroundType;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Controls behaviour of the letterbox UI for {@link mActivityRecord}. */
// TODO(b/185262487): Improve test coverage of this class. Parts of it are tested in
// SizeCompatTests and LetterboxTests but not all.
// TODO(b/185264020): Consider making LetterboxUiController applicable to any level of the
// hierarchy in addition to ActivityRecord (Task, DisplayArea, ...).
// TODO(b/263021211): Consider renaming to more generic CompatUIController.
final class LetterboxUiController {

    private static final Predicate<ActivityRecord> FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE =
            activityRecord -> activityRecord.fillsParent() && !activityRecord.isFinishing()
                    && activityRecord.nowVisible;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "LetterboxUiController" : TAG_ATM;

    private static final float UNDEFINED_ASPECT_RATIO = 0f;

    // Minimum value of mSetOrientationRequestCounter before qualifying as orientation request loop
    @VisibleForTesting
    static final int MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP = 2;
    // Used to determine reset of mSetOrientationRequestCounter if next app requested
    // orientation is after timeout value
    @VisibleForTesting
    static final int SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS = 1000;

    private final Point mTmpPoint = new Point();

    private final LetterboxConfiguration mLetterboxConfiguration;

    private final ActivityRecord mActivityRecord;

    /**
     * Taskbar expanded height. Used to determine when to crop an app window to display the
     * rounded corners above the expanded taskbar.
     */
    private final float mExpandedTaskBarHeight;

    // TODO(b/265576778): Cache other overrides as well.

    // Corresponds to OVERRIDE_ANY_ORIENTATION
    private final boolean mIsOverrideAnyOrientationEnabled;
    // Corresponds to OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT
    private final boolean mIsOverrideToPortraitOrientationEnabled;
    // Corresponds to OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR
    private final boolean mIsOverrideToNosensorOrientationEnabled;
    // Corresponds to OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE
    private final boolean mIsOverrideToReverseLandscapeOrientationEnabled;
    // Corresponds to OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA
    private final boolean mIsOverrideOrientationOnlyForCameraEnabled;
    // Corresponds to OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION
    private final boolean mIsOverrideUseDisplayLandscapeNaturalOrientationEnabled;
    // Corresponds to OVERRIDE_RESPECT_REQUESTED_ORIENTATION
    private final boolean mIsOverrideRespectRequestedOrientationEnabled;

    // Corresponds to OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION
    private final boolean mIsOverrideCameraCompatDisableForceRotationEnabled;
    // Corresponds to OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH
    private final boolean mIsOverrideCameraCompatDisableRefreshEnabled;
    // Corresponds to OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE
    private final boolean mIsOverrideCameraCompatEnableRefreshViaPauseEnabled;

    // Corresponds to OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION
    private final boolean mIsOverrideEnableCompatIgnoreRequestedOrientationEnabled;
    // Corresponds to OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED
    private final boolean mIsOverrideEnableCompatIgnoreOrientationRequestWhenLoopDetectedEnabled;

    // Corresponds to OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS
    private final boolean mIsOverrideEnableCompatFakeFocusEnabled;

    @Nullable
    private final Boolean mBooleanPropertyAllowOrientationOverride;
    @Nullable
    private final Boolean mBooleanPropertyAllowDisplayOrientationOverride;

    /*
     * WindowContainerListener responsible to make translucent activities inherit
     * constraints from the first opaque activity beneath them. It's null for not
     * translucent activities.
     */
    @Nullable
    private WindowContainerListener mLetterboxConfigListener;

    private boolean mShowWallpaperForLetterboxBackground;

    // In case of transparent activities we might need to access the aspectRatio of the
    // first opaque activity beneath.
    private float mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
    private float mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;

    // Updated when ActivityRecord#setRequestedOrientation is called
    private long mTimeMsLastSetOrientationRequest = 0;

    @Configuration.Orientation
    private int mInheritedOrientation = ORIENTATION_UNDEFINED;

    // The app compat state for the opaque activity if any
    private int mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;

    // Counter for ActivityRecord#setRequestedOrientation
    private int mSetOrientationRequestCounter = 0;

    // The CompatDisplayInsets of the opaque activity beneath the translucent one.
    private ActivityRecord.CompatDisplayInsets mInheritedCompatDisplayInsets;

    @Nullable
    private Letterbox mLetterbox;

    @Nullable
    private final Boolean mBooleanPropertyCameraCompatAllowForceRotation;

    @Nullable
    private final Boolean mBooleanPropertyCameraCompatAllowRefresh;

    @Nullable
    private final Boolean mBooleanPropertyCameraCompatEnableRefreshViaPause;

    // Whether activity "refresh" was requested but not finished in
    // ActivityRecord#activityResumedLocked following the camera compat force rotation in
    // DisplayRotationCompatPolicy.
    private boolean mIsRefreshAfterRotationRequested;

    @Nullable
    private final Boolean mBooleanPropertyIgnoreRequestedOrientation;

    @Nullable
    private final Boolean mBooleanPropertyAllowIgnoringOrientationRequestWhenLoopDetected;

    @Nullable
    private final Boolean mBooleanPropertyFakeFocus;

    private boolean mIsRelaunchingAfterRequestedOrientationChanged;

    private boolean mLastShouldShowLetterboxUi;

    private boolean mDoubleTapEvent;

    LetterboxUiController(WindowManagerService wmService, ActivityRecord activityRecord) {
        mLetterboxConfiguration = wmService.mLetterboxConfiguration;
        // Given activityRecord may not be fully constructed since LetterboxUiController
        // is created in its constructor. It shouldn't be used in this constructor but it's safe
        // to use it after since controller is only used in ActivityRecord.
        mActivityRecord = activityRecord;

        PackageManager packageManager = wmService.mContext.getPackageManager();
        mBooleanPropertyIgnoreRequestedOrientation =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        mLetterboxConfiguration::isPolicyForIgnoringRequestedOrientationEnabled,
                        PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION);
        mBooleanPropertyAllowIgnoringOrientationRequestWhenLoopDetected =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        mLetterboxConfiguration::isPolicyForIgnoringRequestedOrientationEnabled,
                        PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED);
        mBooleanPropertyFakeFocus =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        mLetterboxConfiguration::isCompatFakeFocusEnabled,
                        PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
        mBooleanPropertyCameraCompatAllowForceRotation =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        () -> mLetterboxConfiguration.isCameraCompatTreatmentEnabled(
                                /* checkDeviceConfig */ true),
                        PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION);
        mBooleanPropertyCameraCompatAllowRefresh =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        () -> mLetterboxConfiguration.isCameraCompatTreatmentEnabled(
                                /* checkDeviceConfig */ true),
                        PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH);
        mBooleanPropertyCameraCompatEnableRefreshViaPause =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        () -> mLetterboxConfiguration.isCameraCompatTreatmentEnabled(
                                /* checkDeviceConfig */ true),
                        PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE);

        mExpandedTaskBarHeight =
                getResources().getDimensionPixelSize(R.dimen.taskbar_frame_height);

        mBooleanPropertyAllowOrientationOverride =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        /* gatingCondition */ null,
                        PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);
        mBooleanPropertyAllowDisplayOrientationOverride =
                readComponentProperty(packageManager, mActivityRecord.packageName,
                        /* gatingCondition */ null,
                        PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE);

        mIsOverrideAnyOrientationEnabled = isCompatChangeEnabled(OVERRIDE_ANY_ORIENTATION);
        mIsOverrideToPortraitOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT);
        mIsOverrideToReverseLandscapeOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE);
        mIsOverrideToNosensorOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR);
        mIsOverrideOrientationOnlyForCameraEnabled =
                isCompatChangeEnabled(OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA);
        mIsOverrideUseDisplayLandscapeNaturalOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION);
        mIsOverrideRespectRequestedOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_RESPECT_REQUESTED_ORIENTATION);

        mIsOverrideCameraCompatDisableForceRotationEnabled =
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION);
        mIsOverrideCameraCompatDisableRefreshEnabled =
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH);
        mIsOverrideCameraCompatEnableRefreshViaPauseEnabled =
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE);

        mIsOverrideEnableCompatIgnoreRequestedOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION);
        mIsOverrideEnableCompatIgnoreOrientationRequestWhenLoopDetectedEnabled =
                isCompatChangeEnabled(
                        OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED);

        mIsOverrideEnableCompatFakeFocusEnabled =
                isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS);
    }

    /**
     * Reads a {@link Boolean} component property fot a given {@code packageName} and a {@code
     * propertyName}. Returns {@code null} if {@code gatingCondition} is {@code false} or if the
     * property isn't specified for the package.
     *
     * <p>Return value is {@link Boolean} rather than {@code boolean} so we can know when the
     * property is unset. Particularly, when this returns {@code null}, {@link
     * #shouldEnableWithOverrideAndProperty} will check the value of override for the final
     * decision.
     */
    @Nullable
    private static Boolean readComponentProperty(PackageManager packageManager, String packageName,
            @Nullable BooleanSupplier gatingCondition, String propertyName) {
        if (gatingCondition != null && !gatingCondition.getAsBoolean()) {
            return null;
        }
        try {
            return packageManager.getProperty(propertyName, packageName).getBoolean();
        } catch (PackageManager.NameNotFoundException e) {
            // No such property name.
        }
        return null;
    }

    /** Cleans up {@link Letterbox} if it exists.*/
    void destroy() {
        if (mLetterbox != null) {
            mLetterbox.destroy();
            mLetterbox = null;
        }
        if (mLetterboxConfigListener != null) {
            mLetterboxConfigListener.onRemoved();
            mLetterboxConfigListener = null;
        }
    }

    void onMovedToDisplay(int displayId) {
        if (mLetterbox != null) {
            mLetterbox.onMovedToDisplay(displayId);
        }
    }

    /**
     * Whether should ignore app requested orientation in response to an app
     * calling {@link android.app.Activity#setRequestedOrientation}.
     *
     * <p>This is needed to avoid getting into {@link android.app.Activity#setRequestedOrientation}
     * loop when {@link DisplayContent#getIgnoreOrientationRequest} is enabled or device has
     * landscape natural orientation which app developers don't expect. For example, the loop can
     * look like this:
     * <ol>
     *     <li>App sets default orientation to "unspecified" at runtime
     *     <li>App requests to "portrait" after checking some condition (e.g. display rotation).
     *     <li>(2) leads to fullscreen -> letterboxed bounds change and activity relaunch because
     *     app can't handle the corresponding config changes.
     *     <li>Loop goes back to (1)
     * </ol>
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the treatment is enabled
     *     <li>Opt-out component property isn't enabled
     *     <li>Opt-in component property or per-app override are enabled
     *     <li>Activity is relaunched after {@link android.app.Activity#setRequestedOrientation}
     *     call from an app or camera compat force rotation treatment is active for the activity.
     *     <li>Orientation request loop detected and is not letterboxed for fixed orientation
     * </ul>
     */
    boolean shouldIgnoreRequestedOrientation(@ScreenOrientation int requestedOrientation) {
        if (shouldEnableWithOverrideAndProperty(
                /* gatingCondition */ mLetterboxConfiguration
                        ::isPolicyForIgnoringRequestedOrientationEnabled,
                mIsOverrideEnableCompatIgnoreRequestedOrientationEnabled,
                mBooleanPropertyIgnoreRequestedOrientation)) {
            if (mIsRelaunchingAfterRequestedOrientationChanged) {
                Slog.w(TAG, "Ignoring orientation update to "
                        + screenOrientationToString(requestedOrientation)
                        + " due to relaunching after setRequestedOrientation for "
                        + mActivityRecord);
                return true;
            }
            if (isCameraCompatTreatmentActive()) {
                Slog.w(TAG, "Ignoring orientation update to "
                        + screenOrientationToString(requestedOrientation)
                        + " due to camera compat treatment for " + mActivityRecord);
                return true;
            }
        }

        if (shouldIgnoreOrientationRequestLoop()) {
            Slog.w(TAG, "Ignoring orientation update to "
                    + screenOrientationToString(requestedOrientation)
                    + " as orientation request loop was detected for "
                    + mActivityRecord);
            return true;
        }
        return false;
    }

    /**
     * Whether an app is calling {@link android.app.Activity#setRequestedOrientation}
     * in a loop and orientation request should be ignored.
     *
     * <p>This should only be called once in response to
     * {@link android.app.Activity#setRequestedOrientation}. See
     * {@link #shouldIgnoreRequestedOrientation} for more details.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the treatment is enabled
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     *     <li>App has requested orientation more than 2 times within 1-second
     *     timer and activity is not letterboxed for fixed orientation
     * </ul>
     */
    @VisibleForTesting
    boolean shouldIgnoreOrientationRequestLoop() {
        if (!shouldEnableWithOptInOverrideAndOptOutProperty(
                /* gatingCondition */ mLetterboxConfiguration
                    ::isPolicyForIgnoringRequestedOrientationEnabled,
                mIsOverrideEnableCompatIgnoreOrientationRequestWhenLoopDetectedEnabled,
                mBooleanPropertyAllowIgnoringOrientationRequestWhenLoopDetected)) {
            return false;
        }

        final long currTimeMs = System.currentTimeMillis();
        if (currTimeMs - mTimeMsLastSetOrientationRequest
                < SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS) {
            mSetOrientationRequestCounter += 1;
        } else {
            // Resets app setOrientationRequest counter if timed out
            mSetOrientationRequestCounter = 0;
        }
        // Update time last called
        mTimeMsLastSetOrientationRequest = currTimeMs;

        return mSetOrientationRequestCounter >= MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP
                && !mActivityRecord.isLetterboxedForFixedOrientationAndAspectRatio();
    }

    @VisibleForTesting
    int getSetOrientationRequestCounter() {
        return mSetOrientationRequestCounter;
    }

    /**
     * Whether sending compat fake focus for split screen resumed activities is enabled. Needed
     * because some game engines wait to get focus before drawing the content of the app which isn't
     * guaranteed by default in multi-window modes.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the treatment is enabled
     *     <li>Component property is NOT set to false
     *     <li>Component property is set to true or per-app override is enabled
     * </ul>
     */
    boolean shouldSendFakeFocus() {
        return shouldEnableWithOverrideAndProperty(
                /* gatingCondition */ mLetterboxConfiguration::isCompatFakeFocusEnabled,
                mIsOverrideEnableCompatFakeFocusEnabled,
                mBooleanPropertyFakeFocus);
    }

    /**
     * Sets whether an activity is relaunching after the app has called {@link
     * android.app.Activity#setRequestedOrientation}.
     */
    void setRelaunchingAfterRequestedOrientationChanged(boolean isRelaunching) {
        mIsRelaunchingAfterRequestedOrientationChanged = isRelaunching;
    }

    /**
     * Whether activity "refresh" was requested but not finished in {@link #activityResumedLocked}
     * following the camera compat force rotation in {@link DisplayRotationCompatPolicy}.
     */
    boolean isRefreshAfterRotationRequested() {
        return mIsRefreshAfterRotationRequested;
    }

    void setIsRefreshAfterRotationRequested(boolean isRequested) {
        mIsRefreshAfterRotationRequested = isRequested;
    }

    boolean isOverrideRespectRequestedOrientationEnabled() {
        return mIsOverrideRespectRequestedOrientationEnabled;
    }

    /**
     * Whether should fix display orientation to landscape natural orientation when a task is
     * fullscreen and the display is ignoring orientation requests.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Opt-in per-app override is enabled
     *     <li>Task is in fullscreen.
     *     <li>{@link DisplayContent#getIgnoreOrientationRequest} is enabled
     *     <li>Natural orientation of the display is landscape.
     * </ul>
     */
    boolean shouldUseDisplayLandscapeNaturalOrientation() {
        return shouldEnableWithOptInOverrideAndOptOutProperty(
                /* gatingCondition */ () -> mActivityRecord.mDisplayContent != null
                        && mActivityRecord.getTask() != null
                        && mActivityRecord.mDisplayContent.getIgnoreOrientationRequest()
                        && !mActivityRecord.getTask().inMultiWindowMode()
                        && mActivityRecord.mDisplayContent.getNaturalOrientation()
                                == ORIENTATION_LANDSCAPE,
                mIsOverrideUseDisplayLandscapeNaturalOrientationEnabled,
                mBooleanPropertyAllowDisplayOrientationOverride);
    }

    @ScreenOrientation
    int overrideOrientationIfNeeded(@ScreenOrientation int candidate) {
        // In some cases (e.g. Kids app) we need to map the candidate orientation to some other
        // orientation.
        candidate = mActivityRecord.mWmService.mapOrientationRequest(candidate);

        if (FALSE.equals(mBooleanPropertyAllowOrientationOverride)) {
            return candidate;
        }

        DisplayContent displayContent = mActivityRecord.mDisplayContent;
        if (mIsOverrideOrientationOnlyForCameraEnabled && displayContent != null
                && (displayContent.mDisplayRotationCompatPolicy == null
                        || !displayContent.mDisplayRotationCompatPolicy
                                .isActivityEligibleForOrientationOverride(mActivityRecord))) {
            return candidate;
        }

        if (mIsOverrideToReverseLandscapeOrientationEnabled
                && (isFixedOrientationLandscape(candidate) || mIsOverrideAnyOrientationEnabled)) {
            Slog.w(TAG, "Requested orientation  " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
            return SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }

        if (!mIsOverrideAnyOrientationEnabled && isFixedOrientation(candidate)) {
            return candidate;
        }

        if (mIsOverrideToPortraitOrientationEnabled) {
            Slog.w(TAG, "Requested orientation  " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_PORTRAIT));
            return SCREEN_ORIENTATION_PORTRAIT;
        }

        if (mIsOverrideToNosensorOrientationEnabled) {
            Slog.w(TAG, "Requested orientation  " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_NOSENSOR));
            return SCREEN_ORIENTATION_NOSENSOR;
        }

        return candidate;
    }

    boolean isOverrideOrientationOnlyForCameraEnabled() {
        return mIsOverrideOrientationOnlyForCameraEnabled;
    }

    /**
     * Whether activity is eligible for activity "refresh" after camera compat force rotation
     * treatment. See {@link DisplayRotationCompatPolicy} for context.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the camera compat treatment is enabled.
     *     <li>Activity isn't opted out by the device manufacturer with override or by the app
     *     developers with the component property.
     * </ul>
     */
    boolean shouldRefreshActivityForCameraCompat() {
        return shouldEnableWithOptOutOverrideAndProperty(
                /* gatingCondition */ () -> mLetterboxConfiguration
                        .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true),
                mIsOverrideCameraCompatDisableRefreshEnabled,
                mBooleanPropertyCameraCompatAllowRefresh);
    }

    /**
     * Whether activity should be "refreshed" after the camera compat force rotation treatment
     * using the "resumed -> paused -> resumed" cycle rather than the "resumed -> ... -> stopped
     * -> ... -> resumed" cycle. See {@link DisplayRotationCompatPolicy} for context.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the camera compat treatment is enabled.
     *     <li>Activity "refresh" via "resumed -> paused -> resumed" cycle isn't disabled with the
     *     component property by the app developers.
     *     <li>Activity "refresh" via "resumed -> paused -> resumed" cycle is enabled by the device
     *     manufacturer with override / by the app developers with the component property.
     * </ul>
     */
    boolean shouldRefreshActivityViaPauseForCameraCompat() {
        return shouldEnableWithOverrideAndProperty(
                /* gatingCondition */ () -> mLetterboxConfiguration
                        .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true),
                mIsOverrideCameraCompatEnableRefreshViaPauseEnabled,
                mBooleanPropertyCameraCompatEnableRefreshViaPause);
    }

    /**
     * Whether activity is eligible for camera compat force rotation treatment. See {@link
     * DisplayRotationCompatPolicy} for context.
     *
     * <p>This treatment is enabled when the following conditions are met:
     * <ul>
     *     <li>Flag gating the camera compat treatment is enabled.
     *     <li>Activity isn't opted out by the device manufacturer with override or by the app
     *     developers with the component property.
     * </ul>
     */
    boolean shouldForceRotateForCameraCompat() {
        return shouldEnableWithOptOutOverrideAndProperty(
                /* gatingCondition */ () -> mLetterboxConfiguration
                        .isCameraCompatTreatmentEnabled(/* checkDeviceConfig */ true),
                mIsOverrideCameraCompatDisableForceRotationEnabled,
                mBooleanPropertyCameraCompatAllowForceRotation);
    }

    private boolean isCameraCompatTreatmentActive() {
        DisplayContent displayContent = mActivityRecord.mDisplayContent;
        if (displayContent == null) {
            return false;
        }
        return displayContent.mDisplayRotationCompatPolicy != null
                && displayContent.mDisplayRotationCompatPolicy
                        .isTreatmentEnabledForActivity(mActivityRecord);
    }

    private boolean isCompatChangeEnabled(long overrideChangeId) {
        return mActivityRecord.info.isChangeEnabled(overrideChangeId);
    }

    /**
     * Returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>{@code gatingCondition} isn't {@code false}
     *     <li>OEM didn't opt out with a per-app override
     *     <li>App developers didn't opt out with a component {@code property}
     * </ul>
     *
     * <p>This is used for the treatments that are enabled based with the heuristic but can be
     * disabled on per-app basis by OEMs or app developers.
     */
    private boolean shouldEnableWithOptOutOverrideAndProperty(BooleanSupplier gatingCondition,
            boolean isOverrideChangeEnabled, Boolean property) {
        if (!gatingCondition.getAsBoolean()) {
            return false;
        }
        return !FALSE.equals(property) && !isOverrideChangeEnabled;
    }

    /**
     * Returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>{@code gatingCondition} isn't {@code false}
     *     <li>OEM did opt in with a per-app override
     *     <li>App developers didn't opt out with a component {@code property}
     * </ul>
     *
     * <p>This is used for the treatments that are enabled based with the heuristic but can be
     * disabled on per-app basis by OEMs or app developers.
     */
    private boolean shouldEnableWithOptInOverrideAndOptOutProperty(BooleanSupplier gatingCondition,
            boolean isOverrideChangeEnabled, Boolean property) {
        if (!gatingCondition.getAsBoolean()) {
            return false;
        }
        return !FALSE.equals(property) && isOverrideChangeEnabled;
    }

    /**
     * Returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>{@code gatingCondition} isn't {@code false}
     *     <li>App developers didn't opt out with a component {@code property}
     *     <li>App developers opted in with a component {@code property} or an OEM opted in with a
     *     per-app override
     * </ul>
     *
     * <p>This is used for the treatments that are enabled only on per-app basis.
     */
    private boolean shouldEnableWithOverrideAndProperty(BooleanSupplier gatingCondition,
            boolean isOverrideChangeEnabled, Boolean property) {
        if (!gatingCondition.getAsBoolean()) {
            return false;
        }
        if (FALSE.equals(property)) {
            return false;
        }
        return TRUE.equals(property) || isOverrideChangeEnabled;
    }

    boolean hasWallpaperBackgroundForLetterbox() {
        return mShowWallpaperForLetterboxBackground;
    }

    /** Gets the letterbox insets. The insets will be empty if there is no letterbox. */
    Rect getLetterboxInsets() {
        if (mLetterbox != null) {
            return mLetterbox.getInsets();
        } else {
            return new Rect();
        }
    }

    /** Gets the inner bounds of letterbox. The bounds will be empty if there is no letterbox. */
    void getLetterboxInnerBounds(Rect outBounds) {
        if (mLetterbox != null) {
            outBounds.set(mLetterbox.getInnerFrame());
            final WindowState w = mActivityRecord.findMainWindow();
            if (w != null) {
                adjustBoundsForTaskbar(w, outBounds);
            }
        } else {
            outBounds.setEmpty();
        }
    }

    /** Gets the outer bounds of letterbox. The bounds will be empty if there is no letterbox. */
    private void getLetterboxOuterBounds(Rect outBounds) {
        if (mLetterbox != null) {
            outBounds.set(mLetterbox.getOuterFrame());
        } else {
            outBounds.setEmpty();
        }
    }

    /**
     * @return {@code true} if bar shown within a given rectangle is allowed to be fully transparent
     *     when the current activity is displayed.
     */
    boolean isFullyTransparentBarAllowed(Rect rect) {
        return mLetterbox == null || mLetterbox.notIntersectsOrFullyContains(rect);
    }

    void updateLetterboxSurface(WindowState winHint) {
        updateLetterboxSurface(winHint, mActivityRecord.getSyncTransaction());
    }

    void updateLetterboxSurface(WindowState winHint, Transaction t) {
        final WindowState w = mActivityRecord.findMainWindow();
        if (w != winHint && winHint != null && w != null) {
            return;
        }
        layoutLetterbox(winHint);
        if (mLetterbox != null && mLetterbox.needsApplySurfaceChanges()) {
            mLetterbox.applySurfaceChanges(t);
        }
    }

    void layoutLetterbox(WindowState winHint) {
        final WindowState w = mActivityRecord.findMainWindow();
        if (w == null || winHint != null && w != winHint) {
            return;
        }
        updateRoundedCornersIfNeeded(w);
        // If there is another main window that is not an application-starting window, we should
        // update rounded corners for it as well, to avoid flickering rounded corners.
        final WindowState nonStartingAppW = mActivityRecord.findMainWindow(
                /* includeStartingApp= */ false);
        if (nonStartingAppW != null && nonStartingAppW != w) {
            updateRoundedCornersIfNeeded(nonStartingAppW);
        }

        updateWallpaperForLetterbox(w);
        if (shouldShowLetterboxUi(w)) {
            if (mLetterbox == null) {
                mLetterbox = new Letterbox(() -> mActivityRecord.makeChildSurface(null),
                        mActivityRecord.mWmService.mTransactionFactory,
                        this::shouldLetterboxHaveRoundedCorners,
                        this::getLetterboxBackgroundColor,
                        this::hasWallpaperBackgroundForLetterbox,
                        this::getLetterboxWallpaperBlurRadius,
                        this::getLetterboxWallpaperDarkScrimAlpha,
                        this::handleHorizontalDoubleTap,
                        this::handleVerticalDoubleTap,
                        this::getLetterboxParentSurface);
                mLetterbox.attachInput(w);
            }

            if (mActivityRecord.isInLetterboxAnimation()) {
                // In this case we attach the letterbox to the task instead of the activity.
                mActivityRecord.getTask().getPosition(mTmpPoint);
            } else {
                mActivityRecord.getPosition(mTmpPoint);
            }

            // Get the bounds of the "space-to-fill". The transformed bounds have the highest
            // priority because the activity is launched in a rotated environment. In multi-window
            // mode, the task-level represents this. In fullscreen-mode, the task container does
            // (since the orientation letterbox is also applied to the task).
            final Rect transformedBounds = mActivityRecord.getFixedRotationTransformDisplayBounds();
            final Rect spaceToFill = transformedBounds != null
                    ? transformedBounds
                    : mActivityRecord.inMultiWindowMode()
                            ? mActivityRecord.getTask().getBounds()
                            : mActivityRecord.getRootTask().getParent().getBounds();
            // In case of translucent activities an option is to use the WindowState#getFrame() of
            // the first opaque activity beneath. In some cases (e.g. an opaque activity is using
            // non MATCH_PARENT layouts or a Dialog theme) this might not provide the correct
            // information and in particular it might provide a value for a smaller area making
            // the letterbox overlap with the translucent activity's frame.
            // If we use WindowState#getFrame() for the translucent activity's letterbox inner
            // frame, the letterbox will then be overlapped with the translucent activity's frame.
            // Because the surface layer of letterbox is lower than an activity window, this
            // won't crop the content, but it may affect other features that rely on values stored
            // in mLetterbox, e.g. transitions, a status bar scrim and recents preview in Launcher
            // For this reason we use ActivityRecord#getBounds() that the translucent activity
            // inherits from the first opaque activity beneath and also takes care of the scaling
            // in case of activities in size compat mode.
            final Rect innerFrame = hasInheritedLetterboxBehavior()
                    ? mActivityRecord.getBounds() : w.getFrame();
            mLetterbox.layout(spaceToFill, innerFrame, mTmpPoint);
            // We need to notify Shell that letterbox position has changed.
            mActivityRecord.getTask().dispatchTaskInfoChangedIfNeeded(true /* force */);
        } else if (mLetterbox != null) {
            mLetterbox.hide();
        }
    }

    boolean isFromDoubleTap() {
        final boolean isFromDoubleTap = mDoubleTapEvent;
        mDoubleTapEvent = false;
        return isFromDoubleTap;
    }

    SurfaceControl getLetterboxParentSurface() {
        if (mActivityRecord.isInLetterboxAnimation()) {
            return mActivityRecord.getTask().getSurfaceControl();
        }
        return mActivityRecord.getSurfaceControl();
    }

    private boolean shouldLetterboxHaveRoundedCorners() {
        // TODO(b/214030873): remove once background is drawn for transparent activities
        // Letterbox shouldn't have rounded corners if the activity is transparent
        return mLetterboxConfiguration.isLetterboxActivityCornersRounded()
                && mActivityRecord.fillsParent();
    }

    // Check if we are in the given pose and in fullscreen mode.
    // Note that we check the task rather than the parent as with ActivityEmbedding the parent might
    // be a TaskFragment, and its windowing mode is always MULTI_WINDOW, even if the task is
    // actually fullscreen.
    private boolean isDisplayFullScreenAndInPosture(DeviceStateController.DeviceState state,
            boolean isTabletop) {
        Task task = mActivityRecord.getTask();
        return mActivityRecord.mDisplayContent != null
                && mActivityRecord.mDisplayContent.getDisplayRotation().isDeviceInPosture(state,
                    isTabletop)
                && task != null
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }

    // Note that we check the task rather than the parent as with ActivityEmbedding the parent might
    // be a TaskFragment, and its windowing mode is always MULTI_WINDOW, even if the task is
    // actually fullscreen.
    private boolean isDisplayFullScreenAndSeparatingHinge() {
        Task task = mActivityRecord.getTask();
        return mActivityRecord.mDisplayContent != null
                && mActivityRecord.mDisplayContent.getDisplayRotation().isDisplaySeparatingHinge()
                && task != null
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }


    float getHorizontalPositionMultiplier(Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean bookModeEnabled = isFullScreenAndBookModeEnabled();
        return isHorizontalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mLetterboxConfiguration.getHorizontalMultiplierForReachability(bookModeEnabled)
                : mLetterboxConfiguration.getLetterboxHorizontalPositionMultiplier(bookModeEnabled);
    }

    private boolean isFullScreenAndBookModeEnabled() {
        return isDisplayFullScreenAndInPosture(
                DeviceStateController.DeviceState.HALF_FOLDED, false /* isTabletop */)
                && mLetterboxConfiguration.getIsAutomaticReachabilityInBookModeEnabled();
    }

    float getVerticalPositionMultiplier(Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean tabletopMode = isDisplayFullScreenAndInPosture(
                DeviceStateController.DeviceState.HALF_FOLDED, true /* isTabletop */);
        return isVerticalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mLetterboxConfiguration.getVerticalMultiplierForReachability(tabletopMode)
                : mLetterboxConfiguration.getLetterboxVerticalPositionMultiplier(tabletopMode);
    }

    float getFixedOrientationLetterboxAspectRatio(@NonNull Configuration parentConfiguration) {
        return shouldUseSplitScreenAspectRatio(parentConfiguration)
                ? getSplitScreenAspectRatio()
                : mActivityRecord.shouldCreateCompatDisplayInsets()
                        ? getDefaultMinAspectRatioForUnresizableApps()
                        : getDefaultMinAspectRatio();
    }

    void recomputeConfigurationForCameraCompatIfNeeded() {
        if (isOverrideOrientationOnlyForCameraEnabled()
                || isCameraCompatSplitScreenAspectRatioAllowed()) {
            mActivityRecord.recomputeConfiguration();
        }
    }

    /**
     * Whether we use split screen aspect ratio for the activity when camera compat treatment
     * is active because the corresponding config is enabled and activity supports resizing.
     */
    private boolean isCameraCompatSplitScreenAspectRatioAllowed() {
        return mLetterboxConfiguration.isCameraCompatSplitScreenAspectRatioEnabled()
                && !mActivityRecord.shouldCreateCompatDisplayInsets();
    }

    private boolean shouldUseSplitScreenAspectRatio(@NonNull Configuration parentConfiguration) {
        final boolean isBookMode = isDisplayFullScreenAndInPosture(
                DeviceStateController.DeviceState.HALF_FOLDED,
                /* isTabletop */ false);
        final boolean isNotCenteredHorizontally = getHorizontalPositionMultiplier(
                parentConfiguration) != LETTERBOX_POSITION_MULTIPLIER_CENTER;
        final boolean isTabletopMode = isDisplayFullScreenAndInPosture(
                DeviceStateController.DeviceState.HALF_FOLDED,
                /* isTabletop */ true);
        // Don't resize to split screen size when in book mode if letterbox position is centered
        return ((isBookMode && isNotCenteredHorizontally) || isTabletopMode)
                    || isCameraCompatSplitScreenAspectRatioAllowed()
                        && isCameraCompatTreatmentActive();
    }

    private float getDefaultMinAspectRatioForUnresizableApps() {
        if (!mLetterboxConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled()
                || mActivityRecord.getDisplayContent() == null) {
            return mLetterboxConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                    > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO
                            ? mLetterboxConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                            : getDefaultMinAspectRatio();
        }

        return getSplitScreenAspectRatio();
    }

    float getSplitScreenAspectRatio() {
        // Getting the same aspect ratio that apps get in split screen.
        final DisplayContent displayContent = mActivityRecord.getDisplayContent();
        if (displayContent == null) {
            return getDefaultMinAspectRatioForUnresizableApps();
        }
        int dividerWindowWidth =
                getResources().getDimensionPixelSize(R.dimen.docked_stack_divider_thickness);
        int dividerInsets =
                getResources().getDimensionPixelSize(R.dimen.docked_stack_divider_insets);
        int dividerSize = dividerWindowWidth - dividerInsets * 2;
        final Rect bounds = new Rect(displayContent.getWindowConfiguration().getAppBounds());
        if (bounds.width() >= bounds.height()) {
            bounds.inset(/* dx */ dividerSize / 2, /* dy */ 0);
            bounds.right = bounds.centerX();
        } else {
            bounds.inset(/* dx */ 0, /* dy */ dividerSize / 2);
            bounds.bottom = bounds.centerY();
        }
        return computeAspectRatio(bounds);
    }

    private float getDefaultMinAspectRatio() {
        final DisplayContent displayContent = mActivityRecord.getDisplayContent();
        if (displayContent == null
                || !mLetterboxConfiguration
                    .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox()) {
            return mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio();
        }
        return computeAspectRatio(new Rect(displayContent.getBounds()));
    }

    Resources getResources() {
        return mActivityRecord.mWmService.mContext.getResources();
    }

    @LetterboxConfiguration.LetterboxVerticalReachabilityPosition
    int getLetterboxPositionForVerticalReachability() {
        final boolean isInFullScreenTabletopMode = isDisplayFullScreenAndSeparatingHinge();
        return mLetterboxConfiguration.getLetterboxPositionForVerticalReachability(
                isInFullScreenTabletopMode);
    }

    @LetterboxConfiguration.LetterboxHorizontalReachabilityPosition
    int getLetterboxPositionForHorizontalReachability() {
        final boolean isInFullScreenBookMode = isFullScreenAndBookModeEnabled();
        return mLetterboxConfiguration.getLetterboxPositionForHorizontalReachability(
                isInFullScreenBookMode);
    }

    @VisibleForTesting
    void handleHorizontalDoubleTap(int x) {
        if (!isHorizontalReachabilityEnabled() || mActivityRecord.isInTransition()) {
            return;
        }

        if (mLetterbox.getInnerFrame().left <= x && mLetterbox.getInnerFrame().right >= x) {
            // Only react to clicks at the sides of the letterboxed app window.
            return;
        }

        boolean isInFullScreenBookMode = isDisplayFullScreenAndSeparatingHinge()
                && mLetterboxConfiguration.getIsAutomaticReachabilityInBookModeEnabled();
        int letterboxPositionForHorizontalReachability = mLetterboxConfiguration
                .getLetterboxPositionForHorizontalReachability(isInFullScreenBookMode);
        if (mLetterbox.getInnerFrame().left > x) {
            // Moving to the next stop on the left side of the app window: right > center > left.
            mLetterboxConfiguration.movePositionForHorizontalReachabilityToNextLeftStop(
                    isInFullScreenBookMode);
            int changeToLog =
                    letterboxPositionForHorizontalReachability
                            == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_LEFT
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__RIGHT_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
        } else if (mLetterbox.getInnerFrame().right < x) {
            // Moving to the next stop on the right side of the app window: left > center > right.
            mLetterboxConfiguration.movePositionForHorizontalReachabilityToNextRightStop(
                    isInFullScreenBookMode);
            int changeToLog =
                    letterboxPositionForHorizontalReachability
                            == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_RIGHT
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__LEFT_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
        }
        mDoubleTapEvent = true;
        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    @VisibleForTesting
    void handleVerticalDoubleTap(int y) {
        if (!isVerticalReachabilityEnabled() || mActivityRecord.isInTransition()) {
            return;
        }

        if (mLetterbox.getInnerFrame().top <= y && mLetterbox.getInnerFrame().bottom >= y) {
            // Only react to clicks at the top and bottom of the letterboxed app window.
            return;
        }
        boolean isInFullScreenTabletopMode = isDisplayFullScreenAndSeparatingHinge();
        int letterboxPositionForVerticalReachability = mLetterboxConfiguration
                .getLetterboxPositionForVerticalReachability(isInFullScreenTabletopMode);
        if (mLetterbox.getInnerFrame().top > y) {
            // Moving to the next stop on the top side of the app window: bottom > center > top.
            mLetterboxConfiguration.movePositionForVerticalReachabilityToNextTopStop(
                    isInFullScreenTabletopMode);
            int changeToLog =
                    letterboxPositionForVerticalReachability
                            == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_TOP
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__BOTTOM_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
        } else if (mLetterbox.getInnerFrame().bottom < y) {
            // Moving to the next stop on the bottom side of the app window: top > center > bottom.
            mLetterboxConfiguration.movePositionForVerticalReachabilityToNextBottomStop(
                    isInFullScreenTabletopMode);
            int changeToLog =
                    letterboxPositionForVerticalReachability
                            == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_BOTTOM
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__TOP_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
        }
        mDoubleTapEvent = true;
        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    /**
     * Whether horizontal reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Activity is portrait-only.
     *   <li>Fullscreen window in landscape device orientation.
     *   <li>Horizontal Reachability is enabled.
     *   <li>Activity fills parent vertically.
     * </ul>
     */
    private boolean isHorizontalReachabilityEnabled(Configuration parentConfiguration) {
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty
        return mLetterboxConfiguration.getIsHorizontalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                && (parentConfiguration.orientation == ORIENTATION_LANDSCAPE
                        && mActivityRecord.getOrientationForReachability() == ORIENTATION_PORTRAIT)
                // Check whether the activity fills the parent vertically.
                && parentConfiguration.windowConfiguration.getAppBounds().height()
                        <= mActivityRecord.getScreenResolvedBounds().height();
    }

    @VisibleForTesting
    boolean isHorizontalReachabilityEnabled() {
        return isHorizontalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    boolean isLetterboxDoubleTapEducationEnabled() {
        return isHorizontalReachabilityEnabled() || isVerticalReachabilityEnabled();
    }

    /**
     * Whether vertical reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Activity is landscape-only.
     *   <li>Fullscreen window in portrait device orientation.
     *   <li>Vertical Reachability is enabled.
     *   <li>Activity fills parent horizontally.
     * </ul>
     */
    private boolean isVerticalReachabilityEnabled(Configuration parentConfiguration) {
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty
        return mLetterboxConfiguration.getIsVerticalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                && (parentConfiguration.orientation == ORIENTATION_PORTRAIT
                        && mActivityRecord.getOrientationForReachability() == ORIENTATION_LANDSCAPE)
                // Check whether the activity fills the parent horizontally.
                && parentConfiguration.windowConfiguration.getBounds().width()
                        == mActivityRecord.getScreenResolvedBounds().width();
    }

    @VisibleForTesting
    boolean isVerticalReachabilityEnabled() {
        return isVerticalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    @VisibleForTesting
    boolean shouldShowLetterboxUi(WindowState mainWindow) {
        if (mIsRelaunchingAfterRequestedOrientationChanged || !isSurfaceReadyToShow(mainWindow)) {
            return mLastShouldShowLetterboxUi;
        }

        final boolean shouldShowLetterboxUi =
                (mActivityRecord.isInLetterboxAnimation() || isSurfaceVisible(mainWindow))
                && mainWindow.areAppWindowBoundsLetterboxed()
                // Check for FLAG_SHOW_WALLPAPER explicitly instead of using
                // WindowContainer#showWallpaper because the later will return true when this
                // activity is using blurred wallpaper for letterbox background.
                && (mainWindow.getAttrs().flags & FLAG_SHOW_WALLPAPER) == 0;

        mLastShouldShowLetterboxUi = shouldShowLetterboxUi;

        return shouldShowLetterboxUi;
    }

    @VisibleForTesting
    boolean isSurfaceReadyToShow(WindowState mainWindow) {
        return mainWindow.isDrawn() // Regular case
                // Waiting for relayoutWindow to call preserveSurface
                || mainWindow.isDragResizeChanged();
    }

    @VisibleForTesting
    boolean isSurfaceVisible(WindowState mainWindow) {
        return mainWindow.isOnScreen() && (mActivityRecord.isVisible()
                || mActivityRecord.isVisibleRequested());
    }

    private Color getLetterboxBackgroundColor() {
        final WindowState w = mActivityRecord.findMainWindow();
        if (w == null || w.isLetterboxedForDisplayCutout()) {
            return Color.valueOf(Color.BLACK);
        }
        @LetterboxBackgroundType int letterboxBackgroundType =
                mLetterboxConfiguration.getLetterboxBackgroundType();
        TaskDescription taskDescription = mActivityRecord.taskDescription;
        switch (letterboxBackgroundType) {
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING:
                if (taskDescription != null && taskDescription.getBackgroundColorFloating() != 0) {
                    return Color.valueOf(taskDescription.getBackgroundColorFloating());
                }
                break;
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND:
                if (taskDescription != null && taskDescription.getBackgroundColor() != 0) {
                    return Color.valueOf(taskDescription.getBackgroundColor());
                }
                break;
            case LETTERBOX_BACKGROUND_WALLPAPER:
                if (hasWallpaperBackgroundForLetterbox()) {
                    // Color is used for translucent scrim that dims wallpaper.
                    return Color.valueOf(Color.BLACK);
                }
                Slog.w(TAG, "Wallpaper option is selected for letterbox background but "
                        + "blur is not supported by a device or not supported in the current "
                        + "window configuration or both alpha scrim and blur radius aren't "
                        + "provided so using solid color background");
                break;
            case LETTERBOX_BACKGROUND_SOLID_COLOR:
                return mLetterboxConfiguration.getLetterboxBackgroundColor();
            default:
                throw new AssertionError(
                    "Unexpected letterbox background type: " + letterboxBackgroundType);
        }
        // If picked option configured incorrectly or not supported then default to a solid color
        // background.
        return mLetterboxConfiguration.getLetterboxBackgroundColor();
    }

    private void updateRoundedCornersIfNeeded(final WindowState mainWindow) {
        final SurfaceControl windowSurface = mainWindow.getSurfaceControl();
        if (windowSurface == null || !windowSurface.isValid()) {
            return;
        }

        // cropBounds must be non-null for the cornerRadius to be ever applied.
        mActivityRecord.getSyncTransaction()
                .setCrop(windowSurface, getCropBoundsIfNeeded(mainWindow))
                .setCornerRadius(windowSurface, getRoundedCornersRadius(mainWindow));
    }

    @VisibleForTesting
    @Nullable
    Rect getCropBoundsIfNeeded(final WindowState mainWindow) {
        if (!requiresRoundedCorners(mainWindow) || mActivityRecord.isInLetterboxAnimation()) {
            // We don't want corner radius on the window.
            // In the case the ActivityRecord requires a letterboxed animation we never want
            // rounded corners on the window because rounded corners are applied at the
            // animation-bounds surface level and rounded corners on the window would interfere
            // with that leading to unexpected rounded corner positioning during the animation.
            return null;
        }

        final Rect cropBounds = new Rect(mActivityRecord.getBounds());

        // It is important to call {@link #adjustBoundsIfNeeded} before {@link cropBounds.offsetTo}
        // because taskbar bounds used in {@link #adjustBoundsIfNeeded}
        // are in screen coordinates
        adjustBoundsForTaskbar(mainWindow, cropBounds);

        final float scale = mainWindow.mInvGlobalScale;
        if (scale != 1f && scale > 0f) {
            cropBounds.scale(scale);
        }

        // ActivityRecord bounds are in screen coordinates while (0,0) for activity's surface
        // control is in the top left corner of an app window so offsetting bounds
        // accordingly.
        cropBounds.offsetTo(0, 0);
        return cropBounds;
    }

    private boolean requiresRoundedCorners(final WindowState mainWindow) {
        return isLetterboxedNotForDisplayCutout(mainWindow)
                && mLetterboxConfiguration.isLetterboxActivityCornersRounded();
    }

    // Returns rounded corners radius the letterboxed activity should have based on override in
    // R.integer.config_letterboxActivityCornersRadius or min device bottom corner radii.
    // Device corners can be different on the right and left sides, but we use the same radius
    // for all corners for consistency and pick a minimal bottom one for consistency with a
    // taskbar rounded corners.
    int getRoundedCornersRadius(final WindowState mainWindow) {
        if (!requiresRoundedCorners(mainWindow)) {
            return 0;
        }

        final int radius;
        if (mLetterboxConfiguration.getLetterboxActivityCornersRadius() >= 0) {
            radius = mLetterboxConfiguration.getLetterboxActivityCornersRadius();
        } else {
            final InsetsState insetsState = mainWindow.getInsetsState();
            radius = Math.min(
                    getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_LEFT),
                    getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_RIGHT));
        }

        final float scale = mainWindow.mInvGlobalScale;
        return (scale != 1f && scale > 0f) ? (int) (scale * radius) : radius;
    }

    /**
     * Returns the taskbar in case it is visible and expanded in height, otherwise returns null.
     */
    @VisibleForTesting
    @Nullable
    InsetsSource getExpandedTaskbarOrNull(final WindowState mainWindow) {
        final InsetsSource taskbar = mainWindow.getInsetsState().peekSource(
                InsetsState.ITYPE_EXTRA_NAVIGATION_BAR);
        if (taskbar != null && taskbar.isVisible()
                && taskbar.getFrame().height() >= mExpandedTaskBarHeight) {
            return taskbar;
        }
        return null;
    }

    boolean getIsRelaunchingAfterRequestedOrientationChanged() {
        return mIsRelaunchingAfterRequestedOrientationChanged;
    }

    private void adjustBoundsForTaskbar(final WindowState mainWindow, final Rect bounds) {
        // Rounded corners should be displayed above the taskbar. When taskbar is hidden,
        // an insets frame is equal to a navigation bar which shouldn't affect position of
        // rounded corners since apps are expected to handle navigation bar inset.
        // This condition checks whether the taskbar is visible.
        // Do not crop the taskbar inset if the window is in immersive mode - the user can
        // swipe to show/hide the taskbar as an overlay.
        // Adjust the bounds only in case there is an expanded taskbar,
        // otherwise the rounded corners will be shown behind the navbar.
        final InsetsSource expandedTaskbarOrNull = getExpandedTaskbarOrNull(mainWindow);
        if (expandedTaskbarOrNull != null) {
            // Rounded corners should be displayed above the expanded taskbar.
            bounds.bottom = Math.min(bounds.bottom, expandedTaskbarOrNull.getFrame().top);
        }
    }

    private int getInsetsStateCornerRadius(
                InsetsState insetsState, @RoundedCorner.Position int position) {
        RoundedCorner corner = insetsState.getRoundedCorners().getRoundedCorner(position);
        return corner == null ? 0 : corner.getRadius();
    }

    private boolean isLetterboxedNotForDisplayCutout(WindowState mainWindow) {
        return shouldShowLetterboxUi(mainWindow)
                && !mainWindow.isLetterboxedForDisplayCutout();
    }

    private void updateWallpaperForLetterbox(WindowState mainWindow) {
        @LetterboxBackgroundType int letterboxBackgroundType =
                mLetterboxConfiguration.getLetterboxBackgroundType();
        boolean wallpaperShouldBeShown =
                letterboxBackgroundType == LETTERBOX_BACKGROUND_WALLPAPER
                        // Don't use wallpaper as a background if letterboxed for display cutout.
                        && isLetterboxedNotForDisplayCutout(mainWindow)
                        // Check that dark scrim alpha or blur radius are provided
                        && (getLetterboxWallpaperBlurRadius() > 0
                                || getLetterboxWallpaperDarkScrimAlpha() > 0)
                        // Check that blur is supported by a device if blur radius is provided.
                        && (getLetterboxWallpaperBlurRadius() <= 0
                                || isLetterboxWallpaperBlurSupported());
        if (mShowWallpaperForLetterboxBackground != wallpaperShouldBeShown) {
            mShowWallpaperForLetterboxBackground = wallpaperShouldBeShown;
            mActivityRecord.requestUpdateWallpaperIfNeeded();
        }
    }

    private int getLetterboxWallpaperBlurRadius() {
        int blurRadius = mLetterboxConfiguration.getLetterboxBackgroundWallpaperBlurRadius();
        return blurRadius < 0 ? 0 : blurRadius;
    }

    private float getLetterboxWallpaperDarkScrimAlpha() {
        float alpha = mLetterboxConfiguration.getLetterboxBackgroundWallpaperDarkScrimAlpha();
        // No scrim by default.
        return (alpha < 0 || alpha >= 1) ? 0.0f : alpha;
    }

    private boolean isLetterboxWallpaperBlurSupported() {
        return mLetterboxConfiguration.mContext.getSystemService(WindowManager.class)
                .isCrossWindowBlurEnabled();
    }

    void dump(PrintWriter pw, String prefix) {
        final WindowState mainWin = mActivityRecord.findMainWindow();
        if (mainWin == null) {
            return;
        }

        boolean areBoundsLetterboxed = mainWin.areAppWindowBoundsLetterboxed();
        pw.println(prefix + "areBoundsLetterboxed=" + areBoundsLetterboxed);
        if (!areBoundsLetterboxed) {
            return;
        }

        pw.println(prefix + "  letterboxReason=" + getLetterboxReasonString(mainWin));
        pw.println(prefix + "  activityAspectRatio="
                + mActivityRecord.computeAspectRatio(mActivityRecord.getBounds()));

        boolean shouldShowLetterboxUi = shouldShowLetterboxUi(mainWin);
        pw.println(prefix + "shouldShowLetterboxUi=" + shouldShowLetterboxUi);

        if (!shouldShowLetterboxUi) {
            return;
        }
        pw.println(prefix + "  letterboxBackgroundColor=" + Integer.toHexString(
                getLetterboxBackgroundColor().toArgb()));
        pw.println(prefix + "  letterboxBackgroundType="
                + letterboxBackgroundTypeToString(
                        mLetterboxConfiguration.getLetterboxBackgroundType()));
        pw.println(prefix + "  letterboxCornerRadius="
                + getRoundedCornersRadius(mainWin));
        if (mLetterboxConfiguration.getLetterboxBackgroundType()
                == LETTERBOX_BACKGROUND_WALLPAPER) {
            pw.println(prefix + "  isLetterboxWallpaperBlurSupported="
                    + isLetterboxWallpaperBlurSupported());
            pw.println(prefix + "  letterboxBackgroundWallpaperDarkScrimAlpha="
                    + getLetterboxWallpaperDarkScrimAlpha());
            pw.println(prefix + "  letterboxBackgroundWallpaperBlurRadius="
                    + getLetterboxWallpaperBlurRadius());
        }

        pw.println(prefix + "  isHorizontalReachabilityEnabled="
                + isHorizontalReachabilityEnabled());
        pw.println(prefix + "  isVerticalReachabilityEnabled=" + isVerticalReachabilityEnabled());
        pw.println(prefix + "  letterboxHorizontalPositionMultiplier="
                + getHorizontalPositionMultiplier(mActivityRecord.getParent().getConfiguration()));
        pw.println(prefix + "  letterboxVerticalPositionMultiplier="
                + getVerticalPositionMultiplier(mActivityRecord.getParent().getConfiguration()));
        pw.println(prefix + "  letterboxPositionForHorizontalReachability="
                + LetterboxConfiguration.letterboxHorizontalReachabilityPositionToString(
                mLetterboxConfiguration.getLetterboxPositionForHorizontalReachability(false)));
        pw.println(prefix + "  letterboxPositionForVerticalReachability="
                + LetterboxConfiguration.letterboxVerticalReachabilityPositionToString(
                mLetterboxConfiguration.getLetterboxPositionForVerticalReachability(false)));
        pw.println(prefix + "  fixedOrientationLetterboxAspectRatio="
                + mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio());
        pw.println(prefix + "  defaultMinAspectRatioForUnresizableApps="
                + mLetterboxConfiguration.getDefaultMinAspectRatioForUnresizableApps());
        pw.println(prefix + "  isSplitScreenAspectRatioForUnresizableAppsEnabled="
                + mLetterboxConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled());
        pw.println(prefix + "  isDisplayAspectRatioEnabledForFixedOrientationLetterbox="
                + mLetterboxConfiguration
                .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox());
    }

    /**
     * Returns a string representing the reason for letterboxing. This method assumes the activity
     * is letterboxed.
     */
    private String getLetterboxReasonString(WindowState mainWin) {
        if (mActivityRecord.inSizeCompatMode()) {
            return "SIZE_COMPAT_MODE";
        }
        if (mActivityRecord.isLetterboxedForFixedOrientationAndAspectRatio()) {
            return "FIXED_ORIENTATION";
        }
        if (mainWin.isLetterboxedForDisplayCutout()) {
            return "DISPLAY_CUTOUT";
        }
        if (mActivityRecord.isAspectRatioApplied()) {
            return "ASPECT_RATIO";
        }
        return "UNKNOWN_REASON";
    }

    private int letterboxHorizontalReachabilityPositionToLetterboxPosition(
            @LetterboxConfiguration.LetterboxHorizontalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__LEFT;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__RIGHT;
            default:
                throw new AssertionError(
                        "Unexpected letterbox horizontal reachability position type: "
                                + position);
        }
    }

    private int letterboxVerticalReachabilityPositionToLetterboxPosition(
            @LetterboxConfiguration.LetterboxVerticalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__TOP;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__BOTTOM;
            default:
                throw new AssertionError(
                        "Unexpected letterbox vertical reachability position type: "
                                + position);
        }
    }

    int getLetterboxPositionForLogging() {
        int positionToLog = APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__UNKNOWN_POSITION;
        if (isHorizontalReachabilityEnabled()) {
            int letterboxPositionForHorizontalReachability = getLetterboxConfiguration()
                    .getLetterboxPositionForHorizontalReachability(
                            isDisplayFullScreenAndInPosture(
                                    DeviceStateController.DeviceState.HALF_FOLDED,
                                    false /* isTabletop */));
            positionToLog = letterboxHorizontalReachabilityPositionToLetterboxPosition(
                    letterboxPositionForHorizontalReachability);
        } else if (isVerticalReachabilityEnabled()) {
            int letterboxPositionForVerticalReachability = getLetterboxConfiguration()
                    .getLetterboxPositionForVerticalReachability(
                            isDisplayFullScreenAndInPosture(
                                    DeviceStateController.DeviceState.HALF_FOLDED,
                                    true /* isTabletop */));
            positionToLog = letterboxVerticalReachabilityPositionToLetterboxPosition(
                    letterboxPositionForVerticalReachability);
        }
        return positionToLog;
    }

    private LetterboxConfiguration getLetterboxConfiguration() {
        return mLetterboxConfiguration;
    }

    /**
     * Logs letterbox position changes via {@link ActivityMetricsLogger#logLetterboxPositionChange}.
     */
    private void logLetterboxPositionChange(int letterboxPositionChange) {
        mActivityRecord.mTaskSupervisor.getActivityMetricsLogger()
                .logLetterboxPositionChange(mActivityRecord, letterboxPositionChange);
    }

    @Nullable
    LetterboxDetails getLetterboxDetails() {
        final WindowState w = mActivityRecord.findMainWindow();
        if (mLetterbox == null || w == null || w.isLetterboxedForDisplayCutout()) {
            return null;
        }
        Rect letterboxInnerBounds = new Rect();
        Rect letterboxOuterBounds = new Rect();
        getLetterboxInnerBounds(letterboxInnerBounds);
        getLetterboxOuterBounds(letterboxOuterBounds);

        if (letterboxInnerBounds.isEmpty() || letterboxOuterBounds.isEmpty()) {
            return null;
        }

        return new LetterboxDetails(
                letterboxInnerBounds,
                letterboxOuterBounds,
                w.mAttrs.insetsFlags.appearance
        );
    }

    /**
     * Handles translucent activities letterboxing inheriting constraints from the
     * first opaque activity beneath.
     * @param parent The parent container.
     */
    void onActivityParentChanged(WindowContainer<?> parent) {
        if (!mLetterboxConfiguration.isTranslucentLetterboxingEnabled()) {
            return;
        }
        if (mLetterboxConfigListener != null) {
            mLetterboxConfigListener.onRemoved();
            clearInheritedConfig();
        }
        // In case mActivityRecord.hasCompatDisplayInsetsWithoutOverride() we don't apply the
        // opaque activity constraints because we're expecting the activity is already letterboxed.
        if (mActivityRecord.getTask() == null || mActivityRecord.fillsParent()
                || mActivityRecord.hasCompatDisplayInsetsWithoutInheritance()) {
            return;
        }
        final ActivityRecord firstOpaqueActivityBeneath = mActivityRecord.getTask().getActivity(
                FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE /* callback */,
                mActivityRecord /* boundary */, false /* includeBoundary */,
                true /* traverseTopToBottom */);
        if (firstOpaqueActivityBeneath == null || firstOpaqueActivityBeneath.isEmbedded()) {
            // We skip letterboxing if the translucent activity doesn't have any opaque
            // activities beneath or the activity below is embedded which never has letterbox.
            return;
        }
        inheritConfiguration(firstOpaqueActivityBeneath);
        mLetterboxConfigListener = WindowContainer.overrideConfigurationPropagation(
                mActivityRecord, firstOpaqueActivityBeneath,
                (opaqueConfig, transparentConfig) -> {
                    final Configuration mutatedConfiguration =
                            fromOriginalTranslucentConfig(transparentConfig);
                    final Rect parentBounds = parent.getWindowConfiguration().getBounds();
                    final Rect bounds = mutatedConfiguration.windowConfiguration.getBounds();
                    final Rect letterboxBounds = opaqueConfig.windowConfiguration.getBounds();
                    // We cannot use letterboxBounds directly here because the position relies on
                    // letterboxing. Using letterboxBounds directly, would produce a double offset.
                    bounds.set(parentBounds.left, parentBounds.top,
                            parentBounds.left + letterboxBounds.width(),
                            parentBounds.top + letterboxBounds.height());
                    // We need to initialize appBounds to avoid NPE. The actual value will
                    // be set ahead when resolving the Configuration for the activity.
                    mutatedConfiguration.windowConfiguration.setAppBounds(new Rect());
                    inheritConfiguration(firstOpaqueActivityBeneath);
                    return mutatedConfiguration;
                });
    }

    /**
     * @return {@code true} if the current activity is translucent with an opaque activity
     * beneath. In this case it will inherit bounds, orientation and aspect ratios from
     * the first opaque activity beneath.
     */
    boolean hasInheritedLetterboxBehavior() {
        return mLetterboxConfigListener != null;
    }

    /**
     * @return {@code true} if the current activity is translucent with an opaque activity
     * beneath and needs to inherit its orientation.
     */
    boolean hasInheritedOrientation() {
        // To force a different orientation, the transparent one needs to have an explicit one
        // otherwise the existing one is fine and the actual orientation will depend on the
        // bounds.
        // To avoid wrong behaviour, we're not forcing orientation for activities with not
        // fixed orientation (e.g. permission dialogs).
        return hasInheritedLetterboxBehavior()
                && mActivityRecord.getOverrideOrientation()
                        != SCREEN_ORIENTATION_UNSPECIFIED;
    }

    float getInheritedMinAspectRatio() {
        return mInheritedMinAspectRatio;
    }

    float getInheritedMaxAspectRatio() {
        return mInheritedMaxAspectRatio;
    }

    int getInheritedAppCompatState() {
        return mInheritedAppCompatState;
    }

    @Configuration.Orientation
    int getInheritedOrientation() {
        return mInheritedOrientation;
    }

    ActivityRecord.CompatDisplayInsets getInheritedCompatDisplayInsets() {
        return mInheritedCompatDisplayInsets;
    }

    void clearInheritedCompatDisplayInsets() {
        mInheritedCompatDisplayInsets = null;
    }

    /**
     * In case of translucent activities, it consumes the {@link ActivityRecord} of the first opaque
     * activity beneath using the given consumer and returns {@code true}.
     */
    boolean applyOnOpaqueActivityBelow(@NonNull Consumer<ActivityRecord> consumer) {
        return findOpaqueNotFinishingActivityBelow()
                .map(activityRecord -> {
                    consumer.accept(activityRecord);
                    return true;
                }).orElse(false);
    }

    /**
     * @return The first not finishing opaque activity beneath the current translucent activity
     * if it exists and the strategy is enabled.
     */
    Optional<ActivityRecord> findOpaqueNotFinishingActivityBelow() {
        if (!hasInheritedLetterboxBehavior() || mActivityRecord.getTask() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mActivityRecord.getTask().getActivity(
                FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE /* callback */,
                mActivityRecord /* boundary */, false /* includeBoundary */,
                true /* traverseTopToBottom */));
    }

    // When overriding translucent activities configuration we need to keep some of the
    // original properties
    private Configuration fromOriginalTranslucentConfig(Configuration translucentConfig) {
        final Configuration configuration = new Configuration(translucentConfig);
        // The values for the following properties will be defined during the configuration
        // resolution in {@link ActivityRecord#resolveOverrideConfiguration} using the
        // properties inherited from the first not finishing opaque activity beneath.
        configuration.orientation = ORIENTATION_UNDEFINED;
        configuration.screenWidthDp = configuration.compatScreenWidthDp = SCREEN_WIDTH_DP_UNDEFINED;
        configuration.screenHeightDp =
                configuration.compatScreenHeightDp = SCREEN_HEIGHT_DP_UNDEFINED;
        configuration.smallestScreenWidthDp =
                configuration.compatSmallestScreenWidthDp = SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
        return configuration;
    }

    private void inheritConfiguration(ActivityRecord firstOpaque) {
        // To avoid wrong behaviour, we're not forcing a specific aspect ratio to activities
        // which are not already providing one (e.g. permission dialogs) and presumably also
        // not resizable.
        if (mActivityRecord.getMinAspectRatio() != UNDEFINED_ASPECT_RATIO) {
            mInheritedMinAspectRatio = firstOpaque.getMinAspectRatio();
        }
        if (mActivityRecord.getMaxAspectRatio() != UNDEFINED_ASPECT_RATIO) {
            mInheritedMaxAspectRatio = firstOpaque.getMaxAspectRatio();
        }
        mInheritedOrientation = firstOpaque.getRequestedConfigurationOrientation();
        mInheritedAppCompatState = firstOpaque.getAppCompatState();
        mInheritedCompatDisplayInsets = firstOpaque.getCompatDisplayInsets();
    }

    private void clearInheritedConfig() {
        mLetterboxConfigListener = null;
        mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedOrientation = ORIENTATION_UNDEFINED;
        mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;
        mInheritedCompatDisplayInsets = null;
    }
}
