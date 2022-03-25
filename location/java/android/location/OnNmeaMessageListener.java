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

package android.location;

import java.util.concurrent.Executor;

/**
 * Used for receiving NMEA sentences from the GNSS.
 * NMEA 0183 is a standard for communicating with marine electronic devices
 * and is a common method for receiving data from a GNSS, typically over a serial port.
 * See <a href="http://en.wikipedia.org/wiki/NMEA_0183">NMEA 0183</a> for more details.
 * You can implement this interface and call
 * {@link LocationManager#addNmeaListener(Executor, OnNmeaMessageListener)} to receive NMEA data
 * from the GNSS engine.
 */
public interface OnNmeaMessageListener {
    /**
     * Called when an NMEA message is received.
     * @param message NMEA message
     * @param timestamp Timestamp of the location fix, as reported by the GNSS chipset. The value
     *                  is specified in Unix time milliseconds since 1st January 1970, 00:00:00 UTC
     */
    void onNmeaMessage(String message, long timestamp);
}
