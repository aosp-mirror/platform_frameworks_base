/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.database.Cursor;

/**
 * This class can be used by external clients of CursorAdapter and
 * CursorTreeAdapter to define how the content of the adapter should be
 * filtered.
 * 
 * @see #runQuery(CharSequence)
 */
public interface FilterQueryProvider {
    /**
     * Runs a query with the specified constraint. This query is requested
     * by the filter attached to this adapter.
     *
     * Contract: when constraint is null or empty, the original results,
     * prior to any filtering, must be returned.
     *
     * @param constraint the constraint with which the query must
     *        be filtered
     *
     * @return a Cursor representing the results of the new query
     */
    Cursor runQuery(CharSequence constraint);
}
