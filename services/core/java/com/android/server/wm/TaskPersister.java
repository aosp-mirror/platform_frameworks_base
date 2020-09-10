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

package com.android.server.wm;

import static com.android.server.wm.RootWindowContainer.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Persister that saves recent tasks into disk.
 */
public class TaskPersister implements PersisterQueue.Listener {
    static final String TAG = "TaskPersister";
    static final boolean DEBUG = false;
    static final String IMAGE_EXTENSION = ".png";

    private static final String TASKS_DIRNAME = "recent_tasks";
    private static final String TASK_FILENAME_SUFFIX = "_task.xml";
    private static final String IMAGES_DIRNAME = "recent_images";
    private static final String PERSISTED_TASK_IDS_FILENAME = "persisted_taskIds.txt";

    private static final String TAG_TASK = "task";

    private final ActivityTaskManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private final RecentTasks mRecentTasks;
    private final SparseArray<SparseBooleanArray> mTaskIdsInFile = new SparseArray<>();
    private final File mTaskIdsDir;
    // To lock file operations in TaskPersister
    private final Object mIoLock = new Object();
    private final PersisterQueue mPersisterQueue;

    private final ArraySet<Integer> mTmpTaskIds = new ArraySet<>();

    TaskPersister(File systemDir, ActivityStackSupervisor stackSupervisor,
            ActivityTaskManagerService service, RecentTasks recentTasks,
            PersisterQueue persisterQueue) {

        final File legacyImagesDir = new File(systemDir, IMAGES_DIRNAME);
        if (legacyImagesDir.exists()) {
            if (!FileUtils.deleteContents(legacyImagesDir) || !legacyImagesDir.delete()) {
                Slog.i(TAG, "Failure deleting legacy images directory: " + legacyImagesDir);
            }
        }

        final File legacyTasksDir = new File(systemDir, TASKS_DIRNAME);
        if (legacyTasksDir.exists()) {
            if (!FileUtils.deleteContents(legacyTasksDir) || !legacyTasksDir.delete()) {
                Slog.i(TAG, "Failure deleting legacy tasks directory: " + legacyTasksDir);
            }
        }

        mTaskIdsDir = new File(Environment.getDataDirectory(), "system_de");
        mStackSupervisor = stackSupervisor;
        mService = service;
        mRecentTasks = recentTasks;
        mPersisterQueue = persisterQueue;
        mPersisterQueue.addListener(this);
    }

    @VisibleForTesting
    TaskPersister(File workingDir) {
        mTaskIdsDir = workingDir;
        mStackSupervisor = null;
        mService = null;
        mRecentTasks = null;
        mPersisterQueue = new PersisterQueue();
        mPersisterQueue.addListener(this);
    }

    private void removeThumbnails(Task task) {
        mPersisterQueue.removeItems(
                item -> {
                    File file = new File(item.mFilePath);
                    return file.getName().startsWith(Integer.toString(task.mTaskId));
                },
                ImageWriteQueueItem.class);
    }

    @NonNull
    SparseBooleanArray loadPersistedTaskIdsForUser(int userId) {
        if (mTaskIdsInFile.get(userId) != null) {
            return mTaskIdsInFile.get(userId).clone();
        }
        final SparseBooleanArray persistedTaskIds = new SparseBooleanArray();
        synchronized (mIoLock) {
            BufferedReader reader = null;
            String line;
            try {
                reader = new BufferedReader(new FileReader(getUserPersistedTaskIdsFile(userId)));
                while ((line = reader.readLine()) != null) {
                    for (String taskIdString : line.split("\\s+")) {
                        int id = Integer.parseInt(taskIdString);
                        persistedTaskIds.put(id, true);
                    }
                }
            } catch (FileNotFoundException e) {
                // File doesn't exist. Ignore.
            } catch (Exception e) {
                Slog.e(TAG, "Error while reading taskIds file for user " + userId, e);
            } finally {
                IoUtils.closeQuietly(reader);
            }
        }
        mTaskIdsInFile.put(userId, persistedTaskIds);
        return persistedTaskIds.clone();
    }


