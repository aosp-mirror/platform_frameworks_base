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

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.operations.utilities.ArrayAccess;
import com.android.internal.widget.remotecompose.core.operations.utilities.CollectionsAccess;
import com.android.internal.widget.remotecompose.core.operations.utilities.DataMap;
import com.android.internal.widget.remotecompose.core.operations.utilities.IntFloatMap;
import com.android.internal.widget.remotecompose.core.operations.utilities.IntIntMap;
import com.android.internal.widget.remotecompose.core.operations.utilities.IntMap;
import com.android.internal.widget.remotecompose.core.operations.utilities.NanMap;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Represents runtime state for a RemoteCompose document State includes things like the value of
 * variables
 */
public class RemoteComposeState implements CollectionsAccess {
    public static final int START_ID = 42;
    //    private static final int MAX_FLOATS = 500;
    private static final int MAX_COLORS = 200;

    private static final int MAX_DATA = 1000;
    private final IntMap<Object> mIntDataMap = new IntMap<>();
    private final IntMap<Boolean> mIntWrittenMap = new IntMap<>();
    private final HashMap<Object, Integer> mDataIntMap = new HashMap<>();
    private final IntFloatMap mFloatMap = new IntFloatMap(); // efficient cache
    private final IntIntMap mIntegerMap = new IntIntMap(); // efficient cache
    private final IntIntMap mColorMap = new IntIntMap(); // efficient cache
    private final IntMap<DataMap> mDataMapMap = new IntMap<>();
    private final IntMap<Object> mObjectMap = new IntMap<>();

    // path information
    private final IntMap<Object> mPathMap = new IntMap<>();
    private final IntMap<float[]> mPathData = new IntMap<>();

    private final boolean[] mColorOverride = new boolean[MAX_COLORS];
    @NonNull private final IntMap<ArrayAccess> mCollectionMap = new IntMap<>();

    private final boolean[] mDataOverride = new boolean[MAX_DATA];
    private final boolean[] mIntegerOverride = new boolean[MAX_DATA];
    private final boolean[] mFloatOverride = new boolean[MAX_DATA];

    private int mNextId = START_ID;
    @NonNull private int[] mIdMaps = new int[] {START_ID, NanMap.START_VAR, NanMap.START_ARRAY};
    @Nullable private RemoteContext mRemoteContext = null;

    /**
     * Get Object based on id. The system will cache things like bitmaps Paths etc. They can be
     * accessed with this command
     *
     * @param id
     * @return
     */
    @Nullable
    public Object getFromId(int id) {
        return mIntDataMap.get(id);
    }

    /**
     * true if the cache contain this id
     *
     * @param id
     * @return
     */
    public boolean containsId(int id) {
        return mIntDataMap.get(id) != null;
    }

    /** Return the id of an item from the cache. */
    public int dataGetId(@NonNull Object data) {
        Integer res = mDataIntMap.get(data);
        if (res == null) {
            return -1;
        }
        return res;
    }

    /**
     * Add an item to the cache. Generates an id for the item and adds it to the cache based on that
     * id.
     */
    public int cacheData(@NonNull Object item) {
        int id = nextId();
        mDataIntMap.put(item, id);
        mIntDataMap.put(id, item);
        return id;
    }

    /**
     * Add an item to the cache. Generates an id for the item and adds it to the cache based on that
     * id.
     */
    public int cacheData(@NonNull Object item, int type) {
        int id = nextId(type);
        mDataIntMap.put(item, id);
        mIntDataMap.put(id, item);
        return id;
    }

    /** Insert an item in the cache */
    public void cacheData(int id, @NonNull Object item) {
        mDataIntMap.put(item, id);
        mIntDataMap.put(id, item);
    }

    /** Insert an item in the cache */
    public void updateData(int id, @NonNull Object item) {
        if (!mDataOverride[id]) {
            Object previous = mIntDataMap.get(id);
            if (previous != item) {
                mDataIntMap.remove(previous);
                mDataIntMap.put(item, id);
                mIntDataMap.put(id, item);
                updateListeners(id);
            }
        }
    }

    /**
     * Get the path asociated with the Data
     *
     * @param id
     * @return
     */
    public Object getPath(int id) {
        return mPathMap.get(id);
    }

    /**
     * Cache a path object. Object will be cleared if you update path data.
     *
     * @param id number asociated with path
     * @param path the path object typically Android Path
     */
    public void putPath(int id, Object path) {
        mPathMap.put(id, path);
    }

