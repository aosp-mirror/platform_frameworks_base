/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice;

import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * {@link FutureTask} that logs unhandled exceptions.
 */
final class ExceptionLoggingFutureTask<V> extends FutureTask<V> {

    private final String mTag;

    public ExceptionLoggingFutureTask(Callable<V> callable, String tag) {
        super(callable);
        mTag = tag;
    }

    @Override
    protected void done() {
        try {
            get();
        } catch (ExecutionException | InterruptedException e) {
            Log.e(mTag, "Uncaught exception.", e);
            throw new RuntimeException(e);
        }
    }
}
