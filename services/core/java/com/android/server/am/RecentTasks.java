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

import static com.android.server.am.ActivityManagerDebugConfig.*;
import static com.android.server.am.TaskRecord.INVALID_TASK_ID;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

/**
 * Class for managing the recent tasks list.
 */
class RecentTasks extends ArrayList<TaskRecord> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RecentTasks" : TAG_AM;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_TASKS = TAG + POSTFIX_TASKS;

    // Maximum number recent bitmaps to keep in memory.
    private static final int MAX_RECENT_BITMAPS = 3;

    // Activity manager service.
    private final ActivityManagerService mService;

    // Mainly to avoid object recreation on multiple calls.
    private final ArrayList<TaskRecord> mTmpRecents = new ArrayList<TaskRecord>();
    private final HashMap<ComponentName, ActivityInfo> tmpAvailActCache = new HashMap<>();
    private final HashMap<String, ApplicationInfo> tmpAvailAppCache = new HashMap<>();
    private final ActivityInfo tmpActivityInfo = new ActivityInfo();
    private final ApplicationInfo tmpAppInfo = new ApplicationInfo();

    RecentTasks(ActivityManagerService service) {
        mService = service;
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

        // Remove tasks from persistent storage.
        mService.notifyTaskPersisterLocked(null, true);
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
        final int[] users = (userId == UserHandle.USER_ALL)
                ? mService.getUsersLocked() : new int[] { userId };
        for (int userIdx = 0; userIdx < users.length; userIdx++) {
            final int user = users[userIdx];
            recentsCount = size() - 1;
            for (int i = recentsCount; i >= 0; i--) {
                TaskRecord task = get(i);
                if (task.userId != user) {
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
                    ActivityInfo ai = tmpAvailActCache.get(task.realActivity);
                    if (ai == null) {
                        try {
                            ai = pm.getActivityInfo(task.realActivity,
                                    PackageManager.GET_UNINSTALLED_PACKAGES
                                            | PackageManager.GET_DISABLED_COMPONENTS, user);
                        } catch (RemoteException e) {
                            // Will never happen.
                            continue;
                        }
                        if (ai == null) {
                            ai = tmpActivityInfo;
                        }
                        tmpAvailActCache.put(task.realActivity, ai);
                    }
                    if (ai == tmpActivityInfo) {
                        // This could be either because the activity no longer exists, or the
                        // app is temporarily gone.  For the former we want to remove the recents
                        // entry; for the latter we want to mark it as unavailable.
                        ApplicationInfo app = tmpAvailAppCache.get(task.realActivity.getPackageName());
                        if (app == null) {
                            try {
                                app = pm.getApplicationInfo(task.realActivity.getPackageName(),
                                        PackageManager.GET_UNINSTALLED_PACKAGES
                                                | PackageManager.GET_DISABLED_COMPONENTS, user);
                            } catch (RemoteException e) {
                                // Will never happen.
                                continue;
                            }
                            if (app == null) {
                                app = tmpAppInfo;
                            }
                            tmpAvailAppCache.put(task.realActivity.getPackageName(), app);
                        }
                        if (app == tmpAppInfo || (app.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
                            // Doesn't exist any more!  Good-bye.
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
                                || (ai.applicationInfo.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
                            if (DEBUG_RECENTS && task.isAvailable) Slog.d(TAG_RECENTS,
                                    "Making recent unavailable: " + task
                                    + " (enabled=" + ai.enabled + "/" + ai.applicationInfo.enabled
                                    + " flags=" + Integer.toHexString(ai.applicationInfo.flags)
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
                if (!isAffiliated) {
                    // Simple case: this is not an affiliated task, so we just move it to the front.
                    remove(taskIndex);
                    add(0, task);
                    mService.notifyTaskPersisterLocked(task, false);
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
                if (task.userId != tr.userId) {
                    continue;
                }
                if (i > MAX_RECENT_BITMAPS) {
                    tr.freeLastThumbnail();
                }
                final Intent trIntent = tr.intent;
                final boolean sameAffinity =
                        task.affinity != null && task.affinity.equals(tr.affinity);
                final boolean sameIntent = (intent != null && intent.filterEquals(trIntent));
                final boolean trIsDocument = trIntent != null && trIntent.isDocument();
                final boolean bothDocuments = document && trIsDocument;
                if (!sameAffinity && !sameIntent && !bothDocuments) {
                    continue;
                }

                if (bothDocuments) {
                    // Do these documents belong to the same activity?
                    final boolean sameActivity = task.realActivity != null
                            && tr.realActivity != null
                            && task.realActivity.equals(tr.realActivity);
                    if (!sameActivity) {
                        continue;
                    }
                    if (maxRecents > 0) {
                        --maxRecents;
                        continue;
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
            mService.notifyTaskPersisterLocked(tr, false);
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
            mService.notifyTaskPersisterLocked(first, false);
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
                mService.notifyTaskPersisterLocked(next, false);
            }
            if (prev.mNextAffiliate != next) {
                Slog.w(TAG, "Link error 3 prev=" + prev + " next=" + prev.mNextAffiliate +
                        " setting next=" + next);
                prev.setNextAffiliate(next);
                mService.notifyTaskPersisterLocked(prev, false);
            }
            prev.inRecents = true;
        }
        // The last one is the beginning of the list and has no prev.
        final TaskRecord last = mTmpRecents.get(tmpSize - 1);
        if (last.mPrevAffiliate != null) {
            Slog.w(TAG, "Link error 4 last.prev=" + last.mPrevAffiliate);
            last.setPrevAffiliate(null);
            mService.notifyTaskPersisterLocked(last, false);
        }

        // Insert the group back into mRecentTasks at start.
        addAll(start, mTmpRecents);
        mTmpRecents.clear();

        // Let the caller know where we left off.
        return start + tmpSize;
    }

}
