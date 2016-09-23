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
 * Base class for NAN attach callbacks. Should be extended by applications and set when calling
 * {@link WifiNanManager#attach(android.os.Handler, WifiNanAttachCallback)}. These are callbacks
 * applying to the NAN connection as a whole - not to specific publish or subscribe sessions -
 * for that see {@link WifiNanDiscoverySessionCallback}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanAttachCallback {
    /** @hide */
    @IntDef({
            REASON_INVALID_ARGS, REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG, REASON_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventReasonCodes {
    }

    /**
     * Indicates invalid argument in the requested operation. Failure reason flag for
     * {@link WifiNanAttachCallback#onAttachFailed(int)}.
     */
    public static final int REASON_INVALID_ARGS = 1000;

    /**
     * Indicates that a {@link ConfigRequest} passed in
     * {@code WifiNanManager#attach(android.os.Handler, ConfigRequest, WifiNanAttachCallback)}
     * couldn't be applied since other connections already exist with an incompatible
     * configurations. Failure reason flag for {@link WifiNanAttachCallback#onAttachFailed(int)}.
     */
    public static final int REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG = 1001;

    /**
     * Indicates an unspecified error occurred during the operation. Failure reason flag for
     * {@link WifiNanAttachCallback#onAttachFailed(int)}.
     */
    public static final int REASON_OTHER = 1002;

    /**
     * Called when NAN attach operation
     * {@link WifiNanManager#attach(android.os.Handler, WifiNanAttachCallback)}
     * is completed and that we can now start discovery sessions or connections.
     *
     * @param session The NAN object on which we can execute further NAN operations - e.g.
     *                discovery, connections.
     */
    public void onAttached(WifiNanSession session) {
        /* empty */
    }

    /**
     * Called when NAN attach operation
     * {@link WifiNanManager#attach(android.os.Handler, WifiNanAttachCallback)} failed.
     *
     * @param reason Failure reason code, see
     *            {@code WifiNanEventCallback.REASON_*}.
     */
    public void onAttachFailed(@EventReasonCodes int reason) {
        /* empty */
    }
}
