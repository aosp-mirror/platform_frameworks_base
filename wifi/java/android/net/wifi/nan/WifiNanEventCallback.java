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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for NAN events callbacks. Should be extended by applications and set when calling
 * {@link WifiNanManager#connect(android.os.Looper, WifiNanEventCallback)}. These are callbacks
 * applying to the NAN connection as a whole - not to specific publish or subscribe sessions -
 * for that see {@link WifiNanSessionCallback}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanEventCallback {
    /** @hide */
    @IntDef({
            REASON_INVALID_ARGS, REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG, REASON_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventReasonCodes {
    }

    /**
     * Indicates invalid argument in the requested operation. Failure reason flag for
     * {@link WifiNanEventCallback#onConnectFail(int)}.
     */
    public static final int REASON_INVALID_ARGS = 1000;

    /**
     * Indicates that a {@link ConfigRequest} passed in
     * {@link WifiNanManager#connect(android.os.Looper, WifiNanEventCallback, ConfigRequest)}
     * couldn't be applied since other connections already exist with an incompatible
     * configurations. Failure reason flag for {@link WifiNanEventCallback#onConnectFail(int)}.
     */
    public static final int REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG = 1001;

    /**
     * Indicates an unspecified error occurred during the operation. Failure reason flag for
     * {@link WifiNanEventCallback#onConnectFail(int)}.
     */
    public static final int REASON_OTHER = 1002;

    /**
     * Called when NAN connect operation
     * {@link WifiNanManager#connect(android.os.Looper, WifiNanEventCallback)}
     * is completed and that we can now start discovery sessions or connections.
     */
    public void onConnectSuccess() {
        /* empty */
    }

    /**
     * Called when NAN connect operation
     * {@link WifiNanManager#connect(android.os.Looper, WifiNanEventCallback)} failed.
     *
     * @param reason Failure reason code, see
     *            {@code WifiNanEventCallback.REASON_*}.
     */
    public void onConnectFail(@EventReasonCodes int reason) {
        /* empty */
    }

    /**
     * Called when NAN identity (the MAC address representing our NAN discovery interface) has
     * changed. Change may be due to device joining a cluster, starting a cluster, or discovery
     * interface change (addresses are randomized at regular intervals). The implication is that
     * peers you've been communicating with may no longer recognize you and you need to
     * re-establish your identity - e.g. by starting a discovery session. This actual MAC address
     * of the interface may also be useful if the application uses alternative (non-NAN)
     * discovery but needs to set up a NAN connection. The provided NAN discovery interface MAC
     * address can then be used in
     * {@link WifiNanManager#createNetworkSpecifier(int, byte[], byte[])}.
     * <p>
     *     This callback is only called if the NAN connection enables it using
     *     {@link ConfigRequest.Builder#setEnableIdentityChangeCallback(boolean)} in
     *     {@link WifiNanManager#connect(android.os.Looper, WifiNanEventCallback, ConfigRequest)}
     *     . It is disabled by default since it may result in additional wake-ups of the host -
     *     increasing power.
     *
     * @param mac The MAC address of the NAN discovery interface. The application must have the
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION} to get the actual MAC address,
     *            otherwise all 0's will be provided.
     */
    public void onIdentityChanged(byte[] mac) {
        /* empty */
    }
}
