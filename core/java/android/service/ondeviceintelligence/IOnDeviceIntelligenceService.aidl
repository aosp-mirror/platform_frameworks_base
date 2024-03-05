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

import android.os.PersistableBundle;
import android.os.ParcelFileDescriptor;
import android.os.ICancellationSignal;
import android.os.RemoteCallback;
import android.app.ondeviceintelligence.IDownloadCallback;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.IFeatureCallback;
import android.app.ondeviceintelligence.IListFeaturesCallback;
import android.app.ondeviceintelligence.IFeatureDetailsCallback;
import com.android.internal.infra.AndroidFuture;
import android.service.ondeviceintelligence.IRemoteProcessingService;


/**
 * Interface for a concrete implementation to provide on device intelligence services.
 *
 * @hide
 */
oneway interface IOnDeviceIntelligenceService {
    void getVersion(in RemoteCallback remoteCallback);
    void getFeature(in int featureId, in IFeatureCallback featureCallback);
    void listFeatures(in IListFeaturesCallback listFeaturesCallback);
    void getFeatureDetails(in Feature feature, in IFeatureDetailsCallback featureDetailsCallback);
    void getReadOnlyFileDescriptor(in String fileName, in AndroidFuture<ParcelFileDescriptor> future);
    void getReadOnlyFeatureFileDescriptorMap(in Feature feature, in RemoteCallback remoteCallback);
    void requestFeatureDownload(in Feature feature, in ICancellationSignal cancellationSignal, in IDownloadCallback downloadCallback);
    void registerRemoteServices(in IRemoteProcessingService remoteProcessingService);
}