/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 package android.app.ondeviceintelligence;

 import com.android.internal.infra.AndroidFuture;
 import android.os.ICancellationSignal;
 import android.os.ParcelFileDescriptor;
 import android.os.PersistableBundle;
 import android.os.RemoteCallback;
 import android.app.ondeviceintelligence.Content;
 import android.app.ondeviceintelligence.Feature;
 import android.app.ondeviceintelligence.FeatureDetails;
 import android.app.ondeviceintelligence.IDownloadCallback;
 import android.app.ondeviceintelligence.IListFeaturesCallback;
 import android.app.ondeviceintelligence.IFeatureCallback;
 import android.app.ondeviceintelligence.IFeatureDetailsCallback;
 import android.app.ondeviceintelligence.IResponseCallback;
 import android.app.ondeviceintelligence.IStreamingResponseCallback;
 import android.app.ondeviceintelligence.IProcessingSignal;
 import android.app.ondeviceintelligence.ITokenCountCallback;


 /**
  * Interface for a OnDeviceIntelligenceManager for managing OnDeviceIntelligenceService and OnDeviceSandboxedInferenceService.
  *
  * @hide
  */
 oneway interface IOnDeviceIntelligenceManager {
      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void getVersion(in RemoteCallback remoteCallback) = 1;

      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void getFeature(in int featureId, in IFeatureCallback remoteCallback) = 2;

      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void listFeatures(in IListFeaturesCallback listFeaturesCallback) = 3;

      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void getFeatureDetails(in Feature feature, in IFeatureDetailsCallback featureDetailsCallback) = 4;

      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void requestFeatureDownload(in Feature feature, ICancellationSignal signal, in IDownloadCallback callback) = 5;

      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void requestTokenCount(in Feature feature, in Content request, in  ICancellationSignal signal,
                                                        in ITokenCountCallback tokenCountcallback) = 6;

      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void processRequest(in Feature feature, in Content request, int requestType, in  ICancellationSignal cancellationSignal, in IProcessingSignal signal,
                                                        in IResponseCallback responseCallback) = 7;

      @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)")
      void processRequestStreaming(in Feature feature,
                    in Content request, int requestType, in  ICancellationSignal cancellationSignal, in  IProcessingSignal signal,
                    in IStreamingResponseCallback streamingCallback) = 8;
 }
