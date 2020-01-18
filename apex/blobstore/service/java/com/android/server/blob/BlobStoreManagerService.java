/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.CurrentTimeSecondsLong;
import android.annotation.IdRes;
import android.annotation.NonNull;
import android.app.blob.BlobHandle;
import android.app.blob.IBlobStoreManager;
import android.app.blob.IBlobStoreSession;
import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.android.server.SystemService;

/**
 * Service responsible for maintaining and facilitating access to data blobs published by apps.
 */
public class BlobStoreManagerService extends SystemService {

    private final Context mContext;

    public BlobStoreManagerService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.BLOB_STORE_SERVICE, new Stub());
    }

    private class Stub extends IBlobStoreManager.Stub {
        @Override
        public long createSession(@NonNull BlobHandle blobHandle, @NonNull String packageName) {
            return 0;
        }

        @Override
        public IBlobStoreSession openSession(long sessionId) {
            return null;
        }

        @Override
        public ParcelFileDescriptor openBlob(@NonNull BlobHandle blobHandle,
                @NonNull String packageName) {
            return null;
        }

        @Override
        public void acquireLease(@NonNull BlobHandle blobHandle, @IdRes int descriptionResId,
                @CurrentTimeSecondsLong long leaseTimeout, @NonNull String packageName) {
        }

        @Override
        public void releaseLease(@NonNull BlobHandle blobHandle, @NonNull String packageName) {
        }
    }
}