    /**
     * The path data the Array of floats that is asoicated with the path It also removes the current
     * path object.
     *
     * @param id the integer asociated with the data and path
     * @param data the array of floats that represents the path
     */
    public void putPathData(int id, float[] data) {
        mPathData.put(id, data);
        mPathMap.remove(id);
    }

    /**
     * Get the path data asociated with the id
     *
     * @param id number that represents the path
     * @return path data
     */
    public float[] getPathData(int id) {
        return mPathData.get(id);
    }

    /**
     * Adds a data Override.
     *
     * @param id
     * @param item the new value
     */
    public void overrideData(int id, @NonNull Object item) {
        Object previous = mIntDataMap.get(id);
        if (previous != item) {
            mDataIntMap.remove(previous);
            mDataIntMap.put(item, id);
            mIntDataMap.put(id, item);
            mDataOverride[id] = true;
            updateListeners(id);
        }
    }

    /** Insert an item in the cache */
    public int cacheFloat(float item) {
        int id = nextId();
        mFloatMap.put(id, item);
        mIntegerMap.put(id, (int) item);
        return id;
    }

    /** Insert an item in the cache */
    public void cacheFloat(int id, float item) {
        mFloatMap.put(id, item);
    }

    /** Insert an float item in the cache */
    public void updateFloat(int id, float value) {
        if (!mFloatOverride[id]) {
            float previous = mFloatMap.get(id);
            if (previous != value) {
                mFloatMap.put(id, value);
                mIntegerMap.put(id, (int) value);
                updateListeners(id);
            }
        }
    }

    /**
     * Adds a float Override.
     *
     * @param id
     * @param value the new value
     */
    public void overrideFloat(int id, float value) {
        float previous = mFloatMap.get(id);
        if (previous != value) {
            mFloatMap.put(id, value);
            mIntegerMap.put(id, (int) value);
            mFloatOverride[id] = true;
            updateListeners(id);
        }
    }

    /** Insert an item in the cache */
    public int cacheInteger(int item) {
        int id = nextId();
        mIntegerMap.put(id, item);
        mFloatMap.put(id, item);
        return id;
    }

    /** Insert an integer item in the cache */
    public void updateInteger(int id, int value) {
        if (!mIntegerOverride[id]) {
            int previous = mIntegerMap.get(id);
            if (previous != value) {
                mFloatMap.put(id, value);
                mIntegerMap.put(id, value);
                updateListeners(id);
            }
        }
    }

    /**
     * Adds a integer Override.
     *
     * @param id
     * @param value the new value
     */
    public void overrideInteger(int id, int value) {
        int previous = mIntegerMap.get(id);
        if (previous != value) {
            mIntegerMap.put(id, value);
            mFloatMap.put(id, value);
            mIntegerOverride[id] = true;
            updateListeners(id);
        }
    }

    /**
     * get a float from the float cache
     *
     * @param id of the float value
     * @return the float value
     */
    public float getFloat(int id) {
        return mFloatMap.get(id);
    }

    /**
     * get an integer from the cache
     *
     * @param id of the integer value
     * @return the integer
     */
    public int getInteger(int id) {
        return mIntegerMap.get(id);
    }

    /**
     * Get the float value
     *
     * @param id
     * @return
     */
    public int getColor(int id) {
        return mColorMap.get(id);
    }

    /**
     * Modify the color at id.
     *
     * @param id
     * @param color
     */
    public void updateColor(int id, int color) {
        if (mColorOverride[id]) {
            return;
        }
        mColorMap.put(id, color);
        updateListeners(id);
    }

    private void updateListeners(int id) {
        ArrayList<VariableSupport> v = mVarListeners.get(id);
        if (v != null && mRemoteContext != null) {
            for (VariableSupport c : v) {
                c.markDirty();
            }
        }
    }

    /**
     * Adds a colorOverride. This is a list of ids and their colors optimized for playback;
     *
     * @param id
     * @param color
     */
    public void overrideColor(int id, int color) {
        mColorOverride[id] = true;
        mColorMap.put(id, color);
        updateListeners(id);
    }

    /** Clear the color Overrides */
    public void clearColorOverride() {
        for (int i = 0; i < mColorOverride.length; i++) {
            mColorOverride[i] = false;
        }
    }

    /**
     * Clear the data override
     *
     * @param id the data id to clear
     */
    public void clearDataOverride(int id) {
        mDataOverride[id] = false;
        updateListeners(id);
    }

