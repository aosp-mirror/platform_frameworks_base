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

import com.android.internal.location.ProviderProperties;

/**
 * Binder interface for manager of all location providers.
 * @hide
 */
interface ILocationProviderManager {

    void onSetAdditionalProviderPackages(in List<String> packageNames);

    void onSetEnabled(boolean enabled);

    void onSetProperties(in ProviderProperties properties);

    void onReportLocation(in Location location);
}
