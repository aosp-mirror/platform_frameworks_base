/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.location.settings;

import static com.android.server.location.LocationManagerService.TAG;
import static com.android.server.location.settings.SettingsStore.VersionedSettings.VERSION_DOES_NOT_EXIST;

import android.util.AtomicFile;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/** Base class for read/write/versioning functionality for storing persistent settings to a file. */
abstract class SettingsStore<T extends SettingsStore.VersionedSettings> {

    interface VersionedSettings {
        /** Represents that the settings do not exist. */
        int VERSION_DOES_NOT_EXIST = Integer.MAX_VALUE;

        /** Must always return a version number less than {@link #VERSION_DOES_NOT_EXIST}. */
        int getVersion();
    }

    private final AtomicFile mFile;

    @GuardedBy("this")
    private boolean mInitialized;
    @GuardedBy("this")
    private T mCache;

    protected SettingsStore(File file) {
        mFile = new AtomicFile(file);
    }

    /**
     * Must be implemented to read in a settings instance, and upgrade to the appropriate version
     * where necessary. If the provided version is {@link VersionedSettings#VERSION_DOES_NOT_EXIST}
     * then the DataInput will be empty, and the method should return a settings instance with all
     * settings set to the default value.
     */
    protected abstract T read(int version, DataInput in) throws IOException;

    /**
     * Must be implemented to write the given settings to the given DataOutput.
     */
    protected abstract void write(DataOutput out, T settings) throws IOException;

    /**
     * Invoked when settings change, and while holding the internal lock. If used to invoke
     * listeners, ensure they are not invoked while holding the lock (ie, asynchronously).
     */
    protected abstract void onChange(T oldSettings, T newSettings);

    public final synchronized void initializeCache() {
        if (!mInitialized) {
            if (mFile.exists()) {
                try (DataInputStream is = new DataInputStream(mFile.openRead())) {
                    mCache = read(is.readInt(), is);
                    Preconditions.checkState(mCache.getVersion() < VERSION_DOES_NOT_EXIST);
                } catch (IOException e) {
                    Log.e(TAG, "error reading location settings (" + mFile
                            + "), falling back to defaults", e);
                }
            }

            if (mCache == null) {
                try {
                    mCache = read(VERSION_DOES_NOT_EXIST,
                            new DataInputStream(new ByteArrayInputStream(new byte[0])));
                    Preconditions.checkState(mCache.getVersion() < VERSION_DOES_NOT_EXIST);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }

            mInitialized = true;
        }
    }

    public final synchronized T get() {
        initializeCache();
        return mCache;
    }

    public synchronized void update(Function<T, T> updater) {
        initializeCache();

        T oldSettings = mCache;
        T newSettings = Objects.requireNonNull(updater.apply(oldSettings));
        if (oldSettings.equals(newSettings)) {
            return;
        }

        mCache = newSettings;
        Preconditions.checkState(mCache.getVersion() < VERSION_DOES_NOT_EXIST);

        writeLazily(newSettings);

        onChange(oldSettings, newSettings);
    }

    @VisibleForTesting
    synchronized void flushFile() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        BackgroundThread.getExecutor().execute(latch::countDown);
        latch.await();
    }

    @VisibleForTesting
    synchronized void deleteFile() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        BackgroundThread.getExecutor().execute(() -> {
            mFile.delete();
            latch.countDown();
        });
        latch.await();
    }

    private void writeLazily(T settings) {
        BackgroundThread.getExecutor().execute(() -> {
            FileOutputStream os = null;
            try {
                os = mFile.startWrite();
                DataOutputStream out = new DataOutputStream(os);
                out.writeInt(settings.getVersion());
                write(out, settings);
                mFile.finishWrite(os);
            } catch (IOException e) {
                mFile.failWrite(os);
                Log.e(TAG, "failure serializing location settings", e);
            } catch (Throwable e) {
                mFile.failWrite(os);
                throw e;
            }
        });
    }
}
