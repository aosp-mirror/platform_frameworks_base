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

package android.flags;

import android.annotation.NonNull;

/**
 * Base class for constants read via {@link android.flags.FeatureFlags}.
 *
 * @param <T> The type of value that this flag stores. E.g. Boolean or String.
 *
 * @hide
 */
public interface Flag<T> {
    /** The namespace for a flag. Should combine uniquely with its name. */
    @NonNull
    String getNamespace();

    /** The name of the flag. Should combine uniquely with its namespace. */
    @NonNull
    String getName();

    /** The value of this flag if no override has been set. Null values are not supported. */
    @NonNull
    T getDefault();

    /** Returns true if the value of this flag can change at runtime. */
    default boolean isDynamic() {
        return false;
    }

    /**
     * Add human-readable details to the flag. Flag client's are not required to set this.
     *
     * See {@link #getLabel()}, {@link #getDescription()}, and {@link #getCategoryName()}.
     *
     * @return Returns `this`, to make a fluent api.
     */
    Flag<T> defineMetaData(String label, String description, String categoryName);

    /**
     * A human-readable name for the flag. Defaults to {@link #getName()}
     *
     * See {@link #defineMetaData(String, String, String)}
     */
    @NonNull
    default String getLabel() {
        return getName();
    }

    /**
     * A human-readable description for the flag. Defaults to null if unset.
     *
     * See {@link #defineMetaData(String, String, String)}
     */
    default String getDescription() {
        return null;
    }

    /**
     * A human-readable category name for the flag. Defaults to null if unset.
     *
     * See {@link #defineMetaData(String, String, String)}
     */
    default String getCategoryName() {
        return null;
    }
}
