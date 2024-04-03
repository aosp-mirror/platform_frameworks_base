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

package android.app.wearable;

import android.app.PendingIntent;
import android.app.wearable.IWearableSensingCallback;
import android.content.ComponentName;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.SharedMemory;

/**
 * Interface for a WearableSensingManager for managing wearable sensing service.
 *
 * @hide
 */
interface IWearableSensingManager {
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)")
     void provideConnection(in ParcelFileDescriptor parcelFileDescriptor, in IWearableSensingCallback wearableSensingCallback, in RemoteCallback statusCallback);
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)")
     void provideDataStream(in ParcelFileDescriptor parcelFileDescriptor, in @nullable IWearableSensingCallback wearableSensingCallback, in RemoteCallback statusCallback);
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)")
     void provideData(in PersistableBundle data, in SharedMemory sharedMemory, in RemoteCallback callback);
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)")
     void registerDataRequestObserver(int dataType, in PendingIntent dataRequestPendingIntent, in RemoteCallback statusCallback);
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)")
     void unregisterDataRequestObserver(int dataType, in PendingIntent dataRequestPendingIntent, in RemoteCallback statusCallback);
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)")
     void startHotwordRecognition(in ComponentName targetVisComponentName, in RemoteCallback statusCallback);
     @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE)")
     void stopHotwordRecognition(in RemoteCallback statusCallback);
}