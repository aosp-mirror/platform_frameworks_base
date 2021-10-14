/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.location.provider;

import android.os.Bundle;

import android.location.provider.ILocationProviderManager;
import android.location.provider.ProviderRequest;

/**
 * Binder interface for services that implement location providers. Do not implement this directly,
 * extend {@link LocationProviderBase} instead.
 * @hide
 */
interface ILocationProvider {

    oneway void setLocationProviderManager(in ILocationProviderManager manager);
    oneway void setRequest(in ProviderRequest request);
    oneway void flush();
    oneway void sendExtraCommand(String command, in Bundle extras);
}
