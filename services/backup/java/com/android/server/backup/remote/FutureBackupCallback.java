/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.remote;

import android.app.backup.IBackupCallback;
import android.os.RemoteException;

import java.util.concurrent.CompletableFuture;

/**
 * An implementation of {@link IBackupCallback} that completes the {@link CompletableFuture}
 * provided in the constructor with a present {@link RemoteResult}.
 */
public class FutureBackupCallback extends IBackupCallback.Stub {
    private final CompletableFuture<RemoteResult> mFuture;

    FutureBackupCallback(CompletableFuture<RemoteResult> future) {
        mFuture = future;
    }

    @Override
    public void operationComplete(long result) throws RemoteException {
        mFuture.complete(RemoteResult.of(result));
    }
}
