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

package com.android.wm.shell.transition.tracing;

import android.internal.perfetto.protos.PerfettoTrace;
import android.os.SystemClock;
import android.tracing.perfetto.DataSourceInstance;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;
import android.tracing.perfetto.TracingContext;
import android.tracing.transition.TransitionDataSource;
import android.util.proto.ProtoOutputStream;

import com.android.wm.shell.transition.Transitions;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class to collect and dump transition traces.
 */
public class PerfettoTransitionTracer implements TransitionTracer {
    private final AtomicInteger mActiveTraces = new AtomicInteger(0);
    private final TransitionDataSource mDataSource = new TransitionDataSource(
            mActiveTraces::incrementAndGet,
            this::onFlush,
            mActiveTraces::decrementAndGet);

    public PerfettoTransitionTracer() {
        Producer.init(InitArguments.DEFAULTS);
        mDataSource.register(DataSourceParams.DEFAULTS);
    }

    /**
     * Adds an entry in the trace to log that a transition has been dispatched to a handler.
     *
     * @param transitionId The id of the transition being dispatched.
     * @param handler The handler the transition is being dispatched to.
     */
    @Override
    public void logDispatched(int transitionId, Transitions.TransitionHandler handler) {
        if (!isTracing()) {
            return;
        }

        mDataSource.trace(ctx -> {
            final int handlerId = getHandlerId(handler, ctx);

            final ProtoOutputStream os = ctx.newTracePacket();
            final long token = os.start(PerfettoTrace.TracePacket.SHELL_TRANSITION);
            os.write(PerfettoTrace.ShellTransition.ID, transitionId);
            os.write(PerfettoTrace.ShellTransition.DISPATCH_TIME_NS,
                    SystemClock.elapsedRealtimeNanos());
            os.write(PerfettoTrace.ShellTransition.HANDLER, handlerId);
            os.end(token);
        });
    }

    private static int getHandlerId(Transitions.TransitionHandler handler,
            TracingContext<DataSourceInstance, TransitionDataSource.TlsState, Void> ctx) {
        final Map<String, Integer> handlerMapping =
                ctx.getCustomTlsState().handlerMapping;
        final int handlerId;
        if (handlerMapping.containsKey(handler.getClass().getName())) {
            handlerId = handlerMapping.get(handler.getClass().getName());
        } else {
            // + 1 to avoid 0 ids which can be confused with missing value when dumped to proto
            handlerId = handlerMapping.size() + 1;
            handlerMapping.put(handler.getClass().getName(), handlerId);
        }
        return handlerId;
    }

    /**
     * Adds an entry in the trace to log that a request to merge a transition was made.
     *
     * @param mergeRequestedTransitionId The id of the transition we are requesting to be merged.
     */
    @Override
    public void logMergeRequested(int mergeRequestedTransitionId, int playingTransitionId) {
        if (!isTracing()) {
            return;
        }

        mDataSource.trace(ctx -> {
            final ProtoOutputStream os = ctx.newTracePacket();
            final long token = os.start(PerfettoTrace.TracePacket.SHELL_TRANSITION);
            os.write(PerfettoTrace.ShellTransition.ID, mergeRequestedTransitionId);
            os.write(PerfettoTrace.ShellTransition.MERGE_REQUEST_TIME_NS,
                    SystemClock.elapsedRealtimeNanos());
            os.write(PerfettoTrace.ShellTransition.MERGE_TARGET, playingTransitionId);
            os.end(token);
        });
    }

    /**
     * Adds an entry in the trace to log that a transition was merged by the handler.
     *
     * @param mergedTransitionId The id of the transition that was merged.
     * @param playingTransitionId The id of the transition the transition was merged into.
     */
    @Override
    public void logMerged(int mergedTransitionId, int playingTransitionId) {
        if (!isTracing()) {
            return;
        }

        mDataSource.trace(ctx -> {
            final ProtoOutputStream os = ctx.newTracePacket();
            final long token = os.start(PerfettoTrace.TracePacket.SHELL_TRANSITION);
            os.write(PerfettoTrace.ShellTransition.ID, mergedTransitionId);
            os.write(PerfettoTrace.ShellTransition.MERGE_TIME_NS,
                    SystemClock.elapsedRealtimeNanos());
            os.write(PerfettoTrace.ShellTransition.MERGE_TARGET, playingTransitionId);
            os.end(token);
        });
    }

    /**
     * Adds an entry in the trace to log that a transition was aborted.
     *
     * @param transitionId The id of the transition that was aborted.
     */
    @Override
    public void logAborted(int transitionId) {
        if (!isTracing()) {
            return;
        }

        mDataSource.trace(ctx -> {
            final ProtoOutputStream os = ctx.newTracePacket();
            final long token = os.start(PerfettoTrace.TracePacket.SHELL_TRANSITION);
            os.write(PerfettoTrace.ShellTransition.ID, transitionId);
            os.write(PerfettoTrace.ShellTransition.SHELL_ABORT_TIME_NS,
                    SystemClock.elapsedRealtimeNanos());
            os.end(token);
        });
    }

    private boolean isTracing() {
        return mActiveTraces.get() > 0;
    }

    private void onFlush() {
        mDataSource.trace(ctx -> {
            final ProtoOutputStream os = ctx.newTracePacket();

            final Map<String, Integer> handlerMapping = ctx.getCustomTlsState().handlerMapping;
            for (String handler : handlerMapping.keySet()) {
                final long token = os.start(PerfettoTrace.TracePacket.SHELL_HANDLER_MAPPINGS);
                os.write(PerfettoTrace.ShellHandlerMapping.ID, handlerMapping.get(handler));
                os.write(PerfettoTrace.ShellHandlerMapping.NAME, handler);
                os.end(token);
            }

            ctx.flush();
        });
    }
}
