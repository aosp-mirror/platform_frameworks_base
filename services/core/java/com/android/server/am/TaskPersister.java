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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
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
import java.util.Comparator;

public class TaskPersister {
    static final String TAG = "TaskPersister";
    static final boolean DEBUG = false;

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

    private static final String TAG_TASK = "task";

    static File sImagesDir;
    static File sTasksDir;

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

    TaskPersister(File systemDir, ActivityStackSupervisor stackSupervisor) {
        sTasksDir = new File(systemDir, TASKS_DIRNAME);
        if (!sTasksDir.exists()) {
            if (DEBUG) Slog.d(TAG, "Creating tasks directory " + sTasksDir);
            if (!sTasksDir.mkdir()) {
                Slog.e(TAG, "Failure creating tasks directory " + sTasksDir);
            }
        }

        sImagesDir = new File(systemDir, IMAGES_DIRNAME);
        if (!sImagesDir.exists()) {
            if (DEBUG) Slog.d(TAG, "Creating images directory " + sTasksDir);
            if (!sImagesDir.mkdir()) {
                Slog.e(TAG, "Failure creating images directory " + sImagesDir);
            }
        }

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
                if (DEBUG) Slog.d(TAG, "Removing " + ((ImageWriteQueueItem) item).mFilename +
                        " from write queue");
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
                if (queueNdx < 0) {
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
            if (DEBUG) Slog.d(TAG, "saveImage: filename=" + filename + " now=" +
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
            if (DEBUG) Slog.d(TAG, "restoreTasksLocked: taskFile=" + taskFile.getName());
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
                        if (DEBUG) Slog.d(TAG, "restoreTasksLocked: START_TAG name=" + name);
                        if (TAG_TASK.equals(name)) {
                            final TaskRecord task =
                                    TaskRecord.restoreFromXml(in, mStackSupervisor);
                            if (DEBUG) Slog.d(TAG, "restoreTasksLocked: restored task=" +
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
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                    }
                }
                if (!DEBUG && deleteFile) {
                    if (true || DEBUG) Slog.d(TAG, "Deleting file=" + taskFile.getName());
                    taskFile.delete();
                }
            }
        }

        if (!DEBUG) {
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
        if (DEBUG) Slog.d(TAG, "removeObsoleteFile: persistentTaskIds=" + persistentTaskIds +
                " files=" + files);
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
                    if (DEBUG) Slog.d(TAG, "removeObsoleteFile: Found taskId=" + taskId);
                } catch (Exception e) {
                    Slog.wtf(TAG, "removeObsoleteFile: Can't parse file=" + file.getName());
                    file.delete();
                    continue;
                }
                if (!persistentTaskIds.contains(taskId)) {
                    if (true || DEBUG) Slog.d(TAG, "removeObsoleteFile: deleting file=" +
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
        if (DEBUG) Slog.d(TAG, "restoreImage: restoring " + filename);
        return BitmapFactory.decodeFile(sImagesDir + File.separator + filename);
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
                    if (DEBUG) Slog.d(TAG, "Looking for obsolete files.");
                    persistentTaskIds.clear();
                    synchronized (mService) {
                        final ArrayList<TaskRecord> tasks = mService.mRecentTasks;
                        if (DEBUG) Slog.d(TAG, "mRecents=" + tasks);
                        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                            final TaskRecord task = tasks.get(taskNdx);
                            if (DEBUG) Slog.d(TAG, "LazyTaskWriter: task=" + task + " persistable=" +
                                    task.isPersistable);
                            if (task.isPersistable && !task.stack.isHomeStack()) {
                                if (DEBUG) Slog.d(TAG, "adding to persistentTaskIds task=" + task);
                                persistentTaskIds.add(task.taskId);
                            } else {
                                if (DEBUG) Slog.d(TAG, "omitting from persistentTaskIds task=" + task);
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
                    final String filename = imageWriteQueueItem.mFilename;
                    final Bitmap bitmap = imageWriteQueueItem.mImage;
                    if (DEBUG) Slog.d(TAG, "writing bitmap: filename=" + filename);
                    FileOutputStream imageFile = null;
                    try {
                        imageFile = new FileOutputStream(new File(sImagesDir, filename));
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, imageFile);
                    } catch (Exception e) {
                        Slog.e(TAG, "saveImage: unable to save " + filename, e);
                    } finally {
                        if (imageFile != null) {
                            try {
                                imageFile.close();
                            } catch (IOException e) {
                            }
                        }
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
}
