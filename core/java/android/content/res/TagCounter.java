/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.res;

import android.annotation.NonNull;
import android.util.Pools.SimplePool;

/**
 * Counter used to track the number of tags seen during manifest validation.
 *
 * {@hide}
 */
public class TagCounter {
    private static final int MAX_POOL_SIZE = 512;
    private static final int DEFAULT_MAX_COUNT = 512;

    private static final ThreadLocal<SimplePool<TagCounter>> sPool =
            ThreadLocal.withInitial(() -> new SimplePool<>(MAX_POOL_SIZE));

    private int mMaxValue;
    private int mCount;

    @NonNull
    static TagCounter obtain(int max) {
        TagCounter counter = sPool.get().acquire();
        if (counter == null) {
            counter = new TagCounter();
        }
        counter.setMaxValue(max);
        return counter;
    }

    void recycle() {
        mCount = 0;
        mMaxValue = DEFAULT_MAX_COUNT;
        sPool.get().release(this);
    }

    public TagCounter() {
        mMaxValue = DEFAULT_MAX_COUNT;
        mCount = 0;
    }

    private void setMaxValue(int maxValue) {
        this.mMaxValue = maxValue;
    }

    void increment() {
        mCount += 1;
    }

    public boolean isValid() {
        return mCount <= mMaxValue;
    }

    int value() {
        return mCount;
    }
}
