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
import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_PIP_TRANSITION;
import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_EXPAND_OR_UNEXPAND;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_REMOVE_STACK;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SNAP_AFTER_RESIZE;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_USER_RESIZE;
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
import android.util.Pair;
import android.util.Size;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SingleInstanceRemoteListener;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.android.wm.shell.pip.IPip;
import com.android.wm.shell.pip.IPipAnimationListener;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipUtils;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipController implements PipTransitionController.PipTransitionCallback,
        RemoteCallable<PipController>, DisplayController.OnDisplaysChangedListener {
    private static final String TAG = "PipController";

    private Context mContext;
    protected ShellExecutor mMainExecutor;
    private DisplayController mDisplayController;
    private PipInputConsumer mPipInputConsumer;
    private WindowManagerShellWrapper mWindowManagerShellWrapper;
    private PipAppOpsListener mAppOpsListener;
    private PipMediaController mMediaController;
    private PipBoundsAlgorithm mPipBoundsAlgorithm;
    private PipBoundsState mPipBoundsState;
    private PipTouchHandler mTouchHandler;
    private PipTransitionController mPipTransitionController;
    private TaskStackListenerImpl mTaskStackListener;
    private Optional<OneHandedController> mOneHandedController;
    protected final PipImpl mImpl;

    private final Rect mTmpInsetBounds = new Rect();

    private boolean mIsInFixedRotation;
    private PipAnimationListener mPinnedStackAnimationRecentsCallback;

    protected PhonePipMenuController mMenuController;
    protected PipTaskOrganizer mPipTaskOrganizer;
    protected PinnedStackListenerForwarder.PinnedTaskListener mPinnedTaskListener =
            new PipControllerPinnedTaskListener();

    private interface PipAnimationListener {
        /**
         * Notifies the listener that the Pip animation is started.
         */
        void onPipAnimationStarted();

        /**
         * Notifies the listener about PiP round corner radius changes.
         * Listener can expect an immediate callback the first time they attach.
         *
         * @param cornerRadius the pixel value of the corner radius, zero means it's disabled.
         */
        void onPipCornerRadiusChanged(int cornerRadius);

        /**
         * Notifies the listener that user leaves PiP by tapping on the expand button.
         */
        void onExpandPip();
    }

    /**
     * Handler for display rotation changes.
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController = (
            int displayId, int fromRotation, int toRotation, WindowContainerTransaction t) -> {
        if (mPipTransitionController.handleRotateDisplay(fromRotation, toRotation, t)) {
            return;
        }
        if (mPipBoundsState.getDisplayLayout().rotation() == toRotation) {
            // The same rotation may have been set by auto PiP-able or fixed rotation. So notify
            // the change with fromRotation=false to apply the rotated destination bounds from
            // PipTaskOrganizer#onMovementBoundsChanged.
            updateMovementBounds(null, false /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */, t);
            return;
        }
        if (!mPipTaskOrganizer.isInPip() || mPipTaskOrganizer.isEntryScheduled()) {
            // Update display layout and bounds handler if we aren't in PIP or haven't actually
            // entered PIP yet.
            onDisplayRotationChangedNotInPip(mContext, toRotation);
            // do not forget to update the movement bounds as well.
            updateMovementBounds(mPipBoundsState.getNormalBounds(), true /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */, t);
            mPipTaskOrganizer.onDisplayRotationSkipped();
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

    @VisibleForTesting
    final DisplayController.OnDisplaysChangedListener mDisplaysChangedListener =
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
                    if (displayId != mPipBoundsState.getDisplayId()) {
                        return;
                    }
                    onDisplayChanged(mDisplayController.getDisplayLayout(displayId),
                            false /* saveRestoreSnapFraction */);
                }

                @Override
                public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                    if (displayId != mPipBoundsState.getDisplayId()) {
                        return;
                    }
                    onDisplayChanged(mDisplayController.getDisplayLayout(displayId),
                            true /* saveRestoreSnapFraction */);
                }
            };

    /**
     * Handler for messages from the PIP controller.
     */
    private class PipControllerPinnedTaskListener extends
            PinnedStackListenerForwarder.PinnedTaskListener {
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
        public void onActionsChanged(ParceledListSlice<RemoteAction> actions,
                RemoteAction closeAction) {
            mMenuController.setAppActions(actions, closeAction);
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

    /**
     * Instantiates {@link PipController}, returns {@code null} if the feature not supported.
     */
    @Nullable
    public static Pip create(Context context, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController, PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler, PipTransitionController pipTransitionController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            Optional<OneHandedController> oneHandedController,
            ShellExecutor mainExecutor) {
        if (!context.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Device doesn't support Pip feature", TAG);
            return null;
        }

        return new PipController(context, displayController, pipAppOpsListener, pipBoundsAlgorithm,
                pipBoundsState, pipMediaController, phonePipMenuController, pipTaskOrganizer,
                pipTouchHandler, pipTransitionController, windowManagerShellWrapper,
                taskStackListener, oneHandedController, mainExecutor)
                .mImpl;
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
            PipTransitionController pipTransitionController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            Optional<OneHandedController> oneHandedController,
            ShellExecutor mainExecutor
    ) {
        // Ensure that we are the primary user's SystemUI.
        final int processUser = UserManager.get(context).getProcessUserId();
        if (processUser != UserHandle.USER_SYSTEM) {
            throw new IllegalStateException("Non-primary Pip component not currently supported.");
        }

        mContext = context;
        mImpl = new PipImpl();
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
        mOneHandedController = oneHandedController;
        mPipTransitionController = pipTransitionController;
        mTaskStackListener = taskStackListener;
        //TODO: move this to ShellInit when PipController can be injected
        mMainExecutor.execute(this::init);
    }

    public void init() {
        mPipInputConsumer = new PipInputConsumer(WindowManagerGlobal.getWindowManagerService(),
                INPUT_CONSUMER_PIP, mMainExecutor);
        mPipTransitionController.registerPipTransitionCallback(this);
        mPipTaskOrganizer.registerOnDisplayIdChangeCallback((int displayId) -> {
            mPipBoundsState.setDisplayId(displayId);
            onDisplayChanged(mDisplayController.getDisplayLayout(displayId),
                    false /* saveRestoreSnapFraction */);
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
        mDisplayController.addDisplayChangingController(mRotationController);
        mDisplayController.addDisplayWindowListener(mDisplaysChangedListener);

        // Ensure that we have the display info in case we get calls to update the bounds before the
        // listener calls back
        mPipBoundsState.setDisplayId(mContext.getDisplayId());
        mPipBoundsState.setDisplayLayout(new DisplayLayout(mContext, mContext.getDisplay()));

        try {
            mWindowManagerShellWrapper.addPinnedStackListener(mPinnedTaskListener);
        } catch (RemoteException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to register pinned stack listener, %s", TAG, e);
        }

        try {
            ActivityTaskManager.RootTaskInfo taskInfo = ActivityTaskManager.getService()
                    .getRootTaskInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (taskInfo != null) {
                // If SystemUI restart, and it already existed a pinned stack,
                // register the pip input consumer to ensure touch can send to it.
                mPipInputConsumer.registerInputConsumer();
            }
        } catch (RemoteException | UnsupportedOperationException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to register pinned stack listener, %s", TAG, e);
            e.printStackTrace();
        }

        // Handle for system task stack changes.
        mTaskStackListener.addListener(
                new TaskStackListenerCallback() {
                    @Override
                    public void onActivityPinned(String packageName, int userId, int taskId,
                            int stackId) {
                        mTouchHandler.onActivityPinned();
                        mMediaController.onActivityPinned();
                        mAppOpsListener.onActivityPinned(packageName);
                        mPipInputConsumer.registerInputConsumer();
                    }

                    @Override
                    public void onActivityUnpinned() {
                        final Pair<ComponentName, Integer> topPipActivityInfo =
                                PipUtils.getTopPipActivity(mContext);
                        final ComponentName topActivity = topPipActivityInfo.first;
                        mTouchHandler.onActivityUnpinned(topActivity);
                        mAppOpsListener.onActivityUnpinned();
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

        mOneHandedController.ifPresent(controller -> {
            controller.asOneHanded().registerTransitionCallback(
                    new OneHandedTransitionCallback() {
                        @Override
                        public void onStartFinished(Rect bounds) {
                            mTouchHandler.setOhmOffset(bounds.top);
                        }

                        @Override
                        public void onStopFinished(Rect bounds) {
                            mTouchHandler.setOhmOffset(bounds.top);
                        }
                    });
        });
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    @Override
    public void onKeepClearAreasChanged(int displayId, Set<Rect> restricted,
            Set<Rect> unrestricted) {
        if (mPipBoundsState.getDisplayId() == displayId) {
            mPipBoundsState.setKeepClearAreas(restricted, unrestricted);
        }
    }

    private void onConfigurationChanged(Configuration newConfig) {
        mPipBoundsAlgorithm.onConfigurationChanged(mContext);
        mTouchHandler.onConfigurationChanged();
        mPipBoundsState.onConfigurationChanged();
    }

    private void onDensityOrFontScaleChanged() {
        mPipTaskOrganizer.onDensityOrFontScaleChanged(mContext);
        onPipCornerRadiusChanged();
    }

    private void onOverlayChanged() {
        mTouchHandler.onOverlayChanged();
        onDisplayChanged(new DisplayLayout(mContext, mContext.getDisplay()),
                false /* saveRestoreSnapFraction */);
    }

    private void onDisplayChanged(DisplayLayout layout, boolean saveRestoreSnapFraction) {
        if (mPipBoundsState.getDisplayLayout().isSameGeometry(layout)) {
            return;
        }
        Runnable updateDisplayLayout = () -> {
            final boolean fromRotation = Transitions.ENABLE_SHELL_TRANSITIONS
                    && mPipBoundsState.getDisplayLayout().rotation() != layout.rotation();
            mPipBoundsState.setDisplayLayout(layout);
            final WindowContainerTransaction wct =
                    fromRotation ? new WindowContainerTransaction() : null;
            updateMovementBounds(null /* toBounds */,
                    fromRotation, false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */,
                    wct /* windowContainerTransaction */);
            if (wct != null) {
                mPipTaskOrganizer.applyFinishBoundsResize(wct, TRANSITION_DIRECTION_SAME,
                        false /* wasPipTopLeft */);
            }
        };

        if (mPipTaskOrganizer.isInPip() && saveRestoreSnapFraction) {
            // Calculate the snap fraction of the current stack along the old movement bounds
            final PipSnapAlgorithm pipSnapAlgorithm = mPipBoundsAlgorithm.getSnapAlgorithm();
            final Rect postChangeStackBounds = new Rect(mPipBoundsState.getBounds());
            final float snapFraction = pipSnapAlgorithm.getSnapFraction(postChangeStackBounds,
                    mPipBoundsAlgorithm.getMovementBounds(postChangeStackBounds),
                    mPipBoundsState.getStashedState());

            updateDisplayLayout.run();

            // Calculate the stack bounds in the new orientation based on same fraction along the
            // rotated movement bounds.
            final Rect postChangeMovementBounds = mPipBoundsAlgorithm.getMovementBounds(
                    postChangeStackBounds, false /* adjustForIme */);
            pipSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                    snapFraction, mPipBoundsState.getStashedState(),
                    mPipBoundsState.getStashOffset(),
                    mPipBoundsState.getDisplayBounds(),
                    mPipBoundsState.getDisplayLayout().stableInsets());

            mTouchHandler.getMotionHelper().movePip(postChangeStackBounds);
        } else {
            updateDisplayLayout.run();
        }
    }

    private void registerSessionListenerForCurrentUser() {
        mMediaController.registerSessionListenerForCurrentUser();
    }

    private void onSystemUiStateChanged(boolean isValidState, int flag) {
        mTouchHandler.onSystemUiStateChanged(isValidState);
    }

    /**
     * Expands the PIP.
     */
    public void expandPip() {
        mTouchHandler.getMotionHelper().expandLeavePip(false /* skipAnimation */);
    }

    /**
     * Hides the PIP menu.
     */
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
    private void setShelfHeight(boolean visible, int height) {
        setShelfHeightLocked(visible, height);
    }

    private void setShelfHeightLocked(boolean visible, int height) {
        final int shelfHeight = visible ? height : 0;
        mPipBoundsState.setShelfVisibility(visible, shelfHeight);
    }

    private void setPinnedStackAnimationType(int animationType) {
        mPipTaskOrganizer.setOneShotAnimationType(animationType);
        mPipTransitionController.setIsFullAnimation(
                animationType == PipAnimationController.ANIM_TYPE_BOUNDS);
    }

    private void setPinnedStackAnimationListener(PipAnimationListener callback) {
        mPinnedStackAnimationRecentsCallback = callback;
        onPipCornerRadiusChanged();
    }

    private void onPipCornerRadiusChanged() {
        if (mPinnedStackAnimationRecentsCallback != null) {
            final int cornerRadius =
                    mContext.getResources().getDimensionPixelSize(R.dimen.pip_corner_radius);
            mPinnedStackAnimationRecentsCallback.onPipCornerRadiusChanged(cornerRadius);
        }
    }

    private Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
            PictureInPictureParams pictureInPictureParams,
            int launcherRotation, int shelfHeight) {
        setShelfHeightLocked(shelfHeight > 0 /* visible */, shelfHeight);
        onDisplayRotationChangedNotInPip(mContext, launcherRotation);
        final Rect entryBounds = mPipTaskOrganizer.startSwipePipToHome(componentName, activityInfo,
                pictureInPictureParams);
        // sync mPipBoundsState with the newly calculated bounds.
        mPipBoundsState.setNormalBounds(entryBounds);
        return entryBounds;
    }

    private void stopSwipePipToHome(int taskId, ComponentName componentName, Rect destinationBounds,
            SurfaceControl overlay) {
        mPipTaskOrganizer.stopSwipePipToHome(taskId, componentName, destinationBounds, overlay);
    }

    private String getTransitionTag(int direction) {
        switch (direction) {
            case TRANSITION_DIRECTION_TO_PIP:
                return "TRANSITION_TO_PIP";
            case TRANSITION_DIRECTION_LEAVE_PIP:
                return "TRANSITION_LEAVE_PIP";
            case TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN:
                return "TRANSITION_LEAVE_PIP_TO_SPLIT_SCREEN";
            case TRANSITION_DIRECTION_REMOVE_STACK:
                return "TRANSITION_REMOVE_STACK";
            case TRANSITION_DIRECTION_SNAP_AFTER_RESIZE:
                return "TRANSITION_SNAP_AFTER_RESIZE";
            case TRANSITION_DIRECTION_USER_RESIZE:
                return "TRANSITION_USER_RESIZE";
            case TRANSITION_DIRECTION_EXPAND_OR_UNEXPAND:
                return "TRANSITION_EXPAND_OR_UNEXPAND";
            default:
                return "TRANSITION_LEAVE_UNKNOWN";
        }
    }

    @Override
    public void onPipTransitionStarted(int direction, Rect pipBounds) {
        // Begin InteractionJankMonitor with PIP transition CUJs
        final InteractionJankMonitor.Configuration.Builder builder =
                InteractionJankMonitor.Configuration.Builder.withSurface(
                        CUJ_PIP_TRANSITION, mContext, mPipTaskOrganizer.getSurfaceControl())
                .setTag(getTransitionTag(direction))
                .setTimeout(2000);
        InteractionJankMonitor.getInstance().begin(builder);

        if (isOutPipDirection(direction)) {
            // Exiting PIP, save the reentry state to restore to when re-entering.
            saveReentryState(pipBounds);
        }
        // Disable touches while the animation is running
        mTouchHandler.setTouchEnabled(false);
        if (mPinnedStackAnimationRecentsCallback != null) {
            mPinnedStackAnimationRecentsCallback.onPipAnimationStarted();
            if (direction == TRANSITION_DIRECTION_LEAVE_PIP) {
                mPinnedStackAnimationRecentsCallback.onExpandPip();
            }
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

    @Override
    public void onPipTransitionFinished(int direction) {
        onPipTransitionFinishedOrCanceled(direction);
    }

    @Override
    public void onPipTransitionCanceled(int direction) {
        onPipTransitionFinishedOrCanceled(direction);
    }

    private void onPipTransitionFinishedOrCanceled(int direction) {
        // End InteractionJankMonitor with PIP transition by CUJs
        InteractionJankMonitor.getInstance().end(CUJ_PIP_TRANSITION);

        // Re-enable touches after the animation completes
        mTouchHandler.setTouchEnabled(true);
        mTouchHandler.onPinnedStackAnimationEnded(direction);
    }

    private void updateMovementBounds(@Nullable Rect toBounds, boolean fromRotation,
            boolean fromImeAdjustment, boolean fromShelfAdjustment,
            WindowContainerTransaction wct) {
        // Populate inset / normal bounds and DisplayInfo from mPipBoundsHandler before
        // passing to mTouchHandler/mPipTaskOrganizer
        final Rect outBounds = new Rect(toBounds);
        final int rotation = mPipBoundsState.getDisplayLayout().rotation();

        mPipBoundsAlgorithm.getInsetBounds(mTmpInsetBounds);
        mPipBoundsState.setNormalBounds(mPipBoundsAlgorithm.getNormalBounds());
        if (outBounds.isEmpty()) {
            outBounds.set(mPipBoundsAlgorithm.getDefaultBounds());
        }

        // mTouchHandler would rely on the bounds populated from mPipTaskOrganizer
        mPipTaskOrganizer.onMovementBoundsChanged(outBounds, fromRotation, fromImeAdjustment,
                fromShelfAdjustment, wct);
        mPipTaskOrganizer.finishResizeForMenu(outBounds);
        mTouchHandler.onMovementBoundsChanged(mTmpInsetBounds, mPipBoundsState.getNormalBounds(),
                outBounds, fromImeAdjustment, fromShelfAdjustment, rotation);
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
        // Bail early if the event is not sent to current display
        if ((displayId != mPipBoundsState.getDisplayId()) || (fromRotation == toRotation)) {
            return false;
        }

        // Bail early if the pinned task is staled.
        final ActivityTaskManager.RootTaskInfo pinnedTaskInfo;
        try {
            pinnedTaskInfo = ActivityTaskManager.getService()
                    .getRootTaskInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (pinnedTaskInfo == null) return false;
        } catch (RemoteException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to get RootTaskInfo for pinned task, %s", TAG, e);
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

        // Calculate the stack bounds in the new orientation based on same fraction along the
        // rotated movement bounds.
        final Rect postChangeMovementBounds = mPipBoundsAlgorithm.getMovementBounds(
                postChangeStackBounds, false /* adjustForIme */);
        pipSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                snapFraction, mPipBoundsState.getStashedState(), mPipBoundsState.getStashOffset(),
                mPipBoundsState.getDisplayBounds(),
                mPipBoundsState.getDisplayLayout().stableInsets());

        mPipBoundsAlgorithm.getInsetBounds(outInsetBounds);
        outBounds.set(postChangeStackBounds);
        t.setBounds(pinnedTaskInfo.token, outBounds);
        return true;
    }

    private void dump(PrintWriter pw) {
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
     * The interface for calls from outside the Shell, within the host process.
     */
    private class PipImpl implements Pip {
        private IPipImpl mIPip;

        @Override
        public IPip createExternalInterface() {
            if (mIPip != null) {
                mIPip.invalidate();
            }
            mIPip = new IPipImpl(PipController.this);
            return mIPip;
        }

        @Override
        public void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {
            mMainExecutor.execute(() -> {
                PipController.this.hidePipMenu(onStartCallback, onEndCallback);
            });
        }

        @Override
        public void expandPip() {
            mMainExecutor.execute(() -> {
                PipController.this.expandPip();
            });
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            mMainExecutor.execute(() -> {
                PipController.this.onConfigurationChanged(newConfig);
            });
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            mMainExecutor.execute(() -> {
                PipController.this.onDensityOrFontScaleChanged();
            });
        }

        @Override
        public void onOverlayChanged() {
            mMainExecutor.execute(() -> {
                PipController.this.onOverlayChanged();
            });
        }

        @Override
        public void onSystemUiStateChanged(boolean isSysUiStateValid, int flag) {
            mMainExecutor.execute(() -> {
                PipController.this.onSystemUiStateChanged(isSysUiStateValid, flag);
            });
        }

        @Override
        public void registerSessionListenerForCurrentUser() {
            mMainExecutor.execute(() -> {
                PipController.this.registerSessionListenerForCurrentUser();
            });
        }

        @Override
        public void setShelfHeight(boolean visible, int height) {
            mMainExecutor.execute(() -> {
                PipController.this.setShelfHeight(visible, height);
            });
        }

        @Override
        public void setPinnedStackAnimationType(int animationType) {
            mMainExecutor.execute(() -> {
                PipController.this.setPinnedStackAnimationType(animationType);
            });
        }

        @Override
        public void addPipExclusionBoundsChangeListener(Consumer<Rect> listener) {
            mMainExecutor.execute(() -> {
                mPipBoundsState.addPipExclusionBoundsChangeCallback(listener);
            });
        }

        @Override
        public void removePipExclusionBoundsChangeListener(Consumer<Rect> listener) {
            mMainExecutor.execute(() -> {
                mPipBoundsState.removePipExclusionBoundsChangeCallback(listener);
            });
        }

        @Override
        public void showPictureInPictureMenu() {
            mMainExecutor.execute(() -> {
                PipController.this.showPictureInPictureMenu();
            });
        }

        @Override
        public void dump(PrintWriter pw) {
            try {
                mMainExecutor.executeBlocking(() -> {
                    PipController.this.dump(pw);
                });
            } catch (InterruptedException e) {
                ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: Failed to dump PipController in 2s", TAG);
            }
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IPipImpl extends IPip.Stub {
        private PipController mController;
        private final SingleInstanceRemoteListener<PipController,
                IPipAnimationListener> mListener;
        private final PipAnimationListener mPipAnimationListener = new PipAnimationListener() {
            @Override
            public void onPipAnimationStarted() {
                mListener.call(l -> l.onPipAnimationStarted());
            }

            @Override
            public void onPipCornerRadiusChanged(int cornerRadius) {
                mListener.call(l -> l.onPipCornerRadiusChanged(cornerRadius));
            }

            @Override
            public void onExpandPip() {
                mListener.call(l -> l.onExpandPip());
            }
        };

        IPipImpl(PipController controller) {
            mController = controller;
            mListener = new SingleInstanceRemoteListener<>(mController,
                    c -> c.setPinnedStackAnimationListener(mPipAnimationListener),
                    c -> c.setPinnedStackAnimationListener(null));
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        void invalidate() {
            mController = null;
        }

        @Override
        public Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
                PictureInPictureParams pictureInPictureParams, int launcherRotation,
                int shelfHeight) {
            Rect[] result = new Rect[1];
            executeRemoteCallWithTaskPermission(mController, "startSwipePipToHome",
                    (controller) -> {
                        result[0] = controller.startSwipePipToHome(componentName, activityInfo,
                                pictureInPictureParams, launcherRotation, shelfHeight);
                    }, true /* blocking */);
            return result[0];
        }

        @Override
        public void stopSwipePipToHome(int taskId, ComponentName componentName,
                Rect destinationBounds, SurfaceControl overlay) {
            executeRemoteCallWithTaskPermission(mController, "stopSwipePipToHome",
                    (controller) -> {
                        controller.stopSwipePipToHome(taskId, componentName, destinationBounds,
                                overlay);
                    });
        }

        @Override
        public void setShelfHeight(boolean visible, int height) {
            executeRemoteCallWithTaskPermission(mController, "setShelfHeight",
                    (controller) -> {
                        controller.setShelfHeight(visible, height);
                    });
        }

        @Override
        public void setPinnedStackAnimationListener(IPipAnimationListener listener) {
            executeRemoteCallWithTaskPermission(mController, "setPinnedStackAnimationListener",
                    (controller) -> {
                        if (listener != null) {
                            mListener.register(listener);
                        } else {
                            mListener.unregister();
                        }
                    });
        }
    }
}
