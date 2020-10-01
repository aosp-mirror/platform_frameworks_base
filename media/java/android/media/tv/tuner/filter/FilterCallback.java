/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Callback interface for receiving information from the corresponding filters.
 *
 * @hide
 */
@SystemApi
public interface FilterCallback {
    /**
     * Invoked when there are filter events.
     *
     * @param filter the corresponding filter which sent the events.
     * @param events the filter events sent from the filter.
     */
    void onFilterEvent(@NonNull Filter filter, @NonNull FilterEvent[] events);
    /**
     * Invoked when filter status changed.
     *
     * @param filter the corresponding filter whose status is changed.
     * @param status the new status of the filter.
     */
    void onFilterStatusChanged(@NonNull Filter filter, @Filter.Status int status);
}
