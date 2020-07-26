/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import android.annotation.Nullable;
import android.hardware.display.DisplayManager;
import android.util.SparseArray;
import android.view.Display;

import androidx.annotation.NonNull;

/**
 * Supplies the instance with given display Id. It generates a new instance if the corresponding
 * one is not existed. It should run in single thread to avoid race conditions.
 *
 * @param <T> the type of results supplied by {@link #createInstance(Display)}.
 */
abstract class DisplayIdIndexSupplier<T> {

    private final SparseArray<T> mSparseArray = new SparseArray<>();
    private final DisplayManager mDisplayManager;

    /**
     * @param displayManager DisplayManager
     */
    DisplayIdIndexSupplier(DisplayManager displayManager) {
        mDisplayManager = displayManager;
    }

    /**
     * @param displayId the logical display Id
     * @return {@code null} if the given display id is invalid
     */
    @Nullable
    public T get(int displayId) {
        T instance = mSparseArray.get(displayId);
        if (instance != null) {
            return instance;
        }
        final Display display = mDisplayManager.getDisplay(displayId);
        if (display == null) {
            return null;
        }
        instance = createInstance(display);
        mSparseArray.put(displayId, instance);
        return instance;
    }

    @NonNull
    protected abstract T createInstance(Display display);

    /**
     * Removes the instance with given display Id.
     *
     * @param displayId the logical display id
     */
    public void remove(int displayId) {
        mSparseArray.remove(displayId);
    }

    /**
     * Clears all elements.
     */
    public void clear() {
        mSparseArray.clear();
    }
}
