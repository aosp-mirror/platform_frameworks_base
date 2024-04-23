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

/**
 * The interface for the trace function called from native on a trace call with a context.
 *
 * @param <DataSourceInstanceType> The type of DataSource this tracing context is for.
 * @param <TlsStateType> The type of the custom TLS state, if any is used.
 * @param <IncrementalStateType> The type of the custom incremental state, if any is used.
 *
 * @hide
 */
public interface TraceFunction<DataSourceInstanceType extends DataSourceInstance,
        TlsStateType, IncrementalStateType> {

    /**
     * This function will be called synchronously (i.e., always before trace() returns) only if
     * tracing is enabled and the data source has been enabled in the tracing config.
     * It can be called more than once per trace() call, in the case of concurrent tracing sessions
     * (or even if the data source is instantiated twice within the same trace config).
     *
     * @param ctx the tracing context to trace for in the trace function.
     */
    void trace(TracingContext<DataSourceInstanceType, TlsStateType, IncrementalStateType> ctx);
}
