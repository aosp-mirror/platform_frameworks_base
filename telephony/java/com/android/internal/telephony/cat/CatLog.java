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

package com.android.internal.telephony.cat;

import android.util.Log;

public abstract class CatLog {
    static final boolean DEBUG = true;

    public static void d(Object caller, String msg) {
        if (!DEBUG) {
            return;
        }

        String className = caller.getClass().getName();
        Log.d("CAT", className.substring(className.lastIndexOf('.') + 1) + ": "
                + msg);
    }

    public static void d(String caller, String msg) {
        if (!DEBUG) {
            return;
        }

        Log.d("CAT", caller + ": " + msg);
    }
}
