/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import android.util.SparseArray;

import java.util.HashMap;

public class ProcessMap<E> {
    final HashMap<String, SparseArray<E>> mMap
            = new HashMap<String, SparseArray<E>>();
    
    public E get(String name, int uid) {
        SparseArray<E> uids = mMap.get(name);
        if (uids == null) return null;
        return uids.get(uid);
    }
    
    public E put(String name, int uid, E value) {
        SparseArray<E> uids = mMap.get(name);
        if (uids == null) {
            uids = new SparseArray<E>(2);
            mMap.put(name, uids);
        }
        uids.put(uid, value);
        return value;
    }
    
    public void remove(String name, int uid) {
        SparseArray<E> uids = mMap.get(name);
        if (uids != null) {
            uids.remove(uid);
            if (uids.size() == 0) {
                mMap.remove(name);
            }
        }
    }
    
    public HashMap<String, SparseArray<E>> getMap() {
        return mMap;
    }
}
