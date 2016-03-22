/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.ContentResolver;
import android.database.Cursor;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;

/**
 * Loader for MTP document.
 * At the first request, the loader returns only first NUM_INITIAL_ENTRIES. Then it launches
 * background thread to load the rest documents and caches its result for next requests.
 * TODO: Rename this class to ObjectInfoLoader
 */
class DocumentLoader implements AutoCloseable {
    static final int NUM_INITIAL_ENTRIES = 10;
    static final int NUM_LOADING_ENTRIES = 20;
    static final int NOTIFY_PERIOD_MS = 500;

    private final MtpDeviceRecord mDevice;
    private final MtpManager mMtpManager;
    private final ContentResolver mResolver;
    private final MtpDatabase mDatabase;
    private final TaskList mTaskList = new TaskList();
    private Thread mBackgroundThread;

    DocumentLoader(MtpDeviceRecord device, MtpManager mtpManager, ContentResolver resolver,
                   MtpDatabase database) {
        mDevice = device;
        mMtpManager = mtpManager;
        mResolver = resolver;
        mDatabase = database;
    }

    /**
     * Queries the child documents of given parent.
     * It loads the first NUM_INITIAL_ENTRIES of object info, then launches the background thread
     * to load the rest.
     */
    synchronized Cursor queryChildDocuments(String[] columnNames, Identifier parent)
            throws IOException {
        assert parent.mDeviceId == mDevice.deviceId;

        LoaderTask task = mTaskList.findTask(parent);
        if (task == null) {
            if (parent.mDocumentId == null) {
                throw new FileNotFoundException("Parent not found.");
            }
            // TODO: Handle nit race around here.
            // 1. getObjectHandles.
            // 2. putNewDocument.
            // 3. startAddingChildDocuemnts.
            // 4. stopAddingChildDocuments - It removes the new document added at the step 2,
            //     because it is not updated between start/stopAddingChildDocuments.
            task = new LoaderTask(mMtpManager, mDatabase, mDevice.operationsSupported, parent);
            task.loadObjectHandles();
            task.loadObjectInfoList(NUM_INITIAL_ENTRIES);
        } else {
            // Once remove the existing task in order to add it to the head of the list.
            mTaskList.remove(task);
        }

        mTaskList.addFirst(task);
        if (task.getState() == LoaderTask.STATE_LOADING) {
            resume();
        }
        return task.createCursor(mResolver, columnNames);
    }

    /**
     * Resumes a background thread.
     */
    synchronized void resume() {
        if (mBackgroundThread == null) {
            mBackgroundThread = new BackgroundLoaderThread();
            mBackgroundThread.start();
        }
    }

    /**
     * Obtains next task to be run in background thread, or release the reference to background
     * thread.
     *
     * Worker thread that receives null task needs to exit.
     */
    @WorkerThread
    synchronized @Nullable LoaderTask getNextTaskOrReleaseBackgroundThread() {
        Preconditions.checkState(mBackgroundThread != null);

        final LoaderTask task = mTaskList.findRunningTask();
        if (task != null) {
            return task;
        }

        final Identifier identifier = mDatabase.getUnmappedDocumentsParent(mDevice.deviceId);
        if (identifier != null) {
            final LoaderTask existingTask = mTaskList.findTask(identifier);
            if (existingTask != null) {
                Preconditions.checkState(existingTask.getState() != LoaderTask.STATE_LOADING);
                mTaskList.remove(existingTask);
            }
            final LoaderTask newTask = new LoaderTask(
                    mMtpManager, mDatabase, mDevice.operationsSupported, identifier);
            newTask.loadObjectHandles();
            mTaskList.addFirst(newTask);
            return newTask;
        }

        mBackgroundThread = null;
        return null;
    }

    /**
     * Terminates background thread.
     */
    @Override
    public void close() throws InterruptedException {
        final Thread thread;
        synchronized (this) {
            mTaskList.clear();
            thread = mBackgroundThread;
        }
        if (thread != null) {
            thread.interrupt();
            thread.join();
        }
    }

    synchronized void clearCompletedTasks() {
        mTaskList.clearCompletedTasks();
    }

    synchronized void clearTask(Identifier parentIdentifier) {
        mTaskList.clearTask(parentIdentifier);
    }

