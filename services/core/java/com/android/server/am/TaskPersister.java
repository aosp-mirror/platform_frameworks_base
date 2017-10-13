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

package com.android.server.am;

import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
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
import org.xmlpull.v1.XmlPullParserException;
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

import static com.android.server.am.ActivityStackSupervisor.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS;

public class TaskPersister {
    static final String TAG = "TaskPersister";
    static final boolean DEBUG = false;

    /** When not flushing don't write out files faster than this */
    private static final long INTER_WRITE_DELAY_MS = 500;

    /**
     * When not flushing delay this long before writing the first file out. This gives the next task
     * being launched a chance to load its resources without this occupying IO bandwidth.
     */
    private static final long PRE_TASK_DELAY_MS = 3000;

    /** The maximum number of entries to keep in the queue before draining it automatically. */
    private static final int MAX_WRITE_QUEUE_LENGTH = 6;

    /** Special value for mWriteTime to mean don't wait, just write */
    private static final long FLUSH_QUEUE = -1;

    private static final String TASKS_DIRNAME = "recent_tasks";
    private static final String TASK_FILENAME_SUFFIX = "_task.xml";
    private static final String IMAGES_DIRNAME = "recent_images";
    private static final String PERSISTED_TASK_IDS_FILENAME = "persisted_taskIds.txt";
    static final String IMAGE_EXTENSION = ".png";

    private static final String TAG_TASK = "task";

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private final RecentTasks mRecentTasks;
    private final SparseArray<SparseBooleanArray> mTaskIdsInFile = new SparseArray<>();
    private final File mTaskIdsDir;
    // To lock file operations in TaskPersister
    private final Object mIoLock = new Object();

    /**
     * Value determines write delay mode as follows: < 0 We are Flushing. No delays between writes
     * until the image queue is drained and all tasks needing persisting are written to disk. There
     * is no delay between writes. == 0 We are Idle. Next writes will be delayed by
     * #PRE_TASK_DELAY_MS. > 0 We are Actively writing. Next write will be at this time. Subsequent
     * writes will be delayed by #INTER_WRITE_DELAY_MS.
     */
    private long mNextWriteTime = 0;

    private final LazyTaskWriterThread mLazyTaskWriterThread;

    private static class WriteQueueItem {}

    private static class TaskWriteQueueItem extends WriteQueueItem {
        final TaskRecord mTask;

        TaskWriteQueueItem(TaskRecord task) {
            mTask = task;
        }
    }

    private static class ImageWriteQueueItem extends WriteQueueItem {
        final String mFilePath;
        Bitmap mImage;

        ImageWriteQueueItem(String filePath, Bitmap image) {
            mFilePath = filePath;
            mImage = image;
        }
    }

    ArrayList<WriteQueueItem> mWriteQueue = new ArrayList<WriteQueueItem>();

    TaskPersister(File systemDir, ActivityStackSupervisor stackSupervisor,
            ActivityManagerService service, RecentTasks recentTasks) {

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
        mLazyTaskWriterThread = new LazyTaskWriterThread("LazyTaskWriterThread");
    }

    @VisibleForTesting
    TaskPersister(File workingDir) {
        mTaskIdsDir = workingDir;
        mStackSupervisor = null;
        mService = null;
        mRecentTasks = null;
        mLazyTaskWriterThread = new LazyTaskWriterThread("LazyTaskWriterThreadTest");
    }

    void startPersisting() {
        if (!mLazyTaskWriterThread.isAlive()) {
            mLazyTaskWriterThread.start();
        }
    }

