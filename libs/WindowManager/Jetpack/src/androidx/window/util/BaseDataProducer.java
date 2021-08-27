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

package androidx.window.util;

import androidx.annotation.NonNull;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Base class that provides the implementation for the callback mechanism of the
 * {@link DataProducer} API.
 *
 * @param <T> The type of data this producer returns through {@link #getData()}.
 */
public abstract class BaseDataProducer<T> implements DataProducer<T> {
    private final Set<Runnable> mCallbacks = new LinkedHashSet<>();

    @Override
    public final void addDataChangedCallback(@NonNull Runnable callback) {
        mCallbacks.add(callback);
    }

    @Override
    public final void removeDataChangedCallback(@NonNull Runnable callback) {
        mCallbacks.remove(callback);
    }

    /**
     * Called to notify all registered callbacks that the data provided by {@link #getData()} has
     * changed.
     */
    protected void notifyDataChanged() {
        for (Runnable callback : mCallbacks) {
            callback.run();
        }
    }
}
