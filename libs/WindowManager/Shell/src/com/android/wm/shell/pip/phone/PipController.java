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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;

import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ParceledListSlice;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IPinnedStackController;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsHandler;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipTaskOrganizer;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipController implements Pip, PipTaskOrganizer.PipTransitionCallback {
    private static final String TAG = "PipController";

    private Context mContext;
    private ShellExecutor mMainExecutor;

    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();
    private final Rect mTmpInsetBounds = new Rect();
    private final Rect mTmpNormalBounds = new Rect();
    protected final Rect mReentryBounds = new Rect();

    private DisplayController mDisplayController;
    private PipAppOpsListener mAppOpsListener;
    private PipBoundsHandler mPipBoundsHandler;
    private @NonNull PipBoundsState mPipBoundsState;
    private PipMediaController mMediaController;
    private PipTouchHandler mTouchHandler;
    private Consumer<Boolean> mPinnedStackAnimationRecentsCallback;
    private WindowManagerShellWrapper mWindowManagerShellWrapper;

    private boolean mIsInFixedRotation;

    protected PipMenuActivityController mMenuController;
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
            mPipBoundsHandler.onDisplayRotationChangedNotInPip(mContext, toRotation);
            return;
        }
        // If there is an animation running (ie. from a shelf offset), then ensure that we calculate
        // the bounds for the next orientation using the destination bounds of the animation
        // TODO: Technically this should account for movement animation bounds as well
        Rect currentBounds = mPipTaskOrganizer.getCurrentOrAnimatingBounds();
        final boolean changed = mPipBoundsHandler.onDisplayRotationChanged(mContext,
                mTmpNormalBounds, currentBounds, mTmpInsetBounds, displayId, fromRotation,
                toRotation, t);
        if (changed) {
            // If the pip was in the offset zone earlier, adjust the new bounds to the bottom of the
            // movement bounds
            mTouchHandler.adjustBoundsForRotation(mTmpNormalBounds,
                    mPipBoundsState.getBounds(), mTmpInsetBounds);

            // The bounds are being applied to a specific snap fraction, so reset any known offsets
            // for the previous orientation before updating the movement bounds.
            // We perform the resets if and only if this callback is due to screen rotation but
            // not during the fixed rotation. In fixed rotation case, app is about to enter PiP
            // and we need the offsets preserved to calculate the destination bounds.
            if (!mIsInFixedRotation) {
                mPipBoundsHandler.setShelfHeight(false, 0);
                mPipBoundsHandler.onImeVisibilityChanged(false, 0);
                mTouchHandler.onShelfVisibilityChanged(false, 0);
                mTouchHandler.onImeVisibilityChanged(false, 0);
            }

            updateMovementBounds(mTmpNormalBounds, true /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */, t);
        }
    };

    private DisplayController.OnDisplaysChangedListener mFixedRotationListener =
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
        public void onListenerRegistered(IPinnedStackController controller) {
            mMainExecutor.execute(() -> mTouchHandler.setPinnedStackController(controller));
        }

        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mMainExecutor.execute(() -> {
                mPipBoundsHandler.onImeVisibilityChanged(imeVisible, imeHeight);
                mTouchHandler.onImeVisibilityChanged(imeVisible, imeHeight);
            });
        }

        @Override
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {
            mMainExecutor.execute(() -> updateMovementBounds(null /* toBounds */,
                    false /* fromRotation */, fromImeAdjustment, false /* fromShelfAdjustment */,
                    null /* windowContainerTransaction */));
        }

        @Override
        public void onActionsChanged(ParceledListSlice<RemoteAction> actions) {
            mMainExecutor.execute(() -> mMenuController.setAppActions(actions));
        }

        @Override
        public void onActivityHidden(ComponentName componentName) {
            mMainExecutor.execute(() -> {
                if (componentName.equals(mPipBoundsState.getLastPipComponentName())) {
                    // The activity was removed, we don't want to restore to the reentry state
                    // saved for this component anymore.
                    mPipBoundsState.setLastPipComponentName(null);
                }
            });
        }

        @Override
        public void onDisplayInfoChanged(DisplayInfo displayInfo) {
            mMainExecutor.execute(() -> mPipBoundsState.setDisplayInfo(displayInfo));
        }

        @Override
        public void onConfigurationChanged() {
            mMainExecutor.execute(() -> {
                mPipBoundsHandler.onConfigurationChanged(mContext);
                mTouchHandler.onConfigurationChanged();
            });
        }

        @Override
        public void onAspectRatioChanged(float aspectRatio) {
            // TODO(b/169373982): Remove this callback as it is redundant with PipTaskOrg params
            // change.
            mMainExecutor.execute(() -> {
                mPipBoundsState.setAspectRatio(aspectRatio);
                mTouchHandler.onAspectRatioChanged();
            });
        }
    }

    protected PipController(Context context,
            DisplayController displayController,
            PipAppOpsListener pipAppOpsListener,
            PipBoundsHandler pipBoundsHandler,
            @NonNull PipBoundsState pipBoundsState,
            PipMediaController pipMediaController,
            PipMenuActivityController pipMenuActivityController,
            PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler,
            WindowManagerShellWrapper windowManagerShellWrapper,
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
        mPipBoundsHandler = pipBoundsHandler;
        mPipBoundsState = pipBoundsState;
        mPipTaskOrganizer = pipTaskOrganizer;
        mMainExecutor = mainExecutor;
        mPipTaskOrganizer.registerPipTransitionCallback(this);
        mPipTaskOrganizer.registerOnDisplayIdChangeCallback((int displayId) -> {
            final DisplayInfo newDisplayInfo = new DisplayInfo();
            displayController.getDisplay(displayId).getDisplayInfo(newDisplayInfo);
            mPipBoundsState.setDisplayInfo(newDisplayInfo);
            updateMovementBounds(null /* toBounds */, false /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdustment */,
                    null /* wct */);
        });
        mMediaController = pipMediaController;
        mMenuController = pipMenuActivityController;
        mTouchHandler = pipTouchHandler;
        mAppOpsListener = pipAppOpsListener;
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
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        mMainExecutor.execute(() -> {
            mPipTaskOrganizer.onDensityOrFontScaleChanged(mContext);
        });
    }

    @Override
    public void onActivityPinned(String packageName) {
        mMainExecutor.execute(() -> {
            mTouchHandler.onActivityPinned();
            mMediaController.onActivityPinned();
            mMenuController.onActivityPinned();
            mAppOpsListener.onActivityPinned(packageName);
        });
    }

    @Override
    public void onActivityUnpinned(ComponentName topActivity) {
        mMainExecutor.execute(() -> {
            mMenuController.onActivityUnpinned();
            mTouchHandler.onActivityUnpinned(topActivity);
            mAppOpsListener.onActivityUnpinned();
        });
    }

    @Override
    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
            boolean clearedTask) {
        if (task.configuration.windowConfiguration.getWindowingMode()
                != WINDOWING_MODE_PINNED) {
            return;
        }
        mTouchHandler.getMotionHelper().expandLeavePip(clearedTask /* skipAnimation */);
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
        final boolean changed = mPipBoundsHandler.setShelfHeight(visible, shelfHeight);
        if (changed) {
            mTouchHandler.onShelfVisibilityChanged(visible, shelfHeight);
            updateMovementBounds(mPipBoundsState.getBounds(),
                    false /* fromRotation */, false /* fromImeAdjustment */,
                    true /* fromShelfAdjustment */, null /* windowContainerTransaction */);
        }
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
        mPipBoundsHandler.onDisplayRotationChangedNotInPip(mContext, launcherRotation);
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
            // Exiting PIP, save the reentry bounds to restore to when re-entering.
            updateReentryBounds(pipBounds);
            final float snapFraction = mPipBoundsHandler.getSnapFraction(mReentryBounds);
            mPipBoundsState.saveReentryState(mReentryBounds, snapFraction);
        }
        // Disable touches while the animation is running
        mTouchHandler.setTouchEnabled(false);
        if (mPinnedStackAnimationRecentsCallback != null) {
            mPinnedStackAnimationRecentsCallback.accept(true);
        }
    }

    /**
     * Update the bounds used to save the re-entry size and snap fraction when exiting PIP.
     */
    public void updateReentryBounds(Rect bounds) {
        final Rect reentryBounds = mTouchHandler.getUserResizeBounds();
        float snapFraction = mPipBoundsHandler.getSnapFraction(bounds);
        mPipBoundsHandler.applySnapFraction(reentryBounds, snapFraction);
        mReentryBounds.set(reentryBounds);
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
        mPipBoundsHandler.onMovementBoundsChanged(mTmpInsetBounds, mTmpNormalBounds,
                outBounds);
        // mTouchHandler would rely on the bounds populated from mPipTaskOrganizer
        mPipTaskOrganizer.onMovementBoundsChanged(outBounds, fromRotation, fromImeAdjustment,
                fromShelfAdjustment, wct);
        mTouchHandler.onMovementBoundsChanged(mTmpInsetBounds, mTmpNormalBounds,
                outBounds, fromImeAdjustment, fromShelfAdjustment,
                mTmpDisplayInfo.rotation);
    }

    @Override
    public void dump(PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        mMenuController.dump(pw, innerPrefix);
        mTouchHandler.dump(pw, innerPrefix);
        mPipBoundsHandler.dump(pw, innerPrefix);
        mPipTaskOrganizer.dump(pw, innerPrefix);
        mPipBoundsState.dump(pw, innerPrefix);
    }

    /**
     * Instantiates {@link PipController}, returns {@code null} if the feature not supported.
     */
    @Nullable
    public static PipController create(Context context, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsHandler pipBoundsHandler,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            PipMenuActivityController pipMenuActivityController,
            PipTaskOrganizer pipTaskOrganizer, PipTouchHandler pipTouchHandler,
            WindowManagerShellWrapper windowManagerShellWrapper,
            ShellExecutor mainExecutor) {
        if (!context.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            Slog.w(TAG, "Device doesn't support Pip feature");
            return null;
        }

        return new PipController(context, displayController, pipAppOpsListener, pipBoundsHandler,
                pipBoundsState, pipMediaController, pipMenuActivityController,
                pipTaskOrganizer, pipTouchHandler, windowManagerShellWrapper, mainExecutor);
    }
}
