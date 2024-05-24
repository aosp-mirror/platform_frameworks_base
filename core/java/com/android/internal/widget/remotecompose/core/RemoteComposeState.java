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
package com.android.internal.widget.remotecompose.core;

import com.android.internal.widget.remotecompose.core.operations.utilities.IntMap;

import java.util.HashMap;

/**
 * Represents runtime state for a RemoteCompose document
 */
public class RemoteComposeState {

    private final IntMap<Object> mIntDataMap = new IntMap<>();
    private final IntMap<Boolean> mIntWrittenMap = new IntMap<>();
    private final HashMap<Object, Integer> mDataIntMap = new HashMap();

    private static int sNextId = 42;

    public Object getFromId(int id)  {
        return mIntDataMap.get(id);
    }

    public boolean containsId(int id)  {
        return mIntDataMap.get(id) != null;
    }

    /**
     * Return the id of an item from the cache.
     */
    public int dataGetId(Object image) {
        Integer res = mDataIntMap.get(image);
        if (res == null) {
            return -1;
        }
        return res;
    }

    /**
     * Add an image to the cache. Generates an id for the image and adds it to the cache based on
     * that id.
     */
    public int cache(Object image) {
        int id = nextId();
        mDataIntMap.put(image, id);
        mIntDataMap.put(id, image);
        return id;
    }

    /**
     * Insert an item in the cache
     */
    public void cache(int id, Object item) {
        mDataIntMap.put(item, id);
        mIntDataMap.put(id, item);
    }

    /**
     * Method to determine if a cached value has been written to the documents WireBuffer based on
     * its id.
     */
    public boolean wasNotWritten(int id) {
        return !mIntWrittenMap.get(id);
    }

    /**
     * Method to mark that a value, represented by its id, has been written to the WireBuffer
     */
    public void  markWritten(int id) {
        mIntWrittenMap.put(id, true);
    }

    /**
     *  Clear the record of the values that have been written to the WireBuffer.
     */
    void reset() {
        mIntWrittenMap.clear();
    }

    public static int nextId() {
        return sNextId++;
    }
    public static void setNextId(int id) {
        sNextId = id;
    }

}
