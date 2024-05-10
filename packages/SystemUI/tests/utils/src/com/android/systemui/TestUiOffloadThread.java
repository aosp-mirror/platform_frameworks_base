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

package com.android.systemui;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * UiOffloadThread that can be used for testing as part of {@link TestableDependency}.
 */
public class TestUiOffloadThread extends UiOffloadThread {
    private final Handler mTestHandler;

    public TestUiOffloadThread(Looper looper) {
        mTestHandler = new Handler(looper);
    }

    @Override
    public Future<?> execute(Runnable runnable) {
        Looper myLooper = Looper.myLooper();
        if (myLooper != null && myLooper.isCurrentThread()) {
            try {
                runnable.run();
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        final CompletableFuture<?> future = new CompletableFuture<>();
        mTestHandler.post(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}
