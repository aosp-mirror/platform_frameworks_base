/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.location.timezone;

import com.android.internal.location.timezone.ILocationTimeZoneProviderManager;
import com.android.internal.location.timezone.LocationTimeZoneProviderRequest;

/**
 * Binder interface for location time zone provider implementations. Do not implement this
 * directly, extend {@link com.android.location.timezone.provider.LocationTimeZoneProviderBase}
 * instead.
 * @hide
 */
interface ILocationTimeZoneProvider {

    oneway void setLocationTimeZoneProviderManager(in ILocationTimeZoneProviderManager manager);

    oneway void setRequest(in LocationTimeZoneProviderRequest request);
}
