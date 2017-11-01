/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Used for receiving notifications from the LocationManager when
 * the a batch of location is ready. These methods are called if the
 * BatchedLocationCallback has been registered with the location manager service
 * using the
 * {@link LocationManager#registerGnssBatchedLocationCallback#startGnssBatch(long,
 * boolean, BatchedLocationCallback, android.os.Handler)} method.
 * @hide
 */
@SystemApi
public abstract class BatchedLocationCallback {

    /**
     * Called when a new batch of locations is ready
     *
     * @param locations A list of all new locations (possibly zero of them.)
     */
    public void onLocationBatch(List<Location> locations) {}
}
