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
import static android.content.pm.ActivityInfo.FORCE_NON_RESIZE_APP;
import static android.content.pm.ActivityInfo.FORCE_RESIZE_APP;
import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION_TO_USER;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER;
import static android.content.pm.ActivityInfo.isFixedOrientation;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.screenOrientationToString;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_4_3;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_APP_DEFAULT;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_DISPLAY_SIZE;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_SPLIT_SCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.content.res.Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE;
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
import android.os.RemoteException;
import android.util.Slog;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.server.wm.LetterboxConfiguration.LetterboxBackgroundType;
import com.android.server.wm.utils.OptPropFactory;
import com.android.server.wm.utils.OptPropFactory.OptProp;
import com.android.window.flags.Flags;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
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
            ActivityRecord::occludesParent;

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

    // TODO(b/265576778): Cache other overrides as well.

    // Corresponds to OVERRIDE_ANY_ORIENTATION
    private final boolean mIsOverrideAnyOrientationEnabled;
    // Corresponds to OVERRIDE_ANY_ORIENTATION_TO_USER
    private final boolean mIsSystemOverrideToFullscreenEnabled;
    // Corresponds to OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT
    private final boolean mIsOverrideToPortraitOrientationEnabled;
    // Corresponds to OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR
    private final boolean mIsOverrideToNosensorOrientationEnabled;
    // Corresponds to OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE
    private final boolean mIsOverrideToReverseLandscapeOrientationEnabled;
    // Corresponds to OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA
    private final boolean mIsOverrideOrientationOnlyForCameraEnabled;
    // Corresponds to OVERRIDE_RESPECT_REQUESTED_ORIENTATION
    private final boolean mIsOverrideRespectRequestedOrientationEnabled;

    // The list of observers for the destroy event of candidate opaque activities
    // when dealing with translucent activities.
    private final List<LetterboxUiController> mDestroyListeners = new ArrayList<>();

    @NonNull
    private final OptProp mAllowOrientationOverrideOptProp;
    @NonNull
    private final OptProp mAllowDisplayOrientationOverrideOptProp;
    @NonNull
    private final OptProp mAllowMinAspectRatioOverrideOptProp;
    @NonNull
    private final OptProp mAllowForceResizeOverrideOptProp;

    @NonNull
    private final OptProp mAllowUserAspectRatioOverrideOptProp;
    @NonNull
    private final OptProp mAllowUserAspectRatioFullscreenOverrideOptProp;

    /*
     * WindowContainerListener responsible to make translucent activities inherit
     * constraints from the first opaque activity beneath them. It's null for not
     * translucent activities.
     */
    @Nullable
    private WindowContainerListener mLetterboxConfigListener;

    @Nullable
    @VisibleForTesting
    ActivityRecord mFirstOpaqueActivityBeneath;

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

    // TODO(b/315140179): Make mUserAspectRatio final
    // The min aspect ratio override set by user
    @PackageManager.UserMinAspectRatio
    private int mUserAspectRatio = USER_MIN_ASPECT_RATIO_UNSET;

    // The CompatDisplayInsets of the opaque activity beneath the translucent one.
    private ActivityRecord.CompatDisplayInsets mInheritedCompatDisplayInsets;

    @Nullable
    private Letterbox mLetterbox;

    @NonNull
    private final OptProp mCameraCompatAllowForceRotationOptProp;

    @NonNull
    private final OptProp mCameraCompatAllowRefreshOptProp;

    @NonNull
    private final OptProp mCameraCompatEnableRefreshViaPauseOptProp;

    // Whether activity "refresh" was requested but not finished in
    // ActivityRecord#activityResumedLocked following the camera compat force rotation in
    // DisplayRotationCompatPolicy.
    private boolean mIsRefreshAfterRotationRequested;

    @NonNull
    private final OptProp mIgnoreRequestedOrientationOptProp;

    @NonNull
    private final OptProp mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp;

    @NonNull
    private final OptProp mFakeFocusOptProp;

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

        final OptPropFactory optPropBuilder = new OptPropFactory(packageManager,
                activityRecord.packageName);

        final BooleanSupplier isPolicyForIgnoringRequestedOrientationEnabled = asLazy(
                mLetterboxConfiguration::isPolicyForIgnoringRequestedOrientationEnabled);
        mIgnoreRequestedOrientationOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION,
                isPolicyForIgnoringRequestedOrientationEnabled);
        mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED,
                isPolicyForIgnoringRequestedOrientationEnabled);

        mFakeFocusOptProp = optPropBuilder.create(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS,
                mLetterboxConfiguration::isCompatFakeFocusEnabled);

        final BooleanSupplier isCameraCompatTreatmentEnabled = asLazy(
                mLetterboxConfiguration::isCameraCompatTreatmentEnabled);
        mCameraCompatAllowForceRotationOptProp = optPropBuilder.create(
                PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION,
                isCameraCompatTreatmentEnabled);
        mCameraCompatAllowRefreshOptProp = optPropBuilder.create(
                PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH,
                isCameraCompatTreatmentEnabled);
        mCameraCompatEnableRefreshViaPauseOptProp = optPropBuilder.create(
                PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE,
                isCameraCompatTreatmentEnabled);

        mAllowOrientationOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);

        mAllowDisplayOrientationOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE,
                () -> mActivityRecord.mDisplayContent != null
                        && mActivityRecord.getTask() != null
                        && mActivityRecord.mDisplayContent.getIgnoreOrientationRequest()
                        && !mActivityRecord.getTask().inMultiWindowMode()
                        && mActivityRecord.mDisplayContent.getNaturalOrientation()
                            == ORIENTATION_LANDSCAPE
        );

        mAllowMinAspectRatioOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);

        mAllowForceResizeOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);

        mAllowUserAspectRatioOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE,
                mLetterboxConfiguration::isUserAppAspectRatioSettingsEnabled);

        mAllowUserAspectRatioFullscreenOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE,
                mLetterboxConfiguration::isUserAppAspectRatioFullscreenEnabled);

        mIsOverrideAnyOrientationEnabled = isCompatChangeEnabled(OVERRIDE_ANY_ORIENTATION);
        mIsSystemOverrideToFullscreenEnabled =
                isCompatChangeEnabled(OVERRIDE_ANY_ORIENTATION_TO_USER);
        mIsOverrideToPortraitOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT);
        mIsOverrideToReverseLandscapeOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE);
        mIsOverrideToNosensorOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR);
        mIsOverrideOrientationOnlyForCameraEnabled =
                isCompatChangeEnabled(OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA);
        mIsOverrideRespectRequestedOrientationEnabled =
                isCompatChangeEnabled(OVERRIDE_RESPECT_REQUESTED_ORIENTATION);

    }

    /** Cleans up {@link Letterbox} if it exists.*/
    void destroy() {
        if (mLetterbox != null) {
            mLetterbox.destroy();
            mLetterbox = null;
        }
        for (int i = mDestroyListeners.size() - 1; i >= 0; i--) {
            mDestroyListeners.get(i).updateInheritedLetterbox();
        }
        mDestroyListeners.clear();
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
        if (mIgnoreRequestedOrientationOptProp.shouldEnableWithOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION))) {
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
    boolean shouldIgnoreOrientationRequestLoop() {
        final boolean loopDetectionEnabled = isCompatChangeEnabled(
                OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED);
        if (!mAllowIgnoringOrientationRequestWhenLoopDetectedOptProp
                .shouldEnableWithOptInOverrideAndOptOutProperty(loopDetectionEnabled)) {
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
        return mFakeFocusOptProp.shouldEnableWithOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS));
    }

    /**
     * Whether we should apply the min aspect ratio per-app override. When this override is applied
     * the min aspect ratio given in the app's manifest will be overridden to the largest enabled
     * aspect ratio treatment unless the app's manifest value is higher. The treatment will also
     * apply if no value is provided in the manifest.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideMinAspectRatio() {
        return mAllowMinAspectRatioOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isCompatChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO));
    }

    /**
     * Whether we should apply the min aspect ratio per-app override only when an app is connected
     * to the camera.
     * When this override is applied the min aspect ratio given in the app's manifest will be
     * overridden to the largest enabled aspect ratio treatment unless the app's manifest value
     * is higher. The treatment will also apply if no value is provided in the manifest.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideMinAspectRatioForCamera() {
        return mActivityRecord.isCameraActive()
                && mAllowMinAspectRatioOverrideOptProp
                .shouldEnableWithOptInOverrideAndOptOutProperty(
                        isCompatChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA));
    }

    /**
     * Whether we should apply the force resize per-app override. When this override is applied it
     * forces the packages it is applied to to be resizable. It won't change whether the app can be
     * put into multi-windowing mode, but allow the app to resize without going into size-compat
     * mode when the window container resizes, such as display size change or screen rotation.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideForceResizeApp() {
        return mAllowForceResizeOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isCompatChangeEnabled(FORCE_RESIZE_APP));
    }

    /**
     * Whether we should apply the force non resize per-app override. When this override is applied
     * it forces the packages it is applied to to be non-resizable.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideForceNonResizeApp() {
        return mAllowForceResizeOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isCompatChangeEnabled(FORCE_NON_RESIZE_APP));
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
        return mAllowDisplayOrientationOverrideOptProp
                .shouldEnableWithOptInOverrideAndOptOutProperty(
                        isCompatChangeEnabled(OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION));
    }

    @ScreenOrientation
    int overrideOrientationIfNeeded(@ScreenOrientation int candidate) {
        final DisplayContent displayContent = mActivityRecord.mDisplayContent;
        final boolean isIgnoreOrientationRequestEnabled = displayContent != null
                && displayContent.getIgnoreOrientationRequest();
        if (shouldApplyUserFullscreenOverride() && isIgnoreOrientationRequestEnabled) {
            Slog.v(TAG, "Requested orientation " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_USER)
                    + " by user aspect ratio settings.");
            return SCREEN_ORIENTATION_USER;
        }

        // In some cases (e.g. Kids app) we need to map the candidate orientation to some other
        // orientation.
        candidate = mActivityRecord.mWmService.mapOrientationRequest(candidate);

        if (shouldApplyUserMinAspectRatioOverride() && (!isFixedOrientation(candidate)
                || candidate == SCREEN_ORIENTATION_LOCKED)) {
            Slog.v(TAG, "Requested orientation " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_PORTRAIT)
                    + " by user aspect ratio settings.");
            return SCREEN_ORIENTATION_PORTRAIT;
        }

        if (mAllowOrientationOverrideOptProp.isFalse()) {
            return candidate;
        }

        if (mIsOverrideOrientationOnlyForCameraEnabled && displayContent != null
                && (displayContent.mDisplayRotationCompatPolicy == null
                        || !displayContent.mDisplayRotationCompatPolicy
                                .isActivityEligibleForOrientationOverride(mActivityRecord))) {
            return candidate;
        }

        // mUserAspectRatio is always initialized first in shouldApplyUserFullscreenOverride(),
        // which will always come first before this check as user override > device
        // manufacturer override.
        if (isSystemOverrideToFullscreenEnabled() && isIgnoreOrientationRequestEnabled) {
            Slog.v(TAG, "Requested orientation  " + screenOrientationToString(candidate) + " for "
                    + mActivityRecord + " is overridden to "
                    + screenOrientationToString(SCREEN_ORIENTATION_USER));
            return SCREEN_ORIENTATION_USER;
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
        return mCameraCompatAllowRefreshOptProp.shouldEnableWithOptOutOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH));
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
        return mCameraCompatEnableRefreshViaPauseOptProp.shouldEnableWithOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE));
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
        return mCameraCompatAllowForceRotationOptProp.shouldEnableWithOptOutOverrideAndProperty(
                isCompatChangeEnabled(OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION));
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

    void updateLetterboxSurfaceIfNeeded(WindowState winHint) {
        updateLetterboxSurfaceIfNeeded(winHint, mActivityRecord.getSyncTransaction());
    }

    void updateLetterboxSurfaceIfNeeded(WindowState winHint, Transaction t) {
        if (shouldNotLayoutLetterbox(winHint)) {
            return;
        }
        layoutLetterboxIfNeeded(winHint);
        if (mLetterbox != null && mLetterbox.needsApplySurfaceChanges()) {
            mLetterbox.applySurfaceChanges(t);
        }
    }

    void layoutLetterboxIfNeeded(WindowState w) {
        if (shouldNotLayoutLetterbox(w)) {
            return;
        }
        updateRoundedCornersIfNeeded(w);
        updateWallpaperForLetterbox(w);
        if (shouldShowLetterboxUi(w)) {
            if (mLetterbox == null) {
                mLetterbox = new Letterbox(() -> mActivityRecord.makeChildSurface(null),
                        mActivityRecord.mWmService.mTransactionFactory,
                        this::shouldLetterboxHaveRoundedCorners,
                        this::getLetterboxBackgroundColor,
                        this::hasWallpaperBackgroundForLetterbox,
                        this::getLetterboxWallpaperBlurRadiusPx,
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
            // mode, the taskFragment-level represents this for both split-screen
            // and activity-embedding. In fullscreen-mode, the task container does
            // (since the orientation letterbox is also applied to the task).
            final Rect transformedBounds = mActivityRecord.getFixedRotationTransformDisplayBounds();
            final Rect spaceToFill = transformedBounds != null
                    ? transformedBounds
                    : mActivityRecord.inMultiWindowMode()
                            ? mActivityRecord.getTaskFragment().getBounds()
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
            if (mDoubleTapEvent) {
                // We need to notify Shell that letterbox position has changed.
                mActivityRecord.getTask().dispatchTaskInfoChangedIfNeeded(true /* force */);
            }
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

    private static boolean shouldNotLayoutLetterbox(WindowState w) {
        if (w == null) {
            return true;
        }
        final int type = w.mAttrs.type;
        // Allow letterbox to be displayed early for base application or application starting
        // windows even if it is not on the top z order to prevent flickering when the
        // letterboxed window is brought to the top
        return (type != TYPE_BASE_APPLICATION && type != TYPE_APPLICATION_STARTING)
                || w.mAnimatingExit;
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
    private boolean isDisplayFullScreenAndInPosture(boolean isTabletop) {
        Task task = mActivityRecord.getTask();
        return mActivityRecord.mDisplayContent != null && task != null
                && mActivityRecord.mDisplayContent.getDisplayRotation().isDeviceInPosture(
                        DeviceStateController.DeviceState.HALF_FOLDED, isTabletop)
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
        return isDisplayFullScreenAndInPosture(/* isTabletop */ false)
                && mLetterboxConfiguration.getIsAutomaticReachabilityInBookModeEnabled();
    }

    float getVerticalPositionMultiplier(Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean tabletopMode = isDisplayFullScreenAndInPosture(/* isTabletop */ true);
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
                || isCameraCompatSplitScreenAspectRatioAllowed()
                || shouldOverrideMinAspectRatioForCamera()) {
            mActivityRecord.recomputeConfiguration();
        }
    }

    boolean isLetterboxEducationEnabled() {
        return mLetterboxConfiguration.getIsEducationEnabled();
    }

    /**
     * Whether we use split screen aspect ratio for the activity when camera compat treatment
     * is active because the corresponding config is enabled and activity supports resizing.
     */
    boolean isCameraCompatSplitScreenAspectRatioAllowed() {
        return mLetterboxConfiguration.isCameraCompatSplitScreenAspectRatioEnabled()
                && !mActivityRecord.shouldCreateCompatDisplayInsets();
    }

    private boolean shouldUseSplitScreenAspectRatio(@NonNull Configuration parentConfiguration) {
        final boolean isBookMode = isDisplayFullScreenAndInPosture(/* isTabletop */ false);
        final boolean isNotCenteredHorizontally = getHorizontalPositionMultiplier(
                parentConfiguration) != LETTERBOX_POSITION_MULTIPLIER_CENTER;
        final boolean isTabletopMode = isDisplayFullScreenAndInPosture(/* isTabletop */ true);
        final boolean isLandscape = isFixedOrientationLandscape(
                mActivityRecord.getOverrideOrientation());

        // Don't resize to split screen size when in book mode if letterbox position is centered
        return (isBookMode && isNotCenteredHorizontally || isTabletopMode && isLandscape)
                    || isCameraCompatSplitScreenAspectRatioAllowed()
                        && isCameraCompatTreatmentActive();
    }

    private float getDefaultMinAspectRatioForUnresizableApps() {
        if (!mLetterboxConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled()
                || mActivityRecord.getDisplayArea() == null) {
            return mLetterboxConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                    > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO
                            ? mLetterboxConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                            : getDefaultMinAspectRatio();
        }

        return getSplitScreenAspectRatio();
    }

    /**
     * @return {@value true} if the resulting app is letterboxed in a way defined as thin.
     */
    boolean isVerticalThinLetterboxed() {
        final int thinHeight = mLetterboxConfiguration.getThinLetterboxHeightPx();
        if (thinHeight < 0) {
            return false;
        }
        final Task task = mActivityRecord.getTask();
        if (task == null) {
            return false;
        }
        final int padding = Math.abs(
                task.getBounds().height() - mActivityRecord.getBounds().height()) / 2;
        return padding <= thinHeight;
    }

    /**
     * @return {@value true} if the resulting app is pillarboxed in a way defined as thin.
     */
    boolean isHorizontalThinLetterboxed() {
        final int thinWidth = mLetterboxConfiguration.getThinLetterboxWidthPx();
        if (thinWidth < 0) {
            return false;
        }
        final Task task = mActivityRecord.getTask();
        if (task == null) {
            return false;
        }
        final int padding = Math.abs(
                task.getBounds().width() - mActivityRecord.getBounds().width()) / 2;
        return padding <= thinWidth;
    }


    /**
     * @return {@value true} if the vertical reachability should be allowed in case of
     * thin letteboxing
     */
    boolean allowVerticalReachabilityForThinLetterbox() {
        if (!Flags.disableThinLetterboxingPolicy()) {
            return true;
        }
        // When the flag is enabled we allow vertical reachability only if the
        // app is not thin letterboxed vertically.
        return !isVerticalThinLetterboxed();
    }

    /**
     * @return {@value true} if the vertical reachability should be enabled in case of
     * thin letteboxing
     */
    boolean allowHorizontalReachabilityForThinLetterbox() {
        if (!Flags.disableThinLetterboxingPolicy()) {
            return true;
        }
        // When the flag is enabled we allow horizontal reachability only if the
        // app is not thin pillarboxed.
        return !isHorizontalThinLetterboxed();
    }

    float getSplitScreenAspectRatio() {
        // Getting the same aspect ratio that apps get in split screen.
        final DisplayArea displayArea = mActivityRecord.getDisplayArea();
        if (displayArea == null) {
            return getDefaultMinAspectRatioForUnresizableApps();
        }
        int dividerWindowWidth =
                getResources().getDimensionPixelSize(R.dimen.docked_stack_divider_thickness);
        int dividerInsets =
                getResources().getDimensionPixelSize(R.dimen.docked_stack_divider_insets);
        int dividerSize = dividerWindowWidth - dividerInsets * 2;
        final Rect bounds = new Rect(displayArea.getWindowConfiguration().getAppBounds());
        if (bounds.width() >= bounds.height()) {
            bounds.inset(/* dx */ dividerSize / 2, /* dy */ 0);
            bounds.right = bounds.centerX();
        } else {
            bounds.inset(/* dx */ 0, /* dy */ dividerSize / 2);
            bounds.bottom = bounds.centerY();
        }
        return computeAspectRatio(bounds);
    }

    /**
     * Whether we should enable users to resize the current app.
     */
    boolean shouldEnableUserAspectRatioSettings() {
        // We use mBooleanPropertyAllowUserAspectRatioOverride to allow apps to opt-out which has
        // effect only if explicitly false. If mBooleanPropertyAllowUserAspectRatioOverride is null,
        // the current app doesn't opt-out so the first part of the predicate is true.
        return !mAllowUserAspectRatioOverrideOptProp.isFalse()
                && mLetterboxConfiguration.isUserAppAspectRatioSettingsEnabled()
                && mActivityRecord.mDisplayContent != null
                && mActivityRecord.mDisplayContent.getIgnoreOrientationRequest();
    }

    /**
     * Whether we should apply the user aspect ratio override to the min aspect ratio for the
     * current app.
     */
    boolean shouldApplyUserMinAspectRatioOverride() {
        if (!shouldEnableUserAspectRatioSettings()) {
            return false;
        }

        mUserAspectRatio = getUserMinAspectRatioOverrideCode();

        return mUserAspectRatio != USER_MIN_ASPECT_RATIO_UNSET
                && mUserAspectRatio != USER_MIN_ASPECT_RATIO_APP_DEFAULT
                && mUserAspectRatio != USER_MIN_ASPECT_RATIO_FULLSCREEN;
    }

    boolean isUserFullscreenOverrideEnabled() {
        if (mAllowUserAspectRatioOverrideOptProp.isFalse()
                || mAllowUserAspectRatioFullscreenOverrideOptProp.isFalse()
                || !mLetterboxConfiguration.isUserAppAspectRatioFullscreenEnabled()) {
            return false;
        }
        return true;
    }

    boolean shouldApplyUserFullscreenOverride() {
        if (isUserFullscreenOverrideEnabled()) {
            mUserAspectRatio = getUserMinAspectRatioOverrideCode();

            return mUserAspectRatio == USER_MIN_ASPECT_RATIO_FULLSCREEN;
        }

        return false;
    }

    boolean isSystemOverrideToFullscreenEnabled() {
        return mIsSystemOverrideToFullscreenEnabled
                && !mAllowOrientationOverrideOptProp.isFalse()
                && (mUserAspectRatio == USER_MIN_ASPECT_RATIO_UNSET
                    || mUserAspectRatio == USER_MIN_ASPECT_RATIO_FULLSCREEN);
    }

    boolean hasFullscreenOverride() {
        return isSystemOverrideToFullscreenEnabled() || shouldApplyUserFullscreenOverride();
    }

    float getUserMinAspectRatio() {
        switch (mUserAspectRatio) {
            case USER_MIN_ASPECT_RATIO_DISPLAY_SIZE:
                return getDisplaySizeMinAspectRatio();
            case USER_MIN_ASPECT_RATIO_SPLIT_SCREEN:
                return getSplitScreenAspectRatio();
            case USER_MIN_ASPECT_RATIO_16_9:
                return 16 / 9f;
            case USER_MIN_ASPECT_RATIO_4_3:
                return 4 / 3f;
            case USER_MIN_ASPECT_RATIO_3_2:
                return 3 / 2f;
            default:
                throw new AssertionError("Unexpected user min aspect ratio override: "
                        + mUserAspectRatio);
        }
    }

    @VisibleForTesting
    int getUserMinAspectRatioOverrideCode() {
        try {
            return mActivityRecord.mAtmService.getPackageManager()
                    .getUserMinAspectRatio(mActivityRecord.packageName, mActivityRecord.mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Exception thrown retrieving aspect ratio user override " + this, e);
        }
        return mUserAspectRatio;
    }

    private float getDisplaySizeMinAspectRatio() {
        final DisplayArea displayArea = mActivityRecord.getDisplayArea();
        if (displayArea == null) {
            return mActivityRecord.info.getMinAspectRatio();
        }
        final Rect bounds = new Rect(displayArea.getWindowConfiguration().getAppBounds());
        return computeAspectRatio(bounds);
    }

    private float getDefaultMinAspectRatio() {
        if (mActivityRecord.getDisplayArea() == null
                || !mLetterboxConfiguration
                    .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox()) {
            return mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio();
        }
        return getDisplaySizeMinAspectRatio();
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
            mDoubleTapEvent = true;
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
            mDoubleTapEvent = true;
        }
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
            mDoubleTapEvent = true;
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
            mDoubleTapEvent = true;
        }
        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    /**
     * Whether horizontal reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Windowing mode is fullscreen.
     *   <li>Horizontal Reachability is enabled.
     *   <li>First top opaque activity fills parent vertically, but not horizontally.
     * </ul>
     */
    private boolean isHorizontalReachabilityEnabled(Configuration parentConfiguration) {
        if (!allowHorizontalReachabilityForThinLetterbox()) {
            return false;
        }
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty
        final Rect opaqueActivityBounds = hasInheritedLetterboxBehavior()
                ? mFirstOpaqueActivityBeneath.getScreenResolvedBounds()
                : mActivityRecord.getScreenResolvedBounds();
        return mLetterboxConfiguration.getIsHorizontalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                // Check whether the activity fills the parent vertically.
                && parentConfiguration.windowConfiguration.getAppBounds().height()
                        <= opaqueActivityBounds.height()
                && parentConfiguration.windowConfiguration.getAppBounds().width()
                        > opaqueActivityBounds.width();
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
     *   <li>Windowing mode is fullscreen.
     *   <li>Vertical Reachability is enabled.
     *   <li>First top opaque activity fills parent horizontally but not vertically.
     * </ul>
     */
    private boolean isVerticalReachabilityEnabled(Configuration parentConfiguration) {
        if (!allowVerticalReachabilityForThinLetterbox()) {
            return false;
        }
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty
        final Rect opaqueActivityBounds = hasInheritedLetterboxBehavior()
                ? mFirstOpaqueActivityBeneath.getScreenResolvedBounds()
                : mActivityRecord.getScreenResolvedBounds();
        return mLetterboxConfiguration.getIsVerticalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                // Check whether the activity fills the parent horizontally.
                && parentConfiguration.windowConfiguration.getAppBounds().width()
                        <= opaqueActivityBounds.width()
                && parentConfiguration.windowConfiguration.getAppBounds().height()
                        > opaqueActivityBounds.height();
    }

    @VisibleForTesting
    boolean isVerticalReachabilityEnabled() {
        return isVerticalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    @VisibleForTesting
    boolean shouldShowLetterboxUi(WindowState mainWindow) {
        if (mIsRelaunchingAfterRequestedOrientationChanged) {
            return mLastShouldShowLetterboxUi;
        }

        final boolean shouldShowLetterboxUi =
                (mActivityRecord.isInLetterboxAnimation() || mActivityRecord.isVisible()
                        || mActivityRecord.isVisibleRequested())
                && mainWindow.areAppWindowBoundsLetterboxed()
                // Check for FLAG_SHOW_WALLPAPER explicitly instead of using
                // WindowContainer#showWallpaper because the later will return true when this
                // activity is using blurred wallpaper for letterbox background.
                && (mainWindow.getAttrs().flags & FLAG_SHOW_WALLPAPER) == 0;

        mLastShouldShowLetterboxUi = shouldShowLetterboxUi;

        return shouldShowLetterboxUi;
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
                    return mLetterboxConfiguration.getLetterboxBackgroundColor();
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

        // In case of translucent activities we check if the requested size is different from
        // the size provided using inherited bounds. In that case we decide to not apply rounded
        // corners because we assume the specific layout would. This is the case when the layout
        // of the translucent activity uses only a part of all the bounds because of the use of
        // LayoutParams.WRAP_CONTENT.
        if (hasInheritedLetterboxBehavior() && (cropBounds.width() != mainWindow.mRequestedWidth
                || cropBounds.height() != mainWindow.mRequestedHeight)) {
            return null;
        }

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
        final InsetsState state = mainWindow.getInsetsState();
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            if (source.getType() == WindowInsets.Type.navigationBars()
                    && source.hasFlags(InsetsSource.FLAG_INSETS_ROUNDED_CORNER)
                    && source.isVisible()) {
                return source;
            }
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
                        && (getLetterboxWallpaperBlurRadiusPx() > 0
                                || getLetterboxWallpaperDarkScrimAlpha() > 0)
                        // Check that blur is supported by a device if blur radius is provided.
                        && (getLetterboxWallpaperBlurRadiusPx() <= 0
                                || isLetterboxWallpaperBlurSupported());
        if (mShowWallpaperForLetterboxBackground != wallpaperShouldBeShown) {
            mShowWallpaperForLetterboxBackground = wallpaperShouldBeShown;
            mActivityRecord.requestUpdateWallpaperIfNeeded();
        }
    }

    private int getLetterboxWallpaperBlurRadiusPx() {
        int blurRadius = mLetterboxConfiguration.getLetterboxBackgroundWallpaperBlurRadiusPx();
        return Math.max(blurRadius, 0);
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
        pw.println(prefix + "  isVerticalThinLetterboxed=" + isVerticalThinLetterboxed());
        pw.println(prefix + "  isHorizontalThinLetterboxed=" + isHorizontalThinLetterboxed());
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
                    + getLetterboxWallpaperBlurRadiusPx());
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
                            isDisplayFullScreenAndInPosture(/* isTabletop */ false));
            positionToLog = letterboxHorizontalReachabilityPositionToLetterboxPosition(
                    letterboxPositionForHorizontalReachability);
        } else if (isVerticalReachabilityEnabled()) {
            int letterboxPositionForVerticalReachability = getLetterboxConfiguration()
                    .getLetterboxPositionForVerticalReachability(
                            isDisplayFullScreenAndInPosture(/* isTabletop */ true));
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
    void updateInheritedLetterbox() {
        final WindowContainer<?> parent = mActivityRecord.getParent();
        if (parent == null) {
            return;
        }
        if (!mLetterboxConfiguration.isTranslucentLetterboxingEnabled()) {
            return;
        }
        if (mLetterboxConfigListener != null) {
            mLetterboxConfigListener.onRemoved();
            clearInheritedConfig();
        }
        // In case mActivityRecord.hasCompatDisplayInsetsWithoutOverride() we don't apply the
        // opaque activity constraints because we're expecting the activity is already letterboxed.
        mFirstOpaqueActivityBeneath = mActivityRecord.getTask().getActivity(
                FIRST_OPAQUE_NOT_FINISHING_ACTIVITY_PREDICATE /* callback */,
                mActivityRecord /* boundary */, false /* includeBoundary */,
                true /* traverseTopToBottom */);
        if (mFirstOpaqueActivityBeneath == null || mFirstOpaqueActivityBeneath.isEmbedded()) {
            // We skip letterboxing if the translucent activity doesn't have any opaque
            // activities beneath or the activity below is embedded which never has letterbox.
            mActivityRecord.recomputeConfiguration();
            return;
        }
        if (mActivityRecord.getTask() == null || mActivityRecord.fillsParent()
                || mActivityRecord.hasCompatDisplayInsetsWithoutInheritance()) {
            return;
        }
        mFirstOpaqueActivityBeneath.mLetterboxUiController.mDestroyListeners.add(this);
        inheritConfiguration(mFirstOpaqueActivityBeneath);
        mLetterboxConfigListener = WindowContainer.overrideConfigurationPropagation(
                mActivityRecord, mFirstOpaqueActivityBeneath,
                (opaqueConfig, transparentOverrideConfig) -> {
                    resetTranslucentOverrideConfig(transparentOverrideConfig);
                    final Rect parentBounds = parent.getWindowConfiguration().getBounds();
                    final Rect bounds = transparentOverrideConfig.windowConfiguration.getBounds();
                    final Rect letterboxBounds = opaqueConfig.windowConfiguration.getBounds();
                    // We cannot use letterboxBounds directly here because the position relies on
                    // letterboxing. Using letterboxBounds directly, would produce a double offset.
                    bounds.set(parentBounds.left, parentBounds.top,
                            parentBounds.left + letterboxBounds.width(),
                            parentBounds.top + letterboxBounds.height());
                    // We need to initialize appBounds to avoid NPE. The actual value will
                    // be set ahead when resolving the Configuration for the activity.
                    transparentOverrideConfig.windowConfiguration.setAppBounds(new Rect());
                    inheritConfiguration(mFirstOpaqueActivityBeneath);
                    return transparentOverrideConfig;
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
        return Optional.ofNullable(mFirstOpaqueActivityBeneath);
    }

    /** Resets the screen size related fields so they can be resolved by requested bounds later. */
    private static void resetTranslucentOverrideConfig(Configuration config) {
        // The values for the following properties will be defined during the configuration
        // resolution in {@link ActivityRecord#resolveOverrideConfiguration} using the
        // properties inherited from the first not finishing opaque activity beneath.
        config.orientation = ORIENTATION_UNDEFINED;
        config.screenWidthDp = config.compatScreenWidthDp = SCREEN_WIDTH_DP_UNDEFINED;
        config.screenHeightDp = config.compatScreenHeightDp = SCREEN_HEIGHT_DP_UNDEFINED;
        config.smallestScreenWidthDp = config.compatSmallestScreenWidthDp =
                SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
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
        if (mFirstOpaqueActivityBeneath != null) {
            mFirstOpaqueActivityBeneath.mLetterboxUiController.mDestroyListeners.remove(this);
        }
        mFirstOpaqueActivityBeneath = null;
        mLetterboxConfigListener = null;
        mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedOrientation = ORIENTATION_UNDEFINED;
        mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;
        mInheritedCompatDisplayInsets = null;
    }

    @NonNull
    private static BooleanSupplier asLazy(@NonNull BooleanSupplier supplier) {
        return new BooleanSupplier() {
            private boolean mRead;
            private boolean mValue;

            @Override
            public boolean getAsBoolean() {
                if (!mRead) {
                    mRead = true;
                    mValue = supplier.getAsBoolean();
                }
                return mValue;
            }
        };
    }
}
