/*
 * Copyright 2020 The Android Open Source Project
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

import android.annotation.SystemApi;

/**
 * An Event that the client would reveice after stopping, reconfiguring and restarting a filter.
 *
 * <p>After stopping and restarting the filter, the client has to discard all coming events until
 * it receives {@link RestartEvent} to avoid using the events from the previous configuration.
 *
 * <p>Recofiguring must happen after stopping the filter.
 *
 * @hide
 */
@SystemApi
public final class RestartEvent extends FilterEvent {
    private final int mStartId;

    // This constructor is used by JNI code only
    private RestartEvent(int startId) {
        mStartId = startId;
    }

    /**
     * Gets the start id.
     *
     * <p>An unique ID to mark the start point of receiving the valid filter events after
     * reconfiguring. It must be sent at least once in the first event after the filter is
     * restarted.
     *
     * <p>0 is reserved for the newly opened filter's first start. It's optional to be received.
     */
    public int getStartId() {
        return mStartId;
    }
}
