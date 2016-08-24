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
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowState.RESIZE_HANDLE_WIDTH_IN_DP;

import android.app.ActivityManager.StackId;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import java.io.PrintWriter;
import java.util.ArrayList;

class DisplayContentList extends ArrayList<DisplayContent> {
}

/**
 * Utility class for keeping track of the WindowStates and other pertinent contents of a
 * particular Display.
 *
 * IMPORTANT: No method from this class should ever be used without holding
 * WindowManagerService.mWindowMap.
 */
class DisplayContent {

    /** Unique identifier of this stack. */
    private final int mDisplayId;

    /** Z-ordered (bottom-most first) list of all Window objects. Assigned to an element
     * from mDisplayWindows; */
    private final WindowList mWindows = new WindowList();

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
    Rect mContentRect = new Rect();

    // Accessed directly by all users.
    boolean layoutNeeded;
    int pendingLayoutChanges;
    final boolean isDefaultDisplay;

    /** Window tokens that are in the process of exiting, but still on screen for animations. */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<>();

    /** Array containing all TaskStacks on this display.  Array
     * is stored in display order with the current bottom stack at 0. */
    private final ArrayList<TaskStack> mStacks = new ArrayList<>();

    /** A special TaskStack with id==HOME_STACK_ID that moves to the bottom whenever any TaskStack
     * (except a future lockscreen TaskStack) moves to the top. */
    private TaskStack mHomeStack = null;

    /** Detect user tapping outside of current focused task bounds .*/
    TaskTapPointerEventListener mTapDetector;

    /** Detect user tapping outside of current focused stack bounds .*/
    Region mTouchExcludeRegion = new Region();

    /** Detect user tapping in a non-resizeable task in docked or fullscreen stack .*/
    Region mNonResizeableRegion = new Region();

    /** Save allocating when calculating rects */
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final Region mTmpRegion = new Region();

    /** For gathering Task objects in order. */
    final ArrayList<Task> mTmpTaskHistory = new ArrayList<Task>();

    final WindowManagerService mService;

    /** Remove this display when animation on it has completed. */
    boolean mDeferredRemoval;

    final DockedStackDividerController mDividerControllerLocked;

    final DimLayerController mDimLayerController;

    final ArrayList<WindowState> mTapExcludedWindows = new ArrayList<>();

    /**
     * @param display May not be null.
     * @param service You know.
     */
    DisplayContent(Display display, WindowManagerService service) {
        mDisplay = display;
        mDisplayId = display.getDisplayId();
        display.getDisplayInfo(mDisplayInfo);
        display.getMetrics(mDisplayMetrics);
        isDefaultDisplay = mDisplayId == Display.DEFAULT_DISPLAY;
        mService = service;
        initializeDisplayBaseInfo();
        mDividerControllerLocked = new DockedStackDividerController(service, this);
        mDimLayerController = new DimLayerController(this);
    }

    int getDisplayId() {
        return mDisplayId;
    }

    WindowList getWindowList() {
        return mWindows;
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
    public boolean hasAccess(int uid) {
        return mDisplay.hasAccess(uid);
    }

    public boolean isPrivate() {
        return (mDisplay.getFlags() & Display.FLAG_PRIVATE) != 0;
    }

    ArrayList<TaskStack> getStacks() {
        return mStacks;
    }

    /**
     * Retrieve the tasks on this display in stack order from the bottommost TaskStack up.
     * @return All the Tasks, in order, on this display.
     */
    ArrayList<Task> getTasks() {
        mTmpTaskHistory.clear();
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            mTmpTaskHistory.addAll(mStacks.get(stackNdx).getTasks());
        }
        return mTmpTaskHistory;
    }

    TaskStack getHomeStack() {
        if (mHomeStack == null && mDisplayId == Display.DEFAULT_DISPLAY) {
            Slog.e(TAG_WM, "getHomeStack: Returning null from this=" + this);
        }
        return mHomeStack;
    }

