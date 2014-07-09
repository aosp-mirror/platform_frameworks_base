package com.android.systemui.recents.model;

import java.util.ArrayList;
import java.util.HashMap;

/** Represents a grouping of tasks witihin a stack. */
public class TaskGrouping {

    String affiliation;
    long latestActiveTimeInGroup;

    ArrayList<Task.TaskKey> mTasks = new ArrayList<Task.TaskKey>();
    HashMap<Task.TaskKey, Integer> mTaskIndices = new HashMap<Task.TaskKey, Integer>();

    /** Creates a group with a specified affiliation. */
    public TaskGrouping(String affiliation) {
        this.affiliation = affiliation;
    }

    /** Adds a new task to this group. */
    void addTask(Task t) {
        mTasks.add(t.key);
        if (t.key.lastActiveTime > latestActiveTimeInGroup) {
            latestActiveTimeInGroup = t.key.lastActiveTime;
        }
        t.setGroup(this);
        updateTaskIndices();
    }

    /** Removes a task from this group. */
    void removeTask(Task t) {
        mTasks.remove(t.key);
        latestActiveTimeInGroup = 0;
        int taskCount = mTasks.size();
        for (int i = 0; i < taskCount; i++) {
            long lastActiveTime = mTasks.get(i).lastActiveTime;
            if (lastActiveTime > latestActiveTimeInGroup) {
                latestActiveTimeInGroup = lastActiveTime;
            }
        }
        t.setGroup(null);
        updateTaskIndices();
    }

    /** Gets the front task */
    public boolean isFrontMostTask(Task t) {
        return t.key.equals(mTasks.get(mTasks.size() - 1));
    }

    /** Finds the index of a given task in a group. */
    public int indexOf(Task t) {
        return mTaskIndices.get(t.key);
    }

    /** Returns the number of tasks in this group. */
    public int getTaskCount() { return mTasks.size(); }

    /** Updates the mapping of tasks to indices. */
    private void updateTaskIndices() {
        mTaskIndices.clear();
        int taskCount = mTasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task.TaskKey k = mTasks.get(i);
            mTaskIndices.put(k, i);
        }
    }
}
