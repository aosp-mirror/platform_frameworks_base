/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.listeners;

import android.annotation.Nullable;

import java.util.concurrent.Executor;

/**
 * A listener transport with an associated request.
 *
 * @param <TRequest>  request type
 * @param <TListener> listener type
 */
public class RequestListenerTransport<TRequest, TListener> extends ListenerTransport<TListener> {

    private final @Nullable TRequest mRequest;

    protected RequestListenerTransport(@Nullable TRequest request, Executor executor,
            TListener listener) {
        super(executor, listener);
        mRequest = request;
    }

    /**
     * Returns the request associated with this transport.
     */
    public final @Nullable TRequest getRequest() {
        return mRequest;
    }
}
