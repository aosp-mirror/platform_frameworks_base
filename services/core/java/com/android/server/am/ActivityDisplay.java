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
import android.util.IntArray;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import com.android.server.wm.ConfigurationContainer;

import java.util.ArrayList;

/**
 * Exactly one of these classes per Display in the system. Capable of holding zero or more
 * attached {@link ActivityStack}s.
 */
class ActivityDisplay extends ConfigurationContainer {
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
    final ArrayList<ActivityStack> mStacks = new ArrayList<>();

    /** Array of all UIDs that are present on the display. */
    private IntArray mDisplayAccessUIDs = new IntArray();

    /** All tokens used to put activities on this stack to sleep (including mOffToken) */
    final ArrayList<ActivityManagerInternal.SleepToken> mAllSleepTokens = new ArrayList<>();
    /** The token acquired by ActivityStackSupervisor to put stacks on the display to sleep */
    ActivityManagerInternal.SleepToken mOffToken;

    private boolean mSleeping;

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
        positionChildAt(stack, position);
        mSupervisor.mService.updateSleepIfNeededLocked();
    }

    void removeChild(ActivityStack stack) {
        if (DEBUG_STACK) Slog.v(TAG_STACK, "removeChild: detaching " + stack
                + " from displayId=" + mDisplayId);
        mStacks.remove(stack);
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

    @Override
    public String toString() {
        return "ActivityDisplay={" + mDisplayId + " numStacks=" + mStacks.size() + "}";
    }

    @Override
    protected int getChildCount() {
        return mStacks.size();
    }

    @Override
    protected ConfigurationContainer getChildAt(int index) {
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

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, CONFIGURATION_CONTAINER);
        proto.write(ID, mDisplayId);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.writeToProto(proto, STACKS);
        }
        proto.end(token);
    }
}
