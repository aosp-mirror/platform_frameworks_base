/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.blob;

import android.annotation.BytesLong;
import android.annotation.NonNull;
import android.app.blob.IBlobCommitCallback;
import android.app.blob.IBlobStoreSession;
import android.os.ParcelFileDescriptor;

/** TODO: add doc */
public class BlobStoreSession extends IBlobStoreSession.Stub {

    @Override
    @NonNull
    public ParcelFileDescriptor openWrite(@BytesLong long offsetBytes,
            @BytesLong long lengthBytes) {
        return null;
    }

    @Override
    @BytesLong
    public long getSize() {
        return 0;
    }

    @Override
    public void allowPackageAccess(@NonNull String packageName,
            @NonNull byte[] certificate) {
    }

    @Override
    public void allowSameSignatureAccess() {
    }

    @Override
    public void allowPublicAccess() {
    }

    @Override
    public void close() {
    }

    @Override
    public void abandon() {
    }

    @Override
    public void commit(IBlobCommitCallback callback) {
    }
}
