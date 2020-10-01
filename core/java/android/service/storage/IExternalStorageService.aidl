/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.storage;

import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.storage.StorageVolume;

/**
 * @hide
 */
oneway interface IExternalStorageService
{
    void startSession(@utf8InCpp String sessionId, int type, in ParcelFileDescriptor deviceFd,
         @utf8InCpp String upperPath, @utf8InCpp String lowerPath, in RemoteCallback callback);
    void endSession(@utf8InCpp String sessionId, in RemoteCallback callback);
    void notifyVolumeStateChanged(@utf8InCpp String sessionId, in StorageVolume vol,
        in RemoteCallback callback);
}