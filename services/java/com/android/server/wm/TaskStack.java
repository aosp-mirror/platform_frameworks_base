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

import java.util.ArrayList;

public class TaskStack {
    final int mStackId;
    final DisplayContent mDisplayContent;
    private ArrayList<Task> mTasks = new ArrayList<Task>();
    int mLayer;
    StackBox mParent;

    TaskStack(int stackId, StackBox parent) {
        mStackId = stackId;
        mParent = parent;
        mDisplayContent = mParent.mDisplayContent;
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

    void addTask(Task task, boolean toTop) {
        mParent.makeDirty();
        mTasks.add(toTop ? mTasks.size() : 0, task);
    }

    void moveTaskToTop(Task task) {
        mTasks.remove(task);
        addTask(task, true);
    }

    void moveTaskToBottom(Task task) {
        mTasks.remove(task);
        addTask(task, false);
    }

    boolean removeTask(Task task) {
        mParent.makeDirty();
        if (mTasks.remove(task)) {
            if (mTasks.size() == 0) {
                mParent.removeStack();
            }
            return true;
        }
        return false;
    }

    int numTokens() {
        int count = 0;
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            count += mTasks.get(taskNdx).mAppTokens.size();
        }
        return count;
    }
}