    @VisibleForTesting
    void writePersistedTaskIdsForUser(@NonNull SparseBooleanArray taskIds, int userId) {
        if (userId < 0) {
            return;
        }
        final File persistedTaskIdsFile = getUserPersistedTaskIdsFile(userId);
        synchronized (mIoLock) {
            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(new FileWriter(persistedTaskIdsFile));
                for (int i = 0; i < taskIds.size(); i++) {
                    if (taskIds.valueAt(i)) {
                        writer.write(String.valueOf(taskIds.keyAt(i)));
                        writer.newLine();
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error while writing taskIds file for user " + userId, e);
            } finally {
                IoUtils.closeQuietly(writer);
            }
        }
    }

    void unloadUserDataFromMemory(int userId) {
        mTaskIdsInFile.delete(userId);
    }

    void wakeup(Task task, boolean flush) {
        synchronized (mPersisterQueue) {
            if (task != null) {
                final TaskWriteQueueItem item = mPersisterQueue.findLastItem(
                        queueItem -> task == queueItem.mTask, TaskWriteQueueItem.class);
                if (item != null && !task.inRecents) {
                    removeThumbnails(task);
                }

                if (item == null && task.isPersistable) {
                    mPersisterQueue.addItem(new TaskWriteQueueItem(task, mService), flush);
                }
            } else {
                // Dummy. Ensures removeObsoleteFiles is called when LazyTaskThreadWriter is
                // notified.
                mPersisterQueue.addItem(PersisterQueue.EMPTY_ITEM, flush);
            }
            if (DEBUG) {
                Slog.d(TAG, "wakeup: task=" + task + " flush=" + flush + " Callers="
                        + Debug.getCallers(4));
            }
        }

        mPersisterQueue.yieldIfQueueTooDeep();
    }

    void flush() {
        mPersisterQueue.flush();
    }

    void saveImage(Bitmap image, String filePath) {
        mPersisterQueue.updateLastOrAddItem(new ImageWriteQueueItem(filePath, image),
                /* flush */ false);
        if (DEBUG) {
            Slog.d(TAG, "saveImage: filePath=" + filePath + " now="
                    + SystemClock.uptimeMillis() + " Callers=" + Debug.getCallers(4));
        }
    }

    Bitmap getTaskDescriptionIcon(String filePath) {
        // See if it is in the write queue
        final Bitmap icon = getImageFromWriteQueue(filePath);
        if (icon != null) {
            return icon;
        }
        return restoreImage(filePath);
    }

    private Bitmap getImageFromWriteQueue(String filePath) {
        final ImageWriteQueueItem item = mPersisterQueue.findLastItem(
                queueItem -> queueItem.mFilePath.equals(filePath), ImageWriteQueueItem.class);
        return item != null ? item.mImage : null;
    }

    private String fileToString(File file) {
        final String newline = System.lineSeparator();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuffer sb = new StringBuffer((int) file.length() * 2);
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line + newline);
            }
            reader.close();
            return sb.toString();
        } catch (IOException ioe) {
            Slog.e(TAG, "Couldn't read file " + file.getName());
            return null;
        }
    }

    private Task taskIdToTask(int taskId, ArrayList<Task> tasks) {
        if (taskId < 0) {
            return null;
        }
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = tasks.get(taskNdx);
            if (task.mTaskId == taskId) {
                return task;
            }
        }
        Slog.e(TAG, "Restore affiliation error looking for taskId=" + taskId);
        return null;
    }

    List<Task> restoreTasksForUserLocked(final int userId, SparseBooleanArray preaddedTasks) {
        final ArrayList<Task> tasks = new ArrayList<Task>();
        ArraySet<Integer> recoveredTaskIds = new ArraySet<Integer>();

        File userTasksDir = getUserTasksDir(userId);

        File[] recentFiles = userTasksDir.listFiles();
        if (recentFiles == null) {
            Slog.e(TAG, "restoreTasksForUserLocked: Unable to list files from " + userTasksDir);
            return tasks;
        }

        for (int taskNdx = 0; taskNdx < recentFiles.length; ++taskNdx) {
            File taskFile = recentFiles[taskNdx];
            if (DEBUG) {
                Slog.d(TAG, "restoreTasksForUserLocked: userId=" + userId
                        + ", taskFile=" + taskFile.getName());
            }

            if (!taskFile.getName().endsWith(TASK_FILENAME_SUFFIX)) {
                continue;
            }
            try {
                final int taskId = Integer.parseInt(taskFile.getName().substring(
                        0 /* beginIndex */,
                        taskFile.getName().length() - TASK_FILENAME_SUFFIX.length()));
                if (preaddedTasks.get(taskId, false)) {
                    Slog.w(TAG, "Task #" + taskId +
                            " has already been created so we don't restore again");
                    continue;
                }
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Unexpected task file name", e);
                continue;
            }

            BufferedReader reader = null;
            boolean deleteFile = false;
            try {
                reader = new BufferedReader(new FileReader(taskFile));
                final XmlPullParser in = Xml.newPullParser();
                in.setInput(reader);

                int event;
                while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                        event != XmlPullParser.END_TAG) {
                    final String name = in.getName();
                    if (event == XmlPullParser.START_TAG) {
                        if (DEBUG) Slog.d(TAG, "restoreTasksForUserLocked: START_TAG name=" + name);
                        if (TAG_TASK.equals(name)) {
                            final Task task = Task.restoreFromXml(in, mStackSupervisor);
                            if (DEBUG) Slog.d(TAG, "restoreTasksForUserLocked: restored task="
                                    + task);
                            if (task != null) {
                                // XXX Don't add to write queue... there is no reason to write
                                // out the stuff we just read, if we don't write it we will
                                // read the same thing again.
                                // mWriteQueue.add(new TaskWriteQueueItem(task));

                                final int taskId = task.mTaskId;
                                if (mService.mRootWindowContainer.anyTaskForId(taskId,
                                        MATCH_TASK_IN_STACKS_OR_RECENT_TASKS) != null) {
                                    // Should not happen.
                                    Slog.wtf(TAG, "Existing task with taskId " + taskId + "found");
                                } else if (userId != task.mUserId) {
                                    // Should not happen.
                                    Slog.wtf(TAG, "Task with userId " + task.mUserId + " found in "
                                            + userTasksDir.getAbsolutePath());
                                } else {
                                    // Looks fine.
                                    mStackSupervisor.setNextTaskIdForUser(taskId, userId);
                                    task.isPersistable = true;
                                    tasks.add(task);
                                    recoveredTaskIds.add(taskId);
                                }
                            } else {
                                Slog.e(TAG, "restoreTasksForUserLocked: Unable to restore taskFile="
                                        + taskFile + ": " + fileToString(taskFile));
                            }
                        } else {
                            Slog.wtf(TAG, "restoreTasksForUserLocked: Unknown xml event=" + event
                                    + " name=" + name);
                        }
                    }
                    XmlUtils.skipCurrentTag(in);
                }
            } catch (Exception e) {
                Slog.wtf(TAG, "Unable to parse " + taskFile + ". Error ", e);
                Slog.e(TAG, "Failing file: " + fileToString(taskFile));
                deleteFile = true;
            } finally {
                IoUtils.closeQuietly(reader);
                if (deleteFile) {
                    if (DEBUG) Slog.d(TAG, "Deleting file=" + taskFile.getName());
                    taskFile.delete();
                }
            }
        }

        if (!DEBUG) {
            removeObsoleteFiles(recoveredTaskIds, userTasksDir.listFiles());
        }

        // Fix up task affiliation from taskIds
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = tasks.get(taskNdx);
            task.setPrevAffiliate(taskIdToTask(task.mPrevAffiliateTaskId, tasks));
            task.setNextAffiliate(taskIdToTask(task.mNextAffiliateTaskId, tasks));
        }

        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task lhs, Task rhs) {
                final long diff = rhs.mLastTimeMoved - lhs.mLastTimeMoved;
                if (diff < 0) {
                    return -1;
                } else if (diff > 0) {
                    return +1;
                } else {
                    return 0;
                }
            }
        });
        return tasks;
    }

    @Override
    public void onPreProcessItem(boolean queueEmpty) {
        // We can't lock mService while locking the queue, but we don't want to
        // call removeObsoleteFiles before every item, only the last time
        // before going to sleep. The risk is that we call removeObsoleteFiles()
        // successively.
        if (queueEmpty) {
            if (DEBUG) Slog.d(TAG, "Looking for obsolete files.");
            mTmpTaskIds.clear();
            synchronized (mService.mGlobalLock) {
                if (DEBUG) Slog.d(TAG, "mRecents=" + mRecentTasks);
                mRecentTasks.getPersistableTaskIds(mTmpTaskIds);
                mService.mWindowManager.removeObsoleteTaskFiles(mTmpTaskIds,
                        mRecentTasks.usersWithRecentsLoadedLocked());
            }
            removeObsoleteFiles(mTmpTaskIds);
        }
        writeTaskIdsFiles();
    }

    private static void removeObsoleteFiles(ArraySet<Integer> persistentTaskIds, File[] files) {
        if (DEBUG) Slog.d(TAG, "removeObsoleteFiles: persistentTaskIds=" + persistentTaskIds +
                " files=" + files);
        if (files == null) {
            Slog.e(TAG, "File error accessing recents directory (directory doesn't exist?).");
            return;
        }
        for (int fileNdx = 0; fileNdx < files.length; ++fileNdx) {
            File file = files[fileNdx];
            String filename = file.getName();
            final int taskIdEnd = filename.indexOf('_');
            if (taskIdEnd > 0) {
                final int taskId;
                try {
                    taskId = Integer.parseInt(filename.substring(0, taskIdEnd));
                    if (DEBUG) Slog.d(TAG, "removeObsoleteFiles: Found taskId=" + taskId);
                } catch (Exception e) {
                    Slog.wtf(TAG, "removeObsoleteFiles: Can't parse file=" + file.getName());
                    file.delete();
                    continue;
                }
                if (!persistentTaskIds.contains(taskId)) {
                    if (DEBUG) Slog.d(TAG, "removeObsoleteFiles: deleting file=" + file.getName());
                    file.delete();
                }
            }
        }
    }

    private void writeTaskIdsFiles() {
        SparseArray<SparseBooleanArray> changedTaskIdsPerUser = new SparseArray<>();
        synchronized (mService.mGlobalLock) {
            for (int userId : mRecentTasks.usersWithRecentsLoadedLocked()) {
                SparseBooleanArray taskIdsToSave = mRecentTasks.getTaskIdsForUser(userId);
                SparseBooleanArray persistedIdsInFile = mTaskIdsInFile.get(userId);
                if (persistedIdsInFile != null && persistedIdsInFile.equals(taskIdsToSave)) {
                    continue;
                } else {
                    SparseBooleanArray taskIdsToSaveCopy = taskIdsToSave.clone();
                    mTaskIdsInFile.put(userId, taskIdsToSaveCopy);
                    changedTaskIdsPerUser.put(userId, taskIdsToSaveCopy);
                }
            }
        }
        for (int i = 0; i < changedTaskIdsPerUser.size(); i++) {
            writePersistedTaskIdsForUser(changedTaskIdsPerUser.valueAt(i),
                    changedTaskIdsPerUser.keyAt(i));
        }
    }

    private void removeObsoleteFiles(ArraySet<Integer> persistentTaskIds) {
        int[] candidateUserIds;
        synchronized (mService.mGlobalLock) {
            // Remove only from directories of the users who have recents in memory synchronized
            // with persistent storage.
            candidateUserIds = mRecentTasks.usersWithRecentsLoadedLocked();
        }
        for (int userId : candidateUserIds) {
            removeObsoleteFiles(persistentTaskIds, getUserImagesDir(userId).listFiles());
            removeObsoleteFiles(persistentTaskIds, getUserTasksDir(userId).listFiles());
        }
    }

    static Bitmap restoreImage(String filename) {
        if (DEBUG) Slog.d(TAG, "restoreImage: restoring " + filename);
        return BitmapFactory.decodeFile(filename);
    }

    private File getUserPersistedTaskIdsFile(int userId) {
        File userTaskIdsDir = new File(mTaskIdsDir, String.valueOf(userId));
        if (!userTaskIdsDir.exists() && !userTaskIdsDir.mkdirs()) {
            Slog.e(TAG, "Error while creating user directory: " + userTaskIdsDir);
        }
        return new File(userTaskIdsDir, PERSISTED_TASK_IDS_FILENAME);
    }

    private static File getUserTasksDir(int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), TASKS_DIRNAME);
    }

    static File getUserImagesDir(int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), IMAGES_DIRNAME);
    }

    private static boolean createParentDirectory(String filePath) {
        File parentDir = new File(filePath).getParentFile();
        return parentDir.exists() || parentDir.mkdirs();
    }

    private static class TaskWriteQueueItem implements PersisterQueue.WriteQueueItem {
        private final ActivityTaskManagerService mService;
        private final Task mTask;

        TaskWriteQueueItem(Task task, ActivityTaskManagerService service) {
            mTask = task;
            mService = service;
        }

        private StringWriter saveToXml(Task task) throws Exception {
            if (DEBUG) Slog.d(TAG, "saveToXml: task=" + task);
            final XmlSerializer xmlSerializer = new FastXmlSerializer();
            StringWriter stringWriter = new StringWriter();
            xmlSerializer.setOutput(stringWriter);

            if (DEBUG) {
                xmlSerializer.setFeature(
                        "http://xmlpull.org/v1/doc/features.html#indent-output", true);
            }

            // save task
            xmlSerializer.startDocument(null, true);

            xmlSerializer.startTag(null, TAG_TASK);
            task.saveToXml(xmlSerializer);
            xmlSerializer.endTag(null, TAG_TASK);

            xmlSerializer.endDocument();
            xmlSerializer.flush();

            return stringWriter;
        }

        @Override
        public void process() {
            // Write out one task.
            StringWriter stringWriter = null;
            Task task = mTask;
            if (DEBUG) Slog.d(TAG, "Writing task=" + task);
            synchronized (mService.mGlobalLock) {
                if (task.inRecents) {
                    // Still there.
                    try {
                        if (DEBUG) Slog.d(TAG, "Saving task=" + task);
                        stringWriter = saveToXml(task);
                    } catch (Exception e) {
                    }
                }
            }
            if (stringWriter != null) {
                // Write out xml file while not holding mService lock.
                FileOutputStream file = null;
                AtomicFile atomicFile = null;
                try {
                    File userTasksDir = getUserTasksDir(task.mUserId);
                    if (!userTasksDir.isDirectory() && !userTasksDir.mkdirs()) {
                        Slog.e(TAG, "Failure creating tasks directory for user " + task.mUserId
                                + ": " + userTasksDir + " Dropping persistence for task " + task);
                        return;
                    }
                    atomicFile = new AtomicFile(new File(userTasksDir,
                            String.valueOf(task.mTaskId) + TASK_FILENAME_SUFFIX));
                    file = atomicFile.startWrite();
                    file.write(stringWriter.toString().getBytes());
                    file.write('\n');
                    atomicFile.finishWrite(file);
                } catch (IOException e) {
                    if (file != null) {
                        atomicFile.failWrite(file);
                    }
                    Slog.e(TAG,
                            "Unable to open " + atomicFile + " for persisting. " + e);
                }
            }
        }

        @Override
        public String toString() {
            return "TaskWriteQueueItem{task=" + mTask + "}";
        }
    }

    private static class ImageWriteQueueItem implements
            PersisterQueue.WriteQueueItem<ImageWriteQueueItem> {
        final String mFilePath;
        Bitmap mImage;

        ImageWriteQueueItem(String filePath, Bitmap image) {
            mFilePath = filePath;
            mImage = image;
        }

        @Override
        public void process() {
            final String filePath = mFilePath;
            if (!createParentDirectory(filePath)) {
                Slog.e(TAG, "Error while creating images directory for file: " + filePath);
                return;
            }
            final Bitmap bitmap = mImage;
            if (DEBUG) Slog.d(TAG, "writing bitmap: filename=" + filePath);
            FileOutputStream imageFile = null;
            try {
                imageFile = new FileOutputStream(new File(filePath));
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, imageFile);
            } catch (Exception e) {
                Slog.e(TAG, "saveImage: unable to save " + filePath, e);
            } finally {
                IoUtils.closeQuietly(imageFile);
            }
        }

        @Override
        public boolean matches(ImageWriteQueueItem item) {
            return mFilePath.equals(item.mFilePath);
        }

        @Override
        public void updateFrom(ImageWriteQueueItem item) {
            mImage = item.mImage;
        }

        @Override
        public String toString() {
            return "ImageWriteQueueItem{path=" + mFilePath
                    + ", image=(" + mImage.getWidth() + "x" + mImage.getHeight() + ")}";
        }
    }
}
