/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Denotes that a field is a {@link ContentProvider} column. It can be used as a
 * key for {@link ContentValues} when inserting or updating data, or as a
 * projection when querying.
 *
 * @hide
 */
@Documented
@Retention(RUNTIME)
@Target({FIELD})
public @interface Column {
    /**
     * The {@link Cursor#getType(int)} of the data stored in this column.
     */
    int value();

    /**
     * This column is read-only and cannot be defined during insert or updates.
     */
    boolean readOnly() default false;
}
