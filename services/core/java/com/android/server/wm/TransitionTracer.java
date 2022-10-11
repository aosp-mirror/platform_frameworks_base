/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Build.IS_USER;

import static com.android.server.wm.shell.ChangeInfo.CHANGE_FLAGS;
import static com.android.server.wm.shell.ChangeInfo.HAS_CHANGED;
import static com.android.server.wm.shell.ChangeInfo.TRANSIT_MODE;
import static com.android.server.wm.shell.ChangeInfo.WINDOW_IDENTIFIER;
import static com.android.server.wm.shell.Transition.CHANGE;
import static com.android.server.wm.shell.Transition.FINISH_TRANSACTION_ID;
import static com.android.server.wm.shell.Transition.FLAGS;
import static com.android.server.wm.shell.Transition.ID;
import static com.android.server.wm.shell.Transition.START_TRANSACTION_ID;
import static com.android.server.wm.shell.Transition.STATE;
import static com.android.server.wm.shell.Transition.TIMESTAMP;
import static com.android.server.wm.shell.Transition.TRANSITION_TYPE;
import static com.android.server.wm.shell.TransitionTraceProto.MAGIC_NUMBER;
import static com.android.server.wm.shell.TransitionTraceProto.MAGIC_NUMBER_H;
import static com.android.server.wm.shell.TransitionTraceProto.MAGIC_NUMBER_L;
import static com.android.server.wm.shell.TransitionTraceProto.TRANSITION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.TraceBuffer;
import com.android.server.wm.Transition.ChangeInfo;
import com.android.server.wm.shell.TransitionTraceProto;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Helper class to collect and dump transition traces.
 */
public class TransitionTracer {

    private static final String LOG_TAG = "TransitionTracer";

    /**
     * Maximum buffer size, currently defined as 5 MB
     */
    private static final int BUFFER_CAPACITY = 5120 * 1024; // 5 MB
    static final String WINSCOPE_EXT = ".winscope";
    private static final String TRACE_FILE = "/data/misc/wmtrace/transition_trace" + WINSCOPE_EXT;
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final TransitionTraceBuffer mTraceBuffer = new TransitionTraceBuffer();

    private final Object mEnabledLock = new Object();
    private volatile boolean mEnabled = false;

    private long mTraceStartTimestamp;

    private class TransitionTraceBuffer {
        private final TraceBuffer mBuffer = new TraceBuffer(BUFFER_CAPACITY);

        private void pushTransitionState(Transition transition) {
            final ProtoOutputStream outputStream = new ProtoOutputStream();
            final long transitionEntryToken = outputStream.start(TRANSITION);

            outputStream.write(ID, transition.getSyncId());
            outputStream.write(TIMESTAMP, SystemClock.elapsedRealtimeNanos());
            outputStream.write(TRANSITION_TYPE, transition.mType);
            outputStream.write(STATE, transition.getState());
            outputStream.write(FLAGS, transition.getFlags());
            if (transition.getStartTransaction() != null) {
                outputStream.write(START_TRANSACTION_ID, transition.getStartTransaction().getId());
            }
            if (transition.getFinishTransaction() != null) {
                outputStream.write(FINISH_TRANSACTION_ID,
                        transition.getFinishTransaction().getId());
            }

            for (int i = 0; i < transition.mChanges.size(); ++i) {
                final WindowContainer window = transition.mChanges.keyAt(i);
                final ChangeInfo changeInfo = transition.mChanges.valueAt(i);
                writeChange(outputStream, window, changeInfo);
            }

            outputStream.end(transitionEntryToken);

            mBuffer.add(outputStream);
        }

        private void writeChange(ProtoOutputStream outputStream, WindowContainer window,
                ChangeInfo changeInfo) {
            Trace.beginSection("TransitionProto#addChange");
            final long changeEntryToken = outputStream.start(CHANGE);

            final int transitMode = changeInfo.getTransitMode(window);
            final boolean hasChanged = changeInfo.hasChanged(window);
            final int changeFlags = changeInfo.getChangeFlags(window);

            outputStream.write(TRANSIT_MODE, transitMode);
            outputStream.write(HAS_CHANGED, hasChanged);
            outputStream.write(CHANGE_FLAGS, changeFlags);
            window.writeIdentifierToProto(outputStream, WINDOW_IDENTIFIER);

            outputStream.end(changeEntryToken);
            Trace.endSection();
        }

