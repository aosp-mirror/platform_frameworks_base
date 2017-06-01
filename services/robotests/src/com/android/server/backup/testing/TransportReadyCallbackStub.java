/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.testing;

import com.android.server.backup.TransportManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Stub implementation of TransportReadyCallback, which can tell which calls were made.
 */
public class TransportReadyCallbackStub implements
        TransportManager.TransportReadyCallback {
    private final Set<String> mSuccessCalls = new HashSet<>();
    private final Set<Integer> mFailureCalls = new HashSet<>();

    @Override
    public void onSuccess(String transportName) {
        mSuccessCalls.add(transportName);
    }

    @Override
    public void onFailure(int reason) {
        mFailureCalls.add(reason);
    }

    /**
     * Returns set of transport names for which {@link #onSuccess(String)} was called.
     */
    public Set<String> getSuccessCalls() {
        return mSuccessCalls;
    }

    /**
     * Returns set of reasons for which {@link #onFailure(int)} } was called.
     */
    public Set<Integer> getFailureCalls() {
        return mFailureCalls;
    }
}
