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
 * limitations under the License
 */

package com.android.server.am;

import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.TaskRecord.INVALID_TASK_ID;

import com.google.android.collect.Sets;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Set;

/**
 * Class for managing the recent tasks list.
 */
class RecentTasks extends ArrayList<TaskRecord> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RecentTasks" : TAG_AM;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_TASKS = TAG + POSTFIX_TASKS;

    // Maximum number recent bitmaps to keep in memory.
    private static final int MAX_RECENT_BITMAPS = 3;
    private static final int DEFAULT_INITIAL_CAPACITY = 5;

    // Whether or not to move all affiliated tasks to the front when one of the tasks is launched
    private static final boolean MOVE_AFFILIATED_TASKS_TO_FRONT = false;

    /**
     * Save recent tasks information across reboots.
     */
    private final TaskPersister mTaskPersister;
    private final ActivityManagerService mService;
    private final SparseBooleanArray mUsersWithRecentsLoaded = new SparseBooleanArray(
            DEFAULT_INITIAL_CAPACITY);

    /**
     * Stores for each user task ids that are taken by tasks residing in persistent storage. These
     * tasks may or may not currently be in memory.
     */
    final SparseArray<SparseBooleanArray> mPersistedTaskIds = new SparseArray<>(
            DEFAULT_INITIAL_CAPACITY);

    // Mainly to avoid object recreation on multiple calls.
    private final ArrayList<TaskRecord> mTmpRecents = new ArrayList<TaskRecord>();
    private final HashMap<ComponentName, ActivityInfo> mTmpAvailActCache = new HashMap<>();
    private final HashMap<String, ApplicationInfo> mTmpAvailAppCache = new HashMap<>();
    private final ActivityInfo mTmpActivityInfo = new ActivityInfo();
    private final ApplicationInfo mTmpAppInfo = new ApplicationInfo();

    RecentTasks(ActivityManagerService service, ActivityStackSupervisor mStackSupervisor) {
        File systemDir = Environment.getDataSystemDirectory();
        mService = service;
        mTaskPersister = new TaskPersister(systemDir, mStackSupervisor, service, this);
        mStackSupervisor.setRecentTasks(this);
    }

    /**
     * Loads the persistent recentTasks for {@code userId} into this list from persistent storage.
     * Does nothing if they are already loaded.
     *
     * @param userId the user Id
     */
    void loadUserRecentsLocked(int userId) {
        if (!mUsersWithRecentsLoaded.get(userId)) {
            // Load the task ids if not loaded.
            loadPersistedTaskIdsForUserLocked(userId);
            Slog.i(TAG, "Loading recents for user " + userId + " into memory.");
            addAll(mTaskPersister.restoreTasksForUserLocked(userId));
            cleanupLocked(userId);
            mUsersWithRecentsLoaded.put(userId, true);
        }
    }

    private void loadPersistedTaskIdsForUserLocked(int userId) {
        // An empty instead of a null set here means that no persistent taskIds were present
        // on file when we loaded them.
        if (mPersistedTaskIds.get(userId) == null) {
            mPersistedTaskIds.put(userId, mTaskPersister.loadPersistedTaskIdsForUser(userId));
            Slog.i(TAG, "Loaded persisted task ids for user " + userId);
        }
    }

    boolean taskIdTakenForUserLocked(int taskId, int userId) {
        loadPersistedTaskIdsForUserLocked(userId);
        return mPersistedTaskIds.get(userId).get(taskId);
    }

    void notifyTaskPersisterLocked(TaskRecord task, boolean flush) {
        if (task != null && task.stack != null && task.stack.isHomeStack()) {
            // Never persist the home stack.
            return;
        }
        syncPersistentTaskIdsLocked();
        mTaskPersister.wakeup(task, flush);
    }

    private void syncPersistentTaskIdsLocked() {
        for (int i = mPersistedTaskIds.size() - 1; i >= 0; i--) {
            int userId = mPersistedTaskIds.keyAt(i);
            if (mUsersWithRecentsLoaded.get(userId)) {
                // Recents are loaded only after task ids are loaded. Therefore, the set of taskids
                // referenced here should not be null.
                mPersistedTaskIds.valueAt(i).clear();
            }
        }
        for (int i = size() - 1; i >= 0; i--) {
            TaskRecord task = get(i);
            if (task.isPersistable && (task.stack == null || !task.stack.isHomeStack())) {
                // Set of persisted taskIds for task.userId should not be null here
                // TODO Investigate why it can happen. For now initialize with an empty set
                if (mPersistedTaskIds.get(task.userId) == null) {
                    Slog.wtf(TAG, "No task ids found for userId " + task.userId + ". task=" + task
                            + " mPersistedTaskIds=" + mPersistedTaskIds);
                    mPersistedTaskIds.put(task.userId, new SparseBooleanArray());
                }
                mPersistedTaskIds.get(task.userId).put(task.taskId, true);
            }
        }
    }


    void onSystemReadyLocked() {
        clear();
        mTaskPersister.startPersisting();
    }

    Bitmap getTaskDescriptionIcon(String path) {
        return mTaskPersister.getTaskDescriptionIcon(path);
    }

    Bitmap getImageFromWriteQueue(String path) {
        return mTaskPersister.getImageFromWriteQueue(path);
    }

    void saveImage(Bitmap image, String path) {
        mTaskPersister.saveImage(image, path);
    }

    void flush() {
        synchronized (mService) {
            syncPersistentTaskIdsLocked();
        }
        mTaskPersister.flush();
    }

    /**
     * Returns all userIds for which recents from persistent storage are loaded into this list.
     *
     * @return an array of userIds.
     */
    int[] usersWithRecentsLoadedLocked() {
        int[] usersWithRecentsLoaded = new int[mUsersWithRecentsLoaded.size()];
        int len = 0;
        for (int i = 0; i < usersWithRecentsLoaded.length; i++) {
            int userId = mUsersWithRecentsLoaded.keyAt(i);
            if (mUsersWithRecentsLoaded.valueAt(i)) {
                usersWithRecentsLoaded[len++] = userId;
            }
        }
        if (len < usersWithRecentsLoaded.length) {
            // should never happen.
            return Arrays.copyOf(usersWithRecentsLoaded, len);
        }
        return usersWithRecentsLoaded;
    }

    private void unloadUserRecentsLocked(int userId) {
        if (mUsersWithRecentsLoaded.get(userId)) {
            Slog.i(TAG, "Unloading recents for user " + userId + " from memory.");
            mUsersWithRecentsLoaded.delete(userId);
            removeTasksForUserLocked(userId);
        }
    }

    /**
     * Removes recent tasks and any other state kept in memory for the passed in user. Does not
     * touch the information present on persistent storage.
     *
     * @param userId the id of the user
     */
    void unloadUserDataFromMemoryLocked(int userId) {
        unloadUserRecentsLocked(userId);
        mPersistedTaskIds.delete(userId);
        mTaskPersister.unloadUserDataFromMemory(userId);
    }

    TaskRecord taskForIdLocked(int id) {
        final int recentsCount = size();
        for (int i = 0; i < recentsCount; i++) {
            TaskRecord tr = get(i);
            if (tr.taskId == id) {
                return tr;
            }
        }
        return null;
    }

    /** Remove recent tasks for a user. */
    void removeTasksForUserLocked(int userId) {
        if(userId <= 0) {
            Slog.i(TAG, "Can't remove recent task on user " + userId);
            return;
        }

        for (int i = size() - 1; i >= 0; --i) {
            TaskRecord tr = get(i);
            if (tr.userId == userId) {
                if(DEBUG_TASKS) Slog.i(TAG_TASKS,
                        "remove RecentTask " + tr + " when finishing user" + userId);
                remove(i);
                tr.removedFromRecents();
            }
        }
    }

    void onPackagesSuspendedChanged(String[] packages, boolean suspended, int userId) {
        final Set<String> packageNames = Sets.newHashSet(packages);
        for (int i = size() - 1; i >= 0; --i) {
            final TaskRecord tr = get(i);
            if (tr.realActivity != null
                    && packageNames.contains(tr.realActivity.getPackageName())
                    && tr.userId == userId
                    && tr.realActivitySuspended != suspended) {
               tr.realActivitySuspended = suspended;
               notifyTaskPersisterLocked(tr, false);
            }
        }

    }

    /**
     * Update the recent tasks lists: make sure tasks should still be here (their
     * applications / activities still exist), update their availability, fix-up ordering
     * of affiliations.
     */
    void cleanupLocked(int userId) {
        int recentsCount = size();
        if (recentsCount == 0) {
            // Happens when called from the packagemanager broadcast before boot,
            // or just any empty list.
            return;
        }

        final IPackageManager pm = AppGlobals.getPackageManager();
        for (int i = recentsCount - 1; i >= 0; i--) {
            final TaskRecord task = get(i);
            if (userId != UserHandle.USER_ALL && task.userId != userId) {
                // Only look at tasks for the user ID of interest.
                continue;
            }
            if (task.autoRemoveRecents && task.getTopActivity() == null) {
                // This situation is broken, and we should just get rid of it now.
                remove(i);
                task.removedFromRecents();
                Slog.w(TAG, "Removing auto-remove without activity: " + task);
                continue;
            }
            // Check whether this activity is currently available.
            if (task.realActivity != null) {
                ActivityInfo ai = mTmpAvailActCache.get(task.realActivity);
                if (ai == null) {
                    try {
                        // At this first cut, we're only interested in
                        // activities that are fully runnable based on
                        // current system state.
                        ai = pm.getActivityInfo(task.realActivity,
                                PackageManager.MATCH_DEBUG_TRIAGED_MISSING, userId);
                    } catch (RemoteException e) {
                        // Will never happen.
                        continue;
                    }
                    if (ai == null) {
                        ai = mTmpActivityInfo;
                    }
                    mTmpAvailActCache.put(task.realActivity, ai);
                }
                if (ai == mTmpActivityInfo) {
                    // This could be either because the activity no longer exists, or the
                    // app is temporarily gone. For the former we want to remove the recents
                    // entry; for the latter we want to mark it as unavailable.
                    ApplicationInfo app = mTmpAvailAppCache
                            .get(task.realActivity.getPackageName());
                    if (app == null) {
                        try {
                            app = pm.getApplicationInfo(task.realActivity.getPackageName(),
                                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                        } catch (RemoteException e) {
                            // Will never happen.
                            continue;
                        }
                        if (app == null) {
                            app = mTmpAppInfo;
                        }
                        mTmpAvailAppCache.put(task.realActivity.getPackageName(), app);
                    }
                    if (app == mTmpAppInfo
                            || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                        // Doesn't exist any more! Good-bye.
                        remove(i);
                        task.removedFromRecents();
                        Slog.w(TAG, "Removing no longer valid recent: " + task);
                        continue;
                    } else {
                        // Otherwise just not available for now.
                        if (DEBUG_RECENTS && task.isAvailable) Slog.d(TAG_RECENTS,
                                "Making recent unavailable: " + task);
                        task.isAvailable = false;
                    }
                } else {
                    if (!ai.enabled || !ai.applicationInfo.enabled
                            || (ai.applicationInfo.flags
                                    & ApplicationInfo.FLAG_INSTALLED) == 0) {
                        if (DEBUG_RECENTS && task.isAvailable) Slog.d(TAG_RECENTS,
                                "Making recent unavailable: " + task
                                        + " (enabled=" + ai.enabled + "/"
                                        + ai.applicationInfo.enabled
                                        + " flags="
                                        + Integer.toHexString(ai.applicationInfo.flags)
                                        + ")");
                        task.isAvailable = false;
                    } else {
                        if (DEBUG_RECENTS && !task.isAvailable) Slog.d(TAG_RECENTS,
                                "Making recent available: " + task);
                        task.isAvailable = true;
                    }
                }
            }
        }

        // Verify the affiliate chain for each task.
        int i = 0;
        recentsCount = size();
        while (i < recentsCount) {
            i = processNextAffiliateChainLocked(i);
        }
        // recent tasks are now in sorted, affiliated order.
    }

    private final boolean moveAffiliatedTasksToFront(TaskRecord task, int taskIndex) {
        int recentsCount = size();
        TaskRecord top = task;
        int topIndex = taskIndex;
        while (top.mNextAffiliate != null && topIndex > 0) {
            top = top.mNextAffiliate;
            topIndex--;
        }
        if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: adding affilliates starting at "
                + topIndex + " from intial " + taskIndex);
        // Find the end of the chain, doing a sanity check along the way.
        boolean sane = top.mAffiliatedTaskId == task.mAffiliatedTaskId;
        int endIndex = topIndex;
        TaskRecord prev = top;
        while (endIndex < recentsCount) {
            TaskRecord cur = get(endIndex);
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: looking at next chain @"
                    + endIndex + " " + cur);
            if (cur == top) {
                // Verify start of the chain.
                if (cur.mNextAffiliate != null || cur.mNextAffiliateTaskId != INVALID_TASK_ID) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": first task has next affiliate: " + prev);
                    sane = false;
                    break;
                }
            } else {
                // Verify middle of the chain's next points back to the one before.
                if (cur.mNextAffiliate != prev
                        || cur.mNextAffiliateTaskId != prev.taskId) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": middle task " + cur + " @" + endIndex
                            + " has bad next affiliate "
                            + cur.mNextAffiliate + " id " + cur.mNextAffiliateTaskId
                            + ", expected " + prev);
                    sane = false;
                    break;
                }
            }
            if (cur.mPrevAffiliateTaskId == INVALID_TASK_ID) {
                // Chain ends here.
                if (cur.mPrevAffiliate != null) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": last task " + cur + " has previous affiliate "
                            + cur.mPrevAffiliate);
                    sane = false;
                }
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: end of chain @" + endIndex);
                break;
            } else {
                // Verify middle of the chain's prev points to a valid item.
                if (cur.mPrevAffiliate == null) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": task " + cur + " has previous affiliate "
                            + cur.mPrevAffiliate + " but should be id "
                            + cur.mPrevAffiliate);
                    sane = false;
                    break;
                }
            }
            if (cur.mAffiliatedTaskId != task.mAffiliatedTaskId) {
                Slog.wtf(TAG, "Bad chain @" + endIndex
                        + ": task " + cur + " has affiliated id "
                        + cur.mAffiliatedTaskId + " but should be "
                        + task.mAffiliatedTaskId);
                sane = false;
                break;
            }
            prev = cur;
            endIndex++;
            if (endIndex >= recentsCount) {
                Slog.wtf(TAG, "Bad chain ran off index " + endIndex
                        + ": last task " + prev);
                sane = false;
                break;
            }
        }
        if (sane) {
            if (endIndex < taskIndex) {
                Slog.wtf(TAG, "Bad chain @" + endIndex
                        + ": did not extend to task " + task + " @" + taskIndex);
                sane = false;
            }
        }
        if (sane) {
            // All looks good, we can just move all of the affiliated tasks
            // to the top.
            for (int i=topIndex; i<=endIndex; i++) {
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: moving affiliated " + task
                        + " from " + i + " to " + (i-topIndex));
                TaskRecord cur = remove(i);
                add(i - topIndex, cur);
            }
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: done moving tasks  " +  topIndex
                    + " to " + endIndex);
            return true;
        }

        // Whoops, couldn't do it.
        return false;
    }

    final void addLocked(TaskRecord task) {
        final boolean isAffiliated = task.mAffiliatedTaskId != task.taskId
                || task.mNextAffiliateTaskId != INVALID_TASK_ID
                || task.mPrevAffiliateTaskId != INVALID_TASK_ID;

        int recentsCount = size();
        // Quick case: never add voice sessions.
        // TODO: VI what about if it's just an activity?
        // Probably nothing to do here
        if (task.voiceSession != null) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                    "addRecent: not adding voice interaction " + task);
            return;
        }
        // Another quick case: check if the top-most recent task is the same.
        if (!isAffiliated && recentsCount > 0 && get(0) == task) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: already at top: " + task);
            return;
        }
        // Another quick case: check if this is part of a set of affiliated
        // tasks that are at the top.
        if (isAffiliated && recentsCount > 0 && task.inRecents
                && task.mAffiliatedTaskId == get(0).mAffiliatedTaskId) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: affiliated " + get(0)
                    + " at top when adding " + task);
            return;
        }

        boolean needAffiliationFix = false;

        // Slightly less quick case: the task is already in recents, so all we need
        // to do is move it.
        if (task.inRecents) {
            int taskIndex = indexOf(task);
            if (taskIndex >= 0) {
                if (!isAffiliated || MOVE_AFFILIATED_TASKS_TO_FRONT) {
                    // Simple case: this is not an affiliated task, so we just move it to the front.
                    remove(taskIndex);
                    add(0, task);
                    notifyTaskPersisterLocked(task, false);
                    if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: moving to top " + task
                            + " from " + taskIndex);
                    return;
                } else {
                    // More complicated: need to keep all affiliated tasks together.
                    if (moveAffiliatedTasksToFront(task, taskIndex)) {
                        // All went well.
                        return;
                    }

                    // Uh oh...  something bad in the affiliation chain, try to rebuild
                    // everything and then go through our general path of adding a new task.
                    needAffiliationFix = true;
                }
            } else {
                Slog.wtf(TAG, "Task with inRecent not in recents: " + task);
                needAffiliationFix = true;
            }
        }

        if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: trimming tasks for " + task);
        trimForTaskLocked(task, true);

        recentsCount = size();
        final int maxRecents = ActivityManager.getMaxRecentTasksStatic();
        while (recentsCount >= maxRecents) {
            final TaskRecord tr = remove(recentsCount - 1);
            tr.removedFromRecents();
            recentsCount--;
        }
        task.inRecents = true;
        if (!isAffiliated || needAffiliationFix) {
            // If this is a simple non-affiliated task, or we had some failure trying to
            // handle it as part of an affilated task, then just place it at the top.
            add(0, task);
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: adding " + task);
        } else if (isAffiliated) {
            // If this is a new affiliated task, then move all of the affiliated tasks
            // to the front and insert this new one.
            TaskRecord other = task.mNextAffiliate;
            if (other == null) {
                other = task.mPrevAffiliate;
            }
            if (other != null) {
                int otherIndex = indexOf(other);
                if (otherIndex >= 0) {
                    // Insert new task at appropriate location.
                    int taskIndex;
                    if (other == task.mNextAffiliate) {
                        // We found the index of our next affiliation, which is who is
                        // before us in the list, so add after that point.
                        taskIndex = otherIndex+1;
                    } else {
                        // We found the index of our previous affiliation, which is who is
                        // after us in the list, so add at their position.
                        taskIndex = otherIndex;
                    }
                    if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                            "addRecent: new affiliated task added at " + taskIndex + ": " + task);
                    add(taskIndex, task);

                    // Now move everything to the front.
                    if (moveAffiliatedTasksToFront(task, taskIndex)) {
                        // All went well.
                        return;
                    }

                    // Uh oh...  something bad in the affiliation chain, try to rebuild
                    // everything and then go through our general path of adding a new task.
                    needAffiliationFix = true;
                } else {
                    if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                            "addRecent: couldn't find other affiliation " + other);
                    needAffiliationFix = true;
                }
            } else {
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                        "addRecent: adding affiliated task without next/prev:" + task);
                needAffiliationFix = true;
            }
        }

        if (needAffiliationFix) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: regrouping affiliations");
            cleanupLocked(task.userId);
        }
    }

    /**
     * If needed, remove oldest existing entries in recents that are for the same kind
     * of task as the given one.
     */
    int trimForTaskLocked(TaskRecord task, boolean doTrim) {
        int recentsCount = size();
        final Intent intent = task.intent;
        final boolean document = intent != null && intent.isDocument();
        int maxRecents = task.maxRecents - 1;
        for (int i = 0; i < recentsCount; i++) {
            final TaskRecord tr = get(i);
            if (task != tr) {
                if (task.stack != null && tr.stack != null && task.stack != tr.stack) {
                    continue;
                }
                if (task.userId != tr.userId) {
                    continue;
                }
                if (i > MAX_RECENT_BITMAPS) {
                    tr.freeLastThumbnail();
                }
                final Intent trIntent = tr.intent;
                final boolean sameAffinity =
                        task.affinity != null && task.affinity.equals(tr.affinity);
                final boolean sameIntentFilter = intent != null && intent.filterEquals(trIntent);
                boolean multiTasksAllowed = false;
                final int flags = intent.getFlags();
                if ((flags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT)) != 0
                        && (flags & FLAG_ACTIVITY_MULTIPLE_TASK) != 0) {
                    multiTasksAllowed = true;
                }
                final boolean trIsDocument = trIntent != null && trIntent.isDocument();
                final boolean bothDocuments = document && trIsDocument;
                if (!sameAffinity && !sameIntentFilter && !bothDocuments) {
                    continue;
                }

                if (bothDocuments) {
                    // Do these documents belong to the same activity?
                    final boolean sameActivity = task.realActivity != null
                            && tr.realActivity != null
                            && task.realActivity.equals(tr.realActivity);
                    // If the document is open in another app or is not the same
                    // document, we don't need to trim it.
                    if (!sameActivity) {
                        continue;
                    // Otherwise only trim if we are over our max recents for this task
                    } else if (maxRecents > 0) {
                        --maxRecents;
                        if (!doTrim || !sameIntentFilter || multiTasksAllowed) {
                            // We don't want to trim if we are not over the max allowed entries and
                            // the caller doesn't want us to trim, the tasks are not of the same
                            // intent filter, or multiple entries fot the task is allowed.
                            continue;
                        }
                    }
                    // Hit the maximum number of documents for this task. Fall through
                    // and remove this document from recents.
                } else if (document || trIsDocument) {
                    // Only one of these is a document. Not the droid we're looking for.
                    continue;
                }
            }

            if (!doTrim) {
                // If the caller is not actually asking for a trim, just tell them we reached
                // a point where the trim would happen.
                return i;
            }

            // Either task and tr are the same or, their affinities match or their intents match
            // and neither of them is a document, or they are documents using the same activity
            // and their maxRecents has been reached.
            tr.disposeThumbnail();
            remove(i);
            if (task != tr) {
                tr.removedFromRecents();
            }
            i--;
            recentsCount--;
            if (task.intent == null) {
                // If the new recent task we are adding is not fully
                // specified, then replace it with the existing recent task.
                task = tr;
            }
            notifyTaskPersisterLocked(tr, false);
        }

        return -1;
    }

    // Sort by taskId
    private static Comparator<TaskRecord> sTaskRecordComparator = new Comparator<TaskRecord>() {
        @Override
        public int compare(TaskRecord lhs, TaskRecord rhs) {
            return rhs.taskId - lhs.taskId;
        }
    };

    // Extract the affiliates of the chain containing recent at index start.
    private int processNextAffiliateChainLocked(int start) {
        final TaskRecord startTask = get(start);
        final int affiliateId = startTask.mAffiliatedTaskId;

        // Quick identification of isolated tasks. I.e. those not launched behind.
        if (startTask.taskId == affiliateId && startTask.mPrevAffiliate == null &&
                startTask.mNextAffiliate == null) {
            // There is still a slim chance that there are other tasks that point to this task
            // and that the chain is so messed up that this task no longer points to them but
            // the gain of this optimization outweighs the risk.
            startTask.inRecents = true;
            return start + 1;
        }

        // Remove all tasks that are affiliated to affiliateId and put them in mTmpRecents.
        mTmpRecents.clear();
        for (int i = size() - 1; i >= start; --i) {
            final TaskRecord task = get(i);
            if (task.mAffiliatedTaskId == affiliateId) {
                remove(i);
                mTmpRecents.add(task);
            }
        }

        // Sort them all by taskId. That is the order they were create in and that order will
        // always be correct.
        Collections.sort(mTmpRecents, sTaskRecordComparator);

        // Go through and fix up the linked list.
        // The first one is the end of the chain and has no next.
        final TaskRecord first = mTmpRecents.get(0);
        first.inRecents = true;
        if (first.mNextAffiliate != null) {
            Slog.w(TAG, "Link error 1 first.next=" + first.mNextAffiliate);
            first.setNextAffiliate(null);
            notifyTaskPersisterLocked(first, false);
        }
        // Everything in the middle is doubly linked from next to prev.
        final int tmpSize = mTmpRecents.size();
        for (int i = 0; i < tmpSize - 1; ++i) {
            final TaskRecord next = mTmpRecents.get(i);
            final TaskRecord prev = mTmpRecents.get(i + 1);
            if (next.mPrevAffiliate != prev) {
                Slog.w(TAG, "Link error 2 next=" + next + " prev=" + next.mPrevAffiliate +
                        " setting prev=" + prev);
                next.setPrevAffiliate(prev);
                notifyTaskPersisterLocked(next, false);
            }
            if (prev.mNextAffiliate != next) {
                Slog.w(TAG, "Link error 3 prev=" + prev + " next=" + prev.mNextAffiliate +
                        " setting next=" + next);
                prev.setNextAffiliate(next);
                notifyTaskPersisterLocked(prev, false);
            }
            prev.inRecents = true;
        }
        // The last one is the beginning of the list and has no prev.
        final TaskRecord last = mTmpRecents.get(tmpSize - 1);
        if (last.mPrevAffiliate != null) {
            Slog.w(TAG, "Link error 4 last.prev=" + last.mPrevAffiliate);
            last.setPrevAffiliate(null);
            notifyTaskPersisterLocked(last, false);
        }

        // Insert the group back into mRecentTasks at start.
        addAll(start, mTmpRecents);
        mTmpRecents.clear();

        // Let the caller know where we left off.
        return start + tmpSize;
    }
}
