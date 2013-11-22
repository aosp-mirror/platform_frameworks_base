/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.util;

import android.os.Handler;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Helper functions for dumping the state of system services.
 */
public final class DumpUtils {
    private DumpUtils() {
    }

    /**
     * Helper for dumping state owned by a handler thread.
     *
     * Because the caller might be holding an important lock that the handler is
     * trying to acquire, we use a short timeout to avoid deadlocks.  The process
     * is inelegant but this function is only used for debugging purposes.
     */
    public static void dumpAsync(Handler handler, final Dump dump, PrintWriter pw, long timeout) {
        final StringWriter sw = new StringWriter();
        if (handler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                PrintWriter lpw = new FastPrintWriter(sw);
                dump.dump(lpw);
                lpw.close();
            }
        }, timeout)) {
            pw.print(sw.toString());
        } else {
            pw.println("... timed out");
        }
    }

    public interface Dump {
        void dump(PrintWriter pw);
    }
}
