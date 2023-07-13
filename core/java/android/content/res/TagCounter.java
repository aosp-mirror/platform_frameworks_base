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

/**
 * Counter used to track the number of tags seen during manifest validation.
 *
 * {@hide}
 */
public class TagCounter {
    private static final int DEFAULT_MAX_COUNT = 512;

    private int mMaxValue;
    private int mCount;

    public TagCounter() {
        mMaxValue = DEFAULT_MAX_COUNT;
        mCount = 0;
    }

    void reset(int maxValue) {
        this.mMaxValue = maxValue;
        this.mCount = 0;
    }

    void increment() {
        mCount += 1;
    }

    public boolean isValid() {
        return mCount <= mMaxValue;
    }
}
