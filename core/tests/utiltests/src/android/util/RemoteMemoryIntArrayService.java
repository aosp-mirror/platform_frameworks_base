/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.util;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.IOException;

/**
 * Service to interact with a {@link MemoryIntArray} in another process.
 */
public class RemoteMemoryIntArrayService extends Service {
    private final Object mLock = new Object();

    private MemoryIntArray mArray;

    @Override
    public IBinder onBind(Intent intent) {
        return new android.util.IRemoteMemoryIntArray.Stub() {

            @Override
            public void create(int size) {
                synchronized (mLock) {
                    try {
                        mArray = new MemoryIntArray(size);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }

            @Override
            public MemoryIntArray peekInstance() {
                synchronized (mLock) {
                    return mArray;
                }
            }

            @Override
            public boolean isWritable() {
                synchronized (mLock) {
                    return mArray.isWritable();
                }
            }

            @Override
            public int get(int index) {
                synchronized (mLock) {
                    try {
                        return mArray.get(index);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }

            @Override
            public void set(int index, int value) {
                synchronized (mLock) {
                    try {
                        mArray.set(index, value);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }

            @Override
            public int size() {
                synchronized (mLock) {
                    return mArray.size();
                }
            }

            @Override
            public void close() {
                synchronized (mLock) {
                    try {
                        mArray.close();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }

            @Override
            public boolean isClosed() {
                synchronized (mLock) {
                    return mArray.isClosed();
                }
            }

            @Override
            public void accessLastElementInRemoteProcess(MemoryIntArray array) {
                try {
                    array.get(array.size() - 1);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }
}
