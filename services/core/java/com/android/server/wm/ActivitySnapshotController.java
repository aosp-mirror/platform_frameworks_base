/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.Environment;
import android.os.Trace;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.BaseAppSnapshotPersister.PersistInfoProvider;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * When an app token becomes invisible, we take a snapshot (bitmap) and put it into our cache.
 * Internally we use gralloc buffers to be able to draw them wherever we like without any copying.
 * <p>
 * System applications may retrieve a snapshot to represent the current state of an activity, and
 * draw them in their own process.
 * <p>
 * Unlike TaskSnapshotController, we only keep one activity snapshot for a visible task in the
 * cache. Which should largely reduce the memory usage.
 * <p>
 * To access this class, acquire the global window manager lock.
 */
class ActivitySnapshotController extends AbsAppSnapshotController<ActivityRecord,
        ActivitySnapshotCache> {
    private static final boolean DEBUG = false;
    private static final String TAG = AbsAppSnapshotController.TAG;
    // Maximum persisted snapshot count on disk.
    private static final int MAX_PERSIST_SNAPSHOT_COUNT = 20;

    static final String SNAPSHOTS_DIRNAME = "activity_snapshots";

    /**
     * The pending activities which should remove snapshot from memory when process transition
     * finish.
     */
    @VisibleForTesting
    final ArraySet<ActivityRecord> mPendingRemoveActivity = new ArraySet<>();

    /**
     * The pending activities which should delete snapshot files when process transition finish.
     */
    @VisibleForTesting
    final ArraySet<ActivityRecord> mPendingDeleteActivity = new ArraySet<>();

    /**
     * The pending activities which should load snapshot from disk when process transition finish.
     */
    @VisibleForTesting
    final ArraySet<ActivityRecord> mPendingLoadActivity = new ArraySet<>();

    private final ArraySet<ActivityRecord> mOnBackPressedActivities = new ArraySet<>();

    private final ArrayList<ActivityRecord> mTmpBelowActivities = new ArrayList<>();
    private final ArrayList<WindowContainer> mTmpTransitionParticipants = new ArrayList<>();
    private final SnapshotPersistQueue mSnapshotPersistQueue;
    private final PersistInfoProvider mPersistInfoProvider;
    private final AppSnapshotLoader mSnapshotLoader;

    /**
     * File information holders, to make the sequence align, always update status of
     * mUserSavedFiles/mSavedFilesInOrder before persist file from mPersister.
     */
    private final SparseArray<SparseArray<UserSavedFile>> mUserSavedFiles = new SparseArray<>();
    // Keep sorted with create timeline.
    private final ArrayList<UserSavedFile> mSavedFilesInOrder = new ArrayList<>();
    private final TaskSnapshotPersister mPersister;

    ActivitySnapshotController(WindowManagerService service, SnapshotPersistQueue persistQueue) {
        super(service);
        mSnapshotPersistQueue = persistQueue;
        mPersistInfoProvider = createPersistInfoProvider(service,
                Environment::getDataSystemCeDirectory);
        mPersister = new TaskSnapshotPersister(persistQueue, mPersistInfoProvider);
        mSnapshotLoader = new AppSnapshotLoader(mPersistInfoProvider);
        initialize(new ActivitySnapshotCache());

        final boolean snapshotEnabled =
                !service.mContext
                        .getResources()
                        .getBoolean(com.android.internal.R.bool.config_disableTaskSnapshots)
                && !ActivityManager.isLowRamDeviceStatic(); // Don't support Android Go
        setSnapshotEnabled(snapshotEnabled);
    }

    @Override
    protected float initSnapshotScale() {
        final float config = mService.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_resActivitySnapshotScale);
        return Math.max(Math.min(config, 1f), 0.1f);
    }

    static PersistInfoProvider createPersistInfoProvider(
            WindowManagerService service, BaseAppSnapshotPersister.DirectoryResolver resolver) {
        // Don't persist reduced file, instead we only persist the "HighRes" bitmap which has
        // already scaled with #initSnapshotScale
        final boolean use16BitFormat = service.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_use16BitTaskSnapshotPixelFormat);
        return new PersistInfoProvider(resolver, SNAPSHOTS_DIRNAME,
                false /* enableLowResSnapshots */, 0 /* lowResScaleFactor */, use16BitFormat);
    }

    /**
     * Retrieves a snapshot for a set of activities from cache.
     * This will only return the snapshot IFF input activities exist entirely in the snapshot.
     * Sample: If the snapshot was captured with activity A and B, here will return null if the
     * input activity is only [A] or [B], it must be [A, B]
     */
    @Nullable
    TaskSnapshot getSnapshot(@NonNull ActivityRecord[] activities) {
        if (activities.length == 0) {
            return null;
        }
        final UserSavedFile tmpUsf = findSavedFile(activities[0]);
        if (tmpUsf == null || tmpUsf.mActivityIds.size() != activities.length) {
            return null;
        }
        int fileId = 0;
        for (int i = activities.length - 1; i >= 0; --i) {
            fileId ^= getSystemHashCode(activities[i]);
        }
        return tmpUsf.mFileId == fileId ? mCache.getSnapshot(tmpUsf.mActivityIds.get(0)) : null;
    }

    private void cleanUpUserFiles(int userId) {
        synchronized (mSnapshotPersistQueue.getLock()) {
            mSnapshotPersistQueue.sendToQueueLocked(
                    new SnapshotPersistQueue.WriteQueueItem(mPersistInfoProvider, userId) {

                        @Override
                        void write() {
                            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "cleanUpUserFiles");
                            final File file = mPersistInfoProvider.getDirectory(mUserId);
                            if (file.exists()) {
                                final File[] contents = file.listFiles();
                                if (contents != null) {
                                    for (int i = contents.length - 1; i >= 0; i--) {
                                        contents[i].delete();
                                    }
                                }
                            }
                            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                        }
                    });
        }
    }

    void addOnBackPressedActivity(ActivityRecord ar) {
        if (shouldDisableSnapshots()) {
            return;
        }
        mOnBackPressedActivities.add(ar);
    }

    void clearOnBackPressedActivities() {
        if (shouldDisableSnapshots()) {
            return;
        }
        mOnBackPressedActivities.clear();
    }

    /**
     * Prepare to collect any change for snapshots processing. Clear all temporary fields.
     */
    void beginSnapshotProcess() {
        if (shouldDisableSnapshots()) {
            return;
        }
        resetTmpFields();
    }

    /**
     * End collect any change for snapshots processing, start process data.
     */
    void endSnapshotProcess() {
        if (shouldDisableSnapshots()) {
            return;
        }
        for (int i = mOnBackPressedActivities.size() - 1; i >= 0; --i) {
            handleActivityTransition(mOnBackPressedActivities.valueAt(i));
        }
        mOnBackPressedActivities.clear();
        mTmpTransitionParticipants.clear();
        postProcess();
    }

    @VisibleForTesting
    void resetTmpFields() {
        mPendingRemoveActivity.clear();
        mPendingDeleteActivity.clear();
        mPendingLoadActivity.clear();
    }

    /**
     * Start process all pending activities for a transition.
     */
    private void postProcess() {
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#postProcess result:"
                    + " remove " + mPendingRemoveActivity
                    + " delete " + mPendingDeleteActivity
                    + " load " + mPendingLoadActivity);
        }
        // load snapshot to cache
        loadActivitySnapshot();
        // clear mTmpRemoveActivity from cache
        for (int i = mPendingRemoveActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingRemoveActivity.valueAt(i);
            removeCachedFiles(ar);
        }
        // clear snapshot on cache and delete files
        for (int i = mPendingDeleteActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingDeleteActivity.valueAt(i);
            removeIfUserSavedFileExist(ar);
        }
        // don't keep any reference
        resetTmpFields();
    }

    class LoadActivitySnapshotItem extends SnapshotPersistQueue.WriteQueueItem {
        private final int mCode;
        private final ActivityRecord[] mActivities;

        LoadActivitySnapshotItem(@NonNull ActivityRecord[] activities, int code, int userId,
                @NonNull PersistInfoProvider persistInfoProvider) {
            super(persistInfoProvider, userId);
            mActivities = activities;
            mCode = code;
        }

        @Override
        void write() {
            try {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                        "load_activity_snapshot");
                final TaskSnapshot snapshot = mSnapshotLoader.loadTask(mCode,
                        mUserId, false /* loadLowResolutionBitmap */);
                if (snapshot == null) {
                    return;
                }
                synchronized (mService.getWindowManagerLock()) {
                    // Verify the snapshot is still needed, and the activity is not finishing
                    if (!hasRecord(mActivities[0])) {
                        return;
                    }
                    for (ActivityRecord ar : mActivities) {
                        mCache.putSnapshot(ar, snapshot);
                    }
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            final LoadActivitySnapshotItem other = (LoadActivitySnapshotItem) o;
            return mCode == other.mCode && mUserId == other.mUserId
                    && mPersistInfoProvider == other.mPersistInfoProvider;
        }

        @Override
        public String toString() {
            return "LoadActivitySnapshotItem{code=" + mCode + ", UserId=" + mUserId + "}";
        }
    }

    void loadActivitySnapshot() {
        if (mPendingLoadActivity.isEmpty()) {
            return;
        }
        // Only load if saved file exists.
        final ArraySet<UserSavedFile> loadingFiles = new ArraySet<>();
        for (int i = mPendingLoadActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingLoadActivity.valueAt(i);
            final UserSavedFile usf = findSavedFile(ar);
            if (usf != null) {
                loadingFiles.add(usf);
            }
        }
        // Filter out the activity if the snapshot was removed.
        for (int i = loadingFiles.size() - 1; i >= 0; i--) {
            final UserSavedFile usf = loadingFiles.valueAt(i);
            final ActivityRecord[] activities = usf.filterExistActivities(mPendingLoadActivity);
            if (activities == null) {
                continue;
            }
            if (getSnapshot(activities) != null) {
                // Found the cache in memory, so skip loading from file.
                continue;
            }
            loadSnapshotInner(activities, usf);
        }
    }

    @VisibleForTesting
    void loadSnapshotInner(ActivityRecord[] activities, UserSavedFile usf) {
        synchronized (mSnapshotPersistQueue.getLock()) {
            mSnapshotPersistQueue.insertQueueAtFirstLocked(new LoadActivitySnapshotItem(
                    activities, usf.mFileId, usf.mUserId, mPersistInfoProvider));
        }
    }

    /**
     * Record one or multiple activities within a snapshot where those activities must belong to
     * the same task.
     * @param activity If the request activity is more than one, try to record those activities
     *                 as a single snapshot, so those activities should belong to the same task.
     */
    void recordSnapshot(@NonNull ArrayList<ActivityRecord> activity) {
        if (shouldDisableSnapshots() || activity.isEmpty()) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#recordSnapshot " + activity);
        }
        final int size = activity.size();
        final int[] mixedCode = new int[size];
        if (size == 1) {
            final ActivityRecord singleActivity = activity.get(0);
            final TaskSnapshot snapshot = recordSnapshotInner(singleActivity);
            if (snapshot != null) {
                mixedCode[0] = getSystemHashCode(singleActivity);
                addUserSavedFile(singleActivity.mUserId, snapshot, mixedCode);
            }
            return;
        }

        final Task mainTask = activity.get(0).getTask();
        // Snapshot by task controller with activity's scale.
        final TaskSnapshot snapshot = mService.mTaskSnapshotController
                .snapshot(mainTask, mHighResSnapshotScale);
        if (snapshot == null) {
            return;
        }

        for (int i = 0; i < activity.size(); ++i) {
            final ActivityRecord next = activity.get(i);
            mCache.putSnapshot(next, snapshot);
            mixedCode[i] = getSystemHashCode(next);
        }
        addUserSavedFile(mainTask.mUserId, snapshot, mixedCode);
    }

    /**
     * Called when the visibility of an app changes outside the regular app transition flow.
     */
    void notifyAppVisibilityChanged(ActivityRecord ar, boolean visible) {
        if (shouldDisableSnapshots()) {
            return;
        }
        final Task task = ar.getTask();
        if (task == null) {
            return;
        }
        // Doesn't need to capture activity snapshot when it converts from translucent.
        if (!visible) {
            resetTmpFields();
            addBelowActivityIfExist(ar, mPendingRemoveActivity, false,
                    "remove-snapshot");
            postProcess();
        }
    }

    @VisibleForTesting
    static int getSystemHashCode(ActivityRecord activity) {
        return System.identityHashCode(activity);
    }

    @VisibleForTesting
    void handleTransitionFinish(@NonNull ArrayList<WindowContainer> windows) {
        mTmpTransitionParticipants.clear();
        mTmpTransitionParticipants.addAll(windows);
        for (int i = mTmpTransitionParticipants.size() - 1; i >= 0; --i) {
            final WindowContainer next = mTmpTransitionParticipants.get(i);
            if (next.asTask() != null) {
                handleTaskTransition(next.asTask());
            } else if (next.asTaskFragment() != null) {
                final TaskFragment tf = next.asTaskFragment();
                final ActivityRecord ar = tf.getTopMostActivity();
                if (ar != null) {
                    handleActivityTransition(ar);
                }
            } else if (next.asActivityRecord() != null) {
                handleActivityTransition(next.asActivityRecord());
            }
        }
    }

    private void handleActivityTransition(@NonNull ActivityRecord ar) {
        if (shouldDisableSnapshots()) {
            return;
        }
        if (ar.isVisibleRequested()) {
            mPendingDeleteActivity.add(ar);
            // load next one if exists.
            // Note if this transition is happen between two TaskFragment, the next N - 1 activity
            // may not participant in this transition.
            // Sample:
            //   [TF1] close
            //   [TF2] open
            //   Bottom Activity <- Able to load this even it didn't participant the transition.
            addBelowActivityIfExist(ar, mPendingLoadActivity, false, "load-snapshot");
        } else {
            // remove the snapshot for the one below close
            addBelowActivityIfExist(ar, mPendingRemoveActivity, true, "remove-snapshot");
        }
    }

    private void handleTaskTransition(Task task) {
        if (shouldDisableSnapshots()) {
            return;
        }
        final ActivityRecord topActivity = task.getTopMostActivity();
        if (topActivity == null) {
            return;
        }
        if (task.isVisibleRequested()) {
            // this is open task transition
            // load the N - 1 to cache
            addBelowActivityIfExist(topActivity, mPendingLoadActivity, true, "load-snapshot");
            // Move the activities to top of mSavedFilesInOrder, so when purge happen, there
            // will trim the persisted files from the most non-accessed.
            adjustSavedFileOrder(task);
        } else {
            // this is close task transition
            // remove the N - 1 from cache
            addBelowActivityIfExist(topActivity, mPendingRemoveActivity, true, "remove-snapshot");
        }
    }

    /**
     * Add the top -1 activity to a set if it exists.
     * @param inTransition true if the activity must participant in transition.
     */
    private void addBelowActivityIfExist(ActivityRecord currentActivity,
            ArraySet<ActivityRecord> set, boolean inTransition, String debugMessage) {
        getActivityBelow(currentActivity, inTransition, mTmpBelowActivities);
        for (int i = mTmpBelowActivities.size() - 1; i >= 0; --i) {
            set.add(mTmpBelowActivities.get(i));
            if (DEBUG) {
                Slog.d(TAG, "ActivitySnapshotController#addBelowTopActivityIfExist "
                        + mTmpBelowActivities.get(i) + " from " + debugMessage);
            }
        }
        mTmpBelowActivities.clear();
    }

    private void getActivityBelow(ActivityRecord currentActivity, boolean inTransition,
            ArrayList<ActivityRecord> result) {
        final Task currentTask = currentActivity.getTask();
        if (currentTask == null) {
            return;
        }
        final ActivityRecord initPrev = currentTask.getActivityBelow(currentActivity);
        if (initPrev == null) {
            return;
        }
        final TaskFragment currTF = currentActivity.getTaskFragment();
        final TaskFragment prevTF = initPrev.getTaskFragment();
        final TaskFragment prevAdjacentTF = prevTF != null
                ? prevTF.getAdjacentTaskFragment() : null;
        if (currTF == prevTF && currTF != null || prevAdjacentTF == null) {
            // Current activity and previous one is in the same task fragment, or
            // previous activity is not in a task fragment, or
            // previous activity's task fragment doesn't adjacent to any others.
            if (!inTransition || isInParticipant(initPrev, mTmpTransitionParticipants)) {
                result.add(initPrev);
            }
            return;
        }

        if (prevAdjacentTF == currTF) {
            // previous activity A is adjacent to current activity B.
            // Try to find anyone below previous activityA, which are C and D if exists.
            // A | B
            // C (| D)
            getActivityBelow(initPrev, inTransition, result);
        } else {
            // previous activity C isn't adjacent to current activity A.
            // A
            // B | C
            final Task prevAdjacentTask = prevAdjacentTF.getTask();
            if (prevAdjacentTask == currentTask) {
                final int currentIndex = currTF != null
                        ? currentTask.mChildren.indexOf(currTF)
                        : currentTask.mChildren.indexOf(currentActivity);
                final int prevAdjacentIndex =
                        prevAdjacentTask.mChildren.indexOf(prevAdjacentTF);
                // prevAdjacentTF already above currentActivity
                if (prevAdjacentIndex > currentIndex) {
                    return;
                }
            }
            if (!inTransition || isInParticipant(initPrev, mTmpTransitionParticipants)) {
                result.add(initPrev);
            }
            // prevAdjacentTF is adjacent to another one
            final ActivityRecord prevAdjacentActivity = prevAdjacentTF.getTopMostActivity();
            if (prevAdjacentActivity != null && (!inTransition
                    || isInParticipant(prevAdjacentActivity, mTmpTransitionParticipants))) {
                result.add(prevAdjacentActivity);
            }
        }
    }

    static boolean isInParticipant(ActivityRecord ar,
            ArrayList<WindowContainer> transitionParticipants) {
        for (int i = transitionParticipants.size() - 1; i >= 0; --i) {
            final WindowContainer wc = transitionParticipants.get(i);
            if (ar == wc || ar.isDescendantOf(wc)) {
                return true;
            }
        }
        return false;
    }

    private void adjustSavedFileOrder(Task nextTopTask) {
        nextTopTask.forAllActivities(ar -> {
            final UserSavedFile usf = findSavedFile(ar);
            if (usf != null) {
                mSavedFilesInOrder.remove(usf);
                mSavedFilesInOrder.add(usf);
            }
        }, false /* traverseTopToBottom */);
    }

    @Override
    void onAppRemoved(ActivityRecord activity) {
        if (shouldDisableSnapshots()) {
            return;
        }
        removeIfUserSavedFileExist(activity);
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#onAppRemoved delete snapshot " + activity);
        }
    }

    @Override
    void onAppDied(ActivityRecord activity) {
        if (shouldDisableSnapshots()) {
            return;
        }
        removeIfUserSavedFileExist(activity);
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#onAppDied delete snapshot " + activity);
        }
    }

    @Override
    ActivityRecord getTopActivity(ActivityRecord activity) {
        return activity;
    }

    @Override
    ActivityManager.TaskDescription getTaskDescription(ActivityRecord object) {
        return object.taskDescription;
    }

    /**
     * Find the window for a given activity to take a snapshot. During app transitions, trampoline
     * activities can appear in the children, but should be ignored.
     */
    @Override
    protected ActivityRecord findAppTokenForSnapshot(ActivityRecord activity) {
        if (activity == null) {
            return null;
        }
        return activity.canCaptureSnapshot() ? activity : null;
    }

    @Override
    protected boolean use16BitFormat() {
        return mPersistInfoProvider.use16BitFormat();
    }

    @Override
    protected Rect getLetterboxInsets(ActivityRecord topActivity) {
        // Do not capture letterbox for ActivityRecord
        return Letterbox.EMPTY_RECT;
    }

    @NonNull
    private SparseArray<UserSavedFile> getUserFiles(int userId) {
        if (mUserSavedFiles.get(userId) == null) {
            mUserSavedFiles.put(userId, new SparseArray<>());
            // This is the first time this user attempt to access snapshot, clear up the disk.
            cleanUpUserFiles(userId);
        }
        return mUserSavedFiles.get(userId);
    }

    UserSavedFile findSavedFile(@NonNull ActivityRecord ar) {
        final int code = getSystemHashCode(ar);
        return findSavedFile(ar.mUserId, code);
    }

    UserSavedFile findSavedFile(int userId, int code) {
        final SparseArray<UserSavedFile> usfs = getUserFiles(userId);
        return usfs.get(code);
    }

    private void removeCachedFiles(ActivityRecord ar) {
        final UserSavedFile usf = findSavedFile(ar);
        if (usf != null) {
            for (int i = usf.mActivityIds.size() - 1; i >= 0; --i) {
                final int activityId = usf.mActivityIds.get(i);
                mCache.onIdRemoved(activityId);
            }
        }
    }

    private void removeIfUserSavedFileExist(ActivityRecord ar) {
        final UserSavedFile usf = findSavedFile(ar);
        if (usf != null) {
            final SparseArray<UserSavedFile> usfs = getUserFiles(ar.mUserId);
            for (int i = usf.mActivityIds.size() - 1; i >= 0; --i) {
                final int activityId = usf.mActivityIds.get(i);
                usf.remove(activityId);
                mCache.onIdRemoved(activityId);
                usfs.remove(activityId);
            }
            mSavedFilesInOrder.remove(usf);
            mPersister.removeSnapshot(usf.mFileId, ar.mUserId);
        }
    }

    @VisibleForTesting
    boolean hasRecord(@NonNull ActivityRecord ar) {
        return findSavedFile(ar) != null;
    }

    @VisibleForTesting
    void addUserSavedFile(int userId, TaskSnapshot snapshot, @NonNull int[] code) {
        final UserSavedFile savedFile = findSavedFile(userId, code[0]);
        if (savedFile != null) {
            Slog.w(TAG, "Duplicate request for recording activity snapshot " + savedFile);
            return;
        }
        int fileId = 0;
        for (int i = code.length - 1; i >= 0; --i) {
            fileId ^= code[i];
        }
        final UserSavedFile usf = new UserSavedFile(fileId, userId);
        SparseArray<UserSavedFile> usfs = getUserFiles(userId);
        for (int i = code.length - 1; i >= 0; --i) {
            usfs.put(code[i], usf);
        }
        usf.mActivityIds.addAll(code);
        mSavedFilesInOrder.add(usf);
        mPersister.persistSnapshot(fileId, userId, snapshot);

        if (mSavedFilesInOrder.size() > MAX_PERSIST_SNAPSHOT_COUNT * 2) {
            purgeSavedFile();
        }
    }

    private void purgeSavedFile() {
        final int savedFileCount = mSavedFilesInOrder.size();
        final int removeCount = savedFileCount - MAX_PERSIST_SNAPSHOT_COUNT;
        if (removeCount < 1) {
            return;
        }

        final ArrayList<UserSavedFile> removeTargets = new ArrayList<>();
        for (int i = removeCount - 1; i >= 0; --i) {
            final UserSavedFile usf = mSavedFilesInOrder.remove(i);
            final SparseArray<UserSavedFile> files = mUserSavedFiles.get(usf.mUserId);
            for (int j = usf.mActivityIds.size() - 1; j >= 0; --j) {
                mCache.removeRunningEntry(usf.mActivityIds.get(j));
                files.remove(usf.mActivityIds.get(j));
            }
            removeTargets.add(usf);
        }
        for (int i = removeTargets.size() - 1; i >= 0; --i) {
            final UserSavedFile usf = removeTargets.get(i);
            mPersister.removeSnapshot(usf.mFileId, usf.mUserId);
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        final String doublePrefix = prefix + "  ";
        final String triplePrefix = doublePrefix + "  ";
        for (int i = mUserSavedFiles.size() - 1; i >= 0; --i) {
            final SparseArray<UserSavedFile> usfs = mUserSavedFiles.valueAt(i);
            pw.println(doublePrefix + "UserSavedFile userId=" + mUserSavedFiles.keyAt(i));
            final ArraySet<UserSavedFile> sets = new ArraySet<>();
            for (int j = usfs.size() - 1; j >= 0; --j) {
                sets.add(usfs.valueAt(j));
            }
            for (int j = sets.size() - 1; j >= 0; --j) {
                pw.println(triplePrefix + "SavedFile=" + sets.valueAt(j));
            }
        }
    }

    static class UserSavedFile {
        // The unique id as filename.
        final int mFileId;
        final int mUserId;

        /**
         * The Id of all activities which are includes in the snapshot.
         */
        final IntArray mActivityIds = new IntArray();

        UserSavedFile(int fileId, int userId) {
            mFileId = fileId;
            mUserId = userId;
        }

        boolean contains(int code) {
            return mActivityIds.contains(code);
        }

        void remove(int code) {
            final int index = mActivityIds.indexOf(code);
            if (index >= 0) {
                mActivityIds.remove(index);
            }
        }

        ActivityRecord[] filterExistActivities(
                @NonNull ArraySet<ActivityRecord> pendingLoadActivity) {
            ArrayList<ActivityRecord> matchedActivities = null;
            for (int i = pendingLoadActivity.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = pendingLoadActivity.valueAt(i);
                if (contains(getSystemHashCode(ar))) {
                    if (matchedActivities == null) {
                        matchedActivities = new ArrayList<>();
                    }
                    matchedActivities.add(ar);
                }
            }
            if (matchedActivities == null || matchedActivities.size() != mActivityIds.size()) {
                return null;
            }
            return matchedActivities.toArray(new ActivityRecord[0]);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("UserSavedFile{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" fileId=");
            sb.append(Integer.toHexString(mFileId));
            sb.append(", activityIds=[");
            for (int i = mActivityIds.size() - 1; i >= 0; --i) {
                sb.append(Integer.toHexString(mActivityIds.get(i)));
                if (i > 0) {
                    sb.append(',');
                }
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        }
    }
}
