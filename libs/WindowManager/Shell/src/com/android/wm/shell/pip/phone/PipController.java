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

package com.android.wm.shell.pip.phone;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.WindowManagerGlobal;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUtils;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipController implements Pip, PipTaskOrganizer.PipTransitionCallback {
    private static final String TAG = "PipController";

    private Context mContext;
    private ShellExecutor mMainExecutor;
    private DisplayController mDisplayController;
    private PipInputConsumer mPipInputConsumer;
    private WindowManagerShellWrapper mWindowManagerShellWrapper;
    private PipAppOpsListener mAppOpsListener;
    private PipMediaController mMediaController;
    private PipBoundsAlgorithm mPipBoundsAlgorithm;
    private PipBoundsState mPipBoundsState;
    private PipTouchHandler mTouchHandler;

    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();
    private final Rect mTmpInsetBounds = new Rect();

    private boolean mIsInFixedRotation;
    private Consumer<Boolean> mPinnedStackAnimationRecentsCallback;

    protected PhonePipMenuController mMenuController;
    protected PipTaskOrganizer mPipTaskOrganizer;
    protected PinnedStackListenerForwarder.PinnedStackListener mPinnedStackListener =
            new PipControllerPinnedStackListener();

    /**
     * Handler for display rotation changes.
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController = (
            int displayId, int fromRotation, int toRotation, WindowContainerTransaction t) -> {
        if (!mPipTaskOrganizer.isInPip() || mPipTaskOrganizer.isDeferringEnterPipAnimation()) {
            // Skip if we aren't in PIP or haven't actually entered PIP yet. We still need to update
            // the display layout in the bounds handler in this case.
            onDisplayRotationChangedNotInPip(mContext, toRotation);
            // do not forget to update the movement bounds as well.
            updateMovementBounds(mPipBoundsState.getNormalBounds(), true /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */, t);
            return;
        }
        // If there is an animation running (ie. from a shelf offset), then ensure that we calculate
        // the bounds for the next orientation using the destination bounds of the animation
        // TODO: Technically this should account for movement animation bounds as well
        Rect currentBounds = mPipTaskOrganizer.getCurrentOrAnimatingBounds();
        final Rect outBounds = new Rect();
        final boolean changed = onDisplayRotationChanged(mContext, outBounds, currentBounds,
                mTmpInsetBounds, displayId, fromRotation, toRotation, t);
        if (changed) {
            // If the pip was in the offset zone earlier, adjust the new bounds to the bottom of the
            // movement bounds
            mTouchHandler.adjustBoundsForRotation(outBounds, mPipBoundsState.getBounds(),
                    mTmpInsetBounds);

            // The bounds are being applied to a specific snap fraction, so reset any known offsets
            // for the previous orientation before updating the movement bounds.
            // We perform the resets if and only if this callback is due to screen rotation but
            // not during the fixed rotation. In fixed rotation case, app is about to enter PiP
            // and we need the offsets preserved to calculate the destination bounds.
            if (!mIsInFixedRotation) {
                // Update the shelf visibility without updating the movement bounds. We're already
                // updating them below with the |fromRotation| flag set, which is more accurate
                // than using the |fromShelfAdjustment|.
                mPipBoundsState.setShelfVisibility(false /* showing */, 0 /* height */,
                        false /* updateMovementBounds */);
                mPipBoundsState.setImeVisibility(false /* showing */, 0 /* height */);
                mTouchHandler.onShelfVisibilityChanged(false, 0);
                mTouchHandler.onImeVisibilityChanged(false, 0);
            }

            updateMovementBounds(outBounds, true /* fromRotation */, false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */, t);
        }
    };

    private final DisplayController.OnDisplaysChangedListener mFixedRotationListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onFixedRotationStarted(int displayId, int newRotation) {
                    mIsInFixedRotation = true;
                }

                @Override
                public void onFixedRotationFinished(int displayId) {
                    mIsInFixedRotation = false;
                }

                @Override
                public void onDisplayAdded(int displayId) {
                    mPipBoundsState.setDisplayLayout(
                            mDisplayController.getDisplayLayout(displayId));
                }
            };

    /**
     * Handler for messages from the PIP controller.
     */
    private class PipControllerPinnedStackListener extends
            PinnedStackListenerForwarder.PinnedStackListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mPipBoundsState.setImeVisibility(imeVisible, imeHeight);
            mTouchHandler.onImeVisibilityChanged(imeVisible, imeHeight);
        }

        @Override
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {
            updateMovementBounds(null /* toBounds */,
                    false /* fromRotation */, fromImeAdjustment, false /* fromShelfAdjustment */,
                    null /* windowContainerTransaction */);
        }

        @Override
        public void onActionsChanged(ParceledListSlice<RemoteAction> actions) {
            mMenuController.setAppActions(actions);
        }

        @Override
        public void onActivityHidden(ComponentName componentName) {
            if (componentName.equals(mPipBoundsState.getLastPipComponentName())) {
                // The activity was removed, we don't want to restore to the reentry state
                // saved for this component anymore.
                mPipBoundsState.setLastPipComponentName(null);
            }
        }

        @Override
        public void onAspectRatioChanged(float aspectRatio) {
            // TODO(b/169373982): Remove this callback as it is redundant with PipTaskOrg params
            // change.
            mPipBoundsState.setAspectRatio(aspectRatio);
            mTouchHandler.onAspectRatioChanged();
        }
    }

    protected PipController(Context context,
            DisplayController displayController,
            PipAppOpsListener pipAppOpsListener,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            @NonNull PipBoundsState pipBoundsState,
            PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController,
            PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            ShellExecutor mainExecutor
    ) {
        // Ensure that we are the primary user's SystemUI.
        final int processUser = UserManager.get(context).getUserHandle();
        if (processUser != UserHandle.USER_SYSTEM) {
            throw new IllegalStateException("Non-primary Pip component not currently supported.");
        }

        mContext = context;
        mWindowManagerShellWrapper = windowManagerShellWrapper;
        mDisplayController = displayController;
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipBoundsState = pipBoundsState;
        mPipTaskOrganizer = pipTaskOrganizer;
        mMainExecutor = mainExecutor;
        mMediaController = pipMediaController;
        mMenuController = phonePipMenuController;
        mTouchHandler = pipTouchHandler;
        mAppOpsListener = pipAppOpsListener;
        mPipInputConsumer = new PipInputConsumer(WindowManagerGlobal.getWindowManagerService(),
                INPUT_CONSUMER_PIP);
        mPipTaskOrganizer.registerPipTransitionCallback(this);
        mPipTaskOrganizer.registerOnDisplayIdChangeCallback((int displayId) -> {
            final DisplayInfo newDisplayInfo = new DisplayInfo();
            displayController.getDisplay(displayId).getDisplayInfo(newDisplayInfo);
            mPipBoundsState.setDisplayInfo(newDisplayInfo);
            updateMovementBounds(null /* toBounds */, false /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */,
                    null /* wct */);
        });
        mPipBoundsState.setOnMinimalSizeChangeCallback(
                () -> {
                    // The minimal size drives the normal bounds, so they need to be recalculated.
                    updateMovementBounds(null /* toBounds */, false /* fromRotation */,
                            false /* fromImeAdjustment */, false /* fromShelfAdjustment */,
                            null /* wct */);
                });
        mPipBoundsState.setOnShelfVisibilityChangeCallback(
                (isShowing, height, updateMovementBounds) -> {
                    mTouchHandler.onShelfVisibilityChanged(isShowing, height);
                    if (updateMovementBounds) {
                        updateMovementBounds(mPipBoundsState.getBounds(),
                                false /* fromRotation */, false /* fromImeAdjustment */,
                                true /* fromShelfAdjustment */,
                                null /* windowContainerTransaction */);
                    }
                });
        if (mTouchHandler != null) {
            // Register the listener for input consumer touch events. Only for Phone
            mPipInputConsumer.setInputListener(mTouchHandler::handleTouchEvent);
            mPipInputConsumer.setRegistrationListener(mTouchHandler::onRegistrationChanged);
        }
        displayController.addDisplayChangingController(mRotationController);
        displayController.addDisplayWindowListener(mFixedRotationListener);

        // Ensure that we have the display info in case we get calls to update the bounds before the
        // listener calls back
        final DisplayInfo displayInfo = new DisplayInfo();
        context.getDisplay().getDisplayInfo(displayInfo);
        mPipBoundsState.setDisplayInfo(displayInfo);

        try {
            mWindowManagerShellWrapper.addPinnedStackListener(mPinnedStackListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register pinned stack listener", e);
        }

        try {
            ActivityTaskManager.RootTaskInfo taskInfo = ActivityTaskManager.getService()
                    .getRootTaskInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (taskInfo != null) {
                // If SystemUI restart, and it already existed a pinned stack,
                // register the pip input consumer to ensure touch can send to it.
                mPipInputConsumer.registerInputConsumer(true /* withSfVsync */);
            }
        } catch (RemoteException | UnsupportedOperationException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
            e.printStackTrace();
        }

        // Handle for system task stack changes.
        taskStackListener.addListener(
                new TaskStackListenerCallback() {
                    @Override
                    public void onActivityPinned(String packageName, int userId, int taskId,
                            int stackId) {
                        mMainExecutor.execute(() -> {
                            mTouchHandler.onActivityPinned();
                            mMediaController.onActivityPinned();
                            mAppOpsListener.onActivityPinned(packageName);
                        });
                        mPipInputConsumer.registerInputConsumer(true /* withSfVsync */);
                    }

                    @Override
                    public void onActivityUnpinned() {
                        final Pair<ComponentName, Integer> topPipActivityInfo =
                                PipUtils.getTopPipActivity(mContext);
                        final ComponentName topActivity = topPipActivityInfo.first;
                        mMainExecutor.execute(() -> {
                            mTouchHandler.onActivityUnpinned(topActivity);
                            mAppOpsListener.onActivityUnpinned();
                        });
                        mPipInputConsumer.unregisterInputConsumer();
                    }

                    @Override
                    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                            boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                        if (task.getWindowingMode() != WINDOWING_MODE_PINNED) {
                            return;
                        }
                        mTouchHandler.getMotionHelper().expandLeavePip(
                                clearedTask /* skipAnimation */);
                    }
                });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mMainExecutor.execute(() -> {
            mPipBoundsAlgorithm.onConfigurationChanged(mContext);
            mTouchHandler.onConfigurationChanged();
            mPipBoundsState.onConfigurationChanged();
        });
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        mMainExecutor.execute(() -> {
            mPipTaskOrganizer.onDensityOrFontScaleChanged(mContext);
        });
    }

    @Override
    public void onOverlayChanged() {
        mMainExecutor.execute(() -> {
            mPipBoundsState.setDisplayLayout(new DisplayLayout(mContext, mContext.getDisplay()));
            updateMovementBounds(null /* toBounds */,
                    false /* fromRotation */, false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */,
                    null /* windowContainerTransaction */);
        });
    }

    @Override
    public void registerSessionListenerForCurrentUser() {
        mMediaController.registerSessionListenerForCurrentUser();
    }

    @Override
    public void onSystemUiStateChanged(boolean isValidState, int flag) {
        mTouchHandler.onSystemUiStateChanged(isValidState);
    }

    /**
     * Expands the PIP.
     */
    @Override
    public void expandPip() {
        mTouchHandler.getMotionHelper().expandLeavePip(false /* skipAnimation */);
    }

    @Override
    public PipTouchHandler getPipTouchHandler() {
        return mTouchHandler;
    }

    /**
     * Hides the PIP menu.
     */
    @Override
    public void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {
        mMenuController.hideMenu(onStartCallback, onEndCallback);
    }

    /**
     * Sent from KEYCODE_WINDOW handler in PhoneWindowManager, to request the menu to be shown.
     */
    public void showPictureInPictureMenu() {
        mTouchHandler.showPictureInPictureMenu();
    }

    /**
     * Sets a customized touch gesture that replaces the default one.
     */
    public void setTouchGesture(PipTouchGesture gesture) {
        mTouchHandler.setTouchGesture(gesture);
    }

    /**
     * Sets both shelf visibility and its height.
     */
    @Override
    public void setShelfHeight(boolean visible, int height) {
        mMainExecutor.execute(() -> setShelfHeightLocked(visible, height));
    }

    private void setShelfHeightLocked(boolean visible, int height) {
        final int shelfHeight = visible ? height : 0;
        mPipBoundsState.setShelfVisibility(visible, shelfHeight);
    }

    @Override
    public void setPinnedStackAnimationType(int animationType) {
        mMainExecutor.execute(() -> mPipTaskOrganizer.setOneShotAnimationType(animationType));
    }

    @Override
    public void setPinnedStackAnimationListener(Consumer<Boolean> callback) {
        mMainExecutor.execute(() -> mPinnedStackAnimationRecentsCallback = callback);
    }

    @Override
    public Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
            PictureInPictureParams pictureInPictureParams,
            int launcherRotation, int shelfHeight) {
        setShelfHeightLocked(shelfHeight > 0 /* visible */, shelfHeight);
        onDisplayRotationChangedNotInPip(mContext, launcherRotation);
        return mPipTaskOrganizer.startSwipePipToHome(componentName, activityInfo,
                pictureInPictureParams);
    }

    @Override
    public void stopSwipePipToHome(ComponentName componentName, Rect destinationBounds) {
        mPipTaskOrganizer.stopSwipePipToHome(componentName, destinationBounds);
    }

    @Override
    public void onPipTransitionStarted(ComponentName activity, int direction, Rect pipBounds) {
        if (isOutPipDirection(direction)) {
            // Exiting PIP, save the reentry state to restore to when re-entering.
            saveReentryState(pipBounds);
        }
        // Disable touches while the animation is running
        mTouchHandler.setTouchEnabled(false);
        if (mPinnedStackAnimationRecentsCallback != null) {
            mPinnedStackAnimationRecentsCallback.accept(true);
        }
    }

    /** Save the state to restore to on re-entry. */
    public void saveReentryState(Rect pipBounds) {
        float snapFraction = mPipBoundsAlgorithm.getSnapFraction(pipBounds);
        if (mPipBoundsState.hasUserResizedPip()) {
            final Rect reentryBounds = mTouchHandler.getUserResizeBounds();
            final Size reentrySize = new Size(reentryBounds.width(), reentryBounds.height());
            mPipBoundsState.saveReentryState(reentrySize, snapFraction);
        } else {
            mPipBoundsState.saveReentryState(null /* bounds */, snapFraction);
        }
    }

    /**
     * Set a listener to watch out for PiP bounds. This is mostly used by SystemUI's
     * Back-gesture handler, to avoid conflicting with PiP when it's stashed.
     */
    @Override
    public void setPipExclusionBoundsChangeListener(
            Consumer<Rect> pipExclusionBoundsChangeListener) {
        mTouchHandler.setPipExclusionBoundsChangeListener(pipExclusionBoundsChangeListener);
    }

    @Override
    public void onPipTransitionFinished(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled(direction);
    }

    @Override
    public void onPipTransitionCanceled(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled(direction);
    }

    private void onPipTransitionFinishedOrCanceled(int direction) {
        // Re-enable touches after the animation completes
        mTouchHandler.setTouchEnabled(true);
        mTouchHandler.onPinnedStackAnimationEnded(direction);
        mMenuController.onPinnedStackAnimationEnded();
    }

    private void updateMovementBounds(@Nullable Rect toBounds, boolean fromRotation,
            boolean fromImeAdjustment, boolean fromShelfAdjustment,
            WindowContainerTransaction wct) {
        // Populate inset / normal bounds and DisplayInfo from mPipBoundsHandler before
        // passing to mTouchHandler/mPipTaskOrganizer
        final Rect outBounds = new Rect(toBounds);
        mTmpDisplayInfo.copyFrom(mPipBoundsState.getDisplayInfo());

        mPipBoundsAlgorithm.getInsetBounds(mTmpInsetBounds);
        mPipBoundsState.setNormalBounds(mPipBoundsAlgorithm.getNormalBounds());
        if (outBounds.isEmpty()) {
            outBounds.set(mPipBoundsAlgorithm.getDefaultBounds());
        }

        // mTouchHandler would rely on the bounds populated from mPipTaskOrganizer
        mPipTaskOrganizer.onMovementBoundsChanged(outBounds, fromRotation, fromImeAdjustment,
                fromShelfAdjustment, wct);
        mTouchHandler.onMovementBoundsChanged(mTmpInsetBounds, mPipBoundsState.getNormalBounds(),
                outBounds, fromImeAdjustment, fromShelfAdjustment,
                mTmpDisplayInfo.rotation);
    }

    /**
     * Updates the display info and display layout on rotation change. This is needed even when we
     * aren't in PIP because the rotation layout is used to calculate the proper insets for the
     * next enter animation into PIP.
     */
    private void onDisplayRotationChangedNotInPip(Context context, int toRotation) {
        // Update the display layout, note that we have to do this on every rotation even if we
        // aren't in PIP since we need to update the display layout to get the right resources
        mPipBoundsState.getDisplayLayout().rotateTo(context.getResources(), toRotation);

        // Populate the new {@link #mDisplayInfo}.
        // The {@link DisplayInfo} queried from DisplayManager would be the one before rotation,
        // therefore, the width/height may require a swap first.
        // Moving forward, we should get the new dimensions after rotation from DisplayLayout.
        mPipBoundsState.setDisplayRotation(toRotation);
        updateDisplayInfoIfNeeded();
    }

    /**
     * Updates the display info, calculating and returning the new stack and movement bounds in the
     * new orientation of the device if necessary.
     *
     * @return {@code true} if internal {@link DisplayInfo} is rotated, {@code false} otherwise.
     */
    private boolean onDisplayRotationChanged(Context context, Rect outBounds, Rect oldBounds,
            Rect outInsetBounds,
            int displayId, int fromRotation, int toRotation, WindowContainerTransaction t) {
        // Bail early if the event is not sent to current {@link #mDisplayInfo}
        if ((displayId != mPipBoundsState.getDisplayInfo().displayId)
                || (fromRotation == toRotation)) {
            return false;
        }

        // Bail early if the pinned task is staled.
        final ActivityTaskManager.RootTaskInfo pinnedTaskInfo;
        try {
            pinnedTaskInfo = ActivityTaskManager.getService()
                    .getRootTaskInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (pinnedTaskInfo == null) return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get RootTaskInfo for pinned task", e);
            return false;
        }
        final PipSnapAlgorithm pipSnapAlgorithm = mPipBoundsAlgorithm.getSnapAlgorithm();

        // Calculate the snap fraction of the current stack along the old movement bounds
        final Rect postChangeStackBounds = new Rect(oldBounds);
        final float snapFraction = pipSnapAlgorithm.getSnapFraction(postChangeStackBounds,
                        mPipBoundsAlgorithm.getMovementBounds(postChangeStackBounds),
                        mPipBoundsState.getStashedState());

        // Update the display layout
        mPipBoundsState.getDisplayLayout().rotateTo(context.getResources(), toRotation);

        // Populate the new {@link #mDisplayInfo}.
        // The {@link DisplayInfo} queried from DisplayManager would be the one before rotation,
        // therefore, the width/height may require a swap first.
        // Moving forward, we should get the new dimensions after rotation from DisplayLayout.
        mPipBoundsState.getDisplayInfo().rotation = toRotation;
        updateDisplayInfoIfNeeded();

        // Calculate the stack bounds in the new orientation based on same fraction along the
        // rotated movement bounds.
        final Rect postChangeMovementBounds = mPipBoundsAlgorithm.getMovementBounds(
                postChangeStackBounds, false /* adjustForIme */);
        pipSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                snapFraction, mPipBoundsState.getStashedState(), mPipBoundsState.getStashOffset(),
                mPipBoundsState.getDisplayBounds());

        mPipBoundsAlgorithm.getInsetBounds(outInsetBounds);
        outBounds.set(postChangeStackBounds);
        t.setBounds(pinnedTaskInfo.token, outBounds);
        return true;
    }

    private void updateDisplayInfoIfNeeded() {
        final DisplayInfo displayInfo = mPipBoundsState.getDisplayInfo();
        final boolean updateNeeded;
        if ((displayInfo.rotation == ROTATION_0) || (displayInfo.rotation == ROTATION_180)) {
            updateNeeded = (displayInfo.logicalWidth > displayInfo.logicalHeight);
        } else {
            updateNeeded = (displayInfo.logicalWidth < displayInfo.logicalHeight);
        }
        if (updateNeeded) {
            final int newLogicalHeight = displayInfo.logicalWidth;
            displayInfo.logicalWidth = displayInfo.logicalHeight;
            displayInfo.logicalHeight = newLogicalHeight;
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        mMenuController.dump(pw, innerPrefix);
        mTouchHandler.dump(pw, innerPrefix);
        mPipBoundsAlgorithm.dump(pw, innerPrefix);
        mPipTaskOrganizer.dump(pw, innerPrefix);
        mPipBoundsState.dump(pw, innerPrefix);
        mPipInputConsumer.dump(pw, innerPrefix);
    }

    /**
     * Instantiates {@link PipController}, returns {@code null} if the feature not supported.
     */
    @Nullable
    public static PipController create(Context context, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController, PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler, WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener, ShellExecutor mainExecutor) {
        if (!context.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            Slog.w(TAG, "Device doesn't support Pip feature");
            return null;
        }

        return new PipController(context, displayController, pipAppOpsListener, pipBoundsAlgorithm,
                pipBoundsState, pipMediaController, phonePipMenuController, pipTaskOrganizer,
                pipTouchHandler, windowManagerShellWrapper, taskStackListener, mainExecutor);
    }
}
