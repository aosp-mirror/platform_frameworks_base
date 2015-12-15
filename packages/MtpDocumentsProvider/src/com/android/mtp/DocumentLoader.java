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

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;

/**
 * Loader for MTP document.
 * At the first request, the loader returns only first NUM_INITIAL_ENTRIES. Then it launches
 * background thread to load the rest documents and caches its result for next requests.
 * TODO: Rename this class to ObjectInfoLoader
 */
class DocumentLoader {
    static final int NUM_INITIAL_ENTRIES = 10;
    static final int NUM_LOADING_ENTRIES = 20;
    static final int NOTIFY_PERIOD_MS = 500;

    private final MtpManager mMtpManager;
    private final ContentResolver mResolver;
    private final MtpDatabase mDatabase;
    private final TaskList mTaskList = new TaskList();
    private boolean mHasBackgroundThread = false;

    DocumentLoader(MtpManager mtpManager, ContentResolver resolver, MtpDatabase database) {
        mMtpManager = mtpManager;
        mResolver = resolver;
        mDatabase = database;
    }

    private static MtpObjectInfo[] loadDocuments(MtpManager manager, int deviceId, int[] handles)
            throws IOException {
        final MtpObjectInfo[] objectInfos = new MtpObjectInfo[handles.length];
        for (int i = 0; i < handles.length; i++) {
            objectInfos[i] = manager.getObjectInfo(deviceId, handles[i]);
        }
        return objectInfos;
    }

    synchronized Cursor queryChildDocuments(String[] columnNames, Identifier parent)
            throws IOException {
        LoaderTask task = mTaskList.findTask(parent);
        if (task == null) {
            if (parent.mDocumentId == null) {
                throw new FileNotFoundException("Parent not found.");
            }

            int parentHandle = parent.mObjectHandle;
            // Need to pass the special value MtpManager.OBJECT_HANDLE_ROOT_CHILDREN to
            // getObjectHandles if we would like to obtain children under the root.
            if (parentHandle == Identifier.DUMMY_HANDLE_FOR_ROOT) {
                parentHandle = MtpManager.OBJECT_HANDLE_ROOT_CHILDREN;
            }
            // TODO: Handle nit race around here.
            // 1. getObjectHandles.
            // 2. putNewDocument.
            // 3. startAddingChildDocuemnts.
            // 4. stopAddingChildDocuments - It removes the new document added at the step 2,
            //     because it is not updated between start/stopAddingChildDocuments.
            task = new LoaderTask(mDatabase, parent, mMtpManager.getObjectHandles(
                    parent.mDeviceId, parent.mStorageId, parentHandle));
            task.fillDocuments(loadDocuments(
                    mMtpManager,
                    parent.mDeviceId,
                    task.getUnloadedObjectHandles(NUM_INITIAL_ENTRIES)));
        } else {
            // Once remove the existing task in order to add it to the head of the list.
            mTaskList.remove(task);
        }

        mTaskList.addFirst(task);
        if (task.getState() == LoaderTask.STATE_LOADING && !mHasBackgroundThread) {
            mHasBackgroundThread = true;
            new BackgroundLoaderThread().start();
        }
        return task.createCursor(mResolver, columnNames);
    }

    synchronized void clearTasks() {
        mTaskList.clear();
    }

    synchronized void clearCompletedTasks() {
        mTaskList.clearCompletedTasks();
    }

    synchronized void clearTask(Identifier parentIdentifier) {
        mTaskList.clearTask(parentIdentifier);
    }

    private class BackgroundLoaderThread extends Thread {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                LoaderTask task;
                int deviceId;
                int[] handles;
                synchronized (DocumentLoader.this) {
                    task = mTaskList.findRunningTask();
                    if (task == null) {
                        mHasBackgroundThread = false;
                        return;
                    }
                    deviceId = task.mIdentifier.mDeviceId;
                    handles = task.getUnloadedObjectHandles(NUM_LOADING_ENTRIES);
                }

                try {
                    final MtpObjectInfo[] objectInfos =
                            loadDocuments(mMtpManager, deviceId, handles);
                    task.fillDocuments(objectInfos);
                    final boolean shouldNotify =
                            task.mLastNotified.getTime() <
                            new Date().getTime() - NOTIFY_PERIOD_MS ||
                            task.getState() != LoaderTask.STATE_LOADING;
                    if (shouldNotify) {
                        task.notify(mResolver);
                    }
                } catch (IOException exception) {
                    task.setError(exception);
                }
            }
        }
    }

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

    private static class LoaderTask {
        static final int STATE_LOADING = 0;
        static final int STATE_COMPLETED = 1;
        static final int STATE_ERROR = 2;

        final MtpDatabase mDatabase;
        final Identifier mIdentifier;
        final int[] mObjectHandles;
        Date mLastNotified;
        int mNumLoaded;
        Exception mError;

        LoaderTask(MtpDatabase database, Identifier identifier, int[] objectHandles) {
            mDatabase = database;
            mIdentifier = identifier;
            mObjectHandles = objectHandles;
            mNumLoaded = 0;
            mLastNotified = new Date();
        }

        Cursor createCursor(ContentResolver resolver, String[] columnNames) throws IOException {
            final Bundle extras = new Bundle();
            switch (getState()) {
                case STATE_LOADING:
                    extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                    break;
                case STATE_ERROR:
                    throw new IOException(mError);
            }

            final Cursor cursor =
                    mDatabase.queryChildDocuments(columnNames, mIdentifier.mDocumentId);
            cursor.setNotificationUri(resolver, createUri());
            cursor.respond(extras);

            return cursor;
        }

        int getState() {
            if (mError != null) {
                return STATE_ERROR;
            } else if (mNumLoaded == mObjectHandles.length) {
                return STATE_COMPLETED;
            } else {
                return STATE_LOADING;
            }
        }

        int[] getUnloadedObjectHandles(int count) {
            return Arrays.copyOfRange(
                    mObjectHandles,
                    mNumLoaded,
                    Math.min(mNumLoaded + count, mObjectHandles.length));
        }

        void notify(ContentResolver resolver) {
            resolver.notifyChange(createUri(), null, false);
            mLastNotified = new Date();
        }

        void fillDocuments(MtpObjectInfo[] objectInfoList) {
            if (objectInfoList.length == 0 || getState() != STATE_LOADING) {
                return;
            }
            if (mNumLoaded == 0) {
                mDatabase.getMapper().startAddingDocuments(mIdentifier.mDocumentId);
            }
            try {
                mDatabase.getMapper().putChildDocuments(
                        mIdentifier.mDeviceId, mIdentifier.mDocumentId, objectInfoList);
                mNumLoaded += objectInfoList.length;
            } catch (SQLiteException exp) {
                mError = exp;
                mNumLoaded = 0;
            }
            if (getState() != STATE_LOADING) {
                mDatabase.getMapper().stopAddingDocuments(mIdentifier.mDocumentId);
            }
        }

        void setError(Exception message) {
            final int lastState = getState();
            mError = message;
            mNumLoaded = 0;
            if (lastState == STATE_LOADING) {
                mDatabase.getMapper().stopAddingDocuments(mIdentifier.mDocumentId);
            }
        }

        private Uri createUri() {
            return DocumentsContract.buildChildDocumentsUri(
                    MtpDocumentsProvider.AUTHORITY, mIdentifier.mDocumentId);
        }
    }
}
