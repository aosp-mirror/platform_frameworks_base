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

import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import static com.android.server.wm.AppTransition.DEFAULT_APP_TRANSITION_DURATION;
import static com.android.server.wm.AppTransition.TOUCH_RESPONSE_INTERPOLATOR;
import static com.android.server.wm.DockedStackDividerControllerProto.MINIMIZED_DOCK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.LAYER_OFFSET_DIM;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.IDockedStackListener;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DockedDividerUtils;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;

/**
 * Keeps information about the docked stack divider.
 */
public class DockedStackDividerController {

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
    private int mTaskHeightInMinimizedMode;
    private boolean mResizing;
    private WindowState mWindow;
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final Rect mTmpRect3 = new Rect();
    private final Rect mLastRect = new Rect();
    private boolean mLastVisibility = false;
    private final RemoteCallbackList<IDockedStackListener> mDockedStackListeners
            = new RemoteCallbackList<>();

    private boolean mMinimizedDock;
    private int mOriginalDockedSide = DOCKED_INVALID;
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
    float mLastAnimationProgress;
    float mLastDividerProgress;
    private final DividerSnapAlgorithm[] mSnapAlgorithmForRotation = new DividerSnapAlgorithm[4];
    private boolean mImeHideRequested;
    private final Rect mLastDimLayerRect = new Rect();
    private float mLastDimLayerAlpha;
    private TaskStack mDimmedStack;

