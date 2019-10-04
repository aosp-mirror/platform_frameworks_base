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
package android.app.blob;

import android.annotation.SystemService;
import android.content.Context;

/**
 * This class provides access to the blob store maintained by the system.
 *
 * Apps can publish data blobs which might be useful for other apps on the device to be
 * maintained by the system and apps that would like to access these data blobs can do so
 * by addressing them via their cryptographically secure hashes.
 *
 * TODO: make this public once the APIs are added.
 * @hide
 */
@SystemService(Context.BLOB_STORE_SERVICE)
public class BlobStoreManager {
    private final Context mContext;
    private final IBlobStoreManager mService;

    /** @hide */
    public BlobStoreManager(Context context, IBlobStoreManager service) {
        mContext = context;
        mService = service;
    }
}
