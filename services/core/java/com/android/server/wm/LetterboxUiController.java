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
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.screenOrientationToString;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
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
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
import static com.android.server.wm.LetterboxConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO;
import static com.android.server.wm.LetterboxConfiguration.letterboxBackgroundTypeToString;

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
import java.util.function.BooleanSupplier;

/** Controls behaviour of the letterbox UI for {@link mActivityRecord}. */
// TODO(b/185262487): Improve test coverage of this class. Parts of it are tested in
// SizeCompatTests and LetterboxTests but not all.
// TODO(b/185264020): Consider making LetterboxUiController applicable to any level of the
// hierarchy in addition to ActivityRecord (Task, DisplayArea, ...).
// TODO(b/263021211): Consider renaming to more generic CompatUIController.
final class LetterboxUiController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "LetterboxUiController" : TAG_ATM;

    private static final float UNDEFINED_ASPECT_RATIO = 0f;

    private final Point mTmpPoint = new Point();

    private final LetterboxConfiguration mLetterboxConfiguration;

    private final ActivityRecord mActivityRecord;

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

    @Configuration.Orientation
    private int mInheritedOrientation = Configuration.ORIENTATION_UNDEFINED;

    // The app compat state for the opaque activity if any
    private int mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;

    // If true it means that the opaque activity beneath a translucent one is in SizeCompatMode.
    private boolean mIsInheritedInSizeCompatMode;

    // This is the SizeCompatScale of the opaque activity beneath a translucent one
    private float mInheritedSizeCompatScale;

    // The CompatDisplayInsets of the opaque activity beneath the translucent one.
    private ActivityRecord.CompatDisplayInsets mInheritedCompatDisplayInsets;

    @Nullable
    private Letterbox mLetterbox;

    // Whether activity "refresh" was requested but not finished in
    // ActivityRecord#activityResumedLocked following the camera compat force rotation in
    // DisplayRotationCompatPolicy.
    private boolean mIsRefreshAfterRotationRequested;

    @Nullable
    private final Boolean mBooleanPropertyIgnoreRequestedOrientation;

    private boolean mIsRelauchingAfterRequestedOrientationChanged;

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
    }

    @Nullable
    private static Boolean readComponentProperty(PackageManager packageManager, String packageName,
            BooleanSupplier gatingCondition, String propertyName) {
        if (!gatingCondition.getAsBoolean()) {
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
     * </ul>
     */
    boolean shouldIgnoreRequestedOrientation(@ScreenOrientation int requestedOrientation) {
        if (!mLetterboxConfiguration.isPolicyForIgnoringRequestedOrientationEnabled()) {
            return false;
        }
        if (Boolean.FALSE.equals(mBooleanPropertyIgnoreRequestedOrientation)) {
            return false;
        }
        if (!Boolean.TRUE.equals(mBooleanPropertyIgnoreRequestedOrientation)
                && !mActivityRecord.info.isChangeEnabled(
                        OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION)) {
            return false;
        }
        if (mIsRelauchingAfterRequestedOrientationChanged) {
            Slog.w(TAG, "Ignoring orientation update to "
                    + screenOrientationToString(requestedOrientation)
                    + " due to relaunching after setRequestedOrientation for " + mActivityRecord);
            return true;
        }
        DisplayContent displayContent = mActivityRecord.mDisplayContent;
        if (displayContent == null) {
            return false;
        }
        if (displayContent.mDisplayRotationCompatPolicy != null
                && displayContent.mDisplayRotationCompatPolicy
                        .isTreatmentEnabledForActivity(mActivityRecord)) {
            Slog.w(TAG, "Ignoring orientation update to "
                    + screenOrientationToString(requestedOrientation)
                    + " due to camera compat treatment for " + mActivityRecord);
            return true;
        }
        return false;
    }

    /**
     * Sets whether an activity is relaunching after the app has called {@link
     * android.app.Activity#setRequestedOrientation}.
     */
    void setRelauchingAfterRequestedOrientationChanged(boolean isRelaunching) {
        mIsRelauchingAfterRequestedOrientationChanged = isRelaunching;
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
            if (w == null) {
                return;
            }
            adjustBoundsForTaskbar(w, outBounds);
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
        updateRoundedCorners(w);
        // If there is another main window that is not an application-starting window, we should
        // update rounded corners for it as well, to avoid flickering rounded corners.
        final WindowState nonStartingAppW = mActivityRecord.findMainWindow(
                /* includeStartingApp= */ false);
        if (nonStartingAppW != null && nonStartingAppW != w) {
            updateRoundedCorners(nonStartingAppW);
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
            final Rect innerFrame = hasInheritedLetterboxBehavior()
                    ? mActivityRecord.getWindowConfiguration().getBounds() : w.getFrame();
            mLetterbox.layout(spaceToFill, innerFrame, mTmpPoint);
        } else if (mLetterbox != null) {
            mLetterbox.hide();
        }
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
    private boolean isDisplayFullScreenAndInPosture(DeviceStateController.FoldState state,
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
        boolean bookMode = isDisplayFullScreenAndInPosture(
                DeviceStateController.FoldState.HALF_FOLDED, false /* isTabletop */);
        return isHorizontalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mLetterboxConfiguration.getHorizontalMultiplierForReachability(bookMode)
                : mLetterboxConfiguration.getLetterboxHorizontalPositionMultiplier(bookMode);
    }

    float getVerticalPositionMultiplier(Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean tabletopMode = isDisplayFullScreenAndInPosture(
                DeviceStateController.FoldState.HALF_FOLDED, true /* isTabletop */);
        return isVerticalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mLetterboxConfiguration.getVerticalMultiplierForReachability(tabletopMode)
                : mLetterboxConfiguration.getLetterboxVerticalPositionMultiplier(tabletopMode);
    }

    float getFixedOrientationLetterboxAspectRatio() {
        return isDisplayFullScreenAndSeparatingHinge()
                ? getSplitScreenAspectRatio()
                : mActivityRecord.shouldCreateCompatDisplayInsets()
                    ? getDefaultMinAspectRatioForUnresizableApps()
                    : mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio();
    }

    private float getDefaultMinAspectRatioForUnresizableApps() {
        if (!mLetterboxConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled()
                || mActivityRecord.getDisplayContent() == null) {
            return mLetterboxConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                    > MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO
                            ? mLetterboxConfiguration.getDefaultMinAspectRatioForUnresizableApps()
                            : mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio();
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
        final Rect bounds = new Rect(displayContent.getBounds());
        if (bounds.width() >= bounds.height()) {
            bounds.inset(/* dx */ dividerSize / 2, /* dy */ 0);
            bounds.right = bounds.centerX();
        } else {
            bounds.inset(/* dx */ 0, /* dy */ dividerSize / 2);
            bounds.bottom = bounds.centerY();
        }
        return computeAspectRatio(bounds);
    }

    Resources getResources() {
        return mActivityRecord.mWmService.mContext.getResources();
    }

    private void handleHorizontalDoubleTap(int x) {
        // TODO(b/260857308): Investigate if enabling reachability for translucent activity
        if (hasInheritedLetterboxBehavior() || !isHorizontalReachabilityEnabled()
                || mActivityRecord.isInTransition()) {
            return;
        }

        if (mLetterbox.getInnerFrame().left <= x && mLetterbox.getInnerFrame().right >= x) {
            // Only react to clicks at the sides of the letterboxed app window.
            return;
        }

        boolean isInFullScreenBookMode = isDisplayFullScreenAndSeparatingHinge();
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

        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    private void handleVerticalDoubleTap(int y) {
        // TODO(b/260857308): Investigate if enabling reachability for translucent activity
        if (hasInheritedLetterboxBehavior() || !isVerticalReachabilityEnabled()
                || mActivityRecord.isInTransition()) {
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
     * </ul>
     */
    private boolean isHorizontalReachabilityEnabled(Configuration parentConfiguration) {
        return mLetterboxConfiguration.getIsHorizontalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                && (parentConfiguration.orientation == ORIENTATION_LANDSCAPE
                && mActivityRecord.getOrientationForReachability() == ORIENTATION_PORTRAIT);
    }

    private boolean isHorizontalReachabilityEnabled() {
        return isHorizontalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    /**
     * Whether vertical reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Activity is landscape-only.
     *   <li>Fullscreen window in portrait device orientation.
     *   <li>Vertical Reachability is enabled.
     * </ul>
     */
    private boolean isVerticalReachabilityEnabled(Configuration parentConfiguration) {
        return mLetterboxConfiguration.getIsVerticalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                && (parentConfiguration.orientation == ORIENTATION_PORTRAIT
                && mActivityRecord.getOrientationForReachability() == ORIENTATION_LANDSCAPE);
    }

    private boolean isVerticalReachabilityEnabled() {
        return isVerticalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    @VisibleForTesting
    boolean shouldShowLetterboxUi(WindowState mainWindow) {
        return isSurfaceReadyAndVisible(mainWindow) && mainWindow.areAppWindowBoundsLetterboxed()
                // Check for FLAG_SHOW_WALLPAPER explicitly instead of using
                // WindowContainer#showWallpaper because the later will return true when this
                // activity is using blurred wallpaper for letterbox backgroud.
                && (mainWindow.mAttrs.flags & FLAG_SHOW_WALLPAPER) == 0;
    }

    @VisibleForTesting
    boolean isSurfaceReadyAndVisible(WindowState mainWindow) {
        boolean surfaceReady = mainWindow.isDrawn() // Regular case
                // Waiting for relayoutWindow to call preserveSurface
                || mainWindow.isDragResizeChanged();
        return surfaceReady && (mActivityRecord.isVisible()
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

    private void updateRoundedCorners(WindowState mainWindow) {
        final SurfaceControl windowSurface = mainWindow.getSurfaceControl();
        if (windowSurface != null && windowSurface.isValid()) {
            final Transaction transaction = mActivityRecord.getSyncTransaction();

            if (!requiresRoundedCorners(mainWindow) || mActivityRecord.isInLetterboxAnimation()) {
                // We don't want corner radius on the window.
                // In the case the ActivityRecord requires a letterboxed animation we never want
                // rounded corners on the window because rounded corners are applied at the
                // animation-bounds surface level and rounded corners on the window would interfere
                // with that leading to unexpected rounded corner positioning during the animation.
                transaction
                        .setWindowCrop(windowSurface, null)
                        .setCornerRadius(windowSurface, 0);
                return;
            }

            Rect cropBounds = null;

            if (hasVisibleTaskbar(mainWindow)) {
                cropBounds = new Rect(mActivityRecord.getBounds());

                // Rounded corners should be displayed above the taskbar.
                // It is important to call adjustBoundsForTaskbarUnchecked before offsetTo
                // because taskbar bounds are in screen coordinates
                adjustBoundsForTaskbarUnchecked(mainWindow, cropBounds);

                // Activity bounds are in screen coordinates while (0,0) for activity's surface
                // control is at the top left corner of an app window so offsetting bounds
                // accordingly.
                cropBounds.offsetTo(0, 0);
            }

            transaction
                    .setWindowCrop(windowSurface, cropBounds)
                    .setCornerRadius(windowSurface, getRoundedCornersRadius(mainWindow));
        }
    }

    private boolean requiresRoundedCorners(WindowState mainWindow) {
        final InsetsSource taskbarInsetsSource = getTaskbarInsetsSource(mainWindow);

        return isLetterboxedNotForDisplayCutout(mainWindow)
                && mLetterboxConfiguration.isLetterboxActivityCornersRounded()
                && taskbarInsetsSource != null;
    }

    // Returns rounded corners radius the letterboxed activity should have based on override in
    // R.integer.config_letterboxActivityCornersRadius or min device bottom corner radii.
    // Device corners can be different on the right and left sides but we use the same radius
    // for all corners for consistency and pick a minimal bottom one for consistency with a
    // taskbar rounded corners.
    int getRoundedCornersRadius(WindowState mainWindow) {
        if (!requiresRoundedCorners(mainWindow)) {
            return 0;
        }

        if (mLetterboxConfiguration.getLetterboxActivityCornersRadius() >= 0) {
            return mLetterboxConfiguration.getLetterboxActivityCornersRadius();
        }

        final InsetsState insetsState = mainWindow.getInsetsState();
        return Math.min(
                getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_LEFT),
                getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_RIGHT));
    }

    /**
     * Returns whether the taskbar is visible. Returns false if the window is in immersive mode,
     * since the user can swipe to show/hide the taskbar as an overlay.
     */
    private boolean hasVisibleTaskbar(WindowState mainWindow) {
        final InsetsSource taskbarInsetsSource = getTaskbarInsetsSource(mainWindow);

        return taskbarInsetsSource != null
                && taskbarInsetsSource.isVisible();
    }

    private InsetsSource getTaskbarInsetsSource(WindowState mainWindow) {
        final InsetsState insetsState = mainWindow.getInsetsState();
        return insetsState.peekSource(InsetsState.ITYPE_EXTRA_NAVIGATION_BAR);
    }

    private void adjustBoundsForTaskbar(WindowState mainWindow, Rect bounds) {
        // Rounded corners should be displayed above the taskbar. When taskbar is hidden,
        // an insets frame is equal to a navigation bar which shouldn't affect position of
        // rounded corners since apps are expected to handle navigation bar inset.
        // This condition checks whether the taskbar is visible.
        // Do not crop the taskbar inset if the window is in immersive mode - the user can
        // swipe to show/hide the taskbar as an overlay.
        if (hasVisibleTaskbar(mainWindow)) {
            adjustBoundsForTaskbarUnchecked(mainWindow, bounds);
        }
    }

    private void adjustBoundsForTaskbarUnchecked(WindowState mainWindow, Rect bounds) {
        // Rounded corners should be displayed above the taskbar.
        bounds.bottom =
                Math.min(bounds.bottom, getTaskbarInsetsSource(mainWindow).getFrame().top);
        scaleIfNeeded(bounds);
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
                                    DeviceStateController.FoldState.HALF_FOLDED,
                                    false /* isTabletop */));
            positionToLog = letterboxHorizontalReachabilityPositionToLetterboxPosition(
                    letterboxPositionForHorizontalReachability);
        } else if (isVerticalReachabilityEnabled()) {
            int letterboxPositionForVerticalReachability = getLetterboxConfiguration()
                    .getLetterboxPositionForVerticalReachability(
                            isDisplayFullScreenAndInPosture(
                                    DeviceStateController.FoldState.HALF_FOLDED,
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
        // In case mActivityRecord.getCompatDisplayInsets() is not null we don't apply the
        // opaque activity constraints because we're expecting the activity is already letterboxed.
        if (mActivityRecord.getTask() == null || mActivityRecord.getCompatDisplayInsets() != null
                || mActivityRecord.fillsParent()) {
            return;
        }
        final ActivityRecord firstOpaqueActivityBeneath = mActivityRecord.getTask().getActivity(
                ActivityRecord::fillsParent, mActivityRecord, false /* includeBoundary */,
                true /* traverseTopToBottom */);
        if (firstOpaqueActivityBeneath == null) {
            // We skip letterboxing if the translucent activity doesn't have any opaque
            // activities beneath
            return;
        }
        inheritConfiguration(firstOpaqueActivityBeneath);
        mLetterboxConfigListener = WindowContainer.overrideConfigurationPropagation(
                mActivityRecord, firstOpaqueActivityBeneath,
                (opaqueConfig, transparentConfig) -> {
                    final Configuration mutatedConfiguration = new Configuration();
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
                    return mutatedConfiguration;
                });
    }

    /**
     * @return {@code true} if the current activity is translucent with an opaque activity
     * beneath. In this case it will inherit bounds, orientation and aspect ratios from
     * the first opaque activity beneath.
     */
    boolean hasInheritedLetterboxBehavior() {
        return mLetterboxConfigListener != null && !mActivityRecord.matchParentBounds();
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
                && mActivityRecord.mOrientation != SCREEN_ORIENTATION_UNSPECIFIED;
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

    float getInheritedSizeCompatScale() {
        return mInheritedSizeCompatScale;
    }

    @Configuration.Orientation
    int getInheritedOrientation() {
        return mInheritedOrientation;
    }

    public ActivityRecord.CompatDisplayInsets getInheritedCompatDisplayInsets() {
        return mInheritedCompatDisplayInsets;
    }

    private void inheritConfiguration(ActivityRecord firstOpaque) {
        // To avoid wrong behaviour, we're not forcing a specific aspet ratio to activities
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
        mIsInheritedInSizeCompatMode = firstOpaque.inSizeCompatMode();
        mInheritedSizeCompatScale = firstOpaque.getCompatScale();
        mInheritedCompatDisplayInsets = firstOpaque.getCompatDisplayInsets();
    }

    private void clearInheritedConfig() {
        mLetterboxConfigListener = null;
        mInheritedMinAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedMaxAspectRatio = UNDEFINED_ASPECT_RATIO;
        mInheritedOrientation = Configuration.ORIENTATION_UNDEFINED;
        mInheritedAppCompatState = APP_COMPAT_STATE_CHANGED__STATE__UNKNOWN;
        mIsInheritedInSizeCompatMode = false;
        mInheritedSizeCompatScale = 1f;
        mInheritedCompatDisplayInsets = null;
    }

    private void scaleIfNeeded(Rect bounds) {
        if (boundsNeedToScale()) {
            bounds.scale(1.0f / mActivityRecord.getCompatScale());
        }
    }

    private boolean boundsNeedToScale() {
        if (hasInheritedLetterboxBehavior()) {
            return mIsInheritedInSizeCompatMode
                    && mInheritedSizeCompatScale < 1.0f;
        } else {
            return mActivityRecord.inSizeCompatMode()
                    && mActivityRecord.getCompatScale() < 1.0f;
        }
    }
}
