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
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_TOP;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.WINDOW_HIDE_TIMEOUT;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.WindowManagerService.localLOGV;
import static com.android.server.wm.WindowState.RESIZE_HANDLE_WIDTH_IN_DP;

import android.annotation.NonNull;
import android.app.ActivityManager.StackId;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManagerInternal;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;

import com.android.internal.util.FastPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Utility class for keeping track of the WindowStates and other pertinent contents of a
 * particular Display.
 *
 * IMPORTANT: No method from this class should ever be used without holding
 * WindowManagerService.mWindowMap.
 */
class DisplayContent extends WindowContainer<DisplayContent.DisplayChildWindowContainer> {

    /** Unique identifier of this stack. */
    private final int mDisplayId;

    // The display only has 2 child window containers. mTaskStackContainers which contains all
    // window containers that are related to apps (Activities) and mNonAppWindowContainers which
    // contains all window containers not related to apps (e.g. Status bar).
    private final TaskStackContainers mTaskStackContainers = new TaskStackContainers();
    private final NonAppWindowContainers mNonAppWindowContainers = new NonAppWindowContainers();

    /** Z-ordered (bottom-most first) list of all Window objects. Assigned to an element
     * from mDisplayWindows; */
    private final WindowList mWindows = new WindowList();

    // Mapping from a token IBinder to a WindowToken object on this display.
    private final HashMap<IBinder, WindowToken> mTokenMap = new HashMap();

    int mInitialDisplayWidth = 0;
    int mInitialDisplayHeight = 0;
    int mInitialDisplayDensity = 0;
    int mBaseDisplayWidth = 0;
    int mBaseDisplayHeight = 0;
    int mBaseDisplayDensity = 0;
    boolean mDisplayScalingDisabled;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Display mDisplay;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();

    Rect mBaseDisplayRect = new Rect();
    private Rect mContentRect = new Rect();

    // Accessed directly by all users.
    private boolean mLayoutNeeded;
    int pendingLayoutChanges;
    final boolean isDefaultDisplay;

    /** Window tokens that are in the process of exiting, but still on screen for animations. */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<>();

    /** A special TaskStack with id==HOME_STACK_ID that moves to the bottom whenever any TaskStack
     * (except a future lockscreen TaskStack) moves to the top. */
    private TaskStack mHomeStack = null;

    /** Detect user tapping outside of current focused task bounds .*/
    TaskTapPointerEventListener mTapDetector;

    /** Detect user tapping outside of current focused stack bounds .*/
    private Region mTouchExcludeRegion = new Region();

    /** Save allocating when calculating rects */
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final RectF mTmpRectF = new RectF();
    private final Matrix mTmpMatrix = new Matrix();
    private final Region mTmpRegion = new Region();

    final WindowManagerService mService;

    /** Remove this display when animation on it has completed. */
    private boolean mDeferredRemoval;

    final DockedStackDividerController mDividerControllerLocked;

    final DimLayerController mDimLayerController;

    final ArrayList<WindowState> mTapExcludedWindows = new ArrayList<>();

    /** Used when rebuilding window list to keep track of windows that have been removed. */
    private WindowState[] mRebuildTmp = new WindowState[20];

    private final TaskForResizePointSearchResult mTmpTaskForResizePointSearchResult =
            new TaskForResizePointSearchResult();
    private final GetWindowOnDisplaySearchResult mTmpGetWindowOnDisplaySearchResult =
            new GetWindowOnDisplaySearchResult();

    // True if this display is in the process of being removed. Used to determine if the removal of
    // the display's direct children should be allowed.
    private boolean mRemovingDisplay = false;

    private final WindowLayersController mLayersController;
    int mInputMethodAnimLayerAdjustment;

    /**
     * @param display May not be null.
     * @param service You know.
     * @param layersController window layer controller used to assign layer to the windows on this
     *                         display.
     */
    DisplayContent(Display display, WindowManagerService service,
            WindowLayersController layersController) {
        mDisplay = display;
        mDisplayId = display.getDisplayId();
        mLayersController = layersController;
        display.getDisplayInfo(mDisplayInfo);
        display.getMetrics(mDisplayMetrics);
        isDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;
        mService = service;
        initializeDisplayBaseInfo();
        mDividerControllerLocked = new DockedStackDividerController(service, this);
        mDimLayerController = new DimLayerController(this);

        // These are the only direct children we should ever have and they are permanent.
        super.addChild(mTaskStackContainers, null);
        super.addChild(mNonAppWindowContainers, null);
    }

    int getDisplayId() {
        return mDisplayId;
    }

    WindowList getWindowList() {
        return mWindows;
    }

    WindowToken getWindowToken(IBinder binder) {
        return mTokenMap.get(binder);
    }

    AppWindowToken getAppWindowToken(IBinder binder) {
        final WindowToken token = getWindowToken(binder);
        if (token == null) {
            return null;
        }
        return token.asAppWindowToken();
    }

    void setWindowToken(IBinder binder, WindowToken token) {
        final DisplayContent dc = mService.mRoot.getWindowTokenDisplay(token);
        if (dc != null) {
            // We currently don't support adding a window token to the display if the display
            // already has the binder mapped to another token. If there is a use case for supporting
            // this moving forward we will either need to merge the WindowTokens some how or have
            // the binder map to a list of window tokens.
            throw new IllegalArgumentException("Can't map token=" + token + " to display=" + this
                    + " already mapped to display=" + dc + " tokens=" + dc.mTokenMap);
        }
        mTokenMap.put(binder, token);

        if (token.asAppWindowToken() == null) {
            // Add non-app token to container hierarchy on the display. App tokens are added through
            // the parent container managing them (e.g. Tasks).
            mNonAppWindowContainers.addChild(token, null);
        }
    }

    WindowToken removeWindowToken(IBinder binder) {
        final WindowToken token = mTokenMap.remove(binder);
        if (token != null && token.asAppWindowToken() == null) {
            mNonAppWindowContainers.removeChild(token);
        }
        return token;
    }

    Display getDisplay() {
        return mDisplay;
    }

    DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    DisplayMetrics getDisplayMetrics() {
        return mDisplayMetrics;
    }

    DockedStackDividerController getDockedDividerController() {
        return mDividerControllerLocked;
    }