    private void removeThumbnails(TaskRecord task) {
        final String taskString = Integer.toString(task.taskId);
        for (int queueNdx = mWriteQueue.size() - 1; queueNdx >= 0; --queueNdx) {
            final WriteQueueItem item = mWriteQueue.get(queueNdx);
            if (item instanceof ImageWriteQueueItem) {
                final File thumbnailFile = new File(((ImageWriteQueueItem) item).mFilePath);
                if (thumbnailFile.getName().startsWith(taskString)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Removing " + ((ImageWriteQueueItem) item).mFilePath +
                                " from write queue");
                    }
                    mWriteQueue.remove(queueNdx);
                }
            }
        }
    }

    private void yieldIfQueueTooDeep() {
        boolean stall = false;
        synchronized (this) {
            if (mNextWriteTime == FLUSH_QUEUE) {
                stall = true;
            }
        }
        if (stall) {
            Thread.yield();
        }
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

    void wakeup(TaskRecord task, boolean flush) {
        synchronized (this) {
            if (task != null) {
                int queueNdx;
                for (queueNdx = mWriteQueue.size() - 1; queueNdx >= 0; --queueNdx) {
                    final WriteQueueItem item = mWriteQueue.get(queueNdx);
                    if (item instanceof TaskWriteQueueItem &&
                            ((TaskWriteQueueItem) item).mTask == task) {
                        if (!task.inRecents) {
                            // This task is being removed.
                            removeThumbnails(task);
                        }
                        break;
                    }
                }
                if (queueNdx < 0 && task.isPersistable) {
                    mWriteQueue.add(new TaskWriteQueueItem(task));
                }
            } else {
                // Dummy. Ensures removeObsoleteFiles is called when LazyTaskThreadWriter is
                // notified.
                mWriteQueue.add(new WriteQueueItem());
            }
            if (flush || mWriteQueue.size() > MAX_WRITE_QUEUE_LENGTH) {
                mNextWriteTime = FLUSH_QUEUE;
            } else if (mNextWriteTime == 0) {
                mNextWriteTime = SystemClock.uptimeMillis() + PRE_TASK_DELAY_MS;
            }
            if (DEBUG) Slog.d(TAG, "wakeup: task=" + task + " flush=" + flush + " mNextWriteTime="
                    + mNextWriteTime + " mWriteQueue.size=" + mWriteQueue.size()
                    + " Callers=" + Debug.getCallers(4));
            notifyAll();
        }

        yieldIfQueueTooDeep();
    }

    void flush() {
        synchronized (this) {
            mNextWriteTime = FLUSH_QUEUE;
            notifyAll();
            do {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            } while (mNextWriteTime == FLUSH_QUEUE);
        }
    }

    void saveImage(Bitmap image, String filePath) {
        synchronized (this) {
            int queueNdx;
            for (queueNdx = mWriteQueue.size() - 1; queueNdx >= 0; --queueNdx) {
                final WriteQueueItem item = mWriteQueue.get(queueNdx);
                if (item instanceof ImageWriteQueueItem) {
                    ImageWriteQueueItem imageWriteQueueItem = (ImageWriteQueueItem) item;
                    if (imageWriteQueueItem.mFilePath.equals(filePath)) {
                        // replace the Bitmap with the new one.
                        imageWriteQueueItem.mImage = image;
                        break;
                    }
                }
            }
            if (queueNdx < 0) {
                mWriteQueue.add(new ImageWriteQueueItem(filePath, image));
            }
            if (mWriteQueue.size() > MAX_WRITE_QUEUE_LENGTH) {
                mNextWriteTime = FLUSH_QUEUE;
            } else if (mNextWriteTime == 0) {
                mNextWriteTime = SystemClock.uptimeMillis() + PRE_TASK_DELAY_MS;
            }
            if (DEBUG) Slog.d(TAG, "saveImage: filePath=" + filePath + " now=" +
                    SystemClock.uptimeMillis() + " mNextWriteTime=" +
                    mNextWriteTime + " Callers=" + Debug.getCallers(4));
            notifyAll();
        }

        yieldIfQueueTooDeep();
    }

    Bitmap getTaskDescriptionIcon(String filePath) {
        // See if it is in the write queue
        final Bitmap icon = getImageFromWriteQueue(filePath);
        if (icon != null) {
            return icon;
        }
        return restoreImage(filePath);
    }

    Bitmap getImageFromWriteQueue(String filePath) {
        synchronized (this) {
            for (int queueNdx = mWriteQueue.size() - 1; queueNdx >= 0; --queueNdx) {
                final WriteQueueItem item = mWriteQueue.get(queueNdx);
                if (item instanceof ImageWriteQueueItem) {
                    ImageWriteQueueItem imageWriteQueueItem = (ImageWriteQueueItem) item;
                    if (imageWriteQueueItem.mFilePath.equals(filePath)) {
                        return imageWriteQueueItem.mImage;
                    }
                }
            }
            return null;
        }
    }

    private StringWriter saveToXml(TaskRecord task) throws IOException, XmlPullParserException {
        if (DEBUG) Slog.d(TAG, "saveToXml: task=" + task);
        final XmlSerializer xmlSerializer = new FastXmlSerializer();
        StringWriter stringWriter = new StringWriter();
        xmlSerializer.setOutput(stringWriter);

        if (DEBUG) xmlSerializer.setFeature(
                "http://xmlpull.org/v1/doc/features.html#indent-output", true);

        // save task
        xmlSerializer.startDocument(null, true);

        xmlSerializer.startTag(null, TAG_TASK);
        task.saveToXml(xmlSerializer);
        xmlSerializer.endTag(null, TAG_TASK);

        xmlSerializer.endDocument();
        xmlSerializer.flush();

        return stringWriter;
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

    private TaskRecord taskIdToTask(int taskId, ArrayList<TaskRecord> tasks) {
        if (taskId < 0) {
            return null;
        }
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = tasks.get(taskNdx);
            if (task.taskId == taskId) {
                return task;
            }
        }
        Slog.e(TAG, "Restore affiliation error looking for taskId=" + taskId);
        return null;
    }

    List<TaskRecord> restoreTasksForUserLocked(final int userId, SparseBooleanArray preaddedTasks) {
        final ArrayList<TaskRecord> tasks = new ArrayList<TaskRecord>();
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
                            final TaskRecord task = TaskRecord.restoreFromXml(in, mStackSupervisor);
                            if (DEBUG) Slog.d(TAG, "restoreTasksForUserLocked: restored task="
                                    + task);
                            if (task != null) {
                                // XXX Don't add to write queue... there is no reason to write
                                // out the stuff we just read, if we don't write it we will
                                // read the same thing again.
                                // mWriteQueue.add(new TaskWriteQueueItem(task));

                                final int taskId = task.taskId;
                                if (mStackSupervisor.anyTaskForIdLocked(taskId,
                                        MATCH_TASK_IN_STACKS_OR_RECENT_TASKS) != null) {
                                    // Should not happen.
                                    Slog.wtf(TAG, "Existing task with taskId " + taskId + "found");
                                } else if (userId != task.userId) {
                                    // Should not happen.
                                    Slog.wtf(TAG, "Task with userId " + task.userId + " found in "
                                            + userTasksDir.getAbsolutePath());
                                } else {
                                    // Looks fine.
                                    mStackSupervisor.setNextTaskIdForUserLocked(taskId, userId);
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
            final TaskRecord task = tasks.get(taskNdx);
            task.setPrevAffiliate(taskIdToTask(task.mPrevAffiliateTaskId, tasks));
            task.setNextAffiliate(taskIdToTask(task.mNextAffiliateTaskId, tasks));
        }

        Collections.sort(tasks, new Comparator<TaskRecord>() {
            @Override
            public int compare(TaskRecord lhs, TaskRecord rhs) {
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
        synchronized (mService) {
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
        synchronized (mService) {
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

    static File getUserTasksDir(int userId) {
        File userTasksDir = new File(Environment.getDataSystemCeDirectory(userId), TASKS_DIRNAME);

        if (!userTasksDir.exists()) {
            if (!userTasksDir.mkdir()) {
                Slog.e(TAG, "Failure creating tasks directory for user " + userId + ": "
                        + userTasksDir);
            }
        }
        return userTasksDir;
    }

    static File getUserImagesDir(int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), IMAGES_DIRNAME);
    }

    private static boolean createParentDirectory(String filePath) {
        File parentDir = new File(filePath).getParentFile();
        return parentDir.exists() || parentDir.mkdirs();
    }

    private class LazyTaskWriterThread extends Thread {

        LazyTaskWriterThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            ArraySet<Integer> persistentTaskIds = new ArraySet<>();
            while (true) {
                // We can't lock mService while holding TaskPersister.this, but we don't want to
                // call removeObsoleteFiles every time through the loop, only the last time before
                // going to sleep. The risk is that we call removeObsoleteFiles() successively.
                final boolean probablyDone;
                synchronized (TaskPersister.this) {
                    probablyDone = mWriteQueue.isEmpty();
                }
                if (probablyDone) {
                    if (DEBUG) Slog.d(TAG, "Looking for obsolete files.");
                    persistentTaskIds.clear();
                    synchronized (mService) {
                        if (DEBUG) Slog.d(TAG, "mRecents=" + mRecentTasks);
                        mRecentTasks.getPersistableTaskIds(persistentTaskIds);
                        mService.mWindowManager.removeObsoleteTaskFiles(persistentTaskIds,
                                mRecentTasks.usersWithRecentsLoadedLocked());
                    }
                    removeObsoleteFiles(persistentTaskIds);
                }
                writeTaskIdsFiles();

                processNextItem();
            }
        }

        private void processNextItem() {
            // This part is extracted into a method so that the GC can clearly see the end of the
            // scope of the variable 'item'.  If this part was in the loop above, the last item
            // it processed would always "leak".
            // See https://b.corp.google.com/issues/64438652#comment7

            // If mNextWriteTime, then don't delay between each call to saveToXml().
            final WriteQueueItem item;
            synchronized (TaskPersister.this) {
                if (mNextWriteTime != FLUSH_QUEUE) {
                    // The next write we don't have to wait so long.
                    mNextWriteTime = SystemClock.uptimeMillis() + INTER_WRITE_DELAY_MS;
                    if (DEBUG) Slog.d(TAG, "Next write time may be in " +
                            INTER_WRITE_DELAY_MS + " msec. (" + mNextWriteTime + ")");
                }

                while (mWriteQueue.isEmpty()) {
                    if (mNextWriteTime != 0) {
                        mNextWriteTime = 0; // idle.
                        TaskPersister.this.notifyAll(); // wake up flush() if needed.
                    }
                    try {
                        if (DEBUG) Slog.d(TAG, "LazyTaskWriter: waiting indefinitely.");
                        TaskPersister.this.wait();
                    } catch (InterruptedException e) {
                    }
                    // Invariant: mNextWriteTime is either FLUSH_QUEUE or PRE_WRITE_DELAY_MS
                    // from now.
                }
                item = mWriteQueue.remove(0);

                long now = SystemClock.uptimeMillis();
                if (DEBUG) Slog.d(TAG, "LazyTaskWriter: now=" + now + " mNextWriteTime=" +
                        mNextWriteTime + " mWriteQueue.size=" + mWriteQueue.size());
                while (now < mNextWriteTime) {
                    try {
                        if (DEBUG) Slog.d(TAG, "LazyTaskWriter: waiting " +
                                (mNextWriteTime - now));
                        TaskPersister.this.wait(mNextWriteTime - now);
                    } catch (InterruptedException e) {
                    }
                    now = SystemClock.uptimeMillis();
                }

                // Got something to do.
            }

            if (item instanceof ImageWriteQueueItem) {
                ImageWriteQueueItem imageWriteQueueItem = (ImageWriteQueueItem) item;
                final String filePath = imageWriteQueueItem.mFilePath;
                if (!createParentDirectory(filePath)) {
                    Slog.e(TAG, "Error while creating images directory for file: " + filePath);
                    return;
                }
                final Bitmap bitmap = imageWriteQueueItem.mImage;
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
            } else if (item instanceof TaskWriteQueueItem) {
                // Write out one task.
                StringWriter stringWriter = null;
                TaskRecord task = ((TaskWriteQueueItem) item).mTask;
                if (DEBUG) Slog.d(TAG, "Writing task=" + task);
                synchronized (mService) {
                    if (task.inRecents) {
                        // Still there.
                        try {
                            if (DEBUG) Slog.d(TAG, "Saving task=" + task);
                            stringWriter = saveToXml(task);
                        } catch (IOException e) {
                        } catch (XmlPullParserException e) {
                        }
                    }
                }
                if (stringWriter != null) {
                    // Write out xml file while not holding mService lock.
                    FileOutputStream file = null;
                    AtomicFile atomicFile = null;
                    try {
                        atomicFile = new AtomicFile(new File(
                                getUserTasksDir(task.userId),
                                String.valueOf(task.taskId) + TASK_FILENAME_SUFFIX));
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
        }
    }
}
