/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Display.REMOVE_MODE_DESTROY_CONTENT;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityDisplayProto.CONFIGURATION_CONTAINER;
import static com.android.server.am.ActivityDisplayProto.STACKS;
import static com.android.server.am.ActivityDisplayProto.ID;

import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.util.IntArray;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.ConfigurationContainer;
import com.android.server.wm.DisplayWindowController;

import com.android.server.wm.WindowContainerListener;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Exactly one of these classes per Display in the system. Capable of holding zero or more
 * attached {@link ActivityStack}s.
 */
class ActivityDisplay extends ConfigurationContainer<ActivityStack>
        implements WindowContainerListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityDisplay" : TAG_AM;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;

    static final int POSITION_TOP = Integer.MAX_VALUE;
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;


    /**
     * Counter for next free stack ID to use for dynamic activity stacks. Unique across displays.
     */
    private static int sNextFreeStackId = 0;

    private ActivityStackSupervisor mSupervisor;
    /** Actual Display this object tracks. */
    int mDisplayId;
    Display mDisplay;

    /**
     * All of the stacks on this display. Order matters, topmost stack is in front of all other
     * stacks, bottommost behind. Accessed directly by ActivityManager package classes. Any calls
     * changing the list should also call {@link #onStackOrderChanged()}.
     */
    private final ArrayList<ActivityStack> mStacks = new ArrayList<>();
    private ArrayList<OnStackOrderChangedListener> mStackOrderChangedCallbacks = new ArrayList<>();

    /** Array of all UIDs that are present on the display. */
    private IntArray mDisplayAccessUIDs = new IntArray();

    /** All tokens used to put activities on this stack to sleep (including mOffToken) */
    final ArrayList<ActivityManagerInternal.SleepToken> mAllSleepTokens = new ArrayList<>();
    /** The token acquired by ActivityStackSupervisor to put stacks on the display to sleep */
    ActivityManagerInternal.SleepToken mOffToken;

    private boolean mSleeping;

    // Cached reference to some special stacks we tend to get a lot so we don't need to loop
    // through the list to find them.
    private ActivityStack mHomeStack = null;
    private ActivityStack mRecentsStack = null;
    private ActivityStack mPinnedStack = null;
    private ActivityStack mSplitScreenPrimaryStack = null;

    // Used in updating the display size
    private Point mTmpDisplaySize = new Point();

    private DisplayWindowController mWindowContainerController;

    @VisibleForTesting
    ActivityDisplay(ActivityStackSupervisor supervisor, int displayId) {
        this(supervisor, supervisor.mDisplayManager.getDisplay(displayId));
    }

    ActivityDisplay(ActivityStackSupervisor supervisor, Display display) {
        mSupervisor = supervisor;
        mDisplayId = display.getDisplayId();
        mDisplay = display;
        mWindowContainerController = createWindowContainerController();
        updateBounds();
    }

    protected DisplayWindowController createWindowContainerController() {
        return new DisplayWindowController(mDisplay, this);
    }

    void updateBounds() {
        mDisplay.getSize(mTmpDisplaySize);
        setBounds(0, 0, mTmpDisplaySize.x, mTmpDisplaySize.y);
    }

    void addChild(ActivityStack stack, int position) {
        if (position == POSITION_BOTTOM) {
            position = 0;
        } else if (position == POSITION_TOP) {
            position = mStacks.size();
        }
        if (DEBUG_STACK) Slog.v(TAG_STACK, "addChild: attaching " + stack
                + " to displayId=" + mDisplayId + " position=" + position);
        addStackReferenceIfNeeded(stack);
        positionChildAt(stack, position);
        mSupervisor.mService.updateSleepIfNeededLocked();
    }

    void removeChild(ActivityStack stack) {
        if (DEBUG_STACK) Slog.v(TAG_STACK, "removeChild: detaching " + stack
                + " from displayId=" + mDisplayId);
        mStacks.remove(stack);
        removeStackReferenceIfNeeded(stack);
        mSupervisor.mService.updateSleepIfNeededLocked();
        onStackOrderChanged();
    }

    void positionChildAtTop(ActivityStack stack) {
        positionChildAt(stack, mStacks.size());
    }

    void positionChildAtBottom(ActivityStack stack) {
        positionChildAt(stack, 0);
    }

    private void positionChildAt(ActivityStack stack, int position) {
        // TODO: Keep in sync with WindowContainer.positionChildAt(), once we change that to adjust
        //       the position internally, also update the logic here
        mStacks.remove(stack);
        final int insertPosition = getTopInsertPosition(stack, position);
        mStacks.add(insertPosition, stack);
        mWindowContainerController.positionChildAt(stack.getWindowContainerController(),
                insertPosition);
        onStackOrderChanged();
    }

    private int getTopInsertPosition(ActivityStack stack, int candidatePosition) {
        int position = mStacks.size();
        if (position > 0) {
            final ActivityStack topStack = mStacks.get(position - 1);
            if (topStack.getWindowConfiguration().isAlwaysOnTop() && topStack != stack) {
                // If the top stack is always on top, we move this stack just below it.
                position--;
            }
        }
        return Math.min(position, candidatePosition);
    }

    <T extends ActivityStack> T getStack(int stackId) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            if (stack.mStackId == stackId) {
                return (T) stack;
            }
        }
        return null;
    }

    /**
     * @return the topmost stack on the display that is compatible with the input windowing mode and
     * activity type. {@code null} means no compatible stack on the display.
     * @see ConfigurationContainer#isCompatible(int, int)
     */
    <T extends ActivityStack> T getStack(int windowingMode, int activityType) {
        if (activityType == ACTIVITY_TYPE_HOME) {
            return (T) mHomeStack;
        } else if (activityType == ACTIVITY_TYPE_RECENTS) {
            return (T) mRecentsStack;
        }
        if (windowingMode == WINDOWING_MODE_PINNED) {
            return (T) mPinnedStack;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            return (T) mSplitScreenPrimaryStack;
        }

        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            if (stack.isCompatible(windowingMode, activityType)) {
                return (T) stack;
            }
        }
        return null;
    }

    private boolean alwaysCreateStack(int windowingMode, int activityType) {
        // Always create a stack for fullscreen, freeform, and split-screen-secondary windowing
        // modes so that we can manage visual ordering and return types correctly.
        return activityType == ACTIVITY_TYPE_STANDARD
                && (windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_FREEFORM
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
    }

    /**
     * Returns an existing stack compatible with the windowing mode and activity type or creates one
     * if a compatible stack doesn't exist.
     * @see #getStack(int, int)
     * @see #createStack(int, int, boolean)
     */
    <T extends ActivityStack> T getOrCreateStack(int windowingMode, int activityType,
            boolean onTop) {
        if (!alwaysCreateStack(windowingMode, activityType)) {
            T stack = getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        }
        return createStack(windowingMode, activityType, onTop);
    }

    /**
     * Returns an existing stack compatible with the input params or creates one
     * if a compatible stack doesn't exist.
     * @see #getOrCreateStack(int, int, boolean)
     */
    <T extends ActivityStack> T getOrCreateStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable TaskRecord candidateTask, int activityType,
            boolean onTop) {
        final int windowingMode = resolveWindowingMode(r, options, candidateTask, activityType);
        return getOrCreateStack(windowingMode, activityType, onTop);
    }

    private int getNextStackId() {
        return sNextFreeStackId++;
    }

    /**
     * Creates a stack matching the input windowing mode and activity type on this display.
     * @param windowingMode The windowing mode the stack should be created in. If
     *                      {@link WindowConfiguration#WINDOWING_MODE_UNDEFINED} then the stack will
     *                      be created in {@link WindowConfiguration#WINDOWING_MODE_FULLSCREEN}.
     * @param activityType The activityType the stack should be created in. If
     *                     {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} then the stack will
     *                     be created in {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}.
     * @param onTop If true the stack will be created at the top of the display, else at the bottom.
     * @return The newly created stack.
     */
    <T extends ActivityStack> T createStack(int windowingMode, int activityType, boolean onTop) {

        if (activityType == ACTIVITY_TYPE_UNDEFINED) {
            // Can't have an undefined stack type yet...so re-map to standard. Anyone that wants
            // anything else should be passing it in anyways...
            activityType = ACTIVITY_TYPE_STANDARD;
        }

        if (activityType != ACTIVITY_TYPE_STANDARD) {
            // For now there can be only one stack of a particular non-standard activity type on a
            // display. So, get that ignoring whatever windowing mode it is currently in.
            T stack = getStack(WINDOWING_MODE_UNDEFINED, activityType);
            if (stack != null) {
                throw new IllegalArgumentException("Stack=" + stack + " of activityType="
                        + activityType + " already on display=" + this + ". Can't have multiple.");
            }
        }

        final ActivityManagerService service = mSupervisor.mService;
        if (!isWindowingModeSupported(windowingMode, service.mSupportsMultiWindow,
                service.mSupportsSplitScreenMultiWindow, service.mSupportsFreeformWindowManagement,
                service.mSupportsPictureInPicture, activityType)) {
            throw new IllegalArgumentException("Can't create stack for unsupported windowingMode="
                    + windowingMode);
        }

        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            // TODO: Should be okay to have stacks with with undefined windowing mode long term, but
            // have to set them to something for now due to logic that depending on them.
            windowingMode = getWindowingMode(); // Put in current display's windowing mode
            if (windowingMode == WINDOWING_MODE_UNDEFINED) {
                // Else fullscreen for now...
                windowingMode = WINDOWING_MODE_FULLSCREEN;
            }
        }

        final int stackId = getNextStackId();
        return createStackUnchecked(windowingMode, activityType, stackId, onTop);
    }

    @VisibleForTesting
    <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType,
            int stackId, boolean onTop) {
        if (windowingMode == WINDOWING_MODE_PINNED) {
            return (T) new PinnedActivityStack(this, stackId, mSupervisor, onTop);
        }
        return (T) new ActivityStack(
                        this, stackId, mSupervisor, windowingMode, activityType, onTop);
    }

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(int... windowingModes) {
        if (windowingModes == null || windowingModes.length == 0) {
            return;
        }

        for (int j = windowingModes.length - 1 ; j >= 0; --j) {
            final int windowingMode = windowingModes[j];
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack stack = mStacks.get(i);
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    continue;
                }
                if (stack.getWindowingMode() != windowingMode) {
                    continue;
                }
                mSupervisor.removeStack(stack);
            }
        }
    }

    void removeStacksWithActivityTypes(int... activityTypes) {
        if (activityTypes == null || activityTypes.length == 0) {
            return;
        }

        for (int j = activityTypes.length - 1 ; j >= 0; --j) {
            final int activityType = activityTypes[j];
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack stack = mStacks.get(i);
                if (stack.getActivityType() == activityType) {
                    mSupervisor.removeStack(stack);
                }
            }
        }
    }

    void onStackWindowingModeChanged(ActivityStack stack) {
        removeStackReferenceIfNeeded(stack);
        addStackReferenceIfNeeded(stack);
    }

    private void addStackReferenceIfNeeded(ActivityStack stack) {
        final int activityType = stack.getActivityType();
        final int windowingMode = stack.getWindowingMode();

        if (activityType == ACTIVITY_TYPE_HOME) {
            if (mHomeStack != null && mHomeStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack="
                        + mHomeStack + " already exist on display=" + this + " stack=" + stack);
            }
            mHomeStack = stack;
        } else if (activityType == ACTIVITY_TYPE_RECENTS) {
            if (mRecentsStack != null && mRecentsStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: recents stack="
                        + mRecentsStack + " already exist on display=" + this + " stack=" + stack);
            }
            mRecentsStack = stack;
        }
        if (windowingMode == WINDOWING_MODE_PINNED) {
            if (mPinnedStack != null && mPinnedStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded: pinned stack="
                        + mPinnedStack + " already exist on display=" + this
                        + " stack=" + stack);
            }
            mPinnedStack = stack;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            if (mSplitScreenPrimaryStack != null && mSplitScreenPrimaryStack != stack) {
                throw new IllegalArgumentException("addStackReferenceIfNeeded:"
                        + " split-screen-primary" + " stack=" + mSplitScreenPrimaryStack
                        + " already exist on display=" + this + " stack=" + stack);
            }
            mSplitScreenPrimaryStack = stack;
            onSplitScreenModeActivated();
        }
    }

    private void removeStackReferenceIfNeeded(ActivityStack stack) {
        if (stack == mHomeStack) {
            mHomeStack = null;
        } else if (stack == mRecentsStack) {
            mRecentsStack = null;
        } else if (stack == mPinnedStack) {
            mPinnedStack = null;
        } else if (stack == mSplitScreenPrimaryStack) {
            mSplitScreenPrimaryStack = null;
            // Inform the reset of the system that split-screen mode was dismissed so things like
            // resizing all the other stacks can take place.
            onSplitScreenModeDismissed();
        }
    }

    private void onSplitScreenModeDismissed() {
        mSupervisor.mWindowManager.deferSurfaceLayout();
        try {
            // Adjust the windowing mode of any stack in secondary split-screen to fullscreen.
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack otherStack = mStacks.get(i);
                if (!otherStack.inSplitScreenSecondaryWindowingMode()) {
                    continue;
                }
                otherStack.setWindowingMode(WINDOWING_MODE_FULLSCREEN, false /* animate */,
                        false /* showRecents */, false /* enteringSplitScreenMode */,
                        true /* deferEnsuringVisibility */);
            }
        } finally {
            final ActivityStack topFullscreenStack =
                    getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (topFullscreenStack != null && mHomeStack != null && !isTopStack(mHomeStack)) {
                // Whenever split-screen is dismissed we want the home stack directly behind the
                // current top fullscreen stack so it shows up when the top stack is finished.
                // TODO: Would be better to use ActivityDisplay.positionChildAt() for this, however
                // ActivityDisplay doesn't have a direct controller to WM side yet. We can switch
                // once we have that.
                mHomeStack.moveToFront("onSplitScreenModeDismissed");
                topFullscreenStack.moveToFront("onSplitScreenModeDismissed");
            }
            mSupervisor.mWindowManager.continueSurfaceLayout();
        }
    }

    private void onSplitScreenModeActivated() {
        mSupervisor.mWindowManager.deferSurfaceLayout();
        try {
            // Adjust the windowing mode of any affected by split-screen to split-screen secondary.
            for (int i = mStacks.size() - 1; i >= 0; --i) {
                final ActivityStack otherStack = mStacks.get(i);
                if (otherStack == mSplitScreenPrimaryStack
                        || !otherStack.affectedBySplitScreenResize()) {
                    continue;
                }
                otherStack.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                        false /* animate */, false /* showRecents */,
                        true /* enteringSplitScreenMode */, true /* deferEnsuringVisibility */);
            }
        } finally {
            mSupervisor.mWindowManager.continueSurfaceLayout();
        }
    }

    /**
     * Returns true if the {@param windowingMode} is supported based on other parameters passed in.
     * @param windowingMode The windowing mode we are checking support for.
     * @param supportsMultiWindow If we should consider support for multi-window mode in general.
     * @param supportsSplitScreen If we should consider support for split-screen multi-window.
     * @param supportsFreeform If we should consider support for freeform multi-window.
     * @param supportsPip If we should consider support for picture-in-picture mutli-window.
     * @param activityType The activity type under consideration.
     * @return true if the windowing mode is supported.
     */
    private boolean isWindowingModeSupported(int windowingMode, boolean supportsMultiWindow,
            boolean supportsSplitScreen, boolean supportsFreeform, boolean supportsPip,
            int activityType) {

        if (windowingMode == WINDOWING_MODE_UNDEFINED
                || windowingMode == WINDOWING_MODE_FULLSCREEN) {
            return true;
        }
        if (!supportsMultiWindow) {
            return false;
        }

        if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            return supportsSplitScreen
                    && WindowConfiguration.supportSplitScreenWindowingMode(activityType);
        }

        if (!supportsFreeform && windowingMode == WINDOWING_MODE_FREEFORM) {
            return false;
        }

        if (!supportsPip && windowingMode == WINDOWING_MODE_PINNED) {
            return false;
        }
        return true;
    }

    int resolveWindowingMode(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
            @Nullable TaskRecord task, int activityType) {

        // First preference if the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;

        // If windowing mode is unset, then next preference is the candidate task, then the
        // activity record.
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            if (task != null) {
                windowingMode = task.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED && r != null) {
                windowingMode = r.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED) {
                // Use the display's windowing mode.
                windowingMode = getWindowingMode();
            }
        }

        // Make sure the windowing mode we are trying to use makes sense for what is supported.
        final ActivityManagerService service = mSupervisor.mService;
        boolean supportsMultiWindow = service.mSupportsMultiWindow;
        boolean supportsSplitScreen = service.mSupportsSplitScreenMultiWindow;
        boolean supportsFreeform = service.mSupportsFreeformWindowManagement;
        boolean supportsPip = service.mSupportsPictureInPicture;
        if (supportsMultiWindow) {
            if (task != null) {
                supportsMultiWindow = task.isResizeable();
                supportsSplitScreen = task.supportsSplitScreenWindowingMode();
                // TODO: Do we need to check for freeform and Pip support here?
            } else if (r != null) {
                supportsMultiWindow = r.isResizeable();
                supportsSplitScreen = r.supportsSplitScreenWindowingMode();
                supportsFreeform = r.supportsFreeform();
                supportsPip = r.supportsPictureInPicture();
            }
        }

        final boolean inSplitScreenMode = hasSplitScreenPrimaryStack();
        if (!inSplitScreenMode
                && windowingMode == WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY) {
            // Switch to fullscreen windowing mode if we are not in split-screen mode and we are
            // trying to launch in split-screen secondary.
            windowingMode = WINDOWING_MODE_FULLSCREEN;
        } else if (inSplitScreenMode && windowingMode == WINDOWING_MODE_FULLSCREEN
                && supportsSplitScreen) {
            windowingMode = WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        }

        if (windowingMode != WINDOWING_MODE_UNDEFINED
                && isWindowingModeSupported(windowingMode, supportsMultiWindow, supportsSplitScreen,
                supportsFreeform, supportsPip, activityType)) {
            return windowingMode;
        }
        // Try to use the display's windowing mode otherwise fallback to fullscreen.
        windowingMode = getWindowingMode();
        return windowingMode != WINDOWING_MODE_UNDEFINED
                ? windowingMode : WINDOWING_MODE_FULLSCREEN;
    }

    /**
     * Get the topmost stack on the display. It may be different from focused stack, because
     * focus may be on another display.
     */
    ActivityStack getTopStack() {
        return mStacks.isEmpty() ? null : mStacks.get(mStacks.size() - 1);
    }

    boolean isTopStack(ActivityStack stack) {
        return stack == getTopStack();
    }

    boolean isTopNotPinnedStack(ActivityStack stack) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack current = mStacks.get(i);
            if (!current.inPinnedWindowingMode()) {
                return current == stack;
            }
        }
        return false;
    }

    ActivityStack getTopStackInWindowingMode(int windowingMode) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack current = mStacks.get(i);
            if (windowingMode == current.getWindowingMode()) {
                return current;
            }
        }
        return null;
    }

    int getIndexOf(ActivityStack stack) {
        return mStacks.indexOf(stack);
    }

    void onLockTaskPackagesUpdated() {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            mStacks.get(i).onLockTaskPackagesUpdated();
        }
    }

    /** We are in the process of exiting split-screen mode. */
    void onExitingSplitScreenMode() {
        // Remove reference to the primary-split-screen stack so it no longer has any effect on the
        // display. For example, we want to be able to create fullscreen stack for standard activity
        // types when exiting split-screen mode.
        mSplitScreenPrimaryStack = null;
    }

    ActivityStack getSplitScreenPrimaryStack() {
        return mSplitScreenPrimaryStack;
    }

    boolean hasSplitScreenPrimaryStack() {
        return mSplitScreenPrimaryStack != null;
    }

    PinnedActivityStack getPinnedStack() {
        return (PinnedActivityStack) mPinnedStack;
    }

    boolean hasPinnedStack() {
        return mPinnedStack != null;
    }

    @Override
    public String toString() {
        return "ActivityDisplay={" + mDisplayId + " numStacks=" + mStacks.size() + "}";
    }

    @Override
    protected int getChildCount() {
        return mStacks.size();
    }

    @Override
    protected ActivityStack getChildAt(int index) {
        return mStacks.get(index);
    }

    @Override
    protected ConfigurationContainer getParent() {
        return mSupervisor;
    }

    boolean isPrivate() {
        return (mDisplay.getFlags() & FLAG_PRIVATE) != 0;
    }

    boolean isUidPresent(int uid) {
        for (ActivityStack stack : mStacks) {
            if (stack.isUidPresent(uid)) {
                return true;
            }
        }
        return false;
    }

    void remove() {
        final boolean destroyContentOnRemoval = shouldDestroyContentOnRemove();
        while (getChildCount() > 0) {
            final ActivityStack stack = getChildAt(0);
            if (destroyContentOnRemoval) {
                // Override the stack configuration to make it equal to the current applied one, so
                // that we don't accidentally report configuration change to activities that are
                // going to be finished.
                stack.onOverrideConfigurationChanged(stack.getConfiguration());
                mSupervisor.moveStackToDisplayLocked(stack.mStackId, DEFAULT_DISPLAY,
                        false /* onTop */);
                stack.finishAllActivitiesLocked(true /* immediately */);
            } else {
                // Moving all tasks to fullscreen stack, because it's guaranteed to be
                // a valid launch stack for all activities. This way the task history from
                // external display will be preserved on primary after move.
                mSupervisor.moveTasksToFullscreenStackLocked(stack, true /* onTop */);
            }
        }

        mWindowContainerController.removeContainer();
        mWindowContainerController = null;
    }

    /** Update and get all UIDs that are present on the display and have access to it. */
    IntArray getPresentUIDs() {
        mDisplayAccessUIDs.clear();
        for (ActivityStack stack : mStacks) {
            stack.getPresentUIDs(mDisplayAccessUIDs);
        }
        return mDisplayAccessUIDs;
    }

    private boolean shouldDestroyContentOnRemove() {
        return mDisplay.getRemoveMode() == REMOVE_MODE_DESTROY_CONTENT;
    }

    boolean shouldSleep() {
        return (mStacks.isEmpty() || !mAllSleepTokens.isEmpty())
                && (mSupervisor.mService.mRunningVoice == null);
    }

    /**
     * @return the stack currently above the {@param stack}.  Can be null if the {@param stack} is
     *         already top-most.
     */
    ActivityStack getStackAbove(ActivityStack stack) {
        final int stackIndex = mStacks.indexOf(stack) + 1;
        return (stackIndex < mStacks.size()) ? mStacks.get(stackIndex) : null;
    }

    /**
     * Adjusts the {@param stack} behind the last visible stack in the display if necessary.
     * Generally used in conjunction with {@link #moveStackBehindStack}.
     */
    void moveStackBehindBottomMostVisibleStack(ActivityStack stack) {
        if (stack.shouldBeVisible(null)) {
            // Skip if the stack is already visible
            return;
        }

        // Move the stack to the bottom to not affect the following visibility checks
        positionChildAtBottom(stack);

        // Find the next position where the stack should be placed
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
            final ActivityStack s = mStacks.get(stackNdx);
            if (s == stack) {
                continue;
            }
            final int winMode = s.getWindowingMode();
            final boolean isValidWindowingMode = winMode == WINDOWING_MODE_FULLSCREEN ||
                    winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (s.shouldBeVisible(null) && isValidWindowingMode) {
                // Move the provided stack to behind this stack
                positionChildAt(stack, Math.max(0, stackNdx - 1));
                break;
            }
        }
    }

    /**
     * Moves the {@param stack} behind the given {@param behindStack} if possible. If
     * {@param behindStack} is not currently in the display, then then the stack is moved to the
     * back. Generally used in conjunction with {@link #moveStackBehindBottomMostVisibleStack}.
     */
    void moveStackBehindStack(ActivityStack stack, ActivityStack behindStack) {
        if (behindStack == null || behindStack == stack) {
            return;
        }

        // Note that positionChildAt will first remove the given stack before inserting into the
        // list, so we need to adjust the insertion index to account for the removed index
        // TODO: Remove this logic when WindowContainer.positionChildAt() is updated to adjust the
        //       position internally
        final int stackIndex = mStacks.indexOf(stack);
        final int behindStackIndex = mStacks.indexOf(behindStack);
        final int insertIndex = stackIndex <= behindStackIndex
                ? behindStackIndex - 1 : behindStackIndex;
        positionChildAt(stack, Math.max(0, insertIndex));
    }

    boolean isSleeping() {
        return mSleeping;
    }

    void setIsSleeping(boolean asleep) {
        mSleeping = asleep;
    }

    /**
     * Adds a listener to be notified whenever the stack order in the display changes. Currently
     * only used by the {@link RecentsAnimation} to determine whether to interrupt and cancel the
     * current animation when the system state changes.
     */
    void registerStackOrderChangedListener(OnStackOrderChangedListener listener) {
        if (!mStackOrderChangedCallbacks.contains(listener)) {
            mStackOrderChangedCallbacks.add(listener);
        }
    }

    /**
     * Removes a previously registered stack order change listener.
     */
    void unregisterStackOrderChangedListener(OnStackOrderChangedListener listener) {
        mStackOrderChangedCallbacks.remove(listener);
    }

    private void onStackOrderChanged() {
        for (int i = mStackOrderChangedCallbacks.size() - 1; i >= 0; i--) {
            mStackOrderChangedCallbacks.get(i).onStackOrderChanged();
        }
    }

    /**
     * See {@link DisplayWindowController#deferUpdateImeTarget()}
     */
    public void deferUpdateImeTarget() {
        mWindowContainerController.deferUpdateImeTarget();
    }

    /**
     * See {@link DisplayWindowController#deferUpdateImeTarget()}
     */
    public void continueUpdateImeTarget() {
        mWindowContainerController.continueUpdateImeTarget();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "displayId=" + mDisplayId + " stacks=" + mStacks.size());
        final String myPrefix = prefix + " ";
        if (mHomeStack != null) {
            pw.println(myPrefix + "mHomeStack=" + mHomeStack);
        }
        if (mRecentsStack != null) {
            pw.println(myPrefix + "mRecentsStack=" + mRecentsStack);
        }
        if (mPinnedStack != null) {
            pw.println(myPrefix + "mPinnedStack=" + mPinnedStack);
        }
        if (mSplitScreenPrimaryStack != null) {
            pw.println(myPrefix + "mSplitScreenPrimaryStack=" + mSplitScreenPrimaryStack);
        }
    }

    public void dumpStacks(PrintWriter pw) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            pw.print(mStacks.get(i).mStackId);
            if (i > 0) {
                pw.print(",");
            }
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, CONFIGURATION_CONTAINER, false /* trim */);
        proto.write(ID, mDisplayId);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.writeToProto(proto, STACKS);
        }
        proto.end(token);
    }

    /**
     * Callback for when the order of the stacks in the display changes.
     */
    interface OnStackOrderChangedListener {
        void onStackOrderChanged();
    }
}
