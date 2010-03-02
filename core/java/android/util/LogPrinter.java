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

package android.util;

/**
 * Implementation of a {@link android.util.Printer} that sends its output
 * to the system log.
 */
public class LogPrinter implements Printer {
    private final int mPriority;
    private final String mTag;
    private final int mBuffer;
    
    /**
     * Create a new Printer that sends to the log with the given priority
     * and tag.
     *
     * @param priority The desired log priority:
     * {@link android.util.Log#VERBOSE Log.VERBOSE},
     * {@link android.util.Log#DEBUG Log.DEBUG},
     * {@link android.util.Log#INFO Log.INFO},
     * {@link android.util.Log#WARN Log.WARN}, or
     * {@link android.util.Log#ERROR Log.ERROR}.
     * @param tag A string tag to associate with each printed log statement.
     */
    public LogPrinter(int priority, String tag) {
        mPriority = priority;
        mTag = tag;
        mBuffer = Log.LOG_ID_MAIN;
    }

    /**
     * @hide
     * Same as above, but buffer is one of the LOG_ID_ constants from android.util.Log.
     */
    public LogPrinter(int priority, String tag, int buffer) {
        mPriority = priority;
        mTag = tag;
        mBuffer = buffer;
    }
    
    public void println(String x) {
        Log.println_native(mBuffer, mPriority, mTag, x);
    }
}
