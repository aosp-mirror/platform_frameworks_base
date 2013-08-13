/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.printspooler;

import android.database.DataSetObservable;

/**
 * This is the simple contract for data providers.
 *
 * @param <T> The type of the providers data.
 */
public abstract class DataProvider<T> extends DataSetObservable {

    /**
     * Gets the number of items.
     *
     * @return The item count.
     */
    public abstract int getItemCount();

    /**
     * Gets the index of an item.
     *
     * @param item The item.
     * @return The item index.
     */
    public abstract int getItemIndex(T item);

    /**
     * Gets an item at a given position.
     *
     * @param index The position.
     * @return The item.
     */
    public abstract T getItemAt(int index);
}
