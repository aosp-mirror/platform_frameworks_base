/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

/**
 * Describes a custom SQL function.
 *
 * @hide
 */
public final class SQLiteCustomFunction {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public final String name;
    @UnsupportedAppUsage
    public final int numArgs;
    public final SQLiteDatabase.CustomFunction callback;

    /**
     * Create custom function.
     *
     * @param name The name of the sqlite3 function.
     * @param numArgs The number of arguments for the function, or -1 to
     * support any number of arguments.
     * @param callback The callback to invoke when the function is executed.
     */
    public SQLiteCustomFunction(String name, int numArgs,
            SQLiteDatabase.CustomFunction callback) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null.");
        }

        this.name = name;
        this.numArgs = numArgs;
        this.callback = callback;
    }

    // Called from native.
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private void dispatchCallback(String[] args) {
        callback.callback(args);
    }
}
