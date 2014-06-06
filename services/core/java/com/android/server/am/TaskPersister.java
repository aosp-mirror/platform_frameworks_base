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

    /** When in slow mode don't write tasks out faster than this */
    private static final long INTER_TASK_DELAY_MS = 60000;
    private static final long DEBUG_INTER_TASK_DELAY_MS = 5000;

    private static final String RECENTS_FILENAME = "_task";
    private static final String TASKS_DIRNAME = "recent_tasks";
    private static final String TASK_EXTENSION = ".xml";
    private static final String IMAGES_DIRNAME = "recent_images";
    private static final String IMAGE_EXTENSION = ".png";

    private static final String TAG_TASK = "task";

    private static File sImagesDir;
    private static File sTasksDir;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;

    private boolean mRecentsChanged = false;

    private final LazyTaskWriterThread mLazyTaskWriterThread;

    TaskPersister(File systemDir, ActivityStackSupervisor stackSupervisor) {
        sTasksDir = new File(systemDir, TASKS_DIRNAME);
        if (!sTasksDir.exists()) {
            if (!sTasksDir.mkdir()) {
                Slog.e(TAG, "Failure creating tasks directory " + sTasksDir);
            }
        }

        sImagesDir = new File(systemDir, IMAGES_DIRNAME);
        if (!sImagesDir.exists()) {
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

    public void notify(TaskRecord task, boolean flush) {
        if (DEBUG) Slog.d(TAG, "notify: task=" + task + " flush=" + flush +
                " Callers=" + Debug.getCallers(4));
        if (task != null) {
            task.needsPersisting = true;
        }
        synchronized (this) {
            mLazyTaskWriterThread.mSlow = !flush;
            mRecentsChanged = true;
            notifyAll();
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

    static void saveImage(Bitmap image, String filename) throws IOException {
        if (DEBUG) Slog.d(TAG, "saveImage: filename=" + filename);
        FileOutputStream imageFile = null;
        try {
            imageFile = new FileOutputStream(new File(sImagesDir, filename + IMAGE_EXTENSION));
            image.compress(Bitmap.CompressFormat.PNG, 100, imageFile);
        } catch (Exception e) {
            Slog.e(TAG, "saveImage: unable to save " + filename, e);
        } finally {
            if (imageFile != null) {
                imageFile.close();
            }
        }
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
                            if (DEBUG) Slog.d(TAG, "restoreTasksLocked: restored task=" + task);
                            if (task != null) {
                                tasks.add(task);
                                final int taskId = task.taskId;
                                recoveredTaskIds.add(taskId);
                                mStackSupervisor.setNextTaskId(taskId);
                            }
                        } else {
                            Slog.e(TAG, "restoreTasksLocked Unknown xml event=" + event + " name="
                                    + name);
                        }
                    }
                    XmlUtils.skipCurrentTag(in);
                }
            } catch (Exception e) {
                Slog.wtf(TAG, "Unable to parse " + taskFile + ". Error " + e);
                deleteFile = true;
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                    }
                }
                if (!DEBUG && deleteFile) {
                    taskFile.delete();
                }
            }
        }

        if (!DEBUG) {
            removeObsoleteFiles(recoveredTaskIds);
        }

        TaskRecord[] tasksArray = new TaskRecord[tasks.size()];
        tasks.toArray(tasksArray);
        Arrays.sort(tasksArray, new Comparator<TaskRecord>() {
            @Override
            public int compare(TaskRecord lhs, TaskRecord rhs) {
                final long diff = lhs.mLastTimeMoved - rhs.mLastTimeMoved;
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
        for (int fileNdx = 0; fileNdx < files.length; ++fileNdx) {
            File file = files[fileNdx];
            String filename = file.getName();
            final int taskIdEnd = filename.indexOf('_');
            if (taskIdEnd > 0) {
                final int taskId;
                try {
                    taskId = Integer.valueOf(filename.substring(0, taskIdEnd));
                } catch (Exception e) {
                    if (DEBUG) Slog.d(TAG, "removeObsoleteFile: Can't parse file=" +
                            file.getName());
                    file.delete();
                    continue;
                }
                if (!persistentTaskIds.contains(taskId)) {
                    if (DEBUG) Slog.d(TAG, "removeObsoleteFile: deleting file=" + file.getName());
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
        return BitmapFactory.decodeFile(sImagesDir + File.separator + filename + IMAGE_EXTENSION);
    }

    private class LazyTaskWriterThread extends Thread {
        boolean mSlow = true;

        LazyTaskWriterThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            ArraySet<Integer> persistentTaskIds = new ArraySet<Integer>();
            while (true) {
                // If mSlow, then delay between each call to saveToXml().
                synchronized (TaskPersister.this) {
                    long now = SystemClock.uptimeMillis();
                    final long releaseTime =
                            now + (DEBUG ? DEBUG_INTER_TASK_DELAY_MS: INTER_TASK_DELAY_MS);
                    while (mSlow && now < releaseTime) {
                        try {
                            if (DEBUG) Slog.d(TAG, "LazyTaskWriter: waiting " +
                                    (releaseTime - now));
                            TaskPersister.this.wait(releaseTime - now);
                        } catch (InterruptedException e) {
                        }
                        now = SystemClock.uptimeMillis();
                    }
                }

                StringWriter stringWriter = null;
                TaskRecord task = null;
                synchronized(mService) {
                    final ArrayList<TaskRecord> tasks = mService.mRecentTasks;
                    persistentTaskIds.clear();
                    for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                        task = tasks.get(taskNdx);
                        if (DEBUG) Slog.d(TAG, "LazyTaskWriter: task=" + task + " persistable=" +
                                task.isPersistable + " needsPersisting=" + task.needsPersisting);
                        if (task.isPersistable) {
                            persistentTaskIds.add(task.taskId);

                            if (task.needsPersisting) {
                                try {
                                    stringWriter = saveToXml(task);
                                    break;
                                } catch (IOException e) {
                                } catch (XmlPullParserException e) {
                                } finally {
                                    task.needsPersisting = false;
                                }
                            }
                        }
                    }
                }

                if (stringWriter != null) {
                    // Write out xml file while not holding mService lock.
                    FileOutputStream file = null;
                    AtomicFile atomicFile = null;
                    try {
                        atomicFile = new AtomicFile(new File(sTasksDir,
                                String.valueOf(task.taskId) + RECENTS_FILENAME + TASK_EXTENSION));
                        file = atomicFile.startWrite();
                        file.write(stringWriter.toString().getBytes());
                        file.write('\n');
                        atomicFile.finishWrite(file);
                    } catch (IOException e) {
                        if (file != null) {
                            atomicFile.failWrite(file);
                        }
                        Slog.e(TAG, "Unable to open " + atomicFile + " for persisting. " + e);
                    }
                } else {
                    // Made it through the entire list and didn't find anything new that needed
                    // persisting.
                    if (!DEBUG) {
                        removeObsoleteFiles(persistentTaskIds);
                    }

                    // Wait here for someone to call setRecentsChanged().
                    synchronized (TaskPersister.this) {
                        while (!mRecentsChanged) {
                            if (DEBUG) Slog.d(TAG, "LazyTaskWriter: Waiting.");
                            try {
                                TaskPersister.this.wait();
                            } catch (InterruptedException e) {
                            }
                        }
                        mRecentsChanged = false;
                        if (DEBUG) Slog.d(TAG, "LazyTaskWriter: Awake");
                    }
                }
                // Some recents file needs to be written.
            }
        }
    }
}
