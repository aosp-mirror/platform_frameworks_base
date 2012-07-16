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

package com.android.internal.location;

import android.location.Location;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.WorkSource;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;

/**
 * Binder interface for services that implement location providers.
 * <p>Use {@link LocationProviderBase} as a helper to implement this
 * interface.
 * @hide
 */
interface ILocationProvider {
    void enable();
    void disable();

    void setRequest(in ProviderRequest request, in WorkSource ws);

    // --- deprecated (but still supported) ---
    ProviderProperties getProperties();
    int getStatus(out Bundle extras);
    long getStatusUpdateTime();
    boolean sendExtraCommand(String command, inout Bundle extras);
}
