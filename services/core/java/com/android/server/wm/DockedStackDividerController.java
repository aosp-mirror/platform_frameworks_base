/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;
import static com.android.server.wm.AppTransition.DEFAULT_APP_TRANSITION_DURATION;
import static com.android.server.wm.AppTransition.TOUCH_RESPONSE_INTERPOLATOR;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.NOTIFY_DOCKED_STACK_MINIMIZED_CHANGED;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IDockedStackListener;
import android.view.SurfaceControl;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManagerInternal;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.server.LocalServices;
import com.android.server.wm.DimLayer.DimLayerUser;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Keeps information about the docked stack divider.
 */
public class DockedStackDividerController implements DimLayerUser {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "DockedStackDividerController" : TAG_WM;

    /**
     * The fraction during the maximize/clip reveal animation the divider meets the edge of the clip
     * revealing surface at the earliest.
     */
    private static final float CLIP_REVEAL_MEET_EARLIEST = 0.6f;

    /**
     * The fraction during the maximize/clip reveal animation the divider meets the edge of the clip
     * revealing surface at the latest.
     */
    private static final float CLIP_REVEAL_MEET_LAST = 1f;

    /**
     * If the app translates at least CLIP_REVEAL_MEET_FRACTION_MIN * minimize distance, we start
     * meet somewhere between {@link #CLIP_REVEAL_MEET_LAST} and {@link #CLIP_REVEAL_MEET_EARLIEST}.
     */
    private static final float CLIP_REVEAL_MEET_FRACTION_MIN = 0.4f;

    /**
     * If the app translates equals or more than CLIP_REVEAL_MEET_FRACTION_MIN * minimize distance,
     * we meet at {@link #CLIP_REVEAL_MEET_EARLIEST}.
     */
    private static final float CLIP_REVEAL_MEET_FRACTION_MAX = 0.8f;

