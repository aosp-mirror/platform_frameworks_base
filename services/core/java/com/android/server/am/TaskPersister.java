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

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.IPackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import libcore.io.IoUtils;

import static com.android.server.am.TaskRecord.INVALID_TASK_ID;

public class TaskPersister {
    static final String TAG = "TaskPersister";
    static final boolean DEBUG_PERSISTER = false;
    static final boolean DEBUG_RESTORER = false;

    /** When not flushing don't write out files faster than this */
    private static final long INTER_WRITE_DELAY_MS = 500;

    /** When not flushing delay this long before writing the first file out. This gives the next
     * task being launched a chance to load its resources without this occupying IO bandwidth. */
    private static final long PRE_TASK_DELAY_MS = 3000;

    /** The maximum number of entries to keep in the queue before draining it automatically. */
    private static final int MAX_WRITE_QUEUE_LENGTH = 6;

    /** Special value for mWriteTime to mean don't wait, just write */
    private static final long FLUSH_QUEUE = -1;

    private static final String RECENTS_FILENAME = "_task";
    private static final String TASKS_DIRNAME = "recent_tasks";
    private static final String TASK_EXTENSION = ".xml";
    private static final String IMAGES_DIRNAME = "recent_images";
    static final String IMAGE_EXTENSION = ".png";

    // Directory where restored historical task XML/PNG files are placed.  This directory
    // contains subdirs named after TASKS_DIRNAME and IMAGES_DIRNAME mirroring the
    // ancestral device's dataset.  This needs to match the RECENTS_TASK_RESTORE_DIR
    // value in RecentsBackupHelper.
    private static final String RESTORED_TASKS_DIRNAME = "restored_" + TASKS_DIRNAME;

    // Max time to wait for the application/package of a restored task to be installed
    // before giving up.
    private static final long MAX_INSTALL_WAIT_TIME = DateUtils.DAY_IN_MILLIS;

    private static final String TAG_TASK = "task";

    static File sImagesDir;
    static File sTasksDir;
    static File sRestoredTasksDir;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;

