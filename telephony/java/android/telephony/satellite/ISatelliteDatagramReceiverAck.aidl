/*
 * Copyright 2023 The Android Open Source Project
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

package android.telephony.satellite;

import android.telephony.satellite.PointingInfo;
import android.telephony.satellite.SatelliteDatagram;

/**
 * Interface for satellite datagram receiver acknowledgement.
 * @hide
 */
oneway interface ISatelliteDatagramReceiverAck {
     /**
      * This callback will be used by datagram receiver app to send ack back to
      * Telephony. If the callback is not received within five minutes,
      * then Telephony will resend the datagram again.
      *
      * @param datagramId An id that uniquely identifies datagram
      *                   received by satellite datagram receiver app.
      *                   This should match with datagramId passed in
      *                   {@link SatelliteDatagramCallback#onSatelliteDatagramReceived(
      *                   long, SatelliteDatagram, int, ISatelliteDatagramReceiverAck)}.
      *                   Upon receiving the ack, Telephony will remove the datagram from
      *                   the persistent memory.
      */
    void acknowledgeSatelliteDatagramReceived(in long datagramId);
}