    /**
     * Clear the integer override
     *
     * @param id the integer id to clear
     */
    public void clearIntegerOverride(int id) {
        mIntegerOverride[id] = false;
        updateListeners(id);
    }

    /**
     * Clear the float override
     *
     * @param id the float id to clear
     */
    public void clearFloatOverride(int id) {
        mFloatOverride[id] = false;
        updateListeners(id);
    }

    /**
     * Method to determine if a cached value has been written to the documents WireBuffer based on
     * its id.
     */
    public boolean wasNotWritten(int id) {
        return !mIntWrittenMap.get(id);
    }

    /** Method to mark that a value, represented by its id, has been written to the WireBuffer */
    public void markWritten(int id) {
        mIntWrittenMap.put(id, true);
    }

    /** Clear the record of the values that have been written to the WireBuffer. */
    public void reset() {
        mIntWrittenMap.clear();
        mDataIntMap.clear();
    }

    /**
     * Get the next available id
     *
     * @return
     */
    public int nextId() {
        return mNextId++;
    }

    /**
     * Get the next available id 0 is normal (float,int,String,color) 1 is VARIABLES 2 is
     * collections
     *
     * @return
     */
    public int nextId(int type) {
        if (0 == type) {
            return mNextId++;
        }
        return mIdMaps[type]++;
    }

    /**
     * Set the next id
     *
     * @param id
     */
    public void setNextId(int id) {
        mNextId = id;
    }

    @NonNull IntMap<ArrayList<VariableSupport>> mVarListeners = new IntMap<>();
    @NonNull ArrayList<VariableSupport> mAllVarListeners = new ArrayList<>();

    private void add(int id, @NonNull VariableSupport variableSupport) {
        ArrayList<VariableSupport> v = mVarListeners.get(id);
        if (v == null) {
            v = new ArrayList<VariableSupport>();
            mVarListeners.put(id, v);
        }
        v.add(variableSupport);
        mAllVarListeners.add(variableSupport);
    }

    /**
     * Commands that listen to variables add themselves.
     *
     * @param id
     * @param variableSupport
     */
    public void listenToVar(int id, @NonNull VariableSupport variableSupport) {
        add(id, variableSupport);
    }

    /**
     * Is any command listening to this variable
     *
     * @param id
     * @return
     */
    public boolean hasListener(int id) {
        return mVarListeners.get(id) != null;
    }

    /**
     * List of Commands that need to be updated
     *
     * @param context
     * @return
     */
    public int getOpsToUpdate(@NonNull RemoteContext context) {
        if (mVarListeners.get(RemoteContext.ID_CONTINUOUS_SEC) != null) {
            return 1;
        }
        if (mVarListeners.get(RemoteContext.ID_TIME_IN_SEC) != null) {
            return 1000;
        }
        if (mVarListeners.get(RemoteContext.ID_TIME_IN_MIN) != null) {
            return 1000 * 60;
        }
        return -1;
    }

    /**
     * Set the width of the overall document on screen.
     *
     * @param width
     */
    public void setWindowWidth(float width) {
        updateFloat(RemoteContext.ID_WINDOW_WIDTH, width);
    }

    /**
     * Set the width of the overall document on screen.
     *
     * @param height
     */
    public void setWindowHeight(float height) {
        updateFloat(RemoteContext.ID_WINDOW_HEIGHT, height);
    }

    public void addCollection(int id, @NonNull ArrayAccess collection) {
        mCollectionMap.put(id & 0xFFFFF, collection);
    }

    @Override
    public float getFloatValue(int id, int index) {
        return mCollectionMap.get(id & 0xFFFFF).getFloatValue(index); // TODO: potential npe
    }

    @Override
    public @Nullable float[] getFloats(int id) {
        return mCollectionMap.get(id & 0xFFFFF).getFloats(); // TODO: potential npe
    }

    @Override
    public int getId(int id, int index) {
        return mCollectionMap.get(id & 0xFFFFF).getId(index);
    }

    public void putDataMap(int id, @NonNull DataMap map) {
        mDataMapMap.put(id, map);
    }

    public @Nullable DataMap getDataMap(int id) {
        return mDataMapMap.get(id);
    }

    @Override
    public int getListLength(int id) {
        return mCollectionMap.get(id & 0xFFFFF).getLength();
    }

    public void setContext(@NonNull RemoteContext context) {
        mRemoteContext = context;
    }

    public void updateObject(int id, @NonNull Object value) {
        mObjectMap.put(id, value);
    }

    public @Nullable Object getObject(int id) {
        return mObjectMap.get(id);
    }
}
