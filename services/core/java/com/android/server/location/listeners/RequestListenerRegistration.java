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

package com.android.server.location.listeners;

import java.util.concurrent.Executor;

/**
 * A listener registration object which includes an associated request.
 *
 * @param <TRequest>           request type
 * @param <TListener>          listener type
 */
public class RequestListenerRegistration<TRequest, TListener> extends
        ListenerRegistration<TListener> {

    private final TRequest mRequest;

    protected RequestListenerRegistration(Executor executor, TRequest request,
            TListener listener) {
        super(executor, listener);
        mRequest = request;
    }

    /**
     * Returns the request associated with this listener, or null if one wasn't supplied.
     */
    public TRequest getRequest() {
        return mRequest;
    }

    @Override
    public String toString() {
        if (mRequest == null) {
            return "[]";
        } else {
            return mRequest.toString();
        }
    }
}

