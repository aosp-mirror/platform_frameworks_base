/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import android.annotation.NonNull;

import com.android.internal.logging.InstanceId;

import java.util.concurrent.atomic.AtomicInteger;

/** State for an authentication session {@see com.android.internal.statusbar.ISessionListener}. */
class BiometricContextSessionInfo {
    private final InstanceId mId;
    private final AtomicInteger mOrder = new AtomicInteger(0);

    /** Wrap a session id with the initial state. */
    BiometricContextSessionInfo(@NonNull InstanceId id) {
        mId = id;
    }

    /** Get the session id. */
    public int getId() {
        return mId.getId();
    }

    /** Gets the current order counter for the session. */
    public int getOrder() {
        return mOrder.get();
    }

    /**
     * Gets the current order counter for the session and increment the counter.
     *
     * This should be called by the framework after processing any logged events,
     * such as success / failure, to preserve the order each event was processed in.
     */
    public int getOrderAndIncrement() {
        return mOrder.getAndIncrement();
    }

    @Override
    public String toString() {
        return "[sid: " +  mId.getId() + "]";
    }
}