    /**
     * Returns true if the specified UID has access to this display.
     */
    boolean hasAccess(int uid) {
        return mDisplay.hasAccess(uid);
    }

    boolean isPrivate() {
        return (mDisplay.getFlags() & FLAG_PRIVATE) != 0;
    }

    TaskStack getHomeStack() {
        if (mHomeStack == null && mDisplayId == DEFAULT_DISPLAY) {
            Slog.e(TAG_WM, "getHomeStack: Returning null from this=" + this);
        }
        return mHomeStack;
    }

    TaskStack getStackById(int stackId) {
        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.get(i);
            if (stack.mStackId == stackId) {
                return stack;
            }
        }
        return null;
    }

    @Override
    void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);

        // The display size information is heavily dependent on the resources in the current
        // configuration, so we need to reconfigure it every time the configuration changes.
        // See {@link PhoneWindowManager#setInitialDisplaySize}...sigh...
        mService.reconfigureDisplayLocked(this);

        getDockedDividerController().onConfigurationChanged();
    }

    /**
     * Callback used to trigger bounds update after configuration change and get ids of stacks whose
     * bounds were updated.
     */
    void updateStackBoundsAfterConfigChange(@NonNull List<Integer> changedStackList) {
        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.get(i);
            if (stack.updateBoundsAfterConfigChange()) {
                changedStackList.add(stack.mStackId);
            }
        }
    }

    @Override
    boolean fillsParent() {
        return true;
    }

    @Override
    boolean isVisible() {
        return true;
    }

    @Override
    void onAppTransitionDone() {
        super.onAppTransitionDone();
        rebuildAppWindowList();
    }

    @Override
    int getOrientation() {
        if (mService.isStackVisibleLocked(DOCKED_STACK_ID)
                || mService.isStackVisibleLocked(FREEFORM_WORKSPACE_STACK_ID)) {
            // Apps and their containers are not allowed to specify an orientation while the docked
            // or freeform stack is visible...except for the home stack/task if the docked stack is
            // minimized and it actually set something.
            if (mHomeStack != null && mHomeStack.isVisible()
                    && mDividerControllerLocked.isMinimizedDock()) {
                final int orientation = mHomeStack.getOrientation();
                if (orientation != SCREEN_ORIENTATION_UNSET) {
                    return orientation;
                }
            }
            return SCREEN_ORIENTATION_UNSPECIFIED;
        }

        final int orientation = super.getOrientation();
        if (orientation != SCREEN_ORIENTATION_UNSET && orientation != SCREEN_ORIENTATION_BEHIND) {
            if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                    "App is requesting an orientation, return " + orientation);
            return orientation;
        }

        if (DEBUG_ORIENTATION) Slog.v(TAG_WM,
                "No app is requesting an orientation, return " + mService.mLastOrientation);
        // The next app has not been requested to be visible, so we keep the current orientation
        // to prevent freezing/unfreezing the display too early.
        return mService.mLastOrientation;
    }

    void updateDisplayInfo() {
        mDisplay.getDisplayInfo(mDisplayInfo);
        mDisplay.getMetrics(mDisplayMetrics);
        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            mTaskStackContainers.get(i).updateDisplayInfo(null);
        }
    }

    void initializeDisplayBaseInfo() {
        final DisplayManagerInternal displayManagerInternal = mService.mDisplayManagerInternal;
        if (displayManagerInternal != null) {
            // Bootstrap the default logical display from the display manager.
            final DisplayInfo newDisplayInfo = displayManagerInternal.getDisplayInfo(mDisplayId);
            if (newDisplayInfo != null) {
                mDisplayInfo.copyFrom(newDisplayInfo);
            }
        }

        mBaseDisplayWidth = mInitialDisplayWidth = mDisplayInfo.logicalWidth;
        mBaseDisplayHeight = mInitialDisplayHeight = mDisplayInfo.logicalHeight;
        mBaseDisplayDensity = mInitialDisplayDensity = mDisplayInfo.logicalDensityDpi;
        mBaseDisplayRect.set(0, 0, mBaseDisplayWidth, mBaseDisplayHeight);
    }

    void getLogicalDisplayRect(Rect out) {
        // Uses same calculation as in LogicalDisplay#configureDisplayInTransactionLocked.
        final int orientation = mDisplayInfo.rotation;
        boolean rotated = (orientation == ROTATION_90 || orientation == ROTATION_270);
        final int physWidth = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int physHeight = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        int width = mDisplayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = mDisplayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    private void getLogicalDisplayRect(Rect out, int orientation) {
        getLogicalDisplayRect(out);

        // Rotate the Rect if needed.
        final int currentRotation = mDisplayInfo.rotation;
        final int rotationDelta = deltaRotation(currentRotation, orientation);
        if (rotationDelta == ROTATION_90 || rotationDelta == ROTATION_270) {
            createRotationMatrix(rotationDelta, mBaseDisplayWidth, mBaseDisplayHeight, mTmpMatrix);
            mTmpRectF.set(out);
            mTmpMatrix.mapRect(mTmpRectF);
            mTmpRectF.round(out);
        }
    }

    void getContentRect(Rect out) {
        out.set(mContentRect);
    }

    /** Refer to {@link WindowManagerService#attachStack(int, int, boolean)} */
    void attachStack(TaskStack stack, boolean onTop) {
        mTaskStackContainers.attachStack(stack, onTop);
    }

    void moveStack(TaskStack stack, boolean toTop) {
        mTaskStackContainers.moveStack(stack, toTop);
    }

    @Override
    protected void addChild(DisplayChildWindowContainer child,
            Comparator<DisplayChildWindowContainer> comparator) {
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    @Override
    protected void addChild(DisplayChildWindowContainer child, int index) {
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    @Override
    protected void removeChild(DisplayChildWindowContainer child) {
        // Only allow removal of direct children from this display if the display is in the process
        // of been removed.
        if (mRemovingDisplay) {
            super.removeChild(child);
            return;
        }
        throw new UnsupportedOperationException("See DisplayChildWindowContainer");
    }

    /**
     * Propagate the new bounds to all child stacks.
     * @param contentRect The bounds to apply at the top level.
     */
    void resize(Rect contentRect) {
        mContentRect.set(contentRect);
    }

    int taskIdFromPoint(int x, int y) {
        for (int stackNdx = mTaskStackContainers.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.get(stackNdx);
            final int taskId = stack.taskIdFromPoint(x, y);
            if (taskId != -1) {
                return taskId;
            }
        }
        return -1;
    }

    /**
     * Find the task whose outside touch area (for resizing) (x, y) falls within.
     * Returns null if the touch doesn't fall into a resizing area.
     */
    Task findTaskForResizePoint(int x, int y) {
        final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        mTmpTaskForResizePointSearchResult.reset();
        for (int stackNdx = mTaskStackContainers.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.get(stackNdx);
            if (!StackId.isTaskResizeAllowed(stack.mStackId)) {
                return null;
            }

            stack.findTaskForResizePoint(x, y, delta, mTmpTaskForResizePointSearchResult);
            if (mTmpTaskForResizePointSearchResult.searchDone) {
                return mTmpTaskForResizePointSearchResult.taskForResize;
            }
        }
        return null;
    }

    void setTouchExcludeRegion(Task focusedTask) {
        mTouchExcludeRegion.set(mBaseDisplayRect);
        final int delta = dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        mTmpRect2.setEmpty();
        for (int stackNdx = mTaskStackContainers.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.get(stackNdx);
            stack.setTouchExcludeRegion(
                    focusedTask, delta, mTouchExcludeRegion, mContentRect, mTmpRect2);
        }
        // If we removed the focused task above, add it back and only leave its
        // outside touch area in the exclusion. TapDectector is not interested in
        // any touch inside the focused task itself.
        if (!mTmpRect2.isEmpty()) {
            mTouchExcludeRegion.op(mTmpRect2, Region.Op.UNION);
        }
        final WindowState inputMethod = mService.mInputMethodWindow;
        if (inputMethod != null && inputMethod.isVisibleLw()) {
            // If the input method is visible and the user is typing, we don't want these touch
            // events to be intercepted and used to change focus. This would likely cause a
            // disappearance of the input method.
            inputMethod.getTouchableRegion(mTmpRegion);
            mTouchExcludeRegion.op(mTmpRegion, Region.Op.UNION);
        }
        for (int i = mTapExcludedWindows.size() - 1; i >= 0; i--) {
            WindowState win = mTapExcludedWindows.get(i);
            win.getTouchableRegion(mTmpRegion);
            mTouchExcludeRegion.op(mTmpRegion, Region.Op.UNION);
        }
        if (getDockedStackVisibleForUserLocked() != null) {
            mDividerControllerLocked.getTouchRegion(mTmpRect);
            mTmpRegion.set(mTmpRect);
            mTouchExcludeRegion.op(mTmpRegion, Op.UNION);
        }
        if (mTapDetector != null) {
            mTapDetector.setTouchExcludeRegion(mTouchExcludeRegion);
        }
    }

    void switchUser() {
        final WindowList windows = getWindowList();
        for (int i = 0; i < windows.size(); i++) {
            final WindowState win = windows.get(i);
            if (win.isHiddenFromUserLocked()) {
                if (DEBUG_VISIBILITY) Slog.w(TAG_WM, "user changing, hiding " + win
                        + ", attrs=" + win.mAttrs.type + ", belonging to " + win.mOwnerUid);
                win.hideLw(false);
            }
        }

        for (int stackNdx = mTaskStackContainers.size() - 1; stackNdx >= 0; --stackNdx) {
            mTaskStackContainers.get(stackNdx).switchUser();
        }

        rebuildAppWindowList();
    }

    void resetAnimationBackgroundAnimator() {
        for (int stackNdx = mTaskStackContainers.size() - 1; stackNdx >= 0; --stackNdx) {
            mTaskStackContainers.get(stackNdx).resetAnimationBackgroundAnimator();
        }
    }

    boolean animateDimLayers() {
        return mDimLayerController.animateDimLayers();
    }

    void resetDimming() {
        mDimLayerController.resetDimming();
    }

    boolean isDimming() {
        return mDimLayerController.isDimming();
    }

    void stopDimmingIfNeeded() {
        mDimLayerController.stopDimmingIfNeeded();
    }

    @Override
    void removeIfPossible() {
        if (isAnimating()) {
            mDeferredRemoval = true;
            return;
        }
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        mRemovingDisplay = true;
        try {
            super.removeImmediately();
            if (DEBUG_DISPLAY) Slog.v(TAG_WM, "Removing display=" + this);
            mDimLayerController.close();
            if (mDisplayId == DEFAULT_DISPLAY) {
                mService.unregisterPointerEventListener(mTapDetector);
                mService.unregisterPointerEventListener(mService.mMousePositionTracker);
            }
        } finally {
            mRemovingDisplay = false;
        }
    }

    /** Returns true if a removal action is still being deferred. */
    @Override
    boolean checkCompleteDeferredRemoval() {
        final boolean stillDeferringRemoval = super.checkCompleteDeferredRemoval();

        if (!stillDeferringRemoval && mDeferredRemoval) {
            removeImmediately();
            mService.onDisplayRemoved(mDisplayId);
            return false;
        }
        return true;
    }

    boolean animateForIme(float interpolatedValue, float animationTarget,
            float dividerAnimationTarget) {
        boolean updated = false;

        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.get(i);
            if (stack == null || !stack.isAdjustedForIme()) {
                continue;
            }

            if (interpolatedValue >= 1f && animationTarget == 0f && dividerAnimationTarget == 0f) {
                stack.resetAdjustedForIme(true /* adjustBoundsNow */);
                updated = true;
            } else {
                mDividerControllerLocked.mLastAnimationProgress =
                        mDividerControllerLocked.getInterpolatedAnimationValue(interpolatedValue);
                mDividerControllerLocked.mLastDividerProgress =
                        mDividerControllerLocked.getInterpolatedDividerValue(interpolatedValue);
                updated |= stack.updateAdjustForIme(
                        mDividerControllerLocked.mLastAnimationProgress,
                        mDividerControllerLocked.mLastDividerProgress,
                        false /* force */);
            }
            if (interpolatedValue >= 1f) {
                stack.endImeAdjustAnimation();
            }
        }

        return updated;
    }

    boolean clearImeAdjustAnimation() {
        boolean changed = false;
        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.get(i);
            if (stack != null && stack.isAdjustedForIme()) {
                stack.resetAdjustedForIme(true /* adjustBoundsNow */);
                changed  = true;
            }
        }
        return changed;
    }

    void beginImeAdjustAnimation() {
        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.get(i);
            if (stack.isVisible() && stack.isAdjustedForIme()) {
                stack.beginImeAdjustAnimation();
            }
        }
    }

    void adjustForImeIfNeeded() {
        final WindowState imeWin = mService.mInputMethodWindow;
        final boolean imeVisible = imeWin != null && imeWin.isVisibleLw() && imeWin.isDisplayedLw()
                && !mDividerControllerLocked.isImeHideRequested();
        final boolean dockVisible = mService.isStackVisibleLocked(DOCKED_STACK_ID);
        final TaskStack imeTargetStack = mService.getImeFocusStackLocked();
        final int imeDockSide = (dockVisible && imeTargetStack != null) ?
                imeTargetStack.getDockSide() : DOCKED_INVALID;
        final boolean imeOnTop = (imeDockSide == DOCKED_TOP);
        final boolean imeOnBottom = (imeDockSide == DOCKED_BOTTOM);
        final boolean dockMinimized = mDividerControllerLocked.isMinimizedDock();
        final int imeHeight = mService.mPolicy.getInputMethodWindowVisibleHeightLw();
        final boolean imeHeightChanged = imeVisible &&
                imeHeight != mDividerControllerLocked.getImeHeightAdjustedFor();

        // The divider could be adjusted for IME position, or be thinner than usual,
        // or both. There are three possible cases:
        // - If IME is visible, and focus is on top, divider is not moved for IME but thinner.
        // - If IME is visible, and focus is on bottom, divider is moved for IME and thinner.
        // - If IME is not visible, divider is not moved and is normal width.

        if (imeVisible && dockVisible && (imeOnTop || imeOnBottom) && !dockMinimized) {
            for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
                final TaskStack stack = mTaskStackContainers.get(i);
                final boolean isDockedOnBottom = stack.getDockSide() == DOCKED_BOTTOM;
                if (stack.isVisible() && (imeOnBottom || isDockedOnBottom)) {
                    stack.setAdjustedForIme(imeWin, imeOnBottom && imeHeightChanged);
                } else {
                    stack.resetAdjustedForIme(false);
                }
            }
            mDividerControllerLocked.setAdjustedForIme(
                    imeOnBottom /*ime*/, true /*divider*/, true /*animate*/, imeWin, imeHeight);
        } else {
            for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
                final TaskStack stack = mTaskStackContainers.get(i);
                stack.resetAdjustedForIme(!dockVisible);
            }
            mDividerControllerLocked.setAdjustedForIme(
                    false /*ime*/, false /*divider*/, dockVisible /*animate*/, imeWin, imeHeight);
        }
    }

    void setInputMethodAnimLayerAdjustment(int adj) {
        if (DEBUG_LAYERS) Slog.v(TAG_WM, "Setting im layer adj to " + adj);
        mInputMethodAnimLayerAdjustment = adj;
        final WindowState imw = mService.mInputMethodWindow;
        if (imw != null) {
            imw.adjustAnimLayer(adj);
        }
        for (int i = mService.mInputMethodDialogs.size() - 1; i >= 0; i--) {
            final WindowState dialog = mService.mInputMethodDialogs.get(i);
            // TODO: This and other places setting mAnimLayer can probably use WS.adjustAnimLayer,
            // but need to make sure we are not setting things twice for child windows that are
            // already in the list.
            dialog.mWinAnimator.mAnimLayer = dialog.mLayer + adj;
            if (DEBUG_LAYERS) Slog.v(TAG_WM, "IM win " + imw
                    + " anim layer: " + dialog.mWinAnimator.mAnimLayer);
        }
    }

    void prepareFreezingTaskBounds() {
        for (int stackNdx = mTaskStackContainers.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.get(stackNdx);
            stack.prepareFreezingTaskBounds();
        }
    }

    void rotateBounds(int oldRotation, int newRotation, Rect bounds) {
        getLogicalDisplayRect(mTmpRect, newRotation);

        // Compute a transform matrix to undo the coordinate space transformation,
        // and present the window at the same physical position it previously occupied.
        final int deltaRotation = deltaRotation(newRotation, oldRotation);
        createRotationMatrix(deltaRotation, mTmpRect.width(), mTmpRect.height(), mTmpMatrix);

        mTmpRectF.set(bounds);
        mTmpMatrix.mapRect(mTmpRectF);
        mTmpRectF.round(bounds);
    }

    static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    private static void createRotationMatrix(int rotation, float displayWidth, float displayHeight,
            Matrix outMatrix) {
        // For rotations without Z-ordering we don't need the target rectangle's position.
        createRotationMatrix(rotation, 0 /* rectLeft */, 0 /* rectTop */, displayWidth,
                displayHeight, outMatrix);
    }

    static void createRotationMatrix(int rotation, float rectLeft, float rectTop,
            float displayWidth, float displayHeight, Matrix outMatrix) {
        switch (rotation) {
            case ROTATION_0:
                outMatrix.reset();
                break;
            case ROTATION_270:
                outMatrix.setRotate(270, 0, 0);
                outMatrix.postTranslate(0, displayHeight);
                outMatrix.postTranslate(rectTop, 0);
                break;
            case ROTATION_180:
                outMatrix.reset();
                break;
            case ROTATION_90:
                outMatrix.setRotate(90, 0, 0);
                outMatrix.postTranslate(displayWidth, 0);
                outMatrix.postTranslate(-rectTop, rectLeft);
                break;
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("Display: mDisplayId="); pw.println(mDisplayId);
        final String subPrefix = "  " + prefix;
        pw.print(subPrefix); pw.print("init="); pw.print(mInitialDisplayWidth); pw.print("x");
            pw.print(mInitialDisplayHeight); pw.print(" "); pw.print(mInitialDisplayDensity);
            pw.print("dpi");
            if (mInitialDisplayWidth != mBaseDisplayWidth
                    || mInitialDisplayHeight != mBaseDisplayHeight
                    || mInitialDisplayDensity != mBaseDisplayDensity) {
                pw.print(" base=");
                pw.print(mBaseDisplayWidth); pw.print("x"); pw.print(mBaseDisplayHeight);
                pw.print(" "); pw.print(mBaseDisplayDensity); pw.print("dpi");
            }
            if (mDisplayScalingDisabled) {
                pw.println(" noscale");
            }
            pw.print(" cur=");
            pw.print(mDisplayInfo.logicalWidth);
            pw.print("x"); pw.print(mDisplayInfo.logicalHeight);
            pw.print(" app=");
            pw.print(mDisplayInfo.appWidth);
            pw.print("x"); pw.print(mDisplayInfo.appHeight);
            pw.print(" rng="); pw.print(mDisplayInfo.smallestNominalAppWidth);
            pw.print("x"); pw.print(mDisplayInfo.smallestNominalAppHeight);
            pw.print("-"); pw.print(mDisplayInfo.largestNominalAppWidth);
            pw.print("x"); pw.println(mDisplayInfo.largestNominalAppHeight);
            pw.println(subPrefix + "deferred=" + mDeferredRemoval
                    + " mLayoutNeeded=" + mLayoutNeeded);

        pw.println();
        pw.println("  Application tokens in top down Z order:");
        for (int stackNdx = mTaskStackContainers.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mTaskStackContainers.get(stackNdx);
            stack.dump(prefix + "  ", pw);
        }

        pw.println();
        if (!mExitingTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i = mExitingTokens.size() - 1; i >= 0; i--) {
                final WindowToken token = mExitingTokens.get(i);
                pw.print("  Exiting #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ");
            }
        }
        pw.println();
        mDimLayerController.dump(prefix + "  ", pw);
        pw.println();
        mDividerControllerLocked.dump(prefix + "  ", pw);

        if (mInputMethodAnimLayerAdjustment != 0) {
            pw.println(subPrefix
                    + "mInputMethodAnimLayerAdjustment=" + mInputMethodAnimLayerAdjustment);
        }
    }

    @Override
    public String toString() {
        return "Display " + mDisplayId + " info=" + mDisplayInfo + " stacks=" + mChildren;
    }

    String getName() {
        return "Display " + mDisplayId + " name=\"" + mDisplayInfo.name + "\"";
    }

    /**
     * @return The docked stack, but only if it is visible, and {@code null} otherwise.
     */
    TaskStack getDockedStackLocked() {
        final TaskStack stack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        return (stack != null && stack.isVisible()) ? stack : null;
    }

    /**
     * Like {@link #getDockedStackLocked}, but also returns the docked stack if it's currently not
     * visible, as long as it's not hidden because the current user doesn't have any tasks there.
     */
    TaskStack getDockedStackVisibleForUserLocked() {
        final TaskStack stack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        return (stack != null && stack.isVisible(true /* ignoreKeyguard */)) ? stack : null;
    }

    /** Find the visible, touch-deliverable window under the given point */
    WindowState getTouchableWinAtPointLocked(float xf, float yf) {
        WindowState touchedWin = null;
        final int x = (int) xf;
        final int y = (int) yf;

        for (int i = mWindows.size() - 1; i >= 0; i--) {
            WindowState window = mWindows.get(i);
            final int flags = window.mAttrs.flags;
            if (!window.isVisibleLw()) {
                continue;
            }
            if ((flags & FLAG_NOT_TOUCHABLE) != 0) {
                continue;
            }

            window.getVisibleBounds(mTmpRect);
            if (!mTmpRect.contains(x, y)) {
                continue;
            }

            window.getTouchableRegion(mTmpRegion);

            final int touchFlags = flags & (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL);
            if (mTmpRegion.contains(x, y) || touchFlags == 0) {
                touchedWin = window;
                break;
            }
        }

        return touchedWin;
    }

    boolean canAddToastWindowForUid(int uid) {
        // We allow one toast window per UID being shown at a time.
        WindowList windows = getWindowList();
        final int windowCount = windows.size();
        for (int i = 0; i < windowCount; i++) {
            WindowState window = windows.get(i);
            if (window.mAttrs.type == TYPE_TOAST && window.mOwnerUid == uid
                    && !window.mPermanentlyHidden && !window.mAnimatingExit
                    && !window.mRemoveOnExit) {
                return false;
            }
        }
        return true;
    }

    void scheduleToastWindowsTimeoutIfNeededLocked(WindowState oldFocus, WindowState newFocus) {
        if (oldFocus == null || (newFocus != null && newFocus.mOwnerUid == oldFocus.mOwnerUid)) {
            return;
        }
        final int lostFocusUid = oldFocus.mOwnerUid;
        final WindowList windows = getWindowList();
        final int windowCount = windows.size();
        final Handler handler = mService.mH;
        for (int i = 0; i < windowCount; i++) {
            final WindowState window = windows.get(i);
            if (window.mAttrs.type == TYPE_TOAST && window.mOwnerUid == lostFocusUid) {
                if (!handler.hasMessages(WINDOW_HIDE_TIMEOUT, window)) {
                    handler.sendMessageDelayed(handler.obtainMessage(WINDOW_HIDE_TIMEOUT, window),
                            window.mAttrs.hideTimeoutMilliseconds);
                }
            }
        }
    }

    WindowState findFocusedWindow() {
        final AppWindowToken focusedApp = mService.mFocusedApp;

        for (int i = mWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mWindows.get(i);

            if (DEBUG_FOCUS) Slog.v(TAG_WM, "Looking for focus: " + i + " = " + win
                    + ", flags=" + win.mAttrs.flags + ", canReceive=" + win.canReceiveKeys());

            if (!win.canReceiveKeys()) {
                continue;
            }

            final AppWindowToken wtoken = win.mAppToken;

            // If this window's application has been removed, just skip it.
            if (wtoken != null && (wtoken.removed || wtoken.sendingToBottom)) {
                if (DEBUG_FOCUS) Slog.v(TAG_WM, "Skipping " + wtoken + " because "
                        + (wtoken.removed ? "removed" : "sendingToBottom"));
                continue;
            }

            if (focusedApp == null) {
                if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: focusedApp=null"
                        + " using new focus @ " + i + " = " + win);
                return win;
            }

            if (!focusedApp.windowsAreFocusable()) {
                // Current focused app windows aren't focusable...
                if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: focusedApp windows not"
                        + " focusable using new focus @ " + i + " = " + win);
                return win;
            }

            // Descend through all of the app tokens and find the first that either matches
            // win.mAppToken (return win) or mFocusedApp (return null).
            if (wtoken != null && win.mAttrs.type != TYPE_APPLICATION_STARTING) {
                if (focusedApp.compareTo(wtoken) > 0) {
                    // App stack below focused app stack. No focus for you!!!
                    if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM,
                            "findFocusedWindow: Reached focused app=" + focusedApp);
                    return null;
                }
            }

            if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: Found new focus @ "
                    + i + " = " + win);
            return win;
        }

        if (DEBUG_FOCUS_LIGHT) Slog.v(TAG_WM, "findFocusedWindow: No focusable windows.");
        return null;
    }

    int addAppWindowToWindowList(final WindowState win) {
        final IWindow client = win.mClient;

        WindowList tokenWindowList = getTokenWindowsOnDisplay(win.mToken);
        if (!tokenWindowList.isEmpty()) {
            return addAppWindowExisting(win, tokenWindowList);
        }

        // No windows from this token on this display
        if (localLOGV) Slog.v(TAG_WM, "Figuring out where to add app window "
                + client.asBinder() + " (token=" + this + ")");

        final WindowToken wToken = win.mToken;

        // Figure out where the window should go, based on the order of applications.
        mTmpGetWindowOnDisplaySearchResult.reset();
        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.get(i);
            stack.getWindowOnDisplayBeforeToken(this, wToken, mTmpGetWindowOnDisplaySearchResult);
            if (mTmpGetWindowOnDisplaySearchResult.reachedToken) {
                // We have reach the token we are interested in. End search.
                break;
            }
        }

        WindowState pos = mTmpGetWindowOnDisplaySearchResult.foundWindow;

        // We now know the index into the apps. If we found an app window above, that gives us the
        // position; else we need to look some more.
        if (pos != null) {
            // Move behind any windows attached to this one.
            final WindowToken atoken = getWindowToken(pos.mClient.asBinder());
            if (atoken != null) {
                tokenWindowList = getTokenWindowsOnDisplay(atoken);
                final int NC = tokenWindowList.size();
                if (NC > 0) {
                    WindowState bottom = tokenWindowList.get(0);
                    if (bottom.mSubLayer < 0) {
                        pos = bottom;
                    }
                }
            }
            addWindowToListBefore(win, pos);
            return 0;
        }

        // Continue looking down until we find the first token that has windows on this display.
        mTmpGetWindowOnDisplaySearchResult.reset();
        for (int i = mTaskStackContainers.size() - 1; i >= 0; --i) {
            final TaskStack stack = mTaskStackContainers.get(i);
            stack.getWindowOnDisplayAfterToken(this, wToken, mTmpGetWindowOnDisplaySearchResult);
            if (mTmpGetWindowOnDisplaySearchResult.foundWindow != null) {
                // We have found a window after the token. End search.
                break;
            }
        }

        pos = mTmpGetWindowOnDisplaySearchResult.foundWindow;

        if (pos != null) {
            // Move in front of any windows attached to this one.
            final WindowToken atoken = getWindowToken(pos.mClient.asBinder());
            if (atoken != null) {
                final WindowState top = atoken.getTopWindow();
                if (top != null && top.mSubLayer >= 0) {
                    pos = top;
                }
            }
            addWindowToListAfter(win, pos);
            return 0;
        }

        // Just search for the start of this layer.
        final int myLayer = win.mBaseLayer;
        int i;
        for (i = mWindows.size() - 1; i >= 0; --i) {
            final WindowState w = mWindows.get(i);
            // Dock divider shares the base layer with application windows, but we want to always
            // keep it above the application windows. The sharing of the base layer is intended
            // for window animations, which need to be above the dock divider for the duration
            // of the animation.
            if (w.mBaseLayer <= myLayer && w.mAttrs.type != TYPE_DOCK_DIVIDER) {
                break;
            }
        }
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Based on layer: Adding window " + win + " at " + (i + 1) + " of "
                + mWindows.size());
        mWindows.add(i + 1, win);
        mService.mWindowsChanged = true;
        return 0;
    }

    /** Adds this non-app window to the window list. */
    void addNonAppWindowToWindowList(WindowState win) {
        // Figure out where window should go, based on layer.
        int i;
        for (i = mWindows.size() - 1; i >= 0; i--) {
            final WindowState otherWin = mWindows.get(i);
            if (otherWin.getBaseType() != TYPE_WALLPAPER && otherWin.mBaseLayer <= win.mBaseLayer) {
                // Wallpaper wanders through the window list, for example to position itself
                // directly behind keyguard. Because of this it will break the ordering based on
                // WindowState.mBaseLayer. There might windows with higher mBaseLayer behind it and
                // we don't want the new window to appear above them. An example of this is adding
                // of the docked stack divider. Consider a scenario with the following ordering (top
                // to bottom): keyguard, wallpaper, assist preview, apps. We want the dock divider
                // to land below the assist preview, so the dock divider must ignore the wallpaper,
                // with which it shares the base layer.
                break;
            }
        }

        i++;
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Free window: Adding window " + this + " at " + i + " of " + mWindows.size());
        mWindows.add(i, win);
        mService.mWindowsChanged = true;
    }

    void addToWindowList(WindowState win, int index) {
        mWindows.add(index, win);
    }

    void addChildWindowToWindowList(WindowState win) {
        final WindowState parentWindow = win.getParentWindow();

        WindowList windowsOnSameDisplay = getTokenWindowsOnDisplay(win.mToken);

        // Figure out this window's ordering relative to the parent window.
        final int wCount = windowsOnSameDisplay.size();
        final int sublayer = win.mSubLayer;
        int largestSublayer = Integer.MIN_VALUE;
        WindowState windowWithLargestSublayer = null;
        int i;
        for (i = 0; i < wCount; i++) {
            WindowState w = windowsOnSameDisplay.get(i);
            final int wSublayer = w.mSubLayer;
            if (wSublayer >= largestSublayer) {
                largestSublayer = wSublayer;
                windowWithLargestSublayer = w;
            }
            if (sublayer < 0) {
                // For negative sublayers, we go below all windows in the same sublayer.
                if (wSublayer >= sublayer) {
                    addWindowToListBefore(win, wSublayer >= 0 ? parentWindow : w);
                    break;
                }
            } else {
                // For positive sublayers, we go above all windows in the same sublayer.
                if (wSublayer > sublayer) {
                    addWindowToListBefore(win, w);
                    break;
                }
            }
        }
        if (i >= wCount) {
            if (sublayer < 0) {
                addWindowToListBefore(win, parentWindow);
            } else {
                addWindowToListAfter(win,
                        largestSublayer >= 0 ? windowWithLargestSublayer : parentWindow);
            }
        }
    }

    /** Updates the layer assignment of windows on this display. */
    void assignWindowLayers(boolean setLayoutNeeded) {
        mLayersController.assignWindowLayers(mWindows);
        if (setLayoutNeeded) {
            setLayoutNeeded();
        }
    }

    /**
     * Z-orders the display window list so that:
     * <ul>
     * <li>Any windows that are currently below the wallpaper window stay below the wallpaper
     *      window.
     * <li>Exiting application windows are at the bottom, but above the wallpaper window.
     * <li>All other application windows are above the exiting application windows and ordered based
     *      on the ordering of their stacks and tasks on the display.
     * <li>Non-application windows are at the very top.
     * </ul>
     * <p>
     * NOTE: This isn't a complete picture of what the user see. Further manipulation of the window
     *       surface layering is done in {@link WindowLayersController}.
     */
    void rebuildAppWindowList() {
        int count = mWindows.size();
        int i;
        int lastBelow = -1;
        int numRemoved = 0;

        if (mRebuildTmp.length < count) {
            mRebuildTmp = new WindowState[count + 10];
        }

        // First remove all existing app windows.
        i = 0;
        while (i < count) {
            final WindowState w = mWindows.get(i);
            if (w.mAppToken != null) {
                final WindowState win = mWindows.remove(i);
                win.mRebuilding = true;
                mRebuildTmp[numRemoved] = win;
                mService.mWindowsChanged = true;
                if (DEBUG_WINDOW_MOVEMENT) Slog.v(TAG_WM, "Rebuild removing window: " + win);
                count--;
                numRemoved++;
                continue;
            } else if (lastBelow == i-1) {
                if (w.mAttrs.type == TYPE_WALLPAPER) {
                    lastBelow = i;
                }
            }
            i++;
        }

        // Keep whatever windows were below the app windows still below, by skipping them.
        lastBelow++;
        i = lastBelow;

        // First add all of the exiting app tokens...  these are no longer in the main app list,
        // but still have windows shown. We put them in the back because now that the animation is
        // over we no longer will care about them.
        final int numStacks = mTaskStackContainers.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            AppTokenList exitingAppTokens = mTaskStackContainers.get(stackNdx).mExitingAppTokens;
            int NT = exitingAppTokens.size();
            for (int j = 0; j < NT; j++) {
                i = exitingAppTokens.get(j).rebuildWindowListUnchecked(i);
            }
        }

        // And add in the still active app tokens in Z order.
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            i = mTaskStackContainers.get(stackNdx).rebuildWindowList(i);
        }

        i -= lastBelow;
        if (i != numRemoved) {
            setLayoutNeeded();
            Slog.w(TAG_WM, "On display=" + mDisplayId + " Rebuild removed " + numRemoved
                    + " windows but added " + i + " rebuildAppWindowListLocked() "
                    + " callers=" + Debug.getCallers(10));
            for (i = 0; i < numRemoved; i++) {
                WindowState ws = mRebuildTmp[i];
                if (ws.mRebuilding) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                    ws.dump(pw, "", true);
                    pw.flush();
                    Slog.w(TAG_WM, "This window was lost: " + ws);
                    Slog.w(TAG_WM, sw.toString());
                    ws.mWinAnimator.destroySurfaceLocked();
                }
            }
            Slog.w(TAG_WM, "Current window hierarchy:");
            dumpChildrenNames();
            Slog.w(TAG_WM, "Final window list:");
            dumpWindows();
        }
        Arrays.fill(mRebuildTmp, null);
    }

    /** Return the list of Windows on this display associated with the input token. */
    WindowList getTokenWindowsOnDisplay(WindowToken token) {
        final WindowList windowList = new WindowList();
        final int count = mWindows.size();
        for (int i = 0; i < count; i++) {
            final WindowState win = mWindows.get(i);
            if (win.mToken == token) {
                windowList.add(win);
            }
        }
        return windowList;
    }

    void setLayoutNeeded() {
        if (DEBUG_LAYOUT) Slog.w(TAG_WM, "setLayoutNeeded: callers=" + Debug.getCallers(3));
        mLayoutNeeded = true;
    }

    void clearLayoutNeeded() {
        if (DEBUG_LAYOUT) Slog.w(TAG_WM, "clearLayoutNeeded: callers=" + Debug.getCallers(3));
        mLayoutNeeded = false;
    }

    boolean isLayoutNeeded() {
        return mLayoutNeeded;
    }

    private int addAppWindowExisting(WindowState win, WindowList tokenWindowList) {

        int tokenWindowsPos;
        // If this application has existing windows, we simply place the new window on top of
        // them... but keep the starting window on top.
        if (win.mAttrs.type == TYPE_BASE_APPLICATION) {
            // Base windows go behind everything else.
            final WindowState lowestWindow = tokenWindowList.get(0);
            addWindowToListBefore(win, lowestWindow);
            tokenWindowsPos = win.mToken.getWindowIndex(lowestWindow);
        } else {
            final AppWindowToken atoken = win.mAppToken;
            final int windowListPos = tokenWindowList.size();
            final WindowState lastWindow = tokenWindowList.get(windowListPos - 1);
            if (atoken != null && lastWindow == atoken.startingWindow) {
                addWindowToListBefore(win, lastWindow);
                tokenWindowsPos = win.mToken.getWindowIndex(lastWindow);
            } else {
                int newIdx = findIdxBasedOnAppTokens(win);
                // There is a window above this one associated with the same apptoken note that the
                // window could be a floating window that was created later or a window at the top
                // of the list of windows associated with this token.
                if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                        "not Base app: Adding window " + win + " at " + (newIdx + 1) + " of "
                                + mWindows.size());
                mWindows.add(newIdx + 1, win);
                if (newIdx < 0) {
                    // No window from token found on win's display.
                    tokenWindowsPos = 0;
                } else {
                    tokenWindowsPos = win.mToken.getWindowIndex(mWindows.get(newIdx)) + 1;
                }
                mService.mWindowsChanged = true;
            }
        }
        return tokenWindowsPos;
    }

    /** Places the first input window after the second input window in the window list. */
    private void addWindowToListAfter(WindowState first, WindowState second) {
        final int i = mWindows.indexOf(second);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Adding window " + this + " at " + (i + 1) + " of " + mWindows.size()
                + " (after " + second + ")");
        mWindows.add(i + 1, first);
        mService.mWindowsChanged = true;
    }

    /** Places the first input window before the second input window in the window list. */
    private void addWindowToListBefore(WindowState first, WindowState second) {
        int i = mWindows.indexOf(second);
        if (DEBUG_FOCUS || DEBUG_WINDOW_MOVEMENT || DEBUG_ADD_REMOVE) Slog.v(TAG_WM,
                "Adding window " + this + " at " + i + " of " + mWindows.size()
                + " (before " + second + ")");
        if (i < 0) {
            Slog.w(TAG_WM, "addWindowToListBefore: Unable to find " + second + " in " + mWindows);
            i = 0;
        }
        mWindows.add(i, first);
        mService.mWindowsChanged = true;
    }

    /**
     * This method finds out the index of a window that has the same app token as win. used for z
     * ordering the windows in mWindows
     */
    private int findIdxBasedOnAppTokens(WindowState win) {
        for(int j = mWindows.size() - 1; j >= 0; j--) {
            final WindowState wentry = mWindows.get(j);
            if(wentry.mAppToken == win.mAppToken) {
                return j;
            }
        }
        return -1;
    }

    private void dumpChildrenNames() {
        StringBuilder output = new StringBuilder();
        dumpChildrenNames(output, " ");
        Slog.v(TAG_WM, output.toString());
    }

    private void dumpWindows() {
        Slog.v(TAG_WM, " Display #" + mDisplayId);
        final WindowList windows = getWindowList();
        for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
            Slog.v(TAG_WM, "  #" + winNdx + ": " + windows.get(winNdx));
        }
    }

    void dumpTokens(PrintWriter pw, boolean dumpAll) {
        if (mTokenMap.isEmpty()) {
            return;
        }
        pw.println("  Display #" + mDisplayId);
        final Iterator<WindowToken> it = mTokenMap.values().iterator();
        while (it.hasNext()) {
            final WindowToken token = it.next();
            pw.print("  ");
            pw.print(token);
            if (dumpAll) {
                pw.println(':');
                token.dump(pw, "    ");
            } else {
                pw.println();
            }
        }
    }

    void enableSurfaceTrace(FileDescriptor fd) {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mWindows.get(i);
            win.mWinAnimator.enableSurfaceTrace(fd);
        }
    }

    void disableSurfaceTrace() {
        for (int i = mWindows.size() - 1; i >= 0; i--) {
            final WindowState win = mWindows.get(i);
            win.mWinAnimator.disableSurfaceTrace();
        }
    }

    static final class GetWindowOnDisplaySearchResult {
        boolean reachedToken;
        WindowState foundWindow;

        void reset() {
            reachedToken = false;
            foundWindow = null;
        }
    }

    static final class TaskForResizePointSearchResult {
        boolean searchDone;
        Task taskForResize;

        void reset() {
            searchDone = false;
            taskForResize = null;
        }
    }

    /**
     * Base class for any direct child window container of {@link #DisplayContent} need to inherit
     * from. This is mainly a pass through class that allows {@link #DisplayContent} to have
     * homogeneous children type which is currently required by sub-classes of
     * {@link WindowContainer} class.
     */
    static class DisplayChildWindowContainer<E extends WindowContainer> extends WindowContainer<E> {

        int size() {
            return mChildren.size();
        }

        E get(int index) {
            return mChildren.get(index);
        }

        @Override
        boolean fillsParent() {
            return true;
        }

        @Override
        boolean isVisible() {
            return true;
        }
    }

    /**
     * Window container class that contains all containers on this display relating to Apps.
     * I.e Activities.
     */
    private class TaskStackContainers extends DisplayChildWindowContainer<TaskStack> {

        void attachStack(TaskStack stack, boolean onTop) {
            if (stack.mStackId == HOME_STACK_ID) {
                if (mHomeStack != null) {
                    throw new IllegalArgumentException("attachStack: HOME_STACK_ID (0) not first.");
                }
                mHomeStack = stack;
            }
            addChild(stack, onTop);
            stack.onDisplayChanged(DisplayContent.this);
        }

        void moveStack(TaskStack stack, boolean toTop) {
            if (StackId.isAlwaysOnTop(stack.mStackId) && !toTop) {
                // This stack is always-on-top silly...
                Slog.w(TAG_WM, "Ignoring move of always-on-top stack=" + stack + " to bottom");
                return;
            }

            if (!mChildren.contains(stack)) {
                Slog.wtf(TAG_WM, "moving stack that was not added: " + stack, new Throwable());
            }
            removeChild(stack);
            addChild(stack, toTop);
        }

        private void addChild(TaskStack stack, boolean toTop) {
            int addIndex = toTop ? mChildren.size() : 0;

            if (toTop
                    && mService.isStackVisibleLocked(PINNED_STACK_ID)
                    && stack.mStackId != PINNED_STACK_ID) {
                // The pinned stack is always the top most stack (always-on-top) when it is visible.
                // So, stack is moved just below the pinned stack.
                addIndex--;
                TaskStack topStack = mChildren.get(addIndex);
                if (topStack.mStackId != PINNED_STACK_ID) {
                    throw new IllegalStateException("Pinned stack isn't top stack??? " + mChildren);
                }
            }
            addChild(stack, addIndex);
            setLayoutNeeded();
        }

    }

    /**
     * Window container class that contains all containers on this display that are not related to
     * Apps. E.g. status bar.
     */
    private static class NonAppWindowContainers extends DisplayChildWindowContainer<WindowToken> {

    }
}
