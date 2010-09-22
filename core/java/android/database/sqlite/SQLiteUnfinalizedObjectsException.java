/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.database.sqlite;

/**
 * Thrown if the database can't be closed because of some un-closed
 * Cursor or SQLiteStatement objects. Could happen when a thread is trying to close
 * the database while another thread still hasn't closed a Cursor on that database.
 * @hide
 */
public class SQLiteUnfinalizedObjectsException extends SQLiteException {
    public SQLiteUnfinalizedObjectsException() {}

    public SQLiteUnfinalizedObjectsException(String error) {
        super(error);
    }
}
