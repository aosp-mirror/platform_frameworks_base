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
public class TracingContext<DataSourceInstanceType extends DataSourceInstance, TlsStateType,
        IncrementalStateType> {

    private final DataSource<DataSourceInstanceType, TlsStateType, IncrementalStateType>
            mDataSource;
    private final int mInstanceIndex;
    private final List<ProtoOutputStream> mTracePackets = new ArrayList<>();

    TracingContext(DataSource<DataSourceInstanceType, TlsStateType, IncrementalStateType>
            dataSource,
            int instanceIndex) {
        this.mDataSource = dataSource;
        this.mInstanceIndex = instanceIndex;
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
     * Can optionally be used to store custom per-sequence
     * session data, which is not reset when incremental state is cleared
     * (e.g. configuration options).
     *h
     * @return The TlsState instance for the tracing thread and instance.
     */
    public TlsStateType getCustomTlsState() {
        TlsStateType tlsState = (TlsStateType) nativeGetCustomTls(mDataSource.mNativeObj);
        if (tlsState == null) {
            final CreateTlsStateArgs<DataSourceInstanceType> args =
                    new CreateTlsStateArgs<>(mDataSource, mInstanceIndex);
            tlsState = mDataSource.createTlsState(args);
            nativeSetCustomTls(mDataSource.mNativeObj, tlsState);
        }

        return tlsState;
    }

    /**
     * Can optionally be used store custom per-sequence
     * incremental data (e.g., interning tables).
     *
     * @return The current IncrementalState object instance.
     */
    public IncrementalStateType getIncrementalState() {
        IncrementalStateType incrementalState =
                (IncrementalStateType) nativeGetIncrementalState(mDataSource.mNativeObj);
        if (incrementalState == null) {
            final CreateIncrementalStateArgs<DataSourceInstanceType> args =
                    new CreateIncrementalStateArgs<>(mDataSource, mInstanceIndex);
            incrementalState = mDataSource.createIncrementalState(args);
            nativeSetIncrementalState(mDataSource.mNativeObj, incrementalState);
        }

        return incrementalState;
    }

    protected byte[][] getAndClearAllPendingTracePackets() {
        byte[][] res = new byte[mTracePackets.size()][];
        for (int i = 0; i < mTracePackets.size(); i++) {
            ProtoOutputStream tracePacket = mTracePackets.get(i);
            res[i] = tracePacket.getBytes();
        }

        mTracePackets.clear();
        return res;
    }

    private static native Object nativeGetCustomTls(long nativeDsPtr);
    private static native void nativeSetCustomTls(long nativeDsPtr, Object tlsState);

    private static native Object nativeGetIncrementalState(long nativeDsPtr);
    private static native void nativeSetIncrementalState(long nativeDsPtr, Object incrementalState);
}