    /** Value determines write delay mode as follows:
     *    < 0 We are Flushing. No delays between writes until the image queue is drained and all
     * tasks needing persisting are written to disk. There is no delay between writes.
     *    == 0 We are Idle. Next writes will be delayed by #PRE_TASK_DELAY_MS.
     *    > 0 We are Actively writing. Next write will be at this time. Subsequent writes will be
     * delayed by #INTER_WRITE_DELAY_MS. */
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
        final String mFilename;
        Bitmap mImage;
        ImageWriteQueueItem(String filename, Bitmap image) {
            mFilename = filename;
            mImage = image;
        }
    }

    ArrayList<WriteQueueItem> mWriteQueue = new ArrayList<WriteQueueItem>();

    // Map of tasks that were backed-up on a different device that can be restored on this device.
    // Data organization: <packageNameOfAffiliateTask, listOfAffiliatedTasksChains>
    private ArrayMap<String, List<List<OtherDeviceTask>>> mOtherDeviceTasksMap =
                new ArrayMap<>(10);
    // Local cache of package names to uid used when restoring a task from another device.
    private ArrayMap<String, Integer> mPackageUidMap;

    // The next time in milliseconds we will remove expired task from
    // {@link #mOtherDeviceTasksMap} and disk. Set to {@link Long.MAX_VALUE} to never clean-up
    // tasks.
    private long mExpiredTasksCleanupTime = Long.MAX_VALUE;

    TaskPersister(File systemDir, ActivityStackSupervisor stackSupervisor) {
        sTasksDir = new File(systemDir, TASKS_DIRNAME);
        if (!sTasksDir.exists()) {
            if (DEBUG_PERSISTER) Slog.d(TAG, "Creating tasks directory " + sTasksDir);
            if (!sTasksDir.mkdir()) {
                Slog.e(TAG, "Failure creating tasks directory " + sTasksDir);
            }
        }

        sImagesDir = new File(systemDir, IMAGES_DIRNAME);
        if (!sImagesDir.exists()) {
            if (DEBUG_PERSISTER) Slog.d(TAG, "Creating images directory " + sTasksDir);
            if (!sImagesDir.mkdir()) {
                Slog.e(TAG, "Failure creating images directory " + sImagesDir);
            }
        }

        sRestoredTasksDir = new File(systemDir, RESTORED_TASKS_DIRNAME);

        mStackSupervisor = stackSupervisor;
        mService = stackSupervisor.mService;

        mLazyTaskWriterThread = new LazyTaskWriterThread("LazyTaskWriterThread");
    }

    void startPersisting() {
        mLazyTaskWriterThread.start();
    }

    private void removeThumbnails(TaskRecord task) {
        final String taskString = Integer.toString(task.taskId);
        for (int queueNdx = mWriteQueue.size() - 1; queueNdx >= 0; --queueNdx) {
            final WriteQueueItem item = mWriteQueue.get(queueNdx);
            if (item instanceof ImageWriteQueueItem &&
                    ((ImageWriteQueueItem) item).mFilename.startsWith(taskString)) {
                if (DEBUG_PERSISTER) Slog.d(TAG, "Removing "
                        + ((ImageWriteQueueItem) item).mFilename + " from write queue");
                mWriteQueue.remove(queueNdx);
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
                // Dummy.
                mWriteQueue.add(new WriteQueueItem());
            }
            if (flush || mWriteQueue.size() > MAX_WRITE_QUEUE_LENGTH) {
                mNextWriteTime = FLUSH_QUEUE;
            } else if (mNextWriteTime == 0) {
                mNextWriteTime = SystemClock.uptimeMillis() + PRE_TASK_DELAY_MS;
            }
            if (DEBUG_PERSISTER) Slog.d(TAG, "wakeup: task=" + task + " flush=" + flush
                    + " mNextWriteTime=" + mNextWriteTime + " mWriteQueue.size="
                    + mWriteQueue.size() + " Callers=" + Debug.getCallers(4));
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

    void saveImage(Bitmap image, String filename) {
        synchronized (this) {
            int queueNdx;
            for (queueNdx = mWriteQueue.size() - 1; queueNdx >= 0; --queueNdx) {
                final WriteQueueItem item = mWriteQueue.get(queueNdx);
                if (item instanceof ImageWriteQueueItem) {
                    ImageWriteQueueItem imageWriteQueueItem = (ImageWriteQueueItem) item;
                    if (imageWriteQueueItem.mFilename.equals(filename)) {
                        // replace the Bitmap with the new one.
                        imageWriteQueueItem.mImage = image;
                        break;
                    }
                }
            }
            if (queueNdx < 0) {
                mWriteQueue.add(new ImageWriteQueueItem(filename, image));
            }
            if (mWriteQueue.size() > MAX_WRITE_QUEUE_LENGTH) {
                mNextWriteTime = FLUSH_QUEUE;
            } else if (mNextWriteTime == 0) {
                mNextWriteTime = SystemClock.uptimeMillis() + PRE_TASK_DELAY_MS;
            }
            if (DEBUG_PERSISTER) Slog.d(TAG, "saveImage: filename=" + filename + " now=" +
                    SystemClock.uptimeMillis() + " mNextWriteTime=" +
                    mNextWriteTime + " Callers=" + Debug.getCallers(4));
            notifyAll();
        }

        yieldIfQueueTooDeep();
    }

    Bitmap getTaskDescriptionIcon(String filename) {
        // See if it is in the write queue
        final Bitmap icon = getImageFromWriteQueue(filename);
        if (icon != null) {
            return icon;
        }
        return restoreImage(filename);
    }

    Bitmap getImageFromWriteQueue(String filename) {
        synchronized (this) {
            for (int queueNdx = mWriteQueue.size() - 1; queueNdx >= 0; --queueNdx) {
                final WriteQueueItem item = mWriteQueue.get(queueNdx);
                if (item instanceof ImageWriteQueueItem) {
                    ImageWriteQueueItem imageWriteQueueItem = (ImageWriteQueueItem) item;
                    if (imageWriteQueueItem.mFilename.equals(filename)) {
                        return imageWriteQueueItem.mImage;
                    }
                }
            }
            return null;
        }
    }

    private StringWriter saveToXml(TaskRecord task) throws IOException, XmlPullParserException {
        if (DEBUG_PERSISTER) Slog.d(TAG, "saveToXml: task=" + task);
        final XmlSerializer xmlSerializer = new FastXmlSerializer();
        StringWriter stringWriter = new StringWriter();
        xmlSerializer.setOutput(stringWriter);

        if (DEBUG_PERSISTER) xmlSerializer.setFeature(
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

    ArrayList<TaskRecord> restoreTasksLocked() {
        final ArrayList<TaskRecord> tasks = new ArrayList<TaskRecord>();
        ArraySet<Integer> recoveredTaskIds = new ArraySet<Integer>();

        File[] recentFiles = sTasksDir.listFiles();
        if (recentFiles == null) {
            Slog.e(TAG, "Unable to list files from " + sTasksDir);
            return tasks;
        }

        for (int taskNdx = 0; taskNdx < recentFiles.length; ++taskNdx) {
            File taskFile = recentFiles[taskNdx];
            if (DEBUG_PERSISTER) Slog.d(TAG, "restoreTasksLocked: taskFile=" + taskFile.getName());
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
                        if (DEBUG_PERSISTER)
                                Slog.d(TAG, "restoreTasksLocked: START_TAG name=" + name);
                        if (TAG_TASK.equals(name)) {
                            final TaskRecord task =
                                    TaskRecord.restoreFromXml(in, mStackSupervisor);
                            if (DEBUG_PERSISTER) Slog.d(TAG, "restoreTasksLocked: restored task=" +
                                    task);
                            if (task != null) {
                                task.isPersistable = true;
                                // XXX Don't add to write queue... there is no reason to write
                                // out the stuff we just read, if we don't write it we will
                                // read the same thing again.
                                //mWriteQueue.add(new TaskWriteQueueItem(task));
                                tasks.add(task);
                                final int taskId = task.taskId;
                                recoveredTaskIds.add(taskId);
                                mStackSupervisor.setNextTaskId(taskId);
                            } else {
                                Slog.e(TAG, "Unable to restore taskFile=" + taskFile + ": " +
                                        fileToString(taskFile));
                            }
                        } else {
                            Slog.wtf(TAG, "restoreTasksLocked Unknown xml event=" + event +
                                    " name=" + name);
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
                if (!DEBUG_PERSISTER && deleteFile) {
                    if (true || DEBUG_PERSISTER)
                            Slog.d(TAG, "Deleting file=" + taskFile.getName());
                    taskFile.delete();
                }
            }
        }

        if (!DEBUG_PERSISTER) {
            removeObsoleteFiles(recoveredTaskIds);
        }

        // Fixup task affiliation from taskIds
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = tasks.get(taskNdx);
            task.setPrevAffiliate(taskIdToTask(task.mPrevAffiliateTaskId, tasks));
            task.setNextAffiliate(taskIdToTask(task.mNextAffiliateTaskId, tasks));
        }

        TaskRecord[] tasksArray = new TaskRecord[tasks.size()];
        tasks.toArray(tasksArray);
        Arrays.sort(tasksArray, new Comparator<TaskRecord>() {
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

        return new ArrayList<TaskRecord>(Arrays.asList(tasksArray));
    }

    private static void removeObsoleteFiles(ArraySet<Integer> persistentTaskIds, File[] files) {
        if (DEBUG_PERSISTER) Slog.d(TAG, "removeObsoleteFile: persistentTaskIds="
                    + persistentTaskIds + " files=" + files);
        if (files == null) {
            Slog.e(TAG, "File error accessing recents directory (too many files open?).");
            return;
        }
        for (int fileNdx = 0; fileNdx < files.length; ++fileNdx) {
            File file = files[fileNdx];
            String filename = file.getName();
            final int taskIdEnd = filename.indexOf('_');
            if (taskIdEnd > 0) {
                final int taskId;
                try {
                    taskId = Integer.valueOf(filename.substring(0, taskIdEnd));
                    if (DEBUG_PERSISTER) Slog.d(TAG, "removeObsoleteFile: Found taskId=" + taskId);
                } catch (Exception e) {
                    Slog.wtf(TAG, "removeObsoleteFile: Can't parse file=" + file.getName());
                    file.delete();
                    continue;
                }
                if (!persistentTaskIds.contains(taskId)) {
                    if (true || DEBUG_PERSISTER) Slog.d(TAG, "removeObsoleteFile: deleting file=" +
                            file.getName());
                    file.delete();
                }
            }
        }
    }

    private void removeObsoleteFiles(ArraySet<Integer> persistentTaskIds) {
        removeObsoleteFiles(persistentTaskIds, sTasksDir.listFiles());
        removeObsoleteFiles(persistentTaskIds, sImagesDir.listFiles());
    }

    static Bitmap restoreImage(String filename) {
        if (DEBUG_PERSISTER) Slog.d(TAG, "restoreImage: restoring " + filename);
        return BitmapFactory.decodeFile(sImagesDir + File.separator + filename);
    }

    /**
     * Tries to restore task that were backed-up on a different device onto this device.
     */
    void restoreTasksFromOtherDeviceLocked() {
        readOtherDeviceTasksFromDisk();
        addOtherDeviceTasksToRecentsLocked();
    }

    /**
     * Read the tasks that were backed-up on a different device and can be restored to this device
     * from disk and populated {@link #mOtherDeviceTasksMap} with the information. Also sets up
     * time to clear out other device tasks that have not been restored on this device
     * within the allotted time.
     */
    private void readOtherDeviceTasksFromDisk() {
        synchronized (mOtherDeviceTasksMap) {
            // Clear out current map and expiration time.
            mOtherDeviceTasksMap.clear();
            mExpiredTasksCleanupTime = Long.MAX_VALUE;

            final File[] taskFiles;
            if (!sRestoredTasksDir.exists()
                    || (taskFiles = sRestoredTasksDir.listFiles()) == null) {
                // Nothing to do if there are no tasks to restore.
                return;
            }

            long earliestMtime = System.currentTimeMillis();
            SparseArray<List<OtherDeviceTask>> tasksByAffiliateIds =
                        new SparseArray<>(taskFiles.length);

            // Read new tasks from disk
            for (int i = 0; i < taskFiles.length; ++i) {
                final File taskFile = taskFiles[i];
                if (DEBUG_RESTORER) Slog.d(TAG, "readOtherDeviceTasksFromDisk: taskFile="
                            + taskFile.getName());

                final OtherDeviceTask task = OtherDeviceTask.createFromFile(taskFile);

                if (task == null) {
                    // Go ahead and remove the file on disk if we are unable to create a task from
                    // it.
                    if (DEBUG_RESTORER) Slog.e(TAG, "Unable to create task for file="
                                + taskFile.getName() + "...deleting file.");
                    taskFile.delete();
                    continue;
                }

                List<OtherDeviceTask> tasks = tasksByAffiliateIds.get(task.mAffiliatedTaskId);
                if (tasks == null) {
                    tasks = new ArrayList<>();
                    tasksByAffiliateIds.put(task.mAffiliatedTaskId, tasks);
                }
                tasks.add(task);
                final long taskMtime = taskFile.lastModified();
                if (earliestMtime > taskMtime) {
                    earliestMtime = taskMtime;
                }
            }

            if (tasksByAffiliateIds.size() > 0) {
                // Sort each affiliated tasks chain by taskId which is the order they were created
                // that should always be correct...Then add to task map.
                for (int i = 0; i < tasksByAffiliateIds.size(); i++) {
                    List<OtherDeviceTask> chain = tasksByAffiliateIds.valueAt(i);
                    Collections.sort(chain);
                    // Package name of the root task in the affiliate chain.
                    final String packageName =
                            chain.get(chain.size()-1).mComponentName.getPackageName();
                    List<List<OtherDeviceTask>> chains = mOtherDeviceTasksMap.get(packageName);
                    if (chains == null) {
                        chains = new ArrayList<>();
                        mOtherDeviceTasksMap.put(packageName, chains);
                    }
                    chains.add(chain);
                }

                // Set expiration time.
                mExpiredTasksCleanupTime = earliestMtime + MAX_INSTALL_WAIT_TIME;
                if (DEBUG_RESTORER) Slog.d(TAG, "Set Expiration time to "
                            + DateUtils.formatDateTime(mService.mContext, mExpiredTasksCleanupTime,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
            }
        }
    }

    /**
     * Removed any expired tasks from {@link #mOtherDeviceTasksMap} and disk if their expiration
     * time is less than or equal to {@link #mExpiredTasksCleanupTime}.
     */
    private void removeExpiredTasksIfNeeded() {
        synchronized (mOtherDeviceTasksMap) {
            final long now = System.currentTimeMillis();
            final boolean noMoreTasks = mOtherDeviceTasksMap.isEmpty();
            if (noMoreTasks || now < mExpiredTasksCleanupTime) {
                if (noMoreTasks && mPackageUidMap != null) {
                    // All done! package->uid map no longer needed.
                    mPackageUidMap = null;
                }
                return;
            }

            long earliestNonExpiredMtime = now;
            mExpiredTasksCleanupTime = Long.MAX_VALUE;

            // Remove expired backed-up tasks that have not been restored. We only want to
            // remove task if it is safe to remove all tasks in the affiliation chain.
            for (int i = mOtherDeviceTasksMap.size() - 1; i >= 0 ; i--) {

                List<List<OtherDeviceTask>> chains = mOtherDeviceTasksMap.valueAt(i);
                for (int j = chains.size() - 1; j >= 0 ; j--) {

                    List<OtherDeviceTask> chain = chains.get(j);
                    boolean removeChain = true;
                    for (int k = chain.size() - 1; k >= 0 ; k--) {
                        OtherDeviceTask task = chain.get(k);
                        final long taskLastModified = task.mFile.lastModified();
                        if ((taskLastModified + MAX_INSTALL_WAIT_TIME) > now) {
                            // File has not expired yet...but we keep looping to get the earliest
                            // mtime.
                            if (earliestNonExpiredMtime > taskLastModified) {
                                earliestNonExpiredMtime = taskLastModified;
                            }
                            removeChain = false;
                        }
                    }
                    if (removeChain) {
                        for (int k = chain.size() - 1; k >= 0; k--) {
                            final File file = chain.get(k).mFile;
                            if (DEBUG_RESTORER) Slog.d(TAG, "Deleting expired file="
                                    + file.getName() + " mapped to not installed component="
                                    + chain.get(k).mComponentName);
                            file.delete();
                        }
                        chains.remove(j);
                    }
                }
                if (chains.isEmpty()) {
                    final String packageName = mOtherDeviceTasksMap.keyAt(i);
                    mOtherDeviceTasksMap.removeAt(i);
                    if (DEBUG_RESTORER) Slog.d(TAG, "Removed package=" + packageName
                                + " from task map");
                }
            }

            // Reset expiration time if there is any task remaining.
            if (!mOtherDeviceTasksMap.isEmpty()) {
                mExpiredTasksCleanupTime = earliestNonExpiredMtime + MAX_INSTALL_WAIT_TIME;
                if (DEBUG_RESTORER) Slog.d(TAG, "Reset expiration time to "
                            + DateUtils.formatDateTime(mService.mContext, mExpiredTasksCleanupTime,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
            } else {
                // All done! package->uid map no longer needed.
                mPackageUidMap = null;
            }
        }
    }

    /**
     * Removes the input package name from the local package->uid map.
     */
    void removeFromPackageCache(String packageName) {
        synchronized (mOtherDeviceTasksMap) {
            if (mPackageUidMap != null) {
                mPackageUidMap.remove(packageName);
            }
        }
    }

    /**
     * Tries to add all backed-up tasks from another device to this device recent's list.
     */
    private void addOtherDeviceTasksToRecentsLocked() {
        synchronized (mOtherDeviceTasksMap) {
            for (int i = mOtherDeviceTasksMap.size() - 1; i >= 0; i--) {
                addOtherDeviceTasksToRecentsLocked(mOtherDeviceTasksMap.keyAt(i));
            }
        }
    }

    /**
     * Tries to add backed-up tasks that are associated with the input package from
     * another device to this device recent's list.
     */
    void addOtherDeviceTasksToRecentsLocked(String packageName) {
        synchronized (mOtherDeviceTasksMap) {
            List<List<OtherDeviceTask>> chains = mOtherDeviceTasksMap.get(packageName);
            if (chains == null) {
                return;
            }

            for (int i = chains.size() - 1; i >= 0; i--) {
                List<OtherDeviceTask> chain = chains.get(i);
                if (!canAddOtherDeviceTaskChain(chain)) {
                    if (DEBUG_RESTORER) Slog.d(TAG, "Can't add task chain at index=" + i
                            + " for package=" + packageName);
                    continue;
                }

                // Generate task records for this chain.
                List<TaskRecord> tasks = new ArrayList<>();
                TaskRecord prev = null;
                for (int j = chain.size() - 1; j >= 0; j--) {
                    TaskRecord task = createTaskRecordLocked(chain.get(j));
                    if (task == null) {
                        // There was a problem in creating one of this task records in this chain.
                        // There is no way we can continue...
                        if (DEBUG_RESTORER) Slog.d(TAG, "Can't create task record for file="
                                + chain.get(j).mFile + " for package=" + packageName);
                        break;
                    }

                    // Wire-up affiliation chain.
                    if (prev == null) {
                        task.mPrevAffiliate = null;
                        task.mPrevAffiliateTaskId = INVALID_TASK_ID;
                        task.mAffiliatedTaskId = task.taskId;
                    } else {
                        prev.mNextAffiliate = task;
                        prev.mNextAffiliateTaskId = task.taskId;
                        task.mAffiliatedTaskId = prev.mAffiliatedTaskId;
                        task.mPrevAffiliate = prev;
                        task.mPrevAffiliateTaskId = prev.taskId;
                    }
                    prev = task;
                    tasks.add(0, task);
                }

                // Add tasks to recent's if we were able to create task records for all the tasks
                // in the chain.
                if (tasks.size() == chain.size()) {
                    // Make sure there is space in recent's to add the new task. If there is space
                    // to the to the back.
                    // TODO: Would be more fancy to interleave the new tasks into recent's based on
                    // {@link TaskRecord.mLastTimeMoved} and drop the oldest recent's vs. just
                    // adding to the back of the list.
                    int spaceLeft =
                            ActivityManager.getMaxRecentTasksStatic()
                            - mService.mRecentTasks.size();
                    if (spaceLeft >= tasks.size()) {
                        mService.mRecentTasks.addAll(mService.mRecentTasks.size(), tasks);
                        for (int k = tasks.size() - 1; k >= 0; k--) {
                            // Persist new tasks.
                            wakeup(tasks.get(k), false);
                        }

                        if (DEBUG_RESTORER) Slog.d(TAG, "Added " + tasks.size()
                                    + " tasks to recent's for" + " package=" + packageName);
                    } else {
                        if (DEBUG_RESTORER) Slog.d(TAG, "Didn't add to recents. tasks.size("
                                    + tasks.size() + ") != chain.size(" + chain.size()
                                    + ") for package=" + packageName);
                    }
                } else {
                    if (DEBUG_RESTORER) Slog.v(TAG, "Unable to add restored tasks to recents "
                            + tasks.size() + " tasks for package=" + packageName);
                }

                // Clean-up structures
                for (int j = chain.size() - 1; j >= 0; j--) {
                    chain.get(j).mFile.delete();
                }
                chains.remove(i);
                if (chains.isEmpty()) {
                    // The fate of all backed-up tasks associated with this package has been
                    // determine. Go ahead and remove it from the to-process list.
                    mOtherDeviceTasksMap.remove(packageName);
                    if (DEBUG_RESTORER)
                            Slog.d(TAG, "Removed package=" + packageName + " from restore map");
                }
            }
        }
    }

    /**
     * Creates and returns {@link TaskRecord} for the task from another device that can be used on
     * this device. Returns null if the operation failed.
     */
    private TaskRecord createTaskRecordLocked(OtherDeviceTask other) {
        File file = other.mFile;
        BufferedReader reader = null;
        TaskRecord task = null;
        if (DEBUG_RESTORER) Slog.d(TAG, "createTaskRecordLocked: file=" + file.getName());

        try {
            reader = new BufferedReader(new FileReader(file));
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(reader);

            int event;
            while (((event = in.next()) != XmlPullParser.END_DOCUMENT)
                    && event != XmlPullParser.END_TAG) {
                final String name = in.getName();
                if (event == XmlPullParser.START_TAG) {

                    if (TAG_TASK.equals(name)) {
                        // Create a task record using a task id that is valid for this device.
                        task = TaskRecord.restoreFromXml(
                                in, mStackSupervisor, mStackSupervisor.getNextTaskId());
                        if (DEBUG_RESTORER)
                                Slog.d(TAG, "createTaskRecordLocked: restored task=" + task);

                        if (task != null) {
                            task.isPersistable = true;
                            task.inRecents = true;
                            // Task can/should only be backed-up/restored for device owner.
                            task.userId = UserHandle.USER_OWNER;
                            // Clear out affiliated ids that are no longer valid on this device.
                            task.mAffiliatedTaskId = INVALID_TASK_ID;
                            task.mPrevAffiliateTaskId = INVALID_TASK_ID;
                            task.mNextAffiliateTaskId = INVALID_TASK_ID;
                            // Set up uids valid for this device.
                            Integer uid = mPackageUidMap.get(task.realActivity.getPackageName());
                            if (uid == null) {
                                // How did this happen???
                                Slog.wtf(TAG, "Can't find uid for task=" + task
                                        + " in mPackageUidMap=" + mPackageUidMap);
                                return null;
                            }
                            task.effectiveUid = task.mCallingUid = uid;
                            for (int i = task.mActivities.size() - 1; i >= 0; --i) {
                                final ActivityRecord activity = task.mActivities.get(i);
                                uid = mPackageUidMap.get(activity.launchedFromPackage);
                                if (uid == null) {
                                    // How did this happen??
                                    Slog.wtf(TAG, "Can't find uid for activity=" + activity
                                            + " in mPackageUidMap=" + mPackageUidMap);
                                    return null;
                                }
                                activity.launchedFromUid = uid;
                            }

                        } else {
                            Slog.e(TAG, "Unable to create task for backed-up file=" + file + ": "
                                        + fileToString(file));
                        }
                    } else {
                        Slog.wtf(TAG, "createTaskRecordLocked Unknown xml event=" + event
                                    + " name=" + name);
                    }
                }
                XmlUtils.skipCurrentTag(in);
            }
        } catch (Exception e) {
            Slog.wtf(TAG, "Unable to parse " + file + ". Error ", e);
            Slog.e(TAG, "Failing file: " + fileToString(file));
        } finally {
            IoUtils.closeQuietly(reader);
        }

        return task;
    }

    /**
     * Returns true if the input task chain backed-up from another device can be restored on this
     * device. Also, sets the {@link OtherDeviceTask#mUid} on the input tasks if they can be
     * restored.
     */
    private boolean canAddOtherDeviceTaskChain(List<OtherDeviceTask> chain) {

        final ArraySet<ComponentName> validComponents = new ArraySet<>();
        final IPackageManager pm = AppGlobals.getPackageManager();
        for (int i = 0; i < chain.size(); i++) {

            OtherDeviceTask task = chain.get(i);
            // Quick check, we can't add the task chain if any of its task files don't exist.
            if (!task.mFile.exists()) {
                if (DEBUG_RESTORER) Slog.d(TAG,
                        "Can't add chain due to missing file=" + task.mFile);
                return false;
            }

            // Verify task package is installed.
            if (!isPackageInstalled(task.mComponentName.getPackageName())) {
                return false;
            }
            // Verify that all the launch packages are installed.
            if (task.mLaunchPackages != null) {
                for (int j = task.mLaunchPackages.size() - 1; j >= 0; --j) {
                    if (!isPackageInstalled(task.mLaunchPackages.valueAt(j))) {
                        return false;
                    }
                }
            }

            if (validComponents.contains(task.mComponentName)) {
                // Existance of component has already been verified.
                continue;
            }

            // Check to see if the specific component is installed.
            try {
                if (pm.getActivityInfo(task.mComponentName, 0, UserHandle.USER_OWNER) == null) {
                    // Component isn't installed...
                    return false;
                }
                validComponents.add(task.mComponentName);
            } catch (RemoteException e) {
                // Should not happen???
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true if the input package name is installed. If the package is installed, an entry
     * for the package is added to {@link #mPackageUidMap}.
     */
    private boolean isPackageInstalled(final String packageName) {
        if (mPackageUidMap != null && mPackageUidMap.containsKey(packageName)) {
            return true;
        }
        try {
            int uid = AppGlobals.getPackageManager().getPackageUid(
                    packageName, UserHandle.USER_OWNER);
            if (uid == -1) {
                // package doesn't exist...
                return false;
            }
            if (mPackageUidMap == null) {
                mPackageUidMap = new ArrayMap<>();
            }
            mPackageUidMap.put(packageName, uid);
            return true;
        } catch (RemoteException e) {
            // Should not happen???
            return false;
        }
    }

    private class LazyTaskWriterThread extends Thread {

        LazyTaskWriterThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            ArraySet<Integer> persistentTaskIds = new ArraySet<Integer>();
            while (true) {
                // We can't lock mService while holding TaskPersister.this, but we don't want to
                // call removeObsoleteFiles every time through the loop, only the last time before
                // going to sleep. The risk is that we call removeObsoleteFiles() successively.
                final boolean probablyDone;
                synchronized (TaskPersister.this) {
                    probablyDone = mWriteQueue.isEmpty();
                }
                if (probablyDone) {
                    if (DEBUG_PERSISTER) Slog.d(TAG, "Looking for obsolete files.");
                    persistentTaskIds.clear();
                    synchronized (mService) {
                        final ArrayList<TaskRecord> tasks = mService.mRecentTasks;
                        if (DEBUG_PERSISTER) Slog.d(TAG, "mRecents=" + tasks);
                        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                            final TaskRecord task = tasks.get(taskNdx);
                            if (DEBUG_PERSISTER) Slog.d(TAG, "LazyTaskWriter: task=" + task +
                                    " persistable=" + task.isPersistable);
                            if ((task.isPersistable || task.inRecents)
                                    && (task.stack == null || !task.stack.isHomeStack())) {
                                if (DEBUG_PERSISTER)
                                        Slog.d(TAG, "adding to persistentTaskIds task=" + task);
                                persistentTaskIds.add(task.taskId);
                            } else {
                                if (DEBUG_PERSISTER) Slog.d(TAG,
                                        "omitting from persistentTaskIds task=" + task);
                            }
                        }
                    }
                    removeObsoleteFiles(persistentTaskIds);
                }

                // If mNextWriteTime, then don't delay between each call to saveToXml().
                final WriteQueueItem item;
                synchronized (TaskPersister.this) {
                    if (mNextWriteTime != FLUSH_QUEUE) {
                        // The next write we don't have to wait so long.
                        mNextWriteTime = SystemClock.uptimeMillis() + INTER_WRITE_DELAY_MS;
                        if (DEBUG_PERSISTER) Slog.d(TAG, "Next write time may be in " +
                                INTER_WRITE_DELAY_MS + " msec. (" + mNextWriteTime + ")");
                    }


                    while (mWriteQueue.isEmpty()) {
                        if (mNextWriteTime != 0) {
                            mNextWriteTime = 0; // idle.
                            TaskPersister.this.notifyAll(); // wake up flush() if needed.
                        }

                        // See if we need to remove any expired back-up tasks before waiting.
                        removeExpiredTasksIfNeeded();

                        try {
                            if (DEBUG_PERSISTER)
                                    Slog.d(TAG, "LazyTaskWriter: waiting indefinitely.");
                            TaskPersister.this.wait();
                        } catch (InterruptedException e) {
                        }
                        // Invariant: mNextWriteTime is either FLUSH_QUEUE or PRE_WRITE_DELAY_MS
                        // from now.
                    }
                    item = mWriteQueue.remove(0);

                    long now = SystemClock.uptimeMillis();
                    if (DEBUG_PERSISTER) Slog.d(TAG, "LazyTaskWriter: now=" + now
                                + " mNextWriteTime=" + mNextWriteTime + " mWriteQueue.size="
                                + mWriteQueue.size());
                    while (now < mNextWriteTime) {
                        try {
                            if (DEBUG_PERSISTER) Slog.d(TAG, "LazyTaskWriter: waiting " +
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
                    final String filename = imageWriteQueueItem.mFilename;
                    final Bitmap bitmap = imageWriteQueueItem.mImage;
                    if (DEBUG_PERSISTER) Slog.d(TAG, "writing bitmap: filename=" + filename);
                    FileOutputStream imageFile = null;
                    try {
                        imageFile = new FileOutputStream(new File(sImagesDir, filename));
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, imageFile);
                    } catch (Exception e) {
                        Slog.e(TAG, "saveImage: unable to save " + filename, e);
                    } finally {
                        IoUtils.closeQuietly(imageFile);
                    }
                } else if (item instanceof TaskWriteQueueItem) {
                    // Write out one task.
                    StringWriter stringWriter = null;
                    TaskRecord task = ((TaskWriteQueueItem) item).mTask;
                    if (DEBUG_PERSISTER) Slog.d(TAG, "Writing task=" + task);
                    synchronized (mService) {
                        if (task.inRecents) {
                            // Still there.
                            try {
                                if (DEBUG_PERSISTER) Slog.d(TAG, "Saving task=" + task);
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
                            atomicFile = new AtomicFile(new File(sTasksDir, String.valueOf(
                                    task.taskId) + RECENTS_FILENAME + TASK_EXTENSION));
                            file = atomicFile.startWrite();
                            file.write(stringWriter.toString().getBytes());
                            file.write('\n');
                            atomicFile.finishWrite(file);
                        } catch (IOException e) {
                            if (file != null) {
                                atomicFile.failWrite(file);
                            }
                            Slog.e(TAG, "Unable to open " + atomicFile + " for persisting. " +
                                    e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper class for holding essential information about task that were backed-up on a different
     * device that can be restored on this device.
     */
    private static class OtherDeviceTask implements Comparable<OtherDeviceTask> {
        final File mFile;
        // See {@link TaskRecord} for information on the fields below.
        final ComponentName mComponentName;
        final int mTaskId;
        final int mAffiliatedTaskId;

        // Names of packages that launched activities in this task. All packages listed here need
        // to be installed on the current device in order for the task to be restored successfully.
        final ArraySet<String> mLaunchPackages;

        private OtherDeviceTask(File file, ComponentName componentName, int taskId,
                int affiliatedTaskId, ArraySet<String> launchPackages) {
            mFile = file;
            mComponentName = componentName;
            mTaskId = taskId;
            mAffiliatedTaskId = (affiliatedTaskId == INVALID_TASK_ID) ? taskId: affiliatedTaskId;
            mLaunchPackages = launchPackages;
        }

        @Override
        public int compareTo(OtherDeviceTask another) {
            return mTaskId - another.mTaskId;
        }

        /**
         * Creates a new {@link OtherDeviceTask} object based on the contents of the input file.
         *
         * @param file input file that contains the complete task information.
         * @return new {@link OtherDeviceTask} object or null if we failed to create the object.
         */
        static OtherDeviceTask createFromFile(File file) {
            if (file == null || !file.exists()) {
                if (DEBUG_RESTORER)
                    Slog.d(TAG, "createFromFile: file=" + file + " doesn't exist.");
                return null;
            }

            BufferedReader reader = null;

            try {
                reader = new BufferedReader(new FileReader(file));
                final XmlPullParser in = Xml.newPullParser();
                in.setInput(reader);

                int event;
                while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                        event != XmlPullParser.START_TAG) {
                    // Skip to the start tag or end of document
                }

                if (event == XmlPullParser.START_TAG) {
                    final String name = in.getName();

                    if (TAG_TASK.equals(name)) {
                        final int outerDepth = in.getDepth();
                        ComponentName componentName = null;
                        int taskId = INVALID_TASK_ID;
                        int taskAffiliation = INVALID_TASK_ID;
                        for (int j = in.getAttributeCount() - 1; j >= 0; --j) {
                            final String attrName = in.getAttributeName(j);
                            final String attrValue = in.getAttributeValue(j);
                            if (TaskRecord.ATTR_REALACTIVITY.equals(attrName)) {
                                componentName = ComponentName.unflattenFromString(attrValue);
                            } else if (TaskRecord.ATTR_TASKID.equals(attrName)) {
                                taskId = Integer.valueOf(attrValue);
                            } else if (TaskRecord.ATTR_TASK_AFFILIATION.equals(attrName)) {
                                taskAffiliation = Integer.valueOf(attrValue);
                            }
                        }
                        if (componentName == null || taskId == INVALID_TASK_ID) {
                            if (DEBUG_RESTORER) Slog.e(TAG,
                                    "createFromFile: FAILED componentName=" + componentName
                                    + " taskId=" + taskId + " file=" + file);
                            return null;
                        }

                        ArraySet<String> launchPackages = null;
                        while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                                (event != XmlPullParser.END_TAG || in.getDepth() < outerDepth)) {
                            if (event == XmlPullParser.START_TAG) {
                                if (TaskRecord.TAG_ACTIVITY.equals(in.getName())) {
                                    for (int j = in.getAttributeCount() - 1; j >= 0; --j) {
                                        if (ActivityRecord.ATTR_LAUNCHEDFROMPACKAGE.equals(
                                                in.getAttributeName(j))) {
                                            if (launchPackages == null) {
                                                launchPackages = new ArraySet();
                                            }
                                            launchPackages.add(in.getAttributeValue(j));
                                        }
                                    }
                                } else {
                                    XmlUtils.skipCurrentTag(in);
                                }
                            }
                        }
                        if (DEBUG_RESTORER) Slog.d(TAG, "creating OtherDeviceTask from file="
                                + file.getName() + " componentName=" + componentName
                                + " taskId=" + taskId + " launchPackages=" + launchPackages);
                        return new OtherDeviceTask(file, componentName, taskId,
                                taskAffiliation, launchPackages);
                    } else {
                        Slog.wtf(TAG,
                                "createFromFile: Unknown xml event=" + event + " name=" + name);
                    }
                } else {
                    Slog.wtf(TAG, "createFromFile: Unable to find start tag in file=" + file);
                }
            } catch (IOException | XmlPullParserException e) {
                Slog.wtf(TAG, "Unable to parse " + file + ". Error ", e);
            } finally {
                IoUtils.closeQuietly(reader);
            }

            // Something went wrong...
            return null;
        }
    }
}
