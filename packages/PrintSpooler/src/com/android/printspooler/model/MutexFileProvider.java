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

package com.android.printspooler.model;

import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * This class provides a shared file to several threads. Only one thread
 * at a time can use the file. To acquire the file a thread has to
 * request it in a blocking call to {@link #acquireFile(OnReleaseRequestCallback)}.
 * The provided callback is optional and is used to notify the owning thread
 * when another one wants to acquire the file. In case a release is requested
 * the thread owning the file must release it as soon as possible. If no
 * callback is provided a thread that acquires the file must release it
 * as soon as possible, i.e. even if callback was provided the thread cannot
 * have the file for less time.
 */
public final class MutexFileProvider {
    private static final String LOG_TAG = "MutexFileProvider";

    private static final boolean DEBUG = true;

    private final Object mLock = new Object();

    private final File mFile;

    private Thread mOwnerThread;

    private OnReleaseRequestCallback mOnReleaseRequestCallback;

    public interface OnReleaseRequestCallback {
        public void onReleaseRequested(File file);
    }

    public MutexFileProvider(File file) throws IOException {
        mFile = file;
        if (file.exists()) {
            file.delete();
        }
        file.createNewFile();
    }

    public File acquireFile(OnReleaseRequestCallback callback) {
        synchronized (mLock) {
            // If this thread has the file, nothing to do.
            if (mOwnerThread == Thread.currentThread()) {
                return mFile;
            }

            // Another thread wants file ask for a release.
            if (mOwnerThread != null && mOnReleaseRequestCallback != null) {
                mOnReleaseRequestCallback.onReleaseRequested(mFile);
            }

            // Wait until the file is released.
            while (mOwnerThread != null) {
                try {
                    mLock.wait();
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }

            // Update the owner and the callback.
            mOwnerThread = Thread.currentThread();
            mOnReleaseRequestCallback = callback;

            if (DEBUG) {
                Log.i(LOG_TAG, "Acquired file: " + mFile + " by thread: " + mOwnerThread);
            }

            return mFile;
        }
    }

    public void releaseFile() {
        synchronized (mLock) {
            if (mOwnerThread != Thread.currentThread()) {
                return;
            }

            if (DEBUG) {
                Log.i(LOG_TAG, "Released file: " + mFile + " from thread: " + mOwnerThread);
            }

            // Update the owner and the callback.
            mOwnerThread = null;
            mOnReleaseRequestCallback = null;

            mLock.notifyAll();
        }
    }
}
