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

package com.android.server.wallpaper;

import android.app.ILocalWallpaperColorConsumer;
import android.graphics.RectF;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of local wallpaper color callbacks and their interested wallpaper regions.
 */
public class LocalColorRepository {
    /**
     * Maps local wallpaper color callbacks' binders to their interested wallpaper regions, which
     * are stored in a map of display Ids to wallpaper regions.
     * binder callback -> [display id: int] -> areas
     */
    ArrayMap<IBinder, SparseArray<ArraySet<RectF>>> mLocalColorAreas = new ArrayMap();
    RemoteCallbackList<ILocalWallpaperColorConsumer> mCallbacks = new RemoteCallbackList();

    /**
     * Add areas to a consumer
     * @param consumer
     * @param areas
     * @param displayId
     */
    public void addAreas(ILocalWallpaperColorConsumer consumer, List<RectF> areas, int displayId) {
        IBinder binder = consumer.asBinder();
        SparseArray<ArraySet<RectF>> displays = mLocalColorAreas.get(binder);
        ArraySet<RectF> displayAreas = null;
        if (displays == null) {
            try {
                consumer.asBinder().linkToDeath(() ->
                        mLocalColorAreas.remove(consumer.asBinder()), 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            displays = new SparseArray<>();
            mLocalColorAreas.put(binder, displays);
        } else {
            displayAreas = displays.get(displayId);
        }
        if (displayAreas == null) {
            displayAreas = new ArraySet(areas);
            displays.put(displayId, displayAreas);
        }

        for (int i = 0; i < areas.size(); i++) {
            displayAreas.add(areas.get(i));
        }
        mCallbacks.register(consumer);
    }

    /**
     * remove an area for a consumer
     * @param consumer
     * @param areas
     * @param displayId
     * @return the areas that are removed from all callbacks
     */
    public List<RectF> removeAreas(ILocalWallpaperColorConsumer consumer, List<RectF> areas,
            int displayId) {
        IBinder binder = consumer.asBinder();
        SparseArray<ArraySet<RectF>> displays = mLocalColorAreas.get(binder);
        ArraySet<RectF> registeredAreas = null;
        if (displays != null) {
            registeredAreas = displays.get(displayId);
            if (registeredAreas == null) {
                mCallbacks.unregister(consumer);
            } else {
                for (int i = 0; i < areas.size(); i++) {
                    registeredAreas.remove(areas.get(i));
                }
                if (registeredAreas.size() == 0) {
                    displays.remove(displayId);
                }
            }
            if (displays.size() == 0) {
                mLocalColorAreas.remove(binder);
                mCallbacks.unregister(consumer);
            }
        } else {
            mCallbacks.unregister(consumer);
        }
        ArraySet<RectF> purged = new ArraySet<>(areas);
        for (int i = 0; i < mLocalColorAreas.size(); i++) {
            for (int j = 0; j < mLocalColorAreas.valueAt(i).size(); j++) {
                for (int k = 0; k < mLocalColorAreas.valueAt(i).valueAt(j).size(); k++) {
                    purged.remove(mLocalColorAreas.valueAt(i).valueAt(j).valueAt(k));
                }
            }
        }
        return new ArrayList(purged);
    }

    /**
     * Return the local areas by display id
     * @param displayId
     * @return
     */
    public List<RectF> getAreasByDisplayId(int displayId) {
        ArrayList<RectF> areas = new ArrayList();
        for (int i = 0; i < mLocalColorAreas.size(); i++) {
            SparseArray<ArraySet<RectF>> displays = mLocalColorAreas.valueAt(i);
            if (displays == null) continue;
            ArraySet<RectF> displayAreas = displays.get(displayId);
            if (displayAreas == null) continue;
            for (int j = 0; j < displayAreas.size(); j++) {
                areas.add(displayAreas.valueAt(j));
            }
        }
        return areas;
    }

    /**
     * invoke a callback for each area of interest
     * @param callback
     * @param area
     * @param displayId
     */
    public void forEachCallback(Consumer<ILocalWallpaperColorConsumer> callback,
            RectF area, int displayId) {
        mCallbacks.broadcast(cb -> {
            IBinder binder = cb.asBinder();
            SparseArray<ArraySet<RectF>> displays = mLocalColorAreas.get(binder);
            if (displays == null) return;
            ArraySet<RectF> displayAreas = displays.get(displayId);
            if (displayAreas != null && displayAreas.contains(area)) callback.accept(cb);
        });
    }

    /**
     * For testing
     * @param callback
     * @return if the callback is registered
     */
    @VisibleForTesting
    protected boolean isCallbackAvailable(ILocalWallpaperColorConsumer callback) {
        return mLocalColorAreas.get(callback.asBinder()) != null;
    }
}
