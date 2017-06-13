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

/**
 * A Callback class for use when the application is actively streaming content.
 * @hide
 */
public class StreamingServiceCallback extends IStreamingServiceCallback.Stub {

    /**
     * Indicates broadcast signal strength is not available for this service.
     *
     * This may be due to the service no longer being available due to geography
     * or timing (end of service) or because lack of demand has caused the service
     * to be delivered via unicast.
     */
    public static final int SIGNAL_STRENGTH_UNAVAILABLE = -1;

    public void error(int errorCode, String message) {
        // default implementation empty
    }

    /**
     * Called to indicate this stream has changed state.
     *
     * See {@link StreamingService#STATE_STOPPED}, {@link StreamingService#STATE_STARTED}
     * and {@link StreamingService#STATE_STALLED}.
     */
    public void streamStateUpdated(int state) {
        // default implementation empty
    }

    /**
     * Called to indicate the mpd of a the stream has changed.
     *
     * Depending on the Dash Client it may need to be either reset
     * (less drastic, but original spec didn't allow mpd to change so not
     * always supported) or restarted.
     *
     * This may be called when a looping stream hits the end or
     * when parameters have changed to account for time drift.
     */
    public void mediaDescriptionUpdated() {
        // default implementation empty
    }

    /**
     * Broadcast Signal Strength updated.
     *
     * This signal strength is the BROADCAST signal strength which,
     * depending on technology in play and it's deployment, may be
     * stronger or weaker than the traditional UNICAST signal
     * strength.  It a simple int from 0-4 for valid levels or
     * {@link #SIGNAL_STRENGTH_UNAVAILABLE} if broadcast is not available
     * for this service due to timing, geography or popularity.
     */
    public void broadcastSignalStrengthUpdated(int signalStrength) {
        // default implementation empty
    }

    /**
     * Notify of bcast/unicast method being used.
     *
     * This is intended to be informational.  Indicates
     * whether we're able to use cell broadcast or have
     * had to fallback to unicast for this stream.
     *
     * This must be called once at the beginning of the stream
     * around the same time as we change to STATE_STARTED, but
     * strict ordering is not specified.  It must be called
     * again if we change modes, but if that doesn't happen
     * the callback won't be used again.
     *
     * See {@link StreamingService#BROADCAST_METHOD} and
     * {@link StreamingService#UNICAST_METHOD}
     */
    public void streamMethodUpdated(int methodType) {
        // default implementation empty
    }
}
