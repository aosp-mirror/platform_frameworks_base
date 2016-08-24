package com.android.systemui.recents.model;

import android.util.ArrayMap;

import java.util.ArrayList;

/** Represents a grouping of tasks witihin a stack. */
public class TaskGrouping {

    int affiliation;
    long latestActiveTimeInGroup;

    Task.TaskKey mFrontMostTaskKey;
    ArrayList<Task.TaskKey> mTaskKeys = new ArrayList<Task.TaskKey>();
    ArrayMap<Task.TaskKey, Integer> mTaskKeyIndices = new ArrayMap<>();

    /** Creates a group with a specified affiliation. */
    public TaskGrouping(int affiliation) {
        this.affiliation = affiliation;
    }

    /** Adds a new task to this group. */
    void addTask(Task t) {
        mTaskKeys.add(t.key);
        if (t.key.lastActiveTime > latestActiveTimeInGroup) {
            latestActiveTimeInGroup = t.key.lastActiveTime;
        }
        t.setGroup(this);
        updateTaskIndices();
    }

    /** Removes a task from this group. */
    void removeTask(Task t) {
        mTaskKeys.remove(t.key);
        latestActiveTimeInGroup = 0;
        int taskCount = mTaskKeys.size();
        for (int i = 0; i < taskCount; i++) {
            long lastActiveTime = mTaskKeys.get(i).lastActiveTime;
            if (lastActiveTime > latestActiveTimeInGroup) {
                latestActiveTimeInGroup = lastActiveTime;
            }
        }
        t.setGroup(null);
        updateTaskIndices();
    }

    /** Returns the key of the next task in the group. */
    public Task.TaskKey getNextTaskInGroup(Task t) {
        int i = indexOf(t);
        if ((i + 1) < getTaskCount()) {
            return mTaskKeys.get(i + 1);
        }
        return null;
    }

    /** Returns the key of the previous task in the group. */
    public Task.TaskKey getPrevTaskInGroup(Task t) {
        int i = indexOf(t);
        if ((i - 1) >= 0) {
            return mTaskKeys.get(i - 1);
        }
        return null;
    }

    /** Gets the front task */
    public boolean isFrontMostTask(Task t) {
        return (t.key == mFrontMostTaskKey);
    }

    /** Finds the index of a given task in a group. */
    public int indexOf(Task t) {
        return mTaskKeyIndices.get(t.key);
    }

    /** Returns whether a task is in this grouping. */
    public boolean containsTask(Task t) {
        return mTaskKeyIndices.containsKey(t.key);
    }

    /** Returns whether one task is above another in the group.  If they are not in the same group,
     * this returns false. */
    public boolean isTaskAboveTask(Task t, Task below) {
        return mTaskKeyIndices.containsKey(t.key) && mTaskKeyIndices.containsKey(below.key) &&
                mTaskKeyIndices.get(t.key) > mTaskKeyIndices.get(below.key);
    }

    /** Returns the number of tasks in this group. */
    public int getTaskCount() { return mTaskKeys.size(); }

    /** Updates the mapping of tasks to indices. */
    private void updateTaskIndices() {
        if (mTaskKeys.isEmpty()) {
            mFrontMostTaskKey = null;
            mTaskKeyIndices.clear();
            return;
        }

        int taskCount = mTaskKeys.size();
        mFrontMostTaskKey = mTaskKeys.get(mTaskKeys.size() - 1);
        mTaskKeyIndices.clear();
        for (int i = 0; i < taskCount; i++) {
            Task.TaskKey k = mTaskKeys.get(i);
            mTaskKeyIndices.put(k, i);
        }
    }
}
