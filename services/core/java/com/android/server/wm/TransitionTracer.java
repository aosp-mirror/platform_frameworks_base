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
import static com.android.server.wm.shell.ChangeInfo.WINDOWING_MODE;
import static com.android.server.wm.shell.ChangeInfo.WINDOW_IDENTIFIER;
import static com.android.server.wm.shell.TransitionInfoChange.LAYER_ID;
import static com.android.server.wm.shell.TransitionInfoChange.MODE;
import static com.android.server.wm.shell.TransitionState.CHANGE;
import static com.android.server.wm.shell.TransitionTraceProto.MAGIC_NUMBER;
import static com.android.server.wm.shell.TransitionTraceProto.MAGIC_NUMBER_H;
import static com.android.server.wm.shell.TransitionTraceProto.MAGIC_NUMBER_L;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.window.TransitionInfo;

import com.android.internal.util.TraceBuffer;
import com.android.server.wm.Transition.ChangeInfo;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Helper class to collect and dump transition traces.
 */
public class TransitionTracer {

    private static final String LOG_TAG = "TransitionTracer";

    private static final int ALWAYS_ON_TRACING_CAPACITY = 15 * 1024; // 15 KB
    private static final int ACTIVE_TRACING_BUFFER_CAPACITY = 5000 * 1024; // 5 MB
    static final String WINSCOPE_EXT = ".winscope";
    private static final String TRACE_FILE = "/data/misc/wmtrace/transition_trace" + WINSCOPE_EXT;
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final TransitionTraceBuffer mTraceBuffer = new TransitionTraceBuffer();

    private final Object mEnabledLock = new Object();
    private volatile boolean mActiveTracingEnabled = false;

    /**
     * Records key information about a transition that has been sent to Shell to be played.
     * @param transition The transition that has been sent to Shell.
     * @param targets Information about the target windows of the transition.
     * @param createTimeNs System elapsed time (nanoseconds since boot including sleep time) at
 *                     which the transition to be recorded was created.
     * @param sendTimeNs System elapsed time (nanoseconds since boot including sleep time) at which
     * @param info
     */
    public void logSentTransition(Transition transition, ArrayList<ChangeInfo> targets,
            long createTimeNs, long sendTimeNs, TransitionInfo info) {
        mTraceBuffer.pushSentTransition(transition, targets, createTimeNs, sendTimeNs);
        logTransitionInfo(transition, info);
    }

    /**
     * Records the current state of a transition in the transition trace (if it is running).
     * @param transition the transition that we want to record the state of.
     */
    public void logState(com.android.server.wm.Transition transition) {
        if (!mActiveTracingEnabled) {
            return;
        }
        mTraceBuffer.pushTransitionState(transition);
    }

    /**
     * Records the transition info that is being sent over to Shell.
     * @param transition The transition the info is associated with.
     * @param info The transition info we want to log.
     */
    private void logTransitionInfo(Transition transition, TransitionInfo info) {
        if (!mActiveTracingEnabled) {
            return;
        }
        mTraceBuffer.pushTransitionInfo(transition, info);
    }

    private class TransitionTraceBuffer {
        private final TraceBuffer mBuffer = new TraceBuffer(ALWAYS_ON_TRACING_CAPACITY);
        private final TraceBuffer mStateBuffer = new TraceBuffer(ACTIVE_TRACING_BUFFER_CAPACITY);
        private final TraceBuffer mTransitionInfoBuffer =
                new TraceBuffer(ACTIVE_TRACING_BUFFER_CAPACITY);

        public void pushSentTransition(
                Transition transition,
                ArrayList<ChangeInfo> targets,
                long createTimeNs,
                long sendTimeNs
        ) {
            Trace.beginSection("TransitionTraceBuffer#pushSentTransition");
            final ProtoOutputStream outputStream = new ProtoOutputStream();
            final long transitionToken = outputStream
                    .start(com.android.server.wm.shell.TransitionTraceProto.SENT_TRANSITIONS);

            if (mActiveTracingEnabled) {
                outputStream.write(com.android.server.wm.shell.Transition.ID,
                        transition.getSyncId());
            }

            outputStream.write(com.android.server.wm.shell.Transition.START_TRANSACTION_ID,
                    transition.getStartTransaction().getId());
            outputStream.write(com.android.server.wm.shell.Transition.FINISH_TRANSACTION_ID,
                    transition.getFinishTransaction().getId());

            outputStream.write(com.android.server.wm.shell.Transition.CREATE_TIME_NS, createTimeNs);
            outputStream.write(com.android.server.wm.shell.Transition.SEND_TIME_NS, sendTimeNs);

            for (int i = 0; i < targets.size(); ++i) {
                final long changeToken = outputStream
                        .start(com.android.server.wm.shell.Transition.TARGETS);

                final Transition.ChangeInfo target = targets.get(i);

                final int mode = target.getTransitMode(target.mContainer);
                final int layerId;
                if (target.mContainer.mSurfaceControl.isValid()) {
                    layerId = target.mContainer.mSurfaceControl.getLayerId();
                } else {
                    layerId = -1;
                }

                outputStream.write(com.android.server.wm.shell.Target.MODE, mode);
                outputStream.write(com.android.server.wm.shell.Target.LAYER_ID, layerId);

                if (mActiveTracingEnabled) {
                    // What we use in the WM trace
                    final int windowId = System.identityHashCode(target.mContainer);
                    outputStream.write(com.android.server.wm.shell.Target.WINDOW_ID, windowId);
                }

                outputStream.end(changeToken);
            }

            outputStream.end(transitionToken);
            mBuffer.add(outputStream);

            Trace.endSection();
        }

