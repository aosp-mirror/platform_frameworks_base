/*
 * Copyright 2014 The Android Open Source Project
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

import android.os.Binder;

class KeySetHandle extends Binder{
    private final long mId;
    private int mRefCount;

    protected KeySetHandle(long id) {
        mId = id;
        mRefCount = 1;
    }

    /*
     * Only used when reading state from packages.xml
     */
    protected KeySetHandle(long id, int refCount) {
        mId = id;
        mRefCount = refCount;
    }

    public long getId() {
        return mId;
    }

    protected int getRefCountLPr() {
        return mRefCount;
    }

    /*
     * Only used when reading state from packages.xml
     */
    protected void setRefCountLPw(int newCount) {
         mRefCount = newCount;
         return;
    }

    protected void incrRefCountLPw() {
        mRefCount++;
        return;
    }

    protected int decrRefCountLPw() {
        mRefCount--;
        return mRefCount;
    }
}
