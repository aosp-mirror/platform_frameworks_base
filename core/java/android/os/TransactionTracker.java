/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.os;

import android.util.Log;
import android.util.Size;
import com.android.internal.util.FastPrintWriter;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Class used to track binder transactions. It indexes the transactions by the stack trace.
 *
 * @hide
 */
public class TransactionTracker {
    private Map<String, Long> mTraces;

    private void resetTraces() {
        synchronized (this) {
            mTraces = new HashMap<String, Long>();
        }
    }

    TransactionTracker() {
        resetTraces();
    }

    public void addTrace() {
        String trace = Log.getStackTraceString(new Throwable());
        synchronized (this) {
            if (mTraces.containsKey(trace)) {
                mTraces.put(trace, mTraces.get(trace) + 1);
            } else {
                mTraces.put(trace, Long.valueOf(1));
            }
        }
    }

    public void writeTracesToFile(ParcelFileDescriptor fd) {
        if (mTraces.isEmpty()) {
            return;
        }

        PrintWriter pw = new FastPrintWriter(new FileOutputStream(fd.getFileDescriptor()));
        synchronized (this) {
            for (String trace : mTraces.keySet()) {
                pw.println("Count: " + mTraces.get(trace));
                pw.println("Trace: " + trace);
                pw.println();
            }
        }
        pw.flush();
    }

    public void clearTraces(){
        resetTraces();
    }
}
