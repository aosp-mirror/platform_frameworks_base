/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.class2greylist;

import java.util.Locale;

public class Status {

    // Highlight "Error:" in red.
    private static final String ERROR = "\u001B[31mError: \u001B[0m";

    private final boolean mDebug;
    private boolean mHasErrors;

    public Status(boolean debug) {
        mDebug = debug;
    }

    public void debug(String msg, Object... args) {
        if (mDebug) {
            System.err.println(String.format(Locale.US, msg, args));
        }
    }

    public void error(Throwable t) {
        System.err.print(ERROR);
        t.printStackTrace(System.err);
        mHasErrors = true;
    }

    public void error(String message) {
        System.err.print(ERROR);
        System.err.println(message);
        mHasErrors = true;
    }

    public void greylistEntry(String signature) {
        System.out.println(signature);
    }

    public boolean ok() {
        return !mHasErrors;
    }
}
