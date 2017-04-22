/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony.mbms;

import android.net.Uri;
import android.telephony.SignalStrength;

/**
 * A Callback class for use when the applicaiton is actively streaming content.
 * @hide
 */
public class StreamingServiceListener extends IStreamingServiceListener.Stub {


    public void error(int errorCode, String message) {
        // default implementation empty
    }

    /**
     * Called to indicate this stream has changed state.
     *
     * See {@link StreamingService#STATE_STOPPED}, {@link StreamingService#STATE_STARTED}
     * and {@link StreamingService#STATE_STALLED}.
     */
    public void stateUpdated(int state) {
        // default implementation empty
    }

    /**
     * Called to indicate published Download Services have changed.
     *
     * This may be called when a looping stream hits the end or
     * when the a new URI should be used to correct for time drift.
     */
    public void uriUpdated(Uri uri) {
        // default implementation empty
    }

    /**
     * Signal Strength updated.
     *
     * This signal strength is the BROADCAST signal strength which,
     * depending on technology in play and it's deployment, may be
     * stronger or weaker than the traditional UNICAST signal
     * strength.
     *
     * A {@link android.telephony.SignalStrength#getLevel} result of 0 means
     * you don't have coverage for this stream, either due to geographic
     * restrictions, poor tower coverage or something (yards of concrete?)
     * interferring with the signal.
     */
    public void signalStrengthUpdated(SignalStrength signalStrength) {
        // default implementation empty
    }
}
