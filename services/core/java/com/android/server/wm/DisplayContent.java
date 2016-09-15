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
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ADD_REMOVE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowState.RESIZE_HANDLE_WIDTH_IN_DP;

import android.app.ActivityManager.StackId;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.hardware.display.DisplayManagerInternal;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.Surface;
import android.view.animation.Animation;

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

    TaskStack getStackById(int stackId) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final TaskStack stack = mStacks.get(i);
            if (stack.mStackId == stackId) {
                return stack;
            }
        }
        return null;
    }

    int getOrientation() {
        // TODO: Most of the logic here can be removed once this class is converted to use
        // WindowContainer which has an abstract implementation of getOrientation that
        // should cover this.
        if (mService.isStackVisibleLocked(DOCKED_STACK_ID)
                || mService.isStackVisibleLocked(FREEFORM_WORKSPACE_STACK_ID)) {
            // Apps and their containers are not allowed to specify an orientation while the docked
            // or freeform stack is visible...except for the home stack/task if the docked stack is
            // minimized and it actually set something.
            if (mHomeStack.isVisible() && mDividerControllerLocked.isMinimizedDock()) {
                final int orientation = mHomeStack.getOrientation();
                if (orientation != SCREEN_ORIENTATION_UNSET) {
                    return orientation;
                }
            }
            return SCREEN_ORIENTATION_UNSPECIFIED;
        }

        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final TaskStack stack = mStacks.get(i);
            if (!stack.isVisible()) {
                continue;
            }

            final int orientation = stack.getOrientation();

            if (orientation == SCREEN_ORIENTATION_BEHIND) {
                continue;
            }

            if (orientation != SCREEN_ORIENTATION_UNSET) {
                if (stack.fillsParent() || orientation != SCREEN_ORIENTATION_UNSPECIFIED) {
                    return orientation;
                }
            }
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
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            mStacks.get(i).updateDisplayInfo(null);
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
    Task findTaskForResizePoint(int x, int y) {
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
            mTapDetector.setTouchExcludeRegion(mTouchExcludeRegion);
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

    void onCompleteDeferredRemoval() {
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
                            wtoken.removeIfPossible();
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

    /**
     * See {@link WindowManagerService#overridePlayingAppAnimationsLw}.
     */
    void overridePlayingAppAnimationsLw(Animation a) {
        for (int i = mStacks.size() - 1; i >= 0; i--) {
            mStacks.get(i).overridePlayingAppAnimations(a);
        }
    }

    boolean canAddToastWindowForUid(int uid) {
        // We allow one toast window per UID being shown at a time.
        WindowList windows = getWindowList();
        final int windowCount = windows.size();
        for (int i = 0; i < windowCount; i++) {
            WindowState window = windows.get(i);
            if (window.mAttrs.type == TYPE_TOAST && window.mOwnerUid == uid
                    && !window.mPermanentlyHidden && !window.mAnimatingExit) {
                return false;
            }
        }
        return true;
    }

    void scheduleToastWindowsTimeoutIfNeededLocked(WindowState oldFocus,
                                                   WindowState newFocus) {
        if (oldFocus == null || (newFocus != null && newFocus.mOwnerUid == oldFocus.mOwnerUid)) {
            return;
        }
        final int lostFocusUid = oldFocus.mOwnerUid;
        WindowList windows = getWindowList();
        final int windowCount = windows.size();
        for (int i = 0; i < windowCount; i++) {
            WindowState window = windows.get(i);
            if (window.mAttrs.type == TYPE_TOAST && window.mOwnerUid == lostFocusUid) {
                if (!mService.mH.hasMessages(WindowManagerService.H.WINDOW_HIDE_TIMEOUT, window)) {
                    mService.mH.sendMessageDelayed(
                            mService.mH.obtainMessage(
                                    WindowManagerService.H.WINDOW_HIDE_TIMEOUT, window),
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
                final TaskStack focusedAppStack = focusedApp.mTask.mStack;
                final TaskStack appStack = wtoken.mTask.mStack;

                // TODO: Use WindowContainer.compareTo() once everything is using WindowContainer
                if ((focusedAppStack == appStack
                        && appStack.isFirstGreaterThanSecond(focusedApp, wtoken))
                        || mStacks.indexOf(focusedAppStack) > mStacks.indexOf(appStack)) {
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
        if (mService.localLOGV) Slog.v(TAG_WM, "Figuring out where to add app window "
                + client.asBinder() + " (token=" + this + ")");

        final WindowToken wToken = win.mToken;

        // Figure out where the window should go, based on the order of applications.
        final GetWindowOnDisplaySearchResults result = new GetWindowOnDisplaySearchResults();
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final TaskStack stack = mStacks.get(i);
            stack.getWindowOnDisplayBeforeToken(this, wToken, result);
            if (result.reachedToken) {
                // We have reach the token we are interested in. End search.
                break;
            }
        }

        WindowState pos = result.foundWindow;

        // We now know the index into the apps. If we found an app window above, that gives us the
        // position; else we need to look some more.
        if (pos != null) {
            // Move behind any windows attached to this one.
            final WindowToken atoken = mService.mTokenMap.get(pos.mClient.asBinder());
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
        result.reset();
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final TaskStack stack = mStacks.get(i);
            stack.getWindowOnDisplayAfterToken(this, wToken, result);
            if (result.foundWindow != null) {
                // We have found a window after the token. End search.
                break;
            }
        }

        pos = result.foundWindow;

        if (pos != null) {
            // Move in front of any windows attached to this one.
            final WindowToken atoken = mService.mTokenMap.get(pos.mClient.asBinder());
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

    static final class GetWindowOnDisplaySearchResults {
        boolean reachedToken;
        WindowState foundWindow;

        void reset() {
            reachedToken = false;
            foundWindow = null;
        }
    }
}
