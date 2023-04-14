/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.os.Build.IS_USER;

import static com.android.wm.shell.WmShellTransitionTraceProto.MAGIC_NUMBER;
import static com.android.wm.shell.WmShellTransitionTraceProto.MAGIC_NUMBER_H;
import static com.android.wm.shell.WmShellTransitionTraceProto.MAGIC_NUMBER_L;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.TraceBuffer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class to collect and dump transition traces.
 */
public class Tracer {
    private static final int ALWAYS_ON_TRACING_CAPACITY = 15 * 1024; // 15 KB

    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    static final String WINSCOPE_EXT = ".winscope";
    private static final String TRACE_FILE =
            "/data/misc/wmtrace/shell_transition_trace" + WINSCOPE_EXT;

    private final Object mEnabledLock = new Object();

    private final TraceBuffer mTraceBuffer = new TraceBuffer(ALWAYS_ON_TRACING_CAPACITY,
            (proto) -> handleOnEntryRemovedFromTrace(proto));
    private final Map<Object, Runnable> mRemovedFromTraceCallbacks = new HashMap<>();

    private final Map<Transitions.TransitionHandler, Integer> mHandlerIds = new HashMap<>();
    private final Map<Transitions.TransitionHandler, Integer> mHandlerUseCountInTrace =
            new HashMap<>();

    /**
     * Adds an entry in the trace to log that a transition has been dispatched to a handler.
     *
     * @param transitionId The id of the transition being dispatched.
     * @param handler The handler the transition is being dispatched to.
     */
    public void logDispatched(int transitionId, Transitions.TransitionHandler handler) {
        final int handlerId;
        if (mHandlerIds.containsKey(handler)) {
            handlerId = mHandlerIds.get(handler);
        } else {
            handlerId = mHandlerIds.size();
            mHandlerIds.put(handler, handlerId);
        }

        ProtoOutputStream outputStream = new ProtoOutputStream();
        final long protoToken =
                outputStream.start(com.android.wm.shell.WmShellTransitionTraceProto.TRANSITIONS);

        outputStream.write(com.android.wm.shell.Transition.ID, transitionId);
        outputStream.write(com.android.wm.shell.Transition.DISPATCH_TIME_NS,
                SystemClock.elapsedRealtimeNanos());
        outputStream.write(com.android.wm.shell.Transition.HANDLER, handlerId);

        outputStream.end(protoToken);

        final int useCountAfterAdd = mHandlerUseCountInTrace.getOrDefault(handler, 0) + 1;
        mHandlerUseCountInTrace.put(handler, useCountAfterAdd);

        mRemovedFromTraceCallbacks.put(outputStream, () -> {
            final int useCountAfterRemove = mHandlerUseCountInTrace.get(handler) - 1;
            mHandlerUseCountInTrace.put(handler, useCountAfterRemove);
        });

        mTraceBuffer.add(outputStream);
    }

    /**
     * Adds an entry in the trace to log that a request to merge a transition was made.
     *
     * @param mergeRequestedTransitionId The id of the transition we are requesting to be merged.
     * @param playingTransitionId The id of the transition we was to merge the transition into.
     */
    public void logMergeRequested(int mergeRequestedTransitionId, int playingTransitionId) {
        ProtoOutputStream outputStream = new ProtoOutputStream();
        final long protoToken =
                outputStream.start(com.android.wm.shell.WmShellTransitionTraceProto.TRANSITIONS);

        outputStream.write(com.android.wm.shell.Transition.ID, mergeRequestedTransitionId);
        outputStream.write(com.android.wm.shell.Transition.MERGE_REQUEST_TIME_NS,
                SystemClock.elapsedRealtimeNanos());
        outputStream.write(com.android.wm.shell.Transition.MERGED_INTO, playingTransitionId);

        outputStream.end(protoToken);

        mTraceBuffer.add(outputStream);
    }

    /**
     * Adds an entry in the trace to log that a transition was merged by the handler.
     *
     * @param mergedTransitionId The id of the transition that was merged.
     * @param playingTransitionId The id of the transition the transition was merged into.
     */
    public void logMerged(int mergedTransitionId, int playingTransitionId) {
        ProtoOutputStream outputStream = new ProtoOutputStream();
        final long protoToken =
                outputStream.start(com.android.wm.shell.WmShellTransitionTraceProto.TRANSITIONS);

        outputStream.write(com.android.wm.shell.Transition.ID, mergedTransitionId);
        outputStream.write(
                com.android.wm.shell.Transition.MERGE_TIME_NS, SystemClock.elapsedRealtimeNanos());
        outputStream.write(com.android.wm.shell.Transition.MERGED_INTO, playingTransitionId);

        outputStream.end(protoToken);

        mTraceBuffer.add(outputStream);
    }

    /**
     * Adds an entry in the trace to log that a transition was aborted.
     *
     * @param transitionId The id of the transition that was aborted.
     */
    public void logAborted(int transitionId) {
        ProtoOutputStream outputStream = new ProtoOutputStream();
        final long protoToken =
                outputStream.start(com.android.wm.shell.WmShellTransitionTraceProto.TRANSITIONS);

        outputStream.write(com.android.wm.shell.Transition.ID, transitionId);
        outputStream.write(
                com.android.wm.shell.Transition.ABORT_TIME_NS, SystemClock.elapsedRealtimeNanos());

        outputStream.end(protoToken);

        mTraceBuffer.add(outputStream);
    }

    /**
     * Being called while taking a bugreport so that tracing files can be included in the bugreport.
     *
     * @param pw Print writer
     */
    public void saveForBugreport(@Nullable PrintWriter pw) {
        if (IS_USER) {
            LogAndPrintln.e(pw, "Tracing is not supported on user builds.");
            return;
        }
        Trace.beginSection("TransitionTracer#saveForBugreport");
        synchronized (mEnabledLock) {
            final File outputFile = new File(TRACE_FILE);
            writeTraceToFileLocked(pw, outputFile);
        }
        Trace.endSection();
    }

    private void writeTraceToFileLocked(@Nullable PrintWriter pw, File file) {
        Trace.beginSection("TransitionTracer#writeTraceToFileLocked");
        try {
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
            writeHandlerMappingToProto(proto);
            int pid = android.os.Process.myPid();
            LogAndPrintln.i(pw, "Writing file to " + file.getAbsolutePath()
                    + " from process " + pid);
            mTraceBuffer.writeTraceToFile(file, proto);
        } catch (IOException e) {
            LogAndPrintln.e(pw, "Unable to write buffer to file", e);
        }
        Trace.endSection();
    }

    private void writeHandlerMappingToProto(ProtoOutputStream outputStream) {
        for (Transitions.TransitionHandler handler : mHandlerUseCountInTrace.keySet()) {
            final int count = mHandlerUseCountInTrace.get(handler);
            if (count > 0) {
                final long protoToken = outputStream.start(
                        com.android.wm.shell.WmShellTransitionTraceProto.HANDLER_MAPPINGS);
                outputStream.write(com.android.wm.shell.HandlerMapping.ID,
                        mHandlerIds.get(handler));
                outputStream.write(com.android.wm.shell.HandlerMapping.NAME,
                        handler.getClass().getName());
                outputStream.end(protoToken);
            }
        }
    }

    private void handleOnEntryRemovedFromTrace(Object proto) {
        if (mRemovedFromTraceCallbacks.containsKey(proto)) {
            mRemovedFromTraceCallbacks.get(proto).run();
            mRemovedFromTraceCallbacks.remove(proto);
        }
    }

    private static class LogAndPrintln {
        private static final String LOG_TAG = "ShellTransitionTracer";

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
