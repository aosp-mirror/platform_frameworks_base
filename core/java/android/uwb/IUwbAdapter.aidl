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

package android.uwb;

import android.os.PersistableBundle;
import android.uwb.AngleOfArrivalSupport;
import android.uwb.IUwbAdapterStateCallbacks;
import android.uwb.IUwbRangingCallbacks;
import android.uwb.SessionHandle;

/**
 * @hide
 */
interface IUwbAdapter {
  /*
   * Register the callbacks used to notify the framework of events and data
   *
   * The provided callback's IUwbAdapterStateCallbacks#onAdapterStateChanged
   * function must be called immediately following registration with the current
   * state of the UWB adapter.
   *
   * @param callbacks callback to provide range and status updates to the framework
   */
  void registerAdapterStateCallbacks(in IUwbAdapterStateCallbacks adapterStateCallbacks);

  /*
   * Unregister the callbacks used to notify the framework of events and data
   *
   * Calling this function with an unregistered callback is a no-op
   *
   * @param callbacks callback to unregister
   */
  void unregisterAdapterStateCallbacks(in IUwbAdapterStateCallbacks callbacks);

  /**
   * Returns true if ranging is supported, false otherwise
   */
  boolean isRangingSupported();

  /**
   * Get the angle of arrival supported by this device
   *
   * @return the angle of arrival type supported
   */
  AngleOfArrivalSupport getAngleOfArrivalSupport();

  /**
   * Generates a list of the supported 802.15.4z channels
   *
   * The list must be prioritized in the order of preferred channel usage.
   *
   * The list must only contain channels that are permitted to be used in the
   * device's current location.
   *
   * @return an array of support channels on the device for the current location.
   */
  int[] getSupportedChannels();

  /**
   * Generates a list of the supported 802.15.4z preamble codes
   *
   * The list must be prioritized in the order of preferred preamble usage.
   *
   * The list must only contain preambles that are permitted to be used in the
   * device's current location.
   *
   * @return an array of supported preambles on the device for the current
   *         location.
   */
  int[] getSupportedPreambleCodes();

  /**
   * Get the accuracy of the ranging timestamps
   *
   * @return accuracy of the ranging timestamps in nanoseconds
   */
  long getTimestampResolutionNanos();

  /**
   * Get the supported number of simultaneous ranging sessions
   *
   * @return the supported number of simultaneous ranging sessions
   */
  int getMaxSimultaneousSessions();

  /**
   * Get the maximum number of remote devices per session when local device is initiator
   *
   * @return the maximum number of remote devices supported in a single session
   */
  int getMaxRemoteDevicesPerInitiatorSession();

  /**
   * Get the maximum number of remote devices per session when local device is responder
   *
   * @return the maximum number of remote devices supported in a single session
   */
  int getMaxRemoteDevicesPerResponderSession();

  /**
   * Provides the capabilities and features of the device
   *
   * @return specification specific capabilities and features of the device
   */
  PersistableBundle getSpecificationInfo();

  /**
   * Request to start a new ranging session
   *
   * This function must return before calling IUwbAdapterCallbacks
   * #onRangingStarted, #onRangingClosed, or #onRangingResult.
   *
   * A ranging session does not need to be started before returning.
   *
   * IUwbAdapterCallbacks#onRangingStarted must be called within
   * RANGING_SESSION_START_THRESHOLD_MS milliseconds of #startRanging being called
   * if the ranging session is scheduled to start successfully.
   *
   * IUwbAdapterCallbacks#onRangingStartFailed must be called within
   * RANGING_SESSION_START_THRESHOLD_MS milliseconds of #startRanging being called
   * if the ranging session fails to be scheduled to start successfully.
   *
   * @param rangingCallbacks the callbacks used to deliver ranging information
   * @param parameters the configuration to use for ranging
   * @return a SessionHandle used to identify this ranging request
   */
  SessionHandle startRanging(in IUwbRangingCallbacks rangingCallbacks,
                             in PersistableBundle parameters);

  /**
   * Stop and close ranging for the session associated with the given handle
   *
   * Calling with an invalid handle or a handle that has already been closed
   * is a no-op.
   *
   * IUwbAdapterCallbacks#onRangingClosed must be called within
   * RANGING_SESSION_CLOSE_THRESHOLD_MS of #stopRanging being called.
   *
   * @param sessionHandle the session handle to stop ranging for
   */
  void closeRanging(in SessionHandle sessionHandle);

  /**
   * The maximum allowed time to start a ranging session.
   */
  const int RANGING_SESSION_START_THRESHOLD_MS = 3000; // Value TBD

  /**
   * The maximum allowed time to notify the framework that a session has been
   * closed.
   */
  const int RANGING_SESSION_CLOSE_THRESHOLD_MS = 3000; // Value TBD

  /**
   * Ranging scheduling time unit (RSTU) for High Rate Pulse (HRP) PHY
   */
  const int HIGH_RATE_PULSE_CHIRPS_PER_RSTU = 416;

  /**
   * Ranging scheduling time unit (RSTU) for Low Rate Pulse (LRP) PHY
   */
  const int LOW_RATE_PULSE_CHIRPS_PER_RSTU = 1;
}
