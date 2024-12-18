/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.tracing.inputmethod;

import android.annotation.NonNull;
import android.tracing.perfetto.DataSource;
import android.tracing.perfetto.DataSourceInstance;
import android.tracing.perfetto.StartCallbackArguments;
import android.tracing.perfetto.StopCallbackArguments;
import android.util.proto.ProtoInputStream;

/**
 * @hide
 */
public final class InputMethodDataSource
        extends DataSource<DataSourceInstance, Void, Void> {
    public static final String DATA_SOURCE_NAME = "android.inputmethod";

    @NonNull
    private final Runnable mOnStartCallback;
    @NonNull
    private final Runnable mOnStopCallback;

    public InputMethodDataSource(@NonNull Runnable onStart, @NonNull Runnable onStop) {
        super(DATA_SOURCE_NAME);
        mOnStartCallback = onStart;
        mOnStopCallback = onStop;
    }

    @Override
    public DataSourceInstance createInstance(ProtoInputStream configStream, int instanceIndex) {
        return new DataSourceInstance(this, instanceIndex) {
            @Override
            protected void onStart(StartCallbackArguments args) {
                mOnStartCallback.run();
            }

            @Override
            protected void onStop(StopCallbackArguments args) {
                mOnStopCallback.run();
            }
        };
    }
}
