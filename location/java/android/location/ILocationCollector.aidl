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

package android.location;

import android.location.Location;

/**
 * Listens for GPS and cell/wifi changes and anonymously uploads to server 
 * for improving quality of service of NetworkLocationProvider. 
 * This service is only enabled when the user has enabled the 
 * network location provider.
 *
 * {@hide}
 */
oneway interface ILocationCollector {
    /**
     * Updates GPS location if collection is enabled
     *
     * @param location location object
     */
    void updateLocation(in Location location);
}
