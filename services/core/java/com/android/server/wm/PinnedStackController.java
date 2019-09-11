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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import static com.android.server.wm.PinnedStackControllerProto.DEFAULT_BOUNDS;
import static com.android.server.wm.PinnedStackControllerProto.MOVEMENT_BOUNDS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.Slog;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;

import com.android.internal.policy.PipSnapAlgorithm;
import com.android.internal.util.Preconditions;
import com.android.server.UiThread;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the common state of the pinned stack between the system and SystemUI. If SystemUI ever
 * needs to be restarted, it will be notified with the last known state.
 *
 * Changes to the pinned stack also flow through this controller, and generally, the system only
 * changes the pinned stack bounds through this controller in two ways:
 *
 * 1) When first entering PiP: the controller returns the valid bounds given, taking aspect ratio
 *    and IME state into account.
 * 2) When rotating the device: the controller calculates the new bounds in the new orientation,
 *    taking the minimized and IME state into account. In this case, we currently ignore the
 *    SystemUI adjustments (ie. expanded for menu, interaction, etc).
 *
 * Other changes in the system, including adjustment of IME, configuration change, and more are
 * handled by SystemUI (similar to the docked stack divider).
 */
class PinnedStackController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "PinnedStackController" : TAG_WM;

    private static final float INVALID_SNAP_FRACTION = -1f;
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final Handler mHandler = UiThread.getHandler();

    private IPinnedStackListener mPinnedStackListener;
    private final PinnedStackListenerDeathHandler mPinnedStackListenerDeathHandler =
            new PinnedStackListenerDeathHandler();

    private final PinnedStackControllerCallback mCallbacks = new PinnedStackControllerCallback();
    private final PipSnapAlgorithm mSnapAlgorithm;

    // States that affect how the PIP can be manipulated
    private boolean mIsMinimized;
    private boolean mIsImeShowing;
    private int mImeHeight;

    // The set of actions and aspect-ratio for the that are currently allowed on the PiP activity
    private ArrayList<RemoteAction> mActions = new ArrayList<>();
    private float mAspectRatio = -1f;

    // Used to calculate stack bounds across rotations
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Rect mStableInsets = new Rect();

    // The size and position information that describes where the pinned stack will go by default.
    private int mDefaultMinSize;
    private int mDefaultStackGravity;
    private float mDefaultAspectRatio;
    private Point mScreenEdgeInsets;

    // The aspect ratio bounds of the PIP.
    private float mMinAspectRatio;
    private float mMaxAspectRatio;

    // Temp vars for calculation
    private final DisplayMetrics mTmpMetrics = new DisplayMetrics();
    private final Rect mTmpInsets = new Rect();
    private final Rect mTmpRect = new Rect();
    private final Point mTmpDisplaySize = new Point();


    /**
     * The callback object passed to listeners for them to notify the controller of state changes.
     */
    private class PinnedStackControllerCallback extends IPinnedStackController.Stub {

        @Override
        public void setIsMinimized(final boolean isMinimized) {
            mHandler.post(() -> {
                mIsMinimized = isMinimized;
                mSnapAlgorithm.setMinimized(isMinimized);
            });
        }

        @Override
        public int getDisplayRotation() {
            synchronized (mService.mGlobalLock) {
                return mDisplayInfo.rotation;
            }
        }

        @Override
        public void startAnimation(Rect destinationBounds, Rect sourceRectHint,
                int animationDuration) {
            synchronized (mService.mGlobalLock) {
                final TaskStack pinnedStack = mDisplayContent.getPinnedStack();
                pinnedStack.animateResizePinnedStack(destinationBounds,
                        sourceRectHint, animationDuration, true /* fromFullscreen */);
            }
        }
    }

    /**
     * Handler for the case where the listener dies.
     */
    private class PinnedStackListenerDeathHandler implements IBinder.DeathRecipient {

        @Override
        public void binderDied() {
            // Clean up the state if the listener dies
            if (mPinnedStackListener != null) {
                mPinnedStackListener.asBinder().unlinkToDeath(mPinnedStackListenerDeathHandler, 0);
            }
            mPinnedStackListener = null;
        }
    }

    PinnedStackController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mSnapAlgorithm = new PipSnapAlgorithm(service.mContext);
        mDisplayInfo.copyFrom(mDisplayContent.getDisplayInfo());
        reloadResources();
        // Initialize the aspect ratio to the default aspect ratio.  Don't do this in reload
        // resources as it would clobber mAspectRatio when entering PiP from fullscreen which
        // triggers a configuration change and the resources to be reloaded.
        mAspectRatio = mDefaultAspectRatio;
    }

    void onConfigurationChanged() {
        reloadResources();
    }

    /**
     * Reloads all the resources for the current configuration.
     */
    private void reloadResources() {
        final Resources res = mService.mContext.getResources();
        mDefaultMinSize = res.getDimensionPixelSize(
                com.android.internal.R.dimen.default_minimal_size_pip_resizable_task);
        mDefaultAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureDefaultAspectRatio);
        final String screenEdgeInsetsDpString = res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureScreenEdgeInsets);
        final Size screenEdgeInsetsDp = !screenEdgeInsetsDpString.isEmpty()
                ? Size.parseSize(screenEdgeInsetsDpString)
                : null;
        mDefaultStackGravity = res.getInteger(
                com.android.internal.R.integer.config_defaultPictureInPictureGravity);
        mDisplayContent.getDisplay().getRealMetrics(mTmpMetrics);
        mScreenEdgeInsets = screenEdgeInsetsDp == null ? new Point()
                : new Point(dpToPx(screenEdgeInsetsDp.getWidth(), mTmpMetrics),
                        dpToPx(screenEdgeInsetsDp.getHeight(), mTmpMetrics));
        mMinAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
        mMaxAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);
    }

    /**
     * Registers a pinned stack listener.
     */
    void registerPinnedStackListener(IPinnedStackListener listener) {
        try {
            listener.asBinder().linkToDeath(mPinnedStackListenerDeathHandler, 0);
            listener.onListenerRegistered(mCallbacks);
            mPinnedStackListener = listener;
            notifyDisplayInfoChanged(mDisplayInfo);
            notifyImeVisibilityChanged(mIsImeShowing, mImeHeight);
            // The movement bounds notification needs to be sent before the minimized state, since
            // SystemUI may use the bounds to retore the minimized position
            notifyMovementBoundsChanged(false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */);
            notifyActionsChanged(mActions);
            notifyMinimizeChanged(mIsMinimized);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
    }

    /**
     * @return whether the given {@param aspectRatio} is valid.
     */
    public boolean isValidPictureInPictureAspectRatio(float aspectRatio) {
        return Float.compare(mMinAspectRatio, aspectRatio) <= 0 &&
                Float.compare(aspectRatio, mMaxAspectRatio) <= 0;
    }

    /**
     * Saves the current snap fraction for re-entry of the current activity into PiP.
     */
    void saveReentrySnapFraction(final ComponentName componentName, final Rect stackBounds) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onSaveReentrySnapFraction(componentName, stackBounds);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering save reentry fraction event.", e);
        }
    }

    /**
     * Resets the last saved snap fraction so that the default bounds will be returned.
     */
    void resetReentrySnapFraction(ComponentName componentName) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onResetReentrySnapFraction(componentName);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering reset reentry fraction event.", e);
        }
    }

    /**
     * @return the default bounds to show the PIP, if a {@param snapFraction} is provided, then it
     * will apply the default bounds to the provided snap fraction.
     */
    private Rect getDefaultBounds(float snapFraction) {
        synchronized (mService.mGlobalLock) {
            final Rect insetBounds = new Rect();
            getInsetBounds(insetBounds);

            final Rect defaultBounds = new Rect();
            final Size size = mSnapAlgorithm.getSizeForAspectRatio(mDefaultAspectRatio,
                    mDefaultMinSize, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
            if (snapFraction != INVALID_SNAP_FRACTION) {
                defaultBounds.set(0, 0, size.getWidth(), size.getHeight());
                final Rect movementBounds = getMovementBounds(defaultBounds);
                mSnapAlgorithm.applySnapFraction(defaultBounds, movementBounds, snapFraction);
            } else {
                Gravity.apply(mDefaultStackGravity, size.getWidth(), size.getHeight(), insetBounds,
                        0, mIsImeShowing ? mImeHeight : 0, defaultBounds);
            }
            return defaultBounds;
        }
    }

    private void setDisplayInfo(DisplayInfo displayInfo) {
        mDisplayInfo.copyFrom(displayInfo);
        notifyDisplayInfoChanged(mDisplayInfo);
    }

    /**
     * In the case where the display rotation is changed but there is no stack, we can't depend on
     * onTaskStackBoundsChanged() to be called.  But we still should update our known display info
     * with the new state so that we can update SystemUI.
     */
    synchronized void onDisplayInfoChanged(DisplayInfo displayInfo) {
        setDisplayInfo(displayInfo);
        notifyMovementBoundsChanged(false /* fromImeAdjustment */, false /* fromShelfAdjustment */);
    }

    /**
     * Updates the display info, calculating and returning the new stack and movement bounds in the
     * new orientation of the device if necessary.
     */
    boolean onTaskStackBoundsChanged(Rect targetBounds, Rect outBounds) {
        synchronized (mService.mGlobalLock) {
            final DisplayInfo displayInfo = mDisplayContent.getDisplayInfo();
            if (isSameDimensionAndRotation(mDisplayInfo, displayInfo)) {
                // No dimension/rotation change, ignore
                outBounds.setEmpty();
                return false;
            } else if (targetBounds.isEmpty()) {
                // The stack is null, we are just initializing the stack, so just store the display
                // info and ignore
                setDisplayInfo(displayInfo);
                outBounds.setEmpty();
                return false;
            }

            mTmpRect.set(targetBounds);
            final Rect postChangeStackBounds = mTmpRect;

            // Calculate the snap fraction of the current stack along the old movement bounds
            final float snapFraction = getSnapFraction(postChangeStackBounds);

            setDisplayInfo(displayInfo);

            // Calculate the stack bounds in the new orientation to the same same fraction along the
            // rotated movement bounds.
            final Rect postChangeMovementBounds = getMovementBounds(postChangeStackBounds,
                    false /* adjustForIme */);
            mSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                    snapFraction);
            if (mIsMinimized) {
                applyMinimizedOffset(postChangeStackBounds, postChangeMovementBounds);
            }

            notifyMovementBoundsChanged(false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */);

            outBounds.set(postChangeStackBounds);
            return true;
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
        notifyMovementBoundsChanged(true /* fromImeAdjustment */, false /* fromShelfAdjustment */);
    }

    /**
     * Sets the current aspect ratio.
     */
    void setAspectRatio(float aspectRatio) {
        if (Float.compare(mAspectRatio, aspectRatio) != 0) {
            mAspectRatio = aspectRatio;
            notifyAspectRatioChanged(aspectRatio);
            notifyMovementBoundsChanged(false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */);
            notifyPrepareAnimation(null /* sourceHintRect */, aspectRatio,
                    null /* stackBounds */);
        }
    }

    /**
     * @return the current aspect ratio.
     */
    float getAspectRatio() {
        return mAspectRatio;
    }

    /**
     * Sets the current set of actions.
     */
    void setActions(List<RemoteAction> actions) {
        mActions.clear();
        if (actions != null) {
            mActions.addAll(actions);
        }
        notifyActionsChanged(mActions);
    }

    void prepareAnimation(Rect sourceRectHint, float aspectRatio, Rect stackBounds) {
        notifyPrepareAnimation(sourceRectHint, aspectRatio, stackBounds);
    }

    private boolean isSameDimensionAndRotation(@NonNull DisplayInfo display1,
            @NonNull DisplayInfo display2) {
        Preconditions.checkNotNull(display1);
        Preconditions.checkNotNull(display2);
        return ((display1.rotation == display2.rotation)
                && (display1.logicalWidth == display2.logicalWidth)
                && (display1.logicalHeight == display2.logicalHeight));
    }

    /**
     * Notifies listeners that the PIP needs to be adjusted for the IME.
     */
    private void notifyImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onImeVisibilityChanged(imeVisible, imeHeight);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering bounds changed event.", e);
            }
        }
    }

    private void notifyAspectRatioChanged(float aspectRatio) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onAspectRatioChanged(aspectRatio);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering aspect ratio changed event.", e);
        }
    }

    /**
     * Notifies listeners that the PIP minimized state has changed.
     */
    private void notifyMinimizeChanged(boolean isMinimized) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onMinimizedStateChanged(isMinimized);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering minimize changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP actions have changed.
     */
    private void notifyActionsChanged(List<RemoteAction> actions) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onActionsChanged(new ParceledListSlice(actions));
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering actions changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP movement bounds have changed.
     */
    private void notifyMovementBoundsChanged(boolean fromImeAdjustment,
            boolean fromShelfAdjustment) {
        synchronized (mService.mGlobalLock) {
            if (mPinnedStackListener == null) {
                return;
            }
            try {
                final Rect animatingBounds = new Rect();
                final TaskStack pinnedStack = mDisplayContent.getPinnedStack();
                if (pinnedStack != null) {
                    pinnedStack.getAnimationOrCurrentBounds(animatingBounds);
                }
                mPinnedStackListener.onMovementBoundsChanged(animatingBounds,
                        fromImeAdjustment, fromShelfAdjustment);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering actions changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP animation is about to happen.
     */
    private void notifyDisplayInfoChanged(DisplayInfo displayInfo) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onDisplayInfoChanged(displayInfo);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering DisplayInfo changed event.", e);
        }
    }

    /**
     * Notifies listeners that the PIP animation is about to happen.
     */
    private void notifyPrepareAnimation(Rect sourceRectHint, float aspectRatio, Rect stackBounds) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onPrepareAnimation(sourceRectHint, aspectRatio, stackBounds);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering prepare animation event.", e);
        }
    }

    /**
     * @return the bounds on the screen that the PIP can be visible in.
     */
    private void getInsetBounds(Rect outRect) {
        synchronized (mService.mGlobalLock) {
            mDisplayContent.getDisplayPolicy().getStableInsetsLw(mDisplayInfo.rotation,
                    mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight,
                    mDisplayInfo.displayCutout, mTmpInsets);
            outRect.set(mTmpInsets.left + mScreenEdgeInsets.x, mTmpInsets.top + mScreenEdgeInsets.y,
                    mDisplayInfo.logicalWidth - mTmpInsets.right - mScreenEdgeInsets.x,
                    mDisplayInfo.logicalHeight - mTmpInsets.bottom - mScreenEdgeInsets.y);
        }
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    private Rect getMovementBounds(Rect stackBounds) {
        synchronized (mService.mGlobalLock) {
            return getMovementBounds(stackBounds, true /* adjustForIme */);
        }
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    private Rect getMovementBounds(Rect stackBounds, boolean adjustForIme) {
        synchronized (mService.mGlobalLock) {
            final Rect movementBounds = new Rect();
            getInsetBounds(movementBounds);

            // Apply the movement bounds adjustments based on the current state.
            // Note that shelf offset does not affect the movement bounds here
            // since it's been taken care of in system UI.
            mSnapAlgorithm.getMovementBounds(stackBounds, movementBounds, movementBounds,
                    (adjustForIme && mIsImeShowing) ? mImeHeight : 0);
            return movementBounds;
        }
    }

    /**
     * Applies the minimized offsets to the given stack bounds.
     */
    private void applyMinimizedOffset(Rect stackBounds, Rect movementBounds) {
        synchronized (mService.mGlobalLock) {
            mTmpDisplaySize.set(mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
            mService.getStableInsetsLocked(mDisplayContent.getDisplayId(), mStableInsets);
            mSnapAlgorithm.applyMinimizedOffset(stackBounds, movementBounds, mTmpDisplaySize,
                    mStableInsets);
        }
    }

    /**
     * @return the default snap fraction to apply instead of the default gravity when calculating
     *         the default stack bounds when first entering PiP.
     */
    private float getSnapFraction(Rect stackBounds) {
        return mSnapAlgorithm.getSnapFraction(stackBounds, getMovementBounds(stackBounds));
    }

    /**
     * @return the pixels for a given dp value.
     */
    private int dpToPx(float dpValue, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, dpValue, dm);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "PinnedStackController");
        pw.print(prefix + "  defaultBounds=");
        getDefaultBounds(INVALID_SNAP_FRACTION).printShortString(pw);
        pw.println();
        pw.println(prefix + "  mDefaultMinSize=" + mDefaultMinSize);
        pw.println(prefix + "  mDefaultStackGravity=" + mDefaultStackGravity);
        pw.println(prefix + "  mDefaultAspectRatio=" + mDefaultAspectRatio);
        mService.getStackBounds(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, mTmpRect);
        pw.print(prefix + "  movementBounds="); getMovementBounds(mTmpRect).printShortString(pw);
        pw.println();
        pw.println(prefix + "  mIsImeShowing=" + mIsImeShowing);
        pw.println(prefix + "  mImeHeight=" + mImeHeight);
        pw.println(prefix + "  mIsMinimized=" + mIsMinimized);
        pw.println(prefix + "  mAspectRatio=" + mAspectRatio);
        pw.println(prefix + "  mMinAspectRatio=" + mMinAspectRatio);
        pw.println(prefix + "  mMaxAspectRatio=" + mMaxAspectRatio);
        if (mActions.isEmpty()) {
            pw.println(prefix + "  mActions=[]");
        } else {
            pw.println(prefix + "  mActions=[");
            for (int i = 0; i < mActions.size(); i++) {
                RemoteAction action = mActions.get(i);
                pw.print(prefix + "    Action[" + i + "]: ");
                action.dump("", pw);
            }
            pw.println(prefix + "  ]");
        }
        pw.println(prefix + "  mDisplayInfo=" + mDisplayInfo);
    }

    void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        getDefaultBounds(INVALID_SNAP_FRACTION).writeToProto(proto, DEFAULT_BOUNDS);
        mService.getStackBounds(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, mTmpRect);
        getMovementBounds(mTmpRect).writeToProto(proto, MOVEMENT_BOUNDS);
        proto.end(token);
    }
}
