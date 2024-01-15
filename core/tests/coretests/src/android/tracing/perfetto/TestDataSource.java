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

import java.util.UUID;
import java.util.function.Consumer;

public class TestDataSource extends DataSource<TestDataSource.TestDataSourceInstance,
        TestDataSource.TestTlsState, TestDataSource.TestIncrementalState> {
    private final DataSourceInstanceProvider mDataSourceInstanceProvider;
    private final TlsStateProvider mTlsStateProvider;
    private final IncrementalStateProvider mIncrementalStateProvider;

    interface DataSourceInstanceProvider {
        TestDataSourceInstance provide(
                TestDataSource dataSource, int instanceIndex, ProtoInputStream configStream);
    }

    interface TlsStateProvider {
        TestTlsState provide(CreateTlsStateArgs<TestDataSourceInstance> args);
    }

    interface IncrementalStateProvider {
        TestIncrementalState provide(CreateIncrementalStateArgs<TestDataSourceInstance> args);
    }

    public TestDataSource() {
        this((ds, idx, config) -> new TestDataSourceInstance(ds, idx),
                args -> new TestTlsState(), args -> new TestIncrementalState());
    }

    public TestDataSource(
            DataSourceInstanceProvider dataSourceInstanceProvider,
            TlsStateProvider tlsStateProvider,
            IncrementalStateProvider incrementalStateProvider
    ) {
        super("android.tracing.perfetto.TestDataSource#" + UUID.randomUUID().toString());
        this.mDataSourceInstanceProvider = dataSourceInstanceProvider;
        this.mTlsStateProvider = tlsStateProvider;
        this.mIncrementalStateProvider = incrementalStateProvider;
    }

    @Override
    public TestDataSourceInstance createInstance(ProtoInputStream configStream, int instanceIndex) {
        return mDataSourceInstanceProvider.provide(this, instanceIndex, configStream);
    }

    @Override
    public TestTlsState createTlsState(CreateTlsStateArgs args) {
        return mTlsStateProvider.provide(args);
    }

    @Override
    public TestIncrementalState createIncrementalState(CreateIncrementalStateArgs args) {
        return mIncrementalStateProvider.provide(args);
    }

    public static class TestTlsState {
        public int testStateValue;
        public int stateIndex = lastIndex++;

        public static int lastIndex = 0;
    }

    public static class TestIncrementalState {
        public int testStateValue;
    }

    public static class TestDataSourceInstance extends DataSourceInstance {
        public Object testObject;
        Consumer<StartCallbackArguments> mStartCallback;
        Consumer<FlushCallbackArguments> mFlushCallback;
        Consumer<StopCallbackArguments> mStopCallback;

        public TestDataSourceInstance(DataSource dataSource, int instanceIndex) {
            this(dataSource, instanceIndex, args -> {}, args -> {}, args -> {});
        }

        public TestDataSourceInstance(
                DataSource dataSource,
                int instanceIndex,
                Consumer<StartCallbackArguments> startCallback,
                Consumer<FlushCallbackArguments> flushCallback,
                Consumer<StopCallbackArguments> stopCallback) {
            super(dataSource, instanceIndex);
            this.mStartCallback = startCallback;
            this.mFlushCallback = flushCallback;
            this.mStopCallback = stopCallback;
        }

        @Override
        public void onStart(StartCallbackArguments args) {
            this.mStartCallback.accept(args);
        }

        @Override
        public void onFlush(FlushCallbackArguments args) {
            this.mFlushCallback.accept(args);
        }

        @Override
        public void onStop(StopCallbackArguments args) {
            this.mStopCallback.accept(args);
        }
    }
}