    /**
     * Background thread to fetch object info.
     */
    private class BackgroundLoaderThread extends Thread {
        /**
         * Finds task that needs to be processed, then loads NUM_LOADING_ENTRIES of object info and
         * store them to the database. If it does not find a task, exits the thread.
         */
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (!Thread.interrupted()) {
                final LoaderTask task = getNextTaskOrReleaseBackgroundThread();
                if (task == null) {
                    return;
                }
                task.loadObjectInfoList(NUM_LOADING_ENTRIES);
                final boolean shouldNotify =
                        task.mLastNotified.getTime() <
                        new Date().getTime() - NOTIFY_PERIOD_MS ||
                        task.getState() != LoaderTask.STATE_LOADING;
                if (shouldNotify) {
                    task.notify(mResolver);
                }
            }
        }
    }

    /**
     * Task list that has helper methods to search/clear tasks.
     */
    private static class TaskList extends LinkedList<LoaderTask> {
        LoaderTask findTask(Identifier parent) {
            for (int i = 0; i < size(); i++) {
                if (get(i).mIdentifier.equals(parent))
                    return get(i);
            }
            return null;
        }

        LoaderTask findRunningTask() {
            for (int i = 0; i < size(); i++) {
                if (get(i).getState() == LoaderTask.STATE_LOADING)
                    return get(i);
            }
            return null;
        }

        void clearCompletedTasks() {
            int i = 0;
            while (i < size()) {
                if (get(i).getState() == LoaderTask.STATE_COMPLETED) {
                    remove(i);
                } else {
                    i++;
                }
            }
        }

        void clearTask(Identifier parentIdentifier) {
            for (int i = 0; i < size(); i++) {
                final LoaderTask task = get(i);
                if (task.mIdentifier.mDeviceId == parentIdentifier.mDeviceId &&
                        task.mIdentifier.mObjectHandle == parentIdentifier.mObjectHandle) {
                    remove(i);
                    return;
                }
            }
        }
    }

    /**
     * Loader task.
     * Each task is responsible for fetching child documents for the given parent document.
     */
    private static class LoaderTask {
        static final int STATE_START = 0;
        static final int STATE_LOADING = 1;
        static final int STATE_COMPLETED = 2;
        static final int STATE_ERROR = 3;

        final MtpManager mManager;
        final MtpDatabase mDatabase;
        final int[] mOperationsSupported;
        final Identifier mIdentifier;
        int[] mObjectHandles;
        int mState;
        Date mLastNotified;
        int mPosition;
        IOException mError;

        LoaderTask(MtpManager manager, MtpDatabase database, int[] operationsSupported,
                Identifier identifier) {
            assert operationsSupported != null;
            assert identifier.mDocumentType != MtpDatabaseConstants.DOCUMENT_TYPE_DEVICE;
            mManager = manager;
            mDatabase = database;
            mOperationsSupported = operationsSupported;
            mIdentifier = identifier;
            mObjectHandles = null;
            mState = STATE_START;
            mPosition = 0;
            mLastNotified = new Date();
        }

        synchronized void loadObjectHandles() {
            assert mState == STATE_START;
            int parentHandle = mIdentifier.mObjectHandle;
            // Need to pass the special value MtpManager.OBJECT_HANDLE_ROOT_CHILDREN to
            // getObjectHandles if we would like to obtain children under the root.
            if (mIdentifier.mDocumentType == MtpDatabaseConstants.DOCUMENT_TYPE_STORAGE) {
                parentHandle = MtpManager.OBJECT_HANDLE_ROOT_CHILDREN;
            }
            try {
                mObjectHandles = mManager.getObjectHandles(
                        mIdentifier.mDeviceId, mIdentifier.mStorageId, parentHandle);
                mState = STATE_LOADING;
            } catch (IOException error) {
                mError = error;
                mState = STATE_ERROR;
            }
        }

        /**
         * Returns a cursor that traverses the child document of the parent document handled by the
         * task.
         * The returned task may have a EXTRA_LOADING flag.
         */
        synchronized Cursor createCursor(ContentResolver resolver, String[] columnNames)
                throws IOException {
            final Bundle extras = new Bundle();
            switch (getState()) {
                case STATE_LOADING:
                    extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                    break;
                case STATE_ERROR:
                    throw mError;
            }

            final Cursor cursor =
                    mDatabase.queryChildDocuments(columnNames, mIdentifier.mDocumentId);
            cursor.setNotificationUri(resolver, createUri());
            cursor.respond(extras);

            return cursor;
        }

        /**
         * Stores object information into database.
         */
        void loadObjectInfoList(int count) {
            synchronized (this) {
                if (mState != STATE_LOADING) {
                    return;
                }
                if (mPosition == 0) {
                    try{
                        mDatabase.getMapper().startAddingDocuments(mIdentifier.mDocumentId);
                    } catch (FileNotFoundException error) {
                        mError = error;
                        mState = STATE_ERROR;
                        return;
                    }
                }
            }
            final ArrayList<MtpObjectInfo> infoList = new ArrayList<>();
            for (int chunkEnd = mPosition + count;
                    mPosition < mObjectHandles.length && mPosition < chunkEnd;
                    mPosition++) {
                try {
                    infoList.add(mManager.getObjectInfo(
                            mIdentifier.mDeviceId, mObjectHandles[mPosition]));
                } catch (IOException error) {
                    Log.e(MtpDocumentsProvider.TAG, "Failed to load object info", error);
                }
            }
            synchronized (this) {
                try {
                    mDatabase.getMapper().putChildDocuments(
                            mIdentifier.mDeviceId,
                            mIdentifier.mDocumentId,
                            mOperationsSupported,
                            infoList.toArray(new MtpObjectInfo[infoList.size()]));
                } catch (FileNotFoundException error) {
                    // Looks like the parent document information is removed.
                    // Adding documents has already cancelled in Mapper so we don't need to invoke
                    // stopAddingDocuments.
                    mError = error;
                    mState = STATE_ERROR;
                    return;
                }
                if (mPosition >= mObjectHandles.length) {
                    try{
                        mDatabase.getMapper().stopAddingDocuments(mIdentifier.mDocumentId);
                        mState = STATE_COMPLETED;
                    } catch (FileNotFoundException error) {
                        mError = error;
                        mState = STATE_ERROR;
                        return;
                    }
                }
            }
        }

        /**
         * Returns a state of the task.
         */
        int getState() {
            return mState;
        }

        /**
         * Notifies a change of child list of the document.
         */
        void notify(ContentResolver resolver) {
            resolver.notifyChange(createUri(), null, false);
            mLastNotified = new Date();
        }

        private Uri createUri() {
            return DocumentsContract.buildChildDocumentsUri(
                    MtpDocumentsProvider.AUTHORITY, mIdentifier.mDocumentId);
        }
    }
}