    void updateDisplayInfo() {
        mDisplay.getDisplayInfo(mDisplayInfo);
        mDisplay.getMetrics(mDisplayMetrics);
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            mStacks.get(i).updateDisplayInfo(null);
        }
    }

    void initializeDisplayBaseInfo() {
        // Bootstrap the default logical display from the display manager.
        final DisplayInfo newDisplayInfo =
                mService.mDisplayManagerInternal.getDisplayInfo(mDisplayId);
        if (newDisplayInfo != null) {
            mDisplayInfo.copyFrom(newDisplayInfo);
        }
        mBaseDisplayWidth = mInitialDisplayWidth = mDisplayInfo.logicalWidth;
        mBaseDisplayHeight = mInitialDisplayHeight = mDisplayInfo.logicalHeight;
        mBaseDisplayDensity = mInitialDisplayDensity = mDisplayInfo.logicalDensityDpi;
        mBaseDisplayRect.set(0, 0, mBaseDisplayWidth, mBaseDisplayHeight);
    }

    void getLogicalDisplayRect(Rect out) {
        // Uses same calculation as in LogicalDisplay#configureDisplayInTransactionLocked.
        final int orientation = mDisplayInfo.rotation;
        boolean rotated = (orientation == Surface.ROTATION_90
                || orientation == Surface.ROTATION_270);
        final int physWidth = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int physHeight = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        int width = mDisplayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = mDisplayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    void getContentRect(Rect out) {
        out.set(mContentRect);
    }

    /** Refer to {@link WindowManagerService#attachStack(int, int, boolean)} */
    void attachStack(TaskStack stack, boolean onTop) {
        if (stack.mStackId == HOME_STACK_ID) {
            if (mHomeStack != null) {
                throw new IllegalArgumentException("attachStack: HOME_STACK_ID (0) not first.");
            }
            mHomeStack = stack;
        }
        if (onTop) {
            mStacks.add(stack);
        } else {
            mStacks.add(0, stack);
        }
        layoutNeeded = true;
    }

    void moveStack(TaskStack stack, boolean toTop) {
        if (StackId.isAlwaysOnTop(stack.mStackId) && !toTop) {
            // This stack is always-on-top silly...
            Slog.w(TAG_WM, "Ignoring move of always-on-top stack=" + stack + " to bottom");
            return;
        }

        if (!mStacks.remove(stack)) {
            Slog.wtf(TAG_WM, "moving stack that was not added: " + stack, new Throwable());
        }

        int addIndex = toTop ? mStacks.size() : 0;

        if (toTop
                && mService.isStackVisibleLocked(PINNED_STACK_ID)
                && stack.mStackId != PINNED_STACK_ID) {
            // The pinned stack is always the top most stack (always-on-top) when it is visible.
            // So, stack is moved just below the pinned stack.
            addIndex--;
            TaskStack topStack = mStacks.get(addIndex);
            if (topStack.mStackId != PINNED_STACK_ID) {
                throw new IllegalStateException("Pinned stack isn't top stack??? " + mStacks);
            }
        }
        mStacks.add(addIndex, stack);
    }

    void detachStack(TaskStack stack) {
        mDimLayerController.removeDimLayerUser(stack);
        mStacks.remove(stack);
    }

    /**
     * Propagate the new bounds to all child stacks.
     * @param contentRect The bounds to apply at the top level.
     */
    void resize(Rect contentRect) {
        mContentRect.set(contentRect);
    }

    int taskIdFromPoint(int x, int y) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            TaskStack stack = mStacks.get(stackNdx);
            stack.getBounds(mTmpRect);
            if (!mTmpRect.contains(x, y) || stack.isAdjustedForMinimizedDockedStack()) {
                continue;
            }
            final ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                final WindowState win = task.getTopVisibleAppMainWindow();
                if (win == null) {
                    continue;
                }
                // We need to use the task's dim bounds (which is derived from the visible
                // bounds of its apps windows) for any touch-related tests. Can't use
                // the task's original bounds because it might be adjusted to fit the
                // content frame. For example, the presence of the IME adjusting the
                // windows frames when the app window is the IME target.
                task.getDimBounds(mTmpRect);
                if (mTmpRect.contains(x, y)) {
                    return task.mTaskId;
                }
            }
        }
        return -1;
    }

    /**
     * Find the task whose outside touch area (for resizing) (x, y) falls within.
     * Returns null if the touch doesn't fall into a resizing area.
     */
    Task findTaskForControlPoint(int x, int y) {
        final int delta = mService.dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            TaskStack stack = mStacks.get(stackNdx);
            if (!StackId.isTaskResizeAllowed(stack.mStackId)) {
                break;
            }
            final ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                if (task.isFullscreen()) {
                    return null;
                }

                // We need to use the task's dim bounds (which is derived from the visible
                // bounds of its apps windows) for any touch-related tests. Can't use
                // the task's original bounds because it might be adjusted to fit the
                // content frame. One example is when the task is put to top-left quadrant,
                // the actual visible area would not start at (0,0) after it's adjusted
                // for the status bar.
                task.getDimBounds(mTmpRect);
                mTmpRect.inset(-delta, -delta);
                if (mTmpRect.contains(x, y)) {
                    mTmpRect.inset(delta, delta);
                    if (!mTmpRect.contains(x, y)) {
                        return task;
                    }
                    // User touched inside the task. No need to look further,
                    // focus transfer will be handled in ACTION_UP.
                    return null;
                }
            }
        }
        return null;
    }

    void setTouchExcludeRegion(Task focusedTask) {
        mTouchExcludeRegion.set(mBaseDisplayRect);
        final int delta = mService.dipToPixel(RESIZE_HANDLE_WIDTH_IN_DP, mDisplayMetrics);
        boolean addBackFocusedTask = false;
        mNonResizeableRegion.setEmpty();
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            TaskStack stack = mStacks.get(stackNdx);
            final ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                AppWindowToken token = task.getTopVisibleAppToken();
                if (token == null || !token.isVisible()) {
                    continue;
                }

                /**
                 * Exclusion region is the region that TapDetector doesn't care about.
                 * Here we want to remove all non-focused tasks from the exclusion region.
                 * We also remove the outside touch area for resizing for all freeform
                 * tasks (including the focused).
                 *
                 * We save the focused task region once we find it, and add it back at the end.
                 */

                task.getDimBounds(mTmpRect);

                if (task == focusedTask) {
                    addBackFocusedTask = true;
                    mTmpRect2.set(mTmpRect);
                }

                final boolean isFreeformed = task.inFreeformWorkspace();
                if (task != focusedTask || isFreeformed) {
                    if (isFreeformed) {
                        // If the task is freeformed, enlarge the area to account for outside
                        // touch area for resize.
                        mTmpRect.inset(-delta, -delta);
                        // Intersect with display content rect. If we have system decor (status bar/
                        // navigation bar), we want to exclude that from the tap detection.
                        // Otherwise, if the app is partially placed under some system button (eg.
                        // Recents, Home), pressing that button would cause a full series of
                        // unwanted transfer focus/resume/pause, before we could go home.
                        mTmpRect.intersect(mContentRect);
                    }
                    mTouchExcludeRegion.op(mTmpRect, Region.Op.DIFFERENCE);
                }
                if (task.isTwoFingerScrollMode()) {
                    stack.getBounds(mTmpRect);
                    mNonResizeableRegion.op(mTmpRect, Region.Op.UNION);
                    break;
                }
            }
        }
        // If we removed the focused task above, add it back and only leave its
        // outside touch area in the exclusion. TapDectector is not interested in
        // any touch inside the focused task itself.
        if (addBackFocusedTask) {
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
            mTapDetector.setTouchExcludeRegion(mTouchExcludeRegion, mNonResizeableRegion);
        }
    }

    void switchUserStacks() {
        final WindowList windows = getWindowList();
        for (int i = 0; i < windows.size(); i++) {
            final WindowState win = windows.get(i);
            if (win.isHiddenFromUserLocked()) {
                if (DEBUG_VISIBILITY) Slog.w(TAG_WM, "user changing, hiding " + win
                        + ", attrs=" + win.mAttrs.type + ", belonging to " + win.mOwnerUid);
                win.hideLw(false);
            }
        }

        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).switchUser();
        }
    }

    void resetAnimationBackgroundAnimator() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).resetAnimationBackgroundAnimator();
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

    void close() {
        mDimLayerController.close();
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).close();
        }
    }

    boolean isAnimating() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            if (stack.isAnimating()) {
                return true;
            }
        }
        return false;
    }

    void checkForDeferredActions() {
        boolean animating = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            if (stack.isAnimating()) {
                animating = true;
            } else {
                if (stack.mDeferDetach) {
                    mService.detachStackLocked(this, stack);
                }
                final ArrayList<Task> tasks = stack.getTasks();
                for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                    final Task task = tasks.get(taskNdx);
                    AppTokenList tokens = task.mAppTokens;
                    for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                        AppWindowToken wtoken = tokens.get(tokenNdx);
                        if (wtoken.mIsExiting) {
                            wtoken.removeAppFromTaskLocked();
                        }
                    }
                }
            }
        }
        if (!animating && mDeferredRemoval) {
            mService.onDisplayRemoved(mDisplayId);
        }
    }

    void rotateBounds(int oldRotation, int newRotation, Rect bounds) {
        final int rotationDelta = DisplayContent.deltaRotation(oldRotation, newRotation);
        getLogicalDisplayRect(mTmpRect);
        switch (rotationDelta) {
            case Surface.ROTATION_0:
                mTmpRect2.set(bounds);
                break;
            case Surface.ROTATION_90:
                mTmpRect2.top = mTmpRect.bottom - bounds.right;
                mTmpRect2.left = bounds.top;
                mTmpRect2.right = mTmpRect2.left + bounds.height();
                mTmpRect2.bottom = mTmpRect2.top + bounds.width();
                break;
            case Surface.ROTATION_180:
                mTmpRect2.top = mTmpRect.bottom - bounds.bottom;
                mTmpRect2.left = mTmpRect.right - bounds.right;
                mTmpRect2.right = mTmpRect2.left + bounds.width();
                mTmpRect2.bottom = mTmpRect2.top + bounds.height();
                break;
            case Surface.ROTATION_270:
                mTmpRect2.top = bounds.left;
                mTmpRect2.left = mTmpRect.right - bounds.bottom;
                mTmpRect2.right = mTmpRect2.left + bounds.height();
                mTmpRect2.bottom = mTmpRect2.top + bounds.width();
                break;
        }
        bounds.set(mTmpRect2);
    }

    static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
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
            pw.print(subPrefix); pw.print("deferred="); pw.print(mDeferredRemoval);
                pw.print(" layoutNeeded="); pw.println(layoutNeeded);

        pw.println();
        pw.println("  Application tokens in top down Z order:");
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            stack.dump(prefix + "  ", pw);
        }

        pw.println();
        if (!mExitingTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i = mExitingTokens.size() - 1; i >= 0; i--) {
                WindowToken token = mExitingTokens.get(i);
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
    }

    @Override
    public String toString() {
        return "Display " + mDisplayId + " info=" + mDisplayInfo + " stacks=" + mStacks;
    }

    /**
     * @return The docked stack, but only if it is visible, and {@code null} otherwise.
     */
    TaskStack getDockedStackLocked() {
        final TaskStack stack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        return (stack != null && stack.isVisibleLocked()) ? stack : null;
    }

    /**
     * Like {@link #getDockedStackLocked}, but also returns the docked stack if it's currently not
     * visible, as long as it's not hidden because the current user doesn't have any tasks there.
     */
    TaskStack getDockedStackVisibleForUserLocked() {
        final TaskStack stack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        return (stack != null && stack.isVisibleForUserLocked()) ? stack : null;
    }

    /**
     * Find the visible, touch-deliverable window under the given point
     */
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
}
