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

package com.android.server.task;

import android.content.ComponentName;
import android.app.task.Task;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.IoThread;
import com.android.server.task.controllers.TaskStatus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/**
 * Maintain a list of classes, and accessor methods/logic for these tasks.
 * This class offers the following functionality:
 *     - When a task is added, it will determine if the task requirements have changed (update) and
 *       whether the controllers need to be updated.
 *     - Persists Tasks, figures out when to to rewrite the Task to disk.
 *     - Handles rescheduling of tasks.
 *       - When a periodic task is executed and must be re-added.
 *       - When a task fails and the client requests that it be retried with backoff.
 *       - This class <strong>is not</strong> thread-safe.
 *
 * Note on locking:
 *      All callers to this class must <strong>lock on the class object they are calling</strong>.
 *      This is important b/c {@link com.android.server.task.TaskStore.WriteTasksMapToDiskRunnable}
 *      and {@link com.android.server.task.TaskStore.ReadTaskMapFromDiskRunnable} lock on that
 *      object.
 */
public class TaskStore {
    private static final String TAG = "TaskManagerStore";
    private static final boolean DEBUG = TaskManagerService.DEBUG;

    /** Threshold to adjust how often we want to write to the db. */
    private static final int MAX_OPS_BEFORE_WRITE = 1;
    final ArraySet<TaskStatus> mTasksSet;
    final Context mContext;

    private int mDirtyOperations;

    private static final Object sSingletonLock = new Object();
    private final AtomicFile mTasksFile;
    /** Handler backed by IoThread for writing to disk. */
    private final Handler mIoHandler = IoThread.getHandler();
    private static TaskStore sSingleton;

    /** Used by the {@Link TaskManagerService} to instantiate the TaskStore. */
    static TaskStore initAndGet(TaskManagerService taskManagerService) {
        synchronized (sSingletonLock) {
            if (sSingleton == null) {
                sSingleton = new TaskStore(taskManagerService.getContext(),
                        Environment.getDataDirectory(), taskManagerService);
            }
            return sSingleton;
        }
    }

    @VisibleForTesting
    public static TaskStore initAndGetForTesting(Context context, File dataDir,
                                                 TaskMapReadFinishedListener callback) {
        return new TaskStore(context, dataDir, callback);
    }

    private TaskStore(Context context, File dataDir, TaskMapReadFinishedListener callback) {
        mContext = context;
        mDirtyOperations = 0;

        File systemDir = new File(dataDir, "system");
        File taskDir = new File(systemDir, "task");
        taskDir.mkdirs();
        mTasksFile = new AtomicFile(new File(taskDir, "tasks.xml"));

        mTasksSet = new ArraySet<TaskStatus>();

        readTaskMapFromDiskAsync(callback);
    }

    /**
     * Add a task to the master list, persisting it if necessary. If the TaskStatus already exists,
     * it will be replaced.
     * @param taskStatus Task to add.
     * @return Whether or not an equivalent TaskStatus was replaced by this operation.
     */
    public boolean add(TaskStatus taskStatus) {
        boolean replaced = mTasksSet.remove(taskStatus);
        mTasksSet.add(taskStatus);
        if (taskStatus.isPersisted()) {
            maybeWriteStatusToDiskAsync();
        }
        if (DEBUG) {
            Slog.d(TAG, "Added task status to store: " + taskStatus);
        }
        return replaced;
    }

