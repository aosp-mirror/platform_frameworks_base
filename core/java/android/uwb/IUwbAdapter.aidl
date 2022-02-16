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

import android.content.AttributionSource;
import android.os.PersistableBundle;
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
   * Get the accuracy of the ranging timestamps
   *
   * @return accuracy of the ranging timestamps in nanoseconds
   */
  long getTimestampResolutionNanos();

  /**
   * Provides the capabilities and features of the device
   *
   * @return specification specific capabilities and features of the device
   */
  PersistableBundle getSpecificationInfo();

  /**
   * Request to open a new ranging session
   *
   * This function does not start the ranging session, but all necessary
   * components must be initialized and ready to start a new ranging
   * session prior to calling IUwbAdapterCallback#onRangingOpened.
   *
   * IUwbAdapterCallbacks#onRangingOpened must be called within
   * RANGING_SESSION_OPEN_THRESHOLD_MS milliseconds of #openRanging being
   * called if the ranging session is opened successfully.
   *
   * IUwbAdapterCallbacks#onRangingOpenFailed must be called within
   * RANGING_SESSION_OPEN_THRESHOLD_MS milliseconds of #openRanging being called
   * if the ranging session fails to be opened.
   *
   * If the provided sessionHandle is already open for the calling client, then
   * #onRangingOpenFailed must be called and the new session must not be opened.
   *
   * @param attributionSource AttributionSource to use for permission enforcement.
   * @param sessionHandle the session handle to open ranging for
   * @param rangingCallbacks the callbacks used to deliver ranging information
   * @param parameters the configuration to use for ranging
   */
  void openRanging(in AttributionSource attributionSource,
                   in SessionHandle sessionHandle,
                   in IUwbRangingCallbacks rangingCallbacks,
                   in PersistableBundle parameters);

  /**
   * Request to start ranging
   *
   * IUwbAdapterCallbacks#onRangingStarted must be called within
   * RANGING_SESSION_START_THRESHOLD_MS milliseconds of #startRanging being
   * called if the ranging session starts successfully.
   *
   * IUwbAdapterCallbacks#onRangingStartFailed must be called within
   * RANGING_SESSION_START_THRESHOLD_MS milliseconds of #startRanging being
   * called if the ranging session fails to be started.
   *
   * @param sessionHandle the session handle to start ranging for
   * @param parameters additional configuration required to start ranging
   */
  void startRanging(in SessionHandle sessionHandle,
                    in PersistableBundle parameters);

  /**
   * Request to reconfigure ranging
   *
   * IUwbAdapterCallbacks#onRangingReconfigured must be called after
   * successfully reconfiguring the session.
   *
   * IUwbAdapterCallbacks#onRangingReconfigureFailed must be called after
   * failing to reconfigure the session.
   *
   * A session must not be modified by a failed call to #reconfigureRanging.
   *
   * @param sessionHandle the session handle to start ranging for
   * @param parameters the parameters to reconfigure and their new values
   */
  void reconfigureRanging(in SessionHandle sessionHandle,
                          in PersistableBundle parameters);

  /**
   * Request to stop ranging
   *
   * IUwbAdapterCallbacks#onRangingStopped must be called after
   * successfully stopping the session.
   *
   * IUwbAdapterCallbacks#onRangingStopFailed must be called after failing
   * to stop the session.
   *
   * @param sessionHandle the session handle to stop ranging for
   */
  void stopRanging(in SessionHandle sessionHandle);

  /**
   * Close ranging for the session associated with the given handle
   *
   * Calling with an invalid handle or a handle that has already been closed
   * is a no-op.
   *
   * IUwbAdapterCallbacks#onRangingClosed must be called within
   * RANGING_SESSION_CLOSE_THRESHOLD_MS of #closeRanging being called.
   *
   * @param sessionHandle the session handle to close ranging for
   */
  void closeRanging(in SessionHandle sessionHandle);

   /**
     * Disables or enables UWB for a user
     *
     * The provided callback's IUwbAdapterStateCallbacks#onAdapterStateChanged
     * function must be called immediately following state change.
     *
     * @param enabled value representing intent to disable or enable UWB. If
     * true, any subsequent calls to #openRanging will be allowed. If false,
     * all active ranging sessions will be closed and subsequent calls to
     * #openRanging will be disallowed.
     */
    void setEnabled(boolean enabled);

   /**
    * Returns the current enabled/disabled UWB state.
    *
    * Possible values are:
    * IUwbAdapterState#STATE_DISABLED
    * IUwbAdapterState#STATE_ENABLED_ACTIVE
    * IUwbAdapterState#STATE_ENABLED_INACTIVE
    *
    * @return value representing enabled/disabled UWB state.
    */
   int getAdapterState();

  /**
   * The maximum allowed time to open a ranging session.
   */
  const int RANGING_SESSION_OPEN_THRESHOLD_MS = 3000; // Value TBD

  /**
   * The maximum allowed time to start a ranging session.
   */
  const int RANGING_SESSION_START_THRESHOLD_MS = 3000; // Value TBD

  /**
   * The maximum allowed time to notify the framework that a session has been
   * closed.
   */
  const int RANGING_SESSION_CLOSE_THRESHOLD_MS = 3000; // Value TBD
}
