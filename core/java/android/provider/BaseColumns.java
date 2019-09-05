/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.provider;

import android.database.Cursor;

public interface BaseColumns {
    /**
     * The unique ID for a row.
     */
    @Column(Cursor.FIELD_TYPE_INTEGER)
    public static final String _ID = "_id";

    /**
     * The count of rows in a directory.
     */
    // @Column(Cursor.FIELD_TYPE_INTEGER)
    public static final String _COUNT = "_count";
}
