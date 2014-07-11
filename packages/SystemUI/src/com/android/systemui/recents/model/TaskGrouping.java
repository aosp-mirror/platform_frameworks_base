package com.android.systemui.recents.model;

import java.util.ArrayList;
import java.util.HashMap;

/** Represents a grouping of tasks witihin a stack. */
public class TaskGrouping {

    int affiliation;
    long latestActiveTimeInGroup;

    Task.TaskKey mFrontMostTaskKey;
    ArrayList<Task.TaskKey> mTaskKeys = new ArrayList<Task.TaskKey>();
    HashMap<Task.TaskKey, Integer> mTaskKeyIndices = new HashMap<Task.TaskKey, Integer>();

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

    /** Gets the front task */
    public boolean isFrontMostTask(Task t) {
        return (t.key == mFrontMostTaskKey);
    }

    /** Finds the index of a given task in a group. */
    public int indexOf(Task t) {
        return mTaskKeyIndices.get(t.key);
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

        mFrontMostTaskKey = mTaskKeys.get(mTaskKeys.size() - 1);
        mTaskKeyIndices.clear();
        int taskCount = mTaskKeys.size();
        for (int i = 0; i < taskCount; i++) {
            Task.TaskKey k = mTaskKeys.get(i);
            mTaskKeyIndices.put(k, i);
        }
    }
}
