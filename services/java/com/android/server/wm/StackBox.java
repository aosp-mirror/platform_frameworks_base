/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.graphics.Rect;

import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;

import java.io.PrintWriter;
import java.util.ArrayList;

public class StackBox {
    /** For use with {@link WindowManagerService#createStack} */
    public static final int TASK_STACK_GOES_BEFORE = 0;
    public static final int TASK_STACK_GOES_AFTER = 1;
    public static final int TASK_STACK_GOES_ABOVE = 2;
    public static final int TASK_STACK_GOES_BELOW = 3;
    public static final int TASK_STACK_GOES_OVER = 4;
    public static final int TASK_STACK_GOES_UNDER = 5;

    /** The display this box sits in. */
    final DisplayContent mDisplayContent;

    /** Non-null indicates this is mFirst or mSecond of a parent StackBox. Null indicates this
     * is this entire size of mDisplayContent. */
    StackBox mParent;

    /** First child, this is null exactly when mStack is non-null. */
    StackBox mFirst;

    /** Second child, this is null exactly when mStack is non-null. */
    StackBox mSecond;

    /** Stack of Tasks, this is null exactly when mFirst and mSecond are non-null. */
    TaskStack mStack;

    /** Content limits relative to the DisplayContent this sits in. */
    Rect mBounds;

    /** Relative orientation of mFirst and mSecond. */
    boolean mVertical;

    /** Dirty flag. Something inside this or some descendant of this has changed. */
    boolean layoutNeeded;

    /** Used to keep from reallocating a temporary array to hold the list of Tasks below */
    ArrayList<Task> mTmpTasks = new ArrayList<Task>();

    StackBox(DisplayContent displayContent, Rect bounds) {
        mDisplayContent = displayContent;
        mBounds = bounds;
    }

    /** Propagate #layoutNeeded bottom up. */
    void makeDirty() {
        layoutNeeded = true;
        if (mParent != null) {
            mParent.makeDirty();
        }
    }

    /** Propagate #layoutNeeded top down. */
    void makeClean() {
        layoutNeeded = false;
        if (mFirst != null) {
            mFirst.makeClean();
            mSecond.makeClean();
        }
    }

    /**
     * Detremine if a particular TaskStack is in this StackBox or any of its descendants.
     * @param stackId The TaskStack being considered.
     * @return true if the specified TaskStack is in this box or its descendants. False otherwise.
     */
    boolean contains(int stackId) {
        if (mStack != null) {
            return mStack.mStackId == stackId;
        }
        return mFirst.contains(stackId) || mSecond.contains(stackId);
    }

    /**
     * Create a new TaskStack relative to a specified one by splitting the StackBox containing
     * the specified TaskStack into two children. The size and position each of the new StackBoxes
     * is determined by the passed parameters.
     * @param stackId The id of the new TaskStack to create.
     * @param relativeStackId The id of the TaskStack to place the new one next to.
     * @param position One of the static TASK_STACK_GOES_xxx positions defined in this class.
     * @param weight The percentage size of the parent StackBox to devote to the new TaskStack.
     * @return The new TaskStack.
     */
    TaskStack split(int stackId, int relativeStackId, int position, float weight) {
        if (mStack != null) {
            if (mStack.mStackId == relativeStackId) {
                // Found it!
                TaskStack stack = new TaskStack(stackId, this);
                TaskStack firstStack;
                TaskStack secondStack;
                int width, height, split;
                switch (position) {
                    default:
                    case TASK_STACK_GOES_BEFORE:
                    case TASK_STACK_GOES_AFTER:
                        mVertical = false;
                        width = (int)(weight * mBounds.width());
                        height = mBounds.height();
                        if (position == TASK_STACK_GOES_BEFORE) {
                            firstStack = stack;
                            secondStack = mStack;
                            split = mBounds.left + width;
                        } else {
                            firstStack = mStack;
                            secondStack = stack;
                            split = mBounds.right - width;
                        }
                        break;
                    case TASK_STACK_GOES_ABOVE:
                    case TASK_STACK_GOES_BELOW:
                        mVertical = true;
                        width = mBounds.width();
                        height = (int)(weight * mBounds.height());
                        if (position == TASK_STACK_GOES_ABOVE) {
                            firstStack = stack;
                            secondStack = mStack;
                            split = mBounds.top + height;
                        } else {
                            firstStack = mStack;
                            secondStack = stack;
                            split = mBounds.bottom - height;
                        }
                        break;
                }
                mFirst = new StackBox(mDisplayContent, new Rect(mBounds.left, mBounds.top,
                        mVertical ? mBounds.right : split, mVertical ? split : mBounds.bottom));
                mFirst.mStack = firstStack;
                mSecond = new StackBox(mDisplayContent, new Rect(mVertical ? mBounds.left : split,
                        mVertical ? split : mBounds.top, mBounds.right, mBounds.bottom));
                mSecond.mStack = secondStack;
                mStack = null;
                return stack;
            }
            // Not the intended TaskStack.
            return null;
        }

        // Propagate the split to see if the target task stack is in either sub box.
        TaskStack stack = mFirst.split(stackId, relativeStackId, position, weight);
        if (stack != null) {
            return stack;
        }
        return mSecond.split(stackId, relativeStackId, position, weight);
    }