    /**
     * Whether this taskStatus object already exists in the TaskStore.
     */
    public boolean containsTaskIdForUid(int taskId, int uId) {
        for (TaskStatus ts : mTasksSet) {
            if (ts.getUid() == uId && ts.getTaskId() == taskId) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return mTasksSet.size();
    }

    /**
     * Remove the provided task. Will also delete the task if it was persisted.
     * @return Whether or not the task existed to be removed.
     */
    public boolean remove(TaskStatus taskStatus) {
        boolean removed = mTasksSet.remove(taskStatus);
        if (!removed) {
            if (DEBUG) {
                Slog.d(TAG, "Couldn't remove task: didn't exist: " + taskStatus);
            }
            return false;
        }
        maybeWriteStatusToDiskAsync();
        return removed;
    }

    @VisibleForTesting
    public void clear() {
        mTasksSet.clear();
        maybeWriteStatusToDiskAsync();
    }

    public List<TaskStatus> getTasksByUser(int userHandle) {
        List<TaskStatus> matchingTasks = new ArrayList<TaskStatus>();
        Iterator<TaskStatus> it = mTasksSet.iterator();
        while (it.hasNext()) {
            TaskStatus ts = it.next();
            if (UserHandle.getUserId(ts.getUid()) == userHandle) {
                matchingTasks.add(ts);
            }
        }
        return matchingTasks;
    }

    /**
     * @param uid Uid of the requesting app.
     * @return All TaskStatus objects for a given uid from the master list.
     */
    public List<TaskStatus> getTasksByUid(int uid) {
        List<TaskStatus> matchingTasks = new ArrayList<TaskStatus>();
        Iterator<TaskStatus> it = mTasksSet.iterator();
        while (it.hasNext()) {
            TaskStatus ts = it.next();
            if (ts.getUid() == uid) {
                matchingTasks.add(ts);
            }
        }
        return matchingTasks;
    }

    /**
     * @param uid Uid of the requesting app.
     * @param taskId Task id, specified at schedule-time.
     * @return the TaskStatus that matches the provided uId and taskId, or null if none found.
     */
    public TaskStatus getTaskByUidAndTaskId(int uid, int taskId) {
        Iterator<TaskStatus> it = mTasksSet.iterator();
        while (it.hasNext()) {
            TaskStatus ts = it.next();
            if (ts.getUid() == uid && ts.getTaskId() == taskId) {
                return ts;
            }
        }
        return null;
    }

    /**
     * @return The live array of TaskStatus objects.
     */
    public ArraySet<TaskStatus> getTasks() {
        return mTasksSet;
    }

    /** Version of the db schema. */
    private static final int TASKS_FILE_VERSION = 0;
    /** Tag corresponds to constraints this task needs. */
    private static final String XML_TAG_PARAMS_CONSTRAINTS = "constraints";
    /** Tag corresponds to execution parameters. */
    private static final String XML_TAG_PERIODIC = "periodic";
    private static final String XML_TAG_ONEOFF = "one-off";
    private static final String XML_TAG_EXTRAS = "extras";

    /**
     * Every time the state changes we write all the tasks in one swathe, instead of trying to
     * track incremental changes.
     * @return Whether the operation was successful. This will only fail for e.g. if the system is
     * low on storage. If this happens, we continue as normal
     */
    private void maybeWriteStatusToDiskAsync() {
        mDirtyOperations++;
        if (mDirtyOperations >= MAX_OPS_BEFORE_WRITE) {
            if (DEBUG) {
                Slog.v(TAG, "Writing tasks to disk.");
            }
            mIoHandler.post(new WriteTasksMapToDiskRunnable());
        }
    }

    private void readTaskMapFromDiskAsync(TaskMapReadFinishedListener callback) {
        mIoHandler.post(new ReadTaskMapFromDiskRunnable(callback));
    }

    public void readTaskMapFromDisk(TaskMapReadFinishedListener callback) {
        new ReadTaskMapFromDiskRunnable(callback).run();
    }

    /**
     * Runnable that writes {@link #mTasksSet} out to xml.
     * NOTE: This Runnable locks on TaskStore.this
     */
    private class WriteTasksMapToDiskRunnable implements Runnable {
        @Override
        public void run() {
            final long startElapsed = SystemClock.elapsedRealtime();
            synchronized (TaskStore.this) {
                writeTasksMapImpl();
            }
            if (TaskManagerService.DEBUG) {
                Slog.v(TAG, "Finished writing, took " + (SystemClock.elapsedRealtime()
                        - startElapsed) + "ms");
            }
        }

        private void writeTasksMapImpl() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(baos, "utf-8");
                out.startDocument(null, true);
                out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

                out.startTag(null, "task-info");
                out.attribute(null, "version", Integer.toString(TASKS_FILE_VERSION));
                for (int i = 0; i < mTasksSet.size(); i++) {
                    final TaskStatus taskStatus = mTasksSet.valueAt(i);
                    if (DEBUG) {
                        Slog.d(TAG, "Saving task " + taskStatus.getTaskId());
                    }
                    out.startTag(null, "task");
                    addIdentifierAttributesToTaskTag(out, taskStatus);
                    writeConstraintsToXml(out, taskStatus);
                    writeExecutionCriteriaToXml(out, taskStatus);
                    writeBundleToXml(taskStatus.getExtras(), out);
                    out.endTag(null, "task");
                }
                out.endTag(null, "task-info");
                out.endDocument();

                // Write out to disk in one fell sweep.
                FileOutputStream fos = mTasksFile.startWrite();
                fos.write(baos.toByteArray());
                mTasksFile.finishWrite(fos);
                mDirtyOperations = 0;
            } catch (IOException e) {
                if (DEBUG) {
                    Slog.v(TAG, "Error writing out task data.", e);
                }
            } catch (XmlPullParserException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Error persisting bundle.", e);
                }
            }
        }

        /** Write out a tag with data comprising the required fields of this task and its client. */
        private void addIdentifierAttributesToTaskTag(XmlSerializer out, TaskStatus taskStatus)
                throws IOException {
            out.attribute(null, "taskid", Integer.toString(taskStatus.getTaskId()));
            out.attribute(null, "package", taskStatus.getServiceComponent().getPackageName());
            out.attribute(null, "class", taskStatus.getServiceComponent().getClassName());
            out.attribute(null, "uid", Integer.toString(taskStatus.getUid()));
        }

        private void writeBundleToXml(PersistableBundle extras, XmlSerializer out)
                throws IOException, XmlPullParserException {
            out.startTag(null, XML_TAG_EXTRAS);
            extras.saveToXml(out);
            out.endTag(null, XML_TAG_EXTRAS);
        }
        /**
         * Write out a tag with data identifying this tasks constraints. If the constraint isn't here
         * it doesn't apply.
         */
        private void writeConstraintsToXml(XmlSerializer out, TaskStatus taskStatus) throws IOException {
            out.startTag(null, XML_TAG_PARAMS_CONSTRAINTS);
            if (taskStatus.hasUnmeteredConstraint()) {
                out.attribute(null, "unmetered", Boolean.toString(true));
            }
            if (taskStatus.hasConnectivityConstraint()) {
                out.attribute(null, "connectivity", Boolean.toString(true));
            }
            if (taskStatus.hasIdleConstraint()) {
                out.attribute(null, "idle", Boolean.toString(true));
            }
            if (taskStatus.hasChargingConstraint()) {
                out.attribute(null, "charging", Boolean.toString(true));
            }
            out.endTag(null, XML_TAG_PARAMS_CONSTRAINTS);
        }

        private void writeExecutionCriteriaToXml(XmlSerializer out, TaskStatus taskStatus)
                throws IOException {
            final Task task = taskStatus.getTask();
            if (taskStatus.getTask().isPeriodic()) {
                out.startTag(null, XML_TAG_PERIODIC);
                out.attribute(null, "period", Long.toString(task.getIntervalMillis()));
            } else {
                out.startTag(null, XML_TAG_ONEOFF);
            }

            if (taskStatus.hasDeadlineConstraint()) {
                // Wall clock deadline.
                final long deadlineWallclock =  System.currentTimeMillis() +
                        (taskStatus.getLatestRunTimeElapsed() - SystemClock.elapsedRealtime());
                out.attribute(null, "deadline", Long.toString(deadlineWallclock));
            }
            if (taskStatus.hasTimingDelayConstraint()) {
                final long delayWallclock = System.currentTimeMillis() +
                        (taskStatus.getEarliestRunTime() - SystemClock.elapsedRealtime());
                out.attribute(null, "delay", Long.toString(delayWallclock));
            }

            // Only write out back-off policy if it differs from the default.
            // This also helps the case where the task is idle -> these aren't allowed to specify
            // back-off.
            if (taskStatus.getTask().getInitialBackoffMillis() != Task.DEFAULT_INITIAL_BACKOFF_MILLIS
                    || taskStatus.getTask().getBackoffPolicy() != Task.DEFAULT_BACKOFF_POLICY) {
                out.attribute(null, "backoff-policy", Integer.toString(task.getBackoffPolicy()));
                out.attribute(null, "initial-backoff", Long.toString(task.getInitialBackoffMillis()));
            }
            if (task.isPeriodic()) {
                out.endTag(null, XML_TAG_PERIODIC);
            } else {
                out.endTag(null, XML_TAG_ONEOFF);
            }
        }
    }

    /**
     * Runnable that reads list of persisted task from xml.
     * NOTE: This Runnable locks on TaskStore.this
     */
    private class ReadTaskMapFromDiskRunnable implements Runnable {
        private TaskMapReadFinishedListener mCallback;
        public ReadTaskMapFromDiskRunnable(TaskMapReadFinishedListener callback) {
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                List<TaskStatus> tasks;
                FileInputStream fis = mTasksFile.openRead();
                synchronized (TaskStore.this) {
                    tasks = readTaskMapImpl(fis);
                }
                fis.close();
                if (tasks != null) {
                    mCallback.onTaskMapReadFinished(tasks);
                }
            } catch (FileNotFoundException e) {
                if (TaskManagerService.DEBUG) {
                    Slog.d(TAG, "Could not find tasks file, probably there was nothing to load.");
                }
            } catch (XmlPullParserException e) {
                if (TaskManagerService.DEBUG) {
                    Slog.d(TAG, "Error parsing xml.", e);
                }
            } catch (IOException e) {
                if (TaskManagerService.DEBUG) {
                    Slog.d(TAG, "Error parsing xml.", e);
                }
            }
        }

        private List<TaskStatus> readTaskMapImpl(FileInputStream fis) throws XmlPullParserException, IOException {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
                Slog.d(TAG, parser.getName());
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                if (DEBUG) {
                    Slog.d(TAG, "No persisted tasks.");
                }
                return null;
            }

            String tagName = parser.getName();
            if ("task-info".equals(tagName)) {
                final List<TaskStatus> tasks = new ArrayList<TaskStatus>();
                // Read in version info.
                try {
                    int version = Integer.valueOf(parser.getAttributeValue(null, "version"));
                    if (version != TASKS_FILE_VERSION) {
                        Slog.d(TAG, "Invalid version number, aborting tasks file read.");
                        return null;
                    }
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Invalid version number, aborting tasks file read.");
                    return null;
                }
                eventType = parser.next();
                do {
                    // Read each <task/>
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        // Start reading task.
                        if ("task".equals(tagName)) {
                            TaskStatus persistedTask = restoreTaskFromXml(parser);
                            if (persistedTask != null) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Read out " + persistedTask);
                                }
                                tasks.add(persistedTask);
                            } else {
                                Slog.d(TAG, "Error reading task from file.");
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
                return tasks;
            }
            return null;
        }

        /**
         * @param parser Xml parser at the beginning of a "<task/>" tag. The next "parser.next()" call
         *               will take the parser into the body of the task tag.
         * @return Newly instantiated task holding all the information we just read out of the xml tag.
         */
        private TaskStatus restoreTaskFromXml(XmlPullParser parser) throws XmlPullParserException,
                IOException {
            Task.Builder taskBuilder;
            int uid;

            // Read out task identifier attributes.
            try {
                taskBuilder = buildBuilderFromXml(parser);
                uid = Integer.valueOf(parser.getAttributeValue(null, "uid"));
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Error parsing task's required fields, skipping");
                return null;
            }

            int eventType;
            // Read out constraints tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);  // Push through to next START_TAG.

            if (!(eventType == XmlPullParser.START_TAG &&
                    XML_TAG_PARAMS_CONSTRAINTS.equals(parser.getName()))) {
                // Expecting a <constraints> start tag.
                return null;
            }
            try {
                buildConstraintsFromXml(taskBuilder, parser);
            } catch (NumberFormatException e) {
                Slog.d(TAG, "Error reading constraints, skipping.");
                return null;
            }
            parser.next(); // Consume </constraints>

            // Read out execution parameters tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (eventType != XmlPullParser.START_TAG) {
                return null;
            }

            Pair<Long, Long> runtimes;
            try {
                runtimes = buildExecutionTimesFromXml(parser);
            } catch (NumberFormatException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Error parsing execution time parameters, skipping.");
                }
                return null;
            }

            if (XML_TAG_PERIODIC.equals(parser.getName())) {
                try {
                    String val = parser.getAttributeValue(null, "period");
                    taskBuilder.setPeriodic(Long.valueOf(val));
                } catch (NumberFormatException e) {
                    Slog.d(TAG, "Error reading periodic execution criteria, skipping.");
                    return null;
                }
            } else if (XML_TAG_ONEOFF.equals(parser.getName())) {
                try {
                    if (runtimes.first != TaskStatus.NO_EARLIEST_RUNTIME) {
                        taskBuilder.setMinimumLatency(runtimes.first - SystemClock.elapsedRealtime());
                    }
                    if (runtimes.second != TaskStatus.NO_LATEST_RUNTIME) {
                        taskBuilder.setOverrideDeadline(
                                runtimes.second - SystemClock.elapsedRealtime());
                    }
                } catch (NumberFormatException e) {
                    Slog.d(TAG, "Error reading task execution criteria, skipping.");
                    return null;
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Invalid parameter tag, skipping - " + parser.getName());
                }
                // Expecting a parameters start tag.
                return null;
            }
            maybeBuildBackoffPolicyFromXml(taskBuilder, parser);

            parser.nextTag(); // Consume parameters end tag.

            // Read out extras Bundle.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (!(eventType == XmlPullParser.START_TAG && XML_TAG_EXTRAS.equals(parser.getName()))) {
                if (DEBUG) {
                    Slog.d(TAG, "Error reading extras, skipping.");
                }
                return null;
            }

            PersistableBundle extras = PersistableBundle.restoreFromXml(parser);
            taskBuilder.setExtras(extras);
            parser.nextTag(); // Consume </extras>

            return new TaskStatus(taskBuilder.build(), uid, runtimes.first, runtimes.second);
        }

        private Task.Builder buildBuilderFromXml(XmlPullParser parser) throws NumberFormatException {
            // Pull out required fields from <task> attributes.
            int taskId = Integer.valueOf(parser.getAttributeValue(null, "taskid"));
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            ComponentName cname = new ComponentName(packageName, className);

            return new Task.Builder(taskId, cname);
        }

        private void buildConstraintsFromXml(Task.Builder taskBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "unmetered");
            if (val != null) {
                taskBuilder.setRequiredNetworkCapabilities(Task.NetworkType.UNMETERED);
            }
            val = parser.getAttributeValue(null, "connectivity");
            if (val != null) {
                taskBuilder.setRequiredNetworkCapabilities(Task.NetworkType.ANY);
            }
            val = parser.getAttributeValue(null, "idle");
            if (val != null) {
                taskBuilder.setRequiresDeviceIdle(true);
            }
            val = parser.getAttributeValue(null, "charging");
            if (val != null) {
                taskBuilder.setRequiresCharging(true);
            }
        }

        /**
         * Builds the back-off policy out of the params tag. These attributes may not exist, depending
         * on whether the back-off was set when the task was first scheduled.
         */
        private void maybeBuildBackoffPolicyFromXml(Task.Builder taskBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "initial-backoff");
            if (val != null) {
                long initialBackoff = Long.valueOf(val);
                val = parser.getAttributeValue(null, "backoff-policy");
                int backoffPolicy = Integer.valueOf(val);  // Will throw NFE which we catch higher up.
                taskBuilder.setBackoffCriteria(initialBackoff, backoffPolicy);
            }
        }

        /**
         * Convenience function to read out and convert deadline and delay from xml into elapsed real
         * time.
         * @return A {@link android.util.Pair}, where the first value is the earliest elapsed runtime
         * and the second is the latest elapsed runtime.
         */
        private Pair<Long, Long> buildExecutionTimesFromXml(XmlPullParser parser)
                throws NumberFormatException {
            // Pull out execution time data.
            final long nowWallclock = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();

            long earliestRunTimeElapsed = TaskStatus.NO_EARLIEST_RUNTIME;
            long latestRunTimeElapsed = TaskStatus.NO_LATEST_RUNTIME;
            String val = parser.getAttributeValue(null, "deadline");
            if (val != null) {
                long latestRuntimeWallclock = Long.valueOf(val);
                long maxDelayElapsed =
                        Math.max(latestRuntimeWallclock - nowWallclock, 0);
                latestRunTimeElapsed = nowElapsed + maxDelayElapsed;
            }
            val = parser.getAttributeValue(null, "delay");
            if (val != null) {
                long earliestRuntimeWallclock = Long.valueOf(val);
                long minDelayElapsed =
                        Math.max(earliestRuntimeWallclock - nowWallclock, 0);
                earliestRunTimeElapsed = nowElapsed + minDelayElapsed;

            }
            return Pair.create(earliestRunTimeElapsed, latestRunTimeElapsed);
        }
    }
}