        public void writeToFile(File file, ProtoOutputStream proto) throws IOException {
            mBuffer.writeTraceToFile(file, proto);
        }

        public void reset() {
            mBuffer.resetBuffer();
        }
    }

    /**
     * Records the current state of a transition in the transition trace (if it is running).
     * @param transition the transition that we want to record the state of.
     */
    public void logState(com.android.server.wm.Transition transition) {
        if (!mEnabled) {
            return;
        }

        Log.d(LOG_TAG, "Logging state of transition " + transition);
        mTraceBuffer.pushTransitionState(transition);
    }

    /**
     * Starts collecting transitions for the trace.
     * If called while a trace is already running, this will reset the trace.
     */
    public void startTrace(@Nullable PrintWriter pw) {
        if (IS_USER) {
            LogAndPrintln.e(pw, "Tracing is not supported on user builds.");
            return;
        }
        Trace.beginSection("TransitionTracer#startTrace");
        LogAndPrintln.i(pw, "Starting shell transition trace.");
        synchronized (mEnabledLock) {
            mTraceStartTimestamp = SystemClock.elapsedRealtime();
            mEnabled = true;
            mTraceBuffer.reset();
        }
        Trace.endSection();
    }

    /**
     * Stops collecting the transition trace and dump to trace to file.
     *
     * Dumps the trace to @link{TRACE_FILE}.
     */
    public void stopTrace(@Nullable PrintWriter pw) {
        stopTrace(pw, new File(TRACE_FILE));
    }

    /**
     * Stops collecting the transition trace and dump to trace to file.
     * @param outputFile The file to dump the transition trace to.
     */
    public void stopTrace(@Nullable PrintWriter pw, File outputFile) {
        if (IS_USER) {
            LogAndPrintln.e(pw, "Tracing is not supported on user builds.");
            return;
        }
        Trace.beginSection("TransitionTracer#stopTrace");
        LogAndPrintln.i(pw, "Stopping shell transition trace.");
        synchronized (mEnabledLock) {
            if (!mEnabled) {
                LogAndPrintln.e(pw,
                        "Error: Tracing can't be stopped because it hasn't been started.");
                return;
            }

            mEnabled = false;
            writeTraceToFileLocked(pw, outputFile);
        }
        Trace.endSection();
    }

    boolean isEnabled() {
        return mEnabled;
    }

    private void writeTraceToFileLocked(@Nullable PrintWriter pw, File file) {
        Trace.beginSection("TransitionTracer#writeTraceToFileLocked");
        try {
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
            proto.write(TransitionTraceProto.TIMESTAMP, mTraceStartTimestamp);
            int pid = android.os.Process.myPid();
            LogAndPrintln.i(pw, "Writing file to " + file.getAbsolutePath()
                    + " from process " + pid);
            mTraceBuffer.writeToFile(file, proto);
        } catch (IOException e) {
            LogAndPrintln.e(pw, "Unable to write buffer to file", e);
        }
        Trace.endSection();
    }

    private static class LogAndPrintln {
        private static void i(@Nullable PrintWriter pw, String msg) {
            Log.i(LOG_TAG, msg);
            if (pw != null) {
                pw.println(msg);
                pw.flush();
            }
        }

        private static void e(@Nullable PrintWriter pw, String msg) {
            Log.e(LOG_TAG, msg);
            if (pw != null) {
                pw.println("ERROR: " + msg);
                pw.flush();
            }
        }

        private static void e(@Nullable PrintWriter pw, String msg, @NonNull Exception e) {
            Log.e(LOG_TAG, msg, e);
            if (pw != null) {
                pw.println("ERROR: " + msg + " ::\n " + e);
                pw.flush();
            }
        }
    }
}
