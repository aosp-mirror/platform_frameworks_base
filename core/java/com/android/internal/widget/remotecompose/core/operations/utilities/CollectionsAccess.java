/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.utilities;

import android.annotation.Nullable;

/**
 * interface to allow expressions to access collections Todo define a convention for when access is
 * unavailable
 */
public interface CollectionsAccess {
    float getFloatValue(int id, int index);

    /**
     * Get the array of float if it is a float array
     *
     * @param id the id of the float array
     * @return
     */
    @Nullable
    float[] getFloats(int id);

    /**
     * Get the number of entries in the list
     *
     * @param id the id of the list
     * @return
     */
    int getListLength(int id);

    /**
     * get the id of an entry if the list is a list of id's
     *
     * @param listId the list id
     * @param index the index into the list
     * @return
     */
    int getId(int listId, int index);

    /**
     * Get the value as an integer
     *
     * @param listId the list id to access
     * @param index the index into the list
     * @return
     */
    default int getIntValue(int listId, int index) {
        return (int) getFloatValue(listId, index);
    }
}
