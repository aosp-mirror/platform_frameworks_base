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

import java.util.ArrayList;

public class StackBox {
    /** For use with {@link WindowManagerService#createStack} */
    public static final int TASK_STACK_GOES_BEFORE = 0;
    public static final int TASK_STACK_GOES_AFTER = 1;
    public static final int TASK_STACK_GOES_ABOVE = 2;
    public static final int TASK_STACK_GOES_BELOW = 3;
    public static final int TASK_STACK_GOES_OVER = 4;
    public static final int TASK_STACK_GOES_UNDER = 5;

    final DisplayContent mDisplayContent;
    StackBox mParent;
    boolean mVertical;
    StackBox mFirst;
    StackBox mSecond;
    float mWeight;
    TaskStack mStack;
    Rect mBounds;
    boolean layoutNeeded;
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
            return null;
        }

        // Propagate the split to see if the target task stack is in either sub box.
        TaskStack stack = mFirst.split(stackId, relativeStackId, position, weight);
        if (stack != null) {
            return stack;
        }
        return mSecond.split(stackId, relativeStackId, position, weight);
    }

    TaskStack merge(int position) {
        TaskStack stack = null;
        if (mFirst != null) {
            switch (position) {
                default:
                case TASK_STACK_GOES_BEFORE:
                case TASK_STACK_GOES_ABOVE:
                    stack = mFirst.merge(position);
                    stack.merge(mSecond.merge(position));
                    break;
                case TASK_STACK_GOES_AFTER:
                case TASK_STACK_GOES_BELOW:
                    stack = mSecond.merge(position);
                    stack.merge(mFirst.merge(position));
                    break;
            }
            return stack;
        }
        return mStack;
    }

    boolean merge(int stackId, int position, StackBox primary, StackBox secondary) {
        TaskStack stack = primary.mStack;
        if (stack != null && stack.mStackId == stackId) {
            stack.merge(secondary.merge(position));
            mStack = stack;
            mFirst = null;
            mSecond = null;
            return true;
        }
        return false;
    }

    boolean merge(int stackId, int position) {
        if (mFirst != null) {
            if (merge(stackId, position, mFirst, mSecond)
                    || merge(stackId, position, mSecond, mFirst)
                    || mFirst.merge(stackId, position)
                    || mSecond.merge(stackId, position)) {
                return true;
            }
        }
        return false;
    }

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

    void absorb(StackBox box) {
        mFirst = box.mFirst;
        mSecond = box.mSecond;
        mStack = box.mStack;
        layoutNeeded = true;
    }

    void removeStack() {
        if (mParent != null) {
            if (mParent.mFirst == this) {
                mParent.absorb(mParent.mSecond);
            } else {
                mParent.absorb(mParent.mFirst);
            }
            mParent.makeDirty();
        }
    }

    boolean addTaskToStack(Task task, int stackId, boolean toTop) {
        if (mStack != null) {
            if (mStack.mStackId == stackId) {
                mStack.addTask(task, toTop);
                return true;
            }
            return false;
        }
        return mFirst.addTaskToStack(task, stackId, toTop)
                || mSecond.addTaskToStack(task, stackId, toTop);
    }

    boolean resize(int stackId, float weight) {
        return false;
    }
}