    private static final Interpolator IME_ADJUST_ENTRY_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0.1f, 1f);

    private static final long IME_ADJUST_ANIM_DURATION = 280;

    private static final long IME_ADJUST_DRAWN_TIMEOUT = 200;

    private static final int DIVIDER_WIDTH_INACTIVE_DP = 4;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private int mDividerWindowWidth;
    private int mDividerWindowWidthInactive;
    private int mDividerInsets;
    private boolean mResizing;
    private WindowState mWindow;
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final Rect mTmpRect3 = new Rect();
    private final Rect mLastRect = new Rect();
    private boolean mLastVisibility = false;
    private final RemoteCallbackList<IDockedStackListener> mDockedStackListeners
            = new RemoteCallbackList<>();
    private final DimLayer mDimLayer;

    private boolean mMinimizedDock;
    private boolean mAnimatingForMinimizedDockedStack;
    private boolean mAnimationStarted;
    private long mAnimationStartTime;
    private float mAnimationStart;
    private float mAnimationTarget;
    private long mAnimationDuration;
    private boolean mAnimationStartDelayed;
    private final Interpolator mMinimizedDockInterpolator;
    private float mMaximizeMeetFraction;
    private final Rect mTouchRegion = new Rect();
    private boolean mAnimatingForIme;
    private boolean mAdjustedForIme;
    private int mImeHeight;
    private WindowState mDelayedImeWin;
    private boolean mAdjustedForDivider;
    private float mDividerAnimationStart;
    private float mDividerAnimationTarget;
    private float mLastAnimationProgress;
    private float mLastDividerProgress;
    private final DividerSnapAlgorithm[] mSnapAlgorithmForRotation = new DividerSnapAlgorithm[4];
    private boolean mImeHideRequested;

    DockedStackDividerController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        final Context context = service.mContext;
        mDimLayer = new DimLayer(displayContent.mService, this, displayContent.getDisplayId(),
                "DockedStackDim");
        mMinimizedDockInterpolator = AnimationUtils.loadInterpolator(
                context, android.R.interpolator.fast_out_slow_in);
        loadDimens();
    }

    int getSmallestWidthDpForBounds(Rect bounds) {
        final DisplayInfo di = mDisplayContent.getDisplayInfo();

        // If the bounds are fullscreen, return the value of the fullscreen configuration
        if (bounds == null || (bounds.left == 0 && bounds.top == 0
                && bounds.right == di.logicalWidth && bounds.bottom == di.logicalHeight)) {
            return mService.mCurConfiguration.smallestScreenWidthDp;
        }
        final int baseDisplayWidth = mDisplayContent.mBaseDisplayWidth;
        final int baseDisplayHeight = mDisplayContent.mBaseDisplayHeight;
        int minWidth = Integer.MAX_VALUE;

        // Go through all screen orientations and find the orientation in which the task has the
        // smallest width.
        for (int rotation = 0; rotation < 4; rotation++) {
            mTmpRect.set(bounds);
            mDisplayContent.rotateBounds(di.rotation, rotation, mTmpRect);
            final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
            mTmpRect2.set(0, 0,
                    rotated ? baseDisplayHeight : baseDisplayWidth,
                    rotated ? baseDisplayWidth : baseDisplayHeight);
            final int orientation = mTmpRect2.width() <= mTmpRect2.height()
                    ? ORIENTATION_PORTRAIT
                    : ORIENTATION_LANDSCAPE;
            final int dockSide = TaskStack.getDockSideUnchecked(mTmpRect, mTmpRect2, orientation);
            final int position = DockedDividerUtils.calculatePositionForBounds(mTmpRect, dockSide,
                    getContentWidth());

            // Since we only care about feasible states, snap to the closest snap target, like it
            // would happen when actually rotating the screen.
            final int snappedPosition = mSnapAlgorithmForRotation[rotation]
                    .calculateNonDismissingSnapTarget(position).position;
            DockedDividerUtils.calculateBoundsForPosition(snappedPosition, dockSide, mTmpRect,
                    mTmpRect2.width(), mTmpRect2.height(), getContentWidth());
            mService.mPolicy.getStableInsetsLw(rotation, mTmpRect2.width(), mTmpRect2.height(),
                    mTmpRect3);
            mService.subtractInsets(mTmpRect2, mTmpRect3, mTmpRect);
            minWidth = Math.min(mTmpRect.width(), minWidth);
        }
        return (int) (minWidth / mDisplayContent.getDisplayMetrics().density);
    }

    private void initSnapAlgorithmForRotations() {
        final Configuration baseConfig = mService.mCurConfiguration;

        // Initialize the snap algorithms for all 4 screen orientations.
        final Configuration config = new Configuration();
        for (int rotation = 0; rotation < 4; rotation++) {
            final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
            final int dw = rotated
                    ? mDisplayContent.mBaseDisplayHeight
                    : mDisplayContent.mBaseDisplayWidth;
            final int dh = rotated
                    ? mDisplayContent.mBaseDisplayWidth
                    : mDisplayContent.mBaseDisplayHeight;
            mService.mPolicy.getStableInsetsLw(rotation, dw, dh, mTmpRect);
            config.setToDefaults();
            config.orientation = (dw <= dh) ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
            config.screenWidthDp = (int)
                    (mService.mPolicy.getConfigDisplayWidth(dw, dh, rotation, baseConfig.uiMode) /
                            mDisplayContent.getDisplayMetrics().density);
            config.screenHeightDp = (int)
                    (mService.mPolicy.getConfigDisplayHeight(dw, dh, rotation, baseConfig.uiMode) /
                            mDisplayContent.getDisplayMetrics().density);
            final Context rotationContext = mService.mContext.createConfigurationContext(config);
            mSnapAlgorithmForRotation[rotation] = new DividerSnapAlgorithm(
                    rotationContext.getResources(), dw, dh, getContentWidth(),
                    config.orientation == ORIENTATION_PORTRAIT, mTmpRect);
        }
    }

    private void loadDimens() {
        final Context context = mService.mContext;
        mDividerWindowWidth = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerInsets = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        mDividerWindowWidthInactive = WindowManagerService.dipToPixel(
                DIVIDER_WIDTH_INACTIVE_DP, mDisplayContent.getDisplayMetrics());
        initSnapAlgorithmForRotations();
    }

    void onConfigurationChanged() {
        loadDimens();
    }

    boolean isResizing() {
        return mResizing;
    }

    int getContentWidth() {
        return mDividerWindowWidth - 2 * mDividerInsets;
    }

    int getContentInsets() {
        return mDividerInsets;
    }

    int getContentWidthInactive() {
        return mDividerWindowWidthInactive;
    }

    void setResizing(boolean resizing) {
        if (mResizing != resizing) {
            mResizing = resizing;
            resetDragResizingChangeReported();
        }
    }

    void setTouchRegion(Rect touchRegion) {
        mTouchRegion.set(touchRegion);
    }

    void getTouchRegion(Rect outRegion) {
        outRegion.set(mTouchRegion);
        outRegion.offset(mWindow.getFrameLw().left, mWindow.getFrameLw().top);
    }

    private void resetDragResizingChangeReported() {
        final WindowList windowList = mDisplayContent.getWindowList();
        for (int i = windowList.size() - 1; i >= 0; i--) {
            windowList.get(i).resetDragResizingChangeReported();
        }
    }

    void setWindow(WindowState window) {
        mWindow = window;
        reevaluateVisibility(false);
    }

    void reevaluateVisibility(boolean force) {
        if (mWindow == null) {
            return;
        }
        TaskStack stack = mDisplayContent.mService.mStackIdToStack.get(DOCKED_STACK_ID);

        // If the stack is invisible, we policy force hide it in WindowAnimator.shouldForceHide
        final boolean visible = stack != null;
        if (mLastVisibility == visible && !force) {
            return;
        }
        mLastVisibility = visible;
        notifyDockedDividerVisibilityChanged(visible);
        if (!visible) {
            setResizeDimLayer(false, INVALID_STACK_ID, 0f);
        }
    }

    boolean wasVisible() {
        return mLastVisibility;
    }

    void setAdjustedForIme(
            boolean adjustedForIme, boolean adjustedForDivider,
            boolean animate, WindowState imeWin, int imeHeight) {
        if (mAdjustedForIme != adjustedForIme || (adjustedForIme && mImeHeight != imeHeight)
                || mAdjustedForDivider != adjustedForDivider) {
            if (animate && !mAnimatingForMinimizedDockedStack) {
                startImeAdjustAnimation(adjustedForIme, adjustedForDivider, imeWin);
            } else {
                // Animation might be delayed, so only notify if we don't run an animation.
                notifyAdjustedForImeChanged(adjustedForIme || adjustedForDivider, 0 /* duration */);
            }
            mAdjustedForIme = adjustedForIme;
            mImeHeight = imeHeight;
            mAdjustedForDivider = adjustedForDivider;
        }
    }

    int getImeHeightAdjustedFor() {
        return mImeHeight;
    }

    void positionDockedStackedDivider(Rect frame) {
        TaskStack stack = mDisplayContent.getDockedStackLocked();
        if (stack == null) {
            // Unfortunately we might end up with still having a divider, even though the underlying
            // stack was already removed. This is because we are on AM thread and the removal of the
            // divider was deferred to WM thread and hasn't happened yet. In that case let's just
            // keep putting it in the same place it was before the stack was removed to have
            // continuity and prevent it from jumping to the center. It will get hidden soon.
            frame.set(mLastRect);
            return;
        } else {
            stack.getDimBounds(mTmpRect);
        }
        int side = stack.getDockSide();
        switch (side) {
            case DOCKED_LEFT:
                frame.set(mTmpRect.right - mDividerInsets, frame.top,
                        mTmpRect.right + frame.width() - mDividerInsets, frame.bottom);
                break;
            case DOCKED_TOP:
                frame.set(frame.left, mTmpRect.bottom - mDividerInsets,
                        mTmpRect.right, mTmpRect.bottom + frame.height() - mDividerInsets);
                break;
            case DOCKED_RIGHT:
                frame.set(mTmpRect.left - frame.width() + mDividerInsets, frame.top,
                        mTmpRect.left + mDividerInsets, frame.bottom);
                break;
            case DOCKED_BOTTOM:
                frame.set(frame.left, mTmpRect.top - frame.height() + mDividerInsets,
                        frame.right, mTmpRect.top + mDividerInsets);
                break;
        }
        mLastRect.set(frame);
    }

    void notifyDockedDividerVisibilityChanged(boolean visible) {
        final int size = mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            final IDockedStackListener listener = mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDividerVisibilityChanged(visible);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering divider visibility changed event.", e);
            }
        }
        mDockedStackListeners.finishBroadcast();
    }

    void notifyDockedStackExistsChanged(boolean exists) {
        final int size = mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            final IDockedStackListener listener = mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDockedStackExistsChanged(exists);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering docked stack exists changed event.", e);
            }
        }
        mDockedStackListeners.finishBroadcast();
        if (exists) {
            InputMethodManagerInternal inputMethodManagerInternal =
                    LocalServices.getService(InputMethodManagerInternal.class);
            if (inputMethodManagerInternal != null) {

                // Hide the current IME to avoid problems with animations from IME adjustment when
                // attaching the docked stack.
                inputMethodManagerInternal.hideCurrentInputMethod();
                mImeHideRequested = true;
            }
        } else if (setMinimizedDockedStack(false)) {
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    /**
     * Resets the state that IME hide has been requested. See {@link #isImeHideRequested}.
     */
    void resetImeHideRequested() {
        mImeHideRequested = false;
    }

    /**
     * The docked stack divider controller makes sure the IME gets hidden when attaching the docked
     * stack, to avoid animation problems. This flag indicates whether the request to hide the IME
     * has been sent in an asynchronous manner, and the IME should be treated as hidden already.
     *
     * @return whether IME hide request has been sent
     */
    boolean isImeHideRequested() {
        return mImeHideRequested;
    }

    void notifyDockedStackMinimizedChanged(boolean minimizedDock, long animDuration) {
        mService.mH.removeMessages(NOTIFY_DOCKED_STACK_MINIMIZED_CHANGED);
        mService.mH.obtainMessage(NOTIFY_DOCKED_STACK_MINIMIZED_CHANGED,
                minimizedDock ? 1 : 0, 0).sendToTarget();
        final int size = mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            final IDockedStackListener listener = mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDockedStackMinimizedChanged(minimizedDock, animDuration);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering minimized dock changed event.", e);
            }
        }
        mDockedStackListeners.finishBroadcast();
    }

    void notifyDockSideChanged(int newDockSide) {
        final int size = mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            final IDockedStackListener listener = mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDockSideChanged(newDockSide);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering dock side changed event.", e);
            }
        }
        mDockedStackListeners.finishBroadcast();
    }

    void notifyAdjustedForImeChanged(boolean adjustedForIme, long animDuration) {
        final int size = mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            final IDockedStackListener listener = mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onAdjustedForImeChanged(adjustedForIme, animDuration);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering adjusted for ime changed event.", e);
            }
        }
        mDockedStackListeners.finishBroadcast();
    }

    void registerDockedStackListener(IDockedStackListener listener) {
        mDockedStackListeners.register(listener);
        notifyDockedDividerVisibilityChanged(wasVisible());
        notifyDockedStackExistsChanged(
                mDisplayContent.mService.mStackIdToStack.get(DOCKED_STACK_ID) != null);
        notifyDockedStackMinimizedChanged(mMinimizedDock, 0 /* animDuration */);
        notifyAdjustedForImeChanged(mAdjustedForIme, 0 /* animDuration */);

    }

    void setResizeDimLayer(boolean visible, int targetStackId, float alpha) {
        SurfaceControl.openTransaction();
        final TaskStack stack = mDisplayContent.mService.mStackIdToStack.get(targetStackId);
        final TaskStack dockedStack = mDisplayContent.getDockedStackLocked();
        boolean visibleAndValid = visible && stack != null && dockedStack != null;
        if (visibleAndValid) {
            stack.getDimBounds(mTmpRect);
            if (mTmpRect.height() > 0 && mTmpRect.width() > 0) {
                mDimLayer.setBounds(mTmpRect);
                mDimLayer.show(mService.mLayersController.getResizeDimLayer(),
                        alpha, 0 /* duration */);
            } else {
                visibleAndValid = false;
            }
        }
        if (!visibleAndValid) {
            mDimLayer.hide();
        }
        SurfaceControl.closeTransaction();
    }

    /**
     * Notifies the docked stack divider controller of a visibility change that happens without
     * an animation.
     */
    void notifyAppVisibilityChanged() {
        checkMinimizeChanged(false /* animate */);
    }

    void notifyAppTransitionStarting() {
        checkMinimizeChanged(true /* animate */);
    }

    boolean isMinimizedDock() {
        return mMinimizedDock;
    }

    private void checkMinimizeChanged(boolean animate) {
        if (mDisplayContent.getDockedStackVisibleForUserLocked() == null) {
            return;
        }
        final TaskStack homeStack = mDisplayContent.getHomeStack();
        if (homeStack == null) {
            return;
        }
        final Task homeTask = homeStack.findHomeTask();
        if (homeTask == null || !isWithinDisplay(homeTask)) {
            return;
        }
        final TaskStack fullscreenStack
                = mService.mStackIdToStack.get(FULLSCREEN_WORKSPACE_STACK_ID);
        final ArrayList<Task> homeStackTasks = homeStack.getTasks();
        final Task topHomeStackTask = homeStackTasks.get(homeStackTasks.size() - 1);
        final boolean homeVisible = homeTask.getTopVisibleAppToken() != null;
        final boolean homeBehind = (fullscreenStack != null && fullscreenStack.isVisibleLocked())
                || (homeStackTasks.size() > 1 && topHomeStackTask != homeTask);
        setMinimizedDockedStack(homeVisible && !homeBehind, animate);
    }

    private boolean isWithinDisplay(Task task) {
        task.mStack.getBounds(mTmpRect);
        mDisplayContent.getLogicalDisplayRect(mTmpRect2);
        return mTmpRect.intersect(mTmpRect2);
    }

    /**
     * Sets whether the docked stack is currently in a minimized state, i.e. all the tasks in the
     * docked stack are heavily clipped so you can only see a minimal peek state.
     *
     * @param minimizedDock Whether the docked stack is currently minimized.
     * @param animate Whether to animate the change.
     */
    private void setMinimizedDockedStack(boolean minimizedDock, boolean animate) {
        final boolean wasMinimized = mMinimizedDock;
        mMinimizedDock = minimizedDock;
        if (minimizedDock == wasMinimized) {
            return;
        }

        final boolean imeChanged = clearImeAdjustAnimation();
        boolean minimizedChange = false;
        if (minimizedDock) {
            if (animate) {
                startAdjustAnimation(0f, 1f);
            } else {
                minimizedChange |= setMinimizedDockedStack(true);
            }
        } else {
            if (animate) {
                startAdjustAnimation(1f, 0f);
            } else {
                minimizedChange |= setMinimizedDockedStack(false);
            }
        }
        if (imeChanged || minimizedChange) {
            if (imeChanged && !minimizedChange) {
                Slog.d(TAG, "setMinimizedDockedStack: IME adjust changed due to minimizing,"
                        + " minimizedDock=" + minimizedDock
                        + " minimizedChange=" + minimizedChange);
            }
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    private boolean clearImeAdjustAnimation() {
        boolean changed = false;
        final ArrayList<TaskStack> stacks = mDisplayContent.getStacks();
        for (int i = stacks.size() - 1; i >= 0; --i) {
            final TaskStack stack = stacks.get(i);
            if (stack != null && stack.isAdjustedForIme()) {
                stack.resetAdjustedForIme(true /* adjustBoundsNow */);
                changed  = true;
            }
        }
        mAnimatingForIme = false;
        return changed;
    }

    private void startAdjustAnimation(float from, float to) {
        mAnimatingForMinimizedDockedStack = true;
        mAnimationStarted = false;
        mAnimationStart = from;
        mAnimationTarget = to;
    }

    private void startImeAdjustAnimation(
            boolean adjustedForIme, boolean adjustedForDivider, WindowState imeWin) {

        // If we're not in an animation, the starting point depends on whether we're adjusted
        // or not. If we're already in an animation, we start from where the current animation
        // left off, so that the motion doesn't look discontinuous.
        if (!mAnimatingForIme) {
            mAnimationStart = mAdjustedForIme ? 1 : 0;
            mDividerAnimationStart = mAdjustedForDivider ? 1 : 0;
            mLastAnimationProgress = mAnimationStart;
            mLastDividerProgress = mDividerAnimationStart;
        } else {
            mAnimationStart = mLastAnimationProgress;
            mDividerAnimationStart = mLastDividerProgress;
        }
        mAnimatingForIme = true;
        mAnimationStarted = false;
        mAnimationTarget = adjustedForIme ? 1 : 0;
        mDividerAnimationTarget = adjustedForDivider ? 1 : 0;

        final ArrayList<TaskStack> stacks = mDisplayContent.getStacks();
        for (int i = stacks.size() - 1; i >= 0; --i) {
            final TaskStack stack = stacks.get(i);
            if (stack.isVisibleLocked() && stack.isAdjustedForIme()) {
                stack.beginImeAdjustAnimation();
            }
        }

        // We put all tasks into drag resizing mode - wait until all of them have completed the
        // drag resizing switch.
        if (!mService.mWaitingForDrawn.isEmpty()) {
            mService.mH.removeMessages(H.WAITING_FOR_DRAWN_TIMEOUT);
            mService.mH.sendEmptyMessageDelayed(H.WAITING_FOR_DRAWN_TIMEOUT,
                    IME_ADJUST_DRAWN_TIMEOUT);
            mAnimationStartDelayed = true;
            if (imeWin != null) {

                // There might be an old window delaying the animation start - clear it.
                if (mDelayedImeWin != null) {
                    mDelayedImeWin.mWinAnimator.endDelayingAnimationStart();
                }
                mDelayedImeWin = imeWin;
                imeWin.mWinAnimator.startDelayingAnimationStart();
            }
            mService.mWaitingForDrawnCallback = () -> {
                mAnimationStartDelayed = false;
                if (mDelayedImeWin != null) {
                    mDelayedImeWin.mWinAnimator.endDelayingAnimationStart();
                }
                // If the adjust status changed since this was posted, only notify
                // the new states and don't animate.
                long duration = 0;
                if (mAdjustedForIme == adjustedForIme
                        && mAdjustedForDivider == adjustedForDivider) {
                    duration = IME_ADJUST_ANIM_DURATION;
                } else {
                    Slog.w(TAG, "IME adjust changed while waiting for drawn:"
                            + " adjustedForIme=" + adjustedForIme
                            + " adjustedForDivider=" + adjustedForDivider
                            + " mAdjustedForIme=" + mAdjustedForIme
                            + " mAdjustedForDivider=" + mAdjustedForDivider);
                }
                notifyAdjustedForImeChanged(
                        mAdjustedForIme || mAdjustedForDivider, duration);
            };
        } else {
            notifyAdjustedForImeChanged(
                    adjustedForIme || adjustedForDivider, IME_ADJUST_ANIM_DURATION);
        }
    }

    private boolean setMinimizedDockedStack(boolean minimized) {
        final TaskStack stack = mDisplayContent.getDockedStackVisibleForUserLocked();
        notifyDockedStackMinimizedChanged(minimized, 0);
        return stack != null && stack.setAdjustedForMinimizedDock(minimized ? 1f : 0f);
    }

    private boolean isAnimationMaximizing() {
        return mAnimationTarget == 0f;
    }

    public boolean animate(long now) {
        if (mWindow == null) {
            return false;
        }
        if (mAnimatingForMinimizedDockedStack) {
            return animateForMinimizedDockedStack(now);
        } else if (mAnimatingForIme) {
            return animateForIme(now);
        } else {
            if (mDimLayer != null && mDimLayer.isDimming()) {
                mDimLayer.setLayer(mService.mLayersController.getResizeDimLayer());
            }
            return false;
        }
    }

    private boolean animateForIme(long now) {
        if (!mAnimationStarted || mAnimationStartDelayed) {
            mAnimationStarted = true;
            mAnimationStartTime = now;
            mAnimationDuration = (long)
                    (IME_ADJUST_ANIM_DURATION * mService.getWindowAnimationScaleLocked());
        }
        float t = Math.min(1f, (float) (now - mAnimationStartTime) / mAnimationDuration);
        t = (mAnimationTarget == 1f ? IME_ADJUST_ENTRY_INTERPOLATOR : TOUCH_RESPONSE_INTERPOLATOR)
                .getInterpolation(t);
        final ArrayList<TaskStack> stacks = mDisplayContent.getStacks();
        boolean updated = false;
        for (int i = stacks.size() - 1; i >= 0; --i) {
            final TaskStack stack = stacks.get(i);
            if (stack != null && stack.isAdjustedForIme()) {
                if (t >= 1f && mAnimationTarget == 0f && mDividerAnimationTarget == 0f) {
                    stack.resetAdjustedForIme(true /* adjustBoundsNow */);
                    updated = true;
                } else {
                    mLastAnimationProgress = getInterpolatedAnimationValue(t);
                    mLastDividerProgress = getInterpolatedDividerValue(t);
                    updated |= stack.updateAdjustForIme(
                            mLastAnimationProgress,
                            mLastDividerProgress,
                            false /* force */);
                }
                if (t >= 1f) {
                    stack.endImeAdjustAnimation();
                }
            }
        }
        if (updated) {
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
        if (t >= 1.0f) {
            mLastAnimationProgress = mAnimationTarget;
            mLastDividerProgress = mDividerAnimationTarget;
            mAnimatingForIme = false;
            return false;
        } else {
            return true;
        }
    }

    private boolean animateForMinimizedDockedStack(long now) {
        final TaskStack stack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        if (!mAnimationStarted) {
            mAnimationStarted = true;
            mAnimationStartTime = now;
            final long transitionDuration = isAnimationMaximizing()
                    ? mService.mAppTransition.getLastClipRevealTransitionDuration()
                    : DEFAULT_APP_TRANSITION_DURATION;
            mAnimationDuration = (long)
                    (transitionDuration * mService.getTransitionAnimationScaleLocked());
            mMaximizeMeetFraction = getClipRevealMeetFraction(stack);
            notifyDockedStackMinimizedChanged(mMinimizedDock,
                    (long) (mAnimationDuration * mMaximizeMeetFraction));
        }
        float t = Math.min(1f, (float) (now - mAnimationStartTime) / mAnimationDuration);
        t = (isAnimationMaximizing() ? TOUCH_RESPONSE_INTERPOLATOR : mMinimizedDockInterpolator)
                .getInterpolation(t);
        if (stack != null) {
            if (stack.setAdjustedForMinimizedDock(getMinimizeAmount(stack, t))) {
                mService.mWindowPlacerLocked.performSurfacePlacement();
            }
        }
        if (t >= 1.0f) {
            mAnimatingForMinimizedDockedStack = false;
            return false;
        } else {
            return true;
        }
    }

    private float getInterpolatedAnimationValue(float t) {
        return t * mAnimationTarget + (1 - t) * mAnimationStart;
    }

    private float getInterpolatedDividerValue(float t) {
        return t * mDividerAnimationTarget + (1 - t) * mDividerAnimationStart;
    }

    /**
     * Gets the amount how much to minimize a stack depending on the interpolated fraction t.
     */
    private float getMinimizeAmount(TaskStack stack, float t) {
        final float naturalAmount = getInterpolatedAnimationValue(t);
        if (isAnimationMaximizing()) {
            return adjustMaximizeAmount(stack, t, naturalAmount);
        } else {
            return naturalAmount;
        }
    }

    /**
     * When maximizing the stack during a clip reveal transition, this adjusts the minimize amount
     * during the transition such that the edge of the clip reveal rect is met earlier in the
     * transition so we don't create a visible "hole", but only if both the clip reveal and the
     * docked stack divider start from about the same portion on the screen.
     */
    private float adjustMaximizeAmount(TaskStack stack, float t, float naturalAmount) {
        if (mMaximizeMeetFraction == 1f) {
            return naturalAmount;
        }
        final int minimizeDistance = stack.getMinimizeDistance();
        float startPrime = mService.mAppTransition.getLastClipRevealMaxTranslation()
                / (float) minimizeDistance;
        final float amountPrime = t * mAnimationTarget + (1 - t) * startPrime;
        final float t2 = Math.min(t / mMaximizeMeetFraction, 1);
        return amountPrime * t2 + naturalAmount * (1 - t2);
    }

    /**
     * Retrieves the animation fraction at which the docked stack has to meet the clip reveal
     * edge. See {@link #adjustMaximizeAmount}.
     */
    private float getClipRevealMeetFraction(TaskStack stack) {
        if (!isAnimationMaximizing() || stack == null ||
                !mService.mAppTransition.hadClipRevealAnimation()) {
            return 1f;
        }
        final int minimizeDistance = stack.getMinimizeDistance();
        final float fraction = Math.abs(mService.mAppTransition.getLastClipRevealMaxTranslation())
                / (float) minimizeDistance;
        final float t = Math.max(0, Math.min(1, (fraction - CLIP_REVEAL_MEET_FRACTION_MIN)
                / (CLIP_REVEAL_MEET_FRACTION_MAX - CLIP_REVEAL_MEET_FRACTION_MIN)));
        return CLIP_REVEAL_MEET_EARLIEST
                + (1 - t) * (CLIP_REVEAL_MEET_LAST - CLIP_REVEAL_MEET_EARLIEST);
    }

    @Override
    public boolean dimFullscreen() {
        return false;
    }

    @Override
    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    @Override
    public void getDimBounds(Rect outBounds) {
        // This dim layer user doesn't need this.
    }

    @Override
    public String toShortString() {
        return TAG;
    }

    WindowState getWindow() {
        return mWindow;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DockedStackDividerController");
        pw.println(prefix + "  mLastVisibility=" + mLastVisibility);
        pw.println(prefix + "  mMinimizedDock=" + mMinimizedDock);
        pw.println(prefix + "  mAdjustedForIme=" + mAdjustedForIme);
        pw.println(prefix + "  mAdjustedForDivider=" + mAdjustedForDivider);
        if (mDimLayer.isDimming()) {
            pw.println(prefix + "  Dim layer is dimming: ");
            mDimLayer.printTo(prefix + "    ", pw);
        }
    }
}
