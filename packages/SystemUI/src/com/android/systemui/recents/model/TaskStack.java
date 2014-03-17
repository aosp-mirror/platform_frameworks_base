/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.model;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;


/**
 * An interface for a task filter to query whether a particular task should show in a stack.
 */
interface TaskFilter {
    /** Returns whether the filter accepts the specified task */
    public boolean acceptTask(Task t, int index);
}

/**
 * A list of filtered tasks.
 */
class FilteredTaskList {
    ArrayList<Task> mTasks = new ArrayList<Task>();
    ArrayList<Task> mFilteredTasks = new ArrayList<Task>();
    TaskFilter mFilter;

    /** Sets the task filter, saving the current touch state */
    void setFilter(TaskFilter filter) {
        mFilter = filter;
        updateFilteredTasks();
    }

    /** Removes the task filter and returns the previous touch state */
    void removeFilter() {
        mFilter = null;
        updateFilteredTasks();
    }

    /** Adds a new task to the task list */
    void add(Task t) {
        mTasks.add(t);
        updateFilteredTasks();
    }

    /** Sets the list of tasks */
    void set(List<Task> tasks) {
        mTasks.clear();
        mTasks.addAll(tasks);
        updateFilteredTasks();
    }

    /** Removes a task from the base list only if it is in the filtered list */
    boolean remove(Task t) {
        if (mFilteredTasks.contains(t)) {
            boolean removed = mTasks.remove(t);
            updateFilteredTasks();
            return removed;
        }
        return false;
    }

    /** Returns the index of this task in the list of filtered tasks */
    int indexOf(Task t) {
        return mFilteredTasks.indexOf(t);
    }

    /** Returns the size of the list of filtered tasks */
    int size() {
        return mFilteredTasks.size();
    }

    /** Returns whether the filtered list contains this task */
    boolean contains(Task t) {
        return mFilteredTasks.contains(t);
    }

    /** Updates the list of filtered tasks whenever the base task list changes */
    private void updateFilteredTasks() {
        mFilteredTasks.clear();
        if (mFilter != null) {
            int taskCount = mTasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task t = mTasks.get(i);
                if (mFilter.acceptTask(t, i)) {
                    mFilteredTasks.add(t);
                }
            }
        } else {
            mFilteredTasks.addAll(mTasks);
        }
    }

    /** Returns whether this task list is filtered */
    boolean hasFilter() {
        return (mFilter != null);
    }

    /** Returns the list of filtered tasks */
    ArrayList<Task> getTasks() {
        return mFilteredTasks;
    }
}

/**
 * The task stack contains a list of multiple tasks.
 */
public class TaskStack {
    /* Task stack callbacks */
    public interface TaskStackCallbacks {
        /* Notifies when a task has been added to the stack */
        public void onStackTaskAdded(TaskStack stack, Task t);
        /* Notifies when a task has been removed from the stack */
        public void onStackTaskRemoved(TaskStack stack, Task t);
        /** Notifies when the stack was filtered */
        public void onStackFiltered(TaskStack stack);
        /** Notifies when the stack was un-filtered */
        public void onStackUnfiltered(TaskStack stack);
    }

    Context mContext;

    FilteredTaskList mTaskList = new FilteredTaskList();
    TaskStackCallbacks mCb;

    public TaskStack(Context context) {
        mContext = context;
    }

    /** Sets the callbacks for this task stack */
    public void setCallbacks(TaskStackCallbacks cb) {
        mCb = cb;
    }

    /** Adds a new task */
    public void addTask(Task t) {
        mTaskList.add(t);
        if (mCb != null) {
            mCb.onStackTaskAdded(this, t);
        }
    }

    /** Removes a task */
    public void removeTask(Task t) {
        if (mTaskList.contains(t)) {
            mTaskList.remove(t);
            if (mCb != null) {
                mCb.onStackTaskRemoved(this, t);
            }
        }
    }

    /** Sets a few tasks in one go */
    public void setTasks(List<Task> tasks) {
        int taskCount = mTaskList.getTasks().size();
        for (int i = 0; i < taskCount; i++) {
            Task t = mTaskList.getTasks().get(i);
            if (mCb != null) {
                mCb.onStackTaskRemoved(this, t);
            }
        }
        mTaskList.set(tasks);
        for (Task t : tasks) {
            if (mCb != null) {
                mCb.onStackTaskAdded(this, t);
            }
        }
    }

    /** Gets the tasks */
    public ArrayList<Task> getTasks() {
        return mTaskList.getTasks();
    }

    /** Gets the number of tasks */
    public int getTaskCount() {
        return mTaskList.size();
    }

    /** Returns the index of this task in this current task stack */
    public int indexOfTask(Task t) {
        return mTaskList.indexOf(t);
    }

    /** Tests whether a task is in this current task stack */
    public boolean containsTask(Task t) {
        return mTaskList.contains(t);
    }

    /** Filters the stack into tasks similar to the one specified */
    public void filterTasks(Task t) {
        // Set the task list filter
        // XXX: This is a dummy filter that currently just accepts every other task.
        mTaskList.setFilter(new TaskFilter() {
            @Override
            public boolean acceptTask(Task t, int i) {
                if (i % 2 == 0) {
                    return true;
                }
                return false;
            }
        });
        if (mCb != null) {
            mCb.onStackFiltered(this);
        }
    }

    /** Unfilters the current stack */
    public void unfilterTasks() {
        // Unset the filter, then update the virtual scroll
        mTaskList.removeFilter();
        if (mCb != null) {
            mCb.onStackUnfiltered(this);
        }
    }

    /** Returns whether tasks are currently filtered */
    public boolean hasFilteredTasks() {
        return mTaskList.hasFilter();
    }

    @Override
    public String toString() {
        String str = "Tasks:\n";
        for (Task t : mTaskList.getTasks()) {
            str += "  " + t.toString() + "\n";
        }
        return str;
    }
}