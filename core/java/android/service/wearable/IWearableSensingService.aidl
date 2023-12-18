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

package android.service.wearable;

import android.app.ambientcontext.AmbientContextEventRequest;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.SharedMemory;

/**
 * Interface for a concrete implementation to provide AmbientContextEvents to the framework for
 * wearables.
 *
 * @hide
 */
oneway interface IWearableSensingService {
    void provideSecureWearableConnection(in ParcelFileDescriptor parcelFileDescriptor, in RemoteCallback callback);
    void provideDataStream(in ParcelFileDescriptor parcelFileDescriptor, in RemoteCallback callback);
    void provideData(in PersistableBundle data, in SharedMemory sharedMemory, in RemoteCallback callback);
    void registerDataRequestObserver(int dataType, in RemoteCallback dataRequestCallback, int dataRequestObserverId, in String packageName, in RemoteCallback statusCallback);
    void unregisterDataRequestObserver(int dataType, int dataRequestObserverId, in String packageName, in RemoteCallback statusCallback);
    void startDetection(in AmbientContextEventRequest request, in String packageName,
            in RemoteCallback detectionResultCallback, in RemoteCallback statusCallback);
    void stopDetection(in String packageName);
    void queryServiceStatus(in int[] eventTypes, in String packageName, in RemoteCallback callback);
}