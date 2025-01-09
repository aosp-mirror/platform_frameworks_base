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

package android.service.ondeviceintelligence;

import android.app.ondeviceintelligence.Feature;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;

import com.android.internal.infra.AndroidFuture;

/**
 * Interface for a concrete implementation to provide access to storage read access
 * for the isolated process.
 *
 * @hide
 */
oneway interface IRemoteStorageService {
    void getReadOnlyFileDescriptor(in String filePath, in AndroidFuture<ParcelFileDescriptor> future);
    void getReadOnlyFeatureFileDescriptorMap(in Feature feature, in RemoteCallback remoteCallback);
}