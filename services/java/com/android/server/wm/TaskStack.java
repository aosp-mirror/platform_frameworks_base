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

import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;

import java.io.PrintWriter;
import java.util.ArrayList;

public class TaskStack {
    /** Unique identifier */
    final int mStackId;

    /** The display this stack sits under. */
    private final DisplayContent mDisplayContent;

    /** The Tasks that define this stack. Oldest Tasks are at the bottom. The ordering must match
     * mTaskHistory in the ActivityStack with the same mStackId */
    private ArrayList<Task> mTasks = new ArrayList<Task>();

    /** The StackBox this sits in. */
    StackBox mStackBox;

    TaskStack(int stackId, DisplayContent displayContent) {
        mStackId = stackId;
        mDisplayContent = displayContent;
    }

    DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    ArrayList<Task> getTasks() {
        return mTasks;
    }

    ArrayList<Task> merge(TaskStack stack) {
        ArrayList<Task> taskLists = stack.mTasks;
        taskLists.addAll(mTasks);
        mTasks = taskLists;
        return taskLists;
    }

    boolean isHomeStack() {
        return mStackId == HOME_STACK_ID;
    }

    /**
     * Put a Task in this stack. Used for adding and moving.
     * @param task The task to add.
     * @param toTop Whether to add it to the top or bottom.
     */
    boolean addTask(Task task, boolean toTop) {
        mStackBox.makeDirty();
        mTasks.add(toTop ? mTasks.size() : 0, task);
        task.mStack = this;
        return mDisplayContent.moveHomeStackBox(mStackId == HOME_STACK_ID);
    }

    boolean moveTaskToTop(Task task) {
        mTasks.remove(task);
        return addTask(task, true);
    }

    boolean moveTaskToBottom(Task task) {
        mTasks.remove(task);
        return addTask(task, false);
    }

    /**
     * Delete a Task from this stack. If it is the last Task in the stack, remove this stack from
     * its parent StackBox and merge the parent.
     * @param task The Task to delete.
     */
    void removeTask(Task task) {
        mStackBox.makeDirty();
        mTasks.remove(task);
    }

    int remove() {
        return mStackBox.remove();
    }

    int numTokens() {
        int count = 0;
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            count += mTasks.get(taskNdx).mAppTokens.size();
        }
        return count;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mStackId="); pw.println(mStackId);
        for (int taskNdx = 0; taskNdx < mTasks.size(); ++taskNdx) {
            pw.print(prefix); pw.println(mTasks.get(taskNdx));
        }
    }

    @Override
    public String toString() {
        return "{stackId=" + mStackId + " tasks=" + mTasks + "}";
    }
}
