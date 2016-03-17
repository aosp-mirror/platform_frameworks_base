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

package android.net.wifi.nan;

/**
 * Base class for NAN events callbacks. Should be extended by applications
 * wanting notifications. These are callbacks applying to the NAN connection as
 * a whole - not to specific publish or subscribe sessions - for that see
 * {@link WifiNanSessionCallback}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanEventCallback {
    /**
     * Called when NAN configuration is completed.
     *
     * @param completedConfig The actual configuration request which was
     *            completed. Note that it may be different from that requested
     *            by the application. The service combines configuration
     *            requests from all applications.
     */
    public void onConfigCompleted(ConfigRequest completedConfig) {
        /* empty */
    }

    /**
     * Called when NAN configuration failed.
     *
     * @param reason Failure reason code, see
     *            {@code WifiNanSessionCallback.FAIL_*}.
     */
    public void onConfigFailed(@SuppressWarnings("unused") ConfigRequest failedConfig, int reason) {
        /* empty */
    }

    /**
     * Called when NAN cluster is down
     *
     * @param reason Reason code for event, see
     *            {@code WifiNanSessionCallback.FAIL_*}.
     */
    public void onNanDown(int reason) {
        /* empty */
    }

    /**
     * Called when NAN identity has changed. This may be due to joining a
     * cluster, starting a cluster, or discovery interface change. The
     * implication is that peers you've been communicating with may no longer
     * recognize you and you need to re-establish your identity.
     */
    public void onIdentityChanged() {
        /* empty */
    }
}
