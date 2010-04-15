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

package android.database;

/**
 * An exception that indicates invoking {@link Cursor#requery()} on Main thread could cause ANR.
 * This exception should encourage apps to invoke {@link Cursor#requery()} in a background thread. 
 * @hide
 */
public class RequeryOnUiThreadException extends RuntimeException {
    public RequeryOnUiThreadException(String packageName) {
        super("In " + packageName + " Requery is executing on main (UI) thread. could cause ANR. " +
                "do it in background thread.");
    }
}
