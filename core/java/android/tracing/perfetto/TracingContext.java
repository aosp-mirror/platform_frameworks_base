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

package android.tracing.perfetto;

import android.util.proto.ProtoOutputStream;

import java.util.ArrayList;
import java.util.List;

/**
 * Argument passed to the lambda function passed to Trace().
 *
 * @param <DataSourceInstanceType> The type of the datasource this tracing context is for.
 * @param <TlsStateType> The type of the custom TLS state, if any is used.
 * @param <IncrementalStateType> The type of the custom incremental state, if any is used.
 *
 * @hide
 */
public class TracingContext<DataSourceInstanceType extends DataSourceInstance,
        TlsStateType, IncrementalStateType> {

    private final long mContextPtr;
    private final TlsStateType mTlsState;
    private final IncrementalStateType mIncrementalState;
    private final List<ProtoOutputStream> mTracePackets = new ArrayList<>();

    // Should only be created from the native side.
    private TracingContext(long contextPtr, TlsStateType tlsState,
            IncrementalStateType incrementalState) {
        this.mContextPtr = contextPtr;
        this.mTlsState = tlsState;
        this.mIncrementalState = incrementalState;
    }

    /**
     * Creates a new output stream to be used to write a trace packet to. The output stream will be
     * encoded to the proto binary representation when the callback trace function finishes and
     * send over to the native side to be included in the proto buffer.
     *
     * @return A proto output stream to write a trace packet proto to
     */
    public ProtoOutputStream newTracePacket() {
        final ProtoOutputStream os = new ProtoOutputStream(0);
        mTracePackets.add(os);
        return os;
    }

    /**
     * Forces a commit of the thread-local tracing data written so far to the
     * service. This is almost never required (tracing data is periodically
     * committed as trace pages are filled up) and has a non-negligible
     * performance hit (requires an IPC + refresh of the current thread-local
     * chunk). The only case when this should be used is when handling OnStop()
     * asynchronously, to ensure sure that the data is committed before the
     * Stop timeout expires.
     */
    public void flush() {
        nativeFlush(this, mContextPtr);
    }

    /**
     * Can optionally be used to store custom per-sequence
     * session data, which is not reset when incremental state is cleared
     * (e.g. configuration options).
     *
     * @return The TlsState instance for the tracing thread and instance.
     */
    public TlsStateType getCustomTlsState() {
        return this.mTlsState;
    }

    /**
     * Can optionally be used store custom per-sequence
     * incremental data (e.g., interning tables).
     *
     * @return The current IncrementalState object instance.
     */
    public IncrementalStateType getIncrementalState() {
        return this.mIncrementalState;
    }

    // Called from native to get trace packets
    private byte[][] getAndClearAllPendingTracePackets() {
        byte[][] res = new byte[mTracePackets.size()][];
        for (int i = 0; i < mTracePackets.size(); i++) {
            ProtoOutputStream tracePacket = mTracePackets.get(i);
            res[i] = tracePacket.getBytes();
        }

        mTracePackets.clear();
        return res;
    }

    // private static native void nativeFlush(long nativeDataSourcePointer);
    private static native void nativeFlush(TracingContext thiz, long ctxPointer);
}
