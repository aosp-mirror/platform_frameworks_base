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

package com.android.server.utils;

import android.annotation.NonNull;

/**
 * A class that implements Snappable can generate a read-only copy its instances.  A
 * snapshot is like a clone except that it is only required to support read-only class
 * methods.  Snapshots are immutable.  Attempts to modify the state of a snapshot throw
 * {@link UnsupporteOperationException}.
 * @param <T> The type returned by the snapshot() method.
 */
public interface Snappable<T> {

    /**
     * Create an immutable copy of the object, suitable for read-only methods.  A snapshot
     * is free to omit state that is only needed for mutating methods.
     */
    @NonNull T snapshot();
}