    DockedStackDividerController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        final Context context = service.mContext;
        mMinimizedDockInterpolator = AnimationUtils.loadInterpolator(
                context, android.R.interpolator.fast_out_slow_in);
        loadDimens();
    }

    int getSmallestWidthDpForBounds(Rect bounds) {
        final DisplayInfo di = mDisplayContent.getDisplayInfo();

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
            final int dockSide = getDockSide(mTmpRect, mTmpRect2, orientation, rotation);
            final int position = DockedDividerUtils.calculatePositionForBounds(mTmpRect, dockSide,
                    getContentWidth());

            final DisplayCutout displayCutout = mDisplayContent.calculateDisplayCutoutForRotation(
                    rotation).getDisplayCutout();

            // Since we only care about feasible states, snap to the closest snap target, like it
            // would happen when actually rotating the screen.
            final int snappedPosition = mSnapAlgorithmForRotation[rotation]
                    .calculateNonDismissingSnapTarget(position).position;
            DockedDividerUtils.calculateBoundsForPosition(snappedPosition, dockSide, mTmpRect,
                    mTmpRect2.width(), mTmpRect2.height(), getContentWidth());
            mDisplayContent.getDisplayPolicy().getStableInsetsLw(rotation, mTmpRect2.width(),
                    mTmpRect2.height(), displayCutout, mTmpRect3);
            mService.intersectDisplayInsetBounds(mTmpRect2, mTmpRect3, mTmpRect);
            minWidth = Math.min(mTmpRect.width(), minWidth);
        }
        return (int) (minWidth / mDisplayContent.getDisplayMetrics().density);
    }

    /**
     * Get the current docked side. Determined by its location of {@param bounds} within
     * {@param displayRect} but if both are the same, it will try to dock to each side and determine
     * if allowed in its respected {@param orientation}.
     *
     * @param bounds bounds of the docked task to get which side is docked
     * @param displayRect bounds of the display that contains the docked task
     * @param orientation the origination of device
     * @return current docked side
     */
    int getDockSide(Rect bounds, Rect displayRect, int orientation, int rotation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            // Portrait mode, docked either at the top or the bottom.
            final int diff = (displayRect.bottom - bounds.bottom) - (bounds.top - displayRect.top);
            if (diff > 0) {
                return DOCKED_TOP;
            } else if (diff < 0) {
                return DOCKED_BOTTOM;
            }
            return canPrimaryStackDockTo(DOCKED_TOP, displayRect, rotation)
                    ? DOCKED_TOP : DOCKED_BOTTOM;
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape mode, docked either on the left or on the right.
            final int diff = (displayRect.right - bounds.right) - (bounds.left - displayRect.left);
            if (diff > 0) {
                return DOCKED_LEFT;
            } else if (diff < 0) {
                return DOCKED_RIGHT;
            }
            return canPrimaryStackDockTo(DOCKED_LEFT, displayRect, rotation)
                    ? DOCKED_LEFT : DOCKED_RIGHT;
        }
        return DOCKED_INVALID;
    }

    void getHomeStackBoundsInDockedMode(Configuration parentConfig, int dockSide, Rect outBounds) {
        final DisplayCutout displayCutout = mDisplayContent.getDisplayInfo().displayCutout;
        final int displayWidth = parentConfig.windowConfiguration.getBounds().width();
        final int displayHeight = parentConfig.windowConfiguration.getBounds().height();
        mDisplayContent.getDisplayPolicy().getStableInsetsLw(
                parentConfig.windowConfiguration.getRotation(), displayWidth, displayHeight,
                displayCutout, mTmpRect);
        int dividerSize = mDividerWindowWidth - 2 * mDividerInsets;
        // The offset in the left (landscape)/top (portrait) is calculated with the minimized
        // offset value with the divider size and any system insets in that direction.
        if (parentConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            outBounds.set(0, mTaskHeightInMinimizedMode + dividerSize + mTmpRect.top,
                    displayWidth, displayHeight);
        } else {
            // In landscape also inset the left/right side with the status bar height to match the
            // minimized size height in portrait mode.
            final int primaryTaskWidth = mTaskHeightInMinimizedMode + dividerSize + mTmpRect.top;
            int left = mTmpRect.left;
            int right = displayWidth - mTmpRect.right;
            if (dockSide == DOCKED_LEFT) {
                left += primaryTaskWidth;
            } else if (dockSide == DOCKED_RIGHT) {
                right -= primaryTaskWidth;
            }
            outBounds.set(left, 0, right, displayHeight);
        }
    }

    boolean isHomeStackResizable() {
        final TaskStack homeStack = mDisplayContent.getHomeStack();
        if (homeStack == null) {
            return false;
        }
        final Task homeTask = homeStack.findHomeTask();
        return homeTask != null && homeTask.isResizeable();
    }

    private void initSnapAlgorithmForRotations() {
        final Configuration baseConfig = mDisplayContent.getConfiguration();

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
            final DisplayCutout displayCutout =
                    mDisplayContent.calculateDisplayCutoutForRotation(rotation).getDisplayCutout();
            final DisplayPolicy displayPolicy =  mDisplayContent.getDisplayPolicy();
            displayPolicy.getStableInsetsLw(rotation, dw, dh, displayCutout, mTmpRect);
            config.unset();
            config.orientation = (dw <= dh) ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;

            final int appWidth = displayPolicy.getNonDecorDisplayWidth(dw, dh, rotation,
                    baseConfig.uiMode, displayCutout);
            final int appHeight = displayPolicy.getNonDecorDisplayHeight(dw, dh, rotation,
                    baseConfig.uiMode, displayCutout);
            displayPolicy.getNonDecorInsetsLw(rotation, dw, dh, displayCutout, mTmpRect);
            final int leftInset = mTmpRect.left;
            final int topInset = mTmpRect.top;

            config.windowConfiguration.setAppBounds(leftInset /*left*/, topInset /*top*/,
                    leftInset + appWidth /*right*/, topInset + appHeight /*bottom*/);

            final float density = mDisplayContent.getDisplayMetrics().density;
            config.screenWidthDp = (int) (displayPolicy.getConfigDisplayWidth(dw, dh, rotation,
                    baseConfig.uiMode, displayCutout) / density);
            config.screenHeightDp = (int) (displayPolicy.getConfigDisplayHeight(dw, dh, rotation,
                    baseConfig.uiMode, displayCutout) / density);
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
        mTaskHeightInMinimizedMode = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.task_height_of_minimized_mode);
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
        mDisplayContent.forAllWindows(WindowState::resetDragResizingChangeReported,
                true /* traverseTopToBottom */ );
    }

    void setWindow(WindowState window) {
        mWindow = window;
        reevaluateVisibility(false);
    }

    void reevaluateVisibility(boolean force) {
        if (mWindow == null) {
            return;
        }
        TaskStack stack = mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();

        // If the stack is invisible, we policy force hide it in WindowAnimator.shouldForceHide
        final boolean visible = stack != null;
        if (mLastVisibility == visible && !force) {
            return;
        }
        mLastVisibility = visible;
        notifyDockedDividerVisibilityChanged(visible);
        if (!visible) {
            setResizeDimLayer(false, WINDOWING_MODE_UNDEFINED, 0f);
        }
    }

    private boolean wasVisible() {
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
        TaskStack stack = mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
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

    private void notifyDockedDividerVisibilityChanged(boolean visible) {
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

    /**
     * Checks if the primary stack is allowed to dock to a specific side based on its original dock
     * side.
     *
     * @param dockSide the side to see if it is valid
     * @return true if the side provided is valid
     */
    boolean canPrimaryStackDockTo(int dockSide, Rect parentRect, int rotation) {
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        return isDockSideAllowed(dockSide, mOriginalDockedSide,
                policy.navigationBarPosition(parentRect.width(), parentRect.height(), rotation),
                policy.navigationBarCanMove());
    }

    @VisibleForTesting
    static boolean isDockSideAllowed(int dockSide, int originalDockSide, int navBarPosition,
            boolean navigationBarCanMove) {
        if (dockSide == DOCKED_TOP) {
            return true;
        }

        if (navigationBarCanMove) {
            // Only allow the dockside opposite to the nav bar position in landscape
            return dockSide == DOCKED_LEFT && navBarPosition == NAV_BAR_RIGHT
                    || dockSide == DOCKED_RIGHT && navBarPosition == NAV_BAR_LEFT;
        }

        // Side is the same as original side
        if (dockSide == originalDockSide) {
            return true;
        }

        // Only if original docked side was top in portrait will allow left for landscape
        return dockSide == DOCKED_LEFT && originalDockSide == DOCKED_TOP;
    }

    void notifyDockedStackExistsChanged(boolean exists) {
        // TODO(multi-display): Perform all actions only for current display.
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

            // If a primary stack was just created, it will not have access to display content at
            // this point so pass it from here to get a valid dock side.
            final TaskStack stack = mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
            mOriginalDockedSide = stack.getDockSideForDisplay(mDisplayContent);
            return;
        }
        mOriginalDockedSide = DOCKED_INVALID;
        setMinimizedDockedStack(false /* minimizedDock */, false /* animate */);

        if (mDimmedStack != null) {
            mDimmedStack.stopDimming();
            mDimmedStack = null;
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

    private void notifyDockedStackMinimizedChanged(boolean minimizedDock, boolean animate,
            boolean isHomeStackResizable) {
        long animDuration = 0;
        if (animate) {
            final TaskStack stack =
                    mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
            final long transitionDuration = isAnimationMaximizing()
                    ? mDisplayContent.mAppTransition.getLastClipRevealTransitionDuration()
                    : DEFAULT_APP_TRANSITION_DURATION;
            mAnimationDuration = (long)
                    (transitionDuration * mService.getTransitionAnimationScaleLocked());
            mMaximizeMeetFraction = getClipRevealMeetFraction(stack);
            animDuration = (long) (mAnimationDuration * mMaximizeMeetFraction);
        }
        mService.mAtmInternal.notifyDockedStackMinimizedChanged(minimizedDock);
        final int size = mDockedStackListeners.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            final IDockedStackListener listener = mDockedStackListeners.getBroadcastItem(i);
            try {
                listener.onDockedStackMinimizedChanged(minimizedDock, animDuration,
                        isHomeStackResizable);
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

    private void notifyAdjustedForImeChanged(boolean adjustedForIme, long animDuration) {
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
                mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility() != null);
        notifyDockedStackMinimizedChanged(mMinimizedDock, false /* animate */,
                isHomeStackResizable());
        notifyAdjustedForImeChanged(mAdjustedForIme, 0 /* animDuration */);

    }

    /**
     * Shows a dim layer with {@param alpha} if {@param visible} is true and
     * {@param targetWindowingMode} isn't
     * {@link android.app.WindowConfiguration#WINDOWING_MODE_UNDEFINED} and there is a stack on the
     * display in that windowing mode.
     */
    void setResizeDimLayer(boolean visible, int targetWindowingMode, float alpha) {
        // TODO: Maybe only allow split-screen windowing modes?
        final TaskStack stack = targetWindowingMode != WINDOWING_MODE_UNDEFINED
                ? mDisplayContent.getTopStackInWindowingMode(targetWindowingMode)
                : null;
        final TaskStack dockedStack = mDisplayContent.getSplitScreenPrimaryStack();
        boolean visibleAndValid = visible && stack != null && dockedStack != null;

        // Ensure an old dim that was shown for the docked stack divider is removed so we don't end
        // up with dim layers that can no longer be removed.
        if (mDimmedStack != null && mDimmedStack != stack) {
            mDimmedStack.stopDimming();
            mDimmedStack = null;
        }

        if (visibleAndValid) {
            mDimmedStack = stack;
            stack.dim(alpha);
        }
        if (!visibleAndValid && stack != null) {
            mDimmedStack = null;
            stack.stopDimming();
        }
    }

    /**
     * @return The layer used for dimming the apps when dismissing docked/fullscreen stack. Just
     *         above all application surfaces.
     */
    private int getResizeDimLayer() {
        return (mWindow != null) ? mWindow.mLayer - 1 : LAYER_OFFSET_DIM;
    }

    /**
     * Notifies the docked stack divider controller of a visibility change that happens without
     * an animation.
     */
    void notifyAppVisibilityChanged() {
        checkMinimizeChanged(false /* animate */);
    }

    void notifyAppTransitionStarting(ArraySet<AppWindowToken> openingApps, int appTransition) {
        final boolean wasMinimized = mMinimizedDock;
        checkMinimizeChanged(true /* animate */);

        // We were minimized, and now we are still minimized, but somebody is trying to launch an
        // app in docked stack, better show recent apps so we actually get unminimized! However do
        // not do this if keyguard is dismissed such as when the device is unlocking. This catches
        // any case that was missed in ActivityStarter.postStartActivityUncheckedProcessing because
        // we couldn't retrace the launch of the app in the docked stack to the launch from
        // homescreen.
        if (wasMinimized && mMinimizedDock && containsAppInDockedStack(openingApps)
                && appTransition != TRANSIT_NONE &&
                !AppTransition.isKeyguardGoingAwayTransit(appTransition)) {
            if (mService.mAtmInternal.isRecentsComponentHomeActivity(mService.mCurrentUserId)) {
                // When the home activity is the recents component and we are already minimized,
                // then there is nothing to do here since home is already visible
            } else {
                mService.showRecentApps();
            }
        }
    }

    /**
     * @return true if {@param apps} contains an activity in the docked stack, false otherwise.
     */
    private boolean containsAppInDockedStack(ArraySet<AppWindowToken> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            final AppWindowToken token = apps.valueAt(i);
            if (token.getTask() != null && token.inSplitScreenPrimaryWindowingMode()) {
                return true;
            }
        }
        return false;
    }

    boolean isMinimizedDock() {
        return mMinimizedDock;
    }

    void checkMinimizeChanged(boolean animate) {
        if (mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility() == null) {
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

        // Do not minimize when dock is already minimized while keyguard is showing and not
        // occluded such as unlocking the screen
        if (mMinimizedDock && mService.mKeyguardOrAodShowingOnDefaultDisplay) {
            return;
        }
        final TaskStack topSecondaryStack = mDisplayContent.getTopStackInWindowingMode(
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        final RecentsAnimationController recentsAnim = mService.getRecentsAnimationController();
        final boolean minimizedForRecentsAnimation = recentsAnim != null &&
                recentsAnim.isSplitScreenMinimized();
        boolean homeVisible = homeTask.getTopVisibleAppToken() != null;
        if (homeVisible && topSecondaryStack != null) {
            // Home should only be considered visible if it is greater or equal to the top secondary
            // stack in terms of z-order.
            homeVisible = homeStack.compareTo(topSecondaryStack) >= 0;
        }
        setMinimizedDockedStack(homeVisible || minimizedForRecentsAnimation, animate);
    }

    private boolean isWithinDisplay(Task task) {
        task.getBounds(mTmpRect);
        mDisplayContent.getBounds(mTmpRect2);
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
        if (isHomeStackResizable()) {
            notifyDockedStackMinimizedChanged(minimizedDock, animate,
                    true /* isHomeStackResizable */);
            minimizedChange = true;
        } else {
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
        final boolean changed = mDisplayContent.clearImeAdjustAnimation();
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

        mDisplayContent.beginImeAdjustAnimation();

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
                    mDelayedImeWin.endDelayingAnimationStart();
                }
                mDelayedImeWin = imeWin;
                imeWin.startDelayingAnimationStart();
            }

            // If we are already waiting for something to be drawn, clear out the old one so it
            // still gets executed.
            // TODO: Have a real system where we can wait on different windows to be drawn with
            // different callbacks.
            if (mService.mWaitingForDrawnCallback != null) {
                mService.mWaitingForDrawnCallback.run();
            }
            mService.mWaitingForDrawnCallback = () -> {
                synchronized (mService.mGlobalLock) {
                    mAnimationStartDelayed = false;
                    if (mDelayedImeWin != null) {
                        mDelayedImeWin.endDelayingAnimationStart();
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
                }
            };
        } else {
            notifyAdjustedForImeChanged(
                    adjustedForIme || adjustedForDivider, IME_ADJUST_ANIM_DURATION);
        }
    }

    private boolean setMinimizedDockedStack(boolean minimized) {
        final TaskStack stack = mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        notifyDockedStackMinimizedChanged(minimized, false /* animate */, isHomeStackResizable());
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
        }
        return false;
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
        final boolean updated =
                mDisplayContent.animateForIme(t, mAnimationTarget, mDividerAnimationTarget);
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
        final TaskStack stack = mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        if (!mAnimationStarted) {
            mAnimationStarted = true;
            mAnimationStartTime = now;
            notifyDockedStackMinimizedChanged(mMinimizedDock, true /* animate */,
                    isHomeStackResizable() /* isHomeStackResizable */);
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

    float getInterpolatedAnimationValue(float t) {
        return t * mAnimationTarget + (1 - t) * mAnimationStart;
    }

    float getInterpolatedDividerValue(float t) {
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
        final float startPrime = mDisplayContent.mAppTransition.getLastClipRevealMaxTranslation()
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
                !mDisplayContent.mAppTransition.hadClipRevealAnimation()) {
            return 1f;
        }
        final int minimizeDistance = stack.getMinimizeDistance();
        final float fraction = Math.abs(mDisplayContent.mAppTransition
                .getLastClipRevealMaxTranslation()) / (float) minimizeDistance;
        final float t = Math.max(0, Math.min(1, (fraction - CLIP_REVEAL_MEET_FRACTION_MIN)
                / (CLIP_REVEAL_MEET_FRACTION_MAX - CLIP_REVEAL_MEET_FRACTION_MIN)));
        return CLIP_REVEAL_MEET_EARLIEST
                + (1 - t) * (CLIP_REVEAL_MEET_LAST - CLIP_REVEAL_MEET_EARLIEST);
    }

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
    }

    void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(MINIMIZED_DOCK, mMinimizedDock);
        proto.end(token);
    }
}
