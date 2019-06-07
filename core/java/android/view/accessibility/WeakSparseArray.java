/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.accessibility;

import android.util.SparseArray;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;


final class WeakSparseArray<E> {

    private final ReferenceQueue<E> mRefQueue = new ReferenceQueue<>();
    private final SparseArray<WeakReferenceWithId<E>> mSparseArray = new SparseArray<>();

    public void append(int key, E value) {
        removeUnreachableValues();
        mSparseArray.append(key, new WeakReferenceWithId(value, mRefQueue, key));
    }

    public void remove(int key) {
        removeUnreachableValues();
        mSparseArray.remove(key);
    }

    public E get(int key) {
        removeUnreachableValues();
        WeakReferenceWithId<E> ref = mSparseArray.get(key);
        return ref != null ? ref.get() : null;
    }

    private void removeUnreachableValues() {
        for (Reference ref = mRefQueue.poll(); ref != null; ref = mRefQueue.poll()) {
            mSparseArray.remove(((WeakReferenceWithId) ref).mId);
        }
    }

    private static class WeakReferenceWithId<E> extends WeakReference<E> {

        final int mId;

        WeakReferenceWithId(E referent, ReferenceQueue<? super E> q, int id) {
            super(referent, q);
            mId = id;
        }
    }
}

