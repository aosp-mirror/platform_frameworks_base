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

package android.util;

/**
 * Logging access for platform metrics.
 *
 * <p>This is <b>not</b> the main "logcat" debugging log ({@link android.util.Log})!
 * These diagnostic stats are for system integrators, not application authors.
 *
 * <p>Stats use integer tag codes.
 * They carry a payload of one or more int, long, or String values.
 * @hide
 */
public class StatsLog {
    /** @hide */ public StatsLog() {}

    private static final String TAG = "StatsLog";

    // We assume that the native methods deal with any concurrency issues.

    /**
     * Records an stats log message.
     * @param tag The stats type tag code
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeInt(int tag, int value);

    /**
     * Records an stats log message.
     * @param tag The stats type tag code
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeLong(int tag, long value);

    /**
     * Records an stats log message.
     * @param tag The stats type tag code
     * @param value A value to log
     * @return The number of bytes written
     */
    public static native int writeFloat(int tag, float value);

    /**
     * Records an stats log message.
     * @param tag The stats type tag code
     * @param str A value to log
     * @return The number of bytes written
     */
    public static native int writeString(int tag, String str);

    /**
     * Records an stats log message.
     * @param tag The stats type tag code
     * @param list A list of values to log. All values should
     * be of type int, long, float or String.
     * @return The number of bytes written
     */
    public static native int writeArray(int tag, Object... list);
}
