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

package android.tracing.transition;

import android.tracing.perfetto.DataSource;
import android.tracing.perfetto.DataSourceInstance;
import android.tracing.perfetto.FlushCallbackArguments;
import android.tracing.perfetto.StartCallbackArguments;
import android.tracing.perfetto.StopCallbackArguments;
import android.util.proto.ProtoInputStream;

/**
 * @hide
 */
public class TransitionDataSource extends DataSource {
    public static String DATA_SOURCE_NAME = "com.android.wm.shell.transition";

    private final Runnable mOnStartStaticCallback;
    private final Runnable mOnFlushStaticCallback;
    private final Runnable mOnStopStaticCallback;

    public TransitionDataSource(Runnable onStart, Runnable onFlush, Runnable onStop) {
        super(DATA_SOURCE_NAME);
        this.mOnStartStaticCallback = onStart;
        this.mOnFlushStaticCallback = onFlush;
        this.mOnStopStaticCallback = onStop;
    }

    @Override
    public DataSourceInstance createInstance(ProtoInputStream configStream, int instanceIndex) {
        return new DataSourceInstance(this, instanceIndex) {
            @Override
            protected void onStart(StartCallbackArguments args) {
                mOnStartStaticCallback.run();
            }

            @Override
            protected void onFlush(FlushCallbackArguments args) {
                mOnFlushStaticCallback.run();
            }

            @Override
            protected void onStop(StopCallbackArguments args) {
                mOnStopStaticCallback.run();
            }
        };
    }
}