        private void pushTransitionState(Transition transition) {
            Trace.beginSection("TransitionTraceBuffer#pushTransitionState");
            final ProtoOutputStream outputStream = new ProtoOutputStream();
            final long stateToken = outputStream
                    .start(com.android.server.wm.shell.TransitionTraceProto.TRANSITION_STATES);

            outputStream.write(com.android.server.wm.shell.TransitionState.TIME_NS,
                    SystemClock.elapsedRealtimeNanos());
            outputStream.write(com.android.server.wm.shell.TransitionState.TRANSITION_ID,
                    transition.getSyncId());
            outputStream.write(com.android.server.wm.shell.TransitionState.TRANSITION_TYPE,
                    transition.mType);
            outputStream.write(com.android.server.wm.shell.TransitionState.STATE,
                    transition.getState());
            outputStream.write(com.android.server.wm.shell.TransitionState.FLAGS,
                    transition.getFlags());

            for (int i = 0; i < transition.mChanges.size(); ++i) {
                final WindowContainer window = transition.mChanges.keyAt(i);
                final ChangeInfo changeInfo = transition.mChanges.valueAt(i);
                writeChange(outputStream, window, changeInfo);
            }

            for (int i = 0; i < transition.mChanges.size(); ++i) {
                final WindowContainer window = transition.mChanges.keyAt(i);
                final ChangeInfo changeInfo = transition.mChanges.valueAt(i);
                writeChange(outputStream, window, changeInfo);
            }

            for (int i = 0; i < transition.mParticipants.size(); ++i) {
                final WindowContainer window = transition.mParticipants.valueAt(i);
                window.writeIdentifierToProto(outputStream,
                        com.android.server.wm.shell.TransitionState.PARTICIPANTS);
            }

            outputStream.end(stateToken);

            mStateBuffer.add(outputStream);
            Trace.endSection();
        }

        private void pushTransitionInfo(Transition transition, TransitionInfo info) {
            Trace.beginSection("TransitionTraceBuffer#pushTransitionInfo");
            final ProtoOutputStream outputStream = new ProtoOutputStream();
            final long transitionInfoToken = outputStream
                    .start(com.android.server.wm.shell.TransitionTraceProto.TRANSITION_INFO);

            outputStream.write(com.android.server.wm.shell.TransitionInfo.TRANSITION_ID,
                    transition.getSyncId());
            for (int i = 0; i < info.getChanges().size(); ++i) {
                TransitionInfo.Change change = info.getChanges().get(i);
                writeTransitionInfoChange(outputStream, change);
            }

            outputStream.end(transitionInfoToken);
            mTransitionInfoBuffer.add(outputStream);
            Trace.endSection();
        }

        private void writeChange(ProtoOutputStream outputStream, WindowContainer window,
                ChangeInfo changeInfo) {
            Trace.beginSection("TransitionTraceBuffer#writeChange");
            final long changeEntryToken = outputStream.start(CHANGE);

            final int transitMode = changeInfo.getTransitMode(window);
            final boolean hasChanged = changeInfo.hasChanged();
            final int changeFlags = changeInfo.getChangeFlags(window);
            final int windowingMode = changeInfo.mWindowingMode;

            outputStream.write(TRANSIT_MODE, transitMode);
            outputStream.write(HAS_CHANGED, hasChanged);
            outputStream.write(CHANGE_FLAGS, changeFlags);
            outputStream.write(WINDOWING_MODE, windowingMode);
            window.writeIdentifierToProto(outputStream, WINDOW_IDENTIFIER);

            outputStream.end(changeEntryToken);
            Trace.endSection();
        }

        private void writeTransitionInfoChange(
                ProtoOutputStream outputStream,
                TransitionInfo.Change change
        ) {
            Trace.beginSection("TransitionTraceBuffer#writeTransitionInfoChange");
            final long changeEntryToken = outputStream
                    .start(com.android.server.wm.shell.TransitionInfo.CHANGE);

            outputStream.write(LAYER_ID, change.getLeash().getLayerId());
            outputStream.write(MODE, change.getMode());

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
            mActiveTracingEnabled = true;
            mTraceBuffer.mBuffer.setCapacity(ACTIVE_TRACING_BUFFER_CAPACITY);
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
            mActiveTracingEnabled = false;
            writeTraceToFileLocked(pw, outputFile);
            mTraceBuffer.mBuffer.setCapacity(ALWAYS_ON_TRACING_CAPACITY);
        }
        Trace.endSection();
    }

    boolean isActiveTracingEnabled() {
        return mActiveTracingEnabled;
    }

    private void writeTraceToFileLocked(@Nullable PrintWriter pw, File file) {
        Trace.beginSection("TransitionTracer#writeTraceToFileLocked");
        try {
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
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
