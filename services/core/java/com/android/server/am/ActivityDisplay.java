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
import static com.android.server.am.proto.ActivityDisplayProto.CONFIGURATION_CONTAINER;
import static com.android.server.am.proto.ActivityDisplayProto.STACKS;
import static com.android.server.am.proto.ActivityDisplayProto.ID;

import android.app.ActivityManagerInternal;
import android.app.WindowConfiguration;
import android.util.IntArray;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.ConfigurationContainer;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Exactly one of these classes per Display in the system. Capable of holding zero or more
 * attached {@link ActivityStack}s.
 */
class ActivityDisplay extends ConfigurationContainer<ActivityStack> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityDisplay" : TAG_AM;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;

    static final int POSITION_TOP = Integer.MAX_VALUE;
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;

    private ActivityStackSupervisor mSupervisor;
    /** Actual Display this object tracks. */
    int mDisplayId;
    Display mDisplay;

    /** All of the stacks on this display. Order matters, topmost stack is in front of all other
     * stacks, bottommost behind. Accessed directly by ActivityManager package classes */
    private final ArrayList<ActivityStack> mStacks = new ArrayList<>();

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

    ActivityDisplay(ActivityStackSupervisor supervisor, int displayId) {
        mSupervisor = supervisor;
        mDisplayId = displayId;
        final Display display = supervisor.mDisplayManager.getDisplay(displayId);
        if (display == null) {
            throw new IllegalStateException("Display does not exist displayId=" + displayId);
        }
        mDisplay = display;
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
    }

    void positionChildAtTop(ActivityStack stack) {
        positionChildAt(stack, mStacks.size());
    }

    void positionChildAtBottom(ActivityStack stack) {
        positionChildAt(stack, 0);
    }

    private void positionChildAt(ActivityStack stack, int position) {
        mStacks.remove(stack);
        mStacks.add(getTopInsertPosition(stack, position), stack);
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
        if (!mSupervisor.isWindowingModeSupported(windowingMode, service.mSupportsMultiWindow,
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

        windowingMode = updateWindowingModeForSplitScreenIfNeeded(windowingMode, activityType);

        final int stackId = mSupervisor.getNextStackId();

        final T stack = createStackUnchecked(windowingMode, activityType, stackId, onTop);

        if (mDisplayId == DEFAULT_DISPLAY && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            // Make sure recents stack exist when creating a dock stack as it normally need to be on
            // the other side of the docked stack and we make visibility decisions based on that.
            // TODO: Not sure if this is needed after we change to calculate visibility based on
            // stack z-order vs. id.
            getOrCreateStack(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_RECENTS, onTop);
        }

        return stack;
    }

    @VisibleForTesting
    <T extends ActivityStack> T createStackUnchecked(int windowingMode, int activityType,
            int stackId, boolean onTop) {
        switch (windowingMode) {
            case WINDOWING_MODE_PINNED:
                return (T) new PinnedActivityStack(this, stackId, mSupervisor, onTop);
            default:
                return (T) new ActivityStack(
                        this, stackId, mSupervisor, windowingMode, activityType, onTop);
        }
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
        }
    }

    /** Returns the top visible stack activity type that isn't in the exclude windowing mode. */
    int getTopVisibleStackActivityType(int excludeWindowingMode) {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = mStacks.get(i);
            if (stack.getWindowingMode() == excludeWindowingMode) {
                continue;
            }
            if (stack.shouldBeVisible(null /* starting */)) {
                return stack.getActivityType();
            }
        }
        return ACTIVITY_TYPE_UNDEFINED;
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

    int getIndexOf(ActivityStack stack) {
        return mStacks.indexOf(stack);
    }

    void onLockTaskPackagesUpdated() {
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            mStacks.get(i).onLockTaskPackagesUpdated();
        }
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

    int updateWindowingModeForSplitScreenIfNeeded(int windowingMode, int activityType) {
        final boolean inSplitScreenMode = hasSplitScreenPrimaryStack();
        if (!inSplitScreenMode
                && windowingMode == WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY) {
            // Switch to fullscreen windowing mode if we are not in split-screen mode and we are
            // trying to launch in split-screen secondary.
            return WINDOWING_MODE_FULLSCREEN;
        } else if (inSplitScreenMode && windowingMode == WINDOWING_MODE_FULLSCREEN
                && WindowConfiguration.supportSplitScreenWindowingMode(
                windowingMode, activityType)) {
            return WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        }
        return windowingMode;
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

    /** Update and get all UIDs that are present on the display and have access to it. */
    IntArray getPresentUIDs() {
        mDisplayAccessUIDs.clear();
        for (ActivityStack stack : mStacks) {
            stack.getPresentUIDs(mDisplayAccessUIDs);
        }
        return mDisplayAccessUIDs;
    }

    boolean shouldDestroyContentOnRemove() {
        return mDisplay.getRemoveMode() == REMOVE_MODE_DESTROY_CONTENT;
    }

    boolean shouldSleep() {
        return (mStacks.isEmpty() || !mAllSleepTokens.isEmpty())
                && (mSupervisor.mService.mRunningVoice == null);
    }

    boolean isSleeping() {
        return mSleeping;
    }

    void setIsSleeping(boolean asleep) {
        mSleeping = asleep;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "displayId=" + mDisplayId + " mStacks=" + mStacks);
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
}
