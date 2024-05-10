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
package com.android.internal.app;

import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Callback wrapper that works around potential memory leaks in app predictor.
 *
 * Nulls the callback itself when destroyed, so at worst you'll leak just this object.
 */
public class ResolverAppPredictorCallback {
    private volatile Consumer<List<AppTarget>> mCallback;

    public ResolverAppPredictorCallback(Consumer<List<AppTarget>> callback) {
        mCallback = callback;
    }

    private void notifyCallback(List<AppTarget> list) {
        Consumer<List<AppTarget>> callback = mCallback;
        if (callback != null) {
            callback.accept(Objects.requireNonNullElseGet(list, List::of));
        }
    }

    public Consumer<List<AppTarget>> asConsumer() {
        return this::notifyCallback;
    }

    public AppPredictor.Callback asCallback() {
        return this::notifyCallback;
    }

    public void destroy() {
        mCallback = null;
    }
}