    /**
     * @return List of all Tasks underneath this StackBox. The order is currently mFirst followed
     * by mSecond putting mSecond Tasks more recent than mFirst Tasks.
     * TODO: Change to MRU ordering.
     */
    ArrayList<Task> getTasks() {
        mTmpTasks.clear();
        if (mStack != null) {
            mTmpTasks.addAll(mStack.getTasks());
        } else {
            mTmpTasks.addAll(mFirst.getTasks());
            mTmpTasks.addAll(mSecond.getTasks());
        }
        return mTmpTasks;
    }

    /** Combine a child StackBox into its parent.
     * @param child The surviving child to be merge up into this StackBox. */
    void absorb(StackBox child) {
        mFirst = child.mFirst;
        mSecond = child.mSecond;
        mStack = child.mStack;
        layoutNeeded = true;
    }

    /** Return the stackId of the first mFirst StackBox with a non-null mStack */
    int getStackId() {
        if (mStack != null) {
            return mStack.mStackId;
        }
        return mFirst.getStackId();
    }

    /** Remove this box and propagate its sibling's content up to their parent.
     * @return The first stackId of the resulting StackBox. */
    int removeStack() {
        if (mParent == null) {
            mDisplayContent.removeStackBox(this);
            return HOME_STACK_ID;
        }
        if (mParent.mFirst == this) {
            mParent.absorb(mParent.mSecond);
        } else {
            mParent.absorb(mParent.mFirst);
        }
        mParent.makeDirty();
        return mParent.getStackId();
    }

    /** TODO: */
    boolean resize(int stackId, float weight) {
        return false;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mParent="); pw.println(mParent);
        pw.print(prefix); pw.print("mBounds="); pw.print(mBounds.toShortString());
            pw.print(" mVertical="); pw.print(mVertical);
            pw.print(" layoutNeeded="); pw.println(layoutNeeded);
        if (mFirst != null) {
            pw.print(prefix); pw.print("mFirst="); pw.println(mStack);
            mFirst.dump(prefix + "  ", pw);
            pw.print(prefix); pw.print("mSecond="); pw.println(mStack);
            mSecond.dump(prefix + "  ", pw);
        } else {
            pw.print(prefix); pw.print("mStack="); pw.println(mStack);
            mStack.dump(prefix + "  ", pw);
        }
    }

    @Override
    public String toString() {
        if (mStack != null) {
            return "Box{" + hashCode() + " stack=" + mStack.mStackId + "}";
        }
        return "Box{" + hashCode() + " parent=" + mParent.hashCode()
                + " first=" + mFirst.hashCode() + " second=" + mSecond.hashCode() + "}";
    }
}
