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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.util.ArrayMap;

import java.util.Map;
import java.util.Objects;

/**
 * The registry for in-process module interfaces.
 * <p>
 * In-process module interfaces should be named with the suffix {@code ManagerLocal} for
 * consistency.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public final class LocalManagerRegistry {
    private LocalManagerRegistry() {}

    @NonNull
    private static final Map<Class<?>, Object> sManagers = new ArrayMap<>();

    /**
     * Get a manager from the registry.
     *
     * @param managerClass the class that the manager implements
     * @return the manager, or {@code null} if not found
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> T getManager(@NonNull Class<T> managerClass) {
        synchronized (sManagers) {
            return (T) sManagers.get(managerClass);
        }
    }

    /**
     * Adds a manager to the registry.
     *
     * @param managerClass the class that the manager implements
     * @param manager the manager
     * @throws IllegalStateException if the manager class is already registered
     */
    public static <T> void addManager(@NonNull Class<T> managerClass, @NonNull T manager) {
        Objects.requireNonNull(managerClass, "managerClass");
        Objects.requireNonNull(manager, "manager");
        synchronized (sManagers) {
            if (sManagers.containsKey(managerClass)) {
                throw new IllegalStateException(managerClass.getName() + " is already registered");
            }
            sManagers.put(managerClass, manager);
        }
    }
}
