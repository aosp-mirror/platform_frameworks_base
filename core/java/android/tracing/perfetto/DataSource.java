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

import android.util.proto.ProtoInputStream;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Templated base class meant to be derived by embedders to create a custom data
 * source.
 *
 * @param <DataSourceInstanceType> The type for the DataSource instances that will be created from
 *                                 this DataSource type.
 * @param <TlsStateType> The type of the custom TLS state, if any is used.
 * @param <IncrementalStateType> The type of the custom incremental state, if any is used.
 *
 * @hide
 */
public abstract class DataSource<DataSourceInstanceType extends DataSourceInstance,
        TlsStateType, IncrementalStateType> {
    protected final long mNativeObj;

    public final String name;

    /**
     * A function implemented by each datasource to create a new data source instance.
     *
     * @param configStream A ProtoInputStream to read the tracing instance's config.
     * @return A new data source instance setup with the provided config.
     */
    public abstract DataSourceInstanceType createInstance(
            ProtoInputStream configStream, int instanceIndex);

    /**
     * Constructor for datasource base class.
     *
     * @param name The fully qualified name of the datasource.
     */
    public DataSource(String name) {
        this.name = name;
        this.mNativeObj = nativeCreate(this, name);
    }

    /**
     * The main tracing method. Tracing code should call this passing a lambda as
     * argument, with the following signature: void(TraceContext).
     * <p>
     * The lambda will be called synchronously (i.e., always before trace()
     * returns) only if tracing is enabled and the data source has been enabled in
     * the tracing config.
     * <p>
     * The lambda can be called more than once per trace() call, in the case of
     * concurrent tracing sessions (or even if the data source is instantiated
     * twice within the same trace config).
     *
     * @param fun The tracing lambda that will be called with the tracing contexts of each active
     *            tracing instance.
     */
    public final void trace(
            TraceFunction<DataSourceInstanceType, TlsStateType, IncrementalStateType> fun) {
        nativeTrace(mNativeObj, fun);
    }

    /**
     * Flush any trace data from this datasource that has not yet been flushed.
     */
    public final void flush() {
        nativeFlushAll(mNativeObj);
    }

    /**
     * Override this method to create a custom TlsState object for your DataSource. A new instance
     * will be created per trace instance per thread.
     *
     * NOTE: Should only be called from native side.
     */
    @VisibleForTesting
    public TlsStateType createTlsState(CreateTlsStateArgs<DataSourceInstanceType> args) {
        return null;
    }

    /**
     * Override this method to create and use a custom IncrementalState object for your DataSource.
     *
     * NOTE: Should only be called from native side.
     */
    protected IncrementalStateType createIncrementalState(
            CreateIncrementalStateArgs<DataSourceInstanceType> args) {
        return null;
    }

    /**
     * Registers the data source on all tracing backends, including ones that
     * connect after the registration. Doing so enables the data source to receive
     * Setup/Start/Stop notifications and makes the trace() method work when
     * tracing is enabled and the data source is selected.
     * <p>
     * NOTE: Once registered, we cannot unregister the data source. Therefore, we should avoid
     * creating and registering data source where not strictly required. This is a fundamental
     * limitation of Perfetto itself.
     *
     * @param params Params to initialize the datasource with.
     */
    public void register(DataSourceParams params) {
        nativeRegisterDataSource(this.mNativeObj, params.bufferExhaustedPolicy);
    }

    /**
     * Gets the datasource instance with a specified index.
     * IMPORTANT: releaseDataSourceInstance must be called after using the datasource instance.
     * @param instanceIndex The index of the datasource to lock and get.
     * @return The DataSourceInstance at index instanceIndex.
     *         Null if the datasource instance at the requested index doesn't exist.
     */
    public DataSourceInstanceType getDataSourceInstanceLocked(int instanceIndex) {
        return (DataSourceInstanceType) nativeGetPerfettoInstanceLocked(mNativeObj, instanceIndex);
    }

    /**
     * Unlock the datasource at the specified index.
     * @param instanceIndex The index of the datasource to unlock.
     */
    protected void releaseDataSourceInstance(int instanceIndex) {
        nativeReleasePerfettoInstanceLocked(mNativeObj, instanceIndex);
    }

    /**
     * Called from native side when a new tracing instance starts.
     *
     * @param rawConfig byte array of the PerfettoConfig encoded proto.
     * @return A new Java DataSourceInstance object.
     */
    private DataSourceInstanceType createInstance(byte[] rawConfig, int instanceIndex) {
        final ProtoInputStream inputStream = new ProtoInputStream(rawConfig);
        return this.createInstance(inputStream, instanceIndex);
    }

    private static native void nativeRegisterDataSource(
            long dataSourcePtr, int bufferExhaustedPolicy);

    private static native long nativeCreate(DataSource thiz, String name);
    private static native void nativeTrace(
            long nativeDataSourcePointer, TraceFunction traceFunction);
    private static native void nativeFlushAll(long nativeDataSourcePointer);
    private static native long nativeGetFinalizer();

    private static native DataSourceInstance nativeGetPerfettoInstanceLocked(
            long dataSourcePtr, int dsInstanceIdx);
    private static native void nativeReleasePerfettoInstanceLocked(
            long dataSourcePtr, int dsInstanceIdx);
}
