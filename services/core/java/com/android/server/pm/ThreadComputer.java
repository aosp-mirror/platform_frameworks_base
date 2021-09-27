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

package com.android.server.pm;

/**
 * This class records the Computer being used by a thread and the Computer's reference
 * count.  There is a thread-local copy of this class.
 */
public final class ThreadComputer {
    Computer mComputer = null;
    int mRefCount = 0;

    void acquire(Computer c) {
        if (mRefCount != 0 && mComputer != c) {
            throw new RuntimeException("computer mismatch, count = " + mRefCount);
        }
        mComputer = c;
        mRefCount++;
    }

    void acquire() {
        if (mRefCount == 0 || mComputer == null) {
            throw new RuntimeException("computer acquire on empty ref count");
        }
        mRefCount++;
    }

    void release() {
        if (--mRefCount == 0) {
            mComputer = null;
        }
    }
}
