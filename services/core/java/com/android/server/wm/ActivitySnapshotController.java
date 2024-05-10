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
import android.os.Environment;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.BaseAppSnapshotPersister.PersistInfoProvider;

import java.io.File;
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
        initialize(new ActivitySnapshotCache(service));

        final boolean snapshotEnabled =
                !service.mContext
                        .getResources()
                        .getBoolean(com.android.internal.R.bool.config_disableTaskSnapshots)
                && isSnapshotEnabled()
                && !ActivityManager.isLowRamDeviceStatic(); // Don't support Android Go
        setSnapshotEnabled(snapshotEnabled);
    }

    @Override
    protected float initSnapshotScale() {
        final float config = mService.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_resActivitySnapshotScale);
        return Math.max(Math.min(config, 1f), 0.1f);
    }

    // TODO remove when enabled
    static boolean isSnapshotEnabled() {
        return SystemProperties.getInt("persist.wm.debug.activity_screenshot", 0) != 0;
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

    /** Retrieves a snapshot for an activity from cache. */
    @Nullable
    TaskSnapshot getSnapshot(ActivityRecord ar) {
        final int code = getSystemHashCode(ar);
        return mCache.getSnapshot(code);
    }

    private void cleanUpUserFiles(int userId) {
        synchronized (mSnapshotPersistQueue.getLock()) {
            mSnapshotPersistQueue.sendToQueueLocked(
                    new SnapshotPersistQueue.WriteQueueItem(mPersistInfoProvider) {
                        @Override
                        boolean isReady() {
                            final UserManagerInternal mUserManagerInternal =
                                    LocalServices.getService(UserManagerInternal.class);
                            return mUserManagerInternal.isUserUnlocked(userId);
                        }

                        @Override
                        void write() {
                            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "cleanUpUserFiles");
                            final File file = mPersistInfoProvider.getDirectory(userId);
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
        for (int i = mPendingLoadActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingLoadActivity.valueAt(i);
            final int code = getSystemHashCode(ar);
            final int userId = ar.mUserId;
            if (mCache.getSnapshot(code) != null) {
                // already in cache, skip
                continue;
            }
            if (containsFile(code, userId)) {
                synchronized (mSnapshotPersistQueue.getLock()) {
                    mSnapshotPersistQueue.insertQueueAtFirstLocked(
                            new LoadActivitySnapshotItem(ar, code, userId, mPersistInfoProvider));
                }
            }
        }
        // clear mTmpRemoveActivity from cache
        for (int i = mPendingRemoveActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingRemoveActivity.valueAt(i);
            final int code = getSystemHashCode(ar);
            mCache.onIdRemoved(code);
        }
        // clear snapshot on cache and delete files
        for (int i = mPendingDeleteActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingDeleteActivity.valueAt(i);
            final int code = getSystemHashCode(ar);
            mCache.onIdRemoved(code);
            removeIfUserSavedFileExist(code, ar.mUserId);
        }
        // don't keep any reference
        resetTmpFields();
    }

    class LoadActivitySnapshotItem extends SnapshotPersistQueue.WriteQueueItem {
        private final int mCode;
        private final int mUserId;
        private final ActivityRecord mActivityRecord;

        LoadActivitySnapshotItem(@NonNull ActivityRecord ar, int code, int userId,
                @NonNull PersistInfoProvider persistInfoProvider) {
            super(persistInfoProvider);
            mActivityRecord = ar;
            mCode = code;
            mUserId = userId;
        }

        @Override
        void write() {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "load_activity_snapshot");
            final TaskSnapshot snapshot = mSnapshotLoader.loadTask(mCode,
                    mUserId, false /* loadLowResolutionBitmap */);
            synchronized (mService.getWindowManagerLock()) {
                if (snapshot != null && !mActivityRecord.finishing) {
                    mCache.putSnapshot(mActivityRecord, snapshot);
                }
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            final LoadActivitySnapshotItem other = (LoadActivitySnapshotItem) o;
            return mCode == other.mCode && mUserId == other.mUserId
                    && mPersistInfoProvider == other.mPersistInfoProvider;
        }
    }

    void recordSnapshot(ActivityRecord activity) {
        if (shouldDisableSnapshots()) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#recordSnapshot " + activity);
        }
        final TaskSnapshot snapshot = recordSnapshotInner(activity);
        if (snapshot != null) {
            final int code = getSystemHashCode(activity);
            addUserSavedFile(code, activity.mUserId, snapshot);
        }
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

    private static int getSystemHashCode(ActivityRecord activity) {
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
            addBelowActivityIfExist(ar, mPendingLoadActivity, true, "load-snapshot");
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
        final int userId = nextTopTask.mUserId;
        nextTopTask.forAllActivities(ar -> {
            final int code = getSystemHashCode(ar);
            final UserSavedFile usf = getUserFiles(userId).get(code);
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
        super.onAppRemoved(activity);
        final int code = getSystemHashCode(activity);
        removeIfUserSavedFileExist(code, activity.mUserId);
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#onAppRemoved delete snapshot " + activity);
        }
    }

    @Override
    void onAppDied(ActivityRecord activity) {
        if (shouldDisableSnapshots()) {
            return;
        }
        super.onAppDied(activity);
        final int code = getSystemHashCode(activity);
        removeIfUserSavedFileExist(code, activity.mUserId);
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#onAppDied delete snapshot " + activity);
        }
    }

    @Override
    ActivityRecord getTopActivity(ActivityRecord activity) {
        return activity;
    }

    @Override
    ActivityRecord getTopFullscreenActivity(ActivityRecord activity) {
        final WindowState win = activity.findMainWindow();
        return (win != null && win.mAttrs.isFullscreen()) ? activity : null;
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

    @NonNull
    private SparseArray<UserSavedFile> getUserFiles(int userId) {
        if (mUserSavedFiles.get(userId) == null) {
            mUserSavedFiles.put(userId, new SparseArray<>());
            // This is the first time this user attempt to access snapshot, clear up the disk.
            cleanUpUserFiles(userId);
        }
        return mUserSavedFiles.get(userId);
    }

    private void removeIfUserSavedFileExist(int code, int userId) {
        final UserSavedFile usf = getUserFiles(userId).get(code);
        if (usf != null) {
            mUserSavedFiles.get(userId).remove(code);
            mSavedFilesInOrder.remove(usf);
            mPersister.removeSnapshot(code, userId);
        }
    }

    private boolean containsFile(int code, int userId) {
        return getUserFiles(userId).get(code) != null;
    }

    private void addUserSavedFile(int code, int userId, TaskSnapshot snapshot) {
        final SparseArray<UserSavedFile> savedFiles = getUserFiles(userId);
        final UserSavedFile savedFile = savedFiles.get(code);
        if (savedFile == null) {
            final UserSavedFile usf = new UserSavedFile(code, userId);
            savedFiles.put(code, usf);
            mSavedFilesInOrder.add(usf);
            mPersister.persistSnapshot(code, userId, snapshot);

            if (mSavedFilesInOrder.size() > MAX_PERSIST_SNAPSHOT_COUNT * 2) {
                purgeSavedFile();
            }
        }
    }

    private void purgeSavedFile() {
        final int savedFileCount = mSavedFilesInOrder.size();
        final int removeCount = savedFileCount - MAX_PERSIST_SNAPSHOT_COUNT;
        final ArrayList<UserSavedFile> usfs = new ArrayList<>();
        if (removeCount > 0) {
            final int removeTillIndex = savedFileCount - removeCount;
            for (int i = savedFileCount - 1; i > removeTillIndex; --i) {
                final UserSavedFile usf = mSavedFilesInOrder.remove(i);
                if (usf != null) {
                    final SparseArray<UserSavedFile> records = getUserFiles(usf.mUserId);
                    records.remove(usf.mFileId);
                    usfs.add(usf);
                }
            }
        }
        if (usfs.size() > 0) {
            removeSnapshotFiles(usfs);
        }
    }

    private void removeSnapshotFiles(ArrayList<UserSavedFile> files) {
        synchronized (mSnapshotPersistQueue.getLock()) {
            mSnapshotPersistQueue.sendToQueueLocked(
                    new SnapshotPersistQueue.WriteQueueItem(mPersistInfoProvider) {
                        @Override
                        void write() {
                            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "activity_remove_files");
                            for (int i = files.size() - 1; i >= 0; --i) {
                                final UserSavedFile usf = files.get(i);
                                mSnapshotPersistQueue.deleteSnapshot(
                                        usf.mFileId, usf.mUserId, mPersistInfoProvider);
                            }
                            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                        }
                    });
        }
    }

    static class UserSavedFile {
        int mFileId;
        int mUserId;
        UserSavedFile(int fileId, int userId) {
            mFileId = fileId;
            mUserId = userId;
        }
    }
}
