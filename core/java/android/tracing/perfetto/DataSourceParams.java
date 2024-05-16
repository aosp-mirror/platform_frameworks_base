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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * DataSource Parameters
 *
 * @hide
 */
public class DataSourceParams {
    /**
     * @hide
     */
    @IntDef(value = {
        PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_DROP,
        PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_ABORT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PerfettoDsBufferExhausted {}

    // If the data source runs out of space when trying to acquire a new chunk,
    // it will drop data.
    public static final int PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_DROP = 0;

    // If the data source runs out of space when trying to acquire a new chunk,
    // it will stall, retry and eventually abort if a free chunk is not acquired
    // after a while.
    public static final int PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_ABORT = 1;

    public static DataSourceParams DEFAULTS = new DataSourceParams.Builder().build();

    private DataSourceParams(@PerfettoDsBufferExhausted int bufferExhaustedPolicy,
            boolean willNotifyOnStop, boolean noFlush) {
        this.bufferExhaustedPolicy = bufferExhaustedPolicy;
        this.willNotifyOnStop = willNotifyOnStop;
        this.noFlush = noFlush;
    }

    public final @PerfettoDsBufferExhausted int bufferExhaustedPolicy;
    public final boolean willNotifyOnStop;
    public final boolean noFlush;

    /**
     * DataSource Parameters builder
     *
     * @hide
     */
    public static final class Builder {
        /**
         * Specify behavior when running out of shared memory buffer space.
         */
        public Builder setBufferExhaustedPolicy(@PerfettoDsBufferExhausted int value) {
            this.mBufferExhaustedPolicy = value;
            return this;
        }

        /**
         * If true, the data source is expected to ack the stop request through the
         * NotifyDataSourceStopped() IPC. If false, the service won't wait for an ack.
         * Set this parameter to false when dealing with potentially frozen producers
         * that wouldn't be able to quickly ack the stop request.
         *
         * Default value: true
         */
        public Builder setWillNotifyOnStop(boolean value) {
            this.mWillNotifyOnStop = value;
            return this;
        }

        /**
         * If true, the service won't emit flush requests for this data source. This
         * allows the service to reduce the flush-related IPC traffic and better deal
         * with frozen producers (see go/perfetto-frozen).
         */
        public Builder setNoFlush(boolean value) {
            this.mNoFlush = value;
            return this;
        }

        /**
         * Build the DataSource parameters.
         */
        public DataSourceParams build() {
            return new DataSourceParams(
                    this.mBufferExhaustedPolicy, this.mWillNotifyOnStop, this.mNoFlush);
        }

        private @PerfettoDsBufferExhausted int mBufferExhaustedPolicy =
                PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_DROP;
        private boolean mWillNotifyOnStop = true;
        private boolean mNoFlush = false;
    }
}
