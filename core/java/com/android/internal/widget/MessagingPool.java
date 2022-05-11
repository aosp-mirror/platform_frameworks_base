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

package com.android.internal.widget;

import android.util.Log;
import android.util.Pools;
import android.view.View;

/**
 * A trivial wrapper around Pools.SynchronizedPool which allows clearing the pool, as well as
 * disabling the pool class altogether.
 * @param <T> the type of object in the pool
 */
public class MessagingPool<T extends View> implements Pools.Pool<T> {
    private static final boolean ENABLED = false;  // disabled to test b/208508846
    private static final String TAG = "MessagingPool";
    private final int mMaxPoolSize;
    private Pools.SynchronizedPool<T> mCurrentPool;

    public MessagingPool(int maxPoolSize) {
        mMaxPoolSize = maxPoolSize;
        if (ENABLED) {
            mCurrentPool = new Pools.SynchronizedPool<>(mMaxPoolSize);
        }
    }

    @Override
    public T acquire() {
        if (!ENABLED) {
            return null;
        }
        T instance = mCurrentPool.acquire();
        if (instance.getParent() != null) {
            Log.wtf(TAG, "acquired " + instance + " with parent " + instance.getParent());
            return null;
        }
        return instance;
    }

    @Override
    public boolean release(T instance) {
        if (instance.getParent() != null) {
            Log.wtf(TAG, "releasing " + instance + " with parent " + instance.getParent());
            return false;
        }
        if (!ENABLED) {
            return false;
        }
        return mCurrentPool.release(instance);
    }

    /** Clear the pool */
    public void clear() {
        if (ENABLED) {
            mCurrentPool = new Pools.SynchronizedPool<>(mMaxPoolSize);
        }
    }

}
