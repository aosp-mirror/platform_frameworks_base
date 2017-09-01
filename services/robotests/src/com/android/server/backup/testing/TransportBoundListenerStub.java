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

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.TransportManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Stub implementation of TransportBoundListener, which returns given result and can tell whether
 * it was called for given transport.
 */
public class TransportBoundListenerStub implements
        TransportManager.TransportBoundListener {
    private boolean mAlwaysReturnSuccess;
    private Set<IBackupTransport> mTransportsCalledFor = new HashSet<>();

    public TransportBoundListenerStub(boolean alwaysReturnSuccess) {
        this.mAlwaysReturnSuccess = alwaysReturnSuccess;
    }

    @Override
    public boolean onTransportBound(IBackupTransport binder) {
        mTransportsCalledFor.add(binder);
        return mAlwaysReturnSuccess;
    }

    public boolean isCalledForTransport(IBackupTransport binder) {
        return mTransportsCalledFor.contains(binder);
    }

    public boolean isCalled() {
        return !mTransportsCalledFor.isEmpty();
    }
}
