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
import android.database.MatrixCursor;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.Log;

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
    private final TaskList mTaskList = new TaskList();
    private boolean mHasBackgroundThread = false;

    DocumentLoader(MtpManager mtpManager, ContentResolver resolver) {
        mMtpManager = mtpManager;
        mResolver = resolver;
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
            int parentHandle = parent.mObjectHandle;
            // Need to pass the special value MtpManager.OBJECT_HANDLE_ROOT_CHILDREN to
            // getObjectHandles if we would like to obtain children under the root.
            if (parentHandle == CursorHelper.DUMMY_HANDLE_FOR_ROOT) {
                parentHandle = MtpManager.OBJECT_HANDLE_ROOT_CHILDREN;
            }
            task = new LoaderTask(parent, mMtpManager.getObjectHandles(
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
        if (!task.completed() && !mHasBackgroundThread) {
            mHasBackgroundThread = true;
            new BackgroundLoaderThread().start();
        }

        return task.createCursor(mResolver, columnNames);
    }

    synchronized void clearTasks(int deviceId) {
        mTaskList.clearTaskForDevice(deviceId);
    }

    synchronized void clearCompletedTasks() {
        mTaskList.clearCompletedTask();
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
                MtpObjectInfo[] objectInfos;
                try {
                    objectInfos = loadDocuments(mMtpManager, deviceId, handles);
                } catch (IOException exception) {
                    objectInfos = null;
                    Log.d(MtpDocumentsProvider.TAG, exception.getMessage());
                }
                synchronized (DocumentLoader.this) {
                    if (objectInfos != null) {
                        task.fillDocuments(objectInfos);
                        final boolean shouldNotify =
                                task.mLastNotified.getTime() <
                                new Date().getTime() - NOTIFY_PERIOD_MS ||
                                task.completed();
                        if (shouldNotify) {
                            task.notify(mResolver);
                        }
                    } else {
                        mTaskList.remove(task);
                    }
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
                if (!get(i).completed())
                    return get(i);
            }
            return null;
        }

        void clearTaskForDevice(int deviceId) {
            int i = 0;
            while (i < size()) {
                if (get(i).mIdentifier.mDeviceId == deviceId) {
                    remove(i);
                } else {
                    i++;
                }
            }
        }

        void clearCompletedTask() {
            int i = 0;
            while (i < size()) {
                if (get(i).completed()) {
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
        final Identifier mIdentifier;
        final int[] mObjectHandles;
        final MtpObjectInfo[] mObjectInfos;
        Date mLastNotified;
        int mNumLoaded;

        LoaderTask(Identifier identifier, int[] objectHandles) {
            mIdentifier = identifier;
            mObjectHandles = objectHandles;
            mObjectInfos = new MtpObjectInfo[mObjectHandles.length];
            mNumLoaded = 0;
            mLastNotified = new Date();
        }

        Cursor createCursor(ContentResolver resolver, String[] columnNames) {
            final MatrixCursor cursor = new MatrixCursor(columnNames);
            final Identifier rootIdentifier = new Identifier(
                    mIdentifier.mDeviceId, mIdentifier.mStorageId);
            for (int i = 0; i < mNumLoaded; i++) {
                CursorHelper.addToCursor(mObjectInfos[i], rootIdentifier, cursor.newRow());
            }
            final Bundle extras = new Bundle();
            extras.putBoolean(DocumentsContract.EXTRA_LOADING, !completed());
            cursor.setNotificationUri(resolver, createUri());
            cursor.respond(extras);
            return cursor;
        }

        boolean completed() {
            return mNumLoaded == mObjectInfos.length;
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

        void fillDocuments(MtpObjectInfo[] objectInfos) {
            for (int i = 0; i < objectInfos.length; i++) {
                mObjectInfos[mNumLoaded++] = objectInfos[i];
            }
        }

        private Uri createUri() {
            return DocumentsContract.buildChildDocumentsUri(
                    MtpDocumentsProvider.AUTHORITY, mIdentifier.toDocumentId());
        }
    }
}
