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

package com.android.server.wm;

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.RotationUtils;
import android.util.Slog;
import android.view.IPinnedTaskListener;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.PictureInPictureSurfaceTransaction;

import java.io.PrintWriter;

/**
 * Holds the common state of the pinned task between the system and SystemUI. If SystemUI ever
 * needs to be restarted, it will be notified with the last known state.
 *
 * Changes to the pinned task also flow through this controller, and generally, the system only
 * changes the pinned task bounds through this controller in two ways:
 *
 * 1) When first entering PiP: the controller returns the valid bounds given, taking aspect ratio
 *    and IME state into account.
 * 2) When rotating the device: the controller calculates the new bounds in the new orientation,
 *    taking the IME state into account. In this case, we currently ignore the
 *    SystemUI adjustments (ie. expanded for menu, interaction, etc).
 *
 * Other changes in the system, including adjustment of IME, configuration change, and more are
 * handled by SystemUI (similar to the docked task divider).
 */
class PinnedTaskController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "PinnedTaskController" : TAG_WM;
    private static final int DEFER_ORIENTATION_CHANGE_TIMEOUT_MS = 1000;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;

    private IPinnedTaskListener mPinnedTaskListener;
    private final PinnedTaskListenerDeathHandler mPinnedTaskListenerDeathHandler =
            new PinnedTaskListenerDeathHandler();

    /**
     * Non-null if the entering PiP task will cause display rotation to change. The bounds are
     * based on the new rotation.
     */
    private Rect mDestRotatedBounds;
    /**
     * Non-null if the entering PiP task from recents animation will cause display rotation to
     * change. The transaction is based on the old rotation.
     */
    private PictureInPictureSurfaceTransaction mPipTransaction;
    /** Whether to skip task configuration change once. */
    private boolean mFreezingTaskConfig;
    /** Defer display orientation change if the PiP task is animating across orientations. */
    private boolean mDeferOrientationChanging;
    private final Runnable mDeferOrientationTimeoutRunnable;

    private boolean mIsImeShowing;
    private int mImeHeight;

    // The aspect ratio bounds of the PIP.
    private float mMinAspectRatio;
    private float mMaxAspectRatio;

    /**
     * Handler for the case where the listener dies.
     */
    private class PinnedTaskListenerDeathHandler implements IBinder.DeathRecipient {

        @Override
        public void binderDied() {
            synchronized (mService.mGlobalLock) {
                mPinnedTaskListener = null;
                mFreezingTaskConfig = false;
                mDeferOrientationTimeoutRunnable.run();
            }
        }
    }

    PinnedTaskController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mDeferOrientationTimeoutRunnable = () -> {
            synchronized (mService.mGlobalLock) {
                if (mDeferOrientationChanging) {
                    continueOrientationChange();
                    mService.mWindowPlacerLocked.requestTraversal();
                }
            }
        };
        reloadResources();
    }

    /** Updates the resources used by pinned controllers.  */
    void onPostDisplayConfigurationChanged() {
        reloadResources();
        mFreezingTaskConfig = false;
    }

    /**
     * Reloads all the resources for the current configuration.
     */
    private void reloadResources() {
        final Resources res = mService.mContext.getResources();
        mMinAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
        mMaxAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);
    }

    /**
     * Registers a pinned task listener.
     */
    void registerPinnedTaskListener(IPinnedTaskListener listener) {
        try {
            listener.asBinder().linkToDeath(mPinnedTaskListenerDeathHandler, 0);
            mPinnedTaskListener = listener;
            notifyImeVisibilityChanged(mIsImeShowing, mImeHeight);
            notifyMovementBoundsChanged(false /* fromImeAdjustment */);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned task listener", e);
        }
    }

    /**
     * @return whether the given {@param aspectRatio} is valid, i.e. min <= ratio <= max.
     */
    public boolean isValidPictureInPictureAspectRatio(float aspectRatio) {
        return Float.compare(mMinAspectRatio, aspectRatio) <= 0
                && Float.compare(aspectRatio, mMaxAspectRatio) <= 0;
    }

    /**
     * @return whether the given {@param aspectRatio} is valid, i.e. ratio < min or ratio > max.
     */
    public boolean isValidExpandedPictureInPictureAspectRatio(float aspectRatio) {
        return Float.compare(mMinAspectRatio, aspectRatio) > 0
                || Float.compare(aspectRatio, mMaxAspectRatio) > 0;
    }

    /**
     * Called when a fullscreen task is entering PiP with display orientation change. This is used
     * to avoid flickering when running PiP animation across different orientations.
     */
    void deferOrientationChangeForEnteringPipFromFullScreenIfNeeded() {
        final ActivityRecord topFullscreen = mDisplayContent.getActivity(
                a -> a.providesOrientation() && !a.getTask().inMultiWindowMode());
        if (topFullscreen == null || topFullscreen.hasFixedRotationTransform()) {
            return;
        }
        final int rotation = mDisplayContent.rotationForActivityInDifferentOrientation(
                topFullscreen);
        if (rotation == ROTATION_UNDEFINED) {
            return;
        }
        // If the next top activity will change the orientation of display, start fixed rotation to
        // notify PipTaskOrganizer before it receives task appeared. And defer display orientation
        // update until the new PiP bounds are set.
        mDisplayContent.setFixedRotationLaunchingApp(topFullscreen, rotation);
        mDeferOrientationChanging = true;
        mService.mH.removeCallbacks(mDeferOrientationTimeoutRunnable);
        final float animatorScale = Math.max(1, mService.getCurrentAnimatorScale());
        mService.mH.postDelayed(mDeferOrientationTimeoutRunnable,
                (int) (animatorScale * DEFER_ORIENTATION_CHANGE_TIMEOUT_MS));
    }

    /** Defers orientation change while there is a top fixed rotation activity. */
    boolean shouldDeferOrientationChange() {
        return mDeferOrientationChanging;
    }

    /**
     * Sets the bounds for {@link #startSeamlessRotationIfNeeded} if the orientation of display
     * will be changed.
     */
    void setEnterPipBounds(Rect bounds) {
        if (!mDeferOrientationChanging) {
            return;
        }
        mFreezingTaskConfig = true;
        mDestRotatedBounds = new Rect(bounds);
        if (!mDisplayContent.mTransitionController.isShellTransitionsEnabled()) {
            continueOrientationChange();
        }
    }

    /**
     * Sets the transaction for {@link #startSeamlessRotationIfNeeded} if the orientation of display
     * will be changed. This is only called when finishing recents animation with pending
     * orientation change that will be handled by
     * {@link DisplayContent.FixedRotationTransitionListener#onFinishRecentsAnimation}.
     */
    void setEnterPipTransaction(PictureInPictureSurfaceTransaction tx) {
        mFreezingTaskConfig = true;
        mPipTransaction = tx;
    }

    /** Called when the activity in PiP task has PiP windowing mode (at the end of animation). */
    private void continueOrientationChange() {
        mDeferOrientationChanging = false;
        mService.mH.removeCallbacks(mDeferOrientationTimeoutRunnable);
        final WindowContainer<?> orientationSource = mDisplayContent.getLastOrientationSource();
        if (orientationSource != null && !orientationSource.isAppTransitioning()) {
            mDisplayContent.continueUpdateOrientationForDiffOrienLaunchingApp();
        }
    }

    /**
     * Resets rotation and applies scale and position to PiP task surface to match the current
     * rotation of display. The final surface matrix will be replaced by PiPTaskOrganizer after it
     * receives the callback of fixed rotation completion.
     */
    void startSeamlessRotationIfNeeded(SurfaceControl.Transaction t,
            int oldRotation, int newRotation) {
        final Rect bounds = mDestRotatedBounds;
        final PictureInPictureSurfaceTransaction pipTx = mPipTransaction;
        final boolean emptyPipPositionTx = pipTx == null || pipTx.mPosition == null;
        if (bounds == null && emptyPipPositionTx) {
            return;
        }
        final TaskDisplayArea taskArea = mDisplayContent.getDefaultTaskDisplayArea();
        final Task pinnedTask = taskArea.getRootPinnedTask();
        if (pinnedTask == null) {
            return;
        }

        mDestRotatedBounds = null;
        mPipTransaction = null;
        final Rect areaBounds = taskArea.getBounds();
        if (!emptyPipPositionTx) {
            // The transaction from recents animation is in old rotation. So the position needs to
            // be rotated.
            float dx = pipTx.mPosition.x;
            float dy = pipTx.mPosition.y;
            final Matrix matrix = pipTx.getMatrix();
            if (pipTx.mRotation == 90) {
                dx = pipTx.mPosition.y;
                dy = areaBounds.right - pipTx.mPosition.x;
                matrix.postRotate(-90);
            } else if (pipTx.mRotation == -90) {
                dx = areaBounds.bottom - pipTx.mPosition.y;
                dy = pipTx.mPosition.x;
                matrix.postRotate(90);
            }
            matrix.postTranslate(dx, dy);
            final SurfaceControl leash = pinnedTask.getSurfaceControl();
            t.setMatrix(leash, matrix, new float[9]);
            if (pipTx.hasCornerRadiusSet()) {
                t.setCornerRadius(leash, pipTx.mCornerRadius);
            }
            Slog.i(TAG, "Seamless rotation PiP tx=" + pipTx + " pos=" + dx + "," + dy);
            return;
        }

        final PictureInPictureParams params = pinnedTask.getPictureInPictureParams();
        final Rect sourceHintRect = params != null && params.hasSourceBoundsHint()
                ? params.getSourceRectHint()
                : null;
        Slog.i(TAG, "Seamless rotation PiP bounds=" + bounds + " hintRect=" + sourceHintRect);
        final int rotationDelta = RotationUtils.deltaRotation(oldRotation, newRotation);
        // Adjust for display cutout if applicable.
        if (sourceHintRect != null && rotationDelta == Surface.ROTATION_270) {
            if (pinnedTask.getDisplayCutoutInsets() != null) {
                final int rotationBackDelta = RotationUtils.deltaRotation(newRotation, oldRotation);
                final Rect displayCutoutInsets = RotationUtils.rotateInsets(
                        Insets.of(pinnedTask.getDisplayCutoutInsets()), rotationBackDelta).toRect();
                sourceHintRect.offset(displayCutoutInsets.left, displayCutoutInsets.top);
            }
        }
        final Rect contentBounds = sourceHintRect != null && areaBounds.contains(sourceHintRect)
                ? sourceHintRect : areaBounds;
        final int w = contentBounds.width();
        final int h = contentBounds.height();
        final float scale = w <= h ? (float) bounds.width() / w : (float) bounds.height() / h;
        final int insetLeft = (int) ((contentBounds.left - areaBounds.left) * scale + .5f);
        final int insetTop = (int) ((contentBounds.top - areaBounds.top) * scale + .5f);
        final Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(bounds.left - insetLeft, bounds.top - insetTop);
        t.setMatrix(pinnedTask.getSurfaceControl(), matrix, new float[9]);
    }

    /**
     * Returns {@code true} to skip {@link Task#onConfigurationChanged} because it is expected that
     * there will be a orientation change and a PiP configuration change.
     */
    boolean isFreezingTaskConfig(Task task) {
        return mFreezingTaskConfig
                && task == mDisplayContent.getDefaultTaskDisplayArea().getRootPinnedTask();
    }

    /** Resets the states which were used to perform fixed rotation with PiP task. */
    void onCancelFixedRotationTransform() {
        mFreezingTaskConfig = false;
        mDeferOrientationChanging = false;
        mDestRotatedBounds = null;
        mPipTransaction = null;
    }

    /**
     * Activity is hidden (either stopped or removed), resets the last saved snap fraction
     * so that the default bounds will be returned for the next session.
     */
    void onActivityHidden(ComponentName componentName) {
        if (mPinnedTaskListener == null) return;
        try {
            mPinnedTaskListener.onActivityHidden(componentName);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering reset reentry fraction event.", e);
        }
    }

    /**
     * Sets the Ime state and height.
     */
    void setAdjustedForIme(boolean adjustedForIme, int imeHeight) {
        // Due to the order of callbacks from the system, we may receive an ime height even when
        // {@param adjustedForIme} is false, and also a zero height when {@param adjustedForIme}
        // is true.  Instead, ensure that the ime state changes with the height and if the ime is
        // showing, then the height is non-zero.
        final boolean imeShowing = adjustedForIme && imeHeight > 0;
        imeHeight = imeShowing ? imeHeight : 0;
        if (imeShowing == mIsImeShowing && imeHeight == mImeHeight) {
            return;
        }

        mIsImeShowing = imeShowing;
        mImeHeight = imeHeight;
        notifyImeVisibilityChanged(imeShowing, imeHeight);
        notifyMovementBoundsChanged(true /* fromImeAdjustment */);
    }

    /**
     * Notifies listeners that the PIP needs to be adjusted for the IME.
     */
    private void notifyImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        if (mPinnedTaskListener != null) {
            try {
                mPinnedTaskListener.onImeVisibilityChanged(imeVisible, imeHeight);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering bounds changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP movement bounds have changed.
     */
    private void notifyMovementBoundsChanged(boolean fromImeAdjustment) {
        synchronized (mService.mGlobalLock) {
            if (mPinnedTaskListener == null) {
                return;
            }
            try {
                mPinnedTaskListener.onMovementBoundsChanged(fromImeAdjustment);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering actions changed event.", e);
            }
        }
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "PinnedTaskController");
        if (mDeferOrientationChanging) pw.println(prefix + "  mDeferOrientationChanging=true");
        if (mFreezingTaskConfig) pw.println(prefix + "  mFreezingTaskConfig=true");
        if (mDestRotatedBounds != null) {
            pw.println(prefix + "  mPendingBounds=" + mDestRotatedBounds);
        }
        if (mPipTransaction != null) {
            pw.println(prefix + "  mPipTransaction=" + mPipTransaction);
        }
        pw.println(prefix + "  mIsImeShowing=" + mIsImeShowing);
        pw.println(prefix + "  mImeHeight=" + mImeHeight);
        pw.println(prefix + "  mMinAspectRatio=" + mMinAspectRatio);
        pw.println(prefix + "  mMaxAspectRatio=" + mMaxAspectRatio);
    }
}
